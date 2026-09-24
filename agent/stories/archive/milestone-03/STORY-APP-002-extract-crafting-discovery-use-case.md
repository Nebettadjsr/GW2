## Story ID

STORY-APP-002

## Title

Extract the crafting-discovery application use case

## Status

DONE

## Milestone

milestone-03

## Goal

Move crafting-discovery orchestration into a named application service and migrate its JavaFX callers while preserving individual-character discovery behavior.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md §7, Phase 3 objective, application-service exit criterion, and crafting-flow/controller migration high-level stories.
- docs/TARGET_ARCHITECTURE.md §8 (Application Layer), §25 (Application Tests).
- docs/CURRENT_ARCHITECTURE.md §5.1–§5.2 (crafting flows), §6 (UI relationships).
- Supplied agent/stories/BACKLOG.md, milestone-02 STORY-DOM-019 result (account-wide recipe knowledge correction).

## Context

Discovery's documented flow uses individual character selectors, a missing-discoverable-recipes lookup, a maximum-rating filter, binding-aware inventory and the single-character planner. It shares graph and lazy recipe-tree machinery with Profit. The completed recipe-knowledge correction must survive this extraction.

## Acceptance Criteria

1. A named crafting-discovery application service owns candidate loading and domain invocation. Its orchestration has no JavaFX dependency and keeps calculations in the domain layer.
2. CraftingDiscoveryController and its view obtain discovery results and lazy recipe-tree results through the application boundary rather than loading repositories or invoking the planner/tree builder themselves. Presentation formatting remains in the presentation layer.
3. Preserve Discovery's individual-character selectors, discipline/rating eligibility, maximum-level filter, binding-aware inventory, settings and blocked-result presentation. Do not replace its calculation with Profit's coordinated-character scope.
4. Preserve the existing account-wide recipe-knowledge policy at the candidate-loading boundary: a recipe known by another character must not become an undiscovered candidate merely because a different character is selected.
5. Preserve selection and refresh behavior, sorting/search, result lookup and error reporting. Reuse applicable application boundaries from STORY-APP-001 without duplicating domain logic.
6. Update docs/CURRENT_ARCHITECTURE.md's affected discovery flow and relationship descriptions and record actual verification and limitations in this story's Result.

## Required Tests

- Application orchestration tests using fake or in-memory adapters for candidate filtering/handoff, selected-character inventory, rating/settings propagation, blocked results, lazy lookup and failure propagation.
- Targeted integration regression through the application entry point proving account-wide recipe knowledge excludes an already-known recipe for a different selected character while retaining eligibility filtering.
- Run existing relevant Discovery real-view blocked-row and refresh-preservation regressions. Report unavailable prerequisites and verification limitations explicitly.

## Constraints

- Behavior-preserving Phase 3 extraction; no new discovery rules or selectors.
- Keep shared domain policy authoritative and avoid duplicating recipe-knowledge logic in the service.
- Limit changes to this use case and necessary shared application seams.

## Dependencies

STORY-APP-001.

## Definition of Done

Discovery uses the application service, acceptance criteria have evidence, targeted verification is recorded, and the architecture description matches the implemented flow.

## Result

Added `application.CraftingDiscoveryService` (existing `application` package, `TARGET_ARCHITECTURE.md`
§8, same pattern as `STORY-APP-001`'s `CraftingProfitService`) and moved into it, verbatim in logic,
everything `CraftingDiscoveryController.reload(...)` previously did beyond presentation formatting:
`CraftingGraphCache.load()`, `RecipeRepository.loadMissingDiscoverableRecipeIdsForCharacter(...)`,
the missing-id/`recipe.minRating <= maxLevel` filtering, the selected-character binding-aware
`InventoryRepository.loadOwnedInventoryForCharacter(...)`/unfiltered `loadOwnedInventory()`
fallback, `TpPriceRepository.loadTpQuotes(...)`/`ItemRepository.loadItems(...)`, the
`CraftingPlanner.evaluateAll(...)` (single-character, not coordinated) invocation, and the lazy
per-recipe `RecipeTreeBuilder`/`PlannerContext` tree build previously inlined in
`getResultByRecipeId(...)` (`buildTreeForRecipeId`). The service has no JavaFX import and calls
`craft.*` rather than reimplementing any calculation.

`CraftingDiscoveryController` now holds one `CraftingDiscoveryService` instance and:
- `reload(choice, settings, selectedCharacterName)` calls `discoveryService.reload(...)` and passes
  its returned `DiscoveryData` (visible/all recipes, results-by-recipe-id, items, tp) into the
  unchanged `prepareRows(...)` presentation boundary.
- `getResultByRecipeId(recipeId)`, `itemName(itemId)` and `itemSellUnit(itemId, listingSell)`
  delegate directly to the service.
- Kept only presentation-layer state (`lastAllowedRecipeIds`, recomputed from the service's
  returned `visibleRecipes` — identical to how `CraftingProfitController` derives its own copy) and
  the unchanged `prepareRows`/`summarizeMissing`/`buildSearchBlob` formatting methods — no
  repository or planner/tree call remains in the controller.

One deliberate deviation from `STORY-APP-001`'s exact split: in Profit, the small `items`/`tp`
lookup caches backing `itemName`/`itemSellUnit` were kept in the controller, recomputed from every
`reload()`'s return value. Discovery's `reload(...)` has a pre-existing early return when there are
no missing discoverable recipes for the chosen character+discipline (`missingIds.isEmpty()`), which
skips the inventory/price/item loads entirely and — in the original controller — left the previous
`lastItems`/`lastTp`/`lastAllRecipes`/`lastSettings`/`lastResultsByRecipeId` caches untouched rather
than clearing them. Reproducing that exact staleness behavior from outside the service (i.e. from a
`DiscoveryData` record alone) would have required the controller to detect "was this an early
return" and conditionally skip updating its own cache copies, duplicating bookkeeping the service
already has to do. Instead, `itemName`/`itemSellUnit` were also moved into
`CraftingDiscoveryService` (alongside `getResultByRecipeId`, which already had to live there for
the same reason), backed by the service's own `lastItems`/`lastTp` fields that are - like
`lastAllRecipes`/`lastSettings`/`lastAllowedRecipeIds`/`lastResultsByRecipeId` - simply left
unassigned on the early-return path, preserving the original stickiness exactly without duplicated
staleness logic. `CraftingDiscoveryView` calls these through the controller exactly as before, so
this is invisible to the view/presentation boundary. Covered by
`itemNameAndSellUnitReflectTheMostRecentSuccessfulReload` in the new test suite below.

**Acceptance criteria evidence:**
1. `application.CraftingDiscoveryService` is a plain class (no `javafx.*` import) that owns all
   data loading and domain invocation for the flow; verified by reading its imports and by the
   dependency-direction update in `docs/CURRENT_ARCHITECTURE.md` §4.
2. `CraftingDiscoveryController`'s imports were reduced to `craft.*` (types only), `repo.DiscChoice`,
   `repo.ItemRepository` — no `repo.RecipeRepository`/`InventoryRepository`/`TpPriceRepository`/
   `CraftingGraphCache`, no `craft.CraftingPlanner`/`RecipeTreeBuilder`/`PlannerContext`.
   `CraftingDiscoveryView` is unchanged and continues to call only
   `controller.reload(...)`/`getResultByRecipeId(...)`/`itemName(...)`/`itemSellUnit(...)`, now
   backed by the service.
3. Discovery's individual-character `DiscChoice` (`CHAR_DISCIPLINE`-only) selector, the separate
   Character selector feeding binding-aware inventory, the `recipe.minRating <= maxLevel` filter,
   `CraftingSettings`, and blocked-result presentation (`CraftingResultPresentation`, unchanged in
   the controller) were all moved unedited; the service calls `CraftingPlanner.evaluateAll(...)`
   (single-character path), never `evaluateAllCoordinated(...)` — Profit's coordinated scope is not
   used here.
4. `RecipeRepository.loadMissingDiscoverableRecipeIdsForCharacter(charName, discipline)` (the
   account-wide-knowledge-aware query fixed by `STORY-DOM-019`) is called unedited inside the
   service with the same arguments as before; no recipe-knowledge decision is reimplemented in the
   service — it stays entirely inside `RecipeRepository`/`RecipeKnowledgePolicy`.
5. Selection/refresh behavior, sorting/search, and error reporting are unchanged: `reload(...)`'s
   signature, thrown exceptions (`SQLException` propagates unchanged, verified by
   `repositoryFailurePropagatesFromReload`), and `prepareRows`'s presentation output are unedited.
   No new domain types, calculations, or persistence mapping were added.
6. `docs/CURRENT_ARCHITECTURE.md` updated: §2 (package list, `application` entry now lists both
   services), §3 (`application` package responsibility row), §4 (dependency diagram and a new "key
   observed fact" describing the Discovery controller/service split, including the early-return
   staleness note), §5.2 (flow diagram now shows
   `CraftingDiscoveryController -> application.CraftingDiscoveryService -> repo.*/craft.*`), §6 (UI
   relationship table's Discovery row now shows an application boundary, matching Profit's row).

**Tests run:**
- New application-layer orchestration suite (`src/test/java/application/CraftingDiscoveryServiceTest.java`,
  9 tests, `TARGET_ARCHITECTURE.md` §25): fake/in-memory subclass adapters for every repository plus
  `CraftingGraphCache` (no PostgreSQL involved), and a recording subclass of the real
  `CraftingPlanner` that captures its inputs before delegating to the real implementation. Covers:
  missing-id/`minRating` filtering routing through `loadMissingDiscoverableRecipeIdsForCharacter`;
  the "no missing recipes" short-circuit skipping the inventory/TP/item loads and the planner call
  entirely; the selected-character binding-aware sellable/bound split reaching the planner; the
  `selectedCharacterName == null` fallback to unfiltered `loadOwnedInventory()`; inventory load
  being skipped when `useOwnMats=false`; a `PRICE_UNAVAILABLE`-blocked result being returned (not
  dropped) for a required unpriced purchase; `getResultByRecipeId(...)` building the resolution
  tree once and caching it (identical object on a second call), and a later `reload()` whose graph
  no longer contains a given recipe id making that id's cached result unreachable; `itemName`/
  `itemSellUnit` reflecting the most recent successful reload and surviving a subsequent
  short-circuited reload untouched; and a repository `SQLException` propagating out of `reload(...)`
  unchanged. Ran via `./mvnw -q -o test -Dtest=application.CraftingDiscoveryServiceTest` — 9/9 pass.
- Full suite: `./mvnw -o test` — 103 tests, 0 failures/errors (includes the new suite above, against
  the real local PostgreSQL instance configured via the project's `.env` for the DB-backed tests,
  per existing test conventions).
- Targeted domain/application-boundary regression, run explicitly to confirm this extraction:
  `CraftingBlockedRowsTest` (calls `CraftingDiscoveryController.prepareRows(...)` directly — still
  passes unchanged, proving the presentation boundary/blocked-result surfacing is unaffected).
- Targeted real-view (TestFX) regressions for the migrated callers, run explicitly:
  `uiverify.CraftingDiscoveryViewBlockedRowIT` (a required unpriced purchase stays visible with the
  `PRICE_UNAVAILABLE` reason through the new
  `View -> Controller -> application.CraftingDiscoveryService -> repo.*` call path — the test's own
  transient `tp_prices` "table does not exist yet" stack trace printed during the fixture's schema
  setup race is pre-existing behavior unrelated to this extraction, and the test still passes) and
  `uiverify.CraftingDiscoveryViewRefreshPreservationIT` (manual Refresh preserves Character
  selection/sort/search/checkbox state and a post-refresh Character change still reaches the
  recalculation) — both pass.

**Remaining uncertainty:** none identified against the acceptance criteria. The one deliberate
design deviation from `STORY-APP-001`'s exact controller/service split (moving `itemName`/
`itemSellUnit` into the service rather than keeping duplicate lookup caches in the controller) is
explained above and is behavior-preserving, evidenced by its own test.

## Blockers

None.
