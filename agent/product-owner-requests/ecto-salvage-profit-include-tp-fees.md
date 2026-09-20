# Product Owner Request

## Status

RESOLVED

## Title

Include Trading Post fees in Ecto Salvage profit calculations

## Requested Change

The Ecto Salvage Analyzer should include Guild Wars 2 Trading Post selling fees in its profit calculations.

Currently the UI explicitly states that Trading Post selling fees are not included.

That behavior should change.

Whenever the salvage calculation uses a Trading Post sell value for the resulting materials, the applicable Trading Post selling fees must be deducted before profit is calculated.

The displayed profit should therefore represent the actual expected proceeds after Trading Post fees rather than the gross sell value.

The current warning:

> figures below do not include Trading Post selling fees

should be removed or replaced with a short note explaining that Trading Post fees are already included in the displayed results.

## Why / Product Intent

The purpose of this view is to help the user judge whether salvaging ectoplasm is economically worthwhile.

A gross profit value without Trading Post fees is not sufficiently useful for that decision because the user would otherwise need to mentally deduct the fees from every result.

This becomes especially impractical when using the result to estimate the cost of generating larger amounts of Luck, for example the approximate cost of obtaining 1000 Luck.

The displayed values should therefore be directly usable without requiring the user to manually calculate Trading Post fees.

## Constraints

- Use the authoritative Trading Post fee rules already defined by the project/domain if available.
- Do not invent a separate fee formula specifically for this view.
- Apply fees only where a Trading Post sale actually occurs.
- Preserve the existing distinction between instant-sell and listing-sell scenarios.
- The profit table should continue to compare the relevant Ecto buy scenario against the relevant Dust sell scenario, but sell proceeds must be net of applicable Trading Post fees.
- Update explanatory UI text so it accurately reflects the new behavior.
- Add or update automated tests for the fee-inclusive calculation where practical.
- adding TP fees to calculation only effects THIS ONE VIEW and should not be implementet in other parts of the application unless specifically decided by the PO

## Additional Context

The current Ecto Salvage Analyzer displays combinations such as:

- Ecto Instant Buy / Dust Instant Sell
- Ecto Instant Buy / Dust Listing Sell
- Ecto Listing Buy / Dust Instant Sell
- Ecto Listing Buy / Dust Listing Sell

The resulting profit values currently exclude Trading Post selling fees.

This request changes that behavior so the values shown to the user represent the expected net result after fees.

## Planner Resolution

Updated `docs/DOMAIN_SPEC.md` §45–47 and DQ-011 to supersede the prior no-fee policy only for Ectoplasm Salvage, preserving §25's Crafting Profit rule. Created `agent/stories/STORY-DOM-016-ecto-salvage-net-sale-proceeds.md` and added it to `agent/stories/BACKLOG.md`; it covers all four scenarios, derived Luck costs, UI notice and numeric regressions. `docs/KNOWN_PROBLEMS.md` §3.6 records the pending product change separately from the resolved duplicate implementation. No implementation completion is claimed.