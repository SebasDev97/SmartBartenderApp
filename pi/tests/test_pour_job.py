"""The pour state machine, driven directly against a simulated backend.

No pytest-asyncio here on purpose — asyncio.run keeps the dependency list to what the Pi
actually needs.
"""

import asyncio
import uuid

import pytest

from app.config import load_config
from app.events import EventBus
from app.hardware import SimulatedBackend
from app.machine import Machine, MachineError
from app.models import JobStatus, PourItem, PourRequest, StepKind


def build(speed=50.0, **backend_options):
    backend = SimulatedBackend(4, speed=speed, **backend_options)
    machine = Machine(load_config(None), backend, EventBus(), speed=speed)
    for pump, bottle in enumerate(["tequila", "triple_sec", "lime_juice", None], start=1):
        machine._slots[pump - 1].bottle_id = bottle
    return machine, backend


def request(**extra):
    return PourRequest(
        job_id=str(uuid.uuid4()),
        drink_name="Margarita",
        items=[
            PourItem(bottle_id="tequila", ingredient_name="Tequila", ml=44.0),
            PourItem(bottle_id="triple_sec", ingredient_name="Triple sec", ml=15.0),
        ],
        **extra,
    )


async def _drain(machine):
    """Wait for the running job's task to settle."""
    if machine._task is not None:
        await machine._task


async def _await_pump(backend, timeout=5.0):
    """Wait until a pump is actually running — the glass step comes first."""
    waited = 0.0
    while not backend.running and waited < timeout:
        await asyncio.sleep(0.05)
        waited += 0.05
    return bool(backend.running)


def test_a_pour_runs_every_pump_and_finishes():
    async def scenario():
        machine, backend = build()
        job, created = await machine.start_pour(request())
        assert created
        await _drain(machine)
        return job, backend

    job, backend = asyncio.run(scenario())
    assert job.status is JobStatus.FINISHED
    assert job.progress == 1.0
    assert job.total_ml == 59.0
    assert job.dispensed_ml == pytest.approx(59.0, abs=4.0)
    assert backend.running == set(), "every pump must be off when the job ends"


def test_progress_is_volume_weighted_not_step_counted():
    async def scenario():
        machine, _ = build()
        job, _ = await machine.start_pour(request())
        await _drain(machine)
        return job

    job = asyncio.run(scenario())
    # 5 steps: glass, 2 pours, mix, finish. Step-counting would make the glass worth 20%.
    # Volume weighting gives the three non-pour steps 15% between them.
    weights = Machine._weights(job)
    pour_share = sum(w for w, s in zip(weights, job.steps) if s.kind is StepKind.POUR)
    assert 0.84 < pour_share < 0.86
    # ...and the big pour is worth more than the small one.
    by_step = dict(zip((s.label for s in job.steps), weights))
    assert by_step["Pouring Tequila"] > by_step["Pouring Triple sec"]


def test_abort_stops_the_pumps_and_lands_on_aborted():
    async def scenario():
        machine, backend = build(speed=1.0)  # real time, so there is something to interrupt
        job, _ = await machine.start_pour(request())
        assert await _await_pump(backend), "a pump should be running by now"
        await machine.abort(job.job_id)
        await _drain(machine)
        return job, backend

    job, backend = asyncio.run(scenario())
    assert job.status is JobStatus.ABORTED
    assert job.error.code.value == "ABORTED_BY_USER"
    assert job.dispensed_ml < job.total_ml
    assert backend.running == set()


def test_a_failing_backend_still_stops_every_pump():
    class ExplodingBackend(SimulatedBackend):
        async def start_pump(self, pump):
            await super().start_pump(pump)
            raise RuntimeError("pump 1 stalled")

    async def scenario():
        backend = ExplodingBackend(4)
        machine = Machine(load_config(None), backend, EventBus(), speed=50.0)
        machine._slots[0].bottle_id = "tequila"
        job, _ = await machine.start_pour(
            PourRequest(
                job_id=str(uuid.uuid4()),
                drink_name="Shot",
                items=[PourItem(bottle_id="tequila", ingredient_name="Tequila", ml=44.0)],
            )
        )
        await _drain(machine)
        return job, backend

    job, backend = asyncio.run(scenario())
    assert job.status is JobStatus.FAILED
    assert job.error.code.value == "PUMP_FAULT"
    assert backend.running == set(), "the finally must stop the pumps even on a crash"


def test_manual_steps_become_steps_the_human_must_do():
    async def scenario():
        machine, _ = build()
        job, _ = await machine.start_pour(request(manual_steps=["Salt the rim", "Add ice"]))
        await _drain(machine)
        return job

    job = asyncio.run(scenario())
    manual = [s.label for s in job.steps if s.kind is StepKind.MANUAL]
    assert manual == ["Salt the rim", "Add ice"]


def test_history_survives_the_job_so_a_returning_app_can_reattach():
    async def scenario():
        machine, _ = build()
        job, _ = await machine.start_pour(request())
        await _drain(machine)
        return machine, job

    machine, job = asyncio.run(scenario())
    assert machine.current_job is None, "a finished job is not 'current'"
    assert machine.job(job.job_id) is not None, "...but it is still retrievable"


async def _await(condition, timeout=5.0):
    waited = 0.0
    while not condition() and waited < timeout:
        await asyncio.sleep(0.01)
        waited += 0.01
    return condition()


def test_no_pump_runs_until_a_glass_is_placed():
    async def scenario():
        machine, backend = build(glass_present=False)
        job, _ = await machine.start_pour(request())
        assert await _await(lambda: job.waiting_for_glass)
        await asyncio.sleep(0.1)  # plenty of polls at x50
        assert backend.running == set(), "nothing may pour into an empty tray"
        assert job.steps[0].label == "Place a glass"
        assert job.progress == 0.0

        backend.place_glass()
        await _drain(machine)
        return job

    job = asyncio.run(scenario())
    assert job.status is JobStatus.FINISHED
    assert job.waiting_for_glass is False
    assert job.steps[0].label == "Glass detected"


def test_abort_while_waiting_for_a_glass_ends_the_wait():
    async def scenario():
        machine, backend = build(glass_present=False)
        job, _ = await machine.start_pour(request())
        assert await _await(lambda: job.waiting_for_glass)
        await machine.abort(job.job_id)
        await _drain(machine)
        return job, backend

    job, backend = asyncio.run(scenario())
    assert job.status is JobStatus.ABORTED
    assert job.waiting_for_glass is False
    assert job.dispensed_ml == 0.0


def test_a_glass_that_already_holds_a_drink_is_not_a_glass_to_pour_into():
    async def scenario():
        machine, backend = build(glass_present=False)
        backend.place_glass(liquid_ml=200.0)  # level far above the "empty glass" band
        job, _ = await machine.start_pour(request())
        await asyncio.sleep(0.1)
        waiting = job.waiting_for_glass
        await machine.abort(job.job_id)
        await _drain(machine)
        return waiting

    assert asyncio.run(scenario()) is True


def test_each_pour_step_reports_what_the_sensor_measured():
    async def scenario():
        # Pump 1 truly pours 20% less than the machine believes: the measurement shows it.
        machine, backend = build(flows={1: 10.0})
        job, _ = await machine.start_pour(request())
        await _drain(machine)
        return job

    job = asyncio.run(scenario())
    tequila, triple_sec = [s for s in job.steps if s.kind is StepKind.POUR]
    assert tequila.measured and triple_sec.measured
    assert tequila.dispensed_ml == pytest.approx(44.0 * 0.8, abs=2.0)
    assert triple_sec.dispensed_ml == pytest.approx(15.0, abs=2.0)
    assert job.dispensed_ml == pytest.approx(tequila.dispensed_ml + triple_sec.dispensed_ml, abs=0.01)


def test_a_glass_lifted_mid_pour_keeps_the_estimate_and_the_recipe_goes_on():
    async def scenario():
        machine, backend = build(speed=10.0)
        job, _ = await machine.start_pour(request())
        assert await _await_pump(backend)
        backend.remove_glass()  # into the drip tray it goes
        await _drain(machine)
        return job, backend

    job, backend = asyncio.run(scenario())
    assert job.status is JobStatus.FINISHED, "a lifted glass must not stop the recipe"
    tequila = next(s for s in job.steps if s.kind is StepKind.POUR)
    assert tequila.measured is False
    assert tequila.dispensed_ml == 44.0
    assert backend.running == set()


def test_a_pour_is_refused_before_the_sensor_has_a_reference():
    class RealisticBackend(SimulatedBackend):
        def info(self):
            info = super().info()
            info.default_reference_cm = None  # like real hardware: nothing measured yet
            return info

    async def scenario():
        machine = Machine(load_config(None), RealisticBackend(4), EventBus(), speed=50.0)
        machine._slots[0].bottle_id = "tequila"
        machine._slots[1].bottle_id = "triple_sec"
        await machine.start_pour(request())

    with pytest.raises(MachineError) as raised:
        asyncio.run(scenario())
    assert raised.value.code.value == "NOT_CALIBRATED"
    assert raised.value.status == 503
