## Story ID

STORY-WEB-023

## Title

Superseded backend Ectoplasm calculation migration

## Status

SUPERSEDED

## Milestone

milestone-05

## Goal

Record that the previously planned migration of the Ectoplasm Salvage browser calculation to the backend must not be implemented.

The current frontend-owned Ectoplasm calculation is intentional and is the canonical implementation.

## Authoritative Source Documents / Sections

- Product Owner resolution establishing the frontend-owned Ectoplasm calculation as the canonical implementation.
- Product Owner resolution retiring the JavaFX user interface.
- `STORY-WEB-022`, which records and verifies the current locally calculating Ectoplasm browser screen.

## Context

This story originally proposed moving Ectoplasm Salvage economics from the browser into a new backend calculation operation.

That architecture has been superseded by a Product Owner decision.

The Ectoplasm calculation is sufficiently simple to remain in the browser. The current browser implementation obtains the required item metadata, Trading Post prices and account Luck data through the existing API boundaries and performs the Ectoplasm calculation locally.

The former backend-owned Ectoplasm calculation flow is obsolete and must not be restored.

The JavaFX user interface is also being retired now that the browser frontend provides the application's user-facing screens. JavaFX compatibility therefore does not justify retaining or recreating the former backend Ectoplasm calculation.

Any architecture, roadmap, domain specification, story, test, or follow-up finding that still describes the backend-owned Ectoplasm calculation or JavaFX/HTTP Ectoplasm consistency as the intended target must be reconciled with these newer Product Owner decisions.

## Acceptance Criteria

1. Do not introduce a new backend Ectoplasm calculation operation.
2. Do not move the current Ectoplasm economic calculation out of the browser.
3. Do not restore the former `/api/ecto/salvage` calculation flow.
4. Preserve the current frontend-owned Ectoplasm calculation and its existing economic behavior.
5. Existing API boundaries required by the browser for item metadata, Trading Post prices and account Luck remain available.
6. Unused frontend API clients, types, backend endpoints, backend calculation code, tests, and other artifacts belonging exclusively to the superseded backend Ectoplasm flow may be removed when they have no remaining consumer.
7. JavaFX-specific Ectoplasm code and compatibility requirements do not need to be preserved when JavaFX is removed.
8. Shared backend or domain functionality that is still consumed elsewhere in the application must be preserved.
9. Documentation that still identifies backend-owned Ectoplasm calculation or JavaFX compatibility as the target architecture must be updated to reflect the current Product Owner decisions.

## Required Tests

No implementation tests are required for the superseded backend migration described by the original story.

Any cleanup resulting from this decision must run the relevant existing frontend, backend, and browser checks necessary to demonstrate that currently supported functionality remains intact.

The current Ectoplasm browser calculation must continue to be covered by its focused component and browser checks.

## Constraints

- Do not restore or introduce backend-owned Ectoplasm calculation behavior.
- Do not remove shared backend/domain functionality merely because the obsolete Ectoplasm flow or JavaFX also consumed it.
- Do not change the current Ectoplasm economic rules as part of architectural cleanup.
- Do not recreate JavaFX compatibility.
- Cleanup should remove only code, contracts, tests, resources, dependencies, and documentation that are demonstrably obsolete or should update documentation that conflicts with the current architecture.

## Dependencies

None.

## Definition of Done

This story is superseded and requires no backend Ectoplasm migration.

The repository and planning documents no longer treat the backend-owned Ectoplasm calculation or JavaFX compatibility as the intended architecture, and any resulting cleanup is handled without changing the current frontend-owned Ectoplasm behavior or unrelated shared functionality.

## Result

SUPERSEDED.

The Product Owner intentionally retained the Ectoplasm calculation in the browser and retired the planned backend calculation migration.

The JavaFX interface is also being retired.

The original implementation direction of STORY-WEB-023 must therefore not be executed.

## Blockers

None.