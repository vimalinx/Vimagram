import {
  applyAccountNameToChannelSection,
  DEFAULT_ACCOUNT_ID,
  deleteAccountFromConfigSection,
  formatPairingApproveHint,
  migrateBaseNameToDefaultAccount,
  normalizeAccountId,
  PAIRING_APPROVED_MESSAGE,
  setAccountEnabledInConfigSection,
  type ChannelGroupContext,
  type ChannelPlugin,
  type ClawdbotConfig,
} from "openclaw/plugin-sdk";
import {
  listTestAccountIds,
  resolveDefaultTestAccountId,
  resolveTestAccount,
  type ResolvedTestAccount,
} from "./accounts.js";
import { normalizeTestAllowEntry, normalizeTestAllowlist } from "./allowlist.js";
import { startTestMonitor } from "./monitor.js";
import { vimalinxOnboardingAdapter } from "./onboarding.js";
import { sendTestMessage } from "./send.js";
import type { TestConfig, TestGroupConfig } from "./types.js";
import { getTestRuntime } from "./runtime.js";

const meta = {
  id: "vimalinx",
  label: "Vimalinx Server",
  selectionLabel: "Vimalinx Server (custom)",
  detailLabel: "Vimalinx Server",
  docsPath: "/channels",
  docsLabel: "channels",
  blurb: "custom webhook + outbound API",
  order: 90,
  quickstartAllowFrom: true,
} as const;

function resolveGroupConfig(params: {
  config: TestConfig;
  groupId?: string | null;
  groupName?: string | null;
  accountId?: string | null;
}): { groupConfig?: TestGroupConfig; wildcardConfig?: TestGroupConfig } {
  const account = resolveTestAccount({
    cfg: params.config,
    accountId: params.accountId ?? undefined,
  });
  const groups = account.config.groups ?? {};
  const groupId = params.groupId?.trim();
  const groupName = params.groupName?.trim();
  const wildcard = groups["*"];
  if (groupId && groups[groupId]) return { groupConfig: groups[groupId], wildcardConfig: wildcard };
  if (groupName && groups[groupName]) {
    return { groupConfig: groups[groupName], wildcardConfig: wildcard };
  }
  return { groupConfig: undefined, wildcardConfig: wildcard };
}

function resolveTestGroupRequireMention(params: ChannelGroupContext): boolean | undefined {
  const match = resolveGroupConfig({
    config: params.cfg as TestConfig,
    groupId: params.groupId ?? null,
    groupName: params.groupChannel ?? null,
    accountId: params.accountId ?? null,
  });
  if (typeof match.groupConfig?.requireMention === "boolean") {
    return match.groupConfig.requireMention;
  }
  if (typeof match.wildcardConfig?.requireMention === "boolean") {
    return match.wildcardConfig.requireMention;
  }
  return undefined;
}

function normalizeTestMessagingTarget(raw: string): string | undefined {
  let normalized = raw.trim();
  if (!normalized) return undefined;
  const lowered = normalized.toLowerCase();
  if (lowered.startsWith("vimalinx:")) {
    normalized = normalized.slice("vimalinx:".length).trim();
  }
  return normalized || undefined;
}

function looksLikeTestTargetId(value: string): boolean {
  return Boolean(value.trim());
}

export const vimalinxPlugin: ChannelPlugin<ResolvedTestAccount> = {
  id: "vimalinx",
  meta: { ...meta },
  onboarding: vimalinxOnboardingAdapter,
  pairing: {
    idLabel: "vimalinxUserId",
    normalizeAllowEntry: (entry) => normalizeTestAllowEntry(entry),
    notifyApproval: async ({ cfg, id }) => {
      await sendTestMessage({
        to: id,
        text: PAIRING_APPROVED_MESSAGE,
        cfg: cfg as TestConfig,
      });
    },
  },
  capabilities: {
    chatTypes: ["direct", "group"],
  },
  reload: { configPrefixes: ["channels.vimalinx"] },
  config: {
    listAccountIds: (cfg) => listTestAccountIds(cfg as TestConfig),
    resolveAccount: (cfg, accountId) => resolveTestAccount({ cfg: cfg as TestConfig, accountId }),
    defaultAccountId: (cfg) => resolveDefaultTestAccountId(cfg as TestConfig),
    setAccountEnabled: ({ cfg, accountId, enabled }) =>
      setAccountEnabledInConfigSection({
        cfg,
        sectionKey: "vimalinx",
        accountId,
        enabled,
        allowTopLevel: true,
      }),
    deleteAccount: ({ cfg, accountId }) =>
      deleteAccountFromConfigSection({
        cfg,
        sectionKey: "vimalinx",
        accountId,
        clearBaseFields: [
          "name",
          "userId",
          "baseUrl",
          "token",
          "webhookPath",
          "webhookToken",
          "inboundMode",
          "pollIntervalMs",
          "pollWaitMs",
        ],
      }),
    isConfigured: (account) => Boolean(account.baseUrl),
    describeAccount: (account) => ({
      accountId: account.accountId,
      name: account.name,
      enabled: account.enabled,
      configured: Boolean(account.baseUrl),
      baseUrl: account.baseUrl,
      tokenSource: account.tokenSource,
      webhookPath: account.webhookPath,
    }),
    resolveAllowFrom: ({ cfg, accountId }) =>
      (resolveTestAccount({ cfg: cfg as TestConfig, accountId }).config.allowFrom ?? []).map(
        (entry) => String(entry),
      ),
    formatAllowFrom: ({ allowFrom }) =>
      normalizeTestAllowlist(allowFrom).filter(Boolean),
  },
  security: {
    resolveDmPolicy: ({ cfg, accountId, account }) => {
      const resolvedAccountId = accountId ?? account.accountId ?? DEFAULT_ACCOUNT_ID;
      const useAccountPath = Boolean(
        (cfg as ClawdbotConfig).channels?.vimalinx?.accounts?.[resolvedAccountId],
      );
      const basePath = useAccountPath
        ? `channels.vimalinx.accounts.${resolvedAccountId}.`
        : "channels.vimalinx.";
      return {
        policy: account.config.dmPolicy ?? "pairing",
        allowFrom: account.config.allowFrom ?? [],
        policyPath: `${basePath}dmPolicy`,
        allowFromPath: basePath,
        approveHint: formatPairingApproveHint("vimalinx"),
        normalizeEntry: (raw) => normalizeTestAllowEntry(raw),
      };
    },
    collectWarnings: ({ account, cfg }) => {
      const defaultGroupPolicy = (cfg as ClawdbotConfig).channels?.defaults?.groupPolicy;
      const groupPolicy = account.config.groupPolicy ?? defaultGroupPolicy ?? "allowlist";
      if (groupPolicy !== "open") return [];
      return [
        "- Vimalinx Server groups: groupPolicy=\"open\" allows any member to trigger (mention-gated). Set channels.vimalinx.groupPolicy=\"allowlist\" + channels.vimalinx.groupAllowFrom to restrict senders.",
      ];
    },
  },
  groups: {
    resolveRequireMention: resolveTestGroupRequireMention,
  },
  messaging: {
    normalizeTarget: normalizeTestMessagingTarget,
    targetResolver: {
      looksLikeId: looksLikeTestTargetId,
      hint: "<chatId>",
    },
  },
  outbound: {
    deliveryMode: "direct",
    chunker: (text, limit) => getTestRuntime().channel.text.chunkText(text, limit),
    chunkerMode: "text",
    textChunkLimit: 4000,
    resolveTarget: ({ to }) => {
      const trimmed = to?.trim();
      if (!trimmed) {
        return { ok: false, error: new Error("Delivering to Vimalinx Server requires --to <chatId>") };
      }
      return { ok: true, to: trimmed };
    },
    sendText: async ({ to, text, accountId, cfg, replyToId }) => {
      return await sendTestMessage({
        to,
        text,
        cfg: cfg as TestConfig,
        accountId: accountId ?? undefined,
        replyToId: replyToId ?? undefined,
      });
    },
    sendMedia: async ({ to, text, mediaUrl, accountId, cfg, replyToId }) => {
      const combined = text?.trim()
        ? `${text.trim()}\n\nAttachment: ${mediaUrl}`
        : `Attachment: ${mediaUrl}`;
      return await sendTestMessage({
        to,
        text: combined,
        cfg: cfg as TestConfig,
        accountId: accountId ?? undefined,
        replyToId: replyToId ?? undefined,
      });
    },
  },
  status: {
    defaultRuntime: {
      accountId: DEFAULT_ACCOUNT_ID,
      running: false,
      lastStartAt: null,
      lastStopAt: null,
      lastError: null,
      lastInboundAt: null,
      lastOutboundAt: null,
    },
    buildAccountSnapshot: ({ account, runtime }) => ({
      accountId: account.accountId,
      name: account.name,
      enabled: account.enabled,
      configured: Boolean(account.baseUrl),
      baseUrl: account.baseUrl,
      webhookPath: account.webhookPath,
      running: runtime?.running ?? false,
      lastStartAt: runtime?.lastStartAt ?? null,
      lastStopAt: runtime?.lastStopAt ?? null,
      lastError: runtime?.lastError ?? null,
      lastInboundAt: runtime?.lastInboundAt ?? null,
      lastOutboundAt: runtime?.lastOutboundAt ?? null,
      dmPolicy: account.config.dmPolicy ?? "pairing",
      tokenSource: account.tokenSource,
    }),
  },
  setup: {
    resolveAccountId: ({ accountId }) => normalizeAccountId(accountId),
    applyAccountName: ({ cfg, accountId, name }) =>
      applyAccountNameToChannelSection({
        cfg,
        channelKey: "vimalinx",
        accountId,
        name,
      }),
    validateInput: ({ input }) => {
      const url = input.url?.trim() || input.httpUrl?.trim();
      if (!url) {
        return "Vimalinx Server requires --url (base URL).";
      }
      return null;
    },
    applyAccountConfig: ({ cfg, accountId, input }) => {
      let next = migrateBaseNameToDefaultAccount(cfg, "vimalinx", accountId);
      const url = input.url?.trim() || input.httpUrl?.trim();
      const token = input.token?.trim();
      const webhookPath = input.webhookPath?.trim();
      next = applyAccountNameToChannelSection({
        cfg: next,
        channelKey: "vimalinx",
        accountId,
        name: input.name,
      });
      if (accountId === DEFAULT_ACCOUNT_ID) {
        return {
          ...next,
          channels: {
            ...next.channels,
            vimalinx: {
              ...next.channels?.vimalinx,
              enabled: true,
              ...(url ? { baseUrl: url } : {}),
              ...(token ? { token } : {}),
              ...(webhookPath ? { webhookPath } : {}),
            },
          },
        };
      }
      return {
        ...next,
        channels: {
          ...next.channels,
          vimalinx: {
            ...next.channels?.vimalinx,
            enabled: true,
            accounts: {
              ...next.channels?.vimalinx?.accounts,
              [accountId]: {
                ...next.channels?.vimalinx?.accounts?.[accountId],
                enabled: true,
                ...(url ? { baseUrl: url } : {}),
                ...(token ? { token } : {}),
                ...(webhookPath ? { webhookPath } : {}),
              },
            },
          },
        },
      };
    },
  },
  gateway: {
    startAccount: async (ctx) => {
      const account = ctx.account;
      const inboundMode =
        account.config.inboundMode?.toLowerCase() === "poll" ? "poll" : "webhook";
      const modeLabel = inboundMode === "poll" ? "poller" : "webhook";
      ctx.log?.info(`[${account.accountId}] starting Vimalinx Server ${modeLabel}`);
      ctx.setStatus({
        accountId: account.accountId,
        running: true,
        lastStartAt: Date.now(),
        webhookPath: account.webhookPath,
      });
      const unregister = await startTestMonitor({
        account,
        config: ctx.cfg as TestConfig,
        runtime: ctx.runtime,
        abortSignal: ctx.abortSignal,
        inboundMode,
        statusSink: (patch) => ctx.setStatus({ accountId: account.accountId, ...patch }),
      });
      return () => {
        unregister?.();
        ctx.setStatus({
          accountId: account.accountId,
          running: false,
          lastStopAt: Date.now(),
        });
      };
    },
  },
};
