import type { IncomingMessage, ServerResponse } from "node:http";

import type { ResolvedTestAccount } from "./accounts.js";
import { handleTestInbound } from "./inbound.js";
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
  const mode = normalizeInboundMode(params.inboundMode ?? params.account.config.inboundMode);
  if (mode === "poll") {
    return await startTestPoller(params);
  }
  const path = params.account.webhookPath ?? DEFAULT_WEBHOOK_PATH;
  return registerTestWebhookTarget({
    account: params.account,
    config: params.config,
    runtime: params.runtime,
    abortSignal: params.abortSignal,
    statusSink: params.statusSink,
    path,
    expectedToken: params.account.webhookToken ?? params.account.token,
  });
}
