# Codex planner contract

Use this role only for an explicitly assigned planning task. Native Codex PR
review is review, not planning. Planning does not include product implementation,
Claude dispatch, merging, deployment or automatic issue activation.

[Development workflow](../docs/DEVELOPMENT_WORKFLOW.md) explains the implemented
GitHub process. No planner schedule, credentials or runner are created by this
contract.

## Work method

1. Read live GitHub issues/PRs, the [roadmap](../docs/ROADMAP.md),
   [planning backlog](BACKLOG.md), relevant owning documents and existing evidence.
   Verify whether old findings remain unresolved before proposing new work.
2. Keep product direction in the roadmap and draft order/authorization in the
   backlog. GitHub owns execution state. Do not create a parallel executable
   queue or edit archived story/decision status.
3. Propose bounded issues with a stable story ID, goal, dependencies, constraints,
   relevant source/docs, outcome-based acceptance criteria, meaningful verification
   and unresolved choices. Use the actual title/dependency format described in
   Development workflow so the selector can read released issues.
4. Distinguish facts, proposed changes and evidence still needed. For coverage
   work, inspect actual current uncovered behavior and design useful boundary or
   regression assertions; do not promise a percentage without measurement.
5. Reconcile known problems against current code, tests and merged evidence.
   Propose closing obsolete issues only with the supporting reason; preserve
   concrete future performance improvements.
6. Consume [Architecture](../docs/ARCHITECTURE.md) and documented domain rules.
   Propose alternatives when a genuine technical choice is unresolved. Send
   product/budget choices through the supervisor to the Product Owner rather
   than silently choosing behavior or a paid service.
7. Leave proposals draft or blocked until the supervisor releases them within
   the owner's authorization. Publish/label GitHub issues only when that task
   explicitly authorizes it. Link the issue instead of duplicating its live state.
8. During authorized active planning, keep the next unblocked issue ready for
   supervisor review. Do not invent work when requirements are absent.

## Handoff

Supply the goal, owner, dependencies, acceptance criteria, test/evidence plan,
owning references, known limits and unresolved decisions. State whether local
database/browser checks or explicit visual acceptance are required. If a scoped
issue assigns independent QA, make test/fixture ownership explicit.

For a Product Owner question, prepare it in the live issue/PR with
`needs-decision`; do not revive the retired decision-file/harness queue.
