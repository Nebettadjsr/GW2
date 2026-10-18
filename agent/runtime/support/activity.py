"""Optional narration observer, scoped to one workflow task and its thread."""
from contextlib import contextmanager
from contextvars import ContextVar
import json
import logging
import re

_SINK = ContextVar('workflow_activity_sink', default=None)
MAX_TEXT = 1000


@contextmanager
def observe(sink):
    token = _SINK.set(sink)
    try:
        yield
    finally:
        _SINK.reset(token)


def emit(role, text, *, kind='message', event_id=None, retry=None):
    sink = _SINK.get()
    if sink is None or not isinstance(text, str) or not text.strip():
        return
    text = text.strip()
    if kind in ('message', 'summary'):
        try:
            if isinstance(json.loads(text), (dict, list)):
                return  # Machine-readable verdicts belong to their artifacts, not the feed.
        except ValueError:
            pass
        text = re.sub(r'```.*?(?:```|$)', '', text, flags=re.DOTALL)
    text = ' '.join(text.split())
    if not text:
        return
    if len(text) > MAX_TEXT:
        text = text[:MAX_TEXT - 3].rstrip() + '...'
    try:
        extra = {'retry': retry} if retry is not None else {}
        sink(role=role, text=text, kind=kind, event_id=event_id, **extra)
    except Exception:
        # Monitoring must never turn a completed model call into a failed coding attempt.
        logging.warning('Could not record workflow activity', exc_info=True)


def retrying(stage, number, limit):
    """Report an already-decided retry; never alter its counter or control flow."""
    if number > 0:
        emit('Workflow', f'{stage}: retry {number}/{limit}.', kind='retry',
             retry={'stage': stage, 'number': number, 'limit': limit})


def claude_event(event):
    if event.get('type') != 'assistant':
        return
    message = event.get('message') or {}
    for index, block in enumerate(message.get('content') or []):
        if block.get('type') == 'text':
            identity = message.get('id')
            emit('Claude / implementer', block.get('text'),
                 event_id=f'claude:{identity}:{index}' if identity else None)
    # Tool use, tool results, raw thinking and final result envelopes are excluded.


def codex_event(event, role):
    if event.get('type') != 'item.completed':
        return
    item = event.get('item') or {}
    if item.get('type') not in ('agent_message', 'reasoning'):
        return
    # Codex's public ReasoningItem.text is its emitted reasoning *summary*.
    # https://github.com/openai/codex/blob/main/sdk/typescript/src/items.ts
    kind = 'summary' if item['type'] == 'reasoning' else 'message'
    # Item IDs restart in each CLI invocation, including nested QA calls in one task.
    emit(f'Codex / {role}', item.get('text'), kind=kind)
