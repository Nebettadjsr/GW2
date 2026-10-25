"""Merge one Claude story PR only after CI and native Codex review agree.

This script runs from the default branch on CI completion or on an edited Codex
review summary. It never checks out or executes code from the pull request.
"""

from __future__ import annotations

import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


REQUIRED_CHECKS = {"Backend tests (Maven)", "Frontend tests (Vitest)", "Coverage KPI report"}
BOT_LOGIN = "chatgpt-codex-connector[bot]"
REVIEW_MARKER = "<!-- codex-pull-request-review-summary -->"
REVIEW_STATE = re.compile(r"<!-- codex-review-state:v1 (\{.*?\}) -->", re.DOTALL)
RUN_REPORT = re.compile(r"<!-- gw2-claude-run-report:v1 head=([0-9a-f]{40}) -->")
ISSUE_LINK = re.compile(r"\b(?:closes|fixes|resolves)\s+#([1-9][0-9]*)\b", re.IGNORECASE)
STORY_TITLE = re.compile(r"^STORY-[A-Z]+-[0-9]+\b")


def api(path: str, *, method: str = "GET", data: dict | None = None) -> dict | list:
    repository = os.environ["GITHUB_REPOSITORY"]
    token = os.environ["GH_TOKEN"]
    body = json.dumps(data).encode() if data is not None else None
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repository}/{path}",
        data=body,
        method=method,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "User-Agent": "GW2-Codex-review-merge-gate",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.load(response)


def pages(path: str) -> list[dict]:
    result: list[dict] = []
    page = 1
    while True:
        separator = "&" if "?" in path else "?"
        batch = api(f"{path}{separator}per_page=100&page={page}")
        if not isinstance(batch, list):
            raise ValueError(f"Expected a list from {path}")
        result.extend(batch)
        if len(batch) < 100:
            return result
        page += 1


def candidate_numbers(event_name: str, event: dict, repository: str) -> list[int]:
    if event_name == "issue_comment":
        comment = event.get("comment") or {}
        issue = event.get("issue") or {}
        if not issue.get("pull_request"):
            return []
        if (comment.get("user") or {}).get("login") != BOT_LOGIN:
            return []
        if REVIEW_MARKER not in (comment.get("body") or ""):
            return []
        return [issue["number"]]
    if event_name == "workflow_run":
        run = event.get("workflow_run") or {}
        if run.get("name") != "CI" or run.get("conclusion") not in {"success", "failure", "timed_out"}:
            return []
        branch = run.get("head_branch") or ""
        if not branch.startswith("story/issue-"):
            return []
        owner = repository.split("/", 1)[0]
        head = urllib.parse.quote(f"{owner}:{branch}", safe="")
        pulls = pages(f"pulls?state=open&head={head}")
        return [pr["number"] for pr in pulls if pr.get("head", {}).get("sha") == run.get("head_sha")]
    return []


def review_state_for_head(comments: list[dict], head_sha: str) -> dict | None:
    for comment in reversed(comments):
        if (comment.get("user") or {}).get("login") != BOT_LOGIN:
            continue
        body = comment.get("body") or ""
        if REVIEW_MARKER not in body:
            continue
        match = REVIEW_STATE.search(body)
        if not match:
            return None
        try:
            state = json.loads(match.group(1))["code_review"]
        except (ValueError, KeyError, TypeError):
            return None
        return state if state.get("commit_sha") == head_sha else None
    return None


def review_for_head(comments: list[dict], head_sha: str) -> bool:
    state = review_state_for_head(comments, head_sha) or {}
    return (state.get("status") == "completed"
            and state.get("has_any_findings") is False
            and state.get("has_unresolved_findings") is False)


def checks_green(checks: list[dict]) -> bool:
    by_name: dict[str, list[dict]] = {name: [] for name in REQUIRED_CHECKS}
    for check in checks:
        if check.get("name") in by_name and (check.get("app") or {}).get("slug") == "github-actions":
            by_name[check["name"]].append(check)
    return all(
        by_name[name]
        and all(check.get("status") == "completed" and check.get("conclusion") == "success"
                for check in by_name[name])
        for name in REQUIRED_CHECKS
    )


def report_for_head(pr: dict, comments: list[dict], repository: str) -> str | None:
    head_sha = pr["head"]["sha"]
    owner = repository.split("/", 1)[0]
    for comment in reversed(comments):
        if (comment.get("user") or {}).get("login") != owner:
            continue
        body = comment.get("body") or ""
        marker = RUN_REPORT.search(body)
        if marker and marker.group(1) == head_sha:
            return body
    body = pr.get("body") or ""
    marker = RUN_REPORT.search(body)
    return body if marker and marker.group(1) == head_sha else None


def ready_reason(pr: dict, issue: dict, comments: list[dict], checks: list[dict], files: list[dict], repository: str) -> str | None:
    if pr.get("state") != "open" or pr.get("draft") or pr.get("base", {}).get("ref") != "master":
        return "PR is closed, draft, or targets another branch"
    if pr.get("base", {}).get("repo", {}).get("full_name") != repository:
        return "PR base is outside this repository"
    if pr.get("head", {}).get("repo", {}).get("full_name") != repository:
        return "PR head is outside this repository"
    if issue.get("state") != "open" or not STORY_TITLE.match(issue.get("title") or ""):
        return "linked story is not open"
    issue_labels = {label["name"] for label in issue.get("labels", [])}
    pr_labels = {label["name"] for label in pr.get("labels", [])}
    if "auto" not in issue_labels or (issue_labels | pr_labels) & {"pairing", "needs-decision", "blocked", "needs-rework"}:
        return "story is not eligible for automatic merge"
    if re.search(r"^Migration status:\s*BLOCKED\b", issue.get("body") or "", re.MULTILINE):
        return "story body still marks it blocked"
    number = issue["number"]
    branch = pr.get("head", {}).get("ref") or ""
    if not branch.startswith(f"story/issue-{number}-run-"):
        return "PR is not on the Claude story branch"
    protected = {"AGENTS.md", "CLAUDE.md", "GW2 GitHub Flow Migration.md"}
    if any(path.startswith(".github/") or path in protected
           for file in files for path in (file.get("filename") or "", file.get("previous_filename") or "")):
        return "PR changes development automation or agent instructions"
    body = pr.get("body") or ""
    linked = ISSUE_LINK.findall(body)
    if linked != [str(number)]:
        return "PR must close exactly its linked story"
    report = report_for_head(pr, comments, repository)
    if not report or "## Implementation report" not in report or "Claude did not supply a run report" in report:
        return "PR lacks a Claude implementation report for this head commit"
    if not re.search(r"^Local-only checks still needed: none$", report, re.MULTILINE):
        return "PR still needs local-only checks or lacks their status"
    if not re.search(r"^Unresolved gaps: none$", report, re.MULTILINE):
        return "PR has an unresolved gap or lacks its status"
    if not checks_green(checks):
        return "required CI checks are missing, pending, or red"
    if not review_for_head(comments, pr["head"]["sha"]):
        return "Codex review is not clean and complete for this head commit"
    return None


def rework_reason(pr: dict, issue: dict, comments: list[dict], checks: list[dict], repository: str) -> str | None:
    """Requeue established stories after definitive failure; never start Claude."""
    labels = {label["name"] for label in issue.get("labels", []) + pr.get("labels", [])}
    if (pr.get("state") != "open" or pr.get("draft") or issue.get("state") != "open"
            or not STORY_TITLE.match(issue.get("title") or "")
            or "auto" not in {label["name"] for label in issue.get("labels", [])}
            or labels & {"pairing", "needs-decision", "blocked"}
            or pr.get("base", {}).get("ref") != "master"
            or pr.get("base", {}).get("repo", {}).get("full_name") != repository
            or pr.get("head", {}).get("repo", {}).get("full_name") != repository
            or not pr.get("head", {}).get("ref", "").startswith(f"story/issue-{issue['number']}-run-")
            or re.search(r"^Migration status:\s*BLOCKED\b", issue.get("body") or "", re.MULTILINE)):
        return None
    state = review_state_for_head(comments, pr["head"]["sha"]) or {}
    if state.get("status") == "completed" and (
            state.get("has_any_findings") is True or state.get("has_unresolved_findings") is True):
        return "current Codex review has findings"
    if any(check.get("name") in REQUIRED_CHECKS
           and (check.get("app") or {}).get("slug") == "github-actions"
           and check.get("status") == "completed"
           and check.get("conclusion") in {"failure", "timed_out"} for check in checks):
        return "current CI failed"
    report = report_for_head(pr, comments, repository)
    if report and (not re.search(r"^Local-only checks still needed: none$", report, re.MULTILINE)
                   or not re.search(r"^Unresolved gaps: none$", report, re.MULTILINE)):
        return "current implementation report lists incomplete verification or gaps"
    return None


def main() -> None:
    event_name = os.environ["GITHUB_EVENT_NAME"]
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
    repository = os.environ["GITHUB_REPOSITORY"]
    for number in candidate_numbers(event_name, event, repository):
        pr = api(f"pulls/{number}")
        match = ISSUE_LINK.search(pr.get("body") or "")
        if not match:
            print(f"PR #{number}: no linked issue")
            continue
        issue_number = int(match.group(1))
        issue = api(f"issues/{issue_number}")
        comments = pages(f"issues/{number}/comments")
        files = pages(f"pulls/{number}/files")
        checks_response = api(f"commits/{pr['head']['sha']}/check-runs?per_page=100")
        checks = checks_response.get("check_runs", [])
        rework = rework_reason(pr, issue, comments, checks, repository)
        if rework:
            if "needs-rework" not in {label["name"] for label in issue.get("labels", [])}:
                api(f"issues/{issue_number}/labels", method="POST", data={"labels": ["needs-rework"]})
            print(f"PR #{number}: {rework}; queued for the next manual Claude run")
            continue
        reason = ready_reason(pr, issue, comments, checks, files, repository)
        if reason:
            print(f"PR #{number}: {reason}; no merge")
            continue
        try:
            result = api(f"pulls/{number}/merge", method="PUT", data={"sha": pr["head"]["sha"], "merge_method": "squash"})
        except urllib.error.HTTPError as error:
            # CI and Codex events can arrive together. Another invocation may
            # have merged this exact PR between the read and conditional PUT.
            latest = api(f"pulls/{number}")
            if latest.get("merged"):
                print(f"PR #{number}: already merged by another gate invocation")
                continue
            print(f"PR #{number}: merge rejected by GitHub ({error.code})", file=sys.stderr)
            raise
        if not result.get("merged"):
            raise RuntimeError(f"PR #{number}: GitHub did not confirm a merge")
        # An API merge does not reliably apply the PR body's "Closes #N"
        # directive. Close the story explicitly so the selector cannot run it
        # again after a successful merge.
        api(f"issues/{issue_number}", method="PATCH", data={"state": "closed", "state_reason": "completed"})
        print(f"PR #{number}: merged at {result['sha']}")


if __name__ == "__main__":
    main()
