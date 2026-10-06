"""Thin bridge adapter for the existing read-only post-implementation QA review."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

from agent.runtime.core import story_state
from agent.runtime.evaluation import implementation_contract
from agent.runtime.qa import qa_agent
from agent.runtime.support import config


class QAReviewRequestError(ValueError):
    """The active story or persisted QA contract is not safe to review."""


def review_contract_sha256(plan: dict, implementation_task_id: str) -> str:
    """Fingerprint the review inputs, excluding only append-only review history."""
    # record_review may set these fields to NEEDS_USER after a review. They are
    # review outcomes, not changes to the prepared QA contract.
    outcome_fields = {"post_implementation_reviews", "status", "clarifications", "user_decision_ids"}
    contract = {key: value for key, value in plan.items() if key not in outcome_fields}
    contract["implementation_task_id"] = implementation_task_id
    encoded = json.dumps(contract, sort_keys=True, ensure_ascii=False,
                         separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest()


def persisted_review_plan(story_id: str, expected_story_contract_sha256: str) -> dict:
    """Load the validated plan for idempotent replay, including a recorded NEEDS_USER outcome."""
    path = story_state.get_active_story_path()
    content = path.read_text(encoding="utf-8")
    if story_state.extract_story_id(content) != story_id:
        raise QAReviewRequestError("The active story changed; fetch its review contract again.")
    plan = json.loads(qa_agent.plan_path(story_id).read_text(encoding="utf-8"))
    plan = implementation_contract._validate_plan(plan, story_id, content)
    if plan.get("story_contract_sha256") != expected_story_contract_sha256:
        raise QAReviewRequestError("The story requirements changed; fetch the review contract again.")
    return plan


def inspect_active_review(implementation: dict | None) -> dict:
    """Build a review-only gate, independent of whether new implementation is allowed."""
    base = {
        "review_permitted": False,
        "outstanding_prerequisites": [],
        "story": None,
        "qa": {"status": "unavailable"},
        "story_contract_sha256": None,
        "review_contract_sha256": None,
        "implementation_task_id": None,
    }
    try:
        path = story_state.get_active_story_path()
        content = path.read_text(encoding="utf-8")
        story_id = story_state.extract_story_id(content)
        active = story_state.parse_backlog_section(
            story_state.BACKLOG_FILE.read_text(encoding="utf-8"), "Active"
        )
        status = story_state.classify_story_status(story_state.extract_status_section(content))
        unsatisfied = story_state.get_unsatisfied_dependencies(content)
    except (OSError, ValueError, RuntimeError, FileNotFoundError) as error:
        return _review_blocked(base, "The active story state is inconsistent: " + str(error))

    base["story"] = {
        "id": story_id,
        "filename": path.name,
        "title": implementation_contract.extract_section(content, "Title") or path.stem,
    }
    base["story_contract_sha256"] = qa_agent.story_contract_sha256(content)
    if len(active) != 1 or active[0] != path.name:
        return _review_blocked(base, "CURRENT_STORY.md and the sole BACKLOG.md Active entry do not match.")
    if not story_id:
        return _review_blocked(base, "The active story has no canonical story ID.")
    if status not in {"TODO", "UNFINISHED", "DONE"}:
        return _review_blocked(base, f"Active story status {status} is not reviewable.")
    if unsatisfied:
        return _review_blocked(base, "Story dependencies are not satisfied: " + "; ".join(unsatisfied))
    if config.ATTEMPT_STATE_FILE.exists():
        return _review_blocked(base, "ATTEMPT_STATE.json exists; recover the recorded attempt before review.")
    if (not implementation or implementation.get("task_type") != "implementation"
            or implementation.get("story_id") != story_id
            or implementation.get("status") != "completed"
            or implementation.get("exit_code") != 0):
        return _review_blocked(base, "A successful persisted implementation task for the active story is required before review.")

    try:
        plan = json.loads(qa_agent.plan_path(story_id).read_text(encoding="utf-8"))
        plan = implementation_contract._validate_plan(plan, story_id, content)
    except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError,
            qa_agent.QAPlanError) as error:
        return _review_blocked(base, f"The active story's persisted QA plan is invalid or stale: {error}")
    base["qa"] = {"status": plan["status"], "rationale": plan.get("rationale", "")}
    if plan["status"] not in {"READY", "NO_TESTS_NEEDED"}:
        return _review_blocked(base, "QA has not produced an outcome that permits post-implementation review.")
    if not plan.get("post_implementation_review_required"):
        return _review_blocked(base, "The validated QA plan does not require a post-implementation review.")

    base.update(
        review_permitted=True,
        outstanding_prerequisites=[],
        implementation_task_id=implementation["id"],
        review_contract_sha256=review_contract_sha256(plan, implementation["id"]),
    )
    return base


def _review_blocked(payload: dict, message: str) -> dict:
    payload["outstanding_prerequisites"].append(message)
    return payload


def active_review_context(
    story_id: str, expected_story_contract_sha256: str, implementation: dict | None
) -> tuple[Path, dict]:
    readiness = inspect_active_review(implementation)
    story = readiness.get("story")
    if not readiness.get("review_permitted") or not story or story.get("id") != story_id:
        raise QAReviewRequestError("Post-implementation review is blocked: "
                                   + "; ".join(readiness.get("outstanding_prerequisites", [])))
    if readiness.get("story_contract_sha256") != expected_story_contract_sha256:
        raise QAReviewRequestError("The story requirements changed; fetch the review contract again before review.")
    path = story_state.get_active_story_path()
    plan = qa_agent.load_plan(path)
    if not plan:
        raise QAReviewRequestError("The active story has no valid persisted QA plan.")
    plan = implementation_contract._validate_plan(
        plan, story_id, path.read_text(encoding="utf-8")
    )
    if review_contract_sha256(plan, implementation["id"]) != readiness["review_contract_sha256"]:
        raise QAReviewRequestError("The QA review contract changed; fetch the review contract again before review.")
    return path, plan


def persisted_review(story_id: str, request_id: str) -> dict | None:
    """Return an already recorded review for safe crash recovery, without running Codex."""
    try:
        path = story_state.get_active_story_path()
        content = path.read_text(encoding="utf-8")
        if story_state.extract_story_id(content) != story_id:
            return None
        plan_path = qa_agent.plan_path(story_id)
        plan = json.loads(plan_path.read_text(encoding="utf-8"))
        implementation_contract._validate_plan(plan, story_id, content)
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError):
        return None
    for review in reversed(plan.get("post_implementation_reviews", [])):
        if isinstance(review, dict) and review.get("request_id") == request_id:
            return review
    return None


def execute_active_story_review(
    story_id: str, expected_story_contract_sha256: str, request_id: str,
    implementation: dict | None,
) -> dict:
    path, plan = active_review_context(story_id, expected_story_contract_sha256, implementation)
    existing = persisted_review(story_id, request_id)
    if existing:
        return {"story_id": story_id, **existing, "result_source": "persisted_qa_review"}

    test_verification = qa_agent.run_web021_review_tests(path, plan)
    review = qa_agent.run_conditional_review(
        path, plan,
        evaluation={"host_run_test_verification": test_verification},
    )

    # Recheck identity, contract, QA plan and protected-test hashes after the
    # read-only runner. A stale or damaged contract is never recorded as a verdict.
    after_path, after_plan = active_review_context(story_id, expected_story_contract_sha256, implementation)
    if (after_path != path
            or review_contract_sha256(after_plan, implementation["id"])
            != review_contract_sha256(plan, implementation["id"])):
        raise QAReviewRequestError("The active story or QA plan changed during review; the result was not accepted.")
    qa_agent.record_review(plan, review, request_id=request_id)
    return {"story_id": story_id, **review, "result_source": "new_post_implementation_review"}
