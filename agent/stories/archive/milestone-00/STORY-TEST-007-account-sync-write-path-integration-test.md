## Story ID

STORY-TEST-007

## Title

Layer 2 PostgreSQL integration test: `sync.AccountSync`'s `account_bank`/`account_materials`/`account_recipes` upsert + stale-row-deletion write paths

## Status

DONE

## Milestone

milestone-00

## Goal

Give `sync.AccountSync`'s three database-write flows (`syncAccountBank`, `syncAccountMaterials`, `syncAccountRecipes`) their first automated test, run against a real, disposable PostgreSQL schema, following the exact precedent `STORY-TEST-003` set for `CharacterSync` and `STORY-TEST-006` set for `TpSync`. This is the write-side counterpart to two already-tested, domain-critical read paths: `repo.InventoryRepository.loadOwnedInventory()`/`loadOwnedInventoryForCharacter(...)` (`STORY-TEST-002`/`STORY-DOM-011`) read directly from `account_bank` and `account_materials`, and `repo.RecipeRepository.loadRecipes(...)`'s unlock `UNION` (`STORY-TEST-004`) reads directly from `account_recipes`. Both repositories' Layer 2 tests seed those tables by inserting rows directly — the actual sync code that populates them from the GW2 API has never run against a real Postgres engine in an automated test. This advances `docs/ROADMAP.md` §4 Phase 0's still-unchecked exit criteria for real-PostgreSQL integration coverage of "critical repositories/sync flows used by the crafting domain."

## Authoritative Source Documents / Sections

- `docs/TEST_STRATEGY.md` §9 "Repository Integration Tests" and §31.2 "Layer 2 — PostgreSQL Integration Tests" (required properties: real PostgreSQL, isolated disposable database/schema, real schema/constraints, actual SQL behavior including upserts and stale-row deletion, reproducible from scratch, never the developer's normal database).
- `docs/ROADMAP.md` §4 "Phase 0 — Build & Test Foundation".
- `src/main/java/sync/AccountSync.java` (`syncAccountBank`, `syncAccountMaterials`, `syncAccountRecipes` — all three currently `public static`, each fetching from a live authenticated GW2 API endpoint via `Gw2ApiClient.getAuth(...)` and then upserting + deleting stale rows in one transaction).
- `src/main/java/repo/InventoryRepository.java` (reads `account_bank`/`account_materials` — the read side already covered by `STORY-TEST-002`/`STORY-DOM-011`).
- `src/main/java/repo/RecipeRepository.java` (reads `account_recipes` as part of its unlock `UNION` — the read side already covered by `STORY-TEST-004`).
- `src/test/java/sync/CharacterSyncTest.java` and `src/test/java/sync/TpSyncTest.java` (the established Layer 2 write-path pattern to reuse: disposable uniquely-named schema, `CREATE SCHEMA`/`DROP SCHEMA ... CASCADE`, a production method's visibility bumped from `private`/`public` to package-private `static` only if needed, no live GW2 API call, seed with already-constructed model rows).
- `src/main/java/util/DbBind.java` (`setIntOrNull`, `setStringOrNull` — the NULL-preservation helpers `AccountSync` uses for `account_bank`'s `item_id`/`count`/`binding`/`bound_to`/`charges`/`stats_id` columns).
- `src/main/java/model/BankSlot.java`, `src/main/java/model/MaterialStack.java` (the already-parsed model rows this story's test should construct directly, mirroring `TpSyncTest`'s use of already-constructed `util.TpPrice` values instead of live API JSON).

## Context

`AccountSync` has three independent public static methods, each following the same shape already proven out by `CharacterSync`/`TpSync`: fetch from a live authenticated GW2 API endpoint, then inside one transaction upsert rows (`ON CONFLICT ... DO UPDATE`) and delete stale rows (`DELETE ... WHERE fetched_at < ?`) using a single `runTs` timestamp shared by both. None of the three (`syncAccountBank` → `account_bank`, `syncAccountMaterials` → `account_materials`, `syncAccountRecipes` → `account_recipes`) has ever been exercised by an automated test — only manually via the live application. Per `docs/TEST_STRATEGY.md` §14/§31.2 (no live external dependencies in Layer 2 tests), the live-fetch half of each method (`Gw2ApiClient.getAuth(...)`) must not be called from this test; the write logic needs to be reachable independently of that network call, following the exact seam precedent `STORY-TEST-003`/`STORY-TEST-006` already established (extracting/exposing a package-private static write method callable with already-constructed model rows plus a `Connection` and a timestamp).

`account_bank` has a richer shape than the other two (`binding`/`bound_to`/`charges`/`stats_id`/`stats_attrs` in addition to `item_id`/`count`), and its `bound_to` value is exactly what `STORY-DOM-011`'s bound-material domain rule (`DOMAIN_SPEC.md` §11.1/DQ-007) depends on being written correctly — making its write path the single highest-value target of the three tables this story covers. `account_recipes` is a simple recipe-ID list, but is exactly the table `RecipeRepository`'s unlock `UNION` (`STORY-TEST-004`) reads to determine "recipe known" status for the currently-authenticated account — a core crafting-domain concern. `account_materials` mirrors `account_bank`'s upsert/stale-delete shape but with a narrower column set (no `bound_to`).

## Acceptance Criteria

- A new Layer 2 test (real, disposable PostgreSQL schema — no Testcontainers/Docker, following `CharacterSyncTest`'s/`TpSyncTest`'s established pattern) exercises `AccountSync`'s write logic for all three of `syncAccountBank`, `syncAccountMaterials`, and `syncAccountRecipes`, seeded with already-constructed model rows (`BankSlot`, `MaterialStack`, plain recipe IDs as applicable) — no live GW2 API call.
- For each of the three tables, the test proves against real Postgres: (a) an initial insert lands correctly with all written columns matching the input, (b) a second write with an overlapping key (`ON CONFLICT`) updates the existing row rather than duplicating it, and (c) a row whose `fetched_at` predates the current sync run's `runTs` is deleted by the stale-row cleanup while a row sharing the current run's `fetched_at` survives.
- The `account_bank` write path specifically proves `binding`/`bound_to` are written and preserved correctly (including a `NULL` binding case via `DbBind.setStringOrNull`), since this is the exact data `STORY-DOM-011`'s bound-material rule consumes downstream.
- Whatever minimal visibility change is needed to call each write path directly from a test (e.g. extracting a package-private static method taking `Connection` + already-constructed rows + a timestamp, mirroring `TpSync.upsertTpPrices`'s precedent) is the only production change made — no SQL text, upsert semantics, or the live-fetch path may change, and it is documented explicitly in the Result section.
- The test does not require or mutate the developer's normal/working local database, and does not depend on the live GW2 API or a real API key.
- `./mvnw test` passes, including this new test and the full existing suite.

## Required Tests

- The new `sync.AccountSync` Layer 2 write-path test(s) themselves (this story's entire deliverable) — covering all three of `syncAccountBank`/`syncAccountMaterials`/`syncAccountRecipes`.
- `./mvnw test` — full existing suite must continue to pass unchanged.

## Constraints

- Do not change `AccountSync`'s upsert/stale-delete SQL, column semantics, or the live-fetch (`Gw2ApiClient.getAuth(...)`) path — this is a test-seam-plus-test story only, unless the test surfaces an actual defect, in which case stop and report it rather than silently fixing it (per `CLAUDE.md`'s bug-fix workflow).
- Do not introduce Testcontainers/Docker or any other new test-infrastructure tooling; reuse the disposable-schema-on-the-existing-local-Postgres-server approach already established.
- Do not touch `repo.InventoryRepository`, `repo.RecipeRepository`, or their existing tests (`STORY-TEST-002`/`STORY-TEST-004`/`STORY-DOM-011`) — this story is scoped to the write side only.
- Do not attempt to cover `sync.ItemSync` or `sync.RecipeSync` — out of scope for this story.
- Follow `docs/CODING_GUIDELINES.md`.

## Dependencies

None.

## Definition of Done

- [x] New Layer 2 test(s) added under `src/test/java/sync/`, passing, proving insert / upsert-on-conflict / stale-row-deletion behavior against real PostgreSQL for all three of `account_bank`, `account_materials`, and `account_recipes`.
- [x] The `account_bank` case specifically covers `binding`/`bound_to` write-and-preserve behavior.
- [x] Test does not require or mutate the developer's normal database, and does not call the live GW2 API.
- [x] Any production visibility change needed to make the test possible is minimal and documented in the Result section.
- [x] `./mvnw test` shows the new test(s) passing alongside the full existing suite.
- [x] `agent/CLAUDE_RESULT.md` filled in.

## Result

Added `sync.AccountSyncTest` (`src/test/java/sync/AccountSyncTest.java`), the project's first
automated coverage of `sync.AccountSync`'s database-write logic. Three tests, run against a real,
disposable PostgreSQL schema (`test_accountsync_<random>`, created/dropped by the test itself,
following `CharacterSyncTest`'s/`TpSyncTest`'s pattern — no Testcontainers/Docker), seed minimal
`account_bank`/`account_materials`/`account_recipes` tables directly via JDBC (matching the real
schema in `src/PostgreSQL Query to create DB`) and exercise each write path with constructed
`model.BankSlot`/`model.MaterialStack`/plain recipe-ID rows — no live GW2 API call.

Each of the three tests proves, against real Postgres: (a) an initial insert lands with all written
columns matching the input, (b) a second write with an overlapping key updates the existing row via
`ON CONFLICT` rather than duplicating it, and (c) a row whose `fetched_at` predates the current
run's `runTs` is deleted by the stale-row cleanup while a row sharing the current run's `fetched_at`
survives. The `account_bank` test additionally proves `binding`/`bound_to` are written and
preserved correctly, including transitioning a bound slot to `NULL` binding on a later run (via
`DbBind.setStringOrNull`) — the exact data `STORY-DOM-011`'s bound-material rule consumes
downstream — and that `stats_attrs` round-trips through the `::jsonb` cast unchanged.

Production change (the only one made): `AccountSync`'s three public methods each had their
inline upsert-then-delete-stale block extracted into a new package-private static method —
`upsertAccountBank(Connection, List<BankSlot>, Timestamp)`, `upsertAccountMaterials(Connection,
List<MaterialStack>, Timestamp)`, and `upsertAccountRecipes(Connection, List<Integer>, Timestamp)`
— each containing the unchanged upsert SQL, unchanged `ON CONFLICT` semantics, and unchanged
`DELETE ... WHERE fetched_at < ?` stale-row cleanup, callable independently of the live
`Gw2ApiClient.getAuth(...)` fetch. This mirrors `STORY-TEST-003`'s `CharacterSync`
`replaceCharacterCrafting`/`replaceCharacterItems` precedent (a single package-private method doing
both upsert and stale-delete) rather than `STORY-TEST-006`'s `TpSync.upsertTpPrices` (upsert only,
since `tp_prices` has no stale-delete step) — `account_bank`/`account_materials`/`account_recipes`
all have a stale-delete step this story's acceptance criteria require testing. No SQL text, upsert
semantics, or the live-fetch path changed; no defect was found.

Tests added (`sync.AccountSyncTest`, 3 tests, Layer 2 PostgreSQL integration):
- `upsertAccountBank_insertsUpdatesOnConflictAndDeletesStaleRows`
- `upsertAccountMaterials_insertsUpdatesOnConflictAndDeletesStaleRows`
- `upsertAccountRecipes_insertsUpdatesOnConflictAndDeletesStaleRows`

Tests run:
- `./mvnw test -Dtest=AccountSyncTest`: 3 run, 0 failures, 0 errors, `BUILD SUCCESS`.
- `./mvnw test` (full suite): 45 tests run, 0 failures, 0 errors, `BUILD SUCCESS` (previous 42 tests
  unchanged, plus the 3 new tests).

No remaining uncertainty. `docs/ROADMAP.md`'s Phase 0 persistence-coverage exit criteria are left
for a future planning run to reconsider holistically alongside `STORY-TEST-008`
(`sync.RecipeSync`), per established precedent.

## Blockers

None.
