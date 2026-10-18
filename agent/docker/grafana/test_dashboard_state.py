"""Projection scenarios. Run with Python's jq package installed."""
import json
from pathlib import Path
import unittest
import copy

import jq
from build_dashboard import update_queries, add_status_panels

QUERY = jq.compile(Path(__file__).with_name("dashboard-state.jq").read_text(encoding="utf-8"))


def state(**changes):
    value = {
        "active_cycle": {"phase": "implement", "history": [
            {"step": "qa", "outcome": "READY"},
            {"step": "evaluate", "outcome": "RETRY"}]},
        "control": {"active": False, "outcome": "waiting_for_claude_budget"},
        "budget": {"stale": False, "error": None, "claude_allowed": False,
                   "result": {"weekly_used_percent": 19, "weekly_cap_percent": 14.3}},
    }
    value.update(changes)
    return QUERY.input_value(value).first()[0]


class DashboardStateTests(unittest.TestCase):
    def test_status_panel_install_does_not_repeat_or_override_later_ui_edits(self):
        dashboard = json.loads((Path(__file__).with_name('dashboards') / 'workflow-overview.json').read_text(encoding='utf-8'))
        first = add_status_panels(dashboard)
        first['panels'][0]['title'] = 'Custom title'
        before = copy.deepcopy(first)
        self.assertEqual(add_status_panels(first), before)

    def test_new_qa_phases_highlight_the_existing_qa_box(self):
        for step, label in [('qa_review', 'QA: CI review'), ('qa_repair', 'QA: test correction')]:
            with self.subTest(step=step):
                result = state(running_task={'status': 'running', 'step': step})
                self.assertEqual(result['qa'], 1)
                self.assertIn(label, result['qa_label'])
                self.assertEqual(result['saved_phase'], step)

    def test_blocked_story_retains_failed_stage_reason_and_request_reference(self):
        result = state(active_cycle=None,
                       last_cycle={'story_id': 'STORY-T-001', 'phase': 'blocked', 'history': []},
                       latest_event={'outcome': 'blocked', 'step': 'qa_repair', 'proceed': False,
                                     'reason': 'Need a product rule'},
                       human_requests={'open': [{'id': 'UD-007', 'story_ids': ['STORY-T-001'],
                                                  'path': 'agent/user-decisions/UD-007-rule.md'}]})
        self.assertEqual(result['qa'], 4)
        self.assertIn('WAITING: HUMAN', result['qa_label'])
        self.assertEqual(result['detail'], 'Need a product rule')
        self.assertIn('UD-007', result['request_notice'])

    def test_manual_error_overrides_old_budget_reason(self):
        result = state(latest_event={'outcome': 'error', 'step': 'qa_review', 'proceed': False,
                                     'reason': 'Invalid QA response'})
        self.assertEqual(result['detail'], 'Invalid QA response')
        self.assertEqual(result['qa'], 3)
        self.assertEqual(result['implement'], 0)

    def test_unclassified_stop_is_stopped_rather_than_idle(self):
        result = state(latest_event={'outcome': 'ci_unconfirmed', 'proceed': False,
                                     'step': 'ci', 'reason': 'Cannot establish CI verdict'})
        self.assertTrue(result['activity'].startswith('STOPPED'))
        self.assertEqual(result['saved_phase'], 'ci')

    def test_retryable_manual_result_does_not_mark_the_next_qa_stage_failed(self):
        result = state(active_cycle={'phase': 'qa_review', 'history': []},
                       latest_event={'outcome': 'FAILED', 'proceed': True, 'step': 'ci'})
        self.assertEqual(result['activity'], 'PAUSED / AWAITING RUN')
        self.assertEqual(result['qa'], 4)
        self.assertEqual(result['ci'], 0)

    def test_successful_qa_diagnosis_leaving_ci_red_does_not_mark_qa_failed(self):
        result = state(active_cycle={'phase': 'ci', 'history': []},
                       latest_event={'outcome': 'ci_still_failed', 'proceed': False,
                                     'step': 'qa_review', 'reason': 'QA found no correction; recheck CI.'})
        self.assertEqual(result['ci'], 3)
        self.assertEqual(result['qa'], 0)
        self.assertEqual(result['saved_phase'], 'ci')

    def test_query_update_preserves_ui_edits_and_history(self):
        path = Path(__file__).with_name('dashboards') / 'workflow-overview.json'
        dashboard = json.loads(path.read_text(encoding='utf-8'))
        dashboard['panels'][0]['title'] = 'Maintainer custom title'
        dashboard['panels'][0]['gridPos']['h'] = 8
        before = copy.deepcopy(dashboard)
        updated = update_queries(dashboard)
        for old, new in zip(before['panels'], updated['panels']):
            if old['type'] == 'table':
                self.assertEqual(old, new)
            else:
                old.pop('targets', None)
                clean = {key: value for key, value in new.items() if key != 'targets'}
                self.assertEqual(old, clean)
        self.assertEqual(len(before['panels']), len(updated['panels']))

    def test_budget_wait_does_not_report_historical_retry_as_failure(self):
        result = state()
        self.assertEqual(result["activity"], "WAITING FOR CLAUDE BUDGET")
        self.assertEqual(result["implement"], 4)
        self.assertEqual(result["qa"], 2)
        self.assertEqual(result["evaluate"], 0)
        self.assertIn("REVISION REQUESTED", result["evaluate_label"])
        self.assertEqual(result["weekly"], "19% used / 14.3% allowed")

    def test_running_task_wins_over_saved_phase_and_budget(self):
        result = state(running_task={"status": "running", "step": "evaluate"})
        self.assertEqual(result["activity"], "RUNNING")
        self.assertEqual(result["saved_phase"], "evaluate")
        self.assertEqual(result["evaluate"], 1)
        self.assertNotEqual(result["implement"], 4)

    def test_stale_or_error_reading_is_not_permission(self):
        for budget in ({"stale": True, "claude_allowed": False, "result": {}},
                       {"stale": False, "error": "unavailable", "result": {}}, {}):
            with self.subTest(budget=budget):
                result = state(budget=budget)
                self.assertEqual(result["activity"], "BUDGET CHECK NEEDED")
                self.assertNotEqual(result["budget_status"], "AVAILABLE")

    def test_new_allowance_does_not_mean_execution_started(self):
        result = state(budget={"stale": False, "claude_allowed": True, "result": {}})
        self.assertEqual(result["activity"], "BUDGET AVAILABLE / AWAITING RUN")

    def test_current_error_marks_only_current_stage_red(self):
        result = state(control={"active": False, "outcome": "error"})
        self.assertEqual(result["activity"], "STOPPED / ERROR")
        self.assertEqual(result["implement"], 3)
        self.assertEqual(result["qa"], 2)

    def test_disabled_automation_is_paused(self):
        result = state(control={"enabled": False})
        self.assertEqual(result["activity"], "PAUSED / AWAITING RUN")

    def test_empty_legacy_payload_is_idle_with_unknown_budget(self):
        result = QUERY.input_value({}).first()[0]
        self.assertEqual(result["activity"], "IDLE")
        self.assertEqual(result["budget_status"], "UNKNOWN")


if __name__ == "__main__":
    unittest.main()
