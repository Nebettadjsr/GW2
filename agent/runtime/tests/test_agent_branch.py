"""The pipeline's own branch: sync from the maintainer branch, publish back to it.

Against a real throwaway repository, a real local bare remote and a second
clone standing in for the maintainer, so every merge, conflict and rejected
push here is git's own behavior, not an assertion about it.

Run with: python -m unittest agent.runtime.tests.test_agent_branch -v
"""
import subprocess
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch

from agent.runtime.support import git_sync
from agent.runtime.tests import REAL_FETCH_BRANCH, REAL_PUSH_REF


def git(*arguments, cwd):
    return subprocess.run(["git", *arguments], cwd=cwd, check=True,
                          capture_output=True, text=True).stdout.strip()


class AgentBranchTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name).resolve()
        remote = root / "origin.git"
        git("init", "--bare", "--initial-branch=master", str(remote), cwd=root)

        self.agent = root / "agent"
        self.maintainer = root / "maintainer"
        git("clone", str(remote), str(self.agent), cwd=root)
        for repo in (self.agent,):
            self.identify(repo)
        (self.agent / "docs.md").write_text("one\ntwo\n", encoding="utf-8")
        (self.agent / "code.txt").write_text("code\n", encoding="utf-8")
        git("add", "--all", cwd=self.agent)
        git("commit", "-m", "seed", cwd=self.agent)
        git("push", "origin", "HEAD:master", cwd=self.agent)
        git("checkout", "-b", "agent/dev", cwd=self.agent)
        git("clone", str(remote), str(self.maintainer), cwd=root)
        self.identify(self.maintainer)

        stack = ExitStack()
        self.addCleanup(stack.close)
        stack.enter_context(patch.object(git_sync, "REPO_ROOT", self.agent))
        stack.enter_context(patch.object(git_sync, "fetch_branch", side_effect=REAL_FETCH_BRANCH))
        stack.enter_context(patch.object(git_sync, "push_ref", side_effect=REAL_PUSH_REF))

    @staticmethod
    def identify(repo):
        git("config", "user.email", "test@example.invalid", cwd=repo)
        git("config", "user.name", "Test", cwd=repo)

    def maintainer_commits(self, name, content):
        (self.maintainer / name).write_text(content, encoding="utf-8")
        git("add", name, cwd=self.maintainer)
        git("commit", "-m", f"maintainer edits {name}", cwd=self.maintainer)
        git("push", "origin", "master", cwd=self.maintainer)

    def test_nothing_new_is_up_to_date(self):
        self.assertEqual(git_sync.sync_with_maintainer_branch()["status"], "UP_TO_DATE")

    def test_maintainer_commits_are_merged_and_reported(self):
        before = git("rev-parse", "HEAD", cwd=self.agent)
        self.maintainer_commits("docs.md", "one\ntwo\nthree\n")
        result = git_sync.sync_with_maintainer_branch()
        self.assertEqual(result["status"], "MERGED")
        self.assertEqual((result["before"], result["changed_paths"]), (before, ["docs.md"]))
        self.assertEqual((self.agent / "docs.md").read_text(encoding="utf-8"), "one\ntwo\nthree\n")
        self.assertEqual(git("rev-parse", "--abbrev-ref", "HEAD", cwd=self.agent), "agent/dev")

    def test_a_conflict_is_aborted_and_leaves_the_branch_untouched(self):
        (self.agent / "docs.md").write_text("one\nagent\n", encoding="utf-8")
        git("commit", "-am", "agent edit", cwd=self.agent)
        head = git("rev-parse", "HEAD", cwd=self.agent)
        self.maintainer_commits("docs.md", "one\nmaintainer\n")
        result = git_sync.sync_with_maintainer_branch()
        self.assertEqual((result["status"], result["conflicts"]), ("CONFLICT", ["docs.md"]))
        self.assertEqual(git("rev-parse", "HEAD", cwd=self.agent), head)
        self.assertEqual(git("status", "--porcelain", cwd=self.agent), "")

    def test_uncommitted_work_in_the_way_defers_the_sync_and_survives(self):
        (self.agent / "docs.md").write_text("pending planning output\n", encoding="utf-8")
        self.maintainer_commits("docs.md", "one\ntwo\nthree\n")
        result = git_sync.sync_with_maintainer_branch()
        self.assertEqual(result["status"], "DEFERRED")
        self.assertEqual((self.agent / "docs.md").read_text(encoding="utf-8"), "pending planning output\n")

    def test_uncommitted_work_elsewhere_does_not_block_the_sync(self):
        (self.agent / "code.txt").write_text("in progress\n", encoding="utf-8")
        self.maintainer_commits("docs.md", "one\ntwo\nthree\n")
        self.assertEqual(git_sync.sync_with_maintainer_branch()["status"], "MERGED")
        self.assertEqual((self.agent / "code.txt").read_text(encoding="utf-8"), "in progress\n")

    def test_publish_fast_forwards_master_and_is_refused_once_it_moved(self):
        (self.agent / "code.txt").write_text("story\n", encoding="utf-8")
        git("commit", "-am", "implemented STORY-X", cwd=self.agent)
        self.assertEqual(git_sync.publish_to_maintainer_branch()["status"], "PUBLISHED")
        git("pull", "origin", "master", cwd=self.maintainer)
        self.assertEqual((self.maintainer / "code.txt").read_text(encoding="utf-8"), "story\n")

        self.maintainer_commits("docs.md", "maintainer moved on\n")
        (self.agent / "code.txt").write_text("next story\n", encoding="utf-8")
        git("commit", "-am", "implemented STORY-Y", cwd=self.agent)
        self.assertEqual(git_sync.publish_to_maintainer_branch()["status"], "REJECTED")

    def test_working_on_the_maintainer_branch_itself_is_skipped(self):
        git("checkout", "master", cwd=self.agent)
        self.assertEqual(git_sync.sync_with_maintainer_branch()["status"], "SKIPPED")
        self.assertEqual(git_sync.publish_to_maintainer_branch()["status"], "SKIPPED")


if __name__ == "__main__":
    unittest.main()
