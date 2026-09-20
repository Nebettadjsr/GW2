from pathlib import Path
import hashlib
import json
import re


# ============================================================
# File helpers
# ============================================================

def read_file(path: Path) -> str:
    if not path.exists():
        raise FileNotFoundError(
            f"Required file not found: {path}"
        )

    return path.read_text(
        encoding="utf-8"
    )


def write_json(
    path: Path,
    data: dict
) -> None:
    path.write_text(
        json.dumps(
            data,
            indent=2,
            ensure_ascii=False
        ),
        encoding="utf-8"
    )


def file_hash(path: Path) -> str | None:
    if not path.exists():
        return None

    return hashlib.sha256(
        path.read_bytes()
    ).hexdigest()


def find_section_span(
    content: str,
    heading: str
) -> tuple[int, int] | None:
    """
    Return the (start, end) character offsets of a '## <heading>'
    section's body in a Markdown document (excluding the heading line
    itself) -- the body runs up to the next '## ' heading or end of
    file. Returns None if the heading isn't present. Shared by any
    runtime module that needs to read or rewrite one section of a
    generated index file (e.g. agent/stories/BACKLOG.md) without
    disturbing the rest of it.
    """

    # The trailing whitespace before the mandatory newline is
    # deliberately [ \t]*, not \s* -- \s also matches newlines, so a
    # greedy \s* here would swallow through any blank lines that
    # immediately follow the heading (e.g. an empty section with no
    # "_(none)_" placeholder), consuming the very "\n##" boundary the
    # lookahead below needs to find the *next* heading. That silently
    # merged an empty section's body into the next section's heading
    # line instead of returning an empty span.
    pattern = re.compile(
        rf"(##\s*{re.escape(heading)}[ \t]*\n)(.*?)(?=\n##\s|\Z)",
        re.DOTALL | re.IGNORECASE,
    )

    match = pattern.search(
        content
    )

    if not match:
        return None

    return match.start(2), match.end(2)
