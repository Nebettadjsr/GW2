"""Single source of truth for the harness boundary.

The harness is everything that steers or checks the agents: workflows and their scripts, the agent
instructions, the quality-gate configuration. The programmer (Claude) and the planner work only on
the GW2 application. They never change harness files; they report proposals instead, and the owner
or a supervisor session decides and changes the harness.

Two lists are kept here and used by both the merge gate (merge_ready_pr.py) and the post-run discard
step (discard_out_of_scope.py):

- OFF_LIMITS: a story run may not change these. Changes are discarded and reported.
- SUPERVISED_ONLY: a story run may change these (a story can legitimately need a new dependency), but
  a PR touching them is never merged automatically.
"""

OFF_LIMITS_FILES = frozenset({
    "AGENTS.md",
    "CLAUDE.md",
    "GW2 GitHub Flow Migration.md",
    "frontend/eslint.config.js",
})

OFF_LIMITS_PREFIXES = (
    ".github/",
    ".claude/",
    "agent/",
    "tasks/",
    "backend/config/",
    "backend/src/test/java/architecture/",
    "backend/src/test/resources/archunit",
)

SUPERVISED_ONLY_FILES = frozenset({
    "backend/pom.xml",
    "frontend/package.json",
    "frontend/package-lock.json",
})


def is_off_limits(path: str) -> bool:
    path = path.replace("\\", "/")
    return path in OFF_LIMITS_FILES or path.startswith(OFF_LIMITS_PREFIXES)


def needs_supervised_merge(path: str) -> bool:
    path = path.replace("\\", "/")
    return is_off_limits(path) or path in SUPERVISED_ONLY_FILES
