"""Commit and push a completed story, deterministically, from Python.

Committing used to be Claude's job and pushing nobody's, which is how a
repository ends up several finished stories ahead of its remote. Both now
happen here, at exactly one point in the workflow: after the evaluator accepts
a story and before the GitHub CI gate verifies it (docs/TEST_STRATEGY.md §36,
docs/CODING_GUIDELINES.md §8). One writer, one meaningful verification point
per story -- not a commit per file Claude touches.

Nothing here decides whether work is good; it only records it. Every function
is a thin, checked wrapper around git itself, so the repository -- not a model
and not a cached assumption -- remains the source of truth about what is
committed and what is pushed.
"""
import re
import subprocess
from pathlib import PurePosixPath

from agent.runtime.support.config import (
    CI_VERIFICATION_ENABLED,
    CI_WORKFLOW_FILE,
    PENDING_COMMIT_PATHS_FILE,
    REPO_ROOT,
)


# github.com/<owner>/<repo>, from either remote URL form. A non-GitHub
# remote is not an error -- it just means this repository has no CI gate.
GITHUB_REMOTE_PATTERN = re.compile(
    r"github\.com[:/]+(?P<slug>[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+?)(?:\.git)?/?$"
)


class GitCommandError(RuntimeError):
    """A git invocation the caller cannot sensibly continue without."""


def run_git(*arguments: str, check: bool = True) -> subprocess.CompletedProcess:
    result = subprocess.run(
        ["git", *arguments],
        cwd=REPO_ROOT,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
    )

    if check and result.returncode != 0:
        raise GitCommandError(
            "git " + " ".join(arguments) + " failed:\n"
            + (result.stderr or result.stdout).strip()
        )

    return result


def _output(*arguments: str) -> str:
    return run_git(*arguments).stdout.strip()


def head_sha() -> str:
    return _output("rev-parse", "HEAD")


def current_branch() -> str | None:
    """The checked-out branch, or None on a detached HEAD."""

    branch = _output("rev-parse", "--abbrev-ref", "HEAD")

    return None if branch == "HEAD" else branch


def remote_slug() -> str | None:
    """"owner/repo" when origin is a GitHub remote, else None."""

    result = run_git("remote", "get-url", "origin", check=False)

    if result.returncode != 0:
        return None

    match = GITHUB_REMOTE_PATTERN.search(result.stdout.strip())

    return match.group("slug") if match else None


def working_tree_changes() -> list[str]:
    """Porcelain lines for everything git would commit, .gitignore honored."""

    return [line for line in _output("status", "--porcelain").splitlines() if line.strip()]


def working_tree_paths() -> set[str]:
    """Repository-relative paths reported by Git, including untracked files."""
    return set(working_tree_entries())


def working_tree_entries() -> dict[str, str]:
    """Map each current path to its two-column porcelain status."""
    result = run_git("status", "--porcelain", "-z", "--untracked-files=all")
    records = result.stdout.split("\0")
    paths: dict[str, str] = {}
    index = 0
    while index < len(records):
        record = records[index]
        index += 1
        if len(record) < 4:
            continue
        status, path = record[:2], record[3:]
        paths[path.replace("\\", "/")] = status
        if "R" in status or "C" in status:
            if index < len(records) and records[index]:
                paths[records[index].replace("\\", "/")] = status
                index += 1
    return paths


def pending_planning_paths() -> set[str]:
    """Validated planner writes awaiting the next story publication commit."""
    try:
        import json
        value = json.loads(PENDING_COMMIT_PATHS_FILE.read_text(encoding="utf-8"))
        paths = value.get("paths", []) if isinstance(value, dict) else []
        return {str(path) for path in paths if isinstance(path, str)}
    except (OSError, ValueError, AttributeError):
        return set()


def clear_pending_planning_paths() -> None:
    PENDING_COMMIT_PATHS_FILE.unlink(missing_ok=True)


def unpushed_commit_count(branch: str) -> int | None:
    """Commits on `branch` that origin does not have, or None if unknown."""

    result = run_git(
        "rev-list", "--count", f"origin/{branch}..{branch}", check=False
    )

    if result.returncode != 0:
        # No remote-tracking ref yet (a branch never pushed).
        return None

    try:
        return int(result.stdout.strip())
    except ValueError:
        return None


def commit_exists_with_subject(prefix: str) -> bool | None:
    """Whether this branch's history holds a commit whose subject starts with
    `prefix`, or None when git cannot answer (no repository, no commits yet).

    The orchestrator's one commit message per story ("implemented <STORY-ID>
    ...", see core/orchestrator.py's _story_commit_message) makes this the
    repository's own record of whether a story's completion was ever
    published -- the one fact a model cannot overwrite by editing a
    '## Status' line.
    """

    result = run_git("log", "--format=%s", check=False)

    if result.returncode != 0:
        return None

    return any(
        line.startswith(prefix) for line in result.stdout.splitlines()
    )


def ci_verification_available() -> tuple[bool, str]:
    """Whether a pushed commit can actually be verified by GitHub CI."""

    if CI_VERIFICATION_ENABLED is False:
        return False, "AGENT_CI_VERIFICATION is turned off for this run"

    slug = remote_slug()

    if slug is None:
        return False, "no GitHub 'origin' remote is configured"

    if not CI_WORKFLOW_FILE.exists():
        return False, f"{CI_WORKFLOW_FILE.name} does not exist in this checkout"

    if current_branch() is None:
        return False, "HEAD is detached, so there is no branch to push"

    return True, f"{slug} via {CI_WORKFLOW_FILE.name}"


# ============================================================
# The one write path
# ============================================================

def push_branch(branch: str) -> subprocess.CompletedProcess:
    """Publish `branch` to origin. Never forced, never a different branch.

    Isolated as its own function so the test package can neutralize exactly
    this call (tests/__init__.py), the same way it neutralizes the model
    runners: a unit test must never be able to publish to the real remote.
    """

    return run_git("push", "origin", f"{branch}:{branch}", check=False)


def commit_and_push(message: str, paths=None) -> dict:
    """Commit only the validated story/planning scope and publish it.

    Returns a record of what happened, never raising for the ordinary
    failure modes (nothing to commit, rejected push, no remote): the
    orchestrator turns those into an unverified outcome a human can act on,
    and a story's finished work is never lost because a push failed.
    """

    branch = current_branch()

    if branch is None:
        return {"status": "UNAVAILABLE", "reason": "HEAD is detached; refusing to push",
                "committed": False, "pushed": False, "sha": head_sha(), "branch": None}

    if paths is None:
        # Kept for standalone callers. The orchestrator always supplies a
        # scope assembled from validated planner writes and observed coding
        # changes, so unrelated dirty files are never swept into a story.
        scoped_paths = working_tree_paths()
    else:
        scoped_paths = set(paths)
    for path in scoped_paths:
        parsed = PurePosixPath(str(path).replace("\\", "/"))
        if parsed.is_absolute() or ".." in parsed.parts:
            return {"status": "UNAVAILABLE", "committed": False, "pushed": False,
                    "sha": head_sha(), "branch": branch,
                    "reason": f"unsafe publication path: {path!r}"}

    committed = False

    if scoped_paths:
        # Path-limited add stages only the validated publication scope. Never
        # use repository-wide `git add -A`/`--all` in the publication path.
        run_git("add", "--", *sorted(scoped_paths))
        staged = sorted(set(_output("diff", "--cached", "--name-only").splitlines()) & scoped_paths)
    else:
        staged = []

    if staged:

        # `--only` commits exactly the scoped paths; anything else a human
        # has staged stays staged and out of this commit.
        result = run_git("commit", "--only", "-m", message, "--", *staged, check=False)

        if result.returncode != 0 and "nothing to commit" not in (
                result.stdout + result.stderr).lower():
            return {"status": "UNAVAILABLE", "committed": False, "pushed": False,
                    "sha": head_sha(), "branch": branch,
                    "reason": "git commit failed: "
                              + (result.stderr or result.stdout).strip()[:500]}

        committed = result.returncode == 0

    sha = head_sha()
    ahead = unpushed_commit_count(branch)
    push_result = None

    if ahead is None or ahead > 0:
        push_result = push_branch(branch)

        if push_result.returncode != 0:
            return {"status": "PUSH_REJECTED", "committed": committed, "pushed": False,
                    "sha": sha, "branch": branch,
                    "reason": (push_result.stderr or push_result.stdout).strip()[:500]}

    return {
        "status": "PUSHED" if push_result is not None else "UP_TO_DATE",
        "committed": committed,
        "pushed": push_result is not None,
        "sha": sha,
        "branch": branch,
        "changed_files": len(staged),
        "changed_paths": sorted(staged),
        "reason": "",
    }
