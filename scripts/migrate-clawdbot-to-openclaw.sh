#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

OLD_STATE_DIR="${CLAWDBOT_STATE_DIR:-$HOME/.clawdbot}"
NEW_STATE_DIR="${OPENCLAW_STATE_DIR:-$HOME/.openclaw}"

OLD_CONFIG_PATH="${CLAWDBOT_CONFIG:-$OLD_STATE_DIR/clawdbot.json}"
NEW_CONFIG_PATH="${OPENCLAW_CONFIG_PATH:-${OPENCLAW_CONFIG:-$NEW_STATE_DIR/openclaw.json}}"

OLD_PLUGIN_DIR="${OLD_STATE_DIR}/extensions/vimalinx"
REPO_PLUGIN_DIR="${REPO_DIR}/plugin"

if ! command -v openclaw >/dev/null 2>&1; then
  echo "openclaw not found in PATH. Install OpenClaw CLI first." >&2
  exit 1
fi

mkdir -p "${NEW_STATE_DIR}"

if [[ ! -f "${NEW_CONFIG_PATH}" && -f "${OLD_CONFIG_PATH}" ]]; then
  mkdir -p "$(dirname "${NEW_CONFIG_PATH}")"
  cp -a "${OLD_CONFIG_PATH}" "${NEW_CONFIG_PATH}"
  echo "Copied config: ${OLD_CONFIG_PATH} -> ${NEW_CONFIG_PATH}"
fi

PLUGIN_SRC=""
if [[ -d "${REPO_PLUGIN_DIR}" ]]; then
  PLUGIN_SRC="${REPO_PLUGIN_DIR}"
elif [[ -d "${OLD_PLUGIN_DIR}" ]]; then
  PLUGIN_SRC="${OLD_PLUGIN_DIR}"
fi

if [[ -z "${PLUGIN_SRC}" ]]; then
  echo "Could not find Vimalinx plugin source." >&2
  echo "Expected one of:" >&2
  echo "  - ${REPO_PLUGIN_DIR}" >&2
  echo "  - ${OLD_PLUGIN_DIR}" >&2
  exit 1
fi

echo "Installing plugin into OpenClaw from: ${PLUGIN_SRC}"
openclaw plugins install "${PLUGIN_SRC}"
openclaw plugins enable vimalinx >/dev/null 2>&1 || true

cat <<EOF
Done.

If you still see OpenClaw reading the old Clawdbot config path during onboarding,
make sure your shell does NOT set OPENCLAW_CONFIG_PATH to a clawdbot.json path.

Try:
  openclaw plugins list
  openclaw onboard
EOF
