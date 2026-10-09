"""Guarded, local-only finalization of a story that passed application CI."""

from __future__ import annotations

import hashlib
import json
import re
import tempfile
from pathlib import Path

from agent.runtime.core import story_state
from agent.runtime.support import config, git_sync
from local_bridge.final_ci import inspect_final_ci
from local_bridge.evaluation import persisted_evaluation


FINALIZATION_FILES = (
    "agent/stories/{filename}",
    "agent/stories/BACKLOG.md",
    "agent/CURRENT_STORY.md",
)


def _commit_subject(story_id: str, sha: str) -> str:
    return f"finalized {story_id} after CI {sha[:12]}"


def _matching_commit(subject: str) -> str | None:
    result = git_sync.run_git("log", "--format=%H%x00%s", check=False)
    if result.returncode:
        return None
    for line in result.stdout.splitlines():
        sha, sep, message = line.partition("\0")
        if sep and message == subject:
            return sha
    return None


def _completed_commit_for_story(story_id: str) -> str | None:
    result = git_sync.run_git("log", "--format=%H%x00%s", check=False)
    if result.returncode:
        return None
    prefix = f"finalized {story_id} after CI "
    for line in result.stdout.splitlines():
        sha, sep, message = line.partition("\0")
        if sep and message.startswith(prefix):
            return sha
    return None


def _task_for_ci(story_id: str, fingerprint: str, head_sha: str, store) -> dict | None:
    task = store.get_by_idempotency_key(f"final-ci:{story_id}:{fingerprint}")
    if not task or task.get("task_type") != "final_ci" or task.get("story_id") != story_id:
        return None
    result = task.get("result")
    if (task.get("contract_sha256") != fingerprint or task.get("status") != "completed"
            or task.get("exit_code") != 0 or not isinstance(result, dict)
            or result.get("status") != "PASSED" or result.get("sha") != head_sha
            or result.get("final_ci_contract_sha256") != fingerprint
            or result.get("result_source") != "github_actions_for_pushed_commit"
            or result.get("application_verdict_policy") != "gw2-application-jobs-v1"):
        return None
    return task


def _finalization_fingerprint(story_id: str, head_sha: str, ci_fingerprint: str) -> str:
    value = {"story_id": story_id, "head_sha": head_sha,
             "final_ci_contract_sha256": ci_fingerprint}
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


_RESULT_FINDING_SECTIONS = {"result", "findings", "follow-up findings"}
_TOP_LEVEL_SECTION = re.compile(r"(?m)^##[ \t]+([^\r\n]+?)[ \t]*\r?$")


def _without_result_findings(content: str) -> tuple[str, list[str]]:
    """Return story material outside the implementation-owned result sections."""
    matches = list(_TOP_LEVEL_SECTION.finditer(content))
    if not matches:
        return content, []
    preamble = content[:matches[0].start()]
    retained = []
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(content)
        section = content[match.start():end]
        heading = match.group(1).strip().casefold()
        if heading in _RESULT_FINDING_SECTIONS:
            continue
        else:
            # The trailing line breaks delimit a following allowed Result or
            # Findings section; their count is not story content.
            retained.append(section.rstrip("\r\n"))
    return preamble, retained


def _story_delta_is_persisted_result(story_path: Path, story_id: str, ci: dict, store) -> bool:
    """Allow only Result/Findings edits that the current COMPLETE Evaluator read."""
    try:
        relative = story_path.relative_to(config.REPO_ROOT).as_posix()
        baseline = git_sync.run_git("show", f"{ci['head_sha']}:{relative}", check=False)
        if baseline.returncode != 0:
            return False
        current_bytes = story_path.read_bytes()
        current = current_bytes.decode("utf-8").replace("\r\n", "\n")
        before_preamble, before_sections = _without_result_findings(baseline.stdout)
        after_preamble, after_sections = _without_result_findings(current)
        # All non-result material, including Status and lifecycle metadata,
        # must remain byte-for-byte identical to the published story.
        from agent.runtime.support.files import find_section_span
        if (before_preamble != after_preamble or before_sections != after_sections
                or find_section_span(baseline.stdout, "Result") is None
                or find_section_span(current, "Result") is None):
            return False

        evaluator_task_id = ci.get("evaluator_task_id")
        contract = ci.get("evaluation_contract_sha256")
        evaluator_task = store.get(evaluator_task_id) if evaluator_task_id else None
        evaluated = persisted_evaluation(evaluator_task_id, contract, story_id) if evaluator_task_id and contract else None
        if (not evaluator_task or evaluator_task.get("task_type") != "evaluator"
                or evaluator_task.get("story_id") != story_id
                or evaluator_task.get("status") != "completed" or evaluator_task.get("exit_code") != 0
                or not isinstance(evaluator_task.get("result"), dict)
                or evaluator_task["result"] != evaluated
                or not evaluated or evaluated.get("decision") != "COMPLETE"
                or evaluated.get("evaluation_task_id") != evaluator_task_id
                or evaluated.get("evaluation_contract_sha256") != contract
                or evaluated.get("implementation_task_id") != ci.get("implementation_task_id")
                or evaluated.get("evaluated_story_sha256") != hashlib.sha256(current.encode("utf-8")).hexdigest()
                or evaluated.get("evaluated_story_contract_sha256") is None):
            return False
        from agent.runtime.qa import qa_agent
        if evaluated["evaluated_story_contract_sha256"] != qa_agent.story_contract_sha256(current):
            return False
        result_content = config.CLAUDE_RESULT_FILE.read_text(encoding="utf-8")
        if evaluated.get("evaluated_result_sha256") != hashlib.sha256(result_content.encode("utf-8")).hexdigest():
            return False
        return True
    except (OSError, ValueError, RuntimeError, KeyError, TypeError, AttributeError):
        return False


def _activation_lifecycle_matches_head(story_path: Path, head_sha: str) -> bool:
    """Allow only the deterministic Active/backlog/pointer delta from activation."""
    try:
        relative = story_path.relative_to(config.REPO_ROOT).as_posix()
        baseline_backlog = git_sync.run_git(
            "show", f"{head_sha}:agent/stories/BACKLOG.md", check=False)
        baseline_pointer = git_sync.run_git(
            "show", f"{head_sha}:agent/CURRENT_STORY.md", check=False)
        if baseline_backlog.returncode != 0 or baseline_pointer.returncode != 0:
            return False
        if (story_state.parse_backlog_section(baseline_backlog.stdout, "Active")
                or story_path.name not in story_state.parse_backlog_section(baseline_backlog.stdout, "To Do")
                or baseline_pointer.stdout.strip()):
            return False
        expected_backlog = story_state.move_backlog_entry_to_active(
            baseline_backlog.stdout, story_path.name)
        current_backlog = story_state.BACKLOG_FILE.read_text(encoding="utf-8")
        current_pointer = story_state.CURRENT_STORY_FILE.read_text(encoding="utf-8")
        return (current_backlog == expected_backlog
                and current_pointer == relative + "\n"
                and story_state.parse_backlog_section(current_backlog, "Active") == [story_path.name])
    except (OSError, ValueError, RuntimeError, KeyError, TypeError):
        return False


def _partial_finalization(story_id: str, store) -> dict | None:
    """Recognize only the exact idempotent write prefixes of the old finalizer."""
    try:
        head = git_sync.head_sha()
        tasks = [t for t in store.tasks_for(story_id, "final_ci")
                 if t.get("status") == "completed" and t.get("exit_code") == 0]
        matching = [t for t in tasks if isinstance(t.get("result"), dict)
                    and t["result"].get("status") == "PASSED"
                    and t["result"].get("sha") == head
                    and t["result"].get("result_source") == "github_actions_for_pushed_commit"
                    and t["result"].get("application_verdict_policy") == "gw2-application-jobs-v1"
                    and t["result"].get("final_ci_contract_sha256") == t.get("contract_sha256")]
        if len(matching) != 1 or config.ATTEMPT_STATE_FILE.exists():
            return None
        if any(t.get("status") in {"queued", "running", "interrupted"}
               for t in store.tasks.values()):
            return None
        story_path = story_state.find_story_file_by_id(story_id, story_state.build_story_index())
        rel_story = story_path.relative_to(config.REPO_ROOT).as_posix()
        baseline_story = git_sync.run_git("show", f"{head}:{rel_story}").stdout
        baseline_backlog = git_sync.run_git("show", f"{head}:agent/stories/BACKLOG.md").stdout
        baseline_pointer = git_sync.run_git("show", f"{head}:agent/CURRENT_STORY.md").stdout
        with tempfile.TemporaryDirectory() as temp_dir:
            temp_story = Path(temp_dir) / story_path.name
            temp_story.write_text(baseline_story, encoding="utf-8")
            story_state.set_story_done(temp_story)
            done_story = temp_story.read_text(encoding="utf-8")
        from agent.runtime.core.orchestrator import strip_backlog_prose
        from agent.runtime.human.user_decisions import extract_section
        done_backlog = story_state.move_backlog_entry_to_done(
            baseline_backlog, story_path.name,
            extract_section(baseline_story, "Title") or "")
        done_backlog = strip_backlog_prose(done_backlog)
        current = (story_path.read_text(encoding="utf-8"),
                   story_state.BACKLOG_FILE.read_text(encoding="utf-8"),
                   story_state.CURRENT_STORY_FILE.read_text(encoding="utf-8"))
        prefixes = (
            (done_story, baseline_backlog, baseline_pointer),
            (done_story, done_backlog, baseline_pointer),
            (done_story, done_backlog, ""),
        )
        if current not in prefixes:
            return None
        # The CI contract's inputs must still be byte-for-byte committed. The
        # only permitted dirty paths are the three sequential lifecycle writes.
        from agent.runtime.qa import qa_agent
        plan = qa_agent.load_plan(story_path)
        protected = set(plan.get("prepared_test_paths", [])) if isinstance(plan, dict) else set()
        critical = {".github/workflows/ci.yml", qa_agent.plan_path(story_id).relative_to(config.REPO_ROOT).as_posix(),
                    "agent/runtime/artifacts/QA_RESULT.json", "agent/runtime/artifacts/QA_STATE.json",
                    "agent/runtime/artifacts/EVALUATOR_RESULT.json", *protected}
        dirty = git_sync.working_tree_paths()
        lifecycle = {rel_story, "agent/stories/BACKLOG.md", "agent/CURRENT_STORY.md"}
        if not dirty.intersection(lifecycle):
            return None
        if dirty.intersection(critical) or any(p.startswith("frontend/") for p in dirty):
            return None
        task = matching[0]
        return {"story_path": story_path, "head_sha": head,
                "ci_fingerprint": task["contract_sha256"],
                "finalization_contract_sha256": _finalization_fingerprint(
                    story_id, head, task["contract_sha256"]),
                "story": {"id": story_id, "filename": story_path.name,
                          "title": extract_section(baseline_story, "Title") or story_path.stem},
                "recovery": True}
    except (OSError, ValueError, RuntimeError, KeyError, TypeError, AttributeError):
        return None


def inspect_finalization(story_id: str | None, store) -> dict:
    result = {
        "finalization_permitted": False,
        "outstanding_prerequisites": [],
        "story": None,
        "head_sha": None,
        "final_ci_contract_sha256": None,
        "finalization_contract_sha256": None,
        "operation_source": "local_bridge_http",
    }

    def block(message: str, code: str) -> dict:
        candidates = [story_id] if story_id else sorted({
            task.get("story_id") for task in store.tasks.values()
            if task.get("task_type") == "final_ci" and task.get("status") == "completed"
            and isinstance(task.get("story_id"), str)
        })
        recoveries = [candidate for candidate in candidates
                      if candidate and _partial_finalization(candidate, store)]
        if len(recoveries) == 1:
            recovery = _partial_finalization(recoveries[0], store)
            result.update(finalization_permitted=True, outstanding_prerequisites=[],
                          story=recovery["story"], head_sha=recovery["head_sha"],
                          story_path=recovery["story_path"],
                          final_ci_contract_sha256=recovery["ci_fingerprint"],
                          finalization_contract_sha256=recovery["finalization_contract_sha256"],
                          recovery=True, recovery_required=True,
                          recovery_message="A recognized partial lifecycle transition will be safely completed.")
            return result
        result["outstanding_prerequisites"].append(message)
        result["blocking_code"] = code
        return result

    try:
        active_path = story_state.get_active_story_path()
        content = active_path.read_text(encoding="utf-8")
        active_id = story_state.extract_story_id(content)
        if not active_id or (story_id and story_id != active_id):
            return block("The requested story is not the current active story.", "story_not_active")
        if story_state.classify_story_status(story_state.extract_status_section(content)) not in {"TODO", "ACTIVE"}:
            return block("The active story has an unexpected lifecycle status.", "story_status_invalid")
        backlog = story_state.BACKLOG_FILE.read_text(encoding="utf-8")
        active_filenames = story_state.parse_backlog_section(backlog, "Active")
        if active_filenames != [active_path.name]:
            return block("The backlog does not identify this story as the sole Active entry.", "active_backlog_inconsistent")
        if config.ATTEMPT_STATE_FILE.exists():
            return block("An unfinished orchestrator attempt journal exists; inspect it before finalization.", "attempt_in_progress")
        if any(t.get("status") in {"queued", "running", "interrupted"}
               for t in store.tasks.values()):
            return block("A bridge task is queued, running, or has an uncertain outcome.", "task_in_progress")
        implementation = store.latest_implementation_task(active_id)
        ci = inspect_final_ci(implementation, store)
        if not ci.get("ci_permitted") or ci.get("story", {}).get("id") != active_id:
            return block("Final CI prerequisites are not current: " + "; ".join(ci.get("outstanding_prerequisites", [])), "ci_not_ready")
        ci_task = _task_for_ci(active_id, ci["final_ci_contract_sha256"], ci["head_sha"], store)
        if not ci_task:
            return block("A verified PASSED application CI result for the current pushed commit is required.", "ci_result_invalid")
        dirty = git_sync.working_tree_paths()
        story_relative = active_path.relative_to(config.REPO_ROOT).as_posix()
        if (story_relative in dirty
                and not _story_delta_is_persisted_result(active_path, active_id, ci, store)):
            return block("The active story has changes outside the current persisted Result/Findings; inspect them before finalization.",
                         "story_result_changes_invalid")
        activation_paths = {"agent/stories/BACKLOG.md", "agent/CURRENT_STORY.md"}
        if (dirty.intersection(activation_paths)
                and not _activation_lifecycle_matches_head(active_path, ci["head_sha"])):
            return block("BACKLOG.md or CURRENT_STORY.md contains changes beyond the recorded active-story transition.",
                         "lifecycle_files_dirty")
        fingerprint = _finalization_fingerprint(active_id, ci["head_sha"], ci["final_ci_contract_sha256"])
        result.update(finalization_permitted=True, outstanding_prerequisites=[], story=ci["story"],
                      head_sha=ci["head_sha"], final_ci_contract_sha256=ci["final_ci_contract_sha256"],
                      finalization_contract_sha256=fingerprint, final_ci_task_id=ci_task["id"])
        return result
    except (OSError, ValueError, RuntimeError, KeyError, TypeError) as error:
        return block(f"Finalization readiness could not be verified ({type(error).__name__}).", "readiness_error")


def finalize_story(story_id: str, fingerprint: str, store) -> dict:
    """Revalidate under the shared story lock, transition, and make one local commit."""
    with story_state.story_activation_lock():
        prior = _completed_commit_for_story(story_id)
        if prior:
            try:
                story_path = story_state.find_story_file_by_id(story_id, story_state.build_story_index())
                status = story_state.classify_story_status(
                    story_state.extract_status_section(story_path.read_text(encoding="utf-8")))
                pointer = story_state.CURRENT_STORY_FILE.read_text(encoding="utf-8").strip()
                if status == "DONE" and not pointer:
                    return {"story_id": story_id, "status": "already_finalized", "commit_sha": prior,
                            "backlog_state": "Done", "current_story": None, "operation_source": "local_bridge_http"}
            except (OSError, ValueError, RuntimeError):
                pass
            raise ValueError("A prior finalization commit exists, but the lifecycle files do not match its completed state. Inspect manually; no changes were made.")
        readiness = inspect_finalization(story_id, store)
        recovering = bool(readiness.get("recovery"))
        if not readiness["finalization_permitted"]:
            recovery = _partial_finalization(story_id, store)
            if recovery:
                readiness = recovery
                recovering = True
            else:
                raise ValueError("Finalization gate rejected the request: " + "; ".join(readiness["outstanding_prerequisites"]))
        if readiness["finalization_contract_sha256"] != fingerprint:
            raise ValueError("Finalization contract changed; fetch readiness again.")
        subject = _commit_subject(story_id, readiness["head_sha"])
        prior = _matching_commit(subject)
        if prior:
            return {"story_id": story_id, "status": "already_finalized", "commit_sha": prior,
                    "backlog_state": "Done", "current_story": None, "operation_source": "local_bridge_http"}

        if git_sync.head_sha() != readiness["head_sha"]:
            raise ValueError("HEAD changed after CI; finalization stopped without changing files.")
        story_path = readiness.get("story_path") or story_state.get_active_story_path()
        paths = [p.format(filename=story_path.name) for p in FINALIZATION_FILES]
        staged = set(git_sync.run_git("diff", "--cached", "--name-only").stdout.splitlines())
        if staged - set(paths):
            raise ValueError("The Git index contains unrelated staged changes. Commit or unstage them before finalization; no unrelated files will be included.")
        if staged and git_sync.run_git("diff", "--quiet", "--", *paths, check=False).returncode != 0:
            raise ValueError("Staged lifecycle files differ from the working tree; inspect the index before recovery.")
        from agent.runtime.core.orchestrator import finalize_completed_story
        problems = finalize_completed_story(story_path, "PASSED", readiness["head_sha"])
        if problems:
            raise ValueError("Lifecycle transition left backlog inconsistencies: " + "; ".join(problems))
        # Existing unrelated unstaged files remain untouched. An existing
        # staged index was rejected above so `git commit` cannot absorb them.
        git_sync.run_git("add", "--", *paths)
        staged_after = set(git_sync.run_git("diff", "--cached", "--name-only").stdout.splitlines())
        if staged_after - set(paths):
            raise ValueError("Unexpected staged paths detected; no commit was made.")
        commit = git_sync.run_git("commit", "--only", "-m", subject, "--", *paths, check=False)
        if commit.returncode:
            raise RuntimeError("Finalization files are updated and staged, but the local commit failed. Inspect the index and retry after resolving the Git error.")
        sha = git_sync.head_sha()
        committed = set(git_sync.run_git("diff-tree", "--no-commit-id", "--name-only", "-r", sha).stdout.splitlines())
        if not committed.issubset(set(paths)):
            raise RuntimeError("The finalization commit contains an unexpected path; stop and inspect this local commit before publishing.")
        return {"story_id": story_id, "status": "recovered_and_finalized" if recovering else "finalized", "commit_sha": sha,
                "backlog_state": "Done", "current_story": None,
                "commit_subject": subject, "operation_source": "local_bridge_http"}
