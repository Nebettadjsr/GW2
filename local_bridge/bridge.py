"""Local authenticated HTTP bridge between n8n and the GW2 development cycle."""

from __future__ import annotations

import hmac
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

from local_bridge import claude_budget, cycle as dev_cycle  # noqa: E402  (needs the repository on sys.path)

CLAUDE_EXECUTABLE = Path(r"C:\Users\Administrator\.local\bin\claude.EXE")
CODEX_NODE_EXECUTABLE = Path(r"C:\Program Files\nodejs\node.exe")
CODEX_CLI_SCRIPT = Path.home() / "AppData" / "Roaming" / "npm" / "node_modules" / "@openai" / "codex" / "bin" / "codex.js"
MAX_PROMPT_LENGTH = 20_000
MAX_BODY_LENGTH = MAX_PROMPT_LENGTH + 4096
CALLBACK_RETRY_DELAYS = (0, 0.25, 0.5, 1, 2, 4, 8)
ACTIVE_STATUSES = {"queued", "running"}


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
    """Another task is running; the bridge runs one at a time."""


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
        for task in self.tasks.values():
            if task.get("status") in ACTIVE_STATUSES:
                # Its outcome is unknown. A cycle step is simply run again;
                # an interrupted implementation resumes with a continuation prompt.
                task.update(status="interrupted", updated_at=utc_now(),
                            error="The bridge restarted while this task was running.")
        self.save()

    def save(self) -> None:
        with self.lock:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            temporary = self.path.with_suffix(self.path.suffix + ".tmp")
            temporary.write_text(json.dumps(
                {"tasks": self.tasks, "cycles": self.cycles, "active": self.active,
                 "planning": self.planning},
                ensure_ascii=False), encoding="utf-8")
            os.replace(temporary, self.path)

    def create(self, **fields) -> dict:
        with self.lock:
            if self.running():
                raise Busy("A bridge task is already running; wait for it to finish.")
            task = {"id": str(uuid.uuid4()), "status": "running", "result": None, "error": None,
                    "callback_url": None, "callback_delivered": False,
                    "created_at": utc_now(), "updated_at": utc_now(), **fields}
            self.tasks[task["id"]] = task
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
            self.cycles[cycle["story_id"]] = cycle
            self.active = cycle["story_id"] if cycle["phase"] not in ("done", "blocked") else None
            self.save()

    def drop_active(self) -> None:
        with self.lock:
            self.active = None
            self.save()


def public_task(task: dict) -> dict:
    """The task as n8n sees it: identity, status and the step's result fields."""
    view = {"task_id": task["id"], "status": task["status"], "kind": task.get("kind"),
            "step": task.get("step"), "story_id": task.get("story_id"), "error": task.get("error")}
    result = task.get("result")
    if isinstance(result, dict):
        view.update(result)
    elif result is not None:
        view["result"] = result
    view.setdefault("proceed", False)
    return view


class Bridge:
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
        self.budget_settings: dict | None = None
        self.last_step_request_at: str | None = None
        self._delivery_locks: dict[str, threading.Lock] = {}

    # ------------------------------------------------------------ cycle

    def next_step(self, callback_url: str, budget_settings: dict | None = None) -> dict:
        """Start (or re-attach to) the next cycle step and return its task."""
        with self.step_lock:
            self.last_step_request_at = utc_now()
            if budget_settings is not None:
                self.budget_settings = claude_budget.settings_from(budget_settings)
            running = self.store.running()
            if running:
                if running.get("kind") != "step":
                    raise Busy("A connection-test task is running; wait for it to finish.")
                # A replacement n8n execution or a timed-out Wait attaches to
                # the step that is still running instead of starting another.
                return self.store.update(running["id"], callback_url=callback_url)

            cycle = self._current_cycle()
            step = cycle["phase"] if cycle else dev_cycle.idle_step(self.store.planning)
            asynchronous = step in dev_cycle.ASYNC_STEPS
            task = self.store.create(kind="step", step=step,
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
            self.store.drop_active()
            cycle = None
        if cycle is None and pointer is not None:
            # A story activated outside the bridge starts at QA.
            cycle = dev_cycle.new_cycle(pointer)
            self.store.save_cycle(cycle)
        return cycle

    def _run_step(self, task_id: str, cycle: dict | None) -> None:
        task = self.store.get(task_id)
        step = task["step"]
        logging.info("Running step %s for %s", step, task.get("story_id") or "next story")
        try:
            if step == "plan":
                result = dev_cycle.step_plan(self.store.planning)
                self.store.save()
            elif step == "select":
                result, cycle = dev_cycle.step_select(None)
            else:
                context = dev_cycle.StepContext(lambda: self.store.save_cycle(cycle), self.budget_settings)
                result = dev_cycle.STEPS[step](cycle, context)
            status, error = "completed", None
        except Exception as exc:  # The step reruns on the next pipeline run.
            logging.exception("Step %s failed", step)
            result = dev_cycle.StepResult("error", False, f"{type(exc).__name__}: {exc}")
            status, error = "failed", result["reason"]
        if cycle is not None:
            cycle["history"].append({"step": step, "outcome": result["outcome"], "at": utc_now()})
            self.store.save_cycle(cycle)
        self.store.update(task_id, status=status, error=error,
                          story_id=cycle["story_id"] if cycle else None,
                          result={**result, "phase": cycle["phase"] if cycle else None})
        logging.info("Step %s finished: %s", step, result["outcome"])
        self.deliver_callback(task_id)

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


def make_handler(bridge: Bridge):
    class Handler(BaseHTTPRequestHandler):
        server_version = "GW2Bridge/2.0"

        def log_message(self, format_string: str, *args) -> None:
            logging.info("%s - %s", self.client_address[0], format_string % args)

        def _send(self, status: int, payload: dict) -> None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _authorized(self) -> bool:
            if hmac.compare_digest(self.headers.get("Authorization", ""), f"Bearer {bridge.token}"):
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
                running = bridge.store.running()
                self._send(200, {"active_cycle": bridge.store.active_cycle(),
                                 "running_task": public_task(running) if running else None,
                                 "last_step_request_at": bridge.last_step_request_at})
            elif path.startswith("/tasks/") and (task := bridge.store.get(path.removeprefix("/tasks/"))):
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
                    self._send(200, claude_budget.status(body.get("budget")))
                except (ValueError, RuntimeError, OSError) as error:
                    self._send(400 if isinstance(error, ValueError) else 500, {"error": str(error)})
                return
            try:
                callback_url = validate_callback_url(body.get("callback_url"), bridge.callback_origin)
                if path == "/cycle/step":
                    task = bridge.next_step(callback_url, body.get("budget"))
                else:
                    prompt, agent = body.get("prompt"), body.get("agent", "claude")
                    if not isinstance(prompt, str) or not prompt.strip() or len(prompt) > MAX_PROMPT_LENGTH:
                        raise ValueError(f"prompt must be a non-empty string of at most {MAX_PROMPT_LENGTH} characters.")
                    if agent not in ("claude", "codex"):
                        raise ValueError("agent must be claude or codex.")
                    task = bridge.start_agent_task(prompt.strip(), agent, callback_url)
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


def main() -> None:
    token = os.environ.get("GW2_BRIDGE_TOKEN", "")
    if len(token) < 32:
        raise SystemExit("Set GW2_BRIDGE_TOKEN to a randomly generated token of at least 32 characters.")
    host = os.environ.get("GW2_BRIDGE_HOST", "127.0.0.1")
    state_file = Path(os.environ.get("GW2_BRIDGE_STATE", str(Path.home() / ".gw2-claude-bridge" / "tasks.json")))
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    bridge = Bridge(Store(state_file), token,
                    callback_origin=os.environ.get("GW2_N8N_CALLBACK_ORIGIN", "http://localhost:5678"))
    server = ThreadingHTTPServer((host, 8765), make_handler(bridge))
    logging.info("Bridge listening on %s:8765", host)
    server.serve_forever()


if __name__ == "__main__":
    main()
