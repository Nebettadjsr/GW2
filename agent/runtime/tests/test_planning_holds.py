"""Regression tests for blocked planning: all state is temporary, all models mocked."""
import json
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import Mock, patch

from agent.runtime.core import orchestrator as loop, project_planner as planner
from agent.runtime.human import user_decisions as decisions


class PlanningHoldTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        for name, relative in {
            "REPO_ROOT": ".", "BACKLOG_FILE": "agent/stories/BACKLOG.md",
            "CURRENT_STORY_FILE": "agent/CURRENT_STORY.md",
            "PROJECT_STATE_FILE": "agent/PROJECT_STATE.md", "ROADMAP_FILE": "docs/ROADMAP.md",
            "TARGET_ARCHITECTURE_FILE": "docs/TARGET_ARCHITECTURE.md",
            "USER_DECISIONS_DIR": "agent/user-decisions",
            "PRODUCT_OWNER_REQUESTS_DIR": "agent/product-owner-requests",
            "ARCHITECT_REQUESTS_DIR": "agent/architect-requests",
        }.items():
            value = self.root / relative
            if value.suffix:
                value.parent.mkdir(parents=True, exist_ok=True)
                value.write_text("initial", encoding="utf-8")
            else:
                value.mkdir(parents=True, exist_ok=True)
            self.stack.enter_context(patch.object(loop, name, value))
        self.ud = loop.USER_DECISIONS_DIR / "UD-010-non-tp-control-disabled-behavior.md"
        self.ud.write_text("## Status\n\nOPEN\n", encoding="utf-8")
        self.cache = self.root / "artifacts/PLANNING_CACHE.json"
        self.claude, self.codex = Mock(), Mock()
        self.claude.available.return_value = False
        self.codex.available.return_value = True
        self.stack.enter_context(patch.object(loop, "log_line"))
        self.plan = self.stack.enter_context(patch.object(loop, "run_planning_pass", return_value={
            "status": "NEEDS_USER", "user_decision_ids": ["UD-010"]}))
        self.scheduler = self.new_scheduler()

    def new_scheduler(self):
        return loop.CapacityScheduler(self.claude, self.codex, cache_file=self.cache)

    def test_first_pass_then_unchanged_wait_and_restart_do_not_retry(self):
        self.assertFalse(self.scheduler.plan_if_useful(idle=True))
        for _ in range(5):
            self.assertFalse(self.scheduler.plan_if_useful(idle=True))
        self.assertFalse(self.new_scheduler().plan_if_useful(idle=True))
        self.plan.assert_called_once()
        self.assertEqual(json.loads(self.cache.read_text())["status"], "NEEDS_USER")

    def test_each_meaningful_input_change_resumes_planning(self):
        self.scheduler.plan_if_useful(idle=True)
        paths = [self.ud, loop.PRODUCT_OWNER_REQUESTS_DIR / "new.md",
                 loop.ARCHITECT_REQUESTS_DIR / "AR-001-question.md",
                 loop.BACKLOG_FILE, loop.BACKLOG_FILE.parent / "STORY-X-001.md",
                 loop.PROJECT_STATE_FILE, loop.ROADMAP_FILE,
                 self.root / "docs/DOMAIN_SPEC.md"]
        for index, path in enumerate(paths):
            with self.subTest(path=path):
                path.write_text("## Status\n\nRESOLVED\n" if path == self.ud else "changed")
                self.scheduler.plan_if_useful(idle=True)
                self.scheduler.plan_if_useful(idle=True)
                self.assertEqual(self.plan.call_count, index + 2)
        paths[-1].unlink()
        self.scheduler.plan_if_useful(idle=True)
        self.assertEqual(self.plan.call_count, len(paths) + 2)

    def test_open_ud_content_change_and_new_unrelated_request_wake_wait(self):
        for path in (self.ud, loop.PRODUCT_OWNER_REQUESTS_DIR / "unrelated.md"):
            with self.subTest(path=path), patch.object(loop, "get_unresolved_user_decisions",
                    return_value=[{"id": "UD-010", "status": "OPEN", "file": self.ud.name}]), \
                 patch.object(loop.time, "sleep", side_effect=lambda _: path.write_text("new input")) as sleep:
                loop.wait_for_user_decisions(["UD-010"])
                sleep.assert_called_once()

    def test_needs_user_with_independent_stories_caches_post_pass_state(self):
        def plan():
            loop.BACKLOG_FILE.write_text("independent story added")
            return {"status": "NEEDS_USER", "user_decision_ids": ["UD-010"],
                    "story_files_created": ["STORY-X-001.md"]}
        self.plan.side_effect = plan
        self.assertTrue(self.scheduler.plan_if_useful(idle=True))
        self.assertFalse(self.scheduler.plan_if_useful(idle=True))
        self.plan.assert_called_once()

    def test_final_blocked_pass_owns_its_writes_and_editor_line_endings_do_not_retrigger(self):
        request = loop.PRODUCT_OWNER_REQUESTS_DIR / "Request-008.md"
        story = loop.BACKLOG_FILE.parent / "STORY-DOM-022.md"
        new_ud = loop.USER_DECISIONS_DIR / "UD-011-choice.md"
        result_path = self.root / "agent/runtime/artifacts/PLANNING_RESULT.json"
        result_path.parent.mkdir(parents=True)
        def plan():
            request.write_bytes(b"## Status\n\nNEEDS_USER\n\n## Planner Resolution\n\nUD-011\n")
            story.write_text("## Status\n\nTODO\n")
            new_ud.write_text("## Status\n\nOPEN\n")
            loop.BACKLOG_FILE.write_text("Independent STORY-DOM-022 queued")
            result = {"status": "NEEDS_USER", "independent_work_remaining": False,
                      "story_files_created": [story.name], "user_decision_ids": ["UD-011"]}
            result_path.write_text(json.dumps(result))
            return result
        self.plan.side_effect = plan
        self.assertTrue(self.scheduler.plan_if_useful(idle=True))
        hold = json.loads(self.cache.read_text())
        self.assertFalse(hold["independent_work_remaining"])
        self.assertIn("agent/product-owner-requests/Request-008.md", hold["planner_changed_inputs"])
        self.assertEqual(hold["inputs"], loop.planning_input_snapshot())
        # Reproduce the observed delayed editor save after the planner's pass.
        request.write_bytes(request.read_bytes().replace(b"\n", b"\r\n"))
        result_path.write_text('{"rewritten":true}')
        for _ in range(4):
            self.assertFalse(self.scheduler.plan_if_useful(idle=True))
        self.assertFalse(self.new_scheduler().plan_if_useful(idle=True))
        self.plan.assert_called_once()
        # A real answer is new information even while Claude remains unavailable.
        new_ud.write_text("## Status\n\nRESOLVED\n\n## User Decision\n\nChosen policy\n")
        self.scheduler.plan_if_useful(idle=True)
        self.assertEqual(self.plan.call_count, 2)

    def test_unavailable_claude_only_polls_locally_after_final_needs_user(self):
        self.plan.return_value = {"status": "NEEDS_USER", "independent_work_remaining": False,
                                  "user_decision_ids": ["UD-010"]}
        self.claude.available.side_effect = [False, False, False, True]
        with patch.object(loop, "get_actionable_architect_requests", return_value=[]), \
             patch.object(loop.time, "sleep") as sleep:
            self.scheduler.wait_for_claude()
        self.plan.assert_called_once()
        self.assertEqual(sleep.call_count, 3)

    def test_true_flag_continues_bounded_pass_then_false_holds(self):
        def plan():
            count = self.plan.call_count
            loop.BACKLOG_FILE.write_text(f"Batch {count}")
            return {"status": "COMPLETE" if count == 1 else "NEEDS_USER",
                    "independent_work_remaining": count == 1,
                    "story_files_created": [f"story-{count}.md"], "user_decision_ids": ["UD-010"]}
        self.plan.side_effect = plan
        self.assertTrue(self.scheduler.plan_if_useful(idle=True))
        self.assertTrue(self.scheduler.plan_if_useful(idle=True))
        self.assertFalse(self.scheduler.plan_if_useful(idle=True))
        self.assertEqual(self.plan.call_count, 2)

    def test_cosmetic_bom_and_tests_are_ignored_but_po_content_is_a_trigger(self):
        self.scheduler.plan_if_useful(idle=True)
        self.ud.write_bytes(b"\xef\xbb\xbf" + self.ud.read_bytes())
        for path in (self.root / "agent/runtime/tests/new_test.py", self.root / "agent/runtime/runners/display.py"):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("# Display/test maintenance")
        self.assertFalse(self.scheduler.plan_if_useful(idle=True))
        request = loop.PRODUCT_OWNER_REQUESTS_DIR / "new.md"
        request.write_text("New product requirement")
        self.scheduler.plan_if_useful(idle=True)
        request.write_text("Changed product requirement")
        self.scheduler.plan_if_useful(idle=True)
        self.assertEqual(self.plan.call_count, 3)

    def test_implementation_result_and_actual_architect_changes_are_triggers(self):
        self.scheduler.plan_if_useful(idle=True)
        with patch.object(loop, "run_architect_pass", return_value={"status": "COMPLETE"}):
            self.scheduler.answer_architect_request({"file": "AR-001.md"})
        self.assertFalse(self.scheduler.plan_if_useful(idle=True))
        (loop.ARCHITECT_REQUESTS_DIR / "AR-001.md").write_text("## Status\n\nRESOLVED\n")
        self.scheduler.plan_if_useful(idle=True)
        result = self.root / "agent/runtime/artifacts/CLAUDE_RESULT.md"
        result.parent.mkdir(parents=True, exist_ok=True)
        result.write_text("New implementation evidence")
        self.scheduler.plan_if_useful(idle=True)
        self.assertEqual(self.plan.call_count, 3)

    def test_validation_failure_is_held_without_capacity_retry(self):
        self.plan.return_value = {"status": "FAILED", "validation_problems": ["bad JSON shape"]}
        self.scheduler.plan_if_useful(idle=True)
        for _ in range(3):
            self.new_scheduler().plan_if_useful(idle=True)
        self.plan.assert_called_once()
        self.codex.defer.assert_not_called()
        cached = json.loads(self.cache.read_text())
        self.assertEqual(cached["status"], "FAILED")
        self.assertEqual(cached["validation_problems"], ["bad JSON shape"])
        self.cache.unlink()
        self.scheduler.plan_if_useful(idle=True)
        self.assertEqual(self.plan.call_count, 2)

    def test_exception_is_held_and_logs_results_capacity_do_not_invalidate(self):
        self.plan.side_effect = ValueError("malformed output")
        self.scheduler.plan_if_useful(idle=True)
        (self.root / "artifacts/PLANNING_RESULT.json").write_text('{}')
        (self.root / "agent/logs").mkdir()
        (self.root / "agent/logs/today.log").write_text('heartbeat')
        self.codex.available.return_value = False
        self.scheduler.plan_if_useful(idle=True)
        self.codex.available.return_value = True
        self.scheduler.plan_if_useful(idle=True)
        self.plan.assert_called_once()
        self.assertEqual(self.scheduler.planning_hold["status"], "FAILED")


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
