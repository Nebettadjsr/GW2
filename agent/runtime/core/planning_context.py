"""Bounded, deterministic context for the two Codex roles.

Every planner/architect pass starts a fresh Codex session, so whatever is
supplied up front is re-sent in full at the start of the run and again with
every model request inside it. The measured cost of supplying the whole
historical `agent/stories/BACKLOG.md` that way is recorded in
`agent/runtime/reports/2026-09-26-planner-usage.md`: 139k characters, about
63% of the planning prompt, almost all of it completed-story narrative.

The rule here is therefore: supply what planning must *decide* with -- the
live queue in its authoritative order, every identifier needed for
deduplication and dependency ordering, and every unresolved question in
full -- and compact finished history into an index that names the
authoritative file to read when a detail actually matters. Nothing is lost;
history becomes a pointer instead of a copy.

These builders are pure text functions. They hold no path constants of their
own, so every caller keeps reading through the (patchable) paths in
support/config.py and these functions stay directly testable.
"""
import re

from agent.runtime.core.story_archive import extract_milestone
from agent.runtime.core.story_state import (
    classify_story_status,
    extract_dependencies_section,
    extract_status_section,
    extract_story_id,
    BACKLOG_ENTRY_STATUSES,
    format_backlog_entry,
    format_completed_backlog_entry,
    is_story_filename,
)
from agent.runtime.human.user_decisions import extract_section as markdown_section


# ============================================================
# Bounds
#
# Every bound below applies to *finished* history or to a description
# that exists in full in a named file. An unresolved question -- an OPEN
# user decision, an unresolved architect request, an OPEN Product Owner
# request -- is never truncated: its wording is what makes a duplicate
# recognizable and what the planner has to act on.
# ============================================================

QUEUE_DESCRIPTION_CHARS = 400
STORY_TITLE_CHARS = 120
DEPENDENCIES_CHARS = 140
OTHER_SECTION_CHARS = 1500

OPEN_QUESTION_CHARS = 1500
OPEN_BLOCKS_CHARS = 600
RESOLVED_QUESTION_CHARS = 300
RESOLVED_ANSWER_CHARS = 300

RESOLUTION_SUMMARY_CHARS = 240
ARCHITECT_DECISION_CHARS = 300

MAX_REFERENCES = 6

# Repository-relative Markdown paths cited in a resolution/decision body.
# These are the authoritative artifacts a compacted entry points at, so a
# planner that needs the detail can read exactly one file instead of being
# handed every receipt in full.
ARTIFACT_REFERENCE_PATTERN = re.compile(
    r"\b(?:docs|agent)/[A-Za-z0-9_./-]+\.md\b"
)

STORY_FILENAME_ID_PATTERN = re.compile(
    r"^(STORY-[A-Za-z0-9]+-\d+)",
    re.IGNORECASE,
)

# Every kind of prerequisite a story's '## Dependencies' section can name.
# These identifiers are listed in full and never truncated: dependency
# ordering is exactly what a bounded summary must not lose. The prose
# around them is summarized separately.
PREREQUISITE_ID_PATTERN = re.compile(
    r"\b(?:STORY-[A-Za-z0-9]+-\d+|UD-\d+|AR-\d+)\b",
    re.IGNORECASE,
)

BACKLOG_REFERENCE_PATTERN = re.compile(r"`([^`]+\.md)`")
COMPACT_BACKLOG_ENTRY_PATTERN = re.compile(
    r"^\s*[-*]\s*(STORY-[A-Za-z0-9]+-\d+)\s*\|\s*"
    r"([^|]+?\.md)\s*\|\s*([^|]+)\s*\|\s*(.*)$",
    re.I,
)

HEADING_PATTERN = re.compile(r"^(#{1,6})\s*(.+?)\s*$")

QUEUE_SECTIONS = ("active", "to do", "blocked")
COMPLETED_SECTIONS = ("done", "archived")


# ============================================================
# Text helpers
# ============================================================

def _line(text, limit: int) -> str:
    """One bounded single-line summary; whitespace collapsed."""

    collapsed = " ".join((text or "").split())

    if len(collapsed) <= limit:
        return collapsed

    return collapsed[:limit].rstrip() + " [...]"


def _body(text, limit: int) -> str:
    """A bounded multi-line body; paragraph structure preserved."""

    stripped = (text or "").strip()

    if len(stripped) <= limit:
        return stripped

    return stripped[:limit].rstrip() + "\n[...]"


def _references(text) -> str:
    """The authoritative artifacts a compacted entry points at."""

    seen = []

    for match in ARTIFACT_REFERENCE_PATTERN.finditer(text or ""):
        if match.group(0) not in seen:
            seen.append(match.group(0))

    if not seen:
        return ""

    shown = seen[:MAX_REFERENCES]

    return ", ".join(shown) + (
        f", (+{len(seen) - len(shown)} more in the file)"
        if len(seen) > len(shown) else ""
    )


def _indent(text: str, prefix: str = "  ") -> str:
    return "\n".join(
        prefix + line if line.strip() else line
        for line in text.splitlines()
    )


# ============================================================
# Story facts
#
# Read from each story file itself rather than from BACKLOG prose: the
# story file is the single source of truth for its ID, status, milestone
# and dependencies, and a backlog bullet's narrative is not.
# ============================================================

def _prerequisite_ids(dependencies_text) -> list[str]:
    identifiers = []

    for match in PREREQUISITE_ID_PATTERN.finditer(dependencies_text or ""):
        value = match.group(0).upper()

        if value not in identifiers:
            identifiers.append(value)

    return identifiers


def story_facts(paths) -> dict[str, dict]:
    facts = {}

    for path in paths:
        try:
            content = path.read_text(encoding="utf-8")
        except OSError:
            continue

        try:
            status = classify_story_status(
                extract_status_section(content)
            )
        except RuntimeError:
            status = "UNKNOWN"

        dependencies = extract_dependencies_section(content)

        facts[path.name] = {
            "id": extract_story_id(content) or "",
            "title": _line(
                markdown_section(content, "Title"), STORY_TITLE_CHARS
            ),
            "milestone": (extract_milestone(content) or "").strip(),
            "status": status,
            "prerequisites": _prerequisite_ids(dependencies),
            "dependencies": _line(dependencies, DEPENDENCIES_CHARS),
        }

    return facts


def _identifier(filename: str, fact: dict) -> str:
    if fact.get("id"):
        return fact["id"]

    match = STORY_FILENAME_ID_PATTERN.match(filename)

    return match.group(1).upper() if match else "(no Story ID)"


def _milestone(fact: dict) -> str:
    return fact.get("milestone") or "(milestone not recorded)"


def _status(fact: dict, fallback: str) -> str:
    if not fact:
        return "FILE MISSING"

    return fact.get("status") or fallback


# ============================================================
# Backlog index
#
# Replaces the full BACKLOG.md in the planning prompt. Queue sections
# (Active / To Do / Blocked) keep BACKLOG's own order -- that order *is*
# the execution priority the planner established -- and keep their
# descriptions, because those carry the current blocking/dependency
# narrative. Completed sections keep one deterministic line per story so
# that every ID and filename stays present for deduplication and
# milestone-coverage review, while the completion narrative stays in the
# story file it belongs to.
# ============================================================

INDEX_HEADER = """Deterministic index of agent/stories/BACKLOG.md, generated by the harness.

Active / To Do / Blocked keep BACKLOG's own order and wording: the To Do order
is the execution priority. Done / Archived entries are compacted to
`ID | filename | status | milestone | title`; every completed story's full text
remains in its own file (`agent/stories/<file>`, archived:
`agent/stories/archive/milestone-NN/<file>`), and its completion narrative
remains in the corresponding BACKLOG section. Read one of those only when a
specific detail decides something.

Every story ID and filename known to the project is listed here, so this index
is sufficient to detect a duplicate story and to see what a milestone already
covers."""


def _entry_filename(line: str) -> str | None:
    compact = COMPACT_BACKLOG_ENTRY_PATTERN.match(line)
    if compact and is_story_filename(compact.group(2).strip()):
        return compact.group(2).strip()

    legacy = re.match(r"^\s*[-*]\s*`([^`]+\.md)`", line)
    if legacy and is_story_filename(legacy.group(1)):
        return legacy.group(1)

    for name in BACKLOG_REFERENCE_PATTERN.findall(line):
        if is_story_filename(name):
            return name

    return None


def _entry_description(line: str) -> str:
    compact = COMPACT_BACKLOG_ENTRY_PATTERN.match(line)
    if compact:
        return compact.group(4).strip()
    return re.sub(
        r"^\s*[-*]\s*`[^`]+`\s*(?:[—:-]\s*)?", "", line
    ).strip()


def _queue_entry(filename: str, facts: dict, description: str) -> str:
    """One queue row, rendered in exactly the format BACKLOG.md holds.

    This index is the planner's view of the backlog, and the planner
    writes entries back into BACKLOG.md -- so whatever shape appears here
    is the shape that ends up in the file. It used to add
    "  dependency note: ..." and "  backlog entry: ..." continuation
    lines, which is precisely how supplementary prose got into the index:
    the planner mirrored its own input faithfully. The row now goes
    through format_backlog_entry(), the single canonical writer, and the
    description it was summarizing stays where it is owned -- in the
    story's own file, which this index already tells the planner how to
    read.
    """

    fact = facts.get(filename) or {}

    status = _status(fact, "TODO")
    milestone = _milestone(fact)

    # This index describes whatever is on disk, including a story whose
    # own file is missing or whose Status is unrecognized. Those are
    # reported as-is rather than raising: the planner needs to see the
    # broken row, and format_backlog_entry() only accepts the statuses a
    # well-formed entry may carry.
    if status not in BACKLOG_ENTRY_STATUSES:
        return "- " + " | ".join([
            _identifier(filename, fact),
            filename,
            status,
            milestone,
            "deps: " + (", ".join(fact.get("prerequisites") or []) or "None"),
        ])

    return format_backlog_entry(
        _identifier(filename, fact),
        filename,
        status,
        milestone if milestone.startswith("milestone-") else "unassigned",
        fact.get("prerequisites") or [],
    )


def _section_status(section: str | None) -> str:
    return {
        "active": "ACTIVE",
        "to do": "TODO",
        "blocked": "BLOCKED",
        "done": "DONE",
        "archived": "DONE",
    }.get((section or "").strip().lower(), "UNKNOWN")


def _completed_entry(filename: str, facts: dict) -> str:
    """One '## Done' row, in the same canonical spelling the file holds."""

    fact = facts.get(filename) or {}

    return format_completed_backlog_entry(
        _identifier(filename, fact),
        filename,
        _milestone(fact),
        fact.get("title") or "",
    )


def _split_blocks(text: str) -> list[tuple[int, str | None, list[str]]]:
    """[(heading level, heading, body lines)]; level 0 is the preamble."""

    blocks: list[tuple[int, str | None, list[str]]] = [(0, None, [])]

    for line in text.splitlines():
        match = HEADING_PATTERN.match(line)

        if match:
            blocks.append((len(match.group(1)), match.group(2), []))
        else:
            blocks[-1][2].append(line)

    return blocks


def _section_kind(section: str | None) -> str:
    normalized = (section or "").strip().lower()

    if normalized in QUEUE_SECTIONS:
        return "queue"

    if normalized in COMPLETED_SECTIONS:
        return "completed"

    return "other"


def _coverage_lines(coverage: dict) -> list[str]:
    if not coverage:
        return []

    lines = [
        "",
        "## Milestone Coverage (derived from the story files listed above)",
        "",
    ]

    for milestone in sorted(coverage):
        counts = coverage[milestone]

        lines.append(
            f"- {milestone}: "
            + ", ".join(
                f"{count} {status}"
                for status, count in sorted(counts.items())
            )
        )

    return lines


def backlog_index(backlog_text: str, facts: dict) -> str:
    lines = [INDEX_HEADER]
    coverage: dict[str, dict[str, int]] = {}
    section = None
    # Title and dependency wording for the queued stories, collected as a
    # separate block below the reconstructed sections. The planner needs
    # this wording to recognize a duplicate, but it must never appear
    # inside something shaped like a BACKLOG section: when it did, the
    # planner copied it back into the real file as "backlog entry:" and
    # "dependency note:" continuation lines, and the entry movers then
    # orphaned them on the next transition.
    summaries: list[str] = []

    for level, heading, body in _split_blocks(backlog_text):
        # The document title and its preamble describe the file's own
        # conventions to a human reader; the header above states them
        # for this index instead.
        if level < 2:
            continue

        if level == 2:
            section = heading

        lines.extend(["", f"{'#' * level} {heading}", ""])

        kind = _section_kind(section)

        if kind == "other":
            lines.append(_body("\n".join(body), OTHER_SECTION_CHARS))
            continue

        for line in body:
            if not line.strip():
                continue

            filename = _entry_filename(line)

            if filename is None:
                # A placeholder such as "_(none)_", or a note the human
                # left in the section. Keep it: it is queue state too.
                lines.append(_line(line, OTHER_SECTION_CHARS))
                continue

            if filename not in facts:
                lines.append(
                    f"- {_identifier(filename, {})} | {filename} | FILE MISSING"
                )
                continue

            fact = facts.get(filename) or {}

            section_status = _section_status(section)
            coverage.setdefault(_milestone(fact), {}).setdefault(section_status, 0)
            coverage[_milestone(fact)][section_status] += 1

            if kind == "completed":
                index_fact = dict(fact, status=section_status)
                lines.append(_completed_entry(filename, {filename: index_fact}))
            else:
                # The backlog's section is authoritative for queue state;
                # the story's own Status remains authoritative elsewhere.
                index_fact = dict(fact, status=section_status)
                lines.append(_queue_entry(
                    filename, {filename: index_fact}, _entry_description(line)
                ))

                identifier = _identifier(filename, fact)
                title = fact.get("title") or _entry_description(line)
                note = (fact.get("dependencies") or "").strip()

                if title:
                    summaries.append(
                        f"{identifier}: {_line(title, QUEUE_DESCRIPTION_CHARS)}"
                    )

                if note and note.strip(".").lower() != "none":
                    summaries.append(
                        f"{identifier} dependencies: "
                        f"{_line(note, DEPENDENCIES_CHARS)}"
                    )

    if summaries:
        lines.extend([
            "",
            "QUEUED STORY SUMMARIES (index only -- never written to "
            "BACKLOG.md)",
            "",
            *summaries,
        ])

    lines.extend(_coverage_lines(coverage))

    return "\n".join(lines).strip() + "\n"


# ============================================================
# User decisions
#
# An OPEN decision is live planning input and stays in full. A RESOLVED
# one is kept as question + answer, bounded: the question is what makes a
# duplicate decision recognizable (validate_planning_result rejects a new
# decision whose question repeats an existing one), and the answer is
# established policy the planner has to plan with. Everything else about
# it lives in agent/user-decisions/<file>.
# ============================================================

def decision_index(decisions) -> str:
    blocks = []

    for decision in decisions:
        content = decision.get("content") or ""
        identifier = decision.get("id") or "(no UD ID)"
        status = decision.get("status") or "UNKNOWN"
        question = markdown_section(content, "Decision Needed")
        answer = markdown_section(content, "User Decision")

        if status == "RESOLVED":
            blocks.append(
                f"- {identifier} (RESOLVED), {decision['file']}\n"
                f"  Question: {_line(question, RESOLVED_QUESTION_CHARS) or '(missing)'}\n"
                f"  Answer: {_line(answer, RESOLVED_ANSWER_CHARS) or '(missing)'}"
            )
            continue

        blocks.append(
            f"- {identifier} ({status}), {decision['file']}\n"
            f"  Question:\n{_indent(_body(question, OPEN_QUESTION_CHARS) or '(missing)', '    ')}\n"
            f"  Blocks: {_line(markdown_section(content, 'Blocks'), OPEN_BLOCKS_CHARS) or '(unspecified)'}\n"
            f"  Answer: {_line(answer, OPEN_BLOCKS_CHARS) or 'TODO'}"
        )

    return "\n\n".join(blocks) or "(none)"


# ============================================================
# Resolved Product Owner receipts
#
# Deduplication input only: the planner must recognize that a request was
# already covered and must never reprocess it. The receipt's own prose is
# not authoritative -- the artifacts it cites are -- so the compact entry
# keeps a summary plus those citations.
# ============================================================

def resolved_request_index(requests) -> str:
    lines = []

    for request in requests:
        resolution = markdown_section(
            request.get("content") or "", "Planner Resolution"
        ) or ""

        references = _references(resolution)

        lines.append(
            f"- {request['file']} | RESOLVED | "
            + (_line(resolution, RESOLUTION_SUMMARY_CHARS) or "(no receipt)")
            + (f" | covered by: {references}" if references else "")
        )

    return "\n".join(lines) or "(none)"


# ============================================================
# Architect requests
#
# Unresolved requests stay in full -- their question text is what makes a
# duplicate recognizable, and the planner may have to plan around them. A
# resolved one becomes a decision line plus the documents the architect
# actually updated, which are the authoritative home of that decision.
# ============================================================

def architect_request_index(requests) -> str:
    if not requests:
        return "(no files under agent/architect-requests/)"

    blocks = []

    for request in requests:
        if request["status"] != "RESOLVED":
            blocks.append(
                f"--- BEGIN {request['file']} ({request['status']}) ---\n"
                f"{request['content']}\n"
                f"--- END {request['file']} ---"
            )
            continue

        decision = markdown_section(
            request["content"], "Architect Decision"
        ) or ""
        resolution = markdown_section(request["content"], "Resolution") or ""
        references = _references(decision + "\n" + resolution)

        blocks.append(
            f"- {request['id'] or request['file']} (RESOLVED): "
            f"{request['title'] or request['file']}\n"
            f"  Architect decision: "
            + (_line(decision, ARCHITECT_DECISION_CHARS) or "(not recorded)")
            + (f"\n  Recorded in: {references}" if references else "")
        )

    return "\n\n".join(blocks)


# ============================================================
# Prompt size diagnostics (terminal only)
#
# Prints how many characters each supplied section costs before a role
# runs, never the sections themselves. Characters are not tokens: this
# measures what the harness supplies, while the runner reports what the
# model actually charged.
# ============================================================

def context_report(role: str, sections: dict, prompt: str) -> str:
    lines = [f"{role} context:"]
    supplied = 0

    for label, text in sections.items():
        size = len(text or "")
        supplied += size
        lines.append(f"  {label}: {size:,} chars")

    lines.append(
        f"  harness prompt scaffolding: {max(0, len(prompt) - supplied):,} chars"
    )
    lines.append(
        f"  total supplied context: {len(prompt):,} chars "
        "(prompt only; tool output during the run adds to it)"
    )

    return "\n".join(lines)
