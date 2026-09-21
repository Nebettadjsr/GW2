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
│                             Crafting Discovery flow, STORY-APP-002; TARGET_ARCHITECTURE.md §8)
├── api/                      Gw2ApiClient, Gw2PriceFetch, BatchUtils, HttpStatus
│   └── tp/                   TpPriceApi
├── craft/                    CraftingPlanner, CraftingResolver, CostEvaluator,
│                             RecipeSimulator, CraftingGraph, RecipeTreeBuilder,
│                             PlanState, PlannerContext, Node, ResolvedNeed,
│                             ResolvedNeedMapper, ResolveResult, CraftResult,
│                             CostEvaluationResult, RecipeSimulationResult,
│                             CraftingSettings, DailyCrafts, AcquisitionMode,
│                             BlockedReason, Recipe, Ingredient, PriceQuote,
│                             RecipeKnowledgePolicy, CharacterCraftingProfile
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

The top-level (default, unnamed) Java package contains the JavaFX entry point, all views, both crafting controllers, and two standalone service classes. Java classes in the default package cannot be imported by classes in named packages, which is itself an architectural constraint on how these pieces can be reused.

`Main.java` (the legacy standalone Ecto CLI calculator) was deleted per the resolved `agent/user-decisions/UD-002-ectoplasm-salvage-fee-model.md` (`docs/KNOWN_PROBLEMS.md` §3.6) and no longer exists in the source tree.

The project is now built with Maven (`./mvnw`, Java 25 target), using the standard `src/main/java` / `src/main/resources` / `src/test/java` layout. Jackson, the PostgreSQL driver, JavaFX, and JUnit are Maven dependencies; there is no `lib/` directory of manually-managed JARs anymore. A `src/test/java` tree exists with one regression test (`craft.CraftingResolverCraftVsBuyTest`).

---

## 3. Package Responsibilities

| Package | Observed responsibility |
|---|---|
| default package | JavaFX application shell (`Gw2App`), 5 JavaFX views, 2 feature controllers, 2 orchestration services (`InitialSetupService`, `AccountRefreshService`) |
| `application` | Application Layer use-case orchestration (`TARGET_ARCHITECTURE.md` §8): `CraftingProfitService`, coordinating `repo.*` loading, coordinated-roster construction and `craft.CraftingPlanner`/`RecipeTreeBuilder` invocation for the Crafting Profit flow (§5.1, `STORY-APP-001`); and `CraftingDiscoveryService`, the same kind of boundary for the Crafting Discovery flow (§5.2, `STORY-APP-002`) - missing-discoverable-recipe/graph/inventory/price/item loading and single-character `craft.CraftingPlanner.evaluateAll`/`RecipeTreeBuilder` invocation. Neither class has a JavaFX dependency. |
| `api` | Low-level HTTP client for the GW2 API (`Gw2ApiClient`), batching helper, HTTP status handling, and `Gw2PriceFetch` — an ad-hoc price fetcher that has had no caller anywhere in the codebase since `Main` (its only caller) was deleted; see `docs/KNOWN_PROBLEMS.md` §7.8 |
| `craft` | Persistence-independent crafting domain: independent domain types (`Recipe`, `Ingredient`, `PriceQuote`), the recipe-known policy (`RecipeKnowledgePolicy`), recipe selection, inventory consumption, craft-vs-buy decisions, cost/profit math, and resolution tree construction. No JDBC/SQL/repository/transport dependency (`TARGET_ARCHITECTURE.md` §7). |
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
- `EctoView` does not go through any repository or controller; it calls `api.Gw2ApiClient` directly (via a plain `HttpClient`) and delegates its calculation to `EctoSalvageCalculator` (default-package plain class), which has no JavaFX/repo/controller dependency (see §7).
- `BankView` and `MaterialsView` also bypass `repo.*`/`AppConfig` entirely: each opens its own `java.sql.DriverManager` connection using a literal, hardcoded `DB_URL`/`DB_USER`/`DB_PASS` inside the view class itself, independent of `repo.Db` and `repo.AppConfig`/`EnvConfig` (see `docs/KNOWN_PROBLEMS.md` §2.2).
- `Gw2App`, `InitialSetupService`, and `AccountRefreshService` call `sync.*` classes and `repo.CraftingGraphCache` directly from the UI-adjacent layer.

---

## 5. Major Data Flows

### 5.1 Crafting Profit flow

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

### 5.3 Ectoplasm Salvage flow (sole implementation)

The historical second implementation (`Main.java`) was deleted (§2, `docs/KNOWN_PROBLEMS.md` §3.6); `EctoView` is now the only Ectoplasm Salvage code path.

```text
EctoView
   -> HttpClient direct call to GW2 commerce/prices API
   -> EctoSalvageCalculator (default-package plain class, no repo/controller dependency)
      fillProfitGrid / fillLuckGrid display its results
      (deducts the 15% TP selling fee from Dust sale proceeds, DOMAIN_SPEC.md §46/§47/DQ-011)
```

### 5.4 Synchronization / initialization flow

```text
Gw2App (button handlers, each on its own daemon Thread)
   "First-time DB Setup"  -> InitialSetupService.firstFill()
        -> AccountSync.syncAccountBank/Materials/Recipes()
        -> RecipeSync.syncAllRecipesGlobalSafe()
        -> TpSync.syncTpTradeableItems/syncTpPricesForDiscovery/syncTpPricesForProfit()
        -> IconSync.syncItemIconUrls() / syncItemIconsToDisk(path from ICON_CACHE_DIR env var)

   "Sync ALL tradeable Items..." -> TpSync.syncTpTradeableItems()
                                  -> RecipeSync.syncAllRecipesGlobalSafe()
                                  -> new CraftingGraphCache(recipeRepo).rebuild()
                                     (rewrites crafting_graph_cache.json, several MB)

   "Sync Account..." -> AccountSync.syncAccountBank/Materials/Recipes()
                      -> CharacterSync.syncCharactersCraftingAndRecipes()
```

Each `sync.*` class talks to the GW2 API via `api.Gw2ApiClient`, parses the response via `parser.*`, and writes to PostgreSQL directly using its own JDBC code (upsert + delete-stale pattern keyed on a `fetched_at` timestamp), independent of the `repo.*` repository classes used for reads.

`AccountRefreshService.refreshAll()` duplicates a subset of `InitialSetupService.firstFill()`'s account-sync calls but is not invoked from any UI button currently read (no call site found for it in `Gw2App`).

---

## 6. UI → Controller → Domain/Repository/API Relationships

| View | Controller | Application (`application.*`) | Domain (`craft.*`) | Repository (`repo.*`) | Direct API/HTTP |
|---|---|---|---|---|---|
| `CraftingProfitView` | `CraftingProfitController` | yes (`CraftingProfitService`, `STORY-APP-001`) | indirectly, via the application service (`CraftingPlanner`, `RecipeTreeBuilder`) | indirectly, via the application service (repositories) | no |
| `CraftingDiscoveryView` | `CraftingDiscoveryController` | yes (`CraftingDiscoveryService`, `STORY-APP-002`) | indirectly, via the application service (`CraftingPlanner`, `RecipeTreeBuilder`) | indirectly, via the application service (4 repos) | no |
| `BankView` | none | no | no | no — bypasses `repo.*` with its own hardcoded inline `DriverManager` connection (`docs/KNOWN_PROBLEMS.md` §2.2) | no |
| `MaterialsView` | none | no | no | no — same hardcoded-connection pattern as `BankView` (`docs/KNOWN_PROBLEMS.md` §2.2) | no |
| `EctoView` | none | no | `EctoSalvageCalculator` (plain class, no repo/controller wiring) | no | yes, direct `HttpClient` to `api.guildwars2.com/v2/commerce/prices` |
| `Gw2App` | none (calls `sync.*`/`InitialSetupService` directly from button handlers) | no | indirectly (`CraftingGraphCache.rebuild()`) | indirectly (`RecipeRepository` for graph rebuild) | indirectly via `sync.*` |

Only the two crafting features (Profit, Discovery) follow a View → Controller → Domain/Repository separation. The other UI entry points (`Gw2App` sync buttons, `EctoView`, `BankView`, `MaterialsView`) call infrastructure or perform calculations directly from the presentation layer. Both crafting flows now have a named Application Layer boundary (`application.CraftingProfitService`/`STORY-APP-001`, `application.CraftingDiscoveryService`/`STORY-APP-002`); the other views' repository/API bypasses in this table are unaffected by those stories.

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
3. **`EctoView`** is the sole Ectoplasm Salvage implementation in the codebase. As of `STORY-DOM-016`, its calculation (`DUST_PER_ECTO = 0.75`, `LUCK_PER_ECTO = 20.0`, `ECTOS_PER_1000_LUCK = 50`, and DOMAIN_SPEC.md §46-47's fee-inclusive net cost) lives in `EctoSalvageCalculator`, a plain class with no JavaFX/repo/controller dependency; `EctoView`'s `fillProfitGrid`/`fillLuckGrid` only format and display that class's results. It is still not wired through a repository or controller like Profit/Discovery.
4. **`Gw2App`** button handlers directly call `sync.*` static methods and construct `CraftingGraphCache`/`RecipeRepository` instances — synchronization orchestration lives in the UI event-handler layer rather than in `InitialSetupService`/`AccountRefreshService` consistently (some buttons call the service classes, others call `sync.*` directly and duplicate the same call sequence).
5. **(Resolved by `STORY-INFRA-003`)** ~~Two independent JDBC connection helpers (`repo.Db`, `sync.Db`) with different method names but identical behavior.~~ `sync.Db` was removed; all `repo.*` and `sync.*` callers now share `repo.Db.open()`.
6. **`BankView`/`MaterialsView`** each declare their own literal `DB_URL`/`DB_USER`/`DB_PASS` constants and call `DriverManager.getConnection(...)` directly, bypassing `repo.AppConfig`/`repo.EnvConfig`/`repo.Db` entirely — two independent connection-acquisition paths beyond the single shared helper in item 5 (`docs/KNOWN_PROBLEMS.md` §2.2).

---

## 10. Status

This document reflects a point-in-time reading of the files listed in §2–§9. Files not explicitly named above (e.g. `BankView`, `MaterialsView`, most of `parser/*`, `sync/CharacterSync`, `sync/ItemSync`, `sync/RecipeSync`, `sync/TpSync` internals, `sync/tp/relevance/*`) were identified and classified by responsibility but not read in full line-by-line detail during this pass. Deeper inspection of those files may refine this document further.
