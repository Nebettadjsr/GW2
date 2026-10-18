"""Only public narration/summary events reach the optional observer."""
import threading
import unittest

from agent.runtime.support import activity


class ActivityTests(unittest.TestCase):
    def test_claude_forwards_text_but_not_thinking_tools_or_results(self):
        rows = []
        with activity.observe(lambda **row: rows.append(row)):
            activity.claude_event({'type': 'assistant', 'message': {'id': 'm1', 'content': [
                {'type': 'thinking', 'thinking': 'not public narration'},
                {'type': 'text', 'text': 'I am updating the results.'},
                {'type': 'tool_use', 'name': 'Bash', 'input': {'command': 'secret command'}},
                {'type': 'tool_result', 'content': 'full file dump'}]}})
            activity.claude_event({'type': 'result', 'result': 'raw result'})
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]['text'], 'I am updating the results.')
        self.assertEqual(rows[0]['event_id'], 'claude:m1:1')

    def test_codex_accepts_completed_public_summaries_not_tool_events_or_partial_updates(self):
        rows = []
        with activity.observe(lambda **row: rows.append(row)):
            for kind in ('reasoning', 'agent_message', 'command_execution', 'file_change', 'mcp_tool_call'):
                activity.codex_event({'type': 'item.updated', 'item': {'type': kind, 'text': 'partial'}}, 'qa')
                activity.codex_event({'type': 'item.completed', 'item': {'type': kind, 'text': kind}}, 'qa')
        self.assertEqual([row['kind'] for row in rows], ['summary', 'message'])
        self.assertTrue(all(row['role'] == 'Codex / qa' for row in rows))

    def test_structured_results_and_code_blocks_are_excluded_and_messages_bounded(self):
        rows = []
        with activity.observe(lambda **row: rows.append(row)):
            for text in ('{"verdict":"COMPLETE"}', '["raw", "result"]', '```json\n{}\n```'):
                activity.emit('qa', text)
            activity.emit('qa', 'Checking behavior.\n```sh\ncat private.txt\n```\nReviewing the result.')
            activity.emit('qa', 'x' * 5000)
        self.assertEqual(rows[0]['text'], 'Checking behavior. Reviewing the result.')
        self.assertEqual(len(rows[1]['text']), activity.MAX_TEXT)
        self.assertEqual(len(rows), 2)

    def test_scopes_do_not_leak_to_other_threads_and_observer_failure_is_nonfatal(self):
        rows = []
        with activity.observe(lambda **row: rows.append(row)):
            worker = threading.Thread(target=lambda: activity.emit('qa', 'other task'))
            worker.start()
            worker.join()
            activity.emit('qa', 'this task')
        activity.emit('qa', 'outside the workflow')
        self.assertEqual([row['text'] for row in rows], ['this task'])
        with activity.observe(lambda **row: (_ for _ in ()).throw(OSError('disk unavailable'))):
            with self.assertLogs(level='WARNING'):
                activity.emit('qa', 'Never fail model execution for monitoring.')
