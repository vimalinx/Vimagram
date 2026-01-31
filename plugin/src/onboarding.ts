import type {
  ChannelOnboardingAdapter,
  ChannelOnboardingDmPolicy,
  ClawdbotConfig,
  DmPolicy,
  WizardPrompter,
} from "openclaw/plugin-sdk";
import {
  DEFAULT_ACCOUNT_ID,
  addWildcardAllowFrom,
  normalizeAccountId,
  promptAccountId,
} from "openclaw/plugin-sdk";

import {
  listTestAccountIds,
  resolveDefaultTestAccountId,
  resolveTestAccount,
} from "./accounts.js";
import type { TestConfig } from "./types.js";

const channel = "vimalinx" as const;
const PLUGIN_ID = "vimalinx";

function ensurePluginEntry(cfg: ClawdbotConfig): ClawdbotConfig {
  const plugins = (cfg.plugins ?? {}) as Record<string, unknown>;
  const entries = (plugins.entries ?? {}) as Record<string, unknown>;
  const nextEntries: Record<string, unknown> = { ...entries };

  const current = (nextEntries[PLUGIN_ID] ?? {}) as Record<string, unknown>;
  nextEntries[PLUGIN_ID] = { ...current, enabled: true };

  return { ...cfg, plugins: { ...plugins, entries: nextEntries } };
}

function normalizeServerUrl(raw: string): string | null {
  const trimmed = raw.trim();
  if (!trimmed) return null;
  if (!/^https?:\/\//i.test(trimmed)) return null;
  return trimmed.replace(/\/+$/, "");
}

function resolveAllowFrom(existing: unknown): Array<string | number> {
  if (Array.isArray(existing) && existing.length > 0) return existing;
  return ["*"];
}

async function loginTestUser(params: {
  serverUrl: string;
  token: string;
}): Promise<{ userId: string; token: string }> {
  const payload: Record<string, string> = {
    token: params.token.trim(),
  };

  const response = await fetch(`${params.serverUrl}/api/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });
  const data = (await response.json().catch(() => ({}))) as {
    ok?: boolean;
    userId?: string;
    token?: string;
    error?: string;
  };

  if (!response.ok || !data.ok || !data.userId || !data.token) {
    throw new Error(data.error ?? `Login failed (${response.status})`);
  }
  return { userId: data.userId, token: data.token };
}

const DEFAULT_TEST_SERVER_URL = "https://vimagram.vimalinx.xyz";
const OFFICIAL_SERVER_LABEL = "Vimalinx official";
const CUSTOM_SERVER_LABEL = "Custom server";

function setTestAccountConfig(
  cfg: ClawdbotConfig,
  accountId: string,
  patch: Record<string, unknown>,
): ClawdbotConfig {
  if (accountId === DEFAULT_ACCOUNT_ID) {
    return {
      ...cfg,
      channels: {
        ...cfg.channels,
        vimalinx: {
          ...cfg.channels?.vimalinx,
          enabled: true,
          ...patch,
        },
      },
    };
  }
  return {
    ...cfg,
    channels: {
      ...cfg.channels,
      vimalinx: {
        ...cfg.channels?.vimalinx,
        enabled: true,
        accounts: {
          ...cfg.channels?.vimalinx?.accounts,
          [accountId]: {
            ...cfg.channels?.vimalinx?.accounts?.[accountId],
            enabled: cfg.channels?.vimalinx?.accounts?.[accountId]?.enabled ?? true,
            ...patch,
          },
        },
      },
    },
  };
}

function resolveTestDmPolicy(cfg: ClawdbotConfig): DmPolicy {
  const accountId = resolveDefaultTestAccountId(cfg as TestConfig);
  const resolved = resolveTestAccount({ cfg: cfg as TestConfig, accountId });
  return resolved.config.dmPolicy ?? "open";
}

function setTestDmPolicy(cfg: ClawdbotConfig, dmPolicy: DmPolicy): ClawdbotConfig {
  const accountId = resolveDefaultTestAccountId(cfg as TestConfig);
  const resolved = resolveTestAccount({ cfg: cfg as TestConfig, accountId });
  const allowFrom =
    dmPolicy === "open" ? addWildcardAllowFrom(resolveAllowFrom(resolved.config.allowFrom)) : undefined;
  return setTestAccountConfig(cfg, accountId, {
    dmPolicy,
    ...(allowFrom ? { allowFrom } : {}),
  });
}

async function promptTestAllowFrom(params: {
  cfg: ClawdbotConfig;
  prompter: WizardPrompter;
  accountId?: string;
}): Promise<ClawdbotConfig> {
  const accountId =
    params.accountId && normalizeAccountId(params.accountId)
      ? (normalizeAccountId(params.accountId) ?? DEFAULT_ACCOUNT_ID)
      : resolveDefaultTestAccountId(params.cfg as TestConfig);
  const resolved = resolveTestAccount({ cfg: params.cfg as TestConfig, accountId });
  const existing = resolveAllowFrom(resolved.config.allowFrom);

  const raw = await params.prompter.text({
    message: "Vimalinx allowFrom (comma-separated user ids or *)",
    placeholder: "*",
    initialValue: existing.join(", "),
    validate: (value) => (String(value ?? "").trim() ? undefined : "Required"),
  });
  const allowFrom = String(raw)
    .split(/[\n,;]+/g)
    .map((entry) => entry.trim())
    .filter(Boolean);

  return setTestAccountConfig(params.cfg, accountId, {
    dmPolicy: "allowlist",
    allowFrom,
  });
}

const dmPolicy: ChannelOnboardingDmPolicy = {
  label: "Vimalinx Server",
  channel,
  policyKey: "channels.vimalinx.dmPolicy",
  allowFromKey: "channels.vimalinx.allowFrom",
  getCurrent: resolveTestDmPolicy,
  setPolicy: setTestDmPolicy,
  promptAllowFrom: promptTestAllowFrom,
};

export const vimalinxOnboardingAdapter: ChannelOnboardingAdapter = {
  channel,
  getStatus: async ({ cfg }) => {
    const configured = listTestAccountIds(cfg as TestConfig).some((accountId) =>
      Boolean(resolveTestAccount({ cfg: cfg as TestConfig, accountId }).baseUrl),
    );
    const defaultAccountId = resolveDefaultTestAccountId(cfg as TestConfig);
    const resolved = resolveTestAccount({ cfg: cfg as TestConfig, accountId: defaultAccountId });
    const baseUrl =
      normalizeServerUrl(resolved.baseUrl ?? DEFAULT_TEST_SERVER_URL) ?? DEFAULT_TEST_SERVER_URL;
    const serverLabel = baseUrl === DEFAULT_TEST_SERVER_URL ? "official" : "custom";
    return {
      channel,
      configured,
      statusLines: [
        `Vimalinx Server: ${configured ? "configured" : "needs server URL"}`,
        `Server: ${serverLabel}`,
      ],
      selectionHint: configured ? `configured · ${serverLabel}` : "local server",
      quickstartScore: configured ? 1 : 8,
    };
  },
  configure: async ({
    cfg,
    prompter,
    accountOverrides,
    shouldPromptAccountIds,
    forceAllowFrom,
  }) => {
    const override = accountOverrides.vimalinx?.trim();
    const defaultAccountId = resolveDefaultTestAccountId(cfg as TestConfig);
    let accountId = override ? normalizeAccountId(override) : defaultAccountId;
    if (shouldPromptAccountIds && !override) {
      accountId = await promptAccountId({
        cfg,
        prompter,
        label: "Vimalinx Server",
        currentId: accountId ?? defaultAccountId,
        listAccountIds: listTestAccountIds,
        defaultAccountId,
      });
    }
    accountId = accountId ?? defaultAccountId;

    const existing = resolveTestAccount({ cfg: cfg as TestConfig, accountId });
    const normalizedExisting =
      normalizeServerUrl(existing.baseUrl ?? DEFAULT_TEST_SERVER_URL) ?? DEFAULT_TEST_SERVER_URL;
    const isOfficial = normalizedExisting === DEFAULT_TEST_SERVER_URL;
    const serverChoice = await prompter.select({
      message: "Vimalinx server",
      options: [
        { value: "official", label: OFFICIAL_SERVER_LABEL },
        { value: "custom", label: CUSTOM_SERVER_LABEL },
      ],
      initialValue: isOfficial ? "official" : "custom",
    });
    let serverUrl = DEFAULT_TEST_SERVER_URL;
    if (serverChoice === "custom") {
      const rawServer = await prompter.text({
        message: "Server URL",
        placeholder: DEFAULT_TEST_SERVER_URL,
        initialValue: normalizedExisting,
        validate: (value) =>
          normalizeServerUrl(String(value ?? "")) ? undefined : "Must include http:// or https://",
      });
      serverUrl = normalizeServerUrl(String(rawServer)) ?? DEFAULT_TEST_SERVER_URL;
    }

    const token = await prompter.text({
      message: "Vimalinx token",
      placeholder: "from the mobile app",
      initialValue: existing.token ?? undefined,
      validate: (value) => (String(value ?? "").trim() ? undefined : "Required"),
    });
    const loggedIn = await loginTestUser({
      serverUrl,
      token: String(token),
    });
    const userId = loggedIn.userId;
    const inboundMode = (await prompter.select({
      message: "Inbound mode",
      options: [
        { value: "poll", label: "Poll (recommended)" },
        { value: "webhook", label: "Webhook" },
      ],
      initialValue: (existing.config.inboundMode ?? "poll") as "poll" | "webhook",
    })) as "poll" | "webhook";

    const allowFrom = resolveAllowFrom(existing.config.allowFrom);
    const security =
      serverUrl.toLowerCase().startsWith("https://")
        ? existing.config.security
        : { ...(existing.config.security ?? {}), requireHttps: false };
    const next = setTestAccountConfig(cfg, accountId, {
      baseUrl: serverUrl,
      token: String(token).trim(),
      userId: String(userId).trim(),
      inboundMode,
      dmPolicy: existing.config.dmPolicy ?? "open",
      allowFrom: forceAllowFrom ? resolveAllowFrom(allowFrom) : allowFrom,
      ...(security ? { security } : {}),
    });

    return { cfg: ensurePluginEntry(next), accountId };
  },
  dmPolicy,
  disable: (cfg) => ({
    ...cfg,
    channels: {
      ...cfg.channels,
      vimalinx: { ...cfg.channels?.vimalinx, enabled: false },
    },
  }),
};
