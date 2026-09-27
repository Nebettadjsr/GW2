## Story ID

STORY-WEB-015

## Title

Simplify Profit purchase details and identify concrete blocking materials

## Status

TODO

## Milestone

milestone-05

## Goal

Make selected Profit details show the purchases needed for counted crafts and concrete blocking causes without redundant labels or introductory prose.

## Authoritative Source Documents / Sections

- docs/DOMAIN_SPEC.md section 2.1.1: purchase basis, useful blocking information and concise presentation.
- docs/TARGET_ARCHITECTURE.md sections 12-13: backend authority and truthful fresh resolution.
- Supplied docs/ROADMAP.md Phase 5: Profit resolution and special states.
- agent/stories/STORY-WEB-007-profit-resolution-detail-view.md, Result: distinct material-list bases and node reasons.
- agent/stories/STORY-WEB-011-profit-controls-text-cleanup.md: existing presentation scope.
- agent/product-owner-requests/Request-010-polish-craft-profit.md.
- docs/FRONTEND_UX_GUIDELINES.md applicable detail, state and accessibility guidance; docs/TEST_STRATEGY.md sections 12 and 36.

## Context

The completed detail story explicitly retained two purchase-list bases and generic node labels. Request-010 now narrows the visible purchase list to counted crafts and requires item-specific missing-price explanations. This is incremental product presentation work, not another resolution model. WEB-014 handles reported integration failures; DOM-023 owns fee integration.

## Acceptance Criteria

1. When buying is enabled, present backend-provided materials still to buy for the already calculated craft count, including item identity, quantity and relevant supplied price information. Remove the separate FOR ONE FURTHER CRAFT section. Do not substitute the fresh single-output tree or its costs for counted-craft purchases, aggregate inclusive node costs or invent missing totals.
2. Review selected-detail labels and remove redundant Buying is off, Over the buy limit and Not blocked labels wherever their information is already clear. Keep concrete useful causes and distinguish limits on further crafting from invalidation of counted crafts. Show the affected required purchase and supplied cost/budget facts for budget blocks where available; do not manufacture amounts.
3. Missing-price explanations identify the affected item by backend name or item ID beside the relevant explanation/node. Trace existing authoritative item/reason information through the existing application/HTTP/presentation boundaries if it is lost. Never guess the item from a generic root reason or imply that a fresh tree proves the cause of an earlier counted-craft stop. Preserve truthful calculation bases and report any unmet context requirement explicitly rather than claim completion.
4. Remove the exact introductory sentence identified in DOMAIN_SPEC section 2.1.1. Retain accessible controls, meaningful errors, special/unavailable states, null-versus-zero distinctions and the existing temporary cycle diagnostic.
5. Reuse existing detail/tree components and response facts. Preserve valid selection, response association, gross values and non-TP control behavior. Shared changes preserve Discovery presentation and JavaFX behavior. Record delivered behavior and targeted evidence in Result and update affected implementation documentation.

## Required Tests

- Targeted component fixtures for counted-craft purchases with quantities/prices, empty and unavailable lists, buying disabled and removal of the extra section and introductory sentence.
- Targeted rendering checks for completed crafts with a subsequent budget/price limit, nested affected-item identity including name fallback to item ID, unknown/unavailable states, and absence of redundant success/status labels while useful causes remain.
- If application/transport changes are needed, focused projection tests prove the existing backend item/reason facts survive unchanged without new calculation semantics.
- Explicit local browser smoke for selected details at wide/narrow widths, keyboard access and item-specific price/budget explanations; use controlled fixtures for rare states and identify their limits. Confirm normal live-backend selection still displays its tree.
- Directly affected tests locally; complete suites remain the GitHub CI gate under TEST_STRATEGY section 36.

## Constraints

- No new domain calculation, independent browser economics, resolution-basis change, arbitrary cleanup or speculative diagnostics.
- Do not change the active WEB-012 story or CURRENT_STORY.md. No broad regression mandate or Phase 5 closure claim.

## Dependencies

Completed STORY-WEB-007, STORY-WEB-008 and STORY-WEB-011. Queue after WEB-014 for integration continuity; presentation work has no unresolved decision prerequisite.

## Definition of Done

Counted-craft purchase information and item-specific blocking causes are understandable and verified, redundant content is removed without losing useful facts, evidence and limitations are recorded, and the story revision passes the CI gate.

## Result

Not started.

## Blockers

None.
