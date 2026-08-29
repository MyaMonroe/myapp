from __future__ import annotations

import hashlib
import json
import sqlite3
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable


class ControlPlaneError(RuntimeError):
    """An expected control-plane rejection."""

    def __init__(self, message: str, *, code: str = "control_plane_error") -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class Settings:
    db_path: str
    client_token: str
    owner_token: str

    def validate(self) -> None:
        if len(self.client_token) < 24:
            raise ValueError("DEFFROW_CLIENT_TOKEN must contain at least 24 characters.")
        if len(self.owner_token) < 24:
            raise ValueError("DEFFROW_OWNER_TOKEN must contain at least 24 characters.")
        if self.client_token == self.owner_token:
            raise ValueError("Client and owner tokens must be different.")


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _iso(value: datetime) -> str:
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc).isoformat(timespec="milliseconds")


def _json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


class ControlPlane:
    """Persistent state surrounding replaceable model engines.

    The class deliberately contains no provider SDK. Models and agents are clients of
    this service; they do not own its state and cannot approve permissions.
    """

    IDENTITY_RULES = {
        "identity": "AKUJI",
        "owner": "DEFF ROW",
        "truth_rule": "Never claim an action, connection, memory, or deployment succeeded without evidence.",
        "permission_rule": "Models may request permissions. Models may never grant permissions.",
        "working_rule": "Do the technical work and ask the owner only for approvals or physical actions that require her.",
    }

    def __init__(
        self,
        settings: Settings,
        *,
        now: Callable[[], datetime] | None = None,
    ) -> None:
        settings.validate()
        self.settings = settings
        self._now = now or _utc_now
        db_path = Path(settings.db_path)
        db_path.parent.mkdir(parents=True, exist_ok=True)
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.settings.db_path, timeout=30)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA busy_timeout = 30000")
        return connection

    def _initialize(self) -> None:
        with self._connect() as connection:
            connection.executescript(
                """
                PRAGMA journal_mode = WAL;

                CREATE TABLE IF NOT EXISTS identity (
                    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
                    name TEXT NOT NULL,
                    owner TEXT NOT NULL,
                    rules_json TEXT NOT NULL,
                    version INTEGER NOT NULL,
                    updated_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS models (
                    name TEXT PRIMARY KEY,
                    adapter_type TEXT NOT NULL,
                    status TEXT NOT NULL,
                    active INTEGER NOT NULL DEFAULT 0 CHECK (active IN (0, 1)),
                    details_json TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );

                CREATE UNIQUE INDEX IF NOT EXISTS one_active_model
                ON models(active) WHERE active = 1;

                CREATE TABLE IF NOT EXISTS agents (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    role TEXT NOT NULL,
                    status TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS memories (
                    id TEXT PRIMARY KEY,
                    kind TEXT NOT NULL,
                    content TEXT NOT NULL,
                    source TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS conversation_messages (
                    id TEXT PRIMARY KEY,
                    role TEXT NOT NULL CHECK (role IN ('owner', 'assistant', 'system')),
                    content TEXT NOT NULL,
                    model TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS tasks (
                    id TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    result_json TEXT,
                    status TEXT NOT NULL,
                    created_by TEXT NOT NULL,
                    assigned_agent TEXT,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    max_attempts INTEGER NOT NULL DEFAULT 3,
                    lease_owner TEXT,
                    lease_until TEXT,
                    last_error TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY (assigned_agent) REFERENCES agents(id)
                );

                CREATE INDEX IF NOT EXISTS task_queue
                ON tasks(status, lease_until, created_at);

                CREATE TABLE IF NOT EXISTS agent_messages (
                    id TEXT PRIMARY KEY,
                    sender TEXT NOT NULL,
                    recipient TEXT NOT NULL,
                    content TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    read_at TEXT,
                    FOREIGN KEY (sender) REFERENCES agents(id),
                    FOREIGN KEY (recipient) REFERENCES agents(id)
                );

                CREATE TABLE IF NOT EXISTS permission_requests (
                    id TEXT PRIMARY KEY,
                    task_id TEXT,
                    requested_by TEXT NOT NULL,
                    action TEXT NOT NULL,
                    resource TEXT NOT NULL,
                    risk TEXT NOT NULL CHECK (risk IN ('read', 'write', 'sensitive', 'forbidden')),
                    status TEXT NOT NULL CHECK (status IN ('pending', 'approved', 'denied')),
                    reason TEXT NOT NULL,
                    decided_by TEXT,
                    created_at TEXT NOT NULL,
                    decided_at TEXT,
                    FOREIGN KEY (task_id) REFERENCES tasks(id)
                );

                CREATE TABLE IF NOT EXISTS audit_events (
                    sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                    id TEXT NOT NULL UNIQUE,
                    event_type TEXT NOT NULL,
                    actor_type TEXT NOT NULL,
                    actor_id TEXT NOT NULL,
                    object_type TEXT NOT NULL,
                    object_id TEXT NOT NULL,
                    payload_json TEXT NOT NULL,
                    previous_hash TEXT NOT NULL,
                    event_hash TEXT NOT NULL UNIQUE,
                    created_at TEXT NOT NULL
                );

                CREATE TRIGGER IF NOT EXISTS audit_events_no_update
                BEFORE UPDATE ON audit_events
                BEGIN
                    SELECT RAISE(ABORT, 'audit events are append-only');
                END;

                CREATE TRIGGER IF NOT EXISTS audit_events_no_delete
                BEFORE DELETE ON audit_events
                BEGIN
                    SELECT RAISE(ABORT, 'audit events are append-only');
                END;
                """
            )

            now = self.now_iso()
            identity_exists = connection.execute(
                "SELECT 1 FROM identity WHERE singleton = 1"
            ).fetchone()
            if not identity_exists:
                connection.execute(
                    """
                    INSERT INTO identity(singleton, name, owner, rules_json, version, updated_at)
                    VALUES(1, ?, ?, ?, 1, ?)
                    """,
                    ("AKUJI", "DEFF ROW", _json(self.IDENTITY_RULES), now),
                )
                self._audit(
                    connection,
                    event_type="identity.created",
                    actor_type="system",
                    actor_id="control-plane",
                    object_type="identity",
                    object_id="AKUJI",
                    payload={"version": 1},
                )

            seed_models = (
                ("local_core", "builtin", "ready", 1, {"provider": None}),
                ("qwen", "provider_adapter", "adapter_pending", 0, {"provider": "Alibaba"}),
                ("gemma", "local_or_provider_adapter", "adapter_pending", 0, {"provider": "Google/open weights"}),
                ("deepseek", "provider_adapter", "adapter_pending", 0, {"provider": "DeepSeek"}),
            )
            for name, adapter_type, status, active, details in seed_models:
                connection.execute(
                    """
                    INSERT OR IGNORE INTO models(name, adapter_type, status, active, details_json, updated_at)
                    VALUES(?, ?, ?, ?, ?, ?)
                    """,
                    (name, adapter_type, status, active, _json(details), now),
                )

            seed_agents = (
                ("orchestrator", "AKUJI Orchestrator", "Owns task routing and recovery"),
                ("memory_keeper", "Memory Keeper", "Maintains shared durable memory"),
                ("tool_operator", "Tool Operator", "Requests approved external tool actions"),
                ("researcher", "Researcher", "Finds and verifies source-grounded information"),
            )
            for agent_id, name, role in seed_agents:
                connection.execute(
                    """
                    INSERT OR IGNORE INTO agents(id, name, role, status, created_at, updated_at)
                    VALUES(?, ?, ?, 'ready', ?, ?)
                    """,
                    (agent_id, name, role, now, now),
                )

    def now_iso(self) -> str:
        return _iso(self._now())

    @staticmethod
    def _row(row: sqlite3.Row | None) -> dict[str, Any] | None:
        if row is None:
            return None
        result = dict(row)
        for key in ("rules_json", "details_json", "payload_json", "result_json"):
            if key in result:
                raw = result.pop(key)
                result[key.removesuffix("_json")] = json.loads(raw) if raw else None
        if "active" in result:
            result["active"] = bool(result["active"])
        return result

    def _audit(
        self,
        connection: sqlite3.Connection,
        *,
        event_type: str,
        actor_type: str,
        actor_id: str,
        object_type: str,
        object_id: str,
        payload: dict[str, Any],
    ) -> dict[str, Any]:
        # Acquire the database write lock before reading the prior hash when an
        # audit entry is the first write in a transaction. This prevents two
        # concurrent requests from creating separate branches of the hash chain.
        if not connection.in_transaction:
            connection.execute("BEGIN IMMEDIATE")
        event_id = str(uuid.uuid4())
        created_at = self.now_iso()
        previous = connection.execute(
            "SELECT event_hash FROM audit_events ORDER BY sequence DESC LIMIT 1"
        ).fetchone()
        previous_hash = previous["event_hash"] if previous else "GENESIS"
        canonical = {
            "id": event_id,
            "event_type": event_type,
            "actor_type": actor_type,
            "actor_id": actor_id,
            "object_type": object_type,
            "object_id": object_id,
            "payload": payload,
            "previous_hash": previous_hash,
            "created_at": created_at,
        }
        event_hash = hashlib.sha256(_json(canonical).encode("utf-8")).hexdigest()
        connection.execute(
            """
            INSERT INTO audit_events(
                id, event_type, actor_type, actor_id, object_type, object_id,
                payload_json, previous_hash, event_hash, created_at
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                event_id,
                event_type,
                actor_type,
                actor_id,
                object_type,
                object_id,
                _json(payload),
                previous_hash,
                event_hash,
                created_at,
            ),
        )
        return {**canonical, "event_hash": event_hash}

    def identity(self) -> dict[str, Any]:
        with self._connect() as connection:
            row = connection.execute("SELECT * FROM identity WHERE singleton = 1").fetchone()
        return self._row(row) or {}

    def models(self) -> list[dict[str, Any]]:
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT * FROM models ORDER BY active DESC, name ASC"
            ).fetchall()
        return [self._row(row) or {} for row in rows]

    def agents(self) -> list[dict[str, Any]]:
        with self._connect() as connection:
            rows = connection.execute("SELECT * FROM agents ORDER BY id").fetchall()
        return [self._row(row) or {} for row in rows]

    def remember(self, content: str, *, kind: str = "fact", source: str = "owner") -> dict[str, Any]:
        clean = content.strip()
        if not clean:
            raise ControlPlaneError("Memory content cannot be empty.", code="invalid_memory")
        if len(clean) > 20_000:
            raise ControlPlaneError("Memory content exceeds 20,000 characters.", code="memory_too_large")
        memory_id = str(uuid.uuid4())
        created_at = self.now_iso()
        with self._connect() as connection:
            connection.execute(
                "INSERT INTO memories(id, kind, content, source, created_at) VALUES(?, ?, ?, ?, ?)",
                (memory_id, kind.strip() or "fact", clean, source.strip() or "owner", created_at),
            )
            self._audit(
                connection,
                event_type="memory.created",
                actor_type="owner" if source == "owner" else "agent",
                actor_id=source,
                object_type="memory",
                object_id=memory_id,
                payload={"kind": kind.strip() or "fact", "content_sha256": hashlib.sha256(clean.encode()).hexdigest()},
            )
        return {"id": memory_id, "kind": kind.strip() or "fact", "content": clean, "source": source, "created_at": created_at}

    def recent_memories(self, limit: int = 50) -> list[dict[str, Any]]:
        limit = max(1, min(limit, 200))
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT * FROM memories ORDER BY created_at DESC LIMIT ?", (limit,)
            ).fetchall()
        return [self._row(row) or {} for row in rows]

    def create_task(
        self,
        title: str,
        *,
        payload: dict[str, Any] | None = None,
        created_by: str = "owner",
        max_attempts: int = 3,
    ) -> dict[str, Any]:
        clean = title.strip()
        if not clean:
            raise ControlPlaneError("Task title cannot be empty.", code="invalid_task")
        if not 1 <= max_attempts <= 20:
            raise ControlPlaneError("max_attempts must be between 1 and 20.", code="invalid_task")
        task_id = str(uuid.uuid4())
        now = self.now_iso()
        task_payload = payload or {}
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO tasks(
                    id, title, payload_json, status, created_by, attempts,
                    max_attempts, created_at, updated_at
                ) VALUES(?, ?, ?, 'queued', ?, 0, ?, ?, ?)
                """,
                (task_id, clean[:500], _json(task_payload), created_by, max_attempts, now, now),
            )
            self._audit(
                connection,
                event_type="task.created",
                actor_type="owner" if created_by == "owner" else "agent",
                actor_id=created_by,
                object_type="task",
                object_id=task_id,
                payload={"title": clean[:500], "max_attempts": max_attempts},
            )
            row = connection.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone()
        return self._row(row) or {}

    def task(self, task_id: str) -> dict[str, Any]:
        with self._connect() as connection:
            row = connection.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone()
        if row is None:
            raise ControlPlaneError("Task not found.", code="not_found")
        return self._row(row) or {}

    def tasks(self, limit: int = 100) -> list[dict[str, Any]]:
        limit = max(1, min(limit, 500))
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT * FROM tasks ORDER BY created_at DESC LIMIT ?", (limit,)
            ).fetchall()
        return [self._row(row) or {} for row in rows]

    def claim_task(self, worker_id: str, *, lease_seconds: int = 60) -> dict[str, Any] | None:
        if not worker_id.strip():
            raise ControlPlaneError("Worker id cannot be empty.", code="invalid_worker")
        if not 1 <= lease_seconds <= 3600:
            raise ControlPlaneError("Lease must be between 1 and 3600 seconds.", code="invalid_lease")
        now_dt = self._now().astimezone(timezone.utc)
        now = _iso(now_dt)
        lease_until = _iso(datetime.fromtimestamp(now_dt.timestamp() + lease_seconds, tz=timezone.utc))
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            if not connection.execute("SELECT 1 FROM agents WHERE id = ?", (worker_id,)).fetchone():
                raise ControlPlaneError("Worker must be a registered agent.", code="unknown_agent")
            row = connection.execute(
                """
                SELECT * FROM tasks
                WHERE attempts < max_attempts
                  AND (
                    status = 'queued'
                    OR (status = 'in_progress' AND lease_until IS NOT NULL AND lease_until <= ?)
                  )
                ORDER BY created_at ASC
                LIMIT 1
                """,
                (now,),
            ).fetchone()
            if row is None:
                connection.commit()
                return None
            task_id = row["id"]
            connection.execute(
                """
                UPDATE tasks
                SET status = 'in_progress', assigned_agent = ?, lease_owner = ?,
                    lease_until = ?, attempts = attempts + 1, updated_at = ?
                WHERE id = ?
                """,
                (worker_id, worker_id, lease_until, now, task_id),
            )
            self._audit(
                connection,
                event_type="task.claimed",
                actor_type="agent",
                actor_id=worker_id,
                object_type="task",
                object_id=task_id,
                payload={"lease_until": lease_until, "recovered": row["status"] == "in_progress"},
            )
            claimed = connection.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone()
            connection.commit()
            return self._row(claimed)
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    def finish_task(
        self,
        task_id: str,
        *,
        worker_id: str,
        success: bool,
        result: dict[str, Any] | None = None,
        error: str | None = None,
        retryable: bool = True,
    ) -> dict[str, Any]:
        now = self.now_iso()
        with self._connect() as connection:
            row = connection.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone()
            if row is None:
                raise ControlPlaneError("Task not found.", code="not_found")
            if row["status"] != "in_progress" or row["lease_owner"] != worker_id:
                raise ControlPlaneError("Worker does not hold this task lease.", code="lease_mismatch")
            if success:
                status = "completed"
                last_error = None
            else:
                status = "queued" if retryable and row["attempts"] < row["max_attempts"] else "failed"
                last_error = (error or "Task failed without an error message.")[:4000]
            connection.execute(
                """
                UPDATE tasks
                SET status = ?, result_json = ?, last_error = ?, lease_owner = NULL,
                    lease_until = NULL, updated_at = ?
                WHERE id = ?
                """,
                (status, _json(result) if result is not None else None, last_error, now, task_id),
            )
            self._audit(
                connection,
                event_type="task.completed" if success else "task.failed",
                actor_type="agent",
                actor_id=worker_id,
                object_type="task",
                object_id=task_id,
                payload={"status": status, "retryable": retryable, "error": last_error},
            )
            updated = connection.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone()
        return self._row(updated) or {}

    def request_permission(
        self,
        *,
        requested_by: str,
        action: str,
        resource: str,
        risk: str,
        task_id: str | None = None,
    ) -> dict[str, Any]:
        risk = risk.strip().lower()
        if risk not in {"read", "write", "sensitive", "forbidden"}:
            raise ControlPlaneError("Risk must be read, write, sensitive, or forbidden.", code="invalid_risk")
        if not action.strip() or not resource.strip():
            raise ControlPlaneError("Action and resource are required.", code="invalid_permission")
        if risk == "read":
            status = "approved"
            reason = "Approved by the external read policy."
            decided_by = "policy:read"
            decided_at = self.now_iso()
        elif risk == "forbidden":
            status = "denied"
            reason = "Denied by the external forbidden-action policy."
            decided_by = "policy:forbidden"
            decided_at = self.now_iso()
        else:
            status = "pending"
            reason = "Waiting for the owner."
            decided_by = None
            decided_at = None
        permission_id = str(uuid.uuid4())
        created_at = self.now_iso()
        with self._connect() as connection:
            if task_id and not connection.execute("SELECT 1 FROM tasks WHERE id = ?", (task_id,)).fetchone():
                raise ControlPlaneError("Task not found.", code="not_found")
            connection.execute(
                """
                INSERT INTO permission_requests(
                    id, task_id, requested_by, action, resource, risk, status,
                    reason, decided_by, created_at, decided_at
                ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    permission_id,
                    task_id,
                    requested_by,
                    action.strip()[:500],
                    resource.strip()[:1000],
                    risk,
                    status,
                    reason,
                    decided_by,
                    created_at,
                    decided_at,
                ),
            )
            self._audit(
                connection,
                event_type="permission.requested",
                actor_type="model_or_agent",
                actor_id=requested_by,
                object_type="permission",
                object_id=permission_id,
                payload={"action": action.strip(), "resource": resource.strip(), "risk": risk, "status": status},
            )
            row = connection.execute(
                "SELECT * FROM permission_requests WHERE id = ?", (permission_id,)
            ).fetchone()
        return self._row(row) or {}

    def decide_permission(
        self,
        permission_id: str,
        *,
        decision: str,
        actor_type: str,
        actor_id: str,
        reason: str = "",
    ) -> dict[str, Any]:
        if actor_type != "owner":
            raise ControlPlaneError(
                "Only the external owner authority can decide permissions.",
                code="owner_authority_required",
            )
        decision = decision.strip().lower()
        if decision not in {"approved", "denied"}:
            raise ControlPlaneError("Decision must be approved or denied.", code="invalid_decision")
        now = self.now_iso()
        with self._connect() as connection:
            row = connection.execute(
                "SELECT * FROM permission_requests WHERE id = ?", (permission_id,)
            ).fetchone()
            if row is None:
                raise ControlPlaneError("Permission request not found.", code="not_found")
            if row["status"] != "pending":
                raise ControlPlaneError("Permission request is already decided.", code="already_decided")
            final_reason = reason.strip() or f"{decision.capitalize()} by the owner."
            connection.execute(
                """
                UPDATE permission_requests
                SET status = ?, reason = ?, decided_by = ?, decided_at = ?
                WHERE id = ?
                """,
                (decision, final_reason[:2000], actor_id, now, permission_id),
            )
            self._audit(
                connection,
                event_type="permission.decided",
                actor_type="owner",
                actor_id=actor_id,
                object_type="permission",
                object_id=permission_id,
                payload={"decision": decision, "reason": final_reason[:2000]},
            )
            updated = connection.execute(
                "SELECT * FROM permission_requests WHERE id = ?", (permission_id,)
            ).fetchone()
        return self._row(updated) or {}

    def permissions(self, *, status: str | None = None, limit: int = 100) -> list[dict[str, Any]]:
        limit = max(1, min(limit, 500))
        with self._connect() as connection:
            if status:
                rows = connection.execute(
                    """
                    SELECT * FROM permission_requests
                    WHERE status = ? ORDER BY created_at DESC LIMIT ?
                    """,
                    (status, limit),
                ).fetchall()
            else:
                rows = connection.execute(
                    "SELECT * FROM permission_requests ORDER BY created_at DESC LIMIT ?",
                    (limit,),
                ).fetchall()
        return [self._row(row) or {} for row in rows]

    def send_agent_message(self, *, sender: str, recipient: str, content: str) -> dict[str, Any]:
        clean = content.strip()
        if not clean:
            raise ControlPlaneError("Agent message cannot be empty.", code="invalid_message")
        message_id = str(uuid.uuid4())
        created_at = self.now_iso()
        with self._connect() as connection:
            known = {
                row["id"] for row in connection.execute(
                    "SELECT id FROM agents WHERE id IN (?, ?)", (sender, recipient)
                ).fetchall()
            }
            if {sender, recipient} - known:
                raise ControlPlaneError("Sender and recipient must be registered agents.", code="unknown_agent")
            connection.execute(
                """
                INSERT INTO agent_messages(id, sender, recipient, content, created_at)
                VALUES(?, ?, ?, ?, ?)
                """,
                (message_id, sender, recipient, clean[:20_000], created_at),
            )
            self._audit(
                connection,
                event_type="agent.message_sent",
                actor_type="agent",
                actor_id=sender,
                object_type="agent_message",
                object_id=message_id,
                payload={"recipient": recipient, "content_sha256": hashlib.sha256(clean.encode()).hexdigest()},
            )
        return {"id": message_id, "sender": sender, "recipient": recipient, "content": clean[:20_000], "created_at": created_at, "read_at": None}

    def agent_inbox(self, recipient: str, *, limit: int = 100) -> list[dict[str, Any]]:
        limit = max(1, min(limit, 500))
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT * FROM agent_messages
                WHERE recipient = ? ORDER BY created_at ASC LIMIT ?
                """,
                (recipient, limit),
            ).fetchall()
        return [self._row(row) or {} for row in rows]

    def _record_chat(self, role: str, content: str, model: str) -> dict[str, Any]:
        message_id = str(uuid.uuid4())
        created_at = self.now_iso()
        with self._connect() as connection:
            connection.execute(
                """
                INSERT INTO conversation_messages(id, role, content, model, created_at)
                VALUES(?, ?, ?, ?, ?)
                """,
                (message_id, role, content, model, created_at),
            )
        return {"id": message_id, "role": role, "content": content, "model": model, "created_at": created_at}

    def chat(self, message: str) -> dict[str, Any]:
        clean = message.strip()
        if not clean:
            raise ControlPlaneError("Message cannot be empty.", code="invalid_message")
        if len(clean) > 20_000:
            raise ControlPlaneError("Message exceeds 20,000 characters.", code="message_too_large")
        active_model = next(model for model in self.models() if model["active"])
        model_name = active_model["name"]
        self._record_chat("owner", clean, model_name)
        normalized = clean.lower()
        created_task: dict[str, Any] | None = None
        if normalized.startswith("remember "):
            item = clean[9:].strip()
            memory = self.remember(item, source="owner")
            reply = f"Saved to DEFF ROW memory: {memory['content']}"
        elif "what do you remember" in normalized or normalized in {"memory", "show memory"}:
            memories = self.recent_memories(10)
            if memories:
                reply = "Latest DEFF ROW memory:\n" + "\n".join(f"• {item['content']}" for item in memories)
            else:
                reply = "DEFF ROW memory is empty."
        elif normalized in {"status", "system status", "akuji status"}:
            status = self.status()
            reply = (
                f"AKUJI is running on the DEFF ROW control plane. "
                f"Memory: {status['memories']}. Tasks: {status['tasks_total']} total, "
                f"{status['tasks_queued']} queued. Pending approvals: {status['permissions_pending']}. "
                f"Active brain: {status['active_model']}."
            )
        else:
            created_task = self.create_task(clean[:500], payload={"owner_message": clean}, created_by="owner")
            reply = (
                "I saved that as a real DEFF ROW task. The sovereign memory, queue, permissions, "
                "and audit system are active. The full reasoning model adapter is the next connection."
            )
        assistant_message = self._record_chat("assistant", reply, model_name)
        with self._connect() as connection:
            self._audit(
                connection,
                event_type="chat.completed",
                actor_type="system",
                actor_id=model_name,
                object_type="conversation_message",
                object_id=assistant_message["id"],
                payload={"created_task_id": created_task["id"] if created_task else None},
            )
        return {"reply": reply, "model": model_name, "created_task": created_task}

    def conversation(self, limit: int = 100) -> list[dict[str, Any]]:
        limit = max(1, min(limit, 500))
        with self._connect() as connection:
            rows = connection.execute(
                """
                SELECT * FROM (
                    SELECT * FROM conversation_messages ORDER BY created_at DESC LIMIT ?
                ) ORDER BY created_at ASC
                """,
                (limit,),
            ).fetchall()
        return [self._row(row) or {} for row in rows]

    def status(self) -> dict[str, Any]:
        with self._connect() as connection:
            memories = connection.execute("SELECT COUNT(*) AS count FROM memories").fetchone()["count"]
            tasks_total = connection.execute("SELECT COUNT(*) AS count FROM tasks").fetchone()["count"]
            tasks_queued = connection.execute(
                "SELECT COUNT(*) AS count FROM tasks WHERE status = 'queued'"
            ).fetchone()["count"]
            permissions_pending = connection.execute(
                "SELECT COUNT(*) AS count FROM permission_requests WHERE status = 'pending'"
            ).fetchone()["count"]
            active_model = connection.execute(
                "SELECT name FROM models WHERE active = 1"
            ).fetchone()["name"]
            audit_events = connection.execute("SELECT COUNT(*) AS count FROM audit_events").fetchone()["count"]
        return {
            "system": "DEFF ROW Sovereign AI",
            "identity": "AKUJI",
            "memories": memories,
            "tasks_total": tasks_total,
            "tasks_queued": tasks_queued,
            "permissions_pending": permissions_pending,
            "active_model": active_model,
            "audit_events": audit_events,
        }

    def audit_events(self, limit: int = 200) -> list[dict[str, Any]]:
        limit = max(1, min(limit, 1000))
        with self._connect() as connection:
            rows = connection.execute(
                "SELECT * FROM audit_events ORDER BY sequence DESC LIMIT ?", (limit,)
            ).fetchall()
        return [self._row(row) or {} for row in rows]

    def verify_audit_chain(self) -> dict[str, Any]:
        with self._connect() as connection:
            rows = connection.execute("SELECT * FROM audit_events ORDER BY sequence ASC").fetchall()
        previous_hash = "GENESIS"
        for row in rows:
            payload = json.loads(row["payload_json"])
            canonical = {
                "id": row["id"],
                "event_type": row["event_type"],
                "actor_type": row["actor_type"],
                "actor_id": row["actor_id"],
                "object_type": row["object_type"],
                "object_id": row["object_id"],
                "payload": payload,
                "previous_hash": previous_hash,
                "created_at": row["created_at"],
            }
            expected = hashlib.sha256(_json(canonical).encode("utf-8")).hexdigest()
            if row["previous_hash"] != previous_hash or row["event_hash"] != expected:
                return {"valid": False, "events": len(rows), "failed_sequence": row["sequence"]}
            previous_hash = row["event_hash"]
        return {"valid": True, "events": len(rows), "head_hash": previous_hash}
