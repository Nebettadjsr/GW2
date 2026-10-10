"""Story-cycle helpers used by the n8n pipeline bridge (local_bridge/cycle.py).

This module used to be the standalone orchestrator loop. That loop was
replaced by the n8n pipeline; what remains are the shared building blocks:
QA preparation, the implementation prompt, evaluation with the conditional QA
review, retry and CI-fix prompts, story blocking and finalization, and the
planning-input fingerprint.
"""

from pathlib import Path
import time
import json
import hashlib

from agent.runtime.support.config import (
    ARCHITECT_REQUESTS_DIR,
    BACKLOG_FILE,
    CLAUDE_RESULT_FILE,
    CURRENT_STORY_FILE,
    CYCLE_RETRY_SECONDS,
    EVALUATION_ATTEMPTS,
    EVALUATION_RETRY_SECONDS,
    EVALUATOR_RESULT_FILE,
    MAX_EVALUATION_BATCHES,
    PROJECT_STATE_FILE,
    USER_DECISIONS_DIR,
    PRODUCT_OWNER_REQUESTS_DIR,
    ROADMAP_FILE,
    TARGET_ARCHITECTURE_FILE,
    MAX_QA_ATTEMPTS_PER_STORY,
    NEXT_PROMPT_FILE,
    REPO_ROOT,
)
from agent.runtime.evaluation.claude_prompt import build_claude_prompt
from agent.runtime.evaluation.evaluator import drop_post_acceptance_gates, evaluate_story
from agent.runtime.support.capacity import ModelCapacityUnavailable
from agent.runtime.support.daily_log import (
    log_line,
    print_status,
)
from agent.runtime.support.files import read_file, write_json
from agent.runtime.qa import qa_agent
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
    get_unsatisfied_dependencies,
    move_backlog_entry_to_blocked,
    move_backlog_entry_to_done,
    move_backlog_entry_to_todo,
    set_story_blocked,
    set_story_done,
    strip_backlog_prose,
    validate_backlog_consistency,
)
from agent.runtime.human.architect_requests import (
    undispatchable_requests as undispatchable_architect_requests,
)
from agent.runtime.human.user_decisions import extract_section
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
        qa_plan: dict | None = None,
        qa_integrity_findings: list[str] | None = None,
) -> dict:

    last_error = None

    for attempt in range(1, EVALUATION_ATTEMPTS + 1):
        try:
            args = (story_content, result_content, claude_exit_code, result_was_updated)
            if qa_plan is None and not qa_integrity_findings:
                return evaluate_story(*args)
            return evaluate_story(*args, qa_plan=qa_plan,
                                  qa_integrity_findings=qa_integrity_findings)
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


def _as_text_list(value) -> list[str]:
    return [str(item) for item in value or [] if str(item).strip()]


def _evaluate_preserving_completed_attempt(
        story_content: str,
        result_content: str,
        claude_exit_code: int,
        result_was_updated: bool,
        qa_plan: dict | None = None,
        qa_integrity_findings: list[str] | None = None,
) -> dict:
    """Evaluate a finished attempt in bounded batches, never rerunning Claude.

    Adds the conditional read-only QA review when the plan, the verdict or an
    integrity finding asks for it, and saves the result to
    EVALUATOR_RESULT.json for inspection. Capacity exhaustion propagates
    unchanged: it is a pause for the caller, never a failed evaluation.
    """

    batch = 0

    while True:
        try:
            evaluation = _evaluate_with_local_retries(
                story_content, result_content, claude_exit_code,
                result_was_updated, qa_plan, qa_integrity_findings,
            )
            review_required = bool(
                evaluation.get("qa_review_required")
                or evaluation.get("decision") == "RETRY"
                or (qa_plan or {}).get("post_implementation_review_required")
                or qa_integrity_findings
            )
            if review_required and qa_plan and not evaluation.get("qa_post_review"):
                review = qa_agent.run_conditional_review(story_path=get_active_story_path(), plan=qa_plan,
                                                        evaluation=evaluation)
                items, gates = drop_post_acceptance_gates(_as_text_list(review.get("actionable_items")))
                if gates and review["decision"] != "APPROVE" and not items:
                    # Only post-acceptance gates were missing: the pipeline checks them next.
                    review = {**review, "decision": "APPROVE", "actionable_items": [],
                              "reason": review.get("reason", "") + " (Post-acceptance gates are "
                                        "verified by the pipeline after acceptance: " + "; ".join(gates) + ")"}
                elif gates:
                    review = {**review, "actionable_items": items}
                qa_agent.record_review(qa_plan, review)
                evaluation["qa_post_review"] = review
                if review["decision"] == "RETRY":
                    items = review.get("actionable_items") or [review.get("reason", "QA review found an unmet requirement.")]
                    evaluation["decision"] = "RETRY"
                    evaluation["actionable_retry_items"] = list(dict.fromkeys(
                        (evaluation.get("actionable_retry_items") or []) + items
                    ))
                    evaluation["reason"] = (evaluation.get("reason", "") + " Independent QA review: " + review.get("reason", "")).strip()
                elif review["decision"] == "NEEDS_USER":
                    evaluation["decision"] = "NEEDS_USER"
                    evaluation["qa_user_decision_ids"] = qa_plan.get("user_decision_ids", [])
                    evaluation["reason"] = (evaluation.get("reason", "") + " QA requires Product Owner clarification: " + review.get("reason", "")).strip()
            write_json(EVALUATOR_RESULT_FILE, evaluation)
            return evaluation
        except ModelCapacityUnavailable:
            raise
        except (OSError, RuntimeError, ValueError, KeyError) as exc:
            batch += 1

            if batch >= MAX_EVALUATION_BATCHES:
                log_line(
                    "Evaluation still unavailable after "
                    f"{MAX_EVALUATION_BATCHES} batches of "
                    f"{EVALUATION_ATTEMPTS} attempts "
                    f"({type(exc).__name__}: {exc}). The finished Claude "
                    "work is preserved; the step reruns evaluation, never Claude."
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


def ci_failures_are_outside_story_scope(verification: dict, publish_paths) -> bool:
    """True only when every reported failing job maps to disjoint owned paths.

    Missing or unfamiliar job data stays conservatively attributable to the
    story. This prevents unrelated known CI suites from invoking Claude while
    avoiding guesses when GitHub changes job names or omits details.
    """
    failures = verification.get("failed_jobs")
    if not isinstance(failures, list) or not failures:
        return False
    scope = {str(path).replace("\\", "/") for path in publish_paths}
    ownership = (
        ("backend", ("src/", "pom.xml", "mvnw", ".mvn/")),
        ("maven", ("src/", "pom.xml", "mvnw", ".mvn/")),
        ("frontend", ("frontend/",)),
        ("vitest", ("frontend/",)),
        ("agent runtime", ("agent/runtime/", "agent/stories/", "agent/CURRENT_STORY.md",
                            "requirements-dev.txt", ".coveragerc")),
        ("python", ("agent/runtime/", "agent/stories/", "agent/CURRENT_STORY.md",
                     "requirements-dev.txt", ".coveragerc")),
        ("coverage kpi", (".github/scripts/publish_coverage_kpi.py",)),
    )
    recognized = 0
    for failure in failures:
        name = str(failure.get("job", "")).casefold()
        roots = next((paths for label, paths in ownership if label in name), None)
        if roots is None:
            return False
        recognized += 1
        if any(path == root or path.startswith(root) for path in scope for root in roots):
            return False
    return recognized > 0


def _story_commit_message(story_path: Path, story_content: str) -> str:
    story_id = extract_story_id(story_content) or story_path.stem
    title = " ".join((extract_section(story_content, "Title") or "").split())

    subject = f"implemented {story_id}" + (f": {title}" if title else "")

    if len(subject) > COMMIT_SUBJECT_MAX_CHARS:
        subject = subject[:COMMIT_SUBJECT_MAX_CHARS].rstrip() + "..."

    return subject


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
- Update the story's Result the same way a normal completed attempt does;
  the harness sets its Status.

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

    # 1. The harness alone states the terminal story Status. A coding agent
    #    may update Result and Findings, but finalization owns DONE.
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
    qa_plan = qa_agent.load_plan(story_path)
    if qa_plan is not None:
        prompt += qa_agent.implementation_contract(qa_plan)

    NEXT_PROMPT_FILE.write_text(
        prompt + "\n",
        encoding="utf-8"
    )

    return prompt, repo_map["enabled"]


def _block_story_for_qa_decision(story_path: Path, plan: dict) -> None:
    decision_ids = plan.get("user_decision_ids", [])
    refs = ", ".join(decision_ids)
    set_story_blocked(story_path, f"Blocked on QA Product Owner decision(s): {refs}.")
    backlog = read_file(BACKLOG_FILE)
    updated = move_backlog_entry_to_blocked(
        backlog, story_path.name, f"waiting on {refs}"
    )
    if updated != backlog:
        BACKLOG_FILE.write_text(updated, encoding="utf-8")
    if CURRENT_STORY_FILE.exists() and CURRENT_STORY_FILE.read_text(encoding="utf-8").strip().endswith(story_path.name):
        CURRENT_STORY_FILE.write_text("", encoding="utf-8")


def _block_story_for_qa_failure(
        story_path: Path, category: str, error: Exception, *, model_retries: int
) -> Path:
    """Hold a story on technical QA failure without misusing the PO intervention flow."""
    report = qa_agent.record_failure(
        story_path, category, f"{type(error).__name__}: {error}",
        model_retries=model_retries,
    )
    try:
        relative_report = report.relative_to(REPO_ROOT).as_posix()
    except ValueError:
        relative_report = report.as_posix()
    reason = f"QA technical failure ({category}); see {relative_report}."
    set_story_blocked(story_path, reason)
    backlog = read_file(BACKLOG_FILE)
    updated = move_backlog_entry_to_blocked(backlog, story_path.name, f"QA failure: {category}")
    if updated != backlog:
        BACKLOG_FILE.write_text(updated, encoding="utf-8")
    if CURRENT_STORY_FILE.exists() and CURRENT_STORY_FILE.read_text(encoding="utf-8").strip().endswith(story_path.name):
        CURRENT_STORY_FILE.write_text("", encoding="utf-8")
    qa_agent.clear_state()
    log_line(f"{story_path.name} blocked on technical QA failure ({category}): {error}. Report: {relative_report}")
    print(f"\n{story_path.name} blocked on technical QA failure; see {relative_report}.")
    return report


def _ensure_preimplementation_qa(story_path: Path, wait_for_qa=None) -> dict | None:
    """Run QA before coding; correct invalid plans and hold technical failures."""
    plan = qa_agent.load_plan(story_path)
    if plan and plan.get("status") in ("READY", "NO_TESTS_NEEDED"):
        changed = qa_agent.restore_protected_tests(plan)
        if changed:
            log_line("Restored QA-owned tests before coding: " + ", ".join(changed))
        return plan
    if plan and plan.get("status") == "NEEDS_USER":
        unresolved = qa_agent.unresolved_plan_decisions(plan)
        if unresolved:
            _block_story_for_qa_decision(story_path, plan)
            return None

    correction_feedback = ""
    model_retries = 0
    while True:
        try:
            if correction_feedback:
                plan = qa_agent.execute_preparation(
                    story_path, correction_feedback=correction_feedback
                )
            else:
                plan = qa_agent.execute_preparation(story_path)
        except ModelCapacityUnavailable:
            if wait_for_qa is None:
                raise
            wait_for_qa()
            continue
        except qa_agent.QAPlanError as exc:
            attempts = qa_agent.record_attempt(story_path)
            model_retries += 1
            log_line(f"QA returned an invalid plan ({attempts}/{MAX_QA_ATTEMPTS_PER_STORY}): {exc}")
            if attempts < MAX_QA_ATTEMPTS_PER_STORY:
                correction_feedback = str(exc)
                continue
            _block_story_for_qa_failure(
                story_path, "invalid_qa_output", exc, model_retries=model_retries
            )
            return None
        except qa_agent.QAFileProtectionError as exc:
            _block_story_for_qa_failure(
                story_path, "file_protection_violation", exc, model_retries=model_retries
            )
            return None
        except qa_agent.QARunnerError as exc:
            _block_story_for_qa_failure(
                story_path, "qa_runner_failure", exc, model_retries=model_retries
            )
            return None
        except (qa_agent.QAInfrastructureError, OSError, RuntimeError, ValueError,
                KeyError, TypeError) as exc:
            _block_story_for_qa_failure(
                story_path, "qa_infrastructure_failure", exc, model_retries=model_retries
            )
            return None

        if plan["status"] == "NEEDS_USER":
            _block_story_for_qa_decision(story_path, plan)
            return None
        qa_agent.clear_state()
        log_line(f"QA preparation complete for {plan['story_id']}: {plan['status']}.")
        return plan


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
    # Fingerprint planning inputs, not execution progress. Story status/result
    # sections, active-story pointers, backlog section moves and Claude's run
    # artifact change during ordinary execution and must not trigger planning.
    # An answered architect request is one of those inputs: resolving it is
    # exactly what makes a previously unplannable question plannable.
    paths = {PROJECT_STATE_FILE, ROADMAP_FILE, TARGET_ARCHITECTURE_FILE}
    for directory in (USER_DECISIONS_DIR, PRODUCT_OWNER_REQUESTS_DIR,
                      ARCHITECT_REQUESTS_DIR):
        paths.update(directory.glob("*.md"))
    paths.update(path for path in BACKLOG_FILE.parent.glob("STORY-*.md"))
    docs = REPO_ROOT / "docs"
    # Only inputs that can change product intent, planning priorities, or
    # implementation constraints release a planning hold. Descriptive current
    # architecture/state snapshots are maintained after implementation and
    # must not by themselves trigger another expensive roadmap pass.
    paths.update(docs / name for name in (
        "DOMAIN_SPEC.md", "FRONTEND_UX_GUIDELINES.md", "KNOWN_PROBLEMS.md",
        "QUALITY_METRICS.md", "TEST_STRATEGY.md", "CODING_GUIDELINES.md",
    ))
    paths.update((docs / "architecture" / "decisions").glob("*.md"))
    paths.update((docs / "bugs").glob("*.md"))
    paths.update((docs / "crafting").glob("*.md"))
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
            if path.parent == BACKLOG_FILE.parent and path.name.startswith("STORY-"):
                content = _stable_story_planning_content(content)
            snapshot[key] = hashlib.sha256(content.encode("utf-8")).hexdigest()
        else:
            snapshot[key] = None
    return snapshot


_EXECUTION_ONLY_STORY_SECTIONS = {
    "status", "result", "follow-up findings", "follow-up findings disposition",
}


def _stable_story_planning_content(content: str) -> str:
    """Exclude mutable execution evidence while retaining story requirements."""
    kept = []
    skipping = False
    for line in content.splitlines():
        if line.startswith("## "):
            skipping = line[3:].strip().casefold() in _EXECUTION_ONLY_STORY_SECTIONS
        if not skipping:
            kept.append(line)
    return "\n".join(kept).strip()


def planning_fingerprint(snapshot=None):
    if snapshot is None:
        snapshot = planning_input_snapshot()
    return hashlib.sha256(json.dumps(snapshot, sort_keys=True).encode()).hexdigest()






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



        # Loop back to the top (which calls requeue_resolved_interventions()
        # again before retrying selection) -- never call
        # Claude/the evaluator/the planner merely because an
        # implementation-time intervention resolved; the planner is
        # not needed to interpret a tool/permission/manual-step
        # resolution.
