"""
Deterministic tests for the AI-free story selector (selector.py).

Story selection must never call Hermes/Ollama -- BACKLOG.md's '## To
Do' section is the canonical execution order, and Python walks it top
to bottom picking the first entry get_selectable_story_candidates()
confirms is executable. These tests exercise select_next_story()
against synthetic, temporary BACKLOG.md/story/user-decision files (via
monkeypatched module path constants), never the real repository state.

Run with: python test_selector.py -v (from inside agent/runtime/), or
python -m unittest agent.runtime.test_selector -v from the repo root.

No application/domain Java code is touched by these tests.
"""

import sys
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))

import selector  # noqa: E402
import story_state  # noqa: E402
import user_decisions  # noqa: E402


STATUS_TODO = """## Status

TODO
"""

STATUS_DONE = """## Status

DONE
"""

STATUS_BLOCKED = """## Status

BLOCKED
"""

STATUS_TODO_WITH_BLOCKED_DEPENDENCY = """## Status

TODO

## Dependencies

Blocked until STORY-OTHER-001 reaches DONE.
"""


def story_body(status_block: str) -> str:
    return f"# Test story\n\n{status_block}\n"


class SelectorTestCase(unittest.TestCase):
    """
    Common fixture: a temp agent/stories/ dir (with BACKLOG.md +
    story files) and a temp agent/user-decisions/ dir, wired in place
    of the real repository paths via patched module attributes.
    """

    def setUp(self):
        self._tmpdir = tempfile.TemporaryDirectory()
        root = Path(self._tmpdir.name)

        self.stories_dir = root / "stories"
        self.stories_dir.mkdir()

        self.user_decisions_dir = root / "user-decisions"
        self.user_decisions_dir.mkdir()

        self.backlog_file = self.stories_dir / "BACKLOG.md"
        self.selector_result_file = root / "SELECTOR_RESULT.json"

        self._stack = ExitStack()
        self._stack.enter_context(
            patch.object(story_state, "STORIES_DIR", self.stories_dir)
        )
        self._stack.enter_context(
            patch.object(story_state, "BACKLOG_FILE", self.backlog_file)
        )
        self._stack.enter_context(
            patch.object(selector, "STORIES_DIR", self.stories_dir)
        )
        self._stack.enter_context(
            patch.object(
                selector, "SELECTOR_RESULT_FILE", self.selector_result_file
            )
        )
        self._stack.enter_context(
            patch.object(
                user_decisions,
                "USER_DECISIONS_DIR",
                self.user_decisions_dir,
            )
        )

        self.addCleanup(self._stack.close)
        self.addCleanup(self._tmpdir.cleanup)

    def write_backlog(self, todo_bullets: list[str]) -> None:
        bullets = "\n".join(f"- `{name}`" for name in todo_bullets)

        self.backlog_file.write_text(
            "# Backlog\n\n"
            "## Active\n\n"
            "## To Do\n\n"
            f"{bullets}\n\n"
            "## Blocked\n\n_(none)_\n\n"
            "## Done\n\n_(none)_\n\n"
            "## Archived\n\n_(none)_\n",
            encoding="utf-8",
        )

    def write_story(self, filename: str, status_block: str) -> None:
        (self.stories_dir / filename).write_text(
            story_body(status_block), encoding="utf-8"
        )

    def write_open_decision(self, filename: str, decision_id: str) -> None:
        (self.user_decisions_dir / filename).write_text(
            f"# {decision_id} -- Something to decide\n\n"
            "## Status\n\nOPEN\n",
            encoding="utf-8",
        )


class FirstExecutableWinsTest(SelectorTestCase):

    def test_first_executable_story_wins(self):
        self.write_backlog(["STORY-A-001-a.md", "STORY-B-001-b.md"])
        self.write_story("STORY-A-001-a.md", STATUS_TODO)
        self.write_story("STORY-B-001-b.md", STATUS_TODO)

        result = selector.select_next_story()

        self.assertEqual(result["decision"], "NEXT")
        self.assertEqual(result["story_path"], "STORY-A-001-a.md")


class BlockedFirstEntryIsSkippedTest(SelectorTestCase):

    def test_blocked_first_story_is_skipped(self):
        self.write_backlog(["STORY-A-001-a.md", "STORY-B-001-b.md"])
        self.write_story("STORY-A-001-a.md", STATUS_BLOCKED)
        self.write_story("STORY-B-001-b.md", STATUS_TODO)

        result = selector.select_next_story()

        self.assertEqual(result["decision"], "NEXT")
        self.assertEqual(result["story_path"], "STORY-B-001-b.md")

    def test_dependency_declared_blocked_is_also_skipped(self):
        self.write_backlog(["STORY-A-001-a.md", "STORY-B-001-b.md"])
        self.write_story(
            "STORY-A-001-a.md", STATUS_TODO_WITH_BLOCKED_DEPENDENCY
        )
        self.write_story("STORY-B-001-b.md", STATUS_TODO)

        result = selector.select_next_story()

        self.assertEqual(result["decision"], "NEXT")
        self.assertEqual(result["story_path"], "STORY-B-001-b.md")

    def test_done_first_entry_is_also_skipped(self):
        self.write_backlog(["STORY-A-001-a.md", "STORY-B-001-b.md"])
        self.write_story("STORY-A-001-a.md", STATUS_DONE)
        self.write_story("STORY-B-001-b.md", STATUS_TODO)

        result = selector.select_next_story()

        self.assertEqual(result["decision"], "NEXT")
        self.assertEqual(result["story_path"], "STORY-B-001-b.md")


class ArchivedOrMissingEntriesIgnoredTest(SelectorTestCase):

    def test_nonexistent_entry_is_ignored(self):
        # STORY-A is listed but its file no longer exists under
        # agent/stories/ -- as if it moved to agent/stories/archive/.
        self.write_backlog(["STORY-A-001-a.md", "STORY-B-001-b.md"])
        self.write_story("STORY-B-001-b.md", STATUS_TODO)

        result = selector.select_next_story()

        self.assertEqual(result["decision"], "NEXT")
        self.assertEqual(result["story_path"], "STORY-B-001-b.md")

    def test_all_entries_missing_and_no_open_decision_is_no_work(self):
        self.write_backlog(["STORY-A-001-a.md", "STORY-B-001-b.md"])
        # Neither story file is written -- both are "gone".

        result = selector.select_next_story()

        self.assertEqual(result["decision"], "NO_WORK")


class OpenDecisionProducesNeedsUserTest(SelectorTestCase):

    def test_open_user_decision_when_nothing_executable_remains(self):
        self.write_backlog(["STORY-A-001-a.md"])
        self.write_story("STORY-A-001-a.md", STATUS_BLOCKED)
        self.write_open_decision(
            "UD-001-something.md", "UD-001"
        )

        result = selector.select_next_story()

        self.assertEqual(result["decision"], "NEEDS_USER")
        self.assertIn("UD-001", result["reason"])

    def test_explicit_blocker_without_open_decision_is_blocked(self):
        self.write_backlog(["STORY-A-001-a.md"])
        self.write_story("STORY-A-001-a.md", STATUS_BLOCKED)
        # No open user-decision file at all.

        result = selector.select_next_story()

        self.assertEqual(result["decision"], "BLOCKED")
        self.assertIn("STORY-A-001-a.md", result["reason"])


class EmptyQueueProducesNoWorkTest(SelectorTestCase):

    def test_empty_to_do_section_is_no_work(self):
        self.write_backlog([])

        result = selector.select_next_story()

        self.assertEqual(result["decision"], "NO_WORK")


class NoHermesCallTest(SelectorTestCase):

    def test_selector_module_has_no_hermes_reference(self):
        self.assertFalse(hasattr(selector, "call_ollama"))
        self.assertNotIn("SELECTOR_SCHEMA", dir(selector))

    def test_selector_never_calls_ollama(self):
        self.write_backlog(["STORY-A-001-a.md", "STORY-B-001-b.md"])
        self.write_story("STORY-A-001-a.md", STATUS_BLOCKED)
        self.write_story("STORY-B-001-b.md", STATUS_TODO)

        with patch(
            "hermes_client.call_ollama",
            side_effect=AssertionError(
                "select_next_story() must never call Hermes/Ollama"
            ),
        ):
            result = selector.select_next_story()

        self.assertEqual(result["decision"], "NEXT")


if __name__ == "__main__":
    unittest.main()
