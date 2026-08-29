from __future__ import annotations

import sqlite3
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from control_plane.core import ControlPlane, ControlPlaneError, Settings


CLIENT_TOKEN = "client-test-token-000000000000"
OWNER_TOKEN = "owner-test-token-0000000000000"


class Clock:
    def __init__(self) -> None:
        self.value = datetime(2026, 1, 1, tzinfo=timezone.utc)

    def now(self) -> datetime:
        return self.value

    def advance(self, seconds: int) -> None:
        self.value += timedelta(seconds=seconds)


class ControlPlaneTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.db_path = str(Path(self.temporary.name) / "deffrow.db")
        self.settings = Settings(self.db_path, CLIENT_TOKEN, OWNER_TOKEN)
        self.plane = ControlPlane(self.settings)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_identity_models_and_agents_are_seeded(self) -> None:
        self.assertEqual(self.plane.identity()["name"], "AKUJI")
        self.assertEqual([model["name"] for model in self.plane.models() if model["active"]], ["local_core"])
        self.assertIn("orchestrator", {agent["id"] for agent in self.plane.agents()})

    def test_memory_and_conversation_survive_restart(self) -> None:
        self.plane.remember("The model is not the AI.")
        self.plane.chat("status")
        restarted = ControlPlane(self.settings)
        self.assertEqual(restarted.recent_memories()[0]["content"], "The model is not the AI.")
        self.assertEqual([message["role"] for message in restarted.conversation()], ["owner", "assistant"])

    def test_chat_saves_memory_and_creates_real_tasks(self) -> None:
        result = self.plane.chat("remember Models may never grant permissions")
        self.assertIn("Saved", result["reply"])
        task_result = self.plane.chat("Prepare the website connection")
        self.assertEqual(task_result["created_task"]["status"], "queued")
        self.assertEqual(self.plane.status()["tasks_total"], 1)

    def test_model_or_agent_cannot_decide_permission(self) -> None:
        request = self.plane.request_permission(
            requested_by="tool_operator",
            action="publish",
            resource="deffrow.com",
            risk="sensitive",
        )
        self.assertEqual(request["status"], "pending")
        with self.assertRaisesRegex(ControlPlaneError, "Only the external owner"):
            self.plane.decide_permission(
                request["id"], decision="approved", actor_type="agent", actor_id="tool_operator"
            )
        approved = self.plane.decide_permission(
            request["id"], decision="approved", actor_type="owner", actor_id="owner-test"
        )
        self.assertEqual(approved["status"], "approved")

    def test_external_policy_handles_read_and_forbidden_requests(self) -> None:
        read_request = self.plane.request_permission(
            requested_by="researcher", action="read", resource="public documentation", risk="read"
        )
        forbidden = self.plane.request_permission(
            requested_by="tool_operator", action="self_grant", resource="owner authority", risk="forbidden"
        )
        self.assertEqual(read_request["status"], "approved")
        self.assertEqual(read_request["decided_by"], "policy:read")
        self.assertEqual(forbidden["status"], "denied")
        self.assertEqual(forbidden["decided_by"], "policy:forbidden")

    def test_expired_task_lease_is_recovered(self) -> None:
        clock = Clock()
        plane = ControlPlane(self.settings, now=clock.now)
        task = plane.create_task("Recover me")
        first = plane.claim_task("orchestrator", lease_seconds=10)
        self.assertEqual(first["id"], task["id"])
        self.assertEqual(first["attempts"], 1)
        clock.advance(11)
        recovered = plane.claim_task("researcher", lease_seconds=10)
        self.assertEqual(recovered["id"], task["id"])
        self.assertEqual(recovered["attempts"], 2)
        completed = plane.finish_task(task["id"], worker_id="researcher", success=True, result={"ok": True})
        self.assertEqual(completed["status"], "completed")

    def test_agents_have_persistent_mailboxes(self) -> None:
        sent = self.plane.send_agent_message(
            sender="orchestrator", recipient="memory_keeper", content="Preserve the owner decision."
        )
        inbox = ControlPlane(self.settings).agent_inbox("memory_keeper")
        self.assertEqual(inbox[0]["id"], sent["id"])

    def test_audit_chain_is_valid_and_append_only(self) -> None:
        self.plane.remember("Audit this")
        verified = self.plane.verify_audit_chain()
        self.assertTrue(verified["valid"])
        with sqlite3.connect(self.db_path) as connection:
            with self.assertRaisesRegex(sqlite3.IntegrityError, "append-only"):
                connection.execute("UPDATE audit_events SET event_type = 'changed' WHERE sequence = 1")
            with self.assertRaisesRegex(sqlite3.IntegrityError, "append-only"):
                connection.execute("DELETE FROM audit_events WHERE sequence = 1")

    def test_tokens_must_be_separate_and_long(self) -> None:
        with self.assertRaises(ValueError):
            ControlPlane(Settings(self.db_path, "short", OWNER_TOKEN))
        with self.assertRaises(ValueError):
            ControlPlane(Settings(self.db_path, CLIENT_TOKEN, CLIENT_TOKEN))


if __name__ == "__main__":
    unittest.main()
