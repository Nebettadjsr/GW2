"""Regression coverage for hash-bound evaluator recovery on DONE stories."""

import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from agent.runtime.core import orchestrator


class CompletionRecoveryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.story_path = root / "STORY-QA-990-pilot.md"
        self.story = (
            "# Pilot\n\n## Story ID\n\nSTORY-QA-990\n"
            "\n## Status\n\nDONE\n"
            "\n## Acceptance Criteria\n\n- verified\n"
        )
        self.story_path.write_text(self.story, encoding="utf-8")
        self.claude_result = root / "CLAUDE_RESULT.md"
        self.claude_result.write_text("Pilot implementation evidence\n", encoding="utf-8")
        self.evaluator_result = root / "EVALUATOR_RESULT.json"
        self.patches = (
            patch.object(orchestrator, "CLAUDE_RESULT_FILE", self.claude_result),
            patch.object(orchestrator, "EVALUATOR_RESULT_FILE", self.evaluator_result),
            patch.object(orchestrator.git_sync, "ci_verification_available",
                         return_value=(False, "pilot has no remote")),
        )
        for item in self.patches:
            item.start()

    def tearDown(self):
        for item in reversed(self.patches):
            item.stop()
        self.temp.cleanup()

    def _write_evaluator_result(self, *, decision="COMPLETE", story=None, result=None):
        payload = {
            "decision": decision,
            "story_id": "STORY-QA-990",
            "evaluated_story_sha256": hashlib.sha256(
                (story or self.story).encode("utf-8")
            ).hexdigest(),
            "evaluated_result_sha256": result or orchestrator.file_hash(self.claude_result),
        }
        self.evaluator_result.write_text(json.dumps(payload), encoding="utf-8")

    def test_done_story_without_a_saved_evaluation_must_resume_evaluation(self):
        reason = orchestrator.unpublished_completion(self.story_path, self.story)

        self.assertIn("no durable COMPLETE evaluator verdict", reason)

    def test_matching_complete_verdict_proves_no_ci_story_is_finished(self):
        self._write_evaluator_result()

        reason = orchestrator.unpublished_completion(self.story_path, self.story)

        self.assertIsNone(reason)

    def test_stale_complete_verdict_for_an_older_result_must_not_skip_evaluation(self):
        self._write_evaluator_result(result="older-result-hash")

        reason = orchestrator.unpublished_completion(self.story_path, self.story)

        self.assertIn("no durable COMPLETE evaluator verdict", reason)

    def test_complete_verdict_for_an_older_story_revision_must_not_skip(self):
        self._write_evaluator_result(story=self.story + "changed\n")

        reason = orchestrator.unpublished_completion(self.story_path, self.story)

        self.assertIn("no durable COMPLETE evaluator verdict", reason)

    def test_non_complete_verdict_must_not_mark_done_story_complete(self):
        self._write_evaluator_result(decision="RETRY")

        reason = orchestrator.unpublished_completion(self.story_path, self.story)

        self.assertIn("no durable COMPLETE evaluator verdict", reason)

    def test_ci_commit_cannot_substitute_for_a_missing_evaluator_verdict(self):
        with patch.object(orchestrator.git_sync, "ci_verification_available",
                          return_value=(True, "origin/master")), \
                patch.object(orchestrator.git_sync, "commit_exists_with_subject",
                             return_value=True):
            reason = orchestrator.unpublished_completion(self.story_path, self.story)

        self.assertIn("no durable COMPLETE evaluator verdict", reason)

    def test_matching_verdict_and_published_commit_prove_ci_story_completion(self):
        self._write_evaluator_result()
        with patch.object(orchestrator.git_sync, "ci_verification_available",
                          return_value=(True, "origin/master")), \
                patch.object(orchestrator.git_sync, "commit_exists_with_subject",
                             return_value=True):
            reason = orchestrator.unpublished_completion(self.story_path, self.story)

        self.assertIsNone(reason)


if __name__ == "__main__":
    unittest.main()
