"""The LED show, ported from the app so the phone and the strip agree.

ledColorAt() below is a straight port of the Kotlin function in
app/src/main/java/com/example/smartbartender/ui/components/Led.kt, over the same seven
colours from ui/theme/Color.kt. Same spectrum, same 7000 ms lap: hold the phone next to
the machine and they match.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Optional

from .models import LedMode, LedState

log = logging.getLogger("bartender.leds")

FRAME_SECONDS = 1 / 30

#: NeonCyan, NeonBlue, NeonViolet, NeonMagenta, NeonAmber, NeonLime, and back to cyan
#: so the loop is seamless. Mirrors LedSpectrum in ui/theme/Color.kt.
SPECTRUM: list[tuple[int, int, int]] = [
    (0x2A, 0xF5, 0xE4),
    (0x3D, 0x8B, 0xFF),
    (0x8B, 0x5C, 0xFF),
    (0xFF, 0x3D, 0xD8),
    (0xFF, 0xB5, 0x47),
    (0x9D, 0xFF, 0x3D),
    (0x2A, 0xF5, 0xE4),
]


def led_color_at(progress: float) -> tuple[int, int, int]:
    """Colour of the strip at `progress` (0..1) through its cycle."""
    steps = len(SPECTRUM) - 1
    scaled = max(0.0, min(1.0, progress)) * steps
    index = min(max(int(scaled), 0), steps - 1)
    return _lerp(SPECTRUM[index], SPECTRUM[index + 1], scaled - index)


def _lerp(a: tuple[int, int, int], b: tuple[int, int, int], t: float) -> tuple[int, int, int]:
    return tuple(int(x + (y - x) * t) for x, y in zip(a, b))  # type: ignore[return-value]


class LedController:
    """Owns the strip's animation clock and the current mode.

    The `pour` mode is set by the machine itself when a job starts, and the previous mode
    is restored when it ends — the app never has to ask for it.
    """

    def __init__(self, backend, pixel_count: int = 24) -> None:
        self._backend = backend
        self._pixel_count = pixel_count
        self._state = LedState()
        self._resume_mode: Optional[LedMode] = None
        self._pour_progress = 0.0
        self._task: Optional[asyncio.Task] = None

    @property
    def state(self) -> LedState:
        return self._state.model_copy()

    async def start(self) -> None:
        await self._backend.set_led(self._state)
        self._task = asyncio.create_task(self._run(), name="led-show")

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
            self._task = None
        await self._backend.set_led(LedState(enabled=False, mode=LedMode.OFF))

    async def apply(self, **changes) -> LedState:
        """Set fields the app sent, leaving the rest alone."""
        self._state = self._state.model_copy(update={k: v for k, v in changes.items() if v is not None})
        if not self._state.enabled:
            self._state.mode = LedMode.OFF
        await self._backend.set_led(self._state)
        return self.state

    async def begin_pour(self) -> LedState:
        self._resume_mode = self._state.mode
        self._pour_progress = 0.0
        if self._state.enabled:
            self._state.mode = LedMode.POUR
            await self._backend.set_led(self._state)
        return self.state

    async def end_pour(self) -> LedState:
        if self._resume_mode is not None:
            self._state.mode = self._resume_mode
            self._resume_mode = None
            await self._backend.set_led(self._state)
        return self.state

    def set_pour_progress(self, progress: float) -> None:
        self._pour_progress = progress

    async def _run(self) -> None:
        elapsed = 0.0
        while True:
            try:
                await asyncio.sleep(FRAME_SECONDS)
                elapsed += FRAME_SECONDS
                if not self._state.enabled:
                    continue
                if self._state.mode is LedMode.POUR:
                    await self._backend.led_pour_progress(self._pour_progress)
                elif self._state.mode is LedMode.SPECTRUM:
                    await self._render_spectrum(elapsed)
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001 — a bad frame must not kill the show
                log.exception("LED frame failed")

    async def _render_spectrum(self, elapsed: float) -> None:
        cycle = max(self._state.cycle_millis, 100) / 1000
        base = (elapsed % cycle) / cycle
        colors = [
            led_color_at((base + i / self._pixel_count) % 1.0) for i in range(self._pixel_count)
        ]
        render = getattr(self._backend, "render", None)
        if render is not None:
            render(colors)
