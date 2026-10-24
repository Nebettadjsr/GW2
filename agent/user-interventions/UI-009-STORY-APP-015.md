# User Intervention

## Status

OPEN

## Story

STORY-APP-015

## Reason

Application tests still need changes after 2 QA correction rounds.

## Claude Response

# STORY-APP-015 — Scope account repositories and atomic source publication

## Outcome

Implemented. This invocation addressed the two reachable-path defects the evaluator found and left
the rest of the already-delivered scoped persistence, publication and snapshot code as it stands.
Both defects are now verified end to end against a real PostgreSQL database on the path the caller
actually takes, and each verification was proved to fail against the previous behaviour.

The story's own `## Result` carries the full detail; this artifact is the summary.

## What changed in this invocation

1. **A validated empty character roster publishes an empty character generation.**
   `sync.CharacterSync.syncCharactersCraftingAndRecipes(AccountScope)` no longer returns early on an
   empty roster; it publishes through the same `repo.AccountSourcePublication` transaction as every
   other source, so the `CHARACTERS` marker and generation advance atomically with the (empty) roster
   replacement. The previous rationale — "an empty `/v2/characters` is indistinguishable from a lost
   `characters` permission" (F003) — was wrong: a lost permission is an HTTP 403 that
   `api.HttpStatus.requireOkOrPartial` throws on inside `fetchCharacterNames()`, and a non-array body
   is rejected by `parser.CharacterNamesParser`. So a successfully validated empty array means the
   account owns no characters.
   Failed validation still publishes nothing, and one case is now stricter: a character the roster
   named whose detail response cannot be parsed used to be skipped (`continue`), which would publish
   the remainder and delete that character as stale. It now aborts before the transaction opens.

2. **A successful authorized use registers or reuses the verified account before recording
   last-seen activity.** `repo.AccountRegistryRepository.recordAuthorizedUse` is one
   `INSERT … ON CONFLICT (account_id) DO UPDATE SET last_seen_at = GREATEST(…)` statement, so
   registration and the activity it describes are atomic and the already-registered case stays
   monotonic. This closes F001: `application.ProfitInputSession.open(AccountScope)` previously issued
   an `UPDATE` that matched no row for an account that had never published, and ignored the `false`.
   `recordSuccessfulSync` deliberately stays a plain `UPDATE`, because the publication transaction has
   already registered the account in the same transaction.

3. **Documentation.** `docs/CURRENT_ARCHITECTURE.md` §7 *Account-scoped persistence and publication*:
   the **Activity** paragraph's "both activity updates are `UPDATE`s rather than upserts" claim, the
   **Publication** paragraph (empty-roster and unreadable-detail rules) and the **Scoped snapshots**
   paragraph were corrected to the delivered behaviour.

No test file was created, modified or deleted. `src/test/java/repo/AccountScopedSchemaTest.java`
still hashes to `94af691d175abae875cbb9c2272a184cbac685dfba6a2225d52195946bc4ad5c`, the plan's
protected hash.

## Tests run

With `JAVA_HOME=/c/Users/Administrator/.jdks/openjdk-25.0.1`, from the worktree root:

- `./mvnw -o -q compile` and `./mvnw -o test-compile` → `BUILD SUCCESS`.
- QA-required: `./mvnw -o -Dtest=AccountScopedSchemaTest test` →
  `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`. QA's two assertion
  corrections resolve the deviations the previous attempt reported; nothing is outstanding there.
- `./mvnw -o -Dtest='AccountScopedSchemaTest,AccountSyncStateRepositoryTest,CharacterRosterPublicationTest,AccountSyncTest,CharacterSyncTest,AccountLuckRepositoryTest,BankRepositoryTest,MaterialStorageRepositoryTest,RecipeRepositoryTest,InventoryRepositoryOwnedInventoryTest,InventoryRepositoryBoundMaterialTest,ProfitInputPublicationTest,CraftingProfitServiceSessionTest,CachedCraftingProfitServiceTest,AccountRefreshServiceTest,TradingPostCoverageCoordinationTest' -DfailIfNoSpecifiedTests=false test`
  → `Tests run: 84, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`.
- With coverage, 21 classes incl. the five account application services:
  `… test jacoco:report` → `Tests run: 106, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`.

Full regression stays with GitHub Actions.

## End-to-end verification (no QA test covers these paths yet)

Passing suites do not establish either behaviour, because no QA test drives the scoped publication or
snapshot interfaces. Both were therefore verified against a real PostgreSQL database by two
disposable probes outside the repository (`%TEMP%\app015probe`, deleted afterwards; each created its
own uniquely named schema, applied `classpath:db/migration`, and dropped the schema in a `finally`).
Complete output is quoted in the story's `## Result`. Summary:

- **Caller path**, `CharacterSync.syncCharactersCraftingAndRecipes(scope)` driven against a
  classpath-override stub of `api.Gw2ApiClient` (so no GW2 API call): an empty `[]` response published
  a `CHARACTERS` marker at generation 1, registered the account, recorded successful-sync activity and
  left the roster empty; a second run advanced the generation to 2; a non-array body threw
  `Unexpected JSON (characters): not an array` and published no marker and no account identity.
- **Publication and snapshot path**: `ProfitInputSession.open(scope)` for an unregistered account
  registered it and recorded `last_seen_at`, fabricated no `last_successful_sync_at` and created no
  source marker, and an older instant did not move `last_seen_at` backwards; `publishRoster` with an
  empty payload list replaced a populated roster, cascaded its children, advanced the generation and
  marker and advanced successful-sync activity; a payload failing mid-transaction left the last-good
  roster, generation, marker and activity untouched.
- **Controls**: both probes were re-run against compiled copies of the two files with only this
  invocation's behaviour reverted. The caller probe then reported 5 FAILs (no marker, generation 0, no
  registration, no sync activity) and the publication probe 2 FAILs (`the authorized use registered
  the account`, `last_seen_at recorded`). The copies lived outside the repository and were deleted.

## Required tests still missing (QA)

Reported, not written — QA owns test authorship:

- **T1.** The scoped publication contract has no test. `src/test/java/sync/CharacterRosterPublicationTest.java`
  exercises the unscoped `CharacterSync.publishRoster(Connection, List, Instant)` only. A scoped
  equivalent would use `publishRoster(Connection, AccountScope, List<CharPayload>, Instant)` and the
  `account_source_state` generation, and should cover: publication of a **validated empty roster**
  (marker present, generation advanced, roster emptied, children cascaded), rollback of a payload that
  fails mid-transaction, and per-account writer serialization vs. unrelated-account progress.
- **T2.** Activity has no test. Needed: `ProfitInputSession.open(AccountScope)` for an **unregistered**
  account registers it and records `last_seen_at`; a second, earlier instant does not move it
  backwards; a read creates no `account_source_state` row and no `last_successful_sync_at`.
- **T3.** The fetch-and-publish caller path (`syncCharactersCraftingAndRecipes(AccountScope)`) has no
  seam a test can inject an upstream response through — `api.Gw2ApiClient` is a final class with static
  methods and a hard-coded base URL, which is why the probe above had to override it on the classpath.
  Making that injectable is production work no criterion of this story asks for; QA should decide
  whether it wants such a seam before testing that path.
- **T4.** Unchanged from the previous attempt: source independence under another source's failure,
  concurrent same-account writers, and REPEATABLE READ consistency during concurrent publication with
  captured per-source generations.

## Coverage review

JaCoCo over the 21 focused suites above (a subset run, but it includes every suite that touches these
classes). The gaps are the same ones the QA plan anticipated, and they are a test-coverage gap, not a
behaviour gap — T1–T4 above name exactly what is uncovered.

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

No speculative target and no scope exclusion is claimed. `docs/QUALITY_METRICS.md` keeps the 90% line
and branch figure as a warning KPI, not a gate.

## Publication note

Unchanged from the previous attempt and still to be checked on the commit (`tasks/lessons.md`,
"Verify what the commit contains"): the four new `model`/`repo` files
(`model/AccountScope.java`, `repo/AccountRegistryRepository.java`, `repo/AccountSourcePublication.java`,
`repo/AccountSourceStateRepository.java`), the modified `application/*` (9), `repo/*` (7), `sync/*` (3)
and `sync/tp/relevance/*` (2) files, and `docs/CURRENT_ARCHITECTURE.md`. The source edits predate this
invocation's run baseline, so none of them is newly dirty.

The `application/access`, `infra/access`, `web/access`, `web/dto/SessionAccessResponse.java`,
`web/Gw2ApiApplication.java`, `src/main/resources/application.properties` and
`docs/architecture/decisions/ADR-008..010` changes in the tree are **not** this story's —
`agent/runtime/artifacts/CARRIED_PUBLISH_PATHS.json` attributes them to the blocked STORY-API-010.
Left untouched.

## Interface Deviations

None. Every name in the QA plan's `interface_contract` is used as given: the production Flyway
location `classpath:db/migration` and all ten named tables, `sync.CharacterSync.publishRoster`,
`application.ProfitInputSession`, `application.CalculationInputVersions` and the connection-taking
`repo.CharacterRepository` query methods. `recordAuthorizedUse` keeps its name and signature; only its
statement changed.

## Follow-up Findings

F001: `AccountSourcePublication.publish` sets `autoCommit(false)` and never restores it, so a caller
reusing the connection afterwards inherits a non-autocommit connection. Harmless today, because every
production caller opens a connection per publication inside try-with-resources, but it is a trap for a
future caller that batches sources on one connection.

F002: `api.Gw2ApiClient` is a final class of static methods with hard-coded `api.guildwars2.com` URLs,
so no caller of it — `CharacterSync`, `AccountSync`, the Trading Post and icon syncs — can be driven
with a chosen upstream response in-process. Every fetch-and-publish entry point is therefore testable
only through its publication seam, never end to end. Introducing an adapter interface is a small,
well-bounded change that would make the whole sync layer injectable; it is outside this story's scope.

## User Resolution

TODO

## Resolution Notes

TODO
