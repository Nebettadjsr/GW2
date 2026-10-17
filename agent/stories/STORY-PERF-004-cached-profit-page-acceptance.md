## Story ID

STORY-PERF-004

## Title

Verify real-data cached Profit page and asynchronous refresh acceptance

## Status

UNFINISHED

## Milestone

milestone-05

## Goal

Establish reproducible Phase 5 performance and workflow evidence for the implemented cached-result contract, replacing historical fresh-cache-only acceptance assumptions.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md, supplied Phase 5 performance and health-review exit criteria.
- docs/TARGET_ARCHITECTURE.md sections 9.1, 17 and 21.
- docs/TEST_STRATEGY.md sections 12, 34, 35.2 and 36.
- docs/DOMAIN_SPEC.md sections 2.2.1, 25, 39 and 40.
- docs/QUALITY_METRICS.md, Test coverage KPI and Establishing the clean baseline.
- agent/user-decisions/UD-013-phase-five-performance-user-acceptance.md and UD-015-trading-post-refresh-performance-budget-scope.md.
- agent/stories/STORY-QUALITY-005-phase-five-completion-review.md, Result F1/F2/F5.
- agent/stories/STORY-PERF-003-refresh-phase-five-page-budget-evidence.md, Result and F001-F003 dispositions.
- agent/stories/STORY-APP-014-cached-profit-refresh-coordination.md and STORY-WEB-034-provisional-profit-refresh-workflow.md.

## Context

Historical fresh-input runs met seven seconds but did not implement the now-decided stale-while-revalidate contract. The expired-TP run measured 17,206 ms. UD-013 removes manual acceptance, while UD-015 requires separate initial cached-result and asynchronous refresh evidence. The completed health review remains the bounded assessment; this story supplies changed-contract evidence for the planner's closure review, not another broad review.

## Acceptance Criteria

1. Measure the current production browser build and real user PostgreSQL data, default All scope, first startup opening and repeat navigation in the required browser/application cache states. Record revision, environment, effective settings, data counts, individual readings and maxima without disclosing credentials/private contents.
2. Demonstrate the initial complete usable cached page within seven seconds for fresh inputs, expired account inputs, expired TP quotes and both expired. Include full requested results, required controls, interactive displayed-value reads and quiescence tied to the initial calculation context/input versions. First-row timing or a loading placeholder is not completion.
3. Separately measure refresh start to terminal outcome, refreshed calculation/rendering and navigation to final result. Confirm backend freshness on final captured inputs and correct indicator termination; do not extend the initial sample through a later replacement or invent an additional refresh-speed threshold.
4. Exercise overlapping insufficient-scope account work and changed TP demand, plus missing-input and failure/timeout recovery. Confirm retained cached results and bounded termination. Clearly label controlled-boundary correctness checks separately from real-data timing; if no usable cache exists, report readiness wait and eventual calculation/rendering as an unavailable cached-page scenario rather than seven-second compliance.
5. Verify economic/domain equivalence from the implementation reference and unchanged-snapshot HTTP/application comparison: no result truncation, fee/ownership/quantity change or frontend recalculation. Settings and valid view state survive final replacement, with row/detail version compatibility.
6. Record findings, limitations and explicit assessment of health-review F1/F2/F5 and PERF-003 F001/F002 against this evidence. Report a breach or unavailable environment as such; do not declare phase completion, waive criteria or request an obsolete manual acceptance gate.

## Required Tests

- Explicit real-browser navigation-to-complete-page measurements and real-database equivalence checks under TEST_STRATEGY sections 34 and 35.2; CI cannot run these.
- Focused checks of measurement context/version separation and actual effective-input capture when extending the existing harness for this contract. Use controlled page transitions to ensure later refresh replacement is timed separately and a placeholder cannot pass.
- Reuse delivered deterministic refresh/recovery regressions; run only targeted checks needed to resolve concrete acceptance questions, not full local suites. Review affected reports when measurement code changes, without estimating coverage or changing scope.

## Constraints

- Read real data for acceptance; never reduce/replace it or point destructive fixture setup/teardown at the user database. Preserve private comparison references outside version control.
- Measurement harness changes must be necessary to verify the new contract, not cosmetic reporting cleanup. No production remediation, broad bug hunt, speculative cleanup, later-phase work or agent/build/CI changes.
- Required full CI is owned by the pipeline after acceptance. No manual Product Owner confirmation is needed for compliant evidence under UD-013.

## Dependencies

- STORY-APP-014
- STORY-WEB-034

## Definition of Done

Reproducible current-contract measurements, equivalence evidence, scenario outcomes and limitations are recorded in Result. Concrete findings are reported through the normal workflow for planner disposition. A breach or lack of access must remain visible; this story cannot manufacture acceptance. Any affected targeted harness tests pass and relevant coverage gaps are recorded. The planner retains milestone closure authority.

## Result

Measured. The cached-result contract was measured in a real browser against the user's real
PostgreSQL database in all four required input-cache states at revision `02eb09e`. **Every initial
complete usable page was within §17's 7 000 ms; the overall maximum over the 20 measured openings is
5 145 ms (74 % of the budget).** The state PERF-003 recorded as a 2.5× breach — expired Trading Post
quotes — now completes in **3 034 ms** with the 2 984-item refresh running asynchronously. The
asynchronous refresh is reported separately, established from the backend's own task status for the
work the envelope advertised, and is never inside an initial interval.

This Result records measurements and limitations. It does not close Phase 5; the planner retains
milestone closure authority.

### What this invocation changed, and what it re-measured

The previous attempt was rejected on one remaining point: the reported initial-page times were taken
*before* the interactivity evidence they claimed. The completion instant was the last change of the
displayed state, while the displayed-value read and the enabled-selection check were taken after the
settle window had elapsed — in `expired-tp` opening #5, a reported 4 316 ms with its reads at 7 442
and 8 513 ms. Both reads were inside the initial calculation context, so nothing was attributed to
the replacement, but AC2 counts a successful displayed-value read as part of completion, and a number
whose usability is established 3 s later does not prove the budget was met at that number.

So the read is now part of the boundary rather than a check after it:

- **The quiescence key carries the read.** `quiescenceKeyOf` hashes the driver's displayed-state
  fingerprint *and* `contentOf`, which includes the value read off a result row and that row's
  selection state. A value that only becomes readable, or a control that only becomes enabled, is a
  change of the displayed state and moves the boundary instead of being settled over.
- **The reported instant is the boundary sample's own.** A context completes at the sample the
  quiescent state was *first* observed on, and completeness — rows, controls, no notice, no
  in-viewport image still loading, the displayed value, the enabled selection control, and the
  requested row count for a complete-set opening — is decided on that same sample. `completedAt`,
  `displayedValueRead`, `selectionEnabled` and `displayedValueReadAt` therefore all come from it, and
  `displayedValueReadAt === completedAt` holds by construction.
- **Both the attribution and the driver assert it.** `violationsOf` raises a contract violation if a
  settled initial context reports a time that is not the instant of a successful read with the
  selection control enabled, and `measureOpening` fails the run on the same condition. The
  post-settle live re-read is kept, but only as corroboration, and the log says so on every opening.
- **The settle window is still excluded from the number.** The confirmation sample is reported
  separately (`confirmed at …`), as before.

Because every reported figure moved, all four real-browser phases were re-measured on one
continuously observed backend process, and the deterministic, smoke and real-database checks were
re-run in the same session. The earlier sessions' readings are superseded and are not carried into
this Result. No production code was changed by this story at all.

### Run context

- **Revision `02eb09e`** ("Pipeline: own agent branch and worktree, maintainer sync with doc review,
  backlog actions"), measured in the pipeline worktree `C:\Users\Administrator\IdeaProjects\GW2-agent`.
  `git status` lists **no modified product source**: only `agent/` workflow documents,
  `docs/TARGET_ARCHITECTURE.md` and `docs/architecture/decisions/ADR-007…` (planner / architect work
  this story integrates but did not write), and this story's own three harness files. Against the
  revision the previous sessions measured (`c6e5fd2`) the only product difference is the Dependabot
  `org.postgresql:postgresql` bump in `pom.xml`.
- **Measured bundle**: `npm run build` (`vue-tsc --noEmit` clean, `target/perf004-build-s5.log`)
  emitted `dist/assets/index-CeXYsep4.js` 181.64 kB / 58.32 kB gzip and
  `dist/assets/index-DFi1pNbV.css` 38.60 kB / 6.69 kB gzip. The served `index.html` was read back over
  HTTP and names exactly those two assets, so one bundle underlies every reading below.
- **Topology**: Chrome 154.0.8037.93 → `GW2_BACKEND_ORIGIN=http://127.0.0.1:8199 npx vite preview
  --host 127.0.0.1 --port 5179 --strictPort` (**PID 34268**, parent 18184) → recording proxy on
  `127.0.0.1:8199` (started by the harness per phase) → backend
  (`JAVA_HOME=…/.jdks/openjdk-25.0.1 GW2_API_PORT=8091 ICON_CACHE_DIR=%LOCALAPPDATA%\Temp\gw2-perf004-icons
  GW2_GLOBAL_REFRESH_INITIAL_DELAY_MS=86400000 ./mvnw -o spring-boot:run`, **PID 35312** under wrapper
  30264, Tomcat up on 8091 at 17:38:33.990+02:00, `target/perf004-backend-session5.log`) → the real
  local PostgreSQL database. All four phases ran against that one backend process, which was stopped
  by PID before the Maven checks below.
- *Positive identity of what answered*, per `tasks/lessons.md`: with the recorder deliberately not
  running, `127.0.0.1:5179/` answered **200** and `127.0.0.1:5179/api/system/status` answered **502**
  while `127.0.0.1:8091/api/system/status` answered **200** directly — so the preview's `/api` really
  pointed at the recorder's port and not at the backend or any other listener, and nothing but the
  recorder could have served the measured answers. The recorder then recorded 1 626 / 1 556 / 1 681 /
  1 578 backend requests across the four phases. Ports 8091, 5179 and 8199 had no listener before the
  session and none after it was stopped by PID.
- **A discarded first attempt, for completeness**: the preview was first started in this worktree
  *without* `GW2_BACKEND_ORIGIN`, so its `/api` proxy fell back to `localhost:8080` (the
  local-development default in `vite.config.ts`) and no browser request ever reached the recorder or
  the backend. The harness measured nothing — it failed the first opening after its 240 s limit — and
  because no backend request was issued, no input state changed. The repository carries no local
  `.env`, which is why that variable has to be in the shell.
- **Host**: 6× Intel i5-9600K @ 3.70 GHz, 64 GB RAM, Windows 10 Pro for Workstations 10.0.19045;
  OpenJDK 25.0.1; node v22.13.1; viewport 1440×900. The 6-hourly administrative global refresh was
  pushed out of the measurement window (`GW2_GLOBAL_REFRESH_INITIAL_DELAY_MS=86400000`).
- **Scope and settings**, taken from the backend's own echo in every measured answer (not from a page
  hook — PERF-003 F003): **scope `{"kind":"ALL","discipline":null,"characterName":null,"rating":0}`**
  · `useOwnMats true` · `allowBuying false` · `maxBuyCopper 10000` · `listingSell false` ·
  `listingBuy false` · `allowDailyCrafts false`. Identical in all 19 calculations the four phases
  issued (5 + 4 + 5 + 5).
- **Data counts** (from the backend's own response/envelope): **rowCount 3 205** for scope ALL,
  203–211 rows matching the default display filters, **2 984** required Trading Post items, **4**
  required account sources (`ACCOUNT_RECIPES`, `BANK`, `CHARACTERS`, `MATERIALS`), 3 190 rows with an
  icon URL and 15 without.
- **Isolation**: a disposable icon store (`%LOCALAPPDATA%\Temp\gw2-perf004-icons`, 1 295 → 1 358
  entries) instead of the user's own; a fresh, previously unused disposable browser profile per phase
  (`gw2-perf004m`/`n`/`o`/`p`) — never the user's browser profile. No credential and no account
  content is printed anywhere in this Result or in the logs.
- **Read-only against the user's data**: nothing was seeded, truncated or replaced. The only writes
  are the product's own refreshes — the account synchronisations and Trading Post price fetches the
  contract itself performs, plus the supported trigger route used to *arrange* one cache state
  (`POST /api/prices/refresh {"variant":"PROFIT"}`).

### AC1 + AC2 — the four input-cache states, first opening and repeats

One invocation per state; the harness checks the claimed state against the backend envelope's own
account-source states and price coverage and fails a mislabelled run, so each line below is the state
the backend reported, not the state the phase was named after. "initial" is the navigation action →
the boundary sample of the initial calculation context: the first sample of the state that then
stayed unchanged through the 3 000 ms settle window, which that window is excluded from. The last
column is the displayed value read **from that same sample, at that same instant**, with the row's
selection control enabled there.

| Phase (log, UTC) | Opening | initial complete usable page | rows shown / requested | contexts | read at the reported instant |
|---|---|---|---|---|---|
| **both-expired** (`perf004-both-expired-s5.log`, 15:45:57→15:47:03) | first, document navigation | **2 493 ms** (first rows 2 367) | 211 / 3 205 | `f9b8375d` provisional → `4d1de460` final | `"+22g 19s 12c"`, enabled, at 2 493 ms |
| | repeat, document reload | **1 738 ms** | 203 / 3 205 | 1 final | `"+22g 69s 22c"`, enabled, at 1 738 ms |
| | repeat, in-application navigation | 145 ms | 203 / 3 205 | 0 calc., client-held | `"+22g 69s 22c"`, enabled, at 145 ms |
| | complete set, document navigation | **2 390 ms** (first rows 2 293) | **3 205 / 3 205** | 1 final | `"+25g 53s 99c"`, enabled, at 2 390 ms |
| | complete set, repeat reload | **4 544 ms** | **3 205 / 3 205** | 1 final | `"+25g 53s 99c"`, enabled, at 4 544 ms |
| **fresh** (`perf004-fresh-s5.log`, 15:47:10→15:47:42) | first, document navigation | **3 176 ms** (first rows 3 047) | 203 / 3 205 | 1 final | `"+22g 69s 22c"`, enabled, at 3 176 ms |
| | repeat, document reload | **2 160 ms** | 203 / 3 205 | 1 final | `"+22g 69s 22c"`, enabled, at 2 160 ms |
| | repeat, in-application navigation | 80 ms | 203 / 3 205 | 0 calc., client-held | `"+22g 69s 22c"`, enabled, at 80 ms |
| | complete set, document navigation | **2 773 ms** (first rows 2 672) | **3 205 / 3 205** | 1 final | `"+25g 53s 99c"`, enabled, at 2 773 ms |
| | complete set, repeat reload | **5 145 ms** | **3 205 / 3 205** | 1 final | `"+25g 53s 99c"`, enabled, at 5 145 ms |
| **expired-account** (`perf004-expired-account-s5.log`, 16:01:38→16:02:50) | first, document navigation | **2 919 ms** (first rows 2 793) | 203 / 3 205 | `e2578136` provisional → `f5ca2293` final | `"+22g 68s 76c"`, enabled, at 2 919 ms |
| | repeat, document reload | **1 957 ms** | 203 / 3 205 | 1 final | `"+22g 68s 76c"`, enabled, at 1 957 ms |
| | repeat, in-application navigation | 156 ms | 203 / 3 205 | 0 calc., client-held | `"+22g 68s 76c"`, enabled, at 156 ms |
| | complete set, document navigation | **2 683 ms** (first rows 2 596) | **3 205 / 3 205** | 1 final | `"+25g 51s 91c"`, enabled, at 2 683 ms |
| | complete set, repeat reload | **4 677 ms** | **3 205 / 3 205** | 1 final | `"+25g 51s 91c"`, enabled, at 4 677 ms |
| **expired-tp** (`perf004-expired-tp-s5.log`, 16:12:25→16:13:08) | first, document navigation | **3 034 ms** (first rows 2 841) | 203 / 3 205 | `a7a05a14` provisional → `18ed10a0` final | `"+22g 68s 76c"`, enabled, at 3 034 ms |
| | repeat, document reload | **1 821 ms** | 206 / 3 205 | 1 final | `"+22g 70s 3c"`, enabled, at 1 821 ms |
| | repeat, in-application navigation | 127 ms | 206 / 3 205 | 0 calc., client-held | `"+22g 70s 3c"`, enabled, at 127 ms |
| | complete set, document navigation | **2 433 ms** | **3 205 / 3 205** | 1 final | `"+25g 53s 99c"`, enabled, at 2 433 ms |
| | complete set, repeat reload | **4 757 ms** | **3 205 / 3 205** | 1 final | `"+25g 53s 99c"`, enabled, at 4 757 ms |

- **Per-phase maxima: both-expired 4 544 · fresh 5 145 · expired-account 4 677 · expired-tp 4 757.
  Overall maximum 5 145 ms, i.e. 1.86 s of headroom.**
- **Every one of the 20 openings is at most 7 000 ms, including the three expired-input states**, and
  the maxima are complete-requested-set openings — not favourable warm cases.
- **The interactive read is inside the measured interval, at its end.** In every row above the read's
  timestamp *is* the reported completion, because they are the same sample; the harness fails the run
  if they differ (`the displayed-value read is at X ms but the reported completion is Y ms`). The
  post-settle live re-read is still taken and logged — 6 035 / 6 355 / 6 005 / 6 071 ms on the four
  first openings, each with the calculation ids answered by then (`["f9b8375d"]`, `["4d1de460"]`,
  `["e2578136"]`, `["a7a05a14"]`, i.e. the replacement had not yet answered) — but it is labelled
  corroboration and no reported number depends on it.
- **What "complete" required**, enforced by the harness rather than claimed: rows present, the result
  controls present, no loading/pending/error notice, **no in-viewport image still loading**, **a
  non-empty displayed value read from a result row and an enabled selection control on that row**,
  and that state quiescent for 3 s — all decided on the one sample whose instant is reported. "First
  rows displayed" is reported separately and is never the reported completion (it is 126–193 ms
  earlier on a first opening; on the complete-set and repeat openings it is 0–101 ms earlier, because
  the rows and the readable, selectable state of the table arrive within one or two samples there).
- **Complete requested set**: the two complete-set openings per phase clear every display filter and
  switch "Show all" on *inside the same continuous timer*, and each ended with **3 205 / 3 205** rows
  in the DOM. No truncation at the measured boundary.
- **In-application navigation is reported but not comparable**: this application re-renders
  client-held rows and issues no calculation, so 80–156 ms measures re-rendering, not a page opening.
  The harness labels it as such and attributes it to the generation already displayed.
- Every phase: **0 browser requests to ArenaNet, 0 off the page's own origin, 0 images off the
  backend's icon route, 0 uncaught page errors, 0 evidence gaps, 0 contract violations** — each of
  those is an assertion inside the harness, so the four `exit=0` runs are what establishes them.

### AC3 — the asynchronous refresh, measured separately and correlated with the advertised work

Reported per provisional opening, never merged into the initial interval and never turned into a
budget (§17 establishes no numerical refresh-speed threshold). "admitted → terminal" starts at the
answer that advertised the work and ends at the **terminal task state the backend reported for that
work**, read from `/api/sync/tasks/{id}`; the recalculation and its rendering are after it and are
reported on their own.

| Phase | advertised work (task) | status reads | admitted → terminal | backend execution (its own instants) | terminal → final answer | refreshed render | navigation → final result |
|---|---|---|---|---|---|---|---|
| both-expired | `ACCOUNT[…4 sources]` `8beb3ac3` RUNNING/QUEUED_BEHIND_ACTIVE_RUN **and** `TRADING_POST[prices]` `bff1d992` RUNNING/ADMITTED | 105 + 1, both →SUCCEEDED | **34 998 ms** | 35 981 ms across both (account 35 981 queued 4 ms, prices 4 811 queued 0 ms) | 1 457 ms | 177 ms | **38 763 ms** |
| expired-account | `ACCOUNT[…4 sources]` `2e9a839f` RUNNING/QUEUED_BEHIND_ACTIVE_RUN | 121, RUNNING→SUCCEEDED | **40 114 ms** | 41 653 ms (queued 0 ms) | 1 897 ms | 94 ms | **44 654 ms** |
| expired-tp | `TRADING_POST[prices]` `abbdb072` PENDING/ADMITTED | 12, RUNNING→SUCCEEDED | **11 475 ms** | 11 206 ms (queued 7 ms) | 1 712 ms | 170 ms | **15 956 ms** |

- **Outcome is `replaced` in all three cases**, and it means what it says: every advertised,
  followable piece of work reported a terminal `SUCCEEDED`, and a final (non-provisional) context
  exists. Work that is advertised but never polled is reported as uncorrelated and raises a contract
  violation instead of being inferred from the indicator — the harness has no path that turns a
  disappearing indicator into a refresh outcome.
- **The initial interval is bounded by its own calculation context.** The initial reading is the
  boundary sample inside the initial context's window, which ends when the replacement's answer
  arrives, so a replacement whose answer came 15.8 / 38.6 / 44.6 s into the opening cannot extend it;
  a resubmission that captured the *same*
  `calculationId` is treated as the same context.
- **The refresh's own execution varies with the upstream, and nothing in §17 bounds it.** The same
  Trading Post refresh of 2 984 items executed in 11 206 ms here and in 4 811 ms in the `both-expired`
  phase 26 minutes earlier, and the arrangement trigger at 16:01:01Z took 26.8 s for the same work.
  That variance is upstream-side and is exactly why it sits outside the initial interval; it is
  reported, not budgeted.
- **Final means final on the captured inputs**: in all three cases the final context's envelope
  reports all four required account sources `FRESH` and Trading Post coverage `covered true` over the
  full 2 984 required items — read from that answer's own envelope, not from a later live marker.
- **Indicator termination was verified against the final rendering, not against the final answer.**
  `both-expired`: indicator gone at 38 695 ms, final result rendered at 38 695 ms (the rendered
  content changed there). `expired-tp`: gone at 15 886 ms, rendered at 15 886 ms. `expired-account`:
  gone at 44 654 ms while the replacement rendered **content identical** to the cached result, so the
  harness says so explicitly and falls back to the final answer's arrival (44 559 ms) as the earliest
  provable rendering rather than claiming a distinction it cannot observe. No phase removed the
  indicator early, and no run displayed a provisional result without it. **Zero violations in every
  phase.**
- No opening reached the harness's 420 s observation limit.

### AC4 — boundary scenarios (controlled-boundary checks are labelled separately from real-data timing)

**Observed on real data** (timings above, states from the backend's own envelope):

- *Overlapping insufficient-scope account work*: in `both-expired` and `expired-account` the first
  profit answer advertised
  `ACCOUNT[ACCOUNT_RECIPES+BANK+CHARACTERS+MATERIALS] RUNNING/QUEUED_BEHIND_ACTIVE_RUN` — an account
  run was already active (started by the page's other freshness-checked requests) and did not cover
  the four sources this caller needs. The cached page was delivered anyway (2 493 / 2 919 ms) and the
  final result afterwards reported all four sources FRESH; the task the caller was handed reached
  `SUCCEEDED` after 36.0 / 41.7 s of backend execution. This is PERF-003 F002's exact sequence, now
  resolved without a stale error reaching the user.
- *Changed Trading Post demand*: in `both-expired` the envelope admitted two separately identified
  pieces of work (`ACCOUNT[…]` `8beb3ac3` and `TRADING_POST[prices]` `bff1d992`), each with its own
  task status, and the final context reported full coverage — so no further uncovered demand remained
  after the account inputs changed. The `expired-tp` phase shows the Trading Post mechanism alone,
  with the account sources already fresh.
- *A usable stale result stays a complete ordinary result*: every provisional opening displayed the
  full requested row set for its filters with real money values and an enabled selection control;
  expiry degraded nothing.
- **Not reachable on real data, reported as such:** no opening lacked a usable cached result
  (`resultAvailable` was true everywhere), and no refresh failed or timed out during these runs.
  Forcing those would mean deleting the user's synchronised account data or breaking the live GW2 API
  path, which this story's constraints forbid. **No seven-second claim is made for the no-usable-cache
  case**; the harness is built to report it as a readiness wait rather than compliance, and that path
  is exercised in the focused tests below.

**Controlled boundaries (not real-data timings)** — all re-run in this invocation, exact commands and
results under "Commands run":

- `CachedCraftingProfitServiceTest` (17 tests): fresh / expired-account / expired-prices /
  both-expired matrices, a successfully synchronised empty source being usable, **a source that never
  synchronised producing a pending state and no fabricated result**, a succeeded task not making a
  result final while markers are stale, a forgotten task reported as bounded recovery information,
  and the envelope describing captured — not newer — versions.
- `AccountRefreshCoordinatorTest` (7) and `TradingPostCoverageCoordinationTest` (4):
  insufficient-scope overlap and coverage re-checking after active work.
- `CraftingProfitRefreshWorkflow.spec.ts` (31): the five-minute cumulative recovery deadline including
  stalled requests, late-answer rejection, bounded failure with retry.
- `profitTiming.spec.mjs` (42, this story's own): the attribution rules a live page cannot stage —
  see "Harness extension" below for the boundary group and the controls that prove they bite.
- `npm run smoke:profit:refresh` (11 steps, real browser, scripted origin): cached rows render
  immediately with the indicator, account and Trading Post freshness stay distinguishable, only
  advertised task locations are polled, **bounded failure keeps the rows, stops the animation and
  offers a retry**, and an explicit retry re-asks the calculation and starts no refresh itself.

### AC5 — economic and domain equivalence

- **Unchanged-snapshot HTTP ↔ application comparison on the real database**
  (`CraftingProfitApiRealDbEquivalenceIT`, §35.2), re-run in this session: for the default All request
  and for All/buying-enabled-at-10g/listing-prices, the endpoint was called until its envelope
  reported a **final** result and the same application method was then called in process with
  identical inputs: `default All (empty request body): 3205 rows equivalent between HTTP and
  in-process calls` and `All, buying enabled at 10g, listing prices: 3205 rows equivalent…` — every
  row, in order, field by field, including blocked state and both missing-material maps. No
  truncation, no fee/ownership/quantity change at the boundary.
- **Implementation-reference capture comparison** (`CraftingProfitInputCaptureEquivalenceIT`): the
  captured-input path and the ordinary reference path produced identical results for both settings
  combinations on the same snapshot; the two references (190 028 and 371 559 bytes) were written to
  the JVM temp directory, **outside version control**, as that check's design requires.
- **No frontend recalculation**: `npm run smoke:profit` (35 steps) re-asserts that every displayed
  count, money value and state is a field of the response, and `smoke:profit:refresh` step 7 shows a
  newer-input detail explaining *its own* row while the table keeps its result (row/detail version
  compatibility), step 8 that the replacement **keeps search, sort and selection and removes the
  indicator**.
- On real data every opening's displayed value is recorded with the context it was read in, so the
  provisional and refreshed results are comparable without being conflated: `expired-tp` read
  `"+22g 68s 76c"` on the cached page and `"+22g 70s 3c"` after the Trading Post quotes were
  refreshed — a changed quote changing a displayed total, with the row set and controls unchanged;
  `expired-account` read the same `"+22g 68s 76c"` before and after its replacement. The complete-set
  openings read `"+25g 53s 99c"` / `"+25g 51s 91c"` as the inputs moved.

### AC6 — assessment of the cited findings

- **QUALITY-005 F1** (the budget evidence predated the calculation path it measured): **addressed.**
  Every reading above is at the current revision `02eb09e`, on the current calculation path, with the
  §34 real-data equivalence comparison run in the same session. Phase 5's performance criterion no
  longer rests on the 2026-09-29 figure.
- **QUALITY-005 F2** (whether an explicit Product Owner acceptance gate still exists): **resolved by
  decision, not by this measurement.** UD-013 removes the manual gate and §17/§34 now say so
  explicitly; this Result therefore requests no confirmation. Recording that the documents agree is
  the planner's to close.
- **QUALITY-005 F5** (a stale-data page open could wait for a full account synchronisation):
  **resolved behaviourally and measured.** With all four required account sources STALE the page did
  not wait: it completed in 2 919 ms (`expired-account`) and 2 493 ms (`both-expired`) while the
  account synchronisation ran asynchronously — the backend's own task instants put that work at
  41 653 ms and 35 981 ms of execution, finishing long after the page was usable. The five-minute
  embedded wait F5 described is not on this path any more.
- **PERF-003 F001** (expired Trading Post quotes = 17 206 ms, 2.5× the budget): **resolved and
  re-measured.** The same state now gives a complete usable page in **3 034 ms** with the 2 984-item
  fetch outside the request: the refresh task itself executed in 11 206 ms, the recalculation it
  caused took 1 712 ms and its rendering 170 ms, for 15 956 ms navigation → final result. The
  in-request refresh that caused the breach is gone from the measured path, and the refresh being
  *slower* here than in the earlier session changes the initial page not at all — which is the point
  of the contract.
- **PERF-003 F002** (a caller handed an account task of insufficient scope): **no longer observable at
  the browser boundary.** Two real-data openings hit exactly that overlap
  (`RUNNING/QUEUED_BEHIND_ACTIVE_RUN`) and each produced a usable page immediately and a final result
  with all four sources fresh, with the reused task followed to `SUCCEEDED`;
  `AccountRefreshCoordinatorTest` covers the rule deterministically.
- **PERF-003 F003** (the harness sampled a removed `effective-settings` hook, so settings read
  `null`): **closed by this story's harness change.** Effective settings and scope are now taken from
  the backend's own echo for the measured request, and an answer missing them ends the run as an
  evidence gap instead of being measured silently.
- **No breach is being reported, and no criterion is waived.** Three scenarios could not be produced
  against the real database without destroying user data or the live API path and are reported as
  unmeasured rather than as passes: no-usable-cache readiness, refresh failure, refresh timeout.

### Harness extension, and why it is necessary

The pre-contract harness folded "how many calculations the page has issued" into its quiescence
fingerprint and had no notion of a calculation context, so under stale-while-revalidate the initial
sample ran until the *refresh* had replaced the page — which is exactly what §17 excludes. The
attribution rules were therefore extracted into `frontend/scripts/profitTiming.mjs` (pure, no
Playwright) and driven by scripted observation streams in
`frontend/scripts/__tests__/profitTiming.spec.mjs` (**42 tests**), because a real page settles once
and cannot reproduce on demand the cases that matter: a replacement rendering identical values, a
placeholder preceding the table, a page that becomes interactive only after its replacement arrives,
a table that becomes readable or selectable part-way through its own context, advertised refresh work
that is never polled, an answer that stopped carrying its envelope.

The group added in this invocation is about *where the reported instant falls*:

- the completion is reported **at the boundary sample**, and the read is at that same instant;
- a context whose displayed value becomes readable only later completes **at that later instant**, not
  at the earlier quiescent-looking one, and the same for a selection control that becomes enabled;
- a context that never becomes usable reports **no time at all** rather than a time without evidence;
- the earlier groups still hold: the initial interval cannot complete while only the replacement is
  interactive, and the replacement's reading stays with the replacement.

**Controls** (throwaway copies beside the script, run, then deleted; the shipped files were never
edited — `git status` afterwards lists no `.tmp-control-*` file):

| Behaviour removed in the copy | Result |
|---|---|
| the boundary rule: the copy reports the settle-confirmation sample's instant (the previous attempt's behaviour) instead of the boundary sample's | `Tests 4 failed \| 38 passed (42)` — `expected 4000 to be 1000`, `expected 8000 to be 5000`, `expected 9000 to be 6000`, `expected 33250 to be 30250`: every reported time slides 3 s past the instant its read was taken at (`target/perf004-control-boundary-s5.log`) |
| the two interactivity requirements in `completenessOf` | `Tests 6 failed \| 36 passed (42)` — `expected true to be false` on both "only the replacement is interactive" cases, `expected 1000 to be 5000` / `to be 6000` where the completion should have waited for the value and the control, `expected 1000 to be null` for a context that never became usable (`target/perf004-control-interactivity-s5.log`) |

### Commands run (exact, with real output)

From `frontend/` unless stated; every capture is in `target/` and `exit=` was appended to each.

```
### npm run build                                         (target/perf004-build-s5.log)
dist/index.html                   0.99 kB │ gzip:  0.56 kB
dist/assets/index-DFi1pNbV.css   38.60 kB │ gzip:  6.69 kB
dist/assets/index-CeXYsep4.js   181.64 kB │ gzip: 58.32 kB
✓ built in 621ms                                          exit=0

### GW2_PERF_PAGE_URL=http://127.0.0.1:5179 GW2_PERF_RECORDER_PORT=8199 \
### GW2_PERF_BACKEND_ORIGIN=http://127.0.0.1:8091 GW2_PERF_PROFILE_DIR=%LOCALAPPDATA%\Temp\gw2-perf004<m|n|o|p> \
### GW2_PERF_ICON_DIR=%LOCALAPPDATA%\Temp\gw2-perf004-icons npm run perf:profit-page <phase>
both-expired     exit=0  Maximum initial complete usable page : 4544 ms (§17 limit 7000 ms)
fresh            exit=0  Maximum initial complete usable page : 5145 ms (§17 limit 7000 ms)
expired-account  exit=0  Maximum initial complete usable page : 4677 ms (§17 limit 7000 ms)
expired-tp       exit=0  Maximum initial complete usable page : 4757 ms (§17 limit 7000 ms)
(each also printed "Claimed input state <phase> confirmed by the first opening's envelope."
 and "Every initial cached-page reading was within §17's limit."; logs perf004-<phase>-s5.log)

### npx vitest run src/crafting/__tests__/CraftingProfitRefreshWorkflow.spec.ts scripts/__tests__/profitTiming.spec.mjs
 Test Files  2 passed (2)                              (target/perf004-controlled-frontend-s5.log)
      Tests  73 passed (73)                            exit=0

### npm run smoke:profit:refresh      11 steps passed.  (target/perf004-smoke-refresh-s5.log) exit=0
### npm run smoke:profit              PASSED (35 steps).(target/perf004-smoke-profit-s5.log)  exit=0

### from the worktree root, JAVA_HOME=…/.jdks/openjdk-25.0.1 (backend stopped by PID first)
./mvnw -o test -Dtest='CachedCraftingProfitServiceTest,AccountRefreshCoordinatorTest,TradingPostCoverageCoordinationTest' -DfailIfNoSpecifiedTests=false
  AccountRefreshCoordinatorTest        Tests run: 7,  Failures: 0, Errors: 0, Skipped: 0
  CachedCraftingProfitServiceTest      Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
  TradingPostCoverageCoordinationTest  Tests run: 4,  Failures: 0, Errors: 0, Skipped: 0
  Tests run: 28, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS (perf004-controlled-backend-s5.log)

./mvnw -o test -Dtest=CraftingProfitApiRealDbEquivalenceIT -DfailIfNoSpecifiedTests=false
  default All (empty request body): 3205 rows equivalent between HTTP and in-process calls
  All, buying enabled at 10g, listing prices: 3205 rows equivalent between HTTP and in-process calls
  Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS (perf004-equivalence-http-s5.log)

./mvnw -o test -Dtest=CraftingProfitInputCaptureEquivalenceIT -DfailIfNoSpecifiedTests=false
  [default All] reference recorded outside version control at …\gw2-profit-capture-reference-…txt
  Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS (perf004-equivalence-capture-s5.log)
```

How each cache state was arranged, with the product's own supported routes and the configured TTLs
(`gw2.account-data.max-age-ms` = 900 000 ms; `sync.tp.TpPriceFreshness.TTL` = 10 minutes). Only one
trigger was needed: the three expired states were reached by *waiting* for the configured TTLs, and
the fresh state by the contract's own refresh having just completed. All instants UTC.

```
15:38:34  backend up (PID 35312); the machine's last refresh had been ~11 h earlier
15:45:57  phase both-expired     (both past their TTL; envelope: 4/4 STALE, 0/2984 covered)
15:47:10  phase fresh            (both refreshed by the phase above: 4/4 FRESH, 2984/2984 covered)
16:01:01  POST /api/prices/refresh {"variant":"PROFIT"}  → SUCCEEDED at 16:01:28.663 (task 67dbe98e)
16:01:38  phase expired-account  (account past 15 min since 16:01:34, quotes just refreshed)
16:12:25  phase expired-tp       (account refreshed at 16:02:30, quotes past 10 min: 0/2984 covered)
```

### Coverage

No production code changed, so no application coverage report is implicated. The measurement code
this story added is **outside the configured coverage scope**: `frontend/vite.config.ts` sets
`coverage.include: ['src/**/*.{ts,vue}']`, so `frontend/scripts/**` — every browser-smoke script and
now `profitTiming.mjs` — is not measured at all. That is a reporting-scope gap, recorded rather than
closed: widening the coverage scope is not this story's work, and padding it would be worse. What the
module does have is 42 focused tests whose bite is demonstrated by the two controls above. The
`QUALITY_METRICS.md` snapshot remains the 2026-10-03 one; nothing here updates or estimates it.

### Limitations

- **No synthetic data and no mock can substitute for these runs, and none of them is CI-runnable**:
  they need the real database, the real GW2 API and a real desktop browser (§36.4).
- Three contract paths were not produced on real data and are covered only by controlled checks:
  no-usable-cache readiness, refresh failure, and refresh timeout. Their deterministic coverage is
  named above; none of them is claimed as a timing measurement.
- The five-minute client recovery deadline is still proved only against controlled time. The longest
  real refresh observed here is 44.7 s navigation → final result.
- **The displayed-value read is a read, not a click.** It asks the rendered row for its value and its
  selection control for its enabled state, on the sample whose instant is reported; it deliberately
  does not *activate* the control, because selecting a row changes the displayed state and would
  perturb the quiescence the same measurement depends on. A control that is present and enabled but
  whose click handler is broken would pass this check; the controlled refresh smoke exercises
  selection itself.
- The sampling interval is 50 ms, so a reported boundary is that coarse; the attribution cannot place
  completion between two samples.
- **One of the three replacements could not be distinguished by content.** In `expired-account` the
  refreshed result rendered values identical to the cached one, so "the final result was rendered" has
  no observable moment of its own there; the harness says so and uses the final answer's arrival as
  the earliest provable rendering rather than asserting a distinction. The other two replacements
  changed the rendered content and are checked against that change.
- Upstream isolation is evidenced by **0 browser requests to ArenaNet** and by every image being
  served from the backend's own icon route; the CONNECT-tunnel count of
  `scripts/upstream-proxy.mjs` was **not** instrumented in this session
  (`GW2_PERF_UPSTREAM_LOG` unset, so the harness printed `null`).
- The recorder's `/api/crafting/profit` figure ends at the backend's **response headers**; body
  transfer, parsing and rendering are inside each opening's total but not inside that column. The
  response body size was not captured in this session.
- The backend's own task instants and this harness's navigation-relative clock are different clocks
  and are never subtracted from one another: "backend execution" comes only from
  `submittedAt`/`startedAt`/`finishedAt`, and "admitted → terminal" only from the harness's own
  timestamps on the answers it observed.
- Repeat in-application navigation re-renders client-held rows and issues no calculation, so it
  neither re-checks freshness nor is comparable to a page opening; it is labelled as such everywhere.
- Phase 5 closure, and the disposition of every finding above, remain the planner's.

## Follow-up Findings

None.

## Blockers

None.
