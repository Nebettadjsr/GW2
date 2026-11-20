# GW2 Tool — Architecture

## 1. Authority and scope

This is the maintained architecture document, reconciled with the source on 2026-11-17.
It describes the existing system and the constraints changes must preserve. Section 10 lists
deferred choices separately; an archived decision is historical rationale, not an instruction
to restore an obsolete implementation.

[DOMAIN_SPEC](DOMAIN_SPEC.md) owns product and calculation rules. [CURRENT_STATE_SPEC](CURRENT_STATE_SPEC.md)
owns deployed revisions and verification limits. [TEST_STRATEGY](TEST_STRATEGY.md) owns test procedures;
[FRONTEND_UX_GUIDELINES](FRONTEND_UX_GUIDELINES.md) owns presentation rules and
[the glossary](crafting/GLOSSARY.md) owns player-facing terminology. Development automation is
documented in [agent/README](../agent/README.md), separately from this application architecture.

The maintained [crafting reader guide](crafting/README.md) intentionally summarizes the domain
rules for people who do not need implementation details. This is the selected exception to
avoiding duplicated specification text: keep the explanation synchronized with DOMAIN_SPEC,
and let the domain specification govern any disagreement. The guide creates no separate rules.

## 2. Runtime and source boundaries

```text
Browser: Vue 3 / TypeScript
  | HTTPS, same-origin /api
Host Nginx + Certbot
  | loopback HTTP
Frontend container: static Nginx
  | internal HTTP
Backend container: Java 25 / Spring Boot
  | SQL                       | HTTPS
PostgreSQL container       Guild Wars 2 API / asset services
```

The browser is the only supported product UI. The deployed application has one backend process
and serves multiple verified GW2 accounts. Account data is isolated; global game data is shared.
Local development uses Vite's same-origin proxy or the three-container Compose stack.

| Source area | Responsibility |
| --- | --- |
| `frontend/src/` | Screens, HTTP clients, interaction state, presentation and the Ecto exception below. |
| `web/`, `web/access/`, `web/task/` | Spring entry point, thin controllers, DTOs, authorization mapping and task transport. |
| `application/` | Use-case orchestration, scoped input capture, refresh coordination, access and operational aggregates. |
| `craft/`, `domain/` | Crafting/economy rules, inventory resolution and bounded character attribute projections. |
| `repo/` | PostgreSQL queries, publication, scoped reads and infrastructure-to-domain mapping. |
| `sync/`, `api/`, `parser/`, `infra/` | ArenaNet adapters, payload validation, schema initialization, icon delivery and file stores. |

Domain code must not depend on Vue, JavaFX, HTTP/JSON DTOs, SQL/JDBC, PostgreSQL, Docker,
filesystem locations or raw GW2 response models. Application services coordinate adapters;
controllers do not become a second calculation layer. Existing code is changed incrementally,
without introducing extra services solely to make the design more elaborate.

## 3. Browser responsibilities and assets

The [supported browser viewport policy](DOMAIN_SPEC.md#supported-browser-viewport) owns
the informational width notice and public-reading exceptions. Viewport detection controls
presentation only: narrow tools retain a desktop-width canvas with horizontal scrolling.
It must not gate account restoration, bootstrap, mounting, requests or native dialogs. Keep
the ordinary route/KeepAlive lifecycle unchanged across resize; no hidden/inert admission
machinery is needed. Account-generation guards and backend authorization still own account
isolation. Public reading pages retain their account-restoration exemption and fluid layout.

Controlled real-browser journeys use the production frontend with a synthetic HTTP boundary.
They verify real navigation, event handling, native focus and request wiring, independently
of Vitest/JaCoCo coverage. They do not prove backend economics, live ArenaNet behavior or
production capacity. Their command, CI execution and evidence format belong in TEST_STRATEGY;
CURRENT_STATE_SPEC distinguishes implemented changes from the actual deployed revision.

The browser presents backend results and owns filtering, sorting, selection and loading/error
state. Crafting Profit, Discovery, recipe eligibility, recursive resolution, opportunity cost,
daily rules, tree quantities and purchase/owned-material values remain authoritative backend
results. The frontend does not recalculate or scale a selected row's resolution tree.

**Ectoplasm Salvage is the explicit exception:** the browser calculates expected salvage/Luck
outcomes from backend-provided item, quote and account-Luck inputs. Editing its controls
recalculates locally. This exception does not extend to crafting or character attributes.

All GW2 API and asset access goes through the backend. Item and build-reference images use
backend-relative URLs and a validated filesystem cache, HTTP caching and stable fallbacks.
Image bytes do not belong in PostgreSQL. Local guide screenshots and profession artwork are
frontend assets; guide images use compressed WebP and responsive variants. Regenerate them
from the ignored `frontend/screenshots/` sources with `node scripts/prepare-guide-images.mjs [cwebp]`
from `frontend/` (requires the `cwebp` CLI; writes `src/home/images`).

Public Home has a separate HTML entry at `/welcome`; tool pages and information pages use the
shared application entry. Public pages do not restore a retained key or request account data.
Only successful canonical Welcome responses permit indexing. See CURRENT_STATE_SPEC for the
actual host policy and its limits.

## 4. PostgreSQL, schema and account publication

One PostgreSQL database contains shared game/economy metadata and account-owned cached data.
The stable verified `/v2/account` GUID is the account key. A replacement API key reuses its
account's dataset; a browser-supplied GUID never selects backend scope.

**The database is disposable cached GW2 data** ([UD-016](archive/agent/user-decisions/UD-016-database-is-a-disposable-cache.md)).
There is no backup, archival or provenance requirement. Lost/inconsistent data is re-fetched.
This does not relax current account separation, transaction or calculation correctness.

Account-owned queries require an explicit trusted `AccountScope`, including joins, deletes,
aggregates, source-state reads and nested character lookup. Account-owned tables carry non-null
GUIDs, account-leading uniqueness and foreign keys. Character children use a composite account
and character relationship; character names and item binding names are not identity proof.
Shared recipes, item names, TP quotes and crafting graph structure are not copied per account.
PostgreSQL row-level security is not required behind this single backend boundary.

Fetch and validate a source outside its publication transaction. Publish its rows, stale-row
removal, `account_source_state` generation/completion and account activity together. Failed work
keeps the last good publication. Missing completion evidence differs from a successful empty
source. Different sources may succeed independently; the `CHARACTERS` source publishes its
roster, crafting, recipes, occupied items and bag manifests as one unit.

Optional equipment/build tabs are separate sources, not default account or Profit inputs.
Their coverage identifies the `CHARACTERS` generation they describe. Publication rechecks that
parent; reads retain explicit missing, stale and incompatible-coverage states. Shared equipment
and build metadata is repaired during bounded synchronization, never ordinary reads.

`ProfitInputSession` captures scoped rows, source generations and shared inputs in a
`REPEATABLE READ` transaction. Authorization and freshness remain separate from one another.
Ordinary account reads do not use a configured/last-used account or fetch upstream directly.

Character roster, inventory, equipment and build-tab reads share
`CharacterRosterService.ConnectionSource.snapshot`, which owns read-only `REPEATABLE READ`
setup, rollback and connection closure. Each call retains its own connection and scoped queries.

### Schema lifecycle

`infra.db.SchemaInitializer` runs before Spring startup and before the retained icon-maintenance
entry point. Flyway alone owns versioned SQL in `backend/src/main/resources/db/migration`:

- An empty schema applies the complete chain.
- A managed schema validates checksums and applies pending versions. Failure stops startup;
  checksum repair and automatic baselining are disabled.
- An unmanaged nonempty schema is explicitly reset before applying the complete chain. No old
  ownership is inferred and no successful source markers are invented.

Applied migrations are immutable; corrections use new versions. A managed checksum/migration
failure does not silently trigger reset. PostgreSQL transactional migrations roll back the
failing version. The unscoped account refresh, coordinator, gateway and sync entry points are
removed; account refresh runs only through `ScopedAccountRefreshCoordinator`. No unscoped
account repository read, price-relevance selection or icon-metadata union remains: account-owned
SQL requires an `AccountScope`, and global operations (Trading Post price refresh, icon metadata
repair, material catalog) select over shared data only. Both launchers initialize Flyway first.

`repo.Db` lazily owns one HikariCP pool per process. Defaults are eight connections and a
15-second acquisition bound, configurable through `DATABASE_POOL_SIZE` and
`DATABASE_POOL_TIMEOUT_MS`. Callers return leases with try-with-resources. Pool return resets
transaction state; borrowing restores the configured search path. A JVM shutdown hook closes
the pool. Flyway initialization uses its own connection before application traffic.

## 5. Account access, credentials and deletion

`application.access` owns access decisions, `infra.access` proves keys and `web.access` maps
HTTP outcomes. Proof validates the same transient key through `/v2/account` and `/v2/tokeninfo`.
Only the account response GUID establishes identity. Effective permissions, URL restrictions
and expiry come from that key's proof, not another session's previously cached data.

The backend issues an opaque 256-bit session identifier and retains only its verifier and
immutable principal in bounded process memory. A session is not an upstream credential.
Its absolute lease is 15 minutes or an earlier upstream expiry; reads/polling do not extend it.
Logout, eviction, expiry or backend restart require proof again. Definitive key rejection revokes
the originating session; transient ArenaNet failure does not revoke an otherwise live lease.

The `GW2_SESSION` cookie is host-only, HttpOnly, SameSite=Strict and Secure except for explicit
loopback HTTP development. Account requests also echo the proof's `X-Account-Context`.
The access interceptor authorizes before freshness, lookup, calculation or task admission,
captures immutable scope and enforces source grants. Current scoped routes enforce this even
though the retained `gw2.account-access.enforce` setting defaults to false. State-changing
requests require same-origin validation. Account and credential responses are `no-store`.

### Browser key lifecycle

Under [UD-018](archive/agent/user-decisions/UD-018-proportionate-gw2-key-handling.md), read-only
GW2 keys use ordinary browser `sessionStorage`, centralized in `api/accountSession.ts`.
Navigation/reload preserves the key; restoration proves it before account reads. Disconnect
and definitive invalid-key rejection remove it; temporary outages permit retry. Storage failure
falls back to page memory. Ordinary browser tab/session lifetime applies, without a secure-erasure
promise. The app has no separate registration, password, key vault or durable server key store.

Keys travel only as `X-GW2-API-Key` when proof or keyed upstream work requires them, never in URLs,
logs, public task payloads or PostgreSQL. Server deployment credentials remain backend-only.
Account switching clears account-derived view state; generation/context guards discard late
answers. One account is active per shared-cookie browser session. Independent accounts in
shared-cookie tabs are not promised; a changed context causes reconnect/retry instead of mixed data.
These are proportionate game-data controls, not a reason to add high-sensitivity key infrastructure.

Material reservations are secret-free, per-verified-GUID browser-local preferences. Bank and
Materials stay separate; changes autosave. Bank includes finite targets and guarded slot hints,
with the saved item amount redistributed if its layout changes. The backend validates settings
and projects protected quantities before aggregation; browser hints never authorize account data
or own the calculation rules. DOMAIN_SPEC section 9.1 owns exact reservation behavior.

### Owner access and deletion

`GW2_OWNER_ACCOUNT_GUID`/`gw2.owner.account-guid` identifies the owner through the same proved
GUID. Blank grants nobody; malformed values fail startup. Detailed system/catalog diagnostics,
global mutations and their task observations are authorized on the backend before accessing
privileged data. Generic health contains no hidden owner payload.

The backend runs the check automatically (`web.ApiCompatibilityScheduler`, `@Scheduled` like the global refresh):
initial delay `gw2.api-compatibility.initial-delay-ms` (default 60 s) and fixed delay
`gw2.api-compatibility.interval-ms` (default six hours), through the same admission as the manual action so
a duplicate is skipped; test contexts defer it via `src/test/resources/config/application.properties`.
The browser shows only a compact "GW2 API" card in the owner catalog grid (Good / Needs update / neutral
Checking, Not checked, Unavailable, plus Last checked) that polls the cached read and has no start control.

`GET /api/system/api-compatibility` (cached result only) and `POST /api/system/api-compatibility/check`
(retained for CLI/testing; the browser never calls it)
are owner-authorized before any read or work; the POST also requires a same Origin and is admitted
once at a time through the existing `BackgroundTasks` port's administrative admission (a duplicate is 409;
the application service does not import the web task package). A checker failure publishes a sanitized
unsuccessful latest attempt rather than keeping an older success. The checker
(`api.ApiCompatibilityChecker`) makes at most seven fixed public ArenaNet requests under a cooperative two-minute
whole-check bound checked between requests; each request is itself bounded by the 1 MiB byte limit
(accumulation is cancelled while reading) and a wall-clock per-request limit that also covers a stalled
body (`api.PublicApiTransport`). Item fields, the ordinary recipe (production full-refresh validator)
and the Jade canary identity/Research Notes currency 61 are verified, not just their type. The checker compares consumed fields/types and the real `RecipeParser`, reads `/v2.json?v=latest` against
an explicit reviewed version baseline, and classifies probes OK/ATTENTION/UNAVAILABLE/MISMATCH. Only the
latest result lives in process memory (not-checked after restart until the automatic run completes; stale after 24 hours); no
history, account endpoint, mutation or automatic API-version migration exists.

`DELETE /api/account/data` takes the process-local per-GUID lifecycle gate, refuses unfinished
refresh work, drains admitted requests and deletes that registry row transactionally under the
account writer lock. Cascades remove account rows, not shared game metadata. Only after commit
are scoped task associations cleared and account sessions deletion-invalidated. Failures reopen
the gate. The browser clears that GUID's reservations and active connection and broadcasts a
GUID-only invalidation; another account remains intact. A new explicit Connect can repopulate
the account. Disconnect only removes the connection/key; it does not delete cached account data.

## 6. Refresh and background work

`BackgroundTaskService` is the in-process asynchronous facility. Task state/history and scheduler
check/error times reset on restart; persisted source markers remain the freshness authority.
There is no distributed queue or durable calculation-job system.

`ScopedAccountRefreshCoordinator` admits one unfinished account writer per verified GUID and
captures that operation's transient credential until completion. Every demand revalidates its
own identity and source grants. A caller can reuse active work only if its admitted credential
covers the needed sources; otherwise it waits/rechecks. Task observation requires a live,
same-account context with the original required grants. Logout does not change an already
admitted worker's scope; completion grants no further HTTP access. Keys are cleared on every
terminal path and errors carry no upstream credential/body text.

| Work | Source/trigger | Default policy |
| --- | --- | --- |
| Account freshness | Per-source publication markers, feature demand | 15-minute maximum age; Bank/Materials/Characters refresh only their needed sources. The optional `WALLET` source is added only when the validated request context permits `WALLET_READ` (independent of whether a raw key accompanies the read); it joins calculation identity/freshness but never makes a calculation pending. Discovery tracks it the same way (unpublished = explicit unknown requested for refresh, expired = stale), never as a mandatory source, and a keyed read also needs the key's own proven wallet scope. |
| Profit account inputs | Bank, Materials, account recipes, Characters | Excludes Luck and optional equipment/build tabs. |
| Trading Post | Feature-relevant item IDs, shared cache | Reuse quotes younger than ten minutes; batch missing/stale IDs on demand. |
| Global metadata | Scheduler and owner action, one shared service | Six-hour fixed delay, one-minute initial delay; cheap ID lists first. |

Profit/Discovery select different TP relevance sets but share one quote repository/fetch path.
`TradingPostRefreshCoordinator` coalesces coverage demands and rechecks uncovered/newly relevant
IDs after active work. There is no full-catalog price timer.

Global refresh publishes changed recipe data before rebuilding the shared `CraftingGraphCache`.
Discovery and ingredient search use that shared cache. Profit separately builds a graph through
its captured database connection with `loadForSnapshot`, preserving its repeatable-read view;
there is no graph-generation reuse for those pinned loads yet. A temporary file/atomic replacement
publishes the rebuilt cache file. Existing readers finish on the graph they captured, and a failed
rebuild leaves the shared cache invalidated. Cold disk loads validate schema, row counts and the
existing recipe publication marker; warm in-memory reads add no source query and still require
invalidation. Incomplete, duplicate or malformed safe-sync details leave the publication incomplete for retry;
only deliberate unsupported-kind skips participate in the complete-catalog fast path.
This publication mechanism does not establish deep
immutability of every graph member; shared-generation reuse remains a concrete future improvement.
Recipe-catalog publication evidence makes incomplete synchronization retryable. Unchanged IDs
skip detail work, so in-place edits under unchanged recipe IDs remain a known limitation.

## 7. Calculation, detail and search boundaries

Profit uses stale-while-revalidate: calculate synchronously from last-good inputs, return a
provisional `200` if usable inputs are stale and admit refresh separately. No usable required
account publication yields `202` with no fabricated rows. Successful empty sources are usable;
missing TP values retain explicit domain states instead of becoming free ingredients.

Mystic Forge recipes enter through a static bundled adapter with a negative recipe-ID namespace, never the
synchronized recipe tables. The backend alone computes exact expected output versus guaranteed/net units
(including seed reuse), vendor acquisitions, binding, and the initial-owner index; work that was rejected or
hit the work limit is not refunded and records no execution. The browser only presents these source facts.

Forge is no longer part of Crafting Profit (issue #378). `GET /api/mystic-forge/recipes` is a public cached-metadata catalogue
(no planner, no account read); `POST /api/mystic-forge/project {recipeId,target}` plans one exact target over the verified
session's account snapshot via `CachedCraftingProfitService.forgeProject` -> `CraftingProfitService.planForgeProject` ->
`ForgeProjectPlanner`, keeping inventory binding pools, roster and known recipes intact. Item and currency icons are mapped to
same-origin routes; the browser never contacts ArenaNet/Wiki image hosts. A project's source identity and price demand are its own public path, not the ordinary set or the catalogue:
`ForgeProjectPlanner.pathItemIds` (structure only: output, direct inputs and fixed-output producers' inputs) feeds
`CraftingProfitItemCollector.coverageExplicit` (quote version over exactly those ids), is fingerprinted with the
catalogue, vendor and Clover reference facts, and is demanded through `TradingPostRefreshCoordinator.requireItems` ->
`TpSync.syncTpPricesForIds` on the shared single-flight/coalesced ten-minute run, with metadata completion and no DB
connection held over the network. A keyless cached read admits no account work. The old all-catalogue Forge flag is not used.
The global metadata refresh selection (`IconSync.globalReferencedOrUnknownItemIds`) includes bundled Forge/Clover/vendor ids,
and Weapon/Armor rows are incomplete until their `subtype` is stored (non-gear never needs one).

The application freshness envelope identifies the captured account/source/quote generations,
coverage, task locations and retry state. A task finishing does not prove final freshness.
The browser polls only advertised locations and recalculates with current settings. One
five-minute recovery budget spans all tasks, polling and recalculation, including stalled calls.
Failure/deadline keeps the last usable result, ends the animation and offers retry. Context,
generation and request guards reject late table/detail answers. Detail supplies its own matching
row if inputs changed; an unchanged completed detail can be reused on return navigation.
The tracked wallet's discrete freshness state joins calculation identity because expiry withholds
its balance even without a new publication. Identity remains stable within each usability state.
If the ordinary economic route exceeds the purchase budget, one rolled-back retry compares cash
cost first across purchases, recipe alternatives and character assignments. It can mix purchases
and currency crafts; the simulator still enforces the cumulative budget and rolls back failed trials.

The existing UD-013–015 responsiveness requirement is at most seven seconds for the complete
usable cached Profit page, including an expired-input provisional result. Refresh and its later
replacement are separate; a no-cache loading placeholder cannot demonstrate that requirement.
This preserves the selected response contract without reviving the withdrawn pre-release
capacity programme or asserting measured compliance. Verification methods belong in TEST_STRATEGY.

The backend resolves Profit's counted recipe executions and Discovery's single attempt through
the ordinary resolver, preserving acquisition decisions and exact tree context. Checked arithmetic
and bounded simulation prevent wrapped negative costs and excessive material expansion; numeric
limits and blocked-result meaning belong in DOMAIN_SPEC. Coordinated workers reuse exclusively
owned simulation state and undo journals within a request, restoring state after each root recipe.
No worker simulation state is global or thread-local.

Output/direct-ingredient search is local over returned rows. Indirect ingredient search uses
the shared reverse recipe index through `/api/crafting/ingredient-search`, returns recipe IDs
only and never calculates or refreshes prices. The browser intersects matches with existing
rows, so search cannot widen calculation scope. Static graph reachability does not assert that
the resolver selected a particular craft/buy path.

Character attribute values also remain backend results. `EquipmentTotals` and
`PassiveCharacterStats` implement the documented level-80 land-equipment and supported passive
projection. Unsupported effects and missing metadata remain explicit; this is not a full combat
simulator. Saved build/equipment tabs preserve upstream order, actual active flags and nullable
slots; optional coverage never masquerades as an empty successful result.

## 8. Deployment and configuration

Frontend assets run in one Nginx container, the executable Java backend runs as non-root, and
PostgreSQL 17 runs separately. Only the frontend publishes a loopback host port; public host
Nginx terminates TLS and Certbot renews the certificate. No Oracle-specific application service
is required. Keep normal Docker-host portability and the selected Always Free budget; do not
introduce Kubernetes, microservices or extra infrastructure without a concrete requirement.

Nginx forwards `/api` with original Host (including port), Origin, cookies and key/context headers.
Client `Forwarded`/`X-Forwarded-*` headers are not trusted. The public edge requires exact canonical
HTTPS Origin for unsafe methods on every proxied path. HTTP and www redirect to the apex.

Local `docker/compose.yaml` is self-contained. `compose.production.yaml` requires a nonblank
database password and passes explicit environment values, but **does not by itself reproduce
the public deployment**. Oracle additionally requires a protected HTTPS override and explicit
environment file for the exact origin and separate restricted application DB role. Until
[#228](https://github.com/Nebettadjsr/GW2/issues/228) is resolved, do not omit that override or
claim standalone production portability. CURRENT_STATE_SPEC owns host paths and verified flags.

Wallet currency display metadata (`wallet_currencies`, public `/v2/currencies?ids=all`, refreshed with global data; failure never blocks the recipe refresh) feeds the same-origin currency icon route `/api/resources/currencies/{id}/icon/...` through the existing resource icon cache; the browser never calls ArenaNet.

Database, downloaded icons and operational history use separate named cache volumes. The
derived graph file is in the backend writable data directory and can rebuild after recreation.
Build/runtime secrets are excluded from Git and frontend build inputs. `VITE_OPERATOR_*` and
related information-page values are deliberately public build-time facts, not secrets.

## 9. Operational history

`OperationalObservationFilter` records query-free, fixed-route-group request aggregates and
backend elapsed time. Sessions, health/system/admin observations, ordinary task polling,
assets and OPTIONS/HEAD are excluded. A capped process-local map estimates recently active
verified accounts without writing account identities to exported history.

The minute scheduler drains only nonempty request buckets. `SparseActivityStore` lazily writes
UTC daily CSV files: at most 30 nonempty days, 2 MiB/day and 32 MiB total including its bounded
index. Idle time writes no empty row/file and performs no retention rewrite. ISO-week downloads
stream existing daily rows; no second weekly copy is stored. Coverage/loss/truncation/pruning
markers distinguish retained activity from a claim of uptime.

Owner-authorized summary/period/download routes use `no-store`. The UI reads on entry/explicit
Refresh, not polling. DB size uses a five-minute cached query with available/stale/unavailable
states. Backend p50/p95 are aggregate latency bins, not browser response or supported-user
capacity measurements. Host CPU/RAM remains in Oracle Monitoring. Container/edge logging is
bounded and excludes keys, query strings, headers and request bodies. Current host inputs
and logging settings are in [Current state](CURRENT_STATE_SPEC.md).

## 10. Deferred choices and concrete limits

- **Automatic inactive-account cleanup:** no threshold or scheduled deletion is selected.
  Decide only if real storage/operational impact justifies it. Existing creation/last-seen/sync
  UTC activity fields remain monotonic; unknown history stays unknown.
- **Multiple backend replicas:** not deployed or coordinated. Shared scheduling, writer
  exclusion, sessions and deletion gates would need an explicit design before scaling out.
- **Portable public production configuration:** #228 tracks the host override dependency.
- **Capacity:** the retired pre-release measurement programme is not an active requirement.
  No supported-user count or measured free-tier headroom is claimed. Concrete future performance
  improvements and actual operations history remain relevant.

Do not turn deferred choices into new infrastructure or product rules during routine work.
Open defects belong in [KNOWN_PROBLEMS](KNOWN_PROBLEMS.md) and GitHub issues;
the owner and approved issues determine priorities.

## 11. Historical rationale

Historical records are preserved in [docs/archive](archive/README.md). Key still-applicable
decisions include disposable caches (UD-016), proportionate browser-held keys (UD-018),
account access (ADR-008), Flyway (ADR-010), static frontend Nginx (ADR-011) and host TLS
(ADR-012). This document carries their current constraints; archived superseded plans,
pre-release gates and workflow instructions are not the execution contract.

The Research Note estimate (DOMAIN_SPEC 2.1.0) is computed once per captured calculation (`ResearchNoteEstimate`, held lazily by the shared `PlannerContext`) from the already captured quotes of recipes 15120/15131, so it joins the normal calculation identity and adds no producer planning per row. The resolver values only wallet-paid Notes (aggregate rounded up once, included in route cost comparison); the planner deducts the amount from profit. Table rows carry only the compact estimate amounts; the two-recipe comparison is selected-detail data. The frontend formats backend results and never calls ArenaNet.
