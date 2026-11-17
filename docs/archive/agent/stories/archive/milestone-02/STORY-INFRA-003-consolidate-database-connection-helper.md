## Story ID

STORY-INFRA-003

## Title

Consolidate repository and synchronization connection helpers

## Status

DONE

## Milestone

milestone-02

## Goal

Replace `repo.Db` and `sync.Db` with one shared connection helper while preserving connection behavior.

## Authoritative Source Documents / Sections

- `docs/ROADMAP.md` §6, Phase 2 connection-helper exit criterion and behavior-preserving dependency.
- `docs/KNOWN_PROBLEMS.md` §4.3.
- `docs/TARGET_ARCHITECTURE.md` §7 Domain Independence Rule.

## Context

The documented helpers duplicate connection creation and both read `repo.AppConfig`. Phase 2 explicitly requires consolidation; the shared infrastructure must stay outside the independent crafting domain.

## Acceptance Criteria

1. One connection helper serves all existing callers of `repo.Db` and `sync.Db`; the duplicate implementation is removed.
2. Existing configuration inputs, connection creation, error propagation, and caller-owned connection lifecycle remain behaviorally unchanged.
3. No JDBC or shared connection-helper dependency is introduced into crafting domain objects or calculations.
4. No SQL, transaction semantics, synchronization behavior, or domain rules change as part of consolidation.

## Required Tests

- Compile all affected repository and synchronization callers.
- Run existing targeted repository/synchronization integration checks that exercise affected connection paths. Inspect whether supplied-connection test seams bypass the helper; where needed, perform a focused connection-open/close check using the existing test database setup.
- Record actual checks and results, including configuration/database availability limitations; do not claim checks of the shared helper when only bypassing seams were exercised.

## Constraints

Keep this a small behavior-preserving consolidation. No pooling, new configuration system, dependency upgrades, schema changes, credential cleanup outside the helper scope, or later-milestone implementation. The helper remains infrastructure code.

## Dependencies

None.

## Definition of Done

All callers use the single helper, the duplicate is removed, and required verification is recorded in Result. Update `docs/KNOWN_PROBLEMS.md` §4.3 and relevant current-architecture statements to match verified implementation facts.

## Result

**Implemented.** Deleted `sync/Db.java`. `repo.Db` (unchanged method: `open()`, including its `TEST_SCHEMA_PROPERTY` UI-test hook) is now the single connection helper for both packages. Updated all six `sync.*` write-path classes (`TpSync`, `RecipeSync`, `ItemSync`, `IconSync`, `CharacterSync`, `AccountSync`) to `import repo.Db;` and call `Db.open()` in place of the deleted `Db.openConnection()` — same call sites, same try-with-resources connection lifecycle, no SQL/transaction/autocommit/commit/rollback changes.

Configuration inputs, connection creation, and error propagation are unchanged: both callers already read the same `repo.AppConfig` (`DB_URL`/`DB_USER`/`DB_PASS`), and `open()`'s implementation (`DriverManager.getConnection(...)`, then the optional test-schema override) is exactly what `repo.*` callers already ran. The only behavioral difference folded in is that `sync.*` callers now also honor `TEST_SCHEMA_PROPERTY` if that system property were ever set during a sync call — it never is in production or in any existing test, and `docs/CURRENT_ARCHITECTURE.md`/`KNOWN_PROBLEMS.md` already documented the two helpers as having "identical behavior."

No JDBC/connection-helper dependency exists in `craft.*` (verified: no `craft/*.java` file was changed or references `repo.Db`).

**Tests run:**
- `./mvnw -o compile` and `./mvnw -o test-compile` — both clean.
- `./mvnw -o test` (full suite) — 73 pre-existing tests pass, 0 failures/errors, after the consolidation.
- Added `repo/DbTest.java`: a new Layer 2 PostgreSQL integration test that calls `Db.open()` directly and asserts the returned connection is open/valid, then closed after `close()`. This was necessary per the Required Tests note — inspection confirmed every existing `repo`/`sync` integration test (`CharacterSelectionCraftingPlanIntegrationTest`, `TpSyncTest`, `RecipeSyncTest`, `CharacterSyncTest`, `AccountSyncTest`, `RecipeRepositoryTest`, `TpPriceRepositoryTest`, `InventoryRepository*Test`) opens its own disposable-schema connection directly via `DriverManager.getConnection(EnvConfig...)`, bypassing `Db.open()`/`Db.openConnection()` entirely, so none of them exercised the actual shared helper. Full suite with this test included: 74 tests, 0 failures/errors, run against the developer's local PostgreSQL (real DB, not mocked).

**Docs updated:** `docs/KNOWN_PROBLEMS.md` §4.3 and its §6 summary table row marked Resolved; `docs/CURRENT_ARCHITECTURE.md` §3 diagram/prose and §9 items 5–6 updated to reflect the single `repo.Db.open()` helper; `docs/ROADMAP.md` §6 Phase 2 exit criterion and story-list bullet marked Done.

**Remaining uncertainty:** None material. The `TEST_SCHEMA_PROPERTY` extension-in-practice noted above only matters if a future sync-path UI test sets that property, which none currently do.

## Blockers

None.
