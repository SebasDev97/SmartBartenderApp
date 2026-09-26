# Putting the service on the Raspberry Pi

Start to finish: from a fresh Pi to a machine the app can pour with. Follow it in order —
each step is verifiable on its own, so when something breaks you know exactly which step
broke it.

**[API.md](API.md)** is the contract the app and this service share. **[README.md](README.md)**
is the day-to-day reference. This file is just the deployment walk-through.

---

## Step 0 — What you need

- A Raspberry Pi on the same Wi-Fi as the phone, with SSH enabled.
- The Arduino with the group's relay/sensor/LCD sketch on it, and a USB cable to the Pi. The
  pump relays, the ultrasonic sensor and the LCD are wired to the Arduino, not to the Pi — the
  Pi drives no pins at all.
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

** Clone the repo on the Pi**

```bash
sudo apt update && sudo apt install -y git python3-venv
git clone https://github.com/SebasDev97/SmartBartenderApp.git ~/SmartBartender
cd ~/SmartBartender/pi
```

A clone only carries what is committed and pushed — commit your latest changes first.

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

74 tests should pass. If they do, the pour state machine and the whole API work on this Pi.

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

The status line turns to `Smart Bartender · simulated · 4 pumps`, and the Pi's log
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
cp -n config.example.yaml config.yaml   # the unit reads it; step 6 fills in the Arduino port
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
it disabled and keep starting the service by hand, or swap `--arduino` for `--simulate` in
`ExecStart` (keep `--config config.yaml`). Once it runs from `config.yaml`, the app shows the
machine as `Smart Bartender`, the `machine.name` set there.

---

## Step 6 — Hardware: the Arduino

The Pi never touches a pin. The relays, the ultrasonic sensor and the LCD are wired to the
Arduino, which already runs the group's sketch; the Pi talks to it over the USB cable. There is
nothing to flash from this repo.

Check the board before involving the service: open the Arduino IDE's **Serial Monitor** at
**115200 baud** with **Newline** line endings. `GET DIST` should answer `DIST 16.3` or so,
`RELAY 1 ON` should click pump 1's relay, and `ALL OFF` should release it. (Or run the group's
own test menu script, which uses the same commands.)

**Connect it to the Pi** and find its port:

```bash
ls /dev/serial/by-id/
```

You'll see something like `usb-Arduino__www.arduino.cc__0043_...-if00`. Use that full
`/dev/serial/by-id/...` path rather than `/dev/ttyACM0`: it names the board, so it never
moves when another USB device is plugged in.

```bash
cp -n config.example.yaml config.yaml   # already there if you did step 5
nano config.yaml          # set arduino.port
groups                    # must include dialout, or the port cannot be opened
```

If `dialout` is missing: `sudo usermod -aG dialout $USER`, then log out and back in.

**Close the Arduino IDE's Serial Monitor** if it is open anywhere — only one program can hold
the port. The same goes for the group's Python scripts: stop them before starting the service.
Then:

```bash
.venv/bin/python -m app.main --arduino --config config.yaml
```

The log should say `Arduino on /dev/serial/by-id/...: answers; every relay is off`, and the
LCD should read `Smart Bartender / Ready`.

## Step 7 — Measure the tray, then calibrate the pumps

**Do this before trusting a single drink.** In the app: **Settings → Calibrate pumps**.

1. **Measure empty tray.** Take every glass off the tray first. The machine measures the
   distance to the empty tray; every glass is detected relative to it. Until this is done, the
   machine refuses to pour (`NOT_CALIBRATED`).
2. **Prime each pump.** Hold a cup under the nozzle and tap **Test 2 s** per pump until liquid
   comes out steadily. A dry tube makes the first calibration far too low.
3. **Calibrate.** Place the **empty 58 mm glass** under the nozzle and tap **Start calibration**. Each pump
   runs 3 seconds into it; the sensor measures the rise and the app shows ml/s per pump as it
   goes. The results are saved to `~/pump_calibration.json` and used straight away — no restart.

If the group's calibration script already wrote `~/pump_calibration.json`, those rates are used
from the start; recalibrate anyway once the tubes are primed (their pump 1 value was measured
on a dry tube). The glass holds about four 3-second runs; if it gets too full the run stops
with "The glass is nearly full" and keeps the pumps it already measured — empty the glass and
calibrate the rest.

Expect ±15 % even after calibrating — peristaltic pumps are non-linear over short runs, which
is exactly where a 15 ml pour lives. The measured volumes in the app show how close it gets.

## Cleaning the pumps

In the app: **Settings → Clean pumps**. Swap each bottle for one of warm water and put a large
container under the nozzle — the screen shows roughly how much will come out. Tick that the
container is in place and tap **Start cleaning**: the pumps run one at a time, 1 → 4, for as
many rounds as you pick. The glass sensor isn't used. The defaults (seconds per pump, rounds,
the pause between pumps) live in the `cleaning:` block of `config.yaml`; the app can override
all but the pause.

---

## Everyday commands

```bash
sudo systemctl restart bartender     # after editing config.yaml
sudo systemctl stop bartender
journalctl -u bartender -n 50        # recent log
journalctl -u bartender -f           # follow live

# update after a code change 
# on the Pi:
cd ~/SmartBartender && git pull && sudo systemctl restart bartender
# either way, if requirements.txt changed: .venv/bin/pip install -r requirements.txt
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
The relay polarity in the Arduino sketch is the wrong way round for the relay board. That is
the sketch's business — check it with the Serial Monitor (step 6).

**`cannot open /dev/...` at startup.**
Wrong `arduino.port`, the cable is out, or the user is not in `dialout` (step 6).

**`no answer from the Arduino`.**
The port opened but nothing answered `ALL OFF`: the sketch isn't on the board, `arduino.baud`
in `config.yaml` isn't 115200, or the Serial Monitor (or one of the group's scripts) still has
the port.

**The pour overlay says "Place a glass" and never moves on.**
The sensor doesn't see an empty glass 0.5–5 cm above the tray reference. Re-measure the
reference with the tray empty (step 7), make sure the glass is empty and centred under the
sensor, and check the live reading on the calibration screen.

**The app says the machine is "not calibrated".**
The tray reference was never measured (step 7, part 1).

**A pour is short or long.**
Calibration (step 7). The measured ml in the app's pour overlay and Stats show what really
went in.

**Stopping service**
Stop the service. `sudo systemctl stop bartender` kills every pump and shuts service down.