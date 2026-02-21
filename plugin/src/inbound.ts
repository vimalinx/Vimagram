import {
  logInboundDrop,
  normalizeAccountId,
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
import {
  getRegisteredMachineProfile,
  type MachineRoutingConfig,
} from "./machine-state.js";
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

type ModeMetadata = {
  modeId?: string;
  modeLabel?: string;
  modelHint?: string;
  agentHint?: string;
  skillsHint?: string;
};

function normalizeModeHint(value: string | undefined, maxLength = 120): string | undefined {
  const trimmed = value?.trim();
  if (!trimmed) return undefined;
  return trimmed.slice(0, maxLength);
}

function normalizeModeId(value: string | undefined): string | undefined {
  const normalized = normalizeModeHint(value, 32)?.toLowerCase();
  if (!normalized) return undefined;
  if (!/^[a-z0-9_-]{1,32}$/.test(normalized)) return undefined;
  return normalized;
}

function resolveModeMetadata(message: TestInboundMessage): ModeMetadata {
  return {
    modeId: normalizeModeId(message.modeId),
    modeLabel: normalizeModeHint(message.modeLabel, 40),
    modelHint: normalizeModeHint(message.modelHint, 120),
    agentHint: normalizeModeHint(message.agentHint, 120),
    skillsHint: normalizeModeHint(message.skillsHint, 160),
  };
}

function deriveModeLookupIds(modeId: string | undefined): string[] {
  if (!modeId) return [];
  const out = [modeId];
  const match = /^inst_(.+)_(ecom|docs|media)$/.exec(modeId);
  if (match) {
    out.push(`inst_${match[1]}`);
  }
  return out;
}

type InstanceIdentityId = "ecom" | "docs" | "media";

function resolveIdentityFromModeId(modeId: string | undefined): InstanceIdentityId | undefined {
  if (!modeId) return undefined;
  const match = /^inst_.+_(ecom|docs|media)$/.exec(modeId);
  if (!match) return undefined;
  if (match[1] === "ecom" || match[1] === "docs" || match[1] === "media") return match[1];
  return undefined;
}

function resolveIdentitySystemPrompt(identity: InstanceIdentityId | undefined): string | undefined {
  if (!identity) return undefined;
  if (identity === "ecom") {
    return [
      "你是一个电商运营助理（偏实战）。",
      "目标：提升成交/转化/复购，并能输出可直接执行的清单与文案。",
      "工作方式：",
      "- 先问清平台/类目/客单价/人群/库存/毛利/当前数据（如有）",
      "- 输出：标题/卖点/详情页结构/短视频脚本/投放素材方向/定价与优惠策略/活动节奏",
      "- 给出可落地的 A/B 测试方案与指标（CTR、CVR、GMV、ROI）",
      "- 文案风格：清晰、短句、强利益点，避免空话",
    ].join("\n");
  }
  if (identity === "docs") {
    return [
      "你是一个文书/写作助理（偏严谨、可交付）。",
      "目标：输出结构清晰、可直接提交或复制使用的正式文本。",
      "工作方式：",
      "- 先确认用途/受众/语气/长度/约束（必须包含/禁止包含）",
      "- 先给大纲，再给正文；必要时给可选版本（正式/中性/强势）",
      "- 关注合规与风险提示：不编造事实；需要信息时用占位符标注",
      "- 输出尽量可编辑：标题层级、要点列表、可替换字段",
    ].join("\n");
  }
  return [
    "你是一个自媒体/内容创作助理（偏增长）。",
    "目标：更高的打开率、完播率、互动率与转粉。",
    "工作方式：",
    "- 先确认平台（抖音/小红书/B站/公众号）、赛道、人设、禁忌",
    "- 输出：选题池、爆点/钩子、脚本分镜、标题与封面文案、发布节奏",
    "- 内容结构：开头 3 秒钩子 -> 价值点 -> 证据/故事 -> 行动号召",
    "- 给 3-5 个不同风格版本（冲突型/干货型/故事型/反常识型）",
  ].join("\n");
}

function resolveModeValue(
  source: Record<string, string> | undefined,
  modeIds: string[],
  maxLength: number,
): string | undefined {
  if (!source || modeIds.length === 0) return undefined;
  for (const modeId of modeIds) {
    const key = Object.keys(source).find((entry) => normalizeModeId(entry) === modeId);
    if (!key) continue;
    const value = normalizeModeHint(source[key], maxLength);
    if (value) return value;
  }
  return undefined;
}

function applyMachineRoutingHints(
  mode: ModeMetadata,
  routing: MachineRoutingConfig | undefined,
): ModeMetadata {
  if (!routing) return mode;
  const modeIds = deriveModeLookupIds(mode.modeId);
  const modelHint =
    mode.modelHint ?? resolveModeValue(routing.modeModelHints, modeIds, 120);
  const agentHint =
    mode.agentHint ?? resolveModeValue(routing.modeAgentHints, modeIds, 120);
  const skillsHint =
    mode.skillsHint ?? resolveModeValue(routing.modeSkillsHints, modeIds, 160);
  return {
    ...mode,
    modelHint,
    agentHint,
    skillsHint,
  };
}

function resolveModeRoutingMap(
  account: ResolvedTestAccount,
  routing: MachineRoutingConfig | undefined,
): Record<string, string> | undefined {
  return routing?.modeAccountMap ?? account.config.modeAccountMap;
}

function resolveModeRouteAccountId(
  account: ResolvedTestAccount,
  mode: ModeMetadata,
  mapping?: Record<string, string>,
): string {
  if (!mode.modeId) return account.accountId;
  if (!mapping || typeof mapping !== "object") return account.accountId;

  const modeIds = deriveModeLookupIds(mode.modeId);
  const mappedAccount =
    modeIds
      .map((candidate) =>
        Object.entries(mapping).find(([modeKey]) => normalizeModeId(modeKey) === candidate)?.[1],
      )
      .find((value) => Boolean(value));
  const normalized = normalizeModeHint(mappedAccount, 64);
  if (!normalized) return account.accountId;
  return normalizeAccountId(normalized);
}

function buildModeUntrustedContext(mode: ModeMetadata): string[] {
  const lines: string[] = [];
  if (mode.modeId || mode.modeLabel) {
    const label = mode.modeLabel ? ` (${mode.modeLabel})` : "";
    lines.push(`Client mode: ${mode.modeId ?? "unknown"}${label}`);
  }
  if (mode.modelHint) lines.push(`Client model hint: ${mode.modelHint}`);
  if (mode.agentHint) lines.push(`Client agent hint: ${mode.agentHint}`);
  if (mode.skillsHint) lines.push(`Client skills hint: ${mode.skillsHint}`);
  return lines;
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
  const machineProfile = getRegisteredMachineProfile(account.accountId);
  const modeRouting = resolveModeRoutingMap(account, machineProfile?.routing);
  const modeMetadata = applyMachineRoutingHints(
    resolveModeMetadata(message),
    machineProfile?.routing,
  );
  const modeUntrustedContext = buildModeUntrustedContext(modeMetadata);

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
    accountId: resolveModeRouteAccountId(account, modeMetadata, modeRouting),
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
  const identitySystemPrompt = resolveIdentitySystemPrompt(resolveIdentityFromModeId(modeMetadata.modeId));
  const mergedSystemPrompt = [groupSystemPrompt, identitySystemPrompt].filter(Boolean).join("\n\n") || undefined;

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
    GroupSystemPrompt: mergedSystemPrompt,
    UntrustedContext: modeUntrustedContext.length > 0 ? modeUntrustedContext : undefined,
    ModeId: modeMetadata.modeId,
    ModeLabel: modeMetadata.modeLabel,
    ModelHint: modeMetadata.modelHint,
    AgentHint: modeMetadata.agentHint,
    SkillsHint: modeMetadata.skillsHint,
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
