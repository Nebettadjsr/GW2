# GW2 Tool — Current Architecture

## 1. Purpose and Scope

This document describes how the existing implementation is actually structured, based on direct inspection of the source tree under `src/`. It is descriptive, not prescriptive — see `TARGET_ARCHITECTURE.md` for the intended direction and `KNOWN_PROBLEMS.md` for risk analysis.

All statements below are **observed facts** unless explicitly marked as an inference. No source files were modified while producing this document.

---

## 2. Physical Package Layout

```text
src/
├── (default package)        Gw2App, Main, InitialSetupService, AccountRefreshService,
│                             BankView, MaterialsView, EctoView,
│                             CraftingProfitView, CraftingProfitController,
│                             CraftingDiscoveryView, CraftingDiscoveryController
├── api/                      Gw2ApiClient, Gw2PriceFetch, BatchUtils, HttpStatus
│   └── tp/                   TpPriceApi
├── craft/                    CraftingPlanner, CraftingResolver, CostEvaluator,
│                             RecipeSimulator, CraftingGraph, CraftingGraphCache,
│                             CraftingGraphDto, RecipeTreeBuilder, PlanState,
│                             PlannerContext, Node, ResolvedNeed, ResolvedNeedMapper,
│                             ResolveResult, CraftResult, CostEvaluationResult,
│                             RecipeSimulationResult, CraftingSettings, DailyCrafts,
│                             AcquisitionMode, BlockedReason
├── model/                    BankSlot, CharacterCraftingRow, CharacterInfo,
│                             CharacterRecipeRow, MaterialStack, Price
├── parser/                   BankParser, CharacterCraftingParser, CharacterNamesParser,
│                             CharacterParser, CharacterRecipesParser, IdListParser,
│                             ItemParser, MaterialParser, RecipeIdParser, RecipeParser,
│                             TpPriceParser
├── repo/                     AppConfig, Db, DiscChoice, CharacterRepository,
│                             InventoryRepository, ItemRepository, RecipeRepository
│   └── tp/                   TpPriceRepository
├── sync/                     Db, AccountSync, CharacterSync, IconSync, ItemSync,
│                             RecipeSync, SyncConstants, TpSync
│   └── tp/relevance/         CraftingProfitItemCollector, DiscoveryItemCollector
├── util/                     CoinUtils, DbBind, TpPrice
└── resources/                Styles/, images/
```

The top-level (default, unnamed) Java package contains the JavaFX entry point, all views, both crafting controllers, and two standalone service classes. Java classes in the default package cannot be imported by classes in named packages, which is itself an architectural constraint on how these pieces can be reused.

The project is now built with Maven (`./mvnw`, Java 25 target), using the standard `src/main/java` / `src/main/resources` / `src/test/java` layout. Jackson, the PostgreSQL driver, JavaFX, and JUnit are Maven dependencies; there is no `lib/` directory of manually-managed JARs anymore. A `src/test/java` tree exists with one regression test (`craft.CraftingResolverCraftVsBuyTest`).

---

## 3. Package Responsibilities

| Package | Observed responsibility |
|---|---|
| default package | JavaFX application shell (`Gw2App`), 5 JavaFX views, 2 feature controllers, 2 orchestration services (`InitialSetupService`, `AccountRefreshService`), legacy CLI calculator (`Main`) |
| `api` | Low-level HTTP client for the GW2 API (`Gw2ApiClient`), batching helper, HTTP status handling, a separate ad-hoc price fetcher (`Gw2PriceFetch`) used only by `Main` |
| `craft` | Recursive crafting resolution engine: recipe selection, inventory consumption, craft-vs-buy decisions, cost/profit math, resolution tree construction, crafting graph representation and its JSON cache |
| `model` | Plain data records used mainly during API-response parsing (bank slots, character rows, material stacks, price) |
| `parser` | Converts raw GW2 API `JsonNode` responses into `model` records or repository-ready structures |
| `repo` | PostgreSQL access via JDBC (`Db`) plus per-domain repositories (items, recipes, inventory, characters, TP prices) and hardcoded configuration (`AppConfig`) |
| `sync` | Orchestrates: call `api` → parse via `parser` → upsert via JDBC directly (not via `repo` repositories) into PostgreSQL. Has its own duplicate `Db` connection helper. |
| `util` | Coin formatting, JDBC null-binding helpers, a `TpPrice` value type |

---

## 4. Dependency Directions (as observed via imports)

```text
Views (default pkg)
   |
   v
Controllers (default pkg)  ----------------------------+
   |                                                     |
   v                                                     v
craft.*  --imports-->  repo.RecipeRepository.Recipe     repo.tp.TpPriceRepository.TpQuote
   |                    repo.RecipeRepository.Ingredient
   v
repo.* (JDBC/PostgreSQL, repo.AppConfig)

sync.* --imports--> api.Gw2ApiClient, parser.*, model.*, util.DbBind, repo.AppConfig
                     (writes to PostgreSQL directly via its own sync.Db, not via repo.* repositories)

Gw2App / InitialSetupService / AccountRefreshService --imports--> sync.*, craft.CraftingGraphCache, repo.RecipeRepository
```

Key observed facts about dependency direction:

- **The `craft` package (the domain/business-logic layer) directly imports and depends on `repo.RecipeRepository.Recipe`, `repo.RecipeRepository.Ingredient`, and `repo.tp.TpPriceRepository.TpQuote`.** These are nested static classes defined *inside* the repository classes themselves — there is no separate domain model. `CraftingPlanner`, `CraftingResolver`, `CostEvaluator`, `RecipeSimulator`, `CraftingGraph`, and `CraftingGraphCache` all import `repo.RecipeRepository` directly.
- `repo.RecipeRepository.loadRecipes(...)` embeds a business concept (which recipes are "unlocked") directly in SQL, via a `WITH unlocked AS (...)` CTE joining `account_recipes` and `character_recipes`.
- `sync` and `repo` are two independent, parallel data-access areas. Both contain their own `Db` class with near-identical JDBC connection logic (`repo.Db.open()` vs `sync.Db.openConnection()`), both reading the same `repo.AppConfig` credentials.
- Views call controllers; controllers call `repo.*` repositories and `craft.CraftingPlanner`/`craft.CraftingGraphCache` directly. Views do not call `repo.*` or `craft.*` directly in the two crafting features (profit/discovery) — that indirection exists only for those two features.
- `EctoView` and `Main` do not go through any repository or controller; they call `api.Gw2ApiClient` / `api.Gw2PriceFetch` directly and perform their own calculation inline (see §7).
- `Gw2App`, `InitialSetupService`, and `AccountRefreshService` call `sync.*` classes and `craft.CraftingGraphCache` directly from the UI-adjacent layer.

---

## 5. Major Data Flows

### 5.1 Crafting Profit flow

STORY-DOM-014 removed Crafting Profit's separate Character selector; the Discipline selector
(`DiscChoice`) is the sole calculation-scope control (DOMAIN_SPEC.md section 2.2.1), defaulting to
`ALL`. Every scope resolves to a roster of `CharacterCraftingProfile` candidates that a single
coordinated planner assigns per recipe step, instead of restricting the whole plan to one
pre-selected character:

```text
CraftingProfitView (Discipline selector ComboBox only; DiscChoice.Kind.ALL / DISCIPLINE_ONLY /
                     CHAR_DISCIPLINE, defaults to ALL)
   -> CraftingProfitController.reload(choice, settings)
        -> RecipeRepository.loadRecipes(...) / loadRecipesForCharacter(...)   [visible/allowed set]
        -> CraftingGraphCache.load()  -> reads crafting_graph_cache.json      [full recipe graph]
        -> buildCoordinatedRoster(choice): CharacterRepository.loadAllCharacterCrafting() ratings,
             filtered to the chosen discipline (DISCIPLINE_ONLY) or character (CHAR_DISCIPLINE);
             ALL keeps every synced character with all of their disciplines
        -> if settings.useOwnMats && roster non-empty:
             InventoryRepository.loadOwnedInventoryForCharacters(roster names) [sellable/account-bound/
             per-character-bound split, binding-aware - DOMAIN_SPEC.md section 11.1 / DQ-007]
        -> TpPriceRepository.loadTpQuotes(itemIds)
        -> ItemRepository.loadItems(itemIds)
        -> CraftingPlanner.evaluateAllCoordinated(allRecipes, sellableInv, accountBoundInv,
             characterBoundInv, roster, tp, settings, allowedRecipeIds)
             -> per recipe: RecipeSimulator assigns an eligible roster character per step
                  (recipe discipline/rating restrictions) and resolves transferable intermediates
                  between eligible characters, consuming shared/bound inventory without reuse
             -> CostEvaluator.evaluate(...)
        -> builds UiRow list, filters, returns to View
   -> CraftingProfitView displays table
   -> on row selection: CraftingProfitController.getResultByRecipeId(...)
        -> RecipeTreeBuilder.buildTree(...) (lazy) -> Node tree -> View renders tree
```

### 5.2 Crafting Discovery flow

Unchanged by STORY-DOM-014: Discovery keeps its own individual-only selectors (a
Discipline+Character `DiscChoice` combo populated with `CHAR_DISCIPLINE` entries only, plus a
separate Character selector feeding the binding-aware inventory lookup) and the single-character
`CraftingPlanner.evaluateAll(...)` path, structurally as in the pre-DOM-014 §5.1: using
`CraftingDiscoveryController` and `RecipeRepository.loadMissingDiscoverableRecipeIdsForCharacter(...)`
instead of the "visible recipes" query, and filtering by `recipe.minRating <= maxLevel`. It shares
the same `CraftingGraphCache` / `RecipeTreeBuilder` machinery as Profit.

### 5.3 Ectoplasm Salvage flow (two independent implementations)

```text
EctoView                                    Main (separate CLI entry point, not
   -> HttpClient direct call to             wired into Gw2App UI)
      GW2 commerce/prices API                  -> api.Gw2PriceFetch.getPrices(...)
   -> inline static methods                    -> inline static methods
      fillProfitGrid / fillLuckGrid               profitPerEcto / costPer1000Luck
      (no repo/controller/domain class)           (applies a 15% sell fee; EctoView does not)
```

### 5.4 Synchronization / initialization flow

```text
Gw2App (button handlers, each on its own daemon Thread)
   "First-time DB Setup"  -> InitialSetupService.firstFill()
        -> AccountSync.syncAccountBank/Materials/Recipes()
        -> RecipeSync.syncAllRecipesGlobalSafe()
        -> TpSync.syncTpTradeableItems/syncTpPricesForDiscovery/syncTpPricesForProfit()
        -> IconSync.syncItemIconUrls() / syncItemIconsToDisk(hardcoded local path)

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

| View | Controller | Domain (`craft.*`) | Repository (`repo.*`) | Direct API/HTTP |
|---|---|---|---|---|
| `CraftingProfitView` | `CraftingProfitController` | yes (`CraftingPlanner`, `RecipeTreeBuilder`) | yes (4 repos) | no |
| `CraftingDiscoveryView` | `CraftingDiscoveryController` | yes (same engine) | yes (4 repos) | no |
| `BankView` | none observed | no | (not inspected in depth this pass) | — |
| `MaterialsView` | none observed | no | (not inspected in depth this pass) | — |
| `EctoView` | none | `EctoSalvageCalculator` (plain class, no repo/controller wiring) | no | yes, direct `HttpClient` to `api.guildwars2.com/v2/commerce/prices` |
| `Gw2App` | none (calls `sync.*`/`InitialSetupService` directly from button handlers) | indirectly (`CraftingGraphCache.rebuild()`) | indirectly (`RecipeRepository` for graph rebuild) | indirectly via `sync.*` |

Only the two crafting features (Profit, Discovery) follow a View → Controller → Domain/Repository separation. The other three UI entry points (`Gw2App` sync buttons, `EctoView`, and the legacy `Main`) call infrastructure or perform calculations directly from the presentation layer.

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

1. **`craft/*` importing `repo/*` types directly.** The crafting engine's core data types (`Recipe`, `Ingredient`, `TpQuote`) are repository-owned nested classes, not independent domain types. Persistence and domain are the same classes.
2. **`repo.RecipeRepository.loadRecipes(...)`** embeds the "recipe is unlocked" business rule as a SQL `UNION` CTE rather than as an application/domain-level concept.
3. **`EctoView`** is the sole Ectoplasm Salvage implementation in the codebase. As of `STORY-DOM-016`, its calculation (`DUST_PER_ECTO = 0.75`, `LUCK_PER_ECTO = 20.0`, `ECTOS_PER_1000_LUCK = 50`, and DOMAIN_SPEC.md §46-47's fee-inclusive net cost) lives in `EctoSalvageCalculator`, a plain class with no JavaFX/repo/controller dependency; `EctoView`'s `fillProfitGrid`/`fillLuckGrid` only format and display that class's results. It is still not wired through a repository or controller like Profit/Discovery.
4. **`Gw2App`** button handlers directly call `sync.*` static methods and construct `CraftingGraphCache`/`RecipeRepository` instances — synchronization orchestration lives in the UI event-handler layer rather than in `InitialSetupService`/`AccountRefreshService` consistently (some buttons call the service classes, others call `sync.*` directly and duplicate the same call sequence).
5. **Two independent JDBC connection helpers** (`repo.Db`, `sync.Db`) with different method names but identical behavior, both reading `repo.AppConfig` — duplicated infrastructure rather than a mixing of layers, but relevant to dependency-direction clarity.

---

## 10. Status

This document reflects a point-in-time reading of the files listed in §2–§9. Files not explicitly named above (e.g. `BankView`, `MaterialsView`, most of `parser/*`, `sync/CharacterSync`, `sync/ItemSync`, `sync/RecipeSync`, `sync/TpSync` internals, `sync/tp/relevance/*`) were identified and classified by responsibility but not read in full line-by-line detail during this pass. Deeper inspection of those files may refine this document further.
