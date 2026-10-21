"""Registry of the agent processes the workflow started, so they can be stopped by identity.

Claude and Codex runs are recorded as ``pid`` + OS start time. ``abort_all`` kills
each registered process tree; ``sweep_stale`` does the same at startup for runs
left behind by an earlier service process. Processes are never found by name or
command line, so a human's own interactive sessions are not touched, and git
operations are not registered, so an abort never interrupts a commit or push.
"""

from __future__ import annotations

import json
import os
import signal
import subprocess
import threading
from datetime import datetime, timezone
from pathlib import Path

START_TOLERANCE_SECONDS = 1


class StepAborted(BaseException):
    """An operator abort killed the process this step was waiting on.

    Deliberately not an ``Exception``: the step must not be recorded as a
    failure, spend a retry budget, or be swallowed by a broad ``except``.
    """


_lock = threading.RLock()
_entries: dict[int, dict] = {}
_aborted: set[int] = set()
_path: Path | None = None


def configure(path: Path | None) -> None:
    """Persist the registry at ``path`` (None keeps it in memory only)."""
    global _path
    with _lock:
        _path = path
        _entries.clear()
        _aborted.clear()


def _start_time(pid: int) -> float | None:
    try:
        import psutil
        return psutil.Process(pid).create_time()
    except Exception:
        return None


def _alive(pid: int) -> bool:
    try:
        import psutil
        return psutil.pid_exists(pid)
    except Exception:
        return False


def _save() -> None:
    if _path is None:
        return
    try:
        _path.parent.mkdir(parents=True, exist_ok=True)
        temporary = _path.with_suffix(_path.suffix + ".tmp")
        temporary.write_text(json.dumps(list(_entries.values())), encoding="utf-8")
        os.replace(temporary, _path)
    except OSError:
        pass  # visibility only; the in-memory registry still drives an abort


def register(pid: int, role: str) -> None:
    with _lock:
        _entries[pid] = {"pid": pid, "role": role, "started": _start_time(pid),
                         "registered_at": datetime.now(timezone.utc).isoformat(timespec="seconds")}
        _save()


def finish(pid: int) -> None:
    """The process has ended: unregister it, and raise ``StepAborted`` if an abort killed it."""
    with _lock:
        _entries.pop(pid, None)
        aborted = pid in _aborted
        _aborted.discard(pid)
        _save()
    if aborted:
        raise StepAborted(f"Process {pid} was stopped by an operator abort.")


def entries() -> list[dict]:
    with _lock:
        return [dict(entry) for entry in _entries.values()]


def read(path: Path) -> list[dict]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return []
    return [entry for entry in data if isinstance(entry, dict) and isinstance(entry.get("pid"), int)]


def _kill_tree(pid: int) -> None:
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True, check=False)
    else:
        try:
            os.kill(pid, signal.SIGKILL)
        except OSError:
            pass


def _is_same_process(entry: dict) -> bool:
    """True only when the pid is alive and provably the process that was registered."""
    pid, started = entry["pid"], entry.get("started")
    if not _alive(pid):
        return False
    current = _start_time(pid)
    return started is not None and current is not None and abs(current - started) <= START_TOLERANCE_SECONDS


def abort_all() -> list[dict]:
    """Kill every registered process tree; the waiting steps then raise ``StepAborted``."""
    with _lock:
        victims = [dict(entry) for entry in _entries.values()]
        _aborted.update(entry["pid"] for entry in victims)
    for entry in victims:
        _kill_tree(entry["pid"])
    return victims


def sweep_stale(path: Path) -> list[dict]:
    """Kill processes an earlier service left registered in ``path``; reused pids are left alone."""
    killed = []
    for entry in read(path):
        if _is_same_process(entry):
            _kill_tree(entry["pid"])
            killed.append(entry)
    try:
        path.write_text("[]", encoding="utf-8")
    except OSError:
        pass
    return killed
