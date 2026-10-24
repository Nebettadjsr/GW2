"""Focused checks for the review gate's deterministic decisions."""

import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import complete_codex_review
from complete_codex_review import format_comment, previous_heads
from prepare_codex_review import linked_issue


class CodexReviewTests(unittest.TestCase):
    def test_linked_issue_accepts_closing_and_partial_references(self):
        self.assertEqual(linked_issue("Closes #42"), 42)
        self.assertEqual(linked_issue("Part of #17"), 17)
        self.assertIsNone(linked_issue("No issue linked"))

    def test_review_rounds_count_distinct_head_commits(self):
        one = "a" * 40
        two = "b" * 40
        comments = [
            {"body": f"<!-- gw2-codex-review head:{one} -->"},
            {"body": f"<!-- gw2-codex-review head:{one} -->"},
            {"body": f"<!-- gw2-codex-review head:{two} -->"},
            {"body": "Unrelated comment"},
        ]
        self.assertEqual(previous_heads(comments), {one, two})

    def test_blocking_findings_are_visible_in_comment(self):
        verdict = {
            "verdict": "changes_requested",
            "summary": "One criterion is missing.",
            "blocking_findings": ["src/Foo.java: account scope is ignored"],
        }
        comment = format_comment(verdict, "a" * 40, 2, [])
        self.assertIn("Blocking findings", comment)
        self.assertIn("account scope is ignored", comment)
        self.assertIn("Review round: 2/3", comment)

    def test_stale_head_cannot_publish_approval_or_enable_merge(self):
        head = "a" * 40
        event = {
            "pull_request": {
                "number": 18,
                "head": {"sha": head},
                "body": "Part of #17",
            }
        }
        verdict = {
            "verdict": "approved",
            "summary": "Looks good",
            "blocking_findings": [],
            "followups": [],
        }
        with TemporaryDirectory() as directory:
            event_path = Path(directory) / "event.json"
            result_path = Path(directory) / "result.json"
            event_path.write_text(json.dumps(event), encoding="utf-8")
            result_path.write_text(json.dumps(verdict), encoding="utf-8")
            environment = {
                "GITHUB_EVENT_PATH": str(event_path),
                "GITHUB_REPOSITORY": "Nebettadjsr/GW2",
                "GITHUB_TOKEN": "fixture-token",
                "AUTO_MERGE_ENABLED": "true",
            }
            with patch.dict(os.environ, environment), patch.object(
                complete_codex_review, "api", return_value={"head": {"sha": "b" * 40}, "state": "open"}
            ) as api_mock, patch("sys.argv", ["gate", str(result_path)]):
                with self.assertRaisesRegex(RuntimeError, "head changed"):
                    complete_codex_review.main()
            self.assertEqual(api_mock.call_count, 1)

    def test_approval_with_blocking_findings_is_rejected_before_api_calls(self):
        event = {"pull_request": {"number": 18, "head": {"sha": "a" * 40}, "body": "Closes #17"}}
        verdict = {
            "verdict": "approved",
            "summary": "Contradictory result",
            "blocking_findings": ["Missing acceptance criterion"],
            "followups": [],
        }
        with TemporaryDirectory() as directory:
            event_path = Path(directory) / "event.json"
            result_path = Path(directory) / "result.json"
            event_path.write_text(json.dumps(event), encoding="utf-8")
            result_path.write_text(json.dumps(verdict), encoding="utf-8")
            environment = {
                "GITHUB_EVENT_PATH": str(event_path),
                "GITHUB_REPOSITORY": "Nebettadjsr/GW2",
                "GITHUB_TOKEN": "fixture-token",
            }
            with patch.dict(os.environ, environment), patch.object(complete_codex_review, "api") as api_mock, patch(
                "sys.argv", ["gate", str(result_path)]
            ):
                with self.assertRaisesRegex(ValueError, "blocking findings"):
                    complete_codex_review.main()
            api_mock.assert_not_called()


if __name__ == "__main__":
    unittest.main()
