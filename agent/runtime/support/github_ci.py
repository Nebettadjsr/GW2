"""Read one commit's GitHub Actions result, and nothing more.

The full regression suite runs in CI (.github/workflows/ci.yml); this module is
how the orchestrator finds out whether it passed for the commit it just pushed,
and -- when it did not -- builds a short failure report to hand back to Claude.

Two properties matter more than convenience here:

* **Bounded cost.** A failed pipeline must reach Claude as a handful of failing
  test names and their messages, never as CI logs. The workflow publishes those
  through failure annotations; this module reads them and truncates what is
  left. Successful output is never fetched at all.
* **Bounded waiting.** Polling is slow (CI_POLL_SECONDS), throttled in its
  terminal output, and has both a "did a run ever start" deadline and a hard
  overall deadline. Every exit is a definite PASSED, FAILED or UNVERIFIED --
  waiting can never become an unbounded loop, and an unreadable API can never
  be mistaken for a pass.

Authentication is optional and only raises the API rate limit: the workflow
result of a public repository is public. A token is read from the environment
and never logged, stored or written into an artifact.
"""
import json
import os
import urllib.error
import urllib.request
from typing import NamedTuple

from agent.runtime.support.config import (
    CI_FAILURE_REPORT_MAX_CHARS,
    CI_MAX_REPORTED_FAILURES,
    CI_POLL_SECONDS,
    CI_RUN_START_TIMEOUT_SECONDS,
    CI_WAIT_TIMEOUT_SECONDS,
)

API_ROOT = "https://api.github.com"

TOKEN_VARIABLES = ("AGENT_GITHUB_TOKEN", "GITHUB_TOKEN", "GH_TOKEN")

# A conclusion that is not a failure: a skipped or neutral job (for example a
# path-filtered one) must not be reported as a red pipeline, and must not be
# reported as a pass either -- `success` and these are the only ways through.
NON_FAILING_CONCLUSIONS = ("success", "skipped", "neutral")

# Transient read errors are normal over a 45-minute wait; only a persistent
# one is an unverifiable outcome.
MAX_CONSECUTIVE_API_ERRORS = 5

# Ceiling for a rate-limit wait. The unauthenticated public budget resets
# hourly; the overall CI deadline still applies on top of this.
MAX_BACKOFF_SECONDS = 15 * 60


def _token() -> str | None:
    for name in TOKEN_VARIABLES:
        value = os.environ.get(name)

        if value and value.strip():
            return value.strip()

    return None


def fetch_json(url: str, timeout: int = 30) -> tuple[int, dict, object]:
    """GET one API URL. Returns (status, headers, parsed body).

    Isolated as the single network entry point so the test package can
    neutralize it wholesale (tests/__init__.py): no unit test may reach
    api.github.com.
    """

    request = urllib.request.Request(url, method="GET")
    request.add_header("Accept", "application/vnd.github+json")
    request.add_header("X-GitHub-Api-Version", "2022-11-28")
    request.add_header("User-Agent", "gw2-tool-orchestrator")

    token = _token()

    if token:
        request.add_header("Authorization", f"Bearer {token}")

    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8", errors="replace")
            return response.status, dict(response.headers), json.loads(body or "{}")
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")

        try:
            parsed = json.loads(body or "{}")
        except ValueError:
            parsed = {"message": body[:200]}

        return error.code, dict(error.headers or {}), parsed


# ============================================================
# Run state
# ============================================================

class ApiError(NamedTuple):
    message: str
    # Set only when the API told us to slow down. A rate limit is not a
    # broken API: it must be waited out (within the overall deadline),
    # not counted towards "this cannot be read".
    retry_after: int | None


def _header(headers: dict, name: str) -> str | None:
    for key, value in headers.items():
        if key.lower() == name:
            return value

    return None


def _rate_limit_backoff(status: int, headers: dict) -> int | None:
    if status not in (403, 429):
        return None

    retry_after = _header(headers, "retry-after")

    if retry_after:
        try:
            return max(1, min(int(retry_after), MAX_BACKOFF_SECONDS))
        except ValueError:
            pass

    if _header(headers, "x-ratelimit-remaining") == "0":
        return MAX_BACKOFF_SECONDS

    return None


def runs_for_commit(slug: str, sha: str) -> tuple[list[dict], ApiError | None]:
    """Workflow runs recorded for exactly this commit."""

    status, headers, body = fetch_json(
        f"{API_ROOT}/repos/{slug}/actions/runs?head_sha={sha}&per_page=20"
    )

    if status != 200:
        message = str(body.get("message", "")) if isinstance(body, dict) else ""

        return [], ApiError(
            f"HTTP {status} from the GitHub API{': ' + message if message else ''}",
            _rate_limit_backoff(status, headers),
        )

    runs = body.get("workflow_runs", []) if isinstance(body, dict) else []

    return [run for run in runs if isinstance(run, dict)], None


def wait_for_commit(
        slug: str,
        sha: str,
        sleep,
        monotonic,
        notify=None,
) -> dict:
    """Block until this commit's CI is decided, or a deadline passes.

    `sleep`, `monotonic` and `notify` are injected so the waiting policy is
    directly testable without real time passing.
    """

    started = monotonic()
    api_errors = 0
    last_seen = 0
    delay = CI_POLL_SECONDS

    while True:
        runs, error = runs_for_commit(slug, sha)
        waited = monotonic() - started
        delay = CI_POLL_SECONDS

        if error:
            if error.retry_after:
                # Rate limited, not broken: wait it out within the deadline
                # instead of declaring the result unreadable.
                delay = error.retry_after
            else:
                api_errors += 1

                if api_errors >= MAX_CONSECUTIVE_API_ERRORS:
                    return _unverified(
                        sha,
                        f"the GitHub API could not be read ({error.message})",
                        runs=[],
                    )
        else:
            api_errors = 0
            last_seen = len(runs)

            if runs and all(run.get("status") == "completed" for run in runs):
                return _decide(slug, sha, runs)

            if not runs and waited >= CI_RUN_START_TIMEOUT_SECONDS:
                return _unverified(
                    sha,
                    "no GitHub Actions run appeared for this commit within "
                    f"{CI_RUN_START_TIMEOUT_SECONDS // 60} minutes",
                    runs=[],
                )

        if waited >= CI_WAIT_TIMEOUT_SECONDS:
            return _unverified(
                sha,
                f"CI did not finish within {CI_WAIT_TIMEOUT_SECONDS // 60} minutes",
                runs=runs,
            )

        if notify is not None:
            notify(
                f"Waiting for GitHub CI on {sha[:7]}: "
                + (f"{last_seen} run(s) in progress" if last_seen else "no run yet")
                + f", waited {int(waited) // 60} min"
            )

        sleep(delay)


def _decide(slug: str, sha: str, runs: list[dict]) -> dict:
    failed = [
        run for run in runs
        if run.get("conclusion") not in NON_FAILING_CONCLUSIONS
    ]

    if not failed:
        return {
            "status": "PASSED",
            "sha": sha,
            "reason": ", ".join(
                f"{run.get('name', 'workflow')}: {run.get('conclusion')}"
                for run in runs
            ),
            "report": "",
            "run_urls": [run.get("html_url", "") for run in runs],
        }

    # A cancelled or timed-out run says nothing about the code, so it is not
    # handed to Claude as a test failure: it needs a human or another run.
    inconclusive = [
        run for run in failed
        if run.get("conclusion") in ("cancelled", "stale", "timed_out", None)
    ]

    if inconclusive and len(inconclusive) == len(failed):
        return _unverified(
            sha,
            "CI ended without a verdict ("
            + ", ".join(
                f"{run.get('name', 'workflow')}: {run.get('conclusion')}"
                for run in inconclusive
            )
            + ")",
            runs=runs,
        )

    details = failure_details(slug, failed)
    return {
        "status": "FAILED",
        "sha": sha,
        "reason": ", ".join(
            f"{run.get('name', 'workflow')}: {run.get('conclusion')}"
            for run in failed
        ),
        "report": failure_report(slug, sha, failed, details=details),
        "failed_jobs": details,
        "run_urls": [run.get("html_url", "") for run in failed],
    }


def _unverified(sha: str, reason: str, runs: list[dict]) -> dict:
    return {
        "status": "UNVERIFIED",
        "sha": sha,
        "reason": reason,
        "report": "",
        "run_urls": [run.get("html_url", "") for run in runs],
    }


# ============================================================
# Concise failure report
#
# Only failing jobs are read, and only their failure annotations -- the
# compact lines .github/scripts/summarize_test_failures.py publishes. A
# passing job's output is never fetched, so a green suite costs nothing in
# Claude's prompt and a red one costs a bounded amount.
# ============================================================

def _failed_jobs(slug: str, run: dict) -> list[dict]:
    run_id = run.get("id")

    if run_id is None:
        return []

    status, _headers, body = fetch_json(
        f"{API_ROOT}/repos/{slug}/actions/runs/{run_id}/jobs?per_page=50"
    )

    if status != 200 or not isinstance(body, dict):
        return []

    return [
        job for job in body.get("jobs", [])
        if isinstance(job, dict)
        and job.get("conclusion") not in NON_FAILING_CONCLUSIONS
    ]


def _annotations(job: dict) -> list[str]:
    check_run_url = job.get("check_run_url")

    if not check_run_url:
        return []

    status, _headers, body = fetch_json(f"{check_run_url}/annotations")

    if status != 200 or not isinstance(body, list):
        return []

    lines = []

    for annotation in body:
        if not isinstance(annotation, dict):
            continue

        if annotation.get("annotation_level") not in ("failure", None):
            continue

        title = (annotation.get("title") or "").strip()
        message = " ".join((annotation.get("message") or "").split())

        if not title and not message:
            continue

        lines.append(f"{title}: {message}" if title else message)

    return lines


def _failed_step_names(job: dict) -> list[str]:
    return [
        step.get("name", "(unnamed step)")
        for step in job.get("steps", []) or []
        if isinstance(step, dict)
        and step.get("conclusion") not in NON_FAILING_CONCLUSIONS
    ]


def failure_details(slug: str, failed_runs: list[dict]) -> list[dict]:
    """Return structured failing jobs and annotations for deterministic triage."""
    result = []
    for run in failed_runs:
        for job in _failed_jobs(slug, run):
            result.append({
                "workflow": run.get("name", "CI"),
                "run_url": run.get("html_url", ""),
                "job": job.get("name", "(unnamed job)"),
                "steps": _failed_step_names(job),
                "annotations": _annotations(job),
            })
    return result


def failure_report(slug: str, sha: str, failed_runs: list[dict],
                   details: list[dict] | None = None) -> str:
    sections = [f"GitHub CI failed for commit {sha[:7]}."]
    reported = 0
    if details is None:
        details = failure_details(slug, failed_runs)

    for run in failed_runs:
        sections.append(
            f"\nWorkflow: {run.get('name', 'CI')} ({run.get('conclusion')})\n"
            f"Run log: {run.get('html_url', '(url unavailable)')}"
        )

        run_details = [item for item in details
                       if item.get("run_url") == run.get("html_url", "")]
        for item in run_details:
            steps = item.get("steps", [])
            sections.append(
                f"\nFailed job: {item.get('job', '(unnamed job)')}"
                + (f" (step: {', '.join(steps)})" if steps else "")
            )

            annotations = item.get("annotations", [])

            if not annotations:
                sections.append(
                    "  (no failure annotation was published; open the run log "
                    "above to see what failed)"
                )
                continue

            for line in annotations:
                if reported >= CI_MAX_REPORTED_FAILURES:
                    sections.append("  [...] further failures omitted; see the run log")
                    break

                sections.append(f"  - {line}")
                reported += 1

    report = "\n".join(sections)

    if len(report) > CI_FAILURE_REPORT_MAX_CHARS:
        report = (
            report[:CI_FAILURE_REPORT_MAX_CHARS].rstrip()
            + "\n[...] report truncated; see the run log above."
        )

    return report
