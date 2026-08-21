from __future__ import annotations

import asyncio
import os
import secrets
from typing import Any

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, status
from pydantic import BaseModel, Field


APP_NAME = "AKUJI Tool Bridge"
BRIDGE_TOKEN = os.getenv("AKUJI_BRIDGE_TOKEN", "").strip()
HARNESS_KIND = os.getenv("AKUJI_HARNESS_KIND", "generic").strip().lower() or "generic"
HARNESS_BASE_URL = os.getenv("AKUJI_HARNESS_BASE_URL", "").strip().rstrip("/")
HARNESS_TASK_PATH = os.getenv("AKUJI_HARNESS_TASK_PATH", "").strip()
HARNESS_TOKEN = os.getenv("AKUJI_HARNESS_TOKEN", "").strip()
ALLOW_EXECUTION = os.getenv("AKUJI_BRIDGE_ALLOW_EXECUTION", "false").strip().lower() == "true"
HERMES_WAIT_SECONDS = 50.0
HERMES_POLL_SECONDS = 0.75

app = FastAPI(title=APP_NAME, version="0.3.0", docs_url=None, redoc_url=None)


class BridgeStatus(BaseModel):
    bridge: str
    authenticated: bool
    harness_kind: str
    harness_configured: bool
    execution_enabled: bool


class HarnessTask(BaseModel):
    instruction: str = Field(min_length=1, max_length=12000)
    context: str | None = Field(default=None, max_length=24000)
    dry_run: bool = True
    session_id: str | None = Field(default=None, max_length=200)


class HarnessResult(BaseModel):
    ok: bool
    status_code: int | None = None
    result: Any | None = None
    message: str | None = None


def require_bridge_auth(authorization: str | None = Header(default=None)) -> None:
    if not BRIDGE_TOKEN:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="AKUJI bridge authentication is not configured.",
        )

    scheme, _, supplied = (authorization or "").partition(" ")
    if scheme.lower() != "bearer" or not supplied or not secrets.compare_digest(supplied, BRIDGE_TOKEN):
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Unauthorized.",
            headers={"WWW-Authenticate": "Bearer"},
        )


def harness_ready() -> bool:
    if HARNESS_KIND == "hermes":
        return bool(HARNESS_BASE_URL and HARNESS_TOKEN)
    return bool(HARNESS_BASE_URL and HARNESS_TASK_PATH)


def harness_headers() -> dict[str, str]:
    headers = {"Content-Type": "application/json"}
    if HARNESS_TOKEN:
        headers["Authorization"] = f"Bearer {HARNESS_TOKEN}"
    return headers


async def relay_json(method: str, target: str, *, payload: dict[str, Any] | None = None) -> HarnessResult:
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(60.0, connect=10.0)) as client:
            response = await client.request(method, target, json=payload, headers=harness_headers())
    except httpx.HTTPError as exc:
        return HarnessResult(ok=False, message=f"Harness connection failed: {exc.__class__.__name__}")

    content_type = response.headers.get("content-type", "")
    if "application/json" in content_type.lower():
        result: Any = response.json()
    else:
        result = response.text[:20000]

    return HarnessResult(
        ok=response.is_success,
        status_code=response.status_code,
        result=result,
        message=None if response.is_success else "Harness returned an error.",
    )


async def run_hermes_and_wait(payload: dict[str, Any]) -> HarnessResult:
    """Start a Hermes run and return its final output when it finishes quickly.

    Hermes POST /v1/runs is intentionally asynchronous. AKUJI Live needs a single
    function response, so the bridge polls the run for a bounded period. Long runs
    are returned with their run_id so they can still be inspected or stopped.
    """

    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(60.0, connect=10.0)) as client:
            start = await client.post(
                f"{HARNESS_BASE_URL}/v1/runs",
                json=payload,
                headers=harness_headers(),
            )
            start_payload = start.json() if "application/json" in start.headers.get("content-type", "").lower() else {}
            if not start.is_success:
                return HarnessResult(
                    ok=False,
                    status_code=start.status_code,
                    result=start_payload or start.text[:20000],
                    message="Hermes could not start the operator run.",
                )

            run_id = str(start_payload.get("run_id") or "").strip()
            if not run_id:
                return HarnessResult(
                    ok=False,
                    status_code=start.status_code,
                    result=start_payload,
                    message="Hermes started without returning a run_id.",
                )

            loop = asyncio.get_running_loop()
            deadline = loop.time() + HERMES_WAIT_SECONDS
            latest: dict[str, Any] = {"run_id": run_id, "status": "started"}

            while loop.time() < deadline:
                await asyncio.sleep(HERMES_POLL_SECONDS)
                poll = await client.get(
                    f"{HARNESS_BASE_URL}/v1/runs/{run_id}",
                    headers=harness_headers(),
                )
                if not poll.is_success:
                    return HarnessResult(
                        ok=False,
                        status_code=poll.status_code,
                        result={"run_id": run_id},
                        message="Hermes started the run, but AKUJI could not read its status.",
                    )

                latest = poll.json()
                run_status = str(latest.get("status") or "").strip().lower()

                if run_status == "completed":
                    output = latest.get("output")
                    if output is None or (isinstance(output, str) and not output.strip()):
                        return HarnessResult(
                            ok=False,
                            status_code=poll.status_code,
                            result={"run_id": run_id, "status": run_status, "details": latest},
                            message=(
                                "Hermes finished without an output. The operator model/provider may not be authenticated yet."
                            ),
                        )
                    return HarnessResult(
                        ok=True,
                        status_code=poll.status_code,
                        result={"run_id": run_id, "status": run_status, "output": output},
                    )

                if run_status in {"failed", "cancelled", "canceled"}:
                    return HarnessResult(
                        ok=False,
                        status_code=poll.status_code,
                        result=latest,
                        message=f"Hermes operator run {run_status}.",
                    )

                if run_status in {
                    "pending_approval",
                    "awaiting_approval",
                    "requires_approval",
                    "requires_action",
                }:
                    return HarnessResult(
                        ok=False,
                        status_code=poll.status_code,
                        result=latest,
                        message="Hermes is waiting for approval before it can continue this operator run.",
                    )

            return HarnessResult(
                ok=True,
                status_code=202,
                result={
                    "run_id": run_id,
                    "status": str(latest.get("status") or "running"),
                    "message": "The Hermes run is still working in the background.",
                },
                message="The operator run is still working; use the run id to check it later.",
            )
    except (httpx.HTTPError, ValueError) as exc:
        return HarnessResult(ok=False, message=f"Hermes connection failed: {exc.__class__.__name__}")


def execution_guard(dry_run: bool) -> None:
    if not dry_run and not ALLOW_EXECUTION:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Bridge execution is disabled. Dry-run/research mode is still available.",
        )


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/v1/status", response_model=BridgeStatus, dependencies=[Depends(require_bridge_auth)])
def bridge_status() -> BridgeStatus:
    return BridgeStatus(
        bridge="ready",
        authenticated=True,
        harness_kind=HARNESS_KIND,
        harness_configured=harness_ready(),
        execution_enabled=ALLOW_EXECUTION,
    )


@app.post("/v1/harness/task", response_model=HarnessResult, dependencies=[Depends(require_bridge_auth)])
async def run_harness_task(task: HarnessTask) -> HarnessResult:
    if not harness_ready():
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="No AKUJI sandbox/harness endpoint is configured yet.",
        )

    execution_guard(task.dry_run)

    if HARNESS_KIND == "hermes":
        instructions = (task.context or "").strip()
        if task.dry_run:
            dry_run_rule = (
                "AKUJI DRY RUN: research, inspect, reason, and propose actions, but do not make destructive, "
                "irreversible, financial, posting/sending, credential, account-security, or external state-changing actions."
            )
            instructions = f"{dry_run_rule}\n\n{instructions}".strip()

        payload: dict[str, Any] = {
            "model": "hermes-agent",
            "input": task.instruction,
        }
        if instructions:
            payload["instructions"] = instructions
        if task.session_id:
            payload["session_id"] = task.session_id

        return await run_hermes_and_wait(payload)

    target = f"{HARNESS_BASE_URL}/{HARNESS_TASK_PATH.lstrip('/')}"
    payload = {
        "instruction": task.instruction,
        "context": task.context,
        "dry_run": task.dry_run,
    }
    return await relay_json("POST", target, payload=payload)


@app.get("/v1/hermes/runs/{run_id}", response_model=HarnessResult, dependencies=[Depends(require_bridge_auth)])
async def get_hermes_run(run_id: str) -> HarnessResult:
    if HARNESS_KIND != "hermes" or not harness_ready():
        raise HTTPException(status_code=503, detail="Hermes harness is not configured.")
    return await relay_json("GET", f"{HARNESS_BASE_URL}/v1/runs/{run_id}")


@app.post("/v1/hermes/runs/{run_id}/stop", response_model=HarnessResult, dependencies=[Depends(require_bridge_auth)])
async def stop_hermes_run(run_id: str) -> HarnessResult:
    if HARNESS_KIND != "hermes" or not harness_ready():
        raise HTTPException(status_code=503, detail="Hermes harness is not configured.")
    return await relay_json("POST", f"{HARNESS_BASE_URL}/v1/runs/{run_id}/stop", payload={})
