import pytest
from fastapi.testclient import TestClient

from app.api import create_app
from app.config import load_config
from app.events import EventBus
from app.hardware import SimulatedBackend
from app.machine import Machine

LOADED = {
    "slots": [
        {"pump": 1, "bottleId": "tequila"},
        {"pump": 2, "bottleId": "triple_sec"},
        {"pump": 3, "bottleId": "lime_juice"},
        {"pump": 4, "bottleId": None},
    ]
}

MARGARITA_ITEMS = [
    {"bottleId": "tequila", "ingredientName": "Tequila", "ml": 44.0},
    {"bottleId": "triple_sec", "ingredientName": "Triple sec", "ml": 15.0},
]


@pytest.fixture
def backend():
    return SimulatedBackend(4)


@pytest.fixture
def machine(backend):
    # speed=50 keeps a whole pour under a tenth of a second.
    return Machine(load_config(None), backend, EventBus(), speed=50.0)


@pytest.fixture
def client(machine):
    with TestClient(create_app(load_config(None), machine, EventBus())) as c:
        c.put("/api/v1/slots", json=LOADED)
        yield c


def pour_body(job_id, items=None, **extra):
    return {
        "jobId": job_id,
        "drinkName": "Margarita",
        "items": items if items is not None else MARGARITA_ITEMS,
        **extra,
    }
