import type { DmPolicy, GroupPolicy } from "openclaw/plugin-sdk";

export type TestGroupConfig = {
  requireMention?: boolean;
  allowFrom?: Array<string | number>;
  systemPrompt?: string;
  enabled?: boolean;
};

export type TestSecurityConfig = {
  requireHttps?: boolean;
  allowTokenInQuery?: boolean;
  hmacSecret?: string;
  requireSignature?: boolean;
  timestampSkewMs?: number;
  rateLimitPerMinute?: number;
  rateLimitPerMinutePerSender?: number;
  maxPayloadBytes?: number;
  allowedIps?: string[];
  signOutbound?: boolean;
};

export type TestAccountConfig = {
  name?: string;
  enabled?: boolean;
  userId?: string;
  baseUrl?: string;
  token?: string;
  webhookPath?: string;
  webhookToken?: string;
  inboundMode?: "webhook" | "poll";
  pollIntervalMs?: number;
  pollWaitMs?: number;
  dmPolicy?: DmPolicy;
  allowFrom?: Array<string | number>;
  groupPolicy?: GroupPolicy;
  groupAllowFrom?: Array<string | number>;
  groups?: Record<string, TestGroupConfig>;
  textChunkLimit?: number;
  chunkMode?: "length" | "newline";
  security?: TestSecurityConfig;
};

export type TestConfig = {
  channels?: {
    vimalinx?: TestAccountConfig & {
      accounts?: Record<string, TestAccountConfig>;
    };
  };
};

export type TestInboundMessage = {
  id?: string;
  chatId: string;
  chatName?: string;
  chatType?: "dm" | "group";
  senderId: string;
  senderName?: string;
  text: string;
  mentioned?: boolean;
  timestamp?: number;
};

export type TestInboundPayload = {
  token?: string;
  message?: Partial<TestInboundMessage>;
} & Partial<TestInboundMessage>;
