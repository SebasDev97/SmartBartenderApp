"""The machine: slot table, LED show, and one pour at a time.

The Pi is the source of truth for a pour in progress. The app posts a plan once and then
renders whatever arrives over the WebSocket — it never runs its own timer. That is what
lets a pour survive the app being backgrounded, killed, or reinstalled mid-drink.
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections import OrderedDict
from typing import Optional

from .config import FIRMWARE, MAX_ITEM_ML, MAX_JOG_SECONDS, Config
from .events import EventBus, now_ms
from .leds import LedController
from .models import (
    ErrorCode,
    Fault,
    JobStatus,
    LedState,
    MachineState,
    MachineStatus,
    PourJob,
    PourRequest,
    PourStep,
    Slot,
    SlotAssignment,
    StepKind,
)

log = logging.getLogger("bartender.machine")

#: How often a running pour recomputes and (every other tick) publishes progress.
TICK_SECONDS = 0.1

#: Fixed pause for the non-pour beats — matches the app's original 1100 ms step feel.
BEAT_SECONDS = 1.1

#: Jobs kept after they end, so a returning app can still GET /pours/{jobId}.
JOB_HISTORY = 20

#: Share of `progress` reserved for the non-pour steps. The pours split the rest by volume.
NON_POUR_WEIGHT = 0.15


class MachineError(Exception):
    """An error the API layer turns into the documented error body."""

    def __init__(self, code: ErrorCode, message: str, status: int, *, with_job: bool = False) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status
        self.with_job = with_job


class Machine:
    def __init__(self, config: Config, backend, bus: EventBus, speed: float = 1.0) -> None:
        self._config = config
        self._backend = backend
        self._bus = bus
        self._speed = max(speed, 0.01)
        self._started_at = time.monotonic()

        self._slots: list[Slot] = [
            Slot(pump=p.pump, bottle_id=p.bottle_id, ml_per_second=p.ml_per_s) for p in config.pumps
        ]
        self._leds = LedController(backend)
        self._jobs: OrderedDict[str, PourJob] = OrderedDict()
        self._current: Optional[PourJob] = None
        self._task: Optional[asyncio.Task] = None
        self._abort = asyncio.Event()
        self._lock = asyncio.Lock()
        self._fault: Optional[Fault] = None

    # ------------------------------------------------------------------ lifecycle

    async def start(self) -> None:
        await self._backend.start()
        await self._leds.start()

    async def stop(self) -> None:
        self._abort.set()
        if self._task is not None:
            self._task.cancel()
        await self._leds.stop()
        await self._backend.stop_all()
        await self._backend.close()

    # ------------------------------------------------------------------ state

    @property
    def state(self) -> MachineState:
        if self._fault is not None:
            return MachineState.FAULT
        if self._current is not None and not self._current.status.terminal:
            return MachineState.BUSY
        return MachineState.IDLE

    @property
    def current_job(self) -> Optional[PourJob]:
        if self._current is not None and not self._current.status.terminal:
            return self._current
        return None

    def job(self, job_id: str) -> Optional[PourJob]:
        return self._jobs.get(job_id)

    def status(self) -> MachineStatus:
        info = self._backend.info()
        return MachineStatus(
            machine_id=self._config.machine.id,
            name=self._config.machine.name,
            firmware=FIRMWARE,
            backend=info.name,
            state=self.state,
            pump_count=len(self._slots),
            max_pour_ml=self._config.machine.max_pour_ml,
            uptime_s=int(time.monotonic() - self._started_at),
            slots=[slot.model_copy() for slot in self._slots],
            led=self._leds.state,
            current_job=self.current_job,
            fault=self._fault,
        )

    @property
    def slots(self) -> list[Slot]:
        return [slot.model_copy() for slot in self._slots]

    def slot_for_bottle(self, bottle_id: str) -> Optional[Slot]:
        return next((s for s in self._slots if s.bottle_id == bottle_id), None)

    # ------------------------------------------------------------------ commands

    async def set_slots(self, assignments: list[SlotAssignment]) -> MachineStatus:
        self._require_idle()

        known = {slot.pump for slot in self._slots}
        seen: set[int] = set()
        for assignment in assignments:
            if assignment.pump not in known:
                raise MachineError(
                    ErrorCode.UNKNOWN_BOTTLE,
                    f"No pump {assignment.pump} on this machine",
                    422,
                )
            if assignment.pump in seen:
                raise MachineError(ErrorCode.UNKNOWN_BOTTLE, f"Pump {assignment.pump} listed twice", 422)
            seen.add(assignment.pump)

        by_pump = {a.pump: a.bottle_id for a in assignments}
        for slot in self._slots:
            if slot.pump in by_pump:
                slot.bottle_id = by_pump[slot.pump]

        log.info("slots set to %s", {s.pump: s.bottle_id for s in self._slots})
        self._bus.publish("slots", {"slots": [s.model_dump(by_alias=True) for s in self._slots]})
        self._publish_snapshot()
        return self.status()

    async def set_led(self, **changes) -> LedState:
        state = await self._leds.apply(**changes)
        self._bus.publish("led", state.model_dump(by_alias=True))
        self._publish_snapshot()
        return state

    async def jog(self, pump: int, seconds: float) -> float:
        self._require_idle()
        if self._config.pump(pump) is None:
            raise MachineError(ErrorCode.UNKNOWN_BOTTLE, f"No pump {pump} on this machine", 422)
        if seconds <= 0 or seconds > MAX_JOG_SECONDS:
            raise MachineError(
                ErrorCode.VOLUME_OUT_OF_RANGE,
                f"Jog must be between 0 and {MAX_JOG_SECONDS:.0f} seconds",
                422,
            )

        log.info("jogging pump %d for %.1fs", pump, seconds)
        await self._backend.start_pump(pump)
        try:
            await asyncio.sleep(seconds)
        finally:
            await self._backend.stop_pump(pump)
        return seconds

    async def start_pour(self, request: PourRequest) -> tuple[PourJob, bool]:
        """Returns (job, created). `created` is False when this jobId was already known."""
        async with self._lock:
            existing = self._jobs.get(request.job_id)
            if existing is not None:
                return existing, False  # idempotent replay — never pour twice

            if self.current_job is not None:
                raise MachineError(
                    ErrorCode.MACHINE_BUSY, "A pour is already running", 409, with_job=True
                )
            if not any(slot.bottle_id for slot in self._slots):
                raise MachineError(ErrorCode.NOT_CONFIGURED, "No bottles are loaded", 503)

            job = self._plan(request)
            self._remember(job)
            self._current = job
            self._abort = asyncio.Event()
            self._task = asyncio.create_task(self._run(job), name=f"pour-{job.job_id[:8]}")
            return job, True

    async def abort(self, job_id: str) -> PourJob:
        job = self._jobs.get(job_id)
        if job is None:
            raise MachineError(ErrorCode.UNKNOWN_BOTTLE, f"No job {job_id}", 404)
        if job.status.terminal:
            return job

        log.info("aborting job %s", job_id)
        job.status = JobStatus.ABORTING
        self._abort.set()
        self._publish_job(job)
        return job

    # ------------------------------------------------------------------ planning

    def _plan(self, request: PourRequest) -> PourJob:
        steps: list[PourStep] = []
        total_ml = 0.0

        steps.append(
            PourStep(
                index=0,
                kind=StepKind.GLASS,
                label="Positioning glass",
                detail=request.glass or "Cocktail glass",
            )
        )

        for item in request.items:
            if item.ml <= 0 or item.ml > MAX_ITEM_ML:
                raise MachineError(
                    ErrorCode.VOLUME_OUT_OF_RANGE,
                    f"{item.ingredient_name}: {item.ml:.0f} ml is outside 0–{MAX_ITEM_ML:.0f} ml",
                    422,
                )
            slot = self.slot_for_bottle(item.bottle_id)
            if slot is None:
                raise MachineError(
                    ErrorCode.SLOT_EMPTY,
                    f"{item.ingredient_name} ({item.bottle_id}) is not loaded",
                    422,
                )
            total_ml += item.ml
            steps.append(
                PourStep(
                    index=len(steps),
                    kind=StepKind.POUR,
                    label=f"Pouring {item.ingredient_name}",
                    detail=f"{item.ml:.0f} ml",
                    pump=slot.pump,
                    ml=item.ml,
                    dispensed_ml=0.0,
                )
            )

        if total_ml > self._config.machine.max_pour_ml:
            raise MachineError(
                ErrorCode.VOLUME_OUT_OF_RANGE,
                f"{total_ml:.0f} ml exceeds the {self._config.machine.max_pour_ml:.0f} ml glass",
                422,
            )

        if any(step.kind is StepKind.POUR for step in steps):
            steps.append(
                PourStep(index=len(steps), kind=StepKind.MIX, label="Mixing", detail="Stirring the blend")
            )

        for manual in request.manual_steps:
            steps.append(
                PourStep(
                    index=len(steps),
                    kind=StepKind.MANUAL,
                    label=manual,
                    detail="Add this yourself",
                )
            )

        steps.append(
            PourStep(
                index=len(steps),
                kind=StepKind.FINISH,
                label="Finishing touch",
                detail="Garnish and serve",
            )
        )

        return PourJob(
            job_id=request.job_id,
            drink_id=request.drink_id,
            drink_name=request.drink_name,
            status=JobStatus.QUEUED,
            steps=steps,
            total_ml=total_ml,
        )

    @staticmethod
    def _weights(job: PourJob) -> list[float]:
        """Per-step share of `progress`, weighted by volume so a garnish isn't a fifth of the work."""
        pours = [s for s in job.steps if s.kind is StepKind.POUR]
        others = len(job.steps) - len(pours)

        if not pours:
            return [1.0 / len(job.steps)] * len(job.steps)

        other_each = (NON_POUR_WEIGHT / others) if others else 0.0
        pour_budget = 1.0 - other_each * others
        return [
            (pour_budget * (step.ml or 0.0) / job.total_ml) if step.kind is StepKind.POUR else other_each
            for step in job.steps
        ]

    # ------------------------------------------------------------------ execution

    async def _run(self, job: PourJob) -> None:
        weights = self._weights(job)
        job.status = JobStatus.RUNNING
        job.started_at_ms = now_ms()
        await self._leds.begin_pour()
        self._publish_job(job)
        self._publish_snapshot()

        try:
            for step in job.steps:
                if self._abort.is_set():
                    break
                job.current_step_index = step.index
                if step.kind is StepKind.POUR:
                    await self._pour_step(job, step, weights)
                else:
                    await self._beat(job, step, weights)

            if self._abort.is_set():
                job.status = JobStatus.ABORTED
                job.error = Fault(
                    code=ErrorCode.ABORTED_BY_USER, message="Stopped from the app", recoverable=True
                )
            else:
                job.status = JobStatus.FINISHED
                job.progress = 1.0
                job.current_step_index = len(job.steps) - 1

        except asyncio.CancelledError:
            job.status = JobStatus.ABORTED
            raise
        except Exception as exc:  # noqa: BLE001 — any failure must still stop the pumps
            log.exception("pour %s failed", job.job_id)
            job.status = JobStatus.FAILED
            job.error = Fault(code=ErrorCode.PUMP_FAULT, message=str(exc))
            self._fault = job.error
            self._bus.publish("fault", job.error.model_dump(by_alias=True))
        finally:
            # The single most important line in this service: whatever happened above —
            # an exception, a cancellation, a client vanishing — every pump stops.
            await self._backend.stop_all()
            job.finished_at_ms = now_ms()
            await self._leds.end_pour()
            self._publish_job(job)
            self._publish_snapshot()
            log.info("job %s -> %s", job.job_id, job.status.value)

    async def _pour_step(self, job: PourJob, step: PourStep, weights: list[float]) -> None:
        target = step.ml or 0.0
        rate = self._rate_for(step.pump)
        duration = (target / rate) / self._speed
        log.info("pump %d: %.1f ml over %.1fs", step.pump, target, duration)

        loop = asyncio.get_running_loop()
        started = loop.time()
        tick = 0
        await self._backend.start_pump(step.pump)
        try:
            while True:
                fraction = 1.0 if duration <= 0 else min((loop.time() - started) / duration, 1.0)
                step.dispensed_ml = round(target * fraction, 2)
                self._recompute(job, weights, fraction)
                tick += 1
                if tick % 2 == 0:
                    self._publish_job(job)
                if fraction >= 1.0 or self._abort.is_set():
                    break
                await asyncio.sleep(TICK_SECONDS)
        finally:
            await self._backend.stop_pump(step.pump)

        self._publish_job(job)
        await self._settle()

    async def _beat(self, job: PourJob, step: PourStep, weights: list[float]) -> None:
        self._recompute(job, weights, 0.0)
        self._publish_job(job)
        await self._sleep(BEAT_SECONDS)
        self._recompute(job, weights, 1.0)

    async def _settle(self) -> None:
        await self._sleep(0.3)

    async def _sleep(self, seconds: float) -> None:
        """Sleep, but wake early on abort so a stop feels immediate."""
        try:
            await asyncio.wait_for(self._abort.wait(), timeout=seconds / self._speed)
        except (TimeoutError, asyncio.TimeoutError):
            pass

    def _recompute(self, job: PourJob, weights: list[float], fraction: float) -> None:
        done = sum(weights[: job.current_step_index])
        job.progress = round(min(done + weights[job.current_step_index] * fraction, 1.0), 4)
        job.dispensed_ml = round(sum(s.dispensed_ml or 0.0 for s in job.steps), 2)
        self._leds.set_pour_progress(job.progress)

    def _rate_for(self, pump: Optional[int]) -> float:
        slot = next((s for s in self._slots if s.pump == pump), None)
        return slot.ml_per_second if slot and slot.ml_per_second > 0 else 12.5

    # ------------------------------------------------------------------ internals

    def _require_idle(self) -> None:
        if self.current_job is not None:
            raise MachineError(ErrorCode.MACHINE_BUSY, "A pour is already running", 409, with_job=True)

    def _remember(self, job: PourJob) -> None:
        self._jobs[job.job_id] = job
        while len(self._jobs) > JOB_HISTORY:
            self._jobs.popitem(last=False)

    def _publish_job(self, job: PourJob) -> None:
        self._bus.publish("pour", job.model_dump(by_alias=True))

    def _publish_snapshot(self) -> None:
        self._bus.publish("snapshot", self.status().model_dump(by_alias=True))
