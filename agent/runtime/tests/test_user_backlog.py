"""User inbox navigation must not change saved story execution state."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from agent.runtime.core import backlog_writer, planning_context, story_state
from agent.runtime.human import user_decisions, user_interventions


BACKLOG = """# Backlog

## Active

- STORY-PERF-004 | STORY-PERF-004-acceptance.md | TODO | milestone-05 | deps: None

## To Do

## Blocked

- STORY-T-001 | STORY-T-001-test.md | BLOCKED | milestone-05 | deps: UD-001

## Superseded

## Done

## Archived

"""


class UserBacklogTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.backlog = self.root / "BACKLOG.md"
        self.backlog.write_text(BACKLOG, encoding="utf-8")
        self.decisions = self.root / "user-decisions"
        self.interventions = self.root / "user-interventions"
        self.decisions.mkdir()
        self.interventions.mkdir()
        for module, name, path in (
            (user_decisions, "USER_DECISIONS_DIR", self.decisions),
            (user_interventions, "USER_INTERVENTIONS_DIR", self.interventions),
        ):
            patcher = patch.object(module, name, path)
            patcher.start()
            self.addCleanup(patcher.stop)

    def request(self, name="UD-001-question.md", status="OPEN", answer="TODO"):
        directory = self.decisions if name.startswith("UD-") else self.interventions
        path = directory / name
        path.write_text(f"# {name.split('.')[0]}\n\n## Status\n\n{status}\n\n"
                        f"## User Decision\n\n{answer}\n", encoding="utf-8")
        return path

    def sync(self):
        return backlog_writer.sync_user_requests(self.backlog)

    def test_open_decisions_and_interventions_are_linked_between_sections(self):
        self.request()
        self.request("UI-002-STORY-T-001.md")
        self.request("UD-003-old.md", "RESOLVED", "Use existing contract.")
        self.assertEqual(self.sync(), [
            "- [UD-001](../user-decisions/UD-001-question.md)",
            "- [UI-002](../user-interventions/UI-002-STORY-T-001.md)",
        ])
        content = self.backlog.read_text(encoding="utf-8")
        self.assertLess(content.index("## Blocked"), content.index("## User"))
        self.assertLess(content.index("## User"), content.index("## Superseded"))
        self.assertNotIn("UD-003", content)
        self.assertEqual([], story_state.validate_backlog_entries(content))

    def test_empty_blocked_section_remains_separate(self):
        self.backlog.write_text("## Blocked\n\n## Superseded\n\n", encoding="utf-8")
        self.request()
        self.sync()
        content = self.backlog.read_text(encoding="utf-8")
        start, end = story_state.find_section_span(content, "Blocked")
        self.assertEqual("", content[start:end].strip())
        self.assertEqual([], story_state.validate_backlog_entries(content))

    def test_resolved_request_removed_without_deleting_evidence(self):
        path = self.request()
        self.sync()
        self.request(status="RESOLVED", answer="The explicitly selected option.")
        evidence = path.read_bytes()
        self.assertEqual([], self.sync())
        self.assertEqual(evidence, path.read_bytes())
        self.assertNotIn("[UD-001]", self.backlog.read_text(encoding="utf-8"))

    def test_answer_without_resolution_stays_listed(self):
        self.request(answer="A partial answer needing clarification.")
        self.assertEqual(1, len(self.sync()))
        self.assertEqual("OPEN", user_decisions.list_decisions()[0]["status"])

    def test_resolved_intervention_removed_but_other_request_remains(self):
        self.request()
        self.request("UI-002-STORY-T-001.md")
        self.sync()
        self.request("UI-002-STORY-T-001.md", "RESOLVED", "Access granted.")
        self.assertEqual(["- [UD-001](../user-decisions/UD-001-question.md)"], self.sync())

    def test_idempotent_refresh_preserves_all_story_sections_and_unrelated_files(self):
        self.request()
        pointer = self.root / "CURRENT_STORY.md"
        pointer.write_text("STORY-PERF-004-acceptance.md", encoding="utf-8")
        pointer_before = pointer.read_bytes()
        self.sync()
        content = self.backlog.read_text(encoding="utf-8")
        span = story_state.find_section_span(content, "User")
        # Remove only the newly generated heading and body: everything else is unchanged.
        heading = content.rfind("## User", 0, span[0])
        self.assertEqual(BACKLOG, content[:heading] + content[span[1] + 1:])
        before = self.backlog.stat().st_mtime_ns
        self.sync()
        self.assertEqual(before, self.backlog.stat().st_mtime_ns)
        self.assertEqual(pointer_before, pointer.read_bytes())
        self.assertEqual([], story_state.parse_backlog_section(content, "To Do"))

    def test_user_index_not_truncated_in_planner_context(self):
        for number in range(1, 50):
            self.request(f"UD-{number:03d}-question.md")
        rows = self.sync()
        index = planning_context.backlog_index(self.backlog.read_text(encoding="utf-8"), {})
        for row in rows:
            self.assertIn(row, index)

    def test_user_section_cannot_hide_stories_or_cross_reference_wrong_request(self):
        for row in (
            "- STORY-T-002 | STORY-T-002-hidden.md | TODO | milestone-05 | deps: None",
            "- [UD-001](../user-interventions/UI-001-test.md)",
            "- [UD-001](../user-decisions/UD-002-test.md)",
        ):
            with self.subTest(row=row):
                self.assertTrue(story_state.validate_backlog_entries("## User\n\n" + row + "\n"))

    def test_invalid_existing_story_index_is_not_written(self):
        content = BACKLOG.replace("## To Do\n", "## To Do\nnot a valid entry\n")
        self.backlog.write_text(content, encoding="utf-8")
        with self.assertRaises(backlog_writer.BacklogWriteRejected):
            self.sync()
        self.assertEqual(content, self.backlog.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
