from pathlib import Path
import time
import traceback

from agent.runtime.runners.claude_runner import (
    get_claude_session_usage_percent,
    run_claude_attempt,
    wait_for_claude_capacity,
)
from agent.runtime.support.config import (
    BACKLOG_FILE,
    CAPACITY_STATUS_INTERVAL_SECONDS,
    CAPACITY_WAIT_POLL_SECONDS,
    CLAUDE_RESULT_FILE,
    CLAUDE_USAGE_LIMIT_PERCENT,
    CURRENT_STORY_FILE,
    CYCLE_RETRY_SECONDS,
    EVALUATION_ATTEMPTS,
    EVALUATION_RETRY_SECONDS,
    PROJECT_STATE_FILE,
    USER_DECISIONS_DIR,
    PRODUCT_OWNER_REQUESTS_DIR,
    ROADMAP_FILE,
    TARGET_ARCHITECTURE_FILE,
    MAX_CLAUDE_FAILED_RUNS_PER_STORY,
    MAX_CONSECUTIVE_CYCLE_ERRORS,
    MAX_RETRIES_PER_STORY,
    MODEL,
    NEXT_PROMPT_FILE,
    REPO_ROOT,
    USER_DECISION_POLL_SECONDS,
)
from agent.runtime.evaluation.dispatcher import build_claude_prompt, dispatch_story
from agent.runtime.evaluation.evaluator import evaluate_story
from agent.runtime.runners.codex_capacity import codex_available
from agent.runtime.support.capacity import CapacityProbe, ModelCapacityUnavailable
from agent.runtime.support.daily_log import (
    log_line,
    print_status,
    start_console_logging,
)
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
from agent.runtime.support.repo_map import (
    format_repo_map_for_prompt,
    generate_repo_map,
)


# ============================================================
# Claude usage measurement (best-effort, never blocks a Claude run)
# ============================================================

def _safe_claude_usage_percent() -> int | None:
    try:
        return get_claude_session_usage_percent()
    except Exception:  # noqa: BLE001 -- measurement must never block
        return None


# ============================================================
# Terminal-only waiting status
#
# A wait can last hours. Its heartbeat belongs on the terminal and
# nowhere else (see support/daily_log.py's print_status): agent/logs/
# records transitions -- exhaustion first detected, capacity back,
# orchestration resumed -- not the fact that the orchestrator is still
# waiting.
# ============================================================

class StatusHeartbeat:
    def __init__(
            self,
            interval: int = CAPACITY_STATUS_INTERVAL_SECONDS,
            clock=time.monotonic,
    ):
        self.interval = interval
        self.clock = clock
        self.next_at = 0.0

    def say(self, *lines: str) -> bool:
        now = self.clock()

        if now < self.next_at:
            return False

        self.next_at = now + self.interval

        for line in lines:
            print_status(line)

        return True


def _seconds_until_recheck(probe) -> int | None:
    """
    How long a CapacityProbe's local cooldown still has to run, or None
    when that cannot be read (e.g. a test double). Never probes the
    model itself.
    """

    retry_at = getattr(probe, "retry_at", None)
    clock = getattr(probe, "clock", None)

    if not isinstance(retry_at, (int, float)) or not callable(clock):
        return None

    try:
        remaining = retry_at - clock()
    except TypeError:
        return None

    return max(0, int(remaining))


# ============================================================
# Evaluation with local retries
#
# Hermes/Ollama is local infrastructure, not a model with a usage
# budget. If it is briefly unreachable after Claude has already
# finished, the finished implementation must not be thrown away and
# Claude must not be re-invoked to "try again" -- retry the evaluation
# itself instead.
# ============================================================

def _evaluate_with_local_retries(
        story_content: str,
        result_content: str,
        claude_exit_code: int,
        result_was_updated: bool,
) -> dict:

    last_error = None

    for attempt in range(1, EVALUATION_ATTEMPTS + 1):
        try:
            return evaluate_story(
                story_content,
                result_content,
                claude_exit_code,
                result_was_updated,
            )
        except (OSError, RuntimeError, ValueError, KeyError) as exc:
            last_error = exc

            print_status(
                f"Evaluator unavailable ({type(exc).__name__}: {exc}); "
                f"local retry {attempt}/{EVALUATION_ATTEMPTS} "
                f"in {EVALUATION_RETRY_SECONDS}s "
                "(Claude is not re-invoked)."
            )

            if attempt < EVALUATION_ATTEMPTS:
                time.sleep(
                    EVALUATION_RETRY_SECONDS
                )

    log_line(
        f"Evaluation failed after {EVALUATION_ATTEMPTS} local attempts "
        f"({type(last_error).__name__}: {last_error}). The completed "
        "Claude work is preserved; the cycle will be retried."
    )

    raise last_error


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

def execute_active_story(before_attempt=None, on_interruption=None) -> str:

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

    # Confirm Claude actually has capacity BEFORE doing any
    # RepoMap/prompt work -- never generate the map speculatively for
    # an attempt that might not run yet. before_attempt (the
    # scheduler's wait_for_claude) blocks here -- using idle time for
    # Codex planning -- only when Claude is not currently available;
    # it returns immediately (a no-op) when it already is, so calling
    # it here in addition to its normal per-attempt call inside the
    # retry loop below is safe and cheap.
    if before_attempt is not None:
        before_attempt()

    # RepoMap is an optional, experimental orientation aid for
    # Claude's implementation prompt only -- never for the planner,
    # the Hermes dispatcher/evaluator, or the deterministic selector.
    # generate_repo_map() never raises and returns an empty map on any
    # failure (Aider missing, non-zero exit, timeout), so this can
    # never block story execution.
    repo_map = generate_repo_map()

    print(
        "\nRepoMap: "
        + ("enabled" if repo_map["enabled"] else "disabled")
        + f", budget={repo_map['token_budget']} tokens"
        + (
            f", available={repo_map['available']}"
            if repo_map["enabled"]
            else ""
        )
        + (
            f", generated={repo_map['char_count']} chars "
            f"(~{repo_map['approx_tokens']} tokens) in "
            f"{repo_map['duration_seconds']:.2f}s"
            if repo_map["text"]
            else ""
        )
        + (f", error={repo_map['error']}" if repo_map["error"] else "")
    )

    prompt = build_claude_prompt(
        plan,
        story_path,
        repo_map_context=format_repo_map_for_prompt(repo_map),
    )

    NEXT_PROMPT_FILE.write_text(
        prompt + "\n",
        encoding="utf-8"
    )

    retry_count = 0
    failed_runs = 0

    while True:

        if before_attempt is not None:
            before_attempt()

        old_result_hash = file_hash(
            CLAUDE_RESULT_FILE
        )

        usage_before = _safe_claude_usage_percent()
        claude_start = time.time()

        attempt = run_claude_attempt(
            prompt
        )

        claude_exit_code = attempt.exit_code

        claude_duration = time.time() - claude_start
        usage_after = _safe_claude_usage_percent()

        print(
            "Claude run measurement: "
            f"repo_map={'on' if repo_map['enabled'] else 'off'}, "
            f"duration={claude_duration:.1f}s, "
            f"usage_before={usage_before}, "
            f"usage_after={usage_after}"
        )

        if claude_exit_code != 0:
            # An interrupted attempt is not an evaluator retry or a
            # story outcome. Keep the same story and work order.
            capacity_limited = attempt.capacity_exhausted or (
                usage_after is not None
                and usage_after >= CLAUDE_USAGE_LIMIT_PERCENT
            )

            set_story_unfinished(story_path)
            print(
                f"Claude exited with code {claude_exit_code} "
                + (
                    "after running out of capacity. Story remains active "
                    "and UNFINISHED; waiting locally for capacity before "
                    "continuing the same story."
                    if capacity_limited
                    else "with no capacity signal. Story remains active "
                    "and UNFINISHED; retrying the same story."
                )
            )
            continuation = (
                "Continue the interrupted attempt on the SAME active story.\n"
                "Inspect existing repository work and resume where it stopped; "
                "do not restart completed work.\n\n"
            )
            if not prompt.startswith(continuation):
                prompt = continuation + prompt
            NEXT_PROMPT_FILE.write_text(prompt + "\n", encoding="utf-8")

            if capacity_limited:
                # Capacity exhaustion is a scheduling event: it never
                # counts against the story's retry budget and never
                # escalates to a human.
                failed_runs = 0
                log_line(
                    f"Claude capacity exhausted during {story_path.name}; "
                    "story preserved as UNFINISHED, waiting locally."
                )

                if on_interruption is not None:
                    on_interruption()
                else:
                    wait_for_claude_capacity()

                continue

            # A non-zero exit with no capacity signal is a failed run,
            # not a pause. Waiting an hour and re-invoking Claude
            # forever would never resolve it, so escalate once it
            # repeats instead of looping.
            failed_runs += 1

            log_line(
                f"Claude run on {story_path.name} exited "
                f"{claude_exit_code} with no capacity signal "
                f"({failed_runs}/{MAX_CLAUDE_FAILED_RUNS_PER_STORY})."
            )

            if failed_runs >= MAX_CLAUDE_FAILED_RUNS_PER_STORY:
                _create_intervention_and_block_story(
                    story_path,
                    read_file(story_path),
                    f"Claude Code exited with code {claude_exit_code} on "
                    f"{failed_runs} consecutive runs without any capacity "
                    "signal, so this is a failing invocation rather than a "
                    "usage pause. The orchestrator continues with other "
                    "work; this story needs a human to inspect the run.",
                    read_file(CLAUDE_RESULT_FILE)
                    if CLAUDE_RESULT_FILE.exists()
                    else "(CLAUDE_RESULT.md does not exist -- the Claude "
                         "process exited non-zero without writing a "
                         "result.)",
                )

                return "NEEDS_USER"

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

        failed_runs = 0

        evaluation = _evaluate_with_local_retries(
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


def _unresolved_ids(
        unresolved: list[dict]
) -> set[str]:
    return {
        item.get("id") or "?"
        for item in unresolved
    }


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
    Wait locally until the set of blocking decisions actually changes --
    i.e. as soon as ANY of them becomes RESOLVED, not only once every
    one has. Resolving one decision can be enough to unblock planning or
    a dependent story, and the caller re-derives what is permissible
    from scratch afterwards, so returning early is always safe and
    never skips a gate that is still open.

    Checks once immediately (the user may have resolved it before this
    state was entered), then re-reads agent/user-decisions/*.md every
    USER_DECISION_POLL_SECONDS -- a plain local file read, never a model
    call. Heartbeat output is terminal-only; only entering and leaving
    the wait are logged. Remains interruptible with Ctrl+C:
    KeyboardInterrupt is never caught here and propagates normally.
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

    blocking = _unresolved_ids(unresolved)

    log_line(
        "Waiting locally for user decision(s): "
        + ", ".join(sorted(blocking))
    )

    heartbeat = StatusHeartbeat()

    while unresolved and _unresolved_ids(unresolved) == blocking:
        heartbeat.say(
            "Waiting for user decision resolution - waiting...",
            "Still unresolved: "
            + ", ".join(
                _describe_unresolved_decision(decision)
                for decision in unresolved
            ),
            "Next local check in "
            f"{USER_DECISION_POLL_SECONDS // 60} minutes.",
        )

        time.sleep(
            USER_DECISION_POLL_SECONDS
        )

        unresolved = get_unresolved_user_decisions(
            decision_ids
        )

    resolved = sorted(
        blocking - _unresolved_ids(unresolved)
    )

    log_line(
        "User decision(s) resolved: " + ", ".join(resolved)
        + "; resuming orchestration."
    )

    print(
        "\nUser decision resolved: " + ", ".join(resolved)
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
    Mirror of wait_for_user_decisions() for agent/user-interventions/:
    returns as soon as ANY listed intervention becomes RESOLVED, since
    one resolution can be enough to requeue a story and the caller
    re-derives eligibility from each story's own file afterwards.
    Heartbeat output is terminal-only; local file reads only, never a
    model call. Interruptible with Ctrl+C.
    """

    unresolved = get_unresolved_user_interventions(
        intervention_ids
    )

    if not unresolved:
        print(
            "\nUser intervention resolved: " + ", ".join(intervention_ids)
        )
        return

    blocking = _unresolved_ids(unresolved)

    log_line(
        "Waiting locally for user intervention(s): "
        + ", ".join(sorted(blocking))
    )

    heartbeat = StatusHeartbeat()

    while unresolved and _unresolved_ids(unresolved) == blocking:
        heartbeat.say(
            "Waiting for user intervention resolution - waiting...",
            "Still unresolved: "
            + ", ".join(
                _describe_unresolved_intervention(item)
                for item in unresolved
            ),
            "Next local check in "
            f"{USER_DECISION_POLL_SECONDS // 60} minutes.",
        )

        time.sleep(
            USER_DECISION_POLL_SECONDS
        )

        unresolved = get_unresolved_user_interventions(
            intervention_ids
        )

    resolved = sorted(
        blocking - _unresolved_ids(unresolved)
    )

    log_line(
        "User intervention(s) resolved: " + ", ".join(resolved)
        + "; resuming orchestration."
    )

    print(
        "\nUser intervention resolved: " + ", ".join(resolved)
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

def planning_fingerprint():
    # No-work/NEEDS_USER suppression lasts only while relevant local inputs stay unchanged.
    paths = {BACKLOG_FILE, CURRENT_STORY_FILE, PROJECT_STATE_FILE, ROADMAP_FILE, TARGET_ARCHITECTURE_FILE}
    for directory in (BACKLOG_FILE.parent, USER_DECISIONS_DIR, PRODUCT_OWNER_REQUESTS_DIR):
        paths.update(directory.glob("*.md"))
    return tuple((str(path), file_hash(path)) for path in sorted(paths))


class CapacityScheduler:
    """
    Independent per-model capacity gating. Neither model's exhaustion is
    ever a story or planning failure, and neither blocks the other:
    Claude drains already-planned work while Codex is exhausted, Codex
    replenishes the queue while Claude is exhausted, and when both are
    out the orchestrator waits locally instead of exiting.
    """

    def __init__(self, claude=None, codex=None):
        self.claude = claude or CapacityProbe(self._read_claude_capacity)
        self.codex = codex or CapacityProbe(codex_available)
        self.no_work_at = None
        self.claude_usage_percent = None
        self.planning_failure = None
        # Remembered purely so a transition is logged once, instead of
        # the same "unavailable" line every polling cycle.
        self.exhausted = {"Claude": False, "Codex": False}
        self.heartbeat = StatusHeartbeat()

    def _read_claude_capacity(self):
        # `claude -p /usage` runs a slash command, so this costs no
        # tokens and never invokes the exhausted model. The reading is
        # kept for the terminal status line.
        self.claude_usage_percent = get_claude_session_usage_percent()

        return self.claude_usage_percent < CLAUDE_USAGE_LIMIT_PERCENT

    def _availability(self, name, probe):
        available = probe.available()

        if available and self.exhausted[name]:
            log_line(f"{name} capacity available again.")
            self.exhausted[name] = False
        elif not available and not self.exhausted[name]:
            log_line(f"{name} capacity exhausted (first detected).")
            self.exhausted[name] = True

        return available

    def claude_available(self):
        return self._availability("Claude", self.claude)

    def codex_available(self):
        return self._availability("Codex", self.codex)

    def plan_if_useful(self, idle=False):
        if not idle and not should_trigger_planning(len(get_selectable_story_candidates())):
            return False
        fingerprint = planning_fingerprint()
        if fingerprint == self.no_work_at or not self.codex_available():
            return False
        try:
            result = run_planning_pass()
        except ModelCapacityUnavailable:
            self.codex.defer()
            self.exhausted["Codex"] = True
            log_line("Codex capacity exhausted during planning; "
                     "planning will resume after the local cooldown.")
            return False
        except (RuntimeError, ValueError, OSError) as exc:
            # A planning pass that fails or is rolled back must never
            # take the orchestrator down with it -- Claude may still
            # have planned work to drain. Back the planner off for one
            # cooldown instead, so it is retried later rather than
            # hammered now, and never mark the state as "no work".
            return self._planning_failed(f"{type(exc).__name__}: {exc}")
        status = result.get("status")
        if status not in ("COMPLETE", "NEEDS_USER"):
            return self._planning_failed(
                f"planner reported {status}: {result.get('reason', 'unknown')}")
        self.planning_failure = None
        changed = planning_fingerprint() != fingerprint
        useful = status == "COMPLETE" and changed and bool(
            result.get("story_files_created") or result.get("milestone_transition"))
        if not useful:
            self.no_work_at = planning_fingerprint()
        return useful

    def _planning_failed(self, description):
        self.planning_failure = description
        self.codex.defer()
        log_line(f"Project planning failed ({description}). Execution of "
                 "already-planned work continues; planning is retried after "
                 "the local cooldown.")
        return False

    def status_lines(self, unavailable):
        lines = [
            " and ".join(unavailable)
            + " capacity unavailable - waiting..."
        ]

        if "Claude" in unavailable:
            lines.append(
                f"Current usage: Claude session {self.claude_usage_percent}% "
                f"(resumes below {CLAUDE_USAGE_LIMIT_PERCENT}%)"
                if self.claude_usage_percent is not None
                else "Current usage: Claude session usage unreadable "
                     "(last local probe failed)"
            )

        for name, probe in (("Claude", self.claude), ("Codex", self.codex)):
            if name not in unavailable:
                continue

            seconds = _seconds_until_recheck(probe)

            if seconds is not None:
                lines.append(
                    f"Next {name} capacity check in "
                    f"{max(1, seconds // 60)} min"
                )

        if self.planning_failure and "Codex" in unavailable:
            lines.append(
                f"Last planning attempt failed: {self.planning_failure}"
            )

        return lines

    def wait_locally(self, unavailable):
        """Stay alive without invoking any exhausted model."""

        self.heartbeat.say(*self.status_lines(unavailable))

        time.sleep(CAPACITY_WAIT_POLL_SECONDS)

    def wait_for_claude(self):
        # Called between attempts, so an interruption retains the exact retry prompt/budget.
        waited = False
        while not self.claude_available():
            waited = True
            if self.plan_if_useful(idle=True):
                continue
            self.wait_locally(["Claude"])
        if waited:
            log_line("Orchestration resumed: continuing the active story "
                     "after waiting locally for Claude capacity.")


_reported_pointer_problem = None


def _active_is_executable():
    global _reported_pointer_problem

    if not CURRENT_STORY_FILE.exists() or not read_file(CURRENT_STORY_FILE).strip():
        return False

    try:
        path = get_active_story_path()
        executable = classify_story_status(
            extract_status_section(read_file(path))
        ) not in ("DONE", "BLOCKED")
    except (FileNotFoundError, RuntimeError) as exc:
        # Stale workflow state must not strand execution: an
        # unresolvable/unreadable pointer means "no active story", so
        # deterministic selection can proceed and overwrite it. Logged
        # once per distinct problem, never once per polling cycle.
        problem = f"{type(exc).__name__}: {exc}"

        if problem != _reported_pointer_problem:
            _reported_pointer_problem = problem
            log_line(
                "agent/CURRENT_STORY.md does not resolve to a usable "
                f"story ({problem}); continuing with deterministic "
                "selection."
            )

        return False

    _reported_pointer_problem = None

    return executable


class DecisionLog:
    """
    Persistent log entries for scheduling *transitions* only.

    Every cycle still checks both models and decides what to do next,
    before acting -- but the orchestrator can legitimately repeat the
    same cycle once a minute for hours (waiting for capacity or for a
    user decision, which announces a suppressed planning attempt and a
    wait every single time). Re-logging that holding pattern would bury
    the transitions that matter, so a cycle whose decisions the previous
    cycle already recorded is silent in agent/logs/ and visible only on
    the terminal, through the wait loops' own heartbeat.
    """

    def __init__(self):
        self.previous = set()
        self.current = set()
        self.availability_logged = False

    def begin_cycle(self) -> None:
        if self.current:
            self.previous = self.current

        self.current = set()
        self.availability_logged = False

    def announce(self, availability: str, decision: str) -> None:
        entry = (availability, decision)

        self.current.add(entry)

        if entry in self.previous:
            return

        if not self.availability_logged:
            log_line(f"Availability check: {availability}")
            self.availability_logged = True

        log_line(f"Decision: {decision}")


def _run_cycle(scheduler, replenish, decisions) -> tuple[str, bool]:
    """
    One scheduling cycle. Returns ("CONTINUE"|"STOP", replenish).

    Priority order: resume the active story > execute selectable To Do
    work > replenish a low queue through Codex > use Claude's idle time
    for planning > wait locally. Every branch either performs work or
    leaves the orchestrator alive; only a genuinely empty, unblocked,
    unplannable repository reaches "STOP".
    """

    decisions.begin_cycle()

    requeue_resolved_interventions()

    # Step 1 (per cycle): check both models' availability
    # independently, before any decision is made. A cheap, cached local
    # check (CapacityProbe) -- never a fresh model call once a probe is
    # within its cooldown window.
    claude_ready = scheduler.claude_available()
    codex_ready = scheduler.codex_available()
    availability = (
        f"claude_available={claude_ready}, codex_available={codex_ready}"
    )

    # Step 2: decide the next action. Resuming an active story is always
    # priority 1 regardless of claude_ready -- if Claude is not ready
    # yet, execute_active_story()'s own wait_for_claude callback blocks
    # (using idle Codex capacity for planning meanwhile) before it ever
    # prepares a RepoMap or invokes Claude, so the decision here is
    # still "code", just not yet runnable this instant.
    if _active_is_executable():
        decisions.announce(
            availability,
            "code (resume active story) -- reason: an unfinished active "
            "story exists, which is always priority 1",
        )
        result = execute_active_story(scheduler.wait_for_claude, scheduler.claude.defer)
        if result not in ("COMPLETE", "BLOCKED", "NEEDS_USER"):
            raise RuntimeError(f"Unexpected story result: {result}")
        return "CONTINUE", True

    candidates = get_selectable_story_candidates()

    # After completing work, give a low queue one replenishment opportunity.
    # On startup existing executable work takes precedence over planning.
    if replenish or not claude_ready or not candidates:
        decisions.announce(
            availability,
            "plan (attempt) -- reason: "
            + (
                "just finished a story (replenish check)" if replenish
                else "Claude unavailable, using idle time for planning" if not claude_ready
                else "To Do queue is empty"
            ),
        )
        planned = scheduler.plan_if_useful(idle=not claude_ready)
        replenish = False
        if planned:
            # Newly planned work is picked up by the next cycle's own
            # selection -- never by restarting the orchestrator.
            return "CONTINUE", False
        candidates = get_selectable_story_candidates()
        # A planning attempt can itself discover that Codex is out of
        # capacity, or fail and put it on cooldown. Re-read that (a
        # cached local check, never a fresh probe) so a planner that
        # cannot run right now leads to local waiting and a later retry
        # instead of being mistaken for "nothing left to plan".
        codex_ready = scheduler.codex_available()
        availability = (
            f"claude_available={claude_ready}, codex_available={codex_ready}"
        )

    if candidates and claude_ready:
        decisions.announce(
            availability,
            f"code (select next story) -- reason: {len(candidates)} "
            "selectable To Do candidate(s), Claude available",
        )
        if _select_and_activate_next_story() == "ACTIVATED":
            return "CONTINUE", replenish
        # Selection found nothing executable after all (a candidate
        # changed underneath us). Fall through to waiting/stopping
        # rather than re-selecting in a tight loop.
        candidates = []

    # Never declare NO_WORK merely because one model is temporarily
    # exhausted: stay alive locally and let the other model work.
    unavailable = [
        name
        for name, ready in (("Claude", claude_ready), ("Codex", codex_ready))
        if not ready
    ]
    if unavailable:
        decisions.announce(
            availability,
            f"wait -- reason: {availability}; no work an available model "
            "can currently make progress on ("
            + ", ".join(unavailable) + " out of capacity)",
        )
        scheduler.wait_locally(unavailable)
        return "CONTINUE", replenish

    unresolved = [item["id"] for item in list_decisions()
                  if item["status"] != "RESOLVED" and item["id"]]
    if unresolved:
        decisions.announce(
            availability,
            "wait (user decision) -- reason: unresolved user "
            f"decision(s) block remaining work: {unresolved}",
        )
        wait_for_user_decisions(unresolved)
        return "CONTINUE", replenish

    decisions.announce(
        availability,
        "stop -- reason: no active story, no selectable work, no useful "
        "planning, no unresolved user decision",
    )
    if _select_and_activate_next_story() == "FINISHED":
        return "STOP", replenish
    return "CONTINUE", replenish


def main() -> None:
    print(f"GW2 AI Orchestrator started. Repository: {REPO_ROOT}")
    scheduler = CapacityScheduler()
    decisions = DecisionLog()
    replenish = False
    failures = 0

    while True:
        try:
            outcome, replenish = _run_cycle(scheduler, replenish, decisions)
            failures = 0
        except KeyboardInterrupt:
            raise
        except Exception as exc:  # noqa: BLE001
            # An unattended run must survive one recoverable failure
            # (a local evaluator outage, a transient file/OS error)
            # rather than dying with work left in the queue. A failure
            # that keeps repeating is not recoverable and needs a human,
            # so it stops the orchestrator explicitly instead of
            # looping forever.
            failures += 1
            log_line(
                f"Orchestration cycle failed ({type(exc).__name__}: {exc}) "
                f"[{failures}/{MAX_CONSECUTIVE_CYCLE_ERRORS}]"
            )
            traceback.print_exc()

            if failures >= MAX_CONSECUTIVE_CYCLE_ERRORS:
                log_line(
                    "Decision: stop -- reason: the same orchestration "
                    "cycle keeps failing; human action required"
                )
                print(
                    "\nOrchestration failed "
                    f"{failures} times in a row. Stopping -- see the "
                    "traceback above and agent/logs/."
                )
                return

            print_status(
                "Retrying the orchestration cycle in "
                f"{CYCLE_RETRY_SECONDS}s..."
            )
            time.sleep(CYCLE_RETRY_SECONDS)
            continue

        if outcome == "STOP":
            return


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
    start_console_logging()

    try:
        main()
    except KeyboardInterrupt:
        print(
            "\nInterrupted by user. Exiting."
        )
        raise SystemExit(130)
