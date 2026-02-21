#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Interactive installer for an OpenClaw client node.

Usage:
  bash scripts/deploy-openclaw-node-interactive.sh

Default flow is token-first quick setup:
  1) Input contributor token
  2) Auto connect to server and apply config

Advanced options are optional.
EOF
}

ask_with_default() {
  local prompt="$1"
  local default_value="$2"
  local answer=""
  read -r -p "${prompt} [${default_value}]: " answer
  if [[ -z "${answer}" ]]; then
    printf '%s\n' "${default_value}"
  else
    printf '%s\n' "${answer}"
  fi
}

ask_optional() {
  local prompt="$1"
  local answer=""
  read -r -p "${prompt}: " answer
  printf '%s\n' "${answer}"
}

ask_secret_required() {
  local prompt="$1"
  local answer=""
  while true; do
    read -r -s -p "${prompt}: " answer
    printf '\n'
    if [[ -n "${answer}" ]]; then
      printf '%s\n' "${answer}"
      return
    fi
    printf 'This field is required.\n'
  done
}

ask_yes_no() {
  local prompt="$1"
  local default_choice="$2"
  local answer=""
  while true; do
    read -r -p "${prompt} [${default_choice}]: " answer
    answer="${answer:-${default_choice}}"
    answer="${answer,,}"
    case "${answer}" in
      y|yes) printf 'true\n'; return ;;
      n|no) printf 'false\n'; return ;;
      *) printf 'Please answer y or n.\n' ;;
    esac
  done
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY_SCRIPT="${SCRIPT_DIR}/deploy-openclaw-node.sh"

if [[ ! -f "${DEPLOY_SCRIPT}" ]]; then
  echo "Missing script: ${DEPLOY_SCRIPT}" >&2
  exit 1
fi

if [[ -n "${SUDO_USER:-}" && "${SUDO_USER}" != "root" ]]; then
  DEFAULT_TARGET_USER="${SUDO_USER}"
else
  DEFAULT_TARGET_USER="$(id -un)"
fi

DEFAULT_SERVER_URL="${VIMALINX_SERVER_URL:-http://49.235.88.239:18788}"
DEFAULT_REPO_URL="${VIMALINX_REPO:-https://github.com/vimalinx/ClawNet.git}"
DEFAULT_INBOUND_MODE="${VIMALINX_INBOUND_MODE:-poll}"

echo
echo "== OpenClaw Client Node Interactive Installer =="
echo

CONTRIBUTOR_TOKEN="$(ask_secret_required "Contributor token")"
SERVER_URL="${DEFAULT_SERVER_URL}"
TARGET_USER="${DEFAULT_TARGET_USER}"
REPO_URL="${DEFAULT_REPO_URL}"
REPO_DIR=""
INBOUND_MODE="${DEFAULT_INBOUND_MODE}"
MACHINE_ID=""
MACHINE_LABEL=""
INSTALL_OPENCLAW="true"
BACKUP_CONFIG="true"
REINSTALL_CONFIG="false"

ADVANCED="$(ask_yes_no "Configure advanced options" "N")"
if [[ "${ADVANCED}" == "true" ]]; then
  SERVER_URL="$(ask_with_default "Server URL" "${SERVER_URL}")"
  TARGET_USER="$(ask_with_default "Target Linux user" "${TARGET_USER}")"
  REPO_URL="$(ask_with_default "Repository URL" "${REPO_URL}")"
  REPO_DIR="$(ask_optional "Repository directory (optional)")"
  INBOUND_MODE="$(ask_with_default "Inbound mode (poll/webhook)" "${INBOUND_MODE}")"
  MACHINE_ID="$(ask_optional "Machine ID (optional)")"
  MACHINE_LABEL="$(ask_optional "Machine label (optional)")"
  INSTALL_OPENCLAW="$(ask_yes_no "Install OpenClaw CLI if missing" "Y")"
  BACKUP_CONFIG="$(ask_yes_no "Backup OpenClaw config before install" "Y")"
  REINSTALL_CONFIG="$(ask_yes_no "Reinstall OpenClaw config (reset then apply)" "N")"
fi

echo
echo "Install summary"
echo "- Server URL: ${SERVER_URL}"
echo "- Contributor token: [set]"
echo "- Target user: ${TARGET_USER}"
echo "- Repo URL: ${REPO_URL}"
if [[ -n "${REPO_DIR}" ]]; then
  echo "- Repo dir: ${REPO_DIR}"
fi
echo "- Inbound mode: ${INBOUND_MODE}"
if [[ -n "${MACHINE_ID}" ]]; then
  echo "- Machine ID: ${MACHINE_ID}"
fi
if [[ -n "${MACHINE_LABEL}" ]]; then
  echo "- Machine label: ${MACHINE_LABEL}"
fi
echo "- Install OpenClaw if missing: ${INSTALL_OPENCLAW}"
echo "- Backup OpenClaw config: ${BACKUP_CONFIG}"
echo "- Reinstall OpenClaw config: ${REINSTALL_CONFIG}"

PROCEED="$(ask_yes_no "Run deployment now" "Y")"
if [[ "${PROCEED}" != "true" ]]; then
  echo "Cancelled."
  exit 0
fi

cmd=(
  bash
  "${DEPLOY_SCRIPT}"
  --server-url "${SERVER_URL}"
  --token "${CONTRIBUTOR_TOKEN}"
  --target-user "${TARGET_USER}"
  --repo-url "${REPO_URL}"
  --inbound-mode "${INBOUND_MODE}"
  --backup-config "${BACKUP_CONFIG}"
  --reinstall-config "${REINSTALL_CONFIG}"
)

if [[ -n "${REPO_DIR}" ]]; then
  cmd+=(--repo-dir "${REPO_DIR}")
fi
if [[ -n "${MACHINE_ID}" ]]; then
  cmd+=(--machine-id "${MACHINE_ID}")
fi
if [[ -n "${MACHINE_LABEL}" ]]; then
  cmd+=(--machine-label "${MACHINE_LABEL}")
fi
if [[ "${INSTALL_OPENCLAW}" != "true" ]]; then
  cmd+=(--install-openclaw false)
fi

"${cmd[@]}"

echo
echo "Interactive OpenClaw client node deployment finished."
