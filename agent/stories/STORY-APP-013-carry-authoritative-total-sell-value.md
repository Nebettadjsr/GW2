## Story ID

STORY-APP-013

## Title

Carry authoritative total sell value into the JavaFX Profit view

## Status

TODO

## Milestone

milestone-05

## Goal

Have the JavaFX Profit view display the backend-provided total sell value instead of deriving it from revenue and craftable count in the view.

## Authoritative Source Documents / Sections

- agent/stories/STORY-DOM-023-crafting-profit-fee-integration.md, Follow-up Findings F002.
- agent/stories/STORY-APP-011-display-authoritative-total-profit.md.
- Supplied docs/ROADMAP.md Phase 5 exit criterion: both JavaFX and web UIs are driven by the same backend.

## Context

DOM-023 records that CraftingProfitView multiplies revenue by craftableCount despite CraftResult.totalSellValueCopper already being the authoritative field. Carrying the supplied value avoids a second derivation in the JavaFX presentation path.

## Acceptance Criteria

1. Carry CraftResult.totalSellValueCopper through CraftingProfitController.UiRow into CraftingProfitView.
2. Display the supplied total sell value without recomputing revenue multiplied by craftable count in the view.
3. Preserve the gross-value meaning and existing unavailable/null handling.
4. Add focused verification that a distinctive supplied total is displayed unchanged.

## Required Tests

- Focused controller/view tests verifying totalSellValueCopper is passed through and displayed unchanged, including applicable unavailable-value behavior.
- Explicit local TestFX verification if the changed presentation is covered by the existing JavaFX verification path; record any unavailable interactive check as a limitation.

## Constraints

- Presentation/data-flow change only; do not change domain calculations, HTTP contracts, or JavaFX coexistence.
- No unrelated UI cleanup.

## Dependencies

Completed STORY-DOM-023 and STORY-APP-011.

## Definition of Done

JavaFX displays the authoritative supplied total sell value, targeted evidence and limitations are recorded, and the revision passes the GitHub CI gate.

## Result

Not started.

## Blockers

None.
