## Story ID

STORY-APP-006

## Title

Extract the Trading Post price refresh application use case

## Status

TODO

## Milestone

milestone-03

## Goal

Introduce a named Trading Post price-refresh application service and route existing price-refresh orchestration through it, preserving operation selection and behavior.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md section 7, supplied Phase 3: named Trading Post price-refresh service exit criterion and application-service migration objective.
- docs/TARGET_ARCHITECTURE.md section 8 (Application Layer) and section 25 (Application Tests).
- docs/CURRENT_ARCHITECTURE.md section 5.4 (InitialSetupService price synchronization).
- STORY-APP-004 and STORY-APP-005 Constraints: Trading Post price refresh remains separately scoped.

## Context

The existing Phase 3 stories cover crafting calculations, Ectoplasm, account refresh, global refresh and graph rebuild. They do not cover the required Trading Post price-refresh service. CURRENT_ARCHITECTURE documents InitialSetupService invoking discovery and profit price synchronization. Exact additional callers and their behavior must be established during implementation, not assumed from this planning pass.

## Acceptance Criteria

1. A named application service owns orchestration of existing Trading Post price-refresh operations, including the documented discovery and profit variants. It has no JavaFX dependency and uses replaceable infrastructure collaborators.
2. Establish existing price-refresh call sites, inputs, operation selection, ordering and failure behavior during implementation. Route existing UI/controller price-refresh calls through the application boundary and record the migrated callers in Result; preserve their distinct scopes rather than refreshing every price indiscriminately.
3. Route InitialSetupService's documented discovery/profit price-refresh steps through the service, preserving setup's existing sequence and failure behavior. Do not substitute a broader workflow for those steps.
4. Preserve existing item relevance, quote and persistence semantics. Keep infrastructure synchronization in its adapter and calculations in the domain; do not introduce a competing pricing calculation.
5. Preserve affected UI background execution, refresh triggers, selection/state preservation and success/error presentation. Do not introduce new controls or scheduling behavior.
6. Update the affected flow descriptions in docs/CURRENT_ARCHITECTURE.md and record verification evidence and limitations in Result. Do not declare Phase 3 complete.

## Required Tests

- Fake-adapter application tests verify operation selection, forwarded scope/inputs, existing ordering where applicable, and success/failure propagation without live HTTP or a database.
- A focused setup regression verifies that substituting the application service preserves its existing price-refresh steps and surrounding operation sequence.
- For migrated UI callers, use controlled service outcomes with the existing JavaFX verification capability to check delegation, failure presentation and preservation of relevant view state. Record unavailable prerequisites or limitations.
- Run affected existing synchronization/persistence regressions if production adapter behavior is touched; no automatic broad test expansion.

## Constraints

- Behavior-preserving Phase 3 extraction only; no new domain rules, synchronization policy, threading redesign or persistence redesign.
- Keep tradeable-item synchronization distinct from price refresh. Global refresh and graph rebuild remain owned by STORY-APP-005; account refresh by STORY-APP-004; Ectoplasm by STORY-APP-003.
- Limit changes to the price-refresh boundary and necessary callers/test seams. Do not broaden into unrelated view migrations or cleanup.

## Dependencies

None.

## Definition of Done

The named price-refresh service is used by the existing price-refresh callers, behavior is preserved with targeted verification evidence, and affected architecture documentation and Result describe the resulting boundary and any limitations.

## Result

Not started.

## Blockers

None.
