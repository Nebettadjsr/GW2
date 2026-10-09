"""Local authenticated HTTP bridge to coding agents for the GW2 repository."""

from __future__ import annotations

import hmac
import hashlib
import json
import logging
import os
import re
import shutil
import subprocess
import sys
import threading
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import parse_qs, urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener

REPOSITORY = Path(__file__).resolve().parent.parent
if str(REPOSITORY) not in sys.path:
    sys.path.insert(0, str(REPOSITORY))
CLAUDE_EXECUTABLE = Path(r"C:\Users\Administrator\.local\bin\claude.EXE")
CODEX_NODE_EXECUTABLE = Path(r"C:\Program Files\nodejs\node.exe")
CODEX_CLI_SCRIPT = Path.home() / "AppData" / "Roaming" / "npm" / "node_modules" / "@openai" / "codex" / "bin" / "codex.js"
SUPPORTED_AGENTS = {"claude", "codex"}
MAX_PROMPT_LENGTH = 20_000
MAX_CALLBACK_URL_LENGTH = 2048
MAX_REQUEST_BODY_LENGTH = MAX_PROMPT_LENGTH + MAX_CALLBACK_URL_LENGTH + 2048
CALLBACK_RETRY_DELAYS = (0, 0.25, 0.5, 1, 2, 4, 8)
TERMINAL_STATUSES = {"completed", "failed", "interrupted"}
QA_TASK_TYPE = "qa_preparation"
QA_REVIEW_TASK_TYPE = "post_implementation_review"
EVALUATOR_TASK_TYPE = "evaluator"
FINAL_CI_TASK_TYPE = "final_ci"


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def validate_callback_url(callback_url: str, callback_origin: str) -> str:
    """Accept only Wait-node resume URLs on the configured local n8n origin."""
    if not isinstance(callback_url, str) or len(callback_url) > MAX_CALLBACK_URL_LENGTH:
        raise ValueError("callback_url is invalid or too long.")
    candidate = urlparse(callback_url)
    allowed = urlparse(callback_origin)
    if (candidate.scheme, candidate.hostname, candidate.port) != (allowed.scheme, allowed.hostname, allowed.port):
        raise ValueError("callback_url must use the configured local n8n origin.")
    if candidate.scheme != "http" or candidate.username or candidate.password or candidate.fragment:
        raise ValueError("callback_url must be a plain local HTTP resume URL.")
    if not re.fullmatch(r"/webhook-waiting/[^/?#]+(?:/[^/?#]+)?", candidate.path):
        raise ValueError("callback_url must be an n8n Wait-node resume URL.")
    query = parse_qs(candidate.query, keep_blank_values=True)
    if set(query) != {"signature"} or len(query["signature"]) != 1 or not query["signature"][0]:
        raise ValueError("callback_url must include n8n's resume signature.")
    return callback_url


class TaskStore:
    def __init__(self, path: Path):
        self.path = path
        self.lock = threading.RLock()
        self.tasks: dict[str, dict] = {}
        self.idempotency: dict[str, str] = {}
        self._load()

    def _load(self) -> None:
        if self.path.exists():
            data = json.loads(self.path.read_text(encoding="utf-8"))
            self.tasks = data.get("tasks", {})
            self.idempotency = data.get("idempotency", {})
        changed = False
        for task in self.tasks.values():
            if "agent" not in task:
                task["agent"] = "claude"
                changed = True
            if task["status"] in {"queued", "running"}:
                message = (
                    "Bridge restarted while Claude implementation was running. Its outcome and repository changes are uncertain; inspect the persisted task and working tree before any deliberate recovery. This task will not be relaunched automatically."
                    if task.get("task_type") == "implementation"
                    else
                    "Bridge restarted during QA preparation; its outcome is uncertain. "
                    "The request will not be rerun automatically. Inspect the task, QA_STATE.json, saved plan and Codex process."
                    if task.get("task_type") == QA_TASK_TYPE
                    else
                    "Bridge restarted during post-implementation QA review; its outcome is uncertain. "
                    "Inspect the task and QA plan before any deliberate retry."
                    if task.get("task_type") == QA_REVIEW_TASK_TYPE
                    else
                    "Bridge restarted while final CI was being checked. Its outcome is uncertain; "
                    "inspect the persisted task and GitHub Actions run before any deliberate recovery."
                    if task.get("task_type") == FINAL_CI_TASK_TYPE
                    else "Bridge restarted while task outcome was uncertain."
                )
                task.update(status="interrupted", error=message, updated_at=utc_now())
                changed = True
            for field, value in (("callback_delivery_attempts", 0), ("callback_last_error", None)):
                if field not in task:
                    task[field] = value
                    changed = True
            if task.get("callback_url"):
                if "callback_revision" not in task:
                    task["callback_revision"] = 1
                    changed = True
                if "callback_delivery_status" not in task:
                    task["callback_delivery_status"] = "pending"
                    changed = True
            else:
                if "callback_revision" not in task:
                    task["callback_revision"] = 0
                    changed = True
                if "callback_delivery_status" not in task:
                    task["callback_delivery_status"] = "not_requested"
                    changed = True
        if changed:
            self._save()

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(self.path.suffix + ".tmp")
        temporary.write_text(json.dumps({"tasks": self.tasks, "idempotency": self.idempotency}, ensure_ascii=False), encoding="utf-8")
        os.replace(temporary, self.path)

    def create(self, prompt: str, key: str, callback_url: str | None = None,
               agent: str = "claude", task_type: str = "generic",
               story_id: str | None = None,
               contract_sha256: str | None = None) -> tuple[dict, bool]:
        with self.lock:
            existing_id = self.idempotency.get(key)
            if existing_id:
                existing = self.tasks[existing_id]
                if (existing.get("agent", "claude") != agent
                        or existing.get("prompt") != prompt
                        or existing.get("task_type", "generic") != task_type
                        or existing.get("story_id") != story_id
                        or existing.get("contract_sha256") != contract_sha256):
                    raise RuntimeError("Idempotency-Key is already associated with a different agent task.")
                # A replacement n8n execution has a new Wait resume URL. Update only
                # the callback on the original task; never create another process.
                if callback_url and callback_url != existing.get("callback_url"):
                    existing.update(callback_url=callback_url,
                                    callback_revision=existing.get("callback_revision", 0) + 1,
                                    callback_delivery_status="pending",
                                    callback_delivery_attempts=0,
                                    callback_last_error=None,
                                    callback_delivered_at=None,
                                    updated_at=utc_now())
                    self._save()
                return dict(existing), False
            if any(task["status"] in {"queued", "running"} for task in self.tasks.values()):
                raise RuntimeError("A bridge task is already running. Wait for it to finish before submitting another.")
            task = {"id": str(uuid.uuid4()), "agent": agent, "status": "queued", "prompt": prompt, "exit_code": None,
                    "result": None, "error": None, "callback_url": callback_url,
                    "task_type": task_type, "story_id": story_id,
                    "contract_sha256": contract_sha256,
                    "callback_delivery_status": "pending" if callback_url else "not_requested",
                    "callback_delivery_attempts": 0, "callback_last_error": None,
                    "callback_revision": 1 if callback_url else 0,
                    "created_at": utc_now(), "updated_at": utc_now()}
            self.tasks[task["id"]] = task
            self.idempotency[key] = task["id"]
            self._save()
            return task, True

    def get_by_idempotency_key(self, key: str) -> dict | None:
        with self.lock:
            task_id = self.idempotency.get(key)
            return dict(self.tasks[task_id]) if task_id else None

    def update(self, task_id: str, **values) -> dict:
        with self.lock:
            task = self.tasks[task_id]
            task.update(values, updated_at=utc_now())
            self._save()
            return dict(task)

    def update_callback_delivery(self, task_id: str, revision: int, **values) -> bool:
        """Apply delivery state only while this worker still owns the callback URL."""
        with self.lock:
            task = self.tasks[task_id]
            if task.get("callback_revision", 0) != revision:
                return False
            task.update(values, updated_at=utc_now())
            self._save()
            return True

    def get(self, task_id: str) -> dict | None:
        with self.lock:
            task = self.tasks.get(task_id)
            return dict(task) if task else None

    def pending_callbacks(self) -> list[str]:
        with self.lock:
            return [task["id"] for task in self.tasks.values()
                    if task.get("callback_url") and task["status"] in TERMINAL_STATUSES
                    and task.get("callback_delivery_status") != "delivered"]

    def has_uncertain_qa_task(self, story_id: str) -> bool:
        """Do not start a fresh QA worker while an older outcome is uncertain."""
        with self.lock:
            return any(task.get("task_type") == QA_TASK_TYPE
                       and task.get("story_id") == story_id
                       and task.get("status") == "interrupted"
                       for task in self.tasks.values())

    def has_uncertain_qa_review_task(self, story_id: str) -> bool:
        with self.lock:
            return any(task.get("task_type") == QA_REVIEW_TASK_TYPE
                       and task.get("story_id") == story_id
                       and task.get("status") == "interrupted"
                       for task in self.tasks.values())

    def has_uncertain_evaluator_task(self, story_id: str, excluding_task_id: str | None = None) -> bool:
        with self.lock:
            return any(task.get("task_type") == EVALUATOR_TASK_TYPE
                       and task.get("story_id") == story_id
                       and task.get("status") == "interrupted"
                       and task.get("id") != excluding_task_id
                       for task in self.tasks.values())

    def has_uncertain_final_ci_task(self, story_id: str, excluding_task_id: str | None = None) -> bool:
        with self.lock:
            return any(task.get("task_type") == FINAL_CI_TASK_TYPE
                       and task.get("story_id") == story_id
                       and task.get("status") == "interrupted"
                       and task.get("id") != excluding_task_id
                       for task in self.tasks.values())

    def has_unfinished_publication_task(self, excluding_task_id: str | None = None) -> bool:
        with self.lock:
            return any(task.get("task_type") == "implementation_publication"
                       and task.get("status") in {"queued", "running", "interrupted"}
                       and task.get("id") != excluding_task_id
                       for task in self.tasks.values())

    def tasks_for(self, story_id: str, task_type: str) -> list[dict]:
        with self.lock:
            return [dict(task) for task in self.tasks.values()
                    if task.get("story_id") == story_id
                    and task.get("task_type") == task_type]

    def latest_implementation_task(self, story_id: str) -> dict | None:
        with self.lock:
            matches = [task for task in self.tasks.values()
                       if task.get("task_type") == "implementation"
                       and task.get("story_id") == story_id]
            return dict(max(matches, key=lambda task: task.get("created_at", ""))) if matches else None


class Bridge:
    def __init__(self, store: TaskStore, token: str, executable: Path = CLAUDE_EXECUTABLE,
                 command_prefix: tuple[str, ...] | None = None,
                 callback_origin: str = "http://localhost:5678",
                 codex_command_prefix: tuple[str, ...] | None = None):
        self.store = store
        self.token = token
        self.executable = executable
        self.command_prefix = command_prefix or (str(executable),)
        node_executable = str(CODEX_NODE_EXECUTABLE if CODEX_NODE_EXECUTABLE.exists() else (shutil.which("node") or CODEX_NODE_EXECUTABLE))
        self.codex_command_prefix = codex_command_prefix or (node_executable, str(CODEX_CLI_SCRIPT))
        self.callback_origin = callback_origin.rstrip("/")
        origin = urlparse(self.callback_origin)
        if origin.scheme != "http" or origin.hostname not in {"localhost", "127.0.0.1", "::1"} or origin.path or origin.query or origin.fragment:
            raise ValueError("GW2_N8N_CALLBACK_ORIGIN must be a local HTTP origin, for example http://localhost:5678.")
        self._callback_locks: dict[str, threading.Lock] = {}
        self._callback_locks_lock = threading.Lock()

    def start(self, task_id: str) -> None:
        threading.Thread(target=self._run, args=(task_id,), daemon=True).start()

    def start_qa_preparation(self, task_id: str) -> None:
        threading.Thread(target=self._run_qa_preparation, args=(task_id,), daemon=True).start()

    def start_qa_review(self, task_id: str) -> None:
        threading.Thread(target=self._run_qa_review, args=(task_id,), daemon=True).start()

    def start_evaluator(self, task_id: str) -> None:
        threading.Thread(target=self._run_evaluator, args=(task_id,), daemon=True).start()

    def start_final_ci(self, task_id: str) -> None:
        threading.Thread(target=self._run_final_ci, args=(task_id,), daemon=True).start()

    def _run_final_ci(self, task_id: str) -> None:
        import time

        task = self.store.update(task_id, status="running")
        logging.info("Starting final CI verification task %s for %s", task_id, task.get("story_id"))
        started = time.monotonic()
        try:
            from local_bridge.final_ci import execute_final_ci

            result = execute_final_ci(
                task["story_id"], task.get("ci_head_sha", ""), task["contract_sha256"],
                self.store.latest_implementation_task(task["story_id"]), self.store, task_id,
            )
            result["duration_seconds"] = round(time.monotonic() - started, 3)
            if result.get("status") == "UNVERIFIED":
                self.store.update(
                    task_id, status="failed", exit_code=1, result=result,
                    error={"category": "ci_unverified", "message": result.get("reason", "CI result is unverified."),
                           "recovery_action": "Inspect this task and its exact GitHub Actions run. Do not claim a pass or resubmit until the outcome is known."},
                )
            else:
                self.store.update(task_id, status="completed",
                                  exit_code=0 if result.get("status") == "PASSED" else 1,
                                  result=result, error=None)
            logging.info("Final CI task %s ended with %s", task_id, result.get("status"))
        except Exception as error:
            from local_bridge.final_ci import FinalCIRequestError

            category = "ci_contract_changed" if isinstance(error, FinalCIRequestError) else "ci_execution_failure"
            self.store.update(
                task_id, status="failed", exit_code=1, result=None,
                error={"category": category, "message": f"{type(error).__name__}: {error}",
                       "recovery_action": "Inspect the persisted task and exact GitHub Actions run. Final CI is not restarted automatically after interruption."},
            )
            logging.error("Final CI task %s failed (%s)", task_id, category)
        self.deliver_callback(task_id)

    def _run_evaluator(self, task_id: str) -> None:
        task = self.store.update(task_id, status="running")
        logging.info("Starting Evaluator task %s for %s", task_id, task.get("story_id"))
        try:
            from local_bridge.evaluation import execute_active_evaluation

            result = execute_active_evaluation(
                task["story_id"], task["contract_sha256"], task["id"],
                self.store.latest_implementation_task(task["story_id"]), self.store,
            )
            self.store.update(task_id, status="completed", exit_code=0,
                              result=result, error=None)
            logging.info("Evaluator task %s completed with %s", task_id, result.get("decision"))
        except Exception as error:
            from local_bridge.evaluation import EvaluationRequestError

            category = "evaluation_contract_changed" if isinstance(error, EvaluationRequestError) else (
                "invalid_evaluator_output" if isinstance(error, ValueError) else "evaluator_failure"
            )
            error_result = {
                "category": category,
                "message": f"{type(error).__name__}: {error}",
                "recovery_action": "Inspect the persisted evaluator task, active story, implementation record, QA plan, approved QA review and EVALUATOR_RESULT.json. An interrupted evaluator is not restarted automatically.",
            }
            self.store.update(task_id, status="failed", exit_code=1,
                              result=None, error=error_result)
            logging.error("Evaluator task %s failed (%s)", task_id, category)
        self.deliver_callback(task_id)

    def _run_qa_review(self, task_id: str) -> None:
        task = self.store.update(task_id, status="running")
        logging.info("Starting post-implementation QA review task %s for %s", task_id, task.get("story_id"))
        try:
            from local_bridge.qa_review import execute_active_story_review

            result = execute_active_story_review(
                task["story_id"], task["contract_sha256"], task["id"],
                self.store.latest_implementation_task(task["story_id"]),
            )
            self.store.update(task_id, status="completed", exit_code=0,
                              result=result, error=None)
            logging.info("Post-implementation QA review task %s completed with %s",
                         task_id, result.get("decision"))
        except Exception as error:
            from agent.runtime.qa import qa_agent

            category = "invalid_qa_output" if isinstance(error, qa_agent.QAPlanError) else "qa_review_failure"
            error_result = {
                "category": category,
                "message": f"{type(error).__name__}: {error}",
                "recovery_action": "Inspect the task, active story and validated QA plan. Do not resubmit an interrupted review; confirm whether its review was persisted first.",
            }
            self.store.update(task_id, status="failed", exit_code=1,
                              result=None, error=error_result)
            logging.error("Post-implementation QA review task %s failed (%s)", task_id, category)
        self.deliver_callback(task_id)

    def _run_qa_preparation(self, task_id: str) -> None:
        task = self.store.update(task_id, status="running")
        logging.info("Starting QA preparation task %s for %s", task_id, task.get("story_id"))
        try:
            from local_bridge.qa_preparation import execute_active_story

            result = execute_active_story(task["story_id"], task["contract_sha256"])
            self.store.update(task_id, status="completed", exit_code=0,
                              result=result, error=None)
            logging.info("QA preparation task %s completed with outcome %s",
                         task_id, result.get("qa_status"))
        except Exception as error:  # Persist a terminal technical result for n8n recovery.
            from agent.runtime.qa import qa_agent
            from agent.runtime.core import story_state

            if isinstance(error, qa_agent.QAPlanError):
                category = "invalid_qa_output"
            elif isinstance(error, qa_agent.QAFileProtectionError):
                category = "file_protection_violation"
            elif isinstance(error, (qa_agent.QARunnerError,)):
                category = "qa_runner_failure"
            else:
                category = "qa_infrastructure_failure"
            try:
                story_path = story_state.find_story_file_by_id(
                    task["story_id"], story_state.build_story_index()
                )
                if story_path is None:
                    raise FileNotFoundError("The QA story no longer exists in the canonical story index.")
                report = qa_agent.record_failure(
                    story_path,
                    category, f"{type(error).__name__}: {error}", model_retries=0,
                )
                from agent.runtime.support import config
                report_path = report.relative_to(config.REPO_ROOT).as_posix()
            except Exception as record_error:
                report_path = ""
                logging.error("Could not persist QA failure report (%s)", type(record_error).__name__)
            error_result = {
                "category": category,
                "message": f"{type(error).__name__}: {error}",
                "failure_report": report_path,
                "recovery_action": "Inspect the QA failure and workspace, then start a deliberate new request after resolving the cause. An interrupted request is never rerun automatically.",
            }
            self.store.update(task_id, status="failed", exit_code=1,
                              result=None, error=error_result)
            logging.error("QA preparation task %s failed (%s)", task_id, category)
        self.deliver_callback(task_id)

    def _run(self, task_id: str) -> None:
        task = self.store.get(task_id)
        if task and task.get("task_type") == "implementation":
            self._run_implementation(task_id)
            return

        self.store.update(task_id, status="running")
        task = self.store.get(task_id)
        agent = task.get("agent", "claude")
        logging.info("Starting %s task %s", agent, task_id)
        try:
            if agent == "codex":
                command = [*self.codex_command_prefix, "exec", "--sandbox", "read-only", "--cd",
                           str(REPOSITORY), "--ephemeral", "-"]
                prompt_input = task["prompt"]
            else:
                # Generic tasks are for harmless agent use. Implementation
                # writes must pass the separate contract-gated endpoint.
                command = [*self.command_prefix, "-p", "--permission-mode", "plan",
                           "--output-format", "text", task["prompt"]]
                prompt_input = None
            completed = subprocess.run(
                command, input=prompt_input,
                cwd=REPOSITORY, text=True, encoding="utf-8", errors="replace",
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False,
            )
            self.store.update(task_id, status="completed" if completed.returncode == 0 else "failed",
                              exit_code=completed.returncode, result=completed.stdout,
                              error=None if completed.returncode == 0 else f"{agent.capitalize()} exited with a non-zero status.")
            logging.info("%s task %s finished with exit code %s", agent, task_id, completed.returncode)
        except OSError as error:
            logging.error("Could not start %s task %s (%s)", agent, task_id, type(error).__name__)
            self.store.update(task_id, status="failed", error=f"Could not start {agent}: {error}")
        self.deliver_callback(task_id)

    def _run_implementation(self, task_id: str) -> None:
        """Run only the freshly validated implementation contract for this task."""
        from agent.runtime.core import story_state
        from agent.runtime.evaluation import implementation_contract
        from agent.runtime.qa import qa_agent
        from agent.runtime.support import config
        from agent.runtime.runners.claude_runner import run_claude_attempt

        self.store.update(task_id, status="running")
        task = self.store.get(task_id)
        output = ""
        try:
            contract = _active_implementation_contract(self.store)
            if (not contract.get("implementation_permitted")
                    or not contract.get("story")
                    or contract["story"].get("id") != task.get("story_id")
                    or contract.get("contract_sha256") != task.get("contract_sha256")):
                raise RuntimeError(
                    "Implementation contract changed or is no longer permitted before Claude started. "
                    "Fetch the active contract and review its prerequisites."
                )

            story_path = story_state.get_active_story_path()
            plan = qa_agent.load_plan(story_path)
            if not plan:
                raise RuntimeError("The validated QA plan disappeared before implementation started.")
            story_content = story_path.read_text(encoding="utf-8")
            plan = implementation_contract._validate_plan(
                plan, task["story_id"], story_content
            )
            unchanged, changed = qa_agent.plan_tests_unchanged(plan)
            if not unchanged:
                raise RuntimeError(
                    "Protected QA tests changed before implementation started: "
                    + ", ".join(changed)
                )

            plan_file = qa_agent.plan_path(task["story_id"])
            protected_files = {plan_file: plan_file.read_bytes()}
            for relative in plan.get("prepared_test_paths", []):
                test_file = config.REPO_ROOT / relative
                protected_files[test_file] = test_file.read_bytes()
                snapshot = config.ARTIFACTS_DIR / "qa-tests" / relative
                protected_files[snapshot] = snapshot.read_bytes()

            lifecycle_snapshot = story_state.snapshot_story_lifecycle(story_path)

            # The publication scope starts with a persisted pre-run Git
            # baseline, before Claude can make any repository changes.
            from local_bridge.publication import capture_implementation_baseline
            publication_metadata = capture_implementation_baseline(
                task_id, task["story_id"], contract,
            )
            self.store.update(task_id, publication_metadata=publication_metadata)

            attempt = None
            protected_changes = []
            lifecycle_changes = []
            try:
                attempt = run_claude_attempt(
                    contract["prompt"], label=f"Implementation {task['story_id']}"
                )
                output = attempt.output
            finally:
                try:
                    for path, original in protected_files.items():
                        current = path.read_bytes() if path.is_file() else None
                        if current != original:
                            protected_changes.append(path.relative_to(config.REPO_ROOT).as_posix())
                            path.parent.mkdir(parents=True, exist_ok=True)
                            path.write_bytes(original)
                finally:
                    # Restore only lifecycle records; implementation
                    # Result and Findings remain available for the reviewer.
                    lifecycle_changes = story_state.restore_story_lifecycle(
                        story_path, lifecycle_snapshot
                    )

            from local_bridge.publication import finalize_implementation_metadata
            publication_metadata = finalize_implementation_metadata(
                publication_metadata, protected_changes, lifecycle_changes,
            )
            self.store.update(task_id, publication_metadata=publication_metadata)

            if protected_changes:
                self.store.update(
                    task_id, status="failed", exit_code=1, result=output,
                    error={
                        "category": "protected_qa_artifact_modified",
                        "message": "Claude changed protected QA artifacts; their saved contents were restored: "
                                   + ", ".join(protected_changes),
                    },
                )
            elif lifecycle_changes:
                self.store.update(
                    task_id, status="failed", exit_code=1, result=output,
                    error={
                        "category": "implementation_lifecycle_modified",
                        "message": "Claude changed harness-owned story lifecycle state; the captured status, backlog and active pointer were restored: "
                                   + ", ".join(lifecycle_changes),
                    },
                )
            elif attempt.exit_code == 0:
                self.store.update(task_id, status="completed", exit_code=0,
                                  result=output, error=None)
            else:
                self.store.update(task_id, status="failed", exit_code=attempt.exit_code,
                                  result=output,
                                  error="Claude exited with a non-zero status.")
        except Exception as error:
            self.store.update(task_id, status="failed", exit_code=1, result=output,
                              error={"category": "implementation_execution_failure",
                                     "message": f"{type(error).__name__}: {error}"})
        self.deliver_callback(task_id)

    def resume_pending_callbacks(self) -> None:
        """Retry terminal callbacks left pending by a bridge restart or prior outage."""
        for task_id in self.store.pending_callbacks():
            threading.Thread(target=self.deliver_callback, args=(task_id,), daemon=True).start()

    def deliver_callback(self, task_id: str) -> None:
        # Prevent startup recovery and task completion from sending concurrently.
        with self._callback_locks_lock:
            callback_lock = self._callback_locks.setdefault(task_id, threading.Lock())
        # Serialize workers. If an old callback is in flight when replaced, its
        # worker notices the revision change and exits before retrying; the newer
        # worker then delivers to the replacement URL.
        callback_lock.acquire()
        try:
            task = self.store.get(task_id)
            if not task or task["status"] not in TERMINAL_STATUSES or not task.get("callback_url"):
                return
            revision = task.get("callback_revision", 0)
            payload = {key: task.get(key) for key in ("task_id", "status", "exit_code", "result", "error")}
            payload["task_id"] = task_id
            opener = build_opener(_NoRedirectHandler())
            last_error = "Callback delivery attempts were exhausted."
            for attempt, delay in enumerate(CALLBACK_RETRY_DELAYS, start=1):
                if delay:
                    threading.Event().wait(delay)
                task = self.store.get(task_id)
                if task.get("callback_revision", 0) != revision:
                    return
                if task.get("callback_delivery_status") == "delivered":
                    return
                attempts = task.get("callback_delivery_attempts", 0) + 1
                if not self.store.update_callback_delivery(
                        task_id, revision, callback_delivery_attempts=attempts,
                        callback_delivery_status="pending", callback_last_attempt_at=utc_now()):
                    return
                request = Request(task["callback_url"], data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
                                  headers={"Content-Type": "application/json"}, method="POST")
                try:
                    with opener.open(request, timeout=5) as response:
                        if 200 <= response.status < 300:
                            if not self.store.update_callback_delivery(
                                    task_id, revision, callback_delivery_status="delivered",
                                    callback_delivered_at=utc_now(), callback_last_error=None):
                                return
                            logging.info("Completion callback delivered for task %s", task_id)
                            return
                        last_error = f"HTTP {response.status}"
                except HTTPError as error:
                    last_error = f"HTTP {error.code}"
                except (URLError, TimeoutError, OSError) as error:
                    last_error = type(error).__name__
                logging.warning("Completion callback attempt %s for task %s failed (%s)", attempt, task_id, last_error)
            self.store.update_callback_delivery(task_id, revision,
                                                callback_delivery_status="delivery_unconfirmed",
                                                callback_last_error=last_error)
        finally:
            callback_lock.release()


class _NoRedirectHandler(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def make_handler(bridge: Bridge):
    class Handler(BaseHTTPRequestHandler):
        server_version = "GW2ClaudeBridge/1.0"

        def log_message(self, format_string: str, *args) -> None:
            logging.info("%s - %s", self.client_address[0], format_string % args)

        def _send(self, status: int, payload: dict) -> None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            if self.close_connection:
                self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)

        def _authorized(self) -> bool:
            supplied = self.headers.get("Authorization", "")
            if hmac.compare_digest(supplied, f"Bearer {bridge.token}"):
                return True
            self.close_connection = True
            self._send(401, {"error": "Missing or invalid bearer token."})
            return False

        def do_GET(self) -> None:
            path = urlparse(self.path).path
            if path == "/health":
                self._send(200, {"status": "ok"})
                return
            if path == "/stories/next":
                if not self._authorized():
                    return
                self._send(200, {**_next_story_payload(), "operation_source": "local_bridge_http"})
                return
            if path == "/stories/active/implementation-contract":
                if not self._authorized():
                    return
                self._send(200, {**_active_implementation_contract(bridge.store), "operation_source": "local_bridge_http"})
                return
            if path == "/stories/active/post-implementation-review-contract":
                if not self._authorized():
                    return
                from local_bridge.qa_review import inspect_active_review
                from agent.runtime.core import story_state

                try:
                    active_path = story_state.get_active_story_path()
                    active_id = story_state.extract_story_id(active_path.read_text(encoding="utf-8"))
                    implementation = bridge.store.latest_implementation_task(active_id)
                except (OSError, ValueError, RuntimeError, FileNotFoundError):
                    implementation = None
                self._send(200, {
                    **inspect_active_review(implementation),
                    "operation_source": "local_bridge_http",
                })
                return
            if path == "/stories/active/evaluation-contract":
                if not self._authorized():
                    return
                from local_bridge.evaluation import inspect_active_evaluation
                from agent.runtime.core import story_state

                try:
                    active_path = story_state.get_active_story_path()
                    active_id = story_state.extract_story_id(active_path.read_text(encoding="utf-8"))
                    implementation = bridge.store.latest_implementation_task(active_id)
                except (OSError, ValueError, RuntimeError, FileNotFoundError):
                    implementation = None
                self._send(200, {
                    **inspect_active_evaluation(implementation, bridge.store),
                    "operation_source": "local_bridge_http",
                })
                return
            if path == "/stories/active/final-ci-readiness":
                if not self._authorized():
                    return
                from local_bridge.final_ci import inspect_final_ci
                from agent.runtime.core import story_state

                try:
                    active_path = story_state.get_active_story_path()
                    active_id = story_state.extract_story_id(active_path.read_text(encoding="utf-8"))
                    implementation = bridge.store.latest_implementation_task(active_id)
                except (OSError, ValueError, RuntimeError, FileNotFoundError):
                    implementation = None
                self._send(200, {
                    **inspect_final_ci(implementation, bridge.store),
                    "operation_source": "local_bridge_http",
                })
                return
            if path == "/stories/active/publication-readiness":
                if not self._authorized():
                    return
                from local_bridge.publication import inspect_publication
                from agent.runtime.core import story_state
                try:
                    active_path = story_state.get_active_story_path()
                    active_id = story_state.extract_story_id(active_path.read_text(encoding="utf-8"))
                    implementation = bridge.store.latest_implementation_task(active_id)
                except (OSError, ValueError, RuntimeError, FileNotFoundError):
                    implementation = None
                self._send(200, {**inspect_publication(implementation, bridge.store),
                                 "operation_source": "local_bridge_http"})
                return
            if path == "/stories/active/finalization-readiness":
                if not self._authorized():
                    return
                from local_bridge.finalization import inspect_finalization
                from agent.runtime.core import story_state
                try:
                    active_path = story_state.get_active_story_path()
                    active_id = story_state.extract_story_id(active_path.read_text(encoding="utf-8"))
                except (OSError, ValueError, RuntimeError, FileNotFoundError):
                    active_id = None
                self._send(200, inspect_finalization(active_id, bridge.store))
                return
            if not path.startswith("/tasks/"):
                self._send(404, {"error": "Not found."})
                return
            if not self._authorized():
                return
            task_id = path.removeprefix("/tasks/")
            if not task_id or "/" in task_id:
                self._send(404, {"error": "Task not found."})
                return
            task = bridge.store.get(task_id)
            if not task:
                self._send(404, {"error": "Task not found."})
                return
            self._send(200, _public_task(task))

        def do_POST(self) -> None:
            path = urlparse(self.path).path
            if path == "/qa-preparations":
                self._submit_qa_preparation()
                return
            if path == "/qa-reviews":
                self._submit_qa_review()
                return
            if path == "/evaluations":
                self._submit_evaluation()
                return
            if path == "/final-ci-tasks":
                self._submit_final_ci()
                return
            if path == "/implementation-publications":
                self._submit_publication()
                return
            if path == "/story-finalizations":
                self._submit_finalization()
                return
            if path == "/stories/activate":
                try:
                    length = int(self.headers.get("Content-Length", "0"))
                    if length <= 0 or length > 4096:
                        raise ValueError("Request body is empty or too large.")
                    raw_body = self.rfile.read(length)
                except ValueError as error:
                    self._send(400, {"error": str(error)})
                    return
                if not self._authorized():
                    return
                try:
                    body = json.loads(raw_body)
                    if not isinstance(body, dict):
                        raise ValueError("Request body must be a JSON object.")
                    story_id = body.get("story_id")
                    filename = body.get("filename")
                    if not isinstance(story_id, str) or not story_id.strip():
                        raise ValueError("story_id must be a non-empty string.")
                    if not isinstance(filename, str) or not filename.strip():
                        raise ValueError("filename must be a non-empty string.")
                except (ValueError, json.JSONDecodeError) as error:
                    self._send(400, {"error": str(error)})
                    return
                try:
                    from agent.runtime.core import story_state

                    story_path, activation_status = story_state.activate_selected_story(
                        story_id.strip(), filename.strip()
                    )
                except (ValueError, RuntimeError, FileNotFoundError) as error:
                    self._send(409, {"error": str(error)})
                    return
                except OSError as error:
                    self._send(500, {
                        "error": "Activation recovery could not complete because a repository file could not be written. "
                                 "Preserve BACKLOG.md and CURRENT_STORY.md, then retry the same activation or reconcile manually.",
                    })
                    return
                self._send(200, {
                    "activation_status": activation_status,
                    "operation_source": "local_bridge_http",
                    **_story_details(story_path),
                })
                return

            if path not in {"/tasks", "/implementation-tasks"}:
                self._send(404, {"error": "Not found."})
                return
            implementation_task = path == "/implementation-tasks"
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length <= 0 or length > MAX_REQUEST_BODY_LENGTH:
                    raise ValueError("Request body is empty or too large.")
                raw_body = self.rfile.read(length)
            except ValueError as error:
                self._send(400, {"error": str(error)})
                return
            if not self._authorized():
                return
            try:
                body = json.loads(raw_body)
                prompt = body.get("prompt") if isinstance(body, dict) else None
                if (not implementation_task and
                        (not isinstance(prompt, str) or not prompt.strip() or len(prompt) > MAX_PROMPT_LENGTH)):
                    raise ValueError(f"prompt must be a non-empty string of at most {MAX_PROMPT_LENGTH} characters.")
                key = self.headers.get("Idempotency-Key", "")
                if not key or len(key) > 200:
                    raise ValueError("Idempotency-Key header is required (maximum 200 characters).")
                task_type = body.get("task_type", "generic") if isinstance(body, dict) else "generic"
                if task_type not in {"generic", "implementation"}:
                    raise ValueError("task_type must be generic or implementation.")
                if task_type == "implementation" and not implementation_task:
                    raise ValueError("Implementation tasks must use the implementation gate endpoint.")
                if implementation_task and task_type != "implementation":
                    raise ValueError("The implementation gate endpoint requires task_type=implementation.")
                callback_url = body.get("callback_url") if isinstance(body, dict) else None
                agent = body.get("agent", "claude") if isinstance(body, dict) else "claude"
                if agent not in SUPPORTED_AGENTS:
                    raise ValueError("agent must be one of: claude, codex.")
                if implementation_task:
                    story_id = body.get("story_id")
                    contract_sha256 = body.get("contract_sha256")
                    if agent != "claude":
                        raise ValueError("Story implementation tasks must use Claude.")
                    if not isinstance(story_id, str) or not story_id.strip():
                        raise ValueError("story_id is required for an implementation task.")
                    if not isinstance(contract_sha256, str) or not re.fullmatch(r"[0-9a-f]{64}", contract_sha256):
                        raise ValueError("contract_sha256 must be a SHA-256 hex digest.")
                    if not isinstance(body.get("callback_url"), str):
                        raise ValueError("callback_url is required for an implementation task.")
                if callback_url is not None:
                    callback_url = validate_callback_url(callback_url, bridge.callback_origin)
            except (ValueError, json.JSONDecodeError) as error:
                self._send(400, {"error": str(error)})
                return
            if implementation_task:
                existing = bridge.store.get_by_idempotency_key(key)
                if existing:
                    if (existing.get("task_type", "generic") != "implementation"
                            or existing.get("story_id") != story_id
                            or existing.get("contract_sha256") != contract_sha256
                            or existing.get("agent", "claude") != agent):
                        self._send(409, {"error": "Idempotency-Key belongs to a different implementation request."})
                        return
                    prompt = existing["prompt"]
                else:
                    contract = _active_implementation_contract(bridge.store)
                    if (not contract["implementation_permitted"]
                            or contract["story"].get("id") != story_id):
                        self._send(409, {
                            "error": "Implementation gate rejected the task.",
                            "preparation_status": contract["preparation_status"],
                            "outstanding_prerequisites": contract["outstanding_prerequisites"],
                        })
                        return
                    if contract["contract_sha256"] != contract_sha256:
                        self._send(409, {
                            "error": "Implementation contract changed after preparation. Fetch it again and review readiness.",
                            "preparation_status": "stale_contract",
                        })
                        return
                    prompt = contract["prompt"]
            try:
                task_prompt = prompt if implementation_task else prompt.strip()
                task, created = bridge.store.create(
                    task_prompt, key, callback_url, agent,
                    task_type="implementation" if implementation_task else "generic",
                    story_id=story_id if implementation_task else None,
                    contract_sha256=contract_sha256 if implementation_task else None,
                )
            except RuntimeError as error:
                self._send(409, {"error": str(error)})
                return
            if created:
                bridge.start(task["id"])
            elif callback_url and task["status"] in TERMINAL_STATUSES and task.get("callback_delivery_status") != "delivered":
                # Replay the persisted terminal result to a replacement Wait URL.
                threading.Thread(target=bridge.deliver_callback, args=(task["id"],), daemon=True).start()
            self._send(202, _public_task(
                task,
                submission_disposition=("created" if created else "idempotent_replay"),
            ))

        def _submit_qa_preparation(self) -> None:
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length <= 0 or length > 4096:
                    raise ValueError("Request body is empty or too large.")
                raw_body = self.rfile.read(length)
            except ValueError as error:
                self._send(400, {"error": str(error)})
                return
            if not self._authorized():
                return
            try:
                body = json.loads(raw_body)
                if not isinstance(body, dict) or set(body) != {"story_id", "story_contract_sha256", "callback_url"}:
                    raise ValueError("QA preparation requires only story_id, story_contract_sha256 and callback_url.")
                story_id = body.get("story_id")
                fingerprint = body.get("story_contract_sha256")
                callback_value = body.get("callback_url")
                if not isinstance(story_id, str) or not re.fullmatch(r"STORY-[A-Za-z0-9]+-\d+", story_id):
                    raise ValueError("story_id must be a canonical STORY identifier.")
                if not isinstance(fingerprint, str) or not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
                    raise ValueError("story_contract_sha256 must be a SHA-256 hex digest.")
                if not isinstance(callback_value, str):
                    raise ValueError("callback_url is required for asynchronous QA preparation.")
                callback_url = validate_callback_url(callback_value, bridge.callback_origin)
                key = self.headers.get("Idempotency-Key", "")
                if not key or len(key) > 200:
                    raise ValueError("Idempotency-Key header is required (maximum 200 characters).")
            except (ValueError, json.JSONDecodeError) as error:
                self._send(400, {"error": str(error)})
                return

            try:
                from local_bridge.qa_preparation import active_story_context, valid_persisted_plan, plan_result

                active_story_context(story_id, fingerprint)
                existing = bridge.store.get_by_idempotency_key(key)
                if existing and (existing.get("task_type") != QA_TASK_TYPE
                                 or existing.get("story_id") != story_id
                                 or existing.get("contract_sha256") != fingerprint):
                    raise RuntimeError("Idempotency-Key is already associated with a different request.")
                cached_plan = valid_persisted_plan(story_id, fingerprint)
                if (not existing and not cached_plan
                        and bridge.store.has_uncertain_qa_task(story_id)):
                    raise RuntimeError(
                        "A prior QA request for this story was interrupted with an uncertain outcome. "
                        "Inspect its task, QA_STATE.json, QA plan and any running Codex process before starting a new request."
                    )

                # A terminal replay returns its saved response directly, so it
                # does not replace a Wait URL or race a callback against Wait.
                callback_for_task = callback_url
                if (existing and existing.get("status") in TERMINAL_STATUSES) or (
                    cached_plan and not existing
                ):
                    callback_for_task = None
                prompt_key = f"QA preparation {story_id} {fingerprint}"
                task, created = bridge.store.create(
                    prompt_key, key, callback_for_task, agent="codex",
                    task_type=QA_TASK_TYPE, story_id=story_id,
                    contract_sha256=fingerprint,
                )
                disposition = "created" if created else "idempotent_replay"
                if cached_plan and (created or task.get("status") in {"interrupted", "failed"}):
                    task = bridge.store.update(
                        task["id"], status="completed", exit_code=0,
                        result=plan_result(cached_plan, source="persisted_qa_plan"),
                        error=None,
                    )
                    disposition = "cached_result"
                elif created:
                    bridge.start_qa_preparation(task["id"])
                task = _public_task(task, submission_disposition=disposition)
                task["operation_source"] = "local_bridge_http"
                self._send(202, task)
            except RuntimeError as error:
                self._send(409, {"error": str(error)})
            except (OSError, ValueError, KeyError, TypeError) as error:
                self._send(409, {"error": f"QA preparation could not be safely accepted: {type(error).__name__}: {error}"})

        def _submit_qa_review(self) -> None:
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length <= 0 or length > 4096:
                    raise ValueError("Request body is empty or too large.")
                raw_body = self.rfile.read(length)
            except ValueError as error:
                self._send(400, {"error": str(error)})
                return
            if not self._authorized():
                return
            try:
                body = json.loads(raw_body)
                if not isinstance(body, dict) or set(body) != {"story_id", "story_contract_sha256", "callback_url"}:
                    raise ValueError("QA review requires only story_id, story_contract_sha256 and callback_url.")
                story_id = body.get("story_id")
                fingerprint = body.get("story_contract_sha256")
                callback_value = body.get("callback_url")
                if not isinstance(story_id, str) or not re.fullmatch(r"STORY-[A-Za-z0-9]+-\d+", story_id):
                    raise ValueError("story_id must be a canonical STORY identifier.")
                if not isinstance(fingerprint, str) or not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
                    raise ValueError("story_contract_sha256 must be a SHA-256 hex digest.")
                if not isinstance(callback_value, str):
                    raise ValueError("callback_url is required for asynchronous QA review.")
                callback_url = validate_callback_url(callback_value, bridge.callback_origin)
                key = self.headers.get("Idempotency-Key", "")
                if not key or len(key) > 200:
                    raise ValueError("Idempotency-Key header is required (maximum 200 characters).")
            except (ValueError, json.JSONDecodeError) as error:
                self._send(400, {"error": str(error)})
                return

            try:
                from local_bridge.qa_review import (
                    active_review_context, persisted_review, persisted_review_plan,
                    review_contract_sha256,
                )

                implementation = bridge.store.latest_implementation_task(story_id)
                if not implementation or implementation.get("status") != "completed" or implementation.get("exit_code") != 0:
                    raise RuntimeError("A successful persisted implementation task for the active story is required before review.")
                # One review per implementation and validated QA contract.
                # A deliberate QA-owned contract correction can request a fresh
                # verdict while retries of that same contract remain idempotent.
                review_plan = persisted_review_plan(story_id, fingerprint)
                review_fingerprint = review_contract_sha256(review_plan, implementation["id"])
                key = f"post-review:{story_id}:{implementation['id']}:{review_fingerprint}"
                existing = bridge.store.get_by_idempotency_key(key)
                if existing and (existing.get("task_type") != QA_REVIEW_TASK_TYPE
                                 or existing.get("story_id") != story_id
                                 or existing.get("contract_sha256") != fingerprint):
                    raise RuntimeError("Idempotency-Key is already associated with a different request.")
                if (not existing and bridge.store.has_uncertain_qa_review_task(story_id)):
                    raise RuntimeError(
                        "A prior post-implementation review was interrupted with an uncertain outcome. "
                        "Inspect its task and the persisted QA plan before any deliberate retry."
                    )
                if not existing or existing.get("status") in {"queued", "running"}:
                    active_review_context(story_id, fingerprint, implementation)
                prompt_key = f"Post-implementation QA review {story_id} {fingerprint}"
                task, created = bridge.store.create(
                    prompt_key, key, callback_url, agent="codex",
                    task_type=QA_REVIEW_TASK_TYPE, story_id=story_id,
                    contract_sha256=fingerprint,
                )
                if existing and existing.get("status") == "interrupted":
                    persisted = persisted_review(story_id, existing["id"])
                    if persisted:
                        task = bridge.store.update(
                            task["id"], status="completed", exit_code=0,
                            result={"story_id": story_id, **persisted,
                                    "result_source": "persisted_qa_review"}, error=None,
                        )
                        created = False
                    else:
                        raise RuntimeError(
                            "This review was interrupted before its outcome was persisted. "
                            "Inspect the task and workspace; it will not be restarted automatically."
                        )
                if created:
                    bridge.start_qa_review(task["id"])
                elif task["status"] in TERMINAL_STATUSES and task.get("callback_delivery_status") != "delivered":
                    threading.Thread(target=bridge.deliver_callback, args=(task["id"],), daemon=True).start()
                public_task = _public_task(
                    task, submission_disposition=("created" if created else "idempotent_replay")
                )
                public_task["operation_source"] = "local_bridge_http"
                self._send(202, public_task)
            except RuntimeError as error:
                self._send(409, {"error": str(error)})
            except (OSError, ValueError, KeyError, TypeError) as error:
                self._send(409, {"error": f"QA review could not be safely accepted: {type(error).__name__}: {error}"})

        def _submit_evaluation(self) -> None:
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length <= 0 or length > 4096:
                    raise ValueError("Request body is empty or too large.")
                raw_body = self.rfile.read(length)
            except ValueError as error:
                self._send(400, {"error": str(error)})
                return
            if not self._authorized():
                return
            try:
                body = json.loads(raw_body)
                required = {"story_id", "evaluation_contract_sha256", "callback_url"}
                if not isinstance(body, dict) or set(body) != required:
                    raise ValueError("Evaluation requires only story_id, evaluation_contract_sha256 and callback_url.")
                story_id = body.get("story_id")
                fingerprint = body.get("evaluation_contract_sha256")
                callback_value = body.get("callback_url")
                if not isinstance(story_id, str) or not re.fullmatch(r"STORY-[A-Za-z0-9]+-\d+", story_id):
                    raise ValueError("story_id must be a canonical STORY identifier.")
                if not isinstance(fingerprint, str) or not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
                    raise ValueError("evaluation_contract_sha256 must be a SHA-256 hex digest.")
                if not isinstance(callback_value, str):
                    raise ValueError("callback_url is required for asynchronous evaluation.")
                callback_url = validate_callback_url(callback_value, bridge.callback_origin)
                supplied_key = self.headers.get("Idempotency-Key", "")
                expected_key = f"gw2-evaluate-{story_id}-{fingerprint}"
                if supplied_key != expected_key:
                    raise ValueError("Idempotency-Key must bind the active story and evaluation contract.")
            except (ValueError, json.JSONDecodeError) as error:
                self._send(400, {"error": str(error)})
                return

            try:
                from local_bridge.evaluation import (
                    EvaluationRequestError, active_evaluation_context,
                    inspect_active_evaluation, persisted_evaluation,
                )

                implementation = bridge.store.latest_implementation_task(story_id)
                key = f"evaluator:{story_id}:{fingerprint}"
                existing = bridge.store.get_by_idempotency_key(key)
                if existing and (existing.get("task_type") != EVALUATOR_TASK_TYPE
                                 or existing.get("story_id") != story_id
                                 or existing.get("contract_sha256") != fingerprint):
                    raise RuntimeError("Idempotency-Key is already associated with a different evaluation request.")
                readiness = inspect_active_evaluation(
                    implementation, bridge.store,
                    allow_task_id=existing.get("id") if existing else None,
                )
                if not readiness.get("evaluation_permitted") or readiness.get("story", {}).get("id") != story_id:
                    self._send(409, {"error": "Evaluator gate rejected the request.", **readiness})
                    return
                if readiness.get("evaluation_contract_sha256") != fingerprint:
                    self._send(409, {"error": "The implementation or QA contract changed. Fetch the evaluation contract again.", **readiness})
                    return

                if bridge.store.has_uncertain_evaluator_task(story_id, existing.get("id") if existing else None):
                    raise RuntimeError("A different Evaluator task was interrupted with an uncertain outcome. Inspect it before a deliberate recovery.")

                active_evaluation_context(
                    story_id, fingerprint, implementation, bridge.store,
                    existing.get("id") if existing else None,
                )
                task, created = bridge.store.create(
                    f"Evaluate {story_id} {fingerprint}", key, callback_url,
                    agent="codex", task_type=EVALUATOR_TASK_TYPE,
                    story_id=story_id, contract_sha256=fingerprint,
                )
                if task.get("status") == "interrupted":
                    persisted = persisted_evaluation(task["id"], fingerprint, story_id)
                    if persisted:
                        task = bridge.store.update(task["id"], status="completed", exit_code=0,
                                                   result=persisted, error=None)
                        created = False
                    else:
                        raise RuntimeError("This Evaluator task was interrupted before its result was persisted. Inspect it; Codex will not be restarted automatically.")
                if created:
                    bridge.start_evaluator(task["id"])
                elif task["status"] in TERMINAL_STATUSES and task.get("callback_delivery_status") != "delivered":
                    threading.Thread(target=bridge.deliver_callback, args=(task["id"],), daemon=True).start()
                public = _public_task(task, submission_disposition="created" if created else "idempotent_replay")
                public["operation_source"] = "local_bridge_http"
                self._send(202, public)
            except EvaluationRequestError as error:
                self._send(409, {"error": str(error)})
            except RuntimeError as error:
                self._send(409, {"error": str(error)})
            except (OSError, ValueError, KeyError, TypeError) as error:
                self._send(409, {"error": f"Evaluator task could not be safely accepted: {type(error).__name__}: {error}"})

        def _submit_finalization(self) -> None:
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length <= 0 or length > 4096:
                    raise ValueError("Request body is empty or too large.")
                raw_body = self.rfile.read(length)
            except ValueError as error:
                self._send(400, {"error": str(error)})
                return
            if not self._authorized():
                return
            try:
                body = json.loads(raw_body)
                if not isinstance(body, dict) or set(body) != {"story_id", "finalization_contract_sha256"}:
                    raise ValueError("Finalization requires story_id and finalization_contract_sha256.")
                story_id = body.get("story_id")
                fingerprint = body.get("finalization_contract_sha256")
                if not isinstance(story_id, str) or not re.fullmatch(r"STORY-[A-Za-z0-9]+-\d+", story_id):
                    raise ValueError("story_id must be a canonical STORY identifier.")
                if not isinstance(fingerprint, str) or not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
                    raise ValueError("finalization_contract_sha256 must be a SHA-256 hex digest.")
                if self.headers.get("Idempotency-Key", "") != f"gw2-finalize-{story_id}-{fingerprint}":
                    raise ValueError("Idempotency-Key must bind the story and finalization contract.")
            except (ValueError, json.JSONDecodeError) as error:
                self._send(400, {"error": str(error)})
                return
            try:
                from local_bridge.finalization import finalize_story
                self._send(200, finalize_story(story_id, fingerprint, bridge.store))
            except (ValueError, RuntimeError, OSError) as error:
                self._send(409, {"error": str(error), "recovery_required": True,
                                 "operation_source": "local_bridge_http"})

        def _submit_final_ci(self) -> None:
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length <= 0 or length > 4096:
                    raise ValueError("Request body is empty or too large.")
                raw_body = self.rfile.read(length)
            except ValueError as error:
                self._send(400, {"error": str(error)})
                return
            if not self._authorized():
                return
            try:
                body = json.loads(raw_body)
                required = {"story_id", "head_sha", "final_ci_contract_sha256", "callback_url"}
                if not isinstance(body, dict) or set(body) != required:
                    raise ValueError("Final CI requires only story_id, head_sha, final_ci_contract_sha256 and callback_url.")
                story_id = body.get("story_id")
                head_sha = body.get("head_sha")
                fingerprint = body.get("final_ci_contract_sha256")
                callback_value = body.get("callback_url")
                if not isinstance(story_id, str) or not re.fullmatch(r"STORY-[A-Za-z0-9]+-\d+", story_id):
                    raise ValueError("story_id must be a canonical STORY identifier.")
                if not isinstance(head_sha, str) or not re.fullmatch(r"[0-9a-f]{40,64}", head_sha):
                    raise ValueError("head_sha must be a full Git commit SHA.")
                if not isinstance(fingerprint, str) or not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
                    raise ValueError("final_ci_contract_sha256 must be a SHA-256 hex digest.")
                if not isinstance(callback_value, str):
                    raise ValueError("callback_url is required for asynchronous final CI.")
                callback_url = validate_callback_url(callback_value, bridge.callback_origin)
                supplied_key = self.headers.get("Idempotency-Key", "")
                expected_key = f"gw2-final-ci-{story_id}-{fingerprint}"
                if supplied_key != expected_key:
                    raise ValueError("Idempotency-Key must bind the active story and final CI contract.")
            except (ValueError, json.JSONDecodeError) as error:
                self._send(400, {"error": str(error)})
                return

            try:
                from local_bridge.final_ci import (
                    CI_TASK_TYPE, FinalCIRequestError, inspect_final_ci,
                )
                from agent.runtime.support.github_ci import APPLICATION_CI_VERDICT_POLICY
                from agent.runtime.core import story_state

                active_path = story_state.get_active_story_path()
                active_id = story_state.extract_story_id(active_path.read_text(encoding="utf-8"))
                if active_id != story_id:
                    raise FinalCIRequestError("The requested story is no longer active.")
                implementation = bridge.store.latest_implementation_task(story_id)
                key = f"final-ci:{story_id}:{fingerprint}"
                existing = bridge.store.get_by_idempotency_key(key)
                if existing and (existing.get("task_type") != FINAL_CI_TASK_TYPE
                                 or existing.get("story_id") != story_id
                                 or existing.get("contract_sha256") != fingerprint
                                 or existing.get("ci_head_sha") != head_sha):
                    raise RuntimeError("Idempotency-Key is already associated with a different final CI request.")
                readiness = inspect_final_ci(
                    implementation, bridge.store,
                    allow_task_id=existing.get("id") if existing else None,
                )
                if not readiness.get("ci_permitted") or readiness.get("story", {}).get("id") != story_id:
                    self._send(409, {"error": "Final CI gate rejected the request.", **readiness})
                    return
                if readiness.get("head_sha") != head_sha or readiness.get("final_ci_contract_sha256") != fingerprint:
                    self._send(409, {"error": "The final CI contract or published commit changed. Fetch readiness again.", **readiness})
                    return
                if bridge.store.has_uncertain_final_ci_task(story_id, existing.get("id") if existing else None):
                    raise RuntimeError("A different final CI task was interrupted with an uncertain outcome. Inspect it before recovery.")

                prior_result = existing.get("result") if existing else None
                recheck_legacy_ci_verdict = bool(
                    existing
                    and existing.get("status") == "completed"
                    and isinstance(prior_result, dict)
                    and prior_result.get("status") == "FAILED"
                    and prior_result.get("result_source") == "github_actions_for_pushed_commit"
                    and prior_result.get("story_id") == story_id
                    and prior_result.get("final_ci_contract_sha256") == fingerprint
                    and prior_result.get("sha") == head_sha
                    and prior_result.get("application_verdict_policy") != APPLICATION_CI_VERDICT_POLICY
                )
                replay_is_terminal = bool(
                    existing and existing.get("status") in TERMINAL_STATUSES
                    and not recheck_legacy_ci_verdict
                )
                task, created = bridge.store.create(
                    f"Final CI {story_id} {head_sha}", key,
                    None if replay_is_terminal else callback_url,
                    agent="ci", task_type=CI_TASK_TYPE, story_id=story_id,
                    contract_sha256=fingerprint,
                )
                if created:
                    task = bridge.store.update(task["id"], ci_head_sha=head_sha)
                    bridge.start_final_ci(task["id"])
                elif recheck_legacy_ci_verdict:
                    # Re-evaluate the already completed GitHub run under the
                    # application-job policy. This queries the same commit;
                    # it does not dispatch CI or rerun its jobs.
                    task = bridge.store.update(
                        task["id"], status="queued", result=None, exit_code=None, error=None,
                    )
                    bridge.start_final_ci(task["id"])
                elif task.get("status") == "interrupted":
                    recovered = task.get("result")
                    if (isinstance(recovered, dict)
                            and recovered.get("story_id") == story_id
                            and recovered.get("final_ci_contract_sha256") == fingerprint
                            and recovered.get("sha") == head_sha
                            and recovered.get("status") in {"PASSED", "FAILED", "UNVERIFIED"}):
                        task = bridge.store.update(
                            task["id"], status="failed" if recovered["status"] == "UNVERIFIED" else "completed",
                            exit_code=0 if recovered["status"] == "PASSED" else 1,
                            error=None if recovered["status"] != "UNVERIFIED" else {
                                "category": "ci_unverified",
                                "message": recovered.get("reason", "CI outcome is unverified."),
                                "recovery_action": "Inspect the exact GitHub Actions run before further action.",
                            },
                        )
                    else:
                        task = bridge.store.update(
                            task["id"], result=None, error={
                                "category": "ci_interrupted",
                                "message": "Final CI was interrupted before its result was persisted.",
                                "recovery_action": "Inspect this task and its exact GitHub Actions run. Do not resubmit until the run outcome is known; the bridge will not restart CI automatically.",
                            },
                        )
                public = _public_task(task, submission_disposition="created" if created else "idempotent_replay")
                public["operation_source"] = "local_bridge_http"
                self._send(202, public)
            except FinalCIRequestError as error:
                self._send(409, {"error": str(error)})
            except RuntimeError as error:
                self._send(409, {"error": str(error)})
            except (OSError, ValueError, KeyError, TypeError) as error:
                self._send(409, {"error": f"Final CI task could not be safely accepted: {type(error).__name__}: {error}"})

        def _submit_publication(self) -> None:
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length <= 0 or length > 4096:
                    raise ValueError("Request body is empty or too large.")
                raw_body = self.rfile.read(length)
            except ValueError as error:
                self._send(400, {"error": str(error)})
                return
            if not self._authorized():
                return
            try:
                body = json.loads(raw_body)
                if not isinstance(body, dict) or set(body) != {"story_id", "publication_contract_sha256"}:
                    raise ValueError("Publication requires story_id and publication_contract_sha256.")
                story_id = body.get("story_id")
                fingerprint = body.get("publication_contract_sha256")
                if not isinstance(story_id, str) or not re.fullmatch(r"STORY-[A-Za-z0-9]+-\d+", story_id):
                    raise ValueError("story_id must be a canonical STORY identifier.")
                if not isinstance(fingerprint, str) or not re.fullmatch(r"[0-9a-f]{64}", fingerprint):
                    raise ValueError("publication_contract_sha256 must be a SHA-256 hex digest.")
                key = self.headers.get("Idempotency-Key", "")
                if key != f"gw2-publish-{story_id}-{fingerprint}":
                    raise ValueError("Idempotency-Key must bind the story and publication contract.")
            except (ValueError, json.JSONDecodeError) as error:
                self._send(400, {"error": str(error)})
                return
            from local_bridge.publication import (inspect_publication, publish_implementation,
                                                  verify_already_published, PUBLICATION_TASK_TYPE)
            from agent.runtime.core import story_state
            try:
                active_path = story_state.get_active_story_path()
                active_id = story_state.extract_story_id(active_path.read_text(encoding="utf-8"))
                implementation = bridge.store.latest_implementation_task(active_id)
                readiness = inspect_publication(implementation, bridge.store)
                if active_id != story_id or not readiness.get("publication_permitted"):
                    self._send(409, {"error": "Publication gate rejected the request.", **readiness})
                    return
                # Readiness owns the publication fingerprint. Normal tasks bind
                # their baseline and scope there; the explicitly validated
                # legacy adoption binds its story/task/commit/path/QA/Evaluator
                # record instead and intentionally has no baseline_head_sha.
                calculated = readiness.get("publication_contract_sha256")
                if calculated != fingerprint:
                    self._send(409, {"error": "Publication scope changed; fetch readiness again.", **readiness})
                    return
                existing = bridge.store.get_by_idempotency_key(key)
                if existing and existing.get("task_type") != PUBLICATION_TASK_TYPE:
                    raise RuntimeError("Idempotency-Key belongs to another task type.")
                if existing and existing.get("status") == "interrupted":
                    sha = verify_already_published(story_id, implementation.get("publication_metadata", {}))
                    if sha:
                        recovered = {"status": "ALREADY_PUBLISHED", "story_id": story_id,
                                     "sha": sha, "push_status": "verified"}
                        task = bridge.store.update(existing["id"], status="completed", exit_code=0,
                                                   result=recovered, error=None)
                        self._send(200, _public_task(task, submission_disposition="recovered_published"))
                    else:
                        self._send(409, {"error": "Publication was interrupted without proof of remote publication; inspect Git and origin before recovery.",
                                         "task_id": existing["id"], "recovery_required": True})
                    return
                if existing and existing.get("status") in TERMINAL_STATUSES:
                    prior = existing.get("result") if isinstance(existing.get("result"), dict) else {}
                    if (existing.get("status") == "failed"
                            and prior.get("status") == "PUSH_REJECTED"):
                        # Retry only the same recorded implementation commit.
                        # Readiness below binds the baseline and exact changed paths.
                        task = existing
                    else:
                        self._send(200, _public_task(existing, submission_disposition="idempotent_replay"))
                        return
                if existing and existing.get("status") in {"queued", "running", "interrupted"}:
                    self._send(409, {"error": "A publication task has an uncertain or unfinished outcome; inspect its persisted record and Git remote before retrying.",
                                     "task_id": existing["id"], "recovery_required": True})
                    return
                if bridge.store.has_unfinished_publication_task(existing.get("id") if existing else None):
                    raise RuntimeError("A different publication task is unfinished or interrupted; inspect it before publishing another story.")
                if existing and existing.get("status") == "failed":
                    task, created = existing, False
                else:
                    task, created = bridge.store.create(
                        f"Publish implementation {story_id} {fingerprint}", key,
                        task_type=PUBLICATION_TASK_TYPE, story_id=story_id,
                        contract_sha256=fingerprint,
                    )
                bridge.store.update(task["id"], status="running")
                try:
                    result = publish_implementation(implementation, bridge.store)
                    status = "completed" if result.get("status") in {"PUBLISHED", "ALREADY_PUBLISHED"} else "failed"
                    task = bridge.store.update(task["id"], status=status,
                                               exit_code=0 if status == "completed" else 1,
                                               result=result, error=None if status == "completed" else result)
                except Exception as error:
                    task = bridge.store.update(task["id"], status="failed", exit_code=1,
                                               error={"category": "publication_failure",
                                                      "message": f"{type(error).__name__}: {error}",
                                                      "recovery_action": "Inspect the Git index, HEAD and origin branch before retrying."})
                # Return terminal task records as JSON even on publication
                # failure so n8n can report a created SHA and recovery details.
                self._send(200, _public_task(task,
                                             submission_disposition="created" if created else "idempotent_replay"))
            except (OSError, ValueError, KeyError, TypeError, RuntimeError) as error:
                self._send(409, {"error": f"Publication could not be safely accepted: {type(error).__name__}: {error}",
                                 "recovery_required": True})

    return Handler


def _public_task(task: dict, submission_disposition: str | None = None) -> dict:
    public = {key: value for key, value in task.items()
              if key not in {"id", "prompt", "callback_url", "callback_revision"}}
    public["task_id"] = task["id"]
    public["record_source"] = "persisted_bridge_task"
    if submission_disposition is not None:
        public["submission_disposition"] = submission_disposition
    return public


def _next_story_payload() -> dict:
    """Expose the existing deterministic selector without its artifact write."""
    from agent.runtime.core import selector, story_state

    candidates = selector.get_selectable_story_candidates()
    if not candidates:
        # This helper only reads backlog/story/decision evidence. The public
        # select_next_story() wrapper is intentionally avoided because it
        # writes SELECTOR_RESULT_FILE on every call.
        empty = selector._no_candidates_result()
        return {
            "queue_empty": True,
            "decision": empty["decision"],
            "reason": empty["reason"],
            "story": None,
            "eligibility": {"eligible": False},
            "dependencies": None,
        }

    chosen = candidates[0]
    details = _story_details(chosen)
    return {
        "queue_empty": False,
        "decision": "NEXT",
        "reason": "First eligible story in BACKLOG.md's To Do order.",
        **details,
    }


def _story_details(story_path: Path) -> dict:
    from agent.runtime.core import story_state
    from agent.runtime.support.files import read_file
    from agent.runtime.human.user_decisions import extract_section

    content = read_file(story_path)
    dependencies = story_state.extract_dependencies_section(content)
    unsatisfied = story_state.get_unsatisfied_dependencies(content)
    return {
        "story": {
            "id": story_state.extract_story_id(content),
            "filename": story_path.name,
            "title": extract_section(content, "Title") or story_path.stem,
            "content": content,
        },
        "eligibility": {
            "eligible": not unsatisfied and story_state.classify_story_status(
                story_state.extract_status_section(content)
            ) not in {"DONE", "BLOCKED", "UNFINISHED", "SUPERSEDED"},
            "status": story_state.classify_story_status(
                story_state.extract_status_section(content)
            ),
        },
        "dependencies": {
            "declared": dependencies or "None.",
            "story_ids": story_state.parse_dependency_ids(dependencies),
            "satisfied": not unsatisfied,
            "unsatisfied": unsatisfied,
        },
    }


def _active_implementation_contract(task_store: TaskStore | None = None) -> dict:
    from agent.runtime.evaluation.implementation_contract import inspect_active_story

    try:
        contract = inspect_active_story()
        story = contract.get("story") or {}
        if task_store and story.get("id"):
            previous = task_store.latest_implementation_task(story["id"])
            if previous and previous.get("status") == "completed" and previous.get("exit_code") == 0:
                if previous.get("contract_sha256") == contract.get("contract_sha256"):
                    contract.update(
                        preparation_status="implementation_already_completed",
                        implementation_permitted=False,
                        implementation_task_id=previous.get("id"),
                        implementation_task_contract_sha256=previous.get("contract_sha256"),
                        prompt=None,
                        outstanding_prerequisites=[
                            "A successful implementation task is already persisted for this active story. Complete its post-implementation review before any deliberate new implementation attempt."
                        ],
                    )
                else:
                    contract.update(
                        preparation_status="stale_implementation_task",
                        implementation_permitted=False,
                        implementation_task_id=previous.get("id"),
                        implementation_task_contract_sha256=previous.get("contract_sha256"),
                        prompt=None,
                        outstanding_prerequisites=[
                            "The persisted implementation task was created under a different implementation contract. Inspect the contract and task before proceeding; do not rerun implementation automatically."
                        ],
                    )
        return contract
    except (OSError, ValueError, TypeError, KeyError) as error:
        logging.error("Implementation contract inspection failed (%s)", type(error).__name__)
        return {
            "preparation_status": "invalid_implementation_contract",
            "implementation_permitted": False,
            "outstanding_prerequisites": [
                "The active-story implementation contract could not be validated; inspect repository and QA artifacts before retrying."
            ],
            "story": None,
            "qa": {"status": "invalid"},
            "instruction_files": [],
            "prompt": None,
            "contract_sha256": None,
        }


def main() -> None:
    token = os.environ.get("GW2_BRIDGE_TOKEN", "")
    if len(token) < 32:
        raise SystemExit("Set GW2_BRIDGE_TOKEN to a randomly generated token of at least 32 characters.")
    host = os.environ.get("GW2_BRIDGE_HOST", "127.0.0.1")
    callback_origin = os.environ.get("GW2_N8N_CALLBACK_ORIGIN", "http://localhost:5678")
    state_file = Path(os.environ.get("GW2_BRIDGE_STATE", str(Path.home() / ".gw2-claude-bridge" / "tasks.json")))
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    bridge = Bridge(TaskStore(state_file), token, callback_origin=callback_origin)
    bridge.resume_pending_callbacks()
    server = ThreadingHTTPServer((host, 8765), make_handler(bridge))
    logging.info("Bridge listening on %s:8765", host)
    server.serve_forever()


if __name__ == "__main__":
    main()
