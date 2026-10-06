"""Fixture-only tests for asynchronous post-implementation QA review."""

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

from bridge import Bridge, TaskStore, _active_implementation_contract, make_handler
from agent.runtime.core import story_state
from agent.runtime.evaluation import claude_prompt, implementation_contract
from agent.runtime.human import user_decisions
from agent.runtime.qa import qa_agent
from agent.runtime.support import config


class QAReviewBridgeTests(unittest.TestCase):
    story_id = "STORY-WEB-921"
    test_path = "frontend/src/fixture/Protected.spec.ts"

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.agent = self.root / "agent"
        self.stories = self.agent / "stories"
        self.stories.mkdir(parents=True)
        self.artifacts = self.agent / "runtime" / "artifacts"
        self.artifacts.mkdir(parents=True)
        self.plans = self.agent / "qa-plans"
        self.plans.mkdir()
        self.decisions = self.agent / "user-decisions"
        self.decisions.mkdir()
        self.story = self.stories / f"{self.story_id}-fixture.md"
        self.story.write_text(
            f"# {self.story_id} Fixture story\n\n## Story ID\n{self.story_id}\n\n"
            "## Title\nFixture review\n\n## Status\nTODO\n\n"
            "## Dependencies\nNone.\n\n## References\n- `docs/DOMAIN_SPEC.md`\n\n"
            "## Definition of Done\nRequired fixture behavior.\n",
            encoding="utf-8",
        )
        self.pointer = self.agent / "CURRENT_STORY.md"
        self.pointer.write_text(f"agent/stories/{self.story.name}\n", encoding="utf-8")
        self.backlog = self.stories / "BACKLOG.md"
        self.backlog.write_text(
            f"# Backlog\n\n## Active\n- `{self.story.name}`\n\n"
            "## To Do\n\n## Blocked\n\n## Done\n\n## Archived\n",
            encoding="utf-8",
        )
        self.attempt = self.artifacts / "ATTEMPT_STATE.json"
        (self.root / "docs").mkdir()
        (self.root / "CLAUDE.md").write_text("fixture instructions\n", encoding="utf-8")
        (self.root / "docs/CODING_GUIDELINES.md").write_text("fixture guidance\n", encoding="utf-8")
        (self.root / "docs/DOMAIN_SPEC.md").write_text("fixture domain\n", encoding="utf-8")
        prepared = self.root / self.test_path
        prepared.parent.mkdir(parents=True)
        prepared.write_text("protected fixture test\n", encoding="utf-8")

        self.stack = ExitStack()
        for module, attr, value in (
            (story_state, "REPO_ROOT", self.root), (story_state, "STORIES_DIR", self.stories),
            (story_state, "BACKLOG_FILE", self.backlog), (story_state, "CURRENT_STORY_FILE", self.pointer),
            (story_state, "ATTEMPT_STATE_FILE", self.attempt),
            (config, "REPO_ROOT", self.root), (config, "AGENT_DIR", self.agent),
            (config, "ARTIFACTS_DIR", self.artifacts), (config, "QA_PLANS_DIR", self.plans),
            (config, "ATTEMPT_STATE_FILE", self.attempt), (config, "USER_DECISIONS_DIR", self.decisions),
            (config, "QA_STATE_FILE", self.artifacts / "QA_STATE.json"),
            (config, "CURRENT_STORY_FILE", self.pointer), (config, "BACKLOG_FILE", self.backlog),
            (user_decisions, "USER_DECISIONS_DIR", self.decisions),
            (claude_prompt, "REPO_ROOT", self.root),
        ):
            self.stack.enter_context(patch.object(module, attr, value))
        self.addCleanup(self.stack.close)
        self.addCleanup(self.temp.cleanup)

        raw_plan = {
            "schema_version": 1, "story_id": self.story_id, "status": "READY",
            "rationale": "Fixture needs a focused test.", "acceptance_checks": [],
            "invariants": [], "test_levels": ["unit"], "existing_tests_reviewed": [],
            "prepared_test_paths": [self.test_path], "test_specifications": [],
            "pre_implementation_verification": {"status": "NOT_RUN"},
            "coverage_review": "Fixture coverage reviewed.", "external_sources": [],
            "clarifications": [], "post_implementation_review_required": True,
            "post_implementation_review_reason": "Fixture independent review.",
        }
        self.plan = qa_agent.save_plan(self.story, raw_plan, [self.test_path])
        self.contract = implementation_contract.inspect_active_story()
        self.assertTrue(self.contract["implementation_permitted"])

        self.callbacks = []
        test_case = self

        class CallbackHandler(BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers.get("Content-Length", "0"))
                test_case.callbacks.append(json.loads(self.rfile.read(length)))
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b"ok")

            def log_message(self, *_args):
                pass

        self.callback_server = ThreadingHTTPServer(("127.0.0.1", 0), CallbackHandler)
        threading.Thread(target=self.callback_server.serve_forever, daemon=True).start()
        self.callback_origin = f"http://127.0.0.1:{self.callback_server.server_port}"
        self.callback_url = self.callback_origin + "/webhook-waiting/exec-1/wait?signature=fixture"
        self.store = TaskStore(self.root / "tasks.json")
        self.bridge = Bridge(self.store, "fixture-token", callback_origin=self.callback_origin)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.bridge))
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        self._add_completed_implementation()
        self.mode = "APPROVE"
        self.runner_calls = 0
        self.runner_patch = patch.object(qa_agent, "run_qa", side_effect=self.fake_qa)
        self.runner_patch.start()
        self.addCleanup(self.runner_patch.stop)
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.addCleanup(self.callback_server.server_close)
        self.addCleanup(self.callback_server.shutdown)

    def _add_completed_implementation(self):
        task, _ = self.store.create(
            "fixture implementation", f"implementation-fixture-{len(self.store.tasks)}", agent="claude",
            task_type="implementation", story_id=self.story_id,
            contract_sha256=self.contract["story_contract_sha256"],
        )
        self.store.update(task["id"], status="completed", exit_code=0, result="fixture done")

    def fake_qa(self, _prompt, messages, **kwargs):
        self.runner_calls += 1
        self.assertTrue(kwargs.get("read_only"))
        if self.mode == "malformed":
            messages.append("not structured QA output")
        elif self.mode == "protected_change":
            (self.root / self.test_path).write_text("changed by fake runner\n", encoding="utf-8")
            messages.append(json.dumps({"decision": "APPROVE", "reason": "ok", "evidence": [], "actionable_items": []}))
        else:
            messages.append(json.dumps({
                "decision": self.mode, "reason": "Fixture review result.",
                "evidence": ["frontend/src/fixture/Protected.spec.ts"],
                "actionable_items": ["Fix the observed defect."] if self.mode == "RETRY" else [],
            }))
        return 0

    def request(self, supplied_key="n8n-execution-1", callback_url=None):
        payload = {
            "story_id": self.story_id,
            "story_contract_sha256": self.contract["story_contract_sha256"],
            "callback_url": callback_url or self.callback_url,
        }
        req = Request(
            self.url + "/qa-reviews", data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json", "Idempotency-Key": supplied_key,
                     "Authorization": "Bearer fixture-token"}, method="POST",
        )
        try:
            with urlopen(req, timeout=3) as response:
                return response.status, json.loads(response.read())
        except HTTPError as error:
            return error.code, json.loads(error.read())

    def wait_task(self, task_id):
        for _ in range(250):
            task = self.bridge.store.get(task_id)
            if task and task["status"] in {"completed", "failed", "interrupted"}:
                return task
            time.sleep(0.02)
        self.fail("QA review did not finish")

    def wait_callback(self, count=1):
        for _ in range(150):
            if len(self.callbacks) >= count:
                return self.callbacks[-1]
            time.sleep(0.02)
        self.fail("QA review callback was not delivered")

    def test_approve_and_defect_verdicts_are_persisted_and_delivered(self):
        for index, mode in enumerate(("APPROVE", "RETRY")):
            with self.subTest(mode=mode):
                if index:
                    self._add_completed_implementation()
                self.mode = mode
                status, response = self.request("same-implementation-key")
                self.assertEqual(202, status)
                task = self.wait_task(response["task_id"])
                self.assertEqual("completed", task["status"])
                self.assertEqual(mode, task["result"]["decision"])
                self.assertEqual("new_post_implementation_review", task["result"]["result_source"])
                self.wait_callback(index + 1)
        saved = json.loads(qa_agent.plan_path(self.story_id).read_text(encoding="utf-8"))
        self.assertEqual(["APPROVE", "RETRY"], [r["decision"] for r in saved["post_implementation_reviews"]])
        self.assertEqual(self.contract["contract_sha256"],
                         implementation_contract.inspect_active_story()["contract_sha256"])

    def test_done_story_remains_reviewable_without_reopening_implementation(self):
        content = self.story.read_text(encoding="utf-8")
        self.story.write_text(story_state._replace_section_body(content, "Status", "DONE"), encoding="utf-8")
        from local_bridge.qa_review import inspect_active_review

        readiness = inspect_active_review(self.store.latest_implementation_task(self.story_id))
        self.assertTrue(readiness["review_permitted"], readiness)
        self.assertFalse(implementation_contract.inspect_active_story()["implementation_permitted"])
        status, response = self.request("review-done-story")
        self.assertEqual(202, status, response)
        task = self.wait_task(response["task_id"])
        self.assertEqual("completed", task["status"])
        self.assertEqual("APPROVE", task["result"]["decision"])

    def test_successful_implementation_blocks_execution_but_opens_review_gate(self):
        from local_bridge.qa_review import inspect_active_review

        implementation = self.store.latest_implementation_task(self.story_id)
        execution_gate = _active_implementation_contract(self.store)
        review_gate = inspect_active_review(implementation)
        self.assertFalse(execution_gate["implementation_permitted"])
        self.assertEqual("implementation_already_completed", execution_gate["preparation_status"])
        self.assertTrue(review_gate["review_permitted"], review_gate)
        self.assertEqual(implementation["id"], review_gate["implementation_task_id"])

    def test_review_contract_endpoint_reports_independent_gate(self):
        req = Request(
            self.url + "/stories/active/post-implementation-review-contract",
            headers={"Authorization": "Bearer fixture-token"},
        )
        with urlopen(req, timeout=3) as response:
            payload = json.loads(response.read())
        self.assertTrue(payload["review_permitted"], payload)
        self.assertEqual(self.story_id, payload["story"]["id"])
        self.assertEqual("local_bridge_http", payload["operation_source"])

    def test_invalid_output_fails_without_persisting_a_verdict(self):
        self.mode = "malformed"
        _status, response = self.request("invalid-review")
        task = self.wait_task(response["task_id"])
        self.wait_callback()
        self.assertEqual("failed", task["status"])
        self.assertEqual("invalid_qa_output", task["error"]["category"])
        self.assertFalse(json.loads(qa_agent.plan_path(self.story_id).read_text()).get("post_implementation_reviews", []))

    def test_changed_protected_test_blocks_review_before_codex(self):
        protected = self.root / self.test_path
        protected.write_text("unauthorized edit\n", encoding="utf-8")
        status, response = self.request("protected-test")
        self.assertEqual(409, status)
        self.assertEqual(0, self.runner_calls)
        self.assertEqual("unauthorized edit\n", protected.read_text(encoding="utf-8"))

    def test_runner_protected_test_violation_is_rejected(self):
        self.mode = "protected_change"
        _status, response = self.request("runner-protected-test")
        task = self.wait_task(response["task_id"])
        self.wait_callback()
        self.assertEqual("failed", task["status"])
        self.assertEqual("qa_review_failure", task["error"]["category"])
        self.assertEqual("changed by fake runner\n", (self.root / self.test_path).read_text())

    def test_duplicate_submission_uses_same_task_and_runs_codex_once(self):
        _status, first = self.request("duplicate-key")
        self.wait_task(first["task_id"])
        _status, second = self.request("different-http-key")
        self.assertEqual(first["task_id"], second["task_id"])
        self.assertEqual("idempotent_replay", second["submission_disposition"])
        self.assertEqual(1, self.runner_calls)

    def test_updated_qa_contract_gets_fresh_review_without_erasing_old_retry(self):
        self.mode = "RETRY"
        _status, first = self.request("first-review")
        first_task = self.wait_task(first["task_id"])
        self.assertEqual("RETRY", first_task["result"]["decision"])

        old_plan = json.loads(qa_agent.plan_path(self.story_id).read_text(encoding="utf-8"))
        old_hash = old_plan["protected_test_hashes"][self.test_path]
        (self.root / self.test_path).write_text("corrected QA-owned assertion\n", encoding="utf-8")
        revised = json.loads(json.dumps(old_plan))
        revised["coverage_review"] += " Locale-neutral assertion correction; no grouping separator is specified by the story."
        updated = qa_agent.save_plan(self.story, revised, revised["prepared_test_paths"])
        self.assertNotEqual(old_hash, updated["protected_test_hashes"][self.test_path])
        self.assertEqual(["RETRY"], [item["decision"] for item in updated["post_implementation_reviews"]])

        from local_bridge.qa_review import review_contract_sha256
        old_contract = review_contract_sha256(old_plan, self.contract["story"]["id"])
        new_contract = review_contract_sha256(updated, self.contract["story"]["id"])
        self.assertNotEqual(old_contract, new_contract)

        self.mode = "APPROVE"
        _status, second = self.request("fresh-review-after-qa-correction")
        self.assertNotEqual(first["task_id"], second["task_id"])
        task = self.wait_task(second["task_id"])
        self.assertEqual("completed", task["status"])
        self.assertEqual("APPROVE", task["result"]["decision"])
        self.assertEqual(2, self.runner_calls)
        history = json.loads(qa_agent.plan_path(self.story_id).read_text(encoding="utf-8"))["post_implementation_reviews"]
        self.assertEqual(["RETRY", "APPROVE"], [item["decision"] for item in history])
        self.assertEqual(first["task_id"], history[0]["request_id"])
        self.assertEqual(second["task_id"], history[1]["request_id"])

    def test_interrupted_review_is_not_restarted_but_persisted_review_recovers(self):
        self.mode = "APPROVE"
        self.bridge.start_qa_review = lambda _task_id: None
        _status, submitted = self.request("first-execution")
        self.store.update(submitted["task_id"], status="running")
        self.bridge.store = TaskStore(self.root / "tasks.json")
        status, interrupted = self.request("replacement-execution")
        self.assertEqual(409, status)
        self.assertIn("interrupted", interrupted["error"])
        self.assertEqual(0, self.runner_calls)

        # A durable review recorded before process loss is returned, not rerun.
        review = {"decision": "NEEDS_USER", "reason": "fixture decision needed", "evidence": [], "actionable_items": []}
        self.plan["status"] = "NEEDS_USER"
        self.plan["clarifications"] = ["fixture decision needed"]
        self.plan["post_implementation_reviews"] = [{**review, "request_id": submitted["task_id"]}]
        qa_agent.write_json(qa_agent.plan_path(self.story_id), self.plan)
        status, recovered = self.request("recover-execution")
        self.assertEqual(202, status)
        self.assertEqual("completed", recovered["status"], recovered)
        task = self.wait_task(recovered["task_id"])
        self.assertEqual("completed", task["status"])
        self.assertEqual("persisted_qa_review", task["result"]["result_source"])
        self.assertEqual("NEEDS_USER", task["result"]["decision"])
        self.wait_callback()
        self.assertEqual(0, self.runner_calls)

    def test_callback_replacement_replays_persisted_result_without_codex(self):
        _status, first = self.request("initial")
        completed = self.wait_task(first["task_id"])
        self.wait_callback()
        self.assertEqual(1, self.runner_calls)
        replacement_url = self.callback_origin + "/webhook-waiting/exec-2/wait?signature=fixture"
        _status, replay = self.request("replacement", replacement_url)
        self.assertEqual(first["task_id"], replay["task_id"])
        self.assertEqual("idempotent_replay", replay["submission_disposition"])
        self.wait_callback(2)
        self.assertEqual(1, self.runner_calls)
        self.assertEqual("APPROVE", self.callbacks[-1]["result"]["decision"])


if __name__ == "__main__":
    unittest.main()
