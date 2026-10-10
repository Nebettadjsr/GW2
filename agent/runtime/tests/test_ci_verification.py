"""The GitHub CI gate: publish a finished story, then trust only CI's verdict.

Three things are pinned here, because all three are ways the gate could quietly
stop being a gate:

* a story never completes on an unproven pipeline -- PASSED completes, FAILED
  goes back to Claude, and anything else asks a human;
* waiting is bounded in both time and output -- no busy loop, no unbounded
  polling, no log dumping;
* what returns to Claude is a short list of failing tests, never CI logs and
  never successful output.

Nothing here touches the real repository, the real remote or the real GitHub
API: the package guard in tests/__init__.py makes those raise, and every test
supplies its own fake in their place.

Run with: python -m unittest agent.runtime.tests.test_ci_verification -v
"""
import subprocess
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch

from agent.runtime.support import git_sync, github_ci
from agent.runtime.tests import REAL_COMMIT_AND_PUSH, REAL_PUSH_BRANCH


# ============================================================
# git_sync: the one write path
# ============================================================

class RemoteSlugTest(unittest.TestCase):

    def test_both_github_url_forms_resolve_to_owner_and_repository(self):
        for url in ("https://github.com/Nebettadjsr/GW2.git",
                    "https://github.com/Nebettadjsr/GW2",
                    "git@github.com:Nebettadjsr/GW2.git",
                    "ssh://git@github.com/Nebettadjsr/GW2.git"):
            with patch.object(git_sync, "run_git", return_value=subprocess.CompletedProcess(
                    [], 0, stdout=url + "\n", stderr="")):
                self.assertEqual(git_sync.remote_slug(), "Nebettadjsr/GW2", url)

    def test_a_non_github_or_missing_remote_means_no_ci_gate(self):
        with patch.object(git_sync, "run_git", return_value=subprocess.CompletedProcess(
                [], 0, stdout="https://gitlab.com/someone/thing.git\n", stderr="")):
            self.assertIsNone(git_sync.remote_slug())

        with patch.object(git_sync, "run_git", return_value=subprocess.CompletedProcess(
                [], 2, stdout="", stderr="error: No such remote 'origin'")):
            self.assertIsNone(git_sync.remote_slug())


class PushBranchTest(unittest.TestCase):

    def test_push_is_never_forced_and_never_touches_another_branch(self):
        with patch.object(git_sync, "run_git") as run_git:
            REAL_PUSH_BRANCH("master")

        run_git.assert_called_once_with(
            "push", "origin", "master:master", check=False
        )


class CommitAndPushTest(unittest.TestCase):
    """Against a real throwaway repository with a real local bare remote."""

    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name).resolve()

        self.repo = root / "work"
        self.remote = root / "origin.git"
        self.repo.mkdir()

        self.git("init", "--initial-branch=master", cwd=self.repo)
        self.git("config", "user.email", "test@example.invalid", cwd=self.repo)
        self.git("config", "user.name", "Test", cwd=self.repo)
        self.git("init", "--bare", str(self.remote), cwd=root)
        self.git("remote", "add", "origin", str(self.remote), cwd=self.repo)

        (self.repo / ".gitignore").write_text(".env\n", encoding="utf-8")
        (self.repo / "seed.txt").write_text("seed\n", encoding="utf-8")
        self.git("add", "--all", cwd=self.repo)
        self.git("commit", "-m", "seed", cwd=self.repo)

        self.pushes = []
        stack = ExitStack()
        self.addCleanup(stack.close)
        stack.enter_context(patch.object(git_sync, "REPO_ROOT", self.repo))
        # The package guard replaces the real push. This fake records the
        # call and performs the same publish against the local bare remote,
        # so the follow-up "already pushed" state is real, not asserted.
        stack.enter_context(patch.object(
            git_sync, "push_branch", side_effect=self.fake_push
        ))

    def git(self, *arguments, cwd):
        subprocess.run(["git", *arguments], cwd=cwd, check=True,
                       capture_output=True, text=True)

    def fake_push(self, branch):
        self.pushes.append(branch)
        return REAL_PUSH_BRANCH(branch)

    def test_dirty_tree_is_committed_once_and_published(self):
        (self.repo / "src.txt").write_text("work\n", encoding="utf-8")

        result = REAL_COMMIT_AND_PUSH("implemented STORY-X: a thing")

        self.assertEqual(result["status"], "PUSHED")
        self.assertTrue(result["committed"])
        self.assertEqual(self.pushes, ["master"])
        self.assertEqual(result["sha"], git_sync.head_sha())
        self.assertEqual(
            git_sync.run_git("log", "-1", "--pretty=%s").stdout.strip(),
            "implemented STORY-X: a thing",
        )

    def test_scoped_publication_leaves_unrelated_dirty_files_uncommitted(self):
        (self.repo / "frontend-change.ts").write_text("story change\n", encoding="utf-8")
        unrelated = self.repo / "agent-runtime-change.py"
        unrelated.write_text("unrelated pre-existing change\n", encoding="utf-8")

        result = REAL_COMMIT_AND_PUSH(
            "implemented STORY-X: a thing", paths=["frontend-change.ts"]
        )

        self.assertEqual(result["status"], "PUSHED")
        self.assertEqual(result["changed_paths"], ["frontend-change.ts"])
        committed = git_sync.run_git("show", "--name-only", "--pretty=", "HEAD").stdout
        self.assertIn("frontend-change.ts", committed)
        self.assertNotIn("agent-runtime-change.py", committed)
        self.assertTrue(unrelated.exists())
        self.assertIn("agent-runtime-change.py", git_sync.working_tree_paths())

    def test_unrelated_staged_changes_neither_block_nor_join_the_scoped_commit(self):
        # Regression: a maintainer's staged deletion made finalization refuse
        # to publish, stranding a CI-passed story as finalized only locally.
        (self.repo / "story.md").write_text("DONE\n", encoding="utf-8")
        self.git("rm", "-q", "seed.txt", cwd=self.repo)

        result = REAL_COMMIT_AND_PUSH("finalized STORY-X", paths=["story.md"])

        self.assertEqual((result["status"], result["changed_paths"]), ("PUSHED", ["story.md"]))
        committed = git_sync.run_git("show", "--name-only", "--pretty=", "HEAD").stdout.split()
        self.assertEqual(committed, ["story.md"])
        staged = git_sync.run_git("diff", "--cached", "--name-only").stdout.split()
        self.assertEqual(staged, ["seed.txt"])

    def test_ignored_files_are_never_swept_into_the_commit(self):
        (self.repo / ".env").write_text("DATABASE_PASSWORD=secret\n", encoding="utf-8")
        (self.repo / "src.txt").write_text("work\n", encoding="utf-8")

        REAL_COMMIT_AND_PUSH("implemented STORY-X: a thing")

        committed = git_sync.run_git("show", "--name-only", "--pretty=", "HEAD").stdout

        self.assertIn("src.txt", committed)
        self.assertNotIn(".env", committed)

    def test_an_existing_unpushed_commit_is_published_without_a_new_commit(self):
        (self.repo / "src.txt").write_text("work\n", encoding="utf-8")
        self.git("add", "--all", cwd=self.repo)
        self.git("commit", "-m", "already committed by hand", cwd=self.repo)

        result = REAL_COMMIT_AND_PUSH("implemented STORY-X: a thing")

        self.assertEqual(result["status"], "PUSHED")
        self.assertFalse(result["committed"])
        self.assertEqual(
            git_sync.run_git("log", "-1", "--pretty=%s").stdout.strip(),
            "already committed by hand",
        )

    def test_nothing_to_commit_and_nothing_to_push_makes_no_commit_at_all(self):
        (self.repo / "src.txt").write_text("work\n", encoding="utf-8")
        REAL_COMMIT_AND_PUSH("implemented STORY-X: a thing")
        self.pushes.clear()

        result = REAL_COMMIT_AND_PUSH("implemented STORY-X: a thing")

        self.assertEqual(result["status"], "UP_TO_DATE")
        self.assertFalse(result["committed"])
        self.assertEqual(self.pushes, [])

    def test_a_rejected_push_is_reported_not_raised_and_never_forced(self):
        (self.repo / "src.txt").write_text("work\n", encoding="utf-8")

        with patch.object(git_sync, "push_branch", return_value=subprocess.CompletedProcess(
                [], 1, stdout="", stderr="! [rejected] master -> master (fetch first)")):
            result = REAL_COMMIT_AND_PUSH("implemented STORY-X: a thing")

        self.assertEqual(result["status"], "PUSH_REJECTED")
        self.assertIn("rejected", result["reason"])
        # The work is committed regardless: a failed publish must never
        # discard a finished story.
        self.assertTrue(result["committed"])

    def test_a_detached_head_refuses_to_publish(self):
        self.git("checkout", "--detach", cwd=self.repo)
        (self.repo / "src.txt").write_text("work\n", encoding="utf-8")

        result = REAL_COMMIT_AND_PUSH("implemented STORY-X: a thing")

        self.assertEqual(result["status"], "UNAVAILABLE")
        self.assertEqual(self.pushes, [])


# ============================================================
# github_ci: bounded waiting, bounded reporting
# ============================================================

def run(conclusion=None, status="completed", identifier=1, name="CI"):
    return {"id": identifier, "name": name, "status": status,
            "conclusion": conclusion,
            "html_url": f"https://github.com/o/r/actions/runs/{identifier}"}


class FakeApi:
    """A recorded GitHub API: every call is scripted, nothing is real."""

    def __init__(self, run_pages, jobs=None, annotations=None, errors=None):
        self.run_pages = list(run_pages)
        self.jobs = jobs or {}
        self.annotations = annotations or {}
        self.errors = errors or {}
        self.urls = []

    def __call__(self, url, timeout=30):
        self.urls.append(url)

        for fragment, response in self.errors.items():
            if fragment in url:
                return response

        if "/actions/runs?" in url:
            page = self.run_pages[0] if len(self.run_pages) == 1 else self.run_pages.pop(0)
            return 200, {}, {"workflow_runs": page}

        if url.endswith("/jobs?per_page=50"):
            return 200, {}, {"jobs": self.jobs.get(url, [])}

        if url.endswith("/annotations"):
            return 200, {}, self.annotations.get(url, [])

        raise AssertionError(f"unexpected API call: {url}")


class ApplicationVerdictTest(unittest.TestCase):
    """The story gate: application jobs decide; the agent-runtime job is reported, never blocking."""

    APP_JOBS = ("Backend tests (Maven)", "Frontend tests (Vitest)", "Coverage KPI report")

    def ci_run(self, conclusion="success", path=".github/workflows/ci.yml@refs/heads/master"):
        return {"id": 7, "name": "CI", "path": path, "status": "completed", "conclusion": conclusion,
                "html_url": "https://github.com/o/r/actions/runs/7"}

    def job(self, name, conclusion="success"):
        return {"name": name, "status": "completed", "conclusion": conclusion,
                "steps": [{"name": "Run tests", "conclusion": conclusion}]}

    def verdict(self, runs, jobs, total=None):
        def api(url, timeout=30):
            if "/actions/runs?" in url:
                return 200, {}, {"workflow_runs": runs}
            if url.endswith("/jobs?per_page=100"):
                return 200, {}, {"jobs": jobs, "total_count": len(jobs) if total is None else total}
            raise AssertionError(f"unexpected API call: {url}")

        with patch.object(github_ci, "fetch_json", side_effect=api):
            return github_ci.wait_for_commit("o/r", "abc1234def", sleep=lambda _s: None,
                                             monotonic=lambda: 0.0, application_checks=True)

    def test_agent_runtime_failure_is_reported_but_does_not_fail_the_story(self):
        jobs = [self.job(name) for name in self.APP_JOBS] + [self.job("Agent runtime tests (pytest-cov)", "failure")]
        result = self.verdict([self.ci_run("failure")], jobs)
        self.assertEqual(result["status"], "PASSED")
        self.assertEqual([item["job"] for item in result["ignored_failures"]], ["Agent runtime tests (pytest-cov)"])
        self.assertIn("were excluded", result["reason"])

    def test_a_failed_application_job_fails_the_story_with_its_details(self):
        jobs = [self.job("Backend tests (Maven)", "failure"), self.job("Frontend tests (Vitest)"),
                self.job("Coverage KPI report")]
        result = self.verdict([self.ci_run("failure")], jobs)
        self.assertEqual(result["status"], "FAILED")
        self.assertEqual(result["reason"], "Required application CI check(s) failed: Backend tests (Maven)")
        self.assertEqual([item["job"] for item in result["failed_jobs"]], ["Backend tests (Maven)"])
        self.assertEqual(result["failed_jobs"][0]["steps"], ["Run tests"])

    def test_an_unknown_failing_job_is_never_hidden_behind_the_runtime_exception(self):
        jobs = [self.job(name) for name in self.APP_JOBS] + [self.job("Docker image build", "failure")]
        result = self.verdict([self.ci_run("failure")], jobs)
        self.assertEqual(result["status"], "FAILED")
        self.assertIn("Docker image build", result["reason"])

    def test_a_missing_required_job_cannot_pass(self):
        jobs = [self.job("Backend tests (Maven)"), self.job("Coverage KPI report")]
        result = self.verdict([self.ci_run()], jobs)
        self.assertEqual(result["status"], "UNVERIFIED")
        self.assertIn("frontend tests (vitest)", result["reason"])

    def test_an_incomplete_job_list_cannot_pass(self):
        jobs = [self.job(name) for name in self.APP_JOBS]
        result = self.verdict([self.ci_run()], jobs, total=5)
        self.assertEqual(result["status"], "UNVERIFIED")
        self.assertIn("incomplete job list", result["reason"])

    def test_a_ci_run_from_an_unexpected_workflow_file_cannot_pass(self):
        result = self.verdict([self.ci_run(path=".github/workflows/other.yml")], [])
        self.assertEqual(result["status"], "UNVERIFIED")
        self.assertIn("path could not be verified", result["reason"])

    def test_a_cancelled_ci_run_is_unverified_not_failed(self):
        jobs = [self.job(name) for name in self.APP_JOBS]
        result = self.verdict([self.ci_run("cancelled")], jobs)
        self.assertEqual(result["status"], "UNVERIFIED")

    def test_a_skipped_required_job_is_not_a_pass(self):
        jobs = [self.job("Backend tests (Maven)", "skipped"), self.job("Frontend tests (Vitest)"),
                self.job("Coverage KPI report")]
        result = self.verdict([self.ci_run()], jobs)
        self.assertEqual(result["status"], "UNVERIFIED")


class WaitForCommitTest(unittest.TestCase):

    def setUp(self):
        self.slept = []
        self.said = []
        self.clock = [0.0]

    def monotonic(self):
        return self.clock[0]

    def sleep(self, seconds):
        self.slept.append(seconds)
        self.clock[0] += seconds

    def wait(self, api):
        with patch.object(github_ci, "fetch_json", side_effect=api):
            return github_ci.wait_for_commit(
                "o/r", "abc1234def", sleep=self.sleep,
                monotonic=self.monotonic, notify=self.said.append,
            )

    def test_all_runs_successful_is_the_only_pass(self):
        result = self.wait(FakeApi([[run("success"), run("success", identifier=2)]]))

        self.assertEqual(result["status"], "PASSED")
        self.assertEqual(self.slept, [])

    def test_an_in_progress_run_is_polled_slowly_until_it_finishes(self):
        api = FakeApi([[run(status="in_progress")], [run(status="queued")],
                       [run("success")]])

        result = self.wait(api)

        self.assertEqual(result["status"], "PASSED")
        self.assertEqual(self.slept, [github_ci.CI_POLL_SECONDS] * 2)
        self.assertEqual(len(self.said), 2, self.said)
        self.assertIn("Waiting for GitHub CI on abc1234", self.said[0])

    def test_no_run_ever_appearing_ends_unverified_without_a_busy_loop(self):
        result = self.wait(FakeApi([[]]))

        self.assertEqual(result["status"], "UNVERIFIED")
        self.assertIn("no GitHub Actions run appeared", result["reason"])
        self.assertTrue(all(delay == github_ci.CI_POLL_SECONDS for delay in self.slept))
        self.assertLessEqual(
            len(self.slept),
            github_ci.CI_RUN_START_TIMEOUT_SECONDS // github_ci.CI_POLL_SECONDS,
        )

    def test_a_run_that_never_finishes_stops_at_the_hard_deadline(self):
        result = self.wait(FakeApi([[run(status="in_progress")]]))

        self.assertEqual(result["status"], "UNVERIFIED")
        self.assertIn("did not finish", result["reason"])
        self.assertLessEqual(
            self.clock[0],
            github_ci.CI_WAIT_TIMEOUT_SECONDS + github_ci.CI_POLL_SECONDS,
        )

    def test_a_cancelled_run_is_unverified_rather_than_a_test_failure(self):
        result = self.wait(FakeApi([[run("cancelled")]]))

        self.assertEqual(result["status"], "UNVERIFIED")
        self.assertIn("without a verdict", result["reason"])

    def test_a_skipped_job_does_not_count_as_a_failure(self):
        result = self.wait(FakeApi([[run("success"), run("skipped", identifier=2)]]))

        self.assertEqual(result["status"], "PASSED")

    def test_a_persistent_api_error_is_unverified_never_a_pass(self):
        api = FakeApi([[run("success")]],
                      errors={"/actions/runs?": (404, {}, {"message": "Not Found"})})

        result = self.wait(api)

        self.assertEqual(result["status"], "UNVERIFIED")
        self.assertIn("404", result["reason"])
        self.assertEqual(len(self.slept), github_ci.MAX_CONSECUTIVE_API_ERRORS - 1)

    def test_rate_limiting_backs_off_instead_of_giving_up(self):
        api = FakeApi(
            [[run("success")]],
            errors={"/actions/runs?": (403, {"x-ratelimit-remaining": "0"},
                                       {"message": "API rate limit exceeded"})},
        )

        result = self.wait(api)

        self.assertEqual(result["status"], "UNVERIFIED")
        self.assertIn("did not finish", result["reason"])
        self.assertIn(github_ci.MAX_BACKOFF_SECONDS, self.slept)


class FailureReportTest(unittest.TestCase):

    def api(self, annotations, job_conclusion="failure"):
        jobs_url = "https://api.github.com/repos/o/r/actions/runs/1/jobs?per_page=50"
        check_url = "https://api.github.com/repos/o/r/check-runs/9"

        return FakeApi(
            [[run("failure")]],
            jobs={jobs_url: [
                {"name": "Backend tests (Maven)", "conclusion": job_conclusion,
                 "check_run_url": check_url,
                 "steps": [{"name": "Run backend test suite", "conclusion": "failure"},
                           {"name": "Checkout", "conclusion": "success"}]},
                {"name": "Frontend tests (Vitest)", "conclusion": "success",
                 "check_run_url": "https://api.github.com/repos/o/r/check-runs/10",
                 "steps": []},
            ]},
            annotations={check_url + "/annotations": annotations},
        )

    def report(self, api):
        with patch.object(github_ci, "fetch_json", side_effect=api):
            return github_ci.wait_for_commit(
                "o/r", "abc1234def", sleep=lambda _s: None,
                monotonic=lambda: 0.0,
            )

    def test_report_names_the_failing_tests_and_the_run_log(self):
        api = self.api([
            {"annotation_level": "failure",
             "title": "Backend (Maven): craft.CraftVsBuyTest.prefersBuy",
             "message": "expected: <100> but was: <120>"},
        ])

        result = self.report(api)

        self.assertEqual(result["status"], "FAILED")
        self.assertIn("craft.CraftVsBuyTest.prefersBuy", result["report"])
        self.assertIn("expected: <100> but was: <120>", result["report"])
        self.assertIn("Failed job: Backend tests (Maven)", result["report"])
        self.assertIn("step: Run backend test suite", result["report"])
        self.assertIn("actions/runs/1", result["report"])

    def test_a_successful_job_costs_nothing_in_the_report(self):
        api = self.api([{"annotation_level": "failure", "title": "t", "message": "m"}])

        result = self.report(api)

        self.assertNotIn("Frontend tests (Vitest)", result["report"])
        self.assertNotIn("check-runs/10", " ".join(api.urls))
        # Logs are never downloaded: only runs, the failing run's jobs and
        # that job's annotations are read.
        self.assertFalse([url for url in api.urls if url.endswith("/logs")])

    def test_the_report_is_bounded_in_entries_and_characters(self):
        api = self.api([
            {"annotation_level": "failure", "title": f"suite.Test{index}",
             "message": "assertion failed " * 40}
            for index in range(40)
        ])

        result = self.report(api)

        listed = [line for line in result["report"].splitlines()
                  if line.startswith("  - ")]

        self.assertLessEqual(len(listed), github_ci.CI_MAX_REPORTED_FAILURES)
        self.assertLessEqual(
            len(result["report"]), github_ci.CI_FAILURE_REPORT_MAX_CHARS + 60
        )
        self.assertIn("truncated", result["report"].lower() + " ")

    def test_a_job_without_annotations_points_at_the_log_instead_of_guessing(self):
        result = self.report(self.api([]))

        self.assertEqual(result["status"], "FAILED")
        self.assertIn("no failure annotation", result["report"])
        self.assertIn("actions/runs/1", result["report"])


if __name__ == "__main__":
    unittest.main()
