"""Offline driver/engine integration, restart, scheduling and single-owner tests."""

import json
import tempfile
import threading
import unittest
from datetime import datetime, timezone
from pathlib import Path
from unittest import mock

import agent.runtime.tests  # noqa: F401 (no models, real pushes or committed logs)
from workflow_service import service, cycle
from workflow_service.control import StateLease, WorkflowControl, scheduled_slot
from workflow_service.test_service import make_cycle


class ScheduleTests(unittest.TestCase):
    def test_original_nightly_schedule_and_daytime_boundary(self):
        for hour in range(24):
            for minute in (0, 34, 35, 36, 59):
                now = datetime(2026, 10, 17, hour, minute, tzinfo=timezone.utc)
                self.assertEqual(scheduled_slot(now) is not None,
                                 minute == 35 and (hour >= 17 or hour <= 6), (hour, minute))


class DriverTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.path = Path(directory.name) / "tasks.json"
        self.service = service.WorkflowService(service.Store(self.path), "t" * 40)
        self.service.automatic_execution = True
        self.control = WorkflowControl(self.service, poll_seconds=.001)
        self.stop = threading.Event()
        patches = [
            mock.patch.object(service.backlog_writer, "sync_user_requests"),
            mock.patch.object(service, "log_line"),
            mock.patch.object(self.service, "_current_cycle", side_effect=self.service.store.active_cycle),
            mock.patch.object(cycle, "choose_step", side_effect=lambda record, *_: record["phase"] if record else "select"),
        ]
        for patcher in patches:
            patcher.start()
            self.addCleanup(patcher.stop)

    def run_steps(self, implementations, *, start=True):
        if start:
            self.control.begin()
        with mock.patch.dict(cycle.STEPS, implementations):
            self.control.run(self.stop)

    def test_full_story_with_evaluator_and_ci_retry_then_empty_queue(self):
        visited = []
        record = make_cycle()
        evaluations = iter(("implement", "publish", "publish"))
        checks = iter(("implement", "finalize"))

        def phase(name, following):
            def execute(current, _context):
                visited.append(name)
                current["phase"] = following() if callable(following) else following
                return cycle.StepResult(name, True)
            return execute

        steps = {"qa": phase("qa", "implement"), "implement": phase("implement", "evaluate"),
                 "evaluate": phase("evaluate", lambda: next(evaluations)),
                 "publish": phase("publish", "ci"), "ci": phase("ci", lambda: next(checks)),
                 "finalize": phase("finalize", "done")}
        with mock.patch.object(cycle, "step_select", side_effect=[
            (cycle.StepResult("activated", True), record), (cycle.StepResult("queue_empty", False), None)
        ]):
            self.run_steps(steps)
        self.assertEqual(visited, ["qa", "implement", "evaluate", "implement", "evaluate", "publish", "ci",
                                   "implement", "evaluate", "publish", "ci", "finalize"])
        self.assertEqual(self.service.store.control["outcome"], "queue_empty")
        self.assertEqual(self.service.store.control["steps"], len(visited) + 2)
        self.assertIsNone(self.service.store.active_cycle())
        self.assertTrue(all(t["callback_url"] is None for t in self.service.store.tasks.values()))

    def test_budget_pause_preserves_story_and_retry_counts_without_spinning(self):
        self.service.store.save_cycle(make_cycle(phase="implement", retry_count=2, failed_runs=1))
        with mock.patch.object(cycle, "step_select") as select:
            self.run_steps({"implement": lambda *_: cycle.StepResult("waiting_for_claude_budget", False, "weekly cap")})
        select.assert_not_called()
        self.assertEqual(self.service.store.control["outcome"], "waiting_for_claude_budget")
        current = self.service.store.active_cycle()
        self.assertEqual((current["phase"], current["retry_count"], current["failed_runs"]), ("implement", 2, 1))
        self.assertEqual(self.service.store.control["steps"], 1)

    def test_step_error_stops_without_advancing_phase(self):
        self.service.store.save_cycle(make_cycle(phase="publish"))
        with mock.patch.object(service.logging, "exception"):
            self.run_steps({"publish": mock.Mock(side_effect=OSError("push failed"))})
        self.assertEqual(self.service.store.control["outcome"], "error")
        self.assertEqual(self.service.store.active_cycle()["phase"], "publish")
        self.assertIn("push failed", self.service.store.control["reason"])

    def test_restart_uses_completed_task_result_instead_of_repeating_it(self):
        self.control.begin()
        task = self.service.store.create(track_control=True, kind="step", step="implement")
        self.service.store.update(task["id"], status="completed",
                                  result={"outcome": "waiting_for_claude_budget", "proceed": False})
        self.service.store = service.Store(self.path)
        self.control = WorkflowControl(self.service, poll_seconds=.001)
        with mock.patch.object(self.service, "next_step") as next_step:
            self.control.run(self.stop)
        next_step.assert_not_called()
        self.assertEqual(self.service.store.control["outcome"], "waiting_for_claude_budget")

    def test_interrupted_task_resumes_through_existing_engine(self):
        self.control.begin()
        self.service.store.create(track_control=True, kind="step", step="ci")
        self.service.store = service.Store(self.path)
        self.control = WorkflowControl(self.service, poll_seconds=.001)
        with mock.patch.object(cycle, "step_select", return_value=(cycle.StepResult("queue_empty", False), None)):
            self.control.run(self.stop)
        self.assertEqual(self.service.store.control["steps"], 2)
        self.assertEqual(self.service.store.control["outcome"], "queue_empty")

    def test_loop_guard_counts_steps_and_survives_restart(self):
        self.service.store.save_cycle(make_cycle(phase="publish"))
        self.control.begin()
        self.control.record(steps=199)
        self.run_steps({"publish": lambda *_: cycle.StepResult("again", True)}, start=False)
        self.assertEqual(self.service.store.control["outcome"], "loop_guard")
        self.assertEqual(self.service.store.control["steps"], 200)

    def test_graceful_stop_waits_for_current_async_step_and_starts_no_next_step(self):
        self.service.store.save_cycle(make_cycle(phase="ci"))
        entered, release = threading.Event(), threading.Event()

        def ci(*_):
            entered.set()
            if not release.wait(3):
                raise RuntimeError("test did not release CI")
            return cycle.StepResult("PASSED", True)

        self.control.begin()
        with mock.patch.dict(cycle.STEPS, {"ci": ci}):
            worker = threading.Thread(target=self.control.run, args=(self.stop,))
            worker.start()
            try:
                self.assertTrue(entered.wait(2))
                self.stop.set()
                self.assertTrue(worker.is_alive())
            finally:
                release.set()
                worker.join(3)
        self.assertFalse(worker.is_alive())
        self.assertEqual(self.service.store.control["steps"], 1)
        self.assertTrue(self.service.store.control["active"])
        self.assertEqual(self.service.store.get(self.service.store.control["task_id"])["status"], "completed")

    def test_sync_restart_does_not_start_a_step_on_old_code(self):
        self.control.begin()
        def synced(_):
            self.stop.set()
            return cycle.StepResult("synced", True, restart=True)
        with mock.patch.object(cycle, "choose_step", return_value="sync"), \
                mock.patch.object(cycle, "step_sync", side_effect=synced), \
                mock.patch.object(self.service, "restart") as restart:
            self.control.run(self.stop)
        restart.assert_called_once()
        self.assertTrue(self.service.restarting)
        self.assertTrue(self.service.store.control["active"])
        self.assertEqual(self.service.store.control["steps"], 1)

    def test_busy_connection_task_is_waited_for_without_creating_a_run_failure(self):
        self.control.begin()
        stop = mock.Mock(spec=threading.Event)
        stop.is_set.return_value = False
        stop.wait.return_value = True
        with mock.patch.object(self.service, "next_step", side_effect=service.Busy("connection test running")):
            self.control.run(stop)
        self.assertTrue(self.service.store.control["active"])
        self.assertIsNone(self.service.store.control["outcome"])
        self.assertEqual(self.service.store.control["steps"], 0)

    def test_same_scheduled_minute_does_not_repeat_an_empty_run(self):
        self.control.clock = lambda: datetime(2026, 10, 17, 17, 35)
        stop = mock.Mock(spec=threading.Event)
        stop.is_set.return_value = False
        stop.wait.side_effect = (False, True)
        with mock.patch.object(cycle, "step_select", return_value=(cycle.StepResult("queue_empty", False), None)) as select:
            self.control.serve(stop)
        select.assert_called_once()
        self.assertFalse(self.service.store.control["enabled"])

    def test_startup_outside_schedule_waits_without_starting_work(self):
        self.control.clock = lambda: datetime(2026, 10, 17, 12, 0)
        stop = mock.Mock(spec=threading.Event)
        stop.is_set.return_value = False
        stop.wait.return_value = True
        with mock.patch.object(self.service, "next_step") as next_step:
            self.control.serve(stop)
        next_step.assert_not_called()

    def test_once_resumes_active_run_outside_schedule_and_closes_service(self):
        self.control.begin()
        self.control.record(steps=7)
        self.control.clock = lambda: datetime(2026, 10, 17, 12, 0)
        finished = mock.Mock()
        with mock.patch.object(cycle, "step_select", return_value=(cycle.StepResult("queue_empty", False), None)):
            self.control.serve(self.stop, run_now=True, once=True, finished=finished)
        self.assertEqual(self.service.store.control["steps"], 8)
        finished.assert_called_once_with()

    def test_driver_exception_is_observable_and_stops_this_run(self):
        finished = mock.Mock()
        with mock.patch.object(self.service, "next_step", side_effect=ValueError("invalid pointer")), \
                mock.patch.object(service.logging, "exception"):
            self.control.serve(self.stop, once=True, run_now=True, finished=finished)
        self.assertEqual(self.service.store.control["outcome"], "driver_error")
        self.assertIn("invalid pointer", self.service.store.control["reason"])
        finished.assert_called_once_with()

    def test_task_identity_and_attempt_count_are_saved_before_execution(self):
        self.control.begin()
        def select(_):
            saved = json.loads(self.path.read_text(encoding="utf-8"))
            task_id = saved["control"]["task_id"]
            self.assertEqual(saved["tasks"][task_id]["status"], "running")
            self.assertEqual(saved["control"]["steps"], 1)
            return cycle.StepResult("queue_empty", False), None
        with mock.patch.object(cycle, "step_select", side_effect=select):
            self.control.run(self.stop)
        self.assertEqual(self.service.store.control["outcome"], "queue_empty")


class LeaseTests(unittest.TestCase):
    def test_duplicate_owner_is_rejected_and_lease_releases_on_close(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "tasks.json"
            first, second = StateLease(state), StateLease(state)
            try:
                first.acquire()
                with self.assertRaisesRegex(RuntimeError, "Another service owns"):
                    second.acquire()
                first.close()
                second.acquire()
            finally:
                first.close()
                second.close()

    def test_port_conflict_never_loads_or_changes_live_state(self):
        with mock.patch.dict(service.os.environ, {"GW2_WORKFLOW_TOKEN": "t" * 40}), \
                mock.patch.object(service, "disable_console_quick_edit"), \
                mock.patch.object(service, "ThreadingHTTPServer", side_effect=OSError("port in use")), \
                mock.patch.object(service, "Store") as store:
            with self.assertRaisesRegex(OSError, "port in use"):
                service.main(run_workflow=True)
        store.assert_not_called()

    def test_state_lock_conflict_closes_bound_server_without_loading_state(self):
        with mock.patch.dict(service.os.environ, {"GW2_WORKFLOW_TOKEN": "t" * 40}), \
                mock.patch.object(service, "disable_console_quick_edit"), \
                mock.patch.object(service, "ThreadingHTTPServer") as server, \
                mock.patch.object(StateLease, "acquire", side_effect=RuntimeError("another owner")), \
                mock.patch.object(service, "Store") as store:
            with self.assertRaisesRegex(RuntimeError, "another owner"):
                service.main(run_workflow=True)
        store.assert_not_called()
        server.return_value.server_close.assert_called_once_with()


if __name__ == "__main__":
    unittest.main()
