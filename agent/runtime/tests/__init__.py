"""Runtime test package.

**No test may start a real model process, publish a commit, or call the
GitHub API.** Importing this package installs a process-wide guard that
makes the three executable lookups (`claude`, `codex` for
planning/architecture, `codex` for the capacity probe), the two git write
paths and the GitHub API entry point raise instead of doing anything real.
A test that means to exercise one of those paths patches it at its own
level, as the existing suites already do, and the guard is transparent to
it.

This exists because a convention is not enough: a scheduling test whose
fixture forgot one patch once reached the live orchestrator path and
spent real Codex capacity on a real architect pass. The cost of that
mistake is not a failing test, it is model usage and unintended writes
to the repository, so it is prevented structurally here rather than
trusted to every future fixture.

Each override is looked up through its module at call time, so
replacing the module attribute is enough to stop the spawn: Codex's
process runner resolves `run_codex`, `run_local_planner`/`run_architect`
resolve it in turn, and both `find_claude` and `find_codex` are resolved
by their callers on every use.
"""

import tempfile
from pathlib import Path

from agent.runtime.core import orchestrator
from agent.runtime.runners import claude_runner, codex_capacity, local_planner_runner
from agent.runtime.support import config, daily_log, git_sync, github_ci


def _refuse(name, what):
    def guard(*args, **kwargs):
        raise AssertionError(
            f"A test tried to {what} via {name}(). "
            "Patch it in the test instead."
        )

    return guard


def _refuse_model(name):
    return _refuse(
        name,
        "start a real model process (tests never consume real Claude or "
        "Codex capacity)",
    )


claude_runner.find_claude = _refuse_model("claude_runner.find_claude")
local_planner_runner.find_codex = _refuse_model("local_planner_runner.find_codex")

# Kept reachable under this name for the one suite that drives the real
# process runner against a fake Popen (test_pipeline_recovery.py), the same
# way REAL_COMMIT_AND_PUSH below serves test_ci_verification.py: the guard
# removes the accident, not the coverage.
REAL_RUN_CODEX = local_planner_runner.run_codex

local_planner_runner.run_codex = _refuse_model("local_planner_runner.run_codex")
codex_capacity.find_codex = _refuse_model("codex_capacity.find_codex")

# The CI gate writes to the real repository and the real remote. Its own
# paths resolve through support/config.py, so a fixture that patches
# orchestrator.REPO_ROOT does NOT redirect them -- an unguarded test
# reaching the completion path would commit and push this repository for
# real. The gate is therefore reported unavailable to every test by
# default (story completion then behaves exactly as it did before the gate
# existed), and the two write paths plus the GitHub API entry point raise
# on top of that. test_ci_verification.py patches these itself.
#
# The unguarded implementations stay reachable under these names for the
# one suite that exercises them against a throwaway repository with a
# local bare remote (test_ci_verification.py) -- the guard removes the
# accident, not the coverage.
REAL_COMMIT_AND_PUSH = git_sync.commit_and_push
REAL_PUSH_BRANCH = git_sync.push_branch

git_sync.ci_verification_available = lambda: (
    False, "CI verification is disabled inside the test package"
)
git_sync.commit_and_push = _refuse(
    "git_sync.commit_and_push", "commit and push the real repository"
)
git_sync.push_branch = _refuse(
    "git_sync.push_branch", "push to the real remote"
)
github_ci.fetch_json = _refuse(
    "github_ci.fetch_json", "call the real GitHub API"
)

# agent/logs/ is a committed historical record of what the orchestrator
# actually did. Only start_console_logging() was kept out of the tests'
# reach; log_line() was not, so every suite that exercised a code path
# containing one -- without patching that module's own log_line -- appended
# invented orchestration lines to the real current day's file. The
# 2026-09-27 log carries a block of them ("Evaluator timeout ... attempt
# 1/2" against EVALUATION_ATTEMPTS=3, "Planning resumed for changed
# inputs", "Decision: wait") written by a test run in the middle of a real
# story, which is exactly the kind of noise that makes an incident log
# unusable as evidence.
#
# Guarding the one writer both entry points share keeps every existing
# `patch.object(module, "log_line")` assertion working. Writes are dropped
# only while they would land in the committed directory, so
# test_daily_log.py -- which redirects LOGS_DIR to a temporary one and is
# the suite that actually tests this module -- still exercises the real
# implementation.
REAL_LOG_WRITE = daily_log._write
COMMITTED_LOGS_DIR = daily_log.LOGS_DIR


def _write_unless_committed(message: str) -> None:
    if daily_log.LOGS_DIR == COMMITTED_LOGS_DIR:
        return

    REAL_LOG_WRITE(message)


daily_log._write = _write_unless_committed

# ATTEMPT_STATE.json is the live position of a real story attempt -- which of
# the harness's own steps still owes an answer. Unlike the other artifacts it
# is not regenerable: deleting it is precisely the failure it exists to
# prevent, because the next run then reads a finished-but-unevaluated story as
# complete and selects new work on top of it.
#
# Every terminal path in execute_active_story() clears it, and the existing
# suites drive those paths against a temp repository while this name still
# resolved through support/config.py -- so `python -m unittest` silently
# deleted the real file, exactly as the "patching one module's paths leaves
# its siblings reading the real repository" lesson in tasks/lessons.md
# describes. Redirected for the whole package; a test that cares about the
# contents patches orchestrator.ATTEMPT_STATE_FILE itself
# (test_pipeline_recovery.py).
TEST_ATTEMPT_STATE_FILE = (
    Path(tempfile.gettempdir()) / "gw2-agent-tests" / "ATTEMPT_STATE.json"
)
TEST_ATTEMPT_STATE_FILE.parent.mkdir(parents=True, exist_ok=True)

orchestrator.ATTEMPT_STATE_FILE = TEST_ATTEMPT_STATE_FILE
config.ATTEMPT_STATE_FILE = TEST_ATTEMPT_STATE_FILE
