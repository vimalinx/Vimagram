---
name: vimalinx-instance-profiles
description: Placeholder identity profiles for VimaClawNet instances
user-invocable: false
disable-model-invocation: true
---

# VimaClawNet instance profiles (placeholder)

This is a placeholder skill bundle shipped **offline** with the `vimalinx` plugin.

It exists to validate that "skills can be delivered" together with the installer/plugin
without requiring ClawHub.

Runtime behavior currently comes from the plugin injecting identity prompts based on `ModeId`
(for example `inst_glm_4_7_docs`).

Future: convert these placeholders into real skill packs (ecom/docs/media) and wire them
to server-managed instance identity selection.
