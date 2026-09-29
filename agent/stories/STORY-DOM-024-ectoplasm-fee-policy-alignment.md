## Story ID

STORY-DOM-024

## Title

Align Ectoplasm expected-value economics and labels with the decided fee policy

## Status

DONE

## Milestone

milestone-05

## Goal

Apply the resolved percentage policy consistently to Ectoplasm expected-value profitability while preserving gross price displays and the existing fee-inclusive net-cost/Luck relationship.

## Authoritative Source Documents / Sections

- docs/DOMAIN_SPEC.md sections 25 and 45-47: shared policy, expected yields and Luck cost.
- agent/user-decisions/UD-011-trading-post-sale-quantity-basis.md: no separate transaction-rounding/grouping model.
- agent/stories/STORY-DOM-022-trading-post-sale-fee-calculation.md, Result: Ectoplasm consumer map.
- agent/stories/STORY-WEB-013-ectoplasm-salvage-page.md: existing service, four scenarios and browser transport owner.
- Supplied docs/ROADMAP.md Phase 5: Ectoplasm screen and shared backend.
- docs/TARGET_ARCHITECTURE.md section 12 and docs/TEST_STRATEGY.md section 36.

## Context

DOM-022 records an Ectoplasm-local rounded net-unit-price calculation before scaling fractional expected yield. The resolved answer makes transaction-level rounding irrelevant and keeps displayed prices gross. This story aligns the existing domain/service and JavaFX behavior; WEB-013 subsequently exposes those authoritative values through HTTP and the browser.

## Acceptance Criteria

1. Inspect the current four Ecto-buy/Dust-sell scenarios and align profit with section 25's 15% fee on corresponding expected gross Dust sale value, deducted once. Preserve yields, live quote source, acquisition modes/costs and scenario identities.
2. Keep market quotes and displayed gross recovered sale values unchanged by fees. Keep economic net cost and cost per 1000 Luck consistent with DOMAIN_SPEC sections 46-47; they remain economic costs derived from fee-inclusive profit, not market-price displays. No fractional expected yield is modeled as an actual sale transaction.
3. Reuse the shared backend percentage-policy owner from DOM-023 if available, or the established appropriate domain abstraction; do not force reuse of DOM-022's transaction-rounding primitive. Keep transport and presentation formula-free. Avoid creating competing percentage implementations.
4. Preserve available precision and distinguish expected-value assumptions, missing values, zero and losses. Align JavaFX labels and fee wording so gross quotes are not called net, and displayed profit is clearly after 15% TP fees.
5. Expose all required authoritative scenario values at the application boundary for WEB-013. If its HTTP/browser consumer exists by implementation time, verify it preserves these values and correct affected labels; do not duplicate its screen/route work.
6. Record observed integration and verification in Result and update implementation documentation. No new exact-sale fee accuracy or milestone-completion claim.

## Required Tests

- Focused Ectoplasm domain tests with independent expected amounts for all four buy/sell scenarios, fractional expected Dust yield, zero/loss outcomes and unchanged acquisition/yield assumptions. Verify gross quotes stay gross and fee-inclusive net cost/Luck values remain consistent.
- Focused service projection tests preserving scenario values and unavailable values without presentation recalculation.
- Explicit local TestFX check of affected JavaFX Ectoplasm labels and values. If the browser consumer already exists, an explicit local browser smoke compares rendered values with its backend response; otherwise that integration check remains WEB-013's required verification.
- Run only directly affected local tests; the CI gate owns complete regression suites.

## Constraints

- No changes to salvage yields, supported items, synchronization, quote source or new user controls.
- No copper-rounding/grouping investigation and no fee deduction from displayed gross market prices.
- Preserve existing domain/application boundaries and JavaFX coexistence. WEB-013 owns new HTTP/browser workflow implementation.

## Dependencies

Resolved UD-011 and completed STORY-DOM-022. Queue after DOM-023 for shared policy reuse; no new transport/browser prerequisite.

## Definition of Done

The Ectoplasm domain/service and JavaFX follow the decided economics and gross-price presentation, targeted checks are recorded, documentation is current and the CI gate is green.

## Result

DONE. **One deduction moved, one field replaced, and every label that called a gross price "net"
corrected.** An interrupted attempt was **resumed, not restarted**: `ecto.EctoSalvageCalculator`,
`EctoView`, `web.EctoSalvageApiController`, `web.dto.EctoSalvageResponse`, the four Java test classes,
`frontend/src/api/types.ts`, `EctoSalvageScreen.vue` and its two test files were already on disk and
were verified against the acceptance criteria rather than rewritten. **One defect in that work was
found and fixed:** both browser smoke scripts had had their *expected* values renamed but not their
*selectors* — `renderedScenarios` still read the removed `[data-test="ecto-scenario-dust-recovered"]`
and the quote-panel assertion still read the two removed `dust-net-*` hooks, so `npm run smoke:ecto`
failed at step 3 with four `null` cells. Everything from those fixes onward, all verification and all
documentation was written in this session.

### The calculation (AC 1, AC 3)

`ecto.EctoSalvageCalculator` holds **no fee rate, multiplier or rounding rule of its own any more**:
its `SELL_FEE_PERCENT`, its private `0.85` `SELL_FEE_MULTIPLIER` and its `netSaleProceeds(int)` entry
point are gone, and `tradingpost.TradingPostFeePolicy` — DOM-023's shared owner of §25's decided
percentage policy — is the single source of the deduction. DOM-022's `TradingPostSaleCalculator`
transaction primitive was deliberately **not** reused, as §25.1 requires.

`evaluate(int ectoAcquisitionCost, int dustGrossUnitPrice)` now runs in §46's order — **yield first,
fee second**:

```text
expectedGrossRecoveredDustValue = round(dustGrossUnitPrice × DUST_PER_ECTO)   // gross, fee-free
netValueOfRecoveredDust         = TradingPostFeePolicy.netOfFee(that)         // 15% off, once
netCostPerEcto                  = ectoAcquisitionCost − netValueOfRecoveredDust
profitPerEcto                   = −netCostPerEcto
costPer1000Luck                 = netCostPerEcto × ECTOS_PER_1000_LUCK
```

The fee therefore lands **once**, on the aggregate expected gross Dust value of one Ectoplasm, and no
Dust unit is rounded into a modeled sale before the fractional yield is applied. The superseded order
(15% off the per-unit quote, then scale) reaches 128c where the decided one reaches 127c at a 200c
quote, and the domain suite pins that difference explicitly. A recovered value of zero carries no fee
— nothing recovered is nothing sold, the same rule `craft.CraftingPlanner` applies to a non-positive
revenue — which also keeps the policy's non-negative precondition satisfied.

`DUST_PER_ECTO = 0.75`, `LUCK_PER_ECTO = 20.0`, `ECTOS_PER_1000_LUCK = 50`, the live
`api.tp.EctoLivePriceGateway` quote source, the four scenario identities and both acquisition modes are
untouched (AC 1 constraints). `application.EctoSalvageService` needed **no change at all**: it already
passed the quote pairs straight through and reported what the domain produced.

### Gross stays gross (AC 2, AC 4)

`ScenarioResult`'s `dustNetUnitPrice` — a per-Dust-unit price *after* the fee, i.e. exactly the modeled
per-unit sale §46 forbids — is replaced by `expectedGrossRecoveredDustValue`. The three gross fields
(`ectoAcquisitionCost`, `dustGrossUnitPrice`, `expectedGrossRecoveredDustValue`) are the ones a client
displays as prices; the four fee-inclusive ones are economic results. `netCostPerEcto` and
`costPer1000Luck` keep §46–§47's existing fee-inclusive basis, and no fee touches the acquisition cost
or the Luck amount.

In JavaFX (`EctoView`): the two Dust quote rows are labelled `Instant Sell (gross)` / `Listing Sell
(gross)`, and the row that used to read `Instant/Listing Sell (net of TP fee)` now shows the **gross**
`Recovered Dust / ecto` value from the new field. Both profitability tables state the fee in their
titles — `ECTO SALVAGE PROFIT (per ecto, after 15% TP fees)` and `Cost per 1000 Luck ~ 50 ectos (after
15% TP fees)` — the percentage interpolated from `TradingPostFeePolicy.PROFIT_FEE_PERCENT` rather than
written out, and the fee notice now says every displayed price is gross instead of describing some of
them as net. **Losses are distinguishable again:** `fillProfitGrid` formats with `CoinUtils.format`,
not `formatSigned`, which strips the minus sign (`KNOWN_PROBLEMS.md` KP-09) — a salvage profit is
normally a loss, so all four cells had been rendering as gains.

### Transport and browser (AC 5)

WEB-013's consumer already existed, so it was verified and corrected rather than re-implemented; no
route, screen, state model or new control was added. `EctoScenarioDto.dustNetUnitPriceCopper` became
`expectedGrossRecoveredDustValueCopper` in the same position, and `assumptions.tradingPostSellFeePercent`
now reads `TradingPostFeePolicy.PROFIT_FEE_PERCENT` — the same owner the calculation deducts through,
so the stated rate cannot drift from the deducted one. The boundary still copies field for field: no
fee, yield, sign or default is computed there.

In the browser the scenario table shows the recovered Dust value **twice** — `gross, per ecto` and
`after 15% TP fees, per ecto`, each from its own backend field — every fee-inclusive column note names
the backend's own percentage, and the price panel lost its two "after fee" rows entirely, because a
market price is never displayed with the fee taken off. No arithmetic was added to the frontend.

### Tests run (exact commands, real output)

- `./mvnw test -Dtest='EctoSalvageCalculatorTest,EctoSalvageServiceTest,EctoSalvageApiControllerTest'`
  — **Tests run: 30, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS.** `EctoSalvageCalculatorTest`
  was rewritten around the decided rule with **every expected amount written out by hand**, never
  recomputed from the production formula: the four scenarios (150c/180c gross recovered → 127c/153c
  net → −873/−847/−773/−747 profit → 43 650/42 350/38 650/37 350 Luck cost), both quotes asserted
  unchanged in every scenario, the order pinned (`theYieldIsScaledBeforeTheFeeRatherThanAfterAModeled
  PerUnitSale` asserts 127c *and* names the 128c the old order produced), fractional yields carried as
  expected values (201c → 150.75 → 151c; 2c → 1.5 → 2c with a 0c fee, since §25 charges a rate and
  §25.1's minimums are not substituted), a zero Dust quote charged nothing, break-even, a loss keeping
  its sign, a gain reported as positive profit with negative net cost, and the deducted rate checked
  against `TradingPostFeePolicy.PROFIT_FEE_PERCENT` on a value the percentage divides exactly
  (200c → 30c). `EctoSalvageServiceTest` gained a projection test naming which quote pair reached
  which scenario, so a swapped acquisition mode or a reused Dust quote fails independently of whatever
  the domain would produce for it; its unavailable-quote cases are unchanged.
- `./mvnw test -Dtest=EctoSalvageViewIT` — **Tests run: 2, Failures: 0, Errors: 0, BUILD SUCCESS**
  (**local TestFX, real desktop**, the layer the CI gate cannot run). The existing case now asserts
  hand-written amounts (`-0g 8s 73c`, `4g 36s 50c`) instead of re-deriving them from the calculator, so
  it would fail if the view and the domain disagreed. The new case reads the gross Dust quotes
  (`0g 2s 0c` / `0g 2s 40c`), the gross recovered values (`0g 1s 50c` / `0g 1s 80c` — deliberately
  *not* the 127c/153c the profit figures use), both table titles' "after 15% TP fees", and the notice's
  "already deduct"/"15%"/"gross" with `net of TP fee` asserted **absent**.
- `npx vitest run src/ecto` — **2 files, 21 tests passed.** Both fixtures stay deliberately
  incoherent (the after-fee value is not the gross one less the stated percentage, the gross one is not
  the quote scaled by the yield), the new gross column is asserted separately including a supplied
  zero beside a non-zero after-fee value on the same row, the seven `.column-note` texts are asserted
  as an exact list against the fixture's 12% fee, and the two removed `dust-net-*` hooks are asserted
  **absent**.
- `npm run type-check` and `npm run build` — clean.
- `npm run smoke:ecto` — **PASSED, 10 steps**, real Chrome against this script's own stub origin on
  `127.0.0.1:5182`, after the selector fixes above and a rebuild. The renamed columns are compared
  field for field against the stub's deliberately incoherent numbers and 12% fee, and a new assertion
  reads the whole price panel and fails if any part of it is labelled after the fee.
- `npm run smoke:ecto:live` (**local, live GW2 API**, `GW2_FRONTEND_URL=http://127.0.0.1:5183`,
  `GW2_BACKEND_ORIGIN=http://127.0.0.1:8080`) — **PASSED**, opening and reload each rendered exactly
  the live response received; 5 timed samples, min 47 ms / median 477 ms / max 765 ms. The served
  response was checked by hand against §46: Dust instant-sell quote **1023c** reported gross,
  `1023 × 0.75 = 767.25 → 767c` gross recovered, `15% of 767 = 115.05 → 115c`, `767 − 115 = 652c` net,
  `1795 − 652 = 1143c` net cost, `−1143c` profit rendered `-11s 43c`, `× 50 = 57 150c` per 1000 Luck.
  Per `tasks/lessons.md`, the backend and a dedicated dev server were started fresh for this (the
  build had changed the DTO), the dev server was bound to `--host 127.0.0.1` after the first attempt
  proved Vite's default bind answers on `::1` only, and afterwards both this session's process trees
  were killed by matched command line — a pre-existing four-day-old `spring-boot:run` tree was left
  alone — with `curl` confirming 8080 and 5183 released.

### Limits and remaining uncertainty

- Suites outside the commands above, the other browser smoke scripts and the real-database `*IT`
  checks were not run locally; GitHub Actions is the full-regression gate (§20/§36).
- Half-away-from-zero rounding on the fee is the shared policy's project convention, not verified game
  behavior (§25.1); this story adopted it rather than deciding it. The single `Math.round` on the
  expected gross recovered value is the only rounding the Ectoplasm path adds, and it precedes the fee.
- The live evidence is one market snapshot: a live no-result and a live upstream failure were never
  observed here, and the 765 ms upstream sample was not diagnosed.
- **No exact-sale fee accuracy claim and no Phase 5 or milestone-completion claim** is made;
  `STORY-QUALITY-005`, `STORY-PERF-002` and `STORY-WEB-016` remain outstanding.

Docs: `CURRENT_ARCHITECTURE.md` (§2's `ecto` tree entry, the `ecto` and `tradingpost` package rows,
§5.3's flow diagram, §5.11's Ectoplasm page bullet, §5.15's field list and derivability note, and §9
item 7 now recording the Ectoplasm side resolved with `TradingPostSaleCalculator` as the one remaining
uncalled primitive), `KNOWN_PROBLEMS.md` KP-09 (the Ecto sign-loss half recorded as resolved, the
`formatSigned` and Buy Cost halves left open) and the §35 guide `docs/crafting/README.md` (the
Ectoplasm fee paragraph: the new order, the gross/after-fee pair and the removed net quote).
`DOMAIN_SPEC.md` §25 and §45–§47 already specified exactly this rule and needed **no** change — this
story brought the implementation to the spec, not the reverse. `CURRENT_STATE_SPEC.md` §19 describes
the calculator at a level ("15%") this change does not alter.

## Blockers

None.

## Follow-up Findings

F001: `util.CoinUtils.formatSigned` still returns `format(Math.abs(copper))`, so it prints every loss
as a gain (`KNOWN_PROBLEMS.md` KP-09). This story only stopped the Ectoplasm profit grid from calling
it; `CraftingDiscoveryView` (2 call sites) and `CraftingProfitView` (2) still do, and
`CraftingProfitView.java:909` reads `setText((signed ? CoinUtils.formatSigned(v) : CoinUtils.formatSigned(v)))`
— both branches of the ternary are identical, so its `signed` flag has no effect at all and the
unsigned column is formatted by the signed formatter.

F002: `frontend/scripts/ecto-browser-smoke.mjs` (and the other `smoke:*` scripts sharing the pattern)
checks only that `frontend/dist/index.html` *exists*, not that it is newer than `src/`, and then drives
Chrome against whatever bundle is there. The first run in this session therefore tested the previous
build of the screen. It failed loudly only because the change was a rename; an additive change would
let a stale bundle produce a green run, which is the kind of evidence these checks exist to prevent.



## Follow-up Findings Disposition

F001: FOLLOW-UP STORY ? STORY-UI-003
F002: FOLLOW-UP STORY ? STORY-WEB-018
