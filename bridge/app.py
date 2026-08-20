from __future__ import annotations

import os
import secrets
from typing import Any

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, status
from pydantic import BaseModel, Field


APP_NAME = "AKUJI Tool Bridge"
BRIDGE_TOKEN = os.getenv("AKUJI_BRIDGE_TOKEN", "").strip()
HARNESS_BASE_URL = os.getenv("AKUJI_HARNESS_BASE_URL", "").strip().rstrip("/")
HARNESS_TASK_PATH = os.getenv("AKUJI_HARNESS_TASK_PATH", "").strip()
HARNESS_TOKEN = os.getenv("AKUJI_HARNESS_TOKEN", "").strip()
ALLOW_EXECUTION = os.getenv("AKUJI_BRIDGE_ALLOW_EXECUTION", "false").strip().lower() == "true"

app = FastAPI(title=APP_NAME, version="0.1.0", docs_url=None, redoc_url=None)


class BridgeStatus(BaseModel):
    bridge: str
    authenticated: bool
    harness_configured: bool
    execution_enabled: bool


class HarnessTask(BaseModel):
    instruction: str = Field(min_length=1, max_length=12000)
    context: str | None = Field(default=None, max_length=24000)
    dry_run: bool = True


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
    return bool(HARNESS_BASE_URL and HARNESS_TASK_PATH)


@app.get("/health")
def health() -> dict[str, str]:
    # Deliberately reveals no connector names, secrets, or account details.
    return {"status": "ok"}


@app.get("/v1/status", response_model=BridgeStatus, dependencies=[Depends(require_bridge_auth)])
def bridge_status() -> BridgeStatus:
    return BridgeStatus(
        bridge="ready",
        authenticated=True,
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

    if not task.dry_run and not ALLOW_EXECUTION:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Bridge execution is disabled. Dry-run/research mode is still available.",
        )

    target = f"{HARNESS_BASE_URL}/{HARNESS_TASK_PATH.lstrip('/')}"
    headers = {"Content-Type": "application/json"}
    if HARNESS_TOKEN:
        headers["Authorization"] = f"Bearer {HARNESS_TOKEN}"

    payload = {
        "instruction": task.instruction,
        "context": task.context,
        "dry_run": task.dry_run,
    }

    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(60.0, connect=10.0)) as client:
            response = await client.post(target, json=payload, headers=headers)
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
