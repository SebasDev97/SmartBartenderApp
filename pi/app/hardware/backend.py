"""The whole hardware surface, in six methods.

The machine isn't built yet, so everything above this line is written against the Protocol
and nothing else. Swapping a relay board for a motor-driver HAT, or dropping in someone
else's working GPIO code, means writing one new class — nothing else in the service moves.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol, runtime_checkable

from ..models import LedState


@dataclass
class BackendInfo:
    name: str  # "simulated" | "gpio" — reported as MachineStatus.backend
    pump_count: int
    has_leds: bool


@runtime_checkable
class HardwareBackend(Protocol):
    async def start_pump(self, pump: int) -> None: ...

    async def stop_pump(self, pump: int) -> None: ...

    async def stop_all(self) -> None:
        """Called on abort, on fault, and on shutdown. Must never raise."""

    async def set_led(self, led: LedState) -> None: ...

    async def led_pour_progress(self, progress: float) -> None:
        """Reactive fill effect during a pour. `progress` is 0..1."""

    def info(self) -> BackendInfo: ...
