#!/usr/bin/env bash
set -euo pipefail

BUNDLE_PATH="${1:-$HOME/test-channel-bundle.tar.gz}"
INSTALL_DIR="${TEST_INSTALL_DIR:-/opt/test}"
USERS_FILE="${TEST_USERS_FILE:-/root/users.json}"
PORT="${TEST_SERVER_PORT:-8788}"
INBOUND_MODE="${TEST_INBOUND_MODE:-poll}"
LOG_PATH="${TEST_SERVER_LOG:-$INSTALL_DIR/server/server.log}"

if [[ ! -f "$BUNDLE_PATH" ]]; then
  echo "bundle not found: $BUNDLE_PATH" >&2
  exit 1
fi

sudo mkdir -p "$INSTALL_DIR"
sudo tar -xzf "$BUNDLE_PATH" -C "$INSTALL_DIR" --strip-components=2

pkill -f "node .*server.mjs" >/dev/null 2>&1 || true

nohup env TEST_INBOUND_MODE="$INBOUND_MODE" TEST_SERVER_PORT="$PORT" TEST_USERS_FILE="$USERS_FILE" \
  node "$INSTALL_DIR/server/server.mjs" > "$LOG_PATH" 2>&1 &

echo "Vimalinx Server started"
echo "log: $LOG_PATH"
