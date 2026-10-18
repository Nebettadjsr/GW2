## Story ID

STORY-APP-013

## Title

Carry authoritative total sell value into the JavaFX Profit view

## Status

DONE

## Milestone

milestone-05

## Goal

Have the JavaFX Profit view display the backend-provided total sell value instead of deriving it from revenue and craftable count in the view.

## Authoritative Source Documents / Sections

- agent/stories/STORY-DOM-023-crafting-profit-fee-integration.md, Follow-up Findings F002.
- agent/stories/STORY-APP-011-display-authoritative-total-profit.md.
- Supplied docs/ROADMAP.md Phase 5 exit criterion: both JavaFX and web UIs are driven by the same backend.

## Context

DOM-023 records that CraftingProfitView multiplies revenue by craftableCount despite CraftResult.totalSellValueCopper already being the authoritative field. Carrying the supplied value avoids a second derivation in the JavaFX presentation path.

## Acceptance Criteria

1. Carry CraftResult.totalSellValueCopper through CraftingProfitController.UiRow into CraftingProfitView.
2. Display the supplied total sell value without recomputing revenue multiplied by craftable count in the view.
3. Preserve the gross-value meaning and existing unavailable/null handling.
4. Add focused verification that a distinctive supplied total is displayed unchanged.

## Required Tests

- Focused controller/view tests verifying totalSellValueCopper is passed through and displayed unchanged, including applicable unavailable-value behavior.
- Explicit local TestFX verification if the changed presentation is covered by the existing JavaFX verification path; record any unavailable interactive check as a limitation.

## Constraints

- Presentation/data-flow change only; do not change domain calculations, HTTP contracts, or JavaFX coexistence.
- No unrelated UI cleanup.

## Dependencies

Completed STORY-DOM-023 and STORY-APP-011.

## Definition of Done

JavaFX displays the authoritative supplied total sell value, targeted evidence and limitations are recorded, and the revision passes the GitHub CI gate.

## Result

**Ownership changed, nothing computed differently.** The JavaFX presentation path no longer derives
the total sell value. The domain's own figure now flows through unbroken:
`CraftingPlanner` → `CraftResult.totalSellValueCopper` → `CraftingProfitController.UiRow.totalSellValueCopper`
→ `CraftRow.totalSellValueCopper` → the "Total sell value" column and its sort path. This is the
duplication `STORY-DOM-023` F002 recorded, removed the same way `STORY-APP-011` removed it for Total
profit.

Two production files, five edits, nothing else:

1. `src/main/java/CraftingProfitController.java` — `UiRow` gained a `totalSellValueCopper` field and
   constructor parameter, documented as carried rather than derived; `prepareRows(...)` fills it from
   `cr.totalSellValueCopper` beside the `cr.totalProfitCopper` it already passed (AC1). No other
   field, the missing-summary text, the search blob and the `CraftingResultPresentation` availability
   flag are untouched.
2. `src/main/java/CraftingProfitView.java` — `CraftRow` gained a matching `totalSellValueCopper`
   property and constructor parameter; `toCraftRow(...)` passes the supplied value straight through.
   The "Total sell value" column's cell value factory is now
   `data -> data.getValue().totalSellValueCopperProperty()`, the same one-line form its neighbours
   use, replacing `new SimpleIntegerProperty(r.getRevenueCopper() * r.getCraftableCount())` (AC2).
   Its cell factory is still the shared `coinCell(false)`, so copper formatting and the blocked-row
   "Unavailable" text are byte-for-byte unchanged (AC3). `rowComparator(...)`'s "Total sell value"
   case reads `CraftRow::getTotalSellValueCopper` instead of repeating the product, so the table
   sorts by exactly the value it displays rather than by a second derivation of it.

**Gross meaning preserved (AC3).** `DOMAIN_SPEC.md` §2.1.1's total sell value is already the gross,
fee-free figure `CraftResult` carries (`revenueCopper × craftableCount`, computed once in the
domain), so carrying it changes no amount: the column shows the same number it showed before. No
domain calculation, HTTP contract, DTO, mapper or JavaFX/web coexistence was touched; the web row
(`web.CraftingRowMapper`) already read the same authoritative field and is unaffected. The
unavailable/null path is unchanged in both halves — `calculationAvailable` still comes from
`CraftingResultPresentation` and still makes the cell render "Unavailable" whatever the value is.

**Verification.** New `src/test/java/CraftingProfitTotalSellValuePresentationTest.java` (5 tests,
plain JUnit — `prepareRows`, `toCraftRow` and `rowComparator` are pure and `CraftRow` is a property
holder, so no TestFX session or database is needed). Every fixture supplies a total sell value that
deliberately differs from its own `revenueCopper × craftableCount`, because the domain's real value
*is* that product today and a planner-built fixture could not tell the two formulas apart:

- `prepareRows(...)` carries a `CraftResult` whose supplied total is **4 242** for a row with
  craftable count 5 and item sell price 30, with an explicit guard asserting the supplied total
  differs from that product, plus assertions that the neighbouring numbers still come from the same
  result (AC4);
- `toCraftRow(...)` carries that same 4 242 into `CraftRow.totalSellValueCopperProperty()`, the
  property the column binds to;
- the "Total sell value" sort orders `(5 × 30, total 1)` after `(1 × 10, total 900)` — the exact
  reverse of the old multiplication's ordering — and stays descending with input order on ties;
- an unavailable row (0 craftable, so `calculationAvailable` false) still carries its supplied total
  unchanged and still reaches the displayed row with the flag the coin cell reads.

Commands and real output on the final tree:

- `./mvnw test -Dtest='CraftingProfitTotalSellValuePresentationTest,CraftingProfitTotalProfitPresentationTest'`
  — **Tests run: 10, Failures: 0, Errors: 0, BUILD SUCCESS.**
- `./mvnw test -Dtest='CraftingProfitTotalSellValuePresentationTest,CraftingProfitTotalProfitPresentationTest,CraftingBlockedRowsTest'`
  — **Tests run: 13, Failures: 0, Errors: 0, BUILD SUCCESS** (run before the negative check below;
  `CraftingBlockedRowsTest` exercises `prepareRows` on blocked results and is unchanged).
- **Negative check:** the comparator was temporarily reverted to
  `r.getRevenueCopper() * r.getCraftableCount()` and the new class re-run —
  **Tests run: 5, Failures: 2** (`totalSellValueSortUsesSuppliedTotals` expected
  `[High, Low]` but was `[Low, High]`; `totalSellValueSortStaysDescendingAndKeepsInputOrderOnTies`
  expected `[TiedFirst, TiedSecond, Middle, Lowest]` but was `[TiedFirst, Lowest, TiedSecond,
  Middle]`). The revert was undone and the suite re-run green, so the new assertions provably detect
  a recomputation rather than agreeing with one.
- **TestFX, on this desktop against a disposable Postgres schema** (the existing JavaFX verification
  path does cover the changed column — `CraftingProfitViewSmokeIT` asserts the real table's "Total
  sell value" cell reads 2 000c):
  `./mvnw test -Dtest=CraftingProfitViewSmokeIT` — **Tests run: 2, Failures: 0, Errors: 0, BUILD
  SUCCESS**, so the real rendered value is unchanged by the rebinding.
  `./mvnw test -Dtest=CraftingProfitViewBlockedRowIT` — **Tests run: 1, Failures: 0, Errors: 0,
  BUILD SUCCESS**, blocked-row presentation intact.

`CraftingProfitTotalProfitPresentationTest`'s `UiRow` helper gained the new constructor argument as
`30 * craftableCount`, which is what its fixtures' item sell price times count already implied, so
its "Total sell value stays ordered by …" assertion keeps the meaning STORY-APP-011 gave it; nothing
in that class was weakened or deleted. No other construction site of either row type exists.

**Documentation.** `docs/CURRENT_ARCHITECTURE.md` §4's dependency-direction bullet owned the
statement that the view displays and sorts the supplied total profit and "no longer derives that
figure itself"; it now records the total sell value on the same footing. No user-visible number,
label, tooltip or crafting rule changed, so `docs/crafting/`, `DOMAIN_SPEC.md` and
`CURRENT_STATE_SPEC.md` needed no edit — the column tooltip "Total Sell Value = craftcount × Item
Sell Price" still states the domain's own formula, which is unchanged and simply no longer repeated
in the view.

**Remaining uncertainty.** The column-to-property binding itself is one line covered by reading plus
the smoke IT's displayed-value assertion; as with STORY-APP-011, no real-data fixture can distinguish
the two formulas, because the domain's value is that product. Suites outside the commands above,
frontend tests, browser smoke scripts and the other real-database `*IT` checks were not run locally —
GitHub Actions is the full-regression gate (`TEST_STRATEGY.md` §20/§36). No Phase 5 completion or
performance claim is implied.

## Follow-up Findings

None.

## Blockers

None.
