## Story ID
STORY-PERF-002

## Title
Verify Phase 5 Crafting Profit page performance on the real user database

## Status

DONE

## Milestone
milestone-05

## Goal
Verify that the integrated web Crafting Profit page meets the accepted complete-page performance budget on the real user PostgreSQL database, measuring navigation through usable rendered content across backend calculation, persistence, transport, and frontend rendering.

## Authoritative Source Documents / Sections
- `docs/ROADMAP.md` ? Phase 5 exit criteria (supplied current-phase excerpt)
- `docs/TARGET_ARCHITECTURE.md` ?33
- `docs/TEST_STRATEGY.md` ?34, Real-user Crafting Profit performance acceptance

## Context
Phase 5 requires performance verification for the complete browser page, including backend, transport, and rendering, against the real user database. The accepted target is at most 7 seconds. Miniature or synthetic data, service-only timings, and first-row timing are not acceptance evidence. The final frontend integration must be in place before this measurement is meaningful.

## Acceptance Criteria
- On the real user PostgreSQL database, measure navigation/request initiation through completed calculation and usable table/control rendering, including UI-thread completion and rendering.
- Record reproducible baseline/post-change context as applicable: revision, hardware/JVM/database environment, selected scope/settings, relevant data counts, cache state, individual elapsed times, and maximum; include default All scope, first opening after startup, and repeat openings.
- Attribute major pipeline stages without excluding them from the end-to-end total; determine whether the maximum complete-page time meets the 7-second target.
- Verify the completed table answers a real displayed-value read before treating it as interactive, and record limitations or unavailable environment access honestly without claiming compliance from substitute data.
- Record measurements and any outstanding or received user acceptance in this story's Result without exposing credentials or private account contents.

## Required Tests
- Explicit real-user-database browser performance measurement following `docs/TEST_STRATEGY.md` ?34. This is a local acceptance check and is not established by CI or mocked browser smoke tests.

## Constraints
- Do not use or alter disposable fixtures as a substitute for real-database acceptance evidence.
- Do not disclose credentials or private account contents.
- Do not reduce or replace real data to make the benchmark faster.

## Dependencies
- STORY-WEB-015
- STORY-SYNC-004
- STORY-DOM-023
- STORY-DOM-024
- STORY-WEB-016

## Definition of Done
- The integrated page has been measured using the required real-database procedure, and evidence, result, limitations, and user-acceptance status are recorded here.

## Result

Measured. **Maximum navigation → complete page over all 15 measured openings: 4 411 ms, inside §33's
7 000 ms budget** on the real user PostgreSQL database. No source file was changed by this story; the
work was the measurement itself and this record.

### How it was measured

`frontend/scripts/profit-page-perf.mjs` (`npm run perf:profit-page <phase>`, the harness
`STORY-WEB-010` established for exactly this §34 procedure), unmodified. The timer starts at the
navigation action — the `goto` for a first opening, `reload` for a repeat, the navigation click for an
in-application reopening — and ends at the **last observed change** of the displayed page, detected by
quiescence: a 50 ms sample of displayed row count, the first and last row's identity and displayed
values, the summary and effective-settings lines, the loading/error notices, every icon's state, and the
number of calculations the page has issued. The 3 s settle window is excluded from the reported time,
and a page counts as complete only once no in-viewport image is still loading.

### Run context

- Revision `e2caa4a`; no product file was modified or uncommitted **while the phases ran** (`git status`
  showed only `agent/` documents). The maintainer edited `frontend/src/ecto/EctoSalvageScreen.vue`
  again at 10:53, after the last phase, so that edit is *not* in the measured bundle and is unrelated
  to the Crafting Profit page; the commit carrying this story will carry it (see F001).
  The measured bundle is the production build of exactly the `e2caa4a` content
  (`npm run build`, type-check clean, `index-HeWuSx8V.js` 178.18 kB / 56.86 kB gzip, CSS 28.15 kB /
  5.09 kB gzip), served by `vite preview`.
- Host: 6× Intel i5-9600K @ 3.70 GHz, 64 GB RAM, Windows 10; OpenJDK 25.0.1+8-27; node v22.13.1;
  Chrome 152.0.7977.84; viewport 1440×900.
- Topology: Chrome → `vite preview` on `127.0.0.1:5179` → recording proxy on `127.0.0.1:8199` →
  backend (`./mvnw -o spring-boot:run`, `GW2_API_PORT=8091`) → the developer's real local PostgreSQL
  database. **Read-only: no synchronization was triggered and no account data was written.** The
  backend ran with an isolated disposable `ICON_CACHE_DIR`; the user's own `~/.nebet-gw2-tool/icons`
  (969 entries, last modified 26.09.2026) was neither read nor written, and the user's own browser
  profile was never touched — each cold phase used a fresh disposable profile directory.
- Scope and settings, as echoed by the page in every opening: **scope ALL** (the default) ·
  own materials used · buying off · max buy 1g 0s 0c · instant sell · instant buy · daily items bought ·
  non-Trading-Post materials allowed.
- Data scale: **3 176 rows calculated** for scope ALL, of which **179 match the default display
  filters**; response body **1 728 855 bytes**; 3 163 of 3 176 rows carry an `iconUrl`, 13 do not.
- Cache states, one phase per invocation: cold browser / cold application icon store (0 entries, and a
  freshly started backend process that had never run a calculation), cold browser / warm store
  (523 entries), warm browser / warm store (680 entries).

### Measurements

| Phase | Opening | navigation → complete | rows shown / requested | calcs | backend `/api/crafting/profit` | backend requests |
|---|---|---|---|---|---|---|
| cold browser / cold app store | first, document navigation | **2 755 ms** (first rows 2 103) | 179 / 3 176 | 1 | 1 789 ms | 19 (17 images) |
| | repeat, document reload | **2 118 ms** | 179 / 3 176 | 1 | 1 783 ms | 164 (162 images) |
| | repeat, in-application navigation | 58 ms | 179 / 3 176 | **0** | — | 0 |
| | complete set, document navigation | **2 346 ms** | **3 176 / 3 176** | 1 | 1 445 ms | 2 |
| | complete set, repeat reload | **4 411 ms** | **3 176 / 3 176** | 1 | 1 954 ms | 235 (233 images) |
| cold browser / warm app store | first, document navigation | **1 935 ms** (first rows 1 866) | 179 / 3 176 | 1 | 1 595 ms | 19 (17 images) |
| | repeat, document reload | **1 900 ms** | 179 / 3 176 | 1 | 1 632 ms | 164 (162 images) |
| | repeat, in-application navigation | 249 ms | 179 / 3 176 | 0 | — | 0 |
| | complete set, document navigation | **2 369 ms** | **3 176 / 3 176** | 1 | 1 450 ms | 2 |
| | complete set, repeat reload | **4 268 ms** | **3 176 / 3 176** | 1 | 1 612 ms | 459 (457 images) |
| warm browser / warm app store | first, document navigation | **1 836 ms** | 179 / 3 176 | 1 | 1 581 ms | 2 (0 images) |
| | repeat, document reload | **1 970 ms** | 179 / 3 176 | 1 | 1 731 ms | 2 |
| | repeat, in-application navigation | 76 ms | 179 / 3 176 | 0 | — | 0 |
| | complete set, document navigation | **2 475 ms** | **3 176 / 3 176** | 1 | 1 568 ms | 2 |
| | complete set, repeat reload | **3 726 ms** | **3 176 / 3 176** | 1 | 1 480 ms | 157 (155 images) |

Per-phase maxima **4 411 / 4 268 / 3 726 ms**; overall maximum **4 411 ms**, i.e. **63 % of the 7 000 ms
budget** with ~2.6 s of headroom. The maximum is a complete-requested-set opening on the *coldest*
phase, not a favourable warm run. In all three phases: **0 browser requests to ArenaNet, 0 off the
page's own origin, 0 images off the backend's icon route, 0 uncaught page errors**, and **0 visible
images still loading** at any moment a page was called complete.

### Pipeline attribution (nothing excluded from the end-to-end total)

- **Backend calculation + persistence + JSON serialization**, timed at the recording proxy from request
  received to response fully forwarded: **1 445–1 954 ms**, i.e. 33–84 % of each opening's total. An
  independent backend-only reference taken by `curl` before the browser phases, on a process that was
  then discarded and restarted: **2.040 s for the first POST after startup, 1.745 s repeat**, 200,
  1 728 855 bytes.
- **Document + bundle load, JSON transfer/parse and Vue rendering** is the remainder: ≈0.2–0.9 s for the
  179-row opening view, ≈0.9–2.5 s when the same continuous timer also covers clearing every display
  filter, switching "Show all" on and rendering all 3 176 rows into the DOM. That rendering step is what
  makes the complete-set openings the maxima, which is why they are reported as the budget figure.
- **Icon delivery** runs on the backend's own route inside these timers (17–457 backend image requests
  per opening); offscreen icons that native lazy loading defers are counted and reported (up to 2 984
  unfinished, 2 691 requests still in flight at completion) and never waited out or used to call a page
  complete.
- **In-application reopening is reported separately and is not the repeat figure**: this client keeps the
  calculated rows, so renavigating re-renders them with **0 calculations and 0 backend requests**
  (58/249/76 ms). The comparable repeat is the document reload, which does recalculate.

### Completeness and a real displayed-value read

Each of the 15 openings only counted as complete after the harness read the first row's
`[data-test="total-profit"]` text and required it to be non-empty, and asserted the row's
`[data-test="select-row"]` control was enabled — a loading placeholder or a disabled table cannot pass.
Each complete-set opening additionally asserts that the number of rows **in the DOM equals the backend's
own `rowCount`** (3 176) or fails, so no reported time covers a truncated result. The page's own summary
line in every opening read `Showing … of … matching recipes · 3176 calculated for this scope · scope ALL`
with the default settings echoed unchanged, so results were not reduced to make the benchmark faster.

### Limitations, recorded rather than worked around

- **User acceptance is OUTSTANDING.** These are measured results only; §33's explicit user/Product Owner
  confirmation has not been requested or received, and per §34 measured compliance cannot be replaced by
  it — nor it by these measurements.
- One machine, one revision, one account's database, one browser, at one market snapshot
  (29.09.2026, 10:44–10:52 local). No multi-environment or multi-account claim.
- The application icon store was an isolated disposable directory, so the cold phase is a **colder** state
  than the user's real installation (969 stored entries); these figures are conservative, not optimistic.
- Upstream CONNECT tunnel counting was not configured for this run (no upstream proxy), so the phases
  report `null` there. Upstream image behaviour was `STORY-WEB-010`'s subject, not this story's.
- Windows refused to delete a closed Chrome profile directory (EPERM on every `Default/*` file, with no
  surviving Chrome process of this session — the user's own Chrome, a single 45-process tree from
  16.09, was left alone). Each cold phase therefore used a **new** disposable profile directory rather
  than a re-emptied one, which is equally cold; the warm phase reused the second phase's directory. Two
  temp profile directories under `%LOCALAPPDATA%\Temp` (`gw2-perf002`, `gw2-perf002b`) could not be
  removed afterwards for the same reason.
- The row-selection resolution detail is still **untimed** — unchanged since `STORY-WEB-010`; this budget
  covers opening the page, not loading a selected row's tree.
- Measured against the current runtime (JVM backend + `vite preview`). The containerized target
  deployment does not exist yet, so nothing here predicts it.
- Per `tasks/lessons.md`: every port was probed on **both** address families before use (5179/8091/8199
  all free) and confirmed released (`000` on both) afterwards; the user's backend on 8080 and the
  pre-existing dev/preview servers on 5173 and 5183 were verified still answering at the end and were
  never touched.

## Follow-up Findings

F001: `npx vitest run src/ecto src/__tests__/App.spec.ts` reports **5 failed | 20 passed** both at the
measured revision `e2caa4a` and again on the working tree including the maintainer's further
`EctoSalvageScreen.vue` edit of 10:53 — identical failures, all from the Ectoplasm result-summary
restructure: the `.result-summary__item` labels the tests read (e.g. `Effective cost`) are no longer
rendered under those names. This story changed no source file, but the commit that carries it will
carry that uncommitted maintainer edit, so the CI gate on this commit will report these five. They are
pre-existing and outside this story's scope; `STORY-WEB-021` (Ecto content test hooks) appears to be
the queued story for that area.

## Blockers

None.