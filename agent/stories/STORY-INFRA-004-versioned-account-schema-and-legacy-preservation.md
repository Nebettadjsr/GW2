## Story ID

STORY-INFRA-004

## Title

Introduce versioned account schema and reset legacy cache

## Status

UNFINISHED

## Milestone

milestone-06

## Goal

Establish the production PostgreSQL migration chain and constrained account schema, resetting disposable legacy cache data before account-scoped use.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md, Phase 6: shared database, stable account scope, legacy cache reset.
- docs/TARGET_ARCHITECTURE.md sections 7.1-7.4; established AR-010 decision.
- agent/user-decisions/UD-016-database-is-a-disposable-cache.md.
- docs/CURRENT_ARCHITECTURE.md sections 7-8, current tenancy and schema lifecycle.
- docs/DOMAIN_SPEC.md sections 6, 9 and 11.1, preservation of recipe knowledge and inventory semantics.
- docs/TEST_STRATEGY.md sections 9, 26.1, 31.2 and 36.
- docs/QUALITY_METRICS.md, Test coverage KPI and Generation and review.

## Context

The architecture selects Flyway and treats the existing database as a disposable GW2 API cache. Current documentation describes manual schema creation and unscoped legacy rows. This story establishes the schema and explicit reset gate; application publication and scoped repositories follow separately.

## Acceptance Criteria

1. Integrate the Flyway Java engine and PostgreSQL module using compatible managed dependencies. Initialization validates/migrates before repository, scheduler or task access. Retained entry points use the same gate or are disabled against the upgraded database. Flyway becomes the sole schema writer for migrated objects; automatic baseline, destructive clean and automatic checksum repair remain disabled.
2. Implement the versioned production schema for the global/account classification in section 7.2, including the GUID registry, account-owned composite uniqueness/foreign keys, character child ownership, explicit inventory slot/null semantics, account/source generations and completion times, and nullable historical activity with UTC timestamps. Store no credentials or sessions in these tables.
3. Gate startup before repositories, tasks and schedulers run. For unmanaged legacy tables or an unrecognized schema, stop legacy writers and scheduled work, explicitly reset cached tables, then apply the full versioned chain to an empty schema. Do not infer old-row ownership, baseline a nonempty schema or treat empty tables as successful synchronization.
4. A validated credential creates or reuses the verified account GUID; its missing sources are populated by fresh GW2 API synchronization through later scoped application work. The schema gate itself makes no upstream calls and fabricates no activity or source-completion history.
5. Migrations use transactional PostgreSQL operations. A failed migration rolls back that version; earlier applied versions remain recorded and retries resume from history. Applied versions are immutable. Failed managed migrations and checksum mismatches do not silently trigger reset or automatic repair. Unscoped runtime paths cannot run against the upgraded account schema.
6. Document the explicit reset and resynchronization path, including the condition that service access resumes only after a valid managed schema is ready. No backup, archive, import ledger or reconciliation step gates cutover.

## Required Tests

- Real isolated PostgreSQL tests using the production migration chain for an empty database and representative legacy/unrecognized schemas. Verify explicit reset yields the empty-schema result, with missing source markers until fresh synchronization.
- Verify ownership constraints across two GUIDs, character-child rejection, duplicate slot/null semantics and shared global-table structure through the runtime database access boundary.
- Verify failed migration rollback and retry, checksum mismatch, disabled automatic baseline/clean/repair, and no accidental reset of a managed schema after an ordinary migration failure. Use deterministic failure injection where necessary.
- Put reproducible migration tests in the default CI-selected test suite where practical; explicitly name and execute any additional real-database check excluded from that suite. No live GW2 API or normal local database dependency.

## Constraints

- Follow TARGET_ARCHITECTURE sections 7.2-7.4 rather than designing another migration or identity scheme.
- No automatic deletion, cleanup threshold, distributed coordination, application authentication redesign or domain calculation changes.
- This story does not implement full scoped repository access, source synchronization or browser access. Keep cutover gated until retained runtime paths are compatible; do not leave legacy writers active on the new schema.
- Do not change agent/runtime/, local_bridge/ or CI workflow configuration.

## Dependencies

None.

## Definition of Done

- Production migration/reset paths, targeted tests and operator instructions satisfy the criteria, including an explicit account-data resynchronization path.
- Review affected backend coverage reports against docs/QUALITY_METRICS.md; report measured evidence and remaining verification/coverage gaps without inventing a baseline or changing measurement scope.
- Update current architecture/runtime documentation for delivered behavior; distinguish unavailable downstream account access from completed migration support.
- The CI gate is green (verified by the pipeline after acceptance).

## Result

Implemented. The production Flyway chain, the startup schema gate with its explicit unmanaged-schema
cache reset, the constrained GUID-scoped account schema and the refusal of the retained unscoped
account paths on a migrated database are in place and verified against real PostgreSQL 17.5. The
withdrawn archive/adoption/provenance/reconciliation design was not reused; only the catalog-verified
legacy/global DDL from `backup/story-infra-004-heavy-impl` was carried over as migration content.

Downstream scoped account access is **not** delivered, which is what keeps the cutover gated: a
migrated database serves global data and reports account access as unavailable.

### What changed

**Dependencies** (`pom.xml`) — `flyway-core` and `flyway-database-postgresql`, versions managed by
the existing `spring-boot-dependencies` BOM (resolved 12.4.0 for Spring Boot 4.1.1, confirmed with
`dependency:list`). Open-source features only: versioned SQL, history, validate, migrate.

**Migrations** (`src/main/resources/db/migration/`)

- `V1__global_reference_and_economy.sql` — the global half (`items`, `recipes`,
  `recipe_ingredients`, `tp_tradeable_items`, `tp_prices`, `material_categories`,
  `material_category_items`). No table carries `account_id`.
- `V2__account_registry_and_scoped_account_data.sql` — `account_registry` (uuid PK = verified
  `/v2/account` GUID; nullable `last_seen_at`/`last_successful_sync_at` and `created_at` as
  `timestamptz`), `account_source_state` `(account_id, source)` with `generation > 0` and
  `completed_at`, `account_bank` `(account_id, slot)`, `account_materials` `(account_id, item_id)`,
  `account_recipes` `(account_id, recipe_id)`, `account_luck` `(account_id)`, `characters` with the
  `(account_id, character_id)` parent key and `(account_id, name)` unique, and the three character
  children with composite foreign keys to that parent. `character_items` uses
  `UNIQUE NULLS NOT DISTINCT` over account, character and the five location columns, replacing the
  legacy `NULLS DISTINCT` index that let duplicate logical slots exist. No credential, session or
  application-user field exists anywhere in the chain.

**New code**

- `infra.db.SchemaInitializer` — the gate. Classifies the connection's current schema as managed
  (Flyway history present), empty, or unmanaged; validates/migrates the first two; for the third
  drops every table, view and sequence in that schema in one transaction and then applies the
  complete chain. `baselineOnMigrate=false`, `cleanDisabled=true`, `validateOnMigrate=true`, and
  `repair()` is never called; `configuration()` exposes those three as `MigrationPolicy`. The reset
  is shape-blind, so a legacy schema and a drifted one are handled identically with no fingerprint,
  whitelist or ownership inference. `initialize(DataSource, String)` appends one deterministic
  transactional failure as the chain's last version, for the rollback/retry behaviour only.
- `repo.ManagedSchema` — recognizes a managed schema by its history table (a catalog read, so it is
  safe inside the `REPEATABLE READ` calculation snapshot) and refuses the unscoped legacy account
  paths there with an actionable diagnostic.

**Wiring** — all three retained entry points (`web.Gw2ApiApplication.main`, `Gw2App.main`,
`sync.IconSync.main`; these are the only `main` methods in `src/main/java`) run
`SchemaInitializer.initializeRuntimeDatabase()` as their first statement, before the Spring context
exists and therefore before any repository, task, scheduler or view controller can open a
connection. Refusals on a migrated database: `AccountSyncStateRepository.ensure`/`lookup` (the choke
point every account read and every account writer passes through), `BankRepository`,
`CharacterRepository`, `InventoryRepository` (all three overloads), `RecipeRepository`'s two
recipe-knowledge reads, `AccountLuckRepository.save`/`find`, `MaterialStorageRepository`,
`AccountSync`'s account-materials publication (its first write is not a marker),
`CraftingProfitItemCollector.collect`/`coverage`, `DiscoveryItemCollector.collect` and
`IconSync.referencedOrUnknownItemIds`. `AccountLuckSchema.ensure` and `MaterialStorageSchema.ensure`
become no-ops there instead of failing, because their objects are Flyway-owned and the global
material catalog is shared data. On an unmigrated database none of this changes behaviour.

**Docs** — `docs/CURRENT_ARCHITECTURE.md` gains *Versioned schema lifecycle and cache reset* under
section 8 (the chain, the three states, the disabled policies, the reset and the resynchronization
path, the operator runbook, and what a migrated database does not serve yet); section 7 and
`docs/CURRENT_STATE_SPEC.md` (setup, persistence, limitations) now distinguish the unmigrated
database the retained paths serve from a migrated one. `docs/QUALITY_METRICS.md` deliberately
untouched — its provisional snapshot may only be replaced by clean-commit CI results.

### Tests run

Real PostgreSQL 17.5. The QA-owned `infra.db.SchemaInitializerPostgresTest` is a `*Test` class in
the default CI-selected suite, needs no live GW2 API, display or developer data, and no real-database
check for this story is excluded from that suite.

QA has since corrected two assertions in that class — the legacy-reset check now asserts the
recreated account-scoped `account_bank` instead of its absence, and the failed-migration check now
also asserts the injected `qa_injected_failure` table is rolled back. The file in the tree matches
the QA plan's `protected_test_hashes` entry byte for byte and was not touched here. The whole class
was re-run in this invocation against real PostgreSQL 17.5:

```
JAVA_HOME=<jdk-25> DATABASE_URL/DATABASE_USER/DATABASE_PASSWORD exported from .env
./mvnw -B -ntp -o -Dtest=infra.db.SchemaInitializerPostgresTest -DfailIfNoSpecifiedTests=false test
  Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 7.823 s   BUILD SUCCESS
```

(`mvnw.cmd` cannot start on this machine — it needs the unavailable `powershell` executable — so the
POSIX wrapper `./mvnw` was used; the `DATABASE_*` values must be real environment variables because
the test reads `System.getenv` and otherwise skips itself.) The fresh report is retained at
`target/surefire-reports/infra.db.SchemaInitializerPostgresTest.txt` (2026-10-20 07:03), with
`TEST-infra.db.SchemaInitializerPostgresTest.xml` recording
`tests="8" errors="0" skipped="0" failures="0" flakes="0"` and all eight test cases by name.

All eight checks now pass and establish: the complete chain on an empty schema with no fabricated
account or source row and no legacy singleton marker; a seeded legacy unscoped `account_bank`
replaced by the account-scoped table with the composite `(account_id, slot)` primary key, no legacy
rows and empty registry/source tables; cross-account bank keys accepted and a duplicate
`(account_id, slot)` rejected; a `character_crafting` child naming another account's character
rejected by the composite foreign key; duplicate `BAG` and `EQUIPMENT` slots rejected through NULL
location components; generation-zero completion rejected; a transactional migration failure leaving
history and the managed tables intact while rolling back its own injected DDL, and a retry resuming
from that history; a tampered checksum failing closed without resetting; and an unrecognized schema
reset while baseline, clean and repair stay disabled. Afterwards the server was re-checked by catalog
query: no leftover `qa_schema_%` schema, 17 `public` tables, no `flyway_schema_history` in `public`.

Because this story adds build dependencies (unbounded blast radius, `TEST_STRATEGY.md` §20) and the
working tree also carries uncommitted STORY-API-010 work the commit will sweep in, the whole default
suite was run locally in the previous invocation, before QA's correction:

```
./mvnw -B -ntp -o clean test jacoco:report -Dmaven.test.failure.ignore=true
  Tests run: 725, Failures: 2, Errors: 0, Skipped: 0
  infra.db.SchemaInitializerPostgresTest                   Tests run: 8, Failures: 1
  application.access.AccountAccessServiceKeyedOperationTest Tests run: 5, Failures: 1
```

The first of those failures was the pre-correction legacy-reset assertion and is gone. The second,
`AccountAccessServiceKeyedOperationTest.rejectsFabricatedOrRevokedSessionContextBeforeProvingKey:63`,
is pre-existing: it belongs to STORY-API-010, which is `BLOCKED` with the open intervention UI-008
("Application tests still need changes after 2 QA correction rounds"). It is an in-memory
`application.access` check that touches nothing this story changed, and it was not re-run in this
invocation, whose scope was the focused PostgreSQL class only.

### Verified beyond the tests

Two throwaway evidence programs were run from outside the repository against disposable schemas and
then deleted (nothing untracked remains; `git status` confirmed).

*Legacy reset, through the production gate* — seeded a legacy `account_bank(slot PRIMARY KEY)` with
one row, a conflicting `items(id)` with one row, and a populated `account_sync_state`:

```
[schema] evidence_…: unmanaged schema reset, dropped 3 relation(s): account_bank, account_sync_state, items
[schema] evidence_…: versioned chain applied (found UNMANAGED).
AFTER: account_bank columns: account_id uuid, slot integer, item_id integer, count integer, binding text,
       bound_to text, charges integer, stats_id integer, stats_attrs jsonb, fetched_at timestamptz
       items columns: item_id, name, type, rarity, level, vendor_value, fetched_at, icon_url, icon_path
       account_bank rows: 0 | items rows: 0 | account_registry rows: 0 | account_source_state rows: 0
       flyway history: 1=true, 2=true   (account_sync_state gone)
```

So the legacy unscoped table and its row are dropped and replaced by the account-scoped shape, the
conflicting global table is replaced by the real one, and nothing claims a synchronized source.

*End-to-end gate ordering and refusal*, through the shipped entry points:

```
1. gate on an empty schema                     -> chain applied (found EMPTY)
2. IconSync.main --dry-run on that schema      -> SQLException, path 'icon-metadata item selection
                                                  without an account scope' is disabled (no coverage
                                                  report printed: it is refused before reading)
3. BankRepository.loadBankSlots()              -> SQLException, 'account_bank without an account scope'
4. CharacterRepository.loadAllCharacterNames() -> SQLException, 'characters without an account scope'
5. MaterialStorageSchema.ensure()              -> no-op; account_materials_sync tables created: 0
6. Gw2ApiApplication.main, tampered checksum   -> FlywayValidateException "Migrations have failed
                                                  validation" before any Spring output
```

The configured database was confirmed untouched afterwards by catalog query: 17 public tables exactly
as before, no `flyway_schema_history` in `public`, no leftover `qa_schema_%`/`evidence_%` schema and
no `gw2_runtime%` role.

### Coverage

Measured in the clean full-suite run above (`jacoco:report`; the previously present report was stale
from the withdrawn attempt and `target/` was cleaned first, which also removed its orphaned
`infra.schema` classes). Package `infra.db`: 79.17% line (76/96), 65.38% branch (17/26) —
`SchemaInitializer` itself 68/86 lines and 17/26 branches. `repo.ManagedSchema` 6/7 lines, 1/2
branches. Project totals: 62.52% line (4575/7318), 55.35% branch (1691/3055), against the 90% KPI
warning target in `QUALITY_METRICS.md`.

Remaining gaps, stated rather than padded: `initializeRuntimeDatabase()`/`runtimeDataSource()` and
the `ManagedSchema` unmanaged branch are exercised by the evidence runs above but by no test, so they
count as uncovered; the uncovered `SchemaInitializer` branches are the view-drop and sequence-drop
arms of the reset, the reset's rollback path, the `current_schema()` null guard and the
failure-injection off-path. QA owns whether any of those warrants a test. No coverage KPI baseline was
changed and the measurement scope was not altered.

## Follow-up Findings

F001: `items.level` is written and read by no production path — `ItemSync` and `IconSync` persist
`name`/`type`/`rarity`/`vendor_value`/`icon_url`/`icon_path` only, and no repository selects it. V1
carries the column so the shared half matches what the application expects, so it is now versioned as
permanently empty data.

F002: `docs/CURRENT_ARCHITECTURE.md` has no section 5.14 — section 5 has no subsections at all — yet
`sync.IconSync`'s javadoc and `docs/KNOWN_PROBLEMS.md` both point readers there for the icon-metadata
refresh behavior. The references predate this story and were left untouched; whichever document now
owns that behavior should be named instead.

F003: `application.access.AccountAccessServiceKeyedOperationTest.rejectsFabricatedOrRevokedSessionContextBeforeProvingKey`
fails in the current working tree. It belongs to STORY-API-010, which is `BLOCKED` behind the open
intervention UI-008, but because the harness commits the whole tree this story's commit carries the
failure into the CI gate.

## Blockers

None.
