from pathlib import Path
import re
import shutil

from agent.runtime.support.config import ARCHIVE_DIR, BACKLOG_FILE, CURRENT_STORY_FILE, STORIES_DIR
from agent.runtime.support.files import find_section_span, read_file
from agent.runtime.core.story_state import (
    COMPACT_BACKLOG_ENTRY, LEGACY_BACKLOG_ENTRY, classify_story_status, extract_status_section,
)


# ============================================================
# Milestone-based story archiving
#
# Archiving moves DONE and SUPERSEDED stories out of agent/stories/
# into agent/stories/archive/<milestone-slug>/ once a roadmap milestone
# transition has been confirmed (never on an individual story
# reaching DONE). This module only ever MOVES files -- it never
# deletes history -- and only ever archives a story whose OWN
# canonical file states a finished Status and a "## Milestone" value
# no later than the closed one. Milestone membership is never inferred from a filename or
# from general project/roadmap context.
#
# Milestone slug convention: "milestone-NN", where NN is the current
# docs/ROADMAP.md Phase number, two digits, zero-padded (Phase 0 ->
# "milestone-00", Phase 1 -> "milestone-01", ...). This reuses the
# roadmap's own numbering instead of inventing a second one.
# ============================================================

MILESTONE_SLUG_PATTERN = re.compile(
    r"^milestone-\d{2}$"
)

# [ \t]*\n, not \s*\n+ -- see files.find_section_span()'s fix/rationale;
# the same greedy-whitespace-starves-the-lookahead bug applies here.
MILESTONE_SECTION_PATTERN = re.compile(
    r"##\s*Milestone[ \t]*\n(.*?)(?=\n##\s|\Z)",
    re.DOTALL | re.IGNORECASE,
)


def milestone_slug_for_phase(
    phase_number: int
) -> str:
    if phase_number < 0:
        raise ValueError(
            "phase_number must be >= 0"
        )

    return f"milestone-{phase_number:02d}"


def is_valid_milestone_slug(
    slug: str
) -> bool:
    return bool(
        MILESTONE_SLUG_PATTERN.match(
            slug.strip()
        )
    )


def extract_milestone(
    story_content: str
) -> str | None:
    match = MILESTONE_SECTION_PATTERN.search(
        story_content
    )

    if not match:
        return None

    value = match.group(1).strip()

    return value or None


# ============================================================
# Path resolution / safety
# ============================================================

def resolve_archive_dir(
    milestone_slug: str
) -> Path:
    slug = milestone_slug.strip()

    if not is_valid_milestone_slug(slug):
        raise ValueError(
            f"Invalid milestone slug: {milestone_slug!r} "
            "(expected e.g. 'milestone-00')."
        )

    archive_root = ARCHIVE_DIR.resolve()
    target = (ARCHIVE_DIR / slug).resolve()

    if target != archive_root and archive_root not in target.parents:
        raise ValueError(
            "Resolved archive path escapes "
            f"agent/stories/archive/: {target}"
        )

    return target


def is_archived_path(
    path: Path
) -> bool:
    resolved = path.resolve()
    archive_root = ARCHIVE_DIR.resolve()

    return (
        resolved == archive_root
        or archive_root in resolved.parents
    )


def list_archived_story_files(
    milestone_slug: str | None = None
) -> list[Path]:
    if milestone_slug is not None:
        target = resolve_archive_dir(
            milestone_slug
        )

        if not target.exists():
            return []

        return sorted(
            target.glob("*.md")
        )

    if not ARCHIVE_DIR.exists():
        return []

    return sorted(
        ARCHIVE_DIR.glob("**/*.md")
    )


def _current_active_story_filename() -> str | None:
    if not CURRENT_STORY_FILE.exists():
        return None

    pointer = CURRENT_STORY_FILE.read_text(
        encoding="utf-8"
    ).strip()

    if not pointer:
        return None

    return pointer.replace(
        "\\", "/"
    ).rsplit("/", 1)[-1]


# ============================================================
# Eligibility / archiving
# ============================================================

FINISHED_STATUSES = ("DONE", "SUPERSEDED")


def _milestone_number(slug: str) -> int:
    return int(slug.strip().rsplit("-", 1)[1])


def get_archivable_stories(
    milestone_slug: str
) -> dict:
    """
    Deterministically re-derive, from each story file's own Status and
    Milestone sections, which stories directly under agent/stories/
    (never agent/stories/archive/) are finished history once the given
    milestone closes: every DONE or SUPERSEDED story of that milestone
    or an earlier one. Earlier milestones are included so a story that
    was finished or retired after its own milestone was archived does
    not linger in BACKLOG.md for good. Never trusts a planning run's own
    claim about which stories belong to a milestone.
    """

    if not is_valid_milestone_slug(milestone_slug):
        raise ValueError(
            f"Invalid milestone slug: {milestone_slug!r}"
        )

    closed = _milestone_number(milestone_slug)
    active_story_filename = _current_active_story_filename()

    eligible = []
    skipped_not_done = []
    skipped_other_milestone = []
    skipped_no_milestone = []
    skipped_active = []

    for path in sorted(STORIES_DIR.glob("*.md")):
        if path.name.lower() == "backlog.md":
            continue

        # STORIES_DIR.glob("*.md") is already non-recursive, so this
        # can't actually match anything under archive/ -- kept as an
        # explicit, defensive statement of the invariant.
        if is_archived_path(path):
            continue

        if path.name == active_story_filename:
            skipped_active.append(path.name)
            continue

        content = read_file(
            path
        )

        status_text = extract_status_section(
            content
        )

        if classify_story_status(status_text) not in FINISHED_STATUSES:
            skipped_not_done.append(path.name)
            continue

        story_milestone = (extract_milestone(content) or "").strip()

        if not is_valid_milestone_slug(story_milestone):
            skipped_no_milestone.append(path.name)
            continue

        if _milestone_number(story_milestone) > closed:
            skipped_other_milestone.append(path.name)
            continue

        eligible.append((path, story_milestone))

    return {
        "eligible": eligible,
        "skipped_not_done": skipped_not_done,
        "skipped_other_milestone": skipped_other_milestone,
        "skipped_no_milestone": skipped_no_milestone,
        "skipped_active": skipped_active,
    }


def archive_milestone_stories(
    milestone_slug: str
) -> dict:
    """
    Move every finished story of the given milestone or an earlier one
    (see get_archivable_stories) from agent/stories/ into
    agent/stories/archive/<its own milestone>/, then drop their rows
    from BACKLOG.md. Never overwrites an existing archived file (a name
    collision is recorded as a conflict and that file is left where it
    is); never archives the currently active story; never deletes
    anything.
    """

    breakdown = get_archivable_stories(
        milestone_slug
    )

    archived = []
    conflicts = []
    errors = []

    for path, story_milestone in breakdown["eligible"]:
        target_dir = resolve_archive_dir(
            story_milestone
        )
        destination = target_dir / path.name

        if destination.exists():
            conflicts.append(
                path.name
            )
            continue

        try:
            target_dir.mkdir(
                parents=True,
                exist_ok=True
            )
            shutil.move(
                str(path),
                str(destination)
            )
            archived.append(
                path.name
            )
        except OSError as exc:
            errors.append(
                f"{path.name}: {exc}"
            )

    backlog_updated = False

    if archived:
        backlog_content = read_file(
            BACKLOG_FILE
        )

        updated_content = update_backlog_for_archive(
            backlog_content,
            archived
        )

        if updated_content != backlog_content:
            BACKLOG_FILE.write_text(
                updated_content,
                encoding="utf-8"
            )
            backlog_updated = True

    return {
        "milestone": milestone_slug,
        "archived": archived,
        "conflicts": conflicts,
        "errors": errors,
        "backlog_updated": backlog_updated,
        "skipped_not_done": breakdown["skipped_not_done"],
        "skipped_other_milestone": breakdown["skipped_other_milestone"],
        "skipped_no_milestone": breakdown["skipped_no_milestone"],
        "skipped_active": breakdown["skipped_active"],
    }


# ============================================================
# BACKLOG.md bookkeeping
#
# BACKLOG.md indexes the current milestone only. An archived story's
# row leaves "## Done" or "## Superseded" and is not written anywhere
# else: agent/stories/archive/milestone-NN/ is the record of a closed
# milestone, and duplicate story IDs are checked against those files
# directly. The historical "## Archived" index is dropped on the first
# archive that finds one.
# ============================================================

ARCHIVED_SECTION_PATTERN = re.compile(
    r"^##[ \t]*Archived[ \t]*\n.*?(?=^##\s|\Z)",
    re.DOTALL | re.IGNORECASE | re.MULTILINE,
)


def _entry_filename(line: str) -> str | None:
    """The story filename of a backlog row: legacy (backticked) or compact (`ID | file | ...`)."""
    legacy = LEGACY_BACKLOG_ENTRY.match(line)
    if legacy:
        return legacy.group(1)
    compact = COMPACT_BACKLOG_ENTRY.match(line)
    return compact.group(2).strip() if compact else None


def update_backlog_for_archive(
    backlog_content: str,
    archived_filenames: list
) -> str:
    if not archived_filenames:
        return backlog_content

    content = backlog_content

    for heading in ("Done", "Superseded"):
        span = find_section_span(
            content,
            heading
        )

        if span is None:
            continue

        start, end = span
        body = "".join(
            line
            for line in content[start:end].splitlines(keepends=True)
            if _entry_filename(line) not in archived_filenames
        )
        content = content[:start] + body + content[end:]

    content = ARCHIVED_SECTION_PATTERN.sub("", content)
    # A removed row leaves its section's blank lines behind.
    content = re.sub(r"\n{3,}", "\n\n", content)

    return content.rstrip("\n") + "\n"
