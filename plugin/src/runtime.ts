import type { PluginRuntime } from "openclaw/plugin-sdk";

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
