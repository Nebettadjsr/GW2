"""The only supported way to add or change an entry in agent/stories/BACKLOG.md.

BACKLOG.md is read by code: `story_state.get_selectable_story_candidates()`
walks `## To Do` top to bottom and hands the first executable entry to the
orchestrator. An entry it cannot parse is an entry that does not exist, and a
section it cannot parse is an empty queue.

Before this module, three writers edited that file. The harness moved bullets
between sections (single-line surgery), `planning_context` rendered an index of
it with extra "backlog entry:"/"dependency note:" continuation lines, and the
planner was told in its own prompt to "add your entries with a small Python
script". Each had its own idea of the format. The observable results, all from
one real run: a completed story's entry stayed under `## Active` because no
Done transition existed at all; a planner rewrote `## To Do` from the rendered
index, putting supplementary prose lines *above* their bullets; and an earlier
activation had already orphaned one of those prose lines, which the planner then
noticed and tried to clean up by hand.

So entries are produced here, through `story_state.format_backlog_entry()`, and
nowhere else. Every function in this module validates the whole file before it
writes and raises without writing if the result would not parse -- a malformed
index is never published, not even briefly.
"""
from pathlib import Path

from agent.runtime.support.config import BACKLOG_FILE
from agent.runtime.support.files import read_file
from agent.runtime.core.story_state import (
    find_section_span,
    format_backlog_entry,
    parse_backlog_section,
    strip_backlog_prose,
    validate_backlog_entries,
)


class BacklogWriteRejected(RuntimeError):
    """The requested write would leave BACKLOG.md unparseable."""


def _write_validated(content: str, backlog_file: Path) -> None:
    problems = validate_backlog_entries(content)

    if problems:
        raise BacklogWriteRejected(
            "Refusing to write agent/stories/BACKLOG.md; the result would not "
            "be a machine-readable index:\n"
            + "\n".join(f"- {problem}" for problem in problems)
        )

    backlog_file.write_text(content, encoding="utf-8")


def add_to_do_entries(
        entries,
        backlog_file: Path | None = None,
) -> list[str]:
    """Append entries to '## To Do' in the given execution-priority order.

    `entries` is a sequence of (story_id, filename, milestone, dependencies).
    A story already listed anywhere in the index is skipped rather than
    duplicated, and the filenames actually added are returned.
    """

    target = backlog_file or BACKLOG_FILE
    content = strip_backlog_prose(read_file(target))

    listed = {
        filename
        for heading in ("Active", "To Do", "Blocked", "Superseded", "Done", "Archived")
        for filename in parse_backlog_section(content, heading)
    }

    rows = []
    added = []

    try:
        for story_id, filename, milestone, dependencies in entries:
            if filename in listed:
                continue

            rows.append(
                format_backlog_entry(
                    story_id, filename, "TODO", milestone, dependencies
                )
            )
            added.append(filename)
    except (ValueError, TypeError) as error:
        raise BacklogWriteRejected(
            f"Refusing to write agent/stories/BACKLOG.md: {error}"
        ) from error

    if not rows:
        _write_validated(content, target)
        return []

    span = find_section_span(content, "To Do")

    if span is None:
        raise BacklogWriteRejected(
            "agent/stories/BACKLOG.md has no '## To Do' section."
        )

    start, end = span
    body = content[start:end].rstrip("\n")

    # An empty or placeholder-only section starts fresh; a populated one keeps
    # its order and gains the new rows at the end.
    if not body.strip() or body.strip().lower().startswith(("_(none", "(none")):
        body = ""

    new_body = (body + "\n" if body else "") + "\n".join(rows) + "\n"

    _write_validated(content[:start] + new_body + content[end:], target)

    return added


def rewrite_section(
        heading: str,
        entries,
        backlog_file: Path | None = None,
) -> None:
    """Replace one section's entries wholesale, in the order given.

    `entries` is a sequence of (story_id, filename, status, milestone,
    dependencies). Used for a reordering that is genuinely a re-prioritization
    rather than a single move; the per-transition movers in `story_state`
    remain the right tool for a story changing state.
    """

    target = backlog_file or BACKLOG_FILE
    content = strip_backlog_prose(read_file(target))

    span = find_section_span(content, heading)

    if span is None:
        raise BacklogWriteRejected(
            f"agent/stories/BACKLOG.md has no '## {heading}' section."
        )

    try:
        rows = [
            format_backlog_entry(story_id, filename, status, milestone, dependencies)
            for story_id, filename, status, milestone, dependencies in entries
        ]
    except (ValueError, TypeError) as error:
        # One rejection type for the caller, and the file is left untouched:
        # a write is refused before anything is published, never halfway.
        raise BacklogWriteRejected(
            f"Refusing to write agent/stories/BACKLOG.md: {error}"
        ) from error

    start, end = span
    new_body = ("\n".join(rows) + "\n") if rows else "\n"

    _write_validated(content[:start] + new_body + content[end:], target)


def clean(backlog_file: Path | None = None) -> list[str]:
    """Drop supplementary prose, then report whatever problems remain.

    Separate from the writers so an already-damaged index can be repaired
    without also changing the queue. Returns the remaining problems, which this
    cannot fix mechanically (a malformed entry needs a decision about what it
    was meant to say).
    """

    target = backlog_file or BACKLOG_FILE
    content = read_file(target)
    cleaned = strip_backlog_prose(content)

    if cleaned != content:
        target.write_text(cleaned, encoding="utf-8")

    return validate_backlog_entries(cleaned)
