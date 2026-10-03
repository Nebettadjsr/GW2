"""Tests for QA plan validation, persistence, write guards, and role configuration."""

import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from agent.runtime.qa import qa_agent
from agent.runtime.runners import local_planner_runner
from agent.runtime.support import config
from agent.runtime.support.capacity import ModelCapacityUnavailable
from agent.runtime.tests import REAL_QA_PREPARATION, REAL_RUN_QA
from agent.runtime.human import user_decisions


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
        self.assertTrue((self.root / "agent/qa-plans/QA-STORY-QA-001.json").is_file())
        self.assertTrue((self.root / "agent/runtime/artifacts/qa-tests" / self.test_file).is_file())

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
