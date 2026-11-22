#!/usr/bin/env python3
"""Discard harness changes made by a story run and list what was discarded.

Story runs work only on the GW2 application. Whatever the agent changed under the harness paths
(harness_paths.OFF_LIMITS_*) is restored to the state the run started from (BASE) and listed, so the
rest of the work stays publishable and the attempt is visible instead of silently ignored.

Environment: DISCARD_BASE (commit the run started from), DISCARD_REPORT (output file, Markdown list).
Run from the repository root. Exit status is 0 unless git itself fails.
"""
import os
import subprocess
import sys
from pathlib import Path

from harness_paths import is_off_limits


def git(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], capture_output=True, text=True, check=check)


def changed_paths(base: str) -> list[str]:
    """Paths whose working tree differs from BASE: tracked changes, deletions, new and untracked files."""
    tracked = git("diff", "--name-only", "-z", "--no-renames", base).stdout.split("\0")
    untracked = git("ls-files", "-o", "--exclude-standard", "-z").stdout.split("\0")
    return sorted({path for path in tracked + untracked if path})


def exists_in(base: str, path: str) -> bool:
    return git("cat-file", "-e", f"{base}:{path}", check=False).returncode == 0


def discard(base: str) -> list[str]:
    discarded = []
    for path in changed_paths(base):
        if not is_off_limits(path):
            continue
        if exists_in(base, path):
            git("checkout", base, "--", path)
        else:
            git("rm", "-f", "--cached", "-q", "--ignore-unmatch", "--", path, check=False)
            target = Path(path)
            if target.is_file() or target.is_symlink():
                target.unlink()
        discarded.append(path)
    return discarded


def main() -> int:
    base = os.environ["DISCARD_BASE"]
    report = Path(os.environ["DISCARD_REPORT"])
    discarded = discard(base)
    if discarded:
        lines = ["These files belong to the harness (workflows, agent instructions, quality gates). Story runs",
                 "work only on the GW2 application, so the changes were discarded:", ""]
        lines += [f"- `{path}`" for path in discarded]
        report.write_text("\n".join(lines) + "\n", encoding="utf-8")
        sys.stdout.write("Discarded out-of-scope changes:\n" + "\n".join(f"  {p}" for p in discarded) + "\n")
    else:
        report.write_text("", encoding="utf-8")
        sys.stdout.write("No out-of-scope changes.\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
