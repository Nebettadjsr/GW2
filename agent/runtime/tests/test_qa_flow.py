"""QA gate ordering, clarification, retry, resume, and conditional-review flows."""

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from agent.runtime.core import orchestrator
from agent.runtime.qa import qa_agent
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

    def test_ready_plan_for_the_current_contract_is_reused(self):
        plan = ready_plan(prepared=True)
        plan["story_contract_sha256"] = qa_agent.story_contract_sha256(self.story.read_text(encoding="utf-8"))
        with patch.object(qa_agent, "load_plan", return_value=plan), \
                patch.object(qa_agent, "restore_protected_tests", return_value=[]), \
                patch.object(qa_agent, "execute_preparation") as run:
            self.assertIs(orchestrator._ensure_preimplementation_qa(self.story), plan)
        run.assert_not_called()

    def test_ready_plan_for_a_changed_contract_is_revised_by_qa(self):
        plan = ready_plan(prepared=True)
        plan["story_contract_sha256"] = qa_agent.story_contract_sha256(self.story.read_text(encoding="utf-8"))
        self.story.write_text(self.story.read_text(encoding="utf-8")
                              + "\n## User Guidance\n\n### UI-001\n\nAdd test T1.\n", encoding="utf-8")
        revised = dict(plan, rationale="Revised for the user guidance.")
        with patch.object(qa_agent, "load_plan", return_value=plan), \
                patch.object(qa_agent, "execute_preparation", return_value=revised) as run, \
                patch.object(qa_agent, "clear_state"), \
                patch.object(orchestrator, "log_line"):
            self.assertIs(orchestrator._ensure_preimplementation_qa(self.story), revised)
        self.assertTrue(run.call_args.kwargs["contract_revision"])

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

    def test_evaluator_rejection_gets_conditional_read_only_qa_review(self):
        plan = ready_plan(prepared=True)
        retry = {"decision": "RETRY", "reason": "Missing invariant.", "actionable_retry_items": ["verify invariant"]}
        review = {"decision": "RETRY", "reason": "Integration behavior is incorrect.", "actionable_items": ["fix the production integration boundary"]}
        with patch.object(orchestrator, "_evaluate_with_local_retries", return_value=retry), \
                patch.object(qa_agent, "run_conditional_review", return_value=review) as qa_review, \
                patch.object(qa_agent, "record_review"), \
                patch.object(orchestrator, "get_active_story_path", return_value=self.story):
            result = orchestrator._evaluate_preserving_completed_attempt(
                "story", "result", 0, True, qa_plan=plan
            )
        self.assertEqual(result["decision"], "RETRY")
        self.assertIn("fix the production integration boundary", result["actionable_retry_items"])
        qa_review.assert_called_once()

    def test_only_qa_test_verdict_requests_writing_without_sending_test_items_to_coder(self):
        plan = ready_plan(prepared=True)
        verdict = {"decision": "RETRY", "reason": "Check test expectation.", "actionable_retry_items": []}
        review = {"decision": "REVISE_TESTS", "reason": "Expected value contradicts the requirement.",
                  "evidence": ["The spec defines zero."], "actionable_items": ["Correct the expected value."]}
        with patch.object(orchestrator, "_evaluate_with_local_retries", return_value=verdict), \
                patch.object(qa_agent, "run_conditional_review", return_value=review), \
                patch.object(qa_agent, "record_review"), \
                patch.object(orchestrator, "get_active_story_path", return_value=self.story):
            result = orchestrator._evaluate_preserving_completed_attempt("story", "result", 0, True, qa_plan=plan)
        self.assertEqual(result["qa_test_repair"], review)
        self.assertEqual(result["actionable_retry_items"], [])

    def test_evaluator_block_that_qa_can_resolve_routes_to_test_repair(self):
        plan = ready_plan(prepared=True)
        verdict = {"decision": "BLOCKED", "reason": "Obsolete tests must be removed through QA.",
                   "qa_review_required": True}
        review = {"decision": "REVISE_TESTS", "reason": "Tests of deleted classes remain.",
                  "evidence": ["OldViewIT references a removed class."], "actionable_items": ["Remove OldViewIT."]}
        with patch.object(orchestrator, "_evaluate_with_local_retries", return_value=verdict), \
                patch.object(qa_agent, "run_conditional_review", return_value=review), \
                patch.object(qa_agent, "record_review"), \
                patch.object(orchestrator, "get_active_story_path", return_value=self.story):
            result = orchestrator._evaluate_preserving_completed_attempt("story", "result", 0, True, qa_plan=plan)
        self.assertEqual(result["decision"], "RETRY")
        self.assertEqual(result["qa_test_repair"], review)

    def test_qa_retry_naming_only_qa_owned_work_routes_to_test_repair(self):
        plan = ready_plan(prepared=True)
        verdict = {"decision": "RETRY", "reason": "Purpose achieved; QA record is stale.", "actionable_retry_items": []}
        review = {"decision": "RETRY", "reason": "The QA plan is stale.",
                  "actionable_items": ["Update agent/qa-plans/QA-STORY-QA-001.json with the final results.",
                                       "In the QA-owned test process, retire src/test/java/OldViewIT.java."]}
        with patch.object(orchestrator, "_evaluate_with_local_retries", return_value=verdict), \
                patch.object(qa_agent, "run_conditional_review", return_value=review), \
                patch.object(qa_agent, "record_review"), \
                patch.object(orchestrator, "get_active_story_path", return_value=self.story):
            result = orchestrator._evaluate_preserving_completed_attempt("story", "result", 0, True, qa_plan=plan)
        self.assertEqual(result["qa_test_repair"]["decision"], "REVISE_TESTS")
        self.assertEqual(result["actionable_retry_items"], [])

    def test_qa_retry_with_production_work_still_goes_to_the_coder(self):
        plan = ready_plan(prepared=True)
        verdict = {"decision": "RETRY", "reason": "Defect.", "actionable_retry_items": []}
        review = {"decision": "RETRY", "reason": "Defect.",
                  "actionable_items": ["Fix src/main/java/App.java and update the QA plan afterwards."]}
        with patch.object(orchestrator, "_evaluate_with_local_retries", return_value=verdict), \
                patch.object(qa_agent, "run_conditional_review", return_value=review), \
                patch.object(qa_agent, "record_review"), \
                patch.object(orchestrator, "get_active_story_path", return_value=self.story):
            result = orchestrator._evaluate_preserving_completed_attempt("story", "result", 0, True, qa_plan=plan)
        self.assertNotIn("qa_test_repair", result)
        self.assertIn("Fix src/main/java/App.java and update the QA plan afterwards.", result["actionable_retry_items"])

    def test_test_repair_does_not_override_a_genuine_product_decision(self):
        plan = ready_plan(prepared=True)
        verdict = {"decision": "NEEDS_USER", "reason": "Product policy is unspecified."}
        review = {"decision": "REVISE_TESTS", "reason": "Also missing a test.",
                  "evidence": ["No assertion for the selected policy."], "actionable_items": ["Add policy test."]}
        with patch.object(orchestrator, "_evaluate_with_local_retries", return_value=verdict), \
                patch.object(qa_agent, "run_conditional_review", return_value=review), \
                patch.object(qa_agent, "record_review"), \
                patch.object(orchestrator, "get_active_story_path", return_value=self.story):
            result = orchestrator._evaluate_preserving_completed_attempt("story", "result", 0, True, qa_plan=plan)
        self.assertEqual(result["decision"], "NEEDS_USER")
        self.assertNotIn("qa_test_repair", result)

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
