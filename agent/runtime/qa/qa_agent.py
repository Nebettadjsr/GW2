"""Structured QA preparation for one active story."""

import hashlib
import json
import re
import subprocess
from pathlib import Path

from agent.runtime.runners.local_planner_runner import run_qa
from agent.runtime.support import config
from agent.runtime.support.capacity import ModelCapacityUnavailable
from agent.runtime.support.files import write_json
from agent.runtime.human.user_decisions import create_qa_user_decision
from agent.runtime.human.user_decisions import list_decisions
from agent.runtime.core.story_state import (
    build_story_index,
    clear_story_blocked,
    find_story_file_by_id,
    get_unsatisfied_dependencies,
    move_backlog_entry_to_todo,
)


class QAPlanError(RuntimeError):
    pass


PLAN_STATUSES = ("READY", "NO_TESTS_NEEDED", "NEEDS_USER")
TEST_ROOTS = (
    "src/test/", "frontend/src/", "frontend/scripts/",
    "agent/runtime/tests/", "docs/bugs/fixtures/",
)
FENCED_JSON = re.compile(r"```(?:json)?\s*(\{.*?\})\s*```", re.DOTALL)


def story_id_from_content(content: str, fallback: str = "") -> str:
    match = re.search(r"^#\s*(STORY-[A-Za-z0-9]+-\d+)", content, re.MULTILINE)
    if match:
        return match.group(1).upper()
    match = re.search(r"^STORY-ID:\s*(STORY-[A-Za-z0-9]+-\d+)", content, re.MULTILINE)
    return match.group(1).upper() if match else fallback


def plan_path(story_id: str) -> Path:
    return config.QA_PLANS_DIR / f"QA-{story_id}.json"


def _safe_relative_path(value: str) -> str:
    normalized = value.replace("\\", "/").strip()
    if not normalized or normalized.startswith("/") or ".." in normalized.split("/"):
        raise QAPlanError(f"Invalid QA test path: {value!r}")
    if not any(normalized.startswith(root) for root in TEST_ROOTS):
        raise QAPlanError(f"QA may only add tests or fixtures, got {normalized!r}")
    valid_test_source = (
        normalized.startswith("src/test/")
        and normalized.endswith(("Test.java", "Tests.java", "TestCase.java"))
    ) or (
        normalized.startswith(("frontend/src/", "frontend/scripts/"))
        and normalized.endswith((".spec.ts", ".spec.mjs"))
    ) or (
        normalized.startswith("agent/runtime/tests/")
        and Path(normalized).name.startswith("test_")
        and normalized.endswith(".py")
    )
    valid_fixture = normalized.startswith("docs/bugs/fixtures/") and Path(normalized).suffix.lower() in (
        ".json", ".csv", ".txt", ".yaml", ".yml", ".xml", ".md", ".dat"
    )
    if not (valid_test_source or valid_fixture):
        raise QAPlanError(f"QA test path does not use an approved test/fixture suffix: {normalized!r}")
    return normalized


def parse_plan(messages: list[str]) -> dict:
    for message in reversed(messages):
        for candidate in reversed(FENCED_JSON.findall(message)):
            try:
                value = json.loads(candidate)
            except json.JSONDecodeError:
                continue
            if isinstance(value, dict):
                return value
        stripped = message.strip()
        if stripped.startswith("{") and stripped.endswith("}"):
            try:
                value = json.loads(stripped)
            except json.JSONDecodeError:
                continue
            if isinstance(value, dict):
                return value
    raise QAPlanError("QA did not return a JSON test plan.")


def validate_plan(raw: dict, story_id: str, owned_test_paths=()) -> dict:
    plan = dict(raw)
    status = plan.get("status")
    if status not in PLAN_STATUSES:
        raise QAPlanError(f"Unknown QA plan status: {status!r}")
    if plan.get("story_id") not in (None, story_id):
        raise QAPlanError("QA plan references a different story.")

    for field in ("acceptance_checks", "invariants", "test_levels", "existing_tests_reviewed",
                  "test_specifications", "external_sources", "clarifications"):
        if not isinstance(plan.get(field, []), list):
            raise QAPlanError(f"QA plan field {field} must be a list.")
    for field in ("rationale", "coverage_review"):
        if not isinstance(plan.get(field), str):
            raise QAPlanError(f"QA plan field {field} must be text.")

    specs = plan.get("test_specifications", [])
    if status == "READY" and not specs and not plan.get("prepared_test_paths"):
        raise QAPlanError("READY requires prepared executable tests or precise test specifications.")
    if status == "NO_TESTS_NEEDED" and not plan["rationale"].strip():
        raise QAPlanError("NO_TESTS_NEEDED requires a justification.")
    if status == "NEEDS_USER" and not plan.get("clarifications"):
        raise QAPlanError("NEEDS_USER requires at least one specific clarification.")

    verification = plan.get("pre_implementation_verification", {})
    if not isinstance(verification, dict):
        raise QAPlanError("pre_implementation_verification must be an object.")
    if verification.get("status") not in ("EXPECTED_FAILURE", "PASS", "NOT_RUN"):
        raise QAPlanError("Pre-implementation test status must distinguish expected failure from not run.")
    if verification.get("status") == "EXPECTED_FAILURE" and not verification.get("failure_evidence"):
        raise QAPlanError("Expected test failure requires evidence that it is behavioral, not a setup failure.")

    prepared_paths = plan.get("prepared_test_paths", [])
    if not isinstance(prepared_paths, list):
        raise QAPlanError("prepared_test_paths must be a list.")
    paths = set()
    owned = {_safe_relative_path(value) for value in owned_test_paths}
    for value in list(prepared_paths) + list(owned):
        if not isinstance(value, str):
            raise QAPlanError("prepared_test_paths entries must be strings.")
        paths.add(_safe_relative_path(value))
    if not paths.issubset(owned):
        raise QAPlanError("prepared_test_paths may only name files created by QA; describe existing tests instead.")
    if status == "NO_TESTS_NEEDED" and owned:
        raise QAPlanError("NO_TESTS_NEEDED cannot create acceptance test files.")
    plan["prepared_test_paths"] = sorted(paths)
    plan["story_id"] = story_id
    plan["schema_version"] = 1
    plan["qa_model"] = config.QA_MODEL
    plan["qa_reasoning_effort"] = config.QA_REASONING_EFFORT
    return plan


def _git_paths() -> set[str]:
    result = subprocess.run(
        ["git", "ls-files", "-co", "--exclude-standard", "-z"],
        cwd=config.REPO_ROOT, check=True, capture_output=True,
    )
    return {item.decode("utf-8", errors="surrogateescape")
            for item in result.stdout.split(b"\0") if item}


def snapshot_files() -> dict[str, bytes]:
    """Snapshot tracked and visible untracked files without touching ignored build trees."""
    snapshot = {}
    for name in _git_paths():
        path = config.REPO_ROOT / name
        try:
            if path.is_file():
                snapshot[name] = path.read_bytes()
        except OSError:
            continue
    return snapshot


def _allowed_new_test(path: str) -> bool:
    try:
        _safe_relative_path(path)
        return True
    except QAPlanError:
        return False


def enforce_test_only_changes(before: dict[str, bytes], after: dict[str, bytes], owned=()) -> tuple[list[str], list[str]]:
    """Restore forbidden writes and return changed/new QA test paths and violations."""
    owned = set(owned)
    before_names, after_names = set(before), set(after)
    changed = {name for name in before_names & after_names if before[name] != after[name]}
    deleted = before_names - after_names
    added = after_names - before_names
    allowed_modified = {name for name in changed if name in owned}
    allowed_added = {name for name in added if _allowed_new_test(name)}
    violations = sorted((changed - allowed_modified) | deleted | (added - allowed_added))

    for name in changed - allowed_modified:
        path = config.REPO_ROOT / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(before[name])
    for name in deleted:
        path = config.REPO_ROOT / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(before[name])
    for name in added - allowed_added:
        (config.REPO_ROOT / name).unlink(missing_ok=True)

    qa_tests = sorted(allowed_added | allowed_modified)
    return qa_tests, violations


def build_qa_prompt(story_path: Path, story_content: str, owned_test_paths=()) -> str:
    existing = ", ".join(sorted(owned_test_paths)) or "None"
    instructions = config.QA_INSTRUCTIONS_FILE.read_text(encoding="utf-8")
    return f"""STORY QA TASK

AUTHORITATIVE QA ROLE CONTRACT
==============================
{instructions}

You are the independent pre-implementation QA agent. Prepare the active story
before its coding agent runs. Read the story, applicable business rules,
`docs/TEST_STRATEGY.md`, `docs/QUALITY_METRICS.md`, relevant architecture and
the existing tests before deciding what verification is needed.

Story path: {story_path.relative_to(config.REPO_ROOT).as_posix()}
Story ID: {story_id_from_content(story_content, story_path.stem)}

Story text:
--- BEGIN STORY ---
{story_content}
--- END STORY ---

QA-owned test files from an interrupted earlier QA session (reuse or finish
these; do not duplicate them): {existing}

Responsibilities:
- Map acceptance criteria, business rules and cross-component invariants to
  executable tests or precise expected-result test specifications.
- Inspect existing tests first and avoid duplication. Prefer tests across
  complete behavior/results where required, not only implementation internals.
- Write acceptance/regression tests before implementation when practical.
  Run prepared tests before implementation and report PASS, EXPECTED_FAILURE,
  or NOT_RUN. For EXPECTED_FAILURE include evidence that it fails on the
  behavior assertion, not because compilation, fixtures or environment broke.
- If requirements are genuinely ambiguous, return NEEDS_USER and formulate a
  concrete Product Owner question. Do not invent product behavior.
- For documentation-only/low-risk work, return NO_TESTS_NEEDED with a specific
  reason. Never add tests only to raise coverage.
- Review coverage in affected modules/packages from existing reports if
  available. Do not invent percentages when unavailable.
- Research trusted external game facts only when the story depends on them.
  Prefer official GW2 API and GW2 Wiki. Record each source URL, verified fact,
  and whether it is an external fact or an application requirement. Tests must
  use stable fixtures, never live API calls.

Write ONLY new test/fixture files under existing test roots. Do not edit or
delete existing files, application code, stories, architecture/docs, or agent
instructions. The harness enforces this boundary. Do not write the persistent
plan yourself; return it in the final JSON so Python can validate and persist it.

Output exactly one fenced JSON object with:
{{
 "status":"READY|NO_TESTS_NEEDED|NEEDS_USER",
 "story_id":"STORY-...",
 "rationale":"...",
 "acceptance_checks":[{{"criterion":"...","expected":"...","verification":"..."}}],
 "invariants":["..."],
 "test_levels":["unit|integration|property|contract|ui|end_to_end"],
 "existing_tests_reviewed":["path and what it covers"],
 "prepared_test_paths":["new paths, including resumed QA-owned files"],
 "test_specifications":["precise test and independently established expected result"],
 "pre_implementation_verification":{{"status":"EXPECTED_FAILURE|PASS|NOT_RUN","command":"...","failure_evidence":"..."}},
 "coverage_review":"...",
 "external_sources":[{{"url":"...","verified_fact":"...","fact_type":"external|product"}}],
 "clarifications":["specific PO question if status is NEEDS_USER"],
 "post_implementation_review_required":true,
 "post_implementation_review_reason":"..."
}}
"""


def implementation_contract(plan: dict) -> str:
    return """

INDEPENDENT QA PLAN — REQUIRED CONTRACT
=======================================
The following plan was prepared before implementation. Treat its acceptance
checks, invariants and QA-owned tests as binding verification requirements.
Do not modify, delete, skip, weaken or rewrite QA-owned tests or this plan.
If a QA expectation appears wrong, preserve it, document the evidence in
CLAUDE_RESULT.md, and request independent review; do not silently change it.
You may add implementation-specific tests. Run required prepared tests and
report their exact command and result. Passing tests alone do not establish
completion; verify the end-to-end behavior and invariants too.

QA plan JSON:
""" + json.dumps(plan, indent=2, ensure_ascii=False) + "\n"


def save_plan(story_path: Path, raw: dict, owned_test_paths=()) -> dict:
    story_content = story_path.read_text(encoding="utf-8")
    story_id = story_id_from_content(story_content, story_path.stem)
    plan = validate_plan(raw, story_id, owned_test_paths)

    if plan["status"] == "NEEDS_USER":
        plan["user_decision_ids"] = [
            create_qa_user_decision(story_id, question, plan.get("rationale", ""))
            for question in plan["clarifications"]
        ]
    else:
        plan["user_decision_ids"] = []

    plan["protected_test_hashes"] = {}
    for rel in plan["prepared_test_paths"]:
        test_path = config.REPO_ROOT / rel
        if not test_path.is_file():
            raise QAPlanError(f"QA listed a test file that does not exist: {rel}")
        plan["protected_test_hashes"][rel] = hashlib.sha256(test_path.read_bytes()).hexdigest()

    config.QA_PLANS_DIR.mkdir(parents=True, exist_ok=True)
    write_json(plan_path(story_id), plan)
    _store_test_snapshots(plan)
    write_json(config.QA_RESULT_FILE, plan)
    return plan


def load_plan(story_path: Path) -> dict | None:
    story_id = story_id_from_content(story_path.read_text(encoding="utf-8"), story_path.stem)
    try:
        plan = json.loads(plan_path(story_id).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    if plan.get("schema_version") != 1 or plan.get("story_id") != story_id:
        return None
    if plan.get("status") not in PLAN_STATUSES:
        return None
    return plan


def plan_tests_unchanged(plan: dict) -> tuple[bool, list[str]]:
    changed = []
    for rel, digest in plan.get("protected_test_hashes", {}).items():
        path = config.REPO_ROOT / rel
        current = hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None
        if current != digest:
            changed.append(rel)
    return not changed, changed


def restore_protected_tests(plan: dict) -> list[str]:
    """Restore QA-owned acceptance tests from the durable pre-code snapshot."""
    changed = []
    root = config.ARTIFACTS_DIR / "qa-tests"
    for rel, digest in plan.get("protected_test_hashes", {}).items():
        target = config.REPO_ROOT / rel
        backup = root / rel
        current = hashlib.sha256(target.read_bytes()).hexdigest() if target.is_file() else None
        if current == digest:
            continue
        changed.append(rel)
        if not backup.is_file():
            raise QAPlanError(f"Missing protected QA-test snapshot for {rel}")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(backup.read_bytes())
    return changed


def _store_test_snapshots(plan: dict) -> None:
    root = config.ARTIFACTS_DIR / "qa-tests"
    for rel in plan.get("prepared_test_paths", []):
        source = config.REPO_ROOT / rel
        target = root / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(source.read_bytes())


def unresolved_plan_decisions(plan: dict) -> list[str]:
    statuses = {item["id"]: item["status"] for item in list_decisions() if item["id"]}
    return [decision_id for decision_id in plan.get("user_decision_ids", [])
            if statuses.get(decision_id) != "RESOLVED"]


def requeue_resolved_qa_stories() -> list[str]:
    """Unblock QA-clarification stories once their Product Owner decision resolves."""
    requeued = []
    if not config.QA_PLANS_DIR.exists():
        return requeued
    for path in sorted(config.QA_PLANS_DIR.glob("QA-STORY-*.json")):
        try:
            plan = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if plan.get("status") != "NEEDS_USER" or not plan.get("user_decision_ids"):
            continue
        if unresolved_plan_decisions(plan):
            continue
        story_id = plan.get("story_id")
        story_path = find_story_file_by_id(story_id, build_story_index())
        if story_path is None:
            continue
        content = story_path.read_text(encoding="utf-8")
        from agent.runtime.core.story_state import classify_story_status, extract_status_section
        if classify_story_status(extract_status_section(content)) != "BLOCKED":
            continue
        if get_unsatisfied_dependencies(content, build_story_index()):
            continue
        clear_story_blocked(story_path)
        backlog = config.BACKLOG_FILE.read_text(encoding="utf-8")
        updated = move_backlog_entry_to_todo(backlog, story_path.name)
        if updated != backlog:
            config.BACKLOG_FILE.write_text(updated, encoding="utf-8")
        pointer = config.CURRENT_STORY_FILE
        if pointer.exists() and pointer.read_text(encoding="utf-8").strip().endswith(story_path.name):
            pointer.write_text("", encoding="utf-8")
        # Keep the question and prior QA record for audit. The next dispatch
        # regenerates the plan because its decision is now resolved.
        requeued.append(story_id)
    return requeued


def make_legacy_plan(story_path: Path) -> dict:
    """Allow a pre-QA interrupted attempt to resume without rerunning coding."""
    sid = story_id_from_content(story_path.read_text(encoding="utf-8"), story_path.stem)
    plan = {
        "schema_version": 1, "story_id": sid, "status": "NO_TESTS_NEEDED",
        "rationale": "This attempt was already in progress before pre-implementation QA was introduced; resume its recorded evaluator/publication phase without repeating completed coding.",
        "acceptance_checks": [], "invariants": [], "test_levels": [],
        "existing_tests_reviewed": [], "prepared_test_paths": [],
        "test_specifications": [], "pre_implementation_verification": {"status": "NOT_RUN"},
        "coverage_review": "Legacy attempt; evaluator will inspect available verification.",
        "external_sources": [], "clarifications": [], "user_decision_ids": [],
        "post_implementation_review_required": True,
        "post_implementation_review_reason": "Legacy attempt has no pre-implementation QA evidence.",
        "legacy_attempt": True,
    }
    config.QA_PLANS_DIR.mkdir(parents=True, exist_ok=True)
    write_json(plan_path(sid), plan)
    return plan


def execute_preparation(story_path: Path) -> dict:
    """One guarded QA run; callers own bounded retry and capacity waiting."""
    story_content = story_path.read_text(encoding="utf-8")
    sid = story_id_from_content(story_content, story_path.stem)
    state = {}
    try:
        state = json.loads(config.QA_STATE_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        pass
    owned = state.get("owned_test_paths", []) if state.get("story_id") == sid else []
    before = snapshot_files()
    messages: list[str] = []
    try:
        code = run_qa(build_qa_prompt(story_path, story_content, owned), messages)
    finally:
        after = snapshot_files()
        qa_tests, violations = enforce_test_only_changes(before, after, owned)
        if qa_tests:
            state = {"schema_version": 1, "story_id": sid,
                     "attempts": int(state.get("attempts", 0)) if state.get("story_id") == sid else 0,
                     "owned_test_paths": sorted(set(owned) | set(qa_tests))}
            write_json(config.QA_STATE_FILE, state)
    if violations:
        raise QAPlanError("QA modified forbidden files; changes were restored: " + ", ".join(violations))
    if code != 0:
        raise QAPlanError(f"QA agent exited with code {code}")
    raw = parse_plan(messages)
    return save_plan(story_path, raw, owned_test_paths=sorted(set(owned) | set(qa_tests)))


def record_attempt(story_path: Path) -> int:
    sid = story_id_from_content(story_path.read_text(encoding="utf-8"), story_path.stem)
    try:
        state = json.loads(config.QA_STATE_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        state = {}
    attempts = int(state.get("attempts", 0)) + 1 if state.get("story_id") == sid else 1
    state.update({"schema_version": 1, "story_id": sid, "attempts": attempts})
    write_json(config.QA_STATE_FILE, state)
    return attempts


def clear_state() -> None:
    config.QA_STATE_FILE.unlink(missing_ok=True)


def run_conditional_review(story_path: Path, plan: dict, evaluation: dict) -> dict:
    """Independent read-only QA check after implementation when requested."""
    prompt = f"""STORY QA POST-IMPLEMENTATION REVIEW

Inspect the repository read-only. Check this story's acceptance criteria and
invariants against the implementation, QA plan and actual tests. Confirm
whether prepared QA tests remain present and meaningful, relevant tests pass
based on available evidence, and any plan deviation is justified. Passing
tests alone do not prove the feature works.

Story: {story_path.relative_to(config.REPO_ROOT).as_posix()}
QA plan:
{json.dumps(plan, indent=2)}
Evaluator result:
{json.dumps(evaluation, indent=2)}

Return exactly one fenced JSON object:
{{"decision":"APPROVE|RETRY|NEEDS_USER","reason":"...","evidence":[...],"actionable_items":[...]}}
"""
    messages: list[str] = []
    code = run_qa(prompt, messages, read_only=True)
    if code != 0:
        raise QAPlanError(f"QA review exited with code {code}")
    raw = parse_plan(messages)
    if raw.get("decision") not in ("APPROVE", "RETRY", "NEEDS_USER"):
        raise QAPlanError("QA review returned an invalid decision.")
    if not isinstance(raw.get("actionable_items", []), list):
        raise QAPlanError("QA review actionable_items must be a list.")
    return raw


def record_review(plan: dict, review: dict) -> None:
    """Persist the independent review alongside its pre-implementation plan."""
    path = plan_path(plan["story_id"])
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        latest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        latest = dict(plan)
    if review.get("decision") == "NEEDS_USER" and plan.get("status") != "NEEDS_USER":
        question = review.get("reason") or "The QA review cannot establish the required expected behavior."
        decision_id = create_qa_user_decision(
            plan["story_id"], question,
            "Post-implementation QA review requires Product Owner input. "
            + str(review.get("reason", "")),
        )
        plan["status"] = "NEEDS_USER"
        plan["clarifications"] = [question]
        plan["user_decision_ids"] = [decision_id]
    for field in ("status", "clarifications", "user_decision_ids"):
        if field in plan:
            latest[field] = plan[field]
    reviews = latest.setdefault("post_implementation_reviews", [])
    reviews.append(review)
    write_json(path, latest)
