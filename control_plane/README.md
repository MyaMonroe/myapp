# DEFF ROW Sovereign AI control plane

This is the first runnable system around the models. It works without an OpenAI, Google, Alibaba, Qwen, Gemma, or DeepSeek account. Those models can later plug into it as replaceable reasoning engines.

What is real now:

- persistent AKUJI identity and operating rules;
- SQLite memory and conversation history;
- persistent task/event queue with leases, retries, and crash recovery;
- orchestrator and specialist-agent registry;
- durable agent-to-agent mailboxes;
- permission requests controlled outside the models;
- a separate owner key for approvals;
- append-only, SHA-256-linked audit events;
- provider-neutral model registry; and
- a phone-first private web interface.

Models can request permissions with the client key. The approval endpoint rejects that key. Only the separate owner key can approve or deny pending write and sensitive actions. Read actions can be approved by external policy; forbidden actions are denied by external policy.

## Run the tests

No third-party Python packages are required.

```bash
python3 -m unittest discover -s control_plane/tests -v
```

## Run on a server

Create two different random keys and keep them outside the repository:

```bash
export DEFFROW_CLIENT_TOKEN="$(openssl rand -hex 32)"
export DEFFROW_OWNER_TOKEN="$(openssl rand -hex 32)"
export DEFFROW_DB_PATH="$PWD/deffrow-data/deffrow.db"
mkdir -p "$PWD/deffrow-data"
python3 -m control_plane.server --host 127.0.0.1 --port 8787
```

Open `http://127.0.0.1:8787`. The keys stay in the current browser tab through `sessionStorage`; they are not written into the application files.

For a portable container:

```bash
docker build -f control_plane/Dockerfile -t deffrow-control-plane .
docker run --name deffrow-control-plane \
  -p 127.0.0.1:8787:8787 \
  -v deffrow-data:/data \
  -e DEFFROW_CLIENT_TOKEN="$DEFFROW_CLIENT_TOKEN" \
  -e DEFFROW_OWNER_TOKEN="$DEFFROW_OWNER_TOKEN" \
  deffrow-control-plane
```

Keep the port private until a server operator puts authenticated HTTPS in front of it. Back up the persistent `/data` volume; that database is the current system memory and history.

## Model boundary

`local_core` is active first. It can save memory, report system status, and turn an owner message into a durable task. `qwen`, `gemma`, and `deepseek` are registered as `adapter_pending`. Connecting a model means building an adapter that receives a task and returns a proposed result. It does not move identity, memory, permissions, or audit ownership into that model.

## Main API routes

All `/api/*` routes require `Authorization: Bearer <key>`.

| Route | Purpose | Authority |
| --- | --- | --- |
| `POST /api/chat` | Talk, remember, or create a task | Client or owner key |
| `GET/POST /api/memories` | Read or add persistent memory | Client or owner key |
| `GET/POST /api/tasks` | Read or create persistent tasks | Client or owner key |
| `POST /api/tasks/claim` | Lease queued work to a registered agent | Client or owner key |
| `POST /api/tasks/{id}/finish` | Complete or retry leased work | Client or owner key |
| `POST /api/permissions/request` | Ask external policy/owner for authority | Client or owner key |
| `POST /api/permissions/{id}/decision` | Approve or deny a pending request | Owner key only |
| `POST /api/agents/messages` | Send persistent agent-to-agent mail | Client or owner key |
| `GET /api/audit/verify` | Verify the event hash chain | Client or owner key |

The next infrastructure connection is an HTTPS host under the DEFF ROW domain with persistent volume backups. The next reasoning connection is one selected model adapter; it should be added only after the host exists and its provider key can be stored as a server secret.
