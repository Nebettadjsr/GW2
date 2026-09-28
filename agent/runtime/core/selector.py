from agent.runtime.support.config import SELECTOR_RESULT_FILE
from agent.runtime.support.files import read_file, write_json
from agent.runtime.core.story_state import (
    extract_status_section,
    classify_story_status,
    get_ready_story_filenames_ordered,
    get_selectable_story_candidates,
    get_unsatisfied_dependencies,
    STORIES_DIR,
)
from agent.runtime.human.user_decisions import get_open_decisions


# ============================================================
# Deterministic story selection
#
# Story selection has no AI involvement. An LLM (the evaluator model of
# the day) repeatedly
# selected nonexistent/invalid stories here, and every such result was
# already being overridden by Python -- so the model call added
# unreliability without adding any real decision-making. BACKLOG.md's
# '## To Do' section is the canonical execution order the planner
# already established; Python only needs to walk it top to bottom and
# pick the first entry that get_selectable_story_candidates() (itself
# fully deterministic -- file exists, not archived, not DONE, not
# blocked by its own Status/Dependencies) confirms is executable.
#
# A model is still used elsewhere (evaluator.py, on Codex) -- this
# module concerns story selection only.
# ============================================================

def select_next_story() -> dict:

    candidates = get_selectable_story_candidates()

    if candidates:
        chosen = candidates[0]

        result = {
            "decision": "NEXT",
            "story_path": chosen.name,
            "reason": (
                "Deterministic selection: first executable entry in "
                "BACKLOG.md's 'To Do' order "
                f"({chosen.name})."
            ),
        }

        write_json(
            SELECTOR_RESULT_FILE,
            result
        )

        return result

    result = _no_candidates_result()

    write_json(
        SELECTOR_RESULT_FILE,
        result
    )

    return result


def _no_candidates_result() -> dict:
    """
    Nothing in get_selectable_story_candidates() -- resolve NO_WORK vs
    NEEDS_USER vs BLOCKED from repository evidence only:

    - '## To Do' itself is empty                  -> NO_WORK
    - an OPEN user-decision file exists            -> NEEDS_USER,
      citing its ID (a human decision is the most likely reason
      nothing is selectable, and the harness must never invent one of
      its own if a real one is already on file)
    - every '## To Do' entry that still exists on disk explicitly
      declares itself blocked (Status/Dependencies)  -> BLOCKED, citing
      those entries and their own blocking text
    - otherwise (e.g. every remaining entry is simply missing/archived,
      with no explicit blocking text to cite)          -> NO_WORK

    General PROJECT_STATE.md/roadmap prose is never consulted here --
    only each story's own canonical file and agent/user-decisions/.
    """

    ready_filenames_ordered = get_ready_story_filenames_ordered()

    if not ready_filenames_ordered:
        return {
            "decision": "NO_WORK",
            "story_path": "",
            "reason": (
                "BACKLOG.md has no stories listed under 'To Do'."
            ),
        }

    open_decisions = get_open_decisions()

    if open_decisions:
        ids = ", ".join(
            decision["id"]
            for decision in open_decisions
            if decision["id"]
        )

        reason = (
            "All stories listed under BACKLOG.md's 'To Do' are "
            "blocked, done, archived, or missing per their own "
            "canonical file, and an open human decision exists that "
            "may explain why."
        )

        if ids:
            reason += f" See open decision(s): {ids}."

        return {
            "decision": "NEEDS_USER",
            "story_path": "",
            "reason": reason,
        }

    blocked_explanations = _collect_explicit_blocked_explanations(
        ready_filenames_ordered
    )

    if blocked_explanations:
        return {
            "decision": "BLOCKED",
            "story_path": "",
            "reason": (
                "Every remaining 'To Do' entry declares itself "
                "blocked in its own canonical file: "
                + "; ".join(blocked_explanations)
            ),
        }

    return {
        "decision": "NO_WORK",
        "story_path": "",
        "reason": (
            "All stories listed under BACKLOG.md's 'To Do' are "
            "blocked, done, archived, or missing per their own "
            "canonical story file, with no explicit blocking reason "
            "or open decision to report."
        ),
    }


def _collect_explicit_blocked_explanations(
    ready_filenames_ordered: list[str]
) -> list[str]:
    explanations = []

    for filename in ready_filenames_ordered:
        story_path = STORIES_DIR / filename

        if story_path.parent != STORIES_DIR or not story_path.exists():
            # Missing/archived -- nothing to cite, not a "blocked"
            # explanation.
            continue

        content = read_file(
            story_path
        )

        status_text = extract_status_section(
            content
        )

        classification = classify_story_status(
            status_text
        )

        if classification == "BLOCKED":
            explanations.append(
                f"{filename} (Status: {status_text.splitlines()[0]})"
            )
            continue

        unsatisfied = get_unsatisfied_dependencies(
            content
        )

        if unsatisfied:
            explanations.append(
                f"{filename} (" + "; ".join(unsatisfied) + ")"
            )

    return explanations
