#!/usr/bin/env bash
set -euo pipefail

SERVER_URL="${TEST_SERVER_URL:-}"
USER_ID="${TEST_USER_ID:-}"
DISPLAY_NAME="${TEST_DISPLAY_NAME:-}"
PASSWORD="${TEST_PASSWORD:-}"
SERVER_TOKEN="${TEST_SERVER_TOKEN:-}"
CONFIG_PATH="${TEST_CONFIG_PATH:-$HOME/.clawdbot/clawdbot.json}"

usage() {
  cat <<'EOF'
Usage:
  register-local.sh --server http://host:8788 --password <pwd> [--user alice] [--name Alice] [--server-token <token>] [--config <path>]

Environment:
  TEST_SERVER_URL, TEST_USER_ID, TEST_DISPLAY_NAME, TEST_PASSWORD, TEST_SERVER_TOKEN, TEST_CONFIG_PATH
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --server)
      SERVER_URL="${2:-}"
      shift 2
      ;;
    --user)
      USER_ID="${2:-}"
      shift 2
      ;;
    --name)
      DISPLAY_NAME="${2:-}"
      shift 2
      ;;
    --password)
      PASSWORD="${2:-}"
      shift 2
      ;;
    --server-token)
      SERVER_TOKEN="${2:-}"
      shift 2
      ;;
    --config)
      CONFIG_PATH="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown arg: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "$SERVER_URL" || -z "$PASSWORD" ]]; then
  echo "Missing --server or --password." >&2
  usage
  exit 1
fi

SERVER_URL="${SERVER_URL%/}"

payload="$(python3 - <<'PY' "$USER_ID" "$DISPLAY_NAME" "$PASSWORD"
import json
import sys

user_id, display_name, password = sys.argv[1:4]
payload = {"password": password}
if user_id:
  payload["userId"] = user_id
if display_name:
  payload["displayName"] = display_name
print(json.dumps(payload))
PY
)"

auth_header=()
if [[ -n "$SERVER_TOKEN" ]]; then
  auth_header=(-H "Authorization: Bearer ${SERVER_TOKEN}")
fi

response="$(curl -sS -X POST "${SERVER_URL}/api/register" \
  -H "Content-Type: application/json" \
  "${auth_header[@]}" \
  -d "${payload}")"

python3 - "$CONFIG_PATH" "$SERVER_URL" "$response" <<'PY'
import json
import os
import sys

config_path = sys.argv[1]
server_url = sys.argv[2]
raw = sys.argv[3]

try:
  data = json.loads(raw)
except json.JSONDecodeError as exc:
  raise SystemExit(f"Registration failed: {exc}")

if not data.get("ok") or not data.get("userId") or not data.get("token"):
  raise SystemExit(f"Registration failed: {data.get('error', raw)}")

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

test_cfg = channels.get("test")
if not isinstance(test_cfg, dict):
  test_cfg = {}

test_cfg.update({
  "enabled": True,
  "baseUrl": server_url,
  "token": token,
  "userId": user_id,
  "inboundMode": "poll",
  "dmPolicy": "open",
  "allowFrom": ["*"],
})

channels["test"] = test_cfg
config["channels"] = channels

plugins = config.get("plugins")
if not isinstance(plugins, dict):
  plugins = {}
entries = plugins.get("entries")
if not isinstance(entries, dict):
  entries = {}
test_entry = entries.get("test")
if not isinstance(test_entry, dict):
  test_entry = {}
test_entry["enabled"] = True
entries["test"] = test_entry
plugins["entries"] = entries
config["plugins"] = plugins

os.makedirs(os.path.dirname(config_path), exist_ok=True)
with open(config_path, "w", encoding="utf-8") as f:
  json.dump(config, f, indent=2, ensure_ascii=True)
  f.write("\n")

print("Registered user:", user_id)
print("Updated config:", config_path)
print("Restart: clawdbot gateway restart")
PY
