import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from save_claude_recovery import save_recovery


class RecoveryTest(unittest.TestCase):
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
