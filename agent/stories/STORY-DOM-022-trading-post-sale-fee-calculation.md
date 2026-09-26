## Story ID

STORY-DOM-022

## Title

Establish a shared copper-accurate Trading Post sale fee calculation

## Status

DONE

## Milestone

milestone-05

## Goal

Provide a reusable backend/domain calculation of gross sale value, listing fee, exchange fee and net proceeds for an explicitly specified sale, without selecting the unresolved crafting or salvage sale-grouping policy.

## Authoritative Source Documents / Sections

- docs/DOMAIN_SPEC.md sections 3, 20, 24-27, 37 and 45-47, DQ-001: Request-008's authoritative fee requirement and preserved unrelated economics.
- docs/TARGET_ARCHITECTURE.md sections 7-12: shared domain authority, thin adapters and presentation-only frontend.
- docs/CURRENT_ARCHITECTURE.md sections 5.1-5.3: crafting application boundaries and the separate Ectoplasm calculator.
- docs/ROADMAP.md, supplied Phase 5 section: backend-provided economic results and JavaFX/web coexistence.
- agent/user-decisions/UD-011-trading-post-sale-quantity-basis.md: unresolved consumer grouping, outside this independent primitive's scope.

## Context

The Product Owner superseded the documented Crafting Profit fee exemption and requires accurate copper amounts across sale-revenue consumers. The supplied completed WEB-008 record describes gross total sell value; CURRENT_ARCHITECTURE documents shared crafting results and a separate fee-inclusive Ectoplasm model. Neither establishes accurate common rounding. Implementation inspection must establish the actual call paths; no current code defect beyond the reported behavior is asserted here. UD-011 blocks consumer integration, but not a calculation whose caller supplies an explicit sale basis.

## Acceptance Criteria

1. Inspect the existing monetary/domain concepts and trace where sale revenue enters Crafting Profit, Discovery, their shared table/fresh-detail results and Ectoplasm profit/Luck costs. Record a concise consumer map and concrete integration consequences in Result; distinguish observed code from documentation. Reuse an existing suitable domain concept rather than introduce a second fee calculator unnecessarily.
2. Verify the game's listing/exchange fee rounding, minimum amounts and transaction quantity basis with reliable source or reproducible game evidence. Record sources, date and discriminating copper examples. Request-008's 300c example alone cannot establish rounding. If evidence cannot establish an edge rule, report that precise limitation rather than substitute a guessed 15% formula or claim accuracy.
3. Implement a pure backend/domain calculation accepting an explicit sale amount/basis and returning authoritative gross copper, separate 5% listing and 10% exchange fees, total fees and net proceeds. Use exact integer/rational arithmetic as appropriate, with documented supported inputs, zero-sale behavior, invalid-input handling and overflow safety. Match the verified actual-sale rules. Do not round a combined 15% if the two fee components have separate rounding.
4. Preserve the original market quote and distinguish it from fee-adjusted net proceeds. Both instant and listing sales must use their selected gross price without a second fee deduction. No fee calculation belongs in HTTP mapping, JavaFX or Vue.
5. Do not wire this primitive into crafting per-craft/total results or Ectoplasm's expected-yield calculation until UD-011 is resolved and integration is separately scoped. Do not choose per-item, per-craft, all-crafts or fractional-yield grouping implicitly through an API default. Document the explicit input basis so later callers cannot confuse those quantities.
6. Record verified fee semantics at DOMAIN_SPEC section 25 and implementation facts at CURRENT_ARCHITECTURE as applicable. Keep consumer-policy questions in UD-011. Report that existing displayed profits remain uncorrected until integration; do not claim Request-008 or Phase 5 complete.

## Required Tests

- Database-independent fee tests against independently established examples: 300c gross produces 15c listing, 30c exchange and 255c net; small copper amounts, boundary values and amounts distinguishing separate fee rounding from a combined multiplier.
- Verified minimum-fee cases, no-sale and rejected-input behavior, explicit multi-item sale quantities, and supported numeric limits/overflow handling. Expected fixtures must not merely call the production formula again.
- Assert gross minus the two fee components equals returned net and that input market prices are unchanged. Run affected existing domain tests if an existing shared abstraction is changed; record precisely what was verified. No live trade or account mutation is required.

## Constraints

- No frontend/domain duplication, economic-policy invention or broad monetary refactor. No HTTP, JSON, persistence, JavaFX or GW2 API models in the domain calculation.
- Preserve owned-material valuation, buying costs/budgets, recipe eligibility, resolution, quantities and yield assumptions.
- Do not modify active STORY-API-009 or icon infrastructure. Routine type naming and placement within established domain boundaries belong to implementation.
- Review evidence and implement this bounded primitive only; do not commission or implement speculative dependent fixes from the consumer map.

## Dependencies

None. UD-011 blocks consumer integration only; this story receives an explicit sale basis.

## Definition of Done

A tested shared domain fee calculation matches evidenced actual-sale behavior, with documented input basis and source-backed edge cases. The consumer map and outstanding integration policy are recorded without claiming delivered profit correction.

## Result

DONE, **bounded primitive only**. `tradingpost.TradingPostSaleCalculator` exists and is tested;
**nothing calls it**, so every profit figure the application displays today is still the
uncorrected gross-revenue one. Request-008 is **not** resolved and Phase 5 is **not** complete.

An interrupted attempt was **resumed, not restarted.** The calculator, its 13-test suite,
`DOMAIN_SPEC.md` §25.1 and the three `CURRENT_ARCHITECTURE.md` sections were already on disk and
correct. This session re-verified them rather than re-implementing anything — re-ran the suite,
re-read `craft.CostEvaluator.computeRevenue` and `ecto.EctoSalvageCalculator` to confirm the
consumer map's two load-bearing claims, re-confirmed by `grep` that nothing outside
`src/*/java/tradingpost/` names the new class and that `frontend/src` contains no fee arithmetic —
then wrote the records the earlier attempt stopped before finishing (this status, the BACKLOG
entry, `CLAUDE_RESULT.md`).

### AC 1 — Consumer map (observed in code, not inferred from documentation)

Crafting Profit and Discovery share one revenue path. `craft.CostEvaluator.computeRevenue(...)`
(`CostEvaluator.java:37-56`) is the **only** place a sale price becomes revenue in the crafting
domain: it reads `PriceQuote.sellUnit` or `PriceQuote.buyUnit` per `CraftingSettings.listingSell`
(DOMAIN_SPEC §20) and returns `unit * recipe.outputCount` — **no fee of any kind**. From there:

- `CraftingPlanner.evaluateOneRecipeNew(...)` (`CraftingPlanner.java:177-201`) derives
  `revenueOne`, `profitOne = revenueOne - buyCostOne - matsSellOne`,
  `totalProfit = profitOne * craftCount` and `totalSellValue = revenueOne * craftCount`, all
  carried on `craft.CraftResult` (`revenueCopper`, `profitCopper`, `totalProfitCopper`,
  `totalSellValueCopper`). `CraftResult`'s 11-argument constructor also derives
  `revenueCopper * craftableCount` for callers that state no total of their own
  (`CraftResult.java:58`).
- **Both** application services copy those four fields verbatim into their row records
  (`CraftingProfitService.java:305-313`, `CraftingDiscoveryService.java:306-313`); neither
  recalculates.
- **JavaFX**: `CraftingProfitController.Row` / `CraftingDiscoveryController.Row` copy them again
  and the views bind them to table columns (`CraftingProfitView.java:545`, `:607`,
  `CraftingDiscoveryView.java:486`). `CraftingResultPresentation.java:22,26` additionally uses
  `revenueCopper > 0` as an availability predicate — a fee-adjusted revenue would change which
  rows are shown as calculable, so integration must keep that predicate on **gross**.
  `CraftingProfitView.java:684` already carries a tooltip admitting "GW2 TradingFees will still
  be deducted -15%!".
- **HTTP/browser**: `web.CraftingRowMapper.java:77-80` copies the same four fields into
  `web.dto.CraftingRowDto` (whose Javadoc at `:33` states "no selling fee deducted"), which feeds
  both table routes and both fresh-detail (`resolveDetail`) responses, and the Vue frontend
  renders them. Searched `frontend/src` for `0.85`/`0.15`/`listingFee`/`exchangeFee`: **no
  frontend fee arithmetic exists**, so AC 4's "no fee calculation in Vue" already holds and stays
  holding.
- **Ectoplasm** is a genuinely separate implementation: `ecto.EctoSalvageCalculator.netSaleProceeds`
  (`EctoSalvageCalculator.java:13-21`) applies `Math.round(gross * 0.85)` — a **combined**
  floating-point 15% multiplier, exactly what §25 rejects — then scales by the fractional
  `DUST_PER_ECTO = 0.75` expected yield. `EctoView` renders "Net" labels and a fee notice built on
  that number.

**Concrete integration consequences.** (a) One change at `CostEvaluator.computeRevenue` reaches
Profit, Discovery, both JavaFX tables, both HTTP table routes and both detail routes at once —
but it is also the point where per-craft vs all-crafts grouping is decided, because `profitOne`
is multiplied by `craftCount` downstream (§27). (b) `totalSellValueCopper` is specified (§2.1.1,
STORY-WEB-008) as **gross** market value and must not silently become net. (c)
`CraftingResultPresentation`'s `revenueCopper > 0` gate and `CraftingRowDto`'s documented "no
selling fee" contract both have to move deliberately. (d) Ecto needs the fractional-yield answer
of UD-011 before its 0.85 multiplier can be replaced. All four are **UD-011-blocked** and
deliberately untouched here.

**Reuse:** no existing suitable concept was found. `util.CoinUtils` is copper parse/format only,
`model.Price`/`util.TpPrice`/`craft.PriceQuote` are quote carriers, and `EctoSalvageCalculator`'s
fee is an ecto-local double. A new shared primitive is the minimum, not a duplicate; AC 5 forbids
retiring the ecto one in this story, so two fee calculations now coexist and that is recorded as
open mixing in `CURRENT_ARCHITECTURE.md` §9 item 7.

### AC 2 — Fee evidence (retrieved 2026-09-26)

**Source:** Guild Wars 2 Wiki, *Trading Post* → *Additional fees*
(https://wiki.guildwars2.com/wiki/Trading_Post, raw wikitext fetched 2026-09-26), verbatim:

> Selling items on the Trading Post requires additional coin fees, **based on the total sale
> price** […] Listing Fee (5%) — […] This fee has a **minimum of 1 copper** and is immediately
> taken from your wallet when you list or instantly sell an item. Exchange Fee (10%) — […] This
> fee has a **minimum of 1 copper** and is deducted from coins delivered to the seller after a
> successful sale.

Corroboration: the German wiki (`wiki-de`, *Handelsposten* → *Gebühren*, same date) gives the same
5%/10% split with a 100c worked example (5c listing, 10c exchange, 85c received) and derives the
vendor-price threshold as `(1−0.15)⁻¹ ≈ 17.6%`. `Talk:Trading Post` describes the charge as
`5% × Q × P`, consistent with the total-sale-price basis.

**Verified:** rates 5%/10%; a 1-copper minimum on **each** component; the basis is the whole
transaction's total sale price, not per item; the listing fee applies to an instant sell too.

**Not established — reported as a limitation, not guessed away.** No reliable source states the
rounding direction for a fractional copper fee. Request-008's 300c example divides exactly
(15c/30c) and cannot discriminate, as the story anticipated. Searched: the English wiki article
and its talk page, three localized wikis, and the wiki search API — none documents rounding. What
the evidence *does* settle is that **rounding up is excluded**: under a ceiling, `ceil(0.05 × p)`
and `ceil(0.10 × p)` are already ≥ 1 for every p ≥ 1, which would make both documented 1-copper
minimums unreachable and meaningless. Between the two survivors, **half away from zero** was
implemented because it matches the only independent implementation that could be inspected
(gw2tp.com's own calculator: `Math.max(1, Math.round(price × 0.05))` and `Math.round(price × 0.10)`
over `qty × sell`) and because, under uncertainty, it never understates fees relative to floor.
**Rounding down is not excluded by the available evidence.** The rule lives in one private method
(`TradingPostSaleCalculator.feeOn`) so in-game evidence can correct it in a single edit. No
accuracy claim beyond this is made. (No live trade or account mutation was performed; the game
client was not available in this environment.)

Discriminating copper examples recorded and asserted: **15c** gross → 1c listing (0.75) + 2c
exchange (1.5) = 3c fees / 12c net, where one rounded 15% gives 2c/13c and `gross × 0.85` gives
12.75 → 13c; **21c** → 1c (1.05) + 2c (2.1); **10c** → 1c (exactly 0.5, rounds up); **30c** → 2c
(1.5) + 3c (3.0); **3c** → both minimums bind, 1c + 1c, net 1c; **1c** → 1c + 1c, net **−1c**;
**7c × 3 = 21c** → 3c of fees on the transaction, where per-item charging would give 6c.

### AC 3–5 — Implementation

`src/main/java/tradingpost/TradingPostSaleCalculator.java`, one new domain package, no existing
file changed.
`forSale(long grossUnitPriceCopper, long quantity)` → `SaleProceeds(grossUnitPriceCopper,
quantity, grossCopper, listingFeeCopper, exchangeFeeCopper, totalFeesCopper, netProceedsCopper)`.

- **Explicit basis (AC 5).** The only entry point demands a unit price *and* a quantity, so a
  caller cannot pass a bare "amount" and leave the grouping implicit; there is deliberately no
  gross-only overload and no default quantity. The Javadoc states that a per-craft, all-crafts or
  fractional expected quantity must be decided by the caller and names UD-011. The class selects
  no sale mode, reads no price source and holds no state.
- **Exact integer arithmetic (AC 3).** `long` throughout; each component is
  `(gross × percent + 50) / 100` then `Math.max(1, …)`. The two components are rounded
  **separately** and summed — never one 15%. No floating point anywhere.
- **Zero sale.** Zero quantity or zero unit price is not a transaction: gross 0, both fees 0, net
  0 (the minimums do not manufacture a fee out of no sale).
- **Invalid input.** Negative price or quantity → `IllegalArgumentException`.
- **Overflow.** `Math.multiplyExact` plus a documented public ceiling
  `MAX_GROSS_SALE_COPPER = (Long.MAX_VALUE − 50) / 10 = 922_337_203_685_477_575`, the largest
  gross for which `gross × 10 + 50` still fits a `long`; anything above it is rejected as
  `IllegalArgumentException` rather than allowed to wrap or to escape as `ArithmeticException`.
- **Market quote preserved (AC 4).** `grossUnitPriceCopper` and `quantity` are echoed unchanged
  beside the fee-adjusted figures, so gross quote, gross sale value, each fee, total fees and net
  proceeds are five distinct values. The caller supplies whichever of §20's instant-sell or
  listing-sell prices it selected and the fee is charged once against that price; the calculator
  cannot deduct twice because it never sees its own output.
- **Negative net.** Both minimums apply to a tiny non-zero sale, so a 1c sale nets −1c. That is
  the real economic result and is documented rather than clamped.
- **Not wired in (AC 5).** `CostEvaluator`, `CraftingPlanner`, `CraftResult`,
  `EctoSalvageCalculator` and every transport/presentation file are **unchanged** — verified by
  `git status`: the only `src/` additions are the new class and its test.

### AC 6 — Documentation

`DOMAIN_SPEC.md` gained **§25.1 Verified fee semantics** (source + retrieval date, the three
verified rules, the separate-rounding example, and the unverified-rounding-direction limitation
with the reasoning that excludes ceiling). `CURRENT_ARCHITECTURE.md` §2 (package layout), §3
(package responsibilities) and §9 item 7 (the two coexisting fee calculations and every still-
uncorrected consumer) record the implementation facts. UD-011 is untouched and still OPEN;
no consumer-policy question was answered here.

### Verification

`./mvnw test -Dtest=TradingPostSaleCalculatorTest` →
`Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 — in tradingpost.TradingPostSaleCalculatorTest`,
`BUILD SUCCESS`. All 13 expected amounts are written out by hand from the documented rule, never
produced by re-running the production formula. Covered: the PO's 300c example (15/30/45/255);
separate-vs-combined rounding at 15c asserted **unequal** to both the combined-15% and the ×0.85
result; half-away-from-zero at 10c/30c and round-down at 21c; both 1-copper minimums binding at
3c; negative net at 1c; whole-transaction basis (7c × 3 ≠ per-item); zero quantity and zero price;
market quote echoed unchanged; instant and listing prices each charged exactly once; the invariant
`gross − listing − exchange == net` and `gross == unit × quantity` across 2005 price/quantity
combinations; the `MAX_GROSS_SALE_COPPER` boundary accepted; that boundary +1 and
`Long.MAX_VALUE × 2` rejected; negative price and negative quantity rejected.

Re-run on resume: `./mvnw -o test -Dtest=TradingPostSaleCalculatorTest` →
`Tests run: 13, Failures: 0, Errors: 0, Skipped: 0 — in tradingpost.TradingPostSaleCalculatorTest`,
`BUILD SUCCESS`.

No existing shared abstraction was changed, so no existing suite was affected and none was re-run
(`TEST_STRATEGY.md` §20 — GitHub Actions is the full-regression gate). No database, GW2 API,
JavaFX or browser path is involved.

### Remaining uncertainty and what is explicitly NOT delivered

- **Displayed profits remain uncorrected.** Crafting Profit, Discovery, their shared table and
  fresh-detail results, the browser tables and the Ectoplasm screen all still show the pre-fee
  numbers. Request-008 stays NEEDS_USER; **no Phase 5 exit criterion or milestone transition is
  claimed.**
- **The rounding direction is unverified** (floor vs half-up), as recorded above. Every fee it
  could change differs by at most 1 copper per component per sale.
- **The AC 2 wiki evidence was not re-fetched on resume.** Web access was unavailable to the
  resuming session, so §25.1's source quote and retrieval date rest on the earlier attempt's
  2026-09-26 retrieval and were not independently re-read. The three verified rules it records
  (5% / 10% / 1-copper minimum each, charged on the whole transaction) are consistent with general
  knowledge of the game, but that is corroboration, not a second retrieval.
- Two fee calculations coexist until UD-011 unblocks integration (`CURRENT_ARCHITECTURE.md` §9
  item 7).
- The consumer map is from code read this session; `resolveDetail`'s row projection was traced
  through the shared `CraftingRowMapper`, not re-measured against a live database.

## Blockers

None for this story. Consumer integration remains blocked by
`agent/user-decisions/UD-011-trading-post-sale-quantity-basis.md` (OPEN), which was deliberately
not answered here.
