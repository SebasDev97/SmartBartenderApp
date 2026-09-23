# Smart Bartender — machine service

The Raspberry Pi half of the project. It owns the pumps and the LED strip; the Android app
owns the recipes. They meet at the HTTP + WebSocket contract in **[API.md](API.md)** — read
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

# On the real machine.
.venv/bin/pip install -r requirements-gpio.txt
cp config.example.yaml config.yaml    # then edit the pins
.venv/bin/python -m app.main --gpio --config config.yaml
```

Python 3.9 or newer. `gpiozero` and `rpi_ws281x` are imported lazily inside `GpioBackend`, so
`--simulate` never needs them.

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
state machine directly, including that a crash mid-pour still stops every pump.

## Layout

| File | What it does |
| --- | --- |
| `API.md` | **The contract.** If code and this file disagree, the file wins |
| `DEPLOY.md` | Step-by-step: getting this running on a real Pi |
| `app/models.py` | The contract in pydantic — snake_case here, camelCase on the wire |
| `app/machine.py` | Slot table, job registry, and the pour state machine |
| `app/api.py` | The routes and the `/events` WebSocket |
| `app/leds.py` | The LED show, ported from the app's `Led.kt` so phone and strip match |
| `app/events.py` | Fan-out to every connected client |
| `app/hardware/backend.py` | Six methods. The entire hardware surface |
| `app/hardware/simulated.py` | Logs instead of pouring. The default |
| `app/hardware/gpio.py` | Real pins. The only file that changes when hardware changes |

## Wiring

`config.yaml` holds the GPIO pin per pump. Nothing else in the service knows a pin number.

Most relay boards are **active-low** — the pin goes LOW to close the relay. If your pumps run
when they should be idle, flip `pump_active_high`.

A WS2812 strip needs PWM0, so **GPIO 18 or 12**. Pumps must have their own supply; a Pi cannot
drive a motor from its 5 V rail, and back-EMF from a DC motor will reset the board or worse.

## Calibrating the pumps

`ml_per_s` in `config.yaml` is the only number that decides how much liquid ends up in the
glass, and it is **measured, not guessed**. Pumps of the same model differ, and the same pump
differs with a syrup versus a juice.

For each pump:

```bash
curl -X POST http://localhost:8080/api/v1/pumps/1/jog \
     -H 'Content-Type: application/json' -d '{"seconds": 10}'
```

Catch the output in a measuring cup, then set `ml_per_s = measured_ml / 10`. Restart the
service. Repeat per pump, with the bottle at the height it will actually sit — head height
changes the rate.

Expect ±15 % even after calibrating. Peristaltic pumps are non-linear over short runs, which
is exactly where a 15 ml pour lives.

## Safety

Every pour is wrapped in `try / finally: await backend.stop_all()`. An exception, a
cancellation, a client vanishing mid-pour — every pump stops. `tests/test_pour_job.py` has a
test for it. Do not remove that `finally`.

A dropped WebSocket does **not** abort a pour: the machine finishes the drink and the app
re-attaches when it returns. A Wi-Fi hiccup should not leave half a Margarita in the glass.

Once real pumps exist, wire a physical stop button. Two hours of work against a machine that
can empty a bottle onto a table.

## Security

There is none by default: anyone on the Wi-Fi can pour a drink. That is fine for a prototype
on a home network and not fine anywhere else. Setting `server.token` in `config.yaml` makes
every `/api/v1` request require `X-Bartender-Token`.
