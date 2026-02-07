#!/usr/bin/env bash
set -euo pipefail

if [[ "${EUID}" -ne 0 ]]; then
  echo "Run this script as root." >&2
  exit 1
fi

MODE="${VIMALINX_MODE:-}"
if [[ -z "${MODE}" ]]; then
  echo "Select setup mode:"
  echo "  1) quick (recommended)"
  echo "  2) manual"
  read -r -p "Mode [1/2]: " MODE
fi

if [[ "${MODE}" == "1" ]]; then
  MODE="quick"
elif [[ "${MODE}" == "2" ]]; then
  MODE="manual"
fi

if [[ "${MODE}" != "quick" && "${MODE}" != "manual" ]]; then
  echo "Invalid mode. Use VIMALINX_MODE=quick|manual or select 1/2." >&2
  exit 1
fi

GIT_REPO="${VIMALINX_REPO:-https://github.com/vimalinx/vimalinx-suite-core}"
GIT_REPO_GITEE="${VIMALINX_REPO_GITEE:-https://gitee.com/mirrors/vimalinx-suite-core}"
INSTALL_DIR="${VIMALINX_INSTALL_DIR:-/opt/vimalinx-suite-core}"
GIT_TIMEOUT="${VIMALINX_GIT_TIMEOUT:-120}"
DATA_DIR="${VIMALINX_DATA_DIR:-/var/lib/vimalinx}"
USERS_FILE="${VIMALINX_USERS_FILE:-${DATA_DIR}/users.json}"
PORT="${VIMALINX_PORT:-8788}"
ALLOW_REGISTRATION="${VIMALINX_ALLOW_REGISTRATION:-true}"
INBOUND_MODE="${VIMALINX_INBOUND_MODE:-poll}"
SERVER_TOKEN="${VIMALINX_SERVER_TOKEN:-}"
HMAC_SECRET="${VIMALINX_HMAC_SECRET:-}"
REQUIRE_SIGNATURE="${VIMALINX_REQUIRE_SIGNATURE:-}"
INVITE_CODES="${VIMALINX_INVITE_CODES:-}"
INVITE_COUNT="${VIMALINX_INVITE_COUNT:-5}"
INVITE_LEN="${VIMALINX_INVITE_LEN:-6}"

normalize_bool() {
  case "${1,,}" in
    y|yes|true|1) echo "true";;
    n|no|false|0) echo "false";;
    *) echo "";;
  esac
}

generate_invites() {
  local count="$1"
  local len="$2"
  local out=()
  local i=0
  while [[ "${i}" -lt "${count}" ]]; do
    local code
    code="$(openssl rand -hex 8 | cut -c1-${len})"
    out+=("vimalinx-${code}")
    i=$((i + 1))
  done
  IFS=,; echo "${out[*]}"; unset IFS
}

if [[ "${MODE}" == "quick" ]]; then
  read -r -p "Server port [${PORT}]: " input_port
  PORT="${input_port:-${PORT}}"
  read -r -p "Allow registration? (y/N) [${ALLOW_REGISTRATION}]: " input_allow
  if [[ -n "${input_allow}" ]]; then
    ALLOW_REGISTRATION="$(normalize_bool "${input_allow}")"
  fi
  if [[ "${ALLOW_REGISTRATION}" == "true" ]]; then
    read -r -p "Enable invite codes? (y/N): " input_invite
    if [[ "$(normalize_bool "${input_invite}")" == "true" ]]; then
      read -r -p "Invite code count [${INVITE_COUNT}]: " input_count
      INVITE_COUNT="${input_count:-${INVITE_COUNT}}"
      read -r -p "Invite code length [${INVITE_LEN}]: " input_len
      INVITE_LEN="${input_len:-${INVITE_LEN}}"
      INVITE_CODES="$(generate_invites "${INVITE_COUNT}" "${INVITE_LEN}")"
      echo "Generated invite codes: ${INVITE_CODES}"
    fi
  fi
else
  read -r -p "Git repo [${GIT_REPO}]: " input_repo
  GIT_REPO="${input_repo:-${GIT_REPO}}"
  read -r -p "Install dir [${INSTALL_DIR}]: " input_dir
  INSTALL_DIR="${input_dir:-${INSTALL_DIR}}"
  read -r -p "Data dir [${DATA_DIR}]: " input_data
  DATA_DIR="${input_data:-${DATA_DIR}}"
  read -r -p "Users file [${USERS_FILE}]: " input_users
  USERS_FILE="${input_users:-${USERS_FILE}}"
  read -r -p "Server port [${PORT}]: " input_port
  PORT="${input_port:-${PORT}}"
  read -r -p "Inbound mode (poll/webhook) [${INBOUND_MODE}]: " input_mode
  INBOUND_MODE="${input_mode:-${INBOUND_MODE}}"
  read -r -p "Allow registration? (y/N) [${ALLOW_REGISTRATION}]: " input_allow
  if [[ -n "${input_allow}" ]]; then
    ALLOW_REGISTRATION="$(normalize_bool "${input_allow}")"
  fi
  read -r -p "Enable invite codes? (y/N): " input_invite
  if [[ "$(normalize_bool "${input_invite}")" == "true" ]]; then
    read -r -p "Invite code count [${INVITE_COUNT}]: " input_count
    INVITE_COUNT="${input_count:-${INVITE_COUNT}}"
    read -r -p "Invite code length [${INVITE_LEN}]: " input_len
    INVITE_LEN="${input_len:-${INVITE_LEN}}"
    INVITE_CODES="$(generate_invites "${INVITE_COUNT}" "${INVITE_LEN}")"
    echo "Generated invite codes: ${INVITE_CODES}"
  fi
  read -r -p "Server token (optional): " SERVER_TOKEN
  read -r -p "HMAC secret (optional): " HMAC_SECRET
  read -r -p "Require signature? (y/N): " input_sig
  if [[ -n "${input_sig}" ]]; then
    REQUIRE_SIGNATURE="$(normalize_bool "${input_sig}")"
  fi
fi

apt-get update -y
apt-get install -y curl git ca-certificates
if ! command -v openssl >/dev/null 2>&1; then
  apt-get install -y openssl
fi

if ! command -v node >/dev/null 2>&1; then
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
  apt-get install -y nodejs
else
  NODE_MAJOR="$(node -v | tr -d 'v' | cut -d. -f1)"
  if [[ "${NODE_MAJOR}" -lt 22 ]]; then
    curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
    apt-get install -y nodejs
  fi
fi

git config --global http.lowSpeedLimit 0
git config --global http.lowSpeedTime ${GIT_TIMEOUT}
git config --global http.postBuffer 1048576000

if [[ -d "${INSTALL_DIR}/.git" ]]; then
  echo "Updating existing installation at ${INSTALL_DIR}..."
  timeout ${GIT_TIMEOUT} git -C "${INSTALL_DIR}" pull --ff-only || \
  timeout ${GIT_TIMEOUT} git -C "${INSTALL_DIR}" fetch origin || true
else
  echo "Cloning from ${GIT_REPO}..."
  timeout ${GIT_TIMEOUT} git clone --depth 1 "${GIT_REPO}" "${INSTALL_DIR}" || \
  { echo "Primary repo failed. Trying Gitee mirror..." >&2; \
    timeout ${GIT_TIMEOUT} git clone --depth 1 "${GIT_REPO_GITEE}" "${INSTALL_DIR}" || \
    { echo "Error: All clone attempts failed. Check network or manually clone." >&2; \
      echo "Manually clone and set VIMALINX_INSTALL_DIR to your directory." >&2; exit 1; }; }
fi

mkdir -p "${DATA_DIR}"
if [[ ! -f "${USERS_FILE}" ]]; then
  cp "${INSTALL_DIR}/server/users.example.json" "${USERS_FILE}"
fi

cat >/etc/vimalinx-server.env <<EOF
TEST_SERVER_PORT=${PORT}
TEST_USERS_FILE=${USERS_FILE}
TEST_USERS_WRITE_FILE=${USERS_FILE}
TEST_ALLOW_REGISTRATION=${ALLOW_REGISTRATION}
TEST_INVITE_CODES=${INVITE_CODES}
TEST_INBOUND_MODE=${INBOUND_MODE}
TEST_SERVER_TOKEN=${SERVER_TOKEN}
TEST_HMAC_SECRET=${HMAC_SECRET}
TEST_REQUIRE_SIGNATURE=${REQUIRE_SIGNATURE}
EOF

cat >/etc/systemd/system/vimalinx-server.service <<EOF
[Unit]
Description=Vimalinx Server
After=network.target

[Service]
Type=simple
EnvironmentFile=/etc/vimalinx-server.env
WorkingDirectory=${INSTALL_DIR}
ExecStart=/usr/bin/node ${INSTALL_DIR}/server/server.mjs
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now vimalinx-server
systemctl status --no-pager vimalinx-server

echo "Done. Server URL: http://<your-server-ip>:${PORT}"
