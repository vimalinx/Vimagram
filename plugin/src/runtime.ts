import type { PluginRuntime } from "clawdbot/plugin-sdk";

let runtime: PluginRuntime | null = null;

export function setTestRuntime(next: PluginRuntime) {
  runtime = next;
}

export function getTestRuntime(): PluginRuntime {
  if (!runtime) {
    throw new Error("Test runtime not initialized");
  }
  return runtime;
}
