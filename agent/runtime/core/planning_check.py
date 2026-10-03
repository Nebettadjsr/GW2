"""The planner's own pre-flight check on what it is about to hand back.

A planning pass used to learn its result was invalid only after it had ended.
In one real run the planner wrote `"outcome": "DEFERRED"` -- a word that belongs
to the follow-up-finding vocabulary, not to `phase_review` -- noticed something
was wrong, attempted a blind string replacement from inside a PowerShell
here-string, got the escaping wrong, exited 0 believing it had fixed it, and
only then did the harness reject the pass. By that point the planner had already
created four story files and rewritten `## To Do`.

Two things follow, and both are implemented rather than asked for:

1. The planner is handed this check in its prompt and told to run it until it
   passes. It is the same validation the harness applies afterwards, so a pass
   here means the result will be accepted; there is no second, stricter gate to
   be surprised by.
2. Failing it is still fatal (`project_planner._run_guarded_planner` rolls the
   whole pass back), so skipping the check cannot produce a half-applied plan.

Run from the repository root:

    python -c "import sys; sys.path.insert(0,'.'); \\
from agent.runtime.core.planning_check import main; sys.exit(main())"

Exits 0 and prints `PLANNING OUTPUT OK`, or exits 1 and prints one problem per
line. It only ever reads; nothing here edits the result or the backlog.
"""
import json

from agent.runtime.support.config import BACKLOG_FILE, PLANNING_RESULT_FILE
from agent.runtime.core.story_state import validate_backlog_entries


def check() -> list[str]:
    """Everything the harness will reject this pass for, as far as it can be
    known before the pass ends."""

    problems = []

    if not PLANNING_RESULT_FILE.exists():
        return [
            f"{PLANNING_RESULT_FILE.name} does not exist yet; write the "
            "required result before finishing."
        ]

    try:
        result = json.loads(
            PLANNING_RESULT_FILE.read_text(encoding="utf-8")
        )
    except (OSError, ValueError) as error:
        return [f"{PLANNING_RESULT_FILE.name} is not readable JSON: {error}"]

    if not isinstance(result, dict):
        return [f"{PLANNING_RESULT_FILE.name} must contain a JSON object."]

    # Imported here, not at module import time: project_planner imports a good
    # deal of the runtime, and this module must stay runnable as a one-liner.
    from agent.runtime.core.project_planner import (
        REVIEW_OUTCOMES,
        PLANNING_STATUSES,
    )

    status = result.get("status")

    if status not in PLANNING_STATUSES:
        problems.append(
            f"Invalid status: {status!r}. Accepted: "
            + ", ".join(PLANNING_STATUSES)
            + "."
        )

    review = result.get("phase_review")

    if not isinstance(review, list) or not review:
        problems.append(
            "phase_review must be a non-empty list of reviewed areas."
        )
    else:
        for entry in review:
            if not isinstance(entry, dict):
                problems.append("phase_review entries must be objects.")
                continue

            outcome = entry.get("outcome")

            if outcome not in REVIEW_OUTCOMES:
                problems.append(
                    f"Invalid phase_review outcome: {outcome!r} "
                    f"(area {entry.get('area')!r}). Accepted: "
                    + ", ".join(REVIEW_OUTCOMES)
                    + ". Note DEFERRED is a follow-up-finding disposition, "
                      "not a phase_review outcome."
                )

    if BACKLOG_FILE.exists():
        problems.extend(
            validate_backlog_entries(
                BACKLOG_FILE.read_text(encoding="utf-8")
            )
        )

    return problems


def main() -> int:
    problems = check()

    if not problems:
        print("PLANNING OUTPUT OK")
        return 0

    for problem in problems:
        print(problem)

    return 1
