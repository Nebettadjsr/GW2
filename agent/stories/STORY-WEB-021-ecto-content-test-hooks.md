## Story ID

STORY-WEB-021

## Title

Give Ectoplasm results stable content hooks for browser checks

## Status

TODO

## Milestone

milestone-05

## Goal

Let Ectoplasm browser and component checks identify the current frontend-calculated result regions without relying on styling classes or the always-rendered screen root.

## Authoritative Source Documents / Sections

- `agent/stories/STORY-WEB-019-layout-smoke-navigation-coverage.md`, Follow-up Findings F002–F003.
- Product Owner resolution establishing the frontend-owned Ectoplasm calculation as the canonical implementation.
- `docs/TEST_STRATEGY.md` §12.1–12.2, frontend state and browser verification.
- Supplied `docs/ROADMAP.md` Phase 5, Ectoplasm screen and rendering/interaction/state tests.

## Context

The Ectoplasm screen now intentionally performs its simple salvage calculation in the browser using the required item metadata, Trading Post prices and account Luck data.

The previous backend-calculated Ectoplasm screen is obsolete and must not be restored.

The screen's existing `ecto-screen` hook is always present and checks currently use presentational classes to identify populated calculation content. Stable semantic `data-test` hooks should identify the current data-driven regions instead.

The component also retains two scoped style rules for removed elements.

## Acceptance Criteria

1. Add stable `data-test` hooks to the current Ectoplasm screen's data-driven calculation/result regions so browser and component checks can identify when the required data has loaded and the frontend-owned result is available.
2. Update relevant Ectoplasm browser and component assertions to use those hooks for content readiness and result checks instead of style-only selectors where a stable hook is appropriate.
3. Verify the current frontend-owned behavior, including populated calculation results, input-driven recalculation, Trading Post price refresh behavior, account Luck presentation, and relevant warning/error states already supported by the screen. Tests must not introduce or require a backend Ectoplasm calculation operation.
4. A root hook alone must not count as evidence that the required data and calculated result are available.
5. Remove the unused `.result-conclusion` and `.tool-separator` scoped rules identified in the original finding without changing visible page behavior.

## Required Tests

- Run focused Ectoplasm component tests covering the current frontend-owned calculation and its relevant data/result states.
- Run the Ectoplasm and layout browser smoke checks that consume the new result hooks in a controlled browser runtime.
- Verify that the browser checks no longer depend on presentational style classes for result readiness where the new semantic hooks apply.

## Constraints

- Keep changes within Ectoplasm presentation, test hooks, and their checks.
- Do not introduce or restore a backend Ectoplasm calculation operation.
- Do not move the Ectoplasm economic calculation out of the browser.
- Preserve the current frontend-owned calculation behavior and economic rules.
- Preserve existing visual and accessible meaning.
- Do not preserve obsolete backend-calculated states merely because older tests or stories referenced them.

## Dependencies

- STORY-WEB-019 (DONE).

## Definition of Done

Stable semantic hooks identify the current frontend-calculated Ectoplasm result content, relevant component and browser checks use those hooks instead of presentation classes where appropriate, obsolete scoped styles are removed, and the named checks pass.

## Result

Not started.

## Blockers

None.