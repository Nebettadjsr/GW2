import re


FINDINGS_HEADING = "Follow-up Findings"
DISPOSITIONS_HEADING = "Follow-up Findings Disposition"
FINDING_LINE = re.compile(r"^\s*(?:[-*]\s+)?(F\d{3})\s*:\s*(.+?)\s*$", re.I)

# The separator between the disposition keyword and its reference is
# deliberately permissive: any run of non-alphanumeric characters, or none
# at all.
#
# It used to be the character class [-—:], which silently broke the whole
# follow-up mechanism. Several story files were written through a non-UTF-8
# round trip at some point, turning their em dashes into literal '?'
# characters -- "F002: ALREADY COVERED ? STORY-SYNC-005 ...". Those lines
# stopped matching, so unresolved_findings() reported the findings as
# undisposed, the planner was handed them again as new work, and it
# replaced the existing dispositions with fresh follow-up stories. Two
# duplicate stories in one real run came from exactly this: WEB-025 for a
# finding already closed by STORY-APP-013, and WEB-026 for one already
# closed by STORY-SYNC-005.
#
# A disposition is a record that a human or planner decided something. It
# must not become invisible because of a mangled punctuation mark.
DISPOSITION_LINE = re.compile(
    r"^\s*(?:[-*]\s+)?(F\d{3})\s*:\s*"
    r"(ALREADY COVERED|FOLLOW-UP STORY|DEFERRED|DISMISSED)"
    r"\s*[^\w\s]*\s*(.+?)\s*$",
    re.I,
)


def _section(content: str, heading: str) -> str:
    match = re.search(rf"^##\s*{re.escape(heading)}\s*$", content, re.M | re.I)
    if not match:
        return ""
    end = re.search(r"^##\s+", content[match.end():], re.M)
    return content[match.end():match.end() + end.start() if end else len(content)]


def unresolved_findings(content: str, story_path: str) -> list[dict[str, str]]:
    findings = {}
    for line in _section(content, FINDINGS_HEADING).splitlines():
        match = FINDING_LINE.match(line)
        if match:
            findings[match.group(1).upper()] = match.group(2).strip()

    disposed = set()
    for line in _section(content, DISPOSITIONS_HEADING).splitlines():
        match = DISPOSITION_LINE.match(line)
        if match:
            disposed.add(match.group(1).upper())

    return [
        {"id": finding_id, "finding": finding, "story_path": story_path}
        for finding_id, finding in findings.items()
        if finding_id not in disposed
    ]


def planner_block(findings: list[dict[str, str]]) -> str:
    if not findings:
        return "(no unresolved Claude implementation follow-up findings)"
    return "\n".join(
        f"- {item['story_path']} [{item['id']}]: {item['finding']}"
        for item in findings
    )
