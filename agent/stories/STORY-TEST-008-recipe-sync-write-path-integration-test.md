## Story ID

STORY-TEST-008

## Title

Layer 2 PostgreSQL integration test: `sync.RecipeSync`'s `recipes`/`recipe_ingredients` upsert write path

## Status

DONE

## Milestone

milestone-00

## Goal

Give `sync.RecipeSync`'s database-write logic its first automated test, run against a real, disposable PostgreSQL schema, following the exact precedent `STORY-TEST-003`/`STORY-TEST-006`/`STORY-TEST-007` established for `CharacterSync`/`TpSync`/`AccountSync`. This is the write-side counterpart to `STORY-TEST-004`'s already-tested read side (`repo.RecipeRepository`, whose `Recipe`/`Ingredient` types are reused directly as the crafting domain's working data model per `docs/KNOWN_PROBLEMS.md` §4.1) — `RecipeRepositoryTest` seeds `recipes`/`recipe_ingredients` by inserting rows directly, but the actual sync code that populates those two tables from the GW2 API has never run against a real Postgres engine in an automated test. This advances `docs/ROADMAP.md` §4 Phase 0's still-unchecked exit criteria for real-PostgreSQL integration coverage of "critical repositories/sync flows used by the crafting domain."

## Authoritative Source Documents / Sections

- `docs/TEST_STRATEGY.md` §9 "Repository Integration Tests" and §31.2 "Layer 2 — PostgreSQL Integration Tests" (required properties: real PostgreSQL, isolated disposable database/schema, real schema/constraints, actual SQL behavior including upserts, reproducible from scratch, never the developer's normal database).
- `docs/ROADMAP.md` §4 "Phase 0 — Build & Test Foundation".
- `src/main/java/sync/RecipeSync.java` (`syncAllRecipesGlobalSafe()`'s recipe-upsert block and `syncRecipeIngredients(...)` — the two upsert SQL statements under test: `recipes` via `ON CONFLICT (recipe_id) DO UPDATE`, `recipe_ingredients` via `ON CONFLICT (recipe_id, item_id) DO UPDATE`).
- `src/main/java/repo/RecipeRepository.java` and `src/test/java/repo/RecipeRepositoryTest.java` (the read side already covered by `STORY-TEST-004` — reuse its `recipes`/`recipe_ingredients` DDL/schema setup where practical).
- `src/main/java/parser/RecipeParser.java` (`RecipeParser.RecipeRow`/`RecipeParser.IngredientRow` — the already-parsed model rows this story's test should construct directly, mirroring `TpSyncTest`'s use of already-constructed `util.TpPrice` values instead of live API JSON).
- `src/test/java/sync/TpSyncTest.java` / `src/test/java/sync/AccountSyncTest.java` (if `STORY-TEST-007` has landed by the time this story executes — the established Layer 2 write-path pattern to reuse: disposable uniquely-named schema, `CREATE SCHEMA`/`DROP SCHEMA ... CASCADE`, a production method's visibility bumped to package-private `static` only if needed, no live GW2 API call).

## Context

`RecipeSync.syncAllRecipesGlobalSafe()` fetches the full global recipe ID list and recipe details from the live GW2 API, then upserts each batch into `recipes` (`ON CONFLICT (recipe_id) DO UPDATE`, including a `disciplines`/`flags` text array and a `guild_ingredients` jsonb column) inside a per-batch transaction, before calling `ItemSync.syncItemsByIds(...)` and finally `syncRecipeIngredients(...)`, which upserts `recipe_ingredients` (`ON CONFLICT (recipe_id, item_id) DO UPDATE`) using a periodic `DB_FLUSH_BATCH`-sized commit inside its own batch loop. Unlike `CharacterSync`/`TpSync`/`AccountSync`, neither upsert here is followed by a stale-row `DELETE` — a recipe or ingredient row that no longer appears upstream simply keeps its last-synced values, which is an existing, unchanged behavior this story does not need to characterize or fix. Neither upsert has ever run against a real Postgres engine in an automated test.

Per `docs/TEST_STRATEGY.md` §14/§31.2 (no live external dependencies in Layer 2 tests), this story must not call the live GW2 API and must not call `ItemSync.syncItemsByIds(...)` (a separate sync flow, out of scope here — see `STORY-TEST-007`'s Constraints for the same "do not attempt to cover `ItemSync`" boundary). That means the two upsert blocks need to be reachable independently of both the network fetch and the `ItemSync` call, following the exact seam precedent `STORY-TEST-003`/`STORY-TEST-006`/`STORY-TEST-007` already established (extracting/exposing package-private static write methods callable with already-constructed model rows).

## Acceptance Criteria

- A new Layer 2 test (real, disposable PostgreSQL schema — no Testcontainers/Docker, following the established `CharacterSyncTest`/`TpSyncTest` pattern) exercises `RecipeSync`'s write logic for both `recipes` and `recipe_ingredients`, seeded with already-constructed `RecipeParser.RecipeRow`/`RecipeParser.IngredientRow` values (or equivalent already-parsed rows) — no live GW2 API call, and no call into `ItemSync`.
- The test proves, against real Postgres: (a) an initial insert into `recipes` lands correctly, including the `disciplines`/`flags` array columns and the `guild_ingredients` jsonb column, (b) a second upsert for the same `recipe_id` updates the existing row in place (verified via a changed field, e.g. `min_rating` or `fetched_at`) rather than inserting a duplicate, (c) an initial insert into `recipe_ingredients` lands correctly for a given `recipe_id`, and (d) a second upsert for the same `(recipe_id, item_id)` pair updates the row's `count` in place rather than duplicating it.
- Whatever minimal visibility change is needed to call each write path directly from a test (e.g. extracting package-private static methods taking `Connection` + already-constructed rows, mirroring `TpSync.upsertTpPrices`'s precedent) is the only production change made — no SQL text or upsert semantics may change, and it is documented explicitly in the Result section.
- The test does not require or mutate the developer's normal/working local database, and does not depend on the live GW2 API or a real API key.
- `./mvnw test` passes, including this new test and the full existing suite.

## Required Tests

- The new `sync.RecipeSync` Layer 2 write-path test(s) themselves (this story's entire deliverable) — covering both `recipes` and `recipe_ingredients`.
- `./mvnw test` — full existing suite must continue to pass unchanged.

## Constraints

- Do not change `RecipeSync`'s upsert SQL, column semantics, or the live-fetch (`Gw2ApiClient.getPublicArray(...)`) path — this is a test-seam-plus-test story only, unless the test surfaces an actual defect, in which case stop and report it rather than silently fixing it (per `CLAUDE.md`'s bug-fix workflow).
- Do not introduce Testcontainers/Docker or any other new test-infrastructure tooling; reuse the disposable-schema-on-the-existing-local-Postgres-server approach already established.
- Do not touch `repo.RecipeRepository` or `STORY-TEST-004`'s existing test — this story is scoped to the write side only.
- Do not attempt to cover `sync.ItemSync` (including the `ItemSync.syncItemsByIds(...)` call `syncAllRecipesGlobalSafe()` makes) — out of scope for this story.
- Do not add or characterize stale-row deletion for `recipes`/`recipe_ingredients` — none exists today, and adding one would be a behavior change beyond this story's test-only scope.
- Follow `docs/CODING_GUIDELINES.md`.

## Dependencies

None. (Independent of `STORY-TEST-007` — both may run in either order.)

## Definition of Done

- [x] New Layer 2 test(s) added under `src/test/java/sync/`, passing, proving insert / upsert-on-conflict behavior against real PostgreSQL for both `recipes` and `recipe_ingredients`.
- [x] Test does not require or mutate the developer's normal database, and does not call the live GW2 API or `ItemSync`.
- [x] Any production visibility change needed to make the test possible is minimal and documented in the Result section.
- [x] `./mvnw test` shows the new test(s) passing alongside the full existing suite.
- [x] `agent/runtime/artifacts/CLAUDE_RESULT.md` filled in.

## Result

Added `src/test/java/sync/RecipeSyncTest.java`, a Layer 2 PostgreSQL integration test (disposable
uniquely-named schema on the local Postgres server, `CREATE SCHEMA`/`DROP SCHEMA ... CASCADE`,
following the `TpSyncTest`/`AccountSyncTest` pattern). Four tests:

- `upsertRecipes_initialInsert_writesAllColumnsIncludingArraysAndJsonb` — proves an initial insert
  into `recipes` lands `type`/`output_item_id`/`output_item_count`/`time_to_craft_ms`/`min_rating`/
  `chat_link` correctly, and that the `disciplines`/`flags` text-array columns and the
  `guild_ingredients` jsonb column round-trip correctly (verified via Postgres array/jsonb equality
  in the `SELECT`, mirroring `AccountSyncTest`'s jsonb-match-column style).
- `upsertRecipes_reRunForSameRecipeId_updatesExistingRowInPlaceRatherThanInserting` — proves a
  second upsert for the same `recipe_id` updates the row in place (`COUNT(*) = 1`), verified via a
  changed `min_rating` and `fetched_at`.
- `upsertRecipeIngredients_initialInsert_writesRowForGivenRecipeId` — proves an initial insert into
  `recipe_ingredients` lands the `count` for a given `(recipe_id, item_id)`.
- `upsertRecipeIngredients_reRunForSameRecipeAndItemId_updatesCountInPlaceRatherThanDuplicating` —
  proves a second upsert for the same `(recipe_id, item_id)` updates `count` in place
  (`COUNT(*) = 1`) rather than duplicating the row.

Production visibility change (the only production change made; no SQL text or upsert semantics
changed) in `src/main/java/sync/RecipeSync.java`:

- Extracted `static void upsertRecipes(Connection con, List<RecipeParser.RecipeRow> rows,
  Timestamp runTs)` from the recipe-upsert block inside `syncAllRecipesGlobalSafe()`'s batch loop.
  The loop still computes `runTs` via `SELECT now()` and still owns
  `setAutoCommit(false)`/`commit()`/`rollback()` around the call, unchanged from before.
- Extracted `static void upsertRecipeIngredients(Connection con, List<RecipeParser.IngredientRow>
  ingRows)` from the ingredient-upsert block inside `syncRecipeIngredients(...)`'s batch loop,
  including its periodic `DB_FLUSH_BATCH`-sized `executeBatch()`/`commit()` flush (unreachable in
  this story's small seeded row counts). As with `upsertRecipes`, the surrounding
  `setAutoCommit(false)`/`commit()`/`rollback()` control stays in the caller's loop, matching the
  original code and the `TpSync.upsertTpPrices`/`AccountSync.upsertAccountBank` precedent of leaving
  transaction control to the caller. `syncRecipeIngredients`'s signature was also trimmed from
  `(List<Integer> ids, String ingredientSql)` to `(List<Integer> ids)` since the SQL text moved
  inside the new extracted method and the parameter became dead.

Neither extraction changed the `recipes`/`recipe_ingredients` `INSERT`/`ON CONFLICT` SQL text or
upsert semantics — only where the `PreparedStatement` is prepared (now inside each extracted method,
once per call, rather than once per `Connection` and reused across id-batches — a performance detail
only, matching the same pattern already established by `TpSync.upsertTpPrices`).

Tests run: `./mvnw test` — 49 tests, 0 failures, 0 errors (includes the 4 new `RecipeSyncTest` cases
and the full existing suite, including `repo.RecipeRepositoryTest`, unchanged).

Remaining uncertainty: none. Stale-row deletion, `ItemSync` coverage, and the live-fetch path
remain explicitly out of scope per the story's Constraints, and were not touched.

## Blockers

None.
