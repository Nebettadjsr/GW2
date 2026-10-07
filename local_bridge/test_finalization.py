"""Focused tests for the guarded local story finalization operation."""

import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from agent.runtime.core import story_state
from agent.runtime.support import config, git_sync
from local_bridge import finalization


class FinalizationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.story = self.root / "agent/stories/STORY-WEB-021-fixture.md"
        self.story.parent.mkdir(parents=True)
        self.backlog = self.root / "agent/stories/BACKLOG.md"
        self.current = self.root / "agent/CURRENT_STORY.md"
        self.current.parent.mkdir(parents=True, exist_ok=True)
        self.story.write_text("## Story ID\nSTORY-WEB-021\n\n## Title\nFixture\n\n## Status\nTODO\n", encoding="utf-8")
        self.backlog.write_text("## Active\n- STORY-WEB-021 | STORY-WEB-021-fixture.md | Active | milestone-01 | deps: None\n\n## Done\n", encoding="utf-8")
        self.current.write_text("agent/stories/STORY-WEB-021-fixture.md\n", encoding="utf-8")
        (self.root / "agent/runtime/artifacts").mkdir(parents=True, exist_ok=True)
        self.lock_path = self.root / "agent/runtime/artifacts/STORY_ACTIVATION.lock"
        self.journal = self.root / "agent/runtime/artifacts/ATTEMPT.json"
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)
        subprocess.run(["git", "config", "user.name", "Fixture"], cwd=self.root, check=True)
        subprocess.run(["git", "config", "user.email", "fixture@example.invalid"], cwd=self.root, check=True)
        subprocess.run(["git", "add", "--", "agent"], cwd=self.root, check=True)
        subprocess.run(["git", "commit", "-qm", "implemented STORY-WEB-021"], cwd=self.root, check=True)
        self.patches = (
            patch.object(config, "REPO_ROOT", self.root),
            patch.object(config, "ATTEMPT_STATE_FILE", self.journal),
            patch.object(story_state, "REPO_ROOT", self.root),
            patch.object(story_state, "BACKLOG_FILE", self.backlog),
            patch.object(story_state, "CURRENT_STORY_FILE", self.current),
            patch.object(story_state, "ACTIVATION_LOCK_FILE", self.lock_path),
            patch.object(story_state, "get_active_story_path", return_value=self.story),
            patch.object(story_state, "find_story_file_by_id", return_value=self.story),
            patch.object(story_state, "build_story_index", return_value={}),
            patch.object(git_sync, "REPO_ROOT", self.root),
        )
        for item in self.patches:
            item.start()

    def tearDown(self):
        for item in reversed(self.patches):
            item.stop()
        self.temp.cleanup()

    def _ready(self, *_args, **_kwargs):
        return {"finalization_permitted": True, "outstanding_prerequisites": [],
                "finalization_contract_sha256": "f" * 64,
                "head_sha": git_sync.head_sha(), "story": {"id": "STORY-WEB-021"}}

    def _transition(self, story_path, *_args):
        content = story_path.read_text(encoding="utf-8").replace("TODO", "DONE")
        story_path.write_text(content, encoding="utf-8")
        self.backlog.write_text("## Active\n\n## Done\n- STORY-WEB-021 | STORY-WEB-021-fixture.md | Done | milestone-01 | deps: None\n", encoding="utf-8")
        self.current.write_text("", encoding="utf-8")
        return []

    def test_success_commits_only_lifecycle_and_repeat_is_idempotent(self):
        unrelated = self.root / "notes.txt"
        unrelated.write_text("keep", encoding="utf-8")
        with patch.object(finalization, "inspect_finalization", side_effect=self._ready), \
             patch("agent.runtime.core.orchestrator.finalize_completed_story", side_effect=self._transition):
            first = finalization.finalize_story("STORY-WEB-021", "f" * 64, object())
            second = finalization.finalize_story("STORY-WEB-021", "f" * 64, object())
        self.assertEqual(first["status"], "finalized")
        self.assertEqual(second["status"], "already_finalized")
        self.assertEqual(first["commit_sha"], second["commit_sha"])
        self.assertEqual(unrelated.read_text(encoding="utf-8"), "keep")
        changed = subprocess.run(["git", "show", "--pretty=", "--name-only", "HEAD"], cwd=self.root, check=True, capture_output=True, text=True).stdout.splitlines()
        self.assertEqual(set(changed), {"agent/CURRENT_STORY.md", "agent/stories/BACKLOG.md", "agent/stories/STORY-WEB-021-fixture.md"})

    def test_gate_rejection_makes_no_changes(self):
        with patch.object(finalization, "inspect_finalization", return_value={"finalization_permitted": False, "outstanding_prerequisites": ["QA missing"]}):
            with self.assertRaisesRegex(ValueError, "QA missing"):
                finalization.finalize_story("STORY-WEB-021", "f" * 64, object())
        self.assertIn("TODO", self.story.read_text(encoding="utf-8"))

    def test_missing_qa_evaluator_or_verified_ci_blocks_at_readiness(self):
        class Store:
            tasks = {}
            def latest_implementation_task(self, _story_id):
                return {"id": "implementation"}
            def tasks_for(self, _story_id, _task_type):
                return []
        blockers = [
            "A current APPROVE post-implementation QA review is required.",
            "A current COMPLETE Evaluator result is required.",
            "The published commit has no verified PASSED application CI result.",
        ]
        for message in blockers:
            with self.subTest(message=message), patch.object(finalization, "inspect_final_ci", return_value={
                "ci_permitted": False, "outstanding_prerequisites": [message],
            }):
                response = finalization.inspect_finalization("STORY-WEB-021", Store())
                self.assertFalse(response["finalization_permitted"])
                self.assertIn(message, response["outstanding_prerequisites"][0])
        self.assertIn("TODO", self.story.read_text(encoding="utf-8"))

    def test_active_section_uses_canonical_membership_not_status_column(self):
        head = git_sync.head_sha()
        ci_contract = "a" * 64
        ci_task = {"id": "ci-1", "task_type": "final_ci", "story_id": "STORY-WEB-021",
                   "contract_sha256": ci_contract, "status": "completed", "exit_code": 0,
                   "result": {"status": "PASSED", "sha": head,
                              "final_ci_contract_sha256": ci_contract,
                              "result_source": "github_actions_for_pushed_commit",
                              "application_verdict_policy": "gw2-application-jobs-v1"}}
        class Store:
            tasks = {"ci-1": ci_task}
            def latest_implementation_task(self, _story_id):
                return {"id": "impl-1"}
            def get_by_idempotency_key(self, _key):
                return ci_task
        ci_readiness = {"ci_permitted": True,
                        "story": {"id": "STORY-WEB-021", "filename": self.story.name,
                                  "title": "Fixture"},
                        "head_sha": head, "final_ci_contract_sha256": ci_contract,
                        "implementation_task_id": "impl-1", "qa_review_task_id": "review-1",
                        "evaluator_task_id": "eval-1"}
        with patch.object(finalization, "inspect_final_ci", return_value=ci_readiness):
            result = finalization.inspect_finalization("STORY-WEB-021", Store())
        self.assertTrue(result["finalization_permitted"], result)
        self.assertEqual(result["story"]["id"], "STORY-WEB-021")
        self.assertEqual(story_state.parse_backlog_section(self.backlog.read_text(encoding="utf-8"), "Active"),
                         [self.story.name])

    def test_unfinished_bridge_task_blocks_finalization(self):
        class Store:
            tasks = {"busy": {"status": "running", "task_type": "generic"}}
            def latest_implementation_task(self, _story_id):
                return {"id": "implementation"}
            def tasks_for(self, _story_id, _task_type):
                return []
        response = finalization.inspect_finalization("STORY-WEB-021", Store())
        self.assertFalse(response["finalization_permitted"])
        self.assertEqual(response["blocking_code"], "task_in_progress")

    def test_staged_changes_block_commit_without_being_included(self):
        extra = self.root / "maintainer.txt"
        extra.write_text("staged", encoding="utf-8")
        subprocess.run(["git", "add", "--", "maintainer.txt"], cwd=self.root, check=True)
        with patch.object(finalization, "inspect_finalization", side_effect=self._ready):
            with self.assertRaisesRegex(ValueError, "staged changes"):
                finalization.finalize_story("STORY-WEB-021", "f" * 64, object())
        self.assertIn("TODO", self.story.read_text(encoding="utf-8"))

    def test_commit_failure_leaves_recoverable_staged_lifecycle_state(self):
        real_run = git_sync.run_git
        def fail_commit(*args, **kwargs):
            if args and args[0] == "commit":
                return subprocess.CompletedProcess(["git", *args], 1, "", "fixture commit failure")
            return real_run(*args, **kwargs)
        with patch.object(finalization, "inspect_finalization", side_effect=self._ready), \
             patch("agent.runtime.core.orchestrator.finalize_completed_story", side_effect=self._transition), \
             patch.object(git_sync, "run_git", side_effect=fail_commit):
            with self.assertRaisesRegex(RuntimeError, "local commit failed"):
                finalization.finalize_story("STORY-WEB-021", "f" * 64, object())
        staged = set(real_run("diff", "--cached", "--name-only").stdout.splitlines())
        self.assertEqual(staged, {"agent/CURRENT_STORY.md", "agent/stories/BACKLOG.md", "agent/stories/STORY-WEB-021-fixture.md"})
        with patch.object(finalization, "inspect_finalization", side_effect=self._ready), \
             patch("agent.runtime.core.orchestrator.finalize_completed_story", side_effect=self._transition):
            recovered = finalization.finalize_story("STORY-WEB-021", "f" * 64, object())
        self.assertEqual(recovered["status"], "finalized")

    def test_competing_requests_create_only_one_finalization_commit(self):
        import concurrent.futures
        with patch.object(finalization, "inspect_finalization", side_effect=self._ready), \
             patch("agent.runtime.core.orchestrator.finalize_completed_story", side_effect=self._transition):
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                results = list(pool.map(lambda _: finalization.finalize_story("STORY-WEB-021", "f" * 64, object()), range(2)))
        self.assertEqual(results[0]["commit_sha"], results[1]["commit_sha"])
        subjects = subprocess.run(["git", "log", "--format=%s"], cwd=self.root, check=True, capture_output=True, text=True).stdout
        self.assertEqual(sum(line.startswith("finalized STORY-WEB-021 after CI ") for line in subjects.splitlines()), 1)

    def test_application_ci_record_requires_pass_for_exact_published_sha(self):
        base = {"task_type": "final_ci", "story_id": "STORY-WEB-021", "contract_sha256": "a" * 64,
                "status": "completed", "exit_code": 0,
                "result": {"status": "PASSED", "sha": "b" * 40,
                          "final_ci_contract_sha256": "a" * 64,
                          "result_source": "github_actions_for_pushed_commit",
                          "application_verdict_policy": "gw2-application-jobs-v1"}}
        class Store:
            def get_by_idempotency_key(self, _key):
                return base
        self.assertIsNotNone(finalization._task_for_ci("STORY-WEB-021", "a" * 64, "b" * 40, Store()))
        base["result"]["status"] = "FAILED"
        self.assertIsNone(finalization._task_for_ci("STORY-WEB-021", "a" * 64, "b" * 40, Store()))
        base["result"]["status"] = "PASSED"
        self.assertIsNone(finalization._task_for_ci("STORY-WEB-021", "a" * 64, "c" * 40, Store()))

    def test_recovers_exact_interrupted_lifecycle_prefix_without_new_task(self):
        from agent.runtime.qa import qa_agent
        story_state.set_story_done(self.story)  # crash after the first existing lifecycle step
        head = git_sync.head_sha()
        ci_contract = "a" * 64
        task = {"id": "ci-1", "task_type": "final_ci", "story_id": "STORY-WEB-021",
                "status": "completed", "exit_code": 0, "contract_sha256": ci_contract,
                "result": {"status": "PASSED", "sha": head, "final_ci_contract_sha256": ci_contract,
                           "result_source": "github_actions_for_pushed_commit",
                           "application_verdict_policy": "gw2-application-jobs-v1"}}
        class Store:
            tasks = {"ci-1": task}
            def tasks_for(self, story_id, task_type):
                return [t for t in self.tasks.values() if t["story_id"] == story_id and t["task_type"] == task_type]
        plan_file = self.root / "agent/qa-plans/QA-STORY-WEB-021.json"
        with patch.object(config, "QA_PLANS_DIR", self.root / "agent/qa-plans"), \
             patch.object(qa_agent, "plan_path", return_value=plan_file), \
             patch("agent.runtime.core.orchestrator.finalize_completed_story", side_effect=self._transition):
            recovery = finalization.inspect_finalization(None, Store())
            self.assertTrue(recovery["finalization_permitted"], recovery)
            self.assertTrue(recovery["recovery_required"])
            result = finalization.finalize_story("STORY-WEB-021", recovery["finalization_contract_sha256"], Store())
        self.assertEqual(result["status"], "recovered_and_finalized")
        self.assertEqual(self.current.read_text(encoding="utf-8"), "")


if __name__ == "__main__":
    unittest.main()
