import { createHmac, randomBytes, randomUUID, scryptSync, timingSafeEqual } from "node:crypto";
import { createServer } from "node:http";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

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

const users = new Map();
const machines = new Map();
const instances = new Map();
let didMigrateUsers = false;
let pendingUsersSave = null;
let pendingMachinesSave = null;
let pendingInstancesSave = null;

if (!hasSecretKey) {
  console.log("warning: TEST_SECRET_KEY not set; token hashing is disabled.");
}

function normalizeToken(value) {
  const trimmed = value?.trim();
  return trimmed ? trimmed : null;
}

function normalizeTokens(values) {
  const tokens = [];
  const seen = new Set();
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

function isTokenHash(value) {
  return value.startsWith(TOKEN_HASH_PREFIX);
}

function hashToken(value) {
  if (!hasSecretKey) return value;
  const digest = createHmac("sha256", secretKey).update(value).digest("hex");
  return `${TOKEN_HASH_PREFIX}${digest}`;
}

function normalizeTokenHash(value) {
  const normalized = normalizeToken(value);
  if (!normalized) return null;
  if (!hasSecretKey) return normalized;
  if (isTokenHash(normalized)) return normalized;
  return hashToken(normalized);
}

function hashPassword(password) {
  const salt = randomBytes(16).toString("hex");
  const derived = scryptSync(password, salt, SCRYPT_KEY_LEN, {
    N: SCRYPT_N,
    r: SCRYPT_R,
    p: SCRYPT_P,
  }).toString("hex");
  return `${PASSWORD_HASH_PREFIX}${SCRYPT_N}$${SCRYPT_R}$${SCRYPT_P}$${salt}$${derived}`;
}

function parsePasswordHash(raw) {
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

function verifyPassword(password, rawHash) {
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

function normalizeUsageNumber(value) {
  if (!Number.isFinite(value)) return void 0;
  return Math.max(0, Math.floor(value ?? 0));
}

function normalizeTokenUsage(raw, tokens) {
  const usage = {};
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

function normalizeInviteCodes(raw) {
  return raw
    .split(/[\n,;]+/g)
    .map((entry) => entry.trim())
    .filter(Boolean);
}

function isInviteCodeValid(code) {
  if (inviteCodes.length === 0) return true;
  const trimmed = code?.trim();
  if (!trimmed) return false;
  return inviteCodes.includes(trimmed);
}

function normalizeUserRecord(entry) {
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
  let passwordHash = entry.passwordHash?.trim() || void 0;
  let password = entry.password?.trim() || void 0;
  if (!passwordHash && password) {
    passwordHash = hashPassword(password);
    password = void 0;
    migrated = true;
  }
  const displayName = entry.displayName?.trim() || void 0;
  const gatewayUrl = entry.gatewayUrl?.trim() || void 0;
  const gatewayToken = entry.gatewayToken?.trim() || void 0;
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

function addUser(entry) {
  const normalized = normalizeUserRecord(entry);
  if (!normalized) return;
  users.set(normalized.id, normalized);
}

function loadUsers() {
  if (usersInline) {
    const parsed = JSON.parse(usersInline);
    for (const entry of parsed.users ?? []) {
      addUser(entry);
    }
  }
  if (usersFilePath) {
    const raw = readFileSync(usersFilePath, "utf-8");
    const parsed = JSON.parse(raw);
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

function normalizeMachineRecord(entry) {
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
    const parsed = parseJson(raw);
    for (const entry of parsed?.machines ?? []) {
      const normalized = normalizeMachineRecord(entry);
      if (!normalized) continue;
      machines.set(normalized.machineId, normalized);
    }
  } catch {
  }
}

loadMachines();

function resolveInstanceKey(userId, chatId) {
  return `${userId}:${chatId}`;
}

const INSTANCE_TIERS = [
  { id: "m2.5", label: "Standard", modelHint: "minimax/m2.5" },
  { id: "glm-4.7", label: "Pro", modelHint: "zai/glm-4.7" },
  { id: "glm-5", label: "Max", modelHint: "zai/glm-5" },
];

const INSTANCE_IDENTITIES = [
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

function normalizeInstanceTierId(value) {
  const trimmed = typeof value === "string" ? value.trim() : "";
  if (!trimmed) return null;
  return INSTANCE_TIERS.some((t) => t.id === trimmed) ? trimmed : null;
}

function normalizeInstanceIdentityId(value) {
  const trimmed = typeof value === "string" ? value.trim() : "";
  if (!trimmed) return null;
  return INSTANCE_IDENTITIES.some((t) => t.id === trimmed) ? trimmed : null;
}

function normalizeInstanceRecord(entry) {
  const userId = normalizeUserId(entry.userId);
  const chatId = typeof entry.chatId === "string" ? entry.chatId.trim() : "";
  const modelTierId = normalizeInstanceTierId(entry.modelTierId);
  const identityId = normalizeInstanceIdentityId(entry.identityId);
  const now = Date.now();
  const createdAt = normalizeUsageNumber(entry.createdAt) ?? now;
  const updatedAt = normalizeUsageNumber(entry.updatedAt) ?? createdAt;
  if (!userId || !chatId || !modelTierId || !identityId) return null;
  return { userId, chatId, modelTierId, identityId, createdAt, updatedAt };
}

function loadInstances() {
  if (!instancesFilePath) return;
  try {
    const raw = readFileSync(instancesFilePath, "utf-8");
    const parsed = parseJson(raw);
    for (const entry of parsed?.instances ?? []) {
      const normalized = normalizeInstanceRecord(entry);
      if (!normalized) continue;
      instances.set(resolveInstanceKey(normalized.userId, normalized.chatId), normalized);
    }
  } catch {
  }
}

loadInstances();

function normalizeUserId(value) {
  const trimmed = value?.trim();
  if (!trimmed) return null;
  const normalized = trimmed.toLowerCase();
  if (!/^[a-z0-9_-]{2,32}$/.test(normalized)) {
    return null;
  }
  return normalized;
}

function normalizePassword(value) {
  const trimmed = value?.trim();
  if (!trimmed) return null;
  if (trimmed.length < 6 || trimmed.length > 64) {
    return null;
  }
  return trimmed;
}

function normalizeHintField(value, maxLength = 120) {
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  if (!trimmed) return undefined;
  return trimmed.slice(0, maxLength);
}

function normalizeModeId(value) {
  const normalized = normalizeHintField(value, 32)?.toLowerCase();
  if (!normalized) return undefined;
  if (!/^[a-z0-9_-]{1,32}$/.test(normalized)) return undefined;
  return normalized;
}

function resolveModeMetadata(payload) {
  return {
    modeId: normalizeModeId(payload.modeId),
    modeLabel: normalizeHintField(payload.modeLabel, 40),
    modelHint: normalizeHintField(payload.modelHint, 120),
    agentHint: normalizeHintField(payload.agentHint, 120),
    skillsHint: normalizeHintField(payload.skillsHint, 160),
  };
}

function normalizeAccountIdRef(value) {
  if (typeof value !== "string") return undefined;
  const normalized = value.trim().toLowerCase();
  if (!normalized) return undefined;
  if (!/^[a-z0-9_-]{1,64}$/.test(normalized)) return undefined;
  return normalized;
}

function normalizeMachineId(value) {
  if (typeof value !== "string") return undefined;
  const normalized = value.trim().toLowerCase();
  if (!normalized) return undefined;
  if (!/^[a-z0-9][a-z0-9_-]{2,63}$/.test(normalized)) return undefined;
  return normalized;
}

function normalizeMachineStatus(value) {
  if (value === "online" || value === "offline") return value;
  return undefined;
}

function normalizeModeAccountMap(raw) {
  if (!raw || typeof raw !== "object") return undefined;
  const next = {};
  for (const [rawMode, rawAccount] of Object.entries(raw)) {
    const modeId = normalizeModeId(rawMode);
    const accountId = normalizeAccountIdRef(rawAccount);
    if (!modeId || !accountId) continue;
    next[modeId] = accountId;
  }
  return Object.keys(next).length > 0 ? next : undefined;
}

function normalizeModeHintMap(raw, maxLength) {
  if (!raw || typeof raw !== "object") return undefined;
  const next = {};
  for (const [rawMode, rawHint] of Object.entries(raw)) {
    const modeId = normalizeModeId(rawMode);
    const hint = normalizeHintField(rawHint, maxLength);
    if (!modeId || !hint) continue;
    next[modeId] = hint;
  }
  return Object.keys(next).length > 0 ? next : undefined;
}

function normalizeMachineRoutingConfig(raw) {
  if (!raw || typeof raw !== "object") return undefined;
  const modeAccountMap = normalizeModeAccountMap(raw.modeAccountMap);
  const modeModelHints = normalizeModeHintMap(raw.modeModelHints, 120);
  const modeAgentHints = normalizeModeHintMap(raw.modeAgentHints, 120);
  const modeSkillsHints = normalizeModeHintMap(raw.modeSkillsHints, 160);
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

function sanitizeMachineRecordForResponse(record) {
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

function generateToken() {
  return randomUUID().replace(/-/g, "");
}

function generateContributorUserId() {
  let next = `contrib_${randomUUID().replace(/-/g, "").slice(0, 10)}`;
  while (users.has(next)) {
    next = `contrib_${randomUUID().replace(/-/g, "").slice(0, 10)}`;
  }
  return next;
}

function makeDeviceKey(userId, token) {
  return `${userId}:${token}`;
}

function extractTokenFromDeviceKey(deviceKey) {
  const idx = deviceKey.indexOf(":");
  if (idx < 0) return null;
  return normalizeToken(deviceKey.slice(idx + 1));
}

function resolveUserTokens(entry) {
  return normalizeTokens([entry.token, ...(entry.tokens ?? [])]);
}

function hasUserToken(entry, token) {
  const normalized = normalizeTokenHash(token);
  if (!normalized) return false;
  return resolveUserTokens(entry).includes(normalized);
}

function addUserToken(entry, token) {
  const tokens = resolveUserTokens(entry);
  const tokenHash = normalizeTokenHash(token);
  if (!tokenHash) return;
  if (!tokens.includes(tokenHash)) tokens.push(tokenHash);
  entry.tokens = tokens;
  if (!entry.token) entry.token = tokenHash;
  updateTokenUsage(entry, tokenHash, { createdAt: Date.now(), lastSeenAt: Date.now() });
}

function serializeUserRecord(entry) {
  const tokens = resolveUserTokens(entry);
  const tokenUsage = normalizeTokenUsage(entry.tokenUsage, tokens);
  return {
    ...entry,
    token: tokens[0],
    tokens,
    password: void 0,
    tokenUsage,
  };
}

function saveUsersSnapshot(entries) {
  if (!usersWritePath) {
    throw new Error("TEST_USERS_FILE is not set; cannot persist registrations.");
  }
  mkdirSync(dirname(usersWritePath), { recursive: true });
  const data = JSON.stringify({ users: entries.map(serializeUserRecord) }, null, 2);
  writeFileSync(usersWritePath, data, "utf-8");
}

function scheduleUsersSave() {
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

function saveMachinesSnapshot(entries) {
  if (!machinesFilePath) return;
  mkdirSync(dirname(machinesFilePath), { recursive: true });
  const data = JSON.stringify({ machines: entries }, null, 2);
  writeFileSync(machinesFilePath, data, "utf-8");
}

function scheduleMachinesSave() {
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

function scheduleInstancesSave() {
  if (!instancesFilePath) return;
  if (pendingInstancesSave) return;
  pendingInstancesSave = setTimeout(() => {
    pendingInstancesSave = null;
    try {
      mkdirSync(dirname(instancesFilePath), { recursive: true });
      const entries = [...instances.values()].sort((a, b) => {
        if (a.userId !== b.userId) return a.userId.localeCompare(b.userId);
        return a.chatId.localeCompare(b.chatId);
      });
      const data = JSON.stringify({ instances: entries }, null, 2);
      writeFileSync(instancesFilePath, data, "utf-8");
    } catch {
    }
  }, 250);
}

function upsertInstanceConfig(params) {
  const key = resolveInstanceKey(params.userId, params.chatId);
  const existing = instances.get(key);
  const now = Date.now();
  const next = {
    userId: params.userId,
    chatId: params.chatId,
    modelTierId: params.modelTierId,
    identityId: params.identityId,
    createdAt: existing?.createdAt ?? now,
    updatedAt: now,
  };
  instances.set(key, next);
  scheduleInstancesSave();
  return next;
}

function encodeInstanceModeId(config) {
  const tier = String(config.modelTierId || "").trim().toLowerCase().replace(/[^a-z0-9]/g, "_");
  const identity = String(config.identityId || "").trim().toLowerCase().replace(/[^a-z0-9]/g, "_");
  return `inst_${tier}_${identity}`;
}

function resolveInstanceModeMetadata(config) {
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

function upsertMachineRecord(params) {
  const machineId =
    normalizeMachineId(params.machineId) ?? `m_${randomUUID().replace(/-/g, "").slice(0, 16)}`;
  const now = Date.now();
  const existing = machines.get(machineId);
  const createdAt = existing?.createdAt ?? now;
  const next = {
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

function resolveOwnedMachine(userId, machineId) {
  const normalized = normalizeMachineId(machineId);
  if (!normalized) return null;
  const entry = machines.get(normalized);
  if (!entry) return null;
  if (entry.userId !== userId) return null;
  return entry;
}

function touchMachineHeartbeat(params) {
  const now = Date.now();
  const next = {
    ...params.entry,
    status: params.status ?? "online",
    updatedAt: now,
    lastSeenAt: now,
  };
  machines.set(next.machineId, next);
  scheduleMachinesSave();
  return next;
}

function ensureTokenUsage(entry, token) {
  const normalized = normalizeTokenHash(token);
  if (!normalized) return null;
  const usage = normalizeTokenUsage(entry.tokenUsage, resolveUserTokens(entry));
  const existing = usage[normalized];
  const createdAt = existing?.createdAt ?? Date.now();
  const next = {
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

function updateTokenUsage(entry, token, patch) {
  const usage = ensureTokenUsage(entry, token);
  if (!usage) return;
  const next = {
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

function sendJson(res, status, body) {
  res.statusCode = status;
  res.setHeader("Content-Type", "application/json; charset=utf-8");
  res.end(JSON.stringify(body));
}

function sendText(res, status, body) {
  res.statusCode = status;
  res.setHeader("Content-Type", "text/plain; charset=utf-8");
  res.end(body);
}

function readBody(req, maxBytes) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on("data", (chunk) => {
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

function parseJson(raw) {
  try {
    return JSON.parse(raw);
  } catch {
    return null;
  }
}

function createSignature(params) {
  return createHmac("sha256", params.secret)
    .update(`${params.timestamp}.${params.nonce}.${params.body}`)
    .digest("hex");
}

function verifySignature(params) {
  const expected = createSignature({
    secret: params.secret,
    timestamp: params.timestamp,
    nonce: params.nonce,
    body: params.body,
  });
  if (expected.length !== params.signature.length) return false;
  return timingSafeEqual(Buffer.from(expected, "utf-8"), Buffer.from(params.signature, "utf-8"));
}

function readSignatureHeaders(req) {
  const timestampRaw = String(req.headers["x-test-timestamp"] ?? "").trim();
  const nonce = String(req.headers["x-test-nonce"] ?? "").trim();
  const signature = String(req.headers["x-test-signature"] ?? "").trim();
  const timestamp = Number(timestampRaw);
  return {
    timestamp: Number.isFinite(timestamp) ? timestamp : null,
    nonce,
    signature,
  };
}

function checkAndStoreNonce(scope, nonce, now, ttlMs) {
  const store = nonceWindows.get(scope) ?? new Map();
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

function verifySignedRequest(params) {
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

const rateLimits = new Map();

function resolveClientIp(req) {
  if (trustProxy) {
    const forwarded = String(req.headers["x-forwarded-for"] ?? "").trim();
    if (forwarded) return forwarded.split(",")[0]?.trim() || "unknown";
  }
  return req.socket.remoteAddress ?? "unknown";
}

function checkRateLimit(key, limit, windowMs) {
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

function getUser(userId, token) {
  if (!userId || !token) return null;
  const entry = users.get(userId);
  if (!entry) return null;
  return hasUserToken(entry, token) ? entry : null;
}

function getUserByPassword(userId, password) {
  if (!userId || !password) return null;
  const entry = users.get(userId);
  if (!entry) return null;
  if (entry.passwordHash) {
    return verifyPassword(password, entry.passwordHash) ? entry : null;
  }
  if (entry.password && entry.password === password) {
    entry.passwordHash = hashPassword(password);
    entry.password = void 0;
    scheduleUsersSave();
    return entry;
  }
  return null;
}

function getUserByToken(token) {
  if (!token) return null;
  for (const entry of users.values()) {
    if (hasUserToken(entry, token)) return entry;
  }
  return null;
}

function resolveAuthMatch({ userId, secret, allowPassword }) {
  const id = userId?.trim();
  const value = secret?.trim();
  if (!value) return null;
  const tokenHash = normalizeTokenHash(value);
  if (id) {
    const tokenMatch = getUser(id, value);
    if (tokenMatch && tokenHash) return { user: tokenMatch, secret: tokenHash, kind: "token" };
    if (allowPassword) {
      const passwordMatch = getUserByPassword(id, value);
      if (passwordMatch) return { user: passwordMatch, secret: value, kind: "password" };
    }
  }
  const tokenMatch = getUserByToken(value);
  if (tokenMatch && tokenHash) return { user: tokenMatch, secret: tokenHash, kind: "token" };
  return null;
}

function isTokenInUse(token, excludeUserId) {
  for (const entry of users.values()) {
    if (excludeUserId && entry.id === excludeUserId) continue;
    if (hasUserToken(entry, token)) return true;
  }
  return false;
}

function normalizeChatId(userId, chatId) {
  if (chatId && chatId.trim()) return chatId.trim();
  return `user:${userId}`;
}

function extractUserIdFromChatId(chatId) {
  if (!chatId) return null;
  const trimmed = chatId.trim();
  if (trimmed.startsWith("user:")) return trimmed.slice("user:".length).trim();
  if (trimmed.startsWith("test:")) return trimmed.slice("test:".length).trim();
  return trimmed || null;
}

function resolvePrimaryToken(user) {
  const tokens = resolveUserTokens(user);
  return tokens[0] ?? null;
}

function resolveOwnerForChatId(chatId) {
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

const clients = new Map();
const outbox = new Map();
const deviceSequences = new Map();
const inboundQueues = new Map();
const inboundWaiters = new Map();
const nonceWindows = new Map();
const chatOwners = new Map();

const OUTBOX_LIMIT = 200;

function nextEventId(deviceKey) {
  const current = deviceSequences.get(deviceKey) ?? 0;
  const next = current + 1;
  deviceSequences.set(deviceKey, next);
  return next;
}

function appendOutbox(deviceKey, payload) {
  const eventId = nextEventId(deviceKey);
  const entry = { eventId, payload: { ...payload, id: String(eventId) } };
  const queue = outbox.get(deviceKey) ?? [];
  queue.push(entry);
  if (queue.length > OUTBOX_LIMIT) {
    queue.splice(0, queue.length - OUTBOX_LIMIT);
  }
  outbox.set(deviceKey, queue);
  return entry;
}

function sendEvent(res, entry) {
  res.write(`id: ${entry.eventId}\n`);
  res.write(`data: ${JSON.stringify(entry.payload)}\n\n`);
}

function replayOutbox(deviceKey, res, sinceId) {
  const queue = outbox.get(deviceKey);
  if (!queue || queue.length === 0) return;
  const start = sinceId && Number.isFinite(sinceId) ? sinceId : 0;
  for (const entry of queue) {
    if (entry.eventId > start) {
      sendEvent(res, entry);
    }
  }
}

function sendToDevice(deviceKey, payload) {
  const entry = appendOutbox(deviceKey, payload);
  const connections = clients.get(deviceKey);
  if (!connections || connections.size === 0) {
    return;
  }
  for (const connection of connections) {
    sendEvent(connection.res, entry);
  }
}

function attachClient(deviceKey, res, lastEventId) {
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

  const entry = { res, heartbeat };
  const set = clients.get(deviceKey) ?? new Set();
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

function enqueueInbound(deviceKey, message) {
  const queue = inboundQueues.get(deviceKey) ?? [];
  queue.push(message);
  inboundQueues.set(deviceKey, queue);
  notifyInboundWaiters(deviceKey);
}

function drainInbound(deviceKey) {
  const queue = inboundQueues.get(deviceKey) ?? [];
  inboundQueues.delete(deviceKey);
  return queue;
}

function waitForInbound(deviceKey, waitMs, req) {
  const queued = inboundQueues.get(deviceKey);
  if (queued?.length) return Promise.resolve(drainInbound(deviceKey));

  return new Promise((resolve) => {
    let done = false;
    const waiters = inboundWaiters.get(deviceKey) ?? new Set();

    const finish = (messages) => {
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

    const entry = {
      finish,
      timeout: setTimeout(() => finish([]), waitMs),
    };

    waiters.add(entry);
    inboundWaiters.set(deviceKey, waiters);

    req.on("close", () => finish([]));
  });
}

function notifyInboundWaiters(deviceKey) {
  const waiters = inboundWaiters.get(deviceKey);
  if (!waiters || waiters.size === 0) return;
  const queue = inboundQueues.get(deviceKey);
  if (!queue || queue.length === 0) return;
  const entry = waiters.values().next().value;
  if (!entry) return;
  const messages = drainInbound(deviceKey);
  entry.finish(messages);
}

function buildInboundMessage(payload, user, deviceKey) {
  const chatId = normalizeChatId(user.id, payload.chatId);
  chatOwners.set(chatId, { userId: user.id, deviceKey });
  const senderName = payload.senderName?.trim() || user.displayName || user.id;
  const chatName = payload.chatName?.trim() || undefined;
  const id = typeof payload.id === "string" ? payload.id.trim() : undefined;
  const instance = instances.get(resolveInstanceKey(user.id, chatId));
  const modeMetadata = instance ? resolveInstanceModeMetadata(instance) : resolveModeMetadata(payload);
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

async function forwardToGateway(message, user) {
  const targetUrl = user.gatewayUrl ?? gatewayUrl;
  if (!targetUrl) {
    throw new Error(`Gateway URL missing for user ${user.id}`);
  }
  const headers = {
    "Content-Type": "application/json",
  };
  const token = user.gatewayToken ?? user.token ?? gatewayToken ?? serverToken;
  if (token) headers.Authorization = `Bearer ${token}`;
  const body = JSON.stringify({ message });
  if (hmacSecret) {
    const timestamp = Date.now();
    const nonce = randomUUID();
    const signature = createSignature({ secret: hmacSecret, timestamp, nonce, body });
    headers["x-test-timestamp"] = String(timestamp);
    headers["x-test-nonce"] = nonce;
    headers["x-test-signature"] = signature;
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

function readBearerToken(req) {
  const authHeader = String(req.headers.authorization ?? "");
  if (authHeader.toLowerCase().startsWith("bearer ")) {
    return authHeader.slice("bearer ".length).trim();
  }
  return "";
}

function readUserIdHeader(req) {
  return String(req.headers["x-test-user"] ?? "").trim();
}

function verifyServerToken(req, user) {
  const provided = readBearerToken(req);
  if (serverToken && provided === serverToken) return true;
  if (user && hasUserToken(user, provided)) return true;
  return false;
}

function verifyAdminServerToken(req) {
  const provided = readBearerToken(req);
  if (!serverToken || !provided) return false;
  return provided === serverToken;
}

function resolveMachineAuth(params) {
  const userId = params.userId?.trim() || readUserIdHeader(params.req) || undefined;
  const token = params.token?.trim() || readBearerToken(params.req) || undefined;
  return resolveAuthMatch({
    userId,
    secret: token,
    allowPassword: false,
  });
}

function serveFile(res, path) {
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
    const raw = await readBody(req, 1024 * 1024).catch((err) => {
      sendJson(res, 413, { error: err.message });
      return null;
    });
    if (!raw) return;
    const payload = parseJson(raw);
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
    const raw = await readBody(req, 1024 * 1024).catch((err) => {
      sendJson(res, 413, { error: err.message });
      return null;
    });
    if (!raw) return;
    const payload = parseJson(raw);
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
    const raw = await readBody(req, 1024 * 1024).catch((err) => {
      sendJson(res, 413, { error: err.message });
      return null;
    });
    if (!raw) return;
    const payload = parseJson(raw);
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
    const contributor = {
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
    const machine = {
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
      const raw = await readBody(req, 1024 * 1024).catch((err) => {
        sendJson(res, 413, { error: err.message });
        return null;
      });
      if (!raw) return;
      const payload = parseJson(raw);
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
      const next = {
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
    attachClient(deviceKey, res, Number.isFinite(lastEventId) ? lastEventId : void 0);
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
    const raw = await readBody(req, 1024 * 1024).catch((err) => {
      sendJson(res, 413, { error: err.message });
      return null;
    });
    if (!raw) return;
    const payload = parseJson(raw);
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
    const entry = {
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
    const raw = await readBody(req, 1024 * 1024).catch((err) => {
      sendJson(res, 413, { error: err.message });
      return null;
    });
    if (!raw) return;
    const payload = parseJson(raw);
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
    const raw = await readBody(req, 1024 * 1024).catch((err) => {
      sendJson(res, 413, { error: err.message });
      return null;
    });
    if (!raw) return;
    const payload = parseJson(raw);
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
    const raw = await readBody(req, 1024 * 1024).catch((err) => {
      sendJson(res, 413, { error: err.message });
      return null;
    });
    if (!raw) return;
    const payload = parseJson(raw);
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
    const raw = await readBody(req, 1024 * 1024).catch((err) => {
      sendJson(res, 413, { error: err.message });
      return null;
    });
    if (!raw) return;
    const payload = parseJson(raw);
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
    let user = null;
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
    const signatureError = verifySignedRequest({ req, body: "", scope: `poll:${auth.user.id}` });
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
    const raw = await readBody(req, 1024 * 1024).catch((err) => {
      sendJson(res, 413, { error: err.message });
      return null;
    });
    if (!raw) return;
    const payload = parseJson(raw);
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
    const raw = await readBody(req, 1024 * 1024).catch((err) => {
      sendJson(res, 413, { error: err.message });
      return null;
    });
    if (!raw) return;
    const payload = parseJson(raw);
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
      senderName: payload.senderName ?? null,
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
