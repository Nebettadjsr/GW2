## Story ID

STORY-TEST-002

## Title

Repository-level intended-behavior test: owned-material pool includes character inventories

## Status

DONE

## Milestone

milestone-00

## Goal

Close the last open item of `docs/ROADMAP.md` §4 Phase 0's exit criteria: give `docs/KNOWN_PROBLEMS.md` §3.3 (owned-material pool omitting character inventories) at least one automated test expressing the intended, spec-correct behavior (`DOMAIN_SPEC.md` §9 / DQ-006 — owned pool = material storage + bank + all character inventories). The production fix already exists (`repo.InventoryRepository.loadOwnedInventory()` already sums `account_materials` + `account_bank` + `character_items`, added by `STORY-DOM-004`/`STORY-SYNC-001`), but it has only ever been checked with a throwaway, uncommitted manual-verification harness against the developer's local Postgres instance (`agent/PROJECT_STATE.md`'s `STORY-DOM-004` result) — there is still no automated regression test for it.

## Authoritative Source Documents / Sections

- `docs/DOMAIN_SPEC.md` §9 / DQ-006 (owned pool definition — already decided, not an open question).
- `docs/KNOWN_PROBLEMS.md` §3.3 (the conflict this test closes out).
- `docs/ROADMAP.md` §4 "Phase 0 — Build & Test Foundation", exit criterion 3 (the specific unchecked box this story satisfies).
- `docs/TEST_STRATEGY.md` §9 "Repository Integration Tests" and §29 "Tooling Decisions" (repository-integration test tooling is explicitly *not yet decided*, but §29 gives selection criteria: favor simplicity, fast execution, good IDE support, good Claude Code usability, minimal configuration overhead; §9 requires such tests not require the developer's normal database).
- `src/main/java/repo/InventoryRepository.java` (`loadOwnedInventory()` — the method under test).
- `src/main/java/repo/Db.java` (current connection helper).

## Context

`InventoryRepository.loadOwnedInventory()` opens a live JDBC connection (`repo.Db.open()`) and runs three plain SQL queries (`account_materials`, `account_bank`, `character_items`), merging all three into one `Map<Integer,Integer>`. There is no seam allowing this to be tested without an actual PostgreSQL database — unlike the pure-domain `craft.*` tests, which construct everything in memory via `CraftTestFixtures`. `docs/TEST_STRATEGY.md` §9 explicitly anticipates this ("Repository tests verify actual PostgreSQL behavior... may use a disposable test PostgreSQL instance") but leaves the exact tooling open (candidates: Testcontainers, another Docker-based approach, or another isolated test database approach) and explicitly requires such a test not depend on the developer's normal/working database.

## Acceptance Criteria

- A new automated test exercises `InventoryRepository.loadOwnedInventory()` (or an equivalent repository-level entry point) against a real, disposable PostgreSQL schema/database seeded with rows in `account_materials`, `account_bank`, and `character_items` for the same `item_id`, and asserts the returned map sums quantities from **all three** tables for that item — i.e. it fails against the pre-`STORY-DOM-004` behavior (materials + bank only) and passes against current behavior.
- The test must not require or mutate the developer's normal/working local database (`docs/TEST_STRATEGY.md` §9) — it must use a separate database/schema/connection, created and torn down by the test itself or its setup.
- Choose the tooling/mechanism for the disposable database per `docs/TEST_STRATEGY.md` §29's stated criteria (simplicity, fast execution, minimal configuration overhead) rather than inventing new criteria. Document the choice briefly in this story's Result section.
- `./mvnw test` passes, including this new test and the full existing suite. If the chosen approach requires something beyond `./mvnw test` to run locally (e.g. a running local Postgres server on a specific port), state that explicitly in the Result section together with how CI/other environments are expected to satisfy it.

## Required Tests

- The new repository-level test itself (this story's entire deliverable).
- `./mvnw test` (or `./mvnw -DskipITs test` plus a documented separate command for this test, if it must be excluded from the default fast run because it needs a real database) — full existing suite must continue to pass unchanged.

## Constraints

- Do not implement or change binding/soulbound filtering (`KNOWN_PROBLEMS.md` §3.4) — out of scope, tracked separately and blocked on `agent/user-decisions/UD-001-selected-character-concept.md`.
- Do not change `loadOwnedInventory()`'s production behavior — this story is test-only unless a minimal, behavior-preserving seam (e.g. accepting a `Connection`) is genuinely necessary to make the test possible; if so, keep it minimal and note it explicitly in the Result section.
- If making this test possible would require installing new software the environment does not already have (e.g. Docker for Testcontainers) rather than using the PostgreSQL instance already required by this project's baseline, stop and report that as a blocker instead of installing tooling unilaterally — per `CLAUDE.md`'s Default Behavior ("ask before implementing" an undecided architecture/tooling choice that needs external input).
- Follow `docs/CODING_GUIDELINES.md`.

## Dependencies

None.

## Definition of Done

- [x] New repository-level test added, passing, verifying `loadOwnedInventory()` sums `account_materials` + `account_bank` + `character_items`.
- [x] Test does not require or mutate the developer's normal database.
- [x] `./mvnw test` (or the documented equivalent) shows the new test passing alongside the full existing suite.
- [x] `agent/CLAUDE_RESULT.md` filled in. Do not check off `docs/ROADMAP.md` §4 Phase 0 exit criterion 3 yourself — that criterion covers all of §3.1-§3.6 collectively, and §3.4/§3.6 remain separately blocked (`UD-001`, `UD-002`); leave the roadmap checkbox for a future planning run to update once the whole criterion is actually satisfied.

## Result

Added `repo.InventoryRepositoryOwnedInventoryTest`
(`src/test/java/repo/InventoryRepositoryOwnedInventoryTest.java`), a Layer 2 PostgreSQL
integration test (`docs/TEST_STRATEGY.md` §31.2) exercising `InventoryRepository.loadOwnedInventory()`
end-to-end.

**Tooling choice (§29):** no new tooling was introduced. The test connects to the same local
PostgreSQL server this project's baseline already requires (via `EnvConfig`'s existing
`DATABASE_URL`/`DATABASE_USER`/`DATABASE_PASSWORD` lookup), but creates a uniquely-named,
disposable schema (`test_inv_<random>`) per test run, sets that schema as the connection's
sole `search_path` (`Connection.setSchema(...)`), creates fresh `account_materials`,
`account_bank`, `characters`, and `character_items` tables inside it (mirroring the real DDL
in `src/PostgreSQL Query to create DB`), and drops the schema (`DROP SCHEMA ... CASCADE`) in
an `@AfterEach`. Because the schema is the only entry on `search_path` during the test, the
unqualified table names `loadOwnedInventory()` queries never resolve to the developer's real
`public`-schema tables/data — satisfying `docs/TEST_STRATEGY.md` §9's "must not require or
mutate the developer's normal/working database" without touching it even for reads.
Testcontainers/Docker-based Postgres was considered (`docs/TEST_STRATEGY.md` §9/§29
candidates) but rejected: it would be new tooling requiring a running Docker daemon (not
currently running in this environment — `docker ps` fails to reach the daemon even though the
Docker CLI is installed), whereas this project's baseline already assumes and requires a
reachable local PostgreSQL server. The schema-isolation approach satisfies §29's stated
criteria (simplicity, fast execution, minimal configuration overhead) better than adding a
Docker dependency would.

**Production seam:** `InventoryRepository.loadOwnedInventory()` was split into the existing
no-arg method (still opens its connection via `repo.Db.open()`, unchanged production
behavior) delegating to a new public overload `loadOwnedInventory(Connection con)` containing
the actual query logic. This is the minimal seam the story's Constraints section allows —
without it, the test would have no way to point the query at its disposable schema without
also satisfying `repo.AppConfig`'s unrelated `GW2_API_KEY` static-init requirement or
overwriting the developer's real `DATABASE_URL`.

**Verified the test is meaningful:** temporarily removed the `character_items` query from
`loadOwnedInventory(Connection)` (reproducing pre-`STORY-DOM-004` behavior) and reran the new
test in isolation — it failed (`expected: <15> but was: <8>`), confirming it actually
distinguishes old vs. current behavior. Reverted immediately afterward.

**Local/CI requirement:** this test requires a reachable local PostgreSQL server on the
connection already configured via `.env`/`DATABASE_URL` (the same server the whole
application already requires — nothing additional). The connecting user must be able to
`CREATE SCHEMA`/`DROP SCHEMA` in that database (true for the project's local `postgres`
superuser role). CI or other environments must provide an equivalent reachable PostgreSQL
instance and matching `DATABASE_URL`/`DATABASE_USER`/`DATABASE_PASSWORD` env vars (or `.env`)
for this test to run — no other setup script or migration tool is required since the test
creates and tears down its own schema/tables.

Tests run: `./mvnw test` — 11 tests, 0 failures, 0 errors, `BUILD SUCCESS` (previous 10 tests
unchanged, plus this 1 new test).

## Blockers

None.
