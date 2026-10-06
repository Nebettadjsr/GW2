"""Read-only implementation readiness tests against temporary repositories."""

import hashlib
import json
import tempfile
import threading
import unittest
from contextlib import ExitStack
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from unittest.mock import patch

from bridge import Bridge, TaskStore, make_handler
from agent.runtime.core import story_state
from agent.runtime.evaluation import claude_prompt, implementation_contract
from agent.runtime.human import user_decisions
from agent.runtime.qa import qa_agent
from agent.runtime.support import config


class ImplementationContractTests(unittest.TestCase):
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
        self.backlog = self.stories / "BACKLOG.md"
        self.pointer = self.agent / "CURRENT_STORY.md"
        self.attempt = self.artifacts / "ATTEMPT_STATE.json"
        self.story = self.stories / "STORY-WEB-021-fixture.md"
        self.stack = ExitStack()
        replacements = (
            (story_state, "REPO_ROOT", self.root),
            (story_state, "STORIES_DIR", self.stories),
            (story_state, "BACKLOG_FILE", self.backlog),
            (story_state, "CURRENT_STORY_FILE", self.pointer),
            (story_state, "ATTEMPT_STATE_FILE", self.attempt),
            (story_state, "ARCHIVE_DIR", self.stories / "archive"),
            (qa_agent, "plan_path", lambda story_id: self.plans / f"QA-{story_id}.json"),
            (user_decisions, "USER_DECISIONS_DIR", self.decisions),
            (config, "REPO_ROOT", self.root),
            (config, "ARTIFACTS_DIR", self.artifacts),
            (config, "ATTEMPT_STATE_FILE", self.attempt),
            (config, "QA_PLANS_DIR", self.plans),
            (config, "QA_STATE_FILE", self.artifacts / "QA_STATE.json"),
            (config, "USER_DECISIONS_DIR", self.decisions),
            (claude_prompt, "REPO_ROOT", self.root),
        )
        for module, name, value in replacements:
            self.stack.enter_context(patch.object(module, name, value))
        self.addCleanup(self.stack.close)
        self.addCleanup(self.temp.cleanup)
        (self.root / "docs").mkdir()
        (self.root / "CLAUDE.md").write_text("fixture project instructions\n", encoding="utf-8")
        (self.root / "docs/CODING_GUIDELINES.md").write_text("fixture coding guidelines\n", encoding="utf-8")
        (self.root / "docs/DOMAIN_SPEC.md").write_text("fixture domain rules\n", encoding="utf-8")
        self.write_story()
        self.activate_fixture()

    def write_story(self, extra=""):
        self.story.write_text(
            "# STORY-WEB-021 Fixture Story\n\n"
            "## Story ID\nSTORY-WEB-021\n\n"
            "## Title\nFixture implementation\n\n"
            "## Status\nTODO\n\n"
            "## Dependencies\nNone.\n\n"
            "## References\n- `docs/DOMAIN_SPEC.md`\n\n"
            "## Definition of Done\nRequired observable behavior.\n\n" + extra,
            encoding="utf-8",
        )

    def activate_fixture(self):
        self.pointer.write_text("agent/stories/" + self.story.name + "\n", encoding="utf-8")
        self.backlog.write_text(
            "# Backlog\n\n## Active\n- `" + self.story.name + "`\n\n"
            "## To Do\n\n## Blocked\n\n## Done\n\n## Archived\n",
            encoding="utf-8",
        )

    def activate_fixture_empty_backlog(self):
        self.backlog.write_text(
            "# Backlog\n\n## Active\n\n## To Do\n\n## Blocked\n\n## Done\n\n## Archived\n",
            encoding="utf-8",
        )

    def write_plan(self, status="READY", **updates):
        plan = {
            "schema_version": 1,
            "story_id": "STORY-WEB-021",
            "story_contract_sha256": qa_agent.story_contract_sha256(self.story.read_text(encoding="utf-8")),
            "status": status,
            "rationale": "The fixture needs a story-specific observable check." if status != "NEEDS_USER" else "Expected behavior is ambiguous.",
            "acceptance_checks": [],
            "invariants": [],
            "test_levels": ["unit"],
            "existing_tests_reviewed": [],
            "prepared_test_paths": [],
            "protected_test_hashes": {},
            "test_specifications": ["Assert the stated observable behavior."],
            "pre_implementation_verification": {"status": "NOT_RUN"},
            "coverage_review": "Reviewed fixture scope.",
            "external_sources": [],
            "clarifications": ["Which result is expected?"] if status == "NEEDS_USER" else [],
            "user_decision_ids": [],
            "post_implementation_review_required": False,
            "post_implementation_review_reason": "",
        }
        plan.update(updates)
        (self.plans / "QA-STORY-WEB-021.json").write_text(json.dumps(plan), encoding="utf-8")
        return plan

    def result(self):
        return implementation_contract.inspect_active_story()

    def test_no_active_story_is_reported_without_writes(self):
        self.pointer.write_text("", encoding="utf-8")
        self.activate_fixture_empty_backlog()
        before = self.file_snapshot()
        result = self.result()
        self.assertEqual(result["preparation_status"], "no_active_story")
        self.assertFalse(result["implementation_permitted"])
        self.assertEqual(before, self.file_snapshot())

    def test_active_backlog_entry_without_pointer_is_inconsistent(self):
        self.pointer.write_text("", encoding="utf-8")
        result = self.result()
        self.assertEqual(result["preparation_status"], "invalid_active_story")
        self.assertIn("CURRENT_STORY.md is empty", result["outstanding_prerequisites"][0])

    def test_active_story_without_plan_requires_qa(self):
        result = self.result()
        self.assertEqual(result["preparation_status"], "preparation_required")
        self.assertFalse(result["implementation_permitted"])

    def test_interrupted_qa_state_requires_resuming_preparation(self):
        config.QA_STATE_FILE.write_text(
            json.dumps({"schema_version": 1, "story_id": "STORY-WEB-021", "attempts": 1}),
            encoding="utf-8",
        )
        result = self.result()
        self.assertEqual(result["qa"]["status"], "incomplete")
        self.assertIn("resume QA preparation", result["outstanding_prerequisites"][0])

    def test_persisted_orchestrator_attempt_requires_recovery(self):
        self.write_plan("READY")
        self.attempt.write_text("{}", encoding="utf-8")
        result = self.result()
        self.assertEqual(result["preparation_status"], "recovery_required")
        self.assertFalse(result["implementation_permitted"])

    def test_ready_plan_builds_implementation_contract(self):
        self.write_plan("READY")
        result = self.result()
        self.assertTrue(result["implementation_permitted"])
        self.assertEqual(result["qa"]["status"], "READY")
        self.assertIn("INDEPENDENT QA PLAN", result["prompt"])
        self.assertIn("docs/CODING_GUIDELINES.md", result["prompt"])
        self.assertEqual(result["story"]["id"], "STORY-WEB-021")

    def test_no_tests_needed_is_a_valid_explicit_qa_outcome(self):
        self.write_plan("NO_TESTS_NEEDED", rationale="Documentation-only change; no behavior or contract changes.", test_specifications=[])
        result = self.result()
        self.assertTrue(result["implementation_permitted"])
        self.assertEqual(result["preparation_status"], "no_tests_needed")

    def test_qa_product_owner_question_blocks_implementation(self):
        self.write_plan("NEEDS_USER", clarifications=["Which expected value is authoritative?"])
        result = self.result()
        self.assertEqual(result["preparation_status"], "needs_user")
        self.assertFalse(result["implementation_permitted"])

    def test_resolved_product_owner_decision_is_in_contract(self):
        decision = self.decisions / "UD-041-fixture.md"
        decision.write_text(
            "# UD-041 - Fixture choice\n\n## Status\nRESOLVED\n\n"
            "## User Decision\nUse the documented result.\n",
            encoding="utf-8",
        )
        self.write_story(extra="## Product Rule\nFollow UD-041.\n")
        self.write_plan("READY")
        result = self.result()
        self.assertTrue(result["implementation_permitted"])
        self.assertIn("UD-041", result["prompt"])
        self.assertIn("Use the documented result.", result["prompt"])

    def test_unresolved_story_decision_blocks_implementation(self):
        (self.decisions / "UD-041-fixture.md").write_text(
            "# UD-041 - Fixture choice\n\n## Status\nOPEN\n\n## User Decision\n\n",
            encoding="utf-8",
        )
        self.write_story(extra="## Product Rule\nFollow UD-041.\n")
        self.write_plan("READY")
        result = self.result()
        self.assertEqual(result["preparation_status"], "needs_user")
        self.assertIn("UD-041", result["outstanding_prerequisites"][0])

    def test_invalid_and_stale_qa_artifacts_fail_closed(self):
        plan_path = self.plans / "QA-STORY-WEB-021.json"
        plan_path.write_text("{broken", encoding="utf-8")
        self.assertEqual(self.result()["preparation_status"], "invalid_qa_artifact")
        self.write_plan("READY")
        self.write_story(extra="## Constraints\nA changed requirement.\n")
        result = self.result()
        self.assertEqual(result["preparation_status"], "invalid_qa_artifact")
        self.assertIn("fingerprint", result["outstanding_prerequisites"][0])

    def test_protected_test_integrity_failure_blocks(self):
        relative = "agent/runtime/tests/test_fixture.py"
        test_file = self.root / relative
        test_file.parent.mkdir(parents=True)
        test_file.write_text("assert True\n", encoding="utf-8")
        digest = hashlib.sha256(test_file.read_bytes()).hexdigest()
        snapshot = self.artifacts / "qa-tests" / relative
        snapshot.parent.mkdir(parents=True)
        snapshot.write_bytes(test_file.read_bytes())
        self.write_plan("READY", prepared_test_paths=[relative], protected_test_hashes={relative: digest})
        test_file.write_text("assert False\n", encoding="utf-8")
        result = self.result()
        self.assertEqual(result["preparation_status"], "invalid_qa_artifact")
        self.assertIn("changed in the working tree", result["outstanding_prerequisites"][0])

    def test_missing_protected_snapshot_blocks(self):
        relative = "agent/runtime/tests/test_fixture.py"
        test_file = self.root / relative
        test_file.parent.mkdir(parents=True)
        test_file.write_text("assert True\n", encoding="utf-8")
        digest = hashlib.sha256(test_file.read_bytes()).hexdigest()
        self.write_plan("READY", prepared_test_paths=[relative], protected_test_hashes={relative: digest})
        result = self.result()
        self.assertEqual(result["preparation_status"], "invalid_qa_artifact")
        self.assertIn("snapshot", result["outstanding_prerequisites"][0])

    def test_active_backlog_mismatch_is_diagnostic(self):
        self.backlog.write_text(self.backlog.read_text(encoding="utf-8").replace(self.story.name, "STORY-OTHER.md"), encoding="utf-8")
        result = self.result()
        self.assertEqual(result["preparation_status"], "invalid_active_story")
        self.assertIn("do not match", result["outstanding_prerequisites"][0])

    def test_readiness_endpoint_is_read_only_and_requires_auth(self):
        self.write_plan("READY")
        bridge = Bridge(TaskStore(self.root / "tasks.json"), "fixture-token")
        server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(bridge))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        url = f"http://127.0.0.1:{server.server_port}/stories/active/implementation-contract"
        before = self.file_snapshot()
        request = Request(url, headers={"Authorization": "Bearer fixture-token"})
        with urlopen(request, timeout=3) as response:
            payload = json.loads(response.read())
            self.assertEqual(response.status, 200)
        self.assertTrue(payload["implementation_permitted"])
        self.assertEqual(before, self.file_snapshot())
        with self.assertRaises(HTTPError) as error:
            urlopen(url, timeout=3)
        self.assertEqual(error.exception.code, 401)

    def test_implementation_submission_revalidates_and_uses_server_contract(self):
        self.write_plan("READY")
        contract = self.result()
        bridge = Bridge(TaskStore(self.root / "tasks.json"), "fixture-token")
        server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(bridge))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        body = {
            "agent": "claude",
            "task_type": "implementation",
            "prompt": "client prompt is ignored in favor of the validated contract",
            "story_id": "STORY-WEB-021",
            "contract_sha256": contract["contract_sha256"],
            "callback_url": "http://localhost:5678/webhook-waiting/execution/wait?signature=test",
        }
        request = Request(
            f"http://127.0.0.1:{server.server_port}/implementation-tasks",
            data=json.dumps(body).encode(),
            headers={"Authorization": "Bearer fixture-token", "Idempotency-Key": "fixture-implementation"},
            method="POST",
        )
        with patch.object(bridge, "start") as start:
            with urlopen(request, timeout=3) as response:
                submitted = json.loads(response.read())
            self.assertEqual(response.status, 202)
            start.assert_called_once()
        stored = bridge.store.get(submitted["task_id"])
        self.assertEqual(stored["prompt"], contract["prompt"])
        self.assertNotIn("client prompt is ignored", stored["prompt"])
        self.write_story(extra="## Result\nImplementation finished.\n")
        self.story.write_text(self.story.read_text(encoding="utf-8").replace("## Status\nTODO", "## Status\nDONE"), encoding="utf-8")
        body["callback_url"] = "http://localhost:5678/webhook-waiting/replacement/wait?signature=test"
        retry = Request(
            f"http://127.0.0.1:{server.server_port}/implementation-tasks",
            data=json.dumps(body).encode(),
            headers={"Authorization": "Bearer fixture-token", "Idempotency-Key": "fixture-implementation"},
            method="POST",
        )
        with patch.object(bridge, "start") as start:
            with urlopen(retry, timeout=3) as response:
                recovered = json.loads(response.read())
            self.assertEqual(response.status, 202)
            start.assert_not_called()
        self.assertEqual(recovered["task_id"], submitted["task_id"])
        self.assertEqual(bridge.store.get(submitted["task_id"])["callback_url"], body["callback_url"])

    def test_implementation_submission_rejects_stale_contract_without_task(self):
        self.write_plan("READY")
        contract = self.result()
        self.write_story(extra="## Constraints\nChanged after preparation.\n")
        bridge = Bridge(TaskStore(self.root / "tasks.json"), "fixture-token")
        server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(bridge))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        body = {
            "agent": "claude", "task_type": "implementation", "prompt": contract["prompt"],
            "story_id": "STORY-WEB-021", "contract_sha256": contract["contract_sha256"],
            "callback_url": "http://localhost:5678/webhook-waiting/execution/wait?signature=test",
        }
        request = Request(
            f"http://127.0.0.1:{server.server_port}/implementation-tasks",
            data=json.dumps(body).encode(),
            headers={"Authorization": "Bearer fixture-token", "Idempotency-Key": "stale-implementation"},
            method="POST",
        )
        with patch.object(bridge, "start") as start, self.assertRaises(HTTPError) as error:
            urlopen(request, timeout=3)
        self.assertEqual(error.exception.code, 409)
        self.assertEqual(bridge.store.tasks, {})
        start.assert_not_called()

    def test_generic_task_endpoint_cannot_accept_explicit_implementation_type(self):
        bridge = Bridge(TaskStore(self.root / "tasks.json"), "fixture-token")
        server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(bridge))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        request = Request(
            f"http://127.0.0.1:{server.server_port}/tasks",
            data=json.dumps({"task_type": "implementation", "agent": "claude", "prompt": "work"}).encode(),
            headers={"Authorization": "Bearer fixture-token", "Idempotency-Key": "bypass-attempt"},
            method="POST",
        )
        with patch.object(bridge, "start") as start, self.assertRaises(HTTPError) as error:
            urlopen(request, timeout=3)
        self.assertEqual(error.exception.code, 400)
        self.assertEqual(bridge.store.tasks, {})
        start.assert_not_called()

    def file_snapshot(self):
        return {path.relative_to(self.root).as_posix(): path.read_bytes()
                for path in self.root.rglob("*") if path.is_file()}


if __name__ == "__main__":
    unittest.main()
