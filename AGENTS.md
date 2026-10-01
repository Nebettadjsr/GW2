# AGENTS.md

## Purpose

Repository-level instructions for Codex.

This repository uses Codex in multiple distinct roles.
Do not mix those roles.

The detailed rules for each role live in dedicated instruction files.
This file is only the repository-level router and shared context.

---

## Role Selection

Codex may run in one of these modes:

### PROJECT PLANNING MODE

Use:

`agent/PLANNER_INSTRUCTIONS.md`

This mode manages planning artifacts only.

Typical responsibilities:

- evaluate the current roadmap phase;
- process Product Owner requests;
- create or update planning artifacts;
- create stories;
- maintain backlog planning state;
- create User Decisions when required;
- raise architecture questions it may not decide as `agent/architect-requests/AR-*.md`;
- maintain roadmap/planner continuity.

It does not implement source code.

When PROJECT PLANNING MODE is explicitly requested or supplied by the orchestrator:

1. read and follow `agent/PLANNER_INSTRUCTIONS.md` — when the task prompt already reproduces it, that supplied copy *is* the read: do not open the file again, and do not read this file either;
2. treat that contract as authoritative;
3. do not also perform Architecture Mode duties unless the planner instructions explicitly require consulting architectural documentation.

---

### ARCHITECTURE MODE

Use:

`agent/ARCHITECT_INSTRUCTIONS.md`

This mode answers architectural questions and maintains architectural intent.

Typical responsibilities:

- answer architecture questions;
- interpret architecture constraints;
- evaluate architectural alternatives;
- make routine architecture decisions within established authority;
- record significant architecture decisions;
- identify architecture conflicts or drift;
- escalate genuine Product Owner decisions when necessary.

It does not:

- implement source code;
- create or prioritize stories;
- select work;
- perform roadmap phase planning.

When ARCHITECTURE MODE is explicitly requested or supplied by the orchestrator:

1. read and follow `agent/ARCHITECT_INSTRUCTIONS.md` — when the task prompt already reproduces it, that supplied copy *is* the read: do not open the file again, and do not read this file either;
2. treat that contract as authoritative;
3. do not perform planner duties.

The orchestrator invokes this mode only while an unresolved architecture
question is queued in `agent/architect-requests/`, dispatching exactly one
request per invocation, and expects
`agent/runtime/artifacts/ARCHITECT_RESULT.json` as the result. There is no
periodic architecture review. See that role contract's "Runtime Contract"
section.

---

## No Implicit Role Switching

Do not switch between Planner Mode and Architecture Mode merely because another role would be useful.

If running as planner:

- consume established architecture, including a RESOLVED architect request's
  recorded decision;
- queue an architecture question you may not decide as an architect request;
- do not silently become the architect, and never answer or edit a request.

If running as architect:

- identify implementation/planning consequences;
- do not create stories or modify planner-owned state.

A separate invocation should perform the other role when needed.

---

## Project

GW2 crafting/economy tool.

Current implementation direction:

- Java 25;
- Maven;
- JavaFX legacy desktop UI (still present; the active browser UI is Vue/TypeScript);
- PostgreSQL;
- Guild Wars 2 API.

Target direction:

- containerized web application;
- separate frontend;
- backend owning business logic;
- PostgreSQL;
- frontend/backend communication through HTTP;
- domain logic isolated from UI, persistence, transport, and external API formats.

Do not assume the target architecture is already implemented.

---

## Authoritative Documentation

Use the correct owner for each kind of information.

- `docs/DOMAIN_SPEC.md`
  - normative product/domain behavior

- `docs/crafting/GLOSSARY.md`
  - canonical player-facing crafting terminology and UI-label mapping; do not copy its definitions into feature docs

- `docs/CURRENT_STATE_SPEC.md`
  - current technologies, runtime behavior, external systems, build/runtime state

- `docs/CURRENT_ARCHITECTURE.md`
  - architecture that exists now

- `docs/TARGET_ARCHITECTURE.md`
  - intentionally selected target architecture, constraints, and unresolved technology/identity choices

- `docs/ROADMAP.md`
  - phases, dependencies, migration sequencing, exit criteria

- `docs/KNOWN_PROBLEMS.md`
  - known defects, architecture conflicts, risks, and technical debt

- `docs/TEST_STRATEGY.md`
  - testing methodology and test layers

- `docs/CODING_GUIDELINES.md`
  - binding implementation-quality conventions

- `agent/user-decisions/UD-*.md`
  - human decisions, for every role

- `agent/architect-requests/AR-*.md`
  - architecture questions for ARCHITECTURE MODE (planner-created, architect-answered)

- `docs/architecture/decisions/ADR-*.md`
  - significant architecture decision records (architect-owned)

- `agent/stories/BACKLOG.md`
  - executable work queue and priority

- `agent/stories/STORY-*.md`
  - individual implementation contracts

- `agent/PROJECT_STATE.md`
  - planner continuity only

Do not duplicate authoritative information merely for convenience.

---

## General Evidence Rules

Distinguish clearly between:

- observed fact;
- inference;
- recommendation.

Current implementation proves current behavior, not intended behavior.

Do not invent:

- repository state;
- test results;
- performance measurements;
- requirements;
- domain behavior;
- architecture decisions;
- dependency behavior;
- completed work.

If authoritative information is missing, treat it as unknown.

---

## Repository Exploration

Keep context proportional to the task.

Prefer:

1. named authoritative documents;
2. directly relevant files;
3. direct callers/callees or related components;
4. broader repository exploration only when needed.

Do not repeatedly reread unchanged large files.

---

## Product Intent

Product behavior remains human-owned.

Codex may identify ambiguity.

Codex must not invent the answer to a genuine Product Owner decision.

Use the role-specific User Decision process when human input is required.

---

## Architecture Principles Shared Across Roles

Preserve these established target principles:

- backend owns authoritative business logic;
- frontend does not reproduce domain calculations;
- frontend does not call the Guild Wars 2 API directly;
- secrets remain backend-only;
- domain logic must not depend on JavaFX, HTTP, JSON transport models, PostgreSQL/JDBC/SQL, Docker, or GW2 API response models;
- external systems should sit behind clear boundaries/adapters;
- PostgreSQL remains the target database unless explicitly changed;
- avoid unnecessary architectural complexity;
- do not finalize a technology marked `TBD` silently: the architect role decides
  it on technical merit and records the rationale; other roles consume that
  decision rather than making their own;
- prefer incremental migration over broad rewrite;
- prefer the simplest design that preserves correctness, testability, and clear responsibility boundaries.

---

## Direct Instructions

Explicit system, developer, orchestrator, or user instructions for the current invocation take precedence over this file.

Role-specific instruction files then define the detailed behavior for that mode.
