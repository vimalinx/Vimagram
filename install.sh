#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_DIR="${REPO_DIR}/plugin"
TARGET_DIR="${VIMALINX_PLUGIN_DIR:-$HOME/.openclaw/extensions/vimalinx}"
CONFIG_PATH="${OPENCLAW_CONFIG:-${CLAWDBOT_CONFIG:-$HOME/.openclaw/openclaw.json}}"
DEFAULT_SERVER_URL="http://123.60.21.129:8788"
SERVER_URL="${VIMALINX_SERVER_URL:-}"
TOKEN="${VIMALINX_TOKEN:-}"
INBOUND_MODE="${VIMALINX_INBOUND_MODE:-poll}"

if ! command -v openclaw >/dev/null 2>&1; then
  echo "openclaw not found in PATH. Install the CLI first." >&2
  exit 1
fi
if ! command -v curl >/dev/null 2>&1; then
  echo "curl not found in PATH. Install curl first." >&2
  exit 1
fi
if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 not found in PATH. Install Python 3 first." >&2
  exit 1
fi

if [[ -f "${CONFIG_PATH}" ]]; then
  python3 - "${CONFIG_PATH}" <<'PY'
import json
import os
import sys

config_path = sys.argv[1]

try:
  with open(config_path, "r", encoding="utf-8") as f:
    config = json.load(f)
except Exception:
  raise SystemExit(0)

plugins = config.get("plugins")
if not isinstance(plugins, dict):
  plugins = {}
entries = plugins.get("entries")
if not isinstance(entries, dict):
  entries = {}

if "vimalinx-server-plugin" in entries:
  entries.pop("vimalinx-server-plugin", None)
  plugins["entries"] = entries
  config["plugins"] = plugins
  with open(config_path, "w", encoding="utf-8") as f:
    json.dump(config, f, indent=2, ensure_ascii=True)
    f.write("\n")
PY
fi

echo "Installing Vimalinx Server plugin to: ${TARGET_DIR}"
if [[ -d "${LEGACY_DIR}" && "${LEGACY_DIR}" != "${TARGET_DIR}" ]]; then
  rm -rf "${LEGACY_DIR}"
fi
if [[ -d "${TARGET_DIR}" ]]; then
  if [[ "${TARGET_DIR}" == "${HOME}/.openclaw/extensions/vimalinx" || "${TARGET_DIR}" == "${HOME}/.clawdbot/extensions/vimalinx" || "${VIMALINX_FORCE_OVERWRITE:-}" == "1" ]]; then
    rm -rf "${TARGET_DIR}"
  else
    echo "Target already exists: ${TARGET_DIR}" >&2
    echo "Set VIMALINX_FORCE_OVERWRITE=1 to overwrite." >&2
    exit 1
  fi
fi
mkdir -p "${TARGET_DIR}"
if command -v rsync >/dev/null 2>&1; then
  rsync -a --delete "${PLUGIN_DIR}/" "${TARGET_DIR}/"
else
  cp -a "${PLUGIN_DIR}/." "${TARGET_DIR}/"
fi

# Ensure OpenClaw discovers the plugin before config validation.
openclaw plugins install "${TARGET_DIR}" >/dev/null
openclaw plugins enable vimalinx >/dev/null 2>&1 || true

if [[ -z "${SERVER_URL}" ]]; then
  read -r -p "Vimalinx Server URL [${DEFAULT_SERVER_URL}]: " SERVER_URL
fi
SERVER_URL="${SERVER_URL:-$DEFAULT_SERVER_URL}"
if [[ ! "${SERVER_URL}" =~ ^https?:// ]]; then
  SERVER_URL="https://${SERVER_URL}"
fi
SERVER_URL="${SERVER_URL%/}"

if [[ -z "${TOKEN}" ]]; then
  read -r -s -p "Vimalinx token: " TOKEN
  echo
fi
TOKEN="$(printf "%s" "${TOKEN}" | tr -d '\r\n' | xargs)"
if [[ -z "${TOKEN}" ]]; then
  echo "Missing Vimalinx token." >&2
  exit 1
fi

if [[ "${INBOUND_MODE}" != "poll" && "${INBOUND_MODE}" != "webhook" ]]; then
  echo "Invalid VIMALINX_INBOUND_MODE (use poll or webhook)." >&2
  exit 1
fi

if ! login_response="$(curl --http1.1 -sS --retry 2 --retry-all-errors \
  --connect-timeout 10 --max-time 20 \
  -X POST "${SERVER_URL}/api/login" \
  -H "Content-Type: application/json" \
  -d "{\"token\":\"${TOKEN}\"}")"; then
  echo "Login request failed. Check server URL/TLS and retry." >&2
  exit 1
fi

python3 - "$CONFIG_PATH" "$SERVER_URL" "$INBOUND_MODE" "$login_response" <<'PY'
import json
import os
import sys

config_path = sys.argv[1]
server_url = sys.argv[2]
inbound_mode = sys.argv[3]
raw = sys.argv[4]

try:
  data = json.loads(raw)
except json.JSONDecodeError as exc:
  raise SystemExit(f"Login failed: {exc}")

if not data.get("ok") or not data.get("userId") or not data.get("token"):
  raise SystemExit(f"Login failed: {data.get('error', raw)}")

user_id = str(data["userId"])
token = str(data["token"])

config = {}
if os.path.exists(config_path):
  with open(config_path, "r", encoding="utf-8") as f:
    try:
      config = json.load(f)
    except json.JSONDecodeError:
      raise SystemExit("Config is not valid JSON. Please convert to JSON and retry.")

channels = config.get("channels")
if not isinstance(channels, dict):
  channels = {}

test_cfg = channels.get("vimalinx")
if not isinstance(test_cfg, dict):
  test_cfg = {}

test_cfg.update({
  "enabled": True,
  "baseUrl": server_url,
  "token": token,
  "userId": user_id,
  "inboundMode": inbound_mode,
  "dmPolicy": "open",
  "allowFrom": ["*"],
})

channels["vimalinx"] = test_cfg
config["channels"] = channels

plugins = config.get("plugins")
if not isinstance(plugins, dict):
  plugins = {}
entries = plugins.get("entries")
if not isinstance(entries, dict):
  entries = {}
entries.pop("test", None)
entries["vimalinx"] = {**entries.get("vimalinx", {}), "enabled": True}
plugins["entries"] = entries

load = plugins.get("load")
if isinstance(load, dict):
  paths = load.get("paths")
  if isinstance(paths, list):
    filtered = [p for p in paths if "vimalinx" not in str(p) and "vimalinx-suite-core" not in str(p)]
    if filtered:
      load["paths"] = filtered
      plugins["load"] = load
    else:
      plugins.pop("load", None)

config["plugins"] = plugins

os.makedirs(os.path.dirname(config_path), exist_ok=True)
with open(config_path, "w", encoding="utf-8") as f:
  json.dump(config, f, indent=2, ensure_ascii=True)
  f.write("\n")

print("Configured user:", user_id)
print("Updated config:", config_path)
PY

if [[ "${VIMALINX_SKIP_DOCTOR_FIX:-}" != "1" ]]; then
  openclaw doctor --fix >/dev/null 2>&1 || true
fi

if [[ "${VIMALINX_SKIP_GATEWAY_START:-}" != "1" ]]; then
  openclaw gateway stop >/dev/null 2>&1 || true
  openclaw gateway start >/dev/null 2>&1 || true
fi

if [[ "${VIMALINX_SKIP_STATUS:-}" != "1" ]]; then
  sleep 2
  openclaw channels status --probe || true
fi

cat <<'EOF'
Done.
If you want to skip auto steps next time:
  - VIMALINX_SKIP_DOCTOR_FIX=1
  - VIMALINX_SKIP_GATEWAY_START=1
  - VIMALINX_SKIP_STATUS=1
EOF
