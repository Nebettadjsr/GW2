# BUG-2026-001: An account refresh task is reused for a caller whose stale sources it does not refresh

## Status

Closed

## Summary

`web/AccountDataFreshnessInterceptor` admitted the automatic account refresh with
`BackgroundTaskService.submitOrExisting("ACCOUNT_SYNC", …)`. That call returns the identifier of
whichever `ACCOUNT_SYNC` task is currently unfinished, regardless of which sources that task
actually refreshes, and it does not run the new caller's body. A caller whose request needed four
stale sources could therefore be handed — and told to wait for — a task that refreshes only one of
them. When that task succeeded, the caller's other three sources were still stale, so the next
request failed the freshness check again.

## Affected feature or component

`web/AccountDataFreshnessInterceptor` (admission), `web/task/BackgroundTaskService.submitOrExisting`
(admission semantics), every route the interceptor gates — in the observed sequence
`/api/crafting/selector-options` (CHARACTERS only) followed by `/api/crafting/profit` (BANK,
MATERIALS, ACCOUNT_RECIPES, CHARACTERS). `frontend/src/api/http.ts` waits for the advertised task
and retries exactly once, so the user could still be shown a stale-data error after that wait.

## Severity

High — a correctly working automatic recovery is the only thing standing between the user and a
manual synchronization, and in this sequence it cannot recover: the sources the caller needs are
never fetched, so the retry fails for the same reason as the original request. No data is lost or
corrupted, which is why this is not Critical.

## Dates

- Discovered: 2026-10-09
- Resolved: 2026-10-12

## Reproduction

1. Preconditions: a backend process with no unfinished `ACCOUNT_SYNC` task, and persisted account
   data whose `BANK`, `MATERIALS`, `ACCOUNT_RECIPES` and `CHARACTERS` completion markers are all
   older than `gw2.account-data.max-age-ms` (15 minutes by default).
2. Steps:
   1. `GET /api/account/../crafting/selector-options`. Its required source set is `CHARACTERS`
      only, so the interceptor admits an `ACCOUNT_SYNC` task that refreshes just that source, and
      answers 503 `ACCOUNT_DATA_STALE` naming task *T*.
   2. While *T* is unfinished, `POST /api/crafting/profit`. The interceptor finds four stale
      sources and calls `submitOrExisting("ACCOUNT_SYNC", …)`, which returns *T* without running
      the four-source body. The response is 503 `ACCOUNT_DATA_STALE` with
      `["BANK","MATERIALS","ACCOUNT_RECIPES","CHARACTERS"]` and a task reference pointing at *T*.
   3. Wait for *T* to reach `SUCCEEDED`, then repeat `POST /api/crafting/profit`.
3. Relevant input data/configuration: default `gw2.account-data.max-age-ms`; no other entry point
   triggering an account sync.

## Expected behavior

A caller is never pointed at refresh work that does not cover the sources it reported stale. Either
work covering the remaining sources is admitted, or the caller is told that the named task is not
refreshing them. Account writes still must not overlap, and at most one `ACCOUNT_SYNC` task may be
unfinished.

## Observed behavior

The second request was handed *T*, whose scope was `CHARACTERS` only. After *T* succeeded, the next
`POST /api/crafting/profit` was again 503 `ACCOUNT_DATA_STALE` with
`["BANK","ACCOUNT_RECIPES","MATERIALS"]` — the three sources *T* never fetched. The browser's single
retry is consumed by the wait for *T*, so a stale-data error could still reach the user.

## Root cause

`submitOrExisting` deduplicates on the operation key alone. The operation key `ACCOUNT_SYNC` carries
no scope, so two callers needing different source sets are indistinguishable to it, and the second
caller's body — the only thing that knew about its sources — is discarded. The interceptor then
reported that task as the refresh for its own sources.

## Correction

`application/AccountRefreshCoordinator` now owns account-refresh admission. It accumulates every
caller's required sources into one outstanding set and drains that set in a single `ACCOUNT_SYNC`
task, one batch at a time, so sources demanded while a narrower batch is in flight are refreshed by
the same run without a second concurrent account writer. When another entry point holds the
operation key, the coordinator keeps the demand and reports
`WAITING_FOR_UNRELATED_TASK` instead of adopting that task; the next request rechecks and admits
whatever is still required. Admission never establishes freshness: freshness is read from the
persisted completion markers alone, so a reused task of insufficient scope leaves its omitted
sources stale in the response. `web/AccountDataFreshnessInterceptor` admits through the coordinator
and keeps its existing `ACCOUNT_DATA_STALE` response policy.

## References

- Issue/story: `agent/stories/STORY-APP-014-cached-profit-refresh-coordination.md` (acceptance
  criteria 5 and 8). Originally reported as
  `agent/stories/STORY-PERF-003-refresh-phase-five-page-budget-evidence.md` Follow-up Finding F002
  and queued as `agent/stories/STORY-WEB-033-recover-overlapping-account-refresh-scopes.md`, which
  was superseded without implementation because its refresh-before-result requirement conflicts
  with resolved UD-015.
- Code change/commit: `src/main/java/application/AccountRefreshCoordinator.java` (new),
  `src/main/java/web/AccountDataFreshnessInterceptor.java`,
  `src/main/java/web/AccountDataFreshnessWebConfig.java`, `src/main/java/web/Gw2ApiApplication.java`.
- Fixture: none. The reproduction is a task/scope interleaving, not input data, and
  `src/test/java/application/FakeBackgroundTasks.java` drives it deterministically.
- Regression test path and test name:
  `src/test/java/application/AccountRefreshCoordinatorTest.java` —
  `anUnrelatedNarrowAccountTaskIsNotReportedAsRefreshingTheCallersSources`,
  `onceTheNarrowTaskEndsTheRemainingSourcesAreRecheckedAndAdmitted` and
  `sourcesDemandedWhileANarrowerBatchRunsAreRefreshedByTheSameSingleWriter` cover the admission
  rule. `src/test/java/web/AccountDataFreshnessInterceptorTest.java` —
  `theNarrowSelectorOptionsSyncEndsUpRefreshingTheFourSourcesDiscoveryDemanded` replays the exact
  observed route sequence through the gate that had the defect, and asserts the four demanded
  sources are really fetched by the one writer rather than three of them being skipped.
- Verification command/result:
  `./mvnw -Dtest=AccountRefreshCoordinatorTest test` — `Tests run: 7, Failures: 0, Errors: 0,
  Skipped: 0`, `BUILD SUCCESS` (2026-10-12).
  `./mvnw -Dtest=AccountDataFreshnessInterceptorTest test` — `Tests run: 6, Failures: 0,
  Errors: 0, Skipped: 0`, `BUILD SUCCESS` (2026-10-12). Both ran again inside the wider
  affected-suite run recorded in the story Result (`Tests run: 270, Failures: 0, Errors: 0`,
  2026-10-12), with the same per-suite counts.
- Exception approval (if a regression test is not feasible): not applicable.
