import type { ClawdbotPluginApi } from "clawdbot/plugin-sdk";
import { emptyPluginConfigSchema } from "clawdbot/plugin-sdk";

import { testPlugin } from "./src/channel.js";
import { registerTestCli } from "./src/cli.js";
import { handleTestWebhookRequest } from "./src/monitor.js";
import { setTestRuntime } from "./src/runtime.js";

const plugin = {
  id: "test",
  name: "Vimalinx Server",
  description: "Vimalinx Server channel plugin",
  configSchema: emptyPluginConfigSchema(),
  register(api: ClawdbotPluginApi) {
    setTestRuntime(api.runtime);
    api.registerChannel({ plugin: testPlugin });
    api.registerHttpHandler(handleTestWebhookRequest);
    api.registerCli(
      ({ program }) => {
        registerTestCli({ program, runtime: api.runtime, logger: api.logger });
      },
      { commands: ["test"] },
    );
  },
};

export default plugin;
