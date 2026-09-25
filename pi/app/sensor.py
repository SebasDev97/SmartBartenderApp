"""The ultrasonic sensor above the glass: from raw echoes to "is there a glass" and "how many ml".

A port of the group's standalone glass-detection and calibration scripts. The sensor looks
straight down, so every reading is a distance in cm to whatever is below it: the empty tray,
the bottom of an empty glass, or the surface of the liquid in it. A falling distance is a
rising level.

Everything here is plain arithmetic except measure(), which only needs a backend that can
read one distance.
"""

from __future__ import annotations

import asyncio
import math
from typing import Awaitable, Callable, Optional, Sequence

from .config import SensorConfig

#: Fewer valid readings than this and a measurement is not trusted at all.
MIN_VALID_READINGS = 5


def trimmed_mean(readings: Sequence[float], discard: int) -> Optional[float]:
    """Mean after dropping the `discard` lowest and highest readings; None if too few are left."""
    if len(readings) < MIN_VALID_READINGS:
        return None
    ordered = sorted(readings)
    kept = ordered[discard : len(ordered) - discard] if discard else ordered
    if not kept:
        return None
    return sum(kept) / len(kept)


async def measure(
    read: Callable[[], Awaitable[Optional[float]]],
    config: SensorConfig,
    sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
) -> Optional[float]:
    """One trustworthy level: `config.samples` readings, trimmed at both ends and averaged.

    A single echo off a moving surface is noisy; twenty with the extremes dropped is what the
    calibration was measured with, so pours are measured the same way.
    """
    readings: list[float] = []
    for _ in range(config.samples):
        distance = await read()
        if distance is not None:
            readings.append(distance)
        await sleep(config.sample_gap_seconds)
    return trimmed_mean(readings, config.discard)


def in_glass_band(distance: Optional[float], reference: Optional[float], config: SensorConfig) -> bool:
    """True when the reading is a glass standing on the tray: a little closer than the tray itself.

    Closer than the band is a glass that already holds liquid (or a hand); further is the tray.
    """
    if distance is None or reference is None:
        return False
    rise = reference - distance
    return config.glass_min_difference_cm <= rise <= config.glass_max_difference_cm


def glass_area_cm2(diameter_mm: float) -> float:
    radius_cm = diameter_mm / 10.0 / 2.0
    return math.pi * radius_cm * radius_cm


def volume_ml(start_cm: float, end_cm: float, diameter_mm: float) -> float:
    """Liquid added between two level readings, assuming a straight-sided glass."""
    return glass_area_cm2(diameter_mm) * (start_cm - end_cm)
