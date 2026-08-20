# AKUJI Tool Bridge

This service is the controlled boundary between the AKUJI Android app and external/private tools such as the Qwen sandbox, GitHub, Yahoo Mail, Google services, video generators, and future MCP connectors.

## Security model

- No account passwords, API keys, OAuth client secrets, signing files, or long-lived provider tokens belong in the Android APK or Git repository.
- Every private bridge request requires `Authorization: Bearer <AKUJI_BRIDGE_TOKEN>`.
- The bridge starts in dry-run/research mode. External execution is disabled unless `AKUJI_BRIDGE_ALLOW_EXECUTION=true` is explicitly set on the bridge host.
- The sandbox/harness endpoint is configured only through environment variables.
- Keep the bridge behind HTTPS and do not expose an unauthenticated Qwen daemon to the public internet.
- Consequential actions such as money movement, posting/sending as Mya, important deletion, password/security changes, legal/government submissions, and destructive device changes must remain approval-gated even after the bridge gains additional connectors.

## Current endpoints

- `GET /health` — minimal liveness check; reveals no private connector information.
- `GET /v1/status` — authenticated bridge/harness readiness state.
- `POST /v1/harness/task` — authenticated sandbox task relay. Dry-run is allowed by default; execution requires the explicit server-side execution flag.

## Not connected yet

This foundation does not itself create a hosted server, Qwen daemon, Yahoo session, GitHub token, or OAuth connection. Those must be attached to the deployed bridge through secure environment variables or provider OAuth flows. Until that happens, AKUJI must report the connector as unavailable instead of pretending the action succeeded.

## Local development

1. Copy `.env.example` to `.env` and provide a long random bridge token.
2. Install dependencies from `requirements.txt`.
3. Export the environment variables from `.env` in the host environment.
4. Run `uvicorn app:app --host 127.0.0.1 --port 8787` from the `bridge` directory for local-only testing.
5. Do not bind to a public interface until HTTPS, authentication, firewalling, and the sandbox configuration are verified.
