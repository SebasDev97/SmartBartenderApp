"""HTTP + WebSocket surface. The routes here are exactly what API.md documents."""

from __future__ import annotations

import asyncio
import logging
from typing import Optional

from fastapi import APIRouter, Body, FastAPI, Header, Request, Response, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse

from .config import FIRMWARE, Config
from .events import HEARTBEAT_SECONDS, EventBus
from .machine import Machine, MachineError
from .models import (
    ApiError,
    ErrorCode,
    ErrorResponse,
    Health,
    JogRequest,
    JogResponse,
    LedMode,
    LedRequest,
    LedState,
    MachineStatus,
    PourJob,
    PourRequest,
    SlotsRequest,
    SlotsResponse,
)

log = logging.getLogger("bartender.api")


def _error(machine: Machine, exc: MachineError) -> JSONResponse:
    body = ErrorResponse(
        error=ApiError(code=exc.code, message=exc.message),
        current_job=machine.current_job if exc.with_job else None,
    )
    return JSONResponse(status_code=exc.status, content=body.model_dump(by_alias=True, mode="json"))


def create_app(config: Config, machine: Machine, bus: EventBus) -> FastAPI:
    app = FastAPI(title=config.machine.name, version=FIRMWARE, docs_url="/docs")
    app.state.machine = machine
    app.state.config = config

    @app.exception_handler(MachineError)
    async def _handle(request: Request, exc: MachineError):  # noqa: ARG001
        return _error(machine, exc)

    @app.get("/healthz", response_model=Health)
    async def healthz() -> Health:
        """Deliberately outside /api/v1 and free of auth — the app's Test button hits this."""
        return Health(ok=True, machine_id=config.machine.id, firmware=FIRMWARE)

    api = APIRouter(prefix="/api/v1")

    @api.get("/status", response_model=MachineStatus)
    async def status() -> MachineStatus:
        return machine.status()

    @api.get("/slots", response_model=SlotsResponse)
    async def get_slots() -> SlotsResponse:
        return SlotsResponse(slots=machine.slots)

    @api.put("/slots", response_model=MachineStatus)
    async def put_slots(body: SlotsRequest) -> MachineStatus:
        return await machine.set_slots(body.slots)

    @api.post("/pours", response_model=PourJob)
    async def start_pour(
        body: PourRequest,
        response: Response,
        idempotency_key: Optional[str] = Header(default=None, alias="Idempotency-Key"),  # noqa: ARG001
    ) -> PourJob:
        job, created = await machine.start_pour(body)
        # 201 for a new pour, 200 for a replay of a jobId we already have. Either way the
        # app gets the same job back, so a retry over a flaky link is harmless.
        response.status_code = 201 if created else 200
        return job

    @api.get("/pours/current")
    async def current_pour():
        job = machine.current_job
        if job is None:
            return Response(status_code=204)
        return JSONResponse(content=job.model_dump(by_alias=True, mode="json"))

    @api.get("/pours/{job_id}", response_model=PourJob)
    async def get_pour(job_id: str) -> PourJob:
        job = machine.job(job_id)
        if job is None:
            raise MachineError(ErrorCode.UNKNOWN_BOTTLE, f"No job {job_id}", 404)
        return job

    @api.post("/pours/{job_id}/abort", response_model=PourJob)
    async def abort_pour(job_id: str, response: Response) -> PourJob:
        job = await machine.abort(job_id)
        response.status_code = 200 if job.status.terminal else 202
        return job

    @api.get("/led", response_model=LedState)
    async def get_led() -> LedState:
        return machine.status().led

    @api.put("/led", response_model=LedState)
    async def put_led(body: LedRequest) -> LedState:
        if body.mode is LedMode.POUR:
            raise MachineError(ErrorCode.MACHINE_BUSY, "'pour' mode belongs to the machine", 409)
        return await machine.set_led(
            enabled=body.enabled,
            mode=body.mode,
            color_hex=body.color_hex,
            brightness=body.brightness,
            cycle_millis=body.cycle_millis,
        )

    @api.post("/pumps/{pump}/jog", response_model=JogResponse)
    async def jog(pump: int, body: JogRequest = Body(default=JogRequest(seconds=5.0))) -> JogResponse:
        seconds = await machine.jog(pump, body.seconds)
        return JogResponse(pump=pump, seconds=seconds)

    @api.websocket("/events")
    async def events(websocket: WebSocket) -> None:
        await websocket.accept()
        subscriber = bus.subscribe()
        # A fresh snapshot first: this is what lets a reconnecting app re-attach to a pour
        # that is already half-poured.
        subscriber.offer("snapshot", machine.status().model_dump(by_alias=True, mode="json"))
        log.info("client connected (%d total)", bus.subscriber_count)
        try:
            while True:
                try:
                    event = await asyncio.wait_for(subscriber.queue.get(), timeout=HEARTBEAT_SECONDS)
                except (TimeoutError, asyncio.TimeoutError):
                    subscriber.offer("heartbeat", {"uptimeS": machine.status().uptime_s})
                    event = await subscriber.queue.get()
                await websocket.send_text(event.model_dump_json(by_alias=True))
        except WebSocketDisconnect:
            pass
        except Exception:  # noqa: BLE001 — one bad client must not disturb the pour
            log.exception("websocket closed unexpectedly")
        finally:
            bus.unsubscribe(subscriber)
            log.info("client disconnected (%d left)", bus.subscriber_count)

    if config.server.token:

        @app.middleware("http")
        async def require_token(request: Request, call_next):
            if request.url.path.startswith("/api/v1"):
                if request.headers.get("X-Bartender-Token") != config.server.token:
                    return JSONResponse(
                        status_code=401,
                        content={"error": {"code": "NOT_CONFIGURED", "message": "Bad or missing token"}},
                    )
            return await call_next(request)

    app.include_router(api)
    return app
