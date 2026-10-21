"""Files that were already dirty when a run began are published once the run edits them."""

import tempfile
import unittest
from pathlib import Path
from unittest import mock

import agent.runtime.tests  # noqa: F401 (no models, real pushes or committed logs)
from workflow_service import cycle


class PublishBaselineTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        patcher = mock.patch.object(cycle, "REPO_ROOT", self.root)
        patcher.start()
        self.addCleanup(patcher.stop)

    def write(self, name, text):
        (self.root / name).write_text(text, encoding="utf-8")

    def test_a_baseline_file_the_run_edited_is_published(self):
        self.write("pom.xml", "left by a blocked story")
        self.write("untouched.txt", "maintainer work")
        dirty = {"pom.xml", "untouched.txt"}
        hashes = cycle._content_hashes(dirty)
        self.write("pom.xml", "left by a blocked story + the dependency this story needs")
        self.write("New.java", "class New {}")
        changed = cycle._changed_since(dirty, hashes, dirty | {"New.java"})
        self.assertEqual(changed, {"pom.xml", "New.java"})

    def test_a_baseline_file_the_run_left_alone_stays_out(self):
        self.write("pom.xml", "x")
        self.assertEqual(cycle._changed_since({"pom.xml"}, cycle._content_hashes({"pom.xml"}), {"pom.xml"}), set())

    def test_a_baseline_file_the_run_deleted_or_created_counts_as_changed(self):
        self.write("gone.txt", "x")
        hashes = cycle._content_hashes({"gone.txt", "later.txt"})
        (self.root / "gone.txt").unlink()
        self.write("later.txt", "now exists")
        self.assertEqual(cycle._changed_since({"gone.txt", "later.txt"}, hashes, {"gone.txt", "later.txt"}),
                         {"gone.txt", "later.txt"})

    def test_an_older_run_without_hashes_keeps_the_path_only_rule(self):
        self.write("pom.xml", "x")
        self.assertEqual(cycle._changed_since({"pom.xml"}, {}, {"pom.xml", "A.java"}), {"A.java"})


class CarriedPublishPathsTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        patcher = mock.patch.object(cycle, "CARRIED_PATHS_FILE", Path(directory.name) / "carried.json")
        patcher.start()
        self.addCleanup(patcher.stop)

    def story(self, story_id="STORY-X-001"):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / f"{story_id}-x.md"
        path.write_text("\n".join(["## Story ID", "", story_id, ""]), encoding="utf-8")
        with mock.patch.object(cycle, "_relative", return_value=path.name):
            return cycle.new_cycle(path)

    def test_a_blocked_stories_unpublished_work_is_carried_into_its_next_cycle(self):
        self.assertEqual(self.story()["publish_paths"], [])
        with mock.patch.object(cycle.story_state, "clear_active_story"):
            cycle._stop_blocked({"story_id": "STORY-X-001", "publish_paths": ["src/A.java", "src/B.java"]}, "why")
        self.assertEqual(self.story()["publish_paths"], ["src/A.java", "src/B.java"])
        self.assertEqual(self.story("STORY-X-002")["publish_paths"], [])

    def test_publishing_forgets_the_carried_paths(self):
        cycle._store_carried("STORY-X-001", ["src/A.java"])
        cycle._store_carried("STORY-X-001", [])
        self.assertEqual(self.story()["publish_paths"], [])

    def test_a_corrupt_record_is_ignored(self):
        cycle.CARRIED_PATHS_FILE.write_text("not json", encoding="utf-8")
        self.assertEqual(self.story()["publish_paths"], [])


if __name__ == "__main__":
    unittest.main()
