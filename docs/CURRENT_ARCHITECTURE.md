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

The Python runtime in `agent/runtime/` is a separate development orchestrator, not part of the deployed application. Its story flow is Planner when needed -> Architect when an actionable architecture request exists -> pre-implementation QA for behavior-bearing work -> coding agent -> Evaluator. QA may explicitly record that a low-risk change needs no application tests. QA writes a durable per-story plan; QA-owned tests and expectations are protected across coding retries. An atomic attempt journal records `CODING`, evaluation, QA review, publication/CI and finalization stages. Startup reconciles its active-story pointer, safely resumes completed agent work, and stops with the preserved artifact when state is malformed or contradictory. A story is finalized only with a persisted evaluator verdict bound to its requirements and result, followed by the configured CI gate. Git publication is limited to tracked story/planning paths, leaving unrelated working-tree edits untouched. Known CI failures in suites outside the story's published paths create a tooling hold and resume at CI after repair; ambiguous failures conservatively follow the repair path. Planner holds fingerprint product/architecture requirements and agent contracts, not descriptive current-state snapshots. See [QA Instructions](../agent/QA_INSTRUCTIONS.md) and the [orchestrator runtime guide](../agent/runtime/README.md) for invocation triggers, recovery and retry policy. This summary was checked against the runtime on 2026-10-04.

## 4. Browser API and workflows

The browser calls same-origin `/api` routes; during development Vite proxies those routes to the Spring Boot server (default port 8080). GW2 credentials and database settings remain backend-side in the current local configuration. The frontend does not contact ArenaNet.

The API exposes Crafting Profit, Crafting Discovery, crafting resolution details, selector options, bank/material reads, item/icon metadata, account Luck, task submission/status, and system status. `GET /api/account/materials` joins the ordered ArenaNet `/v2/materials` catalog with the last synchronized account quantities; every catalog position remains present with quantity zero when unowned. It fails until a material sync has completed, including when no stacks were returned. Ectoplasm Salvage is calculated in the browser from backend-provided inputs; its old backend calculation route is absent. HTTP request/response DTOs are transport models and do not replace domain types.

Crafting Profit's **Refresh data & results** workflow first submits the account refresh task for bank, material storage, account recipe knowledge, and character crafting/recipes/inventory. It then requests a fresh calculation and renders loading/error feedback. This deliberately excludes account Luck. The feature does not require a visit to System Status.

Crafting Discovery takes one character/discipline scope. Its application service uses that character for candidate eligibility and owned inventory, and evaluates one attempt per candidate. The API has no independent inventory-character or max-buy input. Recipe output quantity still affects the revenue from that attempt.

Crafting Profit and Discovery have separate item-ID relevance collectors but share `TradingPostPriceRefreshService`, the same persisted quote repository, freshness rule, GW2 batching path, and price cache. The selected result exposes backend-provided resolution and purchase values; the frontend formats/displays these values and does not reconstruct recursive quantities or acquisition prices. UI label semantics are explained by the linked glossary; domain meaning is specified in `DOMAIN_SPEC.md`.

Crafting Profit and Discovery keep output-name, direct-ingredient and other existing field matching local over the current calculation rows. Indirect matches use a debounced `/api/crafting/ingredient-search` request; it returns only matching recipe IDs and does not trigger a calculation or price refresh. The route queries the shared in-memory `CraftingGraphCache` snapshot, whose item-name catalog was loaded with the graph. It walks the reverse ingredient-to-consumer index, following each reached recipe's output item to all its consumers. The browser intersects those IDs with rows already returned by the current calculation, so search never widens the result scope and never transfers dependency paths or crafting trees. Search is independent of the resolver: a static path can match even when inventory, character eligibility, buying settings or profitability made the resolver buy an intermediate or choose another path.

## 5. Refresh and background work

### Account data

`application.AccountRefreshService` reuses the existing account sync gateway. `refreshCraftingProfitData()` refreshes bank, material storage, account recipes, and character crafting/recipes/inventory, omitting Luck. `refreshAll()` also refreshes Luck and is available through System Status. Account records and refresh timestamps are single-account/process state today; they are not tenant-scoped.

### Trading Post prices

`application.TradingPostPriceRefreshService` accepts the required item IDs from the feature-specific collector. Persisted prices younger than ten minutes are reused. Missing/stale required IDs are fetched in existing batches and upserted; no full-catalog timer runs. The same mechanism serves Profit and Discovery. Status exposes cached/stale counts and newest fetch time where available.

### Global game data and crafting graph

`GlobalDataRefreshScheduler` checks global data with a configurable fixed delay. The current defaults are six hours and a one-minute initial delay (`GW2_GLOBAL_REFRESH_INTERVAL_MS`, `GW2_GLOBAL_REFRESH_INITIAL_DELAY_MS`, in `src/main/resources/application.properties`). It submits the same `GlobalDataRefreshService` operation used by the System Status manual action through the in-process `BackgroundTaskService`.

Recipe and TP-tradeable ID lists are fetched before expensive detail data. Unchanged recipe IDs return without recipe detail downloads. Changed recipes are synchronized, including ingredient replacement and removed-ID cleanup; the recipe sync reports a change only after its work succeeds. The Spring application shares one `CraftingGraphCache` instance among both crafting calculation factories, ingredient search and global refresh. `load()` deserializes/builds one immutable in-memory graph snapshot; `rebuild()` loads the recipe catalog and referenced item names, writes the cache file through a temporary file and atomic replacement, then publishes the new snapshot. After changed recipe sync succeeds, global refresh invalidates the shared snapshot and rebuilds it. Readers already holding the old immutable graph finish safely; later readers wait for the complete rebuild and cannot observe a partial graph. A failed rebuild leaves the cache invalidated rather than serving stale recipe relationships. A TP-tradeable list change alone does not trigger a graph rebuild. If the cache is absent, `load()` generates the baseline on first use. Manual graph rebuild is not offered on the current System Status page.

The scheduler catches task failures through the shared task boundary, records the latest global failure in process-local status, and can run again on a later interval. Task history, check/change times, and account refresh times are not durable telemetry and reset on restart.

### Coordination limits

`web.task.BackgroundTaskService` prevents overlapping unfinished tasks with the same operation key within one backend process. It does not use a distributed queue, lock, or shared job store. This is appropriate for today's single-backend-instance runtime. If multiple replicas are introduced, global scheduled work and refresh deduplication need shared coordination (for example PostgreSQL leases/advisory locks); that is future work.

## 6. System Status

The browser destination formerly named Synchronization is presented as **System Status**. It is an admin/diagnostic page, not a normal-user maintenance sequence. It shows account refresh scope/time, global check/change/recipe/graph timestamps, shared TP freshness/counts, tracked task status, and the last global failure where available. Color is paired with text for OK, Problem, Running, and not-run states. Its manual operations are full account refresh and global-data check. Feature flows perform account/price refreshes they need without requiring this page.

## 7. Persistence and current tenancy limit

Account-dependent browser reads and crafting calculations use durable per-source completion markers in `account_sync_state`. A source becomes fresh only when its API fetch and data writes succeed; ordinary reads do not update the marker. The default maximum age is 15 minutes (`gw2.account-data.max-age-ms`, default 900000). An expired or absent marker starts/reuses the shared `ACCOUNT_SYNC` background task and the HTTP response advertises its task-status URL; the browser waits for completion and retries once. Bank/material/Luck screens refresh only their own source. Crafting Profit/Discovery refresh bank, materials, account recipes and characters. Static game metadata, Trading Post data and crafting graph invalidation remain on their separate policies. Character timestamps are published only after all character payloads complete, so partially committed rows do not present the character source as current.

PostgreSQL stores shared item/recipe metadata and TP cache data together with one configured account's bank/material, recipe, character, and inventory state. Account data is not consistently keyed by a stable account identity; only selected newer data such as account Luck has identity-aware persistence. The current local API key is configured by environment, and the HTTP API has no application authentication. This is not multi-user isolation.

The intended future scope is documented in `TARGET_ARCHITECTURE.md`: account state and credentials become account/user scoped, while TP prices, GW2 metadata, and the crafting graph remain shared. Avoid treating the current schema/key model as a desired hosted model. The exact hosted provider is undecided; Cloudflare-related hosting is only a possible option for later evaluation.

## 8. Startup, configuration, and build

`web.Gw2ApiApplication` starts Spring Boot; `Gw2App` remains the JavaFX entry point. The backend uses `repo.EnvConfig` for local environment and `.env` configuration, and Spring properties expose the HTTP port and global scheduler interval settings. The schema is still created/maintained manually; no versioned migration tool is selected. The system is not containerized or deployed.

Run backend tests with `./mvnw test jacoco:report`; frontend coverage uses `npm run test:coverage` under `frontend/`; the Python agent-runtime suite and coverage use pytest over the existing unittest-compatible suite. `TEST_STRATEGY.md` defines the test layers, local prerequisites, browser smoke checks, CI gate, coverage KPI, and performance acceptance procedure. CI collects JaCoCo, Vitest V8, and coverage.py reports and publishes a combined summary plus raw/HTML artifacts; coverage is warning-only below the 90% target.

## 9. Legacy JavaFX paths

JavaFX views retain legacy controls and presentation workflows, including home-screen setup/account/global actions, per-view timers, and Ectoplasm price refresh. Those controls do not define browser behavior. Browser flows use HTTP task APIs and the automated global scheduler described above. JavaFX-only concurrency and presentation limitations are tracked in `KNOWN_PROBLEMS.md` and should be labeled as legacy if they are carried forward.

## 10. Evidence scope

This map was checked against the Spring controllers, `GlobalDataRefreshScheduler`, `GlobalDataRefreshService`, `RecipeSync`, `AccountRefreshService`, `TradingPostPriceRefreshService`, task/status services, frontend screens and APIs, and project configuration. It summarizes their architecture and does not attempt to enumerate every DTO field, SQL statement, or historical story result; those details belong to their code/tests or canonical story files.
