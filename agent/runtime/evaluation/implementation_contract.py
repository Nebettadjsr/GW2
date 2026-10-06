"""Read-only implementation readiness and prompt construction."""

import hashlib
import json
import re
from pathlib import Path

from agent.runtime.core import story_state
from agent.runtime.evaluation.claude_prompt import build_claude_prompt
from agent.runtime.human.user_decisions import extract_section, list_decisions
from agent.runtime.qa import qa_agent
from agent.runtime.support import config
from agent.runtime.support.files import read_file


def inspect_active_story() -> dict:
    """Return a read-only implementation contract for the active story."""
    base = {
        "preparation_status": "invalid",
        "implementation_permitted": False,
        "outstanding_prerequisites": [],
        "story": None,
        "qa": {"status": "unavailable"},
        "instruction_files": [],
        "prompt": None,
        "contract_sha256": None,
    }
    try:
        pointer = story_state.CURRENT_STORY_FILE.read_text(encoding="utf-8").strip()
    except FileNotFoundError:
        pointer = ""
    except OSError as error:
        return _blocked(base, "invalid_active_story", f"CURRENT_STORY.md cannot be read: {error}")
    if not pointer:
        try:
            active = story_state.parse_backlog_section(read_file(story_state.BACKLOG_FILE), "Active")
        except (OSError, RuntimeError) as error:
            return _blocked(base, "invalid_active_story", f"BACKLOG.md cannot be validated: {error}")
        if active:
            return _blocked(base, "invalid_active_story", f"BACKLOG.md lists {active} as Active but CURRENT_STORY.md is empty.")
        return _blocked(base, "no_active_story", "CURRENT_STORY.md does not identify an active story.")

    try:
        path = story_state.resolve_story_path(pointer)
        content = read_file(path)
        story_id = story_state.extract_story_id(content)
        backlog = read_file(story_state.BACKLOG_FILE)
        active = story_state.parse_backlog_section(backlog, "Active")
    except (OSError, RuntimeError, FileNotFoundError) as error:
        return _blocked(base, "invalid_active_story", f"Active story state is inconsistent: {error}")

    base["story"] = {
        "id": story_id,
        "filename": path.name,
        "content": content,
        "title": extract_section(content, "Title") or path.stem,
    }
    base["story_contract_sha256"] = qa_agent.story_contract_sha256(content)
    base["dependencies"] = {
        "declared": story_state.extract_dependencies_section(content) or "None.",
        "unsatisfied": [],
    }
    if len(active) != 1 or active[0] != path.name:
        return _blocked(base, "invalid_active_story", "CURRENT_STORY.md and the sole BACKLOG.md Active entry do not match.")

    try:
        status = story_state.classify_story_status(story_state.extract_status_section(content))
        unsatisfied = story_state.get_unsatisfied_dependencies(content)
    except (RuntimeError, OSError, ValueError) as error:
        return _blocked(base, "invalid_active_story", f"Story eligibility could not be validated: {error}")
    if status not in {"TODO", "UNFINISHED"}:
        return _blocked(base, "story_not_executable", f"Active story status {status} is not executable.")
    if unsatisfied:
        base["dependencies"]["unsatisfied"] = unsatisfied
        return _blocked(base, "story_not_executable", "Story dependencies are not satisfied: " + "; ".join(unsatisfied))
    base["eligibility"] = {"eligible": True, "status": status}

    if config.ATTEMPT_STATE_FILE.exists():
        return _blocked(base, "recovery_required", "ATTEMPT_STATE.json exists; recover the recorded attempt before starting new implementation.")

    if not story_id:
        return _blocked(base, "invalid_active_story", "Active story does not contain a canonical story ID.")

    plan_file = qa_agent.plan_path(story_id)
    try:
        plan = json.loads(plan_file.read_text(encoding="utf-8"))
    except FileNotFoundError:
        _plan, blocked = _missing_qa_plan(story_id, base)
        return blocked
    except (OSError, json.JSONDecodeError) as error:
        base["qa"] = {"status": "invalid"}
        return _blocked(base, "invalid_qa_artifact", f"The persisted QA plan cannot be read: {error}")

    try:
        plan = _validate_plan(plan, story_id, content)
    except (qa_agent.QAPlanError, OSError, ValueError, TypeError, KeyError) as error:
        base["qa"] = {"status": "invalid"}
        return _blocked(base, "invalid_qa_artifact", f"The persisted QA plan is invalid or stale: {error}")

    qa_status = plan["status"]
    base["qa"] = {"status": qa_status, "rationale": plan.get("rationale", "")}
    if qa_status == "NEEDS_USER":
        unresolved = _unresolved_decisions(plan, content)
        detail = ", ".join(unresolved) if unresolved else "QA has an outstanding Product Owner clarification."
        return _blocked(base, "needs_user", f"QA requires Product Owner resolution: {detail}")

    decision_ids = _referenced_decision_ids(plan, content)
    decisions_by_id = {entry["id"]: entry for entry in list_decisions() if entry["id"]}
    unresolved = [decision_id for decision_id in decision_ids
                  if decisions_by_id.get(decision_id, {}).get("status") != "RESOLVED"]
    if unresolved:
        base["qa"] = {"status": "needs_user", "rationale": plan.get("rationale", "")}
        return _blocked(base, "needs_user", "Unresolved Product Owner decision(s): " + ", ".join(unresolved))

    decision_text = _read_decisions(decision_ids, decisions_by_id)
    references = _story_document_references(content)
    prompt = build_claude_prompt(path)
    prompt += "\n\nIMPLEMENTATION REQUIREMENTS\n===========================\n"
    prompt += "Read and follow docs/CODING_GUIDELINES.md and the active story's References. "
    prompt += "Apply the applicable product and architecture requirements owned by those referenced documents.\n"
    if decision_text:
        prompt += "\nAPPLICABLE RESOLVED PRODUCT OWNER DECISIONS\n===========================================\n" + decision_text
    prompt += qa_agent.implementation_contract(plan)
    instruction_files = list(dict.fromkeys(
        ["CLAUDE.md", "docs/CODING_GUIDELINES.md", *references]
    ))
    try:
        contract_sha256 = _contract_fingerprint(prompt, instruction_files)
    except OSError as error:
        return _blocked(base, "invalid_implementation_contract", f"A required instruction or referenced document is unavailable: {error}")

    base.update(
        preparation_status="ready" if qa_status == "READY" else "no_tests_needed",
        implementation_permitted=True,
        qa={"status": qa_status, "rationale": plan.get("rationale", "")},
        instruction_files=[*instruction_files,
                           "agent/runtime/support/config.py (implementation ownership contract)"],
        prompt=prompt,
        contract_sha256=contract_sha256,
        outstanding_prerequisites=[],
    )
    return base


def _contract_fingerprint(prompt: str, instruction_files: list[str]) -> str:
    digest = hashlib.sha256(prompt.encode("utf-8"))
    for relative in instruction_files:
        digest.update(relative.encode("utf-8"))
        digest.update((config.REPO_ROOT / relative).read_bytes())
    return digest.hexdigest()


def _validate_plan(plan: dict, story_id: str, story_content: str) -> dict:
    if not isinstance(plan, dict):
        raise qa_agent.QAPlanError("QA plan root must be an object.")
    if plan.get("schema_version") != 1 or plan.get("story_id") != story_id:
        raise qa_agent.QAPlanError("QA plan schema or story ID does not match the active story.")
    expected = qa_agent.story_contract_sha256(story_content)
    if plan.get("story_contract_sha256") != expected:
        raise qa_agent.QAPlanError("QA plan does not contain a matching story requirements fingerprint; prepare QA again.")
    normalized = qa_agent.validate_plan(plan, story_id, plan.get("prepared_test_paths", []))
    if normalized["status"] not in {"READY", "NO_TESTS_NEEDED", "NEEDS_USER"}:
        raise qa_agent.QAPlanError("QA outcome does not permit implementation.")
    decision_ids = plan.get("user_decision_ids", [])
    if not isinstance(decision_ids, list) or any(not isinstance(value, str) for value in decision_ids):
        raise qa_agent.QAPlanError("user_decision_ids must be a list of decision IDs.")
    _validate_protected_tests(plan)
    return normalized | {"user_decision_ids": decision_ids}


def _missing_qa_plan(story_id: str, base: dict):
    state_path = config.QA_STATE_FILE
    if state_path.exists():
        try:
            state = json.loads(state_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            base["qa"] = {"status": "invalid_state"}
            return None, _blocked(base, "invalid_qa_artifact", f"QA_STATE.json cannot be read: {error}")
        if isinstance(state, dict) and state.get("story_id") == story_id:
            base["qa"] = {"status": "incomplete", "attempts": state.get("attempts", 0)}
            return None, _blocked(base, "preparation_required", "QA_STATE.json records incomplete preparation for this story; resume QA preparation and persist a valid plan first.")
    base["qa"] = {"status": "missing"}
    return None, _blocked(base, "preparation_required", "No persisted QA plan exists for the active story; complete QA preparation first.")


def _validate_protected_tests(plan: dict) -> None:
    paths = plan.get("prepared_test_paths", [])
    hashes = plan.get("protected_test_hashes", {})
    if not isinstance(hashes, dict) or set(hashes) != set(paths):
        raise qa_agent.QAPlanError("Protected-test hashes do not match the prepared test paths.")
    snapshot_root = config.ARTIFACTS_DIR / "qa-tests"
    for relative in paths:
        expected = hashes.get(relative)
        if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected):
            raise qa_agent.QAPlanError(f"Protected-test hash is invalid for {relative}.")
    unchanged, changed = qa_agent.plan_tests_unchanged(plan)
    if not unchanged:
        raise qa_agent.QAPlanError("Protected QA test(s) changed in the working tree: " + ", ".join(changed))
    for relative, expected in hashes.items():
        snapshot = snapshot_root / relative
        try:
            actual = hashlib.sha256(snapshot.read_bytes()).hexdigest()
        except OSError as error:
            raise qa_agent.QAPlanError(f"Protected QA test {relative} is missing from the durable snapshot.") from error
        if actual != expected:
            raise qa_agent.QAPlanError(f"Protected QA test {relative} changed in the durable snapshot.")


def _referenced_decision_ids(plan: dict, content: str) -> list[str]:
    identifiers = set(plan.get("user_decision_ids", []))
    identifiers.update(re.findall(r"\bUD-\d+\b", content))
    return sorted(identifiers)


def _unresolved_decisions(plan: dict, content: str) -> list[str]:
    decisions = {entry["id"]: entry for entry in list_decisions() if entry["id"]}
    return [identifier for identifier in _referenced_decision_ids(plan, content)
            if decisions.get(identifier, {}).get("status") != "RESOLVED"]


def _read_decisions(identifiers: list[str], decisions: dict) -> str:
    sections = []
    for identifier in identifiers:
        entry = decisions[identifier]
        path = config.USER_DECISIONS_DIR / entry["file"]
        sections.append(f"--- {identifier} ---\n{read_file(path)}")
    return "\n\n".join(sections) + ("\n" if sections else "")


def _story_document_references(content: str) -> list[str]:
    references = extract_section(content, "References") or ""
    paths = re.findall(r"(?:docs|agent)/[A-Za-z0-9_./-]+\.md", references)
    return list(dict.fromkeys(paths))


def _blocked(result: dict, status: str, diagnostic: str) -> dict:
    result["preparation_status"] = status
    result["outstanding_prerequisites"] = [diagnostic]
    return result
