"""Weekly Claude budget rules; /usage output is supplied, Claude is never called."""

import unittest
from datetime import datetime

from workflow_service import claude_budget

USAGE = ("Current session: {session}% used · resets Oct 9, 11:40pm (Europe/Berlin)\n"
         "Current week (all models): {weekly}% used · resets Oct 10, 7am (Europe/Berlin)\n")


def status(now, weekly=0, session=0, **settings):
    # The weekly/session rules are tested independently of the daily start window.
    settings.setdefault("window_start", "00:00")
    settings.setdefault("window_end", "23:59")
    return claude_budget.status(settings, now=now, usage_text=USAGE.format(weekly=weekly, session=session))


class WeeklyShareTests(unittest.TestCase):
    """Week Oct 3 (Sat 07:00) .. Oct 10 (Sat 07:00); 10 windows = 100 %, 7 daily shares."""

    def test_wednesday_after_unlock_allows_five_of_seven_shares(self):
        result = status(datetime(2026, 10, 7, 17, 31))
        self.assertEqual(result["shares_unlocked"], 5)
        self.assertAlmostEqual(result["weekly_cap_percent"], 71.4)
        self.assertEqual(result["next_unlock"], "2026-10-08T17:30")

    def test_before_the_daily_unlock_only_earlier_days_count(self):
        self.assertEqual(status(datetime(2026, 10, 7, 17, 29))["shares_unlocked"], 4)

    def test_saturday_after_reset_has_nothing_until_evening(self):
        result = status(datetime(2026, 10, 3, 9, 0), weekly=0)
        self.assertEqual((result["shares_unlocked"], result["claude_allowed"]), (0, False))
        self.assertIn("Next share unlocks Sat 17:30", result["reason"])

    def test_friday_evening_unlocks_the_whole_week(self):
        result = status(datetime(2026, 10, 9, 21, 0), weekly=99)
        self.assertEqual((result["shares_unlocked"], result["weekly_cap_percent"], result["claude_allowed"]),
                         (7, 100.0, True))

    def test_usage_beyond_the_unlocked_shares_blocks_claude(self):
        result = status(datetime(2026, 10, 7, 18, 0), weekly=72)
        self.assertFalse(result["claude_allowed"])
        self.assertIn("72% used of 71% allowed", result["reason"])

    def test_reset_time_comes_from_usage_output(self):
        self.assertEqual(status(datetime(2026, 10, 7, 18, 0))["weekly_reset"], "2026-10-10T07:00")

    def test_a_reset_already_passed_rolls_forward_instead_of_failing(self):
        result = status(datetime(2026, 10, 17, 19, 0))
        self.assertEqual((result["weekly_reset"], result["shares_unlocked"], result["next_unlock"]),
                         ("2026-10-24T07:00", 1, "2026-10-18T17:30"))

    def test_unlock_boundary_is_inclusive_and_unspent_shares_carry_forward(self):
        self.assertEqual(status(datetime(2026, 10, 7, 17, 30))["shares_unlocked"], 5)
        self.assertEqual(status(datetime(2026, 10, 8, 9, 0))["shares_unlocked"], 5)

    def test_reset_starts_a_new_week_without_carrying_the_previous_weeks_shares(self):
        result = status(datetime(2026, 10, 10, 7, 0))
        self.assertEqual((result["weekly_reset"], result["shares_unlocked"], result["claude_allowed"]),
                         ("2026-10-17T07:00", 0, False))

    def test_configured_conversion_between_windows_and_weekly_percent_is_preserved(self):
        result = status(datetime(2026, 10, 9, 21, 0), allowance_windows=7, windows_per_full_week=14)
        self.assertEqual(result["weekly_cap_percent"], 50)
        capped = status(datetime(2026, 10, 9, 21, 0), allowance_windows=20)
        self.assertEqual(capped["weekly_cap_percent"], 100)

    def test_usage_without_a_printed_reset_uses_the_existing_saturday_0700_fallback(self):
        result = claude_budget.status({}, now=datetime(2026, 10, 7, 18),
                                      usage_text="Current session: 10% used\nCurrent week: 20% used\n")
        self.assertEqual(result["weekly_reset"], "2026-10-10T07:00")

    def test_smaller_allowance_scales_each_share(self):
        self.assertAlmostEqual(status(datetime(2026, 10, 9, 21, 0), allowance_windows=7)["weekly_cap_percent"], 70.0)


class OverrideAndSessionTests(unittest.TestCase):
    def test_equal_to_either_limit_is_not_enough_capacity_to_start(self):
        for session, weekly, expected in ((89, 99, True), (90, 99, False), (89, 100, False)):
            with self.subTest(session=session, weekly=weekly):
                self.assertEqual(status(datetime(2026, 10, 9, 21), session=session, weekly=weekly)["claude_allowed"], expected)

    def test_manual_zero_blocks_and_manual_100_does_not_bypass_the_session_limit(self):
        self.assertFalse(status(datetime(2026, 10, 7, 18), mode="manual", manual_cap_percent=0)["claude_allowed"])
        self.assertFalse(status(datetime(2026, 10, 7, 18), mode="manual", manual_cap_percent=100,
                                session=90)["claude_allowed"])

    def test_most_used_weekly_allowance_gates_the_model(self):
        usage = (USAGE.format(weekly=10, session=10)
                 + "Current week (Opus): 80% used · resets Oct 10, 7am (Europe/Berlin)\n")
        result = claude_budget.status({}, now=datetime(2026, 10, 7, 18), usage_text=usage)
        self.assertEqual((result["weekly_used_percent"], result["claude_allowed"]), (80, False))

    def test_unreadable_usage_is_an_error_not_permission(self):
        for usage in ("", "Current session: 10% used\n", "Current week: 10% used\n"):
            with self.subTest(usage=usage), self.assertRaises(RuntimeError):
                claude_budget.status({}, usage_text=usage)

    def test_nonfinite_and_nonnumeric_settings_are_rejected(self):
        for key in ("allowance_windows", "windows_per_full_week", "manual_cap_percent"):
            for value in (float("nan"), float("inf"), [], "not-a-number"):
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    claude_budget.settings_from({key: value})
        with self.assertRaises(ValueError):
            claude_budget.settings_from([])

    def test_manual_mode_uses_the_manual_cap(self):
        result = status(datetime(2026, 10, 3, 9, 0), weekly=30, mode="Manual", manual_cap_percent=40)
        self.assertEqual((result["mode"], result["weekly_cap_percent"], result["claude_allowed"]), ("manual", 40, True))

    def test_nearly_used_session_blocks_start_even_with_weekly_budget(self):
        result = status(datetime(2026, 10, 9, 21, 0), weekly=10, session=90)
        self.assertFalse(result["claude_allowed"])
        self.assertIn("session", result["reason"])

    def test_data_table_row_extras_are_ignored_and_bad_values_rejected(self):
        self.assertEqual(claude_budget.settings_from({"id": 1, "createdAt": "x", "mode": ""})["mode"], "auto")
        for bad in ({"mode": "sometimes"}, {"unlock_time": "25:00"}, {"manual_cap_percent": 150}):
            with self.assertRaises(ValueError):
                claude_budget.settings_from(bad)


if __name__ == "__main__":
    unittest.main()
