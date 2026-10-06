"""Fixture-only tests for authenticated asynchronous QA preparation."""

import json
import subprocess
import sys
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
from agent.runtime.evaluation import implementation_contract
from agent.runtime.evaluation import claude_prompt
from agent.runtime.human import user_decisions
from agent.runtime.qa import qa_agent
from agent.runtime.support import config


class QAPreparationBridgeTests(unittest.TestCase):
    story_id = "STORY-QA-901"

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
        self.unrelated = self.root / "src" / "unrelated.txt"
        self.unrelated.parent.mkdir()
        self.unrelated.write_text("maintainer change before QA\n", encoding="utf-8")
        self.pointer = self.agent / "CURRENT_STORY.md"
        self.backlog = self.stories / "BACKLOG.md"
        self.attempt = self.artifacts / "ATTEMPT_STATE.json"
        self.qa_state = self.artifacts / "QA_STATE.json"
        self.qa_result = self.artifacts / "QA_RESULT.json"
        self.qa_failures = self.artifacts / "qa-failures"
        self.qa_instructions = self.agent / "QA_INSTRUCTIONS.md"
        self.stack = ExitStack()
        for module, attr, value in (
            (config, "REPO_ROOT", self.root), (config, "AGENT_DIR", self.agent),
            (config, "ARTIFACTS_DIR", self.artifacts), (config, "QA_PLANS_DIR", self.plans),
            (config, "QA_STATE_FILE", self.qa_state), (config, "QA_RESULT_FILE", self.qa_result),
            (config, "QA_FAILURES_DIR", self.qa_failures), (config, "QA_INSTRUCTIONS_FILE", self.qa_instructions),
            (config, "USER_DECISIONS_DIR", self.decisions), (config, "ATTEMPT_STATE_FILE", self.attempt),
            (story_state, "REPO_ROOT", self.root), (story_state, "STORIES_DIR", self.stories),
            (story_state, "BACKLOG_FILE", self.backlog), (story_state, "CURRENT_STORY_FILE", self.pointer),
            (story_state, "ATTEMPT_STATE_FILE", self.attempt),
            (user_decisions, "USER_DECISIONS_DIR", self.decisions),
            (claude_prompt, "REPO_ROOT", self.root),
        ):
            self.stack.enter_context(patch.object(module, attr, value))
        self.addCleanup(self.stack.close)
        self.addCleanup(self.temp.cleanup)

        (self.root / "docs").mkdir()
        (self.root / "CLAUDE.md").write_text("fixture instructions\n", encoding="utf-8")
        (self.root / "docs" / "CODING_GUIDELINES.md").write_text("fixture guidelines\n", encoding="utf-8")
        (self.root / "docs" / "DOMAIN_SPEC.md").write_text("fixture domain requirements\n", encoding="utf-8")
        self.qa_instructions.write_text("Fixture QA role contract.\n", encoding="utf-8")
        self.story.write_text(
            f"# {self.story_id} Fixture story\n\n## Story ID\n{self.story_id}\n\n"
            "## Title\nFixture QA story\n\n## Status\nTODO\n\n"
            "## Dependencies\nNone.\n\n## References\n- `docs/DOMAIN_SPEC.md`\n\n"
            "## Definition of Done\nA visible fixture behavior.\n",
            encoding="utf-8",
        )
        self.pointer.write_text(f"agent/stories/{self.story.name}\n", encoding="utf-8")
        self.backlog.write_text(
            f"# Backlog\n\n## Active\n- `{self.story.name}`\n\n"
            "## To Do\n\n## Blocked\n\n## Done\n\n## Archived\n",
            encoding="utf-8",
        )
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)
        subprocess.run(["git", "add", "."], cwd=self.root, check=True)
        self.unrelated.write_text("maintainer change before QA\n", encoding="utf-8")

        self.callback_payloads = []
        test_case = self

        class CallbackHandler(BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers.get("Content-Length", "0"))
                payload = json.loads(self.rfile.read(length))
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b"ok")
                test_case.callback_payloads.append(payload)

            def log_message(self, *_args):
                pass

        self.callback_server = ThreadingHTTPServer(("127.0.0.1", 0), CallbackHandler)
        threading.Thread(target=self.callback_server.serve_forever, daemon=True).start()
        self.callback_origin = f"http://127.0.0.1:{self.callback_server.server_port}"
        self.callback_url = self.callback_origin + "/webhook-waiting/exec-1/wait-node?signature=fixture"
        self.store = TaskStore(self.root / "tasks.json")
        self.bridge = Bridge(self.store, "fixture-token", callback_origin=self.callback_origin)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.bridge))
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        self.runner = patch.object(qa_agent, "run_qa", side_effect=self.fake_qa)
        self.runner.start()
        self.addCleanup(self.runner.stop)
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.addCleanup(self.callback_server.server_close)
        self.addCleanup(self.callback_server.shutdown)
        self.mode = "READY"
        self.runner_calls = 0

    def fake_qa(self, _prompt, messages, **_kwargs):
        self.runner_calls += 1
        if self.mode == "technical_failure":
            raise RuntimeError("fixture Codex failure")
        if self.mode == "malformed":
            messages.append("not a JSON QA plan")
            return 0
        if self.mode == "unauthorized_write":
            self.unrelated.write_text("unauthorized Codex write\n", encoding="utf-8")
            return 0
        raw = {
            "status": self.mode,
            "story_id": self.story_id,
            "rationale": "Fixture rationale for this outcome.",
            "acceptance_checks": [], "invariants": [], "test_levels": [],
            "existing_tests_reviewed": [], "prepared_test_paths": [],
            "test_specifications": [],
            "pre_implementation_verification": {"status": "NOT_RUN"},
            "coverage_review": "Fixture review.", "external_sources": [],
            "clarifications": ["Which behavior should the Product Owner choose?"]
            if self.mode == "NEEDS_USER" else [],
        }
        if self.mode == "READY":
            raw["test_levels"] = ["unit"]
            raw["test_specifications"] = ["Assert the fixture's visible result."]
        messages.append(json.dumps(raw))
        return 0

    def request(self, key="qa-request-1", *, payload=None, token="fixture-token"):
        payload = payload or {
            "story_id": self.story_id,
            "story_contract_sha256": qa_agent.story_contract_sha256(self.story.read_text(encoding="utf-8")),
            "callback_url": self.callback_url,
        }
        req = Request(
            self.url + "/qa-preparations", data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json", "Idempotency-Key": key,
                     "Authorization": f"Bearer {token}"}, method="POST",
        )
        try:
            with urlopen(req, timeout=3) as response:
                return response.status, json.loads(response.read())
        except HTTPError as error:
            return error.code, json.loads(error.read())

    def wait_task(self, task_id):
        for _ in range(300):
            task = self.store.get(task_id)
            if task and task["status"] in {"completed", "failed", "interrupted"}:
                return task
            time.sleep(0.02)
        self.fail("QA task did not reach a terminal state")

    def wait_callback(self, count=1):
        for _ in range(150):
            if len(self.callback_payloads) >= count:
                return self.callback_payloads[-1]
            time.sleep(0.02)
        self.fail("QA completion callback was not delivered")

    def test_ready_plan_completes_with_callback_and_opens_existing_implementation_gate(self):
        status, submitted = self.request()
        self.assertEqual(202, status)
        task = self.wait_task(submitted["task_id"])
        callback = self.wait_callback()
        self.assertEqual("completed", task["status"])
        self.assertEqual("READY", task["result"]["qa_status"])
        self.assertEqual("completed", callback["status"])
        self.assertEqual("READY", callback["result"]["qa_status"])
        readiness = implementation_contract.inspect_active_story()
        self.assertTrue(readiness["implementation_permitted"])
        self.assertEqual("READY", readiness["qa"]["status"])
        self.assertEqual(1, self.runner_calls)

    def test_no_tests_needed_is_a_valid_persisted_qa_outcome(self):
        self.mode = "NO_TESTS_NEEDED"
        status, submitted = self.request()
        task = self.wait_task(submitted["task_id"])
        self.wait_callback()
        self.assertEqual(202, status)
        self.assertEqual("NO_TESTS_NEEDED", task["result"]["qa_status"])
        readiness = implementation_contract.inspect_active_story()
        self.assertTrue(readiness["implementation_permitted"])
        self.assertEqual("NO_TESTS_NEEDED", readiness["qa"]["status"])

    def test_needs_user_is_reported_without_activating_or_blocking_another_story(self):
        self.mode = "NEEDS_USER"
        status, submitted = self.request()
        task = self.wait_task(submitted["task_id"])
        self.wait_callback()
        self.assertEqual(202, status)
        self.assertEqual("NEEDS_USER", task["result"]["qa_status"])
        self.assertTrue(task["result"]["outstanding_requirements"])
        self.assertFalse(implementation_contract.inspect_active_story()["implementation_permitted"])
        self.assertEqual([self.story.name], story_state.parse_backlog_section(self.backlog.read_text(), "Active"))

    def test_malformed_output_and_runner_failure_are_distinct_technical_failures(self):
        for mode, category in (("malformed", "invalid_qa_output"),
                               ("technical_failure", "qa_runner_failure")):
            with self.subTest(mode=mode):
                self.mode = mode
                key = "qa-" + mode
                expected_callback_count = len(self.callback_payloads) + 1
                status, submitted = self.request(key)
                task = self.wait_task(submitted["task_id"])
                self.wait_callback(expected_callback_count)
                self.assertEqual(202, status)
                self.assertEqual("failed", task["status"])
                self.assertEqual(category, task["error"]["category"])
                self.assertTrue(task["error"]["failure_report"])

    def test_idempotent_duplicate_does_not_start_codex_twice(self):
        _status, submitted = self.request()
        task = self.wait_task(submitted["task_id"])
        self.wait_callback()
        before = self.runner_calls
        _status, replay = self.request()
        self.assertEqual("idempotent_replay", replay["submission_disposition"])
        self.assertEqual(task["id"], replay["task_id"])
        self.assertEqual(before, self.runner_calls)
        self.assertEqual("READY", replay["result"]["qa_status"])

    def test_interrupted_request_is_not_restarted_and_blocks_a_new_key(self):
        self.bridge.start_qa_preparation = lambda _task_id: None
        _status, submitted = self.request()
        self.store.update(submitted["task_id"], status="running")
        restarted = TaskStore(self.root / "tasks.json")
        self.bridge.store = restarted
        _status, replay = self.request()
        self.assertEqual("interrupted", replay["status"])
        self.assertEqual(0, self.runner_calls)
        status, rejected = self.request("new-qa-request")
        self.assertEqual(409, status)
        self.assertIn("uncertain outcome", rejected["error"])
        self.assertEqual(0, self.runner_calls)

    def test_interruption_after_plan_persisted_recovers_the_plan_without_codex(self):
        raw = {
            "status": "READY", "story_id": self.story_id,
            "rationale": "Fixture plan survived worker interruption.",
            "acceptance_checks": [], "invariants": [], "test_levels": ["unit"],
            "existing_tests_reviewed": [], "prepared_test_paths": [],
            "test_specifications": ["Check fixture behavior."],
            "pre_implementation_verification": {"status": "NOT_RUN"},
            "coverage_review": "Fixture review.", "external_sources": [], "clarifications": [],
        }
        qa_agent.save_plan(self.story, raw)
        self.bridge.start_qa_preparation = lambda _task_id: None
        _status, submitted = self.request()
        self.store.update(submitted["task_id"], status="running")
        self.bridge.store = TaskStore(self.root / "tasks.json")
        _status, recovered = self.request()
        self.assertEqual("completed", recovered["status"])
        self.assertEqual("cached_result", recovered["submission_disposition"])
        self.assertEqual("persisted_qa_plan", recovered["result"]["result_source"])
        self.assertEqual(0, self.runner_calls)

    def test_requires_auth_and_rejects_nonactive_story_or_stale_requirements(self):
        payload = {
            "story_id": self.story_id,
            "story_contract_sha256": qa_agent.story_contract_sha256(self.story.read_text(encoding="utf-8")),
            "callback_url": self.callback_url,
        }
        self.assertEqual(401, self.request(token="wrong")[0])
        wrong_story = dict(payload, story_id="STORY-QA-999")
        self.assertEqual(409, self.request("wrong-story", payload=wrong_story)[0])
        stale = dict(payload, story_contract_sha256="0" * 64)
        self.assertEqual(409, self.request("stale-contract", payload=stale)[0])

    def test_existing_unrelated_maintainer_change_is_restored_and_qa_fails_closed(self):
        self.mode = "unauthorized_write"
        _status, submitted = self.request("unauthorized-write")
        task = self.wait_task(submitted["task_id"])
        self.wait_callback()
        self.assertEqual("failed", task["status"])
        self.assertEqual("file_protection_violation", task["error"]["category"])
        self.assertEqual("maintainer change before QA\n", self.unrelated.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
