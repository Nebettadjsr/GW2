"""Renamed configuration must reuse existing state rather than reset progress."""

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import agent.runtime.tests  # noqa: F401 (offline guards)
from workflow_service import service


class ConfigurationTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.home = Path(directory.name)
        patcher = mock.patch.object(service.Path, "home", return_value=self.home)
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_previous_environment_settings_are_still_accepted(self):
        state = self.home / "existing.json"
        with mock.patch.dict(service.os.environ, {"GW2_BRIDGE_TOKEN": "old-token", "GW2_BRIDGE_HOST": "old-host",
                                                  "GW2_BRIDGE_STATE": str(state)}, clear=True):
            self.assertEqual(service.environment_settings(), ("old-token", "old-host", state))

    def test_new_names_take_precedence_over_previous_names(self):
        state = self.home / "selected.json"
        settings = {"GW2_BRIDGE_TOKEN": "old-token", "GW2_BRIDGE_HOST": "old-host", "GW2_BRIDGE_STATE": "old.json",
                    "GW2_WORKFLOW_TOKEN": "new-token", "GW2_WORKFLOW_HOST": "new-host", "GW2_WORKFLOW_STATE": str(state)}
        with mock.patch.dict(service.os.environ, settings, clear=True):
            self.assertEqual(service.environment_settings(), ("new-token", "new-host", state))

    def test_existing_state_file_is_reused_without_copying_or_rewriting(self):
        state = self.home / ".gw2-claude-bridge" / "tasks.json"
        state.parent.mkdir()
        state.write_text('{"active": "STORY-1"}', encoding="utf-8")
        before = state.read_bytes()
        with mock.patch.dict(service.os.environ, {}, clear=True):
            self.assertEqual(service.environment_settings()[2], state)
        self.assertEqual(state.read_bytes(), before)
        self.assertFalse((self.home / ".gw2-workflow-service").exists())

    def test_new_state_file_is_preferred_when_both_paths_exist(self):
        for folder in (".gw2-claude-bridge", ".gw2-workflow-service"):
            path = self.home / folder / "tasks.json"
            path.parent.mkdir()
            path.write_text("{}", encoding="utf-8")
        with mock.patch.dict(service.os.environ, {}, clear=True):
            self.assertEqual(service.environment_settings()[2], self.home / ".gw2-workflow-service" / "tasks.json")

    def test_fresh_installation_uses_service_names(self):
        with mock.patch.dict(service.os.environ, {}, clear=True):
            self.assertEqual(service.environment_settings(),
                             ("", "127.0.0.1", self.home / ".gw2-workflow-service" / "tasks.json"))

    def test_old_checkpoint_migrates_without_losing_story_or_budget(self):
        path = self.home / "tasks.json"
        original = {"active": "STORY-1", "cycles": {"STORY-1": {"phase": "evaluate", "retry_count": 2}},
                    "planning": {"fingerprint": "saved"},
                    "runner": {"active": True, "steps": 87, "task_id": "completed-task"},
                    "tasks": {"completed-task": {"status": "completed", "result": {"proceed": True}}},
                    "budget": {"settings": {"mode": "manual", "manual_cap_percent": 15},
                               "checked_at": None, "result": None, "error": None}}
        path.write_text(json.dumps(original), encoding="utf-8")
        store = service.Store(path)
        self.assertEqual(store.control, original["runner"])
        self.assertEqual(store.active_cycle(), original["cycles"]["STORY-1"])
        self.assertEqual(store.planning, original["planning"])
        self.assertEqual(store.budget["settings"]["manual_cap_percent"], 15)
        saved = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(saved["control"], original["runner"])
        self.assertEqual(service.Store(path).control, store.control)

    @unittest.skipUnless(os.name == "nt", "Windows launcher")
    def test_launcher_reaches_renamed_entry_point_without_opening_state_for_help(self):
        root = Path(__file__).resolve().parent.parent
        state = self.home / "must-not-be-created.json"
        env = {**os.environ, "GW2_WORKFLOW_TOKEN": "test-token-" * 4,
               "GW2_AGENT_WORKTREE": str(root), "GW2_WORKFLOW_STATE": str(state),
               "GW2_WORKFLOW_DRIVER": "python"}
        result = subprocess.run(["cmd", "/d", "/c", "workflow_service\\start-service.cmd --help"],
                                cwd=root, env=env, text=True, capture_output=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("--once", result.stdout)
        self.assertIn("Workflow service repository:", result.stdout)
        self.assertFalse(state.exists())

    @unittest.skipUnless(os.name == "nt", "Windows launcher")
    def test_launcher_refuses_an_agent_worktree_without_the_new_package(self):
        root = Path(__file__).resolve().parent.parent
        env = {**os.environ, "GW2_WORKFLOW_TOKEN": "test-token-" * 4,
               "GW2_AGENT_WORKTREE": str(self.home), "GW2_WORKFLOW_DRIVER": "python"}
        result = subprocess.run(["cmd", "/d", "/c", "workflow_service\\start-service.cmd --help"],
                                cwd=root, env=env, text=True, capture_output=True, timeout=15)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("Deploy this change", result.stdout)
