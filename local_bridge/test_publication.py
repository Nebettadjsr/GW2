"""Fixture-only tests for implementation metadata and publication guards."""

import json
import hashlib
import tempfile
import unittest
import threading
from http.server import ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from urllib.request import Request, urlopen

from agent.runtime.core import story_state
from agent.runtime.qa import qa_agent
from agent.runtime.support import git_sync
from local_bridge import publication
from local_bridge.bridge import Bridge, TaskStore, make_handler


class _Store:
    def __init__(self, evaluator, qa_review=None):
        self.evaluator = evaluator
        self.qa_review = qa_review

    def get_by_idempotency_key(self, _key):
        return self.evaluator

    def get(self, task_id):
        if self.qa_review and task_id == self.qa_review["id"]:
            return self.qa_review
        return None


class PublicationReadinessTests(unittest.TestCase):
    story_id = "STORY-WEB-028"
    baseline = "a" * 40

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.story = self.root / "agent/stories/STORY-WEB-028-fixture.md"
        self.story.parent.mkdir(parents=True)
        self.story.write_text("# fixture\n", encoding="utf-8")
        self.qa_plan = self.root / "agent/qa-plans/QA-STORY-WEB-028.json"
        self.qa_plan.parent.mkdir(parents=True)
        self.qa_plan.write_text("{}", encoding="utf-8")
        source = self.root / "src/Fixture.java"
        source.parent.mkdir(parents=True)
        source.write_text("source\n", encoding="utf-8")
        self.impl = {
            "id": "impl-1", "task_type": "implementation", "story_id": self.story_id,
            "status": "completed", "exit_code": 0,
            "publication_metadata": {
                "schema_version": 1, "task_id": "impl-1", "story_id": self.story_id,
                "baseline_head_sha": self.baseline, "changed_paths": ["src/Fixture.java"],
                "changed_path_sha256": {"src/Fixture.java": hashlib.sha256(source.read_bytes()).hexdigest()},
                "ambiguous_baseline_paths": [], "protected_paths_restored": [],
                "lifecycle_paths_restored": [],
                "authorized_scope": {"qa_plan_path": "agent/qa-plans/QA-STORY-WEB-028.json",
                                     "protected_test_paths": [], "protected_test_hashes": {}},
            },
        }
        self.evaluator = {
            "id": "eval-1", "task_type": "evaluator", "story_id": self.story_id,
            "status": "completed", "exit_code": 0,
            "result": {"decision": "COMPLETE"},
        }
        self.stack = [
            patch.object(publication, "REPO_ROOT", self.root),
            patch.object(publication, "_current_context", return_value=(
                {"evaluation_permitted": True, "story": {"id": self.story_id},
                 "evaluation_contract_sha256": "f" * 64},
                {"id": self.story_id, "path": "agent/stories/STORY-WEB-028-fixture.md",
                 "content": "fixture"}, self.story_id,
            )),
            patch.object(publication, "persisted_evaluation", return_value={"decision": "COMPLETE"}),
            patch.object(qa_agent, "load_plan", return_value={"prepared_test_paths": []}),
            patch.object(qa_agent, "plan_path", return_value=self.qa_plan),
            patch.object(qa_agent, "plan_tests_unchanged", return_value=(True, [])),
            patch.object(story_state, "get_active_story_path", return_value=self.story),
            patch.object(git_sync, "head_sha", return_value=self.baseline),
            patch.object(git_sync, "current_branch", return_value="main"),
            patch.object(git_sync, "run_git", return_value=SimpleNamespace(
                returncode=0, stdout=self.baseline + "\trefs/heads/main\n")),
            patch.object(git_sync, "working_tree_paths", return_value={"src/Fixture.java"}),
            patch.object(git_sync, "working_tree_entries", return_value={"src/Fixture.java": " M"}),
            patch.object(git_sync, "_output", return_value=""),
        ]
        for context in self.stack:
            context.__enter__()
            self.addCleanup(context.__exit__, None, None, None)

    def readiness(self, implementation=None, dirty=None, staged=None):
        if dirty is not None:
            git_sync.working_tree_paths = lambda: set(dirty)
            git_sync.working_tree_entries = lambda: {path: " M" for path in dirty}
        if staged is not None:
            git_sync._output = lambda *_args: staged
        return publication.inspect_publication(implementation or self.impl, _Store(self.evaluator))

    def test_clean_scope_is_permitted_and_fingerprinted(self):
        ready = self.readiness()
        self.assertTrue(ready["publication_permitted"])
        self.assertEqual(["agent/qa-plans/QA-STORY-WEB-028.json", "src/Fixture.java"],
                         ready["authorized_paths"])
        self.assertEqual(64, len(ready["publication_contract_sha256"]))

    def test_v2_metadata_requires_captured_remote_baseline_match(self):
        metadata = dict(self.impl["publication_metadata"], schema_version=2,
                        baseline_branch="main", baseline_remote_head_sha=self.baseline)
        valid = self.readiness({**self.impl, "publication_metadata": metadata})
        self.assertTrue(valid["publication_permitted"], valid)
        stale_remote = self.readiness({**self.impl, "publication_metadata": {
            **metadata, "baseline_remote_head_sha": "b" * 40,
        }})
        self.assertEqual("baseline_metadata_invalid", stale_remote["blocking_code"])
        stale_branch = self.readiness({**self.impl, "publication_metadata": {
            **metadata, "baseline_branch": "other",
        }})
        self.assertEqual("baseline_branch_mismatch", stale_branch["blocking_code"])

    def test_unrelated_dirty_file_is_rejected(self):
        ready = self.readiness(dirty={"src/Fixture.java", "maintainer.txt"})
        self.assertEqual("unrelated_dirty_files", ready["blocking_code"])

    def test_unrelated_staged_file_is_rejected(self):
        ready = self.readiness(staged="maintainer.txt")
        self.assertEqual("unrelated_staged_files", ready["blocking_code"])

    def test_baseline_drift_is_rejected(self):
        git_sync.head_sha = lambda: "b" * 40
        git_sync.run_git = lambda *_args, **_kwargs: SimpleNamespace(
            returncode=0, stdout="c" * 40 if _args[0] == "rev-parse" else "unrelated commit\n",
        )
        ready = self.readiness()
        self.assertEqual("baseline_drift", ready["blocking_code"])

    def test_missing_metadata_and_failed_implementation_are_rejected(self):
        legacy = dict(self.impl)
        legacy.pop("publication_metadata")
        readiness = self.readiness(legacy)
        self.assertEqual("implementation_metadata_missing", readiness["blocking_code"])
        self.assertEqual(self.story_id, readiness["story"]["id"])
        failed = dict(self.impl, status="failed")
        self.assertEqual("implementation_missing", self.readiness(failed)["blocking_code"])

    def test_stale_qa_or_evaluator_contract_blocks_publication(self):
        publication._current_context = lambda *_args: (
            {"evaluation_permitted": False, "outstanding_prerequisites": ["stale QA"]},
            None, self.story_id,
        )
        self.assertEqual("evaluation_not_ready", self.readiness()["blocking_code"])

    def test_evaluator_artifact_must_still_be_complete(self):
        publication.persisted_evaluation = lambda *_args: {"decision": "RETRY"}
        self.assertEqual("evaluator_incomplete", self.readiness()["blocking_code"])

    def test_protected_path_and_preexisting_dirty_path_are_rejected(self):
        protected = dict(self.impl)
        protected["publication_metadata"] = dict(self.impl["publication_metadata"],
                                                  changed_paths=["agent/qa-plans/QA.json"])
        self.assertEqual("protected_path_in_scope", self.readiness(protected)["blocking_code"])
        ambiguous = dict(self.impl)
        ambiguous["publication_metadata"] = dict(self.impl["publication_metadata"],
                                                   ambiguous_baseline_paths=["src/Fixture.java"])
        self.assertEqual("baseline_scope_ambiguous", self.readiness(ambiguous)["blocking_code"])

    def test_human_authorized_web028_legacy_record_needs_no_baseline(self):
        metadata_paths = sorted(publication._WEB028_LEGACY_PATHS)
        path_hashes, blob_oids = {}, {}
        for index, relative in enumerate(metadata_paths, 1):
            file_path = self.root / relative
            file_path.parent.mkdir(parents=True, exist_ok=True)
            file_path.write_text(f"authorized fixture {index}\n", encoding="utf-8")
            path_hashes[relative] = publication._sha(file_path.read_bytes())
            blob_oids[relative] = f"{index:040x}"

        qa_result = {"decision": "APPROVE", "findings": []}
        evaluator_result = {"decision": "COMPLETE"}
        qa_review = {"id": "qa-web028", "task_type": "post_implementation_review",
                     "status": "completed", "exit_code": 0, "result": qa_result}
        evaluator = {"id": "eval-web028", "task_type": "evaluator", "story_id": self.story_id,
                     "status": "completed", "exit_code": 0, "result": evaluator_result}
        plan = {"prepared_test_paths": [], "protected_test_hashes": {}}
        adoption = {
            "kind": "human_authorized_legacy_publication", "authorized_by": "human",
            "story_id": self.story_id,
            "implementation_task_id": publication._WEB028_LEGACY_TASK_ID,
            "commit_sha": publication._WEB028_LEGACY_COMMIT,
            "authorized_paths": metadata_paths,
            "parent_sha": "c" * 40,
            "path_sha256": path_hashes, "git_blob_oids": blob_oids,
            "story_contract_sha256": "a" * 64,
            "qa_review_task_id": qa_review["id"],
            "qa_review_contract_sha256": "b" * 64,
            "qa_review_decision": "APPROVE",
            "qa_review_result_sha256": publication._sha(json.dumps(
                qa_result, sort_keys=True, separators=(",", ":"))),
            "evaluator_task_id": evaluator["id"],
            "evaluation_contract_sha256": "d" * 64,
            "evaluation_decision": "COMPLETE",
            "evaluator_result_sha256": publication._sha(json.dumps(
                evaluator_result, sort_keys=True, separators=(",", ":"))),
            "qa_plan_sha256": publication._sha(self.qa_plan.read_bytes()),
            "protected_test_hashes": {},
        }
        implementation = {
            "id": publication._WEB028_LEGACY_TASK_ID,
            "task_type": "implementation", "story_id": self.story_id,
            "status": "completed", "exit_code": 0,
            "publication_metadata": {
                "schema_version": 1, "task_id": publication._WEB028_LEGACY_TASK_ID,
                "story_id": self.story_id, "legacy_adoption": adoption,
                # Deliberately no baseline_head_sha or normal changed_paths.
            },
        }
        story_content = "WEB-028 current contract"
        evaluation = {
            "evaluation_permitted": True,
            "story_contract_sha256": adoption["story_contract_sha256"],
            "qa_review_task_id": qa_review["id"],
            "qa_review_contract_sha256": adoption["qa_review_contract_sha256"],
            "evaluation_contract_sha256": adoption["evaluation_contract_sha256"],
        }
        responses = {
            ("log", "-1", "--format=%s"): "implemented STORY-WEB-028",
            ("diff-tree", "--no-commit-id", "--name-only", "-r",
             publication._WEB028_LEGACY_COMMIT): "\n".join(metadata_paths),
            ("rev-parse", f"{publication._WEB028_LEGACY_COMMIT}^"): "c" * 40,
            ("ls-remote", "origin", "refs/heads/main"):
                publication._WEB028_LEGACY_COMMIT + "\trefs/heads/main",
        }
        for relative, oid in blob_oids.items():
            responses[("rev-parse", f"{publication._WEB028_LEGACY_COMMIT}:{relative}")] = oid

        def run_git(*args, **_kwargs):
            return SimpleNamespace(returncode=0, stdout=responses.get(args, ""))

        with patch.object(publication, "_current_context", return_value=(
                evaluation, {"id": self.story_id,
                             "path": "agent/stories/STORY-WEB-028-fixture.md",
                             "content": story_content}, self.story_id)), \
             patch.object(publication, "REPO_ROOT", self.root), \
             patch.object(publication, "persisted_evaluation", return_value={"decision": "COMPLETE"}), \
             patch.object(qa_agent, "load_plan", return_value=plan), \
             patch.object(qa_agent, "plan_tests_unchanged", return_value=(True, [])), \
             patch.object(git_sync, "head_sha", return_value=publication._WEB028_LEGACY_COMMIT), \
             patch.object(git_sync, "current_branch", return_value="main"), \
             patch.object(git_sync, "run_git", side_effect=run_git):
            store = _Store(evaluator, qa_review)
            ready = publication.inspect_publication(implementation, store)
            with patch.object(git_sync, "commit_and_push") as commit_and_push:
                published = publication.publish_implementation(implementation, store)
                commit_and_push.assert_not_called()

        self.assertTrue(ready["publication_permitted"], ready)
        self.assertTrue(ready["already_published"])
        self.assertEqual(publication._WEB028_LEGACY_COMMIT, ready["commit_sha"])
        self.assertEqual(metadata_paths, ready["authorized_paths"])
        self.assertNotIn("baseline_head_sha", implementation["publication_metadata"])
        self.assertEqual("ALREADY_PUBLISHED", published["status"])
        self.assertEqual(publication._WEB028_LEGACY_COMMIT, published["sha"])


class PublicationWorkflowFixtureTests(unittest.TestCase):
    def test_n8n_parent_resumes_from_evaluator_to_publication_then_ci(self):
        path = Path(__file__).parent / "workflows/GW2-Development-Pipeline.json"
        workflow = json.loads(path.read_text(encoding="utf-8"))[0]
        connections = workflow["connections"]
        self.assertEqual("Publish Implementation",
                         connections["Evaluator Complete?"]["main"][0][0]["node"])
        self.assertEqual("Run Final Application CI",
                         connections["Publication Succeeded?"]["main"][0][0]["node"])
        final_ci = next(node for node in workflow["nodes"] if node["name"] == "Normalize Run Final CI")
        self.assertIn("raw.commit_sha===publishedSha", final_ci["parameters"]["jsCode"])
        ci_call = next(node for node in workflow["nodes"] if node["name"] == "Run Final Application CI")
        self.assertEqual("={{ $('Normalize Publication').first().json.commit_sha }}",
                         ci_call["parameters"]["workflowInputs"]["value"]["published_commit_sha"])

    def test_publish_workflow_is_reusable_and_bridge_authenticated(self):
        path = Path(__file__).parent / "workflows/GW2-Publish-Implementation.json"
        workflow = json.loads(path.read_text(encoding="utf-8"))[0]
        self.assertEqual("GW2 - Publish Implementation", workflow["name"])
        submit = next(node for node in workflow["nodes"] if node["name"] == "Submit Implementation Publication")
        self.assertEqual("httpHeaderAuth", submit["parameters"]["genericAuthType"])
        self.assertEqual("http://172.29.240.1:8765/implementation-publications", submit["parameters"]["url"])

    def test_final_ci_child_refuses_a_different_head_sha(self):
        path = Path(__file__).parent / "workflows/GW2-Run-Final-CI.json"
        workflow = json.loads(path.read_text(encoding="utf-8"))[0]
        gate = next(node for node in workflow["nodes"] if node["name"] == "Final CI Gate Permits Submission?")
        conditions = gate["parameters"]["conditions"]["conditions"]
        self.assertTrue(any("published_commit_sha" in condition["leftValue"] for condition in conditions))


class PublicationExecutionTests(unittest.TestCase):
    story_id = "STORY-WEB-028"
    sha = "b" * 40

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.story = Path(self.temp.name) / "STORY-WEB-028-fixture.md"
        self.story.write_text("## Story ID\nSTORY-WEB-028\n\n## Title\nFixture\n", encoding="utf-8")
        self.readiness = {
            "publication_permitted": True,
            "story": {"id": self.story_id},
            "authorized_paths": ["src/Fixture.java"],
        }

    def run_publish(self, subject="previous", commit_result=None):
        from agent.runtime.core import orchestrator
        with patch.object(publication, "inspect_publication", return_value=self.readiness), \
             patch.object(story_state, "get_active_story_path", return_value=self.story), \
             patch.object(orchestrator, "_story_commit_message", return_value="implemented STORY-WEB-028: Fixture"), \
             patch.object(git_sync, "head_sha", return_value=self.sha), \
             patch.object(git_sync, "current_branch", return_value="main"), \
             patch.object(git_sync, "pending_planning_paths", return_value=set()), \
             patch.object(git_sync, "run_git", side_effect=lambda *args, **kwargs: SimpleNamespace(
                 returncode=0,
                 stdout=(self.sha + "\trefs/heads/main\n") if args[:1] == ("ls-remote",)
                 else subject + "\n")), \
             patch.object(git_sync, "commit_and_push", return_value=commit_result or {
                 "status": "PUSHED", "sha": self.sha, "branch": "main", "changed_paths": ["src/Fixture.java"]
             }) as commit:
            result = publication.publish_implementation({"id": "impl"}, object())
        return result, commit

    def test_clean_publication_commits_only_recorded_paths_and_returns_sha(self):
        result, commit = self.run_publish(subject="previous")
        self.assertEqual("PUBLISHED", result["status"])
        self.assertEqual(self.sha, result["sha"])
        self.assertEqual(["src/Fixture.java"], commit.call_args.kwargs["paths"])

    def test_already_published_is_reused_without_second_commit(self):
        result, commit = self.run_publish(subject="implemented STORY-WEB-028: Fixture")
        self.assertEqual("ALREADY_PUBLISHED", result["status"])
        commit.assert_not_called()

    def test_push_failure_keeps_commit_sha_and_reports_failure(self):
        result, _ = self.run_publish(commit_result={
            "status": "PUSH_REJECTED", "sha": self.sha, "branch": "main", "reason": "rejected",
        })
        self.assertEqual("PUSH_REJECTED", result["status"])
        self.assertEqual("failed", result["push_status"])
        self.assertEqual(self.sha, result["sha"])

    def test_commit_failure_does_not_claim_push_or_success(self):
        result, _ = self.run_publish(commit_result={
            "status": "UNAVAILABLE", "sha": "a" * 40, "branch": "main",
            "reason": "git commit failed: fixture",
        })
        self.assertEqual("UNAVAILABLE", result["status"])
        self.assertEqual("not_attempted", result["push_status"])
        self.assertEqual("git commit failed: fixture", result["reason"])

    def test_interrupted_publication_recovers_only_exact_remote_commit(self):
        metadata = {"baseline_head_sha": "a" * 40, "changed_paths": ["src/Fixture.java"]}
        responses = {
            ("rev-parse", "HEAD^"): "a" * 40,
            ("log", "-1", "--format=%s"): "implemented STORY-WEB-028: Fixture",
            ("diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD"): "src/Fixture.java",
            ("ls-remote", "origin", "refs/heads/main"): self.sha + "\trefs/heads/main",
        }
        def run(*args, **_kwargs):
            return SimpleNamespace(returncode=0, stdout=responses.get(args, ""))
        with patch.object(git_sync, "head_sha", return_value=self.sha), \
             patch.object(git_sync, "current_branch", return_value="main"), \
             patch.object(git_sync, "run_git", side_effect=run):
            self.assertEqual(self.sha, publication.verify_already_published(self.story_id, metadata))
            metadata["changed_paths"] = ["src/Other.java"]
            self.assertIsNone(publication.verify_already_published(self.story_id, metadata))

    def test_duplicate_submission_key_reuses_one_persisted_task(self):
        store = TaskStore(Path(self.temp.name) / "tasks.json")
        first, created = store.create("publish", "stable-key", task_type=publication.PUBLICATION_TASK_TYPE,
                                      story_id=self.story_id, contract_sha256="c" * 64)
        second, duplicate_created = store.create("publish", "stable-key", task_type=publication.PUBLICATION_TASK_TYPE,
                                                  story_id=self.story_id, contract_sha256="c" * 64)
        self.assertTrue(created)
        self.assertFalse(duplicate_created)
        self.assertEqual(first["id"], second["id"])

    def test_unfinished_or_interrupted_publication_conflicts_with_new_scope(self):
        store = TaskStore(Path(self.temp.name) / "tasks.json")
        task, _ = store.create("publish", "interrupted-key",
                               task_type=publication.PUBLICATION_TASK_TYPE,
                               story_id=self.story_id, contract_sha256="c" * 64)
        store.update(task["id"], status="interrupted")
        self.assertTrue(store.has_unfinished_publication_task())
        self.assertFalse(store.has_unfinished_publication_task(task["id"]))


class PublicationHTTPIdempotencyTests(unittest.TestCase):
    def test_authenticated_legacy_post_without_baseline_reuses_published_commit(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        story = root / "story.md"
        story.write_text("## Story ID\nSTORY-WEB-028\n", encoding="utf-8")
        store = TaskStore(root / "bridge-tasks.json")
        legacy_record = {
            "kind": "human_authorized_legacy_publication", "authorized_by": "human",
            "story_id": "STORY-WEB-028",
            "implementation_task_id": publication._WEB028_LEGACY_TASK_ID,
            "commit_sha": publication._WEB028_LEGACY_COMMIT,
            "authorized_paths": sorted(publication._WEB028_LEGACY_PATHS),
        }
        implementation_id = publication._WEB028_LEGACY_TASK_ID
        store.tasks[implementation_id] = {
            "id": implementation_id, "agent": "claude", "status": "completed",
            "exit_code": 0, "task_type": "implementation", "story_id": "STORY-WEB-028",
            "contract_sha256": "d" * 64, "created_at": "2026-10-01T00:00:00+00:00",
            "publication_metadata": {"schema_version": 1,
                                     "task_id": implementation_id,
                                     "story_id": "STORY-WEB-028",
                                     "legacy_adoption": legacy_record},
        }
        store._save()
        bridge = Bridge(store, "fixture-publication-token")
        server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(bridge))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(thread.join, 5)
        self.addCleanup(server.shutdown)
        readiness = {
            "publication_permitted": True,
            "story": {"id": "STORY-WEB-028"},
            "implementation_task_id": implementation_id,
            # Legacy readiness binds the validated adoption record instead of
            # requiring normal implementation baseline metadata.
            "publication_contract_sha256": hashlib.sha256(json.dumps(
                legacy_record, sort_keys=True, separators=(",", ":")).encode()).hexdigest(),
            "authorized_paths": sorted(publication._WEB028_LEGACY_PATHS),
            "evaluation_contract_sha256": "f" * 64,
        }
        fingerprint = readiness["publication_contract_sha256"]
        payload = json.dumps({"story_id": "STORY-WEB-028",
                              "publication_contract_sha256": fingerprint}).encode()
        request = Request(f"http://127.0.0.1:{server.server_port}/implementation-publications",
                          data=payload, method="POST", headers={
                              "Authorization": "Bearer fixture-publication-token",
                              "Idempotency-Key": f"gw2-publish-STORY-WEB-028-{fingerprint}",
                              "Content-Type": "application/json",
                          })
        saved_result = {"status": "ALREADY_PUBLISHED", "story_id": "STORY-WEB-028",
                        "sha": publication._WEB028_LEGACY_COMMIT,
                        "push_status": "verified"}
        with patch.object(story_state, "get_active_story_path", return_value=story), \
             patch.object(publication, "inspect_publication", return_value=readiness), \
             patch.object(publication, "publish_implementation", return_value=saved_result) as publish:
            with urlopen(request, timeout=5) as response:
                first = json.loads(response.read())
            with urlopen(request, timeout=5) as response:
                second = json.loads(response.read())
        self.assertEqual("completed", first["status"])
        self.assertEqual("completed", second["status"])
        self.assertEqual("idempotent_replay", second["submission_disposition"])
        self.assertEqual(saved_result, second["result"])
        self.assertNotIn("baseline_head_sha", readiness)
        publish.assert_called_once()


if __name__ == "__main__":
    unittest.main()
