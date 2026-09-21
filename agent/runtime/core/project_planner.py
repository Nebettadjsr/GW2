import json
import re
import subprocess

from agent.runtime.runners.local_planner_runner import run_local_planner
from agent.runtime.support.capacity import ModelCapacityUnavailable
from agent.runtime.core.story_state import (
    get_active_story_path, classify_story_status, extract_status_section, parse_backlog_section,
)
from agent.runtime.support.config import (
    BACKLOG_FILE,
    CURRENT_STORY_FILE,
    PROJECT_STATE_FILE,
    ROADMAP_FILE,
    PLANNING_MAX_STORIES_PER_RUN,
    PLANNING_MIN_STORIES_PER_RUN,
    PLANNING_RESULT_FILE,
    PLANNING_TRIGGER_MAX_READY_STORIES,
    REPO_ROOT,
    STORIES_DIR,
    USER_DECISIONS_DIR,
)
from agent.runtime.support.files import file_hash, write_json
from agent.runtime.core.story_archive import (
    archive_milestone_stories,
    extract_milestone,
    is_valid_milestone_slug,
    list_archived_story_files,
)
from agent.runtime.human.user_decisions import (
    find_duplicate_decision_ids,
    is_placeholder,
    list_decisions,
    missing_required_headings as missing_decision_headings,
    extract_section as extract_decision_section,
    extract_status as extract_decision_status,
)
from agent.runtime.human.product_owner_requests import (
    validate_request_updates,
    read_requests,
)


# ============================================================
# Planning trigger
# ============================================================

def should_trigger_planning(
        ready_story_count: int
) -> bool:
    return ready_story_count <= PLANNING_TRIGGER_MAX_READY_STORIES


# ============================================================
# Current-phase scoping
# ============================================================

PHASE_HEADING_PATTERN = re.compile(
    r"^(#{1,6})\s*(?:\d+\.\s*)?Phase\s+(\d+)\b[^\n]*$",
    re.IGNORECASE | re.MULTILINE,
    )

CURRENT_PHASE_PATTERNS = [
    re.compile(
        r"\bPhase\s+(\d+)\s+(?:is\s+)?(?:in progress|current|active)\b",
        re.IGNORECASE,
    ),
    re.compile(
        r"\bCurrent\s+(?:roadmap\s+)?phase\s*:?\s*Phase\s+(\d+)\b",
        re.IGNORECASE,
    ),
]


def _detect_current_phase_number(
        project_state: str
) -> int:
    for pattern in CURRENT_PHASE_PATTERNS:
        match = pattern.search(
            project_state
        )

        if match:
            return int(
                match.group(1)
            )

    raise RuntimeError(
        "Could not determine the current roadmap phase from "
        "agent/PROJECT_STATE.md. Expected wording such as "
        "'Phase 0 in progress' or 'Current phase: Phase 0'."
    )


def _extract_phase_section(
        roadmap: str,
        phase_number: int,
) -> str:
    matches = list(
        PHASE_HEADING_PATTERN.finditer(
            roadmap
        )
    )

    for index, match in enumerate(matches):
        if int(match.group(2)) != phase_number:
            continue

        start = match.start()

        if index + 1 < len(matches):
            end = matches[index + 1].start()
        else:
            end = len(roadmap)

        return roadmap[
            start:end
        ].strip()

    raise RuntimeError(
        f"Could not find a heading for Phase {phase_number} "
        "in docs/ROADMAP.md."
    )


# ============================================================
# Planning prompt
#
# Python determines the current phase and supplies only that phase's
# roadmap section. The model is not allowed to discover or redefine
# its own roadmap scope.
# ============================================================

def build_planning_prompt() -> str:

    planner_instructions_file = (
            REPO_ROOT
            / "agent"
            / "PLANNER_INSTRUCTIONS.md"
    )

    planner_instructions = (
        planner_instructions_file.read_text(
            encoding="utf-8"
        )
    )

    project_state = (
        PROJECT_STATE_FILE.read_text(
            encoding="utf-8"
        )
    )

    backlog = (
        BACKLOG_FILE.read_text(
            encoding="utf-8"
        )
    )

    product_owner_requests = read_requests()

    if product_owner_requests:
        product_owner_requests_block = "\n\n".join(
            f"--- BEGIN {item['file']} ---\n"
            f"{item['content']}\n"
            f"--- END {item['file']} ---"
            for item in product_owner_requests
        )
    else:
        product_owner_requests_block = (
            "(no OPEN or NEEDS_USER requests under agent/product-owner-requests/, "
            "excluding README.md)"
        )

    roadmap = (
        ROADMAP_FILE.read_text(
            encoding="utf-8"
        )
    )

    phase_number = _detect_current_phase_number(
        project_state
    )

    phase_section = _extract_phase_section(
        roadmap,
        phase_number,
    )

    milestone = (
        f"milestone-{phase_number:02d}"
    )

    return f"""PROJECT PLANNING MODE

You are the local project-planning agent.

CURRENT-PHASE LOCK
==================

Python has already determined the planning scope.

Current roadmap phase: Phase {phase_number}
Current milestone: {milestone}

You may plan ONLY for Phase {phase_number} during this run.

The exact Phase {phase_number} section from docs/ROADMAP.md is supplied below.
Do NOT open or read the full docs/ROADMAP.md yourself.
Do NOT inspect, reason about, quote, or create work for Phase {phase_number + 1}
or any later phase during this run.

If you encounter requirements belonging to a later phase, ignore them.

The supplied phase section is the ONLY roadmap section authoritative for this
planning pass.

IMPORTANT COMPLETION BEHAVIOR
=============================

If Phase {phase_number} is NOT complete:
- create only the next small useful batch of stories needed for Phase {phase_number};
- tag every new story with {milestone};
- do not create future-phase stories.

If Phase {phase_number} IS complete:
- update docs/ROADMAP.md and/or agent/PROJECT_STATE.md only as necessary to
  record the completed phase and move the documented current phase forward;
- report the milestone transition in PLANNING_RESULT.json;
- create ZERO next-phase stories in this run;
- stop.

The Python harness will automatically run another planning pass after a
validated milestone transition. That next pass will receive the next phase as
its own isolated planning scope.

Therefore: NEVER plan two phases in one run.

EVIDENCE RULE
=============

A new story may only be created when its Goal and Acceptance Criteria can be
directly traced to at least one of:
- the supplied current-phase roadmap section;
- agent/PROJECT_STATE.md;
- an existing current-phase story;
- an allowed authoritative domain/architecture/problem document referenced by
  the current phase or an existing current-phase story.

Missing information by itself is NOT permission to invent a story.

If required information is genuinely a human decision, create/reference the
appropriate OPEN user-decision file and return NEEDS_USER.

Do not invent repository facts, implementation state, test results, coverage,
dependency versions, CI state, migration requirements, or later-phase work.

ADDITIONAL READS
================

The authoritative planning context below has already been loaded for you.
Do not reread these files through the shell.

You may additionally inspect only when necessary:
- docs/KNOWN_PROBLEMS.md
- docs/CURRENT_ARCHITECTURE.md
- docs/TARGET_ARCHITECTURE.md
- docs/DOMAIN_SPEC.md
- existing files directly under agent/stories/
- existing files under agent/user-decisions/

Do not inspect CLAUDE.md, src/, pom.xml, build files, CI files, Git history, or
unrelated repository files.

STORY OUTPUT
============

When Phase {phase_number} is not complete and new stories are justified:
- create between 1 and 6 stories;
- never create filler stories;
- place each canonical file directly under agent/stories/;
- use filename STORY-<AREA>-<NUMBER>-short-name.md;
- add each new story to BACKLOG.md under ## To Do in execution-priority order;
- never modify ## Active or ## Archived.

Each new story must contain these `##` headings in this exact order:
1. Story ID
2. Title
3. Status
4. Milestone
5. Goal
6. Authoritative Source Documents / Sections
7. Context
8. Acceptance Criteria
9. Required Tests
10. Constraints
11. Dependencies
12. Definition of Done
13. Result
14. Blockers

New stories start with Status TODO.
Result starts with a placeholder such as "Not started."
Blockers is "None." when not blocked.
Dependencies may be "None".
Required Tests may be "None" only when genuinely not applicable.

PRODUCT OWNER REQUESTS
=======================

Before creating any new stories, process every file listed under
"PRODUCT OWNER REQUESTS (INBOX CONTENTS)" below (README.md is never
included there). Full processing rules are defined in the "Product Owner
Requests" section of the AUTHORITATIVE PLANNER INSTRUCTIONS below --
follow those exactly. In summary:
- if a request is already fully covered by existing docs/stories, do
  create no duplicate artifacts; record the existing coverage in its resolution;
- if it changes long-term intended behavior or architecture, update the
  correct authoritative document first (never copy the same requirement
  into more than one document);
- convert concrete, current-phase-appropriate work into normal stories/
  BACKLOG entries, following the same STORY OUTPUT rules above;
- if it belongs to a later phase, only record it as future planned work --
  never create a later-phase story;
- if an implementation/product decision genuinely requires a human, create
  or reuse an OPEN agent/user-decisions/UD-*.md file and return NEEDS_USER
  instead of guessing.

Never delete Product Owner request files. Update the original file's
## Status (OPEN | NEEDS_USER | RESOLVED) and ## Planner Resolution.
A missing Status in a legacy note means OPEN. Ignore RESOLVED requests.
For RESOLVED, briefly state exactly what was done (docs updated, stories
created, backlog entries added, existing artifacts reused, or no action
required), citing the authoritative Markdown artifact paths that fully
represent the request. An unresolved user decision is not full coverage.
For NEEDS_USER, identify the blocking agent/user-decisions/UD-*.md file.
For partial processing, retain OPEN and describe progress/remaining work.
Preserve the original request intent. Only the human PO deletes reviewed
RESOLVED files manually.

Only list newly RESOLVED filenames in product_owner_requests_processed.
That list must be empty unless planning status is COMPLETE; do not resolve
requests on NEEDS_USER/FAILED passes. OPEN and NEEDS_USER remain eligible
for later passes, including after the blocking user decision is resolved.

PLANNING RESULT
===============

The run is not complete until you write:

agent/runtime/artifacts/PLANNING_RESULT.json

with exactly these fields:

{{
  "status": "COMPLETE" | "NEEDS_USER" | "FAILED",
  "phase_considered": "Phase {phase_number}",
  "phase_exit_criteria_satisfied": true | false,
  "story_files_created": ["STORY-AREA-NUMBER-short-name.md", ...],
  "user_decision_ids": ["UD-NUMBER", ...],
  "product_owner_requests_processed": ["filename.md", ...],
  "milestone_transition": true | false,
  "completed_milestone": "{milestone}" | null,
  "next_milestone": "milestone-NN" | null,
  "backlog_updated": true | false,
  "roadmap_updated": true | false,
  "project_state_updated": true | false,
  "reason": "<short explanation grounded in the supplied current-phase context>"
}}

If status is NEEDS_USER:
- story_files_created must be empty;
- product_owner_requests_processed must be empty;
- user_decision_ids must contain the relevant OPEN decision IDs.

If milestone_transition is true:
- status must be COMPLETE;
- completed_milestone must be "{milestone}";
- story_files_created must be empty;
- next_milestone should identify the newly documented current milestone.

If milestone_transition is false:
- completed_milestone and next_milestone must both be null.

Stop immediately after writing PLANNING_RESULT.json.

AUTHORITATIVE PLANNER INSTRUCTIONS
==================================

{planner_instructions}

EXACT CURRENT ROADMAP PHASE
===========================

{phase_section}

PROJECT STATE
=============

{project_state}

CURRENT BACKLOG
===============

{backlog}

PRODUCT OWNER REQUESTS (INBOX CONTENTS)
========================================

{product_owner_requests_block}
"""


# ============================================================
# Deterministic Python-side validation
# ============================================================

REQUIRED_STORY_HEADINGS = [
    "Story ID",
    "Title",
    "Status",
    "Milestone",
    "Goal",
    "Authoritative Source Documents / Sections",
    "Context",
    "Acceptance Criteria",
    "Required Tests",
    "Constraints",
    "Dependencies",
    "Definition of Done",
    "Result",
    "Blockers",
]

STORY_ID_PATTERN = re.compile(
    r"##\s*Story ID\s*\n+\s*([A-Za-z0-9_-]+)",
    re.IGNORECASE,
)


def _has_heading(
        content: str,
        heading: str
) -> bool:
    pattern = re.compile(
        rf"^##\s*{re.escape(heading)}\s*$",
        re.IGNORECASE | re.MULTILINE,
        )

    return pattern.search(content) is not None


def _extract_story_id(
        content: str
) -> str | None:
    match = STORY_ID_PATTERN.search(
        content
    )

    return match.group(1).strip() if match else None


def _existing_story_files() -> list:
    # Includes archived stories -- a Story ID or filename must stay
    # unique for all time, not just among currently active stories,
    # so an archived STORY-DOM-001 must still block a new file from
    # reusing that ID or name.
    active = [
        path
        for path in STORIES_DIR.glob("*.md")
        if path.name.lower() != "backlog.md"
    ]

    return active + list_archived_story_files()


def _existing_story_ids() -> set:
    ids = set()

    for path in _existing_story_files():
        story_id = _extract_story_id(
            path.read_text(encoding="utf-8")
        )

        if story_id:
            ids.add(story_id)

    return ids


def _git_dirty_src_lines() -> set:
    # Scoped to src/ only -- unrelated pre-existing dirty state
    # elsewhere in the working tree (e.g. an in-progress story) must
    # not cause a false positive here.
    result = subprocess.run(
        [
            "git",
            "status",
            "--porcelain",
            "--",
            "src",
        ],
        cwd=REPO_ROOT,
        text=True,
        capture_output=True,
    )

    if result.returncode != 0:
        raise RuntimeError(
            "Could not check git status for src/ during the "
            "planning safety check:\n" + result.stderr
        )

    return {
        line
        for line in result.stdout.splitlines()
        if line.strip()
    }


def validate_planning_result(
        raw_result: dict,
        pre_current_story_hash,
        pre_src_status: set,
        pre_story_ids: set,
        pre_existing_story_filenames: set,
        pre_decisions: list,
        pre_requests: dict[str, str],
) -> dict:

    result = dict(raw_result)
    problems = []

    status = result.get("status")

    # The planner's own claim, captured before any validation-driven
    # downgrade below and never mutated afterward. Whether newly
    # created stories are "speculative" depends on what the planner
    # actually claimed, not on whether some *other*, unrelated
    # validation failure later forces `status` to FAILED -- otherwise
    # a legitimate COMPLETE run that merely created too many/too few
    # stories would also get a misleading second "speculative stories"
    # complaint tacked on, obscuring the real problem.
    original_status = status

    if status not in ("COMPLETE", "NEEDS_USER", "FAILED"):
        problems.append(
            f"Unknown or missing planning status: {status!r}"
        )
        status = "FAILED"

    # Safety: the active-story pointer must never move during planning.
    if file_hash(CURRENT_STORY_FILE) != pre_current_story_hash:
        problems.append(
            "Planning run modified agent/CURRENT_STORY.md, "
            "which is not permitted."
        )
        status = "FAILED"

    # Safety: application/domain source must never change during planning.
    if _git_dirty_src_lines() - pre_src_status:
        problems.append(
            "Planning run modified files under src/, "
            "which is not permitted."
        )
        status = "FAILED"

    story_files_created = result.get(
        "story_files_created"
    ) or []

    if not isinstance(story_files_created, list):
        problems.append(
            "story_files_created must be a list."
        )
        status = "FAILED"
        story_files_created = []

    # A COMPLETE run needing zero new stories right now is valid (e.g.
    # the existing backlog already covers what's needed) -- the batch
    # bound only applies once it actually creates any. Never require a
    # minimum that would incentivize filler stories: 1 is as valid a
    # batch as 6.
    if (
            status == "COMPLETE"
            and story_files_created
            and not (
            PLANNING_MIN_STORIES_PER_RUN
            <= len(story_files_created)
            <= PLANNING_MAX_STORIES_PER_RUN
    )
    ):
        problems.append(
            "A COMPLETE planning run that creates stories must create "
            f"between {PLANNING_MIN_STORIES_PER_RUN} and "
            f"{PLANNING_MAX_STORIES_PER_RUN} of them; "
            f"got {len(story_files_created)}."
        )
        status = "FAILED"

    if original_status != "COMPLETE" and story_files_created:
        problems.append(
            f"Planning status is {original_status} but "
            "story_files_created is non-empty; speculative "
            "stories must not be created outside COMPLETE."
        )
        status = "FAILED"

    seen_ids = set()
    new_story_milestones = set()

    for relative_name in story_files_created:
        story_path = STORIES_DIR / relative_name

        if (
                not story_path.exists()
                or story_path.parent != STORIES_DIR
        ):
            problems.append(
                "Reported story file does not exist under "
                f"agent/stories/: {relative_name}"
            )
            status = "FAILED"
            continue

        if relative_name in pre_existing_story_filenames:
            problems.append(
                "Reported story file already existed before "
                f"planning ran: {relative_name}"
            )
            status = "FAILED"

        content = story_path.read_text(
            encoding="utf-8"
        )

        missing_headings = [
            heading
            for heading in REQUIRED_STORY_HEADINGS
            if not _has_heading(content, heading)
        ]

        if missing_headings:
            problems.append(
                f"{relative_name} is missing required sections: "
                + ", ".join(missing_headings)
            )
            status = "FAILED"

        story_milestone = extract_milestone(
            content
        )

        if not story_milestone or not is_valid_milestone_slug(
                story_milestone
        ):
            problems.append(
                f"{relative_name} has a missing or invalid "
                f"Milestone value: {story_milestone!r} "
                "(expected e.g. 'milestone-00')."
            )
            status = "FAILED"
        else:
            new_story_milestones.add(
                story_milestone.strip()
            )

        story_id = _extract_story_id(
            content
        )

        if not story_id:
            problems.append(
                f"{relative_name} has no readable Story ID."
            )
            status = "FAILED"
        elif story_id in pre_story_ids or story_id in seen_ids:
            problems.append(
                f"{relative_name} duplicates an existing "
                f"Story ID: {story_id}"
            )
            status = "FAILED"
        else:
            seen_ids.add(story_id)

    # ------------------------------------------------------------
    # User-decision validation
    # ------------------------------------------------------------

    duplicate_decision_ids = find_duplicate_decision_ids()

    if duplicate_decision_ids:
        problems.append(
            "Duplicate user-decision IDs exist under "
            f"agent/user-decisions/: {', '.join(duplicate_decision_ids)}"
        )
        status = "FAILED"

    pre_decisions_by_file = {
        decision["file"]: decision
        for decision in pre_decisions
    }

    post_decisions = list_decisions()
    post_decisions_by_id = {
        decision["id"]: decision
        for decision in post_decisions
        if decision["id"]
    }

    for decision in post_decisions:
        is_new_file = decision["file"] not in pre_decisions_by_file

        if is_new_file:
            content = (
                    USER_DECISIONS_DIR / decision["file"]
            ).read_text(encoding="utf-8")

            missing = missing_decision_headings(content)

            if missing:
                problems.append(
                    f"{decision['file']} is missing required "
                    "sections: " + ", ".join(missing)
                )
                status = "FAILED"

            if decision["status"] != "OPEN":
                problems.append(
                    f"{decision['file']} is a newly created "
                    "decision file but its Status is not OPEN."
                )
                status = "FAILED"

            user_decision_text = extract_decision_section(
                content, "User Decision"
            )
            resolution_text = extract_decision_section(
                content, "Resolution"
            )

            if not is_placeholder(user_decision_text) or not is_placeholder(
                    resolution_text
            ):
                problems.append(
                    f"{decision['file']} was created this run but "
                    "already has a non-TODO User Decision/Resolution "
                    "-- the planner must not invent the answer."
                )
                status = "FAILED"

            continue

        previous = pre_decisions_by_file[decision["file"]]

        if (
                previous["status"] == "OPEN"
                and decision["status"] == "RESOLVED"
        ):
            content = (
                    USER_DECISIONS_DIR / decision["file"]
            ).read_text(encoding="utf-8")

            user_decision_text = extract_decision_section(
                content, "User Decision"
            )

            if is_placeholder(user_decision_text):
                problems.append(
                    f"{decision['file']} was marked RESOLVED this "
                    "run but its User Decision section is still a "
                    "placeholder -- a decision may only be marked "
                    "RESOLVED when an explicit human decision is "
                    "already present."
                )
                status = "FAILED"

    user_decision_ids = result.get("user_decision_ids") or []

    if not isinstance(user_decision_ids, list):
        problems.append(
            "user_decision_ids must be a list."
        )
        status = "FAILED"
        user_decision_ids = []

    if status == "NEEDS_USER" and not user_decision_ids:
        problems.append(
            "Planning status is NEEDS_USER but user_decision_ids "
            "is empty -- a NEEDS_USER result must reference an "
            "OPEN user-decision file."
        )
        status = "FAILED"

    for decision_id in user_decision_ids:
        referenced = post_decisions_by_id.get(decision_id)

        if referenced is None:
            problems.append(
                f"user_decision_ids references {decision_id!r}, "
                "which does not correspond to any file under "
                "agent/user-decisions/."
            )
            status = "FAILED"
        elif referenced["status"] != "OPEN":
            problems.append(
                f"user_decision_ids references {decision_id!r}, "
                f"but that decision's Status is {referenced['status']!r}, "
                "not OPEN."
            )
            status = "FAILED"

    # ------------------------------------------------------------
    # Product Owner request validation: retain originals and verify lifecycle.
    processed = result.get("product_owner_requests_processed", [])
    if not isinstance(processed, list):
        problems.append("product_owner_requests_processed must be a list.")
        status = "FAILED"
        processed = []
    request_problems = validate_request_updates(pre_requests, processed, original_status)
    if request_problems:
        problems.extend(request_problems)
        status = "FAILED"
    result["product_owner_requests_processed"] = sorted({
        name for name in processed if isinstance(name, str)
    })

    # ------------------------------------------------------------
    # Milestone-transition validation
    #
    # This only validates the CLAIM's shape/safety. The actual
    # archiving decision (which stories move) is re-derived
    # independently and deterministically by
    # story_archive.archive_milestone_stories() from each story's own
    # Status/Milestone -- never from this claim directly.
    # ------------------------------------------------------------

    milestone_transition = bool(
        result.get("milestone_transition")
    )
    completed_milestone = result.get("completed_milestone")

    if milestone_transition and original_status != "COMPLETE":
        problems.append(
            "milestone_transition is true but planning status is "
            f"{original_status!r}, not COMPLETE; a transition may only "
            "be claimed alongside a COMPLETE run."
        )
        status = "FAILED"
        milestone_transition = False

    if milestone_transition:
        if not completed_milestone or not is_valid_milestone_slug(
                completed_milestone
        ):
            problems.append(
                "milestone_transition is true but completed_milestone "
                f"is missing or invalid: {completed_milestone!r} "
                "(expected e.g. 'milestone-00')."
            )
            status = "FAILED"
            milestone_transition = False
        elif completed_milestone.strip() in new_story_milestones:
            problems.append(
                "milestone_transition is true and completed_milestone "
                f"is {completed_milestone!r}, but a newly created "
                "story is tagged with that same (just-completed) "
                "milestone instead of the next one."
            )
            status = "FAILED"
            milestone_transition = False
    elif completed_milestone:
        problems.append(
            "completed_milestone is set but milestone_transition is "
            "not true; clear one or the other."
        )
        status = "FAILED"

    result["milestone_transition"] = milestone_transition
    result["completed_milestone"] = (
        completed_milestone if milestone_transition else None
    )

    if problems:
        result["validation_problems"] = problems

    result["status"] = status

    return result


# ============================================================
# Planning run
# ============================================================

def _planning_snapshot():
    paths = set((REPO_ROOT / "docs").rglob("*.md"))
    paths.update(path for path in (REPO_ROOT / "agent").rglob("*.md")
                 if "artifacts" not in path.parts)
    paths.add(PLANNING_RESULT_FILE)
    return {path: path.read_bytes() for path in paths if path.is_file()}


def _run_guarded_planner(prompt):
    """One synchronous writer; interrupted planning cannot publish partial queue changes."""
    before = _planning_snapshot()
    protected = {CURRENT_STORY_FILE: CURRENT_STORY_FILE.read_bytes()
                 if CURRENT_STORY_FILE.exists() else None}
    active = None
    active_entries = parse_backlog_section(BACKLOG_FILE.read_text(encoding="utf-8"), "Active")
    if CURRENT_STORY_FILE.exists() and CURRENT_STORY_FILE.read_text(encoding="utf-8").strip():
        active = get_active_story_path()
        if classify_story_status(extract_status_section(active.read_text(encoding="utf-8"))) not in ("DONE", "BLOCKED"):
            protected[active] = active.read_bytes()
            prompt += ("\nAn unfinished Claude story is active: " + str(active.relative_to(REPO_ROOT))
                       + ". Do not modify it or CURRENT_STORY.md. Do not transition the milestone. "
                       "Plan only other useful current-scope work; creating zero stories is valid.\n")
    try:
        code = run_local_planner(prompt)
        for path, content in protected.items():
            if (path.read_bytes() if path.exists() else None) != content:
                raise RuntimeError(f"Planner modified protected active state: {path.name}")
        if parse_backlog_section(BACKLOG_FILE.read_text(encoding="utf-8"), "Active") != active_entries:
            raise RuntimeError("Planner modified BACKLOG Active section")
        if active in protected and PLANNING_RESULT_FILE.exists():
            result = json.loads(PLANNING_RESULT_FILE.read_text(encoding="utf-8"))
            if result.get("milestone_transition"):
                raise RuntimeError("Planner tried to transition with an unfinished active story")
        if code != 0:
            raise RuntimeError(f"Codex planner exited with code {code}")
        return code
    except (ModelCapacityUnavailable, RuntimeError, ValueError, OSError):
        after = _planning_snapshot()
        for path in after.keys() - before.keys():
            path.unlink()
        for path, content in before.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
        raise


def run_planning_pass() -> dict:

    print(
        "\n========================================"
    )
    print(
        "Running project-planning pass"
    )
    print(
        "========================================\n"
    )

    pre_current_story_hash = file_hash(
        CURRENT_STORY_FILE
    )

    pre_planning_result_hash = file_hash(
        PLANNING_RESULT_FILE
    )

    pre_src_status = _git_dirty_src_lines()

    pre_story_ids = _existing_story_ids()

    pre_existing_story_filenames = {
        path.name
        for path in _existing_story_files()
    }

    pre_decisions = list_decisions()

    pre_requests = {item["file"]: item["content"]
                    for item in read_requests(include_resolved=True)}

    prompt = build_planning_prompt()

    planner_exit_code = _run_guarded_planner(
        prompt
    )

    post_planning_result_hash = file_hash(
        PLANNING_RESULT_FILE
    )

    if (
            not PLANNING_RESULT_FILE.exists()
            or post_planning_result_hash == pre_planning_result_hash
    ):
        result = {
            "status": "FAILED",
            "reason": (
                "Planning run did not produce a new "
                "agent/runtime/artifacts/PLANNING_RESULT.json."
            ),
            "planner_exit_code": planner_exit_code,
        }

        write_json(
            PLANNING_RESULT_FILE,
            result
        )

        return result

    try:
        raw_result = json.loads(
            PLANNING_RESULT_FILE.read_text(
                encoding="utf-8"
            )
        )
    except (OSError, json.JSONDecodeError) as exc:
        result = {
            "status": "FAILED",
            "reason": (
                "Could not parse "
                f"agent/runtime/artifacts/PLANNING_RESULT.json: {exc}"
            ),
            "planner_exit_code": planner_exit_code,
        }

        write_json(
            PLANNING_RESULT_FILE,
            result
        )

        return result

    validated = validate_planning_result(
        raw_result,
        pre_current_story_hash,
        pre_src_status,
        pre_story_ids,
        pre_existing_story_filenames,
        pre_decisions,
        pre_requests,
    )

    validated["planner_exit_code"] = planner_exit_code

    # Archiving is a deterministic side effect of a validated,
    # confirmed transition -- never something the planning run does
    # itself, and never attempted for a FAILED/NEEDS_USER result (which
    # includes a milestone blocked by an OPEN user decision, since
    # that case must resolve to NEEDS_USER upstream, not COMPLETE).
    if (
            validated.get("status") == "COMPLETE"
            and validated.get("milestone_transition")
            and validated.get("completed_milestone")
    ):
        archive_result = archive_milestone_stories(
            validated["completed_milestone"]
        )

        validated["archive_result"] = archive_result

        if archive_result["errors"]:
            validated.setdefault(
                "validation_problems", []
            ).extend(
                f"archive error: {message}"
                for message in archive_result["errors"]
            )
            validated["status"] = "FAILED"

        if archive_result["conflicts"]:
            validated.setdefault(
                "validation_problems", []
            ).extend(
                "archive conflict (destination already exists, "
                f"not overwritten): {name}"
                for name in archive_result["conflicts"]
            )

    write_json(
        PLANNING_RESULT_FILE,
        validated
    )

    print(
        "\n========================================"
    )
    print(
        f"Planning result: {validated.get('status')}"
    )
    print(
        f"Reason: {validated.get('reason', '')}"
    )

    if validated.get("validation_problems"):
        print(
            "Validation problems:"
        )

        for problem in validated["validation_problems"]:
            print(
                f"- {problem}"
            )

    print(
        "========================================\n"
    )

    return validated
