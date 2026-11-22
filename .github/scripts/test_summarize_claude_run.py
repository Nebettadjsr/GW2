"""Offline checks for the run metrics renderer."""

import json
import os
import tempfile
import unittest
from pathlib import Path

import summarize_claude_run as metrics

RESULT = {"type": "result", "subtype": "success", "is_error": False, "num_turns": 31,
          "duration_ms": 754000, "duration_api_ms": 600000, "total_cost_usd": 4.5678,
          "usage": {"input_tokens": 1200, "output_tokens": 34000,
                    "cache_read_input_tokens": 2500000, "cache_creation_input_tokens": 90000}}


class RunMetricsTest(unittest.TestCase):
    def write(self, text):
        handle = tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8")
        handle.write(text)
        handle.close()
        self.addCleanup(os.unlink, handle.name)
        return handle.name

    def test_json_array_with_result_record(self):
        path = self.write(json.dumps([{"type": "system"}, {"type": "assistant"}, RESULT]))
        text = metrics.render(metrics.final_result(metrics.load_messages(path)), 800, "40")
        self.assertIn("Turns: 31 of 40 allowed", text)
        self.assertIn("13 min 20 s", text)
        self.assertIn("input 1,200, output 34,000, cache read 2,500,000, cache write 90,000", text)
        self.assertIn("$4.57", text)

    def test_json_lines_are_accepted(self):
        path = self.write("\n".join([json.dumps({"type": "assistant"}), json.dumps(RESULT)]))
        self.assertEqual(31, metrics.final_result(metrics.load_messages(path))["num_turns"])

    def test_missing_fields_render_as_not_available(self):
        text = metrics.render({"type": "result"}, None, "")
        self.assertIn("Turns: n/a of n/a allowed", text)
        self.assertIn("cost (informational, the run uses the subscription): n/a", text)
        self.assertNotIn("Traceback", text)

    def test_error_result_is_named(self):
        text = metrics.render({"type": "result", "subtype": "error_max_turns", "is_error": True}, 10, "40")
        self.assertIn("Result: error_max_turns", text)

    def test_unreadable_log_never_raises(self):
        for content in ("", "not json at all", "[1, 2, 3]"):
            self.assertEqual({}, metrics.final_result(metrics.load_messages(self.write(content))))
        old = dict(os.environ)
        try:
            os.environ["CLAUDE_EXECUTION_FILE"] = str(Path(tempfile.gettempdir()) / "does-not-exist.json")
            os.environ.pop("RUN_STARTED_AT", None)
            self.assertEqual(0, metrics.main())
        finally:
            os.environ.clear()
            os.environ.update(old)


if __name__ == "__main__":
    unittest.main()
