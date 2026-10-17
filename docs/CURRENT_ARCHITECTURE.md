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

The development workflow is separate from the deployed application. The Python workflow service (`workflow_service/`) schedules work, executes stages and saves progress, using shared code in `agent/runtime/`. The story flow is Planner/Architect when needed -> select -> pre-implementation QA for behavior-bearing work -> coding agent -> Evaluator with conditional QA review -> publish -> GitHub CI -> finalize, with bounded retry and CI-fix loops back to the coding agent. QA writes a durable per-story plan; QA-owned tests and expectations are protected across coding retries. The service persists story phases, retry counters and scheduling checkpoints for recovery. Git publication commits only the story's own paths; CI is judged on the published SHA by the application jobs, while agent-workflow failures are reported separately. Claude requires fresh session and weekly-budget checks; settings persist with workflow state. See the [service guide](../workflow_service/README.md) for operation and migration from the earlier installation, and the [runtime guide](../agent/runtime/README.md) for shared execution code. This summary describes the source implementation as of 2026-10-17, not a completed switch of the live service.

## 4. Browser API and workflows

The browser calls same-origin `/api` routes; during development Vite proxies those routes to the Spring Boot server (default port 8080). GW2 credentials and database settings remain backend-side in the current local configuration. The frontend does not contact ArenaNet.

The API exposes Crafting Profit, Crafting Discovery, crafting resolution details, selector options, bank/material reads, item/icon metadata, account Luck, task submission/status, and system status. `GET /api/account/materials` joins the ordered ArenaNet `/v2/materials` catalog with the last synchronized account quantities; every catalog position remains present with quantity zero when unowned. It fails until a material sync has completed, including when no stacks were returned. Ectoplasm Salvage is calculated in the browser from backend-provided inputs; its old backend calculation route is absent. HTTP request/response DTOs are transport models and do not replace domain types.

Crafting Profit's **Refresh data & results** control posts the calculation request and nothing else; the backend admits whatever account and Trading Post refresh its own inputs still need, which covers bank, material storage, account recipe knowledge and character crafting/recipes/inventory and deliberately excludes account Luck. The browser calls no synchronization trigger from this page, so no second account writer can originate here, and the feature does not require a visit to System Status.

`POST /api/crafting/profit` and `POST /api/crafting/profit/resolution` answer from the last successfully published inputs and do not wait for account synchronization or a price fetch (`TARGET_ARCHITECTURE.md` section 9.1, ADR-007). `application.CachedCraftingProfitService` captures the required sources' completion markers and the relevant item set's quote coverage, runs the ordinary calculation when those inputs can produce one, and only then admits the refresh they still need. The response is `200` with the result and a bounded freshness envelope — input generation identity, per-source fresh/stale/missing state, aggregate price coverage, outstanding work with task-status URLs, and bounded retry information — or `202` with that envelope and no rows when no required source has ever synchronized. Expired inputs produce a provisional `200`, never a refusal; a succeeded refresh task alone does not make a result final. The detail route reports whether it still explains the input generation the caller's row came from, and always carries the row recalculated from its own. Input generation identity is derived from the required sources' completion instants, the required item set and a version of the relevant quotes themselves, so a quote refetched inside the reported coverage boundaries or rewritten with new numbers under its existing instant is a different generation even though no coverage aggregate changed.

On the browser side that envelope is consumed by `crafting/useCraftingProfit.ts`, which owns one bounded recovery cycle per context, and presented by `crafting/ProfitRefreshStatus.vue`, which formats supplied states and counts and derives no freshness. The cycle reads the advertised `taskStatusUrl` values through the existing `syncApi.readTaskStatus` and builds no task path of its own; after work completes it resubmits the calculation with the settings currently in effect, because the backend's checks — not a task state — decide whether a result is final. Its clock, waiting and budget timer are injected ports, so the five-minute budget is driven deterministically in tests; the budget is taken once per cycle and covers polling, successive tasks and recalculation, including the calculation an explicit retry asks for. It is enforced both by checks between awaits and by a cancellable timer started with the cycle, so a task-status read or a recalculation that never answers ends the cycle at the deadline instead of animating behind a pending request; the timer is cancelled when the cycle ends first. A monotonic request identifier discards a superseded calculation answer and a cycle counter stops an abandoned cycle at its next checkpoint and discards the answer of a request belonging to it, so a new selection, a settings change, an explicit retry or an expired cycle cannot be overwritten by earlier work, cannot run two cycles at once, and need not assume the backend stopped what it was doing. Every round after the first waits one interval, which bounds the request rate independently of what the backend advertises. The detail request carries the displayed response's `calculationId` as `basedOnCalculationId`, and the detail panel renders the response's own row when the backend reports `RECALCULATED_FOR_CHANGED_INPUTS`, so a row and the tree beside it always come from one generation. `scripts/profit-refresh-browser-smoke.mjs` covers this lifecycle in a real browser against scripted responses, and `scripts/profit-envelope-live-check.mjs` checks the shape those responses imitate against the running backend.

Crafting Discovery takes one character/discipline scope. Its application service uses that character for candidate eligibility and owned inventory, and evaluates one attempt per candidate. The API has no independent inventory-character or max-buy input. Recipe output quantity still affects the revenue from that attempt.

Crafting Profit and Discovery have separate item-ID relevance collectors but share `TradingPostPriceRefreshService`, the same persisted quote repository, freshness rule, GW2 batching path, and price cache. The selected result exposes backend-provided resolution and purchase values; the frontend formats/displays these values and does not reconstruct recursive quantities or acquisition prices. UI label semantics are explained by the linked glossary; domain meaning is specified in `DOMAIN_SPEC.md`.

Crafting Profit and Discovery keep output-name, direct-ingredient and other existing field matching local over the current calculation rows. Indirect matches use a debounced `/api/crafting/ingredient-search` request; it returns only matching recipe IDs and does not trigger a calculation or price refresh. The route queries the shared in-memory `CraftingGraphCache` snapshot, whose item-name catalog was loaded with the graph. It walks the reverse ingredient-to-consumer index, following each reached recipe's output item to all its consumers. The browser intersects those IDs with rows already returned by the current calculation, so search never widens the result scope and never transfers dependency paths or crafting trees. Search is independent of the resolver: a static path can match even when inventory, character eligibility, buying settings or profitability made the resolver buy an intermediate or choose another path.

## 5. Refresh and background work

### Account data

`application.AccountRefreshService` reuses the existing account sync gateway. `refreshCraftingProfitData()` refreshes bank, material storage, account recipes, and character crafting/recipes/inventory, omitting Luck. `refreshAll()` also refreshes Luck and is available through System Status. Account records and refresh timestamps are single-account/process state today; they are not tenant-scoped.

Automatic refresh admission goes through `application.AccountRefreshCoordinator`, which accumulates each caller's required sources into one outstanding set and drains it in a single `ACCOUNT_SYNC` task, one batch at a time. Sources demanded while a narrower batch is in flight are refreshed by the same run, so no second account writer starts. When another entry point already holds the operation key, the coordinator keeps the demand and reports that the named task is not refreshing those sources, rather than adopting it; the next request rechecks and admits what is still required. Admission never establishes freshness — that is read from the completion markers alone — so a task of insufficient scope cannot present its omitted sources as current (`docs/bugs/BUG-2026-001`).

### Trading Post prices

`application.TradingPostPriceRefreshService` accepts the required item IDs from the feature-specific collector. Persisted prices younger than ten minutes are reused. Missing/stale required IDs are fetched in existing batches and upserted; no full-catalog timer runs. The same mechanism serves Profit and Discovery. Status exposes cached/stale counts and newest fetch time where available.

Crafting Profit no longer fetches quotes inside its own request. `application.TradingPostRefreshCoordinator` coalesces concurrent coverage demands into one run under the existing `PRICE_REFRESH_PROFIT` key and gives a demand raised during a run one further pass afterwards, which recomputes the required set and therefore covers ids that became relevant only because account data changed meanwhile. It is coordinated separately from account refresh and introduces no second cache or fetch path. `sync.tp.relevance.CraftingProfitItemCollector` now owns one relevance definition shared by the refresh selection and the coverage report the feature reports freshness from.

### Global game data and crafting graph

`GlobalDataRefreshScheduler` checks global data with a configurable fixed delay. The current defaults are six hours and a one-minute initial delay (`GW2_GLOBAL_REFRESH_INTERVAL_MS`, `GW2_GLOBAL_REFRESH_INITIAL_DELAY_MS`, in `src/main/resources/application.properties`). It submits the same `GlobalDataRefreshService` operation used by the System Status manual action through the in-process `BackgroundTaskService`.

Recipe and TP-tradeable ID lists are fetched before expensive detail data. Unchanged recipe IDs return without recipe detail downloads. Changed recipes are synchronized, including ingredient replacement and removed-ID cleanup; the recipe sync reports a change only after its work succeeds. The Spring application shares one `CraftingGraphCache` instance among both crafting calculation factories, ingredient search and global refresh. `load()` deserializes/builds one immutable in-memory graph snapshot; `rebuild()` loads the recipe catalog and referenced item names, writes the cache file through a temporary file and atomic replacement, then publishes the new snapshot. After changed recipe sync succeeds, global refresh invalidates the shared snapshot and rebuilds it. Readers already holding the old immutable graph finish safely; later readers wait for the complete rebuild and cannot observe a partial graph. A failed rebuild leaves the cache invalidated rather than serving stale recipe relationships. A TP-tradeable list change alone does not trigger a graph rebuild. If the cache is absent, `load()` generates the baseline on first use. Manual graph rebuild is not offered on the current System Status page.

The scheduler catches task failures through the shared task boundary, records the latest global failure in process-local status, and can run again on a later interval. Task history, check/change times, and account refresh times are not durable telemetry and reset on restart.

### Coordination limits

`web.task.BackgroundTaskService` prevents overlapping unfinished tasks with the same operation key within one backend process. It does not use a distributed queue, lock, or shared job store. This is appropriate for today's single-backend-instance runtime. If multiple replicas are introduced, global scheduled work and refresh deduplication need shared coordination (for example PostgreSQL leases/advisory locks); that is future work.

## 6. System Status

The browser destination formerly named Synchronization is presented as **System Status**. It is an admin/diagnostic page, not a normal-user maintenance sequence. It shows account refresh scope/time, global check/change/recipe/graph timestamps, shared TP freshness/counts, tracked task status, and the last global failure where available. Color is paired with text for OK, Problem, Running, and not-run states. Its manual operations are full account refresh and global-data check. Feature flows perform account/price refreshes they need without requiring this page.

## 7. Persistence and current tenancy limit

Account-dependent browser reads and crafting calculations use durable per-source completion markers in `account_sync_state`. A source becomes fresh only when its API fetch and data writes succeed; ordinary reads do not update the marker. The default maximum age is 15 minutes (`gw2.account-data.max-age-ms`, default 900000). Bank/material/Luck screens refresh only their own source; Crafting Discovery refreshes bank, materials, account recipes and characters. For those routes an expired or absent marker admits refresh through the account coordinator and answers `ACCOUNT_DATA_STALE` advertising the task-status URL; the browser waits for completion and retries once. Crafting Profit is not gated that way — see section 4. Static game metadata, Trading Post data and crafting graph invalidation remain on their separate policies.

Each account source writes its rows and its completion marker in one transaction, so a published generation is visible whole or not at all and a failed refresh leaves the previous generation and its marker readable. The character source commits every character payload and its marker together for that reason. A Crafting Profit calculation reads all of its inputs — markers, price coverage, recipes, roster, inventory, quotes and item metadata — through one `REPEATABLE READ` snapshot (`application.ProfitInputSession`), so it cannot pair a part-written refresh with an older marker or mix input generations within one calculation.

PostgreSQL stores shared item/recipe metadata and TP cache data together with one configured account's bank/material, recipe, character, and inventory state. Account data is not consistently keyed by a stable account identity; only selected newer data such as account Luck has identity-aware persistence. The current local API key is configured by environment, and the HTTP API has no application authentication. This is not multi-user isolation.

The intended future scope is documented in `TARGET_ARCHITECTURE.md`: account state and credentials become account/user scoped, while TP prices, GW2 metadata, and the crafting graph remain shared. Avoid treating the current schema/key model as a desired hosted model. The exact hosted provider is undecided; Cloudflare-related hosting is only a possible option for later evaluation.

## 8. Startup, configuration, and build

`web.Gw2ApiApplication` starts Spring Boot; `Gw2App` remains the JavaFX entry point. The backend uses `repo.EnvConfig` for local environment and `.env` configuration, and Spring properties expose the HTTP port and global scheduler interval settings. The schema is still created/maintained manually; no versioned migration tool is selected. The system is not containerized or deployed.

Run backend tests with `./mvnw test jacoco:report`; frontend coverage uses `npm run test:coverage` under `frontend/`; the Python agent-runtime suite and coverage use pytest over the existing unittest-compatible suite. `TEST_STRATEGY.md` defines the test layers, local prerequisites, browser smoke checks, CI gate, coverage KPI, and performance acceptance procedure. CI collects JaCoCo, Vitest V8, and coverage.py reports and publishes a combined summary plus raw/HTML artifacts; coverage is warning-only below the 90% target.

## 9. Legacy JavaFX paths

JavaFX views retain legacy controls and presentation workflows, including home-screen setup/account/global actions, per-view timers, and Ectoplasm price refresh. Those controls do not define browser behavior. Browser flows use HTTP task APIs and the automated global scheduler described above. JavaFX-only concurrency and presentation limitations are tracked in `KNOWN_PROBLEMS.md` and should be labeled as legacy if they are carried forward.

## 10. Evidence scope

This map was checked against the Spring controllers, `GlobalDataRefreshScheduler`, `GlobalDataRefreshService`, `RecipeSync`, `AccountRefreshService`, `TradingPostPriceRefreshService`, task/status services, frontend screens and APIs, and project configuration. It summarizes their architecture and does not attempt to enumerate every DTO field, SQL statement, or historical story result; those details belong to their code/tests or canonical story files.
