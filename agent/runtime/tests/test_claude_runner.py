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
import json
import unittest
from contextlib import redirect_stdout
from unittest.mock import Mock, patch

from agent.runtime.runners import claude_runner
from agent.runtime.support.config import (
    CLAUDE_MODEL,
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
             patch.object(claude_runner.subprocess, "Popen", return_value=process) as popen, \
             redirect_stdout(terminal):
            result = claude_runner.run_claude_attempt("Implement")

        # A Ctrl+C in the service console must not reach Claude.
        self.assertEqual(popen.call_args.kwargs["creationflags"], claude_runner.AGENT_PROCESS_FLAGS)
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

    def test_new_session_is_pinned_with_session_id_and_resume_uses_resume(self):
        created = self.run_claude(lambda: claude_runner.run_claude_attempt("p", session_id="abc"))
        resumed = self.run_claude(lambda: claude_runner.run_claude_attempt("p", session_id="abc", resume=True))
        plain = self.run_claude(lambda: claude_runner.run_claude("p"))
        self.assertEqual(created["argv"][created["argv"].index("--session-id") + 1], "abc")
        self.assertNotIn("--resume", created["argv"])
        self.assertEqual(resumed["argv"][resumed["argv"].index("--resume") + 1], "abc")
        self.assertNotIn("--session-id", resumed["argv"])
        self.assertNotIn("--continue", resumed["argv"])
        self.assertTrue(all(flag not in plain["argv"] for flag in ("--resume", "--session-id")))

    def test_attempt_reports_session_and_context_size_from_stream_events(self):
        output = ('{"type":"system","subtype":"init","session_id":"sid-1"}\n'
                  '{"type":"result","is_error":false,"session_id":"sid-1","usage":'
                  '{"input_tokens":10,"cache_read_input_tokens":20,"cache_creation_input_tokens":5}}\n')
        attempt = self.run_claude(lambda: claude_runner.run_claude_attempt("p", session_id="sid-1"),
                                  output=output)["code"]
        self.assertEqual((attempt.session_id, attempt.context_tokens, attempt.resume_failed), ("sid-1", 35, False))

    def test_resuming_an_unknown_session_is_reported_as_resume_failure(self):
        missing = "No conversation found with session ID: abc\n"
        failed = self.run_claude(lambda: claude_runner.run_claude_attempt("p", session_id="abc", resume=True),
                                 output=missing, returncode=1)["code"]
        created = self.run_claude(lambda: claude_runner.run_claude_attempt("p", session_id="abc"),
                                  output=missing, returncode=1)["code"]
        self.assertTrue(failed.resume_failed)
        self.assertFalse(created.resume_failed)

    def test_on_start_receives_the_pid_before_the_prompt_is_written(self):
        order = []
        process = fake_process('{"type":"result","is_error":false}')
        process.pid = 4242
        process.stdin.write.side_effect = lambda _text: order.append("prompt")
        with patch.object(claude_runner, "find_claude", return_value="claude.exe"), \
             patch.object(claude_runner.subprocess, "Popen", return_value=process), \
             redirect_stdout(io.StringIO()):
            claude_runner.run_claude_attempt("p", on_start=lambda pid: order.append(pid))
        self.assertEqual(order, [4242, "prompt"])

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

    def test_session_limit_in_final_output_is_a_capacity_interruption(self):
        # Regression: this exact message was counted as a failed run and
        # blocked STORY-WEB-030 after two "failures".
        captured = self.run_claude(
            lambda: claude_runner.run_claude_attempt("Implement"),
            output="You've hit your session limit · resets 11:40pm (Europe/Berlin)\n",
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

    def stream(self, *events):
        return "".join(json.dumps(event) + "\n" for event in events)

    def test_attempt_reads_stream_json_and_renders_text_and_tool_calls(self):
        output = self.stream(
            {"type": "system", "subtype": "init"},
            {"type": "assistant", "message": {"content": [
                {"type": "tool_use", "name": "Bash", "input": {"command": "npm   test --  --run"}}]}},
            {"type": "assistant", "message": {"content": [{"type": "text", "text": "Story implemented."}]}},
            {"type": "result", "subtype": "success", "is_error": False, "result": "Story implemented."},
        )
        captured = self.run_claude(lambda: claude_runner.run_claude_attempt("Implement"), output=output)

        argv = captured["argv"]
        self.assertEqual(argv[argv.index("--output-format") + 1], "stream-json")
        self.assertIn("--verbose", argv)
        self.assertEqual(captured["code"].output, "> Bash: npm test -- --run\nStory implemented.")
        self.assertEqual(captured["code"].exit_code, 0)

    def test_a_finished_answer_does_not_wait_for_background_processes(self):
        # Regression: Claude answered, but a dev server it had started kept
        # the process alive, so the pipeline step never returned.
        process = fake_process(self.stream(
            {"type": "assistant", "message": {"content": [{"type": "text", "text": "Done."}]}},
            {"type": "result", "subtype": "success", "is_error": False, "result": "Done."},
            {"type": "assistant", "message": {"content": [{"type": "text", "text": "never read"}]}},
        ))
        process.wait.side_effect = [claude_runner.subprocess.TimeoutExpired("claude", 30), 1]
        with patch.object(claude_runner, "find_claude", return_value="claude.exe"), \
             patch.object(claude_runner.subprocess, "Popen", return_value=process), \
             patch.object(claude_runner, "_stop_process_tree") as stop, \
             redirect_stdout(io.StringIO()):
            result = claude_runner.run_claude_attempt("Implement")

        stop.assert_called_once_with(process)
        self.assertEqual(process.wait.call_args_list[0].kwargs, {"timeout": claude_runner.RESULT_EXIT_GRACE_SECONDS})
        self.assertEqual((result.exit_code, result.output), (0, "Done."))

    def test_an_error_result_carrying_the_session_limit_is_capacity(self):
        output = self.stream({"type": "result", "subtype": "success", "is_error": True,
                              "result": "You've hit your session limit \u00b7 resets 11:40pm"})
        captured = self.run_claude(lambda: claude_runner.run_claude_attempt("Implement"),
                                   output=output, returncode=1)

        self.assertTrue(captured["code"].capacity_exhausted)
        self.assertIn("session limit", captured["code"].output)

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


class StopOrphanTest(unittest.TestCase):
    def run_guard(self, pid, started, alive, current, stays_alive=False):
        calls = []

        def is_alive(_pid):
            calls.append(1)
            return alive if len(calls) == 1 else stays_alive

        clock = iter(range(0, 1000, 3))
        with patch.object(claude_runner, "process_alive", side_effect=is_alive),              patch.object(claude_runner.time, "monotonic", side_effect=lambda: next(clock)), \
             patch.object(claude_runner, "process_start_time", return_value=current), \
             patch.object(claude_runner.subprocess, "run") as taskkill, \
             patch.object(claude_runner.os, "kill", create=True) as kill, \
             patch.object(claude_runner.time, "sleep"):
            result = claude_runner.stop_orphan(pid, started)
        return result, taskkill.called or kill.called

    def test_nothing_recorded_or_already_dead_is_clear_and_nothing_is_killed(self):
        self.assertEqual(self.run_guard(None, None, alive=True, current=1.0), (True, False))
        self.assertEqual(self.run_guard(10, 1.0, alive=False, current=None), (True, False))

    def test_a_reused_pid_with_a_different_start_time_is_not_ours_and_is_left_alone(self):
        self.assertEqual(self.run_guard(10, 100.0, alive=True, current=500.0), (True, False))

    def test_the_recorded_run_still_alive_is_stopped(self):
        self.assertEqual(self.run_guard(10, 100.0, alive=True, current=100.4), (True, True))

    def test_a_run_that_survives_the_kill_is_reported_as_not_clear(self):
        self.assertEqual(self.run_guard(10, 100.0, alive=True, current=100.0, stays_alive=True)[0], False)

    def test_a_live_process_that_cannot_be_identified_is_never_killed_and_blocks(self):
        self.assertEqual(self.run_guard(10, None, alive=True, current=100.0), (False, False))
        self.assertEqual(self.run_guard(10, 100.0, alive=True, current=None), (False, False))
