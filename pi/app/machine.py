"""The machine: slot table, LED show, the sensor, and one operation at a time.

The Pi is the source of truth for a pour in progress. The app posts a plan once and then
renders whatever arrives over the WebSocket — it never runs its own timer. That is what
lets a pour survive the app being backgrounded, killed, or reinstalled mid-drink.

A calibration run and a cleaning run work the same way: the app starts one, and the Pi pushes
the whole run object as it goes. Pours, calibration runs and cleaning runs exclude each other.
"""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from collections import OrderedDict
from typing import Optional

from .calibration import CalibrationStore
from .config import FIRMWARE, MAX_CLEANING_ROUNDS, MAX_ITEM_ML, MAX_JOG_SECONDS, Config
from .events import EventBus, now_ms
from .lcd import Lcd
from .leds import LedController
from .models import (
    CalibrationPhase,
    CalibrationResult,
    CalibrationRun,
    CalibrationStatus,
    CleaningPhase,
    CleaningRun,
    CleaningStatus,
    ErrorCode,
    Fault,
    JobStatus,
    LedState,
    MachineState,
    MachineStatus,
    PourJob,
    PourRequest,
    PourStep,
    SensorInfo,
    SensorReading,
    Slot,
    SlotAssignment,
    StepKind,
)
from .sensor import in_glass_band, measure, volume_ml

log = logging.getLogger("bartender.machine")

#: How often a running pour recomputes and (every other tick) publishes progress.
TICK_SECONDS = 0.1

#: Fixed pause for the non-pour beats — matches the app's original 1100 ms step feel.
BEAT_SECONDS = 1.1

#: Jobs kept after they end, so a returning app can still GET /pours/{jobId}.
JOB_HISTORY = 20

#: Share of `progress` reserved for the non-pour steps. The pours split the rest by volume.
NON_POUR_WEIGHT = 0.15

#: A sensor volume is believed only up to this multiple of what the pump time predicts (plus
#: MEASURE_SLACK_ML). Anything above is a hand, a moved glass or a splash, not a pour.
MEASURE_TOLERANCE = 2.0
MEASURE_SLACK_ML = 10.0


class MachineError(Exception):
    """An error the API layer turns into the documented error body."""

    def __init__(self, code: ErrorCode, message: str, status: int, *, with_job: bool = False) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status = status
        self.with_job = with_job


class SensorError(Exception):
    """The sensor could not give the reading a calibration needs. The user can usually fix it."""


class Machine:
    def __init__(
        self,
        config: Config,
        backend,
        bus: EventBus,
        speed: float = 1.0,
        calibration: Optional[CalibrationStore] = None,
    ) -> None:
        self._config = config
        self._backend = backend
        self._bus = bus
        self._speed = max(speed, 0.01)
        self._started_at = time.monotonic()
        self._calibration = calibration or CalibrationStore(config.calibration.path)
        self._default_reference = backend.info().default_reference_cm

        # A pump's calibrated rate beats the guess in config.yaml.
        self._slots: list[Slot] = [
            Slot(
                pump=p.pump,
                bottle_id=p.bottle_id,
                ml_per_second=self._calibration.ml_per_second(p.pump) or p.ml_per_s,
            )
            for p in config.pumps
        ]
        self._leds = LedController(backend)
        self._lcd = Lcd(backend)
        self._jobs: OrderedDict[str, PourJob] = OrderedDict()
        self._current: Optional[PourJob] = None
        self._task: Optional[asyncio.Task] = None
        self._run_cal: Optional[CalibrationRun] = None
        self._run_clean: Optional[CleaningRun] = None
        self._abort = asyncio.Event()
        self._lock = asyncio.Lock()
        self._fault: Optional[Fault] = None

    # ------------------------------------------------------------------ lifecycle

    async def start(self) -> None:
        await self._backend.start()
        await self._leds.start()
        await self._lcd.show("idle")

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
        if (
            self.current_job is not None
            or self.current_calibration is not None
            or self.current_cleaning is not None
        ):
            return MachineState.BUSY
        return MachineState.IDLE

    @property
    def current_job(self) -> Optional[PourJob]:
        if self._current is not None and not self._current.status.terminal:
            return self._current
        return None

    @property
    def current_calibration(self) -> Optional[CalibrationRun]:
        if self._run_cal is not None and not self._run_cal.status.terminal:
            return self._run_cal
        return None

    @property
    def last_calibration(self) -> Optional[CalibrationRun]:
        """The running calibration, or the one that ran last."""
        return self._run_cal

    @property
    def current_cleaning(self) -> Optional[CleaningRun]:
        if self._run_clean is not None and not self._run_clean.status.terminal:
            return self._run_clean
        return None

    @property
    def last_cleaning(self) -> Optional[CleaningRun]:
        """The running cleaning, or the one that ran last."""
        return self._run_clean

    @property
    def reference_cm(self) -> Optional[float]:
        return self._calibration.reference_cm or self._default_reference

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
            sensor=SensorInfo(
                reference_cm=self.reference_cm,
                glass_diameter_mm=self._config.sensor.glass_diameter_mm,
                calibrated_at_ms=self._calibration.calibrated_at_ms,
            ),
            calibration=self.current_calibration,
            cleaning=self.current_cleaning,
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
        self._publish_slots()
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
        await self._lcd.show("jog", pump=pump, seconds=seconds)
        await self._backend.start_pump(pump)
        try:
            await asyncio.sleep(seconds)
        finally:
            await self._backend.stop_pump(pump)
            await self._lcd.show("idle")
        return seconds

    async def start_pour(self, request: PourRequest) -> tuple[PourJob, bool]:
        """Returns (job, created). `created` is False when this jobId was already known."""
        async with self._lock:
            existing = self._jobs.get(request.job_id)
            if existing is not None:
                return existing, False  # idempotent replay — never pour twice

            self._require_idle()
            if not any(slot.bottle_id for slot in self._slots):
                raise MachineError(ErrorCode.NOT_CONFIGURED, "No bottles are loaded", 503)
            self._require_reference()

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

    # ------------------------------------------------------------------ sensor

    async def read_sensor(self) -> SensorReading:
        """One raw reading, for the app's calibration screen. Safe to call during a pour."""
        try:
            distance = await self._backend.read_distance()
        except Exception as exc:  # noqa: BLE001
            raise MachineError(ErrorCode.SENSOR_FAULT, f"Sensor unreachable: {exc}", 500) from exc
        reference = self.reference_cm
        return SensorReading(
            distance_cm=round(distance, 2) if distance is not None else None,
            glass_present=in_glass_band(distance, reference, self._config.sensor),
            reference_cm=reference,
        )

    async def measure_reference(self) -> SensorReading:
        """Measure the empty tray and keep it as the zero every glass is detected against."""
        async with self._lock:
            self._require_idle()
            await self._lcd.show("reference")
            try:
                reference = await measure(
                    self._backend.read_distance,
                    self._config.sensor,
                    sleep=lambda s: asyncio.sleep(s / self._speed),
                )
            finally:
                await self._lcd.show("idle")
            if reference is None:
                raise MachineError(ErrorCode.SENSOR_FAULT, "The sensor gave no valid readings", 422)

            reference = round(reference, 2)
            log.info("tray reference measured at %.2f cm", reference)
            self._calibration.set_reference(reference)
            self._publish_snapshot()
            return SensorReading(distance_cm=reference, glass_present=False, reference_cm=reference)

    # ------------------------------------------------------------------ calibration

    async def start_calibration(
        self, pumps: Optional[list[int]] = None, seconds: Optional[float] = None
    ) -> CalibrationRun:
        async with self._lock:
            self._require_idle()
            self._require_reference()

            pumps = self._pick_pumps(pumps)
            seconds = seconds if seconds is not None else self._config.calibration.pump_seconds
            if seconds <= 0 or seconds > MAX_JOG_SECONDS:
                raise MachineError(
                    ErrorCode.VOLUME_OUT_OF_RANGE,
                    f"Calibration time must be between 0 and {MAX_JOG_SECONDS:.0f} seconds",
                    422,
                )

            run = CalibrationRun(run_id=str(uuid.uuid4()), pumps=pumps, started_at_ms=now_ms())
            self._run_cal = run
            self._abort = asyncio.Event()
            self._task = asyncio.create_task(self._calibrate(run, seconds), name="calibration")
            return run

    async def abort_calibration(self) -> CalibrationRun:
        run = self._run_cal
        if run is None:
            raise MachineError(ErrorCode.NOT_CALIBRATED, "No calibration has run", 404)
        if not run.status.terminal:
            log.info("aborting calibration %s", run.run_id)
            run.message = "Stopping…"
            self._abort.set()
            self._publish_calibration(run)
        return run

    async def _calibrate(self, run: CalibrationRun, seconds: float) -> None:
        """Run each pump into the empty glass and turn the rise in level into ml/s.

        The same method as the group's calibration script: pump for a fixed time, let the
        surface settle, measure, and divide the volume by the time the pump actually ran.
        """
        sensor = self._config.sensor
        loop = asyncio.get_running_loop()
        self._publish_snapshot()

        try:
            self._cal_phase(run, CalibrationPhase.WAITING_GLASS, "Place the empty glass under the nozzle")
            await self._lcd.show("cal_glass")
            await self._await_glass()

            level: Optional[float] = None
            for pump in run.pumps:
                if self._abort.is_set():
                    break
                run.current_pump = pump

                self._cal_phase(run, CalibrationPhase.MEASURING, f"Measuring the level before pump {pump}")
                await self._lcd.show("cal_measure")
                if level is None:
                    level = await self._measure()
                if level is None:
                    raise SensorError("The sensor gave no valid readings")
                if level < sensor.min_distance_cm:
                    raise SensorError("The glass is nearly full — empty it and calibrate the rest")

                self._cal_phase(run, CalibrationPhase.PUMPING, f"Pump {pump} runs for {seconds:g} s")
                await self._lcd.show("cal_pump", pump=pump, seconds=seconds)
                started = loop.time()
                await self._backend.start_pump(pump)
                try:
                    await self._sleep(seconds)
                finally:
                    await self._backend.stop_pump(pump)
                ran_for = (loop.time() - started) * self._speed
                if self._abort.is_set():
                    break

                self._cal_phase(run, CalibrationPhase.SETTLING, "Letting the liquid settle")
                await self._sleep(self._config.calibration.settle_seconds)

                self._cal_phase(run, CalibrationPhase.MEASURING, f"Measuring what pump {pump} poured")
                end = await self._measure()
                if end is None:
                    raise SensorError("The sensor gave no valid readings")
                if end >= level:
                    raise SensorError(
                        f"Pump {pump} did not raise the level — is its tube primed and the glass "
                        "under the nozzle?"
                    )

                poured = volume_ml(level, end, sensor.glass_diameter_mm)
                result = CalibrationResult(
                    pump=pump,
                    ml_per_second=round(poured / ran_for, 3),
                    volume_ml=round(poured, 2),
                    seconds=round(ran_for, 3),
                    start_distance_cm=round(level, 2),
                    end_distance_cm=round(end, 2),
                )
                run.results.append(result)
                self._slot(pump).ml_per_second = result.ml_per_second
                log.info("pump %d calibrated: %.3f ml/s", pump, result.ml_per_second)
                await self._lcd.show("cal_result", pump=pump, rate=result.ml_per_second)
                self._publish_calibration(run)
                level = end

            if self._abort.is_set():
                run.status = CalibrationStatus.ABORTED
                run.message = "Stopped"
                run.error = Fault(code=ErrorCode.ABORTED_BY_USER, message="Stopped from the app", recoverable=True)
            else:
                run.status = CalibrationStatus.FINISHED
                run.message = f"Calibrated {len(run.results)} pump{'s' if len(run.results) != 1 else ''}"

        except asyncio.CancelledError:
            run.status = CalibrationStatus.ABORTED
            raise
        except SensorError as exc:
            run.status = CalibrationStatus.FAILED
            run.message = str(exc)
            run.error = Fault(code=ErrorCode.SENSOR_FAULT, message=str(exc), recoverable=True)
        except Exception as exc:  # noqa: BLE001 — any failure must still stop the pumps
            log.exception("calibration %s failed", run.run_id)
            run.status = CalibrationStatus.FAILED
            run.message = str(exc)
            run.error = Fault(code=ErrorCode.PUMP_FAULT, message=str(exc))
        finally:
            await self._backend.stop_all()
            run.phase = CalibrationPhase.DONE
            run.current_pump = None
            run.finished_at_ms = now_ms()
            # Every pump that finished is a good measurement, even if a later one failed.
            if run.results:
                self._calibration.record(
                    run.results,
                    glass_diameter_mm=sensor.glass_diameter_mm,
                    pump_seconds=seconds,
                    at_ms=run.finished_at_ms,
                )
                self._publish_slots()
            if run.status is CalibrationStatus.FINISHED:
                await self._lcd.show("cal_done")
            elif run.status is CalibrationStatus.FAILED:
                await self._lcd.show("cal_failed", message=run.message)
            else:
                await self._lcd.show("idle")
            self._publish_calibration(run)
            self._publish_snapshot()
            log.info("calibration %s -> %s", run.run_id, run.status.value)

    def _cal_phase(self, run: CalibrationRun, phase: CalibrationPhase, message: str) -> None:
        run.phase = phase
        run.message = message
        self._publish_calibration(run)

    # ------------------------------------------------------------------ cleaning

    async def start_cleaning(
        self,
        pumps: Optional[list[int]] = None,
        seconds: Optional[float] = None,
        rounds: Optional[int] = None,
    ) -> CleaningRun:
        """Rinse the lines: every pump in turn, `rounds` times, into a container.

        Needs neither loaded slots nor a tray reference — nothing is poured into a glass and
        the sensor is never read. Bottle ids are ignored; the slot table is left as it is.
        """
        async with self._lock:
            self._require_idle()
            pumps = self._pick_pumps(pumps)
            config = self._config.cleaning
            seconds = seconds if seconds is not None else config.pump_seconds
            rounds = rounds if rounds is not None else config.rounds
            if seconds <= 0 or seconds > MAX_JOG_SECONDS:
                raise MachineError(
                    ErrorCode.VOLUME_OUT_OF_RANGE,
                    f"Cleaning time must be between 0 and {MAX_JOG_SECONDS:.0f} seconds per pump",
                    422,
                )
            if rounds < 1 or rounds > MAX_CLEANING_ROUNDS:
                raise MachineError(
                    ErrorCode.VOLUME_OUT_OF_RANGE,
                    f"Cleaning takes between 1 and {MAX_CLEANING_ROUNDS} rounds",
                    422,
                )

            run = CleaningRun(
                run_id=str(uuid.uuid4()),
                pumps=pumps,
                rounds=rounds,
                seconds=seconds,
                started_at_ms=now_ms(),
            )
            self._run_clean = run
            self._abort = asyncio.Event()
            self._task = asyncio.create_task(self._clean(run), name="cleaning")
            return run

    async def abort_cleaning(self) -> CleaningRun:
        run = self._run_clean
        if run is None:
            raise MachineError(ErrorCode.NOT_CONFIGURED, "No cleaning has run", 404)
        if not run.status.terminal:
            log.info("aborting cleaning %s", run.run_id)
            run.message = "Stopping…"
            self._abort.set()
            self._publish_cleaning(run)
        return run

    async def _clean(self, run: CleaningRun) -> None:
        """Run each pump for `run.seconds`, one after the other, round after round.

        Pumps never overlap: one stops, a short pause, then the next starts.
        """
        loop = asyncio.get_running_loop()
        planned = run.rounds * len(run.pumps) * run.seconds
        done = 0.0  # pump-seconds finished so far
        self._publish_snapshot()

        try:
            for lap in range(1, run.rounds + 1):
                for pump in run.pumps:
                    if self._abort.is_set():
                        break
                    if done > 0:
                        self._clean_phase(run, CleaningPhase.PAUSING, f"Next up: pump {pump}")
                        await self._sleep(self._config.cleaning.pause_seconds)
                        if self._abort.is_set():
                            break

                    run.current_round = lap
                    run.current_pump = pump
                    self._clean_phase(
                        run, CleaningPhase.PUMPING, f"Rinsing pump {pump} (round {lap} of {run.rounds})"
                    )
                    await self._lcd.show("clean_pump", round=lap, rounds=run.rounds, pump=pump, seconds=run.seconds)

                    duration = run.seconds / self._speed
                    started = loop.time()
                    tick = 0
                    await self._backend.start_pump(pump)
                    try:
                        while True:
                            elapsed = loop.time() - started
                            fraction = 1.0 if duration <= 0 else min(elapsed / duration, 1.0)
                            run.progress = round(min((done + run.seconds * fraction) / planned, 1.0), 4)
                            tick += 1
                            if tick % 5 == 0:
                                self._publish_cleaning(run)
                            if fraction >= 1.0 or self._abort.is_set():
                                break
                            await asyncio.sleep(min(TICK_SECONDS, duration - elapsed))
                    finally:
                        await self._backend.stop_pump(pump)
                    done += run.seconds
                if self._abort.is_set():
                    break

            if self._abort.is_set():
                run.status = CleaningStatus.ABORTED
                run.message = "Stopped"
                run.error = Fault(code=ErrorCode.ABORTED_BY_USER, message="Stopped from the app", recoverable=True)
            else:
                run.status = CleaningStatus.FINISHED
                run.progress = 1.0
                run.message = "Rinsed — put your bottles back"

        except asyncio.CancelledError:
            run.status = CleaningStatus.ABORTED
            raise
        except Exception as exc:  # noqa: BLE001 — any failure must still stop the pumps
            log.exception("cleaning %s failed", run.run_id)
            run.status = CleaningStatus.FAILED
            run.message = str(exc)
            run.error = Fault(code=ErrorCode.PUMP_FAULT, message=str(exc))
        finally:
            await self._backend.stop_all()
            run.phase = CleaningPhase.DONE
            run.current_round = None
            run.current_pump = None
            run.finished_at_ms = now_ms()
            if run.status is CleaningStatus.FINISHED:
                await self._lcd.show("clean_done")
            elif run.status is CleaningStatus.FAILED:
                await self._lcd.show("clean_failed", message=run.message)
            else:
                await self._lcd.show("idle")
            self._publish_cleaning(run)
            self._publish_snapshot()
            log.info("cleaning %s -> %s", run.run_id, run.status.value)

    def _clean_phase(self, run: CleaningRun, phase: CleaningPhase, message: str) -> None:
        run.phase = phase
        run.message = message
        self._publish_cleaning(run)

    # ------------------------------------------------------------------ planning

    def _plan(self, request: PourRequest) -> PourJob:
        steps: list[PourStep] = []
        total_ml = 0.0

        steps.append(
            PourStep(
                index=0,
                kind=StepKind.GLASS,
                label="Place a glass",
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
            # The liquid level in the glass, carried from one pour step to the next so each
            # level is measured once. None means "measure it before the next pump".
            level: Optional[float] = None
            for step in job.steps:
                if self._abort.is_set():
                    break
                job.current_step_index = step.index
                if step.kind is StepKind.GLASS:
                    await self._glass_step(job, step, weights)
                elif step.kind is StepKind.POUR:
                    level = await self._pour_step(job, step, weights, level)
                else:
                    if step.kind is StepKind.MIX:
                        await self._lcd.show("mixing", drink=job.drink_name)
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
            job.waiting_for_glass = False
            job.finished_at_ms = now_ms()
            await self._leds.end_pour()
            if job.status is JobStatus.FINISHED:
                await self._lcd.show("done", drink=job.drink_name)
            elif job.status is JobStatus.FAILED:
                await self._lcd.show("failed", message=job.error.message if job.error else "")
            else:
                await self._lcd.show("aborted", drink=job.drink_name)
            self._publish_job(job)
            self._publish_snapshot()
            log.info("job %s -> %s", job.job_id, job.status.value)

    async def _glass_step(self, job: PourJob, step: PourStep, weights: list[float]) -> None:
        """Hold the pour until a glass stands under the nozzle. No timeout: abort ends the wait."""
        self._recompute(job, weights, 0.0)
        job.waiting_for_glass = True
        self._publish_job(job)
        await self._lcd.show("place_glass", drink=job.drink_name)

        await self._await_glass()

        job.waiting_for_glass = False
        if not self._abort.is_set():
            step.label = "Glass detected"
            self._recompute(job, weights, 1.0)
        self._publish_job(job)

    async def _await_glass(self) -> None:
        """Return once enough readings in a row look like a glass on the tray, or on abort."""
        sensor = self._config.sensor
        in_a_row = 0
        while not self._abort.is_set():
            distance = await self._backend.read_distance()
            in_a_row = in_a_row + 1 if in_glass_band(distance, self.reference_cm, sensor) else 0
            if in_a_row >= sensor.glass_confirmations:
                return
            await self._sleep(sensor.poll_seconds)

    async def _pour_step(
        self, job: PourJob, step: PourStep, weights: list[float], level: Optional[float]
    ) -> Optional[float]:
        """Pour one item by time, then measure what actually went in. Returns the new level."""
        target = step.ml or 0.0
        rate = self._rate_for(step.pump)
        duration = (target / rate) / self._speed
        log.info("pump %d: %.1f ml over %.1fs", step.pump, target, duration)

        if level is None:
            level = await self._measure()
        await self._lcd.show("pouring", ingredient=step.label.removeprefix("Pouring "), pump=step.pump, ml=target)

        loop = asyncio.get_running_loop()
        started = loop.time()
        tick = 0
        await self._backend.start_pump(step.pump)
        try:
            while True:
                elapsed = loop.time() - started
                fraction = 1.0 if duration <= 0 else min(elapsed / duration, 1.0)
                step.dispensed_ml = round(target * fraction, 2)
                self._recompute(job, weights, fraction)
                tick += 1
                if tick % 2 == 0:
                    self._publish_job(job)
                if fraction >= 1.0 or self._abort.is_set():
                    break
                # Never oversleep the end of the pour: a whole tick is up to 2 ml of overshoot.
                await asyncio.sleep(min(TICK_SECONDS, duration - elapsed))
        finally:
            await self._backend.stop_pump(step.pump)

        self._publish_job(job)
        if self._abort.is_set():
            return None  # a stop should feel immediate; the estimate stands

        await self._sleep(self._config.sensor.settle_seconds)
        end = await self._measure()
        self._apply_measurement(job, step, level, end)
        self._publish_job(job)
        # If the glass was lifted the next step measures afresh rather than trusting this.
        return end if step.measured else None

    def _apply_measurement(
        self, job: PourJob, step: PourStep, start: Optional[float], end: Optional[float]
    ) -> None:
        """Swap the time-based estimate for what the sensor saw — if the sensor is believable.

        A lifted glass, a hand in the way or a splash gives a nonsense rise; then the estimate
        stays, `measured` stays False, and the recipe simply carries on.
        """
        if start is None or end is None:
            return
        poured = volume_ml(start, end, self._config.sensor.glass_diameter_mm)
        estimate = step.dispensed_ml or 0.0
        if 0 < poured <= estimate * MEASURE_TOLERANCE + MEASURE_SLACK_ML:
            step.dispensed_ml = round(poured, 2)
            step.measured = True
            job.dispensed_ml = round(sum(s.dispensed_ml or 0.0 for s in job.steps), 2)
            log.info("pump %d: planned %.1f ml, measured %.1f ml", step.pump, step.ml or 0, poured)
        else:
            log.warning("pump %d: ignoring implausible measured volume %.1f ml", step.pump, poured)

    async def _measure(self) -> Optional[float]:
        return await measure(self._backend.read_distance, self._config.sensor, sleep=self._sleep)

    async def _beat(self, job: PourJob, step: PourStep, weights: list[float]) -> None:
        self._recompute(job, weights, 0.0)
        self._publish_job(job)
        await self._sleep(BEAT_SECONDS)
        self._recompute(job, weights, 1.0)

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

    def _slot(self, pump: int) -> Slot:
        return next(s for s in self._slots if s.pump == pump)

    # ------------------------------------------------------------------ internals

    def _require_idle(self) -> None:
        if self.current_job is not None:
            raise MachineError(ErrorCode.MACHINE_BUSY, "A pour is already running", 409, with_job=True)
        if self.current_calibration is not None:
            raise MachineError(ErrorCode.MACHINE_BUSY, "A calibration is running", 409)
        if self.current_cleaning is not None:
            raise MachineError(ErrorCode.MACHINE_BUSY, "Cleaning is running", 409)

    def _pick_pumps(self, pumps: Optional[list[int]]) -> list[int]:
        """The pumps a run asked for — every pump when none — checked against this machine."""
        known = [slot.pump for slot in self._slots]
        pumps = list(pumps) if pumps else known
        for pump in pumps:
            if pump not in known:
                raise MachineError(ErrorCode.UNKNOWN_BOTTLE, f"No pump {pump} on this machine", 422)
        if len(set(pumps)) != len(pumps):
            raise MachineError(ErrorCode.UNKNOWN_BOTTLE, "A pump is listed twice", 422)
        return pumps

    def _require_reference(self) -> None:
        if self.reference_cm is None:
            raise MachineError(
                ErrorCode.NOT_CALIBRATED,
                "The glass sensor has no reference yet — measure it in Settings → Calibrate pumps",
                503,
            )

    def _remember(self, job: PourJob) -> None:
        self._jobs[job.job_id] = job
        while len(self._jobs) > JOB_HISTORY:
            self._jobs.popitem(last=False)

    def _publish_job(self, job: PourJob) -> None:
        self._bus.publish("pour", job.model_dump(by_alias=True))

    def _publish_calibration(self, run: CalibrationRun) -> None:
        self._bus.publish("calibration", run.model_dump(by_alias=True))

    def _publish_cleaning(self, run: CleaningRun) -> None:
        self._bus.publish("cleaning", run.model_dump(by_alias=True))

    def _publish_slots(self) -> None:
        self._bus.publish("slots", {"slots": [s.model_dump(by_alias=True) for s in self._slots]})

    def _publish_snapshot(self) -> None:
        self._bus.publish("snapshot", self.status().model_dump(by_alias=True))
