"""Regression tests for blocked planning: all state is temporary, all models mocked."""
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch

from agent.runtime.core import orchestrator as loop, project_planner as planner
from agent.runtime.human import user_decisions as decisions


class PlanningInputNormalizationTest(unittest.TestCase):
    def test_story_status_results_and_findings_do_not_change_contract_fingerprint(self):
        original = """## Story ID

STORY-WEB-001

## Acceptance Criteria

- The page displays the requested result.

## Status

TODO

## Result

Initial result.

## Follow-up Findings

F001: Possible issue.
"""
        completed = original.replace("TODO", "DONE").replace(
            "Initial result.", "Implementation complete.").replace(
            "Possible issue.", "Already investigated.")
        self.assertEqual(loop._stable_story_planning_content(original),
                         loop._stable_story_planning_content(completed))
        changed_requirement = completed.replace(
            "The page displays the requested result.",
            "The page displays the requested result and its source.")
        self.assertNotEqual(loop._stable_story_planning_content(original),
                            loop._stable_story_planning_content(changed_requirement))


class PlanningValidationTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.ud = self.root / "UD-010-non-tp-control-disabled-behavior.md"
        self.ud.write_text("## Status\n\nOPEN\n", encoding="utf-8")
        stack = ExitStack()
        self.addCleanup(stack.close)
        for module, name, value in [(decisions, "USER_DECISIONS_DIR", self.root),
                                     (planner, "USER_DECISIONS_DIR", self.root),
                                     (planner, "STORIES_DIR", self.root)]:
            stack.enter_context(patch.object(module, name, value))
        for name, value in [("file_hash", None), ("_git_dirty_src_lines", set()),
                            ("_adr_snapshot", {}), ("validate_request_updates", [])]:
            stack.enter_context(patch.object(planner, name, return_value=value))
        stack.enter_context(patch.object(planner.architect_requests, "validate_planner_updates", return_value=[]))
        stack.enter_context(patch.object(planner.architect_requests, "find_duplicate_request_ids", return_value=[]))

    def validate(self, result):
        if isinstance(result, dict):
            result = dict(result, independent_work_remaining=False, phase_review=[
                {"area": "Control behavior", "outcome": "USER_DECISION", "references": ["UD-010"]}])
        return planner.validate_planning_result(result, None, set(), set(), set(),
                                                decisions.list_decisions(), {}, {}, {})

    def test_stable_id_resolves_without_markdown_title(self):
        result = self.validate({"status": "NEEDS_USER", "user_decision_ids": ["UD-010"]})
        self.assertEqual(result["status"], "NEEDS_USER", result)

    def test_duplicate_filename_id_rejected_deterministically(self):
        (self.root / "UD-010-other.md").write_text("## Status\n\nOPEN\n")
        result = self.validate({"status": "NEEDS_USER", "user_decision_ids": ["UD-010"]})
        self.assertEqual(result["status"], "FAILED")
        self.assertIn("Duplicate user-decision IDs", str(result["validation_problems"]))

    def test_malformed_output_is_failed_not_needs_user(self):
        for raw in ([], {"status": "NEEDS_USER"},
                    {"status": "NEEDS_USER", "user_decision_ids": [{}]},
                    {"status": "NEEDS_USER", "user_decision_ids": [self.ud.name]}):
            with self.subTest(raw=raw):
                result = self.validate(raw)
                self.assertEqual(result["status"], "FAILED")
                self.assertTrue(result["validation_problems"])

    def test_independent_story_is_valid_on_needs_user(self):
        name = "STORY-TEST-001-independent.md"
        sections = {heading: "None." for heading in planner.REQUIRED_STORY_HEADINGS}
        sections.update({"Story ID": "STORY-TEST-001", "Status": "TODO", "Milestone": "milestone-00"})
        (self.root / name).write_text("\n\n".join(f"## {key}\n\n{value}" for key, value in sections.items()))
        result = self.validate({"status": "NEEDS_USER", "user_decision_ids": ["UD-010"],
                                "story_files_created": [name]})
        self.assertEqual(result["status"], "NEEDS_USER", result)
