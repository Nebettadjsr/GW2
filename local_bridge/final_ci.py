"""Guarded adapter for the repository's existing GitHub Actions final CI."""

from __future__ import annotations

import hashlib
import json
import re
import time

from agent.runtime.evaluation import evaluator as evaluator_runtime
from agent.runtime.support import git_sync
from agent.runtime.support import github_ci
from agent.runtime.support import config

from local_bridge.evaluation import inspect_active_evaluation, persisted_evaluation


CI_TASK_TYPE = "final_ci"


class FinalCIRequestError(ValueError):
    """The active implementation is not eligible for authoritative final CI."""


def _sha(value: bytes | str) -> str:
    if isinstance(value, str):
        value = value.encode("utf-8")
    return hashlib.sha256(value).hexdigest()


def _current_head_subject() -> str:
    result = git_sync.run_git("log", "-1", "--format=%s", check=False)
    if result.returncode != 0:
        return ""
    return result.stdout.strip()


def inspect_final_ci(implementation: dict | None, store, allow_task_id: str | None = None) -> dict:
    """Return readiness for an already-published implementation commit.

    The endpoint deliberately does not commit, push, or invoke local test
    commands. The CI workflow itself is the authoritative test runner.
    """
    payload = {
        "ci_permitted": False,
        "outstanding_prerequisites": [],
        "story": None,
        "implementation_task_id": None,
        "qa_review_task_id": None,
        "evaluator_task_id": None,
        "evaluation_contract_sha256": None,
        "head_sha": None,
        "final_ci_contract_sha256": None,
        "ci_workflow": ".github/workflows/ci.yml",
    }

    def block(message: str, code: str) -> dict:
        payload["outstanding_prerequisites"].append(message)
        payload["blocking_code"] = code
        return payload

    evaluation = inspect_active_evaluation(implementation, store)
    if not evaluation.get("evaluation_permitted") or not evaluation.get("story"):
        return block(
            "Evaluator prerequisites are not ready: "
            + "; ".join(evaluation.get("outstanding_prerequisites", [])),
            "evaluation_not_ready",
        )

    story_id = evaluation["story"]["id"]
    fingerprint = evaluation["evaluation_contract_sha256"]
    eval_key = f"evaluator:{story_id}:{fingerprint}"
    evaluator_task = store.get_by_idempotency_key(eval_key)
    if (not evaluator_task or evaluator_task.get("task_type") != "evaluator"
            or evaluator_task.get("story_id") != story_id
            or evaluator_task.get("contract_sha256") != fingerprint
            or evaluator_task.get("status") != "completed"
            or evaluator_task.get("exit_code") != 0):
        return block("A successful Evaluator task for the current contract is required.", "evaluator_missing")

    evaluator_result = evaluator_task.get("result")
    persisted = persisted_evaluation(evaluator_task["id"], fingerprint, story_id)
    if (not isinstance(evaluator_result, dict) or not persisted
            or persisted.get("story_id") != story_id
            or persisted.get("evaluation_task_id") != evaluator_task["id"]
            or persisted.get("evaluation_contract_sha256") != fingerprint
            or evaluator_result.get("story_id") != story_id
            or evaluator_result.get("evaluation_task_id") != evaluator_task["id"]
            or evaluator_result.get("evaluation_contract_sha256") != fingerprint
            or evaluator_result.get("decision") != "COMPLETE"
            or persisted.get("decision") != "COMPLETE"):
        return block("The persisted Evaluator result is missing, stale, or not COMPLETE.", "evaluator_result_invalid")

    available, detail = git_sync.ci_verification_available()
    if not available:
        return block("GitHub Actions CI is unavailable: " + detail, "ci_unavailable")
    branch = git_sync.current_branch()
    if not branch:
        return block("A branch checkout is required for final CI.", "branch_unavailable")
    pushed = git_sync.unpushed_commit_count(branch)
    if pushed is None or pushed != 0:
        return block("The current branch must be fully pushed before its GitHub CI run can be verified.", "commit_not_pushed")

    try:
        head_sha = git_sync.head_sha()
        if not re.fullmatch(r"[0-9a-f]{40,64}", head_sha):
            return block("The current Git commit could not be identified.", "head_unavailable")
        expected_subject = f"implemented {story_id}"
        subject = _current_head_subject()
        if not subject.startswith(expected_subject):
            return block(
                "HEAD must be the published implementation commit for this story; "
                "the bridge will not publish or select another commit.",
                "implementation_commit_not_head",
            )
        from agent.runtime.core import story_state
        from agent.runtime.qa import qa_agent

        story_path = story_state.get_active_story_path()
        plan = qa_agent.load_plan(story_path)
        protected_paths = set(plan.get("prepared_test_paths", [])) if isinstance(plan, dict) else set()
        required_paths = {
            story_path.relative_to(config.REPO_ROOT).as_posix(),
            "agent/stories/BACKLOG.md",
            "agent/CURRENT_STORY.md",
            ".github/workflows/ci.yml",
            qa_agent.plan_path(story_id).relative_to(config.REPO_ROOT).as_posix(),
            *protected_paths,
        }
        dirty = git_sync.working_tree_paths()
        if dirty.intersection(required_paths) or any(path.startswith("frontend/") for path in dirty):
            return block(
                "The story, its protected tests, or frontend implementation files have uncommitted changes; "
                "the existing GitHub CI run cannot verify that workspace state.",
                "story_changes_unpublished",
            )
        workflow_bytes = config.CI_WORKFLOW_FILE.read_bytes()
    except (OSError, ValueError, RuntimeError) as error:
        return block(f"Final CI inputs could not be read: {type(error).__name__}.", "ci_inputs_unavailable")

    contract = {
        "story_id": story_id,
        "implementation_task_id": implementation["id"],
        "evaluation_task_id": evaluator_task["id"],
        "evaluation_contract_sha256": fingerprint,
        "head_sha": head_sha,
        "ci_workflow_sha256": _sha(workflow_bytes),
    }
    contract_sha = _sha(json.dumps(contract, sort_keys=True, separators=(",", ":")))

    unfinished = [task for task in store.tasks_for(story_id, CI_TASK_TYPE)
                  if task.get("id") != allow_task_id
                  and task.get("status") in {"queued", "running", "interrupted"}]
    if unfinished:
        prior = max(unfinished, key=lambda item: item.get("created_at", ""))
        if prior.get("status") == "interrupted":
            return block("A prior final CI task has an uncertain outcome; inspect it before recovery.", "ci_interrupted")
        return block("Another final CI task is unfinished for the active story.", "ci_in_progress")

    payload.update(
        ci_permitted=True,
        outstanding_prerequisites=[],
        story=evaluation["story"],
        implementation_task_id=implementation["id"],
        qa_review_task_id=evaluation["qa_review_task_id"],
        evaluator_task_id=evaluator_task["id"],
        evaluation_contract_sha256=fingerprint,
        head_sha=head_sha,
        final_ci_contract_sha256=contract_sha,
        evaluator_decision="COMPLETE",
        operation_source="local_bridge_http",
    )
    return payload


def execute_final_ci(story_id: str, head_sha: str, contract_sha256: str, implementation: dict, store, task_id: str) -> dict:
    """Wait for GitHub Actions on the exact already-pushed HEAD; never publish."""
    before = inspect_final_ci(implementation, store, allow_task_id=task_id)
    if (not before.get("ci_permitted") or before.get("story", {}).get("id") != story_id
            or before.get("head_sha") != head_sha
            or before.get("final_ci_contract_sha256") != contract_sha256):
        raise FinalCIRequestError("Final CI readiness changed before the existing GitHub Actions run could be checked.")

    slug = git_sync.remote_slug()
    if not slug:
        raise FinalCIRequestError("The GitHub origin remote is unavailable.")
    result = github_ci.wait_for_commit(
        slug, head_sha, sleep=time.sleep, monotonic=time.monotonic,
        application_checks=True,
    )

    after = inspect_final_ci(implementation, store, allow_task_id=task_id)
    if (not after.get("ci_permitted") or after.get("head_sha") != head_sha
            or after.get("final_ci_contract_sha256") != contract_sha256):
        raise FinalCIRequestError("The story, Evaluator contract, CI workflow, or HEAD changed while final CI was running.")

    return {
        **result,
        "story_id": story_id,
        "implementation_task_id": before["implementation_task_id"],
        "evaluator_task_id": before["evaluator_task_id"],
        "evaluation_contract_sha256": before["evaluation_contract_sha256"],
        "final_ci_contract_sha256": contract_sha256,
        "result_source": "github_actions_for_pushed_commit",
        "application_verdict_policy": github_ci.APPLICATION_CI_VERDICT_POLICY,
        "operation_source": "local_bridge_http",
    }
