## Story ID

STORY-APP-001

## Title

Extract the crafting-profit application use case

## Status

DONE

## Milestone

milestone-03

## Goal

Move crafting-profit orchestration from the JavaFX controller into a named application service, preserving the coordinated crafting calculation and displayed behavior.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md §7, Phase 3 objective, application-service exit criterion, and crafting-flow/controller migration high-level stories.
- docs/TARGET_ARCHITECTURE.md §8 (Application Layer), §25 (Application Tests).
- docs/CURRENT_ARCHITECTURE.md §5.1 (Crafting Profit flow), §6 (UI relationships).

## Context

The documented flow has CraftingProfitController loading recipes, graph data, character profiles, inventory, quotes and items, invoking the coordinated planner, and providing lazy recipe-tree results. Phase 3 requires a use-case boundary around this orchestration. The supplied backlog and direct story directory contain no existing Phase 3 extraction story.

## Acceptance Criteria

1. A named crafting-profit application service owns data loading and domain invocation for the documented profit flow. Its orchestration has no JavaFX dependency and does not reimplement domain calculations.
2. CraftingProfitController and its view route calculation and lazy recipe-tree requests through the application boundary; presentation formatting remains in the presentation layer. Repository loading and planner/tree execution for this flow no longer occur in those UI classes.
3. Preserve ALL, DISCIPLINE_ONLY and CHAR_DISCIPLINE scope semantics, coordinated character eligibility, sellable/account-bound/per-character-bound inventory handling, settings, allowed recipes and blocked results. Preserve lazy result lookup by recipe ID without cross-request stale results.
4. Preserve the existing user-visible selection, refresh, sorting/search and error behavior while migrating callers. Use the existing domain types and persistence mapping rather than adding duplicate models or calculations.
5. Update docs/CURRENT_ARCHITECTURE.md's affected profit flow and relationship descriptions to reflect the implemented boundary; record actual verification and any limitations in this story's Result.

## Required Tests

- Application orchestration tests with fake or in-memory adapters covering scope-to-roster/data selection, inventory and quote handoff, returned blocked results, lazy result lookup, and failure propagation.
- Run existing targeted coordinated-profit domain and real-view regressions relevant to the migrated callers, including scope selection, blocked rows and refresh preservation. Report unavailable prerequisites honestly; do not claim unexecuted checks passed.

## Constraints

- Behavior-preserving Phase 3 extraction only; no new domain policy or user-facing feature.
- Keep domain calculations in the independent domain layer and infrastructure coordination in the application layer.
- Introduce only boundaries necessary for this use case and meaningful tests; no broad infrastructure rewrite.

## Dependencies

None.

## Definition of Done

The profit flow uses the application service, the acceptance criteria are evidenced, targeted tests are recorded, and architecture documentation reflects the resulting flow.

## Result

Added `application.CraftingProfitService` (new `application` package, `TARGET_ARCHITECTURE.md` §8)
and moved into it, verbatim in logic, everything `CraftingProfitController.reload(...)` previously
did beyond presentation formatting: `RecipeRepository`/`CraftingGraphCache`/`CharacterRepository`/
`InventoryRepository`/`TpPriceRepository`/`ItemRepository` loading, `buildCoordinatedRoster(...)`,
`CraftingPlanner.evaluateAllCoordinated(...)` invocation, and the lazy per-recipe
`RecipeTreeBuilder`/`PlannerContext` tree build previously inlined in `getResultByRecipeId(...)`
(`buildTreeForRecipeId`). The service has no JavaFX import and calls `craft.*` rather than
reimplementing any calculation.

`CraftingProfitController` now holds one `CraftingProfitService` instance and:
- `reload(choice, settings)` calls `profitService.reload(...)` and passes its returned
  `ProfitData` (visible/all recipes, results-by-recipe-id, items, tp) into the unchanged
  `prepareRows(...)` presentation boundary.
- `getResultByRecipeId(recipeId)` delegates directly to `profitService.getResultByRecipeId(...)`.
- Kept only presentation-layer state (`lastItems`, `lastTp`, `lastAllowedRecipeIds`, recomputed from
  the service's return value) and the unchanged `prepareRows`/`summarizeMissing`/`buildSearchBlob`/
  `itemName`/`itemSellUnit`/`tpQuote` formatting methods — no repository or planner/tree call
  remains in the controller.
`CraftingProfitView` and `CraftingDiscoveryController`/`CraftingDiscoveryView` were not changed;
Discovery's own extraction is `STORY-APP-002` (To Do), left as its own boundary per this story's
"introduce only the boundary necessary for this use case" constraint.

**Acceptance criteria evidence:**
1. `application.CraftingProfitService` is a plain class (no `javafx.*` import) that owns all data
   loading and domain invocation for the flow; verified by reading its imports and by the
   dependency-direction update in `docs/CURRENT_ARCHITECTURE.md` §4.
2. `CraftingProfitController`'s imports were reduced to `craft.*` (types only), `repo.DiscChoice`,
   `repo.ItemRepository` — no `repo.RecipeRepository`/`InventoryRepository`/`TpPriceRepository`/
   `CharacterRepository`/`CraftingGraphCache`, no `craft.CraftingPlanner`/`RecipeTreeBuilder`/
   `PlannerContext`/`CraftingGraph`. `CraftingProfitView` is unchanged and continues to call only
   `controller.reload(...)`/`getResultByRecipeId(...)`/`itemName(...)`/`itemSellUnit(...)`/
   `tpQuote(...)`, now backed by the service.
3. Scope/roster/inventory-split/settings/allowed-recipes/blocked-result logic was moved unedited
   (`buildCoordinatedRoster`, the `useOwnMats`/roster-non-empty inventory-split branch, the
   `evaluateAllCoordinated` call with the same arguments); lazy tree lookup keeps the same
   "build once, cache on the current `lastResultsByRecipeId` map, replaced wholesale by the next
   `reload()`" behavior, now inside the service instead of the controller, so no cross-request
   staleness was introduced.
4. No new domain types, calculations, or persistence mapping were added; `prepareRows` and its
   presentation helpers are byte-for-byte the same as before except for the data now arriving via
   `ProfitData` instead of controller fields.
5. `docs/CURRENT_ARCHITECTURE.md` updated: §2 (package list, new `application` entry), §3
   (`application` package responsibility row), §4 (dependency diagram and a new "key observed
   fact" describing the controller/service split, noting Discovery is unchanged), §5.1 (flow
   diagram now shows `CraftingProfitController -> application.CraftingProfitService -> repo.*/
   craft.*`), §6 (UI relationship table gained an "Application" column; Profit row updated,
   Discovery/other rows explicitly "no").

**Tests run:**
- New application-layer orchestration suite (`src/test/java/application/CraftingProfitServiceTest.java`,
  8 tests, TARGET_ARCHITECTURE.md §25): fake/in-memory subclass adapters for every repository plus
  `CraftingGraphCache` (no PostgreSQL involved), and a recording subclass of the real
  `CraftingPlanner` that captures its inputs before delegating to the real implementation. Covers:
  `ALL` scope querying `loadRecipes("All")` and keeping the full multi-discipline roster;
  `CHAR_DISCIPLINE` routing to `loadRecipesForCharacter(charName, discipline)` and narrowing the
  roster to that one character/discipline; `DISCIPLINE_ONLY` narrowing the roster to characters
  holding that discipline; owned-inventory/TP-quote/item handoff into the planner call and the
  returned `ProfitData` when `useOwnMats=true` and the roster is non-empty; inventory load being
  skipped when `useOwnMats=false`; a `PRICE_UNAVAILABLE`-blocked result being returned (not dropped)
  for a required unpriced purchase; `getResultByRecipeId(...)` building the resolution tree once and
  caching it (identical object on a second call), and a later `reload()` whose graph no longer
  contains a given recipe id making that id's cached result unreachable (no cross-request
  staleness); and a repository `SQLException` propagating out of `reload(...)` unchanged. Ran via
  `./mvnw -q -o test -Dtest=application.CraftingProfitServiceTest` — 8/8 pass.
- Full suite: `./mvnw -q -o test` — exit 0, no failures reported (includes the new suite above,
  against the real local PostgreSQL instance configured via the project's `.env` for the
  DB-backed tests, per existing test conventions).
- Targeted domain/application-boundary regressions, run explicitly to confirm this extraction:
  `CraftingBlockedRowsTest` (calls `CraftingProfitController.prepareRows(...)` directly — still
  passes unchanged), `CraftingProfitCoordinatedScopeTest` (calls `CraftingProfitController.reload(...)`
  against a disposable-schema fixture — still passes, proving `ALL`/`DISCIPLINE_ONLY`/
  `CHAR_DISCIPLINE` scope and bound-material-per-character behavior are unchanged through the new
  service boundary), `craft.CraftingResolverCoordinatedCharactersTest` (unaffected domain test).
- Targeted real-view (TestFX) regressions for the migrated callers:
  `uiverify.CraftingProfitViewSmokeIT`, `uiverify.CraftingProfitViewRefreshPreservationIT`,
  `uiverify.CraftingProfitViewBlockedRowIT`, `uiverify.CraftingProfitViewErrorStatusIT` (the forced
  `SQLException` from a missing `tp_prices` table now propagates
  `application.CraftingProfitService.reload` → `CraftingProfitController.reload` →
  `CraftingProfitView`, and the view still shows the same `"❌ DB load failed: ..."` status — the
  stack trace in the test log confirms the new call path, and the test still passes),
  `uiverify.CraftingProfitCoordinatedScopeIT` — all pass.

`CraftingGraphCache` gained a small enabling change: `CraftingProfitService` now injects one
`CraftingGraphCache` instance (built once, alongside the other repositories) instead of
constructing a new `new CraftingGraphCache(recipeRepo)` inline on every `reload()` call — behavior
is unchanged (the class is stateless besides its `ObjectMapper`/`recipeRepo` reference) and this is
what makes the graph cache substitutable by the fake adapter above.

**Remaining uncertainty:** none identified against the acceptance criteria. `STORY-APP-002` (Discovery
extraction) remains separate future work and was not started.

## Blockers

None.
