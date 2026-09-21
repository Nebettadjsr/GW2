"""Daily append-only operational log (agent/logs/).

This is operational log output, not authoritative workflow state --
treated like agent/runtime/artifacts/ in that sense (generated, never
a source of truth), but deliberately NOT gitignored: it is meant to be
a committed, permanent historical record of orchestrator decisions.

One file per calendar day, named by date (e.g. agent/logs/2026-09-20.log).
A new day's file is created automatically the first time log_line() is
called after the date rolls over. An existing day's file is never
truncated or overwritten -- every write opens in append mode.
"""
from datetime import datetime

from agent.runtime.support.config import LOGS_DIR


def _today_log_path(now: datetime):
    LOGS_DIR.mkdir(parents=True, exist_ok=True)
    return LOGS_DIR / f"{now.date().isoformat()}.log"


def log_line(message: str) -> None:
    now = datetime.now()
    path = _today_log_path(now)

    timestamp = now.strftime("%H:%M:%S")

    with path.open("a", encoding="utf-8") as handle:
        handle.write(f"[{timestamp}] {message}\n")
