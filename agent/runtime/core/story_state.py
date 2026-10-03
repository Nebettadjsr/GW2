from pathlib import Path
import re

from agent.runtime.support.config import ARCHIVE_DIR, BACKLOG_FILE, CURRENT_STORY_FILE, REPO_ROOT, STORIES_DIR
from agent.runtime.support.files import find_section_span, read_file
from agent.runtime.human.user_decisions import list_decisions
from agent.runtime.human.architect_requests import list_requests as list_architect_requests


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


def clear_active_story() -> None:
    """Leave no active story. Idempotent.

    The counterpart to set_active_story(), and the only supported way to
    say "nothing is active": an empty CURRENT_STORY.md is what
    _active_is_executable() reads as "no active story", which is what lets
    deterministic selection run. Deleting the file would work too, but an
    empty tracked file keeps the pointer's absence visible in review.
    """

    CURRENT_STORY_FILE.parent.mkdir(parents=True, exist_ok=True)
    CURRENT_STORY_FILE.write_text("", encoding="utf-8")


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

    # Routed through the shared puller rather than repeating its loop here.
    # This function used to carry its own copy, which moved the bullet alone and
    # left any continuation lines under it behind in '## To Do' -- the orphaned
    # "backlog entry: ..." line a real run found and tried to clean up by hand.
    content, bullet = _pull_bullet_from_backlog_section(
        backlog_content,
        "To Do",
        filename,
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
# Story status (read from the story file itself, not from a model)
# ============================================================
#
# Whether a story is DONE or BLOCKED must be checked deterministically
# in Python, before Claude Code (or the evaluator) is ever invoked. Sending an
# already-DONE story to Claude wastes a run; sending a BLOCKED story
# risks Claude improvising around the blocker instead of stopping.

# [ \t]*\n, not \s*\n+ -- see find_section_span()'s fix/rationale in
# files.py; the same greedy-whitespace-starves-the-lookahead bug
# applies to every '## <heading> ... (?=\n##|\Z)' pattern in this
# file, not only the one in files.py.
STATUS_SECTION_PATTERN = re.compile(
    r"##\s*Status[ \t]*\n(.*?)(?=\n##\s|\Z)",
    re.DOTALL | re.IGNORECASE,
)

DONE_PATTERN = re.compile(r"^done\b", re.IGNORECASE)
BLOCKED_PATTERN = re.compile(r"\bblocked\b", re.IGNORECASE)
TODO_PATTERN = re.compile(r"^todo\b", re.IGNORECASE)
UNFINISHED_PATTERN = re.compile(r"^unfinished\b", re.IGNORECASE)
# A story retired by a product/architecture decision. Classified
# explicitly rather than falling through to "OTHER", which every caller
# reads as "still executable" -- the only thing keeping a SUPERSEDED
# story out of the queue used to be which BACKLOG section it happened to
# sit in.
SUPERSEDED_PATTERN = re.compile(r"^superseded\b", re.IGNORECASE)


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
    r"##\s*Dependencies[ \t]*\n(.*?)(?=\n##\s|\Z)",
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
    # case that must not be dispatched to Claude either. Checked
    # before the plain TODO_PATTERN match below so that combined line
    # is still classified BLOCKED, not TODO.
    if BLOCKED_PATTERN.search(status_text):
        return "BLOCKED"

    if TODO_PATTERN.match(first_line):
        return "TODO"

    if UNFINISHED_PATTERN.match(first_line):
        return "UNFINISHED"

    if SUPERSEDED_PATTERN.match(first_line):
        return "SUPERSEDED"

    # Genuinely unrecognized/malformed Status text (blank, or
    # something other than the four canonical values) -- every
    # existing caller only ever branches on "DONE"/"BLOCKED"
    # specifically and treats everything else as "still executable",
    # so introducing this explicit "TODO" case above changes no
    # existing behavior; "OTHER" remains the fail-safe fallback.
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


# The two bullet shapes a BACKLOG section may hold. Module level, not
# local to parse_backlog_section(), because every bullet *mover* below
# needs the same understanding of what a bullet refers to -- see
# bullet_names_story().
LEGACY_BACKLOG_ENTRY = re.compile(r"^\s*[-*]\s*`([^`]+\.md)`")
COMPACT_BACKLOG_ENTRY = re.compile(
    r"^\s*[-*]\s*(STORY-[A-Za-z0-9]+-\d+)\s*\|\s*"
    r"([^|]+?\.md)\s*\|\s*([^|]+)\s*\|"
)


# ============================================================
# The canonical BACKLOG.md entry -- one writer, one parser
#
# BACKLOG.md is an index: the only information it carries is what story
# selection and navigation need. Everything else -- a story's goal,
# requirements, evidence, findings, or why it was superseded -- belongs
# in that story's own canonical file; roadmap sequencing belongs in
# docs/ROADMAP.md; run diagnostics belong in agent/logs/ and
# agent/runtime/artifacts/.
#
# One entry is exactly one line:
#
#   - STORY-ID | filename.md | STATUS | milestone-NN | deps: A, B
#
# format_backlog_entry() is the ONLY place that spelling is produced and
# parse_backlog_entry() the only place it is taken apart. Before they
# existed, three different writers each had their own idea of the
# format: planning_context's rendered index emitted supplementary
# "backlog entry:"/"dependency note:" continuation lines, the planner
# copied that rendering back into BACKLOG.md as if it were canonical,
# and the movers below -- which only ever understood a single line --
# left those continuation lines behind as orphans whenever an entry
# moved sections.
# ============================================================

BACKLOG_ENTRY_STATUSES = (
    "ACTIVE",
    "TODO",
    "UNFINISHED",
    "BLOCKED",
    "DONE",
    "SUPERSEDED",
)

# Sections whose rows drive story selection. Every content line in one
# of these must be a canonical entry -- there is nothing else a
# selectable queue can usefully say.
STRICT_ENTRY_SECTIONS = ("Active", "To Do", "Blocked", "Superseded")

# Supplementary prose the index must never carry, in any section. These
# are exactly the three prefixes that accumulated in practice.
BANNED_ENTRY_PROSE = re.compile(
    r"^\s*(?:[-*]\s*)?(backlog entry|dependency note|disposition)\s*:",
    re.IGNORECASE,
)

SECTION_PLACEHOLDER = re.compile(r"^\s*_?\(\s*none\b.*$", re.IGNORECASE)

CANONICAL_BACKLOG_ENTRY = re.compile(
    r"^- (STORY-[A-Za-z0-9]+-\d+) \| (\S+\.md) \| ([A-Z]+) \| "
    r"(milestone-\d+|unassigned) \| deps: (.*)$"
)

# A completed entry's fifth field is the story's title rather than its
# dependencies: '## Done' is a navigational record, and what a reader
# needs there is what the story was, not what it once waited for. Same
# four leading fields, so both forms stay machine-readable.
COMPLETED_BACKLOG_ENTRY = re.compile(
    r"^- (STORY-[A-Za-z0-9]+-\d+) \| (\S+\.md) \| ([A-Z]+) \| "
    r"(milestone-\d+|unassigned) \| (.+)$"
)


def _dependency_names(dependencies) -> list[str]:
    if isinstance(dependencies, str):
        text = dependencies.strip()

        if text.lower() in ("", "none"):
            return []

        return [part.strip() for part in text.split(",") if part.strip()]

    return [
        str(name).strip()
        for name in (dependencies or [])
        if str(name).strip()
    ]


def format_backlog_entry(
        story_id: str,
        filename: str,
        status: str,
        milestone: str,
        dependencies,
) -> str:
    """The one canonical spelling of a BACKLOG.md entry line."""

    normalized_status = (status or "TODO").strip().upper()

    if normalized_status not in BACKLOG_ENTRY_STATUSES:
        raise ValueError(
            f"Unsupported BACKLOG entry status: {status!r}. "
            f"Supported: {', '.join(BACKLOG_ENTRY_STATUSES)}."
        )

    return "- " + " | ".join([
        story_id.strip(),
        filename.strip(),
        normalized_status,
        (milestone or "").strip() or "unassigned",
        "deps: " + (", ".join(_dependency_names(dependencies)) or "None"),
    ])


def format_completed_backlog_entry(
        story_id: str,
        filename: str,
        milestone: str,
        title: str,
) -> str:
    """The one canonical spelling of a '## Done' entry line."""

    return "- " + " | ".join([
        story_id.strip(),
        filename.strip(),
        "DONE",
        (milestone or "").strip() or "unassigned",
        " ".join((title or "").split()) or "(title not recorded in the story file)",
    ])


def parse_completed_backlog_entry(line: str) -> dict | None:
    """One '## Done' entry line as fields, or None when it is not one."""

    match = COMPLETED_BACKLOG_ENTRY.match(line.rstrip())

    if not match:
        return None

    return {
        "story_id": match.group(1),
        "filename": match.group(2),
        "status": match.group(3),
        "milestone": match.group(4),
        "title": match.group(5).strip(),
    }


def parse_backlog_entry(line: str) -> dict | None:
    """One canonical entry line as fields, or None when it is not one."""

    match = CANONICAL_BACKLOG_ENTRY.match(line.rstrip())

    if not match:
        return None

    raw_dependencies = match.group(5).strip()

    return {
        "story_id": match.group(1),
        "filename": match.group(2),
        "status": match.group(3),
        "milestone": match.group(4),
        "dependencies": _dependency_names(raw_dependencies),
    }


def rewrite_backlog_entry_status(line: str, status: str) -> str:
    """Re-emit one entry with a different status.

    A line that does not parse canonically is returned unchanged rather
    than guessed at -- validate_backlog_entries() is what reports it.
    """

    fields = parse_backlog_entry(line)

    if fields is None:
        return line

    return format_backlog_entry(
        fields["story_id"],
        fields["filename"],
        status,
        fields["milestone"],
        fields["dependencies"],
    )


def _is_entry_continuation(line: str) -> bool:
    """A line that belongs to the entry above it rather than standing alone.

    Indented text, or any of the banned supplementary-prose prefixes.
    Such a line is never carried along when its entry moves: it is prose
    the index may not hold at all, so the movers drop it.
    """

    if not line.strip():
        return False

    if BANNED_ENTRY_PROSE.match(line):
        return True

    return (
        line[:1] in (" ", "\t")
        and parse_backlog_entry(line.strip()) is None
    )


def strip_backlog_prose(backlog_content: str) -> str:
    """Drop every supplementary-prose line from the index.

    Idempotent, and safe on an already-clean file. This is the cleanup
    half of the orphan problem: a prose line whose entry already moved
    elsewhere has nothing left to attach to, so no mover can ever find
    it by story name again.
    """

    return "".join(
        line
        for line in backlog_content.splitlines(keepends=True)
        if not BANNED_ENTRY_PROSE.match(line)
    )


def _section_entry_problems(
        backlog_content: str,
        heading: str,
        strict: bool,
) -> list[str]:
    span = find_section_span(
        backlog_content,
        heading
    )

    if span is None:
        return []

    start, end = span
    offset = backlog_content[:start].count("\n") + 1
    problems = []

    for index, line in enumerate(backlog_content[start:end].splitlines()):
        if BANNED_ENTRY_PROSE.match(line):
            # Already reported once, for the whole file.
            continue

        if not strict and not line.lstrip().startswith(("- ", "* ")):
            # A non-strict section (Done) keeps a short navigational
            # preamble; only lines presenting themselves as entries are
            # held to the entry format.
            continue

        if strict and (not line.strip() or SECTION_PLACEHOLDER.match(line)):
            continue

        if parse_backlog_entry(line) is not None:
            continue

        # Validation accepts whatever selection can actually parse, and no
        # less: COMPACT_BACKLOG_ENTRY is what parse_backlog_section() reads, so
        # a row in that older four-field shape is a working entry even though
        # format_backlog_entry() would not write it that way. Being stricter
        # here than the parser would flag rows that select perfectly well.
        # What must still be rejected is anything selection cannot see at all
        # -- prose lines, and continuation lines that orphan on a move.
        if COMPACT_BACKLOG_ENTRY.match(line) or LEGACY_BACKLOG_ENTRY.match(line):
            continue

        if not strict and parse_completed_backlog_entry(line) is not None:
            continue

        problems.append(
            f"line {offset + index}: '## {heading}' "
            + (
                "may contain only canonical entries "
                "('- STORY-ID | filename.md | STATUS | milestone-NN | "
                "deps: ...')"
                if strict
                else "entry is not in a recognized format"
            )
            + f", found: {line.strip()[:80]!r}"
        )

    return problems


def validate_backlog_entries(backlog_content: str) -> list[str]:
    """Every way BACKLOG.md can stop being a machine-readable index.

    Reported, never repaired here: the caller that wrote the file
    decides whether to reject the write (the planning guard does) or to
    surface the problem (the orchestrator's periodic report does).
    """

    problems = [
        f"line {number}: BACKLOG.md carries supplementary prose, which "
        "belongs in the story file, docs/ROADMAP.md or the run log, not "
        f"the index: {line.strip()[:80]!r}"
        for number, line in enumerate(backlog_content.splitlines(), start=1)
        if BANNED_ENTRY_PROSE.match(line)
    ]

    for heading in STRICT_ENTRY_SECTIONS:
        problems.extend(
            _section_entry_problems(backlog_content, heading, strict=True)
        )

    problems.extend(
        _section_entry_problems(backlog_content, "Done", strict=False)
    )

    return problems


def bullet_names_story(line: str, filename: str) -> bool:
    """
    Whether one BACKLOG bullet refers to `filename`, in either supported
    format.

    parse_backlog_section() has understood both the legacy backtick form
    and the compact `STORY-ID | filename | STATUS | summary` row for some
    time, but every function that *moves* a bullet matched only the
    backtick form. A compact row therefore could not be pulled out of a
    section: activating such a story left its row under '## To Do' and
    synthesized a second, bare bullet under '## Active', so one story was
    listed in two sections at once (validate_backlog_consistency()
    reports exactly that) and blocking one left a stale To Do row behind.
    """

    if f"`{filename}`" in line:
        return True

    match = COMPACT_BACKLOG_ENTRY.match(line)

    return bool(match and match.group(2).strip() == filename)


def parse_backlog_section(
    backlog_content: str,
    heading: str
) -> list[str]:
    """
    Return canonical story filenames from legacy backtick entries or
    compact `STORY-ID | filename | STATUS | summary` rows in a section.
    """

    # [ \t]*, not \s*, before the mandatory newline -- see the
    # identical fix and its rationale in files.find_section_span().
    # This is an independent regex (not a call into that helper), so
    # it needed the same correction separately: a purely blank section
    # (no "_(none)_" placeholder) previously bled into the next
    # section's heading and body.
    pattern = re.compile(
        rf"##\s*{re.escape(heading)}[ \t]*\n(.*?)(?=\n##\s|\Z)",
        re.DOTALL | re.IGNORECASE,
    )

    match = pattern.search(
        backlog_content
    )

    if not match:
        return []

    section = match.group(1)

    filenames = []

    for line in section.splitlines():
        legacy = LEGACY_BACKLOG_ENTRY.match(line)
        compact = None if legacy else COMPACT_BACKLOG_ENTRY.match(line)

        if not legacy and not compact:
            continue

        name = legacy.group(1) if legacy else compact.group(2).strip()

        if is_story_filename(name):
            filenames.append(name)

    return filenames


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
# Story ID / dependency resolution
#
# A story's own '## Dependencies' section names other Story IDs. This
# must never be a keyword scan for the literal word "blocked" -- that
# only catches a dependency an author happened to phrase with that
# word (e.g. "Blocked until STORY-X reaches DONE") and silently passes
# a plain reference like "STORY-DOM-013; STORY-DOM-014" straight
# through as satisfied, which is exactly how STORY-DOM-015 was once
# selected while both of its listed dependencies were still TODO.
# Instead, every STORY-<AREA>-<NUMBER> token named in that section is
# resolved against the actual current Status of the story it refers
# to (active or archived) -- deterministically, in Python, never left
# to an LLM to judge.
# ============================================================

STORY_ID_PATTERN = re.compile(
    r"##\s*Story ID\s*\n+\s*([A-Za-z0-9_-]+)",
    re.IGNORECASE,
)

DEPENDENCY_ID_PATTERN = re.compile(
    r"\bSTORY-[A-Za-z0-9]+-\d+\b",
    re.IGNORECASE,
)

# Matches a Dependencies section that *starts with* "None"/"None.",
# regardless of what explanatory prose follows on the same or later
# lines -- e.g. "None. Not dependent on any other story."
NONE_DEPENDENCY_PATTERN = re.compile(
    r"^none\b\.?",
    re.IGNORECASE,
)


def extract_story_id(
        story_content: str
) -> str | None:
    match = STORY_ID_PATTERN.search(
        story_content
    )

    return match.group(1).strip() if match else None


def parse_dependency_ids(
        dependencies_text: str
) -> list[str]:
    return sorted(
        {
            match.group(0).upper()
            for match in DEPENDENCY_ID_PATTERN.finditer(
                dependencies_text
            )
        }
    )


def build_story_index() -> dict[str, dict]:
    """
    Scan every known story file -- active (agent/stories/*.md) and
    archived (agent/stories/archive/**/*.md) -- once, indexed by each
    file's own '## Story ID' value, recording its path and its current
    classify_story_status() result. Rebuilt fresh on every call
    (deliberately not cached): story files change between selection
    attempts, and a stale index could wrongly keep a story blocked
    after its dependency actually completes, or vice versa.
    """

    # Local import: story_archive imports from this module, so a
    # module-level import here would be circular.
    from agent.runtime.core.story_archive import list_archived_story_files

    active = [
        path
        for path in STORIES_DIR.glob("*.md")
        if path.name.lower() != "backlog.md"
    ]

    index = {}

    for path in active + list_archived_story_files():
        try:
            content = read_file(path)
        except FileNotFoundError:
            continue

        story_id = extract_story_id(content)

        if not story_id:
            continue

        index[story_id] = {
            "path": path,
            "status": classify_story_status(
                extract_status_section(content)
            ),
        }

    return index


def find_story_file_by_id(
        story_id: str,
        story_index: dict[str, dict] | None = None,
) -> Path | None:
    if story_index is None:
        story_index = build_story_index()

    entry = story_index.get(story_id)

    return entry["path"] if entry else None


def get_unsatisfied_dependencies(
        story_content: str,
        story_index: dict[str, dict] | None = None,
) -> list[str]:
    """
    Deterministically re-derive which of a story's own
    '## Dependencies' entries are not yet satisfied. An empty result
    means every dependency is satisfied, including the explicit "None"
    case. Never delegates this judgment to an LLM.

    Fail-safe by construction: a referenced Story ID that does not
    resolve to any known story file, or Dependencies text that names
    no recognizable STORY/UD/AR reference at all despite not
    being empty/"None", is treated as UNSATISFIED rather than ignored.
    """

    dependencies_text = extract_dependencies_section(
        story_content
    )

    stripped = dependencies_text.strip()

    # "None" (optionally followed by "." and/or free-form explanatory
    # prose, e.g. "None. Not dependent on any other story.") always
    # means no dependencies, regardless of what follows -- even if
    # that prose happens to mention another Story ID in passing. Word
    # boundary (\bnone\b) so "Nonetheless ..." does not match.
    if not stripped or NONE_DEPENDENCY_PATTERN.match(stripped):
        return []

    dependency_ids = parse_dependency_ids(
        dependencies_text
    )

    decision_ids = set(re.findall(r"\b(?:UD|AR)-\d+\b", dependencies_text))
    if not dependency_ids and not decision_ids:
        return [
            "Dependencies section is non-empty but names no "
            f"recognizable STORY/UD/AR reference: {stripped!r}"
        ]

    if story_index is None:
        story_index = build_story_index()

    unsatisfied = []
    if decision_ids:
        records = []
        if any(item.startswith("UD-") for item in decision_ids):
            records.extend(list_decisions())
        if any(item.startswith("AR-") for item in decision_ids):
            records.extend(list_architect_requests())
        for decision_id in sorted(decision_ids):
            matches = [item for item in records if item["id"] == decision_id]
            if len(matches) != 1 or matches[0]["status"] != "RESOLVED":
                unsatisfied.append(f"depends on {decision_id}, which is not uniquely RESOLVED")

    for dependency_id in dependency_ids:
        entry = story_index.get(dependency_id)

        if entry is None:
            unsatisfied.append(
                f"depends on {dependency_id}, which does not match "
                "any known story file"
            )
        elif entry["status"] != "DONE":
            unsatisfied.append(
                f"depends on {dependency_id}, which is not DONE "
                f"(currently {entry['status']})"
            )

    return unsatisfied


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

    return bool(
        get_unsatisfied_dependencies(story_content)
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

        # Stale To Do entries must not reselect completed stories,
        # retired ones, or interrupted work (which resumes through
        # CURRENT_STORY instead).
        if classification in ("DONE", "UNFINISHED", "SUPERSEDED"):
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

def validate_backlog_consistency(publishing: str | None = None) -> list[str]:
    """Report every way the live queue contradicts itself.

    `publishing` names the one story whose completion is being published
    right now: committed, pushed, and waiting for that commit's CI
    verdict. The harness publishes before it finalizes
    (orchestrator.verify_with_github_ci, then finalize_completed_story
    once the gate answers), because a red gate has to hand the story back
    as active work -- so between the push and the verdict the committed
    tree deliberately holds a story whose own Status is DONE while its
    entry is still under '## Active' and CURRENT_STORY.md still names it.
    The two rules that forbid a finished story under '## Active' are
    suspended for that one story, and for nothing else.

    Every other caller passes nothing and gets the strict rules: the
    running harness must keep reporting a finalization that never
    happened, which is the failure these rules were added for.
    """

    backlog = read_file(
        BACKLOG_FILE
    )

    sections = {
        heading: parse_backlog_section(backlog, heading)
        for heading in ("Active", "To Do", "Blocked", "Done", "Archived")
    }

    problems = validate_backlog_entries(backlog)

    if len(sections["Active"]) > 1:
        problems.append(
            "'## Active' lists more than one story: "
            f"{sections['Active']}"
        )

    # A completed or retired story under '## Active' is wrong regardless
    # of where CURRENT_STORY.md points. The pointer-relative check further
    # down only fires while the pointer still names that story, so a
    # finalization that cleared the pointer but failed to move the entry
    # went unreported.
    for filename in sections["Active"]:
        story_path = STORIES_DIR / filename

        if not story_path.exists():
            continue

        state = classify_story_status(
            extract_status_section(read_file(story_path))
        )

        if state in ("DONE", "SUPERSEDED") and not (
            state == "DONE" and filename == publishing
        ):
            problems.append(
                f"'## Active' lists {filename}, whose own Status is {state}; "
                "a finished story must not remain active."
            )

    for filename in sections["To Do"]:
        story_path = STORIES_DIR / filename

        if not story_path.exists():
            continue

        state = classify_story_status(
            extract_status_section(read_file(story_path))
        )

        if state in ("DONE", "SUPERSEDED"):
            problems.append(
                f"'## To Do' lists {filename}, whose own Status is {state}; "
                "it can never be selected and must not sit in the queue."
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

        if (
            classification == "DONE"
            and current_name in active_names
            and current_name != publishing
        ):
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


# ============================================================
# Story-file Status/Blockers rewriting
#
# Deterministic, narrow text surgery on a story's own two structured
# fields -- never a rewrite of its narrative content (Result, Context,
# etc.). Used only to record/clear a block that Python itself decided
# (a dependency check, or a user-intervention outcome) -- never to
# express a judgment an LLM should be making.
# ============================================================

def _replace_section_body(
        content: str,
        heading: str,
        new_body: str,
) -> str:
    span = find_section_span(
        content,
        heading
    )

    if span is None:
        raise RuntimeError(
            f"Story file has no '## {heading}' section."
        )

    start, end = span

    return (
        content[:start]
        + "\n" + new_body.strip() + "\n"
        + content[end:]
    )


def set_story_unfinished(story_path: Path) -> None:
    """Record an interrupted attempt without changing its backlog or blockers."""
    content = read_file(story_path)
    content = _replace_section_body(content, "Status", "UNFINISHED")
    story_path.write_text(content, encoding="utf-8")


def set_story_done(story_path: Path) -> None:
    """Normalize a completed story's own Status to DONE. Idempotent.

    Claude normally writes DONE itself, but its Status is a statement
    about the implementation and cannot be relied on as the record of the
    harness's verdict: a CI-fix round sets UNFINISHED, and a resumed
    attempt may complete from a status Claude never rewrote. The
    completion transition therefore states it rather than assuming it.
    """

    content = read_file(story_path)

    if classify_story_status(extract_status_section(content)) == "DONE":
        return

    story_path.write_text(
        _replace_section_body(content, "Status", "DONE"),
        encoding="utf-8",
    )


def set_story_blocked(
        story_path: Path,
        blocker_note: str,
) -> None:
    content = read_file(
        story_path
    )

    content = _replace_section_body(
        content, "Status", "BLOCKED"
    )
    content = _replace_section_body(
        content, "Blockers", blocker_note
    )

    story_path.write_text(
        content,
        encoding="utf-8"
    )


def clear_story_blocked(
        story_path: Path
) -> None:
    content = read_file(
        story_path
    )

    content = _replace_section_body(
        content, "Status", "TODO"
    )
    content = _replace_section_body(
        content, "Blockers", "None."
    )

    story_path.write_text(
        content,
        encoding="utf-8"
    )


# ============================================================
# BACKLOG.md Active <-> Blocked <-> To Do bullet movement
#
# Small, purpose-built text surgery mirroring
# move_backlog_entry_to_active() above -- never a generic Markdown
# editor. '## Blocked' and '## To Do' may each hold multiple entries,
# so both destination moves append rather than replace.
# ============================================================

def _pull_bullet_from_backlog_section(
        backlog_content: str,
        heading: str,
        filename: str,
) -> tuple[str, str | None]:
    span = find_section_span(
        backlog_content,
        heading
    )

    if span is None:
        return backlog_content, None

    start, end = span
    body = backlog_content[start:end]

    bullet = None
    remaining_lines = []
    dropping_continuation = False

    for line in body.splitlines(keepends=True):
        if bullet is None and bullet_names_story(line, filename):
            bullet = line.rstrip("\n")
            dropping_continuation = True
            continue

        # Continuation lines under the entry being moved are prose the
        # index may not carry (see BANNED_ENTRY_PROSE). They are dropped
        # with the move rather than left behind: a note reading
        # "dependency note: - STORY-WEB-019 (DONE)." under a To Do row
        # means nothing once the row is Done in another section, and no
        # mover can ever find it again by story name.
        if dropping_continuation and _is_entry_continuation(line):
            continue

        dropping_continuation = False
        remaining_lines.append(line)

    content = (
        backlog_content[:start]
        + "".join(remaining_lines)
        + backlog_content[end:]
    )

    return content, bullet


def _append_bullet_to_backlog_section(
        backlog_content: str,
        heading: str,
        bullet: str,
) -> str:
    span = find_section_span(
        backlog_content,
        heading
    )

    if span is None:
        raise RuntimeError(
            f"agent/stories/BACKLOG.md has no '## {heading}' section."
        )

    start, end = span
    body = backlog_content[start:end]

    # Any "_(none ...)_" placeholder, not only the bare "_(none)_" spelling
    # -- a placeholder that explains itself ("_(none - no story is
    # active)_") is still a placeholder, and leaving it above the first
    # real entry made that section fail entry validation.
    if not body.strip() or SECTION_PLACEHOLDER.match(body.strip()):
        body = ""

    new_body = body.rstrip("\n")
    new_body = (new_body + "\n" if new_body else "") + bullet + "\n"

    return backlog_content[:start] + new_body + backlog_content[end:]


def move_backlog_entry_to_blocked(
        backlog_content: str,
        filename: str,
        blocker_note: str = "",
) -> str:
    """
    Move `filename`'s bullet out of '## Active' (its only possible
    source -- a story is only ever blocked this way while it is the
    active story) into '## Blocked', restamped BLOCKED. If BACKLOG.md
    has no matching bullet under Active (already out of sync), a bare
    bullet is used rather than leaving the newly blocked story
    undocumented.

    `blocker_note` is accepted and deliberately not written: the reason a
    story is blocked belongs in that story's own '## Blockers' section,
    which is the single place anything reads it from. Appending it here
    used to leave the row unparseable as a canonical entry, so the row
    stopped counting as a Blocked entry at all.
    """

    content, bullet = _pull_bullet_from_backlog_section(
        backlog_content, "Active", filename
    )

    if bullet is None:
        bullet = f"- `{filename}`"

    return _append_bullet_to_backlog_section(
        content, "Blocked", rewrite_backlog_entry_status(bullet, "BLOCKED")
    )


def move_backlog_entry_to_done(
        backlog_content: str,
        filename: str,
        title: str = "",
) -> str:
    """
    Move `filename`'s entry into '## Done', restamped DONE, from
    whichever queue section currently holds it.

    This is the transition that had no implementation at all. Every other
    one existed (To Do -> Active, Active -> Blocked, Blocked -> To Do),
    so a story that passed evaluation and CI could only leave '## Active'
    if a *planner* happened to rewrite the Markdown later -- an LLM doing
    the harness's own bookkeeping, which is exactly how a DONE story sat
    under '## Active' while planning ran against the inconsistent queue.

    Idempotent: a story already listed under '## Done' is left alone, so
    an interrupted finalization can simply be repeated.
    """

    if filename in parse_backlog_section(backlog_content, "Done"):
        return _pull_bullet_from_backlog_section(
            backlog_content, "Active", filename
        )[0]

    content = backlog_content
    bullet = None

    for heading in ("Active", "To Do", "Blocked"):
        content, bullet = _pull_bullet_from_backlog_section(
            content, heading, filename
        )

        if bullet is not None:
            break

    if bullet is None:
        bullet = f"- `{filename}`"

    fields = parse_backlog_entry(bullet)

    if fields is not None:
        bullet = format_completed_backlog_entry(
            fields["story_id"],
            fields["filename"],
            fields["milestone"],
            title,
        )
    else:
        bullet = rewrite_backlog_entry_status(bullet, "DONE")

    return _append_bullet_to_backlog_section(
        content, "Done", bullet
    )


def ensure_backlog_entry_blocked(
        backlog_content: str,
        filename: str,
) -> str:
    """
    Idempotently ensure `filename` is listed under '## Blocked',
    pulling it out of '## Active' if it is still sitting there (e.g.
    a story whose own file already says BLOCKED -- set by Claude Code
    itself or a human -- before BACKLOG.md was updated to match).
    Never duplicates: does nothing if `filename` is already listed
    under '## Blocked'. Unlike move_backlog_entry_to_blocked(), this
    adds no extra annotation -- the story's own Blockers section is
    assumed to already explain why.
    """

    if filename in parse_backlog_section(backlog_content, "Blocked"):
        return backlog_content

    content, bullet = _pull_bullet_from_backlog_section(
        backlog_content, "Active", filename
    )

    if bullet is None:
        bullet = f"- `{filename}`"

    return _append_bullet_to_backlog_section(
        content, "Blocked", bullet
    )


def move_backlog_entry_to_todo(
        backlog_content: str,
        filename: str,
) -> str:
    """
    Move `filename`'s bullet out of '## Blocked' back into '## To Do',
    appended at the end of the existing order. Performs no eligibility
    judgment itself -- callers must already have re-verified the story
    is genuinely executable before calling this.
    """

    content, bullet = _pull_bullet_from_backlog_section(
        backlog_content, "Blocked", filename
    )

    if bullet is None:
        bullet = f"- `{filename}`"

    return _append_bullet_to_backlog_section(
        content, "To Do", rewrite_backlog_entry_status(bullet, "TODO")
    )
