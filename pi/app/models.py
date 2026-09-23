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


class MachineStatus(Wire):
    machine_id: str
    name: str
    firmware: str
    backend: Literal["simulated", "gpio"]
    state: MachineState
    pump_count: int
    max_pour_ml: float
    uptime_s: int
    slots: list[Slot]
    led: LedState
    current_job: Optional[PourJob] = None
    fault: Optional[Fault] = None


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

    type: Literal["snapshot", "pour", "led", "slots", "fault", "heartbeat"]
    seq: int
    ts: int
    data: dict
