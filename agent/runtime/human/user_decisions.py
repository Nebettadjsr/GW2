from pathlib import Path
import re

from agent.runtime.support.config import USER_DECISIONS_DIR


# ============================================================
# Canonical user-decision files under agent/user-decisions/
#
# One file per unresolved decision (UD-<NUMBER>-<slug>.md). These
# helpers only read and classify what is already on disk -- creating,
# resolving, or referencing a decision is a planning-prompt/story
# responsibility, not something this module decides on its own.
# ============================================================

DECISION_TITLE_PATTERN = re.compile(
    r"^#\s*(UD-\d+)\s*(?:[—-]\s*(.*))?\s*$",
    re.MULTILINE,
)

DECISION_FILENAME_PATTERN = re.compile(r"^(UD-\d+)(?:-[A-Za-z0-9_-]+)?\.md$")

STATUS_PATTERN = re.compile(
    r"##\s*Status\s*\n+\s*(OPEN|RESOLVED)\b",
    re.IGNORECASE,
)

REQUIRED_DECISION_HEADINGS = [
    "Status",
    "Decision Needed",
    "Why This Is Needed",
    "Context",
    "Blocks",
    "External Input Possibly Required",
    "User Decision",
    "Resolution",
]

PLACEHOLDER_VALUES = {
    "todo",
}


def _section_text(
    content: str,
    heading: str
) -> str | None:
    # [ \t]*\n, not \s*\n+ -- see files.find_section_span()'s
    # fix/rationale; the same greedy-whitespace-starves-the-lookahead
    # bug applies here.
    pattern = re.compile(
        rf"##\s*{re.escape(heading)}[ \t]*\n(.*?)(?=\n##\s|\Z)",
        re.DOTALL | re.IGNORECASE,
    )

    match = pattern.search(
        content
    )

    return match.group(1).strip() if match else None


def extract_section(
    content: str,
    heading: str
) -> str | None:
    return _section_text(
        content,
        heading
    )


def extract_decision_id(
    content: str
) -> str | None:
    match = DECISION_TITLE_PATTERN.search(
        content
    )

    return match.group(1) if match else None


def extract_title(
    content: str
) -> str | None:
    match = DECISION_TITLE_PATTERN.search(
        content
    )

    if match and match.group(2):
        return match.group(2).strip()

    return None


def extract_status(
    content: str
) -> str | None:
    match = STATUS_PATTERN.search(
        content
    )

    return match.group(1).upper() if match else None


def is_placeholder(
    section_text: str | None
) -> bool:
    if section_text is None:
        return True

    return (
        section_text.strip().strip(".").lower()
        in PLACEHOLDER_VALUES
    )


def missing_required_headings(
    content: str
) -> list[str]:
    return [
        heading
        for heading in REQUIRED_DECISION_HEADINGS
        if not re.search(
            rf"^##\s*{re.escape(heading)}\s*$",
            content,
            re.IGNORECASE | re.MULTILINE,
        )
    ]


def list_decision_files() -> list[Path]:
    if not USER_DECISIONS_DIR.exists():
        return []

    return sorted(
        USER_DECISIONS_DIR.glob("UD-*.md")
    )


def list_decisions() -> list[dict]:
    decisions = []

    for path in list_decision_files():
        content = path.read_text(
            encoding="utf-8"
        )

        filename_id = DECISION_FILENAME_PATTERN.fullmatch(path.name)
        decisions.append(
            {
                "file": path.name,
                "id": filename_id.group(1) if filename_id else None,
                "title": extract_title(content),
                "status": extract_status(content),
            }
        )

    return decisions


def get_open_decisions() -> list[dict]:
    return [
        decision
        for decision in list_decisions()
        if decision["status"] == "OPEN"
    ]


def find_duplicate_decision_ids() -> list[str]:
    seen = set()
    duplicates = set()

    for decision in list_decisions():
        decision_id = decision["id"]

        if not decision_id:
            continue

        if decision_id in seen:
            duplicates.add(decision_id)

        seen.add(decision_id)

    return sorted(duplicates)


def create_qa_user_decision(story_id: str, question: str, context: str = "") -> str:
    """Create a normal Product Owner decision from a validated QA clarification."""
    USER_DECISIONS_DIR.mkdir(parents=True, exist_ok=True)
    for existing in list_decision_files():
        content = existing.read_text(encoding="utf-8")
        if (story_id in content and question.strip() in content
                and extract_status(content) == "OPEN"):
            decision_id = extract_decision_id(content)
            if decision_id:
                return decision_id
    used = [
        int(match.group(1))
        for path in list_decision_files()
        if (match := re.match(r"^UD-(\d+)", path.name))
    ]
    number = max(used, default=0) + 1
    decision_id = f"UD-{number:03d}"
    slug = re.sub(r"[^a-z0-9]+", "-", question.lower()).strip("-")[:48] or "qa-clarification"
    path = USER_DECISIONS_DIR / f"{decision_id}-{slug}.md"
    path.write_text(
        f"# {decision_id} — QA clarification for {story_id}\n\n"
        "## Status\n\nOPEN\n\n"
        "## Decision Needed\n\n"
        f"{question.strip()}\n\n"
        "## Why This Is Needed\n\n"
        "The independent QA review found that implementation requirements are insufficient to define a reliable expected result.\n\n"
        "## Context\n\n"
        f"Story: {story_id}\n\n{context.strip() or 'See the story and its QA plan.'}\n\n"
        "## Blocks\n\n"
        f"Implementation of {story_id}.\n\n"
        "## External Input Possibly Required\n\n"
        "Product Owner decision.\n\n"
        "## User Decision\n\nTODO\n\n"
        "## Resolution\n\nTODO\n",
        encoding="utf-8",
    )
    return decision_id
