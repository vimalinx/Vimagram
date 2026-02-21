import type { ClawdbotPluginApi } from "openclaw/plugin-sdk";
import { emptyPluginConfigSchema } from "openclaw/plugin-sdk";

import { vimalinxPlugin } from "./src/channel.js";
import { registerVimalinxCli } from "./src/cli.js";
import { handleTestWebhookRequest } from "./src/monitor.js";
import { setTestRuntime } from "./src/runtime.js";
import { createVimalinxProfileTool } from "./src/tools/vimalinx-profile-tool.js";

const plugin = {
  id: "vimalinx",
  name: "VimaClawNet Server",
  description: "VimaClawNet Server channel plugin",
  configSchema: emptyPluginConfigSchema(),
  register(api: ClawdbotPluginApi) {
    setTestRuntime(api.runtime);
    api.registerChannel({ plugin: vimalinxPlugin });
    api.registerTool((ctx) => createVimalinxProfileTool(ctx));
    api.registerHttpHandler(handleTestWebhookRequest);
    api.registerCli(
      ({ program }) => {
        registerVimalinxCli({ program, runtime: api.runtime, logger: api.logger });
      },
      { commands: ["vimalinx"] },
    );
  },
};

export default plugin;
