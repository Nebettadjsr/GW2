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


class CodexRoleVisibilityTest(unittest.TestCase):
    def test_both_roles_check_quota_before_invocation_and_reuse_it_for_display(self):
        from agent.runtime.core import orchestrator as loop
        events = []
        quota = {"available": True, "primary": {"usedPercent": 25, "windowDurationMins": 300},
                 "secondary": {"usedPercent": 80, "windowDurationMins": 10080}, "limit_reached": None}
        def read():
            events.append("quota")
            return quota
        def show(line):
            self.assertIn("25%", line)
            self.assertIn("80%", line)
            events.append("status")
        def plan():
            events.append("planner")
            return {"status": "NEEDS_USER", "independent_work_remaining": False}
        def architect(request):
            events.append("architect")
            return {"status": "COMPLETE"}
        with patch.object(loop, "read_codex_capacity", side_effect=read) as rpc, \
             patch.object(loop, "print_status", side_effect=show), \
             patch.object(loop, "planning_input_snapshot", return_value={}), \
             patch.object(loop, "get_selectable_story_candidates", return_value=[]), \
             patch.object(loop, "run_planning_pass", side_effect=plan), \
             patch.object(loop, "run_architect_pass", side_effect=architect), patch.object(loop, "log_line"):
            scheduler = loop.CapacityScheduler(claude=Mock(), cache_file=None)
            scheduler.plan_if_useful(idle=True)
            scheduler.answer_architect_request({"file": "AR-001.md"})
        self.assertEqual(events, ["quota", "status", "planner", "quota", "status", "architect"])
        self.assertEqual(rpc.call_count, 2)  # Display caused no additional RPC.

    def test_exhausted_or_unknown_quota_prevents_both_roles(self):
        from agent.runtime.core import orchestrator as loop
        for reading in (False, RuntimeError("endpoint unavailable")):
            with self.subTest(reading=reading), \
                 patch.object(loop, "read_codex_capacity", side_effect=reading if isinstance(reading, Exception) else None,
                              return_value={"available": False}) as rpc, \
                 patch.object(loop, "planning_input_snapshot", return_value={}), \
                 patch.object(loop, "run_planning_pass") as plan, \
                 patch.object(loop, "run_architect_pass") as architect, patch.object(loop, "log_line"):
                scheduler = loop.CapacityScheduler(claude=Mock(), cache_file=None)
                self.assertFalse(scheduler.plan_if_useful(idle=True))
                self.assertFalse(scheduler.answer_architect_request({"file": "AR-001.md"}))
                plan.assert_not_called()
                architect.assert_not_called()
                rpc.assert_called_once()  # Local exhaustion cooldown.

    def test_capacity_display_bypasses_persistent_console_log(self):
        from agent.runtime.core import orchestrator as loop
        from agent.runtime.support import daily_log
        terminal = io.StringIO()
        scheduler = loop.CapacityScheduler(claude=Mock(), codex=Mock(), cache_file=None)
        scheduler.codex_capacity = {"primary": {"usedPercent": 12}, "secondary": None}
        with patch("sys.stdout", daily_log.ConsoleTee(terminal)), patch.object(daily_log, "_write") as log:
            scheduler._show_codex_capacity("architect")
        self.assertIn("Codex available before architect: primary 12% used", terminal.getvalue())
        log.assert_not_called()

    def test_usage_line_exposes_cached_input_and_role(self):
        from agent.runtime.runners import local_planner_runner as runner
        terminal = io.StringIO()
        with patch("sys.stdout", terminal):
            runner._handle_json_event({"type": "turn.completed", "usage": {
                "input_tokens": 727623, "cached_input_tokens": 637312, "output_tokens": 6894}}, role="architect")
        self.assertIn("[architect] turn completed", terminal.getvalue())
        self.assertIn("input tokens: 727623 | cached input tokens: 637312", terminal.getvalue())
