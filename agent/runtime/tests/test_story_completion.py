"""Regression tests for the story-completion lifecycle and the backlog index.

Every test here pins a failure that actually happened, in one run recorded in
`agent/logs/2026-10-03.log`:

* `STORY-WEB-018` passed the evaluator and GitHub CI (commit `2f9b2d5`), and its
  entry stayed under `## Active` with `CURRENT_STORY.md` still naming it. The
  harness logged its own inconsistency and went straight on to planning.
* That planning pass rewrote `## To Do` by hand from the rendered planner index,
  putting `backlog entry:` and `dependency note:` lines *above* their bullets --
  and an earlier activation had already orphaned one such line, because the entry
  movers only ever understood a single line.
* The pass emitted `"outcome": "DEFERRED"`, a word from the follow-up-finding
  vocabulary rather than the `phase_review` one, failed validation, and left four
  new story files and a rewritten queue behind, because validation ran after the
  guard had already returned.
* Two of those four stories duplicated finished work, because a disposition line
  written with a mangled separator (`ALREADY COVERED ? STORY-SYNC-005`) was
  invisible to the finding parser and the finding was re-supplied as new.

These exercise the real selection, orchestration, recovery and planning paths.
Nothing here calls Claude, the evaluator, the planner or git.

Run with: python -m unittest agent.runtime.tests.test_story_completion -v
(from the repository root).
"""
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch

from agent.runtime.core import backlog_writer
from agent.runtime.core import orchestrator
from agent.runtime.core import project_planner
from agent.runtime.core import story_state
from agent.runtime.core.follow_up_findings import unresolved_findings
from agent.runtime.runners import claude_runner
from agent.runtime.tests.test_orchestrator import (
    OrchestratorInterventionTestCase,
    story_with_id,
)


CANONICAL = "- STORY-WEB-018 | STORY-WEB-018-smoke.md | TODO | milestone-05 | deps: None"


class CompletionLifecycleTestCase(OrchestratorInterventionTestCase):
    """A story Claude has finished, with the harness's own steps outstanding."""

    def setUp(self):
        super().setUp()

        self.attempt_state_file = self.stories_dir / "ATTEMPT_STATE.json"
        self.result_file = self.stories_dir / "CLAUDE_RESULT.md"
        self.prompt_file = self.stories_dir / "NEXT_PROMPT.md"

        for name, value in [
            ("ATTEMPT_STATE_FILE", self.attempt_state_file),
            ("CLAUDE_RESULT_FILE", self.result_file),
            ("NEXT_PROMPT_FILE", self.prompt_file),
        ]:
            self._stack.enter_context(patch.object(orchestrator, name, value))

        self.result_file.write_text("Finished work.", encoding="utf-8")

        self.filename = "STORY-WEB-018-smoke.md"
        self.story = self.write_story(
            self.filename,
            story_with_id("STORY-WEB-018", "## Status\n\nTODO\n")
            + "\n## Title\n\nReject stale frontend bundles\n"
            + "\n## Result\n\nNot started.\n",
        )

    def claude_writes_done(self, *args, **kwargs):
        """Stand in for a Claude attempt that finished and recorded DONE.

        The story must not start out DONE: `execute_active_story()` reads a
        pre-set DONE status as "already finished" and short-circuits before the
        evaluator and the gate, so a fixture that pre-sets it would never reach
        the branch under test.
        """

        self.story.write_text(
            self.story.read_text(encoding="utf-8")
            .replace("## Status\n\nTODO\n", "## Status\n\nDONE\n")
            .replace("Not started.", "Work Claude finished."),
            encoding="utf-8",
        )

        return claude_runner.ClaudeAttempt(0, False)

    def given_finished_story(self, phase, **state):
        """A story Claude already finished, with `phase` still outstanding."""

        self.claude_writes_done()
        self.activate()
        orchestrator.record_attempt_state(self.story, phase, **state)

    def activate(self):
        self.write_backlog(active=[self.filename])
        self.set_active(self.filename)

    def run_execute(self, stack, **patches):
        stack.enter_context(
            patch.object(orchestrator, "build_claude_prompt", return_value="Implement")
        )
        stack.enter_context(
            patch.object(orchestrator, "generate_repo_map",
                         return_value={"enabled": False, "text": "",
                                       "token_budget": 0, "error": None})
        )
        stack.enter_context(
            patch.object(orchestrator, "get_claude_usage",
                         return_value=claude_runner.ClaudeUsage(0, 0))
        )
        stack.enter_context(patch.object(orchestrator.time, "sleep"))

        for name, value in patches.items():
            stack.enter_context(patch.object(orchestrator, name, **value))

        return orchestrator.execute_active_story()

    def assert_finalized(self):
        """The complete transition, as one assertion."""

        backlog = self.backlog_file.read_text(encoding="utf-8")

        self.assertEqual(
            story_state.parse_backlog_section(backlog, "Active"), [],
            "a completed story must not remain under '## Active'",
        )
        self.assertIn(
            self.filename, story_state.parse_backlog_section(backlog, "Done"),
        )
        self.assertEqual(
            self.current_story_file.read_text(encoding="utf-8").strip(), "",
            "CURRENT_STORY.md must be cleared so selection can run",
        )
        self.assertEqual(
            story_state.classify_story_status(
                story_state.extract_status_section(self.story.read_text())
            ),
            "DONE",
        )
        self.assertFalse(
            self.attempt_state_file.exists(),
            "a terminal outcome clears the attempt record",
        )
        self.assertEqual(story_state.validate_backlog_consistency(), [])


class PassingCiCompletesTheTransitionTest(CompletionLifecycleTestCase):

    def test_implementation_cannot_finalize_or_deactivate_before_evaluation(self):
        self.activate()

        def fake_claude(*_args, **_kwargs):
            content = self.story.read_text(encoding="utf-8")
            self.story.write_text(
                content.replace("## Status\n\nTODO\n", "## Status\n\nDONE\n"),
                encoding="utf-8",
            )
            self.backlog_file.write_text("# Agent moved story to Done\n", encoding="utf-8")
            self.current_story_file.write_text("", encoding="utf-8")
            return claude_runner.ClaudeAttempt(0, False)

        def evaluate_after_restore(*_args, **_kwargs):
            self.assertEqual(
                "TODO",
                story_state.classify_story_status(
                    story_state.extract_status_section(self.story.read_text(encoding="utf-8"))
                ),
            )
            self.assertEqual([self.filename], story_state.parse_backlog_section(
                self.backlog_file.read_text(encoding="utf-8"), "Active"
            ))
            self.assertEqual(
                self.filename,
                self.current_story_file.read_text(encoding="utf-8").strip(),
            )
            return {"decision": "COMPLETE", "reason": "fixture review passed"}

        with ExitStack() as stack:
            stack.enter_context(patch.object(orchestrator, "evaluate_story", side_effect=evaluate_after_restore))
            stack.enter_context(patch.object(orchestrator, "run_claude_attempt", side_effect=fake_claude))
            stack.enter_context(patch.object(orchestrator, "verify_with_github_ci", return_value={
                "status": "SKIPPED", "reason": "fixture", "report": "", "sha": "", "run_urls": []
            }))
            self.assertEqual(self.run_execute(stack), "COMPLETE")

        self.assert_finalized()

    def test_successful_ci_moves_the_story_clears_the_pointer_and_validates(self):
        self.activate()

        with ExitStack() as stack:
            stack.enter_context(
                patch.object(orchestrator, "evaluate_story",
                             return_value={"decision": "COMPLETE", "reason": "ok"})
            )
            stack.enter_context(
                patch.object(orchestrator, "run_claude_attempt",
                             side_effect=self.claude_writes_done)
            )
            stack.enter_context(
                patch.object(orchestrator, "verify_with_github_ci",
                             return_value={"status": "PASSED", "reason": "CI: success",
                                           "report": "", "sha": "2f9b2d5" * 5,
                                           "run_urls": []})
            )
            self.assertEqual(self.run_execute(stack), "COMPLETE")

        self.assert_finalized()

        self.assertTrue(
            any("completed and finalized" in line for line in self.logged),
            f"the transition must be logged; got {self.logged}",
        )

    def test_a_skipped_gate_completes_the_same_way(self):
        """No CI configured is still a completion, so it must still transition."""

        self.activate()

        with ExitStack() as stack:
            stack.enter_context(
                patch.object(orchestrator, "evaluate_story",
                             return_value={"decision": "COMPLETE", "reason": "ok"})
            )
            stack.enter_context(
                patch.object(orchestrator, "run_claude_attempt",
                             side_effect=self.claude_writes_done)
            )
            stack.enter_context(
                patch.object(orchestrator, "verify_with_github_ci",
                             return_value={"status": "SKIPPED", "reason": "no remote",
                                           "report": "", "sha": "", "run_urls": []})
            )
            self.assertEqual(self.run_execute(stack), "COMPLETE")

        self.assert_finalized()

    def test_a_failing_gate_does_not_finalize_anything(self):
        """The counterpart: a red pipeline must leave the story active."""

        self.activate()

        with ExitStack() as stack:
            stack.enter_context(
                patch.object(orchestrator, "evaluate_story",
                             return_value={"decision": "COMPLETE", "reason": "ok"})
            )
            stack.enter_context(
                patch.object(orchestrator, "run_claude_attempt",
                             side_effect=self.claude_writes_done)
            )
            stack.enter_context(
                patch.object(orchestrator, "verify_with_github_ci",
                             return_value={"status": "FAILED", "reason": "red",
                                           "report": "one test failed",
                                           "sha": "abc1234", "run_urls": []})
            )
            stack.enter_context(patch.object(orchestrator, "MAX_CI_FIX_ATTEMPTS", 0))
            self.run_execute(stack)

        backlog = self.backlog_file.read_text(encoding="utf-8")

        self.assertNotIn(
            self.filename, story_state.parse_backlog_section(backlog, "Done"),
        )
        self.assertNotEqual(
            self.current_story_file.read_text(encoding="utf-8").strip(), "",
        )


class InterruptedFinalizationTest(CompletionLifecycleTestCase):

    def test_restart_during_finalization_finishes_it_without_rerunning_anything(self):
        """A FINALIZING record means CI already passed; only bookkeeping is left."""

        self.given_finished_story(
            "FINALIZING",
            ci_status="PASSED", ci_sha="2f9b2d54d99d205f100f5a17be066d432b37764f",
        )

        with ExitStack() as stack:
            claude = stack.enter_context(
                patch.object(orchestrator, "run_claude_attempt")
            )
            evaluate = stack.enter_context(
                patch.object(orchestrator, "evaluate_story")
            )
            verify = stack.enter_context(
                patch.object(orchestrator, "verify_with_github_ci")
            )
            self.assertEqual(self.run_execute(stack), "COMPLETE")

        self.assert_finalized()

        # Nothing that already answered is asked again. Re-running the gate
        # would buy a second verdict on an already-published commit; re-running
        # Claude would redo accepted work.
        claude.assert_not_called()
        evaluate.assert_not_called()
        verify.assert_not_called()

        self.assertTrue(
            any("Resuming" in line and "FINALIZING" in line for line in self.logged),
            f"the resume must be logged; got {self.logged}",
        )

    def test_finalizing_is_idempotent_so_a_second_interrupt_is_harmless(self):
        self.claude_writes_done()
        self.activate()

        orchestrator.finalize_completed_story(self.story, "PASSED", "2f9b2d5")
        first = self.backlog_file.read_text(encoding="utf-8")

        orchestrator.finalize_completed_story(self.story, "PASSED", "2f9b2d5")

        self.assertEqual(self.backlog_file.read_text(encoding="utf-8"), first)
        self.assertEqual(
            story_state.parse_backlog_section(first, "Done").count(self.filename), 1,
        )
        self.assert_finalized()

    def test_an_unfinished_ci_gate_is_never_bypassed_by_a_restart(self):
        """AWAITING_CI still publishes and waits; it does not shortcut to done."""

        self.given_finished_story("AWAITING_CI")

        with ExitStack() as stack:
            verify = stack.enter_context(
                patch.object(orchestrator, "verify_with_github_ci",
                             return_value={"status": "PASSED", "reason": "CI: success",
                                           "report": "", "sha": "deadbee",
                                           "run_urls": []})
            )
            stack.enter_context(patch.object(orchestrator, "run_claude_attempt"))
            self.assertEqual(self.run_execute(stack), "COMPLETE")

        verify.assert_called_once()
        self.assert_finalized()

    def test_a_done_story_already_published_still_completes_its_transition(self):
        """The 'treat as complete' path used to return without moving anything."""

        self.claude_writes_done()
        self.activate()

        with ExitStack() as stack:
            stack.enter_context(
                patch.object(orchestrator, "unpublished_completion", return_value=None)
            )
            claude = stack.enter_context(
                patch.object(orchestrator, "run_claude_attempt")
            )
            self.assertEqual(self.run_execute(stack), "COMPLETE")

        claude.assert_not_called()
        self.assert_finalized()


class CompletedWorkIsNeverSelectedTest(OrchestratorInterventionTestCase):

    def _story(self, filename, story_id, status):
        return self.write_story(
            filename, story_with_id(story_id, f"## Status\n\n{status}\n")
        )

    def test_done_unfinished_and_superseded_entries_are_not_selectable(self):
        """A stale To Do row must never hand finished or retired work to Claude."""

        self._story("STORY-A-001-done.md", "STORY-A-001", "DONE")
        self._story("STORY-A-002-superseded.md", "STORY-A-002", "SUPERSEDED")
        self._story("STORY-A-003-unfinished.md", "STORY-A-003", "UNFINISHED")
        self._story("STORY-A-004-ready.md", "STORY-A-004", "TODO")

        self.write_backlog(todo=[
            "STORY-A-001-done.md",
            "STORY-A-002-superseded.md",
            "STORY-A-003-unfinished.md",
            "STORY-A-004-ready.md",
        ])

        self.assertEqual(
            [path.name for path in story_state.get_selectable_story_candidates()],
            ["STORY-A-004-ready.md"],
        )

    def test_superseded_is_classified_rather_than_falling_through_to_other(self):
        """OTHER means "still executable" to every caller, so this must not be OTHER."""

        self.assertEqual(
            story_state.classify_story_status("SUPERSEDED by the Product Owner"),
            "SUPERSEDED",
        )

    def test_a_done_story_left_under_active_or_to_do_is_reported(self):
        self._story("STORY-A-001-done.md", "STORY-A-001", "DONE")
        self.write_backlog(active=["STORY-A-001-done.md"])
        self.current_story_file.write_text("", encoding="utf-8")

        problems = story_state.validate_backlog_consistency()

        self.assertTrue(
            any("must not remain active" in problem for problem in problems),
            problems,
        )

    def test_the_story_being_published_is_excused_while_its_gate_is_open(self):
        """The one tree the CI gate is handed: pushed, DONE, not yet moved."""

        self._story("STORY-A-001-done.md", "STORY-A-001", "DONE")
        self.write_backlog(active=["STORY-A-001-done.md"])
        self.set_active("STORY-A-001-done.md")

        strict = story_state.validate_backlog_consistency()

        self.assertTrue(
            any("must not remain active" in problem for problem in strict), strict,
        )
        self.assertTrue(
            any("is DONE but is still listed" in problem for problem in strict),
            strict,
        )

        self.assertEqual(
            story_state.validate_backlog_consistency(
                publishing="STORY-A-001-done.md"
            ),
            [],
        )

    def test_publishing_excuses_neither_a_retired_story_nor_the_rest_of_the_queue(self):
        """Only a DONE story's own publication window, never anything else."""

        self._story("STORY-A-001-superseded.md", "STORY-A-001", "SUPERSEDED")
        self._story("STORY-A-002-done.md", "STORY-A-002", "DONE")
        self.write_backlog(
            active=["STORY-A-001-superseded.md"], todo=["STORY-A-002-done.md"],
        )
        self.set_active("STORY-A-001-superseded.md")

        problems = story_state.validate_backlog_consistency(
            publishing="STORY-A-001-superseded.md"
        )

        self.assertTrue(
            any("whose own Status is SUPERSEDED" in problem for problem in problems),
            problems,
        )
        self.assertTrue(
            any(
                "'## To Do' lists STORY-A-002-done.md" in problem
                for problem in problems
            ),
            problems,
        )


class BacklogIsAnIndexTest(unittest.TestCase):
    """The entry format, and what the movers and validator do with it."""

    def section(self, heading, body):
        return f"# Backlog\n\n## {heading}\n\n{body}\n\n## Done\n\n_(none)_\n"

    def test_the_writer_produces_one_canonical_line(self):
        self.assertEqual(
            story_state.format_backlog_entry(
                "STORY-WEB-021", "STORY-WEB-021-hooks.md", "todo", "milestone-05",
                ["STORY-WEB-019", "STORY-WEB-022"],
            ),
            "- STORY-WEB-021 | STORY-WEB-021-hooks.md | TODO | milestone-05 | "
            "deps: STORY-WEB-019, STORY-WEB-022",
        )

    def test_an_unsupported_status_is_refused_rather_than_written(self):
        with self.assertRaises(ValueError):
            story_state.format_backlog_entry(
                "STORY-WEB-021", "STORY-WEB-021-hooks.md", "ALMOST-DONE",
                "milestone-05", [],
            )

    def test_round_trip_through_the_parser_keeps_every_field(self):
        line = story_state.format_backlog_entry(
            "STORY-WEB-020", "STORY-WEB-020-fixture.md", "TODO", "milestone-05",
            ["STORY-WEB-019"],
        )

        self.assertEqual(story_state.parse_backlog_entry(line), {
            "story_id": "STORY-WEB-020",
            "filename": "STORY-WEB-020-fixture.md",
            "status": "TODO",
            "milestone": "milestone-05",
            "dependencies": ["STORY-WEB-019"],
        })

    def test_moving_an_entry_leaves_no_orphaned_prose_behind(self):
        """The orphan bug: the mover understood one line, entries had three."""

        backlog = (
            "# Backlog\n\n"
            "## Active\n\n\n\n"
            "## To Do\n\n"
            "- STORY-WEB-018 | STORY-WEB-018-smoke.md | TODO | milestone-05 | deps: None\n"
            "  dependency note: - STORY-WEB-017 (DONE).\n"
            "  backlog entry: milestone-05: Reject stale frontend bundles.\n"
            "- STORY-WEB-020 | STORY-WEB-020-fixture.md | TODO | milestone-05 | deps: None\n"
            "\n## Done\n\n_(none)_\n"
        )

        moved = story_state.move_backlog_entry_to_active(
            backlog, "STORY-WEB-018-smoke.md"
        )

        self.assertNotIn("dependency note:", moved)
        self.assertNotIn("backlog entry:", moved)
        self.assertEqual(
            story_state.parse_backlog_section(moved, "Active"),
            ["STORY-WEB-018-smoke.md"],
        )
        self.assertEqual(
            story_state.parse_backlog_section(moved, "To Do"),
            ["STORY-WEB-020-fixture.md"],
        )
        self.assertEqual(story_state.validate_backlog_entries(moved), [])

    def test_moving_to_done_restamps_the_status_and_carries_the_title(self):
        backlog = self.section(
            "Active",
            "- STORY-WEB-018 | STORY-WEB-018-smoke.md | ACTIVE | milestone-05 | deps: None",
        )

        moved = story_state.move_backlog_entry_to_done(
            backlog, "STORY-WEB-018-smoke.md", "Reject stale frontend bundles",
        )

        self.assertEqual(story_state.parse_backlog_section(moved, "Active"), [])
        self.assertIn(
            "- STORY-WEB-018 | STORY-WEB-018-smoke.md | DONE | milestone-05 | "
            "Reject stale frontend bundles",
            moved,
        )
        self.assertEqual(story_state.validate_backlog_entries(moved), [])

    def test_supplementary_prose_is_rejected_wherever_it_appears(self):
        for prose in (
            "backlog entry: milestone-05: Reject stale frontend bundles.",
            "  dependency note: - STORY-WEB-019 (DONE).",
            "disposition: retired by product decision.",
        ):
            with self.subTest(prose=prose):
                backlog = self.section(
                    "To Do",
                    prose + "\n"
                    "- STORY-WEB-020 | STORY-WEB-020-fixture.md | TODO | "
                    "milestone-05 | deps: None",
                )

                problems = story_state.validate_backlog_entries(backlog)

                self.assertTrue(
                    any("supplementary prose" in problem for problem in problems),
                    problems,
                )

    def test_a_malformed_entry_in_a_queue_section_is_rejected(self):
        backlog = self.section("To Do", "- STORY-WEB-020 is next, probably")

        problems = story_state.validate_backlog_entries(backlog)

        self.assertTrue(
            any("canonical entries" in problem for problem in problems), problems,
        )

    def test_validation_accepts_what_selection_can_actually_parse(self):
        """Not stricter than the parser: both older shapes still select."""

        for row in (
            "- `STORY-WEB-020-fixture.md`",
            "- STORY-WEB-020 | STORY-WEB-020-fixture.md | TODO | milestone-05: fixture.",
        ):
            with self.subTest(row=row):
                backlog = self.section("To Do", row)

                self.assertEqual(story_state.validate_backlog_entries(backlog), [])
                self.assertEqual(
                    story_state.parse_backlog_section(backlog, "To Do"),
                    ["STORY-WEB-020-fixture.md"],
                )

    def test_stripping_prose_is_idempotent(self):
        backlog = self.section("To Do", "  backlog entry: anything\n" + CANONICAL)

        once = story_state.strip_backlog_prose(backlog)

        self.assertEqual(story_state.strip_backlog_prose(once), once)
        self.assertEqual(story_state.validate_backlog_entries(once), [])


class BacklogWriterTest(unittest.TestCase):
    """The planner's only supported route into the index."""

    def setUp(self):
        import tempfile

        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.backlog = Path(directory.name) / "BACKLOG.md"
        self.backlog.write_text(
            "# Backlog\n\n## Active\n\n\n\n## To Do\n\n"
            "- STORY-WEB-020 | STORY-WEB-020-fixture.md | TODO | milestone-05 | deps: None\n"
            "\n## Blocked\n\n\n\n## Done\n\n_(none)_\n",
            encoding="utf-8",
        )

    def test_entries_are_appended_in_priority_order_and_stay_canonical(self):
        added = backlog_writer.add_to_do_entries(
            [
                ("STORY-WEB-024", "STORY-WEB-024-contracts.md", "milestone-05",
                 ["STORY-WEB-018"]),
                ("STORY-WEB-027", "STORY-WEB-027-control.md", "milestone-05", []),
            ],
            backlog_file=self.backlog,
        )

        content = self.backlog.read_text(encoding="utf-8")

        self.assertEqual(
            added, ["STORY-WEB-024-contracts.md", "STORY-WEB-027-control.md"],
        )
        self.assertEqual(
            story_state.parse_backlog_section(content, "To Do"),
            [
                "STORY-WEB-020-fixture.md",
                "STORY-WEB-024-contracts.md",
                "STORY-WEB-027-control.md",
            ],
        )
        self.assertEqual(story_state.validate_backlog_entries(content), [])

    def test_an_already_listed_story_is_not_duplicated(self):
        added = backlog_writer.add_to_do_entries(
            [("STORY-WEB-020", "STORY-WEB-020-fixture.md", "milestone-05", [])],
            backlog_file=self.backlog,
        )

        self.assertEqual(added, [])
        self.assertEqual(
            story_state.parse_backlog_section(
                self.backlog.read_text(encoding="utf-8"), "To Do"
            ),
            ["STORY-WEB-020-fixture.md"],
        )

    def test_pre_existing_prose_is_cleaned_up_by_the_next_write(self):
        self.backlog.write_text(
            self.backlog.read_text(encoding="utf-8").replace(
                "## To Do\n\n",
                "## To Do\n\nbacklog entry: milestone-05: orphaned.\n",
            ),
            encoding="utf-8",
        )

        backlog_writer.add_to_do_entries(
            [("STORY-WEB-024", "STORY-WEB-024-contracts.md", "milestone-05", [])],
            backlog_file=self.backlog,
        )

        content = self.backlog.read_text(encoding="utf-8")

        self.assertNotIn("backlog entry:", content)
        self.assertEqual(story_state.validate_backlog_entries(content), [])

    def test_a_write_that_would_break_the_index_is_refused_outright(self):
        before = self.backlog.read_text(encoding="utf-8")

        with self.assertRaises(backlog_writer.BacklogWriteRejected):
            backlog_writer.rewrite_section(
                "To Do",
                [("STORY-WEB-020", "STORY-WEB-020-fixture.md", "WHENEVER",
                  "milestone-05", [])],
                backlog_file=self.backlog,
            )

        self.assertEqual(self.backlog.read_text(encoding="utf-8"), before)


class PlanningOutcomeValidationTest(unittest.TestCase):

    def test_deferred_is_not_a_phase_review_outcome(self):
        """The exact value one real run emitted, from the wrong vocabulary."""

        self.assertNotIn("DEFERRED", project_planner.REVIEW_OUTCOMES)
        self.assertIn("DEFERRED", project_planner.FINDING_DISPOSITIONS)

        problems = project_planner._validate_phase_review(
            {
                "independent_work_remaining": False,
                "phase_review": [
                    {"area": "Optional contrast selectors", "outcome": "DEFERRED",
                     "references": ["STORY-WEB-019"]},
                ],
            },
            made_progress=True,
        )

        self.assertTrue(
            any("DEFERRED" in problem for problem in problems), problems,
        )

    def test_the_prompt_states_the_accepted_values_from_one_definition(self):
        """The planner is shown exactly what the validator accepts."""

        prompt = project_planner.build_planning_prompt()

        for outcome in project_planner.REVIEW_OUTCOMES:
            self.assertIn(f'"{outcome}"', prompt)

        self.assertIn("not phase_review outcomes", prompt)
        self.assertIn("DEFERRED in particular is not an accepted", prompt)

    def test_an_unsupported_outcome_is_not_silently_reinterpreted(self):
        """No mapping of DEFERRED onto PREREQUISITE or COVERED behind the scenes."""

        problems = project_planner._validate_phase_review(
            {
                "independent_work_remaining": False,
                "phase_review": [
                    {"area": "Something", "outcome": "DEFERRED",
                     "references": ["docs/ROADMAP.md"]},
                ],
            },
            made_progress=True,
        )

        self.assertNotEqual(problems, [])


class PlanningRollbackTest(unittest.TestCase):
    """A failed pass leaves the previously valid queue, and nothing of its own."""

    def setUp(self):
        import tempfile

        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name).resolve()
        self.agent = self.root / "agent"
        self.agent.mkdir()

        self.active = self.agent / "active.md"
        self.active.write_text("## Status\n\nUNFINISHED\n", encoding="utf-8")

        self.pointer = self.agent / "CURRENT_STORY.md"
        self.pointer.write_text("agent/active.md", encoding="utf-8")

        self.backlog = self.agent / "BACKLOG.md"
        self.valid_queue = (
            "# Backlog\n\n## Active\n\n\n\n## To Do\n\n" + CANONICAL + "\n"
            "\n## Done\n\n_(none)_\n"
        )
        self.backlog.write_text(self.valid_queue, encoding="utf-8")

        self.result = self.agent / "PLANNING_RESULT.json"

        self._stack = ExitStack()
        self.addCleanup(self._stack.close)

        for name, value in (
            ("REPO_ROOT", self.root),
            ("CURRENT_STORY_FILE", self.pointer),
            ("BACKLOG_FILE", self.backlog),
            ("PLANNING_RESULT_FILE", self.result),
        ):
            self._stack.enter_context(patch.object(project_planner, name, value))

        self._stack.enter_context(
            patch.object(project_planner, "get_active_story_path",
                         return_value=self.active)
        )

    def test_a_failed_validation_rolls_back_every_change_the_pass_made(self):
        new_story = self.agent / "STORY-WEB-025-duplicate.md"

        def run(prompt):
            # Exactly the shape of the real failure: files created, the queue
            # rewritten, a clean exit, and an invalid result.
            new_story.write_text("## Status\n\nTODO\n", encoding="utf-8")
            self.backlog.write_text(
                self.valid_queue.replace(
                    "## To Do\n\n",
                    "## To Do\n\nbacklog entry: milestone-05: new work.\n",
                ),
                encoding="utf-8",
            )
            self.result.write_text('{"status":"COMPLETE"}', encoding="utf-8")
            return 0

        def validate(code):
            return {"status": "FAILED", "reason": "invalid phase_review outcome",
                    "validation_problems": ["Invalid phase_review outcome: 'DEFERRED'"]}

        with patch.object(project_planner, "run_local_planner", side_effect=run), \
                self.assertRaises(project_planner.PlanningRejected):
            project_planner._run_guarded_planner("plan", validate=validate)

        self.assertFalse(
            new_story.exists(), "a rejected pass must not leave its story files"
        )
        self.assertEqual(
            self.backlog.read_text(encoding="utf-8"), self.valid_queue,
            "the previously valid queue must survive a failed pass",
        )

    def test_a_valid_pass_keeps_its_changes(self):
        def run(prompt):
            self.backlog.write_text(
                self.valid_queue.replace(
                    CANONICAL + "\n",
                    CANONICAL + "\n- STORY-WEB-024 | STORY-WEB-024-c.md | TODO | "
                    "milestone-05 | deps: None\n",
                ),
                encoding="utf-8",
            )
            self.result.write_text('{"status":"COMPLETE"}', encoding="utf-8")
            return 0

        def validate(code):
            return {"status": "COMPLETE", "reason": "ok"}

        with patch.object(project_planner, "run_local_planner", side_effect=run):
            code, validated = project_planner._run_guarded_planner(
                "plan", validate=validate
            )

        self.assertEqual(code, 0)
        self.assertEqual(validated["status"], "COMPLETE")
        self.assertIn("STORY-WEB-024-c.md", self.backlog.read_text(encoding="utf-8"))

    def test_an_index_broken_by_the_pass_fails_it(self):
        """Validated with the result: a pass that breaks selection has failed."""

        self.backlog.write_text(
            self.valid_queue.replace(
                "## To Do\n\n", "## To Do\n\nbacklog entry: milestone-05: orphan.\n"
            ),
            encoding="utf-8",
        )

        problems = story_state.validate_backlog_entries(
            self.backlog.read_text(encoding="utf-8")
        )

        self.assertTrue(
            any("supplementary prose" in problem for problem in problems), problems,
        )


class FindingDispositionTest(unittest.TestCase):
    """A recorded disposition must not reappear as new work."""

    def story(self, disposition):
        return (
            "## Status\n\nDONE\n\n"
            "## Follow-up Findings Disposition\n"
            f"{disposition}\n\n"
            "## Follow-up Findings\n\n"
            "F002: the account smoke bank reload window is timing dependent.\n"
        )

    def test_a_mangled_separator_no_longer_hides_a_disposition(self):
        """The exact line that produced two duplicate stories."""

        content = self.story("F002: ALREADY COVERED ? STORY-SYNC-005 isolates it.")

        self.assertEqual(unresolved_findings(content, "STORY-SYNC-004.md"), [])

    def test_every_separator_spelling_is_accepted(self):
        for separator in ("—", "-", ":", "?", "--", ""):
            with self.subTest(separator=separator):
                content = self.story(
                    f"F002: ALREADY COVERED {separator} STORY-SYNC-005 isolates it."
                )

                self.assertEqual(
                    unresolved_findings(content, "STORY-SYNC-004.md"), [],
                )

    def test_an_undispositioned_finding_is_still_reported(self):
        content = self.story("F001: DISMISSED — unrelated.")

        self.assertEqual(
            [item["id"] for item in unresolved_findings(content, "S.md")], ["F002"],
        )

    def test_the_repository_has_no_invisible_dispositions_left(self):
        """Guards the repair: nine such lines existed across six story files."""

        from agent.runtime.support.config import STORIES_DIR

        import re

        damaged = []

        for path in sorted(STORIES_DIR.glob("STORY-*.md")):
            for line in path.read_text(encoding="utf-8").splitlines():
                if re.match(
                    r"^F\d{3}:\s*(ALREADY COVERED|FOLLOW-UP STORY|DEFERRED|DISMISSED)\s*\?",
                    line,
                ):
                    damaged.append(f"{path.name}: {line}")

        self.assertEqual(damaged, [])


class DocumentOwnershipTest(unittest.TestCase):
    """The ownership contract is one definition, used for prompt and enforcement."""

    def test_harness_owned_state_is_read_only_for_every_model_role(self):
        from agent.runtime.support import config

        for role in (config.PLANNER, config.ARCHITECT, config.IMPLEMENTATION):
            with self.subTest(role=role):
                protected = config.protected_paths_for(role)

                for path in config.HARNESS_OWNED_PATHS:
                    self.assertIn(path, protected)

    def test_no_role_may_rewrite_a_role_contract(self):
        from agent.runtime.support import config

        for role in (config.PLANNER, config.ARCHITECT, config.IMPLEMENTATION):
            with self.subTest(role=role):
                protected = config.protected_paths_for(role)

                for path in config.ROLE_CONTRACT_FILES:
                    self.assertIn(path, protected)

    def test_the_guard_protects_exactly_what_the_table_declares(self):
        from agent.runtime.support import config

        declared = set(config.protected_paths_for(config.PLANNER))
        enforced = set(project_planner._planner_protected_paths())

        self.assertTrue(
            declared.issubset(enforced),
            f"declared but not enforced: {declared - enforced}",
        )

    def test_each_prompt_renders_the_table_for_its_own_role(self):
        from agent.runtime.support import config

        table = config.ownership_table(config.PLANNER)

        self.assertIn("agent/CURRENT_STORY.md [READ-ONLY for you]", table)
        self.assertIn("agent/stories/BACKLOG.md [you may write]", table)

        implementation = config.ownership_table(config.IMPLEMENTATION)

        self.assertIn("agent/stories/BACKLOG.md [READ-ONLY for you]", implementation)


class QueueConsistencyTest(unittest.TestCase):
    """The live repository's own queue, as a standing invariant."""

    def story_awaiting_its_gate(self):
        """The story this very commit publishes, while its gate is open.

        This suite runs inside the CI gate, against the commit the harness
        just pushed -- and that commit is taken mid-transition on purpose:
        the orchestrator commits the accepted story, waits for *this* run's
        verdict, and only then sets the status, moves the entry out of
        '## Active' and clears the pointer, because a red verdict has to
        hand the story back as active work. So Status DONE + an '## Active'
        entry + a pointer still naming it is the normal content of every
        commit a story completes in (commit 3084e18 failed here for exactly
        that, as 61cd1a0 did before it).

        A checkout cannot tell that window apart from a finalization that
        never ran: the trees are identical, and the record that separates
        them -- ATTEMPT_STATE.json -- is a gitignored runtime artifact. So
        the exception is granted here, to this one story only, and the rule
        itself stays covered where it is decidable: in the running harness
        (which validates strictly, and logged exactly this for
        STORY-WEB-018), in assert_finalized() above, and in
        CompletedWorkIsNeverSelectedTest.
        """

        from agent.runtime.support.config import BACKLOG_FILE

        active = story_state.parse_backlog_section(
            BACKLOG_FILE.read_text(encoding="utf-8"), "Active"
        )

        try:
            pointer = story_state.get_active_story_path()
        except (FileNotFoundError, RuntimeError):
            return None

        if pointer is None or not active or active[0] != pointer.name:
            return None

        status = story_state.classify_story_status(
            story_state.extract_status_section(
                pointer.read_text(encoding="utf-8")
            )
        )

        return pointer.name if status == "DONE" else None

    def test_the_committed_backlog_is_a_valid_machine_readable_index(self):
        from agent.runtime.support.config import BACKLOG_FILE

        self.assertEqual(
            story_state.validate_backlog_entries(
                BACKLOG_FILE.read_text(encoding="utf-8")
            ),
            [],
        )

    def test_the_committed_queue_is_internally_consistent(self):
        self.assertEqual(
            story_state.validate_backlog_consistency(
                publishing=self.story_awaiting_its_gate()
            ),
            [],
        )

    def test_every_selectable_story_has_its_dependencies_satisfied(self):
        index = story_state.build_story_index()

        for path in story_state.get_selectable_story_candidates():
            with self.subTest(story=path.name):
                self.assertEqual(
                    story_state.get_unsatisfied_dependencies(
                        path.read_text(encoding="utf-8"), index
                    ),
                    [],
                )

    def test_no_superseded_or_done_story_sits_in_the_queue(self):
        from agent.runtime.support.config import BACKLOG_FILE, STORIES_DIR

        backlog = BACKLOG_FILE.read_text(encoding="utf-8")
        publishing = self.story_awaiting_its_gate()

        for heading in ("Active", "To Do"):
            for filename in story_state.parse_backlog_section(backlog, heading):
                path = STORIES_DIR / filename

                if not path.exists() or filename == publishing:
                    continue

                with self.subTest(section=heading, story=filename):
                    self.assertNotIn(
                        story_state.classify_story_status(
                            story_state.extract_status_section(
                                path.read_text(encoding="utf-8")
                            )
                        ),
                        ("DONE", "SUPERSEDED"),
                    )


if __name__ == "__main__":
    unittest.main()
