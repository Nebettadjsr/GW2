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
EVALUATOR_RESULT_FILE = ARTIFACTS_DIR / "EVALUATOR_RESULT.json"
SELECTOR_RESULT_FILE = ARTIFACTS_DIR / "SELECTOR_RESULT.json"
DISPATCH_RESULT_FILE = ARTIFACTS_DIR / "DISPATCH_RESULT.json"
PLANNING_RESULT_FILE = ARTIFACTS_DIR / "PLANNING_RESULT.json"

DOCS_DIR = REPO_ROOT / "docs"
ROADMAP_FILE = DOCS_DIR / "ROADMAP.md"
KNOWN_PROBLEMS_FILE = DOCS_DIR / "KNOWN_PROBLEMS.md"
CURRENT_ARCHITECTURE_FILE = DOCS_DIR / "CURRENT_ARCHITECTURE.md"
TARGET_ARCHITECTURE_FILE = DOCS_DIR / "TARGET_ARCHITECTURE.md"
DOMAIN_SPEC_FILE = DOCS_DIR / "DOMAIN_SPEC.md"

# Application/domain source. Planning runs must never touch this --
# only story execution (Claude Code running against an active story)
# is allowed to.
SRC_DIR = REPO_ROOT / "src"

OLLAMA_URL = "http://localhost:11434/api/chat"

# Hermes remains responsible for dispatch/evaluation/selection.
MODEL = "hermes3:8b"


MAX_RETRIES_PER_STORY = 2

CLAUDE_USAGE_LIMIT_PERCENT = 90

# Shared local-recheck cooldown for both models' capacity probes
# (see support/capacity.py's CapacityProbe) -- deliberately not named
# for Claude specifically, since Codex's probe uses this same default.
MODEL_CAPACITY_RECHECK_SECONDS = 60 * 60

# When a planning pass returns NEEDS_USER and no other current-milestone
# story is independently selectable, the orchestrator waits locally and
# re-checks agent/user-decisions/*.md at this interval -- a plain file
# read, never a model call (Codex/Claude/Hermes/Ollama/the planner).
USER_DECISION_POLL_SECONDS = 1800

# Planning is triggered once the number of selectable "To Do" stories
# drops to this many or fewer (including zero).
PLANNING_TRIGGER_MAX_READY_STORIES = 2

# A single COMPLETE planning run that creates stories at all must create
# at least this many (never zero speculative filler just to pad a batch --
# 1 is a fully valid, normal batch size) and never more than the upper
# bound below, i.e. never the whole future roadmap in one run. A run that
# needs zero new stories should not be reporting COMPLETE-with-creation in
# the first place -- see PLANNER_INSTRUCTIONS.md.
PLANNING_MIN_STORIES_PER_RUN = 1
PLANNING_MAX_STORIES_PER_RUN = 6

# ============================================================
# Aider RepoMap (experimental, orientation-only context for Claude)
#
# Aider is used only as a repository-structure/context generator for
# Claude's implementation prompt -- never as a coding agent, and never
# added to the Codex planner, the Hermes evaluator/dispatcher, or the
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