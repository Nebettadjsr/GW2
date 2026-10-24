import unittest
from datetime import datetime

from claude_gate import BERLIN, parse_usage, usage_allowed, weekly_cap, window_open


RESET = datetime(2026, 10, 31, 6, 0, tzinfo=BERLIN)


def usage(session: int, week: int) -> str:
    return (
        "You are currently using your subscription to power your Claude Code usage\n"
        f"Current session: {session}% used · resets Oct 24, 6:50pm (Europe/Berlin)\n"
        f"Current week (all models): {week}% used · resets Oct 31, 6am (Europe/Berlin)\n"
    )


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

    def test_usage_requires_unlocked_weekly_share_and_session_headroom(self):
        before = datetime(2026, 10, 24, 17, 29, tzinfo=BERLIN)
        after = datetime(2026, 10, 24, 17, 35, tzinfo=BERLIN)
        self.assertEqual(weekly_cap(before, RESET), 0)
        self.assertAlmostEqual(weekly_cap(after, RESET), 100 / 7)
        self.assertFalse(usage_allowed(usage(5, 0), before)[0])
        self.assertTrue(usage_allowed(usage(5, 14), after)[0])
        self.assertFalse(usage_allowed(usage(90, 0), after)[0])
        self.assertFalse(usage_allowed(usage(5, 15), after)[0])

    def test_missing_or_unexpected_usage_fails_closed(self):
        now = datetime(2026, 10, 24, 17, 35, tzinfo=BERLIN)
        self.assertFalse(usage_allowed("Current session: 5% used", now)[0])
        self.assertEqual(parse_usage(usage(5, 1), now)[2], RESET)


if __name__ == "__main__":
    unittest.main()
