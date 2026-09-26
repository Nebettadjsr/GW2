## Status

RESOLVED

## Decision Needed

Define Crafting Profit's "Allow non-Trading-Post materials" calculation option:

1. With the option off, exclude any plan consuming a non-TP material (including
   owned/bound materials and craftable intermediates), or allow owned/craftable
   non-TP materials and exclude only requirements needing unavailable external
   acquisition? Specify treatment of recursive ingredients and alternative
   sourcing paths. For example, should an owned account-bound ingredient prevent
   the recipe from being considered? Should a craftable non-TP intermediate?
2. Does "non-Trading-Post" mean an item cannot be traded by item classification,
   or also a normally tradeable item whose current selected quote is unavailable?
   Existing missing-price behavior must not silently become a different rule.
3. Should excluded candidates disappear from the calculation's result set or
   remain as explicitly unavailable results with an explanation? Choose the
   opening default (enabled or disabled) and whether this option applies only
   to Profit as requested; no Discovery or JavaFX behavior change is assumed.

## Why This Is Needed

Request-006 expressly requires authoritative semantics before implementation.
DOMAIN_SPEC sections 11.1, 11.2 / DQ-008, 21 and 42 define binding, valuation,
missing prices and reasons, but not this eligibility toggle. Valuation does not
establish whether a material should be allowed. Each choice changes candidate
eligibility or craftability and therefore belongs to the Product Owner.

## Context

- agent/product-owner-requests/Request-006-simplify-crafting-profit-state-and-non-tp-material-control.md
- docs/DOMAIN_SPEC.md sections 2.1.1, 11.1, 11.2, 21, 42 and DQ-008.
- docs/KNOWN_PROBLEMS.md CH-17 records an implementation gap in the valuation
  fallback; this is not permission to redefine valuation or bundle its repair.
- STORY-APP-012 and STORY-DOM-020 Results preserve existing economics and expose
  available semantic trace states; those results do not decide this toggle.

The presentation-only requirement is already recorded in DOMAIN_SPEC section
2.1.1 and incorporated into existing STORY-WEB-008. It can proceed independently.
No answer here authorizes changing buying, budget, daily or allowed-recipe rules
unless that change is explicitly decided and recorded at the domain owner.

## Blocks

Authoritative domain semantics and implementation planning for the new control;
full resolution of Request-006. Does not block the active STORY-API-008 or the
independent presentation work. A subsequent planner pass must record the answer
in DOMAIN_SPEC before scoping implementation.

## External Input Possibly Required

Product Owner choice only. Implementers may verify available item metadata after
the behavior is decided; they must not equate missing quotes with nontradeability
without an authoritative rule.

## User Decision

DECIDED

For Crafting Profit, `Allow non-Trading-Post materials` uses the following semantics:

- The option is enabled by default.
- A non-Trading-Post material that is already owned may be used normally.
- A non-Trading-Post material that can be produced through an allowed crafting
  path may also be used normally, including recursively crafted intermediates.
- A plan becomes unavailable under this rule only when a required non-Trading-Post
  material cannot be satisfied from owned inventory or an allowed crafting path
  and therefore has no valid acquisition path.
- Alternative valid sourcing paths must still be evaluated normally.
- `Non-Trading-Post` means an item that cannot be traded through the Trading Post
  by its item/domain classification.
- A normally tradeable item whose current Trading Post quote is missing is NOT
  treated as non-Trading-Post. Existing `PRICE_UNAVAILABLE` behavior remains
  unchanged.
- A result blocked by this rule remains representable by the backend/domain rather
  than being silently discarded. Normal result-display filters may hide it, such
  as the default `Hide items with craftable count 0` filter.
- The reason/tag is not shown as a normal comparison-table column. It is presented
  as contextual information in the selected-result panel on the right, preferably
  at the affected material/tree node.
- This option applies to Crafting Profit only. It does not change Crafting
  Discovery or JavaFX behavior.

The intent is to allow recipes using owned or craftable non-Trading-Post materials
while preventing the calculation from pretending that an otherwise unobtainable
non-Trading-Post requirement can be acquired externally.