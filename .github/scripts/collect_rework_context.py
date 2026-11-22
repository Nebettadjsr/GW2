#!/usr/bin/env python3
"""Write the rework context for an existing story PR before Claude starts.

A rework run used to receive only "address review and CI feedback" and had to find the failures on
its own. This collects, for the PR's current head commit, the failing checks with their annotation
messages (the test summaries written by summarize_test_failures.py) and the unresolved review
threads, and writes them to a Markdown file.

Environment: GH_TOKEN, GITHUB_REPOSITORY, REWORK_BRANCH, REWORK_CONTEXT_FILE.
Collection is best effort: a failed lookup is reported in the file instead of failing the run.
"""
import json
import os
import subprocess
import sys
from pathlib import Path

MAX_ANNOTATIONS_PER_CHECK = 15
MAX_TEXT = 900
FAILED = {"failure", "timed_out", "cancelled", "action_required", "startup_failure"}


def clip(text: str, limit: int = MAX_TEXT) -> str:
    text = (text or "").strip()
    return text if len(text) <= limit else text[: limit - 3] + "..."


def render(pr_number, head_sha: str, checks: list[dict], annotations: dict[str, list[dict]],
           threads: list[dict], notes: list[str]) -> str:
    lines = [f"# Rework context for PR #{pr_number}", "",
             f"Current head commit: `{head_sha}`. Fix every item below before finishing; do not report "
             "`Unresolved gaps: none` while one is unaddressed.", ""]
    failing = [c for c in checks if c.get("conclusion") in FAILED]
    lines.append("## Failing checks")
    if not failing:
        lines += ["", "None recorded for this head (checks may still be running or absent).", ""]
    for check in failing:
        lines += ["", f"### {check.get('name')} ({check.get('conclusion')})", ""]
        found = annotations.get(str(check.get("id")), [])
        if not found:
            lines.append("No annotations were published. Read the job log of this check.")
        for item in found[:MAX_ANNOTATIONS_PER_CHECK]:
            where = item.get("path") or ""
            title = item.get("title") or ""
            lines.append(f"- {where} {title}: {clip(item.get('message', ''))}".replace("  ", " "))
        if len(found) > MAX_ANNOTATIONS_PER_CHECK:
            lines.append(f"- ... {len(found) - MAX_ANNOTATIONS_PER_CHECK} more annotations omitted")
    lines += ["", "## Unresolved review threads", ""]
    if not threads:
        lines.append("None.")
    for thread in threads:
        lines.append(f"- {thread.get('path') or 'general'}: {clip(thread.get('body', ''))}")
    if notes:
        lines += ["", "## Collection notes", ""] + [f"- {note}" for note in notes]
    lines += ["", "## Required approach", "",
              "- When production behavior changes, search the tests for assertions of the OLD behavior "
              "(grep the changed method and its callers in `backend/src/test` and "
              "`frontend/src/**/__tests__`) and update them with a stated reason.",
              "- Run the complete test classes you found, not only newly written ones.", ""]
    return "\n".join(lines)


def gh_json(*args: str):
    result = subprocess.run(["gh", *args], capture_output=True, text=True, timeout=120)
    if result.returncode != 0:
        raise RuntimeError(clip(result.stderr, 300) or f"gh {args[0]} failed")
    return json.loads(result.stdout or "null")


def collect(repo: str, branch: str) -> str:
    notes: list[str] = []
    pr = gh_json("pr", "view", branch, "--repo", repo, "--json", "number,headRefOid")
    sha = pr["headRefOid"]
    checks: list[dict] = []
    annotations: dict[str, list[dict]] = {}
    threads: list[dict] = []
    try:
        data = gh_json("api", f"repos/{repo}/commits/{sha}/check-runs?per_page=100")
        checks = [{"id": c["id"], "name": c["name"], "conclusion": c.get("conclusion")}
                  for c in data.get("check_runs", [])]
        for check in checks:
            if check["conclusion"] in FAILED:
                try:
                    raw = gh_json("api", f"repos/{repo}/check-runs/{check['id']}/annotations?per_page=50")
                    # Warnings and notices (runtime deprecations, artifact hints) are noise here.
                    annotations[str(check["id"])] = [a for a in raw if a.get("annotation_level", "failure") == "failure"]
                except (RuntimeError, ValueError) as error:
                    notes.append(f"annotations of {check['name']} unavailable: {error}")
    except (RuntimeError, ValueError) as error:
        notes.append(f"check runs unavailable: {error}")
    try:
        owner, name = repo.split("/", 1)
        query = ("query($o:String!,$n:String!,$p:Int!){repository(owner:$o,name:$n){pullRequest(number:$p)"
                 "{reviewThreads(first:50){nodes{isResolved comments(first:1){nodes{path body}}}}}}}")
        graph = gh_json("api", "graphql", "-f", f"query={query}", "-F", f"p={pr['number']}",
                        "-f", f"o={owner}", "-f", f"n={name}")
        for node in graph["data"]["repository"]["pullRequest"]["reviewThreads"]["nodes"]:
            if not node["isResolved"] and node["comments"]["nodes"]:
                first = node["comments"]["nodes"][0]
                threads.append({"path": first.get("path"), "body": first.get("body")})
    except (RuntimeError, ValueError, KeyError, TypeError) as error:
        notes.append(f"review threads unavailable: {error}")
    return render(pr["number"], sha, checks, annotations, threads, notes)


def main() -> int:
    target = Path(os.environ["REWORK_CONTEXT_FILE"])
    try:
        text = collect(os.environ["GITHUB_REPOSITORY"], os.environ["REWORK_BRANCH"])
    except Exception as error:  # noqa: BLE001 - reporting must never fail the run
        text = f"# Rework context\n\nCollection failed: {clip(str(error), 300)}\n"
    target.write_text(text, encoding="utf-8")
    sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
