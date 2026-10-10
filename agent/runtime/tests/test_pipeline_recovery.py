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
from agent.runtime.runners import local_planner_runner
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
            ("CLAUDE_RESULT_FILE", self.result_file),
            ("NEXT_PROMPT_FILE", self.prompt_file),
            ("EVALUATOR_RESULT_FILE", self.stories_dir / "EVALUATOR_RESULT.json"),
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

    def write_matching_evaluator_verdict(self, story):
        story_content = story.read_text(encoding="utf-8")
        evaluator_file = self.stories_dir / "EVALUATOR_RESULT.json"
        evaluator_file.write_text(json.dumps({
            "decision": "COMPLETE",
            "reason": "persisted test verdict",
            "story_id": "STORY-DOM-001",
            "evaluated_story_sha256": hashlib.sha256(
                story_content.encode("utf-8")
            ).hexdigest(),
            "evaluated_result_sha256": orchestrator.file_hash(self.result_file),
        }), encoding="utf-8")


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

        # Bounded, not infinite -- and the failure reaches the bridge step,
        # which reruns evaluation, never the finished Claude work.
        self.assertEqual(evaluate.call_count, 6)
        self.assertTrue(
            any("never Claude" in line for line in self.logged)
        )


class EvaluatorCapacityTest(PipelineRecoveryTestCase):
    """
    Evaluation runs on Codex now, so it can hit a usage limit. That is a
    scheduling event, not a failed evaluation: the work is finished and
    waiting is free, so it must never spend an attempt or escalate a story
    because a quota reset is hours away.
    """

    def test_codex_exhaustion_is_handed_back_without_consuming_an_attempt(self):
        attempts = []

        def evaluate(*_args):
            attempts.append(1)
            raise ModelCapacityUnavailable("Codex usage exhausted")

        with ExitStack() as stack:
            stack.enter_context(patch.object(orchestrator, "evaluate_story", side_effect=evaluate))
            stack.enter_context(patch.object(orchestrator, "EVALUATION_ATTEMPTS", 3))
            sleep = stack.enter_context(patch.object(orchestrator.time, "sleep"))

            with self.assertRaises(ModelCapacityUnavailable):
                orchestrator._evaluate_preserving_completed_attempt("story", "finished result", 0, True)

        # A pause for the caller: no retry spent, no wait, no failure logged.
        self.assertEqual(len(attempts), 1)
        sleep.assert_not_called()
        self.assertFalse(any("failed" in line for line in self.logged), self.logged)


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
