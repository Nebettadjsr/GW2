"""Shared local cooldown for independent model capacity probes."""
import time
import subprocess

from agent.runtime.support.config import MODEL_CAPACITY_RECHECK_SECONDS


class ModelCapacityUnavailable(RuntimeError):
    """Usage exhaustion is a scheduling event, not a failed work result."""


class CapacityProbe:
    def __init__(self, read_available, recheck_seconds=MODEL_CAPACITY_RECHECK_SECONDS,
                 clock=time.monotonic):
        self.read_available = read_available
        self.recheck_seconds = recheck_seconds
        self.clock = clock
        self.retry_at = 0

    def defer(self):
        self.retry_at = self.clock() + self.recheck_seconds

    def available(self):
        if self.clock() < self.retry_at:
            return False
        try:
            available = self.read_available()
        except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as exc:
            print(f"Capacity check unavailable ({type(exc).__name__}); retrying locally later.")
            available = False
        if not available:
            self.defer()
        return available
