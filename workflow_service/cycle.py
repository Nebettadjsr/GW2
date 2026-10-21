"""Stage selection and transition rules for the workflow service.

The service keeps one persisted cycle record per story. Its ``phase`` names the
next step to run; a step only advances the phase once it has finished, so any
interruption simply reruns the same step. Each step reuses the old
orchestrator's runtime functions, including its retry and CI-fix budgets:

    [plan] -> select -> qa -> implement -> evaluate -> publish -> ci -> finalize
                                ^            |                    |
                                +-- RETRY ---+---- CI FAILED -----+

Between stories, and while Claude waits for its budget, Codex does the old
orchestrator's background work first: an actionable architect request, or a
planning pass when the queue is low, planning inputs changed, or the planner
asked for a follow-up pass.

Between stories only, the agent branch first merges the maintainer branch
(``sync``); whatever that brings in is reviewed against its documents once
(``review``) and handed to the next planning pass. A finished story is
published back to the maintainer branch after CI passes.

Blocking outcomes (NEEDS_USER, BLOCKED, exhausted budgets) move the story to
the backlog's Blocked section and clear the pointer, so the next run selects
other work instead of stalling.
"""

from __future__ import annotations

import time
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Callable, NamedTuple

from agent.runtime.core import orchestrator, story_state, sync_review
from agent.runtime.core.architect import run_architect_pass
from agent.runtime.core.project_planner import run_planning_pass, should_trigger_planning
from agent.runtime.core.selector import select_next_story
from agent.runtime.human.architect_requests import get_actionable_requests
from agent.runtime.qa import qa_agent
from agent.runtime.runners.claude_runner import process_start_time, run_claude_attempt, stop_orphan
from agent.runtime.support import git_sync, github_ci
from agent.runtime.support import activity
from agent.runtime.support.config import (
    AGENT_BRANCH, BACKLOG_FILE, CLAUDE_RESULT_FILE, CURRENT_STORY_FILE, MAX_CI_FIX_ATTEMPTS,
    MAX_CLAUDE_FAILED_RUNS_PER_STORY, MAX_RETRIES_PER_STORY, MAX_QA_TEST_REPAIRS_PER_STORY, REPO_ROOT,
)
from agent.runtime.support.daily_log import log_line
from agent.runtime.support.files import file_hash, read_file
from workflow_service import claude_budget

ASYNC_STEPS = {"plan", "review", "qa", "qa_review", "qa_repair", "implement", "evaluate", "ci"}
SYNC_INTERVAL = timedelta(minutes=30)  # between stories; an idle queue fetches at most this often
MAX_REVIEW_ATTEMPTS = 2
MAX_QA_RESPONSE_RETRIES = 1
# A merge that changes these needs the service restarted to run the new code.
RESTART_PATHS = ("workflow_service/", "agent/runtime/")
CONTINUATION = (
    "Continue the interrupted attempt on the SAME active story.\n"
    "Inspect existing repository work and resume where it stopped; "
    "do not restart completed work.\n\n"
)
# Answering a resumed session: its history already holds the story, so only the
# delta is sent, plus the reminder that the tree may have moved under it.
RESUME_NOTE = (
    "This session already worked on this story. The harness may have restored "
    "protected files and others may have edited the tree since; re-read a file "
    "before you edit it.\n\n"
)
# Past this many input tokens a resume re-reads more than a fresh start costs.
SESSION_MAX_CONTEXT_TOKENS = 150_000


class StepContext(NamedTuple):
    checkpoint: Callable[[], None]  # persist the cycle mid-step
    budget_settings: dict | None = None  # persisted Claude budget configuration


class StepResult(dict):
    """Outcome of one step: ``proceed`` permits the scheduling loop to continue."""

    def __init__(self, outcome: str, proceed: bool, reason: str = "", **details):
        super().__init__(outcome=outcome, proceed=proceed, reason=reason, **details)


def _relative(path: Path) -> str:
    return path.relative_to(REPO_ROOT).as_posix()


def _story_path(cycle: dict) -> Path:
    return REPO_ROOT / cycle["story_file"]


def new_cycle(story_path: Path) -> dict:
    content = read_file(story_path)
    return {
        "story_id": story_state.extract_story_id(content) or story_path.stem,
        "story_file": _relative(story_path),
        "phase": "qa",
        "retry_count": 0,
        "ci_fix_attempts": 0,
        "failed_runs": 0,
        "qa_repair_attempts": 0,
        "qa_action": None,
        "interface_deviations": [],
        "prompt": None,
        "claude_session_id": None,
        "claude_session_started": False,
        "claude_session_tokens": 0,
        "claude_pid": None,
        "claude_pid_started": None,
        "implement_started": False,
        "publish_paths": [],
        "published_sha": None,
        "history": [],
    }


def pointer_story() -> Path | None:
    """The story named by CURRENT_STORY.md, or None when nothing is active."""
    if not CURRENT_STORY_FILE.exists() or not read_file(CURRENT_STORY_FILE).strip():
        return None
    return story_state.get_active_story_path()


def _block(cycle: dict, reason: str, claude_output: str = "") -> StepResult:
    """Hand the story to a human and free the pipeline for other work."""
    story_path = _story_path(cycle)
    orchestrator._create_intervention_and_block_story(
        story_path, read_file(story_path), reason,
        claude_output or orchestrator._current_result_content(),
    )
    return _stop_blocked(cycle, reason)


def _stop_blocked(cycle: dict, reason: str) -> StepResult:
    story_state.clear_active_story()
    cycle["phase"] = "blocked"
    return StepResult("blocked", False, reason)


# ---------------------------------------------------------------- steps

def finished_stories() -> list[str]:
    """Story files under BACKLOG.md's '## Done' -- execution progress, not a planning input."""
    return sorted(story_state.parse_backlog_section(read_file(BACKLOG_FILE), "Done"))


def codex_work_needed(planning: dict) -> bool:
    """The old orchestrator's triggers for architect or planner work."""
    if get_actionable_requests() or planning.get("follow_up") or planning.get("sync_notes"):
        return True
    queue_low = should_trigger_planning(len(story_state.get_selectable_story_candidates()))
    if orchestrator.planning_fingerprint() != planning.get("fingerprint"):
        return queue_low or planning.get("fingerprint") is not None  # or inputs changed since the last pass
    # Unchanged inputs would repeat the last answer -- unless stories finished
    # since then: a pass that waited on queued work (e.g. a milestone review
    # blocked until that work is done) must look again once it is done.
    return queue_low and finished_stories() != planning.get("finished")


def choose_step(cycle: dict | None, planning: dict, budget_settings: dict | None) -> str:
    """The next step: the cycle's phase, with Codex work between stories and while Claude waits."""
    if cycle is None:
        if planning.get("review"):
            return "review"
        if sync_due(planning):
            return "sync"
        orchestrator.requeue_resolved_interventions()
        orchestrator.requeue_dependency_blocked_stories()
        qa_agent.requeue_resolved_qa_stories()
        return "plan" if codex_work_needed(planning) else "select"
    if (cycle["phase"] == "implement" and codex_work_needed(planning)
            and not claude_budget.status(budget_settings)["claude_allowed"]):
        return "plan"
    return cycle["phase"]


def sync_due(planning: dict) -> bool:
    """Between stories, on the agent branch, at most every SYNC_INTERVAL."""
    if git_sync.current_branch() != AGENT_BRANCH:
        return False
    last = planning.get("last_sync")
    return last is None or datetime.now(timezone.utc) - datetime.fromisoformat(last) >= SYNC_INTERVAL


def step_sync(planning: dict) -> StepResult:
    """Merge the maintainer branch into the agent branch; never during a story."""
    planning["last_sync"] = datetime.now(timezone.utc).isoformat()
    result = git_sync.sync_with_maintainer_branch()
    status = result["status"]
    if status == "CONFLICT":
        return StepResult("sync_conflict", False, result["reason"] + ". Merge the maintainer branch into "
                          f"{AGENT_BRANCH} by hand in the pipeline worktree, then rerun.")
    if status != "MERGED":
        # Nothing new, or deferred/unavailable: the next sync tries again.
        return StepResult(f"sync_{status.lower()}", True, result.get("reason", ""))
    changed = result["changed_paths"]
    if sync_review.needs_review(changed):
        planning["review"] = {"base": result["before"], "head": result["after"], "attempts": 0}
    restart = any(path.startswith(RESTART_PATHS) and path.endswith(".py") for path in changed)
    return StepResult("synced", True, f"Merged {len(changed)} changed file(s) from the maintainer branch.",
                      changed_paths=changed, restart=restart)


def step_review(planning: dict) -> StepResult:
    """Review a sync's incoming range against its documents, once, for the planner."""
    review = planning["review"]
    activity.retrying('Documentation review', review['attempts'], MAX_REVIEW_ATTEMPTS - 1)
    review["attempts"] += 1
    result = sync_review.run_sync_review(review["base"], review["head"], review.get("problems"))
    if result["status"] == "FAILED" and review["attempts"] < MAX_REVIEW_ATTEMPTS:
        review["problems"] = result.get("validation_problems") or [result.get("reason", "")]
        return StepResult("review_failed", True, "Retrying once: " + result.get("reason", ""))
    planning.pop("review")
    if result["status"] == "FAILED":
        # The range stays known to the planner even though no review succeeded.
        planning["sync_notes"] = (f"Maintainer commits {review['base'][:12]}..{review['head'][:12]} were "
                                  "merged but their documentation review failed; check them for "
                                  "contradictions with the owning documents.")
        return StepResult("review_failed", True, result.get("reason", ""),
                          workflow_warnings=["Documentation sync review failed twice: "
                                             + result.get("reason", "")])
    planning["sync_notes"] = sync_review.planner_notes(result)
    return StepResult("reviewed", True, result.get("reason", ""), findings=result.get("findings", []))


def step_plan(planning: dict) -> StepResult:
    requests = get_actionable_requests()
    if requests:
        result = run_architect_pass(requests[0])
        status = result.get("status", "FAILED")
        return StepResult(f"architect_{status.lower()}", status != "FAILED",
                          result.get("reason", ""), request=requests[0]["file"])
    before = orchestrator.planning_fingerprint()
    notes = planning.get("sync_notes")
    result = run_planning_pass(notes=notes) if notes else run_planning_pass()
    if result.get("status") == "FAILED":
        # A rejected pass was rolled back whole; one retry told why usually
        # fixes a formatting slip. A second rejection waits for changed inputs.
        problems = result.get("validation_problems") or [result.get("reason") or "invalid output"]
        log_line("Planning pass rejected and rolled back; retrying once -- " + "; ".join(problems))
        activity.retrying('Planner validation', 1, 1)
        result = (run_planning_pass(rejected=problems, notes=notes) if notes
                  else run_planning_pass(rejected=problems))
    after = orchestrator.planning_fingerprint()
    status = result.get("status", "FAILED")
    if status != "FAILED":
        planning.pop("sync_notes", None)
    # A follow-up pass is only honoured after a pass that actually changed something.
    planning.update(fingerprint=after, finished=finished_stories(),
                    follow_up=bool(result.get("independent_work_remaining")) and after != before)
    return StepResult(f"planning_{status.lower()}", status != "FAILED", result.get("reason", ""),
                      stories_created=result.get("story_files_created") or [],
                      follow_up=planning["follow_up"])


def step_select(_cycle: None) -> tuple[StepResult, dict | None]:
    problems = story_state.validate_backlog_consistency()
    for problem in problems:
        log_line(f"BACKLOG.md inconsistency before selection -- {problem}")
    selection = select_next_story()
    if selection["decision"] != "NEXT":
        stranded = orchestrator._report_undispatchable_architect_requests()
        reason = selection.get("reason", "") + (
            " Architect request(s) no model can advance: " + ", ".join(stranded) if stranded else "")
        return StepResult("queue_empty", False, reason, decision=selection["decision"],
                          queue_problems=problems), None
    story_path = story_state.set_active_story(selection["story_path"])
    cycle = new_cycle(story_path)
    return StepResult("activated", True, selection.get("reason", ""), queue_problems=problems), cycle


def step_qa(cycle: dict, _context: StepContext) -> StepResult:
    plan = orchestrator._ensure_preimplementation_qa(_story_path(cycle))
    if plan is None:
        # QA recorded a Product Owner decision or a technical failure and
        # already moved the story to Blocked.
        return _stop_blocked(cycle, "QA preparation blocked the story; see its QA plan or failure report.")
    cycle["phase"] = "implement"
    return StepResult(plan["status"], True)


CLAUDE_SESSION_FIELDS = {"claude_session_id": None, "claude_session_started": False,
                         "claude_session_tokens": 0, "claude_pid": None, "claude_pid_started": None}


def clear_claude_session(cycle: dict) -> None:
    """Forget the Claude session and process recorded for a story."""
    cycle.update(CLAUDE_SESSION_FIELDS)


def release_claude(cycle: dict) -> None:
    """Stop any leftover Claude run of this cycle and forget its session (the cycle is being abandoned)."""
    stop_orphan(cycle.get("claude_pid"), cycle.get("claude_pid_started"))
    clear_claude_session(cycle)


def _reuse_session(cycle: dict) -> bool:
    """Resume the story's Claude session unless it is missing, suspect or too large."""
    return bool(cycle.get("claude_session_id") and cycle.get("claude_session_started")
                and cycle.get("failed_runs", 0) < 2
                and cycle.get("claude_session_tokens", 0) <= SESSION_MAX_CONTEXT_TOKENS)


def _reported_interface_deviations() -> list[str]:
    try:
        return qa_agent.parse_interface_deviations(read_file(CLAUDE_RESULT_FILE))
    except FileNotFoundError:
        return []


def step_implement(cycle: dict, context: StepContext) -> StepResult:
    story_path = _story_path(cycle)
    plan = qa_agent.load_plan(story_path)
    if plan is None:
        cycle["phase"] = "qa"
        return StepResult("qa_plan_missing", True, "No QA plan found; running QA preparation first.")
    budget = claude_budget.status(context.budget_settings)
    if not budget["claude_allowed"]:
        # Nothing has started; the next scheduled run tries again.
        return StepResult("waiting_for_claude_budget", False, budget["reason"], budget=budget)

    if not stop_orphan(cycle.get("claude_pid"), cycle.get("claude_pid_started")):
        # A Claude from an earlier run may still be writing to the tree or session.
        return StepResult("claude_orphan_running", False,
                          f"A Claude process (PID {cycle['claude_pid']}) from an earlier run is still alive and "
                          "could not be stopped or identified; stop it, then rerun the pipeline.")
    cycle.update(claude_pid=None, claude_pid_started=None)

    prompt = cycle["prompt"] or orchestrator._prepare_implementation_prompt(story_path)[0]
    interrupted = cycle["implement_started"]
    resume = _reuse_session(cycle)
    if interrupted:
        # A previous run of this step was interrupted mid-attempt.
        prompt = CONTINUATION + prompt.removeprefix(CONTINUATION)
        if resume:
            prompt = CONTINUATION  # the session already holds the full prompt
    else:
        cycle.update(implement_started=True, prompt=prompt,
                     run_baseline=sorted(git_sync.working_tree_paths()),
                     result_hash_before=file_hash(CLAUDE_RESULT_FILE))
    if resume:
        prompt = RESUME_NOTE + prompt
    else:
        clear_claude_session(cycle)
        cycle["claude_session_id"] = str(uuid.uuid4())
    baseline = set(cycle["run_baseline"])
    context.checkpoint()  # an interrupted run must resume with this baseline and session

    def record_pid(pid: int) -> None:
        cycle.update(claude_pid=pid, claude_pid_started=process_start_time(pid))
        context.checkpoint()  # a crash from here on must still find this process

    plan_file = qa_agent.plan_path(plan["story_id"])
    plan_snapshot = plan_file.read_bytes()
    lifecycle = story_state.snapshot_story_lifecycle(story_path)
    integrity = qa_agent.restore_protected_tests(plan)
    tests_before = qa_agent.snapshot_test_files()
    deferred = None
    try:
        try:
            attempt = run_claude_attempt(prompt, label=f"Implementation {cycle['story_id']}",
                                         session_id=cycle["claude_session_id"], resume=resume,
                                         on_start=record_pid)
            if attempt.resume_failed:
                # The session is gone (never written, or deleted): start a fresh one.
                clear_claude_session(cycle)
                cycle["claude_session_id"] = str(uuid.uuid4())
                full = cycle["prompt"] or orchestrator._prepare_implementation_prompt(story_path)[0]
                # Every Claude start is gated, including this one inside a running step.
                again = claude_budget.status(context.budget_settings)
                if not again["claude_allowed"]:
                    deferred = StepResult("waiting_for_claude_budget", False, again["reason"], budget=again)
                else:
                    attempt = run_claude_attempt(CONTINUATION + full.removeprefix(CONTINUATION)
                                                 if interrupted else full,
                                                 label=f"Implementation {cycle['story_id']}",
                                                 session_id=cycle["claude_session_id"], on_start=record_pid)
            # A session that produced any output exists on disk and must be resumed, not recreated.
            cycle.update(claude_session_started=cycle.get("claude_session_started", False) or bool(attempt.output),
                         claude_session_tokens=attempt.context_tokens or cycle.get("claude_session_tokens", 0))
        finally:
            cycle.update(claude_pid=None, claude_pid_started=None)  # this run has ended
            integrity += qa_agent.restore_protected_tests(plan)
            integrity += [f"coding agent wrote test file (reverted): {path}"
                          for path in qa_agent.revert_test_changes(tests_before)]
    finally:
        integrity += [f"harness lifecycle state: {path}"
                      for path in story_state.restore_story_lifecycle(story_path, lifecycle)]
        if plan_file.read_bytes() != plan_snapshot:
            plan_file.write_bytes(plan_snapshot)
            integrity.append(_relative(plan_file))

    changed = {path for path in git_sync.working_tree_paths() - baseline
               if not path.startswith("agent/logs/")}
    cycle["publish_paths"] = sorted(set(cycle["publish_paths"]) | changed)

    if deferred is not None:
        # Nothing further started; the pending prompt is rebuilt when the gate reopens.
        story_state.set_story_unfinished(story_path)
        return deferred
    if attempt.exit_code != 0:
        story_state.set_story_unfinished(story_path)
        # Claude's wording for an exhausted limit changes; /usage is authoritative.
        # The window is deliberately not part of this: a real failure just before it closes still counts.
        if attempt.capacity_exhausted or not (lambda b: b.get("usage_allowed", b["claude_allowed"]))(claude_budget.status(context.budget_settings)):
            return StepResult("capacity_exhausted", False,
                              "Claude ran out of capacity; rerun the pipeline once it is available.")
        cycle["failed_runs"] += 1
        if cycle["failed_runs"] >= MAX_CLAUDE_FAILED_RUNS_PER_STORY:
            return _block(cycle, f"Claude Code exited with code {attempt.exit_code} on "
                                 f"{cycle['failed_runs']} consecutive runs without a capacity signal.",
                          attempt.output)
        return StepResult("claude_failed", True,
                          f"Claude exited with code {attempt.exit_code}; retrying the same attempt.")

    result_was_updated = file_hash(CLAUDE_RESULT_FILE) != cycle["result_hash_before"]
    cycle.update(
        phase="evaluate", implement_started=False, prompt=None, failed_runs=0,
        integrity_findings=sorted(set(integrity)),
        result_was_updated=result_was_updated,
        interface_deviations=_reported_interface_deviations() if result_was_updated else [],
    )
    return StepResult("implemented", True, changed_paths=sorted(changed), budget=budget)


def step_evaluate(cycle: dict, _context: StepContext) -> StepResult:
    story_path = _story_path(cycle)
    deviations = cycle.get("interface_deviations") or []
    if deviations:
        # The coder built a different interface than QA's contract: align the tests first, cheaply,
        # before the evaluator spends tokens judging an attempt the tests cannot yet describe.
        cycle["interface_deviations"] = []
        return _queue_test_repair(cycle, {
            "decision": "REVISE_TESTS",
            "reason": "The coder reported interface deviations from QA's contract.",
            "evidence": deviations,
            "actionable_items": ["Align names, signatures, imports and setup of the affected tests to the "
                                 "implemented interface; do not change expected values."],
        }, source="interface_deviation")
    plan = qa_agent.load_plan(story_path)
    integrity = cycle.get("integrity_findings") or []
    result_content = orchestrator._current_result_content()
    if integrity:
        result_content += (
            "\n\nProtected-state violation: the coding agent modified QA-owned tests or "
            "harness-owned lifecycle state; Python restored the captured state. A conditional "
            "read-only QA review is required for: " + ", ".join(integrity)
        )
    evaluation = orchestrator._evaluate_preserving_completed_attempt(
        read_file(story_path), result_content, 0, cycle.get("result_was_updated", True),
        qa_plan=plan, qa_integrity_findings=integrity,
    )
    decision, reason = evaluation["decision"], evaluation.get("reason", "")
    cycle["evaluation"] = {"decision": decision, "reason": reason}

    if decision == "COMPLETE":
        cycle["phase"] = "publish"
        return StepResult("COMPLETE", True, reason)
    if decision == "BLOCKED":
        story_state.set_story_blocked(story_path, f"Blocked per evaluator: {reason}")
        orchestrator._blocked_bookkeeping(story_path)
        return _stop_blocked(cycle, reason)
    if decision == "NEEDS_USER":
        if evaluation.get("qa_user_decision_ids"):
            orchestrator._block_story_for_qa_decision(
                story_path, {"user_decision_ids": evaluation["qa_user_decision_ids"]})
            return _stop_blocked(cycle, reason)
        return _block(cycle, reason)

    if evaluation.get("qa_test_repair"):
        return _queue_test_repair(cycle, evaluation["qa_test_repair"], source="evaluation")

    items = evaluation.get("actionable_retry_items") or []
    if not items:
        return _block(cycle, "Evaluator returned RETRY with no actionable deficiencies.")
    cycle["retry_count"] += 1
    if cycle["retry_count"] > MAX_RETRIES_PER_STORY:
        return _block(cycle, f"Automatic retries exhausted ({MAX_RETRIES_PER_STORY}) "
                             "without the evaluator confirming completion.")
    story_state.set_story_unfinished(story_path)
    cycle["prompt"] = orchestrator.build_retry_prompt(
        items, reason, story_path, unmet_intent=evaluation.get("unmet_intent") or [],
    ) + qa_agent.implementation_contract(plan)
    cycle["phase"] = "implement"
    return StepResult("RETRY", True, reason, retry=cycle["retry_count"], items=items)


def step_publish(cycle: dict, _context: StepContext) -> StepResult:
    story_path = _story_path(cycle)
    candidates = set(cycle["publish_paths"]) | git_sync.pending_planning_paths() | {
        cycle["story_file"], _relative(BACKLOG_FILE), _relative(CURRENT_STORY_FILE),
    }
    plan = qa_agent.load_plan(story_path)
    if plan:
        candidates.add(_relative(qa_agent.plan_path(plan["story_id"])))
        candidates.update(plan.get("prepared_test_paths", []))
    # Only dirty paths can be staged; everything else is already committed.
    paths = sorted(candidates & git_sync.working_tree_paths())
    push = git_sync.commit_and_push(
        orchestrator._story_commit_message(story_path, read_file(story_path)), paths=paths)
    if push["status"] not in ("PUSHED", "UP_TO_DATE"):
        return StepResult(push["status"], False,
                          f"Publication failed: {push['reason']}. Fix it and rerun; the step is retried.")
    cycle.update(phase="ci", published_sha=push["sha"])
    return StepResult("published", True, sha=push["sha"], paths=paths)


def step_ci(cycle: dict, _context: StepContext) -> StepResult:
    sha = cycle["published_sha"]
    available, detail = git_sync.ci_verification_available()
    if available:
        verification = github_ci.wait_for_commit(
            git_sync.remote_slug(), sha, sleep=time.sleep, monotonic=time.monotonic,
            application_checks=True,
        )
    else:
        verification = {"status": "SKIPPED", "reason": detail, "report": ""}
    status, reason = verification["status"], verification.get("reason", "")

    if status in ("PASSED", "SKIPPED"):
        cycle["phase"] = "finalize"
        # Agent-workflow suites never gate a story; they are reported separately.
        warnings = [f"{item['job']} failed for {sha[:12]}: agent workflow issue, the story is not affected "
                    f"({item.get('run_url', '')})" for item in verification.get("ignored_failures", [])]
        return StepResult(status, True, reason, sha=sha, workflow_warnings=warnings)
    if status != "FAILED":
        return StepResult(status, False, f"CI result for {sha[:12]} is not established: {reason}. "
                                         "Rerun the pipeline to check again.")
    if orchestrator.ci_failures_are_outside_story_scope(verification, cycle["publish_paths"]):
        return StepResult("FAILED_OUTSIDE_SCOPE", False,
                          "CI failed only in suites this story did not touch. Fix or rerun CI on "
                          f"GitHub for {sha[:12]}, then rerun the pipeline. Claude was not invoked.")

    unchanged = cycle.get("ci_qa_no_action") or {}
    if unchanged.get("published_sha") == sha and unchanged.get("verification") == verification:
        return StepResult("ci_still_failed", False,
                          "QA already found no actionable correction for this unchanged CI failure; investigate or rerun CI.")

    cycle["qa_action"] = {"request_id": str(uuid.uuid4()), "source": "ci_failure",
                          "published_sha": sha, "verification": verification}
    cycle["phase"] = "qa_review"
    return StepResult("ci_qa_review", True, reason)


def _queue_test_repair(cycle: dict, review: dict, *, source: str) -> StepResult:
    """Only an evidenced independent QA decision can authorize test writing."""
    if (review.get("decision") != "REVISE_TESTS" or not review.get("evidence")
            or not review.get("actionable_items")):
        return _block(cycle, "Test correction was requested without an evidenced QA decision.")
    count = cycle.get("qa_repair_attempts", 0)
    if count >= MAX_QA_TEST_REPAIRS_PER_STORY:
        return _block(cycle, f"Application tests still need changes after {count} QA correction rounds.")
    cycle["qa_repair_attempts"] = count + 1
    action = cycle.get("qa_action") or {"request_id": str(uuid.uuid4()), "source": source}
    action.update(review=review, invalid_attempts=0)
    action.pop("feedback", None)
    cycle.update(phase="qa_repair", qa_action=action)
    return StepResult("qa_test_repair_requested", True, review.get("reason", ""))


def _qa_routing_failure(cycle: dict, context: StepContext, error: Exception) -> StepResult:
    action = cycle["qa_action"]
    action["invalid_attempts"] = action.get("invalid_attempts", 0) + 1
    action["feedback"] = f"{type(error).__name__}: {error}"
    if cycle["phase"] == "qa_review":
        action.pop("review", None)
        action["request_id"] = str(uuid.uuid4())
    if isinstance(error, qa_agent.QAPlanError) and action["invalid_attempts"] <= MAX_QA_RESPONSE_RETRIES:
        if context:
            context.checkpoint()
        return StepResult("qa_correction_required", True, action["feedback"])
    orchestrator._block_story_for_qa_failure(
        _story_path(cycle), "qa_test_correction_failure", error,
        model_retries=action["invalid_attempts"] - 1)
    return _stop_blocked(cycle, action["feedback"])


def step_qa_review(cycle: dict, context: StepContext) -> StepResult:
    """Read-only QA classifies application CI failure before any coder retry."""
    action = cycle["qa_action"]
    story_path = _story_path(cycle)
    plan = qa_agent.load_plan(story_path)
    if not plan:
        return StepResult("qa_plan_missing", False, "CI diagnosis requires the story's QA plan.")
    try:
        review = action.get("review")
        if review is None:
            review = qa_agent.run_conditional_review(story_path, plan, evaluation={
                "source": "ci_failure", "published_sha": action["published_sha"],
                "verification": action["verification"], "correction_feedback": action.get("feedback", "")})
            action["review"] = review
            if context:
                context.checkpoint()
        qa_agent.record_review(plan, review, request_id=action["request_id"])
    except (qa_agent.QAPlanError, qa_agent.QAInfrastructureError) as error:
        return _qa_routing_failure(cycle, context, error)
    decision, reason = review["decision"], review.get("reason", "")
    if decision == "REVISE_TESTS":
        return _queue_test_repair(cycle, review, source="ci_failure")
    if decision == "NEEDS_USER":
        updated = qa_agent.load_plan(story_path)
        orchestrator._block_story_for_qa_decision(story_path, updated)
        return _stop_blocked(cycle, reason)
    if decision == "APPROVE":
        # QA cannot turn a red published SHA green. A later run rechecks GitHub.
        cycle.update(phase="ci", qa_action=None, ci_qa_no_action={
            "published_sha": action["published_sha"], "verification": action["verification"]})
        return StepResult("ci_still_failed", False, reason or "QA found no correction; CI must be rechecked.")
    if decision != "RETRY" or not review.get("actionable_items"):
        return _qa_routing_failure(cycle, context, qa_agent.QAPlanError("QA must name actionable production defects."))
    cycle["ci_fix_attempts"] += 1
    if cycle["ci_fix_attempts"] > MAX_CI_FIX_ATTEMPTS:
        return _block(cycle, f"GitHub CI still fails after {MAX_CI_FIX_ATTEMPTS} production-code fixes.")
    story_state.set_story_unfinished(story_path)
    cycle["prompt"] = (orchestrator.build_ci_failure_prompt(action["verification"], story_path)
                       + "\n\nIndependent QA confirmed production-code deficiencies:\n"
                       + "\n".join(f"- {item}" for item in review["actionable_items"])
                       + qa_agent.implementation_contract(plan))
    cycle.update(phase="implement", qa_action=None)
    return StepResult("ci_production_retry", True, reason, ci_fix=cycle["ci_fix_attempts"])


def step_qa_repair(cycle: dict, context: StepContext) -> StepResult:
    """Write application tests only, then re-evaluate before any new publication."""
    action = cycle["qa_action"]
    story_path = _story_path(cycle)
    try:
        plan = qa_agent.execute_test_repair(story_path, action, action.get("feedback", ""))
    except (qa_agent.QAPlanError, qa_agent.QAInfrastructureError) as error:
        return _qa_routing_failure(cycle, context, error)
    if plan["status"] == "NEEDS_USER":
        orchestrator._block_story_for_qa_decision(story_path, plan)
        return _stop_blocked(cycle, "QA test correction needs a Product Owner decision.")
    if plan["status"] != "READY":
        return _qa_routing_failure(cycle, context, qa_agent.QAPlanError("Test correction did not produce READY."))
    cycle["publish_paths"] = sorted(set(cycle["publish_paths"]) | set(plan.get("prepared_test_paths", [])))
    cycle.setdefault("qa_repair_history", []).append({
        "request_id": action["request_id"], "source": action["source"],
        "reason": action["review"]["reason"], "paths": plan.get("prepared_test_paths", [])})
    # Never accept CI for the earlier commit after a test correction.
    cycle.update(phase="evaluate", qa_action=None, published_sha=None, prompt=None)
    return StepResult("qa_tests_corrected", True, "Re-evaluate corrected tests and production behavior before publishing.")


def step_finalize(cycle: dict, _context: StepContext) -> StepResult:
    story_path = _story_path(cycle)
    sha = cycle["published_sha"]
    orchestrator.finalize_completed_story(story_path, "PASSED", sha)
    paths = sorted({cycle["story_file"], _relative(BACKLOG_FILE), _relative(CURRENT_STORY_FILE)}
                   & git_sync.working_tree_paths())
    push = git_sync.commit_and_push(f"finalized {cycle['story_id']} after CI {sha[:12]}", paths=paths)
    if push["status"] not in ("PUSHED", "UP_TO_DATE"):
        return StepResult(push["status"], False,
                          f"Story is finalized locally but publishing failed: {push['reason']}. Rerun to retry.")
    git_sync.clear_pending_planning_paths()
    cycle["phase"] = "done"
    warnings = []
    published = git_sync.publish_to_maintainer_branch()
    if published["status"] == "REJECTED":
        # The maintainer branch moved; the next sync merges it, the next story publishes both.
        warnings.append(f"{cycle['story_id']} was not published to the maintainer branch yet: "
                        f"{published['reason']}")
    return StepResult("done", True, f"{cycle['story_id']} is complete.", sha=push["sha"],
                      workflow_warnings=warnings)


STEPS = {
    "qa": step_qa, "qa_review": step_qa_review, "qa_repair": step_qa_repair,
    "implement": step_implement, "evaluate": step_evaluate,
    "publish": step_publish, "ci": step_ci, "finalize": step_finalize,
}
