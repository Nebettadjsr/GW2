## Story ID

STORY-DOM-009

## Title

Characterization test: direct-price heuristic skip under useOwnMats=false/allowBuying=false

## Status

DONE

## Milestone

milestone-00

## Goal

Add a targeted craft-domain test for the risk described in `docs/KNOWN_PROBLEMS.md` §7.4: when `useOwnMats=false` and `allowBuying=false`, `CraftingPlanner.evaluateOneRecipeNew` may skip simulating a recipe entirely (`shouldSimulateRecipe(...)`, gated by `maySkipCheap`) based only on **direct, non-recursive** ingredient buy/sell prices, without considering that an ingredient might itself be cheaper to craft recursively. `docs/KNOWN_PROBLEMS.md` §7.4 explicitly recommends this test "once domain tests exist" — `CraftTestFixtures` (`STORY-TEST-001`) now exists, so this is no longer blocked. This is a characterization test of current behavior, not a fix — no production-code change is authorized by this story.

## Authoritative Source Documents / Sections

- `docs/KNOWN_PROBLEMS.md` §7.4 (the specific risk and its own recommendation).
- `docs/TEST_STRATEGY.md` §4.2 (characterization tests), §6.4 (craft-vs-buy test area), §16 (named regression cases), §24 (priority order).
- `src/main/java/craft/CraftingPlanner.java` lines ~42-161 (`evaluateOneRecipeNew`, `maySkipCheap`, `shouldSimulateRecipe`).
- `src/test/java/craft/CraftTestFixtures.java` (existing shared fixture helpers to reuse, not duplicate).

## Context

`shouldSimulateRecipe(recipe, ctx)` estimates a recipe's worth using only `CraftingResolver.resolveDirectBuyUnit`/`resolveDirectSellUnit` for each direct ingredient — i.e. "buy this ingredient outright" or "sell what you already have," never "craft this ingredient recursively and use that cost instead." When `useOwnMats=false && allowBuying=false` (`maySkipCheap`), a recipe whose direct-ingredient economics look unprofitable is skipped outright (treated as `craftableCount = 0` via an empty `RecipeSimulationResult`), even if one of its ingredients has its own recipe that would make the whole chain profitable. This story only needs to construct that scenario with `CraftTestFixtures` and record what `CraftingPlanner.evaluateAll(...)` actually returns today — it does not require deciding whether this is acceptable or must be fixed.

## Acceptance Criteria

- A new test in `src/test/java/craft/`, built with `CraftTestFixtures`, constructs a recipe whose **direct** ingredient prices make `shouldSimulateRecipe(...)` return `false` under `useOwnMats=false, allowBuying=false` settings, where at least one ingredient itself has a recipe that would be cheaper to craft recursively than to buy directly.
- The test calls `CraftingPlanner.evaluateAll(...)` (the existing public entry point — do not call the private `evaluateOneRecipeNew`/`shouldSimulateRecipe` directly) and asserts the current, actual outcome for that recipe's `CraftResult` (e.g. `craftCount`/`totalProfitCopper`), whatever that outcome is — this is a characterization test, not an assertion of correctness.
- The test's name and a short comment make clear it characterizes the §7.4 heuristic-skip risk under this specific settings combination, not a statement that the result is correct (`docs/TEST_STRATEGY.md` §4.2/§16 naming style, e.g. `shouldCurrentlySkipSimulationBasedOnDirectPricesEvenWhenRecursiveCraftingWouldBeCheaper`).
- `./mvnw test` passes, including this new test and the full existing suite.

## Required Tests

- The new characterization test itself (this story's entire deliverable).
- `./mvnw test` — full existing suite must continue to pass unchanged.

## Constraints

- Do not change `CraftingPlanner`, `CraftingResolver`, or any other production code — test-only change.
- Do not attempt to fix or redesign the heuristic (e.g. making `shouldSimulateRecipe` recursive) — out of scope for this story.
- Follow `docs/CODING_GUIDELINES.md`.

## Dependencies

None.

## Definition of Done

- [x] New characterization test added under `src/test/java/craft/`, passing, and clearly named/commented as characterizing the §7.4 heuristic-skip risk.
- [x] No production code changed.
- [x] `./mvnw test` shows the new test passing alongside the full existing suite.
- [x] `agent/runtime/artifacts/CLAUDE_RESULT.md` filled in.

## Result

Added `craft.CraftingPlannerHeuristicSkipCharacterizationTest`
(`shouldCurrentlySkipSimulationBasedOnDirectPricesEvenWhenRecursiveCraftingWouldBeCheaper`),
built entirely with `CraftTestFixtures`. It constructs a three-recipe chain: an Output
recipe needing 1x Intermediate; Intermediate has its own recipe crafting it from 1x Base;
Base has a recipe with zero ingredients (a fixture-only stand-in for "recursively free to
obtain," needed so the whole chain resolves without any buying/inventory use under
`useOwnMats=false`/`allowBuying=false`). Intermediate's *direct* TP buy price (1000) is set
higher than Output's direct sell price (500), so `shouldSimulateRecipe(...)`'s
direct-price-only estimate (`minCost=1000 >= revenue=500`) returns `false` and
`maySkipCheap` skips simulating the Output recipe entirely — even though Intermediate's own
recipe would let it be crafted from Base at effectively zero direct cost, which is exactly
the §7.4 gap.

Calling `CraftingPlanner.evaluateAll(...)` and reading the Output recipe's `CraftResult`
confirms the current (unfixed) outcome: `craftableCount=0`, `totalProfitCopper=0`,
`missingToBuy={}`, while `profitCopper` (the per-craft figure computed from direct TP prices
alone, independent of the skip) is `500` — i.e. the heuristic reports a recipe that "would be
worth 500 per craft" as having zero actual craftable count, silently discarding the
recursive path. No production code was touched; this is a pure characterization test, not an
assertion of correctness.

`./mvnw test` run: 12 tests, 0 failures, 0 errors, `BUILD SUCCESS` (11 pre-existing + this 1
new test).

## Blockers

None.
