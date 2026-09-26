# GW2 Tool — Target Architecture

## 1. Purpose

This document defines the intended target architecture for the GW2 Tool.

It describes the structural boundaries the system should move toward while leaving implementation details open where no decision has yet been made.

The goal is to make the application:

- easier to understand,
- easier to test,
- safer to change,
- deployable as a web application,
- containerized,
- suitable for controlled work by Claude Code or other coding agents.

This document does not define domain behavior. Domain behavior is defined in `DOMAIN_SPEC.md`.

---

# 2. Architectural Goals

The target architecture should achieve the following:

1. Separate business/domain logic from UI, database, and external APIs.
2. Replace the current desktop-only UI with a web-based interface.
3. Run the application as a small set of independent containers.
4. Keep PostgreSQL as the persistent database.
5. Make the backend the single owner of business logic.
6. Make external integrations replaceable through adapters.
7. Allow domain logic to be tested without database, network, or UI dependencies.
8. Avoid unnecessary architectural complexity.

The target is a small maintainable application, not a distributed microservice platform.

---

# 3. High-Level Target Structure

```text
User Browser
     |
     | HTTP
     v
+----------------------+
| Frontend Container   |
|                      |
| Web UI               |
| Technology: see 4.1  |
+----------+-----------+
           |
           | HTTP / JSON
           v
+----------------------+
| Backend Container    |
|                      |
| API                  |
| Application Services |
| Domain Logic         |
| Adapters             |
+----+-------------+---+
     |             |
     |             | HTTPS
     |             v
     |       Guild Wars 2 API
     |
     | SQL
     v
+----------------------+
| PostgreSQL Container |
+----------------------+
```

---

# 4. Container Model

The target deployment consists of three primary application containers.

## 4.1 Frontend Container

Responsibilities:

- serve the web user interface,
- display data received from the backend,
- collect user input,
- call backend API endpoints,
- present calculation results and recipe trees.

The frontend must not contain authoritative business logic.

Examples of logic that must remain in the backend:

- crafting profitability calculations,
- recursive recipe resolution,
- opportunity-cost calculation,
- recipe eligibility,
- material valuation,
- daily-item rules.

### Technology

**Status:** DECIDED — Vue 3 with TypeScript, using strict type checking.

Use Vue single-file components with the Composition API and `<script setup lang="ts">`
as the default component convention. Build a browser-rendered client of the existing
backend HTTP API; static frontend assets fit the existing frontend container without
requiring a server-side JavaScript application runtime. Select compatible stable
dependency versions when implementing, lock them reproducibly, and include component
and TypeScript type checking in verification. Build tooling and optional UI libraries
are separate decisions, not selected here.

This is the simplest overall fit by architectural judgment for the small, single-maintainer
UI: consistent component conventions for tables, recursive tree presentation and
interaction state, with typed API contracts for bounded agent changes. React with
TypeScript remains viable but offers no required capability that outweighs the additional
application-convention choices here. Decision history, evidence and alternatives are in
[ADR-001](architecture/decisions/ADR-001-frontend-framework-and-language.md).

Framework dependencies stay inside the frontend. Backend/domain architecture and HTTP
contracts must remain independent of Vue; TypeScript types describe transport and
presentation data, not a second implementation of domain rules. Static types do not
validate received JSON or replace backend validation. Existing frontend responsibilities,
secret boundaries and the full-page performance requirement remain binding.

---

## 4.2 Backend Container

The backend is the core application runtime.

Responsibilities include:

- exposing the application API,
- executing use cases,
- enforcing domain rules,
- running crafting calculations,
- synchronizing data with the GW2 API,
- loading and storing data,
- coordinating repository access,
- providing explainable calculation results to the frontend.

The backend must be the single authoritative location for business behavior.

### Technology

A Java-based backend is currently preferred because the existing application and domain logic are already written in Java.

The exact framework is not yet decided.

Possible candidates may include:

- Spring Boot,
- Quarkus,
- another suitable Java web framework.

**Status:** TBD

The architecture must not rely on framework-specific behavior inside the domain layer.

---

## 4.3 PostgreSQL Container

PostgreSQL remains the persistent application database.

Responsibilities:

- global Guild Wars 2 item and recipe data,
- Trading Post data,
- synchronized account data,
- character data,
- application persistence.

The database is infrastructure.

Domain logic must not directly depend on PostgreSQL-specific APIs or SQL.

Database access must occur through repository interfaces / persistence adapters.

---

# 5. External Guild Wars 2 API

The official Guild Wars 2 API remains an external dependency.

It is not part of the container stack.

```text
Backend
   |
   | HTTPS
   v
Guild Wars 2 API
```

The backend owns all GW2 API communication.

The frontend must never call the GW2 API directly.

Reasons include:

- API-key handling,
- consistent synchronization behavior,
- caching,
- rate-limit handling,
- easier testing,
- separation of external API models from the UI.

---

# 6. Backend Internal Architecture

The backend should be organized into clear layers.

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
       |
       +------------------+
       |                  |
       v                  v
Persistence Adapter   GW2 API Adapter
       |                  |
       v                  v
PostgreSQL           Guild Wars 2 API
```

Dependencies should point inward toward the domain.

---

# 7. Domain Layer

The Domain Layer contains the core business rules.

Examples include:

- crafting resolution,
- crafting graph traversal,
- material consumption,
- craft-vs-buy decisions,
- opportunity-cost calculation,
- non-tradable item valuation,
- recipe selection,
- daily-item behavior,
- discovery eligibility,
- profit calculation.

The authoritative rules are defined in:

```text
DOMAIN_SPEC.md
```

## Domain Independence Rule

The Domain Layer must know nothing about:

- JavaFX,
- React,
- Vue,
- browsers,
- HTTP,
- REST,
- JSON transport objects,
- PostgreSQL,
- JDBC,
- SQL,
- Docker,
- GW2 API response JSON,
- filesystem paths.

Domain logic should operate on domain objects and interfaces.

This is one of the most important rules of the target architecture.

---

# 8. Application Layer

The Application Layer coordinates use cases.

Examples:

```text
CalculateCraftingProfit
GetCraftingDiscoveryCandidates
RefreshAccountData
RefreshTradingPostPrices
RebuildCraftingGraph
GetBankContents
GetMaterialStorage
CalculateEctoLuckCost
```

An application service may:

1. request data from repositories,
2. construct a domain request,
3. execute domain logic,
4. persist required state,
5. return a result.

The Application Layer may coordinate infrastructure, but it should not contain complex domain calculations itself.

---

# 9. API / HTTP Layer

The API layer translates web requests into application use cases.

Responsibilities include:

- HTTP routing,
- request validation,
- authentication if introduced later,
- converting transport DTOs,
- returning HTTP responses.

Example endpoints may eventually resemble:

```text
GET  /api/account/materials
GET  /api/account/bank

POST /api/sync/account
POST /api/sync/global
POST /api/prices/refresh

POST /api/crafting/profit
POST /api/crafting/discovery

GET  /api/items/{id}
GET  /api/recipes/{id}
```

These endpoint names are examples, not yet a fixed API contract.

Business rules must not be implemented in controllers.

---

# 10. Persistence Boundary

The domain/application layers should depend on repository interfaces rather than concrete PostgreSQL classes.

Conceptually:

```text
Application / Domain
        |
        v
RecipeRepository interface
        |
        v
PostgresRecipeRepository
        |
        v
PostgreSQL
```

This enables:

- unit testing without PostgreSQL,
- easier database migrations,
- clearer ownership,
- reduced coupling.

---

# 11. External API Boundary

GW2 API access should follow the same pattern.

Conceptually:

```text
Application
    |
    v
Gw2ApiPort
    |
    v
Gw2HttpApiClient
    |
    v
ArenaNet API
```

External GW2 API response models should be converted into internal application/domain models at this boundary.

GW2 JSON structures must not leak into domain logic.

---

# 12. Frontend Responsibilities

The frontend is responsible for presentation and interaction.

Reusable web UX/UI requirements are owned by
[`FRONTEND_UX_GUIDELINES.md`](FRONTEND_UX_GUIDELINES.md). Frontend implementation
stories must reference its relevant sections. Phase 5's initial frontend must
be brought into compliance before extending its presentation patterns to
additional pages; presentation correction preserves the backend contracts below.

It may:

- display tables,
- provide filters,
- display money values,
- display recipe trees,
- trigger sync operations,
- show loading/error states,
- submit crafting settings,
- provide search and sorting,
- visually flag special states.

The frontend must not independently reproduce business calculations.

For example, it may display:

```text
profitCopper = 12345
```

but must not independently recalculate crafting profit from raw materials and prices.

---

## 12.1 Web presentation assets and product requirements

Crafting Profit's requested result content and interactions are owned by
DOMAIN_SPEC section 2.1.1; apply the frontend UX guidelines to their presentation.
These are Phase 5 frontend migration requirements, covered incrementally by its
normal stories, without changing its existing backend-authority or review gates.

Provide standard browser favicon support using a replaceable asset file in the
frontend project. Document the exact filename and path where the Product Owner
should place the final icon; do not embed the final image into source code.

### Item and recipe icon delivery

**DECIDED (AR-005, Class C).** Use browser HTTP caching over a backend-owned
persistent filesystem cache, with ArenaNet image retrieval only on local miss.
This satisfies Request-005 and replaces the AR-004 direct-browser contract for
Crafting Profit, Bank, Materials, recipe/detail trees and future item views.
This is target intent, not implemented behavior.
[ADR-005](architecture/decisions/ADR-005-persistent-web-icon-cache.md) records
the rationale; ADR-004 is superseded. API-009 and WEB-010 still require planner
rescoping before selection. Phase 5 performance and health-review gates remain.

**Metadata and boundaries.** Backend synchronization owns canonical upstream
metadata in existing `items.icon_url`; do not create a parallel web metadata
store. Synchronization must cover referenced account items, recipe outputs and
ingredients, including nontradeable items, and refresh changed URLs during
explicit metadata refresh. The existing null-only backfill does not establish
coverage or refresh. Missing metadata is tolerable and repaired through backend
synchronization, never navigation or an image request. Keep metadata acquisition
usable without binary downloads or desktop setup; no new browser sync control
is required.

Application reads batch-enrich presentation results from retained metadata,
outside domain calculations. Item-bearing HTTP representations carry nullable
`iconUrl`; recipes use their backend-known output item and detail nodes their
actual item identity. Non-item/unsupported nodes and missing or rejected sources
yield null. Page-data reads/calculations perform no per-row database lookup,
live GW2 metadata request or image download. A local metadata read on an image
cache miss is allowed; it must not trigger GW2 JSON acquisition. URL, disk and
HTTP concerns stay outside the domain. A backend application icon-delivery
boundary coordinates filesystem and upstream HTTP adapters behind thin HTTP
controllers.

**Application URL and validation.** Emit application-relative
`/api/items/{itemId}/icon/{sourceKey}.{ext}` as `iconUrl`, served by backend
`GET`. The frontend consumes it verbatim through existing application API
routing; development routing must also forward this path to the backend.
Remove filesystem `iconPath` from browser contracts and migrate affected
transport consumers together. Retain local paths for in-process JavaFX.
Never emit or redirect to an upstream image URL; no direct-browser fallback.

Accept only parsed absolute HTTPS sources on exactly
`render.guildwars2.com`, with `/file/{signature}/{file_id}.{ext}` paths:
hexadecimal signature, positive decimal file ID, and lowercase `png` or `jpg`.
Reject userinfo, nondefault ports, queries, fragments, encoded separators,
dot segments and other hosts/schemes/paths. Canonicalize scheme/host to lowercase,
omit explicit port 443, and retain accepted path spelling. The source key is
lowercase 64-hex SHA-256 of the canonical URL's UTF-8 bytes. Centralize this
backend policy. Validate route item ID, key and extension before file access.

On a miss, load the item's retained source and require its validated derived
key/extension to match the route before fetching. Unknown items, missing metadata,
obsolete or mismatched keys return 404 without upstream access. A cached old key
may still serve its original image, never new-version bytes. Accept no
caller-provided URL/path/host. Disable upstream redirects; forward no API key,
cookies, application authorization or account data. This is an asset endpoint,
not an arbitrary proxy. Browser GW2 API requests remain forbidden.

**Persistent storage and hits.** Reuse `ICON_CACHE_DIR`, whose existing local
default is `<user.home>/.nebet-gw2-tool/icons`. The backend infrastructure
adapter owns `<ICON_CACHE_DIR>/assets-v1/{sourceKey}.{ext}`; items sharing a
source share one binary. Only fully committed, validated images are entries.
Partial, empty or corrupt files are misses. Constrain resolved paths to the
configured root, including symlink handling; expose no directory listing or
generic file-serving route.

Serve valid hits without any upstream request/revalidation or live metadata
dependency, including during upstream outage. Browser expiry does not expire
the disk copy. No backend age-based expiry or routine redownload is selected.
Changed retained metadata produces a new key and browser URL on the next read.

**Misses and failures.** Fetch only the matched canonical image URL. Require
HTTP 200 and a complete, nonempty, decodable PNG/JPEG matching the extension.
Reject HTML/error bodies and unsupported images. Enforce finite connection and
response deadlines, response-byte and decoded-pixel limits, bounded download
concurrency and bounded waiting requests. Implementation must document chosen
bounds; these are protective limits, not measured latency claims. Coalesce
same-key concurrent misses within the backend process and recheck disk after
acquiring the key's coordination slot; no distributed lock service.

Write a unique temporary file beside the destination, validate, and atomically
publish before success. All writers must use this protocol; readers must never
see partial files. A concurrent publisher must not overwrite a valid committed
entry. Persistence failure is an unavailable response, not a successful uncached
image stream. Bulk warmup is not required.

Malformed route values return 400; absent/rejected metadata or upstream 404
returns 404; transient upstream failure, invalid images, exhausted capacity and
disk failure return 503. All non-success responses use
`Cache-Control: no-store`; 503 includes finite `Retry-After`.
Never persist failures or placeholders at image keys. Use short-lived, bounded
in-process failure suppression by key to avoid repeated outage requests; it
must expire and never hide a valid disk hit. No persistent negative cache or
automatic request retry loop. Log diagnosable failures without secrets or
exposing local paths to the browser.

**Browser caching and rendering.** Successful responses use
`Cache-Control: public, max-age=86400`, verified `Content-Type`,
`X-Content-Type-Options: nosniff`, and a strong ETag derived from stored bytes.
Support `If-None-Match`/304 using the local copy, retaining Cache-Control and
ETag on 304. This one-day freshness interval is an application choice, not an
upstream guarantee. Do not use `immutable`: finite freshness allows repairs
to become visible after revalidation. Browser eviction/reload can issue
requests, which still use disk. Never substitute new-version bytes at an old
key. No timestamp cache busting, base64 embedding, blob-fetch layer, service
worker or IndexedDB image store is required.

Use one shared frontend image component, reserved dimensions, prompt visible
image loading and appropriate native offscreen lazy loading.
Set `referrerpolicy="no-referrer"`; configured CSP needs no ArenaNet image
origin exception. Null/rejected URLs and load/decode failures use one bundled
neutral placeholder without upstream fallback or retry loops. Reset failure
state on identity/URL change. Retain item text (backend name or item ID),
quantities, rarity, domain states and keyboard interaction; avoid redundant
accessible announcements. Empty bank slots stay empty. Image failure cannot
affect crafting eligibility or result availability.

**JavaFX coexistence and reuse.** Keep `ICON_CACHE_DIR` and `items.icon_path`
compatibility, while routing future `IconSync` binary writes and web misses
through the same adapter/key/publication policy. Existing explicit desktop
bulk sync remains callable; web use does not depend on it. JavaFX consumes
shared files through `icon_path`, updated only after publication and while the
item's retained source still matches. A desktop-path database update failure
does not invalidate a committed asset. Sharing across desktop/backend processes
requires the same root at usable local paths; host-specific absolute database
paths are not portable web inputs.

Legacy `items/{itemId}.png` files are migration candidates. Observed
`IconSync` records no source-version provenance and uses .png filenames even
for .jpg sources. Validate actual bytes and require evidence associating them
with the canonical source before adoption: recorded provenance, or byte
equality with a successful on-demand fetch of that source. Current database
URL plus filename alone cannot prove this. With evidence, adopt into the keyed
cache through safe linking/copying and update the desktop path; without it,
leave legacy files usable by JavaFX and populate the new entry on demand.
No mass redownload or startup migration is required. Remove legacy copies
only after successful adoption/path migration and confirmation that no
retained desktop reference needs them. Temporary migration copies are acceptable;
do not maintain independent web and desktop download stores going forward.

**Retention and deployment.** Preserve committed files across backend restarts,
upgrades and container replacement. Container deployment must mount a writable
persistent volume or host directory at `ICON_CACHE_DIR`; an image layer,
ephemeral writable layer or temporary directory is insufficient. The frontend
needs no mount. Keep the configured root stable across restarts, diagnose
missing/unwritable storage, and never silently downgrade to memory-only caching.
This constrains existing deployment work; it does not commission a new service,
container, deployment story or multi-replica requirement. No database binary
store, distributed cache or external cache service is selected.

Do not schedule age-based eviction, refresh or sweeping of valid images.
Cleanup may remove abandoned temporary files, corrupt entries and safely
unreferenced legacy duplicates, protecting active readers/writers. Old source
keys may remain; no measured storage pressure justifies a garbage collector.
Manual removal of a corrupt entry permits on-demand repair. Disk-full failures
must preserve existing hits and surface bounded failure on misses. Any later
retention policy needs evidence and must preserve normal persistent reuse.

**Verification and planner follow-up.** Rescope API-009 and WEB-010 before
selection; old direct-CDN/no-cache constraints are obsolete. Verify URL/null
mapping, actual item identities, batch enrichment, source/route rejection, path
confinement, no secrets/paths on the wire, and no external calls on page-data
reads. Verify hits with upstream disabled; persistence before success; concurrent
misses; interrupted writes and restart reuse; changed metadata; malformed,
oversized and failed images; disk errors; bounded recovery; HTTP headers/304;
safe legacy reuse and JavaFX compatibility. Use disposable cache roots and
controlled HTTP fixtures, with no implicit live metadata sync. Verify shared
rendering, layout stability and empty slots.

Then record real-browser/real-database integration and section 33 full-page
timings for cold browser/cold application cache, cold browser/warm application
cache and warm browser/warm application cache, including restart and warm-cache
upstream-unavailable checks. Record metadata coverage, cache conditions,
first/repeat openings, settings, result counts, individual timings/maxima,
browser/backend/upstream requests and image completion/failures. Warm application
cache must demonstrate zero upstream image requests for cached entries.
Fixtures do not establish live performance; this decision claims no timings or
successful integration. Do not conceal pending images, truncate requested
results, or use lazy loading/forced placeholders to claim the gate is met.
Record fallback runs separately from successful delivery; report missing
evidence or unmet gates for planner disposition. Request-005 closure and
Phase 5 review remain planner-owned. Crafting calculations and the active
resolution-endpoint story are unchanged.

# 13. Resolution Tree

**Status: DECIDED (AR-002; root identity clarified by AR-003, Class C).** The contract below is target intent,
not a claim that detail endpoints or semantic resolution traces exist today.
[ADR-002](architecture/decisions/ADR-002-resolution-tree-http-contract.md)
records the original alternatives and rationale;
[ADR-003](architecture/decisions/ADR-003-resolution-root-identity.md) records the
root-identity and explanation-basis clarification. This section owns the wire contract.

## 13.1 Detail operations and explicit inputs

Add `POST /api/crafting/profit/resolution` and
`POST /api/crafting/discovery/resolution`. These paths are fixed by this decision,
unlike the illustrative endpoints in section 9. Both are read-only calculations;
neither consumes persisted inventory nor triggers GW2 synchronization.

Each required JSON body contains `recipeId` (positive integer) and `calculation`
(object). `calculation` uses the corresponding existing table request contract:

- Profit: `scope` (`kind`: `ALL`, `DISCIPLINE`, `CHARACTER_DISCIPLINE`, plus
  `discipline`, `characterName`, `rating` as applicable) and `settings`.
- Discovery: `scope` (`discipline`, `characterName`, `rating`), independent nullable
  `inventoryCharacterName`, and `settings`. Preserve the separate inventory
  character and the existing null/unfiltered-pool semantics.
- Settings retain `useOwnMats`, `allowBuying`, `maxBuyCopper`, `listingSell`,
  `listingBuy`, and Profit's `dailyBuyInsteadOfCraft`. Discovery fixes the latter
  to false and does not accept it as a selectable input. Existing validation,
  defaults and rating semantics apply; this decision itself adds no new calculation
  controls. Profit's settings additionally carry `allowNonTradeableMaterials`, the
  control `DOMAIN_SPEC.md` section 2.1.1 decided separately: both routes take and
  echo it with the same defaults and validation, so a detail is evaluated under the
  table's own material rule. Discovery does not have it.

The browser copies the table response's effective inputs into the appropriate
request fields rather than relying on defaults again. It must not send row
numbers, prices, material maps or a prior service/context identifier as inputs.
Recipe identity is the recipe ID, never merely its output item ID.

## 13.2 Calculation lifetime and consistency

Validate first, then create one fresh application-service calculation context
for the detail operation. Load data, check the recipe against the newly returned
visible candidate set, evaluate and produce its explanation inside that operation.
Initially reusing the existing reload use case is acceptable; selected-recipe
optimization must preserve its eligibility and domain semantics. Never look up a
previous request's service or share mutable last-result fields across requests.
An empty Discovery reload cannot expose any previous result or lookup state.

Capture the loaded inputs used by this operation and use them for both its row
summary and tree evaluation. Do not reload prices, inventory or graph midway
through explanation construction. Release request-local state after completion or
failure; no retained calculation session, TTL, cache token, durable snapshot or
new task store is required. Shared reference data must not carry mutable
simulation/inventory state between operations.

This is a **fresh calculation**, not retrieval of the table's historical result.
Even identical input values may yield different results after synchronization or
price changes. There is no cross-request snapshot guarantee and no claim of an
atomic database-plus-graph snapshot during loading. Request-local reuse prevents
explanation construction from introducing a second data read, but does not settle
future synchronization consistency design (section 23 / UD-007).

## 13.3 Successful response

The completed response is JSON with these required fields:

| Field | Type and meaning |
| --- | --- |
| `recipeId` | Requested recipe ID, used for selection and response association; does not assert that this recipe produced the root requirement. |
| `calculation` | Effective scope and settings, shaped as the corresponding table response's scope/settings; Discovery also includes nullable `inventoryCharacterName`. All effective settings are explicit, including Discovery's fixed daily setting. |
| `consistency` | Literal `FRESH_CALCULATION`. |
| `calculatedAt` | UTC ISO-8601 completion timestamp; informational, not a data version or snapshot identifier. |
| `row` | Existing shared crafting row DTO for this recipe, recalculated in this operation; retains its existing per-craft/total field meanings and nullability. |
| `treeStatus` | `AVAILABLE` or `RESULT_UNAVAILABLE`. |
| `treeBasis` | Literal `SINGLE_OUTPUT_REQUIREMENT`: resolution of one output batch of the requested recipe from this operation's initial inventory, budget and daily state, applying the existing simulation phase order, selected settings and authoritative resolver rules. It does not assert execution of the requested recipe. |
| `tree` | Recursive node below, or null only when `treeStatus` is `RESULT_UNAVAILABLE`. |

The explanation is a domain evaluation, not multiplication of a recipe skeleton
and not a trace of all `row.craftableCount` crafts or the next craft after
exhausting a simulation. Its root item and requested quantity are the requested
recipe's output item and output count. The trace records how that requirement
was actually sourced; it is not a guarantee of one execution of that recipe.
The row remains the normal simulation summary. In particular, tree costs must
not be presented as the row's multi-craft totals; the browser labels the basis.
Both evaluations use the same captured inputs and authoritative resolver rules.
A blocked first evaluation still returns an available explanation with reasons. If the
backend has no calculation result, retain the row's existing unavailable/null
semantics and return `RESULT_UNAVAILABLE`, not a fabricated empty tree.

Each recursive requirement node has exactly this initial semantic shape:

| Field | Type and meaning |
| --- | --- |
| `itemId` | Positive integer item identifier. |
| `itemName` | String or null when metadata is unavailable; display may fall back to the ID. |
| `requestedQuantity` | Nonnegative integer units required by this occurrence. |
| `inventoryQuantity`, `craftedQuantity`, `boughtQuantity`, `missingQuantity` | Nonnegative integer contributions to that requirement, decided by the backend. Crafted quantity means units used here, not surplus output. Their sum equals requested quantity. |
| `recipeId` | Actually selected producing recipe ID (including a selected blocked attempt), or null when no crafting path is selected. This applies equally to the root and descendants; the root ID may differ from the response-level requested `recipeId`. |
| `craftCount`, `producedQuantity` | Nonnegative integers for the selected crafting contribution; zero when none completes. Production can exceed crafted quantity because of recipe batch size. |
| `characterName` | Assigned crafting character or null when not applicable/not assigned; preserve backend character selection. |
| `methods` | Array containing the actually used contributions from `INVENTORY`, `CRAFT`, `BUY`; multiple methods are allowed for split sourcing. Empty if no contribution succeeds. |
| `states` | Array of backend flags from `BLOCKED`, `PRICE_UNAVAILABLE`, `DAILY_LIMIT`, `UNVALUED_NONTRADEABLE`; empty if none apply. These may coexist with methods. |
| `blockedReasons` | Array of the explicit reasons in DOMAIN_SPEC section 42 (`NO_RECIPE`, `BUYING_DISABLED`, `DAILY_LIMIT`, `CYCLE_DETECTED`, `PRICE_UNAVAILABLE`, `RECIPE_NOT_ALLOWED`, `INSUFFICIENT_BUDGET`); empty for an unblocked node. |
| `cashCostCopper`, `opportunityCostCopper`, `effectiveCostCopper` | Backend-provided numeric amounts in copper for this node's complete requirement, inclusive of descendants; null when a complete value cannot be established. Preserve domain precision; no UI rounding in transport. Known zero is distinct from null. |
| `children` | Ordered array of ingredient requirement nodes for the selected crafting path (including a blocked attempted path); empty for leaves. |

Requested identity belongs only to the response envelope and its row association;
actual sourcing belongs to the tree. For an inventory-only root, node `recipeId`
is null, `craftCount` and `producedQuantity` are zero, and no recipe ingredients
are invented. If another recipe supplies the output, its actual ID, executions,
production and ingredient children are retained. For a blocked selected crafting
path, retain that attempted recipe's identity without claiming completed crafts.
Never overwrite node identity with the requested ID, wrap the trace in a fabricated
craft node, suppress an otherwise available trace because identities differ, or
repair quantities/costs in an API mapper or browser. The browser identifies the
requested recipe separately from the root's actual methods and producing recipe;
it must not label output supplied from stock or another recipe as execution of
the requested recipe. Response association still uses the envelope `recipeId`.

Costs on ancestors include descendant costs; the frontend must not add them again.
For a blocked requirement, a complete cost is null rather than a misleading
partial total. A domain-established zero for an unvalued non-tradable item remains
zero with its explicit state. Unknown purchase prices never become zero. Failed
speculative paths must not leak committed quantities or costs. Cycle detection
terminates with a finite blocked node; no object references or graph back-links
are serialized. Repeated items in different branches remain separate occurrences;
a presentation key may use the child-index path and is not a persistent node ID.
Do not truncate a tree silently or infer states from raw quotes in a mapper.

The domain produces resolution choices, quantities, valuation and failure facts;
the application coordinates the captured context, and the API maps those facts
into DTOs. Existing `Node.action` text and a first-recipe dependency expansion are
not sufficient evidence of actual resolution. The implementation must obtain a
semantic trace from authoritative resolution, not build a competing resolver in
the controller, DTO mapper or browser. Domain types remain independent of JSON,
HTTP and JavaFX. No color, widget, CSS class or expansion state belongs in this
schema. Unknown future state/reason codes must remain visibly representable,
never silently treated as success.

## 13.4 Missing results, errors and browser association

A valid recipe ID absent from the fresh visible candidate set returns HTTP 404
with the existing error-body shape `{ "error": "RECIPE_NOT_IN_CALCULATION",
"message": "..." }`. This includes a recipe that is no longer discoverable;
it does not assert that the recipe is absent from the global database. Invalid
input uses existing 400 validation/malformed-request errors; store failures use
503 and unexpected calculation failures use 500 with existing safe error codes.
Blocked domain paths and available rows with unavailable calculation results are
completed HTTP 200 responses, not transport failures. Existing table endpoints
retain their empty-list 200 behavior and do not eagerly attach trees.

The browser associates a request with feature, recipe ID, effective calculation
inputs and a local request generation. Changing selection or calculation inputs,
starting a table reload, or leaving the view invalidates the previous detail.
Cancellation is optional; ignoring late responses for invalid generations is
required even for A-to-B-to-A selection. Accept a response only for the active
generation and matching echoed identity/inputs. Sorting or formatting alone does
not change calculation identity. Preserve valid user controls during refresh.

Display the returned row and tree together as freshly calculated detail, with
its single-output-requirement basis. Do not label it as the exact explanation of the earlier
table row or silently overwrite the old table row with it. Errors/unavailable
results clear the active tree; they must not leave an old tree under a new
selection. Expansion and rendering remain frontend concerns.

## 13.5 Migration, execution and verification constraints

JavaFX continues to call application services in process. Keep the table routes
and their response shapes compatible; adding detail does not force JavaFX through
HTTP or attach tree-building work to every table row. Local class names and trace
representation are implementation choices within these boundaries.

AR-003 replaces the earlier `SINGLE_CRAFT` wire literal and requested-root
identity assertion before detail HTTP/browser integration. Internal explainer
names may remain unchanged. This is a truthful representation of the existing
calculation, not a redefinition of DOMAIN_SPEC section 28: craftable count still
normatively means executions of the requested recipe. KNOWN_PROBLEMS CH-15
remains an unresolved domain defect. Correcting it is not an architectural
prerequisite to shipping this explanation of current results; the detail contract
neither fixes nor accepts it as intended domain behavior. A later correction must
occur in the authoritative domain path shared by calculations and explanation,
not exclusively in detail, transport or presentation. The separated identities
remain valid after that correction. This decision grants no Phase 5 completion
or waiver of existing correctness, review or performance gates.

The schema above defines the completed response. Per section 23 and UD-007,
measure the detail operation on representative real data before fixing its
synchronous/asynchronous execution policy: prefer direct HTTP 200 completion when
consistently short; if measurements require a task, retain these inputs and
completed payload and document the task transport before shipping the consumer.
This decision does not prescribe or assume a new task mechanism.

Phase 5 must still verify section 33's complete browser-page timing, including
any detail automatically loaded on navigation. Lazy selection does not excuse
hiding required initial work or omitting results. No performance result is
claimed by this contract. Verification must cover semantic split sourcing,
blocked/unvalued nodes, null costs, cycles, requested identity versus actual root
sourcing (owned finished stock only, an alternative producing recipe, the
requested recipe, mixed sourcing and a blocked attempted path), fresh-input row/tree consistency,
concurrent scope isolation, empty Discovery after populated calculation, and
late browser responses. Test responsibilities remain owned by TEST_STRATEGY.md;
the planner owns bounded implementation and verification work.

---

# 14. Special Domain States

The backend/domain may expose meaningful states such as:

```text
INVENTORY
CRAFT
BUY
BLOCKED
PRICE_UNAVAILABLE
DAILY_LIMIT
UNVALUED_NONTRADEABLE
```

These are domain meanings.

The frontend decides how those states are visually represented.

Example:

```text
UNVALUED_NONTRADEABLE
```

may eventually be shown in blue, yellow, or another visual style.

That color is not part of the domain contract.

---

# 15. Configuration and Secrets

Runtime configuration must not be hard-coded into source files.

Configuration should be supplied through runtime configuration such as environment variables or equivalent deployment configuration.

Examples include:

```text
GW2_API_KEY
DATABASE_URL
DATABASE_USER
DATABASE_PASSWORD
```

Secrets must remain backend-only.

The frontend must not receive the GW2 API key or database credentials.

---

# 16. Database Schema Management

The current manually executed SQL setup should eventually be replaced by a repeatable database migration mechanism.

The exact migration technology is TBD.

Possible solutions depend on the selected backend framework.

Required properties:

- schema creation is reproducible,
- migrations are versioned,
- migrations are stored in the repository,
- a fresh database can be created without manually copying SQL into PostgreSQL,
- application upgrades can migrate existing databases safely.

---

# 17. Container Orchestration

For local development and small-scale deployment, Docker Compose is the preferred initial orchestration mechanism.

Conceptually:

```text
docker compose up
```

should start:

```text
frontend
backend
postgres
```

Possible future supporting services should only be added when clearly necessary.

The project should not introduce Kubernetes or a microservice platform without a concrete requirement.

---

# 18. Container Networking

Containers communicate over an internal Docker network.

Conceptually:

```text
Browser
   |
   v
Frontend : public

Frontend
   |
   v
Backend : internal/API

Backend
   |
   v
Postgres : internal only
```

PostgreSQL does not need to be publicly exposed in a production deployment.

---

# 19. Deployment Model

The initial target is a small single-host deployment.

Possible hosts may include:

- a home server,
- VPS,
- NAS/container host,
- another Docker-capable server.

The hosting provider is not yet decided.

The architecture should avoid relying on provider-specific services unless intentionally introduced later.

---

# 20. Authentication

Authentication is currently not a defined requirement.

**Status:** TBD

If the application remains a private single-user tool, full user-account infrastructure may not be necessary.

Authentication should not be added merely because the application is web-based.

If public or multi-user access becomes a requirement, authentication and account isolation must be designed separately.

---

# 21. User / Account Scope

The current intended deployment should initially assume one configured Guild Wars 2 account per application instance unless a future requirement explicitly introduces multi-user or multi-account support.

This keeps the initial architecture small.

Multi-tenancy must not be introduced speculatively.

---

# 22. Synchronization

Synchronization should become an application-level use case rather than UI-owned behavior.

Examples:

```text
Refresh account
Refresh global item data
Refresh recipes
Refresh Trading Post prices
Rebuild crafting graph
```

The frontend may trigger these operations.

The backend controls:

- execution order,
- persistence,
- error handling,
- synchronization state.

---

# 23. Long-Running Operations

Some synchronization operations may take longer than a normal HTTP request.

The first implementation should remain as simple as practical.

Per resolved `agent/user-decisions/UD-007-http-long-running-sync-approach.md`, use a mixed approach:

- GW2 API synchronization operations use asynchronous backend tasks with status reporting.
- For other endpoints, obtain representative real-world runtime measurements during implementation. Prefer synchronous requests for consistently short operations; use backend tasks with a status endpoint when execution takes materially longer or risks interruption/connection timeout. Do not assume durations or assign the split speculatively.

A full queue system or distributed job platform is not currently required.

**Status:** approach decided in UD-007; per-endpoint choices outside GW2 synchronization follow measured behavior. Routine task implementation details remain implementation work.

---

# 24. Crafting Graph

The Crafting Graph remains an internal backend/domain concern.

The frontend should not construct or maintain the crafting graph.

The target architecture should determine later whether the graph is:

- rebuilt in memory,
- cached in the database,
- cached in a file,
- generated on startup,
- generated after recipe synchronization.

This is an implementation decision as long as domain results remain correct.

---

# 25. Testing Architecture

The architecture must support multiple test levels.

## Existing JavaFX UI Verification Capability

The implementation workflow must support repeatable automated verification of the existing JavaFX application. This is intended capability, not a claim that tooling is already installed or verified. Its immediate scope is the Phase 1 crafting views and the verification gaps recorded in STORY-DOM-013 through STORY-DOM-015.

The capability must launch the application, detect successful startup, interact with relevant controls (including ComboBox selection and button clicks), inspect TableView contents and displayed state, verify selection-driven result changes and important empty/error states, capture screenshots when useful, and shut down cleanly. Prefer control-based deterministic regression tests over ad-hoc desktop interaction; fixed screen coordinates are not an acceptable foundation.

Evaluate TestFX or an equivalent maintained approach against the actual JavaFX version and existing Maven setup before adopting a substantial framework. Compatibility and reliability must be demonstrated rather than assumed. Keep reusable capability setup separate from the behavior coverage owned by the existing crafting stories: character-dependent results, All characters selection, refresh preservation of sorting/filtering, initial defaults, and zero-character/empty-data handling.

Windows PowerShell may be used within the repository/development workflow for interim automation, including investigation of System.Windows.Automation where useful. This is not a requirement to broaden agent permissions and does not replace practical automated regression tests. Preserve application correctness and retain manual verification as a fallback when automation is genuinely impractical. Once tooling is established, document its permanent test methodology, invocation and limitations in TEST_STRATEGY.md; this section owns the intended capability, while that document owns how it is tested.

## Domain Tests

Run without:

- database,
- HTTP,
- Docker,
- GW2 API.

These tests protect the rules in `DOMAIN_SPEC.md`.

## Application Tests

Test use-case orchestration using fake or in-memory adapters.

## Persistence Integration Tests

Verify PostgreSQL repository behavior.

## External API Adapter Tests

Verify conversion and handling of GW2 API responses.

## API Tests

Verify backend HTTP contracts.

## End-to-End Tests

A small number of tests may cover:

```text
browser/API
→ backend
→ database
```

The majority of business behavior should not require end-to-end tests.

---

# 26. Dependency Direction

The intended dependency direction is:

```text
Frontend
    |
    v
Backend API
    |
    v
Application
    |
    v
Domain
```

Infrastructure implements interfaces required by the application/domain.

The following dependency directions are forbidden:

```text
Domain → PostgreSQL
Domain → HTTP
Domain → Frontend
Domain → JavaFX
Domain → Docker
```

---

# 27. Migration Principle

The current application should not be rewritten all at once.

Migration should preserve working behavior while gradually establishing the target boundaries.

Preferred strategy:

```text
existing application
      |
      v
protect behavior with tests
      |
      v
isolate domain logic
      |
      v
isolate persistence/API adapters
      |
      v
introduce backend API
      |
      v
introduce web frontend
      |
      v
containerize final runtime
```

The exact migration plan belongs in `MIGRATION_PLAN.md`.

---

# 28. Reuse of Existing Code

Existing Java code should be reused when it correctly implements defined domain behavior and can reasonably be isolated.

A rewrite is not automatically preferred.

Code should be:

- retained when sound,
- refactored when coupling prevents safe use,
- replaced when behavior is incorrect or isolation would be more expensive than replacement.

The target architecture describes boundaries, not a requirement to throw away the current implementation.

---

# 29. Architecture Simplicity Rule

When several designs can satisfy the requirements, prefer the simplest design that:

- preserves domain correctness,
- is testable,
- has clear responsibility boundaries,
- is understandable by a small project team,
- can be maintained with Claude-assisted development.

Avoid adding abstractions solely because they are common in large enterprise applications.

---

# 30. Technology Decisions

## Decided

The following target decisions are currently established:

Backend web framework: **Spring Boot**, per resolved `agent/user-decisions/UD-006-backend-web-framework.md`. Choose a supported version compatible with the project's actual Java and Maven versions at implementation time. Spring provides HTTP routing, validation, transport DTO handling and runtime infrastructure at the backend boundary; existing application/domain behavior must not become dependent on Spring APIs or be redesigned around the framework. Phase 4 remains additive: JavaFX continues calling application services in-process.

Long-running HTTP operation policy is decided in section 23 (UD-007); concrete implementation choices follow that policy.

Frontend framework and language are decided in section 4.1; that section owns the selection and implementation constraints.

```text
Application type:
Web application

Deployment:
Containerized

Primary containers:
Frontend
Backend
PostgreSQL

Database:
PostgreSQL

Business logic owner:
Backend

External GW2 API access:
Backend only

Communication:
Frontend → Backend through HTTP API

Domain:
Independent from UI, database, HTTP, and external API formats

Local/small deployment:
Docker Compose preferred
```

---

## To Be Decided

The following decisions intentionally remain open:

```text
Database migration tool:
TBD

Reverse proxy:
TBD

Hosting provider:
TBD

Authentication:
TBD, only if required

```

An implementation agent must not choose one of these technologies merely because it is familiar without an explicit project decision.

---

# 31. Intended Repository Direction

A possible future repository structure may resemble:

```text
/
├── docs/
│   ├── DOMAIN_SPEC.md
│   ├── CURRENT_STATE_SPEC.md
│   ├── TARGET_ARCHITECTURE.md
│   ├── MIGRATION_PLAN.md
│   └── TEST_STRATEGY.md
│
├── backend/
│   ├── domain/
│   ├── application/
│   ├── api/
│   └── infrastructure/
│
├── frontend/
│
├── docker-compose.yml
│
└── ...
```

This is an architectural illustration, not yet a mandatory physical directory structure.

The actual structure should be chosen when the migration begins.

---

# 32. Relationship to Claude Code

Claude should use this document to determine architectural boundaries.

When modifying or creating code, Claude must preserve these principles:

1. Domain logic belongs in the backend domain layer.
2. UI code must not own business rules.
3. Controllers/API endpoints must remain thin.
4. SQL/JDBC must not leak into domain logic.
5. GW2 HTTP/JSON models must not leak into domain logic.
6. The frontend must not receive secrets.
7. The frontend must not duplicate authoritative calculations.
8. New dependencies must respect inward dependency direction.
9. Technology choices marked `TBD` must not be silently finalized.
10. Major architecture changes should be explicit and documented.

---

# 33. Crafting calculation performance and Phase 3 acceptance gate

Crafting Profit load performance is required current Phase 3 work before proceeding to Phase 4/backend HTTP API work. The Product Owner reported approximately 20 seconds to populate CraftingProfitView (2026-09-22); that is a reported observation, not an independently measured baseline. Investigate and resolve it now, preserving intended calculation behavior. This supersedes the earlier deferral for Crafting Profit; Discovery performance remains future work unless separately authorized.

**Required result:** opening Crafting Profit must take **at most 7 seconds from page-load initiation to the complete page being populated and interactive**, measured against the user's current real PostgreSQL database with its real account, recipe, inventory and price data. Faster is welcome but not required. Time starts with the navigation action, not after loading a service or starting the calculation. Completion includes initial scope/control loading, data acquisition, calculation, presentation preparation, table population and rendered usable UI for the complete requested result set. An empty shell, spinner, first rows, partial results, or only backend execution time does not satisfy this limit. Do not hide work before the timer, omit recipes, lower simulation limits or silently serve stale results to meet it.

Use the actual user environment and database, not a mock, disposable tiny fixture, reduced dataset or synthetic benchmark as acceptance evidence. Cover the default All scope, repeat openings and the first opening after application startup; record selected settings, data scale, software/hardware environment and cache state. Report individual end-to-end timings and the maximum; an average below seven seconds does not excuse a measured opening above it. A separately initiated first-time setup/account synchronization is outside page navigation; any work triggered by opening the page remains inside the timer. The measurement procedure is owned by `TEST_STRATEGY.md` §34.

Begin with a reproducible baseline and attribute elapsed time to database access, recipe/graph/cache loading, calculation/simulation, presentation preparation and UI rendering. Optimize evidenced bottlenecks, not assumed ones. Before major optimization, compare materially different viable approaches and correctness, freshness, maintainability, memory and database-load trade-offs. Meaningful product/architecture trade-offs require a User Decision; the time budget does not authorize changing domain rules. Keep reusable calculation improvements behind the application/domain boundary so future REST callers benefit. Repeat the same real-data measurements and relevant correctness checks after changes.

**Blocking acceptance gate — satisfied 2026-09-23; the requirement below stands for all future work:** both recorded measurements meeting the limit and an explicit subsequent Product Owner confirmation that the issue is solved are required to close this requirement. Both were obtained for the current implementation; the measurements and the dated confirmation are recorded in `STORY-PERF-001`'s Result, which owns that evidence. Record the user's dated confirmation, tied to the tested revision/results, in `STORY-PERF-001`'s Result through the normal workflow. This instruction establishes the requirement; it is not that confirmation. Automated success, an agent/evaluator marking implementation complete, silence, or merely moving the work elsewhere cannot substitute for user acceptance. Until both conditions are met, Phase 3 remains open and Phase 4 or later work must not proceed. If measurement or user confirmation is unavailable, report the outstanding blocker rather than claiming success or relaxing the target.

Future backend API and web frontend phases must preserve this performance requirement and reverify it at their respective boundaries; service/API timing alone never substitutes for the eventual browser-navigation-to-complete-page measurement. Later-phase checks do not defer the current Phase 3 gate.

# 34. Repository quality and recurring project-health review policy

## Quality targets

Aim for a maintainable, meaningfully tested, well-documented product with coherent architecture and low technical debt. Protect important behavior and persistence/integration semantics, keep build/test behavior reproducible, and reduce confirmed problems over time. Testing methodology remains owned by `docs/TEST_STRATEGY.md`; current defects and debt belong in `docs/KNOWN_PROBLEMS.md`. These are long-term quality targets, not instructions to remediate every dimension during a review or pursue numeric coverage/complexity goals.

## PROJECT HEALTH REVIEW execution

A PROJECT HEALTH REVIEW is a bounded milestone-level assessment of whether the project remains coherent, healthy and aligned before milestone completion. Schedule it near milestone exit, after implementation work is substantially complete, not after every story. Every roadmap phase, including future phases, must include this exit requirement by reference to this section.

Inspect and compare the following within the milestone's scope:

- **Roadmap health:** actual completion of goals and exit criteria; mistakenly unchecked implemented criteria; skipped criteria and whether they are obsolete, inapplicable, or require transfer to a current/later milestone; and accidental scope drift. Check inherited prerequisite evidence only where relevant, without reopening obsolete implementation paths.
- **Architecture health:** ROADMAP alignment with TARGET_ARCHITECTURE, whether the target still expresses the intended future direction, and CURRENT_ARCHITECTURE agreement with implementation. Distinguish intentional transitional complexity from evidenced current debt. Planned replacement or restructuring in a future milestone does not by itself make today's architecture defective or authorize implementing that future architecture now.
- **Documentation health:** stale facts, contradictions, obsolete references (including renamed/deleted artifacts), duplicated authority, incorrect ownership, and disagreement with implementation or roadmap. Small, clearly evidenced corrections may be made directly at the authoritative owner.
- **Known-problem/debt health:** resolved items still marked open, historical observations presented as current facts, missing important current problems, relevance of TODO/TBD/open decisions, and clearly obsolete/dead/superseded artifacts. Report concrete evidence rather than speculative cleanup opportunities.
- **Verification health:** whether existing verification gives meaningful confidence for the completed milestone. Tests, saved results, story results and implementation evidence are inputs. Run a targeted check only when needed to answer a concrete review question; report any verification weakness or uncertainty honestly.

Assessment comes before remediation:

```text
inspect -> compare -> assess -> correct small authoritative documentation errors
        -> report concrete findings -> finish review
```

Explicit Non-Goals: no automatic bug hunt, broad regression run, test-strategy redesign, coverage-driven test expansion, refactoring/cleanup campaign, architecture implementation, or later-milestone work. A review does not automatically run every suite or create tests merely because it occurred. Substantial implementation, refactoring or test work belongs in a separate normal story decided by the user/planner during subsequent planning; the review itself must neither implement that work nor create stories for it. Do not create speculative cleanup work.

## Review results and milestone completion

Record review evidence, documentation corrections, concrete findings and the overall milestone-health assessment through the existing story Result and workflow reporting mechanisms. Preserve their established ownership and reporting flow. Findings should identify affected components, supporting evidence, impact and whether they block milestone completion, so subsequent normal planning can decide any separately scoped work or criterion transfers.

Review completion is distinct from milestone completion. The planner/user subsequently decides disposition of findings and any transfers through normal planning. Blocking findings and unsatisfied criteria keep the milestone open until resolved or explicitly dispositioned with evidence; a proposed transfer alone does not satisfy an exit criterion. The sequence is milestone implementation, bounded review, findings/corrections, planner/user resolution of blockers, then milestone closure. This policy claims no review has run and does not retroactively reopen archived milestones; relevant inherited gaps are assessed within the current milestone.

# 35. Human-readable crafting documentation

Maintain a small Markdown guide for readers with little Guild Wars 2 knowledge. Its entry point is `docs/crafting/README.md`; split into a few files only when readability benefits. Explain the end-to-end Profit and Discovery calculation, inputs, assumptions and why rules matter, with compact glossary/FAQ and worked economic examples rather than class/method walkthroughs. Cover the applicable rules in `DOMAIN_SPEC.md`: recipe choice/unlocks, discipline/rating and character eligibility, coordinated crafting and transferable intermediates, binding, account/bank/character inventory, opportunity cost, buying and craft-versus-buy, price modes, feature-specific fees, unavailable prices and blocked results, daily restrictions, discovery, the intentional simulation cap, and total/per-output-item profit. Distinguish game mechanics, project choices and documented implementation gaps; do not invent rules.

This guide is an explicit exception to the normal prohibition on documentation duplication: a self-contained explanatory summary is permitted; authoritative documents remain the source of truth. Every change materially affecting user-visible crafting rules, character/binding handling, pricing, fees, profit, blocked states or limits must update the relevant guide alongside the authoritative owner. Development/implementation documentation must link this maintenance requirement. Add only concise links near the top of root `README.md` to `agent/agent_README_experimental.md` (AI-assisted development/orchestration) and the crafting guide. Do not rewrite `agent/agent_README_experimental.md` or duplicate it in the root README.

# 36. Deferred independent model availability in development orchestration

Future planned orchestration work, not executable Phase 1 domain-stabilization work: Python must manage Claude and Codex capacity independently while retaining deterministic story selection and a simple single-writer model for workflow state. No implementation state or current defect is asserted here.

Priority is to resume an active Claude story when capacity permits, then execute selectable To Do work. At To Do <= 2, invoke Codex to replenish within existing phase/story rules if its capacity permits. Codex exhaustion must not stop Claude from draining already-planned work, even to zero. When Claude is unavailable, Codex may perform useful current-scope planning until no further useful work should be created. This does not authorize future-phase stories. If both lack capacity, wait locally; resume the appropriate flow when capacity returns.

Usage exhaustion is neither story nor planning failure. Do not invoke an exhausted model repeatedly. Reuse the existing Claude usage-wait approach where practical and add equivalent Codex detection/wait handling; local checks should avoid consuming model tokens. Claude resumes the same unfinished active story; planning resumes only while its trigger remains applicable.

Capacity checks and safe preparation may proceed independently. Python must serialize all commits to shared workflow state, including BACKLOG, CURRENT_STORY, story statuses, planner/evaluator/runtime result artifacts and milestone/planning state. Codex must not modify an active implementation story; Claude must not select its next story. Prefer understandable scheduling and single-writer state updates over concurrent file-locking complexity.

# 37. Status

This document defines the initial target architecture.

It intentionally specifies architectural boundaries more strongly than implementation technology.

The target architecture is considered stable enough to guide:

- migration planning,
- test strategy,
- repository restructuring,
- Claude Code instructions.

Technology choices marked `TBD` remain open and should be resolved only when implementation work reaches the point where the decision is necessary.
