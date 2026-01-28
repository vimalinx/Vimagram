#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_DIR="${REPO_DIR}/plugin"
TARGET_DIR="${VIMALINX_PLUGIN_DIR:-$HOME/.clawdbot/extensions/vimalinx-server-plugin}"

if ! command -v clawdbot >/dev/null 2>&1; then
  echo "clawdbot not found in PATH. Install the CLI first." >&2
  exit 1
fi

echo "Installing Vimalinx Server plugin to: ${TARGET_DIR}"
mkdir -p "${TARGET_DIR}"
if command -v rsync >/dev/null 2>&1; then
  rsync -a --delete "${PLUGIN_DIR}/" "${TARGET_DIR}/"
else
  cp -a "${PLUGIN_DIR}/." "${TARGET_DIR}/"
fi

clawdbot plugins install "${TARGET_DIR}"
clawdbot plugins enable vimalinx-server-plugin >/dev/null 2>&1 || true

cat <<'EOF'
Done.
Next:
  1) Run: clawdbot onboard
  2) Select Vimalinx Server
  3) Paste the token
EOF
