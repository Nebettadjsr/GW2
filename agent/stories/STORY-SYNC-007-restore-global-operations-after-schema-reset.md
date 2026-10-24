## Story ID

STORY-SYNC-007

## Title

Restore global operations on the versioned database

## Status

UNFINISHED

## Milestone

milestone-06

## Goal

Keep global item, recipe, icon metadata and Trading Post workflows usable on the versioned database without requiring an account credential, while keeping account-derived inputs behind verified account scope.

## Authoritative Source Documents / Sections

- `docs/ROADMAP.md` Phase 6 objective and global/account exit criteria.
- `docs/TARGET_ARCHITECTURE.md` §§7, 7.4, 8.1, shared versus account-scoped data and reset behavior.
- `agent/stories/STORY-INFRA-004-versioned-account-schema-and-legacy-preservation.md`, schema reset and fail-closed gate.
- `agent/stories/STORY-APP-015-account-scoped-repositories-and-publication.md`, scoped repositories and remaining unscoped runnable paths.
- `agent/stories/STORY-UI-004-remove-legacy-javafx-application.md` F007.
- `docs/TEST_STRATEGY.md`, integration and regression verification.
- `docs/QUALITY_METRICS.md`, affected coverage review.

## Context

The reset leaves no account source populated. The Product Owner reports that unscoped account guards also stop global workflows, including mixed icon metadata and Profit/Discovery price relevance selection. Global work must remain available while account access is introduced. This story is the first queued restoration step and does not weaken the account fail-closed gate.

## Acceptance Criteria

1. On a migrated empty-account database, global item/recipe metadata, global synchronization, icon metadata selection and Trading Post price refresh execute without a verified account or an unscoped-account-access error. They use shared data only.
2. Split mixed icon metadata and Profit/Discovery price relevance inputs into a global selection that operates independently and an account-derived selection that requires a verified account scope. Without that scope, account rows do not enter selections, refresh demand or calculations; no default account is inferred.
3. With verified scope, the account-derived selection reads only that account's bank, materials and character items. Two accounts with overlapping item IDs retain independent account-derived selections while sharing global metadata and quotes.
4. Preserve the shared ten-minute, required-ID-only Trading Post cache and existing global refresh coordination. Do not introduce a per-user global refresh or full-catalog timer.
5. Remove the reported unscoped bank-read failure from the local item-icon real-database path by routing any account input through scoped access. A missing scope fails safely for the account portion while global icon metadata remains available.

## Required Tests

- QA provides isolated migrated-PostgreSQL integration checks for global-only operations with no account row or key, and two-account checks for mixed selections and foreign-row exclusion.
- Regress the reported icon real-database failure with the affected `ItemIconApiRealDbIT` path, and verify the global half still works without account data. This local real-database check must be run explicitly because CI does not run it.
- Check shared quote reuse, required-ID-only refresh and ten-minute freshness across two account demands; review affected coverage against `docs/QUALITY_METRICS.md`. QA determines concrete cases and test placement.

## Constraints

- Keep `ManagedSchema` account-data access fail closed; never restore unscoped reads or infer account ownership from reset data.
- No GW2 key storage, new domain rule, per-account database, full-catalog timer or unrelated cleanup.
- Coordinate with the scoped interfaces in STORY-APP-015 without modifying QA-owned tests or fixtures in the coding attempt.

## Dependencies

STORY-INFRA-004.

## Definition of Done

- Global workflows operate on the versioned database, account inputs require verified scope, and QA records the targeted regression and coverage evidence. The CI gate is green (verified by the pipeline after acceptance).

## Result

Implemented. Each workflow that read shared and account data in one statement is now two selections
over one shared definition: the global half takes no scope and is refused by nothing, the
account-derived half takes an explicit `model.AccountScope`. The fail-closed gate is unchanged — the
unscoped forms still refuse on a migrated database.

| Change | Effect |
| --- | --- |
| `sync.IconSync.globalReferencedOrUnknownItemIds(Connection)` | Items still missing metadata, the material catalog, recipe outputs and recipe ingredients. No account table, no scope, no rejection. |
| `sync.IconSync.accountReferencedItemIds(Connection, AccountScope)` | That account's bank, material-storage and character-inventory references only; `requireManagedAccountSchema`. |
| `IconSync.metadataRefreshSelection` (private), used by the refresh and `--dry-run` | Global half on a managed database; the unchanged unscoped union on a pre-cutover one. |
| `CraftingProfitItemCollector.collectGlobal(Connection)`, `DiscoveryItemCollector.collectGlobal(Connection)` | "Craftable"/"not yet unlocked" become the shared crafting graph itself, which is what they mean with no account in play. The three SQL variants now differ only in the recipe source; the item derivation, tradeability join, quote cache and ten-minute filter are one template. |
| `TpSync.syncTpPricesForProfit()` / `ForDiscovery()` | Select globally on a managed database, by the stored recipe knowledge on an unmigrated one. |
| `BankRepository.loadBankSlots()` (no-arg) | Returns no rows on a managed database instead of refusing: the absent scope contributes no account row. `loadBankSlots(Connection)` still refuses, so no unscoped *query* was restored. |

AC1 — on the migrated schema the icon-metadata repair selected and wrote all seven global ids
(`MetadataRepair[selected=7, rowsWritten=7, rowsCreated=2, …]`) and both price-refresh entry points
completed through `Db.open()`, with no unscoped-account-access error anywhere.
AC2 — the three global selections are byte-identical with two accounts registered and with every
account row deleted; `accountReferencedItemIds` for an unregistered GUID and for an empty registry is
`[]`, so nothing is inferred. AC3 — scoped selections exclude the other account's rows throughout:
icon references `[20, 22, 24]` for A vs `[21, 23, 25]` for B, Profit `[10, 11]` vs `[30, 31]`,
Discovery `[30, 31, 40, 41]` vs `[10, 11, 40, 41]`. AC4 — a fresh shared quote for item 10 drops it
from A's demand (`[11]`) and from the global demand while B, which does not require it, is unchanged;
the same quote aged past ten minutes returns. `TradingPostCoverageCoordinationTest` and
`TradingPostRefreshCoordinatorTest` still pass and no timer or per-user pipeline was added.
AC5 — the real-database icon path now reads three seeded bank rows through
`BankContentsService.getBankContents(AccountScope)` and exercises both route checks against them.

Verified commands, with output quoted in `agent/runtime/artifacts/CLAUDE_RESULT.md`:

- `./mvnw -o clean test-compile` → `Compiling 222 … Compiling 121 source files`, `BUILD SUCCESS`.
- `./mvnw -o -Dtest=VersionedGlobalOperationsTest test` → `Tests run: 4, Failures: 0, Errors: 0,
  Skipped: 0`, `BUILD SUCCESS`. **All four cases pass**, each on its own disposable PostgreSQL schema
  migrated through both checked-in versions and dropped afterwards. The three fixture defects the
  earlier attempt reported have been corrected by QA; production was not changed for them.
- `./mvnw -o -Dtest=ItemIconApiRealDbIT -DfailIfNoSpecifiedTests=false test` (local real database,
  not run by CI) → `3 bank slots, 3 with an accepted retained icon source; sampling 3` and
  `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`. The seeded rows reach both checks: the scoped
  read's `assertEquals(3, slots.size())` held, all three sampled URLs were derived from bank rows,
  and the foreign-key case's `assertNotNull(itemId, …)` held so its 404 / `no-store` / no-upstream-URL
  assertions ran against bank item 19721 instead of returning early. The sampled URLs answer `503`
  because the fixture's icon source is synthetic — one of the three statuses the test accepts, with
  its failure-branch headers asserted.
- `./mvnw -o -Dtest=IconSyncMetadataTest,CraftingProfitItemCollectorTest,TradingPostCoverageCoordinationTest,BankRepositoryTest,BankContentsServiceTest,TradingPostPriceRefreshServiceTest,TradingPostRefreshCoordinatorTest,CachedCraftingProfitServiceTest test`
  → `Tests run: 61, Failures: 0, Errors: 0, Skipped: 0` (earlier invocation, unchanged code).
- A disposable probe outside the repository drove the production entry points on a freshly migrated
  schema and produced the AC1/AC2/AC4 figures above. Its full output is in the result artifact; it is
  now corroboration rather than the primary evidence.
- Both QA files were run unmodified: their SHA-256 digests equal the plan's `protected_test_hashes`.
  No disposable schema leaked — `information_schema.schemata LIKE 'test\_%'` returns `0` afterwards.

## Blockers

None. The earlier blocker — `VersionedGlobalOperationsTest` could not pass as written — was a QA
correction, and QA has made it: the class now passes all four cases unmodified.

## Follow-up Findings

F001: On a reset database the first global Profit/Discovery price refresh now requires every
Trading-Post-tradeable item reachable from any recipe, because the shared crafting graph is the only
account-free relevance definition available. The ten-minute filter and required-ID-only batching bound
every later run, but the cold-cache run is far larger than the account-derived one it replaces; a
later story may want a narrower global relevance rule (for example only items a browser route can
display).

F002: `docs/CURRENT_STATE_SPEC.md` line 57 still states that the scoped account repositories and
publication "are not implemented yet", while `docs/CURRENT_ARCHITECTURE.md` §8 states they now exist.
Both are owned descriptions of the same fact and one of them is stale from STORY-APP-015's partial
landing.

F003: STORY-APP-015's foundation is still uncommitted in the working tree, and only the two files the
published scope could not compile without (`src/main/java/model/AccountScope.java`,
`src/main/java/repo/ManagedSchema.java`) are carried by this story's commit. Its remaining untracked
files — the other scoped repositories, `application/access/`, `infra/access/`, `web/access/` and
three ADRs — will land with whichever later story happens to edit them, so no commit is a deliberate
record of that work.
