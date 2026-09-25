"""The whole hardware surface, in ten methods.

Everything above this line is written against the Protocol and nothing else. The Pi drives
no pins itself — the real backend (arduino.py) talks to an Arduino over USB — so changing how
the machine is wired means changing that sketch, or writing one new class here; nothing else
in the service moves.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Optional, Protocol, runtime_checkable

from ..models import LedState


@dataclass
class BackendInfo:
    name: str  # "simulated" | "arduino" — reported as MachineStatus.backend
    pump_count: int
    has_leds: bool
    # A tray distance the machine can start with before anyone has measured one. Only the
    # simulator knows its tray in advance; real hardware must be measured.
    default_reference_cm: Optional[float] = None


@runtime_checkable
class HardwareBackend(Protocol):
    async def start(self) -> None:
        """Called once, inside the event loop, before anything else. May raise."""

    async def close(self) -> None:
        """Called once on shutdown, after stop_all."""

    async def start_pump(self, pump: int) -> None: ...

    async def stop_pump(self, pump: int) -> None: ...

    async def stop_all(self) -> None:
        """Called on abort, on fault, and on shutdown. Must never raise."""

    async def set_led(self, led: LedState) -> None: ...

    async def led_pour_progress(self, progress: float) -> None:
        """Reactive fill effect during a pour. `progress` is 0..1."""

    async def read_distance(self) -> Optional[float]:
        """One ultrasonic reading in cm, straight down. None when there was no valid echo."""

    async def show_lcd(self, line1: str, line2: str) -> None:
        """Two 16-character lines on the machine's display. Must never raise."""

    def info(self) -> BackendInfo: ...
