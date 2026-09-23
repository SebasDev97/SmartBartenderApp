"""Real pins. Only imported when the service is started with --gpio.

gpiozero and rpi_ws281x are imported inside __init__ on purpose: this module must import
cleanly on a laptop so `python -m app.main --simulate` works anywhere.

If someone already has working pump code, this is the one file to replace — implement the
six methods against their functions and nothing above this layer changes.
"""

from __future__ import annotations

import logging

from ..config import Config
from ..models import LedMode, LedState
from .backend import BackendInfo

log = logging.getLogger("bartender.hardware")


class GpioBackend:
    def __init__(self, config: Config) -> None:
        from gpiozero import OutputDevice  # noqa: PLC0415 — see module docstring

        self._config = config
        # Relay boards are usually active-low; config.pump_active_high flips it.
        # initial_value=False means "idle" whichever way round that is.
        self._pumps = {
            pump.pump: OutputDevice(
                pump.gpio,
                active_high=config.pump_active_high,
                initial_value=False,
            )
            for pump in config.pumps
        }

        self._strip = None
        if config.led.type == "ws2812":
            try:
                from rpi_ws281x import PixelStrip  # noqa: PLC0415

                self._strip = PixelStrip(
                    config.led.count,
                    config.led.gpio,
                    brightness=int(config.led.brightness * 255),
                )
                self._strip.begin()
            except Exception as exc:  # a missing strip must not stop the machine pouring
                log.warning("LED strip unavailable, continuing without it: %s", exc)

    async def start_pump(self, pump: int) -> None:
        self._pumps[pump].on()

    async def stop_pump(self, pump: int) -> None:
        self._pumps[pump].off()

    async def stop_all(self) -> None:
        for device in self._pumps.values():
            try:
                device.off()
            except Exception:  # noqa: BLE001 — stop_all must never raise
                log.exception("failed to stop a pump")

    async def set_led(self, led: LedState) -> None:
        if self._strip is None:
            return
        if not led.enabled or led.mode is LedMode.OFF:
            self._fill((0, 0, 0))
        elif led.mode is LedMode.SOLID:
            self._fill(_hex_to_rgb(led.color_hex))
        # spectrum/pour are driven frame-by-frame from LedController via _render

    async def led_pour_progress(self, progress: float) -> None:
        if self._strip is None:
            return
        lit = int(self._strip.numPixels() * max(0.0, min(1.0, progress)))
        for i in range(self._strip.numPixels()):
            self._set(i, (42, 245, 228) if i < lit else (4, 12, 18))
        self._strip.show()

    def render(self, colors: list[tuple[int, int, int]]) -> None:
        """Paint one frame. Called by LedController for the animated modes."""
        if self._strip is None:
            return
        for i in range(self._strip.numPixels()):
            self._set(i, colors[i % len(colors)])
        self._strip.show()

    def close(self) -> None:
        for device in self._pumps.values():
            device.close()

    def info(self) -> BackendInfo:
        return BackendInfo(
            name="gpio",
            pump_count=len(self._pumps),
            has_leds=self._strip is not None,
        )

    # ------------------------------------------------------------------ internals

    def _fill(self, rgb: tuple[int, int, int]) -> None:
        for i in range(self._strip.numPixels()):
            self._set(i, rgb)
        self._strip.show()

    def _set(self, index: int, rgb: tuple[int, int, int]) -> None:
        from rpi_ws281x import Color  # noqa: PLC0415

        self._strip.setPixelColor(index, Color(*rgb))


def _hex_to_rgb(value: str) -> tuple[int, int, int]:
    raw = value.lstrip("#")
    return int(raw[0:2], 16), int(raw[2:4], 16), int(raw[4:6], 16)
