## Story ID

STORY-WEB-032

## Title

Verify root sourcing in browser resolution details

## Status

TODO

## Milestone

milestone-05

## Goal

Add browser coverage that verifies the frontend renders backend-provided root-sourcing details when a requested recipe is resolved through a different actual root recipe.

## Authoritative Source Documents / Sections

- Supplied Phase 5 roadmap excerpt: finish browser coverage and acceptance; render backend-provided resolution details.
- `docs/TARGET_ARCHITECTURE.md` §10.2, Resolution tree.
- `docs/TEST_STRATEGY.md` §12.2, Frontend verification levels.
- `agent/stories/STORY-WEB-024-repair-fresh-build-smoke-contracts.md`, Follow-up Finding F004.

## Context

The existing browser smoke fixtures use a tree whose root is the requested recipe, so they do not exercise the conditional root-sourcing presentation. The finding reports that `describeRootSourcing` has no browser coverage. Cover the alternate-root case with backend-provided detail and keep expected presentation independent of values derived from the page.

## Acceptance Criteria

1. A browser-level check supplies a requested recipe whose resolution tree has a different actual root recipe and verifies the corresponding root-sourcing description is rendered.
2. The check verifies the requested recipe identity and actual root sourcing remain distinct and consistent with the supplied backend detail.
3. Existing root-equals-requested-recipe behavior remains covered and does not display a misleading alternate-root description.

## Required Tests

- Add or update a controlled browser smoke fixture covering both the alternate-root and same-root cases, and run the affected smoke check.
- Run relevant frontend component tests and review affected coverage; report any unresolved coverage gap for the changed presentation path.

## Constraints

Do not recalculate or infer resolution sourcing in the frontend. Preserve the backend-provided resolution contract and existing product presentation. Keep the change scoped to browser coverage and directly required test hooks.

## Dependencies

None.

## Definition of Done

Both root identity cases are meaningfully verified in a browser, expected values are independently established by the fixture, affected coverage and unresolved quality gaps are reviewed, and the CI gate is green.

## Result

Not started.

## Blockers

None.
