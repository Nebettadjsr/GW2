# GW2 Tool â€” Current Architecture

## 1. Purpose and Scope

This document describes how the existing implementation is actually structured, based on direct inspection of the source tree under `src/`. It is descriptive, not prescriptive â€” see `TARGET_ARCHITECTURE.md` for the intended direction and `KNOWN_PROBLEMS.md` for risk analysis.

All statements below are **observed facts** unless explicitly marked as an inference. No source files were modified while producing this document.

---

## 2. Physical Package Layout

```text
src/
â”œâ”€â”€ (default package)        Gw2App, BankView, MaterialsView, EctoView,
â”‚                             CraftingProfitView, CraftingProfitController,
â”‚                             CraftingDiscoveryView, CraftingDiscoveryController,
â”‚                             CraftingResultPresentation
â”œâ”€â”€ application/              CraftingProfitService (Application Layer use case for the Crafting
â”‚                             Profit flow, STORY-APP-001), CraftingDiscoveryService (same for the
â”‚                             Crafting Discovery flow, STORY-APP-002), EctoSalvageService (same for
â”‚                             the Ectoplasm Salvage flow, STORY-APP-003; TARGET_ARCHITECTURE.md Â§8),
â”‚                             BankContentsService / MaterialStorageService (the bank and
â”‚                             material-storage read use cases, STORY-APP-009),
â”‚                             CharacterSelectionService (the character/crafting-discipline read
â”‚                             both crafting views' selectors are populated from, STORY-APP-010),
â”‚                             AccountRefreshService (STORY-APP-004), GlobalDataRefreshService /
â”‚                             CraftingGraphRebuildService (STORY-APP-005),
â”‚                             TradingPostPriceRefreshService (STORY-APP-006),
â”‚                             InitialSetupService (STORY-APP-007) â€” the synchronization and
â”‚                             setup use cases, which are also the ones the web sync/refresh
â”‚                             triggers submit as task bodies (Â§5.7-Â§5.9),
â”‚                             CraftingResolutionDetail (what one resolution-detail operation of
â”‚                             both crafting services produced, STORY-APP-012, Â§5.12)
â”‚   â””â”€â”€ icons/                ItemIconUrls (the application-relative icon URL format and its route
â”‚                             parsing), IconDelivery / IconDeliveryResult (the icon-delivery
â”‚                             boundary over the filesystem and upstream adapters, STORY-API-009,
â”‚                             Â§5.14)
â”œâ”€â”€ infra/
â”‚   â””â”€â”€ icons/                IconSourcePolicy / IconSource (the canonical upstream-source policy
â”‚                             and derived cache key), IconStore / FilesystemIconStore / StoredIcon /
â”‚                             IconStorageException (the persistent keyed cache),
â”‚                             IconImageFetcher / HttpIconImageFetcher / IconFetchResult /
â”‚                             IconImageBytes (the upstream image adapter and its validation),
â”‚                             IconAcquisition / IconAcquisitionResult (shared miss coordination),
â”‚                             IconCacheBounds (the chosen protective limits) â€” STORY-API-009, Â§5.14
â”œâ”€â”€ api/                      Gw2ApiClient, Gw2PriceFetch, BatchUtils, HttpStatus
â”‚   â””â”€â”€ tp/                   TpPriceApi, EctoLivePriceGateway (live Ecto/Dust price lookup for
â”‚                             EctoSalvageService, STORY-APP-003 - distinct from TpPriceApi's
â”‚                             DB-sync batch fetching)
â”œâ”€â”€ craft/                    CraftingPlanner, CraftingResolver, CostEvaluator,
â”‚                             RecipeSimulator, CraftingGraph, RecipeTreeBuilder,
â”‚                             PlanState, PlannerContext, Node, ResolvedNeed,
â”‚                             ResolvedNeedMapper, ResolveResult, CraftResult,
â”‚                             CostEvaluationResult, RecipeSimulationResult,
â”‚                             CraftingSettings, DailyCrafts, AcquisitionMode,
â”‚                             BlockedReason, Recipe, Ingredient, PriceQuote,
â”‚                             RecipeKnowledgePolicy, CharacterCraftingProfile,
â”‚                             SingleCraftExplainer, SingleCraftExplanation,
â”‚                             CraftTraceNode, AcquisitionMethod, ResolutionState
â”‚                             (single-craft explanation, Â§5.12),
â”œâ”€â”€ ecto/                     EctoSalvageCalculator (Ectoplasm Salvage domain calculation, no
â”‚                             JavaFX/repo/controller/application dependency; moved out of the
â”‚                             default package by STORY-APP-003 so application.EctoSalvageService
â”‚                             can import it; charges its selling fee through tradingpost.
â”‚                             TradingPostFeePolicy since STORY-DOM-024)
â”œâ”€â”€ model/                    BankSlot, CharacterCraftingRow, CharacterInfo,
â”‚                             CharacterRecipeRow, MaterialStack, Price
â”œâ”€â”€ parser/                   BankParser, CharacterCraftingParser, CharacterNamesParser,
â”‚                             CharacterParser, CharacterRecipesParser, IdListParser,
â”‚                             ItemParser, MaterialParser, RecipeIdParser, RecipeParser,
â”‚                             TpPriceParser
â”œâ”€â”€ repo/                     AppConfig, Db, DiscChoice, CharacterRepository,
â”‚                             InventoryRepository, ItemRepository, RecipeRepository,
â”‚                             CraftingGraphCache, CraftingGraphDto,
â”‚                             BankRepository, MaterialStorageRepository (slot-/stack-level
â”‚                             display reads for the Bank and Materials views, STORY-APP-009),
â”‚                             ItemIconMetadataRepository (one item's retained icon source, read
â”‚                             only on an image-cache miss, STORY-API-009)
â”‚   â””â”€â”€ tp/                   TpPriceRepository
â”œâ”€â”€ tradingpost/              TradingPostFeePolicy (the decided 15% profitability fee, STORY-DOM-023),
â”‚                             TradingPostSaleCalculator (shared Trading Post sale fee/net-proceeds
â”‚                             calculation for one explicitly stated sale, STORY-DOM-022; no caller
â”‚                             yet - see Â§9)
â”œâ”€â”€ sync/                     AccountSync, CharacterSync, IconSync, ItemSync,
â”‚                             RecipeSync, SyncConstants, TpSync,
â”‚                             DesktopIconAdoption (gives one item a shared keyed icon file for
â”‚                             items.icon_path, STORY-API-009, Â§5.14)
â”‚   â””â”€â”€ tp/relevance/         CraftingProfitItemCollector, DiscoveryItemCollector
â”œâ”€â”€ util/                     CoinUtils, DbBind, TpPrice
â”œâ”€â”€ web/                      Gw2ApiApplication (Spring Boot HTTP entry point),
â”‚                             CraftingProfitApiController, CraftingProfitApiMapper
â”‚                             (STORY-API-001), CraftingDiscoveryApiController,
â”‚                             CraftingDiscoveryApiMapper (STORY-API-002), CraftingRowMapper
â”‚                             (shared row copying), ApiExceptionHandler, ApiValidationException,
â”‚                             AccountSyncApiController, SyncTaskApiController,
â”‚                             SyncTaskStatusMapper, SyncApiExceptionHandler,
â”‚                             UnknownTaskException (STORY-API-003),
â”‚                             GlobalSyncApiController, SyncRequestValidation
â”‚                             (STORY-API-004 â€” the latter is the parameterless-body rule
â”‚                             both sync triggers share),
â”‚                             PriceRefreshApiController, PriceRefreshVariant
â”‚                             (STORY-API-005 â€” the latter maps the requested variant to
â”‚                             its use-case method and its operation key),
â”‚                             CraftingSelectorOptionsApiController,
â”‚                             CraftingSelectorOptionsApiExceptionHandler
â”‚                             (STORY-API-006 â€” the read-only scope-selector route and
â”‚                             its own status mapping),
â”‚                             BankContentsApiController, MaterialStorageApiController,
â”‚                             AccountReadApiExceptionHandler
â”‚                             (STORY-API-007 â€” the two read-only account-inventory routes
â”‚                             and the status mapping they share),
â”‚                             CraftingResolutionMapper, RecipeNotInCalculationException
â”‚                             (STORY-API-008 â€” the part of the resolution-detail contract
â”‚                             both crafting controllers share, and its one 404 outcome),
â”‚   â”œâ”€â”€ task/                 BackgroundTaskService, TaskState, TaskSnapshot,
â”‚   â”‚                         TaskAlreadyRunningException (STORY-API-003) â€” the in-process
â”‚   â”‚                         background-task facility the sync triggers share; plain Java, no
â”‚   â”‚                         Spring/HTTP/domain import
â”‚   â””â”€â”€ dto/                  CraftingProfitRequest/Response, CraftingDiscoveryRequest/Response,
â”‚                             CraftingRowDto, MissingItemDto, TradingPostQuoteDto (shared by both
â”‚                             calculation routes), ApiErrorResponse, SyncTaskAcceptedResponse,
â”‚                             SyncTaskStatusResponse (STORY-API-003),
â”‚                             CraftingSelectorOptionsResponse (STORY-API-006),
â”‚                             BankContentsResponse, MaterialStorageResponse
â”‚                             (STORY-API-007),
â”‚                             CraftingProfitResolutionRequest/Response,
â”‚                             CraftingDiscoveryResolutionRequest/Response, ResolutionNodeDto
â”‚                             (STORY-API-008 â€” the two detail envelopes and the recursive
â”‚                             node both share) â€”
â”‚                             transport-only types, distinct from craft.*/repo.*/GW2 JSON
â””â”€â”€ resources/                Styles/, images/, application.properties
```

The HTTP layer is named `web`, not `api` as `TARGET_ARCHITECTURE.md` Â§31 sketches: `api` already denotes the *outbound* GW2 API client in this source tree, so the inbound boundary took a different name rather than overloading it.

The top-level (default, unnamed) Java package contains the JavaFX entry point, all views, both crafting controllers, and two standalone service classes. Java classes in the default package cannot be imported by classes in named packages, which is itself an architectural constraint on how these pieces can be reused - it is why `STORY-APP-003` had to move `EctoSalvageCalculator` into the new `ecto` package for `application.EctoSalvageService` (a named package) to depend on it, while `EctoView` itself stays in the default package.

`Main.java` (the legacy standalone Ecto CLI calculator) was deleted per the resolved `agent/user-decisions/UD-002-ectoplasm-salvage-fee-model.md` (`docs/KNOWN_PROBLEMS.md` Â§3.6) and no longer exists in the source tree.

The project is now built with Maven (`./mvnw`, Java 25 target), using the standard `src/main/java` / `src/main/resources` / `src/test/java` layout. Jackson, the PostgreSQL driver, JavaFX, JUnit and (since `STORY-API-001`) Spring Boot are Maven dependencies; there is no `lib/` directory of manually-managed JARs anymore. The `spring-boot-dependencies` BOM is imported, so Jackson and JUnit versions are managed by it rather than pinned individually â€” Spring Boot 4 serves HTTP with Jackson 3 (`tools.jackson`) while the existing GW2/cache code keeps using Jackson 2, and the two share the `com.fasterxml.jackson.annotation` artifact, which a separate pin would desynchronise. A `src/test/java` tree exists, holding the layered suites whose methodology `docs/TEST_STRATEGY.md` owns.

---

## 3. Package Responsibilities

| Package | Observed responsibility |
|---|---|
| default package | JavaFX application shell (`Gw2App`), 5 JavaFX views, 2 feature controllers, and the shared blocked/unavailable-row presentation helper (`CraftingResultPresentation`). The former top-level `InitialSetupService`/`AccountRefreshService` classes no longer exist â€” that orchestration moved into `application.*` (Â§4, `STORY-APP-004`/`STORY-APP-007`). |
| `application` | Application Layer use-case orchestration (`TARGET_ARCHITECTURE.md` Â§8): `CraftingProfitService`, coordinating `repo.*` loading, coordinated-roster construction and `craft.CraftingPlanner`/`RecipeTreeBuilder` invocation for the Crafting Profit flow (Â§5.1, `STORY-APP-001`); `CraftingDiscoveryService`, the same kind of boundary for the Crafting Discovery flow (Â§5.2, `STORY-APP-002`) - missing-discoverable-recipe/graph/inventory/price/item loading and single-character `craft.CraftingPlanner.evaluateAll`/`RecipeTreeBuilder` invocation; and `EctoSalvageService`, coordinating `api.tp.EctoLivePriceGateway` price acquisition and `ecto.EctoSalvageCalculator` invocation for the Ectoplasm Salvage flow (Â§5.3, `STORY-APP-003`). None of the three classes has a JavaFX dependency. |
| `api` | Low-level HTTP client for the GW2 API (`Gw2ApiClient`), batching helper, HTTP status handling, `Gw2PriceFetch` â€” an ad-hoc price fetcher that has had no caller anywhere in the codebase since `Main` (its only caller) was deleted (see `docs/KNOWN_PROBLEMS.md` Â§7.8) â€” and `api.tp.EctoLivePriceGateway`, the live (unsynchronized) Ecto/Dust price lookup used by `application.EctoSalvageService` (`STORY-APP-003`) |
| `craft` | Persistence-independent crafting domain: independent domain types (`Recipe`, `Ingredient`, `PriceQuote`), the recipe-known policy (`RecipeKnowledgePolicy`), recipe selection, inventory consumption, craft-vs-buy decisions, cost/profit math, and resolution tree construction. No JDBC/SQL/repository/transport dependency (`TARGET_ARCHITECTURE.md` Â§7). |
| `ecto` | Persistence-independent Ectoplasm Salvage domain calculation (`EctoSalvageCalculator`): the fee-inclusive net cost/profit/Luck-cost math (DOMAIN_SPEC.md Â§45-47), with the selling fee itself taken from `tradingpost.TradingPostFeePolicy` rather than owned here (`STORY-DOM-024`). No JDBC/SQL/repository/transport/JavaFX dependency. Moved out of the default package by `STORY-APP-003` so `application.EctoSalvageService` can import it. |
| `tradingpost` | Two persistence-independent Trading Post domain calculations, each with its own model. `TradingPostFeePolicy` owns the decided **profitability** fee (DOMAIN_SPEC.md Â§25, resolved `UD-011`, `STORY-DOM-023`): 15% of whatever gross sell value the caller states, in exact integer copper rounded half away from zero, with no minimum. `craft.CraftingPlanner` and `ecto.EctoSalvageCalculator` are its callers. `TradingPostSaleCalculator` owns the **transaction** model: gross sale value, separately rounded 5% listing and 10% exchange fees with their 1-copper minimums, total fees and net proceeds for one sale the caller states explicitly as unit price Ã— quantity (Â§25.1, `STORY-DOM-022`); Â§25.1 forbids substituting it for the policy above, and nothing calls it yet (Â§9). Neither has a JDBC/SQL/repository/transport/JavaFX dependency or a dependency on `craft`/`ecto`, and neither selects a sale mode, price source or sale grouping of its own. |
| `model` | Plain data records used mainly during API-response parsing (bank slots, character rows, material stacks, price) |
| `parser` | Converts raw GW2 API `JsonNode` responses into `model` records or repository-ready structures |
| `repo` | PostgreSQL access via JDBC (`Db`) plus per-domain repositories (items, recipes, inventory, characters, TP prices), the persistence-to-domain mapping boundary for `craft.*` types (`TARGET_ARCHITECTURE.md` Â§10), the crafting-graph JSON cache (`CraftingGraphCache`/`CraftingGraphDto`), and hardcoded configuration (`AppConfig`) |
| `sync` | Orchestrates: call `api` â†’ parse via `parser` â†’ upsert via JDBC directly (not via `repo` repositories) into PostgreSQL, sharing `repo.Db.open()` with the `repo` package. |
| `util` | Coin formatting, JDBC null-binding helpers, a `TpPrice` value type |
| `web` | Inbound HTTP boundary (`TARGET_ARCHITECTURE.md` Â§9 and Â§13, `STORY-API-001`/`STORY-API-002`/`STORY-API-003`/`STORY-API-004`/`STORY-API-005`/`STORY-API-006`/`STORY-API-007`/`STORY-API-008`): Spring Boot entry point (`Gw2ApiApplication`), the Crafting Profit and Crafting Discovery routes (`CraftingProfitApiController`, `CraftingDiscoveryApiController`), their request defaulting/validation and DTO translation (`CraftingProfitApiMapper`, `CraftingDiscoveryApiMapper`, plus `CraftingRowMapper` for the row projection both share), the two resolution-detail operations on those same controllers with the input rules, envelope literals and recursive tree copy they share (`CraftingResolutionMapper`, `RecipeNotInCalculationException`), the read-only crafting selector-options route (`CraftingSelectorOptionsApiController`), the two read-only account-inventory routes (`BankContentsApiController`, `MaterialStorageApiController`), the account- and global-synchronization triggers and the Trading Post price-refresh trigger with their shared task-status route and shared body-strictness rule (`AccountSyncApiController`, `GlobalSyncApiController`, `PriceRefreshApiController` with `PriceRefreshVariant`, `SyncTaskApiController`, `SyncTaskStatusMapper`, `SyncRequestValidation`), and the status mapping (`ApiExceptionHandler` for the calculation routes, `SyncApiExceptionHandler` for the synchronization routes, `CraftingSelectorOptionsApiExceptionHandler` for the selector read, `AccountReadApiExceptionHandler` for the two account reads, each scoped to its own controllers). Contains no crafting rule, no synchronization step and no orchestration â€” each route calls its existing application service and copies what comes back. `web.dto` holds transport-only records. |
| `web.task` | The in-process background-task facility the synchronization triggers share (`STORY-API-003`, `TARGET_ARCHITECTURE.md` Â§23): identifier issue, lifecycle state, one-unfinished-task-per-operation admission, virtual-thread execution and bounded in-memory retention (`BackgroundTaskService`, `TaskState`, `TaskSnapshot`, `TaskAlreadyRunningException`). Plain Java â€” no Spring, servlet, HTTP or domain import â€” so the asynchrony is testable without a server and reusable by later sync/refresh routes. |

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
- **`CraftingDiscoveryController` no longer imports `repo.RecipeRepository`/`InventoryRepository`/`TpPriceRepository`/`ItemRepository`/`CraftingGraphCache`, or `craft.CraftingPlanner`/`RecipeTreeBuilder`/`PlannerContext` directly (`STORY-APP-002`).** It imports and holds one `application.CraftingDiscoveryService`, which owns those calls; the controller keeps only presentation formatting (`UiRow`/`prepareRows`, missing-summary text, the item-name search blob) and delegates `getResultByRecipeId(...)`/`itemName(...)`/`itemSellUnit(...)` straight through to the service, which is also where that lookup state (`lastItems`/`lastTp`/`lastAllRecipes`/etc.) now lives â€” including the pre-existing "no missing recipes for this character+discipline" short-circuit that leaves that lookup state untouched rather than clearing it (unchanged behavior, preserved verbatim). The application boundary now covers both crafting flows.
- **The `craft` package (the domain/business-logic layer) no longer imports or depends on any `repo.*` type (`STORY-DOM-017`).** Independent domain types `craft.Recipe`, `craft.Ingredient`, and `craft.PriceQuote` replace the formerly repository-nested classes; `repo.RecipeRepository`/`repo.tp.TpPriceRepository` own the persistence-to-domain mapping (`TARGET_ARCHITECTURE.md` Â§10). `CraftingGraphCache`/`CraftingGraphDto` (the on-disk crafting-graph cache, which depends on `RecipeRepository` and Jackson) moved to `repo.*` for the same reason. Verified by dependency inspection: no `src/main/java/craft/*.java` or `src/test/java/craft/*.java` file imports `repo.*`.
- `repo.RecipeRepository.loadRecipes(...)`/`loadRecipesForCharacter(...)`/`loadMissingDiscoverableRecipeIdsForCharacter(...)` fetch plain unlock facts (`account_recipes`/`character_recipes` ids) and delegate the "recipe is unlocked" decision to `craft.RecipeKnowledgePolicy.isKnownAccountWide` (see Â§9 item 2) rather than expressing it as SQL. All three entry points now use the same account-wide fact (`STORY-DOM-019`).
- `sync` and `repo` are two independent, parallel data-access areas, but both now share the same `repo.Db.open()` connection helper (`sync.Db` was removed by `STORY-INFRA-003`), which reads `repo.AppConfig` credentials.
- Views call controllers; in the two crafting features (profit/discovery) the controllers no longer call `repo.*` repositories (including `repo.CraftingGraphCache`) or `craft.CraftingPlanner` themselves â€” their application services do (see the two bullets above), and the controllers keep only the `repo.DiscChoice`/`repo.ItemRepository.ItemInfo` types their formatting needs. Both crafting **views** obtain their selector contents through `application.CharacterSelectionService` as well (`STORY-APP-010`): neither view instantiates or queries `repo.CharacterRepository` any more, and no view in the codebase constructs a repository. Both still use `craft.*` result/settings types (`CraftResult`, `CraftingSettings`, `Node`, plus `Recipe`/`Ingredient` in Discovery) for rendering, and the selector read hands back the `repo.CharacterRepository.DiscRow` persistence row type â€” see `docs/KNOWN_PROBLEMS.md` Â§4.4 (CH-E2, both the selector portion and the total-profit duplication resolved). `CraftingProfitView` displays and sorts the total profit supplied by `CraftingProfitController.UiRow` (`STORY-APP-011`) and, since `STORY-APP-013`, the total sell value the same row carries from `CraftResult.totalSellValueCopper`; it derives neither figure itself.
- `EctoView` does not go through any repository or controller; it calls one `application.EctoSalvageService` (`STORY-APP-003`), which fetches Ecto/Dust quotes via `api.tp.EctoLivePriceGateway` (a plain `HttpClient` call, not `repo.tp.TpPriceRepository`/`api.Gw2ApiClient`) and delegates its calculation to `ecto.EctoSalvageCalculator`, which has no JavaFX/repo/controller/application dependency (see Â§7). Icon fetching remains a direct `HttpClient` call inside the view itself (presentation-only, not part of the price/calculation use case).
- `BankView` and `MaterialsView` each call one application service (`application.BankContentsService`, `application.MaterialStorageService`, `STORY-APP-009`) instead of connecting to the database themselves. Each service coordinates one persistence adapter (`repo.BankRepository`, `repo.MaterialStorageRepository`) that holds the SQL and opens its connection via the shared `repo.Db.open()` â†’ `repo.AppConfig`/`EnvConfig` path. Both views' former literal `DB_URL`/`DB_USER`/`DB_PASS` constants and inline `DriverManager` calls are gone (`docs/KNOWN_PROBLEMS.md` Â§2.2, resolved); neither view contains SQL or a `java.sql` import any more. Both flows are read-only â€” no write and no synchronization on either path. `STORY-API-007` added an HTTP entry point over each of the same two services (`web.BankContentsApiController`, `web.MaterialStorageApiController`, Â§5.12) without touching the services, the repositories or the views: the two paths call the identical method and the JavaFX one still runs in process.
- `Gw2App`'s three sync/setup button handlers call only `application.*` services; the `sync.*` calls and the `repo.CraftingGraphCache` rebuild now sit behind those services' gateways/collaborators (Â§5.4, Â§9 item 4). The former top-level `InitialSetupService` and `AccountRefreshService` classes no longer exist.
- **`web.*` is the only package that imports Spring (`STORY-API-001`, re-verified by `STORY-API-002`, `STORY-API-003`, `STORY-API-007` and `STORY-API-008`).** No `application.*`, `craft.*`, `ecto.*`, `repo.*` or `sync.*` class imports a Spring, servlet or HTTP-transport type, and no JavaFX class imports `web.*`: the two entry points (`Gw2App`, `web.Gw2ApiApplication`) sit side by side over the same application services, and the JavaFX path is unchanged by every one of those stories. The dependency runs one way â€” `web.*` â†’ `application.*` â†’ `craft.*`/`repo.*`.
- **The background-task concern sits inside `web.*`, not in the application layer (`STORY-API-003`, unchanged by `STORY-API-004` and `STORY-API-005`).** `web.task.BackgroundTaskService` imports only `java.*`, and `application.AccountRefreshService`/`application.GlobalDataRefreshService`/`application.TradingPostPriceRefreshService` are unchanged and unaware of it â€” each is handed to the facility as a method reference. Task identifiers, lifecycle state and the admission rule are therefore transport-boundary concepts that neither the JavaFX path nor the application layer can observe: `Gw2App`'s "Sync Account" and "Sync ALL tradeable Itemsâ€¦" buttons and both crafting views' "Refresh Trade Post Prices" buttons still call their use cases in process and synchronously, exactly as before. Adding the second and third triggers needed no change to the facility at all â€” only further operation keys.

---

## 5. Major Data Flows

### 5.1 Crafting Profit flow

Performance measurement must span the existing view â†’ controller â†’ `application.CraftingProfitService` â†’ repository/domain â†’ presentation/rendering path described below. The measured latency and verification status are owned by `KNOWN_PROBLEMS.md` Â§7.9; the Phase 3 acceptance requirement is owned by `TARGET_ARCHITECTURE.md` Â§33. Measured compliance with the Â§33 limit and the Product Owner's required subsequent dated confirmation both exist; `STORY-PERF-001`'s Result owns that evidence.

`STORY-PERF-001` attributed the real-database page-load cost along this whole path and changed it in four places; full before/after measurements live in that story's Result.

- **Two reloads per page open (presentation).** `CraftingProfitView.show(...)` ran its own initial `reloadTable` while the background discipline-selector load ended in `selectFirst()`, whose value listener fired a second, concurrent, identical reload. The listener now reloads only when the selection means a different scope (`sameScope(...)`; `null` and `Kind.ALL` are the same scope, since `CraftingProfitService.reload` treats them identically).
- **Console reload counters (presentation).** The reload-summary line logged per `KNOWN_PROBLEMS.md` Â§7.7 called `getResultByRecipeId(...)` once per visible recipe, building each row's resolution tree. It now uses `CraftingProfitService.getRawResultByRecipeId(...)`, which returns the planner's result without the lazy tree build. The logged counters are unchanged.
- **Speculation without copying (domain).** `craft.PlanState` carries an undo journal: `mark()`/`rollbackTo(...)`, plus `captureDelta(...)`/`applyDelta(...)` for choosing among several speculative attempts, and `commitTo(...)` for accepted work that is never revisited. `CraftingResolver.resolveNeed`/`tryCraft` and `RecipeSimulator.simulatePhase` now speculate on the live state and undo losing attempts, instead of deep-copying the whole inventory before every attempt and copying the winner back (UD-005 authorized this representation change). Every mutating path on `PlanState` is journalled, so external callers use `addMissingToBuy`/`addBuyCost`/`beginVisiting`/`endVisiting`/`setDailyLeft` rather than mutating its maps directly.
- **Memoized pure lookups (domain).** `PlannerContext` memoizes `eligibleCharactersFor(recipe)` and backs `CraftingResolver.firstRecipeFor(...)`; both answers depend only on data fixed for a context's lifetime. `PlannerContext.withBuyingDisabled()` derives `RecipeSimulator`'s zero-cash phase-1 context so it shares those tables instead of starting empty per recipe.

Every computed value is preserved: the planner's results were compared against a pre-change reference recorded from the real database across five settings combinations, with zero differences in any profit, cost, craftable count, blocked reason or resolution tree. The one observable change is that `CraftResult.missingToBuy`'s (unordered) map iteration order differs, which changes which two materials the deliberately truncated "To buy: â€¦" summary names first for a minority of rows when buying is enabled; the default view settings (buying disabled) are unaffected. See `STORY-PERF-001`'s Result.

STORY-DOM-014 removed Crafting Profit's separate Character selector; the Discipline selector
(`DiscChoice`) is the sole calculation-scope control (DOMAIN_SPEC.md section 2.2.1), defaulting to
`ALL`. Every scope resolves to a roster of `CharacterCraftingProfile` candidates that a single
coordinated planner assigns per recipe step, instead of restricting the whole plan to one
pre-selected character.

`STORY-APP-001` extracted this orchestration out of `CraftingProfitController` into a named
Application Layer use case, `application.CraftingProfitService` (`TARGET_ARCHITECTURE.md` Â§8):
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
   -> reload-summary counters (console only, KNOWN_PROBLEMS.md Â§7.7):
        CraftingProfitController.getRawResultByRecipeId(...) [no tree build]
   -> on row selection: CraftingProfitController.getResultByRecipeId(...)
        -> application.CraftingProfitService.getResultByRecipeId(...)
             -> RecipeTreeBuilder.buildTree(...) (lazy, scoped to the most recent reload(), cached
                  per-recipe so a later reload() cannot return a stale tree) -> Node tree
        -> View renders tree
```

`STORY-APP-012` added a second, independent entry point on the same service â€”
`resolveDetail(recipeId, choice, settings)`, described in Â§5.12 â€” which runs its own fresh load
and calculation for one selected recipe. It shares this flow's loading steps but none of the
reload-scoped `last*` state above, and the JavaFX path is unchanged.

**Material acquisition.** Crafting Profit uses the normal resolver for owned inventory,
recursive crafting and purchases. There is no item-ID tradeability classification in the
crafting path and no non-Trading-Post exclusion setting. `tp_tradeable_items` remains part
of Trading Post price-refresh relevance selection for Profit and Discovery.

### 5.2 Crafting Discovery flow

Unchanged in calculation by STORY-DOM-014 or STORY-APP-002: Discovery keeps its own
individual-only selectors (a Discipline+Character `DiscChoice` combo populated with
`CHAR_DISCIPLINE` entries only, plus a separate Character selector feeding the binding-aware
inventory lookup), the missing-discoverable-recipe lookup, the `recipe.minRating <= maxLevel`
filter, and the single-character `CraftingPlanner.evaluateAll(...)` path. `STORY-APP-002` extracted
this orchestration out of `CraftingDiscoveryController` into a named Application Layer use case,
`application.CraftingDiscoveryService` (`TARGET_ARCHITECTURE.md` Â§8), the same way `STORY-APP-001`
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
                  [account-wide recipe-knowledge policy, STORY-DOM-019 â€” empty result short-circuits
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

`STORY-APP-012` added a second, independent entry point on the same service â€”
`resolveDetail(recipeId, choice, settings, inventoryCharacterName)`, described in Â§5.12 â€” which
runs its own fresh load and calculation for one selected recipe, keeping the separate nullable
inventory character with the same unfiltered-pool fallback. It shares this flow's loading steps
but none of the reload-scoped `last*` state above, and the JavaFX path is unchanged.

### 5.3 JavaFX Ectoplasm Salvage flow (application-service boundary)

The historical second JavaFX implementation (`Main.java`) was deleted (Â§2, `docs/KNOWN_PROBLEMS.md` Â§3.6); `EctoView` is the desktop Ectoplasm Salvage code path. The browser page is described in Â§5.11. `STORY-APP-003` routed the desktop flow through an application service instead of the view acquiring prices and invoking the domain calculator directly (`docs/KNOWN_PROBLEMS.md` Â§4.4).

```text
EctoView
   -> application.EctoSalvageService.calculate()
        -> api.tp.EctoLivePriceGateway.fetchQuotes(...) (direct HttpClient call to GW2 commerce/prices API)
        -> ecto.EctoSalvageCalculator.evaluate(...) x4 (Ecto-buy/Dust-sell combinations)
           (scales the expected Dust yield onto the gross quote, then deducts tradingpost.
            TradingPostFeePolicy's 15% once from that value, DOMAIN_SPEC.md Â§25/Â§46/Â§47/DQ-011)
   -> fillProfitGrid / fillLuckGrid render the returned EctoScenarios; icon fetching remains a
      direct view concern (presentation-only, not part of the price/calculation use case)
```

`EctoLivePriceGateway` is a live, unsynchronized lookup for exactly Ecto/Dust - distinct from the DB-backed `repo.tp.TpPriceRepository` the Crafting flows use; it is not part of the `sync.*`/`repo.*` synchronization machinery described in Â§5.4.

The former HTTP entry point over this service has been removed (Â§5.15). The JavaFX view still calls the service in process; its gateway and calculator remain in use.

### 5.4 Synchronization / initialization flow

#### Current refresh redesign (2026-09)

The browser Crafting Profit page now offers **Refresh data & results**. It submits the existing account-refresh task key using `AccountRefreshService.refreshCraftingProfitData()` (bank, materials, account recipes, and character crafting/recipes/inventory; it deliberately skips unrelated account Luck), waits for completion, and reloads the calculation. That calculation routes through the shared Profit-relevant stale-price refresh path before calculating. The account task and calculation phase are visible in the page's progress/error feedback. The full account refresh remains available in System Status.

The browser navigation calls the former Synchronization destination **System Status**. It presents compact account, global-data, and shared Trading Post cache health cards with color and text status, last checked/changed timestamps, and refresh task outcomes. Its manual actions are limited to account refresh and global-data check; TP prices refresh on demand through the shared cache. Task status is in-memory only, and the page does not claim durable history or scheduler telemetry.

Global refresh now compares the cheap recipe and TP-tradeable ID sets before downloading detail data. Equal recipe ID sets skip recipe details and graph rebuild. Changed IDs cause detail persistence and deleted-recipe/ingredient cleanup; only after that succeeds does `GlobalDataRefreshService` rebuild the graph. A six-hour-by-default Spring scheduled check (one-minute initial delay) submits the same `GLOBAL_SYNC` work to the existing in-process `BackgroundTaskService`; interval values use `GW2_GLOBAL_REFRESH_INTERVAL_MS` and `GW2_GLOBAL_REFRESH_INITIAL_DELAY_MS`. Duplicate manual/scheduled global tasks share that admission key. Profit and Discovery calculation routes call their feature's relevant-ID collector and feed stale/missing quotes through the same shared `tp_prices` cache and batched gateway. Fresh quotes younger than ten minutes are skipped. This API refresh is currently synchronous with the calculation request; the explicit Profit workflow keeps account synchronization asynchronous.

The intended future data scope is account state per user/account and shared GW2 metadata, TP prices, and graph. The current app remains single-user and one backend instance; multi-replica shared job coordination is deferred until replicas exist.

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

`STORY-APP-006` routed Trading Post price refresh through a named Application Layer use case, `application.TradingPostPriceRefreshService` (`TARGET_ARCHITECTURE.md` Â§8): it exposes the two pre-existing, distinctly scoped variants - `refreshForDiscovery()` and `refreshForProfit()` - each delegating to its own `sync.TradingPostPriceRefreshGateway` call (a thin instantiable wrapper over the non-instantiable `TpSync`, mirroring `AccountRefreshGateway`/`GlobalDataRefreshGateway`'s precedent). Item relevance selection (`sync.tp.relevance.DiscoveryItemCollector`/`CraftingProfitItemCollector`), quote fetching and `tp_prices` persistence are unchanged - the service only replaces the call sites, not `TpSync`'s own implementation. `CraftingDiscoveryView`'s and `CraftingProfitView`'s "Refresh Trade Post Prices" buttons now call the service instead of `TpSync` directly (each view keeps its own distinct variant; neither refreshes the other's scope), and `application.InitialSetupService.firstFill()` (`STORY-APP-007`) calls the same service directly as one of its own collaborators, preserving its exact position (after tradeable-item sync, before icon sync) and discovery-then-profit order. Tradeable-item synchronization (`TpSync.syncTpTradeableItems()`) remains outside this service in every caller, per `STORY-APP-005`'s constraint that it stay distinct from price refresh.

`STORY-APP-004` routed the "Sync Account" button through a named Application Layer use case, `application.AccountRefreshService` (`TARGET_ARCHITECTURE.md` Â§8), instead of `Gw2App` calling `sync.*` directly: the service runs account bank, materials, recipes, Luck, then character crafting/recipes and short-circuits on the first failure. Luck was added after the original extraction (Â§5.16). It has no JavaFX import and coordinates one collaborator, `sync.AccountRefreshGateway` â€” a thin instantiable wrapper needed only because `AccountSync`/`CharacterSync` are non-instantiable static utility classes with no seam of their own; a fake subclass of the gateway substitutes for it in application-layer tests. This replaces the previously unused top-level `AccountRefreshService` (`docs/KNOWN_PROBLEMS.md` Â§8), which duplicated a subset of this orchestration but had no `Gw2App` call site; that dead class has been deleted.

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

`STORY-APP-005` similarly routed the "Sync ALL tradeable Items..." button through two named Application Layer use cases instead of `Gw2App` calling `sync.*`/`repo.*` directly. `application.GlobalDataRefreshService.refreshAll()` owns the exact pre-extraction call order (tradeable-item sync, then global recipe sync, then one graph rebuild) and short-circuits on the first failure, unchanged from the original inline sequence; it coordinates two collaborators: `sync.GlobalDataRefreshGateway` (a thin instantiable wrapper over the non-instantiable `TpSync`/`RecipeSync` statics, mirroring `AccountRefreshGateway`) and `application.CraftingGraphRebuildService`, which owns invocation of the existing `repo.CraftingGraphCache.rebuild()` and introduces no competing graph-building algorithm. Neither service has a JavaFX import; `CraftingGraphCache` â€” already an instantiable, overridable class â€” is `CraftingGraphRebuildService`'s sole replaceable collaborator, substituted with a fake subclass in application-layer tests. Cache format/location (`crafting_graph_cache.json`, relative to the process working directory) and `CraftingGraphCache.load()`'s existing auto-rebuild-when-missing behavior (`STORY-INFRA-002`) are unchanged. `InitialSetupService.firstFill()` was not touched by this story â€” its own distinct account/global-recipe/TP/icon operation selection and order remain exactly as before, and it still does not call the graph rebuild at all.

`STORY-APP-007` routed the "First-time DB Setup" button through a named Application Layer use case, `application.InitialSetupService` (`TARGET_ARCHITECTURE.md` Â§8), instead of `Gw2App` calling a top-level `InitialSetupService`/`sync.*` directly: `firstFill()` runs account bank/materials/recipes/Luck, global recipes, TP tradeable items, discovery price refresh, profit price refresh, icon URLs and icon disk download, short-circuiting on the first failure. Luck was added after the original extraction (Â§5.16). It has no JavaFX import and coordinates four collaborators: `sync.AccountRefreshGateway` (bank/materials/recipes/Luck methods only â€” not the character crafting/recipes step `application.AccountRefreshService` adds), `sync.GlobalDataRefreshGateway` (global-recipe/tradeable-item methods only â€” not the graph rebuild `application.GlobalDataRefreshService` adds, and in setup's own recipes-before-items order, the reverse of that service's items-before-recipes order), `application.TradingPostPriceRefreshService` (unchanged, `STORY-APP-006`), and a new `sync.IconSyncGateway` â€” a thin instantiable wrapper over the non-instantiable `IconSync` statics, mirroring `AccountRefreshGateway`/`GlobalDataRefreshGateway`/`TradingPostPriceRefreshGateway`'s precedent. Setup's step selection and order differ from both `AccountRefreshService`'s and `GlobalDataRefreshService`'s broader workflows, so this story reuses their gateways' individual methods directly rather than composing those two higher-level services, per the constraint against introducing character refresh or graph rebuild into setup. The icon-cache-directory path (`repo.AppConfig.ICON_CACHE_DIR`, env-var-configurable with a `<user.home>/...` default) is still read at `firstFill()` call time, not at construction time, matching the original's lazy-configuration-read timing. This replaces the previously separate top-level `InitialSetupService`, which duplicated this orchestration outside the application layer; that class (and its `syncTpPrices(...)` test seam and matching test) has been deleted so one authoritative setup workflow remains.

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
  `maxBuyCopper` 10000, instant sell, instant buy, daily crafts disabled). The response echoes
  the effective scope and settings.
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
  `totalSellValueCopper` â€” neither recomputed at this boundary from a count Ã— a per-craft figure;
  `craft.CraftingPlanner` carries the sell value onto `craft.CraftResult` from `DOMAIN_SPEC.md` Â§24's
  per-execution output revenue and Â§28's craftable count, gross and with the recipe's output
  quantity in it once; Â§25's 15% fee is deducted only from the two profit figures), the
  `craft.BlockedReason` name, both missing-material maps (sorted by item id for a
  stable wire order), the raw trading-post quotes and, since `STORY-API-009` (Â§5.14), a nullable
  `iconUrl` on the row (the recipe's **output item**) and on each missing material (its own item).
  Rows with no calculation result are reported
  with `resultAvailable: false` instead of being dropped. Presentation text produced by the JavaFX
  layer (status strings, "no TP" markers, search blobs) is deliberately absent â€” it is a rendering
  rule, not domain state. The per-row resolution tree (`TARGET_ARCHITECTURE.md` Â§13) is also not on
  this route: the application service builds it lazily per selected row, so it belongs to the
  separate detail route of Â§5.13, which this response still does not eagerly attach. Since `STORY-API-002` the row itself is `web.dto.CraftingRowDto`, shared with
  Discovery (Â§5.6) and copied by the shared `web.CraftingRowMapper`; the JSON field names are
  unchanged.
- **Synchronous, by measurement.** Per `TARGET_ARCHITECTURE.md` Â§23 / `UD-007`, the sync/async choice
  for a non-GW2-API endpoint follows measured behavior. Measured against the real database, this
  request completes well inside a normal HTTP request (see `STORY-API-001`'s Result for the
  figures), so it is served synchronously with no background task or status endpoint.

### 5.6 Crafting Discovery HTTP flow (`STORY-API-002`)

The second calculation route on the same runtime, over the same `application.CraftingDiscoveryService`
the JavaFX page uses (Â§5.2). JavaFX Discovery is untouched and keeps its in-process calls.

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
  one (Â§5.2). `scope.rating` is required rather than defaulted because it drives the
  `recipe.minRating <= rating` filter â€” defaulting it to 0 would silently answer with an emptier
  result than the caller asked for.
- **Separate inventory character.** `inventoryCharacterName` is optional and independent of
  `scope.characterName`, matching Discovery's second selector: supplied, it reaches the binding-aware
  owned-inventory lookup (`DOMAIN_SPEC.md` Â§11.1 / DQ-007); omitted or blank, it reaches the service
  as null, which keeps the service's own unfiltered-pool fallback (`STORY-DOM-012`) rather than the
  API inventing a substitute.
- **Discovery's own defaults**, taken from the JavaFX Discovery view's opening control state and
  deliberately *not* Profit's: `useOwnMats` true, `allowBuying` **true**, `maxBuyCopper` **200000**
  (its "20g" field), instant sell, instant buy. `allowDailyCrafts` is not a request field at
  all â€” Discovery fixes it to true to preserve its prior daily-crafting behavior â€” but the value
  used is echoed in the response's effective settings.
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
- **Response content** is the same `web.dto.CraftingRowDto` row as Profit (Â§5.5), produced by the
  same `web.CraftingRowMapper`, plus the echoed effective scope, inventory character and settings.
  Recipes whose calculation produced no result are reported with `resultAvailable: false` â€” where the
  JavaFX Discovery table drops them â€” so a client sees the complete recipe set.
- **Synchronous, by measurement.** Per `TARGET_ARCHITECTURE.md` Â§23 / `UD-007`, measured across every
  synced discipline+character combination on the real database (figures in `STORY-API-002`'s Result),
  including the largest result set. Comfortably inside a normal HTTP request, so it is served
  synchronously with no background task or status endpoint.

### 5.7 Account synchronization HTTP flow (`STORY-API-003`)

The first *asynchronous* route on this runtime, over the same `application.AccountRefreshService.refreshAll()`
the JavaFX "Sync Account" button calls (Â§5.4). JavaFX is untouched and keeps calling it in process and
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
    + Location header                        -> bank, materials, recipes, Luck, characters
    | 400 | 409                                 (order, persistence and short-circuiting
                                                 unchanged, inside the service)
```

Observed properties of this flow:

- **Asynchronous by decision, not by measurement.** `UD-007` / `TARGET_ARCHITECTURE.md` Â§23 require GW2
  API synchronization to run as a backend task with status reporting, so no duration was measured or
  assumed to reach that choice â€” unlike the two calculation routes (Â§5.5, Â§5.6), whose synchronous
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
  200 â€” a failed task is a successful *lookup* â€” and the state, never the HTTP status, carries the
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
  retained â€” older terminal records are evicted on submission, and an unfinished one is never evicted.
  Nothing is persisted, so a restart loses every record and shutdown interrupts a task in flight; work
  already committed to the database stays committed. An evicted identifier, one from a previous process
  and one that never existed are all the same 404, because the facility genuinely cannot tell them apart.
  The status route is shared by operation key rather than owned by this trigger, so a later sync/refresh
  route needs no status route of its own.

### 5.8 Global-data synchronization HTTP flow (`STORY-API-004`)

The second asynchronous route, over the same `application.GlobalDataRefreshService.refreshAll()` the
JavaFX "Sync ALL tradeable Items, Recipes and (re)build Crafting Graph" button calls (Â§5.4). JavaFX is
untouched and keeps calling it in process and synchronously.

It reuses Â§5.7's boundary rather than restating it: the same `web.task.BackgroundTaskService` (unchanged),
the same `GET /api/sync/tasks/{taskId}` status route, the same `SyncTaskStatusMapper`, the same
`SyncApiExceptionHandler` (which gained only this controller in its `assignableTypes` scope) and the same
acceptance/status DTOs. The trigger itself is the only new route.

```text
POST /api/sync/global                         GET /api/sync/tasks/{taskId}
   |                                             |   (the same route as Â§5.7 â€” shared, not duplicated)
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

Everything Â§5.7 records about acceptance-is-not-completion, the enforced empty body, the four states, the
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
- **No step-level progress, for the same reason as Â§5.7.** A `FAILED` global task does not say whether the
  failure was in tradeable items, recipes or the rebuild, and its message says completed steps were not
  rolled back â€” which for this sequence can mean refreshed item/recipe rows with a graph cache still
  reflecting the previous data.
- **The parameterless-body rule is shared, not copied.** `web.SyncRequestValidation` holds it for both
  triggers and names the offending route in the message, so the two cannot drift into answering the same
  malformed request differently.

### 5.9 Trading Post price-refresh HTTP flow (`STORY-API-005`)

The third asynchronous route, and the first that takes a request input. It sits over the same two
`application.TradingPostPriceRefreshService` methods the two crafting views' "Refresh Trade Post Prices"
buttons call (Â§5.4, Â§6). JavaFX is untouched and keeps calling both in process and synchronously.

It reuses Â§5.7's boundary exactly as Â§5.8 does: the same unchanged `web.task.BackgroundTaskService`, the
same `GET /api/sync/tasks/{taskId}` status route, the same `SyncTaskStatusMapper`, the same
`SyncApiExceptionHandler` (which gained only this controller in its `assignableTypes` scope) and the same
acceptance/status DTOs. The trigger is the only new route.

```text
POST /api/prices/refresh                      GET /api/sync/tasks/{taskId}
  {"variant": "PROFIT" | "DISCOVERY"}            |   (the same route as Â§5.7 â€” shared, not duplicated)
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

Everything Â§5.7 records about acceptance-is-not-completion, the four states, the always-200 known
identifier, the 404, the failure wording and the error-disclosure convention holds here verbatim, with
`operation` reading the requested variant's key. What is specific to this flow:

- **The variant is a required request input, and there are exactly two.** `web.PriceRefreshVariant` maps
  `PROFIT` â†’ `refreshForProfit()` and `DISCOVERY` â†’ `refreshForDiscovery()`, and nothing else. A missing,
  blank, differently-cased, non-string or unrecognised value â€” including a combined "all prices" value â€”
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
  `INSERT â€¦ ON CONFLICT (item_id) DO UPDATE` and there is no `DELETE` on the path, so overlapping runs
  can only overwrite a row with another freshly fetched quote for that same `item_id` â€” they cannot lose
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
  earlier batches stay committed â€” which is exactly what the shared "steps that had already completed
  were not rolled back" wording says, and no more. How many items were refreshed before the failure is
  not something the service exposes, and manufacturing a count at the boundary would mean duplicating its
  orchestration.
- **Item selection stays out of the route.** The controller submits one method reference and nothing
  else; relevance collection (`sync.tp.relevance.*`), batching, quote fetching and persistence are not
  reachable from `web.*`, and no calculation runs on this path.

### 5.10 Crafting selector-options HTTP flow (`STORY-API-006`)

The first read-only route that is not a calculation: the options a browser needs to build Crafting
Profit's single Discipline scope selector (`DOMAIN_SPEC.md` Â§2.2.1) and post a scope back to Â§5.5.
It sits over the same `application.CharacterSelectionService.getCraftingCharacterOptions()` the two
JavaFX selectors call (Â§5.1, Â§5.2, Â§6); JavaFX is untouched and keeps calling it in process.

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
  no validation step â€” there is nothing a caller can get wrong.
- **Response fields.** `defaultScopeKind` (`"ALL"`, read from `CraftingProfitApiMapper`'s own default
  so the two routes cannot disagree about which entry is preselected); `disciplines`, the nine generic
  discipline entries in the order the JavaFX selector lists them; `characterOptionCount`; and
  `characterOptions`, one `{characterName, discipline, rating, active}` record per synced character
  discipline. The first three character fields are exactly what a `CHARACTER_DISCIPLINE` scope needs
  in Â§5.5's request body; `active` is reported because it is a service-provided fact the caller cannot
  re-derive, and no rule is applied to it here.
- **Service order and values are preserved verbatim.** The controller runs one `map` over the returned
  rows â€” no sorting, no grouping, no deduplication, no eligibility test â€” so the character entries
  arrive in the repository's `discipline, rating DESC, name` order, the same order the JavaFX selector
  shows.
- **Empty is a 200, and nothing is substituted.** A database with no synced characters returns
  `characterOptionCount: 0` with an empty `characterOptions` list; `defaultScopeKind` and the nine
  `disciplines` are unaffected, which matches the JavaFX selector still offering All plus the generic
  entries when nothing is synced. No character is invented as a fallback, and no 404 case exists.
- **The generic discipline list is mirrored at the boundary**, in
  `CraftingSelectorOptionsApiController.GENERIC_DISCIPLINES`, for the same reason Â§5.5's defaults are:
  it is the JavaFX view's established presentation list, not a fact any application service reports,
  and both entry points must offer the caller the same choices. Selecting one still means a
  `DISCIPLINE` scope covering all synced characters having that discipline; the route narrows nothing.
- **Status mapping** is its own advice, `web.CraftingSelectorOptionsApiExceptionHandler`, scoped to
  this controller: 503 `DATA_STORE_UNAVAILABLE` for a `SQLException` â€” the same code and message as
  Â§5.5/Â§5.6, since it means the same thing â€” and 500 `SELECTOR_OPTIONS_FAILED` otherwise. A separate
  advice for the reason `SyncApiExceptionHandler` already records: `ApiExceptionHandler`'s 500
  `CALCULATION_FAILED` describes a crafting calculation, which is the wrong thing to tell a caller
  about a selector read, and the calculation routes' contract is not reworded to make room for this
  one. Both messages are fixed; failure detail is logged server-side only.
- **One shared service instance.** Unlike the calculation routes, `CharacterSelectionService` keeps no
  per-call state (it holds only `repo.CharacterRepository`), so there is no reload result for
  concurrent requests to observe and no per-request factory is needed.

---

## 5.11 Browser frontend (`STORY-WEB-001`, `STORY-WEB-002`, `STORY-WEB-003`, `STORY-WEB-004`, `STORY-WEB-005`, `STORY-WEB-006`, `STORY-WEB-007`, `STORY-WEB-012`, `STORY-WEB-013`)

The browser client of the HTTP API. It lives in `frontend/`, entirely outside the Maven
module â€” no Java source, build step or package depends on it, and removing the directory would
leave the backend and JavaFX untouched.

**Technology as built.** Vue 3 single-file components with the Composition API and
`<script setup lang="ts">`, TypeScript in `strict` mode (plus `noUncheckedIndexedAccess`,
`noUnusedLocals`, `noUnusedParameters`, `verbatimModuleSyntax`), built by Vite. Dependency versions
are locked in `frontend/package-lock.json`. `vue` is the only runtime dependency; everything else is
a build- or test-time tool. `vite build` emits static assets only â€” there is no server-side
JavaScript application runtime, matching `TARGET_ARCHITECTURE.md` Â§4.1.

**Trading Post quote disclosure.** The GW2 prices API's unit quotes do not supply enough order-book
depth to price an entire requested quantity; displayed costs and proceeds can extrapolate one quote
across multiple units. `items/TradingPostPriceDisclaimer.vue` is the reusable frontend pattern for
explaining that limit and advising users to verify current prices and available quantities in-game
before a large transaction. Its compact trigger opens a native modal dialog. Crafting Profit and
Crafting Discovery place it beside their calculated results when a result is present. It adds no
market-depth request or calculation. Ecto Salvage includes the same warning beside its calculation
controls.

**File layout.**

```text
frontend/
â”œâ”€â”€ index.html                        page shell, and the document's tab-icon link
â”œâ”€â”€ public/
â”‚   â””â”€â”€ favicon.ico                   the replaceable tab icon, copied verbatim into dist/
â”œâ”€â”€ scripts/
â”‚   â”œâ”€â”€ resolveBrowserPath.mjs        locates an installed Chrome/Edge for the checks below
â”‚   â”œâ”€â”€ stubOrigin.mjs                serves dist/ and answers /api/ for the controlled-origin checks
â”‚   â”œâ”€â”€ browser-smoke.mjs             real-browser check against a running backend, detail included
â”‚   â”œâ”€â”€ sync-browser-smoke.mjs        real-browser check against a stub origin in its own process
â”‚   â”œâ”€â”€ layout-browser-smoke.mjs      real-browser navigation/layout/zoom/focus/contrast check
â”‚   â”œâ”€â”€ profit-browser-smoke.mjs      real-browser check of the Profit split, tree, selection and reflow
â”‚   â”œâ”€â”€ discovery-browser-smoke.mjs   real-browser check of the Discovery split, keyboard selection and
â”‚   â”‚                                 sorting, grouped controls and fresh detail, against a stub origin
â”‚   â”œâ”€â”€ discovery-live-smoke.mjs      read-only Discovery comparison: listed rows and one selected
â”‚   â”‚                                 tree against the responses a real backend returned
â”‚   â”œâ”€â”€ ecto-browser-smoke.mjs        real-browser check of the Ectoplasm page against a stub origin:
â”‚   â”‚                                 local calculation, price-only refresh, warning and narrow layout
â”‚   â”œâ”€â”€ ecto-live-smoke.mjs           legacy check of the removed calculation route; pending replacement
â”‚   â”œâ”€â”€ account-browser-smoke.mjs     real-browser check of Bank/Materials against a running backend
â”‚   â”œâ”€â”€ icon-browser-smoke.mjs        real-browser item images against a controlled origin: 503/404/
â”‚   â”‚                                 undecodable/slow images, fallback, layout, empty slots
â”‚   â”œâ”€â”€ favicon-browser-smoke.mjs     real-browser check that the built page requests the tab icon
â”‚   â”‚                                 and receives public/favicon.ico byte for byte
â”‚   â”œâ”€â”€ recordingProxy.mjs            records what the backend really answered, for the live icon runs
â”‚   â”œâ”€â”€ upstream-proxy.mjs            counts (or refuses) the backend's CONNECT tunnels to ArenaNet
â”‚   â”œâ”€â”€ icon-live-check.mjs           real backend/database/upstream: delivery, browser cache,
â”‚   â”‚                                 revalidation, restart reuse, upstream unavailable
â”‚   â””â”€â”€ profit-page-perf.mjs          Â§33 navigation-to-complete-page measurement, real database,
â”‚                                     three browser/application cache combinations
â””â”€â”€ src/
    â”œâ”€â”€ main.ts, App.vue, styles.css  mount point; the shell; the shared tokens and treatments
    â”œâ”€â”€ shell/
    â”‚   â”œâ”€â”€ destinations.ts           the six implemented destinations and their URLs/titles
    â”‚   â”œâ”€â”€ useHashRoute.ts           the open destination, kept in the location hash
    â”‚   â”œâ”€â”€ SiteHeader.vue            wordmark, destination links, compact synchronization activity
    â”‚   â””â”€â”€ PageHeader.vue            every page's h1, intro sentence and grouped page actions
    â”œâ”€â”€ api/
    â”‚   â”œâ”€â”€ types.ts                  TypeScript shapes of the Â§5.5/Â§5.7â€“Â§5.10/Â§5.12/Â§5.13 and the obsolete Ecto contract
    â”‚   â”‚                             transport records
    â”‚   â”œâ”€â”€ http.ts                   fetch wrapper; maps the uniform error body to ApiRequestError
    â”‚   â”œâ”€â”€ craftingApi.ts            the five crafting routes the two screens call, behind an interface
    â”‚   â”œâ”€â”€ syncApi.ts                the three triggers and the shared status route
    â”‚   â”œâ”€â”€ accountApi.ts             the two Â§5.12 account reads, behind an interface
    â”‚   â””â”€â”€ ectoApi.ts                unused legacy client for the removed calculation route
    â”œâ”€â”€ account/
    â”‚   â”œâ”€â”€ BankScreen.vue            the bank as Â§5.12 supplies it, empty slots kept in place
    â”‚   â”œâ”€â”€ MaterialsScreen.vue       the backend's categories, labels and stack order, unchanged
    â”‚   â”œâ”€â”€ InventoryItem.vue         one entry: supplied item id, count, rarity and its shared icon
    â”‚   â””â”€â”€ useAccountRead.ts         one account read's phase, data, failure and explicit reload
    â”œâ”€â”€ items/
    â”‚   â””â”€â”€ ItemIcon.vue              the one item image: supplied URL verbatim, reserved box, no
    â”‚                                 referrer, lazy/eager, one bundled fallback, no retry
    â”œâ”€â”€ crafting/
    â”‚   â”œâ”€â”€ CraftingProfitScreen.vue  the screen: controls, states, comparison and detail regions
    â”‚   â”œâ”€â”€ ScopeSelector.vue         the sole Discipline selector (DOMAIN_SPEC.md Â§2.2.1)
    â”‚   â”œâ”€â”€ ProfitSettingsForm.vue    the settings the Profit contract supports
    â”‚   â”œâ”€â”€ CraftingProfitTable.vue   the seven comparison columns and the row-selection control
    â”‚   â”œâ”€â”€ ResultDisplayControls.vue the Displayed results subgroup: search slot, three filters, maximum
    â”‚   â”œâ”€â”€ SelectedResultDetail.vue  the selected row's supplied summary, quote and materials
    â”‚   â”œâ”€â”€ CraftingResolution.vue    the Â§5.13 answer: its five situations, concise basis and tree
    â”‚   â”œâ”€â”€ ResolutionTreeNode.vue    one requirement's compact summary and its collapsed children
    â”‚   â”œâ”€â”€ useResolutionDetail.ts    Â§13.4's association rules, shared by both features
    â”‚   â”œâ”€â”€ useProfitResolution.ts    Profit's detail request body and its own identity check
    â”‚   â”œâ”€â”€ resolutionPresentation.ts method/state/blocked-reason codes in user-oriented words
    â”‚   â”œâ”€â”€ useCraftingProfit.ts      scope/settings state and request lifecycle
    â”‚   â”œâ”€â”€ useProfitTableView.ts     search, filters, sort, display limit and identity selection
    â”‚   â”œâ”€â”€ scopeOptions.ts           builds selector entries from the Â§5.10 response
    â”‚   â”œâ”€â”€ CraftingDiscoveryScreen.vue  the Discovery page: grouped controls, states, list, detail
    â”‚   â”œâ”€â”€ DiscoveryScopeSelector.vue   character/discipline scope and the separate inventory input
    â”‚   â”œâ”€â”€ DiscoverySettingsForm.vue    the five settings the Discovery contract accepts
    â”‚   â”œâ”€â”€ DiscoveryTable.vue           the six comparison columns and the row-selection control
    â”‚   â”œâ”€â”€ SelectedDiscoveryDetail.vue  the selected candidate's supplied values, lists and tree
    â”‚   â”œâ”€â”€ useCraftingDiscovery.ts      Discovery scope/inventory/settings state and its lifecycle
    â”‚   â”œâ”€â”€ useDiscoveryTableView.ts     search, sort (no profit filter) and identity selection
    â”‚   â”œâ”€â”€ useDiscoveryResolution.ts    Discovery's detail request body and identity check
    â”‚   â”œâ”€â”€ discoveryScopeOptions.ts     individual character-discipline entries from Â§5.10
    â”‚   â”œâ”€â”€ rowState.ts               shared row-state wording for Discovery
    â”‚   â”œâ”€â”€ recipeLabel.ts            name, or the item id when the backend supplied none; wiki URL
    â”‚   â””â”€â”€ formatCopper.ts           copper â†’ gold/silver/copper text, signed where it may be a loss
    â”œâ”€â”€ ecto/
    â”‚   â”œâ”€â”€ EctoSalvageScreen.vue      local Ecto/Dust calculator and Magic Find presentation
    â”‚   â””â”€â”€ useEctoSalvage.ts          unused legacy request state
    â””â”€â”€ sync/
        â”œâ”€â”€ SyncScreen.vue            the synchronization area: one trigger and task state each
        â”œâ”€â”€ useSyncOperations.ts      per-operation submission and status-polling state
        â”œâ”€â”€ provideSyncOperations.ts  the shell owns one instance; the area injects it
        â”œâ”€â”€ statusPresentation.ts     backend states and situations in user-oriented words
        â””â”€â”€ operations.ts             the four backend operation keys, labels and trigger calls
```

**Runtime path.** The browser calls only the origin it was served from. In development
`vite`'s dev server proxies `/api` to the backend (`GW2_BACKEND_ORIGIN`, default
`http://localhost:8080`), so the backend needs no CORS configuration and the browser never names a
second host. No GW2 API key, database URL or credential of any kind reaches the frontend â€” it holds
none and reads none, and the GW2 API is only ever contacted by the backend (Â§5.4, Â§5.7â€“Â§5.9).

**Crafting Profit data flow.** Opening the screen issues `GET /api/crafting/selector-options` (Â§5.10) and, in
parallel, `POST /api/crafting/profit` (Â§5.5) with an **empty body**, so the defaults applied are the
backend's own rather than a second copy of them. The settings controls are populated from the
`settings` echo of that first response; a scope or settings change posts the full current selection
and replaces the rows. Each request carries a monotonically increasing id and a response whose id is
no longer the newest is discarded, so a slow earlier answer cannot overwrite the current selection's
results. The scope and settings controls stay enabled while a request is in flight â€” a calculation
takes seconds, and disabling them would both strand the user and make that superseding unreachable.
Since `STORY-WEB-004` the page separates the page action (Reload results) from a labelled "Calculation
controls" panel and from an "Opportunities" region holding the summary, the state messages and the
table; a failed calculation offers a retry. Since `STORY-WEB-011` that panel holds two subgroups â€” a
"Calculation" fieldset (scope, settings) and a "Displayed results" fieldset (search, the three
display filters, the maximum and Show all) â€” so what is asked of the backend and what is made of its
answer are separated inside one panel.

**Crafting Profit information hierarchy (`STORY-WEB-005`).** The page is three groups: the controls
panel, the comparison region and the selected-result detail. `.results-split` puts the comparison and
the detail side by side from 64rem upwards (the detail bounded at 20â€“26rem) and stacks the detail
below the comparison under that width; the table keeps its own `.table-region` scrolling either way.

- **Seven comparison columns, not fourteen.** Recipe (the row header), Disciplines, Craftable, Own
  materials *cost, per craft*, Profit *per craft*, Total sell value *all crafts* and Total profit
  *all crafts* â€” `DOMAIN_SPEC.md` Â§2.1.1's required comparison content since `STORY-WEB-008`, which
  also removed the general State column (below). Each money column states its basis in its own
  header, because the contract mixes the two: `revenueCopper`, `matsSellValueCopper` and
  `profitCopper` are per single craft while `buyCostCopper`, `totalSellValueCopper` and
  `totalProfitCopper` are totals for every craft counted (`web.dto.CraftingRowDto`). The own-materials
  column keeps its supplied per-craft basis rather than being scaled into a total it was never stated
  as, and the two totals are set in a heavier weight â€” never distinguished by color. Sorting is
  offered on exactly these seven keys. Nothing is dropped from the client â€” `outputCount`,
  `minRating`, `revenueCopper`, the buy cost, the output quote and both material lists moved into the
  detail region.
- **Selection is the recipe, never the row number.** `useProfitTableView` holds `selectedRecipeId`
  and derives the detail from the *current* rows, so sorting and searching keep a valid selection,
  a replacement calculation shows that recipe's new values, and a recipe the newest result set no
  longer contains is deselected instead of lying dormant. A superseded response never reaches `rows`,
  so it can never reach the detail either. The control is an ordinary `<button>` carrying the recipe
  name, so Tab and Enter/Space operate it; the selected row is marked by `aria-current="true"`, a
  raised surface, a highlight border, a marker glyph and a visually hidden "Selected" â€” never color
  alone, and visibly distinct from the shared focus outline.
- **The detail region renders the response and nothing else.** Labelled sub-regions for the table
  calculation's summary (per-craft group, totals group, the output item's raw trading-post quote).
  Since `STORY-DOM-023` the Profit and Total profit labels in that summary carry a small
  "after 15% TP fees" note (`DOMAIN_SPEC.md` Â§2.1.1, resolved `UD-011`), and nothing else does:
  Output revenue, Total sell value and the Instant buy / Instant sell quote are the backend's gross
  figures. The note is wording only â€” no client-side fee arithmetic exists, here or in Discovery's
  matching detail. There are also sub-regions
  for the crafting resolution (below, `STORY-WEB-007`) and for the purchases still to be made. Since
  `STORY-WEB-015` that last region is **one** list on **one** basis â€” `missingToBuy`, the crafts the
  calculation already counted (`DOMAIN_SPEC.md` Â§2.1.1). `missingToBuyOne` remains in the row
  contract and is no longer displayed; the resolution's single-batch tree is a different calculation
  and is never substituted for the counted-craft list. Each material line is its supplied quantity
  and its own quote; no shopping total is produced and no procurement quantity is derived. An
  unsupplied list, an empty list and "nothing to buy" stay three distinct messages. A GW2 Wiki link is
  offered only when the backend supplied a name to build an article title from, percent-encoded into
  the path; with no name the link is omitted and said to be omitted, because an item id is not a wiki
  address.
- **The page carries no introductory sentence** (`STORY-WEB-015`, `DOMAIN_SPEC.md` Â§2.1.1).
  `PageHeader`'s `intro` is optional and Crafting Profit passes none; every other page keeps its own.
 - **Profit row-state semantics are technical detail, not normal-view copy** (`DOMAIN_SPEC.md`
  Â§Â§2.1.1 and 42). The comparison table and Selected Result panel show no generic row-state labels,
  explanations or diagnostic badges. The selected row's raw `blockedReason` is shown in the closed
  "Technical details" disclosure alongside `resultAvailable` and identifiers. `rowState.ts` remains
  shared with Discovery and continues to provide its presentation mapping there. The Profit
  `RECIPE_NOT_ALLOWED` display filter still reads the backend code and availability flag; removing
  the badge does not remove or alter the filter or any row data. Missing numeric values remain `â€”`.
- **Money that may go either way carries its sign.** `formatSignedCopper` writes `+`/`-` and
  `moneyTone` adds the shared `.money--gain`/`.money--loss` treatment, so the distinction survives
  without color; a supplied `0c` and an unsupplied `â€”` both stay neutral and stay distinct.
- **The settings are one collapsed group with their effect on show.** The remaining controls sit behind a
  "Price and material settings" disclosure whose summary line words the settings the backend echoed;
  no default is repeated in the browser. Scope and Reload results stay in the open.

**Result-display controls and whole-row selection (`STORY-WEB-006`, regrouped by `STORY-WEB-011`).**
`DOMAIN_SPEC.md` Â§2.1.1's display controls are a "Displayed results" fieldset
(`ResultDisplayControls.vue`) inside the calculation-controls panel, beside the "Calculation"
fieldset â€” the separation is the two legends and boundaries, not a paragraph saying so. They are pure
view state in `useProfitTableView`, so changing one cannot issue a request, cannot alter what was
asked for, and cannot alter Â§28's per-recipe simulation cap; `api.calculateProfit` still runs only
for an opened screen, a changed scope, changed settings and an explicit reload. The whole result set
stays loaded â€” a hidden row is hidden, never dropped. The search sits in this group too: it narrows
the listed rows exactly as the filters do, and it reaches the backend no more than they do.

- **Three filters, each reading one supplied field.** Craftable count exactly `0`, a
  `blockedReason` of `RECIPE_NOT_ALLOWED` on a row the backend actually calculated, and a
  `profitCopper` of `0` or less. All three are on initially and each reverses on its own. A value the
  backend did not supply is its own answer and is never folded into any of the three: a null count is
  not `0`, a null profit is not "0 or less", and `resultAvailable: false` is a technical failure, not
  a recipe reported as not allowed â€” the not-allowed test reads `resultAvailable` as well as the code.
- **Search and filters run before the sort, and the sort before the cut-off.** `matchingRows` is the
  searched, filtered and ordered set; `visibleRows` is that set cut off at the maximum. So Show all
  reveals the rest of *that* matching set rather than an unrelated unfiltered one.
- **The maximum is changeable and starts at 250.** It is applied on the committed entry rather than
  per keystroke; an entry that is not a whole number of at least 1 is refused â€” the control returns
  to the maximum in effect and says so, instead of blanking the list or silently showing everything.
  "Show all" removes the limit and disables the maximum while it is on, rather than explaining in a
  paragraph that the typed maximum is not being applied; switching it off puts that maximum back.
- **Three counts, kept apart.** The compact summary reads "Showing *displayed* of *matching* matching
  recipes Â· *calculated* calculated for this scope", which is also the only thing that reports a
  cut-off list â€” `STORY-WEB-011` removed the paragraph that used to explain the limit. A list
  emptied by the controls states that the calculation's rows are all still held and names every
  restriction currently applied, with the controls still on screen to undo.
- **No permanent instructions.** The row-selection/keyboard sentence, the paragraph explaining the
  filters and the dynamic limit paragraph are gone (`DOMAIN_SPEC.md` Â§2.1.1). What they carried for
  assistive technology stays: the comparison table's visually hidden `<caption>` still describes the
  per-craft/total bases and how a row is opened, and the column headings still carry the basis
  visually.
- **The whole row selects, the button is still the control.** A click on any part of a row that is
  not itself interactive selects that row's recipe; a click that started inside a nested `a`,
  `button`, `input`, `select`, `textarea`, `label`, `summary` or `role="button"`/`role="link"` is
  left to that control, so the recipe button selects once and not twice and a link added later keeps
  following its own href. The `<tr>` is deliberately not a control â€” the button remains the single
  tab stop, the accessible name and the keyboard route (Enter/Space), and the row click is an extra
  pointer affordance rather than a second, nameless widget. Selection is still by recipe identity.
- **A selected recipe the display controls hide keeps its identity and its detail.** The detail
  region stays on screen and says which control is holding the row back â€” the maximum
  (`selectionHiddenReason === 'limited'`) or the search/filters (`'filtered'`) â€” rather than being
  cleared or silently paired with a different row. Only a result set that no longer contains the
  recipe clears the selection, exactly as `STORY-WEB-005` established.

**Resolution detail in the browser (`STORY-WEB-007`).** Selecting a recipe asks `POST
/api/crafting/profit/resolution` (Â§5.13) for that one recipe and renders the returned row and tree in
the detail panel's "Crafting resolution" sub-region. The request is **lazy** â€” one per selection,
never one per table row â€” and completes as a plain HTTP 200, which is the execution policy
`STORY-API-008` measured and fixed; there is no task, no status resource and nothing polled here.

- **The table's own effective inputs are what is sent.** `useProfitResolution.request` posts
  `recipeId` plus `calculation` built from the scope and settings the *table response echoed*, so the
  detail is calculated with the inputs the list on screen was calculated with rather than asking for
  the backend's defaults a second time. An effective scope member the backend reported as absent is
  omitted rather than sent as a null, which is how the table request contract carries "not supplied".
  No row number, price, material map or context identifier is part of the body.
- **Association is feature, recipe, inputs and a local generation** (Â§13.4). Every invalidating event
  increments a generation counter and clears the panel: a changed selection, changed calculation
  inputs, a started table reload, and leaving the view while the page is kept alive (`onDeactivated`
  invalidates, `onActivated` asks again, freshly). An answer for a superseded generation is dropped,
  which is what makes A â†’ B â†’ A safe: A's first answer belongs to a dead generation and cannot revive
  it. An accepted answer must additionally echo back the recipe id and the same effective inputs; one
  that does not is reported as a mismatch and leaves no tree. The identity key is built from the
  echoed scope and settings **only**, so sorting, searching, the display filters and the display
  maximum do not invalidate a detail â€” re-ordering the table is not a new calculation.
- **Fresh detail explains the selected result's full quantity.** The Profit service uses the fresh
  detail row's `craftableCount` as the accepted execution limit and runs the normal resolver for each
  accepted craft. Each craft consumes the same inventory/settings/eligibility/price inputs captured
  for that detail request; recursive nodes and their inclusive costs are aggregated from those actual
  traces. The root's requested output quantity is accepted crafts multiplied by recipe `outputCount`.
  A zero-count row retains one first blocked attempt and reports `FIRST_BLOCKED_ATTEMPT`, so it does
  not imply counted output. The fresh response remains separate from the earlier table response and
  its association/generation checks are unchanged; the comparison table is never replaced with the
  detail row. Profit reports `SELECTED_RESULT_OUTPUT_QUANTITY`; Discovery retains
  `SINGLE_OUTPUT_REQUIREMENT`.
- **Requested identity and actual sourcing stay apart** (AR-003). The envelope's `recipeId` is the
  recipe that was *asked about*; each node's `recipeId` is the recipe actually selected or attempted.
  A sentence names which of the three the root is: the requested recipe, another producing recipe, or
  no recipe at all (an inventory-only root). Nothing relabels a root as an execution of the requested
  recipe, and no ingredient path is invented for a root that has none.
- **A compact per-node summary, printed and not computed** (`STORY-WEB-016`). Each node shows its own
  item identity, the supplied required quantity ("20 needed"), the sourcing labels for **every**
  supplied method â€” a mixed requirement is not forced into one â€” the named character under
  "Crafted by" where the backend supplied one. Profit shows one `Value` from the node's inclusive
  `effectiveCostCopper`; it has no per-node Technical details disclosure. Discovery continues to show
  its three supplied cost values.
  Every supplied state and blocked reason keeps its own marker and sentence, so nothing unresolved is
  dropped for brevity. The resolver's bookkeeping is **not** in the normal view: the
  inventory/crafted/bought/missing split, the producing recipe and its id, the craft count and the
  produced batch total are all still in `ResolutionNodeDto` and simply not rendered
  (`DOMAIN_SPEC.md` Â§2.1.1). A node the backend assigned no character to prints no crafter line at
  all rather than a "Not assigned" row. The three costs already include everything below the node, so
  no descendant cost is added into a parent; a null cost renders `â€”`, a domain-established zero
  renders `0c`, and the two never merge â€” the sentence saying the marker is not zero is stated once
  under the tree instead of under every node. Two occurrences of one item in different branches stay
  two nodes â€” the presentation key is the child-index path, not a node id.
- **Children sit in a collapsed `<details>` group at every level, the root's included.** A native
  `<summary>` is the disclosure control, so it is in the tab order and Enter/Space operate it; each
  group opens on its own and opens nothing below it, which is what keeps a deep tree a short list
  until the next level is asked for. Collapsing withholds nothing: every returned requirement is
  rendered, in order, with no depth cap, no merged occurrence and no "show more". Because `open` is
  the element's own state rather than this application's, `CraftingResolution` keys the tree on a
  counter it bumps for each accepted answer, so a replacement resolution remounts and starts
  collapsed rather than inheriting the previous selection's expansions.
- **Five situations, kept apart** (`ResolutionPhase`). Loading; an answer with a tree, whose nodes may
  still report blocked requirements; an answer reporting `RESULT_UNAVAILABLE`, worded as the
  calculation's own answer with no tree; a 404 `RECIPE_NOT_IN_CALCULATION`, worded as the fresh
  calculation no longer offering this recipe and explicitly *not* as the recipe being gone from the
  database; and a request that failed, worded as a request that did not work rather than as something
  established about the recipe. The last three clear the tree, so no old tree is ever left under a new
  selection, and the backend's own code and message stay visible as secondary evidence.
- **Codes read as words, with the raw code kept visible** (`resolutionPresentation.ts`). The three
  `craft.AcquisitionMethod` names, the four `craft.ResolutionState` names and all eight
  `craft.BlockedReason` names of `DOMAIN_SPEC.md` Â§42 have their own wording, reusing `rowState.ts`'s
  with the one adjustment a node needs: a row's reason is about *further* crafting, while a node's
  reason is about *this requirement*. `BUYING_DISABLED` therefore reads as a requirement that would
  have to be bought while buying is off, never as invalidation of crafts already counted.
  `PRICE_UNAVAILABLE` is stated as an unknown price that is not zero, and since `STORY-WEB-015` it
  **names the item** it is about â€” that node's own backend name, or its item id when no name was
  supplied (`DOMAIN_SPEC.md` Â§2.1.1). The item comes from `nodeLabel` for the node the code arrived
  on, never from an ancestor and never from a row's own reason, which carries no item at all.
  `UNVALUED_NONTRADEABLE` reads as a
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

**System Status area (`SyncScreen.vue`, `STORY-WEB-004`).** This is the former synchronization destination at `#/synchronization`, now titled **System Status**. Three compact health cards summarize account data, global game data, and the shared Trading Post cache. Text and color identify OK, Problem, Running, and not-run states; timestamps distinguish last check from last change. The only manual actions are account refresh and global-data check. Price refresh stays on demand in Crafting Profit and Discovery through the shared backend cache pipeline; this page offers no Profit-vs-Discovery price buttons.

Task states for those two manual actions are polled through the existing task-status API at three-second intervals, bounded to 600 lookups and without overlapping requests. Failed tasks, rejected submissions, failed status lookups, and unknown outcomes remain distinct. A retry repeats only a status lookup when the backend can resolve the task. Task identity and lifecycle timestamps are available in collapsed task details. Tracking remains shell-scoped and process-local: navigation does not retrigger work, while browser reload or backend restart loses the client's task history.

**Application shell and navigation (`STORY-WEB-004`).** `App.vue` is a site header plus the one open
application area. The header carries the wordmark and one link per implemented destination â€”
Crafting Profit, System Status, Bank, Materials (`shell/destinations.ts`) â€” and nothing unfinished
is listed, so no link leads nowhere. Each page opens with a `PageHeader` and its `h1`; pages that
need an introduction or page actions place them there.

- **Destinations are addressable, with no router library.** `shell/useHashRoute.ts` keeps the open
  destination in the location hash (`#/crafting`, `#/synchronization`, `#/bank`, `#/materials`), so
  every area has a real URL, Back/Forward move between the areas that were visited, a bookmark opens
  the named area and the document title names it (`<area> Â· GW2 Crafting Tool`). The links are real
  `<a href>` elements: an ordinary left click is handled in the page, and a modified click is left to
  the browser. An empty or unrecognized hash resolves to Crafting Profit and is corrected in place
  with `replaceState`, so the address bar never claims an area that does not exist. Only the
  destination is in the URL â€” no scope, settings, selection or task identifier â€” so a reload opens
  the named area with nothing else restored.
- **Navigating submits nothing.** No navigation issues a calculation or a synchronization request.
  Opening Bank or Materials issues that screen's own read (see below); opening Crafting Profit or
  System Status reads its diagnostic endpoint and resumes polling any tracked tasks without
  submitting a new operation.
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

**Shared presentation (`STORY-WEB-004`).** `src/styles.css` holds one set of semantic tokens â€” the
`FRONTEND_UX_GUIDELINES.md` Â§9 baseline palette, a 4/8/12/16/24/32/48 spacing scale, one body face,
a small type scale, two corner radii â€” and the reusable treatments built on them: `.page` (bounded at
1440px and centered), `.screen`, `.panel`, `.stack`/`.cluster`, `.prose` (bounded at 68ch), `.meta`
for secondary text, `.notice` with error/warning/info tones, `.status` pills with idle/busy/success/
failure/caution/unknown tones, `.diagnostics` for labelled secondary technical detail, `.table-region`
for a table that genuinely needs two dimensions, and default/hover/focus/active/disabled treatments
for buttons and inputs. Every screen uses these; no page defines a color of its own. Decorative
transitions are removed under `prefers-reduced-motion`. A disabled control keeps its size and its
readability and states its reason next to itself rather than fading out.

A wide table scrolls inside `.table-region` only â€” a focusable, named region â€” so the page around it
reflows and never gains a horizontal scrollbar of its own. That region is the horizontal scroll
container, which is why the result table's header row is no longer `position: sticky`: keeping the
page's own vertical scrolling was the deliberate trade.

**Bank and Materials flow (`STORY-WEB-003`, restyled by `STORY-WEB-004`).** Each screen owns one read
of one Â§5.12 route â€” `GET /api/account/bank`, `GET /api/account/materials` â€” issued on mount and
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
  owned count of `0`, and a missing count renders `â€”`. A successful `slotCount: 0` response is a
  distinct message from a bank whose slots are empty. The Materials screen renders the supplied
  categories, their labels â€” the `"Category <id>"` fallback included â€” and each category's stack
  order unchanged, and shows each stack's own `category` id, since that is the only way to see which
  id produced a fallback label. Nothing is regrouped, deduplicated, sorted, aggregated or filtered
  here: no category map and no inclusion rule exists in the browser (Â§5.12).
- **Items are identified by id, and their image comes from this application.** The two routes supply
  no item name, so `InventoryItem.vue` shows `#<itemId>` and invents nothing; no item-detail lookup
  exists and the GW2 API is never contacted. The supplied `iconUrl` (`STORY-API-009`, Â§5.14) is
  rendered by the shared `items/ItemIcon.vue` (see "One image component" below), lazily, because both
  screens are long grids. An empty bank slot gets no icon at all. Rarity is displayed when supplied
  and omitted when not, and none of the text depends on whether the picture arrived.
- **Four states, kept apart, per screen.** `useAccountRead.ts` holds `loading`, `loaded` and
  `failed`, with a successful empty result rendered as its own message inside `loaded`. A failure
  shows the backend's sanitized code and message (503 `DATA_STORE_UNAVAILABLE`, 500
  `ACCOUNT_READ_FAILED`, Â§5.12) or this client's own name for a transport failure, and offers an
  explicit retry that repeats only that read. Starting a read clears the previous answer, so a failed
  reload can neither masquerade as an empty inventory nor leave stale data on screen as if newly
  loaded.
- **A superseded answer is discarded.** Each read carries a monotonically increasing id and the
  composable drops any answer â€” success or failure â€” whose id is no longer the newest or whose screen
  has been unmounted, so a late bank response cannot reach the Materials screen and a slow first read
  cannot overwrite a reload's result.

**One image component (`STORY-WEB-010`, `TARGET_ARCHITECTURE.md` Â§12.1).** `items/ItemIcon.vue` is the
only place `frontend/` renders an item image, and every item surface uses it: the Crafting Profit rows
(inside the selection button, so the control's accessible name stays the recipe), the selected
recipe's detail heading, both "still to buy" material lists, every resolution-tree node, and the Bank
and Materials entries through `InventoryItem.vue`.

- **The URL is the backend's, used verbatim.** The component takes `iconUrl` and the *item's* id â€”
  a row passes its recipe's `outputItemId`, a node its own `itemId` â€” and builds no address of its
  own: not from a recipe id, not from an item id, and never from an upstream one. There is no cache
  busting, so an item keeps one URL across openings and both browser caching and the backend's
  revalidation can work.
- **Delivery attributes.** An ordinary `<img>` with reserved `width`/`height` (20 px in lists, 32 px
  on the detail heading) inside a box the wrapper reserves as well, `referrerpolicy="no-referrer"`,
  `decoding="async"`, `loading="eager"` for the detail's single visible image and `loading="lazy"` for
  list entries. No CSP is configured anywhere in this repository today, so there is no ArenaNet
  image-origin exception to remove; the browser makes no ArenaNet request to need one.
- **One bundled fallback, no retries.** A null `iconUrl` (absent *or* backend-rejected metadata) and a
  load/decode failure both end in the same inline neutral SVG â€” inline so the fallback for a failed
  request cannot itself be a request that fails. A failure sets a flag and stops; the `src` is never
  reassigned. The flag resets when the item id or the URL changes, so a new item, or the same item
  whose retained source changed, gets its own attempt. The image carries `alt=""`/`aria-hidden`, since
  every consumer already names the item beside it, and it is not focusable, so row and detail keyboard
  interaction is untouched.
- **Nothing is inferred from an image.** Its presence, absence or failure says nothing about
  craftability, ownership, price or any other domain state, and no code reads one out of it.

**Measured browser behaviour (`STORY-WEB-010`, real backend, real user database).** `npm run
smoke:icons` drives the built page against a controlled origin for the cases a real backend will not
produce on demand (503, 404, an undecodable body, a 1.2 s image); `npm run check:icons:live` and `npm
run perf:profit-page` use the real backend behind `scripts/recordingProxy.mjs` (backend-side request
records) and `scripts/upstream-proxy.mjs` (a counting HTTPS forward proxy the backend's image fetcher
reaches through the JVM's own proxy selector). What those runs observed, with the figures in
`STORY-WEB-010`'s Result: the browser asked only this application for images and never ArenaNet; the
development server forwards `/api/items/.../icon/...`; real images arrived with `public,
max-age=86400`, a strong ETag and a verified type; a reopened page re-requested none of what the
browser already held; the browser's stored validator produced `If-None-Match` â†’ 304 from the backend's
local copy with no upstream tunnel; a **restarted** backend process served its stored images with zero
upstream tunnels, as did a backend whose upstream proxy refused every tunnel. Navigation to a
complete, interactive Crafting Profit page â€” detected by quiescence, with every in-viewport image
finished and the complete requested row set in the DOM â€” stayed inside `TARGET_ARCHITECTURE.md` Â§33's
7 s across cold browser/cold application cache, cold browser/warm application cache and warm
browser/warm application cache.

**Evidence limits.** Offscreen list icons are deferred by native lazy loading and keep loading after
the page is complete; the measurements report how many were unfinished and how many requests were
still in flight at that moment rather than waiting them out, and a complete page never depends on
them. A CONNECT count is tunnels, not requests: zero for a freshly started backend proves no upstream
fetch, a positive number only proves at least one. The 304 path was exercised through the browser's
own "validate with the server" mode, not by waiting out the one-day freshness window. Timings come
from this one machine, this revision and the default All scope, and no Product Owner acceptance is
claimed by them; `TARGET_ARCHITECTURE.md` Â§33's confirmation gate is unchanged. The favicon, the
Discovery and Ectoplasm screens, and Phase 5's bounded health review remain outside this work.

**Crafting Discovery page (`STORY-WEB-012`).** The browser consumer of Â§5.6 and the Discovery half of
Â§5.13, at `#/discovery` â€” a destination of its own with its own document title and page heading, kept
alive across navigation on its own `KeepAlive` boundary so returning to it loses neither the selection
nor posts the calculation again. It reuses the Profit page's structure and every shared presentation
module (`ItemIcon`, `CraftingResolution`, `ResolutionTreeNode`, `rowState`, `resolutionPresentation`,
`recipeLabel`, `formatCopper`) and adds no second mechanism for any of them.

- **Two independent scope controls, both from selector facts.** The scope is one character *and* one
  discipline: `discoveryScopeOptions.ts` builds one entry per synced character discipline from Â§5.10,
  each carrying the rating that response reported, and there is no All entry and no default scope
  because the route has neither. The separate `inventoryCharacterName` input is its own labelled
  control â€” it decides whose owned materials may be consumed, not which recipes are listed, since
  recipe knowledge is account-wide â€” and "No character" is a real choice that omits the field and
  leaves the backend's own unfiltered-pool fallback in place. It opens on the selected character
  discipline's own character; once a value is in effect, neither a scope change nor a reload rewrites
  it, and only a name the selector stops offering falls back to that default. With no character
  discipline to offer, or a failed selector read, the page says so in that situation's own words and
  sends **no** calculation at all. Until the selector has answered the page reports loading, because
  whether a character exists is not yet established.
- **Discovery's own defaults and echo.** The first request carries the scope and inventory character
  only; the five settings controls (`useOwnMats`, `allowBuying`, `maxBuyCopper`, `listingSell`,
  `listingBuy`) are populated from that response's `settings` echo, so Discovery's defaults are the
  backend's and never Profit's. `allowDailyCrafts` is *reported* as true, the value the flow fixes and
- **Six comparison columns, each stating its basis.** Recipe (row header), Level *recipe minimum*,
  Craftable *crafts*, Materials to buy *cost, all crafts*, Output sell value *all crafts* and Profit
  *per craft*. Sorting is offered on those six keys, level sorting works in both directions and opens
  on highest first, and a row whose value the backend did not supply sorts last rather than as zero.
  Search narrows the listed rows over name, discipline, level, ids and the words a state is called.
  Neither sorting nor searching issues any request.
- **No eligibility or economics of its own.** The rating filter, the account-wide recipe-knowledge
  rule and normal-discovery eligibility stay entirely in the backend (`DOMAIN_SPEC.md` Â§34/Â§35). The
  page has **no** profit filter: a zero or negative immediate profit is a legitimate discovery
  candidate (Â§37), so Profit's "hide profit â‰¤ 0" default is deliberately not imported here and no
  candidate the backend returned is removed. Per `DOMAIN_SPEC.md` Â§25 the displayed prices and output
  sell value stay gross; since `STORY-DOM-023` the Profit and Total profit labels carry the same small
  "after 15% TP fees" note Crafting Profit's detail uses, and the region's own paragraph states that
  the domain's 15% selling fee is already in the backend's profit figure and is never applied again on
  this page.
- **Lazy fresh detail over the shared association rules.** Selecting a recipe by id â€” whole row by
  pointer, the row's own `<button>` by keyboard â€” issues one `POST /api/crafting/discovery/resolution`
  carrying the recipe id and the effective scope, **nullable inventory character** and settings the
  *table response echoed*. `useResolutionDetail` holds Â§13.4's generation and identity rules for both
  features; `useDiscoveryResolution` supplies only Discovery's request body and the check that an
  answer echoes back that recipe, those settings **and that inventory character** â€” resolving the same
  recipe against another character's materials is a different calculation, so an answer echoing the
  previous one is refused. Selection, reload, any input change and leaving the page invalidate the
  detail, including A â†’ B â†’ A; sorting and searching do not. The fresh row and tree are shown beside
  the table row without replacing it, with Discovery's one-output-batch basis and the actual root
  sourcing labelled truthfully. Profit uses the complete selected-result quantity as described
  above.

**Ecto Salvage page.** The browser page at `#/ecto` loads `GET /api/items/metadata` for Ecto, Dust
and the five displayed salvage tools, `GET /api/items/prices` for Ecto (19721) and Crystalline Dust
(24277) only, and `GET /api/account/luck` for the configured account. It uses the shared
`ItemIcon` with backend-supplied `iconUrl`; it does not call the GW2 API or the removed
`/api/ecto/salvage` calculation route. Unlike the Crafting screens, this page is not kept alive
across navigation; reopening it loads its inputs again.

- **Local calculator.** `EctoSalvageScreen.vue` keeps GW2 Wiki statistical salvage yields and tool
  usage costs as frontend reference data. Amount, salvage method, exact tool and TP modes recalculate
  locally without another HTTP request. The selected Instant Buy or Buy Order quote values every
  consumed Ecto, including one already owned; the UI explains that owned Ectos are not free. The
  selected Dust sale quote is scaled by expected yield, then its value includes the 15% TP selling
  fee. Coin-priced tool use contributes to effective cost; Black Lion Gems stay separate from coin
  and are never converted into gold.
- **Results and Magic Find.** The structured salvage calculation shows Luck and Dust received, Ecto
  value consumed, tool cost, Dust value after TP fees, effective cost and cost per 1,000 Luck. The
  account read supplies cumulative Luck thresholds; the progress bar uses current and next thresholds
  and fills at the 300% cap. Next, +5%, +10% and cap target costs use the same selected yield, tool
  and TP valuation as the main result. The target table separates Ecto value consumed from effective
  cost and shows Gem costs separately.
- **Market refresh and limits.** The manual "Refresh TP prices" control rereads only the two price
  quotes and updates the local calculation; it neither reloads metadata nor account Luck. The shared
  `TradingPostPriceDisclaimer` opens a warning beside the calculation controls: the GW2 API has no
  order-book depth sufficient to guarantee a unit quote for the whole quantity, so users should
  check current price and available quantity in-game before large transactions. The page labels
  salvage yields as statistical averages rather than guaranteed drops.

**Crafting calculation boundary.** The Crafting Profit and Discovery pages perform no profit, fee,
valuation, crafting, inventory or eligibility calculation. Their economic numbers come from the
backend; the Ecto Salvage page's local calculator is the separate exception described above.
`totalProfitCopper` and `totalSellValueCopper` in particular are displayed and sorted exactly as
received and are never derived from `craftableCount Ã— profitCopper` or `craftableCount Ã—
revenueCopper`. Rows the backend could not calculate are kept, not dropped:
`resultAvailable: false` remains available as raw data in the selected result's collapsed Technical
details disclosure, and a null numeric field renders as `â€”` rather than `0`. Generic row-state
labels and explanations are not rendered in the normal Profit table or selected result.
`formatCopper`/`formatCount`/`formatSignedCopper` are the only transformations, and all three are
presentation-only. The Profit screen's display filters and maximum only decide which of the loaded
rows are listed: no row is dropped from the result set, no unsupplied value is read as zero, and no
blocked or unavailable result is reinterpreted as successful or absent. The same holds for a
resolution tree: no descendant cost is summed into an ancestor (they already include it), no quantity
is derived from the others, no state is inferred from a price, no two occurrences of an item are
merged, and no shopping total is produced from any of it. The TypeScript types in `api/types.ts` describe transport shape; they do not
validate received JSON and do not replace backend validation.

**Not built yet.** Item names in a resolution node are whatever the backend supplied. Missing:
automatic TP refresh, task cancellation, item names on the inventory screens (no contract supplies
them, Â§5.12), and persistence across a browser reload. Neither crafting screen has
virtualization or paging: every row left visible enters the DOM at once â€” Show all over the live
default Profit scope means all 3176 of them, and Discovery has no display limit at all, so a live
character discipline putting 672 candidates on screen puts 672 rows in the DOM. `STORY-WEB-006`'s
maximum is a display control the user owns, not a performance mechanism, and Discovery has no
equivalent. Full-page timings exist for the Profit table only
(see "Measured browser behaviour" above); the **detail a selection loads** is still untimed, and so is
the Discovery page as a whole â€”
`TARGET_ARCHITECTURE.md` Â§33 requires both as part of a complete page. The removed Ectoplasm route
has historical timings (Â§5.15), but no full-page timing exists. Neither result table's header
stays visible while scrolling. The tab icon is a neutral placeholder awaiting the Product Owner's file
(see "Browser tab icon" below); there is no mobile navigation menu
(six short destination links wrap instead), no footer and no artwork or icon set beyond item images.
`docs/ROADMAP.md` Phase 5 owns what remains.

**Browser tab icon (`STORY-WEB-009`).** `frontend/public/favicon.ico` is the whole mechanism. Vite
copies `public/` into `dist/` unprocessed, unhashed and unrenamed, and `index.html` carries one
`<link rel="icon" type="image/x-icon" href="%BASE_URL%favicon.ico">`, so the emitted href follows the
build's configured base path â€” `/favicon.ico` at the default root base, `/gw2/favicon.ico` for a build
with `--base=/gw2/`, exactly like the emitted script and stylesheet URLs. No component, module or API
route participates, and nothing in the application source depends on the file's contents: replacing
that one file replaces the icon. The committed file is a neutral 32Ã—32 grey rounded square placeholder,
not the Product Owner's artwork; `README.md` owns the replacement and cache-refresh instructions.
Chromium loads a tab icon through its own loader, which Playwright does not report as a page request,
so `smoke:favicon` counts what the origin actually served rather than page request events.

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
| `npm run smoke:profit` | real-browser check of the Crafting Profit comparison/detail split, the compact resolution tree and its collapsed keyboard-operated ingredient groups, keyboard and whole-row selection, the sticky panel, the display controls and its reflow; needs `npm run build` only |
| `npm run smoke:account` | real-browser check of Bank and Materials; needs the backend **and** `npm run dev` already running |
| `npm run smoke:discovery` | real-browser check of the Crafting Discovery split at two viewports, keyboard selection and sorting, the grouped controls, the fresh detail and what the page sends; needs `npm run build` only |
| `npm run smoke:discovery:live` | read-only comparison of the rendered Discovery rows and one selected tree against the responses a real backend returned; needs the backend **and** `npm run dev` already running |
| `npm run smoke:ecto` | real-browser check of the current Ecto page: its destination, initial data reads, local calculation, price-only refresh, shared warning and narrow layout; needs `npm run build` only |
| `npm run smoke:ecto:live` | Legacy check for the removed calculation route; pending replacement and currently cannot pass against the backend |
| `npm run smoke:favicon` | real-browser check of the tab icon: the built document's single icon link, and the bytes of `public/favicon.ico` loaded from it; answers every `/api/` call 404, so it evidences nothing about any screen's data; needs `npm run build` only |

The backend is started separately with `./mvnw spring-boot:run` from the repository root (Â§8).
All of these checks drive an installed Chrome/Edge through `playwright-core` (`GW2_BROWSER_PATH`
overrides the executable, `scripts/resolveBrowserPath.mjs` finds it); none downloads a browser of its
own. `smoke:browser` and `smoke:account` use the real backend, which is safe because Crafting Profit
and both inventory screens only read; `smoke:account` additionally re-reads each route itself and
compares the rendered slot order, empty-slot positions, item ids, counts, category labels and stack
order against that response. `smoke:browser` reads the resolution response the browser itself
received and compares the rendered tree against it â€” every node label in the backend's own
depth-first child order, the root's requested quantity, and the four envelope literals â€” so what it
establishes is that the page displayed that response, never that a value is domain-correct. Since the
detail route is only mapped in a backend built after `STORY-API-008`, a long-running older backend
process answers it with Spring's default 404 body; the check then records the situation instead of a
comparison, which is the honest outcome and not a frontend failure.
`smoke:discovery:live` uses the real backend for the same reason â€” both Discovery routes only
calculate over stored data â€” and triggers no synchronization: it re-reads nothing it did not already
receive, compares every listed row and the selected recipe's whole tree against the responses the
browser itself got, and fails rather than synchronizing when the live selector offers no character.
What it establishes is that the page displayed those responses, never that a value is domain-correct.
`smoke:sync`, `smoke:layout`, `smoke:profit` and `smoke:discovery` deliberately do not: through `scripts/stubOrigin.mjs`
they serve `dist/` and answer every route they need from their own process â€” the trigger and status
routes with scripted task lifecycles for the first, the four areas' reads for the second, and for the
third a row set covering gain, loss, blocked, not-allowed, no-result and null-versus-zero plus a
scripted resolution response whose tree carries split sourcing, a repeated item, a known zero, a
missing price and an unrecognized method/state/reason, and for the fourth two character disciplines
and a candidate set covering a gain, a loss, an exact zero, a blocked row with null economics and a
recipe with no result â€” so the pages are exercised in a real browser
without starting a real synchronization or touching user data.
`stubOrigin` binds and the scripts open `127.0.0.1` rather than the name `localhost`, it fails on a
`listen` error, and each run asserts that this server served the page â€” without those, another process
on that port (a dev server bound to `::1` alone leaves the IPv4 port free) would be driven instead,
and its `/api` proxy would reach the real backend. A passing `smoke:sync` therefore evidences browser
interaction and task-state presentation only, never that a backend synchronization ran; `smoke:layout`,
`smoke:profit` and `smoke:discovery` evidence structure, layout, selection, display-control behavior,
tree rendering, focus and contrast, and nothing about real data or page-load performance. In
particular, `smoke:discovery` exercises no eligibility rule: the rating filter, the account-wide
recipe-knowledge rule and normal-discovery eligibility are the backend's, and what that run shows
about them is controlled-response evidence only.

---

### 5.12 Account bank and material-storage HTTP flows (`STORY-API-007`)

Two read-only routes over the two reads the JavaFX `BankView` and `MaterialsView` already use
(Â§4, Â§6). The browser's Bank and Materials screens consume them (`STORY-WEB-003`, Â§5.11); the
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
  their SQL (Â§4), so a GET cannot change stored data.
- **Bank response fields.** `slotCount` and `slots`, one `{slot, itemId, count, iconUrl, rarity}`
  record per row of `BankContentsService.getBankContents()`, in that service's slot order. **Empty
  slots are kept**, with `itemId` and `count` both `null` â€” that is the empty-slot representation,
  and no substitute id or `0` count is invented. Dropping them would shift every following item into
  the wrong cell of the bank grid, which the caller could not undo. `rarity` is `null` when the
  slot's item has no matching `items` row; that is display metadata the service supplied and it is
  reported as-is, never defaulted. `iconUrl` is this application's own image URL for the slot's item,
  derived by the controller from the retained `items.icon_url` the same read already carried
  (`STORY-API-009`, Â§5.14) â€” `null` for an empty slot or unusable metadata. The backend's local
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
  nested `map` over what it was handed â€” no regrouping, sorting, deduplication, filtering or
  inventory aggregation â€” so the category order, the labels, the fallback label and the
  "non-empty stacks only" rule remain `MaterialStorageService`'s, and the Materials page and a browser
  see the same grouping. Neither controller re-derives a single one of them.
- **Empty is a 200, distinguishable from a failure.** No bank rows is `slotCount: 0` with an empty
  `slots` list; empty material storage is `categoryCount: 0` with an empty `categories` list. Neither
  is a 404 and neither substitutes a placeholder slot or category, so an empty read is never
  mistakable for a failed one.
- **Status mapping** is one shared advice, `web.AccountReadApiExceptionHandler`, scoped to both
  controllers: 503 `DATA_STORE_UNAVAILABLE` for a `SQLException` â€” the same code and message as
  Â§5.5/Â§5.6/Â§5.10, since it means the same thing â€” and 500 `ACCOUNT_READ_FAILED` otherwise. One advice
  for both routes because they fail the same way for the same reasons and a caller already knows
  which of them it called. It stays separate from `ApiExceptionHandler` for the reason Â§5.10 records.
  Both messages are fixed; failure detail is logged server-side only, so no credential, connection
  string, SQL or exception text reaches a client. Being scoped with `assignableTypes`, the advice does
  not intercept the framework's own 404/405 responses for other paths.
- **Two shared service instances.** Like `CharacterSelectionService` (Â§5.10), neither service keeps
  per-call state â€” each holds only its repository, which opens its connection inside the call â€” so no
  per-request factory is needed and the beans cost no connection at startup.
- **JavaFX is untouched.** `BankView`/`MaterialsView` keep calling the same two services in process
  (Â§6); no HTTP, Spring or transport type appears in `application.*`, `repo.*` or either view.

### 5.12 Single-craft resolution explanation (`STORY-DOM-020`, `STORY-APP-012`)

`craft.SingleCraftExplainer` is the domain entry point for explaining **one** execution of an
explicitly selected recipe. `explainIndividual(...)` and `explainCoordinated(...)` take the same
captured inputs as `CraftingPlanner.evaluateAll(...)`/`evaluateAllCoordinated(...)` â€” recipes,
inventory pools, roster, quotes, settings, allowed recipe IDs â€” and return a
`SingleCraftExplanation`: a tree of immutable `CraftTraceNode`s, or `available() == false` when
there is no result to explain. The contract these facts serve is owned by
`TARGET_ARCHITECTURE.md` Â§13.3.

It runs the authoritative resolver rather than a second one. `CraftingResolver` gained a tracing
mode (`new CraftingResolver(true)`) in which every resolved requirement additionally records the
facts the economic result does not carry â€” the recipe it actually selected, how many executions it
performed and what they produced, the character it assigned, whether consumed owned quantity was
valued at zero without a modellable value, and the craft attempt whose ingredients explain the node
â€” on a `ResolvedNeed.NodeTrace`. Because those records hang off the `ResolvedNeed`s themselves, a
speculative craft that loses to buying or to another eligible character is discarded with its nodes
and cannot reach the explanation; a craft that was attempted and blocked is still reachable, which
is what lets a blocked requirement show why.

`RecipeSimulator` gained two opt-in behaviors for the same purpose: a package-private constructor
taking the resolver and a "stop after the first accepted craft" flag, and retention of the craft
attempt that ended a phase without being accepted (`RecipeSimulationResult.getBlockedAttempt()`,
paired with `blockedReason` and cleared with it). The explanation is therefore the simulation's
first craft under its real phase order â€” not a recipe skeleton, and not the craft that would follow
an exhausted simulation. Each call builds its own `PlannerContext` and `PlanState` from the supplied
inputs, so it loads nothing, mutates no caller state and shares nothing with another call.

Trace production is opt-in per selected recipe. The table paths in Â§5.1/Â§5.2 construct their
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
callers of that explainer (`TARGET_ARCHITECTURE.md` Â§13.1/Â§13.2). Each is one **fresh calculation**,
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
the row's simulation â€” which may consume the pool across several crafts â€” cannot become the
explanation's starting state.

`CraftingPlanner.evaluateOne(...)`/`evaluateOneCoordinated(...)` (new) are the table calculation
restricted to one recipe: the same context, the same fresh `PlanState` and the same
`evaluateOneRecipeNew` the `evaluateAll*` loops use, including their heuristic skip. They are an
entry point for the selected-recipe operation, not a second set of rules.

Isolation is structural rather than managed. `resolveDetail` neither reads nor writes the
`last*` fields that back the JavaFX lazy-tree lookups of Â§5.1/Â§5.2, and holds every mutable
planning state inside the one call, so successive, concurrent and failing operations cannot
contaminate one another, an operation that finds nothing discoverable reports exactly that instead
of an earlier operation's row/metadata/trace, and there is no retained session, snapshot token or
task store. `CraftingResolutionDetail.Status` keeps the three Â§13.3/Â§13.4 outcomes apart â€”
`RECIPE_NOT_IN_CALCULATION`, `AVAILABLE` (including a blocked explanation, which still carries its
reasons) and `RESULT_UNAVAILABLE`. Item names are taken only from the metadata that operation
captured; a traced item with no usable name is simply absent from `itemNames()`. The table paths
are untouched: they still build no semantic trace per row, and a detail row carries no legacy
`Node` tree.

The record also carries the operation's own selected `craft.Recipe` and its captured item-metadata
and quote maps, which is what lets a presentation layer describe the recipe, name items and report
quotes from *this* operation's facts without reading anything again (`STORY-API-008`). It remains
calculation facts only: no JSON, HTTP or JavaFX type appears in it, and the Â§13.3 envelope fields
(`consistency`, `calculatedAt`, the echoed `calculation`) are produced at the boundary in Â§5.13.

### 5.13 Crafting resolution-detail HTTP flow (`STORY-API-008`)

The two detail operations `TARGET_ARCHITECTURE.md` Â§13.1 fixes, added to the existing crafting
controllers over the Â§5.12 application operation. JavaFX is untouched and keeps calling the
application services in process; the table routes of Â§5.5/Â§5.6 are unchanged.

```text
POST /api/crafting/profit/resolution        POST /api/crafting/discovery/resolution
   |
   v
web.CraftingProfitApiController             web.CraftingDiscoveryApiController
   |  1. CraftingResolutionMapper.requireBody / requireRecipeId / requireCalculation
   |  2. Crafting{Profit,Discovery}ApiMapper.toEffective(calculation)   the table route's own
   |                                                                   defaults + validation
   |  3. Supplier<...Service>.get()                      one fresh service per request
   |  4. service.resolveDetail(recipeId, ...)            the Â§5.12 use case, unmodified
   |  5. CraftingResolutionMapper.toRow / treeStatus / toTree  copy the captured facts into DTOs
   v
200 Crafting{Profit,Discovery}ResolutionResponse | 404 | 400 | 503 | 500
```

Observed properties of this flow:

- **Required body with a nested existing contract.** Both members are required: `recipeId` (positive)
  and `calculation`, which *is* the corresponding table request record (`CraftingProfitRequest` /
  `CraftingDiscoveryRequest`). Its members therefore keep each table route's own defaults, scope
  kinds, validation messages and semantics â€” Profit's `ALL`/`DISCIPLINE`/`CHARACTER_DISCIPLINE` and its
  view defaults, Discovery's required individual scope, its separate nullable
  `inventoryCharacterName` with the service's unfiltered-pool fallback, its own defaults and its
  daily setting fixed to true (daily crafts allowed) and reported rather than accepted. Nothing else is an input: no row
  number, price, material map or prior-context identifier.
- **One fresh request-local calculation.** The controller holds the same
  `Supplier<...Service>` seam as the table route, so each request resolves on its own service
  instance and no `last*` reload field is read or written. The response's row and tree both come from
  the single `CraftingResolutionDetail` that one call returned, and the mapper reads no repository,
  graph or clock-dependent domain state â€” it cannot introduce a second data read.
- **Envelope.** `recipeId` and `calculation` echo the *requested* identity and effective inputs;
  `consistency` is the literal `FRESH_CALCULATION`; Profit uses
  `SELECTED_RESULT_OUTPUT_QUANTITY` for counted output and `FIRST_BLOCKED_ATTEMPT` for a zero-count
  selected row, while Discovery retains `SINGLE_OUTPUT_REQUIREMENT`. `calculatedAt` is a UTC ISO-8601
  completion instant that is informational only. `row` is the same shared `web.dto.CraftingRowDto`
  the tables report, produced by the same `web.CraftingRowMapper`, so a caller reads it identically on
  either route.
- **Tree.** `web.dto.ResolutionNodeDto` is a transport copy of `craft.CraftTraceNode`: quantities,
  the actually selected (or attempted) recipe id, craft count and production, assigned character,
  method/state/blocked-reason names, the three inclusive costs, ordered children, and â€” since
  `STORY-API-009` (Â§5.14) â€” this node's **own** item's nullable `iconUrl`. Nothing is
  summed, rounded, repaired or inferred here, repeated occurrences stay separate, and identities are
  kept apart â€” the envelope carries the requested recipe, every node carries what actually sourced its
  requirement, and an inventory-only root has no recipe, no craft and no invented ingredient path.
- **Status mapping** is the existing `web.ApiExceptionHandler`, plus one outcome:
  404 `RECIPE_NOT_IN_CALCULATION` (via `web.RecipeNotInCalculationException`) when the operation's own
  fresh candidate set does not contain the requested recipe. A blocked resolution is a 200 with
  `treeStatus: AVAILABLE` and its reasons; an unavailable calculation result is a 200 with
  `treeStatus: RESULT_UNAVAILABLE` and no `tree` rather than a fabricated empty one. 400/503/500 keep
  their existing codes and messages, and no database, credential or exception detail is returned.
- **Read-only.** A detail request consumes no persisted inventory, writes nothing and triggers no
  synchronization or GW2 API call.
- **Synchronous, by measurement.** Per Â§13.5 / Â§23 / `UD-007`, measured against the real database
  before the policy was fixed (`web.CraftingResolutionApiRealDbIT`; figures in `STORY-API-008`'s
  Result): comfortably inside a normal HTTP request across Profit `ALL`, a buying-enabled deep tree
  and a Discovery character scope, so both operations complete synchronously with no background task
  or status endpoint.

**Remaining work.** Both routes now have a browser consumer â€” Profit's since `STORY-WEB-007` and
Discovery's since `STORY-WEB-012`, sharing one implementation of Â§13.4's request/generation
association and one tree/detail presentation (Â§5.11) â€” but no `TARGET_ARCHITECTURE.md` Â§33 full-page
timing is claimed for either: the figures above are backend request time only, and the detail a
selection loads is still untimed. `KNOWN_PROBLEMS.md` CH-15 and CH-17 show through the tree
unchanged, as Â§5.12 records.

---

### 5.14 Item icon metadata and image delivery (`STORY-API-009`, `STORY-SYNC-004`)

Implements `TARGET_ARCHITECTURE.md` Â§12.1 (AR-005): item-bearing HTTP reads carry an
application-relative `iconUrl`, and one thin backend route serves those images from a persistent
filesystem cache shared with the JavaFX desktop icon download. The browser never receives an upstream
URL, a redirect to one, or a backend filesystem path.

**Boundaries as implemented.**

| Concern | Where it lives |
|---|---|
| Canonical source validation, canonicalization, source key, extension, content type | `infra.icons.IconSourcePolicy` (the single policy; `infra.icons.IconSource` is its accepted result) |
| Application-relative URL format **and** route parsing | `application.icons.ItemIconUrls` |
| Delivery decisions (route â†’ disk â†’ retained metadata â†’ acquisition) | `application.icons.IconDelivery` / `IconDeliveryResult` |
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
batch reads â€” `repo.ItemRepository.ItemInfo.iconUrl` (crafting tables, material lists and both
resolution-detail flows) and the `icon_url` column added to `repo.BankRepository.BankSlotRow` /
`repo.MaterialStorageRepository.MaterialStorageRow` â€” so a read costs no extra query, and no read
performs a per-item lookup, a synchronization step, an image download or a GW2 request. The transport
mappers turn that retained source into `iconUrl` through `ItemIconUrls`: `web.CraftingRowMapper` uses
the recipe's **output item** for a row and each missing material's own item;
`web.CraftingResolutionMapper` uses **each node's own item**, never the requested recipe's output and
never the node's sourcing. `iconPath` is gone from the browser contracts (`BankSlotDto`,
`MaterialStackDto`); JavaFX keeps consuming `items.icon_path` unchanged.

**Metadata acquisition, and how it is invoked.** `sync.IconSync.syncItemIconUrls()` refreshes the items
an item-bearing view can reference â€” bank slots, material storage, character inventories, recipe
outputs and recipe ingredients, nontradeable items included â€” plus any item still missing metadata,
and writes a URL that *changed*, not only a null one. It downloads no image and needs no local
directory. Invocations:

- JavaFX "First-time DB Setup" (`application.InitialSetupService.firstFill()`), unchanged;
- standalone, for a backend-only machine, with no browser control and no icon download:

  ```
  ./mvnw -o -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
  java -cp "target/classes;$(cat target/classpath.txt)" sync.IconSync            # repair metadata
  java -cp "target/classes;$(cat target/classpath.txt)" sync.IconSync --dry-run  # report coverage only
  ```

Missing metadata stays tolerable: it yields `iconUrl: null` and is repaired only by this explicit
refresh â€” never by navigation, a page-data read or an image request.

**Reaching an id that has no `items` row at all (`STORY-SYNC-004`).** Account synchronization stores
the item id it found; nothing creates that item's metadata row, and until this story the refresh's
selection read `FROM items`, so such an id was structurally unreachable â€” the gap `STORY-API-009`
recorded. `referencedOrUnknownItemIds` is now a union of the reference families *themselves* (plus
`items WHERE icon_url IS NULL`), so discovery no longer depends on a row existing, and the write is an
upsert:

| Row state | What the repair writes |
|---|---|
| no `items` row | the row is created with the canonical `name`, `type`, `rarity`, `vendor_value`, `icon_url` and `fetched_at` |
| row exists | whichever of `name`, `type`, `rarity` and `vendor_value` the stored row is **missing**, plus `icon_url` when upstream supplied one *and* it differs |

Every stored field wins over the response (`COALESCE(items.<col>, EXCLUDED.<col>)`), so a populated
value is never overwritten while a half-populated row â€” a row that exists but holds no canonical
metadata â€” is completed rather than left with an icon and nothing else. `icon_url` coalesces the other
way round, so an upstream response reporting no icon never blanks a stored one. The statement's
`WHERE` restricts the write to rows that actually gain something, so an unchanged run costs no row
updates, and `items.icon_path` â€” the desktop column â€” is still never touched. Upstream is reached through the same public `/v2/items` adapter every other sync uses,
behind a package-private `ItemMetadataSource` seam so a controlled fixture can stand in for it.

A batch whose metadata request fails is **reported and skipped, not fatal**: nothing is fabricated for
its ids, nothing retained is destroyed, and since the selection is recomputed from the references on
every run those ids are picked up by the next explicit repair. `MetadataRepair` is the run's own
report â€” selected, rows written, rows created, ids upstream did not return, items carrying no icon
reference, items whose reference the canonical policy rejects, failed batches and the ids they
covered â€” so remaining fallbacks are classified rather than merely counted. `--dry-run` additionally
reports how many selected ids have no `items` row, using the same selection and no GW2 call.

**Image route.** `GET /api/items/{itemId}/icon/{sourceKey}.{ext}`, in this order: validate the route's
own values â†’ read the cache â†’ on a miss, load the item's retained source and require the key and
extension it derives to match â†’ only then acquire. A valid disk hit therefore needs neither the
upstream host nor live metadata; an unknown item, absent or rejected metadata, and an obsolete or
foreign key are 404s with no upstream access. A cached old key keeps serving its original image, and
changed metadata produces a new key and a new URL on the next read. Malformed route â†’ 400; absent
metadata or upstream 404 â†’ 404; transient upstream failure, invalid image, exhausted capacity or disk
failure â†’ 503 with `Retry-After: 30`. All three carry `Cache-Control: no-store` and fixed wording with
no path, upstream URL or lower-layer message. Success carries the verified `Content-Type`,
`X-Content-Type-Options: nosniff`, `Cache-Control: public, max-age=86400` (finite, never `immutable`)
and a strong ETag derived from the stored bytes; `If-None-Match` yields a 304 that keeps both the ETag
and the freshness. There is no listing, no generic file path and no caller-supplied path, host or URL
anywhere on the route.

**Chosen protective bounds** (`infra.icons.IconCacheBounds`; limits, not measured latencies): connect
5 s, whole response 10 s, response body â‰¤ 2 MiB, decoded pixels â‰¤ 4 000 000, â‰¤ 4 concurrent
downloads, â‰¤ 32 requests handling a miss at once, â‰¤ 5 s waiting for a coordination or download slot,
30 s in-process failure suppression per key (which expires on its own and is consulted only *after*
the disk is rechecked), `Retry-After: 30`.

**Storage and publication.** Root is the existing `ICON_CACHE_DIR` (default
`<user.home>/.nebet-gw2-tool/icons`); entries live in its `assets-v1` subdirectory, one file per
canonical source, so items sharing a source share one binary. Only a fully committed file whose bytes
match its extension is an entry â€” empty, partial, corrupt, symlinked and temporary files all read as
a miss. Publication writes a unique `*.tmp` beside the destination and then renames it **without**
`REPLACE_EXISTING` (a plain `Files.move`, deliberately not `ATOMIC_MOVE`, which on Windows replaces
the destination): a reader never sees a partial file, and a concurrent publisher of the same key keeps
the committed entry instead of overwriting it. A failed publication is a 503, never a successful
uncached image, and no failure or placeholder is ever written at an image key. Nothing expires,
sweeps or redownloads a valid entry.

**Desktop coexistence.** `sync.IconSync.syncItemIconsToDisk` no longer downloads into
`items/{itemId}.png`. It goes through `sync.DesktopIconAdoption`, which reuses an entry either side
already published (no download), otherwise acquires and publishes through the same protocol, and only
then records the keyed file in `items.icon_path` â€” so a failed path update cannot invalidate a
committed asset, and the next run reuses it. Legacy `items/{itemId}.png` files are left in place and
stay usable by JavaFX; their bytes are adopted only when they are byte-identical to a successful
on-demand fetch of the canonical source (`ADOPTED_LEGACY`), since `IconSync` recorded no
source-version provenance and used `.png` names even for `.jpg` sources. Unproven legacy bytes never
become a keyed entry. No mass migration, startup migration or redownload happens, and there is no
second download store.

**Deployment requirement (recorded, not commissioned).** Per Â§12.1, container deployment must mount a
writable persistent volume or host directory at `ICON_CACHE_DIR`, kept stable across restarts; an
image layer, an ephemeral writable layer or a temporary directory is insufficient, and the cache never
silently degrades to memory-only. Desktop/backend sharing needs that same root at usable local paths â€”
host-specific absolute database paths are not portable web inputs. This story commissions no
deployment work and no new service, container or replica requirement.

**Evidence and limits.** Fixture-level coverage exists for source acceptance/rejection and key
derivation, publication atomicity and non-replacement, path/key confinement and symlinks, restart
reuse, coalescing, bounds, suppression expiry, storage failure, every status and header including
local 304, the nullable URLs and actual item identities, and desktop/web sharing with proven versus
unproven legacy reuse (`infra.icons.*Test`, `application.icons.*Test`, `web.ItemIconApiControllerTest`,
`sync.DesktopIconAdoptionTest`). The metadata refresh's *coverage*, *reachability without an `items`
row*, *changed-URL* behavior, preservation of retained metadata and account quantities, safe repeat,
per-batch failure tolerance and missing/rejected upstream classification are covered against a
disposable Postgres schema by `sync.IconSyncMetadataTest`, whose upstream is a controlled fixture â€” it
calls no GW2 API and downloads nothing, which is also how its independence from the desktop icon
download is shown. Real-database evidence
(`web.ItemIconApiRealDbIT`, `web.AccountReadApiRealDbEquivalenceIT`, figures in `STORY-API-009`'s and
`STORY-SYNC-004`'s Results): `STORY-API-009` left coverage partial and *unrepaired* (74 of 180 bank
slots carried an accepted source; 504 of 504 material stacks did; the referenced-item selection was
only dry-run at 13 965 items), and three sampled real URLs were delivered with the documented headers,
revalidated to 304, and re-served unchanged by a later backend process (persistent reuse across
restart, observed rather than only fixture-proven). `STORY-SYNC-004` then executed that refresh
against the same database: **163 of 180 bank slots now carry an accepted source â€” every occupied one**,
505 of 505 material stacks, 2 873 of 2 873 recipe ingredients and 13 025 of 13 065 recipe outputs, with
297 `items` rows created that no path had ever created. The 40 recipe outputs still without metadata
are ids the GW2 items endpoint answers `no such id` for, so they are unresolvable rather than pending;
they stay selected by every later run. A later run of the same invocation, after the upsert learned to
fill a stored row's missing canonical fields, reported
`selected=14302, rowsWritten=0, rowsCreated=0, notReturnedUpstream=40` and left every count above
unchanged â€” this database currently holds no row that is missing `name`, `type`, `rarity` or
`vendor_value`, so completing such a row is evidenced by `IconSyncMetadataTest` rather than by live
data, while the live run evidences that the widened write causes no churn on already-complete rows. Real-browser rendering of these URLs, the browser-side caching/revalidation
observations and the Â§33 full-page timings across the cold/warm browser and application-cache
combinations, the restart run and the warm-cache upstream-unavailable run are `STORY-WEB-010`'s and are
recorded in Â§5.11 ("Measured browser behaviour") and that story's Result. `STORY-SYNC-004` extended
`frontend/scripts/icon-live-check.mjs` from Bank to all three item-bearing views (its warm-storage
phases still assert about the one opening they were written for) and reran it after the repair: Bank,
Materials and the crafting Profit table rendered **0 entries without metadata and 0 failed images**
across 336 image requests, all on this application's own origin and icon route. **Still not covered:**
legacy adoption against real legacy files â€” this machine's cache holds no `items/` directory at all â€”
and any real upstream outage, redirect, oversized image or disk-full condition, all of which remain
controlled-fixture evidence only.

One production detail changed with `STORY-WEB-010`: `HttpIconImageFetcher`'s default client now routes
through `ProxySelector.getDefault()` instead of the builder's no-proxy default, so the JVM's own
`https.proxyHost`/`https.proxyPort`/`http.nonProxyHosts` configuration applies (Â§15 â€” egress becomes
configuration rather than a code change). With nothing configured the selector chooses a direct
connection, which is what every earlier run made; the observable consequence is that an upstream image
fetch can be *counted* by a proxy instead of inferred from a rendered picture.

---

### 5.15 Ectoplasm Salvage HTTP flow (removed)

`GET /api/ecto/salvage` and its response model, controller, exception mapping, and Spring bean wiring have been removed. The JavaFX `EctoView` still calls `application.EctoSalvageService` in process (Â§5.3). The browser page has not yet been replaced. Its future read inputs are the generic item routes and current-account Luck route in Â§5.17; no backend salvage calculation route exists.

---

### 5.16 Account Luck and Magic Find progression

The existing account sync sequences now include consumed Luck. `AccountRefreshService.refreshAll()` and `InitialSetupService.firstFill()` call `AccountRefreshGateway.syncAccountLuck()`, which delegates to `AccountSync`. The narrow materials-and-recipes refresh remains narrow. `AccountSync` uses the existing authenticated `Gw2ApiClient` to read `/v2/account` for the stable account GUID and `/v2/account/luck` for consumed Luck. The latter requires `account`, `progression`, and `unlocks` key permissions. An empty Luck array means zero; malformed values fail the sync before a write. HTTP/authentication failures propagate through the same account-sync task handling as the other account steps. Sync does not silently treat a missing permission as zero.

`AccountLuckRepository` upserts one `account_luck` row per GW2 account GUID, with a nonnegative `BIGINT` consumed value and fetch timestamp. This table is account-scoped even though the older bank/material/recipe tables remain single-account. `AccountLuckSchema` applies the idempotent SQL resource before normal backend startup completes and before the JavaFX account sync writes Luck. It also checks the required column types and nullability, failing startup or sync on an incompatible existing table. The resource remains available for manual application, but a normal backend startup no longer silently serves a database missing this table.

`luck.MagicFindProgression` loads one global CSV resource containing the exact cumulative thresholds from the [GW2 Wiki Luck progression table](https://wiki.guildwars2.com/wiki/Luck#Progression), from 0% through the 300% cap. It is not copied into account rows. `application.AccountLuckService` reads a named account's stored Luck and resolves its current percentage, current/next thresholds, remaining Luck, cap remainder, and +5%/+10%/cap targets. It can resolve arbitrary requested later targets without sending the whole table to a client. Luck-derived Magic Find is separate from achievement and other bonuses; consumed Luck above the 300% threshold is retained while progression stays capped.

`GET /api/account/luck` binds the read to the server-configured GW2 API key: `CurrentAccountLuckService` calls authenticated `/v2/account` for its stable ID, then reads only that ID through `AccountLuckService`. The browser supplies no account ID or key; query parameters are rejected. When that account has no stored Luck row, the service invokes the existing `AccountRefreshGateway.syncAccountLuck()` step once and rereads it, so an otherwise initialized cold backend does not require a separate full account sync before the page can load. This is the current single-user deployment boundary, not a multi-user session implementation. An unavailable GW2 identity or Luck sync returns 502, and an unavailable database returns 503. The removed Ectoplasm calculation route remains absent.

---

### 5.17 Generic item reads for the future Ectoplasm page

`GET /api/items/prices?ids=19721,24277` reads live `/v2/commerce/prices` quotes through the existing `EctoLivePriceGateway`. The response is `{ "prices": [{ "itemId": 19721, "buyUnitCopper": 0, "sellUnitCopper": 0 }] }`, with actual nonnegative copper values or nulls when a quote is unavailable. `buyUnitCopper` is the Trading Post's highest buy offer; `sellUnitCopper` is its lowest sell listing. This route does no salvage calculation and does not read the synchronized crafting price snapshot.

`GET /api/items/metadata?ids=...` batch-reads `items` through `ItemRepository`. Missing rows or icon sources are filled on demand through the existing `IconSync` GW2 item parser and metadata upsert, then reread. Each requested ID returns `{ "itemId": 19721, "name": "Glob of Ectoplasm", "iconUrl": "/api/items/19721/icon/<sourceKey>.png" }`; unknown items have null name and iconUrl. `ItemIconUrls` derives the application-relative image URL from retained canonical metadata, and the existing `ItemIconApiController` serves the image bytes. Both generic reads accept 1â€“50 distinct positive decimal IDs in a comma-separated `ids` query parameter and preserve request order. Upstream failures return 502; metadata storage failures return 503. No image source URL is sent to the browser.

The account Luck response contains consumed Luck, current and next Luck-derived Magic Find percentages, `cumulativeLuckForCurrentPercent`, the next and cap cumulative thresholds, remaining Luck to the next percentage and cap, the stored fetch timestamp, and labeled `PLUS_5`, `PLUS_10`, and `CAP` target records. The current threshold is the cumulative Luck at which the current percentage began; together with `cumulativeLuckForNextPercent`, it lets the browser display progress within that one level. At the 300% cap, the current threshold is the 300% threshold, the next percentage and threshold are null, next remainder is zero, and later target percentages clamp to 300. The browser displays a full progress bar at the cap. The controller only copies `AccountLuckService`'s resolved values.

---

## 6. UI â†’ Controller â†’ Domain/Repository/API Relationships

| View | Controller | Application (`application.*`) | Domain (`craft.*`) | Repository (`repo.*`) | Direct API/HTTP |
|---|---|---|---|---|---|
| `CraftingProfitView` | `CraftingProfitController` | yes (`CraftingProfitService`, `STORY-APP-001`, for reload; `TradingPostPriceRefreshService.refreshForProfit()`, `STORY-APP-006`, for its "Refresh Trade Post Prices" button; `AccountRefreshService.refreshMaterialsAndRecipes()`, `STORY-APP-008`, for its 90s auto-refresh timer; `CharacterSelectionService.getCraftingCharacterOptions()`, `STORY-APP-010`, for its Discipline selector) | indirectly, via the application service (`CraftingPlanner`, `RecipeTreeBuilder`) | indirectly, via the application service (repositories) | no |
| `CraftingDiscoveryView` | `CraftingDiscoveryController` | yes (`CraftingDiscoveryService`, `STORY-APP-002`, for reload; `TradingPostPriceRefreshService.refreshForDiscovery()`, `STORY-APP-006`, for its "Refresh Trade Post Prices" button; `AccountRefreshService.refreshAll()`, `STORY-APP-008`, for its 120s auto-refresh timer; `CharacterSelectionService.getCraftingCharacterOptions()`/`getCharacterNames()`, `STORY-APP-010`, for its two selectors) | indirectly, via the application service (`CraftingPlanner`, `RecipeTreeBuilder`) | indirectly, via the application service (4 repos) | no |
| `BankView` | none | yes (`BankContentsService`, `STORY-APP-009`) | no | indirectly, via the application service (`repo.BankRepository`, on `repo.Db.open()`) | no |
| `MaterialsView` | none | yes (`MaterialStorageService`, `STORY-APP-009`) | no | indirectly, via the application service (`repo.MaterialStorageRepository`, on `repo.Db.open()`) | no |
| `EctoView` | none | yes (`EctoSalvageService`, `STORY-APP-003`) | indirectly, via the application service (`ecto.EctoSalvageCalculator`) | no | indirectly, via the application service (`api.tp.EctoLivePriceGateway` â†’ `api.guildwars2.com/v2/commerce/prices`); icon fetching remains a direct `HttpClient` call in the view |
| `Gw2App` | none | "Sync Account" (`application.AccountRefreshService`, `STORY-APP-004`), "Sync ALL tradeable Items..." (`application.GlobalDataRefreshService`/`application.CraftingGraphRebuildService`, `STORY-APP-005`), and "First-time DB Setup" (`application.InitialSetupService`, `STORY-APP-007`) | indirectly, via the three application services for all three migrated buttons | indirectly, via the three application services for all three migrated buttons | indirectly via `sync.*` (`AccountRefreshService`'s `sync.AccountRefreshGateway` collaborator for Sync Account; `GlobalDataRefreshService`'s `sync.GlobalDataRefreshGateway` collaborator for Sync ALL tradeable Items; `InitialSetupService`'s `sync.AccountRefreshGateway`/`sync.GlobalDataRefreshGateway`/`sync.IconSyncGateway` collaborators, plus `application.TradingPostPriceRefreshService`, for First-time DB Setup) |

The three JavaFX crafting/Ecto features (Profit, Discovery, Ectoplasm Salvage) follow a View â†’ Application â†’ Domain(/Repository) separation; `EctoView` has no Controller layer, calling its application service directly. `Gw2App`'s "Sync Account", "Sync ALL tradeable Items..." and "First-time DB Setup" buttons now follow the same pattern; `STORY-APP-009` brought `BankView` and `MaterialsView` onto it too, so no view opens a database connection or runs SQL itself any more; `STORY-APP-010` removed the last direct repository construction from a view, so no view instantiates a `repo.*` repository either. Eleven named Application Layer boundaries now exist (`application.CraftingProfitService`/`STORY-APP-001`, `application.CraftingDiscoveryService`/`STORY-APP-002`, `application.EctoSalvageService`/`STORY-APP-003`, `application.AccountRefreshService`/`STORY-APP-004`, `application.GlobalDataRefreshService`/`application.CraftingGraphRebuildService`/`STORY-APP-005`, `application.TradingPostPriceRefreshService`/`STORY-APP-006`, `application.InitialSetupService`/`STORY-APP-007`, `application.BankContentsService`/`application.MaterialStorageService`/`STORY-APP-009`, `application.CharacterSelectionService`/`STORY-APP-010`); the other views' remaining direct API/HTTP use in this table is unaffected by those stories. `STORY-APP-008` added no boundary of its own â€” it routed both crafting views' auto-refresh timers through the existing `application.AccountRefreshService` (Â§5.4), after which no view calls `sync.*` directly any more.

---

## 7. Initialization and Synchronization Flow

- `Gw2App.start(Stage)` builds the home screen synchronously on the JavaFX Application Thread.
- Every sync/setup action is dispatched via a manually created `new Thread(...)` (marked daemon), not a managed executor or task framework.
- Completion/failure is reported back to the JavaFX thread using `Platform.runLater(...)`, updating a shared `status` `Label`.
- Errors are caught, printed via `ex.printStackTrace()`, and surfaced as a status-label string; there is no structured error type or centralized handling (matches `CURRENT_STATE_SPEC.md` Â§24).
- The Crafting Graph is only ever (re)built as a side effect of the "Sync ALL tradeable Items..." button and `InitialSetupService.firstFill()` is not itself called automatically â€” the user must trigger it manually from the home screen.
- `CraftingGraphCache.load()`/`rebuild()` read/write `crafting_graph_cache.json` using a path relative to the process's current working directory (`new File("crafting_graph_cache.json")`), not a configurable or resource-relative path.

---

## 8. Configuration

- `repo.AppConfig` now sources the GW2 API key and PostgreSQL URL/user/password from `repo.EnvConfig`, which reads environment variables with a gitignored `.env` file as a local-development fallback (see `.env.example`). No credential values remain hardcoded in source (`docs/KNOWN_PROBLEMS.md` Â§2.1).
- Configuration is supplied via environment variables / `.env` as described above. The one exception is the HTTP API runtime (`STORY-API-001`): `src/main/resources/application.properties` holds Spring Boot's own settings, and its only non-default value is the listen port, itself externalised as `server.port=${GW2_API_PORT:8080}` (environment variable, or any standard Spring override) rather than hard-coded. Database and GW2 API credentials still come from `repo.EnvConfig`; the API runtime adds no second credential path.
- The `Gw2App` home screen has an API-key `TextField` and "Save" button whose handler only sets a status label (`"API key saving not implemented yet."`) and does not persist anything â€” confirmed by direct reading of `Gw2App.java`.

---

## 9. Areas Where Responsibilities Are Mixed

Observed (not inferred) mixing of concerns, by file:

1. **(Resolved by `STORY-DOM-017`)** ~~`craft/*` importing `repo/*` types directly.~~ The crafting engine's core data types are now independent domain types (`craft.Recipe`, `craft.Ingredient`, `craft.PriceQuote`); `repo.RecipeRepository`/`repo.tp.TpPriceRepository` map persistence rows into them, and `craft.*` no longer imports anything from `repo.*`.
2. **(Resolved by `STORY-DOM-017`/`STORY-DOM-018`/`STORY-DOM-019`)** ~~`repo.RecipeRepository.loadRecipes(...)` embeds the "recipe is unlocked" business rule as a SQL `UNION` CTE.~~ The unlock decision is now `craft.RecipeKnowledgePolicy.isKnownAccountWide` (a single pure domain policy function, no JDBC/SQL dependency); `repo.RecipeRepository` fetches plain unlock facts and calls the policy in Java. `STORY-DOM-018` extracted the policy but left `loadRecipesForCharacter`/`loadMissingDiscoverableRecipeIdsForCharacter` checking only the selected character's own unlocks (tracked as a confirmed disagreement in `docs/KNOWN_PROBLEMS.md` Â§4.2); `STORY-DOM-019` corrected both to use the same account-wide (any-character) knowledge decision as `loadRecipes`, per `DOMAIN_SPEC.md` Â§34/35 and decided `DQ-010`.
3. **(Resolved by `STORY-APP-003`)** ~~`EctoView` calls its own `EctoSalvageCalculator` directly, with no application-service boundary.~~ `EctoView` is the JavaFX Ectoplasm Salvage implementation. Its calculation (`DUST_PER_ECTO = 0.75`, `LUCK_PER_ECTO = 20.0`, `ECTOS_PER_1000_LUCK = 50`, and DOMAIN_SPEC.md Â§46-47's fee-inclusive net cost) lives in `ecto.EctoSalvageCalculator`, a plain domain class with no JavaFX/repo/controller/application dependency. `EctoView` calls one `application.EctoSalvageService`, which fetches Ecto/Dust quotes via `api.tp.EctoLivePriceGateway` and invokes the calculator across all four Ecto-buy/Dust-sell combinations; `EctoView`'s `fillProfitGrid`/`fillLuckGrid` only format and display the returned results. Like Profit/Discovery, it now has a named Application Layer boundary, though (unlike those two) with no Controller layer in between.
4. **(Resolved by `STORY-APP-004`/`STORY-APP-005`/`STORY-APP-006`/`STORY-APP-007`)** ~~`Gw2App`'s "First-time DB Setup" button handler directly calls `InitialSetupService`/`sync.*`.~~ All three `Gw2App` sync buttons ("Sync Account", "Sync ALL tradeable Items...", "First-time DB Setup") now delegate to named application services (`application.AccountRefreshService`/`application.GlobalDataRefreshService`/`application.InitialSetupService`) instead of calling `sync.*`/`repo.*` directly from the button handler. `application.InitialSetupService.firstFill()` (`STORY-APP-007`) owns the setup orchestration that previously lived in a top-level `InitialSetupService` class outside the application layer; that class has been deleted.
5. **(Resolved by `STORY-INFRA-003`)** ~~Two independent JDBC connection helpers (`repo.Db`, `sync.Db`) with different method names but identical behavior.~~ `sync.Db` was removed; all `repo.*` and `sync.*` callers now share `repo.Db.open()`.
6. **(Resolved by `STORY-APP-009`)** ~~`BankView`/`MaterialsView` each declare their own literal `DB_URL`/`DB_USER`/`DB_PASS` constants and call `DriverManager.getConnection(...)` directly, bypassing `repo.AppConfig`/`repo.EnvConfig`/`repo.Db` entirely.~~ Both views now read through an application service (`application.BankContentsService`, `application.MaterialStorageService`) over a persistence adapter (`repo.BankRepository`, `repo.MaterialStorageRepository`) that opens its connection with the shared `repo.Db.open()` helper from item 5. The two extra connection-acquisition paths no longer exist (`docs/KNOWN_PROBLEMS.md` Â§2.2).
7. **(Resolved by `STORY-DOM-023` and `STORY-DOM-024`; one uncalled primitive remains)** Two Trading Post fee calculations now exist, for two different models. Resolved `UD-011` chose a profitability model, so `tradingpost.TradingPostFeePolicy` is the owner for profit: `craft.CraftingPlanner.evaluateOneRecipeNew(...)` deducts its 15% once, from the gross per-craft revenue, when building `CraftResult.profitCopper`/`totalProfitCopper`. `revenueCopper` and `totalSellValueCopper` stay gross, and every consumer that copies them â€” `application.CraftingProfitService`/`CraftingDiscoveryService`, both JavaFX controllers/views, `web.CraftingRowMapper`/`web.dto.CraftingRowDto` and the browser frontend â€” carries the corrected profits and the unchanged gross values through unaltered. `craft.CostEvaluationResult` publishes no profit of its own since `STORY-DOM-025`: its pre-fee `profitPerCraft`/`totalProfit`, and the unread `buyCostPerCraft`/`effectiveCostPerCraft` whose purchased-material basis (`sim.getFirstCraft().getBuyCostCopper()`) differed from the planner's, are gone, leaving only the gross `revenuePerCraft` and `opportunityCostPerCraft` the planner actually reads. `STORY-DOM-024` brought the Ectoplasm side onto the same owner: `ecto.EctoSalvageCalculator` holds no fee rate, multiplier or rounding rule of its own any more â€” its `netSaleProceeds(...)`/`SELL_FEE_PERCENT`/`0.85` multiplier are gone â€” and `evaluate(...)` scales the expected Dust yield onto the gross quote first, then takes `TradingPostFeePolicy.netOfFee(...)` off that one aggregate (Â§46), so no Dust unit is rounded into a modeled sale and no fractional expected drop becomes an actual one. Still open: `tradingpost.TradingPostSaleCalculator`, the Â§25.1 transaction model, has no caller (Â§25.1 forbids substituting it for the profitability model).

---

## 10. Status

This document reflects a point-in-time reading of the files listed in Â§2â€“Â§9. Files not explicitly named above (e.g. most of `parser/*`, `sync/CharacterSync`, `sync/ItemSync`, `sync/RecipeSync`, `sync/TpSync` internals, `sync/tp/relevance/*`) were identified and classified by responsibility but not read in full line-by-line detail during this pass. Deeper inspection of those files may refine this document further.
