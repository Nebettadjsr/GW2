# STORY-DOM-013: Expose blocked crafting rows

## Story ID

STORY-DOM-013

## Title

Preserve unavailable and blocked results in Crafting Profit and Discovery

## Status

DONE

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
- Verify result preservation in both flows with automated tests at the narrowest practical existing boundary. Use STORY-UI-001's reusable capability to assert that unavailable results and reasons are displayed in both real views; record manual fallback only for specifically documented impractical automation cases.
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

**What changed:** `CraftingProfitController.hasZeroPricedBuy(...)`'s controller-level row filtering
(`docs/KNOWN_PROBLEMS.md` §3.5) is removed. `CraftingProfitController`/`CraftingDiscoveryController`
now route result preparation through a shared, testable `prepareRows(...)` boundary and a new
`CraftingResultPresentation` (`src/main/java/CraftingResultPresentation.java`) that recomputes
"a required purchase has no usable price" (missing, zero, or negative — `DOMAIN_SPEC.md` §21) and
reports `BlockedReason.PRICE_UNAVAILABLE` instead of dropping the row. Both `CraftingProfitView`
and `CraftingDiscoveryView` gained a "Status / requirements" column showing the blocked reason and
replace numeric buy-cost/revenue/profit cells with literal "Unavailable" text (never a fabricated
zero/free cost or a completed profit figure) whenever `calculationAvailable` is false. Valid,
evaluable rows are unaffected — they keep their existing `craftableCount`/`buyCostCopper`/
`revenueCopper`/`profitCopper`/`totalProfitCopper` values from the existing domain calculation.
`docs/KNOWN_PROBLEMS.md` §3.5 (plus its two summary-table cross-references) updated from "Partially
Resolved" to "Resolved" with this evidence.

Also fixed, as a precondition for actually running the real-view regressions below:
`src/test/java/uiverify/CraftingUiTestFixtures.java` left an empty (invalid-JSON) crafting-graph-
cache temp file in place; `craft.CraftingGraphCache.load()` only rebuilds from the repository when
the file does not exist, so every fixture-backed UI test failed to parse it. Fixed by deleting the
reserved temp path immediately after creation, exactly matching STORY-UI-001's temp-file pattern.

**Why:** `docs/DOMAIN_SPEC.md` §21 requires a missing/zero/negative required purchase price be
treated as an explicit "unavailable" state, never resolved as free; §41 requires feasibility and
profitability to stay distinguishable; §42 lists `PRICE_UNAVAILABLE` as a blocked reason the domain
must preserve. The controller was silently dropping the entire row instead, which is the exact
defect `docs/KNOWN_PROBLEMS.md` §3.5 documented as still open after `BlockedReason` itself was
extended by an earlier story.

**Tests run** (all via a directly-invoked cached Maven distribution, `JAVA_HOME` =
`C:\Users\Administrator\.jdks\openjdk-25.0.1`, since the `mvnw.cmd` wrapper's embedded PowerShell
call is unavailable in this environment; equivalent to `./mvnw`):
- `mvn -o clean test` — full default suite, 53 tests, 0 failures/errors, including the new
  `CraftingBlockedRowsTest` (3 tests: required-unpriced-purchase survives `prepareRows` for both
  controllers across missing/absent-quote/zero/negative prices × instant/listing buy mode; planner-
  produced `PRICE_UNAVAILABLE` reaches both controllers' rows; a valid-price control case keeps its
  existing `craftableCount`/`buyCostCopper`/`revenueCopper`/`profitCopper`/`totalProfitCopper`).
- `mvn -o test -Dtest=CraftingProfitViewBlockedRowIT` — real `CraftingProfitView`/`Gw2App`, disposable
  Postgres schema, local desktop session (STORY-UI-001's harness): a recipe requiring 2 unpriced
  "UI Test Ore" stays visible with 0 craftable and a `PRICE_UNAVAILABLE` status after enabling
  "buy missing mats". First invocation failed on a cold JVM/JavaFX start (`No ComboBox with prompt
  text "Character" found` — the view had not finished laying out before the lookup ran); re-run
  passed 3/3 consecutively once the JVM was warm, matching this project's existing headful-only,
  no-Monocle TestFX limitation recorded in `docs/TEST_STRATEGY.md` §32.6 rather than a defect in the
  fix itself.
- `mvn -o test -Dtest=CraftingDiscoveryViewBlockedRowIT` — same fixture shape for
  `CraftingDiscoveryView`/`CraftingDiscoveryController` (a genuine not-yet-discovered recipe, not
  yet in `account_recipes`): passed 2/2 consecutively.
- `mvn -o test -Dtest=FxCompatibilityPrototypeIT,CraftingProfitViewSmokeIT,CraftingProfitViewBlockedRowIT,CraftingDiscoveryViewBlockedRowIT`
  — all four real-view TestFX tests together, 4/4 passed, confirming no cross-test interference.
- `mvn -o test` (default goal, re-run after the above) — 53 tests, 0 failures, confirming the IT
  suffix still excludes all four TestFX tests from the default goal.

**Remaining uncertainty:**
- The cold-start ComboBox-lookup flake observed on `CraftingProfitViewBlockedRowIT`'s first run is a
  pre-existing property of this headful, no-wait-before-first-lookup TestFX pattern (shared by the
  already-DONE `CraftingProfitViewSmokeIT`), not something this story introduced or fixed; recorded
  here as observed fact rather than resolved, since resolving it is outside this story's scope.
- `CraftingProfitController`/`CraftingDiscoveryController`'s pre-existing `if (cr == null) continue`
  guard in `prepareRows(...)` (a missing-result, not a blocked-result, safeguard) was inspected and
  left unchanged — it cannot silently drop a result that reached evaluation, so it does not fall
  under this story's "silent exclusion" scope.
- `docs/KNOWN_PROBLEMS.md` §3.7 (Crafting Profit's coordinated-scope selector) and the associated
  `STORY-DOM-014`/`STORY-DOM-015` remain untouched and out of scope for this story.

## Blockers

None.
