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

import agent.runtime.tests  # noqa: F401  (guards: no agent/logs writes, model runs or real pushes)
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
    """The old orchestrator's architect/planner triggers, between stories and while Claude waits."""

    def setUp(self):
        super().setUp()
        self.orchestrator.get_actionable_architect_requests.return_value = []
        self.orchestrator.planning_fingerprint.return_value = "fp1"
        cycle.story_state.get_selectable_story_candidates.return_value = []
        patches = {"run_planning_pass": mock.Mock(return_value={"status": "COMPLETE"}),
                   "claude_budget": mock.Mock(**{"status.return_value": {"claude_allowed": False}})}
        for name, value in patches.items():
            patcher = mock.patch.object(cycle, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        self.planner = cycle.run_planning_pass

    def test_queued_work_is_selected_without_planning_on_unknown_inputs(self):
        cycle.story_state.get_selectable_story_candidates.return_value = ["story"]
        self.assertEqual(cycle.choose_step(None, {}, None), "select")
        self.orchestrator.requeue_resolved_interventions.assert_called_once()

    def test_empty_queue_plans_once_then_selects_until_inputs_change(self):
        planning = {}
        self.assertEqual(cycle.choose_step(None, planning, None), "plan")
        result = cycle.step_plan(planning)
        self.assertEqual((result["outcome"], result["proceed"]), ("planning_complete", True))
        self.assertEqual(cycle.choose_step(None, planning, None), "select")
        self.orchestrator.planning_fingerprint.return_value = "fp2"
        self.assertEqual(cycle.choose_step(None, planning, None), "plan")

    def test_changed_planning_inputs_replan_even_with_a_stocked_queue(self):
        cycle.story_state.get_selectable_story_candidates.return_value = ["story"]
        self.assertEqual(cycle.choose_step(None, {"fingerprint": "fp0"}, None), "plan")

    def test_planning_trigger_threshold_from_config_is_honoured(self):
        cycle.story_state.get_selectable_story_candidates.return_value = ["one", "two"]
        with mock.patch.object(cycle, "should_trigger_planning", return_value=True) as trigger:
            self.assertEqual(cycle.choose_step(None, {}, None), "plan")
        trigger.assert_called_once_with(2)

    def test_follow_up_pass_only_after_a_pass_that_changed_something(self):
        self.planner.return_value = {"status": "COMPLETE", "independent_work_remaining": True}
        self.orchestrator.planning_fingerprint.side_effect = ["before", "after"]
        planning = {}
        cycle.step_plan(planning)
        self.assertTrue(planning["follow_up"])
        self.orchestrator.planning_fingerprint.side_effect = ["same", "same"]
        cycle.step_plan(planning)
        self.assertFalse(planning["follow_up"])

    def test_failed_planning_stops_and_waits_for_changed_inputs(self):
        self.planner.return_value = {"status": "FAILED", "reason": "invalid output"}
        planning = {}
        result = cycle.step_plan(planning)
        self.assertEqual((result["outcome"], result["proceed"]), ("planning_failed", False))
        self.assertEqual(planning["fingerprint"], "fp1")

    def test_actionable_architect_request_is_answered_before_planning(self):
        self.orchestrator.get_actionable_architect_requests.return_value = [{"file": "AR-1.md"}]
        self.orchestrator.run_architect_pass.return_value = {"status": "COMPLETE"}
        cycle.story_state.get_selectable_story_candidates.return_value = ["story"]
        self.assertEqual(cycle.choose_step(None, {"fingerprint": "fp1"}, None), "plan")
        result = cycle.step_plan({})
        self.assertEqual((result["outcome"], result["proceed"]), ("architect_complete", True))
        self.planner.assert_not_called()

    def test_codex_works_while_claude_waits_for_its_budget(self):
        record = make_cycle(phase="implement")
        self.assertEqual(cycle.choose_step(record, {}, {"mode": "auto"}), "plan")
        cycle.claude_budget.status.return_value = {"claude_allowed": True}
        self.assertEqual(cycle.choose_step(record, {}, {"mode": "auto"}), "implement")

    def test_other_phases_are_never_delayed_by_codex_work(self):
        self.assertEqual(cycle.choose_step(make_cycle(phase="ci"), {}, None), "ci")
        cycle.claude_budget.status.assert_not_called()


class SelectQaFinalizeTests(_CycleTestCase):
    def test_selection_activates_the_first_story_and_starts_at_qa(self):
        cycle.story_state.validate_backlog_consistency.return_value = []
        cycle.story_state.set_active_story.return_value = cycle.REPO_ROOT / STORY_FILE
        cycle.story_state.extract_story_id.return_value = "STORY-T-001"
        with mock.patch.object(cycle, "select_next_story",
                               return_value={"decision": "NEXT", "story_path": "STORY-T-001-x.md", "reason": "first"}):
            result, record = cycle.step_select(None)
        cycle.story_state.set_active_story.assert_called_once_with("STORY-T-001-x.md")
        self.assertEqual((result["outcome"], record["phase"], record["story_file"]), ("activated", "qa", STORY_FILE))

    def test_empty_queue_reports_inconsistencies_and_stranded_architect_requests(self):
        cycle.story_state.validate_backlog_consistency.return_value = ["two Active entries"]
        self.orchestrator._report_undispatchable_architect_requests.return_value = ["AR-9.md"]
        with mock.patch.object(cycle, "select_next_story",
                               return_value={"decision": "NO_WORK", "reason": "Nothing queued."}):
            result, record = cycle.step_select(None)
        self.assertIsNone(record)
        self.assertEqual((result["outcome"], result["proceed"], result["queue_problems"]),
                         ("queue_empty", False, ["two Active entries"]))
        self.assertIn("AR-9.md", result["reason"])
        self.orchestrator.log_line.assert_called_once()

    def test_ready_qa_plan_moves_to_implementation(self):
        record = make_cycle()
        self.orchestrator._ensure_preimplementation_qa.return_value = {"status": "READY"}
        result = cycle.step_qa(record, None)
        self.assertEqual((record["phase"], result["outcome"], result["proceed"]), ("implement", "READY", True))

    def test_qa_that_blocked_the_story_stops_and_frees_the_pointer(self):
        record = make_cycle()
        self.orchestrator._ensure_preimplementation_qa.return_value = None
        result = cycle.step_qa(record, None)
        self.assertEqual((record["phase"], result["proceed"]), ("blocked", False))
        cycle.story_state.clear_active_story.assert_called_once()

    def test_ci_pass_reports_agent_workflow_failures_without_holding_the_story(self):
        record = make_cycle(phase="ci", published_sha="f" * 40)
        cycle.github_ci.wait_for_commit.return_value = {
            "status": "PASSED", "reason": "ok",
            "ignored_failures": [{"job": "Agent runtime tests (pytest-cov)", "run_url": "u"}]}
        result = cycle.step_ci(record, None)
        self.assertEqual((record["phase"], result["proceed"]), ("finalize", True))
        self.assertIn("Agent runtime tests (pytest-cov) failed for ffffffffffff", result["workflow_warnings"][0])

    def test_finalize_commits_only_lifecycle_files_and_continues(self):
        record = make_cycle(phase="finalize", published_sha="a" * 40)
        cycle.git_sync.working_tree_paths.return_value = {STORY_FILE, "agent/stories/BACKLOG.md", "other.txt"}
        cycle.git_sync.commit_and_push.return_value = {"status": "PUSHED", "sha": "b" * 40}
        result = cycle.step_finalize(record, None)
        self.assertEqual(cycle.git_sync.commit_and_push.call_args.args, ("finalized STORY-T-001 after CI aaaaaaaaaaaa",))
        self.assertEqual(cycle.git_sync.commit_and_push.call_args.kwargs["paths"],
                         sorted([STORY_FILE, "agent/stories/BACKLOG.md"]))
        self.assertEqual((record["phase"], result["proceed"]), ("done", True))
        cycle.git_sync.clear_pending_planning_paths.assert_called_once()

    def test_finalize_push_failure_keeps_the_phase_for_a_retry(self):
        record = make_cycle(phase="finalize", published_sha="a" * 40)
        cycle.git_sync.working_tree_paths.return_value = set()
        cycle.git_sync.commit_and_push.return_value = {"status": "PUSH_REJECTED", "reason": "behind"}
        result = cycle.step_finalize(record, None)
        self.assertEqual((record["phase"], result["proceed"]), ("finalize", False))
        cycle.git_sync.clear_pending_planning_paths.assert_not_called()


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
        self.budget = {"claude_allowed": True, "reason": "ok"}
        for name, value in {"file_hash": mock.Mock(side_effect=["h1", "h2"]),
                            "run_claude_attempt": mock.Mock(),
                            "claude_budget": mock.Mock(**{"status.return_value": self.budget})}.items():
            patcher = mock.patch.object(cycle, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)

    def implement(self, record, exit_code=0, capacity=False):
        cycle.run_claude_attempt.return_value = mock.Mock(exit_code=exit_code, capacity_exhausted=capacity, output="")
        checkpoints = []
        context = cycle.StepContext(lambda: checkpoints.append(dict(record)), {"mode": "auto"})
        result = cycle.step_implement(record, context)
        self.assertTrue(checkpoints and checkpoints[0]["implement_started"], "baseline must be saved before Claude runs")
        return result

    def test_success_records_only_new_changes_and_moves_to_evaluation(self):
        record = make_cycle(phase="implement")
        result = self.implement(record)
        self.assertEqual(record["publish_paths"], ["src/New.java"])
        self.assertEqual((record["phase"], record["implement_started"], record["result_was_updated"]),
                         ("evaluate", False, True))
        self.assertTrue(result["proceed"])

    def test_exhausted_budget_stops_before_claude_and_keeps_the_phase(self):
        self.budget.update(claude_allowed=False, reason="Weekly Claude budget reached")
        result = cycle.step_implement(make_cycle(phase="implement"), cycle.StepContext(lambda: None, {"mode": "auto"}))
        self.assertEqual((result["outcome"], result["proceed"]), ("waiting_for_claude_budget", False))
        cycle.claude_budget.status.assert_called_once_with({"mode": "auto"})
        cycle.run_claude_attempt.assert_not_called()

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

    def test_failure_with_exhausted_usage_counts_as_capacity_even_without_a_known_message(self):
        record = make_cycle(phase="implement")
        cycle.claude_budget.status.side_effect = [dict(self.budget), {"claude_allowed": False, "reason": "session"}]
        result = self.implement(record, exit_code=1)
        self.assertEqual((result["outcome"], record["failed_runs"], result["proceed"]),
                         ("capacity_exhausted", 0, False))

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
        self.log = mock.patch.object(bridge, "log_line").start()  # never write the real agent/logs
        self.addCleanup(mock.patch.stopall)
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

    def get(self, path):
        request = Request(f"http://127.0.0.1:{self.server.server_port}{path}",
                          headers={"Authorization": f"Bearer {TOKEN}"})
        with urlopen(request, timeout=5) as response:
            return json.loads(response.read())

    def test_finished_steps_are_written_to_the_decision_log_and_inspectable(self):
        with mock.patch.dict(cycle.STEPS, {"ci": self.fake_ci}):
            _, task = self.post("/cycle/step", {"callback_url": self.callback, "budget": {"mode": "auto"}})
            self.assertEqual(self.get("/cycle")["running_task"]["task_id"], task["task_id"])
            self.release.set()
            self.wait_for(lambda: _CallbackReceiver.received)
        self.assertEqual(self.get(f"/tasks/{task['task_id']}")["outcome"], "PASSED")
        self.assertIsNone(self.get("/cycle")["running_task"])
        self.log.assert_any_call("Pipeline ci [STORY-T-001]: PASSED")

    def test_exhausted_model_capacity_is_a_pause_not_a_failure(self):
        def exhausted(_record, _context):
            raise bridge.ModelCapacityUnavailable("Codex weekly limit")
        with mock.patch.dict(cycle.STEPS, {"ci": exhausted}):
            self.post("/cycle/step", {"callback_url": self.callback})
            self.wait_for(lambda: _CallbackReceiver.received)
        payload = _CallbackReceiver.received[0][1]
        self.assertEqual((payload["status"], payload["outcome"], payload["proceed"], payload["phase"]),
                         ("completed", "model_capacity_exhausted", False, "ci"))

    def test_invalid_budget_settings_are_rejected_before_any_step_starts(self):
        with self.assertRaises(HTTPError) as rejected:
            self.post("/cycle/step", {"callback_url": self.callback, "budget": {"mode": "sometimes"}})
        self.assertEqual(rejected.exception.code, 400)
        self.assertIsNone(self.bridge.store.running())

    def test_budget_endpoint_reports_the_cap_in_force(self):
        usage = ("Current session: 10% used\n"
                 "Current week (all models): 20% used · resets Oct 10, 7am (Europe/Berlin)\n")
        with mock.patch.object(bridge.claude_budget, "read_claude_usage_text", return_value=usage):
            status, body = self.post("/budget", {"budget": {"mode": "manual", "manual_cap_percent": 15}})
        self.assertEqual((status, body["weekly_cap_percent"], body["claude_allowed"]), (200, 15, False))

    def test_connection_test_task_runs_the_agent_read_only_and_reports_back(self):
        self.bridge.claude_command = ("python", "-c", "import sys; print('pong', sys.argv[1:4])")
        status, task = self.post("/tasks", {"prompt": "ping", "callback_url": self.callback})
        self.assertEqual((status, task["kind"]), (202, "agent"))
        self.wait_for(lambda: _CallbackReceiver.received)
        payload = _CallbackReceiver.received[0][1]
        self.assertEqual(payload["status"], "completed")
        self.assertIn("--permission-mode', 'plan'", payload["result"])


if __name__ == "__main__":
    unittest.main()
