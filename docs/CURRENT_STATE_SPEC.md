# GW2 Tool — Current State

## Purpose and authority

This runtime snapshot was updated for scoped account recovery and the container runtime on 2026-10-25. DOMAIN_SPEC owns product rules; CURRENT_ARCHITECTURE describes source boundaries; TARGET_ARCHITECTURE records selected design; ROADMAP owns sequencing.

## Runtime and technology

The repository contains one user interface:

- The Vue 3 / TypeScript browser application is the active, canonical and only product UI, served during development by Vite and backed by the Spring Boot HTTP application.
- The Java 25 / JavaFX desktop UI has been removed: its entry point, views and stylesheet, the application and utility classes those views were the only caller of, and its 31 desktop-only test and fixture files (`STORY-UI-004`). Its build and test infrastructure (the `org.openjfx` dependencies and plugin, the `javafx.*` and `main.class` properties, the TestFX dependency and the CI `javafx.platform` flag) was removed by `STORY-INFRA-005`; see [Current Architecture, section 9](CURRENT_ARCHITECTURE.md).

The backend is Java 25, Maven and Spring Boot 4.1.1. It owns HTTP, application services, Profit/Discovery domain calculations, persistence and ArenaNet access. PostgreSQL stores shared game/economy data and GUID-scoped account data. The browser does not call ArenaNet directly. Ectoplasm Salvage calculates in the browser using backend-provided data.

The app is not hosted yet. Account HTTP workflows now validate browser-supplied GW2 keys and carry verified stable account GUIDs through persistence, calculations and asynchronous work. Replacement keys reuse the same GUID dataset. There is no separate app login; low-risk read-only key handling follows UD-018.

## Local development and configuration

The backend can be started from `backend/` with `./mvnw spring-boot:run` (Windows: `mvnw.cmd spring-boot:run`); `repo.EnvConfig` reads `.env` from the working directory and, when run from `backend/` with no `.env` there, from the repository root. IDE run configurations use `backend/` as the working directory. It listens on port 8080 by default. Run the browser development server from `frontend/` with `npm ci` and `npm run dev`; Vite uses port 5173 and forwards `/api` requests to the backend.

Configure PostgreSQL through the environment settings in .env.example. Enter the read-only GW2 key in the connection panel on the System Status page; sessionStorage retains it across navigation/reload, and the backend receives it transiently for proof/upstream work. Local GW2_API_KEY configuration may remain for retained entry points, but scoped HTTP routes never fall back to it. Flyway owns schema lifecycle; disposable cached data is reset/re-fetched under UD-016. Vite preserves the browser host through its proxy for normal same-origin checks. `docker compose up --build` starts the local frontend/backend/PostgreSQL stack with its own database and named cache volumes (`CURRENT_ARCHITECTURE.md`, Docker Compose stack); there is no hosted deployment.

The Maven wrapper and `backend/pom.xml` manage Java dependencies. Frontend dependencies use `frontend/package.json` and its lockfile. Main Java code and tests use Maven's `backend/src/main/java` and `backend/src/test/java` layout. Repository layout: `backend/` (Maven module: `pom.xml`, `mvnw`, `.mvn`, `src`), `frontend/` (Vue app), `docker/` (startup README, canonical `compose.yaml`, optional configuration example, image definitions under `backend/` and `frontend/`), `scripts/` (container smokes), `docs/`.

## Current user workflows

The browser navigation includes Crafting Profit, Crafting Discovery, Ectoplasm Salvage, Bank, Materials, and System Status. See [README](../README.md) for a user-oriented overview and [Frontend UX Guidelines](FRONTEND_UX_GUIDELINES.md) for presentation rules.

Crafting Profit provides an explicit **Refresh data & results** action. It requests the calculation again and starts no synchronization itself: the backend answers from its last successfully synchronized inputs and admits the account and Trading Post refresh those inputs still need, so bank, material storage, account recipe knowledge and character crafting/recipes/inventory are what get refreshed, and account Luck is not fetched for this workflow. The full account refresh remains available for diagnostics in System Status. Normal feature use does not require opening System Status.

Profit and Discovery select their own required Trading Post item IDs but share one persisted price-cache/refresh pipeline. Prices younger than ten minutes are reused; only missing or stale required IDs are requested, in API batches. Feature calculations trigger this on demand; no full TP-price timer is run.

Owned-input valuation uses the domain fallback rules when the selected Trading Post side has no usable price. Item reads include vendor values; the same values reach row calculations and resolution details. The calculation row's `unvaluedOwnedInput` flag identifies a consumed input for which no market, crafting or vendor value could be established. Crafting Profit shows a warning in its table and selected detail so an unknown input counted at zero is not presented as a reliable profit. This flag is separate from the freshness envelope: freshly fetched data can still contain an unvalued item.

Normal global item synchronization retains canonical icon URLs. The account refresh gateway also repairs missing or invalid icon metadata for newly fetched Bank, Materials and character-item IDs before publishing their account generation, outside the account transaction. Metadata failures leave ordinary image fallbacks and can be retried by a later synchronization; they do not discard account data. The browser obtains image bytes from the backend's on-demand cache rather than calling ArenaNet directly.

A Crafting Profit calculation is answered from the inputs that last synchronized successfully and does not wait for account synchronization or a price fetch. Expired account data or expired quotes still produce the ordinary result, marked provisional, together with the refresh work that was admitted for them; the result becomes final only once the required sources and the required item set are confirmed current. When no required account source has ever synchronized, the calculation is reported as pending with no rows rather than as an empty result. A selected row's detail says whether it still explains the inputs that row was calculated from, and always carries the row recalculated from the inputs it did use.

The browser presents that contract without deciding anything about freshness. A provisional result is displayed immediately as the ordinary result, with a **"Stale data – refreshing…"** label in the Trading Post warning's compact bordered form, a green continuously cycling border, an explanation that the cached results stay until the refresh finishes, and the backend's own per-source account states and Trading Post coverage counts shown as two separate facts. A pending answer shows that nothing has been calculated yet and lists no row. While the label is up, the page polls only the task locations the response advertised and asks for the calculation again after each completion, because a succeeded task is not evidence that every required source is fresh; the label disappears only when a calculation for the current scope, settings and context comes back final. Settings, search, display filters, sorting and a still-present row selection survive each replacement, and a selection the replacement no longer offers falls back to the empty detail state. A detail answered from inputs newer than the displayed row shows that answer's own row and values, and says why, rather than explaining the older row. Automatic recovery is bounded to five minutes in total, including polling, successive tasks and recalculation; a failed, unknown or expired task, a transport failure, a response reporting a failure, and the deadline each stop the animation, retain the last usable rows and offer a retry with the backend's own reason. The deadline also ends recovery while a request has not answered at all — a stalled status read or recalculation stops the animation at five minutes like any other outcome, and its answer, if it arrives later, no longer changes what is displayed. Cached table/detail requests use the live account context without a raw key. If the backend reports CREDENTIAL_REQUIRED and a key is retained, the browser admits one explicit four-source account refresh within the same recovery budget, follows its advertised task and recalculates keylessly. A transient proof/admission failure keeps the cached view and key. Retry asks for the calculation again and can admit the needed keyed refresh within its own five-minute budget.

Both crafting result tables search output names, direct ingredient names and existing row fields locally. A 250 ms debounced request obtains indirect parent recipe IDs from the shared cached reverse graph; only IDs are returned, and the frontend intersects them with already-calculated rows. The graph and referenced item names are held in the shared in-process cache and persisted catalog file. Search does not receive dependency paths or complete crafting trees and does not alter resolver values or acquisition choices.

Global recipe metadata and TP-tradeable IDs are checked automatically. The default schedule is a six-hour fixed delay with a one-minute initial delay, configurable as `GW2_GLOBAL_REFRESH_INTERVAL_MS` and `GW2_GLOBAL_REFRESH_INITIAL_DELAY_MS`. The service fetches cheap ID lists first and skips detail work when IDs are unchanged. This detects recipe additions/removals but not an in-place recipe edit under an unchanged ID set. When the set changes, detail data is persisted before the crafting graph is rebuilt; unchanged recipe IDs do not trigger detail sync or a refresh-time graph rebuild. A missing graph cache is initialized on first use. The scheduled and manual System Status action use the same refresh service. In-process duplicate-task prevention and task/timestamp/error status are process-local and reset on restart.

System Status is an administrative diagnostics and maintenance page. It shows current account refresh status/time, shared price-cache freshness, global check/change and recipe/graph timestamps, task state and the last global failure when available. It offers full account refresh and global-data check actions. It is not required for routine feature workflows and is not a general monitoring system.

## Persistence and generated data

The current PostgreSQL schema has shared item and recipe facts, TP tradeable IDs and cached quotes, plus account/character facts for bank, material storage, recipes, crafting capabilities, character inventory and consumed Luck. Principal tables are:

| Scope | Tables | Contents |
| --- | --- | --- |
| Shared metadata | items, recipes, recipe_ingredients | Item/recipe facts and crafting graph inputs. |
| Shared Trading Post | tp_tradeable_items, tp_prices | Tradeable IDs and quotes with timestamps. |
| Account identity/activity | account_registry | Verified stable GUID and monotonic activity. |
| Scoped account data | account_bank, account_materials, account_recipes, account_luck | Re-fetchable data owned by the verified account GUID. |
| Scoped characters | characters, character_crafting, character_recipes, character_items | Roster, crafting, recipes and inventories owned by that GUID. |
| Scoped freshness | account_source_state | Per-account/source publication generation and completion time, including empty successful results. |

Flyway alone writes the managed schema. The disabled legacy account_sync_state path is absent from scoped HTTP workflows. A reset discards cached data; verified synchronization repopulates it (UD-016). Missing markers are distinct from successful empty data. Reads report missing/stale state without inventing freshness or using another configured account. Publication is atomic per source; Profit captures scoped inputs in a repeatable-read snapshot.

The graph is derived from shared recipes, initialized on first use if absent and rebuilt after persisted global recipe changes. System Status exposes global maintenance and scoped account refresh. Graph/Trading Post caches are shared rather than copied per account.

## Error handling and background work

Long-running web synchronization uses Spring-managed application task submission and task status APIs. Duplicate identical work is prevented within the running backend process. Account freshness checks share the `ACCOUNT_SYNC` operation; an active run is reused only for the sources it is actually refreshing, and sources it omits stay outstanding until a run covers them, so a narrower active task is never reported as having refreshed them. Account writes remain non-overlapping, and the shared price refresh is coordinated separately. The global scheduler catches/reports failures so a failed execution does not prevent later scheduled runs. This is single-process coordination, not a distributed job system; multi-replica coordination is intentionally future work.

Frontend pages present operation loading, completion, and failure states. Backend task history and refresh timestamps are in memory rather than durable operational telemetry.

## Build and verification

Backend tests run with `./mvnw test` in `backend/`; JaCoCo reports are generated with `./mvnw test jacoco:report` (output under `backend/target/site/jacoco`). Frontend checks are run from `frontend/`: `npm test`, `npm run test:coverage`, `npm run type-check`, and `npm run build`; browser smoke commands are documented in [Test Strategy](TEST_STRATEGY.md). GitHub Actions provides the push/PR CI gate and combined Java/frontend coverage KPI summary. Coverage below the 90% target is reported as a warning, not a build failure. Optional integration and live API checks have prerequisites and are not equivalent to the default deterministic suite.

Development uses GitHub issues, PRs, Maven/Vitest CI and native Codex review. Each explicit Claude workflow dispatch selects one ready auto issue; the normal turn limit is 40 (configurable) and the job bound is 45 minutes. An authorized supervisor may dispatch/review subsequent runs. There is no usage-percentage gate or independent background supervisor. Small nonblocking follow-ups may be recorded by review. The retired Python service is recoverable at pre-github-flow.

The informational Performance workflow publishes separate application-process and three-container resource reports, including CPU/memory observations, HTTP/database figures, startup and image sizes where measured. Failures and unavailable metrics remain visible and nonblocking; container global-read samples do not establish crafting capacity. [Performance Measurements](PERFORMANCE_MEASUREMENTS.md) owns invocation, units, workload versions and comparison limits; GitHub run artifacts hold the measurements.

## Current limitations

- Account datasets are GUID-scoped. One account is active per shared-cookie browser session; independent accounts in such tabs are not promised.
- The connection panel (shown only on System Status, which stays reachable while disconnected and on direct URL/reload; other pages show a concise link to it) retains the key in sessionStorage, proves it before loading account data, and renews access without repeated entry. Invalid keys are removed; transient failures retain the key for retry.
- Scoped HTTP workflows now operate on the migrated schema; legacy unscoped account access stays refused. Local Chrome/live-ArenaNet recovery evidence covers completed full synchronization, selector options, loaded Profit, Bank, Materials, reload retention and disconnect. This does not certify every feature or establish a hosted deployment.
- A Java 25 backend image and a static Nginx frontend image with same-origin `/api` forwarding, each with an isolated Docker smoke, are available (`CURRENT_ARCHITECTURE.md`, container image sections). A local three-container Docker Compose stack joins them (frontend is the only published port, `bash scripts/compose-smoke.sh` verifies it offline). Public TLS and hosted deployment are not delivered.
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
