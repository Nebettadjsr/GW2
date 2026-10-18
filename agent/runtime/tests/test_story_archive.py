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

## Superseded

- STORY-T-006 | STORY-T-006-replaced.md | SUPERSEDED | milestone-05 | deps: None

## Done

- STORY-T-001 | STORY-T-001-done.md | DONE | milestone-05 | First finished story
- STORY-T-002 | STORY-T-002-older.md | DONE | milestone-04 | Older milestone story
- STORY-T-007 | STORY-T-007-later.md | DONE | milestone-06 | Later milestone story

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
            "STORY-T-006-replaced.md": story("SUPERSEDED"),
            "STORY-T-007-later.md": story("DONE", "milestone-06"),
        }
        for name, content in files.items():
            (self.stories / name).write_text(content, encoding="utf-8")
        stack = ExitStack()
        self.addCleanup(stack.close)
        for name, value in {"STORIES_DIR": self.stories, "ARCHIVE_DIR": self.archive,
                            "BACKLOG_FILE": self.backlog, "CURRENT_STORY_FILE": self.pointer}.items():
            stack.enter_context(patch.object(story_archive, name, value))

    def test_finished_stories_up_to_the_milestone_move_to_their_own_folder(self):
        # Regression: only DONE stories of exactly the closed milestone
        # moved, so an older milestone's late finisher and every
        # SUPERSEDED story stayed in BACKLOG.md for good.
        result = story_archive.archive_milestone_stories("milestone-05")

        self.assertEqual(result["archived"],
                         ["STORY-T-001-done.md", "STORY-T-002-older.md", "STORY-T-006-replaced.md"])
        self.assertEqual(result["skipped_active"], ["STORY-T-004-active.md"])
        self.assertEqual(result["skipped_other_milestone"], ["STORY-T-007-later.md"])
        self.assertEqual(result["skipped_not_done"], ["STORY-T-003-open.md"])
        self.assertEqual(result["skipped_no_milestone"], ["STORY-T-005-untagged.md"])
        self.assertTrue((self.archive / "milestone-05" / "STORY-T-001-done.md").is_file())
        self.assertTrue((self.archive / "milestone-04" / "STORY-T-002-older.md").is_file())
        self.assertTrue((self.archive / "milestone-05" / "STORY-T-006-replaced.md").is_file())
        self.assertFalse((self.stories / "STORY-T-001-done.md").exists())

    def test_archived_rows_leave_the_backlog_and_the_history_index_is_dropped(self):
        story_archive.archive_milestone_stories("milestone-05")
        backlog = self.backlog.read_text(encoding="utf-8")

        for name in ("STORY-T-001-done.md", "STORY-T-002-older.md", "STORY-T-006-replaced.md",
                     "STORY-T-000-earlier.md"):
            self.assertNotIn(name, backlog)
        self.assertNotIn("## Archived", backlog)
        self.assertIn("- STORY-T-007 | STORY-T-007-later.md | DONE | milestone-06", backlog)
        self.assertIn("STORY-T-004-active.md", backlog.split("## To Do")[0])
        self.assertIn("## Superseded\n\n## Done", backlog)

    def test_an_existing_archived_file_is_never_overwritten(self):
        target = self.archive / "milestone-05"
        target.mkdir(parents=True)
        (target / "STORY-T-001-done.md").write_text("already archived", encoding="utf-8")

        result = story_archive.archive_milestone_stories("milestone-05")

        self.assertEqual(result["conflicts"], ["STORY-T-001-done.md"])
        self.assertNotIn("STORY-T-001-done.md", result["archived"])
        self.assertEqual((target / "STORY-T-001-done.md").read_text(encoding="utf-8"), "already archived")
        self.assertTrue((self.stories / "STORY-T-001-done.md").is_file())
        self.assertIn("STORY-T-001-done.md", self.backlog.read_text(encoding="utf-8"))

    def test_legacy_rows_are_dropped_and_sections_after_archived_survive(self):
        content = ("## Done\n\n- `STORY-T-009-x.md`\n- `STORY-T-011-z.md`\n\n"
                   "## Archived\n\n### Milestone 01\n\n- `STORY-T-008-w.md`\n\n## Notes\n\nkeep\n")

        updated = story_archive.update_backlog_for_archive(content, ["STORY-T-009-x.md"])

        self.assertEqual(updated, "## Done\n\n- `STORY-T-011-z.md`\n\n## Notes\n\nkeep\n")

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
                         ["STORY-T-001-done.md", "STORY-T-006-replaced.md"])
        self.assertTrue(story_archive.is_archived_path(self.archive / "milestone-05" / "STORY-T-001-done.md"))


if __name__ == "__main__":
    unittest.main()
