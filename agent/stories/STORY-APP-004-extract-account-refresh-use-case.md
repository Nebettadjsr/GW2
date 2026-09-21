## Story ID

STORY-APP-004

## Title

Route account refresh through an application service

## Status

TODO

## Milestone

milestone-03

## Goal

Introduce a named account-refresh application use case, migrate Gw2App's account sync action to it, and resolve the unused AccountRefreshService orchestration.

## Authoritative Source Documents / Sections

- Supplied docs/ROADMAP.md section 7, Phase 3: account-refresh application service, Gw2App handler migration, and AccountRefreshService dead-call exit criteria.
- docs/TARGET_ARCHITECTURE.md section 8 (Application Layer) and section 25 (Application Tests).
- docs/CURRENT_ARCHITECTURE.md sections 5.4, 6 and 7 (account sync calls, unused service and UI execution/reporting).
- docs/KNOWN_PROBLEMS.md section 8 (AccountRefreshService has no Gw2App call site).

## Context

The documented Sync Account action directly invokes account bank/material/recipe synchronization and character crafting/recipe synchronization. AccountRefreshService.refreshAll() duplicates a subset of setup's account synchronization but is unused by Gw2App. Profit and Discovery already have application services; the existing Ectoplasm story does not cover account refresh.

## Acceptance Criteria

1. A named application service owns the existing account-refresh orchestration, including the account bank, materials, recipes and character synchronization operations currently invoked by the Sync Account action. Establish the exact existing call order and failure behavior during implementation and preserve them.
2. Gw2App's Sync Account handler delegates to this service instead of calling sync.*, repo.* or craft.* directly. Preserve background execution, UI-thread status updates and existing success/failure reporting.
3. Resolve the existing AccountRefreshService dead-call situation by adapting and wiring it as the application use case or replacing it and removing the unused implementation. Keep one authoritative account-refresh orchestration, without competing unused service code.
4. The service has no JavaFX dependency and coordinates replaceable infrastructure collaborators; it does not acquire presentation responsibilities or introduce domain calculations.
5. Preserve first-time setup behavior. If consolidating shared account operations changes InitialSetupService, retain its existing operation selection, order and failure behavior; do not substitute the broader account-refresh workflow blindly.
6. Update docs/CURRENT_ARCHITECTURE.md's affected account flow and docs/KNOWN_PROBLEMS.md section 8's dead-call entry to match the result. Record verification evidence and limitations without declaring other synchronization use cases or Phase 3 complete.

## Required Tests

- Fake/in-memory-adapter application tests verify the account action's existing operation selection and order, successful completion, and failure propagation/short-circuit behavior without live HTTP or a database.
- A targeted deterministic JavaFX check with controlled service behavior verifies Sync Account delegates and presents success/failure results while retaining background execution and UI-thread updates. Reuse the established UI verification capability; record any unavailable prerequisite or limitation.
- If shared setup orchestration changes, add a focused fake-adapter regression protecting its existing operation selection and ordering. Run affected existing account/character synchronization tests where production adapter behavior is touched.

## Constraints

- Behavior-preserving Phase 3 application-service extraction only.
- Keep existing synchronization persistence and domain semantics; do not change API-key persistence, add scheduling, or redesign threading.
- Limit setup edits to necessary account-orchestration reuse. Global data refresh, Trading Post price refresh and graph rebuild remain separately scoped work.

## Dependencies

None.

## Definition of Done

The account action consumes a tested application use case, the dead account-refresh implementation is wired or removed, existing behavior is preserved, and affected authoritative documentation and story Result contain the outcome and verification evidence.

## Result

Not started.

## Blockers

None.
