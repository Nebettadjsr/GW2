## Story ID

STORY-INFRA-004

## Title

Introduce versioned account schema and preserve legacy data

## Status

TODO

## Scope Change (UD-016)

**Authoritative; overrides every conflicting line below, including the title.** The database is a
disposable cache of GW2 API data (`agent/user-decisions/UD-016-database-is-a-disposable-cache.md`,
`TARGET_ARCHITECTURE.md` sections 7 and 7.4). Deliver only: Flyway versioned schema, the startup gate
on every entry point, the GUID-scoped account tables with their constraints, transactional source
publication, and activity/freshness fields. **Not required, and to be removed from the implementation:**
legacy schema fingerprinting and adoption, restricted archive schema and runtime-role isolation,
provenance/import ledger, reconciliation, Luck conflict handling, backup prerequisite, and
ownership inference for old rows. A legacy or drifted schema is reset (dropped and recreated) by the
gate and re-synchronized from the API. Tests prove the empty-schema result, the reset, and scoped
constraints; they never prove that old rows survive. Treat the preservation wording below as superseded.

## Milestone

milestone-06

## Goal

Establish the production PostgreSQL migration chain and constrained account schema without guessing legacy ownership or losing existing data.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md, Phase 6: shared database, stable account scope, non-destructive migration.
- docs/TARGET_ARCHITECTURE.md sections 7.1-7.4; established AR-010 decision.
- docs/CURRENT_ARCHITECTURE.md sections 7-8, current tenancy and schema lifecycle.
- docs/DOMAIN_SPEC.md sections 6, 9 and 11.1, preservation of recipe knowledge and inventory semantics.
- docs/TEST_STRATEGY.md sections 9, 26.1, 31.2 and 36.
- docs/QUALITY_METRICS.md, Test coverage KPI and Generation and review.

## Context

The architecture selects Flyway and explicitly separates schema history from ownership evidence. Current documentation describes manual schema creation and unscoped legacy rows. This story establishes storage and cutover safety; application publication and scoped repositories follow separately.

## Acceptance Criteria

1. Integrate the Flyway Java engine and PostgreSQL module using compatible managed dependencies. Initialization validates/migrates before repository, scheduler or task access. Retained entry points use the same gate or are disabled against the upgraded database. Flyway becomes the sole schema writer for migrated objects; automatic baseline, destructive clean and automatic checksum repair remain disabled.
2. Implement the versioned production schema for the global/account classification in section 7.2, including the GUID registry, account-owned composite uniqueness/foreign keys, character child ownership, explicit inventory slot/null semantics, account/source generations and completion times, and nullable historical activity with UTC timestamps. Store no credentials or sessions in these tables.
3. Document the supported actual legacy schema fingerprints and optional variants from implementation evidence. Adoption is an explicit preflight/baseline operation. Unknown drift, invalid schema or failed preservation blocks cutover with actionable diagnostics and no deletion. Empty and supported adopted databases converge on the same live schema.
4. Preserve the legacy account dataset, original relations/timestamps, invalid or ambiguous records, and stable import manifests in a restricted archive and provenance ledger. Global data remains shared and intact. Runtime repositories/search paths cannot expose archived account data. Require stopped old writers and a restorable backup before legacy adoption; do not operate on a developer's normal database during verification.
5. Follow section 7.4's provenance rules: preserve valid identity-aware Luck ownership, quarantine malformed/conflicting records, and never infer ownership of other sources from a configured key, Luck GUID, names, nonempty tables or first login. Record unresolved association explicitly. No migration makes upstream calls or fabricates activity/completion history.
6. Migrations use transactional PostgreSQL operations. Failed migration and retry preserve data/history consistently; applied versions are immutable. Reconcile live/archive contents and constraints before serving the new runtime. Provide operator instructions for backup/restore with a compatible application version and resuming migration safely. Unscoped runtime paths cannot run against the upgraded account schema.

## Required Tests

- Real isolated PostgreSQL tests using the production migration chain for an empty database and each supported legacy fingerprint, with content/relationship reconciliation of live, archive and ledger records.
- Verify ownership constraints across two GUIDs, character-child rejection, duplicate slot/null semantics, global-data preservation and archive inaccessibility through the runtime database access boundary.
- Verify unsupported drift, failed migration rollback, retry, checksum mismatch and explicit adoption without automatic baseline or guessed ownership. Use deterministic failure injection where necessary.
- Put reproducible migration tests in the default CI-selected test suite where practical; explicitly name and execute any additional real-database check excluded from that suite. No live GW2 API or normal local database dependency.

## Constraints

- Follow TARGET_ARCHITECTURE sections 7.2-7.4 rather than designing another migration or identity scheme.
- No automatic deletion, cleanup threshold, distributed coordination, application authentication redesign or domain calculation changes.
- This story does not implement full scoped repository access, source synchronization or browser access. Keep cutover gated until retained runtime paths are compatible; do not leave legacy writers active on the new schema.
- Do not change agent/runtime/, local_bridge/ or CI workflow configuration.

## Dependencies

None.

## Definition of Done

- Production migration/adoption paths, targeted tests and operator instructions satisfy the criteria, with supported variants and any unrecoverable ownership association clearly reported.
- Review affected backend coverage reports against docs/QUALITY_METRICS.md; report measured evidence and remaining verification/coverage gaps without inventing a baseline or changing measurement scope.
- Update current architecture/runtime documentation for delivered behavior; distinguish unavailable downstream account access from completed migration support.
- The CI gate is green (verified by the pipeline after acceptance).

## Result

Delivered. The production Flyway chain, the startup gate, the constrained account schema and the
explicit non-destructive legacy adoption are implemented and verified against real isolated
PostgreSQL. Downstream scoped account access is deliberately *not* delivered and is reported as
unavailable rather than guessed.

### 1. Flyway gate and schema-writer ownership

`flyway-core` and `flyway-database-postgresql` are added with versions managed by the existing
`spring-boot-dependencies` BOM (Flyway 12.4.0 for Spring Boot 4.1.1), so the engine and its
PostgreSQL module cannot drift from each other or from the Spring Boot line, and PostgreSQL support
is pinned nowhere else. Only versioned SQL, history, validate and migrate are used.

`infra.schema.SchemaGate.runStartupGate()` is invoked as the first statement of
`web.Gw2ApiApplication.main`, of `Gw2App.start(Stage)` and of the retained standalone
`sync.IconSync.main` — before any Spring bean, scheduler, background task or repository exists, so
nothing can reach the database ahead of it. The shared
configuration keeps `baselineOnMigrate=false`, `cleanDisabled=true`, `validateOnMigrate=true`,
`outOfOrder=false`, `mixed=false`, `group=false`; there is no automatic checksum repair. The gate
distinguishes `MANAGED` (validate, apply pending, validate again), `EMPTY` (apply the complete
chain) and `LEGACY_UNMANAGED` (write nothing, record no baseline, report the explicit adoption
command and keep the legacy runtime working), and ahead of all three it refuses to serve a database
whose legacy adoption never finished (see 6 below). The JavaFX test seams `start(Stage, …)`
deliberately do not gate — they substitute controlled services and touch no real database.

`repo.ManagedSchema` makes Flyway the sole schema writer for migrated objects and disables the
unscoped account paths on a managed database: `AccountSyncStateRepository.ensure` is refused with an
actionable diagnostic, so is `IconSync.referencedOrUnknownItemIds`, whose item-id selection reads the
legacy `account_bank`/`account_materials`/`character_items` tables and is therefore itself an
unscoped runtime path — the explicit refusal replaces a bare `relation … does not exist`.
`AccountLuckSchema`/`MaterialStorageSchema` become no-ops because their objects are Flyway-owned or
retired. On an unmigrated database all of these behave exactly as before, so `IconSync --dry-run`
still runs there.

### 2. Versioned account schema

`V1__global_reference_and_economy.sql` is the global half, byte-for-byte the legacy global schema, so
it doubles as the adoption baseline version.
`V2__account_registry_and_scoped_account_data.sql` adds the registry and the scoped account tables:
`gw2_account` (uuid primary key equal to the verified `/v2/account` GUID, optional display name,
`created_at` plus nullable `last_seen_at`/`last_successful_sync_at` as `timestamptz`),
`account_source_state` (`(account_id, source)`, `generation > 0`, `completed_at`),
`account_bank_slot` `(account_id, slot)`, `account_material_stack` `(account_id, item_id)`,
`account_recipe_unlock` `(account_id, recipe_id)`, `account_luck_total` `(account_id)`,
`account_character` (`(account_id, character_id)` parent key, `(account_id, name)` unique) and its
three children, each carrying the account GUID and reaching the parent through a composite foreign
key. `account_character_item` uses `UNIQUE NULLS NOT DISTINCT` over
`(account_id, character_id, location, bag_index, slot_index, equipment_slot)`, stating the slot/NULL
semantics explicitly instead of letting nullable columns decide them. New table names are used on
purpose, so an old unscoped statement cannot run at all instead of reading across accounts. No
credential, credential hash, session or application-user field exists, and a test asserts no such
column or table name can appear.

### 3. Supported legacy fingerprints and convergence

`infra.schema.LegacySchemaFingerprint` documents the supported inventory, established from a
read-only catalog inspection of the actual deployed manual schema (columns with underlying types and
nullability, plus every constraint definition per table) and cross-checked against the SQL `sync.*`
and `repo.*` issue. Twelve required tables must match exactly; five objects are known optional
variants, exactly the three `db/manual/*.sql` scripts' additions (`material_categories`,
`material_category_items`, `account_materials_sync`, `account_luck`, `account_sync_state`). Anything
else is drift, reported item by item, with nothing written, moved or deleted. Adoption is only
available through `infra.schema.LegacySchemaAdoptionCli`; an empty or already-managed database is
refused rather than baselined. Absent *global* optional objects are created by an explicit
normalization step driven by the detected variant, never by a broad `IF NOT EXISTS`, so the live
schema is exactly V1 before the baseline is recorded. An empty and an adopted database are asserted
to reach an identical live schema shape.

### 4. Preservation, archive isolation and cutover prerequisites

Adoption inventories every present legacy account object — row count, UTC-rendered content checksum,
original `fetched_at` range, invalid/orphaned rows and the inventory rows whose legacy representation
cannot distinguish duplicate slots — then moves those tables into a restricted archive schema with
their original rows, relations, constraints, timestamps and owned sequences, beside an
`import_run`/`import_manifest`/`provenance_ledger` ledger. Global tables and data stay in place.
Nothing is deleted or purged anywhere in this path.

Archive inaccessibility is enforced by a PostgreSQL role, because the search path and the `PUBLIC`
grants cannot deliver it: the configured runtime credential *owns* the archived tables, and an owner
keeps access to what it owns, so a schema-qualified read would still succeed. `repo.RuntimeAccessRole`
therefore creates a privilege-less, login-less `gw2_runtime_<live schema>` role in the same
transaction that creates the archive, grants it data privileges on the live schema only (no
`CREATE`), revokes the archive from it, and `repo.Db.open()` — the one runtime database access
boundary — issues `SET ROLE` to it on every runtime connection. A runtime connection's
schema-qualified `SELECT … FROM <archive>.account_bank` then fails with PostgreSQL's own
`42501 insufficient_privilege`, while the adoption session keeps its own credential and reconciles
the preserved rows; adoption verifies that exact denial before it allows the cutover to finish. An
installation that never held legacy data has no archive, no role and unchanged runtime behavior.
Residual limitation, now documented in `CURRENT_ARCHITECTURE.md` 8.1: a *superuser* runtime
credential can leave the role it was switched to, so the deployment credential must not be a
superuser. A blank backup reference, a missing stopped-writers attestation or a
missing `--confirm` is refused before any write, and an exclusive advisory lock excludes a second
adoption or concurrent account publication.

### 5. Provenance

Only the identity-aware Luck source is associated: a valid stored GUID keeps that account, its
consumed value and its original fetch time. Malformed identities and GUIDs claimed by more than one
row are quarantined and stay archived.

Live data is never overwritten and the import never claims an association it did not make. The
association step reads the live `account_luck_total` row before writing: nothing live → insert;
exactly the archived value *and* the archived fetch time → the idempotent resume accepts its own
earlier work; anything else → the live row is left untouched, the source is recorded `CONFLICTED`
with both values named instead of `ASSOCIATED`, and the cutover stays blocked until an operator
resolves it. The final reconciliation does not trust that verdict: before `reconciled_at` may be
written it compares every associated account's live value and original fetch time against the
archived row, rejects a live Luck row no archived row explains (a quarantined GUID, for instance)
and rejects an association with no live row. Any of these rolls the whole attempt back, leaving the
live data, archive and ledger untouched, and the startup gate keeps refusing to serve until a resume
succeeds. No source-completion marker is imported, so even the proven
Luck source stays unusable as a completed calculation source until a fresh synchronization. Bank,
material, account-recipe, character and the two legacy marker objects are recorded `UNRESOLVED` with
the reason stated. Registry activity columns stay `NULL`. A test asserts the compiled `infra.schema`
classes reference no GW2 API adapter, sync writer or HTTP client, so no migration can make an
upstream call or read a deployment key.

### 6. Transactionality, retry and operator recovery

Each migration is one PostgreSQL transaction: an injected failing migration rolls back its own DDL,
data and history while V1/V2 stay recorded, and a retry resumes from that history with the data
committed in between intact. An injected failure after preservation writes rolls the whole step back
and leaves the legacy database bit-identical. The import ID is derived from the source inventory
rather than generated, so a rerun resumes committed preservation instead of importing twice and a
rerun of a finished adoption is a no-op. Applied versions are immutable: an edited checksum fails
startup and is left unrepaired. Live/archive/ledger reconciliation runs before serving and blocks
cutover on any mismatch — including a live account row no source can prove ownership of — while
preserving the archive and ledger.

Safe cutover is enforced on the *serving* path, not only inside adoption. Flyway commits the
versioned chain in the middle of a cutover, so a crash or a reconciliation failure after that point
would otherwise leave a managed database that ordinary startup accepts. `import_run.reconciled_at`
is written only in the transaction that also passes provenance association and the final integrity
reconciliation, and `SchemaGate.unreconciledAdoptions` makes every gated entry point refuse to serve
a live schema that has a preserved archive with that marker still `NULL` — before the state is even
classified, so a `MANAGED` database is not accepted as current. The refusal names the resume command,
deletes nothing, and stops being raised once the resumed adoption reconciles. The operator runbook
(backup, compatible application version, stopped writers, resume) is in
`docs/CURRENT_ARCHITECTURE.md` section 8.1.

### Tests run

All against real PostgreSQL 17.5 in freshly created, randomly named disposable schemas that are
dropped afterwards; the configured database's own `public` schema was neither read nor written
(verified afterwards by catalog query: 17 public tables unchanged, no Flyway history in `public`, no
leftover test schema and no leftover `gw2_runtime_%` role — `DisposableSchema.close` now drops the
restricted role it caused, since roles are cluster-wide rather than schema-scoped).

```
./mvnw -o test jacoco:report -Dtest='infra.schema.*Test,repo.ManagedSchemaLegacyPathsTest,
  repo.AccountLuckRepositoryTest,sync.IconSync*Test,application.InitialSetupServiceTest,
  web.Gw2ApiApplicationBootTest'
  application.InitialSetupServiceTest              Tests run: 5,  Failures: 0, Errors: 0
  infra.schema.AccountSchemaOwnershipConstraintsTest Tests run: 6,  Failures: 0, Errors: 0
  infra.schema.CutoverGateAndArchiveIsolationTest  Tests run: 8,  Failures: 0, Errors: 0
  infra.schema.LegacySchemaAdoptionCliTest         Tests run: 5,  Failures: 0, Errors: 0
  infra.schema.LegacySchemaAdoptionTest            Tests run: 12, Failures: 0, Errors: 0
  infra.schema.MigrationTransactionalityTest       Tests run: 2,  Failures: 0, Errors: 0
  infra.schema.SchemaMigrationChainTest            Tests run: 5,  Failures: 0, Errors: 0
  repo.AccountLuckRepositoryTest                   Tests run: 2,  Failures: 0, Errors: 0
  repo.ManagedSchemaLegacyPathsTest                Tests run: 3,  Failures: 0, Errors: 0
  sync.IconSyncMetadataTest                        Tests run: 15, Failures: 0, Errors: 0
  web.Gw2ApiApplicationBootTest                    Tests run: 8,  Failures: 0, Errors: 0
  Tests run: 71, Failures: 0, Errors: 0, Skipped: 0 -- BUILD SUCCESS
```

`LegacySchemaAdoptionTest.aResumedAdoptionRefusesAConflictingLiveLuckRowAndKeepsTheCutoverBlocked`
is the regression check for the live-destination rule: the adoption is interrupted after the chain
is recorded, a conflicting live Luck value is committed for the proven GUID by an independent
session, and the resume is refused with both values named — the live row is untouched, the ledger
holds no association, `reconciled_at` stays `NULL`, the four archived rows are intact and
`SchemaGate.run` still refuses to serve. A fetch time that differs on its own is refused the same
way, as is a live Luck row for the quarantined GUID; once the live destination matches the archive
exactly, the same resume completes, associates only the proven GUID, reconciles and startup serves.
The identical-data retry coverage in
`aFailureAfterPreservationWritesRollsBackCompletelyAndTheRetrySucceedsOnce` is unchanged. Removing
the comparison from the association or the verification from the reconciliation each makes this
check fail; both mutations were run and reverted.

Because the earlier pass changed build dependencies, the whole default suite was also run locally
once then (`./mvnw -o test jacoco:report`): `Tests run: 732, Failures: 0, Errors: 0, Skipped: 0` —
`BUILD SUCCESS`. Every new check is a `*Test` class in the default CI-selected suite; none needs a
live GW2 API, a display or the developer's own database content. No real-database check is excluded
from that suite.

`infra.schema.CutoverGateAndArchiveIsolationTest` is the new class covering the three guarantees that
only hold where the serving path and the runtime access boundary enforce them:

| Check | Asserts |
| --- | --- |
| `startupRefusesToServeACutoverInterruptedBetweenMigrationAndReconciliation` | An injected crash in the window after the chain is recorded leaves `MANAGED` + `reconciled_at IS NULL`; `SchemaGate.run` refuses with the resume command; the archive survives; the resumed adoption reconciles and startup then serves. |
| `startupRefusesToServeAfterIntegrityReconciliationFailed` | A live account row planted in that same window makes the final reconciliation refuse; startup then refuses the committed-chain database; removing the row and resuming restores service. |
| `runtimeDatabaseAccessIsDeniedTheArchiveWhileArchiveAccessStillReconcilesIt` | `Db.open()` reports `current_user = gw2_runtime_<schema>`; six schema-qualified archive reads fail with `42501`; the same connection still serves the live schema; owner access reconciles the archived `account_bank` rows against the manifest checksum. |
| `theRetainedIconSyncEntryPointCannotReachDataBeforeTheSchemaIsValidated` | With an edited applied checksum, `IconSync.main --dry-run` throws from the gate and prints no coverage report; with the checksum restored it is refused explicitly as an unscoped path. |
| `theRetainedIconSyncEntryPointRefusesAnUnreconciledAdoption` | The same entry point refuses an unreconciled adoption and reads nothing. |
| `theRetainedIconSyncEntryPointStillRunsAgainstAnUnmigratedLegacyDatabase` | The gate reports the legacy state, the command runs exactly as before, and no history table appears. |
| `anInstallationWithoutAnArchiveIsNotRestrictedAtAll` + `theRestrictedRoleNameStaysWithinPostgresIdentifierLimits` | No archive means no role and the runtime credential is unchanged; the role name is truncated to 63 characters stably. |

Each guarantee was mutation-checked by disabling the production behaviour and re-running the class:
removing `RuntimeAccessRole.restrict` from `Db.open()` and the unreconciled-adoption check from
`SchemaGate.run` → 4 failures; removing the gate from `IconSync.main` → 3 failures; removing
`ManagedSchema.rejectUnscopedAccountAccess` from the item-id selection → 1 failure (`FEHLER: Relation
account_bank existiert nicht` instead of the diagnostic). All mutations were reverted.

### Coverage

Measured with the `jacoco:report` run quoted above: `infra.schema` 93.74% line (674/719) and 82.12%
branch (248/302); `repo.RuntimeAccessRole` 100% line (46/46) and 75.00% branch (12/16);
`repo.ManagedSchema` 100% line and branch; `repo.Db` 100% line, 50% branch (2/4). The Luck-conflict
code is covered (`readLiveLuck` 7/7 lines, `importLuck` 44/44 lines and 19/20 branches,
`verifyAssociatedLuckMatchesTheArchive` 35/40 lines and 23/28 branches).
This is a targeted-suite measurement,
not a project snapshot — the earlier full-suite run measured `infra.schema` at 94.92%/83.91% and
project totals 64.80%/58.88%; the figures above are from a smaller selection and are reported as
such. `sync.IconSync` is not quoted here for the same reason: this selection drives only part of it.
The provisional snapshot in `docs/QUALITY_METRICS.md` was deliberately not edited: it may only be
replaced by clean-commit CI results.

Remaining gaps: `infra.schema` branch coverage is below the 90% aim. The uncovered branches are
failure-diagnostic paths inside `LegacySchemaAdoption` (an archive table present without an
`import_run` row, the unlock swallow, the normalization default) that would need further production
seams to drive; the behaviour-bearing refusal, rollback, retry and reconciliation branches are
covered. The five uncovered branches in `verifyAssociatedLuckMatchesTheArchive` are its defensive
backstops (an associated account with no live row, or with no archived row), which `importLuck`
cannot produce and which exist precisely so the reconciliation does not depend on it.
`repo.RuntimeAccessRole`'s four uncovered branches are the `liveSchema == null` defaults,
which only occur when the gate manages a connection's *default* schema — every test deliberately
runs in a named disposable schema so that the developer's own schema is never touched, so these
branches are untestable without that prohibited setup. `repo.AccountLuckSchema` stays at its
pre-existing 76.2% line coverage — its uncovered branches are the older incompatible-column checks,
unchanged by this story.

## Follow-up Findings

F001: `items.level` is written and read by no production path — `ItemSync` and `IconSync` persist
`name`/`type`/`rarity`/`vendor_value`/`icon_url`/`icon_path` only, and no repository selects it. V1
preserves the column deliberately (dropping it would be destructive drift against the supported
legacy fingerprint), so it is now carried in the versioned schema as permanently empty data.

F002: `AccountSyncStateRepository.lookup` is the one account-marker path that bypasses the managed
-schema guard, because `application.ProfitInputSession` must not turn its `REPEATABLE READ` snapshot
into a writing transaction. On a migrated database that path still fails closed, but with
PostgreSQL's `relation "account_sync_state" does not exist` rather than the actionable diagnostic the
other account paths give.

F003: `repo.MaterialStorageSchema.ensure` ends with an `INSERT … SELECT … FROM account_materials`, so
on a pre-migration database it throws when the legacy `account_materials` table happens to be absent
even though the three tables it creates do not depend on it.

F004: `docs/CURRENT_ARCHITECTURE.md` has no section 5.14 — section 5 has no subsections at all — yet
`sync.IconSync`'s javadoc and `docs/KNOWN_PROBLEMS.md` line 177 both point readers there for the
icon-metadata refresh behavior. The references predate this story and were left untouched; whichever
document now owns that behavior should be named instead.

## Blockers

None.
