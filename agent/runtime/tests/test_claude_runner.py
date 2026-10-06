"""
Deterministic tests for runners/claude_runner.py: the orchestrator must
launch Claude Code with an explicitly pinned model, and must stream
Claude's output through this process's stdout so the daily log can
mirror it.

No real Claude process is started -- find_claude and subprocess.Popen
are both replaced.

Run with: python -m unittest agent.runtime.tests.test_claude_runner -v
(from the repository root).
"""

import io
import unittest
from contextlib import redirect_stdout
from unittest.mock import Mock, patch

from agent.runtime.runners import claude_runner
from agent.runtime.support.config import (
    CLAUDE_MODEL, CLAUDE_USAGE_LIMIT_PERCENT,
    CLAUDE_WEEKLY_MIN_REMAINING_PERCENT,
)


def fake_process(output: str = "", returncode: int = 0):
    process = Mock()
    process.stdin = Mock()
    process.stdout = io.StringIO(output)
    process.wait.return_value = returncode
    return process


class ClaudeRunnerTest(unittest.TestCase):

    def test_unrepresentable_model_output_does_not_abort_completed_attempt(self):
        class Cp1252Console(io.StringIO):
            @property
            def encoding(self):
                return "cp1252"

            def write(self, value):
                value.encode(self.encoding)
                return super().write(value)

        process = fake_process("Result contains an arrow →\n")
        terminal = Cp1252Console()
        with patch.object(claude_runner, "find_claude", return_value="claude.exe"), \
             patch.object(claude_runner.subprocess, "Popen", return_value=process), \
             redirect_stdout(terminal):
            result = claude_runner.run_claude_attempt("Implement")

        self.assertEqual(result.exit_code, 0)
        self.assertIn("Result contains an arrow", result.output)
        self.assertIn("Result contains an arrow ?", terminal.getvalue())

    def test_usage_probe_parses_session_and_weekly_from_one_response(self):
        result = Mock(returncode=0, stdout=(
            "Current session: 89% used\n"
            "Current week (all models): 98% used\n"
            "Current week (Opus): 97% used\n"
        ))
        with patch.object(claude_runner, "find_claude", return_value="claude.exe"), \
             patch.object(claude_runner.subprocess, "run", return_value=result) as run:
            usage = claude_runner.get_claude_usage()
        self.assertEqual(usage, claude_runner.ClaudeUsage(89, 98))
        run.assert_called_once()

    def test_both_capacity_thresholds_are_independent(self):
        self.assertFalse(claude_runner.ClaudeUsage(90, 0).available())
        self.assertFalse(claude_runner.ClaudeUsage(0, 98).available())
        self.assertFalse(claude_runner.ClaudeUsage(89, 50).available())
        self.assertTrue(claude_runner.ClaudeUsage(89, 49).available())
        self.assertEqual(CLAUDE_USAGE_LIMIT_PERCENT, 90)
        self.assertEqual(CLAUDE_WEEKLY_MIN_REMAINING_PERCENT, 50)

    def test_wait_rechecks_both_allowances_until_both_recover(self):
        readings = [
            claude_runner.ClaudeUsage(90, 20),
            claude_runner.ClaudeUsage(50, 98),
            claude_runner.ClaudeUsage(50, 49),
        ]
        with patch.object(claude_runner, "get_claude_usage", side_effect=readings) as probe, \
             patch.object(claude_runner.time, "sleep") as sleep, \
             patch.object(claude_runner, "print_status") as status:
            claude_runner.wait_for_claude_capacity()
        self.assertEqual(probe.call_count, 3)
        self.assertEqual(sleep.call_count, 2)
        self.assertTrue(any("weekly remaining 2%" in call.args[0]
                            and "resumes above 50%" in call.args[0]
                            for call in status.call_args_list))

    def test_unreadable_weekly_usage_fails_closed(self):
        result = Mock(returncode=0, stdout="Current session: 20% used\n")
        with patch.object(claude_runner, "find_claude", return_value="claude.exe"), \
             patch.object(claude_runner.subprocess, "run", return_value=result):
            with self.assertRaisesRegex(RuntimeError, "session and weekly"):
                claude_runner.get_claude_usage()

    def run_claude(self, call, output: str = "", returncode: int = 0):
        captured = {}
        process = fake_process(output, returncode)

        def fake_popen(argv, **kwargs):
            captured["argv"] = argv
            captured["kwargs"] = kwargs
            return process

        terminal = io.StringIO()
        with patch.object(claude_runner, "find_claude", return_value="claude.exe"), \
             patch.object(claude_runner.subprocess, "Popen", side_effect=fake_popen), \
             redirect_stdout(terminal):
            captured["code"] = call()

        captured["terminal"] = terminal.getvalue()
        captured["prompt"] = process.stdin.write.call_args[0][0]
        return captured

    def test_story_execution_pins_the_configured_model(self):
        captured = self.run_claude(
            lambda: claude_runner.run_claude("Implement the story")
        )
        argv = captured["argv"]

        self.assertIn("--model", argv)
        # The value must immediately follow the flag, or the CLI would
        # read the next argument as the model name.
        self.assertEqual(argv[argv.index("--model") + 1], CLAUDE_MODEL)
        self.assertEqual(captured["prompt"], "Implement the story")

    def test_planning_run_pins_the_same_model(self):
        captured = self.run_claude(
            lambda: claude_runner.run_claude_planning("Plan the milestone")
        )
        argv = captured["argv"]

        self.assertEqual(argv[argv.index("--model") + 1], CLAUDE_MODEL)

    def test_model_is_pinned_to_an_explicit_id_not_an_alias(self):
        # An alias like "opus" would drift to a different model as new
        # releases land; the pin exists precisely to prevent that.
        self.assertTrue(
            CLAUDE_MODEL.startswith("claude-"),
            f"CLAUDE_MODEL should be a full model id, got {CLAUDE_MODEL!r}",
        )

    def test_claude_output_is_printed_so_the_log_can_mirror_it(self):
        captured = self.run_claude(
            lambda: claude_runner.run_claude("Implement"),
            output="Reading config.py\nEditing config.py\n",
        )

        self.assertIn("Reading config.py", captured["terminal"])
        self.assertIn("Editing config.py", captured["terminal"])

    def test_output_is_piped_rather_than_inherited(self):
        # Inheriting the terminal would bypass Python entirely and leave
        # the run unrecorded in agent/logs/.
        captured = self.run_claude(lambda: claude_runner.run_claude("Implement"))
        kwargs = captured["kwargs"]

        self.assertEqual(kwargs["stdout"], claude_runner.subprocess.PIPE)
        self.assertEqual(kwargs["stderr"], claude_runner.subprocess.STDOUT)

    def test_usage_limit_in_the_final_output_is_a_capacity_interruption(self):
        captured = self.run_claude(
            lambda: claude_runner.run_claude_attempt("Implement"),
            output="Editing config.py\nClaude usage limit reached|1764500000\n",
            returncode=1,
        )

        self.assertEqual(captured["code"].exit_code, 1)
        self.assertTrue(captured["code"].capacity_exhausted)

    def test_weekly_limit_in_final_output_is_a_capacity_interruption(self):
        captured = self.run_claude(
            lambda: claude_runner.run_claude_attempt("Implement"),
            output="You've hit your weekly limit · resets Oct 3, 7am (Europe/Berlin)\n",
            returncode=1,
        )
        self.assertTrue(captured["code"].capacity_exhausted)

    def test_failed_run_without_a_capacity_signal_is_not_capacity(self):
        captured = self.run_claude(
            lambda: claude_runner.run_claude_attempt("Implement"),
            output="Error: permission denied while running tests\n",
            returncode=1,
        )

        self.assertFalse(captured["code"].capacity_exhausted)

    def test_zero_exit_is_never_treated_as_capacity_exhaustion(self):
        # A story about capacity handling legitimately prints these
        # words while editing this very subsystem.
        captured = self.run_claude(
            lambda: claude_runner.run_claude_attempt("Implement"),
            output="Wrote wait_for_claude_capacity: usage limit handling\n",
        )

        self.assertFalse(captured["code"].capacity_exhausted)

    def test_a_mid_run_mention_is_not_the_runs_own_outcome(self):
        # Only the tail is scanned: the phrase appeared long before the
        # (unrelated) failure that ended the run.
        captured = self.run_claude(
            lambda: claude_runner.run_claude_attempt("Implement"),
            output="usage limit\n" + ("edited a file\n" * 400)
            + "Traceback: unexpected error\n",
            returncode=1,
        )

        self.assertFalse(captured["code"].capacity_exhausted)

    def test_exit_code_is_returned_from_the_finished_process(self):
        captured = self.run_claude(
            lambda: claude_runner.run_claude("Implement"),
            returncode=2,
        )

        self.assertEqual(captured["code"], 2)
        self.assertIn("exit code: 2", captured["terminal"])


if __name__ == "__main__":
    unittest.main()
