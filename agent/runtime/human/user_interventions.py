from pathlib import Path
import re

from agent.runtime.support.config import USER_INTERVENTIONS_DIR
from agent.runtime.support.files import read_file


# ============================================================
# Implementation-time human/tooling intervention records
# (agent/user-interventions/), distinct from agent/user-decisions/
# (product/domain/architecture decisions only).
#
# The evaluator only ever CLASSIFIES a story attempt as NEEDS_USER --
# it never writes to this directory itself. Creating, reading, and
# resolving these files is entirely deterministic Python, mirroring
# user_decisions.py's read-side conventions where the shapes match
# (a '## Status' of OPEN/RESOLVED), plus a writer since these files
# are always machine-created, never hand-authored like a UD file.
# ============================================================

REQUIRED_INTERVENTION_HEADINGS = [
    "Status",
    "Story",
    "Reason",
    "Claude Response",
    "User Resolution",
    "Resolution Notes",
]

INTERVENTION_FILENAME_PATTERN = re.compile(
    r"^(UI-\d+)-.*\.md$",
    re.IGNORECASE,
)

STATUS_PATTERN = re.compile(
    r"##\s*Status\s*\n+\s*(OPEN|RESOLVED)\b",
    re.IGNORECASE,
)

STORY_PATTERN = re.compile(
    r"##\s*Story\s*\n+\s*(\S+)",
    re.IGNORECASE,
)


def list_intervention_files() -> list[Path]:
    if not USER_INTERVENTIONS_DIR.exists():
        return []

    return sorted(
        USER_INTERVENTIONS_DIR.glob("UI-*.md")
    )


def intervention_id_from_filename(
        filename: str
) -> str | None:
    match = INTERVENTION_FILENAME_PATTERN.match(
        filename
    )

    return match.group(1).upper() if match else None


def extract_status(
        content: str
) -> str | None:
    match = STATUS_PATTERN.search(
        content
    )

    return match.group(1).upper() if match else None


def extract_story_id(
        content: str
) -> str | None:
    match = STORY_PATTERN.search(
        content
    )

    return match.group(1).strip() if match else None


def list_interventions() -> list[dict]:
    interventions = []

    for path in list_intervention_files():
        content = read_file(
            path
        )

        interventions.append(
            {
                "file": path.name,
                "id": intervention_id_from_filename(path.name),
                "story_id": extract_story_id(content),
                "status": extract_status(content),
            }
        )

    return interventions


def get_open_interventions_for_story(
        story_id: str
) -> list[dict]:
    return [
        item
        for item in list_interventions()
        if item["story_id"] == story_id and item["status"] == "OPEN"
    ]


def next_intervention_number() -> int:
    """
    Smallest unused UI-<N> number, derived from filenames only --
    never reused, monotonically increasing. No coordination needed:
    the orchestrator is single-instance and interventions are only
    ever created between story-execution attempts.
    """

    existing_numbers = []

    for path in list_intervention_files():
        intervention_id = intervention_id_from_filename(
            path.name
        )

        if not intervention_id:
            continue

        try:
            existing_numbers.append(
                int(intervention_id.split("-")[1])
            )
        except (IndexError, ValueError):
            continue

    return (max(existing_numbers) + 1) if existing_numbers else 1


def create_intervention_file(
        story_id: str,
        reason: str,
        claude_response: str,
) -> Path:
    """
    Deterministically write a new OPEN user-intervention file. Always
    called from Python (the orchestrator), never by the evaluator
    itself -- the evaluator only classifies the outcome as NEEDS_USER.
    The Claude response is preserved verbatim so a human can see
    exactly what was requested and why, without Python summarizing or
    editing it.
    """

    USER_INTERVENTIONS_DIR.mkdir(
        parents=True,
        exist_ok=True
    )

    intervention_id = f"UI-{next_intervention_number():03d}"
    filename = f"{intervention_id}-{story_id}.md"
    path = USER_INTERVENTIONS_DIR / filename

    content = (
        "# User Intervention\n"
        "\n"
        "## Status\n"
        "\n"
        "OPEN\n"
        "\n"
        "## Story\n"
        "\n"
        f"{story_id}\n"
        "\n"
        "## Reason\n"
        "\n"
        f"{reason.strip()}\n"
        "\n"
        "## Claude Response\n"
        "\n"
        f"{claude_response.strip()}\n"
        "\n"
        "## User Resolution\n"
        "\n"
        "TODO\n"
        "\n"
        "## Resolution Notes\n"
        "\n"
        "TODO\n"
    )

    path.write_text(
        content,
        encoding="utf-8"
    )

    return path
