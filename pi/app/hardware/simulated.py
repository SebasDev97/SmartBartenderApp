"""The default backend: logs what it would do, drives nothing.

This is what lets the Android app be built and demoed before a single pump exists.
"""

from __future__ import annotations

import logging

from ..models import LedState
from .backend import BackendInfo

log = logging.getLogger("bartender.hardware")


class SimulatedBackend:
    def __init__(self, pump_count: int = 4) -> None:
        self._pump_count = pump_count
        self.running: set[int] = set()

    async def start(self) -> None:
        pass

    async def close(self) -> None:
        pass

    async def start_pump(self, pump: int) -> None:
        self.running.add(pump)
        log.info("pump %d ON", pump)

    async def stop_pump(self, pump: int) -> None:
        self.running.discard(pump)
        log.info("pump %d OFF", pump)

    async def stop_all(self) -> None:
        if self.running:
            log.info("all pumps OFF (was running: %s)", sorted(self.running))
        self.running.clear()

    async def set_led(self, led: LedState) -> None:
        log.info(
            "led %s mode=%s colour=%s brightness=%.2f",
            "on" if led.enabled else "off",
            led.mode.value,
            led.color_hex,
            led.brightness,
        )

    async def led_pour_progress(self, progress: float) -> None:
        pass  # too chatty to log; the real strip renders this

    def info(self) -> BackendInfo:
        return BackendInfo(name="simulated", pump_count=self._pump_count, has_leds=True)
