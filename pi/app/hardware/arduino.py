"""Pumps and LEDs on an Arduino over USB serial. Used when the service starts with --arduino.

The Pi drives no pins at all. It sends one short text line per command to the sketch in
firmware/bartender/bartender.ino and waits for that command's reply; the sketch owns every
pin number, the relay polarity and the LED animations. The protocol is documented at the top
of the sketch — this file and that one are the two ends of the same wire.

Three things keep a pump from running away:

1. Every command is idempotent (ON 1, OFF 1, STOP, LED ...), so a command that gets no reply
   is simply sent again.
2. A heartbeat PING goes out four times a second. If the Pi, this service or the cable dies,
   the sketch's watchdog stops every pump on its own.
3. A dropped port is reopened on the next command, so unplugging and replugging the USB cable
   heals without a restart.
"""

from __future__ import annotations

import asyncio
import logging
import time
from typing import Callable, Optional

import serial

from ..config import Config
from ..models import LedMode, LedState
from .backend import BackendInfo

log = logging.getLogger("bartender.arduino")

#: How long one command waits for its reply before it is sent again.
REPLY_SECONDS = 0.3

#: Sends per command before it counts as a hardware fault.
ATTEMPTS = 3

#: Opening the port resets most boards (DTR); the bootloader needs this long before the
#: sketch is listening. Anything sent sooner is eaten by the bootloader.
BOOT_SECONDS = 2.0

#: Must stay well under WATCHDOG_MS in the sketch.
HEARTBEAT_SECONDS = 0.25


class ArduinoError(RuntimeError):
    """The Arduino is missing, silent, or refused a command."""


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
        self._heartbeat: Optional[asyncio.Task] = None

        # Filled in from the sketch's HELLO reply.
        self.firmware = ""
        self._pump_count = 0
        self._led_count = 0

        self._brightness = 0
        self._lit: Optional[int] = None

    # ------------------------------------------------------------------ lifecycle

    async def start(self) -> None:
        """Connect and start the heartbeat. Raises if the Arduino is not there at boot."""
        self._lock = asyncio.Lock()  # created here, not in __init__, so it binds uvicorn's loop
        async with self._lock:
            await asyncio.to_thread(self._connect)
        self._heartbeat = asyncio.create_task(self._beat(), name="arduino-heartbeat")

    async def close(self) -> None:
        if self._heartbeat is not None:
            self._heartbeat.cancel()
            self._heartbeat = None
        await self.stop_all()
        if self._port is not None:
            self._port.close()
            self._port = None

    # ------------------------------------------------------------------ pumps

    async def start_pump(self, pump: int) -> None:
        await self._send(f"ON {pump}")

    async def stop_pump(self, pump: int) -> None:
        await self._send(f"OFF {pump}")

    async def stop_all(self) -> None:
        try:
            await self._send("STOP")
        except Exception:  # noqa: BLE001 — stop_all must never raise; the watchdog is the backstop
            log.exception("could not send STOP; the Arduino watchdog will stop the pumps")

    # ------------------------------------------------------------------ LEDs
    #
    # LED failures are logged, never raised: the strip is decoration, and a pour must not fail
    # because a colour did not arrive.

    async def set_led(self, led: LedState) -> None:
        if not self._led_count:
            return
        self._brightness = round(max(0.0, min(1.0, led.brightness * self._config.led.brightness)) * 255)
        if not led.enabled or led.mode is LedMode.OFF:
            command = "LED OFF"
        elif led.mode is LedMode.SOLID:
            command = f"LED SOLID {led.color_hex.lstrip('#')[:6]} {self._brightness}"
        elif led.mode is LedMode.SPECTRUM:
            command = f"LED SPECTRUM {max(led.cycle_millis, 100)} {self._brightness}"
        else:  # POUR: start empty, led_pour_progress fills it
            self._lit = 0
            command = f"LED FILL 0 {self._brightness}"
        await self._send_quietly(command)

    async def led_pour_progress(self, progress: float) -> None:
        """Called every frame; only sends when the number of lit pixels actually changes."""
        if not self._led_count:
            return
        lit = int(self._led_count * max(0.0, min(1.0, progress)))
        if lit == self._lit:
            return
        self._lit = lit
        await self._send_quietly(f"LED FILL {lit} {self._brightness}")

    def info(self) -> BackendInfo:
        return BackendInfo(
            name="arduino",
            pump_count=self._pump_count or self._config.pump_count,
            has_leds=self._led_count > 0,
        )

    # ------------------------------------------------------------------ transport

    async def _send(self, command: str) -> str:
        if self._lock is None:
            raise ArduinoError("ArduinoBackend.start() was never called")
        async with self._lock:
            return await asyncio.to_thread(self._exchange, command)

    async def _send_quietly(self, command: str) -> None:
        try:
            await self._send(command)
        except Exception as exc:  # noqa: BLE001 — see the LED note above
            log.warning("LED command %r failed: %s", command, exc)

    async def _beat(self) -> None:
        while True:
            await asyncio.sleep(HEARTBEAT_SECONDS)
            try:
                await self._send("PING")
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001 — keep beating; it reconnects on its own
                log.debug("heartbeat failed: %s", exc)

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
                port.write(b"HELLO\n")
                reply = self._read_reply(port)
                if reply and reply.startswith("OK BARTENDER"):
                    self._handshake(reply)
                    self._port = port
                    log.info(
                        "Arduino on %s: firmware %s, %d pumps, %d LEDs",
                        where,
                        self.firmware,
                        self._pump_count,
                        self._led_count,
                    )
                    return
        except (serial.SerialException, OSError) as exc:
            port.close()
            raise ArduinoError(f"lost {where} during the handshake: {exc}") from exc
        except ArduinoError:
            port.close()
            raise

        port.close()
        raise ArduinoError(f"no answer from the bartender sketch on {where} — is it flashed?")

    def _handshake(self, reply: str) -> None:
        # OK BARTENDER <firmware> <pumps> <leds>
        parts = reply.split()
        try:
            self.firmware, pumps, leds = parts[2], int(parts[3]), int(parts[4])
        except (IndexError, ValueError) as exc:
            raise ArduinoError(f"unreadable HELLO reply: {reply!r}") from exc
        if pumps < self._config.pump_count:
            # Pouring on a pump the sketch doesn't have would silently pour nothing.
            raise ArduinoError(
                f"the sketch drives {pumps} pumps but config.yaml has {self._config.pump_count}"
            )
        self._pump_count, self._led_count = pumps, leds

    def _exchange(self, command: str) -> str:
        last = "no reply"
        for attempt in range(1, ATTEMPTS + 1):
            if self._port is None:
                self._connect()
            try:
                self._port.write(command.encode("ascii") + b"\n")
                reply = self._read_reply(self._port)
            except (serial.SerialException, OSError) as exc:
                self._drop(exc)
                last = str(exc)
                continue

            if reply is not None and reply.startswith("OK"):
                return reply
            last = reply or "no reply"
            log.warning("%r attempt %d/%d: %s", command, attempt, ATTEMPTS, last)

        raise ArduinoError(f"{command!r} failed: {last}")

    def _read_reply(self, port: serial.Serial) -> Optional[str]:
        """The next OK/ERR line, logging any '#' lines the sketch sends on its own."""
        while True:
            raw = port.readline()
            if not raw:
                return None  # timed out
            line = raw.decode("ascii", errors="replace").strip()
            if line.startswith(("OK", "ERR")):
                return line
            if line:
                log.info("arduino: %s", line.lstrip("# "))

    def _drop(self, exc: Exception) -> None:
        log.error("lost the Arduino (%s); reconnecting on the next command", exc)
        try:
            self._port.close()
        except Exception:  # noqa: BLE001
            pass
        self._port = None
