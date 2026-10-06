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
import json
import subprocess
import tempfile
import unittest
from contextlib import ExitStack
from pathlib import Path
from unittest.mock import patch

from agent.runtime.core import orchestrator
from agent.runtime.human import user_interventions
from agent.runtime.runners import claude_runner
from agent.runtime.support import git_sync, github_ci
from agent.runtime.tests import REAL_COMMIT_AND_PUSH, REAL_PUSH_BRANCH
from agent.runtime.tests.test_orchestrator import (
    OrchestratorInterventionTestCase,
    story_with_id,
)


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


# ============================================================
# Orchestrator: what a red pipeline does to a story
# ============================================================

class StoryCompletionGateTest(OrchestratorInterventionTestCase):

    def setUp(self):
        super().setUp()
        self.filename = "STORY-DOM-042-fees.md"
        self.story = self.write_story(
            self.filename,
            story_with_id("STORY-DOM-042", "## Status\n\nTODO\n")
            + "\n## Title\n\nShared sale fee calculation\n"
            + "\n## Result\n\nNot started.\n",
        )
        self.write_backlog(active=[self.filename])
        self.set_active(self.filename)

        self.result_file = self.stories_dir / "CLAUDE_RESULT.md"
        self.result_file.write_text("Implemented.", encoding="utf-8")

        self.prompts = []
        self.pushed = []

    def execute(self, verifications, evaluate=None):
        """Run the story loop with scripted CI verdicts."""

        verdicts = iter(verifications)
        outcomes = iter([{"decision": "COMPLETE", "reason": "ok"}] * 9)

        def claude(prompt):
            self.prompts.append(prompt)
            return claude_runner.ClaudeAttempt(0, False)

        def commit_and_push(message, paths=None):
            self.pushed.append(message)
            return {"status": "PUSHED", "committed": True, "pushed": True,
                    "sha": f"sha{len(self.pushed)}0000000", "branch": "master",
                    "changed_files": 3, "reason": ""}

        def wait_for_commit(slug, sha, **kwargs):
            return dict(next(verdicts), sha=sha)

        with ExitStack() as stack:
            for name, value in [("CLAUDE_RESULT_FILE", self.result_file),
                                ("NEXT_PROMPT_FILE", self.stories_dir / "prompt.md"),
                                ("MAX_CI_FIX_ATTEMPTS", 2)]:
                stack.enter_context(patch.object(orchestrator, name, value))

            stack.enter_context(patch.object(orchestrator, "build_claude_prompt",
                                             return_value="Implement it"))
            stack.enter_context(patch.object(orchestrator, "generate_repo_map",
                                             return_value={
                                                 "enabled": False, "available": False,
                                                 "text": "", "token_budget": 0,
                                                 "char_count": 0, "approx_tokens": 0,
                                                 "duration_seconds": 0.0, "error": None,
                                             }))
            stack.enter_context(patch.object(orchestrator, "_safe_claude_usage",
                                             return_value=claude_runner.ClaudeUsage(10, 0)))
            stack.enter_context(patch.object(orchestrator, "run_claude_attempt",
                                             side_effect=claude))
            stack.enter_context(patch.object(
                orchestrator, "evaluate_story",
                side_effect=evaluate or (lambda *a, **_kwargs: next(outcomes)),
            ))
            stack.enter_context(patch.object(orchestrator.git_sync,
                                             "ci_verification_available",
                                             return_value=(True, "o/r via ci.yml")))
            stack.enter_context(patch.object(orchestrator.git_sync, "commit_and_push",
                                             side_effect=commit_and_push))
            stack.enter_context(patch.object(orchestrator.git_sync, "remote_slug",
                                             return_value="o/r"))
            stack.enter_context(patch.object(orchestrator.github_ci, "wait_for_commit",
                                             side_effect=wait_for_commit))

            return orchestrator.execute_active_story()

    @staticmethod
    def verdict(status, report="", reason="scripted"):
        return {"status": status, "report": report, "reason": reason, "run_urls": []}

    def test_green_ci_completes_the_story_after_exactly_one_push(self):
        outcome = self.execute([self.verdict("PASSED")])

        self.assertEqual(outcome, "COMPLETE")
        self.assertEqual(self.pushed, ["implemented STORY-DOM-042: Shared sale fee calculation"])
        self.assertEqual(len(self.prompts), 1)

    def test_red_ci_returns_the_failing_tests_to_claude_and_retries(self):
        report = ("GitHub CI failed for commit sha1000.\n"
                  "  - craft.CraftVsBuyTest.prefersBuy: expected <100> but was <120>")

        outcome = self.execute([self.verdict("FAILED", report), self.verdict("PASSED")])

        self.assertEqual(outcome, "COMPLETE")
        self.assertEqual(len(self.prompts), 2)
        self.assertEqual(len(self.pushed), 2)

        retry = self.prompts[1]
        self.assertIn("craft.CraftVsBuyTest.prefersBuy", retry)
        self.assertIn("narrowest command", retry)
        self.assertIn("never the full suite", retry)
        self.assertIn(self.filename, retry)
        self.assertNotIn("Implement it", retry)
        self.assertFalse(self.interventions_dir.exists())

    def test_known_failure_in_unrelated_suite_holds_ci_without_reinvoking_claude(self):
        outcome = self.execute([dict(self.verdict("FAILED", "Agent runtime tests: Python runtime failed"),
            failed_jobs=[{"job": "Agent runtime tests (pytest-cov)",
                          "steps": ["Run orchestrator/harness tests"]}])])

        self.assertEqual(outcome, "NEEDS_USER")
        self.assertEqual(len(self.prompts), 1)
        state = json.loads(orchestrator.ATTEMPT_STATE_FILE.read_text(encoding="utf-8"))
        self.assertEqual(state["phase"], "AWAITING_CI")
        self.assertEqual(state["story"], f"stories/{self.filename}")
        interventions = user_interventions.list_interventions()
        self.assertEqual(len(interventions), 1)
        text = (self.interventions_dir / interventions[0]["file"]).read_text(encoding="utf-8")
        self.assertIn("does not overlap", text)
        self.assertIn("Agent runtime tests", text)

    def test_unrecognized_ci_failure_is_conservatively_attributed_to_story(self):
        self.assertFalse(orchestrator.ci_failures_are_outside_story_scope(
            {"failed_jobs": [{"job": "New unknown job"}]}, ["frontend/src/App.vue"]
        ))
        self.assertFalse(orchestrator.ci_failures_are_outside_story_scope(
            {"failed_jobs": [{"job": "Agent runtime tests (pytest-cov)"}]},
            ["agent/stories/BACKLOG.md"],
        ))

    def test_a_story_whose_ci_fails_is_not_left_claiming_to_be_done(self):
        recorded = []

        def evaluate(*args, **_kwargs):
            recorded.append(orchestrator.extract_status_section(
                self.story.read_text(encoding="utf-8")))
            return {"decision": "COMPLETE", "reason": "ok"}

        self.execute([self.verdict("FAILED", "  - a.B.c: boom"),
                      self.verdict("PASSED")],
                     evaluate=evaluate)

        # The second attempt starts from UNFINISHED, not from a DONE story
        # that CI has already contradicted.
        self.assertEqual(recorded[1], "UNFINISHED")

    def test_repeated_ci_failure_escalates_to_a_human_with_the_report(self):
        outcome = self.execute([self.verdict("FAILED", "  - a.B.c: boom")] * 3)

        self.assertEqual(outcome, "NEEDS_USER")
        # One initial attempt plus MAX_CI_FIX_ATTEMPTS fixes.
        self.assertEqual(len(self.prompts), 3)

        interventions = user_interventions.list_interventions()
        self.assertEqual(len(interventions), 1)

        recorded = (self.interventions_dir / interventions[0]["file"]).read_text(
            encoding="utf-8")
        self.assertIn("still fails after 2", recorded)
        self.assertIn("a.B.c: boom", recorded)
        self.assertEqual(
            orchestrator.classify_story_status(
                orchestrator.extract_status_section(
                    self.story.read_text(encoding="utf-8"))),
            "BLOCKED",
        )

    def test_an_unverifiable_pipeline_asks_a_human_instead_of_completing(self):
        outcome = self.execute([self.verdict(
            "UNVERIFIED", reason="no GitHub Actions run appeared for this commit")])

        self.assertEqual(outcome, "NEEDS_USER")
        # Nothing concrete to fix, so Claude is never re-invoked for it.
        self.assertEqual(len(self.prompts), 1)

        interventions = user_interventions.list_interventions()
        recorded = (self.interventions_dir / interventions[0]["file"]).read_text(
            encoding="utf-8")
        self.assertIn("could not be established", recorded)
        self.assertIn("no GitHub Actions run appeared", recorded)


class GateUnavailableTest(OrchestratorInterventionTestCase):

    def test_without_a_gate_the_story_completes_exactly_as_before(self):
        story_path = self.write_story(
            "STORY-DOM-043-x.md",
            story_with_id("STORY-DOM-043", "## Status\n\nTODO\n"),
        )

        with patch.object(orchestrator.git_sync, "ci_verification_available",
                          return_value=(False, "no GitHub 'origin' remote is configured")), \
             patch.object(orchestrator.git_sync, "commit_and_push") as commit:
            verification = orchestrator.verify_with_github_ci(
                story_path, story_path.read_text(encoding="utf-8"))

        self.assertEqual(verification["status"], "SKIPPED")
        commit.assert_not_called()
        self.assertTrue(any("CI verification skipped" in line for line in self.logged))

    def test_a_push_that_cannot_happen_is_unverified_not_complete(self):
        story_path = self.write_story(
            "STORY-DOM-044-x.md",
            story_with_id("STORY-DOM-044", "## Status\n\nTODO\n"),
        )

        with patch.object(orchestrator.git_sync, "ci_verification_available",
                          return_value=(True, "o/r via ci.yml")), \
             patch.object(orchestrator.git_sync, "commit_and_push",
                          return_value={"status": "PUSH_REJECTED", "committed": True,
                                        "pushed": False, "sha": "abc", "branch": "master",
                                        "reason": "! [rejected] fetch first"}), \
             patch.object(orchestrator.github_ci, "wait_for_commit") as wait:
            verification = orchestrator.verify_with_github_ci(
                story_path, story_path.read_text(encoding="utf-8"))

        self.assertEqual(verification["status"], "UNVERIFIED")
        self.assertIn("rejected", verification["reason"])
        wait.assert_not_called()


if __name__ == "__main__":
    unittest.main()
