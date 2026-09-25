"""Cleaning: every pump in turn, round after round, and nothing else may run meanwhile."""

import asyncio
import uuid

import pytest

from app.config import load_config
from app.events import EventBus
from app.hardware import SimulatedBackend
from app.machine import Machine, MachineError
from app.models import CleaningPhase, CleaningStatus, PourItem, PourRequest, SlotAssignment

from .conftest import SPEED


def build(**backend_options):
    backend = SimulatedBackend(4, speed=SPEED, **backend_options)
    machine = Machine(load_config(None), backend, EventBus(), speed=SPEED)
    return machine, backend


async def until_pumping(backend, timeout=5.0):
    waited = 0.0
    while not backend.running and waited < timeout:
        await asyncio.sleep(0.01)
        waited += 0.01
    assert backend.running, "a pump should be running by now"


def test_every_pump_runs_in_turn_for_each_round():
    async def scenario():
        machine, backend = build()
        run = await machine.start_cleaning(seconds=1.0, rounds=2)
        await machine._task
        return run, machine, backend

    run, machine, backend = asyncio.run(scenario())
    assert run.status is CleaningStatus.FINISHED, run.message
    assert run.phase is CleaningPhase.DONE
    assert run.progress == 1.0
    assert backend.started == [1, 2, 3, 4, 1, 2, 3, 4]
    assert backend.running == set()
    assert machine.current_cleaning is None and machine.last_cleaning is run


def test_the_defaults_come_from_config_yaml():
    async def scenario():
        machine, _ = build()
        run = await machine.start_cleaning()
        machine._abort.set()
        await machine._task
        return run

    run = asyncio.run(scenario())
    assert (run.seconds, run.rounds, run.pumps) == (10.0, 2, [1, 2, 3, 4])


def test_a_subset_of_pumps_can_be_cleaned():
    async def scenario():
        machine, backend = build()
        await machine.start_cleaning(pumps=[2, 4], seconds=1.0, rounds=1)
        await machine._task
        return backend

    assert asyncio.run(scenario()).started == [2, 4]


def test_cleaning_needs_no_bottles_and_no_tray_reference():
    async def scenario():
        machine, backend = build()
        machine._default_reference = None  # as if the tray had never been measured
        assert machine.reference_cm is None
        await machine.set_slots([SlotAssignment(pump=p, bottle_id=None) for p in range(1, 5)])
        run = await machine.start_cleaning(seconds=1.0, rounds=1)
        await machine._task
        return run, machine

    run, machine = asyncio.run(scenario())
    assert run.status is CleaningStatus.FINISHED
    assert all(slot.bottle_id is None for slot in machine.slots), "the slot table is left alone"


def test_abort_stops_the_pump_mid_run():
    async def scenario():
        machine, backend = build()
        run = await machine.start_cleaning(seconds=20.0)
        await until_pumping(backend)
        await machine.abort_cleaning()
        await machine._task
        return run, backend

    run, backend = asyncio.run(scenario())
    assert run.status is CleaningStatus.ABORTED
    assert run.error.code.value == "ABORTED_BY_USER"
    assert run.progress < 1.0
    assert backend.running == set()


def test_nothing_else_may_drive_a_pump_while_cleaning():
    async def scenario():
        machine, backend = build()
        await machine.set_slots([SlotAssignment(pump=1, bottle_id="vodka")])
        await machine.start_cleaning(seconds=20.0)
        await until_pumping(backend)
        refusals = []
        for attempt in (
            lambda: machine.start_pour(
                PourRequest(
                    job_id=str(uuid.uuid4()),
                    drink_name="Vodka",
                    items=[PourItem(bottle_id="vodka", ingredient_name="Vodka", ml=30)],
                )
            ),
            lambda: machine.start_calibration(),
            lambda: machine.start_cleaning(),
            lambda: machine.jog(1, 1.0),
            lambda: machine.set_slots([]),
        ):
            with pytest.raises(MachineError) as caught:
                await attempt()
            refusals.append(caught.value)
        await machine.abort_cleaning()
        await machine._task
        return refusals

    for refusal in asyncio.run(scenario()):
        assert refusal.code.value == "MACHINE_BUSY" and refusal.status == 409


def test_cleaning_waits_for_a_pour_or_a_calibration_to_end():
    async def scenario():
        machine, _ = build(glass_present=False)  # both wait for a glass forever
        await machine.set_slots([SlotAssignment(pump=1, bottle_id="vodka")])
        refusals = []

        await machine.start_calibration()
        with pytest.raises(MachineError) as caught:
            await machine.start_cleaning()
        refusals.append(caught.value)
        await machine.abort_calibration()
        await machine._task

        job, _ = await machine.start_pour(
            PourRequest(
                job_id=str(uuid.uuid4()),
                drink_name="Vodka",
                items=[PourItem(bottle_id="vodka", ingredient_name="Vodka", ml=30)],
            )
        )
        with pytest.raises(MachineError) as caught:
            await machine.start_cleaning()
        refusals.append(caught.value)
        await machine.abort(job.job_id)
        await machine._task
        return refusals

    for refusal in asyncio.run(scenario()):
        assert refusal.code.value == "MACHINE_BUSY"


@pytest.mark.parametrize(
    "options",
    [
        {"pumps": [9]},
        {"pumps": [1, 1]},
        {"seconds": 0},
        {"seconds": 31},
        {"rounds": 0},
        {"rounds": 6},
    ],
)
def test_out_of_range_requests_are_refused(options):
    async def scenario():
        machine, backend = build()
        with pytest.raises(MachineError) as caught:
            await machine.start_cleaning(**options)
        return caught.value, machine, backend

    refusal, machine, backend = asyncio.run(scenario())
    assert refusal.status == 422
    assert machine.last_cleaning is None and backend.started == []


def test_aborting_when_nothing_ever_ran_is_a_404():
    async def scenario():
        machine, _ = build()
        with pytest.raises(MachineError) as caught:
            await machine.abort_cleaning()
        return caught.value

    assert asyncio.run(scenario()).status == 404
