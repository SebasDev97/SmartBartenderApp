"""config.yaml -> dataclasses. See config.example.yaml for the annotated version."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional, Union

import yaml

FIRMWARE = "0.1.0"

#: Hard ceiling on a single pour, whatever the request or the config says.
MAX_ITEM_ML = 150.0

#: Hard ceiling on a jog, so a slipped decimal can't empty a bottle.
MAX_JOG_SECONDS = 30.0

#: Hard ceiling on cleaning rounds, so a slipped digit can't run the pumps for an hour.
MAX_CLEANING_ROUNDS = 5


@dataclass
class ServerConfig:
    host: str = "0.0.0.0"
    port: int = 8080
    token: Optional[str] = None


@dataclass
class MachineConfig:
    id: str = "bartender-01"
    name: str = "Smart Bartender"
    max_pour_ml: float = 250.0


@dataclass
class PumpConfig:
    pump: int
    ml_per_s: float = 12.5
    bottle_id: Optional[str] = None


@dataclass
class ArduinoConfig:
    port: str = "/dev/ttyACM0"
    baud: int = 115200  # what the relay/sensor/LCD sketch on the Arduino listens at


@dataclass
class SensorConfig:
    """The ultrasonic sensor above the glass. Distances are in cm, measured downwards."""

    # A glass is "placed" when the reading is this much closer than the empty tray.
    glass_min_difference_cm: float = 0.5
    glass_max_difference_cm: float = 5.0
    glass_confirmations: int = 3  # in-band readings in a row before a glass counts
    poll_seconds: float = 0.3  # between readings while waiting for a glass

    samples: int = 20  # readings per level measurement...
    discard: int = 2  # ...minus this many from each end...
    sample_gap_seconds: float = 0.05  # ...taken this far apart
    settle_seconds: float = 1.0  # after a pump stops, before the level is measured

    glass_diameter_mm: float = 58.0  # the straight glass the volume maths assumes
    min_distance_cm: float = 5.0  # closer than this and the glass is too full to pour into


@dataclass
class CalibrationConfig:
    # The same file the group's standalone calibration script writes. None keeps calibration
    # in memory only — what tests and a bare --simulate get.
    path: Optional[str] = "~/pump_calibration.json"
    pump_seconds: float = 3.0  # how long each pump runs during a calibration
    settle_seconds: float = 2.0


@dataclass
class CleaningConfig:
    # The rinse: each pump in turn runs warm water into a container. No sensor is involved.
    pump_seconds: float = 10.0  # per pump, per round; capped at MAX_JOG_SECONDS
    rounds: int = 2  # how many times the whole row of pumps is run
    pause_seconds: float = 1.0  # between pumps, so one relay is off before the next goes on


@dataclass
class LedConfig:
    brightness: float = 0.6  # ceiling; the app's brightness is scaled under it


@dataclass
class Config:
    server: ServerConfig = field(default_factory=ServerConfig)
    machine: MachineConfig = field(default_factory=MachineConfig)
    pumps: list[PumpConfig] = field(default_factory=list)
    arduino: ArduinoConfig = field(default_factory=ArduinoConfig)
    led: LedConfig = field(default_factory=LedConfig)
    sensor: SensorConfig = field(default_factory=SensorConfig)
    calibration: CalibrationConfig = field(default_factory=CalibrationConfig)
    cleaning: CleaningConfig = field(default_factory=CleaningConfig)

    @property
    def pump_count(self) -> int:
        return len(self.pumps)

    def pump(self, number: int) -> Optional[PumpConfig]:
        return next((p for p in self.pumps if p.pump == number), None)


def default_pumps() -> list[PumpConfig]:
    """Four slots, matching BottleCatalog.MAX_SLOTS, with no bottles loaded."""
    return [PumpConfig(pump=n) for n in range(1, 5)]


def load_config(path: Optional[Union[str, Path]]) -> Config:
    """Load config.yaml, or return usable defaults when no path is given."""
    if path is None:
        return Config(pumps=default_pumps(), calibration=CalibrationConfig(path=None))

    raw = yaml.safe_load(Path(path).read_text()) or {}
    pumps = [PumpConfig(**entry) for entry in raw.get("pumps", [])] or default_pumps()
    pumps.sort(key=lambda p: p.pump)

    return Config(
        server=ServerConfig(**raw.get("server", {})),
        machine=MachineConfig(**raw.get("machine", {})),
        pumps=pumps,
        arduino=ArduinoConfig(**raw.get("arduino", {})),
        led=LedConfig(**raw.get("led", {})),
        sensor=SensorConfig(**raw.get("sensor", {})),
        calibration=CalibrationConfig(**raw.get("calibration", {})),
        cleaning=CleaningConfig(**raw.get("cleaning", {})),
    )
