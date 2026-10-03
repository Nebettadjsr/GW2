import json

from agent.runtime.runners.local_planner_runner import run_architect
from agent.runtime.support.capacity import ModelCapacityUnavailable
from agent.runtime.support import config
from agent.runtime.support.config import (
    ARCHITECT_INSTRUCTIONS_FILE,
    ARCHITECT_RESULT_FILE,
    BACKLOG_FILE,
    CURRENT_STORY_FILE,
    PROJECT_STATE_FILE,
    PRODUCT_OWNER_REQUESTS_DIR,
    REPO_ROOT,
    ROLE_CONTRACT_FILES,
    STORIES_DIR,
    USER_DECISIONS_DIR,
)
from agent.runtime.support.files import file_hash, write_json
from agent.runtime.support.daily_log import print_status
from agent.runtime.core.planning_context import context_report
from agent.runtime.human import architect_requests
from agent.runtime.human.user_decisions import (
    extract_section as extract_markdown_section,
    is_placeholder as is_decision_placeholder,
    list_decisions,
    missing_required_headings as missing_decision_headings,
)

# One implementation of the src/ safety check, shared with the planner:
# neither Codex role may touch application source.
from agent.runtime.core.project_planner import _git_dirty_src_lines


# ============================================================
# ARCHITECTURE MODE (agent/ARCHITECT_INSTRUCTIONS.md)
#
# The architect answers ONE architect request per invocation and is
# invoked only while an unresolved request exists -- there is no
# periodic architecture review and no run against an empty inbox.
#
# Everything here is the deterministic half of that role: which request
# was dispatched, what the model was allowed to write, and whether the
# reported result matches what actually happened on disk. The
# architectural judgment itself belongs entirely to the model and its
# role contract; Python never decides an architecture question, and
# never answers a User Decision on the Product Owner's behalf.
# ============================================================

# Paths ARCHITECTURE MODE must never write ("Forbidden Writes" in
# agent/ARCHITECT_INSTRUCTIONS.md). Verified byte-for-byte after every
# run and rolled back if touched, the same way the planner's guard
# protects the active story -- there is exactly one writer of workflow
# state at a time, by construction.
def _protected_paths(dispatched_request_path):
    protected = {
        CURRENT_STORY_FILE,
        PROJECT_STATE_FILE,
        BACKLOG_FILE,
    }

    # A role may never rewrite the contract that governs it, its own or
    # another role's. Observed in a real run: an architect pass appended
    # a new subsection to its own instructions while answering an
    # unrelated question.
    protected.update(ROLE_CONTRACT_FILES)

    if STORIES_DIR.exists():
        protected.update(STORIES_DIR.rglob("*.md"))

    if PRODUCT_OWNER_REQUESTS_DIR.exists():
        protected.update(PRODUCT_OWNER_REQUESTS_DIR.glob("*.md"))

    # Every OTHER architect request: one invocation answers exactly one
    # question, so a pass may not quietly resolve or rewrite the rest of
    # the inbox.
    for path in architect_requests.list_request_files():
        if path != dispatched_request_path:
            protected.add(path)

    return {path: path.read_bytes() if path.exists() else None
            for path in protected}


def _architecture_snapshot():
    """Every Markdown file either role owns, for change detection and rollback."""

    paths = set((REPO_ROOT / "docs").rglob("*.md"))
    paths.update(
        path
        for path in (REPO_ROOT / "agent").rglob("*.md")
        if "artifacts" not in path.parts
    )

    return {path: path.read_bytes() for path in paths if path.is_file()}


def _relative(path) -> str:
    return path.relative_to(REPO_ROOT).as_posix()


def _changed_paths(before: dict, after: dict) -> set:
    changed = {
        path
        for path in after.keys() - before.keys()
    }

    changed.update(
        path
        for path, content in after.items()
        if path in before and before[path] != content
    )

    return changed


# ============================================================
# Architect prompt
#
# Python supplies the dispatched request and the role contract. The
# model is not allowed to pick its own question, scan the inbox, or
# decide when architecture work happens.
# ============================================================

def build_architect_context(request: dict) -> tuple[str, dict]:
    """The architect prompt plus the size of each supplied section."""

    instructions = ARCHITECT_INSTRUCTIONS_FILE.read_text(encoding="utf-8")

    decisions = list_decisions()

    if decisions:
        decisions_block = "\n".join(
            f"- {decision['id']} ({decision['status']}): "
            f"{decision['title'] or decision['file']}"
            for decision in decisions
        )
    else:
        decisions_block = "(no files under agent/user-decisions/)"

    request_file = f"agent/architect-requests/{request['file']}"

    sections = {
        "dispatched request": request["content"],
        "user decision index": decisions_block,
        "document ownership": config.ownership_table(config.ARCHITECT),
        "role instructions": instructions,
    }

    ownership_table = config.ownership_table(config.ARCHITECT)

    prompt = f"""ARCHITECTURE MODE

You are the project architect. Your authoritative role contract is
agent/ARCHITECT_INSTRUCTIONS.md, reproduced in full at the end of this prompt.

SUPPLIED CONTEXT
================

Your full role contract is already in this prompt, below. Do not read or
reread agent/ARCHITECT_INSTRUCTIONS.md, and do not read AGENTS.md: its
instruction to read your contract is already satisfied here, and reading
either again only resends the same text to the model.

The dispatched request is supplied below in full. Read further files only
where the answer genuinely depends on them, a section at a time rather than
whole documents, batching independent reads into one command and never
reading the same section twice.

DOCUMENT OWNERSHIP (enforced by the harness, not advisory)
==========================================================

{ownership_table}

DISPATCHED REQUEST LOCK
=======================

Python has already selected the single architect request for this run:

{request_file}

Answer ONLY that request.

- Do NOT read, answer, resolve or edit any other file under
  agent/architect-requests/.
- Do NOT perform PROJECT PLANNING MODE duties: no stories, no backlog, no
  roadmap phase planning, no agent/PROJECT_STATE.md, no agent/CURRENT_STORY.md.
- Do NOT modify src/, build files, or implementation tests.
- Do NOT perform a general architecture review. The dispatched question is the
  entire scope of this run.

If answering the question reveals other architecture problems, state them in
your Resolution so the planner or a later request can pick them up. Do not
start working on them now.

DISPATCHED REQUEST CONTENTS
===========================

--- BEGIN {request['file']} ---
{request['content']}
--- END {request['file']} ---

EXISTING USER DECISIONS
=======================

{decisions_block}

Never contradict a RESOLVED decision. Never invent the Product Owner's answer
to an OPEN one.

REQUIRED OUTCOME
================

Classify the question first (Class A/B/C/D in your role contract), then take
exactly one of these three outcomes.

1. RESOLVED (result status COMPLETE) -- the expected outcome

   Decide the question yourself whenever it is a technical one. That includes a
   Class C technology selection, and it includes a technology the documents
   still mark TBD: turning a documented TBD into a recorded, reasoned decision
   is this role's job. "Several options are viable" is not grounds for
   escalation -- it is the reason you were asked.

   docs/TARGET_ARCHITECTURE.md §30 forbids an *implementation agent* from
   picking a TBD technology out of familiarity without an explicit project
   decision. Your decision, recorded in the owning document and an ADR, IS
   that explicit project decision.

   Judge it on the merits, for this project (see HOW TO DECIDE below), then:

   - update the architecture documents your contract allows, where the answer
     belongs (do not duplicate the same fact into more than one owner);
   - record a significant decision as docs/architecture/decisions/ADR-<NUMBER>-<slug>.md
     when your contract's ADR rules call for one; do not create an ADR for a
     trivial choice;
   - rewrite the request file's own sections:
       ## Status -> RESOLVED
       ## Architect Decision -> the decision itself, named concretely, with the
         reasoning and the runner-up you rejected and why
       ## Resolution -> what you changed and what follow-up the planner owns
   - leave ## Blocking User Decision as None.

HOW TO DECIDE A TECHNOLOGY CHOICE
=================================

Do not reduce this to "both are popular, so ask the human". Work the
trade-offs, and state which ones actually decided it:

- what specific problem each option solves, and whether this project has that
  problem at all;
- concrete benefits and downsides for THIS codebase and its documented
  requirements -- the backend-owned domain, the existing HTTP boundary, the
  data shapes the frontend has to render (tables, recursive resolution trees,
  special/blocked states), and the documented page-load expectation;
- what size and shape of project each option is normally used for, and whether
  this project matches it: a single-maintainer, single-user desktop-to-web
  migration, not a large product team;
- ecosystem and maintenance reality: stability, release cadence, documentation
  quality, how much churn the project would inherit, how long support lasts;
- how much of the option's complexity this project would actually use, and
  what it costs to carry the rest;
- maintainability by coding agents working one bounded story at a time, since
  that is how this repository is developed;
- operational cost and added moving parts;
- reversibility: how expensive is changing your mind later.

Then eliminate options whose extra complexity buys this project nothing, and
choose the strongest overall fit. If the remaining options are genuinely close
on the evidence you have, pick the simpler, more conventional one for a project
this size and say that this is why -- a reasoned tie-break is a decision, not
an escalation.

Use only evidence you actually have. Do not invent benchmarks, adoption
statistics or measurements; if a claim is your judgement rather than a
documented fact, say so as judgement.

2. NEEDS_USER (result status NEEDS_USER) -- the narrow exception

   Escalate only when the remaining choice does not turn on technical merit at
   all, i.e. when it depends on a non-technical preference, or materially
   changes one of: product behavior; user experience not already specified;
   project scope; recurring monetary cost; deployment/hosting expectations;
   security/privacy expectations; operational responsibility; the supported
   user model.

   If you can settle it on technical grounds, you must. Do not escalate because
   alternatives exist, because a document says TBD, because you would like the
   human to confirm your recommendation, or because the decision feels
   significant.

   - create or reuse an OPEN agent/user-decisions/UD-<NUMBER>-<slug>.md file,
     using the existing User Decision format: Status, Decision Needed, Why This
     Is Needed, Context, Blocks, External Input Possibly Required, User
     Decision, Resolution;
   - a NEW decision file starts with Status OPEN and literal TODO for both User
     Decision and Resolution. Never write the answer yourself;
   - give the Product Owner real analysis in the decision file: constraints,
     viable alternatives, meaningful trade-offs, your recommendation when the
     evidence supports one, and what work is blocked;
   - rewrite the request file's own sections:
       ## Status -> NEEDS_USER
       ## Blocking User Decision -> the UD ID(s), e.g. UD-009
       ## Architect Decision -> your analysis so far and what you recommend
       ## Resolution -> leave as TODO (the question is not resolved yet)

   The orchestrator invokes you again for this same request automatically once
   every listed decision is RESOLVED. Do not ask the human anything except
   through that decision file.

3. FAILED (result status FAILED)

   You could not produce either outcome. Leave the request file's Status
   exactly as you found it and explain why in the result's reason.

ARCHITECT RESULT
================

The run is not complete until you write:

agent/runtime/artifacts/ARCHITECT_RESULT.json

with exactly these fields:

{{
  "status": "COMPLETE" | "NEEDS_USER" | "FAILED",
  "architect_request": "{request['file']}",
  "user_decision_ids": ["UD-NUMBER", ...],
  "architecture_artifacts_changed": ["docs/...", ...],
  "reason": "<short explanation grounded in the authoritative sources you used>"
}}

Rules Python verifies deterministically and will reject:

- status COMPLETE requires the request file to be RESOLVED with a non-placeholder
  Architect Decision and Resolution, and an empty user_decision_ids;
- status NEEDS_USER requires the request file to be NEEDS_USER, a non-empty
  user_decision_ids, each of those decisions to exist and be OPEN, and the
  request's own Blocking User Decision section to name every one of them;
- status FAILED requires the request file's Status to be unchanged;
- architecture_artifacts_changed must list every Markdown file you changed
  except the request file itself and the user-decision files (those are already
  reported above), each as a repository-relative path that really changed;
- you may not resolve an existing User Decision;
- you may not touch the protected planner/harness files listed above.

Stop immediately after writing ARCHITECT_RESULT.json.

AUTHORITATIVE ARCHITECT INSTRUCTIONS
====================================

{instructions}
"""

    return prompt, sections


def build_architect_prompt(request: dict) -> str:
    return build_architect_context(request)[0]


# ============================================================
# Guarded run
# ============================================================

def _run_guarded_architect(prompt: str, request_path):
    """One synchronous writer; a failed run cannot leave partial architecture state."""

    before = _architecture_snapshot()
    protected = _protected_paths(request_path)

    try:
        code = run_architect(prompt)

        for path, content in protected.items():
            if (path.read_bytes() if path.exists() else None) != content:
                raise RuntimeError(
                    f"Architect modified protected state: {_relative(path)}"
                )

        if code != 0:
            raise RuntimeError(f"Codex architect exited with code {code}")

        return code, before
    except (ModelCapacityUnavailable, RuntimeError, ValueError, OSError):
        after = _architecture_snapshot()

        for path in after.keys() - before.keys():
            path.unlink()

        for path, content in before.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)

        raise


# ============================================================
# Deterministic validation
# ============================================================

def validate_architect_result(
        raw_result: dict,
        request: dict,
        before: dict,
        after: dict,
        pre_decisions: list,
        pre_src_status: set,
) -> dict:

    result = dict(raw_result)
    problems = []

    status = result.get("status")
    original_status = status

    if status not in ("COMPLETE", "NEEDS_USER", "FAILED"):
        problems.append(f"Unknown or missing architect status: {status!r}")
        status = "FAILED"

    reported_request = result.get("architect_request")

    if reported_request and reported_request != request["file"]:
        problems.append(
            "architect_request reports "
            f"{reported_request!r}, but the dispatched request was "
            f"{request['file']!r}."
        )
        status = "FAILED"

    result["architect_request"] = request["file"]

    if _git_dirty_src_lines() - pre_src_status:
        problems.append(
            "Architect run modified files under src/, which is not permitted."
        )
        status = "FAILED"

    request_path = request["path"]

    if not request_path.exists():
        problems.append(
            f"The dispatched request file was deleted: {request['file']}"
        )

        result["status"] = "FAILED"
        result["validation_problems"] = problems

        return result

    post_content = request_path.read_text(encoding="utf-8")
    post_status = architect_requests.extract_status(post_content)

    missing_headings = architect_requests.missing_required_headings(post_content)

    if missing_headings:
        problems.append(
            f"{request['file']} is missing required sections after the run: "
            + ", ".join(missing_headings)
        )
        status = "FAILED"

    # ------------------------------------------------------------
    # Lifecycle transition
    # ------------------------------------------------------------

    if original_status == "COMPLETE":
        if post_status != "RESOLVED":
            problems.append(
                "Architect reported COMPLETE but "
                f"{request['file']}'s Status is {post_status!r}, not RESOLVED."
            )
            status = "FAILED"

        for heading in architect_requests.ANSWER_SECTIONS:
            if architect_requests.is_placeholder(
                    architect_requests.section(post_content, heading)
            ):
                problems.append(
                    f"{request['file']} is RESOLVED but its '{heading}' "
                    "section is still a placeholder."
                )
                status = "FAILED"

    elif original_status == "NEEDS_USER":
        if post_status != "NEEDS_USER":
            problems.append(
                "Architect reported NEEDS_USER but "
                f"{request['file']}'s Status is {post_status!r}."
            )
            status = "FAILED"

    elif original_status == "FAILED" and post_status != request["status"]:
        problems.append(
            "Architect reported FAILED but changed "
            f"{request['file']}'s Status from {request['status']!r} to "
            f"{post_status!r}; a failed run must leave the request as it "
            "found it."
        )

    # ------------------------------------------------------------
    # User decisions -- the ONE escalation path, never answered here
    # ------------------------------------------------------------

    decision_ids = result.get("user_decision_ids") or []

    if not isinstance(decision_ids, list):
        problems.append("user_decision_ids must be a list.")
        status = "FAILED"
        decision_ids = []

    post_decisions = list_decisions()
    post_by_id = {
        decision["id"]: decision
        for decision in post_decisions
        if decision["id"]
    }
    pre_by_file = {decision["file"]: decision for decision in pre_decisions}

    if original_status == "COMPLETE" and decision_ids:
        problems.append(
            "Architect reported COMPLETE but also reported blocking user "
            f"decisions {decision_ids}; a resolved request has none."
        )
        status = "FAILED"

    if original_status == "NEEDS_USER":
        if not decision_ids:
            problems.append(
                "Architect reported NEEDS_USER but user_decision_ids is "
                "empty -- an escalation must reference an OPEN user-decision "
                "file."
            )
            status = "FAILED"

        recorded = architect_requests.blocking_decision_ids(post_content)

        for decision_id in decision_ids:
            if decision_id not in recorded:
                problems.append(
                    f"{request['file']} does not name {decision_id} in its "
                    "'Blocking User Decision' section, so the architect "
                    "would never be resumed for it."
                )
                status = "FAILED"

    for decision_id in decision_ids:
        referenced = post_by_id.get(decision_id)

        if referenced is None:
            problems.append(
                f"user_decision_ids references {decision_id!r}, which does "
                "not correspond to any file under agent/user-decisions/."
            )
            status = "FAILED"
        elif referenced["status"] != "OPEN":
            problems.append(
                f"user_decision_ids references {decision_id!r}, but that "
                f"decision's Status is {referenced['status']!r}, not OPEN."
            )
            status = "FAILED"

    for decision in post_decisions:
        previous = pre_by_file.get(decision["file"])
        content = (USER_DECISIONS_DIR / decision["file"]).read_text(
            encoding="utf-8"
        )

        if previous is None:
            missing = missing_decision_headings(content)

            if missing:
                problems.append(
                    f"{decision['file']} is missing required sections: "
                    + ", ".join(missing)
                )
                status = "FAILED"

            if decision["status"] != "OPEN":
                problems.append(
                    f"{decision['file']} is a newly created decision file "
                    "but its Status is not OPEN."
                )
                status = "FAILED"

            if not is_decision_placeholder(
                    extract_markdown_section(content, "User Decision")
            ) or not is_decision_placeholder(
                extract_markdown_section(content, "Resolution")
            ):
                problems.append(
                    f"{decision['file']} was created this run but already "
                    "has a non-TODO User Decision/Resolution -- the "
                    "architect must not invent the Product Owner's answer."
                )
                status = "FAILED"

            continue

        if previous["status"] == "OPEN" and decision["status"] == "RESOLVED":
            problems.append(
                f"{decision['file']} was marked RESOLVED by the architect; "
                "only the human Product Owner resolves a user decision."
            )
            status = "FAILED"

    # ------------------------------------------------------------
    # Reported architecture artifacts must match what really changed
    # ------------------------------------------------------------

    reported_artifacts = result.get("architecture_artifacts_changed") or []

    if not isinstance(reported_artifacts, list):
        problems.append("architecture_artifacts_changed must be a list.")
        status = "FAILED"
        reported_artifacts = []

    changed = {_relative(path) for path in _changed_paths(before, after)}

    # The request file and the user-decision files are already reported
    # through their own fields.
    accounted = {_relative(request_path)}
    accounted.update(
        _relative(USER_DECISIONS_DIR / decision["file"])
        for decision in post_decisions
    )

    reported = {name for name in reported_artifacts if isinstance(name, str)}

    for name in sorted(reported - changed):
        problems.append(
            f"architecture_artifacts_changed reports {name!r}, which did "
            "not change during this run."
        )
        status = "FAILED"

    for name in sorted(changed - reported - accounted):
        problems.append(
            f"{name} was changed but not reported in "
            "architecture_artifacts_changed."
        )
        status = "FAILED"

    result["architecture_artifacts_changed"] = sorted(reported)
    result["user_decision_ids"] = [
        decision_id for decision_id in decision_ids
        if isinstance(decision_id, str)
    ]
    result["request_status"] = post_status

    duplicate_ids = architect_requests.find_duplicate_request_ids()

    if duplicate_ids:
        problems.append(
            "Duplicate architect request IDs exist under "
            f"agent/architect-requests/: {', '.join(duplicate_ids)}"
        )
        status = "FAILED"

    if problems:
        result["validation_problems"] = problems

    result["status"] = status

    return result


# ============================================================
# Architect run
# ============================================================

def run_architect_pass(request: dict) -> dict:

    print("\n========================================")
    print(f"Running architect pass for {request['file']}")
    print("========================================\n")

    pre_src_status = _git_dirty_src_lines()
    pre_decisions = list_decisions()
    pre_result_hash = file_hash(ARCHITECT_RESULT_FILE)

    prompt, sections = build_architect_context(request)

    # Terminal only, same accounting the planner prints: supplied
    # characters per section, never the sections themselves.
    print_status(context_report("Architect", sections, prompt))

    architect_exit_code, before = _run_guarded_architect(
        prompt, request["path"]
    )

    after = _architecture_snapshot()

    post_result_hash = file_hash(ARCHITECT_RESULT_FILE)

    if (
            not ARCHITECT_RESULT_FILE.exists()
            or post_result_hash == pre_result_hash
    ):
        result = {
            "status": "FAILED",
            "architect_request": request["file"],
            "user_decision_ids": [],
            "architecture_artifacts_changed": [],
            "reason": (
                "Architect run did not produce a new "
                "agent/runtime/artifacts/ARCHITECT_RESULT.json."
            ),
            "architect_exit_code": architect_exit_code,
        }

        write_json(ARCHITECT_RESULT_FILE, result)

        return result

    try:
        raw_result = json.loads(
            ARCHITECT_RESULT_FILE.read_text(encoding="utf-8")
        )
    except (OSError, json.JSONDecodeError) as exc:
        result = {
            "status": "FAILED",
            "architect_request": request["file"],
            "user_decision_ids": [],
            "architecture_artifacts_changed": [],
            "reason": (
                "Could not parse "
                f"agent/runtime/artifacts/ARCHITECT_RESULT.json: {exc}"
            ),
            "architect_exit_code": architect_exit_code,
        }

        write_json(ARCHITECT_RESULT_FILE, result)

        return result

    validated = validate_architect_result(
        raw_result,
        request,
        before,
        after,
        pre_decisions,
        pre_src_status,
    )

    validated["architect_exit_code"] = architect_exit_code

    # A rejected pass must not leave a half-applied lifecycle behind:
    # the request file is pure workflow state and its Status is what
    # drives scheduling, so restore exactly the question that was
    # dispatched rather than trusting an unvalidated transition. Any
    # document edits the pass made are deliberately left in place and
    # reported, as with a rejected planning pass, so a human can see
    # what it actually did.
    if validated.get("status") == "FAILED":
        previous = before.get(request["path"])

        if previous is not None and request["path"].read_bytes() != previous:
            request["path"].write_bytes(previous)
            validated["request_restored"] = True
            validated["request_status"] = request["status"]

        # A rejected escalation can still have left a real, useful
        # decision file behind. It is deliberately NOT deleted -- the
        # analysis in it is genuine work and the human may answer it --
        # but it is now unreferenced, so say so rather than leaving it
        # to be discovered. The next pass for this same request is told
        # to reuse an existing OPEN decision instead of creating a
        # second one.
        known = {decision["file"] for decision in pre_decisions}

        orphaned = sorted(
            decision["file"]
            for decision in list_decisions()
            if decision["file"] not in known
        )

        if orphaned:
            validated["orphaned_user_decisions"] = orphaned

    write_json(ARCHITECT_RESULT_FILE, validated)

    print("\n========================================")
    print(f"Architect result: {validated.get('status')}")
    print(f"Request: {validated.get('architect_request')} "
          f"-> {validated.get('request_status')}")
    print(f"Reason: {validated.get('reason', '')}")

    if validated.get("validation_problems"):
        print("Validation problems:")

        for problem in validated["validation_problems"]:
            print(f"- {problem}")

    print("========================================\n")

    return validated
