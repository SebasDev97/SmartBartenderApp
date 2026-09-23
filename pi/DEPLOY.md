# Putting the service on the Raspberry Pi

Start to finish: from a fresh Pi to a machine the app can pour with. Follow it in order —
each step is verifiable on its own, so when something breaks you know exactly which step
broke it.

**[API.md](API.md)** is the contract the app and this service share. **[README.md](README.md)**
is the day-to-day reference. This file is just the deployment walk-through.

---

## Step 0 — What you need

- A Raspberry Pi on the same Wi-Fi as the phone, with SSH enabled.
- Python 3.9 or newer (`python3 --version`). Raspberry Pi OS Bookworm ships 3.11.
- The Pi's IP address. Get it with `hostname -I` on the Pi.

**Give the Pi a fixed address now**, before anything else — a DHCP reservation in your
router, pinned to the Pi's MAC. Otherwise its IP moves on a reboot and the app looks broken
when nothing is actually wrong.

```bash
ssh pi@raspberrypi.local     # or pi@<the IP>
python3 --version
hostname -I
```

---

## Step 1 — Copy the service across

Two options. Either is fine.

**Option A — clone the repo on the Pi** (easiest to update later):

```bash
sudo apt update && sudo apt install -y git python3-venv
git clone <your-repo-url> ~/SmartBartender
cd ~/SmartBartender/pi
```

**Option B — copy the folder from your computer:**

```bash
# run this on your Mac, not on the Pi
rsync -av --exclude '.venv' --exclude '__pycache__' --exclude '.pytest_cache' \
  ~/AndroidStudioProjects/SmartBartender/pi/ pi@raspberrypi.local:~/SmartBartender/pi/
```

---

## Step 2 — Install the dependencies

On the Pi, in the `pi/` folder:

```bash
cd ~/SmartBartender/pi
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

This installs only FastAPI, uvicorn, pydantic and PyYAML. Nothing GPIO-related yet — that
comes in step 6, so the service can be proven working before any wiring exists.

Check it:

```bash
.venv/bin/python -m pytest tests/ -q
```

18 tests should pass. If they do, the pour state machine and the whole API work on this Pi.

---

## Step 3 — Run it in simulation

```bash
.venv/bin/python -m app.main --simulate --host 0.0.0.0 --port 8080
```

`--host 0.0.0.0` matters: bound to localhost the Pi answers only itself, and the phone gets
a connection refused that looks identical to the service being down.

From another terminal on the Pi:

```bash
curl -s localhost:8080/healthz
```

You want `{"ok":true,"machineId":"bartender-01","firmware":"0.1.0"}`.

Then from your computer, using the Pi's real address, to prove the network path works:

```bash
curl -s http://<pi-ip>:8080/api/v1/status
```

Nothing is wired up yet. The service will happily "pour" — it logs which pump would run and
for how long, and moves no liquid at all.

---

## Step 4 — Connecting the app

In the app: **Settings → Machine link**.

1. Type the Pi's address (e.g. `192.168.1.42`) and port `8080`.
2. Tap **Test**. It should report the machine's name, backend and firmware.
3. Turn on **Use hardware machine**, then tap **Connect**.

The status line turns to `Smart Bartender De-Luxe · simulated · 4 pumps`, and the Pi's log
shows `client connected (1 total)`.

Now open any cocktail and tap **Make this cocktail**. The overlay animates off the Pi's
pushed progress, and the Pi logs each pump on and off with its duration. Try aborting
mid-pour; try killing the app mid-pour and reopening it, which should drop you straight back
into the live overlay.

If the button is greyed out, the app is not connected — check step 3 first.

---

## Step 5 — Keep it running on boot

```bash
sudo cp bartender.service /etc/systemd/system/
sudo nano /etc/systemd/system/bartender.service
```

**Edit `WorkingDirectory` and `ExecStart`** to the path you actually used. The file ships
pointing at `/home/pi/SmartBartender/pi`; if you put it elsewhere, it will not start.

While there, if you have no LED strip, change `User=root` to `User=pi` — root is only needed
for the WS2812 driver.

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now bartender
systemctl status bartender
journalctl -u bartender -f       # live log
```

Note that the unit runs with `--gpio`. Until you have done step 6, either leave it disabled
and keep starting the service by hand, or change `ExecStart` to use `--simulate`.

---

## Step 6 — Hardware

```bash
.venv/bin/pip install -r requirements-gpio.txt
cp config.example.yaml config.yaml
nano config.yaml
```

Set the **GPIO pin for each pump**. Nothing else in the service knows a pin number, so this
file is the only thing that changes when the wiring changes.

Then:

```bash
.venv/bin/python -m app.main --gpio --config config.yaml
```

## Step 7 — Calibrate the pumps

**Do this before trusting a single drink.** `ml_per_s` in `config.yaml` is the only number
that decides how much liquid ends up in the glass, and the value shipped in the example is a
placeholder, not a measurement. Pumps of the same model differ from each other, and the same
pump differs with a syrup versus a juice.

For each pump, with the bottle at the height it will actually sit and a measuring cup under
the nozzle:

```bash
curl -X POST http://localhost:8080/api/v1/pumps/1/jog \
     -H 'Content-Type: application/json' -d '{"seconds": 10}'
```

Measure what came out, then:

```
ml_per_s = measured_ml / 10
```

Write it into `config.yaml` for that pump. Repeat for pumps 2, 3 and 4, then restart the
service.

Expect ±15 % even after calibrating — peristaltic pumps are non-linear over short runs, which
is exactly where a 15 ml pour lives.

---

## Everyday commands

```bash
sudo systemctl restart bartender     # after editing config.yaml
sudo systemctl stop bartender
journalctl -u bartender -n 50        # recent log
journalctl -u bartender -f           # follow live

# update after a code change
cd ~/SmartBartender && git pull && sudo systemctl restart bartender
```

---

## When something is wrong

**The app says "Machine unreachable".**
Check the service is up and listening on all interfaces:
`sudo ss -tlnp | grep 8080` — you want `0.0.0.0:8080`, not `127.0.0.1:8080`.
Then check the phone is on the same Wi-Fi, and that the address in Settings is the Pi's
current IP (`hostname -I`).

**It worked yesterday and now it doesn't.**
The Pi's IP almost certainly moved. Give it a DHCP reservation (step 0).

**`systemctl status bartender` shows a failure.**
Nine times out of ten the paths in the unit file were not edited (step 5). Check with
`journalctl -u bartender -n 30`.

**Pumps run when idle, or don't run at all.**
`pump_active_high` is the wrong way round for your relay board (step 6).

**A pour is short or long.**
Calibration (step 7). The service is doing exactly what `ml_per_s` told it to.

**Stopping service**
Stop the service. `sudo systemctl stop bartender` kills every pump and shuts service down.