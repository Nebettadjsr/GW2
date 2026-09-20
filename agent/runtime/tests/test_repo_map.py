"""
Deterministic tests for agent/runtime/support/repo_map.py and its
injection point in evaluation/dispatcher.py's build_claude_prompt().

Never invokes a real Aider or Claude process. subprocess.run and
shutil.which are patched in every case that would otherwise touch the
filesystem/PATH for an external tool.

Run with: python -m unittest agent.runtime.tests.test_repo_map -v
(from the repository root).
"""

import subprocess
import unittest
from pathlib import Path
from unittest.mock import patch

from agent.runtime.support import repo_map
from agent.runtime.support import config
from agent.runtime.support.config import REPO_ROOT
from agent.runtime.evaluation import dispatcher


class RepoMapEnabledDefaultTest(unittest.TestCase):
    """
    REPO_MAP_ENABLED's default must be easy to override (AGENT_REPO_MAP_ENABLED)
    and otherwise reflect only whether the Aider CLI is actually present --
    a checkout without Aider must behave as if this feature did not exist.
    """

    def test_env_override_true(self):
        with patch.dict("os.environ", {"AGENT_REPO_MAP_ENABLED": "1"}):
            self.assertTrue(config._default_repo_map_enabled())

    def test_env_override_false(self):
        with patch.dict("os.environ", {"AGENT_REPO_MAP_ENABLED": "false"}):
            self.assertFalse(config._default_repo_map_enabled())

    def test_unset_falls_back_to_aider_presence(self):
        with patch.dict("os.environ", {}, clear=False):
            import os
            os.environ.pop("AGENT_REPO_MAP_ENABLED", None)

            with patch.object(config.shutil, "which", return_value=None):
                self.assertFalse(config._default_repo_map_enabled())

            with patch.object(
                config.shutil, "which", return_value="/usr/bin/aider"
            ):
                self.assertTrue(config._default_repo_map_enabled())


class GenerateRepoMapDisabledTest(unittest.TestCase):

    def test_disabled_returns_empty_without_touching_aider(self):
        with patch.object(
            repo_map, "find_aider",
            side_effect=AssertionError("must not look for Aider when disabled"),
        ):
            result = repo_map.generate_repo_map(enabled=False)

        self.assertFalse(result["enabled"])
        self.assertEqual(result["text"], "")
        self.assertIsNone(result["error"])


class GenerateRepoMapAvailabilityTest(unittest.TestCase):

    def test_aider_not_found_fails_gracefully(self):
        with patch.object(repo_map, "find_aider", return_value=None), \
             patch.object(
                 subprocess, "run",
                 side_effect=AssertionError("must not invoke a subprocess"),
             ):
            result = repo_map.generate_repo_map(
                token_budget=1200, enabled=True
            )

        self.assertTrue(result["enabled"])
        self.assertFalse(result["available"])
        self.assertEqual(result["text"], "")
        self.assertIsNotNone(result["error"])

    def test_is_repo_map_available_reflects_find_aider(self):
        with patch.object(repo_map, "find_aider", return_value=None):
            self.assertFalse(repo_map.is_repo_map_available())

        with patch.object(repo_map, "find_aider", return_value="/usr/bin/aider"):
            self.assertTrue(repo_map.is_repo_map_available())


class GenerateRepoMapSubprocessTest(unittest.TestCase):

    def test_nonzero_exit_fails_gracefully(self):
        with patch.object(repo_map, "find_aider", return_value="/usr/bin/aider"), \
             patch.object(
                 subprocess, "run",
                 return_value=subprocess.CompletedProcess(
                     args=[], returncode=1, stdout="", stderr="boom"
                 ),
             ):
            result = repo_map.generate_repo_map(enabled=True)

        self.assertTrue(result["available"])
        self.assertEqual(result["text"], "")
        self.assertIn("boom", result["error"])

    def test_timeout_fails_gracefully(self):
        with patch.object(repo_map, "find_aider", return_value="/usr/bin/aider"), \
             patch.object(
                 subprocess, "run",
                 side_effect=subprocess.TimeoutExpired(cmd="aider", timeout=60),
             ):
            result = repo_map.generate_repo_map(enabled=True)

        self.assertEqual(result["text"], "")
        self.assertIsNotNone(result["error"])

    def test_unexpected_exception_fails_gracefully(self):
        # Simulates a bug/edge case entirely unrelated to Aider itself
        # -- generate_repo_map() must still never raise.
        with patch.object(
            repo_map, "find_aider", side_effect=RuntimeError("unexpected")
        ):
            result = repo_map.generate_repo_map(enabled=True)

        self.assertEqual(result["text"], "")
        self.assertIn("unexpected", result["error"])

    def test_success_populates_text_and_size_fields(self):
        map_text = "src/\n  Foo.java\n  Bar.java\n"

        with patch.object(repo_map, "find_aider", return_value="/usr/bin/aider"), \
             patch.object(
                 subprocess, "run",
                 return_value=subprocess.CompletedProcess(
                     args=[], returncode=0, stdout=map_text, stderr=""
                 ),
             ):
            result = repo_map.generate_repo_map(
                token_budget=1200, enabled=True
            )

        self.assertTrue(result["enabled"])
        self.assertTrue(result["available"])
        self.assertEqual(result["text"], map_text.strip())
        self.assertEqual(result["token_budget"], 1200)
        self.assertEqual(result["char_count"], len(map_text.strip()))
        self.assertEqual(result["approx_tokens"], len(map_text.strip()) // 4)
        self.assertIsNone(result["error"])

    def test_budget_is_passed_to_aider_cli(self):
        captured = {}

        def fake_run(args, **kwargs):
            captured["args"] = args
            return subprocess.CompletedProcess(
                args=args, returncode=0, stdout="map", stderr=""
            )

        with patch.object(repo_map, "find_aider", return_value="/usr/bin/aider"), \
             patch.object(subprocess, "run", side_effect=fake_run):
            repo_map.generate_repo_map(token_budget=777, enabled=True)

        self.assertIn("--map-tokens", captured["args"])
        idx = captured["args"].index("--map-tokens")
        self.assertEqual(captured["args"][idx + 1], "777")


class FormatRepoMapForPromptTest(unittest.TestCase):

    def test_empty_map_produces_empty_string(self):
        empty = {"text": "", "token_budget": 1200}
        self.assertEqual(repo_map.format_repo_map_for_prompt(empty), "")

    def test_nonempty_map_includes_orientation_note_and_budget(self):
        result = {"text": "src/\n  Foo.java\n", "token_budget": 1200}

        formatted = repo_map.format_repo_map_for_prompt(result)

        self.assertIn("orientation only", formatted)
        self.assertIn("not authoritative", formatted)
        self.assertIn("1200", formatted)
        self.assertIn("Foo.java", formatted)


class BuildClaudePromptRepoMapInjectionTest(unittest.TestCase):

    def _plan(self):
        return {
            "story_id": "STORY-X-001",
            "goal": "Do the thing.",
            "acceptance_criteria": ["It works."],
            "references": ["docs/DOMAIN_SPEC.md"],
        }

    def test_no_repo_map_context_is_byte_identical_to_before(self):
        story_path = REPO_ROOT / "agent" / "stories" / "STORY-X-001-thing.md"

        with_default = dispatcher.build_claude_prompt(self._plan(), story_path)
        with_explicit_empty = dispatcher.build_claude_prompt(
            self._plan(), story_path, repo_map_context=""
        )

        self.assertEqual(with_default, with_explicit_empty)
        self.assertNotIn("Repository map", with_default)

    def test_repo_map_context_is_included_when_present(self):
        story_path = REPO_ROOT / "agent" / "stories" / "STORY-X-001-thing.md"

        prompt = dispatcher.build_claude_prompt(
            self._plan(),
            story_path,
            repo_map_context="Repository map (orientation only): src/Foo.java",
        )

        self.assertIn("Repository map (orientation only)", prompt)
        self.assertIn("src/Foo.java", prompt)
        # Still contains the normal required sections.
        self.assertIn("Execute STORY-X-001.", prompt)
        self.assertIn("Active story:", prompt)
        self.assertIn("Goal:", prompt)


if __name__ == "__main__":
    unittest.main()
