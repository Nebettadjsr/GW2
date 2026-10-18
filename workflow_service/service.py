"""Persistent workflow executor and authenticated status API, with legacy n8n support."""

from __future__ import annotations

import copy
import hmac
import json
import logging
import os
import re
import shutil
import subprocess
import sys
import threading
import time
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

from workflow_service import claude_budget, cycle as dev_cycle, status as status_view  # noqa: E402
from agent.runtime.core import backlog_writer  # noqa: E402
from agent.runtime.support.capacity import ModelCapacityUnavailable  # noqa: E402
from agent.runtime.support.daily_log import log_line  # noqa: E402

CLAUDE_EXECUTABLE = Path(r"C:\Users\Administrator\.local\bin\claude.EXE")
CODEX_NODE_EXECUTABLE = Path(r"C:\Program Files\nodejs\node.exe")
CODEX_CLI_SCRIPT = Path.home() / "AppData" / "Roaming" / "npm" / "node_modules" / "@openai" / "codex" / "bin" / "codex.js"
MAX_PROMPT_LENGTH = 20_000
MAX_BODY_LENGTH = MAX_PROMPT_LENGTH + 4096
CALLBACK_RETRY_DELAYS = (0, 0.25, 0.5, 1, 2, 4, 8)
# start-service.cmd starts the service again when it exits with this code.
RESTART_EXIT_CODE = 3
ACTIVE_STATUSES = {"queued", "running"}
BUDGET_REFRESH_SECONDS = 15 * 60


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def validate_callback_url(callback_url, callback_origin: str) -> str:
    """Accept only signed Wait-node resume URLs on the configured local n8n origin."""
    if not isinstance(callback_url, str) or len(callback_url) > 2048:
        raise ValueError("callback_url is required and must be at most 2048 characters.")
    candidate, allowed = urlparse(callback_url), urlparse(callback_origin)
    if (candidate.scheme, candidate.hostname, candidate.port) != (allowed.scheme, allowed.hostname, allowed.port):
        raise ValueError("callback_url must use the configured local n8n origin.")
    if candidate.username or candidate.password or candidate.fragment:
        raise ValueError("callback_url must be a plain local HTTP resume URL.")
    if not re.fullmatch(r"/webhook-waiting/[^/?#]+(?:/[^/?#]+)?", candidate.path):
        raise ValueError("callback_url must be an n8n Wait-node resume URL.")
    query = parse_qs(candidate.query, keep_blank_values=True)
    if set(query) != {"signature"} or len(query["signature"]) != 1 or not query["signature"][0]:
        raise ValueError("callback_url must include n8n's resume signature.")
    return callback_url


class Busy(RuntimeError):
    """Another task is running; the service runs one at a time."""


class Store:
    """Tasks plus per-story cycle records, persisted atomically as one JSON file."""

    def __init__(self, path: Path):
        self.path = path
        self.lock = threading.RLock()
        data = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
        self.tasks: dict[str, dict] = data.get("tasks", {})
        self.cycles: dict[str, dict] = data.get("cycles", {})
        self.active: str | None = data.get("active")
        self.planning: dict = data.get("planning", {})
        self.control: dict = data.get("control", data.get("runner", {}))
        self.budget: dict = data.get("budget") or {
            "settings": claude_budget.settings_from(None),
            "checked_at": None, "result": None, "error": None,
        }
        self.budget["settings"] = claude_budget.settings_from(self.budget.get("settings"))
        for task in self.tasks.values():
            if task.get("status") in ACTIVE_STATUSES:
                # Its outcome is unknown. A cycle step is simply run again;
                # an interrupted implementation resumes with a continuation prompt.
                task.update(status="interrupted", updated_at=utc_now(),
                            error="The service restarted while this task was running.")
        self.save()

    def save(self) -> None:
        with self.lock:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            temporary = self.path.with_suffix(self.path.suffix + ".tmp")
            temporary.write_text(json.dumps(
                {"tasks": self.tasks, "cycles": self.cycles, "active": self.active,
                 "planning": self.planning, "budget": self.budget, "control": self.control},
                ensure_ascii=False), encoding="utf-8")
            os.replace(temporary, self.path)

    def create(self, *, track_control: bool = False, **fields) -> dict:
        with self.lock:
            if self.running():
                raise Busy("A service task is already running; wait for it to finish.")
            task = {"id": str(uuid.uuid4()), "status": "running", "result": None, "error": None,
                    "callback_url": None, "callback_delivered": False,
                    "created_at": utc_now(), "updated_at": utc_now(), **fields}
            self.tasks[task["id"]] = task
            if track_control:
                self.control.update(task_id=task["id"], steps=self.control.get("steps", 0) + 1)
            self.save()
            return dict(task)

    def update(self, task_id: str, **values) -> dict:
        with self.lock:
            task = self.tasks[task_id]
            task.update(values, updated_at=utc_now())
            self.save()
            return dict(task)

    def get(self, task_id: str) -> dict | None:
        with self.lock:
            task = self.tasks.get(task_id)
            return dict(task) if task else None

    def running(self) -> dict | None:
        with self.lock:
            return next((dict(t) for t in self.tasks.values() if t.get("status") in ACTIVE_STATUSES), None)

    def active_cycle(self) -> dict | None:
        with self.lock:
            return self.cycles.get(self.active) if self.active else None

    def save_cycle(self, cycle: dict) -> None:
        with self.lock:
            if cycle["phase"] in ("done", "blocked"):
                dev_cycle.clear_claude_session(cycle)  # a finished or handed-off story keeps no session
            self.cycles[cycle["story_id"]] = cycle
            self.active = cycle["story_id"] if cycle["phase"] not in ("done", "blocked") else None
            self.save()

    def drop_active(self) -> None:
        with self.lock:
            self.active = None
            self.save()


def public_task(task: dict) -> dict:
    """Public task identity, status and the step's result fields."""
    view = {"task_id": task["id"], "status": task["status"], "kind": task.get("kind"),
            "step": task.get("step"), "story_id": task.get("story_id"), "error": task.get("error")}
    view.update(created_at=task.get("created_at"), updated_at=task.get("updated_at"))
    result = task.get("result")
    if isinstance(result, dict):
        view.update(result)
    elif result is not None:
        view["result"] = result
    view.setdefault("proceed", False)
    return view


class WorkflowService:
    def cycle_status(self) -> dict:
        """Include terminal work even after blocking clears the active-story pointer."""
        with self.store.lock:
            tasks = [t for t in self.store.tasks.values() if t.get("kind") == "step"
                     and t.get("status") not in ACTIVE_STATUSES]
            task = max(tasks, key=lambda t: t.get("created_at", ""), default=None)
            last = public_task(task) if task else None
            control = copy.deepcopy(self.store.control)
            snapshot = copy.deepcopy({
                "active_cycle": self.store.active_cycle(),
                "running_task": public_task(running) if (running := self.store.running()) else None,
                "last_task": last,
                "last_cycle": self.store.cycles.get(last.get("story_id")) if last else None,
                "latest_event": status_view.latest_event(last, control),
                "last_step_request_at": self.last_step_request_at,
                "control": control,
            })
        snapshot.update(budget=self.budget_state(), human_requests=status_view.human_requests(REPOSITORY))
        return snapshot

    def __init__(self, store: Store, token: str, callback_origin: str = "http://localhost:5678",
                 claude_command: tuple[str, ...] = (str(CLAUDE_EXECUTABLE),),
                 codex_command: tuple[str, ...] | None = None):
        origin = urlparse(callback_origin.rstrip("/"))
        if origin.scheme != "http" or origin.hostname not in {"localhost", "127.0.0.1", "::1"} or origin.path:
            raise ValueError("GW2_N8N_CALLBACK_ORIGIN must be a local HTTP origin, for example http://localhost:5678.")
        self.store = store
        self.token = token
        self.callback_origin = callback_origin.rstrip("/")
        self.claude_command = claude_command
        node = str(CODEX_NODE_EXECUTABLE if CODEX_NODE_EXECUTABLE.exists() else (shutil.which("node") or CODEX_NODE_EXECUTABLE))
        self.codex_command = codex_command or (node, str(CODEX_CLI_SCRIPT))
        self.step_lock = threading.Lock()
        self.budget_lock = threading.Lock()
        self.last_step_request_at: str | None = None
        self.restarting = False
        self.automatic_execution = False
        self._delivery_locks: dict[str, threading.Lock] = {}

    @property
    def budget_settings(self) -> dict:
        with self.store.lock:
            return dict(self.store.budget["settings"])

    def set_budget_settings(self, raw: dict) -> None:
        settings = claude_budget.settings_from(raw)
        with self.budget_lock, self.store.lock:
            if settings != self.store.budget["settings"]:
                self.store.budget.update(settings=settings, checked_at=None, result=None, error=None)
                self.store.save()

    def budget_state(self) -> dict:
        """Read saved status without invoking Claude; old readings never grant permission."""
        with self.store.lock:
            state = copy.deepcopy(self.store.budget)
        checked_at = state["checked_at"]
        state["stale"] = checked_at is None or (
            datetime.now(timezone.utc) - datetime.fromisoformat(checked_at)
        ).total_seconds() >= BUDGET_REFRESH_SECONDS
        state["refresh_interval_seconds"] = BUDGET_REFRESH_SECONDS
        state["claude_allowed"] = bool(
            not state["stale"] and not state["error"] and (state["result"] or {}).get("claude_allowed")
        )
        return state

    def check_budget(self, raw: dict | None = None) -> dict:
        """Refresh and persist the existing calculation, also serving legacy n8n callers."""
        # Validate before changing saved settings. Hold the lock through the
        # probe so a slow old reading cannot replace a newer configuration.
        settings = claude_budget.settings_from(raw) if raw is not None else None
        with self.budget_lock:
            if settings is None:
                settings = self.budget_settings
            try:
                result = claude_budget.status(settings)
            except (ValueError, RuntimeError, OSError, subprocess.SubprocessError) as error:
                with self.store.lock:
                    self.store.budget.update(settings=settings, checked_at=utc_now(), result=None, error=str(error))
                    self.store.save()
                raise
            with self.store.lock:
                self.store.budget.update(settings=settings, checked_at=utc_now(), result=result, error=None)
                self.store.save()
        return result

    def monitor_budget(self, stop: threading.Event) -> None:
        """Refresh at startup and each quarter-hour."""
        while not stop.is_set():
            try:
                self.check_budget()
            except (ValueError, RuntimeError, OSError, subprocess.SubprocessError):
                logging.exception("Claude budget refresh failed; saved budget status is unavailable")
            if stop.wait(BUDGET_REFRESH_SECONDS - time.time() % BUDGET_REFRESH_SECONDS):
                return

    # ------------------------------------------------------------ cycle

    def next_step(self, callback_url: str | None = None, budget_settings: dict | None = None) -> dict:
        """Start (or re-attach to) the next cycle step and return its task."""
        with self.step_lock:
            if self.restarting:
                raise Busy("The service is restarting to run merged workflow code; retry in a few seconds.")
            self.last_step_request_at = utc_now()
            if budget_settings is not None:
                self.set_budget_settings(budget_settings)
            running = self.store.running()
            if running:
                if running.get("kind") != "step":
                    raise Busy("A connection-test task is running; wait for it to finish.")
                # A replacement n8n execution or a timed-out Wait attaches to
                # the step that is still running instead of starting another.
                return self.store.update(running["id"], callback_url=callback_url)

            cycle = self._current_cycle()
            backlog_writer.sync_user_requests(backlog_file=dev_cycle.BACKLOG_FILE)
            step = dev_cycle.choose_step(cycle, self.store.planning, self.budget_settings)
            asynchronous = step in dev_cycle.ASYNC_STEPS
            task = self.store.create(track_control=self.automatic_execution, kind="step", step=step,
                                     story_id=cycle["story_id"] if cycle else None,
                                     callback_url=callback_url if asynchronous else None)
            if asynchronous:
                threading.Thread(target=self._run_step, args=(task["id"], cycle), daemon=True).start()
                return task
            self._run_step(task["id"], cycle)
            return self.store.get(task["id"])

    def _current_cycle(self) -> dict | None:
        """The in-flight cycle, reconciled with the story pointer a human may have changed."""
        cycle = self.store.active_cycle()
        pointer = dev_cycle.pointer_story()
        pointer_file = pointer.relative_to(dev_cycle.REPO_ROOT).as_posix() if pointer else None
        if cycle and cycle["phase"] != "finalize" and cycle["story_file"] != pointer_file:
            logging.warning("Story pointer no longer names %s; dropping its cycle.", cycle["story_id"])
            dev_cycle.release_claude(cycle)
            self.store.drop_active()
            cycle = None
        if cycle is None and pointer is not None:
            # A story activated outside the service starts at QA.
            cycle = dev_cycle.new_cycle(pointer)
            self.store.save_cycle(cycle)
        return cycle

    def _run_step(self, task_id: str, cycle: dict | None) -> None:
        task = self.store.get(task_id)
        step = task["step"]
        logging.info("Running step %s for %s", step, task.get("story_id") or "next story")
        try:
            if step in ("plan", "sync", "review"):
                result = getattr(dev_cycle, f"step_{step}")(self.store.planning)
                self.store.save()
            elif step == "select":
                result, cycle = dev_cycle.step_select(None)
            else:
                context = dev_cycle.StepContext(lambda: self.store.save_cycle(cycle), self.budget_settings)
                result = dev_cycle.STEPS[step](cycle, context)
            status, error = "completed", None
        except ModelCapacityUnavailable as exc:  # A scheduling pause, not a failure.
            result = dev_cycle.StepResult("model_capacity_exhausted", False, f"{exc}; the next run retries.")
            status, error = "completed", None
        except Exception as exc:  # The step reruns on the next pipeline run.
            logging.exception("Step %s failed", step)
            result = dev_cycle.StepResult("error", False, f"{type(exc).__name__}: {exc}")
            status, error = "failed", result["reason"]
        try:
            # Includes requests raised by QA, architect or evaluator, and human
            # resolutions observed while another story remains active.
            backlog_writer.sync_user_requests(backlog_file=dev_cycle.BACKLOG_FILE)
        except Exception:
            # An index failure must not lose a completed step or rerun an agent.
            logging.exception("Could not refresh BACKLOG User section")
            result.setdefault("workflow_warnings", []).append("Could not refresh BACKLOG User section.")
        if cycle is not None:
            cycle["history"].append({"step": step, "outcome": result["outcome"], "at": utc_now()})
            self.store.save_cycle(cycle)
        # Publish this before the terminal task: the Python driver must not
        # start another step using code that the sync has just replaced.
        if result.get("restart"):
            self.restarting = True
        self.store.update(task_id, status=status, error=error,
                          story_id=cycle["story_id"] if cycle else None,
                          result={**result, "phase": cycle["phase"] if cycle else None})
        logging.info("Step %s finished: %s", step, result["outcome"])
        # The decision trail the old orchestrator kept in agent/logs.
        log_line(f"Pipeline {step} [{(cycle or {}).get('story_id', 'no active story')}]: "
                 f"{result['outcome']}" + (f" -- {result['reason']}" if result.get("reason") else ""))
        for warning in result.get("workflow_warnings", []):
            log_line(f"WORKFLOW WARNING: {warning}")
        self.deliver_callback(task_id)
        if result.get("restart"):
            self.restart()

    def restart(self) -> None:
        """Exit once this response is out; start-service.cmd starts the merged code."""
        self.restarting = True  # nothing new starts before the launcher loads the new code
        log_line("WorkflowService restarting to run workflow code merged from the maintainer branch.")
        threading.Timer(2.0, os._exit, args=(RESTART_EXIT_CODE,)).start()

    # ------------------------------------------------------ connection test

    def start_agent_task(self, prompt: str, agent: str, callback_url: str) -> dict:
        task = self.store.create(kind="agent", agent=agent, prompt=prompt, callback_url=callback_url)
        threading.Thread(target=self._run_agent_task, args=(task["id"],), daemon=True).start()
        return task

    def _run_agent_task(self, task_id: str) -> None:
        """Harmless read-only agent call used by the connection-test workflows."""
        task = self.store.get(task_id)
        if task["agent"] == "codex":
            command = [*self.codex_command, "exec", "--sandbox", "read-only", "--cd", str(REPOSITORY), "--ephemeral", "-"]
            stdin = task["prompt"]
        else:
            command = [*self.claude_command, "-p", "--permission-mode", "plan", "--output-format", "text", task["prompt"]]
            stdin = None
        try:
            completed = subprocess.run(command, input=stdin, cwd=REPOSITORY, text=True, encoding="utf-8",
                                       errors="replace", stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=False)
            self.store.update(task_id, status="completed" if completed.returncode == 0 else "failed",
                              result=completed.stdout, exit_code=completed.returncode,
                              error=None if completed.returncode == 0 else f"{task['agent']} exited with {completed.returncode}.")
        except OSError as error:
            self.store.update(task_id, status="failed", error=f"Could not start {task['agent']}: {error}")
        self.deliver_callback(task_id)

    # ------------------------------------------------------------ callbacks

    def deliver_callback(self, task_id: str) -> None:
        """POST the terminal task to its current Wait URL, retrying briefly."""
        lock = self._delivery_locks.setdefault(task_id, threading.Lock())
        with lock:
            opener = build_opener(_NoRedirectHandler())
            for delay in CALLBACK_RETRY_DELAYS:
                threading.Event().wait(delay)
                task = self.store.get(task_id)
                if not task.get("callback_url") or task.get("callback_delivered"):
                    return
                body = json.dumps(public_task(task), ensure_ascii=False).encode("utf-8")
                request = Request(task["callback_url"], data=body, method="POST",
                                  headers={"Content-Type": "application/json"})
                try:
                    with opener.open(request, timeout=5) as response:
                        if 200 <= response.status < 300:
                            self.store.update(task_id, callback_delivered=True)
                            return
                except (HTTPError, URLError, OSError) as error:
                    logging.warning("Callback for task %s failed (%s)", task_id, error)
            # The result stays in the store; the next pipeline run continues from it.
            logging.warning("Callback for task %s was not delivered.", task_id)


class _NoRedirectHandler(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def make_handler(service: WorkflowService):
    class Handler(BaseHTTPRequestHandler):
        server_version = "GW2WorkflowService/2.0"

        def log_message(self, format_string: str, *args) -> None:
            logging.info("%s - %s", self.client_address[0], format_string % args)

        def log_request(self, code="-", size="-") -> None:
            path = urlparse(self.path).path
            status_read = (path in {"/health", "/cycle", "/budget"} or path.startswith("/tasks/"))
            status = int(code) if str(code).isdigit() else 0
            if self.command == "GET" and status_read and 200 <= status < 300:
                log = logging.debug
            else:
                log = logging.warning if status >= 400 else logging.info
            log('%s - "%s" %s %s', self.client_address[0], self.requestline, code, size)

        def log_error(self, format_string: str, *args) -> None:
            logging.error("%s - %s", self.client_address[0], format_string % args)

        def _send(self, status: int, payload: dict) -> None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _authorized(self) -> bool:
            if hmac.compare_digest(self.headers.get("Authorization", ""), f"Bearer {service.token}"):
                return True
            self._send(401, {"error": "Missing or invalid bearer token."})
            return False

        def _body(self) -> dict:
            length = int(self.headers.get("Content-Length") or 0)
            if length <= 0 or length > MAX_BODY_LENGTH:
                raise ValueError("Request body is empty or too large.")
            body = json.loads(self.rfile.read(length))
            if not isinstance(body, dict):
                raise ValueError("Request body must be a JSON object.")
            return body

        def do_GET(self) -> None:
            path = urlparse(self.path).path
            if path == "/health":
                self._send(200, {"status": "ok"})
            elif not self._authorized():
                return
            elif path == "/cycle":
                self._send(200, service.cycle_status())
            elif path == "/budget":
                self._send(200, service.budget_state())
            elif path.startswith("/tasks/") and (task := service.store.get(path.removeprefix("/tasks/"))):
                self._send(200, public_task(task))
            else:
                self._send(404, {"error": "Not found."})

        def do_POST(self) -> None:
            path = urlparse(self.path).path
            if path not in ("/cycle/step", "/tasks", "/budget"):
                self._send(404, {"error": "Not found."})
                return
            try:
                # Read the bounded body first: replying before it is consumed resets the connection.
                body = self._body()
            except (ValueError, json.JSONDecodeError) as error:
                self._send(400, {"error": str(error)})
                return
            if not self._authorized():
                return
            if path == "/budget":
                try:
                    self._send(200, service.check_budget(body.get("budget")))
                except (ValueError, RuntimeError, OSError, subprocess.SubprocessError) as error:
                    self._send(400 if isinstance(error, ValueError) else 500, {"error": str(error)})
                return
            if service.automatic_execution:
                self._send(409, {"error": "Automatic workflow execution is active; external task starts are disabled."})
                return
            try:
                callback_url = validate_callback_url(body.get("callback_url"), service.callback_origin)
                if path == "/cycle/step":
                    task = service.next_step(callback_url, body.get("budget"))
                else:
                    prompt, agent = body.get("prompt"), body.get("agent", "claude")
                    if not isinstance(prompt, str) or not prompt.strip() or len(prompt) > MAX_PROMPT_LENGTH:
                        raise ValueError(f"prompt must be a non-empty string of at most {MAX_PROMPT_LENGTH} characters.")
                    if agent not in ("claude", "codex"):
                        raise ValueError("agent must be claude or codex.")
                    task = service.start_agent_task(prompt.strip(), agent, callback_url)
            except (ValueError, json.JSONDecodeError) as error:
                self._send(400, {"error": str(error)})
                return
            except Busy as error:
                self._send(409, {"error": str(error)})
                return
            except Exception as error:  # e.g. an unreadable story pointer
                logging.exception("Request %s failed", path)
                self._send(500, {"error": f"{type(error).__name__}: {error}"})
                return
            self._send(202, public_task(task))

    return Handler


def disable_console_quick_edit() -> None:
    """A click in a QuickEdit console pauses every write to it until Esc -- and
    with it the whole service and the Claude run streaming through it."""
    if os.name != "nt":
        return
    import ctypes
    kernel32 = ctypes.windll.kernel32
    handle = kernel32.GetStdHandle(-10)  # STD_INPUT_HANDLE
    mode = ctypes.c_uint32()
    if kernel32.GetConsoleMode(handle, ctypes.byref(mode)):
        kernel32.SetConsoleMode(handle, (mode.value & ~0x0040) | 0x0080)  # -QUICK_EDIT, +EXTENDED_FLAGS


def environment_settings() -> tuple[str, str, Path]:
    """Prefer service names while reusing existing credentials and saved work."""
    token = os.environ.get("GW2_WORKFLOW_TOKEN", os.environ.get("GW2_BRIDGE_TOKEN", ""))
    host = os.environ.get("GW2_WORKFLOW_HOST", os.environ.get("GW2_BRIDGE_HOST", "127.0.0.1"))
    default_state = Path.home() / ".gw2-workflow-service" / "tasks.json"
    legacy_state = Path.home() / ".gw2-claude-bridge" / "tasks.json"
    if not default_state.exists() and legacy_state.exists():
        default_state = legacy_state
    state = os.environ.get("GW2_WORKFLOW_STATE", os.environ.get("GW2_BRIDGE_STATE", str(default_state)))
    return token, host, Path(state)


def main(*, run_workflow: bool = False, run_now: bool = False, once: bool = False) -> None:
    disable_console_quick_edit()
    token, host, state_file = environment_settings()
    if len(token) < 32:
        raise SystemExit("Set GW2_WORKFLOW_TOKEN to a randomly generated token of at least 32 characters.")
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    # Bind before opening Store: a duplicate service must not mark the first
    # service's running tasks interrupted. Also lock the state path, including
    # when two services bind different interfaces.
    from workflow_service.control import StateLease, WorkflowControl
    server = ThreadingHTTPServer((host, 8765), None)
    lease = StateLease(state_file)
    try:
        lease.acquire()
        service = WorkflowService(Store(state_file), token,
                        callback_origin=os.environ.get("GW2_N8N_CALLBACK_ORIGIN", "http://localhost:5678"))
    except BaseException:
        lease.close()
        server.server_close()
        raise
    server.RequestHandlerClass = make_handler(service)
    service.automatic_execution = run_workflow
    budget_stop = threading.Event()
    budget_worker = threading.Thread(target=service.monitor_budget, args=(budget_stop,), daemon=True)
    budget_worker.start()
    control_stop = threading.Event()
    control_worker = None
    if run_workflow:
        control = WorkflowControl(service)
        control_worker = threading.Thread(
            target=control.serve, args=(control_stop,),
            kwargs={"run_now": run_now or once, "once": once, "finished": server.shutdown}, daemon=True)
        control_worker.start()
    logging.info("WorkflowService listening on %s:8765", host)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        logging.info("Stopping after the current step finishes; saved progress will resume on restart.")
    finally:
        control_stop.set()
        if control_worker:
            # Never abandon a running model/CI operation on a normal shutdown.
            control_worker.join()
        budget_stop.set()
        budget_worker.join(timeout=35)
        server.server_close()
        lease.close()


if __name__ == "__main__":
    main()
