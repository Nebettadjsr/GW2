"""Review what a sync brought in from the maintainer branch against the docs that own it.

The maintainer changes code on the maintainer branch without always updating
every document. Merged into the agent branch unreviewed, that divergence
surfaces later in the middle of a planning pass, which then either invents a
disposition or fails over it. So every sync that brings in commits is reviewed
once, in a bounded Codex pass over exactly that range:

* a descriptive document that merely lags an intentional maintainer change is
  updated in place (maintainer changes are authoritative, CLAUDE.md);
* a contradiction with an authoritative document is never decided here -- it
  becomes a user decision (domain/product) or an architect request
  (architecture), and is recorded in KNOWN_PROBLEMS.md;
* a probable defect is reported for the planner to turn into work.

The pass writes only those documents. Anything else it touches, or an invalid
result, rolls the whole pass back -- the same contract as planning.
"""

from __future__ import annotations

import hashlib
import json
import re
import subprocess

from agent.runtime.human import architect_requests, user_decisions
from agent.runtime.runners import local_planner_runner
from agent.runtime.support import git_sync
from agent.runtime.support.config import (
    ARCHITECT_REQUESTS_DIR, ARTIFACTS_DIR, CURRENT_ARCHITECTURE_FILE, DOCS_DIR,
    KNOWN_PROBLEMS_FILE, LOGS_DIR, PENDING_COMMIT_PATHS_FILE, REPO_ROOT, USER_DECISIONS_DIR,
)
from agent.runtime.support.files import read_file, write_json

SYNC_REVIEW_RESULT_FILE = ARTIFACTS_DIR / "SYNC_REVIEW_RESULT.json"
TASK_HEADER = "DOCUMENTATION SYNC REVIEW TASK"
KINDS = ("DOC_UPDATED", "USER_DECISION", "ARCHITECT_REQUEST", "KNOWN_PROBLEM", "BUG_FINDING")

# Descriptive documents a lagging maintainer change may be reconciled into.
# Authoritative ones (DOMAIN_SPEC, TARGET_ARCHITECTURE, ...) are never edited here.
WRITABLE_DOCUMENTS = (
    DOCS_DIR / "CURRENT_STATE_SPEC.md",
    CURRENT_ARCHITECTURE_FILE,
    KNOWN_PROBLEMS_FILE,
)
WRITABLE_DIRECTORIES = (DOCS_DIR / "crafting",)

# Changes that never move documented behavior: dependency bumps.
_DEPENDENCY_ONLY = re.compile(r"(^|/)(package-lock\.json|pom\.xml)$")


def needs_review(changed_paths: list[str]) -> bool:
    return any(not _DEPENDENCY_ONLY.search(path) for path in changed_paths)


def _relative(path) -> str:
    return path.relative_to(REPO_ROOT).as_posix()


def _writable(relative: str) -> bool:
    path = REPO_ROOT / relative
    return path in WRITABLE_DOCUMENTS or any(directory in path.parents for directory in WRITABLE_DIRECTORIES)


def _snapshot() -> dict[str, str | None]:
    """Content hash of every file git reports as changed, plus every doc."""
    ignored = (_relative(ARTIFACTS_DIR) + "/", _relative(LOGS_DIR) + "/")
    paths = set(git_sync.working_tree_paths())
    paths |= {_relative(path) for path in DOCS_DIR.rglob("*.md")}
    paths |= {_relative(path) for directory in (USER_DECISIONS_DIR, ARCHITECT_REQUESTS_DIR)
              for path in directory.glob("*.md")}
    snapshot = {}
    for relative in paths:
        if relative.startswith(ignored):
            continue
        path = REPO_ROOT / relative
        snapshot[relative] = hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None
    return snapshot


def _git(*arguments: str) -> str:
    return subprocess.run(["git", *arguments], cwd=REPO_ROOT, text=True, encoding="utf-8",
                          errors="replace", capture_output=True, check=False).stdout.strip()


def _documentation_map() -> str:
    match = re.search(r"^## Documentation Map\n(.*?)(?=^## )", read_file(REPO_ROOT / "CLAUDE.md"),
                      re.MULTILINE | re.DOTALL)
    return match.group(1).strip() if match else "(see CLAUDE.md, Documentation Map)"


def build_prompt(base: str, head: str, rejected: list[str] | None = None) -> str:
    commits = _git("log", "--no-merges", "--format=%h %an: %s", f"{base}..{head}") or "(merge only)"
    stat = _git("diff", "--stat=160", base, head)
    writable = ", ".join([_relative(path) for path in WRITABLE_DOCUMENTS]
                         + [_relative(path) + "/" for path in WRITABLE_DIRECTORIES])
    retry = ""
    if rejected:
        retry = ("\nYOUR PREVIOUS ATTEMPT WAS REJECTED AND ROLLED BACK. Avoid these problems:\n"
                 + "".join(f"- {problem}\n" for problem in rejected))
    return f"""The maintainer branch was just merged into the pipeline's working branch.
Review ONLY what that merge brought in ({base[:12]}..{head[:12]}) against the
documents that own the affected behavior. Maintainer changes are intentional
and authoritative (CLAUDE.md, "Human maintainer changes"): never propose to
revert them, and never review anything outside this range.

COMMITS
{commits}

CHANGED FILES
{stat}

Inspect a change with `git diff {base[:12]} {head[:12]} -- <path>` and read only
the document sections it affects.

DOCUMENTATION MAP (which file owns what)
{_documentation_map()}

For every MATERIAL divergence between the merged code and its owning
documents, choose exactly one disposition:
- DOC_UPDATED: a descriptive document merely lags the intended change. Update
  it minimally and surgically. You may edit only: {writable}.
- USER_DECISION: the change contradicts a domain or product rule in
  docs/DOMAIN_SPEC.md (or another authoritative product document). Do not
  decide which side is right. Create agent/user-decisions/UD-<next free
  number>-<slug>.md with these `##` headings in order:
  {", ".join(user_decisions.REQUIRED_DECISION_HEADINGS)}; Status OPEN, User
  Decision and Resolution TODO. Ask whether the specification is outdated or
  the code is wrong, citing both.
- ARCHITECT_REQUEST: the change contradicts docs/TARGET_ARCHITECTURE.md or an
  ADR. Create agent/architect-requests/AR-<next free number>-<slug>.md starting
  with the title line `# AR-<number> - <title>` and these `##` headings in
  order: {", ".join(architect_requests.REQUIRED_REQUEST_HEADINGS)}; Status OPEN,
  Architect Decision and Resolution TODO.
- KNOWN_PROBLEM: a real, bounded risk or debt with no decision needed; record
  it in docs/KNOWN_PROBLEMS.md following that file's rules.
- BUG_FINDING: the merged code probably violates its own specification. Report
  it with evidence only; the planner decides what work follows.
Contradictions with an authoritative document are also recorded in
docs/KNOWN_PROBLEMS.md. Cosmetic or wording differences are not material.

Never edit code, tests, docs/DOMAIN_SPEC.md, docs/TARGET_ARCHITECTURE.md, ADRs,
stories, BACKLOG.md, ROADMAP.md, PROJECT_STATE.md, or an existing user
decision or architect request. A pass that does is rolled back entirely.

Finish by writing agent/runtime/artifacts/SYNC_REVIEW_RESULT.json:
{{
  "status": "COMPLETE" | "FAILED",
  "range": "{base[:12]}..{head[:12]}",
  "findings": [
    {{"kind": "{'" | "'.join(KINDS)}",
      "summary": "<one sentence>",
      "evidence": ["<path or path:line>"],
      "artifacts": ["<file you changed or created, empty for BUG_FINDING>"]}}
  ],
  "reason": "<short summary; say so explicitly when nothing material diverges>"
}}
{retry}"""


def _validate(result, before: dict, after: dict, decisions_before: set, requests_before: dict) -> list[str]:
    problems = []
    if not isinstance(result, dict) or result.get("status") not in ("COMPLETE", "FAILED"):
        return ["SYNC_REVIEW_RESULT.json must be an object with status COMPLETE or FAILED."]
    findings = result.get("findings")
    if not isinstance(findings, list) or not all(isinstance(item, dict) for item in findings):
        return ["findings must be a list of objects."]
    changed = {path for path in before.keys() | after.keys() if before.get(path) != after.get(path)}
    new_decisions = {path for path in changed if path.startswith(_relative(USER_DECISIONS_DIR) + "/")}
    new_requests = {path for path in changed if path.startswith(_relative(ARCHITECT_REQUESTS_DIR) + "/")}
    for path in sorted(changed - new_decisions - new_requests):
        if not _writable(path):
            problems.append(f"Not writable by the sync review: {path}")
    for path in sorted(new_decisions):
        name = path.rsplit("/", 1)[-1]
        if name in decisions_before:
            problems.append(f"Existing user decision changed: {path}")
            continue
        content = read_file(REPO_ROOT / path)
        missing = user_decisions.missing_required_headings(content)
        if missing:
            problems.append(f"{path} is missing headings: {', '.join(missing)}")
        if (user_decisions.extract_status(content) or "").upper() != "OPEN":
            problems.append(f"{path} must have Status OPEN.")
    if user_decisions.find_duplicate_decision_ids():
        problems.append("Duplicate user-decision IDs: " + ", ".join(user_decisions.find_duplicate_decision_ids()))
    created_requests = sorted(path.rsplit("/", 1)[-1] for path in new_requests)
    problems.extend(architect_requests.validate_planner_updates(requests_before, created_requests, "COMPLETE"))
    for item in findings:
        if item.get("kind") not in KINDS:
            problems.append(f"Invalid finding kind: {item.get('kind')!r}")
        elif item["kind"] != "BUG_FINDING" and not item.get("artifacts"):
            problems.append(f"A {item['kind']} finding must name the artifact it changed or created.")
        if not str(item.get("summary") or "").strip():
            problems.append("Every finding needs a summary.")
    return problems


def run_sync_review(base: str, head: str, rejected: list[str] | None = None) -> dict:
    """One bounded review pass; returns the validated result, FAILED ones rolled back."""
    before = _snapshot()
    originals = {path: (REPO_ROOT / path).read_bytes() for path in before if (REPO_ROOT / path).is_file()}
    decisions_before = {path.name for path in user_decisions.list_decision_files()}
    requests_before = {request["file"]: request["content"] for request in architect_requests.list_requests()}
    SYNC_REVIEW_RESULT_FILE.unlink(missing_ok=True)

    code = local_planner_runner.run_codex(build_prompt(base, head, rejected), task_header=TASK_HEADER,
                                          label="Codex documentation sync review")
    try:
        result = json.loads(SYNC_REVIEW_RESULT_FILE.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        result = None
        problems = [f"No readable SYNC_REVIEW_RESULT.json ({error})."]
    else:
        problems = []
    after = _snapshot()
    if result is not None:
        problems = _validate(result, before, after, decisions_before, requests_before)
    if code != 0:
        problems.append(f"Codex exited with code {code}.")

    changed = sorted(path for path in before.keys() | after.keys() if before.get(path) != after.get(path))
    if problems or result.get("status") == "FAILED":
        for path in changed:  # roll the whole pass back
            target = REPO_ROOT / path
            if path in originals:
                target.write_bytes(originals[path])
            else:
                target.unlink(missing_ok=True)
        failed = {"status": "FAILED", "range": f"{base[:12]}..{head[:12]}",
                  "findings": [], "validation_problems": problems,
                  "reason": (result or {}).get("reason") or "; ".join(problems)}
        write_json(SYNC_REVIEW_RESULT_FILE, failed)
        return failed

    try:
        pending = set(json.loads(PENDING_COMMIT_PATHS_FILE.read_text(encoding="utf-8")).get("paths", []))
    except (OSError, ValueError, AttributeError):
        pending = set()
    if changed:  # committed with the next published story, like planning output
        write_json(PENDING_COMMIT_PATHS_FILE, {"paths": sorted(pending | set(changed))})
    result["changed_paths"] = changed
    write_json(SYNC_REVIEW_RESULT_FILE, result)
    return result


def planner_notes(result: dict) -> str:
    """The review's findings as a planning input, so they are dispositioned once."""
    lines = [f"- {item['kind']}: {item['summary']}"
             + (f" ({', '.join(item.get('artifacts') or item.get('evidence') or [])})"
                if item.get("artifacts") or item.get("evidence") else "")
             for item in result.get("findings", [])]
    return (f"Maintainer commits {result.get('range')} were merged and reviewed against their documents. "
            f"{result.get('reason', '')}\n" + "\n".join(lines))
