import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from save_claude_recovery import save_recovery


class RecoveryTest(unittest.TestCase):
    def test_published_base_restores_committed_and_uncommitted_work(self):
        original_cwd = Path.cwd()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repository = root / "repository"
            repository.mkdir()

            def git(*args, cwd=repository):
                return subprocess.check_output(["git", *args], cwd=cwd, stderr=subprocess.PIPE)

            git("init")
            git("config", "user.name", "Recovery test")
            git("config", "user.email", "recovery@example.invalid")
            (repository / ".gitignore").write_text(".env\n", encoding="utf-8")
            (repository / "source.txt").write_text("published\n", encoding="utf-8")
            git("add", ".")
            git("commit", "-m", "published base")
            base = git("rev-parse", "HEAD").decode().strip()
            # Preparation/implementation can create commits before the rejected push.
            (repository / "prerequisite.txt").write_text("merged prerequisite\n", encoding="utf-8")
            git("add", ".")
            git("commit", "-m", "unpublished prerequisite merge contents")
            (repository / "source.txt").write_text("committed implementation\n", encoding="utf-8")
            workflow = repository / ".github" / "workflows" / "example.yml"
            workflow.parent.mkdir(parents=True)
            workflow.write_text("name: example\n", encoding="utf-8")
            git("add", ".")
            git("commit", "-m", "implementation before rejected publication")
            (repository / "source.txt").write_text("latest uncommitted correction\n", encoding="utf-8")
            (repository / ".env").write_text("secret=excluded\n", encoding="utf-8")
            report = root / "report.md"
            report.write_text("Checks still need review\n", encoding="utf-8")
            artifact = root / "artifact"
            try:
                os.chdir(repository)
                save_recovery(artifact, report, base)
            finally:
                os.chdir(original_cwd)
            self.assertEqual(base, (artifact / "base-commit.txt").read_text().strip())
            self.assertTrue((artifact / "changes.patch").stat().st_size)
            self.assertNotIn(b"secret=excluded", (artifact / "changes.patch").read_bytes())
            restored = root / "restored"
            git("clone", str(repository), str(restored))
            git("checkout", base, cwd=restored)
            git("apply", str(artifact / "changes.patch"), cwd=restored)
            self.assertEqual("latest uncommitted correction\n", (restored / "source.txt").read_text())
            self.assertEqual("merged prerequisite\n", (restored / "prerequisite.txt").read_text())
            self.assertEqual("name: example\n", (restored / ".github/workflows/example.yml").read_text())
            self.assertEqual("Checks still need review\n", (artifact / "run-report.md").read_text())
            self.assertFalse((restored / ".env").exists())

    def test_workflow_recovers_after_both_publication_steps(self):
        workflow = (Path(__file__).resolve().parents[1] / "workflows/claude.yml").read_text()
        recovery_at = workflow.index("- name: Save interrupted implementation")
        self.assertGreater(recovery_at, workflow.index("- name: Publish PR evidence"))
        recovery = workflow[recovery_at:workflow.index("- name: Upload interrupted implementation")]
        for step in ("claude", "publish_branch", "publish_pr"):
            self.assertIn(f"steps.{step}.outcome == 'failure'", recovery)
        self.assertIn("steps.prepare.outputs.recovery_base", recovery)
        prepare = workflow[workflow.index("- name: Validate story and prepare branch"):workflow.index("- name: Implement one story")]
        self.assertLess(prepare.index("recovery_base=$(git rev-parse HEAD)"), prepare.index("git merge origin/master"))
        self.assertIn('echo "recovery_base=$recovery_base"', prepare)

    def test_patch_restores_tracked_new_deleted_and_binary_files(self):
        original_cwd = Path.cwd()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repository = root / "repository"
            repository.mkdir()

            def git(*args, cwd=repository):
                return subprocess.check_output(["git", *args], cwd=cwd, stderr=subprocess.PIPE)

            git("init")
            git("config", "user.name", "Recovery test")
            git("config", "user.email", "recovery@example.invalid")
            (repository / ".gitignore").write_text(".env\n", encoding="utf-8")
            (repository / "existing.txt").write_text("before\n", encoding="utf-8")
            (repository / "removed.txt").write_text("before\n", encoding="utf-8")
            (repository / "image.bin").write_bytes(b"\x00before")
            git("add", ".")
            git("commit", "-m", "base")
            base = git("rev-parse", "HEAD").decode().strip()
            (repository / "existing.txt").write_text("after\n", encoding="utf-8")
            (repository / "removed.txt").unlink()
            (repository / "image.bin").write_bytes(b"\x00after\xff")
            (repository / "new.txt").write_text("new source\n", encoding="utf-8")
            (repository / ".env").write_text("secret=excluded\n", encoding="utf-8")
            report = root / "report.md"
            report.write_text("Still incomplete\n", encoding="utf-8")
            artifact = root / "artifact"
            try:
                os.chdir(repository)
                save_recovery(artifact, report)
            finally:
                os.chdir(original_cwd)

            self.assertEqual((artifact / "base-commit.txt").read_text().strip(), base)
            self.assertEqual((artifact / "run-report.md").read_text(), "Still incomplete\n")
            self.assertNotIn(b"secret=excluded", (artifact / "changes.patch").read_bytes())
            restored = root / "restored"
            git("clone", str(repository), str(restored))
            git("apply", str(artifact / "changes.patch"), cwd=restored)
            self.assertEqual((restored / "existing.txt").read_text(), "after\n")
            self.assertEqual((restored / "new.txt").read_text(), "new source\n")
            self.assertEqual((restored / "image.bin").read_bytes(), b"\x00after\xff")
            self.assertFalse((restored / "removed.txt").exists())
            self.assertFalse((restored / ".env").exists())


if __name__ == "__main__":
    unittest.main()
