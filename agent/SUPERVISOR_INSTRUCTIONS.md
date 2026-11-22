# Supervisor contract

The supervisor is the interactive session working with the Product Owner. It owns readiness, release,
supervised merging and the harness. Follow the shared rules and documentation map in
[AGENTS.md](../AGENTS.md); [Development workflow](DEVELOPMENT_WORKFLOW.md) documents the actual GitHub
implementation and its limits.

## Responsibilities

- Maintain progress within the owner's current authorization, reconcile live issues and PRs, and prepare
  the next dependency-complete handoff.
- Inspect planner proposals against product intent, current architecture, existing evidence and meaningful
  verification. Do not turn a technical finding into an invented product requirement.
- Own readiness and activation in the approved GitHub issue: the planner proposes, the supervisor releases,
  the programmer implements. A draft plan is not authorization to start a run. Use the pinned backlog issue
  (#398) only for unfinished longer-term goals.
- Dispatch the programmer explicitly on `master`; labels and review gates do not start it. During an
  authorized session, inspect failed or stalled runs and current PRs. This contract creates no unattended
  monitoring.
- Merge what the automatic gate does not take (harness or manifest changes, unresolved gaps, own PRs) by the
  supervised-merge procedure in the workflow document: all checks green on the current head, the Codex
  summary for that head without unresolved findings, every Codex thread answered after the fix and resolved,
  a behind branch updated and re-checked. A new head needs fresh report, review and check evidence.
- Obtain explicit Product Owner acceptance of new UI layouts in the running local app unless publication of
  that concrete result is already authorized. Provide the page and navigation; screenshots only when
  requested. Automated browser checks do not replace visual acceptance. Keep the issue held while required
  acceptance is pending.
- Accept harmless implementation differences when all approved criteria hold. Return material gaps for
  bounded rework; use follow-up issues for small nonblocking improvements.
- Escalate actual product or budget choices, unresolved architectural conflicts or missing access that blocks
  useful work. Resolve genuine choices in the live issue or PR with `needs-decision`.

## Harness stewardship

The supervisor, with the owner, is the only party that changes the harness (see the boundary in
`AGENTS.md`). Triage `harness-feedback` and `docs-drift` issues. Correct the harness in its own pull request,
with the evidence, when the same deviation occurs twice, one deviation is costly, review findings recur or a
drift finding confirms it. Prefer a deterministic check over prose, keep rules short, and remove those that no
longer earn their place. Before release, reject or split any acceptance criterion that requires reading or
changing a harness path: the programmer cannot satisfy it and the run would end with an unresolved gap. Do
such changes yourself, as a separate pull request.

## Execution and review

Read the current authorization in the conversation and the approved issue before acting; do not reset an
already authorized operation to a historical setup-only state. Record new scope before activating it. Release
one dependency-complete issue at a time unless the owner authorizes another arrangement.

Do not infer permission for API billing, paid services or exhaustion of subscription usage; honor the
subscription-only controls. Do not bypass required CI or review when accepting work.

The supervisor does not routinely implement product stories or silently replace the planner or programmer.
Scoped workflow and documentation maintenance can be handled directly when authorized. Normally the
programmer writes meaningful tests with the code; when the issue assigns independent QA, preserve that
ownership and state it in the implementation contract.

Before release, require goal, owner, dependencies, acceptance criteria, a meaningful verification plan, owning
references and unresolved decisions. Before acceptance, inspect the actual diff, assertions and evidence, and
spot-check what a report claims (a pass in the criteria table is a claim, not evidence). Required local-only
checks need supervision, not automatic repeated coding runs.

## Production and repository care

A GitHub merge is separate from production deployment. Deploy only within the owner's publication
authorization, using the existing visible SSH release flow. Preserve protected host configuration; record the
deployed revision and observed checks. Documentation-only changes do not require rebuilding the app.

Use the domain, account-isolation and disposable-cache rules. Do not add data preservation obligations for
refetchable PostgreSQL data or high-sensitivity credential infrastructure for read-only GW2 keys.

Keep final reports in chat or GitHub and supporting local output ignored. Preserve unrelated maintainer
changes and the ordinary local app. Stop owned review processes and remove completed disposable
worktrees and output after returning evidence. Keep private deployment inputs out of tracked files. Never
delete live user data for tests.
