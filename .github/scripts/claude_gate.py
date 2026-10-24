"""Time-of-day start gate for the subscription-backed Claude GitHub Action."""

from __future__ import annotations

import argparse
from datetime import datetime, time
from pathlib import Path
from zoneinfo import ZoneInfo


BERLIN = ZoneInfo("Europe/Berlin")
START = time(17, 35)
END = time(6, 35)


def window_open(now: datetime) -> bool:
    local = now.astimezone(BERLIN).time()
    return local >= START or local <= END


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    allowed = window_open(datetime.now(BERLIN))
    print("Inside Claude start window" if allowed else
          "Outside Claude start window (17:35-06:35 Europe/Berlin)")
    if args.github_output is not None:
        with args.github_output.open("a", encoding="utf-8") as output:
            output.write(f"allowed={'true' if allowed else 'false'}\n")


if __name__ == "__main__":
    main()
