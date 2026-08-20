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

# e2-micro has 1 GB RAM. Add swap so image pulls/builds do not die under brief memory pressure.
if ! swapon --show | grep -q .; then
  echo "Creating 2 GB swap..."
  $SUDO fallocate -l 2G /swapfile
  $SUDO chmod 600 /swapfile
  $SUDO mkswap /swapfile >/dev/null
  $SUDO swapon /swapfile
  if ! grep -q '^/swapfile ' /etc/fstab; then
    echo '/swapfile none swap sw 0 0' | $SUDO tee -a /etc/fstab >/dev/null
  fi
fi

mkdir -p hermes-data
if [[ ! -f hermes-data/config.yaml ]]; then
  cp hermes-config.yaml hermes-data/config.yaml
fi
chmod 700 hermes-data
chmod 600 hermes-data/config.yaml

if [[ ! -f .env ]]; then
  BRIDGE_TOKEN="$(openssl rand -hex 32)"
  HERMES_KEY="$(openssl rand -hex 32)"
  printf 'Paste the NEW rotated Picsart developer key (it will not echo): '
  IFS= read -r -s PICSART_KEY
  printf '\n'
  if [[ -z "$PICSART_KEY" ]]; then
    echo "Picsart key was empty. Nothing was started."
    exit 1
  fi

  cat > .env <<EOF
AKUJI_BRIDGE_TOKEN=${BRIDGE_TOKEN}
HERMES_API_SERVER_KEY=${HERMES_KEY}
PICSART_API_KEY=${PICSART_KEY}
AKUJI_BRIDGE_ALLOW_EXECUTION=false
EOF
  chmod 600 .env
else
  echo "Existing deploy/.env found; leaving it unchanged."
fi

$SUDO docker compose pull hermes
$SUDO docker compose build bridge
$SUDO docker compose up -d

printf '\nAKUJI backend containers:\n'
$SUDO docker compose ps
printf '\nBridge local health:\n'
curl --fail --silent --show-error http://127.0.0.1:8787/health || true
printf '\n\nNext: authenticate MiniMax OAuth inside Hermes with:\n'
printf 'sudo docker exec -it akuji-hermes hermes auth add minimax-oauth --no-browser\n'
printf '\nExecution remains OFF until dry-run tests pass.\n'
