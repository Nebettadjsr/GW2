## Story ID

STORY-API-002

## Title

Expose Crafting Discovery through the HTTP API

## Status

DONE

## Milestone

milestone-04

## Goal

Expose the existing Crafting Discovery application use case through the established HTTP runtime with explicit transport DTOs and a tested request/response contract.

## Authoritative Source Documents / Sections

- `docs/ROADMAP.md` section 8, Phase 4 calculation endpoints, thin controllers, distinct DTOs, API tests and additive JavaFX exit criteria.
- `docs/TARGET_ARCHITECTURE.md` sections 9, 23 and 30.
- `docs/TEST_STRATEGY.md` section 11.
- `agent/user-decisions/UD-007-http-long-running-sync-approach.md`, resolved User Decision.
- `agent/stories/STORY-API-001-spring-boot-crafting-profit-endpoint.md` for the shared HTTP runtime and contract conventions.

## Context

The supplied backlog records `application.CraftingDiscoveryService` as the owner of Discovery orchestration and the individual-character calculation path. Phase 4 requires a Discovery endpoint alongside Profit. This story reuses the runtime established by API-001 and does not infer unknown runtime performance.

## Acceptance Criteria

1. Implement and document a Discovery calculation route using the established API runtime and conventions. Define supported input fields, defaults, validation and status/error behavior by mapping the existing service contract.
2. The controller delegates to the existing Discovery application service. It does not implement recipe eligibility, discovery filtering, inventory, price or crafting calculations.
3. Request/response DTOs remain distinct from domain/persistence/GW2 API objects. Preserve individual-character semantics, complete results and blocked/domain states in transport mapping, including the existing empty-result behavior. Do not expose stale results from another request.
4. Use representative real-world runtime measurements to choose synchronous requests or asynchronous tasks with status reporting per section 23; record the observations and choice. Reuse established task infrastructure if needed instead of creating a competing mechanism. This is not a Discovery optimization campaign or a new numerical performance target.
5. Contract tests cover valid requests, invalid inputs, applicable missing-resource cases, mapped domain failures and empty results, including request isolation. Shared Profit API contracts continue to pass.
6. JavaFX Discovery continues its existing in-process service calls without UI changes; relevant existing application and JavaFX regressions verify preserved behavior.

## Required Tests

- HTTP contract checks with controlled service collaborators for input translation, validation before invocation, response DTOs, empty results and error/status mappings.
- Distinct successive/concurrent request checks that expose state leakage or stale response data.
- Relevant existing Discovery application/domain and JavaFX checks, plus the existing Profit HTTP contract tests affected by shared boundary changes.
- Representative real-world runtime measurements supporting the section 23 request/task choice; record actual conditions and outcomes.

## Constraints

Reuse the Spring Boot boundary and preserve application/domain independence. Do not reimplement domain mathematics, alter discovery semantics, migrate JavaFX, or add sync/refresh endpoints in this story. No speculative performance target or distributed job platform. Report genuine undecided product/architecture trade-offs through the normal user-decision workflow.

## Dependencies

STORY-API-001.

## Definition of Done

The endpoint, DTO mapping and targeted verification satisfy the acceptance criteria. Record implemented contract, measured behavior, test evidence and limitations in Result, updating authoritative architecture/testing documentation only where needed. Phase 4 remains subject to its other exit criteria and bounded health review.

## Result

Done. `POST /api/crafting/discovery` exposes the existing `application.CraftingDiscoveryService` on
the Spring Boot runtime STORY-API-001 established. No JavaFX file was modified.

### 1. Implemented contract

Full prose contract lives in `docs/CURRENT_ARCHITECTURE.md` §5.6 (its owner); summary:

- **Body required, scope required.** Unlike Profit there is no All reading and no default scope.
  `scope.discipline`, `scope.characterName` and `scope.rating` are all mandatory, and the mapper
  always builds `DiscChoice.charDiscipline(...)` — the individual-character shape the JavaFX
  selector produces, which is populated with `CHAR_DISCIPLINE` entries only and refuses to calculate
  without one (§5.2).
- **`scope.rating` is required rather than defaulted.** It drives the real `recipe.minRating <=
  rating` filter, so defaulting it to 0 (as Profit does, where it only round-trips) would have
  silently answered with a much emptier result than the caller asked for. This is the one place the
  two routes' validation deliberately diverges, and the reason is recorded on the DTO.
- **`inventoryCharacterName`** is a separate optional field, independent of `scope.characterName`,
  matching Discovery's second selector. Blank or omitted reaches the service as `null`, which keeps
  the service's own unfiltered owned-inventory fallback (STORY-DOM-012) instead of the API inventing
  a substitute.
- **Discovery's own defaults**, read off the JavaFX Discovery view's opening control state and
  deliberately *not* Profit's: `useOwnMats` true, `allowBuying` **true**, `maxBuyCopper` **200000**
  (its "20g" field), instant sell, instant buy.
- **`dailyBuyInsteadOfCraft` is not an input.** The Discovery flow has always fixed it to false
  ("not relevant for discovery"); the route maps that contract rather than widening it, and still
  echoes the value used in the effective settings so the caller can see it.
- **Validation** (all before any service is constructed): absent body / absent scope; blank or
  missing `scope.discipline`; blank or missing `scope.characterName`; missing `scope.rating`;
  negative `scope.rating`; negative `settings.maxBuyCopper`.
- **Statuses**: 200; 400 `INVALID_REQUEST`/`MALFORMED_REQUEST`; 503 `DATA_STORE_UNAVAILABLE`; 500
  `CALCULATION_FAILED`. `web.ApiExceptionHandler` is now scoped to both calculation controllers with
  `assignableTypes`, so framework 404/405 handling is still untouched and the error codes are
  identical on both routes.
- **No missing-resource case exists on this route** and none was invented: a character+discipline
  with nothing left to discover is a legitimate empty 200 — the transport form of the service's own
  "no missing recipes" short-circuit. 404 remains the framework's unknown-path response; both are
  covered by tests.

No calculation input was added or reinterpreted: the route exposes exactly the
`DiscChoice` × `CraftingSettings` × selected-inventory-character triple the existing use case
accepts.

### 2. Thin controller

`CraftingDiscoveryApiController` validates, calls `CraftingDiscoveryService.reload(...)`, and maps
the result. It implements no recipe eligibility, no discovery filtering, no missing-recipe lookup,
no rating filter, no inventory or price loading and no crafting arithmetic — all of that stays in
the application service and `craft.*`, unchanged. Layering re-verified by grep over
`src/main/java`: `web.*` is still the only package importing Spring or servlet types, and no JavaFX
class imports `web.*`.

### 3. DTOs and request isolation

New transport-only records: `web.dto.CraftingDiscoveryRequest` / `CraftingDiscoveryResponse`. No
`craft.*`, `repo.*` or GW2-API JSON type is serialized.

**Shared row projection.** Discovery's row is the identical projection of the identical domain
objects Profit's row is (a `craft.Recipe` plus its `craft.CraftResult`), so rather than duplicating
the record and ~90 lines of copying, `CraftingProfitResponse`'s nested `RowDto`/`MissingItemDto`/
`TradingPostQuoteDto` were promoted to top-level `web.dto.CraftingRowDto`/`MissingItemDto`/
`TradingPostQuoteDto` and the copying to `web.CraftingRowMapper`, both routes now using them. The
JSON field names and nesting are byte-identical — verified by the 17 existing Profit contract tests
(jsonPath, so a rename could not hide) and by re-running the real-database Profit equivalence check
(§5 below). This is the "shared boundary change" the story's Required Tests anticipated.

Preservation rather than recomputation is inherited from that shared mapper: `totalProfitCopper`
copied from `CraftResult` never derived, `blockedReason` carried as the `craft.BlockedReason` name,
both missing-material maps carried in full (sorted by item id for a stable wire order), raw
trading-post quotes passed through. Rows whose calculation produced no result are reported with
`resultAvailable: false` — where the JavaFX Discovery table drops them (`if (cr == null) continue`)
— so a client sees the complete recipe set.

**Request isolation.** `CraftingDiscoveryService` keeps its last reload's lookup data in instance
fields *and deliberately leaves them untouched when a reload finds nothing to discover*, so a shared
instance could answer an empty request with an earlier request's rows. The controller therefore
takes a `Supplier<CraftingDiscoveryService>` and builds one service per request, reading only the
`DiscoveryData` that call returned. Proven by three tests: successive requests with distinct inputs,
8 concurrent requests whose stubbed results are derived from each request's own input, and an
explicit "a full result followed by an empty one" case against exactly that stale-state risk.

### 4. §23 / UD-007 measurement and choice — synchronous

`web.CraftingDiscoveryApiRealDbPerfIT`, real PostgreSQL, no schema override, real embedded server,
timed from issuing the request to holding the complete response body (routing, calculation and full
JSON serialization inside the timer; server startup, ~1.4 s, outside it and stated as such).
Environment: OpenJDK 25.0.1, Maven 3.9.16 via `./mvnw`, Windows 10 Pro for Workstations 19045, local
PostgreSQL on 5432. The scope is read from the database rather than hard-coded — the first synced
crafting combination, i.e. what the JavaFX page itself selects on opening — with Discovery's own
default settings. Character names are omitted here as private account content
(`TEST_STRATEGY.md` §34); the run output carries them.

Page-load-equivalent scope (Armorsmith 500, 672 rows, 721 KB):

| Run | Elapsed |
|---|---|
| FIRST (cold JVM/JIT, cold DB/OS cache, cold graph cache) | **1304 ms** |
| REPEAT 1 | 628 ms |
| REPEAT 2 | 484 ms |
| REPEAT 3 | 329 ms |

One warm request per *other* synced combination (19 more, so the maximum is a real worst case rather
than one favourable scope), covering 0 to 1085 rows and up to 1.1 MB: **258–515 ms**, including the
largest result sets (Weaponsmith 500 / 1085 rows / 1135 KB in 308 ms, Tailor 500 / 728 rows in
404 ms, Huntsman 441 / 978 rows in 299 ms). An earlier full run of the same test gave the same
picture (first 1199/1051 ms, warm 267–697 ms), so the figures are stable across runs.

**Choice: synchronous.** This is not a GW2-API synchronization endpoint, so UD-007 required a
measured decision. Measured maximum 1304 ms — cold, once — with every warm request under ~700 ms
across all 20 combinations the account can ask for, is comfortably inside a normal HTTP request. It
is therefore served synchronously: no background task, no status endpoint, no queue, and no
competing mechanism introduced. Had the measurements said otherwise, the asynchronous path would
have been required; they did not. This is a routing decision from observed behavior, not a
performance target — no Discovery optimization was attempted or claimed.

### 5. Tests

**Default `./mvnw test` run — 209 tests, 0 failures, 0 errors** (189 pre-existing, all passing; 20
new):

- `web.CraftingDiscoveryApiControllerTest` (20) — standalone MockMvc over the real controller,
  advice and message conversion, with a recording stub behind the constructor seam (TEST_STRATEGY.md
  §35.1). Covers: full valid request reaching the service as the right `CHAR_DISCIPLINE` choice,
  settings and inventory character; Discovery's own defaults asserted explicitly against Profit's;
  blank inventory character arriving as null; complete row mapping including authoritative total,
  blocked reason, both missing-material maps, unknown item and unquoted item; blocked and
  result-less rows reported not omitted; nothing-left-to-discover as an empty 200; **seven**
  validation rejections each asserting no service was created; malformed JSON; unknown path as 404;
  `SQLException` → 503 without leaking the connection string; `RuntimeException` → 500; successive,
  concurrent, and empty-after-full request isolation.
- `web.Gw2ApiApplicationBootTest` (2, extended) — the context now also exposes the Discovery
  controller and hands out a fresh Discovery service per call. Still never touches the database.
- `web.CraftingProfitApiControllerTest` (17, unchanged except one message string) — the shared
  advice's 500 message was generalized from "The crafting profit calculation could not be completed"
  to "The crafting calculation could not be completed" (and the malformed-body message likewise), so
  one route's wording does not describe the other's failure. Error **codes**, statuses and every
  field name are unchanged; this is the only externally visible change to the Profit contract.

**Explicit real-database runs** (`*IT`, excluded from the default goal by Surefire's patterns):

- `web.CraftingDiscoveryApiRealDbPerfIT` — 1 test, passing, figures in §4.
- `web.CraftingDiscoveryApiRealDbEquivalenceIT` — 2 tests, both passing. All **672 rows** match the
  in-process `CraftingDiscoveryService.reload(...)` field by field — count, order, `minRating`,
  `craftableCount`, `buyCostCopper`, `matsSellValueCopper`, `revenueCopper`, `profitCopper`,
  `totalProfitCopper`, `blockedReason` and both missing-material maps — both with the scope's
  character selected as inventory owner and with none selected (the STORY-DOM-012 fallback), the
  second also on listing prices. Both runs read-only, moments apart, no synchronization between.
- `web.CraftingProfitApiRealDbEquivalenceIT` — re-run after the shared-mapper extraction: all
  **3151 rows** still equivalent across both settings combinations. This is what proves the
  refactor preserved Profit's values, not just its field names.

**JavaFX Discovery regressions** (acceptance criterion 6): `uiverify.CraftingDiscoveryViewBlockedRowIT`
and `uiverify.CraftingDiscoveryViewRefreshPreservationIT` — both passing on the final tree, plus
`application.CraftingDiscoveryServiceTest` and the controller tests in the default suite. No JavaFX
source file changed, so no new UI test was added (`TEST_STRATEGY.md` §33). Crafting mathematics is
not re-tested at this layer (§11).

### 6. Files

New: `src/main/java/web/{CraftingDiscoveryApiController,CraftingDiscoveryApiMapper,CraftingRowMapper}.java`,
`src/main/java/web/dto/{CraftingDiscoveryRequest,CraftingDiscoveryResponse,CraftingRowDto,MissingItemDto,TradingPostQuoteDto}.java`,
`src/test/java/web/{CraftingDiscoveryApiControllerTest,CraftingDiscoveryApiRealDbPerfIT,CraftingDiscoveryApiRealDbEquivalenceIT}.java`.

Modified: `src/main/java/web/{Gw2ApiApplication,ApiExceptionHandler,ApiValidationException,CraftingProfitApiMapper}.java`,
`src/main/java/web/dto/CraftingProfitResponse.java`,
`src/test/java/web/{Gw2ApiApplicationBootTest,CraftingProfitApiControllerTest}.java`. No `pom.xml`
change was needed. Docs: `docs/CURRENT_ARCHITECTURE.md` (§2 layout, §3 responsibilities, §4
dependency direction, §5.5 row-type note, new §5.6) and `docs/TEST_STRATEGY.md` §35 intro.
`TARGET_ARCHITECTURE.md` was deliberately not edited: §9's example route list and §23's policy are
unchanged by this story, and the implemented route is a current-state fact owned by
`CURRENT_ARCHITECTURE.md`.

Configuration is not hard-coded; no API key or database credential is read or exposed by the HTTP
layer, which reaches the database only through the same `repo.Db`/`EnvConfig` path JavaFX uses.

### Limitations

- **Resolution tree not on this route**, for the same reason as Profit: `CraftResult.tree` is null
  in bulk reload results because the service builds it lazily per selected row, so exposing it — and
  Discovery's shopping-list view over it — needs a separate detail endpoint. Not built here.
- **No authentication**, unchanged from STORY-API-001 and `TARGET_ARCHITECTURE.md` §20. The new route
  serves account-derived data to any caller that can reach the port, exactly as the Profit route
  does. Flagged again, not silently changed.
- **§33's seven-second budget is not addressed by this story** and no claim about it is made here:
  the measurement above is backend request time for a route with no frontend.
- **Sync/refresh endpoints remain out of scope** (STORY-API-003 onward). Milestone completion is not
  declared, and the Phase 4 PROJECT HEALTH REVIEW exit criterion remains open.

## Blockers

None.
