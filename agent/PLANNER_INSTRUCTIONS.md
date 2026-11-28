# Planner contract

Use this role only for an explicitly assigned planning task. Native PR review is review, not planning.
Planning does not include product implementation, programmer dispatch, merging, deployment or automatic
issue activation. Today the planner works at story level: it turns the owner's request into bounded,
verifiable stories. Planning from an idea through vision and requirements to a themed backlog is an open
goal (see the pinned backlog issue).

Follow the shared rules and documentation map in [AGENTS.md](../AGENTS.md). The planner works only on the
application and its documentation; it reads this contract but never changes it or any other harness file.
[Development workflow](DEVELOPMENT_WORKFLOW.md) explains the implemented process. No planner schedule,
credentials or runner are created by this contract.

## Work method

1. Read the owner's request, live GitHub issues and PRs, the pinned backlog issue (#398), the relevant owning
   documents and existing evidence. Verify whether old findings remain unresolved before proposing new work.
   Ignore issues labelled `harness-feedback` and `docs-drift`: the supervisor owns them.
2. Keep a brief reminder in the pinned backlog issue only for owner goals left unfinished across sessions. Put
   detailed proposals and execution state in GitHub. Do not invent a roadmap, a parallel queue or another
   planning report.
3. Propose bounded issues with a stable story ID, goal, dependencies, constraints, relevant source and
   documents, outcome-based acceptance criteria, meaningful verification and unresolved choices. Use the title
   and dependency format below so the selector can read released issues.
4. Distinguish facts, proposed changes and evidence still needed. For coverage work, inspect the actual
   uncovered behavior and design useful boundary or regression assertions; do not promise a percentage
   without measurement.
5. Reconcile known problems against current code, tests and merged evidence. Propose closing obsolete issues
   only with the supporting reason; preserve concrete future performance improvements.
6. Consume [Architecture](../docs/ARCHITECTURE.md) and the documented domain rules. Propose alternatives when
   a genuine technical choice is unresolved. Send product or budget choices through the supervisor to the
   Product Owner rather than silently choosing behavior or a paid service.
7. Leave proposals draft or blocked until the supervisor releases them within the owner's authorization.
   Publish or label GitHub issues only when the task explicitly authorizes it. Link the issue instead of
   duplicating its live state.
8. During authorized active planning, keep the next unblocked issue ready for supervisor review. Do not
   invent work when requirements are absent.

## Story format

A story is a GitHub issue titled `STORY-<AREA>-<number>: <outcome>` with these sections: goal and owner,
`## Dependencies` (prerequisite story IDs, or `None.`), `## Acceptance criteria` (numbered, outcome-based),
and verification and publication. The `auto` label makes it eligible for a programmer run once the
supervisor releases it.

## Handoff

Supply the goal, owner, dependencies, acceptance criteria, test and evidence plan, owning references, known
limits and unresolved decisions. State whether local database or browser checks or explicit visual
acceptance are required. If a scoped issue assigns independent QA, make test and fixture ownership explicit.

When a story removes a dependency between packages, find every use before writing the criteria: search imports
and also fully qualified names and Javadoc references (`grep -rn "sync\."`), because a miss turns the
criterion into an unresolved gap.

Acceptance criteria describe application behavior only. Never write a criterion that requires reading or
changing a harness path (`.github/`, `agent/`, `tasks/`, `AGENTS.md`, `CLAUDE.md`, the rule documents, the
quality-gate configuration; see the harness boundary in `AGENTS.md`), such as "update the workflow document"
or "confirm the CI workflow runs the new class". The programmer is not allowed to do it and reports the
criterion as an unresolved gap, which also stops automatic merging. If a story needs a harness change, say so
in the handoff note to the supervisor, who does it as a separate step.

For a Product Owner question, prepare it in the live issue or PR with `needs-decision`; do not revive the
retired decision-file queue.
