"""Readiness and execution for implementation-scoped Git publication."""

from __future__ import annotations

import hashlib
import json
import re
from pathlib import PurePosixPath

from agent.runtime.core import story_state
from agent.runtime.support import git_sync
from agent.runtime.support.config import REPO_ROOT
from local_bridge.evaluation import inspect_active_evaluation, persisted_evaluation


PUBLICATION_TASK_TYPE = "implementation_publication"

# One-time human-authorized adoption for the pre-metadata WEB-028 publication.
_WEB028_LEGACY_TASK_ID = "7ae6c8a5-a6c2-446f-a71f-63918933d3e1"
_WEB028_LEGACY_COMMIT = "23a6d90aefcb4235ab76f7138e7b257dc1d0b327"
_WEB028_LEGACY_PATHS = frozenset({
    "frontend/scripts/discovery-browser-smoke.mjs",
    "frontend/scripts/profit-browser-smoke.mjs",
    "frontend/scripts/profit-live-smoke.mjs",
    "frontend/src/account/BankScreen.vue",
    "frontend/src/account/MaterialsScreen.vue",
    "frontend/src/account/__tests__/AccountPageHeaderContract.spec.ts",
    "frontend/src/crafting/CraftingResolution.vue",
    "frontend/src/crafting/ResolutionTreeNode.vue",
    "frontend/src/crafting/SelectedDiscoveryDetail.vue",
    "frontend/src/crafting/SelectedResultDetail.vue",
    "frontend/src/crafting/__tests__/CraftingResolution.spec.ts",
})


def capture_implementation_baseline(task_id: str, story_id: str, contract: dict) -> dict:
    """Capture a publishable, remote-synchronized baseline before the runner."""
    head = git_sync.head_sha()
    branch = git_sync.current_branch()
    if not branch:
        raise RuntimeError("Implementation requires a named branch with a published origin branch tip.")
    remote = git_sync.run_git("ls-remote", "origin", f"refs/heads/{branch}", check=False)
    if remote.returncode != 0 or not remote.stdout.split():
        raise RuntimeError(f"Could not verify origin/{branch} before implementation started.")
    remote_head = remote.stdout.split()[0]
    if remote_head != head:
        raise RuntimeError(
            f"Local HEAD {head} differs from origin/{branch} {remote_head}; synchronize the branch before implementation."
        )
    status = git_sync.run_git("status", "--porcelain", "-z", "--untracked-files=all").stdout
    paths = git_sync.working_tree_paths()
    entries = {}
    for path in sorted(paths):
        full = REPO_ROOT / path
        try:
            content_hash = _sha(full.read_bytes()) if full.is_file() else None
        except OSError:
            content_hash = None
        entries[path] = {"content_sha256": content_hash}
    status_entries = []
    status_by_path = {}
    records = status.split("\0")
    for record in records:
        if len(record) >= 4:
            status_entries.append({"xy": record[:2], "path": record[3:].replace("\\", "/")})
            status_by_path[record[3:].replace("\\", "/")] = record[:2]
    story = contract.get("story") or {}
    from agent.runtime.qa import qa_agent
    from agent.runtime.evaluation.implementation_contract import _story_document_references
    try:
        active_path = story_state.get_active_story_path()
        content = active_path.read_text(encoding="utf-8")
        plan = qa_agent.load_plan(active_path) or {}
        story_path = active_path.relative_to(REPO_ROOT).as_posix()
    except (OSError, ValueError, RuntimeError):
        content, plan, story_path = "", {}, None
    references = _story_document_references(content) if content else []
    qa_plan_path = qa_agent.plan_path(story_id)
    try:
        qa_plan_relative = qa_plan_path.relative_to(REPO_ROOT).as_posix()
    except ValueError:
        qa_plan_relative = None
    return {
        "schema_version": 2, "task_id": task_id, "story_id": story_id,
        "baseline_head_sha": head,
        "baseline_branch": branch,
        "baseline_remote_head_sha": remote_head,
        "baseline_status_sha256": _sha(status),
        "baseline_paths": sorted(paths), "baseline_entries": entries,
        "baseline_status_entries": status_entries,
        "baseline_status_by_path": status_by_path,
        "baseline_index_diff_sha256": _sha(git_sync.run_git("diff", "--cached", "--binary").stdout),
        "baseline_worktree_diff_sha256": _sha(git_sync.run_git("diff", "HEAD", "--binary").stdout),
        "authorized_scope": {
            "story_path": story_path or story.get("filename"),
            "referenced_documents": references,
            "protected_test_paths": sorted(plan.get("prepared_test_paths", [])),
            "protected_test_hashes": dict(plan.get("protected_test_hashes", {})),
            "qa_plan_path": qa_plan_relative,
            "implementation_contract_sha256": contract.get("contract_sha256"),
            "policy": "task-delta-of-baseline-clean-paths; excludes harness-owned lifecycle, QA, bridge and operational paths",
        },
    }


def finalize_implementation_metadata(metadata: dict, protected_paths: list[str], lifecycle_paths: list[str]) -> dict:
    """Persist deterministic baseline-relative changes and safeguard restorations."""
    changed = git_sync.working_tree_paths()
    baseline_paths = set(metadata.get("baseline_paths", []))
    entries = metadata.get("baseline_entries", {})
    delta = []
    ambiguous = []
    hashes = {}
    for path in sorted(changed | baseline_paths):
        full = REPO_ROOT / path
        try:
            current_hash = _sha(full.read_bytes()) if full.is_file() else None
        except OSError:
            current_hash = None
        before = entries.get(path, {}).get("content_sha256")
        if path not in baseline_paths or current_hash != before:
            delta.append(path)
            hashes[path] = current_hash
            if path in baseline_paths:
                ambiguous.append(path)
    metadata.update(
        changed_paths=sorted(set(delta) - set(ambiguous)),
        changed_path_sha256={path: digest for path, digest in sorted(hashes.items())
                             if path not in ambiguous},
        ambiguous_baseline_paths=sorted(ambiguous),
        protected_paths_restored=sorted(set(protected_paths)),
        lifecycle_paths_restored=sorted(set(lifecycle_paths)),
        final_head_sha=git_sync.head_sha(),
        final_status_sha256=_sha(git_sync.run_git("status", "--porcelain", "-z", "--untracked-files=all").stdout),
    )
    return metadata


def _sha(value: bytes | str) -> str:
    if isinstance(value, str):
        value = value.encode("utf-8")
    return hashlib.sha256(value).hexdigest()


def _allowed_path(path: str, story_path: str, qa_paths: set[str]) -> bool:
    """Reject harness, bridge, QA and operational files from implementation scope."""
    path = path.replace("\\", "/")
    if path == story_path or path in qa_paths or path == "CLAUDE_RESULT.md":
        return True
    blocked = (
        "agent/CURRENT_STORY.md", "agent/stories/BACKLOG.md", "agent/logs/",
        "agent/runtime/", "agent/qa-plans/", "agent/qa-failures/",
        "agent/architect-requests/", "agent/user-decisions/", "local_bridge/",
        ".github/workflows/", ".git/",
        "target/", "out/", "dist/", "coverage/", "node_modules/",
        ".pytest_cache/", ".idea/",
    )
    return (not path.endswith((".log", ".tmp", ".temp"))
            and not any(path == prefix or path.startswith(prefix) for prefix in blocked))


def _current_context(implementation: dict | None, store) -> tuple[dict, dict | None, str | None]:
    active_path = story_state.get_active_story_path()
    story_id = story_state.extract_story_id(active_path.read_text(encoding="utf-8"))
    evaluation = inspect_active_evaluation(implementation, store)
    if not evaluation.get("evaluation_permitted") or not story_id:
        return evaluation, None, story_id
    story_path = active_path.relative_to(REPO_ROOT).as_posix()
    story_content = active_path.read_text(encoding="utf-8")
    return evaluation, {"id": story_id, "path": story_path, "content": story_content}, story_id


def _validate_legacy_adoption(metadata: dict, implementation: dict, evaluation: dict,
                              qa_review: dict, evaluator: dict, plan: dict, plan_path) -> dict | None:
    """Validate the single recorded legacy adoption; never infer another scope."""
    record = metadata.get("legacy_adoption")
    if not isinstance(record, dict):
        return None
    paths = record.get("authorized_paths")
    if (record.get("kind") != "human_authorized_legacy_publication"
            or record.get("authorized_by") != "human"
            or record.get("story_id") != "STORY-WEB-028"
            or record.get("implementation_task_id") != _WEB028_LEGACY_TASK_ID
            or implementation.get("id") != _WEB028_LEGACY_TASK_ID
            or record.get("commit_sha") != _WEB028_LEGACY_COMMIT
            or not isinstance(paths, list) or set(paths) != _WEB028_LEGACY_PATHS
            or len(paths) != len(_WEB028_LEGACY_PATHS)
            or record.get("story_contract_sha256") != evaluation.get("story_contract_sha256")
            or record.get("qa_review_task_id") != evaluation.get("qa_review_task_id")
            or record.get("qa_review_contract_sha256") != evaluation.get("qa_review_contract_sha256")
            or record.get("qa_review_decision") != "APPROVE"
            or qa_review.get("id") != evaluation.get("qa_review_task_id")
            or qa_review.get("task_type") != "post_implementation_review"
            or qa_review.get("status") != "completed" or qa_review.get("exit_code") != 0
            or not isinstance(qa_review.get("result"), dict)
            or qa_review["result"].get("decision") != "APPROVE"
            or record.get("qa_review_result_sha256") != _sha(json.dumps(
                qa_review.get("result"), sort_keys=True, separators=(",", ":")))
            or record.get("evaluator_task_id") != evaluator.get("id")
            or record.get("evaluation_contract_sha256") != evaluation.get("evaluation_contract_sha256")
            or record.get("evaluation_decision") != "COMPLETE"
            or record.get("evaluator_result_sha256") != _sha(json.dumps(
                evaluator.get("result"), sort_keys=True, separators=(",", ":")))
            or record.get("qa_plan_sha256") != _sha(plan_path.read_bytes())
            or record.get("protected_test_hashes") != plan.get("protected_test_hashes", {})):
        return {"blocking_code": "legacy_adoption_invalid",
                "reason": "The one-time WEB-028 adoption record does not match the current implementation, QA, Evaluator, or approved scope."}

    try:
        head = git_sync.head_sha()
        subject = git_sync.run_git("log", "-1", "--format=%s", check=False)
        committed = git_sync.run_git("diff-tree", "--no-commit-id", "--name-only", "-r", head, check=False)
        parent = git_sync.run_git("rev-parse", f"{head}^", check=False)
        branch = git_sync.current_branch()
        remote = git_sync.run_git("ls-remote", "origin", f"refs/heads/{branch}", check=False) if branch else None
        if (head != _WEB028_LEGACY_COMMIT
                or subject.returncode != 0 or subject.stdout.strip() != "implemented STORY-WEB-028"
                or committed.returncode != 0 or set(committed.stdout.splitlines()) != _WEB028_LEGACY_PATHS
                or parent.returncode != 0 or parent.stdout.strip() != record.get("parent_sha")
                or remote is None or remote.returncode != 0 or not remote.stdout.split()
                or remote.stdout.split()[0] != _WEB028_LEGACY_COMMIT):
            return {"blocking_code": "legacy_commit_mismatch",
                    "reason": "HEAD, commit subject, exact file scope, parent, or origin branch no longer matches the adopted publication."}
        fingerprints = record.get("path_sha256", {})
        blob_oids = record.get("git_blob_oids", {})
        if set(fingerprints) != _WEB028_LEGACY_PATHS or set(blob_oids) != _WEB028_LEGACY_PATHS:
            return {"blocking_code": "legacy_adoption_invalid",
                    "reason": "The adopted implementation file fingerprints are incomplete."}
        for path in _WEB028_LEGACY_PATHS:
            blob = git_sync.run_git("rev-parse", f"{head}:{path}", check=False)
            full = REPO_ROOT / path
            diff = git_sync.run_git("diff", "--quiet", head, "--", path, check=False)
            staged_diff = git_sync.run_git("diff", "--cached", "--quiet", head, "--", path, check=False)
            if (blob.returncode != 0 or blob.stdout.strip() != blob_oids.get(path)
                    or not full.is_file() or _sha(full.read_bytes()) != fingerprints.get(path)
                    or diff.returncode != 0 or staged_diff.returncode != 0):
                return {"blocking_code": "legacy_commit_mismatch",
                        "reason": f"The adopted commit or working file does not match its recorded fingerprint: {path}."}
    except (OSError, RuntimeError, ValueError) as error:
        return {"blocking_code": "legacy_commit_mismatch",
                "reason": f"Could not verify the adopted commit: {type(error).__name__}: {error}"}
    return {"commit_sha": _WEB028_LEGACY_COMMIT, "paths": sorted(_WEB028_LEGACY_PATHS)}


def inspect_publication(implementation: dict | None, store, allow_task_id: str | None = None) -> dict:
    result = {"publication_permitted": False, "story": None, "commit_sha": None,
              "implementation_task_id": None, "authorized_paths": [],
              "outstanding_prerequisites": []}

    def block(message: str, code: str) -> dict:
        result["outstanding_prerequisites"].append(message)
        result["blocking_code"] = code
        return result

    try:
        evaluation, story, story_id = _current_context(implementation, store)
        if story_id:
            result["story"] = {"id": story_id,
                               "filename": story_path_name(story["path"]) if story else ""}
        if implementation and implementation.get("story_id") == story_id:
            result["implementation_task_id"] = implementation.get("id")
        if not evaluation.get("evaluation_permitted") or not story:
            return block("Current story, approved QA, protected-test state or evaluation contract is invalid.",
                         "evaluation_not_ready")
        if (not implementation or implementation.get("task_type") != "implementation"
                or implementation.get("story_id") != story_id
                or implementation.get("status") != "completed"
                or implementation.get("exit_code") != 0):
            return block("A successful implementation task for the active story is required.", "implementation_missing")
        key = f"evaluator:{story_id}:{evaluation['evaluation_contract_sha256']}"
        evaluator = store.get_by_idempotency_key(key)
        persisted = persisted_evaluation(evaluator["id"], evaluation["evaluation_contract_sha256"], story_id) if evaluator else None
        if (not evaluator or evaluator.get("status") != "completed" or evaluator.get("exit_code") != 0
                or not isinstance(evaluator.get("result"), dict)
                or evaluator["result"].get("decision") != "COMPLETE"
                or not persisted or persisted.get("decision") != "COMPLETE"):
            return block("A current persisted Evaluator COMPLETE result is required.", "evaluator_incomplete")

        metadata = implementation.get("publication_metadata")
        legacy_adoption = isinstance(metadata, dict) and isinstance(metadata.get("legacy_adoption"), dict)
        if not isinstance(metadata, dict) or metadata.get("schema_version") not in {1, 2}:
            return block("Implementation publication metadata is missing; scope cannot be inferred.",
                         "implementation_metadata_missing")
        if (metadata.get("task_id") != implementation.get("id")
                or metadata.get("story_id") != story_id
                or (not legacy_adoption and (
                    not re.fullmatch(r"[0-9a-f]{40,64}", str(metadata.get("baseline_head_sha", "")))
                    or not isinstance(metadata.get("changed_paths"), list)))):
            return block("Implementation publication metadata is malformed or does not match this task.",
                         "implementation_metadata_invalid")
        if metadata.get("schema_version") == 2:
            baseline = metadata.get("baseline_head_sha")
            baseline_branch = metadata.get("baseline_branch")
            if (not isinstance(baseline_branch, str) or not baseline_branch
                    or metadata.get("baseline_remote_head_sha") != baseline):
                return block("The implementation baseline was not recorded against its matching origin branch tip.",
                             "baseline_metadata_invalid")
            if git_sync.current_branch() != baseline_branch:
                return block("The checkout branch differs from the implementation baseline branch.",
                             "baseline_branch_mismatch")
        if legacy_adoption and (story_id != "STORY-WEB-028" or implementation.get("id") != _WEB028_LEGACY_TASK_ID):
            return block("Legacy publication adoption is restricted to its recorded WEB-028 task.", "legacy_adoption_invalid")
        if not legacy_adoption and metadata.get("ambiguous_baseline_paths"):
            return block("Implementation touched paths that were already dirty at its baseline: "
                         + ", ".join(metadata["ambiguous_baseline_paths"]), "baseline_scope_ambiguous")
        if not legacy_adoption and (metadata.get("protected_paths_restored") or metadata.get("lifecycle_paths_restored")):
            return block("The implementation required protected or lifecycle restoration; inspect its failed task record.",
                         "implementation_safeguard_triggered")

        from agent.runtime.qa import qa_agent
        active_path = story_state.get_active_story_path()
        plan = qa_agent.load_plan(active_path)
        if not plan:
            return block("The active QA plan is unavailable.", "qa_plan_missing")
        plan_path = qa_agent.plan_path(story_id)
        if legacy_adoption:
            unchanged, changed_protected = qa_agent.plan_tests_unchanged(plan)
            if not unchanged:
                return block("Protected QA tests changed: " + ", ".join(changed_protected),
                             "protected_qa_state_invalid")
            qa_review = store.get(evaluation["qa_review_task_id"])
            adopted = _validate_legacy_adoption(metadata, implementation, evaluation,
                                                qa_review or {}, evaluator, plan, plan_path)
            if not adopted:
                return block("The legacy adoption record could not be validated.", "legacy_adoption_invalid")
            if adopted.get("blocking_code"):
                return block(adopted["reason"], adopted["blocking_code"])
            result.update(
                publication_permitted=True, already_published=True,
                story={"id": story_id, "filename": story_path_name(story["path"])},
                implementation_task_id=implementation["id"],
                authorized_paths=adopted["paths"], commit_sha=adopted["commit_sha"],
                evaluator_task_id=evaluator["id"],
                evaluation_contract_sha256=evaluation["evaluation_contract_sha256"],
                publication_contract_sha256=_sha(json.dumps(metadata["legacy_adoption"],
                                                               sort_keys=True, separators=(",", ":"))),
            )
            return result
        qa_paths = set(plan.get("prepared_test_paths", []))
        implementation_paths = set(metadata["changed_paths"])
        if not implementation_paths or any(not isinstance(path, str) for path in metadata["changed_paths"]):
            return block("The implementation task has no validated changed-file scope.", "empty_implementation_scope")
        if any(not _allowed_path(path, story["path"], qa_paths) for path in implementation_paths):
            return block("The implementation scope includes a protected or harness-owned path.", "protected_path_in_scope")
        path_hashes = metadata.get("changed_path_sha256")
        if not isinstance(path_hashes, dict) or set(path_hashes) != implementation_paths:
            return block("Implementation changed-file fingerprints are missing or incomplete.",
                         "implementation_metadata_invalid")
        for path, expected_hash in path_hashes.items():
            full = REPO_ROOT / path
            try:
                current_hash = _sha(full.read_bytes()) if full.is_file() else None
            except OSError:
                current_hash = None
            if current_hash != expected_hash:
                return block(f"Implementation file changed after task completion: {path}.",
                             "implementation_scope_changed")
        plan_relative = plan_path.relative_to(REPO_ROOT).as_posix()
        authorized_scope = metadata.get("authorized_scope", {})
        if (authorized_scope.get("qa_plan_path") != plan_relative
                or set(authorized_scope.get("protected_test_paths", [])) != qa_paths
                or authorized_scope.get("protected_test_hashes") != plan.get("protected_test_hashes", {})):
            return block("The validated QA artifacts differ from the implementation-time scope record.",
                         "qa_artifact_scope_changed")
        unchanged, changed_protected = qa_agent.plan_tests_unchanged(plan)
        if not unchanged:
            return block("Protected QA tests changed: " + ", ".join(changed_protected),
                         "protected_qa_state_invalid")
        qa_artifacts = {plan_relative, *qa_paths}
        # Validated QA artifacts are a separate, contract-bound part of the
        # story publication scope, not paths inferred from implementation code.
        paths = implementation_paths | qa_artifacts

        head = git_sync.head_sha()
        baseline = metadata["baseline_head_sha"]
        if head != baseline:
            parent = git_sync.run_git("rev-parse", "HEAD^", check=False)
            subject = git_sync.run_git("log", "-1", "--format=%s", check=False)
            committed_paths = git_sync.run_git("diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD", check=False)
            expected = f"implemented {story_id}"
            if (parent.returncode != 0 or parent.stdout.strip() != baseline
                    or subject.returncode != 0 or not subject.stdout.strip().startswith(expected)
                    or committed_paths.returncode != 0
                    or not implementation_paths.issubset(set(committed_paths.stdout.splitlines()))
                    or not set(committed_paths.stdout.splitlines()).issubset(paths)):
                return block("HEAD moved since implementation began; publication scope is stale.", "baseline_drift")
        else:
            branch = git_sync.current_branch()
            if not branch:
                return block("A branch checkout is required for implementation publication.", "branch_unavailable")
            remote = git_sync.run_git("ls-remote", "origin", f"refs/heads/{branch}", check=False)
            if (remote.returncode != 0 or not remote.stdout.split()
                    or remote.stdout.split()[0] != baseline):
                return block("The implementation baseline is not the current origin branch tip.",
                             "baseline_not_remote_head")

        current_entries = git_sync.working_tree_entries()
        changed_now = set(current_entries)
        staged = set(git_sync._output("diff", "--cached", "--name-only").splitlines())
        baseline_status = metadata.get("baseline_status_by_path", {})
        baseline_entries = metadata.get("baseline_entries", {})
        preserved_lifecycle = set()
        for lifecycle_path in ("agent/CURRENT_STORY.md", "agent/stories/BACKLOG.md"):
            if (lifecycle_path in changed_now and lifecycle_path in metadata.get("baseline_paths", [])
                    and baseline_status.get(lifecycle_path) == current_entries.get(lifecycle_path) == " M"
                    and lifecycle_path not in staged):
                full = REPO_ROOT / lifecycle_path
                before = baseline_entries.get(lifecycle_path, {}).get("content_sha256")
                if full.is_file() and _sha(full.read_bytes()) == before:
                    preserved_lifecycle.add(lifecycle_path)
        unrelated = changed_now - paths - preserved_lifecycle
        if unrelated:
            return block("Unrelated dirty paths must be resolved or preserved before publication: "
                         + ", ".join(sorted(unrelated)), "unrelated_dirty_files")
        if staged - paths:
            return block("Unrelated staged paths prevent safe publication: "
                         + ", ".join(sorted(staged - paths)), "unrelated_staged_files")

        result.update(publication_permitted=True, story={"id": story_id, "filename": story_path_name(story["path"])},
                      implementation_task_id=implementation["id"], authorized_paths=sorted(paths),
                      baseline_head_sha=baseline, evaluator_task_id=evaluator["id"],
                      evaluation_contract_sha256=evaluation["evaluation_contract_sha256"])
        publication_contract = {
            "story_id": story_id, "implementation_task_id": implementation["id"],
            "baseline_head_sha": baseline, "authorized_paths": sorted(paths),
            "evaluation_contract_sha256": evaluation["evaluation_contract_sha256"],
        }
        result["publication_contract_sha256"] = _sha(json.dumps(
            publication_contract, sort_keys=True, separators=(",", ":")))
        return result
    except (OSError, ValueError, RuntimeError, KeyError, TypeError) as error:
        return block(f"Publication readiness could not be established: {type(error).__name__}: {error}",
                     "publication_readiness_error")


def story_path_name(path: str) -> str:
    return PurePosixPath(path).name


def publish_implementation(implementation: dict | None, store) -> dict:
    readiness = inspect_publication(implementation, store)
    if not readiness.get("publication_permitted"):
        raise ValueError("Publication gate rejected the request: "
                         + "; ".join(readiness.get("outstanding_prerequisites", [])))
    if readiness.get("already_published"):
        return {"status": "ALREADY_PUBLISHED", "story_id": readiness["story"]["id"],
                "sha": readiness["commit_sha"], "push_status": "verified",
                "changed_paths": readiness["authorized_paths"], "legacy_adoption": True}
    story_path = story_state.get_active_story_path()
    content = story_path.read_text(encoding="utf-8")
    from agent.runtime.core.orchestrator import _story_commit_message
    story_id = readiness["story"]["id"]
    expected_prefix = f"implemented {story_id}"

    # Recover only an exact existing implementation commit. Never select an
    # arbitrary commit by branch position or manufacture a second one.
    head = git_sync.head_sha()
    subject = git_sync.run_git("log", "-1", "--format=%s", check=False).stdout.strip()
    if subject.startswith(expected_prefix):
        published = git_sync.run_git("ls-remote", "origin", f"refs/heads/{git_sync.current_branch()}", check=False)
        if published.returncode == 0 and published.stdout.split() and published.stdout.split()[0] == head:
            return {"status": "ALREADY_PUBLISHED", "story_id": story_id, "sha": head,
                    "push_status": "verified", "changed_paths": readiness["authorized_paths"]}

    paths = readiness["authorized_paths"]
    pending = git_sync.pending_planning_paths()
    if pending.intersection(paths):
        raise ValueError("Implementation publication scope overlaps pending planner changes.")
    record = git_sync.commit_and_push(_story_commit_message(story_path, content), paths=paths)
    if record.get("status") not in {"PUSHED", "UP_TO_DATE"}:
        return {"status": record.get("status"), "story_id": story_id, "sha": record.get("sha"),
                "push_status": "failed" if record.get("status") == "PUSH_REJECTED" else "not_attempted",
                "reason": record.get("reason", "Commit or push failed."), "changed_paths": paths}
    branch = record.get("branch")
    remote = git_sync.run_git("ls-remote", "origin", f"refs/heads/{branch}", check=False)
    sha = git_sync.head_sha()
    if remote.returncode != 0 or not remote.stdout.split() or remote.stdout.split()[0] != sha:
        return {"status": "PUBLICATION_UNVERIFIED", "story_id": story_id, "sha": sha,
                "push_status": "unverified", "reason": "Remote branch does not resolve to the implementation commit.",
                "changed_paths": paths}
    return {"status": "PUBLISHED", "story_id": story_id, "sha": sha,
            "push_status": "verified", "changed_paths": paths}


def verify_already_published(story_id: str, metadata: dict) -> str | None:
    """Return HEAD only when exact baseline-parent, subject, paths and remote match."""
    head = git_sync.head_sha()
    baseline = metadata.get("baseline_head_sha")
    if not isinstance(baseline, str) or head == baseline:
        return None
    parent = git_sync.run_git("rev-parse", "HEAD^", check=False)
    subject = git_sync.run_git("log", "-1", "--format=%s", check=False)
    committed = git_sync.run_git("diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD", check=False)
    branch = git_sync.current_branch()
    if (not branch or parent.returncode != 0 or parent.stdout.strip() != baseline
            or subject.returncode != 0 or not subject.stdout.strip().startswith(f"implemented {story_id}")
            or committed.returncode != 0
            or not set(metadata.get("changed_paths", [])).issubset(set(committed.stdout.splitlines()))
            or not set(committed.stdout.splitlines()).issubset(
                set(metadata.get("changed_paths", []))
                | {metadata.get("authorized_scope", {}).get("qa_plan_path")}
                | set(metadata.get("authorized_scope", {}).get("protected_test_paths", [])))):
        return None
    remote = git_sync.run_git("ls-remote", "origin", f"refs/heads/{branch}", check=False)
    if remote.returncode == 0 and remote.stdout.split() and remote.stdout.split()[0] == head:
        return head
    return None
