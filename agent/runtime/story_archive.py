from pathlib import Path
import re
import shutil

from config import ARCHIVE_DIR, BACKLOG_FILE, CURRENT_STORY_FILE, STORIES_DIR
from files import find_section_span, read_file
from story_state import classify_story_status, extract_status_section


# ============================================================
# Milestone-based story archiving
#
# Archiving moves DONE stories out of agent/stories/ into
# agent/stories/archive/<milestone-slug>/ once a roadmap milestone
# transition has been confirmed (never on an individual story
# reaching DONE). This module only ever MOVES files -- it never
# deletes history -- and only ever archives a story whose OWN
# canonical file states both Status: DONE and a matching "## Milestone"
# value. Milestone membership is never inferred from a filename or
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

MILESTONE_SECTION_PATTERN = re.compile(
    r"##\s*Milestone\s*\n+(.*?)(?=\n##\s|\Z)",
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

def get_archivable_stories(
    milestone_slug: str
) -> dict:
    """
    Deterministically re-derive, from each story file's own Status and
    Milestone sections, which stories directly under agent/stories/
    (never agent/stories/archive/) are eligible for the given
    milestone. Never trusts a planning run's own claim about which
    stories belong to a milestone.
    """

    if not is_valid_milestone_slug(milestone_slug):
        raise ValueError(
            f"Invalid milestone slug: {milestone_slug!r}"
        )

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

        if classify_story_status(status_text) != "DONE":
            skipped_not_done.append(path.name)
            continue

        story_milestone = extract_milestone(
            content
        )

        if story_milestone is None:
            skipped_no_milestone.append(path.name)
            continue

        if story_milestone.strip() != milestone_slug:
            skipped_other_milestone.append(path.name)
            continue

        eligible.append(path)

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
    Move every DONE story explicitly tagged with the given milestone
    from agent/stories/ into agent/stories/archive/<milestone_slug>/,
    then update BACKLOG.md's "## Archived" section to match. Never
    overwrites an existing archived file (a name collision is recorded
    as a conflict and that file is left where it is); never archives
    the currently active story; never deletes anything.
    """

    breakdown = get_archivable_stories(
        milestone_slug
    )

    target_dir = resolve_archive_dir(
        milestone_slug
    )

    archived = []
    conflicts = []
    errors = []

    if breakdown["eligible"]:
        target_dir.mkdir(
            parents=True,
            exist_ok=True
        )

    for path in breakdown["eligible"]:
        destination = target_dir / path.name

        if destination.exists():
            conflicts.append(
                path.name
            )
            continue

        try:
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
            milestone_slug,
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
# BACKLOG.md "## Archived" bookkeeping
#
# Deliberately narrow, purpose-built text surgery (not a generic
# Markdown editor): move each archived filename's existing bullet line
# out of "## Done" and into "## Archived" / "### Milestone N", keeping
# whatever description text that bullet already had (still an index
# entry, never full story content).
# ============================================================

def _milestone_number_from_slug(
    milestone_slug: str
) -> str:
    match = re.search(
        r"(\d+)\s*$",
        milestone_slug
    )

    return match.group(1) if match else milestone_slug


def update_backlog_for_archive(
    backlog_content: str,
    milestone_slug: str,
    archived_filenames: list
) -> str:
    if not archived_filenames:
        return backlog_content

    content = backlog_content

    moved_lines = []

    done_span = find_section_span(
        content,
        "Done"
    )

    if done_span is not None:
        start, end = done_span
        done_body = content[start:end]

        remaining_lines = []

        for line in done_body.splitlines(keepends=True):
            stripped = line.strip()

            matched_filename = next(
                (
                    filename
                    for filename in archived_filenames
                    if f"`{filename}`" in stripped
                ),
                None
            )

            if matched_filename is not None:
                moved_lines.append(
                    line.rstrip("\n")
                )
            else:
                remaining_lines.append(
                    line
                )

        content = (
            content[:start]
            + "".join(remaining_lines)
            + content[end:]
        )

    # Any archived filename that had no matching bullet in "## Done"
    # (e.g. BACKLOG.md was already out of sync) still gets a bare
    # index entry, so the archive doesn't silently go undocumented.
    already_moved_filenames = {
        re.search(r"`([^`]+\.md)`", line).group(1)
        for line in moved_lines
        if re.search(r"`([^`]+\.md)`", line)
    }

    for filename in archived_filenames:
        if filename not in already_moved_filenames:
            moved_lines.append(
                f"- `{filename}`"
            )

    milestone_number = _milestone_number_from_slug(
        milestone_slug
    )
    milestone_heading = f"Milestone {milestone_number}"

    archived_span = find_section_span(
        content,
        "Archived"
    )

    if archived_span is None:
        content = content.rstrip("\n") + "\n\n## Archived\n\n"
        archived_span = find_section_span(
            content,
            "Archived"
        )

    start, end = archived_span
    archived_body = content[start:end]

    # Matches the "_(none)_" placeholder style already used by the
    # other BACKLOG.md sections (Active, Blocked, ...) -- drop it once
    # there is real content to put in its place.
    if archived_body.strip().lower() in ("_(none)_", "(none)", ""):
        archived_body = ""

    subsection_pattern = re.compile(
        rf"(###\s*{re.escape(milestone_heading)}\b[^\n]*\n)(.*?)"
        rf"(?=\n###\s|\Z)",
        re.DOTALL | re.IGNORECASE,
    )

    sub_match = subsection_pattern.search(
        archived_body
    )

    lines_block = "\n".join(moved_lines) + "\n"

    if sub_match:
        new_sub_body = (
            sub_match.group(2).rstrip("\n")
            + "\n" + lines_block
        )

        new_archived_body = (
            archived_body[:sub_match.start(2)]
            + new_sub_body
            + archived_body[sub_match.end(2):]
        )
    else:
        addition = f"\n### {milestone_heading}\n\n{lines_block}"
        new_archived_body = (
            archived_body.rstrip("\n") + "\n" + addition
        )

    content = (
        content[:start]
        + new_archived_body
        + content[end:]
    )

    return content
