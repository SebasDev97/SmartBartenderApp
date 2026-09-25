# Smart Bartender — machine API

The contract between the **Android app** and the **Raspberry Pi** that runs the pumps, the
glass sensor and the display (through an Arduino on USB — invisible at this level). Both sides are written from this document; if the two disagree, this file wins.

- **Base URL** — `http://<pi-address>:8080`
- **Version prefix** — `/api/v1` (only `/healthz` sits outside it)
- **Content type** — `application/json` everywhere, UTF-8
- **Field naming** — `camelCase`, matching kotlinx.serialization's defaults on the Android side
- **Auth** — none by default. If `server.token` is set in `config.yaml`, every `/api/v1` request
  must carry `X-Bartender-Token: <token>`; without it the header is ignored.

## Who owns what

The **app owns recipes**. It fetches them from TheCocktailDB, matches ingredients to bottles,
parses measures into millilitres, and sends the Pi a finished list of "pump N, 44 ml".

The **Pi owns the machine**. It knows which bottle is in which pump, how fast each pump runs
(it calibrates them itself), whether a glass is under the nozzle, how much actually went into
it, and how far along a pour is. It never hears about TheCocktailDB.

The shared vocabulary is the **bottle id** from the app's `BottleCatalog` — `vodka`,
`light_rum`, `lime_juice`, `triple_sec`, and so on. These strings appear in slot configuration
and pour requests, and they are the only thing both sides must agree on beyond this document.

**The Pi is the source of truth for a pour in progress.** The app issues one command and then
renders whatever the Pi pushes over the WebSocket. It never runs its own timer. This is what
makes a pour survive the app being backgrounded, killed, or moved to another phone.

---

## 1. Core objects

### 1.1 `MachineStatus`

The complete state of the machine. Returned by `GET /api/v1/status`, and pushed as the
`snapshot` WebSocket event whenever anything structural changes.

```json
{
  "machineId": "bartender-01",
  "name": "Smart Bartender",
  "firmware": "0.1.0",
  "backend": "simulated",
  "state": "idle",
  "pumpCount": 4,
  "maxPourMl": 250.0,
  "uptimeS": 1843,
  "slots": [
    { "pump": 1, "bottleId": "tequila",    "mlPerSecond": 12.5 },
    { "pump": 2, "bottleId": "triple_sec", "mlPerSecond": 12.5 },
    { "pump": 3, "bottleId": "lime_juice", "mlPerSecond": 11.0 },
    { "pump": 4, "bottleId": null,         "mlPerSecond": 12.5 }
  ],
  "led": { "enabled": true, "mode": "spectrum", "colorHex": "#2AF5E4", "brightness": 0.6, "cycleMillis": 7000 },
  "currentJob": null,
  "fault": null,
  "sensor": { "referenceCm": 16.3, "glassDiameterMm": 58.0, "calibratedAtMs": 1758531234567 },
  "calibration": null,
  "cleaning": null
}
```

| Field                 | Type                        | Notes                                                                  |
|-----------------------|-----------------------------|------------------------------------------------------------------------|
| `machineId`           | string                      | Stable id from `config.yaml`.                                          |
| `backend`             | `simulated` \| `arduino`    | Whether real pumps are being driven. The app shows this.               |
| `state`               | `idle` \| `busy` \| `fault` | `busy` while a job runs.                                               |
| `pumpCount`           | int                         | Always 4 on this machine; the app's `BottleCatalog.MAX_SLOTS`.         |
| `maxPourMl`           | float                       | Total volume ceiling for one drink. The app scales a plan down to fit. |
| `slots[].pump`        | int                         | **1-based.** Pump 1 is the leftmost bottle.                            |
| `slots[].bottleId`    | string \| null              | `null` means the slot is empty.                                        |
| `slots[].mlPerSecond` | float                       | Calibrated flow. See `POST /api/v1/calibration`.                       |
| `currentJob`          | `PourJob` \| null           | The running job, if any.                                               |
| `fault`               | `Fault` \| null             | Set when `state` is `fault`.                                           |
| `sensor.referenceCm`  | float \| null               | Sensor to the empty tray. `null` until measured — pours are refused.   |
| `sensor.glassDiameterMm` | float                    | The straight glass measured volumes assume.                            |
| `sensor.calibratedAtMs` | int \| null               | When the pumps were last calibrated.                                   |
| `calibration`         | `CalibrationRun` \| null    | The running calibration, if any. `state` is `busy` meanwhile.          |
| `cleaning`            | `CleaningRun` \| null       | The running cleaning, if any. `state` is `busy` meanwhile.             |

### 1.2 `PourJob`

One drink being made. Shaped so it maps directly onto the app's existing preparation overlay.

```json
{
  "jobId": "1f9d0f4e-1c7a-4f4b-9f8b-2f0e5c8a7b31",
  "drinkId": "11007",
  "drinkName": "Margarita",
  "status": "running",
  "steps": [
    { "index": 0, "kind": "glass",  "label": "Glass detected",     "detail": "Cocktail glass",    "pump": null, "ml": null, "dispensedMl": null, "measured": false },
    { "index": 1, "kind": "pour",   "label": "Pouring Tequila",    "detail": "44 ml",             "pump": 1,    "ml": 44.0, "dispensedMl": 43.1, "measured": true },
    { "index": 2, "kind": "pour",   "label": "Pouring Triple sec", "detail": "15 ml",             "pump": 2,    "ml": 15.0, "dispensedMl": 6.2,  "measured": false },
    { "index": 3, "kind": "manual", "label": "Salt the rim",       "detail": "Add this yourself", "pump": null, "ml": null, "dispensedMl": null, "measured": false },
    { "index": 4, "kind": "finish", "label": "Finishing touch",    "detail": "Garnish and serve", "pump": null, "ml": null, "dispensedMl": null, "measured": false }
  ],
  "currentStepIndex": 2,
  "progress": 0.52,
  "totalMl": 89.0,
  "dispensedMl": 50.2,
  "startedAtMs": 1758531234567,
  "finishedAtMs": null,
  "error": null,
  "waitingForGlass": false
}
```

`status` moves through:

```
queued ──► running ──(per step)──► finished
             │                        ▲
             ├── abort() ──► aborting ┴─► aborted
             └── exception ─────────────► failed
```

`kind` is one of:

| `kind`   | Meaning                                                                          |
|----------|----------------------------------------------------------------------------------|
| `glass`  | **Waits for a glass under the nozzle** — see below. Nothing pours before it.     |
| `pour`   | A pump runs. Carries `pump`, `ml`, and a live `dispensedMl`.                     |
| `mix`    | A stir/settle pause after the last pour.                                         |
| `manual` | **Something the human must do** — ice, mint, a salted rim. No pump can serve it. |
| `finish` | "Garnish and serve" — the closing beat.                                          |

**The glass step.** The step starts as "Place a glass" with `waitingForGlass: true`. The Pi
polls the ultrasonic sensor and moves on once three readings in a row are 0.5–5 cm closer than
the empty tray (`sensor.referenceCm`) — an empty glass standing on it. There is no timeout; the
user ends the wait with abort. The label then becomes "Glass detected" and `waitingForGlass`
goes back to `false`. A glass taken away later does **not** stop the recipe (the tray catches
it); it only makes that step's measurement fall back to the estimate.

**`dispensedMl` and `measured`.** While a pump runs, `dispensedMl` is the time-based estimate
(pump time × calibrated flow), so progress stays smooth. After each pour step the liquid is
left to settle for a second, the level is measured, and `dispensedMl` is replaced with the
sensor's volume (π·r²·rise in a straight glass of `sensor.glassDiameterMm`); `measured` becomes
`true`. If the reading is implausible — ≤ 0, or more than twice the estimate plus 10 ml — the
estimate stays and `measured` stays `false`. A stopped pour is not measured.

`progress` is **volume-weighted**, computed by the Pi: pour steps are weighted by their share of
`totalMl`, and the non-pour steps share a small fixed remainder. It is much truer than counting
steps, which would treat "Garnish and serve" as a fifth of the work.

### 1.3 `LedState`

```json
{ "enabled": true, "mode": "spectrum", "colorHex": "#2AF5E4", "brightness": 0.6, "cycleMillis": 7000 }
```

| `mode`     | Behaviour                                                                                                  |
|------------|------------------------------------------------------------------------------------------------------------|
| `off`      | Strip dark. Equivalent to `enabled: false`.                                                                |
| `solid`    | One colour, `colorHex`.                                                                                    |
| `spectrum` | The app's cycle — cyan → blue → violet → magenta → amber → lime → cyan, one lap per `cycleMillis`.         |
| `pour`     | Reactive fill effect. **Set by the Pi itself** while a job runs; the previous mode is restored afterwards. |

`enabled: false` always wins over `mode`. The app only ever sends `spectrum` or `off`.
The current machine has no LED strip: the state is kept and echoed back (the app's on-screen
show uses it) but drives nothing.

### 1.4 `Fault`

```json
{ "code": "PUMP_FAULT", "message": "Pump 2 did not reach target", "recoverable": false }
```

### 1.5 `CalibrationRun`

One run of the pump calibration. Pushed whole as the `calibration` WebSocket event on every
phase change, and returned by the calibration endpoints.

```json
{
  "runId": "5b0f…",
  "status": "running",
  "phase": "pumping",
  "pumps": [1, 2, 3, 4],
  "currentPump": 2,
  "message": "Pump 2 runs for 3 s",
  "results": [
    { "pump": 1, "mlPerSecond": 18.62, "volumeMl": 55.9, "seconds": 3.006, "startDistanceCm": 15.6, "endDistanceCm": 13.48 }
  ],
  "startedAtMs": 1758531234567,
  "finishedAtMs": null,
  "error": null
}
```

| Field    | Values                                                                        |
|----------|-------------------------------------------------------------------------------|
| `status` | `running` → `finished` \| `failed` \| `aborted`                                |
| `phase`  | `waiting_glass` → per pump: `measuring` → `pumping` → `settling` → `measuring`, then `done` |
| `error`  | A `Fault`. `SENSOR_FAULT` (recoverable) for "no reading", "level did not rise" and "glass nearly full" |

The method is the group's calibration script: wait for the empty glass, then per pump measure
the level, run the pump for `seconds`, settle 2 s, measure again, and divide the volume by the
time the pump actually ran. Each pump's result replaces its `mlPerSecond` immediately and is
saved on the Pi — also when a later pump fails, so a nearly-full glass loses nothing.

### 1.6 `CleaningRun`

One rinse of the pump lines. Pushed whole as the `cleaning` WebSocket event on every phase
change and every ~0.5 s while a pump runs, and returned by the cleaning endpoints.

```json
{
  "runId": "a41c…",
  "status": "running",
  "phase": "pumping",
  "pumps": [1, 2, 3, 4],
  "rounds": 2,
  "seconds": 10.0,
  "currentRound": 1,
  "currentPump": 3,
  "progress": 0.31,
  "message": "Rinsing pump 3 (round 1 of 2)",
  "startedAtMs": 1758531234567,
  "finishedAtMs": null,
  "error": null
}
```

| Field      | Values                                                                        |
|------------|-------------------------------------------------------------------------------|
| `status`   | `running` → `finished` \| `failed` \| `aborted`                                |
| `phase`    | per pump: `pumping`, then `pausing` before the next one; `done` at the end    |
| `progress` | Pump time done over pump time planned (`rounds × pumps × seconds`), 0–1       |
| `error`    | A `Fault`: `ABORTED_BY_USER` when stopped, `PUMP_FAULT` on hardware trouble    |

The user swaps each bottle for warm water and puts a large container under the nozzle. Then
every pump in `pumps` runs for `seconds` in turn, one at a time with a short pause between them,
and the whole row repeats `rounds` times. **The glass sensor is not used**: the container holds
more than a glass, and the app asks the user to confirm it is there. Bottle ids are ignored, and
the slot table, the calibration and the pour history are left untouched.

### 1.7 `SensorReading`

```json
{ "distanceCm": 15.6, "glassPresent": true, "referenceCm": 16.3 }
```

`distanceCm` is `null` when the sensor got no valid echo. `glassPresent` is the same 0.5–5 cm
test the glass step uses, on this single reading.

---

## 2. REST endpoints

| Method | Path                          | Purpose                                  |
|--------|-------------------------------|------------------------------------------|
| `GET`  | `/healthz`                    | Liveness probe                           |
| `GET`  | `/api/v1/status`              | Full `MachineStatus`                     |
| `GET`  | `/api/v1/slots`               | Current pump → bottle mapping            |
| `PUT`  | `/api/v1/slots`               | Replace the mapping                      |
| `POST` | `/api/v1/pours`               | Start a pour                             |
| `GET`  | `/api/v1/pours/current`       | The running job, or `204`                |
| `GET`  | `/api/v1/pours/{jobId}`       | Re-attach to a known job                 |
| `POST` | `/api/v1/pours/{jobId}/abort` | Stop everything                          |
| `GET`  | `/api/v1/led`                 | Current `LedState`                       |
| `PUT`  | `/api/v1/led`                 | Set the LED show                         |
| `POST` | `/api/v1/pumps/{pump}/jog`    | Run one pump for N seconds (priming, testing) |
| `GET`  | `/api/v1/sensor`              | One live `SensorReading`                 |
| `POST` | `/api/v1/sensor/reference`    | Measure the empty tray                   |
| `POST` | `/api/v1/calibration`         | Start a calibration run                  |
| `GET`  | `/api/v1/calibration`         | The running or last run, or `204`        |
| `POST` | `/api/v1/calibration/abort`   | Stop the calibration                     |
| `POST` | `/api/v1/cleaning`            | Start rinsing the pump lines             |
| `GET`  | `/api/v1/cleaning`            | The running or last rinse, or `204`      |
| `POST` | `/api/v1/cleaning/abort`      | Stop the rinse                           |

### `GET /healthz`

Deliberately outside `/api/v1` and free of auth, so the app's "Test connection" button has
something cheap and stable to hit.

```json
{ "ok": true, "machineId": "bartender-01", "firmware": "0.1.0" }
```

### `GET /api/v1/status`

Returns `MachineStatus` (§1.1). No parameters.

### `PUT /api/v1/slots`

The app pushes its whole rack whenever a bottle is loaded or ejected, and again on every
reconnect, so the Pi converges on what the app believes.

Request — always all four slots, `null` for an empty one:

```json
{
  "slots": [
    { "pump": 1, "bottleId": "tequila" },
    { "pump": 2, "bottleId": "triple_sec" },
    { "pump": 3, "bottleId": "lime_juice" },
    { "pump": 4, "bottleId": null }
  ]
}
```

Response `200` — the updated `MachineStatus`. Also broadcasts a `slots` event.

Errors: `409 MACHINE_BUSY` while a pour is running (changing the map mid-pour would send the
wrong liquid). `422` if `pump` is out of range or a pump appears twice.

### `POST /api/v1/pours`

Request — the app has already resolved ingredients to bottles and measures to millilitres:

```json
{
  "jobId": "1f9d0f4e-1c7a-4f4b-9f8b-2f0e5c8a7b31",
  "drinkId": "11007",
  "drinkName": "Margarita",
  "glass": "Cocktail glass",
  "items": [
    { "bottleId": "tequila",    "ingredientName": "Tequila",    "ml": 44.0 },
    { "bottleId": "triple_sec", "ingredientName": "Triple sec", "ml": 15.0 },
    { "bottleId": "lime_juice", "ingredientName": "Lime juice", "ml": 30.0 }
  ],
  "manualSteps": ["Salt the rim"]
}
```

`items` are poured **in the order given**. The Pi resolves each `bottleId` to a pump from its own
slot table — the app never names a pump here, so a stale rack in the app surfaces as a clean
`422 SLOT_EMPTY` instead of the wrong liquid.

Response `201` with the `PourJob` (§1.2) in `queued`/`running`. Progress then arrives over the
WebSocket; polling `GET /api/v1/pours/{jobId}` also works.

#### Idempotency and re-attaching

The **app** generates `jobId` (UUID v4) and repeats it in an `Idempotency-Key` header. That makes
the call safe to retry over a flaky link:

| Situation                        | Result                                                         |
|----------------------------------|----------------------------------------------------------------|
| New `jobId`, machine idle        | `201 Created` + the new `PourJob`                              |
| `jobId` the Pi already knows     | `200 OK` + that same `PourJob`. **No second pour.**            |
| New `jobId`, another job running | `409 Conflict` + `MACHINE_BUSY`, with `currentJob` in the body |

The Pi keeps the last 20 jobs in memory, so `GET /api/v1/pours/{jobId}` still answers after a job
ends. The app persists the active `jobId`; on a cold start it re-attaches and drops the user back
into a live overlay at the right percentage.

Errors: `409 MACHINE_BUSY` (a pour, a calibration or a cleaning is running), `422 UNKNOWN_BOTTLE` (no such
bottle id), `422 SLOT_EMPTY` (that bottle isn't loaded), `422 VOLUME_OUT_OF_RANGE` (an item over
150 ml, or a total over `maxPourMl`), `503 NOT_CONFIGURED` (no slots configured yet),
`503 NOT_CALIBRATED` (the sensor has no tray reference, so no glass can be detected).

### `GET /api/v1/pours/current`

`200` with the `PourJob`, or `204 No Content` when idle.

### `POST /api/v1/pours/{jobId}/abort`

Empty body. Responds `202` immediately with the job in `aborting`; every pump stops, and a final
`pour` event carries `aborted`. Aborting an already-finished job is a no-op that returns `200`.

### `PUT /api/v1/led`

```json
{ "enabled": true, "mode": "spectrum", "cycleMillis": 7000 }
```

All fields optional except `enabled`. Response `200` with the resulting `LedState`; also
broadcasts a `led` event. Rejected with `409` only if `mode` is `pour` (that mode belongs to the
Pi).

### `POST /api/v1/pumps/{pump}/jog`

Runs one pump by hand — to prime its tube before calibrating, or to check a relay clicks.

```json
{ "seconds": 2.0 }
```

Response `200`: `{ "pump": 1, "seconds": 2.0 }`, once the pump has stopped. Capped at 30 seconds.
`409 MACHINE_BUSY` during a pour or a calibration.

### `GET /api/v1/sensor`

One `SensorReading` (§1.7), taken now. Safe during a pour. `500 SENSOR_FAULT` if the Arduino
does not answer.

### `POST /api/v1/sensor/reference`

Empty body. **Take every glass off the tray first.** Measures the tray (20 readings, the two
highest and lowest dropped), stores it as `sensor.referenceCm`, and returns the `SensorReading`.
Every glass is detected relative to this, so measure it again if the sensor or tray moves.
`409 MACHINE_BUSY` while busy, `422 SENSOR_FAULT` when no valid reading came back. A snapshot
event follows.

### `POST /api/v1/calibration`

```json
{ "pumps": [1, 2, 3, 4], "seconds": 3.0 }
```

Both fields optional: every pump, and `calibration.pump_seconds` from `config.yaml`. Prime the
tubes first (jog each pump into a cup) — a dry tube under-reports its first run. Then place the
**empty** glass; the run waits for it like a pour does.

Response `202` with the `CalibrationRun` (§1.5). Progress arrives as `calibration` events.
Errors: `409 MACHINE_BUSY`, `503 NOT_CALIBRATED` (no tray reference yet), `422` for an unknown or
repeated pump or `seconds` outside 0–30.

### `GET /api/v1/calibration`

`200` with the running run, or the last one after it ended; `204` if none ran since boot.

### `POST /api/v1/calibration/abort`

Empty body. `202` with the run while it stops (the pump goes off at once), `200` if it had
already ended, `404` if none ever ran.

### `POST /api/v1/cleaning`

```json
{ "pumps": [1, 2, 3, 4], "seconds": 10.0, "rounds": 2 }
```

All fields optional: every pump, and `cleaning.pump_seconds` / `cleaning.rounds` from
`config.yaml`. Needs no loaded slots and no tray reference.

Response `202` with the `CleaningRun` (§1.6). Progress arrives as `cleaning` events.
Errors: `409 MACHINE_BUSY`, and `422` for an unknown or repeated pump, `seconds` outside 0–30,
or `rounds` outside 1–5. While it runs, every other command that drives a pump or changes the
slots (`pours`, `calibration`, `jog`, `PUT /slots`, `sensor/reference`) gets `409 MACHINE_BUSY`.

### `GET /api/v1/cleaning`

`200` with the running rinse, or the last one after it ended; `204` if none ran since boot.

### `POST /api/v1/cleaning/abort`

Empty body. `202` with the run while it stops (the pump goes off at once), `200` if it had
already ended, `404` if none ever ran.

### Error shape

Every `4xx`/`5xx` uses the same body:

```json
{ "error": { "code": "MACHINE_BUSY", "message": "A pour is already running" }, "currentJob": null }
```

| Code                  | HTTP | Meaning                                         |
|-----------------------|------|-------------------------------------------------|
| `MACHINE_BUSY`        | 409  | A pour, calibration or cleaning is running; `currentJob` is included for a pour |
| `NOT_CONFIGURED`      | 503  | No slots configured                             |
| `UNKNOWN_BOTTLE`      | 422  | Bottle id not recognised                        |
| `SLOT_EMPTY`          | 422  | That bottle is not loaded in any pump           |
| `VOLUME_OUT_OF_RANGE` | 422  | Item over 150 ml, or total over `maxPourMl`     |
| `NOT_CALIBRATED`      | 503  | No tray reference: measure it first             |
| `SENSOR_FAULT`        | 422/500 | The sensor gave no usable reading            |
| `PUMP_FAULT`          | 500  | Hardware trouble; the machine is now in `fault` |
| `ABORTED_BY_USER`     | —    | Only ever appears inside `PourJob.error`        |

---

## 3. WebSocket — `ws://<pi-address>:8080/api/v1/events`

Live machine state. Connect once and leave it open.

**Every frame uses one envelope, and carries the whole object it concerns — never a delta.**
That makes the client a plain replace, removes any chance of an ordering bug, and makes a
reconnect self-healing.

```json
{ "type": "pour", "seq": 41, "ts": 1758531236120, "data": { "...": "the full PourJob" } }
```

`seq` is a per-connection counter for logging and de-duplication only. Gaps are not recovered by
replay — a reconnect sends a fresh `snapshot`, which *is* the recovery mechanism.

| `type`      | `data`                | Sent when                                                                                                                              |
|-------------|-----------------------|----------------------------------------------------------------------------------------------------------------------------------------|
| `snapshot`  | full `MachineStatus`  | Immediately on connect, and after any slot / LED / state change                                                                        |
| `pour`      | full `PourJob`        | Job starts, every step change, and every ~200 ms while a pump runs. The last frame of a job carries `finished`, `aborted`, or `failed` |
| `led`       | `LedState`            | LED changed from anywhere, including the Pi's own `pour` mode switch                                                                   |
| `slots`     | `{ "slots": [...] }`  | Slot mapping or a pump's calibrated `mlPerSecond` changed                                                                             |
| `calibration` | `CalibrationRun`    | A calibration run starts, changes phase, gets a result, and ends                                                                       |
| `cleaning`  | `CleaningRun`         | A cleaning run starts, changes pump or phase, every ~0.5 s while a pump runs, and ends                                                 |
| `fault`     | `Fault`               | Hardware trouble. Also flips `state` to `fault`                                                                                        |
| `heartbeat` | `{ "uptimeS": 1843 }` | Every 5 seconds                                                                                                                        |

**The client sends nothing.** All commands go over REST, which keeps the socket a pure one-way
state feed and the Pi's handler trivial.

Liveness: the 5-second `heartbeat` plus WebSocket ping/pong. A client should treat 15 seconds of
silence as a dead link and reconnect with backoff (1s → 2s → 4s → 8s → 15s).

**A dropped socket does not abort a pour.** The machine finishes the drink; the app re-attaches
when it comes back. This is deliberate — you do not want a Wi-Fi hiccup leaving half a Margarita
in the glass.

---

## 4. curl cookbook

```bash
PI=http://localhost:8080

# Is it alive?
curl -s $PI/healthz | python3 -m json.tool

# What does it think it is?
curl -s $PI/api/v1/status | python3 -m json.tool

# Load the rack
curl -s -X PUT $PI/api/v1/slots \
  -H 'Content-Type: application/json' \
  -d '{"slots":[{"pump":1,"bottleId":"tequila"},{"pump":2,"bottleId":"triple_sec"},{"pump":3,"bottleId":"lime_juice"},{"pump":4,"bottleId":null}]}' \
  | python3 -m json.tool

# Watch the live feed in another terminal  (brew install websocat)
websocat ws://localhost:8080/api/v1/events

# Pour a Margarita
JOB=$(uuidgen)
curl -s -X POST $PI/api/v1/pours \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $JOB" \
  -d "{\"jobId\":\"$JOB\",\"drinkId\":\"11007\",\"drinkName\":\"Margarita\",\"glass\":\"Cocktail glass\",
       \"items\":[{\"bottleId\":\"tequila\",\"ingredientName\":\"Tequila\",\"ml\":44.0},
                  {\"bottleId\":\"triple_sec\",\"ingredientName\":\"Triple sec\",\"ml\":15.0},
                  {\"bottleId\":\"lime_juice\",\"ingredientName\":\"Lime juice\",\"ml\":30.0}],
       \"manualSteps\":[\"Salt the rim\"]}" \
  | python3 -m json.tool

# Same call again -> 200, the same job, no second pour
# Change of heart
curl -s -X POST $PI/api/v1/pours/$JOB/abort | python3 -m json.tool

# LEDs
curl -s -X PUT $PI/api/v1/led -H 'Content-Type: application/json' \
  -d '{"enabled":true,"mode":"spectrum","cycleMillis":7000}' | python3 -m json.tool
curl -s -X PUT $PI/api/v1/led -H 'Content-Type: application/json' \
  -d '{"enabled":false}' | python3 -m json.tool

# Prime pump 1's tube (into a cup)
curl -s -X POST $PI/api/v1/pumps/1/jog -H 'Content-Type: application/json' -d '{"seconds":2}'

# Glass sensor: one reading, then measure the empty tray (no glass on it!)
curl -s $PI/api/v1/sensor | python3 -m json.tool
curl -s -X POST $PI/api/v1/sensor/reference | python3 -m json.tool

# Calibrate every pump into the empty glass, then watch it finish
curl -s -X POST $PI/api/v1/calibration -H 'Content-Type: application/json' -d '{}' | python3 -m json.tool
curl -s $PI/api/v1/calibration | python3 -m json.tool

# ...or only pump 1, for 3 s
curl -s -X POST $PI/api/v1/calibration -H 'Content-Type: application/json' -d '{"pumps":[1],"seconds":3}'

# Rinse every pump with warm water: 10 s each, twice round (a large container under the nozzle!)
curl -s -X POST $PI/api/v1/cleaning -H 'Content-Type: application/json' -d '{"seconds":10,"rounds":2}' | python3 -m json.tool
curl -s -X POST $PI/api/v1/cleaning/abort | python3 -m json.tool
```
