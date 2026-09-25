"""Relays, the ultrasonic sensor and the LCD on an Arduino over USB serial. Used with --arduino.

The Pi drives no pins at all. The Arduino runs the group's relay/sensor/LCD sketch, and this
file speaks that sketch's line protocol — 115200 baud, one '\\n'-terminated command, one reply
line each:

    RELAY <n> ON | RELAY <n> OFF   one pump; n is 1-based, matching the app's slot numbers
    ALL OFF                        every pump
    GET DIST                       -> "DIST <cm>"; a negative value means no valid echo
    LCD BOTH;<line 1>;<line 2>     the 16x2 display

The sketch is a black box here: its acknowledgement text is not specified, so any non-empty
line counts as "done". There is no handshake and no LED strip.

What keeps a pump from running away:

1. The pump commands are idempotent, so one that gets no reply is simply sent again.
2. A dropped port is reopened on the next command, so unplugging and replugging the USB cable
   heals without a restart. Opening the port resets the board, which releases every relay.
3. ALL OFF goes out on connect, after every pour and jog, on shutdown, and — through
   `python -m app.safe_off` in bartender.service — after the service has died.

What this sketch does *not* have is a watchdog: if the Pi hangs outright mid-pour, the relay
stays closed until someone pulls the plug. See "Safety" in README.md.
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Callable, Optional

import serial

from ..config import Config
from ..models import LedState
from .backend import BackendInfo

log = logging.getLogger("bartender.arduino")

#: How long one command waits for its reply before it is sent again. Generous, because
#: GET DIST waits for an ultrasonic echo on the board before it answers.
REPLY_SECONDS = 1.0

#: Sends per command before it counts as a hardware fault.
ATTEMPTS = 3

#: Opening the port resets most boards (DTR); the bootloader needs this long before the
#: sketch is listening. Anything sent sooner is eaten by the bootloader.
BOOT_SECONDS = 2.0

LCD_WIDTH = 16


class ArduinoError(RuntimeError):
    """The Arduino is missing, silent, or unreadable."""


def lcd_text(text: str) -> str:
    """What fits on one LCD line: ASCII, no ';' (it separates the lines), 16 characters."""
    ascii_only = text.encode("ascii", errors="ignore").decode("ascii")
    return ascii_only.replace(";", ",").strip()[:LCD_WIDTH]


class ArduinoBackend:
    def __init__(
        self,
        config: Config,
        open_port: Optional[Callable[[], "serial.Serial"]] = None,
        boot_seconds: float = BOOT_SECONDS,
    ) -> None:
        self._config = config
        self._open_port = open_port or self._open_serial
        self._boot_seconds = boot_seconds
        self._port: Optional[serial.Serial] = None
        self._lock: Optional[asyncio.Lock] = None
        self._lcd: Optional[tuple[str, str]] = None

    # ------------------------------------------------------------------ lifecycle

    async def start(self) -> None:
        """Connect and switch every relay off. Raises if the Arduino is not there at boot."""
        self._lock = asyncio.Lock()  # created here, not in __init__, so it binds uvicorn's loop
        async with self._lock:
            await asyncio.to_thread(self._connect)

    async def close(self) -> None:
        await self.stop_all()
        if self._port is not None:
            self._port.close()
            self._port = None

    # ------------------------------------------------------------------ pumps

    async def start_pump(self, pump: int) -> None:
        await self._send(f"RELAY {pump} ON")

    async def stop_pump(self, pump: int) -> None:
        await self._send(f"RELAY {pump} OFF")

    async def stop_all(self) -> None:
        try:
            await self._send("ALL OFF")
        except Exception:  # noqa: BLE001 — stop_all must never raise
            log.exception("could not send ALL OFF")

    # ------------------------------------------------------------------ sensor and display

    async def read_distance(self) -> Optional[float]:
        reply = await self._send("GET DIST")
        parts = reply.split()
        if len(parts) != 2 or parts[0] != "DIST":
            log.warning("unexpected GET DIST reply: %r", reply)
            return None
        try:
            distance = float(parts[1])
        except ValueError:
            return None
        return distance if distance >= 0 else None

    async def show_lcd(self, line1: str, line2: str) -> None:
        lines = (lcd_text(line1), lcd_text(line2))
        if lines == self._lcd:
            return  # the display already says this; don't spend a serial round trip on it
        try:
            await self._send(f"LCD BOTH;{lines[0]};{lines[1]}")
            self._lcd = lines
        except Exception as exc:  # noqa: BLE001 — the display must never fail a pour
            log.warning("LCD update failed: %s", exc)

    # ------------------------------------------------------------------ LEDs: none on this build

    async def set_led(self, led: LedState) -> None:
        pass

    async def led_pour_progress(self, progress: float) -> None:
        pass

    def info(self) -> BackendInfo:
        return BackendInfo(name="arduino", pump_count=self._config.pump_count, has_leds=False)

    # ------------------------------------------------------------------ transport

    async def _send(self, command: str) -> str:
        if self._lock is None:
            raise ArduinoError("ArduinoBackend.start() was never called")
        async with self._lock:
            return await asyncio.to_thread(self._exchange, command)

    # Everything below runs on a worker thread, one call at a time under self._lock.

    def _open_serial(self) -> serial.Serial:
        cfg = self._config.arduino
        return serial.Serial(cfg.port, cfg.baud, timeout=REPLY_SECONDS, write_timeout=REPLY_SECONDS)

    def _connect(self) -> None:
        where = self._config.arduino.port
        try:
            port = self._open_port()
        except (serial.SerialException, OSError) as exc:
            raise ArduinoError(f"cannot open {where}: {exc}") from exc

        try:
            time.sleep(self._boot_seconds)
            port.reset_input_buffer()
            for _ in range(ATTEMPTS):
                port.write(b"ALL OFF\n")
                if self._read_reply(port) is not None:
                    self._port = port
                    self._lcd = None  # a reset board shows whatever its sketch boots with
                    log.info("Arduino on %s answers; every relay is off", where)
                    return
        except (serial.SerialException, OSError) as exc:
            port.close()
            raise ArduinoError(f"lost {where} while connecting: {exc}") from exc

        port.close()
        raise ArduinoError(f"no answer from the Arduino on {where} — is the sketch flashed?")

    def _exchange(self, command: str) -> str:
        last = "no reply"
        for attempt in range(1, ATTEMPTS + 1):
            if self._port is None:
                self._connect()
            try:
                # Anything still waiting is a late reply to an earlier, already retried command.
                self._port.reset_input_buffer()
                self._port.write(command.encode("ascii") + b"\n")
                reply = self._read_reply(self._port)
            except (serial.SerialException, OSError) as exc:
                self._drop(exc)
                last = str(exc)
                continue

            if reply is not None:
                return reply
            log.warning("%r attempt %d/%d: no reply", command, attempt, ATTEMPTS)

        raise ArduinoError(f"{command!r} failed: {last}")

    @staticmethod
    def _read_reply(port: serial.Serial) -> Optional[str]:
        """The next non-empty line, or None when the board stayed quiet."""
        while True:
            raw = port.readline()
            if not raw:
                return None  # timed out
            line = raw.decode("ascii", errors="replace").strip()
            if line:
                return line

    def _drop(self, exc: Exception) -> None:
        log.error("lost the Arduino (%s); reconnecting on the next command", exc)
        try:
            self._port.close()
        except Exception:  # noqa: BLE001
            pass
        self._port = None
