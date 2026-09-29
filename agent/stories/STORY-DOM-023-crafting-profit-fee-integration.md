## Story ID

STORY-DOM-023

## Title

Apply the decided Trading Post fee model to crafting profits and presentation

## Status

DONE

## Milestone

milestone-05

## Goal

Make Crafting Profit and Discovery use the resolved 15% profitability policy through the shared domain, application, HTTP, browser and JavaFX paths while preserving gross displayed prices.

## Authoritative Source Documents / Sections

- docs/DOMAIN_SPEC.md sections 2.1.1, 24-27 and 37: economic policy and presentation.
- agent/user-decisions/UD-011-trading-post-sale-quantity-basis.md: resolved product answer.
- agent/stories/STORY-DOM-022-trading-post-sale-fee-calculation.md, Result consumer map and limitations.
- docs/TARGET_ARCHITECTURE.md sections 12-14: backend authority, shared row and fresh-detail contract, JavaFX coexistence.
- Supplied docs/ROADMAP.md Phase 5: authoritative browser results and shared backend.
- docs/TEST_STRATEGY.md sections 12 and 36: targeted frontend checks and CI gate.

## Context

DOM-022 records that crafting revenue is gross and shared by Profit, Discovery, table/detail DTOs and JavaFX. Its explicit-sale primitive is not wired into those paths. UD-011 now chooses a simpler percentage model, superseding the earlier transaction-rounding request. Do not blindly reduce computeRevenue: its gross value also drives market displays and availability predicates. Implementation must confirm the recorded consumer map against the current revision after the active DOM-021 finishes; this story must preserve that story's non-TP policy.

## Acceptance Criteria

1. Trace and correct the shared authoritative calculation of per-craft and total profit under DOMAIN_SPEC section 25. Apply 15% to the corresponding sell value once, preserving existing quantity and material-cost aggregation. Cover both chosen sale-price modes and both Profit and Discovery consumers.
2. Preserve gross revenue/output value, total sell value and instant buy/sell quotes. Preserve gross-based availability predicates and all existing null/unavailable distinctions. Do not modify resolution, eligibility, inventory opportunity cost, buying costs, budgets, counts or non-TP policy to compensate for the changed profit.
3. Use one backend/domain owner for the decided percentage policy, reusing suitable monetary concepts. Do not substitute DOM-022's separately rounded minimum-fee calculation for that model. No independent arithmetic in application mapping, HTTP, JavaFX or Vue.
4. Carry corrected profit fields through both services, both table routes and both fresh-detail routes. Preserve fresh response association, quantity bases and tree semantics. Existing profit filters, sorting and rankings consume the corrected backend values; Discovery losses remain eligible.
5. In the Profit browser calculation details label Profit and Total Profit with a small 'after 15% TP fees' note. Preserve gross Output Revenue, Total Sell Value and market-price labels. Align shared Discovery presentation where already present; WEB-012 owns the new Discovery workflow and must consume these corrected values.
6. Ensure JavaFX consumes the same corrected backend profit and gross prices. Correct any tooltip or notice claiming fees still need deduction from displayed profit; do not move JavaFX to HTTP.
7. Update delivered contract/implementation documentation and calculation guidance as required by existing documentation policy. Record the consumer verification and limitations in Result. Do not claim Phase 5 performance or completion.

## Required Tests

- Focused domain fixtures: 300c gross less 204c own materials gives 51c profit; purchased costs, loss/zero profit, multi-output and multi-craft totals, both sale modes and unavailable prices. Include a small-copper case that distinguishes the decided percentage model from minimum transaction fees; no new exact-sale rounding requirement.
- Focused application/HTTP tests demonstrate both Profit and Discovery table/fresh-detail results retain gross values and corrected authoritative profits, including null results. Test projections with fixed distinctive values rather than reimplementing formulas outside domain tests.
- Browser component checks for gross labels, fee notes, backend-provided profits and resulting profit sort/filter behavior; preserve losses in Discovery.
- Explicit local browser smoke of Profit table and selected detail against a backend response. Explicit local TestFX check of affected JavaFX profit presentation/tooltip and gross values. Record unavailable interactive verification as a limitation, never a pass.
- Run directly affected tests locally; the complete regression/type-check/build suites remain the GitHub CI gate under TEST_STRATEGY section 36.

## Constraints

- Preserve DOM-021's completed behavior when implementation reaches this story; do not edit the currently active story.
- No transaction-rounding research, frontend fee formula, broad monetary refactor, new endpoint design or resolver retuning.
- Do not build Discovery's page here; WEB-012 owns that workflow.
- Ectoplasm integration is separately scoped in STORY-DOM-024. Keep the shared percentage-policy owner reusable within existing domain boundaries.

## Dependencies

Resolved UD-011; completed STORY-DOM-022, STORY-WEB-007 and STORY-WEB-008. No unresolved decision blocks this work. Integrate against the current shared-domain revision without overwriting DOM-021 changes.

## Definition of Done

Corrected backend profits and unchanged gross values reach both client families, required targeted evidence is recorded, documentation reflects delivered behavior, and the story revision passes the CI gate.

## Result

DONE. One domain deduction, carried unchanged to every consumer; two label notes and three tooltips
in presentation. An interrupted attempt was **resumed, not restarted**: `tradingpost/TradingPostFeePolicy.java`,
its test, the `CraftingPlanner` deduction and the `CraftResult` field documentation were already on
disk and were verified rather than rewritten. Two defects in that work were found and fixed (below);
everything from the existing tests' expectations onwards was written in this session.

### Where the fee is applied

`tradingpost.TradingPostFeePolicy` is the single backend owner of the decided percentage policy
(AC 3): `feeOn(long grossSellValueCopper)` returns 15% of whatever gross sell value the caller states,
in exact integer copper rounded half away from zero, with **no minimum** and no knowledge of prices,
sale modes or quantity bases; `netOfFee` is the same value's complement. Its Javadoc states that
`TradingPostSaleCalculator`'s separately rounded 5%/10% components with their 1-copper minimums
describe a *transaction* and must not be substituted here (§25.1), and the tests hold the two apart on
a 3-copper sale where the transaction model charges 2c and this one charges nothing.

`craft.CraftingPlanner.evaluateOneRecipeNew(...)` is the only caller. It charges the fee **once**, on
`cost.getRevenuePerCraft()`, when building `profitOne`; `totalProfit` stays `profitOne × craftCount`,
which is §27's relationship and, because the fee is a flat rate, charges the same 15% of the total
gross sell value (UD-011 waives the rounding remainder). Both sale-price modes are covered because the
fee follows whichever gross revenue `CostEvaluator.computeRevenue(...)` selected. **A revenue of zero
or less is not a sale and carries no fee** — the policy rejects a negative gross value as a caller
defect, so the planner guards it rather than throwing on a stored non-positive quote (§21).

### What stayed gross, and what was verified rather than changed (AC 2, AC 4)

`revenueCopper` and `totalSellValueCopper` are untouched, so `CraftingResultPresentation`'s
`revenueCopper > 0` availability predicate, `CraftingProfitController`'s revenue filter, the instant
buy/sell quotes and every null/unavailable distinction behave exactly as before. Resolution,
eligibility, opportunity cost, buy costs, budgets, craft counts and DOM-021's non-TP policy were not
touched to compensate. **The recorded consumer map was re-read against this revision and holds:** the
four money fields are copied verbatim by `application.CraftingProfitService` and
`CraftingDiscoveryService` (including Discovery's lazy tree-enrichment copy), by both JavaFX
controllers/views, and by `web.CraftingRowMapper` into `web.dto.CraftingRowDto`, which feeds both
table routes and both fresh-detail routes. No projection re-derives a profit, so carrying the
corrected values needed **no behavioral** application, mapper, DTO or route change — the only edit at
that boundary is `CraftingRowDto`'s Javadoc, which now states the after-fee basis of `profitCopper`/
`totalProfitCopper` beside the gross basis of `revenueCopper`/`totalSellValueCopper`. The passthrough
is asserted, not assumed (below). Existing profit filters, sorting and rankings consume the backend values
(`useProfitTableView`'s "sorts by the supplied total profit, not by any derived value"), and
Discovery keeps every loss listed.

### Presentation (AC 5, AC 6)

Browser: `SelectedResultDetail.vue`'s Profit and Total profit labels carry a small
`after 15% TP fees` note (`data-test="detail-profit-fee-note"` / `detail-total-profit-fee-note`);
Output revenue, Total sell value and the Trading Post price/item quote carry none. The shared
Discovery detail is aligned with the same two notes, and its existing paragraph now says the fee is
*already deducted* by the backend rather than only that this page never applies one. Both are wording:
no fee arithmetic exists in the frontend, and the comparison table is unchanged (§2.1.1 places the
note in the calculation details).

JavaFX: the "Item sell price" tooltip claimed `GW2 TradingFees will still be deducted -15%!` — now
false — and reads `Gross Trading Post price of ONE crafted item, before fees / The 15% TP fee is
already deducted from Profit per Craft and Total Profit`. The Profit-per-craft formula tooltip gained
`- 15% TP fees` and Total profit gained `(after 15% TP fees)`. JavaFX still consumes the same backend
`CraftResult`; nothing moved to HTTP.

### Two defects in the interrupted work, fixed

1. `TradingPostFeePolicyTest.fractionalCopperFeesRoundHalfAwayFromZero` asserted `feeOn(10) == 1`
   while 15% of 10c is 1.5c, which its own documented rule rounds **away from zero** to 2c; the
   implementation was right and the expectation was wrong.
2. `CraftingPlanner` passed the revenue to `feeOn` unguarded. A stored output quote of zero or less
   would have thrown `IllegalArgumentException` out of the planner where the previous code produced a
   (negative) profit the availability predicates then suppressed.

### Tests run

Backend (`./mvnw test`, full output, `BUILD SUCCESS` each time):

- `-Dtest='CraftingPlannerProfitFeeTest,CraftingPlannerTotalSellValueTest,CraftingBlockedRowsTest,CraftingPlannerHeuristicSkipCharacterizationTest,TradingPostFeePolicyTest,CraftingPlannerSingleRecipeTest'`
  — **Tests run: 28, Failures: 0, Errors: 0**. New `craft.CraftingPlannerProfitFeeTest` (8) holds
  §25's own worked example (300c gross − 45c fee − 204c own materials = **51c**), the listing-sell
  mode charging the fee on its own gross revenue (500 → 125c), a 3-output recipe over 4 crafts
  (300c gross per execution → 235c profit, **940c** total, **1 200c** gross total sell value),
  purchased materials leaving a loss (−45c per craft, −90c total, 600c gross sell value, the 600c buy
  cost uncharged), an exactly-break-even craft, an unavailable output price (no revenue, no fee),
  a non-positive stored quote, and the small-copper case where the §25.1 transaction model would
  charge 2c and this one charges 0c. Every expected amount is written by hand, never recomputed from
  the production formula.
- Existing expectations corrected, not weakened: `CraftingPlannerTotalSellValueTest` 2 980/14 900 →
  **2 530/12 650** (gross revenue and total sell value asserted unchanged), `CraftingBlockedRowsTest`
  80/160 → **65/130** with revenue still 100c, `CraftingPlannerHeuristicSkipCharacterizationTest`
  500 → **425**.
- `-Dtest='CraftingDiscoveryResolutionDetailTest,CraftingProfitNonTradeableMaterialFlowTest,CraftingProfitResolutionDetailTest,CraftingResolverCoordinatedCharactersTest,ResolutionTracePreservesCalculationTest,CharacterSelectionCraftingPlanIntegrationTest,CraftingResolverInsufficientBudgetTest'`
  — **Tests run: 51, Failures: 0** (every remaining suite that drives the real planner).
- `-Dtest='CraftingProfitApiControllerTest,CraftingDiscoveryApiControllerTest,CraftingProfitResolutionApiControllerTest,CraftingProfitTotalProfitPresentationTest'`
  — **Tests run: 75, Failures: 0**; `-Dtest=CraftingDiscoveryResolutionApiControllerTest` —
  **Tests run: 12, Failures: 0** including the new
  `theRowsProfitsAndGrossValuesAreTheDomainsOwnOnThisRouteToo`, which pins Discovery's fresh-detail
  row to fixed distinctive values (revenue 7 890, total sell value 9 999, profit 700, total profit
  2 800, buy cost 1 234, own materials 56 — mutually underivable) and asserts a `RESULT_UNAVAILABLE`
  result leaves all four money fields **absent** rather than reporting a fee-adjusted zero.

Frontend: `npx vitest run src/crafting` — **11 files / 213 tests passed**, including the new
`marksOnlyTheProfitFiguresAsBeingAfterTradingPostFees` (both notes present, exactly two `.value-note`
elements, gross values and the quote unaltered and unlabelled) and the updated Discovery detail
assertions. `npm run build` (type-check + production build) clean.

Browser, real Chrome against each script's own stub origin on `127.0.0.1` (probed free first; every
answer produced by the script, no backend, database or GW2 API involved): `npm run smoke:profit`
**PASSED, 38 steps** — the new step reads the selected detail and finds `after 15% TP fees` on Profit
and Total profit only (2 `.value-note` elements), the supplied `+1g 23s 45c` / `+14g 81s 40c` profits
unchanged, and the gross `148g 14s 1c` total sell value and `12g/13g` quotes unaltered and unlabelled.
`npm run smoke:discovery` **PASSED, 13 steps** with its fee-note step still green.

JavaFX: `./mvnw test -Dtest=CraftingProfitViewSmokeIT` — **Tests run: 2, Failures: 0** on a real
desktop against a disposable Postgres schema. The new
`profitColumnsShowTheFeeAdjustedBackendFiguresBesideGrossPrices` drives the real view's Refresh and
reads the real table: "Item sell price" **400c** and "Total sell value" **2 000c** gross, "Profit per
craft" **340c** (400 − 60) and "Total profit" **1 700c**, and the three column tooltips read back off
the header nodes to confirm the superseded warning is gone and the fee wording is present.

### Limitations

- No live-data run: the browser evidence is controlled-response only, and the JavaFX check uses a
  three-row fixture schema, never the user's account database or the GW2 API.
- Rounding of the 15% is half away from zero, matching the sibling calculator's documented choice.
  UD-011 waives copper-level differences, so this is a project convention, not verified game behavior.
- `totalProfit` is `profitOne × craftCount`, so the total's implied fee differs from one charged on the
  total in a single step by at most the per-craft rounding remainder × craft count — exactly the
  difference UD-011 declares irrelevant. No transaction-grouping model was built.
- The Ectoplasm path still uses `EctoSalvageCalculator`'s own 15% multiplier; STORY-DOM-024 owns it.
  `TradingPostSaleCalculator` still has no caller, as §25.1 requires.
- Suites outside the ones listed, other smoke scripts, the real-database `*IT` equivalence checks and
  the `*RealDbPerfIT` measurements were not run locally; GitHub Actions is the full-regression gate
  (`TEST_STRATEGY.md` §20/§36).
- **No Phase 5 performance claim and no Phase 5 completion is implied** — `STORY-PERF-002` and
  `STORY-DOM-024` remain outstanding.

### Documentation

`DOMAIN_SPEC.md` §24–§27/§2.1.1 already carried the decided rule and needed no change.
`CURRENT_STATE_SPEC.md` §15 (the conceptual profit formula now deducts the fee and states that
displayed prices stay gross), `CURRENT_ARCHITECTURE.md` (§2 tree, the `tradingpost` package row now
describing two models and their callers, the Profit detail's fee note, the Discovery detail's aligned
note, §5.5's gross-total wording corrected to cite §24, and §9 item 7 rewritten as crafting-side
resolved with the three remaining open pieces named), `KNOWN_PROBLEMS.md` KP-09 (the stale fee-tooltip
sentence removed; its sign-loss and buy-cost-label halves left open), and the §35 guide —
`docs/crafting/README.md`'s fee section rewritten around the shared 15% and the gross-display rule,
its profit formula and worked example recomputed (40c gross → 6c fee → **14c** per craft, 3 500c over
the 250-craft cap, 10 000c gross total sell value), and `GLOSSARY.md`'s FAQ entry replaced with the
question the change actually raises ("why is Total Sell Value minus my costs more than Total Profit?").


## Follow-up Findings Disposition
F001: ALREADY COVERED ? STORY-DOM-025 removes the stale CostEvaluator profit calculation.
F002: ALREADY COVERED ? STORY-APP-013 carries the authoritative total sell value into the JavaFX Profit view.
## Follow-up Findings

F001: `craft.CostEvaluator.evaluate(...)` still builds `CostEvaluationResult.profitPerCraft`/
`totalProfit` from a pre-fee formula. No production code reads either — `CraftingPlanner` computes its
own per-craft profit, on a different buy-cost basis (`missingToBuyOne` rather than
`sim.getFirstCraft().getBuyCostCopper()`) — so they were left alone as out of scope, but they are now
a "profit" that contradicts `DOMAIN_SPEC.md` §26 and would be wrong the moment a caller picked one up.

F002: `CraftingProfitView`'s "Total sell value" column computes `revenue × craftableCount` in the view
instead of carrying `CraftResult.totalSellValueCopper` through `CraftingProfitController.UiRow`, which
is the duplication `STORY-APP-011` removed for Total profit and `STORY-WEB-008` avoided on the web
row. The formula is identical today, so no value disagrees; it is a second derivation site, not a
defect.

## Blockers

None.

