# GW2 Tool — Target Architecture

## 1. Purpose

This document defines the intended structural boundaries of the GW2 Tool. Domain behavior belongs in `DOMAIN_SPEC.md`; detailed testing procedures belong in `TEST_STRATEGY.md`; migration sequencing belongs in `ROADMAP.md`.

The target is a small, maintainable web application that is easy to test, safe to change, containerized, multi-user capable, and suitable for bounded AI-assisted development. Prefer simple boundaries over enterprise-style complexity.

---

# 2. Architectural Goals

1. Separate shared domain/application logic from UI, persistence, and external APIs.
2. Use the browser frontend as the sole supported user interface. JavaFX and other desktop UI frameworks are not planned application components.
3. Keep PostgreSQL as persistent storage and isolate it behind repository/adaptor boundaries.
4. Keep Guild Wars 2 API communication in the backend.
5. Keep authoritative shared calculations in the backend unless an explicit feature-local exception is documented.
6. Support multiple Guild Wars 2 accounts in one hosted application instance and one shared PostgreSQL database while strictly isolating account-specific data.
7. Share global Guild Wars 2/economy data across users.
8. Avoid durable server-side storage of users' Guild Wars 2 API keys when the browser-held-key model satisfies identity and synchronization requirements.
9. Preserve portability and avoid unnecessary infrastructure or provider-specific dependencies.

---

# 3. High-Level Target Structure

```text
User Browser
    |
    v
Frontend Container
    |
    | HTTP / JSON
    v
Backend Container
    |             \
    | SQL          \ HTTPS
    v               v
PostgreSQL       Guild Wars 2 API
```

The deployed application consists primarily of frontend, backend, and PostgreSQL containers. The Guild Wars 2 API remains an external dependency.

---

# 4. Frontend

## 4.1 Responsibilities

The frontend:

- serves and renders the web UI,
- collects user input,
- calls backend APIs,
- presents backend-provided results and domain states,
- owns presentation state such as filtering, sorting, selection, loading, and error display,
- may own feature-local calculations only where this architecture explicitly defines an exception.

The frontend must not duplicate authoritative backend/domain calculations. Crafting Profit, Crafting Discovery, recursive recipe resolution, material valuation, opportunity cost, recipe eligibility, daily-item rules, and resolution-tree construction remain backend-owned. Crafting results supply total owned-material opportunity value and, for each required purchase, the selected acquisition unit price and purchase total; the frontend displays these values without scaling quantities or choosing between raw Trading Post quotes.

Reusable UX/UI requirements are owned by `FRONTEND_UX_GUIDELINES.md`. Detailed user-visible Crafting Profit and Discovery semantics are owned by `DOMAIN_SPEC.md`. For the mapping between player-facing labels and Trading Post/domain terms, consult the canonical [Crafting glossary](crafting/GLOSSARY.md); do not duplicate its label definitions here.

## 4.2 Ectoplasm Salvage exception

Ectoplasm Salvage is the explicit frontend-owned calculation exception. The browser loads the required item metadata, Ecto and Crystalline Dust Trading Post quotes, and account Luck through backend read APIs, then performs the Ecto-specific calculation locally according to `DOMAIN_SPEC.md` sections 2.3 and 45–47.

Changing Ecto amount, salvage method or exact tool, or Trading Post price modes recalculates locally. A Trading Post refresh rereads the required prices through the backend price boundary; it does not require a dedicated Ecto calculation request.

The former backend-owned Ecto calculation flow, including the old parameterless `GET /api/ecto/salvage` operation and client/contracts used exclusively by it, is not part of the target architecture and may be removed when no active consumer remains. The browser implementation is the canonical Ecto Salvage UI; JavaFX compatibility is not required.

This exception does not permit the frontend to recreate Crafting Profit, Discovery, recursive crafting, or other backend-owned domain logic.

## 4.3 Technology

**DECIDED:** Vue 3 with TypeScript and strict type checking.

Use Vue single-file components with the Composition API and `<script setup lang="ts">` as the default convention. Static frontend assets fit the frontend container without requiring a server-side JavaScript runtime. Framework dependencies remain frontend-only; backend/domain architecture and HTTP contracts must remain independent of Vue.

Decision rationale and alternatives belong in `architecture/decisions/ADR-001-frontend-framework-and-language.md`.

## 4.4 Presentation assets

Item/recipe icons are presentation assets, not domain behavior.

- The browser obtains icons through the application/backend boundary rather than directly from ArenaNet asset URLs.
- The backend may maintain a persistent filesystem cache; icon binaries do not belong in PostgreSQL or source control.
- Browser/HTTP caching should avoid unnecessary transfers.
- Missing or failed retrieval degrades to a stable fallback without invalidating calculation results.
- Deployed environments that rely on the cache must provide suitable persistent storage.
- Detailed cache mechanics and failure/status behavior belong in the relevant feature specification/tests.

---

# 5. Backend

The backend is the core application runtime and authoritative owner of shared domain/application behavior.

Responsibilities include:

- exposing the HTTP API,
- executing application use cases,
- enforcing shared domain rules,
- running backend-owned calculations,
- synchronizing with the Guild Wars 2 API,
- loading and storing data,
- coordinating repositories/adapters,
- returning explainable results to the frontend.

A Java backend is retained because the existing application/domain implementation is Java-based. Framework-specific behavior must not leak into the domain layer. The exact web framework is decided separately when required by implementation work.

---

# 6. Backend Internal Boundaries

```text
HTTP / API Layer
      |
      v
Application Layer
      |
      v
Domain Layer
      |
      v
Ports / Interfaces
   /          \
  v            v
Persistence   GW2 API
Adapter        Adapter
  |             |
  v             v
PostgreSQL    Guild Wars 2 API
```

Dependencies point inward toward application/domain abstractions.

## 6.1 Domain Layer

The domain layer contains shared business rules such as crafting resolution, graph traversal, material consumption, craft-vs-buy decisions, opportunity cost, non-tradable valuation, recipe selection, daily-item behavior, discovery eligibility, and backend-owned profit calculation.

Authoritative behavior is defined in `DOMAIN_SPEC.md`.

The domain layer must not depend on UI frameworks, browsers, HTTP/REST/JSON transport types, PostgreSQL/JDBC/SQL, Docker, filesystem paths, or raw Guild Wars 2 API response models.

## 6.2 Application Layer

The application layer coordinates use cases and infrastructure without becoming a second home for complex domain calculations.

Examples include:

```text
CalculateCraftingProfit
GetCraftingDiscoveryCandidates
RefreshAccountData
RefreshTradingPostPrices
RebuildCraftingGraph
GetBankContents
GetMaterialStorage
```

An application service may load data through ports, construct domain input, execute domain behavior, persist required state, and return a result.

## 6.3 HTTP / API Layer

The API layer owns routing, request validation, transport DTO mapping, authentication/account context when applicable, and HTTP response/error mapping. Controllers must remain thin and must not implement business rules.

Endpoint names and DTO details belong to API/feature specifications rather than this architecture document.

## 6.4 Persistence and external API adapters

Application/domain code depends on repository and external-service interfaces, not concrete PostgreSQL or HTTP clients. Adapters implement those interfaces and translate infrastructure-specific models at the boundary.

Guild Wars 2 JSON structures and SQL/JDBC concerns must not leak into domain logic.

---

# 7. PostgreSQL and Data Scope

One shared PostgreSQL database serves the hosted application. A separate database per user/account is not part of the target architecture.

**The database is a disposable cache (UD-016).** Every row is fetched from the Guild Wars 2 API and nothing in it is secret or irreplaceable. There are no database backups, archives, provenance ledgers or preservation guarantees across schema changes. Missing, inconsistent or lost data is repaired by resetting the affected cached tables (or the whole schema) and re-synchronizing from the API, never by recovery, reconciliation or an ownership investigation. Correctness of current behavior (account isolation, scoping, transactional publication of fresh data) still applies; only preservation of old data is out of scope.

Persistent data distinguishes:

- global/shared data, including Guild Wars 2 item/recipe metadata and Trading Post data,
- account-scoped data, including characters, inventories, material storage, recipe knowledge, and other synchronized account information.

Account-scoped records are keyed by the stable Guild Wars 2 account identity, not by a particular API key. Replacing an API key for the same account must reuse the existing account dataset.

Account isolation is enforced by backend/application/persistence boundaries. Domain calculations should remain account-agnostic where practical and receive already-scoped input data.

Schema creation and upgrades use Flyway with versioned PostgreSQL SQL migrations. Sections 7.2-7.4 own the persistence and migration contract; ADR-010 records the technology alternatives and rationale. This is target architecture, not a claim that the existing manual schema has been migrated.

## 7.1 Account data retention

Persist enough activity information to identify long-inactive account data. Do not introduce automatic deletion without evidence that stale data causes meaningful storage, performance, or operational cost.

If cleanup becomes justified, explicitly decide the inactivity threshold, affected records, cleanup frequency, retained identity data, and concurrency protection. Cleanup must never remove global/shared data merely because one account is inactive.

Persist account creation time, `last_seen_at` for successfully authorized account use, and `last_successful_sync_at` for successful source publication, using UTC instants (`timestamptz`). Missing legacy activity remains unknown, never a fabricated historical visit. Update activity monotonically so concurrent requests cannot move it backwards. Failed proof, rejected cross-account requests and task polling failures do not advance activity; ordinary authorized reads may advance activity but never freshness. Per-source completion times remain the freshness authority; the account-wide sync time means only that some source succeeded. These fields permit later inactivity analysis without selecting a threshold or adding a cleaner.

## 7.2 Account ownership and publication

Use an account registry with a PostgreSQL `uuid` primary key equal to the verified `/v2/account` GUID. Display names are optional descriptive metadata. Credentials, credential hashes, sessions and application-user identities are not registry keys or durable registry fields. Concurrent registration of the same GUID is idempotent.

Persistence ownership is classified as follows:

| Scope | Records and relations |
| --- | --- |
| Global | Items and icon metadata, recipes and ingredients, material category/catalog order, TP-tradeable IDs and TP quotes. The crafting graph contains shared recipe structure; account recipe knowledge never partitions it. Global source versions/status remain global. |
| Account | Bank slots, material quantities, account recipe unlocks, Luck, character roster and each character's crafting disciplines, recipe unlocks and inventory/equipment. Account source completion/version rows and activity belong to the same account. |
| Account-derived process state | Input snapshots, calculation/result/detail caches, refresh demands, task ownership and task associations carry the verified GUID under section 8.1. This does not require making task history or calculation results durable. |

Every live account-owned table has a non-null account GUID referencing the registry. Account-owned uniqueness includes that GUID: bank `(account_id, slot)`, materials `(account_id, item_id)`, account recipes `(account_id, recipe_id)`, and Luck `(account_id)`. Character names are unique within an account, not a global ownership proof. Character surrogate IDs may be retained, with a unique `(account_id, character_id)` parent key. Character child rows carry the account GUID and use a composite foreign key to that parent; child uniqueness/upserts include account and the appropriate discipline, recipe or inventory location key. Preserve existing inventory slot/null semantics explicitly rather than relying on nullable columns to enforce uniqueness. No child may refer to a character owned by another account. Existing bound-item names remain domain input, not authorization or identity evidence.

All account repository operations require explicit trusted scope, including joins, existence checks, aggregates, stale-row deletion, upserts and marker access. No multi-user overload defaults to the configured/last-used account. Account indexes lead with account GUID where needed for scoped access. Enforce ownership through scoped queries plus database keys/foreign keys; PostgreSQL row-level security is not required for this single backend boundary. Keep recipe-knowledge and other domain decisions in their existing domain policies, receiving already-scoped data.

Use one durable source-state relation keyed by `(account_id, source)` for bank, materials, account recipes, characters and Luck. A successful publication records `completed_at` and an account/source-local monotonically increasing generation. No marker means missing; a completed empty payload has a marker and is usable. A generation advances only when that source publishes, including a successful unchanged/empty response; timestamps alone are not generation identifiers. Retire the singleton `account_materials_sync` as a runtime authority after importing only proven material completion evidence. Source schemas and identifiers remain application/persistence concerns.

Fetch and validate a source outside the database transaction, then publish all of its replacements/upserts/stale-row removals, generation/completion marker and account sync activity in one transaction. The character source includes the roster and all character payloads as one publication. Serialize writers per account under section 9.1; legacy promotion uses the same exclusion boundary. Failed fetch/validation/commit leaves last-good rows, version and completion unchanged. Successful sources may commit even when another source fails; there is no fabricated whole-account completion. Preserve the existing `REPEATABLE READ` calculation-input transaction across scoped rows, markers and shared inputs. Freshness envelopes identify the captured account and source generations, never later live markers. No transaction spans upstream calls and no extra domain calculation rules are introduced.

## 7.3 Versioned schema lifecycle

Use the open-source Flyway Java engine with its PostgreSQL support module, integrated into backend initialization before repositories, tasks or schedulers can run. Pin compatible dependencies with the Spring Boot dependency management in use and verify Java/PostgreSQL compatibility when implementing and upgrading. Use only the ordinary versioned SQL, history, validation and migration capabilities; no paid feature, new service, CLI installation or recurring subscription is required. Keep migrations under the backend's `db/migration` resources. Applied migrations are immutable; corrections are new versions. All runtime entry points retained during transition must use this gate or be disabled against the upgraded database.

Flyway becomes the sole schema writer: replace repository/startup `CREATE TABLE IF NOT EXISTS` and manual setup paths when their schema enters migration ownership. Validate history/checksums and fail startup on incompatible or failed schema state. Keep automatic baselining disabled; Flyway's destructive clean stays off and the explicit reset of section 7.4 is the only way cached data is dropped. An empty database executes the complete versioned chain; an existing non-empty database is never silently declared current.

A database that already contains unmanaged legacy tables is not adopted: the startup gate resets the cache, dropping the legacy cached tables and applying the complete versioned chain to an empty schema (section 7.4). A drifted or unrecognized schema is reset the same way; no schema fingerprint, preservation or ownership inference is required. Reset happens only as this explicit gate action, never as a silent response to an ordinary failed migration or a checksum mismatch on a managed schema.

Use transactional PostgreSQL DDL/data migrations for this cutover; avoid operations that require nontransactional execution. Migration failure rolls back the failing migration; earlier committed versions remain recorded and retries resume from that history. Do not automatically repair checksums or edit applied migrations to force progress. The recovery boundary is the cache reset of section 7.4, not a backup; ordinary changes move forward through new versions. The old unscoped application must not run against the new schema.

## 7.4 Legacy data reset

Because the database is a disposable cache (UD-016), legacy unscoped records are not migrated, archived or reconciled, and their ownership is never inferred. When the gate finds legacy unmanaged tables, or an unrecognized schema, it stops legacy writers and scheduled work, drops the cached tables, applies the complete migration chain and starts with an empty live account dataset. The first credential validated through the backend boundary creates or reuses its verified account and populates every source by fresh synchronization from the GW2 API. Until a source has been synchronized it is simply missing and refreshes on demand under the existing freshness rules; an empty table is not a successful empty synchronization.

No backup, import manifest, provenance ledger, restricted archive schema, runtime-role separation for archived data, or reconciliation step is required, and none of them gates the cutover. Account creation and activity timestamps start from the reset; missing history stays unknown.

Verification follows `TEST_STRATEGY.md` and `QUALITY_METRICS.md`: real isolated PostgreSQL runs the production migration chain for empty and supported legacy schemas and proves scoped constraints, transactional publication, snapshot consistency, and that a legacy schema is reset to the empty-schema result. Survival of pre-existing cached rows is deliberately not tested. Controlled offline upstream fixtures cover identity and failed/partial validation; application and HTTP boundaries cover scope propagation under section 8.1. QA independently owns the concrete story plan. Keep existing domain invariants and coverage/reporting requirements; this decision changes no measured baseline.

---

# 8. Guild Wars 2 API, Credentials, and Account Identity

All Guild Wars 2 API communication is backend-owned. The frontend does not call ArenaNet directly.

For the target multi-user deployment:

- each user supplies their own Guild Wars 2 API key through the browser,
- the backend receives the key transiently when an account-specific API operation requires it,
- the backend resolves the stable Guild Wars 2 account identity and uses that identity for persisted account scope,
- API keys are not persisted in PostgreSQL or other durable server-side application storage,
- replacing a key for the same account must not create duplicate account data,
- logs/errors must not unnecessarily record API keys.

Browser key retention and credential transport follow section 8.2; ADR-009 records the storage alternatives and rationale.

Validated GW2 API-key possession is sufficient application identity for Phase 6. There is no separate application user, password, identity provider, or account-linking model. The resulting principal represents access to one GW2 account, not proof of a particular person's identity. Section 8.1 owns the access contract; ADR-008 records the rationale.

The current local/single-user application may continue using `GW2_API_KEY` from local environment configuration during migration.

## 8.1 Validated account access

### Identity proof and session lifetime

The backend establishes access only after successful HTTPS calls to `/v2/account` and `/v2/tokeninfo` using the same transient key. The canonical account identity is the account response's persistent `id` GUID; neither its display name, the token-info identifier, a key hash, nor an identifier supplied by the browser establishes account identity. Validate the response shapes and required identity permission before issuing access. Capture the key's permissions and any expiry/endpoint restrictions from token info; effective access must never exceed them. Replacement keys resolving to the same GUID reuse that account's dataset without transferring privileges from an earlier key.

Issue a fresh opaque session identifier generated with a cryptographically secure random source (at least 128 bits of entropy). Keep only its verifier and immutable principal (account GUID, effective grants/restrictions, validation time and expiry) in bounded process memory, without the GW2 key. Use a host-only, HttpOnly, Secure, SameSite=Strict session cookie in deployed HTTPS; permit non-Secure transport only for explicit localhost development. Do not accept session identifiers from URLs or caller-provided account fields. Protect state-changing browser requests, including session establishment, with same-origin/CSRF validation. Session establishment and account responses use `Cache-Control: no-store`; neither keys nor session identifiers belong in logs, error text, URLs or shared HTTP caches.

Sessions expire absolutely 15 minutes after successful upstream validation, or at an earlier credential expiry. Reads, polling and refresh success do not extend them. Renewal requires presenting the key and repeating validation; there is no refresh token or durable session store. Logout revokes the session server-side; expiry, eviction and backend restart require a new proof. A valid session can authorize cached reads while ArenaNet is unavailable. New proof or renewal cannot succeed during that outage, and an expired session cannot fall back to cached identity. This bounded lease deliberately permits up to 15 minutes of cached access after an upstream key revocation not yet observed by the application; immediate external revocation detection is not promised. A definitive upstream invalid-credential response for a credential used with a session revokes that session immediately; a transient upstream failure does not. The duration is an engineering choice balancing revocation delay and upstream traffic, not a measured performance result.

### Request and application scope

Every account read, calculation, account synchronization admission, account status view and account task/result lookup requires a live session. Authorization precedes account lookup, cache access, calculation and task admission. Required grants are defined by each operation's account input sources and their upstream permissions/restrictions; cached rows synchronized with a more privileged key do not bypass the presenting session's grants. A calculation needs authorization for all account sources it consumes, including a cached result or detail. Freshness and authorization are independent checks: fresh persisted data never proves access, and a live session never proves freshness.

The HTTP boundary resolves the session to a trusted immutable account context and passes it explicitly to application use cases. Application ports/adapters require that scope for account reads/writes; each nested resource (including a selected character), source marker, snapshot, account-dependent cache and result generation belongs to it. Browser account IDs may only be consistency assertions and must match the resolved context; they never select an authorized account. There is no default/last-used/configured account fallback in multi-user routes. Avoid mutable process-wide current-account state or request-thread state inherited implicitly by workers. Domain rules receive scoped data without sessions, API keys or authentication dependencies. Pure global-data use cases remain account-independent.

Account-specific upstream work additionally requires a transient key supplied for that operation: a session is not an upstream credential. Revalidate the supplied key's identity and operation grants before admission; it must resolve to the session's GUID. Insufficient permission or a mismatched account is rejected before work or writes. Hold the key only in the admitted operation's private memory until completion/failure, never in task metadata, status, persisted queues or results, and release references on every terminal path. Cached calculations needing no account refresh require no GW2 key. If stale-while-revalidate needs account work but no key was supplied, return the authorized usable result with an explicit credential-required refresh state (or pending/no-result when no usable input exists); do not claim a task was admitted or silently use another caller's key. Supplying a key and retrying follows the existing bounded recovery contract in section 9.1. Section 8.2 defines browser key retention and transport.

### Failures and asynchronous access

Missing, unknown or expired sessions and definitively invalid keys map to 401; authenticated requests lacking operation grants or supplying a mismatched account/key map to 403. Upstream validation unavailability maps to a safe retryable 503 (including upstream rate limiting with bounded retry guidance), not successful access. Malformed validation responses fail closed. Authorization failures disclose no account existence, content or freshness. A foreign, expired or unknown account-task identifier returns the same 404 after session authentication; task IDs are references, never bearer credentials.

At admission, capture the verified account GUID and required operation grants in the task. The worker uses that immutable scope for all persistence and result publication, regardless of later browser account changes, logout or session expiry. An admitted operation may finish after its session ends; that grants no further HTTP access. Status, result, error, history/listing and System Status projections all enforce the requesting live session's account and the task's required grants. Another adequately authorized session for the same GUID, including one proved with a replacement key, may observe retained work. Account scope is preserved on success and failure alike; task errors expose no raw upstream response or secrets.

Coalesce account work only within the same verified account, never across accounts or keys of unknown identity. Any additional demand must independently pass grant checks and may use the active task's credential only when that credential covers the added sources; otherwise wait/recheck and admit separately with a validated suitable credential. Shared TP/global tasks remain globally coordinated, but their public status contains only global execution facts. Account-derived requested item sets, caller identities and calculation associations remain in scoped request context and never appear in shared task/history payloads. Expose only the requesting account's task associations in freshness envelopes and diagnostics.

### Verification boundaries

Preserve `TEST_STRATEGY.md` and `QUALITY_METRICS.md`: application invariants cover scope propagation and no unauthorized work; HTTP contracts cover authorization/failure mapping and successive/concurrent request isolation; real PostgreSQL integration verifies account query/write isolation and stable-identity reuse. Controlled upstream fixtures verify identity, grants and failed proof; normal tests require no live GW2 API. Async verification uses the real in-process facility with controlled work, covering account ownership on admission, execution and observation. QA owns the concrete plan; this decision neither supplies test cases nor changes coverage baselines or reporting requirements.

---

## 8.2 Browser credential lifecycle and transport

### Storage and lifetime

Retain the GW2 API key only in private JavaScript memory for the current document. A small credential holder owns it outside serializable/reactive application stores; the entry field is masked and cleared after submission. Never place it in localStorage, sessionStorage, IndexedDB, cookies, Cache Storage, service-worker queues, history/router state, URLs, telemetry, console output or persisted application snapshots. No remember-key option or application-managed encrypted key vault is introduced.

SPA route changes retain the key. Reload, document navigation, a new/duplicated tab, tab close and browser restart require entry again; no tab copies or requests another tab's key. Clear references, entry fields and account state on pagehide; a back/forward-cache restore starts at the credential gate and requires new proof. Do not rely on unload delivery or garbage collection for secure erasure. Memory retention reduces application-controlled persistence, but cannot prevent active XSS, privileged extensions, browser/OS memory capture or user-controlled password-manager storage. Keep the key out of general frontend state and diagnostic tooling and apply normal XSS defenses; memory is not an XSS security boundary.

The key lifetime is separate from section 8.1's backend lease. While the document retains a key, a serialized renewal may repeat upstream proof when that lease expires; it does not extend the old session. Without a key, even a surviving cookie does not automatically restore browser account state: require entry and fresh proof. A definitive invalid-key response clears the key and account state. Lease expiry alone can retain the key for renewal; transient validation failure can retain a candidate in memory for explicit retry, without restoring old account data. An authorization/grant failure does not by itself prove the key invalid.

### Clear, replace and stale answers

Clear key/logout immediately drops the key and all account-derived browser state and requests server-side session revocation. Local clearing succeeds even offline; if revocation cannot reach the server, the old cookie may remain usable until the existing lease expires. Do not claim offline logout revoked the server lease. Clearing does not delete persisted account records or cancel already admitted backend work.

Submitting a replacement first invalidates the previous browser context and releases the old key; it then validates the candidate. Failed proof leaves the account view empty. Even a replacement resolving to the same GUID requires a fresh session and reloading under its effective grants. It reuses the backend dataset through section 8.1, never the previous browser authorization or result state.

Maintain a monotonically changing local credential generation. Clear, replacement, logout and loss of authorized context advance it before asynchronous work can commit. Capture it with every proof, read, calculation, detail, polling, retry and renewal chain; accept a completion only for the current generation and validated access context. Abort obsolete requests and stop timers/polling, but also reject their success, error and cleanup callbacks: cancellation alone is insufficient. Clear account rows, details, selected character, account-source settings, freshness, task associations and account-dependent caches. Only global metadata and settings independent of account identity may survive. A late A response cannot repopulate B, or a later A generation.

### Shared-cookie tab coordination

The section 8.1 cookie is shared within its browser cookie scope; memory-only key retention does not make authentication tab-local. On proof/renewal return a fresh non-secret access-context identifier bound to that exact server session and effective grants, distinct from the secret cookie identifier. Keep it in document memory. Every subsequent account request, including logout and task polling, supplies it in `X-Account-Context`; compare it with the authenticated session before account work or disclosure. Missing/mismatched context fails closed (403 after session authentication), with no account data. This identifier is a consistency assertion, never an independent credential or browser-selected principal. Account responses identify their access context so the browser can reject mismatched answers.

Use same-origin BroadcastChannel notifications containing only an invalidation event to clear other live tabs on clear/replacement and session rotation. Never broadcast keys, cookies or account contents. A notified tab drops its key and account state and waits for explicit entry; it must not automatically re-prove its old key and switch the shared cookie back. Mask account content while suspended and verify the existing context before resuming account display/work. Notifications improve prompt clearing; server context checks and local generation checks remain mandatory if delivery is delayed or absent. A context mismatch clears the local view and requires explicit entry, without automatically logging out another tab's session. Concurrent proof responses can still replace the shared cookie out of order; a stale response cannot commit browser state and any resulting context mismatch requires fresh proof rather than silently adopting that cookie's account. Independent simultaneous accounts in tabs sharing cookies are not promised; separate browser profiles provide separate cookie scopes.

### Credential transport boundary

Send the key in the dedicated `X-GW2-API-Key` request header only for session proof/renewal and explicit account-specific operations that may call GW2. Do not attach it through a blanket HTTP interceptor, or to global data, cached-only reads, polling, status or logout. If a cached read reports credential-required refresh, the browser may issue the explicit keyed operation within section 9.1's bounded recovery; do not turn a cached-page response into an upstream-validation prerequisite. The browser uses same-origin `/api` routes, HTTPS outside explicit localhost development, and refuses redirects for keyed requests. Preserve section 8.1's cookie, origin/CSRF and authorization checks; a custom header alone is not authorization.

The HTTP adapter extracts the credential into private transient operation input, separate from domain data and public DTOs. No echo in responses, exception text, task status, request dumps or tracing. Redact the credential header in application, proxy and observability configuration; disable credential request capture. Credential-bearing requests and all account responses use `Cache-Control: no-store` and bypass application/service-worker caches and offline replay. Only the backend adapter contacts GW2; its outbound authentication and errors require the same redaction. Release references on every terminal path as required by section 8.1. This establishes no new durable credential/session store or service.

### Verification ownership

Preserve `TEST_STRATEGY.md` sections 12 and 31 and `QUALITY_METRICS.md`: frontend state verification covers lifecycle and superseded answers, real-browser evidence covers document/tab/cookie behavior, and backend HTTP/application verification covers context checks and secret handling. Existing real PostgreSQL isolation requirements remain applicable; mocked browser evidence does not prove persistence isolation. QA independently prepares the concrete plan. No coverage baseline or test-layer requirement changes here.

---

# 9. Synchronization and Long-Running Operations

Synchronization is an application-level/backend responsibility. The frontend may trigger permitted operations and display status/freshness; the backend owns execution order, persistence, error handling, and synchronization state.

Account refreshes run for the account in the current single-user installation. Crafting Profit's explicit **Refresh data & results** workflow refreshes bank, material storage, account recipe knowledge, and character crafting/recipes/inventory before recalculating. It deliberately does not fetch account Luck, which the Crafting Profit calculation does not need. The full account refresh remains available in System Status. Account state is intended to become user/account scoped; current account data is not yet multi-tenant.

Trading Post prices are a globally shared cache. A calculation supplies its feature's relevant item set to the shared refresh/cache pipeline; stored quotes younger than ten minutes are reused, and only missing/stale required IDs are fetched in existing GW2 API batches. Profit and Discovery may select different required IDs, but do not own separate price caches or fetch mechanisms. Prices are refreshed on demand rather than by a full-catalog timer.

Global GW2 metadata and the crafting graph are shared. The backend checks cheap recipe and TP-tradeable ID lists every six hours by default (initial delay one minute), compares them with stored IDs, and downloads detailed recipe/item data only when those IDs changed. This detects additions/removals; it does not detect an in-place recipe edit when the recipe ID set is unchanged. Recipe additions/removals trigger the necessary detail persistence; the graph cache is initialized on first use if absent and is rebuilt during refresh only after changed recipe data has been persisted. The interval is configurable using `GW2_GLOBAL_REFRESH_INTERVAL_MS` and `GW2_GLOBAL_REFRESH_INITIAL_DELAY_MS`. Manual System Status execution and the scheduler submit the same application service and share in-process duplicate-task protection.

The System Status page is an administrative diagnostics/maintenance area, not a prerequisite for normal feature use. It groups account refresh time/scope, global check/change/recipe/graph timestamps, and shared TP cache counts/fetch time into health cards with clear OK, Problem, Running, or not-run states. Manual actions are limited to account refresh and global-data check; TP prices refresh on demand through feature workflows. It shows currently tracked task states and last global failure. Refresh timestamps/task history are not durable telemetry. This is not a general monitoring platform.

The current runtime is single-user and single-backend-instance. In-process duplicate-job prevention is sufficient. Future account data will be scoped/refreshed per account/user; GW2 global metadata, TP prices, and the crafting graph remain shared. If multiple backend replicas are introduced, global scheduled work and refresh deduplication will then require shared coordination, for example PostgreSQL-backed leases or advisory locks. That coordination is explicitly future work; do not introduce Redis, distributed locks, or a job queue for the current deployment.

Per resolved `agent/user-decisions/UD-007-http-long-running-sync-approach.md`:

- Guild Wars 2 API synchronization uses asynchronous backend tasks with status reporting.
- Other endpoints should be measured with representative real data; keep consistently short operations synchronous and use backend tasks when runtime materially risks interruption or connection timeout.

Do not introduce a distributed queue/job platform without a concrete need.

## 9.1 Crafting Profit cached-result and refresh contract

Per resolved UD-014 (which delegates to UD-015) and UD-015, Crafting Profit uses stale-while-revalidate. This is a target contract, not evidence that the current blocking account/TP request paths implement it. ADR-007 records the technical decision and alternatives.

The application service captures usable cached inputs and performs the ordinary backend calculation without waiting for external refresh. Expired inputs remain eligible for a provisional result; expiry is not loss of the last successful data. Missing account sources must not be synthesized, and a successfully synchronized empty source is usable. Missing TP quotes retain the existing explicit price-unavailable domain semantics, never zero-cost substitutes. If the available inputs cannot produce a usable result, return a pending/no-result state and refresh asynchronously; a loading placeholder is not a completed page.

The application layer owns the required account-source set, feature-relevant TP item IDs, freshness checks, task admission and calculation-input capture. Crafting Profit requires bank, materials, account recipe knowledge and characters; Luck remains outside that scope. Keep source-specific account completion markers and the configured account TTL. Keep the shared ten-minute TP policy and existing batched fetch path. The domain receives ordinary calculation inputs and does not depend on clocks, task handles, HTTP freshness metadata or external response models.

Each successful result carries an application/HTTP freshness envelope alongside the unchanged economic result and domain states. It identifies the calculation/request context and the input versions actually used, required account sources with their successful completion times and fresh/stale/missing state, and coverage/freshness of the required TP item set. Include backend evaluation time, whether the result is provisional, outstanding refresh work, task-status references and safe failure/retry information. Account and TP states remain distinguishable; task status is execution information, not a substitute for input freshness. Exact DTO spelling belongs to the bounded API contract, but these semantics are required. Metadata must describe the captured inputs, not newer live markers read after calculation. Neither reads nor task admission advance freshness markers.

Cached input capture must use last-successfully-published source data. Readers must not combine partially written refresh data with an old successful marker or mix input generations during one calculation. The application/adapters must retain a last-good read view while refreshing and publish a source's data and successful version together; a bounded immutable application snapshot is sufficient for this single-process runtime where existing persistence writes are incremental. This does not require durable result history or a database-wide transaction spanning upstream calls. A failed source refresh leaves its last-good view and marker intact. Summary and resolution detail must use compatible input versions and calculation settings per section 10.2; a detail calculated against newer inputs must be identified as such and associated with a matching updated row, rather than presented as the explanation of an older provisional row.

Account refresh and TP refresh remain separate operations in the existing asynchronous in-process task facility. In Phase 6, preserve at most one unfinished ACCOUNT_SYNC task per verified account per backend process, applying section 8.1's credential and visibility rules. Account coordination, accumulated demands and completion markers must all carry that account scope; different accounts do not share an exclusion key. When an existing account task has insufficient scope, await its terminal state and recheck the caller's required sources; admit successive work for the remaining sources without concurrent writes for that account. Do not treat that task's success as success for sources it did not refresh. TP work uses independent shared operation coordination: coalesce concurrent required-ID demands, reuse quotes younger than ten minutes, and after active work finishes recheck any uncovered missing/stale IDs and admit remaining work. Profit and Discovery retain one TP repository and fetch mechanism; no feature-specific TP cache or distributed platform is introduced.

The browser polls advertised task status and automatically resubmits the calculation with the current settings when relevant refresh work succeeds. The backend rechecks required-source and TP coverage on each resubmission; changed account inputs can change relevant TP IDs and require further refresh before a final result is ready. Keep cached calculations synchronous unless representative measurement establishes that calculation itself needs a task under UD-007. Do not introduce stored server-side calculation jobs merely to replace the browser's HTTP request. A result is final only when its captured required account inputs and TP coverage are confirmed fresh and the calculation on those inputs has completed. Known unavailable TP prices remain explicit domain states; only a successful authoritative fetch outcome may establish that unavailability, rather than an absent cache entry being declared fresh.

The HTTP boundary returns a successful calculation response when usable cached results exist, including its provisional envelope; it must not reject that result solely for TTL expiry. A no-usable-cache response is an asynchronous pending response (202 with freshness/task references and no fabricated result). Existing task-status routes report running/succeeded/failed/unknown states. After task completion the client must request/recheck application results, not infer a final calculation from task status. Framework freshness interception must permit this feature contract; this decision does not silently change every other feature's response policy.

The frontend renders the provisional/refresh/failure states prescribed by UD-015 and preserves settings, filters, valid selections and sorting through replacement. It removes the refreshing indicator only after rendering the final calculation for the current request context. Correlate requests and input versions so late task/detail/calculation responses cannot overwrite newer settings or selections. Refresh failures retain the last usable displayed result, stop the animation and expose a retry; no-cache failures remain explicit empty/error states. Preserve one five-minute overall recovery deadline for an automatic refresh cycle, including successive account/TP tasks, status checks and recalculation; task reuse or additional missing sources must not reset it. Unknown/expired task references, transport failure and deadline exhaustion terminate visible recovery safely. An explicit user retry starts a new bounded cycle and rechecks freshness; it does not blindly repeat all previously completed work. A client deadline does not imply that a backend task was cancelled or authorize overlapping writes. This bounds the UI protocol without promising cancellation of upstream work.

---

# 10. Authoritative Calculation and Result Boundaries

## 10.1 Crafting graph and calculations

The crafting graph and Crafting Profit/Discovery calculations remain backend/domain concerns. The frontend must not construct the graph or independently recalculate their economic results.

Internal graph caching/rebuild strategy is an implementation decision as long as domain results remain correct.

## 10.2 Resolution tree

The resolution tree is an authoritative backend/domain result explaining how a crafting result was obtained and costed. The frontend renders it rather than reconstructing it.

The tree and summary result must use compatible calculation context/snapshots, preserve domain states and selected sourcing decisions, and associate asynchronous detail responses with the request/result that initiated them so stale responses cannot overwrite newer selections.

For Crafting Profit, the tree explains the full output quantity represented by
the fresh detail response's selected row. `craftableCount` is recipe executions;
`outputCount` is output items per execution, so the requested root quantity is
the row's counted executions multiplied by the recipe output count. The backend
must resolve those executions through the ordinary resolver so inventory,
storage, buying limits, daily operations, recipe eligibility and recursive
requirements are applied normally. It must stop at the counted execution
quantity rather than making an additional speculative craft. A zero-count row
may return the first blocked attempt, explicitly identified as such. Discovery
resolves one attempt for the selected character; its output quantity still
determines root output quantity and revenue. The response's row, tree and `treeBasis`
must make these distinctions explicit. No tree quantity or cost may be derived
or scaled in the frontend.

Missing/unavailable detail data must remain explicit; the frontend must not fabricate a result. Exact DTO fields and transport mechanics belong in feature/API specifications.

## 10.3 Domain states

Domain states such as `INVENTORY`, `CRAFT`, `BUY`, `BLOCKED`, `PRICE_UNAVAILABLE`, `DAILY_LIMIT`, and `UNVALUED_NONTRADEABLE` originate from backend/domain behavior. The frontend owns their visual representation, not their meaning.

---

# 11. Configuration and Secrets

Runtime configuration must not be hard-coded into source files. Server-owned configuration and secrets are supplied through environment variables or equivalent deployment configuration and must never be exposed to the frontend.

Per-user Guild Wars 2 API keys follow section 8's browser-held/transient-backend model and are not server-owned persistent configuration.

---

# 12. Deployment and Containers

The initial target is a small single-host Docker deployment using Docker Compose.

```text
Browser
   |
   v
Frontend (public)
   |
   v
Backend (application API)
   |
   v
PostgreSQL (internal)
```

The backend also communicates with the external Guild Wars 2 API over HTTPS. PostgreSQL does not need public exposure.

The application must remain portable to a normal Docker-capable host. A home server, VPS, NAS/container host, or suitable cloud VM may be used. Oracle Cloud Free Tier / Always Free is only a deployment candidate and must be re-evaluated when deployment work is reached; application architecture must not depend on Oracle-specific services.

Cloudflare-related hosting is another possible future option to evaluate; neither Cloudflare nor any specific Cloudflare product is selected. Hosting remains undecided until the deployment phase.

Do not introduce Kubernetes, microservices, or additional supporting services without a concrete requirement.

---

# 13. Testing Architecture

Detailed testing strategy belongs in `TEST_STRATEGY.md`. Architecture-level expectations are:

- domain tests verify shared business rules without database, HTTP, UI, or Guild Wars 2 API dependencies,
- application tests verify use-case orchestration across domain and ports,
- persistence integration tests use real PostgreSQL where schema/query semantics matter,
- external API adapter tests use controlled/captured payloads; optional live smoke tests stay outside the deterministic default suite,
- API tests verify transport mapping, validation, and mapped failures without duplicating domain-rule tests,
- frontend/component/browser tests verify presentation, interaction, frontend-owned state, and the explicitly frontend-owned Ecto calculation,
- end-to-end tests cover selected critical deployed flows.

The browser frontend is the canonical UI. JavaFX-specific tests may be removed with obsolete JavaFX production code they exclusively verify. Shared backend/domain behavior must retain appropriate non-JavaFX coverage.

---

# 14. Migration and Code Reuse

Migration should preserve working behavior while establishing the target boundaries incrementally. The detailed sequence belongs in `ROADMAP.md`.

Existing code should be:

- retained when it correctly implements required behavior and fits the target boundaries,
- refactored when coupling prevents safe reuse,
- replaced or removed when obsolete, incorrect, or more expensive to isolate than replace.

The browser frontend is the sole supported user interface. Remove the obsolete JavaFX UI code, tests, resources, dependencies, entry points and support code with no remaining consumer during Phase 6, before its health review. Preserve shared domain/application/backend functionality used by the browser; desktop-only features do not require replacement web features unless the product specification requires them. Do not introduce a replacement desktop framework or retain a desktop compatibility layer.

The obsolete backend-owned Ecto calculation path may likewise be removed while preserving the frontend-owned Ecto behavior defined in section 4.2.

---

# 15. Architecture Simplicity and Quality

When several designs satisfy the requirements, prefer the simplest design that preserves correctness, is testable, has clear ownership, is understandable by a small project team, and supports bounded AI-assisted development.

Near milestone completion, project-health reviews should check for:

- dependency/boundary violations,
- duplicate or obsolete implementations,
- repository/documentation drift,
- test coverage appropriate to changed boundaries,
- relevant performance regressions,
- security/configuration conflicts,
- temporary migration structures that can now be removed.

Review workflow and story disposition belong in the agent/planning documentation.

---

# 16. Technology and Architecture Decisions

## Decided

- **Frontend:** Vue 3 + TypeScript, strict type checking; see ADR-001.
- **Backend:** Java 25 and Spring Boot; see resolved `UD-006`.
- **Database:** PostgreSQL.
- **Schema migrations:** Flyway with versioned PostgreSQL SQL migrations; sections 7.3-7.4 and ADR-010.
- **Runtime packaging:** frontend, backend, and PostgreSQL containers; Docker Compose for the initial small deployment.
- **Shared business-logic ownership:** backend/domain/application layers, with the explicit frontend-owned Ecto Salvage exception in section 4.2.
- **Supported UI:** browser frontend only; JavaFX and a replacement desktop UI are not planned.
- **GW2 API ownership:** backend adapter boundary; browsers do not call ArenaNet directly.
- **Multi-user persistence:** one shared database with shared global data and account-scoped data keyed by stable Guild Wars 2 account identity.
- **Per-user GW2 API keys:** browser-held and supplied transiently when required; not durable server-side application data.
- **Application identity/access:** validated GW2-key possession with bounded account sessions; section 8.1 and ADR-008. No separate application authentication infrastructure.
- **Browser key storage/transport:** document-memory-only retention, explicit keyed requests and generation/context checks; section 8.2 and ADR-009.
- **Global metadata checks:** backend-owned scheduled ID-list checks; implementation default six hours, configurable by environment.
- **Trading Post prices:** globally shared on-demand freshness cache, ten-minute TTL; no full-catalog timer.
- **Hosting portability:** no unnecessary provider-specific dependency.
- **Potential hosting options:** Cloudflare-related infrastructure may be evaluated with portable hosts; no provider or product is selected.

## To Be Decided

- Reverse proxy, if required by deployment.
- Inactive-account retention threshold, only if measured impact justifies automatic cleanup.
- Hosting provider.

---

# 17. Performance Requirement

Crafting Profit is performance-sensitive. Per UD-013 through UD-015, the initial complete usable browser page calculated from available cached inputs must take at most 7 seconds from navigation/request initiation, including when those inputs have expired and the displayed calculation is provisional. The span includes cached-input reads, backend calculation, persistence work required for that response, transport, required controls and complete result rendering. A first row or loading placeholder is not completion. Account and TP refresh must not become prerequisites for this cached-page response.

Subsequent asynchronous account/TP refresh and the calculation/rendering that replaces the provisional result are excluded from the initial seven-second interval. They require separate reproducible timing evidence, including refresh start to terminal outcome, refreshed calculation/rendering, and total navigation-to-final-result time. Initial completion must be associated with its calculation context/version so later replacement is measured separately rather than extending the initial-page quiescence sample. No additional numerical refresh-speed budget is established by these decisions; the bounded recovery contract in section 9.1 still applies. A no-usable-cache loading state cannot prove seven-second compliance: report readiness wait and eventual calculation/rendering separately and explicitly identify the unavailable cached-page scenario.

Verification must distinguish fresh caches, expired account inputs, expired TP quotes, both expired, overlapping insufficient-scope account work, missing inputs, and failure/timeout recovery. Include representative real-data startup and repeat navigation with the default All scope and recorded effective settings; favorable fresh-cache measurements alone cannot establish the updated contract. Preserve economic and domain invariants across provisional and refreshed results. Detailed test and measurement methodology belongs in `TEST_STRATEGY.md`; implementation QA independently supplies bounded story plans.

Reproducible measured compliance covering the required scenarios, together with the required quality gates, is sufficient for Phase 5 performance acceptance under UD-013. There is no additional manual Product Owner acceptance gate. Historical fresh-cache evidence does not accept the new stale-while-revalidate behavior or close unresolved measurements. Phase closure still requires the bounded health review in section 21 and planner disposition of blocking findings.

## Application resource measurements

Measure the target application's actual process memory (idle and peak), operation CPU time, workload CPU utilization, HTTP p95 latency and throughput, database size/reads/writes/connections, network traffic, and background-job duration, CPU, memory and frequency where technically measurable. Use representative crafting, account/global synchronization, price refresh, database, HTTP, idle and concurrent workloads with comparable fixture and environment metadata. Record unavailable measurements explicitly rather than estimating them. These measurements inform eventual free-tier or low-cost hosting decisions; keep deployment portable and provider-independent, with no provider selected.

Run a lightweight dedicated GitHub Actions performance workflow on normal development activity and manually on demand. Display results and diagnostics in its job summary, retain useful detailed artifacts, and identify commit, timestamp, workload and environment. Compare only compatible prior measurements. The workflow and missing or failed benchmarks are informational: they do not affect functional/security gates, pull requests, merges or story acceptance. No daily/nightly schedule or additional monitoring service is required.

Before containers exist, measure application processes and database activity only. When container infrastructure is introduced, extend the measurements to actual container resource use, startup-to-readiness time and image size. Label process and container measurements distinctly; do not introduce containers solely for measurement.

---

# 18. Documentation Boundaries

- `DOMAIN_SPEC.md` owns authoritative business/domain behavior.
- `FRONTEND_UX_GUIDELINES.md` owns reusable browser UX/UI requirements.
- `TEST_STRATEGY.md` owns detailed verification procedures.
- `ROADMAP.md` owns migration sequencing and milestones.
- `docs/crafting/README.md` is the permitted human-readable explanation of crafting rules and must remain aligned with `DOMAIN_SPEC.md` when user-visible crafting behavior changes.
- `docs/crafting/GLOSSARY.md` is the canonical player-facing terminology and label mapping; reference it from feature documentation rather than copying its definitions.
- Agent/runtime orchestration is development infrastructure and belongs in agent/runtime documentation, not deployed application architecture.

---

# 19. Rules for AI-Assisted Implementation

When modifying or creating code:

1. Follow the ownership/boundary rules in this document rather than recreating them in individual stories.
2. Follow `DOMAIN_SPEC.md` for business behavior.
3. Keep controllers/API endpoints thin and infrastructure concerns outside the domain.
4. Do not duplicate backend-owned calculations in the frontend; respect the explicit Ecto exception in section 4.2.
5. Keep server-owned secrets out of the frontend and follow section 8 for per-user Guild Wars 2 API keys.
6. Do not silently finalize decisions listed as TBD.
7. Document major architecture changes explicitly and remove obsolete paths when a migration decision makes them unnecessary.

---

# 20. Status

This document defines the current target architecture and is stable enough to guide migration planning, testing, repository restructuring, and AI-assisted implementation.

Implementation details not fixed here remain open until they are required. Domain behavior must not be inferred from this document when `DOMAIN_SPEC.md` is the authoritative owner.

---

# 21. Project Health Review

Before a phase is closed, perform a bounded review against that phase's exit criteria. Check the actual implementation and its evidence for architecture/boundary drift, duplicate or obsolete paths, documentation consistency, appropriate tests, relevant performance and security/configuration risks, and temporary migration code that is now removable. Reconcile current-state documents with completed work: remove confirmed-resolved findings, obsolete descriptions, and historical detail that no longer informs current decisions. Remove duplicated information from documents that do not own it, keeping the current authoritative statement in its owning document and linking to it where needed. Keep documentation concise without deleting still-relevant requirements, decisions, or evidence. Record concrete findings, evidence, impact, and whether each finding blocks closure in the phase review story. A review does not itself close a phase: blocking findings and proposed criterion transfers require explicit planner disposition. Detailed test procedures remain in `TEST_STRATEGY.md`; planning workflow remains in `agent/PLANNER_INSTRUCTIONS.md`.
