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

import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from agent.runtime.core import orchestrator


class PlanningInputSnapshotTest(unittest.TestCase):

    def test_descriptive_runtime_snapshots_do_not_trigger_full_planning(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            docs = root / "docs"
            agent = root / "agent"
            stories = agent / "stories"
            for path in (docs, agent, stories):
                path.mkdir(parents=True, exist_ok=True)
            (docs / "DOMAIN_SPEC.md").write_text("intent A", encoding="utf-8")
            (docs / "CURRENT_ARCHITECTURE.md").write_text("snapshot A", encoding="utf-8")
            (docs / "CURRENT_STATE_SPEC.md").write_text("state A", encoding="utf-8")
            names = {
                "REPO_ROOT": root,
                "PROJECT_STATE_FILE": agent / "PROJECT_STATE.md",
                "ROADMAP_FILE": docs / "ROADMAP.md",
                "TARGET_ARCHITECTURE_FILE": docs / "TARGET_ARCHITECTURE.md",
                "USER_DECISIONS_DIR": agent / "user-decisions",
                "PRODUCT_OWNER_REQUESTS_DIR": agent / "product-owner-requests",
                "ARCHITECT_REQUESTS_DIR": agent / "architect-requests",
                "BACKLOG_FILE": stories / "BACKLOG.md",
            }
            with patch.multiple(orchestrator, **names):
                before = orchestrator.planning_input_snapshot()
                (docs / "CURRENT_ARCHITECTURE.md").write_text("snapshot B", encoding="utf-8")
                (docs / "CURRENT_STATE_SPEC.md").write_text("state B", encoding="utf-8")
                self.assertEqual(before, orchestrator.planning_input_snapshot())
                (docs / "DOMAIN_SPEC.md").write_text("intent B", encoding="utf-8")
                self.assertNotEqual(before, orchestrator.planning_input_snapshot())


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


if __name__ == "__main__":
    unittest.main()
