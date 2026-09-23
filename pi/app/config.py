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


@dataclass
class ServerConfig:
    host: str = "0.0.0.0"
    port: int = 8080
    token: Optional[str] = None


@dataclass
class MachineConfig:
    id: str = "bartender-01"
    name: str = "Smart Bartender De-Luxe"
    max_pour_ml: float = 250.0


@dataclass
class PumpConfig:
    pump: int
    gpio: int
    ml_per_s: float = 12.5
    bottle_id: Optional[str] = None


@dataclass
class LedConfig:
    type: str = "ws2812"
    gpio: int = 18
    count: int = 24
    brightness: float = 0.6


@dataclass
class Config:
    server: ServerConfig = field(default_factory=ServerConfig)
    machine: MachineConfig = field(default_factory=MachineConfig)
    pumps: list[PumpConfig] = field(default_factory=list)
    pump_active_high: bool = False
    led: LedConfig = field(default_factory=LedConfig)

    @property
    def pump_count(self) -> int:
        return len(self.pumps)

    def pump(self, number: int) -> Optional[PumpConfig]:
        return next((p for p in self.pumps if p.pump == number), None)


def default_pumps() -> list[PumpConfig]:
    """Four slots, matching BottleCatalog.MAX_SLOTS, with no bottles loaded."""
    return [PumpConfig(pump=n, gpio=pin) for n, pin in enumerate((17, 27, 22, 23), start=1)]


def load_config(path: Optional[Union[str, Path]]) -> Config:
    """Load config.yaml, or return usable defaults when no path is given."""
    if path is None:
        return Config(pumps=default_pumps())

    raw = yaml.safe_load(Path(path).read_text()) or {}
    pumps = [PumpConfig(**entry) for entry in raw.get("pumps", [])] or default_pumps()
    pumps.sort(key=lambda p: p.pump)

    return Config(
        server=ServerConfig(**raw.get("server", {})),
        machine=MachineConfig(**raw.get("machine", {})),
        pumps=pumps,
        pump_active_high=raw.get("pump_active_high", False),
        led=LedConfig(**raw.get("led", {})),
    )
