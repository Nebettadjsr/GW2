"""Daily append-only operational log (agent/logs/).

This is operational log output, not authoritative workflow state --
treated like agent/runtime/artifacts/ in that sense (generated, never
a source of truth), but deliberately NOT gitignored: it is meant to be
a committed, permanent historical record of orchestrator decisions.

One file per calendar day, named by date (e.g. agent/logs/2026-09-20.log).
A new day's file is created automatically the first time a line is
written after the date rolls over. An existing day's file is never
truncated or overwritten -- every write opens in append mode.

Two things land here. log_line() records the orchestrator's own
scheduling decisions. start_console_logging() additionally mirrors
everything printed to the terminal -- including Claude's and the Codex
planner's output -- into the same file, so an unattended overnight run
can be read back in full afterwards. The terminal still shows
everything it showed before; the log is an addition, never a
replacement.
"""
import sys
from contextlib import contextmanager
from contextvars import ContextVar
from datetime import datetime

from agent.runtime.support.config import LOGS_DIR


_DEFERRED_WRITES = ContextVar("deferred_daily_log_writes", default=None)


def _today_log_path(now: datetime):
    LOGS_DIR.mkdir(parents=True, exist_ok=True)
    return LOGS_DIR / f"{now.date().isoformat()}.log"


def _write(message: str) -> None:
    deferred = _DEFERRED_WRITES.get()
    if deferred is not None:
        deferred.append(message)
        return
    _write_now(message)


def _write_now(message: str) -> None:
    now = datetime.now()
    path = _today_log_path(now)

    timestamp = now.strftime("%H:%M:%S")

    with path.open("a", encoding="utf-8") as handle:
        handle.write(f"[{timestamp}] {message}\n")


@contextmanager
def defer_log_writes():
    """Buffer this context's log appends until a guarded workspace check ends."""
    buffered = []
    token = _DEFERRED_WRITES.set(buffered)
    try:
        yield
    finally:
        _DEFERRED_WRITES.reset(token)
        parent = _DEFERRED_WRITES.get()
        if parent is not None:
            parent.extend(buffered)
        else:
            for message in buffered:
                _write_now(message)


def log_line(message: str) -> None:
    _write(message)


def print_status(message: str) -> None:
    """Write a transient status line to the terminal only.

    The orchestrator can sit in the same waiting state (capacity
    exhausted, an unresolved user decision) for hours. Those heartbeat
    lines carry no historical value and would bury the transitions that
    do, so they deliberately bypass ConsoleTee by writing to the stream
    it wraps. Only meaningful transitions -- exhaustion first detected,
    capacity available again, orchestration resumed -- go through
    log_line().
    """
    stream = getattr(sys.stdout, "raw", sys.stdout)

    stream.write(message + "\n")
    stream.flush()


class ConsoleTee:
    """A stdout/stderr wrapper that also appends each line to the log.

    Terminal output is written first and unchanged, so console
    behaviour is exactly what it was before this wrapper existed.
    Text is buffered until a newline arrives because print() writes the
    value and its line terminator as two separate calls.
    """

    def __init__(self, stream):
        self._stream = stream
        self._pending = ""

    @property
    def raw(self):
        """The wrapped stream, for output that must stay out of the log."""
        return self._stream

    def write(self, text: str) -> int:
        written = self._stream.write(text)

        self._pending += text

        while "\n" in self._pending:
            line, self._pending = self._pending.split("\n", 1)

            # Blank separator lines carry no information once each
            # logged line is timestamped, and dropping them keeps the
            # transcript readable.
            if line.strip():
                _write(line)

        return written

    def flush(self) -> None:
        self._stream.flush()

    def isatty(self) -> bool:
        return self._stream.isatty()

    def fileno(self) -> int:
        return self._stream.fileno()


def start_console_logging() -> None:
    """Mirror this process's terminal output into today's log file.

    Call once, at the process entry point. Tests that invoke the
    orchestrator directly deliberately do not call this, so they never
    write to the real agent/logs/ directory.
    """
    for name in ("stdout", "stderr"):
        stream = getattr(sys, name)

        if isinstance(stream, ConsoleTee):
            continue

        # Claude's output contains box-drawing characters and emoji.
        # Those raise UnicodeEncodeError when the console or the
        # redirect target is a legacy Windows codepage, which would
        # kill an unattended run mid-story.
        stream.reconfigure(errors="replace")

        setattr(sys, name, ConsoleTee(stream))
