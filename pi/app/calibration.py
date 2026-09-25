"""Pump calibration and the sensor reference, kept in one JSON file.

The file has the same shape the group's standalone calibration script writes to
~/pump_calibration.json, so an existing file is picked up as-is:

    {
      "glassDiameterMm": 58.0,
      "pumpTimeSeconds": 3.0,
      "pumps": { "1": { "mlPerSecond": 18.6, "volumeMl": 55.9, "pumpTimeSeconds": 3.0,
                        "startDistanceCm": 15.6, "endDistanceCm": 13.5 }, ... },
      "referenceDistanceCm": 16.3,     <- added by this service: the empty tray
      "calibratedAtMs": 1758531234567  <- added by this service
    }

A pump's `mlPerSecond` here wins over `ml_per_s` in config.yaml. Keys this service does not
know about are kept when it saves.
"""

from __future__ import annotations

import json
import logging
import os
from pathlib import Path
from typing import Any, Optional

from .models import CalibrationResult

log = logging.getLogger("bartender.calibration")


class CalibrationStore:
    def __init__(self, path: Optional[str | Path] = None) -> None:
        """`path` None keeps everything in memory — nothing is read or written."""
        self.path = Path(path).expanduser() if path else None
        self._data: dict[str, Any] = {"pumps": {}}
        if self.path is not None and self.path.exists():
            self._load()

    # ------------------------------------------------------------------ reading

    def ml_per_second(self, pump: int) -> Optional[float]:
        entry = self._pumps().get(str(pump))
        try:
            rate = float(entry["mlPerSecond"]) if entry else None
        except (KeyError, TypeError, ValueError):
            return None
        return rate if rate and rate > 0 else None

    @property
    def reference_cm(self) -> Optional[float]:
        value = self._data.get("referenceDistanceCm")
        return float(value) if isinstance(value, (int, float)) and value > 0 else None

    @property
    def calibrated_at_ms(self) -> Optional[int]:
        value = self._data.get("calibratedAtMs")
        return int(value) if isinstance(value, (int, float)) else None

    # ------------------------------------------------------------------ writing

    def set_reference(self, reference_cm: float) -> None:
        self._data["referenceDistanceCm"] = round(reference_cm, 2)
        self.save()

    def record(
        self,
        results: list[CalibrationResult],
        *,
        glass_diameter_mm: float,
        pump_seconds: float,
        at_ms: int,
    ) -> None:
        """Store one run's results. Pumps not in `results` keep their previous calibration."""
        pumps = self._pumps()
        for result in results:
            pumps[str(result.pump)] = {
                "mlPerSecond": round(result.ml_per_second, 3),
                "volumeMl": round(result.volume_ml, 2),
                "pumpTimeSeconds": round(result.seconds, 3),
                "startDistanceCm": round(result.start_distance_cm, 2),
                "endDistanceCm": round(result.end_distance_cm, 2),
            }
        self._data["pumps"] = pumps
        self._data["glassDiameterMm"] = glass_diameter_mm
        self._data["pumpTimeSeconds"] = pump_seconds
        self._data["calibratedAtMs"] = at_ms
        self.save()

    def save(self) -> None:
        if self.path is None:
            return
        # Write-then-rename, so a power cut mid-save leaves the old file rather than half a new one.
        tmp = self.path.with_name(self.path.name + ".tmp")
        tmp.write_text(json.dumps(self._data, indent=4))
        os.replace(tmp, self.path)
        log.info("calibration saved to %s", self.path)

    # ------------------------------------------------------------------ internals

    def _pumps(self) -> dict[str, Any]:
        pumps = self._data.get("pumps")
        return pumps if isinstance(pumps, dict) else {}

    def _load(self) -> None:
        try:
            data = json.loads(self.path.read_text())
        except (OSError, ValueError) as exc:
            # A broken file must not keep the machine from starting; config.yaml rates still work.
            log.error("ignoring unreadable calibration file %s: %s", self.path, exc)
            return
        if isinstance(data, dict):
            data.setdefault("pumps", {})
            self._data = data
            log.info("calibration loaded from %s", self.path)
