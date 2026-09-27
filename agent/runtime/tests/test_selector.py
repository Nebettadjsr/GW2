"""
Deterministic tests for the AI-free story selector (selector.py).

Story selection must never call Hermes/Ollama -- BACKLOG.md's '## To
Do' section is the canonical execution order, and Python walks it top
to bottom picking the first entry get_selectable_story_candidates()
confirms is executable. These tests exercise select_next_story()
against synthetic, temporary BACKLOG.md/story/user-decision files (via
monkeypatched module path constants), never the real repository state.

Run with: python -m unittest agent.runtime.tests.test_selector -v
(from the repository root).

No application/domain Java code is touched by these tests.
"""

import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch

from agent.runtime.core import selector
from agent.runtime.core import story_archive
from agent.runtime.core import story_state
from agent.runtime.core import planning_context
from agent.runtime.human import user_decisions


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

        # Deliberately never created: build_story_index() (used by the
        # dependency-eligibility checks) treats a missing archive dir
        # as "no archived stories", which is exactly what isolates
        # these tests from the real repository's actual
        # agent/stories/archive/ contents.
        self.archive_dir = root / "archive"

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
            patch.object(story_archive, "ARCHIVE_DIR", self.archive_dir)
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

    def write_story_with_id(
        self,
        filename: str,
        story_id: str,
        status_block: str,
        dependencies_text: str | None = None,
    ) -> None:
        content = (
            "## Story ID\n\n"
            f"{story_id}\n\n"
            f"{status_block}\n"
        )

        if dependencies_text is not None:
            content += f"\n## Dependencies\n\n{dependencies_text}\n"

        (self.stories_dir / filename).write_text(
            content, encoding="utf-8"
        )

    def write_archived_story_with_id(
        self,
        filename: str,
        story_id: str,
        status_block: str,
    ) -> None:
        milestone_dir = self.archive_dir / "milestone-00"
        milestone_dir.mkdir(parents=True, exist_ok=True)

        (milestone_dir / filename).write_text(
            "## Story ID\n\n"
            f"{story_id}\n\n"
            f"{status_block}\n",
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


class CompactBacklogSyntaxTest(SelectorTestCase):
    def write_compact_backlog(self):
        self.backlog_file.write_text(
            "# Story Backlog Index\n\n"
            "## Active\n\n"
            "- STORY-A-001 | STORY-A-001-a.md | ACTIVE | milestone-01: current\n\n"
            "## To Do\n\n"
            "- STORY-B-001 | STORY-B-001-b.md | TODO | milestone-01: first\n\n"
            "- STORY-C-001 | STORY-C-001-c.md | TODO | milestone-01: second\n\n"
            "## Blocked\n\n"
            "- STORY-D-001 | STORY-D-001-d.md | BLOCKED | milestone-01: wait\n\n"
            "## Done\n\n"
            "- STORY-E-001 | STORY-E-001-e.md | DONE | milestone-01: finished\n",
            encoding="utf-8",
        )

    def test_compact_rows_are_parsed_by_all_canonical_sections(self):
        self.write_compact_backlog()
        backlog = self.backlog_file.read_text(encoding="utf-8")
        self.assertEqual(story_state.parse_backlog_section(backlog, "Active"),
                         ["STORY-A-001-a.md"])
        self.assertEqual(story_state.parse_backlog_section(backlog, "To Do"),
                         ["STORY-B-001-b.md", "STORY-C-001-c.md"])
        self.assertEqual(story_state.parse_backlog_section(backlog, "Blocked"),
                         ["STORY-D-001-d.md"])
        self.assertEqual(story_state.parse_backlog_section(backlog, "Done"),
                         ["STORY-E-001-e.md"])

    def test_multiple_compact_todo_rows_allow_selector_to_choose_first(self):
        self.write_compact_backlog()
        self.write_story_with_id("STORY-B-001-b.md", "STORY-B-001", STATUS_TODO)
        self.write_story_with_id("STORY-C-001-c.md", "STORY-C-001", STATUS_TODO)

        result = selector.select_next_story()

        self.assertEqual(result["decision"], "NEXT")
        self.assertEqual(result["story_path"], "STORY-B-001-b.md")

    def test_compact_blocked_row_is_not_selectable(self):
        self.write_compact_backlog()
        self.write_story_with_id("STORY-B-001-b.md", "STORY-B-001", STATUS_TODO)
        self.write_story_with_id("STORY-C-001-c.md", "STORY-C-001", STATUS_TODO)
        self.write_story_with_id("STORY-D-001-d.md", "STORY-D-001", STATUS_TODO)

        self.assertEqual(story_state.get_ready_story_filenames_ordered(),
                         ["STORY-B-001-b.md", "STORY-C-001-c.md"])
        result = selector.select_next_story()
        self.assertEqual(result.get("story_path"), "STORY-B-001-b.md")

    def test_blank_lines_do_not_end_compact_todo_section(self):
        self.write_compact_backlog()
        self.assertEqual(story_state.get_ready_story_filenames_ordered(),
                         ["STORY-B-001-b.md", "STORY-C-001-c.md"])

    def test_compact_index_status_follows_backlog_section(self):
        self.write_compact_backlog()
        filenames = ["STORY-D-001-d.md"]
        facts = {filenames[0]: {
            "id": "STORY-D-001", "title": "Blocked story", "status": "TODO",
            "milestone": "milestone-01", "prerequisites": [], "dependencies": "None.",
        }}
        result = planning_context.backlog_index(
            self.backlog_file.read_text(encoding="utf-8"), facts
        )
        self.assertIn("STORY-D-001 | STORY-D-001-d.md | BLOCKED | milestone-01", result)


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


class DependencyEligibilityTest(SelectorTestCase):
    """
    Regression coverage for the exact defect that let
    STORY-DOM-015 be selected while STORY-DOM-013/STORY-DOM-014 (its
    own listed Dependencies) were still TODO: eligibility must be
    derived from each referenced Story ID's *actual current status*,
    never from a keyword scan of the Dependencies text.
    """

    def test_dependency_on_todo_story_blocks_selection(self):
        self.write_backlog(["STORY-DOM-015-c.md", "STORY-B-001-b.md"])
        self.write_story_with_id(
            "STORY-DOM-013-a.md", "STORY-DOM-013", STATUS_TODO
        )
        self.write_story_with_id(
            "STORY-DOM-015-c.md",
            "STORY-DOM-015",
            STATUS_TODO,
            dependencies_text="STORY-DOM-013; STORY-DOM-014",
        )
        self.write_story("STORY-B-001-b.md", STATUS_TODO)

        result = selector.select_next_story()

        # STORY-DOM-015 must be skipped even though its own Dependencies
        # text contains no literal "blocked" word -- exactly the shape
        # that previously slipped through.
        self.assertEqual(result["decision"], "NEXT")
        self.assertEqual(result["story_path"], "STORY-B-001-b.md")

    def test_dependency_on_done_story_allows_selection(self):
        self.write_backlog(["STORY-DOM-015-c.md"])
        self.write_story_with_id(
            "STORY-DOM-012-a.md", "STORY-DOM-012", STATUS_DONE
        )
        self.write_story_with_id(
            "STORY-DOM-015-c.md",
            "STORY-DOM-015",
            STATUS_TODO,
            dependencies_text="STORY-DOM-012 (DONE)",
        )

        result = selector.select_next_story()

        self.assertEqual(result["decision"], "NEXT")
        self.assertEqual(result["story_path"], "STORY-DOM-015-c.md")

    def test_dependency_on_archived_done_story_allows_selection(self):
        self.write_backlog(["STORY-DOM-015-c.md"])
        self.write_archived_story_with_id(
            "STORY-DOM-012-a.md", "STORY-DOM-012", STATUS_DONE
        )
        self.write_story_with_id(
            "STORY-DOM-015-c.md",
            "STORY-DOM-015",
            STATUS_TODO,
            dependencies_text="STORY-DOM-012",
        )

        result = selector.select_next_story()

        self.assertEqual(result["decision"], "NEXT")
        self.assertEqual(result["story_path"], "STORY-DOM-015-c.md")

    def test_none_dependency_allows_selection(self):
        self.write_backlog(["STORY-DOM-015-c.md"])
        self.write_story_with_id(
            "STORY-DOM-015-c.md",
            "STORY-DOM-015",
            STATUS_TODO,
            dependencies_text="None.",
        )

        result = selector.select_next_story()

        self.assertEqual(result["decision"], "NEXT")
        self.assertEqual(result["story_path"], "STORY-DOM-015-c.md")

    def test_none_dependency_with_explanatory_prose_allows_selection(self):
        # Regression: "None." followed by free-form prose was
        # previously misparsed as a non-empty, unrecognized Dependencies
        # section (fail-safe BLOCKED) purely because the exact string
        # didn't equal "none" after stripping a trailing period.
        self.write_backlog(["STORY-DOM-015-c.md"])
        self.write_story_with_id(
            "STORY-DOM-015-c.md",
            "STORY-DOM-015",
            STATUS_TODO,
            dependencies_text="None. Not dependent on any other story.",
        )

        result = selector.select_next_story()

        self.assertEqual(result["decision"], "NEXT")
        self.assertEqual(result["story_path"], "STORY-DOM-015-c.md")

    def test_none_prefix_wins_even_if_prose_names_a_story_id(self):
        # "None" at the start is authoritative -- a Story ID mentioned
        # only in trailing explanatory prose must not resurrect a
        # dependency.
        self.write_backlog(["STORY-DOM-015-c.md"])
        self.write_story_with_id(
            "STORY-DOM-015-c.md",
            "STORY-DOM-015",
            STATUS_TODO,
            dependencies_text=(
                "None. Previously blocked by STORY-DOM-005, now resolved."
            ),
        )

        result = selector.select_next_story()

        self.assertEqual(result["decision"], "NEXT")
        self.assertEqual(result["story_path"], "STORY-DOM-015-c.md")

    def test_nonetheless_is_not_mistaken_for_none(self):
        # Word-boundary check: "Nonetheless" must not match the "None"
        # prefix and silently clear a real dependency.
        self.write_backlog(["STORY-DOM-015-c.md"])
        self.write_story_with_id(
            "STORY-DOM-013-a.md", "STORY-DOM-013", STATUS_TODO
        )
        self.write_story_with_id(
            "STORY-DOM-015-c.md",
            "STORY-DOM-015",
            STATUS_TODO,
            dependencies_text=(
                "Nonetheless this depends on STORY-DOM-013."
            ),
        )

        result = selector.select_next_story()

        self.assertEqual(result["decision"], "BLOCKED")

    def test_unrecognized_dependency_reference_fails_safe(self):
        self.write_backlog(["STORY-DOM-015-c.md"])
        self.write_story_with_id(
            "STORY-DOM-015-c.md",
            "STORY-DOM-015",
            STATUS_TODO,
            dependencies_text="Waiting on external API access.",
        )

        result = selector.select_next_story()

        # No recognizable STORY-<AREA>-<NUMBER> reference and not
        # "None" either -- must fail safe (non-selectable, reported as
        # BLOCKED with a reason), never silently treated as satisfied.
        self.assertEqual(result["decision"], "BLOCKED")

    def test_dependency_on_unknown_story_id_fails_safe(self):
        self.write_backlog(["STORY-DOM-015-c.md"])
        self.write_story_with_id(
            "STORY-DOM-015-c.md",
            "STORY-DOM-015",
            STATUS_TODO,
            dependencies_text="STORY-GHOST-999",
        )

        result = selector.select_next_story()

        self.assertEqual(result["decision"], "BLOCKED")


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
            "agent.runtime.evaluation.hermes_client.call_ollama",
            side_effect=AssertionError(
                "select_next_story() must never call Hermes/Ollama"
            ),
        ):
            result = selector.select_next_story()

        self.assertEqual(result["decision"], "NEXT")


class StoryStatusClassificationTest(unittest.TestCase):
    """
    classify_story_status() must reliably recognize all three
    canonical Status values (TODO/DONE/BLOCKED) rather than lumping
    TODO into the generic "OTHER" fallback -- which previously made a
    correctly-TODO dependency (e.g. STORY-DOM-013) report as
    "currently OTHER" instead of "currently TODO" in diagnostics.
    """

    def test_todo_is_classified_as_todo_not_other(self):
        self.assertEqual(
            story_state.classify_story_status("TODO"), "TODO"
        )

    def test_done_is_still_classified_as_done(self):
        self.assertEqual(
            story_state.classify_story_status("DONE"), "DONE"
        )

    def test_blocked_is_still_classified_as_blocked(self):
        self.assertEqual(
            story_state.classify_story_status("BLOCKED"), "BLOCKED"
        )

    def test_todo_with_blocking_note_is_still_blocked(self):
        # Existing precedent must be preserved: a "TODO" line that
        # also mentions being blocked is BLOCKED, not TODO.
        self.assertEqual(
            story_state.classify_story_status(
                "TODO -- blocked until STORY-DOM-001 reaches DONE"
            ),
            "BLOCKED",
        )

    def test_blank_status_is_other(self):
        self.assertEqual(story_state.classify_story_status(""), "OTHER")

    def test_unrecognized_status_text_is_other(self):
        self.assertEqual(
            story_state.classify_story_status("IN_PROGRESS"), "OTHER"
        )

    def test_unsatisfied_dependency_message_reports_todo_not_other(self):
        # Direct reproduction of the STORY-DOM-013 diagnostic
        # complaint: a dependency on a TODO story must say so.
        content = (
            "## Status\n\nTODO\n\n"
            "## Dependencies\n\nSTORY-DOM-013\n"
        )
        story_index = {
            "STORY-DOM-013": {"path": None, "status": "TODO"}
        }

        unsatisfied = story_state.get_unsatisfied_dependencies(
            content, story_index
        )

        self.assertEqual(len(unsatisfied), 1)
        self.assertIn("STORY-DOM-013", unsatisfied[0])
        self.assertIn("currently TODO", unsatisfied[0])
        self.assertNotIn("OTHER", unsatisfied[0])


if __name__ == "__main__":
    unittest.main()
