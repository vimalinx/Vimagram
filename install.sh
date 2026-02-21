#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_DIR="${REPO_DIR}/plugin"
TARGET_DIR="${VIMALINX_PLUGIN_DIR:-$HOME/.openclaw/extensions/vimalinx}"
CONFIG_PATH="${OPENCLAW_CONFIG_PATH:-${CLAWDBOT_CONFIG_PATH:-$HOME/.openclaw/openclaw.json}}"
DEFAULT_SERVER_URL="http://49.235.88.239:18788"
SERVER_URL="${VIMALINX_SERVER_URL:-}"
TOKEN="${VIMALINX_TOKEN:-}"
INBOUND_MODE="${VIMALINX_INBOUND_MODE:-poll}"
MACHINE_ID="${VIMALINX_MACHINE_ID:-}"
MACHINE_LABEL="${VIMALINX_MACHINE_LABEL:-}"
AUTO_REGISTER_MACHINE="${VIMALINX_AUTO_REGISTER_MACHINE:-}"
MACHINE_HEARTBEAT_MS="${VIMALINX_MACHINE_HEARTBEAT_MS:-}"
BACKUP_OPENCLAW_CONFIG="${VIMALINX_BACKUP_OPENCLAW_CONFIG:-1}"
REINSTALL_OPENCLAW_CONFIG="${VIMALINX_REINSTALL_OPENCLAW_CONFIG:-0}"

is_true() {
  case "${1,,}" in
    1|y|yes|true|on) return 0 ;;
    *) return 1 ;;
  esac
}

backup_openclaw_config() {
  local config_path="$1"
  if [[ ! -f "${config_path}" ]]; then
    return 0
  fi
  local backup_dir backup_file
  backup_dir="$(dirname "${config_path}")/backups"
  backup_file="${backup_dir}/openclaw.$(date +%Y%m%d-%H%M%S).json"
  mkdir -p "${backup_dir}"
  cp "${config_path}" "${backup_file}"
  echo "Backed up OpenClaw config to: ${backup_file}"
}

# Clean up installs from older layouts/names.
LEGACY_DIRS=(
  "$HOME/.clawdbot/extensions/vimalinx"
  "$HOME/.openclaw/extensions/vimalinx-server-plugin"
  "$HOME/.clawdbot/extensions/vimalinx-server-plugin"
)

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

existing_server_url=""
existing_inbound_mode=""
existing_token=""
if [[ -f "${CONFIG_PATH}" ]]; then
  while IFS= read -r line; do
    case "$line" in
      server_url=*) existing_server_url="${line#server_url=}" ;;
      inbound_mode=*) existing_inbound_mode="${line#inbound_mode=}" ;;
      token=*) existing_token="${line#token=}" ;;
    esac
  done < <(python3 - "${CONFIG_PATH}" <<'PY'
import json
import sys

config_path = sys.argv[1]
try:
  with open(config_path, "r", encoding="utf-8") as f:
    config = json.load(f)
except Exception:
  raise SystemExit(0)

channels = config.get("channels")
if not isinstance(channels, dict):
  raise SystemExit(0)
cfg = channels.get("vimalinx")
if not isinstance(cfg, dict):
  raise SystemExit(0)

base_url = cfg.get("baseUrl") or ""
inbound_mode = cfg.get("inboundMode") or ""
token = cfg.get("token") or ""

print(f"server_url={str(base_url)}")
print(f"inbound_mode={str(inbound_mode)}")
print(f"token={str(token)}")
PY
  )
fi

if [[ -z "${VIMALINX_INBOUND_MODE:-}" && -n "${existing_inbound_mode}" ]]; then
  INBOUND_MODE="${existing_inbound_mode}"
fi

echo "Installing VimaClawNet plugin to: ${TARGET_DIR}"
for legacy_dir in "${LEGACY_DIRS[@]}"; do
  if [[ -d "${legacy_dir}" && "${legacy_dir}" != "${TARGET_DIR}" ]]; then
    rm -rf "${legacy_dir}"
  fi
done
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
# `openclaw plugins install` errors if the plugin is already installed; keep the script rerunnable.
openclaw plugins install "${TARGET_DIR}" >/dev/null 2>&1 || true
openclaw plugins enable vimalinx >/dev/null 2>&1 || true

server_url_default="${existing_server_url:-$DEFAULT_SERVER_URL}"
if [[ -t 0 ]]; then
  read -r -p "VimaClawNet Server URL [${server_url_default}]: " SERVER_URL
else
  SERVER_URL="${SERVER_URL:-$server_url_default}"
fi
SERVER_URL="${SERVER_URL:-$server_url_default}"
if [[ ! "${SERVER_URL}" =~ ^https?:// ]]; then
  SERVER_URL="https://${SERVER_URL}"
fi
SERVER_URL="${SERVER_URL%/}"

if [[ -t 0 ]]; then
  read -r -s -p "VimaClawNet token (leave blank to keep existing): " TOKEN
  echo
  if [[ -z "${TOKEN}" ]]; then
    TOKEN="${existing_token}" 
  fi
fi
TOKEN="${TOKEN:-$existing_token}"
TOKEN="$(printf "%s" "${TOKEN}" | tr -d '\r\n' | xargs)"
if [[ -z "${TOKEN}" ]]; then
  echo "Missing VimaClawNet token." >&2
  exit 1
fi

if [[ -n "${MACHINE_HEARTBEAT_MS}" ]] && ! [[ "${MACHINE_HEARTBEAT_MS}" =~ ^[0-9]+$ ]]; then
  echo "Invalid VIMALINX_MACHINE_HEARTBEAT_MS (use an integer)." >&2
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

user_id="$(python3 - "${login_response}" <<'PY'
import json
import sys
raw = sys.argv[1]
try:
  data = json.loads(raw)
except Exception:
  print("")
  raise SystemExit(0)
print(str(data.get("userId") or ""))
PY
)"
token_value="$(python3 - "${login_response}" <<'PY'
import json
import sys
raw = sys.argv[1]
try:
  data = json.loads(raw)
except Exception:
  print("")
  raise SystemExit(0)
print(str(data.get("token") or ""))
PY
)"

if [[ -z "${user_id}" || -z "${token_value}" ]]; then
  echo "Login failed: ${login_response}" >&2
  exit 1
fi

if is_true "${BACKUP_OPENCLAW_CONFIG}"; then
  backup_openclaw_config "${CONFIG_PATH}"
fi

if is_true "${REINSTALL_OPENCLAW_CONFIG}"; then
  config_dir="$(dirname "${CONFIG_PATH}")"
  mkdir -p "${config_dir}"
  rm -f "${CONFIG_PATH}"
  printf '{}\n' > "${CONFIG_PATH}"
  echo "Reinitialized OpenClaw config at: ${CONFIG_PATH}"
fi

export OPENCLAW_CONFIG_PATH="${CONFIG_PATH}"

openclaw config set channels.vimalinx.enabled true
openclaw config set channels.vimalinx.baseUrl "${SERVER_URL}"
openclaw config set channels.vimalinx.userId "${user_id}"
openclaw config set channels.vimalinx.token "${token_value}"
openclaw config set channels.vimalinx.inboundMode "${INBOUND_MODE}"
openclaw config set channels.vimalinx.dmPolicy "open"
openclaw config set channels.vimalinx.allowFrom '["*"]'

if [[ -n "${AUTO_REGISTER_MACHINE}" ]]; then
  openclaw config set channels.vimalinx.autoRegisterMachine "${AUTO_REGISTER_MACHINE}"
fi
if [[ -n "${MACHINE_HEARTBEAT_MS}" ]]; then
  openclaw config set channels.vimalinx.machineHeartbeatMs "${MACHINE_HEARTBEAT_MS}"
fi
if [[ -n "${MACHINE_ID}" ]]; then
  openclaw config set channels.vimalinx.machineId "${MACHINE_ID}"
fi
if [[ -n "${MACHINE_LABEL}" ]]; then
  openclaw config set channels.vimalinx.machineLabel "${MACHINE_LABEL}"
fi

openclaw config unset plugins.entries.vimalinx-server-plugin >/dev/null 2>&1 || true
openclaw config unset plugins.entries.test >/dev/null 2>&1 || true

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
  - VIMALINX_BACKUP_OPENCLAW_CONFIG=0
  - VIMALINX_REINSTALL_OPENCLAW_CONFIG=1
EOF
