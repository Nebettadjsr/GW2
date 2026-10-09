"""Story development cycle driven one step at a time by n8n.

The bridge keeps one persisted cycle record per story. Its ``phase`` names the
next step to run; a step only advances the phase once it has finished, so any
interruption simply reruns the same step. Each step reuses the old
orchestrator's runtime functions, including its retry and CI-fix budgets:

    [plan] -> select -> qa -> implement -> evaluate -> publish -> ci -> finalize
                                ^            |                    |
                                +-- RETRY ---+---- CI FAILED -----+

With no active story, queued work is selected; an empty queue first runs the
architect (for actionable requests) and the project planner, as the old
orchestrator did.

Blocking outcomes (NEEDS_USER, BLOCKED, exhausted budgets) move the story to
the backlog's Blocked section and clear the pointer, so the next run selects
other work instead of stalling.
"""

from __future__ import annotations

import time
from pathlib import Path
from typing import Callable, NamedTuple

from agent.runtime.core import orchestrator, story_state
from agent.runtime.core.project_planner import run_planning_pass
from agent.runtime.core.selector import select_next_story
from agent.runtime.qa import qa_agent
from agent.runtime.runners.claude_runner import run_claude_attempt
from agent.runtime.support import git_sync, github_ci
from agent.runtime.support.config import (
    BACKLOG_FILE, CLAUDE_RESULT_FILE, CURRENT_STORY_FILE, MAX_CI_FIX_ATTEMPTS,
    MAX_CLAUDE_FAILED_RUNS_PER_STORY, MAX_RETRIES_PER_STORY, REPO_ROOT,
)
from agent.runtime.support.files import file_hash, read_file
from local_bridge import claude_budget

ASYNC_STEPS = {"plan", "qa", "implement", "evaluate", "ci"}
CONTINUATION = (
    "Continue the interrupted attempt on the SAME active story.\n"
    "Inspect existing repository work and resume where it stopped; "
    "do not restart completed work.\n\n"
)


class StepContext(NamedTuple):
    checkpoint: Callable[[], None]  # persist the cycle mid-step
    budget_settings: dict | None = None  # the n8n "GW2 Claude Budget" row


class StepResult(dict):
    """Outcome of one step: ``proceed`` tells n8n whether to run the next one."""

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
        "prompt": None,
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

def idle_step(planning: dict) -> str:
    """With no active story: select queued work, otherwise plan if that can help."""
    orchestrator.requeue_resolved_interventions()
    qa_agent.requeue_resolved_qa_stories()
    if story_state.get_selectable_story_candidates():
        return "select"
    if orchestrator.get_actionable_architect_requests():
        return "plan"
    # Planning on unchanged inputs would only repeat the last empty answer.
    if orchestrator.planning_fingerprint() != planning.get("fingerprint"):
        return "plan"
    return "select"  # reports why the queue is empty


def step_plan(planning: dict) -> StepResult:
    requests = orchestrator.get_actionable_architect_requests()
    if requests:
        result = orchestrator.run_architect_pass(requests[0])
        status = result.get("status", "FAILED")
        return StepResult(f"architect_{status.lower()}", status != "FAILED",
                          result.get("reason", ""), request=requests[0]["file"])
    result = run_planning_pass()
    planning["fingerprint"] = orchestrator.planning_fingerprint()
    status = result.get("status", "FAILED")
    created = result.get("story_files_created") or []
    planned = status == "COMPLETE" and bool(story_state.get_selectable_story_candidates())
    return StepResult(f"planning_{status.lower()}", planned,
                      result.get("reason", "") or ("" if planned else "The planner added no selectable story."),
                      stories_created=created)


def step_select(_cycle: None) -> tuple[StepResult, dict | None]:
    selection = select_next_story()
    if selection["decision"] != "NEXT":
        return StepResult("queue_empty", False, selection.get("reason", ""),
                          decision=selection["decision"]), None
    story_path = story_state.set_active_story(selection["story_path"])
    cycle = new_cycle(story_path)
    return StepResult("activated", True, selection.get("reason", "")), cycle


def step_qa(cycle: dict, _context: StepContext) -> StepResult:
    plan = orchestrator._ensure_preimplementation_qa(_story_path(cycle))
    if plan is None:
        # QA recorded a Product Owner decision or a technical failure and
        # already moved the story to Blocked.
        return _stop_blocked(cycle, "QA preparation blocked the story; see its QA plan or failure report.")
    cycle["phase"] = "implement"
    return StepResult(plan["status"], True)


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

    prompt = cycle["prompt"] or orchestrator._prepare_implementation_prompt(story_path)[0]
    if cycle["implement_started"]:
        # A previous run of this step was interrupted mid-attempt.
        prompt = CONTINUATION + prompt.removeprefix(CONTINUATION)
    else:
        cycle.update(implement_started=True, prompt=prompt,
                     run_baseline=sorted(git_sync.working_tree_paths()),
                     result_hash_before=file_hash(CLAUDE_RESULT_FILE))
    baseline = set(cycle["run_baseline"])
    context.checkpoint()  # an interrupted run must resume with this baseline

    plan_file = qa_agent.plan_path(plan["story_id"])
    plan_snapshot = plan_file.read_bytes()
    lifecycle = story_state.snapshot_story_lifecycle(story_path)
    integrity = qa_agent.restore_protected_tests(plan)
    try:
        try:
            attempt = run_claude_attempt(prompt, label=f"Implementation {cycle['story_id']}")
        finally:
            integrity += qa_agent.restore_protected_tests(plan)
    finally:
        integrity += [f"harness lifecycle state: {path}"
                      for path in story_state.restore_story_lifecycle(story_path, lifecycle)]
        if plan_file.read_bytes() != plan_snapshot:
            plan_file.write_bytes(plan_snapshot)
            integrity.append(_relative(plan_file))

    changed = {path for path in git_sync.working_tree_paths() - baseline
               if not path.startswith("agent/logs/")}
    cycle["publish_paths"] = sorted(set(cycle["publish_paths"]) | changed)

    if attempt.exit_code != 0:
        story_state.set_story_unfinished(story_path)
        if attempt.capacity_exhausted:
            return StepResult("capacity_exhausted", False,
                              "Claude ran out of capacity; rerun the pipeline once it is available.")
        cycle["failed_runs"] += 1
        if cycle["failed_runs"] >= MAX_CLAUDE_FAILED_RUNS_PER_STORY:
            return _block(cycle, f"Claude Code exited with code {attempt.exit_code} on "
                                 f"{cycle['failed_runs']} consecutive runs without a capacity signal.",
                          attempt.output)
        return StepResult("claude_failed", True,
                          f"Claude exited with code {attempt.exit_code}; retrying the same attempt.")

    cycle.update(
        phase="evaluate", implement_started=False, prompt=None, failed_runs=0,
        integrity_findings=sorted(set(integrity)),
        result_was_updated=file_hash(CLAUDE_RESULT_FILE) != cycle["result_hash_before"],
    )
    return StepResult("implemented", True, changed_paths=sorted(changed), budget=budget)


def step_evaluate(cycle: dict, _context: StepContext) -> StepResult:
    story_path = _story_path(cycle)
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
        return StepResult(status, True, reason, sha=sha)
    if status != "FAILED":
        return StepResult(status, False, f"CI result for {sha[:12]} is not established: {reason}. "
                                         "Rerun the pipeline to check again.")
    if orchestrator.ci_failures_are_outside_story_scope(verification, cycle["publish_paths"]):
        return StepResult("FAILED_OUTSIDE_SCOPE", False,
                          "CI failed only in suites this story did not touch. Fix or rerun CI on "
                          f"GitHub for {sha[:12]}, then rerun the pipeline. Claude was not invoked.")

    story_path = _story_path(cycle)
    cycle["ci_fix_attempts"] += 1
    if cycle["ci_fix_attempts"] > MAX_CI_FIX_ATTEMPTS:
        return _block(cycle, f"GitHub CI still fails after {MAX_CI_FIX_ATTEMPTS} automatic fix "
                             "attempts.\n\n" + (verification.get("report") or reason))
    story_state.set_story_unfinished(story_path)
    cycle["prompt"] = (orchestrator.build_ci_failure_prompt(verification, story_path)
                       + qa_agent.implementation_contract(qa_agent.load_plan(story_path)))
    cycle["phase"] = "implement"
    return StepResult("FAILED", True, reason, ci_fix=cycle["ci_fix_attempts"])


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
    return StepResult("done", True, f"{cycle['story_id']} is complete.", sha=push["sha"])


STEPS = {
    "qa": step_qa, "implement": step_implement, "evaluate": step_evaluate,
    "publish": step_publish, "ci": step_ci, "finalize": step_finalize,
}
