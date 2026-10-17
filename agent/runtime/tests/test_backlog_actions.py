"""Planner backlog actions: the planner asks, the harness writes BACKLOG.md.

Run with: python -m unittest agent.runtime.tests.test_backlog_actions -v
"""
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch

from agent.runtime.core import backlog_writer, story_state
from agent.runtime.support import config

QUEUE = ("## Active\n\n## To Do\n\n"
         "- STORY-T-001 | STORY-T-001-a.md | TODO | milestone-05 | deps: None\n"
         "- STORY-T-002 | STORY-T-002-b.md | TODO | milestone-05 | deps: None\n\n"
         "## Blocked\n\n## Superseded\n\n## Done\n\n## Archived\n")


class BacklogActionsTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.stories = Path(directory.name).resolve()
        self.backlog = self.stories / "BACKLOG.md"
        self.backlog.write_text(QUEUE, encoding="utf-8")
        for number, status, dependencies in ((1, "TODO", "None"), (2, "TODO", "None"),
                                             (3, "TODO", "STORY-T-001"), (4, "BLOCKED", "None")):
            self.story(number, status, dependencies)
        stack = ExitStack()
        self.addCleanup(stack.close)
        stack.enter_context(patch.object(config, "STORIES_DIR", self.stories))

    def story(self, number, status, dependencies):
        letter = "abcd"[number - 1]
        (self.stories / f"STORY-T-00{number}-{letter}.md").write_text(
            f"## Story ID\n\nSTORY-T-00{number}\n\n## Status\n\n{status}\n\n## Milestone\n\nmilestone-05\n\n"
            f"## Dependencies\n\n{dependencies}\n\n## Blockers\n\nNone.\n", encoding="utf-8")

    def section(self, heading):
        return story_state.parse_backlog_section(self.backlog.read_text(encoding="utf-8"), heading)

    def apply(self, *actions):
        return backlog_writer.apply_backlog_actions(list(actions), backlog_file=self.backlog)

    def test_add_derives_the_entry_from_the_story_file(self):
        self.assertEqual(self.apply({"command": "add_to_do", "story": "STORY-T-003"}), [])
        self.assertIn("- STORY-T-003 | STORY-T-003-c.md | TODO | milestone-05 | deps: STORY-T-001",
                      self.backlog.read_text(encoding="utf-8"))
        self.assertEqual(self.apply({"command": "add_to_do", "story": "STORY-T-003-c.md"}), [])
        self.assertEqual(self.section("To Do").count("STORY-T-003-c.md"), 1)

    def test_a_story_created_blocked_is_listed_under_blocked(self):
        self.assertEqual(self.apply({"command": "add_to_do", "story": "STORY-T-004"}), [])
        self.assertEqual(self.section("Blocked"), ["STORY-T-004-d.md"])

    def test_move_is_a_harness_transition_of_entry_and_story(self):
        self.assertEqual(self.apply({"command": "move", "story": "STORY-T-002", "to": "Blocked",
                                     "reason": "Waits for milestone-05."}), [])
        self.assertEqual(self.section("Blocked"), ["STORY-T-002-b.md"])
        content = (self.stories / "STORY-T-002-b.md").read_text(encoding="utf-8")
        self.assertEqual(story_state.classify_story_status(story_state.extract_status_section(content)), "BLOCKED")
        self.assertIn("Waits for milestone-05.", content)
        self.assertIn("| BLOCKED |", self.backlog.read_text(encoding="utf-8"))

    def test_reorder_needs_the_complete_to_do_list(self):
        self.assertEqual(self.apply({"command": "reorder_to_do", "order": ["STORY-T-002", "STORY-T-001"]}), [])
        self.assertEqual(self.section("To Do"), ["STORY-T-002-b.md", "STORY-T-001-a.md"])
        problems = self.apply({"command": "reorder_to_do", "order": ["STORY-T-001"]})
        self.assertIn("must list every '## To Do' story", problems[0])

    def test_invalid_actions_write_nothing(self):
        cases = [{"command": "rewrite_everything"}, {"command": "add_to_do", "story": "STORY-T-999"},
                 {"command": "move", "story": "STORY-T-001", "to": "Done"},
                 {"command": "move", "story": "STORY-T-001", "to": "Blocked"}]
        for action in cases:
            with self.subTest(action=action):
                self.assertTrue(self.apply({"command": "add_to_do", "story": "STORY-T-003"}, action))
                self.assertEqual(self.backlog.read_text(encoding="utf-8"), QUEUE)

    def test_active_entries_cannot_be_moved(self):
        self.backlog.write_text(QUEUE.replace("## Active\n\n", "## Active\n\n- STORY-T-003 | STORY-T-003-c.md | "
                                              "TODO | milestone-05 | deps: STORY-T-001\n\n"), encoding="utf-8")
        problems = self.apply({"command": "move", "story": "STORY-T-003", "to": "Blocked", "reason": "x"})
        self.assertIn("Cannot move STORY-T-003-c.md", problems[0])


if __name__ == "__main__":
    unittest.main()
