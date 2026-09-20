# STORY-DOM-013: Expose blocked crafting rows

## Story ID

STORY-DOM-013

## Title

Preserve unavailable and blocked results in Crafting Profit and Discovery

## Status

TODO

## Milestone

milestone-01

## Goal

Keep unresolvable crafting results visible with their blocked/unavailable reason instead of silently dropping them in the Crafting Profit and Discovery flows.

## Authoritative Source Documents / Sections

- Supplied Phase 1 roadmap: exit criterion requiring blocked/unavailable rows to remain visible; missing-blocked-reasons high-level story.
- docs/KNOWN_PROBLEMS.md §3.5, especially its Partially Resolved status and remaining controller filtering.
- docs/DOMAIN_SPEC.md §21, §41 and §42.

## Context

KNOWN_PROBLEMS.md §3.5 records that PRICE_UNAVAILABLE, RECIPE_NOT_ALLOWED and INSUFFICIENT_BUDGET now exist with regression coverage, but CraftingProfitController.hasZeroPricedBuy still filters rows. Domain reasons alone do not close the Phase 1 requirement to expose these results to users. Discovery must also preserve unresolved results under the same exit criterion; its exact current behavior must be verified during implementation.

## Acceptance Criteria

- Add a failing regression test before fixing the documented controller-level loss of a result requiring an unavailable purchase price.
- Remove the silent exclusion described in §3.5; the affected result remains visible with PRICE_UNAVAILABLE or the applicable existing unavailable state.
- Missing, zero and negative required purchase prices are never presented as free purchases or as valid completed profit calculations (§21).
- Verify both Crafting Profit and Discovery preserve unresolvable results and expose their existing blocked reasons; fix any silent exclusion of such results encountered in these flows. Do not invent new domain reasons.
- Valid, evaluable results retain their existing calculations; feasibility and profitability remain distinguishable (§41).
- Update KNOWN_PROBLEMS.md §3.5 with the implemented behavior and actual verification evidence; mark it resolved only when its remaining filtering conflict is closed.

## Required Tests

- Automated regression covering a blocked result surviving controller/result preparation and retaining its reason, failing before the fix and passing afterward.
- Cover missing, zero and negative required purchase prices, plus a valid-price control case.
- Verify result preservation in both flows with automated tests at the narrowest practical existing boundary. Record manual UI verification that unavailable results and reasons are visible in both views.
- Run the existing relevant domain regressions and project test suite; record actual commands and outcomes. Do not claim unexecuted checks passed.

## Constraints

- Keep changes in the current structure and limited to the Phase 1 blocked-row requirement.
- Reuse existing domain blocked reasons and preserve calculation semantics for valid results.
- Do not replace missing prices with zero or fabricate profitability for unavailable results.
- No new framework or broad architecture changes.

## Dependencies

None. The required blocked-reason values are recorded as implemented in KNOWN_PROBLEMS.md §3.5.

## Definition of Done

- Acceptance criteria satisfied and regression tests passing.
- Both user-facing flows verified and evidence recorded in Result.
- The authoritative problem record accurately reflects remaining or resolved scope.

## Result

Not started.

## Blockers

None.
