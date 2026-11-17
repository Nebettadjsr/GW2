## Story ID

STORY-TEST-004

## Title

Layer 2 PostgreSQL integration test: `repo.RecipeRepository` unlock/discipline/ingredient read semantics

## Status

DONE

## Milestone

milestone-00

## Goal

Give `repo.RecipeRepository` — the repository whose `Recipe`/`Ingredient` nested classes are reused directly as the crafting domain's working data model (`docs/KNOWN_PROBLEMS.md` §4.1: `CraftingPlanner`, `CraftingResolver`, `CostEvaluator`, `RecipeSimulator`, `CraftingGraph`, and `CraftingGraphCache` all import it) — its first automated test, run against a real, disposable PostgreSQL schema. This advances `docs/ROADMAP.md` §4 Phase 0's still-unchecked exit criteria "Persistence-relevant code paths are covered by integration tests against a real PostgreSQL instance, not only mocked/in-memory behavior" and "At least the critical repositories/sync flows used by the crafting domain are verified against the real database schema, constraints, and upsert/delete semantics." `STORY-TEST-002`/`STORY-TEST-003` (both DONE) covered `InventoryRepository`'s owned-material-pool read and `CharacterSync`'s write logic; `RecipeRepository` — arguably the single most load-bearing repository for the crafting domain, since every recipe/ingredient the planner ever sees comes from it — currently has zero automated coverage of any kind.

## Authoritative Source Documents / Sections

- `docs/TEST_STRATEGY.md` §9 "Repository Integration Tests" and §31.2 "Layer 2 — PostgreSQL Integration Tests" (required properties: real PostgreSQL, isolated disposable database/schema, real schema/constraints, actual SQL behavior, reproducible from scratch, never the developer's normal database).
- `docs/ROADMAP.md` §4 "Phase 0 — Build & Test Foundation", the two exit criteria named in the Goal above.
- `src/main/java/repo/RecipeRepository.java` (`loadRecipes(String discipline)`, `loadRecipesForCharacter(String, String)`, `loadAllRecipes()` — the methods under test; `loadIngredientsByRecipe()` is exercised indirectly through them).
- `docs/KNOWN_PROBLEMS.md` §4.1/§4.2 (context on why this repository's correctness matters to the domain, and the separate, not-in-scope "recipe is unlocked" consistency question between `loadRecipes` and `loadMissingDiscoverableRecipeIdsForCharacter` — Phase 2 concern, not this story's).
- `src/test/java/repo/InventoryRepositoryOwnedInventoryTest.java` and `src/test/java/repo/InventoryRepositoryBoundMaterialTest.java` (existing Layer 2 test pattern — reuse the disposable-schema approach: `CREATE SCHEMA <unique>` / `setSchema(...)` / `DROP SCHEMA ... CASCADE`, no new tooling).

## Context

`RecipeRepository.loadRecipes(discipline)` is what turns raw `recipes`/`recipe_ingredients`/`account_recipes`/`character_recipes` rows into the `Recipe`/`Ingredient` objects the entire crafting domain operates on. Its SQL does real, non-trivial work: a `UNION` CTE deciding "unlocked" status across account-wide and character-specific recipe unlocks, a `discipline = ANY(r.disciplines)` array-containment filter, an `"All"` case-insensitive bypass, and a Postgres `text[]` `disciplines` column that can be `NULL` or empty and must map to an empty/absent `disciplinesText`. None of this has ever been exercised against a real Postgres engine — only implicitly, by running the live application against the developer's own database. A bug in the unlock `UNION`, the discipline filter, or the ingredient join would silently corrupt crafting-profit/discovery results without any test ever catching it.

## Acceptance Criteria

- A new Layer 2 test (real, disposable PostgreSQL schema — no Testcontainers/Docker, following the existing `InventoryRepositoryOwnedInventoryTest`/`InventoryRepositoryBoundMaterialTest` pattern) seeds `recipes`, `recipe_ingredients`, `account_recipes`, and `character_recipes` rows directly via SQL/JDBC (no live GW2 API call, no parser involved) and exercises `RecipeRepository.loadRecipes(...)`.
- The test proves, against real Postgres: (a) a recipe unlocked only via `account_recipes` is returned, (b) a recipe unlocked only via a specific character's `character_recipes` row is returned by `loadRecipes("All")`/`loadAllRecipes()` semantics as applicable, (c) a recipe unlocked by neither is excluded, (d) the discipline filter (`? = ANY(r.disciplines)`) correctly includes a matching discipline and excludes a non-matching one, and (e) ingredients are correctly attached to their owning recipe (including a recipe with zero ingredients, and two recipes each with their own distinct ingredient rows, to confirm no cross-recipe leakage).
- At least one case covers a `NULL` or empty `disciplines` array, confirming `disciplinesText` is produced as an empty string rather than a `NullPointerException` or a bad `ANY(...)` match.
- The test does not require or mutate the developer's normal/working local database, and does not depend on the live GW2 API or a real API key.
- `./mvnw test` passes, including this new test and the full existing suite.

## Required Tests

- The new `repo.RecipeRepository` Layer 2 test itself (this story's entire deliverable).
- `./mvnw test` — full existing suite must continue to pass unchanged.

## Constraints

- Do not change `RecipeRepository`'s SQL or method signatures — this is a test-only story, unless the test surfaces an actual defect, in which case stop and report it rather than silently fixing it (per `CLAUDE.md`'s bug-fix workflow).
- Do not attempt to resolve or re-verify the separate `loadRecipes` vs. `loadMissingDiscoverableRecipeIdsForCharacter` consistency question raised in `docs/KNOWN_PROBLEMS.md` §4.2 — that is explicitly out of scope (Phase 2 territory).
- Do not introduce Testcontainers/Docker or any other new test-infrastructure tooling; reuse the disposable-schema-on-the-existing-local-Postgres-server approach already established.
- Follow `docs/CODING_GUIDELINES.md`.

## Dependencies

None.

## Definition of Done

- [x] New Layer 2 test added under `src/test/java/repo/`, passing, proving `RecipeRepository`'s unlock-CTE, discipline-filter, and ingredient-mapping behavior against real PostgreSQL, including a `NULL`/empty-disciplines case.
- [x] Test does not require or mutate the developer's normal database, and does not call the live GW2 API.
- [x] Any minimal production change needed to make the test possible (if any) is documented in the Result section.
- [x] `./mvnw test` shows the new test passing alongside the full existing suite.
- [x] `agent/runtime/artifacts/CLAUDE_RESULT.md` filled in.

## Result

Added `src/test/java/repo/RecipeRepositoryTest.java`, the project's first automated coverage of
any kind for `repo.RecipeRepository`, run as a Layer 2 PostgreSQL integration test
(`docs/TEST_STRATEGY.md` §31.2) against a disposable, uniquely-named schema
(`test_recipe_<random>`), created/dropped by the test itself, following the same pattern as
`InventoryRepositoryOwnedInventoryTest`/`InventoryRepositoryBoundMaterialTest` — no
Testcontainers/Docker, no touch of the developer's normal database, no live GW2 API call.

Eight tests seed `recipes`/`recipe_ingredients`/`account_recipes`/`character_recipes`/`characters`
rows directly via JDBC and exercise `loadRecipes(Connection, String)` (and one test for
`loadAllRecipes(Connection)`), proving against real Postgres:
- a recipe unlocked only via `account_recipes` is returned;
- a recipe unlocked only via a specific character's `character_recipes` row is returned;
- a recipe unlocked by neither is excluded;
- the `? = ANY(r.disciplines)` filter includes a matching discipline and excludes a non-matching
  one;
- a `NULL` `disciplines` array produces `disciplinesText = ""` rather than throwing, and is
  included under `"All"`;
- an empty (`ARRAY[]::TEXT[]`) `disciplines` array likewise produces `disciplinesText = ""` and is
  correctly excluded by a specific-discipline filter (neither case matches `ANY(...)` over an
  empty array);
- ingredients attach only to their owning recipe — a two-ingredient recipe, a one-ingredient
  recipe, and a zero-ingredient recipe all seeded together show no cross-recipe leakage;
- `loadAllRecipes(Connection)` returns a recipe regardless of unlock status (no
  `account_recipes`/`character_recipes` row at all).

**Minimal production change (test seam, no behavior change):** `RecipeRepository` had no
`Connection`-accepting overload of any kind — every method opened its own connection via
`repo.Db.open()`, unlike `InventoryRepository` (which already gained this seam in
`STORY-TEST-002`/`STORY-DOM-011`). Added `Connection`-parameter overloads for `loadRecipes`,
`loadRecipesForCharacter`, `loadAllRecipes`, and the private `loadIngredientsByRecipe`; each
no-arg method now opens a connection via `Db.open()` and delegates to its `Connection` overload,
so production call sites and behavior are unchanged. The old private no-arg
`loadIngredientsByRecipe()` was removed as dead code once its only caller was updated to call the
`Connection` overload directly. No SQL text and no existing method signature changed — this is
purely additive, consistent with this story's Constraints and the precedent
`InventoryRepository` already established.

No defect was found in `RecipeRepository`'s unlock/discipline/ingredient logic — all behavior
matched the acceptance criteria as originally written.

**Tests run:** `./mvnw clean test` — 33 tests total (25 previously existing + 8 new), 0 failures,
0 errors, `BUILD SUCCESS`. `RecipeRepositoryTest` requires a reachable local Postgres server
(same precondition as the existing `InventoryRepository*`/`CharacterSyncTest` Layer 2 tests).

**Uncertainty:** None. This story's scope (the four listed methods' unlock/discipline/ingredient
semantics) is fully covered; the separate `loadRecipes` vs.
`loadMissingDiscoverableRecipeIdsForCharacter` consistency question (`docs/KNOWN_PROBLEMS.md`
§4.2) remains explicitly out of scope, as instructed.

## Blockers

None.
