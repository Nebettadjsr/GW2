from pathlib import Path
from typing import NamedTuple
import re
import shutil
import subprocess
import time

from agent.runtime.support.config import (
    CLAUDE_MODEL,
    CLAUDE_USAGE_LIMIT_PERCENT,
    MODEL_CAPACITY_RECHECK_SECONDS,
    REPO_ROOT,
)
from agent.runtime.support.daily_log import print_status


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


def get_claude_session_usage_percent() -> int:
    claude = find_claude()

    result = subprocess.run(
        [
            claude,
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

    match = re.search(
        r"Current session:\s*(\d+)% used",
        result.stdout,
        re.IGNORECASE,
    )

    if not match:
        raise RuntimeError(
            "Could not parse Claude session usage from:\n"
            + result.stdout
        )

    return int(
        match.group(1)
    )


def wait_for_claude_capacity(
    threshold: int = CLAUDE_USAGE_LIMIT_PERCENT
) -> None:
    """
    Wait locally until the Claude session is back under `threshold`.

    `claude -p /usage` runs a slash command and performs no inference,
    so re-checking costs no tokens and never invokes the exhausted
    model. Status output is terminal-only (print_status): an overnight
    wait must not fill agent/logs/ with identical heartbeat lines.
    """

    while True:
        usage = get_claude_session_usage_percent()

        if usage < threshold:
            print_status(
                f"Claude capacity available again (session usage {usage}%)."
            )
            return

        print_status(
            "Claude capacity unavailable - waiting..."
        )

        print_status(
            f"Current usage: Claude session {usage}% "
            f"(resumes below {threshold}%)"
        )

        print_status(
            "Next capacity check in "
            f"{MODEL_CAPACITY_RECHECK_SECONDS // 60} min"
        )

        time.sleep(
            MODEL_CAPACITY_RECHECK_SECONDS
        )


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
    r"|rate limit"
    r"|limit reached"
    r"|quota (?:exceeded|exhausted)"
    r"|insufficient quota"
    r"|credit balance",
    re.IGNORECASE,
)


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

    for line in process.stdout:
        text = line.rstrip("\n")

        print(
            text
        )

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
