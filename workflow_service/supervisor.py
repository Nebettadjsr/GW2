"""Tiny always-on launcher so a dashboard button can open the workflow service window.

    python -m workflow_service.supervisor

The workflow service cannot start itself, and Grafana (in Docker) cannot start a Windows
process. This stdlib server listens beside the service (port 8766, same token and host
settings) and does exactly one thing: ``POST /launch`` opens ``start-service.cmd`` in a new
console unless the service already answers on 8765. ``GET /status`` reports whether it does.
It accepts no arguments from the caller, so it cannot be made to run anything else.
"""

from __future__ import annotations

import hmac
import json
import logging
import subprocess
import sys
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from workflow_service.ctl import settings

SERVICE_PORT = 8765
SUPERVISOR_PORT = 8766
LAUNCHER = Path(__file__).resolve().parent / "start-service.cmd"


def service_running(host: str) -> bool:
    try:
        with urllib.request.urlopen(f"http://{host}:{SERVICE_PORT}/health", timeout=3):
            return True
    except (urllib.error.URLError, OSError):
        return False


def launch(host: str, launcher: Path = LAUNCHER) -> dict:
    """Open the service console unless one already answers; never starts a second service."""
    if service_running(host):
        return {"launched": False, "reason": "The workflow service is already running."}
    creation = getattr(subprocess, "CREATE_NEW_CONSOLE", 0)
    subprocess.Popen(["cmd.exe", "/k", str(launcher)], cwd=launcher.parent.parent, creationflags=creation)
    return {"launched": True, "reason": "Opened the workflow service window."}


def make_handler(token: str, host: str):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format_string, *args):
            logging.info("%s %s", self.address_string(), format_string % args)

        def _send(self, status: int, payload: dict) -> None:
            body = json.dumps(payload).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _authorized(self) -> bool:
            supplied = self.headers.get("Authorization", "").removeprefix("Bearer ")
            if hmac.compare_digest(supplied.encode(), token.encode()):
                return True
            self._send(401, {"error": "Unauthorized."})
            return False

        def do_GET(self):
            if self.path == "/status" and self._authorized():
                self._send(200, {"service_running": service_running(host)})
            elif self.path != "/status":
                self._send(404, {"error": "Not found."})

        def do_POST(self):
            length = int(self.headers.get("Content-Length") or 0)
            self.rfile.read(min(length, 4096))  # the body is ignored
            if self.path != "/launch":
                self._send(404, {"error": "Not found."})
            elif self._authorized():
                self._send(200, launch(host))

    return Handler


def main() -> None:
    token, host, _state = settings()
    if len(token) < 32:
        raise SystemExit("No workflow token found; create it as described in workflow_service/README.md.")
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    server = ThreadingHTTPServer((host, SUPERVISOR_PORT), make_handler(token, host))
    logging.info("Workflow supervisor listening on %s:%s", host, SUPERVISOR_PORT)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
    main()
