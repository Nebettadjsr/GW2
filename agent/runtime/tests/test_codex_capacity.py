import io
import unittest
from unittest.mock import Mock, patch

from agent.runtime.runners import codex_capacity
from agent.runtime.runners.local_planner_runner import is_capacity_error


class CodexCapacityTest(unittest.TestCase):
    def test_checks_both_windows_and_ignores_unrelated_bucket(self):
        self.assertTrue(codex_capacity.quota_available({"rateLimitsByLimitId": {
            "codex": {"primary": {"usedPercent": 25}, "secondary": {"usedPercent": 99}},
            "other": {"primary": {"usedPercent": 100}}}}))
        self.assertFalse(codex_capacity.quota_available({"rateLimits": {
            "primary": {"usedPercent": 20}, "secondary": {"usedPercent": 100}}}))

    def test_missing_or_invalid_quota_is_not_capacity(self):
        for data in ({}, {"rateLimits": {}}, {"rateLimits": {"primary": {"usedPercent": "0"}}}):
            with self.assertRaises(ValueError):
                codex_capacity.quota_available(data)

    def test_explicit_limit_state_overrides_percentages(self):
        self.assertFalse(codex_capacity.quota_available({"rateLimits": {
            "primary": {"usedPercent": 0}, "rateLimitReachedType": "monthly"}}))

    def test_structured_usage_errors_only(self):
        self.assertTrue(is_capacity_error({"type": "turn.failed", "error": {"code": "usage_limit_reached"}}))
        self.assertTrue(is_capacity_error({"type": "error", "message": "You've hit your usage limit"}))
        self.assertFalse(is_capacity_error({"type": "item.completed", "item": {
            "type": "agent_message", "text": "usage limit"}}))
        self.assertFalse(is_capacity_error({"type": "error", "message": "Authentication failed"}))

    def test_rpc_handshake_reads_limits_without_a_model_turn(self):
        process = Mock()
        process.stdout = io.StringIO('{"id":1,"result":{}}\n'
                                     '{"method":"notice"}\n'
                                     '{"id":2,"result":{"rateLimits":{"primary":{"usedPercent":10}}}}\n')
        process.stdin = Mock()
        process.poll.return_value = None
        with patch.object(codex_capacity, "find_codex", return_value="codex"), \
             patch.object(codex_capacity.subprocess, "Popen", return_value=process) as start:
            self.assertTrue(codex_capacity.codex_available())
        self.assertEqual(start.call_args.args[0], ["codex", "app-server"])
        written = "".join(call.args[0] for call in process.stdin.write.call_args_list)
        self.assertIn('"initialize"', written)
        self.assertIn('"initialized"', written)
        self.assertIn('"account/rateLimits/read"', written)
        self.assertNotIn('turn/start', written)
        process.terminate.assert_called_once()
        process.stdin.close.assert_called_once()

    def test_endpoint_failure_cleans_up_process(self):
        process = Mock()
        process.stdout = io.StringIO('{"id":1,"result":{}}\n{"id":2,"error":{"message":"login needed"}}\n')
        process.poll.return_value = None
        with patch.object(codex_capacity, "find_codex", return_value="codex"), \
             patch.object(codex_capacity.subprocess, "Popen", return_value=process), \
             self.assertRaises(RuntimeError):
            codex_capacity.codex_available()
        process.terminate.assert_called_once()
