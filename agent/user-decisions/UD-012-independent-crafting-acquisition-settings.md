# UD-012 — Independent crafting acquisition settings

## Status

RESOLVED

## Decision Needed

Define how the Crafting Profit controls for owned materials, Trading Post buying, and daily crafting
interact, especially for recursive acquisition paths.

## User Decision

The three controls are independent:

- `useOwnMats` controls whether usable owned inventory may satisfy requirements.
- `allowBuying` controls whether Trading Post purchases may satisfy requirements, at any level of
  the recursive crafting tree.
- `allowDailyCrafts` controls whether one available daily/time-gated craft operation per output
  item may be performed in a planning state.

Disabling one source does not enable or disable another. Owned daily items may be consumed when
`useOwnMats` is enabled even if daily crafting is disabled. Daily crafting may feed a parent craft
when enabled, while any further purchases still require `allowBuying`. Disabling buying prevents
all Trading Post purchases, including indirect child/intermediate purchases. Disabling owned
materials prevents inventory and material storage from contributing.

Daily crafting state is not synchronized from the account. Each planning state therefore models one
available craft operation per daily output item, producing the recipe's normal output batch.

## Implementation Notes

The Profit API field is named `allowDailyCrafts`. Its omitted-value default is false to preserve the
former Profit default, which did not craft daily outputs. Discovery continues to fix this setting
to true to preserve that flow's prior behavior; it is not a Discovery request control.

## Resolution

RESOLVED by the Product Owner instruction received 2026-09-30. The current domain rules are recorded
in `docs/DOMAIN_SPEC.md` §§2.1.1 and 32–33.
