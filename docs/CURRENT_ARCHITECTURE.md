# GW2 Tool — Current Architecture

## 1. Purpose and Scope

This document describes how the existing implementation is actually structured, based on direct inspection of the source tree under `src/`. It is descriptive, not prescriptive — see `TARGET_ARCHITECTURE.md` for the intended direction and `KNOWN_PROBLEMS.md` for risk analysis.

All statements below are **observed facts** unless explicitly marked as an inference. No source files were modified while producing this document.

---

## 2. Physical Package Layout

```text
src/
├── (default package)        Gw2App, InitialSetupService, AccountRefreshService,
│                             BankView, MaterialsView, EctoView,
│                             CraftingProfitView, CraftingProfitController,
│                             CraftingDiscoveryView, CraftingDiscoveryController
├── application/              CraftingProfitService (Application Layer use case for the Crafting
│                             Profit flow, STORY-APP-001), CraftingDiscoveryService (same for the
│                             Crafting Discovery flow, STORY-APP-002), EctoSalvageService (same for
│                             the Ectoplasm Salvage flow, STORY-APP-003; TARGET_ARCHITECTURE.md §8)
├── api/                      Gw2ApiClient, Gw2PriceFetch, BatchUtils, HttpStatus
│   └── tp/                   TpPriceApi, EctoLivePriceGateway (live Ecto/Dust price lookup for
│                             EctoSalvageService, STORY-APP-003 - distinct from TpPriceApi's
│                             DB-sync batch fetching)
├── craft/                    CraftingPlanner, CraftingResolver, CostEvaluator,
│                             RecipeSimulator, CraftingGraph, RecipeTreeBuilder,
│                             PlanState, PlannerContext, Node, ResolvedNeed,
│                             ResolvedNeedMapper, ResolveResult, CraftResult,
│                             CostEvaluationResult, RecipeSimulationResult,
│                             CraftingSettings, DailyCrafts, AcquisitionMode,
│                             BlockedReason, Recipe, Ingredient, PriceQuote,
│                             RecipeKnowledgePolicy, CharacterCraftingProfile
├── ecto/                     EctoSalvageCalculator (Ectoplasm Salvage domain calculation, no
│                             JavaFX/repo/controller/application dependency; moved out of the
│                             default package by STORY-APP-003 so application.EctoSalvageService
│                             can import it)
├── model/                    BankSlot, CharacterCraftingRow, CharacterInfo,
│                             CharacterRecipeRow, MaterialStack, Price
├── parser/                   BankParser, CharacterCraftingParser, CharacterNamesParser,
│                             CharacterParser, CharacterRecipesParser, IdListParser,
│                             ItemParser, MaterialParser, RecipeIdParser, RecipeParser,
│                             TpPriceParser
├── repo/                     AppConfig, Db, DiscChoice, CharacterRepository,
│                             InventoryRepository, ItemRepository, RecipeRepository,
│                             CraftingGraphCache, CraftingGraphDto
│   └── tp/                   TpPriceRepository
├── sync/                     AccountSync, CharacterSync, IconSync, ItemSync,
│                             RecipeSync, SyncConstants, TpSync
│   └── tp/relevance/         CraftingProfitItemCollector, DiscoveryItemCollector
├── util/                     CoinUtils, DbBind, TpPrice
└── resources/                Styles/, images/
```

The top-level (default, unnamed) Java package contains the JavaFX entry point, all views, both crafting controllers, and two standalone service classes. Java classes in the default package cannot be imported by classes in named packages, which is itself an architectural constraint on how these pieces can be reused - it is why `STORY-APP-003` had to move `EctoSalvageCalculator` into the new `ecto` package for `application.EctoSalvageService` (a named package) to depend on it, while `EctoView` itself stays in the default package.

`Main.java` (the legacy standalone Ecto CLI calculator) was deleted per the resolved `agent/user-decisions/UD-002-ectoplasm-salvage-fee-model.md` (`docs/KNOWN_PROBLEMS.md` §3.6) and no longer exists in the source tree.

The project is now built with Maven (`./mvnw`, Java 25 target), using the standard `src/main/java` / `src/main/resources` / `src/test/java` layout. Jackson, the PostgreSQL driver, JavaFX, and JUnit are Maven dependencies; there is no `lib/` directory of manually-managed JARs anymore. A `src/test/java` tree exists with one regression test (`craft.CraftingResolverCraftVsBuyTest`).

---

## 3. Package Responsibilities

| Package | Observed responsibility |
|---|---|
| default package | JavaFX application shell (`Gw2App`), 5 JavaFX views, 2 feature controllers, 2 orchestration services (`InitialSetupService`, `AccountRefreshService`) |
| `application` | Application Layer use-case orchestration (`TARGET_ARCHITECTURE.md` §8): `CraftingProfitService`, coordinating `repo.*` loading, coordinated-roster construction and `craft.CraftingPlanner`/`RecipeTreeBuilder` invocation for the Crafting Profit flow (§5.1, `STORY-APP-001`); `CraftingDiscoveryService`, the same kind of boundary for the Crafting Discovery flow (§5.2, `STORY-APP-002`) - missing-discoverable-recipe/graph/inventory/price/item loading and single-character `craft.CraftingPlanner.evaluateAll`/`RecipeTreeBuilder` invocation; and `EctoSalvageService`, coordinating `api.tp.EctoLivePriceGateway` price acquisition and `ecto.EctoSalvageCalculator` invocation for the Ectoplasm Salvage flow (§5.3, `STORY-APP-003`). None of the three classes has a JavaFX dependency. |
| `api` | Low-level HTTP client for the GW2 API (`Gw2ApiClient`), batching helper, HTTP status handling, `Gw2PriceFetch` — an ad-hoc price fetcher that has had no caller anywhere in the codebase since `Main` (its only caller) was deleted (see `docs/KNOWN_PROBLEMS.md` §7.8) — and `api.tp.EctoLivePriceGateway`, the live (unsynchronized) Ecto/Dust price lookup used by `application.EctoSalvageService` (`STORY-APP-003`) |
| `craft` | Persistence-independent crafting domain: independent domain types (`Recipe`, `Ingredient`, `PriceQuote`), the recipe-known policy (`RecipeKnowledgePolicy`), recipe selection, inventory consumption, craft-vs-buy decisions, cost/profit math, and resolution tree construction. No JDBC/SQL/repository/transport dependency (`TARGET_ARCHITECTURE.md` §7). |
| `ecto` | Persistence-independent Ectoplasm Salvage domain calculation (`EctoSalvageCalculator`): the fee-inclusive net cost/profit/Luck-cost math (DOMAIN_SPEC.md §45-47). No JDBC/SQL/repository/transport/JavaFX dependency. Moved out of the default package by `STORY-APP-003` so `application.EctoSalvageService` can import it. |
| `model` | Plain data records used mainly during API-response parsing (bank slots, character rows, material stacks, price) |
| `parser` | Converts raw GW2 API `JsonNode` responses into `model` records or repository-ready structures |
| `repo` | PostgreSQL access via JDBC (`Db`) plus per-domain repositories (items, recipes, inventory, characters, TP prices), the persistence-to-domain mapping boundary for `craft.*` types (`TARGET_ARCHITECTURE.md` §10), the crafting-graph JSON cache (`CraftingGraphCache`/`CraftingGraphDto`), and hardcoded configuration (`AppConfig`) |
| `sync` | Orchestrates: call `api` → parse via `parser` → upsert via JDBC directly (not via `repo` repositories) into PostgreSQL, sharing `repo.Db.open()` with the `repo` package. |
| `util` | Coin formatting, JDBC null-binding helpers, a `TpPrice` value type |

---

## 4. Dependency Directions (as observed via imports)

```text
Views (default pkg)
   |
   v
Controllers (default pkg)
   |
   +-- CraftingProfitController --> application.CraftingProfitService --+
   |                                                                     |
   +-- CraftingDiscoveryController --> application.CraftingDiscoveryService
                                                                         |
                                                                         v
repo.RecipeRepository  --maps rows into-->  craft.Recipe / craft.Ingredient
repo.tp.TpPriceRepository  --maps rows into-->  craft.PriceQuote
   |
   v
repo.* (JDBC/PostgreSQL, repo.AppConfig)

craft.* (independent domain types and calculation logic; no import of repo.*, JDBC, SQL or transport types)

sync.* --imports--> api.Gw2ApiClient, parser.*, model.*, util.DbBind, repo.Db, repo.AppConfig
                     (writes to PostgreSQL directly via repo.Db, not via repo.* repositories)

Gw2App / InitialSetupService / AccountRefreshService --imports--> sync.*, repo.CraftingGraphCache, repo.RecipeRepository
```

Key observed facts about dependency direction:

- **`CraftingProfitController` no longer imports `repo.*` repositories, `craft.CraftingPlanner`, or `craft.RecipeTreeBuilder` directly (`STORY-APP-001`).** It imports and holds one `application.CraftingProfitService`, which owns those repository/planner/tree-builder calls; the controller keeps only presentation formatting (`UiRow`/`prepareRows`) and the small `ItemRepository.ItemInfo`/`PriceQuote` lookup maps that formatting needs.
- **`CraftingDiscoveryController` no longer imports `repo.RecipeRepository`/`InventoryRepository`/`TpPriceRepository`/`ItemRepository`/`CraftingGraphCache`, or `craft.CraftingPlanner`/`RecipeTreeBuilder`/`PlannerContext` directly (`STORY-APP-002`).** It imports and holds one `application.CraftingDiscoveryService`, which owns those calls; the controller keeps only presentation formatting (`UiRow`/`prepareRows`, missing-summary text, the item-name search blob) and delegates `getResultByRecipeId(...)`/`itemName(...)`/`itemSellUnit(...)` straight through to the service, which is also where that lookup state (`lastItems`/`lastTp`/`lastAllRecipes`/etc.) now lives — including the pre-existing "no missing recipes for this character+discipline" short-circuit that leaves that lookup state untouched rather than clearing it (unchanged behavior, preserved verbatim). The application boundary now covers both crafting flows.
- **The `craft` package (the domain/business-logic layer) no longer imports or depends on any `repo.*` type (`STORY-DOM-017`).** Independent domain types `craft.Recipe`, `craft.Ingredient`, and `craft.PriceQuote` replace the formerly repository-nested classes; `repo.RecipeRepository`/`repo.tp.TpPriceRepository` own the persistence-to-domain mapping (`TARGET_ARCHITECTURE.md` §10). `CraftingGraphCache`/`CraftingGraphDto` (the on-disk crafting-graph cache, which depends on `RecipeRepository` and Jackson) moved to `repo.*` for the same reason. Verified by dependency inspection: no `src/main/java/craft/*.java` or `src/test/java/craft/*.java` file imports `repo.*`.
- `repo.RecipeRepository.loadRecipes(...)`/`loadRecipesForCharacter(...)`/`loadMissingDiscoverableRecipeIdsForCharacter(...)` fetch plain unlock facts (`account_recipes`/`character_recipes` ids) and delegate the "recipe is unlocked" decision to `craft.RecipeKnowledgePolicy.isKnownAccountWide` (see §9 item 2) rather than expressing it as SQL. All three entry points now use the same account-wide fact (`STORY-DOM-019`).
- `sync` and `repo` are two independent, parallel data-access areas, but both now share the same `repo.Db.open()` connection helper (`sync.Db` was removed by `STORY-INFRA-003`), which reads `repo.AppConfig` credentials.
- Views call controllers; controllers call `repo.*` repositories (including `repo.CraftingGraphCache`) and `craft.CraftingPlanner` directly. Views do not call `repo.*` or `craft.*` directly in the two crafting features (profit/discovery) — that indirection exists only for those two features.
- `EctoView` does not go through any repository or controller; it calls one `application.EctoSalvageService` (`STORY-APP-003`), which fetches Ecto/Dust quotes via `api.tp.EctoLivePriceGateway` (a plain `HttpClient` call, not `repo.tp.TpPriceRepository`/`api.Gw2ApiClient`) and delegates its calculation to `ecto.EctoSalvageCalculator`, which has no JavaFX/repo/controller/application dependency (see §7). Icon fetching remains a direct `HttpClient` call inside the view itself (presentation-only, not part of the price/calculation use case).
- `BankView` and `MaterialsView` also bypass `repo.*`/`AppConfig` entirely: each opens its own `java.sql.DriverManager` connection using a literal, hardcoded `DB_URL`/`DB_USER`/`DB_PASS` inside the view class itself, independent of `repo.Db` and `repo.AppConfig`/`EnvConfig` (see `docs/KNOWN_PROBLEMS.md` §2.2).
- `Gw2App`, `InitialSetupService`, and `AccountRefreshService` call `sync.*` classes and `repo.CraftingGraphCache` directly from the UI-adjacent layer.

---

## 5. Major Data Flows

### 5.1 Crafting Profit flow

Performance measurement must span the existing view → controller → `application.CraftingProfitService` → repository/domain → presentation/rendering path described below. The measured latency and verification status are owned by `KNOWN_PROBLEMS.md` §7.9; the Phase 3 acceptance requirement is owned by `TARGET_ARCHITECTURE.md` §33. Measured compliance with the §33 limit exists; the §33 gate additionally requires the Product Owner's confirmation, which `STORY-PERF-001` owns.

`STORY-PERF-001` attributed the real-database page-load cost along this whole path and changed it in four places; full before/after measurements live in that story's Result.

- **Two reloads per page open (presentation).** `CraftingProfitView.show(...)` ran its own initial `reloadTable` while the background discipline-selector load ended in `selectFirst()`, whose value listener fired a second, concurrent, identical reload. The listener now reloads only when the selection means a different scope (`sameScope(...)`; `null` and `Kind.ALL` are the same scope, since `CraftingProfitService.reload` treats them identically).
- **Console reload counters (presentation).** The reload-summary line logged per `KNOWN_PROBLEMS.md` §7.7 called `getResultByRecipeId(...)` once per visible recipe, building each row's resolution tree. It now uses `CraftingProfitService.getRawResultByRecipeId(...)`, which returns the planner's result without the lazy tree build. The logged counters are unchanged.
- **Speculation without copying (domain).** `craft.PlanState` carries an undo journal: `mark()`/`rollbackTo(...)`, plus `captureDelta(...)`/`applyDelta(...)` for choosing among several speculative attempts, and `commitTo(...)` for accepted work that is never revisited. `CraftingResolver.resolveNeed`/`tryCraft` and `RecipeSimulator.simulatePhase` now speculate on the live state and undo losing attempts, instead of deep-copying the whole inventory before every attempt and copying the winner back (UD-005 authorized this representation change). Every mutating path on `PlanState` is journalled, so external callers use `addMissingToBuy`/`addBuyCost`/`beginVisiting`/`endVisiting`/`setDailyLeft` rather than mutating its maps directly.
- **Memoized pure lookups (domain).** `PlannerContext` memoizes `eligibleCharactersFor(recipe)` and backs `CraftingResolver.firstRecipeFor(...)`; both answers depend only on data fixed for a context's lifetime. `PlannerContext.withBuyingDisabled()` derives `RecipeSimulator`'s zero-cash phase-1 context so it shares those tables instead of starting empty per recipe.

Every computed value is preserved: the planner's results were compared against a pre-change reference recorded from the real database across five settings combinations, with zero differences in any profit, cost, craftable count, blocked reason or resolution tree. The one observable change is that `CraftResult.missingToBuy`'s (unordered) map iteration order differs, which changes which two materials the deliberately truncated "To buy: …" summary names first for a minority of rows when buying is enabled; the default view settings (buying disabled) are unaffected. See `STORY-PERF-001`'s Result.

STORY-DOM-014 removed Crafting Profit's separate Character selector; the Discipline selector
(`DiscChoice`) is the sole calculation-scope control (DOMAIN_SPEC.md section 2.2.1), defaulting to
`ALL`. Every scope resolves to a roster of `CharacterCraftingProfile` candidates that a single
coordinated planner assigns per recipe step, instead of restricting the whole plan to one
pre-selected character.

`STORY-APP-001` extracted this orchestration out of `CraftingProfitController` into a named
Application Layer use case, `application.CraftingProfitService` (`TARGET_ARCHITECTURE.md` §8):
the service owns all repository loading, coordinated-roster construction, planner invocation, and
the lazy per-recipe resolution-tree build; it has no JavaFX import and performs no calculation
itself beyond calling `craft.*`. `CraftingProfitController` now holds one `CraftingProfitService`
instance, delegates `reload(...)` and `getResultByRecipeId(...)` to it, and keeps only presentation
formatting (`UiRow`/`prepareRows`, missing-summary text, the item-name search blob) plus the small
`items`/`tp` lookup caches that formatting needs:

```text
CraftingProfitView (Discipline selector ComboBox only; DiscChoice.Kind.ALL / DISCIPLINE_ONLY /
                     CHAR_DISCIPLINE, defaults to ALL)
   -> CraftingProfitController.reload(choice, settings)
        -> application.CraftingProfitService.reload(choice, settings)
             -> RecipeRepository.loadRecipes(...) / loadRecipesForCharacter(...) [visible/allowed set]
             -> CraftingGraphCache.load()  -> reads crafting_graph_cache.json    [full recipe graph]
             -> buildCoordinatedRoster(choice): CharacterRepository.loadAllCharacterCrafting()
                  ratings, filtered to the chosen discipline (DISCIPLINE_ONLY) or character
                  (CHAR_DISCIPLINE); ALL keeps every synced character with all of their disciplines
             -> if settings.useOwnMats && roster non-empty:
                  InventoryRepository.loadOwnedInventoryForCharacters(roster names) [sellable/
                  account-bound/per-character-bound split, binding-aware - DOMAIN_SPEC.md section
                  11.1 / DQ-007]
             -> TpPriceRepository.loadTpQuotes(itemIds)
             -> ItemRepository.loadItems(itemIds)
             -> CraftingPlanner.evaluateAllCoordinated(allRecipes, sellableInv, accountBoundInv,
                  characterBoundInv, roster, tp, settings, allowedRecipeIds)
                  -> per recipe: RecipeSimulator assigns an eligible roster character per step
                       (recipe discipline/rating restrictions) and resolves transferable
                       intermediates between eligible characters, consuming shared/bound inventory
                       without reuse
                  -> CostEvaluator.evaluate(...)
             -> returns visible/all recipes, results-by-recipe-id, items and tp quotes to controller
        -> CraftingProfitController.prepareRows(...) builds UiRow list (presentation formatting)
   -> CraftingProfitView displays table
   -> reload-summary counters (console only, KNOWN_PROBLEMS.md §7.7):
        CraftingProfitController.getRawResultByRecipeId(...) [no tree build]
   -> on row selection: CraftingProfitController.getResultByRecipeId(...)
        -> application.CraftingProfitService.getResultByRecipeId(...)
             -> RecipeTreeBuilder.buildTree(...) (lazy, scoped to the most recent reload(), cached
                  per-recipe so a later reload() cannot return a stale tree) -> Node tree
        -> View renders tree
```

### 5.2 Crafting Discovery flow

Unchanged in calculation by STORY-DOM-014 or STORY-APP-002: Discovery keeps its own
individual-only selectors (a Discipline+Character `DiscChoice` combo populated with
`CHAR_DISCIPLINE` entries only, plus a separate Character selector feeding the binding-aware
inventory lookup), the missing-discoverable-recipe lookup, the `recipe.minRating <= maxLevel`
filter, and the single-character `CraftingPlanner.evaluateAll(...)` path. `STORY-APP-002` extracted
this orchestration out of `CraftingDiscoveryController` into a named Application Layer use case,
`application.CraftingDiscoveryService` (`TARGET_ARCHITECTURE.md` §8), the same way `STORY-APP-001`
did for Profit: the service owns all repository loading, the missing/level filtering, planner
invocation, and the lazy per-recipe resolution-tree build; it has no JavaFX import and performs no
calculation itself beyond calling `craft.*`. `CraftingDiscoveryController` now holds one
`CraftingDiscoveryService` instance and delegates `reload(...)`, `getResultByRecipeId(...)`,
`itemName(...)` and `itemSellUnit(...)` to it, keeping only presentation formatting
(`UiRow`/`prepareRows`, missing-summary text, the item-name search blob):

```text
CraftingDiscoveryView (Discipline+Character DiscChoice selector, CHAR_DISCIPLINE only; separate
                        Character selector for binding-aware inventory)
   -> CraftingDiscoveryController.reload(choice, settings, selectedCharacterName)
        -> application.CraftingDiscoveryService.reload(choice, settings, selectedCharacterName)
             -> CraftingGraphCache.load()  -> reads crafting_graph_cache.json    [full recipe graph]
             -> RecipeRepository.loadMissingDiscoverableRecipeIdsForCharacter(charName, discipline)
                  [account-wide recipe-knowledge policy, STORY-DOM-019 — empty result short-circuits
                  before any inventory/price/item load, leaving the service's cached lookup state
                  from the previous reload() untouched]
             -> visible recipes = graph recipes in that missing-id set, filtered to
                  recipe.minRating <= choice.rating
             -> if settings.useOwnMats: InventoryRepository.loadOwnedInventoryForCharacter(...)
                  (binding-aware sellable/bound split, DOMAIN_SPEC.md section 11.1 / DQ-007) when a
                  character is selected, else InventoryRepository.loadOwnedInventory() (unfiltered
                  fallback, STORY-DOM-012)
             -> TpPriceRepository.loadTpQuotes(itemIds)
             -> ItemRepository.loadItems(itemIds)
             -> CraftingPlanner.evaluateAll(allRecipes, sellableInv, boundInv, tp, settings,
                  allowedRecipeIds)  [single-character path, not the coordinated roster]
             -> returns visible/all recipes, results-by-recipe-id, items and tp quotes to controller
        -> CraftingDiscoveryController.prepareRows(...) builds UiRow list (presentation formatting)
   -> CraftingDiscoveryView displays table
   -> on row selection: CraftingDiscoveryController.getResultByRecipeId(...)/itemName(...)/
        itemSellUnit(...) delegate to application.CraftingDiscoveryService, which builds the lazy
        RecipeTreeBuilder.buildTree(...) result (cached per-recipe, scoped to the most recent
        reload()) -> View renders tree and shopping list
```

It shares the same `CraftingGraphCache` / `RecipeTreeBuilder` machinery as Profit, each flow's
service holding its own instance.

### 5.3 Ectoplasm Salvage flow (sole implementation, application-service boundary)

The historical second implementation (`Main.java`) was deleted (§2, `docs/KNOWN_PROBLEMS.md` §3.6); `EctoView` is now the only Ectoplasm Salvage code path. `STORY-APP-003` routed it through an application service instead of the view acquiring prices and invoking the domain calculator directly (`docs/KNOWN_PROBLEMS.md` §4.4).

```text
EctoView
   -> application.EctoSalvageService.calculate()
        -> api.tp.EctoLivePriceGateway.fetchQuotes(...) (direct HttpClient call to GW2 commerce/prices API)
        -> ecto.EctoSalvageCalculator.evaluate(...) x4 (Ecto-buy/Dust-sell combinations)
           (deducts the 15% TP selling fee from Dust sale proceeds, DOMAIN_SPEC.md §46/§47/DQ-011)
   -> fillProfitGrid / fillLuckGrid render the returned EctoScenarios; icon fetching remains a
      direct view concern (presentation-only, not part of the price/calculation use case)
```

`EctoLivePriceGateway` is a live, unsynchronized lookup for exactly Ecto/Dust - distinct from the DB-backed `repo.tp.TpPriceRepository` the Crafting flows use; it is not part of the `sync.*`/`repo.*` synchronization machinery described in §5.4.

### 5.4 Synchronization / initialization flow

```text
Gw2App (button handlers, each on its own daemon Thread)
   "First-time DB Setup"  -> application.InitialSetupService.firstFill()
        -> sync.AccountRefreshGateway.syncAccountBank/Materials/Recipes()
             -> AccountSync.syncAccountBank/Materials/Recipes()
        -> sync.GlobalDataRefreshGateway.syncAllRecipesGlobalSafe()
             -> RecipeSync.syncAllRecipesGlobalSafe()
        -> sync.GlobalDataRefreshGateway.syncTpTradeableItems()
             -> TpSync.syncTpTradeableItems()
        -> application.TradingPostPriceRefreshService.refreshForDiscovery()/refreshForProfit()
             -> sync.TradingPostPriceRefreshGateway.syncTpPricesForDiscovery/ForProfit()
                  -> TpSync.syncTpPricesForDiscovery/ForProfit()
        -> sync.IconSyncGateway.syncItemIconUrls() / syncItemIconsToDisk(path from ICON_CACHE_DIR env var)
             -> IconSync.syncItemIconUrls() / syncItemIconsToDisk(...)

   "Sync ALL tradeable Items..." -> application.GlobalDataRefreshService.refreshAll()
                                  -> sync.GlobalDataRefreshGateway.syncTpTradeableItems()
                                       -> TpSync.syncTpTradeableItems()
                                  -> sync.GlobalDataRefreshGateway.syncAllRecipesGlobalSafe()
                                       -> RecipeSync.syncAllRecipesGlobalSafe()
                                  -> application.CraftingGraphRebuildService.rebuild()
                                       -> new CraftingGraphCache(recipeRepo).rebuild()
                                          (rewrites crafting_graph_cache.json, several MB)

   "Sync Account..." -> application.AccountRefreshService.refreshAll()
                      -> sync.AccountRefreshGateway.syncAccountBank/Materials/Recipes()
                           -> AccountSync.syncAccountBank/Materials/Recipes()
                      -> sync.AccountRefreshGateway.syncCharacterCraftingAndRecipes()
                           -> CharacterSync.syncCharactersCraftingAndRecipes()

CraftingDiscoveryView "Refresh Trade Post Prices" -> application.TradingPostPriceRefreshService.refreshForDiscovery()
                                                    -> sync.TradingPostPriceRefreshGateway.syncTpPricesForDiscovery()
                                                         -> TpSync.syncTpPricesForDiscovery()

CraftingProfitView "Refresh Trade Post Prices" -> application.TradingPostPriceRefreshService.refreshForProfit()
                                                 -> sync.TradingPostPriceRefreshGateway.syncTpPricesForProfit()
                                                      -> TpSync.syncTpPricesForProfit()

CraftingDiscoveryView auto-refresh timer (120s countdown, own single-thread scheduler)
                      -> application.AccountRefreshService.refreshAll()
                           -> sync.AccountRefreshGateway.syncAccountBank/Materials/Recipes()
                                -> AccountSync.syncAccountBank/Materials/Recipes()
                           -> sync.AccountRefreshGateway.syncCharacterCraftingAndRecipes()
                                -> CharacterSync.syncCharactersCraftingAndRecipes()
                      -> then Platform.runLater: "Last refresh" label + status text + table reload

CraftingProfitView auto-refresh timer (90s countdown, own single-thread scheduler)
                   -> application.AccountRefreshService.refreshMaterialsAndRecipes()
                        -> sync.AccountRefreshGateway.syncAccountMaterials/Recipes()
                             -> AccountSync.syncAccountMaterials/Recipes()
                             (no bank sync, no character sync - narrower than refreshAll())
                   -> then Platform.runLater: status text + table reload
```

Each `sync.*` class talks to the GW2 API via `api.Gw2ApiClient`, parses the response via `parser.*`, and writes to PostgreSQL directly using its own JDBC code (upsert + delete-stale pattern keyed on a `fetched_at` timestamp), independent of the `repo.*` repository classes used for reads.

`STORY-APP-006` routed Trading Post price refresh through a named Application Layer use case, `application.TradingPostPriceRefreshService` (`TARGET_ARCHITECTURE.md` §8): it exposes the two pre-existing, distinctly scoped variants - `refreshForDiscovery()` and `refreshForProfit()` - each delegating to its own `sync.TradingPostPriceRefreshGateway` call (a thin instantiable wrapper over the non-instantiable `TpSync`, mirroring `AccountRefreshGateway`/`GlobalDataRefreshGateway`'s precedent). Item relevance selection (`sync.tp.relevance.DiscoveryItemCollector`/`CraftingProfitItemCollector`), quote fetching and `tp_prices` persistence are unchanged - the service only replaces the call sites, not `TpSync`'s own implementation. `CraftingDiscoveryView`'s and `CraftingProfitView`'s "Refresh Trade Post Prices" buttons now call the service instead of `TpSync` directly (each view keeps its own distinct variant; neither refreshes the other's scope), and `application.InitialSetupService.firstFill()` (`STORY-APP-007`) calls the same service directly as one of its own collaborators, preserving its exact position (after tradeable-item sync, before icon sync) and discovery-then-profit order. Tradeable-item synchronization (`TpSync.syncTpTradeableItems()`) remains outside this service in every caller, per `STORY-APP-005`'s constraint that it stay distinct from price refresh.

`STORY-APP-004` routed the "Sync Account" button through a named Application Layer use case, `application.AccountRefreshService` (`TARGET_ARCHITECTURE.md` §8), instead of `Gw2App` calling `sync.*` directly: the service owns the exact pre-extraction call order (account bank, account materials, account recipes, then character crafting/recipes) and short-circuits on the first failure, unchanged from the original inline sequence. It has no JavaFX import and coordinates one collaborator, `sync.AccountRefreshGateway` — a thin instantiable wrapper needed only because `AccountSync`/`CharacterSync` are non-instantiable static utility classes with no seam of their own; a fake subclass of the gateway substitutes for it in application-layer tests. This replaces the previously unused top-level `AccountRefreshService` (`docs/KNOWN_PROBLEMS.md` §8), which duplicated a subset of this orchestration but had no `Gw2App` call site; that dead class has been deleted.

`STORY-APP-008` routed both crafting views' periodic auto-refresh timers through that same
`application.AccountRefreshService` instead of them calling `sync.AccountSync`/`sync.CharacterSync`
directly (the last such direct call sites in the presentation layer). The two timers run
**different** synchronization sequences, established by reading the pre-extraction code and
preserved verbatim: `CraftingDiscoveryView`'s is exactly the Sync Account sequence, so it reuses
`refreshAll()`; `CraftingProfitView`'s is the narrower account-materials-then-account-recipes pair
with no bank and no character step, so the service gained a second named operation,
`refreshMaterialsAndRecipes()`, rather than being widened to fit. No synchronization was added to
either path. Cadence (90s/120s), the per-view single-thread `ScheduledExecutorService` and its
shutdown on reopen/back, the one-second countdown-label tick, the countdown reset on both success
and failure, the `Platform.runLater` status/`Last refresh`-label updates and the post-refresh
`reloadTable.run()` all stay in the views. Each view gained a `show(Stage, Runnable,
TradingPostPriceRefreshService, AccountRefreshService)` overload for injection (the existing
three-argument overload delegates to it with a real service) and publishes its refresh `Runnable`
in a package-private static `autoRefreshTask` field, so a UI check can run exactly the scheduler's
production task without waiting out a real countdown interval.

`STORY-APP-005` similarly routed the "Sync ALL tradeable Items..." button through two named Application Layer use cases instead of `Gw2App` calling `sync.*`/`repo.*` directly. `application.GlobalDataRefreshService.refreshAll()` owns the exact pre-extraction call order (tradeable-item sync, then global recipe sync, then one graph rebuild) and short-circuits on the first failure, unchanged from the original inline sequence; it coordinates two collaborators: `sync.GlobalDataRefreshGateway` (a thin instantiable wrapper over the non-instantiable `TpSync`/`RecipeSync` statics, mirroring `AccountRefreshGateway`) and `application.CraftingGraphRebuildService`, which owns invocation of the existing `repo.CraftingGraphCache.rebuild()` and introduces no competing graph-building algorithm. Neither service has a JavaFX import; `CraftingGraphCache` — already an instantiable, overridable class — is `CraftingGraphRebuildService`'s sole replaceable collaborator, substituted with a fake subclass in application-layer tests. Cache format/location (`crafting_graph_cache.json`, relative to the process working directory) and `CraftingGraphCache.load()`'s existing auto-rebuild-when-missing behavior (`STORY-INFRA-002`) are unchanged. `InitialSetupService.firstFill()` was not touched by this story — its own distinct account/global-recipe/TP/icon operation selection and order remain exactly as before, and it still does not call the graph rebuild at all.

`STORY-APP-007` routed the "First-time DB Setup" button through a named Application Layer use case, `application.InitialSetupService` (`TARGET_ARCHITECTURE.md` §8), instead of `Gw2App` calling a top-level `InitialSetupService`/`sync.*` directly: `firstFill()` owns the exact pre-extraction call order (account bank/materials/recipes, global recipes, TP tradeable items, discovery price refresh, profit price refresh, icon URLs, icon disk download) and short-circuits on the first failure, unchanged from the original inline sequence. It has no JavaFX import and coordinates four collaborators: `sync.AccountRefreshGateway` (bank/materials/recipes methods only — not the character crafting/recipes step `application.AccountRefreshService` adds), `sync.GlobalDataRefreshGateway` (global-recipe/tradeable-item methods only — not the graph rebuild `application.GlobalDataRefreshService` adds, and in setup's own recipes-before-items order, the reverse of that service's items-before-recipes order), `application.TradingPostPriceRefreshService` (unchanged, `STORY-APP-006`), and a new `sync.IconSyncGateway` — a thin instantiable wrapper over the non-instantiable `IconSync` statics, mirroring `AccountRefreshGateway`/`GlobalDataRefreshGateway`/`TradingPostPriceRefreshGateway`'s precedent. Setup's step selection and order differ from both `AccountRefreshService`'s and `GlobalDataRefreshService`'s broader workflows, so this story reuses their gateways' individual methods directly rather than composing those two higher-level services, per the constraint against introducing character refresh or graph rebuild into setup. The icon-cache-directory path (`repo.AppConfig.ICON_CACHE_DIR`, env-var-configurable with a `<user.home>/...` default) is still read at `firstFill()` call time, not at construction time, matching the original's lazy-configuration-read timing. This replaces the previously separate top-level `InitialSetupService`, which duplicated this orchestration outside the application layer; that class (and its `syncTpPrices(...)` test seam and matching test) has been deleted so one authoritative setup workflow remains.

---

## 6. UI → Controller → Domain/Repository/API Relationships

| View | Controller | Application (`application.*`) | Domain (`craft.*`) | Repository (`repo.*`) | Direct API/HTTP |
|---|---|---|---|---|---|
| `CraftingProfitView` | `CraftingProfitController` | yes (`CraftingProfitService`, `STORY-APP-001`, for reload; `TradingPostPriceRefreshService.refreshForProfit()`, `STORY-APP-006`, for its "Refresh Trade Post Prices" button; `AccountRefreshService.refreshMaterialsAndRecipes()`, `STORY-APP-008`, for its 90s auto-refresh timer) | indirectly, via the application service (`CraftingPlanner`, `RecipeTreeBuilder`) | indirectly, via the application service (repositories) | no |
| `CraftingDiscoveryView` | `CraftingDiscoveryController` | yes (`CraftingDiscoveryService`, `STORY-APP-002`, for reload; `TradingPostPriceRefreshService.refreshForDiscovery()`, `STORY-APP-006`, for its "Refresh Trade Post Prices" button; `AccountRefreshService.refreshAll()`, `STORY-APP-008`, for its 120s auto-refresh timer) | indirectly, via the application service (`CraftingPlanner`, `RecipeTreeBuilder`) | indirectly, via the application service (4 repos) | no |
| `BankView` | none | no | no | no — bypasses `repo.*` with its own hardcoded inline `DriverManager` connection (`docs/KNOWN_PROBLEMS.md` §2.2) | no |
| `MaterialsView` | none | no | no | no — same hardcoded-connection pattern as `BankView` (`docs/KNOWN_PROBLEMS.md` §2.2) | no |
| `EctoView` | none | yes (`EctoSalvageService`, `STORY-APP-003`) | indirectly, via the application service (`ecto.EctoSalvageCalculator`) | no | indirectly, via the application service (`api.tp.EctoLivePriceGateway` → `api.guildwars2.com/v2/commerce/prices`); icon fetching remains a direct `HttpClient` call in the view |
| `Gw2App` | none | "Sync Account" (`application.AccountRefreshService`, `STORY-APP-004`), "Sync ALL tradeable Items..." (`application.GlobalDataRefreshService`/`application.CraftingGraphRebuildService`, `STORY-APP-005`), and "First-time DB Setup" (`application.InitialSetupService`, `STORY-APP-007`) | indirectly, via the three application services for all three migrated buttons | indirectly, via the three application services for all three migrated buttons | indirectly via `sync.*` (`AccountRefreshService`'s `sync.AccountRefreshGateway` collaborator for Sync Account; `GlobalDataRefreshService`'s `sync.GlobalDataRefreshGateway` collaborator for Sync ALL tradeable Items; `InitialSetupService`'s `sync.AccountRefreshGateway`/`sync.GlobalDataRefreshGateway`/`sync.IconSyncGateway` collaborators, plus `application.TradingPostPriceRefreshService`, for First-time DB Setup) |

The three crafting/Ecto features (Profit, Discovery, Ectoplasm Salvage) follow a View → Application → Domain(/Repository) separation; `EctoView` has no Controller layer, calling its application service directly. `Gw2App`'s "Sync Account", "Sync ALL tradeable Items..." and "First-time DB Setup" buttons now follow the same pattern; `BankView` and `MaterialsView` still call infrastructure directly from the presentation layer. Nine named Application Layer boundaries now exist (`application.CraftingProfitService`/`STORY-APP-001`, `application.CraftingDiscoveryService`/`STORY-APP-002`, `application.EctoSalvageService`/`STORY-APP-003`, `application.AccountRefreshService`/`STORY-APP-004`, `application.GlobalDataRefreshService`/`application.CraftingGraphRebuildService`/`STORY-APP-005`, `application.TradingPostPriceRefreshService`/`STORY-APP-006`, `application.InitialSetupService`/`STORY-APP-007`); the other views' repository/API bypasses in this table are unaffected by those stories. `STORY-APP-008` added no tenth boundary — it routed both crafting views' auto-refresh timers through the existing `application.AccountRefreshService` (§5.4), after which no view calls `sync.*` directly any more.

---

## 7. Initialization and Synchronization Flow

- `Gw2App.start(Stage)` builds the home screen synchronously on the JavaFX Application Thread.
- Every sync/setup action is dispatched via a manually created `new Thread(...)` (marked daemon), not a managed executor or task framework.
- Completion/failure is reported back to the JavaFX thread using `Platform.runLater(...)`, updating a shared `status` `Label`.
- Errors are caught, printed via `ex.printStackTrace()`, and surfaced as a status-label string; there is no structured error type or centralized handling (matches `CURRENT_STATE_SPEC.md` §24).
- The Crafting Graph is only ever (re)built as a side effect of the "Sync ALL tradeable Items..." button and `InitialSetupService.firstFill()` is not itself called automatically — the user must trigger it manually from the home screen.
- `CraftingGraphCache.load()`/`rebuild()` read/write `crafting_graph_cache.json` using a path relative to the process's current working directory (`new File("crafting_graph_cache.json")`), not a configurable or resource-relative path.

---

## 8. Configuration

- `repo.AppConfig` now sources the GW2 API key and PostgreSQL URL/user/password from `repo.EnvConfig`, which reads environment variables with a gitignored `.env` file as a local-development fallback (see `.env.example`). No credential values remain hardcoded in source (`docs/KNOWN_PROBLEMS.md` §2.1).
- Configuration is supplied via environment variables / `.env` as described above; there is no properties-file or command-line-argument based configuration mechanism.
- The `Gw2App` home screen has an API-key `TextField` and "Save" button whose handler only sets a status label (`"API key saving not implemented yet."`) and does not persist anything — confirmed by direct reading of `Gw2App.java`.

---

## 9. Areas Where Responsibilities Are Mixed

Observed (not inferred) mixing of concerns, by file:

1. **(Resolved by `STORY-DOM-017`)** ~~`craft/*` importing `repo/*` types directly.~~ The crafting engine's core data types are now independent domain types (`craft.Recipe`, `craft.Ingredient`, `craft.PriceQuote`); `repo.RecipeRepository`/`repo.tp.TpPriceRepository` map persistence rows into them, and `craft.*` no longer imports anything from `repo.*`.
2. **(Resolved by `STORY-DOM-017`/`STORY-DOM-018`/`STORY-DOM-019`)** ~~`repo.RecipeRepository.loadRecipes(...)` embeds the "recipe is unlocked" business rule as a SQL `UNION` CTE.~~ The unlock decision is now `craft.RecipeKnowledgePolicy.isKnownAccountWide` (a single pure domain policy function, no JDBC/SQL dependency); `repo.RecipeRepository` fetches plain unlock facts and calls the policy in Java. `STORY-DOM-018` extracted the policy but left `loadRecipesForCharacter`/`loadMissingDiscoverableRecipeIdsForCharacter` checking only the selected character's own unlocks (tracked as a confirmed disagreement in `docs/KNOWN_PROBLEMS.md` §4.2); `STORY-DOM-019` corrected both to use the same account-wide (any-character) knowledge decision as `loadRecipes`, per `DOMAIN_SPEC.md` §34/35 and decided `DQ-010`.
3. **(Resolved by `STORY-APP-003`)** ~~`EctoView` calls its own `EctoSalvageCalculator` directly, with no application-service boundary.~~ `EctoView` is the sole Ectoplasm Salvage implementation in the codebase. Its calculation (`DUST_PER_ECTO = 0.75`, `LUCK_PER_ECTO = 20.0`, `ECTOS_PER_1000_LUCK = 50`, and DOMAIN_SPEC.md §46-47's fee-inclusive net cost) lives in `ecto.EctoSalvageCalculator`, a plain domain class with no JavaFX/repo/controller/application dependency. `EctoView` calls one `application.EctoSalvageService`, which fetches Ecto/Dust quotes via `api.tp.EctoLivePriceGateway` and invokes the calculator across all four Ecto-buy/Dust-sell combinations; `EctoView`'s `fillProfitGrid`/`fillLuckGrid` only format and display the returned results. Like Profit/Discovery, it now has a named Application Layer boundary, though (unlike those two) with no Controller layer in between.
4. **(Resolved by `STORY-APP-004`/`STORY-APP-005`/`STORY-APP-006`/`STORY-APP-007`)** ~~`Gw2App`'s "First-time DB Setup" button handler directly calls `InitialSetupService`/`sync.*`.~~ All three `Gw2App` sync buttons ("Sync Account", "Sync ALL tradeable Items...", "First-time DB Setup") now delegate to named application services (`application.AccountRefreshService`/`application.GlobalDataRefreshService`/`application.InitialSetupService`) instead of calling `sync.*`/`repo.*` directly from the button handler. `application.InitialSetupService.firstFill()` (`STORY-APP-007`) owns the setup orchestration that previously lived in a top-level `InitialSetupService` class outside the application layer; that class has been deleted.
5. **(Resolved by `STORY-INFRA-003`)** ~~Two independent JDBC connection helpers (`repo.Db`, `sync.Db`) with different method names but identical behavior.~~ `sync.Db` was removed; all `repo.*` and `sync.*` callers now share `repo.Db.open()`.
6. **`BankView`/`MaterialsView`** each declare their own literal `DB_URL`/`DB_USER`/`DB_PASS` constants and call `DriverManager.getConnection(...)` directly, bypassing `repo.AppConfig`/`repo.EnvConfig`/`repo.Db` entirely — two independent connection-acquisition paths beyond the single shared helper in item 5 (`docs/KNOWN_PROBLEMS.md` §2.2).

---

## 10. Status

This document reflects a point-in-time reading of the files listed in §2–§9. Files not explicitly named above (e.g. `BankView`, `MaterialsView`, most of `parser/*`, `sync/CharacterSync`, `sync/ItemSync`, `sync/RecipeSync`, `sync/TpSync` internals, `sync/tp/relevance/*`) were identified and classified by responsibility but not read in full line-by-line detail during this pass. Deeper inspection of those files may refine this document further.
