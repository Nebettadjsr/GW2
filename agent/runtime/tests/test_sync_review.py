"""The documentation review of a maintainer sync: what it may write, and its rollback.

Run with: python -m unittest agent.runtime.tests.test_sync_review -v
"""
import json
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch

from agent.runtime.core import sync_review
from agent.runtime.human import architect_requests, user_decisions

DECISION = "\n\n".join(f"## {heading}\n\n{'OPEN' if heading == 'Status' else 'TODO' if heading in ('User Decision', 'Resolution') else 'Context.'}"
                       for heading in user_decisions.REQUIRED_DECISION_HEADINGS) + "\n"


class SyncReviewTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = root = Path(directory.name).resolve()
        self.docs = root / "docs"
        (self.docs / "crafting").mkdir(parents=True)
        self.decisions = root / "agent" / "user-decisions"
        self.requests = root / "agent" / "architect-requests"
        self.artifacts = root / "agent" / "runtime" / "artifacts"
        for path in (self.decisions, self.requests, self.artifacts):
            path.mkdir(parents=True)
        (root / "CLAUDE.md").write_text("## Documentation Map\n\n| a | b |\n\n## Roles\n", encoding="utf-8")
        self.state = self.docs / "CURRENT_STATE_SPEC.md"
        self.domain = self.docs / "DOMAIN_SPEC.md"
        self.state.write_text("old behaviour\n", encoding="utf-8")
        self.domain.write_text("rule\n", encoding="utf-8")
        self.result = self.artifacts / "SYNC_REVIEW_RESULT.json"
        self.pending = self.artifacts / "PENDING_COMMIT_PATHS.json"

        stack = ExitStack()
        self.addCleanup(stack.close)
        for name, value in (("REPO_ROOT", root), ("DOCS_DIR", self.docs), ("ARTIFACTS_DIR", self.artifacts),
                            ("LOGS_DIR", root / "agent" / "logs"), ("USER_DECISIONS_DIR", self.decisions),
                            ("ARCHITECT_REQUESTS_DIR", self.requests), ("SYNC_REVIEW_RESULT_FILE", self.result),
                            ("PENDING_COMMIT_PATHS_FILE", self.pending),
                            ("WRITABLE_DOCUMENTS", (self.state, self.docs / "KNOWN_PROBLEMS.md")),
                            ("WRITABLE_DIRECTORIES", (self.docs / "crafting",))):
            stack.enter_context(patch.object(sync_review, name, value))
        stack.enter_context(patch.object(user_decisions, "USER_DECISIONS_DIR", self.decisions))
        stack.enter_context(patch.object(architect_requests, "ARCHITECT_REQUESTS_DIR", self.requests))
        stack.enter_context(patch.object(sync_review.git_sync, "working_tree_paths", return_value=set()))

    def review(self, writes, result):
        def codex(prompt, **_):
            self.assertIn("DOCUMENTATION MAP", prompt)
            for path, content in writes.items():
                (self.root / path).write_text(content, encoding="utf-8")
            if result is not None:
                self.result.write_text(json.dumps(result), encoding="utf-8")
            return 0
        with patch.object(sync_review.local_planner_runner, "run_codex", side_effect=codex):
            return sync_review.run_sync_review("a" * 40, "b" * 40)

    def finding(self, kind, artifacts):
        return {"status": "COMPLETE", "reason": "r",
                "findings": [{"kind": kind, "summary": "s", "evidence": ["src/x"], "artifacts": artifacts}]}

    def test_a_lagging_descriptive_doc_is_updated_and_queued_for_commit(self):
        result = self.review({"docs/CURRENT_STATE_SPEC.md": "new behaviour\n"},
                             self.finding("DOC_UPDATED", ["docs/CURRENT_STATE_SPEC.md"]))
        self.assertEqual(result["status"], "COMPLETE", result)
        self.assertEqual(self.state.read_text(encoding="utf-8"), "new behaviour\n")
        self.assertEqual(json.loads(self.pending.read_text())["paths"], ["docs/CURRENT_STATE_SPEC.md"])
        self.assertIn("DOC_UPDATED: s", sync_review.planner_notes(result))

    def test_editing_an_authoritative_spec_rolls_back_the_whole_pass(self):
        result = self.review({"docs/DOMAIN_SPEC.md": "rewritten\n", "docs/CURRENT_STATE_SPEC.md": "new\n"},
                             self.finding("DOC_UPDATED", ["docs/DOMAIN_SPEC.md"]))
        self.assertEqual(result["status"], "FAILED")
        self.assertIn("Not writable by the sync review: docs/DOMAIN_SPEC.md", result["validation_problems"])
        self.assertEqual(self.domain.read_text(encoding="utf-8"), "rule\n")
        self.assertEqual(self.state.read_text(encoding="utf-8"), "old behaviour\n")
        self.assertFalse(self.pending.exists())

    def test_a_contradiction_becomes_an_open_user_decision(self):
        name = "agent/user-decisions/UD-020-spec-or-code.md"
        result = self.review({name: DECISION}, self.finding("USER_DECISION", [name]))
        self.assertEqual(result["status"], "COMPLETE", result)
        self.assertTrue((self.root / name).exists())

    def test_a_malformed_user_decision_is_removed_again(self):
        name = "agent/user-decisions/UD-020-spec-or-code.md"
        result = self.review({name: "## Status\n\nOPEN\n"}, self.finding("USER_DECISION", [name]))
        self.assertEqual(result["status"], "FAILED")
        self.assertFalse((self.root / name).exists())

    def test_a_missing_result_fails(self):
        self.assertEqual(self.review({}, None)["status"], "FAILED")

    def test_dependency_bumps_need_no_review(self):
        self.assertFalse(sync_review.needs_review(["pom.xml", "frontend/package-lock.json"]))
        self.assertTrue(sync_review.needs_review(["pom.xml", "frontend/src/App.vue"]))


if __name__ == "__main__":
    unittest.main()
