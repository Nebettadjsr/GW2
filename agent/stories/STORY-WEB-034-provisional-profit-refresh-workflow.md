## Story ID

STORY-WEB-034

## Title

Show provisional Crafting Profit results and finish bounded asynchronous refresh

## Status

BLOCKED

## Milestone

milestone-05

## Goal

Render usable cached Profit results immediately and replace them automatically after verified input refresh while preserving the user's current view and safely terminating failed recovery.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md, supplied Phase 5 browser performance and normal-feature-refresh criteria.
- docs/TARGET_ARCHITECTURE.md sections 9.1, 10.2 and 17; resolved AR-007.
- agent/user-decisions/UD-015-trading-post-refresh-performance-budget-scope.md, required UI and recovery behavior.
- docs/DOMAIN_SPEC.md sections 2.2.1, 25, 39 and 40.
- docs/TEST_STRATEGY.md sections 12, 34 and 36.
- docs/QUALITY_METRICS.md, Test coverage KPI and Establishing the clean baseline.
- agent/stories/STORY-APP-014-cached-profit-refresh-coordination.md, delivered HTTP contract.
- agent/stories/STORY-WEB-033-recover-overlapping-account-refresh-scopes.md, supersession Result.

## Context

UD-015 replaces waiting for freshness before showing a result. The backend supplies authoritative values and captured-input metadata. The browser owns presentation, polling and correlated resubmission, not freshness inference or domain calculations.

## Acceptance Criteria

1. Render successful cached results even when provisional. Reuse the existing TP-warning visual design for “Stale data – refreshing…” with a green animated border and an explanation that cached results remain displayed until refresh finishes. Show loading/empty for 202 without usable data; never fabricate rows.
2. Poll advertised task references and resubmit with current settings after relevant work completes. Use the backend's required-source and TP coverage checks; support successive account/TP work without assuming task success means all inputs are fresh. Remove the indicator only after rendering the final calculation for the current context.
3. Preserve calculation settings, filters, valid row selections and sorting through result replacement; fall back gracefully when a selection no longer exists. Correlate calculation, task and detail responses so late responses cannot overwrite newer scope/settings/selection. Never display a newer-input detail as explanation of an older provisional row; obtain/associate the matching row context.
4. Maintain one five-minute overall automatic recovery deadline including polling, successive tasks and recalculation. Task changes do not reset it. Failed/unknown/expired tasks, transport failure and deadline exhaustion stop animation, retain last usable rows and expose failure/retry; no-cache failure remains explicit. An explicit retry starts a new bounded cycle and rechecks freshness without assuming backend cancellation or starting overlapping writes.
5. Render backend monetary values, states and resolution quantities unchanged. Keep account and TP freshness distinguishable. Normal refresh requires no System Status visit, and other feature policies remain intact.

## Required Tests

- Real HTTP-client request/response and controlled-time component checks covering provisional-to-final, 202-to-result, successive insufficient-scope account work, TP work after account changes, preserved controls and final indicator removal.
- Regressions for reordered task/calculation/detail responses, settings changes mid-refresh, failure with/without cached rows, unknown task, transport failure, five-minute cumulative deadline and explicit retry. Independent backend fixtures must make any accidental monetary recalculation detectable.
- Run affected existing HTTP/Profit/detail tests, type check and production build. Tests use controlled transport and clocks without live API/database/display dependencies.
- Explicitly run a focused real-browser Profit refresh smoke check using the current build and controlled responses for deterministic stale/failure cases; assert visible rows, indicator, preserved controls, safe detail association and termination. Separately verify the browser connects to the delivered backend envelope. These browser checks are outside CI and cannot substitute for real-data performance acceptance.

## Constraints

- Follow the established backend envelope and UD-015 design; no independent domain calculations, direct GW2 requests or new numerical refresh budget.
- No cosmetic smoke-reporting cleanup, general client-test expansion, JavaFX work, agent workflow edits or CI changes.

## Dependencies

- STORY-APP-014

## Definition of Done

Affected deterministic tests, build and explicit browser checks pass with evidence and limitations recorded. Review affected coverage reports and unresolved gaps against the 90% warning KPI. Update current behavior documentation where delivered behavior changed. Full CI is verified by the pipeline after acceptance; real-data timing remains STORY-PERF-004.

## Result

Not started.

## Blockers

Waiting on dependencies: depends on STORY-APP-014, which is not DONE (currently TODO)
