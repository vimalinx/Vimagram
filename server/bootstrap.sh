#!/usr/bin/env bash
set -euo pipefail

if ! command -v node >/dev/null 2>&1; then
  echo "Node.js not found. Please install Node.js 22+ and re-run."
  exit 1
fi

node --version

node server/setup.mjs --defaults

set -a
source .env
set +a

node server/server.mjs
