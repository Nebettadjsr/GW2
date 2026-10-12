## Story ID

STORY-APP-014

## Title

Return cached Crafting Profit results while coordinating asynchronous input refresh

## Status

DONE

## Milestone

milestone-05

## Goal

Implement the established application/HTTP cached-result contract for Crafting Profit so external account and TP refresh do not block a calculation with usable cached inputs.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md, supplied Phase 5 objective and performance/feature-refresh exit criteria.
- docs/TARGET_ARCHITECTURE.md sections 9.1, 10.2 and 17; resolved AR-007.
- agent/user-decisions/UD-013-phase-five-performance-user-acceptance.md and UD-015-trading-post-refresh-performance-budget-scope.md (UD-014 delegates to UD-015).
- docs/DOMAIN_SPEC.md sections 2.2.1, 25, 39 and 40.
- docs/TEST_STRATEGY.md sections 8, 11, 34, 35 and 36.
- docs/QUALITY_METRICS.md, Test coverage KPI and Establishing the clean baseline.
- docs/bugs/README.md and docs/bugs/BUG-REPORT-TEMPLATE.md.
- agent/stories/STORY-PERF-003-refresh-phase-five-page-budget-evidence.md, F001/F002; agent/stories/STORY-WEB-033-recover-overlapping-account-refresh-scopes.md, supersession Result.

## Context

AR-007 establishes the target contract; TARGET_ARCHITECTURE section 9.1 explicitly distinguishes it from the current blocking paths. STORY-WEB-033 was superseded without implementation because its refresh-before-result requirement conflicts with UD-015. Its insufficient-scope task defect remains required work. Historical timing is comparison evidence, not acceptance of this contract.

## Acceptance Criteria

1. A Profit request calculates synchronously from usable last-successfully-published inputs even when account or TP TTLs have expired. Return the ordinary authoritative economic results with the freshness envelope required by section 9.1; do not reject usable results solely for expiry or wait for upstream refresh before responding.
2. Capture account bank, materials, recipe knowledge and characters and feature-relevant TP quotes with their actual successful versions. A synchronized empty account source is usable; a missing source is not synthesized. Preserve explicit unavailable-price semantics without zero-cost replacements. If no usable calculation exists, return HTTP 202 with pending/freshness/task references and no fabricated result.
3. Freshness metadata describes the inputs used, with request/calculation context, evaluation time, per-source fresh/stale/missing state, TP coverage, provisional/final state, outstanding work and safe failure/retry information. Task success alone cannot establish a final result. Fresh requests admit no unnecessary refresh.
4. Application/adapters preserve a last-good read view during incremental writes and publish data/version together. Concurrent reads cannot mix partly refreshed sources with old successful markers. Failed refresh leaves last-good data and markers intact. Summary and resolution detail remain associated with compatible versions/settings; identify newer detail and obtain a matching updated row rather than explaining an old row with new inputs.
5. Keep existing source scopes, account TTL, ten-minute TP policy and in-process tasks. At most one ACCOUNT_SYNC is unfinished. Reproduce the CHARACTERS-only task followed by Profit needing four sources: after that task ends, recheck and admit remaining source work without concurrent account writes. Task reuse must not claim unrefreshed sources fresh.
6. Coordinate TP refresh separately, coalescing concurrent required-ID demands and checking uncovered IDs after active work. Recalculate/recheck after successful work, including changed account inputs producing new required TP IDs. Profit and Discovery retain the shared TP repository/fetch path. Failed or unknown tasks expose actionable bounded recovery information.
7. Define and test the bounded transport envelope for browser consumption. Limit interception changes to enabling this Profit contract; preserve other feature response policies. Preserve domain mathematics, inventory ownership, fees, quantities and request isolation.
8. Link the verified overlapping-scope defect to a registry record and deterministic regression under the registry lifecycle, reusing a matching record if present.

## Required Tests

- Application tests with controlled clocks/tasks/adapters for fresh, account-expired, TP-expired, both-expired, successful-empty and missing inputs; hold upstream completion and assert cached results return before it is released.
- Concurrency/publication tests proving last-good input/version consistency, insufficient/sufficient account-task reuse, account write exclusion, TP demand coalescing, changed relevant-ID coverage and failure preservation. Use deterministic coordination rather than live API calls or sleeps.
- Serialized HTTP contract tests for successful provisional/final responses, 202 no-result, validation, freshness/version correspondence and independent request contexts; run affected existing Profit/resolution/Discovery/task tests.
- Capture a read-only real-data calculation reference before changing shared input capture, then compare unchanged-snapshot economic/domain row fields and resolution values after the change under sections 34 and 35.2. Name/run the affected real-database equivalence check explicitly; it is not covered by CI. Keep reference data outside version control and disclose no account contents or credentials.

## Constraints

- Consume AR-007; do not decide a new architecture or copy its contract into another authoritative document.
- No stored calculation jobs, distributed task platform, tenancy work, broad layering cleanup, TTL changes or measurement exclusions.
- Keep domain logic independent of freshness clocks, HTTP and task models. Do not modify agent/runtime/, local_bridge/ or CI configuration.

## Dependencies

None.

## Definition of Done

The contract and defect regression are implemented and affected deterministic tests pass. Record real-data equivalence evidence or an explicit access blocker, not synthetic proof. Review affected coverage reports against the 90% warning KPI and report unresolved gaps without claiming local measurements are a clean CI baseline. Update current implementation documentation only for delivered behavior. Full CI is verified by the pipeline after acceptance.

## Result

Implemented. Crafting Profit now answers from its last successfully published inputs, reports what
those inputs were, and admits the refresh they still need without waiting for it. The confirmed
overlapping-scope defect is corrected and closed under the registry lifecycle.

This attempt corrected a rejected one. The earlier implementation and tests were kept; what the
rejection found missing is delivered under "Corrections in the current pass" below, headed by the
real defect it named: calculation identity was built from the coverage *aggregates*, so a changed
Trading Post quote could keep the previous identity and newer resolution detail was then reported as
a fresh explanation of an older row. Identity now includes the relevant quotes themselves.

### What was built

**Application layer (new).**

- `application/ProfitInputSession` — one `REPEATABLE READ`, never-written, rolled-back transaction
  through which a single calculation reads *every* input: completion markers, price coverage,
  recipes, roster, owned inventory, quotes and item metadata. `detached()` keeps the previous
  per-repository-connection behaviour and is the seam the application tests use.
- `application/CalculationInputVersions` — the captured generation: the four required sources'
  successful completion instants plus the relevant tradeable item set's coverage, with `usable()`,
  `sourcesNeedingRefresh()`, `confirmedCurrent()` and a `calculationId()` digest that changes only
  when a version changes. Its two readers take the calculation's own connection.
- `application/CalculationFreshness` — the transport-free envelope: request/calculation context,
  evaluation instant, per-source `FRESH`/`STALE`/`MISSING`, aggregate price coverage,
  provisional/final, outstanding work per mechanism and bounded failure/retry items.
- `application/CachedCraftingProfitService` — the use case, in the order the contract requires:
  capture → calculate (only if usable) → *then* admit refresh and describe. Admitting after
  calculating is what makes the envelope describe the inputs the result came from.
- `application/AccountRefreshCoordinator` — accumulated demand drained by one `ACCOUNT_SYNC` task,
  one batch at a time; a foreign holder of the operation key is waited out, never adopted;
  admission never establishes freshness.
- `application/TradingPostRefreshCoordinator` — separate coordination under the existing
  `PRICE_REFRESH_PROFIT` key: concurrent demands coalesce, a demand raised during a run earns one
  further pass, and that pass recomputes the required set so newly relevant ids are covered.
- `application/BackgroundTasks` — port over the existing in-process task facility, so coordination
  does not depend on the HTTP boundary and every race is drivable in a test.

**Changed.**

- `CraftingProfitService` gained a session-carrying constructor; each input read goes through the
  captured connection when there is one and is unchanged otherwise. `CharacterRepository` and
  `ItemRepository` gained connection-taking overloads beside their existing methods;
  `AccountSyncStateRepository.lookup` is `find` without the `CREATE TABLE`, so a snapshot read does
  not become a writing transaction.
- `sync/CharacterSync` now commits every character payload **and** the `CHARACTERS` marker in one
  transaction (it used to commit per character and mark afterwards), so a reader cannot see a
  half-replaced roster under the previous run's marker and a failure leaves the whole last-good
  generation intact.
- `sync/tp/relevance/CraftingProfitItemCollector` now owns one relevance definition
  (`RELEVANT_TRADEABLE_ITEMS`) shared by the refresh selection and the new `coverage()` report, so a
  reported gap and the work admitted to close it cannot describe different item sets. `coverage()`
  also reports a `quoteVersion` over the relevant quotes themselves (see the corrections below).
- `web/AccountDataFreshnessInterceptor` no longer gates the two Profit routes and admits through the
  coordinator; every other gated route keeps its source set and its `ACCOUNT_DATA_STALE` response.
- `web/CraftingProfitApiController` returns `200` with the result and its envelope (provisional or
  final) or `202` with the envelope and no rows; validation, 404, 503 and 500 are unchanged. The
  resolution route accepts an optional `basedOnCalculationId` and answers `FRESH_CALCULATION` or
  `RECALCULATED_FOR_CHANGED_INPUTS`, always carrying the row recalculated from the inputs it used.
- `web/CalculationFreshnessMapper` + `web/dto/CalculationFreshnessDto` are the bounded transport
  form (no per-item list, no task history); `web/BackgroundTaskServiceTasks` adapts the task
  facility and reports the same failure codes the task-status route publishes.

### Acceptance criteria

| AC | Evidence |
|---|---|
| 1 | `CachedCraftingProfitServiceTest.expiredAccountInputsStillReturnTheOrdinaryResultBeforeAnyRefreshCompletes` / `expiredPricesStillReturnTheOrdinaryResultAndAdmitOnlyTradingPostWork` / `bothExpiredAdmitTwoSeparatelyIdentifiedPiecesOfWork` hold the refresh gateway on a latch and assert the ordinary result and matching provisional envelope *before* release. `CraftingProfitApiControllerTest.expiredInputsStillAnswerTwoHundredWithTheResultAndAProvisionalEnvelope` is the serialized HTTP form. |
| 2 | `aSuccessfullySynchronizedEmptySourceIsUsable`, `aSourceThatNeverSynchronizedProducesAPendingStateAndNoFabricatedResult`, `theEnvelopeReportsTheVersionsCapturedBeforeTheCalculationNotNewerOnes`; `CraftingProfitApiControllerTest.noUsableCachedInputsAnswerTwoHundredTwoWithFreshnessAndTaskReferencesAndNoRows`; unavailable/zero/non-tradeable price semantics in `CraftingProfitResolutionApiControllerTest.knownZeroCostsPriceUnavailableAndUnvaluedNonTradeableAllSurviveDistinctly`. `missingItems` is never counted as covered. |
| 3 | `freshInputsProduceAFinalResultAndAdmitNoRefreshAtAll`, `aSucceededRefreshTaskDoesNotMakeAResultFinalWhileTheMarkersAreStillStale`, `theCalculationIdNamesTheInputGenerationAndChangesOnlyWithIt`, `aForgottenTaskIsReportedAsBoundedRecoveryInformation`; HTTP `confirmedCurrentInputsAnswerAFinalEnvelopeWithNoOutstandingWork`. |
| 4 | `CharacterRosterPublicationTest` drives the production publish (`CharacterSync.publishRoster`) and the production capture (`ProfitInputSession` + the marker reader + `CharacterRepository`) against real PostgreSQL: a capture started while a refresh is mid-write reads the whole previous roster under the previous marker, a part-way failure leaves that roster and marker intact and none of its own rows behind, and one capture cannot mix generations. `ProfitInputPublicationTest` proves the database-level snapshot semantics those rely on. `CraftingProfitServiceSessionTest` proves every input read of a table calculation and of a detail uses the one captured connection, and that a detached session keeps the old behaviour. Detail/row compatibility: `detailExplainingTheCallersGenerationIsReportedAsAFreshCalculation`, `detailComputedFromChangedInputsIsIdentifiedAndCarriesItsOwnMatchingRow` and, for a change no aggregate shows, `detailAfterAnInteriorQuoteChangeReportsChangedInputsAndCarriesItsOwnRow` plus its real-database form. |
| 5 | `AccountRefreshCoordinatorTest` — narrow foreign task not claimed, remaining sources rechecked and admitted after it ends, sufficient scope reused without extra work, demands arriving mid-batch refreshed by the same single writer, concurrent demands never admitting a second writer (`maxConcurrent` asserted), failure leaving the writer free. `AccountDataFreshnessInterceptorTest.theNarrowSelectorOptionsSyncEndsUpRefreshingTheFourSourcesDiscoveryDemanded` replays the observed route sequence end to end. |
| 6 | `TradingPostRefreshCoordinatorTest` — coalescing into one run with exactly one recheck pass, foreign task waited out, failure reporting bounded recovery and being demandable again, the Discovery variant untouched. `TradingPostCoverageCoordinationTest` (real PostgreSQL) adds what that cannot show: the ids a run actually refreshed and the coverage it left — exactly the uncovered required ids for one demand, `[{11,20,21}, {40,41}]` across four coalesced demands with an account-derived relevance change mid-run, the widened set fully covered, and a provisional request becoming final on resubmission once that work landed. `CraftingProfitItemCollectorTest.newlyRelevantItemsEnterTheRequiredSetAsSoonAsAccountKnowledgeChanges` and `coverageDescribesTheSameRequiredSetAndCountsAbsentQuotesAsUncovered` cover the shared definition itself. |
| 7 | 28 Profit and 33 resolution HTTP tests including `twoInterleavedRequestContextsKeepTheirOwnEnvelopesAndTheirOwnResults`, `concurrentRequestsWithDistinctInputsDoNotMixResults`, the unchanged validation/404/503/500 mapping, and `AccountDataFreshnessInterceptorTest.everyOtherGatedRouteKeepsItsOwnRequiredSourcesAndTheStaleResponse` for bank, materials, Luck, Discovery, Discovery resolution and selector options. Domain mathematics unchanged — proved on real data, below. |
| 8 | `docs/bugs/BUG-2026-001-account-refresh-task-reused-for-insufficient-scope.md` reuses no existing record (none matched), links STORY-PERF-003 F002 and the superseded STORY-WEB-033, names the correction, both regression suites and their commands, and is now **Closed** because those regressions and the affected checks pass. |

### Corrections in the current pass

**1. Changed Trading Post quotes are a changed calculation identity (the rejection's main point).**
`CraftingProfitItemCollector.coverage()` now also reports a `quoteVersion`: one
`md5(string_agg(...))` over every relevant item's own quote — its id, its fetch instant rendered in
UTC, and its four quoted numbers, each absent value written as a distinct marker so a missing quote
cannot collide with a zero one — computed from the same shared relevance definition the refresh
selects by. `CalculationFreshness.PriceCoverage` carries it and
`CalculationInputVersions.calculationId()` digests it. It is deliberately not published over HTTP:
it names a generation, the envelope already describes one, and the transport body stays bounded.
This closes the two cases the aggregates cannot see — a quote refetched between the reported oldest
and newest instants, and a quote rewritten with new numbers under its existing instant. Regressions,
each asserting that every aggregate is unchanged before asserting that identity is not:
`CraftingProfitItemCollectorTest.rewritingOneQuotesNumbersChangesTheQuoteVersionAndNoAggregate`,
`.refetchingAnInteriorQuoteChangesTheQuoteVersionAndNoAggregate`,
`.twoReadsOfUnchangedQuotesReportTheSameQuoteVersion`;
`CachedCraftingProfitServiceTest.anInteriorQuoteChangeIsANewGenerationEvenThoughEveryAggregateIsUnchanged`
and `.detailAfterAnInteriorQuoteChangeReportsChangedInputsAndCarriesItsOwnRow`;
`TradingPostCoverageCoordinationTest.anInteriorQuoteChangeMakesResolutionReportChangedInputsAndCarryItsOwnRow`
end to end over the production readers.

**2. Publication and failure preservation through the production paths.** `CharacterSync`'s publish
transaction is now the named `publishRoster(con, payloads, completedAt)` — the loop, the marker, the
commit and the rollback unchanged, only the upstream fetch left behind — so the protocol is drivable
without a GW2 API call. `src/test/java/sync/CharacterRosterPublicationTest.java` (3 tests, real
PostgreSQL in a disposable schema) holds a refresh open *mid-transaction* with a trigger that waits
on an advisory lock the test owns, waiting until `pg_stat_activity` reports that writer's backend as
waiting on it rather than sleeping, and reads through the production capture path. It asserts data
and marker together during the interrupted write, after a rollback caused by a payload the schema
rejects, and across a whole generation publishing between two reads of one capture.

**3. Trading Post coordination verified by refreshed ids and remaining coverage.**
`src/test/java/application/TradingPostCoverageCoordinationTest.java` (4 tests, real PostgreSQL)
drives the production coordinator over the production selection and the production upsert;
`src/test/java/sync/ProfitQuoteRefreshRecorder.java` replaces only the HTTP fetch, so an item with no
market data still gets the explicit all-null row and no absent price becomes a zero one. It asserts
the ids each pass fetched, that nothing required is left uncovered, that four demands raised during a
held run coalesce into it, that the recheck pass covers exactly the ids an account recipe change made
relevant mid-run, and that the request which reported the gap is provisional while the resubmitted
one is final with a different identity and no outstanding work.

**Controls**, run from the shipped files and then reverted (both suites green again afterwards):
removing `tp.quotes` from the identity material gave `Tests run: 21, Failures: 3`, two of them
`expected: <RECALCULATED_FOR_CHANGED_INPUTS> but was: <FRESH_CALCULATION>` — the reported defect
itself; restoring the per-character `con.commit()` inside `publishRoster` gave `Tests run: 3,
Failures: 2` in `CharacterRosterPublicationTest`, both `expected: <{Scribe Bo=300, Scribe Ana=400}>
but was: <{Scribe Ana=450, Scribe Bo=300}>`.

### Added in the previous pass

- `src/test/java/web/AccountDataFreshnessInterceptorTest.java` (6 tests, real PostgreSQL in a
  disposable schema via `Db.TEST_SCHEMA_PROPERTY`, recorder gateway, no GW2 API call). The QA plan
  requires asserting that interception changes did not alter a non-Profit endpoint's policy and
  nothing covered the interceptor at all. It also carries the interceptor-level form of
  BUG-2026-001.
- `src/test/java/web/BackgroundTaskServiceTasksTest.java` (6 tests) — the port adapter against the
  real `BackgroundTaskService`: refusal carrying the existing identifier, separate operation keys
  not blocking each other, terminal/failed mapping with the published failure codes and no
  exception text, unresolvable identifier answering empty. It was the weakest measured area
  (line 81.2 %, branch 37.5 %) and is now 100 %/100 %.
- Removed the now-orphaned `Supplier<CraftingProfitService>` bean from `Gw2ApiApplication` and
  retargeted `Gw2ApiApplicationBootTest` to the wiring that actually serves the Profit routes:
  `theProfitRoutesAreWiredToTheCachedUseCaseAndTheSharedRefreshCoordinators` asserts the cached use
  case is a bean and that exactly one coordinator bean of each kind exists — two account
  coordinators would reintroduce overlapping account writes. The request-isolation claim the old
  assertion made for Profit is kept where it is now observable
  (`everyCalculationGetsItsOwnServiceOverTheSessionThatCapturedItsVersions`); Discovery's factory
  assertion is unchanged.
- Closed BUG-2026-001 and extended its regression/verification references.

### Tests run (exact commands and real output)

JDK 25.0.1+8-27, `JAVA_HOME=C:\Users\Administrator\.jdks\openjdk-25.0.1`, `./mvnw -o` from the
repository root. Captured whole to `target/app014-fix.log`.

`./mvnw -o -Dtest=CachedCraftingProfitServiceTest,AccountRefreshCoordinatorTest,TradingPostRefreshCoordinatorTest,TradingPostCoverageCoordinationTest,ProfitInputPublicationTest,CharacterRosterPublicationTest,CharacterSyncTest,CraftingProfitServiceSessionTest,CraftingProfitItemCollectorTest,AccountDataFreshnessInterceptorTest,BackgroundTaskServiceTasksTest,CraftingProfitApiControllerTest,CraftingProfitResolutionApiControllerTest,CraftingProfitServiceTest,CraftingProfitResolutionDetailTest,BackgroundTaskServiceTest,AccountDataFreshnessPolicyTest,AccountRefreshServiceTest,TradingPostPriceRefreshServiceTest,CraftingDiscoveryServiceTest,CraftingDiscoveryApiControllerTest,CraftingDiscoveryResolutionApiControllerTest,CraftingSelectorOptionsApiControllerTest,SyncTaskApiControllerTest,Gw2ApiApplicationBootTest,TpSyncTest,TpPriceRepositoryTest,PriceRefreshApiControllerTest test`

```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.098 s -- in application.AccountDataFreshnessPolicyTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.052 s -- in application.AccountRefreshCoordinatorTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.039 s -- in application.AccountRefreshServiceTest
[INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.125 s -- in application.CachedCraftingProfitServiceTest
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.407 s -- in application.CraftingDiscoveryServiceTest
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.131 s -- in application.CraftingProfitResolutionDetailTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.019 s -- in application.CraftingProfitServiceSessionTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.016 s -- in application.CraftingProfitServiceTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.237 s -- in application.ProfitInputPublicationTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.157 s -- in application.TradingPostCoverageCoordinationTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.113 s -- in application.TradingPostPriceRefreshServiceTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.018 s -- in application.TradingPostRefreshCoordinatorTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.425 s -- in repo.tp.TpPriceRepositoryTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.837 s -- in sync.CharacterRosterPublicationTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.363 s -- in sync.CharacterSyncTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.529 s -- in sync.tp.relevance.CraftingProfitItemCollectorTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.248 s -- in sync.TpSyncTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.199 s -- in web.AccountDataFreshnessInterceptorTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.079 s -- in web.BackgroundTaskServiceTasksTest
[INFO] Tests run: 19, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.600 s -- in web.CraftingDiscoveryApiControllerTest
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.217 s -- in web.CraftingDiscoveryResolutionApiControllerTest
[INFO] Tests run: 28, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.378 s -- in web.CraftingProfitApiControllerTest
[INFO] Tests run: 33, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.393 s -- in web.CraftingProfitResolutionApiControllerTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.119 s -- in web.CraftingSelectorOptionsApiControllerTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.063 s -- in web.Gw2ApiApplicationBootTest
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.211 s -- in web.PriceRefreshApiControllerTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.057 s -- in web.SyncTaskApiControllerTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.261 s -- in web.task.BackgroundTaskServiceTest
[INFO] Tests run: 270, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Added to the earlier list are the three new suites plus `CharacterSyncTest`, `TpSyncTest`,
`TpPriceRepositoryTest` and `PriceRefreshApiControllerTest`, which cover the changed roster publish
and the changed quote SQL. The printed stack traces in the log are the deliberately failed refreshes
and the deliberately rejected payload being logged by the code under test; the counts are zero
failures and zero errors.

No QA-owned test exists for this story (`prepared_test_paths: []`); nothing was edited, skipped or
weakened, and the plan's expectations were added to rather than changed. No full
backend/frontend/harness run was made locally — GitHub Actions is the authoritative gate
(`TEST_STRATEGY.md` §20, §36). The three new suites are `*Test` classes that create and drop their
own schema, so the gate runs them against its empty database like the existing ones. `git status`
before this write-up listed no product file this story did not author.

### Real-database equivalence (not covered by CI)

Re-run in this pass because it changed the shared coverage query and the roster publish. Captured
whole to `target/app014-realdb-fix.log`.

`./mvnw -o -Dtest=CraftingProfitApiRealDbEquivalenceIT,CraftingProfitInputCaptureEquivalenceIT -DfailIfNoSpecifiedTests=false test`

```
[default All] reference recorded outside version control at C:\Users\ADMINI~1\AppData\Local\Temp\gw2-profit-capture-reference-14458407781319494174-default-All.txt
[All, buying enabled at 10g, listing prices] reference recorded outside version control at C:\Users\ADMINI~1\AppData\Local\Temp\gw2-profit-capture-reference-3835901322484357094-All-buying-enabled-at-10g-listing-prices.txt
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 18.75 s -- in application.CraftingProfitInputCaptureEquivalenceIT
default All (empty request body): 3205 rows equivalent between HTTP and in-process calls
All, buying enabled at 10g, listing prices: 3205 rows equivalent between HTTP and in-process calls
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 81.64 s -- in web.CraftingProfitApiRealDbEquivalenceIT
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- `CraftingProfitInputCaptureEquivalenceIT` is this story's own before/after reference for the
  changed shared input capture (§34, §35.2). Because the previous capture path still exists as a
  detached session, the reference and the comparison run against the **same** database snapshot
  moments apart rather than across two builds: 3 205 rows, two settings combinations (default All;
  All with buying at 10 g and listing prices). Every row's `craftableCount`, `missingToBuy`,
  `missingToBuyOne`, `buyCostCopper`, `matsSellValueCopper`, `totalMatsSellValueCopper`,
  `revenueCopper`, `profitCopper`, `totalProfitCopper`, `totalSellValueCopper`, `blockedReason` and
  both material-purchase-cost maps are compared, plus the full resolution tree text of the first five
  rows. **Identical.** The reference is written to the JVM temp directory, is not in version control,
  and the run prints no account contents and reads no credential.
- `CraftingProfitApiRealDbEquivalenceIT` is the named affected existing check. It drives the backend
  to a confirmed-current envelope before comparing — otherwise the request's own admitted refresh
  could write between the two reads and a moved snapshot would look like a mapping difference — then
  asserts `status CALCULATED` and `provisional false` and compares 3 205 rows field for field against
  the in-process calculation: **equivalent in both combinations.** So the added quote-version digest
  changed no economic result on real data, and a confirmed-current envelope is still reachable with
  it in the identity.
- This run took 81.64 s: the ten-minute TP TTL had expired beforehand, so the first request admitted
  a real refresh and the later ones reported nothing to fetch — AC3's "fresh requests admit no
  unnecessary refresh" and AC6's coalescing, observed on real data rather than asserted.

### Coverage

`./mvnw -o jacoco:report`, read from `target/site/jacoco/jacoco.csv`. **These are scoped local
figures from one accumulated `jacoco.exec` covering the 28 suites named above plus the two
real-database checks run in the same session. They are not a clean CI baseline and the project-wide
KPI is unmeasured here** (`QUALITY_METRICS.md`).

| Class | Line | Branch |
|---|---|---|
| `application.CachedCraftingProfitService` | 100.0 % (69/69) | 92.3 % (24/26) |
| `application.CalculationInputVersions` | 96.1 % (49/51) | 100.0 % (22/22) |
| `application.AccountRefreshCoordinator` | 92.6 % (50/54) | 100.0 % (8/8) |
| `application.TradingPostRefreshCoordinator` | 92.3 % (36/39) | 100.0 % (4/4) |
| `application.ProfitInputSession` | 80.8 % (21/26) | 100.0 % (4/4) |
| `application.CraftingProfitService` | 97.3 % (143/147) | 82.9 % (58/70) |
| `web.CraftingProfitApiController` | 100.0 % (35/35) | 100.0 % (4/4) |
| `web.CalculationFreshnessMapper` | 100.0 % (47/47) | 91.7 % (11/12) |
| `web.CraftingProfitApiMapper` | 97.7 % (42/43) | 95.7 % (44/46) |
| `web.AccountDataFreshnessInterceptor` | 100.0 % (26/26) | 100.0 % (20/20) |
| `web.BackgroundTaskServiceTasks` | 100.0 % (16/16) | 100.0 % (8/8) |
| `sync.tp.relevance.CraftingProfitItemCollector` | 95.7 % (22/23) | 50.0 % (5/10) |
| `sync.CharacterSync` | 100.0 % (111/111) | 70.6 % (24/34) |

Unresolved gaps, named rather than padded:

- `ProfitInputSession` 80.8 % line — the five uncovered lines are exactly its failure-cleanup paths
  (the `catch` that closes a connection it could not configure, and the two
  `catch (SQLException ignored)` arms of rollback and close). Reaching them needs a driver that fails
  on `setTransactionIsolation` or `close`; no test injects one.
- `CraftingProfitItemCollector` 50 % branch — of the five missed, one is the `if (!rs.next())` guard
  on a `COUNT(*)` aggregate, which PostgreSQL cannot take, and four are the compiler-generated
  suppressed-exception paths of a try-with-resources close. The quote-version digest added this pass
  introduced no branch.
- `CharacterSync` 70.6 % branch — the extracted `publishRoster` is covered by the new suite; the
  misses are in the surrounding fetch/parse code this story did not change, which has no automated
  coverage because it calls the live GW2 API. Its line figure is additionally inflated by the
  real-database checks in the same accumulated run, whose backend admitted a genuine account refresh.
- `CraftingProfitService` 82.9 % branch and `CalculationFreshnessMapper` 91.7 % are pre-existing and
  near-miss respectively; `CraftingProfitService`'s misses are in the long-standing scope/ownership
  branches this story did not change, now additionally exercised through the session path.

### Documentation

`CURRENT_ARCHITECTURE.md` (§4 cached-result route behaviour, account/TP coordination — extended in
this pass with one sentence stating what input generation identity is derived from, now that it
covers the relevant quotes themselves — and §7 single snapshot and per-source transactional
publication), `CURRENT_STATE_SPEC.md` (the behaviour a user
sees, and the narrower account-task reuse rule) and `KNOWN_PROBLEMS.md` (KP-12 scope reduced, not
closed) describe only delivered behaviour. No authoritative contract was copied out of
`TARGET_ARCHITECTURE.md` §9.1 or ADR-007.

### Remaining uncertainty

- The browser still consumes the unchanged `rows`/`rowCount` of a `200`. Rendering the provisional
  state, polling and correlated resubmission are STORY-WEB-034's scope, and real-data page/refresh
  acceptance timing is STORY-PERF-004's; neither is claimed here.
- The two real-database checks exercised one account's data on one machine. They establish
  equivalence of the capture change and of the HTTP mapping, not performance acceptance.
- TestFX, browser smoke and live GW2 API layers were not run: no screen, script or upstream client
  changed in this story.

## Follow-up Findings

F001: `AccountRefreshCoordinator.drain()` clears `draining`/`activeTaskId` in its `finally`, which
runs before the task facility records the task as terminal. A `require()` landing in that window
gets `OperationAlreadyRunning` for the coordinator's *own* finishing task and reports
`WAITING_FOR_UNRELATED_TASK`. Behaviour stays correct — the demand is kept and the next request
admits it — but the `reuse` label the envelope shows for that one response is wrong about whose task
it is.

## Blockers

None.
