## Story ID

STORY-WEB-020

## Title

Exercise backend total sell value in the shared layout smoke fixture

## Status

TODO

## Milestone

milestone-05

## Goal

Make the Crafting Profit and Discovery layout browser check render and measure a backend-supplied total sell value instead of a placeholder in every fixture row.

## Authoritative Source Documents / Sections

- `agent/stories/STORY-WEB-019-layout-smoke-navigation-coverage.md`, Follow-up Findings F001.
- `docs/TEST_STRATEGY.md` §12.1–12.2, controlled fixture values and browser layout checks.
- Supplied `docs/ROADMAP.md` Phase 5, frontend rendering from backend-provided values and frontend tests.

## Context

The shared `profitRows()` fixture omits `totalSellValueCopper`. The layout check therefore does not exercise the money presentation for the Total sell value column on either screen.

## Acceptance Criteria

1. Supply `totalSellValueCopper` in representative shared Profit/Discovery fixture rows so the Total sell value cells render monetary values during layout checks.
2. Make at least one supplied total deliberately differ from a simple multiplication of other fixture fields, preserving the check's ability to expose client-side derivation.
3. Preserve existing gain, loss, and unavailable fixture cases and their intended presentation; do not add a browser calculation.
4. Confirm the layout check reaches its relevant contrast and responsive assertions with the populated column.

## Required Tests

- Run `npm run smoke:layout` in a controlled browser runtime and verify the populated Profit and Discovery rows and existing layout/contrast assertions.

## Constraints

- Change fixture data and only directly necessary smoke assertions; do not alter authoritative backend values or frontend economic calculations.
- Keep the check focused on presentation rather than domain arithmetic.

## Dependencies

- STORY-WEB-019 (source of the finding; finish its layout smoke restructure first).

## Definition of Done

Acceptance criteria are met, the named browser smoke check passes, and Result records the fixture and verification evidence.

## Result

Not started.

## Blockers

STORY-WEB-019 must complete first.
