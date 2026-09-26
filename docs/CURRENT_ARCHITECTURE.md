# GW2 Tool — Current Architecture

## 1. Purpose and Scope

This document describes how the existing implementation is actually structured, based on direct inspection of the source tree under `src/`. It is descriptive, not prescriptive — see `TARGET_ARCHITECTURE.md` for the intended direction and `KNOWN_PROBLEMS.md` for risk analysis.

All statements below are **observed facts** unless explicitly marked as an inference. No source files were modified while producing this document.

---

## 2. Physical Package Layout

```text
src/
├── (default package)        Gw2App, BankView, MaterialsView, EctoView,
│                             CraftingProfitView, CraftingProfitController,
│                             CraftingDiscoveryView, CraftingDiscoveryController,
│                             CraftingResultPresentation
├── application/              CraftingProfitService (Application Layer use case for the Crafting
│                             Profit flow, STORY-APP-001), CraftingDiscoveryService (same for the
│                             Crafting Discovery flow, STORY-APP-002), EctoSalvageService (same for
│                             the Ectoplasm Salvage flow, STORY-APP-003; TARGET_ARCHITECTURE.md §8),
│                             BankContentsService / MaterialStorageService (the bank and
│                             material-storage read use cases, STORY-APP-009),
│                             CharacterSelectionService (the character/crafting-discipline read
│                             both crafting views' selectors are populated from, STORY-APP-010),
│                             AccountRefreshService (STORY-APP-004), GlobalDataRefreshService /
│                             CraftingGraphRebuildService (STORY-APP-005),
│                             TradingPostPriceRefreshService (STORY-APP-006),
│                             InitialSetupService (STORY-APP-007) — the synchronization and
│                             setup use cases, which are also the ones the web sync/refresh
│                             triggers submit as task bodies (§5.7-§5.9),
│                             CraftingResolutionDetail (what one resolution-detail operation of
│                             both crafting services produced, STORY-APP-012, §5.12)
│   └── icons/                ItemIconUrls (the application-relative icon URL format and its route
│                             parsing), IconDelivery / IconDeliveryResult (the icon-delivery
│                             boundary over the filesystem and upstream adapters, STORY-API-009,
│                             §5.14)
├── infra/
│   └── icons/                IconSourcePolicy / IconSource (the canonical upstream-source policy
│                             and derived cache key), IconStore / FilesystemIconStore / StoredIcon /
│                             IconStorageException (the persistent keyed cache),
│                             IconImageFetcher / HttpIconImageFetcher / IconFetchResult /
│                             IconImageBytes (the upstream image adapter and its validation),
│                             IconAcquisition / IconAcquisitionResult (shared miss coordination),
│                             IconCacheBounds (the chosen protective limits) — STORY-API-009, §5.14
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
│                             RecipeKnowledgePolicy, CharacterCraftingProfile,
│                             SingleCraftExplainer, SingleCraftExplanation,
│                             CraftTraceNode, AcquisitionMethod, ResolutionState
│                             (single-craft explanation, §5.12)
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
│                             CraftingGraphCache, CraftingGraphDto,
│                             BankRepository, MaterialStorageRepository (slot-/stack-level
│                             display reads for the Bank and Materials views, STORY-APP-009),
│                             ItemIconMetadataRepository (one item's retained icon source, read
│                             only on an image-cache miss, STORY-API-009)
│   └── tp/                   TpPriceRepository
├── sync/                     AccountSync, CharacterSync, IconSync, ItemSync,
│                             RecipeSync, SyncConstants, TpSync,
│                             DesktopIconAdoption (gives one item a shared keyed icon file for
│                             items.icon_path, STORY-API-009, §5.14)
│   └── tp/relevance/         CraftingProfitItemCollector, DiscoveryItemCollector
├── util/                     CoinUtils, DbBind, TpPrice
├── web/                      Gw2ApiApplication (Spring Boot HTTP entry point),
│                             CraftingProfitApiController, CraftingProfitApiMapper
│                             (STORY-API-001), CraftingDiscoveryApiController,
│                             CraftingDiscoveryApiMapper (STORY-API-002), CraftingRowMapper
│                             (shared row copying), ApiExceptionHandler, ApiValidationException,
│                             AccountSyncApiController, SyncTaskApiController,
│                             SyncTaskStatusMapper, SyncApiExceptionHandler,
│                             UnknownTaskException (STORY-API-003),
│                             GlobalSyncApiController, SyncRequestValidation
│                             (STORY-API-004 — the latter is the parameterless-body rule
│                             both sync triggers share),
│                             PriceRefreshApiController, PriceRefreshVariant
│                             (STORY-API-005 — the latter maps the requested variant to
│                             its use-case method and its operation key),
│                             CraftingSelectorOptionsApiController,
│                             CraftingSelectorOptionsApiExceptionHandler
│                             (STORY-API-006 — the read-only scope-selector route and
│                             its own status mapping),
│                             BankContentsApiController, MaterialStorageApiController,
│                             AccountReadApiExceptionHandler
│                             (STORY-API-007 — the two read-only account-inventory routes
│                             and the status mapping they share),
│                             CraftingResolutionMapper, RecipeNotInCalculationException
│                             (STORY-API-008 — the part of the resolution-detail contract
│                             both crafting controllers share, and its one 404 outcome)
│   ├── task/                 BackgroundTaskService, TaskState, TaskSnapshot,
│   │                         TaskAlreadyRunningException (STORY-API-003) — the in-process
│   │                         background-task facility the sync triggers share; plain Java, no
│   │                         Spring/HTTP/domain import
│   └── dto/                  CraftingProfitRequest/Response, CraftingDiscoveryRequest/Response,
│                             CraftingRowDto, MissingItemDto, TradingPostQuoteDto (shared by both
│                             calculation routes), ApiErrorResponse, SyncTaskAcceptedResponse,
│                             SyncTaskStatusResponse (STORY-API-003),
│                             CraftingSelectorOptionsResponse (STORY-API-006),
│                             BankContentsResponse, MaterialStorageResponse
│                             (STORY-API-007),
│                             CraftingProfitResolutionRequest/Response,
│                             CraftingDiscoveryResolutionRequest/Response, ResolutionNodeDto
│                             (STORY-API-008 — the two detail envelopes and the recursive
│                             node both share) —
│                             transport-only types, distinct from craft.*/repo.*/GW2 JSON
└── resources/                Styles/, images/, application.properties
```

The HTTP layer is named `web`, not `api` as `TARGET_ARCHITECTURE.md` §31 sketches: `api` already denotes the *outbound* GW2 API client in this source tree, so the inbound boundary took a different name rather than overloading it.

The top-level (default, unnamed) Java package contains the JavaFX entry point, all views, both crafting controllers, and two standalone service classes. Java classes in the default package cannot be imported by classes in named packages, which is itself an architectural constraint on how these pieces can be reused - it is why `STORY-APP-003` had to move `EctoSalvageCalculator` into the new `ecto` package for `application.EctoSalvageService` (a named package) to depend on it, while `EctoView` itself stays in the default package.

`Main.java` (the legacy standalone Ecto CLI calculator) was deleted per the resolved `agent/user-decisions/UD-002-ectoplasm-salvage-fee-model.md` (`docs/KNOWN_PROBLEMS.md` §3.6) and no longer exists in the source tree.

The project is now built with Maven (`./mvnw`, Java 25 target), using the standard `src/main/java` / `src/main/resources` / `src/test/java` layout. Jackson, the PostgreSQL driver, JavaFX, JUnit and (since `STORY-API-001`) Spring Boot are Maven dependencies; there is no `lib/` directory of manually-managed JARs anymore. The `spring-boot-dependencies` BOM is imported, so Jackson and JUnit versions are managed by it rather than pinned individually — Spring Boot 4 serves HTTP with Jackson 3 (`tools.jackson`) while the existing GW2/cache code keeps using Jackson 2, and the two share the `com.fasterxml.jackson.annotation` artifact, which a separate pin would desynchronise. A `src/test/java` tree exists, holding the layered suites whose methodology `docs/TEST_STRATEGY.md` owns.

---

## 3. Package Responsibilities

| Package | Observed responsibility |
|---|---|
| default package | JavaFX application shell (`Gw2App`), 5 JavaFX views, 2 feature controllers, and the shared blocked/unavailable-row presentation helper (`CraftingResultPresentation`). The former top-level `InitialSetupService`/`AccountRefreshService` classes no longer exist — that orchestration moved into `application.*` (§4, `STORY-APP-004`/`STORY-APP-007`). |
| `application` | Application Layer use-case orchestration (`TARGET_ARCHITECTURE.md` §8): `CraftingProfitService`, coordinating `repo.*` loading, coordinated-roster construction and `craft.CraftingPlanner`/`RecipeTreeBuilder` invocation for the Crafting Profit flow (§5.1, `STORY-APP-001`); `CraftingDiscoveryService`, the same kind of boundary for the Crafting Discovery flow (§5.2, `STORY-APP-002`) - missing-discoverable-recipe/graph/inventory/price/item loading and single-character `craft.CraftingPlanner.evaluateAll`/`RecipeTreeBuilder` invocation; and `EctoSalvageService`, coordinating `api.tp.EctoLivePriceGateway` price acquisition and `ecto.EctoSalvageCalculator` invocation for the Ectoplasm Salvage flow (§5.3, `STORY-APP-003`). None of the three classes has a JavaFX dependency. |
| `api` | Low-level HTTP client for the GW2 API (`Gw2ApiClient`), batching helper, HTTP status handling, `Gw2PriceFetch` — an ad-hoc price fetcher that has had no caller anywhere in the codebase since `Main` (its only caller) was deleted (see `docs/KNOWN_PROBLEMS.md` §7.8) — and `api.tp.EctoLivePriceGateway`, the live (unsynchronized) Ecto/Dust price lookup used by `application.EctoSalvageService` (`STORY-APP-003`) |
| `craft` | Persistence-independent crafting domain: independent domain types (`Recipe`, `Ingredient`, `PriceQuote`), the recipe-known policy (`RecipeKnowledgePolicy`), recipe selection, inventory consumption, craft-vs-buy decisions, cost/profit math, and resolution tree construction. No JDBC/SQL/repository/transport dependency (`TARGET_ARCHITECTURE.md` §7). |
| `ecto` | Persistence-independent Ectoplasm Salvage domain calculation (`EctoSalvageCalculator`): the fee-inclusive net cost/profit/Luck-cost math (DOMAIN_SPEC.md §45-47). No JDBC/SQL/repository/transport/JavaFX dependency. Moved out of the default package by `STORY-APP-003` so `application.EctoSalvageService` can import it. |
| `model` | Plain data records used mainly during API-response parsing (bank slots, character rows, material stacks, price) |
| `parser` | Converts raw GW2 API `JsonNode` responses into `model` records or repository-ready structures |
| `repo` | PostgreSQL access via JDBC (`Db`) plus per-domain repositories (items, recipes, inventory, characters, TP prices), the persistence-to-domain mapping boundary for `craft.*` types (`TARGET_ARCHITECTURE.md` §10), the crafting-graph JSON cache (`CraftingGraphCache`/`CraftingGraphDto`), and hardcoded configuration (`AppConfig`) |
| `sync` | Orchestrates: call `api` → parse via `parser` → upsert via JDBC directly (not via `repo` repositories) into PostgreSQL, sharing `repo.Db.open()` with the `repo` package. |
| `util` | Coin formatting, JDBC null-binding helpers, a `TpPrice` value type |
| `web` | Inbound HTTP boundary (`TARGET_ARCHITECTURE.md` §9 and §13, `STORY-API-001`/`STORY-API-002`/`STORY-API-003`/`STORY-API-004`/`STORY-API-005`/`STORY-API-006`/`STORY-API-007`/`STORY-API-008`): Spring Boot entry point (`Gw2ApiApplication`), the Crafting Profit and Crafting Discovery routes (`CraftingProfitApiController`, `CraftingDiscoveryApiController`), their request defaulting/validation and DTO translation (`CraftingProfitApiMapper`, `CraftingDiscoveryApiMapper`, plus `CraftingRowMapper` for the row projection both share), the two resolution-detail operations on those same controllers with the input rules, envelope literals and recursive tree copy they share (`CraftingResolutionMapper`, `RecipeNotInCalculationException`), the read-only crafting selector-options route (`CraftingSelectorOptionsApiController`), the two read-only account-inventory routes (`BankContentsApiController`, `MaterialStorageApiController`), the account- and global-synchronization triggers and the Trading Post price-refresh trigger with their shared task-status route and shared body-strictness rule (`AccountSyncApiController`, `GlobalSyncApiController`, `PriceRefreshApiController` with `PriceRefreshVariant`, `SyncTaskApiController`, `SyncTaskStatusMapper`, `SyncRequestValidation`), and the status mapping (`ApiExceptionHandler` for the calculation routes, `SyncApiExceptionHandler` for the synchronization routes, `CraftingSelectorOptionsApiExceptionHandler` for the selector read, `AccountReadApiExceptionHandler` for the two account reads, each scoped to its own controllers). Contains no crafting rule, no synchronization step and no orchestration — each route calls its existing application service and copies what comes back. `web.dto` holds transport-only records. |
| `web.task` | The in-process background-task facility the synchronization triggers share (`STORY-API-003`, `TARGET_ARCHITECTURE.md` §23): identifier issue, lifecycle state, one-unfinished-task-per-operation admission, virtual-thread execution and bounded in-memory retention (`BackgroundTaskService`, `TaskState`, `TaskSnapshot`, `TaskAlreadyRunningException`). Plain Java — no Spring, servlet, HTTP or domain import — so the asynchrony is testable without a server and reusable by later sync/refresh routes. |

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

Gw2App --imports--> application.AccountRefreshService / application.GlobalDataRefreshService /
                     application.InitialSetupService   (no sync.* or repo.* import remains; the
                     former top-level InitialSetupService/AccountRefreshService classes are deleted)

HTTP client
   |
   v
web.Gw2ApiApplication (Spring Boot / embedded Tomcat)
   |
   v
web.CraftingProfitApiController    --> application.CraftingProfitService     (the same use cases
web.CraftingDiscoveryApiController --> application.CraftingDiscoveryService   the JavaFX
   |                                                                          controllers call)
   v
web.CraftingProfitApiMapper    --> web.dto.*  (translation only; imports craft.*/repo.DiscChoice/
web.CraftingDiscoveryApiMapper                 repo.ItemRepository.ItemInfo to read them, never to
   |                                           hand them to a client)
   v
web.CraftingRowMapper --> web.dto.CraftingRowDto  (the recipe+result projection both routes share)

the same two controllers' detail operations (STORY-API-008)
   |  --> application.CraftingProfitService.resolveDetail / CraftingDiscoveryService.resolveDetail
   v
web.CraftingResolutionMapper --> web.dto.ResolutionNodeDto / web.CraftingRowMapper
                                 (copy only; reads application.CraftingResolutionDetail and
                                  craft.CraftTraceNode/AcquisitionMethod/ResolutionState/
                                  BlockedReason to read them, never to hand them to a client)

web.CraftingSelectorOptionsApiController --> application.CharacterSelectionService  (the same
   |                                                selector read the JavaFX views call)
   v
web.dto.CraftingSelectorOptionsResponse  (copy only; reads repo.CharacterRepository.DiscRow,
                                          never hands it to a client)

web.BankContentsApiController    --> application.BankContentsService    (the same two reads the
web.MaterialStorageApiController --> application.MaterialStorageService  JavaFX Bank/Materials
   |                                                                    views call)
   v
web.dto.BankContentsResponse / web.dto.MaterialStorageResponse  (copy only; read
                                          repo.BankRepository.BankSlotRow and
                                          repo.MaterialStorageRepository.MaterialStorageRow,
                                          never hand them to a client)

web.AccountSyncApiController --> web.task.BackgroundTaskService  (submits the task)
   |                         --> application.AccountRefreshService::refreshAll  (the task body, the
   |                                                                            same use case JavaFX calls)
   |
web.GlobalSyncApiController  --> web.task.BackgroundTaskService  (submits the task, own operation key)
   |                         --> application.GlobalDataRefreshService::refreshAll  (the task body, the
   |                                                                               same use case JavaFX calls)
   |
web.PriceRefreshApiController --> web.PriceRefreshVariant  (request value -> use case + operation key)
   |                          --> web.task.BackgroundTaskService  (submits the task, one key per variant)
   |                          --> application.TradingPostPriceRefreshService::refreshForProfit
   |                              / ::refreshForDiscovery  (the task body, the same two use cases
   |                                                        the JavaFX buttons call)
   |
   |  all three --> web.SyncRequestValidation  (the shared body-strictness rule)
   v
web.SyncTaskApiController --> web.task.BackgroundTaskService.find(id)
   |
   v
web.SyncTaskStatusMapper --> web.dto.SyncTaskStatusResponse / web.dto.ApiErrorResponse

web.task.* --imports--> nothing outside java.* (no Spring, servlet, HTTP or application/domain type)
```

Key observed facts about dependency direction:

- **`CraftingProfitController` no longer imports `repo.*` repositories, `craft.CraftingPlanner`, or `craft.RecipeTreeBuilder` directly (`STORY-APP-001`).** It imports and holds one `application.CraftingProfitService`, which owns those repository/planner/tree-builder calls; the controller keeps only presentation formatting (`UiRow`/`prepareRows`) and the small `ItemRepository.ItemInfo`/`PriceQuote` lookup maps that formatting needs.
- **`CraftingDiscoveryController` no longer imports `repo.RecipeRepository`/`InventoryRepository`/`TpPriceRepository`/`ItemRepository`/`CraftingGraphCache`, or `craft.CraftingPlanner`/`RecipeTreeBuilder`/`PlannerContext` directly (`STORY-APP-002`).** It imports and holds one `application.CraftingDiscoveryService`, which owns those calls; the controller keeps only presentation formatting (`UiRow`/`prepareRows`, missing-summary text, the item-name search blob) and delegates `getResultByRecipeId(...)`/`itemName(...)`/`itemSellUnit(...)` straight through to the service, which is also where that lookup state (`lastItems`/`lastTp`/`lastAllRecipes`/etc.) now lives — including the pre-existing "no missing recipes for this character+discipline" short-circuit that leaves that lookup state untouched rather than clearing it (unchanged behavior, preserved verbatim). The application boundary now covers both crafting flows.
- **The `craft` package (the domain/business-logic layer) no longer imports or depends on any `repo.*` type (`STORY-DOM-017`).** Independent domain types `craft.Recipe`, `craft.Ingredient`, and `craft.PriceQuote` replace the formerly repository-nested classes; `repo.RecipeRepository`/`repo.tp.TpPriceRepository` own the persistence-to-domain mapping (`TARGET_ARCHITECTURE.md` §10). `CraftingGraphCache`/`CraftingGraphDto` (the on-disk crafting-graph cache, which depends on `RecipeRepository` and Jackson) moved to `repo.*` for the same reason. Verified by dependency inspection: no `src/main/java/craft/*.java` or `src/test/java/craft/*.java` file imports `repo.*`.
- `repo.RecipeRepository.loadRecipes(...)`/`loadRecipesForCharacter(...)`/`loadMissingDiscoverableRecipeIdsForCharacter(...)` fetch plain unlock facts (`account_recipes`/`character_recipes` ids) and delegate the "recipe is unlocked" decision to `craft.RecipeKnowledgePolicy.isKnownAccountWide` (see §9 item 2) rather than expressing it as SQL. All three entry points now use the same account-wide fact (`STORY-DOM-019`).
- `sync` and `repo` are two independent, parallel data-access areas, but both now share the same `repo.Db.open()` connection helper (`sync.Db` was removed by `STORY-INFRA-003`), which reads `repo.AppConfig` credentials.
- Views call controllers; in the two crafting features (profit/discovery) the controllers no longer call `repo.*` repositories (including `repo.CraftingGraphCache`) or `craft.CraftingPlanner` themselves — their application services do (see the two bullets above), and the controllers keep only the `repo.DiscChoice`/`repo.ItemRepository.ItemInfo` types their formatting needs. Both crafting **views** obtain their selector contents through `application.CharacterSelectionService` as well (`STORY-APP-010`): neither view instantiates or queries `repo.CharacterRepository` any more, and no view in the codebase constructs a repository. Both still use `craft.*` result/settings types (`CraftResult`, `CraftingSettings`, `Node`, plus `Recipe`/`Ingredient` in Discovery) for rendering, and the selector read hands back the `repo.CharacterRepository.DiscRow` persistence row type — see `docs/KNOWN_PROBLEMS.md` §4.4 (CH-E2, both the selector portion and the total-profit duplication resolved). `CraftingProfitView` displays and sorts the total profit supplied by `CraftingProfitController.UiRow` (`STORY-APP-011`); it no longer derives that figure itself.
- `EctoView` does not go through any repository or controller; it calls one `application.EctoSalvageService` (`STORY-APP-003`), which fetches Ecto/Dust quotes via `api.tp.EctoLivePriceGateway` (a plain `HttpClient` call, not `repo.tp.TpPriceRepository`/`api.Gw2ApiClient`) and delegates its calculation to `ecto.EctoSalvageCalculator`, which has no JavaFX/repo/controller/application dependency (see §7). Icon fetching remains a direct `HttpClient` call inside the view itself (presentation-only, not part of the price/calculation use case).
- `BankView` and `MaterialsView` each call one application service (`application.BankContentsService`, `application.MaterialStorageService`, `STORY-APP-009`) instead of connecting to the database themselves. Each service coordinates one persistence adapter (`repo.BankRepository`, `repo.MaterialStorageRepository`) that holds the SQL and opens its connection via the shared `repo.Db.open()` → `repo.AppConfig`/`EnvConfig` path. Both views' former literal `DB_URL`/`DB_USER`/`DB_PASS` constants and inline `DriverManager` calls are gone (`docs/KNOWN_PROBLEMS.md` §2.2, resolved); neither view contains SQL or a `java.sql` import any more. Both flows are read-only — no write and no synchronization on either path. `STORY-API-007` added an HTTP entry point over each of the same two services (`web.BankContentsApiController`, `web.MaterialStorageApiController`, §5.12) without touching the services, the repositories or the views: the two paths call the identical method and the JavaFX one still runs in process.
- `Gw2App`'s three sync/setup button handlers call only `application.*` services; the `sync.*` calls and the `repo.CraftingGraphCache` rebuild now sit behind those services' gateways/collaborators (§5.4, §9 item 4). The former top-level `InitialSetupService` and `AccountRefreshService` classes no longer exist.
- **`web.*` is the only package that imports Spring (`STORY-API-001`, re-verified by `STORY-API-002`, `STORY-API-003`, `STORY-API-007` and `STORY-API-008`).** No `application.*`, `craft.*`, `ecto.*`, `repo.*` or `sync.*` class imports a Spring, servlet or HTTP-transport type, and no JavaFX class imports `web.*`: the two entry points (`Gw2App`, `web.Gw2ApiApplication`) sit side by side over the same application services, and the JavaFX path is unchanged by every one of those stories. The dependency runs one way — `web.*` → `application.*` → `craft.*`/`repo.*`.
- **The background-task concern sits inside `web.*`, not in the application layer (`STORY-API-003`, unchanged by `STORY-API-004` and `STORY-API-005`).** `web.task.BackgroundTaskService` imports only `java.*`, and `application.AccountRefreshService`/`application.GlobalDataRefreshService`/`application.TradingPostPriceRefreshService` are unchanged and unaware of it — each is handed to the facility as a method reference. Task identifiers, lifecycle state and the admission rule are therefore transport-boundary concepts that neither the JavaFX path nor the application layer can observe: `Gw2App`'s "Sync Account" and "Sync ALL tradeable Items…" buttons and both crafting views' "Refresh Trade Post Prices" buttons still call their use cases in process and synchronously, exactly as before. Adding the second and third triggers needed no change to the facility at all — only further operation keys.

---

## 5. Major Data Flows

### 5.1 Crafting Profit flow

Performance measurement must span the existing view → controller → `application.CraftingProfitService` → repository/domain → presentation/rendering path described below. The measured latency and verification status are owned by `KNOWN_PROBLEMS.md` §7.9; the Phase 3 acceptance requirement is owned by `TARGET_ARCHITECTURE.md` §33. Measured compliance with the §33 limit and the Product Owner's required subsequent dated confirmation both exist; `STORY-PERF-001`'s Result owns that evidence.

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
   -> selector population (STORY-APP-010, once per page open, independent of the reload below):
        application.CharacterSelectionService.getCraftingCharacterOptions()
             -> CharacterRepository.loadAllCharacterCrafting()
        -> view builds "All" + the fixed nine base disciplines + one CHAR_DISCIPLINE entry per
             returned row, then selects "All"
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

`STORY-APP-012` added a second, independent entry point on the same service —
`resolveDetail(recipeId, choice, settings)`, described in §5.12 — which runs its own fresh load
and calculation for one selected recipe. It shares this flow's loading steps but none of the
reload-scoped `last*` state above, and the JavaFX path is unchanged.

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
   -> selector population (STORY-APP-010, once per page open, independent of the reload below):
        application.CharacterSelectionService.getCraftingCharacterOptions()
             -> CharacterRepository.loadAllCharacterCrafting()  [CHAR_DISCIPLINE entries only]
        application.CharacterSelectionService.getCharacterNames()
             -> CharacterRepository.loadAllCharacterNames()     [separate Character selector]
        -> each selector selects its first entry, if any; those selections are what start the
             first reload
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

`STORY-APP-012` added a second, independent entry point on the same service —
`resolveDetail(recipeId, choice, settings, inventoryCharacterName)`, described in §5.12 — which
runs its own fresh load and calculation for one selected recipe, keeping the separate nullable
inventory character with the same unfiltered-pool fallback. It shares this flow's loading steps
but none of the reload-scoped `last*` state above, and the JavaFX path is unchanged.

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

### 5.5 Crafting Profit HTTP flow (`STORY-API-001`)

A second, additive entry point over the same application service. `web.Gw2ApiApplication` is a Spring
Boot application (Spring Boot 4.1.1 / Spring Framework 7, embedded Tomcat) started with
`./mvnw spring-boot:run`; the JavaFX `Gw2App` entry point and its in-process calls are untouched.

```text
POST /api/crafting/profit
   |
   v
web.CraftingProfitApiController
   |  1. CraftingProfitApiMapper.toEffective(request)   defaults + validation (no service yet)
   |  2. Supplier<CraftingProfitService>.get()          one fresh service per request
   |  3. service.reload(DiscChoice, CraftingSettings)   the existing use case, unmodified
   |  4. CraftingProfitApiMapper.toResponse(...)        copy ProfitData into web.dto records
   v
200 CraftingProfitResponse | 400 | 503 | 500
```

Observed properties of this flow:

- **Optional body, documented defaults.** An absent or empty body runs the default All scope with the
  same settings the JavaFX Profit view opens with (`useOwnMats` true, `allowBuying` false,
  `maxBuyCopper` 10000, instant sell, instant buy, daily items bought). The response echoes the
  effective scope and settings.
- **Scope kinds** `ALL`, `DISCIPLINE`, `CHARACTER_DISCIPLINE` map onto `repo.DiscChoice`'s three
  factory methods. A scope that matches no recipes is an empty 200, not a 404.
- **Status mapping** (`web.ApiExceptionHandler`, scoped to this controller so framework 404/405
  handling is unaffected): 400 `INVALID_REQUEST` / `MALFORMED_REQUEST` (raised before any service is
  created, so an invalid request starts no calculation), 503 `DATA_STORE_UNAVAILABLE` for a
  `SQLException`, 500 `CALCULATION_FAILED` otherwise. Only validation messages reach the caller;
  failure detail is logged server-side.
- **No shared request state.** `CraftingProfitService` keeps the last reload's data in instance
  fields, so the controller holds a `Supplier<CraftingProfitService>` and builds one per request
  rather than sharing a bean. The graph cache is re-read per reload in either entry point, so this
  costs no more than the JavaFX path already does.
- **Response content.** One row per visible recipe, in repository order, carrying the domain's
  authoritative numbers (including `totalProfitCopper` and, since `STORY-WEB-008`,
  `totalSellValueCopper` — neither recomputed at this boundary from a count × a per-craft figure;
  `craft.CraftingPlanner` carries the sell value onto `craft.CraftResult` from `DOMAIN_SPEC.md` §25's
  per-execution output revenue and §28's craftable count, fee-free and with the recipe's output
  quantity in it once), the `craft.BlockedReason` name, both missing-material maps (sorted by item id for a
  stable wire order), the raw trading-post quotes and, since `STORY-API-009` (§5.14), a nullable
  `iconUrl` on the row (the recipe's **output item**) and on each missing material (its own item).
  Rows with no calculation result are reported
  with `resultAvailable: false` instead of being dropped. Presentation text produced by the JavaFX
  layer (status strings, "no TP" markers, search blobs) is deliberately absent — it is a rendering
  rule, not domain state. The per-row resolution tree (`TARGET_ARCHITECTURE.md` §13) is also not on
  this route: the application service builds it lazily per selected row, so it belongs to the
  separate detail route of §5.13, which this response still does not eagerly attach. Since `STORY-API-002` the row itself is `web.dto.CraftingRowDto`, shared with
  Discovery (§5.6) and copied by the shared `web.CraftingRowMapper`; the JSON field names are
  unchanged.
- **Synchronous, by measurement.** Per `TARGET_ARCHITECTURE.md` §23 / `UD-007`, the sync/async choice
  for a non-GW2-API endpoint follows measured behavior. Measured against the real database, this
  request completes well inside a normal HTTP request (see `STORY-API-001`'s Result for the
  figures), so it is served synchronously with no background task or status endpoint.

### 5.6 Crafting Discovery HTTP flow (`STORY-API-002`)

The second calculation route on the same runtime, over the same `application.CraftingDiscoveryService`
the JavaFX page uses (§5.2). JavaFX Discovery is untouched and keeps its in-process calls.

```text
POST /api/crafting/discovery
   |
   v
web.CraftingDiscoveryApiController
   |  1. CraftingDiscoveryApiMapper.toEffective(request)     defaults + validation (no service yet)
   |  2. Supplier<CraftingDiscoveryService>.get()            one fresh service per request
   |  3. service.reload(DiscChoice, CraftingSettings,        the existing use case, unmodified
   |                    inventoryCharacterName)
   |  4. CraftingDiscoveryApiMapper.toResponse(...)          copy DiscoveryData into web.dto records
   v
200 CraftingDiscoveryResponse | 400 | 503 | 500
```

Observed properties of this flow:

- **Required body, individual-character scope.** Unlike Profit, there is no All reading and no
  default scope: `scope.discipline`, `scope.characterName` and `scope.rating` are all required, and
  the mapper always builds `DiscChoice.charDiscipline(...)`. This mirrors the JavaFX page, whose
  selector is populated with `CHAR_DISCIPLINE` entries only and which refuses to calculate without
  one (§5.2). `scope.rating` is required rather than defaulted because it drives the
  `recipe.minRating <= rating` filter — defaulting it to 0 would silently answer with an emptier
  result than the caller asked for.
- **Separate inventory character.** `inventoryCharacterName` is optional and independent of
  `scope.characterName`, matching Discovery's second selector: supplied, it reaches the binding-aware
  owned-inventory lookup (`DOMAIN_SPEC.md` §11.1 / DQ-007); omitted or blank, it reaches the service
  as null, which keeps the service's own unfiltered-pool fallback (`STORY-DOM-012`) rather than the
  API inventing a substitute.
- **Discovery's own defaults**, taken from the JavaFX Discovery view's opening control state and
  deliberately *not* Profit's: `useOwnMats` true, `allowBuying` **true**, `maxBuyCopper` **200000**
  (its "20g" field), instant sell, instant buy. `dailyBuyInsteadOfCraft` is not a request field at
  all — the Discovery flow has always fixed it to false — but the value used is echoed in the
  response's effective settings.
- **Empty result is a 200.** A character+discipline with nothing left to discover returns
  `rowCount: 0`, which is the transport form of the service's own "no missing recipes" short-circuit.
  No missing-resource (404) case exists on this route and none was invented; 404 remains the
  framework's unknown-path response.
- **Status mapping** is the shared `web.ApiExceptionHandler`, now scoped to both calculation
  controllers: 400 `INVALID_REQUEST` / `MALFORMED_REQUEST` (raised before any service is created),
  503 `DATA_STORE_UNAVAILABLE`, 500 `CALCULATION_FAILED`. Error codes are identical on both routes so
  a caller need not branch on which one it called.
- **No shared request state.** `CraftingDiscoveryService` keeps its last reload's lookup data in
  instance fields *and deliberately leaves them untouched when a reload finds nothing to discover*,
  so a shared instance could answer an empty request with an earlier request's rows. The controller
  therefore holds a `Supplier<CraftingDiscoveryService>` and builds one per request.
- **Response content** is the same `web.dto.CraftingRowDto` row as Profit (§5.5), produced by the
  same `web.CraftingRowMapper`, plus the echoed effective scope, inventory character and settings.
  Recipes whose calculation produced no result are reported with `resultAvailable: false` — where the
  JavaFX Discovery table drops them — so a client sees the complete recipe set.
- **Synchronous, by measurement.** Per `TARGET_ARCHITECTURE.md` §23 / `UD-007`, measured across every
  synced discipline+character combination on the real database (figures in `STORY-API-002`'s Result),
  including the largest result set. Comfortably inside a normal HTTP request, so it is served
  synchronously with no background task or status endpoint.

### 5.7 Account synchronization HTTP flow (`STORY-API-003`)

The first *asynchronous* route on this runtime, over the same `application.AccountRefreshService.refreshAll()`
the JavaFX "Sync Account" button calls (§5.4). JavaFX is untouched and keeps calling it in process and
synchronously.

```text
POST /api/sync/account                        GET /api/sync/tasks/{taskId}
   |                                             |
   v                                             v
web.AccountSyncApiController               web.SyncTaskApiController
   |  1. reject any request field                |  1. BackgroundTaskService.find(taskId)
   |     (no task exists yet)                    |  2. SyncTaskStatusMapper.toResponse(...)
   |  2. BackgroundTaskService.submit(            v
   |       "ACCOUNT_SYNC",                    200 SyncTaskStatusResponse | 404
   |       accountRefreshService::refreshAll)
   v                                    (background virtual thread)
202 SyncTaskAcceptedResponse            application.AccountRefreshService.refreshAll()
    + Location header                        -> bank, materials, recipes, characters
    | 400 | 409                                 (order, persistence and short-circuiting
                                                 unchanged, inside the service)
```

Observed properties of this flow:

- **Asynchronous by decision, not by measurement.** `UD-007` / `TARGET_ARCHITECTURE.md` §23 require GW2
  API synchronization to run as a backend task with status reporting, so no duration was measured or
  assumed to reach that choice — unlike the two calculation routes (§5.5, §5.6), whose synchronous
  handling *was* measured.
- **Acceptance is not completion.** 202 returns `taskId`, `operation` and `statusUrl` (also sent as the
  `Location` header) and deliberately carries no outcome field of any kind. The synchronization's success
  or failure is only ever readable from the status route.
- **No request inputs, enforced.** The use case takes no parameters, so the body must be absent or `{}`;
  any field is rejected with 400 `INVALID_REQUEST` rather than silently ignored. Unreadable JSON is 400
  `MALFORMED_REQUEST`. Both are raised before a task exists, so a rejected request synchronizes nothing.
- **Overlapping triggers are refused, not queued.** While an `ACCOUNT_SYNC` task is `PENDING` or
  `RUNNING`, a second trigger is 409 `SYNC_ALREADY_RUNNING` and the message names the task already doing
  the work. The sequence writes account-wide rows and is GW2-API rate limited, so a second concurrent run
  would duplicate the same work against the same data.
- **Status distinguishes four states**, not two: `PENDING`, `RUNNING`, `SUCCEEDED`, `FAILED`, with
  `startedAt` set from `RUNNING` on and `finishedAt` only once terminal. A known identifier is always
  200 — a failed task is a successful *lookup* — and the state, never the HTTP status, carries the
  outcome. An identifier this process cannot resolve is 404 `TASK_NOT_FOUND`.
- **Failure is reported without implying rollback.** `refreshAll()` is a straight-line sequence that
  stops at its first failing step and has never had partial-completion handling, so a failure can leave
  earlier steps' writes in place. The failure message says exactly that ("steps that had already
  completed were not rolled back"). What the route cannot say is *which* steps completed: the service
  exposes no step-level progress, and inventing one would mean duplicating its orchestration at the
  boundary.
- **Error disclosure matches the calculation routes.** The failure body is the same
  `web.dto.ApiErrorResponse`; a `SQLException` keeps the shared `DATA_STORE_UNAVAILABLE` code, anything
  else is `SYNC_FAILED`, and the exception's own text (GW2 endpoints, hosts, JDBC URLs) is logged
  server-side only. `SyncApiExceptionHandler` is a second advice rather than an extension of
  `ApiExceptionHandler`, because that one's messages and its `CALCULATION_FAILED` describe a crafting
  calculation; both are scoped with `assignableTypes`, so the framework's own 404/405 responses are
  untouched on either.
- **The task facility is in-memory and process-local** (`web.task.BackgroundTaskService`): one virtual
  thread per task, at most one unfinished task per operation key, and the most recent 100 records
  retained — older terminal records are evicted on submission, and an unfinished one is never evicted.
  Nothing is persisted, so a restart loses every record and shutdown interrupts a task in flight; work
  already committed to the database stays committed. An evicted identifier, one from a previous process
  and one that never existed are all the same 404, because the facility genuinely cannot tell them apart.
  The status route is shared by operation key rather than owned by this trigger, so a later sync/refresh
  route needs no status route of its own.

### 5.8 Global-data synchronization HTTP flow (`STORY-API-004`)

The second asynchronous route, over the same `application.GlobalDataRefreshService.refreshAll()` the
JavaFX "Sync ALL tradeable Items, Recipes and (re)build Crafting Graph" button calls (§5.4). JavaFX is
untouched and keeps calling it in process and synchronously.

It reuses §5.7's boundary rather than restating it: the same `web.task.BackgroundTaskService` (unchanged),
the same `GET /api/sync/tasks/{taskId}` status route, the same `SyncTaskStatusMapper`, the same
`SyncApiExceptionHandler` (which gained only this controller in its `assignableTypes` scope) and the same
acceptance/status DTOs. The trigger itself is the only new route.

```text
POST /api/sync/global                         GET /api/sync/tasks/{taskId}
   |                                             |   (the same route as §5.7 — shared, not duplicated)
   v                                             v
web.GlobalSyncApiController                web.SyncTaskApiController
   |  1. SyncRequestValidation:                  |  1. BackgroundTaskService.find(taskId)
   |     reject any request field                |  2. SyncTaskStatusMapper.toResponse(...)
   |     (no task exists yet)                    v
   |  2. BackgroundTaskService.submit(        200 SyncTaskStatusResponse | 404
   |       "GLOBAL_SYNC",
   |       globalDataRefreshService::refreshAll)
   v                                    (background virtual thread)
202 SyncTaskAcceptedResponse            application.GlobalDataRefreshService.refreshAll()
    + Location header                        -> tradeable items, global recipes, graph rebuild
    | 400 | 409                                 (order, persistence, the rebuild and
                                                 short-circuiting unchanged, inside the service)
```

Everything §5.7 records about acceptance-is-not-completion, the enforced empty body, the four states, the
always-200 known identifier, the 404, the no-rollback failure wording and the error-disclosure convention
holds here verbatim, with `operation` reading `GLOBAL_SYNC`. What is specific to this flow:

- **Operation key `GLOBAL_SYNC`**, reported in both the acceptance and the status body. While such a task
  is `PENDING` or `RUNNING`, a second global trigger is 409 `SYNC_ALREADY_RUNNING` naming that task. The
  reason is stronger than for account sync: the sequence rewrites the account-independent
  `tp_tradeable_items`, `recipes` and `recipe_ingredients` rows and then rebuilds the single
  `crafting_graph_cache.json`, so two concurrent runs would repeat the same rate-limited GW2 API work and
  have two rebuilds writing that one cache file.
- **Independent of `ACCOUNT_SYNC`.** Different keys, so the facility's per-key rule lets a global and an
  account sync be accepted and run at the same time; each refuses only a second submission of its own
  operation, and the two remain separately identifiable through the one status route. This is the existing
  admission policy applied, not a new judgement that the two are safe together. What is observable is that
  they write disjoint tables (global as above, account `account_*`/`character*`) and that they share the
  GW2 API rate budget and the database, so running both at once makes each slower. No cross-operation
  exclusion was introduced.
- **The graph rebuild stays inside the application service.** The route submits one
  `refreshAll()` and nothing else; no controller calls `CraftingGraphRebuildService`, any
  `sync.GlobalDataRefreshGateway` step, or `repo.CraftingGraphCache`. Step order and the first-failure
  short-circuit are `application.GlobalDataRefreshService`'s, unmodified by this story.
- **No step-level progress, for the same reason as §5.7.** A `FAILED` global task does not say whether the
  failure was in tradeable items, recipes or the rebuild, and its message says completed steps were not
  rolled back — which for this sequence can mean refreshed item/recipe rows with a graph cache still
  reflecting the previous data.
- **The parameterless-body rule is shared, not copied.** `web.SyncRequestValidation` holds it for both
  triggers and names the offending route in the message, so the two cannot drift into answering the same
  malformed request differently.

### 5.9 Trading Post price-refresh HTTP flow (`STORY-API-005`)

The third asynchronous route, and the first that takes a request input. It sits over the same two
`application.TradingPostPriceRefreshService` methods the two crafting views' "Refresh Trade Post Prices"
buttons call (§5.4, §6). JavaFX is untouched and keeps calling both in process and synchronously.

It reuses §5.7's boundary exactly as §5.8 does: the same unchanged `web.task.BackgroundTaskService`, the
same `GET /api/sync/tasks/{taskId}` status route, the same `SyncTaskStatusMapper`, the same
`SyncApiExceptionHandler` (which gained only this controller in its `assignableTypes` scope) and the same
acceptance/status DTOs. The trigger is the only new route.

```text
POST /api/prices/refresh                      GET /api/sync/tasks/{taskId}
  {"variant": "PROFIT" | "DISCOVERY"}            |   (the same route as §5.7 — shared, not duplicated)
   |                                             v
   v                                        web.SyncTaskApiController
web.PriceRefreshApiController                    |  1. BackgroundTaskService.find(taskId)
   |  1. SyncRequestValidation: reject any       |  2. SyncTaskStatusMapper.toResponse(...)
   |     field other than "variant"              v
   |  2. PriceRefreshVariant.ofRequestValue   200 SyncTaskStatusResponse | 404
   |     (required; no third value)
   |     (no task exists yet at either step)
   |  3. BackgroundTaskService.submit(
   |       "PRICE_REFRESH_PROFIT" | "PRICE_REFRESH_DISCOVERY",
   |       priceRefreshService::refreshForProfit | ::refreshForDiscovery)
   v                                    (background virtual thread)
202 SyncTaskAcceptedResponse            application.TradingPostPriceRefreshService.refreshForProfit()
    + Location header                     or .refreshForDiscovery()
    | 400 | 409                              -> relevance collection, batched quote fetching and
                                                the tp_prices upsert, unchanged, inside the
                                                service and sync.TpSync
```

Everything §5.7 records about acceptance-is-not-completion, the four states, the always-200 known
identifier, the 404, the failure wording and the error-disclosure convention holds here verbatim, with
`operation` reading the requested variant's key. What is specific to this flow:

- **The variant is a required request input, and there are exactly two.** `web.PriceRefreshVariant` maps
  `PROFIT` → `refreshForProfit()` and `DISCOVERY` → `refreshForDiscovery()`, and nothing else. A missing,
  blank, differently-cased, non-string or unrecognised value — including a combined "all prices" value —
  is 400 `INVALID_REQUEST`. **No implicit all-prices operation exists**: the two workflows select
  different item sets for different pages, so a request that named neither would have to invent a third
  workflow at the HTTP boundary. This is the first sync/refresh route with a field, so
  `SyncRequestValidation` gained `rejectUnknownRequestFields(route, request, "variant")` alongside the
  parameterless rule the other two use; any other field (`itemIds`, say) is rejected rather than silently
  ignored, so a caller cannot believe it narrowed the refresh. Both 400s are raised before
  `submit(...)`, so a rejected request refreshes nothing and calls neither variant.
- **Two operation keys, `PRICE_REFRESH_PROFIT` and `PRICE_REFRESH_DISCOVERY`**, reported in both the
  acceptance and the status body. The facility's existing per-key rule therefore refuses a second refresh
  *of the same variant* while one is unfinished (409 `SYNC_ALREADY_RUNNING` naming that task) and leaves
  the two variants, and the two sync operations, independent of each other.
- **The concrete shared write behind that choice.** Both variants write the same table, `tp_prices`, so
  the independence is not a claim that they never touch the same row. What is observable: the write is
  `INSERT … ON CONFLICT (item_id) DO UPDATE` and there is no `DELETE` on the path, so overlapping runs
  can only overwrite a row with another freshly fetched quote for that same `item_id` — they cannot lose
  a row, mix two items' quotes or leave a partially written row. The two item sets are selected
  independently and may overlap. This concurrency is already reachable today without this route: both
  JavaFX buttons exist and neither excludes the other. What the two runs do contend for is the GW2
  commerce rate budget and the database, so running both at once makes each slower. No cross-operation
  exclusion was introduced; introducing one would be a product decision this route does not own.
- **A succeeded task does not mean prices were fetched.** Each variant's relevance collector skips items
  whose stored quote is younger than ten minutes (`sync.TpSync`), so a refresh issued shortly after
  another one can legitimately select no items and succeed having called the GW2 API not at all. The
  service reports no item count, so neither does the route.
- **A failed task says nothing about how much was written.** The refresh commits per fetched batch, so
  earlier batches stay committed — which is exactly what the shared "steps that had already completed
  were not rolled back" wording says, and no more. How many items were refreshed before the failure is
  not something the service exposes, and manufacturing a count at the boundary would mean duplicating its
  orchestration.
- **Item selection stays out of the route.** The controller submits one method reference and nothing
  else; relevance collection (`sync.tp.relevance.*`), batching, quote fetching and persistence are not
  reachable from `web.*`, and no calculation runs on this path.

### 5.10 Crafting selector-options HTTP flow (`STORY-API-006`)

The first read-only route that is not a calculation: the options a browser needs to build Crafting
Profit's single Discipline scope selector (`DOMAIN_SPEC.md` §2.2.1) and post a scope back to §5.5.
It sits over the same `application.CharacterSelectionService.getCraftingCharacterOptions()` the two
JavaFX selectors call (§5.1, §5.2, §6); JavaFX is untouched and keeps calling it in process.

```text
GET /api/crafting/selector-options
   |
   v
web.CraftingSelectorOptionsApiController
   |  1. CharacterSelectionService.getCraftingCharacterOptions()   the existing read, unmodified
   |  2. field-for-field copy into web.dto records                 no filter, sort or default
   v
200 CraftingSelectorOptionsResponse | 503 | 500
```

Observed properties of this flow:

- **No request input at all.** A GET with no body and no parameter, so the route has no 400 path and
  no validation step — there is nothing a caller can get wrong.
- **Response fields.** `defaultScopeKind` (`"ALL"`, read from `CraftingProfitApiMapper`'s own default
  so the two routes cannot disagree about which entry is preselected); `disciplines`, the nine generic
  discipline entries in the order the JavaFX selector lists them; `characterOptionCount`; and
  `characterOptions`, one `{characterName, discipline, rating, active}` record per synced character
  discipline. The first three character fields are exactly what a `CHARACTER_DISCIPLINE` scope needs
  in §5.5's request body; `active` is reported because it is a service-provided fact the caller cannot
  re-derive, and no rule is applied to it here.
- **Service order and values are preserved verbatim.** The controller runs one `map` over the returned
  rows — no sorting, no grouping, no deduplication, no eligibility test — so the character entries
  arrive in the repository's `discipline, rating DESC, name` order, the same order the JavaFX selector
  shows.
- **Empty is a 200, and nothing is substituted.** A database with no synced characters returns
  `characterOptionCount: 0` with an empty `characterOptions` list; `defaultScopeKind` and the nine
  `disciplines` are unaffected, which matches the JavaFX selector still offering All plus the generic
  entries when nothing is synced. No character is invented as a fallback, and no 404 case exists.
- **The generic discipline list is mirrored at the boundary**, in
  `CraftingSelectorOptionsApiController.GENERIC_DISCIPLINES`, for the same reason §5.5's defaults are:
  it is the JavaFX view's established presentation list, not a fact any application service reports,
  and both entry points must offer the caller the same choices. Selecting one still means a
  `DISCIPLINE` scope covering all synced characters having that discipline; the route narrows nothing.
- **Status mapping** is its own advice, `web.CraftingSelectorOptionsApiExceptionHandler`, scoped to
  this controller: 503 `DATA_STORE_UNAVAILABLE` for a `SQLException` — the same code and message as
  §5.5/§5.6, since it means the same thing — and 500 `SELECTOR_OPTIONS_FAILED` otherwise. A separate
  advice for the reason `SyncApiExceptionHandler` already records: `ApiExceptionHandler`'s 500
  `CALCULATION_FAILED` describes a crafting calculation, which is the wrong thing to tell a caller
  about a selector read, and the calculation routes' contract is not reworded to make room for this
  one. Both messages are fixed; failure detail is logged server-side only.
- **One shared service instance.** Unlike the calculation routes, `CharacterSelectionService` keeps no
  per-call state (it holds only `repo.CharacterRepository`), so there is no reload result for
  concurrent requests to observe and no per-request factory is needed.

---

## 5.11 Browser frontend (`STORY-WEB-001`, `STORY-WEB-002`, `STORY-WEB-003`, `STORY-WEB-004`, `STORY-WEB-005`, `STORY-WEB-006`, `STORY-WEB-007`)

The browser client of the HTTP API. It lives in `frontend/`, entirely outside the Maven
module — no Java source, build step or package depends on it, and removing the directory would
leave the backend and JavaFX untouched.

**Technology as built.** Vue 3 single-file components with the Composition API and
`<script setup lang="ts">`, TypeScript in `strict` mode (plus `noUncheckedIndexedAccess`,
`noUnusedLocals`, `noUnusedParameters`, `verbatimModuleSyntax`), built by Vite. Dependency versions
are locked in `frontend/package-lock.json`. `vue` is the only runtime dependency; everything else is
a build- or test-time tool. `vite build` emits static assets only — there is no server-side
JavaScript application runtime, matching `TARGET_ARCHITECTURE.md` §4.1.

**File layout.**

```text
frontend/
├── index.html                        page shell
├── scripts/
│   ├── resolveBrowserPath.mjs        locates an installed Chrome/Edge for the checks below
│   ├── stubOrigin.mjs                serves dist/ and answers /api/ for the controlled-origin checks
│   ├── browser-smoke.mjs             real-browser check against a running backend, detail included
│   ├── sync-browser-smoke.mjs        real-browser check against a stub origin in its own process
│   ├── layout-browser-smoke.mjs      real-browser navigation/layout/zoom/focus/contrast check
│   ├── profit-browser-smoke.mjs      real-browser check of the Profit split, tree, selection and reflow
│   └── account-browser-smoke.mjs     real-browser check of Bank/Materials against a running backend
└── src/
    ├── main.ts, App.vue, styles.css  mount point; the shell; the shared tokens and treatments
    ├── shell/
    │   ├── destinations.ts           the four implemented destinations and their URLs/titles
    │   ├── useHashRoute.ts           the open destination, kept in the location hash
    │   ├── SiteHeader.vue            wordmark, destination links, compact synchronization activity
    │   └── PageHeader.vue            every page's h1, intro sentence and grouped page actions
    ├── api/
    │   ├── types.ts                  TypeScript shapes of the §5.5/§5.7–§5.10/§5.12/§5.13 transport records
    │   ├── http.ts                   fetch wrapper; maps the uniform error body to ApiRequestError
    │   ├── craftingApi.ts            the three routes the Profit screen calls, behind an interface
    │   ├── syncApi.ts                the three triggers and the shared status route
    │   └── accountApi.ts             the two §5.12 account reads, behind an interface
    ├── account/
    │   ├── BankScreen.vue            the bank as §5.12 supplies it, empty slots kept in place
    │   ├── MaterialsScreen.vue       the backend's categories, labels and stack order, unchanged
    │   ├── InventoryItem.vue         one entry: supplied item id, count, rarity, neutral icon fallback
    │   └── useAccountRead.ts         one account read's phase, data, failure and explicit reload
    ├── crafting/
    │   ├── CraftingProfitScreen.vue  the screen: controls, states, comparison and detail regions
    │   ├── ScopeSelector.vue         the sole Discipline selector (DOMAIN_SPEC.md §2.2.1)
    │   ├── ProfitSettingsForm.vue    the settings the Profit contract supports
    │   ├── CraftingProfitTable.vue   the seven comparison columns and the row-selection control
    │   ├── ResultDisplayControls.vue the three display filters, the maximum and Show all
    │   ├── SelectedResultDetail.vue  the selected row's supplied summary, quote and materials
    │   ├── CraftingResolution.vue    the §5.13 answer: its five situations, basis and fresh row
    │   ├── ResolutionTreeNode.vue    one requirement and, recursively, its ingredient occurrences
    │   ├── useProfitResolution.ts    the lazy detail request and §13.4's association rules
    │   ├── resolutionPresentation.ts method/state/blocked-reason codes in user-oriented words
    │   ├── useCraftingProfit.ts      scope/settings state and request lifecycle
    │   ├── useProfitTableView.ts     search, filters, sort, display limit and identity selection
    │   ├── scopeOptions.ts           builds selector entries from the §5.10 response
    │   ├── rowState.ts               supplied state in words, plus the minimal row diagnostic
    │   ├── recipeLabel.ts            name, or the item id when the backend supplied none; wiki URL
    │   └── formatCopper.ts           copper → gold/silver/copper text, signed where it may be a loss
    └── sync/
        ├── SyncScreen.vue            the synchronization area: one trigger and task state each
        ├── useSyncOperations.ts      per-operation submission and status-polling state
        ├── provideSyncOperations.ts  the shell owns one instance; the area injects it
        ├── statusPresentation.ts     backend states and situations in user-oriented words
        └── operations.ts             the four backend operation keys, labels and trigger calls
```

**Runtime path.** The browser calls only the origin it was served from. In development
`vite`'s dev server proxies `/api` to the backend (`GW2_BACKEND_ORIGIN`, default
`http://localhost:8080`), so the backend needs no CORS configuration and the browser never names a
second host. No GW2 API key, database URL or credential of any kind reaches the frontend — it holds
none and reads none, and the GW2 API is only ever contacted by the backend (§5.4, §5.7–§5.9).

**Crafting Profit data flow.** Opening the screen issues `GET /api/crafting/selector-options` (§5.10) and, in
parallel, `POST /api/crafting/profit` (§5.5) with an **empty body**, so the defaults applied are the
backend's own rather than a second copy of them. The settings controls are populated from the
`settings` echo of that first response; a scope or settings change posts the full current selection
and replaces the rows. Each request carries a monotonically increasing id and a response whose id is
no longer the newest is discarded, so a slow earlier answer cannot overwrite the current selection's
results. The scope and settings controls stay enabled while a request is in flight — a calculation
takes seconds, and disabling them would both strand the user and make that superseding unreachable.
Since `STORY-WEB-004` the page separates the page action (Reload results) from a labelled "Calculation
controls" panel holding scope, search and settings, and from an "Opportunities" region holding the
summary, the state messages and the table; a failed calculation offers a retry.

**Crafting Profit information hierarchy (`STORY-WEB-005`).** The page is three groups: the controls
panel, the comparison region and the selected-result detail. `.results-split` puts the comparison and
the detail side by side from 64rem upwards (the detail bounded at 20–26rem) and stacks the detail
below the comparison under that width; the table keeps its own `.table-region` scrolling either way.

- **Seven comparison columns, not fourteen.** Recipe (the row header), Disciplines, Craftable, Own
  materials *cost, per craft*, Profit *per craft*, Total sell value *all crafts* and Total profit
  *all crafts* — `DOMAIN_SPEC.md` §2.1.1's required comparison content since `STORY-WEB-008`, which
  also removed the general State column (below). Each money column states its basis in its own
  header, because the contract mixes the two: `revenueCopper`, `matsSellValueCopper` and
  `profitCopper` are per single craft while `buyCostCopper`, `totalSellValueCopper` and
  `totalProfitCopper` are totals for every craft counted (`web.dto.CraftingRowDto`). The own-materials
  column keeps its supplied per-craft basis rather than being scaled into a total it was never stated
  as, and the two totals are set in a heavier weight — never distinguished by color. Sorting is
  offered on exactly these seven keys. Nothing is dropped from the client — `outputCount`,
  `minRating`, `revenueCopper`, the buy cost, the output quote and both material lists moved into the
  detail region.
- **Selection is the recipe, never the row number.** `useProfitTableView` holds `selectedRecipeId`
  and derives the detail from the *current* rows, so sorting and searching keep a valid selection,
  a replacement calculation shows that recipe's new values, and a recipe the newest result set no
  longer contains is deselected instead of lying dormant. A superseded response never reaches `rows`,
  so it can never reach the detail either. The control is an ordinary `<button>` carrying the recipe
  name, so Tab and Enter/Space operate it; the selected row is marked by `aria-current="true"`, a
  raised surface, a highlight border, a marker glyph and a visually hidden "Selected" — never color
  alone, and visibly distinct from the shared focus outline.
- **The detail region renders the response and nothing else.** Labelled sub-regions for the table
  calculation's summary (per-craft group, totals group, the output item's raw trading-post quote),
  for the crafting resolution (below, `STORY-WEB-007`) and for the two supplied material lists
  (`missingToBuy` for every craft counted, `missingToBuyOne` for one further craft). Each material
  line is its supplied quantity and its own quote; no shopping total is produced and no procurement
  quantity is derived. An unsupplied list, an empty list and "nothing to buy" stay three distinct
  messages. A GW2 Wiki link is offered only when the backend supplied a name to build an article
  title from, percent-encoded into the path; with no name the link is omitted and said to be omitted,
  because an item id is not a wiki address.
- **Domain states read as words, with the code kept secondary** (`rowState.ts`). The seven
  `craft.BlockedReason` names of `DOMAIN_SPEC.md` §42 each have their own wording; the reason is
  stated as *further* crafting being blocked when the backend still counted crafts, which is what
  the field means (`craft.CraftResult`). `NONE` with no craftable count reads "None craftable",
  `resultAvailable: false` reads "No result", an absent state reads "State not reported", and a code
  this client does not know is shown as itself and is never toned as success. The raw code appears
  only in the detail's closed "Technical details" disclosure.
- **The state lives in the selected result, not in a column** (`STORY-WEB-008`, `DOMAIN_SPEC.md`
  §2.1.1). `BUYING_DISABLED`, `NO_RECIPE`, `DAILY_LIMIT`, `RECIPE_NOT_ALLOWED` and
  `INSUFFICIENT_BUDGET` carry no row label at all; the detail states each in words, beside the
  supplied buy cost and — for a budget restriction only — the maximum buy the backend echoed, and no
  missing acquisition amount is invented. `rowState.rowDiagnostic` keeps a few words beside the
  recipe name for the four situations a row's own numbers cannot express: `CYCLE_DETECTED`,
  `PRICE_UNAVAILABLE`, `resultAvailable: false` and an unreported or unrecognized code (worded, never
  the raw code). The `CYCLE_DETECTED` diagnostic is **temporary presentation technical debt**, kept
  until the Product Owner asks for its removal, and is recorded in `KNOWN_PROBLEMS.md` as such.
  Ordinary blocked rows are hidden by no filter of their own — the display controls are unchanged.
- **Money that may go either way carries its sign.** `formatSignedCopper` writes `+`/`-` and
  `moneyTone` adds the shared `.money--gain`/`.money--loss` treatment, so the distinction survives
  without color; a supplied `0c` and an unsupplied `—` both stay neutral and stay distinct.
- **The settings are one collapsed group with their effect on show.** The six controls sit behind a
  "Price and material settings" disclosure whose summary line words the settings the backend echoed;
  no default is repeated in the browser. Scope, search and Reload results stay in the open.

**Result-display controls and whole-row selection (`STORY-WEB-006`).** `DOMAIN_SPEC.md` §2.1.1's
display controls are a "Result display" fieldset (`ResultDisplayControls.vue`) inside the
*Opportunities* region, not in the calculation-controls panel — a structural separation the group's
own note repeats in words. They are pure view state in `useProfitTableView`, so changing one cannot
issue a request, cannot alter what was asked for, and cannot alter §28's per-recipe simulation cap;
`api.calculateProfit` still runs only for an opened screen, a changed scope, changed settings and an
explicit reload. The whole result set stays loaded — a hidden row is hidden, never dropped.

- **Three filters, each reading one supplied field.** Craftable count exactly `0`, a
  `blockedReason` of `RECIPE_NOT_ALLOWED` on a row the backend actually calculated, and a
  `profitCopper` of `0` or less. Only the zero-count filter is on initially. A value the backend did
  not supply is its own answer and is never folded into any of the three: a null count is not `0`, a
  null profit is not "0 or less", and `resultAvailable: false` is a technical failure, not a recipe
  reported as not allowed — the not-allowed test reads `resultAvailable` as well as the code.
- **Search and filters run before the sort, and the sort before the cut-off.** `matchingRows` is the
  searched, filtered and ordered set; `visibleRows` is that set cut off at the maximum. So Show all
  reveals the rest of *that* matching set rather than an unrelated unfiltered one.
- **The maximum is changeable and starts at 250.** It is applied on the committed entry rather than
  per keystroke; an entry that is not a whole number of at least 1 is refused — the control returns
  to the maximum in effect and says so, instead of blanking the list or silently showing everything.
  "Show all matching recipes" removes the limit while keeping the typed maximum for switching back.
- **Three counts, kept apart.** The summary reads "Showing *displayed* of *matching* matching
  recipes · *calculated* calculated for this scope"; a cut-off list additionally says how many
  further matching recipes the maximum is holding back. A list emptied by the controls states that
  the calculation's rows are all still held and names every restriction currently applied, with the
  controls still on screen to undo.
- **The whole row selects, the button is still the control.** A click on any part of a row that is
  not itself interactive selects that row's recipe; a click that started inside a nested `a`,
  `button`, `input`, `select`, `textarea`, `label`, `summary` or `role="button"`/`role="link"` is
  left to that control, so the recipe button selects once and not twice and a link added later keeps
  following its own href. The `<tr>` is deliberately not a control — the button remains the single
  tab stop, the accessible name and the keyboard route (Enter/Space), and the row click is an extra
  pointer affordance rather than a second, nameless widget. Selection is still by recipe identity.
- **A selected recipe the display controls hide keeps its identity and its detail.** The detail
  region stays on screen and says which control is holding the row back — the maximum
  (`selectionHiddenReason === 'limited'`) or the search/filters (`'filtered'`) — rather than being
  cleared or silently paired with a different row. Only a result set that no longer contains the
  recipe clears the selection, exactly as `STORY-WEB-005` established.

**Resolution detail in the browser (`STORY-WEB-007`).** Selecting a recipe asks `POST
/api/crafting/profit/resolution` (§5.13) for that one recipe and renders the returned row and tree in
the detail panel's "Crafting resolution" sub-region. The request is **lazy** — one per selection,
never one per table row — and completes as a plain HTTP 200, which is the execution policy
`STORY-API-008` measured and fixed; there is no task, no status resource and nothing polled here.

- **The table's own effective inputs are what is sent.** `useProfitResolution.request` posts
  `recipeId` plus `calculation` built from the scope and settings the *table response echoed*, so the
  detail is calculated with the inputs the list on screen was calculated with rather than asking for
  the backend's defaults a second time. An effective scope member the backend reported as absent is
  omitted rather than sent as a null, which is how the table request contract carries "not supplied".
  No row number, price, material map or context identifier is part of the body.
- **Association is feature, recipe, inputs and a local generation** (§13.4). Every invalidating event
  increments a generation counter and clears the panel: a changed selection, changed calculation
  inputs, a started table reload, and leaving the view while the page is kept alive (`onDeactivated`
  invalidates, `onActivated` asks again, freshly). An answer for a superseded generation is dropped,
  which is what makes A → B → A safe: A's first answer belongs to a dead generation and cannot revive
  it. An accepted answer must additionally echo back the recipe id and the same effective inputs; one
  that does not is reported as a mismatch and leaves no tree. The identity key is built from the
  echoed scope and settings **only**, so sorting, searching, the display filters and the display
  maximum do not invalidate a detail — re-ordering the table is not a new calculation.
- **Fresh detail, stated as such, and never merged with the table row.** The region says in words that
  it is a separate calculation run when the recipe was selected, resolving **one output batch** of the
  requested recipe from that calculation's own starting inventory, budget and daily state — not a
  trace of every craft the table counted and not a claim that the requested recipe was executed. The
  returned `row` is displayed inside that region under its own heading; the comparison table's values
  are left exactly as their own calculation reported them. Because the two are separate calculations,
  either may legitimately differ, and neither is described as the other's explanation (§13.2).
- **Requested identity and actual sourcing stay apart** (AR-003). The envelope's `recipeId` is the
  recipe that was *asked about*; each node's `recipeId` is the recipe actually selected or attempted.
  A sentence names which of the three the root is: the requested recipe, another producing recipe, or
  no recipe at all (an inventory-only root). Nothing relabels a root as an execution of the requested
  recipe, and no ingredient path is invented for a root that has none.
- **Node facts are printed, not computed.** Each node shows its supplied requested/inventory/crafted/
  bought/missing quantities, producing recipe or "None selected", craft count, produced quantity,
  character or "Not assigned", the three inclusive costs, its methods, its states and its blocked
  reasons, with children in the resolver's own order. The three costs already include everything below
  the node, so no descendant cost is added into a parent; a null cost renders `—` with the statement
  that it could not be established, a domain-established zero renders `0c`, and the two never merge.
  Two occurrences of one item in different branches stay two nodes — the presentation key is the
  child-index path, not a node id. Children sit in an open `<details>` group per node, so the whole
  tree is present from the start, collapsing is the user's choice and nothing is paged, truncated or
  hidden behind a "show more".
- **Five situations, kept apart** (`ResolutionPhase`). Loading; an answer with a tree, whose nodes may
  still report blocked requirements; an answer reporting `RESULT_UNAVAILABLE`, worded as the
  calculation's own answer with no tree; a 404 `RECIPE_NOT_IN_CALCULATION`, worded as the fresh
  calculation no longer offering this recipe and explicitly *not* as the recipe being gone from the
  database; and a request that failed, worded as a request that did not work rather than as something
  established about the recipe. The last three clear the tree, so no old tree is ever left under a new
  selection, and the backend's own code and message stay visible as secondary evidence.
- **Codes read as words, with the raw code kept visible** (`resolutionPresentation.ts`). The three
  `craft.AcquisitionMethod` names, the four `craft.ResolutionState` names and all seven
  `craft.BlockedReason` names of `DOMAIN_SPEC.md` §42 have their own wording, reusing `rowState.ts`'s
  with the one adjustment a node needs: a row's reason is about *further* crafting, while a node's
  reason is about *this requirement*. `BUYING_DISABLED` therefore reads as a requirement that would
  have to be bought while buying is off, never as invalidation of crafts already counted.
  `PRICE_UNAVAILABLE` is stated as an unknown price that is not zero; `UNVALUED_NONTRADEABLE` as a
  known zero for a non-tradable item that is still a real requirement. A code this client has no
  wording for is shown as itself, marked "(not recognized)", and is never toned as success. The
  envelope's `consistency`, `treeBasis`, `treeStatus` and `calculatedAt` appear verbatim in the
  detail's closed "Technical details" disclosure.
- **The panel is sticky where there is room for it.** From 64rem upwards `.results-split__aside` is
  `position: sticky` with a `100vh`-bounded height, its own `overflow-y` and `tabindex="0"`, so a
  detail taller than the viewport is scrollable by keyboard as well as by pointer instead of having
  its foot pinned off-screen. Below that width it is an ordinary block below the comparison region.
  Inside the tree the shared `.status` treatment's `white-space: nowrap` is relaxed, because an
  unrecognized code is of unknown length and a deeply nested node would otherwise widen the page.

**Synchronization area flow (`STORY-WEB-002`, restructured by `STORY-WEB-004`).** `SyncScreen.vue` is
the `#/synchronization` area — its own destination, not a panel above the crafting analysis. It
renders one trigger per backend operation key — `ACCOUNT_SYNC`, `GLOBAL_SYNC`, `PRICE_REFRESH_PROFIT`
and `PRICE_REFRESH_DISCOVERY` (§5.7–§5.9) — and the task state the backend reports for each. Nothing
is triggered by mounting or reloading the page; every request follows a click.

- **Acceptance is tracked, not believed.** A 202 supplies `taskId`, `operation` and `statusUrl`;
  the panel records the identity and polls the advertised status location, and renders `PENDING`,
  `RUNNING`, `SUCCEEDED` and `FAILED` from the status body's `state` alone. The status HTTP code is
  never read as an outcome, so a `FAILED` task returned with 200 renders as a failure. The only
  progress facts shown are the backend's `submittedAt`/`startedAt`/`finishedAt`; no percentage, step
  or count is displayed, because none exists (§5.7).
- **The advertised status location is constrained, not followed blindly.** `syncApi.readTaskStatus`
  refuses a location that does not sit under the API base path (`UNUSABLE_STATUS_URL`) rather than
  fetching it, keeping the browser on the single origin it was served from.
- **Per-operation state, mirroring the backend's admission rule.** The four operations hold separate
  state, the two price variants included; a trigger is suppressed only while *that* operation is
  submitting or still tracking an unfinished task of its own, so an account sync and a global sync
  stay independently triggerable. No client-side queue, mutual exclusion or ordering exists. A 409
  `SYNC_ALREADY_RUNNING` is shown as the refusal it is and never resubmitted.
- **Three failures, kept apart.** A refused or unanswered trigger (no task exists), a task that ran
  and failed (the backend's sanitized code and message, its "were not rolled back" qualification
  included), and a failed status lookup (the outcome is simply not established). A trigger that
  failed in transport additionally says the admission is unknown, and nothing is ever resubmitted
  automatically. A 404 `TASK_NOT_FOUND` is reported as an identifier this backend process cannot
  resolve — explicitly neither success, failure nor rollback — and offers no status retry, since
  retention is process-local (§5.7) and asking again cannot establish more. Every other lookup
  failure offers a status retry that repeats the lookup only, never the POST.
- **Polling is bounded and never overlaps.** The next lookup is scheduled only once the previous one
  answered (3 s apart, at most 600 lookups per tracked task ≈ 30 minutes); a terminal state, an
  unresolvable identifier, any lookup failure and the bound all stop it. Each tracked task carries a
  token, so an answer belonging to a task no longer tracked is discarded, and disposing the tracking
  cancels the pending timer and discards answers still in flight.
- **The tracking state belongs to the shell, not to the page** (`provideSyncOperations.ts`,
  `STORY-WEB-004`). `App.vue` creates exactly one instance and provides it; `SyncScreen.vue` injects
  it and refuses to run without it, rather than silently creating a second one. Opening another area
  therefore keeps an unfinished task tracked — with no repeated trigger and no second polling loop —
  and the compact "N tasks running" indication on the synchronization link reports what this page is
  tracking, being part of the link and not a control. The timers are cleared when that tracking is
  actually disposed, which for the shell means the application unmounting. Nothing is persisted, so a
  browser reload starts with no tracked task; the page says so instead of implying otherwise.
- **User-oriented words, backend evidence secondary** (`statusPresentation.ts`, `STORY-WEB-004`). The
  four states read as "Accepted, waiting to start", "Running", "Completed" and "Failed"; any other
  state the backend might report is shown as itself (`Unrecognized state: <code>`) and is never
  treated as terminal or as success. The four situations stay distinct in wording as well as in state:
  refused (`Not accepted`), unanswered trigger and stopped tracking (both `Outcome not established`,
  the latter keeping the last reported state visible as "<state> — outcome not established"), and a
  task that ran and failed. A failure leads with the backend's own message, its no-rollback
  qualification included; the operation buttons read as actions ("Synchronize account", "Refresh
  Trading Post prices for Crafting Profit"), never as operation keys. Task identifier, timestamps and
  the raw state code sit in a closed "Technical details" disclosure and nowhere else.
- **What a rendered `SUCCEEDED` does and does not mean.** It means the backend task finished. For a
  price refresh it does not establish that any item was fetched — the freshness filter can select
  nothing and succeed (§5.9) — and the panel shows no item count, because the backend reports none.
  Task records are process-local and unpersisted (§5.7), so a backend restart makes a tracked
  identifier unresolvable and the panel reports it as such; the panel keeps no record of its own.

**Application shell and navigation (`STORY-WEB-004`).** `App.vue` is a site header plus the one open
application area. The header carries the wordmark and one link per implemented destination —
Crafting Profit, Synchronization, Bank, Materials (`shell/destinations.ts`) — and nothing unfinished
is listed, so no link leads nowhere. Each page opens with a `PageHeader`: its `h1`, one introductory
sentence and that page's actions grouped together, in the same place on every page.

- **Destinations are addressable, with no router library.** `shell/useHashRoute.ts` keeps the open
  destination in the location hash (`#/crafting`, `#/synchronization`, `#/bank`, `#/materials`), so
  every area has a real URL, Back/Forward move between the areas that were visited, a bookmark opens
  the named area and the document title names it (`<area> · GW2 Crafting Tool`). The links are real
  `<a href>` elements: an ordinary left click is handled in the page, and a modified click is left to
  the browser. An empty or unrecognized hash resolves to Crafting Profit and is corrected in place
  with `replaceState`, so the address bar never claims an area that does not exist. Only the
  destination is in the URL — no scope, settings, selection or task identifier — so a reload opens
  the named area with nothing else restored.
- **Navigating submits nothing.** No navigation issues a calculation or a synchronization request.
  Opening Bank or Materials issues that screen's own read (see below); opening Crafting Profit or
  Synchronization issues nothing at all.
- **What survives moving between areas.** Crafting Profit is kept alive (`<KeepAlive>`), because its
  rows belong to a scope and settings the user chose: returning finds the same scope, settings,
  search, sort and display controls, and does *not* post the calculation again. Bank and Materials hold no such input,
  so each is mounted on open and unmounted on leave, which is what makes their read-per-open and
  their discarding of an answer still in flight the behavior `STORY-WEB-003` specified. The
  synchronization tracking state is neither: it lives in the shell (below).
- **Accessibility of the shell.** A skip link precedes the header; the navigation is a labelled
  `<nav>` whose current entry carries `aria-current="page"` plus a weight/border/inset-bar treatment,
  so the current destination is not indicated by color alone. Changing destination moves focus to the
  new page's `h1` (`tabindex="-1"`), which is what announces the change to a screen reader; the
  initial page load is left alone. Shared focus styling is one `:focus-visible` outline in the
  information/focus token, distinguishable from the highlight-colored selection treatments.

**Shared presentation (`STORY-WEB-004`).** `src/styles.css` holds one set of semantic tokens — the
`FRONTEND_UX_GUIDELINES.md` §9 baseline palette, a 4/8/12/16/24/32/48 spacing scale, one body face,
a small type scale, two corner radii — and the reusable treatments built on them: `.page` (bounded at
1440px and centered), `.screen`, `.panel`, `.stack`/`.cluster`, `.prose` (bounded at 68ch), `.meta`
for secondary text, `.notice` with error/warning/info tones, `.status` pills with idle/busy/success/
failure/caution/unknown tones, `.diagnostics` for labelled secondary technical detail, `.table-region`
for a table that genuinely needs two dimensions, and default/hover/focus/active/disabled treatments
for buttons and inputs. Every screen uses these; no page defines a color of its own. Decorative
transitions are removed under `prefers-reduced-motion`. A disabled control keeps its size and its
readability and states its reason next to itself rather than fading out.

A wide table scrolls inside `.table-region` only — a focusable, named region — so the page around it
reflows and never gains a horizontal scrollbar of its own. That region is the horizontal scroll
container, which is why the result table's header row is no longer `position: sticky`: keeping the
page's own vertical scrolling was the deliberate trade.

**Bank and Materials flow (`STORY-WEB-003`, restyled by `STORY-WEB-004`).** Each screen owns one read
of one §5.12 route — `GET /api/account/bank`, `GET /api/account/materials` — issued on mount and
repeated only by its own Reload button. Both routes are GETs, so neither screen can start a
synchronization or reach the GW2 API, and neither posts anything. Both now use the shell's page
header, panels, notices and secondary-text treatments, and their slot/stack grids are fluid, so one
supplied order reflows from a single column on a phone to many columns on a desktop without any entry
changing position; an empty slot is marked by a dashed, unfilled cell rather than by reduced
contrast.

- **The backend's inventory facts are rendered, not reinterpreted.** Bank slots appear in the
  supplied order with empty ones left in their positions, because the bank is a grid and dropping
  them would move every following item into the wrong cell. An empty slot is the backend's
  both-null representation and is shown as "Empty"; a `null` is never read as item id `0` or as an
  owned count of `0`, and a missing count renders `—`. A successful `slotCount: 0` response is a
  distinct message from a bank whose slots are empty. The Materials screen renders the supplied
  categories, their labels — the `"Category <id>"` fallback included — and each category's stack
  order unchanged, and shows each stack's own `category` id, since that is the only way to see which
  id produced a fallback label. Nothing is regrouped, deduplicated, sorted, aggregated or filtered
  here: no category map and no inclusion rule exists in the browser (§5.12).
- **Items are identified by id, and no image is requested yet.** The two routes supply no item name,
  so `InventoryItem.vue` shows `#<itemId>` and invents nothing; no item-detail lookup exists and the
  GW2 API is never contacted. Both routes now carry a requestable `iconUrl` (`STORY-API-009`, §5.14),
  but **no `<img>` is rendered on either screen**: every entry still gets the same neutral
  placeholder and the supplied URL is used only to say whether the metadata existed at all
  (`data-icon-supplied`). `STORY-WEB-010` owns the shared image component that will request it.
  Rarity is displayed when supplied and omitted when not.
- **Four states, kept apart, per screen.** `useAccountRead.ts` holds `loading`, `loaded` and
  `failed`, with a successful empty result rendered as its own message inside `loaded`. A failure
  shows the backend's sanitized code and message (503 `DATA_STORE_UNAVAILABLE`, 500
  `ACCOUNT_READ_FAILED`, §5.12) or this client's own name for a transport failure, and offers an
  explicit retry that repeats only that read. Starting a read clears the previous answer, so a failed
  reload can neither masquerade as an empty inventory nor leave stale data on screen as if newly
  loaded.
- **A superseded answer is discarded.** Each read carries a monotonically increasing id and the
  composable drops any answer — success or failure — whose id is no longer the newest or whose screen
  has been unmounted, so a late bank response cannot reach the Materials screen and a slow first read
  cannot overwrite a reload's result.

**What the frontend does not do.** No profit, fee, valuation, crafting, inventory or eligibility
calculation exists in `frontend/`. Every number rendered is a value the backend supplied;
`totalProfitCopper` and `totalSellValueCopper` in particular are displayed and sorted exactly as
received and are never derived from `craftableCount × profitCopper` or `craftableCount ×
revenueCopper`. Rows the backend could not calculate are kept, not dropped:
`resultAvailable: false` renders as "No result", a `blockedReason` renders as its own wording in the
selected-result detail with the code kept in that detail's disclosure, and a null numeric field
renders as `—` rather than `0`.
`formatCopper`/`formatCount`/`formatSignedCopper` are the only transformations, and all three are
presentation-only. The Profit screen's display filters and maximum only decide which of the loaded
rows are listed: no row is dropped from the result set, no unsupplied value is read as zero, and no
blocked or unavailable result is reinterpreted as successful or absent. The same holds for a
resolution tree: no descendant cost is summed into an ancestor (they already include it), no quantity
is derived from the others, no state is inferred from a price, no two occurrences of an item are
merged, and no shopping total is produced from any of it. The TypeScript types in `api/types.ts` describe transport shape; they do not
validate received JSON and do not replace backend validation.

**Not built yet.** The Discovery page, which is the other §13 detail route's consumer: `frontend/`
calls `POST /api/crafting/profit/resolution` only, and nothing in the browser reaches
`POST /api/crafting/discovery/resolution` (§5.13). Item names in a resolution node are whatever the
backend supplied. Also missing: automatic refresh, task cancellation, any
client-side progress mechanism, item names on the inventory screens (no contract supplies them,
§5.12), **any rendered item image anywhere** — every affected contract now carries a requestable
`iconUrl` (§5.14) but nothing in the browser requests one yet, which is `STORY-WEB-010`'s shared image
component — any persistence across a browser reload, and every other screen. Crafting Profit still has no
virtualization and no paging: every row the display controls leave visible enters the DOM at once, and
Show all over the live default scope means all 3175 of them. `STORY-WEB-006`'s maximum is a display
control the user owns, not a performance mechanism, and no browser check establishes full-page
performance either way — including the detail a selection loads, which `TARGET_ARCHITECTURE.md` §33
still requires to be timed as part of a complete page. The result table's header does not stay visible while scrolling. There is no mobile navigation menu (four short
destination links wrap instead), no footer and no artwork or icon set. `docs/ROADMAP.md` Phase 5 owns
what remains. No browser check establishes full-page performance.

**Local commands** (run in `frontend/`, after `npm ci`):

| Command | Purpose |
|---|---|
| `npm run dev` | dev server on `GW2_FRONTEND_PORT` (default 5173), proxying `/api` to the backend |
| `npm run build` | type-check, then production build into `frontend/dist/` |
| `npm run type-check` | `vue-tsc --noEmit` strict check, components included |
| `npm test` | component/unit suite (Vitest + jsdom), controlled HTTP responses only |
| `npm run smoke:browser` | real-browser check of Crafting Profit **including the selected recipe's resolution detail**; needs the backend **and** `npm run dev` already running |
| `npm run smoke:sync` | real-browser check of the synchronization area; needs `npm run build` only |
| `npm run smoke:layout` | real-browser check of navigation, three viewports, zoom, keyboard focus and contrast; needs `npm run build` only |
| `npm run smoke:profit` | real-browser check of the Crafting Profit comparison/detail split, the resolution tree, keyboard and whole-row selection, the sticky panel, the display controls and its reflow; needs `npm run build` only |
| `npm run smoke:account` | real-browser check of Bank and Materials; needs the backend **and** `npm run dev` already running |

The backend is started separately with `./mvnw spring-boot:run` from the repository root (§8).
All five checks drive an installed Chrome/Edge through `playwright-core` (`GW2_BROWSER_PATH`
overrides the executable, `scripts/resolveBrowserPath.mjs` finds it); none downloads a browser of its
own. `smoke:browser` and `smoke:account` use the real backend, which is safe because Crafting Profit
and both inventory screens only read; `smoke:account` additionally re-reads each route itself and
compares the rendered slot order, empty-slot positions, item ids, counts, category labels and stack
order against that response. `smoke:browser` reads the resolution response the browser itself
received and compares the rendered tree against it — every node label in the backend's own
depth-first child order, the root's requested quantity, and the four envelope literals — so what it
establishes is that the page displayed that response, never that a value is domain-correct. Since the
detail route is only mapped in a backend built after `STORY-API-008`, a long-running older backend
process answers it with Spring's default 404 body; the check then records the situation instead of a
comparison, which is the honest outcome and not a frontend failure.
`smoke:sync`, `smoke:layout` and `smoke:profit` deliberately do not: through `scripts/stubOrigin.mjs`
they serve `dist/` and answer every route they need from their own process — the trigger and status
routes with scripted task lifecycles for the first, the four areas' reads for the second, and for the
third a row set covering gain, loss, blocked, not-allowed, no-result and null-versus-zero plus a
scripted resolution response whose tree carries split sourcing, a repeated item, a known zero, a
missing price and an unrecognized method/state/reason — so the pages are exercised in a real browser
without starting a real synchronization or touching user data.
`stubOrigin` binds and the scripts open `127.0.0.1` rather than the name `localhost`, it fails on a
`listen` error, and each run asserts that this server served the page — without those, another process
on that port (a dev server bound to `::1` alone leaves the IPv4 port free) would be driven instead,
and its `/api` proxy would reach the real backend. A passing `smoke:sync` therefore evidences browser
interaction and task-state presentation only, never that a backend synchronization ran; `smoke:layout`
and `smoke:profit` evidence structure, layout, selection, display-control behavior, tree rendering,
focus and contrast, and nothing about real data or page-load performance.

---

### 5.12 Account bank and material-storage HTTP flows (`STORY-API-007`)

Two read-only routes over the two reads the JavaFX `BankView` and `MaterialsView` already use
(§4, §6). The browser's Bank and Materials screens consume them (`STORY-WEB-003`, §5.11); the
JavaFX views keep calling the same two services in process, so both clients read the same facts by
different paths.

```text
GET /api/account/bank                        GET /api/account/materials
   |                                            |
   v                                            v
web.BankContentsApiController                web.MaterialStorageApiController
   |  1. BankContentsService                    |  1. MaterialStorageService
   |     .getBankContents()                     |     .getMaterialStorage()
   |  2. field-for-field copy into web.dto      |  2. field-for-field copy into web.dto
   v                                            v
200 BankContentsResponse | 503 | 500         200 MaterialStorageResponse | 503 | 500
```

Observed properties of both flows:

- **No request input at all.** Each is a GET with no body and no parameter, so neither route has a
  400 path and neither has a validation step.
- **Exactly one application-service call per accepted request**, asserted by the contract tests. No
  repository is queried at the boundary, no synchronization is started, no inventory row is written,
  the GW2 API is not contacted and no crafting calculation runs. Both services are read-only down to
  their SQL (§4), so a GET cannot change stored data.
- **Bank response fields.** `slotCount` and `slots`, one `{slot, itemId, count, iconUrl, rarity}`
  record per row of `BankContentsService.getBankContents()`, in that service's slot order. **Empty
  slots are kept**, with `itemId` and `count` both `null` — that is the empty-slot representation,
  and no substitute id or `0` count is invented. Dropping them would shift every following item into
  the wrong cell of the bank grid, which the caller could not undo. `rarity` is `null` when the
  slot's item has no matching `items` row; that is display metadata the service supplied and it is
  reported as-is, never defaulted. `iconUrl` is this application's own image URL for the slot's item,
  derived by the controller from the retained `items.icon_url` the same read already carried
  (`STORY-API-009`, §5.14) — `null` for an empty slot or unusable metadata. The backend's local
  `iconPath` left this contract with that story and is not reported.
- **Materials response fields.** `categoryCount` and `categories`, one
  `{name, materials}` record per category the service returned, each holding
  `{category, itemId, count, iconUrl, rarity}` stacks. `name` is the service's category label or its
  `"Category <id>"` fallback for an id it does not know; the numeric `category` id is reported too,
  since it is the only way a caller can see which id produced a fallback label. `itemId` is `null`
  when the stored row carries none; `count` is always present, because the service's read excludes
  empty stacks. `rarity` is `null` on the same LEFT JOIN condition as the bank route's, and `iconUrl`
  is derived exactly as the bank route's is.
- **Grouping, labels and inclusion rules stay in the application service.** The controller runs one
  nested `map` over what it was handed — no regrouping, sorting, deduplication, filtering or
  inventory aggregation — so the category order, the labels, the fallback label and the
  "non-empty stacks only" rule remain `MaterialStorageService`'s, and the Materials page and a browser
  see the same grouping. Neither controller re-derives a single one of them.
- **Empty is a 200, distinguishable from a failure.** No bank rows is `slotCount: 0` with an empty
  `slots` list; empty material storage is `categoryCount: 0` with an empty `categories` list. Neither
  is a 404 and neither substitutes a placeholder slot or category, so an empty read is never
  mistakable for a failed one.
- **Status mapping** is one shared advice, `web.AccountReadApiExceptionHandler`, scoped to both
  controllers: 503 `DATA_STORE_UNAVAILABLE` for a `SQLException` — the same code and message as
  §5.5/§5.6/§5.10, since it means the same thing — and 500 `ACCOUNT_READ_FAILED` otherwise. One advice
  for both routes because they fail the same way for the same reasons and a caller already knows
  which of them it called. It stays separate from `ApiExceptionHandler` for the reason §5.10 records.
  Both messages are fixed; failure detail is logged server-side only, so no credential, connection
  string, SQL or exception text reaches a client. Being scoped with `assignableTypes`, the advice does
  not intercept the framework's own 404/405 responses for other paths.
- **Two shared service instances.** Like `CharacterSelectionService` (§5.10), neither service keeps
  per-call state — each holds only its repository, which opens its connection inside the call — so no
  per-request factory is needed and the beans cost no connection at startup.
- **JavaFX is untouched.** `BankView`/`MaterialsView` keep calling the same two services in process
  (§6); no HTTP, Spring or transport type appears in `application.*`, `repo.*` or either view.

### 5.12 Single-craft resolution explanation (`STORY-DOM-020`, `STORY-APP-012`)

`craft.SingleCraftExplainer` is the domain entry point for explaining **one** execution of an
explicitly selected recipe. `explainIndividual(...)` and `explainCoordinated(...)` take the same
captured inputs as `CraftingPlanner.evaluateAll(...)`/`evaluateAllCoordinated(...)` — recipes,
inventory pools, roster, quotes, settings, allowed recipe IDs — and return a
`SingleCraftExplanation`: a tree of immutable `CraftTraceNode`s, or `available() == false` when
there is no result to explain. The contract these facts serve is owned by
`TARGET_ARCHITECTURE.md` §13.3.

It runs the authoritative resolver rather than a second one. `CraftingResolver` gained a tracing
mode (`new CraftingResolver(true)`) in which every resolved requirement additionally records the
facts the economic result does not carry — the recipe it actually selected, how many executions it
performed and what they produced, the character it assigned, whether consumed owned quantity was
valued at zero without a modellable value, and the craft attempt whose ingredients explain the node
— on a `ResolvedNeed.NodeTrace`. Because those records hang off the `ResolvedNeed`s themselves, a
speculative craft that loses to buying or to another eligible character is discarded with its nodes
and cannot reach the explanation; a craft that was attempted and blocked is still reachable, which
is what lets a blocked requirement show why.

`RecipeSimulator` gained two opt-in behaviors for the same purpose: a package-private constructor
taking the resolver and a "stop after the first accepted craft" flag, and retention of the craft
attempt that ended a phase without being accepted (`RecipeSimulationResult.getBlockedAttempt()`,
paired with `blockedReason` and cleared with it). The explanation is therefore the simulation's
first craft under its real phase order — not a recipe skeleton, and not the craft that would follow
an exhausted simulation. Each call builds its own `PlannerContext` and `PlanState` from the supplied
inputs, so it loads nothing, mutates no caller state and shares nothing with another call.

Trace production is opt-in per selected recipe. The table paths in §5.1/§5.2 construct their
resolvers without it, so their `ResolvedNeed`s carry no trace and no semantic tree is built per row;
the legacy lazy `RecipeTreeBuilder`/`Node` tree the JavaFX views render is unchanged and was not
replaced. Two limitations remain recorded rather than fixed here: the root explains the resolution
of the selected recipe's *output*, so `KNOWN_PROBLEMS.md` CH-15 (owned finished stock, or another
recipe for the same output, satisfying the root) shows up in the explanation exactly as it does in
the calculation; and `UNVALUED_NONTRADEABLE` reports the zero valuation the domain actually applies,
without the recursive/vendor fallback CH-17 still describes as absent.

#### Application detail operation (`STORY-APP-012`)

`CraftingProfitService.resolveDetail(recipeId, choice, settings)` and
`CraftingDiscoveryService.resolveDetail(recipeId, choice, settings, inventoryCharacterName)` are the
callers of that explainer (`TARGET_ARCHITECTURE.md` §13.1/§13.2). Each is one **fresh calculation**,
not retrieval of an earlier `reload(...)`'s result:

```text
resolveDetail(recipeId, <the flow's existing calculation inputs>)
   -> loadCandidates(choice)                     [visible set + graph recipes + allowed IDs]
        -> recipe IDs outside that set return CraftingResolutionDetail.Status
             .RECIPE_NOT_IN_CALCULATION before any price, item or inventory load
   -> loadCalculationInputs(...)                 [quotes, item metadata, roster, inventory pools]
   -> CraftingPlanner.evaluateOne(...) / evaluateOneCoordinated(...)      -> the row summary
   -> craft.SingleCraftExplainer.explainIndividual(...) / explainCoordinated(...)  -> the tree
   -> CraftingResolutionDetail(recipeId, status, recipe, row, explanation, items, quotes)
```

Both the row and the tree are computed from the one `CalculationInputs` value that operation
loaded, and nothing is read again while the explanation is built. Because `CraftingPlanner` and
`SingleCraftExplainer` each build their own `PlanState` from those captured initial inventory maps,
the row's simulation — which may consume the pool across several crafts — cannot become the
explanation's starting state.

`CraftingPlanner.evaluateOne(...)`/`evaluateOneCoordinated(...)` (new) are the table calculation
restricted to one recipe: the same context, the same fresh `PlanState` and the same
`evaluateOneRecipeNew` the `evaluateAll*` loops use, including their heuristic skip. They are an
entry point for the selected-recipe operation, not a second set of rules.

Isolation is structural rather than managed. `resolveDetail` neither reads nor writes the
`last*` fields that back the JavaFX lazy-tree lookups of §5.1/§5.2, and holds every mutable
planning state inside the one call, so successive, concurrent and failing operations cannot
contaminate one another, an operation that finds nothing discoverable reports exactly that instead
of an earlier operation's row/metadata/trace, and there is no retained session, snapshot token or
task store. `CraftingResolutionDetail.Status` keeps the three §13.3/§13.4 outcomes apart —
`RECIPE_NOT_IN_CALCULATION`, `AVAILABLE` (including a blocked explanation, which still carries its
reasons) and `RESULT_UNAVAILABLE`. Item names are taken only from the metadata that operation
captured; a traced item with no usable name is simply absent from `itemNames()`. The table paths
are untouched: they still build no semantic trace per row, and a detail row carries no legacy
`Node` tree.

The record also carries the operation's own selected `craft.Recipe` and its captured item-metadata
and quote maps, which is what lets a presentation layer describe the recipe, name items and report
quotes from *this* operation's facts without reading anything again (`STORY-API-008`). It remains
calculation facts only: no JSON, HTTP or JavaFX type appears in it, and the §13.3 envelope fields
(`consistency`, `calculatedAt`, the echoed `calculation`) are produced at the boundary in §5.13.

### 5.13 Crafting resolution-detail HTTP flow (`STORY-API-008`)

The two detail operations `TARGET_ARCHITECTURE.md` §13.1 fixes, added to the existing crafting
controllers over the §5.12 application operation. JavaFX is untouched and keeps calling the
application services in process; the table routes of §5.5/§5.6 are unchanged.

```text
POST /api/crafting/profit/resolution        POST /api/crafting/discovery/resolution
   |
   v
web.CraftingProfitApiController             web.CraftingDiscoveryApiController
   |  1. CraftingResolutionMapper.requireBody / requireRecipeId / requireCalculation
   |  2. Crafting{Profit,Discovery}ApiMapper.toEffective(calculation)   the table route's own
   |                                                                   defaults + validation
   |  3. Supplier<...Service>.get()                      one fresh service per request
   |  4. service.resolveDetail(recipeId, ...)            the §5.12 use case, unmodified
   |  5. CraftingResolutionMapper.toRow / treeStatus / toTree  copy the captured facts into DTOs
   v
200 Crafting{Profit,Discovery}ResolutionResponse | 404 | 400 | 503 | 500
```

Observed properties of this flow:

- **Required body with a nested existing contract.** Both members are required: `recipeId` (positive)
  and `calculation`, which *is* the corresponding table request record (`CraftingProfitRequest` /
  `CraftingDiscoveryRequest`). Its members therefore keep each table route's own defaults, scope
  kinds, validation messages and semantics — Profit's `ALL`/`DISCIPLINE`/`CHARACTER_DISCIPLINE` and its
  view defaults, Discovery's required individual scope, its separate nullable
  `inventoryCharacterName` with the service's unfiltered-pool fallback, its own defaults and its
  daily setting fixed to false and reported rather than accepted. Nothing else is an input: no row
  number, price, material map or prior-context identifier.
- **One fresh request-local calculation.** The controller holds the same
  `Supplier<...Service>` seam as the table route, so each request resolves on its own service
  instance and no `last*` reload field is read or written. The response's row and tree both come from
  the single `CraftingResolutionDetail` that one call returned, and the mapper reads no repository,
  graph or clock-dependent domain state — it cannot introduce a second data read.
- **Envelope.** `recipeId` and `calculation` echo the *requested* identity and effective inputs;
  `consistency` is the literal `FRESH_CALCULATION`, `treeBasis` the literal
  `SINGLE_OUTPUT_REQUIREMENT`, and `calculatedAt` a UTC ISO-8601 completion instant that is
  informational only. `row` is the same shared `web.dto.CraftingRowDto` the tables report, produced by
  the same `web.CraftingRowMapper`, so a caller reads it identically on either route.
- **Tree.** `web.dto.ResolutionNodeDto` is a transport copy of `craft.CraftTraceNode`: quantities,
  the actually selected (or attempted) recipe id, craft count and production, assigned character,
  method/state/blocked-reason names, the three inclusive costs, ordered children, and — since
  `STORY-API-009` (§5.14) — this node's **own** item's nullable `iconUrl`. Nothing is
  summed, rounded, repaired or inferred here, repeated occurrences stay separate, and identities are
  kept apart — the envelope carries the requested recipe, every node carries what actually sourced its
  requirement, and an inventory-only root has no recipe, no craft and no invented ingredient path.
- **Status mapping** is the existing `web.ApiExceptionHandler`, plus one outcome:
  404 `RECIPE_NOT_IN_CALCULATION` (via `web.RecipeNotInCalculationException`) when the operation's own
  fresh candidate set does not contain the requested recipe. A blocked resolution is a 200 with
  `treeStatus: AVAILABLE` and its reasons; an unavailable calculation result is a 200 with
  `treeStatus: RESULT_UNAVAILABLE` and no `tree` rather than a fabricated empty one. 400/503/500 keep
  their existing codes and messages, and no database, credential or exception detail is returned.
- **Read-only.** A detail request consumes no persisted inventory, writes nothing and triggers no
  synchronization or GW2 API call.
- **Synchronous, by measurement.** Per §13.5 / §23 / `UD-007`, measured against the real database
  before the policy was fixed (`web.CraftingResolutionApiRealDbIT`; figures in `STORY-API-008`'s
  Result): comfortably inside a normal HTTP request across Profit `ALL`, a buying-enabled deep tree
  and a Discovery character scope, so both operations complete synchronously with no background task
  or status endpoint.

**Remaining work.** The browser consumer of these routes — request/generation association, tree
rendering, the requested-versus-actual labelling of §13.3/§13.4 — is not implemented, and no
`TARGET_ARCHITECTURE.md` §33 full-page timing is claimed: the figures above are backend request time
only. `KNOWN_PROBLEMS.md` CH-15 and CH-17 show through the tree unchanged, as §5.12 records.

---

### 5.14 Item icon metadata and image delivery (`STORY-API-009`)

Implements `TARGET_ARCHITECTURE.md` §12.1 (AR-005): item-bearing HTTP reads carry an
application-relative `iconUrl`, and one thin backend route serves those images from a persistent
filesystem cache shared with the JavaFX desktop icon download. The browser never receives an upstream
URL, a redirect to one, or a backend filesystem path.

**Boundaries as implemented.**

| Concern | Where it lives |
|---|---|
| Canonical source validation, canonicalization, source key, extension, content type | `infra.icons.IconSourcePolicy` (the single policy; `infra.icons.IconSource` is its accepted result) |
| Application-relative URL format **and** route parsing | `application.icons.ItemIconUrls` |
| Delivery decisions (route → disk → retained metadata → acquisition) | `application.icons.IconDelivery` / `IconDeliveryResult` |
| Persistent cache (`<ICON_CACHE_DIR>/assets-v1/{sourceKey}.{ext}`), confinement, publication | `infra.icons.IconStore` / `FilesystemIconStore` / `StoredIcon` / `IconStorageException` |
| Upstream image acquisition and image validation | `infra.icons.IconImageFetcher` / `HttpIconImageFetcher` / `IconFetchResult` / `IconImageBytes` |
| Coalescing, download/waiting bounds, failure suppression, publish-before-success | `infra.icons.IconAcquisition` / `IconAcquisitionResult` |
| Chosen protective bounds, in one place | `infra.icons.IconCacheBounds` |
| Retained metadata read on a miss (one row) | `repo.ItemIconMetadataRepository` |
| HTTP route, statuses, caching headers, ETag/304 | `web.ItemIconApiController` |
| Desktop publication and legacy handling | `sync.DesktopIconAdoption`, used by `sync.IconSync.syncItemIconsToDisk` |

`web.*` imports `application.icons.*` only; `craft.*`/`ecto.*` import none of it, so no URL, disk or
HTTP type entered the domain. Spring appears only in `web.ItemIconApiController` and the
`Gw2ApiApplication` bean that wires one shared `IconDelivery` (shared deliberately: the download
bound, the waiting bound, per-key coalescing and failure suppression only hold with one instance).

**Enrichment on page-data reads (no per-row work).** `items.icon_url` is carried by the *existing*
batch reads — `repo.ItemRepository.ItemInfo.iconUrl` (crafting tables, material lists and both
resolution-detail flows) and the `icon_url` column added to `repo.BankRepository.BankSlotRow` /
`repo.MaterialStorageRepository.MaterialStorageRow` — so a read costs no extra query, and no read
performs a per-item lookup, a synchronization step, an image download or a GW2 request. The transport
mappers turn that retained source into `iconUrl` through `ItemIconUrls`: `web.CraftingRowMapper` uses
the recipe's **output item** for a row and each missing material's own item;
`web.CraftingResolutionMapper` uses **each node's own item**, never the requested recipe's output and
never the node's sourcing. `iconPath` is gone from the browser contracts (`BankSlotDto`,
`MaterialStackDto`); JavaFX keeps consuming `items.icon_path` unchanged.

**Metadata acquisition, and how it is invoked.** `sync.IconSync.syncItemIconUrls()` now refreshes the
items an item-bearing view can reference — bank slots, material storage, character inventories, recipe
outputs and recipe ingredients, nontradeable items included — plus any item still missing metadata,
and writes a URL that *changed*, not only a null one. It downloads no image and needs no local
directory. Invocations:

- JavaFX "First-time DB Setup" (`application.InitialSetupService.firstFill()`), unchanged;
- standalone, for a backend-only machine, with no browser control and no icon download:

  ```
  ./mvnw -o -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
  java -cp "target/classes;$(cat target/classpath.txt)" sync.IconSync            # refresh metadata
  java -cp "target/classes;$(cat target/classpath.txt)" sync.IconSync --dry-run  # report coverage only
  ```

Missing metadata stays tolerable: it yields `iconUrl: null` and is repaired only by this explicit
refresh — never by navigation, a page-data read or an image request.

**Image route.** `GET /api/items/{itemId}/icon/{sourceKey}.{ext}`, in this order: validate the route's
own values → read the cache → on a miss, load the item's retained source and require the key and
extension it derives to match → only then acquire. A valid disk hit therefore needs neither the
upstream host nor live metadata; an unknown item, absent or rejected metadata, and an obsolete or
foreign key are 404s with no upstream access. A cached old key keeps serving its original image, and
changed metadata produces a new key and a new URL on the next read. Malformed route → 400; absent
metadata or upstream 404 → 404; transient upstream failure, invalid image, exhausted capacity or disk
failure → 503 with `Retry-After: 30`. All three carry `Cache-Control: no-store` and fixed wording with
no path, upstream URL or lower-layer message. Success carries the verified `Content-Type`,
`X-Content-Type-Options: nosniff`, `Cache-Control: public, max-age=86400` (finite, never `immutable`)
and a strong ETag derived from the stored bytes; `If-None-Match` yields a 304 that keeps both the ETag
and the freshness. There is no listing, no generic file path and no caller-supplied path, host or URL
anywhere on the route.

**Chosen protective bounds** (`infra.icons.IconCacheBounds`; limits, not measured latencies): connect
5 s, whole response 10 s, response body ≤ 2 MiB, decoded pixels ≤ 4 000 000, ≤ 4 concurrent
downloads, ≤ 32 requests handling a miss at once, ≤ 5 s waiting for a coordination or download slot,
30 s in-process failure suppression per key (which expires on its own and is consulted only *after*
the disk is rechecked), `Retry-After: 30`.

**Storage and publication.** Root is the existing `ICON_CACHE_DIR` (default
`<user.home>/.nebet-gw2-tool/icons`); entries live in its `assets-v1` subdirectory, one file per
canonical source, so items sharing a source share one binary. Only a fully committed file whose bytes
match its extension is an entry — empty, partial, corrupt, symlinked and temporary files all read as
a miss. Publication writes a unique `*.tmp` beside the destination and then renames it **without**
`REPLACE_EXISTING` (a plain `Files.move`, deliberately not `ATOMIC_MOVE`, which on Windows replaces
the destination): a reader never sees a partial file, and a concurrent publisher of the same key keeps
the committed entry instead of overwriting it. A failed publication is a 503, never a successful
uncached image, and no failure or placeholder is ever written at an image key. Nothing expires,
sweeps or redownloads a valid entry.

**Desktop coexistence.** `sync.IconSync.syncItemIconsToDisk` no longer downloads into
`items/{itemId}.png`. It goes through `sync.DesktopIconAdoption`, which reuses an entry either side
already published (no download), otherwise acquires and publishes through the same protocol, and only
then records the keyed file in `items.icon_path` — so a failed path update cannot invalidate a
committed asset, and the next run reuses it. Legacy `items/{itemId}.png` files are left in place and
stay usable by JavaFX; their bytes are adopted only when they are byte-identical to a successful
on-demand fetch of the canonical source (`ADOPTED_LEGACY`), since `IconSync` recorded no
source-version provenance and used `.png` names even for `.jpg` sources. Unproven legacy bytes never
become a keyed entry. No mass migration, startup migration or redownload happens, and there is no
second download store.

**Deployment requirement (recorded, not commissioned).** Per §12.1, container deployment must mount a
writable persistent volume or host directory at `ICON_CACHE_DIR`, kept stable across restarts; an
image layer, an ephemeral writable layer or a temporary directory is insufficient, and the cache never
silently degrades to memory-only. Desktop/backend sharing needs that same root at usable local paths —
host-specific absolute database paths are not portable web inputs. This story commissions no
deployment work and no new service, container or replica requirement.

**Evidence and limits.** Fixture-level coverage exists for source acceptance/rejection and key
derivation, publication atomicity and non-replacement, path/key confinement and symlinks, restart
reuse, coalescing, bounds, suppression expiry, storage failure, every status and header including
local 304, the nullable URLs and actual item identities, and desktop/web sharing with proven versus
unproven legacy reuse (`infra.icons.*Test`, `application.icons.*Test`, `web.ItemIconApiControllerTest`,
`sync.DesktopIconAdoptionTest`). The metadata refresh's *coverage* and *changed-URL* behavior are
covered against a disposable Postgres schema by `sync.IconSyncMetadataTest`, which calls no GW2 API
and downloads nothing — which is also how its independence from the desktop icon download is shown.
Real-database evidence is narrow and read-only
(`web.ItemIconApiRealDbIT`, `web.AccountReadApiRealDbEquivalenceIT`, figures in `STORY-API-009`'s
Result): metadata coverage is partial (74 of 180 bank slots carried an accepted source; 504 of 504
material stacks did), three sampled real URLs were delivered with the documented headers and
revalidated to 304, those same three entries were still the only files under `assets-v1` and were
re-served unchanged by a later backend process (persistent reuse across restart, observed rather than
only fixture-proven), and the referenced-item refresh selection was only *dry-run* (13 965 items on
this machine) — no live metadata synchronization was executed, so no claim is made about
post-refresh coverage. **Not covered:** any
real-browser rendering, any `TARGET_ARCHITECTURE.md` §33 full-page timing (cold/warm browser and
application cache, restart and warm-cache upstream-unavailable runs), and legacy adoption against real
legacy files — this machine's cache holds no `items/` directory at all. `STORY-WEB-010` retains the
shared browser image component and that integrated cache/performance gate; until it lands the browser
shows its neutral placeholder and requests no image.

---

## 6. UI → Controller → Domain/Repository/API Relationships

| View | Controller | Application (`application.*`) | Domain (`craft.*`) | Repository (`repo.*`) | Direct API/HTTP |
|---|---|---|---|---|---|
| `CraftingProfitView` | `CraftingProfitController` | yes (`CraftingProfitService`, `STORY-APP-001`, for reload; `TradingPostPriceRefreshService.refreshForProfit()`, `STORY-APP-006`, for its "Refresh Trade Post Prices" button; `AccountRefreshService.refreshMaterialsAndRecipes()`, `STORY-APP-008`, for its 90s auto-refresh timer; `CharacterSelectionService.getCraftingCharacterOptions()`, `STORY-APP-010`, for its Discipline selector) | indirectly, via the application service (`CraftingPlanner`, `RecipeTreeBuilder`) | indirectly, via the application service (repositories) | no |
| `CraftingDiscoveryView` | `CraftingDiscoveryController` | yes (`CraftingDiscoveryService`, `STORY-APP-002`, for reload; `TradingPostPriceRefreshService.refreshForDiscovery()`, `STORY-APP-006`, for its "Refresh Trade Post Prices" button; `AccountRefreshService.refreshAll()`, `STORY-APP-008`, for its 120s auto-refresh timer; `CharacterSelectionService.getCraftingCharacterOptions()`/`getCharacterNames()`, `STORY-APP-010`, for its two selectors) | indirectly, via the application service (`CraftingPlanner`, `RecipeTreeBuilder`) | indirectly, via the application service (4 repos) | no |
| `BankView` | none | yes (`BankContentsService`, `STORY-APP-009`) | no | indirectly, via the application service (`repo.BankRepository`, on `repo.Db.open()`) | no |
| `MaterialsView` | none | yes (`MaterialStorageService`, `STORY-APP-009`) | no | indirectly, via the application service (`repo.MaterialStorageRepository`, on `repo.Db.open()`) | no |
| `EctoView` | none | yes (`EctoSalvageService`, `STORY-APP-003`) | indirectly, via the application service (`ecto.EctoSalvageCalculator`) | no | indirectly, via the application service (`api.tp.EctoLivePriceGateway` → `api.guildwars2.com/v2/commerce/prices`); icon fetching remains a direct `HttpClient` call in the view |
| `Gw2App` | none | "Sync Account" (`application.AccountRefreshService`, `STORY-APP-004`), "Sync ALL tradeable Items..." (`application.GlobalDataRefreshService`/`application.CraftingGraphRebuildService`, `STORY-APP-005`), and "First-time DB Setup" (`application.InitialSetupService`, `STORY-APP-007`) | indirectly, via the three application services for all three migrated buttons | indirectly, via the three application services for all three migrated buttons | indirectly via `sync.*` (`AccountRefreshService`'s `sync.AccountRefreshGateway` collaborator for Sync Account; `GlobalDataRefreshService`'s `sync.GlobalDataRefreshGateway` collaborator for Sync ALL tradeable Items; `InitialSetupService`'s `sync.AccountRefreshGateway`/`sync.GlobalDataRefreshGateway`/`sync.IconSyncGateway` collaborators, plus `application.TradingPostPriceRefreshService`, for First-time DB Setup) |

The three crafting/Ecto features (Profit, Discovery, Ectoplasm Salvage) follow a View → Application → Domain(/Repository) separation; `EctoView` has no Controller layer, calling its application service directly. `Gw2App`'s "Sync Account", "Sync ALL tradeable Items..." and "First-time DB Setup" buttons now follow the same pattern; `STORY-APP-009` brought `BankView` and `MaterialsView` onto it too, so no view opens a database connection or runs SQL itself any more; `STORY-APP-010` removed the last direct repository construction from a view, so no view instantiates a `repo.*` repository either. Eleven named Application Layer boundaries now exist (`application.CraftingProfitService`/`STORY-APP-001`, `application.CraftingDiscoveryService`/`STORY-APP-002`, `application.EctoSalvageService`/`STORY-APP-003`, `application.AccountRefreshService`/`STORY-APP-004`, `application.GlobalDataRefreshService`/`application.CraftingGraphRebuildService`/`STORY-APP-005`, `application.TradingPostPriceRefreshService`/`STORY-APP-006`, `application.InitialSetupService`/`STORY-APP-007`, `application.BankContentsService`/`application.MaterialStorageService`/`STORY-APP-009`, `application.CharacterSelectionService`/`STORY-APP-010`); the other views' remaining direct API/HTTP use in this table is unaffected by those stories. `STORY-APP-008` added no boundary of its own — it routed both crafting views' auto-refresh timers through the existing `application.AccountRefreshService` (§5.4), after which no view calls `sync.*` directly any more.

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
- Configuration is supplied via environment variables / `.env` as described above. The one exception is the HTTP API runtime (`STORY-API-001`): `src/main/resources/application.properties` holds Spring Boot's own settings, and its only non-default value is the listen port, itself externalised as `server.port=${GW2_API_PORT:8080}` (environment variable, or any standard Spring override) rather than hard-coded. Database and GW2 API credentials still come from `repo.EnvConfig`; the API runtime adds no second credential path.
- The `Gw2App` home screen has an API-key `TextField` and "Save" button whose handler only sets a status label (`"API key saving not implemented yet."`) and does not persist anything — confirmed by direct reading of `Gw2App.java`.

---

## 9. Areas Where Responsibilities Are Mixed

Observed (not inferred) mixing of concerns, by file:

1. **(Resolved by `STORY-DOM-017`)** ~~`craft/*` importing `repo/*` types directly.~~ The crafting engine's core data types are now independent domain types (`craft.Recipe`, `craft.Ingredient`, `craft.PriceQuote`); `repo.RecipeRepository`/`repo.tp.TpPriceRepository` map persistence rows into them, and `craft.*` no longer imports anything from `repo.*`.
2. **(Resolved by `STORY-DOM-017`/`STORY-DOM-018`/`STORY-DOM-019`)** ~~`repo.RecipeRepository.loadRecipes(...)` embeds the "recipe is unlocked" business rule as a SQL `UNION` CTE.~~ The unlock decision is now `craft.RecipeKnowledgePolicy.isKnownAccountWide` (a single pure domain policy function, no JDBC/SQL dependency); `repo.RecipeRepository` fetches plain unlock facts and calls the policy in Java. `STORY-DOM-018` extracted the policy but left `loadRecipesForCharacter`/`loadMissingDiscoverableRecipeIdsForCharacter` checking only the selected character's own unlocks (tracked as a confirmed disagreement in `docs/KNOWN_PROBLEMS.md` §4.2); `STORY-DOM-019` corrected both to use the same account-wide (any-character) knowledge decision as `loadRecipes`, per `DOMAIN_SPEC.md` §34/35 and decided `DQ-010`.
3. **(Resolved by `STORY-APP-003`)** ~~`EctoView` calls its own `EctoSalvageCalculator` directly, with no application-service boundary.~~ `EctoView` is the sole Ectoplasm Salvage implementation in the codebase. Its calculation (`DUST_PER_ECTO = 0.75`, `LUCK_PER_ECTO = 20.0`, `ECTOS_PER_1000_LUCK = 50`, and DOMAIN_SPEC.md §46-47's fee-inclusive net cost) lives in `ecto.EctoSalvageCalculator`, a plain domain class with no JavaFX/repo/controller/application dependency. `EctoView` calls one `application.EctoSalvageService`, which fetches Ecto/Dust quotes via `api.tp.EctoLivePriceGateway` and invokes the calculator across all four Ecto-buy/Dust-sell combinations; `EctoView`'s `fillProfitGrid`/`fillLuckGrid` only format and display the returned results. Like Profit/Discovery, it now has a named Application Layer boundary, though (unlike those two) with no Controller layer in between.
4. **(Resolved by `STORY-APP-004`/`STORY-APP-005`/`STORY-APP-006`/`STORY-APP-007`)** ~~`Gw2App`'s "First-time DB Setup" button handler directly calls `InitialSetupService`/`sync.*`.~~ All three `Gw2App` sync buttons ("Sync Account", "Sync ALL tradeable Items...", "First-time DB Setup") now delegate to named application services (`application.AccountRefreshService`/`application.GlobalDataRefreshService`/`application.InitialSetupService`) instead of calling `sync.*`/`repo.*` directly from the button handler. `application.InitialSetupService.firstFill()` (`STORY-APP-007`) owns the setup orchestration that previously lived in a top-level `InitialSetupService` class outside the application layer; that class has been deleted.
5. **(Resolved by `STORY-INFRA-003`)** ~~Two independent JDBC connection helpers (`repo.Db`, `sync.Db`) with different method names but identical behavior.~~ `sync.Db` was removed; all `repo.*` and `sync.*` callers now share `repo.Db.open()`.
6. **(Resolved by `STORY-APP-009`)** ~~`BankView`/`MaterialsView` each declare their own literal `DB_URL`/`DB_USER`/`DB_PASS` constants and call `DriverManager.getConnection(...)` directly, bypassing `repo.AppConfig`/`repo.EnvConfig`/`repo.Db` entirely.~~ Both views now read through an application service (`application.BankContentsService`, `application.MaterialStorageService`) over a persistence adapter (`repo.BankRepository`, `repo.MaterialStorageRepository`) that opens its connection with the shared `repo.Db.open()` helper from item 5. The two extra connection-acquisition paths no longer exist (`docs/KNOWN_PROBLEMS.md` §2.2).

---

## 10. Status

This document reflects a point-in-time reading of the files listed in §2–§9. Files not explicitly named above (e.g. most of `parser/*`, `sync/CharacterSync`, `sync/ItemSync`, `sync/RecipeSync`, `sync/TpSync` internals, `sync/tp/relevance/*`) were identified and classified by responsibility but not read in full line-by-line detail during this pass. Deeper inspection of those files may refine this document further.
