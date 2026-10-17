"""Persistent budget observation; no model, pipeline, Git, or live state is used."""

import json
import subprocess
import tempfile
import threading
import unittest
from pathlib import Path
from unittest import mock

import agent.runtime.tests  # noqa: F401  (neutralizes model runs, real pushes, and committed logs)
from local_bridge import bridge


class BudgetStateTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name) / "tasks.json"
        self.service = bridge.Bridge(bridge.Store(self.path), "t" * 40)
        self.probe = mock.patch.object(bridge.claude_budget, "status",
                                       return_value={"claude_allowed": True, "reason": "capacity available"}).start()
        self.addCleanup(mock.patch.stopall)

    def test_old_state_gains_budget_without_losing_cycle_or_planner_progress(self):
        original = {"tasks": {}, "cycles": {"STORY-1": {"phase": "evaluate"}},
                    "active": "STORY-1", "planning": {"fingerprint": "saved"}}
        self.path.write_text(json.dumps(original), encoding="utf-8")
        store = bridge.Store(self.path)
        restored = json.loads(self.path.read_text(encoding="utf-8"))
        for key, value in original.items():
            self.assertEqual(restored[key], value)
        self.assertEqual(store.budget["settings"]["unlock_time"], "17:30")
        self.probe.assert_not_called()

    def test_manual_settings_and_last_reading_survive_restart(self):
        self.service.check_budget({"mode": "manual", "manual_cap_percent": 35})
        restarted = bridge.Bridge(bridge.Store(self.path), "t" * 40)
        self.assertEqual(restarted.budget_settings["manual_cap_percent"], 35)
        self.assertEqual(restarted.budget_settings["mode"], "manual")
        self.assertTrue(restarted.budget_state()["claude_allowed"])
        self.assertEqual(restarted.budget_state()["result"]["reason"], "capacity available")

    def test_refresh_without_n8n_settings_uses_the_saved_configuration(self):
        self.service.set_budget_settings({"mode": "manual", "manual_cap_percent": 35})
        self.service.check_budget()
        self.probe.assert_called_once_with(self.service.budget_settings)
        self.assertEqual(self.probe.call_args.args[0]["mode"], "manual")

    def test_reads_do_not_probe_and_an_old_green_result_is_marked_stale(self):
        self.service.check_budget()
        self.service.store.budget["checked_at"] = "2000-01-01T00:00:00+00:00"
        self.probe.reset_mock()
        state = self.service.budget_state()
        self.assertTrue(state["stale"])
        self.assertFalse(state["claude_allowed"])
        self.probe.assert_not_called()

    def test_settings_change_invalidates_a_previous_reading(self):
        self.service.check_budget()
        self.service.set_budget_settings({"mode": "manual", "manual_cap_percent": 0})
        state = self.service.budget_state()
        self.assertTrue(state["stale"])
        self.assertIsNone(state["result"])
        self.assertFalse(state["claude_allowed"])

    def test_probe_failure_is_persisted_without_old_permission_or_pipeline_changes(self):
        self.service.check_budget()
        self.service.store.save_cycle({"story_id": "STORY-1", "phase": "implement", "failed_runs": 0})
        self.probe.side_effect = subprocess.TimeoutExpired("claude /usage", 30)
        with self.assertRaises(subprocess.TimeoutExpired):
            self.service.check_budget()
        restarted = bridge.Bridge(bridge.Store(self.path), "t" * 40)
        self.assertIsNotNone(restarted.budget_state()["error"])
        self.assertIsNone(restarted.budget_state()["result"])
        self.assertFalse(restarted.budget_state()["claude_allowed"])
        self.assertEqual(restarted.store.active_cycle()["failed_runs"], 0)
        self.assertEqual(restarted.store.active_cycle()["phase"], "implement")

    def test_invalid_settings_cannot_replace_the_last_valid_state(self):
        self.service.check_budget({"mode": "manual", "manual_cap_percent": 35})
        before = self.path.read_bytes()
        with self.assertRaises(ValueError):
            self.service.check_budget({"manual_cap_percent": float("nan")})
        self.assertEqual(self.path.read_bytes(), before)

    def test_monitor_refreshes_at_startup_then_the_next_quarter_hour_even_after_failure(self):
        stop = mock.Mock(spec=threading.Event)
        stop.is_set.return_value = False
        stop.wait.return_value = True
        with mock.patch.object(self.service, "check_budget", side_effect=RuntimeError("unavailable")) as check, \
                mock.patch.object(bridge.time, "time", return_value=3001.25), \
                mock.patch.object(bridge.logging, "exception"):
            self.service.monitor_budget(stop)
        check.assert_called_once_with()
        stop.wait.assert_called_once_with(598.75)


if __name__ == "__main__":
    unittest.main()
