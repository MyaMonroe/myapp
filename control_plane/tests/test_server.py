from __future__ import annotations

import json
import tempfile
import threading
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from control_plane.core import Settings
from control_plane.server import build_server


CLIENT_TOKEN = "client-http-token-000000000000"
OWNER_TOKEN = "owner-http-token-0000000000000"


class ServerTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        settings = Settings(
            str(Path(self.temporary.name) / "deffrow.db"), CLIENT_TOKEN, OWNER_TOKEN
        )
        self.server = build_server(settings, "127.0.0.1", 0)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)
        self.temporary.cleanup()

    def request(
        self, path: str, *, token: str | None = None, method: str = "GET", body: dict | None = None
    ) -> tuple[int, dict | list]:
        headers = {}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body).encode("utf-8")
        request = Request(self.base_url + path, data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=3) as response:
                return response.status, json.loads(response.read())
        except HTTPError as error:
            return error.code, json.loads(error.read())

    def test_health_and_interface_are_available_without_a_key(self) -> None:
        status, health = self.request("/healthz")
        self.assertEqual(status, 200)
        self.assertEqual(health["status"], "running")
        with urlopen(self.base_url + "/", timeout=3) as response:
            self.assertIn(b"DEFF ROW", response.read())

    def test_private_routes_require_a_key(self) -> None:
        status, payload = self.request("/api/status")
        self.assertEqual(status, 403)
        self.assertEqual(payload["code"], "unauthorized")
        status, payload = self.request("/api/status", token=CLIENT_TOKEN)
        self.assertEqual(status, 200)
        self.assertEqual(payload["identity"], "AKUJI")

    def test_client_key_cannot_approve_but_owner_key_can(self) -> None:
        status, permission = self.request(
            "/api/permissions/request",
            token=CLIENT_TOKEN,
            method="POST",
            body={
                "requested_by": "tool_operator",
                "action": "publish",
                "resource": "DEFF ROW website",
                "risk": "sensitive",
            },
        )
        self.assertEqual(status, 200)
        self.assertEqual(permission["status"], "pending")

        path = f"/api/permissions/{permission['id']}/decision"
        status, rejected = self.request(
            path, token=CLIENT_TOKEN, method="POST", body={"decision": "approved"}
        )
        self.assertEqual(status, 403)
        self.assertEqual(rejected["code"], "owner_key_required")

        status, approved = self.request(
            path, token=OWNER_TOKEN, method="POST", body={"decision": "approved"}
        )
        self.assertEqual(status, 200)
        self.assertEqual(approved["status"], "approved")

    def test_client_can_use_chat_and_persistent_memory(self) -> None:
        status, response = self.request(
            "/api/chat",
            token=CLIENT_TOKEN,
            method="POST",
            body={"message": "remember DEFF ROW owns the control plane"},
        )
        self.assertEqual(status, 200)
        self.assertIn("Saved", response["reply"])
        status, memories = self.request("/api/memories", token=CLIENT_TOKEN)
        self.assertEqual(status, 200)
        self.assertEqual(memories[0]["content"], "DEFF ROW owns the control plane")


if __name__ == "__main__":
    unittest.main()
