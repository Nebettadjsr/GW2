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


def quota_available(result):
    bucket = (result.get("rateLimitsByLimitId") or {}).get("codex")
    if bucket is None:
        bucket = result.get("rateLimits")
    if not isinstance(bucket, dict):
        raise ValueError("Missing Codex quota bucket")
    if bucket.get("rateLimitReachedType"):
        return False
    windows = [bucket[key] for key in ("primary", "secondary") if bucket.get(key)]
    if not windows:
        raise ValueError("Missing Codex quota windows")
    percentages = [window.get("usedPercent") for window in windows]
    if any(type(value) not in (int, float) or not 0 <= value <= 100 for value in percentages):
        raise ValueError("Invalid Codex quota percentage")
    return all(value < 100 for value in percentages)


def codex_available(timeout=30):
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
        return quota_available(response(2))
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
