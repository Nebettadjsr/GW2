# GW2 Tool — Current Architecture

## 1. Purpose and authority

This document describes the architecture observed in the source tree as of 2026-09-30. It is a current implementation map, not a normative domain specification or target design. See [Domain Specification](DOMAIN_SPEC.md) for product rules, [Target Architecture](TARGET_ARCHITECTURE.md) for intentional boundaries and future decisions, [Current State](CURRENT_STATE_SPEC.md) for runtime/setup facts, and [Roadmap](ROADMAP.md) for sequencing. The [Crafting Glossary](crafting/GLOSSARY.md) is the canonical mapping of player-facing terms and labels.

## 2. Runtime shape

```text
Browser (Vue 3 / TypeScript / Vite)
       | same-origin HTTP / JSON
       v
Spring Boot web package
       v
Application services ---- repositories ---- PostgreSQL
       |
       +---- GW2 API adapters ----------------+

Legacy JavaFX entry point and views remain in the Maven application.
```

The browser UI is the active and canonical product interface. JavaFX remains as a local compatibility interface; it is not a web runtime and is scheduled for later removal. The backend is Java 25 / Spring Boot 4.1.1, built with Maven. The frontend is in `frontend/` and is built with Vite.

## 3. Source areas and responsibility

- `web.*` contains the Spring Boot entry point, HTTP controllers, request/response DTOs, error mapping, and task coordination.
- `application.*` coordinates Crafting Profit, Discovery, Ectoplasm support for the legacy UI, account/global refresh, price refresh, setup, and bank/material reads.
- `craft.*` contains the persistence-independent crafting model and calculation/resolution logic. It does not import repository types.
- `repo.*` owns PostgreSQL access and persistence-to-domain mapping.
- `sync.*` and `api.*` own synchronization and Guild Wars 2 HTTP adapters.
- `frontend/src/` owns browser screens, HTTP clients, presentation, interaction state, and the explicitly frontend-owned Ectoplasm calculation.
- JavaFX views/controllers remain in the legacy default package and call shared application/domain behavior directly where available.

The primary direction is browser -> HTTP -> application -> domain/repository/API adapters. Domain calculation code remains free of UI, HTTP, SQL/JDBC, and GW2 response-model dependencies. JavaFX is a parallel compatibility entry point, not a dependency of the browser backend.

## 4. Browser API and workflows

The browser calls same-origin `/api` routes; during development Vite proxies those routes to the Spring Boot server (default port 8080). GW2 credentials and database settings remain backend-side in the current local configuration. The frontend does not contact ArenaNet.

The API exposes Crafting Profit, Crafting Discovery, crafting resolution details, selector options, bank/material reads, item/icon metadata, account Luck, task submission/status, and system status. Ectoplasm Salvage is calculated in the browser from backend-provided inputs; its old backend calculation route is absent. HTTP request/response DTOs are transport models and do not replace domain types.

Crafting Profit's **Refresh data & results** workflow first submits the account refresh task for bank, material storage, account recipe knowledge, and character crafting/recipes/inventory. It then requests a fresh calculation and renders loading/error feedback. This deliberately excludes account Luck. The feature does not require a visit to System Status.

Crafting Discovery takes one character/discipline scope. Its application service uses that character for candidate eligibility and owned inventory, and evaluates one attempt per candidate. The API has no independent inventory-character or max-buy input. Recipe output quantity still affects the revenue from that attempt.

Crafting Profit and Discovery have separate item-ID relevance collectors but share `TradingPostPriceRefreshService`, the same persisted quote repository, freshness rule, GW2 batching path, and price cache. The selected result exposes backend-provided resolution and purchase values; the frontend formats/displays these values and does not reconstruct recursive quantities or acquisition prices. UI label semantics are explained by the linked glossary; domain meaning is specified in `DOMAIN_SPEC.md`.

## 5. Refresh and background work

### Account data

`application.AccountRefreshService` reuses the existing account sync gateway. `refreshCraftingProfitData()` refreshes bank, material storage, account recipes, and character crafting/recipes/inventory, omitting Luck. `refreshAll()` also refreshes Luck and is available through System Status. Account records and refresh timestamps are single-account/process state today; they are not tenant-scoped.

### Trading Post prices

`application.TradingPostPriceRefreshService` accepts the required item IDs from the feature-specific collector. Persisted prices younger than ten minutes are reused. Missing/stale required IDs are fetched in existing batches and upserted; no full-catalog timer runs. The same mechanism serves Profit and Discovery. Status exposes cached/stale counts and newest fetch time where available.

### Global game data and crafting graph

`GlobalDataRefreshScheduler` checks global data with a configurable fixed delay. The current defaults are six hours and a one-minute initial delay (`GW2_GLOBAL_REFRESH_INTERVAL_MS`, `GW2_GLOBAL_REFRESH_INITIAL_DELAY_MS`, in `src/main/resources/application.properties`). It submits the same `GlobalDataRefreshService` operation used by the System Status manual action through the in-process `BackgroundTaskService`.

Recipe and TP-tradeable ID lists are fetched before expensive detail data. Unchanged recipe IDs return without recipe detail downloads. Changed recipes are synchronized, including ingredient replacement and removed-ID cleanup; the recipe sync reports a change only after its work succeeds. `GlobalDataRefreshService` rebuilds the shared crafting graph during refresh only when the recipe sync reports changed data. A TP-tradeable list change alone does not trigger a graph rebuild. If the cache is absent, `CraftingGraphCache.load()` generates the baseline on first use. Manual graph rebuild is not offered on the current System Status page.

The scheduler catches task failures through the shared task boundary, records the latest global failure in process-local status, and can run again on a later interval. Task history, check/change times, and account refresh times are not durable telemetry and reset on restart.

### Coordination limits

`web.task.BackgroundTaskService` prevents overlapping unfinished tasks with the same operation key within one backend process. It does not use a distributed queue, lock, or shared job store. This is appropriate for today's single-backend-instance runtime. If multiple replicas are introduced, global scheduled work and refresh deduplication need shared coordination (for example PostgreSQL leases/advisory locks); that is future work.

## 6. System Status

The browser destination formerly named Synchronization is presented as **System Status**. It is an admin/diagnostic page, not a normal-user maintenance sequence. It shows account refresh scope/time, global check/change/recipe/graph timestamps, shared TP freshness/counts, tracked task status, and the last global failure where available. Color is paired with text for OK, Problem, Running, and not-run states. Its manual operations are full account refresh and global-data check. Feature flows perform account/price refreshes they need without requiring this page.

## 7. Persistence and current tenancy limit

PostgreSQL stores shared item/recipe metadata and TP cache data together with one configured account's bank/material, recipe, character, and inventory state. Account data is not consistently keyed by a stable account identity; only selected newer data such as account Luck has identity-aware persistence. The current local API key is configured by environment, and the HTTP API has no application authentication. This is not multi-user isolation.

The intended future scope is documented in `TARGET_ARCHITECTURE.md`: account state and credentials become account/user scoped, while TP prices, GW2 metadata, and the crafting graph remain shared. Avoid treating the current schema/key model as a desired hosted model. The exact hosted provider is undecided; Cloudflare-related hosting is only a possible option for later evaluation.

## 8. Startup, configuration, and build

`web.Gw2ApiApplication` starts Spring Boot; `Gw2App` remains the JavaFX entry point. The backend uses `repo.EnvConfig` for local environment and `.env` configuration, and Spring properties expose the HTTP port and global scheduler interval settings. The schema is still created/maintained manually; no versioned migration tool is selected. The system is not containerized or deployed.

Run backend tests with `./mvnw test`. Frontend verification uses npm scripts under `frontend/`. `TEST_STRATEGY.md` defines the test layers, local prerequisites, browser smoke checks, CI gate, and performance acceptance procedure.

## 9. Legacy JavaFX paths

JavaFX views retain legacy controls and presentation workflows, including home-screen setup/account/global actions, per-view timers, and Ectoplasm price refresh. Those controls do not define browser behavior. Browser flows use HTTP task APIs and the automated global scheduler described above. JavaFX-only concurrency and presentation limitations are tracked in `KNOWN_PROBLEMS.md` and should be labeled as legacy if they are carried forward.

## 10. Evidence scope

This map was checked against the Spring controllers, `GlobalDataRefreshScheduler`, `GlobalDataRefreshService`, `RecipeSync`, `AccountRefreshService`, `TradingPostPriceRefreshService`, task/status services, frontend screens and APIs, and project configuration. It summarizes their architecture and does not attempt to enumerate every DTO field, SQL statement, or historical story result; those details belong to their code/tests or canonical story files.
