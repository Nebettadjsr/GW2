"""Keep interrupted edits as an artifact without pushing unreviewed code."""

import os
from pathlib import Path
import shutil
import subprocess


def save_recovery(destination: Path, report: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    # Intent-to-add includes new source files in the patch while honoring .gitignore.
    # This runs only on the disposable runner after Claude has failed.
    subprocess.run(["git", "add", "--intent-to-add", "--", "."], check=True)
    patch = subprocess.check_output(["git", "diff", "--binary", "HEAD", "--", "."])
    (destination / "changes.patch").write_bytes(patch)
    base = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    (destination / "base-commit.txt").write_text(base + "\n", encoding="utf-8")
    if report.is_file():
        shutil.copyfile(report, destination / "run-report.md")
    (destination / "README.md").write_text(
        "Interrupted Claude implementation; not accepted or verified.\n\n"
        "Apply changes.patch in an isolated checkout of base-commit.txt, then review\n"
        "the changes and remaining issue/PR findings before resuming.\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    destination = Path(os.environ["RUNNER_TEMP"]) / "claude-recovery"
    save_recovery(destination, Path(os.environ["GW2_RUN_REPORT"]))
    with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as summary:
        summary.write(
            f"\nClaude failed on issue #{os.environ['ISSUE_NUMBER']}. "
            "Download the claude-recovery artifact to recover interrupted edits. "
            "The PR was not published and no automatic retry was started.\n"
        )
