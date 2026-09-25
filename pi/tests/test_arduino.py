"""The Arduino link, driven against a fake board that speaks the relay/sensor/LCD sketch's protocol.

FakeBoard stands in for serial.Serial: whatever the backend writes is answered the way the
group's sketch answers it (RELAY / ALL OFF / GET DIST / LCD BOTH), so these tests pin the wire
format from the Pi's side without a board on the desk. The sketch's exact acknowledgement text
is unknown, so the fake answers with a plausible one and the backend must not care what it is.
"""

import asyncio
import uuid

import pytest
import serial

from app.calibration import CalibrationStore
from app.config import load_config
from app.events import EventBus
from app.hardware.arduino import ArduinoBackend, ArduinoError, lcd_text
from app.machine import Machine
from app.models import JobStatus, PourItem, PourRequest


class FakeBoard:
    def __init__(self, pumps=4):
        self.pumps = pumps
        self.running: set[int] = set()
        self.sent: list[str] = []
        self.lcd = ("", "")
        self.distance = 15.6  # an empty glass on a 16.3 cm tray
        self.unplugged = False
        self.silent = False
        self.swallow = 0  # drop this many replies, as a garbled line would
        self._replies: list[bytes] = []

    def write(self, data: bytes) -> int:
        if self.unplugged:
            raise serial.SerialException("device disconnected")
        command = data.decode().rstrip("\n")
        self.sent.append(command)
        reply = self._answer(command)
        if self.silent:
            return len(data)
        if self.swallow:
            self.swallow -= 1
        else:
            self._replies.append(reply.encode() + b"\r\n")
        return len(data)

    def readline(self) -> bytes:
        return self._replies.pop(0) if self._replies else b""

    def reset_input_buffer(self) -> None:
        self._replies.clear()

    def close(self) -> None:
        pass

    def _answer(self, command: str) -> str:
        if command == "ALL OFF":
            self.running.clear()
            return "ALLE RELAIS UIT"
        if command == "GET DIST":
            return f"DIST {self.distance:.1f}"
        if command.startswith("LCD BOTH;"):
            _, line1, line2 = command.split(";")
            self.lcd = (line1, line2)
            return "LCD OK"
        words = command.split()
        if len(words) == 3 and words[0] == "RELAY":
            pump = int(words[1])
            if 1 <= pump <= self.pumps:
                (self.running.add if words[2] == "ON" else self.running.discard)(pump)
            return f"RELAY {pump} {words[2]}"
        return "ONBEKEND COMMANDO"


def connect(board, config=None):
    return ArduinoBackend(config or load_config(None), open_port=lambda: board, boot_seconds=0)


def test_connecting_switches_every_relay_off_first():
    async def scenario():
        board = FakeBoard()
        board.running = {2}  # a relay left closed by whatever ran before
        backend = connect(board)
        await backend.start()
        info = backend.info()
        await backend.close()
        return board, info

    board, info = asyncio.run(scenario())
    assert board.sent[0] == "ALL OFF"
    assert board.running == set()
    assert (info.name, info.pump_count, info.has_leds) == ("arduino", 4, False)


def test_a_silent_board_refuses_to_start():
    async def scenario():
        board = FakeBoard()
        board.silent = True
        await connect(board).start()

    with pytest.raises(ArduinoError, match="no answer"):
        asyncio.run(scenario())


def test_pump_commands_reach_the_board_one_based():
    async def scenario():
        board = FakeBoard()
        backend = connect(board)
        await backend.start()
        await backend.start_pump(1)
        await backend.start_pump(3)
        running = set(board.running)
        await backend.stop_pump(1)
        after_stop = set(board.running)
        await backend.close()
        return board, running, after_stop

    board, running, after_stop = asyncio.run(scenario())
    assert "RELAY 1 ON" in board.sent and "RELAY 1 OFF" in board.sent
    assert running == {1, 3}
    assert after_stop == {3}
    assert board.running == set(), "close() must leave every pump off"


def test_a_lost_reply_is_resent_because_relay_commands_are_idempotent():
    async def scenario():
        board = FakeBoard()
        backend = connect(board)
        await backend.start()
        board.swallow = 1
        await backend.start_pump(2)
        await backend.close()
        return board

    board = asyncio.run(scenario())
    assert board.sent.count("RELAY 2 ON") == 2


def test_a_board_that_stops_answering_becomes_an_error_after_retrying():
    async def scenario():
        board = FakeBoard()
        backend = connect(board)
        await backend.start()
        board.silent = True
        try:
            await backend.start_pump(1)
        finally:
            board.silent = False
            await backend.close()

    with pytest.raises(ArduinoError, match="RELAY 1 ON"):
        asyncio.run(scenario())


def test_distances_are_parsed_and_a_negative_one_means_no_echo():
    async def scenario():
        board = FakeBoard()
        backend = connect(board)
        await backend.start()
        board.distance = 12.47
        good = await backend.read_distance()
        board.distance = -1
        missing = await backend.read_distance()
        await backend.close()
        return good, missing

    good, missing = asyncio.run(scenario())
    assert good == 12.5  # the sketch answers with one decimal
    assert missing is None


def test_the_lcd_gets_two_trimmed_lines_and_no_repeats():
    async def scenario():
        board = FakeBoard()
        backend = connect(board)
        await backend.start()
        await backend.show_lcd("Pouring Triple sec; now", "Pump 2  15 ml")
        await backend.show_lcd("Pouring Triple sec; now", "Pump 2  15 ml")
        await backend.close()
        return board

    board = asyncio.run(scenario())
    assert board.lcd == ("Pouring Triple s", "Pump 2  15 ml")
    assert len([c for c in board.sent if c.startswith("LCD")]) == 1


def test_lcd_text_drops_the_line_separator_and_non_ascii():
    assert lcd_text("Curaçao; blue") == "Curaao, blue"


def test_the_lcd_never_raises_even_with_the_cable_pulled():
    async def scenario():
        board = FakeBoard()
        backend = connect(board)
        await backend.start()
        board.unplugged = True
        await backend.show_lcd("Hello", "World")  # must not raise
        board.unplugged = False
        await backend.close()

    asyncio.run(scenario())


def test_stop_all_never_raises_even_with_the_cable_pulled():
    async def scenario():
        board = FakeBoard()
        backend = connect(board)
        await backend.start()
        board.unplugged = True
        await backend.stop_all()  # must not raise
        board.unplugged = False
        await backend.close()

    asyncio.run(scenario())


def test_the_link_reconnects_after_the_cable_comes_back():
    async def scenario():
        board = FakeBoard()
        backend = connect(board)
        await backend.start()
        board.unplugged = True
        with pytest.raises(ArduinoError):
            await backend.start_pump(1)
        board.unplugged = False
        await backend.start_pump(1)
        running = set(board.running)
        await backend.close()
        return board, running

    board, running = asyncio.run(scenario())
    assert running == {1}
    assert board.sent.count("ALL OFF") >= 2, "a fresh port starts with every relay off again"


def test_a_whole_pour_through_the_arduino_leaves_every_pump_off():
    async def scenario():
        board = FakeBoard()
        backend = connect(board)
        store = CalibrationStore()
        store.set_reference(16.3)
        machine = Machine(load_config(None), backend, EventBus(), speed=50.0, calibration=store)
        machine._slots[0].bottle_id = "tequila"
        machine._slots[1].bottle_id = "triple_sec"
        await machine.start()
        job, _ = await machine.start_pour(
            PourRequest(
                job_id=str(uuid.uuid4()),
                drink_name="Margarita",
                items=[
                    PourItem(bottle_id="tequila", ingredient_name="Tequila", ml=44.0),
                    PourItem(bottle_id="triple_sec", ingredient_name="Triple sec", ml=15.0),
                ],
            )
        )
        await machine._task
        await machine.stop()
        return job, board

    job, board = asyncio.run(scenario())
    assert job.status is JobStatus.FINISHED
    assert "RELAY 1 ON" in board.sent and "RELAY 2 ON" in board.sent
    assert "GET DIST" in board.sent, "the glass is looked for before anything pours"
    assert board.sent.index("GET DIST") < board.sent.index("RELAY 1 ON")
    assert board.lcd[0] == "Enjoy!"
    assert board.running == set()
