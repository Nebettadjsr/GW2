"""Read-only status projection; never reconcile stories or start workflow work."""
from __future__ import annotations

import re
from datetime import datetime
from pathlib import Path

from agent.runtime.human import user_decisions


def human_requests(root: Path) -> dict:
    records, errors = [], []
    for directory, prefix, kind, heading in (
        ("user-decisions", "UD", "decision", "Decision Needed"),
        ("user-interventions", "UI", "intervention", "Reason"),
    ):
        for path in sorted((root / "agent" / directory).glob(f"{prefix}-*.md")):
            try:
                content = path.read_text(encoding="utf-8-sig")
            except (OSError, UnicodeError) as error:
                errors.append(f"{path.name}: {error}")
                continue
            request_status = user_decisions.extract_status(content)
            identifier = re.match(rf"^{prefix}-\d+(?:-|$)", path.stem)
            if not identifier or request_status is None:
                errors.append(f"{path.name}: missing or invalid request ID/status")
                continue
            if request_status != "OPEN":
                continue
            sections = "\n".join(user_decisions.extract_section(content, h) or ""
                                 for h in ("Story", "Blocks"))
            records.append({
                "id": identifier.group().rstrip("-"),
                "kind": kind, "status": "OPEN", "path": path.relative_to(root).as_posix(),
                "story_ids": sorted(set(re.findall(r"\bSTORY-[A-Z0-9]+-\d+\b", sections))),
                "reason": user_decisions.extract_section(content, heading) or "No reason recorded.",
            })
    return {"open": records, "errors": errors}


def latest_event(task: dict | None, control: dict) -> dict:
    """A newer manual step must supersede a previous scheduled run's stop."""
    def timestamp(value):
        try:
            return datetime.fromisoformat(value).timestamp()
        except (TypeError, ValueError):
            return float('-inf')

    stopped_at = control.get("finished_at") or control.get("updated_at")
    if control.get("outcome") and (not task or timestamp(stopped_at) >= timestamp(task.get("created_at"))):
        matching = task if task and task.get("task_id") == control.get("task_id") else {}
        return {"outcome": control["outcome"], "reason": control.get("reason") or "",
                "step": matching.get("step") if matching.get("outcome") == control["outcome"] else None,
                "story_id": matching.get("story_id"),
                "phase": matching.get("phase"),
                "proceed": False, "source": "control"}
    return {**(task or {}), "source": "task"}
