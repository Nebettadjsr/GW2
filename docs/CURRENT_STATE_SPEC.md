# GW2 Tool — Current State

## Purpose and authority

This is a factual snapshot of the application and developer runtime, checked against the repository implementation on 2026-09-30. It is not the product-rule or target-architecture authority. See [Domain Specification](DOMAIN_SPEC.md) for intended behavior, [Current Architecture](CURRENT_ARCHITECTURE.md) for source structure and flows, [Target Architecture](TARGET_ARCHITECTURE.md) for chosen direction and unresolved decisions, and [Roadmap](ROADMAP.md) for sequencing. Player-facing crafting terminology is mapped in the canonical [Crafting Glossary](crafting/GLOSSARY.md).

## Runtime and technology

The repository currently contains two user interfaces:

- The Vue 3 / TypeScript browser application is the active, canonical product UI, served during development by Vite and backed by the Spring Boot HTTP application.
- The Java 25 / JavaFX desktop UI remains in the source tree as a legacy/local interface. It has not yet been removed and is not the target UI.

The backend is Java 25, Maven and Spring Boot 4.1.1. It owns the HTTP API, application services, the Crafting Profit and Discovery domain calculations, persistence, and Guild Wars 2 API access. PostgreSQL stores shared game/economy data and the current single account's state. The browser does not call ArenaNet directly. Ectoplasm Salvage is a documented exception: its calculation runs in the browser using backend-provided data.

The web application is not yet a hosted multi-user service. There is no account tenancy or application authentication, and the local backend currently obtains one GW2 API key from environment configuration. Account tables are therefore not generally account-keyed. Treat this as a current migration constraint; the future model is documented in [Target Architecture, data scope and identity](TARGET_ARCHITECTURE.md#7-postgresql-and-data-scope).

## Local development and configuration

The backend can be started from the repository root with `./mvnw spring-boot:run` (Windows: `mvnw.cmd spring-boot:run`). It listens on port 8080 by default. Run the browser development server from `frontend/` with `npm ci` and `npm run dev`; Vite uses port 5173 and forwards `/api` requests to the backend.

PostgreSQL must be available and configured using the environment settings in `.env.example`; `GW2_API_KEY` supplies the current local account key. The database schema is not yet managed through a versioned migration tool. This is a development setup, not a packaged deployment. No Docker Compose deployment or hosted runtime is implemented.

The Maven wrapper and `pom.xml` manage Java dependencies. Frontend dependencies use `frontend/package.json` and its lockfile. Main Java code and tests use Maven's `src/main/java` and `src/test/java` layout.

## Current user workflows

The browser navigation includes Crafting Profit, Crafting Discovery, Ectoplasm Salvage, Bank, Materials, and System Status. See [README](../README.md) for a user-oriented overview and [Frontend UX Guidelines](FRONTEND_UX_GUIDELINES.md) for presentation rules.

Crafting Profit provides an explicit **Refresh data & results** action. It refreshes bank, material storage, account recipe knowledge, and character crafting/recipes/inventory through the existing account-refresh service, then recalculates. It does not fetch account Luck for this workflow. The full account refresh remains available for diagnostics in System Status. Normal feature use does not require opening System Status.

Profit and Discovery select their own required Trading Post item IDs but share one persisted price-cache/refresh pipeline. Prices younger than ten minutes are reused; only missing or stale required IDs are requested, in API batches. Feature calculations trigger this on demand; no full TP-price timer is run.

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

Database creation and most schema upkeep still use manually applied SQL scripts; the Luck and account freshness tables have narrow idempotent schema checks. Material Storage tables are also created idempotently by the material sync and read paths. `src/main/resources/db/manual/account_sync_state.sql` and `material_storage.sql` are available for operators applying changes directly. There is no general versioned migration system. Account browser reads use a configurable 15-minute default max age and schedule stale-source refreshes asynchronously; ordinary reads never advance freshness.

The crafting graph is derived from recipe data and shared across the current installation. If its cache is missing, first use creates the baseline graph; scheduled/manual global refresh rebuilds it only after successfully persisted recipe changes. System Status does not currently expose a standalone graph-rebuild action. TP prices and global game metadata are shared data; account records are not yet tenant-scoped.

## Error handling and background work

Long-running web synchronization uses Spring-managed application task submission and task status APIs. Duplicate identical work is prevented within the running backend process. Account freshness checks share the `ACCOUNT_SYNC` operation and reuse an active task. The global scheduler catches/reports failures so a failed execution does not prevent later scheduled runs. This is single-process coordination, not a distributed job system; multi-replica coordination is intentionally future work.

Frontend pages present operation loading, completion, and failure states. Backend task history and refresh timestamps are in memory rather than durable operational telemetry. The legacy JavaFX UI retains its own compatibility workflows and is not the model for web scheduling.

## Build and verification

Backend tests run with `./mvnw test`; JaCoCo reports are generated with `./mvnw test jacoco:report`. Frontend checks are run from `frontend/`: `npm test`, `npm run test:coverage`, `npm run type-check`, and `npm run build`; browser smoke commands are documented in [Test Strategy](TEST_STRATEGY.md). The existing Python agent-runtime unittest suite is also run through pytest-cov for coverage without rewriting its tests. GitHub Actions provides the push/PR CI gate and combined coverage KPI summary. Coverage below the 90% target is reported as a warning, not a build failure. Optional integration and live API checks have prerequisites and are not equivalent to the default deterministic suite.

The separate Python story orchestrator runs Planner, an applicable Architect pass, pre-coding QA, coding, and evaluation. QA is pinned to Codex GPT-6 Luna with medium reasoning effort; Planner, Architect, and Evaluator continue to inherit the configured local Codex model and reasoning effort. Durable QA plans are stored in `agent/qa-plans/`; orchestration state and transient results are stored under ignored `agent/runtime/artifacts/`. The flow and restart behavior are documented in [the runtime guide](../agent/runtime/README.md).

## Current limitations

- One local account/API key; account state and credential handling are not multi-user or tenant-isolated.
- Authentication and browser API-key handling are not implemented.
- PostgreSQL schema setup/upgrades remain manual; a migration tool is undecided.
- The backend/frontend/PostgreSQL stack is not containerized or deployed.
- JavaFX remains in the repository while the browser migration and acceptance work continue.
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
