## Story ID

STORY-APP-015

## Title

Scope account repositories and atomic source publication

## Status

BLOCKED

## Milestone

milestone-06

## Goal

Require explicit trusted account scope for persistence and calculation-input snapshots, with atomic per-source publication and monotonic account activity.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md, Phase 6: persistence isolation, stable account reuse, activity and account-agnostic domain logic.
- docs/TARGET_ARCHITECTURE.md sections 7.1-7.4 and 8.1, scoped persistence and publication.
- agent/user-decisions/UD-016-database-is-a-disposable-cache.md.
- docs/CURRENT_ARCHITECTURE.md sections 5 and 7, current account coordination and snapshot behavior.
- docs/DOMAIN_SPEC.md sections 6, 9 and 11.1, account material pool, recipe knowledge and binding.
- docs/TEST_STRATEGY.md sections 8-9, 26.1, 31.2 and 36.
- docs/QUALITY_METRICS.md, Test coverage KPI and Generation and review.
- agent/stories/STORY-INFRA-004-versioned-account-schema-and-legacy-preservation.md.

## Context

The account schema must be consumed without default-account fallbacks. Current documented source transactions and REPEATABLE READ input snapshots must retain their consistency guarantees while becoming account-scoped. HTTP admission and upstream credential handling are separate work.

## Acceptance Criteria

1. Account registry registration/upsert is idempotent for the verified stable GUID, including concurrent callers. Replacement keys do not form new persistence identities. Require explicit trusted scope at account repository/application persistence ports; no configured/last-used account overload or global mutable current account selects records.
2. Scope every account read, join, aggregate, existence check, upsert, replacement, stale-row removal and source marker by account, covering bank, materials, account recipes, Luck, roster, disciplines, character recipes and inventory/equipment. Reject a nested character belonging to another account. Global metadata, graph and TP repositories remain shared.
3. Fetch/validation remains outside database transactions. Provide source publication that commits data, stale-row removals, completion timestamp, account/source-local generation and successful-sync activity atomically. Characters publish the roster and all character payloads together. Successful empty/unchanged sources have valid markers and advance their generation; failed validation/commit leaves last-good rows and markers untouched. Independent successful sources may publish despite another source's failure.
4. Serialize publication writers per verified account without serializing unrelated accounts. Fresh synchronization populates the reset account cache; no legacy archive promotion or ownership inference participates in publication.
5. Preserve one REPEATABLE READ calculation-input transaction across the requesting account's rows/markers and shared inputs. Snapshot metadata captures that account and the generations actually read. Retire singleton material-sync authority; after reset, missing source markers remain missing until a successful fresh publication. Ordinary reads never mark data fresh.
6. Implement activity persistence per section 7.1: UTC creation, monotonic last_seen_at for successful authorized use and last_successful_sync_at for successful publication. Invalid proof/rejected scope does not advance activity; missing historical activity stays unknown. Preserve the policy that cleanup requires measured impact and a later explicit threshold decision; add no cleaner.
7. Preserve existing recipe knowledge, account material-pool and binding semantics by supplying scoped data to existing domain rules. Domain types/calculations gain no sessions, keys, JDBC or authentication dependencies. Adapt affected callers so no retained runnable path silently uses the old unscoped schema.

## Required Tests

- Real isolated PostgreSQL tests against production migrations with two accounts sharing item/recipe identifiers and overlapping character/slot inputs. Assert complete read/write/delete/aggregate isolation and foreign-child rejection across all account source families.
- Verify atomic rollback on mid-publication failure, successful empty/unchanged generations, source independence, concurrent same-account writers, unrelated-account progress and monotonic activity.
- Verify REPEATABLE READ input consistency during concurrent publication, captured account/source generations, and shared global quote/reference reuse across both accounts.
- Application tests use independently specified fixtures to prove scoped owned-material/recipe inputs and unchanged domain expectations. Add regression tests for any confirmed defect found during implementation.
- Use default CI-selected tests for deterministic checks; name and run any real-database check excluded from the gate explicitly. No live key/network, existing local data or display requirement.

## Constraints

- Implement the established persistence contract; do not create a new identity or recipe policy.
- This story does not complete session HTTP enforcement, multi-user refresh admission, task observation or browser credential handling.
- Do not change agent/runtime/, local_bridge/ or CI workflow configuration.

## Dependencies

STORY-INFRA-004

## Definition of Done

- Scoped persistence and publication satisfy the criteria and targeted tests; affected callers compile and maintain explicit scope.
- Review relevant package/branch coverage reports under docs/QUALITY_METRICS.md and report unresolved gaps without speculative targets or scope exclusions.
- Current architecture documentation describes delivered persistence, activity and snapshot behavior, with remaining HTTP/sync work clearly distinguished.
- The CI gate is green (verified by the pipeline after acceptance).

## Result

Implemented. Two invocations contributed: the first delivered the scoped persistence, publication and
snapshot code plus the architecture documentation; this one fixed the two reachable-path defects the
evaluator found, corrected the documentation they made stale, and verified both behaviours end to end.

### Delivered (first invocation, unchanged)

`model.AccountScope` is the dependency-free GUID value every account-owned operation requires; it has
no no-argument factory, no "current"/"configured"/"last used" accessor and no process-wide holder, so
no path can acquire scope by default. `repo.AccountRegistryRepository` owns idempotent registration
(`ON CONFLICT DO NOTHING`, so concurrent callers race on the primary key rather than on
check-then-insert) and the monotonic `GREATEST` activity updates. `repo.AccountSourceStateRepository`
owns the `(account_id, source)` markers and generations, with `read`/`readAll` as pure `SELECT`s so
capturing freshness never creates it. `repo.AccountSourcePublication` is the one publication
transaction: a per-account `pg_advisory_xact_lock`, then payload, scoped stale-row removal, generation
advance and successful-sync activity committed together or rolled back together.

Scope-taking reads on `BankRepository`, `MaterialStorageRepository`, `CharacterRepository`,
`InventoryRepository`, `RecipeRepository`, `AccountLuckRepository`, `CraftingProfitItemCollector` and
`DiscoveryItemCollector`; scoped publication in `AccountSync` (bank, materials, recipes, Luck) and
`CharacterSync.publishRoster` (roster plus every character payload as one source);
per-source-independent `AccountRefreshService.refreshSources(AccountScope, Set)`; and
`ProfitInputSession.open(AccountScope)` with `CalculationInputVersions` capturing the account and the
generation actually read per source, both folded into `calculationId()`.
`ManagedSchema.requireManagedAccountSchema` gates every scoped entry point, mirroring
`rejectUnscopedAccountAccess` on the unscoped ones, so neither half of the cutover can run against the
other's schema. Every retained unscoped account path fails closed on a migrated database with an
actionable diagnostic rather than silently reading the old schema (criterion 7).

### Fixed in this invocation

**A validated empty character roster now publishes an empty character generation (criterion 3).**
`CharacterSync.syncCharactersCraftingAndRecipes(AccountScope)` no longer returns early when the roster
is empty; it goes through the same `AccountSourcePublication` transaction as every other source, so the
`CHARACTERS` marker and generation advance atomically with the (empty) roster replacement and its
cascaded children. The previous rationale, recorded as F003, was wrong: an empty `/v2/characters` array
is *not* indistinguishable from a key that lost the `characters` permission. A lost permission is an
HTTP 403, and `api.HttpStatus.requireOkOrPartial` throws on anything but 200/206 inside
`fetchCharacterNames()`; a body that is not an array is thrown out by `parser.CharacterNamesParser`. So
a successfully validated empty array means this account owns no characters, and its character source is
legitimately empty and current.

Failed validation still publishes nothing, and one case became stricter rather than looser: a character
the roster named whose detail response `CharacterParser.parseInfo` cannot read used to be skipped with
`continue`, which published the remaining characters and deleted the unreadable one as stale. It now
aborts the whole publication before the transaction opens, leaving the last-good roster, generation and
marker untouched. `AccountRefreshService.refreshSources(AccountScope, Set)` already treats that throw
as this source's own failure, so the other sources still publish.

**A successful authorized use now registers or reuses the verified account before recording last-seen
activity (criterion 6).** `AccountRegistryRepository.recordAuthorizedUse` is a single
`INSERT INTO account_registry (account_id, last_seen_at) ... ON CONFLICT (account_id) DO UPDATE SET
last_seen_at = GREATEST(account_registry.last_seen_at, EXCLUDED.last_seen_at)`, so registration and the
activity it describes are atomic, and the already-registered case stays monotonic. This closes F001:
`ProfitInputSession.open(AccountScope)` previously issued an `UPDATE` that matched no row for an account
which had never published a source — every account after a cache reset — and ignored the `false`.
`recordSuccessfulSync` deliberately keeps the plain `UPDATE` form, because `AccountSourcePublication`
has already registered the account inside the same transaction, so an upsert there could only mask a
missing registration. Neither statement touches `display_name`, `created_at` or the other's column, so a
visit still fabricates no synchronization and rewrites no history.

**Documentation.** `docs/CURRENT_ARCHITECTURE.md` §7 was corrected where this change made it stale: the
**Activity** paragraph (which claimed both activity updates are `UPDATE`s rather than upserts), the
**Publication** paragraph (the empty-roster and unreadable-detail rules) and the **Scoped snapshots**
paragraph (registration on first authorized use).

### Tests run

All commands from the worktree root with `JAVA_HOME=/c/Users/Administrator/.jdks/openjdk-25.0.1`.

1. `./mvnw -o -q compile` and `./mvnw -o test-compile` — `BUILD SUCCESS`, so every adapted caller
   compiles.
2. QA-required: `./mvnw -o -Dtest=AccountScopedSchemaTest test`

```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.009 s -- in repo.AccountScopedSchemaTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

   QA's two assertion corrections resolve the deviations the previous attempt reported, so the
   *QA expectation deviations* section is gone — nothing is outstanding there. The file was not
   modified by this invocation either: it still hashes to
   `94af691d175abae875cbb9c2272a184cbac685dfba6a2225d52195946bc4ad5c`, the plan's protected hash.
3. Repository, sync and application suites —
   `./mvnw -o -Dtest='AccountScopedSchemaTest,AccountSyncStateRepositoryTest,CharacterRosterPublicationTest,AccountSyncTest,CharacterSyncTest,AccountLuckRepositoryTest,BankRepositoryTest,MaterialStorageRepositoryTest,RecipeRepositoryTest,InventoryRepositoryOwnedInventoryTest,InventoryRepositoryBoundMaterialTest,ProfitInputPublicationTest,CraftingProfitServiceSessionTest,CachedCraftingProfitServiceTest,AccountRefreshServiceTest,TradingPostCoverageCoordinationTest' -DfailIfNoSpecifiedTests=false test`
   → `Tests run: 84, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
4. The same plus the five account application services, with coverage
   (`... test jacoco:report`, 21 classes) → `Tests run: 106, Failures: 0, Errors: 0, Skipped: 0`,
   `BUILD SUCCESS`.

The full regression remains GitHub Actions' job.

### End-to-end verification of the two fixed behaviours

No QA test drives the scoped publication or snapshot interfaces (see *Required tests still missing*),
so the passing suites above do not establish either behaviour. Both were verified against the real
local PostgreSQL 17.5 database by two disposable probes that live **outside** the repository
(`%TEMP%\app015probe`, deleted afterwards — `git status` lists no probe file). Each created its own
uniquely named schema, applied the production `classpath:db/migration` chain, pointed every
`Db.open()` at it through `Db.TEST_SCHEMA_PROPERTY`, and dropped the schema in a `finally`.

**Caller path.** `CharacterSync.syncCharactersCraftingAndRecipes(scope)` — the entry point
`AccountRefreshService` calls — driven against a classpath-override stand-in for `api.Gw2ApiClient`
that answers `/v2/characters` with a chosen body and rejects every other call, so no GW2 API request
was made:

```
  stub answered https://api.guildwars2.com/v2/characters with []
ok   the empty roster published a marker
ok   generation advanced to 1
ok   the account was registered by the publication
ok   successful-sync activity recorded
ok   the roster is empty
  stub answered https://api.guildwars2.com/v2/characters with []
ok   an unchanged empty source advances again
  stub answered https://api.guildwars2.com/v2/characters with {"text":"invalid key"}
  rejected: java.lang.IllegalArgumentException: Unexpected JSON (characters): not an array
ok   the failed validation threw
ok   no marker was published
ok   no account identity was created by a failed fetch
CALLER PROBE OK
```

**Publication and snapshot path.** `ProfitInputSession.open(scope)`, `publishRoster(con, scope, ...)` and
`AccountRegistryRepository.recordAuthorizedUse` called directly:

```
ok   account starts unregistered
ok   scoped snapshot carries its account
ok   scoped snapshot is REPEATABLE READ
ok   the authorized use registered the account
ok   last_seen_at recorded
  last_seen_at = 2026-10-21T16:46:23.025008Z
ok   no successful sync fabricated
ok   no source marker created by a read
ok   last_seen_at is monotonic
ok   one character published
ok   generation 1
ok   crafting child published
  empty publication -> Published[source=CHARACTERS, generation=2, completedAt=2026-10-21T10:00:00Z]
ok   empty roster replaced the previous one
ok   children cascaded away
ok   generation advanced to 2
ok   marker records the empty publication
ok   successful-sync activity advanced
ok   reported generation
  rejected: Batch entry 0 INSERT INTO character_crafting
ok   the failing publication threw
ok   last-good roster intact
ok   generation unchanged
ok   marker unchanged
ok   successful-sync activity unchanged
PROBE OK
```

**Controls.** Both probes were re-run against compiled copies of `CharacterSync` and
`AccountRegistryRepository` with only this invocation's behaviour reverted (the early
`if (names.isEmpty()) return;` restored, `recordAuthorizedUse` back to the plain `UPDATE`), placed
ahead of `target/classes`. The shipped files were never edited for this; the copies lived in the same
temporary directory and were deleted with it.

```
--- CallerProbe against the old early-return ---
  stub answered https://api.guildwars2.com/v2/characters with []
FAIL the empty roster published a marker (expected 1, got 0)
FAIL generation advanced to 1 (expected 1, got 0)
FAIL the account was registered by the publication (expected 1, got 0)
FAIL successful-sync activity recorded (expected true, got false)
ok   the roster is empty
  stub answered https://api.guildwars2.com/v2/characters with []
FAIL an unchanged empty source advances again (expected 2, got 0)
...
CALLER PROBE FAILED (5)
--- Probe against the old UPDATE-only activity ---
ok   account starts unregistered
ok   scoped snapshot carries its account
ok   scoped snapshot is REPEATABLE READ
FAIL the authorized use registered the account (expected 1, got 0)
FAIL last_seen_at recorded (expected true, got false)
  last_seen_at = null
ok   no successful sync fabricated
ok   no source marker created by a read
```

The second control then aborted with an NPE on the `null` `last_seen_at`, which is the same finding;
its `finally` still dropped the schema.

### Required tests still missing (QA)

Reported, not written — QA owns all test authorship, and the deficiency list's request to add
PostgreSQL-backed tests is handed over here rather than acted on:

- **T1.** The scoped publication contract has no test. `src/test/java/sync/CharacterRosterPublicationTest.java`
  exercises the unscoped `CharacterSync.publishRoster(Connection, List, Instant)` only, which is the
  evaluator's own QA observation. A scoped equivalent would call
  `publishRoster(Connection, AccountScope, List<CharPayload>, Instant)` and read
  `account_source_state`, and should cover publication of a **validated empty roster** (marker present,
  generation advanced, roster emptied, children cascaded), rollback of a payload that fails
  mid-transaction, and per-account writer serialization against unrelated-account progress.
- **T2.** Activity has no test. Needed: `ProfitInputSession.open(AccountScope)` for an **unregistered**
  account registers it and records `last_seen_at`; a later call with an earlier instant does not move it
  backwards; the read creates no `account_source_state` row and no `last_successful_sync_at`.
- **T3.** The fetch-and-publish caller path has no injectable upstream seam: `api.Gw2ApiClient` is a
  final class of static methods with hard-coded URLs, which is why the probe above had to override it on
  the classpath. Creating such a seam is production work no criterion of this story asks for; QA should
  decide whether it wants one before testing that path (see F002).
- **T4.** Unchanged from the previous attempt: source independence under another source's failure,
  concurrent same-account writers, and REPEATABLE READ consistency during concurrent publication with
  captured per-source generations.

### Coverage review

JaCoCo over the 21 focused suites above (`jacoco:report`; a subset run, so it understates classes other
suites reach, but it includes every suite that touches these classes). The gap is unchanged and is a
test-coverage gap rather than a behaviour gap — T1–T4 name exactly what is uncovered, and the behaviour
itself is evidenced by the probes above.

| Class | Line | Branch |
|---|---:|---:|
| `model.AccountScope` | 0.0% | 0.0% |
| `repo.AccountRegistryRepository` | 3.0% | 0.0% |
| `repo.AccountSourceStateRepository` | 3.1% | 0.0% |
| `repo.AccountSourcePublication` | 8.8% | 0.0% |
| `sync.tp.relevance.DiscoveryItemCollector` | 0.0% | 0.0% |
| `sync.AccountSync` | 25.2% | 28.1% |
| `repo.CharacterRepository` | 31.2% | 25.0% |
| `sync.CharacterSync` | 42.0% | 27.4% |
| `application.ProfitInputSession` | 64.1% | 100.0% |
| `application.CalculationInputVersions` | 93.2% | 100.0% |

No speculative target and no scope exclusion is claimed; `docs/QUALITY_METRICS.md` keeps the 90% line
and branch figure as a warning KPI rather than a gate.

### Publication note

The source changes were made by the earlier invocations, so they were already dirty when this run's
baseline was taken. Per `tasks/lessons.md` ("Verify what the commit contains"), the commit must carry
exactly these paths:

- New: `src/main/java/model/AccountScope.java`, `src/main/java/repo/AccountRegistryRepository.java`,
  `src/main/java/repo/AccountSourcePublication.java`,
  `src/main/java/repo/AccountSourceStateRepository.java`
- Modified: `application/{AccountLuckService,AccountRefreshService,BankContentsService,CachedCraftingProfitService,CalculationInputVersions,CharacterSelectionService,CraftingProfitService,MaterialStorageService,ProfitInputSession}.java`,
  `repo/{AccountLuckRepository,BankRepository,CharacterRepository,InventoryRepository,ManagedSchema,MaterialStorageRepository,RecipeRepository}.java`,
  `sync/{AccountRefreshGateway,AccountSync,CharacterSync}.java`,
  `sync/tp/relevance/{CraftingProfitItemCollector,DiscoveryItemCollector}.java`,
  `docs/CURRENT_ARCHITECTURE.md`

The `application/access`, `infra/access`, `web/access`, `web/dto/SessionAccessResponse.java`,
`web/Gw2ApiApplication.java`, `src/main/resources/application.properties` and
`docs/architecture/decisions/ADR-008..010` changes in the tree are **not** this story's:
`agent/runtime/artifacts/CARRIED_PUBLISH_PATHS.json` attributes all of them to the blocked
STORY-API-010. They were left untouched.

## Follow-up Findings

F001: `AccountSourcePublication.publish` sets `autoCommit(false)` and never restores it, so a caller
reusing the connection afterwards inherits a non-autocommit connection. Harmless today, because every
production caller opens a connection per publication inside try-with-resources, but it is a trap for a
future caller that batches sources on one connection.

F002: `api.Gw2ApiClient` is a final class of static methods with hard-coded `api.guildwars2.com` URLs,
so no caller of it — `CharacterSync`, `AccountSync`, the Trading Post and icon syncs — can be driven
with a chosen upstream response in-process. Every fetch-and-publish entry point is therefore testable
only through its publication seam, never end to end; this invocation had to override the class on the
classpath in a disposable probe to verify the empty-roster path at all. Introducing an adapter
interface is a small, well-bounded change that would make the whole sync layer injectable, and it is
outside this story's scope.

## Blockers

Blocked on user intervention (see agent/user-interventions/UI-009-STORY-APP-015.md).
