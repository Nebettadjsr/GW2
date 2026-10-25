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
        "body": f"<!-- gw2-claude-run-report:v1 head={SHA} -->\nCloses #42\n\n## Implementation report\n\nLocal-only checks still needed: none\nUnresolved gaps: none\n",
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
    def test_branch_behind_master_is_queued_for_preparation_and_fresh_checks(self):
        pr, issue, comments, checks, _ = fixtures()
        pr["mergeable_state"] = "behind"
        self.assertIn("current master", gate.rework_reason(pr, issue, comments, checks, REPO))

    def test_current_review_failure_requeues_but_old_or_running_review_does_not(self):
        pr, issue, comments, checks, _ = fixtures()
        comments[0]["body"] = comments[0]["body"].replace('"has_any_findings": false', '"has_any_findings": true')
        self.assertIn("Codex", gate.rework_reason(pr, issue, comments, checks, REPO))
        comments[0]["body"] = comments[0]["body"].replace(SHA, "b" * 40)
        self.assertIsNone(gate.rework_reason(pr, issue, comments, checks, REPO))
        comments[0]["body"] = comments[0]["body"].replace("b" * 40, SHA).replace('"status": "completed"', '"status": "running"')
        self.assertIsNone(gate.rework_reason(pr, issue, comments, checks, REPO))

    def test_ci_failure_requeues_existing_work(self):
        pr, issue, comments, checks, _ = fixtures()
        checks[0]["conclusion"] = "failure"
        self.assertIn("CI", gate.rework_reason(pr, issue, comments, checks, REPO))
        checks[0]["conclusion"] = "success"
        self.assertIsNone(gate.rework_reason(pr, issue, comments, checks, REPO))

    def test_report_gaps_block_merge_without_automatically_requeuing_coding(self):
        for original, replacement in (
            ("Local-only checks still needed: none", "Local-only checks still needed: browser smoke"),
            ("Unresolved gaps: none", "Unresolved gaps: activity unverified"),
        ):
            with self.subTest(report=replacement):
                pr, issue, comments, checks, files = fixtures()
                pr["body"] = pr["body"].replace(original, replacement)
                self.assertIsNone(gate.rework_reason(pr, issue, comments, checks, REPO))
                self.assertIsNotNone(gate.ready_reason(pr, issue, comments, checks, files, REPO))
        pr["head"]["sha"] = "b" * 40
        self.assertIsNone(gate.rework_reason(pr, issue, comments, checks, REPO))

    def test_requeue_respects_manual_holds_and_branch_ownership(self):
        for hold in ("pairing", "blocked", "needs-decision"):
            pr, issue, comments, checks, _ = fixtures()
            checks[0]["conclusion"] = "failure"
            issue["labels"].append({"name": hold})
            self.assertIsNone(gate.rework_reason(pr, issue, comments, checks, REPO))
        pr, issue, comments, checks, _ = fixtures()
        checks[0]["conclusion"] = "failure"
        pr["head"]["repo"]["full_name"] = "foreign/repository"
        self.assertIsNone(gate.rework_reason(pr, issue, comments, checks, REPO))

    def test_failed_ci_event_targets_only_matching_current_pr_head(self):
        pr, _, _, _, _ = fixtures()
        event = {"workflow_run": {"name": "CI", "conclusion": "failure",
                                  "head_branch": pr["head"]["ref"], "head_sha": SHA}}
        with patch.object(gate, "pages", return_value=[{"number": 42, "head": {"sha": SHA}}]):
            self.assertEqual([42], gate.candidate_numbers("workflow_run", event, REPO))
        with patch.object(gate, "pages", return_value=[{"number": 42, "head": {"sha": "b" * 40}}]):
            self.assertEqual([], gate.candidate_numbers("workflow_run", event, REPO))

    def test_failed_current_ci_adds_rework_label_without_attempting_merge(self):
        pr, issue, _, checks, files = fixtures()
        checks[0]["conclusion"] = "failure"
        calls = []

        def fake_api(path, *, method="GET", data=None):
            calls.append((path, method, data))
            if path == "pulls/42":
                return pr
            if path == "issues/42":
                return issue
            if path.endswith("/check-runs?per_page=100"):
                return {"check_runs": checks}
            if path == "issues/42/labels" and method == "POST":
                return []
            self.fail(f"Unexpected write: {path} {method}")

        with patch.dict(gate.os.environ, {"GITHUB_EVENT_NAME": "workflow_run", "GITHUB_EVENT_PATH": "unused",
                                          "GITHUB_REPOSITORY": REPO}), \
                patch.object(gate.Path, "read_text", return_value="{}"), \
                patch.object(gate, "candidate_numbers", return_value=[42]), \
                patch.object(gate, "pages", side_effect=[[], files]), \
                patch.object(gate, "api", side_effect=fake_api):
            gate.main()
        self.assertIn(("issues/42/labels", "POST", {"labels": ["needs-rework"]}), calls)

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
        pr, issue, comments, checks, files = fixtures()
        files.append({"filename": "archive/ci.yml", "previous_filename": ".github/workflows/ci.yml"})
        self.assertIn("development automation", gate.ready_reason(pr, issue, comments, checks, files, REPO))

    def test_rework_uses_latest_report_for_current_head(self):
        pr, issue, comments, checks, files = fixtures()
        comments.append({"user": {"login": "Nebettadjsr"},
                         "body": f"<!-- gw2-claude-run-report:v1 head={SHA} -->\n## Implementation report\n\nLocal-only checks still needed: browser smoke\nUnresolved gaps: none"})
        self.assertIn("local-only", gate.ready_reason(pr, issue, comments, checks, files, REPO))
        comments[-1]["body"] = comments[-1]["body"].replace("browser smoke", "none")
        self.assertIsNone(gate.ready_reason(pr, issue, comments, checks, files, REPO))

    def test_report_from_previous_head_cannot_authorize_merge(self):
        pr, issue, comments, checks, files = fixtures()
        pr["head"]["sha"] = "b" * 40
        for check in checks:
            check["status"] = "completed"
        self.assertIn("implementation report", gate.ready_reason(pr, issue, comments, checks, files, REPO))

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

    def test_confirmed_merge_closes_story_issue(self):
        pr, issue, _, _, _ = fixtures()
        calls = []

        def fake_api(path, *, method="GET", data=None):
            calls.append((path, method, data))
            if path == "pulls/42" and method == "GET":
                return pr
            if path == "issues/42" and method == "GET":
                return issue
            if path.endswith("/check-runs?per_page=100"):
                return {"check_runs": []}
            if path == "pulls/42/merge" and method == "PUT":
                return {"merged": True, "sha": SHA}
            if path == "issues/42" and method == "PATCH":
                return {"state": "closed"}
            self.fail(f"Unexpected API call: {path} {method}")

        with patch.dict(gate.os.environ, {"GITHUB_EVENT_NAME": "workflow_run", "GITHUB_EVENT_PATH": "unused",
                                          "GITHUB_REPOSITORY": REPO}), \
                patch.object(gate.Path, "read_text", return_value="{}"), \
                patch.object(gate, "candidate_numbers", return_value=[42]), \
                patch.object(gate, "pages", return_value=[]), \
                patch.object(gate, "ready_reason", return_value=None), \
                patch.object(gate, "api", side_effect=fake_api):
            gate.main()

        self.assertLess(calls.index(("pulls/42/merge", "PUT", {"sha": SHA, "merge_method": "squash"})),
                        calls.index(("issues/42", "PATCH", {"state": "closed", "state_reason": "completed"})))


if __name__ == "__main__":
    unittest.main()
