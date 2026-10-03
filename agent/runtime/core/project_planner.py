import json
import re
import subprocess

from agent.runtime.runners.local_planner_runner import run_local_planner
from agent.runtime.support.capacity import ModelCapacityUnavailable
from agent.runtime.core.story_state import (
    get_active_story_path, classify_story_status, extract_status_section, parse_backlog_section,
    get_unsatisfied_dependencies, validate_backlog_entries,
)
from agent.runtime.support.config import (
    ADR_DIR,
    ARCHITECT_REQUESTS_DIR,
    BACKLOG_FILE,
    CURRENT_STORY_FILE,
    PROJECT_STATE_FILE,
    ROADMAP_FILE,
    PLANNER_INSTRUCTIONS_FILE,
    PLANNING_MAX_STORIES_PER_RUN,
    PLANNING_MIN_STORIES_PER_RUN,
    PLANNING_RESULT_FILE,
    PLANNING_TRIGGER_MAX_READY_STORIES,
    REPO_ROOT,
    ROLE_CONTRACT_FILES,
    STORIES_DIR,
    USER_DECISIONS_DIR,
)
from agent.runtime.support import config
from agent.runtime.support.files import file_hash, write_json
from agent.runtime.support.daily_log import print_status
from agent.runtime.core import planning_context
from agent.runtime.core.follow_up_findings import planner_block, unresolved_findings
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
    request_status,
)
from agent.runtime.human import architect_requests


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
#
# What each block costs is measured, not assumed: see
# agent/runtime/reports/2026-09-26-planner-usage.md. Finished history
# (completed backlog entries, resolved decisions, resolved receipts) is
# compacted by core/planning_context.py into indexes that keep every
# identifier planning needs and name the file holding the detail;
# unresolved questions are always supplied in full.
# ============================================================

def build_planning_context() -> tuple[str, dict]:
    """The planning prompt plus the size of each supplied section."""

    planner_instructions = PLANNER_INSTRUCTIONS_FILE.read_text(
        encoding="utf-8"
    )

    project_state = (
        PROJECT_STATE_FILE.read_text(
            encoding="utf-8"
        )
    )

    backlog_index = planning_context.backlog_index(
        BACKLOG_FILE.read_text(encoding="utf-8"),
        planning_context.story_facts(_existing_story_files()),
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

    resolved_request_coverage = planning_context.resolved_request_index([
        item for item in read_requests(include_resolved=True)
        if request_status(item["content"]) == "RESOLVED"
    ])

    decision_coverage_block = planning_context.decision_index([
        dict(item, content=(USER_DECISIONS_DIR / item["file"]).read_text(
            encoding="utf-8"
        ))
        for item in list_decisions()
    ])

    architect_requests_block = planning_context.architect_request_index(
        architect_requests.list_requests()
    )

    implementation_follow_up_findings = []
    for story_path in _existing_story_files():
        try:
            content = story_path.read_text(encoding="utf-8")
        except OSError:
            continue
        if classify_story_status(extract_status_section(content)) != "DONE":
            continue
        implementation_follow_up_findings.extend(
            unresolved_findings(
                content, story_path.relative_to(REPO_ROOT).as_posix()
            )
        )
    implementation_follow_up_findings_block = planner_block(
        implementation_follow_up_findings
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

    sections = {
        "backlog index": backlog_index,
        "open PO requests": product_owner_requests_block,
        "resolved PO summary": resolved_request_coverage,
        "UD summary": decision_coverage_block,
        "architect requests": architect_requests_block,
        "unresolved Claude follow-up findings": implementation_follow_up_findings_block,
        "current roadmap phase": phase_section,
        "project state": project_state,
        "document ownership": config.ownership_table(config.PLANNER),
        "role instructions": planner_instructions,
    }

    review_outcomes = " | ".join(f'"{outcome}"' for outcome in REVIEW_OUTCOMES)
    ownership_table = config.ownership_table(config.PLANNER)

    prompt = f"""PROJECT PLANNING MODE

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
appropriate OPEN user-decision file for that area, then continue reviewing other
current-phase areas. Choose result status only after the full area review.

Do not invent repository facts, implementation state, test results, coverage,
dependency versions, CI state, migration requirements, or later-phase work.

SUPPLIED CONTEXT AND ADDITIONAL READS
=====================================

Everything below is already in your context. Do not read, reread or print any
of it through the shell: your role contract, AGENTS.md (its instruction to
read agent/PLANNER_INSTRUCTIONS.md is already satisfied by this prompt), the
current-phase roadmap section, agent/PROJECT_STATE.md, the backlog index, the
supplied requests and the decision summaries.

The backlog index below is generated from agent/stories/BACKLOG.md. It lists
every story ID and filename in the project, so it is sufficient to detect a
duplicate story, to see the queue in priority order, and to see what the
current milestone already covers. Completed entries are compacted; the full
text of any story is in its own file.

Targeted reads, only when a specific detail actually decides something:
- a single story file under agent/stories/ (or agent/stories/archive/)
- a single file under agent/user-decisions/
- the relevant section of docs/KNOWN_PROBLEMS.md, docs/CURRENT_ARCHITECTURE.md,
  docs/TARGET_ARCHITECTURE.md, docs/DOMAIN_SPEC.md, docs/TEST_STRATEGY.md,
  docs/QUALITY_METRICS.md, docs/bugs/README.md, docs/bugs/BUG-REPORT-TEMPLATE.md,
  or one bug record under docs/bugs/
- one named section of agent/stories/BACKLOG.md, when a completed entry's
narrative is the evidence you need

UNRESOLVED CLAUDE IMPLEMENTATION FOLLOW-UP FINDINGS
===================================================

These concise observations came from completed stories and are outside their
implementation scope. Process every supplied finding during this normal
planning pass. Do not create a separate planning pass solely because findings
exist. Check the supplied backlog/story index and relevant authoritative
references to avoid duplicate work. For each finding, record exactly one
disposition in its source story under `## Follow-up Findings Disposition`:

- `F001: ALREADY COVERED — <story/reference>`
- `F001: FOLLOW-UP STORY — <new story ID>`
- `F001: DEFERRED — <known-problem/reference or later milestone and reason>`
- `F001: DISMISSED — <reason and reference>`

These four words are the disposition vocabulary for findings ONLY. They are
not phase_review outcomes: DEFERRED in particular is not an accepted
phase_review[].outcome value, and a result using it is rejected. A deferred
*area* is PREREQUISITE or COVERED depending on what it waits for.

Before creating a story for a finding, confirm the underlying problem still
exists in the current implementation and is not already covered. A finding is
an observation from an earlier run, not a work order: a later story or a
maintainer change may already have fixed it. Check the source story's existing
disposition first -- if one is already recorded there, do not replace it with a
new story.

Create stories only through the normal story and backlog workflow. Never ask
Claude to create stories. Update only the disposition section of a completed
source story; leave its implementation finding and Result unchanged. Do not
set independent_work_remaining solely because findings exist.

Triage each finding as a verified current defect, missing verification,
documentation drift, already resolved, or optional improvement. Confirm an
alleged defect against the current implementation and confirmed Product Owner
decisions. Verification gaps do not automatically imply implementation
stories; documentation drift must follow newer confirmed decisions; optional
improvements need not become mandatory work. Create an implementation story
only for a verified defect or unmet requirement not already covered by
existing work.

{implementation_follow_up_findings_block}

Read a section or line range rather than a whole large document, batch
independent reads into one command, and never read the same thing twice. The
forbidden-read list in your role contract's "Read Scope" still applies.

agent/stories/BACKLOG.md is a machine-read index, not prose. Do not hand-write
its Markdown. Add, move and restamp entries only through the harness's own
entry writer, which produces the one canonical spelling:

  python -c "import sys; sys.path.insert(0,'.'); from agent.runtime.core.backlog_writer import add_to_do_entries; add_to_do_entries([('STORY-WEB-030','STORY-WEB-030-short-name.md','milestone-NN',['STORY-WEB-029'])])"

Rules the harness enforces and will reject the whole pass over:
- one line per entry, exactly
  `- STORY-ID | filename.md | STATUS | milestone-NN | deps: A, B` (or `deps: None`);
- no `backlog entry:`, `dependency note:` or `disposition:` lines, and no
  indented continuation lines of any kind, anywhere in the file. A story's
  description, requirements, evidence and disposition belong in its own file;
  roadmap sequencing belongs in docs/ROADMAP.md; run detail belongs in the log;
- never touch `## Active` or `## Archived`; the harness owns those.

Before you finish, validate your own output -- a result that fails validation
is discarded together with every change this pass made:

  python -c "import sys; sys.path.insert(0,'.'); from agent.runtime.core.planning_check import main; sys.exit(main())"

It prints either `PLANNING OUTPUT OK` or one problem per line. Fix what it
names and run it again until it passes.

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
For behavior changes, include relevant business rules and `docs/TEST_STRATEGY.md` in
authoritative references. Require meaningful behavior tests, cross-component checks, regression
tests for confirmed defects, and review of affected coverage; make any exception explicit in the
story. Consult `docs/QUALITY_METRICS.md` for coverage target and reports, and `docs/bugs/` for
confirmed defect records. Keep these requirements proportional to the affected behavior.

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
  or reuse an OPEN agent/user-decisions/UD-*.md file instead of guessing,
  then continue with other areas. Do not return at the first blocker.

Never delete Product Owner request files. Update the original file's
## Status (OPEN | NEEDS_USER | RESOLVED) and ## Planner Resolution.
A missing Status in a legacy note means OPEN. Use RESOLVED coverage receipts
to avoid duplicates; never edit or reprocess them.
For RESOLVED, briefly state exactly what was done (docs updated, stories
created, backlog entries added, existing artifacts reused, or no action
required), citing the authoritative Markdown artifact paths that fully
represent the request. An unresolved user decision is not full coverage.
For NEEDS_USER, identify the blocking agent/user-decisions/UD-*.md file.
For partial processing, retain OPEN and describe progress/remaining work.
Preserve the original request intent. Only the human PO deletes reviewed
RESOLVED files manually.

Only list newly RESOLVED filenames in product_owner_requests_processed.
On COMPLETE or NEEDS_USER, resolve independently covered requests.
On FAILED, do not resolve requests. OPEN and NEEDS_USER remain eligible
for later passes, including after the blocking user decision is resolved.

ARCHITECT REQUESTS
==================

A separate Codex role, the ARCHITECT (agent/ARCHITECT_INSTRUCTIONS.md), owns
architecture decisions. You are the PLANNER. Do not switch roles: never enter
ARCHITECTURE MODE, never answer an architecture question you are not
authorized to decide, and never write
docs/architecture/decisions/ADR-*.md.

When planning runs into an architectural question you are not authorized to
decide -- a component/layer boundary that authoritative documents do not
settle, a technology still marked TBD, a structural alternative with
materially different consequences, a conflict between two architecture
documents -- create an architect request instead of guessing and instead of
going straight to a User Decision:

- one file per question, directly under agent/architect-requests/;
- filename AR-<NUMBER>-short-name.md, with an ID unique for all time;
- these `##` headings, in this exact order:
  1. Status               (OPEN)
  2. Architecture Question
  3. Context and Constraints
  4. Authoritative References
  5. Blocked Work
  6. Blocking User Decision   (None.)
  7. Architect Decision       (TODO)
  8. Resolution               (TODO)
- state the question precisely, the constraints and context you already know,
  the authoritative documents/sections that bear on it, and exactly what
  planning or story work it blocks;
- leave Architect Decision and Resolution as TODO. Answering your own request
  is a role violation and Python rejects it.

Do NOT create an architect request for a trivial implementation choice a story
can safely make (naming, local structure inside one class, test placement,
obvious reuse of an existing pattern). Those belong to the implementing story.

Duplicates are rejected: the unresolved requests supplied below are the
current architecture questions. If your question is already one of them, do
not create a second request -- record that the work is blocked by the existing
AR-* instead.

You may never modify an existing file under agent/architect-requests/. Python
compares them byte-for-byte after this run. A RESOLVED request's Architect
Decision is established architecture input: plan with it, and convert its
implied work into normal current-phase stories when the phase allows.

If a request is NEEDS_USER, it is waiting on the Product Owner through its own
agent/user-decisions/UD-*.md file. Treat the work it blocks as blocked; do not
duplicate that decision and do not create a competing one.

PLANNING RESULT
===============

The run is not complete until you write:

agent/runtime/artifacts/PLANNING_RESULT.json

with exactly these fields:

{{
  "status": "COMPLETE" | "NEEDS_USER" | "FAILED",
  "phase_considered": "Phase {phase_number}",
  "independent_work_remaining": true | false,
  "phase_review": [{{"area": "current-phase area", "outcome": {review_outcomes}, "references": ["artifact path or stable ID", ...]}}],
  "phase_exit_criteria_satisfied": true | false,
  "story_files_created": ["STORY-AREA-NUMBER-short-name.md", ...],
  "user_decision_ids": ["UD-NUMBER", ...],
  "product_owner_requests_processed": ["filename.md", ...],
  "architect_requests_created": ["AR-NUMBER-short-name.md", ...],
  "milestone_transition": true | false,
  "completed_milestone": "{milestone}" | null,
  "next_milestone": "milestone-NN" | null,
  "backlog_updated": true | false,
  "roadmap_updated": true | false,
  "project_state_updated": true | false,
  "reason": "<short explanation grounded in the supplied current-phase context>"
}}

Review remaining relevant current-phase areas BEFORE choosing the result.
Discover all currently identifiable independent UDs and ARs in the same pass.
One blocker, an active story, or the story batch limit must not terminate that review.
Record each area's disposition and evidence in phase_review; split mixed areas.
Reuse existing OPEN/NEEDS_USER/RESOLVED artifacts and backlog coverage.
Do not exhaustively decompose the milestone: keep the 1?6 story batch limit.

COMPLETE means a successful batch, and may contain UDs, ARs and stories together.
If a READY area can advance in another bounded pass, set independent_work_remaining
true and use COMPLETE. This requires concrete progress in this pass (new stories,
UDs, ARs or resolved PO requests), not just changed prose. Otherwise set it false.
NEEDS_USER means no further useful independent planning is available without user
input after this review; it does not mean merely that an open UD exists.
Architecture-only/prerequisite waits use COMPLETE with no independent work remaining.

If status is NEEDS_USER:
- independent_work_remaining must be false and no phase_review entry may be READY;
- finish independent planning and request processing in this same pass;
- story_files_created may contain only stories independent of the open decisions;
- product_owner_requests_processed may contain independently resolved requests;
- user_decision_ids must contain stable OPEN IDs, e.g. UD-010 (not filenames).
  Each ID resolves to exactly one UD-010-*.md (or UD-010.md) filename;
  no Markdown title is required. Duplicate IDs are invalid.
A decision blocks only dependent work. Never stop the whole pass at the first UD.

If milestone_transition is true:
- status must be COMPLETE;
- completed_milestone must be "{milestone}";
- story_files_created must be empty;
- next_milestone should identify the newly documented current milestone.

If milestone_transition is false:
- completed_milestone and next_milestone must both be null.

Stop immediately after writing PLANNING_RESULT.json.

DOCUMENT OWNERSHIP (enforced by the harness, not advisory)
==========================================================

This is the contract this pass is checked against. A write to anything marked
READ-ONLY rolls the entire pass back, including every story file and backlog
entry it created. The same list is what the harness protects, so there is no
second, unstated rule.

{ownership_table}

AUTHORITATIVE PLANNER INSTRUCTIONS
==================================

{planner_instructions}

EXACT CURRENT ROADMAP PHASE
===========================

{phase_section}

PROJECT STATE
=============

{project_state}

BACKLOG INDEX (agent/stories/BACKLOG.md; COMPLETE LIST OF STORY IDS)
====================================================================

{backlog_index}

PRODUCT OWNER REQUESTS (INBOX CONTENTS)
========================================

{product_owner_requests_block}

USER DECISION COVERAGE (ALL STATUSES; REUSE QUESTIONS AND ANSWERS)
===============================================================

Unresolved decisions are supplied in full. A RESOLVED one is supplied as its
question and its answer; read agent/user-decisions/<file> for the rest.

{decision_coverage_block}

RESOLVED PO REQUEST COVERAGE (READ ONLY; DO NOT REPROCESS)
=========================================================

{resolved_request_coverage}

ARCHITECT REQUESTS (INBOX CONTENTS)
====================================

{architect_requests_block}
"""

    return prompt, sections


def build_planning_prompt() -> str:
    return build_planning_context()[0]


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


def _adr_snapshot() -> dict:
    """ADR filename -> content hash. Architect-owned; planning must not move it."""

    if not ADR_DIR.exists():
        return {}

    return {
        path.name: file_hash(path)
        for path in sorted(ADR_DIR.glob("*.md"))
    }


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


# The ONLY accepted phase_review[].outcome values. An ordered tuple, not a
# set, because build_planning_prompt() renders this very sequence into the
# prompt's result schema: the planner is shown exactly what this validator
# accepts, from one definition, so the two can never drift.
#
# DEFERRED is deliberately absent, and is the value a real run emitted. It
# belongs to the *other* vocabulary in this prompt -- the follow-up-finding
# dispositions (ALREADY COVERED / FOLLOW-UP STORY / DEFERRED / DISMISSED) --
# and the planner carried the word across. A deferred *area* is PREREQUISITE
# or COVERED depending on what it is waiting for, and choosing between those
# is a judgment the harness must not silently make on the planner's behalf.
# The accepted top-level planning statuses, shared with planning_check so the
# planner is validated against the same list the harness applies.
PLANNING_STATUSES = ("COMPLETE", "NEEDS_USER", "FAILED")

REVIEW_OUTCOMES = (
    "PLANNED",
    "READY",
    "USER_DECISION",
    "ARCHITECT_REQUEST",
    "COVERED",
    "PREREQUISITE",
)

FINDING_DISPOSITIONS = (
    "ALREADY COVERED",
    "FOLLOW-UP STORY",
    "DEFERRED",
    "DISMISSED",
)


def _validate_phase_review(result, made_progress):
    """Validate the review receipt, not the model's semantic completeness judgment."""
    problems = []
    remaining = result.get("independent_work_remaining")
    review = result.get("phase_review")
    if not isinstance(remaining, bool):
        problems.append("independent_work_remaining must be a boolean.")
    if not isinstance(review, list) or not review:
        return problems + ["phase_review must contain the remaining current-phase areas (or phase-exit coverage)."]
    ready = False
    reviewed_decisions = set()
    reviewed_architecture = set()
    for entry in review:
        if not isinstance(entry, dict):
            problems.append("phase_review entries must be objects.")
            continue
        outcome = entry.get("outcome")
        refs = entry.get("references")
        if not isinstance(entry.get("area"), str) or not entry["area"].strip():
            problems.append("phase_review entries need a nonempty area.")
        if not isinstance(outcome, str) or outcome not in REVIEW_OUTCOMES:
            problems.append(f"Invalid phase_review outcome: {outcome!r}")
        if not isinstance(refs, list) or not refs or not all(isinstance(ref, str) and ref.strip() for ref in refs):
            problems.append("phase_review entries need artifact references.")
            continue
        ready |= outcome == "READY"
        if outcome == "ARCHITECT_REQUEST":
            reviewed_architecture.update(ref for ref in refs if re.fullmatch(r"AR-\d+", ref))
            if not any(re.fullmatch(r"AR-\d+", ref) for ref in refs):
                problems.append("ARCHITECT_REQUEST review entries must reference stable AR IDs.")
        if outcome == "USER_DECISION":
            reviewed_decisions.update(ref for ref in refs if re.fullmatch(r"UD-\d+", ref))
            if not any(re.fullmatch(r"UD-\d+", ref) for ref in refs):
                problems.append("USER_DECISION review entries must reference stable UD IDs.")
    if reviewed_architecture:
        requests = architect_requests.list_requests()
        for request_id in reviewed_architecture:
            matches = [item for item in requests if item["id"] == request_id]
            if len(matches) != 1 or matches[0]["status"] == "RESOLVED":
                problems.append(f"phase_review references no unique unresolved request: {request_id}")
    for filename in result.get("architect_requests_created", []):
        match = re.match(r"(AR-\d+)-", filename)
        if match and match.group(1) not in reviewed_architecture:
            problems.append(f"New architect request {filename} is missing from phase_review.")
    ids = result.get("user_decision_ids", [])
    if isinstance(ids, list) and all(isinstance(item, str) for item in ids):
        if set(ids) != reviewed_decisions:
            problems.append("phase_review USER_DECISION references must match all user_decision_ids.")
    if remaining != ready:
        problems.append("independent_work_remaining must agree with READY areas in phase_review.")
    if remaining:
        if result.get("status") != "COMPLETE":
            problems.append("Independent READY work remains: use COMPLETE, not NEEDS_USER/FAILED.")
        if not made_progress:
            problems.append("Another planning batch requires concrete progress; prose-only changes cannot trigger retries.")
    return problems


def validate_planning_result(
        raw_result: dict,
        pre_current_story_hash,
        pre_src_status: set,
        pre_story_ids: set,
        pre_existing_story_filenames: set,
        pre_decisions: list,
        pre_requests: dict[str, str],
        pre_architect_requests: dict[str, str],
        pre_adr_files: dict[str, str | None],
) -> dict:

    if not isinstance(raw_result, dict):
        return {"status": "FAILED", "reason": "Malformed planning output",
                "validation_problems": ["Planning result must be a JSON object."]}
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

    if status not in PLANNING_STATUSES:
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
            original_status in ("COMPLETE", "NEEDS_USER")
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

    if original_status not in ("COMPLETE", "NEEDS_USER") and story_files_created:
        problems.append(
            f"Planning status is {original_status} but "
            "story_files_created is non-empty; speculative "
            "stories require COMPLETE or NEEDS_USER (independent work only)."
        )
        status = "FAILED"

    seen_ids = set()
    new_story_milestones = set()

    for relative_name in story_files_created:
        if not isinstance(relative_name, str):
            problems.append("story_files_created entries must be filenames.")
            status = "FAILED"
            continue
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

        unresolved = get_unsatisfied_dependencies(content)
        if unresolved and classify_story_status(extract_status_section(content)) != "BLOCKED":
            problems.append(f"{relative_name} has unresolved prerequisites but is not BLOCKED: {unresolved}")
            status = "FAILED"

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

    questions = {}
    for decision in post_decisions:
        content = (USER_DECISIONS_DIR / decision["file"]).read_text(encoding="utf-8")
        question = " ".join((extract_decision_section(content, "Decision Needed") or "").casefold().split())
        if question:
            questions.setdefault(question, []).append(decision["file"])
    for names in questions.values():
        if len(names) > 1 and any(name not in pre_decisions_by_file for name in names):
            problems.append("Duplicate user-decision question (reuse existing OPEN/RESOLVED decision): " + ", ".join(names))
            status = "FAILED"

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
        if not isinstance(decision_id, str) or not re.fullmatch(r"UD-\d+", decision_id):
            problems.append(f"Invalid user_decision_ids entry {decision_id!r}; expected stable UD-NUMBER.")
            status = "FAILED"
            continue
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

    for decision in post_decisions:
        if decision["file"] not in pre_decisions_by_file and decision["id"] not in user_decision_ids:
            problems.append(f"New decision {decision['file']} is missing from user_decision_ids.")
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
    # Architect requests: the planner may only ADD a question for the
    # architect (AGENTS.md's "No Implicit Role Switching"). Answering
    # one, editing one, duplicating an unresolved one, or writing an
    # architect-owned ADR are all role violations, and all of them are
    # detectable from the files themselves.
    # ------------------------------------------------------------

    architect_created = result.get("architect_requests_created", [])

    if not isinstance(architect_created, list):
        problems.append("architect_requests_created must be a list.")
        status = "FAILED"
        architect_created = []

    architect_problems = architect_requests.validate_planner_updates(
        pre_architect_requests, architect_created, original_status
    )

    if architect_problems:
        problems.extend(architect_problems)
        status = "FAILED"

    result["architect_requests_created"] = sorted({
        name for name in architect_created if isinstance(name, str)
    })

    duplicate_request_ids = architect_requests.find_duplicate_request_ids()

    if duplicate_request_ids:
        problems.append(
            "Duplicate architect request IDs exist under "
            f"agent/architect-requests/: {', '.join(duplicate_request_ids)}"
        )
        status = "FAILED"

    if _adr_snapshot() != pre_adr_files:
        problems.append(
            "Planning run added or modified an Architecture Decision Record "
            "under docs/architecture/decisions/, which only ARCHITECTURE "
            "MODE may write."
        )
        status = "FAILED"

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

    review_problems = _validate_phase_review(result, bool(
        story_files_created or processed or architect_created
        or any(item["file"] not in pre_decisions_by_file for item in post_decisions)
    ))
    if review_problems:
        problems.extend(review_problems)
        status = "FAILED"

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


def _planner_protected_paths() -> tuple:
    """The planner-protected documents, resolved through this module's names.

    `config.DOCUMENT_OWNERSHIP` is the one place that *declares* which
    documents the planner may not write. The paths come back through this
    module's own module-level names so that the ownership guarantees stay
    directly testable: the guard tests patch `ADR_DIR`, `CURRENT_STORY_FILE`
    and friends to temporary directories, and a guard reading the real
    repository paths instead would protect the wrong files and quietly pass.
    """

    local = {
        config.CURRENT_STORY_FILE: CURRENT_STORY_FILE,
        config.ADR_DIR: ADR_DIR,
        config.ARCHITECT_REQUESTS_DIR: ARCHITECT_REQUESTS_DIR,
        config.BACKLOG_FILE: BACKLOG_FILE,
        config.PROJECT_STATE_FILE: PROJECT_STATE_FILE,
        config.ROADMAP_FILE: ROADMAP_FILE,
        config.STORIES_DIR: STORIES_DIR,
        config.USER_DECISIONS_DIR: USER_DECISIONS_DIR,
    }

    paths = [
        local.get(path, path)
        for path in config.protected_paths_for(config.PLANNER)
    ]

    # The architect-request *directory* is resolved through the module that
    # owns it, which the guard tests patch separately.
    paths.extend(architect_requests.list_request_files())

    return tuple(paths)


class PlanningRejected(RuntimeError):
    """The pass produced an invalid result; its changes were rolled back."""

    def __init__(self, result):
        super().__init__(result.get("reason") or "planning validation failed")
        self.result = result


def _run_guarded_planner(prompt, validate=None):
    """One synchronous writer; interrupted or invalid planning publishes nothing.

    `validate` is called after the planner exits, while the rollback is still
    armed, and returns the validated result dict. A FAILED one raises
    PlanningRejected, which rolls the pass back exactly like a crash.

    That ordering is the fix for a real failure: validation used to run in
    run_planning_pass(), *after* this function had already returned
    successfully, so a pass that exited 0 and touched nothing protected was
    never rolled back no matter what validation said. One run therefore left
    four new story files, a rewritten '## To Do' and five edited story files on
    disk under a FAILED result -- partial plan, no record of which half applied.
    """
    before = _planning_snapshot()

    # The protected set is derived from config.DOCUMENT_OWNERSHIP -- the same
    # definition the prompt's ownership table is rendered from -- rather than
    # listed again here. It used to be a handful of literals in this function
    # while the rules were also stated in prose in four role files, so the
    # enforced contract and the described one could drift apart silently.
    #
    # Files are protected individually; a protected *directory* protects every
    # file currently in it (the planner may add a new architect request, but
    # never change an existing one).
    protected = {}

    for path in _planner_protected_paths():
        if path.is_dir():
            protected.update(
                {child: child.read_bytes() for child in sorted(path.rglob("*.md"))}
            )
        elif path.exists() or path == CURRENT_STORY_FILE:
            protected[path] = (
                path.read_bytes() if path.exists() else None
            )

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
        # Presence now proves fresh output, even when its contents match the
        # previous pass exactly. Failed/interrupted invocations restore it.
        PLANNING_RESULT_FILE.unlink(missing_ok=True)
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
        if validate is not None:
            validated = validate(code)
            if validated.get("status") == "FAILED":
                raise PlanningRejected(validated)
            return code, validated
        return code
    except (ModelCapacityUnavailable, RuntimeError, ValueError, OSError, TypeError, AttributeError):
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

    pre_src_status = _git_dirty_src_lines()

    pre_story_ids = _existing_story_ids()

    pre_existing_story_filenames = {
        path.name
        for path in _existing_story_files()
    }

    pre_decisions = list_decisions()

    pre_requests = {item["file"]: item["content"]
                    for item in read_requests(include_resolved=True)}

    pre_architect_requests = {
        request["file"]: request["content"]
        for request in architect_requests.list_requests()
    }

    pre_adr_files = _adr_snapshot()

    prompt, sections = build_planning_context()

    # Terminal only: what the harness is about to supply, by section.
    # Characters are not tokens -- the runner reports what the model
    # actually charged once the run finishes.
    print_status(
        planning_context.context_report("Planner", sections, prompt)
    )

    def _validate(planner_exit_code: int) -> dict:
        """Read and validate this pass's result, still inside the rollback.

        Returns the validated result; a FAILED one makes
        _run_guarded_planner() restore the previous queue before anything
        else sees it. A missing or unparseable result is a FAILED result for
        the same reason -- it used to be returned as-is, leaving whatever the
        pass had already written to story files and BACKLOG.md in place.
        """

        if not PLANNING_RESULT_FILE.exists():
            return {
                "status": "FAILED",
                "reason": (
                    "Planning run did not produce a new "
                    "agent/runtime/artifacts/PLANNING_RESULT.json."
                ),
                "planner_exit_code": planner_exit_code,
            }

        try:
            raw_result = json.loads(
                PLANNING_RESULT_FILE.read_text(
                    encoding="utf-8"
                )
            )
        except (OSError, json.JSONDecodeError) as exc:
            return {
                "status": "FAILED",
                "reason": (
                    "Could not parse "
                    f"agent/runtime/artifacts/PLANNING_RESULT.json: {exc}"
                ),
                "planner_exit_code": planner_exit_code,
            }

        checked = validate_planning_result(
            raw_result,
            pre_current_story_hash,
            pre_src_status,
            pre_story_ids,
            pre_existing_story_filenames,
            pre_decisions,
            pre_requests,
            pre_architect_requests,
            pre_adr_files,
        )

        # The index is validated with the result: a pass that left
        # BACKLOG.md unparseable has broken story selection for every later
        # run, which is a failed pass however good its JSON looks.
        entry_problems = validate_backlog_entries(
            BACKLOG_FILE.read_text(encoding="utf-8")
        )

        if entry_problems:
            checked.setdefault("validation_problems", []).extend(entry_problems)
            checked["status"] = "FAILED"

        checked["planner_exit_code"] = planner_exit_code

        return checked

    try:
        planner_exit_code, validated = _run_guarded_planner(
            prompt,
            validate=_validate,
        )
    except PlanningRejected as rejected:
        # The rollback has already restored the previous queue, including the
        # previous PLANNING_RESULT.json, so the failure is recorded now --
        # after the restore, not before it.
        write_json(PLANNING_RESULT_FILE, rejected.result)

        print(
            "\n========================================"
        )
        print(
            "Planning result: FAILED (all changes rolled back)"
        )
        print(
            f"Reason: {rejected.result.get('reason', '')}"
        )

        for problem in rejected.result.get("validation_problems", []):
            print(f"- {problem}")

        print(
            "========================================\n"
        )

        return rejected.result

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
