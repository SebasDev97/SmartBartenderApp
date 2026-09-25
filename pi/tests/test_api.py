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


def test_status_reports_the_sensor(client):
    sensor = client.get("/api/v1/status").json()["sensor"]
    assert sensor["referenceCm"] == 16.3  # the simulator knows its tray
    assert sensor["glassDiameterMm"] == 58.0


def test_a_live_sensor_reading_sees_the_simulated_glass(client):
    reading = client.get("/api/v1/sensor").json()
    assert reading["distanceCm"] == 15.6
    assert reading["glassPresent"] is True


def test_the_reference_can_be_measured(client, backend):
    backend.remove_glass()
    reading = client.post("/api/v1/sensor/reference").json()
    assert reading["referenceCm"] == 16.3


def test_a_calibration_run_is_started_and_reported(client):
    assert client.get("/api/v1/calibration").status_code == 204

    started = client.post("/api/v1/calibration", json={"pumps": [2], "seconds": 1.0})
    assert started.status_code == 202
    run = started.json()
    assert run["status"] == "running" and run["pumps"] == [2]

    finished = _wait_for(lambda: client.get("/api/v1/calibration").json(), lambda r: r["status"] != "running")
    assert finished["status"] == "finished", finished["message"]
    assert finished["results"][0]["pump"] == 2


def test_calibration_and_pouring_refuse_each_other(client, backend):
    backend.remove_glass()  # so the calibration waits for a glass and stays busy
    client.post("/api/v1/calibration", json={})
    refused = client.post("/api/v1/pours", json=pour_body(str(uuid.uuid4())))
    assert refused.status_code == 409
    assert refused.json()["error"]["code"] == "MACHINE_BUSY"
    assert client.get("/api/v1/status").json()["calibration"]["phase"] == "waiting_glass"

    client.post("/api/v1/calibration/abort")
    run = _wait_for(lambda: client.get("/api/v1/calibration").json(), lambda r: r["status"] != "running")
    assert run["status"] == "aborted"


def test_calibrating_an_unknown_pump_is_rejected(client):
    assert client.post("/api/v1/calibration", json={"pumps": [9]}).status_code == 422


def test_a_cleaning_run_is_started_and_reported(client, backend):
    assert client.get("/api/v1/cleaning").status_code == 204

    started = client.post("/api/v1/cleaning", json={"pumps": [1, 3], "seconds": 1.0, "rounds": 2})
    assert started.status_code == 202
    assert started.json()["status"] == "running"

    finished = _wait_for(lambda: client.get("/api/v1/cleaning").json(), lambda r: r["status"] != "running")
    assert finished["status"] == "finished", finished["message"]
    assert finished["progress"] == 1.0
    assert backend.started == [1, 3, 1, 3]
    assert client.get("/api/v1/status").json()["cleaning"] is None


def test_a_cleaning_run_shows_in_the_snapshot_and_can_be_stopped(client):
    client.post("/api/v1/cleaning", json={"seconds": 20.0})
    status = client.get("/api/v1/status").json()
    assert status["state"] == "busy"
    assert status["cleaning"]["pumps"] == [1, 2, 3, 4]

    refused = client.post("/api/v1/pours", json=pour_body(str(uuid.uuid4())))
    assert refused.status_code == 409
    assert refused.json()["error"]["message"] == "Cleaning is running"

    assert client.post("/api/v1/cleaning/abort").status_code == 202
    run = _wait_for(lambda: client.get("/api/v1/cleaning").json(), lambda r: r["status"] != "running")
    assert run["status"] == "aborted"
    assert client.post("/api/v1/cleaning/abort").status_code == 200


def test_cleaning_rejects_too_many_rounds(client):
    assert client.post("/api/v1/cleaning", json={"rounds": 6}).status_code == 422


def _wait_for(fetch, done, timeout=5.0):
    import time

    deadline = time.monotonic() + timeout
    value = fetch()
    while not done(value) and time.monotonic() < deadline:
        time.sleep(0.02)
        value = fetch()
    return value
