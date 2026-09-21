import json
import shutil
import subprocess
import threading

from agent.runtime.support.config import REPO_ROOT
from agent.runtime.support.capacity import ModelCapacityUnavailable


def is_capacity_error(event):
    if event.get("type") not in ("error", "turn.failed"):
        return False
    error = event.get("error") or event
    if not isinstance(error, dict):
        return False
    code = str(error.get("code", "")).lower()
    message = str(error.get("message", "")).lower()
    return code in ("usage_limit_reached", "rate_limit_exceeded", "insufficient_quota") or any(
        phrase in message for phrase in ("usage limit", "usage_limit_reached", "rate limit exceeded",
                                         "insufficient quota", "rate_limit_exceeded"))


# ============================================================
# Codex discovery
# ============================================================

def find_codex() -> str:
    executable = shutil.which("codex")

    if executable is not None:
        return executable

    raise RuntimeError(
        "Codex CLI was not found in PATH."
    )


# ============================================================
# Live output handling
# ============================================================

def _handle_json_event(
        event: dict
) -> None:

    event_type = event.get("type")

    if event_type == "thread.started":
        thread_id = event.get("thread_id")

        if thread_id:
            print(
                f"[planner] session {thread_id}"
            )

        return

    if event_type == "turn.started":
        print(
            "[planner] reasoning..."
        )
        return

    if event_type in (
            "item.started",
            "item.updated",
            "item.completed",
    ):
        item = event.get("item") or {}
        item_type = item.get("type")

        if item_type == "reasoning":
            text = (
                    item.get("text")
                    or ""
            ).strip()

            if text:
                print(
                    f"\n[planner reasoning] {text}"
                )

            return

        if item_type == "command_execution":
            command = (
                    item.get("command")
                    or ""
            ).strip()

            if event_type == "item.started":
                if command:
                    print(
                        f"\n[planner command] {command}"
                    )

                return

            if event_type == "item.completed":
                exit_code = item.get(
                    "exit_code"
                )

                status = item.get(
                    "status"
                )

                if exit_code is not None:
                    print(
                        f"[planner command done] exit {exit_code}"
                    )
                elif status:
                    print(
                        f"[planner command done] {status}"
                    )

                # Deliberately do NOT print aggregated_output.
                # This suppresses entire file dumps.
                return

        if (
                item_type == "file_change"
                and event_type == "item.completed"
        ):
            status = (
                    item.get("status")
                    or "completed"
            )

            print(
                f"\n[planner file change] {status}"
            )

            return

        if (
                item_type == "agent_message"
                and event_type == "item.completed"
        ):
            text = (
                    item.get("text")
                    or ""
            ).strip()

            if text:
                print(
                    "\nPlanner last response:"
                )
                print(
                    text
                )

            return

        if item_type == "error":
            message = (
                    item.get("message")
                    or ""
            ).strip()

            if message:
                print(
                    f"\n[planner error] {message}"
                )

            return

        return

    if event_type == "turn.completed":
        usage = (
                event.get("usage")
                or {}
        )

        print(
            "\n[planner] turn completed"
            f" | input tokens: {usage.get('input_tokens')}"
            f" | output tokens: {usage.get('output_tokens')}"
        )

        return

    if event_type == "turn.failed":
        error = (
                event.get("error")
                or {}
        )

        message = (
                error.get("message")
                or str(error)
        )

        print(
            f"\n[planner error] turn failed: {message}"
        )

        return

    if event_type == "error":
        message = (
                event.get("message")
                or "Unknown Codex error."
        )

        print(
            f"\n[planner error] {message}"
        )


def _drain_stderr(
        pipe
) -> None:

    try:
        for line in pipe:
            text = line.strip()

            if not text:
                continue

            lower = text.lower()

            if (
                    "warning" in lower
                    or "error" in lower
                    or "failed" in lower
            ):
                print(
                    f"\n[codex] {text}"
                )

    finally:
        pipe.close()


# ============================================================
# Hosted Codex planner
# ============================================================

def run_local_planner(
        prompt: str
) -> int:

    codex = find_codex()

    print(
        "\n========================================"
    )
    print(
        "Starting hosted Codex planner"
    )
    print(
        "========================================\n"
    )

    planner_prompt = f"""
PROJECT PLANNING TASK

{prompt}

EXECUTION ENVIRONMENT

You are running on Windows.

For reading/searching files, use PowerShell commands such as:
- Get-Content
- Get-ChildItem
- Select-String
- Test-Path

For text-file modifications:
- do not use Add-Content
- do not use Set-Content for complex Markdown/JSON
- do not construct Markdown through PowerShell quoting
- prefer Python file editing

When modifying BACKLOG.md:
1. read the file;
2. locate the exact target section;
3. modify that section in memory;
4. write the complete file back.

Do not blindly append to BACKLOG.md.
"""

    process = subprocess.Popen(
        [
            codex,
            "exec",

            # Stream structured progress events.
            "--json",

            # Allow planning files to be edited.
            "--sandbox",
            "workspace-write",

            # Prompt comes from stdin.
            "-",
        ],
        cwd=REPO_ROOT,
        text=True,
        encoding="utf-8",
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        bufsize=1,
    )

    stderr_thread = threading.Thread(
        target=_drain_stderr,
        args=(process.stderr,),
        daemon=True,
    )

    stderr_thread.start()

    assert process.stdin is not None
    assert process.stdout is not None

    process.stdin.write(
        planner_prompt
    )
    process.stdin.close()

    capacity_exhausted = False
    for line in process.stdout:
        stripped = line.strip()

        if not stripped:
            continue

        try:
            event = json.loads(
                stripped
            )
        except json.JSONDecodeError:
            print(
                "[planner] received non-JSON Codex output "
                "(suppressed)"
            )
            continue

        capacity_exhausted = capacity_exhausted or is_capacity_error(event)
        _handle_json_event(
            event
        )

    process.stdout.close()

    return_code = process.wait()

    stderr_thread.join(
        timeout=1
    )

    print(
        "\n========================================"
    )
    print(
        f"Hosted Codex planner exit code: {return_code}"
    )
    print(
        "========================================\n"
    )

    if capacity_exhausted:
        raise ModelCapacityUnavailable("Codex usage exhausted")
    return return_code
