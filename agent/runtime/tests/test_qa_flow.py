"""QA gate ordering, clarification, retry, resume, and conditional-review flows."""

import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from agent.runtime.core import orchestrator
from agent.runtime.qa import qa_agent
from agent.runtime.runners.claude_runner import ClaudeAttempt
from agent.runtime.support import config
from agent.runtime.support.capacity import ModelCapacityUnavailable


def ready_plan(story_id="STORY-QA-010", *, prepared=False, review=False):
    return {
        "schema_version": 1, "story_id": story_id,
        "status": "READY" if prepared else "NO_TESTS_NEEDED",
        "rationale": "Behavior test is prepared." if prepared else "Documentation-only change; no runtime contract changes.",
        "acceptance_checks": [], "invariants": [], "test_levels": ["unit"] if prepared else [],
        "existing_tests_reviewed": [], "prepared_test_paths": [],
        "test_specifications": ["Assert the independent expected result."] if prepared else [],
        "pre_implementation_verification": {"status": "NOT_RUN"},
        "coverage_review": "Module result inspected." if prepared else "Not applicable.",
        "external_sources": [], "clarifications": [], "user_decision_ids": [],
        "post_implementation_review_required": review,
    }


class QAGateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.story = self.root / "STORY-QA-010-example.md"
        self.story.write_text(
            "# STORY-QA-010: Example\n\n## Status\n\nTODO\n\n## Blockers\n\nNone.\n\n## Result\n\nTODO\n",
            encoding="utf-8",
        )
        self.events = []

    def tearDown(self):
        self.temp.cleanup()

    def test_required_and_unnecessary_qa_both_allow_only_valid_plans(self):
        for plan in (ready_plan(prepared=True), ready_plan(prepared=False)):
            with self.subTest(status=plan["status"]), \
                    patch.object(qa_agent, "load_plan", return_value=None), \
                    patch.object(qa_agent, "execute_preparation", return_value=plan), \
                    patch.object(qa_agent, "clear_state"):
                result = orchestrator._ensure_preimplementation_qa(self.story)
            self.assertEqual(result["status"], plan["status"])

    def test_successful_qa_runs_before_coding_and_evaluation(self):
        plan = ready_plan(prepared=True)
        result_file = self.root / "CLAUDE_RESULT.md"

        def prep(*_args):
            self.events.append("qa")
            return plan

        def code(_prompt):
            self.events.append("coding")
            result_file.write_text("implementation report", encoding="utf-8")
            return ClaudeAttempt(0, False)

        def evaluate(*_args, **_kwargs):
            self.events.append("evaluation")
            return {"decision": "BLOCKED", "reason": "fixture stop"}

        with patch.object(orchestrator, "get_active_story_path", return_value=self.story), \
                patch.object(orchestrator, "REPO_ROOT", self.root), \
                patch.object(orchestrator, "pending_attempt_for", return_value=None), \
                patch.object(orchestrator, "_ensure_preimplementation_qa", side_effect=prep), \
                patch.object(orchestrator, "_prepare_implementation_prompt", return_value=("implement", False)), \
                patch.object(orchestrator, "CLAUDE_RESULT_FILE", result_file), \
                patch.object(orchestrator, "run_claude_attempt", side_effect=code), \
                patch.object(orchestrator, "_evaluate_preserving_completed_attempt", side_effect=evaluate), \
                patch.object(orchestrator, "set_story_blocked"), \
                patch.object(orchestrator, "_blocked_bookkeeping"), \
                patch.object(orchestrator, "record_attempt_state"), \
                patch.object(orchestrator, "clear_attempt_state"), \
                patch.object(orchestrator, "get_claude_usage", return_value=None):
            result = orchestrator.execute_active_story()
        self.assertEqual(result, "BLOCKED")
        self.assertEqual(self.events, ["qa", "coding", "evaluation"])

    def test_clarification_blocks_only_story_until_product_owner_answers(self):
        plan = ready_plan()
        plan.update(status="NEEDS_USER", clarifications=["Which result is required?"], user_decision_ids=["UD-041"])
        with patch.object(qa_agent, "load_plan", return_value=None), \
                patch.object(qa_agent, "execute_preparation", return_value=plan), \
                patch.object(orchestrator, "_block_story_for_qa_decision") as block:
            result = orchestrator._ensure_preimplementation_qa(self.story)
        self.assertIsNone(result)
        block.assert_called_once_with(self.story, plan)

    def test_invalid_qa_output_gets_actionable_feedback_before_bounded_retry(self):
        plan = ready_plan()
        with patch.object(qa_agent, "load_plan", return_value=None), \
                patch.object(qa_agent, "execute_preparation", side_effect=[qa_agent.QAPlanError("bad status"), plan]) as run, \
                patch.object(qa_agent, "record_attempt", return_value=1), \
                patch.object(qa_agent, "clear_state"), \
                patch.object(orchestrator, "log_line"), \
                patch.object(orchestrator, "_create_intervention_and_block_story") as escalate:
            result = orchestrator._ensure_preimplementation_qa(self.story)
        self.assertEqual(result["status"], "NO_TESTS_NEEDED")
        self.assertEqual(run.call_count, 2)
        self.assertEqual(run.call_args.kwargs["correction_feedback"], "bad status")
        escalate.assert_not_called()

    def test_file_protection_failure_is_technical_and_does_not_retry_or_escalate_to_product_owner(self):
        failure = qa_agent.QAFileProtectionError("agent/logs/2026-10-03.log")
        with patch.object(qa_agent, "load_plan", return_value=None), \
                patch.object(qa_agent, "execute_preparation", side_effect=failure) as run, \
                patch.object(qa_agent, "record_attempt") as record_attempt, \
                patch.object(orchestrator, "_block_story_for_qa_failure") as technical_block, \
                patch.object(orchestrator, "_create_intervention_and_block_story") as intervention:
            self.assertIsNone(orchestrator._ensure_preimplementation_qa(self.story))
        run.assert_called_once()
        record_attempt.assert_not_called()
        technical_block.assert_called_once_with(
            self.story, "file_protection_violation", failure, model_retries=0
        )
        intervention.assert_not_called()

    def test_codex_capacity_does_not_consume_qa_failure_retry(self):
        plan = ready_plan()
        with patch.object(qa_agent, "load_plan", return_value=None), \
                patch.object(qa_agent, "execute_preparation", side_effect=[ModelCapacityUnavailable("quota"), plan]) as run, \
                patch.object(qa_agent, "record_attempt") as record, \
                patch.object(qa_agent, "clear_state"), \
                patch.object(orchestrator, "log_line"), \
                patch.object(orchestrator, "_create_intervention_and_block_story") as escalate:
            result = orchestrator._ensure_preimplementation_qa(self.story, wait_for_qa=lambda: self.events.append("wait"))
        self.assertEqual(result["status"], "NO_TESTS_NEEDED")
        self.assertEqual(run.call_count, 2)
        record.assert_not_called()
        escalate.assert_not_called()
        self.assertEqual(self.events, ["wait"])

    def test_completed_qa_and_interrupted_attempt_are_not_repeated(self):
        plan = ready_plan()
        attempt = {"phase": "AWAITING_EVALUATION", "claude_exit_code": 0, "result_was_updated": False,
                   "retry_count": 0, "ci_fix_attempts": 0}
        result_file = self.root / "CLAUDE_RESULT.md"
        result_file.write_text("finished", encoding="utf-8")
        with patch.object(orchestrator, "get_active_story_path", return_value=self.story), \
                patch.object(orchestrator, "REPO_ROOT", self.root), \
                patch.object(orchestrator, "pending_attempt_for", return_value=attempt), \
                patch.object(qa_agent, "load_plan", return_value=plan), \
                patch.object(orchestrator, "_ensure_preimplementation_qa") as qa, \
                patch.object(orchestrator, "_current_result_content", return_value="finished"), \
                patch.object(orchestrator, "_evaluate_preserving_completed_attempt", return_value={"decision": "BLOCKED", "reason": "fixture"}), \
                patch.object(orchestrator, "set_story_blocked"), \
                patch.object(orchestrator, "_blocked_bookkeeping"), \
                patch.object(orchestrator, "record_attempt_state"), \
                patch.object(orchestrator, "clear_attempt_state"):
            self.assertEqual(orchestrator.execute_active_story(), "BLOCKED")
        qa.assert_not_called()

    def test_evaluator_rejection_gets_conditional_read_only_qa_review(self):
        plan = ready_plan(prepared=True)
        retry = {"decision": "RETRY", "reason": "Missing invariant.", "actionable_retry_items": ["verify invariant"]}
        review = {"decision": "RETRY", "reason": "Integration boundary is unverified.", "actionable_items": ["add an integration assertion"]}
        with patch.object(orchestrator, "_evaluate_with_local_retries", return_value=retry), \
                patch.object(qa_agent, "run_conditional_review", return_value=review) as qa_review, \
                patch.object(qa_agent, "record_review"), \
                patch.object(orchestrator, "get_active_story_path", return_value=self.story):
            result = orchestrator._evaluate_preserving_completed_attempt(
                "story", "result", 0, True, qa_plan=plan
            )
        self.assertEqual(result["decision"], "RETRY")
        self.assertIn("add an integration assertion", result["actionable_retry_items"])
        qa_review.assert_called_once()

    def test_codex_capacity_during_qa_review_does_not_repeat_completed_evaluation(self):
        plan = ready_plan(review=True)
        evaluated = {"decision": "COMPLETE", "reason": "ok", "qa_review_required": True}
        reviews = iter([ModelCapacityUnavailable("quota"), {
            "decision": "APPROVE", "reason": "verified", "evidence": [], "actionable_items": []
        }])
        with patch.object(orchestrator, "_evaluate_with_local_retries", return_value=evaluated) as evaluator, \
                patch.object(qa_agent, "run_conditional_review", side_effect=reviews) as reviewer, \
                patch.object(qa_agent, "record_review"), \
                patch.object(orchestrator, "get_active_story_path", return_value=self.story):
            result = orchestrator._evaluate_preserving_completed_attempt(
                "story", "result", 0, True,
                wait_for_evaluator=lambda: self.events.append("wait"),
                qa_plan=plan,
            )
        self.assertEqual(result["decision"], "COMPLETE")
        evaluator.assert_called_once()
        self.assertEqual(reviewer.call_count, 2)
        self.assertEqual(self.events, ["wait"])

    def test_resume_awaiting_qa_review_uses_saved_evaluator_result(self):
        plan = ready_plan(review=True)
        result_path = self.root / "EVALUATOR_RESULT.json"
        claude_result = self.root / "CLAUDE_RESULT.md"
        claude_result.write_text("completed implementation", encoding="utf-8")
        story_content = self.story.read_text(encoding="utf-8")
        result_path.write_text(json.dumps({
            "decision": "BLOCKED", "reason": "saved", "qa_review_required": True,
            "story_id": orchestrator.extract_story_id(story_content) or self.story.stem,
            "evaluated_story_sha256": hashlib.sha256(story_content.encode("utf-8")).hexdigest(),
            "evaluated_result_sha256": orchestrator.file_hash(claude_result),
        }), encoding="utf-8")
        attempt = {"phase": "AWAITING_QA_REVIEW", "claude_exit_code": 0,
                   "result_was_updated": False, "retry_count": 1, "ci_fix_attempts": 0}
        with patch.object(orchestrator, "get_active_story_path", return_value=self.story), \
                patch.object(orchestrator, "REPO_ROOT", self.root), \
                patch.object(orchestrator, "pending_attempt_for", return_value=attempt), \
                patch.object(qa_agent, "load_plan", return_value=plan), \
                patch.object(orchestrator, "_ensure_preimplementation_qa") as qa, \
                patch.object(orchestrator, "CLAUDE_RESULT_FILE", claude_result), \
                patch.object(orchestrator, "EVALUATOR_RESULT_FILE", result_path), \
                patch.object(orchestrator, "_evaluate_with_local_retries") as evaluator, \
                patch.object(qa_agent, "run_conditional_review", return_value={"decision": "APPROVE", "reason": "ok", "evidence": [], "actionable_items": []}), \
                patch.object(qa_agent, "record_review"), \
                patch.object(orchestrator, "record_attempt_state"), \
                patch.object(orchestrator, "set_story_blocked"), \
                patch.object(orchestrator, "_blocked_bookkeeping"), \
                patch.object(orchestrator, "clear_attempt_state"):
            self.assertEqual(orchestrator.execute_active_story(), "BLOCKED")
        qa.assert_not_called()
        evaluator.assert_not_called()

    def test_resolved_product_decision_requeues_qa_blocked_story(self):
        plans = self.root / "plans"
        plans.mkdir()
        (plans / "QA-STORY-QA-010.json").write_text(json.dumps({
            "story_id": "STORY-QA-010", "status": "NEEDS_USER",
            "user_decision_ids": ["UD-041"],
        }), encoding="utf-8")
        backlog = self.root / "BACKLOG.md"
        backlog.write_text(
            "## To Do\n\n_(none)_\n\n## Blocked\n\n- `STORY-QA-010-example.md`\n",
            encoding="utf-8",
        )
        pointer = self.root / "CURRENT_STORY.md"
        pointer.write_text("agent/stories/STORY-QA-010-example.md\n", encoding="utf-8")
        self.story.write_text(self.story.read_text(encoding="utf-8").replace("TODO", "BLOCKED", 1), encoding="utf-8")
        with patch.object(config, "QA_PLANS_DIR", plans), \
                patch.object(config, "BACKLOG_FILE", backlog), \
                patch.object(config, "CURRENT_STORY_FILE", pointer), \
                patch.object(qa_agent, "list_decisions", return_value=[{"id": "UD-041", "status": "RESOLVED"}]), \
                patch.object(qa_agent, "find_story_file_by_id", return_value=self.story), \
                patch.object(qa_agent, "build_story_index", return_value={}), \
                patch.object(qa_agent, "get_unsatisfied_dependencies", return_value=[]), \
                patch.object(qa_agent, "clear_story_blocked") as clear:
            result = qa_agent.requeue_resolved_qa_stories()
        self.assertEqual(result, ["STORY-QA-010"])
        clear.assert_called_once_with(self.story)
        self.assertEqual(pointer.read_text(encoding="utf-8"), "")
        self.assertIn("STORY-QA-010-example.md", backlog.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
