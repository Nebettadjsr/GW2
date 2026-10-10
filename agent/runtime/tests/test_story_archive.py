"""Milestone archiving: which stories move, and how BACKLOG.md follows them."""

import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch

from agent.runtime.core import story_archive

BACKLOG = """# Backlog

## Active

- STORY-T-004 | STORY-T-004-active.md | TODO | milestone-05 | deps: None

## To Do

## Done

Completed current-milestone story details remain in their canonical files.

- STORY-T-001 | STORY-T-001-done.md | DONE | milestone-05 | First finished story
- STORY-T-002 | STORY-T-002-older.md | DONE | milestone-04 | Older milestone story

## Archived

### Milestone 05

- `STORY-T-000` | [STORY-T-000-earlier.md](archive/milestone-05/STORY-T-000-earlier.md) | DONE | Earlier
"""


def story(status, milestone="milestone-05"):
    return f"# Story\n\n## Status\n\n{status}\n\n## Milestone\n\n{milestone}\n"


class ArchiveMilestoneTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.stories = Path(directory.name) / "stories"
        self.archive = self.stories / "archive"
        self.stories.mkdir()
        self.backlog = self.stories / "BACKLOG.md"
        self.backlog.write_text(BACKLOG, encoding="utf-8")
        self.pointer = Path(directory.name) / "CURRENT_STORY.md"
        self.pointer.write_text("agent/stories/STORY-T-004-active.md\n", encoding="utf-8")
        files = {
            "STORY-T-001-done.md": story("DONE"),
            "STORY-T-002-older.md": story("DONE", "milestone-04"),
            "STORY-T-003-open.md": story("TODO"),
            "STORY-T-004-active.md": story("DONE"),
            "STORY-T-005-untagged.md": "# Story\n\n## Status\n\nDONE\n",
        }
        for name, content in files.items():
            (self.stories / name).write_text(content, encoding="utf-8")
        stack = ExitStack()
        self.addCleanup(stack.close)
        for name, value in {"STORIES_DIR": self.stories, "ARCHIVE_DIR": self.archive,
                            "BACKLOG_FILE": self.backlog, "CURRENT_STORY_FILE": self.pointer}.items():
            stack.enter_context(patch.object(story_archive, name, value))

    def test_only_done_stories_of_the_milestone_are_moved_never_the_active_one(self):
        result = story_archive.archive_milestone_stories("milestone-05")

        self.assertEqual(result["archived"], ["STORY-T-001-done.md"])
        self.assertEqual(result["skipped_active"], ["STORY-T-004-active.md"])
        self.assertEqual(result["skipped_other_milestone"], ["STORY-T-002-older.md"])
        self.assertEqual(result["skipped_not_done"], ["STORY-T-003-open.md"])
        self.assertEqual(result["skipped_no_milestone"], ["STORY-T-005-untagged.md"])
        self.assertTrue((self.archive / "milestone-05" / "STORY-T-001-done.md").is_file())
        self.assertFalse((self.stories / "STORY-T-001-done.md").exists())

    def test_compact_done_row_moves_under_its_milestone_heading(self):
        # Regression: only backticked rows were recognised, so compact
        # `ID | file | ...` rows stayed in Done pointing at moved files.
        story_archive.archive_milestone_stories("milestone-05")
        backlog = self.backlog.read_text(encoding="utf-8")
        done, archived = backlog.split("## Done")[1].split("## Archived")

        self.assertNotIn("STORY-T-001-done.md", done)
        self.assertIn("STORY-T-002-older.md", done)
        milestone_05 = archived.split("### Milestone 05")[1]
        self.assertIn("STORY-T-000-earlier.md", milestone_05)
        self.assertIn("- STORY-T-001 | STORY-T-001-done.md | DONE | milestone-05 | First finished story",
                      milestone_05)
        self.assertEqual(backlog.count("STORY-T-001-done.md"), 1)

    def test_an_existing_archived_file_is_never_overwritten(self):
        target = self.archive / "milestone-05"
        target.mkdir(parents=True)
        (target / "STORY-T-001-done.md").write_text("already archived", encoding="utf-8")

        result = story_archive.archive_milestone_stories("milestone-05")

        self.assertEqual((result["archived"], result["conflicts"]), ([], ["STORY-T-001-done.md"]))
        self.assertEqual((target / "STORY-T-001-done.md").read_text(encoding="utf-8"), "already archived")
        self.assertFalse(result["backlog_updated"])

    def test_a_new_milestone_heading_is_created_and_none_placeholder_replaced(self):
        content = "## Done\n\n- `STORY-T-009-x.md`\n\n## Archived\n\n_(none)_\n"

        updated = story_archive.update_backlog_for_archive(content, "milestone-06", ["STORY-T-009-x.md"])

        self.assertNotIn("_(none)_", updated)
        self.assertIn("### Milestone 06\n\n- `STORY-T-009-x.md`", updated)
        self.assertNotIn("STORY-T-009-x.md", updated.split("## Archived")[0])

    def test_an_archived_story_missing_from_done_still_gets_an_archive_row(self):
        updated = story_archive.update_backlog_for_archive("## Done\n\n", "milestone-07", ["STORY-T-010-y.md"])

        self.assertIn("## Archived", updated)
        self.assertIn("- `STORY-T-010-y.md`", updated.split("### Milestone 07")[1])

    def test_invalid_slugs_and_archive_listing(self):
        for bad in ("milestone-5", "../milestone-05", "phase-05"):
            with self.subTest(slug=bad), self.assertRaises(ValueError):
                story_archive.resolve_archive_dir(bad)
        self.assertEqual(story_archive.milestone_slug_for_phase(5), "milestone-05")
        with self.assertRaises(ValueError):
            story_archive.milestone_slug_for_phase(-1)
        self.assertEqual(story_archive.list_archived_story_files(), [])
        story_archive.archive_milestone_stories("milestone-05")
        self.assertEqual([path.name for path in story_archive.list_archived_story_files("milestone-05")],
                         ["STORY-T-001-done.md"])
        self.assertTrue(story_archive.is_archived_path(self.archive / "milestone-05" / "STORY-T-001-done.md"))


if __name__ == "__main__":
    unittest.main()
