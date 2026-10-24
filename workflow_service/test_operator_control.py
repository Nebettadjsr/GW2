"""Claude start window, pause/abort holds and the agent-process registry; no real agents run."""

import json
import subprocess
import sys
import tempfile
import threading
import unittest
from datetime import datetime
from pathlib import Path
from unittest import mock

import agent.runtime.tests  # noqa: F401 (no models, real pushes or committed logs)
from agent.runtime.support import processes
from workflow_service import claude_budget, cycle, service
from workflow_service.control import WorkflowControl, scheduled_slot
from workflow_service.test_service import make_cycle

DEFAULT = claude_budget.settings_from(None)
USAGE = ("Current session: 0% used · resets Oct 9, 11:40pm (Europe/Berlin)\n"
         "Current week (all models): 0% used · resets Oct 10, 7am (Europe/Berlin)\n")


class WindowTests(unittest.TestCase):
    def test_window_is_inclusive_at_both_ends_and_wraps_midnight(self):
        cases = {(17, 34): False, (17, 35): True, (23, 59): True, (0, 0): True, (6, 35): True, (6, 36): False,
                 (12, 0): False}
        for (hour, minute), expected in cases.items():
            self.assertEqual(claude_budget.window_open(DEFAULT, datetime(2026, 10, 21, hour, minute)),
                             expected, (hour, minute))

    def test_override_opens_the_window_but_is_never_a_setting(self):
        with mock.patch.object(claude_budget, "WINDOW_OVERRIDE", True):
            self.assertTrue(claude_budget.window_open(DEFAULT, datetime(2026, 10, 21, 12, 0)))
        self.assertNotIn("override", " ".join(DEFAULT))

    def test_closed_window_blocks_claude_with_usage_still_ok(self):
        result = claude_budget.status(None, now=datetime(2026, 10, 7, 7, 0), usage_text=USAGE)
        self.assertFalse(result["claude_allowed"])
        self.assertTrue(result["usage_allowed"] or result["weekly_used_percent"] >= result["weekly_cap_percent"])
        self.assertFalse(result["window_open"])
        self.assertIn("no new Claude starts until Wed 17:35", result["reason"])

    def test_next_start_after_midnight_and_validation(self):
        self.assertEqual(claude_budget.next_window_start(DEFAULT, datetime(2026, 10, 21, 18, 0)),
                         datetime(2026, 10, 22, 17, 35))
        with self.assertRaises(ValueError):
            claude_budget.settings_from({"window_end": "25:00"})

    def test_schedule_follows_the_window(self):
        self.assertIsNotNone(scheduled_slot(datetime(2026, 10, 21, 6, 35)))
        self.assertIsNone(scheduled_slot(datetime(2026, 10, 21, 7, 35)))
        self.assertIsNone(scheduled_slot(datetime(2026, 10, 21, 18, 36)))


class ProcessRegistryTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.registry = Path(directory.name) / "tasks.processes.json"
        processes.configure(self.registry)
        self.addCleanup(processes.configure, None)

    def sleeper(self):
        child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
        self.addCleanup(lambda: (child.kill(), child.wait()))
        return child

    def test_abort_kills_registered_processes_and_flags_the_waiting_step(self):
        child = self.sleeper()
        processes.register(child.pid, "claude")
        self.assertEqual([entry["pid"] for entry in processes.abort_all()], [child.pid])
        child.wait(10)
        with self.assertRaises(processes.StepAborted):
            processes.finish(child.pid)
        self.assertEqual(processes.entries(), [])

    def test_normal_finish_does_not_raise_and_unregisters(self):
        child = self.sleeper()
        processes.register(child.pid, "codex")
        self.assertEqual(len(processes.read(self.registry)), 1)
        processes.finish(child.pid)
        self.assertEqual(processes.read(self.registry), [])

    def test_unregistered_bystanders_are_left_alone(self):
        bystander, registered = self.sleeper(), self.sleeper()
        processes.register(registered.pid, "claude")
        processes.abort_all()
        registered.wait(10)
        self.assertIsNone(bystander.poll())

    def test_startup_sweep_kills_a_matching_leftover_but_not_a_reused_pid(self):
        leftover, reused = self.sleeper(), self.sleeper()
        processes.register(leftover.pid, "claude")
        processes.register(reused.pid, "codex")
        entries = processes.read(self.registry)
        entries[1]["started"] -= 1000  # same pid, different process: a reused pid
        self.registry.write_text(json.dumps(entries), encoding="utf-8")
        processes.configure(self.registry)
        swept = processes.sweep_stale(self.registry)
        self.assertEqual([entry["pid"] for entry in swept], [leftover.pid])
        leftover.wait(10)
        self.assertIsNone(reused.poll())


class HoldTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name) / "tasks.json"
        self.service = service.WorkflowService(service.Store(self.path), "t" * 40)
        self.service.automatic_execution = True
        self.control = WorkflowControl(self.service, poll_seconds=.001)
        self.stop = threading.Event()
        for patcher in (
            mock.patch.object(service.backlog_writer, "sync_user_requests"),
            mock.patch.object(service, "log_line"),
            mock.patch.object(self.service, "_current_cycle", side_effect=self.service.store.active_cycle),
            mock.patch.object(cycle, "choose_step", side_effect=lambda record, *_: record["phase"]),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_pause_lets_the_current_step_finish_then_holds_and_resume_continues(self):
        self.service.store.save_cycle(make_cycle(phase="qa"))
        visited = []

        def qa(current, _context):
            visited.append("qa")
            self.service.control_command("pause")  # requested while this step is running
            current["phase"] = "implement"
            return cycle.StepResult("READY", True)

        def implement(current, _context):
            visited.append("implement")
            current["phase"] = "evaluate"
            return cycle.StepResult("implemented", False)

        self.control.begin()
        with mock.patch.dict(cycle.STEPS, {"qa": qa, "implement": implement}):
            self.control.run(self.stop)
            self.assertEqual(visited, ["qa"])
            self.assertEqual(self.service.store.control["status"], "paused")
            self.assertTrue(self.service.store.control["active"])
            self.assertTrue(service.Store(self.path).control["hold"])  # survives a restart
            self.service.control_command("resume")
            self.assertEqual(self.service.store.control["status"], "running")
            self.control.run(self.stop)
        self.assertEqual(visited, ["qa", "implement"])

    def test_abort_marks_the_step_interrupted_without_spending_retries_and_holds(self):
        self.service.store.save_cycle(make_cycle(phase="implement", failed_runs=0, retry_count=1))
        self.control.begin()

        def implement(*_):
            raise processes.StepAborted("killed")

        with mock.patch.dict(cycle.STEPS, {"implement": implement}):
            self.control.run(self.stop)
        task = next(iter(self.service.store.tasks.values()))
        self.assertEqual(task["status"], "interrupted")
        current = self.service.store.active_cycle()
        self.assertEqual((current["phase"], current["failed_runs"], current["retry_count"], current["history"]),
                         ("implement", 0, 1, []))

    def test_abort_command_holds_and_stops_registered_agents(self):
        self.control.begin()
        with mock.patch.object(processes, "abort_all", return_value=[{"pid": 7, "role": "claude"}]) as abort:
            result = self.service.control_command("abort")
        abort.assert_called_once_with()
        self.assertEqual(result["stopped_processes"], [{"pid": 7, "role": "claude"}])
        self.assertTrue(self.service.store.control["hold"])
        self.assertEqual(self.service.store.control["status"], "paused")

    def test_manual_schedule_and_unknown_actions(self):
        self.service.control_command("manual")
        self.assertEqual(self.service.store.control["schedule"], "manual")
        self.service.control_command("auto")
        self.assertEqual(self.service.store.control["schedule"], "auto")
        with self.assertRaises(ValueError):
            self.service.control_command("explode")

    def test_held_service_does_not_start_a_scheduled_run(self):
        self.service.control_command("pause")
        clock = mock.Mock(return_value=datetime(2026, 10, 21, 18, 35).astimezone())
        control = WorkflowControl(self.service, clock=clock, poll_seconds=.001)
        with mock.patch.object(control, "run") as run:
            threading.Timer(.1, self.stop.set).start()
            control.serve(self.stop)
        run.assert_not_called()


class ManualReleaseTests(unittest.TestCase):
    def setUp(self):
        self.addCleanup(setattr, claude_budget, "RELEASED", False)
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name) / "tasks.json"
        self.service = service.WorkflowService(service.Store(self.path), "t" * 40)
        patcher = mock.patch.object(service, "log_line")
        patcher.start()
        self.addCleanup(patcher.stop)

    def closed_and_over_budget(self):
        used = USAGE.replace("Current session: 0%", "Current session: 99%").replace("week (all models): 0%", "week (all models): 99%")
        return claude_budget.status(None, now=datetime(2026, 10, 7, 12, 0), usage_text=used)

    def test_manual_schedule_releases_claude_from_window_and_budget_and_auto_restores_them(self):
        blocked = self.closed_and_over_budget()
        self.assertFalse(blocked["claude_allowed"])
        self.service.control_command("manual")
        released = self.closed_and_over_budget()
        self.assertTrue(released["claude_allowed"])
        self.assertTrue(released["released"])
        self.assertFalse(released["usage_allowed"])  # a real capacity failure is still recognised
        self.assertIn("Released by the operator", released["reason"])
        self.service.control_command("auto")
        self.assertFalse(self.closed_and_over_budget()["claude_allowed"])

    def test_budget_state_follows_the_release_even_without_a_fresh_reading(self):
        self.assertFalse(self.service.budget_state()["claude_allowed"])
        self.service.control_command("manual")
        self.assertTrue(self.service.budget_state()["claude_allowed"])

    def test_a_restart_keeps_the_persisted_schedule(self):
        self.service.control_command("manual")
        claude_budget.RELEASED = False
        service.WorkflowService(service.Store(self.path), "t" * 40)
        self.assertTrue(claude_budget.RELEASED)


if __name__ == "__main__":
    unittest.main()
