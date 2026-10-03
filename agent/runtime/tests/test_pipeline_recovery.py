"""
Tests for the harness recovering its OWN pipeline position, and for the
backlog bookkeeping that goes with a story leaving '## Active'.

The property under test is the one the reported incident violated: a
story's "## Status" is written by Claude and says nothing about whether
the harness has evaluated, committed, pushed and CI-verified that work.
Reading "Status: DONE" as "story complete" let an evaluator outage,
a Ctrl+C or a crash between those two facts turn finished work into a
skipped story -- the next run selected a fresh story and left the
previous one unevaluated and unpushed.

Never calls Claude/the evaluator/the planner, never touches git or the
real agent/logs/.

Run with: python -m unittest agent.runtime.tests.test_pipeline_recovery -v
(from the repository root).
"""

import io
import hashlib
import json
import unittest
from contextlib import ExitStack
from unittest.mock import Mock, patch

from agent.runtime.core import orchestrator
from agent.runtime.core import story_state
from agent.runtime.evaluation.evaluator import parse_evaluator_verdict
from agent.runtime.runners import claude_runner, local_planner_runner
from agent.runtime.support import git_sync
from agent.runtime.support.capacity import ModelCapacityUnavailable
from agent.runtime.tests import REAL_RUN_CODEX
from agent.runtime.tests.test_orchestrator import (
    OrchestratorInterventionTestCase,
    story_with_id,
)


def _fake_codex_process(events):
    """A Popen stand-in that replays a Codex JSON event stream."""

    process = Mock()
    process.stdin = Mock()
    process.stdout = io.StringIO(
        "".join(json.dumps(event) + "\n" for event in events)
    )
    process.stderr = io.StringIO("")
    process.wait.return_value = 0

    return process


class PipelineRecoveryTestCase(OrchestratorInterventionTestCase):
    """The shared fixture plus a redirected ATTEMPT_STATE.json."""

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
            self._stack.enter_context(
                patch.object(orchestrator, name, value)
            )

        self.result_file.write_text(
            "Implementation finished before the interruption.",
            encoding="utf-8",
        )

    def given_finished_attempt(self, phase, status="DONE", **budgets):
        """A story Claude has finished, with `phase` still outstanding."""

        filename = "STORY-DOM-001-interrupted.md"
        story = self.write_story(
            filename,
            story_with_id("STORY-DOM-001", f"## Status\n\n{status}\n")
            + "\n## Result\n\nWork Claude already finished.\n",
        )
        self.write_backlog(active=[filename])
        self.set_active(filename)

        orchestrator.record_attempt_state(story, phase, **budgets)

        return story

    def run_execute(self, stack, **patches):
        """execute_active_story() with every model path accounted for."""

        stack.enter_context(
            patch.object(orchestrator, "build_claude_prompt",
                         return_value="Implement active story")
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
            stack.enter_context(
                patch.object(orchestrator, name, **value)
            )

        return orchestrator.execute_active_story()


class ResumeAfterInterruptionTest(PipelineRecoveryTestCase):

    def test_done_story_awaiting_evaluation_resumes_without_rerunning_claude(self):
        self.given_finished_attempt("AWAITING_EVALUATION")

        with ExitStack() as stack:
            claude = stack.enter_context(
                patch.object(orchestrator, "run_claude_attempt")
            )
            evaluate = stack.enter_context(
                patch.object(orchestrator, "evaluate_story",
                             return_value={"decision": "COMPLETE",
                                           "reason": "ok"})
            )
            result = self.run_execute(stack)

        self.assertEqual(result, "COMPLETE")
        # The whole point: the evaluation that never happened happens, and
        # the Claude run that already happened is not paid for twice.
        evaluate.assert_called_once()
        claude.assert_not_called()
        self.assertFalse(self.attempt_state_file.exists())

    def test_awaiting_ci_resumes_at_publication_without_reevaluating(self):
        self.given_finished_attempt("AWAITING_CI")

        with ExitStack() as stack:
            claude = stack.enter_context(
                patch.object(orchestrator, "run_claude_attempt")
            )
            evaluate = stack.enter_context(
                patch.object(orchestrator, "evaluate_story")
            )
            verify = stack.enter_context(
                patch.object(orchestrator, "verify_with_github_ci",
                             return_value={"status": "PASSED", "reason": "ok",
                                           "report": "", "sha": "abc1234",
                                           "run_urls": []})
            )
            result = self.run_execute(stack)

        self.assertEqual(result, "COMPLETE")
        verify.assert_called_once()
        # The evaluator already accepted this attempt; its verdict is not
        # bought a second time, and Claude is not re-invoked either.
        evaluate.assert_not_called()
        claude.assert_not_called()
        self.assertFalse(self.attempt_state_file.exists())

    def test_awaiting_ci_fix_resumes_saved_prompt_without_repeating_qa_or_publication(self):
        self.given_finished_attempt(
            "AWAITING_CI_FIX", status="UNFINISHED", ci_fix_attempts=1
        )
        saved_prompt = "Fix the failed agent-runtime queue consistency checks."
        self.prompt_file.write_text(saved_prompt, encoding="utf-8")

        with ExitStack() as stack:
            claude = stack.enter_context(
                patch.object(orchestrator, "run_claude_attempt",
                             return_value=claude_runner.ClaudeAttempt(0, False))
            )
            evaluate = stack.enter_context(
                patch.object(orchestrator, "_evaluate_preserving_completed_attempt",
                             return_value={"decision": "COMPLETE", "reason": "verified"})
            )
            qa = stack.enter_context(
                patch.object(orchestrator, "_ensure_preimplementation_qa")
            )
            verify = stack.enter_context(
                patch.object(orchestrator, "verify_with_github_ci",
                             return_value={"status": "PASSED", "reason": "ok",
                                           "report": "", "sha": "abc1234",
                                           "run_urls": []})
            )
            result = self.run_execute(stack)

        self.assertEqual(result, "COMPLETE")
        claude.assert_called_once_with(saved_prompt)
        evaluate.assert_called_once()
        qa.assert_not_called()
        verify.assert_called_once()
        self.assertFalse(self.attempt_state_file.exists())

    def test_retry_budget_survives_a_restart(self):
        # MAX_RETRIES_PER_STORY already spent before the interruption: a
        # resumed attempt must escalate, not hand the story a fresh
        # allowance and loop forever across restarts.
        self.given_finished_attempt(
            "AWAITING_EVALUATION",
            retry_count=orchestrator.MAX_RETRIES_PER_STORY,
        )

        with ExitStack() as stack:
            claude = stack.enter_context(
                patch.object(orchestrator, "run_claude_attempt")
            )
            stack.enter_context(
                patch.object(orchestrator, "evaluate_story",
                             return_value={"decision": "RETRY",
                                           "reason": "still incomplete",
                                           "actionable_retry_items": ["do x"]})
            )
            result = self.run_execute(stack)

        self.assertEqual(result, "NEEDS_USER")
        claude.assert_not_called()
        self.assertFalse(self.attempt_state_file.exists())

    def test_done_story_remains_executable_until_durable_evaluation_exists(self):
        story = self.given_finished_attempt("AWAITING_EVALUATION")

        evaluator_file = self.stories_dir / "EVALUATOR_RESULT.json"
        with patch.object(orchestrator, "CURRENT_STORY_FILE", self.current_story_file), \
                patch.object(orchestrator, "EVALUATOR_RESULT_FILE", evaluator_file), \
                patch.object(git_sync, "ci_verification_available",
                             return_value=(False, "no GitHub remote")):
            self.assertTrue(orchestrator._active_is_executable())

            # Lost attempt state cannot make DONE alone authoritative.
            orchestrator.clear_attempt_state()
            self.assertTrue(orchestrator._active_is_executable())

            content = story.read_text(encoding="utf-8")
            evaluator_file.write_text(json.dumps({
                "decision": "COMPLETE",
                "story_id": "STORY-DOM-001",
                "evaluated_story_sha256": hashlib.sha256(content.encode("utf-8")).hexdigest(),
                "evaluated_result_sha256": orchestrator.file_hash(self.result_file),
            }), encoding="utf-8")
            self.assertFalse(orchestrator._active_is_executable())

        self.assertEqual(
            story_state.extract_status_section(
                story.read_text(encoding="utf-8")
            ),
            "DONE",
        )

    def test_a_malformed_record_is_ignored_rather_than_guessed_at(self):
        self.given_finished_attempt("AWAITING_EVALUATION")
        self.attempt_state_file.write_text("{not json", encoding="utf-8")

        self.assertIsNone(orchestrator.read_attempt_state())


class UnpublishedCompletionTest(PipelineRecoveryTestCase):
    """
    The second, independent check, for a DONE story whose record was lost
    with the rest of the gitignored artifacts directory.
    """

    def check(self, story, *, published, changes, ahead):
        with ExitStack() as stack:
            stack.enter_context(
                patch.object(git_sync, "ci_verification_available",
                             return_value=(True, "owner/repo via ci.yml"))
            )
            stack.enter_context(
                patch.object(git_sync, "commit_exists_with_subject",
                             return_value=published)
            )
            stack.enter_context(
                patch.object(git_sync, "working_tree_changes",
                             return_value=changes)
            )
            stack.enter_context(
                patch.object(git_sync, "current_branch", return_value="master")
            )
            stack.enter_context(
                patch.object(git_sync, "unpushed_commit_count",
                             return_value=ahead)
            )

            return orchestrator.unpublished_completion(
                story, story.read_text(encoding="utf-8")
            )

    def setUp(self):
        super().setUp()
        self.story = self.given_finished_attempt("AWAITING_EVALUATION")
        orchestrator.clear_attempt_state()
        self.evaluator_file = self.stories_dir / "EVALUATOR_RESULT.json"
        self._stack.enter_context(
            patch.object(orchestrator, "EVALUATOR_RESULT_FILE", self.evaluator_file)
        )
        story_content = self.story.read_text(encoding="utf-8")
        self.evaluator_file.write_text(json.dumps({
            "decision": "COMPLETE",
            "story_id": "STORY-DOM-001",
            "evaluated_story_sha256": hashlib.sha256(
                story_content.encode("utf-8")
            ).hexdigest(),
            "evaluated_result_sha256": orchestrator.file_hash(self.result_file),
        }), encoding="utf-8")

    def test_missing_commit_with_uncommitted_work_is_reported(self):
        reason = self.check(
            self.story, published=False,
            changes=[" M frontend/src/App.vue"], ahead=0,
        )

        self.assertIsNotNone(reason)
        self.assertIn("implemented STORY-DOM-001", reason)

    def test_missing_commit_with_unpushed_commits_is_reported(self):
        reason = self.check(
            self.story, published=False, changes=[], ahead=2,
        )

        self.assertIsNotNone(reason)
        self.assertIn("not on origin", reason)

    def test_log_churn_alone_is_not_unpublished_work(self):
        # agent/logs/<date>.log is tracked and appended to continuously, so
        # the tree is dirty within seconds of every commit. Treating that as
        # unpublished work would re-run the CI gate on a finished story
        # forever.
        self.assertIsNone(
            self.check(self.story, published=False,
                       changes=[" M agent/logs/2026-09-28.log"], ahead=0)
        )

    def test_a_published_story_is_never_reported(self):
        self.assertIsNone(
            self.check(self.story, published=True,
                       changes=[" M frontend/src/App.vue"], ahead=3)
        )

    def test_missing_evaluator_verdict_requires_recovery_without_a_ci_gate(self):
        self.evaluator_file.unlink()
        with patch.object(git_sync, "ci_verification_available",
                          return_value=(False, "no GitHub remote")):
            reason = orchestrator.unpublished_completion(
                self.story, self.story.read_text(encoding="utf-8")
            )
        self.assertIn("no durable COMPLETE evaluator verdict", reason)


class BoundedEvaluationTest(PipelineRecoveryTestCase):

    def test_a_permanently_unreachable_evaluator_stops_retrying(self):
        with ExitStack() as stack:
            evaluate = stack.enter_context(
                patch.object(orchestrator, "evaluate_story",
                             side_effect=OSError("connection refused"))
            )
            stack.enter_context(patch.object(orchestrator, "EVALUATION_ATTEMPTS", 2))
            stack.enter_context(patch.object(orchestrator, "MAX_EVALUATION_BATCHES", 3))
            stack.enter_context(patch.object(orchestrator, "EVALUATION_RETRY_SECONDS", 0))
            stack.enter_context(patch.object(orchestrator.time, "sleep"))
            stack.enter_context(patch.object(orchestrator, "print_status"))

            with self.assertRaises(OSError):
                orchestrator._evaluate_preserving_completed_attempt(
                    "story", "finished result", 0, True
                )

        # Bounded, not infinite -- and the finished work is never re-run,
        # because the failure reaches main() with the attempt recorded.
        self.assertEqual(evaluate.call_count, 6)
        self.assertTrue(
            any("resume at evaluation" in line for line in self.logged)
        )


class EvaluatorCapacityTest(PipelineRecoveryTestCase):
    """
    Evaluation runs on Codex now, so it can hit a usage limit. That is a
    scheduling event, not a failed evaluation: the work is finished and
    waiting is free, so it must never spend an attempt or escalate a story
    because a quota reset is hours away.
    """

    def test_codex_exhaustion_waits_instead_of_consuming_an_attempt(self):
        attempts = []
        waits = []

        def evaluate(*_args):
            attempts.append(1)

            if len(attempts) <= 2:
                raise ModelCapacityUnavailable("Codex usage exhausted")

            return {"decision": "COMPLETE", "reason": "ok"}

        with ExitStack() as stack:
            stack.enter_context(
                patch.object(orchestrator, "evaluate_story", side_effect=evaluate)
            )
            # One attempt per batch and one batch: were exhaustion counted
            # as a failure, the very first one would end the evaluation.
            stack.enter_context(patch.object(orchestrator, "EVALUATION_ATTEMPTS", 1))
            stack.enter_context(patch.object(orchestrator, "MAX_EVALUATION_BATCHES", 1))
            stack.enter_context(patch.object(orchestrator.time, "sleep"))

            result = orchestrator._evaluate_preserving_completed_attempt(
                "story", "finished result", 0, True,
                wait_for_evaluator=lambda: waits.append(1),
            )

        self.assertEqual(result["decision"], "COMPLETE")
        self.assertEqual(len(attempts), 3)
        self.assertEqual(len(waits), 2)
        self.assertTrue(
            any("does not consume an evaluation attempt" in line
                for line in self.logged),
            self.logged,
        )

    def test_waiting_for_codex_never_spends_it_on_other_work(self):
        scheduler = orchestrator.CapacityScheduler(Mock(), Mock(), cache_file=None)
        scheduler.codex_work_if_useful = Mock(
            side_effect=AssertionError(
                "the planner and architect run on the same exhausted budget"
            )
        )
        availability = iter([False, True])
        scheduler.codex_available = Mock(side_effect=lambda: next(availability))
        scheduler.wait_locally = Mock()

        with patch.object(orchestrator, "log_line", side_effect=self.logged.append):
            scheduler.wait_for_codex()

        scheduler.wait_locally.assert_called_once_with(["Codex"])
        scheduler.codex_work_if_useful.assert_not_called()


class TestGuardTest(unittest.TestCase):
    """
    The package guard itself. Without this, a fixture that drives a
    terminal path of execute_active_story() against a temp repository still
    deletes the real agent/runtime/artifacts/ATTEMPT_STATE.json -- which is
    the live position of an actual story attempt, and the one artifact that
    cannot be regenerated.
    """

    def test_the_real_attempt_state_file_is_out_of_every_test_s_reach(self):
        from agent.runtime.support import config

        self.assertNotEqual(
            orchestrator.ATTEMPT_STATE_FILE,
            config.RUNTIME_DIR / "artifacts" / "ATTEMPT_STATE.json",
        )
        self.assertNotIn(
            "artifacts", orchestrator.ATTEMPT_STATE_FILE.parts
        )


class EvaluatorIsReadOnlyTest(unittest.TestCase):
    """
    The evaluator judges work that is still uncommitted in the working
    tree. There is no safe "restore what it touched" for that, so it must
    be structurally unable to touch anything -- a sandbox guarantee, not a
    promise in a prompt.
    """

    def test_evaluation_runs_in_codexs_read_only_sandbox(self):
        with patch.object(local_planner_runner, "run_codex",
                          return_value=0) as run_codex:
            local_planner_runner.run_evaluator("prompt", [])

        self.assertEqual(run_codex.call_args.kwargs["sandbox"], "read-only")
        self.assertEqual(
            run_codex.call_args.kwargs["task_header"], "STORY EVALUATION TASK"
        )

    def test_a_read_only_run_is_not_given_file_editing_guidance(self):
        read_only = local_planner_runner.compose_codex_prompt(
            "judge this", "STORY EVALUATION TASK", sandbox="read-only"
        )
        writing = local_planner_runner.compose_codex_prompt(
            "plan this", "PROJECT PLANNING TASK", sandbox="workspace-write"
        )

        self.assertNotIn("write the complete file back", read_only)
        self.assertIn("write the complete file back", writing)

        # What both roles do need is still there.
        for prompt in (read_only, writing):
            self.assertIn("EXECUTION ENVIRONMENT", prompt)
            self.assertIn("CONTEXT DISCIPLINE", prompt)

    def test_captured_agent_messages_are_how_a_verdict_comes_back(self):
        messages = []

        with patch.object(local_planner_runner, "find_codex",
                          return_value="codex"), \
             patch.object(local_planner_runner.subprocess, "Popen",
                          return_value=_fake_codex_process([
                              {"type": "item.completed",
                               "item": {"type": "reasoning", "text": "thinking"}},
                              {"type": "item.completed",
                               "item": {"type": "agent_message",
                                        "text": '```json\n{"decision": "COMPLETE"}\n```'}},
                          ])) as popen:
            exit_code = REAL_RUN_CODEX(
                "judge this", task_header="STORY EVALUATION TASK",
                sandbox="read-only", messages=messages,
            )

        # read-only reaches the CLI as the sandbox it was asked for.
        command = popen.call_args.args[0]
        self.assertEqual(
            command[command.index("--sandbox") + 1], "read-only"
        )

        self.assertEqual(exit_code, 0)
        # Only agent messages, and the verdict survives intact.
        self.assertEqual(len(messages), 1)
        self.assertEqual(
            parse_evaluator_verdict(messages)["decision"], "COMPLETE"
        )


class EvaluatorBlockedVerdictTest(PipelineRecoveryTestCase):

    def test_blocked_verdict_records_the_block_on_story_and_backlog(self):
        filename = "STORY-DOM-002-blocked.md"
        story = self.write_story(
            filename,
            story_with_id("STORY-DOM-002", "## Status\n\nTODO\n"),
        )
        self.write_backlog(active=[filename])
        self.set_active(filename)

        with ExitStack() as stack:
            stack.enter_context(
                patch.object(orchestrator, "run_claude_attempt",
                             return_value=_finished_attempt())
            )
            stack.enter_context(
                patch.object(orchestrator, "evaluate_story",
                             return_value={"decision": "BLOCKED",
                                           "reason": "the API has no such field"})
            )
            result = self.run_execute(stack)

        self.assertEqual(result, "BLOCKED")

        # Without this bookkeeping the verdict changed nothing, so the very
        # next cycle re-invoked Claude on the same story, forever.
        content = story.read_text(encoding="utf-8")
        self.assertEqual(
            story_state.extract_status_section(content), "BLOCKED"
        )
        self.assertIn("the API has no such field", content)

        backlog = self.backlog_file.read_text(encoding="utf-8")
        self.assertEqual(
            story_state.parse_backlog_section(backlog, "Blocked"), [filename]
        )
        self.assertEqual(
            story_state.parse_backlog_section(backlog, "Active"), []
        )
        self.assertFalse(self.attempt_state_file.exists())


class CompactBacklogRowTest(PipelineRecoveryTestCase):
    """
    Bullet movement has to understand both BACKLOG.md formats. It only
    understood the legacy backtick one, so a compact row could not be
    pulled out of its section: the story ended up listed under two
    headings at once.
    """

    def write_compact_backlog(self, rows) -> None:
        self.backlog_file.write_text(
            "# Backlog\n\n## Active\n\n_None._\n\n## To Do\n\n"
            + "\n".join(rows)
            + "\n\n## Blocked\n\n_(none)_\n\n## Done\n\n_(none)_\n\n"
            "## Archived\n\n_(none)_\n",
            encoding="utf-8",
        )

    def test_activating_a_compact_row_moves_it_instead_of_duplicating_it(self):
        filename = "STORY-SYNC-004-metadata.md"
        self.write_story(
            filename,
            story_with_id("STORY-SYNC-004", "## Status\n\nTODO\n"),
        )
        self.write_story(
            "STORY-DOM-023-fees.md",
            story_with_id("STORY-DOM-023", "## Status\n\nTODO\n"),
        )
        self.write_compact_backlog([
            f"- STORY-SYNC-004 | {filename} | TODO | milestone-05: metadata.",
            "- STORY-DOM-023 | STORY-DOM-023-fees.md | TODO | milestone-05: fees.",
        ])

        story_state.set_active_story(filename)

        backlog = self.backlog_file.read_text(encoding="utf-8")
        self.assertEqual(
            story_state.parse_backlog_section(backlog, "Active"), [filename]
        )
        self.assertEqual(
            story_state.parse_backlog_section(backlog, "To Do"),
            ["STORY-DOM-023-fees.md"],
        )
        # The row itself is moved, not replaced by a bare bullet, so its
        # summary survives activation.
        self.assertIn("milestone-05: metadata.", backlog)
        self.assertEqual(story_state.validate_backlog_consistency(), [])

    def test_blocking_a_compact_row_leaves_no_stale_to_do_entry(self):
        filename = "STORY-SYNC-004-metadata.md"
        self.write_compact_backlog([
            f"- STORY-SYNC-004 | {filename} | TODO | milestone-05: metadata.",
        ])
        backlog = self.backlog_file.read_text(encoding="utf-8")

        moved = story_state.move_backlog_entry_to_blocked(
            story_state.move_backlog_entry_to_active(backlog, filename),
            filename,
            "blocked on UI-001",
        )

        self.assertEqual(
            story_state.parse_backlog_section(moved, "Blocked"), [filename]
        )
        for heading in ("Active", "To Do"):
            self.assertEqual(
                story_state.parse_backlog_section(moved, heading), []
            )


def _finished_attempt():
    from agent.runtime.runners import claude_runner

    return claude_runner.ClaudeAttempt(0, False)


if __name__ == "__main__":
    unittest.main()
