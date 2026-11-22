"""Offline checks for the harness boundary: path lists, discarding, feedback extraction."""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

import discard_out_of_scope as discard
import extract_harness_feedback as feedback
import harness_paths as paths


def git(root, *args):
    subprocess.run(["git", "-C", str(root), *args], check=True, capture_output=True)


def make_repo(files):
    root = Path(tempfile.mkdtemp())
    git(root, "init", "-q")
    git(root, "config", "user.email", "t@example.invalid")
    git(root, "config", "user.name", "t")
    for name, text in files.items():
        (root / name).parent.mkdir(parents=True, exist_ok=True)
        (root / name).write_text(text, encoding="utf-8")
    git(root, "add", "-A")
    git(root, "commit", "-q", "-m", "base")
    base = subprocess.run(["git", "-C", str(root), "rev-parse", "HEAD"], capture_output=True, text=True,
                          check=True).stdout.strip()
    return root, base


class PathListTest(unittest.TestCase):
    def test_off_limits_and_supervised_lists(self):
        for path in (".github/workflows/ci.yml", "agent/README.md", "tasks/lessons.md", "AGENTS.md", "CLAUDE.md",
                     "backend/config/checkstyle/checkstyle.xml", "frontend/eslint.config.js",
                     "backend/src/test/java/architecture/ArchitectureTest.java",
                     "backend/src/test/resources/archunit_store/stored.rules", ".claude/settings.local.json"):
            self.assertTrue(paths.is_off_limits(path), path)
        for path in ("backend/src/main/java/sync/TpSync.java", "frontend/src/App.vue", "docs/DOMAIN_SPEC.md",
                     "docs/KNOWN_PROBLEMS.md", "backend/pom.xml", "docs/CODING_GUIDELINES.md"):
            self.assertFalse(paths.is_off_limits(path), path)
        for path in ("backend/pom.xml", "frontend/package.json", "frontend/package-lock.json", "agent/README.md"):
            self.assertTrue(paths.needs_supervised_merge(path), path)
        self.assertFalse(paths.needs_supervised_merge("frontend/src/App.vue"))
        self.assertTrue(paths.is_off_limits(".github\\workflows\\ci.yml"))


class DiscardTest(unittest.TestCase):
    def test_modified_deleted_and_new_harness_files_are_restored_and_listed(self):
        root, base = make_repo({
            "backend/src/main/java/A.java": "class A {}", "agent/README.md": "agent",
            "CLAUDE.md": "claude", ".github/workflows/ci.yml": "ci", "tasks/lessons.md": "l",
        })
        (root / "backend/src/main/java/A.java").write_text("class A { int x; }", encoding="utf-8")
        (root / "agent/README.md").write_text("changed", encoding="utf-8")
        (root / "CLAUDE.md").unlink()
        (root / "agent/NEW.md").write_text("new", encoding="utf-8")
        (root / ".github/workflows/new.yml").write_text("new", encoding="utf-8")
        (root / "backend/pom.xml").write_text("pom", encoding="utf-8")
        cwd = os.getcwd()
        os.chdir(root)
        try:
            discarded = discard.discard(base)
            git(root, "add", "-A")
            status = subprocess.run(["git", "-C", str(root), "status", "--porcelain"], capture_output=True,
                                    text=True, check=True).stdout
        finally:
            os.chdir(cwd)
        self.assertEqual({"agent/README.md", "CLAUDE.md", "agent/NEW.md", ".github/workflows/new.yml"},
                         set(discarded))
        self.assertEqual("agent", (root / "agent/README.md").read_text(encoding="utf-8"))
        self.assertEqual("claude", (root / "CLAUDE.md").read_text(encoding="utf-8"))
        self.assertFalse((root / "agent/NEW.md").exists())
        self.assertIn("backend/src/main/java/A.java", status)
        self.assertIn("backend/pom.xml", status)
        for path in discarded:
            self.assertNotIn(path, status)

    def test_harness_changes_that_were_committed_by_the_agent_are_reverted(self):
        root, base = make_repo({"agent/README.md": "agent", "backend/src/main/java/A.java": "a"})
        (root / "agent/README.md").write_text("changed", encoding="utf-8")
        git(root, "add", "-A")
        git(root, "commit", "-q", "-m", "agent commit")
        cwd = os.getcwd()
        os.chdir(root)
        try:
            self.assertEqual(["agent/README.md"], discard.discard(base))
        finally:
            os.chdir(cwd)
        self.assertEqual("agent", (root / "agent/README.md").read_text(encoding="utf-8"))

    def test_nothing_to_discard(self):
        root, base = make_repo({"backend/src/main/java/A.java": "a"})
        (root / "backend/src/main/java/A.java").write_text("b", encoding="utf-8")
        cwd = os.getcwd()
        os.chdir(root)
        try:
            self.assertEqual([], discard.discard(base))
        finally:
            os.chdir(cwd)


class FeedbackTest(unittest.TestCase):
    REPORT = "## Changes\nx\n\n## Harness feedback\n\n{}\n\n## Acceptance criteria evidence\n| a | b | c |\n"

    def test_none_values_are_skipped(self):
        for text in ("none", "None.", "**none**", "", "n/a", "-"):
            self.assertTrue(feedback.is_none(feedback.feedback_section(self.REPORT.format(text))), text)

    def test_real_feedback_is_extracted_without_the_next_section(self):
        section = feedback.feedback_section(self.REPORT.format("The rework context lacked the test class."))
        self.assertEqual("The rework context lacked the test class.", section)
        self.assertFalse(feedback.is_none(section))

    def test_status_lines_after_the_last_section_are_not_feedback(self):
        last = "## Changes\nx\n\n## Harness feedback\n\n{}\n\nLocal-only checks still needed: none\nUnresolved gaps: none\n"
        self.assertTrue(feedback.is_none(feedback.feedback_section(last.format("none"))))
        self.assertEqual("Rule X is unclear.", feedback.feedback_section(last.format("Rule X is unclear.")))
        self.assertTrue(feedback.is_none(feedback.feedback_section(
            "## Harness feedback\nnone\nLocal-only checks still needed: none\nUnresolved gaps: none")))

    def test_missing_section_counts_as_none_here(self):
        self.assertEqual("", feedback.feedback_section("## Changes\nx\n"))

    def test_body_lists_feedback_and_discarded_changes(self):
        body = feedback.build_body("Rule X is unclear.", "- `agent/README.md`\n", "391", "https://x/pr/1", "https://x/run/2")
        self.assertIn("From story #391, https://x/pr/1, run https://x/run/2.", body)
        self.assertIn("Rule X is unclear.", body)
        self.assertIn("agent/README.md", body)
        only_discarded = feedback.build_body("none", "- `CLAUDE.md`\n", "1", "", "")
        self.assertNotIn("Reported by the agent", only_discarded)


if __name__ == "__main__":
    unittest.main()
