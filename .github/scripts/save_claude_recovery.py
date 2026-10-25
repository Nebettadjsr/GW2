"""Preserve implementation and publication failures without pushing unreviewed code."""

import os
from pathlib import Path
import shutil
import subprocess


def save_recovery(destination: Path, report: Path, base_ref: str | None = None) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    # The workflow supplies the published head from before preparation/implementation, so a
    # publication failure after git commit still includes all work (and any merged prerequisites).
    base = subprocess.check_output(
        ["git", "rev-parse", "--verify", f"{base_ref or 'HEAD'}^{{commit}}"], text=True
    ).strip()
    # Intent-to-add includes new source files in the patch while honoring .gitignore.
    # This runs only on the disposable runner after implementation or publication has failed.
    subprocess.run(["git", "add", "--intent-to-add", "--", "."], check=True)
    patch = subprocess.check_output(["git", "diff", "--binary", base, "--", "."])
    (destination / "changes.patch").write_bytes(patch)
    (destination / "base-commit.txt").write_text(base + "\n", encoding="utf-8")
    if report.is_file():
        shutil.copyfile(report, destination / "run-report.md")
    (destination / "README.md").write_text(
        "Interrupted Claude implementation/publication; not accepted or verified.\n\n"
        "Apply changes.patch in an isolated checkout of base-commit.txt, then review\n"
        "the changes and remaining issue/PR findings before resuming. The patch includes\n"
        "committed and uncommitted changes since the published base, including prerequisite merges.\n"
        "Check the remote branch/PR first: publication may have partially succeeded.\n"
        "Workflow-file changes need a publisher with workflows permission; no automatic retry occurs.\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    destination = Path(os.environ["RUNNER_TEMP"]) / "claude-recovery"
    save_recovery(destination, Path(os.environ["GW2_RUN_REPORT"]), os.environ.get("CLAUDE_RECOVERY_BASE"))
    with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as summary:
        summary.write(
            f"\nClaude run failed during {os.environ.get('RECOVERY_STAGE', 'implementation/publication')} "
            f"on issue #{os.environ['ISSUE_NUMBER']}. "
            "Download the claude-recovery artifact to recover interrupted edits. "
            "Check the remote branch/PR before applying: publication may have partially succeeded. "
            "Workflow-file publication requires workflows permission. No automatic retry was started.\n"
        )
