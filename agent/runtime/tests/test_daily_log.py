"""
Deterministic tests for support/daily_log.py: file creation, strict
append-only behavior (never truncates), and automatic rollover to a
new day's file when the date changes.

Run with: python -m unittest agent.runtime.tests.test_daily_log -v
(from the repository root).
"""

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


if __name__ == "__main__":
    unittest.main()
