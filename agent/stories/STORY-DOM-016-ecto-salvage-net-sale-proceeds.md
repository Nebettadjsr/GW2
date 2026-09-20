## Story ID

STORY-DOM-016

## Title

Include Trading Post selling fees in Ectoplasm Salvage results

## Status

DONE

## Milestone

milestone-01

## Goal

Bring the live Ectoplasm Salvage calculation into agreement with the updated DOMAIN_SPEC.md §45–47/DQ-011 by using net sale proceeds for profit and Luck cost.

## Authoritative Source Documents / Sections

- Supplied Phase 1 objective and Ectoplasm fee-model item.
- docs/DOMAIN_SPEC.md §20, §25, §45–47, DQ-011.
- docs/KNOWN_PROBLEMS.md §3.6 (historical implementation evidence).
- PO request: agent/product-owner-requests/ecto-salvage-profit-include-tp-fees.md.

## Context

The PO explicitly supersedes the prior no-fee decision for this view. Historical documentation records EctoView as the surviving implementation. This story changes that calculation in its current location; it does not revive deleted legacy code or presume service extraction.

## Acceptance Criteria

- Add failing regressions before changing the fee-excluding calculation.
- Apply §46 net proceeds to recovered materials sold on the Trading Post in all four instant/listing Ecto-buy and Dust-sell combinations; preserve each scenario's price source.
- Profit, net Ecto cost and cost per 1000 Luck consistently use those net proceeds. Apply fees only to modeled TP sales, once; preserve acquisition costs and expected yields.
- Replace the fee-exclusion warning with accurate fee-included text; distinguish raw quoted prices from net proceeds.
- Preserve Crafting Profit's §25 exemption and all other views' fee behavior.
- Record implementation and verification evidence, updating affected authoritative problem documentation without claiming the historical duplicate implementation has returned.

## Required Tests

- Deterministic numeric tests for all four buy/sell combinations using distinct quote values, verifying §46's deduction once and §47's derived Luck cost; include a zero-profit boundary and a negative-profit example.
- Regression proving acquisition cost/yield assumptions are unchanged and no fee is deducted from Luck itself.
- Verify rendered fee notice and displayed scenario results using existing JavaFX verification capability where practical; record any specific automation limitation.
- Run relevant Ecto and crafting regressions and the project test suite; record actual commands/outcomes.

## Constraints

Only Ectoplasm Salvage changes fee behavior. Reuse authoritative price/fee rules; no new fee model, yield assumption, framework or broad restructuring. Follow the failing-test-first workflow.

## Dependencies

None.

## Definition of Done

All acceptance criteria met; required checks executed with outcomes recorded; documentation consistent with the implemented fee-inclusive view.

## Result

Extracted the Ectoplasm Salvage calculation out of `EctoView`'s private, UI-coupled
`fillProfitGrid`/`fillLuckGrid` methods into a new plain class, `EctoSalvageCalculator`
(no JavaFX/repo/controller dependency), so it could be exercised by deterministic unit tests. Added
`src/test/java/EctoSalvageCalculatorTest.java` first, with the calculator's fee deduction
temporarily stubbed out to `return grossUnitPrice` (matching `EctoView`'s then-current no-fee
behavior byte-for-byte) — ran it and confirmed 6 of 7 tests failed against that unchanged
behavior (the 7th, proving acquisition cost/yield constants are untouched, correctly still
passed, since that part of the behavior isn't changing). Then implemented DOMAIN_SPEC.md §46's
`selected gross price × 0.85` deduction in `EctoSalvageCalculator.netSaleProceeds(...)` and
re-ran: all 7 passed.

`EctoView` now calls `EctoSalvageCalculator.evaluate(ectoAcquisitionCost, dustGrossUnitPrice)` for
all four Ecto-buy/Dust-sell combinations in both `fillProfitGrid` and `fillLuckGrid`, using its
`profitPerEcto()`/`costPer1000Luck()` results directly instead of reimplementing the arithmetic.
Ecto acquisition cost (`ectoInstantBuy`/`ectoListingBuy`) is passed through unchanged — no fee is
ever applied to a purchase, and the `LUCK_PER_ECTO`/`DUST_PER_ECTO`/`ECTOS_PER_1000_LUCK`
constants are unchanged (now sourced from the calculator instead of being redefined). The fee is
deducted exactly once, inside `evaluate(...)`, from the Dust unit price before it is multiplied by
the expected Dust yield.

UI changes: the fee-exclusion warning label was replaced with fee-inclusive wording naming the
15% rate and stating Ecto acquisition cost/Luck are unaffected. The Prices card now shows both the
raw Dust Trading Post quotes (relabeled "Instant/Listing Sell (raw)") and a new row of net-of-fee
Dust proceeds ("Instant/Listing Sell (net of TP fee)", via `EctoSalvageCalculator.netSaleProceeds`),
so raw quotes and net proceeds are simultaneously visible and distinguishable, not just described in
prose.

Crafting Profit's §25 no-fee exemption was not touched — `EctoSalvageCalculator` is a new,
self-contained class with no callers outside `EctoView`; no other view's fee behavior changed.

### Required Tests

- `src/test/java/EctoSalvageCalculatorTest.java` (7 tests, Layer 1/unit, deterministic, offline):
  one test per Ecto-buy/Dust-sell combination (instant/instant, instant/listing, listing/instant,
  listing/listing) with distinct quote values, each asserting the net Dust unit price, net
  recovered-Dust value, net cost per Ecto, profit per Ecto, and cost-per-1000-Luck; a zero-profit
  boundary case (net recovered Dust value exactly equals Ecto acquisition cost); a negative-profit
  case (same net Dust value, higher Ecto cost); and a regression asserting
  `LUCK_PER_ECTO`/`DUST_PER_ECTO`/`ECTOS_PER_1000_LUCK` are unchanged, that acquisition cost passes
  through unmodified, and that `costPer1000Luck == netCostPerEcto * ECTOS_PER_1000_LUCK` exactly
  (i.e. no separate fee is ever applied to Luck).
- `src/test/java/uiverify/EctoFeeNoticeSmokeIT.java` (Layer 5/TestFX, real app + real view,
  excluded from the default `./mvnw test` goal per its "IT" suffix): launches the real `Gw2App`,
  clicks the real "Salvage Ecto for Dust & Luck" button, and asserts the real, rendered fee notice
  label (looked up via a new `.ecto-fee-notice` style class, since it had no other selector) states
  fees are already deducted, names "15%", and no longer contains the old "do not include" wording.
  Passed (`./mvnw test -Dtest=EctoFeeNoticeSmokeIT`, 1/1).
- **Automation limitation (recorded per this story's acceptance criteria, not solved):** the
  profit/Luck-cost grids' *displayed* values were not verified end-to-end through TestFX.
  `EctoView` fetches Trading Post quotes directly from the live GW2 API with no injectable
  price seam (unlike `CraftingProfitView`, which `CraftingUiTestFixtures` can point at a disposable
  Postgres schema) — adding one would be new test-seam/production-code scope beyond this story's
  "no new... framework or broad restructuring" constraint. The arithmetic itself is fully covered
  deterministically by `EctoSalvageCalculatorTest`, and `fillProfitGrid`/`fillLuckGrid` were
  confirmed by direct code reading to call that same calculator rather than reimplementing the
  formula, so there is no separate copy of the math that could silently drift from what the tests
  cover.

### Commands run and outcomes

- `./mvnw -q compile` — success.
- `./mvnw -q test-compile` — success.
- `./mvnw test -Dtest=EctoSalvageCalculatorTest` — first run (fee deduction stubbed out): 6/7
  failed as expected (red). Second run (fee deduction implemented): 7/7 passed (green).
- `./mvnw test` (default goal, full project suite) — **72/72 passed**, confirming no regression in
  the domain/repository/sync/parser suite (Layer 1–3) and that `EctoFeeNoticeSmokeIT` is correctly
  excluded from the default goal (verified by inspecting `target/surefire-reports/` after clearing
  it and re-running — only `EctoSalvageCalculatorTest`, not the `IT`, appears).
- `./mvnw test -Dtest=EctoFeeNoticeSmokeIT` — 1/1 passed, against a real desktop/JavaFX session.
- `./mvnw test -Dtest=EctoSalvageCalculatorTest,EctoFeeNoticeSmokeIT` — 8/8 passed together.

## Blockers

None.
