#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
One-click deploy for a local OpenClaw node.

Usage:
  bash scripts/deploy-openclaw-node.sh --server-url <url> [options]

Options:
  --server-url <url>             VimaClawNet server URL (required)
  --repo-url <url>               Git repository URL
  --repo-dir <path>              Local repository directory
  --target-user <user>           User for OpenClaw config (~/.openclaw)
  --user-id <id>                 VimaClawNet user id
  --password <pwd>               VimaClawNet account password
  --token <token>                Existing VimaClawNet host token
  --display-name <name>          Register display name
  --invite-code <code>           Invite code for registration
  --server-token <token>         Server token for restricted registration
  --machine-id <id>              Bind a fixed machine id
  --machine-label <label>        Bind a machine display label
  --inbound-mode <poll|webhook>  Channel inbound mode (default: poll)
  --auto-register <bool>         Auto register when login missing (default: true)
  --install-openclaw <bool>      Install openclaw if missing (default: true)
  --backup-config <bool>         Backup openclaw config before install (default: true)
  --reinstall-config <bool>      Reinitialize openclaw config before apply (default: false)
  --home-install-script <path>   Write interactive launcher (default: ~/install.sh)

Environment aliases:
  VIMALINX_SERVER_URL, VIMALINX_REPO, VIMALINX_REPO_DIR, VIMALINX_TARGET_USER,
  VIMALINX_USER_ID, VIMALINX_PASSWORD, VIMALINX_TOKEN, VIMALINX_DISPLAY_NAME,
  VIMALINX_INVITE_CODE, VIMALINX_SERVER_TOKEN, VIMALINX_MACHINE_ID,
  VIMALINX_MACHINE_LABEL, VIMALINX_INBOUND_MODE,
  VIMALINX_AUTO_REGISTER, VIMALINX_INSTALL_OPENCLAW,
  VIMALINX_BACKUP_OPENCLAW_CONFIG, VIMALINX_REINSTALL_OPENCLAW_CONFIG,
  VIMALINX_HOME_INSTALL_SCRIPT
EOF
}

is_true() {
  case "${1,,}" in
    1|y|yes|true|on) return 0 ;;
    *) return 1 ;;
  esac
}

run_root() {
  if [[ "${EUID}" -eq 0 ]]; then
    "$@"
  else
    sudo "$@"
  fi
}

run_as_target() {
  local user="$1"
  local home_dir="$2"
  shift 2
  if [[ "$(id -un)" == "${user}" ]]; then
    env HOME="${home_dir}" "$@"
  else
    sudo -u "${user}" -H env HOME="${home_dir}" "$@"
  fi
}

ensure_base_deps() {
  run_root apt-get update -y
  run_root apt-get install -y curl git ca-certificates python3 openssl
}

ensure_node22() {
  if ! command -v node >/dev/null 2>&1; then
    curl -fsSL https://deb.nodesource.com/setup_22.x | run_root bash -
    run_root apt-get install -y nodejs
    return
  fi
  local major
  major="$(node -v | sed -E 's/^v([0-9]+).*/\1/')"
  if [[ -z "${major}" || "${major}" -lt 22 ]]; then
    curl -fsSL https://deb.nodesource.com/setup_22.x | run_root bash -
    run_root apt-get install -y nodejs
  fi
}

normalize_url() {
  local raw="$1"
  local value="${raw%/}"
  if [[ ! "${value}" =~ ^https?:// ]]; then
    value="http://${value}"
  fi
  printf '%s' "${value}"
}

random_user_id() {
  printf 'node_%s' "$(openssl rand -hex 5)"
}

extract_json_field() {
  python3 - "$1" "$2" <<'PY'
import json
import sys

raw = sys.argv[1]
key = sys.argv[2]
try:
  data = json.loads(raw)
except json.JSONDecodeError:
  print("")
  raise SystemExit(0)
value = data.get(key)
if value is None:
  print("")
else:
  print(str(value))
PY
}

write_home_install_launcher() {
  local launcher_path="$1"
  local repo_dir="$2"
  local target_user="$3"

  run_root mkdir -p "$(dirname "${launcher_path}")"
  run_root tee "${launcher_path}" >/dev/null <<EOF
#!/usr/bin/env bash
set -euo pipefail

REPO_DIR="${repo_dir}"
INSTALLER="\${REPO_DIR}/scripts/deploy-openclaw-node-interactive.sh"

if [[ ! -f "\${INSTALLER}" ]]; then
  echo "Missing installer script: \${INSTALLER}" >&2
  echo "Run the bootstrap deploy command again." >&2
  exit 1
fi

exec bash "\${INSTALLER}" "\$@"
EOF
  run_root chmod 755 "${launcher_path}"
  run_root chown "${target_user}:${target_user}" "${launcher_path}" >/dev/null 2>&1 || true
}

SERVER_URL="${VIMALINX_SERVER_URL:-http://49.235.88.239:18788}"
REPO_URL="${VIMALINX_REPO:-https://github.com/vimalinx/ClawNet.git}"
REPO_DIR="${VIMALINX_REPO_DIR:-}"
TARGET_USER="${VIMALINX_TARGET_USER:-}"
USER_ID="${VIMALINX_USER_ID:-}"
PASSWORD="${VIMALINX_PASSWORD:-}"
TOKEN="${VIMALINX_TOKEN:-}"
DISPLAY_NAME="${VIMALINX_DISPLAY_NAME:-}"
INVITE_CODE="${VIMALINX_INVITE_CODE:-}"
SERVER_TOKEN="${VIMALINX_SERVER_TOKEN:-}"
MACHINE_ID="${VIMALINX_MACHINE_ID:-}"
MACHINE_LABEL="${VIMALINX_MACHINE_LABEL:-}"
INBOUND_MODE="${VIMALINX_INBOUND_MODE:-poll}"
AUTO_REGISTER="${VIMALINX_AUTO_REGISTER:-true}"
INSTALL_OPENCLAW="${VIMALINX_INSTALL_OPENCLAW:-true}"
RUN_OPENCLAW_ONBOARD="${VIMALINX_RUN_OPENCLAW_ONBOARD:-true}"
OPENCLAW_GATEWAY_BIND="${VIMALINX_OPENCLAW_GATEWAY_BIND:-loopback}"
HOME_INSTALL_SCRIPT="${VIMALINX_HOME_INSTALL_SCRIPT:-}"
BACKUP_OPENCLAW_CONFIG="${VIMALINX_BACKUP_OPENCLAW_CONFIG:-true}"
REINSTALL_OPENCLAW_CONFIG="${VIMALINX_REINSTALL_OPENCLAW_CONFIG:-false}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --server-url) SERVER_URL="${2:-}"; shift 2 ;;
    --repo-url) REPO_URL="${2:-}"; shift 2 ;;
    --repo-dir) REPO_DIR="${2:-}"; shift 2 ;;
    --target-user) TARGET_USER="${2:-}"; shift 2 ;;
    --user-id) USER_ID="${2:-}"; shift 2 ;;
    --password) PASSWORD="${2:-}"; shift 2 ;;
    --token) TOKEN="${2:-}"; shift 2 ;;
    --display-name) DISPLAY_NAME="${2:-}"; shift 2 ;;
    --invite-code) INVITE_CODE="${2:-}"; shift 2 ;;
    --server-token) SERVER_TOKEN="${2:-}"; shift 2 ;;
    --machine-id) MACHINE_ID="${2:-}"; shift 2 ;;
    --machine-label) MACHINE_LABEL="${2:-}"; shift 2 ;;
    --inbound-mode) INBOUND_MODE="${2:-}"; shift 2 ;;
    --auto-register) AUTO_REGISTER="${2:-}"; shift 2 ;;
    --install-openclaw) INSTALL_OPENCLAW="${2:-}"; shift 2 ;;
    --backup-config) BACKUP_OPENCLAW_CONFIG="${2:-}"; shift 2 ;;
    --reinstall-config) REINSTALL_OPENCLAW_CONFIG="${2:-}"; shift 2 ;;
    --home-install-script) HOME_INSTALL_SCRIPT="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "${SERVER_URL}" ]]; then
  echo "Missing --server-url" >&2
  usage
  exit 1
fi

if [[ "${INBOUND_MODE}" != "poll" && "${INBOUND_MODE}" != "webhook" ]]; then
  echo "Invalid --inbound-mode. Use poll or webhook." >&2
  exit 1
fi

if [[ -z "${TARGET_USER}" ]]; then
  if [[ -n "${SUDO_USER:-}" && "${SUDO_USER}" != "root" ]]; then
    TARGET_USER="${SUDO_USER}"
  else
    TARGET_USER="$(id -un)"
  fi
fi

if ! id -u "${TARGET_USER}" >/dev/null 2>&1; then
  echo "Target user not found: ${TARGET_USER}" >&2
  exit 1
fi

TARGET_HOME="$(getent passwd "${TARGET_USER}" | cut -d: -f6)"
if [[ -z "${TARGET_HOME}" ]]; then
  echo "Cannot resolve home for ${TARGET_USER}" >&2
  exit 1
fi

OPENCLAW_CONFIG_PATH="${OPENCLAW_CONFIG_PATH:-${OPENCLAW_CONFIG:-${CLAWDBOT_CONFIG_PATH:-${TARGET_HOME}/.openclaw/openclaw.json}}}"

if [[ -z "${REPO_DIR}" ]]; then
  REPO_DIR="${TARGET_HOME}/vimalinx-suite-core"
fi
if [[ -z "${HOME_INSTALL_SCRIPT}" ]]; then
  HOME_INSTALL_SCRIPT="${TARGET_HOME}/install.sh"
fi

if is_true "${REINSTALL_OPENCLAW_CONFIG}" && ! is_true "${BACKUP_OPENCLAW_CONFIG}"; then
  echo "Reinstall requested; forcing config backup for safety."
  BACKUP_OPENCLAW_CONFIG="true"
fi

SERVER_URL="$(normalize_url "${SERVER_URL}")"

ensure_base_deps
ensure_node22

if is_true "${INSTALL_OPENCLAW}" && ! command -v openclaw >/dev/null 2>&1; then
  run_root npm install -g openclaw@latest
fi

if is_true "${RUN_OPENCLAW_ONBOARD}"; then
  run_as_target "${TARGET_USER}" "${TARGET_HOME}" \
    env OPENCLAW_CONFIG_PATH="${OPENCLAW_CONFIG_PATH}" \
      openclaw onboard \
        --mode local \
        --non-interactive \
        --accept-risk \
        --install-daemon \
        --skip-channels \
        --skip-skills \
        --skip-ui \
        --skip-health \
        --gateway-bind "${OPENCLAW_GATEWAY_BIND}" \
        --auth-choice skip \
        >/dev/null 2>&1
fi

if [[ -d "${REPO_DIR}/.git" ]]; then
  run_as_target "${TARGET_USER}" "${TARGET_HOME}" git -C "${REPO_DIR}" pull --ff-only
else
  run_as_target "${TARGET_USER}" "${TARGET_HOME}" git clone "${REPO_URL}" "${REPO_DIR}"
fi

if [[ -n "${TOKEN}" && -z "${USER_ID}" ]]; then
  login_with_token_payload="$(python3 - "${TOKEN}" <<'PY'
import json
import sys
print(json.dumps({"token": sys.argv[1]}))
PY
)"
  login_with_token_resp="$(curl -sS -X POST "${SERVER_URL}/api/login" -H "Content-Type: application/json" -d "${login_with_token_payload}" || true)"
  login_with_token_ok="$(extract_json_field "${login_with_token_resp}" "ok")"
  if [[ "${login_with_token_ok}" != "True" && "${login_with_token_ok}" != "true" && "${login_with_token_ok}" != "1" ]]; then
    echo "Token login failed: ${login_with_token_resp}" >&2
    exit 1
  fi
  USER_ID="$(extract_json_field "${login_with_token_resp}" "userId")"
  if [[ -z "${USER_ID}" ]]; then
    echo "Token login succeeded but userId is empty." >&2
    exit 1
  fi
fi

if [[ -z "${TOKEN}" ]]; then
  if [[ -z "${PASSWORD}" && -t 0 ]]; then
    read -r -s -p "VimaClawNet password: " PASSWORD
    echo
  fi
  if [[ -z "${PASSWORD}" ]]; then
    echo "Missing password. Provide --password or --token." >&2
    exit 1
  fi
  if [[ -z "${USER_ID}" ]]; then
    USER_ID="$(random_user_id)"
  fi

  login_payload="$(python3 - "${USER_ID}" "${PASSWORD}" <<'PY'
import json
import sys
print(json.dumps({"userId": sys.argv[1], "password": sys.argv[2]}))
PY
)"
  login_resp="$(curl -sS -X POST "${SERVER_URL}/api/account/login" -H "Content-Type: application/json" -d "${login_payload}" || true)"
  login_ok="$(extract_json_field "${login_resp}" "ok")"

  if [[ "${login_ok}" != "True" && "${login_ok}" != "true" && "${login_ok}" != "1" ]]; then
    if ! is_true "${AUTO_REGISTER}"; then
      echo "Login failed and auto-register disabled." >&2
      echo "Response: ${login_resp}" >&2
      exit 1
    fi
    register_payload="$(python3 - "${USER_ID}" "${DISPLAY_NAME}" "${PASSWORD}" "${INVITE_CODE}" "${SERVER_TOKEN}" <<'PY'
import json
import sys
user_id, display_name, password, invite_code, server_token = sys.argv[1:6]
payload = {"userId": user_id, "password": password}
if display_name:
    payload["displayName"] = display_name
if invite_code:
    payload["inviteCode"] = invite_code
if server_token:
    payload["serverToken"] = server_token
print(json.dumps(payload))
PY
)"
    register_resp="$(curl -sS -X POST "${SERVER_URL}/api/register" -H "Content-Type: application/json" -d "${register_payload}" || true)"
    register_ok="$(extract_json_field "${register_resp}" "ok")"
    if [[ "${register_ok}" != "True" && "${register_ok}" != "true" && "${register_ok}" != "1" ]]; then
      echo "Register failed: ${register_resp}" >&2
      exit 1
    fi
  fi

  token_payload="$(python3 - "${USER_ID}" "${PASSWORD}" <<'PY'
import json
import sys
print(json.dumps({"userId": sys.argv[1], "password": sys.argv[2]}))
PY
)"
  token_resp="$(curl -sS -X POST "${SERVER_URL}/api/token" -H "Content-Type: application/json" -d "${token_payload}")"
  token_ok="$(extract_json_field "${token_resp}" "ok")"
  if [[ "${token_ok}" != "True" && "${token_ok}" != "true" && "${token_ok}" != "1" ]]; then
    echo "Token request failed: ${token_resp}" >&2
    exit 1
  fi
  TOKEN="$(extract_json_field "${token_resp}" "token")"
  if [[ -z "${TOKEN}" ]]; then
    echo "Token request succeeded but token is empty." >&2
    exit 1
  fi
fi

run_as_target "${TARGET_USER}" "${TARGET_HOME}" \
  env OPENCLAW_CONFIG_PATH="${OPENCLAW_CONFIG_PATH}" \
    VIMALINX_SERVER_URL="${SERVER_URL}" \
    VIMALINX_TOKEN="${TOKEN}" \
    VIMALINX_INBOUND_MODE="${INBOUND_MODE}" \
    VIMALINX_MACHINE_ID="${MACHINE_ID}" \
    VIMALINX_MACHINE_LABEL="${MACHINE_LABEL}" \
    VIMALINX_BACKUP_OPENCLAW_CONFIG="${BACKUP_OPENCLAW_CONFIG}" \
    VIMALINX_REINSTALL_OPENCLAW_CONFIG="${REINSTALL_OPENCLAW_CONFIG}" \
    VIMALINX_FORCE_OVERWRITE=1 \
    bash "${REPO_DIR}/install.sh"

write_home_install_launcher "${HOME_INSTALL_SCRIPT}" "${REPO_DIR}" "${TARGET_USER}"

echo
echo "Done."
echo "- Target user: ${TARGET_USER}"
echo "- Server URL: ${SERVER_URL}"
echo "- User ID: ${USER_ID:-unknown}"
echo "- Machine ID: ${MACHINE_ID:-auto-by-plugin}"
echo "- OpenClaw plugin installed and configured"
echo "- Interactive installer: ${HOME_INSTALL_SCRIPT}"
echo "- Config backup before install: ${BACKUP_OPENCLAW_CONFIG}"
echo "- Config reinstall mode: ${REINSTALL_OPENCLAW_CONFIG}"
