import { createHmac, randomUUID, timingSafeEqual } from "node:crypto";
import { createServer } from "node:http";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = resolve(fileURLToPath(new URL(".", import.meta.url)));
const publicDir = resolve(__dirname, "public");
const port = Number.parseInt(process.env.TEST_SERVER_PORT ?? "8788", 10);
const gatewayUrl = process.env.TEST_GATEWAY_URL?.trim();
const gatewayToken = process.env.TEST_GATEWAY_TOKEN?.trim();
const serverToken = process.env.TEST_SERVER_TOKEN?.trim();
const usersFilePath = process.env.TEST_USERS_FILE?.trim();
const usersInline = process.env.TEST_USERS?.trim();
const defaultUserId = process.env.TEST_DEFAULT_USER_ID?.trim();
const defaultUserToken = process.env.TEST_DEFAULT_USER_TOKEN?.trim();
const inboundMode = (process.env.TEST_INBOUND_MODE ?? "poll").trim().toLowerCase();
const usersWritePath = process.env.TEST_USERS_WRITE_FILE?.trim() || usersFilePath;
const allowRegistration = (process.env.TEST_ALLOW_REGISTRATION ?? "true").toLowerCase() === "true";
const hmacSecret = process.env.TEST_HMAC_SECRET?.trim();
const requireSignature = (process.env.TEST_REQUIRE_SIGNATURE ?? "").trim().toLowerCase();
const signatureRequired = requireSignature ? requireSignature === "true" : Boolean(hmacSecret);
const signatureTtlMs = Number.parseInt(process.env.TEST_SIGNATURE_TTL_MS ?? "300000", 10);
const inviteCodes = normalizeInviteCodes(
  process.env.TEST_INVITE_CODES ?? process.env.TEST_INVITE_CODE ?? "",
);

const users = new Map();

function normalizeToken(value) {
  const trimmed = value?.trim();
  return trimmed ? trimmed : null;
}

function normalizeTokens(values) {
  const tokens = [];
  const seen = new Set();
  for (const value of values) {
    const normalized = normalizeToken(value);
    if (!normalized || seen.has(normalized)) continue;
    seen.add(normalized);
    tokens.push(normalized);
  }
  return tokens;
}

function normalizeUsageNumber(value) {
  if (!Number.isFinite(value)) return void 0;
  return Math.max(0, Math.floor(value ?? 0));
}

function normalizeTokenUsage(raw, tokens) {
  const usage = {};
  if (raw) {
    for (const [key, entry] of Object.entries(raw)) {
      const token = normalizeToken(entry?.token ?? key);
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
  const tokens = normalizeTokens([entry.token, ...(entry.tokens ?? [])]);
  const password = entry.password?.trim() || void 0;
  const displayName = entry.displayName?.trim() || void 0;
  const gatewayUrl = entry.gatewayUrl?.trim() || void 0;
  const gatewayToken = entry.gatewayToken?.trim() || void 0;
  const tokenUsage = normalizeTokenUsage(entry.tokenUsage, tokens);
  return {
    ...entry,
    id,
    token: tokens[0],
    tokens,
    password,
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
}

loadUsers();

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

function generateToken() {
  return randomUUID().replace(/-/g, "");
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
  if (!token) return false;
  return resolveUserTokens(entry).includes(token.trim());
}

function addUserToken(entry, token) {
  const tokens = resolveUserTokens(entry);
  if (!tokens.includes(token)) tokens.push(token);
  entry.tokens = tokens;
  if (!entry.token) entry.token = token;
  updateTokenUsage(entry, token, { createdAt: Date.now(), lastSeenAt: Date.now() });
}

function serializeUserRecord(entry) {
  const tokens = resolveUserTokens(entry);
  const tokenUsage = normalizeTokenUsage(entry.tokenUsage, tokens);
  return {
    ...entry,
    token: tokens[0],
    tokens,
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

let pendingUsersSave = null;

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

function ensureTokenUsage(entry, token) {
  const normalized = normalizeToken(token);
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

function getUser(userId, token) {
  if (!userId || !token) return null;
  const entry = users.get(userId);
  if (!entry) return null;
  return hasUserToken(entry, token) ? entry : null;
}

function getUserByPassword(userId, password) {
  if (!userId || !password) return null;
  const entry = users.get(userId);
  if (!entry || !entry.password) return null;
  return entry.password === password ? entry : null;
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
  if (id) {
    const tokenMatch = getUser(id, value);
    if (tokenMatch) return { user: tokenMatch, secret: value, kind: "token" };
    if (allowPassword) {
      const passwordMatch = getUserByPassword(id, value);
      if (passwordMatch) return { user: passwordMatch, secret: value, kind: "password" };
    }
  }
  const tokenMatch = getUserByToken(value);
  if (tokenMatch) return { user: tokenMatch, secret: value, kind: "token" };
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

  if (req.method === "GET" && url.pathname === "/") {
    serveFile(res, resolve(publicDir, "index.html"));
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
      password,
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
    const user = users.get(userId);
    if (!user || !user.password || user.password !== password) {
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
    const user = users.get(userId);
    if (!user || !user.password || user.password !== password) {
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
    const user = users.get(userId);
    if (!user || !user.password || user.password !== password) {
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
    updateTokenUsage(user, token, { lastSeenAt: Date.now() });
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
    });
    sendJson(res, 200, { ok: true, delivered: true });
    return;
  }

  sendText(res, 404, "Not Found");
});

server.listen(Number.isFinite(port) ? port : 8788, () => {
  const usersCount = users.size;
  const location = Number.isFinite(port) ? port : 8788;
  // eslint-disable-next-line no-console
  console.log(`Vimalinx Server listening on http://0.0.0.0:${location}`);
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
