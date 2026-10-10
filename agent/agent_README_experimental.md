# AI Development Orchestrator

## Purpose

This directory contains the experimental AI-assisted development workflow used to clean up, stabilize, and further develop the GW2 crafting application.

> **Documentation status:** This file preserves the experiment's background and earlier design notes. It is not the current runtime contract; some operational details below are historical. The standalone Python orchestrator described below has been replaced by the n8n development pipeline; for the implemented workflow, use [`../local_bridge/README.md`](../local_bridge/README.md), [`runtime/README.md`](runtime/README.md), [`PLANNER_INSTRUCTIONS.md`](PLANNER_INSTRUCTIONS.md), [`ARCHITECT_INSTRUCTIONS.md`](ARCHITECT_INSTRUCTIONS.md), and [`QA_INSTRUCTIONS.md`](QA_INSTRUCTIONS.md). The runtime guide owns current Planner triggers, stage recovery, retry limits, and technical failure handling.

This setup is deliberately experimental.

The project is being used to investigate a broader question:

> **How far can software development be automated in practice, while still producing a working product and maintainable software?**

The experiment is not only about whether AI can generate code. It is intended to observe whether a coordinated system of deterministic automation, planning models, implementation agents, evaluation, and human product decisions can reliably move a real software project forward over a longer period of time.

The experiment has three concrete success criteria:

1. **A functioning product**
   - the application should increasingly behave as intended;
   - known defects and ambiguous behavior should be resolved;
   - new Product Owner requirements should become usable product functionality.

2. **Clean and maintainable code**
   - correctness must not be achieved through uncontrolled patch accumulation;
   - architecture, domain rules, tests, and documentation should converge toward a coherent system;
   - technical debt should be identified and reduced rather than silently hidden.

3. **Observable development cost**
   - development time;
   - model usage;
   - subscription/API cost where applicable;
   - human intervention;
   - failed attempts and rework;
   - effort required to keep the automation itself working.

The point is therefore not merely to automate implementation, but to understand the practical limits, strengths, weaknesses, and economics of AI-assisted software development.


# Why the GW2 Crafting Project?

The experiment is intentionally being performed on a difficult problem rather than a trivial demonstration application.

Guild Wars 2 crafting contains a large number of interacting rules and data sources, including:

- nested crafting recipes;
- intermediate materials;
- multiple crafting disciplines;
- character-specific crafting eligibility;
- soulbound / character-bound materials;
- account-wide materials;
- bank and inventory state;
- trading-post prices;
- recipe discovery;
- owned-material valuation;
- profitable craft calculations;
- recursive crafting trees;
- simulation limits;
- multiple characters cooperating on transferable intermediate crafts;
- persistence and synchronization against external game/API data.

This creates a domain where superficially plausible code can still be wrong.

That makes it useful for testing whether an AI development system can do more than produce syntax-correct code.

The system must preserve domain correctness while planning, implementing, testing, documenting, and eventually optimizing a non-trivial application.


# Experimental Goal

The long-term experiment asks whether a software project can move from:

```text
existing / inconsistent repository
        ↓
documented intended behavior
        ↓
known defects and conflicts identified
        ↓
planned work
        ↓
AI-assisted implementation
        ↓
verification
        ↓
cleaner architecture
        ↓
working target product
```

with progressively less manual implementation work while keeping the human in control of product intent.


# Core Principle

No single AI is allowed to control the entire development process.

Responsibilities are intentionally separated between:

- deterministic Python orchestration;
- Codex as project planner;
- Codex as architect, in a separate invocation and a separate role;
- Claude Code as implementation agent;
- Codex as implementation evaluator, read-only and in a third separate role;
- the human Product Owner for genuine product decisions.

The models may reason inside their assigned role.

Python controls workflow state.

The Product Owner remains authoritative for product intent.


# Current Architecture

The current high-level workflow is:

```text
                     PRODUCT OWNER
                          │
          ┌───────────────┴────────────────┐
          │                                │
   Product requests                 User decisions
          │                                │
          ▼                                ▼
agent/product-owner-requests/    agent/user-decisions/
          │                                ▲
          └──────────────┬─────────────────┘
                         │
                         ▼
                Python Orchestrator
                         │
        ┌────────────────┴────────────────┐
        │                                 │
        ▼                                 │
  Codex Architect                         │
  (only while an unresolved               │
   agent/architect-requests/              │
   AR-* exists)                           │
        │                                 │
 architecture docs / ADRs                 │
 or a new OPEN UD-*                       │
        │                                 │
        └────────────────┬────────────────┘
                         │
                         ▼
                  Codex Planner
                         │
                 planning artifacts
                 (incl. new AR-* questions)
                         │
                         ▼
                Python validation
                         │
                         ▼
          deterministic Python selector
                         │
                         ▼
                  Claude Code
                  implementation
                         │
                         ▼
                Codex Evaluator
                         │
                  ┌──────┴──────┐
                  │             │
                DONE          retry /
                  │           correction
                  ▼
            next planning /
                next story
```

There is intentionally no LLM-based story selector.

The next executable story is selected deterministically by Python from the backlog according to repository state and eligibility rules.


# Responsibility Split

The system separates several different questions.

```text
WHAT should the product become?
        │
        ▼
Product Owner
+ authoritative project/domain documentation

WHAT architecture should satisfy those requirements?
        │
        ▼
Codex architect
(only when asked a queued question)

WHAT work is still required?
        │
        ▼
Codex project planner

WHAT can run next?
        │
        ▼
deterministic Python selection

HOW should this bounded story be implemented?
        │
        ▼
Claude Code

DID THE IMPLEMENTATION SATISFY THE STORY?
        │
        ▼
Codex evaluator (read-only)
```

This separation is intended to reduce:

- hallucinated requirements;
- uncontrolled repository-wide edits;
- architecture drift;
- duplicate documentation;
- wasted model usage;
- models choosing convenient work rather than required work;
- one model silently redefining product behavior while implementing it.


# 1. Python Orchestrator

Location:

`agent/runtime/`

Python is the deterministic control layer.

It is responsible for:

- reading current workflow state;
- detecting whether the current story is already complete;
- deciding when project planning is required;
- invoking the project planner;
- validating planner output;
- invoking the architect when, and only when, an unresolved architecture question is queued;
- validating architect output;
- determining which stories are selectable;
- deterministically selecting the next executable story;
- activating stories;
- invoking implementation;
- invoking evaluation;
- handling milestone transitions;
- handling blocked work;
- handling Product Owner decisions;
- waiting for human input without repeatedly consuming model tokens.

Python, not an LLM, owns orchestration state.


## Deterministic Story Selection

Selectable work lives in:

`agent/stories/BACKLOG.md`

under:

`## To Do`

The planner may create and prioritize work in the backlog, but it does not choose the next runtime story.

Python checks eligibility and selects the next valid story deterministically.

This keeps scheduling behavior inspectable and prevents a model from arbitrarily changing execution order during runtime.


## Planning Trigger

The planner does not need to run before every story.

Existing planned work is allowed to execute first.

When the amount of remaining selectable work becomes small, the orchestrator invokes project planning again so that the project can be reassessed before the queue is exhausted.


## NEEDS_USER Behavior

`NEEDS_USER` is intended to block only the work that actually depends on a human decision.

It must not automatically stop unrelated development.

When planning creates or references an unresolved User Decision:

1. the relevant work remains blocked;
2. independent executable stories may continue;
3. if independent work exists, Python continues normal deterministic selection;
4. only when no independent work remains does the orchestrator wait for the required decision.

While waiting, Python checks only the relevant local User Decision files.

It does not repeatedly call Codex or Claude merely to ask whether the user has answered.

As soon as any blocking decision becomes `RESOLVED`, the wait ends and Python re-derives what is now permissible — it does not keep waiting for the remaining open decisions, since one answer can be enough to unblock planning or a dependent story.


# 2. Codex — Project Planner

Codex is the high-level project planner.

It currently runs through the hosted Codex CLI using the ChatGPT subscription.

Codex replaced the earlier local `gpt-oss:20b` planning experiment because the local planner proved too slow and unreliable for repository-level planning and tool use.


## Codex Responsibilities

Codex may:

- evaluate the current roadmap phase;
- inspect relevant planning documentation;
- process Product Owner requests;
- identify missing work;
- create stories;
- update backlog planning state;
- maintain roadmap progress;
- create User Decisions when required;
- update authoritative target/domain documentation;
- determine whether current phase exit criteria are satisfied.

Codex does not implement application source code.


## Phase-Scoped Planning

Python determines the current roadmap phase before the planner starts.

The planner receives the current phase as authoritative scope.

The intended rule is:

```text
one planning pass
      =
one roadmap phase
```

The planner must not freely expand into later roadmap phases.

If the current phase completes:

1. the planner records the transition;
2. no next-phase implementation stories are created in the same pass;
3. the pass ends;
4. Python starts a fresh planning pass for the newly current phase.


# 3. Codex — Architect

Codex also runs in a second, logically separate role: the architect
(`agent/ARCHITECT_INSTRUCTIONS.md`, routed by `AGENTS.md`).

The planner decides *what work is required*. The architect decides *how the
system should be structured* to satisfy the documented requirements. The same
model may perform both, but never in the same invocation.

## Demand-Driven, Not Periodic

The architect is invoked only when there is a question waiting for it.

Architecture questions the planner is not authorized to decide are queued as
one file per question:

`agent/architect-requests/AR-<NUMBER>-short-name.md`

Each request records the question, its context and constraints, the
authoritative documents that bear on it, and what work it blocks.

The orchestrator dispatches exactly one request per invocation, and only while
an unresolved request is actionable. An empty inbox means the architect never
runs: there is no periodic architecture review, and no architecture pass
"just in case".

## Lifecycle

```text
OPEN         needs the architect
NEEDS_USER   escalated; waiting on the UD-* the request names
RESOLVED     answered; never processed again
```

The architect may answer a request from existing authoritative documentation,
make a local architectural decision inside established boundaries, or record a
significant decision whose answer existing constraints already force. When a
genuine Product Owner choice remains -- most importantly a technology still
marked `TBD` -- it escalates through the existing `agent/user-decisions/` flow.
Architecture does not get its own human-decision mechanism.

Once the human resolves that decision, the orchestrator runs the architect
again for the same request automatically. Once the request is `RESOLVED`,
planning continues automatically and consumes the recorded decision as input.

## What the Architect Does Not Do

- implement source code;
- create, prioritize or activate stories;
- touch the backlog, the active story, or planner continuity state;
- perform roadmap phase planning;
- silently finalize a technology marked `TBD`;
- answer a User Decision on the Product Owner's behalf;
- rewrite its own role contract.

Python verifies each of those after every pass and rolls the pass back if one
was violated, the same way it protects the active story from the planner.

Usage exhaustion is not an architecture failure: the request keeps its status
and is dispatched again when capacity returns.


# 4. Product Owner Requests

New desired features, behavior changes, and product requirements can be placed in:

`agent/product-owner-requests/`

These files are transient planning input.

They are not directly executable work items.

Codex evaluates them and determines whether they require:

- a domain specification update;
- a target architecture update;
- roadmap work;
- one or more stories;
- a User Decision.

Once the request is fully represented in authoritative planning artifacts, the planner retains the original file with Status RESOLVED and a Planner Resolution describing the work and citing those artifacts. Future passes ignore RESOLVED requests; only the human Product Owner deletes them after review. OPEN and NEEDS_USER requests remain visible, with blocking UD-* files recorded for NEEDS_USER.

The request inbox is intentionally not a second permanent requirements database.


# 5. Human User Decisions

Product behavior must not be guessed when a genuine decision belongs to the Product Owner.

Open questions are stored under:

`agent/user-decisions/UD-*.md`

Typical examples include:

- ambiguous product behavior;
- UX alternatives with materially different outcomes;
- domain-rule ambiguity;
- architecture alternatives;
- explicit technology choices;
- behavior that cannot be safely inferred from authoritative sources.

The objective is not to ask the human about every implementation detail.

Normal technical implementation choices should remain with the implementation/planning system where appropriate.

User Decisions are for decisions that materially affect intended product behavior or architecture.


# 6. Claude Code — Implementation Agent

Claude Code performs bounded implementation.

Claude is intentionally not the project manager.

It receives one selected story at a time.


## Claude Responsibilities

Claude may:

- inspect implementation-relevant source;
- modify application code;
- add or update tests;
- run builds and tests;
- make story-required documentation updates;
- record implementation results;
- report blockers or unexpected technical findings.

Claude should implement the active story rather than redesign the project plan.


## Claude Does Not Decide

Claude does not decide:

- the current roadmap phase;
- which story executes next;
- Product Owner priorities;
- whether an unspecified feature should exist;
- unresolved domain/product questions.

Those decisions belong to the planning/orchestration/product layers.


# 7. Codex — Evaluator

Evaluation runs on Codex, in a read-only sandbox, through the same runner as
PROJECT PLANNING MODE and ARCHITECTURE MODE and against the same capacity
budget.

It is a constrained evaluator, not the project planner and not the story
selector. Its purpose is to inspect the completed implementation against the
active story contract, and it answers two questions:

1. were this story's own acceptance criteria and Definition of Done satisfied;
2. did the work actually achieve what the story set out to achieve.

Evaluation targets include:

- acceptance criteria;
- the Definition of Done;
- what the change actually does, read from the working tree (`git status`,
  `git diff`, the changed files, the tests that are supposed to cover the
  behaviour);
- the implementation result Claude reported — treated as a claim to verify,
  never as evidence;
- blockers.

The evaluator can reject incomplete work and cause another
implementation/correction cycle. It cannot redefine the story, invent project
requirements, or write anything at all.


## Why It Is No Longer a Local Model

This was `hermes3:8b` running locally through Ollama, on the reasoning that
evaluation is narrower than repository-wide planning and should stay
inexpensive.

Two things were wrong with that. The model was too weak for the judgment — it
produced empty RETRY verdicts that the normalization layer had to rescue
deterministically. And its only evidence was the implementer's written account
of its own work, so it could confirm that a report claimed every criterion was
met but never whether the repository agreed. A change can satisfy every
literal criterion and still miss the story's point; catching that needs both a
stronger model and access to the diff.

The read-only sandbox is what keeps the role honest. Evaluation runs while the
story's implementation is still uncommitted in the working tree, so there is no
safe "restore whatever it touched" for this role — it has to be structurally
incapable of touching anything. The model classifies; Python records.


# Documentation Ownership

The repository follows a single-source-of-truth approach.

| Document | Responsibility |
|---|---|
| `docs/ROADMAP.md` | phases, dependencies, exit criteria, high-level future work |
| `docs/CURRENT_ARCHITECTURE.md` | architecture that exists now |
| `docs/TARGET_ARCHITECTURE.md` | intended future architecture |
| `docs/DOMAIN_SPEC.md` | authoritative product/domain behavior |
| `docs/KNOWN_PROBLEMS.md` | known defects, conflicts, risks, technical debt |
| `docs/TEST_STRATEGY.md` | testing methodology and test layers |
| `agent/stories/BACKLOG.md` | executable work queue |
| `agent/stories/STORY-*.md` | individual implementation contracts and results |
| `agent/user-decisions/UD-*.md` | human decisions |
| `agent/architect-requests/AR-*.md` | architecture questions for the architect role |
| `docs/architecture/decisions/ADR-*.md` | significant architecture decision records |
| `agent/PROJECT_STATE.md` | minimal planner continuity |
| `agent/product-owner-requests/` | transient Product Owner input |

Information should not be copied between documents merely for convenience.

The goal is that each important fact has one authoritative home.


# Story Lifecycle

A normal story progresses approximately as follows:

```text
TODO
 │
 ▼
selected by Python
 │
 ▼
ACTIVE
 │
 ▼
Claude Code
 │
 ▼
implementation
 │
 ▼
Codex evaluation
 │
 ├─ incomplete
 │    └─ retry / correction
 │
 └─ accepted
      │
      ▼
     DONE
```

When a story is already `DONE`, the orchestrator does not invoke Claude again for that story.


# Milestones and Roadmap Phases

Roadmap phases map to milestones.

Example:

```text
Phase 0 -> milestone-00
Phase 1 -> milestone-01
Phase 2 -> milestone-02
```

Milestone closure includes the bounded PROJECT HEALTH REVIEW required by `docs/ROADMAP.md`; review policy is defined in `docs/TARGET_ARCHITECTURE.md` §34, with subsequent planner disposition governed by `agent/PLANNER_INSTRUCTIONS.md`.

Future-milestone stories must not become executable while the current milestone remains incomplete.

Later work may exist in roadmap/target planning, but the runtime `## To Do` queue should contain only currently executable work.


# Current State of the Experiment

The orchestration system is functional but still experimental.

The current architecture is:

```text
Python orchestrator
        │
        ├── hosted Codex project planner
        │
        ├── hosted Codex architect (demand-driven)
        │
        ├── deterministic Python story selector
        │
        ├── Claude Code implementation agent
        │
        └── Codex evaluator (read-only)
```

This architecture has evolved through several experiments.


## Earlier Local Planner Experiment

A local `gpt-oss:20b` model was previously used through Codex/Ollama as project planner.

That experiment exposed several practical limitations:

- planning passes taking many minutes;
- excessive context/tool usage;
- repeated rereading of files;
- unreliable shell commands;
- unreliable Markdown/JSON editing;
- weak adherence to constrained planning scope;
- high orchestration complexity for relatively simple planning tasks.

The hosted Codex planner currently provides substantially faster and more reliable planning behavior.


## Current Orchestrator Experiment

The current system is testing whether a mixed architecture performs better:

- deterministic code for workflow decisions;
- strong hosted reasoning where project-level reasoning is required;
- bounded implementation by Claude;
- evaluation grounded in the working tree, not in the implementer's own report;
- human intervention only for genuine product decisions.

This split is itself part of the experiment and may change if measurements show a better structure.


# Current Project Objective

The application is being improved incrementally rather than rewritten from scratch.

The current process aims to:

1. establish trustworthy authoritative documentation;
2. understand the existing implementation;
3. identify conflicting or incomplete behavior;
4. add regression and integration tests;
5. resolve known defects;
6. finish intended crafting/domain behavior;
7. incorporate new Product Owner requirements;
8. improve architecture where justified;
9. optimize performance only after correctness is established;
10. continue roadmap implementation until the target product is reached.


# Performance as a Later Technical Requirement

Crafting calculation performance is explicitly important.

However, optimization comes after intended behavior is established.

The intended optimization workflow is:

```text
measure current performance
        ↓
identify actual bottleneck
        ↓
produce alternative solutions where useful
        ↓
evaluate tradeoffs
        ↓
implement selected optimization
        ↓
repeat the same measurements
```

The system should not sacrifice crafting correctness merely to reduce runtime.


# What We Want to Measure

Because this is an experiment in software-development automation, the process itself is part of the result.

Where practical, we want to observe:

## Development Time

- wall-clock time per story;
- planning time;
- implementation time;
- evaluation/retry time;
- waiting time for human decisions.

## AI / Tool Cost

- ChatGPT/Codex usage;
- Claude usage;
- local model usage;
- any future API or infrastructure cost.

## Human Effort

- number of User Decisions required;
- manual testing effort;
- prompt/debugging effort;
- orchestration maintenance;
- interventions needed after failed agent runs.

## Automation Reliability

- successful stories without intervention;
- failed planning passes;
- failed implementation attempts;
- evaluator retries;
- incorrect assumptions;
- regressions introduced;
- rework required.

## Code / Product Quality

- test coverage of important behavior;
- known-problem reduction;
- architecture cleanup;
- duplicated or contradictory documentation;
- defect rate;
- functional correctness.

The aim is to understand not just whether automation works, but what it costs to make it work reliably.


# Experimental Questions

This project should eventually help answer questions such as:

- How much software-development work can be delegated reliably?
- Which decisions are safe to automate deterministically?
- Which tasks benefit from a strong hosted model?
- Which tasks are sufficiently constrained for a smaller local model?
- How much human Product Owner input remains necessary?
- How much orchestration code is required to make agents reliable?
- Does the automation save time after its maintenance cost is included?
- Does it reduce or increase total development cost?
- Can AI-generated implementation remain maintainable over many stories?
- Can the system work effectively on a complex recursive domain such as GW2 crafting?
- At what point does adding more autonomous agent behavior reduce reliability rather than improve it?


# Important Design Decisions

## Deterministic Logic Where Possible

LLMs are not used for tasks that can be handled reliably through deterministic code.

Examples include:

- selecting the next eligible story;
- checking file status;
- validating result structure;
- waiting for User Decisions;
- enforcing basic workflow invariants.


## Planner and Implementer Are Separate

Codex plans project work.

Claude implements bounded stories.

This separation reduces the chance that implementation convenience silently changes product requirements.


## Product Intent Remains Human-Owned

Models can identify ambiguity.

They should not invent the answer.

Genuine product decisions are escalated through User Decision files.


## Independent Work Should Continue

One unresolved decision should not freeze unrelated development.

The orchestrator should continue executing independent stories whenever possible.


## Correctness Before Optimization

The crafting domain is complex enough that fast wrong answers are not useful.

Behavior must first become understood, specified, tested, and correct.

Only then should performance become the primary optimization target.


# Long-Term Target

The desired outcome is both a product and an experiment result.

On the product side:

- a functioning GW2 crafting application;
- correct domain behavior;
- maintainable architecture;
- useful automated tests;
- substantially reduced known technical debt;
- acceptable calculation performance.

On the experimental side:

- evidence about how much of software development can be automated;
- evidence about which model/tool split works reliably;
- evidence about required human involvement;
- evidence about time and cost;
- evidence about the maintenance overhead of the automation itself.

The final goal is therefore not an indefinitely self-modifying AI system.

The orchestrator is a controlled development mechanism used to investigate whether a complex real-world software project can be systematically moved toward a target product with increasingly automated planning, implementation, and verification while preserving human ownership of product intent.
