import type { OutboundDeliveryResult } from "openclaw/plugin-sdk";

import { resolveTestAccount } from "./accounts.js";
import {
  createTestSignature,
  generateNonce,
  resolveTestSecurityConfig,
} from "./security.js";
import type { TestConfig } from "./types.js";

function buildSendUrl(baseUrl: string): string {
  const normalized = baseUrl.endsWith("/") ? baseUrl : `${baseUrl}/`;
  return new URL("send", normalized).toString();
}

function isHttpsUrl(value: string): boolean {
  try {
    return new URL(value).protocol === "https:";
  } catch {
    return false;
  }
}

export async function sendTestMessage(params: {
  to: string;
  text: string;
  cfg: TestConfig;
  accountId?: string;
  replyToId?: string;
}): Promise<OutboundDeliveryResult> {
  const account = resolveTestAccount({
    cfg: params.cfg,
    accountId: params.accountId,
  });
  if (!account.baseUrl) {
    throw new Error("Vimalinx baseUrl is not configured.");
  }

  const security = resolveTestSecurityConfig(account.config.security);
  if (security.requireHttps && !isHttpsUrl(account.baseUrl)) {
    throw new Error("Vimalinx baseUrl must use https when requireHttps is enabled.");
  }

  const headers: Record<string, string> = {
    "Content-Type": "application/json",
  };
  if (account.token) {
    headers.Authorization = `Bearer ${account.token}`;
  }

  const body = JSON.stringify({
    chatId: params.to,
    text: params.text,
    replyToId: params.replyToId,
    accountId: account.accountId,
  });

  if (security.signOutbound && security.hmacSecret) {
    const timestamp = Date.now();
    const nonce = generateNonce();
    const signature = createTestSignature({
      secret: security.hmacSecret,
      timestamp,
      nonce,
      body,
    });
    headers["x-vimalinx-timestamp"] = String(timestamp);
    headers["x-vimalinx-nonce"] = nonce;
    headers["x-vimalinx-signature"] = signature;
  }

  const response = await fetch(buildSendUrl(account.baseUrl), {
    method: "POST",
    headers,
    body,
  });

  if (!response.ok) {
    throw new Error(`Vimalinx send failed (${response.status} ${response.statusText}).`);
  }

  const data = (await response.json().catch(() => ({}))) as { messageId?: unknown };
  const messageId =
    typeof data.messageId === "string" && data.messageId.trim()
      ? data.messageId.trim()
      : `vimalinx-${Date.now()}`;

  return {
    channel: "vimalinx",
    messageId,
    chatId: params.to,
  };
}
