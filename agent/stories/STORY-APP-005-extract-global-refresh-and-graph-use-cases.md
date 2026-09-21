## Story ID

STORY-APP-005

## Title

Extract global data refresh and crafting graph rebuild application services

## Status

TODO

## Milestone

milestone-03

## Goal

Move the global synchronization action's orchestration into a named application service, with a named crafting graph rebuild use case, and make Gw2App delegate to that boundary.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md section 7, supplied Phase 3 objective and exit criteria for global data refresh, crafting graph rebuild and Gw2App handler migration.
- docs/TARGET_ARCHITECTURE.md section 8 (Application Layer) and section 25 (Application Tests).
- docs/CURRENT_ARCHITECTURE.md sections 5.4 (synchronization sequence), 7 (execution and reporting), and 9 item 4 (UI orchestration).

## Context

The documented Sync ALL tradeable Items action invokes tradeable-item synchronization, global recipe synchronization and CraftingGraphCache.rebuild directly. STORY-APP-004 covers account refresh and explicitly leaves global refresh and graph rebuild separately scoped. This story addresses those two closely related use cases without changing their existing behavior.

## Acceptance Criteria

1. A named global-data-refresh application service coordinates the existing action's tradeable-item synchronization, global recipe synchronization and graph rebuild in their existing order. Confirm exact operation selection and failure behavior during implementation and preserve them.
2. A named crafting-graph-rebuild application service owns invocation of the existing repository-backed cache rebuild. The global refresh service delegates its rebuild step to that use case; no competing graph-building algorithm is introduced.
3. Gw2App's global synchronization handler calls the application service instead of sync.*, repo.* or craft.* directly. Preserve background execution, JavaFX-thread status updates and success/failure reporting.
4. Both application services are free of JavaFX dependencies and expose replaceable infrastructure collaborators for deterministic application tests. Domain logic and persistence remain at their existing boundaries.
5. Preserve cache format, location and existing load-time auto-rebuild behavior. No new graph-rebuild button is required. Preserve first-time setup's distinct operation selection and ordering if shared orchestration is touched; do not replace setup with the global refresh workflow blindly.
6. Update affected sections of docs/CURRENT_ARCHITECTURE.md to describe the resulting boundary. Record verification evidence and limitations in Result without declaring all synchronization extraction or Phase 3 complete.

## Required Tests

- Fake-adapter application tests prove global refresh operation selection, ordering, one graph-rebuild delegation on success, and existing failure propagation/short-circuit behavior without live HTTP or a database.
- A focused graph-service test proves delegation to the existing cache rebuild and propagation of its failure without introducing a second calculation.
- A targeted deterministic JavaFX check with controlled service outcomes verifies global-action delegation and success/failure presentation while preserving background execution and UI-thread updates. Reuse the existing verification capability and record unavailable prerequisites or limitations.
- If setup orchestration or production cache behavior changes, run focused affected regressions; protect setup's distinct sequence with a fake-adapter check when touched.

## Constraints

- Behavior-preserving Phase 3 extraction only; no API, persistence, domain-rule, scheduling or threading redesign.
- Account refresh remains owned by STORY-APP-004. Trading Post price refresh remains separately scoped; tradeable-item synchronization in this global action does not by itself satisfy the price-refresh exit criterion.
- Do not broaden this work into unrelated view migrations or cleanup.

## Dependencies

None.

## Definition of Done

The global action delegates through tested application services for global refresh and graph rebuild, existing behavior is preserved, and the affected architecture documentation and story Result record the outcome and verification evidence.

## Result

Not started.

## Blockers

None.
