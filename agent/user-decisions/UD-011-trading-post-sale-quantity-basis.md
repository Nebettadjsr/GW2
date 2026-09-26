## Status

OPEN

## Decision Needed

Choose the assumed sale grouping for Request-008's copper-accurate profitability model:

1. For crafting, calculate fees for each individual output item, each recipe execution's output batch, or the complete output of all counted crafts as one sale? If the total uses a different grouping from the per-craft figure, may total profit differ from per-craft profit multiplied by craft count? The existing DOMAIN_SPEC section 27 establishes that multiplication; changing it needs an explicit decision.
2. For Ectoplasm's fractional expected Dust yield, use the net proceeds of selling one whole Dust item multiplied by the unchanged expected yield, or another explicitly specified sale-quantity model? An expected fractional drop is not itself a real Trading Post sale transaction.

These questions concern the application's modeled sale, not the game's factual fee rates or rounding algorithm. A single consistent policy may answer both.

## Why This Is Needed

Request-008 requires actual copper-level fees but supplies only a one-item example. Rounding fees on different sale quantities can produce different results. The calculator has output counts and simulated craft counts, while Ectoplasm uses fractional expected yield; no existing decision specifies their sale grouping. Choosing one silently would invent product economics.

## Context

The new request supersedes DOMAIN_SPEC section 25/DQ-001's fee exemption and section 46's approximate multiplier. The accepted requirement is recorded at those owners. Sections 24, 27 and 28 establish output quantity, total-profit multiplication and craft count; sections 45-47 establish expected-value salvage calculations. Existing material opportunity-cost, acquisition, eligibility and resolution rules remain unchanged by this decision.

The supplied backlog records shared CraftingPlanner results consumed by Profit, Discovery and JavaFX, and CURRENT_ARCHITECTURE section 5.3 records EctoSalvageService invoking EctoSalvageCalculator for four scenarios. No implementation was inspected in this planning pass.

## Blocks

- Completing planning coverage and resolving agent/product-owner-requests/Request-008-trading-post-fees-in-profit-calculations.md.
- Fee integration into crafting per-craft/total profit, informational Discovery economics and downstream API/browser/JavaFX presentation.
- Copper-accurate fee integration into the Ectoplasm expected-value model.

Does not block STORY-DOM-022's explicit-sale fee primitive, the active API-009, or existing independent Phase 5 presentation, non-TP-policy, Discovery and Ectoplasm transport/browser work. STORY-WEB-013 can expose the existing service; it must not decide this policy or claim the new fee accuracy is delivered.

## External Input Possibly Required

The Product Owner must select the modeled sale grouping. Implementation must separately verify actual game rounding and minimum-fee behavior against reliable evidence, including distinguishing listing and exchange fees; that factual investigation is STORY-DOM-022, not a requested human policy answer.

## User Decision

TODO

## Resolution

TODO
