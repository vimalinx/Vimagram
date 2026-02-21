import { createHmac, randomBytes, randomUUID, scryptSync, timingSafeEqual } from "node:crypto";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

type UserRecord = {
  id: string;
  token?: string;
  tokens?: string[];
  password?: string;
  passwordHash?: string;
  displayName?: string;
  gatewayUrl?: string;
  gatewayToken?: string;
  tokenUsage?: Record<string, TokenUsage>;
};

type UsersFile = {
  users: UserRecord[];
};

type ClientModeMetadata = {
  modeId?: string;
  modeLabel?: string;
  modelHint?: string;
  agentHint?: string;
  skillsHint?: string;
};

type InstanceConfig = {
  userId: string;
  chatId: string;
  modelTierId: string;
  identityId: string;
  createdAt: number;
  updatedAt: number;
};

type InstancesFile = {
  instances: InstanceConfig[];
};

type IncomingClientMessage = ClientModeMetadata & {
  userId?: string;
  token?: string;
  text?: string;
  chatId?: string;
  chatType?: "dm" | "group";
  mentioned?: boolean;
  senderName?: string;
  chatName?: string;
  id?: string;
  instanceModelTierId?: string;
  instanceIdentityId?: string;
};

type SendPayload = {
  chatId?: string;
  text?: string;
  replyToId?: string;
  accountId?: string;
  id?: string;
};

type InboundMessage = ClientModeMetadata & {
  id?: string;
  chatId: string;
  chatName?: string;
  chatType?: "dm" | "group";
  senderId: string;
  senderName?: string;
  text: string;
  mentioned?: boolean;
  timestamp: number;
};

type AuthMatch = {
  user: UserRecord;
  secret: string;
  kind: "token" | "password";
};

type ChatOwner = {
  userId: string;
  deviceKey: string;
};

type TokenUsage = {
  token: string;
  createdAt?: number;
  lastSeenAt?: number;
  streamConnects?: number;
  inboundCount?: number;
  outboundCount?: number;
  lastInboundAt?: number;
  lastOutboundAt?: number;
};

type OutboxEntry = {
  eventId: number;
  payload: Record<string, unknown>;
};

type MachineRoutingConfig = {
  modeAccountMap?: Record<string, string>;
  modeModelHints?: Record<string, string>;
  modeAgentHints?: Record<string, string>;
  modeSkillsHints?: Record<string, string>;
};

type MachineRecord = {
  machineId: string;
  userId: string;
  accountId?: string;
  machineLabel?: string;
  hostName?: string;
  platform?: string;
  arch?: string;
  runtimeVersion?: string;
  pluginVersion?: string;
  status: "online" | "offline";
  createdAt: number;
  updatedAt: number;
  lastSeenAt: number;
  routing?: MachineRoutingConfig;
};

type MachinesFile = {
  machines: MachineRecord[];
};

type MachineRegisterPayload = {
  userId?: string;
  token?: string;
  machineId?: string;
  accountId?: string;
  machineLabel?: string;
  hostName?: string;
  platform?: string;
  arch?: string;
  runtimeVersion?: string;
  pluginVersion?: string;
  routing?: MachineRoutingConfig;
};

type MachineHeartbeatPayload = {
  userId?: string;
  token?: string;
  machineId?: string;
  status?: "online" | "offline";
};

type MachinePatchPayload = {
  routing?: MachineRoutingConfig;
  status?: "online" | "offline";
  machineLabel?: string;
};

type MachineContributorPayload = {
  userId?: string;
  password?: string;
  machineId?: string;
  machineLabel?: string;
  accountId?: string;
  routing?: MachineRoutingConfig;
};

const __dirname = resolve(fileURLToPath(new URL(".", import.meta.url)));
const publicDir = resolve(__dirname, "public");
const port = Number.parseInt(process.env.TEST_SERVER_PORT ?? "18788", 10);
const gatewayUrl = process.env.TEST_GATEWAY_URL?.trim();
const gatewayToken = process.env.TEST_GATEWAY_TOKEN?.trim();
const serverToken = process.env.TEST_SERVER_TOKEN?.trim();
const bindHost = process.env.TEST_BIND_HOST?.trim() || "0.0.0.0";
const usersFilePath = process.env.TEST_USERS_FILE?.trim();
const usersInline = process.env.TEST_USERS?.trim();
const defaultUserId = process.env.TEST_DEFAULT_USER_ID?.trim();
const defaultUserToken = process.env.TEST_DEFAULT_USER_TOKEN?.trim();
const inboundMode = (process.env.TEST_INBOUND_MODE ?? "poll").trim().toLowerCase();
const usersWritePath = process.env.TEST_USERS_WRITE_FILE?.trim() || usersFilePath;
const machinesFilePath =
  process.env.TEST_MACHINES_FILE?.trim() ||
  (usersWritePath
    ? resolve(dirname(usersWritePath), "machines.json")
    : usersFilePath
      ? resolve(dirname(usersFilePath), "machines.json")
      : resolve(__dirname, "machines.json"));

const instancesFilePath =
  process.env.TEST_INSTANCES_FILE?.trim() ||
  (usersWritePath
    ? resolve(dirname(usersWritePath), "instances.json")
    : usersFilePath
      ? resolve(dirname(usersFilePath), "instances.json")
      : resolve(__dirname, "instances.json"));
const allowRegistration = (process.env.TEST_ALLOW_REGISTRATION ?? "true").toLowerCase() === "true";
const hmacSecret = process.env.TEST_HMAC_SECRET?.trim();
const requireSignature = (process.env.TEST_REQUIRE_SIGNATURE ?? "").trim().toLowerCase();
const signatureRequired = requireSignature ? requireSignature === "true" : Boolean(hmacSecret);
const signatureTtlMs = Number.parseInt(process.env.TEST_SIGNATURE_TTL_MS ?? "300000", 10);
const secretKey = process.env.TEST_SECRET_KEY?.trim() || "";
const hasSecretKey = secretKey.length >= 16;
const trustProxy = (process.env.TEST_TRUST_PROXY ?? "").trim().toLowerCase() === "true";
const rateLimitEnabled = (process.env.TEST_RATE_LIMIT ?? "true").trim().toLowerCase() !== "false";
const inviteCodes = normalizeInviteCodes(
  process.env.TEST_INVITE_CODES ?? process.env.TEST_INVITE_CODE ?? "",
);

const users = new Map<string, UserRecord>();
const machines = new Map<string, MachineRecord>();
const instances = new Map<string, InstanceConfig>();
let didMigrateUsers = false;
let pendingUsersSave: NodeJS.Timeout | null = null;
let pendingMachinesSave: NodeJS.Timeout | null = null;
let pendingInstancesSave: NodeJS.Timeout | null = null;

if (!hasSecretKey) {
  console.log("warning: TEST_SECRET_KEY not set; token hashing is disabled.");
}

for (const entry of loadInstancesSnapshot()) {
  instances.set(resolveInstanceKey(entry.userId, entry.chatId), entry);
}

function normalizeToken(value?: string): string | null {
  const trimmed = value?.trim();
  return trimmed ? trimmed : null;
}

function normalizeTokens(values: Array<string | undefined>): string[] {
  const tokens: string[] = [];
  const seen = new Set<string>();
  for (const value of values) {
    const normalized = normalizeTokenHash(value);
    if (!normalized || seen.has(normalized)) continue;
    seen.add(normalized);
    tokens.push(normalized);
  }
  return tokens;
}

const TOKEN_HASH_PREFIX = "hmac$";
const PASSWORD_HASH_PREFIX = "scrypt$";
const SCRYPT_N = Number.parseInt(process.env.TEST_SCRYPT_N ?? "16384", 10);
const SCRYPT_R = Number.parseInt(process.env.TEST_SCRYPT_R ?? "8", 10);
const SCRYPT_P = Number.parseInt(process.env.TEST_SCRYPT_P ?? "1", 10);
const SCRYPT_KEY_LEN = Number.parseInt(process.env.TEST_SCRYPT_KEY_LEN ?? "64", 10);

function isTokenHash(value: string): boolean {
  return value.startsWith(TOKEN_HASH_PREFIX);
}

function hashToken(value: string): string {
  if (!hasSecretKey) return value;
  const digest = createHmac("sha256", secretKey).update(value).digest("hex");
  return `${TOKEN_HASH_PREFIX}${digest}`;
}

function normalizeTokenHash(value?: string): string | null {
  const normalized = normalizeToken(value);
  if (!normalized) return null;
  if (!hasSecretKey) return normalized;
  if (isTokenHash(normalized)) return normalized;
  return hashToken(normalized);
}

function hashPassword(password: string): string {
  const salt = randomBytes(16).toString("hex");
  const derived = scryptSync(password, salt, SCRYPT_KEY_LEN, {
    N: SCRYPT_N,
    r: SCRYPT_R,
    p: SCRYPT_P,
  }).toString("hex");
  return `${PASSWORD_HASH_PREFIX}${SCRYPT_N}$${SCRYPT_R}$${SCRYPT_P}$${salt}$${derived}`;
}

function parsePasswordHash(raw: string): {
  n: number;
  r: number;
  p: number;
  salt: string;
  hash: string;
} | null {
  if (!raw.startsWith(PASSWORD_HASH_PREFIX)) return null;
  const parts = raw.split("$");
  if (parts.length !== 6) return null;
  const n = Number.parseInt(parts[1] || "", 10);
  const r = Number.parseInt(parts[2] || "", 10);
  const p = Number.parseInt(parts[3] || "", 10);
  const salt = parts[4] || "";
  const hash = parts[5] || "";
  if (!Number.isFinite(n) || !Number.isFinite(r) || !Number.isFinite(p)) return null;
  if (!salt || !hash) return null;
  return { n, r, p, salt, hash };
}

function verifyPassword(password: string, rawHash: string): boolean {
  const parsed = parsePasswordHash(rawHash);
  if (!parsed) return false;
  const keyLen = Math.max(1, Math.floor(parsed.hash.length / 2));
  const derived = scryptSync(password, parsed.salt, keyLen, {
    N: parsed.n,
    r: parsed.r,
    p: parsed.p,
  }).toString("hex");
  if (derived.length !== parsed.hash.length) return false;
  return timingSafeEqual(Buffer.from(derived, "utf-8"), Buffer.from(parsed.hash, "utf-8"));
}

function normalizeUsageNumber(value?: number): number | undefined {
  if (!Number.isFinite(value)) return undefined;
  return Math.max(0, Math.floor(value ?? 0));
}

function normalizeTokenUsage(
  raw: Record<string, TokenUsage> | undefined,
  tokens: string[],
): Record<string, TokenUsage> {
  const usage: Record<string, TokenUsage> = {};
  if (raw) {
    for (const [key, entry] of Object.entries(raw)) {
      const token = normalizeTokenHash(entry?.token ?? key);
      if (!token) continue;
      usage[token] = {
        token,
        createdAt: normalizeUsageNumber(entry?.createdAt),
        lastSeenAt: normalizeUsageNumber(entry?.lastSeenAt),
        streamConnects: normalizeUsageNumber(entry?.streamConnects),
        inboundCount: normalizeUsageNumber(entry?.inboundCount),
        outboundCount: normalizeUsageNumber(entry?.outboundCount),
        lastInboundAt: normalizeUsageNumber(entry?.lastInboundAt),
        lastOutboundAt: normalizeUsageNumber(entry?.lastOutboundAt),
      };
    }
  }
  for (const token of tokens) {
    if (!usage[token]) {
      usage[token] = { token };
    }
  }
  return usage;
}

function normalizeInviteCodes(raw: string): string[] {
  return raw
    .split(/[\n,;]+/g)
    .map((entry) => entry.trim())
    .filter(Boolean);
}

function isInviteCodeValid(code?: string): boolean {
  if (inviteCodes.length === 0) return true;
  const trimmed = code?.trim();
  if (!trimmed) return false;
  return inviteCodes.includes(trimmed);
}

function normalizeUserRecord(entry: UserRecord): UserRecord | null {
  const id = entry.id?.trim();
  if (!id) return null;
  let migrated = false;
  const rawTokens = [entry.token, ...(entry.tokens ?? [])];
  if (hasSecretKey) {
    for (const raw of rawTokens) {
      if (raw && !isTokenHash(raw.trim())) {
        migrated = true;
        break;
      }
    }
  }
  const tokens = normalizeTokens(rawTokens);
  let passwordHash = entry.passwordHash?.trim() || undefined;
  let password = entry.password?.trim() || undefined;
  if (!passwordHash && password) {
    passwordHash = hashPassword(password);
    password = undefined;
    migrated = true;
  }
  const displayName = entry.displayName?.trim() || undefined;
  const gatewayUrl = entry.gatewayUrl?.trim() || undefined;
  const gatewayToken = entry.gatewayToken?.trim() || undefined;
  const tokenUsage = normalizeTokenUsage(entry.tokenUsage, tokens);
  if (migrated) didMigrateUsers = true;
  return {
    ...entry,
    id,
    token: tokens[0],
    tokens,
    password,
    passwordHash,
    displayName,
    gatewayUrl,
    gatewayToken,
    tokenUsage,
  };
}

function addUser(entry: UserRecord) {
  const normalized = normalizeUserRecord(entry);
  if (!normalized) return;
  users.set(normalized.id, normalized);
}

function loadUsers() {
  if (usersInline) {
    const parsed = JSON.parse(usersInline) as UsersFile;
    for (const entry of parsed.users ?? []) {
      addUser(entry);
    }
  }
  if (usersFilePath) {
    const raw = readFileSync(usersFilePath, "utf-8");
    const parsed = JSON.parse(raw) as UsersFile;
    for (const entry of parsed.users ?? []) {
      addUser(entry);
    }
  }
  if (defaultUserId && defaultUserToken) {
    addUser({ id: defaultUserId, token: defaultUserToken });
  }
  if (didMigrateUsers) {
    scheduleUsersSave();
  }
}

loadUsers();

function normalizeMachineRecord(entry: MachineRecord): MachineRecord | null {
  const machineId = normalizeMachineId(entry.machineId);
  const userId = normalizeUserId(entry.userId);
  if (!machineId || !userId) return null;
  const now = Date.now();
  const createdAt = normalizeUsageNumber(entry.createdAt) ?? now;
  const updatedAt = normalizeUsageNumber(entry.updatedAt) ?? createdAt;
  const lastSeenAt = normalizeUsageNumber(entry.lastSeenAt) ?? updatedAt;
  const status = normalizeMachineStatus(entry.status) ?? "offline";
  return {
    machineId,
    userId,
    accountId: normalizeAccountIdRef(entry.accountId),
    machineLabel: normalizeHintField(entry.machineLabel, 80),
    hostName: normalizeHintField(entry.hostName, 80),
    platform: normalizeHintField(entry.platform, 40),
    arch: normalizeHintField(entry.arch, 40),
    runtimeVersion: normalizeHintField(entry.runtimeVersion, 40),
    pluginVersion: normalizeHintField(entry.pluginVersion, 40),
    status,
    createdAt,
    updatedAt,
    lastSeenAt,
    routing: normalizeMachineRoutingConfig(entry.routing),
  };
}

function loadMachines() {
  if (!machinesFilePath) return;
  try {
    const raw = readFileSync(machinesFilePath, "utf-8");
    const parsed = parseJson<MachinesFile>(raw);
    for (const entry of parsed?.machines ?? []) {
      const normalized = normalizeMachineRecord(entry);
      if (!normalized) continue;
      machines.set(normalized.machineId, normalized);
    }
  } catch {
  }
}

loadMachines();

function normalizeUserId(value?: string): string | null {
  const trimmed = value?.trim();
  if (!trimmed) return null;
  const normalized = trimmed.toLowerCase();
  if (!/^[a-z0-9_-]{2,32}$/.test(normalized)) {
    return null;
  }
  return normalized;
}

function normalizePassword(value?: string): string | null {
  const trimmed = value?.trim();
  if (!trimmed) return null;
  if (trimmed.length < 6 || trimmed.length > 64) {
    return null;
  }
  return trimmed;
}

function normalizeHintField(value: unknown, maxLength = 120): string | undefined {
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  if (!trimmed) return undefined;
  return trimmed.slice(0, maxLength);
}

function normalizeModeId(value: unknown): string | undefined {
  const normalized = normalizeHintField(value, 32)?.toLowerCase();
  if (!normalized) return undefined;
  if (!/^[a-z0-9_-]{1,32}$/.test(normalized)) return undefined;
  return normalized;
}

function resolveModeMetadata(payload: IncomingClientMessage): ClientModeMetadata {
  return {
    modeId: normalizeModeId(payload.modeId),
    modeLabel: normalizeHintField(payload.modeLabel, 40),
    modelHint: normalizeHintField(payload.modelHint, 120),
    agentHint: normalizeHintField(payload.agentHint, 120),
    skillsHint: normalizeHintField(payload.skillsHint, 160),
  };
}

function normalizeAccountIdRef(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  const normalized = value.trim().toLowerCase();
  if (!normalized) return undefined;
  if (!/^[a-z0-9_-]{1,64}$/.test(normalized)) return undefined;
  return normalized;
}

function normalizeMachineId(value: unknown): string | undefined {
  if (typeof value !== "string") return undefined;
  const normalized = value.trim().toLowerCase();
  if (!normalized) return undefined;
  if (!/^[a-z0-9][a-z0-9_-]{2,63}$/.test(normalized)) return undefined;
  return normalized;
}

function normalizeMachineStatus(value: unknown): "online" | "offline" | undefined {
  if (value === "online" || value === "offline") return value;
  return undefined;
}

function normalizeModeAccountMap(raw: unknown): Record<string, string> | undefined {
  if (!raw || typeof raw !== "object") return undefined;
  const next: Record<string, string> = {};
  for (const [rawMode, rawAccount] of Object.entries(raw as Record<string, unknown>)) {
    const modeId = normalizeModeId(rawMode);
    const accountId = normalizeAccountIdRef(rawAccount);
    if (!modeId || !accountId) continue;
    next[modeId] = accountId;
  }
  return Object.keys(next).length > 0 ? next : undefined;
}

function normalizeModeHintMap(
  raw: unknown,
  maxLength: number,
): Record<string, string> | undefined {
  if (!raw || typeof raw !== "object") return undefined;
  const next: Record<string, string> = {};
  for (const [rawMode, rawHint] of Object.entries(raw as Record<string, unknown>)) {
    const modeId = normalizeModeId(rawMode);
    const hint = normalizeHintField(rawHint, maxLength);
    if (!modeId || !hint) continue;
    next[modeId] = hint;
  }
  return Object.keys(next).length > 0 ? next : undefined;
}

function normalizeMachineRoutingConfig(raw: unknown): MachineRoutingConfig | undefined {
  if (!raw || typeof raw !== "object") return undefined;
  const source = raw as Record<string, unknown>;
  const modeAccountMap = normalizeModeAccountMap(source.modeAccountMap);
  const modeModelHints = normalizeModeHintMap(source.modeModelHints, 120);
  const modeAgentHints = normalizeModeHintMap(source.modeAgentHints, 120);
  const modeSkillsHints = normalizeModeHintMap(source.modeSkillsHints, 160);
  if (!modeAccountMap && !modeModelHints && !modeAgentHints && !modeSkillsHints) {
    return undefined;
  }
  return {
    modeAccountMap,
    modeModelHints,
    modeAgentHints,
    modeSkillsHints,
  };
}

function sanitizeMachineRecordForResponse(record: MachineRecord): Record<string, unknown> {
  return {
    machineId: record.machineId,
    userId: record.userId,
    accountId: record.accountId,
    machineLabel: record.machineLabel,
    hostName: record.hostName,
    platform: record.platform,
    arch: record.arch,
    runtimeVersion: record.runtimeVersion,
    pluginVersion: record.pluginVersion,
    status: record.status,
    createdAt: record.createdAt,
    updatedAt: record.updatedAt,
    lastSeenAt: record.lastSeenAt,
    routing: record.routing,
  };
}

function generateToken(): string {
  return randomUUID().replace(/-/g, "");
}

function generateContributorUserId(): string {
  let next = `contrib_${randomUUID().replace(/-/g, "").slice(0, 10)}`;
  while (users.has(next)) {
    next = `contrib_${randomUUID().replace(/-/g, "").slice(0, 10)}`;
  }
  return next;
}

function makeDeviceKey(userId: string, token: string): string {
  return `${userId}:${token}`;
}

function extractTokenFromDeviceKey(deviceKey: string): string | null {
  const idx = deviceKey.indexOf(":");
  if (idx < 0) return null;
  return normalizeToken(deviceKey.slice(idx + 1));
}

function resolveUserTokens(entry: UserRecord): string[] {
  return normalizeTokens([entry.token, ...(entry.tokens ?? [])]);
}

function hasUserToken(entry: UserRecord, token?: string): boolean {
  const normalized = normalizeTokenHash(token);
  if (!normalized) return false;
  return resolveUserTokens(entry).includes(normalized);
}

function addUserToken(entry: UserRecord, token: string): void {
  const tokens = resolveUserTokens(entry);
  const tokenHash = normalizeTokenHash(token);
  if (!tokenHash) return;
  if (!tokens.includes(tokenHash)) tokens.push(tokenHash);
  entry.tokens = tokens;
  if (!entry.token) entry.token = tokenHash;
  updateTokenUsage(entry, tokenHash, { createdAt: Date.now(), lastSeenAt: Date.now() });
}

function serializeUserRecord(entry: UserRecord): UserRecord {
  const tokens = resolveUserTokens(entry);
  const tokenUsage = normalizeTokenUsage(entry.tokenUsage, tokens);
  return {
    ...entry,
    token: tokens[0],
    tokens,
    password: undefined,
    tokenUsage,
  };
}

function saveUsersSnapshot(entries: UserRecord[]) {
  if (!usersWritePath) {
    throw new Error("TEST_USERS_FILE is not set; cannot persist registrations.");
  }
  mkdirSync(dirname(usersWritePath), { recursive: true });
  const data = JSON.stringify({ users: entries.map(serializeUserRecord) }, null, 2);
  writeFileSync(usersWritePath, data, "utf-8");
}

function scheduleUsersSave(): void {
  if (!usersWritePath) return;
  if (pendingUsersSave) return;
  pendingUsersSave = setTimeout(() => {
    pendingUsersSave = null;
    try {
      const entries = [...users.values()].sort((a, b) => a.id.localeCompare(b.id));
      saveUsersSnapshot(entries);
    } catch {
      // Ignore background save failures; explicit writes handle errors.
    }
  }, 1000);
}

function saveMachinesSnapshot(entries: MachineRecord[]) {
  if (!machinesFilePath) return;
  mkdirSync(dirname(machinesFilePath), { recursive: true });
  const data = JSON.stringify({ machines: entries }, null, 2);
  writeFileSync(machinesFilePath, data, "utf-8");
}

function loadInstancesSnapshot(): InstanceConfig[] {
  try {
    const raw = readFileSync(instancesFilePath, "utf-8");
    const parsed = parseJson<InstancesFile>(raw);
    const list = Array.isArray(parsed?.instances) ? parsed.instances : [];
    return list
      .map((entry) => {
        const userId = normalizeUserId(entry.userId);
        const chatId = typeof entry.chatId === "string" ? entry.chatId.trim() : "";
        const modelTierId = typeof entry.modelTierId === "string" ? entry.modelTierId.trim() : "";
        const identityId = typeof entry.identityId === "string" ? entry.identityId.trim() : "";
        const createdAt = Number.isFinite(entry.createdAt) ? entry.createdAt : Date.now();
        const updatedAt = Number.isFinite(entry.updatedAt) ? entry.updatedAt : createdAt;
        if (!userId || !chatId || !modelTierId || !identityId) return null;
        return {
          userId,
          chatId,
          modelTierId,
          identityId,
          createdAt,
          updatedAt,
        } satisfies InstanceConfig;
      })
      .filter((entry): entry is InstanceConfig => Boolean(entry));
  } catch {
    return [];
  }
}

function scheduleSaveInstancesSnapshot(entries: InstanceConfig[]) {
  if (pendingInstancesSave) clearTimeout(pendingInstancesSave);
  pendingInstancesSave =
    setTimeout(() => {
      pendingInstancesSave = null;
      mkdirSync(dirname(instancesFilePath), { recursive: true });
      const payload: InstancesFile = { instances: entries };
      writeFileSync(instancesFilePath, JSON.stringify(payload, null, 2) + "\n", "utf-8");
    }, 150);
}

function upsertInstanceConfig(params: {
  userId: string;
  chatId: string;
  modelTierId: string;
  identityId: string;
}): InstanceConfig {
  const key = `${params.userId}:${params.chatId}`;
  const existing = instances.get(key);
  const now = Date.now();
  const next: InstanceConfig = {
    userId: params.userId,
    chatId: params.chatId,
    modelTierId: params.modelTierId,
    identityId: params.identityId,
    createdAt: existing?.createdAt ?? now,
    updatedAt: now,
  };
  instances.set(key, next);
  const entries = [...instances.values()].sort((a, b) => {
    if (a.userId !== b.userId) return a.userId.localeCompare(b.userId);
    return a.chatId.localeCompare(b.chatId);
  });
  scheduleSaveInstancesSnapshot(entries);
  return next;
}

function resolveInstanceKey(userId: string, chatId: string): string {
  return `${userId}:${chatId}`;
}

type InstanceTier = { id: string; label: string; modelHint: string };
type InstanceIdentity = { id: string; label: string; skillsHint: string; agentHint: string };

const INSTANCE_TIERS: InstanceTier[] = [
  { id: "m2.5", label: "Standard", modelHint: "minimax/m2.5" },
  { id: "glm-4.7", label: "Pro", modelHint: "zai/glm-4.7" },
  { id: "glm-5", label: "Max", modelHint: "zai/glm-5" },
];

const INSTANCE_IDENTITIES: InstanceIdentity[] = [
  {
    id: "ecom",
    label: "E-commerce",
    agentHint: "ecom",
    skillsHint: "ecom: listings, ads, pricing, seo",
  },
  {
    id: "docs",
    label: "Writing",
    agentHint: "docs",
    skillsHint: "docs: contracts, proposals, formal writing",
  },
  {
    id: "media",
    label: "Creator",
    agentHint: "media",
    skillsHint: "media: scripts, captions, hooks, content calendar",
  },
];

function normalizeInstanceTierId(raw: unknown): string | null {
  const trimmed = typeof raw === "string" ? raw.trim() : "";
  if (!trimmed) return null;
  return INSTANCE_TIERS.some((t) => t.id === trimmed) ? trimmed : null;
}

function normalizeInstanceIdentityId(raw: unknown): string | null {
  const trimmed = typeof raw === "string" ? raw.trim() : "";
  if (!trimmed) return null;
  return INSTANCE_IDENTITIES.some((t) => t.id === trimmed) ? trimmed : null;
}

function encodeInstanceModeId(config: InstanceConfig): string {
  const tier = config.modelTierId.trim().toLowerCase().replace(/[^a-z0-9]/g, "_");
  const identity = config.identityId.trim().toLowerCase().replace(/[^a-z0-9]/g, "_");
  return `inst_${tier}_${identity}`;
}

function resolveInstanceModeMetadata(config: InstanceConfig): ClientModeMetadata {
  const tier = INSTANCE_TIERS.find((t) => t.id === config.modelTierId);
  const identity = INSTANCE_IDENTITIES.find((i) => i.id === config.identityId);
  const tierLabel = tier?.label ?? config.modelTierId;
  const identityLabel = identity?.label ?? config.identityId;
  const modeId = encodeInstanceModeId(config);
  return {
    modeId,
    modeLabel: `${tierLabel} · ${identityLabel}`,
    modelHint: tier?.modelHint,
    agentHint: identity?.agentHint,
    skillsHint: identity?.skillsHint,
  };
}

function scheduleMachinesSave(): void {
  if (!machinesFilePath) return;
  if (pendingMachinesSave) return;
  pendingMachinesSave = setTimeout(() => {
    pendingMachinesSave = null;
    try {
      const entries = [...machines.values()].sort((a, b) => a.machineId.localeCompare(b.machineId));
      saveMachinesSnapshot(entries);
    } catch {
    }
  }, 1000);
}

function upsertMachineRecord(params: {
  userId: string;
  machineId?: string;
  accountId?: string;
  machineLabel?: string;
  hostName?: string;
  platform?: string;
  arch?: string;
  runtimeVersion?: string;
  pluginVersion?: string;
  routing?: MachineRoutingConfig;
}): MachineRecord {
  const machineId =
    normalizeMachineId(params.machineId) ?? `m_${randomUUID().replace(/-/g, "").slice(0, 16)}`;
  const now = Date.now();
  const existing = machines.get(machineId);
  const createdAt = existing?.createdAt ?? now;
  const next: MachineRecord = {
    machineId,
    userId: params.userId,
    accountId: normalizeAccountIdRef(params.accountId) ?? existing?.accountId,
    machineLabel: normalizeHintField(params.machineLabel, 80) ?? existing?.machineLabel,
    hostName: normalizeHintField(params.hostName, 80) ?? existing?.hostName,
    platform: normalizeHintField(params.platform, 40) ?? existing?.platform,
    arch: normalizeHintField(params.arch, 40) ?? existing?.arch,
    runtimeVersion: normalizeHintField(params.runtimeVersion, 40) ?? existing?.runtimeVersion,
    pluginVersion: normalizeHintField(params.pluginVersion, 40) ?? existing?.pluginVersion,
    status: "online",
    createdAt,
    updatedAt: now,
    lastSeenAt: now,
    routing: params.routing ?? existing?.routing,
  };
  machines.set(machineId, next);
  scheduleMachinesSave();
  return next;
}

function resolveOwnedMachine(userId: string, machineId?: string): MachineRecord | null {
  const normalized = normalizeMachineId(machineId);
  if (!normalized) return null;
  const entry = machines.get(normalized);
  if (!entry) return null;
  if (entry.userId !== userId) return null;
  return entry;
}

function touchMachineHeartbeat(params: {
  entry: MachineRecord;
  status?: "online" | "offline";
}): MachineRecord {
  const now = Date.now();
  const next: MachineRecord = {
    ...params.entry,
    status: params.status ?? "online",
    updatedAt: now,
    lastSeenAt: now,
  };
  machines.set(next.machineId, next);
  scheduleMachinesSave();
  return next;
}

function ensureTokenUsage(entry: UserRecord, token: string): TokenUsage | null {
  const normalized = normalizeTokenHash(token);
  if (!normalized) return null;
  const usage = normalizeTokenUsage(entry.tokenUsage, resolveUserTokens(entry));
  const existing = usage[normalized];
  const createdAt = existing?.createdAt ?? Date.now();
  const next: TokenUsage = {
    token: normalized,
    createdAt,
    lastSeenAt: existing?.lastSeenAt,
    streamConnects: existing?.streamConnects,
    inboundCount: existing?.inboundCount,
    outboundCount: existing?.outboundCount,
    lastInboundAt: existing?.lastInboundAt,
    lastOutboundAt: existing?.lastOutboundAt,
  };
  usage[normalized] = next;
  entry.tokenUsage = usage;
  return next;
}

function updateTokenUsage(
  entry: UserRecord,
  token: string,
  patch: Partial<TokenUsage> & {
    streamConnectsDelta?: number;
    inboundCountDelta?: number;
    outboundCountDelta?: number;
  },
): void {
  const usage = ensureTokenUsage(entry, token);
  if (!usage) return;
  const next: TokenUsage = {
    ...usage,
    ...patch,
  };
  if (patch.streamConnectsDelta) {
    next.streamConnects = (usage.streamConnects ?? 0) + patch.streamConnectsDelta;
  }
  if (patch.inboundCountDelta) {
    next.inboundCount = (usage.inboundCount ?? 0) + patch.inboundCountDelta;
  }
  if (patch.outboundCountDelta) {
    next.outboundCount = (usage.outboundCount ?? 0) + patch.outboundCountDelta;
  }
  entry.tokenUsage = {
    ...(entry.tokenUsage ?? {}),
    [usage.token]: next,
  };
  scheduleUsersSave();
}

function sendJson(res: ServerResponse, status: number, body: Record<string, unknown>) {
  res.statusCode = status;
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  res.end(JSON.stringify(body));
}

function sendText(res: ServerResponse, status: number, body: string) {
  res.statusCode = status;
  res.setHeader("Content-Type", "text/plain; charset=utf-8");
  res.end(body);
}

function readBody(req: IncomingMessage, maxBytes: number): Promise<string> {
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

function parseJson<T>(raw: string): T | null {
  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
}

function createSignature(params: { secret: string; timestamp: number; nonce: string; body: string }): string {
  return createHmac("sha256", params.secret)
    .update(`${params.timestamp}.${params.nonce}.${params.body}`)
    .digest("hex");
}

function verifySignature(params: {
  secret: string;
  timestamp: number;
  nonce: string;
  body: string;
  signature: string;
}): boolean {
  const expected = createSignature({
    secret: params.secret,
    timestamp: params.timestamp,
    nonce: params.nonce,
    body: params.body,
  });
  if (expected.length !== params.signature.length) return false;
  return timingSafeEqual(Buffer.from(expected, "utf-8"), Buffer.from(params.signature, "utf-8"));
}

function readSignatureHeaders(req: IncomingMessage): {
  timestamp: number | null;
  nonce: string;
  signature: string;
} {
  const timestampRaw = String(req.headers["x-vimalinx-timestamp"] ?? "").trim();
  const nonce = String(req.headers["x-vimalinx-nonce"] ?? "").trim();
  const signature = String(req.headers["x-vimalinx-signature"] ?? "").trim();
  const timestamp = Number(timestampRaw);
  return {
    timestamp: Number.isFinite(timestamp) ? timestamp : null,
    nonce,
    signature,
  };
}

function checkAndStoreNonce(scope: string, nonce: string, now: number, ttlMs: number): boolean {
  const store = nonceWindows.get(scope) ?? new Map<string, number>();
  const cutoff = now - ttlMs;
  for (const [key, value] of store.entries()) {
    if (value < cutoff) store.delete(key);
  }
  if (store.has(nonce)) {
    nonceWindows.set(scope, store);
    return false;
  }
  store.set(nonce, now);
  nonceWindows.set(scope, store);
  return true;
}

function verifySignedRequest(params: {
  req: IncomingMessage;
  body: string;
  scope: string;
}): string | null {
  if (!signatureRequired) return null;
  if (!hmacSecret) return "missing HMAC secret";
  const headers = readSignatureHeaders(params.req);
  if (!headers.timestamp || !headers.nonce || !headers.signature) {
    return "missing signature headers";
  }
  const now = Date.now();
  if (Math.abs(now - headers.timestamp) > signatureTtlMs) {
    return "stale signature";
  }
  if (!checkAndStoreNonce(params.scope, headers.nonce, now, signatureTtlMs)) {
    return "replay detected";
  }
  const ok = verifySignature({
    secret: hmacSecret,
    timestamp: headers.timestamp,
    nonce: headers.nonce,
    body: params.body,
    signature: headers.signature,
  });
  return ok ? null : "invalid signature";
}

type RateLimitState = {
  count: number;
  resetAt: number;
};

const rateLimits = new Map<string, RateLimitState>();

function resolveClientIp(req: IncomingMessage): string {
  if (trustProxy) {
    const forwarded = String(req.headers["x-forwarded-for"] ?? "").trim();
    if (forwarded) return forwarded.split(",")[0]?.trim() || "unknown";
  }
  return req.socket.remoteAddress ?? "unknown";
}

function checkRateLimit(key: string, limit: number, windowMs: number): boolean {
  if (!rateLimitEnabled) return true;
  const now = Date.now();
  const entry = rateLimits.get(key);
  if (!entry || entry.resetAt <= now) {
    rateLimits.set(key, { count: 1, resetAt: now + windowMs });
    return true;
  }
  entry.count += 1;
  rateLimits.set(key, entry);
  return entry.count <= limit;
}

function getUser(userId?: string, token?: string): UserRecord | null {
  if (!userId || !token) return null;
  const entry = users.get(userId);
  if (!entry) return null;
  return hasUserToken(entry, token) ? entry : null;
}

function getUserByPassword(userId?: string, password?: string): UserRecord | null {
  if (!userId || !password) return null;
  const entry = users.get(userId);
  if (!entry) return null;
  if (entry.passwordHash) {
    return verifyPassword(password, entry.passwordHash) ? entry : null;
  }
  if (entry.password && entry.password === password) {
    entry.passwordHash = hashPassword(password);
    entry.password = undefined;
    scheduleUsersSave();
    return entry;
  }
  return null;
}

function getUserByToken(token?: string): UserRecord | null {
  if (!token) return null;
  for (const entry of users.values()) {
    if (hasUserToken(entry, token)) return entry;
  }
  return null;
}

function resolveAuthMatch(params: {
  userId?: string;
  secret?: string;
  allowPassword?: boolean;
}): AuthMatch | null {
  const userId = params.userId?.trim();
  const secret = params.secret?.trim();
  if (!secret) return null;
  const tokenHash = normalizeTokenHash(secret);
  if (userId) {
    const tokenMatch = getUser(userId, secret);
    if (tokenMatch && tokenHash) return { user: tokenMatch, secret: tokenHash, kind: "token" };
    if (params.allowPassword) {
      const passwordMatch = getUserByPassword(userId, secret);
      if (passwordMatch) return { user: passwordMatch, secret, kind: "password" };
    }
  }
  const tokenMatch = getUserByToken(secret);
  if (tokenMatch && tokenHash) return { user: tokenMatch, secret: tokenHash, kind: "token" };
  return null;
}

function isTokenInUse(token: string, excludeUserId?: string): boolean {
  for (const entry of users.values()) {
    if (excludeUserId && entry.id === excludeUserId) continue;
    if (hasUserToken(entry, token)) return true;
  }
  return false;
}

function normalizeChatId(userId: string, chatId?: string): string {
  if (chatId && chatId.trim()) return chatId.trim();
  return `user:${userId}`;
}

function extractUserIdFromChatId(chatId?: string): string | null {
  if (!chatId) return null;
  const trimmed = chatId.trim();
  if (trimmed.startsWith("user:")) return trimmed.slice("user:".length).trim();
  if (trimmed.startsWith("vimalinx:")) return trimmed.slice("vimalinx:".length).trim();
  return trimmed || null;
}

function resolvePrimaryToken(user: UserRecord): string | null {
  const tokens = resolveUserTokens(user);
  return tokens[0] ?? null;
}

function resolveOwnerForChatId(
  chatId?: string,
): { user: UserRecord; deviceKey: string } | null {
  if (!chatId) return null;
  const mapped = chatOwners.get(chatId);
  if (mapped) {
    const mappedUser = users.get(mapped.userId);
    if (mappedUser) return { user: mappedUser, deviceKey: mapped.deviceKey };
  }
  const directId = extractUserIdFromChatId(chatId);
  if (directId) {
    const direct = users.get(directId);
    if (direct) {
      const token = resolvePrimaryToken(direct);
      if (token) {
        return { user: direct, deviceKey: makeDeviceKey(direct.id, token) };
      }
    }
  }
  if (users.size === 1) {
    const only = users.values().next().value ?? null;
    if (!only) return null;
    const token = resolvePrimaryToken(only);
    if (!token) return null;
    return { user: only, deviceKey: makeDeviceKey(only.id, token) };
  }
  return null;
}

type ClientConnection = {
  res: ServerResponse;
  heartbeat: NodeJS.Timeout;
};

const clients = new Map<string, Set<ClientConnection>>();
const outbox = new Map<string, OutboxEntry[]>();
const deviceSequences = new Map<string, number>();
const inboundQueues = new Map<string, InboundMessage[]>();
const inboundWaiters = new Map<string, Set<InboundWaiter>>();
const nonceWindows = new Map<string, Map<string, number>>();
const chatOwners = new Map<string, ChatOwner>();

type InboundWaiter = {
  finish: (messages: InboundMessage[]) => void;
  timeout: NodeJS.Timeout;
};

const OUTBOX_LIMIT = 200;

function nextEventId(deviceKey: string): number {
  const current = deviceSequences.get(deviceKey) ?? 0;
  const next = current + 1;
  deviceSequences.set(deviceKey, next);
  return next;
}

function appendOutbox(deviceKey: string, payload: Record<string, unknown>): OutboxEntry {
  const eventId = nextEventId(deviceKey);
  const entry: OutboxEntry = { eventId, payload: { ...payload, id: String(eventId) } };
  const queue = outbox.get(deviceKey) ?? [];
  queue.push(entry);
  if (queue.length > OUTBOX_LIMIT) {
    queue.splice(0, queue.length - OUTBOX_LIMIT);
  }
  outbox.set(deviceKey, queue);
  return entry;
}

function sendEvent(res: ServerResponse, entry: OutboxEntry) {
  res.write(`id: ${entry.eventId}\n`);
  res.write(`data: ${JSON.stringify(entry.payload)}\n\n`);
}

function replayOutbox(deviceKey: string, res: ServerResponse, sinceId?: number) {
  const queue = outbox.get(deviceKey);
  if (!queue || queue.length === 0) return;
  const start = sinceId && Number.isFinite(sinceId) ? sinceId : 0;
  for (const entry of queue) {
    if (entry.eventId > start) {
      sendEvent(res, entry);
    }
  }
}

function sendToDevice(deviceKey: string, payload: Record<string, unknown>) {
  const entry = appendOutbox(deviceKey, payload);
  const connections = clients.get(deviceKey);
  if (!connections || connections.size === 0) {
    return;
  }
  for (const connection of connections) {
    sendEvent(connection.res, entry);
  }
}

function attachClient(deviceKey: string, res: ServerResponse, lastEventId?: number) {
  res.statusCode = 200;
  res.setHeader("Content-Type", "text/event-stream; charset=utf-8");
  res.setHeader("Cache-Control", "no-cache");
  res.setHeader("Connection", "keep-alive");
  res.write("event: ready\n");
  res.write("data: {}\n\n");

  replayOutbox(deviceKey, res, lastEventId);

  const heartbeat = setInterval(() => {
    res.write("event: ping\n");
    res.write(`data: ${Date.now()}\n\n`);
  }, 25000);

  const entry: ClientConnection = { res, heartbeat };
  const set = clients.get(deviceKey) ?? new Set<ClientConnection>();
  set.add(entry);
  clients.set(deviceKey, set);

  res.on("close", () => {
    clearInterval(heartbeat);
    const current = clients.get(deviceKey);
    if (!current) return;
    current.delete(entry);
    if (current.size === 0) clients.delete(deviceKey);
  });
}

function enqueueInbound(deviceKey: string, message: InboundMessage) {
  const queue = inboundQueues.get(deviceKey) ?? [];
  queue.push(message);
  inboundQueues.set(deviceKey, queue);
  notifyInboundWaiters(deviceKey);
}

function drainInbound(deviceKey: string): InboundMessage[] {
  const queue = inboundQueues.get(deviceKey) ?? [];
  inboundQueues.delete(deviceKey);
  return queue;
}

function waitForInbound(
  deviceKey: string,
  waitMs: number,
  req: IncomingMessage,
): Promise<InboundMessage[]> {
  const queued = inboundQueues.get(deviceKey);
  if (queued?.length) return Promise.resolve(drainInbound(deviceKey));

  return new Promise((resolve) => {
    let done = false;
    const waiters = inboundWaiters.get(deviceKey) ?? new Set<InboundWaiter>();

    const finish = (messages: InboundMessage[]) => {
      if (done) return;
      done = true;
      const current = inboundWaiters.get(deviceKey);
      if (current) {
        current.delete(entry);
        if (current.size === 0) inboundWaiters.delete(deviceKey);
      }
      clearTimeout(entry.timeout);
      resolve(messages);
    };

    const entry: InboundWaiter = {
      finish,
      timeout: setTimeout(() => finish([]), waitMs),
    };

    waiters.add(entry);
    inboundWaiters.set(deviceKey, waiters);

    req.on("close", () => finish([]));
  });
}

function notifyInboundWaiters(deviceKey: string) {
  const waiters = inboundWaiters.get(deviceKey);
  if (!waiters || waiters.size === 0) return;
  const queue = inboundQueues.get(deviceKey);
  if (!queue || queue.length === 0) return;
  const entry = waiters.values().next().value as InboundWaiter | undefined;
  if (!entry) return;
  const messages = drainInbound(deviceKey);
  entry.finish(messages);
}

function buildInboundMessage(
  payload: IncomingClientMessage,
  user: UserRecord,
  deviceKey: string,
): InboundMessage {
  const chatId = normalizeChatId(user.id, payload.chatId);
  chatOwners.set(chatId, { userId: user.id, deviceKey });
  const senderName = payload.senderName?.trim() || user.displayName || user.id;
  const chatName = payload.chatName?.trim() || undefined;
  const id = typeof payload.id === "string" ? payload.id.trim() : undefined;
  const modeMetadata = resolveModeMetadata(payload);
  return {
    id,
    chatId,
    chatName,
    chatType: payload.chatType ?? "dm",
    senderId: user.id,
    senderName,
    text: payload.text ?? "",
    mentioned: Boolean(payload.mentioned),
    timestamp: Date.now(),
    ...modeMetadata,
  };
}

async function forwardToGateway(message: InboundMessage, user: UserRecord) {
  const targetUrl = user.gatewayUrl ?? gatewayUrl;
  if (!targetUrl) {
    throw new Error(`Gateway URL missing for user ${user.id}`);
  }
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
  };
  const token = user.gatewayToken ?? user.token ?? gatewayToken ?? serverToken;
  if (token) headers.Authorization = `Bearer ${token}`;
  const body = JSON.stringify({ message });
  if (hmacSecret) {
    const timestamp = Date.now();
    const nonce = randomUUID();
    const signature = createSignature({ secret: hmacSecret, timestamp, nonce, body });
    headers["x-vimalinx-timestamp"] = String(timestamp);
    headers["x-vimalinx-nonce"] = nonce;
    headers["x-vimalinx-signature"] = signature;
  }

  const res = await fetch(targetUrl, {
    method: "POST",
    headers,
    body,
  });

  if (!res.ok) {
    throw new Error(`Gateway request failed (${res.status} ${res.statusText})`);
  }
}

function readBearerToken(req: IncomingMessage): string {
  const authHeader = String(req.headers.authorization ?? "");
  if (authHeader.toLowerCase().startsWith("bearer ")) {
    return authHeader.slice("bearer ".length).trim();
  }
  return "";
}

function readUserIdHeader(req: IncomingMessage): string {
  return String(req.headers["x-vimalinx-user"] ?? "").trim();
}

function verifyServerToken(req: IncomingMessage, user: UserRecord | null): boolean {
  const provided = readBearerToken(req);
  if (serverToken && provided === serverToken) return true;
  if (user && hasUserToken(user, provided)) return true;
  return false;
}

function verifyAdminServerToken(req: IncomingMessage): boolean {
  const provided = readBearerToken(req);
  if (!serverToken || !provided) return false;
  return provided === serverToken;
}

function resolveMachineAuth(params: {
  req: IncomingMessage;
  userId?: string;
  token?: string;
}): AuthMatch | null {
  const userId = params.userId?.trim() || readUserIdHeader(params.req) || undefined;
  const token = params.token?.trim() || readBearerToken(params.req) || undefined;
  return resolveAuthMatch({
    userId,
    secret: token,
    allowPassword: false,
  });
}

function serveFile(res: ServerResponse, path: string) {
  try {
    const data = readFileSync(path);
    res.statusCode = 200;
    if (path.endsWith(".html")) {
      res.setHeader("Content-Type", "text/html; charset=utf-8");
    } else if (path.endsWith(".js")) {
      res.setHeader("Content-Type", "text/javascript; charset=utf-8");
    } else if (path.endsWith(".css")) {
      res.setHeader("Content-Type", "text/css; charset=utf-8");
    }
    res.end(data);
  } catch {
    sendText(res, 404, "Not Found");
  }
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? "/", "http://localhost");

  if (req.method === "GET" && url.pathname === "/healthz") {
    sendJson(res, 200, { ok: true });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/config") {
    sendJson(res, 200, {
      ok: true,
      inviteRequired: inviteCodes.length > 0,
      allowRegistration,
    });
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/machine/register") {
    const clientId = resolveClientIp(req);
    if (!checkRateLimit(`machine-register:${clientId}`, 120, 60 * 1000)) {
      sendJson(res, 429, { error: "rate limited" });
      return;
    }
    const raw = await readBody(req, 1024 * 1024).catch((err: Error) => {
      sendJson(res, 413, { error: err.message });
      return null;
    });
    if (!raw) return;
    const payload = parseJson<MachineRegisterPayload>(raw);
    if (!payload) {
      sendJson(res, 400, { error: "invalid JSON" });
      return;
    }
    const auth = resolveMachineAuth({
      req,
      userId: payload.userId,
      token: payload.token,
    });
    if (!auth) {
      sendJson(res, 401, { error: "unauthorized" });
      return;
    }
    const signatureError = verifySignedRequest({
      req,
      body: raw,
      scope: `machine-register:${auth.user.id}`,
    });
    if (signatureError) {
      sendJson(res, 401, { error: signatureError });
      return;
    }
    const machineId = normalizeMachineId(payload.machineId);
    if (machineId) {
      const existing = machines.get(machineId);
      if (existing && existing.userId !== auth.user.id) {
        sendJson(res, 409, { error: "machineId already in use" });
        return;
      }
    }
    const hasRoutingField = Object.prototype.hasOwnProperty.call(payload, "routing");
    const routing = normalizeMachineRoutingConfig(payload.routing);
    if (hasRoutingField && payload.routing && !routing) {
      sendJson(res, 400, { error: "invalid routing" });
      return;
    }
    const machine = upsertMachineRecord({
      userId: auth.user.id,
      machineId,
      accountId: payload.accountId,
      machineLabel: payload.machineLabel,
      hostName: payload.hostName,
      platform: payload.platform,
      arch: payload.arch,
      runtimeVersion: payload.runtimeVersion,
      pluginVersion: payload.pluginVersion,
      routing,
    });
    sendJson(res, 200, {
      ok: true,
      machine: sanitizeMachineRecordForResponse(machine),
      config: { routing: machine.routing ?? {} },
      serverTime: Date.now(),
    });
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/machine/heartbeat") {
    const clientId = resolveClientIp(req);
    if (!checkRateLimit(`machine-heartbeat:${clientId}`, 240, 60 * 1000)) {
      sendJson(res, 429, { error: "rate limited" });
      return;
    }
    const raw = await readBody(req, 1024 * 1024).catch((err: Error) => {
      sendJson(res, 413, { error: err.message });
      return null;
    });
    if (!raw) return;
    const payload = parseJson<MachineHeartbeatPayload>(raw);
    if (!payload) {
      sendJson(res, 400, { error: "invalid JSON" });
      return;
    }
    const auth = resolveMachineAuth({
      req,
      userId: payload.userId,
      token: payload.token,
    });
    if (!auth) {
      sendJson(res, 401, { error: "unauthorized" });
      return;
    }
    const signatureError = verifySignedRequest({
      req,
      body: raw,
      scope: `machine-heartbeat:${auth.user.id}`,
    });
    if (signatureError) {
      sendJson(res, 401, { error: signatureError });
      return;
    }
    const machine = resolveOwnedMachine(auth.user.id, payload.machineId);
    if (!machine) {
      sendJson(res, 404, { error: "machine not found" });
      return;
    }
    const status = normalizeMachineStatus(payload.status);
    if (payload.status && !status) {
      sendJson(res, 400, { error: "invalid status" });
      return;
    }
    const next = touchMachineHeartbeat({
      entry: machine,
      status,
    });
    sendJson(res, 200, {
      ok: true,
      machine: sanitizeMachineRecordForResponse(next),
      config: { routing: next.routing ?? {} },
      serverTime: Date.now(),
    });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/machine/config") {
    const auth = resolveMachineAuth({
      req,
      userId: url.searchParams.get("userId") ?? undefined,
      token: url.searchParams.get("token") ?? undefined,
    });
    if (!auth) {
      sendJson(res, 401, { error: "unauthorized" });
      return;
    }
    const machineId = normalizeMachineId(url.searchParams.get("machineId") ?? undefined);
    if (!machineId) {
      sendJson(res, 400, { error: "machineId required" });
      return;
    }
    const machine = resolveOwnedMachine(auth.user.id, machineId);
    if (!machine) {
      sendJson(res, 404, { error: "machine not found" });
      return;
    }
    sendJson(res, 200, {
      ok: true,
      machine: sanitizeMachineRecordForResponse(machine),
      config: { routing: machine.routing ?? {} },
      serverTime: Date.now(),
    });
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/machines/contributors") {
    if (!serverToken) {
      sendJson(res, 403, { error: "server token disabled" });
      return;
    }
    if (!verifyAdminServerToken(req)) {
      sendJson(res, 401, { error: "unauthorized" });
      return;
    }
    const raw = await readBody(req, 1024 * 1024).catch((err: Error) => {
      sendJson(res, 413, { error: err.message });
      return null;
    });
    if (!raw) return;
    const payload = parseJson<MachineContributorPayload>(raw);
    if (!payload) {
      sendJson(res, 400, { error: "invalid JSON" });
      return;
    }

    const requestedUserId = normalizeUserId(payload.userId ?? undefined);
    const contributorPassword = normalizePassword(payload.password ?? undefined);
    if (!contributorPassword) {
      sendJson(res, 400, { error: "password required" });
      return;
    }
    const userId = requestedUserId ?? generateContributorUserId();
    if (users.has(userId)) {
      sendJson(res, 409, { error: "user exists" });
      return;
    }

    const machineId =
      normalizeMachineId(payload.machineId ?? undefined) ??
      `m_${randomUUID().replace(/-/g, "").slice(0, 16)}`;
    if (machines.has(machineId)) {
      sendJson(res, 409, { error: "machineId already in use" });
      return;
    }

    const hasRoutingField = Object.prototype.hasOwnProperty.call(payload, "routing");
    const routing = normalizeMachineRoutingConfig(payload.routing);
    if (hasRoutingField && payload.routing && !routing) {
      sendJson(res, 400, { error: "invalid routing" });
      return;
    }

    const displayName = normalizeHintField(payload.machineLabel, 80) ?? userId;
    const accountId = normalizeAccountIdRef(payload.accountId) ?? "default";
    const contributor: UserRecord = {
      id: userId,
      displayName,
      passwordHash: hashPassword(contributorPassword),
    };

    let token = generateToken();
    while (isTokenInUse(token, userId)) {
      token = generateToken();
    }
    addUserToken(contributor, token);

    try {
      const nextUsers = [...users.values(), contributor].sort((a, b) => a.id.localeCompare(b.id));
      saveUsersSnapshot(nextUsers);
    } catch (err) {
      sendJson(res, 500, { error: String(err) });
      return;
    }
    users.set(userId, contributor);

    const now = Date.now();
    const machine: MachineRecord = {
      machineId,
      userId,
      accountId,
      machineLabel: displayName,
      status: "offline",
      createdAt: now,
      updatedAt: now,
      lastSeenAt: now,
      routing,
    };
    machines.set(machineId, machine);
    scheduleMachinesSave();

    sendJson(res, 200, {
      ok: true,
      contributor: {
        userId,
        token,
        machineId,
        accountId,
      },
      machine: sanitizeMachineRecordForResponse(machine),
      serverTime: now,
    });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/machines") {
    const isAdmin = Boolean(serverToken) && verifyAdminServerToken(req);
    const userAuth = isAdmin
      ? null
      : resolveMachineAuth({
          req,
          userId: url.searchParams.get("userId") ?? undefined,
          token: url.searchParams.get("token") ?? undefined,
        });
    if (!isAdmin && !userAuth) {
      sendJson(res, 401, { error: "unauthorized" });
      return;
    }
    const machineList = [...machines.values()]
      .filter((entry) => (isAdmin ? true : entry.userId === userAuth?.user.id))
      .sort((a, b) => b.lastSeenAt - a.lastSeenAt)
      .map((entry) => sanitizeMachineRecordForResponse(entry));
    sendJson(res, 200, {
      ok: true,
      count: machineList.length,
      machines: machineList,
      scope: isAdmin ? "admin" : "user",
      serverTime: Date.now(),
    });
    return;
  }

  if (url.pathname.startsWith("/api/machines/")) {
    const isAdmin = Boolean(serverToken) && verifyAdminServerToken(req);
    const userAuth = isAdmin
      ? null
      : resolveMachineAuth({
          req,
          userId: url.searchParams.get("userId") ?? undefined,
          token: url.searchParams.get("token") ?? undefined,
        });
    if (!isAdmin && !userAuth) {
      sendJson(res, 401, { error: "unauthorized" });
      return;
    }
    const rawMachineId = url.pathname.slice("/api/machines/".length);
    const machineId = normalizeMachineId(decodeURIComponent(rawMachineId));
    if (!machineId) {
      sendJson(res, 400, { error: "invalid machineId" });
      return;
    }
    const current = machines.get(machineId);
    if (!current) {
      sendJson(res, 404, { error: "machine not found" });
      return;
    }
    if (!isAdmin && current.userId !== userAuth?.user.id) {
      sendJson(res, 404, { error: "machine not found" });
      return;
    }

    if (req.method === "GET") {
      sendJson(res, 200, {
        ok: true,
        machine: sanitizeMachineRecordForResponse(current),
        serverTime: Date.now(),
      });
      return;
    }

    if (req.method === "PATCH") {
      const raw = await readBody(req, 1024 * 1024).catch((err: Error) => {
        sendJson(res, 413, { error: err.message });
        return null;
      });
      if (!raw) return;
      const payload = parseJson<MachinePatchPayload>(raw);
      if (!payload) {
        sendJson(res, 400, { error: "invalid JSON" });
        return;
      }

      const hasRoutingField = Object.prototype.hasOwnProperty.call(payload, "routing");
      const routing = normalizeMachineRoutingConfig(payload.routing);
      if (hasRoutingField && payload.routing && !routing) {
        sendJson(res, 400, { error: "invalid routing" });
        return;
      }
      const hasStatusField = Object.prototype.hasOwnProperty.call(payload, "status");
      const status = normalizeMachineStatus(payload.status);
      if (hasStatusField && payload.status && !status) {
        sendJson(res, 400, { error: "invalid status" });
        return;
      }
      const hasMachineLabelField = Object.prototype.hasOwnProperty.call(payload, "machineLabel");
      const machineLabel = normalizeHintField(payload.machineLabel, 80);

      const now = Date.now();
      const next: MachineRecord = {
        ...current,
        updatedAt: now,
        status: status ?? current.status,
        routing: hasRoutingField ? routing : current.routing,
        machineLabel: hasMachineLabelField ? machineLabel : current.machineLabel,
      };
      machines.set(machineId, next);
      scheduleMachinesSave();

      sendJson(res, 200, {
        ok: true,
        machine: sanitizeMachineRecordForResponse(next),
        serverTime: Date.now(),
      });
      return;
    }

    sendJson(res, 405, { error: "method not allowed" });
    return;
  }

  if (req.method === "GET" && url.pathname === "/") {
    serveFile(res, resolve(publicDir, "index.html"));
    return;
  }

  if (req.method === "GET" && (url.pathname === "/admin" || url.pathname === "/admin.html")) {
    serveFile(res, resolve(publicDir, "admin.html"));
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/stream") {
    const userId = url.searchParams.get("userId") ?? readUserIdHeader(req) ?? "";
    const secret = readBearerToken(req) || url.searchParams.get("token") || "";
    const auth = resolveAuthMatch({ userId, secret, allowPassword: false });
    if (!auth) {
      sendJson(res, 401, { error: "unauthorized" });
      return;
    }
    const deviceKey = makeDeviceKey(auth.user.id, auth.secret);
    const rawLastEvent =
      url.searchParams.get("lastEventId") ??
      (typeof req.headers["last-event-id"] === "string" ? req.headers["last-event-id"] : "");
    const lastEventId = Number.parseInt(rawLastEvent ?? "", 10);
    updateTokenUsage(auth.user, auth.secret, {
      lastSeenAt: Date.now(),
      streamConnectsDelta: 1,
    });
    attachClient(deviceKey, res, Number.isFinite(lastEventId) ? lastEventId : undefined);
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/register") {
    if (!allowRegistration) {
      sendJson(res, 403, { error: "registration disabled" });
      return;
    }
    const clientId = resolveClientIp(req);
    if (!checkRateLimit(`register:${clientId}`, 10, 10 * 60 * 1000)) {
      sendJson(res, 429, { error: "rate limited" });
      return;
    }
    const raw = await readBody(req, 1024 * 1024).catch((err: Error) => {
      sendJson(res, 413, { error: err.message });
      return null;
    });
    if (!raw) return;
    const payload = parseJson<{
      userId?: string;
      displayName?: string;
      password?: string;
      inviteCode?: string;
      serverToken?: string;
    }>(raw);
    if (!payload) {
      sendJson(res, 400, { error: "invalid JSON" });
      return;
    }
    const authToken = readBearerToken(req) || payload.serverToken?.trim() || "";
    const hasServerAuth = Boolean(serverToken && authToken === serverToken);
    if (serverToken && !hasServerAuth) {
      sendJson(res, 401, { error: "unauthorized" });
      return;
    }
    if (!hasServerAuth && !isInviteCodeValid(payload.inviteCode)) {
      sendJson(res, 403, { error: "invalid invite code" });
      return;
    }
    const requestedId = normalizeUserId(payload.userId);
    if (!requestedId) {
      sendJson(res, 400, { error: "userId required" });
      return;
    }
    const finalId = requestedId;
    if (users.has(finalId)) {
      sendJson(res, 409, { error: "user exists" });
      return;
    }
    const displayName = payload.displayName?.trim();
    const password = normalizePassword(payload.password);
    if (!password) {
      sendJson(res, 400, { error: "password required" });
      return;
    }
    const entry: UserRecord = {
      id: finalId,
      passwordHash: hashPassword(password),
      displayName: displayName || undefined,
    };
    try {
      const next = [...users.values(), entry].sort((a, b) => a.id.localeCompare(b.id));
      saveUsersSnapshot(next);
      users.set(entry.id, entry);
      sendJson(res, 200, {
        ok: true,
        userId: entry.id,
        displayName: entry.displayName ?? entry.id,
      });
    } catch (err) {
      sendJson(res, 500, { error: String(err) });
    }
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/account/login") {
    const clientId = resolveClientIp(req);
    if (!checkRateLimit(`account-login:${clientId}`, 30, 60 * 1000)) {
      sendJson(res, 429, { error: "rate limited" });
      return;
    }
    const raw = await readBody(req, 1024 * 1024).catch((err: Error) => {
      sendJson(res, 413, { error: err.message });
      return null;
    });
    if (!raw) return;
    const payload = parseJson<{ userId?: string; password?: string }>(raw);
    const userId = normalizeUserId(payload?.userId);
    const password = normalizePassword(payload?.password);
    if (!userId || !password) {
      sendJson(res, 400, { error: "userId and password required" });
      return;
    }
    const user = getUserByPassword(userId, password);
    if (!user) {
      sendJson(res, 401, { error: "unauthorized" });
      return;
    }
    sendJson(res, 200, {
      ok: true,
      userId: user.id,
      displayName: user.displayName ?? user.id,
    });
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/token") {
    const clientId = resolveClientIp(req);
    if (!checkRateLimit(`token:${clientId}`, 30, 60 * 1000)) {
      sendJson(res, 429, { error: "rate limited" });
      return;
    }
    const raw = await readBody(req, 1024 * 1024).catch((err: Error) => {
      sendJson(res, 413, { error: err.message });
      return null;
    });
    if (!raw) return;
    const payload = parseJson<{ userId?: string; password?: string }>(raw);
    const userId = normalizeUserId(payload?.userId);
    const password = normalizePassword(payload?.password);
    if (!userId || !password) {
      sendJson(res, 400, { error: "userId and password required" });
      return;
    }
    const user = getUserByPassword(userId, password);
    if (!user) {
      sendJson(res, 401, { error: "unauthorized" });
      return;
    }
    let token = generateToken();
    while (isTokenInUse(token, user.id)) {
      token = generateToken();
    }
    addUserToken(user, token);
    try {
      const entries = [...users.values()].sort((a, b) => a.id.localeCompare(b.id));
      saveUsersSnapshot(entries);
    } catch (err) {
      sendJson(res, 500, { error: String(err) });
      return;
    }
    sendJson(res, 200, {
      ok: true,
      userId: user.id,
      token,
    });
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/token/usage") {
    const clientId = resolveClientIp(req);
    if (!checkRateLimit(`token-usage:${clientId}`, 60, 60 * 1000)) {
      sendJson(res, 429, { error: "rate limited" });
      return;
    }
    const raw = await readBody(req, 1024 * 1024).catch((err: Error) => {
      sendJson(res, 413, { error: err.message });
      return null;
    });
    if (!raw) return;
    const payload = parseJson<{ userId?: string; password?: string }>(raw);
    const userId = normalizeUserId(payload?.userId);
    const password = normalizePassword(payload?.password);
    if (!userId || !password) {
      sendJson(res, 400, { error: "userId and password required" });
      return;
    }
    const user = getUserByPassword(userId, password);
    if (!user) {
      sendJson(res, 401, { error: "unauthorized" });
      return;
    }
    const usage = normalizeTokenUsage(user.tokenUsage, resolveUserTokens(user));
    sendJson(res, 200, {
      ok: true,
      userId: user.id,
      usage: Object.values(usage),
    });
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/login") {
    const clientId = resolveClientIp(req);
    if (!checkRateLimit(`login:${clientId}`, 60, 60 * 1000)) {
      sendJson(res, 429, { error: "rate limited" });
      return;
    }
    const raw = await readBody(req, 1024 * 1024).catch((err: Error) => {
      sendJson(res, 413, { error: err.message });
      return null;
    });
    if (!raw) return;
    const payload = parseJson<{ userId?: string; token?: string }>(raw);
    const token = payload?.token?.trim();
    if (!token) {
      sendJson(res, 400, { error: "token required" });
      return;
    }
    const tokenHash = normalizeTokenHash(token);
    if (!tokenHash) {
      sendJson(res, 400, { error: "token required" });
      return;
    }
    const requestedUserId = normalizeUserId(payload?.userId);
    let user: UserRecord | null = null;
    if (requestedUserId) {
      user = users.get(requestedUserId) ?? null;
      if (!user || !hasUserToken(user, token)) {
        sendJson(res, 401, { error: "unauthorized" });
        return;
      }
    } else {
      user = getUserByToken(token);
      if (!user) {
        sendJson(res, 401, { error: "unauthorized" });
        return;
      }
    }
    updateTokenUsage(user, tokenHash, { lastSeenAt: Date.now() });
    sendJson(res, 200, {
      ok: true,
      userId: user.id,
      token,
      displayName: user.displayName ?? user.id,
    });
    return;
  }

  if (req.method === "GET" && url.pathname === "/api/poll") {
    const userId = url.searchParams.get("userId") ?? readUserIdHeader(req) ?? "";
    const secret = readBearerToken(req) || url.searchParams.get("token") || "";
    const auth = resolveAuthMatch({ userId, secret, allowPassword: false });
    if (!auth) {
      sendJson(res, 401, { error: "unauthorized" });
      return;
    }
    updateTokenUsage(auth.user, auth.secret, { lastSeenAt: Date.now() });
    const signatureError = verifySignedRequest({
      req,
      body: "",
      scope: `poll:${auth.user.id}`,
    });
    if (signatureError) {
      sendJson(res, 401, { error: signatureError });
      return;
    }
    const rawWait = Number.parseInt(url.searchParams.get("waitMs") ?? "", 10);
    const waitMs = Number.isFinite(rawWait)
      ? Math.max(0, Math.min(rawWait, 30000))
      : 20000;
    const deviceKey = makeDeviceKey(auth.user.id, auth.secret);
    const messages = await waitForInbound(deviceKey, waitMs, req);
    if (req.aborted || res.writableEnded) return;
    sendJson(res, 200, { ok: true, messages });
    return;
  }

  if (req.method === "POST" && url.pathname === "/api/message") {
    const raw = await readBody(req, 1024 * 1024).catch((err: Error) => {
      sendJson(res, 413, { error: err.message });
      return null;
    });
    if (!raw) return;
    const payload = parseJson<IncomingClientMessage>(raw);
    const resolvedUserId = payload?.userId || readUserIdHeader(req) || "";
    const secret = payload?.token || readBearerToken(req) || "";
    if (!secret || !payload?.text) {
      sendJson(res, 400, { error: "token and text required" });
      return;
    }
    const auth = resolveAuthMatch({ userId: resolvedUserId, secret, allowPassword: false });
    if (!auth) {
      sendJson(res, 401, { error: "unauthorized" });
      return;
    }
    const now = Date.now();
    updateTokenUsage(auth.user, auth.secret, {
      lastSeenAt: now,
      inboundCountDelta: 1,
      lastInboundAt: now,
    });
    const deviceKey = makeDeviceKey(auth.user.id, auth.secret);

    const modelTierId = normalizeInstanceTierId(payload?.instanceModelTierId);
    const identityId = normalizeInstanceIdentityId(payload?.instanceIdentityId);
    const normalizedChatId = normalizeChatId(auth.user.id, payload?.chatId);
    if (modelTierId && identityId && normalizedChatId) {
      upsertInstanceConfig({
        userId: auth.user.id,
        chatId: normalizedChatId,
        modelTierId,
        identityId,
      });
    }

    const message = buildInboundMessage(
      {
        ...payload,
        userId: auth.user.id,
        token: payload?.token,
      },
      auth.user,
      deviceKey,
    );
    if (inboundMode === "webhook") {
      try {
        await forwardToGateway(message, auth.user);
        sendJson(res, 200, { ok: true, delivered: true });
      } catch (err) {
        sendJson(res, 502, { error: String(err) });
      }
    } else {
      enqueueInbound(deviceKey, message);
      sendJson(res, 200, { ok: true, queued: true });
    }
    return;
  }

  if (req.method === "POST" && url.pathname === "/send") {
    const raw = await readBody(req, 1024 * 1024).catch((err: Error) => {
      sendJson(res, 413, { error: err.message });
      return null;
    });
    if (!raw) return;
    const payload = parseJson<SendPayload>(raw);
    if (!payload?.chatId || !payload.text) {
      sendJson(res, 400, { error: "chatId and text required" });
      return;
    }
    const owner = resolveOwnerForChatId(payload.chatId);
    if (!owner) {
      const userId = extractUserIdFromChatId(payload.chatId);
      if (!userId) {
        sendJson(res, 400, { error: "invalid chatId" });
        return;
      }
      sendJson(res, 404, { error: "unknown user" });
      return;
    }
    const signatureError = verifySignedRequest({ req, body: raw, scope: `send:${owner.user.id}` });
    if (signatureError) {
      sendJson(res, 401, { error: signatureError });
      return;
    }
    if (!verifyServerToken(req, owner.user)) {
      sendJson(res, 401, { error: "unauthorized" });
      return;
    }
    const now = Date.now();
    const token = extractTokenFromDeviceKey(owner.deviceKey);
    if (token) {
      updateTokenUsage(owner.user, token, {
        lastSeenAt: now,
        outboundCountDelta: 1,
        lastOutboundAt: now,
      });
    }
    sendToDevice(owner.deviceKey, {
      type: "message",
      chatId: payload.chatId,
      text: payload.text,
      replyToId: payload.replyToId ?? null,
      receivedAt: now,
    });
    sendJson(res, 200, { ok: true, delivered: true });
    return;
  }

  sendText(res, 404, "Not Found");
});

server.listen(Number.isFinite(port) ? port : 18788, bindHost, () => {
  const usersCount = users.size;
  const location = Number.isFinite(port) ? port : 18788;
  // eslint-disable-next-line no-console
  console.log(`VimaClawNet Server listening on http://${bindHost}:${location}`);
  // eslint-disable-next-line no-console
  console.log(`inbound mode: ${inboundMode}`);
  if (!gatewayUrl && inboundMode === "webhook") {
    // eslint-disable-next-line no-console
    console.log("warning: TEST_GATEWAY_URL is not set");
  }
  if (signatureRequired && !hmacSecret) {
    // eslint-disable-next-line no-console
    console.log("warning: TEST_REQUIRE_SIGNATURE is true but TEST_HMAC_SECRET is missing");
  }
  if (usersCount === 0) {
    // eslint-disable-next-line no-console
    console.log("warning: no users configured; set TEST_USERS_FILE or TEST_USERS");
  }
});
