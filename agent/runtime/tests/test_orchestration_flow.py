"""
End-to-end control-flow tests for core/orchestrator.py's main loop.

Every model process, sleep and capacity probe is replaced: no test here
consumes real Claude or Codex capacity, sleeps, writes to the committed
agent/logs/, or touches the repository.

The property under test throughout is the one the workflow depends on:
once started, the orchestrator keeps progressing through execution,
evaluation, planning, capacity waiting and resumption, and only stops
when there is genuinely no permissible work.

Run with: python -m unittest agent.runtime.tests.test_orchestration_flow -v
(from the repository root).
"""

import unittest
from unittest.mock import Mock, patch

from agent.runtime.core import orchestrator


class LoopStopped(BaseException):
    """
    Breaks out of main()'s deliberately infinite loop from inside a
    patched time.sleep. Derived from BaseException, not Exception, so
    main()'s recoverable-failure guard cannot swallow it and disguise a
    test's exit as an orchestration error.
    """


class MainFlowTestCase(unittest.TestCase):
    """
    One scripted repository state (`self.state`) plus a scheduler whose
    two capacity probes are independently controllable, wired into
    main() through the same module-level functions it really calls.
    """

    def setUp(self):
        self.events = []
        self.state = {"fingerprint": "a", "candidates": [], "active": False}
        self.logged = []
        self.status = []

        self.claude = Mock()
        self.codex = Mock()

        for probe in (self.claude, self.codex):
            probe.available.return_value = True
            # Mirror the real CapacityProbe: a deferred probe reports
            # unavailable until its local cooldown expires.
            probe.defer.side_effect = (
                lambda captured=probe: setattr(
                    captured.available, "return_value", False
                )
            )

        self.scheduler = orchestrator.CapacityScheduler(self.claude, self.codex, cache_file=None)

        self.planning_result = {"status": "COMPLETE", "story_files_created": []}
        self.decisions = []
        self.architect_requests = []
        self.undispatchable_requests = []
        self.architect_result = {
            "status": "COMPLETE",
            "request_status": "RESOLVED",
            "user_decision_ids": [],
        }

        patches = [
            patch.object(orchestrator, "CapacityScheduler", return_value=self.scheduler),
            patch.object(orchestrator, "requeue_resolved_interventions"),
            patch.object(orchestrator, "get_actionable_architect_requests",
                         side_effect=lambda: list(self.architect_requests)),
            patch.object(orchestrator, "undispatchable_architect_requests",
                         side_effect=lambda: list(self.undispatchable_requests)),
            patch.object(orchestrator, "_active_is_executable",
                         side_effect=lambda: self.state["active"]),
            patch.object(orchestrator, "get_selectable_story_candidates",
                         side_effect=lambda: list(self.state["candidates"])),
            patch.object(orchestrator, "planning_input_snapshot",
                         side_effect=lambda: {"fixture": self.state["fingerprint"]}),
            patch.object(orchestrator, "planning_fingerprint",
                         side_effect=lambda snapshot=None: self.state["fingerprint"]),
            patch.object(orchestrator, "execute_active_story", side_effect=self.execute),
            patch.object(orchestrator, "_select_and_activate_next_story",
                         side_effect=self.select),
            patch.object(orchestrator, "run_planning_pass", side_effect=self.plan),
            patch.object(orchestrator, "run_architect_pass", side_effect=self.architect),
            patch.object(orchestrator, "list_decisions", side_effect=lambda: list(self.decisions)),
            patch.object(orchestrator, "wait_for_user_decisions", side_effect=self.wait_decision),
            patch.object(orchestrator, "log_line", side_effect=self.logged.append),
            patch.object(orchestrator, "print_status", side_effect=self.status.append),
            patch.object(orchestrator.time, "sleep", side_effect=self.sleep),
        ]

        for item in patches:
            item.start()
            self.addCleanup(item.stop)

        self.max_waits = 1

    # -- scripted collaborators --------------------------------------

    def execute(self, before_attempt, on_interruption, wait_for_evaluator=None):
        before_attempt()
        self.events.append("claude")
        self.state["active"] = False
        return "COMPLETE"

    def select(self):
        self.events.append("select")

        if not self.state["candidates"]:
            return "FINISHED"

        self.state["candidates"].pop(0)
        self.state["active"] = True
        return "ACTIVATED"

    def plan(self):
        self.events.append("plan")
        return self.planning_result

    def architect(self, request):
        self.events.append(f"architect:{request['file']}")
        # A finished pass always moves the request out of the actionable
        # set -- RESOLVED, or NEEDS_USER behind a decision.
        self.architect_requests = [
            item for item in self.architect_requests
            if item["file"] != request["file"]
        ]
        return self.architect_result

    def wait_decision(self, ids):
        self.events.append(f"decision-wait:{sorted(ids)}")
        self.decisions = [dict(item, status="RESOLVED") for item in self.decisions]
        # Answering a decision edits its file, so the planning
        # fingerprint really does change and planning is reconsidered.
        self.state["fingerprint"] += "+"

    def sleep(self, seconds):
        self.events.append("wait")

        if self.events.count("wait") >= self.max_waits:
            raise LoopStopped

    def run_main(self, expect_stop=False):
        if expect_stop:
            orchestrator.main()
            return

        with self.assertRaises(LoopStopped):
            orchestrator.main()

    def decisions_logged(self):
        return [line for line in self.logged if line.startswith("Decision:")]


class EvaluatorRetryTests(unittest.TestCase):
    def test_timeout_retries_and_then_returns_evaluation(self):
        result = {"decision": "COMPLETE", "reason": "ok"}
        with patch.object(orchestrator, "evaluate_story",
                          side_effect=[TimeoutError("slow"), result]), \
             patch.object(orchestrator, "EVALUATION_ATTEMPTS", 2), \
             patch.object(orchestrator, "EVALUATION_RETRY_SECONDS", 0), \
             patch.object(orchestrator.time, "sleep"), \
             patch.object(orchestrator, "log_line") as log:
            actual = orchestrator._evaluate_with_local_retries("story", "result", 0, True)
        self.assertEqual(actual, result)
        self.assertTrue(any("Evaluation attempt 1/2 failed" in c.args[0]
                            for c in log.call_args_list))

    def test_retry_exhaustion_keeps_evaluation_local_without_reinvoking_claude(self):
        class RetryLater(BaseException):
            pass

        claude = Mock()
        with patch.object(orchestrator, "evaluate_story",
                          side_effect=TimeoutError("slow")) as evaluate, \
             patch.object(orchestrator, "EVALUATION_ATTEMPTS", 2), \
             patch.object(orchestrator, "EVALUATION_RETRY_SECONDS", 0), \
             patch.object(orchestrator.time, "sleep", side_effect=[None, RetryLater]), \
             patch.object(orchestrator, "print_status") as status:
            with self.assertRaises(RetryLater):
                orchestrator._evaluate_preserving_completed_attempt(
                    "story", "completed result", 0, True
                )
        self.assertEqual(evaluate.call_count, 2)
        claude.assert_not_called()
        self.assertIn("completed Claude work is preserved", status.call_args.args[0])


class IdlePlanningTriggerTests(unittest.TestCase):
    def make_scheduler(self, snapshot, result):
        scheduler = orchestrator.CapacityScheduler(Mock(), Mock(), cache_file=None)
        scheduler.codex_available = Mock(return_value=True)
        scheduler._show_codex_capacity = Mock()
        return scheduler

    def test_unchanged_no_work_result_suppresses_idle_call(self):
        scheduler = self.make_scheduler({"po": "a"},
                                        {"status": "COMPLETE", "independent_work_remaining": False})
        with patch.object(orchestrator, "planning_input_snapshot", return_value={"po": "a"}), \
             patch.object(orchestrator, "planning_fingerprint", return_value="same"), \
             patch.object(orchestrator, "get_selectable_story_candidates", return_value=["queued"]), \
             patch.object(orchestrator, "should_trigger_planning", return_value=False), \
             patch.object(orchestrator, "run_planning_pass") as run, \
             patch.object(orchestrator, "log_line") as log:
            scheduler._hold_planning({"status": "COMPLETE", "independent_work_remaining": False},
                                     {"po": "a"}, "same")
            self.assertFalse(scheduler.plan_if_useful(idle=True))
        run.assert_not_called()
        self.assertTrue(any("no planning inputs changed" in c.args[0]
                            for c in log.call_args_list))

    def test_independent_work_remaining_allows_bounded_followup(self):
        scheduler = self.make_scheduler({"po": "a"},
                                        {"status": "COMPLETE", "independent_work_remaining": True})
        with patch.object(orchestrator, "planning_input_snapshot", return_value={"po": "a"}), \
             patch.object(orchestrator, "planning_fingerprint", return_value="same"), \
             patch.object(orchestrator, "get_selectable_story_candidates", return_value=["queued"]), \
             patch.object(orchestrator, "should_trigger_planning", return_value=False), \
             patch.object(orchestrator, "run_planning_pass", return_value={
                 "status": "COMPLETE", "independent_work_remaining": True,
                 "story_files_created": []}):
            scheduler._hold_planning({"status": "COMPLETE", "independent_work_remaining": True},
                                     {"po": "a"}, "same")
            self.assertFalse(scheduler.plan_if_useful(idle=True))

    def test_changed_po_ud_ar_or_story_completion_input_triggers_with_stocked_queue(self):
        for key in ("product_owner_request", "user_decision", "architect_request",
                    "claude_result_story_completion"):
            with self.subTest(input=key):
                scheduler = self.make_scheduler({key: "new"},
                                                {"status": "COMPLETE", "independent_work_remaining": False})
                scheduler._hold_planning({"status": "COMPLETE", "independent_work_remaining": False},
                                         {key: "old"}, "old")
                with patch.object(orchestrator, "planning_input_snapshot", return_value={key: "new"}), \
                     patch.object(orchestrator, "planning_fingerprint", return_value="new"), \
                     patch.object(orchestrator, "get_selectable_story_candidates", return_value=["queued"]), \
                     patch.object(orchestrator, "should_trigger_planning", return_value=False), \
                     patch.object(orchestrator, "run_planning_pass", return_value={
                         "status": "COMPLETE", "independent_work_remaining": False,
                         "story_files_created": []}) as run:
                    scheduler.plan_if_useful(idle=False)
                run.assert_called_once()

    def test_planner_self_changes_are_snapshotted_after_pass(self):
        scheduler = orchestrator.CapacityScheduler(Mock(), Mock(), cache_file=None)
        scheduler.codex_available = Mock(return_value=True)
        snapshots = iter([{"source": "before"}, {"source": "before", "planner_story": "created"}])
        with patch.object(orchestrator, "planning_input_snapshot", side_effect=lambda: next(snapshots)), \
             patch.object(orchestrator, "planning_fingerprint", side_effect=lambda s=None: "post" if s and "planner_story" in s else "pre"), \
             patch.object(orchestrator, "get_selectable_story_candidates", return_value=[]), \
             patch.object(orchestrator, "should_trigger_planning", return_value=True), \
             patch.object(orchestrator, "run_planning_pass", return_value={
                 "status": "COMPLETE", "independent_work_remaining": False,
                 "story_files_created": ["new.md"]}):
            self.assertTrue(scheduler.plan_if_useful())
            self.assertEqual(scheduler.no_work_at, "post")

    def test_stocked_queue_and_unchanged_inputs_do_not_idle_replan(self):
        scheduler = orchestrator.CapacityScheduler(Mock(), Mock(), cache_file=None)
        scheduler.codex_available = Mock(return_value=True)
        scheduler._hold_planning({"status": "COMPLETE", "independent_work_remaining": False},
                                 {"stable": "same"}, "stable")
        with patch.object(orchestrator, "planning_input_snapshot", return_value={"stable": "same"}), \
             patch.object(orchestrator, "planning_fingerprint", return_value="stable"), \
             patch.object(orchestrator, "get_selectable_story_candidates", return_value=["queued", "queued-2"]), \
             patch.object(orchestrator, "should_trigger_planning", return_value=False), \
             patch.object(orchestrator, "run_planning_pass") as run:
            self.assertFalse(scheduler.plan_if_useful(idle=True))
        run.assert_not_called()


class ClaudeWithWorkTest(MainFlowTestCase):

    def test_available_claude_selects_executes_and_continues_automatically(self):
        # A completed story must lead straight to the next operation --
        # replenishment check, then selection -- with no restart.
        self.state["candidates"] = ["STORY-A.md", "STORY-B.md"]

        self.run_main(expect_stop=True)

        self.assertEqual(
            self.events,
            # The second replenishment attempt is suppressed because no
            # local planning input changed since the first one found
            # nothing useful to add.
            ["select", "claude", "plan", "select", "claude", "select"],
        )


class ClaudeExhaustedTest(MainFlowTestCase):

    def test_exhausted_claude_lets_codex_keep_planning_and_waits_locally(self):
        self.claude.available.return_value = False
        self.planning_result = {"status": "COMPLETE",
                                "story_files_created": ["STORY-NEW.md"]}

        def plan():
            self.events.append("plan")

            if self.state["candidates"]:
                # Nothing further worth creating this pass.
                return {"status": "COMPLETE", "story_files_created": []}

            # A useful pass genuinely changes local planning state.
            self.state["fingerprint"] += "+"
            self.state["candidates"] = ["STORY-NEW.md"]
            return self.planning_result

        with patch.object(orchestrator, "run_planning_pass", side_effect=plan):
            self.run_main()

        self.assertIn("plan", self.events)
        # Claude is never invoked while it has no capacity, and planned
        # work is left in the queue rather than the orchestrator exiting.
        self.assertNotIn("claude", self.events)
        self.assertEqual(self.state["candidates"], ["STORY-NEW.md"])
        self.assertIn("Claude capacity exhausted (first detected).", self.logged)


class CodexExhaustedTest(MainFlowTestCase):

    def test_exhausted_codex_never_blocks_claude_draining_the_queue(self):
        self.codex.available.return_value = False
        self.state["candidates"] = ["STORY-A.md"]

        self.run_main()

        # The planner is never invoked, the story still runs to
        # completion, and the empty queue leads to local waiting rather
        # than a stop.
        self.assertEqual(self.events, ["select", "claude", "wait"])
        self.assertIn("Codex capacity exhausted (first detected).", self.logged)


class BothExhaustedTest(MainFlowTestCase):

    def test_both_exhausted_waits_then_resumes_when_claude_recovers(self):
        self.claude.available.return_value = False
        self.codex.available.return_value = False
        self.state["active"] = True
        self.max_waits = 3

        def sleep(seconds):
            self.events.append("wait")

            if self.events.count("wait") == 2:
                # Capacity returns while the orchestrator is waiting.
                self.claude.available.return_value = True

            if self.events.count("wait") >= self.max_waits:
                raise LoopStopped

        with patch.object(orchestrator.time, "sleep", side_effect=sleep):
            self.run_main()

        # Waited locally without invoking either model, then resumed the
        # active story by itself once Claude came back.
        self.assertEqual(self.events, ["wait", "wait", "claude", "wait"])
        self.assertNotIn("plan", self.events)
        self.assertIn("Claude capacity available again.", self.logged)


class PlannerReplenishmentTest(MainFlowTestCase):

    def test_planner_created_work_executes_without_a_restart(self):
        created = ["STORY-NEW.md"]

        def plan():
            self.events.append("plan")

            if created:
                self.state["fingerprint"] += "+"
                self.state["candidates"] = [created.pop()]
                return {"status": "COMPLETE", "story_files_created": ["STORY-NEW.md"]}

            return {"status": "COMPLETE", "story_files_created": []}

        with patch.object(orchestrator, "run_planning_pass", side_effect=plan):
            self.run_main(expect_stop=True)

        self.assertEqual(
            self.events, ["plan", "select", "claude", "select"]
        )


class PlanningFailureTest(MainFlowTestCase):

    def test_failed_planning_pass_does_not_stop_execution(self):
        # A planner failure is not a reason to abandon planned work.
        self.state["candidates"] = ["STORY-A.md"]
        self.planning_result = {"status": "FAILED", "reason": "validation problem"}

        self.run_main()

        self.assertEqual(self.events, ["select", "claude", "plan", "wait"])
        self.codex.defer.assert_not_called()
        self.assertTrue(
            any("Project planning FAILED" in line for line in self.logged),
            self.logged,
        )

    def test_planner_exception_is_not_fatal(self):
        self.state["candidates"] = ["STORY-A.md"]

        with patch.object(orchestrator, "run_planning_pass",
                          side_effect=RuntimeError("planner modified protected state")):
            self.run_main()

        self.assertEqual(self.events, ["select", "claude", "wait"])
        self.codex.defer.assert_not_called()


class UserDecisionGateTest(MainFlowTestCase):

    def test_independent_work_executes_while_user_decision_stays_open(self):
        self.decisions = [{"id": "UD-010", "status": "OPEN", "file": "UD-010-choice.md"}]
        def plan():
            self.events.append("plan")
            self.state["fingerprint"] = "independent story added"
            self.state["candidates"] = ["STORY-INDEPENDENT.md"]
            return {"status": "NEEDS_USER", "user_decision_ids": ["UD-010"],
                    "story_files_created": ["STORY-INDEPENDENT.md"]}
        with patch.object(orchestrator, "run_planning_pass", side_effect=plan) as model:
            decisions = orchestrator.DecisionLog()
            replenish = False
            for _ in range(3):
                outcome, replenish = orchestrator._run_cycle(self.scheduler, replenish, decisions)
                self.assertEqual(outcome, "CONTINUE")
            model.assert_called_once()
        self.assertEqual(self.events, ["plan", "select", "claude"])
        self.assertEqual(self.decisions[0]["status"], "OPEN")

    def test_open_user_decision_blocks_instead_of_being_bypassed(self):
        self.decisions = [{"id": "UD-006", "status": "OPEN", "file": "UD-006.md"}]
        self.planning_result = {"status": "NEEDS_USER", "user_decision_ids": ["UD-006"]}

        self.run_main(expect_stop=True)

        # No story is invented around the open decision: the
        # orchestrator plans, waits for the human, re-plans with the
        # answer, and only then concludes there is nothing to run.
        self.assertEqual(
            self.events,
            ["plan", "decision-wait:['UD-006']", "plan", "select"],
        )
        self.assertNotIn("claude", self.events)


class WaitLoggingTest(MainFlowTestCase):

    def test_repeated_waiting_logs_one_transition_and_stays_on_the_terminal(self):
        self.claude.available.return_value = False
        self.codex.available.return_value = False
        self.max_waits = 4

        self.run_main()

        self.assertEqual(self.events.count("wait"), 4)
        # Four identical polling cycles, one persistent entry.
        self.assertEqual(
            len([line for line in self.decisions_logged() if "wait" in line]), 1
        )
        self.assertEqual(len(self.logged), len(set(self.logged)), self.logged)
        self.assertTrue(
            any("capacity unavailable - waiting..." in line for line in self.status),
            self.status,
        )
        self.assertFalse(
            any("waiting..." in line for line in self.logged), self.logged
        )


class CapacityStatusTest(unittest.TestCase):

    def test_status_lines_report_usage_and_next_check_without_probing(self):
        claude = orchestrator.CapacityProbe(Mock(return_value=False),
                                            recheck_seconds=1800, clock=lambda: 0)
        scheduler = orchestrator.CapacityScheduler(claude, Mock(), cache_file=None)
        scheduler.claude_usage_percent = 97
        scheduler.claude_weekly_used_percent = 20
        claude.defer()

        lines = scheduler.status_lines(["Claude"])

        self.assertIn("Claude capacity unavailable - waiting...", lines[0])
        self.assertTrue(any("Current usage: Claude session 97%" in line
                            for line in lines), lines)
        self.assertTrue(any("Next Claude capacity check in 30 min" in line
                            for line in lines), lines)


class StalePointerTest(unittest.TestCase):

    def test_unusable_current_story_pointer_does_not_strand_execution(self):
        logged = []

        with patch.object(orchestrator, "CURRENT_STORY_FILE") as pointer, \
             patch.object(orchestrator, "read_file", return_value="agent/stories/gone.md"), \
             patch.object(orchestrator, "get_active_story_path",
                          side_effect=FileNotFoundError("Story does not exist")), \
             patch.object(orchestrator, "log_line", side_effect=logged.append):
            pointer.exists.return_value = True
            orchestrator._reported_pointer_problem = None

            self.assertFalse(orchestrator._active_is_executable())
            # Repeated cycles must not re-log the same problem.
            self.assertFalse(orchestrator._active_is_executable())

        self.assertEqual(len(logged), 1, logged)
        self.assertIn("CURRENT_STORY.md", logged[0])


class CycleErrorGuardTest(MainFlowTestCase):

    def test_transient_cycle_failure_is_retried_then_escalated(self):
        failures = []

        def execute(before_attempt, on_interruption, wait_for_evaluator=None):
            failures.append(1)
            self.state["candidates"] = ["STORY-A.md"]
            raise OSError("evaluator unreachable")

        self.state["candidates"] = ["STORY-A.md"]
        self.state["active"] = True

        with patch.object(orchestrator, "execute_active_story", side_effect=execute), \
             patch.object(orchestrator.time, "sleep"):
            orchestrator.main()

        # Retried locally rather than dying on the first failure, and
        # stopped explicitly instead of looping forever.
        self.assertEqual(len(failures), orchestrator.MAX_CONSECUTIVE_CYCLE_ERRORS)
        self.assertTrue(
            any("human action required" in line for line in self.logged),
            self.logged,
        )


class EvaluationRetryTest(unittest.TestCase):

    def test_evaluator_outage_is_retried_locally_without_re_invoking_claude(self):
        calls = []

        def evaluate(*args, **_kwargs):
            calls.append(args)

            if len(calls) < 3:
                raise OSError("connection refused")

            return {"decision": "COMPLETE", "reason": "ok"}

        # The attempt count is stated here rather than inherited: the
        # production value is tuned for real Codex runs, and this test is
        # about "retried more than once, then succeeded".
        with patch.object(orchestrator, "evaluate_story", side_effect=evaluate), \
             patch.object(orchestrator, "EVALUATION_ATTEMPTS", 3), \
             patch.object(orchestrator, "print_status"), \
             patch.object(orchestrator.time, "sleep") as sleep:
            result = orchestrator._evaluate_with_local_retries("story", "result", 0, True)

        self.assertEqual(result["decision"], "COMPLETE")
        self.assertEqual(len(calls), 3)
        self.assertEqual(sleep.call_count, 2)


class UserDecisionWaitTest(unittest.TestCase):

    def test_wait_returns_as_soon_as_any_blocking_decision_resolves(self):
        # Observed in a real run: UD-007 resolved at 18:32 but the
        # orchestrator kept waiting for UD-006, so nothing was
        # re-derived for another hour.
        states = [
            [{"id": "UD-006", "status": "OPEN", "file": "UD-006.md"},
             {"id": "UD-007", "status": "OPEN", "file": "UD-007.md"}],
            [{"id": "UD-006", "status": "OPEN", "file": "UD-006.md"}],
        ]
        logged = []
        status = []

        with patch.object(orchestrator, "get_unresolved_user_decisions",
                          side_effect=states), \
             patch.object(orchestrator, "log_line", side_effect=logged.append), \
             patch.object(orchestrator, "print_status", side_effect=status.append), \
             patch.object(orchestrator.time, "sleep") as sleep:
            orchestrator.wait_for_user_decisions(["UD-006", "UD-007"])

        self.assertEqual(sleep.call_count, 1)
        self.assertTrue(any("UD-007" in line and "resolved" in line
                            for line in logged), logged)
        # Heartbeat output never becomes a persistent log entry.
        self.assertTrue(any("Still unresolved" in line for line in status), status)
        self.assertFalse(any("Still unresolved" in line for line in logged), logged)


if __name__ == "__main__":
    unittest.main()
