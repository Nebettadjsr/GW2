## Story ID

STORY-DOM-024

## Title

Align Ectoplasm expected-value economics and labels with the decided fee policy

## Status

TODO

## Milestone

milestone-05

## Goal

Apply the resolved percentage policy consistently to Ectoplasm expected-value profitability while preserving gross price displays and the existing fee-inclusive net-cost/Luck relationship.

## Authoritative Source Documents / Sections

- docs/DOMAIN_SPEC.md sections 25 and 45-47: shared policy, expected yields and Luck cost.
- agent/user-decisions/UD-011-trading-post-sale-quantity-basis.md: no separate transaction-rounding/grouping model.
- agent/stories/STORY-DOM-022-trading-post-sale-fee-calculation.md, Result: Ectoplasm consumer map.
- agent/stories/STORY-WEB-013-ectoplasm-salvage-page.md: existing service, four scenarios and browser transport owner.
- Supplied docs/ROADMAP.md Phase 5: Ectoplasm screen and shared backend.
- docs/TARGET_ARCHITECTURE.md section 12 and docs/TEST_STRATEGY.md section 36.

## Context

DOM-022 records an Ectoplasm-local rounded net-unit-price calculation before scaling fractional expected yield. The resolved answer makes transaction-level rounding irrelevant and keeps displayed prices gross. This story aligns the existing domain/service and JavaFX behavior; WEB-013 subsequently exposes those authoritative values through HTTP and the browser.

## Acceptance Criteria

1. Inspect the current four Ecto-buy/Dust-sell scenarios and align profit with section 25's 15% fee on corresponding expected gross Dust sale value, deducted once. Preserve yields, live quote source, acquisition modes/costs and scenario identities.
2. Keep market quotes and displayed gross recovered sale values unchanged by fees. Keep economic net cost and cost per 1000 Luck consistent with DOMAIN_SPEC sections 46-47; they remain economic costs derived from fee-inclusive profit, not market-price displays. No fractional expected yield is modeled as an actual sale transaction.
3. Reuse the shared backend percentage-policy owner from DOM-023 if available, or the established appropriate domain abstraction; do not force reuse of DOM-022's transaction-rounding primitive. Keep transport and presentation formula-free. Avoid creating competing percentage implementations.
4. Preserve available precision and distinguish expected-value assumptions, missing values, zero and losses. Align JavaFX labels and fee wording so gross quotes are not called net, and displayed profit is clearly after 15% TP fees.
5. Expose all required authoritative scenario values at the application boundary for WEB-013. If its HTTP/browser consumer exists by implementation time, verify it preserves these values and correct affected labels; do not duplicate its screen/route work.
6. Record observed integration and verification in Result and update implementation documentation. No new exact-sale fee accuracy or milestone-completion claim.

## Required Tests

- Focused Ectoplasm domain tests with independent expected amounts for all four buy/sell scenarios, fractional expected Dust yield, zero/loss outcomes and unchanged acquisition/yield assumptions. Verify gross quotes stay gross and fee-inclusive net cost/Luck values remain consistent.
- Focused service projection tests preserving scenario values and unavailable values without presentation recalculation.
- Explicit local TestFX check of affected JavaFX Ectoplasm labels and values. If the browser consumer already exists, an explicit local browser smoke compares rendered values with its backend response; otherwise that integration check remains WEB-013's required verification.
- Run only directly affected local tests; the CI gate owns complete regression suites.

## Constraints

- No changes to salvage yields, supported items, synchronization, quote source or new user controls.
- No copper-rounding/grouping investigation and no fee deduction from displayed gross market prices.
- Preserve existing domain/application boundaries and JavaFX coexistence. WEB-013 owns new HTTP/browser workflow implementation.

## Dependencies

Resolved UD-011 and completed STORY-DOM-022. Queue after DOM-023 for shared policy reuse; no new transport/browser prerequisite.

## Definition of Done

The Ectoplasm domain/service and JavaFX follow the decided economics and gross-price presentation, targeted checks are recorded, documentation is current and the CI gate is green.

## Result

Not started.

## Blockers

None.

