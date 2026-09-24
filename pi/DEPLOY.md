# Putting the service on the Raspberry Pi

Start to finish: from a fresh Pi to a machine the app can pour with. Follow it in order —
each step is verifiable on its own, so when something breaks you know exactly which step
broke it.

**[API.md](API.md)** is the contract the app and this service share. **[README.md](README.md)**
is the day-to-day reference. This file is just the deployment walk-through.

---

## Step 0 — What you need

- A Raspberry Pi on the same Wi-Fi as the phone, with SSH enabled.
- An Arduino (Uno or Nano) and a USB cable to the Pi. The pumps' relays and the LED strip are
  wired to the Arduino, not to the Pi — the Pi drives no pins at all.
- The Arduino IDE on your computer, to flash the sketch once (step 6).
- Python 3.9 or newer (`python3 --version`). Raspberry Pi OS Bookworm ships 3.11.
- The Pi's IP address. Get it with `hostname -I` on the Pi.
- The username you picked in Raspberry Pi Imager. Raspberry Pi OS has had no default `pi`
  user since 2022, so `<user>` below means yours.

**Give the Pi a fixed address now**, before anything else — a DHCP reservation in your
router, pinned to the Pi's MAC. Otherwise its IP moves on a reboot and the app looks broken
when nothing is actually wrong.

```bash
ssh <user>@raspberrypi.local     # or <user>@<the IP>
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
  ~/AndroidStudioProjects/SmartBartender/pi/ <user>@raspberrypi.local:~/SmartBartender/pi/
```

Keep the `--exclude '.venv'`. A venv built on the Mac points at the Mac's Python and cannot
run on the Pi; step 2 builds one on the Pi itself.

---

## Step 2 — Install the dependencies

On the Pi, in the `pi/` folder:

```bash
cd ~/SmartBartender/pi
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
```

This installs FastAPI, uvicorn, pydantic, PyYAML and pyserial — everything, including what
talks to the Arduino. Nothing needs to be plugged in yet: steps 3–5 prove the service
working in simulation before any wiring exists.

Check it:

```bash
.venv/bin/python -m pytest tests/ -q
```

28 tests should pass. If they do, the pour state machine and the whole API work on this Pi.

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

`bartender.service` is a template: it has `@USER@` and `@DIR@` where your username and the
`pi/` folder go. Fill them in while installing it — from the `pi/` folder, as your normal user
(not after `sudo -i`, or it will say `root`):

```bash
cd ~/SmartBartender/pi
ls .venv/bin/python        # must exist — if not, do step 2 first
sed -e "s|@USER@|$USER|g" -e "s|@DIR@|$PWD|g" bartender.service \
  | sudo tee /etc/systemd/system/bartender.service
```

`tee` prints the installed unit; check that `User=`, `WorkingDirectory=` and `ExecStart=`
show your username and your real path. Run it again whenever you move the folder.

The imager's user is already in the `dialout` group that may open the Arduino's serial
port — check it with `groups`, see step 6.

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now bartender
systemctl status bartender
journalctl -u bartender -f       # live log
```

Note that the unit runs with `--arduino`, and the service **refuses to start without the
Arduino attached** (systemd retries every 3 seconds). Until you have done step 6, either leave
it disabled and keep starting the service by hand, or change `ExecStart` to use `--simulate`.

---

## Step 6 — Hardware: the Arduino

The Pi never touches a pin. The relays and the LED strip are wired to the Arduino, and the Pi
talks to it over the USB cable.

**Flash the sketch** (from your computer, once — and again whenever the wiring changes):

1. Open `pi/firmware/bartender/bartender.ino` in the Arduino IDE.
2. Install **Adafruit NeoPixel** from the Library Manager.
3. Edit the block at the top to match your wiring: `PUMP_PINS` (pump 1 first), `LED_PIN`,
   `LED_COUNT` (`0` if there is no strip) and `PUMP_ACTIVE_HIGH`. These are the only pin
   numbers in the whole project.
4. Upload it.

Check it before involving the Pi: open the **Serial Monitor** at **9600 baud** with **Newline**
line endings and type `HELLO`. You want `OK BARTENDER 1.0.0 4 24`. Then `ON 1` should run
pump 1, `STOP` should stop it — and if you type `ON 1` and then nothing, the pump stops by
itself after 1.5 seconds. That is the watchdog, and it is supposed to do that.

**Connect it to the Pi** and find its port:

```bash
ls /dev/serial/by-id/
```

You'll see something like `usb-Arduino__www.arduino.cc__0043_...-if00`. Use that full
`/dev/serial/by-id/...` path rather than `/dev/ttyACM0`: it names the board, so it never
moves when another USB device is plugged in.

```bash
cp config.example.yaml config.yaml
nano config.yaml          # set arduino.port
groups                    # must include dialout, or the port cannot be opened
```

If `dialout` is missing: `sudo usermod -aG dialout $USER`, then log out and back in.

**Close the Arduino IDE's Serial Monitor** if it is open anywhere — only one program can hold
the port. Then:

```bash
.venv/bin/python -m app.main --arduino --config config.yaml
```

The log should say `Arduino on /dev/serial/by-id/...: firmware 1.0.0, 4 pumps, 24 LEDs` and
the strip should start its spectrum.

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
Check with `journalctl -u bartender -n 30`.

**The log says `status=203/EXEC` or `Failed to locate executable .../.venv/bin/python`.**
systemd can't find the venv's Python. Either the unit points at the wrong user or folder
(re-run the install in step 5 from the right folder), or there is no venv there (step 2).
If `readlink -f .venv/bin/python` shows a Mac path such as `/opt/homebrew/...`, the venv was
copied from your computer: `rm -rf .venv` and redo step 2 on the Pi.

**Pumps run when idle, or don't run at all.**
`PUMP_ACTIVE_HIGH` in the sketch is the wrong way round for your relay board. Flip it and
re-flash (step 6).

**`cannot open /dev/...` at startup.**
Wrong `arduino.port`, the cable is out, or the user is not in `dialout` (step 6).

**`no answer from the bartender sketch`.**
The port opened but nothing answered `HELLO`: the sketch isn't flashed, the baud rate in
`config.yaml` doesn't match `BAUD` in the sketch, or the Serial Monitor still has the port.

**The log says `watchdog: no command from the Pi`.**
The Arduino stopped the pumps because the service went quiet for 1.5 seconds mid-pour —
the service crashed, or the USB link dropped. Check `journalctl -u bartender` around that
time.

**A pour is short or long.**
Calibration (step 7). The service is doing exactly what `ml_per_s` told it to.

**Stopping service**
Stop the service. `sudo systemctl stop bartender` kills every pump and shuts service down.