## Story ID

STORY-PERF-001

## Title

Resolve Crafting Profit page-load latency on the real user database

## Status

DONE

## Milestone

milestone-03

## Goal

Meet the complete-page performance requirement in TARGET_ARCHITECTURE.md §33 on the user's current real database and obtain explicit user confirmation before Phase 3 closes.

## Authoritative Source Documents / Sections

- Explicit Product Owner instruction, 2026-09-22: real current user data; page load to page complete at most seven seconds; no next phase before resolution and explicit user confirmation.
- docs/TARGET_ARCHITECTURE.md §33 (requirement and acceptance gate).
- docs/ROADMAP.md Phase 3 (blocking exit criterion).
- docs/TEST_STRATEGY.md §34 (measurement procedure).
- docs/KNOWN_PROBLEMS.md §7.9 (reported issue).
- docs/CURRENT_ARCHITECTURE.md §5.1; docs/DOMAIN_SPEC.md (preserve calculation behavior).

## Context

The user reports approximately 20 seconds from opening Crafting Profit to a populated page. This is not yet an independently measured baseline. Profit now has an application-service boundary; isolate the actual bottleneck before choosing changes, so useful improvements carry into the future REST backend. The active application extraction work must not be overwritten or mixed into this fix.

## Acceptance Criteria

- Establish reproducible end-to-end baseline measurements on the current real user PostgreSQL database and running application, including All scope, first opening after startup and repeat openings. Record environment/data scale/settings/cache state and each elapsed time; do not substitute mock or reduced fixtures.
- Identify the responsible pipeline stages with evidence; implement bounded optimizations preserving complete requested results, domain rules, binding, economic valuation and intentional simulation limits. Obtain a user decision for material architecture/freshness/correctness trade-offs.
- Re-measure navigation-to-complete-interactive-page time under the same real-data conditions; every reported acceptance run must meet the at-most-seven-second limit in §33. Faster is optional. First rows/spinners, backend-only duration or moved/hidden work do not satisfy the requirement.
- Record before/after timings, relevant correctness evidence, limitations and tested revision in Result. Update current architecture only for actual implementation changes and the known-problem status only when supported.
- Obtain and record explicit subsequent, dated user confirmation that the measured fix solves the issue. The request authorizing this work is not acceptance. Until measurements and confirmation both exist, keep the acceptance gate open and do not claim Phase 3 may close or Phase 4 may begin.

## Required Tests

- Real-user-database end-to-end timing under TEST_STRATEGY.md §34; retain per-run results and maximum, not just averages.
- Relevant correctness regressions for the evidenced optimization; add a failing regression before a behavior fix where practical. Synthetic tests may protect correctness but cannot establish speed acceptance.
- Verify complete results and the real UI endpoint; run affected build/tests as appropriate, reporting unavailable checks honestly.

## Constraints

No assumed caching/parallelism/backend rewrite; profile first. Do not change data freshness or silently omit/reduce work. Do not modify, delete or reseed real user data to benchmark; never run destructive fixture setup against it. Do not expose credentials/account contents. No later-phase architecture implementation or unrelated cleanup. No milestone transition or automatic inference of user approval.

## Dependencies

None. Use the current application-service boundary after deterministic selection; do not modify an active parallel story.

## Definition of Done

Measured compliance on the current real user database, relevant correctness verification, documented implementation/evidence and explicit dated user acceptance are all present. If implementation is ready but user confirmation is pending, report that through the existing human-intervention workflow; do not mark this gate complete.

## Result

**Status: COMPLETE. Implementation is measured compliant on the real user database and the Product
Owner gave explicit dated acceptance on 2026-09-23 (recorded verbatim under "User acceptance" below),
so both conditions of the `TARGET_ARCHITECTURE.md` §33 acceptance gate are met and that gate is
CLOSED. Phase 3's other exit criteria are untouched by this story and remain the planner's to
assess; this story does not itself close Phase 3 or start Phase 4.**

Tested revision: working tree on top of commit `a693d9d` (this story's changes are uncommitted at
the time of measurement, alongside the unrelated in-flight `STORY-APP-*` extraction work).

### Environment and conditions (identical for every run below)

- Windows 10 Pro for Workstations 19045, 6 logical processors, OpenJDK 25.0.1, JavaFX 25.0.2.
- The developer's real PostgreSQL database and real account data — no `gw2tool.test.schema`
  override, no fixture seeding, no data modified or reseeded. Read-only throughout.
- Data scale: 3151 visible recipes (All scope), 13141 recipes in the full graph, 13836 item ids,
  9426 TP quotes, 13804 items, 8 characters, 579 sellable / 251 account-bound / 2 character-bound
  inventory entries.
- Settings: the view's own defaults (`useOwnMats=true, allowBuying=false, maxBuyCopper=1g,
  listingSell=false, listingBuy=false, dailyBuyInsteadOfCraft=true`), All scope.
- Cache state: each measurement launches the application fresh, so run 1 is the first opening after
  startup (cold JIT, cold OS/DB cache, `crafting_graph_cache.json` read from disk) and runs 2–3 are
  repeat openings within the same process (warm).

### Measurement procedure

`uiverify.CraftingProfitPageLoadRealDbPerfIT` (new; the `IT` suffix keeps it out of the default
`./mvnw test` run, per `TEST_STRATEGY.md` §34) launches the real `Gw2App` through TestFX and measures
**from the click on "Crafting Profit Calculator" until the real page stops changing**. Completion is
detected by quiescence rather than by first rows: the FX thread is sampled every 50 ms for the
table's row count, the identity of its first and last row objects and the status label, and the page
counts as complete at the moment of the *last observed change*, after which nothing changes for 3 s.
The 3 s settle window is excluded from the reported elapsed time. The table must then answer a real
displayed-value read through its own `cellValueFactory` chain for every row, so "interactive" is
verified rather than assumed. Row-object identity is part of the sample deliberately: a second,
redundant reload replaces every `CraftRow`, so it is observable even when it recomputes identical
numbers.

### The reported-versus-measured discrepancy, resolved

`UI-001-STORY-PERF-001` required investigating why the previous ~22 s backend diagnostic did not
match the user's ~38–45 s observation. It did not match because the backend diagnostic measured one
`CraftingProfitService.reload(...)` in isolation, while the real page did materially more work.
Instrumenting the real controller/service/planner/UI path showed, per page open:

| Stage | Baseline (real UI, per reload) |
|---|---|
| `CraftingProfitService.reload` data load (all `repo.*` stages) | 0.3–1.0 s |
| `planner.evaluateAllCoordinated` | 21.7–38.5 s (27–38 s when two reloads contend for the 6 cores) |
| `CraftingProfitController.prepareRows` | 11–45 ms |
| console reload-counter loop in `CraftingProfitView` | **5.8–10.0 s** |
| FX queue wait + row mapping + filter/sort/refresh | 3–25 ms |
| **reloads executed per page open** | **2, concurrently** |

So the real page ran the entire pipeline **twice**, and each pass additionally spent 6–10 s building
per-recipe resolution trees for a console-only counter line. Neither was visible to a service-level
diagnostic. The FX thread itself was never the problem: table population, filtering, sorting and
rendering together stayed under 30 ms, and nothing blocked the FX thread.

### Changes made

All four are behaviour-preserving. UD-005's prohibition on the `RecipeSimulator` binary-search
replacement was observed — the sequential batch loop still runs one batch at a time — and no caching,
precomputation or staleness was introduced anywhere.

1. **`CraftingProfitView` — one reload per page open.** `show(...)` ran its own initial
   `reloadTable`, while `reloadDisciplineChoices` finished on a background thread and called
   `selectFirst()`, whose value listener fired a second, concurrent, identical reload (the value
   moved from `null` to "All", and `CraftingProfitService.reload` treats those identically). The
   listener now reloads only on a real scope change, via a new package-private `sameScope(...)`.
2. **`CraftingProfitView` / `CraftingProfitService` — console counters off the tree-building path.**
   The reload-summary counters kept by `KNOWN_PROBLEMS.md` §7.7 read only `missingToBuy`, but went
   through `getResultByRecipeId(...)`, which lazily builds each row's resolution tree, rebuilding a
   13141-recipe index and copying the whole 13141-entry result map on every call. They now use a new
   `getRawResultByRecipeId(...)`. The logged line is unchanged.
3. **`craft.PlanState` — speculation by undo journal instead of deep copies (authorized by UD-005).**
   The planner speculates constantly: try a craft, compare it against buying, try each eligible
   character, keep the winner. That was expressed by deep-copying the entire inventory before every
   attempt — over 4.17 million full copies of ~830 entries per All-scope page load. `PlanState` now
   records the previous value of every slot it writes, so `mark()`/`rollbackTo(...)` undoes an
   attempt in time proportional to the changes actually made. `captureDelta(...)`/`applyDelta(...)`
   cover the one site that picks a winner among several attempts, and `commitTo(...)` drops undo
   information for accepted craft batches that are never revisited, keeping the journal bounded
   across the unchanged 250-iteration limit. Callers mutate through `addMissingToBuy`, `addBuyCost`,
   `beginVisiting`/`endVisiting` and `setDailyLeft`; `CraftingResolver.resolveNeed`,
   `CraftingResolver.tryCraft` and `RecipeSimulator.simulatePhase` speculate in place and roll back.
4. **`craft.PlannerContext` — memoized pure lookups.** `eligibleCharactersFor(recipe)` and the choice
   behind `CraftingResolver.firstRecipeFor(...)` depend only on data fixed for a context's lifetime,
   yet were recomputed millions of times per page load — re-splitting discipline strings, rescanning
   the roster, re-estimating every candidate recipe's cost. Both are now memoized per context.
   `PlannerContext.withBuyingDisabled()` derives `RecipeSimulator`'s zero-cash phase-1 context so it
   shares those tables instead of starting empty for each of the 13141 recipes.

### Before / after (real UI, real database, All scope, view defaults)

| Opening | Baseline | After (verification run) | After (shipped revision, acceptance run) |
|---|---|---|---|
| FIRST, after application startup | 36 400 ms | 2 879 ms | **2 772 ms** |
| REPEAT #1 | 41 866 ms | 1 960 ms | **1 658 ms** |
| REPEAT #2 | 43 245 ms | 1 399 ms | **1 406 ms** |
| **Maximum** | **43 245 ms** | 2 879 ms | **2 772 ms** |

**Every acceptance run — including the slowest, and including the first opening after startup — is
within the ≤7 000 ms limit of `TARGET_ARCHITECTURE.md` §33; the measured maximum is 2 772 ms.** The
acceptance runs were taken after all temporary instrumentation had been removed, i.e. on exactly the
revision described above. `changesAfterFirstPopulation` was 0 in every run, confirming that a single
reload now reaches the complete result set. Per-run figures are reported rather than an average, and
no run was excluded. Nothing was moved outside the timer, hidden behind a spinner, omitted, cached or
served stale: the full 3151-row All-scope result set is computed from the database on every open, and
the 250-craft simulation limit (UD-003) is untouched.

Stage attribution after the change, from the same instrumentation as the baseline table: data load
0.3–0.7 s, `planner.evaluateAllCoordinated` 0.43–0.96 s (from 21.7 s), console counters 1–2 ms,
`prepareRows` 11–25 ms, FX population/sort/render 3–25 ms, one reload per open.

### Correctness evidence

- **Real-database result equivalence.** `CraftingProfitEquivalenceRealDbIT` (new) records a digest of
  every field of all 13141 `CraftResult`s — craftable count, buy cost, mats sell value, revenue,
  profit per craft, total profit, blocked reason, and both missing-to-buy maps in iteration order —
  plus every prepared `UiRow`'s displayed name, count and summary, and a broad sample of rendered
  resolution trees, across five settings combinations: view defaults; own mats + buying at 1g; own
  mats + buying at 100g with listing prices and daily-craft mode; no own mats + no buying; no own
  mats + buying at 10g. The digest was recorded **before** any `craft.*` change and compared after.
  Over 65 755 result lines and 15 755 row lines:
  **0 differences in any computed value, 0 differences in any resolution tree.**
- **One observable difference, disclosed.** 764 results differ only in the *iteration order* of the
  unordered `missingToBuy` map — identical keys, identical quantities. Because
  `CraftingProfitController.summarizeMissing` displays only the first two entries it encounters, 122
  of 15 755 prepared rows name a different pair of materials in the truncated "To buy: X, Y, …"
  text: 63 merely reorder the same two names, 59 name a different material in the visible pair. The
  full missing set, all quantities and the shopping-list contents are unchanged.
  **The view's default settings are entirely unaffected (0 differences), because `missingToBuy` is
  only populated when "buy missing mats" is enabled.** That order was never specified and was already
  an arbitrary `HashMap` bucket/chain artifact; reproducing the old arbitrary order would mean
  preserving incidental `HashMap` capacity and chain-order side effects of the discarded
  copy-per-attempt implementation. Raised for the Product Owner rather than silently accepted: if a
  defined ordering for that summary is wanted, that is a new presentation decision, not part of this
  story.
- **Regression suite:** full default `./mvnw test` — 140 tests, 0 failures, 0 errors, including every
  `craft.CraftingResolver*Test`, `CraftingPlannerHeuristicSkipCharacterizationTest`,
  `CraftingResolverCoordinatedCharactersTest`, `CraftingResolverBoundMaterial*Test`,
  `application.CraftingProfitServiceTest`, `CraftingProfitCoordinatedScopeTest`,
  `CraftingBlockedRowsTest` and `repo.CharacterSelectionCraftingPlanIntegrationTest`.
- **New regression tests.** `craft.PlanStateJournalTest` (8 tests) pins the property the whole
  optimization rests on: that undoing a speculation restores exactly the prior state across every
  kind of slot, that nested speculations unwind independently, that a captured winning delta
  re-applies exactly and remains undoable, and that a commit keeps values while ending their undo
  history. `CraftingProfitViewScopeChangeTest` (6 tests) pins both halves of the reload suppression:
  that `null`/All really are one scope, and that no genuinely different discipline, character, or
  All↔narrower transition is ever mistaken for the current one.
- **Real-view UI regressions:** `uiverify.CraftingProfitViewSmokeIT`,
  `CraftingProfitViewBlockedRowIT`, `CraftingProfitViewErrorStatusIT`,
  `CraftingProfitViewRefreshPreservationIT`, `CraftingProfitCoordinatedScopeIT` and
  `CraftingProfitViewTpRefreshIT` — 7 tests, 0 failures. These use disposable-schema fixtures and
  prove behaviour, not speed.

### Limitations

- The equivalence reference digest is written to `target/` and deliberately never committed: it
  contains real account-derived quantities. `CraftingProfitEquivalenceRealDbIT` is therefore a manual
  before/after tool — it records a reference when none exists and asserts against it when one does —
  not a standing CI regression test. Re-running it on a clean `target/` only re-records.
- Measurements come from one machine with 6 logical processors. The planner parallelizes across
  cores, so a machine with fewer cores would be slower. The margin is large (2.8 s against 7 s), but
  this is a single-environment measurement.
- Only the All scope at view defaults was measured for acceptance, as §33 requires; narrower scopes
  are strictly smaller work and were not separately timed.
- Temporary stage instrumentation was added to `CraftingProfitView`, `CraftingProfitController` and
  `CraftingProfitService` to produce the attribution table above, and **removed** before the
  acceptance runs. No debug counters remain in production source: `PlanState.DEBUG_COPY_COUNT` and
  `PlannerContext.DEBUG_ELIGIBLE_CALLS`, listed as known temporary artifacts by the previous attempt,
  are gone.
- `application.CraftingProfitServiceRealDbPerfIT` is retained for backend per-stage profiling, but it
  is no longer acceptance evidence; `uiverify.CraftingProfitPageLoadRealDbPerfIT` is.

### User acceptance

`TARGET_ARCHITECTURE.md` §33 requires both recorded measurements meeting the limit *and* an explicit
subsequent Product Owner confirmation, dated and tied to the tested revision and results. Both now
exist.

**Given: 2026-09-23**, through the human-intervention workflow, recorded in
`agent/user-interventions/UI-001-STORY-PERF-001.md` (`User Resolution: ACCEPTED — 2026-09-23`), after
and separate from the request that authorized this work. It is tied to the revision and results
above — the working tree on top of `a693d9d`, maximum 2 772 ms against the 7 000 ms limit. Verbatim:

> The Product Owner accepts the completed STORY-PERF-001 result.
>
> The real UI navigation-to-complete-page measurements now meet the required ≤7 second performance
> target, including the measured first opening after startup and repeat openings. The performance
> issue is considered resolved.
>
> The implemented behavior-preserving optimizations, including the PlanState undo-journal redesign,
> are accepted.
>
> The reported `missingToBuy` iteration-order difference is also acceptable. No defined ordering for
> the truncated first-two-items presentation is required at this time, and no follow-up work is
> requested for it.
>
> STORY-PERF-001 may be completed and its performance gate closed. Normal roadmap execution may
> resume according to the existing planner/roadmap process. This acceptance does not add or reorder
> any other roadmap work.

Consequently: the §33 gate is closed, `KNOWN_PROBLEMS.md` §7.9 is resolved, and the disclosed
`missingToBuy` iteration-order difference is accepted with no follow-up story requested. Phase 3's
remaining exit criteria and any milestone transition stay with the planner; this story asserts
neither.

### Re-verification at completion (2026-09-23)

Re-ran the default suite on the accepted revision before closing: `./mvnw test` — **36 test classes,
140 tests, 0 failures, 0 errors**, unchanged from the figures recorded above. The performance and
equivalence measurements were not re-run at closure; the source under test is unchanged since they
were taken, and re-running the equivalence harness against a clean `target/` would only re-record its
reference (see Limitations).

## Blockers

None. Acceptance received 2026-09-23; the prior simulation-design intervention is addressed by
`agent/user-decisions/UD-005-crafting-profit-simulation-optimization.md`.
