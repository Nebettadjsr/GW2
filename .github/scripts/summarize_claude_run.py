#!/usr/bin/env python3
"""Render a short "Run metrics" Markdown section for the PR evidence.

Reads the execution log written by anthropics/claude-code-action (JSON array or JSON lines of
SDK messages; the final message of type "result" carries turns, duration, usage and cost) plus
the workflow's own wall-clock start time. Every field is optional: whatever is missing is shown as
"n/a", and an unreadable log produces a note instead of an error, so reporting can never fail a run.

Environment: CLAUDE_EXECUTION_FILE, RUN_STARTED_AT (epoch seconds), CLAUDE_MAX_TURNS.
"""
import json
import os
import sys
import time
from pathlib import Path


def load_messages(path: str) -> list[dict]:
    text = Path(path).read_text(encoding="utf-8").strip()
    if not text:
        return []
    try:
        data = json.loads(text)
        return [m for m in (data if isinstance(data, list) else [data]) if isinstance(m, dict)]
    except json.JSONDecodeError:
        messages = []
        for line in text.splitlines():
            try:
                value = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(value, dict):
                messages.append(value)
        return messages


def final_result(messages: list[dict]) -> dict:
    for message in reversed(messages):
        if message.get("type") == "result":
            return message
    return {}


def number(value, digits: int = 0) -> str:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return "n/a"
    return f"{value:,.{digits}f}" if digits else f"{int(value):,}"


def seconds(value) -> str:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return "n/a"
    minutes, rest = divmod(int(value), 60)
    return f"{minutes} min {rest:02d} s"


def render(result: dict, wall_clock_seconds, max_turns: str, log_note: str = "") -> str:
    usage = result.get("usage") if isinstance(result.get("usage"), dict) else {}
    duration_ms = result.get("duration_ms")
    api_ms = result.get("duration_api_ms")
    lines = [
        "## Run metrics",
        "",
        f"- Turns: {number(result.get('num_turns'))} of {max_turns or 'n/a'} allowed",
        f"- Wall-clock time of the Claude step: {seconds(wall_clock_seconds)}",
        f"- Session duration (reported): {seconds(duration_ms / 1000 if isinstance(duration_ms, (int, float)) else None)}"
        f", of which API time {seconds(api_ms / 1000 if isinstance(api_ms, (int, float)) else None)}",
        f"- Tokens: input {number(usage.get('input_tokens'))}, output {number(usage.get('output_tokens'))}, "
        f"cache read {number(usage.get('cache_read_input_tokens'))}, "
        f"cache write {number(usage.get('cache_creation_input_tokens'))}",
        f"- API-equivalent cost (informational, the run uses the subscription): "
        f"{'$' + number(result.get('total_cost_usd'), 2) if isinstance(result.get('total_cost_usd'), (int, float)) else 'n/a'}",
    ]
    if result.get("is_error") is True or (isinstance(result.get("subtype"), str) and result["subtype"] != "success"):
        lines.append(f"- Result: {result.get('subtype', 'error')}")
    if log_note:
        lines.append(f"- Note: {log_note}")
    return "\n".join(lines) + "\n"


def main() -> int:
    path = os.environ.get("CLAUDE_EXECUTION_FILE", "")
    max_turns = os.environ.get("CLAUDE_MAX_TURNS", "")
    started = os.environ.get("RUN_STARTED_AT", "")
    wall = None
    if started.isdigit():
        wall = max(0, int(time.time()) - int(started))
    result: dict = {}
    note = ""
    if not path or not Path(path).is_file():
        note = "execution log not available"
    else:
        try:
            result = final_result(load_messages(path))
        except (OSError, UnicodeDecodeError):
            note = "execution log unreadable"
        if not result and not note:
            note = "execution log has no result record"
    sys.stdout.write(render(result, wall, max_turns, note))
    return 0


if __name__ == "__main__":
    sys.exit(main())
