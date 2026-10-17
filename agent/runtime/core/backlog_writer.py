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


def sync_user_requests(backlog_file: Path | None = None) -> list[str]:
    """Refresh the navigational User section from canonical OPEN UD/UI records.

    Resolution stays with the existing request owner. This index never answers
    a request, moves a story, or changes selection eligibility.
    """
    from agent.runtime.human import user_decisions, user_interventions

    target = backlog_file or BACKLOG_FILE
    content = read_file(target)
    if not target.exists():
        raise BacklogWriteRejected(f"Backlog does not exist: {target}")
    rows = []
    for directory, records in (
        ("user-decisions", user_decisions.list_decisions()),
        ("user-interventions", user_interventions.list_interventions()),
    ):
        for record in records:
            if record["status"] == "OPEN":
                rows.append(f"- [{record['id'] or record['file']}](../{directory}/{record['file']})")

    span = find_section_span(content, "User")
    if span is None:
        # Place it between Blocked and Superseded without touching either body.
        blocked = find_section_span(content, "Blocked")
        if blocked is None:
            raise BacklogWriteRejected("Backlog has no '## Blocked' section.")
        position = blocked[1]
        if content[position:position + 1] == "\n":
            position += 1  # keep the preceding section's terminating blank line
        content = content[:position] + "## User\n\n" + content[position:]
        span = find_section_span(content, "User")
    start, end = span
    body = ("\n" + "\n".join(rows) + "\n") if rows else ""
    updated = content[:start] + body + content[end:]
    if updated != read_file(target):
        _write_validated(updated, target)
    return rows


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


# ------------------------------------------------------------------
# Planner backlog actions
# ------------------------------------------------------------------
#
# The planner never edits BACKLOG.md. It lists the changes it wants as
# `backlog_actions` in PLANNING_RESULT.json and the harness applies them
# here, deriving every entry field from the story file itself, so the
# planner neither re-emits index text nor can misspell an entry.

BACKLOG_ACTION_COMMANDS = ("add_to_do", "move", "reorder_to_do")
_MOVE_TARGETS = {"To Do": "TODO", "Blocked": "BLOCKED", "Superseded": "SUPERSEDED"}
_PLANNER_MOVABLE_FROM = ("To Do", "Blocked", "Superseded")


def _story_file(reference) -> Path:
    """A story file under agent/stories/ named by filename or story ID."""
    from agent.runtime.support.config import STORIES_DIR

    name = str(reference or "").strip()
    if not name:
        raise BacklogWriteRejected("A backlog action names no story.")
    if name.endswith(".md"):
        path = STORIES_DIR / name
        if path.parent == STORIES_DIR and path.is_file():
            return path
    else:
        matches = sorted(STORIES_DIR.glob(f"{name}-*.md"))
        if len(matches) == 1:
            return matches[0]
    raise BacklogWriteRejected(f"No unique story file under agent/stories/ for {name!r}.")


def check_backlog_actions(actions) -> list[str]:
    """Shape and reference problems, without writing anything (planning_check)."""
    if not isinstance(actions, list) or not all(isinstance(action, dict) for action in actions):
        return ["backlog_actions must be a list of objects."]
    problems = []
    for action in actions:
        command = action.get("command")
        try:
            if command == "add_to_do":
                _story_file(action.get("story"))
            elif command == "move":
                _story_file(action.get("story"))
                if action.get("to") not in _MOVE_TARGETS:
                    problems.append(f"move target must be one of {', '.join(_MOVE_TARGETS)}: {action.get('to')!r}")
                if action.get("to") in ("Blocked", "Superseded") and not str(action.get("reason") or "").strip():
                    problems.append(f"move to {action['to']} needs a reason: {action.get('story')!r}")
            elif command == "reorder_to_do":
                if not isinstance(action.get("order"), list):
                    problems.append("reorder_to_do needs an 'order' list.")
                for reference in action.get("order") or []:
                    _story_file(reference)
            else:
                problems.append(f"Unknown backlog action {command!r}; use one of "
                                + ", ".join(BACKLOG_ACTION_COMMANDS) + ".")
        except BacklogWriteRejected as error:
            problems.append(str(error))
    return problems


def apply_backlog_actions(actions, backlog_file: Path | None = None) -> list[str]:
    """Apply validated planner actions in order; returns problems (nothing written then).

    Moves are harness transitions: the story's own Status (and Blockers) is
    set to match the section, exactly as the harness's other transitions do.
    """
    from agent.runtime.core import story_state
    from agent.runtime.core.story_archive import extract_milestone

    problems = check_backlog_actions(actions)
    if problems:
        return problems
    target = backlog_file or BACKLOG_FILE
    content = strip_backlog_prose(read_file(target))
    story_writes: dict[Path, str] = {}

    def story_content(path: Path) -> str:
        return story_writes.get(path) or read_file(path)

    try:
        for action in actions:
            command = action["command"]
            if command == "add_to_do":
                path = _story_file(action["story"])
                text = story_content(path)
                listed = {name for heading in ("Active", "To Do", "Blocked", "Superseded", "Done", "Archived")
                          for name in parse_backlog_section(content, heading)}
                if path.name in listed:
                    continue
                status = story_state.classify_story_status(story_state.extract_status_section(text))
                status = "BLOCKED" if status == "BLOCKED" else "TODO"
                entry = format_backlog_entry(
                    story_state.extract_story_id(text) or path.stem, path.name, status,
                    extract_milestone(text) or "",
                    story_state.parse_dependency_ids(story_state.extract_dependencies_section(text)) or "None")
                # A story created already blocked is listed where it belongs.
                content = story_state._append_bullet_to_backlog_section(
                    content, "Blocked" if status == "BLOCKED" else "To Do", entry)
            elif command == "move":
                path = _story_file(action["story"])
                section = action["to"]
                bullet = None
                for source in _PLANNER_MOVABLE_FROM:
                    content, bullet = story_state._pull_bullet_from_backlog_section(content, source, path.name)
                    if bullet is not None:
                        break
                if bullet is None:
                    raise BacklogWriteRejected(
                        f"Cannot move {path.name}: it is not in " + ", ".join(_PLANNER_MOVABLE_FROM) + ".")
                status = _MOVE_TARGETS[section]
                content = story_state._append_bullet_to_backlog_section(
                    content, section, story_state.rewrite_backlog_entry_status(bullet, status))
                text = story_state._replace_section_body(story_content(path), "Status", status)
                text = story_state._replace_section_body(
                    text, "Blockers", str(action.get("reason") or "None.").strip() if status == "BLOCKED" else
                    ("None. Superseded, not blocked: " + str(action["reason"]).strip()
                     if status == "SUPERSEDED" else "None."))
                story_writes[path] = text
            elif command == "reorder_to_do":
                names = [_story_file(reference).name for reference in action["order"]]
                current = parse_backlog_section(content, "To Do")
                if sorted(names) != sorted(current):
                    raise BacklogWriteRejected(
                        "reorder_to_do must list every '## To Do' story exactly once; "
                        f"To Do holds {current}.")
                bullets = []
                for name in names:
                    content, bullet = story_state._pull_bullet_from_backlog_section(content, "To Do", name)
                    bullets.append(bullet)
                for bullet in bullets:
                    content = story_state._append_bullet_to_backlog_section(content, "To Do", bullet)
    except (RuntimeError, ValueError, TypeError) as error:  # BacklogWriteRejected included
        return [str(error)]

    problems = validate_backlog_entries(content)
    if problems:
        return problems
    for path, text in story_writes.items():
        path.write_text(text, encoding="utf-8")
    target.write_text(content, encoding="utf-8")
    return []
