#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
TARGET_DIR="${TEST_PLUGIN_DIR:-$HOME/.openclaw/extensions/vimalinx}"

# Clean up installs from older layouts/names.
LEGACY_DIRS=(
  "$HOME/.clawdbot/extensions/vimalinx"
  "$HOME/.openclaw/extensions/vimalinx-server-plugin"
  "$HOME/.clawdbot/extensions/vimalinx-server-plugin"
)

if ! command -v openclaw >/dev/null 2>&1; then
  echo "openclaw not found in PATH. Install the official package first." >&2
  exit 1
fi

echo "Installing Vimalinx Server plugin to: ${TARGET_DIR}"
for legacy_dir in "${LEGACY_DIRS[@]}"; do
  if [[ -d "${legacy_dir}" && "${legacy_dir}" != "${TARGET_DIR}" ]]; then
    rm -rf "${legacy_dir}"
  fi
done
mkdir -p "${TARGET_DIR}"
rsync -a --delete "${PLUGIN_DIR}/" "${TARGET_DIR}/"

cd "${TARGET_DIR}"
npm install --omit=dev

openclaw plugins install "${TARGET_DIR}"

cat <<'EOF'
Done.
Next:
  1) Run: openclaw quickstart
  2) Select Vimalinx Server
  3) Paste the token (server URL uses the default)
EOF
