import { DEFAULT_ACCOUNT_ID, normalizeAccountId } from "openclaw/plugin-sdk";

import type { TestAccountConfig, TestConfig } from "./types.js";

export type ResolvedTestAccount = {
  accountId: string;
  enabled: boolean;
  name?: string;
  baseUrl?: string;
  token?: string;
  tokenSource: "config" | "none";
  webhookPath?: string;
  webhookToken?: string;
  config: TestAccountConfig;
};

function listConfiguredAccountIds(cfg: TestConfig): string[] {
  const accounts = cfg.channels?.vimalinx?.accounts;
  if (!accounts || typeof accounts !== "object") return [];
  const ids = new Set<string>();
  for (const key of Object.keys(accounts)) {
    if (!key) continue;
    ids.add(normalizeAccountId(key));
  }
  return [...ids];
}

export function listTestAccountIds(cfg: TestConfig): string[] {
  const ids = listConfiguredAccountIds(cfg);
  if (ids.length === 0) return [DEFAULT_ACCOUNT_ID];
  return ids.sort((a, b) => a.localeCompare(b));
}

export function resolveDefaultTestAccountId(cfg: TestConfig): string {
  const ids = listTestAccountIds(cfg);
  if (ids.includes(DEFAULT_ACCOUNT_ID)) return DEFAULT_ACCOUNT_ID;
  return ids[0] ?? DEFAULT_ACCOUNT_ID;
}

function resolveAccountConfig(cfg: TestConfig, accountId: string): TestAccountConfig | undefined {
  const accounts = cfg.channels?.vimalinx?.accounts;
  if (!accounts || typeof accounts !== "object") return undefined;
  const direct = accounts[accountId] as TestAccountConfig | undefined;
  if (direct) return direct;
  const normalized = normalizeAccountId(accountId);
  const matchKey = Object.keys(accounts).find((key) => normalizeAccountId(key) === normalized);
  return matchKey ? (accounts[matchKey] as TestAccountConfig | undefined) : undefined;
}

function mergeTestAccountConfig(cfg: TestConfig, accountId: string): TestAccountConfig {
  const { accounts: _ignored, ...base } = (cfg.channels?.vimalinx ?? {}) as TestAccountConfig & {
    accounts?: unknown;
  };
  const account = resolveAccountConfig(cfg, accountId) ?? {};
  return { ...base, ...account };
}

export function resolveTestAccount(params: {
  cfg: TestConfig;
  accountId?: string | null;
}): ResolvedTestAccount {
  const normalized = normalizeAccountId(params.accountId);
  const merged = mergeTestAccountConfig(params.cfg, normalized);
  const baseEnabled = params.cfg.channels?.vimalinx?.enabled !== false;
  const enabled = baseEnabled && merged.enabled !== false;
  const baseUrl = merged.baseUrl?.trim().replace(/\/$/, "");
  const token = merged.token?.trim();
  const webhookToken = merged.webhookToken?.trim();

  return {
    accountId: normalized,
    enabled,
    name: merged.name?.trim() || undefined,
    baseUrl: baseUrl || undefined,
    token: token || undefined,
    tokenSource: token ? "config" : "none",
    webhookPath: merged.webhookPath?.trim() || undefined,
    webhookToken: webhookToken || undefined,
    config: merged,
  } satisfies ResolvedTestAccount;
}

export function listEnabledTestAccounts(cfg: TestConfig): ResolvedTestAccount[] {
  return listTestAccountIds(cfg)
    .map((accountId) => resolveTestAccount({ cfg, accountId }))
    .filter((account) => account.enabled);
}
