"""Bridge and development-cycle tests. Agents, Git and CI are faked; nothing is launched."""

from __future__ import annotations

import json
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from local_bridge import bridge, cycle

TOKEN = "t" * 40
STORY_FILE = "agent/stories/STORY-T-001-x.md"


def make_cycle(**values) -> dict:
    record = {"story_id": "STORY-T-001", "story_file": STORY_FILE, "phase": "qa", "retry_count": 0,
              "ci_fix_attempts": 0, "failed_runs": 0, "prompt": None, "implement_started": False,
              "publish_paths": [], "published_sha": None, "history": []}
    record.update(values)
    return record


class _CycleTestCase(unittest.TestCase):
    """Fakes every runtime collaborator of the cycle module."""

    def setUp(self):
        patches = {
            "read_file": mock.Mock(return_value="story"),
            "story_state": mock.Mock(),
            "qa_agent": mock.Mock(**{"load_plan.return_value": {"story_id": "STORY-T-001"},
                                     "plan_path.return_value": cycle.REPO_ROOT / "agent/qa-plans/QA-STORY-T-001.json",
                                     "implementation_contract.return_value": " +contract",
                                     "restore_protected_tests.return_value": []}),
            "git_sync": mock.Mock(**{"pending_planning_paths.return_value": set(),
                                     "ci_verification_available.return_value": (True, "")}),
            "github_ci": mock.Mock(),
            "orchestrator": mock.Mock(**{"build_retry_prompt.return_value": "retry prompt",
                                         "build_ci_failure_prompt.return_value": "ci prompt",
                                         "ci_failures_are_outside_story_scope.return_value": False,
                                         "_current_result_content.return_value": "result"}),
        }
        for name, value in patches.items():
            patcher = mock.patch.object(cycle, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        self.orchestrator = cycle.orchestrator

    def evaluate(self, record, decision, **extra):
        self.orchestrator._evaluate_preserving_completed_attempt.return_value = {
            "decision": decision, "reason": "why", **extra}
        return cycle.step_evaluate(record, None)


class CycleStepTests(_CycleTestCase):
    """Phase transitions follow the old orchestrator's retry and CI-fix rules."""

    def test_complete_evaluation_moves_to_publication(self):
        record = make_cycle(phase="evaluate")
        result = self.evaluate(record, "COMPLETE")
        self.assertEqual((record["phase"], result["proceed"]), ("publish", True))

    def test_retry_returns_to_claude_with_retry_prompt_until_budget_is_exhausted(self):
        record = make_cycle(phase="evaluate")
        for attempt in (1, 2):
            result = self.evaluate(record, "RETRY", actionable_retry_items=["fix x"])
            self.assertEqual((record["phase"], record["retry_count"], result["proceed"]), ("implement", attempt, True))
            self.assertEqual(record["prompt"], "retry prompt +contract")
        result = self.evaluate(record, "RETRY", actionable_retry_items=["fix x"])
        self.assertEqual((record["phase"], result["outcome"], result["proceed"]), ("blocked", "blocked", False))
        self.orchestrator._create_intervention_and_block_story.assert_called_once()
        cycle.story_state.clear_active_story.assert_called()

    def test_retry_without_items_blocks_instead_of_spending_a_claude_run(self):
        record = make_cycle(phase="evaluate")
        self.evaluate(record, "RETRY", actionable_retry_items=[])
        self.assertEqual(record["phase"], "blocked")

    def test_evaluator_blocked_moves_story_to_blocked_and_frees_the_pointer(self):
        record = make_cycle(phase="evaluate")
        result = self.evaluate(record, "BLOCKED")
        self.assertFalse(result["proceed"])
        self.orchestrator._blocked_bookkeeping.assert_called_once()
        cycle.story_state.clear_active_story.assert_called_once()

    def test_ci_failure_in_story_scope_returns_to_claude_then_blocks(self):
        record = make_cycle(phase="ci", published_sha="a" * 40)
        cycle.github_ci.wait_for_commit.return_value = {"status": "FAILED", "reason": "red", "report": "r"}
        for attempt in (1, 2):
            result = cycle.step_ci(record, None)
            self.assertEqual((record["phase"], record["ci_fix_attempts"], record["prompt"]),
                             ("implement", attempt, "ci prompt +contract"))
            record["phase"] = "ci"
        cycle.step_ci(record, None)
        self.assertEqual(record["phase"], "blocked")

    def test_ci_waits_for_the_published_sha_not_head(self):
        record = make_cycle(phase="ci", published_sha="b" * 40)
        cycle.github_ci.wait_for_commit.return_value = {"status": "PASSED", "reason": ""}
        result = cycle.step_ci(record, None)
        self.assertEqual(cycle.github_ci.wait_for_commit.call_args.args[1], "b" * 40)
        self.assertEqual((record["phase"], result["proceed"]), ("finalize", True))

    def test_unverified_ci_keeps_the_phase_for_a_later_recheck(self):
        record = make_cycle(phase="ci", published_sha="c" * 40)
        cycle.github_ci.wait_for_commit.return_value = {"status": "UNVERIFIED", "reason": "timeout"}
        result = cycle.step_ci(record, None)
        self.assertEqual((record["phase"], result["proceed"]), ("ci", False))

    def test_ci_failure_outside_story_scope_stops_without_invoking_claude(self):
        record = make_cycle(phase="ci", published_sha="c" * 40)
        cycle.github_ci.wait_for_commit.return_value = {"status": "FAILED", "reason": "red"}
        self.orchestrator.ci_failures_are_outside_story_scope.return_value = True
        result = cycle.step_ci(record, None)
        self.assertEqual((record["phase"], record["ci_fix_attempts"], result["proceed"]), ("ci", 0, False))

    def test_publish_commits_only_dirty_story_scope_and_records_the_sha(self):
        record = make_cycle(phase="publish", publish_paths=["src/A.java"])
        cycle.git_sync.working_tree_paths.return_value = {"src/A.java", STORY_FILE, "unrelated.txt"}
        cycle.git_sync.commit_and_push.return_value = {"status": "PUSHED", "sha": "d" * 40}
        result = cycle.step_publish(record, None)
        self.assertEqual(cycle.git_sync.commit_and_push.call_args.kwargs["paths"], sorted(["src/A.java", STORY_FILE]))
        self.assertEqual((record["phase"], record["published_sha"], result["proceed"]), ("ci", "d" * 40, True))

    def test_failed_push_keeps_the_publish_phase(self):
        record = make_cycle(phase="publish")
        cycle.git_sync.working_tree_paths.return_value = set()
        cycle.git_sync.commit_and_push.return_value = {"status": "PUSH_REJECTED", "reason": "behind"}
        result = cycle.step_publish(record, None)
        self.assertEqual((record["phase"], result["proceed"]), ("publish", False))


class IdleAndPlanningTests(_CycleTestCase):
    """With no active story the bridge selects queued work or replenishes an empty queue."""

    def setUp(self):
        super().setUp()
        self.orchestrator.get_actionable_architect_requests.return_value = []
        self.orchestrator.planning_fingerprint.return_value = "fp1"
        cycle.story_state.get_selectable_story_candidates.return_value = []
        patcher = mock.patch.object(cycle, "run_planning_pass", return_value={"status": "COMPLETE"})
        self.planner = patcher.start()
        self.addCleanup(patcher.stop)

    def test_queued_work_is_selected_without_planning(self):
        cycle.story_state.get_selectable_story_candidates.return_value = ["story"]
        self.assertEqual(cycle.idle_step({}), "select")
        self.orchestrator.requeue_resolved_interventions.assert_called_once()

    def test_empty_queue_plans_once_then_reports_empty_until_inputs_change(self):
        planning = {}
        self.assertEqual(cycle.idle_step(planning), "plan")
        result = cycle.step_plan(planning)
        self.assertEqual((result["outcome"], result["proceed"]), ("planning_complete", False))
        self.assertEqual(cycle.idle_step(planning), "select")
        self.orchestrator.planning_fingerprint.return_value = "fp2"
        self.assertEqual(cycle.idle_step(planning), "plan")

    def test_planning_that_adds_a_selectable_story_continues_to_selection(self):
        self.planner.side_effect = lambda: cycle.story_state.get_selectable_story_candidates.configure_mock(
            return_value=["new story"]) or {"status": "COMPLETE", "story_files_created": ["new.md"]}
        result = cycle.step_plan({})
        self.assertTrue(result["proceed"])
        self.assertEqual(result["stories_created"], ["new.md"])

    def test_actionable_architect_request_is_answered_before_planning(self):
        self.orchestrator.get_actionable_architect_requests.return_value = [{"file": "AR-1.md"}]
        self.orchestrator.run_architect_pass.return_value = {"status": "COMPLETE"}
        self.assertEqual(cycle.idle_step({}), "plan")
        result = cycle.step_plan({})
        self.assertEqual((result["outcome"], result["proceed"]), ("architect_complete", True))
        self.planner.assert_not_called()


class ImplementStepTests(_CycleTestCase):
    def setUp(self):
        super().setUp()
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        plan_file = Path(self.directory.name) / "plan.json"
        plan_file.write_text("{}", encoding="utf-8")
        cycle.qa_agent.plan_path.return_value = plan_file
        cycle.story_state.restore_story_lifecycle.return_value = []
        cycle.git_sync.working_tree_paths.side_effect = [{"dirty.txt"}, {"dirty.txt", "src/New.java"}]
        self.orchestrator._prepare_implementation_prompt.return_value = ("prompt", False)
        for name, value in {"file_hash": mock.Mock(side_effect=["h1", "h2"]),
                            "run_claude_attempt": mock.Mock()}.items():
            patcher = mock.patch.object(cycle, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def implement(self, record, exit_code=0, capacity=False):
        cycle.run_claude_attempt.return_value = mock.Mock(exit_code=exit_code, capacity_exhausted=capacity, output="")
        checkpoints = []
        result = cycle.step_implement(record, lambda: checkpoints.append(dict(record)))
        self.assertTrue(checkpoints and checkpoints[0]["implement_started"], "baseline must be saved before Claude runs")
        return result

    def test_success_records_only_new_changes_and_moves_to_evaluation(self):
        record = make_cycle(phase="implement")
        result = self.implement(record)
        self.assertEqual(record["publish_paths"], ["src/New.java"])
        self.assertEqual((record["phase"], record["implement_started"], record["result_was_updated"]),
                         ("evaluate", False, True))
        self.assertTrue(result["proceed"])

    def test_interrupted_attempt_resumes_with_a_continuation_prompt_and_its_baseline(self):
        record = make_cycle(phase="implement", implement_started=True, prompt="prompt",
                            run_baseline=["dirty.txt"], result_hash_before="h0")
        cycle.git_sync.working_tree_paths.side_effect = [{"dirty.txt", "src/New.java"}]
        self.implement(record)
        self.assertTrue(cycle.run_claude_attempt.call_args.args[0].startswith(cycle.CONTINUATION))
        self.assertEqual(record["publish_paths"], ["src/New.java"])

    def test_capacity_exhaustion_stops_without_using_the_failure_budget(self):
        record = make_cycle(phase="implement")
        result = self.implement(record, exit_code=1, capacity=True)
        self.assertEqual((record["phase"], record["failed_runs"], result["proceed"]), ("implement", 0, False))

    def test_repeated_claude_failures_block_the_story(self):
        record = make_cycle(phase="implement")
        self.assertTrue(self.implement(record, exit_code=2)["proceed"])
        cycle.git_sync.working_tree_paths.side_effect = [{"dirty.txt"}]
        cycle.file_hash.side_effect = ["h3"]
        self.assertFalse(self.implement(record, exit_code=2)["proceed"])
        self.assertEqual(record["phase"], "blocked")


class _CallbackReceiver(BaseHTTPRequestHandler):
    received: list = []

    def do_POST(self):
        self.received.append((self.path, json.loads(self.rfile.read(int(self.headers["Content-Length"])))))
        self.send_response(200)
        self.end_headers()

    def log_message(self, *args):
        pass


class BridgeHttpTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.state = Path(self.directory.name) / "tasks.json"
        _CallbackReceiver.received = []
        self.receiver = ThreadingHTTPServer(("127.0.0.1", 0), _CallbackReceiver)
        threading.Thread(target=self.receiver.serve_forever, daemon=True).start()
        self.addCleanup(self.receiver.shutdown)
        origin = f"http://127.0.0.1:{self.receiver.server_port}"
        self.callback = origin + "/webhook-waiting/1?signature=abc"
        self.bridge = bridge.Bridge(bridge.Store(self.state), TOKEN, callback_origin=origin)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), bridge.make_handler(self.bridge))
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.shutdown)
        self.release = threading.Event()
        pointer = mock.patch.object(cycle, "pointer_story", return_value=cycle.REPO_ROOT / STORY_FILE)
        pointer.start()
        self.addCleanup(pointer.stop)
        self.bridge.store.save_cycle(make_cycle(phase="ci", published_sha="e" * 40))

    def post(self, path, body, token=TOKEN):
        request = Request(f"http://127.0.0.1:{self.server.server_port}{path}", method="POST",
                          data=json.dumps(body).encode(), headers={"Authorization": f"Bearer {token}",
                                                                   "Content-Type": "application/json"})
        with urlopen(request, timeout=5) as response:
            return response.status, json.loads(response.read())

    def wait_for(self, condition):
        deadline = time.monotonic() + 5
        while not condition():
            self.assertLess(time.monotonic(), deadline, "timed out")
            time.sleep(0.02)

    def fake_ci(self, record, _checkpoint):
        self.release.wait(5)
        record["phase"] = "finalize"
        return cycle.StepResult("PASSED", True)

    def test_async_step_runs_once_and_delivers_its_result_to_the_latest_wait_url(self):
        with mock.patch.dict(cycle.STEPS, {"ci": self.fake_ci}):
            status, first = self.post("/cycle/step", {"callback_url": self.callback})
            self.assertEqual((status, first["status"], first["step"]), (202, "running", "ci"))
            replacement = self.callback.replace("/1?", "/2?")
            _, attached = self.post("/cycle/step", {"callback_url": replacement})
            self.assertEqual(attached["task_id"], first["task_id"])
            self.release.set()
            self.wait_for(lambda: _CallbackReceiver.received)
        path, payload = _CallbackReceiver.received[0]
        self.assertEqual(path, "/webhook-waiting/2?signature=abc")
        self.assertEqual((payload["outcome"], payload["proceed"], payload["phase"]), ("PASSED", True, "finalize"))
        self.assertEqual(self.bridge.store.active_cycle()["history"][-1]["outcome"], "PASSED")

    def test_failing_step_stops_the_pipeline_and_keeps_the_phase(self):
        def broken(_record, _checkpoint):
            raise RuntimeError("boom")
        with mock.patch.dict(cycle.STEPS, {"ci": broken}):
            self.post("/cycle/step", {"callback_url": self.callback})
            self.wait_for(lambda: _CallbackReceiver.received)
        payload = _CallbackReceiver.received[0][1]
        self.assertEqual((payload["status"], payload["proceed"], payload["phase"]), ("failed", False, "ci"))
        self.assertIn("boom", payload["reason"])

    def test_restart_marks_a_running_step_interrupted_so_the_next_run_repeats_it(self):
        with mock.patch.dict(cycle.STEPS, {"ci": self.fake_ci}):
            _, task = self.post("/cycle/step", {"callback_url": self.callback})
            restarted = bridge.Store(self.state)
            self.release.set()
        self.assertEqual(restarted.get(task["task_id"])["status"], "interrupted")
        self.assertEqual(restarted.active_cycle()["phase"], "ci")

    def test_pointer_change_by_a_human_drops_the_stale_cycle_and_adopts_the_new_story(self):
        other = cycle.REPO_ROOT / "agent/stories/STORY-T-002-y.md"
        with mock.patch.object(cycle, "pointer_story", return_value=other), \
                mock.patch.object(cycle, "new_cycle", return_value=make_cycle(story_id="STORY-T-002",
                                                                               story_file="agent/stories/STORY-T-002-y.md")):
            adopted = self.bridge._current_cycle()
        self.assertEqual((adopted["story_id"], adopted["phase"]), ("STORY-T-002", "qa"))

    def test_rejects_bad_token_and_foreign_callback_urls(self):
        with self.assertRaises(HTTPError) as unauthorized:
            self.post("/cycle/step", {"callback_url": self.callback}, token="wrong")
        self.assertEqual(unauthorized.exception.code, 401)
        for url in ("http://evil.example/webhook-waiting/1?signature=a", self.callback.split("?")[0]):
            with self.assertRaises(HTTPError) as rejected:
                self.post("/cycle/step", {"callback_url": url})
            self.assertEqual(rejected.exception.code, 400)

    def test_connection_test_task_is_refused_while_a_step_runs(self):
        with mock.patch.dict(cycle.STEPS, {"ci": self.fake_ci}):
            self.post("/cycle/step", {"callback_url": self.callback})
            with self.assertRaises(HTTPError) as busy:
                self.post("/tasks", {"prompt": "hi", "callback_url": self.callback})
            self.release.set()
        self.assertEqual(busy.exception.code, 409)


if __name__ == "__main__":
    unittest.main()
