"""Fixture tests for gated GitHub Actions final-CI monitoring through the bridge."""

import json
import tempfile
import threading
import time
import unittest
from contextlib import ExitStack
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from unittest.mock import patch

from bridge import Bridge, TaskStore, make_handler
from agent.runtime.core import story_state
from agent.runtime.qa import qa_agent
from agent.runtime.support import config, git_sync, github_ci
from local_bridge import final_ci


class ApplicationScopedCIVerdictTests(unittest.TestCase):
    sha = "a" * 40
    run_url = "https://github.invalid/actions/runs/51"
    jobs_url = "https://api.github.com/repos/fixture/repo/actions/runs/51/jobs?per_page=100"

    @staticmethod
    def job(name, conclusion="success", status="completed"):
        return {"name": name, "status": status, "conclusion": conclusion,
                "steps": [], "check_run_url": "https://api.github.com/check-runs/51"}

    def base_jobs(self):
        return [self.job("Backend tests (Maven)"),
                self.job("Frontend tests (Vitest)"),
                self.job("Coverage KPI report")]

    def evaluate(self, jobs, *, run_conclusion="failure", jobs_response=None):
        run_record = {
            "id": 51, "name": "CI", "path": ".github/workflows/ci.yml@refs/heads/main",
            "status": "completed", "conclusion": run_conclusion,
            "html_url": self.run_url,
        }

        def fetch(url, timeout=30):
            if "/actions/runs?" in url:
                return 200, {}, {"workflow_runs": [run_record]}
            if url == self.jobs_url:
                if jobs_response is not None:
                    return jobs_response
                return 200, {}, {"total_count": len(jobs), "jobs": jobs}
            if url.endswith("/annotations"):
                return 200, {}, []
            raise AssertionError(f"unexpected GitHub API request: {url}")

        with patch.object(github_ci, "fetch_json", side_effect=fetch):
            return github_ci.wait_for_commit(
                "fixture/repo", self.sha, sleep=lambda _seconds: None,
                monotonic=lambda: 0.0, application_checks=True,
            )

    def test_agent_only_failure_passes_when_each_application_job_succeeds(self):
        result = self.evaluate(self.base_jobs() + [
            self.job("Agent runtime tests (pytest-cov)", "failure")
        ])
        self.assertEqual("PASSED", result["status"])
        self.assertEqual("Agent runtime tests (pytest-cov)", result["ignored_failures"][0]["job"])

    def test_required_application_failure_stays_failed(self):
        jobs = self.base_jobs()
        jobs[0] = self.job("Backend tests (Maven)", "failure")
        self.assertEqual("FAILED", self.evaluate(jobs)["status"])

    def test_mixed_application_and_agent_failures_stay_failed(self):
        jobs = self.base_jobs() + [self.job("Agent runtime tests (pytest-cov)", "failure")]
        jobs[1] = self.job("Frontend tests (Vitest)", "failure")
        result = self.evaluate(jobs)
        self.assertEqual("FAILED", result["status"])
        self.assertIn("Frontend tests (Vitest)", result["reason"])

    def test_unknown_combined_failure_is_not_ignored(self):
        result = self.evaluate(self.base_jobs() + [self.job("Backend and agent checks", "failure")])
        self.assertEqual("FAILED", result["status"])
        self.assertIn("Backend and agent checks", result["reason"])

    def test_missing_or_unreadable_results_are_unverified(self):
        missing = self.evaluate(self.base_jobs()[:-1])
        self.assertEqual("UNVERIFIED", missing["status"])
        self.assertIn("missing or ambiguous", missing["reason"])
        unreadable = self.evaluate([], jobs_response=(503, {}, {"message": "unavailable"}))
        self.assertEqual("UNVERIFIED", unreadable["status"])

    def test_cancelled_required_application_check_is_not_a_pass(self):
        jobs = self.base_jobs()
        jobs[-1] = self.job("Coverage KPI report", "cancelled")
        result = self.evaluate(jobs)
        self.assertEqual("UNVERIFIED", result["status"])


class FinalCIBridgeTests(unittest.TestCase):
    story_id = "STORY-WEB-921"
    head_sha = "b" * 40
    evaluation_sha = "a" * 64

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.story = self.root / "agent" / "stories" / f"{self.story_id}-fixture.md"
        self.story.parent.mkdir(parents=True)
        self.story.write_text(
            f"# Fixture\n\n## Story ID\n{self.story_id}\n\n## Status\nACTIVE\n",
            encoding="utf-8",
        )
        self.plan_dir = self.root / "agent" / "qa-plans"
        self.plan_dir.mkdir(parents=True)
        self.plan_file = self.plan_dir / f"QA-{self.story_id}.json"
        self.plan_file.write_text("{}", encoding="utf-8")
        self.workflow_file = self.root / ".github" / "workflows" / "ci.yml"
        self.workflow_file.parent.mkdir(parents=True)
        self.workflow_file.write_text("name: CI\n", encoding="utf-8")
        self.store = TaskStore(self.root / "tasks.json")
        self.implementation, _ = self.store.create(
            "fixture implementation", "implementation-fixture", agent="claude",
            task_type="implementation", story_id=self.story_id,
            contract_sha256="c" * 64,
        )
        self.implementation = self.store.update(
            self.implementation["id"], status="completed", exit_code=0,
            result="fixture implementation result",
        )
        self.evaluator_key = f"evaluator:{self.story_id}:{self.evaluation_sha}"
        self.evaluator, _ = self.store.create(
            "fixture evaluator", self.evaluator_key, agent="codex",
            task_type="evaluator", story_id=self.story_id,
            contract_sha256=self.evaluation_sha,
        )
        self.evaluator_result = {
            "story_id": self.story_id,
            "evaluation_task_id": self.evaluator["id"],
            "evaluation_contract_sha256": self.evaluation_sha,
            "decision": "COMPLETE",
            "reason": "Fixture evaluator completed.",
        }
        self.evaluator = self.store.update(
            self.evaluator["id"], status="completed", exit_code=0,
            result=self.evaluator_result,
        )
        self.eval_readiness = {
            "evaluation_permitted": True,
            "outstanding_prerequisites": [],
            "story": {"id": self.story_id, "filename": self.story.name, "title": "fixture"},
            "implementation_task_id": self.implementation["id"],
            "qa_review_task_id": "qa-review-fixture",
            "evaluation_contract_sha256": self.evaluation_sha,
        }
        self.persisted_evaluator = dict(self.evaluator_result)
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        patches = (
            patch.object(config, "REPO_ROOT", self.root),
            patch.object(config, "CI_WORKFLOW_FILE", self.workflow_file),
            patch.object(story_state, "get_active_story_path", return_value=self.story),
            patch.object(qa_agent, "plan_path", return_value=self.plan_file),
            patch.object(qa_agent, "load_plan", return_value={"prepared_test_paths": ["frontend/fixture.spec.ts"]}),
            patch.object(final_ci, "inspect_active_evaluation", side_effect=self.active_evaluation),
            patch.object(final_ci, "persisted_evaluation", side_effect=self.persisted_evaluation),
            patch.object(git_sync, "ci_verification_available", return_value=(True, "fixture/repo via ci.yml")),
            patch.object(git_sync, "current_branch", return_value="main"),
            patch.object(git_sync, "unpushed_commit_count", return_value=0),
            patch.object(git_sync, "head_sha", return_value=self.head_sha),
            patch.object(final_ci, "_current_head_subject", return_value=f"implemented {self.story_id}: fixture"),
            patch.object(git_sync, "working_tree_paths", return_value=set()),
            patch.object(git_sync, "remote_slug", return_value="fixture/repo"),
            patch.object(git_sync, "commit_and_push", side_effect=AssertionError("CI must not commit or push")),
            patch.object(git_sync, "push_branch", side_effect=AssertionError("CI must not commit or push")),
        )
        for item in patches:
            self.stack.enter_context(item)

        self.ci_result = {
            "status": "PASSED", "sha": self.head_sha,
            "reason": "Backend tests: success, Frontend tests: success",
            "report": "", "run_urls": ["https://github.invalid/actions/1"],
        }
        self.wait_patch = patch.object(github_ci, "wait_for_commit", side_effect=self.wait_for_commit)
        self.wait_patch.start()
        self.addCleanup(self.wait_patch.stop)
        self.wait_calls = []
        self.application_policy_calls = []

        self.bridge = Bridge(self.store, "fixture-token", callback_origin="http://127.0.0.1:56789")
        self.callbacks = []
        case = self

        class CallbackHandler(BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers.get("Content-Length", "0"))
                case.callbacks.append(json.loads(self.rfile.read(length)))
                self.send_response(200)
                self.end_headers()

            def log_message(self, *_args):
                pass

        self.callback_server = ThreadingHTTPServer(("127.0.0.1", 0), CallbackHandler)
        threading.Thread(target=self.callback_server.serve_forever, daemon=True).start()
        self.bridge.callback_origin = f"http://127.0.0.1:{self.callback_server.server_port}"
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.bridge))
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        self.callback_url = self.bridge.callback_origin + "/webhook-waiting/run-1/wait?signature=fixture"
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.addCleanup(self.callback_server.server_close)
        self.addCleanup(self.callback_server.shutdown)

    def active_evaluation(self, implementation, store, allow_task_id=None):
        if not self.eval_readiness["evaluation_permitted"]:
            return dict(self.eval_readiness)
        if not implementation or implementation.get("status") != "completed" or implementation.get("exit_code") != 0:
            result = dict(self.eval_readiness)
            result.update(evaluation_permitted=False, outstanding_prerequisites=["implementation missing"])
            return result
        return dict(self.eval_readiness)

    def persisted_evaluation(self, task_id, fingerprint, story_id):
        if (self.persisted_evaluator and task_id == self.evaluator["id"]
                and fingerprint == self.evaluation_sha and story_id == self.story_id):
            return dict(self.persisted_evaluator)
        return None

    def wait_for_commit(self, slug, sha, sleep, monotonic, notify=None, application_checks=False):
        self.wait_calls.append((slug, sha))
        self.application_policy_calls.append(application_checks)
        return dict(self.ci_result)

    def readiness(self):
        return final_ci.inspect_final_ci(self.store.latest_implementation_task(self.story_id), self.store)

    def request(self, callback_url=None, fingerprint=None, sha=None):
        ready = self.readiness()
        fp = fingerprint or ready.get("final_ci_contract_sha256") or ("f" * 64)
        target_sha = sha or ready.get("head_sha") or self.head_sha
        body = {
            "story_id": self.story_id,
            "head_sha": target_sha,
            "final_ci_contract_sha256": fp,
            "callback_url": callback_url or self.callback_url,
        }
        request = Request(
            self.url + "/final-ci-tasks", data=json.dumps(body).encode(),
            headers={"Authorization": "Bearer fixture-token", "Content-Type": "application/json",
                     "Idempotency-Key": f"gw2-final-ci-{self.story_id}-{fp}"},
            method="POST",
        )
        try:
            with urlopen(request, timeout=3) as response:
                return response.status, json.loads(response.read())
        except HTTPError as error:
            return error.code, json.loads(error.read())

    def wait_task(self, task_id, status=None):
        for _ in range(200):
            task = self.store.get(task_id)
            if task and (status is None and task["status"] in {"completed", "failed", "interrupted"}
                         or task and status is not None and task["status"] == status):
                return task
            time.sleep(0.01)
        self.fail("Final CI task did not reach the expected state")

    def wait_callback(self, count=1):
        for _ in range(150):
            if len(self.callbacks) >= count:
                return self.callbacks[-1]
            time.sleep(0.01)
        self.fail("Final CI callback was not delivered")

    def test_ci_is_permitted_only_after_current_qa_and_evaluator_complete(self):
        ready = self.readiness()
        self.assertTrue(ready["ci_permitted"], ready)
        self.assertEqual(self.story_id, ready["story"]["id"])
        self.eval_readiness.update(evaluation_permitted=False, outstanding_prerequisites=["QA review not approved"])
        self.assertFalse(self.readiness()["ci_permitted"])
        self.eval_readiness.update(evaluation_permitted=True, outstanding_prerequisites=[])
        self.persisted_evaluator["decision"] = "RETRY"
        self.assertFalse(self.readiness()["ci_permitted"])

    def test_missing_or_stale_evaluator_result_blocks_final_ci(self):
        self.persisted_evaluator = None
        self.assertEqual("evaluator_result_invalid", self.readiness()["blocking_code"])
        self.persisted_evaluator = {**self.evaluator_result, "story_id": "STORY-OLD-001"}
        self.assertEqual("evaluator_result_invalid", self.readiness()["blocking_code"])

    def test_unpublished_commit_and_stale_fingerprint_are_rejected(self):
        with patch.object(git_sync, "unpushed_commit_count", return_value=1):
            self.assertEqual("commit_not_pushed", self.readiness()["blocking_code"])
        ready = self.readiness()
        status, response = self.request(fingerprint="f" * 64)
        self.assertEqual(409, status)
        self.assertIn("contract", response["error"].lower())

    def test_success_and_duplicate_submission_reuse_task_and_replace_callback(self):
        status, first = self.request()
        self.assertEqual(202, status, first)
        task = self.wait_task(first["task_id"])
        self.wait_callback()
        self.assertEqual("PASSED", task["result"]["status"])
        self.assertEqual([("fixture/repo", self.head_sha)], self.wait_calls)
        self.assertEqual([True], self.application_policy_calls)
        callback2 = self.bridge.callback_origin + "/webhook-waiting/run-2/wait?signature=fixture"
        status, replay = self.request(callback_url=callback2)
        self.assertEqual(202, status, replay)
        self.assertEqual(first["task_id"], replay["task_id"])
        self.assertEqual("idempotent_replay", replay["submission_disposition"])
        # Terminal replays return the persisted result directly. They do not
        # replace or redeliver a callback for an execution that already ended.
        self.assertEqual("completed", replay["status"])
        self.assertEqual(1, len(self.wait_calls))

    def test_running_duplicate_replaces_callback_without_starting_ci_again(self):
        self.bridge.start_final_ci = lambda _task_id: None
        fingerprint = self.readiness()["final_ci_contract_sha256"]
        status, first = self.request(fingerprint=fingerprint)
        self.assertEqual(202, status, first)
        task_id = first["task_id"]
        self.store.update(task_id, status="running")

        callback2 = self.bridge.callback_origin + "/webhook-waiting/run-2/wait?signature=fixture"
        status, replay = self.request(callback_url=callback2, fingerprint=fingerprint)
        self.assertEqual(202, status, replay)
        self.assertEqual(task_id, replay["task_id"])
        self.assertEqual("idempotent_replay", replay["submission_disposition"])
        self.assertEqual(callback2, self.store.get(task_id)["callback_url"])
        self.assertEqual([], self.wait_calls)

        result = {"story_id": self.story_id, "final_ci_contract_sha256": fingerprint,
                  "sha": self.head_sha, "status": "PASSED", "reason": "fixture", "report": "", "run_urls": []}
        self.store.update(task_id, status="completed", result=result, exit_code=0)
        self.bridge.deliver_callback(task_id)
        self.wait_callback()
        self.assertEqual([], self.wait_calls)
        self.assertEqual(task_id, self.callbacks[-1]["task_id"])

    def test_legacy_failed_record_rechecks_same_run_under_application_policy(self):
        started = []
        self.bridge.start_final_ci = started.append
        fingerprint = self.readiness()["final_ci_contract_sha256"]
        status, initial = self.request(fingerprint=fingerprint)
        self.assertEqual(202, status, initial)
        task_id = initial["task_id"]
        started.clear()

        old_result = {
            "status": "FAILED", "sha": self.head_sha, "story_id": self.story_id,
            "final_ci_contract_sha256": fingerprint,
            "result_source": "github_actions_for_pushed_commit",
            "reason": "CI: failure", "failed_jobs": [
                {"job": "Agent runtime tests (pytest-cov)"},
            ],
        }
        self.store.update(task_id, status="completed", exit_code=1, result=old_result)
        callback2 = self.bridge.callback_origin + "/webhook-waiting/run-2/wait?signature=fixture"
        status, replay = self.request(callback_url=callback2, fingerprint=fingerprint)
        self.assertEqual(202, status, replay)
        self.assertEqual(task_id, replay["task_id"])
        self.assertEqual("queued", replay["status"])
        self.assertEqual([task_id], started)

        self.ci_result = {
            "status": "PASSED", "sha": self.head_sha,
            "reason": "All required GW2 application CI checks passed.",
            "report": "", "run_urls": ["https://github.invalid/actions/1"],
            "ignored_failures": [{"job": "Agent runtime tests (pytest-cov)", "conclusion": "failure"}],
        }
        self.bridge._run_final_ci(task_id)
        task = self.store.get(task_id)
        self.assertEqual("completed", task["status"])
        self.assertEqual(0, task["exit_code"])
        self.assertEqual("PASSED", task["result"]["status"])
        self.assertEqual("gw2-application-jobs-v1", task["result"]["application_verdict_policy"])
        self.assertEqual([("fixture/repo", self.head_sha)], self.wait_calls)
        self.assertEqual([True], self.application_policy_calls)

    def test_failed_ci_is_distinct_from_unverified_timeout(self):
        self.ci_result = {"status": "FAILED", "sha": self.head_sha, "reason": "Frontend tests: failure",
                          "report": "Ecto fixture test failed", "failed_jobs": [
                              {"job": "Frontend tests", "failures": ["test assertion failed"]},
                              {"job": "Backend build", "failures": ["Maven compilation failed"]},
                          ],
                          "run_urls": ["https://github.invalid/actions/2"]}
        _status, submitted = self.request()
        failed = self.wait_task(submitted["task_id"])
        self.wait_callback()
        self.assertEqual("completed", failed["status"])
        self.assertEqual(1, failed["exit_code"])
        self.assertEqual("FAILED", failed["result"]["status"])

        self.ci_result = {"status": "UNVERIFIED", "sha": self.head_sha,
                          "reason": "CI did not finish within 45 minutes", "report": "", "run_urls": []}
        self.implementation = self.store.latest_implementation_task(self.story_id)
        self.evaluator = self.store.update(self.evaluator["id"], result={**self.evaluator_result, "decision": "COMPLETE"})
        self.workflow_file.write_text("name: CI with changed contract\n", encoding="utf-8")
        status, submitted = self.request()
        self.assertEqual(202, status, submitted)
        unverified = self.wait_task(submitted["task_id"])
        self.wait_callback(2)
        self.assertEqual("failed", unverified["status"])
        self.assertEqual("UNVERIFIED", unverified["result"]["status"])
        self.assertEqual("ci_unverified", unverified["error"]["category"])

    def test_interrupted_ci_is_not_restarted_but_persisted_result_is_recovered(self):
        self.bridge.start_final_ci = lambda _task_id: None
        original_fingerprint = self.readiness()["final_ci_contract_sha256"]
        status, submitted = self.request()
        self.assertEqual(202, status, submitted)
        task_id = submitted["task_id"]
        self.store.update(task_id, status="running")
        self.store = TaskStore(self.store.path)
        self.bridge.store = self.store
        self.assertEqual("interrupted", self.store.get(task_id)["status"])
        status, body = self.request(fingerprint=original_fingerprint)
        self.assertEqual(202, status)
        self.assertEqual(task_id, body["task_id"])
        self.assertEqual("interrupted", body["status"])
        self.assertIn("will not restart", body["error"]["recovery_action"])
        self.assertEqual([], self.wait_calls)

        task = self.store.get(task_id)
        stale = {"story_id": "STORY-OLD-001", "final_ci_contract_sha256": task["contract_sha256"],
                 "sha": self.head_sha, "status": "PASSED"}
        self.store.update(task_id, result=stale)
        status, stale_recovery = self.request(fingerprint=original_fingerprint)
        self.assertEqual(202, status)
        self.assertEqual(task_id, stale_recovery["task_id"])
        self.assertIsNone(stale_recovery["result"])
        self.assertIn("will not restart", stale_recovery["error"]["recovery_action"])

        persisted = {"story_id": self.story_id, "final_ci_contract_sha256": task["contract_sha256"],
                     "sha": self.head_sha, "status": "PASSED", "reason": "Recovered", "report": "", "run_urls": []}
        self.store.update(task_id, result=persisted)
        status, recovered = self.request(fingerprint=original_fingerprint)
        self.assertEqual(202, status, recovered)
        self.assertEqual(task_id, recovered["task_id"])
        self.assertEqual("completed", recovered["status"])
        # A persisted terminal result is returned directly to the recovery
        # execution; there is no need to rely on its obsolete callback URL.
        self.assertEqual([], self.wait_calls)


if __name__ == "__main__":
    unittest.main()
