"""Focused checks for the review gate's deterministic decisions."""

import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import complete_codex_review
from complete_codex_review import format_comment, previous_heads
from prepare_codex_review import issue_comments, linked_issue


class CodexReviewTests(unittest.TestCase):
    def test_linked_issue_accepts_closing_and_partial_references(self):
        self.assertEqual(linked_issue("Closes #42"), 42)
        self.assertEqual(linked_issue("Part of #17"), 17)
        self.assertIsNone(linked_issue("No issue linked"))

    def test_review_context_includes_recent_issue_discussion(self):
        class FakeResponse:
            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

        comments = [{"user": {"login": "claude[bot]"}, "body": "Maven tests passed."}]
        with patch("prepare_codex_review.urlopen", return_value=FakeResponse()), patch(
            "prepare_codex_review.json.load", return_value=comments
        ):
            self.assertIn("Maven tests passed.", issue_comments("owner/repo", 9, "fixture-token"))

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

    def test_third_unsuccessful_round_requires_human_decision(self):
        head = "c" * 40
        event = {"pull_request": {"number": 18, "head": {"sha": head}, "body": "Closes #17"}}
        verdict = {
            "verdict": "changes_requested",
            "summary": "Criterion 2 is missing.",
            "blocking_findings": ["Criterion 2"],
            "followups": [],
        }
        calls = []

        def fake_api(method, path, token, payload=None):
            calls.append((method, path, payload))
            if path.endswith("/pulls/18"):
                return {"head": {"sha": head}, "state": "open", "labels": []}
            if "/comments?" in path:
                return [
                    {"body": "<!-- gw2-codex-review head:" + "a" * 40 + " -->"},
                    {"body": "<!-- gw2-codex-review head:" + "b" * 40 + " -->"},
                ]
            return {}

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
            with patch.dict(os.environ, environment), patch.object(
                complete_codex_review, "api", side_effect=fake_api
            ), patch("sys.argv", ["gate", str(result_path)]):
                self.assertEqual(complete_codex_review.main(), 1)
        self.assertTrue(any(path.endswith("/labels") for _, path, _ in calls))
        self.assertTrue(any("Needs Decision" in payload["body"] for _, path, payload in calls if path.endswith("/comments")))

    def test_auto_issue_enables_merge_after_approval(self):
        head = "a" * 40
        event = {"pull_request": {"number": 18, "head": {"sha": head}, "body": "Closes #9"}}
        verdict = {"verdict": "approved", "summary": "Criteria met.", "blocking_findings": [], "followups": []}
        calls = []

        def fake_api(method, path, token, payload=None):
            calls.append((method, path, payload))
            if path.endswith("/pulls/18"):
                return {"head": {"sha": head}, "state": "open", "labels": [], "node_id": "PR_node"}
            if "/comments?" in path:
                return []
            if path.endswith("/issues/9"):
                return {"labels": [{"name": "auto"}]}
            return {}

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
                complete_codex_review, "api", side_effect=fake_api
            ), patch("sys.argv", ["gate", str(result_path)]):
                self.assertEqual(complete_codex_review.main(), 0)
        self.assertTrue(any(path == "/graphql" for _, path, _ in calls))

    def test_existing_followup_issue_is_reused(self):
        head = "a" * 40
        existing_body = f"<!-- gw2-codex-followup pr:18 head:{head} index:0 -->"
        with patch.object(
            complete_codex_review,
            "api",
            return_value=[{"body": existing_body, "html_url": "https://github.com/Nebettadjsr/GW2/issues/40"}],
        ):
            found = complete_codex_review.existing_followups("Nebettadjsr/GW2", 18, head, "fixture-token")
        self.assertEqual(found, {0: "https://github.com/Nebettadjsr/GW2/issues/40"})


if __name__ == "__main__":
    unittest.main()
