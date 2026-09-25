"""Entry point.

    python -m app.main --simulate --speed 4        # laptop, no hardware
    python -m app.main --arduino --config config.yaml  # the real machine
"""

from __future__ import annotations

import argparse
import contextlib
import logging

import uvicorn

from .api import create_app
from .config import load_config
from .events import EventBus
from .hardware import SimulatedBackend
from .machine import Machine


def build(args: argparse.Namespace):
    config = load_config(args.config)
    if args.host:
        config.server.host = args.host
    if args.port:
        config.server.port = args.port

    if args.arduino:
        from .hardware.arduino import ArduinoBackend

        backend = ArduinoBackend(config)
    else:
        backend = SimulatedBackend(
            pump_count=config.pump_count,
            # The "true" flow of each simulated pump. A calibration run should find these again.
            flows={p.pump: p.ml_per_s for p in config.pumps},
            speed=args.speed,
            glass_diameter_mm=config.sensor.glass_diameter_mm,
            # Someone takes the drink away once the pumps have been quiet for a while, so a
            # long demo never ends with a glass too full to pour into.
            auto_serve_seconds=5.0,
        )

    bus = EventBus()
    machine = Machine(config, backend, bus, speed=args.speed)
    app = create_app(config, machine, bus)

    @contextlib.asynccontextmanager
    async def lifespan(_):
        await machine.start()
        try:
            yield
        finally:
            await machine.stop()

    app.router.lifespan_context = lifespan
    return config, app


def main() -> None:
    parser = argparse.ArgumentParser(description="Smart Bartender machine service")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--simulate", action="store_true", help="drive nothing, just log (default)")
    mode.add_argument(
        "--arduino", action="store_true", help="drive the real pumps, sensor and LCD through the Arduino"
    )
    parser.add_argument("--config", help="path to config.yaml")
    parser.add_argument("--host", help="override server.host")
    parser.add_argument("--port", type=int, help="override server.port")
    parser.add_argument(
        "--speed",
        type=float,
        default=1.0,
        help="divide every delay by this, so a full pour takes seconds during development",
    )
    parser.add_argument("--log", default="INFO")
    args = parser.parse_args()

    logging.basicConfig(
        level=args.log.upper(),
        format="%(asctime)s %(levelname)-7s %(name)-20s %(message)s",
        datefmt="%H:%M:%S",
    )

    config, app = build(args)
    logging.getLogger("bartender").info(
        "%s on http://%s:%d  (%s, speed x%g)",
        config.machine.name,
        config.server.host,
        config.server.port,
        "arduino" if args.arduino else "simulated",
        args.speed,
    )
    uvicorn.run(app, host=config.server.host, port=config.server.port, log_level="warning")


if __name__ == "__main__":
    main()
