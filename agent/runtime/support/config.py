import os
import shutil
from pathlib import Path


# ============================================================
# Paths / configuration
# ============================================================

REPO_ROOT = Path(__file__).resolve().parents[3]
AGENT_DIR = REPO_ROOT / "agent"
RUNTIME_DIR = AGENT_DIR / "runtime"
ARTIFACTS_DIR = RUNTIME_DIR / "artifacts"
STORIES_DIR = AGENT_DIR / "stories"

CURRENT_STORY_FILE = AGENT_DIR / "CURRENT_STORY.md"
PROJECT_STATE_FILE = AGENT_DIR / "PROJECT_STATE.md"
CLAUDE_RESULT_FILE = ARTIFACTS_DIR / "CLAUDE_RESULT.md"
BACKLOG_FILE = STORIES_DIR / "BACKLOG.md"
USER_DECISIONS_DIR = AGENT_DIR / "user-decisions"
ARCHIVE_DIR = STORIES_DIR / "archive"
PRODUCT_OWNER_REQUESTS_DIR = AGENT_DIR / "product-owner-requests"

# Architecture questions the planner is not authorized to decide
# (AR-<NUMBER>-<slug>.md). The planner creates them; ARCHITECTURE MODE
# (agent/ARCHITECT_INSTRUCTIONS.md) is the only role that answers them,
# and is invoked only while an unresolved request exists -- never
# periodically. Genuine Product Owner questions still escalate through
# USER_DECISIONS_DIR; this is not a second human-decision mechanism.
ARCHITECT_REQUESTS_DIR = AGENT_DIR / "architect-requests"
ARCHITECT_INSTRUCTIONS_FILE = AGENT_DIR / "ARCHITECT_INSTRUCTIONS.md"
PLANNER_INSTRUCTIONS_FILE = AGENT_DIR / "PLANNER_INSTRUCTIONS.md"
QA_INSTRUCTIONS_FILE = AGENT_DIR / "QA_INSTRUCTIONS.md"

# The role contracts themselves (AGENTS.md's router, each role's own
# instructions, and Claude's). A model run in one role may never
# rewrite the rules that govern it or another role: both Codex guards
# verify these byte-for-byte and roll back a run that touches them.
ROLE_CONTRACT_FILES = (
    REPO_ROOT / "AGENTS.md",
    REPO_ROOT / "CLAUDE.md",
    ARCHITECT_INSTRUCTIONS_FILE,
    PLANNER_INSTRUCTIONS_FILE,
    QA_INSTRUCTIONS_FILE,
)

# Implementation-time human/tooling intervention records (distinct from
# agent/user-decisions/, which is for product/domain/architecture
# decisions only) -- see agent/user-interventions/README.md.
USER_INTERVENTIONS_DIR = AGENT_DIR / "user-interventions"

# Daily append-only operational log (agent/logs/, a sibling of
# agent/stories/ -- deliberately NOT under agent/runtime/artifacts/,
# since artifacts/ is generated-and-gitignored while these logs are a
# committed historical record). One file per calendar day; see
# support/daily_log.py.
LOGS_DIR = AGENT_DIR / "logs"

# docs/ROADMAP.md is intentionally not read by the orchestrator. The
# selector previously received it alongside BACKLOG.md and started
# reasoning about roadmap phases instead of picking a concrete backlog
# entry, which produced a NEXT decision with no story_path. BACKLOG.md
# is the sole authoritative source for story selection.

NEXT_PROMPT_FILE = ARTIFACTS_DIR / "NEXT_PROMPT.md"

# The harness's own position inside a story attempt -- which of ITS steps
# (evaluation, publication, the CI verdict) still owes an answer for a
# Claude run that has already finished. See core/orchestrator.py's
# "Durable attempt state". A story's own '## Status' is Claude's statement
# about the implementation and can never carry this; before this file
# existed, any interruption between "Claude wrote DONE" and "CI passed"
# was indistinguishable from a completed story, so the next run skipped
# the story and selected a new one on top of unevaluated, unpushed work.
# Generated, local and gitignored like every other artifact.
ATTEMPT_STATE_FILE = ARTIFACTS_DIR / "ATTEMPT_STATE.json"

EVALUATOR_RESULT_FILE = ARTIFACTS_DIR / "EVALUATOR_RESULT.json"
PENDING_COMMIT_PATHS_FILE = ARTIFACTS_DIR / "PENDING_COMMIT_PATHS.json"
SELECTOR_RESULT_FILE = ARTIFACTS_DIR / "SELECTOR_RESULT.json"
PLANNING_RESULT_FILE = ARTIFACTS_DIR / "PLANNING_RESULT.json"
ARCHITECT_RESULT_FILE = ARTIFACTS_DIR / "ARCHITECT_RESULT.json"
QA_RESULT_FILE = ARTIFACTS_DIR / "QA_RESULT.json"
QA_STATE_FILE = ARTIFACTS_DIR / "QA_STATE.json"
QA_PLANS_DIR = AGENT_DIR / "qa-plans"
QA_FAILURES_DIR = ARTIFACTS_DIR / "qa-failures"

DOCS_DIR = REPO_ROOT / "docs"
ROADMAP_FILE = DOCS_DIR / "ROADMAP.md"
KNOWN_PROBLEMS_FILE = DOCS_DIR / "KNOWN_PROBLEMS.md"
CURRENT_ARCHITECTURE_FILE = DOCS_DIR / "CURRENT_ARCHITECTURE.md"
TARGET_ARCHITECTURE_FILE = DOCS_DIR / "TARGET_ARCHITECTURE.md"
DOMAIN_SPEC_FILE = DOCS_DIR / "DOMAIN_SPEC.md"

# Architecture Decision Records -- architect-owned
# (agent/ARCHITECT_INSTRUCTIONS.md "Architecture Decision Records").
# A planning run must never add or change one.
ADR_DIR = DOCS_DIR / "architecture" / "decisions"

# Application/domain source. Planning runs must never touch this --
# only story execution (Claude Code running against an active story)
# is allowed to.
SRC_DIR = REPO_ROOT / "src"

# Post-implementation evaluation runs on Codex (see
# evaluation/evaluator.py's "Who evaluates, and why it changed"). It used
# to be a local hermes3:8b through Ollama at
# http://localhost:11434/api/chat, judging the story text against Claude's
# own report; that model was too weak for the judgment and, having no
# access to the repository, could only confirm what the implementer said
# about itself. Codex shares the planner's and architect's capacity budget
# and is invoked through the same runner, in a read-only sandbox.
#
# Model the orchestrator launches Claude Code with (`claude --model`).
# Pinned here so unattended runs do not silently follow whatever the
# interactive CLI default happens to be set to. Change this one value
# to move story execution and Claude planning to a different model.
CLAUDE_MODEL = "claude-opus-5"


MAX_RETRIES_PER_STORY = 2

CLAUDE_USAGE_LIMIT_PERCENT = 90
# Weekly capacity is independent of the five-hour session allowance.
# A run may start only with more than 50% of the weekly allowance remaining.
CLAUDE_WEEKLY_MIN_REMAINING_PERCENT = 50

# Shared local-recheck cooldown for both models' capacity probes
# (see support/capacity.py's CapacityProbe) -- deliberately not named
# for Claude specifically, since Codex's probe uses this same default.
MODEL_CAPACITY_RECHECK_SECONDS = 60 * 60

# Local waiting cadence while a model is out of capacity. The cooldown
# above governs when a model is actually re-probed; these two only
# control how often the orchestrator wakes up to re-derive permissible
# work, and how often it refreshes the terminal while it waits. Status
# output during a wait is terminal-only (support/daily_log.py's
# print_status) -- never a persistent log entry.
CAPACITY_WAIT_POLL_SECONDS = 60
CAPACITY_STATUS_INTERVAL_SECONDS = 300

# A Claude run that exits non-zero while carrying no capacity signal is a
# failed attempt, not a capacity pause. After this many consecutive such
# runs on the same story the orchestrator escalates to a user
# intervention instead of re-invoking Claude forever.
MAX_CLAUDE_FAILED_RUNS_PER_STORY = 2

# A failed evaluation must not throw away a finished Claude run, so it is
# retried (never by re-invoking Claude) before the cycle is treated as
# failed. These two are deliberately small: every attempt is now a real
# Codex run against the working tree, not a free local HTTP call, so the
# ceiling is EVALUATION_ATTEMPTS * MAX_EVALUATION_BATCHES = 4 evaluation
# runs before the cycle fails and a human is involved. Codex usage
# exhaustion is NOT one of these attempts -- it is a scheduling event the
# orchestrator waits out (CapacityScheduler.wait_for_codex).
EVALUATION_ATTEMPTS = 2
EVALUATION_RETRY_SECONDS = 60

# How many bounded batches of EVALUATION_ATTEMPTS a single cycle rides out
# before the failure propagates to main()'s recoverable-failure handler.
# Evaluation used to be the one step that retried forever while every
# other unrecoverable condition escalated, so an unavailable evaluator
# pinned the orchestrator in a silent loop with a stocked queue and full
# capacity. Giving up is safe now only because ATTEMPT_STATE_FILE records
# that Claude's attempt is already finished: each retried cycle resumes at
# evaluation, never at another Claude run.
MAX_EVALUATION_BATCHES = 2

# One unexpected failure in a single orchestration cycle is retried
# locally rather than killing an unattended run; a failure that keeps
# repeating needs a human and stops the orchestrator explicitly.
MAX_CONSECUTIVE_CYCLE_ERRORS = 3
CYCLE_RETRY_SECONDS = 60

# When a planning pass returns NEEDS_USER and no other current-milestone
# story is independently selectable, the orchestrator waits locally and
# re-checks agent/user-decisions/*.md at this interval -- a plain file
# read, never a model call (Codex/Claude/the evaluator/the planner).
USER_DECISION_POLL_SECONDS = 1800

# The normal backlog trigger is an exhausted executable queue. Changed
# requirements and architecture inputs can trigger planning sooner.
PLANNING_TRIGGER_MAX_READY_STORIES = 0

# A single COMPLETE planning run that creates stories at all must create
# at least this many (never zero speculative filler just to pad a batch --
# 1 is a fully valid, normal batch size) and never more than the upper
# bound below, i.e. never the whole future roadmap in one run. A run that
# needs zero new stories should not be reporting COMPLETE-with-creation in
# the first place -- see PLANNER_INSTRUCTIONS.md.
PLANNING_MIN_STORIES_PER_RUN = 1
PLANNING_MAX_STORIES_PER_RUN = 6

# ============================================================
# GitHub CI verification gate
#
# The full regression suite is owned by .github/workflows/ci.yml, not by
# Claude's local implementation loop (docs/TEST_STRATEGY.md §36). The
# orchestrator commits and pushes a completed story, then waits for that
# commit's CI conclusion before the story counts as verified.
#
# AGENT_CI_VERIFICATION=0 turns the gate off for a run (a story then
# completes on the evaluator's verdict alone, exactly as before this
# existed). Unset means "on when the repository actually has a GitHub
# origin remote", so a clone without one behaves normally instead of
# waiting for a run that can never appear.
# ============================================================

CI_WORKFLOW_FILE = REPO_ROOT / ".github" / "workflows" / "ci.yml"

# One poll per minute: enough to notice a finished run promptly, few
# enough that an unauthenticated public-API budget (60 requests/hour)
# survives a normal verification. Status output while waiting is
# terminal-only and throttled by CAPACITY_STATUS_INTERVAL_SECONDS.
CI_POLL_SECONDS = 60

# A push must produce a queued run quickly; if none appears the gate
# reports an unverified outcome instead of waiting out the full budget.
CI_RUN_START_TIMEOUT_SECONDS = 10 * 60

# Hard ceiling on one commit's verification. Reaching it is an
# unverified outcome (human escalation), never a silent pass.
CI_WAIT_TIMEOUT_SECONDS = 45 * 60

# How many times a CI failure may be handed back to Claude for the same
# story before the orchestrator escalates to a user intervention. This
# is deliberately separate from MAX_RETRIES_PER_STORY: an evaluator
# retry and a red pipeline are different failures.
MAX_CI_FIX_ATTEMPTS = 2

# Bounds on what a failure report may cost in Claude's prompt. The full
# output always remains in the CI job log for a human.
CI_MAX_REPORTED_FAILURES = 12
CI_FAILURE_REPORT_MAX_CHARS = 4000


def _configured_ci_verification() -> bool | None:
    """True/False from AGENT_CI_VERIFICATION, or None for auto-detection."""

    override = os.environ.get("AGENT_CI_VERIFICATION")

    if override is None:
        return None

    return override.strip().lower() not in ("0", "false", "no", "")


# None means "decide from the repository's own remote and workflow file
# at call time" (support/git_sync.py). An explicit override wins.
CI_VERIFICATION_ENABLED = _configured_ci_verification()


# ============================================================
# Aider RepoMap (experimental, orientation-only context for Claude)
#
# Aider is used only as a repository-structure/context generator for
# Claude's implementation prompt -- never as a coding agent, and never
# added to the Codex planner, the Codex evaluator, or the
# deterministic selector. See agent/runtime/support/repo_map.py.
# ============================================================

# Recommended initial budget; see agent/runtime/README.md.
REPO_MAP_TOKEN_BUDGET = 1200


def _default_repo_map_enabled() -> bool:
    # AGENT_REPO_MAP_ENABLED lets a single run be forced on/off (e.g.
    # for an A/B comparison) without editing this file. Unset falls
    # back to "on only if the Aider CLI is actually installed", so a
    # normal checkout without Aider behaves exactly as if this feature
    # did not exist.
    override = os.environ.get("AGENT_REPO_MAP_ENABLED")

    if override is not None:
        return override.strip().lower() not in ("0", "false", "no", "")

    return shutil.which("aider") is not None

REPO_MAP_ENABLED = _default_repo_map_enabled()


# ============================================================
# Document ownership -- one definition, enforced, not just described
#
# Roles write into this repository (IMPLEMENTATION, PROJECT PLANNING,
# ARCHITECTURE, QA, EVALUATION, and the harness itself) and the rules about who may write
# what were previously stated in prose in four separate files -- CLAUDE.md,
# AGENTS.md, agent/PLANNER_INSTRUCTIONS.md and
# agent/ARCHITECT_INSTRUCTIONS.md -- while the actual enforcement lived as
# scattered literals inside project_planner._run_guarded_planner(). Prose
# in four places drifts, and a rule only a prompt states is a rule a model
# can miss.
#
# So the contract is defined once, here:
#
#   * HARNESS_OWNED_PATHS   no model role may write these at all. They are
#                           the harness's own mechanical state, and a model
#                           editing them is how shared state gets corrupted.
#   * DOCUMENT_OWNERSHIP    for every shared document, who may write it and
#                           what belongs in it.
#
# ownership_table() renders this for a prompt, so each role is told the
# current contract from the same definition that is enforced against it;
# protected_paths_for() renders it as the guard's protected set.
#
# Enforcement is deliberately coarse: it rejects a write rather than trying
# to judge whether the content was reasonable. A rejected planning pass
# rolls back (see project_planner.PlanningRejected); a corrupted index is
# never published.
# ============================================================

HARNESS = "harness"
IMPLEMENTATION = "implementation"
PLANNER = "planner"
ARCHITECT = "architect"
QA = "qa"
HUMAN = "human"

# QA has a dedicated model configuration. Planner, Architect, and Evaluator
# keep inheriting the Codex CLI's normal configuration.
QA_MODEL = "gpt-6-luna"
QA_REASONING_EFFORT = "medium"
MAX_QA_ATTEMPTS_PER_STORY = 2
MAX_QA_REVIEW_ATTEMPTS = 1

# Mechanical state. The harness is the only writer, because every one of
# these encodes a transition that must be deterministic: which story is
# active, which pipeline step still owes an answer, and which BACKLOG
# section a story sits in.
HARNESS_OWNED_PATHS = (
    CURRENT_STORY_FILE,
    ATTEMPT_STATE_FILE,
)

# Sections of BACKLOG.md the harness alone moves entries into or out of.
# '## To Do' is shared: the planner appends to it (through
# core/backlog_writer.py), and the harness moves entries out of it.
HARNESS_OWNED_BACKLOG_SECTIONS = ("Active", "Done", "Archived")

DOCUMENT_OWNERSHIP = (
    (CURRENT_STORY_FILE, (HARNESS,),
     "Which story is active. Written only by the activation and completion "
     "transitions."),
    (ATTEMPT_STATE_FILE, (HARNESS,),
     "Which of the harness's own pipeline steps still owes an answer."),
    (BACKLOG_FILE, (HARNESS, PLANNER),
     "Index only: one canonical entry line per story, carrying nothing "
     "beyond what selection and navigation need. The planner appends to "
     "'## To Do' through core/backlog_writer.py; the harness owns every "
     "section transition. No role hand-writes its Markdown."),
    (STORIES_DIR, (HARNESS, PLANNER, IMPLEMENTATION),
     "One canonical file per story, owning its scope, acceptance criteria, "
     "result, blockers and findings. The planner creates them and records "
     "finding dispositions; IMPLEMENTATION updates the active story's "
     "Result/Findings. Only the harness changes Status."),
    (PROJECT_STATE_FILE, (PLANNER,),
     "Planner-only continuity state. IMPLEMENTATION must not write here, "
     "and completed-story history and live test counts never belong here."),
    (ROADMAP_FILE, (PLANNER,),
     "Phases, dependencies and exit criteria."),
    (ADR_DIR, (ARCHITECT,),
     "Architecture decision records. The planner may neither add nor edit "
     "one."),
    (ARCHITECT_REQUESTS_DIR, (ARCHITECT, PLANNER),
     "The planner may create a request; only ARCHITECTURE MODE answers, "
     "edits or resolves one."),
    (USER_DECISIONS_DIR, (PLANNER, HUMAN, HARNESS),
     "The planner may open a decision; only the human resolves it."),
    (QA_PLANS_DIR, (QA, HARNESS),
     "Persistent QA plans; QA prepares them and the harness records validated results."),
    (QA_INSTRUCTIONS_FILE, (HUMAN,),
     "Authoritative QA role contract; no model role may rewrite it."),
    (USER_INTERVENTIONS_DIR, (HARNESS, HUMAN),
     "The harness records an intervention; only the human resolves it."),
    (PRODUCT_OWNER_REQUESTS_DIR, (HUMAN, PLANNER),
     "The human writes requests; the planner records their resolution and "
     "never deletes a request file."),
    (ARTIFACTS_DIR, (HARNESS, IMPLEMENTATION),
     "Generated run artifacts. IMPLEMENTATION writes CLAUDE_RESULT.md; "
     "everything else here belongs to the harness. Never a source of truth "
     "about a story."),
    (LOGS_DIR, (HARNESS,),
     "Append-only operational log."),
)

# No role rewrites the contract that governs it.
ROLE_CONTRACT_OWNERSHIP = tuple(
    (path, (HUMAN,), "Role contract: no model role may rewrite it.")
    for path in ROLE_CONTRACT_FILES
)


def protected_paths_for(role: str) -> tuple:
    """Paths `role` must not modify, derived from DOCUMENT_OWNERSHIP."""

    protected = []

    for path, owners, _ in DOCUMENT_OWNERSHIP + ROLE_CONTRACT_OWNERSHIP:
        if role not in owners:
            protected.append(path)

    return tuple(protected)


def ownership_table(role: str | None = None) -> str:
    """The ownership contract as prompt text, from the enforced definition.

    With `role`, each line says whether that role may write the document, so
    a prompt never has to restate the table in its own words.
    """

    lines = []

    for path, owners, note in DOCUMENT_OWNERSHIP + ROLE_CONTRACT_OWNERSHIP:
        try:
            shown = path.relative_to(REPO_ROOT).as_posix()
        except ValueError:
            shown = str(path)

        if path.is_dir() or shown.endswith(("stories", "decisions", "requests",
                                            "interventions", "artifacts", "logs")):
            shown += "/"

        verdict = ""

        if role is not None:
            verdict = " [you may write]" if role in owners else " [READ-ONLY for you]"

        lines.append(
            f"- {shown}{verdict}\n"
            f"  owners: {', '.join(owners)}\n"
            f"  {note}"
        )

    return "\n".join(lines)
