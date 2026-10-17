## Story ID

STORY-APP-011

## Title

Display and sort by the authoritative crafting total profit

## Status

DONE

## Milestone

milestone-03

## Goal

Pass the existing application/domain total-profit result through to CraftingProfitView instead of recomputing it in presentation.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md section 7: Phase 3 application extraction objective and JavaFX delegation high-level story.
- docs/KNOWN_PROBLEMS.md section 4.4, CH-E2: duplicated total-profit ownership.
- agent/stories/STORY-QUALITY-003-phase-three-completion-review.md Result, F3.

## Context

The review records that CraftingProfitController.UiRow already carries totalProfitCopper, but CraftRow drops it and the total-profit column and both comparators multiply craftableCount by profitCopper. No current numeric divergence was evidenced. This story removes the duplicated ownership without changing the domain formula.

## Acceptance Criteria

1. Carry UiRow.totalProfitCopper into CraftRow.totalProfitCopper when constructing displayed rows.
2. Use that supplied value for the total-profit column and both total-profit comparators; remove their independent multiplication formula.
3. Preserve currency formatting, blocked-row presentation, filter behavior and sort direction/tie behavior. Do not change domain calculations or per-item profit semantics.
4. Update the total-profit portion of KNOWN_PROBLEMS section 4.4 only after verification; retain any independently unresolved selector finding. Record changed ownership and verification in Result.

## Required Tests

- A focused presentation regression supplies an authoritative total deliberately different from craftableCount multiplied by profitCopper, proving row mapping and displayed total use the supplied value.
- Verify both total-profit sort paths order rows using supplied totals, with contrasting values that would order differently under the old multiplication. Reuse existing presentation/UI verification facilities.
- Run relevant existing crafting-profit presentation checks for formatting and blocked-row preservation; no broad test expansion is required.

## Constraints

- No new profit formula, domain-rule changes, selector redesign, speculative cleanup or milestone closure.
- Preserve the accepted performance behavior; no new data loading or calculation work.

## Dependencies

STORY-APP-001 and STORY-QUALITY-003 (both recorded DONE).

## Definition of Done

The view displays and sorts the authoritative total, focused checks demonstrate it cannot silently recompute the old formula, and the story Result and known-problem status reflect the evidence.

## Result

**Ownership changed.** `CraftingProfitView` no longer derives total profit. The application layer's
value now flows through unbroken:
`CraftingPlanner` → `CraftResult.totalProfitCopper` → `CraftingProfitController.UiRow.totalProfitCopper`
→ `CraftRow.totalProfitCopper` → the "Total profit" column and both total-profit sort paths.

Three changes in `src/main/java/CraftingProfitView.java`, nothing else in production code:

1. `CraftRow` gained a `totalProfitCopper` constructor parameter that sets the already-existing but
   never-assigned `totalProfitCopper` property (AC1). The row construction loop inside `show(...)`
   moved verbatim into a package-private `toCraftRow(CraftingProfitController.UiRow)`, so the
   mapping is directly checkable without a window; every other field is passed through unchanged.
2. The "Total profit" column's cell value factory now binds to `totalProfitCopperProperty()` —
   the same one-line form the neighbouring "Profit per craft" column already uses — replacing
   `new SimpleIntegerProperty(craftable * profitPer)` (AC2). Its cell factory is still the shared
   `profitCell()`, so copper formatting and the blocked-row "Unavailable" text are untouched.
3. The sort-box comparator `switch` moved out of `applyClientFilterAndSort(...)` into a
   package-private `rowComparator(String sortMode)`; its "Total profit" case and its default
   fallback are now `Comparator.comparingInt(CraftRow::getTotalProfitCopper).reversed()` (AC2).
   The other three cases are byte-for-byte unchanged, including "Total sell value"'s own
   `revenueCopper * craftableCount` product, which is a different figure and out of scope.

`applyClientFilterAndSort(...)` keeps its search-blob filter, its single stable `FXCollections.sort`
and `table.refresh()`, so filter behavior, descending direction and tie order (stable sort, equal
keys keep input order) are preserved (AC3). No domain class was touched; `CraftingPlanner` still
computes `profitOne * craftCount` and `profitCopper` still means profit per craft. No new data
loading or calculation work, so `TARGET_ARCHITECTURE.md` §33's accepted page-load measurement is
unaffected and was deliberately not re-run.

**Verification.** New `src/test/java/CraftingProfitTotalProfitPresentationTest.java` (5 tests, plain
JUnit — `CraftRow` is a property holder and both new seams are pure, so no TestFX session or
database is needed):

- row mapping carries a supplied total of `4242` for a row with `craftableCount 5` / `profitCopper
  100`, with an explicit guard asserting the supplied total differs from that product, plus
  assertions that the other displayed numbers still come from the same `UiRow`;
- the "Total profit" sort path and the default fallback each order `(5, 100, total 1)` after
  `(1, 10, total 900)` — the exact reverse of the old multiplication's ordering, so a recomputation
  cannot pass;
- descending direction and input order on tied totals;
- the three unrelated sort modes still order as before.

This construction is only possible with hand-built rows: the domain's own total *is* today's
product, so no database fixture could distinguish the two formulas — which is precisely why the
review found no numeric divergence, and why a real-data test would have proved nothing.

Runs on the final tree: `./mvnw test` — `Tests run: 170, Failures: 0, Errors: 0`. Real-view checks
re-run for formatting, blocked rows and filter/sort preservation:
`uiverify.CraftingProfitViewSmokeIT` and `uiverify.CraftingProfitViewBlockedRowIT`
(`Tests run: 2, Failures: 0`) and `uiverify.CraftingProfitViewRefreshPreservationIT`
(`Tests run: 1, Failures: 0`). No test was weakened or deleted; no broad test expansion.

**Documentation.** `docs/KNOWN_PROBLEMS.md` §4.4's closing paragraph now records the total-profit
portion as resolved with its verification, and §10's CH-E2 line records both halves resolved. The
selector paragraph from `STORY-APP-010` was left exactly as it stood — it was already resolved, so
no independently unresolved selector finding existed to retain. `docs/CURRENT_ARCHITECTURE.md` §4's
dependency-direction bullet, which asserted the duplication "remains open", was corrected at its
owner. No user-visible crafting rule changed, so `docs/crafting/` is untouched (the "Total profit =
craftcount × Profit per craft" column tooltip stays accurate: that is still the domain's formula,
just no longer re-derived in the view).

**Remaining uncertainty.** The column-to-property binding itself (one line) is covered by reading,
not by an assertion on a live `TableColumn`: the existing real-view ITs confirm the table still
renders correctly, but they cannot distinguish the two formulas for the reason given above. A row
constructed outside `toCraftRow(...)` could still supply a wrong total, but no other construction
site exists in the codebase.

## Blockers

None.
