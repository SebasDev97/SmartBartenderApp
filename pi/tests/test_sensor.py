"""The sensor maths, checked against numbers from the group's real calibration run."""

import asyncio

import pytest

from app.config import SensorConfig
from app.sensor import glass_area_cm2, in_glass_band, measure, trimmed_mean, volume_ml


def test_the_extremes_are_dropped_before_averaging():
    readings = [15.0, 15.1, 15.2, 15.3, 15.4, 99.0, 0.1]
    # sorted: 0.1 15.0 15.1 15.2 15.3 15.4 99.0 -> drop two each end -> 15.1 15.2 15.3
    assert trimmed_mean(readings, discard=2) == pytest.approx(15.2)


def test_fewer_than_five_valid_readings_is_no_measurement():
    assert trimmed_mean([15.0, 15.1, 15.2, 15.3], discard=0) is None


def test_measure_skips_readings_without_an_echo():
    answers = iter([None, 15.0, None, 15.2, 15.4, 15.6, 15.8] + [None] * 13)

    async def read():
        return next(answers)

    async def no_sleep(_):
        pass

    config = SensorConfig(samples=20, discard=1)
    assert asyncio.run(measure(read, config, sleep=no_sleep)) == pytest.approx(15.4)


def test_a_glass_is_a_little_closer_than_the_tray():
    config = SensorConfig()  # 0.5 .. 5.0 cm above the tray
    reference = 16.3
    assert in_glass_band(15.6, reference, config), "an empty glass bottom"
    assert not in_glass_band(16.2, reference, config), "the tray itself, give or take noise"
    assert not in_glass_band(9.0, reference, config), "a glass that is already full, or a hand"
    assert not in_glass_band(None, reference, config), "no echo"
    assert not in_glass_band(15.6, None, config), "no reference measured yet"


def test_volume_matches_the_groups_pump_2_calibration():
    # Pump 2: 14.61 cm -> 12.47 cm in a 58 mm glass = 56.47 ml.
    assert glass_area_cm2(58.0) == pytest.approx(26.42, abs=0.01)
    assert volume_ml(14.61, 12.47, 58.0) == pytest.approx(56.47, abs=0.1)
