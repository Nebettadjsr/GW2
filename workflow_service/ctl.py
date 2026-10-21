"""Operator commands for the running workflow service.

    python -m workflow_service.ctl status|pause|resume|start|abort|auto|manual
    python -m workflow_service.ctl kill-leftovers

``pause`` holds after the current step; ``abort`` also kills the agent (Claude
and Codex) processes the service started and holds; ``resume`` continues the
held run; ``start`` also begins a new run; ``auto``/``manual`` choose whether
the clock may start runs. ``kill-leftovers`` works without the service: it
stops processes still listed in the service's process registry (matched by pid
and start time, never by name).
"""

from __future__ import annotations

import json
import sys
import urllib.error
import urllib.request

from pathlib import Path

from workflow_service.service import environment_settings

PORT = 8765
LAUNCHER_HOST = "172.29.240.1"  # what start-service.cmd binds when nothing else is set


def settings() -> tuple[str, str, Path]:
    """Like the service's own settings, with the launcher's token-file and host fallbacks."""
    token, host, state = environment_settings()
    if not token:
        for folder in (".gw2-workflow-service", ".gw2-claude-bridge"):
            candidate = Path.home() / folder / "token.txt"
            if candidate.exists():
                token = candidate.read_text(encoding="ascii").strip()
                break
    import os
    if not any(key in os.environ for key in ("GW2_WORKFLOW_HOST", "GW2_BRIDGE_HOST")):
        host = LAUNCHER_HOST
    return token, host, state


def call(method: str, path: str, body: dict | None = None) -> dict:
    token, host, _state = settings()
    request = urllib.request.Request(
        f"http://{host}:{PORT}{path}", method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def summary(cycle: dict) -> str:
    control = cycle.get("control") or {}
    running = cycle.get("running_task") or {}
    return (f"run: {control.get('status', 'idle')}  hold: {bool(control.get('hold'))}  "
            f"schedule: {control.get('schedule', 'auto')}  step: {running.get('step', '-')}  "
            f"agents: {[(p['role'], p['pid']) for p in cycle.get('agent_processes', [])]}")


def main(argv: list[str]) -> int:
    action = argv[0] if argv else "status"
    try:
        if action == "kill-leftovers":
            from agent.runtime.support import processes
            _token, _host, state = settings()
            try:
                call("GET", "/health")
            except (urllib.error.URLError, OSError):
                pass
            else:
                print("The service is running; use 'abort' so the stopped steps are handled properly.")
                return 1
            killed = processes.sweep_stale(state.with_suffix(".processes.json"))
            print(f"Stopped {len(killed)} leftover agent process(es): {[(e['role'], e['pid']) for e in killed]}")
        elif action == "status":
            print(summary(call("GET", "/cycle")))
        else:
            result = call("POST", "/control", {"action": action})
            print(f"{action}: ok; stopped {len(result['stopped_processes'])} agent process(es)")
            print(summary({"control": result["control"]}))
    except urllib.error.HTTPError as error:
        print(f"{action}: {error.code} {error.read().decode(errors='replace')}")
        return 1
    except (urllib.error.URLError, OSError) as error:
        print(f"Service not reachable ({error}). 'kill-leftovers' still works without it.")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
