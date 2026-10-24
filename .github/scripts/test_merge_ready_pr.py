"""Offline checks for the review and CI merge boundary."""

import json
import unittest
from unittest.mock import patch

import merge_ready_pr as gate


SHA = "a" * 40
REPO = "Nebettadjsr/GW2"


def fixtures():
    pr = {
        "state": "open", "draft": False,
        "base": {"ref": "master", "repo": {"full_name": REPO}},
        "head": {"ref": "story/issue-42-run-100-1", "sha": SHA, "repo": {"full_name": REPO}},
        "body": "Closes #42\n\n## Implementation report\n\nLocal-only checks still needed: none\nUnresolved gaps: none\n",
        "labels": [],
    }
    issue = {"number": 42, "state": "open", "title": "STORY-TEST-011: Verify quotes", "labels": [{"name": "auto"}]}
    state = {"code_review": {"commit_sha": SHA, "status": "completed", "has_any_findings": False,
                             "has_unresolved_findings": False}}
    comments = [{"user": {"login": gate.BOT_LOGIN},
                 "body": gate.REVIEW_MARKER + "\n<!-- codex-review-state:v1 " + json.dumps(state) + " -->"}]
    checks = [{"name": name, "status": "completed", "conclusion": "success",
               "app": {"slug": "github-actions"}} for name in gate.REQUIRED_CHECKS]
    files = [{"filename": "src/test/java/parser/TpPriceParserTest.java"}]
    return pr, issue, comments, checks, files


class MergeGateTest(unittest.TestCase):
    def test_clean_current_review_and_all_checks_allow_merge(self):
        self.assertIsNone(gate.ready_reason(*fixtures(), REPO))

    def test_stale_or_unresolved_review_blocks_merge(self):
        pr, issue, comments, checks, files = fixtures()
        comments[0]["body"] = comments[0]["body"].replace(SHA, "b" * 40)
        self.assertIn("Codex review", gate.ready_reason(pr, issue, comments, checks, files, REPO))
        pr, issue, comments, checks, files = fixtures()
        comments[0]["body"] = comments[0]["body"].replace('"has_unresolved_findings": false',
                                                        '"has_unresolved_findings": true')
        self.assertIn("Codex review", gate.ready_reason(pr, issue, comments, checks, files, REPO))

    def test_incomplete_ci_or_pending_local_check_blocks_merge(self):
        pr, issue, comments, checks, files = fixtures()
        checks[0]["status"] = "in_progress"
        self.assertIn("CI checks", gate.ready_reason(pr, issue, comments, checks, files, REPO))
        pr, issue, comments, checks, files = fixtures()
        pr["body"] = pr["body"].replace("Local-only checks still needed: none", "Local-only checks still needed: browser smoke")
        self.assertIn("local-only", gate.ready_reason(pr, issue, comments, checks, files, REPO))

    def test_pairing_or_missing_report_blocks_merge(self):
        pr, issue, comments, checks, files = fixtures()
        issue["labels"].append({"name": "pairing"})
        self.assertIn("not eligible", gate.ready_reason(pr, issue, comments, checks, files, REPO))
        pr, issue, comments, checks, files = fixtures()
        pr["body"] = pr["body"].replace("## Implementation report", "## Scope")
        self.assertIn("implementation report", gate.ready_reason(pr, issue, comments, checks, files, REPO))

    def test_blocked_story_body_prevents_merge(self):
        pr, issue, comments, checks, files = fixtures()
        issue["body"] = "Migration status: BLOCKED. Dependencies: STORY-API-010."
        self.assertIn("blocked", gate.ready_reason(pr, issue, comments, checks, files, REPO))

    def test_automation_changes_require_manual_merge(self):
        pr, issue, comments, checks, files = fixtures()
        files.append({"filename": ".github/workflows/ci.yml"})
        self.assertIn("development automation", gate.ready_reason(pr, issue, comments, checks, files, REPO))

    def test_lookalike_comment_from_other_user_cannot_approve(self):
        pr, issue, comments, checks, files = fixtures()
        comments[0]["user"]["login"] = "someone-else"
        self.assertIn("Codex review", gate.ready_reason(pr, issue, comments, checks, files, REPO))

    def test_event_candidate_requires_bot_summary_or_matching_ci_run(self):
        pr, _, comments, _, _ = fixtures()
        event = {"issue": {"number": 42, "pull_request": {"url": "https://api.github.com/pulls/42"}},
                 "comment": {"user": {"login": gate.BOT_LOGIN}, "body": comments[0]["body"]}}
        self.assertEqual([42], gate.candidate_numbers("issue_comment", event, REPO))
        event["comment"]["user"]["login"] = "someone-else"
        self.assertEqual([], gate.candidate_numbers("issue_comment", event, REPO))
        run = {"workflow_run": {"name": "CI", "conclusion": "success",
                                "head_branch": pr["head"]["ref"], "head_sha": SHA}}
        with patch.object(gate, "pages", return_value=[{"number": 42, "head": {"sha": SHA}}]):
            self.assertEqual([42], gate.candidate_numbers("workflow_run", run, REPO))


if __name__ == "__main__":
    unittest.main()
