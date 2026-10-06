import json
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from bridge import Bridge, TaskStore, make_handler, validate_callback_url


class BridgeHttpTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.fake_claude = self.root / "fake_claude.py"
        self.fake_calls = self.root / "claude-calls.txt"
        self.fake_codex = self.root / "fake_codex.py"
        self.fake_codex_calls = self.root / "codex-calls.txt"
        self.fake_claude.write_text(
            "import json, os, sys\n"
            f"open({str(self.fake_calls)!r}, 'a', encoding='utf-8').write('called\\n')\n"
            "print(json.dumps({'args': sys.argv[1:], 'cwd': os.getcwd()}))\n"
            "if sys.argv[-1] == 'slow task':\n"
            " import time; time.sleep(0.3)\n"
            "if sys.argv[-1] == 'exit nonzero': sys.exit(7)\n",
            encoding="utf-8",
        )
        self.fake_codex.write_text(
            "import json, os, sys\n"
            f"open({str(self.fake_codex_calls)!r}, 'a', encoding='utf-8').write('called\\n')\n"
            "prompt = sys.stdin.read()\n"
            "print(json.dumps({'args': sys.argv[1:], 'prompt': prompt, 'cwd': os.getcwd()}))\n"
            "if 'exit nonzero' in prompt: sys.exit(8)\n",
            encoding="utf-8",
        )
        self.store = TaskStore(self.root / "tasks.json")
        self.callback_payloads = []
        self.callback_paths = []
        self.callback_responses = []
        self.callback_block_path = None
        self.callback_entered = threading.Event()
        self.callback_release = threading.Event()

        test_case = self

        class CallbackHandler(BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers.get("Content-Length", "0"))
                test_case.callback_payloads.append(json.loads(self.rfile.read(length)))
                test_case.callback_paths.append(self.path)
                if self.path == test_case.callback_block_path:
                    test_case.callback_entered.set()
                    test_case.callback_release.wait(3)
                status = test_case.callback_responses.pop(0) if test_case.callback_responses else 200
                self.send_response(status)
                self.end_headers()

            def log_message(self, format_string, *args):
                pass

        self.callback_server = ThreadingHTTPServer(("127.0.0.1", 0), CallbackHandler)
        self.callback_thread = threading.Thread(target=self.callback_server.serve_forever, daemon=True)
        self.callback_thread.start()
        self.callback_origin = f"http://127.0.0.1:{self.callback_server.server_port}"
        self.callback_url = self.callback_origin + "/webhook-waiting/exec-1/wait-node?signature=local-test"
        self.bridge = Bridge(self.store, "test-token", command_prefix=(sys.executable, str(self.fake_claude)),
                             codex_command_prefix=(sys.executable, str(self.fake_codex)),
                             callback_origin=self.callback_origin)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(self.bridge))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.callback_server.shutdown()
        self.callback_server.server_close()
        self.directory.cleanup()

    def request(self, path, method="GET", payload=None, token="test-token", key=None, callback_url=None):
        headers = {}
        data = None
        if token is not None:
            headers["Authorization"] = f"Bearer {token}"
        if key is not None:
            headers["Idempotency-Key"] = key
        if payload is not None:
            if callback_url is not None:
                payload["callback_url"] = callback_url
            data = json.dumps(payload).encode()
            headers["Content-Type"] = "application/json"
        request = Request(self.url + path, data=data, headers=headers, method=method)
        try:
            response = urlopen(request, timeout=3)
        except HTTPError as error:
            return error.code, json.loads(error.read())
        return response.status, json.loads(response.read())

    def wait_for(self, task_id):
        for _ in range(100):
            task = self.store.get(task_id)
            if task["status"] in {"completed", "failed", "interrupted"}:
                return task
            time.sleep(0.02)
        self.fail("Task did not complete")

    def wait_for_callback(self, task_id, expected="delivered", store=None):
        task_store = store or self.store
        for _ in range(150):
            task = task_store.get(task_id)
            if task.get("callback_delivery_status") == expected:
                return task
            time.sleep(0.02)
        self.fail(f"Callback did not reach status {expected}")

    def callback_url_for(self, execution_id):
        return self.callback_origin + f"/webhook-waiting/{execution_id}/wait-node?signature=local-test"

    def test_health_is_public_and_task_routes_require_token(self):
        self.assertEqual(200, self.request("/health", token=None)[0])
        self.assertEqual(401, self.request("/tasks/unknown", token=None)[0])
        status, _ = self.request("/tasks", method="POST", payload={"prompt": "x"}, token="wrong", key="unauth")
        self.assertEqual(401, status)

    def test_task_runs_once_and_persists_result(self):
        status, created = self.request("/tasks", method="POST", payload={"prompt": "inspect status"}, key="req-1")
        self.assertEqual(202, status)
        self.assertEqual("created", created["submission_disposition"])
        self.assertEqual("persisted_bridge_task", created["record_source"])
        result = self.wait_for(created["task_id"])
        self.assertEqual("completed", result["status"])
        self.assertEqual(0, result["exit_code"])
        output = json.loads(result["result"])
        self.assertIn("inspect status", output["args"])
        self.assertEqual("plan", output["args"][output["args"].index("--permission-mode") + 1])
        self.assertEqual(str(Path(__file__).resolve().parent.parent), output["cwd"])
        duplicate_status, duplicate = self.request("/tasks", method="POST", payload={"prompt": "inspect status"}, key="req-1")
        self.assertEqual(202, duplicate_status)
        self.assertEqual("idempotent_replay", duplicate["submission_disposition"])
        self.assertEqual(created["task_id"], duplicate["task_id"])
        self.assertEqual("completed", duplicate["status"])
        self.assertEqual(result["result"], duplicate["result"])
        public_status, public_task = self.request(f"/tasks/{created['task_id']}")
        self.assertEqual(200, public_status)
        self.assertEqual("persisted_bridge_task", public_task["record_source"])
        self.assertNotIn("prompt", public_task)
        recovered = TaskStore(self.root / "tasks.json").get(created["task_id"])
        self.assertEqual(result["result"], recovered["result"])

    def test_codex_selection_uses_fixed_read_only_gw2_command_and_callback(self):
        status, created = self.request("/tasks", method="POST",
                                       payload={"agent": "codex", "prompt": "Return the fixed test sentence.",
                                                "callback_url": self.callback_url},
                                       key="codex-task")
        self.assertEqual(202, status)
        task = self.wait_for(created["task_id"])
        self.wait_for_callback(created["task_id"])
        output = json.loads(task["result"])
        self.assertEqual("completed", task["status"])
        self.assertEqual("codex", task["agent"])
        self.assertEqual("Return the fixed test sentence.", output["prompt"])
        self.assertEqual(str(Path(__file__).resolve().parent.parent), output["cwd"])
        self.assertEqual(["exec", "--sandbox", "read-only", "--cd",
                          str(Path(__file__).resolve().parent.parent), "--ephemeral", "-"], output["args"])
        self.assertEqual(created["task_id"], self.callback_payloads[0]["task_id"])
        self.assertEqual("completed", self.callback_payloads[0]["status"])
        self.assertEqual(1, len(self.fake_codex_calls.read_text(encoding="utf-8").splitlines()))
        self.assertFalse(self.fake_calls.exists())
        self.request("/tasks", method="POST", payload={"agent": "codex", "prompt": "Return the fixed test sentence."},
                     key="codex-task", callback_url=self.callback_url)
        self.assertEqual(1, len(self.fake_codex_calls.read_text(encoding="utf-8").splitlines()))

    def test_codex_nonzero_exit_is_saved_as_failed(self):
        status, created = self.request("/tasks", method="POST",
                                       payload={"agent": "codex", "prompt": "exit nonzero"},
                                       key="codex-failed")
        self.assertEqual(202, status)
        task = self.wait_for(created["task_id"])
        self.assertEqual("failed", task["status"])
        self.assertEqual(8, task["exit_code"])

    def test_restart_recovers_codex_callback_as_interrupted_without_rerunning(self):
        task, _ = self.store.create("do not run after restart", "codex-restart", self.callback_url, "codex")
        self.store.update(task["id"], status="running")
        recovered_store = TaskStore(self.root / "tasks.json")
        recovered_bridge = Bridge(recovered_store, "test-token",
                                  codex_command_prefix=(sys.executable, str(self.fake_codex)),
                                  callback_origin=self.callback_origin)
        recovered_bridge.resume_pending_callbacks()
        recovered = self.wait_for_callback(task["id"], store=recovered_store)
        self.assertEqual("interrupted", recovered["status"])
        self.assertEqual("codex", recovered["agent"])
        self.assertEqual("interrupted", self.callback_payloads[0]["status"])
        self.assertFalse(self.fake_codex_calls.exists())

    def test_agent_is_authenticated_and_unknown_selection_is_rejected(self):
        status, _ = self.request("/tasks", method="POST", payload={"agent": "codex", "prompt": "x"},
                                 token=None, key="codex-unauth")
        self.assertEqual(401, status)
        status, response = self.request("/tasks", method="POST",
                                        payload={"agent": "shell", "prompt": "x"}, key="unknown-agent")
        self.assertEqual(400, status)
        self.assertIn("agent", response["error"])

    def test_rejects_invalid_prompt_and_missing_idempotency_key(self):
        self.assertEqual(400, self.request("/tasks", method="POST", payload={"prompt": " "}, key="x")[0])
        self.assertEqual(400, self.request("/tasks", method="POST", payload={"prompt": "hello"})[0])

    def test_restart_marks_running_task_interrupted_without_rerunning(self):
        task, _ = self.store.create("uncertain", "uncertain-key")
        self.store.update(task["id"], status="running")
        recovered = TaskStore(self.root / "tasks.json")
        self.assertEqual("interrupted", recovered.get(task["id"])["status"])

    def test_callback_delivered_once_and_hidden_from_status_response(self):
        status, created = self.request("/tasks", method="POST", payload={"prompt": "callback once"},
                                       key="callback-once", callback_url=self.callback_url)
        self.assertEqual(202, status)
        self.wait_for(created["task_id"])
        task = self.wait_for_callback(created["task_id"])
        self.assertEqual("delivered", task["callback_delivery_status"])
        self.assertEqual("delivered", TaskStore(self.root / "tasks.json").get(created["task_id"])["callback_delivery_status"])
        self.assertEqual(1, len(self.callback_payloads))
        self.assertEqual({"task_id", "status", "exit_code", "result", "error"}, set(self.callback_payloads[0]))
        self.assertEqual("completed", self.callback_payloads[0]["status"])

        public_status, public_task = self.request(f"/tasks/{created['task_id']}")
        self.assertEqual(200, public_status)
        self.assertNotIn("callback_url", public_task)
        self.assertNotIn("prompt", public_task)

        self.request("/tasks", method="POST", payload={"prompt": "callback once"}, key="callback-once",
                     callback_url=self.callback_url)
        self.assertEqual(1, len(self.callback_payloads))
        self.assertEqual(1, len(self.fake_calls.read_text(encoding="utf-8").splitlines()))

    def test_replacement_callback_before_completion_uses_same_running_task(self):
        first_url = self.callback_url_for("old-run")
        replacement_url = self.callback_url_for("new-run")
        status, first = self.request("/tasks", method="POST", payload={"prompt": "slow task"},
                                     key="replace-before", callback_url=first_url)
        self.assertEqual(202, status)
        for _ in range(100):
            if self.store.get(first["task_id"])["status"] == "running":
                break
            time.sleep(0.005)
        status, replacement = self.request("/tasks", method="POST", payload={"prompt": "slow task"},
                                           key="replace-before", callback_url=replacement_url)
        self.assertEqual(202, status)
        self.assertEqual(first["task_id"], replacement["task_id"])
        task = self.wait_for(first["task_id"])
        self.wait_for_callback(first["task_id"])
        self.assertEqual("completed", task["status"])
        self.assertEqual(["/webhook-waiting/new-run/wait-node?signature=local-test"], self.callback_paths)
        self.assertEqual(1, len(self.fake_calls.read_text(encoding="utf-8").splitlines()))

    def test_completed_task_replays_persisted_result_to_replacement_callback(self):
        _, submitted = self.request("/tasks", method="POST", payload={"prompt": "complete then replace"},
                                    key="replace-after", callback_url=self.callback_url_for("old-done"))
        original = self.wait_for(submitted["task_id"])
        self.wait_for_callback(submitted["task_id"])
        original_result = original["result"]
        _, replacement = self.request("/tasks", method="POST", payload={"prompt": "complete then replace"},
                                      key="replace-after", callback_url=self.callback_url_for("new-done"))
        self.wait_for_callback(submitted["task_id"])
        self.assertEqual(submitted["task_id"], replacement["task_id"])
        self.assertEqual("completed", replacement["status"])
        self.assertEqual(original_result, replacement["result"])
        self.assertEqual(2, len(self.callback_payloads))
        self.assertEqual(original_result, self.callback_payloads[-1]["result"])
        self.assertEqual("/webhook-waiting/new-done/wait-node?signature=local-test", self.callback_paths[-1])
        self.assertEqual(1, len(self.fake_calls.read_text(encoding="utf-8").splitlines()))

    def test_delayed_obsolete_callback_stops_retrying_after_replacement(self):
        old_url = self.callback_url_for("delayed-old")
        new_url = self.callback_url_for("delayed-new")
        self.callback_block_path = "/webhook-waiting/delayed-old/wait-node?signature=local-test"
        self.callback_responses[:] = [503, 200]
        _, submitted = self.request("/tasks", method="POST", payload={"prompt": "slow task"},
                                    key="replace-delayed", callback_url=old_url)
        self.assertTrue(self.callback_entered.wait(3), "Old callback was not attempted")
        _, replacement = self.request("/tasks", method="POST", payload={"prompt": "slow task"},
                                      key="replace-delayed", callback_url=new_url)
        self.callback_release.set()
        self.wait_for_callback(submitted["task_id"])
        self.assertEqual(submitted["task_id"], replacement["task_id"])
        self.assertEqual(["/webhook-waiting/delayed-old/wait-node?signature=local-test",
                          "/webhook-waiting/delayed-new/wait-node?signature=local-test"], self.callback_paths)
        self.assertEqual(1, len(self.fake_calls.read_text(encoding="utf-8").splitlines()))

    def test_idempotency_key_cannot_be_reused_for_different_task(self):
        _, submitted = self.request("/tasks", method="POST", payload={"prompt": "original prompt"}, key="key-conflict")
        self.wait_for(submitted["task_id"])
        status, response = self.request("/tasks", method="POST", payload={"prompt": "different prompt"}, key="key-conflict")
        self.assertEqual(409, status)
        self.assertIn("different", response["error"])
        self.assertEqual(1, len(self.fake_calls.read_text(encoding="utf-8").splitlines()))

    def test_restart_retries_persisted_completed_result_without_agent_restart(self):
        task, _ = self.store.create("completed before restart", "done-restart", self.callback_url_for("restart-old"))
        self.store.update(task["id"], status="completed", exit_code=0, result="saved result", error=None,
                          callback_delivery_status="delivery_unconfirmed")
        recovered_store = TaskStore(self.root / "tasks.json")
        recovered_bridge = Bridge(recovered_store, "test-token", command_prefix=(sys.executable, str(self.fake_claude)),
                                  callback_origin=self.callback_origin)
        recovered_bridge.resume_pending_callbacks()
        recovered = self.wait_for_callback(task["id"], store=recovered_store)
        self.assertEqual("saved result", self.callback_payloads[0]["result"])
        self.assertEqual("completed", recovered["status"])
        self.assertFalse(self.fake_calls.exists())

    def test_temporary_callback_failure_retries_without_rerunning_claude(self):
        self.callback_responses[:] = [503, 200]
        status, created = self.request("/tasks", method="POST", payload={"prompt": "retry callback"},
                                       key="callback-retry", callback_url=self.callback_url)
        self.assertEqual(202, status)
        self.wait_for(created["task_id"])
        task = self.wait_for_callback(created["task_id"])
        self.assertGreaterEqual(task["callback_delivery_attempts"], 2)
        self.assertEqual(2, len(self.callback_payloads))
        self.assertEqual("completed", task["status"])
        self.assertEqual(1, len(self.fake_calls.read_text(encoding="utf-8").splitlines()))

    def test_failed_claude_task_notifies_with_exit_code_and_error(self):
        status, created = self.request("/tasks", method="POST", payload={"prompt": "exit nonzero"},
                                       key="callback-failed", callback_url=self.callback_url)
        self.assertEqual(202, status)
        self.wait_for(created["task_id"])
        self.wait_for_callback(created["task_id"])
        self.assertEqual("failed", self.callback_payloads[0]["status"])
        self.assertEqual(7, self.callback_payloads[0]["exit_code"])
        self.assertIn("non-zero", self.callback_payloads[0]["error"])

    def test_rejects_callback_outside_configured_local_n8n_resume_route(self):
        for invalid in (
            "http://example.com/webhook-waiting/exec-1/wait-node",
            self.callback_origin + "/other/path",
            "https://127.0.0.1:5678/webhook-waiting/exec-1/wait-node",
        ):
            status, _ = self.request("/tasks", method="POST", payload={"prompt": "reject callback"},
                                     key="reject-" + str(len(invalid)), callback_url=invalid)
            self.assertEqual(400, status)
        self.assertEqual({}, self.store.tasks)

    def test_accepts_this_n8n_instances_execution_only_resume_path(self):
        resume_url = self.callback_origin + "/webhook-waiting/14?signature=local-test"
        self.assertEqual(resume_url, validate_callback_url(resume_url, self.callback_origin))

    def test_restart_recovers_callback_for_interrupted_task_without_rerunning(self):
        task, _ = self.store.create("never run", "restart-key", self.callback_url)
        self.store.update(task["id"], status="running")

        recovered_store = TaskStore(self.root / "tasks.json")
        self.assertEqual("interrupted", recovered_store.get(task["id"])["status"])
        recovered_bridge = Bridge(recovered_store, "test-token",
                                  command_prefix=(sys.executable, str(self.fake_claude)),
                                  callback_origin=self.callback_origin)
        recovered_bridge.resume_pending_callbacks()
        recovered = self.wait_for_callback(task["id"], store=recovered_store)
        self.assertEqual("interrupted", recovered["status"])
        self.assertEqual("interrupted", self.callback_payloads[0]["status"])
        self.assertFalse(self.fake_calls.exists())


if __name__ == "__main__":
    unittest.main()
