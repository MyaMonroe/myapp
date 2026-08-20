# AKUJI Tool Bridge

This service is the controlled boundary between the AKUJI Android app and external/private tools such as Hermes Agent, GitHub, Yahoo Mail, Google services, video generators, and future MCP connectors.

## Security model

- No account passwords, API keys, OAuth client secrets, signing files, or long-lived provider tokens belong in the Android APK or Git repository.
- Every private bridge request requires `Authorization: Bearer <AKUJI_BRIDGE_TOKEN>`.
- The bridge starts in dry-run/research mode. External execution is disabled unless `AKUJI_BRIDGE_ALLOW_EXECUTION=true` is explicitly set on the bridge host.
- Harness credentials and endpoints are configured only through environment variables.
- Keep the bridge and Hermes behind HTTPS/authentication when exposed beyond localhost.
- Do not run Hermes' API server without `API_SERVER_KEY`; AKUJI maps that key to `AKUJI_HARNESS_TOKEN` on the bridge host.
- Consequential actions such as money movement, posting/sending as Mya, important deletion, password/security changes, legal/government submissions, and destructive device changes remain approval-gated even after more connectors are attached.

## Hermes mode

Set:

- `AKUJI_HARNESS_KIND=hermes`
- `AKUJI_HARNESS_BASE_URL=http://127.0.0.1:8642` (or the secured Hermes API-server URL)
- `AKUJI_HARNESS_TOKEN=<Hermes API_SERVER_KEY>`

AKUJI uses Hermes' asynchronous Runs API for long agent work so the mobile voice path does not have to block while Hermes is using tools.

## Current endpoints

- `GET /health` — minimal liveness check; reveals no private connector information.
- `GET /v1/status` — authenticated bridge/harness readiness state.
- `POST /v1/harness/task` — starts a harness task. In Hermes mode this creates a Hermes `/v1/runs` run and returns its run ID.
- `GET /v1/hermes/runs/{run_id}` — polls Hermes run state/output.
- `POST /v1/hermes/runs/{run_id}/stop` — requests a safe stop for an active Hermes run.

## Current boundary

This repository now knows how to speak to a configured Hermes API server, but it does not itself host Hermes or contain Hermes credentials. Hermes must still be deployed/configured on a host and connected through secure environment variables. Until that happens, AKUJI must report the harness as unavailable instead of pretending execution succeeded.

## Local development

1. Copy `.env.example` to `.env` and provide a long random bridge token.
2. Configure Hermes and set its `API_SERVER_KEY`.
3. Set the Hermes base URL and matching bridge harness token in `.env`.
4. Install dependencies from `requirements.txt`.
5. Export the environment variables from `.env` in the host environment.
6. Run `uvicorn app:app --host 127.0.0.1 --port 8787` from the `bridge` directory for local-only testing.
7. Keep `AKUJI_BRIDGE_ALLOW_EXECUTION=false` until dry-run, authentication, approval gates, and sandbox behavior are verified.
