"""Python scheduling and step loop for the workflow service.

Run with ``python -m workflow_service.control``. The HTTP status API and budget
monitor share its process and Store.
"""

from __future__ import annotations

import argparse
import logging
import os
import threading
from datetime import datetime
from pathlib import Path

from workflow_service.service import ACTIVE_STATUSES, WorkflowService, Busy, main, utc_now

MAX_STEPS = 200


class StateLease:
    """OS-held exclusive state-file lease, released even after process death."""

    def __init__(self, state_file: Path):
        self.path = state_file.with_suffix(state_file.suffix + ".lock")
        self.file = None

    def acquire(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.file = self.path.open("a+b")
        self.file.seek(0, os.SEEK_END)
        if self.file.tell() == 0:
            self.file.write(b"0")
            self.file.flush()
        self.file.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(self.file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            self.file.close()
            self.file = None
            raise RuntimeError(f"Another service owns {self.path}; stop it before starting this one.") from error

    def close(self) -> None:
        if self.file is not None:
            self.file.close()
            self.file = None


def scheduled_slot(now: datetime) -> str | None:
    """Start hourly at :35, 17:35 through 06:35, local time."""
    if now.minute == 35 and (now.hour >= 17 or now.hour <= 6):
        return now.strftime("%Y-%m-%dT%H:%M%z")
    return None


class WorkflowControl:
    def __init__(self, service: WorkflowService, *, clock=None, poll_seconds: float = 1):
        self.service = service
        self.store = service.store
        self.clock = clock or (lambda: datetime.now().astimezone())
        self.poll_seconds = poll_seconds

    def record(self, **fields) -> None:
        with self.store.lock:
            self.store.control.update(fields, updated_at=utc_now())
            self.store.save()

    def begin(self) -> None:
        self.record(active=True, status="running", started_at=utc_now(), finished_at=None,
                    steps=0, task_id=None, outcome=None, reason=None)

    def finish(self, outcome: str, reason: str = "") -> None:
        self.record(active=False, status="stopped", finished_at=utc_now(), outcome=outcome, reason=reason)
        logging.info("Workflow run stopped: %s%s", outcome, f" -- {reason}" if reason else "")

    def run(self, stop: threading.Event) -> None:
        """Drain a run until a step stops it; checkpoints survive service restarts."""
        while True:
            task_id = self.store.control.get("task_id")
            task = self.store.get(task_id) if task_id else None
            if task and task["status"] in ACTIVE_STATUSES:
                # Finish the current operation even after a graceful stop request.
                threading.Event().wait(self.poll_seconds)
                continue
            if self.service.restarting:
                if stop.wait(self.poll_seconds):
                    return
                continue
            if task and task["status"] != "interrupted":
                result = task.get("result") or {}
                if task["status"] != "completed" or result.get("proceed") is not True:
                    self.finish(result.get("outcome", "error"), task.get("error") or result.get("reason", ""))
                    return
            if stop.is_set():
                return
            # An interrupted task retries through the engine's existing recovery;
            # it is an execution attempt and still counts toward the run limit.
            if self.store.control.get("steps", 0) >= MAX_STEPS:
                self.finish("loop_guard", f"Stopped after {MAX_STEPS} steps; inspect the saved state before restarting.")
                return
            try:
                task = self.service.next_step()
            except Busy:
                if stop.wait(self.poll_seconds):
                    return
                continue
            # Store.create checkpoints the task identity and attempt count in
            # the same write as the task, before any agent starts.
            if self.store.control.get("task_id") != task["id"]:
                self.record(task_id=task["id"])

    def serve(self, stop: threading.Event, *, run_now=False, once=False, finished=None) -> None:
        self.service.automatic_execution = True
        self.record(enabled=True)
        immediate = run_now
        try:
            while not stop.is_set():
                slot = scheduled_slot(self.clock())
                due = slot is not None and slot != self.store.control.get("last_schedule_slot")
                # Consume a busy schedule tick without queuing a second run.
                if due:
                    self.record(last_schedule_slot=slot)
                if self.store.control.get("active") or immediate or due:
                    if not self.store.control.get("active"):
                        self.begin()
                    immediate = False
                    try:
                        self.run(stop)
                    except Exception as error:
                        logging.exception("Workflow driver failed; inspect the saved state before retrying")
                        self.finish("driver_error", f"{type(error).__name__}: {error}")
                    # A long run may have crossed this minute's scheduled start.
                    slot = scheduled_slot(self.clock())
                    if slot:
                        self.record(last_schedule_slot=slot)
                    if once:
                        return
                if stop.wait(self.poll_seconds):
                    return
        finally:
            self.record(enabled=False)
            if once and finished is not None:
                finished()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--now", action="store_true", help="Run immediately, then keep the nightly schedule.")
    group.add_argument("--once", action="store_true", help="Run immediately and exit when the run stops.")
    args = parser.parse_args()
    main(run_workflow=True, run_now=args.now, once=args.once)
