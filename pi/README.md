# Smart Bartender — machine service

The Raspberry Pi half of the project. It owns the pumps and the LED strip; the Android app
owns the recipes. The Pi drives no pins itself: the pumps and the strip hang off an **Arduino
on USB**, running the sketch in `firmware/bartender/`, and the Pi tells it what to do over
serial. They meet at the HTTP + WebSocket contract in **[API.md](API.md)** — read
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

# On the real machine, with the Arduino plugged in and flashed.
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
cable, reconnecting, and a whole pour.

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
| `app/hardware/backend.py` | Eight methods. The entire hardware surface |
| `app/hardware/simulated.py` | Logs instead of pouring. The default |
| `app/hardware/arduino.py` | The serial link to the Arduino: retries, heartbeat, reconnect |
| `firmware/bartender/bartender.ino` | The Arduino sketch. Pins, relay polarity, LED animations, and the serial protocol |

## Wiring

```
phone ──Wi-Fi──▶ Raspberry Pi ──USB serial──▶ Arduino ──▶ relays ──▶ pumps
                                                      └──▶ WS2812 strip
```

**Pin numbers live only in the sketch** — `PUMP_PINS`, `LED_PIN` and `LED_COUNT` at the top of
`bartender.ino`. The Pi addresses pumps by number (1–4) and never learns a pin. Change the
wiring, change the sketch, flash it; the service does not move.

Most relay boards are **active-low** — the pin goes LOW to close the relay. If your pumps run
when they should be idle, flip `PUMP_ACTIVE_HIGH` in the sketch.

The sketch must drive at least as many pumps as `config.yaml` lists; the service checks this
at connect time and refuses to start otherwise.

Pumps must have their own supply; neither board can drive a motor from its 5 V rail, and
back-EMF from a DC motor will reset the Arduino or worse. Tie the grounds together.

### The serial link

Plain text at 9600 baud, one command per line, one `OK`/`ERR` reply each — the full protocol
is the comment at the top of `bartender.ino`. You can drive it by hand from the Arduino IDE's
Serial Monitor (newline line ending) with `HELLO`, `ON 1`, `STOP`, `LED SPECTRUM 7000 150`.

Opening the port resets most Arduinos, so the service waits two seconds for the bootloader
before its first `HELLO`. If the cable is pulled mid-run, the next command reopens the port
by itself; if the Arduino is missing at startup, the service exits and systemd retries.

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

That `finally` cannot help when the Pi itself dies, or the USB cable comes out mid-pour, so the
sketch has a **watchdog**: the service pings the Arduino four times a second, and if 1.5
seconds pass with no command, the Arduino stops every pump on its own.

A dropped WebSocket does **not** abort a pour: the machine finishes the drink and the app
re-attaches when it returns. A Wi-Fi hiccup should not leave half a Margarita in the glass.

Once real pumps exist, wire a physical stop button. Two hours of work against a machine that
can empty a bottle onto a table.

## Security

There is none by default: anyone on the Wi-Fi can pour a drink. That is fine for a prototype
on a home network and not fine anywhere else. Setting `server.token` in `config.yaml` makes
every `/api/v1` request require `X-Bartender-Token`.
