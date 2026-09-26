"""Read hosted ChatGPT quota without starting a model turn.

Protocol: https://developers.openai.com/codex/app-server (account/rateLimits/read).
Unknown/authentication failures are unavailable, never guessed to be spare quota.
"""
import json
import queue
import subprocess
import threading
import time

from agent.runtime.runners.local_planner_runner import find_codex
from agent.runtime.support.config import REPO_ROOT


def parse_codex_capacity(result):
    bucket = (result.get("rateLimitsByLimitId") or {}).get("codex")
    if bucket is None:
        bucket = result.get("rateLimits")
    if not isinstance(bucket, dict):
        raise ValueError("Missing Codex quota bucket")
    reached = bucket.get("rateLimitReachedType")
    windows = [bucket[key] for key in ("primary", "secondary") if bucket.get(key)]
    if not windows and not reached:
        raise ValueError("Missing Codex quota windows")
    percentages = [window.get("usedPercent") for window in windows]
    if any(type(value) not in (int, float) or not 0 <= value <= 100 for value in percentages):
        raise ValueError("Invalid Codex quota percentage")
    return {
        "available": not reached and all(value < 100 for value in percentages),
        "primary": bucket.get("primary"), "secondary": bucket.get("secondary"),
        "limit_reached": reached,
    }


def quota_available(result):
    return bool(parse_codex_capacity(result)["available"])


def format_codex_capacity(capacity):
    parts = []
    for name in ("primary", "secondary"):
        window = capacity.get(name)
        if not window:
            continue
        minutes = window.get("windowDurationMins")
        duration = f"/{minutes}min" if minutes else ""
        parts.append(f"{name} {window['usedPercent']:g}% used{duration}")
    if capacity.get("limit_reached"):
        parts.append(f"limit reached: {capacity['limit_reached']}")
    return "; ".join(parts) or "quota windows unavailable"


def read_codex_capacity(timeout=30):
    process = subprocess.Popen(
        [find_codex(), "app-server"], cwd=REPO_ROOT, text=True, encoding="utf-8",
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
    )
    messages = queue.Queue()

    def read_output():
        try:
            for line in process.stdout:
                messages.put(line)
        finally:
            messages.put(None)

    reader = threading.Thread(target=read_output, daemon=True)
    reader.start()
    deadline = time.monotonic() + timeout

    def send(message):
        process.stdin.write(json.dumps(message) + "\n")
        process.stdin.flush()

    def response(request_id):
        while True:
            try:
                line = messages.get(timeout=max(0, deadline - time.monotonic()))
            except queue.Empty as exc:
                raise RuntimeError("Codex capacity check timed out") from exc
            if line is None:
                raise RuntimeError("Codex capacity process exited")
            event = json.loads(line)
            if event.get("id") == request_id:
                if "error" in event:
                    raise RuntimeError("Codex capacity endpoint unavailable")
                return event["result"]

    try:
        send({"id": 1, "method": "initialize", "params": {
            "clientInfo": {"name": "gw2_capacity", "version": "1.0"}}})
        response(1)
        send({"method": "initialized"})
        send({"id": 2, "method": "account/rateLimits/read"})
        return parse_codex_capacity(response(2))
    finally:
        if process.poll() is None:
            process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        reader.join(timeout=5)
        process.stdin.close()
        process.stdout.close()


def codex_available(timeout=30):
    """Compatibility boolean gate; the scheduler retains the detailed reading."""
    return bool(read_codex_capacity(timeout)["available"])
