"""Read-only HTTP adapter tests using isolated story/backlog fixtures."""

import json
import sys
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
from agent.runtime.core import selector, story_archive, story_state
from agent.runtime.human import user_decisions


class StorySelectionEndpointTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.stories = self.root / "stories"
        self.stories.mkdir()
        self.backlog = self.stories / "BACKLOG.md"
        self.decisions = self.root / "user-decisions"
        self.decisions.mkdir()
        self.archive = self.root / "archive"
        self.artifact = self.root / "selector-result.json"
        self.stack = ExitStack()
        for module, name, value in (
            (story_state, "STORIES_DIR", self.stories),
            (story_state, "BACKLOG_FILE", self.backlog),
            (story_archive, "ARCHIVE_DIR", self.archive),
            (selector, "STORIES_DIR", self.stories),
            (selector, "SELECTOR_RESULT_FILE", self.artifact),
            (user_decisions, "USER_DECISIONS_DIR", self.decisions),
        ):
            self.stack.enter_context(patch.object(module, name, value))
        self.addCleanup(self.stack.close)
        self.addCleanup(self.temp.cleanup)

        self.bridge = Bridge(TaskStore(self.root / "tasks.json"), "fixture-token")
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.bridge))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.url = f"http://127.0.0.1:{self.server.server_port}"

    def write_backlog(self, names):
        entries = "\n".join(f"- `{name}`" for name in names)
        self.backlog.write_text(
            f"# Backlog\n\n## Active\n\n## To Do\n\n{entries}\n\n"
            "## Blocked\n\n_(none)_\n\n## Done\n\n_(none)_\n\n## Archived\n\n_(none)_\n",
            encoding="utf-8",
        )

    def write_story(self, name, story_id, title, status="TODO", dependencies="None."):
        (self.stories / name).write_text(
            f"## Story ID\n{story_id}\n\n## Title\n{title}\n\n"
            f"## Status\n{status}\n\n## Dependencies\n{dependencies}\n\n"
            "## Description\nFixture story content.\n",
            encoding="utf-8",
        )

    def get_selection(self, token="fixture-token"):
        headers = {} if token is None else {"Authorization": f"Bearer {token}"}
        request = Request(self.url + "/stories/next", headers=headers)
        try:
            with urlopen(request, timeout=3) as response:
                return response.status, json.loads(response.read())
        except HTTPError as error:
            return error.code, json.loads(error.read())

    def test_uses_selector_order_and_dependency_eligibility(self):
        first = "STORY-A-001-blocked.md"
        second = "STORY-B-001-ready.md"
        self.write_backlog([first, second])
        self.write_story("STORY-DEP-001.md", "STORY-DEP-001", "Pending dependency")
        self.write_story(first, "STORY-A-001", "Blocked by dependency", dependencies="STORY-DEP-001")
        self.write_story(second, "STORY-B-001", "Ready title")

        # Compare with the production selector's exact candidate ordering.
        expected = selector.get_selectable_story_candidates()[0]
        status, payload = self.get_selection()
        self.assertEqual(status, 200)
        self.assertEqual(payload["story"]["filename"], expected.name)
        self.assertEqual(payload["story"]["id"], "STORY-B-001")
        self.assertEqual(payload["story"]["title"], "Ready title")
        self.assertIn("Fixture story content", payload["story"]["content"])
        self.assertTrue(payload["eligibility"]["eligible"])
        self.assertEqual(payload["dependencies"]["story_ids"], [])

    def test_empty_queue_and_repeated_reads_do_not_mutate_repository(self):
        self.write_backlog([])
        status, payload = self.get_selection()
        self.assertEqual(status, 200)
        self.assertTrue(payload["queue_empty"])
        self.assertEqual(payload["decision"], "NO_WORK")
        self.assertIsNone(payload["story"])

        tracked = [
            Path("agent/stories/BACKLOG.md"),
            Path("agent/CURRENT_STORY.md"),
            Path("agent/runtime/artifacts"),
        ]
        before = self.snapshot(tracked)
        responses = [self.get_selection()[1] for _ in range(3)]
        self.assertEqual(responses, [payload] * 3)
        self.assertEqual(self.snapshot(tracked), before)
        self.assertFalse(self.artifact.exists())

    def test_requires_existing_bearer_auth(self):
        status, payload = self.get_selection(token=None)
        self.assertEqual(status, 401)
        self.assertIn("error", payload)

    @staticmethod
    def snapshot(paths):
        result = {}
        for path in paths:
            if path.is_file():
                result[str(path)] = path.read_bytes()
            elif path.is_dir():
                result[str(path)] = {
                    str(child): child.read_bytes()
                    for child in sorted(path.rglob("*")) if child.is_file()
                }
            else:
                result[str(path)] = None
        return result


if __name__ == "__main__":
    unittest.main()
