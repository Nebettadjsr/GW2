# Comparison with the reference workflow

This process was modelled on a workflow described in a video talk (German, mid 2026). This document
records what that workflow consists of, how each part is implemented here, where this setup differs on
purpose, and what is still open. It compares the method, not the numbers.

## The reference

An enterprise-software consultant rebuilt his own back-office application (about ten microservices,
roughly 420,000 lines, hundreds of user stories with thousands of acceptance criteria) with AI coding
agents in about 45 working days, first with Codex and later almost only with Claude. The talk describes how
the agent was steered. The figures are the author's own measurements, taken on a large greenfield rebuild by
an expert; no comparable measurement exists for this repository and no speed factor is claimed here.

The talk is high level. It shows the idea of the harness but not its content, and it does not say who
corrects the harness, when, or whether that happens automatically.

Its building blocks:

- a **harness**: instruction files loaded as the agent's system prompt (an architecture guideline with
  hundreds of rules, a coding guideline, a test description, how to split requirements into tasks, the
  format of an idea);
- **static analysis after every change** (architecture and code analyzers) instead of asking the model to
  check itself;
- **harness-correction development**: a deviation in the output means the harness is corrected, not only the
  code;
- five phases of working: micro-managed prompts, quality-driven (harness plus analyzers), spec-driven
  (stories with acceptance criteria), test-driven (tests derived from the criteria), idea-driven by voice;
- two test layers (service API tests and workflow tests with the external systems mocked);
- an agent that can build, deploy to a test environment and read logs;
- documentation regenerated every night;
- measurements: structural debt, coverage, documentation coverage, effort and cost;
- the human moving from developer to architect, product owner and finally stakeholder.

## Mapping

Status: **Implemented**, **Partly**, **Different** (deliberately), **Open**.

| Reference element | Here | Where | Status |
|---|---|---|---|
| Harness as the agent's prompt | Shared rules, programmer guide, rule documents, owning documents and the run prompt | [AGENTS.md](../AGENTS.md), [CLAUDE.md](../CLAUDE.md), `docs/*_GUIDELINES.md`, [claude.yml](../.github/workflows/claude.yml) | Implemented |
| Architecture guideline | Written decisions plus executable rules (a strict layer order without cycles, no JDBC or connections in the web layer, one frozen known debt) | [ARCHITECTURE.md](../docs/ARCHITECTURE.md), `backend/src/test/java/architecture/` | Implemented |
| Coding guideline | Cross-language principles and rules per language, including comments and simplicity | [CODING_GUIDELINES.md](../docs/CODING_GUIDELINES.md) | Implemented |
| Analyzers as the gate after every change | ArchUnit, Checkstyle and Error Prone for Java, ESLint for TypeScript and Vue; the agent runs them before it finishes and CI enforces them | `backend/config/`, `backend/pom.xml`, `frontend/eslint.config.js` | Implemented (other tools; the reference tools are .NET) |
| Harness-correction development | Agents report `## Harness feedback`, which opens an issue; the supervisor corrects the harness in its own PR when a deviation recurs, is costly or is confirmed by a drift finding | [DEVELOPMENT_WORKFLOW](DEVELOPMENT_WORKFLOW.md) section 5 | Implemented (and answers who and when, which the reference leaves open) |
| Spec-driven development | Stories are issues with numbered acceptance criteria; the domain specification is the source of intended behavior | [PLANNER_INSTRUCTIONS](PLANNER_INSTRUCTIONS.md), [DOMAIN_SPEC](../docs/DOMAIN_SPEC.md) | Partly (stories yes; whole requirement specs written before the stories, no) |
| Tests derived from the criteria | The report must map every criterion to a concrete test or evidence and the merge gate rejects an empty table | `.github/scripts/merge_ready_pr.py` | Partly (traceability yes; test-first is not enforced) |
| Test layers | Domain unit, property, application, real-PostgreSQL integration, HTTP contract and parity, controlled browser journeys, container smoke | [TEST_STRATEGY](../docs/TEST_STRATEGY.md) | Implemented (different layering: real database and fixtures instead of mocked services) |
| Idea-driven development by voice | Requests reach the planner or supervisor in chat; there is no idea intake, vision or requirement stage | none | Open (item 1) |
| Agent with access to environments | The programmer runs in an isolated runner with a database service and a browser; it cannot reach a test or production environment, and deployment stays manual | [DEVELOPMENT_WORKFLOW](DEVELOPMENT_WORKFLOW.md) sections 2 and 8 | Different (deliberate) |
| Nightly documentation | A nightly drift check of the application documents (domain, UI, architecture, current state, known problems) against code and tests, with git history per finding; it reports in one issue and does not rewrite the documents | [docs-drift.yml](../.github/workflows/docs-drift.yml) | Implemented (detects instead of regenerating) |
| Measurements | Per-run turns, time, tokens and API-equivalent cost; a coverage KPI with a 90% target that only warns | `.github/scripts/summarize_claude_run.py`, `.github/scripts/publish_coverage_kpi.py` | Partly (no structural-debt measure over time) |
| Role of the human | The owner sets goals and decides; a supervisor session runs the process; the programmer works in Actions | [README](README.md) | Implemented |

## Where this setup goes further

- A second model reviews every PR at its exact head commit, and its unresolved findings block the merge.
- An automatic merge gate checks the head SHA, five required checks, the criteria table, the feedback field
  and the review summary; everything else needs a supervised merge.
- The harness boundary is enforced in code: agents cannot change harness paths (changes are discarded and
  reported), and a change to those paths never merges automatically.
- Rework starts from the failing checks of the current head, and a rework that changes nothing fails visibly.
- Cost and recovery controls: subscription credential only, turn and time limits, a recovery artifact for
  failed runs.
- A container smoke in CI builds both images as Compose builds them and starts the stack.
- The documentation check keeps its report as an auditable artifact and counts a run as checked only when
  the every group produced a valid report.

## Open items

1. **Idea, vision, requirements, themed backlog, stories.** The planner works at story level. No document
   says who uses the application and what they want to achieve, so the programmer and the supervisor lack
   the product context. Proposal: a short, owner-approved vision document (`VISION.md`: purpose, users and goals, key
   scenarios, success criteria, non-goals, principles); `idea` and `theme` issue forms and story fields for
   value, theme and spec references; a planner that takes a whole theme (critical check of the idea against
   the vision, requirements written first as a specification PR the owner approves, a theme issue with an
   ordered list of stories, then the stories); a product block in the programmer guide; and a value check in
   the supervisor's acceptance. The pinned backlog becomes the roadmap of themes. Decisions needed from the
   owner: who the application is for, what the user wants to achieve, what it must never become, the two or
   three scenarios that carry its value, and how success is recognized. Tracked in the pinned backlog issue
   (#398).
2. **Frozen architecture debt:** `application` still uses JDBC directly (182 recorded violations, decision
   in issue #419: wrap pass-through services, keep the connection in the snapshot classes). The package
   cycles are gone and the layer order is enforced without exceptions.
3. **Boundary in a real run:** the discard step and the read-deny rules are unit-tested but no agent has yet
   tried to cross the boundary in a live run.
4. **Test-first** is not enforced; tests are written with the code and mapped afterwards.
5. **Report honesty:** `Local-only checks still needed: none` is a claim the merge gate cannot verify,
   notably for scripts that CI does not run.
6. **Structural debt over time** has no measure beyond the shrinking violation store.
