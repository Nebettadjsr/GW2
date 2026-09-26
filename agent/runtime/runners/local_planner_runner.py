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
# Token accounting
#
# One Codex run makes several model requests, and each one resends the
# whole prompt plus everything returned so far. Reporting only the last
# turn therefore hides the cost this harness is actually optimizing, so
# every usage-bearing event is counted and the totals are printed once
# when the run ends.
#
# Event shapes differ between Codex CLI versions: `turn.completed`
# carries the turn's own usage, while per-request `token_count` events
# (when the CLI emits them) carry `info.last_token_usage`. Both are
# recorded, and they are reported separately rather than added together,
# so nothing here invents a number the stream did not state.
# ============================================================

USAGE_FIELDS = ("input_tokens", "cached_input_tokens", "output_tokens")


class UsageTally:

    def __init__(self):
        self.requests = 0
        self.turns = 0
        self.per_request = dict.fromkeys(USAGE_FIELDS, 0)
        self.turn_totals = dict.fromkeys(USAGE_FIELDS, 0)

    @staticmethod
    def _counters(usage):
        if not isinstance(usage, dict):
            return None

        counters = {
            field: usage.get(field)
            for field in USAGE_FIELDS
            if isinstance(usage.get(field), int)
        }

        return counters or None

    def record(self, event: dict) -> None:
        info = event.get("info")

        if isinstance(info, dict):
            counters = self._counters(info.get("last_token_usage"))

            if counters:
                self.requests += 1

                for field, value in counters.items():
                    self.per_request[field] += value

        counters = self._counters(event.get("usage"))

        if counters and event.get("type") == "turn.completed":
            self.turns += 1

            for field, value in counters.items():
                self.turn_totals[field] += value

    def _line(self, label, counters, requests):
        gross = counters["input_tokens"]
        cached = counters["cached_input_tokens"]

        return (
            f"{label}"
            f" | model requests: {requests}"
            f" | gross input tokens: {gross:,}"
            f" | cached input tokens: {cached:,}"
            f" | uncached input tokens: {gross - cached:,}"
            f" | output tokens: {counters['output_tokens']:,}"
        )

    def summary(self, role: str) -> str:
        lines = []

        if self.turns:
            lines.append(
                self._line(f"[{role}] turn totals", self.turn_totals,
                           self.requests or self.turns)
            )

        if self.requests:
            lines.append(
                self._line(f"[{role}] summed across requests",
                           self.per_request, self.requests)
            )

        if not lines:
            return f"[{role}] no token usage was reported by Codex."

        return "\n".join(lines)


# ============================================================
# Live output handling
# ============================================================

def _handle_json_event(
        event: dict,
        role: str = "planner",
) -> None:

    event_type = event.get("type")

    if event_type == "thread.started":
        thread_id = event.get("thread_id")

        if thread_id:
            print(
                f"[{role}] session {thread_id}"
            )

        return

    if event_type == "turn.started":
        print(
            f"[{role}] reasoning..."
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
                    f"\n[{role} reasoning] {text}"
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
                        f"\n[{role} command] {command}"
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
                        f"[{role} command done] exit {exit_code}"
                    )
                elif status:
                    print(
                        f"[{role} command done] {status}"
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
                f"\n[{role} file change] {status}"
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
                    f"\n{role.capitalize()} last response:"
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
                    f"\n[{role} error] {message}"
                )

            return

        return

    if event_type == "turn.completed":
        usage = (
                event.get("usage")
                or {}
        )

        print(
            f"\n[{role}] turn completed"
            f" | input tokens: {usage.get('input_tokens')}"
            f" | cached input tokens: {usage.get('cached_input_tokens', 'unknown')}"
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
            f"\n[{role} error] turn failed: {message}"
        )

        return

    if event_type == "error":
        message = (
                event.get("message")
                or "Unknown Codex error."
        )

        print(
            f"\n[{role} error] {message}"
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
# Hosted Codex
#
# One process runner for both Codex roles (PROJECT PLANNING MODE and
# ARCHITECTURE MODE -- see AGENTS.md). The roles stay logically
# separate: each supplies its own task header and its own role contract
# inside `prompt`. Only the execution-environment guidance below, which
# is about Windows and file editing rather than about either role, is
# shared.
# ============================================================

EXECUTION_ENVIRONMENT = """EXECUTION ENVIRONMENT

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
"""

SECTIONED_FILE_EDITING = """When modifying a file with named sections
(for example BACKLOG.md, or a request/decision file's '## Status'):
1. read the file;
2. locate the exact target section;
3. modify that section in memory;
4. write the complete file back.

Do not blindly append to such a file.
Do this inside one script -- do not print the file's contents to do it.
"""

# Shared by both roles: this is about how a Codex run spends context, not
# about what either role decides. Every command's returned output stays in
# the run's context and is resent with every later model request in the same
# run, so a repeated read or a full-file dump is paid for many times over
# (measured in agent/runtime/reports/2026-09-26-planner-usage.md).
CONTEXT_DISCIPLINE = """CONTEXT DISCIPLINE

Everything supplied in this task prompt is already in your context. Never
read, reread or print anything it already contains -- including your own
role contract and AGENTS.md.

When you do need more:
- read one named section or line range, not a whole large document;
- batch independent reads into a single command;
- read a file or section at most once per run, and reuse the output you
  already have instead of asking again;
- keep command output small (search with Select-String and a small context
  window instead of printing whole files);
- do not print a file merely to edit it.

Never truncate or skip evidence a decision actually depends on: narrow the
read, do not guess. Correctness first, then context economy.
"""


def run_codex(
        prompt: str,
        task_header: str = "PROJECT PLANNING TASK",
        label: str = "hosted Codex planner",
) -> int:

    codex = find_codex()
    role = "architect" if task_header == "ARCHITECTURE TASK" else "planner"

    print(
        "\n========================================"
    )
    print(
        f"Starting {label}"
    )
    print(
        "========================================\n"
    )

    planner_prompt = f"""
{task_header}

{prompt}

{EXECUTION_ENVIRONMENT}
{SECTIONED_FILE_EDITING}
{CONTEXT_DISCIPLINE}"""

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
    usage = UsageTally()
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
                f"[{role}] received non-JSON Codex output "
                "(suppressed)"
            )
            continue

        capacity_exhausted = capacity_exhausted or is_capacity_error(event)
        usage.record(event)
        _handle_json_event(
            event, role=role
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
        usage.summary(role)
    )
    print(
        f"{label.capitalize()} exit code: {return_code}"
    )
    print(
        "========================================\n"
    )

    if capacity_exhausted:
        raise ModelCapacityUnavailable("Codex usage exhausted")
    return return_code


def run_local_planner(
        prompt: str
) -> int:
    return run_codex(
        prompt,
        task_header="PROJECT PLANNING TASK",
        label="hosted Codex planner",
    )


def run_architect(
        prompt: str
) -> int:
    return run_codex(
        prompt,
        task_header="ARCHITECTURE TASK",
        label="hosted Codex architect",
    )
