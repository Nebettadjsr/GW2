## Story ID

STORY-API-001

## Title

Add a Spring Boot HTTP boundary for Crafting Profit

## Status

DONE

## Milestone

milestone-04

## Goal

Establish the additive Spring Boot API runtime and expose the existing Crafting Profit application service through a thin, tested HTTP contract while preserving accepted calculation behavior and performance.

## Authoritative Source Documents / Sections

- `docs/ROADMAP.md` section 8, Phase 4 objective, exit criteria and calculation/DTO/API-test stories.
- `docs/TARGET_ARCHITECTURE.md` sections 9, 23, 30 and 33.
- `docs/TEST_STRATEGY.md` section 11.
- `agent/user-decisions/UD-006-backend-web-framework.md` and `agent/user-decisions/UD-007-http-long-running-sync-approach.md`, resolved User Decisions.

## Context

The supplied backlog records the extraction of `application.CraftingProfitService` and accepted performance evidence. Phase 4 requires HTTP translation over that boundary. The framework is now explicitly chosen; compatibility and endpoint runtime have not been verified by planning. Section 9's route names are illustrative, not a fixed contract.

## Acceptance Criteria

1. Add a runnable Spring Boot HTTP entry point wired to the existing Profit application service. Verify and record the chosen supported version's compatibility with the actual Java/Maven setup. JavaFX retains its existing entry point and in-process application calls without UI modifications.
2. Define and document the Profit route, request fields, defaults, validation, response schema and error/status mappings at the API boundary. Map the existing supported calculation inputs and complete results without inventing domain behavior or rebuilding orchestration in the controller.
3. Use explicit transport DTOs distinct from domain, persistence and GW2 API JSON objects. Preserve authoritative values and blocked/domain states rather than recomputing totals or dropping unavailable rows during mapping.
4. Delegate business behavior to the existing service; keep Spring and HTTP concerns outside application/domain behavior. Ensure concurrent or successive HTTP requests do not mix request-specific calculation results or state.
5. Measure representative real-database request runtimes, including first and repeat default-All calculations and complete response serialization. Record settings, environment, data scale, cache state, individual timings and maximum. Apply section 23's measured synchronous/asynchronous choice; if tasks are necessary, provide status reporting and access to complete results.
6. Reverify section 33 at the backend/API boundary, preserving complete results and the accepted seven-second full-page requirement. API timing is only part of that budget, not proof of full-page performance. Record any remaining performance gap honestly; do not omit data, reduce limits or introduce stale results to meet timing.
7. HTTP contract tests cover valid requests, invalid input, applicable missing-resource cases and mapped domain failures. Existing JavaFX Profit behavior remains verified through relevant existing checks.

## Required Tests

- HTTP tests with controlled application collaborators for request validation, delegation, complete DTO mapping and success/error status contracts; invalid requests must not invoke calculations.
- Successive/concurrent request checks with distinct inputs/results to detect shared-state leakage at the new boundary.
- Relevant existing Profit application/domain and JavaFX regressions; retain the existing test methodology rather than recreating crafting mathematics in API tests.
- Real-user-database API timing and result-equivalence checks required by acceptance criteria 5 and 6; record measurements separately from fixture-based contract tests.

## Constraints

Keep scope to runtime setup and the Profit API. Do not migrate JavaFX to HTTP, add other feature endpoints, change crafting rules, or introduce speculative optimization. Choose framework versions from implementation-time evidence. No full queue/distributed job platform is required. A new material product or architectural trade-off needs the normal user-decision workflow.

## Dependencies

None. UD-006 and UD-007 are resolved; the supplied current phase establishes Phase 3 as the prerequisite already passed.

## Definition of Done

The runnable HTTP boundary, DTO/error contract and targeted tests meet the acceptance criteria. Record actual verification, runtime evidence and limitations in Result; update current architecture and testing documentation only for implemented facts and reusable methodology. Do not declare milestone completion.

## Result

Done. A Spring Boot HTTP boundary (`web.*`) exposes the existing `application.CraftingProfitService`
over `POST /api/crafting/profit`. JavaFX is untouched.

### 1. Runtime and verified compatibility

**Spring Boot 4.1.1** — the latest GA at implementation time, selected from the published version
list rather than assumed (4.2.0-M1 exists but is a milestone). It pulls Spring Framework 7.0.9 and
embedded Tomcat 11.0.24.

Compatibility with the *actual* setup was verified, not asserted. Environment: OpenJDK 25.0.1+8-27
(Oracle, `C:\Users\Administrator\.jdks\openjdk-25.0.1`), Apache Maven 3.9.16 via `./mvnw`, Windows 10
Pro for Workstations 19045, local PostgreSQL on 5432. Evidence:

- `./mvnw dependency:resolve` / `dependency:list` — full resolution, BUILD SUCCESS.
- `./mvnw test` — 189 tests, 0 failures, 0 errors (170 pre-existing + 19 new).
- `web.Gw2ApiApplicationBootTest` (`@SpringBootTest`, RANDOM_PORT) — context starts, server binds.
- A real run: `GW2_API_PORT=18080 ./mvnw spring-boot:run` logged
  `Tomcat started on port 18080` / `Started Gw2ApiApplication in 1.502 seconds`, and `curl` against it
  returned HTTP 200 with 3151 rows / 1,306,198 bytes for the default request, HTTP 400 for an invalid
  `scope.kind`, and HTTP 200 / 433 rows for a `DISCIPLINE` scope with non-default settings.

**Two dependency-version consequences, recorded because they are real and were observed, not
predicted.** Adding the `spring-boot-dependencies` BOM changes two versions the project previously
pinned:

- JUnit Jupiter 5.10.2 → **6.0.3**. Not cosmetic: `spring-test` 7 requires a current JUnit, and
  keeping the old pin would have left a split JUnit platform. The pin was removed so the BOM manages
  it. All 170 pre-existing tests pass unchanged, and the TestFX (`testfx-junit5` 4.0.18) sources still
  compile against it.
- Jackson 2 databind 2.17.2 → **2.21.5** (annotations 2.21). The first attempt kept 2.17.2 by importing
  `jackson-bom` ahead of the Spring BOM; that *failed at runtime* with
  `NoClassDefFoundError: com/fasterxml/jackson/annotation/JsonSerializeAs`, because Spring Boot 4
  serves HTTP with Jackson 3 (`tools.jackson` 3.1.5) and the two Jackson generations share the
  `com.fasterxml.jackson.annotation` artifact. The independent pin was therefore removed and the whole
  Jackson line left BOM-aligned. Jackson-facing tests (`parser.*`, `sync.*`, graph-cache paths) pass.

The `spring-boot-maven-plugin` is declared with **no `<executions>`**, so `repackage` never runs and
the existing `gw2-tool` jar and JavaFX launch (`${main.class}` = `Gw2App`, javafx-maven-plugin) are
byte-for-byte unaffected. No JavaFX source file was modified by this story.

### 2. Documented contract

`POST /api/crafting/profit`. Full prose contract lives in `docs/CURRENT_ARCHITECTURE.md` §5.5 (its
owner); summary:

- **Body optional.** Absent body, absent members and absent fields all fall back to the JavaFX Profit
  view's own opening defaults — `useOwnMats` true, `allowBuying` false, `maxBuyCopper` 10000 (its "1g"
  field), instant sell, instant buy, daily items bought — so `{}` reproduces the default page load.
- **Scope** `ALL` (default) / `DISCIPLINE` / `CHARACTER_DISCIPLINE`, mapping onto `repo.DiscChoice`'s
  three factories.
- **Validation** (all before any service is created): unknown `kind`; missing `discipline` for
  `DISCIPLINE`/`CHARACTER_DISCIPLINE`; missing `characterName` for `CHARACTER_DISCIPLINE`; negative
  `rating`; negative `maxBuyCopper`. Blank strings are treated as absent.
- **Response** echoes the effective scope/settings plus `rowCount` and one row per visible recipe.
- **Statuses**: 200; 400 `INVALID_REQUEST`/`MALFORMED_REQUEST`; 503 `DATA_STORE_UNAVAILABLE`
  (`SQLException`); 500 `CALCULATION_FAILED` (anything else, including the graph-cache failure the
  service wraps in a `RuntimeException`). The advice is scoped with `assignableTypes` so it cannot
  swallow the framework's own 404/405 handling. Only validation messages — composed from the request
  contract — reach the caller; failure detail is logged server-side.
- **No missing-resource case exists on this route**, and none was invented: a scope naming a character
  or discipline with no recipes is a legitimate empty 200, verified by test. 404 is only the
  framework's unknown-path response, also covered by a test.

No calculation input was added or reinterpreted: the route exposes exactly `DiscChoice` ×
`CraftingSettings`, the pair the existing use case accepts.

### 3. Transport DTOs

`web.dto.CraftingProfitRequest`, `CraftingProfitResponse` (with nested `EffectiveScopeDto`,
`EffectiveSettingsDto`, `RowDto`, `MissingItemDto`, `TradingPostQuoteDto`) and `ApiErrorResponse` —
records used nowhere but the boundary. No `craft.*`, `repo.*` or GW2-API JSON type is serialized.

Preservation rather than recomputation: `totalProfitCopper` is copied from `CraftResult`, never
derived from count × per-craft profit; `blockedReason` is carried as the `craft.BlockedReason` name;
both missing-material maps are carried in full (sorted by item id only so the wire order is stable);
rows whose calculation produced no result are reported with `resultAvailable: false` instead of being
dropped, unlike the JavaFX controller's `continue`. Raw trading-post quotes are passed through for the
output and every missing material, so a client can render its own price-unavailable marker without the
API deciding that rule. Presentation text (status strings, "no TP" markers, search blobs) is
deliberately absent — see Limitations for the resolution tree.

### 4. Delegation and request isolation

`CraftingProfitApiController` validates, calls `CraftingProfitService.reload(...)`, and maps the
result. No crafting rule, no orchestration, no total. `web.*` is the only package importing Spring —
verified by grep over `src/main/java`: no `application.*`, `craft.*`, `ecto.*`, `repo.*` or `sync.*`
file imports a Spring, servlet or HTTP type, and no JavaFX class imports `web.*`.

`CraftingProfitService` keeps the last reload in instance fields (backing its lazy tree lookup), so the
controller takes a `Supplier<CraftingProfitService>` and builds **one service per request**, reading
only the `ProfitData` that call returned; nothing request-specific is held in a field. This costs no
extra work — the graph cache is re-read per `reload()` on the JavaFX path too. Proven by two tests:
successive requests with distinct inputs, and 8 concurrent requests whose stubbed results are derived
from each request's own input (a leaked result would surface as a mismatched recipe id), both also
asserting one distinct service instance per request.

### 5. Measured runtime (real database)

`web.CraftingProfitApiRealDbPerfIT`, real PostgreSQL, no schema override, default All scope (`{}`),
timed from issuing the request to holding the complete response body — routing, calculation and full
JSON serialization all inside the timer. Data scale: 3151 visible recipes, 13141 graph recipes, 13836
item ids, 9426 TP quotes, 8 characters, 1,306,198-byte response.

| Run | Elapsed |
|---|---|
| FIRST (cold JVM/JIT, cold DB/OS cache, cold graph cache) | **1981 ms** |
| REPEAT 1 | 942 ms |
| REPEAT 2 | 919 ms |
| REPEAT 3 | 714 ms |
| **Maximum** | **1981 ms** |

Comparison against the application layer alone, same session/database
(`application.CraftingProfitServiceRealDbPerfIT`): FIRST 1829 ms, REPEAT 937 ms. HTTP + DTO mapping +
JSON serialization therefore add roughly 150 ms on the cold run and nothing distinguishable from noise
on warm runs. Server/context startup (~1.5 s) is outside the per-request timer, as it should be for a
per-request figure.

**§23 / UD-007 choice: synchronous.** This is not a GW2-API synchronization endpoint, so UD-007 required
a measured decision. Measured maximum 1981 ms is consistently and comfortably inside a normal HTTP
request, so it is served synchronously — no background task, no status endpoint, no queue. Had it not
been, the asynchronous path would have been required instead; it was not needed and none was built.

### 6. §33 reverification at this boundary — and the honest gap

Result equivalence on real data (`web.CraftingProfitApiRealDbEquivalenceIT`): for both the default
settings and an own-mats + buying-at-10g + listing-prices combination, all **3151 rows** match the
in-process `CraftingProfitService.reload(...)` field by field — count, order, `craftableCount`,
`buyCostCopper`, `matsSellValueCopper`, `revenueCopper`, `profitCopper`, `totalProfitCopper`,
`blockedReason` and both missing-material maps. Complete results are preserved: nothing was omitted, no
limit reduced, no cache-staleness introduced, no recipe dropped.

**The seven-second requirement is not proven by this story, and this measurement must not be read as
proving it.** §33 measures navigation to a complete, populated, interactive page. What is measured here
is backend request time only. The remaining, unmeasured parts of that budget are transport of a 1.3 MB
response to a browser, client-side parsing, and rendering ~3151 rows — none of which exists yet, since
there is no web frontend (Phase 5). Against the 7000 ms budget, 1981 ms of it is now accounted for,
leaving ~5000 ms for transport and rendering; whether that suffices is unknown and must be measured in
Phase 5. The accepted JavaFX page-load evidence in `STORY-PERF-001` stands and is unaffected — no
JavaFX or calculation code changed.

One honest observation for the frontend phase: a 1.3 MB full-scope response is large enough that
transport/rendering, not backend time, is the likely risk to the budget. That is an observation from
this measurement, not a change this story was authorized to make.

### 7. Tests

**Default `./mvnw test` run — 189 tests, 0 failures, 0 errors** (170 pre-existing, all passing
unchanged; 19 new):

- `web.CraftingProfitApiControllerTest` (17) — standalone MockMvc over the real controller, advice and
  message conversion, with a recording stub behind the constructor seam. Covers: empty and absent body
  defaulting; all three scope kinds reaching the service as the right `DiscChoice`; explicit settings
  passed through unchanged; complete row mapping including authoritative total, blocked reason, output
  and missing-material quotes, unknown item name and unquoted item; blocked and result-less rows
  reported not omitted; empty scope as 200; five validation rejections **each asserting no service was
  created**; malformed JSON; unknown path as 404; `SQLException` → 503 without leaking the connection
  string; `RuntimeException` → 500; successive and concurrent request isolation.
- `web.Gw2ApiApplicationBootTest` (2) — context/server startup, and the factory handing out a fresh
  service per call. Never touches the database.

**Explicit real-database runs** (`*IT`, excluded from the default goal by Surefire's patterns):

- `web.CraftingProfitApiRealDbEquivalenceIT` — 2 tests, both passing, figures in §6 above.
- `web.CraftingProfitApiRealDbPerfIT` — 1 test, passing, figures in §5 above.
- `application.CraftingProfitServiceRealDbPerfIT` — re-run unchanged for the comparison baseline.

Existing JavaFX Profit behavior is verified by the existing suite, which passes unchanged. Per
`TEST_STRATEGY.md` §33, no TestFX run was added: this story changed no view, controller or displayed
value. Crafting mathematics is not re-tested here (`TEST_STRATEGY.md` §11).

### 8. Files

New: `src/main/java/web/{Gw2ApiApplication,CraftingProfitApiController,CraftingProfitApiMapper,ApiExceptionHandler,ApiValidationException}.java`,
`src/main/java/web/dto/{CraftingProfitRequest,CraftingProfitResponse,ApiErrorResponse}.java`,
`src/main/resources/application.properties`,
`src/test/java/web/{CraftingProfitApiControllerTest,Gw2ApiApplicationBootTest,CraftingProfitApiRealDbPerfIT,CraftingProfitApiRealDbEquivalenceIT}.java`.

Modified: `pom.xml` only. Docs: `docs/CURRENT_ARCHITECTURE.md` (§2 layout/build, §3 responsibilities,
§4 dependency direction, new §5.5, §8 configuration), `docs/TEST_STRATEGY.md` (new §35, Layer 6
methodology). `TARGET_ARCHITECTURE.md` was deliberately not edited: §30's framework decision and §23's
policy are unchanged by this story, and the implemented versions are current-state facts owned by
`CURRENT_ARCHITECTURE.md`.

Configuration is not hard-coded: the port is `server.port=${GW2_API_PORT:8080}`. No API key or database
credential is read, exposed or added by the HTTP layer; it reaches the database only through the same
`repo.Db`/`EnvConfig` path the JavaFX application uses.

### Limitations

- **Resolution tree not on this route.** `CraftResult.tree` is null in bulk reload results — the service
  builds it lazily per selected row — so exposing it means a separate detail endpoint. Documented in
  `CURRENT_ARCHITECTURE.md` §5.5; not built here, as the story scopes to the Profit route.
- **Backend timing only** for §33 (see §6). The navigation-to-complete-page measurement needs Phase 5.
- **No authentication**, matching `TARGET_ARCHITECTURE.md` §20 (TBD, only if required). The endpoint
  binds `server.port` on all interfaces and serves account-derived data to any caller that can reach
  it — acceptable for the current local single-user deployment, and a decision to revisit before any
  non-local deployment. Flagged, not silently changed.
- **Discovery and sync/refresh endpoints are not in this story** (STORY-API-002 and the remaining
  Phase 4 stories). Milestone completion is not declared, and the Phase 4 PROJECT HEALTH REVIEW exit
  criterion remains open.

## Blockers

None.
