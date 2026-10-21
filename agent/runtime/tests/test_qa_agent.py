"""Tests for QA plan validation, persistence, write guards, and role configuration."""

import json
import io
import subprocess
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

from agent.runtime.qa import qa_agent
from agent.runtime.runners import local_planner_runner
from agent.runtime.support import config
from agent.runtime.support.capacity import ModelCapacityUnavailable
from agent.runtime.tests import REAL_QA_PREPARATION, REAL_QA_TEST_REPAIR, REAL_RUN_QA
from agent.runtime.human import user_decisions
from agent.runtime.support import daily_log


def raw_plan(status="READY", **overrides):
    plan = {
        "status": status,
        "story_id": "STORY-QA-001",
        "rationale": "The story changes visible behavior.",
        "acceptance_checks": [{"criterion": "Shown in UI", "expected": "Visible", "verification": "component test"}],
        "invariants": ["Quantity remains conserved across the complete result."],
        "test_levels": ["unit", "integration"],
        "existing_tests_reviewed": ["agent/runtime/tests/test_story.py covers existing workflow"],
        "prepared_test_paths": [],
        "test_specifications": ["A behavior-level expected result."],
        "pre_implementation_verification": {"status": "NOT_RUN", "command": "", "failure_evidence": ""},
        "coverage_review": "The affected module is below the KPI target.",
        "external_sources": [],
        "clarifications": [],
        "post_implementation_review_required": False,
        "post_implementation_review_reason": "",
    }
    plan.update(overrides)
    return plan


class QAPlanValidationTest(unittest.TestCase):
    def test_story_id_section_overrides_filename_slug(self):
        content = "# Ecto Content Hooks\n\n## Story ID\n\nSTORY-WEB-021\n"
        story_id = qa_agent.story_id_from_content(
            content, "STORY-WEB-021-ecto-content-test-hooks"
        )
        self.assertEqual(story_id, "STORY-WEB-021")
        plan = raw_plan(story_id="STORY-WEB-021")
        self.assertEqual(
            qa_agent.validate_plan(plan, story_id)["story_id"],
            "STORY-WEB-021",
        )

    def test_unprefixed_exact_story_id_is_normalized_without_fuzzy_matching(self):
        value = raw_plan(story_id="WEB-027")
        normalized = qa_agent.validate_plan(value, "STORY-WEB-027")
        self.assertEqual(normalized["story_id"], "STORY-WEB-027")

        value["story_id"] = "WEB-028"
        with self.assertRaises(qa_agent.QAPlanError):
            qa_agent.validate_plan(value, "STORY-WEB-027")

    def test_no_tests_needs_story_specific_justification(self):
        value = raw_plan("NO_TESTS_NEEDED", rationale="This changes prose only; no runtime behavior or contract changes.", test_specifications=[])
        self.assertEqual(qa_agent.validate_plan(value, "STORY-QA-001")["status"], "NO_TESTS_NEEDED")
        value["rationale"] = ""
        with self.assertRaises(qa_agent.QAPlanError):
            qa_agent.validate_plan(value, "STORY-QA-001")

    def test_ready_must_include_tests_or_precise_specifications(self):
        value = raw_plan(test_specifications=[])
        with self.assertRaises(qa_agent.QAPlanError):
            qa_agent.validate_plan(value, "STORY-QA-001")

    def test_ready_cannot_delegate_unwritten_tests_through_specifications(self):
        value = raw_plan(existing_tests_reviewed=[], prepared_test_paths=[])
        with self.assertRaisesRegex(qa_agent.QAPlanError, "cannot delegate test authorship"):
            qa_agent.validate_plan(value, "STORY-QA-001")

    def test_qa_can_maintain_existing_tests_and_test_fixtures_only(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            paths = ("src/test/java/ExistingTest.java", "src/test/resources/expected.json",
                     "frontend/src/Existing.spec.ts", "src/main/java/App.java")
            before = {name: b"original" for name in paths}
            after = {name: b"updated" for name in paths}
            for name in paths:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(after[name])
            with patch.object(config, "REPO_ROOT", root):
                owned, violations = qa_agent.enforce_test_only_changes(before, after)
            self.assertEqual(owned, sorted(paths[:3]))
            self.assertEqual(violations, [paths[3]])
            self.assertEqual((root / paths[3]).read_bytes(), b"original")
            for name in paths[:3]:
                self.assertEqual((root / name).read_bytes(), b"updated")

    def test_expected_red_requires_behavior_failure_evidence(self):
        value = raw_plan(pre_implementation_verification={"status": "EXPECTED_FAILURE", "command": "pytest test.py", "failure_evidence": ""})
        with self.assertRaisesRegex(qa_agent.QAPlanError, "failure requires evidence"):
            qa_agent.validate_plan(value, "STORY-QA-001")
        value["pre_implementation_verification"]["failure_evidence"] = "The assertion comparing actual output to documented value failed."
        self.assertEqual(qa_agent.validate_plan(value, "STORY-QA-001")["status"], "READY")

    def test_path_validation_rejects_production_files_and_traversal(self):
        for path in ("src/main/java/App.java", "../src/test/FooTest.java"):
            with self.subTest(path=path), self.assertRaises(qa_agent.QAPlanError):
                qa_agent._safe_relative_path(path)

    def test_test_only_guard_restores_production_write_and_keeps_new_test(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "src/main/java/App.java"
            source.parent.mkdir(parents=True)
            source.write_text("safe", encoding="utf-8")
            with patch.object(config, "REPO_ROOT", root):
                before = {"src/main/java/App.java": b"safe"}
                source.write_text("changed", encoding="utf-8")
                test = root / "src/test/java/AppTest.java"
                test.parent.mkdir(parents=True)
                test.write_text("assert behavior", encoding="utf-8")
                after = {"src/main/java/App.java": b"changed", "src/test/java/AppTest.java": b"assert behavior"}
                added, violations = qa_agent.enforce_test_only_changes(before, after)
            self.assertEqual(added, ["src/test/java/AppTest.java"])
            self.assertEqual(violations, ["src/main/java/App.java"])
            self.assertEqual(source.read_text(encoding="utf-8"), "safe")
            self.assertTrue(test.exists())

    def test_test_only_guard_preserves_a_concurrent_change_instead_of_overwriting_it(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "src/main/java/App.java"
            source.parent.mkdir(parents=True)
            source.write_bytes(b"concurrent user change")
            with patch.object(config, "REPO_ROOT", root):
                _, violations = qa_agent.enforce_test_only_changes(
                    {"src/main/java/App.java": b"before QA"},
                    {"src/main/java/App.java": b"QA version"},
                )
            self.assertTrue(any("preserved newer concurrent change; not restored" in item for item in violations))
            self.assertEqual(source.read_bytes(), b"concurrent user change")

    def test_qa_model_and_effort_are_explicit_without_changing_other_codex_roles(self):
        with patch.object(local_planner_runner, "run_qa", REAL_RUN_QA), \
                patch.object(local_planner_runner, "run_codex", return_value=0) as run:
            self.assertEqual(local_planner_runner.run_qa("plan", []), 0)
        kwargs = run.call_args.kwargs
        self.assertEqual(kwargs["model"], "gpt-6-luna")
        self.assertEqual(kwargs["reasoning_effort"], "medium")
        self.assertEqual(kwargs["sandbox"], "workspace-write")
        # Existing Codex roles omit model/effort overrides by construction.
        with patch.object(local_planner_runner, "run_codex", return_value=0) as run:
            local_planner_runner.run_architect("architecture")
        self.assertNotIn("model", run.call_args.kwargs)
        self.assertNotIn("reasoning_effort", run.call_args.kwargs)


class QAPreparationPersistenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        self.story = self.root / "agent/stories/STORY-QA-001-behavior.md"
        self.story.parent.mkdir(parents=True)
        self.story.write_text("# STORY-QA-001: Behavior\n\n## Status\n\nTODO\n\n## Acceptance Criteria\n\n- value is conserved\n", encoding="utf-8")
        (self.root / "agent/runtime/artifacts").mkdir(parents=True)
        self.test_file = "src/test/java/PreparedAcceptanceTest.java"
        self.test_path = self.root / self.test_file
        self.test_path.parent.mkdir(parents=True)
        self.exit_stack = __import__("contextlib").ExitStack()
        for name, value in {
            "REPO_ROOT": self.root,
            "QA_PLANS_DIR": self.root / "agent/qa-plans",
            "QA_RESULT_FILE": self.root / "agent/runtime/artifacts/QA_RESULT.json",
            "QA_STATE_FILE": self.root / "agent/runtime/artifacts/QA_STATE.json",
            "ARTIFACTS_DIR": self.root / "agent/runtime/artifacts",
            "QA_FAILURES_DIR": self.root / "agent/runtime/artifacts/qa-failures",
            "QA_INSTRUCTIONS_FILE": config.REPO_ROOT / "agent/QA_INSTRUCTIONS.md",
            "USER_DECISIONS_DIR": self.root / "agent/user-decisions",
        }.items():
            self.exit_stack.enter_context(patch.object(config, name, value))
        self.exit_stack.enter_context(patch.object(user_decisions, "USER_DECISIONS_DIR", self.root / "agent/user-decisions"))

    def tearDown(self):
        self.exit_stack.close()
        self.temp.cleanup()

    def test_prepares_expected_red_tests_and_persists_protected_plan(self):
        def prepare(prompt, messages):
            self.assertIn("unit", prompt)
            self.test_path.write_text("assert expected outcome", encoding="utf-8")
            messages.append("""```json
{"status":"READY","story_id":"STORY-QA-001","rationale":"Behavior story.","acceptance_checks":[{"criterion":"value conserved","expected":"sum remains equal","verification":"test"}],"invariants":["Quantity conserved"],"test_levels":["unit"],"existing_tests_reviewed":[],"prepared_test_paths":["src/test/java/PreparedAcceptanceTest.java"],"test_specifications":[],"pre_implementation_verification":{"status":"EXPECTED_FAILURE","command":"mvn -Dtest=PreparedAcceptanceTest test","failure_evidence":"The expected-value assertion failed; compile completed."},"coverage_review":"Coverage report inspected.","external_sources":[],"clarifications":[],"post_implementation_review_required":true,"post_implementation_review_reason":"Calculation spans layers."}
```""")
            return 0

        with patch.object(qa_agent, "execute_preparation", REAL_QA_PREPARATION), \
                patch.object(qa_agent, "run_qa", side_effect=prepare):
            plan = qa_agent.execute_preparation(self.story)
        self.assertEqual(plan["status"], "READY")
        self.assertEqual(plan["pre_implementation_verification"]["status"], "EXPECTED_FAILURE")
        self.assertIn(self.test_file, plan["protected_test_hashes"])
        self.assertEqual(
            plan["story_contract_sha256"],
            qa_agent.story_contract_sha256(self.story.read_text(encoding="utf-8")),
        )
        self.assertTrue((self.root / "agent/qa-plans/QA-STORY-QA-001.json").is_file())
        self.assertTrue((self.root / "agent/runtime/artifacts/qa-tests" / self.test_file).is_file())

    def test_qa_update_to_existing_test_is_persisted_and_protected_from_coding(self):
        self.test_path.write_text("old expectation", encoding="utf-8")

        def prepare(_prompt, messages):
            self.test_path.write_text("requirement-based expectation", encoding="utf-8")
            value = raw_plan(prepared_test_paths=[self.test_file])
            messages.append("```json\n" + json.dumps(value) + "\n```")
            return 0

        with patch.object(qa_agent, "execute_preparation", REAL_QA_PREPARATION), \
                patch.object(qa_agent, "run_qa", side_effect=prepare):
            plan = qa_agent.execute_preparation(self.story)
        self.assertIn(self.test_file, plan["prepared_test_paths"])
        self.assertIn(self.test_file, plan["protected_test_hashes"])
        self.test_path.write_text("coding agent changed this", encoding="utf-8")
        restored = qa_agent.restore_protected_tests(plan)
        self.assertIn(self.test_file, restored)
        self.assertEqual(self.test_path.read_text(encoding="utf-8"), "requirement-based expectation")

    def test_orchestrator_log_appends_during_qa_are_not_misclassified_as_agent_writes(self):
        logs = self.root / "agent/logs"
        logs.mkdir(parents=True)
        log = logs / f"{datetime.now().date().isoformat()}.log"
        log.write_text("pre-existing history\n", encoding="utf-8")

        def prepare(_prompt, output):
            tee = daily_log.ConsoleTee(io.StringIO())
            print("QA runner transcript line", file=tee)
            output.append("""```json
{"status":"READY","story_id":"STORY-QA-001","rationale":"Story-specific checks.","acceptance_checks":[],"invariants":[],"test_levels":["unit"],"existing_tests_reviewed":["Existing story checks cover the required observable result."],"prepared_test_paths":[],"test_specifications":["Check the required observable result."],"pre_implementation_verification":{"status":"NOT_RUN"},"coverage_review":"Reviewed.","external_sources":[],"clarifications":[],"post_implementation_review_required":false}
```""")
            return 0

        with patch.object(daily_log, "LOGS_DIR", logs), \
                patch.object(qa_agent, "execute_preparation", REAL_QA_PREPARATION), \
                patch.object(qa_agent, "run_qa", side_effect=prepare):
            plan = qa_agent.execute_preparation(self.story)
        self.assertEqual(plan["status"], "READY")
        content = log.read_text(encoding="utf-8")
        self.assertIn("pre-existing history", content)
        self.assertIn("QA runner transcript line", content)

    def test_direct_qa_log_edit_is_rejected_and_restored_without_losing_buffered_logs(self):
        logs = self.root / "agent/logs"
        logs.mkdir(parents=True)
        log = logs / f"{datetime.now().date().isoformat()}.log"
        log.write_text("pre-existing history\n", encoding="utf-8")

        def prepare(_prompt, _output):
            daily_log.log_line("legitimate orchestrator output")
            log.write_text("QA replacement", encoding="utf-8")
            return 0

        with patch.object(daily_log, "LOGS_DIR", logs), \
                patch.object(qa_agent, "execute_preparation", REAL_QA_PREPARATION), \
                patch.object(qa_agent, "run_qa", side_effect=prepare):
            with self.assertRaises(qa_agent.QAFileProtectionError):
                qa_agent.execute_preparation(self.story)
        content = log.read_text(encoding="utf-8")
        self.assertIn("pre-existing history", content)
        self.assertIn("legitimate orchestrator output", content)
        self.assertNotIn("QA replacement", content)

    def test_technical_failure_report_is_actionable_and_not_a_product_decision(self):
        failure = qa_agent.record_failure(
            self.story, "file_protection_violation", "QAFileProtectionError: log changed",
            model_retries=0,
        )
        report = json.loads(failure.read_text(encoding="utf-8"))
        self.assertEqual(report["status"], "TECHNICAL_FAILURE")
        self.assertEqual(report["story_id"], "STORY-QA-001")
        self.assertIn("manually requeue", report["recovery_action"])
        self.assertFalse((self.root / "agent/user-decisions").exists())

    def test_interrupted_qa_keeps_generated_tests_for_resume_without_duplicates(self):
        messages = []
        calls = []

        def prepare(_prompt, _messages):
            calls.append(1)
            if len(calls) == 1:
                self.test_path.write_text("prepared once", encoding="utf-8")
                raise ModelCapacityUnavailable("quota")
            self.assertTrue(self.test_path.is_file())
            _messages.append("""```json
{"status":"READY","story_id":"STORY-QA-001","rationale":"Behavior story.","acceptance_checks":[],"invariants":[],"test_levels":["unit"],"existing_tests_reviewed":[],"prepared_test_paths":["src/test/java/PreparedAcceptanceTest.java"],"test_specifications":[],"pre_implementation_verification":{"status":"EXPECTED_FAILURE","failure_evidence":"behavior assertion"},"coverage_review":"Reviewed.","external_sources":[],"clarifications":[],"post_implementation_review_required":false}
```""")
            return 0

        with patch.object(qa_agent, "execute_preparation", REAL_QA_PREPARATION), \
                patch.object(qa_agent, "run_qa", side_effect=prepare):
            with self.assertRaises(ModelCapacityUnavailable):
                qa_agent.execute_preparation(self.story)
            state = json.loads(config.QA_STATE_FILE.read_text(encoding="utf-8"))
            self.assertEqual(state["owned_test_paths"], [self.test_file])
            plan = qa_agent.execute_preparation(self.story)
        self.assertEqual(len(calls), 2)
        self.assertEqual(plan["prepared_test_paths"], [self.test_file])

    def test_application_repair_updates_protected_tests_and_reuses_saved_result_on_restart(self):
        self.test_path.write_text("old expectation", encoding="utf-8")
        original = raw_plan(prepared_test_paths=[self.test_file])
        qa_agent.save_plan(self.story, original, owned_test_paths=[self.test_file])
        request = {"request_id": "repair-1", "source": "ci_failure", "verification": {"report": "wrong fixture"},
                   "review": {"decision": "REVISE_TESTS", "reason": "Expected value contradicts spec.",
                              "evidence": ["Spec defines zero."], "actionable_items": ["Correct expected value."]}}

        def repair(prompt, messages):
            self.assertIn("QA APPLICATION TEST REPAIR", prompt)
            self.assertIn("wrong fixture", prompt)
            self.test_path.write_text("requirement-based expectation", encoding="utf-8")
            messages.append("```json\n" + json.dumps(original) + "\n```")
            return 0

        with patch.object(qa_agent, "execute_preparation", REAL_QA_PREPARATION), \
                patch.object(qa_agent, "run_qa", side_effect=repair) as writer:
            plan = REAL_QA_TEST_REPAIR(self.story, request)
            restored = REAL_QA_TEST_REPAIR(self.story, json.loads(json.dumps(request)))
        writer.assert_called_once()
        self.assertEqual(restored["test_repair"]["request_id"], "repair-1")
        self.assertTrue(plan["post_implementation_review_required"])
        self.test_path.write_text("coder changed test", encoding="utf-8")
        qa_agent.restore_protected_tests(plan)
        self.assertEqual(self.test_path.read_text(encoding="utf-8"), "requirement-based expectation")

    def test_application_repair_restores_production_and_workflow_test_writes(self):
        source = self.root / "src/main/java/App.java"
        workflow_test = self.root / "workflow_service/test_engine.py"  # the flow never edits itself
        for path in (source, workflow_test):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("protected", encoding="utf-8")
        request = {"request_id": "repair-2", "source": "evaluation",
                   "review": {"decision": "REVISE_TESTS", "evidence": ["Spec mismatch"],
                              "actionable_items": ["Correct application fixture."]}}

        def repair(_prompt, _messages):
            source.write_text("forbidden production edit", encoding="utf-8")
            workflow_test.write_text("forbidden workflow edit", encoding="utf-8")
            return 0

        with patch.object(qa_agent, "execute_preparation", REAL_QA_PREPARATION), \
                patch.object(qa_agent, "run_qa", side_effect=repair):
            with self.assertRaises(qa_agent.QAFileProtectionError):
                REAL_QA_TEST_REPAIR(self.story, request)
        self.assertEqual(source.read_text(encoding="utf-8"), "protected")
        self.assertEqual(workflow_test.read_text(encoding="utf-8"), "protected")

    def test_interface_alignment_repair_is_restricted_to_names_and_setup(self):
        original = raw_plan(prepared_test_paths=[self.test_file])
        self.test_path.write_text("old", encoding="utf-8")
        qa_agent.save_plan(self.story, original, owned_test_paths=[self.test_file])
        request = {"request_id": "iface-1", "source": "interface_deviation",
                   "review": {"decision": "REVISE_TESTS", "evidence": ["test expects Foo.bar"],
                              "actionable_items": ["Align names."]}}
        prompts = []

        def repair(prompt, messages):
            prompts.append(prompt)
            self.test_path.write_text("aligned", encoding="utf-8")
            messages.append("```json\n" + json.dumps(original) + "\n```")
            return 0

        with patch.object(qa_agent, "execute_preparation", REAL_QA_PREPARATION),                 patch.object(qa_agent, "run_qa", side_effect=repair):
            REAL_QA_TEST_REPAIR(self.story, request)
        self.assertIn("INTERFACE ALIGNMENT ONLY", prompts[0])
        self.assertIn("Do not change expected values", prompts[0])

    def test_implementation_contract_tells_the_coder_how_to_report_interface_deviations(self):
        text = qa_agent.implementation_contract(raw_plan())
        self.assertIn("interface_contract", text)
        self.assertIn(qa_agent.INTERFACE_DEVIATIONS_HEADING, text)

    def test_workflow_and_agent_runtime_tests_are_not_qa_writable(self):
        for path in ("workflow_service/test_engine.py", "agent/runtime/tests/test_story.py"):
            with self.assertRaises(qa_agent.QAPlanError):
                qa_agent.validate_plan(raw_plan(prepared_test_paths=[path]), "STORY-QA-001", [path])

    def test_interface_contract_must_be_a_list(self):
        with self.assertRaises(qa_agent.QAPlanError):
            qa_agent.validate_plan(raw_plan(interface_contract="Foo.bar()"), "STORY-QA-001")
        plan = qa_agent.validate_plan(raw_plan(interface_contract=["Foo.bar(int): int"]), "STORY-QA-001")
        self.assertEqual(plan["interface_contract"], ["Foo.bar(int): int"])

    def test_interface_deviations_are_read_only_from_their_own_section(self):
        text = "\n".join(["# Result", "- not a deviation", "## Interface Deviations",
                          "- test expects Foo.bar, production offers Foo.baz", "* ctor takes two args",
                          "## Next", "- unrelated"])
        self.assertEqual(qa_agent.parse_interface_deviations(text),
                         ["test expects Foo.bar, production offers Foo.baz", "ctor takes two args"])
        self.assertEqual(qa_agent.parse_interface_deviations("## Interface Deviations\nNone.\n"), [])
        self.assertEqual(qa_agent.parse_interface_deviations("no sections at all"), [])

    def test_test_files_written_by_the_coder_are_reverted(self):
        edited, added = self.root / "src/test/java/EditedTest.java", self.root / "src/test/java/AddedTest.java"
        edited.parent.mkdir(parents=True, exist_ok=True)
        edited.write_text("original", encoding="utf-8")
        with patch.object(qa_agent, "_git_paths", side_effect=lambda: {
                p for p in ("src/test/java/EditedTest.java", "src/test/java/AddedTest.java")
                if (self.root / p).exists()}):
            before = qa_agent.snapshot_test_files()
            edited.write_text("coder edit", encoding="utf-8")
            added.write_text("coder test", encoding="utf-8")
            touched = qa_agent.revert_test_changes(before)
        self.assertEqual(touched, ["src/test/java/AddedTest.java", "src/test/java/EditedTest.java"])
        self.assertEqual(edited.read_text(encoding="utf-8"), "original")
        self.assertFalse(added.exists())

    def test_coder_request_does_not_authorize_test_repair(self):
        request = {"request_id": "repair-3", "review": {"decision": "RETRY", "evidence": ["coder claim"],
                                                         "actionable_items": ["weaken test"]}}
        with patch.object(qa_agent, "run_qa") as writer:
            with self.assertRaises(qa_agent.QAPlanError):
                REAL_QA_TEST_REPAIR(self.story, request)
        writer.assert_not_called()

    def test_clarification_uses_standard_product_owner_decision(self):
        plan = raw_plan(
            status="NEEDS_USER", rationale="The product expectation is unresolved.",
            test_specifications=[], clarifications=["Should zero-value rows be hidden or shown?"],
        )
        result = qa_agent.save_plan(self.story, plan)
        self.assertEqual(result["user_decision_ids"], ["UD-001"])
        decision_file = next((self.root / "agent/user-decisions").glob("UD-*.md"))
        decision = decision_file.read_text(encoding="utf-8")
        self.assertIn("OPEN", decision)
        self.assertIn("Should zero-value rows be hidden or shown?", decision)


if __name__ == "__main__":
    unittest.main()


class ConditionalReviewPromptTest(unittest.TestCase):
    """The review runs before publication, so it must not demand CI evidence."""

    def test_review_prompt_explains_ci_timing_and_reserves_needs_user_for_product_decisions(self):
        from agent.runtime.tests import REAL_RUN_CONDITIONAL_REVIEW

        prompts = []

        def fake_run_qa(prompt, messages, read_only):
            prompts.append(prompt)
            messages.append('```json\n{"decision": "APPROVE", "reason": "ok", "evidence": [], "actionable_items": []}\n```')
            return 0

        story = config.REPO_ROOT / "agent" / "stories" / "STORY-T-001-x.md"
        with patch.object(qa_agent, "run_qa", side_effect=fake_run_qa):
            review = REAL_RUN_CONDITIONAL_REVIEW(story_path=story, plan={"story_id": "STORY-T-001"})

        self.assertEqual(review["decision"], "APPROVE")
        self.assertIn("Do not require that future CI result as a pre-acceptance condition", prompts[0])
        self.assertIn("NEEDS_USER is only for a product decision", prompts[0])

    def test_ci_review_reads_failure_evidence_and_can_request_test_corrections(self):
        from agent.runtime.tests import REAL_RUN_CONDITIONAL_REVIEW

        def review(prompt, messages, read_only):
            self.assertTrue(read_only)
            self.assertIn("GitHub CI has failed", prompt)
            self.assertNotIn("Do not require that future CI result", prompt)
            self.assertIn("failing assertion", prompt)
            messages.append('```json\n{"decision":"REVISE_TESTS","reason":"Spec mismatch",'
                            '"evidence":["Spec defines zero"],"actionable_items":["Correct expected value"]}\n```')
            return 0

        with patch.object(qa_agent, "run_qa", side_effect=review):
            result = REAL_RUN_CONDITIONAL_REVIEW(
                config.REPO_ROOT / "agent/stories/STORY-T-001-x.md", {},
                evaluation={"source": "ci_failure", "verification": {"report": "failing assertion"}})
        self.assertEqual(result["decision"], "REVISE_TESTS")

    def test_test_correction_without_independent_evidence_is_rejected(self):
        from agent.runtime.tests import REAL_RUN_CONDITIONAL_REVIEW

        def review(_prompt, messages, read_only):
            messages.append('```json\n{"decision":"REVISE_TESTS","reason":"Coder dislikes test",'
                            '"evidence":[],"actionable_items":["Change test"]}\n```')
            return 0

        with patch.object(qa_agent, "run_qa", side_effect=review):
            with self.assertRaisesRegex(qa_agent.QAPlanError, "requires evidence"):
                REAL_RUN_CONDITIONAL_REVIEW(config.REPO_ROOT / "agent/stories/STORY-T-001-x.md", {})


class QAInterfaceDesignOwnershipTest(unittest.TestCase):
    """A missing production interface is QA's design task, never a Product Owner question."""

    def test_prompt_makes_interface_design_qa_owned_and_forbids_needs_user_for_it(self):
        story = config.REPO_ROOT / "agent" / "stories" / "STORY-T-001-x.md"
        prompt = qa_agent.build_qa_prompt(story, "story text", [])
        self.assertIn("Technical design is yours, not the Product Owner's", prompt)
        self.assertIn("Never return NEEDS_USER because an interface is absent", prompt)
