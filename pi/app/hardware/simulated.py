"""The default backend: drives nothing, but behaves like the machine.

This is what lets the Android app be built and demoed before a single pump exists. Beyond
logging pump commands it models what the ultrasonic sensor would see — the tray, a glass on
it, and the liquid rising while a pump runs — so glass detection, measured volumes and
calibration all work end to end under --simulate and in the tests.
"""

from __future__ import annotations

import logging
import time
from typing import Optional

from ..models import LedState
from ..sensor import glass_area_cm2
from .backend import BackendInfo

log = logging.getLogger("bartender.hardware")

#: Sensor to the empty tray, and to the bottom of an empty glass on it. The group's real
#: machine measured 15.6 cm to the bottom of its empty 58 mm glass.
TRAY_CM = 16.3
GLASS_BOTTOM_CM = 15.6

#: The HC-SR04 cannot see closer than about 2 cm.
MIN_RANGE_CM = 2.0

DEFAULT_FLOW = 12.5


class SimulatedBackend:
    def __init__(
        self,
        pump_count: int = 4,
        *,
        flows: Optional[dict[int, float]] = None,
        speed: float = 1.0,
        glass_diameter_mm: float = 58.0,
        glass_present: bool = True,
        auto_serve_seconds: Optional[float] = None,
    ) -> None:
        """
        flows: the true ml/s of each pump, which a calibration run should find again.
        speed: the Machine's speed factor. A pump that runs 1 s of wall time at speed 4
            pours 4 s worth, the same way the Machine compresses its timings.
        auto_serve_seconds: when set, a glass with liquid in it is swapped for an empty one
            after the pumps have been idle this long — a stand-in for the person taking their
            drink, so a long --simulate session never fills up.
        """
        self._pump_count = pump_count
        self._flows = flows or {}
        self._speed = speed
        self._area = glass_area_cm2(glass_diameter_mm)
        self._auto_serve = auto_serve_seconds
        self.running: set[int] = set()
        self.started: list[int] = []  # every start_pump, in order — for tests
        self.lcd: tuple[str, str] = ("", "")

        self.glass_present = glass_present
        self.liquid_ml = 0.0
        self._on_since: dict[int, float] = {}
        self._idle_since = time.monotonic()

    # ------------------------------------------------------------------ test/demo hooks

    def place_glass(self, liquid_ml: float = 0.0) -> None:
        self._settle_flow()
        self.glass_present = True
        self.liquid_ml = liquid_ml

    def remove_glass(self) -> None:
        self._settle_flow()
        self.glass_present = False
        self.liquid_ml = 0.0

    # ------------------------------------------------------------------ backend

    async def start(self) -> None:
        pass

    async def close(self) -> None:
        pass

    async def start_pump(self, pump: int) -> None:
        self.running.add(pump)
        self.started.append(pump)
        self._on_since.setdefault(pump, time.monotonic())
        log.info("pump %d ON", pump)

    async def stop_pump(self, pump: int) -> None:
        self._settle_flow(pump)
        self.running.discard(pump)
        log.info("pump %d OFF", pump)

    async def stop_all(self) -> None:
        self._settle_flow()
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
        pass  # too chatty to log

    async def read_distance(self) -> Optional[float]:
        self._maybe_serve()
        if not self.glass_present:
            return TRAY_CM
        level_cm = (self.liquid_ml + self._flowing_ml()) / self._area
        return round(max(GLASS_BOTTOM_CM - level_cm, MIN_RANGE_CM), 2)

    async def show_lcd(self, line1: str, line2: str) -> None:
        if (line1, line2) != self.lcd:
            self.lcd = (line1, line2)
            log.info("lcd [%-16s|%-16s]", line1, line2)

    def info(self) -> BackendInfo:
        return BackendInfo(
            name="simulated",
            pump_count=self._pump_count,
            has_leds=True,
            default_reference_cm=TRAY_CM,
        )

    # ------------------------------------------------------------------ the physics

    def _flow(self, pump: int) -> float:
        return self._flows.get(pump, DEFAULT_FLOW)

    def _flowing_ml(self) -> float:
        """Liquid poured by the pumps that are still running, not yet added to the glass."""
        if not self.glass_present:
            return 0.0
        now = time.monotonic()
        return sum(self._flow(p) * (now - t) * self._speed for p, t in self._on_since.items())

    def _settle_flow(self, pump: Optional[int] = None) -> None:
        now = time.monotonic()
        for p in [pump] if pump is not None else list(self._on_since):
            started = self._on_since.pop(p, None)
            if started is not None and self.glass_present:
                self.liquid_ml += self._flow(p) * (now - started) * self._speed
        if not self._on_since:
            self._idle_since = now

    def _maybe_serve(self) -> None:
        if self._auto_serve is None or self._on_since or self.liquid_ml <= 0:
            return
        if time.monotonic() - self._idle_since >= self._auto_serve:
            log.info("simulated guest took their drink; an empty glass is back under the nozzle")
            self.liquid_ml = 0.0
