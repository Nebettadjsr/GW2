"""Publish a structured Codex verdict and gate auto-merge on the PR head."""

import json
import os
from pathlib import Path
import re
import sys
from urllib.request import Request, urlopen


REVIEW_MARKER = "<!-- gw2-codex-review head:"


def api(method: str, path: str, token: str, payload: dict | None = None):
    request = Request(
        "https://api.github.com" + path,
        data=json.dumps(payload).encode("utf-8") if payload is not None else None,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "User-Agent": "GW2-Codex-review",
        },
        method=method,
    )
    with urlopen(request, timeout=20) as response:
        content = response.read()
        return json.loads(content) if content else None


def marker(head: str) -> str:
    return f"{REVIEW_MARKER}{head} -->"


def previous_heads(comments: list[dict]) -> set[str]:
    heads = set()
    for comment in comments:
        match = re.search(r"<!-- gw2-codex-review head:([0-9a-f]{40}) -->", comment.get("body") or "")
        if match:
            heads.add(match.group(1))
    return heads


def existing_followups(repository: str, number: int, head: str, token: str) -> dict[int, str]:
    found = {}
    prefix = f"<!-- gw2-codex-followup pr:{number} head:{head} index:"
    for page in range(1, 11):
        issues = api("GET", f"/repos/{repository}/issues?state=all&per_page=100&page={page}", token)
        for issue in issues:
            body = issue.get("body") or ""
            match = re.search(re.escape(prefix) + r"(\d+) -->", body)
            if match:
                found[int(match.group(1))] = issue["html_url"]
        if len(issues) < 100:
            break
    return found


def format_comment(verdict: dict, head: str, round_number: int, followup_urls: list[str], retry: bool = False) -> str:
    lines = [marker(head), f"## Codex review: {verdict['verdict'].replace('_', ' ').title()}", "", verdict["summary"], ""]
    if verdict["blocking_findings"]:
        lines.extend(["### Blocking findings", ""])
        lines.extend(f"- {finding}" for finding in verdict["blocking_findings"])
        lines.append("")
    if followup_urls:
        lines.extend(["### Nonblocking follow-ups", ""])
        lines.extend(f"- {url}" for url in followup_urls)
        lines.append("")
    if retry:
        lines.extend(["@claude Please fix the blocking findings above on this PR branch, run the relevant tests, and report the result.", ""])
    lines.append(f"Review round: {round_number}/3. Head: `{head}`.")
    return "\n".join(lines)


def main() -> int:
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"))
    verdict = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    repository = os.environ["GITHUB_REPOSITORY"]
    token = os.environ["GITHUB_TOKEN"]
    original_pr = event["pull_request"]
    number = original_pr["number"]
    head = original_pr["head"]["sha"]

    if verdict.get("verdict") not in {"approved", "changes_requested", "needs_decision"}:
        raise ValueError("Invalid reviewer verdict")
    if not isinstance(verdict.get("summary"), str) or not isinstance(verdict.get("blocking_findings"), list):
        raise ValueError("Incomplete reviewer result")
    if verdict["verdict"] == "approved" and verdict["blocking_findings"]:
        raise ValueError("Reviewer approved with blocking findings")
    issue_match = re.search(
        r"(?im)\b(?:closes|fixes|resolves|part of)\s+#\d+\b", original_pr.get("body") or ""
    )
    if verdict["verdict"] == "approved" and not issue_match:
        raise ValueError("Cannot approve a PR without a linked issue")

    current = api("GET", f"/repos/{repository}/pulls/{number}", token)
    if current["head"]["sha"] != head or current["state"] != "open":
        raise RuntimeError("PR head changed or PR closed during review; discard this verdict")

    comments = api("GET", f"/repos/{repository}/issues/{number}/comments?per_page=100", token)
    heads = previous_heads(comments)
    round_number = len(heads) + (head not in heads)
    if round_number >= 3 and verdict["verdict"] != "approved":
        verdict["verdict"] = "needs_decision"
        verdict["summary"] += " Three distinct review rounds have been used; human disposition is needed."

    followup_urls = []
    if verdict["verdict"] == "approved":
        existing = existing_followups(repository, number, head, token) if verdict.get("followups") else {}
        for index, item in enumerate(verdict.get("followups", [])[:3]):
            if not item.get("title") or not item.get("body"):
                raise ValueError("Follow-up issue needs a title and body")
            if index in existing:
                followup_urls.append(existing[index])
                continue
            issue = api(
                "POST",
                f"/repos/{repository}/issues",
                token,
                {
                    "title": item["title"],
                    "body": item["body"] + f"\n\nFollow-up from #{number} at `{head}`.\n"
                    f"<!-- gw2-codex-followup pr:{number} head:{head} index:{index} -->",
                },
            )
            followup_urls.append(issue["html_url"])

    if verdict["verdict"] == "needs_decision":
        api("POST", f"/repos/{repository}/issues/{number}/labels", token, {"labels": ["needs-decision"]})

    labels = {item["name"] for item in current.get("labels", [])}
    linked_number = int(re.search(r"#(\d+)", issue_match.group(0)).group(1)) if issue_match else None
    linked = api("GET", f"/repos/{repository}/issues/{linked_number}", token) if linked_number else {}
    labels.update(item["name"] for item in linked.get("labels", []))
    retry = verdict["verdict"] == "changes_requested" and "auto" in labels and round_number < 3
    body = format_comment(verdict, head, round_number, followup_urls, retry)
    previous_comment = next((item for item in comments if marker(head) in (item.get("body") or "")), None)
    if previous_comment:
        api("PATCH", f"/repos/{repository}/issues/comments/{previous_comment['id']}", token, {"body": body})
    else:
        api("POST", f"/repos/{repository}/issues/{number}/comments", token, {"body": body})

    if verdict["verdict"] != "approved":
        print(f"Codex requested {verdict['verdict']} on PR #{number}")
        return 1

    if "auto" in labels and "needs-decision" not in labels and os.environ.get("AUTO_MERGE_ENABLED") == "true":
        mutation = {
            "query": "mutation($id: ID!) { enablePullRequestAutoMerge(input: {pullRequestId: $id, mergeMethod: SQUASH}) { pullRequest { number } } }",
            "variables": {"id": current["node_id"]},
        }
        result = api("POST", "/graphql", token, mutation)
        if result.get("errors"):
            raise RuntimeError("Could not enable auto-merge: " + json.dumps(result["errors"]))
        print(f"Codex approved PR #{number}; auto-merge enabled")
    else:
        print(f"Codex approved PR #{number}; auto-merge remains disabled")
    return 0


if __name__ == "__main__":
    sys.exit(main())
