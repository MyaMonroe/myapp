from __future__ import annotations

import os
import secrets
from typing import Any

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, status
from pydantic import BaseModel, Field


APP_NAME = "AKUJI Direct Tool Bridge"
BRIDGE_TOKEN = os.getenv("AKUJI_BRIDGE_TOKEN", "").strip()
PICSART_API_KEY = os.getenv("PICSART_API_KEY", "").strip()
ALLOW_EXECUTION = os.getenv("AKUJI_BRIDGE_ALLOW_EXECUTION", "false").strip().lower() == "true"

app = FastAPI(title=APP_NAME, version="1.1.0", docs_url=None, redoc_url=None)


class BridgeStatus(BaseModel):
    bridge: str
    authenticated: bool
    operator_mode: str
    execution_enabled: bool
    available_tools: list[str]


class OperatorToolRequest(BaseModel):
    tool: str = Field(min_length=1, max_length=120)
    arguments: dict[str, Any] = Field(default_factory=dict)
    dry_run: bool = True


class OperatorToolResult(BaseModel):
    ok: bool
    executed: bool
    tool: str
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


def available_tools() -> list[str]:
    tools = ["bridge_echo"]
    if PICSART_API_KEY:
        tools.append("picsart_remove_background")
    return tools


def execution_guard(dry_run: bool) -> None:
    if not dry_run and not ALLOW_EXECUTION:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="AKUJI operator execution is disabled. Dry-run is still available.",
        )


def require_url_argument(arguments: dict[str, Any], name: str) -> str:
    value = str(arguments.get(name) or "").strip()
    if not value.startswith(("https://", "http://")):
        raise HTTPException(status_code=422, detail=f"{name} must be an http(s) URL.")
    return value


async def picsart_remove_background(arguments: dict[str, Any]) -> OperatorToolResult:
    if not PICSART_API_KEY:
        raise HTTPException(status_code=503, detail="Picsart is not connected to the AKUJI bridge.")

    image_url = require_url_argument(arguments, "image_url")
    output_format = str(arguments.get("format") or "PNG").strip().upper()
    if output_format not in {"PNG", "JPG", "WEBP"}:
        raise HTTPException(status_code=422, detail="format must be PNG, JPG, or WEBP.")

    files = {
        "image_url": (None, image_url),
        "output_type": (None, "cutout"),
        "format": (None, output_format),
    }
    headers = {
        "accept": "application/json",
        "X-Picsart-API-Key": PICSART_API_KEY,
    }

    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(120.0, connect=15.0)) as client:
            response = await client.post(
                "https://api.picsart.io/tools/1.0/removebg",
                headers=headers,
                files=files,
            )
    except httpx.HTTPError as exc:
        return OperatorToolResult(
            ok=False,
            executed=True,
            tool="picsart_remove_background",
            message=f"Picsart connection failed: {exc.__class__.__name__}",
        )

    content_type = response.headers.get("content-type", "")
    if "application/json" in content_type.lower():
        result: Any = response.json()
    else:
        result = response.text[:20000]

    return OperatorToolResult(
        ok=response.is_success,
        executed=True,
        tool="picsart_remove_background",
        status_code=response.status_code,
        result=result,
        message=None if response.is_success else "Picsart returned an error.",
    )


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/v1/status", response_model=BridgeStatus, dependencies=[Depends(require_bridge_auth)])
def bridge_status() -> BridgeStatus:
    return BridgeStatus(
        bridge="ready",
        authenticated=True,
        operator_mode="direct",
        execution_enabled=ALLOW_EXECUTION,
        available_tools=available_tools(),
    )


@app.post(
    "/v1/operator/tool",
    response_model=OperatorToolResult,
    dependencies=[Depends(require_bridge_auth)],
)
async def run_operator_tool(request: OperatorToolRequest) -> OperatorToolResult:
    tool = request.tool.strip().lower()
    execution_guard(request.dry_run)

    if tool not in {"bridge_echo", "picsart_remove_background"}:
        raise HTTPException(status_code=404, detail=f"Unknown AKUJI operator tool: {tool}")

    if tool == "picsart_remove_background" and not PICSART_API_KEY:
        raise HTTPException(status_code=503, detail="Picsart is not connected to the AKUJI bridge.")

    if request.dry_run:
        return OperatorToolResult(
            ok=True,
            executed=False,
            tool=tool,
            result={
                "arguments": request.arguments,
                "execution_enabled": ALLOW_EXECUTION,
                "note": "Dry-run only. No external provider call was made and no external state changed.",
            },
            message="AKUJI validated the tool request without executing it.",
        )

    if tool == "bridge_echo":
        return OperatorToolResult(
            ok=True,
            executed=True,
            tool=tool,
            result={"echo": request.arguments},
            message="AKUJI bridge echo completed locally.",
        )

    if tool == "picsart_remove_background":
        return await picsart_remove_background(request.arguments)

    raise HTTPException(status_code=404, detail=f"Unknown AKUJI operator tool: {tool}")
