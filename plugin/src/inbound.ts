import {
  logInboundDrop,
  resolveControlCommandGate,
  resolveMentionGatingWithBypass,
  resolveNestedAllowlistDecision,
  type ClawdbotConfig,
  type RuntimeEnv,
} from "openclaw/plugin-sdk";

import type { ResolvedTestAccount } from "./accounts.js";
import {
  normalizeTestAllowlist,
  resolveTestAllowlistMatch,
} from "./allowlist.js";
import { checkSenderRateLimit, resolveTestSecurityConfig } from "./security.js";
import { sendTestMessage } from "./send.js";
import { getTestRuntime } from "./runtime.js";
import type { TestConfig, TestGroupConfig, TestInboundMessage } from "./types.js";

const CHANNEL_ID = "vimalinx" as const;

type GroupMatch = {
  groupConfig?: TestGroupConfig;
  wildcardConfig?: TestGroupConfig;
  allowed: boolean;
  allowlistConfigured: boolean;
};

function resolveGroupMatch(params: {
  groups?: Record<string, TestGroupConfig>;
  chatId: string;
  chatName?: string | null;
}): GroupMatch {
  const groups = params.groups ?? {};
  const allowlistConfigured = Object.keys(groups).length > 0;
  const direct = groups[params.chatId];
  if (direct) {
    return { groupConfig: direct, wildcardConfig: groups["*"], allowed: true, allowlistConfigured };
  }
  const nameKey = params.chatName?.trim();
  if (nameKey && groups[nameKey]) {
    return {
      groupConfig: groups[nameKey],
      wildcardConfig: groups["*"],
      allowed: true,
      allowlistConfigured,
    };
  }
  if (groups["*"]) {
    return {
      groupConfig: undefined,
      wildcardConfig: groups["*"],
      allowed: true,
      allowlistConfigured,
    };
  }
  return {
    groupConfig: undefined,
    wildcardConfig: undefined,
    allowed: !allowlistConfigured,
    allowlistConfigured,
  };
}

function resolveRequireMention(params: { groupConfig?: TestGroupConfig; wildcardConfig?: TestGroupConfig }): boolean {
  if (typeof params.groupConfig?.requireMention === "boolean") {
    return params.groupConfig.requireMention;
  }
  if (typeof params.wildcardConfig?.requireMention === "boolean") {
    return params.wildcardConfig.requireMention;
  }
  return true;
}

function normalizeTimestamp(value?: number): number {
  if (!value || !Number.isFinite(value)) return Date.now();
  if (value > 1_000_000_000_000) return Math.floor(value);
  if (value > 1_000_000_000) return Math.floor(value * 1000);
  return Date.now();
}

function resolveGroupAllow(params: {
  groupPolicy: string;
  outerAllowFrom: Array<string | number> | undefined;
  innerAllowFrom: Array<string | number> | undefined;
  senderId: string;
  senderName?: string | null;
}): { allowed: boolean } {
  if (params.groupPolicy === "disabled") {
    return { allowed: false };
  }
  if (params.groupPolicy === "open") {
    return { allowed: true };
  }

  const outerAllow = normalizeTestAllowlist(params.outerAllowFrom);
  const innerAllow = normalizeTestAllowlist(params.innerAllowFrom);
  if (outerAllow.length === 0 && innerAllow.length === 0) {
    return { allowed: false };
  }

  const outerMatch = resolveTestAllowlistMatch({
    allowFrom: params.outerAllowFrom,
    senderId: params.senderId,
    senderName: params.senderName,
  });
  const innerMatch = resolveTestAllowlistMatch({
    allowFrom: params.innerAllowFrom,
    senderId: params.senderId,
    senderName: params.senderName,
  });

  const allowed = resolveNestedAllowlistDecision({
    outerConfigured: outerAllow.length > 0 || innerAllow.length > 0,
    outerMatched: outerAllow.length > 0 ? outerMatch.allowed : true,
    innerConfigured: innerAllow.length > 0,
    innerMatched: innerMatch.allowed,
  });

  return { allowed };
}

async function deliverTestReply(params: {
  payload: { text?: string; mediaUrls?: string[]; mediaUrl?: string; replyToId?: string };
  chatId: string;
  accountId: string;
  config: TestConfig;
  statusSink?: (patch: { lastOutboundAt?: number }) => void;
}): Promise<void> {
  const { payload, chatId, accountId, config, statusSink } = params;
  const text = payload.text ?? "";
  const mediaList = payload.mediaUrls?.length
    ? payload.mediaUrls
    : payload.mediaUrl
      ? [payload.mediaUrl]
      : [];

  if (!text.trim() && mediaList.length === 0) return;

  const mediaBlock = mediaList.length ? mediaList.map((url) => `Attachment: ${url}`).join("\n") : "";
  const combined = text.trim()
    ? mediaBlock
      ? `${text.trim()}\n\n${mediaBlock}`
      : text.trim()
    : mediaBlock;

  await sendTestMessage({
    to: chatId,
    text: combined,
    cfg: config,
    accountId,
    replyToId: payload.replyToId,
  });
  statusSink?.({ lastOutboundAt: Date.now() });
}

export async function handleTestInbound(params: {
  message: TestInboundMessage;
  account: ResolvedTestAccount;
  config: TestConfig;
  runtime: RuntimeEnv;
  statusSink?: (patch: { lastInboundAt?: number; lastOutboundAt?: number }) => void;
  rateLimitChecked?: boolean;
}): Promise<void> {
  const { message, account, config, runtime, statusSink } = params;
  const core = getTestRuntime();

  const rawBody = message.text?.trim() ?? "";
  if (!rawBody) return;

  const isGroup = message.chatType === "group";
  const senderId = message.senderId;
  const senderName = message.senderName;
  const chatId = message.chatId;
  const chatName = message.chatName;
  const timestamp = normalizeTimestamp(message.timestamp);

  statusSink?.({ lastInboundAt: timestamp });

  const security = resolveTestSecurityConfig(account.config.security);
  if (!params.rateLimitChecked) {
    const allowed = checkSenderRateLimit(
      `vimalinx:${account.accountId}:${senderId}`,
      security.rateLimitPerMinutePerSender,
    );
    if (!allowed) {
      runtime.log?.(`vimalinx: drop sender ${senderId} (rate limited)`);
      return;
    }
  }

  const dmPolicy = account.config.dmPolicy ?? "pairing";
  const defaultGroupPolicy = (config as ClawdbotConfig).channels?.defaults?.groupPolicy;
  const groupPolicy = account.config.groupPolicy ?? defaultGroupPolicy ?? "allowlist";

  const configAllowFrom = normalizeTestAllowlist(account.config.allowFrom);
  const configGroupAllowFrom = normalizeTestAllowlist(account.config.groupAllowFrom);
  const storeAllowFrom = await core.channel.pairing.readAllowFromStore(CHANNEL_ID).catch(() => []);
  const storeAllowList = normalizeTestAllowlist(storeAllowFrom);

  const groupMatch = resolveGroupMatch({
    groups: account.config.groups,
    chatId,
    chatName,
  });

  if (isGroup && !groupMatch.allowed) {
    runtime.log?.(`vimalinx: drop group ${chatId} (not allowlisted)`);
    return;
  }
  if (groupMatch.groupConfig?.enabled === false) {
    runtime.log?.(`vimalinx: drop group ${chatId} (disabled)`);
    return;
  }

  const groupAllowFrom = normalizeTestAllowlist(groupMatch.groupConfig?.allowFrom);
  const baseGroupAllowFrom = configGroupAllowFrom.length > 0 ? configGroupAllowFrom : configAllowFrom;

  const effectiveAllowFrom = [...configAllowFrom, ...storeAllowList].filter(Boolean);
  const effectiveGroupAllowFrom = [...baseGroupAllowFrom, ...storeAllowList].filter(Boolean);

  const allowTextCommands = core.channel.commands.shouldHandleTextCommands({
    cfg: config as ClawdbotConfig,
    surface: CHANNEL_ID,
  });
  const useAccessGroups = (config as ClawdbotConfig).commands?.useAccessGroups !== false;
  const senderAllowedForCommands = resolveTestAllowlistMatch({
    allowFrom: isGroup ? effectiveGroupAllowFrom : effectiveAllowFrom,
    senderId,
    senderName,
  }).allowed;
  const hasControlCommand = core.channel.text.hasControlCommand(
    rawBody,
    config as ClawdbotConfig,
  );
  const commandGate = resolveControlCommandGate({
    useAccessGroups,
    authorizers: [
      {
        configured: (isGroup ? effectiveGroupAllowFrom : effectiveAllowFrom).length > 0,
        allowed: senderAllowedForCommands,
      },
    ],
    allowTextCommands,
    hasControlCommand,
  });
  const commandAuthorized = commandGate.commandAuthorized;

  if (isGroup && commandGate.shouldBlock) {
    logInboundDrop({
      log: (line) => runtime.log?.(line),
      channel: CHANNEL_ID,
      reason: "control command (unauthorized)",
      target: senderId,
    });
    return;
  }

  if (isGroup) {
    const groupAllow = resolveGroupAllow({
      groupPolicy,
      outerAllowFrom: effectiveGroupAllowFrom,
      innerAllowFrom: groupAllowFrom,
      senderId,
      senderName,
    });
    if (!groupAllow.allowed) {
      runtime.log?.(`vimalinx: drop group sender ${senderId} (policy=${groupPolicy})`);
      return;
    }
  } else {
    if (dmPolicy === "disabled") {
      runtime.log?.(`vimalinx: drop DM sender=${senderId} (dmPolicy=disabled)`);
      return;
    }
    if (dmPolicy !== "open") {
      const dmAllowed = resolveTestAllowlistMatch({
        allowFrom: effectiveAllowFrom,
        senderId,
        senderName,
      }).allowed;
      if (!dmAllowed) {
        if (dmPolicy === "pairing") {
          const { code, created } = await core.channel.pairing.upsertPairingRequest({
            channel: CHANNEL_ID,
            id: senderId,
            meta: { name: senderName || undefined },
          });
          if (created) {
            try {
              await sendTestMessage({
                to: chatId,
                text: core.channel.pairing.buildPairingReply({
                  channel: CHANNEL_ID,
                  idLine: `Your vimalinx user id: ${senderId}`,
                  code,
                }),
                cfg: config,
                accountId: account.accountId,
              });
              statusSink?.({ lastOutboundAt: Date.now() });
            } catch (err) {
              runtime.error?.(`vimalinx: pairing reply failed for ${senderId}: ${String(err)}`);
            }
          }
        }
        runtime.log?.(`vimalinx: drop DM sender ${senderId} (dmPolicy=${dmPolicy})`);
        return;
      }
    }
  }

  const requireMention = isGroup
    ? resolveRequireMention({
        groupConfig: groupMatch.groupConfig,
        wildcardConfig: groupMatch.wildcardConfig,
      })
    : false;
  const mentionGate = resolveMentionGatingWithBypass({
    isGroup,
    requireMention,
    canDetectMention: true,
    wasMentioned: Boolean(message.mentioned),
    allowTextCommands,
    hasControlCommand,
    commandAuthorized,
  });
  if (isGroup && mentionGate.shouldSkip) {
    runtime.log?.(`vimalinx: drop group ${chatId} (no mention)`);
    return;
  }

  const route = core.channel.routing.resolveAgentRoute({
    cfg: config as ClawdbotConfig,
    channel: CHANNEL_ID,
    accountId: account.accountId,
    peer: {
      kind: isGroup ? "group" : "dm",
      id: chatId,
    },
  });

  const fromLabel = isGroup
    ? `group:${chatName || chatId}`
    : senderName || `user:${senderId}`;
  const storePath = core.channel.session.resolveStorePath(
    (config as ClawdbotConfig).session?.store,
    {
      agentId: route.agentId,
    },
  );
  const envelopeOptions = core.channel.reply.resolveEnvelopeFormatOptions(
    config as ClawdbotConfig,
  );
  const previousTimestamp = core.channel.session.readSessionUpdatedAt({
    storePath,
    sessionKey: route.sessionKey,
  });
  const body = core.channel.reply.formatAgentEnvelope({
    channel: "Vimalinx",
    from: fromLabel,
    timestamp,
    previousTimestamp,
    envelope: envelopeOptions,
    body: rawBody,
  });

  const groupSystemPrompt = groupMatch.groupConfig?.systemPrompt?.trim() || undefined;

  const ctxPayload = core.channel.reply.finalizeInboundContext({
    Body: body,
    RawBody: rawBody,
    CommandBody: rawBody,
    From: isGroup ? `vimalinx:group:${chatId}` : `vimalinx:${senderId}`,
    To: `vimalinx:${chatId}`,
    SessionKey: route.sessionKey,
    AccountId: route.accountId,
    ChatType: isGroup ? "group" : "direct",
    ConversationLabel: fromLabel,
    SenderName: senderName || undefined,
    SenderId: senderId,
    GroupSubject: isGroup ? chatName || chatId : undefined,
    GroupSystemPrompt: isGroup ? groupSystemPrompt : undefined,
    Provider: CHANNEL_ID,
    Surface: CHANNEL_ID,
    WasMentioned: isGroup ? Boolean(message.mentioned) : undefined,
    MessageSid: message.id,
    ReplyToId: message.id,
    Timestamp: timestamp,
    OriginatingChannel: CHANNEL_ID,
    OriginatingTo: `vimalinx:${chatId}`,
    CommandAuthorized: commandAuthorized,
  });

  await core.channel.session.recordInboundSession({
    storePath,
    sessionKey: ctxPayload.SessionKey ?? route.sessionKey,
    ctx: ctxPayload,
    onRecordError: (err) => {
      runtime.error?.(`vimalinx: failed updating session meta: ${String(err)}`);
    },
  });

  await core.channel.reply.dispatchReplyWithBufferedBlockDispatcher({
    ctx: ctxPayload,
    cfg: config as ClawdbotConfig,
    dispatcherOptions: {
      deliver: async (payload) => {
        await deliverTestReply({
          payload: payload as {
            text?: string;
            mediaUrls?: string[];
            mediaUrl?: string;
            replyToId?: string;
          },
          chatId,
          accountId: account.accountId,
          config,
          statusSink,
        });
      },
      onError: (err, info) => {
        runtime.error?.(`vimalinx ${info.kind} reply failed: ${String(err)}`);
      },
    },
  });
}
