"""Offline scheduling tests: no model processes, sleeps, or repository writes."""
import unittest
from unittest.mock import Mock, patch
from agent.runtime.core import orchestrator
from agent.runtime.runners import claude_runner
from agent.runtime.support.capacity import CapacityProbe, ModelCapacityUnavailable


class CapacityProbeTest(unittest.TestCase):
    def test_exhaustion_is_cached_until_local_recheck(self):
        clock = Mock(return_value=0)
        read = Mock(side_effect=[False, True])
        gate = CapacityProbe(read, recheck_seconds=100, clock=clock)
        self.assertFalse(gate.available())
        self.assertFalse(gate.available())
        self.assertEqual(read.call_count, 1)
        clock.return_value = 100
        self.assertTrue(gate.available())

    def test_failed_probe_does_not_invoke_model_or_claim_capacity(self):
        gate = CapacityProbe(Mock(side_effect=RuntimeError("offline")))
        self.assertFalse(gate.available())


class SchedulerTest(unittest.TestCase):
    def setUp(self):
        self.claude = Mock()
        self.codex = Mock()
        self.scheduler = orchestrator.CapacityScheduler(self.claude, self.codex, cache_file=None)
        self.claude.available.return_value = True
        self.codex.available.return_value = True
        # Transition logging is exercised separately; no test may write
        # to the real, committed agent/logs/.
        self.logged = []
        for patcher in (
            patch.object(orchestrator, "log_line", side_effect=self.logged.append),
            # Never dispatch the real architect from a scheduling test.
            patch.object(orchestrator, "get_actionable_architect_requests",
                         return_value=[]),
        ):
            patcher.start()
            self.addCleanup(patcher.stop)

    def test_codex_unavailable_does_not_delay_claude(self):
        self.codex.available.return_value = False
        with patch.object(orchestrator, "run_planning_pass") as plan:
            self.scheduler.wait_for_claude()
            plan.assert_not_called()

    def test_pre_run_gate_waits_for_session_and_weekly_capacity(self):
        now = [0]
        readings = iter([
            claude_runner.ClaudeUsage(90, 20),
            claude_runner.ClaudeUsage(50, 98),
            claude_runner.ClaudeUsage(50, 49),
        ])
        scheduler = orchestrator.CapacityScheduler(codex=self.codex, cache_file=None)
        scheduler.claude = CapacityProbe(scheduler._read_claude_capacity,
                                          recheck_seconds=60, clock=lambda: now[0])
        with patch.object(orchestrator, "get_claude_usage", side_effect=readings) as usage, \
             patch.object(scheduler, "codex_work_if_useful", return_value=False), \
             patch.object(scheduler, "wait_locally", side_effect=lambda _: now.__setitem__(0, now[0] + 60)) as wait:
            scheduler.wait_for_claude()
        self.assertEqual(usage.call_count, 3)
        self.assertEqual(wait.call_count, 2)
        self.assertEqual(scheduler.claude_usage_percent, 50)
        self.assertEqual(scheduler.claude_weekly_used_percent, 49)
        self.assertEqual(self.logged.count("Claude capacity exhausted (first detected)."), 1)
        self.assertEqual(self.logged.count("Claude capacity available again."), 1)

    def test_idle_claude_plans_only_for_exhausted_queue_and_bounded_followup(self):
        self.claude.available.side_effect = [False, False, False, True]
        with patch.object(orchestrator, "planning_fingerprint", side_effect=["a", "b", "c", "c", "c", "c"]), \
             patch.object(orchestrator, "get_selectable_story_candidates", return_value=[]), \
             patch.object(orchestrator, "run_planning_pass", side_effect=[
                 {"status": "COMPLETE", "story_files_created": ["new.md"],
                  "independent_work_remaining": True},
                 {"status": "COMPLETE", "story_files_created": [],
                  "independent_work_remaining": False},
             ]) as plan, patch.object(orchestrator.time, "sleep"):
            self.scheduler.wait_for_claude()
            self.assertEqual(plan.call_count, 2)

    def test_two_ready_stories_do_not_trigger_replenishment(self):
        with patch.object(orchestrator, "get_selectable_story_candidates", return_value=[1, 2]), \
             patch.object(orchestrator, "planning_fingerprint", return_value="a"), \
             patch.object(orchestrator, "run_planning_pass", return_value={"status": "COMPLETE"}) as plan:
            self.scheduler.plan_if_useful()
            plan.assert_not_called()

    def test_both_unavailable_only_waits_locally(self):
        self.claude.available.side_effect = [False, False, True]
        self.codex.available.return_value = False
        with patch.object(orchestrator, "run_planning_pass") as plan, \
             patch.object(orchestrator.time, "sleep") as sleep:
            self.scheduler.wait_for_claude()
            self.assertEqual(sleep.call_count, 2)
            plan.assert_not_called()

    def test_capacity_interruption_is_not_planning_failure(self):
        with patch.object(orchestrator, "get_selectable_story_candidates", return_value=[]), \
             patch.object(orchestrator, "planning_fingerprint", return_value="a"), \
             patch.object(orchestrator, "run_planning_pass", side_effect=ModelCapacityUnavailable("Codex")):
            self.scheduler.plan_if_useful()
            self.codex.defer.assert_called_once()


class MainLoopTest(unittest.TestCase):
    def test_active_resume_queue_drains_to_zero_then_waits_for_codex(self):
        events = []
        active = [True]
        pending = ["second"]
        claude = Mock()
        claude.available.return_value = True
        codex = Mock()
        codex.available.return_value = False
        scheduler = orchestrator.CapacityScheduler(claude, codex, cache_file=None)
        writing = [False]

        def execute(before, interrupted, wait_for_evaluator=None):
            self.assertFalse(writing[0])
            before()
            writing[0] = True
            events.append("claude")
            active[0] = False
            writing[0] = False
            return "COMPLETE"

        def select():
            self.assertFalse(writing[0])
            events.append("select")
            if pending:
                pending.pop()
                active[0] = True
                return "ACTIVATED"
            return "FINISHED"

        def wait(seconds):
            self.assertEqual(events.count("claude"), 2)
            self.assertNotIn("plan", events)
            self.assertFalse(writing[0])
            events.append("wait")
            codex.available.return_value = True

        def plan():
            self.assertFalse(writing[0])
            self.assertFalse(active[0])
            events.append("plan")
            return {"status": "COMPLETE", "story_files_created": []}

        with patch.object(orchestrator, "CapacityScheduler", return_value=scheduler), \
             patch.object(orchestrator, "get_actionable_architect_requests", return_value=[]), \
             patch.object(orchestrator, "undispatchable_architect_requests", return_value=[]), \
             patch.object(orchestrator, "requeue_resolved_interventions"), \
             patch.object(orchestrator, "_active_is_executable", side_effect=lambda: active[0]), \
             patch.object(orchestrator, "get_selectable_story_candidates", side_effect=lambda: pending[:]), \
             patch.object(orchestrator, "execute_active_story", side_effect=execute), \
             patch.object(orchestrator, "_select_and_activate_next_story", side_effect=select), \
             patch.object(orchestrator, "run_planning_pass", side_effect=plan), \
             patch.object(orchestrator, "planning_fingerprint", return_value="state"), \
             patch.object(orchestrator, "list_decisions", return_value=[]), \
             patch.object(orchestrator, "log_line"), \
             patch.object(orchestrator.time, "sleep", side_effect=wait):
            orchestrator.main()
        self.assertEqual(events, ["claude", "select", "claude", "wait", "plan", "select"])


from agent.runtime.tests.test_orchestrator import OrchestratorInterventionTestCase, story_with_id


class CooperativeResumeTest(OrchestratorInterventionTestCase):
    def test_interrupted_story_plans_then_resumes_same_prompt_without_evaluation(self):
        story = self.write_story("STORY-DOM-099-test.md", story_with_id(
            "STORY-DOM-099", "## Status\n\nTODO\n"))
        self.write_backlog(active=[story.name])
        self.set_active(story.name)
        pointer = self.current_story_file.read_bytes()
        claude, codex = Mock(), Mock()
        # First value is consumed by execute_active_story()'s new
        # pre-repo-map capacity confirmation (Claude ready, so the map/
        # prompt build proceeds); the remaining four are the original
        # in-retry-loop sequence: ready -> interrupted -> down (plans)
        # -> still down (fingerprint-suppressed, no replan) -> ready again.
        claude.available.side_effect = [True, True, False, False, True]
        codex.available.return_value = True
        scheduler = orchestrator.CapacityScheduler(claude, codex, cache_file=None)
        events = []
        prompts = []

        def run(prompt):
            prompts.append(prompt)
            events.append("claude")
            # First attempt is cut short by capacity exhaustion.
            if len(prompts) == 1:
                return claude_runner.ClaudeAttempt(1, True)
            return claude_runner.ClaudeAttempt(0, False)

        def plan():
            self.assertEqual(events, ["claude"])
            self.assertEqual(self.current_story_file.read_bytes(), pointer)
            self.assertIn("UNFINISHED", story.read_text())
            events.append("plan")
            return {"status": "NEEDS_USER", "user_decision_ids": ["UD-010"], "story_files_created": []}

        with patch.object(orchestrator, "build_claude_prompt", return_value="Implement active"), \
             patch.object(orchestrator, "NEXT_PROMPT_FILE", self.stories_dir / "prompt.md"), \
             patch.object(orchestrator, "CLAUDE_RESULT_FILE", self.stories_dir / "result.md"), \
             patch.object(orchestrator, "generate_repo_map", return_value={
                 "enabled":False,"text":"","token_budget":0,"error":None}), \
             patch.object(orchestrator, "_safe_claude_usage", return_value=claude_runner.ClaudeUsage(0, 0)), \
             patch.object(orchestrator, "run_claude_attempt", side_effect=run), \
             patch.object(orchestrator, "run_planning_pass", side_effect=plan), \
             patch.object(orchestrator, "planning_fingerprint", return_value="state"), \
             patch.object(orchestrator, "evaluate_story", return_value={"decision":"COMPLETE","reason":"ok"}) as evaluate, \
             patch.object(orchestrator.time, "sleep"):
            self.assertEqual(orchestrator.execute_active_story(
                scheduler.wait_for_claude, claude.defer), "COMPLETE")
        self.assertEqual(events, ["claude", "plan", "claude"])
        self.assertIn("resume where it stopped", prompts[1])
        self.assertTrue(prompts[1].endswith(prompts[0]))
        evaluate.assert_called_once()
        claude.defer.assert_called_once()


class RepoMapOrderingTest(OrchestratorInterventionTestCase):
    """
    Phase 3 ordering: RepoMap must never be generated speculatively --
    only once Claude's capacity has actually been confirmed (waiting
    locally, using idle Codex planning meanwhile, if it wasn't ready).
    """

    def test_repo_map_generated_after_capacity_wait_not_before(self):
        story = self.write_story(
            "STORY-DOM-100-test.md",
            story_with_id("STORY-DOM-100", "## Status\n\nTODO\n"),
        )
        self.write_backlog(active=[story.name])
        self.set_active(story.name)

        events = []
        claude, codex = Mock(), Mock()
        # 1st call: not ready (triggers idle planning); 2nd: ready
        # (pre-map check succeeds); 3rd: ready again (retry-loop's own
        # per-attempt check).
        claude.available.side_effect = [False, True, True]
        codex.available.return_value = True
        scheduler = orchestrator.CapacityScheduler(claude, codex, cache_file=None)

        def fake_generate_repo_map(*args, **kwargs):
            events.append("repo_map")
            return {
                "enabled": False, "available": False, "text": "",
                "token_budget": 1200, "char_count": 0, "approx_tokens": 0,
                "duration_seconds": 0.0, "error": None,
            }

        def fake_plan():
            events.append("plan")
            return {"status": "COMPLETE", "story_files_created": []}

        def fake_run(prompt):
            events.append("claude")
            return claude_runner.ClaudeAttempt(0, False)

        with patch.object(orchestrator, "build_claude_prompt", return_value="Implement"), \
             patch.object(orchestrator, "NEXT_PROMPT_FILE", self.stories_dir / "prompt.md"), \
             patch.object(orchestrator, "CLAUDE_RESULT_FILE", self.stories_dir / "result.md"), \
             patch.object(orchestrator, "generate_repo_map", side_effect=fake_generate_repo_map), \
             patch.object(orchestrator, "_safe_claude_usage", return_value=claude_runner.ClaudeUsage(0, 0)), \
             patch.object(orchestrator, "run_claude_attempt", side_effect=fake_run), \
             patch.object(orchestrator, "run_planning_pass", side_effect=fake_plan), \
             patch.object(orchestrator, "planning_fingerprint", return_value="state"), \
             patch.object(orchestrator, "get_selectable_story_candidates", return_value=list(range(6))), \
             patch.object(orchestrator, "evaluate_story",
                           return_value={"decision": "COMPLETE", "reason": "ok"}), \
             patch.object(orchestrator.time, "sleep"):
            result = orchestrator.execute_active_story(
                scheduler.wait_for_claude, scheduler.claude.defer
            )

        self.assertEqual(result, "COMPLETE")
        # Eligible work means there is no reason to run a planning pass while
        # Claude's capacity recovers; repo-map preparation follows the wait.
        self.assertEqual(events, ["repo_map", "claude"])


class MainCycleLoggingTest(unittest.TestCase):
    """Phase 3 steps 1-2: every cycle logs the raw availability check
    and the resulting decision, before acting on it."""

    def test_availability_and_decision_are_both_logged(self):
        claude, codex = Mock(), Mock()
        claude.available.return_value = True
        codex.available.return_value = True
        scheduler = orchestrator.CapacityScheduler(claude, codex, cache_file=None)
        logged = []

        def fake_select():
            return "FINISHED"

        with patch.object(orchestrator, "CapacityScheduler", return_value=scheduler), \
             patch.object(orchestrator, "get_actionable_architect_requests", return_value=[]), \
             patch.object(orchestrator, "undispatchable_architect_requests", return_value=[]), \
             patch.object(orchestrator, "requeue_resolved_interventions"), \
             patch.object(orchestrator, "_active_is_executable", return_value=False), \
             patch.object(orchestrator, "get_selectable_story_candidates", return_value=[]), \
             patch.object(orchestrator, "run_planning_pass",
                           return_value={"status": "COMPLETE", "story_files_created": []}), \
             patch.object(orchestrator, "planning_fingerprint", return_value="state"), \
             patch.object(orchestrator, "list_decisions", return_value=[]), \
             patch.object(orchestrator, "_select_and_activate_next_story", side_effect=fake_select), \
             patch.object(orchestrator, "log_line", side_effect=logged.append):
            orchestrator.main()

        self.assertTrue(
            any(line.startswith("Availability check:") for line in logged),
            logged,
        )
        self.assertTrue(
            any(line.startswith("Decision:") for line in logged),
            logged,
        )


if __name__ == "__main__":
    unittest.main()
