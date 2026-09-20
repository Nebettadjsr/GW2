## Story ID

STORY-DOM-008

## Title

Add the remaining missing BlockedReason values: RECIPE_NOT_ALLOWED and INSUFFICIENT_BUDGET

## Status

DONE

## Milestone

milestone-00

## Goal

Finish the `docs/DOMAIN_SPEC.md` §42 blocked-reason set started by `STORY-DOM-003` (which added `PRICE_UNAVAILABLE`) by adding `RECIPE_NOT_ALLOWED` and `INSUFFICIENT_BUDGET` to `craft.BlockedReason`, and setting each at the specific, already-identified spots in `craft.CraftingResolver`/`craft.RecipeSimulator` where that exact situation occurs today but is currently collapsed into a generic or absent reason, per `docs/KNOWN_PROBLEMS.md` §3.5 and `docs/ROADMAP.md` Phase 1's "Add the missing BlockedReason values" item.

## Authoritative Source Documents / Sections

- `docs/DOMAIN_SPEC.md` §19 "Buying Budget", §42 "Blocked Reasons".
- `docs/KNOWN_PROBLEMS.md` §3.5 (the parent conflict; `PRICE_UNAVAILABLE` is done, `RECIPE_NOT_ALLOWED`/`INSUFFICIENT_BUDGET` remain per its own text).
- `docs/TEST_STRATEGY.md` §6.10 (Buying Budget), §6.8 (Multiple Recipes), §24 (priority order).
- `src/main/java/craft/BlockedReason.java`.
- `src/main/java/craft/CraftingResolver.java` — `firstRecipeFor(int, PlannerContext, String)` (around line 378): returns `null` uniformly both when no recipe exists for an item at all (`list == null || list.isEmpty()`) and when every candidate recipe is filtered out by `ctx.allowedRecipeIds.contains(...)` (the loop's `continue`). Callers of `firstRecipeFor` currently treat any `null` as `NO_RECIPE`.
- `src/main/java/craft/RecipeSimulator.java` — `simulatePhase(...)` (around line 72): `if (ctx.settings.maxBuyCopper > 0 && nextBuyTotal > ctx.settings.maxBuyCopper) break;` silently stops accumulating further crafts with no record that the budget was the limiting factor.

## Context

`STORY-DOM-003` established the pattern for this exact class of fix: add the specific `BlockedReason` value `DOMAIN_SPEC.md` §42 names, set it at the precise point the situation is detected, and cover it with a regression test — without conflating it with UI-level row filtering (left out of scope there too). This story applies the same pattern to the two reasons `STORY-DOM-003` explicitly deferred.

`RECIPE_NOT_ALLOWED` belongs where `firstRecipeFor` currently cannot distinguish "no recipe exists for this item" from "a recipe exists but none of the candidates are in `ctx.allowedRecipeIds`" — both cases return `null` today and are reported as `NO_RECIPE` by callers.

`INSUFFICIENT_BUDGET` belongs where a positive `maxBuyCopper` budget prevents even the first attempted craft's cumulative purchase cost from being affordable (`craftCount` cannot advance past 0 because of the budget cap specifically, not because of `NO_RECIPE`/`PRICE_UNAVAILABLE`/other reasons) — `DOMAIN_SPEC.md` §19's "purchase cost <= budget for feasible plans" rule already defines the correctness condition; what's missing is surfacing *why* a plan was infeasible when budget was the specific cause.

## Acceptance Criteria

- `craft.BlockedReason` gains `RECIPE_NOT_ALLOWED` and `INSUFFICIENT_BUDGET`.
- `firstRecipeFor` (or its callers) distinguish "no recipe exists at all" from "a recipe exists but is filtered out by `allowedRecipeIds`," and the latter case results in `BlockedReason.RECIPE_NOT_ALLOWED` instead of `NO_RECIPE` wherever that distinction currently determines the reported reason.
- When a positive `maxBuyCopper` budget is the specific reason a craft/purchase path could not proceed (per the exact code location cited above), the domain layer marks the result with `BlockedReason.INSUFFICIENT_BUDGET` instead of leaving it indistinguishable from "not profitable" or an unset/`NONE` reason.
- Two new tests (one per reason) demonstrate each case using `CraftTestFixtures`, following the `Given/When/Then` style already used in `CraftingResolverPriceUnavailableTest`/`CraftingResolverMultipleRecipeSelectionTest`.
- `./mvnw test` — the new tests plus the full existing suite pass.

## Required Tests

- New test(s) demonstrating `RECIPE_NOT_ALLOWED` is set when a recipe exists for an item but none of its candidate recipes are in `allowedRecipeIds`.
- New test(s) demonstrating `INSUFFICIENT_BUDGET` is set when a positive budget specifically prevents an otherwise-valid craft/purchase path from proceeding.
- `./mvnw test` — full existing suite must continue to pass.

## Constraints

- Do not change `CraftingProfitController`/`CraftingDiscoveryController` UI-level row filtering in this story — domain-layer scope only, matching `STORY-DOM-003`'s precedent.
- Do not change the actual budget/recipe-selection logic's outcomes (which recipe is chosen, how many crafts are attempted) — only add the missing reason reporting around the existing, already-correct decisions.
- If distinguishing "budget-limited" from "genuinely infeasible for another reason" turns out to require a larger restructure of `RecipeSimulationResult`/`ResolvedNeed` than a small, additive change, stop and report that instead of expanding scope.
- Follow `docs/CODING_GUIDELINES.md`.

## Dependencies

None.

## Definition of Done

- [ ] `RECIPE_NOT_ALLOWED` and `INSUFFICIENT_BUDGET` added to `craft.BlockedReason` and set at the identified locations.
- [ ] New tests for both reasons pass; full existing suite still passes.
- [ ] Any scope found to be larger than expected is reported, not silently expanded.
- [ ] `agent/CLAUDE_RESULT.md` filled in.

## Result

`craft.BlockedReason` gained `RECIPE_NOT_ALLOWED` and `INSUFFICIENT_BUDGET`.

`CraftingResolver.tryCraft(...)` now distinguishes the two `firstRecipeFor(...) == null`
causes via a new private helper `hasOnlyDisallowedRecipes(itemId, ctx)`: when
`ctx.recipesByOutput` has a non-empty candidate list for the item but every candidate's
`recipeId` is absent from `ctx.allowedRecipeIds`, `tryCraft` now returns a blocked
`ResolvedNeed` with `BlockedReason.RECIPE_NOT_ALLOWED` (qtyBlocked = qtyRequested) instead
of `null`. This reuses the existing `craftEval.need.getBlockedReason() != NONE` propagation
path in `resolveNeed(...)`'s fallback branch (already used for `CYCLE_DETECTED`/
`DAILY_LIMIT`), so no changes were needed to that branch itself. The true "no recipe exists
at all" case (`list == null || list.isEmpty()`) is unchanged and still falls through to
`NO_RECIPE` via the pre-existing fallback logic. `firstRecipeFor(...)`'s own selection logic
(same-discipline-then-cheapest, `STORY-DOM-002`) was not touched.

`RecipeSimulationResult` gained a `blockedReason` field (default `NONE`) with a getter/
setter. `RecipeSimulator.simulatePhase(...)` now sets
`result.setBlockedReason(BlockedReason.INSUFFICIENT_BUDGET)` at the exact existing
`ctx.settings.maxBuyCopper > 0 && nextBuyTotal > ctx.settings.maxBuyCopper` break site
identified in the story, immediately before the `break`. This fires whether the budget cap
is hit on the very first attempted craft (`craftCount == 0`) or after some crafts already
succeeded (budget-limited further crafts) — in both cases the budget was the specific,
last-detected reason the simulation stopped early. No change was made to which/how many
crafts are attempted or to `RecipeSimulationResult`/`ResolvedNeed`'s existing fields beyond
this one additive field — the smaller-restructure guardrail in the story's Constraints was
not triggered.

## Files Materially Changed

- `src/main/java/craft/BlockedReason.java` — added `RECIPE_NOT_ALLOWED`, `INSUFFICIENT_BUDGET`.
- `src/main/java/craft/CraftingResolver.java` — `tryCraft(...)` now returns a blocked
  `RECIPE_NOT_ALLOWED` `ResolvedNeed` instead of `null` when every candidate recipe for an
  item is filtered out by `allowedRecipeIds`; new private helper `hasOnlyDisallowedRecipes(...)`.
- `src/main/java/craft/RecipeSimulationResult.java` — new `blockedReason` field + getter/setter.
- `src/main/java/craft/RecipeSimulator.java` — sets `INSUFFICIENT_BUDGET` on the result at the
  existing budget-cap `break` site in `simulatePhase(...)`.
- `src/test/java/craft/CraftingResolverRecipeNotAllowedTest.java` (new).
- `src/test/java/craft/CraftingResolverInsufficientBudgetTest.java` (new).

## Tests Added

- `CraftingResolverRecipeNotAllowedTest.shouldMarkRecipeNotAllowedWhenOnlyCandidateIsFilteredOut`
  — an item with exactly one producing recipe, whose id is excluded from
  `ctx.allowedRecipeIds`; resolving it (with direct-buy disallowed at that node, so the only
  possible path is the filtered recipe) asserts `BlockedReason.RECIPE_NOT_ALLOWED`.
- `CraftingResolverInsufficientBudgetTest.shouldMarkInsufficientBudgetWhenBudgetBlocksEvenFirstCraft`
  — a recipe needing one ingredient that costs 100 copper to buy, simulated with
  `maxBuyCopper = 50`; asserts `craftCount == 0` and `BlockedReason.INSUFFICIENT_BUDGET` on
  the `RecipeSimulationResult`.

## Tests Run

`./mvnw test` — 14 tests run (12 pre-existing + 2 new), 0 failures, 0 errors, `BUILD SUCCESS`.

## Definition-of-Done Status

- [x] `RECIPE_NOT_ALLOWED` and `INSUFFICIENT_BUDGET` added to `craft.BlockedReason` and set at
      the identified locations.
- [x] New tests for both reasons pass; full existing suite still passes.
- [x] No scope expansion needed — both fixes were small and additive as anticipated.
- [x] `agent/CLAUDE_RESULT.md` filled in.

## Discovered Issues

None. One existing-behavior note (not a new problem, pre-existing and out of scope): in
`resolveNeed(...)`'s fallback branch, `!ctx.settings.allowBuying` is checked before the
`craftEval` reason, so `BUYING_DISABLED` still takes priority over `RECIPE_NOT_ALLOWED` (and
over `CYCLE_DETECTED`/`DAILY_LIMIT`) when buying is globally disabled — this priority
ordering predates this story and was not changed.

## Follow-Up Recommendations

None required by this story. `docs/DOMAIN_SPEC.md` §42's full blocked-reason set
(`NO_RECIPE`, `BUYING_DISABLED`, `DAILY_LIMIT`, `CYCLE_DETECTED`, `PRICE_UNAVAILABLE`,
`RECIPE_NOT_ALLOWED`, `INSUFFICIENT_BUDGET`) is now fully represented in
`craft.BlockedReason` and set at identified points.

## PROJECT_STATE.md Update Needed?

Yes — done alongside this story (see that file's "Completed Major Milestones", "Test
Status", "Next Candidate Stories", and "Last Completed Story" sections).

## ROADMAP.md Update Needed?

No — Phase 1's "Add the missing `BlockedReason` values" item is now complete, but
`docs/ROADMAP.md`'s own exit-criteria tracking is for Phase 0 (`§3.1`-`§3.6`); this story is
a Phase 1 item and its own roadmap entry, if checkbox-tracked there, should be updated during
PROJECT PLANNING MODE's next pass rather than here, per this file's instruction to avoid
duplicating story content into other tracking files.

## Blockers

None.
