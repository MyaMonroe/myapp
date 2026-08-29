# AKUJI Direct Tool Bridge

This service is the controlled boundary between the AKUJI Android app and private/external tools. Gemini Live remains AKUJI's conversation/reasoning layer; this bridge does not run a second AI model and does not require Hermes, MiniMax, Qwen, or another model provider.

## Security model

- No account passwords, API keys, OAuth client secrets, signing files, or long-lived provider tokens belong in the Android APK or Git repository.
- Every private bridge request requires `Authorization: Bearer <AKUJI_BRIDGE_TOKEN>`.
- The bridge starts with execution disabled unless `AKUJI_BRIDGE_ALLOW_EXECUTION=true` is explicitly set on the host.
- Dry-run validates a tool request without calling the external provider.
- Consequential actions remain approval-gated even after more tools are attached.

## Current endpoints

- `GET /health` — liveness check.
- `GET /v1/status` — authenticated direct-operator state and the actual connected tool names.
- `POST /v1/operator/tool` — validates or executes one allowlisted tool request.

## Current direct tool

`picsart_remove_background` accepts JSON arguments containing `image_url` and optional `format` (`PNG`, `JPG`, or `WEBP`).

When `dry_run=true`, the bridge returns a validation result and does **not** call Picsart or consume a Picsart developer credit. When execution is explicitly enabled and `dry_run=false`, the bridge calls Picsart's official Remove Background API with the server-side `PICSART_API_KEY`.

## Local development

1. Copy `.env.example` to `.env` and provide a long random bridge token.
2. Leave `PICSART_API_KEY` blank unless you deliberately want to connect it.
3. Leave `AKUJI_BRIDGE_ALLOW_EXECUTION=false` while testing.
4. Install dependencies from `requirements.txt`.
5. Export the environment variables from `.env`.
6. Run `uvicorn app:app --host 127.0.0.1 --port 8787` from the `bridge` directory.
