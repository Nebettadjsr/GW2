from pathlib import Path
from typing import NamedTuple
import json
import os
import re
import shutil
import subprocess
import sys

from agent.runtime.support.config import (
    CLAUDE_MODEL,
    REPO_ROOT,
)


# ============================================================
# Claude discovery / usage
# ============================================================

def find_claude() -> str:
    executable = shutil.which(
        "claude"
    )

    if executable is not None:
        return executable

    fallback = (
        Path.home()
        / ".local"
        / "bin"
        / "claude.exe"
    )

    if fallback.exists():
        return str(
            fallback
        )

    raise RuntimeError(
        "Claude Code was not found."
    )


class ClaudeUsage(NamedTuple):
    session_used_percent: int
    weekly_used_percent: int


def get_claude_usage() -> ClaudeUsage:
    return parse_claude_usage(read_claude_usage_text())


def read_claude_usage_text() -> str:
    """Raw `claude -p /usage` output; a slash command, so it spends no tokens."""
    result = subprocess.run(
        [
            find_claude(),
            "-p",
            "/usage",
        ],
        cwd=REPO_ROOT,
        text=True,
        encoding="utf-8",
        capture_output=True,
        timeout=30,
    )

    if result.returncode != 0:
        raise RuntimeError(
            "Could not read Claude usage:\n"
                + result.stderr
        )

    return result.stdout


def parse_claude_usage(text: str) -> ClaudeUsage:
    session = re.search(
        r"Current session:\s*(\d+)% used",
        text,
        re.IGNORECASE,
    )

    weekly = re.findall(
        r"^Current week(?:\s*\([^\n)]*\))?:\s*(\d+)% used",
        text,
        re.IGNORECASE | re.MULTILINE,
    )

    if not session or not weekly:
        raise RuntimeError(
            "Could not parse Claude session and weekly usage from:\n"
            + text
        )

    # If /usage reports more than one weekly allowance, the most-used
    # one is the safe gate for the pinned model.
    return ClaudeUsage(int(session.group(1)), max(map(int, weekly)))


# ============================================================
# Claude Code
#
# A non-zero exit means one of two very different things: Claude ran out
# of capacity mid-attempt (a scheduling event -- wait locally and resume
# the same story), or the attempt genuinely failed (re-invoking Claude
# on an hourly loop would never fix it). The two must not be conflated,
# so the attempt reports whether Claude's own output carried a capacity
# signal, and the orchestrator decides from that plus the token-free
# usage probe above.
#
# Only the tail of the output is scanned: an implementation story may
# legitimately print the words "usage limit" while editing this very
# subsystem, and a mid-run mention must never be mistaken for the run's
# own outcome.
# ============================================================

CAPACITY_OUTPUT_TAIL_CHARS = 2000

# Claude's final `result` event ends the answer, but in --print mode the
# process stays alive while a background shell it started (a dev server, a
# watcher) keeps running -- the pipeline then waits forever. After the result
# it gets this long to exit on its own before its process tree is stopped.
RESULT_EXIT_GRACE_SECONDS = 30

CAPACITY_OUTPUT_PATTERN = re.compile(
    r"usage limit"
    r"|weekly limit"
    r"|session limit"
    r"|rate limit"
    r"|limit reached"
    r"|quota (?:exceeded|exhausted)"
    r"|insufficient quota"
    r"|credit balance",
    re.IGNORECASE,
)


def _console_safe(text: str) -> str:
    """Replace characters the active console encoding cannot represent.

    Model output is UTF-8, but Windows consoles may expose a legacy encoding
    such as cp1252. A print failure here occurs after the model has finished
    and can prevent the orchestrator from recording its resumable phase.
    """
    encoding = getattr(sys.stdout, "encoding", None)
    if not encoding:
        return text
    try:
        text.encode(encoding)
    except (LookupError, UnicodeEncodeError):
        try:
            return text.encode(encoding, errors="replace").decode(encoding)
        except LookupError:
            return text
    return text


def output_indicates_capacity_exhaustion(
    output: str
) -> bool:
    return bool(
        CAPACITY_OUTPUT_PATTERN.search(
            output[-CAPACITY_OUTPUT_TAIL_CHARS:]
        )
    )


class ClaudeAttempt(NamedTuple):
    exit_code: int
    capacity_exhausted: bool
    output: str = ""


def _render_event(event: dict) -> str:
    """One readable console/log line per stream-json event ('' to skip)."""
    kind = event.get("type")
    if kind == "assistant":
        parts = []
        for block in (event.get("message") or {}).get("content") or []:
            if block.get("type") == "text" and block.get("text", "").strip():
                parts.append(block["text"].rstrip())
            elif block.get("type") == "tool_use":
                tool_input = block.get("input") or {}
                detail = (tool_input.get("command") or tool_input.get("file_path")
                          or tool_input.get("description") or "")
                parts.append(f"> {block.get('name')}: {' '.join(str(detail).split())[:160]}")
        return "\n".join(parts)
    if kind == "result" and event.get("is_error"):
        return str(event.get("result") or event.get("subtype") or "Claude reported an error.")
    return ""


def _stop_process_tree(process) -> None:
    """Stop Claude and everything it left running (dev servers, shells)."""
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                       capture_output=True, check=False)
    else:
        process.kill()


def run_claude_attempt(
    prompt: str,
    label: str = "Claude Code"
) -> ClaudeAttempt:

    claude = find_claude()

    print(
        "\n========================================"
    )
    print(
        f"Starting {label}"
    )
    print(
        "========================================\n"
    )

    # Claude's output is piped rather than inherited so that every line
    # passes through this process's stdout and can be inspected. stream-json
    # marks the end of the answer explicitly (a `result` event); plain text
    # cannot, which left the pipeline waiting on a Claude process kept alive
    # by its own background shells. stderr is merged into stdout to keep
    # interleaved output in its original order.
    process = subprocess.Popen(
        [
            claude,
            "-p",
            "--model",
            CLAUDE_MODEL,
            "--output-format",
            "stream-json",
            "--verbose",
            "--permission-mode",
            "dontAsk",
            "--allowedTools",
            "Read",
            "Edit",
            "Write",
            "Bash",
        ],
        cwd=REPO_ROOT,
        text=True,
        encoding="utf-8",
        errors="replace",
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        bufsize=1,
    )

    process.stdin.write(
        prompt
    )
    process.stdin.close()

    tail = ""
    output_lines = []
    final = None

    for line in process.stdout:
        raw = line.rstrip("\n")
        try:
            event = json.loads(raw)
        except ValueError:
            event = None
        text = _render_event(event) if isinstance(event, dict) else raw
        if text:
            output_lines.append(text)
            print(_console_safe(text))
            tail = (tail + text + "\n")[-CAPACITY_OUTPUT_TAIL_CHARS:]
        if isinstance(event, dict) and event.get("type") == "result":
            final = event
            break

    if final is None:
        process.stdout.close()
        return_code = process.wait()
    else:
        try:
            return_code = process.wait(timeout=RESULT_EXIT_GRACE_SECONDS)
        except subprocess.TimeoutExpired:
            print(f"{label} answered but is still running (background processes); "
                  "stopping its process tree.")
            _stop_process_tree(process)
            process.wait()
            return_code = 1 if final.get("is_error") else 0
        process.stdout.close()

    print(
        "\n========================================"
    )

    print(
        f"{label} exit code: {return_code}"
    )

    print(
        "========================================\n"
    )

    return ClaudeAttempt(
        return_code,
        return_code != 0
        and output_indicates_capacity_exhaustion(tail),
        "\n".join(output_lines),
    )


def run_claude(
    prompt: str,
    label: str = "Claude Code"
) -> int:
    return run_claude_attempt(
        prompt,
        label
    ).exit_code


def run_claude_planning(
    prompt: str
) -> int:
    # Same executable and permission model as story execution -- the
    # planning/implementation boundary is enforced by the planning
    # prompt's scope plus project_planner's post-run validation, not by
    # a different set of allowed tools.
    return run_claude(
        prompt,
        label="Claude Code (planning mode)"
    )
