from pathlib import Path
import re

from agent.runtime.support.config import PRODUCT_OWNER_REQUESTS_DIR, REPO_ROOT, USER_DECISIONS_DIR
from agent.runtime.human.user_decisions import extract_section


# ============================================================
# Product Owner request inbox (agent/product-owner-requests/)
#
# Human-written, free-form Markdown files describing requested changes,
# new features, or product-level requirements. This module only lists
# and reads what is already on disk -- deciding whether a request is
# already covered, converting it into doc updates / stories / user
# decisions, and recording its resolution are all planning-
# prompt responsibilities (see PLANNER_INSTRUCTIONS.md's "Product Owner
# Requests" section), never something this module decides on its own.
#
# README.md documents the format/lifecycle for the human and is never
# itself a request -- it is excluded everywhere below. No filename
# convention is enforced (unlike STORY-*.md/UD-*.md): a human may drop
# an unstructured note here, and the planner must still attempt to
# understand it rather than rejecting it for formatting.
# ============================================================

def list_request_files() -> list[Path]:
    if not PRODUCT_OWNER_REQUESTS_DIR.exists():
        return []

    return sorted(
        path
        for path in PRODUCT_OWNER_REQUESTS_DIR.glob("*.md")
        if path.name.lower() != "readme.md"
    )


def list_request_filenames() -> set[str]:
    return {
        path.name
        for path in list_request_files()
    }


def request_status(content: str) -> str:
    # Legacy free-form requests enter the lifecycle as OPEN.
    status = extract_section(content, "Status")
    return "OPEN" if status is None else status.strip().upper()


def read_requests(include_resolved: bool = False) -> list[dict]:
    requests = [
        {
            "file": path.name,
            "content": path.read_text(
                encoding="utf-8"
            ),
        }
        for path in list_request_files()
    ]

    return requests if include_resolved else [
        item for item in requests
        if request_status(item["content"]) != "RESOLVED"
    ]


def validate_request_updates(before: dict[str, str], processed: list,
                             planning_status: str) -> list[str]:
    """Validate retained lifecycle records; coverage itself is the planner's judgment."""
    after = {item["file"]: item["content"]
             for item in read_requests(include_resolved=True)}
    problems = []
    reported = set()
    for name in processed:
        if not isinstance(name, str) or name not in before:
            problems.append(f"Unknown processed Product Owner request: {name!r}")
        else:
            reported.add(name)
    for name in before.keys() - after.keys():
        problems.append(f"Product Owner request was deleted: {name}")
    for name, content in after.items():
        status = request_status(content)
        previous = request_status(before[name]) if name in before else None
        if previous == "RESOLVED":
            if content != before[name] or name in reported:
                problems.append(f"Already RESOLVED request must be ignored: {name}")
            continue
        if status not in ("OPEN", "NEEDS_USER", "RESOLVED"):
            problems.append(f"Invalid Product Owner request status in {name}: {status!r}")
        resolution = extract_section(content, "Planner Resolution") or ""
        if status in ("RESOLVED", "NEEDS_USER") and not resolution.strip():
            problems.append(f"{name} requires a Planner Resolution.")
        if status == "RESOLVED":
            if name not in reported or planning_status not in ("COMPLETE", "NEEDS_USER"):
                problems.append(f"{name} may be resolved only as a reported request on a COMPLETE or NEEDS_USER run (independent work only).")
            # Require concrete, existing authoritative evidence, including for
            # existing coverage/no-action resolutions. Do not accept prose alone.
            refs = re.findall(r"(?:docs|agent/stories)/[A-Za-z0-9_./-]+\.md", resolution)
            if not any((REPO_ROOT / ref).is_file() and ".." not in Path(ref).parts
                       for ref in refs):
                problems.append(f"{name} must cite an existing authoritative document or story in Planner Resolution.")
        elif name in reported:
            problems.append(f"Processed request is not RESOLVED: {name}")
        if status == "NEEDS_USER":
            refs = re.findall(r"UD-\d+-[A-Za-z0-9_-]+\.md", resolution)
            if not refs or any(not (USER_DECISIONS_DIR / ref).is_file() for ref in refs):
                problems.append(f"{name} must identify an existing blocking UD-* file in Planner Resolution.")
    return problems
