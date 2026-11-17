## Status

RESOLVED

## Decision Needed

What changes when the user disables "Allow non-Trading-Post materials"?
Should disabling it reject paths consuming even owned/bound or recursively
craftable non-TP ingredients (while still evaluating alternatives), or should
those remain allowed? If they remain allowed, specify what distinct calculation
behavior the disabled option provides, or whether to omit the toggle and make
UD-009's acquisition rule unconditional.

## Why This Is Needed

UD-009 is RESOLVED and establishes the enabled default, usable owned/craftable
materials, classification, retained blocked results and Profit-only scope.
It does not state the disabled behavior. Inferring that owned or craftable
ingredients become forbidden would invent a product eligibility rule.

## Context

- agent/user-decisions/UD-009-non-trading-post-material-control.md, User Decision.
- docs/DOMAIN_SPEC.md section 2.1.1 records the settled behavior and remaining gap.
- agent/product-owner-requests/Request-006-simplify-crafting-profit-state-and-non-tp-material-control.md.

The existing decision remains intact. Missing quotes for normally tradeable items
remain PRICE_UNAVAILABLE; this is not a valuation or architecture question.

## Blocks

Implementation planning for the non-TP toggle and full resolution of Request-006.
Independent presentation work and the active STORY-WEB-007 remain unaffected.

## External Input Possibly Required

Product Owner definition of the disabled behavior only.

## User Decision

DECIDED

When `Allow non-Trading-Post materials` is disabled, Crafting Profit must not
use a calculation path that consumes a non-Trading-Post material.

This includes non-Trading-Post materials that are:

- already owned;
- account-bound or otherwise non-tradeable;
- recursively craftable;
- used as intermediate ingredients.

Alternative valid sourcing/crafting paths that do not require a non-Trading-Post
material must still be evaluated normally.

When the option is enabled, the behavior established by UD-009 applies:
owned non-Trading-Post materials and non-Trading-Post materials obtainable through
an allowed crafting path may be used. A missing non-Trading-Post requirement with
no valid owned/crafting acquisition path remains unavailable.

Normally tradeable items with a missing Trading Post quote remain
`PRICE_UNAVAILABLE` and are not classified as non-Trading-Post materials.

The option therefore means:

- enabled: include valid crafting paths that depend on non-Trading-Post materials;
- disabled: restrict Crafting Profit to valid paths that do not consume
  non-Trading-Post materials.

Blocked/domain information remains available for selected-result details.
Normal result-display filters determine whether zero/unavailable results are
visible in the comparison list.

## Resolution

2026-09-26: The recorded Product Owner answer was found under External Input
Possibly Required rather than a User Decision heading. Restored the heading
without changing the answer or RESOLVED status. Applied the disabled behavior
to docs/DOMAIN_SPEC.md section 2.1.1. Implementation is planned separately;
this resolution does not claim delivery.
## Supersession note (2026-09-29)

The Product Owner explicitly removed the Crafting Profit non-Trading-Post checkbox and its restrictive behavior. The former enabled behavior is permanent: normal owned, crafting, and buying paths apply, and an unsatisfied requirement blocks naturally. The original decision above remains as historical context. Current behavior is defined in `docs/DOMAIN_SPEC.md` ?2.1.1.
