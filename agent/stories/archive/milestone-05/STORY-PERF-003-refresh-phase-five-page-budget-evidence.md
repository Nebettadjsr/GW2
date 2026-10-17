## Story ID

STORY-PERF-003

## Title

Refresh Phase 5 complete-page performance evidence after calculation changes

## Status

DONE

## Milestone

milestone-05

## Goal

Measure the current integrated Crafting Profit browser page on the real user database against the existing 7-second budget, closing the current-revision measurement gap identified by the Phase 5 health review.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md, supplied Phase 5 performance exit criterion.
- docs/TARGET_ARCHITECTURE.md sections 17 and 21.
- docs/TEST_STRATEGY.md sections 34 and 36.
- docs/QUALITY_METRICS.md, Test coverage KPI and Establishing the clean baseline.
- agent/stories/STORY-QUALITY-005-phase-five-completion-review.md, Result sections 1(a), 5 F1 and 7.
- agent/stories/STORY-PERF-002-phase-five-crafting-profit-page-budget.md, measurement procedure and historical Result.

## Context

The health review reports that the earlier measurement predates changes to the calculation and request paths. This establishes missing current verification, not a proven regression. Refresh the evidence using the existing performance harness and a fresh production bundle; do not commission speculative optimization.

This independent story measures the already-defined case with fresh account data. UD-014 separately asks whether automatic account-refresh waiting is included in the budget; this story cannot settle or verify that additional case. UD-013 separately asks about explicit user acceptance; measurement does not supply that answer or close the milestone.

F1's missing historical before/after calculation comparison must remain visible. Comparing elapsed times or changed account data cannot establish result equivalence across the earlier maintainer changes. No new calculation change is authorized here.

## Acceptance Criteria

1. Identify the measured backend/frontend revision and any relevant uncommitted product changes; build and serve the current production frontend. Record hardware, JVM/browser/database context, effective settings, relevant data counts and cache states without credentials or private account contents.
2. Measure the default All scope on the real user PostgreSQL database, including first opening after backend startup and repeat openings, across cold browser/cold application icon store, cold browser/warm store and warm browser/warm store. Include the complete requested result set as in STORY-PERF-002; do not substitute a filtered first-row timing.
3. Time navigation/request initiation through completed backend calculation, persistence, transport and usable rendered table/controls. Detect completion by quiescence including row identity, exclude the settling window, and verify a real displayed-value read and displayed-row count matching the requested complete result set.
4. Record individual elapsed times, each cache-state maximum, overall maximum and major-stage attribution. State explicitly whether every measured opening satisfies at most 7000 ms. A breach or unavailable environment is a recorded finding/blocker, not permission to claim compliance or optimize production code within this story.
5. Establish and record that account data is fresh for this measured case using existing supported configuration/workflows. Report any automatic refresh or refresh wait actually observed. Do not silently exclude such a wait from a reported end-to-end time, change freshness behavior, or claim coverage of the unresolved stale-account case.
6. Record limitations, whether an equivalent historical before/after result reference exists, and outstanding/received user acceptance. Do not claim retrospective calculation equivalence without matching inputs and evidence. Return the current measurements and remaining gaps for planner assessment under UD-013/UD-014.

## Required Tests

- Run the explicit local real-user-database browser measurement with `npm run perf:profit-page <phase>` for the three cache states, following docs/TEST_STRATEGY.md section 34 and the existing harness procedure. CI, mocked smoke checks and synthetic data cannot substitute for this check.
- Verify full-result completion, displayed-value readability and end-to-end timing as described above. No new implementation tests or broad local regression suite are required for a measurement-only story.

## Constraints

- Measurement and evidence only; no production calculation, workflow, build/CI or agent-runtime changes.
- Do not reduce, replace or mutate real account data for a faster result; isolate fixture-based checks from the user database. Use disposable browser/cache state where appropriate without deleting the user's cache.
- Preserve existing domain calculations, acquisition settings, ownership and limits. Report a suspected defect through the normal findings/bug process rather than changing behavior here.
- No arbitrary coverage expansion or exclusions. Review affected coverage evidence only if verification tooling changes; missing clean CI coverage remains unknown. No coverage target applies to an unchanged production implementation.
- No milestone closure, acceptance-policy decision or stale-account-budget assumption.

## Dependencies

- STORY-PERF-002
- STORY-QUALITY-005

## Definition of Done

Current-revision real-data measurements, reproducible context, completion checks, budget assessment and limitations are recorded in Result. Any failure, missing access, historical-equivalence gap or outstanding acceptance is explicit. Review relevant coverage reports and unresolved quality gaps proportionally if tooling changes; no full local regression run is required. Milestone closure remains a subsequent planning decision.

## Result

Measured on the real user PostgreSQL database at revision `56cc3b7`. **Over the 15 openings of the
three required cache states, the maximum navigation → complete page was 4 128 ms — inside §33's
7 000 ms budget (59 % of it), and every single opening was within the budget.** No source file was
changed by this story; the work was the measurement itself and this record.

One further state, measured because §34 forbids reporting only the favourable cache state, **does
breach the budget: with Trading Post prices older than their 10-minute TTL, the first opening took
17 206 ms** (F001). That state is outside the three cache states this story was asked to measure, so
it is reported as an observation for planner disposition, not as a verdict on the defined case and
not as licence to change production code here.

### Commands run

From `frontend/`, one invocation per phase, each capture redirected whole
(`> target/perf003-phase<n>.log 2>&1; echo "exit=$?" >> …`):

- `npm run perf:profit-page cold-browser-cold-app` — exit 0
- `npm run perf:profit-page cold-browser-warm-app` — exit 0
- `npm run perf:profit-page warm-browser-warm-app` — exit 0
- `npm run perf:profit-page warm-browser-warm-app` once more at 18:15:32, after the Trading Post
  price TTL had expired — exit 0, and the harness's own verdict line reports the breach

Environment passed to each: `GW2_PERF_PAGE_URL=http://127.0.0.1:5179`,
`GW2_PERF_RECORDER_PORT=8199`, `GW2_PERF_BACKEND_ORIGIN=http://127.0.0.1:8091`,
`GW2_PERF_PROFILE_DIR=%LOCALAPPDATA%\Temp\gw2-perf003a` (phase 1) or `…\gw2-perf003b`
(phases 2, 3 and the supplementary run), `GW2_PERF_ICON_DIR=%LOCALAPPDATA%\Temp\gw2-perf003-icons`.
The harness (`frontend/scripts/profit-page-perf.mjs`) was **not modified**.

### How it was measured

`frontend/scripts/profit-page-perf.mjs`, unmodified, the same §34 harness `STORY-WEB-010` established
and `STORY-PERF-002` used. The timer starts at the navigation action — `goto` for a first opening,
`reload` for a repeat, the navigation click for an in-application reopening — and ends at the **last
observed change** of the displayed page, detected by quiescence over a 50 ms sample of: displayed row
count, the first and last row's **identity** (`.recipe-ids`) together with its displayed total-profit
and craftable-count text, the summary and effective-settings lines, the scope line, the loading/error
notices, every icon's state, and the number of calculations the page has issued. The 3 s settle
window is excluded from the reported elapsed time, and a page counts as complete only once no
in-viewport image is still loading.

### Run context

- **Revision `56cc3b7`** ("finalized STORY-QUALITY-005 after CI 795cda02d4d2"). `git status` was
  taken before the build and again after the last phase: in both, **no product file was modified or
  uncommitted** — the only entries are `agent/` workflow documents (`CURRENT_STORY.md`,
  `PROJECT_STATE.md`, `BACKLOG.md`, `logs/`, `product-owner-requests/Request-013…`, this story file,
  `qa-plans/QA-STORY-PERF-003.json`, `user-decisions/UD-013`, `UD-014`). So the commit carrying this
  story carries no product change, and no narrow product suite is implicated by it.
- **Measured bundle**: the production build of exactly that content, `npm run build`
  (`vue-tsc --noEmit` clean): `dist/assets/index-5Z5_bIrc.js` 174.39 kB / 54.50 kB gzip,
  `dist/assets/index-13p-Cxw_.css` 38.01 kB / 6.57 kB gzip, `dist/index.html` 0.97 kB, built in
  791 ms. Served by `npx vite preview --host 127.0.0.1 --port 5179 --strictPort`; the served
  `index.html` was read back and references exactly those two asset names, so the measured page is
  the bundle just built and not a stale `dist`.
- **Topology**: Chrome 154.0.8037.93 → `vite preview` on `127.0.0.1:5179` → recording proxy on
  `127.0.0.1:8199` → backend (`./mvnw -o spring-boot:run`, `GW2_API_PORT=8091`, PID 12476, started
  18:09:42.531+02:00) → the developer's real local PostgreSQL database.
  *Positive identity of what answered*, per `tasks/lessons.md`: while the recorder was deliberately
  not yet running, `127.0.0.1:5179/api/…` answered **502** although the backend on 8091 was up and
  would have answered 404 — so the preview's `/api` really pointed at the recorder; and the recorder
  then recorded 532 / 721 / 222 backend requests across the three phases, so the measured traffic
  really traversed it.
- **Host**: 6× Intel i5-9600K @ 3.70 GHz, 64 GB RAM, Windows 10 Pro for Workstations 10.0.19045;
  OpenJDK 25.0.1+8-27 (`~/.jdks/openjdk-25.0.1`); node v22.13.1; Chrome 154.0.8037.93; viewport
  1440×900.
- **Isolation**: the backend ran with `ICON_CACHE_DIR` pointed at a disposable
  `%LOCALAPPDATA%\Temp\gw2-perf003-icons`; the user's own `~/.nebet-gw2-tool/icons` (**1 967
  entries**) was neither read nor written, and the user's browser profile was never touched — each
  cold phase used a fresh, previously unused disposable profile directory.
- **Scope and settings**, echoed identically by the backend in every opening and by the page's own
  summary line (`· scope ALL`): **scope ALL** (the default) · `useOwnMats true` · `allowBuying false`
  · `maxBuyCopper 10000` (1 g 0 s 0 c) · `listingSell false` (instant sell) · `listingBuy false`
  (instant buy) · `allowDailyCrafts false`.
- **Data scale** (from the backend's own response): **rowCount 3 205** for scope ALL, of which
  **204 match the default display filters**; response body **2 097 452 bytes**; **3 190** of 3 205
  rows carry an `iconUrl`, **15** do not — which is exactly what the page reports (3 190 icons with a
  URL, 15 without metadata). For comparison, `STORY-PERF-002` measured 3 176 rows / 1 728 855 bytes /
  179 matching, so the current measurement covers a **larger** result set than the historical one.
- **Cache states**, one phase per invocation: cold browser / cold application icon store (**0**
  entries, and a freshly started backend process that had never run a calculation), cold browser /
  warm store (**476** entries), warm browser / warm store (**628** entries). Trading Post prices were
  **fresh** in all three (see F001 and "Stage attribution").
- **Scheduled global refresh**: the measured process ran with
  `GW2_GLOBAL_REFRESH_INITIAL_DELAY_MS=3600000`, so the 6-hourly scheduled *administrative* global
  data refresh (`GlobalDataRefreshScheduler`, default initial delay 60 s) could not overlap the
  phases. It is not excluded from anything the budget covers — it is a background admin job, not part
  of opening the page — and it had already run to completion minutes earlier in this same session on
  this same data (icon metadata: 14 308 selected, 7 rows written, 7 created, 40 not returned upstream;
  tradeable TP ids 27 997; global recipes unchanged, therefore no crafting-graph rebuild, and
  `crafting_graph_cache.json` is untouched in `git status`). The single profit call taken *while* it
  was still running cost **19.6 s** and is reported below as exactly that — a contaminated
  backend-only reading, never used as a page measurement.

### Account data freshness (AC5)

Account data was **not** fresh when the backend was restarted, and the product's automatic refresh is
what made that visible — so this is observed behaviour, not an assumption:

| Observation | Evidence |
|---|---|
| First contact reported all four required sources stale | `POST /api/crafting/profit` → `503 {"error":"ACCOUNT_DATA_STALE","staleSources":["CHARACTERS","ACCOUNT_RECIPES","BANK","MATERIALS"]}` |
| Automatic refresh #1 (CHARACTERS), submitted by the interceptor itself | task `5b0560ac…`, `submittedAt 16:03:47.020Z` → `finishedAt 16:04:20.427Z` = **33.407 s** |
| Automatic refresh #2 (BANK, ACCOUNT_RECIPES, MATERIALS) | task `70e96134…`, `16:04:36.986Z` → `16:04:41.978Z` = **4.992 s** |
| Freshness for the measured phases, established through the supported route `POST /api/sync/account/crafting-profit` (bank, materials, recipes, characters) | task `58a27d74…`, `16:10:09.708Z` → `16:10:49.800Z` = **40.092 s**, i.e. 18:10:49 local |

The policy window is `gw2.account-data.max-age-ms` = 900 000 ms, so that establishes freshness until
18:25:49 local. **The three phases ran 18:10:50 → 18:13:27 local, entirely inside the window**, and
the supplementary run finished at 18:16:16, also inside it. Evidence that no refresh wait sat inside
any measured opening: every opening issued **exactly one** calculation and the recorder holds, for it,
a real `/api/crafting/profit` answer with a recorded time to its response headers (a stale answer is a
503 carrying no rows, which could
not have passed the displayed-value read); all 15 openings passed that read; 0 uncaught page errors;
and the measured process's log contains no synchronization output at all between 18:10:50 and
18:16:16 other than the per-request price check. Freshness is therefore established from the
policy's own timestamps and the absence of a refresh, **not inferred from elapsed time**.

No claim is made about the stale-account case. The 33.4 s and 5.0 s figures above are recorded as
observations of what an automatic refresh actually costs; whether such a wait belongs inside the
7 000 ms budget is **UD-014**, which this measurement does not settle.

### Measurements — the three required cache states

The `/api/crafting/profit` column is the recording proxy's figure, which ends at the backend's
**response headers**; the 2.10 MB body transfer, parsing and rendering happen after it and are inside
the navigation → complete total but not inside that column (see "Stage attribution").

| Phase | Opening | navigation → complete | rows shown / requested | calcs | `/api/crafting/profit` → response headers | backend requests |
|---|---|---|---|---|---|---|
| cold browser / cold app store (0 icons) | first, document navigation | **3 712 ms** (first rows 2 792) | 204 / 3 205 | 1 | 2 455 ms | 21 (19 images) |
| | repeat, document reload | **2 083 ms** | 204 / 3 205 | 1 | 1 778 ms | 102 (100 images) |
| | repeat, in-application navigation | 71 ms | 204 / 3 205 | **0** | — | 0 |
| | complete set, document navigation | **3 302 ms** (first rows 2 871) | **3 205 / 3 205** | 1 | 1 894 ms | 6 (4 images) |
| | complete set, repeat reload | **3 785 ms** | **3 205 / 3 205** | 1 | 1 668 ms | 161 (159 images) |
| cold browser / warm app store (476 icons) | first, document navigation | **2 812 ms** (first rows 2 747) | 204 / 3 205 | 1 | 1 742 ms | 21 (19 images) |
| | repeat, document reload | **2 088 ms** | 204 / 3 205 | 1 | 1 754 ms | 187 (185 images) |
| | repeat, in-application navigation | 133 ms | 204 / 3 205 | 0 | — | 0 |
| | complete set, document navigation | **2 793 ms** (first rows 2 707) | **3 205 / 3 205** | 1 | 1 707 ms | 6 (4 images) |
| | complete set, repeat reload | **4 128 ms** | **3 205 / 3 205** | 1 | 1 643 ms | 350 (348 images) |
| warm browser / warm app store (628 icons) | first, document navigation | **2 043 ms** | 204 / 3 205 | 1 | 1 750 ms | 2 (0 images) |
| | repeat, document reload | **2 201 ms** | 204 / 3 205 | 1 | 1 892 ms | 2 |
| | repeat, in-application navigation | 81 ms | 204 / 3 205 | 0 | — | 0 |
| | complete set, document navigation | **2 848 ms** | **3 205 / 3 205** | 1 | 1 849 ms | 2 |
| | complete set, repeat reload | **4 069 ms** | **3 205 / 3 205** | 1 | 1 755 ms | 215 (213 images) |

- **Per-cache-state maxima: 3 785 / 4 128 / 4 069 ms. Overall maximum 4 128 ms.**
- **Explicit budget assessment: every one of the 15 openings is at most 7 000 ms.** The largest is
  4 128 ms = 59 % of the budget, with ~2.9 s of headroom. It is a complete-requested-set opening on a
  *cold*-browser phase, not a favourable warm run.
- In all three phases: **0 browser requests to ArenaNet, 0 off the page's own origin, 0 images off the
  backend's icon route, 0 uncaught page errors**, and **0 visible images still loading** at any moment
  a page was called complete.
- Against the historical figure, the measured maximum moved 4 411 ms → 4 128 ms while the result set
  grew from 3 176 to 3 205 rows and from 1.73 MB to 2.10 MB. **No regression is observed in this
  case.** This compares elapsed times only; it establishes nothing about calculation equivalence (see
  "Historical equivalence").

### Supplementary state: Trading Post prices past their TTL — budget breached

Every `POST /api/crafting/profit` performs an in-request Trading Post price refresh
(`web/CraftingProfitApiController` → `application.TradingPostPriceRefreshService.refreshForProfit()`,
relevance collected by `sync.tp.relevance.CraftingProfitItemCollector` under
`sync.tp.TpPriceFreshness.TTL` = 10 minutes). In all 12 calculations of the three required phases that
step found nothing to do — the measured process logged `TP price items to fetch: 0` twelve times, once
per calculation — because the global refresh earlier in the session had just refreshed those prices.

Reporting only that state would be reporting only the favourable cache state, which §34 forbids, so
the same warm/warm phase was run once more at 18:15:32, after the 10-minute TTL had expired and with
nothing else running:

| Opening | navigation → complete | rows shown / requested | `/api/crafting/profit` → response headers | upstream work inside the request |
|---|---|---|---|---|
| first, document navigation | **17 206 ms — ABOVE the 7 000 ms budget** | 198 / 3 205 | **16 935 ms** | `TP price items to fetch: 2 984`, fetched 2 984 / 2 984, "TP prices synced" |
| repeat, document reload | 1 865 ms | 198 / 3 205 | 1 589 ms | `TP price items to fetch: 0` |
| repeat, in-application navigation | 78 ms | 198 / 3 205 | — | — |
| complete set, document navigation | 2 675 ms | 3 205 / 3 205 | 1 721 ms | `TP price items to fetch: 0` |
| complete set, repeat reload | 3 711 ms | 3 205 / 3 205 | 1 750 ms | `TP price items to fetch: 0` |

So the breach is **2.5× the budget**, it is attributable (16 935 of the 17 206 ms elapse before the
backend's response headers even arrive, because it fetched 2 984 item prices from the GW2 API inside
the request; the remaining ~271 ms covers the body transfer, parsing and rendering of a 198-row
opening view), and it is self-clearing: the same page
opened again 20 seconds later took 1 865 ms. The displayed matching-row count moved 204 → 198 across
that refresh, which independently confirms real new price data entered the calculation. Recorded as
**F001** for planner disposition; no production code was changed here.

### Stage attribution (nothing excluded from the end-to-end total)

- **Backend request receipt → response headers**, which is exactly what the recording proxy measures:
  `frontend/scripts/recordingProxy.mjs` stamps `started` when the request reaches the proxy and
  computes `ms` inside the `http.request` response callback — that callback fires when the backend's
  status line and headers arrive, and the response body is only piped onward afterwards (line 47), so
  **body transfer is not inside this figure**. It covers waiting for the backend to accept the
  request, run the calculation, persist, and begin answering: **1 643–2 455 ms** over the 12 measured
  calculations, i.e. 40–86 % of each opening's total (minimum 1 643 / 4 128 ms, maximum
  1 892 / 2 201 ms). The first calculation on the freshly started
  process is the slowest of them (2 455 ms); steady state is 1 643–1 892 ms. Whether the backend
  streams or buffers its 2.10 MB body before the headers go out is **not** resolved by this
  measurement, so no claim is made that serialization is wholly inside or wholly outside it.
- **Everything after those response headers** is the remainder of each opening's total, and it is the
  part body transfer lives in: transferring the **2 097 452-byte** JSON response over the loopback
  chain, `JSON.parse`, Vue rendering of the table and controls, plus document and bundle load on a
  first opening. That remainder is **293–1 257 ms** for the 204-row opening view (the upper end being
  the cold-browser first opening, which also downloads the bundle), and **999–2 485 ms** when the same
  continuous timer additionally covers clearing every display filter, switching "Show all" on and
  rendering all 3 205 rows into the DOM. Transfer, parse and render are *not* separated from one
  another within that remainder — the harness takes no intermediate mark between the response headers
  and the page's last observed change — so the remainder is reported as one bounded segment rather
  than attributed to any single one of them. The render step is nonetheless why the complete-set
  openings are the maxima (same response, same headers latency, more DOM), which is why they are
  reported as the budget figure.
- **Icon delivery** runs on the backend's own route inside these timers (0–348 backend image requests
  per opening, all 200s, no 304s on these cold/warming stores); offscreen icons that native lazy
  loading defers are counted and reported (up to 2 982 unfinished, 2 764 requests still in flight at
  the moment of completion) and are never waited out nor used to call a page complete.
- **In-request Trading Post price check**: 0 items to fetch in all 12 measured calculations; 2 984
  items and 16.9 s when it has work (above).
- **In-application reopening is reported separately and is not the repeat figure**: this client keeps
  the calculated rows, so renavigating re-renders them with **0 calculations and 0 backend requests**
  (71 / 133 / 81 ms). The comparable repeat is the document reload, which does recalculate.
- **Not attributed by exclusion**: no stage above was subtracted from any reported total. The reported
  total is always navigation action → last observed change of the page.

### Completeness, row identity and a real displayed-value read (AC3)

Each of the 15 openings only counted as complete after quiescence over a sample that **includes the
first and last row's identity** (`.recipe-ids`) and their displayed values, so a reload recomputing
identical numbers still moves the sample; after that, the harness read the first row's
`[data-test="total-profit"]` text and required it to be non-empty, and asserted the row's
`[data-test="select-row"]` control was enabled — a loading placeholder or a disabled table cannot
pass. The 3 s settle window is excluded from every reported time. Each complete-set opening
additionally asserts that the number of rows **in the DOM equals the backend's own `rowCount`**
(3 205 / 3 205, six times over the four runs) or fails, so no reported time covers a truncated
result. The page's summary line in every opening read
`Showing … of … matching recipes · 3205 calculated for this scope · scope ALL`, so results were not
reduced to make the benchmark faster.

### Historical equivalence — the gap stays open (AC6)

**No equivalent historical before/after calculation-result reference exists, and none can be
reconstructed retrospectively.** `STORY-PERF-002` recorded aggregates only (3 176 rows,
1 728 855 bytes, 179 matching) and no per-row results; the pre-`f0e360e` code is no longer running
anywhere; and the inputs have moved since in ways this story could not hold still — account data was
re-synced, Trading Post prices were refetched twice, and the row count itself is now 3 205. §34's
real-data equivalence check for the maintainer's calculation changes therefore **cannot** be supplied
after the fact, and **nothing in this Result claims calculation equivalence**: the timing comparison
above is a comparison of elapsed times under different inputs, which is not evidence about computed
values. `STORY-QUALITY-005` F1's missing-comparison half remains open; this story closes only its
missing-current-measurement half.

### Acceptance and the open decisions (AC6)

- **Explicit user acceptance is OUTSTANDING.** §34 keeps user/Product Owner confirmation as a
  supplement that measured compliance cannot replace, and it has not been requested or received for
  these figures. Whether closure needs it at all is **UD-013 (OPEN)**; this Result does not answer it.
- **UD-014 (OPEN)** — whether an automatic account-refresh wait falls inside the budget — is
  untouched. This story measured the fresh-account case only, and records the 33.4 s / 5.0 s refresh
  observations without deciding the question.
- **Milestone closure is not declared here** and remains a planning decision.

### Limitations, recorded rather than worked around

- One machine, one revision, one account's database, one browser, at one market snapshot
  (11.10.2026, 18:09–18:16 local). No multi-environment or multi-account claim.
- The three required phases all ran with Trading Post prices inside their 10-minute TTL. That is a
  favourable cache state, it is named as such, and the other state is measured above (F001) rather
  than left to inference.
- The application icon store was an isolated disposable directory, so the cold phase is a **colder**
  state than the user's real installation (1 967 stored entries); these figures are conservative.
- The scheduled administrative global refresh was pushed outside the measured window by configuration
  (see Run context). The contaminated reading it produced — a first profit call of **19.6 s** while it
  was fetching 2 984 TP prices and repairing 14 308 icon metadata rows concurrently — is reported as a
  backend-only observation and is **not** used as a page measurement; its price-fetch component is the
  same effect F001 isolates cleanly.
- Upstream CONNECT tunnel counting was not configured (no upstream proxy), so every phase reports
  `null` there, exactly as in `STORY-PERF-002`. Upstream image behaviour was `STORY-WEB-010`'s
  subject.
- The harness's `effective settings` line reads `null` in every phase: the
  `[data-test="effective-settings"]` hook it samples no longer exists in `CraftingProfitScreen.vue`
  (removed by `1da5b94`). The settings evidence above therefore comes from the backend's own echoed
  `settings` object and the page's `· scope ALL`, both of which were read in this run (**F003**).
- The row-selection resolution detail is still **untimed** — unchanged since `STORY-WEB-010`; this
  budget covers opening the page, not loading a selected row's tree.
- Measured against the current runtime (JVM backend + `vite preview`). The containerized target
  deployment does not exist yet, so nothing here predicts it.
- Per `tasks/lessons.md`: ports 5179 / 8091 / 8199 were probed on **both** address families and found
  free before use, and confirmed released (`000` on both) afterwards. Stopping the background wrappers
  did **not** release either server, so each was stopped by the PID it was started as — backend JVM
  12476, `vite` 24816 and its `npx` parent — identified from a listing whose command lines and
  creation times were read first; two foreign `node.exe` processes in the same listing (a Codex CLI
  from 08.10, Adobe Creative Cloud from 09.10) were recognized as not mine and left alone. No process
  the session did not start was stopped. The user's own backend and dev servers were not running at
  any point in this session, so nothing of theirs was displaced.
- Windows again refused to delete the closed Chrome profile directories (EPERM on every `Default/*`
  file), so `%LOCALAPPDATA%\Temp\gw2-perf003a` and `…\gw2-perf003b` remain on disk; each cold phase
  used a **new, previously unused** directory rather than a re-emptied one, which is equally cold.
  The disposable icon store `…\gw2-perf003-icons` was removed successfully.
- No documentation owned by another file changed, because nothing this story did changed behaviour,
  architecture, domain rules or test methodology. No coverage target applies: the production
  implementation and the verification tooling are both unchanged, so no coverage report was
  re-measured, and `STORY-QUALITY-005`'s record of missing clean CI coverage is unaffected.

### Captured output

The four captures in full, as produced by the commands above.

**`npm run perf:profit-page cold-browser-cold-app`**

```

> gw2-tool-frontend@0.1.0 perf:profit-page
> node scripts/profit-page-perf.mjs cold-browser-cold-app

=== STORY-WEB-010 §33 browser-boundary measurement ===
Phase        : cold-browser-cold-app
Revision     : 56cc3b7
Page         : http://127.0.0.1:5179 (server -> http://127.0.0.1:8199 -> http://127.0.0.1:8091)
Browser      : C:/Program Files/Google/Chrome/Application/chrome.exe
Profile      : C:\Users\Administrator\AppData\Local\Temp\gw2-perf003a
Icon storage : C:\Users\Administrator\AppData\Local\Temp\gw2-perf003-icons — 0 entries before this phase
Upstream log : (not counted) — null tunnels before this phase
Host         : 6x Intel(R) Core(TM) i5-9600K CPU @ 3.70GHz, 64 GB RAM, node v22.13.1
Viewport     : 1440x900

Browser version: 154.0.8037.93


  FIRST opening (document navigation, opening view)
    navigation -> complete page : 3712 ms (within §33's 7000 ms)
    first rows displayed        : 2792 ms
    rows displayed / requested  : 204 / 3205 (opening view, 250-row display limit)
    calculations issued         : 1
    backend requests            : 21 (profit 2455 ms; images 19 = 19x200, 0x304, 0x other)
    browser requests            : 24 (19 images, 0 still in flight at completion)
    upstream CONNECT tunnels    : null
    icons on screen             : 204 with a URL (19 decoded, 0 still pending in view, 185 offscreen and unfinished — deferred by lazy loading or still in flight), 0 without metadata, 0 failed
    stored icon entries after   : 18
    summary line                : Showing 204 of 204 matching recipes · 3205 calculated for this scope · scope ALL
    effective settings          : null

  REPEAT #1 (document reload, fresh calculation, opening view)
    navigation -> complete page : 2083 ms (within §33's 7000 ms)
    first rows displayed        : 2083 ms
    rows displayed / requested  : 204 / 3205 (opening view, 250-row display limit)
    calculations issued         : 1
    backend requests            : 102 (profit 1778 ms; images 100 = 100x200, 0x304, 0x other)
    browser requests            : 209 (204 images, 85 still in flight at completion)
    upstream CONNECT tunnels    : null
    icons on screen             : 204 with a URL (19 decoded, 0 still pending in view, 185 offscreen and unfinished — deferred by lazy loading or still in flight), 0 without metadata, 0 failed
    stored icon entries after   : 111
    summary line                : Showing 204 of 204 matching recipes · 3205 calculated for this scope · scope ALL
    effective settings          : null

  REPEAT #2 (in-application navigation, client-held rows, opening view)
    navigation -> complete page : 71 ms (within §33's 7000 ms)
    first rows displayed        : 71 ms
    rows displayed / requested  : 204 / 3205 (opening view, 250-row display limit)
    calculations issued         : 0
    backend requests            : 0 (profit  ms; images 0 = 0x200, 0x304, 0x other)
    browser requests            : 0 (0 images, 0 still in flight at completion)
    upstream CONNECT tunnels    : null
    icons on screen             : 204 with a URL (19 decoded, 0 still pending in view, 185 offscreen and unfinished — deferred by lazy loading or still in flight), 0 without metadata, 0 failed
    stored icon entries after   : 336
    summary line                : Showing 204 of 204 matching recipes · 3205 calculated for this scope · scope ALL
    effective settings          : null

  COMPLETE requested set (document navigation + full-set request)
    navigation -> complete page : 3302 ms (within §33's 7000 ms)
    first rows displayed        : 2871 ms
    rows displayed / requested  : 3205 / 3205 (complete requested set)
    calculations issued         : 1
    backend requests            : 6 (profit 1894 ms; images 4 = 4x200, 0x304, 0x other)
    browser requests            : 213 (208 images, 0 still in flight at completion)
    upstream CONNECT tunnels    : null
    icons on screen             : 3190 with a URL (208 decoded, 0 still pending in view, 2982 offscreen and unfinished — deferred by lazy loading or still in flight), 15 without metadata, 0 failed
    stored icon entries after   : 339
    summary line                : Showing 3205 of 3205 matching recipes · 3205 calculated for this scope · scope ALL
    effective settings          : null

  COMPLETE requested set (repeat document reload + full-set request)
    navigation -> complete page : 3785 ms (within §33's 7000 ms)
    first rows displayed        : 3785 ms
    rows displayed / requested  : 3205 / 3205 (complete requested set)
    calculations issued         : 1
    backend requests            : 161 (profit 1668 ms; images 159 = 159x200, 0x304, 0x other)
    browser requests            : 3136 (3131 images, 2764 still in flight at completion)
    upstream CONNECT tunnels    : null
    icons on screen             : 3190 with a URL (208 decoded, 0 still pending in view, 2982 offscreen and unfinished — deferred by lazy loading or still in flight), 15 without metadata, 0 failed
    stored icon entries after   : 470
    summary line                : Showing 3205 of 3205 matching recipes · 3205 calculated for this scope · scope ALL
    effective settings          : null

=== cold-browser-cold-app summary ===
Maximum navigation -> complete page : 3785 ms (§33 limit 7000 ms)
Maximum for the complete requested set: 3785 ms
Visible images left pending at completion: 0 (must be 0)
Browser requests: 3739 total, 3718 images, 0 to ArenaNet, 0 off this origin, 0 images off the icon route
Backend requests recorded: 532 (523 on the icon route)
Upstream CONNECT tunnels this phase: null
Stored icon entries after this phase: 470
Calculations issued in total: 4
Requested row count last reported by the backend: 3205

cold-browser-cold-app: measurement complete. Every reading was within §33’s limit.
exit=0
```

**`npm run perf:profit-page cold-browser-warm-app`**

```

> gw2-tool-frontend@0.1.0 perf:profit-page
> node scripts/profit-page-perf.mjs cold-browser-warm-app

=== STORY-WEB-010 §33 browser-boundary measurement ===
Phase        : cold-browser-warm-app
Revision     : 56cc3b7
Page         : http://127.0.0.1:5179 (server -> http://127.0.0.1:8199 -> http://127.0.0.1:8091)
Browser      : C:/Program Files/Google/Chrome/Application/chrome.exe
Profile      : C:\Users\Administrator\AppData\Local\Temp\gw2-perf003b
Icon storage : C:\Users\Administrator\AppData\Local\Temp\gw2-perf003-icons — 476 entries before this phase
Upstream log : (not counted) — null tunnels before this phase
Host         : 6x Intel(R) Core(TM) i5-9600K CPU @ 3.70GHz, 64 GB RAM, node v22.13.1
Viewport     : 1440x900

Browser version: 154.0.8037.93


  FIRST opening (document navigation, opening view)
    navigation -> complete page : 2812 ms (within §33's 7000 ms)
    first rows displayed        : 2747 ms
    rows displayed / requested  : 204 / 3205 (opening view, 250-row display limit)
    calculations issued         : 1
    backend requests            : 21 (profit 1742 ms; images 19 = 19x200, 0x304, 0x other)
    browser requests            : 24 (19 images, 0 still in flight at completion)
    upstream CONNECT tunnels    : null
    icons on screen             : 204 with a URL (19 decoded, 0 still pending in view, 185 offscreen and unfinished — deferred by lazy loading or still in flight), 0 without metadata, 0 failed
    stored icon entries after   : 476
    summary line                : Showing 204 of 204 matching recipes · 3205 calculated for this scope · scope ALL
    effective settings          : null

  REPEAT #1 (document reload, fresh calculation, opening view)
    navigation -> complete page : 2088 ms (within §33's 7000 ms)
    first rows displayed        : 2088 ms
    rows displayed / requested  : 204 / 3205 (opening view, 250-row display limit)
    calculations issued         : 1
    backend requests            : 187 (profit 1754 ms; images 185 = 185x200, 0x304, 0x other)
    browser requests            : 209 (204 images, 0 still in flight at completion)
    upstream CONNECT tunnels    : null
    icons on screen             : 204 with a URL (19 decoded, 0 still pending in view, 185 offscreen and unfinished — deferred by lazy loading or still in flight), 0 without metadata, 0 failed
    stored icon entries after   : 476
    summary line                : Showing 204 of 204 matching recipes · 3205 calculated for this scope · scope ALL
    effective settings          : null

  REPEAT #2 (in-application navigation, client-held rows, opening view)
    navigation -> complete page : 133 ms (within §33's 7000 ms)
    first rows displayed        : 133 ms
    rows displayed / requested  : 204 / 3205 (opening view, 250-row display limit)
    calculations issued         : 0
    backend requests            : 0 (profit  ms; images 0 = 0x200, 0x304, 0x other)
    browser requests            : 0 (0 images, 0 still in flight at completion)
    upstream CONNECT tunnels    : null
    icons on screen             : 204 with a URL (19 decoded, 0 still pending in view, 185 offscreen and unfinished — deferred by lazy loading or still in flight), 0 without metadata, 0 failed
    stored icon entries after   : 476
    summary line                : Showing 204 of 204 matching recipes · 3205 calculated for this scope · scope ALL
    effective settings          : null

  COMPLETE requested set (document navigation + full-set request)
    navigation -> complete page : 2793 ms (within §33's 7000 ms)
    first rows displayed        : 2707 ms
    rows displayed / requested  : 3205 / 3205 (complete requested set)
    calculations issued         : 1
    backend requests            : 6 (profit 1707 ms; images 4 = 4x200, 0x304, 0x other)
    browser requests            : 216 (211 images, 0 still in flight at completion)
    upstream CONNECT tunnels    : null
    icons on screen             : 3190 with a URL (211 decoded, 0 still pending in view, 2979 offscreen and unfinished — deferred by lazy loading or still in flight), 15 without metadata, 0 failed
    stored icon entries after   : 476
    summary line                : Showing 3205 of 3205 matching recipes · 3205 calculated for this scope · scope ALL
    effective settings          : null

  COMPLETE requested set (repeat document reload + full-set request)
    navigation -> complete page : 4128 ms (within §33's 7000 ms)
    first rows displayed        : 4128 ms
    rows displayed / requested  : 3205 / 3205 (complete requested set)
    calculations issued         : 1
    backend requests            : 350 (profit 1643 ms; images 348 = 348x200, 0x304, 0x other)
    browser requests            : 3136 (3131 images, 2572 still in flight at completion)
    upstream CONNECT tunnels    : null
    icons on screen             : 3190 with a URL (211 decoded, 0 still pending in view, 2979 offscreen and unfinished — deferred by lazy loading or still in flight), 15 without metadata, 0 failed
    stored icon entries after   : 622
    summary line                : Showing 3205 of 3205 matching recipes · 3205 calculated for this scope · scope ALL
    effective settings          : null

=== cold-browser-warm-app summary ===
Maximum navigation -> complete page : 4128 ms (§33 limit 7000 ms)
Maximum for the complete requested set: 4128 ms
Visible images left pending at completion: 0 (must be 0)
Browser requests: 3742 total, 3721 images, 0 to ArenaNet, 0 off this origin, 0 images off the icon route
Backend requests recorded: 721 (712 on the icon route)
Upstream CONNECT tunnels this phase: null
Stored icon entries after this phase: 622
Calculations issued in total: 4
Requested row count last reported by the backend: 3205

cold-browser-warm-app: measurement complete. Every reading was within §33’s limit.
exit=0
```

**`npm run perf:profit-page warm-browser-warm-app`**

```

> gw2-tool-frontend@0.1.0 perf:profit-page
> node scripts/profit-page-perf.mjs warm-browser-warm-app

=== STORY-WEB-010 §33 browser-boundary measurement ===
Phase        : warm-browser-warm-app
Revision     : 56cc3b7
Page         : http://127.0.0.1:5179 (server -> http://127.0.0.1:8199 -> http://127.0.0.1:8091)
Browser      : C:/Program Files/Google/Chrome/Application/chrome.exe
Profile      : C:\Users\Administrator\AppData\Local\Temp\gw2-perf003b
Icon storage : C:\Users\Administrator\AppData\Local\Temp\gw2-perf003-icons — 628 entries before this phase
Upstream log : (not counted) — null tunnels before this phase
Host         : 6x Intel(R) Core(TM) i5-9600K CPU @ 3.70GHz, 64 GB RAM, node v22.13.1
Viewport     : 1440x900

Browser version: 154.0.8037.93


  FIRST opening (document navigation, opening view)
    navigation -> complete page : 2043 ms (within §33's 7000 ms)
    first rows displayed        : 2043 ms
    rows displayed / requested  : 204 / 3205 (opening view, 250-row display limit)
    calculations issued         : 1
    backend requests            : 2 (profit 1750 ms; images 0 = 0x200, 0x304, 0x other)
    browser requests            : 24 (19 images, 0 still in flight at completion)
    upstream CONNECT tunnels    : null
    icons on screen             : 204 with a URL (19 decoded, 0 still pending in view, 185 offscreen and unfinished — deferred by lazy loading or still in flight), 0 without metadata, 0 failed
    stored icon entries after   : 628
    summary line                : Showing 204 of 204 matching recipes · 3205 calculated for this scope · scope ALL
    effective settings          : null

  REPEAT #1 (document reload, fresh calculation, opening view)
    navigation -> complete page : 2201 ms (within §33's 7000 ms)
    first rows displayed        : 2201 ms
    rows displayed / requested  : 204 / 3205 (opening view, 250-row display limit)
    calculations issued         : 1
    backend requests            : 2 (profit 1892 ms; images 0 = 0x200, 0x304, 0x other)
    browser requests            : 209 (204 images, 0 still in flight at completion)
    upstream CONNECT tunnels    : null
    icons on screen             : 204 with a URL (19 decoded, 0 still pending in view, 185 offscreen and unfinished — deferred by lazy loading or still in flight), 0 without metadata, 0 failed
    stored icon entries after   : 628
    summary line                : Showing 204 of 204 matching recipes · 3205 calculated for this scope · scope ALL
    effective settings          : null

  REPEAT #2 (in-application navigation, client-held rows, opening view)
    navigation -> complete page : 81 ms (within §33's 7000 ms)
    first rows displayed        : 81 ms
    rows displayed / requested  : 204 / 3205 (opening view, 250-row display limit)
    calculations issued         : 0
    backend requests            : 0 (profit  ms; images 0 = 0x200, 0x304, 0x other)
    browser requests            : 0 (0 images, 0 still in flight at completion)
    upstream CONNECT tunnels    : null
    icons on screen             : 204 with a URL (19 decoded, 0 still pending in view, 185 offscreen and unfinished — deferred by lazy loading or still in flight), 0 without metadata, 0 failed
    stored icon entries after   : 628
    summary line                : Showing 204 of 204 matching recipes · 3205 calculated for this scope · scope ALL
    effective settings          : null

  COMPLETE requested set (document navigation + full-set request)
    navigation -> complete page : 2848 ms (within §33's 7000 ms)
    first rows displayed        : 2848 ms
    rows displayed / requested  : 3205 / 3205 (complete requested set)
    calculations issued         : 1
    backend requests            : 2 (profit 1849 ms; images 0 = 0x200, 0x304, 0x other)
    browser requests            : 222 (217 images, 0 still in flight at completion)
    upstream CONNECT tunnels    : null
    icons on screen             : 3190 with a URL (217 decoded, 0 still pending in view, 2973 offscreen and unfinished — deferred by lazy loading or still in flight), 15 without metadata, 0 failed
    stored icon entries after   : 628
    summary line                : Showing 3205 of 3205 matching recipes · 3205 calculated for this scope · scope ALL
    effective settings          : null

  COMPLETE requested set (repeat document reload + full-set request)
    navigation -> complete page : 4069 ms (within §33's 7000 ms)
    first rows displayed        : 4069 ms
    rows displayed / requested  : 3205 / 3205 (complete requested set)
    calculations issued         : 1
    backend requests            : 215 (profit 1755 ms; images 213 = 213x200, 0x304, 0x other)
    browser requests            : 3136 (3131 images, 2352 still in flight at completion)
    upstream CONNECT tunnels    : null
    icons on screen             : 3190 with a URL (217 decoded, 0 still pending in view, 2973 offscreen and unfinished — deferred by lazy loading or still in flight), 15 without metadata, 0 failed
    stored icon entries after   : 794
    summary line                : Showing 3205 of 3205 matching recipes · 3205 calculated for this scope · scope ALL
    effective settings          : null

=== warm-browser-warm-app summary ===
Maximum navigation -> complete page : 4069 ms (§33 limit 7000 ms)
Maximum for the complete requested set: 4069 ms
Visible images left pending at completion: 0 (must be 0)
Browser requests: 3748 total, 3727 images, 0 to ArenaNet, 0 off this origin, 0 images off the icon route
Backend requests recorded: 222 (213 on the icon route)
Upstream CONNECT tunnels this phase: null
Stored icon entries after this phase: 796
Calculations issued in total: 4
Requested row count last reported by the backend: 3205

warm-browser-warm-app: measurement complete. Every reading was within §33’s limit.
exit=0
```

**`npm run perf:profit-page warm-browser-warm-app`, re-run at 18:15:32 with Trading Post prices past
their TTL (the supplementary state, F001)**

```

> gw2-tool-frontend@0.1.0 perf:profit-page
> node scripts/profit-page-perf.mjs warm-browser-warm-app

=== STORY-WEB-010 §33 browser-boundary measurement ===
Phase        : warm-browser-warm-app
Revision     : 56cc3b7
Page         : http://127.0.0.1:5179 (server -> http://127.0.0.1:8199 -> http://127.0.0.1:8091)
Browser      : C:/Program Files/Google/Chrome/Application/chrome.exe
Profile      : C:\Users\Administrator\AppData\Local\Temp\gw2-perf003b
Icon storage : C:\Users\Administrator\AppData\Local\Temp\gw2-perf003-icons — 801 entries before this phase
Upstream log : (not counted) — null tunnels before this phase
Host         : 6x Intel(R) Core(TM) i5-9600K CPU @ 3.70GHz, 64 GB RAM, node v22.13.1
Viewport     : 1440x900

Browser version: 154.0.8037.93


  FIRST opening (document navigation, opening view)
    navigation -> complete page : 17206 ms (ABOVE §33's 7000 ms)
    first rows displayed        : 17206 ms
    rows displayed / requested  : 198 / 3205 (opening view, 250-row display limit)
    calculations issued         : 1
    backend requests            : 2 (profit 16935 ms; images 0 = 0x200, 0x304, 0x other)
    browser requests            : 24 (19 images, 0 still in flight at completion)
    upstream CONNECT tunnels    : null
    icons on screen             : 198 with a URL (19 decoded, 0 still pending in view, 179 offscreen and unfinished — deferred by lazy loading or still in flight), 0 without metadata, 0 failed
    stored icon entries after   : 801
    summary line                : Showing 198 of 198 matching recipes · 3205 calculated for this scope · scope ALL
    effective settings          : null

  REPEAT #1 (document reload, fresh calculation, opening view)
    navigation -> complete page : 1865 ms (within §33's 7000 ms)
    first rows displayed        : 1865 ms
    rows displayed / requested  : 198 / 3205 (opening view, 250-row display limit)
    calculations issued         : 1
    backend requests            : 3 (profit 1589 ms; images 1 = 1x200, 0x304, 0x other)
    browser requests            : 203 (198 images, 0 still in flight at completion)
    upstream CONNECT tunnels    : null
    icons on screen             : 198 with a URL (19 decoded, 0 still pending in view, 179 offscreen and unfinished — deferred by lazy loading or still in flight), 0 without metadata, 0 failed
    stored icon entries after   : 802
    summary line                : Showing 198 of 198 matching recipes · 3205 calculated for this scope · scope ALL
    effective settings          : null

  REPEAT #2 (in-application navigation, client-held rows, opening view)
    navigation -> complete page : 78 ms (within §33's 7000 ms)
    first rows displayed        : 78 ms
    rows displayed / requested  : 198 / 3205 (opening view, 250-row display limit)
    calculations issued         : 0
    backend requests            : 0 (profit  ms; images 0 = 0x200, 0x304, 0x other)
    browser requests            : 0 (0 images, 0 still in flight at completion)
    upstream CONNECT tunnels    : null
    icons on screen             : 198 with a URL (19 decoded, 0 still pending in view, 179 offscreen and unfinished — deferred by lazy loading or still in flight), 0 without metadata, 0 failed
    stored icon entries after   : 802
    summary line                : Showing 198 of 198 matching recipes · 3205 calculated for this scope · scope ALL
    effective settings          : null

  COMPLETE requested set (document navigation + full-set request)
    navigation -> complete page : 2675 ms (within §33's 7000 ms)
    first rows displayed        : 2675 ms
    rows displayed / requested  : 3205 / 3205 (complete requested set)
    calculations issued         : 1
    backend requests            : 2 (profit 1721 ms; images 0 = 0x200, 0x304, 0x other)
    browser requests            : 209 (204 images, 0 still in flight at completion)
    upstream CONNECT tunnels    : null
    icons on screen             : 3190 with a URL (204 decoded, 0 still pending in view, 2986 offscreen and unfinished — deferred by lazy loading or still in flight), 15 without metadata, 0 failed
    stored icon entries after   : 802
    summary line                : Showing 3205 of 3205 matching recipes · 3205 calculated for this scope · scope ALL
    effective settings          : null

  COMPLETE requested set (repeat document reload + full-set request)
    navigation -> complete page : 3711 ms (within §33's 7000 ms)
    first rows displayed        : 3711 ms
    rows displayed / requested  : 3205 / 3205 (complete requested set)
    calculations issued         : 1
    backend requests            : 119 (profit 1750 ms; images 117 = 117x200, 0x304, 0x other)
    browser requests            : 3136 (3131 images, 2244 still in flight at completion)
    upstream CONNECT tunnels    : null
    icons on screen             : 3190 with a URL (204 decoded, 0 still pending in view, 2986 offscreen and unfinished — deferred by lazy loading or still in flight), 15 without metadata, 0 failed
    stored icon entries after   : 904
    summary line                : Showing 3205 of 3205 matching recipes · 3205 calculated for this scope · scope ALL
    effective settings          : null

=== warm-browser-warm-app summary ===
Maximum navigation -> complete page : 17206 ms (§33 limit 7000 ms)
Maximum for the complete requested set: 3711 ms
Visible images left pending at completion: 0 (must be 0)
Browser requests: 3729 total, 3708 images, 0 to ArenaNet, 0 off this origin, 0 images off the icon route
Backend requests recorded: 127 (118 on the icon route)
Upstream CONNECT tunnels this phase: null
Stored icon entries after this phase: 906
Calculations issued in total: 4
Requested row count last reported by the backend: 3205

warm-browser-warm-app: measurement complete. At least one reading was ABOVE §33’s limit — report it for planner disposition.
exit=0
```

## Follow-up Findings

F001: every `POST /api/crafting/profit` refreshes Trading Post prices inside the request
(`web/CraftingProfitApiController` lines 72 and 106 → `TradingPostPriceRefreshService.refreshForProfit()`,
10-minute TTL in `sync/tp/TpPriceFreshness`), so the first page opening after that TTL expires carries
the whole relevant-item fetch: measured **17 206 ms page / 16 935 ms to the backend's response headers
/ 2 984 item prices fetched upstream** (the remaining ~271 ms is body transfer, parsing and rendering
of a 198-row opening view), 2.5× §33's 7 000 ms budget, self-clearing on the next opening (1 865 ms).
The three
cache states this story was asked to measure all happened to run with prices fresh. Observed, not
changed: whether the budget is meant to cover this state, and whether the refresh belongs inside the
request at all, is a product/architecture question adjacent to UD-014.

F002: `web/AccountDataFreshnessInterceptor` submits the automatic refresh with
`tasks.submitOrExisting("ACCOUNT_SYNC", …)`, so a caller can be handed a task that refreshes **fewer
sources than that caller needs**. Observed in this run: a `/api/crafting/selector-options` request
started a CHARACTERS-only sync; the next `/api/crafting/profit` reported four stale sources but was
given that same task id; after the task succeeded, the following profit request was still 503 with
`["BANK","ACCOUNT_RECIPES","MATERIALS"]`. `frontend/src/api/http.ts` waits for the advertised task and
retries exactly once (`sendJsonAttempt(path, init, false)`), so in this sequence the single retry can
still surface a stale error to the user.

F003: `frontend/scripts/profit-page-perf.mjs:147` samples `[data-test="effective-settings"]`, which
`frontend/src/crafting/CraftingProfitScreen.vue` no longer renders (removed by `1da5b94`, "Redesign
crafting discovery workflow"); the component now exposes only `[data-test="summary"]` and
`[data-test="effective-scope"]`. Every phase report's `effective settings` line therefore reads
`null`, so that part of §34's evidence line no longer resolves from the harness output alone.

## Follow-up Findings Disposition

F001: DEFERRED — agent/user-decisions/UD-015-trading-post-refresh-performance-budget-scope.md defines the expired-price budget scope before verification/remediation can be sized. The observed breach remains a Phase 5 closure question; fresh-price compliance does not dispose of it.

F002: FOLLOW-UP STORY — STORY-WEB-033

F003: DISMISSED — Missing reporting evidence, not a verified app defect. This Result's Run context and Limitations record settings from the backend echo and page scope. frontend/scripts/profit-page-perf.mjs still samples the obsolete hook, but Request-013-raise-backend-test-coverage.md prohibits cosmetic smoke reporting follow-ups while open. No separate repair is needed for the recorded measurements; future measurements must still record effective inputs under TEST_STRATEGY.md section 34.

## Blockers

None.
