Purpose

Default guide for Claude Code.
Default role: IMPLEMENTATION MODE.
Project planning lives in agent/PLANNER_INSTRUCTIONS.md.
Keep context small:

read only task-relevant files;

prefer targeted inspection;

do not repeatedly reopen unchanged files.

Project

GW2 crafting/economy tool.
Current stack: Java 25, Maven, JavaFX, PostgreSQL, GW2 API.
Target direction: containerized web app with separate frontend, backend, and PostgreSQL.
Do not assume target architecture is already implemented.

Documentation Router

Read only what the task requires:

docs/DOMAIN_SPEC.md — authoritative domain behavior.

docs/CURRENT_STATE_SPEC.md — descriptive current behavior.

docs/CURRENT_ARCHITECTURE.md — current structure/runtime architecture.

docs/TARGET_ARCHITECTURE.md — intended architecture and TBD technologies.

docs/TEST_STRATEGY.md — testing methodology/layers.

docs/ROADMAP.md — phases, dependencies, exit criteria.

docs/KNOWN_PROBLEMS.md — current defects, conflicts, risks, technical debt.

Documentation Ownership

Use one authoritative owner:

ROADMAP.md — phases, dependencies, exit criteria, high-level future work.

BACKLOG.md — executable queue, priority, status grouping.

STORY-*.md — story scope, acceptance criteria, result, blockers.

CURRENT_ARCHITECTURE.md — current architecture.

TARGET_ARCHITECTURE.md — target architecture/TBD technologies.

DOMAIN_SPEC.md — normative domain rules.

KNOWN_PROBLEMS.md — known defects/risks/debt.

TEST_STRATEGY.md — test methodology, never live test totals.

UD-*.md — human decisions and OPEN/RESOLVED status.

PROJECT_STATE.md — planner-only continuity state.
If information is derivable from its owner, do not store another copy elsewhere.

Roles

IMPLEMENTATION MODE — default, defined here.

PROJECT PLANNING MODE — defined entirely in agent/PLANNER_INSTRUCTIONS.md.
If PROJECT PLANNING MODE is explicitly requested, follow that file instead of this implementation workflow.

Story System

agent/CURRENT_STORY.md — path to active story.

agent/runtime/artifacts/CLAUDE_RESULT.md — implementation result.

agent/stories/BACKLOG.md — status index only.

agent/stories/ — canonical story files.

agent/stories/archive/ — archived completed milestones.

agent/user-decisions/ — canonical decision files.
Each story has one canonical file.
Do not duplicate story content into BACKLOG.md.
BACKLOG sections:

Active — current story.

To Do — READY/selectable.

Blocked — blocked.

Done — completed in current milestone.

Archived — historical index.
Never select or activate the next story.

IMPLEMENTATION MODE

Before

Read agent/CURRENT_STORY.md.

Open the referenced story.

Treat it as the authoritative work order.

Read only its listed authoritative docs.

Inspect only relevant code, callers/callees, and tests.

Do not modify CURRENT_STORY.md.

Do not select another story.
If the story is DONE or BLOCKED, report and stop.

During

Work only against the story Definition of Done.

Keep changes small and focused.

Do not add unrelated refactoring.

Do not broaden scope automatically.

If a material ambiguity is unspecified, stop and report it.

Preserve existing behavior unless story/spec says otherwise.

Update status/result in the story file.

Update agent/runtime/artifacts/CLAUDE_RESULT.md.

Update only that story's BACKLOG status entry.

Never activate the next story.

Documentation After Implementation

Update another document only if the story materially changes information owned by that document:

architecture -> CURRENT_ARCHITECTURE.md

domain behavior -> DOMAIN_SPEC.md

known problem -> KNOWN_PROBLEMS.md

roadmap exit state -> ROADMAP.md
Do not:

copy story results into PROJECT_STATE.md;

add completed-story history to PROJECT_STATE.md;

add live test counts to TEST_STRATEGY.md;

duplicate BACKLOG state;

duplicate user-decision status;

duplicate architecture/domain facts into ROADMAP or PROJECT_STATE.
agent/PROJECT_STATE.md is planner-owned. Normal implementation must not update it.

Source Priority

When information conflicts:

explicit current user instruction

active story scope/acceptance criteria

DOMAIN_SPEC.md

TARGET_ARCHITECTURE.md

other task-specific authoritative specs

existing implementation

README/comments
Existing code proves current behavior, not desired behavior.

Domain

Never invent or reinterpret a domain rule to make implementation easier.
If code conflicts with DOMAIN_SPEC.md, report the conflict before changing behavior.

Architecture

Target separation:

UI/transport

application use cases

domain

infrastructure
Domain code must not depend on JavaFX, frontend frameworks, HTTP/REST, JSON transport models,
PostgreSQL/JDBC/SQL, Docker, or GW2 API response models.
Business rules belong in backend/domain.
External systems need clear boundaries/adapters.
Frontend technology is undecided.
Backend framework is undecided.
Do not introduce either unless explicitly requested.
PostgreSQL remains the target database.

GW2 API / Security

Backend owns GW2 API access.
Frontend must not call it directly.
Do not expose API keys/database credentials to frontend code.
Runtime configuration must not be hard-coded.
Accepted project decisions:

read-only GW2 API key is not an account-takeover credential;

local PostgreSQL password is not a material security finding.
Do not repeatedly raise those as findings.

Testing

Domain behavior changes require automated tests.
Bug fix:

reproduce with a test when practical;

make smallest fix;

run relevant regression tests.
Refactor:

preserve behavior with tests;

refactor;

verify unchanged behavior.
Do not weaken/delete tests merely to make a change pass unless the specification changed.

Repository Exploration

Start with named files, direct callers/callees, relevant tests, and relevant repo/domain classes.
Expand only when necessary.
For large files, inspect relevant sections rather than rereading the whole file.

Generated / External Files

Do not modify or commit generated/dependency files unless explicitly required.
Examples: third-party JARs, caches, build outputs, IDE metadata.

Communication

Implementation report:

What changed

Why

Tests run

Remaining uncertainty
Analysis report:

Observed fact

Inference

Recommendation
Do not present inference as observed fact.

Default Behavior

If clear, proceed without unnecessary clarification.
If work requires choosing an undecided domain/architecture rule, stop and ask.
Keep changes small, reviewable, and testable.