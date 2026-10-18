"""Read-only monitoring tests: no agents, Git writes or workflow transitions."""
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import agent.runtime.tests  # noqa: F401
from workflow_service import service, status


class StatusTests(unittest.TestCase):
    def test_open_requests_include_ids_paths_and_only_explicit_story_associations(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for kind in ('user-decisions', 'user-interventions'):
                (root / 'agent' / kind).mkdir(parents=True)
            (root / 'agent/user-decisions/UD-001-choice.md').write_text(
                '# UD-001\n## Status\nOPEN\n## Decision Needed\nChoose a rule.\n'
                '## Blocks\nSTORY-T-001\n## Context\nSTORY-OTHER-002 was an example.', encoding='utf-8')
            (root / 'agent/user-interventions/UI-002-tool.md').write_text(
                '## Status\nOPEN\n## Story\nSTORY-T-001\n## Reason\nAccess denied.', encoding='utf-8')
            (root / 'agent/user-decisions/UD-003-old.md').write_text('## Status\nRESOLVED', encoding='utf-8')
            before = {p: p.read_bytes() for p in root.rglob('*.md')}
            result = status.human_requests(root)
            self.assertEqual([r['id'] for r in result['open']], ['UD-001', 'UI-002'])
            self.assertEqual(result['open'][0]['story_ids'], ['STORY-T-001'])
            self.assertEqual(result['open'][1]['reason'], 'Access denied.')
            self.assertEqual(before, {p: p.read_bytes() for p in root.rglob('*.md')})

    def test_unreadable_request_is_reported_without_losing_other_status(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / 'agent/user-decisions/UD-001-bad.md'
            path.parent.mkdir(parents=True)
            path.write_bytes(b'\xff')
            result = status.human_requests(root)
            self.assertEqual(result['open'], [])
            self.assertIn('UD-001-bad.md', result['errors'][0])

    def test_manual_error_supersedes_older_scheduled_budget_wait(self):
        task = {'task_id': 'new', 'created_at': '2026-10-18T10:00:00+00:00',
                'step': 'qa_repair', 'outcome': 'error', 'reason': 'QA broke', 'proceed': False}
        result = status.latest_event(task, {'updated_at': '2026-10-18T09:00:00+00:00',
                                          'outcome': 'waiting_for_claude_budget'})
        self.assertEqual(result['reason'], 'QA broke')
        self.assertEqual(result['step'], 'qa_repair')

    def test_loop_guard_stop_supersedes_last_successful_task(self):
        result = status.latest_event({'task_id': 'a', 'step': 'implement', 'proceed': True,
                                      'created_at': '2026-10-18T09:00:00+00:00'},
                                     {'task_id': 'a', 'updated_at': '2026-10-18T10:00:00+00:00',
                                      'outcome': 'loop_guard', 'reason': '200 attempts'})
        self.assertEqual(result['reason'], '200 attempts')
        self.assertIsNone(result['step'])  # The run guard is not an implementation failure.
        self.assertFalse(result['proceed'])

    def test_event_order_compares_times_instead_of_timezone_strings(self):
        result = status.latest_event({'created_at': '2026-10-18T09:30:00+00:00', 'reason': 'new task'},
                                     {'updated_at': '2026-10-18T10:00:00+02:00',
                                      'outcome': 'error', 'reason': 'older control error'})
        self.assertEqual(result['reason'], 'new task')

    def test_restart_checkpoint_does_not_make_an_old_stop_newer_than_manual_work(self):
        result = status.latest_event({'created_at': '2026-10-18T09:30:00+00:00', 'reason': 'new task'},
                                     {'finished_at': '2026-10-18T08:00:00+00:00',
                                      'updated_at': '2026-10-18T10:00:00+00:00',
                                      'outcome': 'error', 'reason': 'older control error'})
        self.assertEqual(result['reason'], 'new task')

    def test_blocked_context_survives_cleared_active_pointer(self):
        with tempfile.TemporaryDirectory() as directory:
            store = service.Store(Path(directory) / 'tasks.json')
            store.save_cycle({'story_id': 'STORY-T-001', 'phase': 'blocked'})
            task = store.create(kind='step', step='qa_review', story_id='STORY-T-001')
            store.update(task['id'], status='completed', result={
                'outcome': 'blocked', 'proceed': False, 'reason': 'Human decision required'})
            monitor = service.WorkflowService(store, 'x' * 40)
            before = store.path.read_bytes()
            with mock.patch.object(service.status_view, 'human_requests', return_value={'open': [], 'errors': []}):
                result = monitor.cycle_status()
            self.assertIsNone(result['active_cycle'])
            self.assertEqual(result['last_cycle']['phase'], 'blocked')
            self.assertEqual(result['latest_event']['step'], 'qa_review')
            self.assertEqual(result['latest_event']['reason'], 'Human decision required')
            self.assertEqual(before, store.path.read_bytes())


if __name__ == '__main__':
    unittest.main()
