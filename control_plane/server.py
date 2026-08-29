from __future__ import annotations

import argparse
import hmac
import json
import os
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

from control_plane.core import ControlPlane, ControlPlaneError, Settings


STATIC_DIR = Path(__file__).with_name("static")
MAX_BODY_BYTES = 1_000_000


class ControlPlaneHTTPServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address: tuple[str, int], control_plane: ControlPlane) -> None:
        self.control_plane = control_plane
        super().__init__(address, ControlPlaneHandler)


class ControlPlaneHandler(BaseHTTPRequestHandler):
    server: ControlPlaneHTTPServer
    server_version = "DEFFROW-ControlPlane/0.1"

    def log_message(self, format: str, *args: Any) -> None:
        # Avoid logging tokens or request bodies. Standard request-path logging is
        # intentionally disabled; production ingress should own access logs.
        return

    def _security_headers(self) -> None:
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")

    def _json(self, status: int, payload: dict[str, Any] | list[Any]) -> None:
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self._security_headers()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _html(self, path: Path) -> None:
        try:
            body = path.read_bytes()
        except FileNotFoundError:
            self._json(HTTPStatus.NOT_FOUND, {"error": "Interface not found."})
            return
        self.send_response(HTTPStatus.OK)
        self._security_headers()
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body(self) -> dict[str, Any]:
        raw_length = self.headers.get("Content-Length", "0")
        try:
            length = int(raw_length)
        except ValueError as exc:
            raise ControlPlaneError("Invalid Content-Length.", code="invalid_request") from exc
        if length <= 0:
            return {}
        if length > MAX_BODY_BYTES:
            raise ControlPlaneError("Request body is too large.", code="request_too_large")
        raw = self.rfile.read(length)
        try:
            value = json.loads(raw)
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            raise ControlPlaneError("Request body must be valid JSON.", code="invalid_json") from exc
        if not isinstance(value, dict):
            raise ControlPlaneError("Request body must be a JSON object.", code="invalid_json")
        return value

    def _role(self) -> str | None:
        authorization = self.headers.get("Authorization", "")
        if authorization.startswith("Bearer "):
            supplied = authorization[7:]
        else:
            supplied = self.headers.get("X-DEFFROW-Token", "")
        if supplied and hmac.compare_digest(supplied, self.server.control_plane.settings.owner_token):
            return "owner"
        if supplied and hmac.compare_digest(supplied, self.server.control_plane.settings.client_token):
            return "client"
        return None

    def _require(self, role: str = "client") -> str:
        actual = self._role()
        allowed = actual == "owner" or (actual == "client" and role == "client")
        if not allowed:
            code = "owner_key_required" if role == "owner" and actual else "unauthorized"
            message = "The separate owner key is required." if code == "owner_key_required" else "A valid DEFF ROW key is required."
            raise ControlPlaneError(message, code=code)
        return actual or "client"

    @staticmethod
    def _limit(query: dict[str, list[str]], default: int) -> int:
        try:
            return int(query.get("limit", [str(default)])[0])
        except ValueError as exc:
            raise ControlPlaneError("limit must be a number.", code="invalid_request") from exc

    def do_GET(self) -> None:
        try:
            parsed = urlparse(self.path)
            if parsed.path in {"/", "/index.html"}:
                self._html(STATIC_DIR / "index.html")
                return
            if parsed.path == "/healthz":
                self._json(HTTPStatus.OK, {"status": "running", "system": "DEFF ROW Sovereign AI"})
                return
            self._require()
            query = parse_qs(parsed.query)
            plane = self.server.control_plane
            routes: dict[str, Any] = {
                "/api/status": plane.status,
                "/api/identity": plane.identity,
                "/api/models": plane.models,
                "/api/agents": plane.agents,
                "/api/audit/verify": plane.verify_audit_chain,
            }
            if parsed.path in routes:
                self._json(HTTPStatus.OK, routes[parsed.path]())
            elif parsed.path == "/api/conversation":
                self._json(HTTPStatus.OK, plane.conversation(self._limit(query, 100)))
            elif parsed.path == "/api/memories":
                self._json(HTTPStatus.OK, plane.recent_memories(self._limit(query, 50)))
            elif parsed.path == "/api/tasks":
                self._json(HTTPStatus.OK, plane.tasks(self._limit(query, 100)))
            elif parsed.path == "/api/permissions":
                status = query.get("status", [None])[0]
                self._json(HTTPStatus.OK, plane.permissions(status=status, limit=self._limit(query, 100)))
            elif parsed.path == "/api/audit":
                self._json(HTTPStatus.OK, plane.audit_events(self._limit(query, 200)))
            elif parsed.path.startswith("/api/agents/") and parsed.path.endswith("/inbox"):
                recipient = parsed.path.split("/")[3]
                self._json(HTTPStatus.OK, plane.agent_inbox(recipient, limit=self._limit(query, 100)))
            else:
                self._json(HTTPStatus.NOT_FOUND, {"error": "Route not found.", "code": "not_found"})
        except ControlPlaneError as exc:
            self._error(exc)
        except Exception:
            self._json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": "The control plane could not complete that request.", "code": "internal_error"})

    def do_POST(self) -> None:
        try:
            parsed = urlparse(self.path)
            role = self._require("owner" if parsed.path.endswith("/decision") else "client")
            body = self._body()
            plane = self.server.control_plane
            if parsed.path == "/api/chat":
                result = plane.chat(str(body.get("message", "")))
            elif parsed.path == "/api/memories":
                result = plane.remember(
                    str(body.get("content", "")),
                    kind=str(body.get("kind", "fact")),
                    source="owner" if role == "owner" else "client",
                )
            elif parsed.path == "/api/tasks":
                payload = body.get("payload", {})
                if not isinstance(payload, dict):
                    raise ControlPlaneError("payload must be an object.", code="invalid_request")
                result = plane.create_task(
                    str(body.get("title", "")), payload=payload, created_by="owner" if role == "owner" else "client"
                )
            elif parsed.path == "/api/tasks/claim":
                result = plane.claim_task(
                    str(body.get("worker_id", "")), lease_seconds=int(body.get("lease_seconds", 60))
                )
                if result is None:
                    self._json(HTTPStatus.OK, {"task": None})
                    return
            elif parsed.path.startswith("/api/tasks/") and parsed.path.endswith("/finish"):
                task_id = parsed.path.split("/")[3]
                result_value = body.get("result")
                if result_value is not None and not isinstance(result_value, dict):
                    raise ControlPlaneError("result must be an object.", code="invalid_request")
                result = plane.finish_task(
                    task_id,
                    worker_id=str(body.get("worker_id", "")),
                    success=bool(body.get("success", False)),
                    result=result_value,
                    error=str(body.get("error", "")) or None,
                    retryable=bool(body.get("retryable", True)),
                )
            elif parsed.path == "/api/permissions/request":
                result = plane.request_permission(
                    requested_by=str(body.get("requested_by", "unknown-client")),
                    action=str(body.get("action", "")),
                    resource=str(body.get("resource", "")),
                    risk=str(body.get("risk", "")),
                    task_id=str(body["task_id"]) if body.get("task_id") else None,
                )
            elif parsed.path.startswith("/api/permissions/") and parsed.path.endswith("/decision"):
                permission_id = parsed.path.split("/")[3]
                result = plane.decide_permission(
                    permission_id,
                    decision=str(body.get("decision", "")),
                    actor_type="owner",
                    actor_id="DEFF ROW owner key",
                    reason=str(body.get("reason", "")),
                )
            elif parsed.path == "/api/agents/messages":
                result = plane.send_agent_message(
                    sender=str(body.get("sender", "")),
                    recipient=str(body.get("recipient", "")),
                    content=str(body.get("content", "")),
                )
            else:
                self._json(HTTPStatus.NOT_FOUND, {"error": "Route not found.", "code": "not_found"})
                return
            self._json(HTTPStatus.OK, result)
        except (TypeError, ValueError):
            self._json(HTTPStatus.BAD_REQUEST, {"error": "The request contains an invalid value.", "code": "invalid_request"})
        except ControlPlaneError as exc:
            self._error(exc)
        except Exception:
            self._json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": "The control plane could not complete that request.", "code": "internal_error"})

    def _error(self, exc: ControlPlaneError) -> None:
        if exc.code in {"unauthorized", "owner_key_required"}:
            status = HTTPStatus.FORBIDDEN
        elif exc.code == "not_found":
            status = HTTPStatus.NOT_FOUND
        elif exc.code in {"already_decided", "lease_mismatch"}:
            status = HTTPStatus.CONFLICT
        elif exc.code == "request_too_large":
            status = HTTPStatus.REQUEST_ENTITY_TOO_LARGE
        else:
            status = HTTPStatus.BAD_REQUEST
        self._json(status, {"error": str(exc), "code": exc.code})


def build_server(settings: Settings, host: str = "127.0.0.1", port: int = 8787) -> ControlPlaneHTTPServer:
    settings.validate()
    return ControlPlaneHTTPServer((host, port), ControlPlane(settings))


def settings_from_environment() -> Settings:
    return Settings(
        db_path=os.environ.get("DEFFROW_DB_PATH", "/data/deffrow.db"),
        client_token=os.environ.get("DEFFROW_CLIENT_TOKEN", ""),
        owner_token=os.environ.get("DEFFROW_OWNER_TOKEN", ""),
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Run the DEFF ROW sovereign AI control plane.")
    parser.add_argument("--host", default=os.environ.get("DEFFROW_HOST", "0.0.0.0"))
    parser.add_argument("--port", type=int, default=int(os.environ.get("PORT", "8787")))
    args = parser.parse_args()
    try:
        server = build_server(settings_from_environment(), args.host, args.port)
    except ValueError as exc:
        raise SystemExit(f"Startup stopped: {exc}") from exc
    print(f"DEFF ROW control plane running on http://{args.host}:{args.port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
