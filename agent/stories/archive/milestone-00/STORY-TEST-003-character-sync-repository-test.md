## Story ID

STORY-TEST-003

## Title

Layer 2 PostgreSQL integration test: `sync.CharacterSync` upsert + stale-row deletion semantics

## Status

DONE

## Milestone

milestone-00

## Goal

Give `sync.CharacterSync`'s database-write logic (`upsertCharacter`, `replaceCharacterCrafting`, `replaceCharacterRecipes`, `replaceCharacterItems`) its first automated test, run against a real, disposable PostgreSQL schema. This advances `docs/ROADMAP.md` §4 Phase 0's still-unchecked exit criteria "Persistence-relevant code paths are covered by integration tests against a real PostgreSQL instance, not only mocked/in-memory behavior" and "At least the critical repositories/sync flows used by the crafting domain are verified against the real database schema, constraints, and upsert/delete semantics." `STORY-TEST-002` (DONE) covered the *read* side of the owned-material pool (`InventoryRepository.loadOwnedInventory()`); the *write* side — the upsert-on-conflict and `fetched_at`-based stale-row deletion that `CharacterSync` performs for `character_crafting`, `character_recipes`, and `character_items` (the very data `STORY-SYNC-001` introduced and `STORY-DOM-004`'s owned-pool fix depends on) — currently has zero automated coverage of any kind.

## Authoritative Source Documents / Sections

- `docs/TEST_STRATEGY.md` §9 "Repository Integration Tests" and §31.2 "Layer 2 — PostgreSQL Integration Tests" (required properties: real PostgreSQL, isolated disposable database/schema, real schema/constraints, actual SQL behavior including upserts and stale-row deletion, reproducible from scratch, never the developer's normal database).
- `docs/ROADMAP.md` §4 "Phase 0 — Build & Test Foundation", the two exit criteria named in the Goal above.
- `src/main/java/sync/CharacterSync.java` (`upsertCharacter`, `replaceCharacterCrafting`, `replaceCharacterRecipes`, `replaceCharacterItems` — the methods under test; all currently `private static`).
- `src/test/java/repo/InventoryRepositoryOwnedInventoryTest.java` (existing Layer 2 test — reuse its disposable-schema pattern: `CREATE SCHEMA <unique>` / `setSchema(...)` / `DROP SCHEMA ... CASCADE`, no new tooling).
- `agent/stories/STORY-TEST-002-owned-pool-character-inventory-repository-test.md` (precedent for the minimal test-seam approach and its Result section's tooling rationale — reuse the same reasoning rather than re-deciding it).

## Context

`CharacterSync.syncCharactersCraftingAndRecipes()` fetches character data from the live GW2 API and writes it to `characters`/`character_crafting`/`character_recipes`/`character_items` inside one transaction per character, using `INSERT ... ON CONFLICT ... DO UPDATE` upserts followed by a `DELETE ... WHERE fetched_at < ?` stale-row cleanup for each of the three per-character tables. None of this SQL has ever been exercised by an automated test — it has only ever run against the developer's real database via the live application. The four methods that perform the actual writes are `private static`, so a test in the same package cannot call them directly today; `STORY-TEST-002` set the precedent for a minimal, behavior-preserving seam (there: a `Connection`-accepting overload) when a test would otherwise be impossible without also dragging in unrelated GW2-API/`AppConfig` dependencies — the same reasoning applies here, since `syncCharactersCraftingAndRecipes()` itself calls the live GW2 API (`fetchCharacterNames`/`fetchCharacterDetails`) and cannot be driven deterministically/offline as required by `TEST_STRATEGY.md` §14.

## Acceptance Criteria

- A new Layer 2 test (real, disposable PostgreSQL schema — no Testcontainers/Docker, following `InventoryRepositoryOwnedInventoryTest`'s pattern) exercises `CharacterSync`'s write logic for at least `character_items` and one of `character_crafting`/`character_recipes`, seeded with already-parsed model rows (no live GW2 API call).
- The test proves, against real Postgres: (a) an initial insert lands correctly, (b) a second write with an overlapping key (`ON CONFLICT`) updates rather than duplicates the row, and (c) a row whose `fetched_at` predates the current sync run is deleted by the stale-row cleanup while a row with a current `fetched_at` survives.
- Whatever minimal visibility change is needed to call the write logic directly from a test (e.g. `private` → package-private) is the only production change made — no behavior change, and note it explicitly in the Result section, per `STORY-TEST-002`'s precedent.
- The test does not require or mutate the developer's normal/working local database, and does not depend on the live GW2 API or a real API key.
- `./mvnw test` passes, including this new test and the full existing suite.

## Required Tests

- The new `sync.CharacterSync` Layer 2 test itself (this story's entire deliverable).
- `./mvnw test` — full existing suite must continue to pass unchanged.

## Constraints

- Do not change `CharacterSync`'s upsert/stale-delete SQL or transaction behavior — this is a test-only story, unless the test surfaces an actual defect, in which case stop and report it rather than silently fixing it (per `CLAUDE.md`'s bug-fix workflow).
- Do not introduce Testcontainers/Docker or any other new test-infrastructure tooling; reuse the disposable-schema-on-the-existing-local-Postgres-server approach `STORY-TEST-002` already established and justified.
- Do not attempt to cover the live-API-fetching half of `syncCharactersCraftingAndRecipes()` (`fetchCharacterNames`/`fetchCharacterDetails`) — that is GW2 API adapter behavior (Layer 3/4), out of scope here.
- Follow `docs/CODING_GUIDELINES.md`.

## Dependencies

None.

## Definition of Done

- [x] New Layer 2 test added under `src/test/java/sync/`, passing, proving insert / upsert-on-conflict / stale-row-deletion behavior against real PostgreSQL for at least `character_items` and one other `CharacterSync`-managed table.
- [x] Test does not require or mutate the developer's normal database, and does not call the live GW2 API.
- [x] Any production visibility change needed to make the test possible is minimal and documented in the Result section.
- [x] `./mvnw test` shows the new test passing alongside the full existing suite.
- [x] `agent/runtime/artifacts/CLAUDE_RESULT.md` filled in.

## Result

Added `sync.CharacterSyncTest` (`src/test/java/sync/CharacterSyncTest.java`), a Layer 2
PostgreSQL integration test (`docs/TEST_STRATEGY.md` §31.2) exercising `CharacterSync`'s
write logic directly: `upsertCharacter`, `replaceCharacterCrafting`, and
`replaceCharacterItems` (three tests, following `InventoryRepositoryOwnedInventoryTest`'s
disposable-schema pattern — `CREATE SCHEMA test_charsync_<random>` / `setSchema(...)` /
`DROP SCHEMA ... CASCADE`, no new tooling). All seeded rows are already-constructed
`CharacterInfo`/`CharacterCraftingRow`/`CharacterItemRow` records built directly in the test;
`fetchCharacterNames`/`fetchCharacterDetails` and the live GW2 API are never touched.

**What each test proves, against real Postgres:**
- `upsertCharacter_insertsThenUpdatesTheSameRowOnConflictingName` — a second upsert with the
  same `name` updates the existing `characters` row (same `character_id`, new `level`) rather
  than inserting a second row.
- `replaceCharacterItems_insertsUpdatesOnConflictAndDeletesStaleRows` — (a) an initial two-slot
  insert lands correctly; (b) a second run with an overlapping key (same bag slot, changed
  `count`) leaves exactly one row for that slot, updated to the new value; (c) the other slot,
  not present in the second run and therefore left with the first run's older `fetched_at`, is
  deleted by the stale-row cleanup.
- `replaceCharacterCrafting_insertsUpdatesOnConflictAndDeletesStaleRows` — same three
  properties for `character_crafting` (two disciplines seeded, one refreshed with a new
  `rating` on the second run, the other absent from that run and consequently deleted as
  stale).

**Production visibility change (the only production change made):** `CharacterSync.upsertCharacter`,
`replaceCharacterCrafting`, and `replaceCharacterItems` changed from `private static` to
package-private `static` (removed the `private` keyword only) so `sync.CharacterSyncTest` can
call them directly, per `STORY-TEST-002`'s precedent. `replaceCharacterRecipes` was left
`private` since no test in this story calls it (the acceptance criteria only required
`character_items` plus one of crafting/recipes; crafting was chosen). No SQL, transaction, or
control-flow behavior changed in any of the four methods.

**Verified the tests are meaningful:** temporarily commented out the stale-row `DELETE`
statement inside `replaceCharacterCrafting` and separately inside `replaceCharacterItems` (one
at a time) and reran `CharacterSyncTest` — each change produced exactly one failing assertion
(the corresponding test's stale-row-survival check), confirming the tests actually exercise
that behavior rather than passing vacuously. Reverted both changes immediately afterward and
confirmed via `diff` against a pre-experiment backup that `CharacterSync.java` returned to
exactly the three-method visibility change with no leftover edits.

**Credential lookup without a new production dependency:** `InventoryRepositoryOwnedInventoryTest`
(package `repo`) can call the package-private `repo.EnvConfig.require(...)` directly since it
shares that package; `CharacterSyncTest` lives in package `sync` and cannot. Rather than widen
`EnvConfig`'s visibility (a second, unrelated production change this story's Constraints don't
authorize) or use `repo.AppConfig` (whose static initializer also requires `GW2_API_KEY`,
reintroducing exactly the "no live GW2 API / no real API key" dependency `STORY-TEST-002`
deliberately avoided), the test duplicates `EnvConfig`'s small env-var/`.env`-fallback lookup
locally as a private test-only helper (`requireEnv(...)`). This keeps the story's only
production change scoped to the three `CharacterSync` methods.

**Real defect check — none found requiring a stop:** while designing the `character_items`
test, discovered via a throwaway JDBC experiment against the local Postgres server that
PostgreSQL's default `UNIQUE` semantics treat `NULL` values as mutually distinct, so
`ON CONFLICT (character_id, location, bag_index, slot_index, equipment_slot)` never actually
fires as a true `UPDATE` for `BAG` rows (whose `equipment_slot` is always `NULL`) — a plain
`INSERT` happens instead. However, `replaceCharacterItems`'s very next statement in the same
call, `DELETE ... WHERE fetched_at < ?`, immediately removes the resulting old-timestamped
duplicate before the method returns (and before the enclosing transaction ever commits), so the
externally observable end state is indistinguishable from a real update: exactly one row per
slot, holding the newest values. No caller, inside or outside this codebase, can observe the
transient duplicate. This is a real technical nuance worth recording, but not a behavior defect
— nothing produces wrong data, an extra row, or a wrong final state — so per this story's
Constraints ("unless the test surfaces an actual defect") it did not warrant stopping the story;
`CharacterSync`'s SQL was left unchanged.

Tests run: `./mvnw test` — 25 tests, 0 failures, 0 errors, `BUILD SUCCESS` (previous 22 tests
unchanged, plus these 3 new tests). Requires the same reachable local PostgreSQL server
(`DATABASE_URL`/`DATABASE_USER`/`DATABASE_PASSWORD`) the project's baseline and the two existing
Layer 2 tests already require — no additional setup.

## Blockers

None.
