# Product Owner Request

## Status

RESOLVED

## Title

Finish Crafting Profit presentation, resolution details and calculation controls before milestone-05 closure

## Requested Change

Milestone-05 must not be considered complete while the current Crafting Profit page still contains missing result data, incomplete resolution details, redundant status labels or broken calculation controls.

The following remaining product issues must be addressed.

### Total Sell Value

`Total sell value` currently renders no value.

It must display the gross Trading Post sell value for all crafts counted.

For recipes producing more than one output item, the calculation must account for the full output quantity:

`Total Sell Value = TP sell price per item × output quantity per craft × craft count`

Equivalently:

`Total Sell Value = Output Revenue per craft × craft count`

This displayed value remains the gross Trading Post sell value before Trading Post fees. Trading Post fees affect profit calculations only.

### Crafting Resolution

The selected-result detail is intended to show how the recipe is resolved/crafted.

The `Crafting resolution` section must therefore display the actual recipe/material resolution tree for the selected result.

A normal valid result must not end with only a failed explanation request such as an HTTP 404 and no recipe tree.

Existing resolution/tree infrastructure should be reused where available rather than creating a second calculation representation.

### Materials Still to Buy

When buying is enabled, the section only needs to show the materials that must be bought for the number of crafts already calculated.

The separate `FOR ONE FURTHER CRAFT` section is unnecessary and should be removed.

The useful information is:

- what must be bought,
- how much must be bought,
- and the relevant price information for those materials.

### Remove Redundant Status Labels

Several current labels repeat information already communicated by the surrounding result and do not help the user.

Remove redundant labels such as:

- `Buying is off`
- `Over the buy limit`
- `Not blocked`

More generally, review the status labels/messages in the selected-result detail.

A label should remain only when it communicates useful information that is not already obvious from the surrounding content.

If a state matters to the user, prefer concrete information about the cause over a generic status label.

For example, a buy-limit problem is more useful when the UI explains which required purchase exceeds the configured limit than when it merely displays `Over the buy limit`.

### Price Missing Must Identify the Item

The current `Price missing` state does not tell the user which required item has no usable price.

When calculation is blocked because a price is missing, the detail must identify the affected item.

The user should be able to understand what data is missing without investigating the recipe manually.

### Remove Unnecessary Page Description

Remove the text:

`Crafting opportunities the backend calculated for the selected scope, with the profit it reported for each.`

It does not add useful information to the page.

### Fix "Allow non-Trading-Post materials"

The `Allow non-Trading-Post materials` checkbox currently does not behave as a persistent selectable control.

When the user checks it, the checkbox immediately clears itself again.

The control must retain the selected state and the calculation request/result must actually use that setting.

Changing the setting should trigger the appropriate recalculation without silently reverting the user's choice.

## Why / Product Intent

The Crafting Profit page should present calculation results in a way that is immediately understandable and useful without requiring the user to interpret internal backend states.

The selected-result detail should answer practical questions such as:

- What can I craft?
- How many can I craft?
- What is the gross sell value?
- What is the resulting profit?
- What materials do I still need to buy?
- Why was further crafting stopped?
- Which specific material or price caused that limitation?
- How is the selected item actually crafted?

Generic implementation-state labels, duplicate explanations and empty result fields do not serve that goal.

The milestone should therefore remain open until these remaining user-visible gaps are implemented and verified.

## Constraints

- Reuse the existing Crafting Profit calculation and resolution infrastructure where possible.
- Do not introduce a second independent calculation model in the frontend.
- `Total sell value` must remain a gross Trading Post value; Trading Post fees are applied only to profit calculations.
- Existing profit-fee decisions remain unchanged.
- Do not remove useful blocking information merely to simplify the UI. Replace generic/redundant labels with concrete information where appropriate.
- Missing-price states must identify the relevant item.
- The resolution tree must represent the backend's actual crafting resolution rather than a frontend reconstruction.
- The non-Trading-Post-material setting must remain under user control and must not silently reset.
- This request is part of milestone-05 completion. The planner must not treat the milestone as complete solely because existing implementation stories have technically finished if these product-visible requirements remain unresolved.

## Additional Context

Current observed issues include:

- `Total sell value` is rendered as `—`.
- `Crafting resolution` can show an HTTP 404 instead of the intended recipe tree.
- `Buying is off`, `Over the buy limit` and `Not blocked` consume prominent UI space while adding little or no useful information.
- `FOR ONE FURTHER CRAFT` duplicates information that is not required when the user already receives the purchase list for the calculated craft count.
- `Price missing` does not identify the item whose price is missing.
- The introductory Crafting Profit description adds no useful information.
- `Allow non-Trading-Post materials` visually resets immediately after being selected and therefore does not currently function as a usable calculation control.

The planner should check existing milestone-05 stories first and reuse or extend existing coverage where appropriate. New stories should be created only for requirements that are not already represented by executable work.

## Planner Resolution

2026-09-27: RESOLVED as planning coverage, not implementation acceptance or milestone closure. Updated docs/DOMAIN_SPEC.md section 2.1.1 with counted-craft-only shopping presentation, concrete item-specific blocking explanations, redundant-content removal and persistent calculation-control behavior. Existing gross-value and non-TP semantics remain unchanged.

Reused agent/stories/STORY-WEB-008-profit-economic-columns.md, agent/stories/STORY-WEB-007-profit-resolution-detail-view.md and agent/stories/STORY-DOM-021-profit-non-tp-material-control.md as established feature coverage. Because the PO reports failures after those stories completed, created agent/stories/STORY-WEB-014-profit-live-contract-repairs.md for targeted reproduction and correction of blank gross totals, erroneous resolution failure and control reset, with live browser/backend evidence. Created agent/stories/STORY-WEB-015-profit-purchase-and-blocking-details.md for the narrowed purchase list, useful item-specific causes, redundant status removal and exact introductory-text removal. Added both to agent/stories/BACKLOG.md in execution order.

agent/stories/STORY-DOM-023-crafting-profit-fee-integration.md continues to own fee integration and preservation of gross displays. docs/TARGET_ARCHITECTURE.md section 13 remains the resolution-contract owner, including legitimate fresh-candidate absence. Phase 5 remains open pending implementation and verification of these requirements and its existing performance and health-review gates.