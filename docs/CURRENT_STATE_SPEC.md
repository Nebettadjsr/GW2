# GW2 Tool — Current State

## Purpose and authority

This is a factual snapshot of the application and developer runtime, checked against the repository implementation on 2026-09-30. It is not the product-rule or target-architecture authority. See [Domain Specification](DOMAIN_SPEC.md) for intended behavior, [Current Architecture](CURRENT_ARCHITECTURE.md) for source structure and flows, [Target Architecture](TARGET_ARCHITECTURE.md) for chosen direction and unresolved decisions, and [Roadmap](ROADMAP.md) for sequencing. Player-facing crafting terminology is mapped in the canonical [Crafting Glossary](crafting/GLOSSARY.md).

## Runtime and technology

The repository contains one user interface:

- The Vue 3 / TypeScript browser application is the active, canonical and only product UI, served during development by Vite and backed by the Spring Boot HTTP application.
- The Java 25 / JavaFX desktop UI has been removed: its entry point, views and stylesheet, the application and utility classes those views were the only caller of, and its 31 desktop-only test and fixture files (`STORY-UI-004`). Its build and test infrastructure (the `org.openjfx` dependencies and plugin, the `javafx.*` and `main.class` properties, the TestFX dependency and the CI `javafx.platform` flag) was removed by `STORY-INFRA-005`; see [Current Architecture, section 9](CURRENT_ARCHITECTURE.md).

The backend is Java 25, Maven and Spring Boot 4.1.1. It owns the HTTP API, application services, the Crafting Profit and Discovery domain calculations, persistence, and Guild Wars 2 API access. PostgreSQL stores shared game/economy data and the current single account's state. The browser does not call ArenaNet directly. Ectoplasm Salvage is a documented exception: its calculation runs in the browser using backend-provided data.

The web application is not yet a hosted multi-user service. There is no account tenancy or application authentication, and the local backend currently obtains one GW2 API key from environment configuration. Account tables are therefore not generally account-keyed. Treat this as a current migration constraint; the future model is documented in [Target Architecture, data scope and identity](TARGET_ARCHITECTURE.md#7-postgresql-and-data-scope).

## Local development and configuration

The backend can be started from the repository root with `./mvnw spring-boot:run` (Windows: `mvnw.cmd spring-boot:run`). It listens on port 8080 by default. Run the browser development server from `frontend/` with `npm ci` and `npm run dev`; Vite uses port 5173 and forwards `/api` requests to the backend.

PostgreSQL must be available and configured using the environment settings in `.env.example`; `GW2_API_KEY` supplies the current local account key. The database schema is created and upgraded by the versioned Flyway chain the startup schema gate applies; starting any entry point against an empty database is enough to create it, and against a legacy or unrecognized schema the gate resets the cache first. This is a development setup, not a packaged deployment. No Docker Compose deployment or hosted runtime is implemented.

The Maven wrapper and `pom.xml` manage Java dependencies. Frontend dependencies use `frontend/package.json` and its lockfile. Main Java code and tests use Maven's `src/main/java` and `src/test/java` layout.

## Current user workflows

The browser navigation includes Crafting Profit, Crafting Discovery, Ectoplasm Salvage, Bank, Materials, and System Status. See [README](../README.md) for a user-oriented overview and [Frontend UX Guidelines](FRONTEND_UX_GUIDELINES.md) for presentation rules.

Crafting Profit provides an explicit **Refresh data & results** action. It requests the calculation again and starts no synchronization itself: the backend answers from its last successfully synchronized inputs and admits the account and Trading Post refresh those inputs still need, so bank, material storage, account recipe knowledge and character crafting/recipes/inventory are what get refreshed, and account Luck is not fetched for this workflow. The full account refresh remains available for diagnostics in System Status. Normal feature use does not require opening System Status.

Profit and Discovery select their own required Trading Post item IDs but share one persisted price-cache/refresh pipeline. Prices younger than ten minutes are reused; only missing or stale required IDs are requested, in API batches. Feature calculations trigger this on demand; no full TP-price timer is run.

A Crafting Profit calculation is answered from the inputs that last synchronized successfully and does not wait for account synchronization or a price fetch. Expired account data or expired quotes still produce the ordinary result, marked provisional, together with the refresh work that was admitted for them; the result becomes final only once the required sources and the required item set are confirmed current. When no required account source has ever synchronized, the calculation is reported as pending with no rows rather than as an empty result. A selected row's detail says whether it still explains the inputs that row was calculated from, and always carries the row recalculated from the inputs it did use.

The browser presents that contract without deciding anything about freshness. A provisional result is displayed immediately as the ordinary result, with a **"Stale data – refreshing…"** label in the Trading Post warning's compact bordered form, a green continuously cycling border, an explanation that the cached results stay until the refresh finishes, and the backend's own per-source account states and Trading Post coverage counts shown as two separate facts. A pending answer shows that nothing has been calculated yet and lists no row. While the label is up, the page polls only the task locations the response advertised and asks for the calculation again after each completion, because a succeeded task is not evidence that every required source is fresh; the label disappears only when a calculation for the current scope, settings and context comes back final. Settings, search, display filters, sorting and a still-present row selection survive each replacement, and a selection the replacement no longer offers falls back to the empty detail state. A detail answered from inputs newer than the displayed row shows that answer's own row and values, and says why, rather than explaining the older row. Automatic recovery is bounded to five minutes in total, including polling, successive tasks and recalculation; a failed, unknown or expired task, a transport failure, a response reporting a failure, and the deadline each stop the animation, retain the last usable rows and offer a retry with the backend's own reason. The deadline also ends recovery while a request has not answered at all — a stalled status read or recalculation stops the animation at five minutes like any other outcome, and its answer, if it arrives later, no longer changes what is displayed. The retry asks for the calculation again, which rechecks freshness, is bounded by its own five minutes, and starts no refresh of its own.

Both crafting result tables search output names, direct ingredient names and existing row fields locally. A 250 ms debounced request obtains indirect parent recipe IDs from the shared cached reverse graph; only IDs are returned, and the frontend intersects them with already-calculated rows. The graph and referenced item names are held in the shared in-process cache and persisted catalog file. Search does not receive dependency paths or complete crafting trees and does not alter resolver values or acquisition choices.

Global recipe metadata and TP-tradeable IDs are checked automatically. The default schedule is a six-hour fixed delay with a one-minute initial delay, configurable as `GW2_GLOBAL_REFRESH_INTERVAL_MS` and `GW2_GLOBAL_REFRESH_INITIAL_DELAY_MS`. The service fetches cheap ID lists first and skips detail work when IDs are unchanged. This detects recipe additions/removals but not an in-place recipe edit under an unchanged ID set. When the set changes, detail data is persisted before the crafting graph is rebuilt; unchanged recipe IDs do not trigger detail sync or a refresh-time graph rebuild. A missing graph cache is initialized on first use. The scheduled and manual System Status action use the same refresh service. In-process duplicate-task prevention and task/timestamp/error status are process-local and reset on restart.

System Status is an administrative diagnostics and maintenance page. It shows current account refresh status/time, shared price-cache freshness, global check/change and recipe/graph timestamps, task state and the last global failure when available. It offers full account refresh and global-data check actions. It is not required for routine feature workflows and is not a general monitoring system.

## Persistence and generated data

The current PostgreSQL schema has shared item and recipe facts, TP tradeable IDs and cached quotes, plus account/character facts for bank, material storage, recipes, crafting capabilities, character inventory and consumed Luck. Principal tables are:

| Scope | Tables | Contents |
| --- | --- | --- |
| Shared GW2 metadata | `items`, `recipes`, `recipe_ingredients` | Item metadata, recipe outputs and recipe ingredients. |
| Shared Trading Post cache | `tp_tradeable_items`, `tp_prices` | Tradeable item IDs and buy/sell quote quantities, unit prices and `fetched_at`. |
| Current account | `account_bank`, `account_materials`, `account_recipes` | Bank slots, material storage, and account recipe unlocks. |
| Current characters | `characters`, `character_crafting`, `character_recipes`, `character_items` | Character identity and crafting levels, recipe knowledge, bags/equipment. |
| Identity-aware account data | `account_luck` | Consumed Luck keyed by stable GW2 account GUID; this is an exception to older single-account tables. |
| Account source freshness | `account_sync_state` | Successful completion time for each synchronized account source, including sources with empty API results. |

The table list above is the unmigrated schema the retained runtime paths read and write. Database creation and upgrades are now owned by a versioned Flyway chain applied by a startup schema gate that every retained entry point runs before any repository, task or scheduler: an empty schema receives the complete chain, a managed schema is validated and brought forward, and a legacy or unrecognized schema is reset — its cached tables dropped and the chain applied to the emptied schema — because the database is a disposable cache of GW2 API data. A migrated database has the GUID-scoped account schema instead of the unscoped tables above: `account_registry` and `account_source_state` replace the identity-less account keys and the singleton `account_sync_state` marker, every account-owned row carries the verified account GUID, and the remaining unscoped paths are refused there rather than served. After a reset every account source is missing until fresh synchronization; the scoped repositories and publication that make a migrated database usable for account data are not implemented yet. The legacy idempotent schema checks remain in place for an unmigrated database, and `src/main/resources/db/manual/account_sync_state.sql` and `material_storage.sql` stay available for operators of one. Account browser reads use a configurable 15-minute default max age and schedule stale-source refreshes asynchronously; ordinary reads never advance freshness.

The crafting graph is derived from recipe data and shared across the current installation. If its cache is missing, first use creates the baseline graph; scheduled/manual global refresh rebuilds it only after successfully persisted recipe changes. System Status does not currently expose a standalone graph-rebuild action. TP prices and global game metadata are shared data; account records are not yet tenant-scoped.

## Error handling and background work

Long-running web synchronization uses Spring-managed application task submission and task status APIs. Duplicate identical work is prevented within the running backend process. Account freshness checks share the `ACCOUNT_SYNC` operation; an active run is reused only for the sources it is actually refreshing, and sources it omits stay outstanding until a run covers them, so a narrower active task is never reported as having refreshed them. Account writes remain non-overlapping, and the shared price refresh is coordinated separately. The global scheduler catches/reports failures so a failed execution does not prevent later scheduled runs. This is single-process coordination, not a distributed job system; multi-replica coordination is intentionally future work.

Frontend pages present operation loading, completion, and failure states. Backend task history and refresh timestamps are in memory rather than durable operational telemetry.

## Build and verification

Backend tests run with `./mvnw test`; JaCoCo reports are generated with `./mvnw test jacoco:report`. Frontend checks are run from `frontend/`: `npm test`, `npm run test:coverage`, `npm run type-check`, and `npm run build`; browser smoke commands are documented in [Test Strategy](TEST_STRATEGY.md). GitHub Actions provides the push/PR CI gate and combined Java/frontend coverage KPI summary. Coverage below the 90% target is reported as a warning, not a build failure. Optional integration and live API checks have prerequisites and are not equivalent to the default deterministic suite.

The current development workflow uses GitHub issues, branches, pull requests, CI, and native Codex review. The owner manually starts the Claude Action for one ready `auto` issue; the workflow limits one run to 12 turns and 45 minutes. It has no account-usage percentage gate, so the owner checks subscription capacity before triggering it. Pairing issues use local sessions. The old Python workflow service and its state are retired and recoverable from the `pre-github-flow` tag.

## Current limitations

- One local account/API key; account state and credential handling are not multi-user or tenant-isolated.
- Authentication and browser API-key handling are not implemented.
- A migrated database serves global data only: the browser credential flow that would resolve a session into an account scope is not implemented, so the retained unscoped account paths are refused there. The global workflows themselves do run: icon-metadata selection and the Profit/Discovery price refresh each have a global half that reads only shared items, recipes, the material catalog and the shared quote cache, and the unscoped bank read a global path passes through returns no rows rather than refusing. Account-derived inputs require an explicit scope and no default account is inferred, so bank, materials, characters, Crafting Profit, Discovery and Luck stay unavailable until that flow lands.
- The backend/frontend/PostgreSQL stack is not containerized or deployed.
- Refresh status is process-local, not durable telemetry. Multiple backend replicas would require shared coordination for global scheduled work and refresh deduplication.
- Hosting provider is undecided. Cloudflare-related infrastructure is only a possible future option, not a decision.

## Companion documents

- [Domain Specification](DOMAIN_SPEC.md): normative product and calculation rules.
- [Current Architecture](CURRENT_ARCHITECTURE.md): observed component boundaries and major flows.
- [Target Architecture](TARGET_ARCHITECTURE.md): intentional design, decisions, and TBD items.
- [Roadmap](ROADMAP.md): active phase, planned sequence, and exit criteria.
- [Known Problems](KNOWN_PROBLEMS.md): confirmed open defects and risks.
- [Test Strategy](TEST_STRATEGY.md): verification layers and procedures.
- [Crafting Glossary](crafting/GLOSSARY.md): player-facing terms and UI-label mapping.
