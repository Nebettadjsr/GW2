"""Thin bridge adapter around the repository's existing QA preparation logic."""

from __future__ import annotations

from pathlib import Path

from agent.runtime.core import story_state
from agent.runtime.evaluation import implementation_contract
from agent.runtime.qa import qa_agent
from agent.runtime.support import config
from agent.runtime.support.files import read_file


class QARequestError(ValueError):
    """The requested story is no longer safe to prepare through this endpoint."""


def active_story_context(story_id: str, expected_story_contract_sha256: str) -> tuple[Path, str, str]:
    """Revalidate active identity, eligibility, attempt state and requirements."""
    readiness = implementation_contract.inspect_active_story()
    story = readiness.get("story")
    if not story or story.get("id") != story_id:
        raise QARequestError("The requested story is not the current active story.")
    if not readiness.get("eligibility", {}).get("eligible"):
        raise QARequestError("The active story is not currently eligible for QA preparation.")
    if readiness.get("preparation_status") == "recovery_required":
        raise QARequestError("ATTEMPT_STATE.json exists; recover the existing attempt before preparing QA.")

    path = story_state.get_active_story_path()
    content = read_file(path)
    actual = qa_agent.story_contract_sha256(content)
    if actual != expected_story_contract_sha256:
        raise QARequestError("Story requirements changed after selection; fetch the active contract again.")
    if config.ATTEMPT_STATE_FILE.exists():
        raise QARequestError("ATTEMPT_STATE.json exists; QA preparation is not allowed during an unfinished attempt.")
    return path, content, actual


def valid_persisted_plan(story_id: str, expected_story_contract_sha256: str) -> dict | None:
    """Return a fully validated plan only when it still belongs to this story."""
    try:
        path = story_state.get_active_story_path()
        content = read_file(path)
    except (OSError, RuntimeError, FileNotFoundError):
        return None
    if story_state.extract_story_id(content) != story_id:
        return None
    if qa_agent.story_contract_sha256(content) != expected_story_contract_sha256:
        return None
    plan = qa_agent.load_plan(path)
    if not plan or plan.get("story_contract_sha256") != expected_story_contract_sha256:
        return None
    try:
        plan = implementation_contract._validate_plan(plan, story_id, content)
    except (qa_agent.QAPlanError, OSError, ValueError, TypeError, KeyError):
        return None
    if plan["status"] == "NEEDS_USER" and not qa_agent.unresolved_plan_decisions(plan):
        # A resolved clarification permits a deliberate fresh QA pass.
        return None
    return plan


def plan_result(plan: dict, *, source: str) -> dict:
    status = plan["status"]
    return {
        "story_id": plan["story_id"],
        "qa_status": status,
        "rationale": plan.get("rationale", ""),
        "outstanding_requirements": plan.get("clarifications", []) if status == "NEEDS_USER" else [],
        "plan": plan,
        "result_source": source,
    }


def execute_active_story(story_id: str, expected_story_contract_sha256: str) -> dict:
    path, _content, fingerprint = active_story_context(story_id, expected_story_contract_sha256)
    existing = valid_persisted_plan(story_id, fingerprint)
    if existing:
        return plan_result(existing, source="persisted_qa_plan")

    plan = qa_agent.execute_preparation(path)

    # The model runs with workspace-write inside the existing QA guard. Before
    # accepting its saved plan, recheck both active identity and requirements.
    _path_after, content_after, fingerprint_after = active_story_context(
        story_id, expected_story_contract_sha256
    )
    if fingerprint_after != fingerprint or qa_agent.story_contract_sha256(content_after) != fingerprint:
        raise QARequestError("The active story requirements changed during QA; its result was not accepted.")
    if plan.get("story_contract_sha256") != fingerprint:
        raise QARequestError("The saved QA plan does not match the current story requirements.")

    # Validate the durable artifact through the same implementation gate used
    # before Claude can start. This also checks protected test hashes/snapshots.
    readiness = implementation_contract.inspect_active_story()
    qa_status = readiness.get("qa", {}).get("status")
    if qa_status != plan.get("status"):
        raise QARequestError("The persisted QA plan failed implementation-contract validation.")
    if plan["status"] in {"READY", "NO_TESTS_NEEDED"}:
        qa_agent.clear_state()
    persisted = valid_persisted_plan(story_id, fingerprint)
    if not persisted:
        raise QARequestError("The persisted QA plan or its protected-test snapshot is invalid.")
    return plan_result(persisted, source="new_qa_preparation")
