# User Intervention

## Status

RESOLVED

## Story

STORY-INFRA-004

## Reason

Automatic retries exhausted (2) without the evaluator confirming completion.

## Claude Response

# STORY-INFRA-004 — Introduce versioned account schema and preserve legacy data

## Outcome

Implemented. Production Flyway chain, startup gate, constrained GUID-scoped account schema and the
explicit non-destructive legacy adoption are in place and verified against real isolated PostgreSQL.
Downstream scoped account access is **not** delivered — it is reported as unavailable rather than
guessed, which is what keeps the cutover gated.

**This pass** closes the remaining evaluator finding: an interrupted adoption could be resumed while
the live Luck destination held a *different* value or original fetch time for the proven GUID, keep
that row, and still record the archived source as `ASSOCIATED` and the import as reconciled. It now
cannot.

- **The import never overwrites and never claims what it did not do.** Association reads the live
  `account_luck_total` row before writing anything. Nothing live → it inserts. Exactly the archived
  value *and* the archived fetch time → the idempotent resume accepts its own earlier work and
  associates. Anything else → the live row is left untouched, the source is recorded `CONFLICTED`
  instead of `ASSOCIATED`, and the operator is told both values and asked to resolve which is
  correct.
- **Reconciliation verifies the live data rather than trusting the association's verdict.** Before
  `reconciled_at` may be written, every associated account's live Luck value and original fetch time
  are compared against the archived row, a live Luck row no archived row explains (for instance a
  quarantined GUID) is rejected, and an association with no live row is rejected. Any of these blocks
  the cutover, which rolls the whole attempt back — the live data, the archive and the ledger are left
  exactly as they were, and the startup gate keeps refusing to serve until the adoption is resumed
  successfully.

The earlier pass closed the three gaps before that, where the guarantee had to hold on the *serving*
path and at the *runtime database access boundary* rather than inside the adoption path:

1. **Safe cutover is now enforced by the serving path.** `SchemaGate` refuses to serve any database
   holding a preserved archive whose adoption never completed its provenance association and
   integrity reconciliation — before it even classifies the state, so a `MANAGED` database committed
   mid-cutover is not accepted as current.
2. **Archive inaccessibility is now a role boundary.** Revoking `PUBLIC` and excluding a schema from
   the search path cannot deny the *owner*, and the runtime credential owns the archived tables.
   `repo.Db.open()` therefore `SET ROLE`s every runtime connection to a privilege-less
   `gw2_runtime_<live schema>` role, so a schema-qualified archive read fails with PostgreSQL's own
   `42501`, while the adoption session keeps its credential and reconciles the preserved rows.
3. **The retained standalone `IconSync.main` passes the same gate**, and its item-id selection — which
   reads the legacy unscoped `account_bank`/`account_materials`/`character_items` tables — is
   explicitly refused on an upgraded database instead of failing on a missing relation.

Each of these is covered by a test that fails when the production behaviour is removed; the
mutation runs are listed under *Tests run*.

## What changed

**Dependencies** (`pom.xml`) — `flyway-core` and `flyway-database-postgresql`, versions managed by
the existing `spring-boot-dependencies` BOM (Flyway 12.4.0 for Spring Boot 4.1.1). Open-source
features only: versioned SQL, history, validate, migrate.

**Migrations** (`src/main/resources/db/migration/`)

- `V1__global_reference_and_economy.sql` — the global half, identical to the legacy global schema, so
  it is also the adoption baseline version.
- `V2__account_registry_and_scoped_account_data.sql` — `gw2_account` (uuid PK = verified
  `/v2/account` GUID, nullable `last_seen_at`/`last_successful_sync_at` as `timestamptz`),
  `account_source_state` `(account_id, source)` with `generation > 0` and `completed_at`,
  `account_bank_slot` `(account_id, slot)`, `account_material_stack` `(account_id, item_id)`,
  `account_recipe_unlock` `(account_id, recipe_id)`, `account_luck_total` `(account_id)`,
  `account_character` (`(account_id, character_id)` parent key, `(account_id, name)` unique) and its
  three children with composite foreign keys to that parent. `account_character_item` uses
  `UNIQUE NULLS NOT DISTINCT` over the account, character and five location columns. No credential,
  session or application-user field exists anywhere.

**New code** (`src/main/java/infra/schema/`) — `SchemaGate` (startup validate/migrate with
`baselineOnMigrate=false`, `cleanDisabled=true`, `validateOnMigrate=true`, no automatic repair, and
the three states `MANAGED`/`EMPTY`/`LEGACY_UNMANAGED`), `LegacySchemaFingerprint` (the documented
supported inventory and drift preflight), `LegacySchemaAdoption` (preflight, inventory, archive move,
ledger, normalization, baseline, provenance, reconciliation, resume), `LegacySchemaAdoptionCli` (the
only, operator-invoked entry point), and the two refusal exceptions.

`LegacySchemaAdoption`'s provenance step gained the live-destination rule: `readLiveLuck` reads the
destination before any write, a mismatching live row yields the new `CONFLICTED` ledger outcome
(added to the `provenance_ledger` check constraint) instead of a silent `ON CONFLICT DO NOTHING`, and
`verifyAssociatedLuckMatchesTheArchive` re-checks value, original fetch time and the absence of
unexplained live Luck rows before the import may be marked reconciled.

`repo.RuntimeAccessRole` is the archive boundary: it creates the privilege-less, login-less,
non-inheriting `gw2_runtime_<live schema>` role (created by adoption in the same transaction as the
archive, so "an archive exists" and "runtime connections are restricted" cannot come apart), grants
it data privileges on the live schema only — no `CREATE`, nothing outside that schema — revokes the
archive from it, and performs the `SET ROLE`.

**Wiring** — `web.Gw2ApiApplication.main`, `Gw2App.start(Stage)` and the retained standalone
`sync.IconSync.main` run the gate as their first statement, ahead of every bean, scheduler, task and
repository. `repo.Db.open()`, the one runtime database access boundary, restricts every connection
where a restricted role exists. `repo.ManagedSchema` refuses the unscoped account paths on a managed
database with an actionable diagnostic — `AccountSyncStateRepository.ensure` (every account read and
every account writer passes through a marker) and `IconSync.referencedOrUnknownItemIds` — and turns
`AccountLuckSchema` and `MaterialStorageSchema` into no-ops there. Unmigrated databases behave
exactly as before, including `IconSync --dry-run`.

**Docs** — `docs/CURRENT_ARCHITECTURE.md` gains section 8.1 (the chain, the gate's three states plus
the unreconciled-adoption refusal, the restricted-role archive boundary with its non-superuser
deployment requirement, the supported legacy inventory, the operator runbook with backup/restore,
compatible application version, stopped writers and safe resume, and the explicit statement that
migration support is complete while scoped account access is not).
`docs/CURRENT_STATE_SPEC.md` persistence facts and limitations updated accordingly.
`docs/QUALITY_METRICS.md` deliberately untouched — its provisional snapshot may only be replaced by
clean-commit CI results.

## Why these choices

- **The legacy fingerprint is real evidence, not a design wish.** It was established by a read-only
  catalog inspection of the actual deployed manual schema (columns with underlying types and
  nullability plus every constraint definition) and cross-checked against the SQL `sync.*`/`repo.*`
  issue. That inspection also revealed why legacy inventory slots are ambiguous: the legacy unique
  index spans nullable bag/slot/equipment columns under PostgreSQL's default `NULLS DISTINCT`.
- **New table names for the scoped schema.** Reusing the legacy names would let an old unscoped
  `SELECT` read across every account. With new names the old statement cannot resolve at all, so
  criterion 6 is satisfied structurally rather than by discipline.
- **The gate reports a legacy database instead of failing startup.** Migrating it is a reviewed
  operation with prerequisites; a legacy database is pre-adoption, not "incompatible or failed", and
  TARGET_ARCHITECTURE 7.4.7 puts unresolved association outside schema-startup failure. Nothing is
  written or baselined in that state, so the cutover stays gated and the current installation keeps
  working.
- **Luck is the only association.** Its row stores the verified GUID itself; no other legacy source
  links a published generation to an upstream-verified account. Its unscoped completion marker is not
  imported either, so the proven source is owned but unusable until a fresh synchronization.
- **A role, not a search path, denies the archive.** The configured runtime credential owns the
  moved tables, and an owner keeps access to what it owns however the search path is set and however
  the `PUBLIC` grants are revoked — a schema-qualified read would still succeed. The only boundary
  PostgreSQL enforces against the owner is a different role, and the only place to apply it that no
  caller can skip is `Db.open()`. Residual limitation: a *superuser* session can leave the role it
  was switched to, so the deployment credential must not be a superuser; this is now stated in
  `CURRENT_ARCHITECTURE.md` 8.1 rather than left implicit.
- **The cutover marker lives in the archive, not in schema history.** Flyway's history says which
  versions are applied, which is true the moment the chain commits — in the middle of a cutover. The
  question "is the preserved dataset accounted for" belongs to the import, so `import_run.reconciled_at`
  carries it and is written in the same transaction as the reconciliation it stands for. Startup
  finds an archive by its own structure rather than by a configured name, because the archive schema
  name is an adoption argument.

## Tests run

Real PostgreSQL 17.5, each check in a freshly created randomly named disposable schema that is
dropped afterwards. Verified afterwards by catalog query that the configured database is untouched:
17 public tables as before, no Flyway history in `public`, no leftover test schema and no leftover
`gw2_runtime_%` role (`DisposableSchema.close` now also drops the restricted role it caused, since
roles are cluster-wide rather than schema-scoped).

```
./mvnw -o test jacoco:report -Dtest='infra.schema.*Test,repo.ManagedSchemaLegacyPathsTest,
  repo.AccountLuckRepositoryTest,sync.IconSync*Test,application.InitialSetupServiceTest,
  web.Gw2ApiApplicationBootTest'
  application.InitialSetupServiceTest                Tests run: 5,  Failures: 0, Errors: 0
  infra.schema.AccountSchemaOwnershipConstraintsTest Tests run: 6,  Failures: 0, Errors: 0
  infra.schema.CutoverGateAndArchiveIsolationTest    Tests run: 8,  Failures: 0, Errors: 0
  infra.schema.LegacySchemaAdoptionCliTest           Tests run: 5,  Failures: 0, Errors: 0
  infra.schema.LegacySchemaAdoptionTest              Tests run: 12, Failures: 0, Errors: 0
  infra.schema.MigrationTransactionalityTest         Tests run: 2,  Failures: 0, Errors: 0
  infra.schema.SchemaMigrationChainTest              Tests run: 5,  Failures: 0, Errors: 0
  repo.AccountLuckRepositoryTest                     Tests run: 2,  Failures: 0, Errors: 0
  repo.ManagedSchemaLegacyPathsTest                  Tests run: 3,  Failures: 0, Errors: 0
  sync.IconSyncMetadataTest                          Tests run: 15, Failures: 0, Errors: 0
  web.Gw2ApiApplicationBootTest                      Tests run: 8,  Failures: 0, Errors: 0
  Tests run: 71, Failures: 0, Errors: 0, Skipped: 0 -- BUILD SUCCESS
```

**This pass's regression check** —
`LegacySchemaAdoptionTest.aResumedAdoptionRefusesAConflictingLiveLuckRowAndKeepsTheCutoverBlocked`,
one real-PostgreSQL run through the whole interrupted-then-resumed cutover:

1. Adoption is interrupted by the injected crash in the window after the versioned chain is
   recorded; the rolled-back association leaves no live Luck row.
2. A conflicting live row (`7654321` luck at `2026-03-09T09:00Z`) is committed for the proven GUID
   by an independent session, and the adoption is resumed. It is refused with both values named; the
   live row still reads `7654321|2026-03-09 09:00:00+00` (not overwritten), `provenance_ledger` is
   empty (nothing recorded as associated), `import_run.reconciled_at` is still `NULL`, all four
   archived Luck rows are intact, and `SchemaGate.run` refuses to serve with "never reconciled" — so
   the cutover is blocked, not merely reported.
3. The live value is then corrected but the *fetch time* left wrong: refused again on the timestamp
   alone, still unreconciled.
4. A live Luck row for the quarantined GUID is added: refused as a row the import "did not
   associate", still unreconciled.
5. The live destination is finally made identical to the archive: the same resume completes,
   `LUCK|ASSOCIATED|<proven GUID>` is the only association, the live row holds the archived
   `1234567|2026-02-05 14:00:00+00`, `reconciled_at` is set and startup serves the database — so the
   identical-data retry still works and the rule is a conflict rule, not a blanket refusal.

The pre-existing identical-data retry coverage is untouched:
`aFailureAfterPreservationWritesRollsBackCompletelyAndTheRetrySucceedsOnce` still asserts the no-op
rerun with one `import_run`, one `account_luck_total` and one `gw2_account` row.

Mutation-checked against the final test and reverted afterwards:

| Mutation | Result |
| --- | --- |
| live-destination comparison removed from `importLuck` (back to `ON CONFLICT (account_id) DO NOTHING`) | 1 failure — the reconciliation backstop still blocks the cutover, but with its own message instead of the conflict report, so the check fails |
| `verifyAssociatedLuckMatchesTheArchive` call removed from the final reconciliation | 1 failure — nothing is thrown at all: the conflicting row is accepted and the import marked reconciled, which is exactly the reported defect |

`infra.schema.CutoverGateAndArchiveIsolationTest` is the new class for the three closed gaps:

| Check | Asserts |
| --- | --- |
| `startupRefusesToServeACutoverInterruptedBetweenMigrationAndReconciliation` | An injected crash in the window after the chain is recorded leaves `MANAGED` + `reconciled_at IS NULL` and no registry row; `SchemaGate.run` refuses, names the resume command, deletes nothing; the resumed adoption reuses the derived import ID, reconciles, and startup then serves. |
| `startupRefusesToServeAfterIntegrityReconciliationFailed` | A live account row planted in that same window makes the final reconciliation refuse (`…speculatively`) and roll back the provenance ledger; startup then refuses the committed-chain database; removing the row and resuming restores service. |
| `runtimeDatabaseAccessIsDeniedTheArchiveWhileArchiveAccessStillReconcilesIt` | `Db.open()` reports `current_user = gw2_runtime_<schema>`; six schema-qualified archive reads (`account_bank`, `account_luck`, `character_items`, `import_run`, `import_manifest`, `provenance_ledger`) fail with SQLState `42501`; the same restricted connection still serves the live schema and still recognizes the managed state, so the unscoped refusal keeps its diagnostic; `IconSync --dry-run` is refused end to end; owner access reconciles the archived `account_bank` rows byte-identically against the manifest checksum. |
| `theRetainedIconSyncEntryPointCannotReachDataBeforeTheSchemaIsValidated` | With an edited applied checksum, `IconSync.main --dry-run` throws from the gate and prints no coverage report; with the checksum restored it is refused explicitly as an unscoped path. |
| `theRetainedIconSyncEntryPointRefusesAnUnreconciledAdoption` | The same entry point refuses an unreconciled adoption and reads nothing. |
| `theRetainedIconSyncEntryPointStillRunsAgainstAnUnmigratedLegacyDatabase` | The gate reports the legacy state, the command runs exactly as before, and no history table appears — so "refused" is distinguishable from "broken". |
| `anInstallationWithoutAnArchiveIsNotRestrictedAtAll`, `theRestrictedRoleNameStaysWithinPostgresIdentifierLimits` | No archive means no role, no-op grants and an unchanged runtime credential; the role name truncates to 63 characters stably. |

Each guarantee was mutation-checked by disabling the production behaviour and re-running the class:

| Mutation | Result |
| --- | --- |
| `RuntimeAccessRole.restrict` removed from `Db.open()` **and** the unreconciled-adoption check removed from `SchemaGate.run` | 4 failures — `current_user` is the owner again, and both startup refusals and the `IconSync` refusal stop being raised |
| gate call removed from `IconSync.main` | 3 failures |
| `ManagedSchema.rejectUnscopedAccountAccess` removed from the item-id selection | 1 failure — `FEHLER: Relation account_bank existiert nicht` instead of the actionable diagnostic |

All mutations were reverted and the class re-run green.

Because the earlier pass changed build dependencies (unbounded blast radius, TEST_STRATEGY §20), the
whole default suite was run locally once then — `./mvnw -o test jacoco:report`:
`Tests run: 732, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`. This pass's changes are
bounded (the Luck association and reconciliation inside `LegacySchemaAdoption`, which nothing
outside `infra.schema` calls, plus one test), so the covering suites above were run instead of the
full suite;
CI is the authoritative full-regression gate. Every new check is a `*Test` class in the default
CI-selected suite; none needs a live GW2 API, a display, or the developer's own database content, and
no real-database check is excluded from that suite.

What the checks establish, beyond passing: the complete chain on an empty database and an idempotent
second startup with immutable history; a legacy database reported and left bit-identical; an edited
checksum failing without repair; cross-account isolation, composite-parent rejection and
`NULLS NOT DISTINCT` slot collisions; full adoption reconciled across source, archive, ledger and
live contents with original timestamps and relations; archive unreachable from runtime access;
empty-versus-adopted schema convergence; drift, missing-backup, missing-writer-stop and
missing-confirmation refusals that change nothing; a transactional migration failure rolling back
with earlier versions preserved and a safe retry; failure injection after preservation writes rolling
the whole step back, then a successful retry and a no-op rerun; failed reconciliation blocking
cutover while keeping the archive; and that the compiled migration classes reference no GW2 adapter,
sync writer or HTTP client.

## Coverage

Two measurements, each labelled with its scope rather than merged into one number.

Full default suite (earlier pass, `./mvnw -o test jacoco:report`): `infra.schema` 94.92% line
(560/590), 83.91% branch (193/230); `repo.ManagedSchema`, `repo.AccountSyncStateRepository` and
`repo.MaterialStorageSchema` 100% line and branch; project totals 64.08% → 64.80% line and
57.85% → 58.88% branch.

This pass, over the affected suites only (the `jacoco:report` run quoted under *Tests run*):
`infra.schema` 93.74% line (674/719) and 82.12% branch (248/302); `repo.RuntimeAccessRole` 100% line
(46/46) and 75.00% branch (12/16); `repo.ManagedSchema` 100% line and branch; `repo.Db` 100% line
and 50% branch (2/4). These are from a smaller selection than the full-suite figures above and are
not a project snapshot; `sync.IconSync` is not quoted because this selection drives only part of it.
The Luck-conflict code added in this pass is covered: `readLiveLuck` 7/7 lines, `importLuck` 44/44
lines with 19/20 branches, `verifyAssociatedLuckMatchesTheArchive` 35/40 lines with 23/28 branches.

Remaining gaps:

- `infra.schema` branch coverage is below the 90% aim. The uncovered branches are failure-diagnostic
  paths in `LegacySchemaAdoption` (an archive table present without an `import_run` row, the unlock
  swallow, the normalization default) that would need further production seams to drive; the
  behaviour-bearing refusal, rollback, retry and reconciliation branches are covered. The five
  branches still uncovered in `verifyAssociatedLuckMatchesTheArchive` are its defensive backstops —
  an associated account with no live row, and an associated account with no archived row — which
  `importLuck` cannot produce; they are kept because the point of that method is not to trust
  `importLuck`'s verdict.
- `repo.RuntimeAccessRole`'s four uncovered branches are the `liveSchema == null` defaults, which
  occur only when the gate manages a connection's *default* schema. Every check deliberately runs in
  a named disposable schema so the developer's own schema is never touched, so driving those branches
  would require exactly the setup TEST_STRATEGY §31.2 forbids. The one uncovered line is gone: the
  unused `roleNameFor(Connection)` overload was removed rather than covered by a test for nothing.
- `repo.AccountLuckSchema` remains at its pre-existing 76.2% line coverage, unchanged by this story.

## QA plan

No QA-owned test existed to preserve (`prepared_test_paths` empty, `pre_implementation_verification`
`NOT_RUN`) and no QA expectation was modified, weakened or skipped. The plan's acceptance checks,
invariants and test specifications are implemented as the seven test classes above.

The plan's *"archival isolation through the actual runtime database role/access boundary"* and its
invariant *"archive data is not reachable through runtime account repositories or runtime database
access"* are now satisfied literally, and the earlier pass's deviation from them is withdrawn: a
restricted `gw2_runtime_<live schema>` role is created by adoption, `repo.Db.open()` switches every
runtime connection to it, and the test asserts the schema-qualified read through that boundary. The
QA expectation was right and the earlier reading of it was wrong — the `PUBLIC`-grant and
search-path argument did not deny the archive to the credential that owns it. `CREATE ROLE` is
needed for the adoption path and therefore for these checks; where it is unavailable the adoption
itself cannot run, so this is a prerequisite of the feature rather than a test-only requirement.

One plan expectation remains satisfied differently from a literal reading, and is flagged for the
required independent review rather than silently reinterpreted:

- *"isolated databases"* — the checks use isolated, disposable **schemas** inside the configured
  PostgreSQL server, which is the established pattern for every existing real-database test here and
  satisfies TEST_STRATEGY §31.2's property. Creating a database per check needs `CREATEDB` and
  cannot run inside a transaction. No row or object in the developer's own schema is read or
  written, and no role outlives the run; both verified by catalog query after the run.

## Verification boundaries

- Real adoption of the maintainer's own database was deliberately not performed: it requires stopped
  writers, a backup and an operator decision, and the story keeps cutover gated.
- The `LEGACY_UNMANAGED` branch of `Gw2App.start(Stage)`/`Gw2ApiApplication.main` is covered through
  `SchemaGate` directly rather than by launching either process; the gate call is the first statement
  of both. `IconSync.main` is the one retained entry point driven as a process entry point, because
  it is the one that can be, so the "gate before data access" ordering is asserted on real
  invocations there.
- The restricted role denies the archive to a non-superuser runtime credential. Verification ran
  with a superuser-owned connection, where the denial comes from `SET ROLE` and a superuser session
  could issue `RESET ROLE` to escape it. That is a deployment requirement, now stated in
  `CURRENT_ARCHITECTURE.md` 8.1, not something this story's code can enforce.

## Follow-up Findings

F001: `items.level` is written and read by no production path — `ItemSync` and `IconSync` persist
`name`/`type`/`rarity`/`vendor_value`/`icon_url`/`icon_path` only, and no repository selects it. V1
preserves the column deliberately (dropping it would be destructive drift against the supported
legacy fingerprint), so it is now carried in the versioned schema as permanently empty data.

F002: `AccountSyncStateRepository.lookup` is the one account-marker path that bypasses the
managed-schema guard, because `application.ProfitInputSession` must not turn its `REPEATABLE READ`
snapshot into a writing transaction. On a migrated database that path still fails closed, but with
PostgreSQL's `relation "account_sync_state" does not exist` rather than the actionable diagnostic the
other account paths give.

F003: `repo.MaterialStorageSchema.ensure` ends with an `INSERT … SELECT … FROM account_materials`, so
on a pre-migration database it throws when the legacy `account_materials` table happens to be absent
even though the three tables it creates do not depend on it.

F004: `docs/CURRENT_ARCHITECTURE.md` has no section 5.14 — section 5 has no subsections at all — yet
`sync.IconSync`'s javadoc and `docs/KNOWN_PROBLEMS.md` line 177 both point readers there for the
icon-metadata refresh behavior. The references predate this story and were left untouched; whichever
document now owns that behavior should be named instead.

## User Resolution

UD-016 (Product Owner): the database is only a disposable cache of GW2 API data. Legacy preservation,
archive, provenance, reconciliation and backup are not required, so the IconSync/legacy-gate question
no longer matters: on `LEGACY_UNMANAGED` the startup gate resets the cache (drop legacy cached tables,
apply the full chain) for every entry point including `IconSync`.

## Resolution Notes

Re-scope STORY-INFRA-004 per its `Scope Change (UD-016)` section, remove the archive/adoption/Luck-conflict
machinery already written, then re-run it with a fresh retry budget. Set Status to RESOLVED to requeue.