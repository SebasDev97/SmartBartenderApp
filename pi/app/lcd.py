"""What the machine's 16x2 display says, keyed by what the machine is doing.

Every string lives in MESSAGES, so the display can be translated (or reworded to fit 16
characters) without touching the machine logic. Placeholders are filled from keyword
arguments; the backend trims each line to fit.
"""

from __future__ import annotations

import logging

log = logging.getLogger("bartender.lcd")

MESSAGES: dict[str, tuple[str, str]] = {
    "idle": ("Smart Bartender", "Ready"),
    "place_glass": ("Place a glass", "{drink}"),
    "pouring": ("{ingredient}", "Pump {pump} {ml:.0f} ml"),
    "mixing": ("{drink}", "Mixing..."),
    "done": ("Enjoy!", "{drink}"),
    "aborted": ("Stopped", "{drink}"),
    "failed": ("Error", "{message}"),
    "reference": ("Measuring tray", "Keep it empty"),
    "jog": ("Test pump {pump}", "{seconds:.0f} s"),
    "cal_glass": ("Calibration", "Put empty glass"),
    "cal_measure": ("Calibration", "Measuring..."),
    "cal_pump": ("Calibrating", "Pump {pump} {seconds:.0f} s"),
    "cal_result": ("Pump {pump}", "{rate:.2f} ml/s"),
    "cal_done": ("Calibration done", "See the app"),
    "cal_failed": ("Calibration", "{message}"),
    "clean_pump": ("Cleaning {round}/{rounds}", "Pump {pump} {seconds:.0f} s"),
    "clean_done": ("Cleaning done", "Refill bottles"),
    "clean_failed": ("Cleaning", "{message}"),
}


class Lcd:
    def __init__(self, backend) -> None:
        self._backend = backend

    async def show(self, key: str, **values) -> None:
        """Never raises: the display is information, and must not break a pour."""
        try:
            line1, line2 = (line.format(**values) for line in MESSAGES[key])
            await self._backend.show_lcd(line1, line2)
        except Exception:  # noqa: BLE001
            log.exception("LCD message %r failed", key)
