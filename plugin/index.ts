import type { ClawdbotPluginApi } from "openclaw/plugin-sdk";
import { emptyPluginConfigSchema } from "openclaw/plugin-sdk";

import { vimalinxPlugin } from "./src/channel.js";
import { registerVimalinxCli } from "./src/cli.js";
import { handleTestWebhookRequest } from "./src/monitor.js";
import { setTestRuntime } from "./src/runtime.js";

const plugin = {
  id: "vimalinx",
  name: "Vimalinx Server",
  description: "Vimalinx Server channel plugin",
  configSchema: emptyPluginConfigSchema(),
  register(api: ClawdbotPluginApi) {
    setTestRuntime(api.runtime);
    api.registerChannel({ plugin: vimalinxPlugin });
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
