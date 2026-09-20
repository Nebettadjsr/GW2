from pathlib import Path
import time

from claude_runner import run_claude, wait_for_claude_capacity
from config import (
    CLAUDE_RESULT_FILE,
    MAX_RETRIES_PER_STORY,
    MODEL,
    NEXT_PROMPT_FILE,
    REPO_ROOT,
    USER_DECISION_POLL_SECONDS,
)
from dispatcher import build_claude_prompt, dispatch_story
from evaluator import evaluate_story
from files import file_hash, read_file
from project_planner import run_planning_pass, should_trigger_planning
from selector import select_next_story
from story_state import (
    classify_story_status,
    extract_status_section,
    get_active_story_path,
    get_ready_story_filenames,
    get_selectable_story_candidates,
    resolve_story_path,
    set_active_story,
)
from user_decisions import list_decisions


# ============================================================
# Retry prompt
# ============================================================

def build_retry_prompt(
        actionable_retry_items: list[str],
        reason: str,
        story_path: Path
) -> str:

    relative_story = story_path.relative_to(
        REPO_ROOT
    ).as_posix()

    items_block = "\n".join(
        f"- {item}" for item in actionable_retry_items
    )

    return f"""Continue the active story.

Active story:
{relative_story}

The previous execution did not yet satisfy the Definition of Done.

Concrete deficiencies to address, and only these:
{items_block}

Evaluator reason (context only -- does not add scope beyond the items above):
{reason}

Inspect the current repository state and continue from the existing work.

Do not restart completed work unnecessarily.

Do not perform any work outside the deficiencies listed above -- in
particular, do not touch roadmap/milestone planning, future/next
stories, or unrelated documentation unless one of the listed
deficiencies explicitly requires it.

Use CLAUDE.md and the active story as the source of truth.

Stop after completing or blocking this story.
"""


# ============================================================
# Story execution loop
# ============================================================

def execute_active_story() -> str:

    story_path = get_active_story_path()

    relative_story = story_path.relative_to(
        REPO_ROOT
    ).as_posix()

    print(
        "\n========================================"
    )
    print(
        f"Active story: {relative_story}"
    )
    print(
        "========================================"
    )

    story_content = read_file(
        story_path
    )

    status_text = extract_status_section(
        story_content
    )

    classification = classify_story_status(
        status_text
    )

    if classification == "DONE":
        print(
            "\nActive story status is DONE. "
            "Skipping Claude Code and treating "
            "the story as complete."
        )

        return "COMPLETE"

    if classification == "BLOCKED":
        print(
            "\nActive story status is BLOCKED. "
            "Stopping the orchestrator without "
            "calling Claude Code."
        )

        print(
            f"Status: {status_text}"
        )

        return "BLOCKED"

    plan = dispatch_story(
        story_content
    )

    if plan.get(
            "status"
    ) == "NEEDS_MORE_CONTEXT":

        print(
            "\nHermes needs more context:"
        )
        print(
            plan.get(
                "reason",
                "No reason supplied."
            )
        )

        return "NEEDS_USER"

    if plan.get(
            "status"
    ) != "READY":

        raise RuntimeError(
            f"Unexpected dispatcher result: {plan}"
        )

    prompt = build_claude_prompt(
        plan,
        story_path
    )

    NEXT_PROMPT_FILE.write_text(
        prompt + "\n",
        encoding="utf-8"
    )

    retry_count = 0

    while True:

        old_result_hash = file_hash(
            CLAUDE_RESULT_FILE
        )

        claude_exit_code = run_claude(
            prompt
        )

        new_result_hash = file_hash(
            CLAUDE_RESULT_FILE
        )

        result_was_updated = (
                new_result_hash is not None
                and new_result_hash
                != old_result_hash
        )

        if CLAUDE_RESULT_FILE.exists():
            result_content = read_file(
                CLAUDE_RESULT_FILE
            )
        else:
            result_content = (
                "CLAUDE_RESULT.md does not exist."
            )

        story_content = read_file(
            story_path
        )

        evaluation = evaluate_story(
            story_content,
            result_content,
            claude_exit_code,
            result_was_updated
        )

        decision = evaluation[
            "decision"
        ]

        reason = evaluation[
            "reason"
        ]

        print(
            "\n========================================"
        )
        print(
            f"Evaluator decision: {decision}"
        )
        print(
            f"Reason: {reason}"
        )
        print(
            "========================================\n"
        )

        if decision == "COMPLETE":
            return "COMPLETE"

        if decision == "BLOCKED":
            return "BLOCKED"

        if decision == "NEEDS_USER":
            return "NEEDS_USER"

        if decision != "RETRY":
            raise RuntimeError(
                f"Unknown evaluator decision: {decision}"
            )

        actionable_retry_items = evaluation.get(
            "actionable_retry_items"
        ) or []

        if not actionable_retry_items:
            # normalize_evaluation() should never hand back an empty
            # RETRY -- this is a defense-in-depth guard, not the
            # primary mechanism. Refuse to spend another Claude run on
            # a deficiency-free RETRY.
            print(
                "\nEvaluator returned RETRY with no actionable "
                "deficiencies after normalization. Refusing to retry."
            )

            return "NEEDS_USER"

        retry_count += 1

        if retry_count > MAX_RETRIES_PER_STORY:
            print(
                "Maximum automatic retries reached."
            )

            return "NEEDS_USER"

        print(
            f"Retrying story "
            f"({retry_count}/{MAX_RETRIES_PER_STORY})..."
        )

        prompt = build_retry_prompt(
            actionable_retry_items,
            reason,
            story_path
        )

        NEXT_PROMPT_FILE.write_text(
            prompt + "\n",
            encoding="utf-8"
        )


# ============================================================
# User-decision polling (planner NEEDS_USER handling)
#
# NEEDS_USER from the planner blocks only the work that actually
# depends on the unresolved decision(s) -- never the whole orchestrator.
# These helpers are purely local/deterministic: they only read
# agent/user-decisions/*.md (via the same parsing project_planner.py
# already uses for validation) and never invoke Codex, Claude, Hermes,
# Ollama, or the project planner themselves.
# ============================================================

def get_unresolved_user_decisions(
        decision_ids: list[str]
) -> list[dict]:
    """
    Resolve each PLANNING_RESULT.json user_decision_id against
    agent/user-decisions/*.md with no model calls. A decision ID with
    no matching file, no readable '## Status', or a status other than
    RESOLVED is treated as unresolved -- never assumed resolved.
    """

    decisions_by_id = {
        decision["id"]: decision
        for decision in list_decisions()
        if decision["id"]
    }

    unresolved = []

    for decision_id in decision_ids:
        decision = decisions_by_id.get(decision_id)

        if decision is None or decision["status"] != "RESOLVED":
            unresolved.append(
                decision
                or {
                    "id": decision_id,
                    "file": None,
                    "status": None,
                }
            )

    return unresolved


def _describe_unresolved_decision(
        decision: dict
) -> str:
    decision_id = decision.get("id") or "?"

    if decision.get("file") is None:
        return (
            f"{decision_id} (no matching file under "
            "agent/user-decisions/)"
        )

    if decision.get("status") is None:
        return (
            f"{decision_id} (no readable '## Status' in "
            f"{decision['file']})"
        )

    return f"{decision_id} ({decision['status']})"


def wait_for_user_decisions(
        decision_ids: list[str]
) -> None:
    """
    Block until every listed decision ID is RESOLVED. Checks once
    immediately (the user may have already resolved it before this
    state was entered) and, if still unresolved, prints which IDs are
    blocking and sleeps for USER_DECISION_POLL_SECONDS before each
    recheck -- a plain local file read, never a model call. Remains
    interruptible with Ctrl+C: KeyboardInterrupt is never caught here
    and propagates normally.
    """

    unresolved = get_unresolved_user_decisions(
        decision_ids
    )

    if not unresolved:
        print(
            "\nUser decision resolved: " + ", ".join(decision_ids)
        )
        print(
            "Re-running project planning."
        )
        return

    print(
        "Waiting for user decision resolution."
    )

    first_check = True

    while unresolved:
        if not first_check:
            print(
                "Still unresolved: "
                + ", ".join(
                    _describe_unresolved_decision(decision)
                    for decision in unresolved
                )
            )

        print(
            "Next local check in "
            f"{USER_DECISION_POLL_SECONDS // 60} minutes."
        )

        first_check = False

        time.sleep(
            USER_DECISION_POLL_SECONDS
        )

        unresolved = get_unresolved_user_decisions(
            decision_ids
        )

    print(
        "\nUser decision resolved: " + ", ".join(decision_ids)
    )
    print(
        "Re-running project planning."
    )


# ============================================================
# Main orchestrator loop
# ============================================================

def main() -> None:

    print(
        "GW2 AI Orchestrator started."
    )

    print(
        f"Repository: {REPO_ROOT}"
    )

    print(
        f"Model:      {MODEL}"
    )

    print()

    while True:

        # Usage guard only before a new story begins.
        wait_for_claude_capacity()

        result = execute_active_story()

        if result == "BLOCKED":
            print(
                "\nOrchestrator stopped: "
                "active story is blocked."
            )
            break

        if result == "NEEDS_USER":
            print(
                "\nOrchestrator stopped: "
                "human input is required."
            )
            break

        if result != "COMPLETE":
            print(
                f"\nUnexpected story result: {result}"
            )
            break

        print(
            "\nStory completed."
        )

        ready_count = len(
            get_ready_story_filenames()
        )

        planning_failed = False
        planning_pass_count = 0

        while should_trigger_planning(
                ready_count
        ):
            planning_pass_count += 1

            if planning_pass_count > 3:
                raise RuntimeError(
                    "More than 3 consecutive planning passes were "
                    "requested. Refusing to loop indefinitely."
                )

            print(
                f"\nOnly {ready_count} selectable 'To Do' "
                "stor" + ("y" if ready_count == 1 else "ies")
                + " remaining -- running a project-planning pass."
            )

            planning_result = run_planning_pass()

            planning_status = planning_result.get("status")

            if planning_status == "NEEDS_USER":
                user_decision_ids = (
                        planning_result.get("user_decision_ids") or []
                )

                print(
                    "\nPlanning requires user input: "
                    + (
                        ", ".join(user_decision_ids)
                        if user_decision_ids
                        else "(no decision IDs reported)"
                    )
                )

                selectable = get_selectable_story_candidates()

                if selectable:
                    count = len(selectable)

                    if count == 1:
                        print(
                            "1 independent To Do story remains; "
                            "continuing normal execution."
                        )
                    else:
                        print(
                            f"{count} independent To Do stories "
                            "remain; continuing normal execution."
                        )

                    # Leave the unresolved decision(s) untouched and
                    # fall through to normal deterministic selection
                    # below -- NEEDS_USER blocks only the work that
                    # actually depends on it, never unrelated
                    # current-milestone work.
                    break

                print(
                    "No independent executable stories remain."
                )

                wait_for_user_decisions(
                    user_decision_ids
                )

                # wait_for_user_decisions() only ever returns once
                # every listed decision is RESOLVED -- rerun planning
                # fresh so the planner (never this loop) interprets
                # the resolution and updates planning artifacts.
                #
                # A human resolving a decision is a genuine external
                # state change, not another iteration of the automatic
                # replanning sequence planning_pass_count guards
                # against -- reset it here so this rerun (and any
                # later, separately human-resolved rerun, however many
                # occur over the orchestrator's lifetime) never
                # accumulates toward the rapid/automatic-loop cap. A
                # still-OPEN decision never reaches this line at all,
                # since wait_for_user_decisions() only returns after
                # resolution.
                planning_pass_count = 0

                ready_count = len(
                    get_ready_story_filenames()
                )

                continue

            if planning_status != "COMPLETE":
                print(
                    "\nOrchestrator stopped: project "
                    "planning did not complete."
                )
                planning_failed = True
                break

            ready_count = len(
                get_ready_story_filenames()
            )

            # A milestone transition intentionally creates no
            # next-phase stories in the same planning pass. Run one
            # fresh, separately scoped pass so Python can expose only
            # the newly current roadmap phase.
            if planning_result.get(
                    "milestone_transition"
            ):
                print(
                    "\nMilestone transition validated -- "
                    "running a fresh planning pass for the "
                    "new current phase."
                )
                continue

            # Normal planning created/confirmed the current phase's
            # next work. Do not repeatedly call the planner merely
            # because the resulting READY count is still <= 2.
            break

        if planning_failed:
            break

        selection = select_next_story()

        decision = selection[
            "decision"
        ]

        reason = selection[
            "reason"
        ]

        print(
            "\n========================================"
        )
        print(
            f"Story selector: {decision}"
        )
        print(
            f"Reason: {reason}"
        )
        print(
            "========================================\n"
        )

        if decision == "NO_WORK":
            print(
                "No more READY stories. "
                "Orchestrator finished."
            )
            break

        if decision == "NEEDS_USER":
            print(
                "Story selection requires "
                "human input."
            )
            break

        if decision == "BLOCKED":
            print(
                "Every remaining 'To Do' story is blocked "
                "by its own canonical file."
            )
            break

        if decision != "NEXT":
            raise RuntimeError(
                f"Unknown selector decision: {decision}"
            )

        next_story = (
                selection.get("story_path") or ""
        ).strip()

        if not next_story:
            raise RuntimeError(
                "Selector returned NEXT without a valid "
                "story_path. Refusing to activate a story."
            )

        # Do not trust the selector's own judgment of what is
        # selectable -- re-derive it from BACKLOG.md directly and
        # reject anything else, including invented paths or stories
        # that are Active, Blocked, Done, or not listed at all.
        candidate_path = resolve_story_path(
            next_story
        )

        ready_filenames = get_ready_story_filenames()

        if candidate_path.name not in ready_filenames:
            raise RuntimeError(
                "Selector chose a story that is not listed "
                "under 'To Do' in BACKLOG.md: "
                f"{next_story}"
            )

        story_path = set_active_story(
            next_story
        )

        print(
            "Next story activated:"
        )

        print(
            story_path.relative_to(
                REPO_ROOT
            ).as_posix()
        )


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print(
            "\nInterrupted by user. Exiting."
        )
        raise SystemExit(130)