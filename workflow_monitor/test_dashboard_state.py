"""Projection scenarios. Run with Python's jq package installed."""
import json
from pathlib import Path
import unittest

import jq

QUERY = jq.compile(Path(__file__).with_name("dashboard-state.jq").read_text(encoding="utf-8"))


def state(**changes):
    value = {
        "active_cycle": {"phase": "implement", "history": [
            {"step": "qa", "outcome": "READY"},
            {"step": "evaluate", "outcome": "RETRY"}]},
        "control": {"active": False, "outcome": "waiting_for_claude_budget"},
        "budget": {"stale": False, "error": None, "claude_allowed": False,
                   "result": {"weekly_used_percent": 19, "weekly_cap_percent": 14.3}},
    }
    value.update(changes)
    return QUERY.input_value(value).first()[0]


class DashboardStateTests(unittest.TestCase):
    def test_budget_wait_does_not_report_historical_retry_as_failure(self):
        result = state()
        self.assertEqual(result["activity"], "WAITING FOR CLAUDE BUDGET")
        self.assertEqual(result["implement"], 4)
        self.assertEqual(result["qa"], 2)
        self.assertEqual(result["evaluate"], 0)
        self.assertIn("REVISION REQUESTED", result["evaluate_label"])
        self.assertEqual(result["weekly"], "19% used / 14.3% allowed")

    def test_running_task_wins_over_saved_phase_and_budget(self):
        result = state(running_task={"status": "running", "step": "evaluate"})
        self.assertEqual(result["activity"], "RUNNING")
        self.assertEqual(result["saved_phase"], "evaluate")
        self.assertEqual(result["evaluate"], 1)
        self.assertNotEqual(result["implement"], 4)

    def test_stale_or_error_reading_is_not_permission(self):
        for budget in ({"stale": True, "claude_allowed": False, "result": {}},
                       {"stale": False, "error": "unavailable", "result": {}}, {}):
            with self.subTest(budget=budget):
                result = state(budget=budget)
                self.assertEqual(result["activity"], "BUDGET CHECK NEEDED")
                self.assertNotEqual(result["budget_status"], "AVAILABLE")

    def test_new_allowance_does_not_mean_execution_started(self):
        result = state(budget={"stale": False, "claude_allowed": True, "result": {}})
        self.assertEqual(result["activity"], "BUDGET AVAILABLE / AWAITING RUN")

    def test_current_error_marks_only_current_stage_red(self):
        result = state(control={"active": False, "outcome": "error"})
        self.assertEqual(result["activity"], "STOPPED / ERROR")
        self.assertEqual(result["implement"], 3)
        self.assertEqual(result["qa"], 2)

    def test_disabled_automation_is_paused(self):
        result = state(control={"enabled": False})
        self.assertEqual(result["activity"], "PAUSED / AWAITING RUN")

    def test_empty_legacy_payload_is_idle_with_unknown_budget(self):
        result = QUERY.input_value({}).first()[0]
        self.assertEqual(result["activity"], "IDLE")
        self.assertEqual(result["budget_status"], "UNKNOWN")


if __name__ == "__main__":
    unittest.main()
