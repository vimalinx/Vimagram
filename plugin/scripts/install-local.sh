#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
TARGET_DIR="${TEST_PLUGIN_DIR:-$HOME/.clawdbot/extensions/test}"

if ! command -v clawdbot >/dev/null 2>&1; then
  echo "clawdbot not found in PATH. Install the official package first." >&2
  exit 1
fi

echo "Installing Vimalinx Server plugin to: ${TARGET_DIR}"
mkdir -p "${TARGET_DIR}"
rsync -a --delete "${PLUGIN_DIR}/" "${TARGET_DIR}/"

cd "${TARGET_DIR}"
npm install --omit=dev

clawdbot plugins install "${TARGET_DIR}"

cat <<'EOF'
Done.
Next:
  1) Run: clawdbot quickstart
  2) Select Vimalinx Server
  3) Paste the token (server URL uses the default)
EOF
