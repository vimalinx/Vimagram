---
name: vimalinx-profile
description: Debug: verify offline skill delivery and tool-dispatch
command-dispatch: tool
command-tool: vimalinx_profile
command-arg-mode: raw
---

# Vimalinx Profile (debug)

This skill is shipped **offline** with the `vimalinx` plugin.

Usage:

- `/vimalinx_profile` — prints a small JSON payload produced by the plugin tool.

This is meant as a smoke test:

1. The plugin's `skills/` directory is discovered.
2. The skill command is exposed.
3. Tool-dispatch works (bypasses the model).
