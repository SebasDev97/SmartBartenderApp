"""Calibration: the JSON store, the sensor reference, and a whole run against the simulator."""

import asyncio
import json

import pytest

from app.calibration import CalibrationStore
from app.config import load_config
from app.events import EventBus
from app.hardware import SimulatedBackend
from app.machine import Machine, MachineError
from app.models import CalibrationResult, CalibrationStatus

# What the group's standalone script wrote on the real machine.
GROUP_FILE = {
    "glassDiameterMm": 58.0,
    "pumpTimeSeconds": 3.0,
    "distanceMeasurements": 20,
    "discardedMeasurements": 2,
    "settleTimeSeconds": 2.0,
    "pumps": {
        "1": {"mlPerSecond": 18.623, "volumeMl": 25.93, "pumpTimeSeconds": 3.006,
              "startDistanceCm": 15.6, "endDistanceCm": 14.62},
        "2": {"mlPerSecond": 18.719, "volumeMl": 56.47, "pumpTimeSeconds": 3.017,
              "startDistanceCm": 14.61, "endDistanceCm": 12.47},
    },
}

SPEED = 50.0


def build(tmp_path=None, flows=None, **backend_options):
    store = CalibrationStore(tmp_path / "cal.json" if tmp_path else None)
    backend = SimulatedBackend(4, speed=SPEED, flows=flows, **backend_options)
    machine = Machine(load_config(None), backend, EventBus(), speed=SPEED, calibration=store)
    return machine, backend, store


# ---------------------------------------------------------------------- the store


def test_the_groups_file_is_read_as_is(tmp_path):
    path = tmp_path / "pump_calibration.json"
    path.write_text(json.dumps(GROUP_FILE))
    store = CalibrationStore(path)
    assert store.ml_per_second(1) == 18.623
    assert store.ml_per_second(2) == 18.719
    assert store.ml_per_second(3) is None
    assert store.reference_cm is None, "the group's script never measured the tray"


def test_calibrated_rates_beat_config_yaml(tmp_path):
    path = tmp_path / "cal.json"
    path.write_text(json.dumps(GROUP_FILE))

    async def scenario():
        machine = Machine(load_config(None), SimulatedBackend(4), EventBus(), calibration=CalibrationStore(path))
        return {s.pump: s.ml_per_second for s in machine.slots}

    rates = asyncio.run(scenario())
    assert rates == {1: 18.623, 2: 18.719, 3: 12.5, 4: 12.5}


def test_saving_keeps_keys_it_does_not_know(tmp_path):
    path = tmp_path / "cal.json"
    path.write_text(json.dumps(GROUP_FILE))
    store = CalibrationStore(path)
    store.set_reference(16.31)
    store.record(
        [CalibrationResult(pump=3, ml_per_second=24.7, volume_ml=74.3, seconds=3.0,
                           start_distance_cm=12.5, end_distance_cm=9.7)],
        glass_diameter_mm=58.0, pump_seconds=3.0, at_ms=123,
    )
    saved = json.loads(path.read_text())
    assert saved["distanceMeasurements"] == 20
    assert saved["referenceDistanceCm"] == 16.31
    assert saved["pumps"]["1"]["mlPerSecond"] == 18.623, "pumps not in the run are untouched"
    assert saved["pumps"]["3"]["mlPerSecond"] == 24.7
    assert not (tmp_path / "cal.json.tmp").exists()


def test_a_broken_file_does_not_stop_the_machine(tmp_path):
    path = tmp_path / "cal.json"
    path.write_text("{ not json")
    assert CalibrationStore(path).ml_per_second(1) is None


# ---------------------------------------------------------------------- the reference


def test_the_reference_is_measured_and_stored(tmp_path):
    async def scenario():
        machine, backend, store = build(tmp_path, glass_present=False)
        reading = await machine.measure_reference()
        return reading, store

    reading, store = asyncio.run(scenario())
    assert reading.reference_cm == pytest.approx(16.3)
    assert CalibrationStore(tmp_path / "cal.json").reference_cm == pytest.approx(16.3)


# ---------------------------------------------------------------------- a run


def test_a_run_finds_each_pumps_true_flow(tmp_path):
    truth = {1: 9.0, 2: 18.7, 3: 24.7, 4: 23.6}

    async def scenario():
        machine, backend, store = build(tmp_path, flows=truth)
        run = await machine.start_calibration(seconds=1.0)  # 4 pumps x 1 s fits one glass
        await machine._task
        return run, machine, backend

    run, machine, backend = asyncio.run(scenario())
    assert run.status is CalibrationStatus.FINISHED, run.message
    found = {r.pump: r.ml_per_second for r in run.results}
    for pump, rate in truth.items():
        assert found[pump] == pytest.approx(rate, rel=0.1)
    assert {s.pump: s.ml_per_second for s in machine.slots} == found, "slots take the new rates at once"
    assert CalibrationStore(tmp_path / "cal.json").ml_per_second(3) == found[3]
    assert backend.running == set()


def test_one_pump_can_be_calibrated_on_its_own():
    async def scenario():
        machine, _, _ = build(flows={1: 8.6})
        run = await machine.start_calibration(pumps=[1], seconds=1.0)
        await machine._task
        return run

    run = asyncio.run(scenario())
    assert [r.pump for r in run.results] == [1]


def test_a_nearly_full_glass_stops_the_run_but_keeps_what_was_measured():
    async def scenario():
        machine, backend, _ = build(flows={1: 30.0, 2: 30.0, 3: 30.0, 4: 30.0})
        run = await machine.start_calibration(seconds=6.0)  # 180 ml per pump: two fill the glass
        await machine._task
        return run, machine, backend

    run, machine, backend = asyncio.run(scenario())
    assert run.status is CalibrationStatus.FAILED
    assert run.error.code.value == "SENSOR_FAULT"
    assert "nearly full" in run.message
    assert 1 <= len(run.results) < 4
    assert machine.slots[0].ml_per_second == pytest.approx(30.0, rel=0.1)
    assert backend.running == set()


def test_abort_stops_the_pump_mid_run():
    async def scenario():
        machine, backend, _ = build()
        run = await machine.start_calibration(seconds=20.0)
        waited = 0.0
        while not backend.running and waited < 5:
            await asyncio.sleep(0.01)
            waited += 0.01
        assert backend.running, "a pump should be running by now"
        await machine.abort_calibration()
        await machine._task
        return run, backend

    run, backend = asyncio.run(scenario())
    assert run.status is CalibrationStatus.ABORTED
    assert run.results == []
    assert backend.running == set()


def test_a_pour_and_a_calibration_exclude_each_other():
    async def scenario():
        machine, backend, _ = build(glass_present=False)  # the run waits for a glass forever
        await machine.start_calibration()
        await asyncio.sleep(0.02)
        try:
            await machine.start_calibration()
        except MachineError as exc:
            second = exc
        await machine.abort_calibration()
        await machine._task
        return second

    second = asyncio.run(scenario())
    assert second.code.value == "MACHINE_BUSY"
