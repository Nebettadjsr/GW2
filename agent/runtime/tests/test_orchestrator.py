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
from agent.runtime.runners import claude_runner
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
        for name in ("get_actionable_architect_requests",
                     "undispatchable_architect_requests"):
            self._stack.enter_context(
                patch.object(orchestrator, name, return_value=[])
            )

        for target, name, value in [
            (story_state, "STORIES_DIR", self.stories_dir),
            (story_state, "BACKLOG_FILE", self.backlog_file),
            (story_state, "CURRENT_STORY_FILE", self.current_story_file),
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

    def _verify_active_story_runs_claude_before_failing_evaluation(self):
        filename = "STORY-DOM-001-active.md"
        story = self.write_story(
            filename,
            story_with_id("STORY-DOM-001", "## Status\n\nTODO\n"),
        )
        self.write_backlog(active=[filename])
        self.set_active(filename)
        events = []

        def run_claude(_prompt):
            events.append("claude")
            return claude_runner.ClaudeAttempt(0, False)

        def evaluate(*_args, **_kwargs):
            events.append("evaluate")
            if events.count("evaluate") == 1:
                raise OSError("evaluator unavailable")
            return {"decision": "COMPLETE", "reason": "ok"}

        with ExitStack() as stack:
            for name, value in [
                ("CLAUDE_RESULT_FILE", self.stories_dir / "result.md"),
                ("NEXT_PROMPT_FILE", self.stories_dir / "prompt.md"),
            ]:
                stack.enter_context(patch.object(orchestrator, name, value))
            stack.enter_context(patch.object(orchestrator, "build_claude_prompt",
                                             return_value="Implement active story"))
            stack.enter_context(patch.object(orchestrator, "generate_repo_map",
                                             return_value={"enabled": False, "text": "",
                                                           "token_budget": 0, "error": None}))
            stack.enter_context(patch.object(orchestrator, "get_claude_usage",
                                             return_value=claude_runner.ClaudeUsage(0, 0)))
            stack.enter_context(patch.object(orchestrator, "run_claude_attempt",
                                             side_effect=run_claude))
            stack.enter_context(patch.object(orchestrator, "evaluate_story",
                                             side_effect=evaluate))
            stack.enter_context(patch.object(orchestrator.time, "sleep"))
            result = orchestrator.execute_active_story()

        self.assertEqual(result, "COMPLETE")
        self.assertEqual(events, ["claude", "evaluate", "evaluate"])
        self.assertTrue(story.exists())


class DirectClaudeExecutionTest(OrchestratorInterventionTestCase):
    def test_active_story_runs_claude_before_failing_evaluation(self):
        self._verify_active_story_runs_claude_before_failing_evaluation()


class InterruptedClaudeTest(OrchestratorInterventionTestCase):

    def run_attempts(self, exits, evaluations, initial_status="TODO",
                     capacity_signal=True, capacity_readings=None):
        filename = "STORY-UI-001-test.md"
        story = self.write_story(
            filename,
            story_with_id("STORY-UI-001", f"## Status\n\n{initial_status}\n")
            + "\n## Result\n\nExisting work.\n",
        )
        self.write_backlog(active=[filename])
        self.set_active(filename)
        backlog_before = self.backlog_file.read_bytes()
        pointer_before = self.current_story_file.read_bytes()
        result = self.stories_dir / "CLAUDE_RESULT.md"
        result.write_text("Stale unrelated DONE result", encoding="utf-8")
        events = []
        codes = iter(exits)

        def run(prompt):
            self.assertEqual(self.backlog_file.read_bytes(), backlog_before)
            self.assertEqual(self.current_story_file.read_bytes(), pointer_before)
            if events and events[-1] == "capacity":
                self.assertEqual(
                    story_state.extract_status_section(story.read_text()),
                    "UNFINISHED",
                )
                self.assertIn("resume where it stopped", prompt)
                self.assertEqual(story_state.validate_backlog_consistency(), [])
            code = next(codes)
            events.append(code)
            # A non-zero exit that carries a capacity signal is a
            # scheduling pause; without one it is a failed run.
            return claude_runner.ClaudeAttempt(
                code, capacity_signal and code != 0
            )

        outcomes = iter(evaluations)

        def evaluate(*args, **_kwargs):
            self.assertEqual(events[-1], 0)
            self.assertEqual(args[2], 0)
            events.append("evaluate")
            return next(outcomes)

        def capacity():
            events.append("capacity")
            # Exercise the existing wait loop without Claude or real sleeping.
            with patch.object(claude_runner, "get_claude_usage",
                              side_effect=capacity_readings or [
                                  claude_runner.ClaudeUsage(100, 0),
                                  claude_runner.ClaudeUsage(0, 0),
                              ]), \
                 patch.object(claude_runner.time, "sleep") as sleep:
                claude_runner.wait_for_claude_capacity()
                sleep.assert_called_once()

        with ExitStack() as stack:
            for name, value in [("CLAUDE_RESULT_FILE", result),
                                ("NEXT_PROMPT_FILE", self.stories_dir / "prompt.md"),
                                ("MAX_RETRIES_PER_STORY", 1)]:
                stack.enter_context(patch.object(orchestrator, name, value))
            stack.enter_context(patch.object(orchestrator, "build_claude_prompt",
                                            return_value=f"Implement {filename}"))
            stack.enter_context(patch.object(orchestrator, "generate_repo_map",
                                            return_value={
                                                "enabled": False, "available": False,
                                                "text": "", "token_budget": 1200,
                                                "char_count": 0, "approx_tokens": 0,
                                                "duration_seconds": 0.0, "error": None,
                                            }))
            stack.enter_context(patch.object(orchestrator, "get_claude_usage",
                                            return_value=claude_runner.ClaudeUsage(50, 0)))
            stack.enter_context(patch.object(orchestrator, "run_claude_attempt", side_effect=run))
            stack.enter_context(patch.object(orchestrator, "evaluate_story", side_effect=evaluate))
            stack.enter_context(patch.object(orchestrator, "wait_for_claude_capacity", side_effect=capacity))
            self.assertEqual(orchestrator.execute_active_story(), "COMPLETE")

        # A completed story finishes its transition here. These assertions used
        # to require BACKLOG.md and CURRENT_STORY.md to be byte-identical
        # afterwards, which is precisely the defect: completion changed nothing,
        # so a story that passed evaluation and CI stayed under '## Active' with
        # the pointer still naming it, and the next planning pass ran against
        # that inconsistent queue.
        self.assertEqual(
            story_state.parse_backlog_section(
                self.backlog_file.read_text(encoding="utf-8"), "Active"
            ),
            [],
        )
        self.assertIn(
            filename,
            story_state.parse_backlog_section(
                self.backlog_file.read_text(encoding="utf-8"), "Done"
            ),
        )
        self.assertEqual(
            self.current_story_file.read_text(encoding="utf-8").strip(), ""
        )
        self.assertEqual(
            story_state.classify_story_status(
                story_state.extract_status_section(story.read_text())
            ),
            "DONE",
        )
        self.assertEqual(story_state.validate_backlog_consistency(), [])
        self.assertIn("Existing work.", story.read_text())
        self.assertFalse(self.interventions_dir.exists())
        return events

    def test_zero_exit_evaluates_normally(self):
        self.assertEqual(self.run_attempts([0], [{"decision": "COMPLETE", "reason": "ok"}]),
                         [0, "evaluate"])

    def test_nonzero_exits_wait_and_resume_without_evaluator_retries(self):
        self.assertEqual(
            self.run_attempts([1, 2, -1, 0], [{"decision": "COMPLETE", "reason": "ok"}]),
            [1, "capacity", 2, "capacity", -1, "capacity", 0, "evaluate"],
        )

    def test_weekly_limit_exit_waits_before_same_story_retries(self):
        weekly_signal = claude_runner.output_indicates_capacity_exhaustion(
            "You've hit your weekly limit · resets Oct 3, 7am (Europe/Berlin)"
        )
        self.assertTrue(weekly_signal)
        self.assertEqual(
            self.run_attempts([1, 0], [{"decision": "COMPLETE", "reason": "ok"}],
                              capacity_signal=weekly_signal,
                              capacity_readings=[
                                  claude_runner.ClaudeUsage(50, 98),
                                  claude_runner.ClaudeUsage(50, 49),
                              ]),
            [1, "capacity", 0, "evaluate"],
        )

    def test_interruption_preserves_evaluator_retry_budget(self):
        self.run_attempts([1, 0, 1, 0], [
            {"decision": "RETRY", "reason": "missing check",
             "actionable_retry_items": ["Finish the check"]},
            {"decision": "COMPLETE", "reason": "ok"},
        ])

    def test_unfinished_story_resumes_after_restart(self):
        self.run_attempts([0], [{"decision": "COMPLETE", "reason": "ok"}],
                          initial_status="UNFINISHED")
        self.assertEqual(story_state.classify_story_status("unfinished"), "UNFINISHED")

    def test_failing_runs_without_capacity_signal_escalate_instead_of_looping(self):
        # A crash, a bad invocation or a denied permission makes Claude
        # exit non-zero forever. Treating that as a capacity pause would
        # re-invoke Claude every hour with no possible progress, so it
        # must escalate to a user intervention and free the orchestrator
        # to work on something else.
        filename = "STORY-UI-002-test.md"
        story = self.write_story(
            filename,
            story_with_id("STORY-UI-002", "## Status\n\nTODO\n"),
        )
        self.write_backlog(active=[filename])
        self.set_active(filename)

        runs = []

        def run(prompt):
            runs.append(prompt)
            return claude_runner.ClaudeAttempt(1, False)

        with ExitStack() as stack:
            for name, value in [
                ("CLAUDE_RESULT_FILE", self.stories_dir / "result.md"),
                ("NEXT_PROMPT_FILE", self.stories_dir / "prompt.md"),
            ]:
                stack.enter_context(patch.object(orchestrator, name, value))
            stack.enter_context(patch.object(orchestrator, "build_claude_prompt",
                                             return_value="Implement"))
            stack.enter_context(patch.object(orchestrator, "generate_repo_map",
                                             return_value={
                                                 "enabled": False, "available": False,
                                                 "text": "", "token_budget": 0,
                                                 "char_count": 0, "approx_tokens": 0,
                                                 "duration_seconds": 0.0, "error": None,
                                             }))
            stack.enter_context(patch.object(orchestrator, "_safe_claude_usage",
                                             return_value=claude_runner.ClaudeUsage(10, 0)))
            stack.enter_context(patch.object(orchestrator, "run_claude_attempt",
                                             side_effect=run))
            stack.enter_context(patch.object(
                orchestrator, "wait_for_claude_capacity",
                side_effect=AssertionError(
                    "a failed run must never wait for capacity"),
            ))
            stack.enter_context(patch.object(
                orchestrator, "evaluate_story",
                side_effect=AssertionError(
                    "a non-zero exit is never evaluated"),
            ))

            self.assertEqual(orchestrator.execute_active_story(), "NEEDS_USER")

        self.assertEqual(len(runs), orchestrator.MAX_CLAUDE_FAILED_RUNS_PER_STORY)
        self.assertEqual(
            story_state.classify_story_status(
                story_state.extract_status_section(story.read_text())
            ),
            "BLOCKED",
        )
        self.assertEqual(len(user_interventions.list_interventions()), 1)

    def test_unfinished_is_not_selected_as_new_work(self):
        filename = "STORY-UI-001-test.md"
        self.write_story(filename, story_with_id("STORY-UI-001", "## Status\n\nUNFINISHED\n"))
        self.write_backlog(todo=[filename])
        self.assertEqual(story_state.get_selectable_story_candidates(), [])


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


class GetUnresolvedUserInterventionsTest(OrchestratorInterventionTestCase):

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
            "## User Resolution\n\nTODO\n\n"
            "## Resolution Notes\n\nTODO\n",
            encoding="utf-8",
        )

    def test_open_intervention_is_unresolved(self):
        self.write_intervention(
            "UI-001-STORY-DOM-015.md", "STORY-DOM-015", "OPEN"
        )

        unresolved = orchestrator.get_unresolved_user_interventions(
            ["UI-001"]
        )

        self.assertEqual(len(unresolved), 1)
        self.assertEqual(unresolved[0]["status"], "OPEN")

    def test_resolved_mixed_case_whitespace_is_resolved(self):
        self.write_intervention(
            "UI-002-STORY-DOM-016.md", "STORY-DOM-016", "  resolved  "
        )

        unresolved = orchestrator.get_unresolved_user_interventions(
            ["UI-002"]
        )

        self.assertEqual(unresolved, [])

    def test_missing_intervention_file_is_unresolved(self):
        unresolved = orchestrator.get_unresolved_user_interventions(
            ["UI-999"]
        )

        self.assertEqual(len(unresolved), 1)
        self.assertIsNone(unresolved[0]["file"])


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


class BlockedActiveStoryTest(OrchestratorInterventionTestCase):
    """
    Regression coverage for: if agent/CURRENT_STORY.md points at a
    story whose own '## Status' already says BLOCKED, the orchestrator
    must never call Claude Code for it, must keep/move it under
    BACKLOG '## Blocked' (idempotently -- no duplicate bullet if it is
    already there), and must let deterministic selection pick up
    other independent TODO work instead of stopping.
    """

    def test_blocked_active_story_is_skipped_without_calling_claude(self):
        blocked_filename = "STORY-DOM-015-x.md"
        other_filename = "STORY-DOM-020-y.md"

        self.write_story(
            blocked_filename,
            story_with_id(
                "STORY-DOM-015",
                "## Status\n\nBLOCKED\n",
                dependencies_text="None.",
            ),
        )
        self.write_story(
            other_filename,
            story_with_id("STORY-DOM-020", "## Status\n\nTODO\n"),
        )

        # Simulate the exact out-of-sync state this bug report
        # described: the story's own file already says BLOCKED, but
        # BACKLOG.md still lists it under '## Active' (never synced).
        self.write_backlog(active=[blocked_filename], todo=[other_filename])
        self.set_active(blocked_filename)

        result = orchestrator.execute_active_story()

        self.assertEqual(result, "BLOCKED")

        backlog = self.backlog_file.read_text(encoding="utf-8")
        self.assertEqual(
            story_state.parse_backlog_section(backlog, "Active"), []
        )
        self.assertEqual(
            story_state.parse_backlog_section(backlog, "Blocked"),
            [blocked_filename],
        )

        # Deterministic selection must now pick up the independent
        # TODO story instead of the orchestrator stopping.
        outcome = orchestrator._select_and_activate_next_story()

        self.assertEqual(outcome, "ACTIVATED")
        self.assertTrue(
            self.current_story_file.read_text(encoding="utf-8")
            .strip()
            .endswith(other_filename)
        )

    def test_already_synced_blocked_story_is_not_duplicated(self):
        filename = "STORY-DOM-015-x.md"

        self.write_story(
            filename,
            story_with_id(
                "STORY-DOM-015", "## Status\n\nBLOCKED\n",
                dependencies_text="None.",
            ),
        )
        # Already correctly parked under '## Blocked', not '## Active'.
        self.write_backlog(blocked=[filename])
        self.set_active(filename)

        result = orchestrator.execute_active_story()

        self.assertEqual(result, "BLOCKED")

        backlog = self.backlog_file.read_text(encoding="utf-8")
        self.assertEqual(
            story_state.parse_backlog_section(backlog, "Blocked"),
            [filename],
        )


if __name__ == "__main__":
    unittest.main()
