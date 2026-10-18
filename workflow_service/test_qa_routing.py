"""Application failures go to production coding or guarded QA test repair by QA verdict."""

import json
from unittest import mock

from agent.runtime.support.capacity import ModelCapacityUnavailable
from workflow_service import cycle
from workflow_service.test_service import _CycleTestCase, make_cycle


def qa_correction():
    return {"decision": "REVISE_TESTS", "reason": "Fixture contradicts the domain rule.",
            "evidence": ["The documented zero-input result differs from the fixture."],
            "actionable_items": ["Correct the application test fixture to the documented result."]}


class ApplicationQARoutingTests(_CycleTestCase):
    def failed_ci(self, record):
        cycle.github_ci.wait_for_commit.return_value = {"status": "FAILED", "reason": "red", "report": "failed test"}
        return cycle.step_ci(record, None)

    def test_evaluation_queues_only_independently_approved_test_work(self):
        record = make_cycle(phase="evaluate")
        result = self.evaluate(record, "RETRY", qa_test_repair=qa_correction())
        self.assertEqual((record["phase"], record["retry_count"], record["qa_repair_attempts"]), ("qa_repair", 0, 1))
        self.assertTrue(result["proceed"])
        cycle.qa_agent.execute_test_repair.assert_not_called()
        self.orchestrator.build_retry_prompt.assert_not_called()
        self.assertEqual(record["qa_action"]["source"], "evaluation")

    def test_reported_interface_deviation_goes_to_qa_before_the_evaluator_runs(self):
        record = make_cycle(phase="evaluate", interface_deviations=["test expects Foo.bar, production offers Foo.baz"])
        result = cycle.step_evaluate(record, None)
        self.assertEqual((record["phase"], record["qa_repair_attempts"], record["interface_deviations"]),
                         ("qa_repair", 1, []))
        self.assertTrue(result["proceed"])
        self.orchestrator._evaluate_preserving_completed_attempt.assert_not_called()
        self.assertEqual(record["qa_action"]["source"], "interface_deviation")
        self.assertEqual(record["qa_action"]["review"]["evidence"], ["test expects Foo.bar, production offers Foo.baz"])

    def test_interface_deviation_cannot_loop_past_the_repair_budget(self):
        record = make_cycle(phase="evaluate", qa_repair_attempts=cycle.MAX_QA_TEST_REPAIRS_PER_STORY,
                            interface_deviations=["x"])
        cycle.step_evaluate(record, None)
        self.assertEqual(record["phase"], "blocked")
        cycle.qa_agent.execute_test_repair.assert_not_called()

    def test_coder_claim_or_evaluator_retry_alone_cannot_authorize_test_writing(self):
        record = make_cycle(phase="evaluate")
        self.evaluate(record, "RETRY", actionable_retry_items=["Coder claims a test is wrong; inspect production."])
        self.assertEqual(record["phase"], "implement")
        cycle.qa_agent.execute_test_repair.assert_not_called()
        self.assertNotIn("qa_action", record)

    def test_unevidenced_test_correction_is_blocked(self):
        record = make_cycle(phase="evaluate")
        review = {**qa_correction(), "evidence": []}
        self.evaluate(record, "RETRY", qa_test_repair=review)
        self.assertEqual(record["phase"], "blocked")
        cycle.qa_agent.execute_test_repair.assert_not_called()

    def test_ci_failure_waits_for_qa_without_consuming_coder_budget(self):
        record = make_cycle(phase="ci", published_sha="a" * 40)
        result = self.failed_ci(record)
        self.assertEqual((record["phase"], record["ci_fix_attempts"], record["retry_count"]), ("qa_review", 0, 0))
        self.assertTrue(result["proceed"])
        self.orchestrator.build_ci_failure_prompt.assert_not_called()
        cycle.qa_agent.run_conditional_review.assert_not_called()

    def test_ci_test_error_goes_to_qa_then_evaluation_publish_and_new_ci(self):
        record = make_cycle(phase="ci", published_sha="a" * 40)
        self.failed_ci(record)
        cycle.qa_agent.run_conditional_review.return_value = qa_correction()
        cycle.step_qa_review(record, None)
        self.assertEqual((record["phase"], record["ci_fix_attempts"]), ("qa_repair", 0))
        request_id = record["qa_action"]["request_id"]
        cycle.qa_agent.execute_test_repair.return_value = {
            "status": "READY", "prepared_test_paths": ["src/test/java/CorrectedTest.java"]}
        cycle.step_qa_repair(record, None)
        self.assertEqual(record["phase"], "evaluate")
        self.assertIsNone(record["published_sha"])
        self.assertIn("src/test/java/CorrectedTest.java", record["publish_paths"])
        self.assertEqual(record["qa_repair_history"][0]["request_id"], request_id)
        self.orchestrator.build_ci_failure_prompt.assert_not_called()
        self.evaluate(record, "COMPLETE")
        self.assertEqual(record["phase"], "publish")
        cycle.git_sync.working_tree_paths.return_value = {"src/test/java/CorrectedTest.java"}
        cycle.git_sync.commit_and_push.return_value = {"status": "PUSHED", "sha": "b" * 40}
        cycle.step_publish(record, None)
        self.assertEqual((record["phase"], record["published_sha"]), ("ci", "b" * 40))
        self.assertIn("src/test/java/CorrectedTest.java", cycle.git_sync.commit_and_push.call_args.kwargs["paths"])
        cycle.github_ci.wait_for_commit.return_value = {"status": "PASSED", "reason": "green"}
        cycle.step_ci(record, None)
        self.assertEqual(record["phase"], "finalize")
        self.assertEqual(cycle.github_ci.wait_for_commit.call_args.args[1], "b" * 40)

    def test_red_ci_cannot_be_accepted_by_a_qa_approval(self):
        record = make_cycle(phase="ci", published_sha="a" * 40)
        self.failed_ci(record)
        cycle.qa_agent.run_conditional_review.return_value = {"decision": "APPROVE", "reason": "No code change identified."}
        result = cycle.step_qa_review(record, None)
        self.assertEqual((record["phase"], result["proceed"]), ("ci", False))
        self.orchestrator.build_ci_failure_prompt.assert_not_called()
        self.assertEqual(cycle.step_ci(record, None)["outcome"], "ci_still_failed")
        self.assertEqual(record["phase"], "ci")
        self.assertEqual(cycle.qa_agent.run_conditional_review.call_count, 1)
        cycle.github_ci.wait_for_commit.return_value = {"status": "PASSED", "reason": "rerun green"}
        cycle.step_ci(record, None)
        self.assertEqual(record["phase"], "finalize")

    def test_unrelated_ci_and_workflow_warning_do_not_request_application_qa(self):
        record = make_cycle(phase="ci", published_sha="a" * 40)
        self.orchestrator.ci_failures_are_outside_story_scope.return_value = True
        self.assertEqual(self.failed_ci(record)["outcome"], "FAILED_OUTSIDE_SCOPE")
        self.assertEqual(record["phase"], "ci")
        cycle.qa_agent.run_conditional_review.assert_not_called()
        cycle.github_ci.wait_for_commit.return_value = {
            "status": "PASSED", "ignored_failures": [{"job": "Agent runtime", "run_url": "url"}]}
        result = cycle.step_ci(record, None)
        self.assertEqual(record["phase"], "finalize")
        self.assertTrue(result["workflow_warnings"])
        cycle.qa_agent.run_conditional_review.assert_not_called()

    def test_saved_qa_decision_does_not_run_a_second_review_after_restart(self):
        record = make_cycle(phase="ci", published_sha="a" * 40)
        self.failed_ci(record)
        cycle.qa_agent.run_conditional_review.return_value = qa_correction()
        checkpoints = []
        context = cycle.StepContext(lambda: checkpoints.append(json.loads(json.dumps(record))))
        cycle.step_qa_review(record, context)
        self.assertEqual(checkpoints[0]["phase"], "qa_review")
        restored = checkpoints[0]
        cycle.step_qa_review(restored, None)
        self.assertEqual(cycle.qa_agent.run_conditional_review.call_count, 1)
        self.assertEqual(restored["phase"], "qa_repair")
        self.assertEqual(restored["qa_repair_attempts"], 1)

    def test_invalid_ci_diagnosis_retries_qa_not_claude(self):
        record = make_cycle(phase="ci", published_sha="a" * 40)
        self.failed_ci(record)
        valid = {"decision": "RETRY", "reason": "Production defect", "actionable_items": ["Fix production behavior"]}
        cycle.qa_agent.run_conditional_review.side_effect = [cycle.qa_agent.QAPlanError("invalid verdict"), valid]
        context = cycle.StepContext(mock.Mock())
        self.assertEqual(cycle.step_qa_review(record, context)["outcome"], "qa_correction_required")
        self.assertEqual((record["phase"], record["ci_fix_attempts"]), ("qa_review", 0))
        self.orchestrator.build_ci_failure_prompt.assert_not_called()
        cycle.step_qa_review(record, context)
        self.assertEqual((record["phase"], record["ci_fix_attempts"]), ("implement", 1))

    def test_ci_qa_product_question_blocks_through_the_existing_decision_flow(self):
        record = make_cycle(phase="ci", published_sha="a" * 40)
        self.failed_ci(record)
        cycle.qa_agent.run_conditional_review.return_value = {"decision": "NEEDS_USER", "reason": "Which policy?"}
        cycle.qa_agent.load_plan.side_effect = [
            {"story_id": "STORY-T-001", "status": "READY"},
            {"story_id": "STORY-T-001", "status": "NEEDS_USER", "user_decision_ids": ["UD-124"]}]
        cycle.step_qa_review(record, None)
        self.assertEqual(record["phase"], "blocked")
        self.assertEqual(self.orchestrator._block_story_for_qa_decision.call_args.args[1]["user_decision_ids"], ["UD-124"])

    def test_capacity_pause_keeps_pending_qa_work_and_counters(self):
        record = make_cycle(phase="evaluate")
        self.evaluate(record, "RETRY", qa_test_repair=qa_correction())
        cycle.qa_agent.execute_test_repair.side_effect = ModelCapacityUnavailable("Codex capacity")
        with self.assertRaises(ModelCapacityUnavailable):
            cycle.step_qa_repair(record, None)
        self.assertEqual((record["phase"], record["qa_repair_attempts"], record["retry_count"]), ("qa_repair", 1, 0))

    def test_repeated_invalid_qa_output_gets_one_correction_then_blocks(self):
        record = make_cycle(phase="evaluate")
        self.evaluate(record, "RETRY", qa_test_repair=qa_correction())
        cycle.qa_agent.execute_test_repair.side_effect = cycle.qa_agent.QAPlanError("invalid plan")
        self.assertTrue(cycle.step_qa_repair(record, None)["proceed"])
        result = cycle.step_qa_repair(record, None)
        self.assertEqual((record["phase"], result["proceed"]), ("blocked", False))
        self.orchestrator._block_story_for_qa_failure.assert_called_once()

    def test_qa_repairs_have_their_own_limit_without_spending_coder_retries(self):
        record = make_cycle(phase="evaluate", qa_repair_attempts=cycle.MAX_QA_TEST_REPAIRS_PER_STORY)
        self.evaluate(record, "RETRY", qa_test_repair=qa_correction())
        self.assertEqual((record["phase"], record["retry_count"], record["ci_fix_attempts"]), ("blocked", 0, 0))

    def test_qa_product_question_uses_the_existing_decision_flow(self):
        record = make_cycle(phase="evaluate")
        self.evaluate(record, "RETRY", qa_test_repair=qa_correction())
        cycle.qa_agent.execute_test_repair.return_value = {"status": "NEEDS_USER", "user_decision_ids": ["UD-123"]}
        result = cycle.step_qa_repair(record, None)
        self.assertEqual((record["phase"], result["proceed"]), ("blocked", False))
        self.orchestrator._block_story_for_qa_decision.assert_called_once()
