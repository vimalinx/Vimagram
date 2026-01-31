import type { IncomingMessage } from "node:http";
import { createHmac, randomUUID, timingSafeEqual } from "node:crypto";

import type { TestSecurityConfig } from "./types.js";

type ResolvedTestSecurityConfig = {
  requireHttps: boolean;
  allowTokenInQuery: boolean;
  hmacSecret?: string;
  requireSignature: boolean;
  timestampSkewMs: number;
  rateLimitPerMinute: number;
  rateLimitPerMinutePerSender: number;
  maxPayloadBytes: number;
  allowedIps: string[];
  signOutbound: boolean;
};

const DEFAULTS = {
  requireHttps: false,
  allowTokenInQuery: false,
  timestampSkewMs: 5 * 60 * 1000,
  rateLimitPerMinute: 120,
  rateLimitPerMinutePerSender: 60,
  maxPayloadBytes: 1024 * 1024,
} as const;

const globalRateLimits = new Map<string, { windowStart: number; count: number }>();
const senderRateLimits = new Map<string, { windowStart: number; count: number }>();
const nonceWindows = new Map<string, Map<string, number>>();

export function resolveTestSecurityConfig(raw?: TestSecurityConfig): ResolvedTestSecurityConfig {
  const secret = raw?.hmacSecret?.trim();
  const requireSignature =
    typeof raw?.requireSignature === "boolean" ? raw.requireSignature : Boolean(secret);
  return {
    requireHttps: raw?.requireHttps === true,
    allowTokenInQuery: raw?.allowTokenInQuery === true,
    hmacSecret: secret || undefined,
    requireSignature,
    timestampSkewMs:
      typeof raw?.timestampSkewMs === "number" && raw.timestampSkewMs > 0
        ? Math.floor(raw.timestampSkewMs)
        : DEFAULTS.timestampSkewMs,
    rateLimitPerMinute:
      typeof raw?.rateLimitPerMinute === "number" && raw.rateLimitPerMinute >= 0
        ? Math.floor(raw.rateLimitPerMinute)
        : DEFAULTS.rateLimitPerMinute,
    rateLimitPerMinutePerSender:
      typeof raw?.rateLimitPerMinutePerSender === "number" && raw.rateLimitPerMinutePerSender >= 0
        ? Math.floor(raw.rateLimitPerMinutePerSender)
        : DEFAULTS.rateLimitPerMinutePerSender,
    maxPayloadBytes:
      typeof raw?.maxPayloadBytes === "number" && raw.maxPayloadBytes > 0
        ? Math.floor(raw.maxPayloadBytes)
        : DEFAULTS.maxPayloadBytes,
    allowedIps: (raw?.allowedIps ?? []).map((entry) => entry.trim()).filter(Boolean),
    signOutbound: raw?.signOutbound !== false && Boolean(secret),
  };
}

export function resolveRequestIp(req: IncomingMessage): string | undefined {
  const forwarded = req.headers["x-forwarded-for"];
  if (typeof forwarded === "string" && forwarded.trim()) {
    return forwarded.split(",")[0]?.trim() || undefined;
  }
  return req.socket.remoteAddress ?? undefined;
}

export function isIpAllowed(allowed: string[], ip?: string): boolean {
  if (allowed.length === 0) return true;
  if (!ip) return false;
  const normalized = ip.toLowerCase();
  if (allowed.includes("*")) return true;
  return allowed.some((entry) => entry.toLowerCase() === normalized);
}

function consumeRateLimit(
  store: Map<string, { windowStart: number; count: number }>,
  key: string,
  limit: number,
  now: number,
): { allowed: boolean; retryAfterMs?: number } {
  if (limit <= 0) return { allowed: true };
  const windowMs = 60_000;
  const current = store.get(key);
  if (!current || now - current.windowStart >= windowMs) {
    store.set(key, { windowStart: now, count: 1 });
    return { allowed: true };
  }
  const nextCount = current.count + 1;
  if (nextCount > limit) {
    return { allowed: false, retryAfterMs: windowMs - (now - current.windowStart) };
  }
  current.count = nextCount;
  return { allowed: true };
}

export function checkGlobalRateLimit(key: string, limit: number): boolean {
  const result = consumeRateLimit(globalRateLimits, key, limit, Date.now());
  return result.allowed;
}

export function checkSenderRateLimit(key: string, limit: number): boolean {
  const result = consumeRateLimit(senderRateLimits, key, limit, Date.now());
  return result.allowed;
}

export function verifyTestSignature(params: {
  secret: string;
  timestamp: number;
  nonce: string;
  body: string;
  signature: string;
}): boolean {
  const expected = createHmac("sha256", params.secret)
    .update(`${params.timestamp}.${params.nonce}.${params.body}`)
    .digest("hex");
  if (expected.length !== params.signature.length) return false;
  return timingSafeEqual(Buffer.from(expected, "utf-8"), Buffer.from(params.signature, "utf-8"));
}

export function createTestSignature(params: {
  secret: string;
  timestamp: number;
  nonce: string;
  body: string;
}): string {
  return createHmac("sha256", params.secret)
    .update(`${params.timestamp}.${params.nonce}.${params.body}`)
    .digest("hex");
}

export function generateNonce(): string {
  return randomUUID();
}

export function checkAndStoreNonce(params: {
  accountId: string;
  nonce: string;
  now: number;
  windowMs: number;
}): boolean {
  const store = nonceWindows.get(params.accountId) ?? new Map<string, number>();
  const cutoff = params.now - params.windowMs;
  for (const [key, value] of store.entries()) {
    if (value < cutoff) store.delete(key);
  }
  if (store.has(params.nonce)) {
    nonceWindows.set(params.accountId, store);
    return false;
  }
  store.set(params.nonce, params.now);
  nonceWindows.set(params.accountId, store);
  return true;
}

export function isTimestampFresh(params: {
  timestamp: number;
  now: number;
  skewMs: number;
}): boolean {
  return Math.abs(params.now - params.timestamp) <= params.skewMs;
}
