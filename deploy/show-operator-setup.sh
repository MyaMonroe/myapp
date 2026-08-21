#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

if [ ! -f .env ]; then
  echo "AKUJI deploy .env was not found in $(pwd)."
  exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is not installed on this host."
  exit 1
fi

docker compose up -d bridge tunnel >/dev/null

URL=""
for _ in $(seq 1 30); do
  URL="$(docker logs akuji-tunnel 2>&1 | grep -Eo 'https://[-a-z0-9]+\.trycloudflare\.com' | tail -n 1 || true)"
  if [ -n "$URL" ]; then
    break
  fi
  sleep 1
done

if [ -z "$URL" ]; then
  echo "The AKUJI tunnel did not publish an HTTPS address yet."
  echo "Run: docker logs akuji-tunnel --tail 80"
  exit 1
fi

TOKEN="$(awk -F= '$1 == "AKUJI_BRIDGE_TOKEN" {sub(/^[^=]*=/, ""); print; exit}' .env)"
if [ -z "$TOKEN" ]; then
  echo "AKUJI_BRIDGE_TOKEN is empty in .env."
  exit 1
fi

cat <<EOF

AKUJI OPERATOR SETUP
====================
HTTPS bridge address:
$URL

Bridge token:
$TOKEN

On the phone: AKUJI > OPERATOR > paste these two values > SAVE + TEST.
Do not paste the bridge token into chat, email, or a public issue.
EOF
