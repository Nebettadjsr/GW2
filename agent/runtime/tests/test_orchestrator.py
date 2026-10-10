"""
Deterministic tests for orchestrator.py's evaluator-NEEDS_USER handling:
creating a user-intervention record, blocking the affected story (file +
BACKLOG.md), and re-checking eligibility once an intervention resolves.

Never calls Claude/the evaluator/the planner. The actual timed polling
loop (wait_for_user_interventions' time.sleep cadence) is exercised
separately, ad hoc, since a sleep-based loop doesn't belong in a fast
unit suite -- see get_unresolved_user_interventions here for its
underlying, directly-testable logic instead.

Run with: python -m unittest agent.runtime.tests.test_orchestrator -v
(from the repository root).
"""

import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch

from agent.runtime.core import orchestrator
from agent.runtime.core import story_archive
from agent.runtime.core import story_state
from agent.runtime.human import user_interventions


def story_with_id(
    story_id: str,
    status_block: str,
    dependencies_text: str | None = None,
) -> str:
    content = f"## Story ID\n\n{story_id}\n\n{status_block}\n"

    if dependencies_text is not None:
        content += f"\n## Dependencies\n\n{dependencies_text}\n"

    # Every real story file has a '## Blockers' section (Story
    # Contract) -- set_story_blocked()/clear_story_blocked() require
    # it to already exist, exactly like a real story would.
    content += "\n## Blockers\n\nNone.\n"

    return content


class OrchestratorInterventionTestCase(unittest.TestCase):
    """
    Common fixture: temp agent/stories/, agent/stories/BACKLOG.md, and
    agent/user-interventions/, wired in place of the real repository
    paths via patched module attributes (mirroring test_selector.py's
    approach). The archive dir is deliberately never created, so
    build_story_index() sees "no archived stories" instead of leaking
    the real repository's actual archive.
    """

    def setUp(self):
        self._tmpdir = tempfile.TemporaryDirectory()
        # .resolve() avoids a Windows short-path (8.3 "ADMINI~1")
        # mismatch between this and Path(__file__).resolve()-derived
        # paths when code under test calls Path.relative_to().
        root = Path(self._tmpdir.name).resolve()

        self.stories_dir = root / "stories"
        self.stories_dir.mkdir()

        self.archive_dir = root / "archive"

        self.interventions_dir = root / "user-interventions"

        self.backlog_file = self.stories_dir / "BACKLOG.md"

        self.current_story_file = root / "CURRENT_STORY.md"
        self.attempt_state_file = root / "ATTEMPT_STATE.json"

        self._stack = ExitStack()

        # No test may append to the real, committed agent/logs/ -- every
        # transition the code under test records lands here instead.
        self.logged = []
        self._stack.enter_context(
            patch.object(orchestrator, "log_line", side_effect=self.logged.append)
        )

        # No test may dispatch the real architect against the real
        # agent/architect-requests/ inbox; test_architect_flow.py drives
        # that path explicitly instead.
        for name in ("undispatchable_architect_requests",):
            self._stack.enter_context(
                patch.object(orchestrator, name, return_value=[])
            )

        for target, name, value in [
            (story_state, "STORIES_DIR", self.stories_dir),
            (story_state, "BACKLOG_FILE", self.backlog_file),
            (story_state, "CURRENT_STORY_FILE", self.current_story_file),
            (story_state, "ATTEMPT_STATE_FILE", self.attempt_state_file),
            (story_state, "ACTIVATION_LOCK_FILE", root / "STORY_ACTIVATION.lock"),
            (orchestrator, "CURRENT_STORY_FILE", self.current_story_file),
            (story_state, "ARCHIVE_DIR", self.archive_dir),
            (story_state, "REPO_ROOT", root),
            (story_archive, "ARCHIVE_DIR", self.archive_dir),
            (orchestrator, "BACKLOG_FILE", self.backlog_file),
            (orchestrator, "REPO_ROOT", root),
            (
                user_interventions,
                "USER_INTERVENTIONS_DIR",
                self.interventions_dir,
            ),
        ]:
            self._stack.enter_context(
                patch.object(target, name, value)
            )

        self.addCleanup(self._stack.close)
        self.addCleanup(self._tmpdir.cleanup)

    def write_backlog(self, active=None, todo=None, blocked=None) -> None:
        def bullets(names):
            return "\n".join(f"- `{name}`" for name in (names or []))

        self.backlog_file.write_text(
            "# Backlog\n\n"
            f"## Active\n\n{bullets(active)}\n\n"
            f"## To Do\n\n{bullets(todo)}\n\n"
            f"## Blocked\n\n{bullets(blocked)}\n\n"
            "## Done\n\n_(none)_\n\n"
            "## Archived\n\n_(none)_\n",
            encoding="utf-8",
        )

    def write_story(self, filename: str, content: str) -> Path:
        path = self.stories_dir / filename
        path.write_text(content, encoding="utf-8")
        return path

    def set_active(self, filename: str) -> None:
        # A bare filename (no path separator) resolves relative to
        # STORIES_DIR in resolve_story_path() -- see story_state.py.
        self.current_story_file.write_text(
            f"{filename}\n", encoding="utf-8"
        )


class CreateInterventionAndBlockStoryTest(OrchestratorInterventionTestCase):

    def test_creates_file_blocks_story_and_moves_backlog_bullet(self):
        filename = "STORY-DOM-015-x.md"
        story_path = self.write_story(
            filename,
            story_with_id("STORY-DOM-015", "## Status\n\nTODO\n"),
        )
        self.write_backlog(active=[filename])

        orchestrator._create_intervention_and_block_story(
            story_path,
            story_path.read_text(encoding="utf-8"),
            "PowerShell access denied; needs an interactive GUI check.",
            "FULL RAW CLAUDE RESPONSE TEXT HERE",
        )

        interventions = user_interventions.list_interventions()
        self.assertEqual(len(interventions), 1)
        self.assertEqual(interventions[0]["status"], "OPEN")
        self.assertEqual(interventions[0]["story_id"], "STORY-DOM-015")

        intervention_path = user_interventions.list_intervention_files()[0]
        self.assertTrue(
            intervention_path.name.startswith("UI-001-STORY-DOM-015")
        )

        raw = intervention_path.read_text(encoding="utf-8")
        self.assertIn("FULL RAW CLAUDE RESPONSE TEXT HERE", raw)
        self.assertIn("PowerShell access denied", raw)
        self.assertIn("## Status\n\nOPEN", raw)

        updated_story = story_path.read_text(encoding="utf-8")
        self.assertEqual(
            story_state.classify_story_status(
                story_state.extract_status_section(updated_story)
            ),
            "BLOCKED",
        )
        self.assertIn(intervention_path.name, updated_story)

        backlog = self.backlog_file.read_text(encoding="utf-8")
        self.assertEqual(
            story_state.parse_backlog_section(backlog, "Active"), []
        )
        self.assertIn(filename, backlog)
        blocked_entries = story_state.parse_backlog_section(
            backlog, "Blocked"
        )
        self.assertEqual(blocked_entries, [filename])

    def test_does_not_consume_retry_count(self):
        # MAX_RETRIES_PER_STORY is never referenced by
        # _create_intervention_and_block_story() at all -- this is a
        # structural guard against a future change accidentally
        # threading retry accounting through this path.
        import inspect

        source = inspect.getsource(
            orchestrator._create_intervention_and_block_story
        )
        self.assertNotIn("retry_count", source)
        self.assertNotIn("MAX_RETRIES_PER_STORY", source)


class RequeueResolvedInterventionsTest(OrchestratorInterventionTestCase):

    def write_intervention(
        self, filename: str, story_id: str, status: str
    ) -> None:
        self.interventions_dir.mkdir(parents=True, exist_ok=True)
        (self.interventions_dir / filename).write_text(
            "# User Intervention\n\n"
            f"## Status\n\n{status}\n\n"
            f"## Story\n\n{story_id}\n\n"
            "## Reason\n\nSomething.\n\n"
            "## Claude Response\n\n...\n\n"
            "## User Resolution\n\nDone.\n\n"
            "## Resolution Notes\n\nGranted access.\n",
            encoding="utf-8",
        )

    def test_resolved_intervention_with_no_other_blocker_requeues_story(
        self,
    ):
        filename = "STORY-DOM-015-x.md"
        story_path = self.write_story(
            filename,
            story_with_id(
                "STORY-DOM-015",
                "## Status\n\nBLOCKED\n",
                dependencies_text="None.",
            ),
        )
        # Simulate the pre-existing blocked-on-intervention Blockers text.
        content = story_path.read_text(encoding="utf-8")
        content = story_state._replace_section_body(
            content, "Blockers",
            "Blocked on user intervention (see "
            "agent/user-interventions/UI-001-STORY-DOM-015.md)."
        )
        story_path.write_text(content, encoding="utf-8")

        self.write_backlog(blocked=[filename])
        self.write_intervention(
            "UI-001-STORY-DOM-015.md", "STORY-DOM-015", "RESOLVED"
        )

        orchestrator.requeue_resolved_interventions()

        updated_story = story_path.read_text(encoding="utf-8")
        self.assertEqual(
            story_state.classify_story_status(
                story_state.extract_status_section(updated_story)
            ),
            "TODO",
        )
        self.assertIn(
            "TODO",
            story_state.extract_status_section(updated_story),
        )

        backlog = self.backlog_file.read_text(encoding="utf-8")
        self.assertEqual(
            story_state.parse_backlog_section(backlog, "Blocked"), []
        )
        self.assertEqual(
            story_state.parse_backlog_section(backlog, "To Do"),
            [filename],
        )

    def test_requeue_transition_restamps_canonical_backlog_entry(self):
        filename = "STORY-DOM-015-x.md"
        backlog = (
            "# Backlog\n\n## Active\n\n_(none)_\n\n## To Do\n\n_(none)_\n\n"
            "## Blocked\n\n- STORY-DOM-015 | STORY-DOM-015-x.md | BLOCKED | "
            "milestone-05 | deps: None\n\n## Done\n\n_(none)_\n\n"
            "## Archived\n\n_(none)_\n"
        )
        updated = story_state.move_backlog_entry_to_todo(backlog, filename)
        self.assertEqual(story_state.parse_backlog_section(updated, "Blocked"), [])
        self.assertEqual(story_state.parse_backlog_section(updated, "To Do"), [filename])
        todo_line = next(line for line in updated.splitlines() if filename in line)
        self.assertEqual(story_state.parse_backlog_entry(todo_line)["status"], "TODO")

    def test_resolved_intervention_but_unmet_dependency_stays_blocked(self):
        filename = "STORY-DOM-015-x.md"
        story_path = self.write_story(
            filename,
            story_with_id(
                "STORY-DOM-015",
                "## Status\n\nBLOCKED\n",
                dependencies_text="STORY-DOM-014",
            ),
        )
        self.write_story(
            "STORY-DOM-014-y.md",
            story_with_id("STORY-DOM-014", "## Status\n\nTODO\n"),
        )

        self.write_backlog(blocked=[filename])
        self.write_intervention(
            "UI-001-STORY-DOM-015.md", "STORY-DOM-015", "RESOLVED"
        )

        orchestrator.requeue_resolved_interventions()

        updated_story = story_path.read_text(encoding="utf-8")
        self.assertEqual(
            story_state.classify_story_status(
                story_state.extract_status_section(updated_story)
            ),
            "BLOCKED",
        )
        self.assertIn("STORY-DOM-014", updated_story)

        backlog = self.backlog_file.read_text(encoding="utf-8")
        self.assertEqual(
            story_state.parse_backlog_section(backlog, "Blocked"),
            [filename],
        )
        self.assertEqual(
            story_state.parse_backlog_section(backlog, "To Do"), []
        )

    def test_still_open_intervention_leaves_story_untouched(self):
        filename = "STORY-DOM-015-x.md"
        story_path = self.write_story(
            filename,
            story_with_id(
                "STORY-DOM-015",
                "## Status\n\nBLOCKED\n",
                dependencies_text="None.",
            ),
        )
        self.write_backlog(blocked=[filename])
        self.write_intervention(
            "UI-001-STORY-DOM-015.md", "STORY-DOM-015", "OPEN"
        )

        orchestrator.requeue_resolved_interventions()

        updated_story = story_path.read_text(encoding="utf-8")
        self.assertEqual(
            story_state.classify_story_status(
                story_state.extract_status_section(updated_story)
            ),
            "BLOCKED",
        )

        backlog = self.backlog_file.read_text(encoding="utf-8")
        self.assertEqual(
            story_state.parse_backlog_section(backlog, "Blocked"),
            [filename],
        )


if __name__ == "__main__":
    unittest.main()
