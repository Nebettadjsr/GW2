## Story ID

STORY-DOM-001

## Title

Add a failing regression test for multiple-recipe selection priority

## Status

DONE

## Milestone

milestone-00

## Goal

Write an automated JUnit 5 test that proves `craft.CraftingResolver` does not currently implement `DOMAIN_SPEC.md` §30's recipe-selection priority when an item has more than one valid producing recipe. This story produces the test only — it does **not** fix the implementation.

## Authoritative Source Documents / Sections

- `docs/DOMAIN_SPEC.md` §30 "Multiple Recipes Producing the Same Item", and DQ-003 in §50 — the priority rule this test must encode.
- `docs/KNOWN_PROBLEMS.md` §3.2 — the confirmed conflict this story targets.
- `docs/TEST_STRATEGY.md` §6.8 (test cases expected), §16 (naming convention for regression tests), §24 (this is priority item 6, the highest-priority *unblocked* domain conflict — see Context).
- `src/main/java/craft/CraftingResolver.java`, method `firstRecipeFor(itemId, ctx)` — the code this test targets.
- `agent/stories/STORY-TEST-001-shared-domain-fixture.md` (if completed by the time this story runs) or `src/test/java/craft/CraftingResolverCraftVsBuyTest.java` (otherwise) — use whichever exists as the construction pattern for `RecipeRepository.Recipe`/`Ingredient`, `PlannerContext`, `PlanState`, `CraftingSettings`, `TpPriceRepository.TpQuote`.

## Context

`CraftingResolver.firstRecipeFor(itemId, ctx)` currently returns the first recipe in `ctx.recipesByOutput.get(itemId)` whose ID is in `ctx.allowedRecipeIds` — no discipline comparison, no cost comparison. `DOMAIN_SPEC.md` §30 requires, in order:

1. prefer a recipe matching the parent recipe's crafting discipline,
2. if several match, prefer the lowest effective cost among them,
3. otherwise, prefer the lowest effective cost among all remaining valid candidates.

Per `docs/KNOWN_PROBLEMS.md` §9, this is ranked below §3.3 (owned-material pool) in raw severity, but §3.3 is currently **blocked** (character inventory sync doesn't exist yet — see `agent/PROJECT_STATE.md`). This is the highest-priority domain conflict that is immediately actionable without a prerequisite feature, matching `TEST_STRATEGY.md` §24 priority position 6.

This mirrors the workflow already used for the craft-vs-buy fix (`KNOWN_PROBLEMS.md` §3.1): a failing test lands first, as its own reviewable step, before any implementation change is authorized (the implementation change itself is `STORY-DOM-002`, already queued and dependent on this story).

Queued to run **after** `STORY-TEST-001` (shared domain test fixture) so this test can reuse that fixture instead of hand-rolling its own recipe/item/price setup a second time. This is a recommendation, not a hard blocker — see Blockers.

## Acceptance Criteria

- A new test class exists under `src/test/java/craft/` covering at least these cases from `DOMAIN_SPEC.md` §30:
  1. An item has two valid recipes: one matches the parent recipe's discipline but has a higher effective cost, the other is a different discipline with a lower effective cost. Expected: the same-discipline recipe is chosen.
  2. An item has two valid, same-discipline recipes with different effective costs. Expected: the cheaper one is chosen.
  3. (If practical in the same class) An item has no same-discipline recipe among its candidates. Expected: the cheapest valid recipe overall is chosen.
- Each test asserts the `DOMAIN_SPEC.md`-correct outcome, not the current implementation's outcome.
- Running `./mvnw test` today shows these new tests **failing**, and the assertion output clearly shows the current code picked the first-in-list recipe rather than the spec-correct one.
- No production code is touched.

## Required Tests

- The new test(s) themselves are the deliverable — run via `./mvnw test`.
- Confirm the existing `craft.CraftingResolverCraftVsBuyTest` still passes unchanged (no regression from adding a new test file).
- No production-code test run is applicable, since no production code changes in this story.

## Constraints

- Test-only story: do not modify `CraftingResolver.java` or any other production source file.
- Do not implement the fix even if it looks trivial — that is `STORY-DOM-002`, so the failing test can be reviewed on its own first.
- Use the shared fixture from `STORY-TEST-001` if it has landed by the time this runs; otherwise hand-roll setup following `CraftingResolverCraftVsBuyTest`'s existing pattern — do not block on `STORY-TEST-001` if it hasn't happened yet.
- Follow `docs/CODING_GUIDELINES.md`'s version-agnostic guidance (single responsibility, descriptive behavior-based test names, no dead comments) — do not introduce Java 25 syntax modernization for its own sake.
- Do not refactor `CraftingResolverCraftVsBuyTest.java` or any other existing file while adding this one.
- No domain behavior changes.

## Definition of Done

- [x] New test file added under `src/test/java/craft/`.
- [x] `./mvnw test` runs it and it fails for the expected reason (wrong recipe selected, not a compile error or unrelated exception).
- [x] `craft.CraftingResolverCraftVsBuyTest` still passes.
- [x] No file outside `src/test/java/craft/` was modified.
- [x] `agent/CLAUDE_RESULT.md` filled in with what was added and the observed failure output.

## Result

Added `src/test/java/craft/CraftingResolverMultipleRecipeSelectionTest.java` with three tests,
one per `DOMAIN_SPEC.md` §30 rule, each constructing a final recipe plus two candidate recipes
for an intermediate item (varying discipline and effective cost) and asserting via
`CraftingResolver.resolveOneCraft(...)` which raw-material child (and effective cost) the
spec-correct recipe would produce:

- `shouldPreferSameDisciplineRecipeOverCheaperCrossDisciplineRecipe` — same-discipline
  candidate (cost 100) listed second, cheaper cross-discipline candidate (cost 50) listed
  first. Expected per rule 1: same-discipline (100) wins.
- `shouldPreferCheaperRecipeAmongSameDisciplineCandidates` — two same-discipline candidates,
  expensive one (100) listed first, cheap one (50) listed second. Expected per rule 2: cheap
  (50) wins.
- `shouldPreferCheapestRecipeWhenNoCandidateMatchesParentDiscipline` — two candidates, neither
  matching the parent's discipline, expensive one (100) listed first, cheap one (50) listed
  second. Expected per rule 3: cheap (50) wins.

All three fail today against `CraftingResolver.firstRecipeFor`, which simply returns the
first allowed recipe in list order:

```text
shouldPreferSameDisciplineRecipeOverCheaperCrossDisciplineRecipe: expected <1101> but was <1102>
shouldPreferCheaperRecipeAmongSameDisciplineCandidates: expected <2101> but was <2102>
shouldPreferCheapestRecipeWhenNoCandidateMatchesParentDiscipline: expected <3102> but was <3101>
```

In each case the "was" value is the raw material behind the first-in-list recipe, not the
spec-correct one — confirming the code picks list order, not discipline/cost.

`craft.CraftingResolverCraftVsBuyTest` still passes unchanged (verified via
`./mvnw test -Dtest=craft.CraftingResolverCraftVsBuyTest`, 1 test, 0 failures).

No production file was modified; only the new test file was added under `src/test/java/craft/`.

## Blockers

None hard. Recommended (not required) to run after `STORY-TEST-001` lands, to reuse its shared fixture instead of duplicating setup code.
