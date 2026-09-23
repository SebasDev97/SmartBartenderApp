"""The contract, exercised through HTTP exactly as the app will use it."""

import uuid

from .conftest import LOADED, pour_body


def test_healthz_is_unversioned_and_cheap(client):
    body = client.get("/healthz").json()
    assert body["ok"] is True
    assert body["machineId"] == "bartender-01"


def test_status_reports_four_slots_and_the_simulated_backend(client):
    body = client.get("/api/v1/status").json()
    assert body["backend"] == "simulated"
    assert body["pumpCount"] == 4
    assert body["state"] == "idle"
    assert [s["pump"] for s in body["slots"]] == [1, 2, 3, 4]


def test_put_slots_replaces_the_mapping(client):
    body = client.put("/api/v1/slots", json=LOADED).json()
    assert {s["pump"]: s["bottleId"] for s in body["slots"]} == {
        1: "tequila",
        2: "triple_sec",
        3: "lime_juice",
        4: None,
    }


def test_pour_plans_glass_pours_mix_manual_finish(client):
    job = client.post(
        "/api/v1/pours", json=pour_body(str(uuid.uuid4()), manualSteps=["Salt the rim"])
    ).json()
    assert [s["kind"] for s in job["steps"]] == ["glass", "pour", "pour", "mix", "manual", "finish"]
    assert job["totalMl"] == 59.0


def test_same_job_id_returns_the_same_job_and_does_not_pour_twice(client):
    job_id = str(uuid.uuid4())
    first = client.post("/api/v1/pours", json=pour_body(job_id))
    second = client.post("/api/v1/pours", json=pour_body(job_id))

    assert first.status_code == 201
    assert second.status_code == 200
    assert first.json()["jobId"] == second.json()["jobId"]


def test_a_second_job_while_busy_is_refused_with_the_running_one(client):
    client.post("/api/v1/pours", json=pour_body(str(uuid.uuid4())))
    conflict = client.post("/api/v1/pours", json=pour_body(str(uuid.uuid4())))

    assert conflict.status_code == 409
    body = conflict.json()
    assert body["error"]["code"] == "MACHINE_BUSY"
    assert body["currentJob"]["drinkName"] == "Margarita"


def test_pouring_a_bottle_that_is_not_loaded_is_rejected(client):
    response = client.post(
        "/api/v1/pours",
        json=pour_body(
            str(uuid.uuid4()),
            items=[{"bottleId": "gin", "ingredientName": "Gin", "ml": 40.0}],
        ),
    )
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "SLOT_EMPTY"


def test_an_absurd_volume_is_rejected_before_any_pump_runs(client, backend):
    response = client.post(
        "/api/v1/pours",
        json=pour_body(
            str(uuid.uuid4()),
            items=[{"bottleId": "tequila", "ingredientName": "Tequila", "ml": 900.0}],
        ),
    )
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "VOLUME_OUT_OF_RANGE"
    assert backend.running == set()


def test_led_can_be_switched_off_and_on(client):
    off = client.put("/api/v1/led", json={"enabled": False}).json()
    assert off["enabled"] is False and off["mode"] == "off"

    on = client.put("/api/v1/led", json={"enabled": True, "mode": "spectrum"}).json()
    assert on["mode"] == "spectrum"


def test_pour_mode_belongs_to_the_machine_not_the_app(client):
    assert client.put("/api/v1/led", json={"enabled": True, "mode": "pour"}).status_code == 409


def test_jog_is_capped(client):
    assert client.post("/api/v1/pumps/1/jog", json={"seconds": 0.01}).status_code == 200
    assert client.post("/api/v1/pumps/1/jog", json={"seconds": 600}).status_code == 422


def test_websocket_opens_with_a_full_snapshot(client):
    with client.websocket_connect("/api/v1/events") as ws:
        frame = ws.receive_json()
        assert frame["type"] == "snapshot"
        assert frame["seq"] == 1
        assert frame["data"]["machineId"] == "bartender-01"
