"""Fail-closed start gate for the subscription-backed Claude GitHub Action.

The old workflow used the same Berlin start window, 90% session ceiling, and
daily-unlocking shares of the weekly allowance. This script has no dependency
on the retired workflow service. It never starts Claude or reads credentials.
"""

from __future__ import annotations

import argparse
from datetime import datetime, time, timedelta
from pathlib import Path
import re
from zoneinfo import ZoneInfo


BERLIN = ZoneInfo("Europe/Berlin")
SESSION_CEILING = 90
START = time(17, 35)
END = time(6, 35)
UNLOCK = time(17, 30)
SESSION = re.compile(r"^Current session:\s*(\d+)% used", re.I | re.M)
WEEKLY = re.compile(r"^Current week[^\n]*?:\s*(\d+)% used", re.I | re.M)
RESET = re.compile(
    r"^Current week[^\n]*?resets\s+([A-Za-z]{3})\s+(\d{1,2}),\s*"
    r"(\d{1,2})(?::(\d{2}))?\s*(am|pm)", re.I | re.M
)


def window_open(now: datetime) -> bool:
    local = now.astimezone(BERLIN).time()
    return local >= START or local <= END


def parse_usage(value: str, now: datetime) -> tuple[int, int, datetime]:
    session = SESSION.search(value)
    weeks = WEEKLY.findall(value)
    reset = RESET.search(value)
    if not session or not weeks or not reset:
        raise ValueError("Claude /usage omitted a session, weekly percentage, or dated reset")

    month, day, hour, minute, meridiem = reset.groups()
    month_number = datetime.strptime(month.title(), "%b").month
    hour_number = int(hour) % 12 + (12 if meridiem.lower() == "pm" else 0)
    local_now = now.astimezone(BERLIN)
    candidates = [
        datetime(year, month_number, int(day), hour_number, int(minute or 0), tzinfo=BERLIN)
        for year in (local_now.year - 1, local_now.year, local_now.year + 1)
    ]
    next_reset = min((date for date in candidates if date > local_now), default=None)
    if next_reset is None:
        raise ValueError("Claude /usage weekly reset is not in the future")
    if next_reset - local_now > timedelta(days=8):
        raise ValueError("Claude /usage weekly reset is too far away")
    return int(session.group(1)), max(map(int, weeks)), next_reset


def weekly_cap(now: datetime, next_reset: datetime) -> float:
    """Unlock one seventh of the weekly allowance daily at 17:30 Berlin time."""
    local_now = now.astimezone(BERLIN)
    week_start = next_reset - timedelta(days=7)
    unlocked = sum(
        1
        for offset in range(8)
        if week_start <= datetime.combine(
            week_start.date() + timedelta(days=offset), UNLOCK, BERLIN
        ) <= local_now
    )
    return min(100.0, unlocked * 100.0 / 7)


def usage_allowed(value: str, now: datetime) -> tuple[bool, str]:
    try:
        session, week, reset = parse_usage(value, now)
    except ValueError as error:
        return False, str(error)
    cap = weekly_cap(now, reset)
    if session >= SESSION_CEILING:
        return False, f"Claude session at {session}% (starts require < {SESSION_CEILING}%)"
    if week >= cap:
        return False, f"Claude week at {week}% (unlocked cap {cap:.1f}%)"
    return True, f"Claude usage permits start: session {session}%, week {week}% below {cap:.1f}% cap"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("phase", choices=("window", "usage"))
    parser.add_argument("--usage-file", type=Path)
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    now = datetime.now(BERLIN)
    if args.phase == "window":
        allowed = window_open(now)
        reason = "Inside Claude start window" if allowed else "Outside Claude start window (17:35-06:35 Europe/Berlin)"
    else:
        if args.usage_file is None:
            parser.error("usage phase requires --usage-file")
        allowed, reason = usage_allowed(args.usage_file.read_text(encoding="utf-8"), now)
    print(reason)
    if args.github_output is not None:
        with args.github_output.open("a", encoding="utf-8") as output:
            output.write(f"allowed={'true' if allowed else 'false'}\n")


if __name__ == "__main__":
    main()
