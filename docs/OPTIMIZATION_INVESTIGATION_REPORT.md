# Current optimization opportunities

Source-reviewed on 17 November 2026. This document retains concrete future improvements after the retired capacity programme. It proposes no automatic implementation and claims no measured production speedup, hit rate or supported-user count. Product behavior belongs in DOMAIN_SPEC; the open defect/risk register is [Known problems](KNOWN_PROBLEMS.md).

The complete [26 October investigation and follow-up](archive/investigations/OPTIMIZATION_INVESTIGATION_REPORT-2026-10-26.md), including the maintainer's local follow-up notes, are preserved with their raw evidence in the archive. Their environments and timings describe those dated experiments. Delivered pooling, worker-state reuse, recipe-payload reuse, economic corrections and retained Bank/Materials refresh behavior are not unfinished work.

## Bound ordinary upstream failure duration and release database leases

**Current evidence:** `api.Gw2ApiClient` now has a 10-second connect timeout and public JSON requests have a 30-second request timeout. Owner full-catalog operations use `TaskDeadline` with a cooperative 30-minute budget and remaining-budget SQL/retry limits. The active authenticated `getAuth(url, apiKey)` builder still lacks a per-request timeout. Ordinary TP entry points use `TaskDeadline.NONE`, so their retry sleep is not bounded by a whole-operation deadline.

`sync.TpSync.syncTpPrices` borrows one database connection before the batch loop and retains it while fetching upstream batches. This is lease ownership across HTTP, not evidence of an open SQL transaction during every wait. Profit also keeps its pinned input connection through CPU evaluation.

**Opportunity:** bound active authenticated requests and ordinary operation/retry duration; borrow connections around persistence, and consider releasing a captured Profit connection only after complete immutable input capture. Preserve source publication, interruption cleanup and snapshot correctness. Full-catalog deadline behavior already exists and should not be reimplemented.

## Reduce DOM work when showing large result sets

**Current evidence:** `useProfitTableView` returns all matching results for Show all, and `CraftingProfitTable.vue` renders each displayed row with `v-for`. The default remains 250. Formatting has already moved into precomputed display rows.

**Opportunity:** investigate windowed rendering or less per-row DOM while preserving the full dataset, filtering, sorting, selected detail, links, keyboard use and accessibility. The older fixture's DOM counts and timings are historical evidence, not a fresh result for today's application. Production behavior must remain intact through any optimization.

The committed frontend proxy has no compression directives. Actual public host compression cannot be concluded from that file alone. Evaluate transfer behavior on its own evidence before proposing a proxy change; transport compression does not remove DOM work.

## Reuse graph generations and narrow evaluation roots safely

**Current evidence:** `repo.CraftingGraphCache.loadForSnapshot(connection)` builds a new complete graph through each pinned connection. `CraftingProfitService` loads broad item/quote inputs and passes the full recipe set to coordinated evaluation even for narrow visible scopes. Forbidden roots can exit early; not every root receives a complete recursive simulation. Recipe queries also load ingredients broadly.

**Opportunity:** reuse a deeply immutable graph identified by the catalog generation captured in the same database snapshot. Retain needed old generations and avoid sharing incomplete publications. `craft.Recipe` still retains the supplied ingredients list, so an outer unmodifiable collection alone is insufficient.

Select evaluation roots separately from the complete dependency/alternative-recipe graph. Preserve unlocks, discipline/rating, daily limits, binding/owner rules, recursive valuation, cycle handling and surplus semantics. Targeted equivalence checks matter more than reusing old timing claims. This is the concrete performance follow-up tracked as KP-30.

## Pause unnecessary status reads and coalesce shared observations

**Current evidence:** System Status already guards generic and owner-detail reads against overlap within the active epoch. It still has a five-second interval without a hidden-tab pause. Automatic inventory refresh separately pauses while hidden or inactive. Shell task polling is serialized, but Profit's task waiter independently reads task status; there is no shared-request coalescing across those consumers.

**Opportunity:** pause System Status while hidden and consider shared observation of the same task. Preserve account generation, response ordering, retries and cancellation. Do not generalize the old overlapping-request observation to current code or to every view. Owner activity history already loads on opening and explicit Refresh, without a polling timer.

## Consider result reuse only with a complete identity

**Current evidence:** `CachedCraftingProfitService.profit` captures inputs and calculates each admitted request; the name describes cached game inputs, not memoized calculation results. `CalculationInputVersions.calculationId` fingerprints source generations and relevant TP quotes, but is not a complete identity for settings, selected scope, global catalog and implementation.

**Conditional opportunity:** establish useful repetition before selecting result memoization. Any cache must include all economic/context inputs, preserve input-generation coherence and account isolation, and have a bounded lifetime/memory cost. No current hit-rate or production memory saving has been established.

## Keep the remaining correctness and portability follow-ups visible

- KP-12: mixed source generations despite existing per-account single-writer and database-snapshot guarantees.
- KP-16: source validation and automatic recovery for the shared file graph cache.
- KP-17: ordinary TP refresh should distinguish confirmed absence from a transient fetch failure; full-catalog refresh already writes explicit no-data rows.
- KP-32: periodic recipe membership checks cannot detect changed details under unchanged IDs; the owner full operation deliberately reloads them.
- [#228](https://github.com/Nebettadjsr/GW2/issues/228): portable production Compose needs to express the actual restricted database role and exact HTTPS origin. Preserve the current protected host override until its reviewed replacement is proven.

Descriptions and evidence for the KP findings live in [Known problems](KNOWN_PROBLEMS.md).

## Earlier recommendations now delivered

Bank and Materials use source-specific refresh endpoints and the grants consumed by each view; their former full-refresh grant mismatch is no longer an open problem. Cached item names, automatic saving and stack-aware reservations are delivered too.

Public host HTTPS, canonical Origin enforcement, cookie verification and bounded Docker/edge logs are deployed. Awaiting ARM access and preparing TLS before public hosting are historical stages. The current runtime receipt is in [Current state](CURRENT_STATE_SPEC.md).

The retired broad capacity/measurement programme remains withdrawn. Pursue an opportunity only through a bounded approved issue with meaningful correctness evidence and a targeted comparison when it answers a concrete question.
