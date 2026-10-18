"""Structured QA preparation for one active story."""

import hashlib
import json
import re
import subprocess
from pathlib import Path

from agent.runtime.runners.local_planner_runner import run_qa
from agent.runtime.support import config
from agent.runtime.support.capacity import ModelCapacityUnavailable
from agent.runtime.support.daily_log import defer_log_writes
from agent.runtime.support.files import write_json
from agent.runtime.human.user_decisions import create_qa_user_decision
from agent.runtime.human.user_decisions import list_decisions
from agent.runtime.core.story_state import (
    build_story_index,
    clear_story_blocked,
    extract_story_id,
    find_story_file_by_id,
    get_unsatisfied_dependencies,
    move_backlog_entry_to_todo,
)


class QAPlanError(RuntimeError):
    pass


class QAInfrastructureError(RuntimeError):
    """The harness could not safely execute or guard QA preparation."""


class QAFileProtectionError(QAInfrastructureError):
    """QA changed forbidden files or a safe restoration was not possible."""


class QARunnerError(QAInfrastructureError):
    """The QA model runner failed independently of plan validation."""


PLAN_STATUSES = ("READY", "NO_TESTS_NEEDED", "NEEDS_USER")
TEST_ROOTS = (
    "src/test/", "frontend/src/", "frontend/scripts/",
    "docs/bugs/fixtures/",
)
FENCED_JSON = re.compile(r"```(?:json)?\s*(\{.*?\})\s*```", re.DOTALL)


_NON_CONTRACT_STORY_SECTIONS = {
    "status", "result", "blockers", "follow-up findings",
    "follow-up findings disposition",
}


def story_contract_sha256(story_content: str) -> str:
    """Fingerprint requirements while ignoring harness-owned execution notes."""
    kept = []
    skip = False
    for line in story_content.splitlines():
        if line.startswith("## "):
            skip = line[3:].strip().casefold() in _NON_CONTRACT_STORY_SECTIONS
        if not skip:
            kept.append(line)
    stable = "\n".join(kept).strip()
    return hashlib.sha256(stable.encode("utf-8")).hexdigest()


def story_id_from_content(content: str, fallback: str = "") -> str:
    canonical_id = extract_story_id(content)
    if canonical_id:
        return canonical_id.upper()
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
        raise QAPlanError(f"QA may only write tests or fixtures, got {normalized!r}")
    valid_test_source = (
        normalized.startswith("src/test/")
    ) or (
        normalized.startswith(("frontend/src/", "frontend/scripts/"))
        and normalized.endswith((".spec.ts", ".spec.mjs"))
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
    supplied_story_id = plan.get("story_id")
    # Models occasionally omit the conventional `STORY-` prefix while still
    # identifying the exact dispatched story (for example `WEB-027`). This
    # deterministic alias is unambiguous because it is derived from the one
    # canonical story ID supplied by the harness. No fuzzy/substring match is
    # accepted, so a plan for a different story remains a hard validation
    # failure.
    accepted_story_ids = {None, story_id}
    if story_id.startswith("STORY-"):
        accepted_story_ids.add(story_id[len("STORY-"):])
    if supplied_story_id not in accepted_story_ids:
        raise QAPlanError("QA plan references a different story.")

    for field in ("acceptance_checks", "invariants", "test_levels", "existing_tests_reviewed",
                  "test_specifications", "external_sources", "clarifications", "interface_contract"):
        if not isinstance(plan.get(field, []), list):
            raise QAPlanError(f"QA plan field {field} must be a list.")
    for field in ("rationale", "coverage_review"):
        if not isinstance(plan.get(field), str):
            raise QAPlanError(f"QA plan field {field} must be text.")

    specs = plan.get("test_specifications", [])
    if status == "READY" and not specs and not plan.get("prepared_test_paths"):
        raise QAPlanError("READY requires QA-prepared tests or specified checks against existing tests.")
    if (status == "READY" and not plan.get("prepared_test_paths")
            and not owned_test_paths and not plan.get("existing_tests_reviewed")):
        raise QAPlanError("READY requires QA-prepared tests or identified existing tests; specifications cannot delegate test authorship to coding.")
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
        raise QAPlanError("prepared_test_paths may only name files created or updated by QA; describe unchanged existing tests instead.")
    if status == "NO_TESTS_NEEDED" and owned:
        raise QAPlanError("NO_TESTS_NEEDED cannot create acceptance test files.")
    plan["prepared_test_paths"] = sorted(paths)
    plan["story_id"] = story_id
    plan["schema_version"] = 1
    plan["qa_model"] = config.QA_MODEL
    plan["qa_reasoning_effort"] = config.QA_REASONING_EFFORT
    return plan


INTERFACE_DEVIATIONS_HEADING = "## Interface Deviations"


def parse_interface_deviations(result_text: str) -> list[str]:
    """Deviations the coder reported between QA's interface contract and what it built."""
    lines, inside = [], False
    for line in result_text.splitlines():
        if line.strip().startswith("#"):
            inside = line.strip().lower() == INTERFACE_DEVIATIONS_HEADING.lower()
            continue
        item = line.strip().lstrip("-*").strip()
        if inside and item and item.lower().rstrip(".") not in ("none", "n/a", "keine"):
            lines.append(item)
    return lines


def snapshot_test_files() -> dict[str, bytes]:
    """Bytes of every QA-writable test file, to detect writes by the coding agent."""
    snapshot = {}
    for name in _git_paths():
        path = config.REPO_ROOT / name
        if _allowed_new_test(name) and path.is_file():
            try:
                snapshot[name] = path.read_bytes()
            except OSError:
                continue
    return snapshot


def revert_test_changes(before: dict[str, bytes]) -> list[str]:
    """Undo test-file writes made since ``before``; return the affected paths."""
    after = snapshot_test_files()
    touched = []
    for name in sorted(set(before) | set(after)):
        if before.get(name) == after.get(name):
            continue
        touched.append(name)
        path = config.REPO_ROOT / name
        if name in before:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(before[name])
        else:
            path.unlink(missing_ok=True)
    return touched


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
    before_names, after_names = set(before), set(after)
    changed = {name for name in before_names & after_names if before[name] != after[name]}
    deleted = before_names - after_names
    added = after_names - before_names
    allowed_modified = {name for name in changed if _allowed_new_test(name)}
    allowed_added = {name for name in added if _allowed_new_test(name)}
    violations = sorted((changed - allowed_modified) | deleted | (added - allowed_added))

    for name in changed - allowed_modified:
        path = config.REPO_ROOT / name
        current = path.read_bytes() if path.is_file() else None
        if current != after[name]:
            violations.append(f"{name} (preserved newer concurrent change; not restored)")
            continue
        path.write_bytes(before[name])
    for name in deleted:
        path = config.REPO_ROOT / name
        if path.exists():
            violations.append(f"{name} (preserved recreated file; not overwritten)")
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(before[name])
    for name in added - allowed_added:
        path = config.REPO_ROOT / name
        if not path.exists():
            continue
        current = path.read_bytes() if path.is_file() else None
        if current != after[name]:
            violations.append(f"{name} (preserved newer concurrent change; not deleted)")
            continue
        path.unlink()

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

Approved test/fixture roots: {", ".join(TEST_ROOTS)}.
The dedicated src/test tree includes test helpers and resources. Colocated
frontend tests use .spec.ts/.spec.mjs. The automated flow develops the application
only: never write tests for the workflow, agent runtime or orchestration code.

Story text:
--- BEGIN STORY ---
{story_content}
--- END STORY ---

QA-owned test files from an interrupted earlier QA session (reuse or finish
these; do not duplicate them): {existing}

Responsibilities:
- Fix an interface contract: the exact class, method, signature, DTO field,
  route and data-test hook names your tests use (`interface_contract`). The
  coder must use them verbatim; the tests are written before the production
  code exists.
- Own all test design and authorship, including implementation-specific and
  infrastructure tests of the application. Map the story's requirements to appropriate
  executable tests, reusing sufficient existing checks.
- Inspect existing tests first and avoid duplication. Prefer tests across
  complete behavior/results where required, not only implementation internals.
- Create or update all required tests before implementation. Do not delegate
  test writing through specifications to the coding agent. If a missing production
  interface prevents compilation or execution, record that limitation accurately.
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

Write ONLY test/fixture files under the approved test roots, including updates
to existing tests. Do not delete tests or edit application code, stories,
architecture/docs, or agent instructions. Test changes must follow requirements,
not merely make an implementation pass. The harness enforces the file boundary.
Mark READY only with required tests prepared or sufficient existing tests identified;
unfinished test authorship is not a coding-agent task. Do not write the persistent
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
 "interface_contract":["exact names/signatures/routes/hooks the prepared tests rely on"],
 "prepared_test_paths":["created or updated paths, including resumed QA-owned files"],
 "test_specifications":["precise prepared/existing or manual check and independently established expected result; no test-writing delegation"],
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
QA alone designs, writes and maintains ALL tests and fixtures. Do not create,
modify, delete, skip, weaken or rewrite tests, fixtures or this plan, including
implementation-specific tests.
If a QA expectation appears wrong, preserve it, document the evidence in
CLAUDE_RESULT.md, and request independent review; do not silently change it.
Implement production behavior to satisfy the QA-defined story exit criteria.
Use every name in the plan's interface_contract verbatim. If one cannot be used
(the design genuinely needs another name or signature), implement your design,
stop, and list each mismatch ("test expects X, production offers Y") as bullets
under a `## Interface Deviations` heading in CLAUDE_RESULT.md. Do not touch the
tests: Python hands the deviation to QA to align names, imports and setup. Any
test file you change is reverted by Python.
Run the required tests and report exact commands and results. Missing or faulty
tests and expectation changes belong to QA, including after a CI failure;
report the evidence instead of writing or repairing tests yourself.
Passing tests alone do not establish
completion; verify the end-to-end behavior and invariants too.

QA plan JSON:
""" + json.dumps(
        {key: value for key, value in plan.items() if key != "post_implementation_reviews"},
        indent=2, ensure_ascii=False,
    ) + "\n"


def save_plan(story_path: Path, raw: dict, owned_test_paths=()) -> dict:
    story_content = story_path.read_text(encoding="utf-8")
    story_id = story_id_from_content(story_content, story_path.stem)
    plan = validate_plan(raw, story_id, owned_test_paths)
    plan["story_contract_sha256"] = story_contract_sha256(story_content)

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


def execute_preparation(story_path: Path, correction_feedback: str = "", *, test_repair: dict | None = None) -> dict:
    """One guarded QA run; callers own bounded retry and capacity waiting."""
    story_content = story_path.read_text(encoding="utf-8")
    sid = story_id_from_content(story_content, story_path.stem)
    previous_plan = load_plan(story_path) if test_repair else None
    if (test_repair and previous_plan
            and previous_plan.get("test_repair", {}).get("request_id") == test_repair["request_id"]
            and previous_plan.get("status") in ("READY", "NEEDS_USER")
            and plan_tests_unchanged(previous_plan)[0]):
        return previous_plan
    state = {}
    try:
        state = json.loads(config.QA_STATE_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        pass
    owned = state.get("owned_test_paths", []) if state.get("story_id") == sid else []
    if test_repair and previous_plan:
        owned = sorted(set(owned) | set(previous_plan.get("prepared_test_paths", [])))
    try:
        before = snapshot_files()
    except Exception as exc:
        raise QAInfrastructureError(
            f"Could not snapshot the repository before QA: {type(exc).__name__}: {exc}"
        ) from exc
    messages: list[str] = []
    qa_tests = []
    violations = []
    runner_error = None
    code = None
    prompt = build_qa_prompt(story_path, story_content, owned)
    if test_repair and test_repair.get("source") == "interface_deviation":
        prompt += (
            "\n\nINTERFACE ALIGNMENT ONLY: the coder reported that the implemented interface differs "
            "from your contract. Adapt only names, signatures, imports, setup and fixtures of the "
            "affected tests to the implemented interface. Do not change expected values or the meaning "
            "of any assertion; if the deviation changes behavior, say so in the plan instead. "
            "Update interface_contract to the final names.\n"
        )
    if test_repair:
        prompt += (
            "\n\nQA APPLICATION TEST REPAIR — THE IMPLEMENTATION ALREADY EXISTS\n"
            "An independent read-only QA review, not the coder, approved this test work.\n"
            "Review the requirements and evidence again. Correct only application tests/fixtures\n"
            "needed for the findings below; preserve all production code.\n"
            "Never weaken expectations merely to accept the implementation. Record the\n"
            "requirement/evidence for each correction and run the relevant focused checks.\n"
            "Return the complete updated plan, preserving previously prepared tests.\n"
            "If the requirements need a human decision, return NEEDS_USER.\n"
            "The earlier pre-implementation timing instructions do not apply: check the current\n"
            "implementation now. The full CI result for a new commit is checked after publication.\n"
            + json.dumps({"approved_request": test_repair, "previous_plan": previous_plan}, ensure_ascii=False)
        )
    if correction_feedback:
        prompt += (
            "\n\nCORRECTION REQUIRED FROM THE PREVIOUS INVALID QA PLAN:\n"
            + correction_feedback
            + "\nReturn a corrected plan. Do not repeat the invalid field, output, or path.\n"
        )
    with defer_log_writes():
        try:
            try:
                code = run_qa(prompt, messages)
            except ModelCapacityUnavailable:
                raise
            except Exception as exc:
                runner_error = exc
        finally:
            try:
                after = snapshot_files()
                qa_tests, violations = enforce_test_only_changes(before, after, owned)
            except Exception as exc:
                raise QAInfrastructureError(
                    f"Could not verify or safely restore QA workspace changes: {type(exc).__name__}: {exc}"
                ) from exc
            if qa_tests:
                state = {"schema_version": 1, "story_id": sid,
                         "attempts": int(state.get("attempts", 0)) if state.get("story_id") == sid else 0,
                         "owned_test_paths": sorted(set(owned) | set(qa_tests))}
                write_json(config.QA_STATE_FILE, state)
    if violations:
        raise QAFileProtectionError("QA modified forbidden files: " + ", ".join(violations))
    if runner_error is not None:
        raise QARunnerError(
            f"QA model runner failed: {type(runner_error).__name__}: {runner_error}"
        ) from runner_error
    if code != 0:
        raise QARunnerError(f"QA agent exited with code {code}")
    raw = parse_plan(messages)
    if test_repair:
        if raw.get("status") not in ("READY", "NEEDS_USER"):
            raise QAPlanError("A test repair must produce READY or NEEDS_USER, not discard required tests.")
        raw["test_repair"] = test_repair
        raw["post_implementation_reviews"] = (previous_plan or {}).get("post_implementation_reviews", [])
        raw["post_implementation_review_required"] = True
        raw["post_implementation_review_reason"] = "Independently recheck the QA test corrections against the implementation."
    return save_plan(story_path, raw, owned_test_paths=sorted(set(owned) | set(qa_tests)))


def execute_test_repair(story_path: Path, request: dict, correction_feedback: str = "") -> dict:
    """One guarded application-test correction; the cycle persists and bounds retries."""
    review = request.get("review", {})
    if (review.get("decision") != "REVISE_TESTS" or not review.get("evidence")
            or not review.get("actionable_items") or not request.get("request_id")):
        raise QAPlanError("Application test repair requires an evidenced independent QA decision.")
    return execute_preparation(story_path, correction_feedback, test_repair=request)


def record_failure(story_path: Path, category: str, message: str, *, model_retries: int) -> Path:
    """Persist actionable technical QA failure without creating a PO decision."""
    content = story_path.read_text(encoding="utf-8")
    sid = story_id_from_content(content, story_path.stem)
    target = config.QA_FAILURES_DIR / f"QA-{sid}.json"
    recovery = {
        "invalid_qa_output": "Inspect the error and QA transcript, correct the invalid plan, then manually requeue the blocked story.",
        "file_protection_violation": "Inspect the named paths and preserved changes, fix the QA workspace violation, then manually requeue the blocked story.",
        "qa_runner_failure": "Check Codex CLI availability and the recorded runner error, then manually requeue the blocked story.",
        "qa_infrastructure_failure": "Repair the repository snapshot or restoration failure without discarding preserved work, then manually requeue the blocked story.",
    }.get(category, "Inspect the QA failure and repair its technical cause before manually requeuing the story.")
    config.QA_FAILURES_DIR.mkdir(parents=True, exist_ok=True)
    write_json(target, {
        "schema_version": 1,
        "story_id": sid,
        "status": "TECHNICAL_FAILURE",
        "category": category,
        "message": message,
        "model_retries": model_retries,
        "recovery_action": recovery,
    })
    return target


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


def run_conditional_review(story_path: Path, plan: dict, evaluation: dict | None = None) -> dict:
    """Independent read-only QA check after implementation when requested."""
    evaluation_context = (
        "No evaluator result was provided; review this implementation independently."
        if evaluation is None else json.dumps(evaluation, indent=2)
    )
    ci_failure = bool(evaluation and evaluation.get("source") == "ci_failure")
    gate_context = (
        "GitHub CI has failed for the published commit shown below. Inspect the failing\n"
        "application tests, fixtures, requirements and production code. A red test does not\n"
        "establish that the test is wrong. Distinguish valid tests exposing production defects\n"
        "from tests whose expectations, setup or coverage need correction. Workflow-related\n"
        "tests are outside this review. APPROVE cannot turn the failed commit green.\n"
        if ci_failure else
        "The harness publishes and checks GitHub CI only after this review and evaluation.\n"
        "Do not require that future CI result as a pre-acceptance condition.\n"
    )
    prompt = f"""STORY QA POST-IMPLEMENTATION REVIEW

Inspect the repository read-only. Check this story's acceptance criteria and
invariants against the implementation, QA plan and actual tests. Confirm
whether prepared QA tests remain present and meaningful, relevant tests pass
based on available evidence, and any plan deviation is justified. Passing
tests alone do not prove the feature works.

{gate_context}
Checks the implementation reports running locally (live smoke
runs, outputs not stored in the repository) are evidence to weigh. If a claim
you cannot verify matters, return RETRY with a concrete actionable item, not
NEEDS_USER. NEEDS_USER is only for a product decision the story leaves open.

If the plan's test_repair.source is "interface_deviation", additionally verify that the
tests only had names, imports or setup aligned and that no expected value or assertion
meaning changed; otherwise return REVISE_TESTS with that evidence.
You alone decide whether application tests are logically correct. The coder's
claim is evidence to investigate, never authorization to change a test.
Return RETRY with concrete production-code deficiencies when the tests are valid.
Return REVISE_TESTS only when requirements/evidence establish a missing or faulty
application test/fixture. Supply evidence and specific test corrections in actionable_items.
Do not mix production-code instructions into REVISE_TESTS: production is evaluated again
after test corrections. REVISE_TESTS schedules a separate guarded, writing QA invocation;
this review remains read-only.

Story: {story_path.relative_to(config.REPO_ROOT).as_posix()}
QA plan:
{json.dumps(plan, indent=2)}
Evaluator result or review context:
{evaluation_context}

Return exactly one fenced JSON object:
{{"decision":"APPROVE|RETRY|REVISE_TESTS|NEEDS_USER","reason":"...","evidence":[...],"actionable_items":[...]}}
"""
    messages: list[str] = []
    code = run_qa(prompt, messages, read_only=True)
    if code != 0:
        raise QAPlanError(f"QA review exited with code {code}")
    raw = parse_plan(messages)
    if raw.get("decision") not in ("APPROVE", "RETRY", "REVISE_TESTS", "NEEDS_USER"):
        raise QAPlanError("QA review returned an invalid decision.")
    if not isinstance(raw.get("reason"), str):
        raise QAPlanError("QA review reason must be text.")
    if not isinstance(raw.get("evidence", []), list):
        raise QAPlanError("QA review evidence must be a list.")
    if not isinstance(raw.get("actionable_items", []), list):
        raise QAPlanError("QA review actionable_items must be a list.")
    if raw["decision"] == "REVISE_TESTS" and (
            not any(isinstance(item, str) and item.strip() for item in raw.get("evidence", []))
            or not any(isinstance(item, str) and item.strip() for item in raw.get("actionable_items", []))):
        raise QAPlanError("REVISE_TESTS requires evidence and specific application-test corrections.")
    return raw


def record_review(plan: dict, review: dict, request_id: str | None = None) -> None:
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
    if request_id:
        review = {**review, "request_id": request_id}
        if any(item.get("request_id") == request_id for item in reviews if isinstance(item, dict)):
            return
    reviews.append(review)
    write_json(path, latest)
