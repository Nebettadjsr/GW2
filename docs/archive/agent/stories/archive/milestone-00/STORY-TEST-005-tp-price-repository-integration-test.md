## Story ID

STORY-TEST-005

## Title

Layer 2 PostgreSQL integration test: `repo.tp.TpPriceRepository` NULL-price and batch-lookup semantics

## Status

DONE

## Milestone

milestone-00

## Goal

Give `repo.tp.TpPriceRepository` its first automated test, run against a real, disposable PostgreSQL schema. This advances `docs/ROADMAP.md` §4 Phase 0's still-unchecked exit criteria "Persistence-relevant code paths are covered by integration tests against a real PostgreSQL instance, not only mocked/in-memory behavior" and "At least the critical repositories/sync flows used by the crafting domain are verified against the real database schema, constraints, and upsert/delete semantics." `TpPriceRepository.loadTpQuotes(...)` is the sole source of Trading Post buy/sell prices consumed by `CraftingResolver`; `docs/TEST_STRATEGY.md` §6.11/§7 requires "Missing or invalid prices must never become free purchases" and "unknown TP price != zero-cost purchase" as an explicit invariant, but the repository method responsible for correctly preserving a `NULL` price from the database (rather than coercing it to `0`) has never been exercised against a real Postgres engine.

## Authoritative Source Documents / Sections

- `docs/TEST_STRATEGY.md` §6.11 "Trading Post Price Selection", §7 (the "unknown TP price != zero-cost purchase" invariant), §9 "Repository Integration Tests", and §31.2 "Layer 2 — PostgreSQL Integration Tests".
- `docs/ROADMAP.md` §4 "Phase 0 — Build & Test Foundation", the two exit criteria named in the Goal above.
- `src/main/java/repo/tp/TpPriceRepository.java` (`loadTpQuotes(Set<Integer> itemIds)` — the method under test).
- `src/test/java/repo/InventoryRepositoryOwnedInventoryTest.java` (existing Layer 2 test pattern — reuse the disposable-schema approach: `CREATE SCHEMA <unique>` / `setSchema(...)` / `DROP SCHEMA ... CASCADE`, no new tooling).

## Context

`TpPriceRepository.loadTpQuotes(itemIds)` runs a single batched query (`WHERE item_id = ANY(?)`) against `tp_prices` and maps each row's nullable `buy_unit_price`/`sell_unit_price` columns into a `TpQuote(Integer buyUnit, Integer sellUnit)`, using `rs.getObject(...)` specifically so a SQL `NULL` becomes a Java `null` rather than JDBC's `getInt(...)` default of `0`. This distinction is exactly what the domain layer's price-unavailable handling (`BlockedReason.PRICE_UNAVAILABLE`, already fixed and tested at the domain level in `STORY-DOM-003`) depends on being correct at the source — if the repository ever silently turned a missing price into `0`, every downstream domain test using a mocked/hand-built price map would keep passing while the real application produced free-purchase bugs. This has never been verified against a real Postgres engine's actual `NULL` semantics and JDBC driver behavior.

## Acceptance Criteria

- A new Layer 2 test (real, disposable PostgreSQL schema — no Testcontainers/Docker, following the existing `InventoryRepositoryOwnedInventoryTest` pattern) seeds `tp_prices` rows directly via SQL/JDBC and exercises `TpPriceRepository.loadTpQuotes(...)`.
- The test proves, against real Postgres: (a) an item with both `buy_unit_price` and `sell_unit_price` populated returns both as the correct non-null values, (b) an item with one or both columns `NULL` returns `null` (not `0`) for that field on the resulting `TpQuote`, (c) an item ID not present in `tp_prices` at all is simply absent from the returned map (not present with zero/null values), and (d) a batched lookup of multiple item IDs returns exactly the requested-and-present rows, correctly keyed by `item_id`, with no cross-item value mixups.
- An empty input `Set<Integer>` returns an empty map without executing a query that would fail on an empty SQL array (matching the existing early-return in `loadTpQuotes`).
- The test does not require or mutate the developer's normal/working local database, and does not depend on the live GW2 API or a real API key.
- `./mvnw test` passes, including this new test and the full existing suite.

## Required Tests

- The new `repo.tp.TpPriceRepository` Layer 2 test itself (this story's entire deliverable).
- `./mvnw test` — full existing suite must continue to pass unchanged.

## Constraints

- Do not change `TpPriceRepository`'s SQL or method signature — this is a test-only story, unless the test surfaces an actual defect, in which case stop and report it rather than silently fixing it (per `CLAUDE.md`'s bug-fix workflow).
- Do not introduce Testcontainers/Docker or any other new test-infrastructure tooling; reuse the disposable-schema-on-the-existing-local-Postgres-server approach already established.
- Follow `docs/CODING_GUIDELINES.md`.

## Dependencies

None.

## Definition of Done

- [x] New Layer 2 test added under `src/test/java/repo/tp/`, passing, proving `TpPriceRepository.loadTpQuotes(...)`'s NULL-preservation, missing-item-omission, and batch-lookup behavior against real PostgreSQL.
- [x] Test does not require or mutate the developer's normal database, and does not call the live GW2 API.
- [x] Any minimal production change needed to make the test possible (if any) is documented in the Result section.
- [x] `./mvnw test` shows the new test passing alongside the full existing suite.
- [x] `agent/runtime/artifacts/CLAUDE_RESULT.md` filled in.

## Result

Added `repo.tp.TpPriceRepositoryTest` (`src/test/java/repo/tp/TpPriceRepositoryTest.java`), the
project's first automated coverage of `repo.tp.TpPriceRepository`. Six tests, run against a real,
disposable PostgreSQL schema (`test_tp_<random>`, created/dropped by the test itself, following
`InventoryRepositoryOwnedInventoryTest`/`RecipeRepositoryTest`'s pattern — no Testcontainers/
Docker), seed a minimal `tp_prices` table directly via JDBC and exercise
`loadTpQuotes(Connection, Set)` — no live GW2 API call, no parser involved.

Minimal production changes made to enable the test (per this story's own allowance, since
`TpPriceRepository` had no way to point its query at a caller-supplied connection):

- `src/main/java/repo/tp/TpPriceRepository.java` — added a `loadTpQuotes(Connection, Set<Integer>)`
  overload carrying the existing SQL and mapping logic unchanged; the original
  `loadTpQuotes(Set<Integer>)` now opens a connection via `Db.open()` and delegates to it. This is
  the same additive test-seam pattern already established by `InventoryRepository`
  (`STORY-TEST-002`) and `RecipeRepository` (`STORY-TEST-004`) — no SQL text or existing method
  signature changed, no behavior change for any existing caller.
- `src/main/java/repo/EnvConfig.java` — widened `EnvConfig` and `EnvConfig.require(...)` from
  package-private to `public`, so the new test (in package `repo.tp`, matching
  `TpPriceRepository`'s own package/directory) can reuse the existing `DATABASE_URL`/
  `DATABASE_USER`/`DATABASE_PASSWORD` lookup instead of duplicating the `.env` fallback logic.
  `EnvConfig.optional(...)` was left package-private since nothing outside `repo` needs it yet.

No defect was found in `TpPriceRepository`: `NULL` `buy_unit_price`/`sell_unit_price` columns were
already correctly preserved as Java `null` (via `rs.getObject(...)`, not `getInt(...)`), items
absent from `tp_prices` were already simply absent from the result map, and the batched
`= ANY(?)` lookup was already correctly keyed with no cross-item mixups — so no production
behavior beyond the additive `Connection` overload was changed.

Tests added (`repo.tp.TpPriceRepositoryTest`, 6 tests, Layer 2 PostgreSQL integration):
- `loadTpQuotes_itemWithBothPrices_returnsBothAsNonNullValues`
- `loadTpQuotes_itemWithBothPricesNull_returnsNullNotZeroForBothFields`
- `loadTpQuotes_itemWithOnlySellPriceNull_returnsNullOnlyForSellField`
- `loadTpQuotes_itemNotInTpPrices_isAbsentFromResultingMap`
- `loadTpQuotes_batchedLookup_returnsOnlyRequestedAndPresentRowsCorrectlyKeyed`
- `loadTpQuotes_emptyItemIdSet_returnsEmptyMapWithoutQueryingDatabase`

Tests run: `./mvnw test` (full suite) — `BUILD SUCCESS`, all tests passing including the 6 new
ones; also ran `./mvnw test -Dtest=repo.tp.TpPriceRepositoryTest` directly to confirm: 6 run, 0
failures, 0 errors.

## Blockers

None.
