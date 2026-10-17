## Story ID

STORY-WEB-034

## Title

Show provisional Crafting Profit results and finish bounded asynchronous refresh

## Status

DONE

## Milestone

milestone-05

## Goal

Render usable cached Profit results immediately and replace them automatically after verified input refresh while preserving the user's current view and safely terminating failed recovery.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md, supplied Phase 5 browser performance and normal-feature-refresh criteria.
- docs/TARGET_ARCHITECTURE.md sections 9.1, 10.2 and 17; resolved AR-007.
- agent/user-decisions/UD-015-trading-post-refresh-performance-budget-scope.md, required UI and recovery behavior.
- docs/DOMAIN_SPEC.md sections 2.2.1, 25, 39 and 40.
- docs/TEST_STRATEGY.md sections 12, 34 and 36.
- docs/QUALITY_METRICS.md, Test coverage KPI and Establishing the clean baseline.
- agent/stories/STORY-APP-014-cached-profit-refresh-coordination.md, delivered HTTP contract.
- agent/stories/STORY-WEB-033-recover-overlapping-account-refresh-scopes.md, supersession Result.

## Context

UD-015 replaces waiting for freshness before showing a result. The backend supplies authoritative values and captured-input metadata. The browser owns presentation, polling and correlated resubmission, not freshness inference or domain calculations.

## Acceptance Criteria

1. Render successful cached results even when provisional. Reuse the existing TP-warning visual design for “Stale data – refreshing…” with a green animated border and an explanation that cached results remain displayed until refresh finishes. Show loading/empty for 202 without usable data; never fabricate rows.
2. Poll advertised task references and resubmit with current settings after relevant work completes. Use the backend's required-source and TP coverage checks; support successive account/TP work without assuming task success means all inputs are fresh. Remove the indicator only after rendering the final calculation for the current context.
3. Preserve calculation settings, filters, valid row selections and sorting through result replacement; fall back gracefully when a selection no longer exists. Correlate calculation, task and detail responses so late responses cannot overwrite newer scope/settings/selection. Never display a newer-input detail as explanation of an older provisional row; obtain/associate the matching row context.
4. Maintain one five-minute overall automatic recovery deadline including polling, successive tasks and recalculation. Task changes do not reset it. Failed/unknown/expired tasks, transport failure and deadline exhaustion stop animation, retain last usable rows and expose failure/retry; no-cache failure remains explicit. An explicit retry starts a new bounded cycle and rechecks freshness without assuming backend cancellation or starting overlapping writes.
5. Render backend monetary values, states and resolution quantities unchanged. Keep account and TP freshness distinguishable. Normal refresh requires no System Status visit, and other feature policies remain intact.

## Required Tests

- Real HTTP-client request/response and controlled-time component checks covering provisional-to-final, 202-to-result, successive insufficient-scope account work, TP work after account changes, preserved controls and final indicator removal.
- Regressions for reordered task/calculation/detail responses, settings changes mid-refresh, failure with/without cached rows, unknown task, transport failure, five-minute cumulative deadline and explicit retry. Independent backend fixtures must make any accidental monetary recalculation detectable.
- Run affected existing HTTP/Profit/detail tests, type check and production build. Tests use controlled transport and clocks without live API/database/display dependencies.
- Explicitly run a focused real-browser Profit refresh smoke check using the current build and controlled responses for deterministic stale/failure cases; assert visible rows, indicator, preserved controls, safe detail association and termination. Separately verify the browser connects to the delivered backend envelope. These browser checks are outside CI and cannot substitute for real-data performance acceptance.

## Constraints

- Follow the established backend envelope and UD-015 design; no independent domain calculations, direct GW2 requests or new numerical refresh budget.
- No cosmetic smoke-reporting cleanup, general client-test expansion, JavaFX work, agent workflow edits or CI changes.

## Dependencies

- STORY-APP-014

## Definition of Done

Affected deterministic tests, build and explicit browser checks pass with evidence and limitations recorded. Review affected coverage reports and unresolved gaps against the 90% warning KPI. Update current behavior documentation where delivered behavior changed. Full CI is verified by the pipeline after acceptance; real-data timing remains STORY-PERF-004.

## Result

Implemented. The Crafting Profit page now displays STORY-APP-014's cached result as soon as it
arrives, follows the refresh work the response advertises, replaces the result when the backend
itself reports it final, and terminates safely inside one five-minute budget. The browser derives
no freshness and calculates nothing: every state, count and money value on screen is a field of the
response.

This attempt resumed an interrupted one. The working tree already held the response types, the
`ProfitRefreshStatus` component and most of `useCraftingProfit`'s recovery cycle; the screen was
mid-edit and did not compile (its old account-then-TP orchestration still referenced refs that had
been deleted). Nothing finished was redone.

That attempt was itself interrupted after the code, the tests and this Result section were written
but before `CLAUDE_RESULT.md` was produced — the artifact still described STORY-APP-014. A further
invocation resumed from there, wrote no product code because the implementation was already
complete, re-ran every deterministic and controlled-browser check against the tree as it stood
(outcomes under *Tests run*) and corrected the one stale figure that re-run exposed — and was then
interrupted too, again before the artifact was written, leaving its backend and preview processes
listening. A third resuming invocation changed no file under `frontend/` or `docs/` either: it
re-ran every check from this tree, this time including the live envelope check, and wrote the
missing artifact (**Re-verified in the resuming invocations** below).

A fourth invocation (2026-10-16, after the rejection) fixed the one thing the evaluator and the
independent QA review both named: the budget was checked **only between awaited requests**, so a
task-status read or a recalculation that never answered left recovery animating indefinitely with
no failure and no retry, and its late answer could still be applied. That is now fixed in the
product code and covered by three controlled-time regressions plus one for the production timer —
see *The deadline while a request is still pending* below. Nothing else about the lifecycle was
changed.

### The deadline while a request is still pending

The cycle's budget no longer depends on a request answering.

- `useCraftingProfit.ts` takes a cancellable one-shot timer for the whole budget when the cycle
  starts (`RecoveryAlarm`, the new `alarm` port; `timerAlarm` is the browser default and the
  screen's `RefreshClock` supplies the test's). When it fires, recovery ends exactly as every other
  bounded failure does — animation off, `RECOVERY_TIMED_OUT` with its retry, the last usable rows
  untouched — and the cycle is abandoned, which is what stops a pending request from writing into
  the page when it eventually answers. Nothing is cancelled in the backend and nothing assumes it
  was. The checks between awaits are kept as well: they end a cycle promptly once a request did
  answer, and the timer ends one that is still waiting. The timer is cancelled when the cycle ends
  first, so a finished refresh leaves no timer behind.
- A recovery resubmission's answer is now correlated by **two** facts, not one: the monotonic
  request id and the owning cycle. The request-id guard alone could not reject the late answer of
  an expired cycle, because nothing newer had been requested, so that answer still replaced the
  rows the page had already explained as not refreshed.
- `retryRefresh()` no longer asks for its calculation *before* a cycle exists. The retry's own
  request is the first step of the new bounded cycle, so while it is pending the page is visibly
  recovering (rather than showing a result with neither indicator nor retry) and the deadline is
  already running against it.

### What was built

**New.**

- `frontend/src/crafting/ProfitRefreshStatus.vue` — the three situations beside the results, never
  two at once: refreshing, failed, or nothing. The chip is the Trading Post warning's own compact
  bordered-label form with a green continuously cycling border instead of the red outline (UD-015
  item 4). It counts entries of supplied lists and formats them; an account state or a work kind it
  has no wording for stays visible as the backend's own string rather than reading as freshness.
- `frontend/src/crafting/__tests__/CraftingProfitRefreshWorkflow.spec.ts` — 31 deterministic
  lifecycle tests through the real screen, with a controlled transport and a controlled clock whose
  `sleep` parks the cycle until the test releases it, so each assertion observes one settled step.
  Its clock also owns the budget timer and can `advance` time *without* releasing anything the cycle
  waits for, which is the only way to observe the five minutes passing while a request is unanswered.
- `frontend/scripts/profit-refresh-browser-smoke.mjs` (`npm run smoke:profit:refresh`) — the
  lifecycle in a real browser against scripted responses.
- `frontend/scripts/profit-envelope-live-check.mjs` (`npm run check:profit:envelope`) — read-only,
  against the running backend: the gap the controlled checks cannot close.

**Changed.**

- `useCraftingProfit.ts` owns one bounded recovery cycle. The deadline is taken once per cycle and
  covers polling, successive tasks and recalculation, so admitting further work never extends it.
  It polls only the advertised `taskStatusUrl` through the existing `syncApi.readTaskStatus`, then
  resubmits with the settings currently in effect — a succeeded task is never treated as freshness.
  `now`/`sleep` are injected ports. A monotonic request id discards a superseded calculation answer
  and a cycle counter stops an abandoned cycle at its next checkpoint. `retryRefresh()` asks for the
  calculation again (which is what rechecks freshness) and starts no refresh, so no second account
  writer can originate here.
- `CraftingProfitScreen.vue` lost the fixed account-then-TP-then-reload orchestration entirely. The
  header control and the failed-request retry are now one `onReload`, which posts the calculation
  and nothing else. A `PENDING` answer renders its own notice with no row. The detail panel reads
  the detail response's own row when the backend answered
  `RECALCULATED_FOR_CHANGED_INPUTS`, with a notice saying so, and the detail watch key gained the
  input generation so a replacement row gets its own explanation — the display controls are still
  not part of that key (13.4).
- `useProfitResolution.ts` sends `basedOnCalculationId`, omitted rather than null when the table
  response named no generation. `SelectedResultDetail.vue` gained the one notice above.
  `api/types.ts` carries the envelope types and makes a detail response's `row` nullable, which is
  what `web.dto.CraftingProfitResolutionResponse` does for `PENDING`.
- `__tests__/fixtures.ts` gained `finalFreshness`/`provisionalFreshness`/`pendingFreshness` and the
  work/coverage builders; `profitResponse` defaults to a **final** envelope so every existing test
  keeps meaning what it meant. `profitResolutionAssociation.spec.ts`'s
  `sendsTheRecipeIdAndTheTablesOwnEffectiveInputsAndNothingElse` was retargeted, not weakened: it
  asserts the whole body field for field plus the exact key set, instead of the absence of one named
  extra. `scripts/profit-browser-smoke.mjs`'s stub now serves the delivered envelope.

### Two defects the new tests found in the resumed implementation

Both were in the carried-over cycle and are fixed:

1. **Unpaced resubmission loop.** Only a round whose tasks had all already been followed waited an
   interval. A backend that keeps admitting *fresh* work, or answers a task as finished immediately
   while its sources stay stale, therefore produced an unbounded request loop — the first run of
   `stopsAtOneCumulativeFiveMinuteDeadlineThatFurtherTasksDoNotReset` hung. Every round after the
   first now waits one interval, inside the same budget.
2. **Untasked outstanding work was never re-asked.** When work was outstanding with no task to poll
   (another entry point holds the mechanism), the round `continue`d past the resubmission, so the
   cycle waited out the whole five minutes without ever asking again and could not pick the refresh
   up when it landed. That branch now falls through to the resubmission;
   `keepsAskingWhileOutstandingWorkHasNoTaskToFollowYet` is the regression.

### Acceptance criteria

| AC | Evidence |
|---|---|
| 1 | `showsTheSuppliedCachedRowsWithTheStaleIndicatorBeforeAnyRefreshFinishes` asserts the supplied rows, totals, chip wording and explanation while the advertised task is still mid-poll; `replacesTheProvisionalResultAndOnlyThenRemovesTheIndicator`; `showsAPendingStateWithNoFabricatedRowForATwoHundredTwoAnswer` asserts zero rows, no empty-result wording and no table at all for 202, then the usable result. Browser steps 1–2. |
| 2 | `followsSuccessiveAccountAndTradingPostWorkAndKeepsTheIndicatorUntilTheFinalAnswer` asserts the three advertised locations polled in order, four calculations each carrying the current settings, and the indicator gone only after the final one; `keepsTheIndicatorUpWhileASucceededTaskLeavesSourcesStale`; `keepsAskingWhileOutstandingWorkHasNoTaskToFollowYet`. Browser steps 4 and 7. |
| 3 | `keepsSettingsSearchSortAndSelectionAcrossAnAutomaticReplacement`, `fallsBackGracefullyWhenTheReplacementNoLongerOffersTheSelectedRecipe`, `doesNotLetALateRecoveryAnswerOverwriteANewerSelection`, `showsADetailFromNewerInputsAgainstItsOwnRowRatherThanTheProvisionalOne`, `requestsAndShowsTheMatchingDetailOnceTheReplacementRowArrives`, `omitsTheGenerationFromADetailRequestWhenTheResponseNamedNone` (the correlation key is omitted, never sent as `null`, when the table response named no generation). Browser steps 5–6. |
| 4 | `stopsAtOneCumulativeFiveMinuteDeadlineThatFurtherTasksDoNotReset` (controlled clock: 300 000 ms elapsed across distinct tasks, recovery stopped, rows kept, nothing parked), `stopsAtTheDeadlineWhileATaskStatusRequestIsStillUnanswered` and `stopsAtTheDeadlineWhileARecalculationIsStillUnansweredAndIgnoresItsLateAnswer` (the clock advances the whole budget while the request is held pending: animation off, rows kept, retry offered, and the held answer released afterwards changes nothing and resubmits nothing), `boundsAnExplicitRetryWhoseRecalculationIsStillUnanswered`, `firesTheBrowsersOwnBudgetTimerAndCancelsItWithTheCycle` (the production timer, not the injected one), `stopsWhenTheAdvertisedTaskIsNoLongerKnownToTheBackend`, `stopsOnATransportFailureWhilePollingAndKeepsTheCachedRows`, `reportsAPollFailureThatIsNotAnErrorObjectRatherThanFailingSilently`, `reportsTheBackendsOwnFailureItemAndStaysExplicitWithNoCachedResult`, `reportsAFailedRefreshTaskWithTheBackendsOwnWording`, `stopsWhenAProvisionalAnswerAdmittedNoRefreshWorkAtAll`, `startsAFreshBoundedCycleOnAnExplicitRetryWithoutStartingAnyRefreshItself`, `keepsTheRetryBoundedToOneCycleRatherThanOverlappingTheAbandonedOne` (both cycles' waits released at once; exactly one polls again), `reportsAFailedRetryRequestInsteadOfSilentlyEndingTheCycle`. Browser steps 8–9. |
| 5 | `rendersTheSuppliedMonetaryValuesStatesAndQuantitiesUnchanged` (totals that are not the product of their parts: 1 234 not 7 × 100, 9 876 not 7 × 380, the per-craft own-materials figure unscaled), `reportsAccountFreshnessAndTradingPostCoverageAsTwoSeparateFacts`, `keepsAnUnknownAccountSourceStateVisibleRatherThanReadingItAsFreshness`, `keepsAnUnknownRefreshMechanismAndAnUnreportedSourceSetVisible`, `needsNoSystemStatusVisitAndNeverCallsASynchronizationTrigger`. Every test's `SyncApi` double rejects and records all four synchronization triggers; `writes` is empty in all 27. Browser steps 3–4 and both live-check runs. |

### Tests run (exact commands and real output)

`npm run type-check`, `npm run build` and `npx vitest run` captured whole to `/tmp/final-checks.log`:

```
### npm run type-check
> vue-tsc --noEmit
exit=0

### npm run build
> npm run type-check && vite build
✓ 112 modules transformed.
dist/index.html                   0.97 kB │ gzip:  0.55 kB
dist/assets/index-CtkV1M4M.css   38.60 kB │ gzip:  6.70 kB
dist/assets/index-Dv355Tm0.js   181.30 kB │ gzip: 58.19 kB
✓ built in 443ms
exit=0

### npx vitest run
 Test Files  28 passed (28)
      Tests  344 passed (344)
   Duration  14.40s
exit=0
```

The whole frontend suite rather than the narrow one on purpose: `__tests__/fixtures.ts` and
`src/api/types.ts` are shared by every crafting spec, which is the unbounded-blast-radius exception
in `CLAUDE.md`. The narrow run is
`npx vitest run src/crafting/__tests__/CraftingProfitRefreshWorkflow.spec.ts` →
`Test Files 1 passed (1) / Tests 27 passed (27)`. No backend, Java or TestFX suite was run: no Java
file changed, and `git status` lists no product file this story did not author.

**Re-verified in the resuming invocations** rather than carried over on trust. Every deterministic
check above was run again from the working tree as it now stands, with the same outcome:
`npm run type-check` → exit 0; `npm run build` → exit 0, `✓ 112 modules transformed`, emitting
`index-CtkV1M4M.css` / `index-Dv355Tm0.js` — the same bundle hashes the browser evidence below was
captured against, so that evidence describes this exact build; `npx vitest run` →
`Test Files 28 passed (28) / Tests 344 passed (344)`, exit 0; the narrow run →
`Tests 27 passed (27)`, exit 0. The lifecycle spec holds 27 tests, not the 26 recorded earlier in
this Result: `omitsTheGenerationFromADetailRequestWhenTheResponseNamedNone` was added after that
sentence was first written, and the counts above are the corrected ones. The suite total is
unchanged at 344 because that figure was already measured with the test present.
`npm run smoke:profit:refresh` (11 steps) and `npm run smoke:profit` (35 steps) were both re-run
against the freshly built bundle and both passed; `npx vitest run --coverage` reproduced the
affected-file table below figure for figure.

The **third** resuming invocation (2026-10-16) repeated all of that from the tree as it now stands:
`npm run type-check` exit 0; `npm run build` exit 0, `✓ 112 modules transformed`, emitting
`index-CtkV1M4M.css` / `index-Dv355Tm0.js` — the same two bundle hashes a third time, so every
browser run recorded in this Result was made against byte-identical output; `npx vitest run` →
`Test Files 28 passed (28) / Tests 344 passed (344)`; the narrow run → `Tests 27 passed (27)`;
`npm run smoke:profit:refresh` → 11 steps passed; `npm run smoke:profit` → PASSED (35 steps);
`npx vitest run --coverage` → the same affected-file figures again (aggregate 93.79 % lines /
86.84 % branches / 92.23 % functions). It also repeated the live `check:profit:envelope`, which the
previous invocation had skipped — three read-only visits, all 6 steps passing, covering both
outcomes of the contract on real data (details under *Browser checks*). That invocation found the
previous one's backend (`8080`) and `vite preview` (`5188`) still listening; it stopped those four
PIDs, confirmed both ports refused connections, then started its own runtime so the live evidence
came from a backend built from the current source rather than a three-day-old process.

The **fourth** invocation (2026-10-16, the rejection fix) changed product code again, so every
check was re-run from the resulting tree. Captured to `/tmp/web034-checks.log`,
`/tmp/web034-coverage2.log`:

```
### npx vitest run src/crafting/__tests__/CraftingProfitRefreshWorkflow.spec.ts
 Test Files  1 passed (1)
      Tests  31 passed (31)

### npx vitest run --coverage        (whole frontend suite)
 Test Files  28 passed (28)
      Tests  348 passed (348)
exit=0

### npm run type-check
> vue-tsc --noEmit
exit=0

### npm run build
dist/index.html                   0.97 kB │ gzip:  0.55 kB
dist/assets/index-ROso5umn.css   38.60 kB │ gzip:  6.70 kB
dist/assets/index-BaBTzFuD.js   181.64 kB │ gzip: 58.32 kB
✓ built in 489ms
```

344 → 348 tests: the three stalled-request regressions and the production-timer one. The bundle
hashes moved (`index-Dv355Tm0.js` → `index-BaBTzFuD.js`, `index-CtkV1M4M.css` → `index-ROso5umn.css`)
because `useCraftingProfit.ts` and `CraftingProfitScreen.vue` changed, so the earlier invocations'
browser evidence no longer describes the shipped bundle — every browser check was therefore re-run
against this one and re-recorded below. `npm run build` was run once more at the very end and
emitted the same two hashes, so the dist the browser checks loaded is this source.

**Controls for the rejection fix** — the behaviour was removed in the shipped file, the spec run,
then reverted and re-run green (`/tmp/control-no-alarm.log`, `/tmp/control-no-cycle-guard.log`):

| Behaviour removed | Result |
|---|---|
| the budget timer no longer ends the cycle (`if (!settled && false) expire(cycle)`) | `Tests 3 failed \| 27 passed (30)` — exactly the three stalled-request regressions, each on `stale-data-indicator`: `AssertionError: expected true to be false`, i.e. recovery still animating five minutes in |
| the owning-cycle guard dropped from `sendProfit` | `Tests 2 failed \| 28 passed (30)` — both late-answer regressions: `expected [ '+43s 21c' ] to deeply equal [ '+12s 34c' ]`, i.e. the expired request's answer replaced the rows |

Reverted and re-run: `Tests 31 passed (31)`, and `git diff` of `useCraftingProfit.ts` shows neither
control line.

**Controls from the earlier attempts** — same method, with both suites green again
afterwards (`/tmp/control1.log` … `/tmp/control4.log`):

| Behaviour removed | Result |
|---|---|
| `deadlineAt` recomputed each round (per-task budget) | `stopsAtOneCumulativeFiveMinuteDeadline…` failed: `expected 600000 to be less than 360000` |
| `PENDING` applied as an empty result | `showsAPendingState…` failed at `exists(wrapper, 'pending')`: `expected true, received false` |
| a succeeded task ends recovery | `keepsTheIndicatorUpWhileASucceededTaskLeavesSourcesStale` failed: the second advertised task was never polled |
| the detail panel always reads the table row | `showsADetailFromNewerInputsAgainstItsOwnRow…` failed: `expected "+77s 77c", received "+12s 34c"` |

### Browser checks (outside CI, with their limitations)

`npm run build && npm run smoke:profit:refresh`, controlled origin, captured to
`/tmp/smoke-refresh.log`:

```
Bundle  : dist/index.html built 2026-10-13T17:04:01.322Z; newest build input src/crafting/__tests__/CraftingProfitRefreshWorkflow.spec.ts 2026-10-13T17:01:22.650Z
Page    : http://127.0.0.1:5177 (stub backend in this process)

  ok   page served by this script — 7 requests answered so far
  ok   cached rows render immediately with the stale-data indicator — 2 rows, totals +14g 81s 40c / +2g 22s 22c, chip "↻ Stale data – refreshing…"
  ok   the indicator is the TP warning’s label form with a cycling border — border 2px rgb(107, 150, 134), animation refresh-cycle-bbfff91f infinite, text rgb(136, 212, 161) (warning rgb(255, 153, 144))
  ok   reduced motion stops the movement and keeps the label and its border — duration 0s, border 2px rgb(136, 212, 161)
  ok   account and Trading Post freshness stay distinguishable — Account data: 3 of 4 up to date · expired: CHARACTERS || Trading Post prices: 3000 of 3205 required items up to date · expired: 180 · no stored price: 25
  ok   only the advertised task locations are polled; no refresh is triggered — 1 location(s) polled, 0 synchronization requests
  ok   a newer-input detail explains its own row and the table keeps its result — detail +77g 77s 77c, table +14g 81s 40c, notice shown
  ok   the replacement keeps search, sort and selection and removes the indicator — profit +40g 40s 40c, search "Deldrimor", detail generation generation-fresh, indicator gone
  ok   bounded failure keeps the rows, stops the animation and offers a retry — reason "Backend answer: ACCOUNT_REFRESH_FAILED: The account refresh task ended without completing its sources.", 1 row kept
  ok   an explicit retry re-asks the calculation and starts no refresh itself — 3 → 4 calculations, 0 synchronization requests
  ok   no uncaught page errors — 0 reported

11 steps passed.
exit=0
```

`npm run smoke:profit` on the same bundle: `Crafting Profit hierarchy browser check PASSED (35
steps).` / `exit=0` (`/tmp/smoke-profit.log`) — the stub now serves the delivered envelope and no
step changed meaning.

**Separate backend-envelope connection check.** `npm run check:profit:envelope` against the real
backend (`./mvnw -o spring-boot:run`, PID noted and stopped afterwards) behind
`vite preview --port 5188 --host 127.0.0.1` serving the same bundle. Both outcomes of the contract
were observed on real data, one per run:

```
  ok   the browser reached the backend’s Crafting Profit route — HTTP 200
  ok   the received body carries the delivered freshness envelope — status CALCULATED, provisional true, 4 account sources, 2984 required items (covered true), 1 outstanding, 0 failure(s)
  ok   rows and count follow the body status — 3205 rows
  ok   the page state agrees with the received envelope — 200 row(s) listed, indicator true, failure false
  ok   no synchronization was triggered and only advertised locations were polled — 24 API requests, 0 triggers, 1 polled location(s)
  ok   no uncaught page errors — 0 reported
```

```
  ok   the browser reached the backend’s Crafting Profit route — HTTP 200
  ok   the received body carries the delivered freshness envelope — status CALCULATED, provisional false, 4 account sources, 2984 required items (covered true), 0 outstanding, 0 failure(s)
  ok   rows and count follow the body status — 3205 rows
  ok   the page state agrees with the received envelope — 200 row(s) listed, indicator false, failure false
  ok   no synchronization was triggered and only advertised locations were polled — 21 API requests, 0 triggers, 0 polled location(s)
  ok   no uncaught page errors — 0 reported
```

So on the real account the page received the delivered envelope, showed 3 205 cached rows with the
indicator up while one account refresh was outstanding, polled only that advertised location, and
on the later visit — the refresh having landed — showed the same rows with no indicator. 200 of the
3 205 are listed because of the page's own 250-row display maximum after its default filters.
(That cleanup held for the first attempt's processes. The next invocation's interruption left *its*
backend and preview listening on 8080 and 5188 for three days; the third invocation found them in
its opening port probe and stopped them — so "every process was stopped" is true of each invocation
that reached its end, and the probe at the start is what caught the one that did not.)

**Repeated on 2026-10-16** against a backend and a `vite preview --port 5188 --host 127.0.0.1`
started for this purpose (`./mvnw -o spring-boot:run`, PIDs 33096/9860 and 32176/6272, all four
stopped afterwards and all ports probed as refusing). The preview served `index-Dv355Tm0.js` /
`index-CtkV1M4M.css`, the bundle built minutes earlier. Three visits, the whole progression this
time rather than two isolated points — each run `PASSED (6 steps)`, exit 0:

```
visit 1  status CALCULATED, provisional true,  4 account sources, 2984 required items (covered false), 2 outstanding, 0 failure(s)
         3205 rows; 199 row(s) listed, indicator true,  failure false; 25 API requests, 0 triggers, 1 polled location(s)
visit 2  status CALCULATED, provisional true,  4 account sources, 2984 required items (covered true),  1 outstanding, 0 failure(s)
         3205 rows; 197 row(s) listed, indicator true,  failure false; 24 API requests, 0 triggers, 1 polled location(s)
visit 3  status CALCULATED, provisional false, 4 account sources, 2984 required items (covered true),  0 outstanding, 0 failure(s)
         3205 rows; 197 row(s) listed, indicator false, failure false; 21 API requests, 0 triggers, 0 polled location(s)
```

Between visits 2 and 3 the backend was polled directly until it reported `provisional false` with
all four sources `FRESH` and nothing outstanding — the real-data counterpart of the indicator
disappearing: the refresh the first visit's page waited on was the Trading Post coverage (`covered
false` → `true`), then the remaining account source, and only the visit after both landed rendered
without the indicator. Every visit triggered no synchronization and polled only advertised
locations, which is the same assertion the controlled checks make, here against the real envelope.

**Re-run on the rejection-fix bundle (2026-10-16, fourth invocation).** All three browser checks
were run again against `index-BaBTzFuD.js` / `index-ROso5umn.css`, the bundle this tree builds:

```
### npm run smoke:profit:refresh          (/tmp/smoke-refresh-web034.log)
Bundle  : dist/index.html built 2026-10-16T19:24:03.208Z; newest build input src/crafting/useCraftingProfit.ts 2026-10-16T19:23:24.164Z
Page    : http://127.0.0.1:5177 (stub backend in this process)
  ok   page served by this script — 7 requests answered so far
  ok   cached rows render immediately with the stale-data indicator — 2 rows, totals +14g 81s 40c / +2g 22s 22c, chip "↻ Stale data – refreshing…"
  ok   the indicator is the TP warning’s label form with a cycling border — border 2px rgb(89, 112, 117), animation refresh-cycle-bbfff91f infinite, text rgb(136, 212, 161) (warning rgb(255, 153, 144))
  ok   reduced motion stops the movement and keeps the label and its border — duration 0s, border 2px rgb(136, 212, 161)
  ok   account and Trading Post freshness stay distinguishable — Account data: 3 of 4 up to date · expired: CHARACTERS || Trading Post prices: 3000 of 3205 required items up to date · expired: 180 · no stored price: 25
  ok   only the advertised task locations are polled; no refresh is triggered — 1 location(s) polled, 0 synchronization requests
  ok   a newer-input detail explains its own row and the table keeps its result — detail +77g 77s 77c, table +14g 81s 40c, notice shown
  ok   the replacement keeps search, sort and selection and removes the indicator — profit +40g 40s 40c, search "Deldrimor", detail generation generation-fresh, indicator gone
  ok   bounded failure keeps the rows, stops the animation and offers a retry — reason "Backend answer: ACCOUNT_REFRESH_FAILED: The account refresh task ended without completing its sources.", 1 row kept
  ok   an explicit retry re-asks the calculation and starts no refresh itself — 3 → 4 calculations, 0 synchronization requests
  ok   no uncaught page errors — 0 reported
11 steps passed.
exit=0

### npm run smoke:profit                  (/tmp/smoke-profit-web034.log)
Crafting Profit hierarchy browser check PASSED (35 steps).
Every backend answer was produced by this script; no real data was read or written.
exit=0
```

The border colour printed in step 3 differs from the earlier capture (`rgb(89, 112, 117)` against
`rgb(107, 150, 134)`): that value is a sample of the cycling border taken at an arbitrary moment
and is reported, not asserted. What the step asserts is unchanged — a solid border at least 1 px
wide, an animation that is not `none` and runs `infinite`, a chip colour different from the Trading
Post warning's, and the warning's own font size.

**Backend-envelope connection check, re-run.** The first two runs of
`GW2_FRONTEND_URL=http://127.0.0.1:5188 npm run check:profit:envelope` passed 6/6 against the new
bundle, both reporting `provisional true … 2 outstanding … indicator true`, i.e. the stale case
with the label up on real data. **But the backend they reached was not this invocation's**: the
`./mvnw -o spring-boot:run` started for them had failed immediately with
`The JAVA_HOME environment variable is not defined correctly` (the Bash tool's environment has no
`JAVA_HOME`), and port 8080 was being served by a Spring Boot process the maintainer had started
from the IDE. That is recorded rather than quietly reused as evidence.

The check was therefore repeated against a backend built and started here:
`JAVA_HOME=…/.jdks/openjdk-25.0.1 GW2_API_PORT=8081 ./mvnw -o spring-boot:run` (PID 32392 under
wrapper 33308) behind `GW2_BACKEND_ORIGIN=http://127.0.0.1:8081 npx vite preview --port 5189`. The
origin's identity was proved, not assumed: the same preview started with
`GW2_BACKEND_ORIGIN=http://127.0.0.1:9999` served the page `200` and answered
`/api/system/status` with `502`, so the override is honoured and the passing run's `/api` traffic
reached port 8081; `curl http://127.0.0.1:5189/` named `index-BaBTzFuD.js`.

```
### GW2_FRONTEND_URL=http://127.0.0.1:5189 npm run check:profit:envelope   (/tmp/web034-envelope-own.log)
Page    : http://127.0.0.1:5189 (real backend behind its /api proxy)
  ok   the browser reached the backend’s Crafting Profit route — HTTP 200
  ok   the received body carries the delivered freshness envelope — status CALCULATED, provisional false, 4 account sources, 2984 required items (covered true), 0 outstanding, 0 failure(s)
  ok   rows and count follow the body status — 3205 rows
  ok   the page state agrees with the received envelope — 198 row(s) listed, indicator false, failure false
  ok   no synchronization was triggered and only advertised locations were polled — 21 API requests, 0 triggers, 0 polled location(s)
  ok   no uncaught page errors — 0 reported
Crafting Profit backend-envelope connection check PASSED (6 steps).
Read-only: no synchronization was triggered and no account content was printed.
exit=0
```

So on this bundle both outcomes of the contract were again observed on real data — the provisional
one with the indicator up (against the maintainer's backend, as stated) and the final one with the
indicator gone (against this invocation's own backend on 8081).

**Processes.** Everything this invocation started was stopped by PID, one
`taskkill /PID <n> /T /F` per tree, each call's output naming the PIDs it ended: the 8081 backend
tree (33308 → 32392, 13504), the `vite preview` trees on 5188 (32444 → 22640, 21528), 5189
(32476 → 34672, 17160) and the bogus-origin probe on 5189 (29508 → 9504, 35376). Ports 8081, 5188,
5189 and 5177 were then probed with `curl` and all refused. Port 8080 also refuses now: the
maintainer's IDE-started backend (PID 22724) is gone, and it was **not** stopped from here — no
`taskkill` call named it or its parent, and the IntelliJ JVM beside it (21844, parent 19932) is
still running untouched, as is the maintainer's `npm run dev` tree (10080).

**Limitations, stated rather than implied.** The controlled-origin checks prove presentation and
interaction against responses this repository writes, not that the backend produces those
sequences; the connection check closes only the shape question and proves no sequence. Neither is
evidence about timing: the five-minute budget is asserted against a controlled clock and never a
real one — the production-timer test drives Vitest's fake timers, so no run waited five real
minutes either — and real-data refresh timing remains STORY-PERF-004. `npm run smoke:profit:live` was not
run — see F001. No TestFX, real-database or live GW2 API layer was touched by this story. Every
process started for this evidence (one `mvnw` wrapper tree, one `java` backend, one
`vite preview` tree, plus a leftover `vitest` tree from the hung first run) was stopped by the PIDs
read out of `Get-CimInstance`, one `taskkill /PID <n> /T /F` per tree, and ports 8080, 5188, 5177
and 5176 were then probed with `curl` and all reported connection failure.

### Coverage

`npx vitest run --coverage`, read from `coverage/coverage-summary.json` after the rejection fix.
Aggregate 93.85 % lines / 86.81 % branches / 92.32 % functions, which is a scoped local figure and
not a clean baseline (`QUALITY_METRICS.md`).

| File | Lines | Branches | Functions |
|---|---|---|---|
| `crafting/useCraftingProfit.ts` | 100 % | 81.95 % | 91.66 % |
| `crafting/CraftingProfitScreen.vue` | 98.97 % | 92.59 % | 97.14 % |
| `crafting/ProfitRefreshStatus.vue` | 100 % | 88.57 % | 100 % |
| `crafting/SelectedResultDetail.vue` | 100 % | 95 % | 100 % |
| `crafting/useProfitResolution.ts` | 100 % | 91.66 % | 100 % |
| `api/types.ts` | 100 % | 100 % | 100 % |

The new `timerAlarm` first measured as four uncovered lines (`lcov.info`: `DA:62..66`), the only
uncovered lines in the file — the production timer a component test never takes. It is covered
directly instead of excused, by `firesTheBrowsersOwnBudgetTimerAndCancelsItWithTheCycle`, which
restored the file to 100 % lines.

Unresolved gaps against the 90 % warning KPI, named rather than padded:

- `useCraftingProfit.ts` 81.95 % branch. Two groups. The `refresh.now ?? …` / `refresh.sleep ?? …` /
  `refresh.alarm ?? …` fallbacks and the screen's `refreshClock === null` arms are the *production*
  clock, waiting and timer: a component test always injects them, so those arms are only ever taken
  in a real browser, which is what the browser runs above exercised (`timerAlarm`'s own body is
  covered, as above; it is the `??` selection of it that a component test never takes). The rest are
  the `cycle !== activeCycle` and
  `requestId !== newestRequestId` guards placed after every await; each is covered in one direction,
  and covering the other at every single checkpoint needs an interleaving the test would have to
  construct per await rather than per behaviour. The behaviours they protect *are* covered — a late
  recovery answer, an abandoned cycle and a superseded selection each have their own test.
- `ProfitRefreshStatus.vue` 88.57 % branch. The remaining arms are a null `freshness` and an empty
  `outstandingWork` while the chip is visible. Neither is reachable through the screen: visibility
  requires a provisional or pending envelope, and an empty `outstandingWork` stops recovery into the
  failure branch instead. They are left as unreachable-through-the-screen rather than exercised by
  mounting the component in a state the page cannot produce.
- `useResolutionDetail.ts` 77.77 % branch is pre-existing; this story did not change that file.

### Documentation

`CURRENT_STATE_SPEC.md` (the delivered browser behaviour, and the **Refresh data & results** action
no longer submitting an account task itself), `CURRENT_ARCHITECTURE.md` §4 (the browser side of the
envelope: one bounded cycle per context, injected clock, the pacing rule, the correlation
counters, the detail generation, and the two new scripts — extended after the rejection with the
budget timer that holds while a request is pending and with the cycle-scoped answer guard, and
`CURRENT_STATE_SPEC.md` with the same behaviour in user-visible terms), `TEST_STRATEGY.md` §12.2 (two new
verification levels with what each does and does not establish), `docs/crafting/README.md`
(the freshness section: a provisional result is the ordinary result over older inputs) and
`docs/crafting/GLOSSARY.md` (**Provisional result**, **"Stale data – refreshing…"**). No
authoritative rule was copied out of `DOMAIN_SPEC.md`, `TARGET_ARCHITECTURE.md` §9.1 or ADR-007, and
no `KNOWN_PROBLEMS.md` entry is closed or reduced by this story.

### QA plan

No QA-owned test existed (`prepared_test_paths: []`, `pre_implementation_verification: NOT_RUN`);
nothing was edited, skipped or weakened. Every test specification in the plan is implemented, under
the file name the plan's command named, and the plan's commands were run:
`npx vitest run src/crafting/__tests__/CraftingProfitRefreshWorkflow.spec.ts` (31 passed),
`npm run type-check` (exit 0) and `npm run build` (exit 0). The plan's
`post_implementation_review_required: true` stands.

The plan's own AC4 verification — "controlled-clock and transport component tests for cumulative
deadline across task changes" — was satisfied only for *answered* requests before the rejection.
Its invariant "one five-minute deadline covers the entire automatic recovery cycle" is now also
exercised where no request answers at all, which is what the three new regressions add.

One plan expectation was preserved rather than changed and is flagged for independent review: the
browser acceptance check asks to "extend/run the focused Profit browser smoke". The lifecycle was
delivered as a *separate* script (`smoke:profit:refresh`) instead of inside the 2 000-line
`profit-browser-smoke.mjs`, because that script's 35 steps all depend on one confirmed-current
response set and a scripted provisional/failure sequence inside it would change what every later
step measures. Both scripts are shipped and both were run; the asserted content is the plan's.

### Remaining uncertainty

- The five-minute bound is proved against a controlled clock. The constant is the one UD-015 names,
  but no run measured a real five minutes elapsing, and a client timeout still implies nothing about
  backend cancellation — which is why retry re-asks the calculation rather than cancelling.
- `reuse: 'WAITING_FOR_UNRELATED_TASK'` is followed like any other advertised task: the cycle waits
  for it and resubmits, letting the backend admit the right work next. That is deliberate, but no
  real-data run produced that value, so only the component tests cover the shape.
- The envelope connection check observed one account's data on one machine — two visits in the
  second invocation, three in the third and three in the fourth. It establishes that the browser
  receives the delivered envelope and agrees with it, not that every sequence the backend can
  produce is handled. No live run has yet produced a failure item, an `UNKNOWN` task or a `202`;
  those shapes are covered only by the component tests and the scripted browser sequence. Two of
  the fourth invocation's three visits reached a backend it did not start (see *Browser checks*),
  so only the third of them attests the backend's source revision.
- No live run has produced a stalled task-status read or recalculation either: the bounded
  termination the rejection was about is covered by the controlled regressions and by the browser
  script's scripted failure, not by a real backend going silent mid-cycle.

## Follow-up Findings

F001: `frontend/scripts/profit-live-smoke.mjs` (`npm run smoke:profit:live`) cannot pass against the
current page. Its `settled()` waits for `[data-test="reload"]` and a later step clicks it, but no
component has rendered that hook since commit `bfef964`; the Crafting Profit page's controls are
`refresh-data-and-results` and `request-retry`. The script was not run or repaired here — this
story's browser requirement is met by `smoke:profit:refresh` and `check:profit:envelope` — but any
story that cites `smoke:profit:live` as evidence would be citing a check that stops at its first
wait.

## Follow-up Findings Disposition

F001: DISMISSED — Missing verification in a legacy smoke script, not a verified application defect. Current inspection confirms `profit-live-smoke.mjs` still waits for `reload`, while `CraftingProfitScreen.vue` renders `refresh-data-and-results` and `request-retry`. This does not invalidate the separate refresh-browser and backend-envelope checks recorded in this story's Result. Standalone repair is optional and is not mandatory Phase 5 work; Request-013-raise-backend-test-coverage.md excludes cosmetic smoke follow-ups without an app defect. `agent/stories/STORY-PERF-004-cached-profit-page-acceptance.md` already requires current real-browser/equivalence evidence and permits necessary measurement-harness changes. Its acceptance must not rely on this stale script passing unchanged.

## Blockers

None.
