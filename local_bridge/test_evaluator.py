"""Fixture-only tests for the authenticated, asynchronous Evaluator bridge operation."""

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
from agent.runtime.evaluation import evaluator as evaluator_runtime
from agent.runtime.evaluation import implementation_contract
from agent.runtime.human import user_decisions
from agent.runtime.qa import qa_agent
from agent.runtime.support import config
from local_bridge.evaluation import inspect_active_evaluation, persisted_evaluation
from local_bridge.qa_review import review_contract_sha256


class EvaluatorBridgeTests(unittest.TestCase):
    story_id = "STORY-WEB-921"
    test_path = "frontend/src/fixture/Protected.spec.ts"
    tests = ("APPROVE", "RETRY", "NEEDS_USER")

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.agent = self.root / "agent"
        self.stories = self.agent / "stories"
        self.stories.mkdir(parents=True)
        self.artifacts = self.agent / "runtime" / "artifacts"
        (self.artifacts / "qa-tests").mkdir(parents=True)
        self.plans = self.agent / "qa-plans"
        self.plans.mkdir()
        self.decisions = self.agent / "user-decisions"
        self.decisions.mkdir()
        self.story = self.stories / f"{self.story_id}-fixture.md"
        self.story.write_text(
            f"# {self.story_id} Fixture story\n\n## Story ID\n{self.story_id}\n\n"
            "## Title\nFixture evaluator\n\n## Status\nTODO\n\n"
            "## Dependencies\nNone.\n\n## Acceptance Criteria\nFixture acceptance.\n\n"
            "## Definition of Done\nFixture completion.\n",
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
        self.claude_result = self.artifacts / "CLAUDE_RESULT.md"
        self.claude_result.write_text("Implementation completed for the fixture story.\n", encoding="utf-8")
        self.evaluator_file = self.artifacts / "EVALUATOR_RESULT.json"
        prepared = self.root / self.test_path
        prepared.parent.mkdir(parents=True)
        prepared.write_text("protected fixture assertion\n", encoding="utf-8")

        self.stack = ExitStack()
        for module, attr, value in (
            (story_state, "REPO_ROOT", self.root), (story_state, "STORIES_DIR", self.stories),
            (story_state, "BACKLOG_FILE", self.backlog), (story_state, "CURRENT_STORY_FILE", self.pointer),
            (story_state, "ATTEMPT_STATE_FILE", self.attempt),
            (config, "REPO_ROOT", self.root), (config, "AGENT_DIR", self.agent),
            (config, "ARTIFACTS_DIR", self.artifacts), (config, "QA_PLANS_DIR", self.plans),
            (config, "ATTEMPT_STATE_FILE", self.attempt), (config, "USER_DECISIONS_DIR", self.decisions),
            (config, "QA_STATE_FILE", self.artifacts / "QA_STATE.json"),
            (config, "QA_RESULT_FILE", self.artifacts / "QA_RESULT.json"),
            (config, "QA_FAILURES_DIR", self.artifacts / "qa-failures"),
            (config, "CURRENT_STORY_FILE", self.pointer), (config, "BACKLOG_FILE", self.backlog),
            (config, "CLAUDE_RESULT_FILE", self.claude_result),
            (user_decisions, "USER_DECISIONS_DIR", self.decisions),
            (evaluator_runtime, "EVALUATOR_RESULT_FILE", self.evaluator_file),
        ):
            self.stack.enter_context(patch.object(module, attr, value))
        self.addCleanup(self.stack.close)
        self.addCleanup(self.temp.cleanup)

        raw_plan = {
            "schema_version": 1, "story_id": self.story_id, "status": "READY",
            "rationale": "Fixture requires a protected test.", "acceptance_checks": [],
            "invariants": [], "test_levels": ["unit"], "existing_tests_reviewed": [],
            "prepared_test_paths": [self.test_path], "test_specifications": [],
            "pre_implementation_verification": {"status": "NOT_RUN"},
            "coverage_review": "Fixture review.", "external_sources": [],
            "clarifications": [], "post_implementation_review_required": True,
            "post_implementation_review_reason": "Fixture independent review.",
        }
        self.plan = qa_agent.save_plan(self.story, raw_plan, [self.test_path])
        self.store = TaskStore(self.root / "tasks.json")
        self.implementation, _ = self.store.create(
            "fixture implementation result", "fixture-implementation", agent="claude",
            task_type="implementation", story_id=self.story_id,
            contract_sha256="a" * 64,
        )
        self.implementation = self.store.update(
            self.implementation["id"], status="completed", exit_code=0,
            result="fixture implementation runner output",
        )
        self.approve_review()
        self.bridge = Bridge(self.store, "fixture-token", callback_origin="http://127.0.0.1:56789")
        self.fake_decision = "COMPLETE"
        self.fake_calls = 0
        self.fake_error = None
        self.eval_patch = patch.object(evaluator_runtime, "evaluate_story", side_effect=self.fake_evaluate)
        self.eval_patch.start()
        self.addCleanup(self.eval_patch.stop)

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
        self.bridge.callback_origin = f"http://127.0.0.1:{self.callback_server.server_port}"
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.bridge))
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        self.callback_url = self.bridge.callback_origin + "/webhook-waiting/exec-1/wait?signature=fixture"
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.addCleanup(self.callback_server.server_close)
        self.addCleanup(self.callback_server.shutdown)

    def approve_review(self):
        fingerprint = review_contract_sha256(self.plan, self.implementation["id"])
        key = f"post-review:{self.story_id}:{self.implementation['id']}:{fingerprint}"
        self.review_task, _ = self.store.create(
            "fixture approved QA review", key, agent="codex",
            task_type="post_implementation_review", story_id=self.story_id,
            contract_sha256=self.plan["story_contract_sha256"],
        )
        self.store.update(
            self.review_task["id"], status="completed", exit_code=0,
            result={"story_id": self.story_id, "decision": "APPROVE", "reason": "Fixture approved.",
                    "evidence": ["fixture"], "actionable_items": [], "result_source": "new_post_implementation_review"},
        )
        qa_agent.record_review(self.plan, {
            "decision": "APPROVE", "reason": "Fixture approved.",
            "evidence": ["fixture"], "actionable_items": [],
        }, request_id=self.review_task["id"])

    def new_implementation(self):
        self.implementation, _ = self.store.create(
            "fixture implementation result revised", f"fixture-implementation-{len(self.store.tasks)}",
            agent="claude", task_type="implementation", story_id=self.story_id,
            contract_sha256="b" * 64,
        )
        self.implementation = self.store.update(
            self.implementation["id"], status="completed", exit_code=0,
            result="revised fixture implementation output",
        )
        self.approve_review()

    def fake_evaluate(self, _story, _result, _exit_code, _updated, qa_plan, findings, persist=True):
        self.fake_calls += 1
        self.assertFalse(persist)
        self.assertEqual("APPROVE", qa_plan["post_implementation_review"]["decision"])
        self.assertEqual([], findings)
        if self.fake_error:
            raise self.fake_error
        return {
            "decision": self.fake_decision, "reason": "Fixture evaluator verdict.",
            "intent_achieved": self.fake_decision == "COMPLETE", "evidence": ["fixture evidence"],
            "unmet_intent": ["fixture gap"] if self.fake_decision == "RETRY" else [],
            "unmet_acceptance_criteria": [], "unmet_definition_of_done": [],
            "actionable_retry_items": ["fix fixture gap"] if self.fake_decision == "RETRY" else [],
            "raw_decision": self.fake_decision, "normalized": False,
            "evaluator_exit_code": 0, "qa_review_required": False, "qa_review_reason": "",
        }

    def readiness(self):
        return inspect_active_evaluation(self.store.latest_implementation_task(self.story_id), self.store)

    def request(self, callback_url=None, fingerprint=None):
        readiness = self.readiness()
        fp = fingerprint or readiness["evaluation_contract_sha256"]
        payload = {
            "story_id": self.story_id,
            "evaluation_contract_sha256": fp,
            "callback_url": callback_url or self.callback_url,
        }
        req = Request(
            self.url + "/evaluations", data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json",
                     "Idempotency-Key": f"gw2-evaluate-{self.story_id}-{fp}",
                     "Authorization": "Bearer fixture-token"}, method="POST",
        )
        try:
            with urlopen(req, timeout=3) as response:
                return response.status, json.loads(response.read())
        except HTTPError as error:
            return error.code, json.loads(error.read())

    def wait_task(self, task_id):
        for _ in range(250):
            task = self.store.get(task_id)
            if task and task["status"] in {"completed", "failed", "interrupted"}:
                return task
            time.sleep(0.02)
        self.fail("Evaluator task did not finish")

    def wait_callback(self, count=1):
        for _ in range(150):
            if len(self.callbacks) >= count:
                return self.callbacks[-1]
            time.sleep(0.02)
        self.fail("Evaluator callback was not delivered")

    def test_permitted_only_after_current_approved_qa_review(self):
        readiness = self.readiness()
        self.assertTrue(readiness["evaluation_permitted"], readiness)
        self.assertEqual(self.story_id, readiness["story"]["id"])
        self.assertEqual(self.implementation["id"], readiness["implementation_task_id"])
        self.assertEqual(self.review_task["id"], readiness["qa_review_task_id"])

        review = self.store.get(self.review_task["id"])
        self.store.update(review["id"], result={**review["result"], "decision": "RETRY"})
        self.assertFalse(self.readiness()["evaluation_permitted"])

    def test_qa_technical_failure_and_unresolved_decision_block_evaluation(self):
        failure_dir = config.QA_FAILURES_DIR
        failure_dir.mkdir(parents=True, exist_ok=True)
        (failure_dir / f"QA-{self.story_id}.json").write_text(json.dumps({
            "story_id": self.story_id, "status": "TECHNICAL_FAILURE", "category": "fixture_failure",
        }), encoding="utf-8")
        self.assertFalse(self.readiness()["evaluation_permitted"])
        (failure_dir / f"QA-{self.story_id}.json").unlink()

        plan_path = qa_agent.plan_path(self.story_id)
        plan = json.loads(plan_path.read_text(encoding="utf-8"))
        plan["status"] = "NEEDS_USER"
        plan["clarifications"] = ["Fixture decision required."]
        plan["user_decision_ids"] = ["UD-999999"]
        qa_agent.write_json(plan_path, plan)
        self.assertFalse(self.readiness()["evaluation_permitted"])

    def test_blocked_when_implementation_missing_or_failed(self):
        self.store.tasks.pop(self.implementation["id"])
        self.store.idempotency.pop("fixture-implementation", None)
        self.assertFalse(self.readiness()["evaluation_permitted"])
        self.implementation, _ = self.store.create(
            "fixture implementation restored", "fixture-implementation-restored", agent="claude",
            task_type="implementation", story_id=self.story_id, contract_sha256="c" * 64,
        )
        self.implementation = self.store.update(
            self.implementation["id"], status="completed", exit_code=0,
            result="fixture implementation runner output",
        )
        self.approve_review()
        self.store.update(self.implementation["id"], status="failed", exit_code=1)
        readiness = self.readiness()
        self.assertFalse(readiness["evaluation_permitted"])

    def test_stale_story_contract_is_rejected(self):
        fingerprint = self.readiness()["evaluation_contract_sha256"]
        with self.story.open("a", encoding="utf-8") as stream:
            stream.write("\nChanged acceptance after review.\n")
        status, body = self.request(fingerprint=fingerprint)
        self.assertEqual(409, status)
        self.assertIn("error", body)

    def test_stale_implementation_result_and_qa_test_contract_are_rejected(self):
        fingerprint = self.readiness()["evaluation_contract_sha256"]
        self.store.update(self.implementation["id"], result="changed implementation result")
        status, _body = self.request(fingerprint=fingerprint)
        self.assertEqual(409, status)

        current = self.readiness()["evaluation_contract_sha256"]
        (self.root / self.test_path).write_text("changed protected test\n", encoding="utf-8")
        status, _body = self.request(fingerprint=current)
        self.assertEqual(409, status)

    def test_duplicate_submission_reuses_task_and_callback_replacement(self):
        status, first = self.request()
        self.assertEqual(202, status, first)
        task = self.wait_task(first["task_id"])
        self.wait_callback()
        self.assertEqual("COMPLETE", task["result"]["decision"])
        self.assertEqual(1, self.fake_calls)

        callback2 = self.bridge.callback_origin + "/webhook-waiting/exec-2/wait?signature=fixture"
        status, replay = self.request(callback2)
        self.assertEqual(202, status, replay)
        self.assertEqual(first["task_id"], replay["task_id"])
        self.assertEqual("idempotent_replay", replay["submission_disposition"])
        self.wait_callback(2)
        self.assertEqual(1, self.fake_calls)
        self.assertEqual("COMPLETE", self.callbacks[-1]["result"]["decision"])

    def test_evaluator_verdicts_use_runtime_vocabulary(self):
        for decision in ("RETRY", "BLOCKED", "NEEDS_USER"):
            with self.subTest(decision=decision):
                self.new_implementation()
                self.fake_decision = decision
                status, response = self.request()
                self.assertEqual(202, status, response)
                task = self.wait_task(response["task_id"])
                self.wait_callback()
                self.assertEqual(decision, task["result"]["decision"])

    def test_malformed_output_and_technical_failure_are_terminal_errors(self):
        self.fake_error = ValueError("Evaluator returned no usable verdict.")
        _status, response = self.request()
        task = self.wait_task(response["task_id"])
        self.wait_callback()
        self.assertEqual("failed", task["status"])
        self.assertEqual("invalid_evaluator_output", task["error"]["category"])

        self.fake_error = RuntimeError("Codex unavailable")
        self.new_implementation()
        _status, response = self.request()
        task = self.wait_task(response["task_id"])
        self.wait_callback(2)
        self.assertEqual("failed", task["status"])
        self.assertEqual("evaluator_failure", task["error"]["category"])

    def test_interrupted_evaluation_recovers_bound_artifact_without_rerunning(self):
        self.bridge.start_evaluator = lambda _task_id: None
        _status, response = self.request()
        task_id = response["task_id"]
        self.store.update(task_id, status="interrupted")
        artifact = {
            "story_id": self.story_id, "evaluation_task_id": task_id,
            "evaluation_contract_sha256": response["contract_sha256"],
            "decision": "COMPLETE", "reason": "Recovered fixture result.",
            "intent_achieved": True, "evidence": ["fixture"], "unmet_intent": [],
            "unmet_acceptance_criteria": [], "unmet_definition_of_done": [],
            "actionable_retry_items": [],
        }
        self.evaluator_file.write_text(json.dumps(artifact), encoding="utf-8")
        status, recovered = self.request(fingerprint=response["contract_sha256"])
        self.assertEqual(202, status, recovered)
        self.assertEqual(task_id, recovered["task_id"])
        self.assertEqual("completed", recovered["status"])
        self.assertEqual(0, self.fake_calls)

    def test_stale_evaluator_artifact_from_another_story_is_not_recovered(self):
        stale = {"story_id": "STORY-OLD-001", "evaluation_task_id": "old-task",
                 "evaluation_contract_sha256": "f" * 64, "decision": "COMPLETE"}
        self.assertIsNone(persisted_evaluation("new-task", "a" * 64, self.story_id))
        self.evaluator_file.write_text(json.dumps(stale), encoding="utf-8")
        self.assertIsNone(persisted_evaluation("new-task", "a" * 64, self.story_id))

    def test_evaluator_operation_requires_bearer_authentication(self):
        readiness = self.readiness()
        fingerprint = readiness["evaluation_contract_sha256"]
        payload = {
            "story_id": self.story_id,
            "evaluation_contract_sha256": fingerprint,
            "callback_url": self.callback_url,
        }
        request = Request(
            self.url + "/evaluations", data=json.dumps(payload).encode(),
            headers={"Content-Type": "application/json",
                     "Idempotency-Key": f"gw2-evaluate-{self.story_id}-{fingerprint}"},
            method="POST",
        )
        with self.assertRaises(HTTPError) as caught:
            urlopen(request, timeout=3)
        self.assertEqual(401, caught.exception.code)
        self.assertEqual(0, self.fake_calls)


if __name__ == "__main__":
    unittest.main()
