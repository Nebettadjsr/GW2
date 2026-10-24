"""Choose one ready GitHub story without invoking Claude for an empty queue."""

from __future__ import annotations

import json
import os
import re
import urllib.request
from pathlib import Path


STORY_ID = re.compile(r"^(STORY-[A-Z]+-\d+)\b")
DEPENDENCY_SECTION = re.compile(r"^## Dependencies[ \t]*\r?\n([\s\S]*?)(?=^## |\Z)", re.MULTILINE)
DEPENDENCY_LINE = re.compile(r"^Migration status:.*?Dependencies:\s*([^\n]*)", re.MULTILINE)
PR_CLOSES = re.compile(r"\b(?:closes|fixes|resolves)\s+#(\d+)\b", re.IGNORECASE)


def story_id(issue: dict) -> str | None:
    match = STORY_ID.match(issue["title"])
    return match.group(1) if match else None


def dependencies(issue: dict) -> set[str]:
    body = issue.get("body") or ""
    section = DEPENDENCY_SECTION.search(body)
    if section:
        source = section.group(1)
    else:
        line = DEPENDENCY_LINE.search(body)
        source = line.group(1) if line else ""
    return set(re.findall(r"STORY-[A-Z]+-\d+", source))


def linked_pr(issue: dict, pulls: list[dict]) -> bool:
    number = issue["number"]
    prefixes = (f"story/issue-{number}-run-", f"story/{number}-")
    for pull in pulls:
        if pull["state"] != "open":
            continue
        if (pull.get("head", {}).get("repo") or {}).get("full_name") != issue["repository_url"].removeprefix(
            "https://api.github.com/repos/"
        ):
            continue
        if pull["head"]["ref"].startswith(prefixes):
            return True
        if str(number) in PR_CLOSES.findall(pull.get("body") or ""):
            return True
    return False


def select_story(issues: list[dict], pulls: list[dict]) -> tuple[dict | None, list[str]]:
    stories = [issue for issue in issues if "pull_request" not in issue and story_id(issue)]
    by_id = {story_id(issue): issue for issue in stories}
    reasons = []
    def priority(item: dict) -> tuple[int, int]:
        labels = {label["name"] for label in item.get("labels", [])}
        return (0 if "needs-rework" in labels else 1, item["number"])

    for issue in sorted(stories, key=priority):
        if issue["state"] != "open":
            continue
        labels = {label["name"] for label in issue.get("labels", [])}
        if "auto" not in labels:
            continue
        number = issue["number"]
        if labels & {"pairing", "needs-decision", "blocked"}:
            reasons.append(f"#{number}: pairing, decision, or blocked label")
            continue
        if re.search(r"^Migration status:\s*BLOCKED\b", issue.get("body") or "", re.MULTILINE):
            reasons.append(f"#{number}: issue body still says BLOCKED")
            continue
        unmet = [dep for dep in sorted(dependencies(issue)) if dep not in by_id or by_id[dep]["state"] != "closed"]
        if unmet:
            reasons.append(f"#{number}: dependency open or missing ({', '.join(unmet)})")
            continue
        has_pr = linked_pr(issue, pulls)
        if "needs-rework" in labels and not has_pr:
            reasons.append(f"#{number}: needs-rework label has no open PR")
            continue
        if has_pr and "needs-rework" not in labels:
            reasons.append(f"#{number}: an open PR already exists")
            continue
        return issue, reasons
    return None, reasons


def github_pages(path: str) -> list[dict]:
    token = os.environ["GH_TOKEN"]
    results = []
    page = 1
    while True:
        request = urllib.request.Request(
            f"https://api.github.com/repos/{os.environ['GITHUB_REPOSITORY']}/{path}"
            f"&page={page}",
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {token}",
                "User-Agent": "GW2-Claude-story-selector",
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        with urllib.request.urlopen(request, timeout=20) as response:
            batch = json.load(response)
        results.extend(batch)
        if len(batch) < 100:
            return results
        page += 1


def main() -> None:
    issues = github_pages("issues?state=all&per_page=100")
    pulls = github_pages("pulls?state=open&per_page=100")
    selected, reasons = select_story(issues, pulls)
    summary = Path(os.environ["GITHUB_STEP_SUMMARY"])
    output = Path(os.environ["GITHUB_OUTPUT"])
    open_stories = [
        issue for issue in issues
        if issue["state"] == "open" and "pull_request" not in issue and story_id(issue)
    ]
    auto_count = sum(
        any(label["name"] == "auto" for label in issue.get("labels", []))
        for issue in open_stories
    )
    pairing = sorted(
        issue["number"] for issue in open_stories
        if any(label["name"] == "pairing" for label in issue.get("labels", []))
    )
    blocked = sorted(
        issue["number"] for issue in open_stories
        if re.search(r"^Migration status:\s*BLOCKED\b", issue.get("body") or "", re.MULTILINE)
        or any(label["name"] in {"blocked", "needs-decision"} for label in issue.get("labels", []))
    )
    if selected:
        number = selected["number"]
        with output.open("a", encoding="utf-8") as stream:
            stream.write(f"issue_number={number}\n")
        message = f"Selected [issue #{number}](https://github.com/{os.environ['GITHUB_REPOSITORY']}/issues/{number}).\n"
    else:
        message = f"No ready `auto` story. Claude did not run. Open `auto` stories: {auto_count}.\n"
        if auto_count == 0:
            message += "A planning session must prepare and label the next automatic story.\n"
    message += f"\nOpen pairing stories: {', '.join(f'#{n}' for n in pairing) or 'none'}.\n"
    message += f"Blocked or waiting for a decision: {', '.join(f'#{n}' for n in blocked) or 'none'}.\n"
    if reasons:
        message += "\nSkipped stories:\n" + "\n".join(f"- {reason}" for reason in reasons) + "\n"
    with summary.open("a", encoding="utf-8") as stream:
        stream.write("## Claude story selection\n\n" + message)
    print(message)


if __name__ == "__main__":
    main()
