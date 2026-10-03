from pathlib import Path
import time
import json
import hashlib
import traceback

from agent.runtime.runners.claude_runner import (
    get_claude_usage,
    run_claude_attempt,
    wait_for_claude_capacity,
)
from agent.runtime.support.config import (
    ARCHITECT_REQUESTS_DIR,
    ATTEMPT_STATE_FILE,
    BACKLOG_FILE,
    CAPACITY_STATUS_INTERVAL_SECONDS,
    CAPACITY_WAIT_POLL_SECONDS,
    CLAUDE_RESULT_FILE,
    CLAUDE_USAGE_LIMIT_PERCENT,
    CLAUDE_WEEKLY_MIN_REMAINING_PERCENT,
    CURRENT_STORY_FILE,
    CYCLE_RETRY_SECONDS,
    MAX_CI_FIX_ATTEMPTS,
    EVALUATION_ATTEMPTS,
    EVALUATION_RETRY_SECONDS,
    MAX_EVALUATION_BATCHES,
    PROJECT_STATE_FILE,
    USER_DECISIONS_DIR,
    PRODUCT_OWNER_REQUESTS_DIR,
    ROADMAP_FILE,
    TARGET_ARCHITECTURE_FILE,
    MAX_CLAUDE_FAILED_RUNS_PER_STORY,
    MAX_CONSECUTIVE_CYCLE_ERRORS,
    MAX_RETRIES_PER_STORY,
    NEXT_PROMPT_FILE,
    REPO_ROOT,
    USER_DECISION_POLL_SECONDS,
)
from agent.runtime.evaluation.claude_prompt import build_claude_prompt
from agent.runtime.evaluation.evaluator import evaluate_story
from agent.runtime.runners.codex_capacity import read_codex_capacity, format_codex_capacity
from agent.runtime.support.capacity import CapacityProbe, ModelCapacityUnavailable
from agent.runtime.support.daily_log import (
    log_line,
    print_status,
    start_console_logging,
)
from agent.runtime.support.files import file_hash, read_file
from agent.runtime.support import git_sync, github_ci
from agent.runtime.core.architect import run_architect_pass
from agent.runtime.core.project_planner import run_planning_pass, should_trigger_planning
from agent.runtime.core.selector import select_next_story
from agent.runtime.core.story_state import (
    build_story_index,
    classify_story_status,
    clear_active_story,
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
    move_backlog_entry_to_done,
    move_backlog_entry_to_todo,
    resolve_story_path,
    set_active_story,
    set_story_blocked,
    set_story_done,
    set_story_unfinished,
    strip_backlog_prose,
    validate_backlog_consistency,
)
from agent.runtime.human.architect_requests import (
    get_actionable_requests as get_actionable_architect_requests,
    undispatchable_requests as undispatchable_architect_requests,
)
from agent.runtime.human.user_decisions import extract_section, list_decisions
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

def _safe_claude_usage():
    try:
        return get_claude_usage()
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
# Durable attempt state -- the harness's own pipeline position
#
# A story's "## Status" is written by Claude. It states what Claude
# believes about the implementation, and it can never state whether the
# HARNESS has finished its own remaining steps: evaluation, the commit,
# the push, and the CI verdict. Those are three more gates after Claude
# stops, and the story file says nothing about them.
#
# Reading "Status: DONE" as "story complete" therefore silently equated
# "Claude thinks it is finished" with "the work is accepted and
# published". An interruption anywhere between the two -- an evaluator
# outage, a Ctrl+C, a crash -- was indistinguishable from a completed
# story: the next run skipped it (_active_is_executable() classified DONE
# as not executable), selected a fresh story, and the finished work was
# left unevaluated, uncommitted and unpushed, to be swept into some later
# story's commit.
#
# So the position is recorded explicitly, beside the other runtime
# artifacts (generated, local, gitignored -- never a source of truth
# about the story itself, only about which of the harness's own steps
# still owes an answer):
#
#   AWAITING_EVALUATION  Claude's attempt finished; no evaluator verdict.
#   AWAITING_CI          the evaluator accepted it; publication and the
#                        CI verdict are still outstanding.
#   FINALIZING           every gate has passed; the story's own state
#                        transition (Status, BACKLOG section, pointer)
#                        is the only thing left to do.
#
# The file exists only between those points. Its absence means the
# harness is not mid-attempt, and every terminal outcome clears it.
#
# FINALIZING exists because the transition is several writes to several
# files and an interrupt can land between any two of them. Without it, a
# restart had to re-derive "did this already pass?" from the repository,
# and the only honest answers were "re-run the gate" or "assume it
# passed" -- one wastes a verdict on a published commit, the other
# bypasses an unfinished gate. The record carries the verdict it already
# has (`ci_status`, `ci_sha`), so a resumed run finishes the bookkeeping
# and nothing else: no Claude invocation, no second evaluator call, no
# second CI wait.
# ============================================================

ATTEMPT_PHASES = ("AWAITING_EVALUATION", "AWAITING_CI", "FINALIZING")


def read_attempt_state() -> dict | None:
    """The recorded pipeline position, or None when there is none.

    Anything unreadable, unversioned or malformed is treated as "no
    recorded attempt" rather than guessed at -- a resume must never be
    driven by a state this code cannot fully understand.
    """

    try:
        state = json.loads(
            ATTEMPT_STATE_FILE.read_text(encoding="utf-8")
        )
    except (OSError, ValueError):
        return None

    if not isinstance(state, dict) or state.get("version") != 1:
        return None

    if state.get("phase") not in ATTEMPT_PHASES or not state.get("story"):
        return None

    return state


def record_attempt_state(
        story_path: Path,
        phase: str,
        claude_exit_code: int = 0,
        result_was_updated: bool = False,
        retry_count: int = 0,
        ci_fix_attempts: int = 0,
        ci_status: str = "",
        ci_sha: str = "",
) -> None:
    """Record which step still owes an answer, atomically.

    The retry budgets travel with it: they used to live only in
    execute_active_story()'s local variables, so a restart handed every
    story a fresh MAX_RETRIES_PER_STORY and MAX_CI_FIX_ATTEMPTS and a
    crash-looping story could never reach its escalation.
    """

    state = {
        "version": 1,
        "story": story_path.relative_to(REPO_ROOT).as_posix(),
        "phase": phase,
        "claude_exit_code": claude_exit_code,
        "result_was_updated": result_was_updated,
        "retry_count": retry_count,
        "ci_fix_attempts": ci_fix_attempts,
        "ci_status": ci_status,
        "ci_sha": ci_sha,
    }

    ATTEMPT_STATE_FILE.parent.mkdir(parents=True, exist_ok=True)

    temporary = ATTEMPT_STATE_FILE.with_suffix(".tmp")
    temporary.write_text(
        json.dumps(state, indent=2),
        encoding="utf-8",
    )
    temporary.replace(ATTEMPT_STATE_FILE)


def clear_attempt_state() -> None:
    ATTEMPT_STATE_FILE.unlink(missing_ok=True)


def pending_attempt_for(story_path: Path) -> dict | None:
    """The recorded attempt, but only if it belongs to `story_path`.

    A record naming a story that no longer resolves is stale workflow
    state and is discarded here rather than left to strand execution.
    """

    state = read_attempt_state()

    if state is None:
        return None

    try:
        recorded = resolve_story_path(state["story"])
    except (FileNotFoundError, RuntimeError):
        clear_attempt_state()
        return None

    return state if recorded == story_path else None


# ============================================================
# Second, independent check: did git ever publish this completion?
#
# ATTEMPT_STATE.json is the authoritative resume signal, but it lives in
# agent/runtime/artifacts/ -- generated, gitignored, and wiped along with
# everything else there. A DONE story whose record is missing must not
# fall straight back into the old silent-skip, so the repository itself is
# asked a second question it cannot be lied to about: is there a commit
# for this story, and is there still unpublished work?
#
# Both halves are required. "No commit for this story" alone would also
# be true after a legitimately completed story whose gate was SKIPPED, and
# "the tree is dirty" alone is true within seconds of every commit,
# because agent/logs/<date>.log is a tracked file the orchestrator appends
# to continuously. Requiring both keeps the check from ever re-running the
# CI gate on a story that has nothing left to publish.
# ============================================================

def _publishable_changes() -> list[str]:
    """Working-tree changes that are real work rather than log churn."""

    return [
        line for line in git_sync.working_tree_changes()
        if not line[3:].strip().strip('"').startswith("agent/logs/")
    ]


def unpublished_completion(
        story_path: Path,
        story_content: str,
) -> str | None:
    """Why a DONE story's completion cannot have been published, or None.

    None means "nothing here contradicts completion" -- including every
    case git cannot answer. This never reports a doubt it cannot
    substantiate, because the caller resumes the story on it.
    """

    available, _detail = git_sync.ci_verification_available()

    if not available:
        # With no CI gate a story completes on the evaluator's verdict
        # alone and nothing is ever committed, so neither half of this
        # check means anything.
        return None

    subject = "implemented " + (
        extract_story_id(story_content) or story_path.stem
    )

    try:
        published = git_sync.commit_exists_with_subject(subject)

        if published is not False:
            return None

        outstanding = _publishable_changes()
        branch = git_sync.current_branch()
        ahead = git_sync.unpushed_commit_count(branch) if branch else None
    except git_sync.GitCommandError:
        return None

    if outstanding:
        return (
            f"no commit named {subject!r} exists and {len(outstanding)} "
            "changed path(s) are still uncommitted"
        )

    if ahead:
        return (
            f"no commit named {subject!r} exists and {ahead} commit(s) on "
            "this branch are not on origin"
        )

    return None


# ============================================================
# Evaluation with retries
#
# A failed evaluation must never throw away a finished Claude run, and
# Claude must never be re-invoked to "try again" at something that was
# not its failure -- retry the evaluation itself instead.
#
# Evaluation runs on Codex now, so an attempt costs real capacity. Two
# consequences: the attempt counts are small (see EVALUATION_ATTEMPTS),
# and capacity exhaustion must be excluded from them entirely.
# ModelCapacityUnavailable is a RuntimeError subclass, so it would
# otherwise be caught here and spent as a retry -- it is re-raised
# explicitly and handled as the scheduling event it is, one level up.
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
        except ModelCapacityUnavailable:
            raise
        except (OSError, RuntimeError, ValueError, KeyError) as exc:
            last_error = exc

            log_line(
                f"Evaluation attempt {attempt}/{EVALUATION_ATTEMPTS} failed: "
                f"{type(exc).__name__}: {exc}"
            )

            print_status(
                f"Evaluation failed ({type(exc).__name__}: {exc}); "
                f"retry {attempt}/{EVALUATION_ATTEMPTS} "
                f"in {EVALUATION_RETRY_SECONDS}s "
                "(Claude is not re-invoked)."
            )

            if attempt < EVALUATION_ATTEMPTS:
                time.sleep(
                    EVALUATION_RETRY_SECONDS
                )

    log_line(
        f"Evaluation failed after {EVALUATION_ATTEMPTS} attempts "
        f"({type(last_error).__name__}: {last_error}). The completed "
        "Claude work is preserved; the cycle will be retried."
    )

    raise last_error


def _evaluate_preserving_completed_attempt(
        story_content: str,
        result_content: str,
        claude_exit_code: int,
        result_was_updated: bool,
        wait_for_evaluator=None,
) -> dict:
    """Retry evaluation in bounded batches, never rerunning Claude.

    This loop used to be infinite, for a reason that no longer holds: the
    comment said returning to main() "would see the story still active and
    invoke Claude again", so an unreachable evaluator pinned the
    orchestrator here forever -- silently as far as any transition went,
    with a stocked queue and both models available, and losing its place
    entirely if the run was interrupted.

    ATTEMPT_STATE.json removes that constraint. The finished attempt is
    recorded before this is ever called, so a failure can propagate: the
    cycle fails, main()'s recoverable-failure handler retries it, and the
    retried cycle resumes at evaluation rather than at another Claude run.
    A genuinely transient failure is still ridden out locally; a permanent
    one now reaches main()'s explicit "human action required" stop instead
    of an unbounded wait.

    `wait_for_evaluator` blocks until Codex has capacity again. Exhaustion
    is not a failed evaluation and never consumes a batch: the work is
    finished and waiting is free, so the only alternative -- escalating a
    story because a quota reset is hours away -- would be wrong.
    """

    batch = 0

    while True:
        try:
            return _evaluate_with_local_retries(
                story_content, result_content, claude_exit_code,
                result_was_updated,
            )
        except ModelCapacityUnavailable as exc:
            log_line(
                f"Codex capacity exhausted during evaluation ({exc}); the "
                "finished Claude work is preserved and recorded in "
                "ATTEMPT_STATE.json. Waiting locally for Codex capacity; "
                "this does not consume an evaluation attempt."
            )

            if wait_for_evaluator is None:
                raise

            wait_for_evaluator()

            continue
        except (OSError, RuntimeError, ValueError, KeyError) as exc:
            batch += 1

            if batch >= MAX_EVALUATION_BATCHES:
                log_line(
                    "Evaluation still unavailable after "
                    f"{MAX_EVALUATION_BATCHES} batches of "
                    f"{EVALUATION_ATTEMPTS} attempts "
                    f"({type(exc).__name__}: {exc}). The finished Claude "
                    "work is preserved and its pipeline position is "
                    "recorded in ATTEMPT_STATE.json; this cycle fails and "
                    "will resume at evaluation, never at another Claude "
                    "run."
                )

                raise

            print_status(
                f"Evaluation retry batch {batch}/{MAX_EVALUATION_BATCHES} "
                "exhausted; completed Claude work is preserved. Retrying "
                f"evaluation in {CYCLE_RETRY_SECONDS}s."
            )
            time.sleep(CYCLE_RETRY_SECONDS)


# ============================================================
# Retry prompt
# ============================================================

def build_retry_prompt(
        actionable_retry_items: list[str],
        reason: str,
        story_path: Path,
        unmet_intent: list[str] | None = None,
) -> str:

    relative_story = story_path.relative_to(
        REPO_ROOT
    ).as_posix()

    items_block = "\n".join(
        f"- {item}" for item in actionable_retry_items
    )

    # An unachieved purpose is a different kind of finding from a missed
    # checklist item, and fixing it usually means changing where or how the
    # work was done rather than adding to it. It is called out separately so
    # a retry does not answer it by ticking the nearest criterion again.
    intent_block = ""

    if unmet_intent:
        intent_block = (
            "\nThe evaluator inspected the repository and found that the "
            "change does not achieve what this story set out to achieve, "
            "even where its individual criteria are satisfied:\n"
            + "\n".join(f"- {item}" for item in unmet_intent)
            + "\n\nTreat this as the primary deficiency. Fix the substance "
              "rather than restating the criterion: make the behaviour hold "
              "where the story requires it, reachable by the caller or user "
              "the story is about, and covered by a test that would fail if "
              "the behaviour were removed. Stay inside this story's scope.\n"
        )

    return f"""Continue the active story.

Active story:
{relative_story}

The previous execution did not yet satisfy the Definition of Done.

Concrete deficiencies to address, and only these:
{items_block}
{intent_block}

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
# GitHub CI verification gate
#
# The evaluator judges the story against its own Definition of Done from
# what Claude reports. It cannot tell whether the repository still builds
# and the whole suite still passes -- and asking Claude to prove that
# locally means paying for a full regression run inside a model session
# on every attempt. So the gate moved: Claude verifies its own change
# with targeted tests, the harness commits and pushes, and
# .github/workflows/ci.yml is the authoritative regression verdict
# (docs/TEST_STRATEGY.md §36).
#
# Three outcomes, and nothing in between:
#   PASSED/SKIPPED -- the story completes as before;
#   FAILED         -- a short report goes back to Claude as more work;
#   UNVERIFIED     -- nobody can say whether it passed, so a human is
#                     asked rather than a green result assumed.
# ============================================================

COMMIT_SUBJECT_MAX_CHARS = 72


def _story_commit_message(story_path: Path, story_content: str) -> str:
    story_id = extract_story_id(story_content) or story_path.stem
    title = " ".join((extract_section(story_content, "Title") or "").split())

    subject = f"implemented {story_id}" + (f": {title}" if title else "")

    if len(subject) > COMMIT_SUBJECT_MAX_CHARS:
        subject = subject[:COMMIT_SUBJECT_MAX_CHARS].rstrip() + "..."

    return subject


def verify_with_github_ci(story_path: Path, story_content: str) -> dict:
    """Commit, push and wait for this commit's CI verdict."""

    available, detail = git_sync.ci_verification_available()

    if not available:
        log_line(
            f"CI verification skipped for {story_path.name}: {detail}. "
            "The story completes on the evaluator's verdict alone."
        )
        return {"status": "SKIPPED", "reason": detail, "report": "",
                "sha": "", "run_urls": []}

    push = git_sync.commit_and_push(
        _story_commit_message(story_path, story_content)
    )

    if push["status"] in ("UNAVAILABLE", "PUSH_REJECTED"):
        log_line(
            f"Could not publish {story_path.name} for CI verification "
            f"({push['status']}): {push['reason']}"
        )
        return {
            "status": "UNVERIFIED", "report": "", "sha": push.get("sha", ""),
            "run_urls": [],
            "reason": f"the completed work could not be pushed ({push['status']}): "
                      f"{push['reason']}",
        }

    log_line(
        f"{story_path.name} published for CI verification: {push['sha'][:7]} on "
        f"{push['branch']} ({push.get('changed_files', 0)} changed path(s), "
        + ("new commit" if push["committed"] else "no new commit needed")
        + ("," if push["pushed"] else ", already on origin,")
        + " waiting for GitHub Actions."
    )

    heartbeat = StatusHeartbeat()

    result = github_ci.wait_for_commit(
        git_sync.remote_slug(),
        push["sha"],
        sleep=time.sleep,
        monotonic=time.monotonic,
        notify=heartbeat.say,
    )

    log_line(
        f"GitHub CI {result['status']} for {push['sha'][:7]} "
        f"({story_path.name}): {result['reason']}"
    )

    return result


def build_ci_failure_prompt(
        verification: dict,
        story_path: Path,
) -> str:
    """Return a red pipeline to Claude as bounded, actionable work."""

    relative_story = story_path.relative_to(REPO_ROOT).as_posix()

    return f"""Continue the active story: its change is committed and pushed, but
the authoritative GitHub CI regression run failed for that commit.

Active story:
{relative_story}

CI failure report (the failing tests only -- successful output is deliberately
not included):

{verification["report"] or verification["reason"]}

Fix the cause of these failures, and only these.

- Reproduce each failing test locally with the narrowest command that covers
  it (a single test class or test name), never the full suite: CI owns the
  full regression run.
- A failing test is evidence about the code first. Do not delete, skip or
  weaken a test to make the pipeline green; if a test itself is genuinely
  wrong, say why in your result.
- Remember CI runs on Linux against an empty, disposable PostgreSQL database
  and a placeholder GW2 API key -- a test that depends on your local machine's
  data, paths, display or network is the defect.
- Do not start unrelated work, refactoring or planning.
- Update the story's Result and Status the same way a normal completed
  attempt does.

The harness commits, pushes and re-runs CI again once you are done.

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
    # The attempt is over: it is a human's problem now, and no later run
    # may resume a pipeline step on this story's behalf.
    clear_attempt_state()

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

# ============================================================
# Completing a story -- the harness's own state transition
#
# Every completion gate has an owner: Claude implements, the evaluator
# accepts, GitHub Actions verifies. None of them owns the *bookkeeping*
# that follows a green verdict, and for a long time nothing did: the
# PASSED branch cleared the attempt record and returned "COMPLETE",
# which left the story's entry under '## Active', CURRENT_STORY.md still
# pointing at it, and the queue internally inconsistent. The next
# planning pass then ran against that state, read the stale '## Active'
# row as the execution truth, and planned around a story that had
# already shipped.
#
# So the transition is stated here, in one deterministic, idempotent
# function, exactly like the BLOCKED verdict's bookkeeping beside it. No
# model is asked to move a row.
#
# Idempotence is the crash-safety mechanism: each step is a no-op when it
# has already happened, so a FINALIZING record can simply be replayed
# after an interrupt rather than reconciled step by step.
# ============================================================

def finalize_completed_story(
        story_path: Path,
        ci_status: str = "",
        ci_sha: str = "",
) -> list[str]:
    """Move a passed story to its completed state. Idempotent.

    Returns the queue problems that remain afterwards, which the caller
    logs. The transition itself never depends on that list being empty:
    refusing to finalize because of an unrelated inconsistency elsewhere
    in the index would strand finished, published work.
    """

    story_content = read_file(story_path)

    verdict = " ".join(part for part in (ci_status, ci_sha[:7]) if part)

    # 1. The story's own Status. Claude usually wrote DONE already, but a
    #    CI-fix round sets UNFINISHED and a resumed attempt may never
    #    have rewritten it, so the harness states its own verdict.
    set_story_done(story_path)

    # 2. The index: out of '## Active' (or wherever it still sits) and
    #    into '## Done', restamped, carrying the story's title.
    backlog_content = read_file(BACKLOG_FILE)

    updated_backlog = move_backlog_entry_to_done(
        backlog_content,
        story_path.name,
        extract_section(story_content, "Title") or "",
    )

    # Any supplementary prose an earlier writer left in the index goes
    # with it -- see strip_backlog_prose().
    updated_backlog = strip_backlog_prose(updated_backlog)

    if updated_backlog != backlog_content:
        BACKLOG_FILE.write_text(updated_backlog, encoding="utf-8")
        print(f"{story_path.name} moved to BACKLOG '## Done'.")

    # 3. The pointer. Nothing is active until deterministic selection
    #    says so, and leaving a completed story named here is what made
    #    _active_is_executable() keep re-examining finished work.
    clear_active_story()

    # 4. Validate before anything else proceeds to planning or selection.
    problems = validate_backlog_consistency()

    log_line(
        f"{story_path.name} completed and finalized"
        + (f" ({verdict})" if verdict else "")
        + ": Status DONE, entry moved to '## Done', CURRENT_STORY.md cleared, "
        + (
            "queue consistent."
            if not problems
            else f"{len(problems)} queue problem(s) remain -- see below."
        )
    )

    for problem in problems:
        log_line(f"BACKLOG.md inconsistency after finalizing -- {problem}")

    return problems


def _blocked_bookkeeping(story_path: Path) -> None:
    """Make BACKLOG.md agree that this story is blocked. Idempotent."""

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


def _current_result_content() -> str:
    if CLAUDE_RESULT_FILE.exists():
        return read_file(
            CLAUDE_RESULT_FILE
        )

    return "CLAUDE_RESULT.md does not exist."


def _prepare_implementation_prompt(story_path: Path) -> tuple[str, bool]:
    # RepoMap is an optional, experimental orientation aid for
    # Claude's implementation prompt only -- never for the planner,
    # the evaluator, or the deterministic selector.
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
        story_path,
        repo_map_context=format_repo_map_for_prompt(repo_map),
    )

    NEXT_PROMPT_FILE.write_text(
        prompt + "\n",
        encoding="utf-8"
    )

    return prompt, repo_map["enabled"]


def execute_active_story(
        before_attempt=None,
        on_interruption=None,
        wait_for_evaluator=None,
) -> str:

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

    # The harness's own position, which the story file cannot express.
    resume = pending_attempt_for(story_path)

    if classification == "BLOCKED":
        clear_attempt_state()

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
        _blocked_bookkeeping(story_path)

        return "BLOCKED"

    if classification == "DONE" and resume is None:
        # No recorded attempt, so ask the repository whether this
        # completion was ever actually published (see
        # unpublished_completion above). Only a substantiated doubt
        # resumes the story; anything git cannot answer completes it
        # exactly as before.
        unpublished = unpublished_completion(story_path, story_content)

        if unpublished is None:
            print(
                "\nActive story status is DONE. "
                "Skipping Claude Code and treating "
                "the story as complete."
            )

            # Same transition as the CI-passed branch, for the same
            # reason: "treat as complete" previously returned without
            # moving anything, so a DONE story could stay under
            # '## Active' with the pointer still naming it, run after run.
            finalize_completed_story(story_path)

            clear_attempt_state()

            return "COMPLETE"

        log_line(
            f"{story_path.name} reports Status DONE, but {unpublished}. "
            "Its evaluation and CI verification never finished, so the "
            "harness resumes its own pipeline for this story instead of "
            "completing it and selecting new work. Claude is not "
            "re-invoked."
        )

        resume = {
            "phase": "AWAITING_EVALUATION",
            "claude_exit_code": 0,
            "result_was_updated": False,
            "retry_count": 0,
            "ci_fix_attempts": 0,
        }

    retry_count = 0
    failed_runs = 0
    ci_fix_attempts = 0
    prompt = None
    repo_map_enabled = False

    if resume is not None:
        # Budgets travel with the recorded attempt so a restart cannot
        # hand the same story a fresh allowance forever.
        retry_count = int(resume.get("retry_count") or 0)
        ci_fix_attempts = int(resume.get("ci_fix_attempts") or 0)

        log_line(
            f"Resuming {story_path.name} at {resume['phase']}: the "
            "previous run's Claude attempt is already finished on disk, "
            f"so it is not repeated (retries {retry_count}/"
            f"{MAX_RETRIES_PER_STORY}, CI fixes {ci_fix_attempts}/"
            f"{MAX_CI_FIX_ATTEMPTS} carried over)."
        )
    else:
        # Confirm Claude actually has capacity BEFORE doing any
        # RepoMap/prompt work -- never generate the map speculatively for
        # an attempt that might not run yet. before_attempt (the
        # scheduler's wait_for_claude) blocks here -- using idle time for
        # Codex planning -- only when Claude is not currently available;
        # it returns immediately (a no-op) when it already is, so calling
        # it here in addition to its normal per-attempt call inside the
        # retry loop below is safe and cheap.
        #
        # A resumed attempt deliberately skips this: evaluating and
        # publishing work Claude has already finished must not wait on
        # Claude's capacity.
        if before_attempt is not None:
            before_attempt()

        prompt, repo_map_enabled = _prepare_implementation_prompt(story_path)

    while True:

        evaluation = None

        if resume is not None:
            story_content = read_file(story_path)
            result_content = _current_result_content()

            if resume["phase"] == "FINALIZING":
                # Claude, the evaluator and CI all already answered for
                # this story; the previous run was interrupted partway
                # through the transition itself. Replaying it is safe
                # because every step is idempotent, and nothing here pays
                # for a verdict again or bypasses one that never ran --
                # the record only exists once CI has passed.
                log_line(
                    f"Resuming {story_path.name} at FINALIZING: its CI "
                    f"verdict ({resume.get('ci_status') or 'PASSED'}"
                    + (
                        f" {str(resume.get('ci_sha') or '')[:7]}"
                        if resume.get("ci_sha")
                        else ""
                    )
                    + ") is already established, so only the story's state "
                      "transition is completed here."
                )

                finalize_completed_story(
                    story_path,
                    str(resume.get("ci_status") or "PASSED"),
                    str(resume.get("ci_sha") or ""),
                )

                clear_attempt_state()

                return "COMPLETE"

            if resume["phase"] == "AWAITING_CI":
                # The evaluator already accepted this attempt; only
                # publication and the CI verdict are outstanding, so its
                # verdict is not paid for a second time.
                evaluation = {
                    "decision": "COMPLETE",
                    "reason": (
                        "Resumed attempt: the evaluator accepted this work "
                        "before the previous run was interrupted, and only "
                        "its CI verification was outstanding."
                    ),
                }
            else:
                evaluation = _evaluate_preserving_completed_attempt(
                    story_content,
                    result_content,
                    int(resume.get("claude_exit_code") or 0),
                    bool(resume.get("result_was_updated")),
                    wait_for_evaluator=wait_for_evaluator,
                )

            resume = None

        if evaluation is None:
            # A new Claude attempt supersedes any recorded position: what
            # is on disk is about to change, so no later run may resume
            # the previous attempt's evaluation or publication.
            clear_attempt_state()

            if prompt is None:
                prompt, repo_map_enabled = _prepare_implementation_prompt(
                    story_path
                )

            if before_attempt is not None:
                before_attempt()

            old_result_hash = file_hash(
                CLAUDE_RESULT_FILE
            )

            usage_before = _safe_claude_usage()
            claude_start = time.time()

            attempt = run_claude_attempt(
                prompt
            )

            claude_exit_code = attempt.exit_code

            claude_duration = time.time() - claude_start
            usage_after = _safe_claude_usage()

            print(
                "Claude run measurement: "
                f"repo_map={'on' if repo_map_enabled else 'off'}, "
                f"duration={claude_duration:.1f}s, "
                f"usage_before={usage_before}, "
                f"usage_after={usage_after}"
            )

            if claude_exit_code != 0:
                # An interrupted attempt is not an evaluator retry or a
                # story outcome. Keep the same story and work order.
                capacity_limited = attempt.capacity_exhausted or (
                    usage_after is not None and not usage_after.available()
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

            result_content = _current_result_content()

            story_content = read_file(
                story_path
            )

            failed_runs = 0

            # Claude's attempt is finished and its output is on disk. From
            # here until a terminal outcome, the outstanding step is the
            # harness's own -- record that before the evaluator is called,
            # so an outage or an interrupt during evaluation resumes here
            # instead of being read as a completed story.
            record_attempt_state(
                story_path,
                "AWAITING_EVALUATION",
                claude_exit_code=claude_exit_code,
                result_was_updated=result_was_updated,
                retry_count=retry_count,
                ci_fix_attempts=ci_fix_attempts,
            )

            evaluation = _evaluate_preserving_completed_attempt(
                story_content,
                result_content,
                claude_exit_code,
                result_was_updated,
                wait_for_evaluator=wait_for_evaluator,
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
            # Accepted, but not yet published or verified. Recorded before
            # the commit/push/CI wait so an interruption in there resumes
            # at publication rather than paying the evaluator again -- and,
            # crucially, never leaves finished work looking complete while
            # it is still uncommitted.
            record_attempt_state(
                story_path,
                "AWAITING_CI",
                retry_count=retry_count,
                ci_fix_attempts=ci_fix_attempts,
            )

            verification = verify_with_github_ci(story_path, story_content)

            if verification["status"] in ("PASSED", "SKIPPED"):
                # Every gate has answered. Record that the only remaining
                # step is the harness's own transition, carrying the
                # verdict with it, so an interrupt in the middle of those
                # writes resumes at the bookkeeping and never re-runs the
                # gate or Claude.
                record_attempt_state(
                    story_path,
                    "FINALIZING",
                    retry_count=retry_count,
                    ci_fix_attempts=ci_fix_attempts,
                    ci_status=verification["status"],
                    ci_sha=verification.get("sha") or "",
                )

                finalize_completed_story(
                    story_path,
                    verification["status"],
                    verification.get("sha") or "",
                )

                clear_attempt_state()

                return "COMPLETE"

            if verification["status"] != "FAILED":
                # Nobody can say whether the suite passed (no run, a
                # cancelled run, an unreadable API, a rejected push). A
                # story must never be completed on that basis, and there
                # is nothing concrete to send Claude back to fix.
                _create_intervention_and_block_story(
                    story_path,
                    story_content,
                    "The story's work is finished and accepted by the evaluator, "
                    "but its GitHub CI result could not be established: "
                    f"{verification['reason']}. Verify the pipeline manually, "
                    "then resolve this intervention.",
                    result_content,
                )

                return "NEEDS_USER"

            ci_fix_attempts += 1
            set_story_unfinished(story_path)

            if ci_fix_attempts > MAX_CI_FIX_ATTEMPTS:
                _create_intervention_and_block_story(
                    story_path,
                    story_content,
                    f"GitHub CI still fails after {MAX_CI_FIX_ATTEMPTS} "
                    "automatic fix attempts.\n\n"
                    + (verification["report"] or verification["reason"]),
                    result_content,
                )

                return "NEEDS_USER"

            print(
                f"\nGitHub CI failed; returning the story to Claude "
                f"({ci_fix_attempts}/{MAX_CI_FIX_ATTEMPTS})."
            )

            prompt = build_ci_failure_prompt(verification, story_path)

            NEXT_PROMPT_FILE.write_text(
                prompt + "\n",
                encoding="utf-8"
            )

            continue

        if decision == "BLOCKED":
            # Deterministic side effect of the verdict, exactly like the
            # NEEDS_USER path below -- the evaluator classifies, Python
            # records. Without this the verdict changed nothing at all:
            # the story kept whatever Status Claude wrote, its bullet
            # stayed under '## Active', CURRENT_STORY.md still pointed at
            # it, and the next cycle re-invoked Claude on the same story
            # with the same prompt -- against no retry budget and with no
            # escalation, because a BLOCKED verdict consumes neither.
            clear_attempt_state()

            set_story_blocked(
                story_path,
                f"Blocked per evaluator: {reason}",
            )

            _blocked_bookkeeping(story_path)

            log_line(
                f"{story_path.name} recorded BLOCKED per the evaluator's "
                f"verdict: {reason}"
            )

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

        # The evaluator has rejected the attempt, so the story file must
        # stop claiming DONE -- the CI-failure branch above already does
        # this for the same reason. Leaving DONE in place meant an
        # interrupted retry looked like a finished story, and a later
        # empty-RETRY verdict would have been normalized to COMPLETE on
        # the strength of that stale label (see normalize_evaluation).
        set_story_unfinished(story_path)

        prompt = build_retry_prompt(
            actionable_retry_items,
            reason,
            story_path,
            unmet_intent=evaluation.get("unmet_intent") or [],
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
# already uses for validation) and never invoke Codex, Claude, the
# evaluator, or the project planner themselves.
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
    initial_inputs = planning_fingerprint()

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

        if planning_fingerprint() != initial_inputs:
            log_line("Planning inputs changed; resuming orchestration.")
            return
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

def planning_input_snapshot():
    # No-work/NEEDS_USER suppression lasts only while relevant local inputs stay unchanged.
    # An answered architect request is one of those inputs: resolving it is
    # exactly what makes a previously unplannable question plannable.
    paths = {BACKLOG_FILE, CURRENT_STORY_FILE, PROJECT_STATE_FILE, ROADMAP_FILE, TARGET_ARCHITECTURE_FILE}
    for directory in (BACKLOG_FILE.parent, USER_DECISIONS_DIR, PRODUCT_OWNER_REQUESTS_DIR,
                      ARCHITECT_REQUESTS_DIR):
        paths.update(directory.glob("*.md"))
    paths.update((REPO_ROOT / "docs").rglob("*.md"))
    paths.update((REPO_ROOT / "agent").glob("*INSTRUCTIONS.md"))
    paths.add(REPO_ROOT / "AGENTS.md")
    # Contract/validator fixes are meaningful inputs too; logs, result JSON,
    # capacity readings and timestamps are deliberately excluded.
    # Only code defining planning/validation contracts belongs in this key.
    # Test edits and terminal/runner changes are not new planning information.
    paths.update(REPO_ROOT / "agent/runtime" / relative for relative in (
        "core/project_planner.py", "core/planning_context.py",
        "core/story_state.py", "support/config.py",
        "human/user_decisions.py", "human/product_owner_requests.py",
        "human/architect_requests.py",
    ))
    # An implementation result is external planning evidence, unlike the
    # planner's own JSON result/cache or terminal logs.
    paths.add(REPO_ROOT / "agent/runtime/artifacts/CLAUDE_RESULT.md")
    snapshot = {}
    for path in sorted(paths):
        try:
            key = path.relative_to(REPO_ROOT).as_posix()
        except ValueError:
            key = path.as_posix()
        if path.exists():
            # Editors/Git may save LF as CRLF after the planner exits. That is
            # not a changed requirement. Preserve all other whitespace/content.
            content = path.read_bytes().decode("utf-8-sig")
            content = content.replace("\r\n", "\n").replace("\r", "\n")
            snapshot[key] = hashlib.sha256(content.encode("utf-8")).hexdigest()
        else:
            snapshot[key] = None
    return snapshot


def planning_fingerprint(snapshot=None):
    if snapshot is None:
        snapshot = planning_input_snapshot()
    return hashlib.sha256(json.dumps(snapshot, sort_keys=True).encode()).hexdigest()


def changed_planning_inputs(before, after):
    return sorted(key for key in before.keys() | after.keys()
                  if before.get(key) != after.get(key))



class CapacityScheduler:
    """
    Independent per-model capacity gating. Neither model's exhaustion is
    ever a story or planning failure, and neither blocks the other:
    Claude drains already-planned work while Codex is exhausted, Codex
    replenishes the queue while Claude is exhausted, and when both are
    out the orchestrator waits locally instead of exiting.
    """

    def __init__(self, claude=None, codex=None,
                 cache_file=REPO_ROOT / "agent/runtime/artifacts/PLANNING_CACHE.json"):
        self.claude = claude or CapacityProbe(self._read_claude_capacity)
        self.codex_capacity = None
        self.codex = codex or CapacityProbe(self._read_codex_capacity)
        self.cache_file = cache_file
        self.no_work_at = None
        self.planning_hold = None
        if cache_file is not None:
            try:
                cached = json.loads(cache_file.read_text(encoding="utf-8"))
                if cached.get("version") == 2:
                    self.no_work_at = cached["fingerprint"]
                    self.planning_hold = cached
            except (OSError, ValueError, KeyError, AttributeError):
                pass
        self.claude_usage_percent = None
        self.claude_weekly_used_percent = None
        self.codex_failure = None
        # Remembered purely so a transition is logged once, instead of
        # the same "unavailable" line every polling cycle.
        self.exhausted = {"Claude": False, "Codex": False}
        self.heartbeat = StatusHeartbeat()

    def _read_claude_capacity(self):
        # `claude -p /usage` runs a slash command, so this costs no
        # tokens and never invokes the exhausted model. The reading is
        # kept for the terminal status line.
        self.claude_usage_percent = None
        self.claude_weekly_used_percent = None
        usage = get_claude_usage()
        self.claude_usage_percent = usage.session_used_percent
        self.claude_weekly_used_percent = usage.weekly_used_percent

        return usage.available()

    def _read_codex_capacity(self):
        self.codex_capacity = None  # Never present an old successful reading as fresh.
        self.codex_capacity = read_codex_capacity()
        return self.codex_capacity["available"]

    def _show_codex_capacity(self, role):
        # Reuse the gate's reading, not another RPC (and never a model turn).
        detail = format_codex_capacity(self.codex_capacity) if self.codex_capacity else "quota details unavailable"
        print_status(f"Codex available before {role}: {detail}")

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

    def _hold_planning(self, result, snapshot=None, fingerprint=None, planner_changes=None):
        # Store POST-pass inputs: the planner may have created the blocking UD
        # or independent stories. Its own writes must not trigger a retry.
        snapshot = planning_input_snapshot() if snapshot is None else snapshot
        self.no_work_at = planning_fingerprint(snapshot) if fingerprint is None else fingerprint
        self.planning_hold = {
            "version": 2, "fingerprint": self.no_work_at,
            "inputs": snapshot,
            "planner_changed_inputs": planner_changes or [],
            "independent_work_remaining": result.get("independent_work_remaining"),
            "status": result.get("status", "FAILED"),
            "user_decision_ids": result.get("user_decision_ids", []),
            "reason": result.get("reason", ""),
            "validation_problems": result.get("validation_problems", []),
        }
        if self.cache_file is not None:
            self.cache_file.parent.mkdir(parents=True, exist_ok=True)
            temporary = self.cache_file.with_suffix(".tmp")
            temporary.write_text(json.dumps(self.planning_hold, indent=2), encoding="utf-8")
            temporary.replace(self.cache_file)

    def _planning_failed(self, description):
        self._hold_planning({"status": "FAILED", "reason": description})
        log_line(f"Project planning failed ({description}). Planning is held until "
                 "inputs change or PLANNING_CACHE.json is removed; independent execution continues.")
        return False

    def plan_if_useful(self, idle=False, force=False):
        if self.cache_file is not None and self.no_work_at and not self.cache_file.exists():
            self.no_work_at = None
            self.planning_hold = None
        before = planning_input_snapshot()
        fingerprint = planning_fingerprint(before)
        changed_since_hold = bool(
            self.no_work_at
            and fingerprint != self.no_work_at
        )
        queue_low = should_trigger_planning(len(get_selectable_story_candidates()))
        # A held result that reported independent work remaining is a trigger in its
        # own right. Nothing else can change while planning is held, so without this
        # the bounded follow-up pass below was only ever reached when the queue
        # happened to be low -- i.e. the flag was silently ignored on a stocked queue.
        follow_up_requested = bool(
            self.planning_hold
            and self.planning_hold.get("independent_work_remaining") is True
        )
        if (idle and fingerprint == self.no_work_at and self.planning_hold
                and not follow_up_requested):
            log_line(
                "Decision: wait -- no planning inputs changed and previous "
                "planner result has no independent work remaining"
            )
            return False
        if not (force or queue_low or changed_since_hold or follow_up_requested or
                (idle and self.planning_hold is None)):
            return False
        if fingerprint == self.no_work_at:
            if follow_up_requested:
                pass  # A bounded follow-up pass was explicitly requested by the planner result.
            else:
                log_line(
                    "Decision: wait -- no planning inputs changed and previous "
                    "planner result has no independent work remaining"
                )
                return False
        if not self.codex_available():
            return False
        if self.planning_hold and self.planning_hold.get("inputs"):
            changes = changed_planning_inputs(self.planning_hold["inputs"], before)
            if changes:
                log_line("Planning resumed for changed inputs: " + ", ".join(changes))
        self._show_codex_capacity("planner")
        try:
            result = run_planning_pass()
        except ModelCapacityUnavailable:
            return self._codex_exhausted("planning")
        except (RuntimeError, ValueError, OSError, TypeError, AttributeError) as exc:
            return self._planning_failed(f"{type(exc).__name__}: {exc}")
        status = result.get("status")
        if status not in ("COMPLETE", "NEEDS_USER"):
            self._hold_planning(result)
            log_line(f"Project planning FAILED: {result.get('reason', 'invalid output')}; "
                     f"validation: {result.get('validation_problems', [])}. "
                     "Held until planning inputs change; independent execution continues.")
            return False
        self.codex_failure = None
        after = planning_input_snapshot()
        after_fingerprint = planning_fingerprint(after)
        changed = after_fingerprint != fingerprint
        useful = changed and bool(
            result.get("story_files_created") or result.get("milestone_transition")
            or result.get("architect_requests_created") or result.get("independent_work_remaining"))
        # Always checkpoint the post-pass state: planner-owned artifacts must
        # not be mistaken for fresh inputs on the next idle scheduling cycle.
        # independent_work_remaining=True still permits one follow-up pass.
        self._hold_planning(result, after, after_fingerprint,
                            changed_planning_inputs(before, after))
        return useful

    def answer_architect_request(self, request):
        """
        Run ARCHITECTURE MODE for one already-selected architect request
        (agent/ARCHITECT_INSTRUCTIONS.md). The caller decides *whether*
        there is an actionable request; this only runs one and reports
        whether the inbox state moved.
        """

        if not self.codex_available():
            return False

        self._show_codex_capacity("architect")
        try:
            result = run_architect_pass(request)
        except ModelCapacityUnavailable:
            # Never an architecture failure: the request keeps its exact
            # status and is dispatched again once capacity returns.
            return self._codex_exhausted(
                "architecture work",
                f"{request['file']} left unchanged",
            )
        except (RuntimeError, ValueError, OSError) as exc:
            return self._codex_failed(
                f"Architect pass for {request['file']}",
                f"{type(exc).__name__}: {exc}")

        status = result.get("status")

        if status not in ("COMPLETE", "NEEDS_USER"):
            return self._codex_failed(
                f"Architect pass for {request['file']}",
                f"architect reported {status}: {result.get('reason', 'unknown')}")

        self.codex_failure = None

        blocking = result.get("user_decision_ids") or []

        log_line(
            f"Architect {status} for {request['file']} -> "
            f"{result.get('request_status')}"
            + (f"; blocked on {', '.join(blocking)}" if blocking else "")
        )

        # Actual changes to the request/architecture invalidate the input
        # snapshot naturally. Merely running a role must not erase a hold.

        return True

    def codex_work_if_useful(self, idle=False):
        """
        Whatever Codex may usefully do right now, architecture first: a
        resolved architect request is what unblocks the planner, so
        answering one before planning avoids a pass that would only
        re-discover the same open question.
        """

        pending = get_actionable_architect_requests()

        if pending and self.answer_architect_request(pending[0]):
            return True

        return self.plan_if_useful(idle=idle)

    def _codex_exhausted(self, activity, detail=None):
        self.codex.defer()
        self.exhausted["Codex"] = True
        log_line(f"Codex capacity exhausted during {activity}"
                 + (f" ({detail})" if detail else "")
                 + "; retried after the local cooldown.")
        return False

    def _codex_failed(self, activity, description):
        self.codex_failure = f"{activity}: {description}"
        self.codex.defer()
        log_line(f"{activity} failed ({description}). Execution of "
                 "already-planned work continues; Codex work is retried after "
                 "the local cooldown.")
        return False

    def status_lines(self, unavailable):
        lines = [
            " and ".join(unavailable)
            + " capacity unavailable - waiting..."
        ]

        if "Claude" in unavailable:
            if self.claude_usage_percent is not None and self.claude_weekly_used_percent is not None:
                lines.append(
                    f"Current usage: Claude session {self.claude_usage_percent}% "
                    f"(resumes below {CLAUDE_USAGE_LIMIT_PERCENT}%), weekly "
                    f"remaining {100 - self.claude_weekly_used_percent}% "
                    f"(resumes above {CLAUDE_WEEKLY_MIN_REMAINING_PERCENT}%)"
                )
            else:
                lines.append("Current usage: Claude usage unreadable "
                             "(last local probe failed)")

        if "Codex" in unavailable:
            lines.append("Codex quota (last reading): " + (
                format_codex_capacity(self.codex_capacity) if self.codex_capacity else "unreadable"
            ))

        for name, probe in (("Claude", self.claude), ("Codex", self.codex)):
            if name not in unavailable:
                continue

            seconds = _seconds_until_recheck(probe)

            if seconds is not None:
                lines.append(
                    f"Next {name} capacity check in "
                    f"{max(1, seconds // 60)} min"
                )

        if self.codex_failure and "Codex" in unavailable:
            lines.append(
                f"Last Codex attempt failed: {self.codex_failure}"
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
            if self.codex_work_if_useful(idle=True):
                continue
            self.wait_locally(["Claude"])
        if waited:
            log_line("Orchestration resumed: continuing the active story "
                     "after waiting locally for Claude capacity.")

    def wait_for_codex(self):
        """Wait out Codex exhaustion when evaluation needs it.

        Unlike wait_for_claude() this never fills the wait with other Codex
        work -- the planner and the architect run on the same exhausted
        budget. Nor can Claude proceed meanwhile: the active story's
        attempt is finished and unevaluated, and starting a second story
        on top of it is exactly the failure ATTEMPT_STATE.json exists to
        prevent.
        """

        self.codex.defer()
        waited = False

        while not self.codex_available():
            waited = True
            self.wait_locally(["Codex"])

        if waited:
            log_line("Orchestration resumed: evaluating the finished attempt "
                     "after waiting locally for Codex capacity.")


_reported_pointer_problem = None


def _active_is_executable():
    global _reported_pointer_problem

    if not CURRENT_STORY_FILE.exists() or not read_file(CURRENT_STORY_FILE).strip():
        return False

    try:
        path = get_active_story_path()
        content = read_file(path)
        classification = classify_story_status(
            extract_status_section(content)
        )
        executable = classification not in ("DONE", "BLOCKED")

        if not executable:
            # A story's own Status is Claude's statement about the
            # implementation, never a statement about the harness's
            # remaining steps (evaluation, commit, push, CI verdict). An
            # attempt those steps never finished stays priority 1 instead
            # of being skipped in favour of a brand-new story on top of
            # unevaluated, unpushed work -- which is precisely what a
            # DONE-and-skip did.
            executable = pending_attempt_for(path) is not None or (
                classification == "DONE"
                and unpublished_completion(path, content) is not None
            )
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


_reported_inbox_problems = set()


def _report_undispatchable_architect_requests() -> list[str]:
    """
    Name every architect request the architect can never be dispatched
    for, logging each distinct problem once -- not once per polling
    cycle. Returning the filenames lets the caller include them in a
    stop reason.

    This exists because the alternative is the worst possible failure
    mode for this inbox: the planner reports itself blocked by an
    architecture question, cycle after cycle, while the architect is
    never invoked and nothing says why.
    """

    stranded = undispatchable_architect_requests()

    for request in stranded:
        problem = f"{request['file']}: {request['reason']}"

        if problem in _reported_inbox_problems:
            continue

        _reported_inbox_problems.add(problem)

        log_line(
            "Architect request cannot be dispatched -- " + problem
            + ". The architect will be invoked as soon as its Status is "
            "OPEN (or NEEDS_USER with a resolvable decision)."
        )

    return [request["file"] for request in stranded]


_reported_backlog_problems = set()


def _report_backlog_inconsistencies() -> None:
    """
    Name each distinct BACKLOG.md structural problem once -- not once per
    polling cycle, and never as a gate.

    validate_backlog_consistency() has existed, and been covered by a
    test, without any running code ever calling it: the exact corruption
    it detects (one story listed under two sections at once, an '## Active'
    bullet that disagrees with CURRENT_STORY.md, a DONE story still listed
    as Active) therefore accumulated in silence. A malformed index must be
    visible, not fatal -- so this only reports, exactly like
    _report_undispatchable_architect_requests() above.
    """

    try:
        problems = validate_backlog_consistency()
    except (FileNotFoundError, RuntimeError) as exc:
        problems = [
            "BACKLOG.md could not be validated "
            f"({type(exc).__name__}: {exc})"
        ]

    for problem in problems:
        if problem in _reported_backlog_problems:
            continue

        _reported_backlog_problems.add(problem)

        log_line("BACKLOG.md inconsistency -- " + problem)


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

    _report_backlog_inconsistencies()

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
        result = execute_active_story(
            scheduler.wait_for_claude,
            scheduler.claude.defer,
            scheduler.wait_for_codex,
        )
        if result not in ("COMPLETE", "BLOCKED", "NEEDS_USER"):
            raise RuntimeError(f"Unexpected story result: {result}")
        return "CONTINUE", True

    # Step 2a: architecture. ARCHITECTURE MODE is invoked only while an
    # actionable architect request exists -- an OPEN one, or a
    # NEEDS_USER one whose blocking decision(s) the human has since
    # resolved. There is no periodic architect run and no architecture
    # review against an empty inbox. It is attempted before planning
    # because an answered question is precisely what unblocks the
    # planner; if it cannot run (Codex exhausted, or the pass failed),
    # the cycle falls through and unrelated work continues.
    architect_pending = get_actionable_architect_requests()

    # Anything unresolved that is NOT actionable and NOT legitimately
    # waiting for the Product Owner is named in the log immediately,
    # rather than only surfacing in a stop reason at the end of a cycle.
    stranded_architect_requests = _report_undispatchable_architect_requests()

    if architect_pending and codex_ready:
        decisions.announce(
            availability,
            f"architect (answer {architect_pending[0]['file']}) -- reason: "
            f"{len(architect_pending)} actionable architect request(s), "
            "Codex available",
        )
        if scheduler.answer_architect_request(architect_pending[0]):
            return "CONTINUE", replenish
        codex_ready = scheduler.codex_available()
        availability = (
            f"claude_available={claude_ready}, codex_available={codex_ready}"
        )

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
        planned = scheduler.plan_if_useful(idle=not claude_ready, force=replenish)
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

    if (scheduler.planning_hold
            and scheduler.planning_hold["status"] == "FAILED"
            and scheduler.no_work_at == planning_fingerprint()):
        scheduler.heartbeat.say("Planning validation/execution failed; waiting for changed inputs. "
                                "See PLANNING_CACHE.json for diagnostics.")
        time.sleep(CAPACITY_WAIT_POLL_SECONDS)
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

    # An unresolved architect request that is neither actionable nor
    # waiting on a real OPEN decision cannot be advanced by any model.
    # Name it in the stop reason instead of reporting a bare "nothing
    # left to do".
    stranded = stranded_architect_requests

    decisions.announce(
        availability,
        "stop -- reason: no active story, no selectable work, no useful "
        "planning, no unresolved user decision"
        + (
            "; unadvanceable architect request(s) need a human: "
            + ", ".join(stranded)
            if stranded
            else ""
        ),
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
        # Claude/the evaluator/the planner merely because an
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
