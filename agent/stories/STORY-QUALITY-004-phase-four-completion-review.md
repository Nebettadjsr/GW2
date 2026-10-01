## Story ID

STORY-QUALITY-004

## Title

Review Phase 4 project health before milestone completion

## Status

DONE

## Milestone

milestone-04

## Goal

Perform the bounded Phase 4 PROJECT HEALTH REVIEW required before milestone closure, assessing the additive HTTP boundary against the supplied Phase 4 exit criteria and recording concrete findings for subsequent planner disposition.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md supplied section 8, Phase 4: objective, dependencies and exit criteria only.
- docs/TARGET_ARCHITECTURE.md sections 9, 23, 30, 33 and 34: HTTP boundaries, decided operation policy/framework, performance preservation and bounded review policy.
- agent/stories/STORY-API-001-spring-boot-crafting-profit-endpoint.md through agent/stories/STORY-API-005-price-refresh-task-endpoint.md: Phase 4 implementation scope and recorded results.

## Context

The supplied backlog records completion of the calculation endpoints and account/global sync endpoints. STORY-API-005 covers the remaining price-refresh variants and must finish before this review executes. No Phase 4 review is recorded. Implementation-story results are evidence inputs, not automatic proof of milestone closure.

## Acceptance Criteria

1. Assess every supplied Phase 4 exit criterion with concrete evidence and an explicit satisfied, unsatisfied or uncertain assessment. Restrict roadmap assessment to Phase 4; do not inspect or plan later phases.
2. Confirm the documented framework and long-running-operation decisions are respected. Assess crafting Profit/Discovery and account/global/price-refresh contracts, application-service delegation, absence of controller business logic, transport DTO separation and protection against GW2 JSON or internal-object leakage.
3. Assess existing contract-test evidence for valid/invalid input and mapped failures, including task admission/status behavior where applicable. Confirm the additive API leaves JavaFX callers working in-process with their existing behavior.
4. Assess the recorded real-user-database Crafting Profit API-boundary performance evidence under section 33, its measurement conditions and its applicability to the completed Phase 4 state. Distinguish backend timing from the full page-load budget; identify concrete evidence gaps without inventing timings or claiming unmeasured page performance.
5. Compare current architecture, relevant target requirements, known problems and verification documentation against the completed Phase 4 implementation. Report concrete contradictions, unresolved relevant decisions or risks, and verification weaknesses. Correct only small, clearly evidenced documentation errors at their authoritative owner.
6. Record evidence, corrections and findings in Result. Each finding identifies affected components, supporting evidence, impact and whether it blocks completion. Any proposed criterion transfer needs an explicit destination and rationale plus subsequent planner/user disposition; a proposal does not satisfy a criterion. Finish with a Phase 4 health assessment without declaring milestone closure.

## Required Tests

Assess existing tests, saved results and implementation evidence against concrete Phase 4 review questions. Run only targeted checks needed to resolve a specific uncertainty, recording the question, actual outcome and limitations. Use existing API, application, JavaFX compatibility or real-database performance checks when the evidence question warrants them. Do not automatically run every suite or add tests merely because a review occurred.

## Constraints

Assessment first, remediation later, as required by TARGET_ARCHITECTURE.md section 34. Explicit Non-Goals: no broad bug hunting, automatic broad regression testing, arbitrary test expansion, test-strategy redesign, speculative cleanup/refactoring, architecture implementation or later-milestone work. Planned future replacement alone is not evidence of current debt. Do not implement substantial fixes or create remediation stories during the review. Report unresolved findings for a subsequent normal planning pass; review completion does not close the milestone. Do not alter domain behavior, reopen the accepted prerequisite performance gate without concrete evidence, or treat API timing as full-page acceptance.

## Dependencies

STORY-API-001, STORY-API-002, STORY-API-003 and STORY-API-004 (recorded DONE); STORY-API-005 must be DONE before this review executes.

## Definition of Done

The bounded assessment covers all supplied Phase 4 exit criteria and section 34 review dimensions. Result records traceable evidence, any small authoritative documentation corrections, concrete findings and verification limitations, leaving milestone closure and any separately scoped remediation to subsequent planning.

## Result

**Review executed; Phase 4 / milestone-04 is NOT declared closed** (`TARGET_ARCHITECTURE.md` §21:
review completion is distinct from milestone completion). No production code was changed. Five small
documentation corrections were made at their authoritative owners (§4 below). Eight findings are
recorded for planner/user disposition (§5); none of them falsifies a Phase 4 exit criterion.

Reviewed tree: HEAD `ea264f1` plus the uncommitted working tree (see F1), i.e. the state
`STORY-API-005` recorded its evidence against — no source file has changed since.

### 1. Phase 4 exit criteria (`ROADMAP.md` §8) — per-criterion assessment

All assessment below is restricted to Phase 4; no later phase was inspected or planned.

**(a) "Preserve and reverify the accepted Crafting Profit performance requirement at the backend/API
boundary (§33); API timing is only one part of the full page-load budget." — SATISFIED.**
`web.CraftingProfitApiRealDbPerfIT` measures the real database through the real embedded server and
the real application service (no `gw2tool.test.schema` override), default All scope with the JavaFX
view's settings, one cold request plus three repeats, wall-clock from issuing the request to holding
the complete response body. Recorded maxima across the phase: **1981 ms** (`STORY-API-001`),
**2344 ms** (`STORY-API-003`), **1672 ms** (`STORY-API-004`), **1794 ms** (`STORY-API-005`, on the
reviewed tree; 3151 rows, 1 305 944 bytes) against §33's 7000 ms. Every record states that this is
backend request time only. Detailed assessment of these conditions and their gaps is §3 below.

**(b) "Complete a bounded PROJECT HEALTH REVIEW before declaring this milestone complete (§34)." —
SATISFIED by this story's execution.** Closure itself remains the planner's/user's decision.

**(c) "Explicit decision made on the backend web framework (§30 marks this TBD — must not be
silently finalized)." — SATISFIED.** `agent/user-decisions/UD-006-backend-web-framework.md` is
RESOLVED and `TARGET_ARCHITECTURE.md` §30 now carries Spring Boot under **Decided**, not TBD. The
implementation matches: `pom.xml` `<spring.boot.version>4.1.1</spring.boot.version>` with the
`spring-boot-dependencies` BOM, `spring-boot-starter-web`, and `web.Gw2ApiApplication` as the entry
point (`./mvnw spring-boot:run`). No framework was adopted ahead of the decision.

**(d) "HTTP endpoints exist for at least the crafting profit/discovery calculations and the
sync/refresh operations sketched in §9." — SATISFIED.** Present and wired in
`web.Gw2ApiApplication`: `POST /api/crafting/profit`, `POST /api/crafting/discovery`,
`POST /api/sync/account`, `POST /api/sync/global`, `POST /api/prices/refresh` (all five are §9's own
example paths), plus the supporting `GET /api/sync/tasks/{taskId}`. §9's read examples
(`GET /api/account/materials`, `/api/account/bank`, `/api/items/{id}`, `/api/recipes/{id}`) are not
implemented; the criterion's "at least" wording does not require them, and §9 states the names are
examples, not a fixed contract. Recorded as fact F8, not as a criterion failure.

**(e) "Controllers/routes contain no business logic (thin translation only)." — SATISFIED.** Read in
full: `CraftingProfitApiController` (63 lines) and `CraftingDiscoveryApiController` (69) default and
validate through their mapper, call one application-service method, map the returned data.
`GlobalSyncApiController`, `AccountSyncApiController` and `PriceRefreshApiController` validate, then
submit a single method reference (`globalDataRefreshService::refreshAll`,
`accountRefreshService::refreshAll`, `PriceRefreshVariant.bodyOf(...)` → `refreshForProfit`/
`refreshForDiscovery`) to `web.task.BackgroundTaskService` and return the identifier. No crafting
rule, no sync step ordering, no total and no item-relevance selection is restated or re-derived in
`web.*`; `CraftingRowMapper`/`CraftingProfitApiMapper`/`CraftingDiscoveryApiMapper` copy the domain's
own values (`result.totalProfitCopper` is passed through, never recomputed).

**(f) "Transport DTOs are distinct from domain objects; GW2 API JSON shapes do not leak into HTTP
responses." — SATISFIED.** Every response type is a `web.dto` record composed of primitives,
`String`s and other `web.dto` records (`CraftingProfitResponse`, `CraftingDiscoveryResponse`,
`CraftingRowDto`, `MissingItemDto`, `TradingPostQuoteDto`, `SyncTaskAcceptedResponse`,
`SyncTaskStatusResponse`, `ApiErrorResponse`). Verified by import inspection that **no class in
`src/main/java/web/` imports `parser.*`, `model.*` or `api.*`** — the packages that hold GW2 JSON
parsing and response shapes — so no GW2 payload shape can reach a response. Internal objects are read
but never handed out: `CraftingRowMapper` copies `ItemRepository.ItemInfo.name` and `PriceQuote`'s two
unit prices into DTO fields; `craft.CraftResult`/`craft.Recipe` never appear in a response type. See
F4 for the inbound-type coupling this leaves.

**(g) "Backend API tests exist per `TEST_STRATEGY.md` §11 (valid/invalid input, mapped domain
failures)." — SATISFIED.** §3 below records the coverage and the targeted run.

**(h) "The JavaFX UI continues to work unmodified (still calling application services in-process) —
the API is additive at this point." — SATISFIED.** Verified three ways: (i) no file outside
`src/main/java/web/` imports `web.*`, and no file outside it imports `org.springframework.*` or
`jakarta.servlet.*`, so neither entry point can reach the other; (ii) every Phase 4 change still
outstanding in the working tree is confined to `web/*` and its tests (`git status`), so
`application.*`, `craft.*`, `repo.*`, `sync.*` and all JavaFX classes are untouched by
`STORY-API-004`/`STORY-API-005`; (iii) the JavaFX regressions were re-run per story
(`STORY-API-005`: `CraftingProfitViewTpRefreshIT`/`CraftingDiscoveryViewTpRefreshIT`, 4 pass;
`STORY-API-004`: `Gw2AppSyncGlobalDataIT`/`Gw2AppSyncAccountIT`, 2 pass). The one non-`web` change
Phase 4 did make is the build's dependency set — see F3.

### 2. Decided framework and long-running-operation policy (§30 / §23 / UD-006 / UD-007)

**Respected.** UD-007/§23 requires GW2 API synchronization to run as asynchronous backend tasks with
status reporting, and everything else to follow *measured* behavior rather than assumption. That is
exactly what the implementation does and what the records say:

- The two calculation routes are **synchronous by measurement** — `STORY-API-001` recorded 1981 ms
  max before choosing, `STORY-API-002` recorded 1304 ms cold / 258–700 ms warm across all 20 synced
  combinations. No task mechanism was introduced for them.
- The three trigger routes are **asynchronous by decision, not by assumed duration**, and each
  controller's javadoc says so in those words ("No duration was measured or assumed to reach that
  choice"). Each returns 202 with `taskId`/`operation`/`statusUrl` and a `Location` header and
  carries **no outcome field**, so acceptance cannot be read as completion.
- Simplicity per §23 ("as simple as practical", "no queue system"): one in-process
  `web.task.BackgroundTaskService` (161 lines, imports only `java.*`), one virtual thread per task,
  at most one unfinished task per operation key, bounded in-memory retention. No queue, no scheduler,
  no persistence — and the absence of persistence, cancellation, progress and retry is stated in each
  story rather than implied.
- The application layer stays framework-free: `BackgroundTaskService` is handed a method reference,
  and `application.AccountRefreshService`/`GlobalDataRefreshService`/`TradingPostPriceRefreshService`
  are unaware of tasks, identifiers or admission. `web.*` is the only package importing Spring.

Contracts assessed against their specs: Profit accepts an optional body defaulting to the JavaFX
view's opening state; Discovery requires a scope and keeps its *own* defaults (buying on, 20g) with
`dailyBuyInsteadOfCraft` fixed false and echoed; the account and global triggers accept no fields;
price refresh requires exactly one field, `variant`, with two values and deliberately no combined
"all" option. Every rejection happens before a task is admitted or a service is constructed.

### 3. Contract-test evidence and the targeted check

Coverage read per file against §11's list (valid request → status/response; invalid input →
validation error; missing resource → expected error; domain failure → mapped API error):

- **Valid input:** default/empty/absent-body Profit request, all three scope kinds, Discovery scope +
  inventory character + explicit settings, complete row mapping including authoritative totals,
  blocked rows and missing-material maps, 202 acceptance bodies and `Location` header on all three
  triggers, per-variant exactly-once delegation.
- **Invalid input:** 14 distinct rejection tests across the calculation routes (unknown scope kind,
  missing discipline/character/rating, negative rating, negative `maxBuyCopper`, malformed JSON,
  absent-but-required body…) and, on the triggers, unexpected field / unreadable body / missing,
  blank, miscased, non-string and unsupported `variant` (including the plausible combined value).
  Each asserts **no service instance was created** or **no task was admitted**.
- **Missing resource:** `unknownPathUnderTheApiIsANotFound`, `unknownPathUnderTheDiscoveryRouteIsANotFound`
  (framework 404 preserved by `assignableTypes`-scoped advices), `anIdentifierThisProcessCannotResolveIs404`
  plus `anUnresolvableIdentifierIsNotEchoedBackToTheCaller`.
- **Mapped failures:** `SQLException` → 503 `DATA_STORE_UNAVAILABLE` without leaking the connection
  string, other `RuntimeException` → 500 `CALCULATION_FAILED`, failed task → `SYNC_FAILED` /
  `DATA_STORE_UNAVAILABLE` asserted to contain neither success wording nor the exception's own text.
- **Task admission/status:** latch-controlled proof that the trigger returns while the use case is
  still blocked and the status route already answers `RUNNING`; 409 on a repeat of the same
  operation; cross-operation independence (global does not block account; an unfinished profit
  refresh does not block discovery); readmission after completion; retention eviction; unknown id.

**Targeted check run for this review** (question: do the per-story suite results hold on the
combined final Phase 4 tree?): `./mvnw -o test` → **Tests run: 262, Failures: 0, Errors: 0**,
`BUILD SUCCESS`. This matches `STORY-API-005`'s recorded figure exactly. No other suite was run: the
real-database `*IT` evidence was recorded by `STORY-API-005` against this identical tree, and §34
forbids running suites merely because a review occurred.

### 4. §33 performance evidence — conditions, applicability and gaps

**Conditions (read from `web.CraftingProfitApiRealDbPerfIT`):** real user database, real embedded
server on a random port, real `application.CraftingProfitService`, default All scope via an empty
`{}` body, one cold + three repeat requests, wall-clock request-to-complete-body (so routing,
calculation and full JSON serialization are inside the timer), context/server startup outside it.
Row count and response bytes are printed with each figure.

**Applicability to the completed Phase 4 state: good.** The 1794 ms maximum was measured on the
reviewed tree, and the measured path includes the Jackson-based `repo.CraftingGraphCache.load()` that
each reload performs. The figure is one part of §33's budget, never proof of it — a distinction every
story record makes explicitly, and which this review does not relax.

**Evidence gaps, stated without inventing timings:**

1. §33's actual requirement — navigation to a complete, populated, interactive page — remains
   measured only for the JavaFX path (`STORY-PERF-001`, max 2.8 s, accepted 2026-09-23). For the HTTP
   path there is no transport-to-browser or rendering measurement and no frontend to measure; the
   remainder of the budget is unquantified. This review asserts nothing about it.
2. The measurement is localhost client-to-server: no network, proxy or TLS cost is included.
3. Only the default All scope on the Profit route is timed at this boundary; other scopes and
   settings combinations are covered by the equivalence IT, not by timing.
4. The accepted full-page evidence predates a runtime dependency change Phase 4 made — see F3.
5. The timing IT asserts nothing and is excluded from the default goal — see F5.

### 5. Findings for planner/user disposition

**F1 — The reviewed Phase 4 implementation is not committed. (Non-blocking for the criteria; strongly
recommended before closure.)**
*Affected:* `web/GlobalSyncApiController.java`, `web/PriceRefreshApiController.java`,
`web/PriceRefreshVariant.java`, `web/SyncRequestValidation.java` and two test classes are untracked;
`web/AccountSyncApiController.java`, `web/Gw2ApiApplication.java`, `web/SyncApiExceptionHandler.java`
and `web/Gw2ApiApplicationBootTest.java` are modified against HEAD.
*Evidence:* `git log` (HEAD `ea264f1` = STORY-API-003), `git status --porcelain -- src/`.
*Impact:* every Phase 4 measurement, suite result and this review describe a tree state that exists
only in the working directory; a clean checkout of HEAD serves neither `/api/sync/global` nor
`/api/prices/refresh`. This is a recurrence of Phase 3 review finding F1, which was resolved by
committing. No exit criterion is falsified — they are met by the implementation, which exists.

**F2 — `ROADMAP.md` §3's phase overview still calls Phase 3 the current in-progress milestone.
(Non-blocking; documentation.)**
*Evidence:* §3 read against `BACKLOG.md` (milestone-03 archived, all five milestone-04 API stories
DONE). *Correction made:* the Phase 4 line, which said "not started", now records implementation
complete with closure pending. *Deliberately not corrected:* the Phase 3 line — rewriting it would
state a milestone-closure conclusion that §34 reserves for the planner/user. §8's per-criterion
markers were likewise left untouched, matching `STORY-QUALITY-003`'s precedent.

**F3 — The accepted §33 full-page evidence predates a runtime dependency change Phase 4 introduced.
(Non-blocking evidence gap; no regression observed.)**
*Affected:* `pom.xml`, `repo.CraftingGraphCache`, and the JavaFX Crafting Profit page-load path.
*Evidence:* the pre-Phase-4 `pom.xml` pinned `jackson.version` 2.17.2; `STORY-API-001` adopted the
Spring BOM, moving Jackson 2 to 2.21.5 (and JUnit to 6.0.3, test-only). `repo.CraftingGraphCache`
reads `crafting_graph_cache.json` with a Jackson 2 `ObjectMapper`, and
`application.CraftingProfitService.reload(...)` calls it on every load — so this dependency sits on
the page-load path that `STORY-PERF-001` measured on 2026-09-23, before the change.
`STORY-API-001`'s statement that the accepted evidence is "unaffected — no JavaFX or calculation code
changed" is true of source but does not cover the dependency delta.
*Impact:* bounded. The same cache read is inside the post-change backend measurements (max 1794 ms
including a cold request), so there is no evidence of a regression; what is unmeasured post-change is
the JavaFX-only remainder (view/controller/rendering), which Phase 4 did not touch. No page timing is
claimed here either way. Disposition options for the planner: re-run
`uiverify.CraftingProfitPageLoadRealDbPerfIT` once, or record the gap as accepted.

**F4 — Persistence types cross the application boundary into the HTTP layer. (Non-blocking; no exit
criterion affected.)**
*Affected:* `web.CraftingRowMapper` (imports `repo.ItemRepository`), `web.CraftingProfitApiMapper` /
`web.CraftingDiscoveryApiMapper` (import `repo.DiscChoice`), because
`application.CraftingProfitService.ProfitData` exposes `Map<Integer, ItemRepository.ItemInfo>` and
both services take a `DiscChoice`.
*Evidence:* import inspection of `src/main/java/web/`; `CraftingRowMapper.itemName(...)`.
*Impact:* no forbidden direction under §26 (which forbids Domain→PostgreSQL/HTTP/JavaFX, all of which
hold), and nothing leaks into a response — only `ItemInfo.name` is copied into a DTO field. It is the
continuation of Phase 3 review finding F4 ("persistence row types cross the application layer"),
which was explicitly "recorded for Phase 4" and is still undispositioned. Recorded as an observed
coupling, not classed as debt (§34: planned future restructuring is not by itself a defect).

**F5 — The reverified §33 API-boundary check has no automated regression guard. (Non-blocking;
verification weakness.)**
*Affected:* `web.CraftingProfitApiRealDbPerfIT` (and `web.CraftingDiscoveryApiRealDbPerfIT`).
*Evidence:* the class asserts nothing by design (`TEST_STRATEGY.md` §35.2: "these print evidence
and, for timing, assert nothing") and its `IT` suffix excludes it from `./mvnw test`.
*Impact:* a future latency regression at this boundary fails no build; the criterion's continued
satisfaction depends on someone running the check explicitly and reading the printed maximum. The
real-database *equivalence* ITs do assert, but are likewise excluded from the default goal. This is
the accepted methodology, recorded here so the limit is visible rather than assumed away.

**F6 — The accepted path of the three trigger routes has never been exercised end to end.
(Non-blocking; each story recorded it.)**
*Affected:* `/api/sync/account`, `/api/sync/global`, `/api/prices/refresh`.
*Evidence:* every trigger test substitutes the application service; the stories state the accepted
path was not run against the live GW2 API (doing so would mutate the real account tables, the global
tables plus the graph cache, or `tp_prices`). Live checks covered rejection, 404 and 405 only.
*Impact:* the HTTP/task protocol — admission, asynchrony, identifiers, status, failure mapping — is
well proven; "an HTTP trigger really performs the synchronization" rests on the delegation assertion
plus the unchanged in-process JavaFX evidence, not on an observed live run.

**F7 — Four unauthenticated write-triggering routes now exist while §20 authentication is TBD.
(Non-blocking for Phase 4; needs disposition before any non-local deployment.)**
*Affected:* the three trigger routes (four operations, counting both refresh variants).
*Evidence:* `KNOWN_PROBLEMS.md` §7.10 (open, widened by `STORY-API-004`/`STORY-API-005`);
`TARGET_ARCHITECTURE.md` §20 "TBD, only if required"; `server.port` bound from configuration.
*Impact:* no Phase 4 exit criterion requires authentication, and the stories correctly declined to
invent one. §7.10 is accurate and current — it is re-reported here as an open, relevant risk, not as
a new one. Its own recommendation (revisit before further write-triggering routes) now points at
whatever adds the next one.

**F8 — Four `§9` example endpoints remain unimplemented. (Non-blocking; fact only.)**
`GET /api/account/materials`, `GET /api/account/bank`, `GET /api/items/{id}`,
`GET /api/recipes/{id}` have no route. §9 calls its list examples rather than a contract, and
criterion (d) requires only the calculation and sync/refresh routes, so this is recorded for planner
awareness. No later-phase work is proposed or planned here.

**Criterion transfers: none proposed.** No Phase 4 exit criterion is skipped, obsolete or
inapplicable, so nothing needs a destination or transfer rationale.

### 6. Documentation corrections made (small, evidenced, at their owners)

`docs/CURRENT_ARCHITECTURE.md` (owner of current structure):
1. §2's default-package listing still named `InitialSetupService` and `AccountRefreshService`, which
   its own §2/§4 record as deleted and which do not exist on disk; replaced with the nine classes
   actually present (adding `CraftingResultPresentation`).
2. §3's package table repeated the same deleted pair as "2 orchestration services"; corrected the
   same way, with the pointer to where that orchestration now lives.
3. §2's `application/` listing omitted five of the eleven services — including the three the Phase 4
   triggers submit as task bodies; added with their stories.
4. §2's closing line claimed the test tree holds "one regression test
   (`craft.CraftingResolverCraftVsBuyTest`)"; replaced with a pointer to `TEST_STRATEGY.md`, with no
   test count written (counts are never stored here or there).

`docs/ROADMAP.md` (owner of phases):
5. §3's overview said Phase 4 was "not started" while all five of its stories are DONE; it now
   records implementation complete with review run and closure pending. No closure is declared — see
   F2 for the Phase 3 line left to the planner.

Checked and found already accurate, so left alone: `CURRENT_ARCHITECTURE.md` §5.7–§5.9 and §4's
dependency block (they match the code read for this review, including the `web.task` isolation
claim), `TEST_STRATEGY.md` §35.1–§35.3, `KNOWN_PROBLEMS.md` §7.10, and `TARGET_ARCHITECTURE.md`
§23/§30 (both decisions recorded as decided, with UD-006/UD-007 RESOLVED).

### 7. Verification limitations of this review

Static reading plus import inspection for layering and leakage claims; one targeted suite run
(262 tests, 0 failures) for the combined-tree question. Real-database timing and equivalence figures
are `STORY-API-005`'s recorded evidence for this identical tree, not re-measured here. No live GW2
API call, no database write and no browser/page measurement was performed. No production code and no
test was changed, and no remediation story was created (§34).

### 8. Phase 4 health assessment

Phase 4 is **healthy**: all eight supplied exit criteria are assessed satisfied with concrete
evidence, the framework and long-running-operation decisions were made before use and are respected,
the boundary is genuinely thin over unchanged application services, the transport contract is
separate from domain and GW2 shapes, contract-test coverage matches `TEST_STRATEGY.md` §11/§35, and
the JavaFX application is provably untouched by the additive API. No finding blocks completion. The
items a closure decision should dispose of first are **F1** (the reviewed implementation is
uncommitted) and, if the planner wants the §33 chain unbroken, **F3** (one re-run of the full-page
measurement). **F4, F5, F6, F7 and F8** are recorded, accurate and open. Milestone closure is not
declared here and remains the planner's/user's decision after disposing of these findings.

### 9. Planner disposition — 2026-09-24

The normal Phase 4 planning pass accepts this review's evidence for all eight supplied exit criteria and declares milestone-04 complete. This is a planning assessment of recorded evidence, not a new source inspection, test run or measurement. No criterion is transferred and no remediation story is required for closure.

- F1: retain the recorded uncommitted-tree limitation as non-blocking. The supplied exit criteria require the implemented and verified boundary, not a commit. No commit or clean-checkout verification is claimed; source control operations are outside this planning pass.
- F2: record Phase 4 completion in `docs/ROADMAP.md` section 8 and advance the continuity pointer in `agent/PROJECT_STATE.md`. The reported historical overview inconsistency remains a documentation limitation; other phase sections are outside this pass's read/edit scope and are not evidence against the supplied Phase 4 criteria.
- F3: accept the bounded evidence gap for Phase 4 closure. The review records post-dependency-change measurement of the shared cache/calculation path within the final API maximum of 1794 ms, plus unchanged JavaFX presentation and recorded compatibility checks. This satisfies the required API-boundary reverification without asserting a new full-page measurement or new Product Owner acceptance. The existing accepted full-page evidence remains historical evidence with the dependency-change limitation explicitly retained.
- F4: accept the observed internal type coupling as non-blocking transitional structure: the review establishes transport DTO separation, no response leakage and no forbidden dependency direction. No refactoring is commissioned without an affected criterion or evidenced defect.
- F5: retain the explicit manual performance-check limitation. Recorded real-database measurements satisfy reverification; neither the supplied phase criteria nor section 34 mandates a new automated performance gate. No automated regression protection is claimed.
- F6: accept the stated verification scope: contract tests prove admission, status, failure mapping and delegation to unchanged application services. Live mutating trigger runs are not required by the supplied API-test criterion; none is claimed.
- F7: retain the open risk at its existing owner, `docs/KNOWN_PROBLEMS.md` section 7.10. Phase 4 closure does not authorize non-local deployment or settle authentication. No authentication requirement or technology decision is invented here.
- F8: no action required for Phase 4. Section 9 labels the read routes examples, and the supplied minimum endpoint criterion is satisfied by the implemented calculation and sync/refresh routes.

The supplied backlog has no Active, To Do or Blocked entries, so no selectable work violates phase gating. No Product Owner requests were supplied. No additional stories or human decisions are needed for this closure.

## Blockers

None.


## Subsequent milestone disposition

The Result above records the review-time assessment and does not itself close Phase 4. The later planner disposition closed milestone-04; the current phase is Phase 5 (`docs/ROADMAP.md`, `agent/PROJECT_STATE.md`). This note records that later lifecycle state without changing the review-time findings.
