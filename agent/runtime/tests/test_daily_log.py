"""
Deterministic tests for support/daily_log.py: file creation, strict
append-only behavior (never truncates), and automatic rollover to a
new day's file when the date changes.

Run with: python -m unittest agent.runtime.tests.test_daily_log -v
(from the repository root).
"""

import io
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

from agent.runtime.support import daily_log


class DailyLogTest(unittest.TestCase):

    def setUp(self):
        self._tmpdir = tempfile.TemporaryDirectory()
        self.logs_dir = Path(self._tmpdir.name).resolve() / "logs"
        self._patch = patch.object(daily_log, "LOGS_DIR", self.logs_dir)
        self._patch.start()
        self.addCleanup(self._patch.stop)
        self.addCleanup(self._tmpdir.cleanup)

    def test_creates_directory_and_file_on_first_write(self):
        self.assertFalse(self.logs_dir.exists())

        daily_log.log_line("first message")

        expected = self.logs_dir / f"{datetime.now().date().isoformat()}.log"
        self.assertTrue(expected.exists())
        self.assertIn("first message", expected.read_text(encoding="utf-8"))

    def test_appends_without_truncating_existing_content(self):
        daily_log.log_line("one")
        daily_log.log_line("two")
        daily_log.log_line("three")

        path = self.logs_dir / f"{datetime.now().date().isoformat()}.log"
        lines = path.read_text(encoding="utf-8").splitlines()

        self.assertEqual(len(lines), 3)
        self.assertIn("one", lines[0])
        self.assertIn("two", lines[1])
        self.assertIn("three", lines[2])

    def test_pre_existing_file_content_is_preserved(self):
        self.logs_dir.mkdir(parents=True)
        path = self.logs_dir / f"{datetime.now().date().isoformat()}.log"
        path.write_text("[00:00:00] pre-existing line\n", encoding="utf-8")

        daily_log.log_line("new line")

        content = path.read_text(encoding="utf-8")
        self.assertIn("pre-existing line", content)
        self.assertIn("new line", content)

    def test_deferred_writes_flush_after_guarded_operation(self):
        self.logs_dir.mkdir(parents=True)
        path = self.logs_dir / f"{datetime.now().date().isoformat()}.log"
        path.write_text("pre-existing line\n", encoding="utf-8")
        with daily_log.defer_log_writes():
            daily_log.log_line("orchestrator-owned QA output")
            self.assertEqual(path.read_text(encoding="utf-8"), "pre-existing line\n")
        self.assertIn("pre-existing line", path.read_text(encoding="utf-8"))
        self.assertIn("orchestrator-owned QA output", path.read_text(encoding="utf-8"))

    def test_each_line_is_timestamped(self):
        daily_log.log_line("hello")

        path = self.logs_dir / f"{datetime.now().date().isoformat()}.log"
        line = path.read_text(encoding="utf-8").strip()

        self.assertRegex(line, r"^\[\d{2}:\d{2}:\d{2}\] hello$")

    def test_rollover_creates_a_new_file_for_a_new_day(self):
        day_one = datetime(2026, 9, 20, 23, 59, 0)
        day_two = datetime(2026, 9, 21, 0, 0, 5)

        with patch.object(daily_log, "datetime") as mock_datetime:
            mock_datetime.now.return_value = day_one
            daily_log.log_line("end of day one")

        with patch.object(daily_log, "datetime") as mock_datetime:
            mock_datetime.now.return_value = day_two
            daily_log.log_line("start of day two")

        file_one = self.logs_dir / "2026-09-20.log"
        file_two = self.logs_dir / "2026-09-21.log"

        self.assertTrue(file_one.exists())
        self.assertTrue(file_two.exists())
        self.assertIn("end of day one", file_one.read_text(encoding="utf-8"))
        self.assertIn("start of day two", file_two.read_text(encoding="utf-8"))

        # Writing to the new day must never touch the previous day's file.
        self.assertNotIn("start of day two", file_one.read_text(encoding="utf-8"))


class ConsoleTeeTest(unittest.TestCase):
    """
    Terminal output must be mirrored into the log, never replaced by it:
    an unattended run has to be readable afterwards without losing the
    live console.
    """

    def setUp(self):
        self._tmpdir = tempfile.TemporaryDirectory()
        self.logs_dir = Path(self._tmpdir.name).resolve() / "logs"
        self._patch = patch.object(daily_log, "LOGS_DIR", self.logs_dir)
        self._patch.start()
        self.addCleanup(self._patch.stop)
        self.addCleanup(self._tmpdir.cleanup)

    def logged_text(self):
        path = self.logs_dir / f"{datetime.now().date().isoformat()}.log"
        return path.read_text(encoding="utf-8")

    def test_output_reaches_both_the_terminal_and_the_log(self):
        terminal = io.StringIO()
        tee = daily_log.ConsoleTee(terminal)

        print("Starting Claude Code", file=tee)

        self.assertEqual(terminal.getvalue(), "Starting Claude Code\n")
        self.assertIn("Starting Claude Code", self.logged_text())

    def test_partial_writes_are_assembled_into_one_line(self):
        # print() writes the value and the newline as separate calls,
        # so a tee that logged per write() would split every line.
        terminal = io.StringIO()
        tee = daily_log.ConsoleTee(terminal)

        tee.write("exit ")
        tee.write("code: 0")
        tee.write("\n")

        lines = self.logged_text().splitlines()
        self.assertEqual(len(lines), 1)
        self.assertTrue(lines[0].endswith("exit code: 0"), lines[0])

    def test_unterminated_text_is_not_logged_until_its_newline(self):
        terminal = io.StringIO()
        tee = daily_log.ConsoleTee(terminal)

        tee.write("still streaming")

        self.assertEqual(terminal.getvalue(), "still streaming")
        self.assertFalse(self.logs_dir.exists())

    def test_print_status_reaches_the_terminal_but_never_the_log(self):
        # Wait-loop heartbeats can repeat for hours; they must not fill
        # the permanent record.
        terminal = io.StringIO()
        tee = daily_log.ConsoleTee(terminal)

        with patch.object(daily_log.sys, "stdout", tee):
            daily_log.print_status("Claude capacity unavailable - waiting...")
            daily_log.print_status("Next capacity check in 42 min")

        self.assertIn("waiting...", terminal.getvalue())
        self.assertIn("42 min", terminal.getvalue())
        self.assertFalse(self.logs_dir.exists())

    def test_print_status_works_without_the_tee_installed(self):
        terminal = io.StringIO()

        with patch.object(daily_log.sys, "stdout", terminal):
            daily_log.print_status("Current usage: 97%")

        self.assertEqual(terminal.getvalue(), "Current usage: 97%\n")
        self.assertFalse(self.logs_dir.exists())

    def test_blank_separator_lines_are_not_logged(self):
        terminal = io.StringIO()
        tee = daily_log.ConsoleTee(terminal)

        tee.write("\n\n   \n")

        self.assertEqual(terminal.getvalue(), "\n\n   \n")
        self.assertFalse(self.logs_dir.exists())


if __name__ == "__main__":
    unittest.main()
