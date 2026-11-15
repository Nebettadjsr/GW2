# GW2 Tool — Current State

## Purpose and authority

This runtime snapshot includes the Product Owner-approved UI and scoped refresh implementation, including the System Status owner and regular-user layouts accepted through 2026-11-12. A described implementation is not a claim that every acceptance gate has passed; consult the relevant GitHub issue/PR evidence. DOMAIN_SPEC owns product rules; CURRENT_ARCHITECTURE describes source boundaries; TARGET_ARCHITECTURE records selected design; ROADMAP owns sequencing.

## Runtime and technology

The repository contains one user interface:

- The Vue 3 / TypeScript browser application is the active, canonical and only product UI, served during development by Vite and backed by the Spring Boot HTTP application.
- The Java 25 / JavaFX desktop UI has been removed: its entry point, views and stylesheet, the application and utility classes those views were the only caller of, and its 31 desktop-only test and fixture files (`STORY-UI-004`). Its build and test infrastructure (the `org.openjfx` dependencies and plugin, the `javafx.*` and `main.class` properties, the TestFX dependency and the CI `javafx.platform` flag) was removed by `STORY-INFRA-005`; see [Current Architecture, section 9](CURRENT_ARCHITECTURE.md).

The backend is Java 25, Maven and Spring Boot 4.1.1. It owns HTTP, application services, Profit/Discovery domain calculations, persistence and ArenaNet access. PostgreSQL stores shared game/economy data and GUID-scoped account data. The browser does not call ArenaNet directly. Ectoplasm Salvage calculates in the browser using backend-provided data.

The app is publicly hosted on Oracle, as described below. Account HTTP workflows now validate browser-supplied GW2 keys and carry verified stable account GUIDs through persistence, calculations and asynchronous work. Replacement keys reuse the same GUID dataset. There is no separate app login; low-risk read-only key handling follows UD-018.

## Local development and configuration

The backend can be started from `backend/` with `./mvnw spring-boot:run` (Windows: `mvnw.cmd spring-boot:run`); `repo.EnvConfig` reads `.env` from the working directory and, when run from `backend/` with no `.env` there, from the repository root. IDE run configurations use `backend/` as the working directory. It listens on port 8080 by default. Run the browser development server from `frontend/` with `npm ci` and `npm run dev`; Vite uses port 5173 and forwards `/api` requests to the backend.

Configure PostgreSQL through the environment settings in .env.example. Enter the read-only GW2 key in the connection panel on the System Status page; sessionStorage retains it across navigation/reload, and the backend receives it transiently for proof/upstream work. Local GW2_API_KEY configuration may remain for retained entry points, but scoped HTTP routes never fall back to it. Flyway owns schema lifecycle; disposable cached data is reset/re-fetched under UD-016. Vite preserves the browser host through its proxy for normal same-origin checks. `docker compose up --build` starts the local frontend/backend/PostgreSQL stack with its own database and named cache volumes (`CURRENT_ARCHITECTURE.md`, Docker Compose stack).

Enable owner controls by setting `GW2_OWNER_ACCOUNT_GUID` in the **backend process environment**, or the Spring property `gw2.owner.account-guid`, to the account GUID verified by the backend's normal key proof. No special admin API key is needed. Unset/blank grants nobody; malformed nonblank values fail startup. Restart the backend after changing this setting. `repo.EnvConfig` reads database/local-entry settings from `.env`; it does not inject that file into Spring's environment, so adding the owner variable only to `.env` does not enable the owner policy. Keep real GUIDs in local/deployment configuration, never committed examples. Both Compose files forward `GW2_OWNER_ACCOUNT_GUID` from the stack environment to the backend; `docker/compose.production.yaml` additionally requires a database password. The public Oracle TLS deployment below uses a required protected host override.

The Maven wrapper and `backend/pom.xml` manage Java dependencies. Frontend dependencies use `frontend/package.json` and its lockfile. Main Java code and tests use Maven's `backend/src/main/java` and `backend/src/test/java` layout. Repository layout: `backend/` (Maven module and its Dockerfile/ignore rules), `frontend/` (Vue app, its Dockerfile/ignore rules and Nginx template), `docker/` (startup README, canonical `compose.yaml` and optional shared configuration example), `scripts/` (container smokes), `docs/`.

## Public Oracle deployment (2026-11-15)

The owner approved publication of the current UI and calculation fix, then explicitly authorized
general public access. The live release is `40007cfa250c89434b0d35be8c178517d4110bd5`, from merged
[#222](https://github.com/Nebettadjsr/GW2/pull/222),
[#226](https://github.com/Nebettadjsr/GW2/pull/226) and
[#224](https://github.com/Nebettadjsr/GW2/pull/224), followed by the explicitly approved
[#192](https://github.com/Nebettadjsr/GW2/pull/192), subsequent maintenance and the additive cached
Bank/Materials item-name foundation [#234](https://github.com/Nebettadjsr/GW2/pull/234). Its tree
exactly matches reviewed/CI candidate `97ad46f9910b015f2d5036a59488f2dfdd95f488`; independent
local QA passed all 67 focused checks. Build, activation and public-access verification ran in
the visible Oracle SSH terminal; all three containers are healthy. The backend was replaced;
the unchanged frontend kept running. The new tile/reservation UI is a separate local-review
draft and is not included in this deployment.

The host is Oracle Frankfurt AD 2, Ubuntu 24.04 ARM64, 2 OCPUs and 12 GB RAM, at
`130.61.78.114`. Docker 29.9.3 / Compose 5.7.1 run frontend, backend and PostgreSQL 17.
Only the frontend publishes a host port, `127.0.0.1:8088`; backend and PostgreSQL publish none.
The database container was not recreated for this release. `/opt/gw2/current` selects
`/opt/gw2/releases/40007cfa250c89434b0d35be8c178517d4110bd5`.
Private deployment inputs remain outside the repository.

### Public Home and crawler boundary

[Welcome](https://gw2-crafting.de/welcome) is the default public entry. The approved Home has
Welcome, What you can do, Getting started and New to Guild Wars 2? tabs, plus the owner's
beginner chapters and compressed local WebP screenshots. Images have responsive variants,
lazy loading and larger-image links. Home and public information pages never restore a retained
key or request account data. Tool bookmarks retain their `/#/...` URLs. Skip-link focus and
Back/Forward navigation are covered by independent regressions.

Vite builds separate `index.html` and `welcome.html` entries. The frontend and host edge allow
`index, follow` only for successful canonical `/welcome` responses (200/206/304); errors,
redirects, the shared root/tool document, legal/privacy/storage, APIs and assets retain
`noindex, nofollow`. `/welcome.html` redirects to `/welcome`. The public plain-text
`robots.txt` allows `/welcome$` and `/assets/`, then disallows everything else. There is no
sitemap. This supersedes the former site-wide crawl block. Robots directives guide cooperating
crawlers; they do not guarantee that a discovered URL never appears in search.

### HTTPS, authorization and database boundary

Host Nginx 1.24 and distro Certbot 2.9 terminate TLS for the apex and www. HTTP and www redirect
to canonical HTTPS, preserving path/query; unknown TLS names are rejected. The certificate
expires 2027-02-13; the timer and successful-renewal reload hook were previously verified with
a dry run. HSTS preloading is not enabled. The old owner-source allowlist is removed.

The edge preserves browser Host, strips client forwarding headers and rejects unsafe requests
unless Origin is exactly `https://gw2-crafting.de`. Missing, foreign and plaintext Origins
returned 403; canonical owner operations reached normal authentication. Account and admin reads
without a session returned 401. A real owner-key HTTPS proof through ordinary DNS passed:
proof 200, owner details 200, logout 204, then owner read 401. The opaque cookie remains
Secure, HttpOnly, SameSite=Strict and host-only. Keys, cookies and access contexts were not
written to verification artifacts.

Every host Compose invocation must include both the release's `docker/compose.production.yaml`
and root-owned `/opt/gw2/config/https.compose.yaml`, using the root-owned mode-0600
`/opt/gw2/config/production.env`. The override forwards the exact HTTPS origin and supplies
the separate `gw2_app` database credential. That role has required schema/Flyway rights but
no superuser/create-role/create-database/replication/bypass-RLS flags. The bootstrap role's
network password is disabled. The frontend has no database credential. Public-release checks
verified the actual role flags, distinct running credential and port bindings.
Portable configuration follow-up is [#228](https://github.com/Nebettadjsr/GW2/issues/228);
the original production file alone is not sufficient for this host.

The inspected host uses key-only SSH. No privileged container, Docker-socket mount or Docker
TCP listener was observed. PostgreSQL uses SCRAM on the internal Docker network; internal DB TLS
is off. This is a bounded deployment check, not an exhaustive security or cross-account audit.

### Information pages, processing and logs

The footer links **Legal notice** (`#/legal`), **Privacy** (`#/privacy`) and **Cookies & browser
storage** (`#/storage`) outside the main navigation. These pages are publicly hosted. Opening one
does not restore a retained key or request account data; an existing connected session and cached
application views remain intact. Public operator facts come from optional `VITE_OPERATOR_*`
configuration; an unconfigured local preview shows its missing-facts notice. The live release has
the owner-approved facts and copy. Crawler policy is described above; these information pages
retain noindex.

The owner approved the legal/privacy/browser-storage copy and supplied all six public deployment
facts; they were verified in the actual served bundle. The operator is a private individual
in Germany; the host is in Frankfurt. The working public contact is `kontakt@gw2-crafting.de`.
Actual address/configuration values stay in ignored local and protected host deployment files,
rather than tracked examples or issue comments. Required information is publicly readable in
the legal page. The tool identifies itself as an unofficial fan project, includes ArenaNet
attribution, and links its public source, MIT licence and feedback channels.

There is no separate registration name/email/password. Stable verified GUIDs, character names,
game data, sessions and request activity are account-linked, not anonymous. Network delivery
also processes client IP addresses. The key remains in per-tab sessionStorage and is used
transiently by the backend; normal cookie/local-storage behavior and actual deletion are
described in the information pages and account sections below. No analytics/advertising
feature was added. Publication approval does not establish executed provider agreements or
a legal certification.

Application operations history remains sparse, aggregate and owner-authorized, with day/week
file selection and download. It keeps 30 nonempty UTC day files subject to byte caps, not
30-calendar-day expiry; idle time writes no empty history. Host CPU/RAM belongs to Oracle
Monitoring. Docker logs are bounded by three 10 MB files per container. Query-free edge access
logs contain timestamp/method/path/status/size/time without account credentials or client IP;
raw request-bound error logs are suppressed. Edge logrotate retains three rotations, checking
daily with a 10 MB threshold; Certbot retains five rotated logs plus current. These are
bounds, not a universal time-based deletion guarantee.

### Release verification and limits

Independent regression QA passed 342 relevant backend tests across 41 suites, including
29 material/arithmetic bounds cases; every review finding has red/green evidence. Default
backend/frontend/workflow CI and exact-head native review passed before merging.
Frontend full-suite evidence on the Home proposal passed 828 tests before the two additional
skip/history regressions; final required CI includes those regressions and type/build checks.

The public hosted stack passed the regular visible Chromium check across all eight destinations:
masked empty key entry and enabled Connect, no repeated key form, no browser errors, no 5xx,
and zero API calls before connection. [#220](https://github.com/Nebettadjsr/GW2/issues/220) is
closed. A formerly non-allowlisted bridge client now receives 200 for Welcome. Live verification
covered header/error/Origin rules, redirects, all operator facts and role/port boundaries.
The first immediate post-reload check saw an old Nginx worker and restored private access;
waiting for the new worker's policy resolved that readiness race, and the complete rerun passed.

Optional Profit/Discovery fixture-bootstrap maintenance
[#194/#231](https://github.com/Nebettadjsr/GW2/pull/231) has passed locally: 57 focused tests,
type/build checks, and visible controlled-browser Profit 36 stages / Discovery 15 stages.
The final source proves the retained synthetic key through normal App startup after each document
load, checks context headers and strict routes/methods, and blocks off-origin requests. Search awaits
the supported cached lookup without recalculating or losing the selected detail; fixtures match
current counted-output and output-batch contracts. Existing supplied values, recursive trees,
keyboard/selection/layout and no-sync checks remain. These are synthetic presentation/interaction
results, not live-account or backend calculation evidence. Exact-head CI and native review passed;
the test-only repair is merged. It does not change the deployed application revision above.
The owner approved the two-line value display [#192](https://github.com/Nebettadjsr/GW2/pull/192),
which is now merged and hosted. The backend projects the display-only unit average while preserving
the inclusive total; both values appear on separate lines. Its synchronized source passed 79 focused
backend integration checks, 60 focused Vue tests, production build and exact-head CI/review.
Hosted public/security, served-label, browser navigation and real-key session checks passed after
the update, with all containers healthy and the database container unchanged. Development dependency
maintenance [#227/#229](https://github.com/Nebettadjsr/GW2/pull/229) is merged separately: aligned
Vitest/mocker/coverage 5.1.0 and scoped minimatch 11.0.8 resolve the three targeted advisories.
Current CI/coverage/build/review and an independent clean Node 22 install passed. Full audit still
reports three moderate package entries from the pre-existing glob advisory, tracked in
[#230](https://github.com/Nebettadjsr/GW2/issues/230). Node/test dependencies are absent from the
production Nginx runtime image; this development-only change has not replaced the live source above.
No supported-user capacity,
new load-test result, complete keyed browser journey or multi-account validation is claimed.

## Current user workflows

The browser navigation includes Home, Crafting Profit, Crafting Discovery, Ectoplasm Salvage, Bank, Materials, Characters and System Status. See [README](../README.md) for a user-oriented overview and [Frontend UX Guidelines](FRONTEND_UX_GUIDELINES.md) for presentation rules.

Crafting Profit provides an explicit **Refresh data & results** action. It requests the calculation again and starts no synchronization itself: the backend answers from its last successfully synchronized inputs and admits the account and Trading Post refresh those inputs still need, so bank, material storage, account recipe knowledge and character crafting/recipes/inventory are what get refreshed, and account Luck is not fetched for this workflow. The full account refresh remains available for diagnostics in System Status. Normal feature use does not require opening System Status.

Profit and Discovery select their own required Trading Post item IDs but share one persisted price-cache/refresh pipeline. Prices younger than ten minutes are reused; only missing or stale required IDs are requested, in API batches. Feature calculations trigger this on demand; no full TP-price timer is run.

Bank and Materials have independent per-verified-account browser selections, pending edits and explicit save/discard. The same item can be protected in either store separately. Crafting Profit sends the committed source-specific ID sets when protection is enabled; the backend excludes those stores' quantities before owned-pool aggregation, preserving character stock. Bank exposes `protectionSelectable` from catalog/flag evidence; unknown evidence stays selectable and all Materials entries retain checkboxes. Category-local presets and stable toolbar behavior follow UX §2.1. Mystic Forge is a disabled checkbox placeholder, not an implemented recipe source.

Bank, Materials and Characters retain their saved content while automatic refresh runs. `useAutomaticAccountRefresh` shares shell task tracking, schedules from the backend's `X-Account-Max-Age-Ms` header (default 900000 ms), pauses inactive/hidden views and backs off after failure. Page operations request only Bank, Materials or Characters respectively; selected character details additionally recover their optional equipment/build sources when needed. The shell no longer bootstraps all account sources for these pages. Feature pages have a compact updating/failure indicator and no manual refresh/reload buttons or routine freshness paragraphs; System Status keeps explicit full-account refresh. Failed reads retain the last successful snapshot and source timestamps.

Characters uses one roster row per character with a shared summary component, default playtime-descending sorting and local alternate sorts; opening/sorting the roster does not fetch every detail. Selecting a character lazily reads core data, equipment and the build data needed for the active-elite subtitle; Inventory reads its bag manifest on inspection. The approved detail header, summary, centered ten-column inventory, 60px equipment tiles, local profession artwork and right-hand template selector are implemented. Tooltips project supplied equipment, upgrade, infusion, glyph and relic facts. Backend base/equipment totals and bounded passive profiles supply the attribute panel; incomplete metadata/unsupported passives remain explicit in the response. The read-only Builds section (saved build tabs in in-game order, actual active build marked separately from the inspected build, full specialization trait rows, land/aquatic skills, supported profession facts, source-scoped optional coverage with original completion time retained on failure) is implemented in the same Characters screen and was visually accepted by the Product Owner on 2026-11-08; it requests only the selected character, performs no editing and makes no browser GW2 calls. Exact presentation belongs in UX §2.2 and calculation scope in DOMAIN_SPEC §56.

Owned-input valuation uses the domain fallback rules when the selected Trading Post side has no usable price. Item reads include vendor values; the same values reach row calculations and resolution details. The calculation row's `unvaluedOwnedInput` flag identifies a consumed input for which no market, crafting or vendor value could be established. Crafting Profit shows a warning in its table and selected detail so an unknown input counted at zero is not presented as a reliable profit. This flag is separate from the freshness envelope: freshly fetched data can still contain an unvalued item.

Normal global item synchronization retains canonical icon URLs. The account refresh gateway also repairs missing or invalid icon metadata for newly fetched Bank, Materials and character-item IDs before publishing their account generation, outside the account transaction. Metadata failures leave ordinary image fallbacks and can be retried by a later synchronization; they do not discard account data. The browser obtains image bytes from the backend's on-demand cache rather than calling ArenaNet directly.

A Crafting Profit calculation is answered from the inputs that last synchronized successfully and does not wait for account synchronization or a price fetch. Expired account data or expired quotes still produce the ordinary result, marked provisional, together with the refresh work that was admitted for them; the result becomes final only once the required sources and the required item set are confirmed current. When no required account source has ever synchronized, the calculation is reported as pending with no rows rather than as an empty result. A selected row's detail says whether it still explains the inputs that row was calculated from, and always carries the row recalculated from the inputs it did use.

The browser presents that contract without deciding anything about freshness. A provisional result is displayed immediately as the ordinary result, with a compact **"Stale data – refreshing…"** label in the Trading Post warning's bordered form and a green continuously cycling border, directly above the retained last-good rows; while it is active and rows exist, no explanatory prose, account-source or Trading Post coverage status, outstanding-work text or "Showing … matching recipes" summary is shown between the label and the table. The backend's per-source account states and Trading Post coverage counts, and the retry reason, remain shown as separate facts in the failure and pending/no-calculation states. The compact treatment applies to every provisional CALCULATED result, including a successful empty one, which keeps its own honest empty message; only a PENDING answer carries the explanatory prose. A pending answer shows that nothing has been calculated yet and lists no row. While the label is up, the page polls only the task locations the response advertised and asks for the calculation again after each completion, because a succeeded task is not evidence that every required source is fresh; the label disappears only when a calculation for the current scope, settings and context comes back final. Settings, search, display filters, sorting and a still-present row selection survive each replacement, and a selection the replacement no longer offers falls back to the empty detail state. A detail answered from inputs newer than the displayed row shows that answer's own row and values, and says why, rather than explaining the older row. Automatic recovery is bounded to five minutes in total, including polling, successive tasks and recalculation; a failed, unknown or expired task, a transport failure, a response reporting a failure, and the deadline each stop the animation, retain the last usable rows and offer a retry with the backend's own reason. The deadline also ends recovery while a request has not answered at all — a stalled status read or recalculation stops the animation at five minutes like any other outcome, and its answer, if it arrives later, no longer changes what is displayed. Cached table/detail requests use the live account context without a raw key. If the backend reports CREDENTIAL_REQUIRED and a key is retained, the browser admits one explicit four-source account refresh within the same recovery budget, follows its advertised task and recalculates keylessly. A transient proof/admission failure keeps the cached view and key. Retry asks for the calculation again and can admit the needed keyed refresh within its own five-minute budget.

Both crafting result tables search output names, direct ingredient names and existing row fields locally. A 250 ms debounced request obtains indirect parent recipe IDs from the shared cached reverse graph; only IDs are returned, and the frontend intersects them with already-calculated rows. The graph and referenced item names are held in the shared in-process cache and persisted catalog file. Search does not receive dependency paths or complete crafting trees and does not alter resolver values or acquisition choices.

Global recipe metadata and TP-tradeable IDs are checked automatically. The default schedule is a six-hour fixed delay with a one-minute initial delay, configurable as `GW2_GLOBAL_REFRESH_INTERVAL_MS` and `GW2_GLOBAL_REFRESH_INITIAL_DELAY_MS`. The service fetches cheap ID lists first and skips detail work when IDs are unchanged. This detects recipe additions/removals but not an in-place recipe edit under an unchanged ID set. When the set changes, detail data is persisted before the crafting graph is rebuilt; unchanged recipe IDs do not trigger detail sync or a refresh-time graph rebuild. A missing graph cache is initialized on first use. The scheduled and manual System Status action use the same refresh service. In-process duplicate-task prevention and task/timestamp/error status are process-local and reset on restart.

System Status currently shows account refresh status/time, shared price-cache freshness, global check/change and recipe/graph timestamps, task state and the last global failure when available. The page offers full account refresh to everyone; shared health is generic (OK/Problem/Running/Not run yet), and detailed diagnostics plus the three full-catalog controls render only after the backend confirms owner authorization for the current account (see `CURRENT_ARCHITECTURE.md` section 6). The first owner boundary (#152) is delivered on the backend: `POST /api/sync/global`, reads of the tasks it and the scheduler admit, and `/api/system/status/details` require a live session whose verified account GUID equals the optional `gw2.owner.account-guid` (unset denies everyone; configuration is deployment-specific), while generic `/api/system/status` is coarse. The three distinct owner-only full-catalog operations (#180) are delivered on the backend as `POST /api/sync/full/items`, `/recipes` and `/trading-post` (operations `FULL_ITEM_CATALOG`, `FULL_RECIPE_CATALOG`, `FULL_TP_CATALOG`; items and recipes share the `GLOBAL_SYNC` exclusion; duplicates get 409 naming the unfinished task; no live full refresh has been run). `/api/system/status/details` additionally returns owner-only read-only aggregates, read only after authorization (never by the generic route): `items`, `recipes` and `tradingPostPrices` (stored rows, rows with a fetch time, rows without one, and `MIN/MAX(fetched_at)` over timestamped rows; `state: UNAVAILABLE` with null figures on query failure) and `accounts` (total registry rows and rows with `last_seen_at` within 60 minutes inclusive of the boundary, evaluated at one instant; no identifiers). The owner layout is visually accepted. Live full refreshes and Oracle qualification remain separate evidence; owner controls are not required for routine feature workflows and are not a general monitoring system.

The owner layout also has an **Application activity** section (STORY-OPS-002; Product Owner visual approval is complete): active accounts in the last 5 minutes (estimate, separate from the existing 60-minute value), total PostgreSQL size with available/stale/unavailable states, recent requests/minute, backend p50/p95 (`>5 s` for overflow, not browser time), 4xx/5xx/failed counts, a collapsed paginated recent-minute (6 h) history with explicit no-stored-activity gaps and partial/truncated markers, one grouped Days/Weeks dropdown (newest first, latest nonempty day preselected) with a single Download of the server-described CSV period (no range or format controls), and an optional configured Oracle Console link. It is read once and on an explicit Refresh, never polled. Retrieval steps: `OPERATIONS_RUNBOOK.md`.

The generic `/api/system/status` also returns an optional own-account `accountOverview` (characters, unlocked account recipes, material types and 64-bit total quantity) per permitted source with `CURRENT|STALE|NOT_LOADED|UNAVAILABLE` states (`DOMAIN_SPEC.md` section 57). Ordinary users see these in the Account data card plus one compact shared-service line. Both ordinary and owner layouts are visually accepted; preserve the approved baseline. Production preparation follows ROADMAP. The capacity-test programme is ended; no supported-user capacity is claimed.

## Persistence and generated data

The current PostgreSQL schema has shared item and recipe facts, TP tradeable IDs and cached quotes, plus account/character facts for bank, material storage, recipes, crafting capabilities, character inventory and consumed Luck. Principal tables are:

| Scope | Tables | Contents |
| --- | --- | --- |
| Shared metadata | items, recipes, recipe_ingredients, recipe_catalog_publication | Item/recipe facts, completion evidence and crafting graph inputs; nullable `items.no_mystic_forge` supports conservative checkbox eligibility. |
| Shared Trading Post | tp_tradeable_items, tp_prices | Tradeable IDs and quotes with timestamps. |
| Account identity/activity | account_registry | Verified stable GUID and monotonic activity. |
| Scoped account data | account_bank, account_materials, account_recipes, account_luck | Re-fetchable data owned by the verified account GUID. |
| Scoped characters | characters, character_crafting, character_recipes, character_items, character_bags, character_bag_layout_publication | GUID-owned core facts, crafting, recipes, occupied items and capacity-defined bag manifests; `character_items.upgrade_ids` retains equipped gathering glyphs and other supplied upgrades. |
| Scoped templates | character_equipment_coverage, character_equipment_tabs, character_equipment_tab_entries, character_build_coverage, character_build_tabs, character_build_tab_specializations | Saved tabs and independent coverage over the account's CHARACTERS generation. |
| Shared character metadata | equipment_item_details, equipment_item_stats, build_specializations, build_traits, build_skills, build_pets, build_legends | Reused item/stat and referenced build metadata, repaired during bounded synchronization, not ordinary reads. |
| Scoped freshness | account_source_state | Per-account/source publication generation and completion time, including empty successful results. |

`characters` retains nullable 64-bit `age_seconds` and `deaths`; null means unknown, not zero. Roster/detail reads expose those and a backend-clock-derived `nextBirthdayAt` (first strictly future multiple of 365 days after creation; null for unknown/future creation). Invalid fetched counters abort publication. Flyway V9 stores shared equipment details/stats, V10 adds equipped upgrade IDs and V11 adds nullable Mystic Forge flags and an ingredient lookup index. These are disposable caches, not durable user-preference storage.

Flyway alone writes the managed schema. The disabled legacy account_sync_state path is absent from scoped HTTP workflows. A reset discards cached data; verified synchronization repopulates it (UD-016). Missing markers are distinct from successful empty data. Reads report missing/stale state without inventing freshness or using another configured account. Publication is atomic per source; Profit captures scoped inputs in a repeatable-read snapshot.

The graph is derived from shared recipes, initialized on first use if absent and rebuilt after persisted global recipe changes. System Status exposes global maintenance and scoped account refresh. Graph/Trading Post caches are shared rather than copied per account.

## Error handling and background work

Long-running web synchronization uses Spring-managed application task submission and task status APIs. Duplicate identical work is prevented within the running backend process. Account freshness checks share the `ACCOUNT_SYNC` operation; an active run is reused only for the sources it is actually refreshing, and sources it omits stay outstanding until a run covers them, so a narrower active task is never reported as having refreshed them. Account writes remain non-overlapping, and the shared price refresh is coordinated separately. The global scheduler catches/reports failures so a failed execution does not prevent later scheduled runs. This is single-process coordination, not a distributed job system; multi-replica coordination is intentionally future work.

Frontend pages present operation loading, completion, and failure states. Backend task history and refresh timestamps are in memory rather than durable operational telemetry.

## Build and verification

Backend tests run with `./mvnw test` in `backend/`; JaCoCo reports are generated with `./mvnw test jacoco:report` (output under `backend/target/site/jacoco`). Frontend checks are run from `frontend/`: `npm test`, `npm run test:coverage`, `npm run type-check`, and `npm run build`; browser smoke commands are documented in [Test Strategy](TEST_STRATEGY.md). GitHub Actions provides the push/PR CI gate and combined Java/frontend coverage KPI summary. Coverage below the 90% target is reported as a warning, not a build failure. Optional integration and live API checks have prerequisites and are not equivalent to the default deterministic suite.

Development uses GitHub issues, PRs, Maven/Vitest CI and native Codex review. Each explicit Claude workflow dispatch selects one ready auto issue; the normal turn limit is 40 (configurable) and the job bound is 45 minutes. An authorized supervisor may dispatch/review subsequent runs. There is no usage-percentage gate or independent background supervisor. Small nonblocking follow-ups may be recorded by review. The retired Python service is recoverable at pre-github-flow.

The informational Performance workflow, resource collectors and capacity journeys were retired by STORY-LAUNCH-001; earlier measurement reports are historical evidence only (see [Performance Measurements](PERFORMANCE_MEASUREMENTS.md)).

## Account data deletion

`DELETE /api/account/data` deletes the verified account's cached data (UD-016: no backup or tombstone). It answers `204` on success, `409 ACCOUNT_WORK_IN_PROGRESS` while a refresh is unfinished, `503 DATA_STORE_UNAVAILABLE` when the account is busy, the wait bound passes, or the delete fails, and `401 ACCOUNT_DATA_DELETED` for a session that a deletion has invalidated, on every session-authenticated route including owner, task, status and renew. A fresh explicit Connect afterwards creates a normal session. The process-local gate reopens on every failure path; a network failure alone cannot confirm whether the server committed deletion.

The key card offers **Delete cached data** for a verified connected account (STORY-DELETE-002), separate from Disconnect. Confirmation uses a small native modal. A `204` removes only that GUID's Material Protection record and shadow, resets the current connection locally without `/session/logout`, and broadcasts a GUID-only invalidation to other same-origin tabs (`BroadcastChannel`, `storage` event fallback). Every received signal cleans the specified GUID, including inactive shadows; only a matching active account disconnects. `ACCOUNT_DATA_DELETED` also cleans that GUID without generic `SESSION_REQUIRED` re-proof. A late deletion response cannot disconnect a newer explicit connection; a retained same-GUID connection keeps empty, ready selection controls. Other accounts stay intact. The Product Owner approved the combined System Status layout on 2026-11-14.

An expired or displaced tab context never causes an automatic DELETE retry. The modal asks for a new
confirmation; the next explicit attempt rechecks the retained key before deleting. Authoritative
`ACCOUNT_DATA_DELETED` remains a final local invalidation rather than this recoverable path.

## Current limitations

- Account datasets are GUID-scoped. One account is active per shared-cookie browser session; independent accounts in such tabs are not promised.
- The connection panel (shown only on System Status, which stays reachable while disconnected and on direct URL/reload; other pages show a concise link to it) retains the key in sessionStorage, proves it before loading account data, and renews access without repeated entry. Invalid keys are removed; transient failures retain the key for retry. The key area is one bordered card: heading, two small help links ("How do I get an API key?", "Is my API key safe here?") in place of routine connection sentences, a full-width field with an eye button that shows or hides only the typed draft, and an actions row below (Connect/Replace key, Try again, Disconnect). Only working, outage and error states are narrated. Help opens a small centered native modal dialog (short safety facts with collapsed "What this app stores"; four numbered creation steps recommending all permissions, an ArenaNet link in the first step, and a footer link to the safety explanation). The draft is masked again after an accepted submission, Disconnect, an account change or remount, and kept after a failed submission. The retained key is never put back into the field.
- Scoped HTTP workflows now operate on the migrated schema; legacy unscoped account access stays refused. Local Chrome/live-ArenaNet recovery evidence covers completed full synchronization, selector options, loaded Profit, Bank, Materials, reload retention and disconnect. This does not certify every feature; the separate hosted checks and their limits are recorded in the public Oracle section above.
- A Java 25 backend image and static Nginx frontend image with same-origin `/api` forwarding run with PostgreSQL in the public Oracle stack. Only the frontend publishes a loopback host port; the host edge supplies public TLS. Every host Compose invocation requires the production file, protected HTTPS override and explicit environment file described above. The standalone production file alone does not describe this deployment; #228 owns portable configuration follow-up. The local offline Compose smoke is separate evidence (`CURRENT_ARCHITECTURE.md`, container image sections).
- Refresh status is process-local, not durable telemetry. Multiple backend replicas would require shared coordination for global scheduled work and refresh deduplication.
- Attribute projections support level-80 land equipment and explicitly modelled passives; they are not a full trait/combat simulator. In-game effects, live economic correctness and general exact attribute parity remain separate verification work.
- Oracle Always Free hosts the public release described above. No supported-user capacity is claimed; the retired capacity-test programme is not a launch prerequisite.

## Companion documents

- [Domain Specification](DOMAIN_SPEC.md): normative product and calculation rules.
- [Current Architecture](CURRENT_ARCHITECTURE.md): observed component boundaries and major flows.
- [Target Architecture](TARGET_ARCHITECTURE.md): intentional design, decisions, and TBD items.
- [Roadmap](ROADMAP.md): active phase, planned sequence, and exit criteria.
- [Known Problems](KNOWN_PROBLEMS.md): confirmed open defects and risks.
- [Test Strategy](TEST_STRATEGY.md): verification layers and procedures.
- [Crafting Glossary](crafting/GLOSSARY.md): player-facing terms and UI-label mapping.
