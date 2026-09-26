from pathlib import Path
import re

from agent.runtime.support.config import ARCHITECT_REQUESTS_DIR
from agent.runtime.human.user_decisions import (
    extract_section,
    list_decisions,
)


# ============================================================
# Architect request inbox (agent/architect-requests/)
#
# One file per architecture question the planner is not authorized to
# decide (AR-<NUMBER>-<slug>.md). The planner creates them and must
# never answer them itself; ARCHITECTURE MODE
# (agent/ARCHITECT_INSTRUCTIONS.md) is the only role that resolves one.
#
# This module only reads and classifies what is already on disk. It
# decides nothing about architecture -- it answers exactly one
# scheduling question for the orchestrator: which requests, if any, the
# architect should currently be invoked for.
#
# Lifecycle (deliberately the same three words the Product Owner inbox
# uses -- there is no second human-decision mechanism here):
#
#   OPEN        needs the architect
#   NEEDS_USER  the architect escalated it; blocked on its own
#               agent/user-decisions/UD-*.md file(s)
#   RESOLVED    answered; never processed again
#
# A NEEDS_USER request becomes actionable again by itself the moment
# every UD it names is RESOLVED -- that is what makes the architect
# resume automatically after a human answers, with no extra state.
#
# README.md documents the format for a human reader and is never itself
# a request.
# ============================================================

REQUEST_TITLE_PATTERN = re.compile(
    r"^#\s*(AR-\d+)\s*(?:[—-]\s*(.*))?\s*$",
    re.MULTILINE,
)

STATUS_PATTERN = re.compile(
    r"##\s*Status[ \t]*\n+[ \t]*([^\n]*)",
    re.IGNORECASE,
)

CANONICAL_STATUSES = ("OPEN", "NEEDS_USER", "RESOLVED")

# A request re-queued by hand is a normal, expected operation: a human
# who wants the architect to look again writes something in the Status
# section and expects it to be picked up. "TODO" in particular is what
# the rest of this repository uses for "not done yet" (story files, and
# this file's own placeholder sections), so it is read as OPEN rather
# than silently ignored. Anything still unrecognized is reported, never
# skipped quietly -- see undispatchable_requests().
OPEN_STATUS_ALIASES = {
    "TODO",
    "TO DO",
    "NEW",
    "PENDING",
    "REOPENED",
    "OPEN AGAIN",
}

DECISION_ID_PATTERN = re.compile(
    r"\bUD-\d+\b",
    re.IGNORECASE,
)

REQUIRED_REQUEST_HEADINGS = [
    "Status",
    "Architecture Question",
    "Context and Constraints",
    "Authoritative References",
    "Blocked Work",
    "Blocking User Decision",
    "Architect Decision",
    "Resolution",
]

ANSWER_SECTIONS = [
    "Architect Decision",
    "Resolution",
]

PLACEHOLDER_VALUES = {
    "todo",
    "none",
    "n/a",
    "",
}


def section(content: str, heading: str) -> str | None:
    """One '## <heading>' body from a request file, or None if absent."""

    return extract_section(content, heading)


def extract_request_id(content: str) -> str | None:
    match = REQUEST_TITLE_PATTERN.search(content)

    return match.group(1).upper() if match else None


def extract_title(content: str) -> str | None:
    match = REQUEST_TITLE_PATTERN.search(content)

    if match and match.group(2):
        return match.group(2).strip()

    return None


def extract_raw_status(content: str) -> str | None:
    """The Status section's first line exactly as written, or None."""

    match = STATUS_PATTERN.search(content)

    if not match:
        return None

    raw = match.group(1).strip()

    return raw or None


def extract_status(content: str) -> str | None:
    """
    The lifecycle status, normalized. Returns None for a value outside
    the vocabulary: an unrecognized status is never guessed into a
    dispatch decision, it is reported instead.
    """

    raw = extract_raw_status(content)

    if raw is None:
        return None

    normalized = raw.strip().strip(".").upper()

    if normalized in CANONICAL_STATUSES:
        return normalized

    if normalized in OPEN_STATUS_ALIASES:
        return "OPEN"

    return None


def is_placeholder(section_text: str | None) -> bool:
    if section_text is None:
        return True

    return (
        section_text.strip().strip(".").lower()
        in PLACEHOLDER_VALUES
    )


def blocking_decision_ids(content: str) -> list[str]:
    """
    The UD IDs a NEEDS_USER request is waiting on, read from its own
    '## Blocking User Decision' section only. Never guessed from prose
    elsewhere in the file: a request that mentions UD-003 as background
    context is not blocked by it.
    """

    blocking = section(content, "Blocking User Decision") or ""

    if is_placeholder(blocking):
        return []

    return sorted(
        {
            match.group(0).upper()
            for match in DECISION_ID_PATTERN.finditer(blocking)
        }
    )


def missing_required_headings(content: str) -> list[str]:
    return [
        heading
        for heading in REQUIRED_REQUEST_HEADINGS
        if not re.search(
            rf"^##\s*{re.escape(heading)}\s*$",
            content,
            re.IGNORECASE | re.MULTILINE,
        )
    ]


def list_request_files() -> list[Path]:
    if not ARCHITECT_REQUESTS_DIR.exists():
        return []

    return sorted(
        ARCHITECT_REQUESTS_DIR.glob("AR-*.md")
    )


def list_requests() -> list[dict]:
    requests = []

    for path in list_request_files():
        content = path.read_text(encoding="utf-8")

        requests.append(
            {
                "file": path.name,
                "path": path,
                "id": extract_request_id(content),
                "title": extract_title(content),
                "status": extract_status(content),
                "raw_status": extract_raw_status(content),
                "blocking_decision_ids": blocking_decision_ids(content),
                "content": content,
            }
        )

    return requests


def find_duplicate_request_ids() -> list[str]:
    seen = set()
    duplicates = set()

    for request in list_requests():
        request_id = request["id"]

        if not request_id:
            continue

        if request_id in seen:
            duplicates.add(request_id)

        seen.add(request_id)

    return sorted(duplicates)


def get_unresolved_requests() -> list[dict]:
    """Everything the planner must not duplicate a question for."""

    return [
        request
        for request in list_requests()
        if request["status"] != "RESOLVED"
    ]


def get_actionable_requests() -> list[dict]:
    """
    The requests the architect should be invoked for right now, in
    filename order.

    OPEN always qualifies. NEEDS_USER qualifies again only once every
    UD it names is RESOLVED -- an unreadable, missing or still-OPEN
    decision keeps it blocked, never assumed answered. A request whose
    Status is missing or unrecognized is treated as unreadable and left
    alone rather than dispatched on a guess.
    """

    resolved_decisions = {
        decision["id"]
        for decision in list_decisions()
        if decision["id"] and decision["status"] == "RESOLVED"
    }

    actionable = []

    for request in list_requests():
        if request["status"] == "OPEN":
            actionable.append(request)
            continue

        if request["status"] != "NEEDS_USER":
            continue

        blocking = request["blocking_decision_ids"]

        if blocking and all(
            decision_id in resolved_decisions for decision_id in blocking
        ):
            actionable.append(request)

    return actionable


def _normalized_question(content: str) -> str:
    """The Architecture Question, whitespace- and case-normalized."""

    return " ".join(
        (section(content, "Architecture Question") or "").lower().split()
    )


def validate_planner_updates(
        before: dict[str, str],
        created: list,
        planning_status: str,
) -> list[str]:
    """
    Deterministic checks on what a planning run did to this inbox.

    The planner may only ever ADD a request. It may not answer one,
    edit one, delete one, or ask a question an unresolved request
    already asks -- those are the failure modes that would let planning
    quietly take over the architect's role or spawn duplicates. What
    counts as a genuinely architectural question, and whether the
    question is well posed, remains the planner's judgment; only the
    mechanics are enforced here.
    """

    problems = []

    after = {
        request["file"]: request["content"]
        for request in list_requests()
    }

    reported = set()

    for name in created:
        if not isinstance(name, str):
            problems.append(f"Invalid architect request name: {name!r}")
            continue

        reported.add(name)

    for name in sorted(before.keys() - after.keys()):
        problems.append(f"Architect request was deleted: {name}")

    for name in sorted(before.keys() & after.keys()):
        if after[name] != before[name]:
            problems.append(
                f"Planning run modified an existing architect request: {name}"
                " -- only ARCHITECTURE MODE may answer or change one."
            )

    new_files = sorted(after.keys() - before.keys())

    for name in new_files:
        if name not in reported:
            problems.append(
                f"{name} was created but not reported in "
                "architect_requests_created."
            )

    for name in sorted(reported):
        if name in before:
            problems.append(f"architect_requests_created reports {name}, which already existed before this run.")
        if name not in after:
            if name in before:
                problems.append(
                    f"architect_requests_created reports {name}, which "
                    "already existed before this run."
                )
            else:
                problems.append(
                    "Reported architect request does not exist under "
                    f"agent/architect-requests/: {name}"
                )

    if created and planning_status not in ("COMPLETE", "NEEDS_USER"):
        problems.append(
            f"Planning status is {planning_status} but "
            "architect_requests_created is non-empty; speculative "
            "requests must not be created on a failed run."
        )

    unresolved_questions = {
        _normalized_question(content): name
        for name, content in before.items()
        if extract_status(content) != "RESOLVED"
    }

    unresolved_questions.pop("", None)

    seen_ids = {
        extract_request_id(content)
        for name, content in before.items()
    }

    for name in new_files:
        content = after[name]

        missing = missing_required_headings(content)

        if missing:
            problems.append(
                f"{name} is missing required sections: " + ", ".join(missing)
            )

        request_id = extract_request_id(content)

        if not request_id:
            problems.append(
                f"{name} has no readable 'AR-<NUMBER>' title."
            )
        elif request_id in seen_ids:
            problems.append(
                f"{name} duplicates an existing architect request ID: "
                f"{request_id}"
            )
        else:
            seen_ids.add(request_id)

        status = extract_status(content)

        if status != "OPEN":
            problems.append(
                f"{name} is newly created but its Status is {status!r}, "
                "not OPEN."
            )

        question = _normalized_question(content)

        if not question:
            problems.append(
                f"{name} has an empty 'Architecture Question'."
            )
        elif question in unresolved_questions:
            problems.append(
                f"{name} asks the same question as the existing "
                f"unresolved request {unresolved_questions[question]}."
            )
        else:
            unresolved_questions[question] = name

        for heading in ANSWER_SECTIONS:
            if not is_placeholder(section(content, heading)):
                problems.append(
                    f"{name} was created by the planner but its "
                    f"'{heading}' section is already filled in -- the "
                    "planner must not answer its own architect request."
                )

    return problems


def undispatchable_requests() -> list[dict]:
    """
    Unresolved requests the architect cannot be dispatched for as they
    stand, each with the concrete reason why.

    A silently skipped request is the one behaviour this inbox must
    never have: from the outside it is indistinguishable from "the
    architect ignored my question", while the planner keeps reporting
    itself blocked by that same question. Observed exactly once, from a
    request re-queued by hand with a status outside the vocabulary.

    A NEEDS_USER request whose decision is genuinely OPEN is NOT listed
    here -- that one is waiting for the Product Owner, which is a real
    gate and not a defect.
    """

    decisions_by_id = {}

    for decision in list_decisions():
        if decision["id"]:
            decisions_by_id[decision["id"]] = decision["status"]

    stranded = []

    for request in list_requests():
        if request["status"] in ("RESOLVED", "OPEN"):
            continue

        if request["status"] == "NEEDS_USER":
            blocking = request["blocking_decision_ids"]

            if not blocking:
                stranded.append(
                    dict(
                        request,
                        reason=(
                            "Status is NEEDS_USER but no blocking UD-* is "
                            "named in 'Blocking User Decision', so no "
                            "decision can ever release it"
                        ),
                    )
                )
                continue

            statuses = {
                decision_id: decisions_by_id.get(decision_id)
                for decision_id in blocking
            }

            if all(status == "RESOLVED" for status in statuses.values()):
                # Actionable: the architect resumes on the next cycle.
                continue

            if any(status == "OPEN" for status in statuses.values()):
                # Legitimately waiting for the Product Owner.
                continue

            unusable = sorted(
                f"{decision_id} ({status or 'no readable user-decision file'})"
                for decision_id, status in statuses.items()
            )

            stranded.append(
                dict(
                    request,
                    reason=(
                        "Status is NEEDS_USER but its blocking decision(s) "
                        "cannot release it: " + "; ".join(unusable)
                    ),
                )
            )
            continue

        stranded.append(
            dict(
                request,
                reason=(
                    f"unreadable Status {request['raw_status']!r}; expected "
                    "one of " + ", ".join(CANONICAL_STATUSES)
                ),
            )
        )

    return stranded


def get_blocking_decision_ids() -> list[str]:
    """Every UD ID currently holding up a NEEDS_USER architect request."""

    ids = set()

    for request in list_requests():
        if request["status"] == "NEEDS_USER":
            ids.update(request["blocking_decision_ids"])

    return sorted(ids)
