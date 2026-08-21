#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

BRANCH="akuji-android-foundation"

echo "AKUJI direct bridge deploy"
echo "=========================="

# Keep the VM on the exact branch being tested from PR #2.
git fetch origin "$BRANCH"
git checkout "$BRANCH"
git pull --ff-only origin "$BRANCH"

cd deploy

# Preserve the existing bridge token if one already exists. Remove obsolete
# Hermes-only secrets so the live host no longer carries provider config AKUJI
# does not use.
if [[ -f .env ]]; then
  TMP_ENV="$(mktemp)"
  grep -Ev '^(HERMES_API_SERVER_KEY|AKUJI_HARNESS_KIND|AKUJI_HARNESS_BASE_URL|AKUJI_HARNESS_TOKEN|AKUJI_HARNESS_TASK_PATH)=' .env > "$TMP_ENV" || true
  if ! grep -q '^AKUJI_BRIDGE_TOKEN=' "$TMP_ENV"; then
    printf 'AKUJI_BRIDGE_TOKEN=%s\n' "$(openssl rand -hex 32)" >> "$TMP_ENV"
  fi
  if ! grep -q '^PICSART_API_KEY=' "$TMP_ENV"; then
    printf 'PICSART_API_KEY=\n' >> "$TMP_ENV"
  fi
  if grep -q '^AKUJI_BRIDGE_ALLOW_EXECUTION=' "$TMP_ENV"; then
    sed -i 's/^AKUJI_BRIDGE_ALLOW_EXECUTION=.*/AKUJI_BRIDGE_ALLOW_EXECUTION=false/' "$TMP_ENV"
  else
    printf 'AKUJI_BRIDGE_ALLOW_EXECUTION=false\n' >> "$TMP_ENV"
  fi
  mv "$TMP_ENV" .env
  chmod 600 .env
else
  cat > .env <<EOF
AKUJI_BRIDGE_TOKEN=$(openssl rand -hex 32)
PICSART_API_KEY=
AKUJI_BRIDGE_ALLOW_EXECUTION=false
EOF
  chmod 600 .env
fi

# Remove the old Hermes container even if it was started under an older compose
# project name, then deploy only the direct AKUJI bridge + HTTPS tunnel.
sudo docker rm -f akuji-hermes >/dev/null 2>&1 || true
sudo docker compose down --remove-orphans >/dev/null 2>&1 || true
sudo docker compose build bridge
sudo docker compose pull tunnel
sudo docker compose up -d --remove-orphans

# Verify the local bridge.
LOCAL_HEALTH="$(curl --fail --silent --show-error http://127.0.0.1:8787/health)"
if [[ "$LOCAL_HEALTH" != *'"status":"ok"'* ]]; then
  echo "Local bridge health check failed: $LOCAL_HEALTH"
  exit 1
fi

# Wait for the outbound Cloudflare Quick Tunnel URL.
URL=""
for _ in $(seq 1 40); do
  URL="$(sudo docker logs akuji-tunnel 2>&1 | grep -Eo 'https://[-a-z0-9]+\.trycloudflare\.com' | tail -n 1 || true)"
  [[ -n "$URL" ]] && break
  sleep 1
done

if [[ -z "$URL" ]]; then
  echo "Bridge is healthy locally, but the HTTPS tunnel URL did not appear."
  sudo docker logs akuji-tunnel --tail 80 || true
  exit 1
fi

TOKEN="$(awk -F= '$1 == "AKUJI_BRIDGE_TOKEN" {sub(/^[^=]*=/, ""); print; exit}' .env)"
if [[ -z "$TOKEN" ]]; then
  echo "AKUJI_BRIDGE_TOKEN is missing after deployment."
  exit 1
fi

# Verify the authenticated status endpoint through the public HTTPS tunnel.
STATUS_JSON="$(curl --fail --silent --show-error -H "Authorization: Bearer $TOKEN" "$URL/v1/status")"
if [[ "$STATUS_JSON" != *'"operator_mode":"direct"'* ]]; then
  echo "Public authenticated bridge verification failed: $STATUS_JSON"
  exit 1
fi

cat <<EOF

AKUJI DIRECT BRIDGE IS LIVE
===========================
HTTPS bridge address:
$URL

Bridge token:
$TOKEN

Verified:
- local bridge health: OK
- public HTTPS tunnel: OK
- authenticated operator mode: direct
- execution: OFF
- Hermes: removed

On the phone: AKUJI > OPERATOR > paste the bridge address and token > SAVE + TEST.
Do not paste the token into chat or any public place.
EOF
