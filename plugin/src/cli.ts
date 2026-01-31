import type { Command } from "commander";
import type { ClawdbotConfig, PluginRuntime } from "openclaw/plugin-sdk";

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

export function registerVimalinxCli(params: {
  program: Command;
  runtime: PluginRuntime;
  logger: Logger;
}) {
  const { program, runtime, logger } = params;
  const root = program.command("vimalinx").description("Vimalinx Server channel utilities");

  root
    .command("register")
    .description("Register a Vimalinx user and update local config")
    .requiredOption("--server <url>", "Server base URL, e.g. http://1.2.3.4:8788")
    .requiredOption("--password <password>", "Account password (6-64 chars)")
    .option("--user <id>", "User id (optional)")
    .option("--name <name>", "Display name (optional)")
    .option("--server-token <token>", "Server registration token (optional)")
    .option("--invite <code>", "Invite code (optional)")
    .action(
      async (options: {
        server: string;
        password: string;
        user?: string;
        name?: string;
        serverToken?: string;
        invite?: string;
      }) => {
        const serverUrl = resolveServerUrl(options.server);
        const password = options.password.trim();
        if (!password) throw new Error("Missing --password");

        const payload: Record<string, string> = { password };
        if (options.user?.trim()) payload.userId = options.user.trim();
        if (options.name?.trim()) payload.displayName = options.name.trim();
        if (options.invite?.trim()) payload.inviteCode = options.invite.trim();

        const headers: Record<string, string> = {
          "Content-Type": "application/json",
        };
        if (options.serverToken?.trim()) {
          headers.Authorization = `Bearer ${options.serverToken.trim()}`;
        }

        const registerResponse = await fetch(`${serverUrl}/api/register`, {
          method: "POST",
          headers,
          body: JSON.stringify(payload),
        });
        const registerData = (await registerResponse.json().catch(() => ({}))) as {
          ok?: boolean;
          userId?: string;
          error?: string;
        };

        if (!registerResponse.ok || !registerData.ok || !registerData.userId) {
          throw new Error(
            registerData.error ?? `Registration failed (${registerResponse.status})`,
          );
        }

        const tokenResponse = await fetch(`${serverUrl}/api/token`, {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ userId: registerData.userId, password }),
        });
        const tokenData = (await tokenResponse.json().catch(() => ({}))) as {
          ok?: boolean;
          userId?: string;
          token?: string;
          error?: string;
        };
        if (!tokenResponse.ok || !tokenData.ok || !tokenData.token) {
          throw new Error(tokenData.error ?? `Token request failed (${tokenResponse.status})`);
        }

        const cfg = runtime.config.loadConfig() as ClawdbotConfig;
        const channels = (cfg.channels ?? {}) as Record<string, unknown>;
        const existing = (channels.vimalinx ?? {}) as TestAccountConfig;

        const nextVimalinx = {
          ...existing,
          enabled: true,
          baseUrl: serverUrl,
          token: tokenData.token,
          userId: registerData.userId,
          inboundMode: "poll",
          dmPolicy: existing.dmPolicy ?? "open",
          allowFrom: resolveAllowFrom(existing.allowFrom),
        };

        const plugins = (cfg.plugins ?? {}) as Record<string, unknown>;
        const entries = (plugins.entries ?? {}) as Record<string, unknown>;
        const currentTest = (entries["vimalinx"] ?? {}) as Record<string, unknown>;

        await runtime.config.writeConfigFile({
          ...cfg,
          channels: { ...channels, vimalinx: nextVimalinx },
          plugins: {
            ...plugins,
            entries: {
              ...entries,
              vimalinx: { ...currentTest, enabled: true },
            },
          },
        });

        logger.info?.(`Registered "${data.userId}" and updated config.`);
        logger.info?.("Restart: openclaw gateway restart");
      },
    );
}
