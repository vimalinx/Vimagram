import { Type } from "@sinclair/typebox";

import type { AnyAgentTool } from "openclaw/plugin-sdk";
import type { OpenClawPluginToolContext } from "openclaw/plugin-sdk";

const VimalinxProfileToolSchema = Type.Object(
  {
    command: Type.Optional(Type.String()),
    commandName: Type.Optional(Type.String()),
    skillName: Type.Optional(Type.String()),
  },
  { additionalProperties: true },
);

export function createVimalinxProfileTool(ctx: OpenClawPluginToolContext): AnyAgentTool {
  return {
    name: "vimalinx_profile",
    label: "Vimalinx Profile",
    description: "Debug tool for verifying vimalinx skill/tool delivery.",
    ownerOnly: true,
    parameters: VimalinxProfileToolSchema,
    execute: async (_toolCallId, args) => {
      const params = args as Record<string, unknown>;
      const rawCommand = typeof params.command === "string" ? params.command : "";
      const payload = {
        ok: true,
        tool: "vimalinx_profile",
        dispatch: "tool",
        received: {
          commandName: typeof params.commandName === "string" ? params.commandName : null,
          skillName: typeof params.skillName === "string" ? params.skillName : null,
          hasCommandArgs: rawCommand.trim().length > 0,
          commandArgsLength: rawCommand.length,
        },
        context: {
          messageChannel: ctx.messageChannel ?? null,
          sandboxed: typeof ctx.sandboxed === "boolean" ? ctx.sandboxed : null,
          hasAgentId: typeof ctx.agentId === "string" && ctx.agentId.trim().length > 0,
          hasSessionKey: typeof ctx.sessionKey === "string" && ctx.sessionKey.trim().length > 0,
          hasAgentAccountId:
            typeof ctx.agentAccountId === "string" && ctx.agentAccountId.trim().length > 0,
        },
      };

      return { content: JSON.stringify(payload, null, 2) };
    },
  };
}
