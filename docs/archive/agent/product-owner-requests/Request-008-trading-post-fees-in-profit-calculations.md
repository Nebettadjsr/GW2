# Product Owner Request

## Status

RESOLVED

## Title

Account for Trading Post fees in profitability calculations

## Requested Change

Profit calculations that use Trading Post sale revenue must account for the actual Guild Wars 2 Trading Post fees.

The current Crafting Profit result can treat the full Trading Post sell price as revenue. This overstates the actual economic result because the player does not receive the full sale price.

Guild Wars 2 charges a total of 15% in Trading Post fees:

- 5% listing fee;
- 10% exchange fee.

Profitability calculations must use the actual revenue remaining after applicable Trading Post fees rather than treating the gross Trading Post sell price as received revenue.

---

### Purpose

Crafting Profit should answer whether crafting and selling an item is actually profitable for the player.

For example, if one crafted item has:

- Trading Post sell value: 3s 00c;
- relevant material value: 2s 04c.

The gross comparison currently produces:

```text
3s 00c - 2s 04c = 96c profit
```

However, selling the item for 3s 00c incurs:

```text
Listing fee:   15c
Exchange fee:  30c
Total fees:    45c
Net revenue: 2s 55c
```

The actual economic result is therefore:

```text
2s 55c - 2s 04c = 51c profit
```

The application should represent the latter result when describing the craft as profitable.

---

### Profit calculations

Where Trading Post sale revenue is used to calculate profitability, use the net revenue after applicable Trading Post fees.

This applies consistently to at least:

- profit per craft;
- total profit for all crafts counted;
- profitability comparisons;
- profitability-based sorting or ranking;
- other calculations that use Trading Post sale proceeds as revenue.

The Trading Post market price itself remains useful information and should not be changed merely to make it represent net revenue.

Gross market value, fees, net revenue and profit are separate concepts.

Backend/domain calculations remain authoritative. Do not introduce independent frontend fee or profitability calculations.

---

### Trading Post fee accuracy

Trading Post fees must follow Guild Wars 2's actual fee behavior.

Fee calculations should operate on copper values and reproduce the amounts the player actually pays or receives, including the game's rounding behavior.

Do not rely on floating-point `sell_price * 0.85` calculations if doing so can produce results that differ from the Trading Post.

The domain implementation should provide authoritative monetary results that downstream consumers can use consistently.

---

### UI expectations

The Crafting Profit UI should make the economic result understandable without confusing gross Trading Post prices with actual received revenue.

Where appropriate, selected-result details should distinguish between:

- gross Trading Post sell value;
- Trading Post fees;
- net revenue after fees;
- resulting profit.

Existing Trading Post buy/sell price information may continue to display the market price itself.

The UI should not present a gross Trading Post sell price as though that full amount is received by the player.

Frontend presentation must use backend-authoritative monetary values rather than recreating fee calculations in Vue.

---

### Existing calculation behavior

This request changes the treatment of Trading Post sale revenue where profitability is calculated.

It should not alter unrelated crafting semantics such as:

- recipe eligibility;
- owned-material handling;
- missing-material resolution;
- crafting quantities;
- Trading Post buy-mode behavior except where an existing profitability calculation consumes those values.

Existing calculation paths should be reviewed so that Trading Post fees are applied consistently rather than only correcting one visible Crafting Profit field.

---

## Why / Product Intent

A displayed profit value should represent the player's actual economic result.

Trading Post fees are unavoidable when an item is sold through the Trading Post. Ignoring them can substantially overstate profitability and can cause an apparently profitable craft to actually be unprofitable.

Users should be able to compare crafting opportunities using values that correspond to what they would actually receive in Guild Wars 2.

The distinction between market value and profit should remain clear:

```text
gross Trading Post sell value
- Trading Post fees
= net sale revenue

net sale revenue
- relevant material cost/value
= profit
```

## Constraints

- Preserve Trading Post market prices as gross market-price information where appropriate.
- Account for the 5% listing fee and 10% exchange fee when Trading Post sale proceeds are used for profitability.
- Match Guild Wars 2's actual copper-level fee and rounding behavior.
- Keep monetary calculations backend/domain authoritative.
- Do not recreate fee calculations independently in Vue.
- Apply the corrected economics consistently across all affected profitability calculations.
- Do not alter unrelated crafting or recipe-resolution semantics.
- Reuse existing shared monetary and Trading Post domain concepts where appropriate.

## Additional Context

This request was identified from the current Crafting Profit result for **Elder Longbow Stave**.

The application displayed:

```text
Output revenue:         3s 00c
Own materials given up: 2s 04c
Profit:                    96c
```

The Guild Wars 2 Trading Post shows that selling the same item for 3s 00c incurs:

```text
Listing fee:  15c
Exchange fee: 30c
```

The player therefore receives 2s 55c, making the corresponding profit 51c rather than 96c.

The planner should inspect the existing authoritative economic calculation paths and determine where Trading Post sale revenue enters profit calculations before creating implementation stories. The correction should be made at the appropriate shared/domain level rather than patched into an individual UI field.

## Planner Resolution

2026-09-26: RESOLVED as planning coverage, not implementation completion.
Consumed the resolved answer in agent/user-decisions/UD-011-trading-post-sale-quantity-basis.md:
its 15% profit model and gross displayed prices supersede the original exact-sale
rounding/grouping requirement. Updated docs/DOMAIN_SPEC.md sections 2.1.1, 25-27,
46 and DQ-001 as the authoritative policy owner.

Reused agent/stories/STORY-DOM-022-trading-post-sale-fee-calculation.md's recorded
consumer inventory; its explicit-sale primitive is not the decided profit model.
Created agent/stories/STORY-DOM-023-crafting-profit-fee-integration.md for shared
Profit/Discovery economics, comparisons, HTTP table/fresh-detail projections,
browser detail notes and JavaFX compatibility. Created
agent/stories/STORY-DOM-024-ectoplasm-fee-policy-alignment.md for expected-value
economics, existing net-cost/Luck relationships and gross-price presentation.
Added both to agent/stories/BACKLOG.md in execution order.

Updated agent/stories/STORY-WEB-012-crafting-discovery-page.md and
agent/stories/STORY-WEB-013-ectoplasm-salvage-page.md to consume the decided policy
and preserve gross price labels within their existing browser workflows.
No implementation, test success or Phase 5 completion is claimed.