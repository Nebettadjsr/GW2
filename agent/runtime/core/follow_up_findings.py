import re


FINDINGS_HEADING = "Follow-up Findings"
DISPOSITIONS_HEADING = "Follow-up Findings Disposition"
FINDING_LINE = re.compile(r"^\s*(?:[-*]\s+)?(F\d{3})\s*:\s*(.+?)\s*$", re.I)
DISPOSITION_LINE = re.compile(
    r"^\s*(?:[-*]\s+)?(F\d{3})\s*:\s*"
    r"(ALREADY COVERED|FOLLOW-UP STORY|DEFERRED|DISMISSED)\s*[-—:]\s*(.+?)\s*$",
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
