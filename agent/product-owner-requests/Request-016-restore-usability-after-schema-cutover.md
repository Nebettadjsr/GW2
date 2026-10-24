# Product Owner Request

## Status

RESOLVED

## Title

Restore a usable application after the versioned-schema cutover

## Requested Change

The application is currently unusable. Since STORY-INFRA-004 introduced the versioned schema chain
and reset the legacy cache, every unscoped account path fails closed with:

> This database is managed by the versioned schema chain, so the unscoped legacy path 'icon-metadata
> item selection without an account scope' is disabled: Flyway is the only schema writer for migrated
> objects, and account data may only be read or written for a verified account GUID. Account access
> through scoped repositories is not available yet, and the previous unscoped cache was dropped by the
> schema reset - every account source is missing until a validated credential drives a fresh
> synchronization.

The gate (`repo.ManagedSchema.rejectUnscopedAccountAccess`) currently refuses 18 call sites, including
global features that only partly read account data:

- `sync/IconSync.java:383`: icon-metadata item selection (mixes global `items`/`recipes` with
  `account_bank`, `account_materials` and `character_items`)
- `sync/tp/relevance/CraftingProfitItemCollector.java:167,182`: Profit price relevance and coverage
- `sync/tp/relevance/DiscoveryItemCollector.java:57`: Discovery price relevance
- `repo.BankRepository`, `CharacterRepository`, `InventoryRepository`, `MaterialStorageRepository`,
  `RecipeRepository`, `AccountLuckRepository`, `AccountSyncStateRepository`, `sync.AccountSync`:
  all account reads and writes

Plan the work so that:

1. Global features that do not need account data work again on a migrated database, at the
   latest when the first scoped story lands. This includes icon metadata, global sync, Trading Post
   price refresh and recipe/item data. Where a selection mixes global and account sources, split it:
   the global half runs unscoped, and the account half runs only for a verified account scope.
2. The account features (bank, materials, characters, Crafting Profit, Discovery, Luck) are usable
   end to end again once a user supplies a validated key, through the scoped path. Make it explicit
   which story delivers the first usable end-to-end account flow, and order the queue so it is reached
   without avoidable detours.
3. Until then, the browser shows a clear "account data unavailable, enter or validate a key" state
   instead of a raw error or a broken page.

## Why / Product Intent

The schema cutover is intended, but it left the product with no usable path at all, including features
that never needed account data. Development continues for days while the application cannot be used
or demonstrated, and local real-database checks such as `web.ItemIconApiRealDbIT` fail for the same
reason (STORY-UI-004 finding F007).

## Constraints

- Keep the fail-closed rule for account data: no account read or write without a verified account scope,
  and no silent fallback to unscoped reads.
- The database stays a disposable cache (UD-016): reset plus fresh synchronization is acceptable, so
  no migration or preservation of old rows is needed.
- Reuse the existing milestone-06 stories where they already cover the work. STORY-APP-015 (scoped
  repositories), STORY-SYNC-006, STORY-API-011 and STORY-WEB-035 are the likely owners. Add or split
  stories only for gaps, such as the global/account split of `IconSync` and the TP relevance collectors.

## Additional Context

STORY-APP-015 is currently blocked (intervention UI-009) after two QA correction rounds, and the
dependent stories wait on it. Its acceptance criterion 7 requires that no retained runnable path
*silently* uses the old schema. Failing closed satisfies that, but does not restore usability.

## Planner Resolution

2026-10-24: Added the post-reset global/account usability criterion to `docs/ROADMAP.md` Phase 6. Created `agent/stories/STORY-SYNC-007-restore-global-operations-after-schema-reset.md` for global-only operation recovery and mixed-source splitting, and `agent/stories/STORY-WEB-036-show-account-data-unavailable-state.md` for the immediate credential-required browser state; requested both backlog additions. Existing `agent/stories/STORY-APP-015-account-scoped-repositories-and-publication.md`, `agent/stories/STORY-SYNC-006-account-scoped-refresh-and-task-ownership.md`, `agent/stories/STORY-API-011-enforce-account-isolation-across-http-workflows.md` and `agent/stories/STORY-WEB-035-memory-only-account-credential-lifecycle.md` cover scoped persistence, refresh, HTTP and final browser integration. The first complete validated-key account flow is delivered when STORY-WEB-035 integrates the preceding scoped contracts. Requested queue order places SYNC-007 and WEB-036 before the remaining account work; no unscoped fallback or legacy-row preservation is authorized.
