from pathlib import Path
import time

from agent.runtime.runners.claude_runner import run_claude, wait_for_claude_capacity
from agent.runtime.support.config import (
    BACKLOG_FILE,
    CLAUDE_RESULT_FILE,
    MAX_RETRIES_PER_STORY,
    MODEL,
    NEXT_PROMPT_FILE,
    REPO_ROOT,
    USER_DECISION_POLL_SECONDS,
)
from agent.runtime.evaluation.dispatcher import build_claude_prompt, dispatch_story
from agent.runtime.evaluation.evaluator import evaluate_story
from agent.runtime.support.files import file_hash, read_file
from agent.runtime.core.project_planner import run_planning_pass, should_trigger_planning
from agent.runtime.core.selector import select_next_story
from agent.runtime.core.story_state import (
    build_story_index,
    classify_story_status,
    clear_story_blocked,
    ensure_backlog_entry_blocked,
    extract_status_section,
    extract_story_id,
    find_story_file_by_id,
    get_active_story_path,
    get_ready_story_filenames,
    get_selectable_story_candidates,
    get_unsatisfied_dependencies,
    move_backlog_entry_to_blocked,
    move_backlog_entry_to_todo,
    resolve_story_path,
    set_active_story,
    set_story_blocked,
    set_story_unfinished,
)
from agent.runtime.human.user_decisions import list_decisions
from agent.runtime.human.user_interventions import (
    create_intervention_file,
    get_open_interventions_for_story,
    list_interventions,
)


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
# Implementation-time human/tooling intervention (evaluator NEEDS_USER)
#
# Deterministic side effect of a NEEDS_USER outcome -- the evaluator
# only ever classifies the attempt; Python is what writes the
# artifact and moves the story. This never consumes the story's
# normal implementation retry count (MAX_RETRIES_PER_STORY governs
# RETRY only; this path never re-enters that loop).
# ============================================================

def _create_intervention_and_block_story(
        story_path: Path,
        story_content: str,
        reason: str,
        claude_response: str,
) -> None:
    story_id = extract_story_id(story_content) or story_path.stem

    intervention_path = create_intervention_file(
        story_id,
        reason,
        claude_response,
    )

    relative_intervention = intervention_path.relative_to(
        REPO_ROOT
    ).as_posix()

    set_story_blocked(
        story_path,
        f"Blocked on user intervention (see {relative_intervention}).",
    )

    backlog_content = read_file(
        BACKLOG_FILE
    )

    updated_backlog = move_backlog_entry_to_blocked(
        backlog_content,
        story_path.name,
        f"blocked on {intervention_path.name}",
    )

    if updated_backlog != backlog_content:
        BACKLOG_FILE.write_text(
            updated_backlog,
            encoding="utf-8"
        )

    print(
        "\nCreated user-intervention record: " + relative_intervention
    )
    print(
        f"{story_path.name} moved to BACKLOG '## Blocked'."
    )


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
            "Skipping Claude Code for this story."
        )

        print(
            f"Status: {status_text}"
        )

        # Deterministic bookkeeping only, no model call: make sure
        # BACKLOG.md reflects the block (idempotent -- a story Claude
        # Code or a human already blocked may have updated this
        # itself) before main() moves on to select other work.
        backlog_content = read_file(
            BACKLOG_FILE
        )

        updated_backlog = ensure_backlog_entry_blocked(
            backlog_content,
            story_path.name,
        )

        if updated_backlog != backlog_content:
            BACKLOG_FILE.write_text(
                updated_backlog,
                encoding="utf-8"
            )
            print(
                f"{story_path.name} moved to BACKLOG '## Blocked'."
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
        dispatch_reason = plan.get(
            "reason",
            "No reason supplied."
        )
        print(
            dispatch_reason
        )

        _create_intervention_and_block_story(
            story_path,
            story_content,
            dispatch_reason,
            "(Claude Code was not invoked this attempt -- the "
            "dispatcher could not build a work order from the story "
            f"file. Dispatcher reason: {dispatch_reason})",
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

        if claude_exit_code != 0:
            # An interrupted attempt is not an evaluator retry or a
            # story outcome. Keep the same story and work order.
            set_story_unfinished(story_path)
            print(
                f"Claude exited with code {claude_exit_code}. "
                "Story remains active and UNFINISHED; waiting for "
                "capacity before continuing the same story."
            )
            continuation = (
                "Continue the interrupted attempt on the SAME active story.\n"
                "Inspect existing repository work and resume where it stopped; "
                "do not restart completed work.\n\n"
            )
            if not prompt.startswith(continuation):
                prompt = continuation + prompt
            NEXT_PROMPT_FILE.write_text(prompt + "\n", encoding="utf-8")
            wait_for_claude_capacity()
            continue

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
            _create_intervention_and_block_story(
                story_path,
                story_content,
                reason,
                result_content,
            )
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

            _create_intervention_and_block_story(
                story_path,
                story_content,
                "Evaluator returned RETRY with no actionable "
                "deficiencies after normalization -- nothing concrete "
                "to retry.",
                result_content,
            )

            return "NEEDS_USER"

        retry_count += 1

        if retry_count > MAX_RETRIES_PER_STORY:
            print(
                "Maximum automatic retries reached."
            )

            _create_intervention_and_block_story(
                story_path,
                story_content,
                "Automatic retries exhausted "
                f"({retry_count - 1}/{MAX_RETRIES_PER_STORY}) without "
                "the evaluator confirming completion.",
                result_content,
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
# User-intervention polling (evaluator NEEDS_USER handling)
#
# Mirrors get_unresolved_user_decisions()/wait_for_user_decisions()
# above exactly, pointed at agent/user-interventions/ instead --
# purely local/deterministic, never a model call.
# ============================================================

def get_unresolved_user_interventions(
        intervention_ids: list[str]
) -> list[dict]:
    interventions_by_id = {
        item["id"]: item
        for item in list_interventions()
        if item["id"]
    }

    unresolved = []

    for intervention_id in intervention_ids:
        item = interventions_by_id.get(intervention_id)

        if item is None or item["status"] != "RESOLVED":
            unresolved.append(
                item
                or {
                    "id": intervention_id,
                    "file": None,
                    "status": None,
                }
            )

    return unresolved


def _describe_unresolved_intervention(
        item: dict
) -> str:
    intervention_id = item.get("id") or "?"

    if item.get("file") is None:
        return (
            f"{intervention_id} (no matching file under "
            "agent/user-interventions/)"
        )

    if item.get("status") is None:
        return (
            f"{intervention_id} (no readable '## Status' in "
            f"{item['file']})"
        )

    return f"{intervention_id} ({item['status']})"


def wait_for_user_interventions(
        intervention_ids: list[str]
) -> None:
    """
    Block until every listed intervention ID is RESOLVED. Checks once
    immediately, then prints which IDs are blocking and sleeps for
    USER_DECISION_POLL_SECONDS before each recheck -- a plain local
    file read, never a model call. Interruptible with Ctrl+C.
    """

    unresolved = get_unresolved_user_interventions(
        intervention_ids
    )

    if not unresolved:
        print(
            "\nUser intervention resolved: " + ", ".join(intervention_ids)
        )
        return

    print(
        "Waiting for user intervention resolution."
    )

    first_check = True

    while unresolved:
        if not first_check:
            print(
                "Still unresolved: "
                + ", ".join(
                    _describe_unresolved_intervention(item)
                    for item in unresolved
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

        unresolved = get_unresolved_user_interventions(
            intervention_ids
        )

    print(
        "\nUser intervention resolved: " + ", ".join(intervention_ids)
    )


def requeue_resolved_interventions() -> None:
    """
    For every RESOLVED user-intervention, re-derive whether the story
    it names is now genuinely executable -- its own file's Status and
    Dependencies, never assumed just because the intervention
    resolved -- and only then flip it back to TODO and move its
    BACKLOG bullet from '## Blocked' back to '## To Do'. A story left
    blocked by something else (another still-open intervention, an
    unsatisfied dependency) is left exactly as-is; this never asks the
    planner to interpret the resolution.
    """

    for item in list_interventions():
        if item["status"] != "RESOLVED" or not item["story_id"]:
            continue

        story_index = build_story_index()
        story_path = find_story_file_by_id(
            item["story_id"], story_index
        )

        if story_path is None:
            continue

        content = read_file(
            story_path
        )

        if classify_story_status(
                extract_status_section(content)
        ) != "BLOCKED":
            # Already requeued (e.g. a second RESOLVED intervention
            # for the same story on a later pass), or never actually
            # blocked by this mechanism -- nothing to do.
            continue

        if get_open_interventions_for_story(item["story_id"]):
            continue

        unsatisfied = get_unsatisfied_dependencies(
            content, story_index
        )

        if unsatisfied:
            set_story_blocked(
                story_path,
                "Blocked on unresolved dependencies: "
                + "; ".join(unsatisfied),
            )
            continue

        clear_story_blocked(
            story_path
        )

        backlog_content = read_file(
            BACKLOG_FILE
        )

        updated_backlog = move_backlog_entry_to_todo(
            backlog_content,
            story_path.name,
        )

        if updated_backlog != backlog_content:
            BACKLOG_FILE.write_text(
                updated_backlog,
                encoding="utf-8"
            )

        print(
            f"User intervention resolved: {item['file']} -> "
            f"{story_path.name} requeued to BACKLOG '## To Do'."
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
            # The active story's own file already said BLOCKED before
            # Claude Code was ever called (e.g. a dependency it
            # discovered itself, or a human edit) -- BACKLOG.md has
            # already been kept in sync above. Never retry Claude for
            # it; fall through to the same post-story bookkeeping
            # COMPLETE/NEEDS_USER use below so deterministic selection
            # can pick up other independent work instead of stopping
            # the whole orchestrator.
            print(
                "\nActive story is blocked -- "
                "continuing with other independent work."
            )
        elif result == "NEEDS_USER":
            # The affected story has already been moved to BACKLOG
            # '## Blocked' with a user-intervention record created
            # (see _create_intervention_and_block_story()) -- fall
            # through to the same post-story bookkeeping COMPLETE
            # uses below. NEEDS_USER blocks only that one story, never
            # the whole orchestrator.
            print(
                "\nStory blocked on user intervention -- "
                "continuing with other independent work."
            )
        elif result != "COMPLETE":
            print(
                f"\nUnexpected story result: {result}"
            )
            break
        else:
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

        if _select_and_activate_next_story() == "FINISHED":
            break


# ============================================================
# Deterministic story selection + activation
#
# Isolated from main()'s outer loop so the "wait locally on an open
# user intervention, then retry selection" cycle can repeat as many
# times as needed without ever re-entering execute_active_story()
# while agent/CURRENT_STORY.md is stale -- every path out of this
# function has either activated a fresh story (ACTIVATED) or decided
# there is truly nothing left to wait for (FINISHED).
# ============================================================

def _select_and_activate_next_story() -> str:
    while True:
        # Always attempted first, unconditionally: a human may have
        # already resolved an intervention before this function was
        # even called (e.g. between orchestrator runs), in which case
        # nothing here would ever be "waited on" below -- without this,
        # that story would stay BLOCKED forever despite its
        # intervention already being RESOLVED. Cheap and deterministic
        # (local file reads only), safe to call every iteration.
        requeue_resolved_interventions()

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

        if decision == "NEXT":
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
            # reject anything else, including invented paths or
            # stories that are Active, Blocked, Done, or not listed
            # at all.
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

            return "ACTIVATED"

        # decision is NO_WORK / NEEDS_USER / BLOCKED. Only retry
        # locally (no model calls) when the sole obstacle is one or
        # more OPEN user-intervention files -- a genuine, externally
        # resolvable state. An unresolved product/domain user decision
        # (the selector's own separate NEEDS_USER trigger) is left to
        # stop the orchestrator exactly as before; interventions are
        # the only new thing this waits on.
        open_intervention_ids = [
            item["id"]
            for item in list_interventions()
            if item["status"] == "OPEN" and item["id"]
        ]

        if not open_intervention_ids:
            if decision == "NO_WORK":
                print(
                    "No more READY stories. "
                    "Orchestrator finished."
                )
            elif decision == "NEEDS_USER":
                print(
                    "Story selection requires human input."
                )
            elif decision == "BLOCKED":
                print(
                    "Every remaining 'To Do' story is blocked "
                    "by its own canonical file."
                )
            else:
                raise RuntimeError(
                    f"Unknown selector decision: {decision}"
                )

            return "FINISHED"

        print(
            "No independent executable stories remain; "
            f"{len(open_intervention_ids)} user intervention(s) "
            "still open."
        )

        wait_for_user_interventions(
            open_intervention_ids
        )

        # Loop back to the top (which calls requeue_resolved_interventions()
        # again before retrying selection) -- never call
        # Claude/Hermes/Ollama/the planner merely because an
        # implementation-time intervention resolved; the planner is
        # not needed to interpret a tool/permission/manual-step
        # resolution.


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print(
            "\nInterrupted by user. Exiting."
        )
        raise SystemExit(130)
