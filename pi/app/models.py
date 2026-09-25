"""The API contract in code. See API.md — if the two disagree, API.md wins.

Fields are snake_case in Python and camelCase on the wire; the alias generator handles
the translation, and FastAPI serialises by alias automatically.
"""

from __future__ import annotations

from enum import Enum
from typing import Literal, Optional

from pydantic import BaseModel, ConfigDict, Field


def _camel(name: str) -> str:
    head, *tail = name.split("_")
    return head + "".join(word.capitalize() for word in tail)


class Wire(BaseModel):
    """Base for everything that crosses the wire."""

    model_config = ConfigDict(alias_generator=_camel, populate_by_name=True)


# --------------------------------------------------------------------------- enums


class MachineState(str, Enum):
    IDLE = "idle"
    BUSY = "busy"
    FAULT = "fault"


class JobStatus(str, Enum):
    QUEUED = "queued"
    RUNNING = "running"
    ABORTING = "aborting"
    ABORTED = "aborted"
    FINISHED = "finished"
    FAILED = "failed"

    @property
    def terminal(self) -> bool:
        return self in (JobStatus.ABORTED, JobStatus.FINISHED, JobStatus.FAILED)


class StepKind(str, Enum):
    GLASS = "glass"
    POUR = "pour"
    MIX = "mix"
    MANUAL = "manual"
    FINISH = "finish"


class LedMode(str, Enum):
    OFF = "off"
    SOLID = "solid"
    SPECTRUM = "spectrum"
    POUR = "pour"


class ErrorCode(str, Enum):
    MACHINE_BUSY = "MACHINE_BUSY"
    NOT_CONFIGURED = "NOT_CONFIGURED"
    UNKNOWN_BOTTLE = "UNKNOWN_BOTTLE"
    SLOT_EMPTY = "SLOT_EMPTY"
    VOLUME_OUT_OF_RANGE = "VOLUME_OUT_OF_RANGE"
    PUMP_FAULT = "PUMP_FAULT"
    ABORTED_BY_USER = "ABORTED_BY_USER"
    NOT_CALIBRATED = "NOT_CALIBRATED"
    SENSOR_FAULT = "SENSOR_FAULT"


class CalibrationStatus(str, Enum):
    RUNNING = "running"
    FINISHED = "finished"
    FAILED = "failed"
    ABORTED = "aborted"

    @property
    def terminal(self) -> bool:
        return self is not CalibrationStatus.RUNNING


class CalibrationPhase(str, Enum):
    WAITING_GLASS = "waiting_glass"
    MEASURING = "measuring"
    PUMPING = "pumping"
    SETTLING = "settling"
    DONE = "done"


class CleaningStatus(str, Enum):
    RUNNING = "running"
    FINISHED = "finished"
    FAILED = "failed"
    ABORTED = "aborted"

    @property
    def terminal(self) -> bool:
        return self is not CleaningStatus.RUNNING


class CleaningPhase(str, Enum):
    PUMPING = "pumping"
    PAUSING = "pausing"
    DONE = "done"


# --------------------------------------------------------------------------- objects


class Slot(Wire):
    pump: int
    bottle_id: Optional[str] = None
    ml_per_second: float = 12.5


class SlotAssignment(Wire):
    """One entry of PUT /api/v1/slots. Calibration stays on the Pi, so it isn't accepted here."""

    pump: int
    bottle_id: Optional[str] = None


class SlotsRequest(Wire):
    slots: list[SlotAssignment]


class SlotsResponse(Wire):
    slots: list[Slot]


class LedState(Wire):
    enabled: bool = True
    mode: LedMode = LedMode.SPECTRUM
    color_hex: str = "#2AF5E4"
    brightness: float = 0.6
    cycle_millis: int = 7000


class LedRequest(Wire):
    enabled: bool
    mode: Optional[LedMode] = None
    color_hex: Optional[str] = None
    brightness: Optional[float] = None
    cycle_millis: Optional[int] = None


class Fault(Wire):
    code: ErrorCode
    message: str
    recoverable: bool = False


class PourStep(Wire):
    index: int
    kind: StepKind
    label: str
    detail: Optional[str] = None
    pump: Optional[int] = None
    ml: Optional[float] = None
    dispensed_ml: Optional[float] = None
    # True once dispensed_ml comes from the sensor (the liquid level rose by that much),
    # rather than from pump time x calibrated flow.
    measured: bool = False


class PourJob(Wire):
    job_id: str
    drink_id: Optional[str] = None
    drink_name: str
    status: JobStatus = JobStatus.QUEUED
    steps: list[PourStep] = Field(default_factory=list)
    current_step_index: int = 0
    progress: float = 0.0
    total_ml: float = 0.0
    dispensed_ml: float = 0.0
    started_at_ms: Optional[int] = None
    finished_at_ms: Optional[int] = None
    error: Optional[Fault] = None
    # True while the glass step waits for a glass under the nozzle. No pump runs until then.
    waiting_for_glass: bool = False


class PourItem(Wire):
    bottle_id: str
    ingredient_name: str
    ml: float


class PourRequest(Wire):
    job_id: str
    drink_id: Optional[str] = None
    drink_name: str
    glass: Optional[str] = None
    items: list[PourItem]
    manual_steps: list[str] = Field(default_factory=list)


class SensorInfo(Wire):
    """What the machine knows about its ultrasonic sensor and the glass it measures."""

    reference_cm: Optional[float] = None  # the empty tray; null until measured
    glass_diameter_mm: float = 58.0
    calibrated_at_ms: Optional[int] = None


class SensorReading(Wire):
    distance_cm: Optional[float] = None  # null when the sensor gave no valid echo
    glass_present: bool = False
    reference_cm: Optional[float] = None


class CalibrationResult(Wire):
    pump: int
    ml_per_second: float
    volume_ml: float
    seconds: float
    start_distance_cm: float
    end_distance_cm: float


class CalibrationRun(Wire):
    run_id: str
    status: CalibrationStatus = CalibrationStatus.RUNNING
    phase: CalibrationPhase = CalibrationPhase.WAITING_GLASS
    pumps: list[int] = Field(default_factory=list)
    current_pump: Optional[int] = None
    message: str = ""
    results: list[CalibrationResult] = Field(default_factory=list)
    started_at_ms: Optional[int] = None
    finished_at_ms: Optional[int] = None
    error: Optional[Fault] = None


class CalibrationRequest(Wire):
    pumps: Optional[list[int]] = None  # null: every pump
    seconds: Optional[float] = None  # null: calibration.pump_seconds from config.yaml


class CleaningRun(Wire):
    run_id: str
    status: CleaningStatus = CleaningStatus.RUNNING
    phase: CleaningPhase = CleaningPhase.PUMPING
    pumps: list[int] = Field(default_factory=list)
    rounds: int = 1
    seconds: float = 0.0  # per pump, per round
    current_round: Optional[int] = None  # 1-based
    current_pump: Optional[int] = None
    progress: float = 0.0  # pump time done / pump time planned
    message: str = ""
    started_at_ms: Optional[int] = None
    finished_at_ms: Optional[int] = None
    error: Optional[Fault] = None


class CleaningRequest(Wire):
    pumps: Optional[list[int]] = None  # null: every pump
    seconds: Optional[float] = None  # null: cleaning.pump_seconds from config.yaml
    rounds: Optional[int] = None  # null: cleaning.rounds from config.yaml


class MachineStatus(Wire):
    machine_id: str
    name: str
    firmware: str
    backend: Literal["simulated", "arduino"]
    state: MachineState
    pump_count: int
    max_pour_ml: float
    uptime_s: int
    slots: list[Slot]
    led: LedState
    current_job: Optional[PourJob] = None
    fault: Optional[Fault] = None
    sensor: SensorInfo = Field(default_factory=SensorInfo)
    calibration: Optional[CalibrationRun] = None  # only while one is running
    cleaning: Optional[CleaningRun] = None  # only while one is running


class Health(Wire):
    ok: bool = True
    machine_id: str
    firmware: str


class JogRequest(Wire):
    seconds: float


class JogResponse(Wire):
    pump: int
    seconds: float


class ApiError(Wire):
    code: ErrorCode
    message: str


class ErrorResponse(Wire):
    error: ApiError
    current_job: Optional[PourJob] = None


# --------------------------------------------------------------------------- events


class Event(Wire):
    """One WebSocket frame. `data` is always a whole object, never a delta."""

    type: Literal["snapshot", "pour", "led", "slots", "fault", "heartbeat", "calibration", "cleaning"]
    seq: int
    ts: int
    data: dict
