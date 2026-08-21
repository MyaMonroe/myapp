#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

if [[ "$(uname -s)" != "Linux" ]]; then
  echo "This bootstrap is for the AKUJI Linux VM."
  exit 1
fi

SUDO=""
if [[ "${EUID}" -ne 0 ]]; then
  SUDO="sudo"
fi

export DEBIAN_FRONTEND=noninteractive
$SUDO apt-get update
$SUDO apt-get install -y ca-certificates curl git openssl docker.io docker-compose-plugin
$SUDO systemctl enable --now docker

if ! swapon --show 2>/dev/null | grep -q .; then
  echo "Creating 2 GB swap..."
  $SUDO fallocate -l 2G /swapfile
  $SUDO chmod 600 /swapfile
  $SUDO mkswap /swapfile >/dev/null
  $SUDO swapon /swapfile
  if ! grep -q '^/swapfile ' /etc/fstab; then
    echo '/swapfile none swap sw 0 0' | $SUDO tee -a /etc/fstab >/dev/null
  fi
fi

if [[ ! -f .env ]]; then
  BRIDGE_TOKEN="$(openssl rand -hex 32)"
  cat > .env <<EOF
AKUJI_BRIDGE_TOKEN=${BRIDGE_TOKEN}
PICSART_API_KEY=
AKUJI_BRIDGE_ALLOW_EXECUTION=false
EOF
  chmod 600 .env
  echo "Created deploy/.env with a new bridge token. Picsart is intentionally disconnected."
else
  echo "Existing deploy/.env found; keeping the existing bridge token and settings."
fi

$SUDO docker compose build bridge
$SUDO docker compose pull tunnel
$SUDO docker compose up -d --remove-orphans

printf '\nAKUJI backend containers:\n'
$SUDO docker compose ps
printf '\nBridge local health:\n'
curl --fail --silent --show-error http://127.0.0.1:8787/health || true
printf '\n\nAKUJI now uses Gemini Live as the AI layer and this bridge only as a direct tool gateway.\n'
printf 'No Hermes, MiniMax, Qwen, or second model-provider login is required by this deployment.\n'
printf 'Execution remains OFF until a specific external tool action is deliberately enabled.\n'
