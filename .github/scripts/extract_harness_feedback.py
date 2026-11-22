#!/usr/bin/env python3
"""Turn the `## Harness feedback` section of a run report into a harness-feedback issue body.

The programmer and the planner never change the harness; they report friction, a missing rule or an
unclear instruction in this section (or `none`). A supervisor triages the resulting issue.

Environment: RUN_REPORT (report file), DISCARDED_FILE (list of discarded harness changes, may be
empty), STORY_ISSUE, PR_URL, RUN_URL, OUTPUT_FILE (issue body, written only when there is something).
Prints `create` when an issue should be opened, otherwise `skip`.
"""
import os
import re
import sys
from pathlib import Path

NONE_VALUES = {"", "none", "none.", "n/a", "-", "keine", "no harness feedback"}
# Standalone status lines the report contract puts after its sections (see claude.yml); they are
# never part of a section's content, even when the section is the last one.
STATUS_LINE = re.compile(r"^(Local-only checks still needed|Unresolved gaps):", re.IGNORECASE)


def feedback_section(report: str) -> str:
    heading = re.search(r"^##+ Harness feedback\s*$", report, re.MULTILINE)
    if not heading:
        return ""
    section = re.split(r"^##+ ", report[heading.end():], maxsplit=1, flags=re.MULTILINE)[0]
    kept = []
    for line in section.splitlines():
        if STATUS_LINE.match(line.strip()):
            break
        kept.append(line)
    return "\n".join(kept).strip()


def is_none(text: str) -> bool:
    return re.sub(r"[\s*_`>-]+$|^[\s*_`>-]+", "", text.lower()) in NONE_VALUES


def build_body(feedback: str, discarded: str, story_issue: str, pr_url: str, run_url: str) -> str:
    lines = [f"From story #{story_issue}" + (f", {pr_url}" if pr_url else "") + (f", run {run_url}" if run_url else "") + ".", ""]
    if feedback and not is_none(feedback):
        lines += ["## Reported by the agent", "", feedback, ""]
    if discarded.strip():
        lines += ["## Harness changes the agent attempted (discarded)", "", discarded.strip(), ""]
    lines += ["Triage as supervisor or owner: decide whether a rule, a machine check, a tool or an instruction "
              "should change. The agent never changes the harness itself. Prefer a deterministic check over "
              "prose, and make each correction its own pull request with this evidence.", ""]
    return "\n".join(lines)


def main() -> int:
    report = Path(os.environ["RUN_REPORT"])
    feedback = feedback_section(report.read_text(encoding="utf-8")) if report.is_file() else ""
    discarded_file = Path(os.environ.get("DISCARDED_FILE", ""))
    discarded = discarded_file.read_text(encoding="utf-8") if discarded_file.is_file() else ""
    if is_none(feedback) and not discarded.strip():
        print("skip")
        return 0
    body = build_body(feedback, discarded, os.environ.get("STORY_ISSUE", "?"),
                      os.environ.get("PR_URL", ""), os.environ.get("RUN_URL", ""))
    Path(os.environ["OUTPUT_FILE"]).write_text(body, encoding="utf-8")
    print("create")
    return 0


if __name__ == "__main__":
    sys.exit(main())
