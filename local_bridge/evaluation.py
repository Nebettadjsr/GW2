"""Deterministic gate and task adapter for the existing Evaluator."""

from __future__ import annotations

import hashlib
import json

from agent.runtime.core import story_state
from agent.runtime.evaluation import evaluator as evaluator_runtime
from agent.runtime.evaluation import implementation_contract
from agent.runtime.qa import qa_agent
from agent.runtime.support import config
from agent.runtime.support.files import write_json

from local_bridge.qa_review import (
    active_review_context,
    inspect_active_review,
    review_contract_sha256,
)


class EvaluationRequestError(ValueError):
    """The current implementation and QA evidence do not permit evaluation."""


def _digest(value: bytes | str) -> str:
    if isinstance(value, str):
        value = value.encode("utf-8")
    return hashlib.sha256(value).hexdigest()


def evaluation_contract_sha256(
    story_content: str,
    result_content: str,
    implementation: dict,
    plan: dict,
    review_task: dict,
) -> str:
    """Bind a run to the exact story, code attempt, report and approved QA contract."""
    contract = {
        "story_sha256": _digest(story_content),
        "story_contract_sha256": qa_agent.story_contract_sha256(story_content),
        "implementation_task_id": implementation["id"],
        "implementation_contract_sha256": implementation.get("contract_sha256"),
        "implementation_result_sha256": _digest(str(implementation.get("result") or "")),
        "claude_result_sha256": _digest(result_content),
        "qa_review_contract_sha256": review_contract_sha256(plan, implementation["id"]),
        "qa_review_task_id": review_task["id"],
        "protected_test_hashes": plan.get("protected_test_hashes", {}),
    }
    encoded = json.dumps(contract, sort_keys=True, ensure_ascii=False,
                         separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def _blocked(payload: dict, reason: str, code: str = "evaluation_blocked") -> dict:
    payload["outstanding_prerequisites"].append(reason)
    payload["blocking_code"] = code
    return payload


def _approved_review(
    story_id: str, implementation: dict, plan: dict, store,
) -> dict | None:
    fingerprint = review_contract_sha256(plan, implementation["id"])
    key = f"post-review:{story_id}:{implementation['id']}:{fingerprint}"
    task = store.get_by_idempotency_key(key)
    if not task or task.get("task_type") != "post_implementation_review":
        return None
    reviews = plan.get("post_implementation_reviews", [])
    latest = reviews[-1] if reviews and isinstance(reviews[-1], dict) else None
    result = task.get("result") if isinstance(task.get("result"), dict) else {}
    if (task.get("story_id") != story_id
            or task.get("status") != "completed"
            or task.get("exit_code") != 0
            or result.get("decision") != "APPROVE"
            or result.get("story_id") != story_id
            or not latest
            or latest.get("request_id") != task.get("id")
            or latest.get("decision") != "APPROVE"):
        return None
    return task


def inspect_active_evaluation(
    implementation: dict | None, store, allow_task_id: str | None = None,
) -> dict:
    base = {
        "evaluation_permitted": False,
        "outstanding_prerequisites": [],
        "story": None,
        "implementation_task_id": None,
        "qa_review_task_id": None,
        "story_contract_sha256": None,
        "qa_review_contract_sha256": None,
        "evaluation_contract_sha256": None,
    }
    review_readiness = inspect_active_review(implementation)
    if not review_readiness.get("review_permitted"):
        return _blocked(
            base,
            "Post-implementation QA review is not ready: "
            + "; ".join(review_readiness.get("outstanding_prerequisites", [])),
            "qa_review_not_ready",
        )

    story = review_readiness.get("story") or {}
    story_id = story.get("id")
    if not story_id:
        return _blocked(base, "The active story ID could not be established.", "invalid_active_story")
    if not implementation or implementation.get("id") != review_readiness.get("implementation_task_id"):
        return _blocked(base, "A successful implementation task for the active story is required.", "implementation_missing")

    try:
        path, plan = active_review_context(
            story_id, review_readiness["story_contract_sha256"], implementation,
        )
        story_content = path.read_text(encoding="utf-8")
        unresolved = qa_agent.unresolved_plan_decisions(plan)
        if unresolved:
            return _blocked(base, "QA has unresolved Product Owner decisions: " + ", ".join(unresolved), "needs_user")

        failure_path = config.QA_FAILURES_DIR / f"QA-{story_id}.json"
        if failure_path.is_file():
            try:
                failure = json.loads(failure_path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                return _blocked(base, "The persisted QA technical-failure record is unreadable.", "qa_technical_failure")
            if failure.get("story_id") == story_id and failure.get("status") == "TECHNICAL_FAILURE":
                return _blocked(base, "A QA technical-failure record remains unresolved: " + str(failure.get("category", "unknown")), "qa_technical_failure")

        result_path = config.CLAUDE_RESULT_FILE
        result_content = result_path.read_text(encoding="utf-8")
        if not result_content.strip():
            return _blocked(base, "The persisted Claude implementation result is empty.", "implementation_result_missing")

        review_task = _approved_review(story_id, implementation, plan, store)
        if not review_task:
            latest = plan.get("post_implementation_reviews", [])
            latest_decision = latest[-1].get("decision") if latest and isinstance(latest[-1], dict) else None
            code = "needs_user" if latest_decision == "NEEDS_USER" else "qa_review_not_approved"
            return _blocked(base, "A completed APPROVE review task for the current QA contract is required.", code)

        fingerprint = evaluation_contract_sha256(story_content, result_content, implementation, plan, review_task)
        unfinished = [task for task in store.tasks_for(story_id, "evaluator")
                      if task.get("id") != allow_task_id
                      and task.get("status") in {"queued", "running", "interrupted"}]
        if unfinished:
            task = max(unfinished, key=lambda item: item.get("created_at", ""))
            is_interrupted = task.get("status") == "interrupted"
            return _blocked(
                base,
                "An earlier Evaluator task has an uncertain outcome; inspect its persisted task and result before continuing."
                if is_interrupted else "Another Evaluator task is unfinished for the active story.",
                "evaluation_interrupted" if is_interrupted else "evaluation_in_progress",
            )
        base.update(
            evaluation_permitted=True,
            outstanding_prerequisites=[],
            story={**story, "filename": path.name},
            implementation_task_id=implementation["id"],
            qa_review_task_id=review_task["id"],
            story_contract_sha256=review_readiness["story_contract_sha256"],
            qa_review_contract_sha256=review_contract_sha256(plan, implementation["id"]),
            evaluation_contract_sha256=fingerprint,
            implementation={"status": implementation["status"], "exit_code": implementation["exit_code"]},
            qa={"status": plan["status"], "review_decision": "APPROVE"},
            result_sha256=_digest(result_content),
        )
        return base
    except (OSError, ValueError, TypeError, KeyError, RuntimeError, json.JSONDecodeError) as error:
        return _blocked(base, f"Evaluation prerequisites could not be safely validated: {type(error).__name__}: {error}", "invalid_evaluation_contract")


def active_evaluation_context(
    story_id: str, expected_fingerprint: str, implementation: dict | None, store,
    evaluation_task_id: str | None = None,
) -> dict:
    readiness = inspect_active_evaluation(implementation, store, allow_task_id=evaluation_task_id)
    if not readiness.get("evaluation_permitted") or not readiness.get("story"):
        raise EvaluationRequestError("Evaluator gate rejected the request: "
                                     + "; ".join(readiness.get("outstanding_prerequisites", [])))
    if readiness["story"]["id"] != story_id:
        raise EvaluationRequestError("The active story changed; fetch the evaluation contract again.")
    if readiness.get("evaluation_contract_sha256") != expected_fingerprint:
        raise EvaluationRequestError("The implementation or QA contract changed; fetch the evaluation contract again.")

    path = story_state.get_active_story_path()
    content = path.read_text(encoding="utf-8")
    plan = qa_agent.load_plan(path)
    if not plan:
        raise EvaluationRequestError("The current QA plan is unavailable.")
    plan = implementation_contract._validate_plan(plan, story_id, content)
    reviews = plan.get("post_implementation_reviews", [])
    review = reviews[-1] if reviews and isinstance(reviews[-1], dict) else {}
    plan_for_evaluator = dict(plan)
    plan_for_evaluator["post_implementation_review"] = {
        key: review.get(key) for key in ("decision", "reason", "evidence", "actionable_items", "request_id")
    }
    implementation = store.latest_implementation_task(story_id)
    result_path = config.CLAUDE_RESULT_FILE
    result_content = result_path.read_text(encoding="utf-8")
    return {
        "story_path": path,
        "story_content": content,
        "result_content": result_content,
        "result_was_updated": bool(implementation.get("result_was_updated", False)) if implementation else False,
        "implementation": implementation,
        "plan": plan_for_evaluator,
        "qa_review_contract_sha256": readiness["qa_review_contract_sha256"],
        "qa_review_task_id": readiness["qa_review_task_id"],
        "evaluation_contract_sha256": expected_fingerprint,
        "story_sha256": _digest(content),
        "result_sha256": _digest(result_content),
    }


def persisted_evaluation(task_id: str, fingerprint: str, story_id: str) -> dict | None:
    """Read only an artifact written by this exact bridge task and contract."""
    try:
        result = json.loads(evaluator_runtime.EVALUATOR_RESULT_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    if (not isinstance(result, dict)
            or result.get("evaluation_task_id") != task_id
            or result.get("evaluation_contract_sha256") != fingerprint
            or result.get("story_id") != story_id
            or result.get("decision") not in {"COMPLETE", "RETRY", "BLOCKED", "NEEDS_USER"}):
        return None
    return result


def execute_active_evaluation(
    story_id: str, fingerprint: str, task_id: str, implementation: dict | None, store,
) -> dict:
    context = active_evaluation_context(story_id, fingerprint, implementation, store, task_id)
    result = evaluator_runtime.evaluate_story(
        context["story_content"], context["result_content"],
        int((context.get("implementation") or {}).get("exit_code") or 0),
        context["result_was_updated"], context["plan"], [], persist=False,
    )

    # Do not accept a verdict if any story, implementation, QA review, protected
    # test or report input changed while Codex was evaluating.
    after = active_evaluation_context(
        story_id, fingerprint, store.latest_implementation_task(story_id), store, task_id,
    )
    if any(after.get(field) != context.get(field) for field in (
            "story_sha256", "result_sha256", "implementation", "qa_review_contract_sha256",
            "qa_review_task_id", "evaluation_contract_sha256")):
        raise EvaluationRequestError("The evaluation contract changed while Codex was running; its verdict was discarded.")

    result.update(
        story_id=story_id,
        evaluation_task_id=task_id,
        evaluation_contract_sha256=fingerprint,
        evaluated_story_sha256=context["story_sha256"],
        evaluated_story_contract_sha256=qa_agent.story_contract_sha256(context["story_content"]),
        evaluated_result_sha256=context["result_sha256"],
        implementation_task_id=context["implementation"]["id"],
        implementation_contract_sha256=context["implementation"].get("contract_sha256"),
        qa_review_task_id=context["qa_review_task_id"],
        qa_review_contract_sha256=context["qa_review_contract_sha256"],
        result_source="bridge_evaluator_task",
    )
    write_json(evaluator_runtime.EVALUATOR_RESULT_FILE, result)
    return result
