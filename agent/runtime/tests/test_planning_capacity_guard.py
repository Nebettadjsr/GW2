import tempfile
import unittest
from pathlib import Path
from contextlib import ExitStack
from unittest.mock import patch

from agent.runtime.core import project_planner
from agent.runtime.human import architect_requests
from agent.runtime.support.capacity import ModelCapacityUnavailable


class PlanningCapacityGuardTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.agent = self.root / "agent"
        self.agent.mkdir()
        self.active = self.agent / "active.md"
        self.active.write_text("## Status\n\nUNFINISHED\n", encoding="utf-8")
        self.pointer = self.agent / "CURRENT_STORY.md"
        self.pointer.write_text("agent/active.md", encoding="utf-8")
        self.backlog = self.agent / "BACKLOG.md"
        self.backlog.write_text("Original queue", encoding="utf-8")
        self.result = self.agent / "PLANNING_RESULT.json"
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        for name, value in (("REPO_ROOT", self.root), ("CURRENT_STORY_FILE", self.pointer),
                            ("BACKLOG_FILE", self.backlog),
                            ("PLANNING_RESULT_FILE", self.result)):
            self.stack.enter_context(patch.object(project_planner, name, value))
        self.stack.enter_context(patch.object(project_planner, "get_active_story_path", return_value=self.active))

    def test_exhausted_planner_rolls_back_partial_queue_and_new_story(self):
        new_story = self.agent / "new.md"
        def run(prompt):
            self.assertIn("Do not modify it", prompt)
            self.backlog.write_text("partial queue")
            new_story.write_text("partial story")
            self.result.write_text('{"status":"COMPLETE"}')
            raise ModelCapacityUnavailable("exhausted")
        with patch.object(project_planner, "run_local_planner", side_effect=run), \
             self.assertRaises(ModelCapacityUnavailable):
            project_planner._run_guarded_planner("plan")
        self.assertEqual(self.backlog.read_text(), "Original queue")
        self.assertFalse(new_story.exists())
        self.assertFalse(self.result.exists())

    def test_previous_result_is_removed_so_identical_fresh_output_is_valid(self):
        content = '{"status":"NEEDS_USER","user_decision_ids":["UD-010"]}'
        self.result.write_text(content)
        def run(prompt):
            self.assertFalse(self.result.exists())
            self.result.write_text(content)
            return 0
        with patch.object(project_planner, "run_local_planner", side_effect=run):
            self.assertEqual(project_planner._run_guarded_planner("plan"), 0)
        self.assertEqual(self.result.read_text(), content)

    def test_active_story_and_pointer_are_protected_even_when_planner_exits_zero(self):
        for path in (self.active, self.pointer):
            before = path.read_bytes()
            def run(prompt):
                path.write_text("unauthorized change")
                return 0
            with patch.object(project_planner, "run_local_planner", side_effect=run), \
                 self.assertRaises(RuntimeError):
                project_planner._run_guarded_planner("plan")
            self.assertEqual(path.read_bytes(), before)

    def test_successful_planning_keeps_changes_and_active_story(self):
        original = self.active.read_bytes()
        def run(prompt):
            self.backlog.write_text("new queue")
            return 0
        with patch.object(project_planner, "run_local_planner", side_effect=run):
            self.assertEqual(project_planner._run_guarded_planner("plan"), 0)
        self.assertEqual(self.backlog.read_text(), "new queue")
        self.assertEqual(self.active.read_bytes(), original)

    def test_planner_may_not_answer_or_edit_an_architect_request(self):
        # The planner asks architecture questions; only ARCHITECTURE MODE
        # answers them (AGENTS.md's "No Implicit Role Switching").
        requests_dir = self.agent / "architect-requests"
        requests_dir.mkdir()
        request = requests_dir / "AR-001-frontend.md"
        request.write_text("# AR-001 — Q\n\n## Status\n\nOPEN\n", encoding="utf-8")
        before = request.read_bytes()

        def run(prompt):
            request.write_text(
                "# AR-001 — Q\n\n## Status\n\nRESOLVED\n", encoding="utf-8"
            )
            return 0

        with patch.object(architect_requests, "ARCHITECT_REQUESTS_DIR", requests_dir), \
             patch.object(project_planner, "run_local_planner", side_effect=run), \
             self.assertRaises(RuntimeError):
            project_planner._run_guarded_planner("plan")

        self.assertEqual(request.read_bytes(), before)

    def test_planner_may_not_rewrite_an_architecture_decision_record(self):
        adr_dir = self.agent / "decisions"
        adr_dir.mkdir()
        adr = adr_dir / "ADR-001-persistence.md"
        adr.write_text("# ADR-001\n\n## Status\n\nACCEPTED\n", encoding="utf-8")
        before = adr.read_bytes()

        def run(prompt):
            adr.write_text("# ADR-001\n\n## Status\n\nSUPERSEDED\n", encoding="utf-8")
            return 0

        with patch.object(project_planner, "ADR_DIR", adr_dir), \
             patch.object(project_planner, "run_local_planner", side_effect=run), \
             self.assertRaises(RuntimeError):
            project_planner._run_guarded_planner("plan")

        self.assertEqual(adr.read_bytes(), before)

    def test_cannot_transition_or_reassign_active_story_while_claude_paused(self):
        for change in (lambda: self.result.write_text('{"milestone_transition":true}'),
                       lambda: self.backlog.write_text('## Active\n\n- `STORY-DOM-098-other.md`\n')):
            def run(prompt):
                change()
                return 0
            with patch.object(project_planner, "run_local_planner", side_effect=run), \
                 self.assertRaises(RuntimeError):
                project_planner._run_guarded_planner("plan")
            self.assertEqual(self.backlog.read_text(), "Original queue")
            self.assertFalse(self.result.exists())
