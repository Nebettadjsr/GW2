## Story ID

STORY-TEST-006

## Title

Layer 2 PostgreSQL integration test: `sync.TpSync`'s `tp_prices` upsert write path (NULL-price preservation, upsert-on-conflict)

## Status

DONE

## Milestone

milestone-00

## Goal

Give `sync.TpSync`'s database-write logic its first automated test, run against a real, disposable PostgreSQL schema, mirroring what `STORY-TEST-003` did for `sync.CharacterSync`. This is the write-side counterpart to `STORY-TEST-005`'s read-side `repo.tp.TpPriceRepository` test: together they cover the whole `tp_prices` NULL-price pipeline (`docs/TEST_STRATEGY.md` §6.11/§7's "unknown TP price != zero-cost purchase" invariant) from sync-write to repository-read. This advances `docs/ROADMAP.md` §4 Phase 0's still-unchecked exit criteria for real-PostgreSQL integration coverage of "critical repositories/sync flows used by the crafting domain."

## Authoritative Source Documents / Sections

- `docs/TEST_STRATEGY.md` §6.11, §7, §9 "Repository Integration Tests", §31.2 "Layer 2 — PostgreSQL Integration Tests".
- `docs/ROADMAP.md` §4 "Phase 0 — Build & Test Foundation".
- `src/main/java/sync/TpSync.java` (`syncTpPrices(Set<Integer>)` and its private `upsertTpRow(...)` helper — the upsert SQL and NULL-handling logic under test).
- `src/main/java/util/TpPrice.java` (`hasMarketData()` — the record whose `null` fields drive the NULL-write path).
- `src/test/java/sync/CharacterSyncTest.java` (the established Layer 2 pattern to reuse: disposable uniquely-named schema, `CREATE SCHEMA`/`DROP SCHEMA ... CASCADE`, a production method's visibility bumped from `private` to package-private `static` as the only production change, no live GW2 API call).

## Context

`TpSync.syncTpPrices(Set<Integer> itemIds)` currently does two things inextricably in one method: (1) fetches live prices from the GW2 API via `fetchTpBatchParsed(...)`, and (2) upserts each resulting `TpPrice` into `tp_prices` via a batched `PreparedStatement` using `ON CONFLICT (item_id) DO UPDATE`, writing an explicit all-`NULL` row (via `DbBind.setLongOrNull`/`setIntOrNull`) when `!quote.hasMarketData()` rather than coercing to `0`. That NULL-write is exactly what `STORY-TEST-005`'s read-side test proves `TpPriceRepository.loadTpQuotes(...)` must later preserve — but the write path itself has never run against a real Postgres engine in an automated test, only manually via the live application.

Per `docs/TEST_STRATEGY.md` §14/§31.2 (no live external dependencies in Layer 2 tests), this story must not call the live GW2 API. That means the upsert logic needs to be reachable independently of `fetchTpBatchParsed`'s network call — following the exact precedent `STORY-TEST-003` already set for `CharacterSync` (extracting/exposing a package-private static write method callable with already-constructed model rows).

## Acceptance Criteria

- A new Layer 2 test (real, disposable PostgreSQL schema — no Testcontainers/Docker, following the existing `CharacterSyncTest`/`InventoryRepositoryOwnedInventoryTest` pattern) seeds a minimal local `tp_prices` table and exercises `TpSync`'s upsert write logic directly with constructed `util.TpPrice` values — no live GW2 API call.
- The test proves, against real Postgres: (a) a `TpPrice` with `hasMarketData() == true` upserts all four numeric columns with their given non-null values, (b) a `TpPrice` with `hasMarketData() == false` (e.g. `TpPrice.noData(itemId)`) upserts a row with all four price/quantity columns `NULL` (not `0`), (c) re-running the upsert for the same `item_id` updates the existing row in place (verified via `fetched_at` and/or a changed value) rather than inserting a duplicate, per the table's `item_id` uniqueness.
- Any production visibility change needed to make the write logic callable from the test (e.g. bumping a private method to package-private `static`, mirroring `STORY-TEST-003`'s `CharacterSync` precedent) is the only production change allowed — no SQL text, upsert semantics, or the live-fetch path may change.
- The test does not require or mutate the developer's normal/working local database, and does not depend on the live GW2 API or a real API key.
- `./mvnw test` passes, including this new test and the full existing suite.

## Required Tests

- The new `sync.TpSync` Layer 2 write-path test itself (this story's entire deliverable).
- `./mvnw test` — full existing suite must continue to pass unchanged.

## Constraints

- Do not change `TpSync`'s upsert SQL, column semantics, or the live-fetch (`fetchTpBatchParsed`/`TpPriceApi`) path — this is a test-seam-plus-test story only, unless the test surfaces an actual defect, in which case stop and report it rather than silently fixing it (per `CLAUDE.md`'s bug-fix workflow).
- Do not introduce Testcontainers/Docker or any other new test-infrastructure tooling; reuse the disposable-schema-on-the-existing-local-Postgres-server approach already established.
- Do not touch `repo.tp.TpPriceRepository` or `STORY-TEST-005`'s work — this story is scoped to the write side only.
- Follow `docs/CODING_GUIDELINES.md`.

## Dependencies

None. (Independent of `STORY-TEST-005` — both may run in either order, though together they close the same `tp_prices` NULL-handling invariant end-to-end.)

## Definition of Done

- [x] New Layer 2 test added under `src/test/java/sync/`, passing, proving `TpSync`'s upsert write path's NULL-preservation and upsert-on-conflict behavior against real PostgreSQL.
- [x] Test does not require or mutate the developer's normal database, and does not call the live GW2 API.
- [x] Any minimal production visibility change needed to make the test possible is documented in the Result section.
- [x] `./mvnw test` shows the new test passing alongside the full existing suite.
- [x] `agent/runtime/artifacts/CLAUDE_RESULT.md` filled in.

## Result

**What changed**

- `src/main/java/sync/TpSync.java`: extracted the per-batch upsert loop that previously lived inline
  inside `syncTpPrices` into a new package-private static method
  `upsertTpPrices(Connection con, List<TpPrice> quotes, Timestamp runTs)`. It prepares the same
  unchanged `INSERT ... ON CONFLICT (item_id) DO UPDATE` SQL, loops the given quotes through the
  existing private `upsertTpRow` helper (unchanged), and calls `ps.executeBatch()`. `syncTpPrices`
  now just calls this new method inside its existing per-HTTP-batch try/commit/rollback block. No SQL
  text, upsert semantics, or the live-fetch (`fetchTpBatchParsed`/`TpPriceApi`) path changed. This is
  the only production change, mirroring `STORY-TEST-003`'s `CharacterSync` precedent (a package-private
  static write method taking `Connection` + already-constructed model rows + a timestamp).
  One minor side effect of the extraction: the `PreparedStatement` is now prepared once per HTTP batch
  (inside `upsertTpPrices`) instead of once for the whole sync and reused via `clearBatch()` — same as
  the existing `CharacterSync.replaceCharacterCrafting`/`replaceCharacterItems` pattern already does.
  This is an implementation detail, not a semantic or SQL change.
- `src/test/java/sync/TpSyncTest.java` (new): Layer 2 test following `CharacterSyncTest`'s disposable-schema
  pattern (`CREATE SCHEMA test_tpsync_<uuid>` / `DROP SCHEMA ... CASCADE`, connection via
  `repo.EnvConfig.require(...)`, matching `TpPriceRepositoryTest`'s `tp_prices` DDL). Three tests call
  `TpSync.upsertTpPrices` directly with constructed `TpPrice` values (no live GW2 API call):
  1. a quote with `hasMarketData() == true` writes all four numeric columns with their given non-null values;
  2. `TpPrice.noData(itemId)` writes all four price/quantity columns as SQL `NULL` (verified via
     `ResultSet.getObject(...) == null`, not `0`);
  3. re-running the upsert for the same `item_id` with a later timestamp updates the existing row in
     place (single row, `MAX(buy_unit_price)` and `MAX(fetched_at)` reflect the second run) rather than
     inserting a duplicate.

**Why**

Closes the write-side half (this repo's first automated coverage of `TpSync`'s DB writes) of the
`tp_prices` NULL-price pipeline invariant from `docs/TEST_STRATEGY.md` §6.11/§7 ("unknown TP price !=
zero-cost purchase"), complementing `STORY-TEST-005`'s read-side `TpPriceRepository` test. Advances
`docs/ROADMAP.md` §4 Phase 0's real-PostgreSQL integration coverage exit criteria.

**Tests run**

`./mvnw test` — full suite: 42 tests, 0 failures, 0 errors (includes the 3 new `sync.TpSyncTest` tests).

**Remaining uncertainty**

None. No defect was found in the existing upsert logic; the extraction is a pure seam with no
behavior change, and the new tests confirm the NULL-write and upsert-on-conflict behavior work
correctly against real PostgreSQL.

## Blockers

None.
