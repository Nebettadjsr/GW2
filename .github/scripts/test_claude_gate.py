import unittest
from datetime import datetime
from zoneinfo import ZoneInfo

from claude_gate import BERLIN, window_open


class GateTest(unittest.TestCase):
    def test_window_wraps_midnight_and_includes_endpoints(self):
        for hour, minute, expected in (
            (17, 34, False), (17, 35, True), (23, 0, True),
            (6, 35, True), (6, 36, False),
        ):
            with self.subTest(hour=hour, minute=minute):
                self.assertEqual(
                    window_open(datetime(2026, 10, 24, hour, minute, tzinfo=BERLIN)), expected
                )

    def test_utc_clock_uses_berlin_daylight_saving(self):
        utc = ZoneInfo("UTC")
        self.assertTrue(window_open(datetime(2026, 7, 1, 15, 35, tzinfo=utc)))
        self.assertFalse(window_open(datetime(2026, 1, 1, 15, 35, tzinfo=utc)))


if __name__ == "__main__":
    unittest.main()
