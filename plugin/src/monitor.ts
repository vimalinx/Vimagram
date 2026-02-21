import type { IncomingMessage, ServerResponse } from "node:http";
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { hostname } from "node:os";

import type { ResolvedTestAccount } from "./accounts.js";
import { handleTestInbound } from "./inbound.js";
import {
  clearRegisteredMachineProfile,
  setRegisteredMachineProfile,
  type RegisteredMachineProfile,
} from "./machine-state.js";
import {
  checkAndStoreNonce,
  checkGlobalRateLimit,
  checkSenderRateLimit,
  createTestSignature,
  generateNonce,
  isIpAllowed,
  isTimestampFresh,
  resolveRequestIp,
  resolveTestSecurityConfig,
  verifyTestSignature,
} from "./security.js";
import type { TestConfig, TestInboundMessage, TestInboundPayload } from "./types.js";

const DEFAULT_WEBHOOK_PATH = "/vimalinx-webhook";
const DEFAULT_POLL_INTERVAL_MS = 1500;
const DEFAULT_POLL_WAIT_MS = 20000;
const MAX_POLL_WAIT_MS = 30000;
const DEFAULT_MACHINE_HEARTBEAT_MS = 30000;
const DEFAULT_MINIMAX_MODEL_ID = "MiniMax-M2.5";

const providerSyncFingerprint = new Map<string, string>();
const providerSyncInFlight = new Map<string, Promise<void>>();

type WebhookTarget = {
  account: ResolvedTestAccount;
  config: TestConfig;
  runtime: { log?: (message: string) => void; error?: (message: string) => void };
  abortSignal: AbortSignal;
  statusSink?: (patch: { lastInboundAt?: number; lastOutboundAt?: number }) => void;
  path: string;
  expectedToken?: string;
};

const webhookTargets = new Map<string, WebhookTarget[]>();

function normalizeWebhookPath(path?: string): string {
  const trimmed = path?.trim();
  if (!trimmed) return DEFAULT_WEBHOOK_PATH;
  let normalized = trimmed.startsWith("/") ? trimmed : `/${trimmed}`;
  if (normalized.length > 1 && normalized.endsWith("/")) {
    normalized = normalized.slice(0, -1);
  }
  return normalized;
}

function normalizeInboundMode(value?: string): "webhook" | "poll" {
  return value?.toLowerCase() === "poll" ? "poll" : "webhook";
}

function resolvePollIntervalMs(account: ResolvedTestAccount): number {
  const raw = Number(account.config.pollIntervalMs);
  if (Number.isFinite(raw) && raw >= 250) {
    return Math.floor(raw);
  }
  return DEFAULT_POLL_INTERVAL_MS;
}

function resolvePollWaitMs(account: ResolvedTestAccount): number {
  const raw = Number(account.config.pollWaitMs);
  if (!Number.isFinite(raw)) return DEFAULT_POLL_WAIT_MS;
  const clamped = Math.max(1000, Math.min(Math.floor(raw), MAX_POLL_WAIT_MS));
  return clamped;
}

function resolveUserId(account: ResolvedTestAccount): string {
  const userId = account.config.userId?.trim();
  if (userId) return userId;
  return account.accountId;
}

function buildBaseUrl(baseUrl: string): string {
  return baseUrl.endsWith("/") ? baseUrl : `${baseUrl}/`;
}

function buildPollUrl(baseUrl: string, userId: string, waitMs: number): string {
  const url = new URL("api/poll", buildBaseUrl(baseUrl));
  url.searchParams.set("userId", userId);
  url.searchParams.set("waitMs", String(waitMs));
  return url.toString();
}

function buildMachineApiUrl(baseUrl: string, path: string): string {
  return new URL(path, buildBaseUrl(baseUrl)).toString();
}

function shouldAutoRegisterMachine(account: ResolvedTestAccount): boolean {
  return account.config.autoRegisterMachine !== false;
}

function resolveMachineHeartbeatMs(account: ResolvedTestAccount): number {
  const raw = Number(account.config.machineHeartbeatMs);
  if (!Number.isFinite(raw)) return DEFAULT_MACHINE_HEARTBEAT_MS;
  return Math.max(5000, Math.min(Math.floor(raw), 300000));
}

function normalizeMachineId(value?: string): string | undefined {
  const normalized = value?.trim().toLowerCase();
  if (!normalized) return undefined;
  if (!/^[a-z0-9][a-z0-9_-]{2,63}$/.test(normalized)) return undefined;
  return normalized;
}

function resolveMachineId(account: ResolvedTestAccount, userId: string): string {
  const explicit = normalizeMachineId(account.config.machineId);
  if (explicit) return explicit;
  const source = `${hostname()}:${userId}:${account.accountId}`;
  const digest = createHash("sha1").update(source).digest("hex").slice(0, 20);
  return `m_${digest}`;
}

function resolveMachineLabel(account: ResolvedTestAccount, userId: string): string {
  const configured = account.config.machineLabel?.trim();
  if (configured) return configured.slice(0, 80);
  return `${hostname()}:${userId}:${account.accountId}`.slice(0, 80);
}

function asRecord(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === "object" ? (value as Record<string, unknown>) : undefined;
}

function normalizeModeMapFromResponse(value: unknown): Record<string, string> | undefined {
  const source = asRecord(value);
  if (!source) return undefined;
  const next: Record<string, string> = {};
  for (const [rawMode, rawTarget] of Object.entries(source)) {
    const modeId = rawMode.trim().toLowerCase();
    if (!/^[a-z0-9_-]{1,32}$/.test(modeId)) continue;
    const target = typeof rawTarget === "string" ? rawTarget.trim().toLowerCase() : "";
    if (!/^[a-z0-9_-]{1,64}$/.test(target)) continue;
    next[modeId] = target;
  }
  return Object.keys(next).length > 0 ? next : undefined;
}

function normalizeModeHintMapFromResponse(
  value: unknown,
  maxLength: number,
): Record<string, string> | undefined {
  const source = asRecord(value);
  if (!source) return undefined;
  const next: Record<string, string> = {};
  for (const [rawMode, rawHint] of Object.entries(source)) {
    const modeId = rawMode.trim().toLowerCase();
    if (!/^[a-z0-9_-]{1,32}$/.test(modeId)) continue;
    const hint = typeof rawHint === "string" ? rawHint.trim() : "";
    if (!hint) continue;
    next[modeId] = hint.slice(0, maxLength);
  }
  return Object.keys(next).length > 0 ? next : undefined;
}

function normalizeMinimaxEndpointFromResponse(value: unknown): "global" | "cn" | undefined {
  if (typeof value !== "string") return undefined;
  const normalized = value.trim().toLowerCase();
  if (normalized === "global") return "global";
  if (normalized === "cn") return "cn";
  return undefined;
}

function normalizeMinimaxModelIdFromResponse(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  const normalized = value.trim();
  if (!normalized) return undefined;
  if (!/^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/.test(normalized)) return undefined;
  return normalized;
}

function normalizeMinimaxApiKeyFromResponse(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  const normalized = value.trim();
  if (!normalized) return undefined;
  if (normalized.length > 512) return undefined;
  return normalized;
}

function parseProviderSyncFromResponse(value: unknown): RegisteredMachineProfile["providerSync"] {
  const source = asRecord(value);
  if (!source) return undefined;
  const minimaxSource = asRecord(source.minimax);
  if (!minimaxSource) return undefined;
  const endpoint = normalizeMinimaxEndpointFromResponse(minimaxSource.endpoint);
  const modelId = normalizeMinimaxModelIdFromResponse(minimaxSource.modelId);
  const apiKey = normalizeMinimaxApiKeyFromResponse(minimaxSource.apiKey);
  if (!apiKey) return undefined;
  return {
    minimax: {
      endpoint,
      modelId,
      apiKey,
    },
  };
}

function parseRegisteredMachineProfile(payload: unknown): RegisteredMachineProfile | null {
  const root = asRecord(payload);
  const machine = asRecord(root?.machine);
  const config = asRecord(root?.config);
  const routing = asRecord(config?.routing);
  const providerSync = parseProviderSyncFromResponse(config?.providerSync);
  const machineIdRaw = machine?.machineId;
  const machineId = typeof machineIdRaw === "string" ? normalizeMachineId(machineIdRaw) : undefined;
  if (!machineId) return null;
  const parsedRouting =
    routing
      ? {
          modeAccountMap: normalizeModeMapFromResponse(routing.modeAccountMap),
          modeModelHints: normalizeModeHintMapFromResponse(routing.modeModelHints, 120),
          modeAgentHints: normalizeModeHintMapFromResponse(routing.modeAgentHints, 120),
          modeSkillsHints: normalizeModeHintMapFromResponse(routing.modeSkillsHints, 160),
        }
      : undefined;
  const updatedAt = typeof machine.updatedAt === "number" ? machine.updatedAt : undefined;
  const lastSeenAt = typeof machine.lastSeenAt === "number" ? machine.lastSeenAt : undefined;
  return {
    machineId,
    routing: parsedRouting,
    providerSync,
    updatedAt,
    lastSeenAt,
  };
}

async function runOpenclawCommand(args: string[]): Promise<void> {
  await new Promise<void>((resolve, reject) => {
    const child = spawn("openclaw", args, {
      stdio: ["ignore", "pipe", "pipe"],
      env: process.env,
    });
    let stderr = "";
    child.stderr.on("data", (chunk: Buffer | string) => {
      if (stderr.length > 4000) return;
      stderr += String(chunk);
    });
    child.on("error", reject);
    child.on("close", (code) => {
      if (code === 0) {
        resolve();
        return;
      }
      const detail = stderr.trim();
      reject(new Error(detail || `openclaw ${args.join(" ")} exited with code ${String(code ?? "?")}`));
    });
  });
}

function resolveMinimaxProviderId(endpoint: "global" | "cn"): string {
  return endpoint === "cn" ? "minimax-cn" : "minimax";
}

function resolveMinimaxBaseUrl(endpoint: "global" | "cn"): string {
  return endpoint === "cn"
    ? "https://api.minimaxi.com/anthropic"
    : "https://api.minimax.io/anthropic";
}

function buildMinimaxModelsPayload(preferredModelId: string): string {
  const ids = [preferredModelId, DEFAULT_MINIMAX_MODEL_ID, "MiniMax-M2.5-Lightning"];
  const uniqueIds = [...new Set(ids.map((value) => value.trim()).filter(Boolean))];
  const models = uniqueIds.map((id) => ({
    id,
    name: id,
    reasoning: true,
    input: ["text"],
    cost: { input: 15, output: 60, cacheRead: 2, cacheWrite: 10 },
    contextWindow: 200000,
    maxTokens: 8192,
  }));
  return JSON.stringify(models);
}

async function applyMinimaxProviderSync(params: {
  account: ResolvedTestAccount;
  minimax: NonNullable<RegisteredMachineProfile["providerSync"]>["minimax"];
}): Promise<void> {
  const apiKey = params.minimax?.apiKey?.trim();
  if (!apiKey) return;
  const endpoint = params.minimax.endpoint === "global" ? "global" : "cn";
  const providerId = resolveMinimaxProviderId(endpoint);
  const baseUrl = resolveMinimaxBaseUrl(endpoint);
  const modelId = params.minimax.modelId?.trim() || DEFAULT_MINIMAX_MODEL_ID;
  await runOpenclawCommand(["config", "set", "models.mode", "merge"]);
  await runOpenclawCommand([
    "config",
    "set",
    `models.providers.${providerId}.baseUrl`,
    baseUrl,
  ]);
  await runOpenclawCommand([
    "config",
    "set",
    `models.providers.${providerId}.api`,
    "anthropic-messages",
  ]);
  await runOpenclawCommand([
    "config",
    "set",
    `models.providers.${providerId}.apiKey`,
    apiKey,
  ]);
  await runOpenclawCommand([
    "config",
    "set",
    `models.providers.${providerId}.models`,
    buildMinimaxModelsPayload(modelId),
  ]);
}

function scheduleProviderSyncForProfile(params: {
  account: ResolvedTestAccount;
  profile: RegisteredMachineProfile;
  runtime: { log?: (message: string) => void; error?: (message: string) => void };
}): void {
  const accountId = params.account.accountId;
  const minimax = params.profile.providerSync?.minimax;
  const apiKey = minimax?.apiKey?.trim();
  if (!apiKey) {
    providerSyncFingerprint.delete(accountId);
    return;
  }
  const endpoint = minimax.endpoint === "global" ? "global" : "cn";
  const modelId = minimax.modelId?.trim() || DEFAULT_MINIMAX_MODEL_ID;
  const fingerprint = `${endpoint}:${modelId}:${apiKey}`;
  if (providerSyncFingerprint.get(accountId) === fingerprint) {
    return;
  }
  if (providerSyncInFlight.has(accountId)) {
    return;
  }
  const task =
    (async () => {
      try {
        await applyMinimaxProviderSync({ account: params.account, minimax });
        providerSyncFingerprint.set(accountId, fingerprint);
        params.runtime.log?.(
          `vimalinx provider sync applied for ${accountId}: minimax endpoint=${endpoint} model=${modelId}`,
        );
      } catch (err) {
        params.runtime.error?.(`vimalinx provider sync failed for ${accountId}: ${String(err)}`);
      }
    })().finally(() => {
      providerSyncInFlight.delete(accountId);
    });
  providerSyncInFlight.set(accountId, task);
}

async function callMachineEndpoint(params: {
  account: ResolvedTestAccount;
  path: string;
  body: Record<string, unknown>;
  security: ReturnType<typeof resolveTestSecurityConfig>;
  abortSignal: AbortSignal;
}): Promise<unknown> {
  const baseUrl = params.account.baseUrl;
  if (!baseUrl) throw new Error("baseUrl is not configured");
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
  };
  const token = params.account.token ?? params.account.webhookToken;
  if (token) headers.Authorization = `Bearer ${token}`;
  const body = JSON.stringify(params.body);
  if (params.security.signOutbound && params.security.hmacSecret) {
    const timestamp = Date.now();
    const nonce = generateNonce();
    const signature = createTestSignature({
      secret: params.security.hmacSecret,
      timestamp,
      nonce,
      body,
    });
    headers["x-vimalinx-timestamp"] = String(timestamp);
    headers["x-vimalinx-nonce"] = nonce;
    headers["x-vimalinx-signature"] = signature;
  }
  const response = await fetch(buildMachineApiUrl(baseUrl, params.path), {
    method: "POST",
    headers,
    body,
    signal: params.abortSignal,
  });
  const parsed = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = asRecord(parsed)?.error;
    const message = typeof error === "string" && error ? error : `HTTP ${response.status}`;
    throw new Error(message);
  }
  return parsed;
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function registerTestWebhookTarget(target: WebhookTarget): () => void {
  const path = normalizeWebhookPath(target.path);
  const normalizedTarget = { ...target, path };
  const existing = webhookTargets.get(path) ?? [];
  const next = [...existing, normalizedTarget];
  webhookTargets.set(path, next);
  return () => {
    const updated = (webhookTargets.get(path) ?? []).filter((entry) => entry !== normalizedTarget);
    if (updated.length > 0) {
      webhookTargets.set(path, updated);
    } else {
      webhookTargets.delete(path);
    }
  };
}

function readRequestBody(req: IncomingMessage, maxBytes: number): Promise<string> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    let size = 0;
    req.on("data", (chunk: Buffer) => {
      size += chunk.length;
      if (size > maxBytes) {
        reject(new Error("payload too large"));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf-8")));
    req.on("error", reject);
  });
}

function readTokenFromHeaders(req: IncomingMessage): string | undefined {
  const authHeader = String(req.headers.authorization ?? "");
  if (authHeader.toLowerCase().startsWith("bearer ")) {
    return authHeader.slice("bearer ".length).trim();
  }
  const direct = req.headers["x-vimalinx-token"];
  return typeof direct === "string" ? direct.trim() : undefined;
}

function readTokenFromRequest(
  payload: TestInboundPayload,
  req: IncomingMessage,
  allowQueryToken: boolean,
): { token?: string; tokenFromQuery?: string } {
  const tokenFromQuery = new URL(req.url ?? "", "http://localhost").searchParams.get("token");
  const tokenFromHeader = readTokenFromHeaders(req);
  const tokenFromPayload = typeof payload.token === "string" ? payload.token.trim() : "";
  if (tokenFromHeader) return { token: tokenFromHeader };
  if (allowQueryToken && tokenFromQuery) return { token: tokenFromQuery.trim(), tokenFromQuery: tokenFromQuery.trim() };
  if (tokenFromPayload) return { token: tokenFromPayload };
  return { token: undefined, tokenFromQuery: allowQueryToken ? tokenFromQuery?.trim() : undefined };
}

function resolveMatchingTarget(
  targets: WebhookTarget[],
  token?: string,
): WebhookTarget | null {
  if (targets.length === 1) return targets[0];
  if (!token) {
    const openTarget = targets.find((target) => !target.expectedToken);
    return openTarget ?? null;
  }
  return targets.find((target) => target.expectedToken === token) ?? null;
}

function parseInboundMessage(payload: TestInboundPayload): TestInboundMessage | null {
  const source = payload.message && typeof payload.message === "object" ? payload.message : payload;
  const chatId = typeof source.chatId === "string" ? source.chatId.trim() : "";
  const senderId = typeof source.senderId === "string" ? source.senderId.trim() : "";
  const text = typeof source.text === "string" ? source.text : "";
  if (!chatId || !senderId || !text) return null;

  const chatType = source.chatType === "group" ? "group" : "dm";
  const senderName = typeof source.senderName === "string" ? source.senderName.trim() : undefined;
  const chatName = typeof source.chatName === "string" ? source.chatName.trim() : undefined;
  const mentioned = typeof source.mentioned === "boolean" ? source.mentioned : undefined;
  const timestamp = typeof source.timestamp === "number" ? source.timestamp : undefined;
  const id = typeof source.id === "string" ? source.id.trim() : undefined;
  const modeId =
    typeof source.modeId === "string" && source.modeId.trim()
      ? source.modeId.trim().toLowerCase()
      : undefined;
  const modeLabel = typeof source.modeLabel === "string" ? source.modeLabel.trim() : undefined;
  const modelHint = typeof source.modelHint === "string" ? source.modelHint.trim() : undefined;
  const agentHint = typeof source.agentHint === "string" ? source.agentHint.trim() : undefined;
  const skillsHint = typeof source.skillsHint === "string" ? source.skillsHint.trim() : undefined;

  return {
    id,
    chatId,
    chatName,
    chatType,
    senderId,
    senderName,
    text,
    mentioned,
    timestamp,
    modeId,
    modeLabel,
    modelHint,
    agentHint,
    skillsHint,
  };
}

function isHttpsUrl(value: string): boolean {
  try {
    return new URL(value).protocol === "https:";
  } catch {
    return false;
  }
}

async function pollServerOnce(params: {
  baseUrl: string;
  userId: string;
  token?: string;
  waitMs: number;
  abortSignal: AbortSignal;
  security: ReturnType<typeof resolveTestSecurityConfig>;
}): Promise<TestInboundMessage[]> {
  const url = buildPollUrl(params.baseUrl, params.userId, params.waitMs);
  const headers: Record<string, string> = {};
  if (params.token) {
    headers.Authorization = `Bearer ${params.token}`;
  }
  if (params.security.signOutbound && params.security.hmacSecret) {
    const timestamp = Date.now();
    const nonce = generateNonce();
    const signature = createTestSignature({
      secret: params.security.hmacSecret,
      timestamp,
      nonce,
      body: "",
    });
    headers["x-vimalinx-timestamp"] = String(timestamp);
    headers["x-vimalinx-nonce"] = nonce;
    headers["x-vimalinx-signature"] = signature;
  }

  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), params.waitMs + 5000);
  const onAbort = () => controller.abort();
  params.abortSignal.addEventListener("abort", onAbort);

  try {
    const response = await fetch(url, {
      method: "GET",
      headers,
      signal: controller.signal,
    });
    if (!response.ok) {
      throw new Error(`poll failed (${response.status} ${response.statusText})`);
    }
    const data = (await response.json().catch(() => ({}))) as {
      messages?: unknown;
    };
    const messages = Array.isArray(data.messages)
      ? data.messages.filter((entry) => entry && typeof entry === "object")
      : [];
    return messages as TestInboundMessage[];
  } catch (err) {
    if (controller.signal.aborted) {
      return [];
    }
    throw err;
  } finally {
    clearTimeout(timeout);
    params.abortSignal.removeEventListener("abort", onAbort);
  }
}

async function startTestPoller(params: {
  account: ResolvedTestAccount;
  config: TestConfig;
  runtime: { log?: (message: string) => void; error?: (message: string) => void };
  abortSignal: AbortSignal;
  statusSink?: (patch: { lastInboundAt?: number; lastOutboundAt?: number }) => void;
}): Promise<() => void> {
  const baseUrl = params.account.baseUrl;
  if (!baseUrl) {
    params.runtime.error?.("vimalinx poller: baseUrl is not configured.");
    return () => {};
  }
  const security = resolveTestSecurityConfig(params.account.config.security);
  if (security.requireHttps && !isHttpsUrl(baseUrl)) {
    params.runtime.error?.("vimalinx poller: baseUrl must use https when requireHttps is enabled.");
    return () => {};
  }
  const userId = resolveUserId(params.account);
  const token = params.account.token ?? params.account.webhookToken;
  const pollIntervalMs = resolvePollIntervalMs(params.account);
  const pollWaitMs = resolvePollWaitMs(params.account);

  params.runtime.log?.(
    `vimalinx poller: user=${userId} wait=${pollWaitMs}ms interval=${pollIntervalMs}ms`,
  );

  let stopped = false;
  const loop = async () => {
    while (!stopped && !params.abortSignal.aborted) {
      try {
        const messages = await pollServerOnce({
          baseUrl,
          userId,
          token,
          waitMs: pollWaitMs,
          abortSignal: params.abortSignal,
          security,
        });

        if (params.abortSignal.aborted || stopped) break;

        if (messages.length === 0) {
          await delay(pollIntervalMs);
          continue;
        }

        for (const message of messages) {
          await handleTestInbound({
            message,
            account: params.account,
            config: params.config,
            runtime: params.runtime,
            statusSink: params.statusSink,
            rateLimitChecked: false,
          });
        }
      } catch (err) {
        if (!params.abortSignal.aborted && !stopped) {
          params.runtime.error?.(`vimalinx poller: ${String(err)}`);
          await delay(pollIntervalMs);
        }
      }
    }
  };

  void loop();

  return () => {
    stopped = true;
  };
}

async function registerMachineForAccount(params: {
  account: ResolvedTestAccount;
  runtime: { log?: (message: string) => void; error?: (message: string) => void };
  abortSignal: AbortSignal;
}): Promise<RegisteredMachineProfile | null> {
  if (!shouldAutoRegisterMachine(params.account)) return null;
  if (!params.account.baseUrl) return null;
  const token = params.account.token ?? params.account.webhookToken;
  if (!token) return null;

  const userId = resolveUserId(params.account);
  const machineId = resolveMachineId(params.account, userId);
  const security = resolveTestSecurityConfig(params.account.config.security);
  try {
    const payload = await callMachineEndpoint({
      account: params.account,
      path: "api/machine/register",
      body: {
        userId,
        token,
        machineId,
        accountId: params.account.accountId,
        machineLabel: resolveMachineLabel(params.account, userId),
        hostName: hostname(),
        platform: process.platform,
        arch: process.arch,
        runtimeVersion: process.version,
      },
      security,
      abortSignal: params.abortSignal,
    });
    const profile = parseRegisteredMachineProfile(payload);
    if (!profile) {
      params.runtime.error?.(`vimalinx machine register failed for ${params.account.accountId}: invalid response`);
      return null;
    }
    setRegisteredMachineProfile(params.account.accountId, profile);
    scheduleProviderSyncForProfile({
      account: params.account,
      profile,
      runtime: params.runtime,
    });
    params.runtime.log?.(`vimalinx machine registered: ${profile.machineId}`);
    return profile;
  } catch (err) {
    params.runtime.error?.(`vimalinx machine register failed for ${params.account.accountId}: ${String(err)}`);
    return null;
  }
}

async function heartbeatMachineForAccount(params: {
  account: ResolvedTestAccount;
  machineId: string;
  runtime: { log?: (message: string) => void; error?: (message: string) => void };
  abortSignal: AbortSignal;
  status?: "online" | "offline";
}): Promise<void> {
  if (!params.account.baseUrl) return;
  const token = params.account.token ?? params.account.webhookToken;
  if (!token) return;
  const userId = resolveUserId(params.account);
  const security = resolveTestSecurityConfig(params.account.config.security);
  try {
    const payload = await callMachineEndpoint({
      account: params.account,
      path: "api/machine/heartbeat",
      body: {
        userId,
        token,
        machineId: params.machineId,
        status: params.status,
      },
      security,
      abortSignal: params.abortSignal,
    });
    const profile = parseRegisteredMachineProfile(payload);
    if (profile) {
      setRegisteredMachineProfile(params.account.accountId, profile);
      scheduleProviderSyncForProfile({
        account: params.account,
        profile,
        runtime: params.runtime,
      });
    }
  } catch (err) {
    params.runtime.error?.(`vimalinx machine heartbeat failed for ${params.account.accountId}: ${String(err)}`);
  }
}

export async function handleTestWebhookRequest(
  req: IncomingMessage,
  res: ServerResponse,
): Promise<boolean> {
  const url = new URL(req.url ?? "/", "http://localhost");
  const path = normalizeWebhookPath(url.pathname);
  const targets = webhookTargets.get(path);
  if (!targets || targets.length === 0) return false;

  if (req.method === "GET") {
    res.statusCode = 200;
    res.setHeader("Content-Type", "text/plain; charset=utf-8");
    res.end("OK");
    return true;
  }

  if (req.method !== "POST") {
    res.statusCode = 405;
    res.setHeader("Allow", "GET, POST");
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify({ error: "Method Not Allowed" }));
    return true;
  }

  const contentType = String(req.headers["content-type"] ?? "").toLowerCase();
  if (contentType && !contentType.includes("application/json")) {
    res.statusCode = 415;
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify({ error: "Unsupported Media Type" }));
    return true;
  }

  let rawBody = "";
  try {
    rawBody = await readRequestBody(req, 1024 * 1024);
  } catch (err) {
    res.statusCode = 413;
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify({ error: String(err) }));
    return true;
  }

  let payload: TestInboundPayload;
  try {
    payload = JSON.parse(rawBody) as TestInboundPayload;
  } catch {
    res.statusCode = 400;
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify({ error: "Invalid JSON" }));
    return true;
  }

  const defaultSecurity = resolveTestSecurityConfig(undefined);
  const { token: providedToken } = readTokenFromRequest(payload, req, defaultSecurity.allowTokenInQuery);
  let target = resolveMatchingTarget(targets, providedToken ?? undefined);
  if (!target) {
    const queryToken = new URL(req.url ?? "", "http://localhost").searchParams.get("token")?.trim();
    if (queryToken) {
      const queryTargets = targets.filter((entry) =>
        resolveTestSecurityConfig(entry.account.config.security).allowTokenInQuery,
      );
      target = resolveMatchingTarget(queryTargets, queryToken) ?? null;
    }
  }
  if (!target) {
    res.statusCode = 401;
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify({ error: "Unauthorized" }));
    return true;
  }

  const security = resolveTestSecurityConfig(target.account.config.security);
  if (Buffer.byteLength(rawBody, "utf-8") > security.maxPayloadBytes) {
    res.statusCode = 413;
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify({ error: "Payload Too Large" }));
    return true;
  }

  const requestIp = resolveRequestIp(req);
  if (!isIpAllowed(security.allowedIps, requestIp)) {
    res.statusCode = 403;
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify({ error: "Forbidden" }));
    return true;
  }

  if (!checkGlobalRateLimit(`vimalinx:${target.account.accountId}:${requestIp ?? "unknown"}`, security.rateLimitPerMinute)) {
    res.statusCode = 429;
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify({ error: "Rate limited" }));
    return true;
  }

  if (target.abortSignal.aborted) {
    res.statusCode = 503;
    res.setHeader("Content-Type", "text/plain; charset=utf-8");
    res.end("Service Unavailable");
    return true;
  }

  const { token: resolvedToken } = readTokenFromRequest(payload, req, security.allowTokenInQuery);
  if (target.expectedToken) {
    if (!resolvedToken || resolvedToken !== target.expectedToken) {
      res.statusCode = 401;
      res.setHeader("Content-Type", "application/json");
      res.end(JSON.stringify({ error: "Unauthorized" }));
      return true;
    }
  }

  if (security.requireSignature) {
    if (!security.hmacSecret) {
      res.statusCode = 401;
      res.setHeader("Content-Type", "application/json");
      res.end(JSON.stringify({ error: "Signature required" }));
      return true;
    }
    const timestampHeader = String(req.headers["x-vimalinx-timestamp"] ?? "").trim();
    const nonce = String(req.headers["x-vimalinx-nonce"] ?? "").trim();
    const signature = String(req.headers["x-vimalinx-signature"] ?? "").trim();
    const timestamp = Number(timestampHeader);
    const now = Date.now();
    if (!timestampHeader || !nonce || !signature || !Number.isFinite(timestamp)) {
      res.statusCode = 401;
      res.setHeader("Content-Type", "application/json");
      res.end(JSON.stringify({ error: "Missing signature headers" }));
      return true;
    }
    if (!isTimestampFresh({ timestamp, now, skewMs: security.timestampSkewMs })) {
      res.statusCode = 401;
      res.setHeader("Content-Type", "application/json");
      res.end(JSON.stringify({ error: "Stale signature" }));
      return true;
    }
    if (!checkAndStoreNonce({ accountId: target.account.accountId, nonce, now, windowMs: security.timestampSkewMs })) {
      res.statusCode = 409;
      res.setHeader("Content-Type", "application/json");
      res.end(JSON.stringify({ error: "Replay detected" }));
      return true;
    }
    const ok = verifyTestSignature({
      secret: security.hmacSecret,
      timestamp,
      nonce,
      body: rawBody,
      signature,
    });
    if (!ok) {
      res.statusCode = 401;
      res.setHeader("Content-Type", "application/json");
      res.end(JSON.stringify({ error: "Invalid signature" }));
      return true;
    }
  }

  const message = parseInboundMessage(payload);
  if (!message) {
    res.statusCode = 400;
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify({ error: "Missing chatId, senderId, or text" }));
    return true;
  }

  if (!checkSenderRateLimit(
    `vimalinx:${target.account.accountId}:${message.senderId}`,
    security.rateLimitPerMinutePerSender,
  )) {
    res.statusCode = 429;
    res.setHeader("Content-Type", "application/json");
    res.end(JSON.stringify({ error: "Rate limited" }));
    return true;
  }

  res.statusCode = 200;
  res.setHeader("Content-Type", "application/json");
  res.end(JSON.stringify({ ok: true }));

  void handleTestInbound({
    message,
    account: target.account,
    config: target.config,
    runtime: target.runtime,
    statusSink: target.statusSink,
    rateLimitChecked: true,
  }).catch((err) => {
    target.runtime.error?.(`vimalinx inbound failed: ${String(err)}`);
  });

  return true;
}

export async function startTestMonitor(params: {
  account: ResolvedTestAccount;
  config: TestConfig;
  runtime: { log?: (message: string) => void; error?: (message: string) => void };
  abortSignal: AbortSignal;
  inboundMode?: "webhook" | "poll";
  statusSink?: (patch: { lastInboundAt?: number; lastOutboundAt?: number }) => void;
}): Promise<() => void> {
  clearRegisteredMachineProfile(params.account.accountId);
  const profile = await registerMachineForAccount({
    account: params.account,
    runtime: params.runtime,
    abortSignal: params.abortSignal,
  });

  let stopHeartbeat = () => {};
  if (profile?.machineId) {
    const heartbeatMs = resolveMachineHeartbeatMs(params.account);
    const timer = setInterval(() => {
      if (params.abortSignal.aborted) return;
      void heartbeatMachineForAccount({
        account: params.account,
        machineId: profile.machineId,
        runtime: params.runtime,
        abortSignal: params.abortSignal,
      });
    }, heartbeatMs);
    stopHeartbeat = () => clearInterval(timer);
  }

  const mode = normalizeInboundMode(params.inboundMode ?? params.account.config.inboundMode);
  let stopTransport: (() => void) | undefined;
  if (mode === "poll") {
    stopTransport = await startTestPoller(params);
  } else {
    const path = params.account.webhookPath ?? DEFAULT_WEBHOOK_PATH;
    stopTransport = registerTestWebhookTarget({
      account: params.account,
      config: params.config,
      runtime: params.runtime,
      abortSignal: params.abortSignal,
      statusSink: params.statusSink,
      path,
      expectedToken: params.account.webhookToken ?? params.account.token,
    });
  }

  return () => {
    stopHeartbeat();
    stopTransport?.();
    if (profile?.machineId) {
      const abort = new AbortController();
      const timeout = setTimeout(() => abort.abort(), 3000);
      void heartbeatMachineForAccount({
        account: params.account,
        machineId: profile.machineId,
        runtime: params.runtime,
        abortSignal: abort.signal,
        status: "offline",
      }).finally(() => clearTimeout(timeout));
    }
    clearRegisteredMachineProfile(params.account.accountId);
  };
}
