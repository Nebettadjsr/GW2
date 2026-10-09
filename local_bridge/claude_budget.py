"""How much of Claude's weekly limit the pipeline may have used by now.

The week (from the weekly reset reported by ``claude -p /usage``) is split into
seven daily shares. Each share unlocks at ``unlock_time`` on its day and stays
available until the weekly reset, so unused shares carry over. With the
defaults a share is 10 windows / 7 days, and one full 5h window is 10 % of the
weekly limit: on Wednesday after 17:30, five shares (~71 %) are unlocked.

In ``manual`` mode ``manual_cap_percent`` replaces the computed cap. The 5h
session limit is checked separately so Claude is never started near its end.
"""

from __future__ import annotations

import re
from datetime import datetime, time, timedelta

from agent.runtime.runners.claude_runner import parse_claude_usage, read_claude_usage_text
from agent.runtime.support.config import CLAUDE_USAGE_LIMIT_PERCENT

DEFAULTS = {"mode": "auto", "allowance_windows": 10, "windows_per_full_week": 10,
            "unlock_time": "17:30", "manual_cap_percent": 100}
_WEEKLY_RESET = re.compile(
    r"^Current week[^\n]*?resets\s+(?:([A-Za-z]{3})[a-z]*\.?\s+(\d{1,2}),\s*)?"
    r"(\d{1,2})(?::(\d{2}))?\s*(am|pm)", re.IGNORECASE | re.MULTILINE)


def settings_from(raw: dict | None) -> dict:
    """Validated settings from the n8n data-table row; missing fields use the defaults."""
    settings = dict(DEFAULTS)
    settings.update({key: value for key, value in (raw or {}).items()
                     if key in DEFAULTS and value not in (None, "")})
    settings["mode"] = str(settings["mode"]).strip().lower()
    if settings["mode"] not in ("auto", "manual"):
        raise ValueError("Claude budget mode must be 'auto' or 'manual'.")
    for key in ("allowance_windows", "windows_per_full_week", "manual_cap_percent"):
        settings[key] = float(settings[key])
    if settings["allowance_windows"] < 0 or settings["windows_per_full_week"] <= 0:
        raise ValueError("allowance_windows must be >= 0 and windows_per_full_week > 0.")
    if not 0 <= settings["manual_cap_percent"] <= 100:
        raise ValueError("manual_cap_percent must be between 0 and 100.")
    if not re.fullmatch(r"([01]?\d|2[0-3]):[0-5]\d", str(settings["unlock_time"]).strip()):
        raise ValueError("unlock_time must look like 17:30.")
    settings["unlock_time"] = str(settings["unlock_time"]).strip()
    return settings


def weekly_reset(usage_text: str, now: datetime) -> datetime:
    """The next weekly reset as printed by /usage (local time), else next Saturday 07:00."""
    match = _WEEKLY_RESET.search(usage_text)
    if match:
        month, day, hour, minute, meridiem = match.groups()
        hour = int(hour) % 12 + (12 if meridiem.lower() == "pm" else 0)
        clock = time(hour, int(minute or 0))
        if month:
            month_number = datetime.strptime(month[:3].title(), "%b").month
            candidates = [datetime.combine(datetime(year, month_number, int(day)).date(), clock)
                          for year in (now.year - 1, now.year, now.year + 1)]
            return min(candidates, key=lambda candidate: abs(candidate - now))
        reset = datetime.combine(now.date(), clock)
        return reset if reset > now else reset + timedelta(days=1)
    days_ahead = (5 - now.weekday()) % 7
    reset = datetime.combine(now.date() + timedelta(days=days_ahead), time(7))
    return reset if reset > now else reset + timedelta(days=7)


def weekly_cap(settings: dict, now: datetime, next_reset: datetime) -> tuple[float, int, datetime]:
    """(allowed weekly-used %, shares unlocked, next unlock) for auto mode."""
    week_start = next_reset - timedelta(days=7)
    hour, minute = map(int, settings["unlock_time"].split(":"))
    unlocks = [datetime.combine(week_start.date() + timedelta(days=offset), time(hour, minute))
               for offset in range(15)]
    unlocks = [moment for moment in unlocks if moment >= week_start]
    shares = sum(1 for moment in unlocks if moment <= now and moment < next_reset)
    next_unlock = next(moment for moment in unlocks if moment > now)
    per_share = settings["allowance_windows"] / 7 * 100 / settings["windows_per_full_week"]
    return min(100.0, shares * per_share), shares, next_unlock


def status(raw_settings: dict | None, now: datetime | None = None, usage_text: str | None = None) -> dict:
    """Current usage, the cap in force, and whether Claude may start now."""
    settings = settings_from(raw_settings)
    now = now or datetime.now()
    text = usage_text if usage_text is not None else read_claude_usage_text()
    usage = parse_claude_usage(text)
    next_reset = weekly_reset(text, now)
    cap, shares, next_unlock = weekly_cap(settings, now, next_reset)
    if settings["mode"] == "manual":
        cap = settings["manual_cap_percent"]
    session_ok = usage.session_used_percent < CLAUDE_USAGE_LIMIT_PERCENT
    weekly_ok = usage.weekly_used_percent < cap
    if not weekly_ok:
        reason = (f"Weekly Claude budget reached: {usage.weekly_used_percent}% used of {cap:.0f}% allowed "
                  f"({settings['mode']}). " + (f"Next share unlocks {next_unlock:%a %H:%M}."
                                               if settings["mode"] == "auto" else "Raise manual_cap_percent or switch to auto."))
    elif not session_ok:
        reason = (f"Claude 5h session is {usage.session_used_percent}% used "
                  f"(starts only below {CLAUDE_USAGE_LIMIT_PERCENT}%); waiting for the session to reset.")
    else:
        reason = f"Claude may run: {usage.weekly_used_percent}% of {cap:.0f}% weekly budget used."
    return {
        "mode": settings["mode"],
        "claude_allowed": session_ok and weekly_ok,
        "weekly_used_percent": usage.weekly_used_percent,
        "weekly_cap_percent": round(cap, 1),
        "shares_unlocked": shares,
        "next_unlock": next_unlock.isoformat(timespec="minutes"),
        "weekly_reset": next_reset.isoformat(timespec="minutes"),
        "session_used_percent": usage.session_used_percent,
        "session_limit_percent": CLAUDE_USAGE_LIMIT_PERCENT,
        "reason": reason,
    }
