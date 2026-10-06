"""Authenticated bridge activation tests against temporary repository fixtures."""

import json
import multiprocessing
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from unittest.mock import patch

from bridge import Bridge, TaskStore, make_handler
from agent.runtime.core import story_archive, story_state
from agent.runtime.human import user_decisions


def _hold_activation_lock(lock_path, ready, release):
    story_state.ACTIVATION_LOCK_FILE = Path(lock_path)
    with story_state.story_activation_lock(timeout=2):
        ready.set()
        release.wait(5)


class StoryActivationEndpointTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name).resolve()
        self.agent = self.root / "agent"
        self.stories = self.agent / "stories"
        self.stories.mkdir(parents=True)
        self.artifacts = self.agent / "runtime" / "artifacts"
        self.artifacts.mkdir(parents=True)
        self.backlog = self.stories / "BACKLOG.md"
        self.pointer = self.agent / "CURRENT_STORY.md"
        self.attempt = self.artifacts / "ATTEMPT_STATE.json"
        self.lock_file = self.artifacts / "STORY_ACTIVATION.lock"
        self.archive = self.stories / "archive"
        self.decisions = self.agent / "user-decisions"
        self.decisions.mkdir()
        self.stack = ExitStack()
        for module, name, value in (
            (story_state, "REPO_ROOT", self.root),
            (story_state, "STORIES_DIR", self.stories),
            (story_state, "BACKLOG_FILE", self.backlog),
            (story_state, "CURRENT_STORY_FILE", self.pointer),
            (story_state, "ATTEMPT_STATE_FILE", self.attempt),
            (story_state, "ARTIFACTS_DIR", self.artifacts),
            (story_state, "ACTIVATION_LOCK_FILE", self.lock_file),
            (story_state, "ARCHIVE_DIR", self.archive),
            (story_archive, "ARCHIVE_DIR", self.archive),
            (user_decisions, "USER_DECISIONS_DIR", self.decisions),
        ):
            self.stack.enter_context(patch.object(module, name, value))
        self.addCleanup(self.stack.close)
        self.addCleanup(self.temp.cleanup)

        self.bridge = Bridge(TaskStore(self.root / "tasks.json"), "fixture-token")
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.bridge))
        self.server_thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.server_thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.url = f"http://127.0.0.1:{self.server.server_port}"

    def write_story(self, filename, story_id, status="TODO", dependencies="None.", title=None):
        (self.stories / filename).write_text(
            f"## Story ID\n{story_id}\n\n## Title\n{title or story_id}\n\n"
            f"## Status\n{status}\n\n## Dependencies\n{dependencies}\n\n"
            "## Blockers\nNone.\n\n## Description\nFixture story content.\n",
            encoding="utf-8",
        )

    def write_backlog(self, todo, active=None, blocked=None):
        def bullets(names):
            return "\n".join(f"- `{name}`" for name in (names or []))

        self.backlog.write_text(
            "# Backlog\n\n"
            f"## Active\n\n{bullets(active)}\n\n"
            f"## To Do\n\n{bullets(todo)}\n\n"
            f"## Blocked\n\n{bullets(blocked)}\n\n"
            "## Done\n\n_(none)_\n\n## Archived\n\n_(none)_\n",
            encoding="utf-8",
        )

    def activate(self, story_id, filename, token="fixture-token"):
        headers = {"Content-Type": "application/json"}
        if token is not None:
            headers["Authorization"] = f"Bearer {token}"
        request = Request(
            self.url + "/stories/activate",
            data=json.dumps({"story_id": story_id, "filename": filename}).encode(),
            headers=headers,
            method="POST",
        )
        try:
            with urlopen(request, timeout=5) as response:
                return response.status, json.loads(response.read())
        except HTTPError as error:
            return error.code, json.loads(error.read())

    def setup_two_stories(self):
        self.write_story("STORY-A-001-a.md", "STORY-A-001", title="A story")
        self.write_story("STORY-B-001-b.md", "STORY-B-001", title="B story")
        self.write_backlog(["STORY-A-001-a.md", "STORY-B-001-b.md"])

    def test_successful_and_duplicate_activation_are_idempotent(self):
        self.setup_two_stories()
        status, body = self.activate("STORY-A-001", "STORY-A-001-a.md")
        self.assertEqual(status, 200)
        self.assertEqual(body["activation_status"], "activated")
        self.assertEqual(body["operation_source"], "local_bridge_http")
        self.assertEqual(body["story"]["content"].count("Fixture story content"), 1)
        backlog_after_first = self.backlog.read_bytes()
        pointer_after_first = self.pointer.read_bytes()

        status, duplicate = self.activate("STORY-A-001", "STORY-A-001-a.md")
        self.assertEqual(status, 200)
        self.assertEqual(duplicate["activation_status"], "already_active")
        self.assertEqual(self.backlog.read_bytes(), backlog_after_first)
        self.assertEqual(self.pointer.read_bytes(), pointer_after_first)
        self.assertEqual(story_state.parse_backlog_section(self.backlog.read_text(), "Active"), ["STORY-A-001-a.md"])

    def test_competing_requests_activate_at_most_one_story(self):
        self.setup_two_stories()
        gate = threading.Barrier(2)

        def run(story_id, filename):
            gate.wait()
            return self.activate(story_id, filename)[0]

        with ThreadPoolExecutor(max_workers=2) as pool:
            statuses = list(pool.map(run, ["STORY-A-001", "STORY-B-001"],
                                     ["STORY-A-001-a.md", "STORY-B-001-b.md"]))
        self.assertCountEqual(statuses, [200, 409])
        self.assertEqual(len(story_state.parse_backlog_section(self.backlog.read_text(), "Active")), 1)

    def test_activation_lock_excludes_a_separate_process(self):
        context = multiprocessing.get_context("spawn")
        ready = context.Event()
        release = context.Event()
        process = context.Process(
            target=_hold_activation_lock,
            args=(str(self.lock_file), ready, release),
        )
        process.start()
        try:
            self.assertTrue(ready.wait(5), "child process did not acquire activation lock")
            with self.assertRaisesRegex(RuntimeError, "locked by another process"):
                with story_state.story_activation_lock(timeout=0.15):
                    pass
        finally:
            release.set()
            process.join(5)
            if process.is_alive():
                process.terminate()
                process.join(2)
        self.assertEqual(process.exitcode, 0)

    def test_stale_selection_of_non_next_story_is_rejected(self):
        self.setup_two_stories()
        status, body = self.activate("STORY-B-001", "STORY-B-001-b.md")
        self.assertEqual(status, 409)
        self.assertIn("Stale story selection", body["error"])
        self.assertFalse(self.pointer.exists())

    def test_missing_dependency_is_rejected(self):
        self.write_story("STORY-DEP-001-dep.md", "STORY-DEP-001")
        self.write_story("STORY-A-001-a.md", "STORY-A-001", dependencies="STORY-DEP-001")
        self.write_backlog(["STORY-A-001-a.md"])
        status, body = self.activate("STORY-A-001", "STORY-A-001-a.md")
        self.assertEqual(status, 409)
        self.assertIn("no longer the next eligible story", body["error"])
        self.assertFalse(self.pointer.exists())

    def test_existing_active_story_blocks_a_different_activation(self):
        self.setup_two_stories()
        self.backlog.write_text(
            self.backlog.read_text().replace("## Active\n\n\n", "## Active\n\n- `STORY-A-001-a.md`\n"),
            encoding="utf-8",
        )
        self.pointer.write_text("agent/stories/STORY-A-001-a.md\n", encoding="utf-8")
        status, body = self.activate("STORY-B-001", "STORY-B-001-b.md")
        self.assertEqual(status, 409)
        self.assertIn("already active", body["error"])

    def test_unfinished_orchestrator_attempt_blocks_new_activation_but_not_duplicate(self):
        self.setup_two_stories()
        status, _ = self.activate("STORY-A-001", "STORY-A-001-a.md")
        self.assertEqual(status, 200)
        self.attempt.write_text('{"version":1,"story":"agent/stories/STORY-A-001-a.md","phase":"CODING"}', encoding="utf-8")

        status, duplicate = self.activate("STORY-A-001", "STORY-A-001-a.md")
        self.assertEqual(status, 200)
        self.assertEqual(duplicate["activation_status"], "already_active")

        status, body = self.activate("STORY-B-001", "STORY-B-001-b.md")
        self.assertEqual(status, 409)
        self.assertIn("ATTEMPT_STATE.json", body["error"])

    def test_interruption_between_pointer_and_backlog_is_reconciled_on_retry(self):
        self.setup_two_stories()
        real_write = story_state._atomic_write_text

        def interrupt_backlog(path, content):
            if path == self.backlog:
                raise OSError("simulated interruption")
            return real_write(path, content)

        with patch.object(story_state, "_atomic_write_text", side_effect=interrupt_backlog):
            status, body = self.activate("STORY-A-001", "STORY-A-001-a.md")
        self.assertEqual(status, 409)
        self.assertIn("Retry the same activation to reconcile it", body["error"])
        self.assertEqual(self.pointer.read_text(encoding="utf-8").strip(), "agent/stories/STORY-A-001-a.md")
        self.assertEqual(story_state.parse_backlog_section(self.backlog.read_text(), "Active"), [])

        status, body = self.activate("STORY-A-001", "STORY-A-001-a.md")
        self.assertEqual(status, 200)
        self.assertEqual(body["activation_status"], "recovered")
        self.assertEqual(story_state.parse_backlog_section(self.backlog.read_text(), "Active"), ["STORY-A-001-a.md"])
        self.assertEqual(story_state.validate_backlog_consistency(), [])

    def test_rejects_inconsistent_state_that_cannot_be_inferred(self):
        self.setup_two_stories()
        self.backlog.write_text(
            self.backlog.read_text().replace("## Active\n\n\n", "## Active\n\n- `STORY-B-001-b.md`\n"),
            encoding="utf-8",
        )
        self.pointer.write_text("agent/stories/STORY-A-001-a.md\n", encoding="utf-8")
        status, body = self.activate("STORY-A-001", "STORY-A-001-a.md")
        self.assertEqual(status, 409)
        self.assertIn("Reconcile manually", body["error"])

    def test_unrelated_files_and_backlog_sections_are_preserved(self):
        self.setup_two_stories()
        unrelated = self.stories / "NOT-IN-QUEUE.md"
        unrelated.write_text("leave this file as-is\n", encoding="utf-8")
        blocked_before = story_state.parse_backlog_section(self.backlog.read_text(), "Blocked")
        status, _ = self.activate("STORY-A-001", "STORY-A-001-a.md")
        self.assertEqual(status, 200)
        self.assertEqual(unrelated.read_text(encoding="utf-8"), "leave this file as-is\n")
        self.assertEqual(story_state.parse_backlog_section(self.backlog.read_text(), "Blocked"), blocked_before)

    def test_requires_existing_bearer_auth(self):
        self.setup_two_stories()
        status, body = self.activate("STORY-A-001", "STORY-A-001-a.md", token=None)
        self.assertEqual(status, 401)
        self.assertIn("error", body)
        self.assertFalse(self.pointer.exists())


if __name__ == "__main__":
    unittest.main()
