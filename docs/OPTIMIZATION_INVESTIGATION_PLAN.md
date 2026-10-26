# Resource, correctness and user-experience investigation

Status: proposed investigation plan, not an approved implementation roadmap. Prepared 2026-10-26 for [#116](https://github.com/Nebettadjsr/GW2/issues/116).

## 1. Purpose and decision ownership

Determine whether today's application can support the next deployment stage, identify improvements worth making, and present the Product Owner with evidence and choices. Investigate resource use, simpler code, runtime speed, database suitability, multi-user refresh behavior and the actual browser experience together.

The Product Owner explicitly retains the adoption decision. Experiments may be implemented on separate branches, but **no experiment, configuration change, database replacement, or resulting recommendation is automatically merged or deployed**. This plan itself remains on a draft PR until reviewed. Investigation issues must not receive the `auto` label or enter the unattended implementation selector. Do not dispatch Claude or change another active story as part of this investigation.

PostgreSQL remains the selected database while alternatives are evaluated. Existing domain behavior, account separation and proportionate key handling remain authoritative. All databases and test accounts used for load experiments are isolated disposable fixtures; there is no backup or data-preservation project. Product-policy changes, including different freshness promises, need an explicit decision.

This document owns the investigation sequence and proposed evaluation criteria, not new product requirements. Existing obligations remain in DOMAIN_SPEC, TARGET_ARCHITECTURE, TEST_STRATEGY and FRONTEND_UX_GUIDELINES. Results must distinguish observation, hypothesis and recommendation.

## 2. What we know now

### Observed baseline and limits

- Application baseline: `bb386ac3ce864e08c8755d014e7abbf300af8fd4` (PR #115). Three separate services: Java backend, PostgreSQL 17 and Nginx frontend. `docker/compose.yaml` sets no CPU or memory limits.
- Host inventory on 2026-10-26: 6 logical processors and 68,651,884,544 bytes physical memory; Docker reports 6 CPUs and 33,626,185,728 bytes available. This is a developer machine, not an agreed hosting specification.
- The populated live observation on 2026-10-25 used source `b976ffae41142684a475e003f990bec43a20d7bd`, Docker Desktop 29.4.3, six CPUs and one real account. It was a successful functional run, not a capacity test.

| Activity / component | Observed sampled RAM maximum | Observed sampled CPU maximum |
| --- | ---: | ---: |
| Account sync / Profit / reload: Java | 718.30 MiB | 541.40% of one core |
| Same activity: PostgreSQL | 91.36 MiB | 19.02% of one core |
| Same activity: Nginx | 11.03 MiB | 4.28% of one core |
| Populated idle: Java | 547.20 MiB | 6.73% of one core |

The maxima were not necessarily simultaneous. They cannot be summed as a measured stack peak, converted into resource limits, or divided to predict users. Background work and other developer processes shared the host. Early global bootstrap was not fully observed. Storage at the end was 27,891,379 database bytes, 32,746 items, 13,213 recipes, 996,958 icon-cache bytes and 4,863,155 graph-cache bytes. That is not a measurement of growth per account.

Source artifacts: `quality-reports/container-20261025/live-resources.json` and `.md` (local ignored files). The new reproducible offline baseline is recorded in `quality-reports/optimization-20261026/baseline/`; its scope and outcome are appended below after completion. Existing methodology and limitations: [PERFORMANCE_MEASUREMENTS.md](PERFORMANCE_MEASUREMENTS.md).

Fresh baseline on the current application commit completed successfully (no diagnostics). Durable raw evidence: [baseline-container.json](investigations/2026-10-26/baseline-container.json). Its raw `commit` field is `unknown`: the local command omitted `--commit`, and the helper defaults to an unset `GITHUB_SHA`. The separately observed application commit and raw report checksum are recorded in [baseline-provenance.json](investigations/2026-10-26/baseline-provenance.json). The raw report is preserved unchanged; future comparisons must supply `--commit` explicitly.

The [raw archive](investigations/2026-10-26/baseline-container-raw.zip) preserves original Windows bytes for checksum verification; Git can normalize line endings in the readable JSON copy. Both checksums are recorded in the provenance sidecar.

| Current offline baseline | Result |
| --- | --- |
| Build/start to all services healthy, one run with cached image layers | 20,737 ms |
| Idle sampled memory maxima: backend / PostgreSQL / frontend | 256,062,259 / 63,554,191 / 8,229,224 bytes |
| Global HTTP sampled memory maxima: backend / PostgreSQL / frontend | 272,210,329 / 66,773,319 / 8,416,919 bytes |
| Global HTTP sampled CPU maxima: backend / PostgreSQL / frontend | 53.35 / 21.34 / 5.23 percent of one core |
| Sequential lightweight HTTP workload | 2,333 requests, 0 errors, p95 33.22 ms, 75.17 requests/s over 31.04 seconds |

This deliberately empty catalog/account database was 8,255,155 bytes. The workload includes the UI shell and global reads, not populated crafting, account sync or multiple users. Its lower RAM cannot be described as an improvement over the populated live run. Only seven idle and ten HTTP resource samples were taken; this single run establishes reproducibility, not statistical confidence or a performance improvement.

### Initial assessment

**Observed:** the previous one-account workflow completed. **Inference:** backend computation/allocation is a stronger initial suspect than database RAM, given those observations. **Unknown:** sustained CPU, simultaneous total memory, contention, saturation, per-account growth and representative multi-user browser latency. Low PostgreSQL CPU does not prove query efficiency; idle gaps can hide expensive database round trips.

**Capacity conclusion today:** sufficient evidence to continue investigation on the current developer machine; insufficient evidence to certify a hosting size or multi-user capacity. No conclusion yet that PostgreSQL should be replaced, more hardware is needed, or a smaller machine will work.

### Concrete code leads, not promised fixes

- `web/task/BackgroundTaskService`: virtual thread per admitted task; exclusion is per operation/account, not a global bound on distinct accounts. Retained terminal history is bounded, unfinished tasks are not evicted. Investigate overload and fairness rather than assuming virtual threads bound resource use.
- `application/ScopedAccountRefreshCoordinator`: account-keyed admission and state; inspect lifecycle, scopes, retries and publication under contention.
- `application/TradingPostRefreshCoordinator`: shared quotes with per-feature demand states and serialized shared refresh service. Measure duplicate-ID fetches, cross-feature waiting and starvation under continuing demand.
- `web/GlobalDataRefreshScheduler`: six-hour fixed-delay trigger with one-minute initial delay; submission is asynchronous. Verify trigger timing, startup bursts, overlap and what failures are visible. Do not assume task completion is the start point of the scheduler's delay.
- `application/access/AccountSessions`: fifteen-minute lease and bounded in-memory entries. Include renewal, capacity eviction and reconnection in multi-user tests without redesigning authentication.
- `repo/Db.open()`: opens a new `DriverManager` connection per call. Measure acquisition/round-trip cost before deciding whether a bounded pool pays for itself.
- Known-problem leads: KP-01/KP-05 calculation correctness; KP-12 cross-source freshness; KP-14 repeated recipe downloads; KP-16 graph cache validation; KP-17 confirmed price absence; KP-25 dead paths; KP-30 broad work for narrow scopes; KP-32 unchanged-ID upstream edits. Revalidate each before claiming it is still present.
- Some architecture prose still describes a single-user runtime despite later account-scoped implementation. The trigger inventory must reconcile these statements with the current code and owning decisions; stale prose must not become an invented runtime restriction.

## 3. Order of work and review points

1. **A: evidence and capacity.** Capture a comparable current baseline, define the expected workload, and determine the first limiting resource. Report capacity and confidence before selecting optimization work.
2. **B: correctness and refresh audit, alongside C: profiling and UX observation.** Separate wrong results and wasteful behavior from slow but necessary work. Produce a ranked opportunity list.
3. **D: bounded experiments.** Try the highest-value changes one at a time; evaluate PostgreSQL fairly before any replacement prototype. Stop exploring a candidate if its likely benefit cannot justify its cost.
4. **E: final report and Product Owner decisions.** Recommend adopt, defer or reject for each candidate. Present branches and evidence; wait for explicit adoption choices.

These are investigation tasks, not executable implementation stories. Create individual issues/branches when an experiment is selected for investigation, linked to #116 with explicit dependencies and no automatic merge. A negative result is a valid completed investigation.

## 4. Capacity and measurement tasks

| ID | Work and tests | Output / expectation |
| --- | --- | --- |
| A1 | Inventory current commit, image digests, JVM/PG/browser settings, machine limits, caches, data sizes and competing processes. Run existing container and application-process reports separately. | Reproducible baseline, explicit unavailable metrics and profiler/load-generator overhead. No mixing one-account live results with synthetic global-read results. |
| A2 | Build a representative, versioned workload with public catalog structure and deterministic upstream/account fixtures. Include small/large inventories, recipe knowledge, All and narrow scopes, Profit and Discovery, Bank, Materials, Luck and details. Use distinct synthetic account GUIDs; same-account multi-tab is a separate case. | Real application/HTTP/SQL path with controlled external edges; no production proof bypass or live upstream load generation. A thin-tree fixture alone is not representative of a full catalog. |
| A3 | Run 1, 2, 5, 10 and 20 active clients, then increase only while safe. Independently vary stored accounts (1/20/100) and active sessions; users do not continuously compute at maximum speed. Test realistic think time plus deliberate bursts. | Capacity curve: concurrency, throughput, complete-page p50/p95/p99, errors, resource use and queue delay. Identify the knee where extra load makes latency/backlog grow. Report achieved load and generator saturation. |
| A4 | Repeat with fully cold bootstrap, warm fresh inputs, stale account, stale prices, both stale, incomplete cache and concurrent new-account onboarding. Keep warmup/build separate from steady-state timing. | Capacity per state, not a single favorable request average. Distinguish provisional-page time, refresh duration and final-result time. |
| A5 | Compare candidate constrained profiles, initially 1 CPU/1 GiB, 2 CPUs/2 GiB and 2 CPUs/4 GiB for the whole stack, with explicit per-service allocations and aggregate cap. These are test profiles, not hosting recommendations. Exercise CPU quotas, GC, memory pressure, connection and disk limits; stop at OOM, persistent failures or runaway backlog. | Smallest *tested* profile supporting the chosen workload with headroom; failed profiles remain visible. Do not keep shrinking just to reach a small idle number. |
| A6 | Run a 60-minute soak at the chosen target load, a short 2x burst and recovery to normal load. If retained state continues growing, extend observation until explained; do not call one hour proof against every leak. Simulate upstream latency, 429/Retry-After, 5xx, incomplete responses and a backend restart. | Memory/connection/task/cache growth, fairness, recovery time and failure behavior. No amplified live ArenaNet traffic. |

Capacity is conditional on the Product Owner's expected simultaneous users, account sizes, latency needs, hosting envelope and budget. Until supplied, use **1/5/20 active clients as exploratory tiers**, not an accepted business requirement. Keep one backend instance as the first deployment model. Multiple replicas are a separate future scenario, not an excuse to add distributed coordination now.

Proposed assessment rules, subject to review: zero cross-account contamination, no incorrect calculations, no unexplained request failures, bounded backlog that drains, and enough sustained CPU/memory headroom to survive the tested burst. Initially evaluate at least 30% steady-state memory/CPU headroom against the constrained profile, but treat sampled peaks and quotas separately. Existing cached complete usable Profit page obligation remains at most seven seconds; a p95 summary must not hide slower individual observations. Report all breaches. Other route latency objectives must be proposed from the measured baseline and user experience rather than invented as existing requirements.

## 5. Areas and experiments to investigate

For every row below, first trace and measure; implement a candidate only when the trace supports it. Expected benefits are hypotheses, not promised percentages.

| ID / area | Tasks and candidate experiments | Required tests and before/after evidence | Expected benefit and decision cost |
| --- | --- | --- | --- |
| B1: refresh ownership and demand | Map every trigger: navigation, connect/reconnect, manual refresh, calculation, detail request, timer, task completion and retry. For each record scope, required sources/IDs, TTL, admission key, publication, invalidation, failure and UI effect. Trace duplicate fetches and demand arriving during work. | Controlled-clock/in-flight tests: many sessions for one account; many different accounts; Profit + Discovery overlapping/disjoint IDs; insufficient-scope active task; global/manual overlap; TTL boundary stampede; session expiry mid-task; repeated navigation; account switch; continued demand fairness. Count upstream calls/IDs/bytes, publications, stale duration, waiting and visible result replacements. | Fewer redundant fetches and more predictable waiting, without changing freshness promises or blocking cached results. Determine whether per-account coordination also needs a measured global admission bound. |
| B2: correct and coherent results | Reproduce KP-01, KP-05, KP-12 and KP-17 against current code. Separate calculation defects from performance work; test moved inventory, failed sources, missing prices and mixed source generations. | Small independently calculated economic examples; property/invariant tests for inventory conservation, rounding, opportunity cost and speculative rollback; real PostgreSQL publication tests. Oracle is domain rules, not equality with a known-wrong baseline. | Trustworthy profitability and ownership. Document any legitimate output changes separately from speed improvements; do not “optimize” by skipping alternatives or treating unknown cost as zero. |
| C1: calculation and allocations | Profile All/narrow scopes, repeated changed settings and selected details. Trace graph parsing/index construction, full-catalog traversals, dependency closure and repeated mapping. Try scoped root evaluation, reusable immutable indexes or bounded versioned caching only where useful. | CPU/allocation/GC profiles plus end-to-end latency and RAM; property/differential tests on valid baseline cases; large shared subgraphs, alternative recipes, cycles and account/settings/version changes. Compare cache hit and miss. | Less computation and allocation without losing recursive ingredients, changing economics or leaking cached account results. Charge invalidation complexity and cache growth against the benefit. |
| C2: PostgreSQL access | Trace actual connection acquisition, query counts, full scans, round trips, batch writes and lock time. Inspect top queries and plans with representative data. Compare unchanged PG with targeted queries/indexes/batching, then a bounded connection-pool candidate if acquisition cost is material. | Query plans, calls/rows/bytes, connection time, active/open connections, transaction duration, lock waits, CPU/I/O, page latency; scoped SQL and publication rollback tests. Ensure no transaction holds resources over an upstream HTTP wait. | Reduce unnecessary data movement/connection overhead before blaming the engine. Indexes and pools can increase memory and write cost; show those tradeoffs. |
| C3: global metadata, prices and caches | Check repeated recipe payload downloads, no-change refresh, graph source/version validation and corrupt-file recovery. Examine confirmed price absence, retries, full-catalog scans and demand batching. | Membership change vs same-ID detail edit; incomplete publication/retry; corrupt/old graph cache; upstream missing/partial/failure; no-change refresh. Measure upstream requests, transferred bytes, parsing, rebuild duration and RAM. | Less network/CPU, reliable recovery and understandable freshness. Any new revalidation interval or negative-cache duration is a Product Owner choice, not an incidental tuning change. |
| C4: simplify code | Recheck KP-25 reachability, competing implementations, repeated DTO/mapping/coordination logic and dependency weight. Include Spring/reflection/config entrypoints before declaring code dead. Remove only confirmed obsolete paths in a separate experiment. | Call-path evidence, focused contract tests, build and full regression checks. Record production lines/dependencies removed and conceptual responsibilities before/after; retain needed tests. | Fewer paths to misunderstand and lower maintenance burden. Lower line count alone is not success; no broad framework rewrite or abstraction merely to hide code. |
| C5: browser UX | Observe connect -> ready -> find opportunity -> inspect ingredients -> decide action. Include slow/cold/stale/offline states, refresh, keyboard/mobile, large Bank lists, icons, Luck, navigation and reload. Capture 390px and desktop video/screenshots. | Task completion/time, clicks, visible waits, lost settings/selection, unexplained changes, keyboard/focus and user-facing error recovery. Use authoritative backend values. Distinguish first usable full result from first paint and final fresh replacement. | Less waiting and confusion, stable results and clearer next action. Suggestions: show usable cached results immediately with concise refreshing feedback, preserve filters/selection, explain first-time preparation, and give local retry actions; assess whether already implemented before proposing changes. Keep key management in System Status. |
| C6: rendering and delivery | Measure large table/list updates, payload size, icon fetches, route bundle size, main-thread long tasks and layout shifts. Try rendering only visible list rows, deduplicating metadata or smaller responses only when measurements show a bottleneck. | Browser timeline/CPU, usable-page time, scroll/keyboard/selection behavior, network bytes and accessibility; warm/cold image caches. Virtualized content must still support the required interaction. | Responsive interaction on modest devices; do not hide required rows or replace useful information just to lower a metric. Include frontend complexity and download cost. |
| D1: database suitability | Compare current PostgreSQL, optimized PostgreSQL, then a bounded embedded SQLite prototype if deployment/concurrency requirements permit. Screen other engines only if a specific workload advantage justifies their evaluation. Compare complete stack cost, not an isolated SELECT. | Same semantic dataset and operations: scoped reads, concurrent sync/price writes, consistent calculation snapshot, atomic publication/rollback, constraints, no upstream I/O inside transactions, cold rebuild and restart. Record p95/p99, writer waiting/errors, RAM/CPU/I/O, development effort and operational complexity. | Possibly simpler deployment or lower resource use. No presumption of a winner: a disposable cache can still need transactions and concurrent publication. No full SQL rewrite or architecture change before the bounded prototype demonstrates a material advantage. |

### Database comparison rules

Start with PostgreSQL because it already implements the application's persistence contract. Measure the cost of application access patterns separately from engine overhead. PostgreSQL query-plan and statement-statistics tools support that diagnosis; enable any extra instrumentation only on the isolated test stack, and record its overhead/configuration.

SQLite is a credible embedded candidate to test for a single application instance, not a promised replacement. Its one-writer-per-database constraint makes concurrent account/global/price publication a decisive experiment; multiple readers alone do not settle suitability. An analytical engine such as DuckDB is a secondary screening candidate only if profiling finds a dominant analytical workload: its in-process write conflict/concurrency behavior must be evaluated against our transactional publication requirements. Do not benchmark three unrelated query examples and declare a database winner.

Primary references consulted 2026-10-26: [PostgreSQL EXPLAIN](https://www.postgresql.org/docs/current/using-explain.html), [PostgreSQL statement statistics](https://www.postgresql.org/docs/current/pgstatstatements.html), [SQLite intended uses and concurrency](https://www.sqlite.org/whentouse.html), [DuckDB concurrency](https://duckdb.org/docs/current/connect/concurrency). Use documentation matching the actual pinned experimental engine version. These sources inform screening, not measured superiority in this app.

## 6. Before/after protocol

1. Freeze baseline commit and candidate commit, runtime image digests, fixture version, settings, cache state, machine constraints and workload seed. Keep load generation outside the measured service containers and record its own resources.
2. Test one conceptual change per branch (`experiment/116-<topic>`). Retain a control branch. If several changes are combined later, measure their interaction rather than adding independent percentage gains.
3. Run correctness checks before timing. Record warmup separately; perform at least five paired control/candidate steady-state runs in alternating order, and at least three independent cold-start runs for startup claims. Choose enough operation samples/window duration for the stated percentile; report count and variation. A percentile from a few syncs is descriptive only.
4. Collect synchronized whole-stack CPU/memory (including cgroup quota/throttling/OOM), JVM heap/allocation/GC, per-service CPU time, database reads/writes/size/connections/locks, upstream and client requests/bytes, job admission/wait/run duration, error rate and browser complete-page/interaction timings. Separate build, global bootstrap, initial usable result and final fresh result. Mark unavailable values and reason; don't substitute zero.
5. Report absolute before/after values and units, relative change and run spread. Avoid summing non-simultaneous peaks. Reject a numerical “gain” within observed noise unless reduced complexity or a verified UX benefit independently justifies it.
6. Re-run critical invariants on candidate: correct economic values; no cross-account data/results; no stale response overwriting changed context; no duplicate same-account writers; last-good data survives refresh failure; bounded UI recovery; correct missing/empty states; basic keyboard/mobile flow. Use focused tests while iterating; full CI and real SQL/browser evidence are required before recommending adoption.
7. Include less favorable effects: cold misses, concurrent writers, memory/cache growth, slow clients, no-change refresh and added code/operational burden. A faster warm request with worse first-use behavior must be visible.
8. Store raw JSON/CSV, profiles, sanitized request traces, screenshots and commands in a named evidence bundle. Keep a concise result summary in the repository and attach raw artifacts to the investigation issue/PR or a downloadable release artifact before relying on them. Local ignored files and expiring CI artifacts alone are not a durable final report. Do not record keys or identifying account payloads.

Before an experiment starts, fill in its primary benefit and a minimum worthwhile improvement after measuring baseline noise (for example a user-visible wait eliminated, meaningful resource headroom, or confirmed obsolete code removed). Do not require arbitrary percentage wins across unrelated areas. Stop after a bounded prototype if there is no measurable benefit, correctness cannot be preserved, or maintenance cost outweighs the gain.

## 7. Deliverables and report format

### First report: capacity readiness

- Workload and tested hardware envelope; current results vs the existing seven-second obligation.
- Supported tested concurrency by cold/fresh/stale state; bottleneck and headroom with confidence/limitations.
- Suitable for next stage / suitable with conditions / not yet demonstrated, with exact evidence and reasons.
- Missing product inputs or measurement gaps; proposed hardware profiles only where supported by tests.

### Final report: improvement decisions

One short executive summary followed by this table for **every** investigated candidate, including rejected experiments:

| Candidate / branch / commits | Current problem and user impact | Expected benefit before trial | Measured before -> after / variation | Correctness and UX result | Cost, regression risk and limitations | Recommendation | Product Owner decision |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Not yet tested | Evidence link required | Hypothesis, not a claim | Not measured | Not tested | Unknown | Investigate / adopt / defer / reject | Pending |

Include a ranked shortlist, explicit keep-as-is choices, a database recommendation with alternatives, a refresh-trigger map and multi-user findings, UX examples, reproducible commands and all failed/neutral trials. Rank correctness and user impact ahead of cosmetic metric gains. Distinguish urgent correctness work from optional optimization.

**Adoption gate:** Product Owner selects the desired changes and tradeoffs. Only then convert selected results into normal implementation/finalization stories or approve merging an experiment after its acceptance checks. No approval is inferred from successful CI, a completed review, silence or a useful benchmark.

## 8. Execution record

- 2026-10-26: reviewed existing resource methodology/live observations, roadmap deployment needs, current task/coordinator/session/scheduler code, known problems and frontend UX rules. Recorded leads above; no application changes or database replacement made.
- Fresh offline container baseline: PASS with no diagnostics, at the separately observed application baseline commit above. Command: `python scripts/container_measure.py --output-dir quality-reports/optimization-20261026/baseline --id investigation-20261026 --idle-seconds 20 --traffic-seconds 30 --strict`. Owned containers, volumes and images were cleaned up. It deliberately measures idle/global HTTP, not account/crafting multi-user capacity. Reproduction must add `--commit <actual-application-commit>`; this run's missing embedded commit is documented rather than silently filled in.
- A2-A6, B1-B2, C1-C6 and D1 are **not executed** by preparing this plan. Their expected benefits are hypotheses and their after-results are unavailable.
- Subsequent execution is recorded separately in [the first-round investigation report](OPTIMIZATION_INVESTIGATION_REPORT.md), draft PR #118. It contains actual experiments, results and explicit remaining qualification; the entries above describe the original planning stage only. Every experiment remains unmerged for Product Owner decisions.
