"""Fixture-only implementation runner safety tests; no Claude process is started."""

import hashlib
import json
import threading
import tempfile
import unittest
from contextlib import ExitStack
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

from bridge import Bridge, TaskStore
from agent.runtime.core import story_state
from agent.runtime.evaluation import claude_prompt, implementation_contract
from agent.runtime.qa import qa_agent
from agent.runtime.support import config
from agent.runtime.runners.claude_runner import ClaudeAttempt


class ImplementationExecutionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.stories = self.root / "agent/stories"
        self.stories.mkdir(parents=True)
        self.artifacts = self.root / "agent/runtime/artifacts"
        self.artifacts.mkdir(parents=True)
        self.plans = self.root / "agent/qa-plans"
        self.plans.mkdir(parents=True)
        self.decisions = self.root / "agent/user-decisions"
        self.decisions.mkdir(parents=True)
        self.story = self.stories / "STORY-WEB-021-fixture.md"
        self.story.write_text(
            "# Fixture story\n\n## Story ID\nSTORY-WEB-021\n\n"
            "## Title\nFixture implementation\n\n## Status\nTODO\n\n"
            "## Dependencies\nNone.\n\n## References\n- `docs/DOMAIN_SPEC.md`\n\n"
            "## Definition of Done\nObservable behavior is implemented.\n",
            encoding="utf-8",
        )
        self.backlog = self.stories / "BACKLOG.md"
        self.backlog.write_text(
            "# Backlog\n\n## Active\n- `STORY-WEB-021-fixture.md`\n\n"
            "## To Do\n\n## Blocked\n\n## Done\n\n## Archived\n",
            encoding="utf-8",
        )
        self.pointer = self.root / "agent/CURRENT_STORY.md"
        self.pointer.write_text("agent/stories/STORY-WEB-021-fixture.md\n", encoding="utf-8")
        self.attempt = self.artifacts / "ATTEMPT_STATE.json"
        for relative, content in (
            ("CLAUDE.md", "fixture instructions\n"),
            ("docs/CODING_GUIDELINES.md", "fixture guidelines\n"),
            ("docs/DOMAIN_SPEC.md", "fixture domain rules\n"),
        ):
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")

        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        for module, name, value in (
            (story_state, "REPO_ROOT", self.root),
            (story_state, "STORIES_DIR", self.stories),
            (story_state, "BACKLOG_FILE", self.backlog),
            (story_state, "CURRENT_STORY_FILE", self.pointer),
            (story_state, "ATTEMPT_STATE_FILE", self.attempt),
            (story_state, "ARCHIVE_DIR", self.stories / "archive"),
            (config, "REPO_ROOT", self.root),
            (config, "ARTIFACTS_DIR", self.artifacts),
            (config, "ATTEMPT_STATE_FILE", self.attempt),
            (config, "QA_PLANS_DIR", self.plans),
            (config, "QA_STATE_FILE", self.artifacts / "QA_STATE.json"),
            (config, "USER_DECISIONS_DIR", self.decisions),
            (qa_agent, "plan_path", lambda story_id: self.plans / f"QA-{story_id}.json"),
            (claude_prompt, "REPO_ROOT", self.root),
        ):
            self.stack.enter_context(patch.object(module, name, value))
        self.test_path = self.root / "frontend/src/ecto/__tests__/Fixture.spec.ts"
        self.test_path.parent.mkdir(parents=True)
        self.test_path.write_text("original QA test\n", encoding="utf-8")
        self.test_snapshot = self.artifacts / "qa-tests/frontend/src/ecto/__tests__/Fixture.spec.ts"
        self.test_snapshot.parent.mkdir(parents=True)
        self.test_snapshot.write_bytes(self.test_path.read_bytes())
        self.plan = {
            "schema_version": 1,
            "story_id": "STORY-WEB-021",
            "story_contract_sha256": qa_agent.story_contract_sha256(self.story.read_text(encoding="utf-8")),
            "status": "READY",
            "rationale": "The fixture changes observable behavior.",
            "acceptance_checks": [],
            "invariants": [],
            "test_levels": ["unit"],
            "existing_tests_reviewed": [],
            "prepared_test_paths": ["frontend/src/ecto/__tests__/Fixture.spec.ts"],
            "protected_test_hashes": {
                "frontend/src/ecto/__tests__/Fixture.spec.ts": hashlib.sha256(self.test_path.read_bytes()).hexdigest(),
            },
            "test_specifications": [],
            "pre_implementation_verification": {"status": "NOT_RUN"},
            "coverage_review": "The observable result is covered.",
            "external_sources": [],
            "clarifications": [],
            "user_decision_ids": [],
            "post_implementation_review_required": False,
            "post_implementation_review_reason": "",
        }
        self.plan_file = self.plans / "QA-STORY-WEB-021.json"
        self.plan_file.write_text(json.dumps(self.plan), encoding="utf-8")
        self.store = TaskStore(self.root / "tasks.json")
        self.bridge = Bridge(self.store, "fixture-token", command_prefix=("fake-claude",))
        self.contract = implementation_contract.inspect_active_story()
        self.assertTrue(self.contract["implementation_permitted"])

    def submit_fixture_task(self, key="WEB021-contract-fixture"):
        task, created = self.store.create(
            self.contract["prompt"], key, agent="claude", task_type="implementation",
            story_id="STORY-WEB-021", contract_sha256=self.contract["contract_sha256"],
        )
        self.assertTrue(created)
        return task

    def test_permitted_task_uses_fresh_server_prompt_and_completes(self):
        task = self.submit_fixture_task()
        with patch("agent.runtime.runners.claude_runner.run_claude_attempt",
                   return_value=ClaudeAttempt(0, False, "implemented")) as run:
            self.bridge._run(task["id"])
        saved = self.store.get(task["id"])
        self.assertEqual(saved["status"], "completed")
        self.assertEqual(saved["result"], "implemented")
        run.assert_called_once_with(self.contract["prompt"], label="Implementation STORY-WEB-021")

    def test_stale_contract_is_blocked_before_claude_starts(self):
        task = self.submit_fixture_task()
        self.story.write_text(self.story.read_text(encoding="utf-8") + "\n## Constraints\nChanged after submission.\n", encoding="utf-8")
        with patch("agent.runtime.runners.claude_runner.run_claude_attempt") as run:
            self.bridge._run(task["id"])
        self.assertEqual(self.store.get(task["id"])["status"], "failed")
        self.assertIn("changed or is no longer permitted", self.store.get(task["id"])["error"]["message"])
        run.assert_not_called()

    def test_modified_qa_test_is_restored_and_task_fails(self):
        task = self.submit_fixture_task()

        def fake_claude(*args, **kwargs):
            self.test_path.write_text("tampered QA test\n", encoding="utf-8")
            return ClaudeAttempt(0, False, "finished")

        with patch("agent.runtime.runners.claude_runner.run_claude_attempt", side_effect=fake_claude):
            self.bridge._run(task["id"])
        self.assertEqual(self.test_path.read_text(encoding="utf-8"), "original QA test\n")
        saved = self.store.get(task["id"])
        self.assertEqual(saved["status"], "failed")
        self.assertEqual(saved["error"]["category"], "protected_qa_artifact_modified")

    def test_implementation_cannot_finalize_or_deactivate_story(self):
        task = self.submit_fixture_task()
        unrelated = self.root / "maintainer-notes.txt"
        unrelated.write_text("pre-existing maintainer edit\n", encoding="utf-8")
        original_backlog = self.backlog.read_bytes()
        original_pointer = self.pointer.read_bytes()

        def fake_claude(*args, **kwargs):
            content = self.story.read_text(encoding="utf-8")
            content = story_state._replace_section_body(content, "Status", "DONE")
            content += "\n## Result\nImplementation result retained by the bridge.\n"
            self.story.write_text(content, encoding="utf-8")
            self.backlog.write_text("# Agent moved the active story to Done\n", encoding="utf-8")
            self.pointer.write_text("", encoding="utf-8")
            return ClaudeAttempt(0, False, "implementation output")

        with patch("agent.runtime.runners.claude_runner.run_claude_attempt", side_effect=fake_claude):
            self.bridge._run(task["id"])

        saved = self.store.get(task["id"])
        self.assertEqual("failed", saved["status"])
        self.assertEqual("implementation_lifecycle_modified", saved["error"]["category"])
        self.assertEqual("TODO", story_state.classify_story_status(
            story_state.extract_status_section(self.story.read_text(encoding="utf-8"))
        ))
        self.assertIn("Implementation result retained", self.story.read_text(encoding="utf-8"))
        self.assertEqual(original_backlog, self.backlog.read_bytes())
        self.assertEqual(original_pointer, self.pointer.read_bytes())
        self.assertEqual("pre-existing maintainer edit\n", unrelated.read_text(encoding="utf-8"))

    def test_nonzero_claude_exit_is_persisted_as_failure(self):
        task = self.submit_fixture_task()
        with patch("agent.runtime.runners.claude_runner.run_claude_attempt",
                   return_value=ClaudeAttempt(2, False, "partial output")):
            self.bridge._run(task["id"])
        saved = self.store.get(task["id"])
        self.assertEqual(saved["status"], "failed")
        self.assertEqual(saved["exit_code"], 2)
        self.assertEqual(saved["result"], "partial output")

    def test_bridge_restart_marks_implementation_uncertain_and_replay_does_not_relaunch(self):
        task = self.submit_fixture_task("stable-request")
        self.store.update(task["id"], status="running")
        recovered_store = TaskStore(self.root / "tasks.json")
        recovered = recovered_store.get(task["id"])
        self.assertEqual(recovered["status"], "interrupted")
        self.assertIn("will not be relaunched automatically", recovered["error"])
        replay, created = recovered_store.create(
            self.contract["prompt"], "stable-request",
            callback_url="http://localhost:5678/webhook-waiting/retry?signature=test",
            agent="claude", task_type="implementation", story_id="STORY-WEB-021",
            contract_sha256=self.contract["contract_sha256"],
        )
        self.assertFalse(created)
        self.assertEqual(replay["id"], task["id"])
        self.assertEqual(replay["status"], "interrupted")

    def test_completed_implementation_replays_result_to_replacement_callback(self):
        received = []

        class Receiver(BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers.get("Content-Length", "0"))
                received.append(json.loads(self.rfile.read(length)))
                self.send_response(200)
                self.end_headers()

            def log_message(self, *_args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Receiver)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        origin = f"http://127.0.0.1:{server.server_port}"
        old_callback = origin + "/webhook-waiting/old/wait?signature=test"
        new_callback = origin + "/webhook-waiting/recovered/wait?signature=test"
        key = "callback-recovery-key"
        task, created = self.store.create(
            self.contract["prompt"], key, old_callback, agent="claude",
            task_type="implementation", story_id="STORY-WEB-021",
            contract_sha256=self.contract["contract_sha256"],
        )
        self.assertTrue(created)
        self.store.update(task["id"], status="completed", exit_code=0,
                          result="persisted implementation result", error=None,
                          callback_delivery_status="delivery_unconfirmed")
        replay, created = self.store.create(
            self.contract["prompt"], key, new_callback, agent="claude",
            task_type="implementation", story_id="STORY-WEB-021",
            contract_sha256=self.contract["contract_sha256"],
        )
        self.assertFalse(created)
        self.assertEqual(replay["id"], task["id"])
        with patch("agent.runtime.runners.claude_runner.run_claude_attempt") as run:
            self.bridge.deliver_callback(task["id"])
        run.assert_not_called()
        self.assertEqual(len(received), 1)
        self.assertEqual(received[0]["task_id"], task["id"])
        self.assertEqual(received[0]["result"], "persisted implementation result")
        self.assertEqual(self.store.get(task["id"])["callback_delivery_status"], "delivered")


if __name__ == "__main__":
    unittest.main()
