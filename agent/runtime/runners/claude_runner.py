from pathlib import Path
from typing import NamedTuple
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
    # passes through this process's stdout, which the daily log mirrors
    # (see support/daily_log.py). Inheriting the terminal would write
    # straight to the OS file descriptor and leave no record of what
    # Claude actually did during an unattended run. stderr is merged
    # into stdout to keep interleaved output in its original order.
    process = subprocess.Popen(
        [
            claude,
            "-p",
            "--model",
            CLAUDE_MODEL,
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

    for line in process.stdout:
        text = line.rstrip("\n")
        output_lines.append(text)

        print(_console_safe(text))

        tail = (tail + text + "\n")[-CAPACITY_OUTPUT_TAIL_CHARS:]

    process.stdout.close()

    return_code = process.wait()

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
