import type { Command } from "commander";
import type { ClawdbotConfig, PluginRuntime } from "clawdbot/plugin-sdk";

import type { TestAccountConfig } from "./types.js";

type Logger = {
  info?: (message: string) => void;
  warn?: (message: string) => void;
  error?: (message: string) => void;
};

function resolveServerUrl(raw: string): string {
  const trimmed = raw.trim();
  if (!trimmed) throw new Error("Missing --server");
  if (!/^https?:\/\//i.test(trimmed)) {
    throw new Error("Server must include http:// or https://");
  }
  return trimmed.replace(/\/+$/, "");
}

function resolveAllowFrom(existing: unknown): Array<string | number> {
  if (Array.isArray(existing) && existing.length > 0) return existing;
  return ["*"];
}

export function registerTestCli(params: {
  program: Command;
  runtime: PluginRuntime;
  logger: Logger;
}) {
  const { program, runtime, logger } = params;
  const root = program.command("test").description("Vimalinx Server channel utilities");

  root
    .command("register")
    .description("Register a test user and update local config")
    .requiredOption("--server <url>", "Server base URL, e.g. http://1.2.3.4:8788")
    .requiredOption("--password <password>", "Account password (6-64 chars)")
    .option("--user <id>", "User id (optional)")
    .option("--name <name>", "Display name (optional)")
    .option("--server-token <token>", "Server registration token (optional)")
    .action(
      async (options: {
        server: string;
        password: string;
        user?: string;
        name?: string;
        serverToken?: string;
      }) => {
        const serverUrl = resolveServerUrl(options.server);
        const password = options.password.trim();
        if (!password) throw new Error("Missing --password");

        const payload: Record<string, string> = { password };
        if (options.user?.trim()) payload.userId = options.user.trim();
        if (options.name?.trim()) payload.displayName = options.name.trim();

        const headers: Record<string, string> = {
          "Content-Type": "application/json",
        };
        if (options.serverToken?.trim()) {
          headers.Authorization = `Bearer ${options.serverToken.trim()}`;
        }

        const response = await fetch(`${serverUrl}/api/register`, {
          method: "POST",
          headers,
          body: JSON.stringify(payload),
        });
        const data = (await response.json().catch(() => ({}))) as {
          ok?: boolean;
          userId?: string;
          token?: string;
          error?: string;
        };

        if (!response.ok || !data.ok || !data.userId || !data.token) {
          throw new Error(data.error ?? `Registration failed (${response.status})`);
        }

        const cfg = runtime.config.loadConfig() as ClawdbotConfig;
        const channels = (cfg.channels ?? {}) as Record<string, unknown>;
        const existing = (channels.test ?? {}) as TestAccountConfig;

        const nextTest = {
          ...existing,
          enabled: true,
          baseUrl: serverUrl,
          token: data.token,
          userId: data.userId,
          inboundMode: "poll",
          dmPolicy: existing.dmPolicy ?? "open",
          allowFrom: resolveAllowFrom(existing.allowFrom),
        };

        const plugins = (cfg.plugins ?? {}) as Record<string, unknown>;
        const entries = (plugins.entries ?? {}) as Record<string, unknown>;
        const currentTest = (entries.test ?? {}) as Record<string, unknown>;

        await runtime.config.writeConfigFile({
          ...cfg,
          channels: { ...channels, test: nextTest },
          plugins: { ...plugins, entries: { ...entries, test: { ...currentTest, enabled: true } } },
        });

        logger.info?.(`Registered "${data.userId}" and updated config.`);
        logger.info?.("Restart: clawdbot gateway restart");
      },
    );
}
