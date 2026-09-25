# Smart Bartender — machine service

The Raspberry Pi half of the project. It owns the pumps, the glass sensor and the display; the
Android app owns the recipes. The Pi drives no pins itself: the pump relays, an ultrasonic
sensor above the glass and a 16x2 LCD hang off an **Arduino on USB**, running the group's
relay/sensor/LCD sketch, and the Pi tells it what to do over serial. They meet at the HTTP + WebSocket contract in **[API.md](API.md)** — read
that first, it is the document both sides are written from.

The service runs perfectly well with no hardware attached, which is the point: the app can be
built, demoed and tested against `--simulate` long before a pump exists.

**Deploying this onto an actual Raspberry Pi for the first time? Follow
[DEPLOY.md](DEPLOY.md)** — it is the step-by-step, from a fresh Pi to a calibrated machine.

## Run it

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt

# On a laptop, or the Pi before anything is wired up.
# --speed 4 divides every delay, so a full drink takes a couple of seconds.
.venv/bin/python -m app.main --simulate --speed 4 --port 8080

# On the real machine, with the Arduino plugged in.
cp config.example.yaml config.yaml    # then set arduino.port
.venv/bin/python -m app.main --arduino --config config.yaml
```

Python 3.9 or newer.

Then point the app at it: **Settings → Machine link**. From the Android emulator the host
machine is **`10.0.2.2`**, not `localhost`. From a physical phone, use the computer's LAN
address (`ipconfig getifaddr en0` on macOS) with the phone on the same Wi-Fi.

Check it by hand:

```bash
curl -s localhost:8080/healthz
curl -s localhost:8080/api/v1/status | python3 -m json.tool
websocat ws://localhost:8080/api/v1/events     # brew install websocat
```

API.md ends with a full curl cookbook, including a Margarita.

## Tests

```bash
.venv/bin/python -m pytest tests/ -q
```

`tests/test_api.py` drives the contract over HTTP; `tests/test_pour_job.py` drives the pour
state machine directly, including that a crash mid-pour still stops every pump;
`tests/test_arduino.py` pins the serial protocol against a fake board — retries, a pulled
cable, reconnecting, distance parsing, the LCD, and a whole pour; `tests/test_sensor.py` and
`tests/test_calibration.py` cover the glass detection, the volume maths and calibration runs
against the simulator's model of a glass filling up.

## Layout

| File | What it does |
| --- | --- |
| `API.md` | **The contract.** If code and this file disagree, the file wins |
| `DEPLOY.md` | Step-by-step: getting this running on a real Pi |
| `app/models.py` | The contract in pydantic — snake_case here, camelCase on the wire |
| `app/machine.py` | Slot table, job registry, the pour state machine and calibration runs |
| `app/sensor.py` | Glass detection and ml-from-level maths, ported from the group's scripts |
| `app/calibration.py` | Pump rates and the tray reference, in `~/pump_calibration.json` |
| `app/lcd.py` | Every string the 16x2 display shows |
| `app/safe_off.py` | `ALL OFF` from a fresh process; systemd runs it after the service stops |
| `app/api.py` | The routes and the `/events` WebSocket |
| `app/leds.py` | LED show state, echoed to the app (this machine has no strip) |
| `app/events.py` | Fan-out to every connected client |
| `app/hardware/backend.py` | Ten methods. The entire hardware surface |
| `app/hardware/simulated.py` | Logs instead of pouring, and models the glass filling up. The default |
| `app/hardware/arduino.py` | The serial link to the Arduino: the protocol, retries, reconnect |
| `calibration.example.json` | The group's first calibration run, in the file's format |

## Wiring

```
phone ──Wi-Fi──▶ Raspberry Pi ──USB serial──▶ Arduino ──▶ relays ──▶ pumps
                                                      ├──▶ HC-SR04 ultrasonic sensor (above the glass)
                                                      └──▶ 16x2 LCD
```

**Pin numbers live only in the Arduino sketch.** The Pi addresses pumps by number (1–4) and
never learns a pin. The sketch is the group's own and is not part of this repo; this service
treats it as a black box that speaks the protocol below.

Pumps must have their own supply; neither board can drive a motor from its 5 V rail, and
back-EMF from a DC motor will reset the Arduino or worse. Tie the grounds together.

### The serial link

Plain text at **115200 baud**, one command per line, one reply line each:

| Command | Reply | Effect |
| --- | --- | --- |
| `RELAY <n> ON` / `RELAY <n> OFF` | any line | One pump, 1-based |
| `ALL OFF` | any line | Every pump |
| `GET DIST` | `DIST <cm>` | Distance straight down; negative means no echo |
| `LCD BOTH;<line 1>;<line 2>` | any line | The display; each line at most 16 characters |

The acknowledgement text isn't specified, so any non-empty line counts as done. You can
drive the board by hand from the Arduino IDE's Serial Monitor (115200, newline line ending).

Opening the port resets most Arduinos, so the service waits two seconds for the bootloader,
then sends `ALL OFF` and expects an answer. If the cable is pulled mid-run, the next command
reopens the port by itself; if the Arduino is missing at startup, the service exits and
systemd retries.

## The glass sensor

The sensor looks straight down at the tray. Everything is measured relative to the **empty
tray** (`sensor.referenceCm`), which you measure once from the app (Settings → Calibrate pumps
→ Measure reference) with nothing on the tray. Until then, pours are refused with
`NOT_CALIBRATED`.

- **Glass detection:** an empty glass reads 0.5–5 cm closer than the tray. A pour starts only
  after three such readings in a row, and waits for as long as it takes (abort ends it). A glass
  lifted mid-pour does not stop the recipe — the tray catches it.
- **Measured volume:** after each pour step the level is measured (20 readings, extremes
  dropped) and the rise × the area of the 58 mm glass becomes that step's `dispensedMl`. The
  Stats tab in the app counts that. It assumes a straight glass centred under the sensor; any
  other glass gives nonsense, which the plausibility check rejects in favour of the estimate.

## Calibrating the pumps

The pump's `mlPerSecond` is the number that decides how much liquid ends up in the glass, and
it is **measured, not guessed**. Pumps of the same model differ, and the same pump differs
with a syrup versus a juice.

Calibrate from the app: **Settings → Calibrate pumps**. Or by hand:

```bash
curl -X POST localhost:8080/api/v1/pumps/1/jog -H 'Content-Type: application/json' -d '{"seconds": 2}'   # prime, into a cup
curl -X POST localhost:8080/api/v1/sensor/reference                                                      # no glass on the tray
curl -X POST localhost:8080/api/v1/calibration -H 'Content-Type: application/json' -d '{}'               # empty glass on the tray
```

The run waits for the empty glass, then runs each pump for 3 s into it, waits 2 s, and turns the
rise in level into ml/s. The results take effect at once and are saved to
`~/pump_calibration.json` — the same file (and format) as the group's standalone calibration
script, so a file it already wrote is used as-is. A calibrated pump ignores `ml_per_s` in
`config.yaml`.

**Prime the tubes first.** A dry tube spends its first seconds filling itself and reports far
too little: in the group's first run pump 1 measured 25.9 ml in 3 s (8.6 ml/s) while its
neighbours did about 19–25 ml/s.

Expect ±15 % even after calibrating. Peristaltic pumps are non-linear over short runs, which
is exactly where a 15 ml pour lives — the measured volume in the app shows how far off it is.

## Safety

Every pour and calibration run is wrapped in `try / finally: await backend.stop_all()`. An
exception, a cancellation, a client vanishing mid-pour — every pump stops.
`tests/test_pour_job.py` has a test for it. Do not remove that `finally`.

That `finally` cannot help when the service itself dies. `bartender.service` therefore runs
`python -m app.safe_off` as `ExecStopPost`, which opens the port afresh (resetting the board)
and sends `ALL OFF` after any stop, a crash included.

**The Arduino sketch has no watchdog.** If the Pi hangs outright — not a crash, a freeze — or
the USB cable comes out mid-pour, a closed relay stays closed. Ask whoever maintains the sketch
to add one: "no command for 2 seconds → every relay off". The service already talks to the
board several times a second during a pour (sensor readings, relay commands), and could send a
periodic keep-alive for it.

A dropped WebSocket does **not** abort a pour: the machine finishes the drink and the app
re-attaches when it returns. A Wi-Fi hiccup should not leave half a Margarita in the glass.

Once real pumps exist, wire a physical stop button. Two hours of work against a machine that
can empty a bottle onto a table.

## Security

There is none by default: anyone on the Wi-Fi can pour a drink. That is fine for a prototype
on a home network and not fine anywhere else. Setting `server.token` in `config.yaml` makes
every `/api/v1` request require `X-Bartender-Token`.
