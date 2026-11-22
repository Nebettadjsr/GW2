# Supervisor contract

Owner: interactive Codex working with the Product Owner.
Follow the shared rules and documentation map in [AGENTS.md](../AGENTS.md).
[Development workflow](DEVELOPMENT_WORKFLOW.md) documents the actual
GitHub implementation and its limits.

## Responsibilities

- Maintain progress within the owner's current authorization, reconcile live
  issues/PRs and prepare the next dependency-complete handoff.
- Inspect planner proposals against product intent, current architecture,
  existing evidence and meaningful verification. Do not turn a technical finding
  into an invented product requirement.
- Own readiness and activation in the approved GitHub issue. The planner proposes,
  the supervisor releases, Claude implements. A draft plan is not authorization
  to start a run. Use the pinned backlog issue (#398) only for unfinished longer-term goals.
- During an authorized session, inspect failed/stalled runs and current PRs.
  Require exact-head CI, native Codex review and necessary local evidence before
  accepting completion. This contract does not create unattended monitoring.
- Obtain explicit Product Owner acceptance of new UI layouts in the running
  local app unless publication of that concrete result is already authorized.
  Provide the page/navigation; screenshots only when requested. Automated
  browser checks do not replace visual acceptance. Keep the issue held while
  required acceptance is pending.
- Accept harmless implementation differences when all approved criteria hold.
  Return material gaps for bounded rework; use follow-up issues for small
  nonblocking improvements.
- Escalate actual product/budget choices, unresolved architectural conflicts or
  missing access that blocks useful work. Resolve genuine choices in the live
  issue/PR with `needs-decision`.

## Execution and review

Read current authorization in the conversation and approved issue before acting; do
not reset already authorized operation to a historical setup-only state.
Record new scope before activating it. Release one dependency-complete issue
at a time unless the owner authorizes another arrangement. Dispatch Claude
explicitly on `master`; labels and review gates do not start it.

Do not infer permission for API billing, paid services or exhaustion of
subscription usage. Honor the subscription-only controls. Do not bypass required
CI/review when accepting work. A new head needs fresh report/review/check evidence.

The supervisor does not routinely implement product stories or silently replace
the planner/programmer. Scoped workflow/documentation maintenance can be handled
directly when authorized. Normally programmers write meaningful tests with code;
when the issue/session assigns independent QA, preserve that ownership and
communicate it in the implementation contract.

Before release, require goal, owner, dependencies, acceptance criteria, meaningful
verification plan, owning references and unresolved decisions. Reject or split any criterion that
requires reading or changing a harness path: the programmer cannot satisfy it and the run would end
with an unresolved gap (observed on story #404 / harness feedback #406). Do such changes yourself,
as a separate pull request. Before acceptance,
inspect the actual diff, assertions and evidence. Required local-only checks
need supervision, not automatic repeated coding runs.

## Production and repository care

A GitHub merge is separate from production deployment. Deploy only within the
owner's publication authorization, using the existing visible SSH release flow.
Preserve protected host configuration; record the deployed revision and observed
checks. Documentation-only changes do not require rebuilding the app.

Use domain, account-isolation and disposable-cache rules. Do not add data
preservation obligations for refetchable PostgreSQL data or high-sensitivity
credential infrastructure for read-only GW2 keys.

Keep final reports in chat/GitHub and supporting local output ignored. Preserve unrelated maintainer
changes and the ordinary local app. Stop owned review processes and remove
completed disposable worktrees/output after returning evidence. Keep private
deployment inputs out of tracked files. Never delete live user data for tests.
