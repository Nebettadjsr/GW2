# GW2 programmer guide

GW2 is a live crafting/economy web application: Vue/TypeScript frontend, Java 25,
Maven/Spring Boot backend, PostgreSQL and the GW2 API. Read only the issue's
relevant documentation/code. [Development workflow](docs/DEVELOPMENT_WORKFLOW.md)
describes actual GitHub automation, evidence, recovery and release.

## Assigned GitHub work

You are the programmer. The explicitly assigned Codex planner proposes work;
interactive Codex supervises/releases it under the contracts in `agent/`.
Read current authorization in `agent/BACKLOG.md` and the approved issue.
Drafts do not authorize implementation. Do not activate work, choose another
milestone, change criteria, dispatch runs, merge or deploy yourself.

One issue gets one branch and PR. Read its goal, acceptance criteria, constraints,
dependencies and existing PR review/CI feedback. Do not start before dependencies
merge. Ask genuine product questions in the issue/PR with `needs-decision`;
do not invent behavior. Use follow-ups for small nonblocking improvements
instead of expanding scope. Missing criteria/material correctness gaps block.

Normally implement meaningful tests/fixtures with code. A scoped approved issue
or session may give independent QA that ownership; honor the override. Run narrow
checks locally and let CI run full default suites. Report actual commands/results,
local checks still needed, unresolved gaps and reasons for changed/weakened
assertions. Keep the temporary workflow report current before lengthy checks.
The workflow commits, pushes and publishes PR evidence.

## Owning documentation

| Document | Owns |
|---|---|
| `docs/DOMAIN_SPEC.md` | Domain/product rules |
| `docs/crafting/GLOSSARY.md` | Player-facing crafting terms |
| `docs/CURRENT_STATE_SPEC.md` | Observed runtime/build/deployment facts |
| `docs/ARCHITECTURE.md` | Current structure, selected boundaries and open choices |
| `docs/ROADMAP.md` | Product direction and exit criteria |
| `docs/FRONTEND_UX_GUIDELINES.md` | Browser UX |
| `docs/TEST_STRATEGY.md`, `docs/QUALITY_METRICS.md` | Verification and measured coverage |
| `docs/CODING_GUIDELINES.md` | Implementation conventions |
| `docs/DEVELOPMENT_WORKFLOW.md` | Implemented GitHub process |

Read the owner before changing its subject; update relevant docs with delivered
behavior. Code establishes observations, not new product intent. Historical
decisions/contracts are in `docs/archive/`, not today's work queue.

## Domain boundaries

Backend owns authoritative business logic/GW2 API access. Deployment secrets
remain backend-only. Browser-held read-only GW2 keys follow Architecture's
account/session rules: proportionate retention and account isolation, without
high-sensitivity credential infrastructure.

Domain logic cannot depend on JavaFX, HTTP, JSON transport models,
PostgreSQL/JDBC/SQL, Docker or GW2 API response models. Use adapters for external
systems. PostgreSQL is disposable cache; missing/inconsistent data is re-fetched.
Ectoplasm Salvage has the documented browser-calculation exception.
Do not silently decide a genuinely unresolved technology/product choice.
Prefer incremental changes and simple responsibility boundaries.

## Checks and care

Use the Test strategy for meaningful assertions. CI supplies an isolated
PostgreSQL service for database-backed `*Test` cases; `*IT` remains outside
the default naming set. Browser smoke/live API checks require their explicit
setup/evidence. Never claim unrun checks passed or use real owner credentials
for routine tests.

Preserve unrelated maintainer edits. Keep the diff focused; separate facts,
inferences and recommendations. Return deliverables to the main project and
stop owned processes/remove disposable worktrees after use. Keep secrets,
temporary reports and scratch output out of tracked files.
