from pathlib import Path
import re

from config import ARCHIVE_DIR, BACKLOG_FILE, CURRENT_STORY_FILE, REPO_ROOT, STORIES_DIR
from files import find_section_span, read_file


# ============================================================
# Canonical agent/stories/BACKLOG.md section semantics
#
# These five headings have exactly one meaning each. Nothing else --
# not a planner run, not a story file's own Status -- may redefine
# them. There is no second interpretation of "selectable."
#
#   ## Active    the ONE story agent/CURRENT_STORY.md currently points
#                to (zero entries when no story is active), and ONLY
#                that story -- never a place to list queued work.
#   ## To Do     every READY/selectable story. get_ready_story_filenames()
#                and get_selectable_story_candidates() read this
#                section, and only this section, for selection.
#   ## Blocked   stories that cannot currently execute because of an
#                unresolved dependency/blocker (per the story's own
#                Status/Dependencies -- see is_story_blocked_by_own_file).
#   ## Done      completed stories belonging to the current,
#                not-yet-archived milestone.
#   ## Archived  a compact historical index only, for stories already
#                moved to agent/stories/archive/ -- never selectable,
#                never re-read for selection purposes.
#
# A newly created READY story always goes under "## To Do", never
# "## Active" -- Active is populated only by the deterministic
# To-Do -> Active move set_active_story() performs below, driven by
# the orchestrator's own selection, never by planner prose.
# ============================================================

# ============================================================
# Active story
# ============================================================

def resolve_story_path(
    relative_path: str
) -> Path:

    cleaned = relative_path.strip()

    if not cleaned:
        raise RuntimeError(
            "Story path must not be empty."
        )

    # BACKLOG.md lists bare filenames (e.g.
    # "STORY-DOM-001-....md"), not full repo-relative paths. A bare
    # filename -- no path separator at all -- is therefore resolved
    # relative to agent/stories/ directly. Anything containing a
    # separator (including the canonical "agent/stories/<filename>"
    # form written to CURRENT_STORY.md, and any absolute or
    # traversal path) is resolved relative to the repo root as
    # before, so the existing containment check below still rejects
    # it if it doesn't land inside agent/stories/.
    has_separator = (
        "/" in cleaned or "\\" in cleaned
    )

    if has_separator:
        base = REPO_ROOT
    else:
        base = STORIES_DIR

    story_path = (
        base / cleaned
    ).resolve()

    stories_root = STORIES_DIR.resolve()

    if stories_root not in story_path.parents:
        raise RuntimeError(
            "Story path must point inside agent/stories/."
        )

    archive_root = ARCHIVE_DIR.resolve()

    if archive_root == story_path or archive_root in story_path.parents:
        raise RuntimeError(
            "Story path must not point inside agent/stories/archive/ "
            "-- an archived story can never become (or remain) active."
        )

    if not story_path.exists():
        raise FileNotFoundError(
            f"Story does not exist: {story_path}"
        )

    if story_path.name.lower() == "backlog.md":
        raise RuntimeError(
            "BACKLOG.md cannot be used as an active story."
        )

    if story_path.suffix.lower() != ".md":
        raise RuntimeError(
            "Active story must be a Markdown file."
        )

    return story_path


def get_active_story_path() -> Path:
    pointer = read_file(
        CURRENT_STORY_FILE
    ).strip()

    lines = [
        line.strip()
        for line in pointer.splitlines()
        if line.strip()
    ]

    if len(lines) != 1:
        raise RuntimeError(
            "agent/CURRENT_STORY.md must contain "
            "exactly one repository-relative path."
        )

    return resolve_story_path(
        lines[0]
    )


def set_active_story(
    story_relative_path: str
) -> Path:

    story_path = resolve_story_path(
        story_relative_path
    )

    normalized = story_path.relative_to(
        REPO_ROOT
    ).as_posix()

    CURRENT_STORY_FILE.write_text(
        normalized + "\n",
        encoding="utf-8"
    )

    # Keep BACKLOG.md's Active/To Do sections in sync deterministically
    # -- this is exactly the bookkeeping a planning run must never do by
    # prose (see the canonical-sections note above), so it happens here
    # in code instead, every time a story is activated.
    backlog_content = read_file(
        BACKLOG_FILE
    )

    updated_backlog = move_backlog_entry_to_active(
        backlog_content,
        story_path.name
    )

    if updated_backlog != backlog_content:
        BACKLOG_FILE.write_text(
            updated_backlog,
            encoding="utf-8"
        )

    return story_path


def move_backlog_entry_to_active(
    backlog_content: str,
    filename: str
) -> str:
    """
    Deterministically move `filename`'s bullet out of "## To Do" (the
    only section a selectable candidate can come from) and into
    "## Active", replacing whatever was already there -- Active holds
    at most one story. If BACKLOG.md has no matching bullet under To
    Do (already out of sync), a bare bullet is used rather than
    silently leaving the newly active story undocumented.
    """

    content = backlog_content
    bullet = None

    todo_span = find_section_span(
        content,
        "To Do"
    )

    if todo_span is not None:
        start, end = todo_span
        todo_body = content[start:end]

        remaining_lines = []

        for line in todo_body.splitlines(keepends=True):
            if bullet is None and f"`{filename}`" in line:
                bullet = line.rstrip("\n")
            else:
                remaining_lines.append(line)

        content = (
            content[:start]
            + "".join(remaining_lines)
            + content[end:]
        )

    if bullet is None:
        bullet = f"- `{filename}`"

    active_span = find_section_span(
        content,
        "Active"
    )

    if active_span is None:
        raise RuntimeError(
            "agent/stories/BACKLOG.md has no '## Active' section."
        )

    start, end = active_span

    # find_section_span's heading pattern can absorb the blank line
    # after "## Active" into the "before" portion (its `\s*` is
    # greedy) -- strip any trailing newlines first so exactly one
    # blank line separates the heading from the bullet, regardless of
    # how much of that blank line ended up on which side of the split.
    before = content[:start].rstrip("\n")

    content = (
        before
        + "\n\n" + bullet + "\n"
        + content[end:]
    )

    return content


# ============================================================
# Story status (read from the story file itself, not Hermes)
# ============================================================
#
# Whether a story is DONE or BLOCKED must be checked deterministically
# in Python, before Claude Code (or Hermes) is ever invoked. Sending an
# already-DONE story to Claude wastes a run; sending a BLOCKED story
# risks Claude improvising around the blocker instead of stopping.

STATUS_SECTION_PATTERN = re.compile(
    r"##\s*Status\s*\n+(.*?)(?=\n##\s|\Z)",
    re.DOTALL | re.IGNORECASE,
)

DONE_PATTERN = re.compile(r"^done\b", re.IGNORECASE)
BLOCKED_PATTERN = re.compile(r"\bblocked\b", re.IGNORECASE)


def extract_status_section(story_content: str) -> str:
    match = STATUS_SECTION_PATTERN.search(
        story_content
    )

    if not match:
        raise RuntimeError(
            "Story file has no '## Status' section."
        )

    return match.group(1).strip()


DEPENDENCIES_SECTION_PATTERN = re.compile(
    r"##\s*Dependencies\s*\n+(.*?)(?=\n##\s|\Z)",
    re.DOTALL | re.IGNORECASE,
)


def extract_dependencies_section(story_content: str) -> str:
    match = DEPENDENCIES_SECTION_PATTERN.search(
        story_content
    )

    # Absent on older story files written before "Dependencies" became
    # a required section -- treat as empty (no self-declared blocker),
    # not as an error, since their blocking info (if any) already
    # lives in the Status section handled by classify_story_status.
    return match.group(1).strip() if match else ""


def classify_story_status(status_text: str) -> str:
    first_line = (
        status_text.splitlines()[0].strip()
        if status_text
        else ""
    )

    if DONE_PATTERN.match(first_line):
        return "DONE"

    # Matches both a literal "BLOCKED" status and a "TODO" status that
    # notes a blocking dependency in the same line (e.g. "TODO --
    # blocked until STORY-DOM-001 reaches DONE"), which is exactly the
    # case that must not be dispatched to Claude either.
    if BLOCKED_PATTERN.search(status_text):
        return "BLOCKED"

    return "OTHER"


# ============================================================
# Backlog parsing
# ============================================================

# Canonical story filenames only: STORY-<AREA>-<NUMBER>-<slug>.md. A
# bullet's description text commonly contains other backtick-quoted
# *.md references (e.g. `docs/KNOWN_PROBLEMS.md`) -- those must never
# be mistaken for a story file.
STORY_FILENAME_PATTERN = re.compile(
    r"^STORY-[A-Za-z0-9]+-\d+-.+\.md$",
    re.IGNORECASE,
)


def is_story_filename(name: str) -> bool:
    return bool(
        STORY_FILENAME_PATTERN.match(
            name.strip()
        )
    )


def parse_backlog_section(
    backlog_content: str,
    heading: str
) -> list[str]:
    """
    Return the canonical story filenames (STORY-*.md) listed as
    backtick-quoted Markdown list items under a given '## <heading>'
    section of BACKLOG.md. Other backtick-quoted Markdown references
    that merely appear inline in a bullet's description (e.g. a
    `docs/KNOWN_PROBLEMS.md` citation) are filtered out -- they are
    not story filenames.
    """

    pattern = re.compile(
        rf"##\s*{re.escape(heading)}\s*\n(.*?)(?=\n##\s|\Z)",
        re.DOTALL | re.IGNORECASE,
    )

    match = pattern.search(
        backlog_content
    )

    if not match:
        return []

    section = match.group(1)

    all_md_references = re.findall(
        r"`([^`]+\.md)`",
        section
    )

    return [
        name
        for name in all_md_references
        if is_story_filename(name)
    ]


def get_ready_story_filenames() -> set[str]:
    backlog = read_file(
        BACKLOG_FILE
    )

    return set(
        parse_backlog_section(
            backlog,
            "To Do"
        )
    )


def get_ready_story_filenames_ordered() -> list[str]:
    """
    Same membership as get_ready_story_filenames(), but preserving the
    top-to-bottom order stories are listed in BACKLOG.md's '## To Do'
    section -- that order is the execution priority the planner
    established. A caller deciding "the next" story must walk this
    list in order and never re-sort it (alphabetically, by story ID, or
    otherwise); see select_next_story() in selector.py, which is the
    sole intended consumer.
    """

    backlog = read_file(
        BACKLOG_FILE
    )

    return parse_backlog_section(
        backlog,
        "To Do"
    )


# ============================================================
# Selectable candidate set
#
# BACKLOG "To Do" membership is necessary but not sufficient: a story
# is only genuinely selectable if its OWN canonical file does not
# declare itself blocked. General PROJECT_STATE.md / ROADMAP blockers,
# or another story's blocker, must never be projected onto a story
# whose own file doesn't block it -- that was the STORY-DOM-007
# overreach bug this guards against. Only the Status and Dependencies
# sections are checked (both structured, expected-to-be-current
# fields); the free-form Blockers section is intentionally not
# keyword-scanned here, since it often narrates already-resolved
# history (e.g. "was blocked on X; now satisfied") using the word
# "blocked" without the story being currently blocked.
# ============================================================

def is_story_blocked_by_own_file(story_content: str) -> bool:
    status_text = extract_status_section(
        story_content
    )

    if classify_story_status(status_text) == "BLOCKED":
        return True

    dependencies_text = extract_dependencies_section(
        story_content
    )

    return bool(
        BLOCKED_PATTERN.search(dependencies_text)
    )


def get_selectable_story_candidates() -> list[Path]:
    """
    The deterministic candidate set, in BACKLOG.md '## To Do' order --
    never alphabetical, never by story ID. The first entry is the one
    to run next; select_next_story() in selector.py relies on that
    ordering guarantee.
    """

    ready_filenames_ordered = get_ready_story_filenames_ordered()

    candidates = []

    for filename in ready_filenames_ordered:
        story_path = STORIES_DIR / filename

        # Defensive: a filename from BACKLOG.md must resolve to a
        # direct child of agent/stories/, never into agent/stories/archive/
        # (or anywhere else) via a crafted/legacy backlog entry.
        if story_path.parent != STORIES_DIR:
            continue

        if not story_path.exists():
            # Covers both a nonexistent entry and one that has since
            # moved to agent/stories/archive/<milestone> -- either way
            # there is no canonical file left to execute here.
            continue

        content = read_file(
            story_path
        )

        status_text = extract_status_section(
            content
        )

        classification = classify_story_status(
            status_text
        )

        # A DONE story left under '## To Do' (backlog out of sync) is
        # never executable again -- this is a distinct check from
        # is_story_blocked_by_own_file(), which only looks for BLOCKED.
        if classification == "DONE":
            continue

        if is_story_blocked_by_own_file(content):
            continue

        candidates.append(
            story_path
        )

    return candidates


# ============================================================
# BACKLOG.md structural consistency validation
#
# Deterministic, structure-only checks -- independent of any one
# planning/orchestrator run -- that the five canonical sections still
# mean what they're supposed to mean. Returns a list of human-readable
# problem strings; an empty list means the backlog is consistent.
# ============================================================

def validate_backlog_consistency() -> list[str]:
    backlog = read_file(
        BACKLOG_FILE
    )

    sections = {
        heading: parse_backlog_section(backlog, heading)
        for heading in ("Active", "To Do", "Blocked", "Done", "Archived")
    }

    problems = []

    if len(sections["Active"]) > 1:
        problems.append(
            "'## Active' lists more than one story: "
            f"{sections['Active']}"
        )

    seen_in = {}

    for heading, filenames in sections.items():
        for filename in filenames:
            if filename in seen_in and seen_in[filename] != heading:
                problems.append(
                    f"{filename} appears in both '## {seen_in[filename]}' "
                    f"and '## {heading}'."
                )
            else:
                seen_in[filename] = heading

    for filename in sections["To Do"]:
        story_path = STORIES_DIR / filename

        if story_path.parent != STORIES_DIR or not story_path.exists():
            problems.append(
                "'## To Do' entry does not resolve to a canonical "
                f"non-archived story file: {filename}"
            )

    try:
        current_path = get_active_story_path()
    except (FileNotFoundError, RuntimeError):
        current_path = None

    active_names = sections["Active"]

    if current_path is None:
        if active_names:
            problems.append(
                "'## Active' lists "
                f"{active_names}, but agent/CURRENT_STORY.md does not "
                "resolve to a valid, non-archived story."
            )
    else:
        current_name = current_path.name

        if active_names and active_names[0] != current_name:
            problems.append(
                f"'## Active' lists {active_names}, which does not "
                f"match agent/CURRENT_STORY.md ({current_name})."
            )

        content = read_file(
            current_path
        )

        status_text = extract_status_section(
            content
        )

        classification = classify_story_status(
            status_text
        )

        if classification == "DONE" and current_name in active_names:
            problems.append(
                f"{current_name} is DONE but is still listed under "
                "'## Active'."
            )

        if (
            classification not in ("DONE", "BLOCKED")
            and current_name not in active_names
        ):
            problems.append(
                f"agent/CURRENT_STORY.md points to {current_name} "
                "(still executable), but it is not listed under "
                "'## Active'."
            )

    return problems
