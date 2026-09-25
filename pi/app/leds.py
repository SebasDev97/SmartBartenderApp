"""The LED show's state: which mode the strip is in, and the pour's fill level.

The current machine has no LED strip — the Arduino backend accepts these calls and drives
nothing (BackendInfo.has_leds is False). The state is still kept and echoed back, because the
app's on-screen LED show reads it, and a backend with a strip can pick it up unchanged: the
Pi only ever says which mode to show, it never streams frames.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Optional

from .models import LedMode, LedState

log = logging.getLogger("bartender.leds")

FRAME_SECONDS = 1 / 30


class LedController:
    """Owns the current mode, and feeds the pour fill level to the backend.

    The `pour` mode is set by the machine itself when a job starts, and the previous mode
    is restored when it ends — the app never has to ask for it.
    """

    def __init__(self, backend) -> None:
        self._backend = backend
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
        while True:
            try:
                await asyncio.sleep(FRAME_SECONDS)
                if self._state.enabled and self._state.mode is LedMode.POUR:
                    await self._backend.led_pour_progress(self._pour_progress)
            except asyncio.CancelledError:
                raise
            except Exception:  # noqa: BLE001 — a bad frame must not kill the show
                log.exception("LED frame failed")
