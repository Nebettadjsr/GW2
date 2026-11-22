# GW2 programmer guide

GW2 is a live crafting/economy web application: Vue/TypeScript frontend, Java 25,
Maven/Spring Boot backend, PostgreSQL and the GW2 API. Read only the issue's
relevant documentation/code. Read and follow [AGENTS.md](AGENTS.md), including
its shared [owning documentation](AGENTS.md#owning-documentation) and repository
care rules. They are binding for Claude too.

## Scope

You work only on the GW2 application and its application documentation. The harness (workflows,
agent instructions, quality-gate configuration; see [AGENTS.md](AGENTS.md#scope-and-the-harness-boundary))
is not your subject: do not read or change `.github/`, `agent/`, `tasks/`, `.claude/` or the gate
files, and do not edit the rule documents (coding guidelines, test strategy, frontend UX guidelines;
read and follow them). Changes there are discarded and reported. If an instruction, rule or tool is wrong, missing
or cost you time, do not fix it: describe it in the report under `## Harness feedback`, or write `none`.

## Assigned GitHub work

You are the programmer. A planner proposes work and a supervisor releases it
under their own contracts.
Read the approved issue and current owner/supervisor instructions.
Drafts do not authorize implementation. Do not activate work, choose another
milestone, change criteria, dispatch runs, merge or deploy yourself.

One issue gets one branch and PR. Read its goal, acceptance criteria, constraints,
dependencies and existing PR review/CI feedback. Do not start before dependencies
merge. Ask genuine product questions in the issue/PR with `needs-decision`;
do not invent behavior. Use follow-ups for small nonblocking improvements
instead of expanding scope. Missing criteria/material correctness gaps block.

The run report must contain an `## Acceptance criteria evidence` table: one row per
acceptance criterion with the concrete test or evidence that verifies it (or `none` and why).
When you change production behavior, search the existing tests for assertions of the old behavior
(grep the changed method and its callers) and run those complete test classes, not only new ones; a
test that asserts the old behavior is updated with a stated reason. On rework, read the context file
named by `GW2_REWORK_CONTEXT` first.
Normally implement meaningful tests/fixtures with code. A scoped approved issue
or session may give independent QA that ownership; honor the override. Run narrow
checks locally and let CI run full default suites. Report actual commands/results,
local checks still needed, unresolved gaps and reasons for changed/weakened
assertions. Keep the temporary workflow report current before lengthy checks.
The workflow commits, pushes and publishes PR evidence.

## Checks and care

Before finishing, run the affected gates and report their exact results: in `backend/`
`./mvnw test` (includes Checkstyle, Error Prone and the ArchUnit architecture rules), in
`frontend/` `npm run lint`, `npm run type-check` and the relevant Vitest files. If a gate
rejects your change, fix the code. Never edit gate configuration, the ArchUnit store or the
lint configuration yourself ([AGENTS.md](AGENTS.md#automated-quality-gates)). If a rule seems wrong,
describe it in the report and the PR instead of working around it.

Use the Test strategy for meaningful assertions. CI supplies an isolated
PostgreSQL service for database-backed `*Test` cases; `*IT` remains outside
the default naming set. Browser smoke/live API checks require their explicit
setup/evidence. Never claim unrun checks passed or use real owner credentials
for routine tests.

Preserve unrelated maintainer edits. Keep the diff focused; separate facts,
inferences and recommendations. Return deliverables to the main project and
stop owned processes/remove disposable worktrees after use. Keep secrets,
temporary reports and scratch output out of tracked files.
