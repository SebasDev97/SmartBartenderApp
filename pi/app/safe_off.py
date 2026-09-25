"""Switch every relay off, from outside the service.

    python -m app.safe_off --config config.yaml

bartender.service runs this as ExecStopPost, so it also runs after the service has crashed
or been killed. The relay/sensor/LCD sketch has no watchdog of its own, so this is what stops
a pump that a dead service left running. Opening the port resets most boards (which already
releases the relays); ALL OFF is sent anyway, for boards that don't reset.
"""

from __future__ import annotations

import argparse
import sys
import time

import serial

from .config import load_config
from .hardware.arduino import BOOT_SECONDS, REPLY_SECONDS


def main() -> int:
    parser = argparse.ArgumentParser(description="Send ALL OFF to the Arduino")
    parser.add_argument("--config", help="path to config.yaml")
    args = parser.parse_args()
    cfg = load_config(args.config).arduino

    try:
        with serial.Serial(cfg.port, cfg.baud, timeout=REPLY_SECONDS, write_timeout=REPLY_SECONDS) as port:
            time.sleep(BOOT_SECONDS)
            port.reset_input_buffer()
            port.write(b"ALL OFF\n")
            reply = port.readline().decode("ascii", errors="replace").strip()
    except (serial.SerialException, OSError) as exc:
        print(f"safe_off: cannot reach the Arduino on {cfg.port}: {exc}", file=sys.stderr)
        return 1
    print(f"safe_off: ALL OFF sent to {cfg.port} ({reply or 'no reply'})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
