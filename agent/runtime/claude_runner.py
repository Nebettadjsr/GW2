from pathlib import Path
import re
import shutil
import subprocess
import time

from config import (
    CLAUDE_USAGE_LIMIT_PERCENT,
    CLAUDE_USAGE_RECHECK_SECONDS,
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
    while True:
        usage = get_claude_session_usage_percent()

        print(
            f"Claude current session usage: {usage}%"
        )

        if usage < threshold:
            return

        print(
            f"Claude usage is at {usage}% "
            f"(required below {threshold}%)."
        )

        print(
            "Pausing for 1 hour before checking again..."
        )

        time.sleep(
            CLAUDE_USAGE_RECHECK_SECONDS
        )


# ============================================================
# Claude Code
# ============================================================

def run_claude(
    prompt: str,
    label: str = "Claude Code"
) -> int:

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

    result = subprocess.run(
        [
            claude,
            "-p",
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
        input=prompt,
    )

    print(
        "\n========================================"
    )

    print(
        f"{label} exit code: {result.returncode}"
    )

    print(
        "========================================\n"
    )

    return result.returncode


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
