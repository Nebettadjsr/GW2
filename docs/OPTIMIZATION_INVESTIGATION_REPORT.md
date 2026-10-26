# Resource and improvement investigation: first-round findings

**Measured first-round findings; full deployment qualification is not complete.** Parent issue: #116. No adoption, merge, database replacement or deployment is authorized by this report. The Product Owner decides which recommendations to implement.

## Conclusions for the next stage

1. **The current developer setup can support continued development and small multi-user trials. Public/shared hosting capacity is not yet certified.** Actual one-account Profit navigation produced a usable result in about two seconds, while larger synthetic workloads exposed throughput and latency limits. The six-CPU developer machine is not equivalent to a tiny hosting instance.
2. **Avoid buying hardware or replacing PostgreSQL as the first response.** There are measured application-level opportunities: bounded connection reuse and reduced simulation-state copying. PostgreSQL's current measured footprint is small relative to Java, and no alternative engine has demonstrated a benefit in this application's complete persistence contract.
3. **Correctness takes priority over faster wrong results.** Two independently reproduced economic defects remain: an avoidable 50-copper purchase and discarded intermediate surplus. Neither is fixed by the performance experiments.
4. **The strongest measured optimization candidates are connection reuse and request-local simulation-state reuse.** Both improve the tested high-load workloads. Neither establishes a universal RAM saving, and the pool prototype exposed a startup fault during qualification that had to be corrected.
5. **Improve refresh clarity and recovery in the UI.** Bank/Materials reread cached data without showing its age, and a recoverable failed read removes the previous view. Proposed changes are described below; they have not been silently implemented.
6. **All decisions remain with the Product Owner.** Experimental branches, before/after data, costs, test evidence and qualification gaps are recorded for selection. No experiment is merged or deployed.

## Scope and evidence ledger

The initial seven-minute deliverable was an investigation plan plus one empty-stack measurement. It did not execute the full plan. This report records subsequent investigation separately so planning cannot be mistaken for completed work.

| Investigation | Evidence collected | Status / limitation |
| --- | --- | --- |
| Empty three-container stack | 2,333 lightweight requests, zero errors, 33.22 ms p95; raw report and provenance committed with plan | Completed single observation; not populated crafting or multi-user capacity |
| Existing populated live workflow | Prior one-account measurements on 2026-10-25 | Historical observation; different workload/source, no before/after comparison |
| Existing application-process benchmark | Ten workloads and boundary integration tests on isolated PostgreSQL 17.11, no live ArenaNet | Completed, zero workload diagnostics; synthetic 5,000-recipe fixture; one account even in existing concurrent workload |
| Distinct-account capacity | New isolated HTTP/SQL experiment at 1/2/5/10/20 accounts, concurrent onboarding and changed-settings calculations, account-specific bank markers | First run passed without errors or marker contamination; no browser rendering or upstream latency included |
| Bounded PostgreSQL connection pool | Opt-in eight-connection prototype, separate branch, unchanged default path | Five alternating paired measurements completed with no workload diagnostics; 47 focused coordinator/scoped SQL/HTTP/connection tests passed with pool enabled |
| Code simplification | Revalidated references and removed three confirmed orphan classes on a separate branch | 155 production lines removed; backend/frontend CI and workflow checks passed on 081504c; no runtime speed claim |
| Correctness | Two independent domain counterexamples: bound-input pruning and batch surplus | Both reproduced on baseline bb386ac; exact expected/actual results below |
| Refresh behavior and UX | Source audit plus actual normal-app browser at 1280px and 390px; cached-read failure injection and recovery | Observations completed, no page errors; not a formal usability/accessibility study |
| Recipe payload reuse | Controlled before/after upstream request counter, exact SQL assertions and failure/retry tests | Two detail fetches before, one after; 26 targeted tests and both application CI suites passed |
| Database alternatives | Technical suitability screening | No end-to-end SQLite/DuckDB prototype or measured engine ranking |
| Constrained application process | Two CPUs / 1.5 GiB and one CPU / 1 GiB with 5,000 recipes; two CPUs / 2 GiB with 13,000 recipes | All completed without workload errors; larger catalog exposes materially worse latency; not a hosting capacity certificate |
| Simulation-state allocation | JFR attribution, request-worker reuse, equivalence and concurrency tests, five alternating 13,000-recipe comparison pairs | All ten workload reports have no diagnostics; 117 domain invocations and strengthened daily-limit comparisons passed; full application CI passed on bdec991 |
| Soak, real catalog and fault matrix | Not completed | Sustained hosting capacity remains unproven |

All load experiments use a dedicated PostgreSQL container with a uniquely named schema per run and deterministic upstream edges. They do not load-test ArenaNet or modify the user's normal database. The load generator currently shares the application JVM, so its CPU, heap and response buffers contribute to process measurements. Windows RSS is unavailable in the existing report. A sampled Windows working set is an observation, not an isolated service memory requirement.

## Initial capacity observations

The first distinct-account run used six host CPUs with unconstrained JVM ergonomics, a 5,000-recipe binary-tree fixture and 400 bank slots per account. Each client performs three changed-budget Profit requests plus bank/status/selectors. One HTTP percentile mixes these routes; it is not a browser complete-page percentile.

| Simultaneous accounts | Mixed HTTP p95 (ms) | Mixed requests/s | Sampled JVM heap maximum (MiB) | Errors / account-marker violations |
| --- | ---: | ---: | ---: | --- |
| 1 | 187.12 | 10.30 | 257.8 | 0 / 0 |
| 2 | 279.24 | 15.27 | 409.4 | 0 / 0 |
| 5 | 451.55 | 26.96 | 885.9 | 0 / 0 |
| 10 | 805.89 | 30.56 | 1,114.1 | 0 / 0 |
| 20 | 1,649.03 | 30.42 | 2,759.2 | 0 / 0 |

Observed throughput plateaus while latency rises at the higher tiers. This supports investigating a saturation point; it does not identify a single cause. Sampled heap includes earlier allocations and the in-process client, so the table is not a per-account memory slope. The 20-client workload has only 240 mixed HTTP operations and is not a sustained capacity test.

The existing eight-client benchmark shares one cookie/context. It was useful concurrency coverage but did not establish eight-account capacity. The new experiment supplies distinct GUIDs, sessions and a unique bank-count marker for each account; calculations must return nonempty, genuinely craftable rows.

## Refresh-trigger audit: current findings

| Trigger | Current behavior / evidence | Multi-user or UX consequence to verify |
| --- | --- | --- |
| Browser reload / restored key | App restores backend proof; account bootstrap reads selector options and synchronizes Profit sources only if pending | Does not blindly full-sync every reload; verify multiple sessions coalesce correctly and users understand first setup |
| Bank / Materials mount and Reload | Plain scoped GET of cached SQL data; no ArenaNet synchronization (`accountApi.ts`, `useAccountRead.ts`) | A user may press Reload expecting fresh game data. Screens do not currently render the returned freshness envelope; propose last-synchronized context and clearly named actions |
| Bank / Materials read loading or failure | `useAccountRead` clears prior data before each read | Recoverable cached-read failure blanks a useful inventory. Consider retaining a clearly marked last successful view rather than implying it is current; explicit UX review required |
| Profit calculation / explicit refresh | Account TTL defaults to 15 minutes; separate shared TP TTL is ten minutes; account/source scope and task references are maintained separately | Verify stale provisional result, scoped source coverage, relevant-ID changes and no repeated external work per browser poll |
| Account synchronization | At most one unfinished task per account; different accounts execute independently | Protects same-account writers, not total CPU/RAM/upstream concurrency across distinct accounts; workload-wide admission/fairness is still an investigation |
| Shared price demand | Coordinator retains account demands and drains them; gateway serializes shared Profit/Discovery refresh work | Existing quotes can be reused, but account-specific selection work may still repeat and one feature can wait behind another. Continuing demand fairness and actual upstream ID counts need controlled tests |
| System Status open | Five-second status interval; timer cleared on unmount; no visibility-state check in the interval | Browsing elsewhere stops that screen timer, but hidden tabs can continue status requests. Slow replies can overlap because setInterval does not await completion |
| Task tracking | Shared shell owns one tracker; normal task polls every three seconds, Profit recovery every one second, bootstrap every two seconds | Navigation does not create another tracker; quantify duplicate status lookups when feature recovery and shared tracking overlap |
| Scheduled global refresh | One-minute initial delay; fixed-delay six-hour submission, same global task exclusion as manual run | Scheduler delay is between submissions, not necessarily six hours after work completes; RuntimeException from admission is swallowed. Test startup/manual overlap and visible failure reporting |
| Recipe refresh with unchanged IDs | Publication marker now detects an interrupted catalog, but completed unchanged membership skips details | Same-ID recipe edits still need an explicit affordable freshness policy; no new interval has been chosen |

These are source observations and test targets, not claims that each inferred risk is a demonstrated defect. Preserve account separation and read-only key handling without introducing financial-grade credential infrastructure.

## Candidate experiments and adoption constraints

### JDBC connection reuse

`repo.Db.open()` currently creates a new DriverManager connection per call. A profiling sample showed digest/HMAC work among the leading execution samples; the shallow recording alone cannot attribute all of it to PostgreSQL authentication. The eight-connection Hikari prototype tests whether connection reuse improves the complete workload instead of assuming a database-engine defect.

The first uncontrolled pilot improved 20-account mixed p95 from 1,649 ms to 1,253 ms and throughput from 30.42 to 41.28 requests/s, with eight observed application connections rather than twenty. Some lower tiers were worse. **These single-run pilot numbers are not an accepted gain:** initial heap state and measurement variation differ. Use the paired series for a recommendation.

The prototype is disabled by default and lives only on `experiment/116-bounded-jdbc-pool`. It uses an explicit public-schema reset for this isolated experiment. Before adoption it would need application lifecycle integration, deployment configuration and timeout behavior, custom search-path support where required, transaction/schema reset tests, and a selected pool size under sustained mixed load. No hot path is approved solely because the prototype runs faster.

#### Five-pair outcome

Same source commit `b7be0ee`, fixture `multiaccount-fixtures-1`, six host processors and a two-GiB maximum JVM heap for both modes. Run order alternates control/candidate and candidate/control. All ten reports have no workload diagnostics. [Run-level summary and checksums](investigations/2026-10-26/paired-pool-summary.json); [raw reports/logs](investigations/2026-10-26/paired-pool-raw.zip).

| Active accounts | Control: median per-run mixed p95 (ms) | Eight-connection pool (ms) | Median paired p95 change | Control -> pool median throughput (requests/s) |
| --- | ---: | ---: | ---: | ---: |
| 1 | 192.17 | 208.68 | +8.6% | 10.13 -> 15.63 |
| 2 | 275.54 | 267.94 | -2.0% | 17.09 -> 23.71 |
| 5 | 475.57 | 472.19 | +1.0% | 25.89 -> 34.45 |
| 10 | 852.64 | 656.88 | -22.5% | 29.49 -> 40.68 |
| 20 | 1,733.70 | 1,144.53 | -30.9% | 29.09 -> 41.21 |

The median paired percentage is computed per matched pair, not from the two displayed medians; these can differ. Mixed-route throughput and p95 can move differently because query mix/cache state affects different requests. This is not a pooled percentile over all runs or a Profit-only browser figure. The high-load gain is repeatable in this fixture; low-load p95 benefit is not established and the one-account median regressed. Account-marker checks and actual crafting checks passed in each run. The pool bounds application DB connections, not total concurrent calculations.

With the pool enabled, 47 focused tests passed: scoped account coordinator (11), shared TP coordinator (5), scoped TP demand (3), repository reads (14), scoped real-HTTP/PostgreSQL isolation (13), and Db helper (1). These exercise current behavior; they do not make the prototype deployment-ready.

At 20 accounts, the median total CPU time for the same workload fell from 26,375 to 20,875 ms, while average CPU utilization increased from about 53% to 59% of the six-core process envelope because the workload finished sooner. Higher instantaneous utilization is not automatically a regression. Median per-run heap peaks also differed, but heap includes GC history and the client; do not turn them into a claimed per-user memory saving.

#### Constrained-process observation

The same experiment passed in a Docker container limited to **2 CPUs and 1.5 GiB**, including Maven, the application JVM and in-process clients, with the JVM configured using `-Xmx768m` (reported max heap 778,502,144 bytes). PostgreSQL remained outside that cap. The image ran Temurin 25.0.4.1 on Linux; the Windows paired series used a different JVM/OS, so these are separate capacity observations, not comparative optimization gains.

At 20 concurrent accounts: mixed HTTP p95 **2,794 ms**, **18.90 requests/s**, no HTTP errors or diagnostics; sampled process RSS maximum **978.9 MiB**, sampled heap maximum **684.2 MiB**. Container exited 0 without OOM. This is a short synthetic burst, not a sustained 20-user hosting guarantee. [Raw process report](investigations/2026-10-26/constrained-cpu2-ram1536.json), [external container limits/provenance](investigations/2026-10-26/constrained-cpu2-ram1536-provenance.json). The raw harness retains a legacy "no container" description; the external provenance records the actual container execution rather than editing raw evidence.

The **first one-CPU / 1 GiB attempt stalled in the prototype**, not from OOM. A mounted virtual thread held `Db$ExperimentPool.<clinit>` while blocked in PostgreSQL authentication I/O on the sole carrier. The investigator preserved [thread dumps and provenance](investigations/2026-10-26/pool-initialization-stall.zip), then stopped that run. No valid capacity report was generated, and it is not counted as a memory-capacity failure.

Revision `3289f8987ac0ffa3b42df7b6f8cdd1bed343a87b` moves pool construction out of class initialization. The one-CPU / 1 GiB rerun, with `-Xmx512m`, completed with no errors or OOM through twenty accounts. At twenty: mixed p95 **5,744.87 ms**, throughput **9.34 requests/s**, sampled RSS maximum **722.5 MiB**, sampled heap maximum **445.1 MiB**. [Raw report](investigations/2026-10-26/constrained-cpu1-ram1024-fixed.json), [limits/source provenance](investigations/2026-10-26/constrained-cpu1-ram1024-fixed-provenance.json). Its embedded commit is unknown because that wrapper omitted GITHUB_SHA; source provenance is recorded separately, not inserted into raw data.

The five-pair speed results above belong to `b7be0ee`; they are not silently relabeled as measurements of revision `3289f89`. The corrected code needs its own adoption verification. A separate fresh-JVM test with virtual-thread scheduler parallelism forced to one passed in 0.642 seconds; it confirms startup without relying on a pool initialized by an earlier test. The one-CPU workload leaves little latency room for rendering and more realistic account/catalog/refresh load; it does not establish a suitable twenty-user deployment. Both profiles cap the harness/client/Maven together and leave PostgreSQL outside the cap. They cannot certify a whole-stack 1 GiB host.

#### Catalog-sized synthetic workload

Increasing to **13,000 synthetic recipes**, with two CPUs / 2 GiB for harness/client/Maven and `-Xmx1280m`, materially changes the assessment. Corrected pool revision `3289f89`; zero HTTP errors, workload diagnostics or OOM. This scales catalog size, not real ArenaNet recipe topology or upstream latency.

| Active accounts | Mixed HTTP p95 (ms) | Requests/s | Sampled process RSS maximum (MiB) |
| --- | ---: | ---: | ---: |
| 1 | 1,481.48 | 2.93 | 708.8 |
| 2 | 1,375.40 | 4.93 | 951.6 |
| 5 | 3,169.96 | 6.17 | 1,236.0 |
| 10 | 5,282.47 | 6.45 | 1,253.1 |
| 20 | 8,066.77 | 6.22 | 1,566.3 |

[Raw report](investigations/2026-10-26/catalog-13000.json), [limits/provenance](investigations/2026-10-26/catalog-13000-provenance.json). Throughput flattens and response latency rises despite all requests succeeding. A mixed API percentile is not a Profit-only or complete-browser percentile, so it cannot certify or precisely quantify the seven-second page obligation. This profile provides no basis for claiming a responsive twenty-user deployment. Reducing connection overhead alone is insufficient.

### Reduce repeated simulation-state allocation

Reprinting the existing JFR with full stack depth identifies `PlanState.<init>` as the first application frame for **63.5% of sampled allocation weight** in that mixed baseline recording. This includes allocations made inside Java map implementations called by the constructor; the shallow hot-site view concealed the application caller. [Attribution and raw-file checksum](investigations/2026-10-26/allocation-profile-summary.json). Sample weight is not live retained memory or an exact allocation total.

Each coordinated recipe evaluation currently copies the account inventories into a fresh simulation state. `experiment/116-reuse-simulation-state` tries one reusable state per worker **inside one request**, retaining accepted-craft journal history until that root evaluation finishes and restoring all state afterward. It uses a request-local worker map, not ThreadLocal storage or cross-request/account pooling. Separate fresh-state behavior remains the default.

Tradeoff: fewer full inventory copies, but a longer undo journal and additional rollback work; correctness and actual throughput must decide whether this is worthwhile. Tests compare complete results across forty seeded fixtures and eight settings combinations, plus twenty-four concurrent account fixtures, and directly verify restoration of bound/character inventory, daily limits, visiting state and buy cost. The existing domain suite passed 117 invocations across 25 classes; the strengthened daily-limit comparison also passed. These comparisons preserve baseline behavior and do not fix the separately confirmed pruning/surplus bugs. The completed timing and sensitivity results follow.

#### Five-pair state-reuse outcome

All ten runs completed successfully on source `bdec991a56fd2cabd38b8db16a83b7c61bfb311d`. Both modes use the corrected eight-connection pool, the same six-core Windows environment and a two-GiB maximum JVM heap. Only simulation-state reuse differs. Do not add these percentages to the earlier pool experiment: its source, catalog size and comparison axis differ.

| Active accounts | Fresh-state median per-run mixed p95 (ms) | Reused-state median (ms) | Median paired p95 change |
| --- | ---: | ---: | ---: |
| 1 | 349.65 | 283.39 | -19.5% |
| 2 | 572.03 | 483.66 | -20.3% |
| 5 | 1,023.32 | 857.61 | -20.8% |
| 10 | 1,719.91 | 1,307.40 | -26.9% |
| 20 | 3,340.82 | 2,460.14 | -26.8% |

At twenty accounts, median per-run total CPU time was **61,562.5 -> 39,828.12 ms** (median paired change **-37.8%**) and throughput **15.21 -> 21.22 requests/s** (median paired change **+39.6%**). Every pair improved p95 (range **-32.3% to -17.4%**) and CPU time. Heap peaks were not consistently better: paired changes range from **-17.3% to +9.3%**. No guaranteed RAM reduction is claimed. [Run-level summary/checksums](investigations/2026-10-26/paired-state-summary.json), [raw reports/logs](investigations/2026-10-26/paired-state-raw.zip).

A supplemental pure-domain empty-inventory/buying-enabled check used two thousand flat recipes, warmup and five alternating pairs. All result checksums matched. Runs were only about 31-37 ms; one candidate run regressed and the short measurements vary, so they do not establish a general latency gain or rule out regressions in other inventory/settings shapes. [Raw observations](investigations/2026-10-26/empty-inventory-tradeoff.json), [reproducer](investigations/2026-10-26/EmptyInventoryTradeoff.java). This is separate from the application/HTTP benchmark.

Recommendation: a worthwhile candidate for production finalization and independent review, with explicit journal-lifetime and account-isolation checks. It adds about forty net production lines plus tests/tooling, rather than simplifying the state model. Preserve that complexity cost and the low-inventory qualification gap in the adoption decision.

#### Constrained follow-up

The combined pool-plus-state prototype was also run with the same two-CPU / 2 GiB harness cap, `-Xmx1280m`, Java image and 13,000-recipe fixture as the earlier constrained catalog observation. All account tiers completed without HTTP errors, workload diagnostics or OOM.

At twenty accounts, mixed p95 was **5,517.78 ms**, throughput **10.09 requests/s**, sampled process RSS maximum **1,519.5 MiB**, and sampled heap maximum **1,117.5 MiB**. Earlier pool-only observation: **8,066.77 ms**, **6.22 requests/s**, **1,566.3 MiB** RSS. These are separate single runs; the Windows five-pair series provides the repeated comparison evidence. Neither includes browser rendering or caps PostgreSQL, and neither certifies a whole-stack hosting size. [Raw follow-up](investigations/2026-10-26/catalog-13000-state-reuse.json), [limits/provenance](investigations/2026-10-26/catalog-13000-state-reuse-provenance.json).

### Code reduction

On `experiment/116-remove-dead-price-path`, delete `api.Gw2PriceFetch`, `model.Price`, and `craft.ResolvedNeedMapper`: 155 production lines, no replacement abstraction or removed assertions. Reference checks found no external production/test caller for these orphan paths. The live domain still uses `Node` and `RecipeTreeBuilder`; those remain. Expect a maintenance benefit, not a meaningful runtime speedup. Build/regression results must be recorded before recommending adoption.

Backend, frontend, workflow checks and coverage reporting passed on the experiment commit `081504c`. Recommendation: a small, low-risk maintenance candidate for Product Owner approval; no assertion was removed or weakened. Preserve separate review rather than bundling this with a database or calculation rewrite.

### Reuse recipe details

`experiment/116-reuse-recipe-payloads` retains parsed ingredient facts from the first detail download until item publication completes. Ingredient publication then uses those same facts rather than requesting and parsing every recipe batch again. Existing transaction ordering and incomplete-publication retry remain.

Observed controlled counter: **two recipe-detail requests before, one after**, with the same recipe/item/ingredient/deletion SQL assertions. This halves detail requests for the fixture, not total global-refresh traffic. Twenty-six targeted real-database tests passed, including item-fetch failure and ingredient-write failure followed by unchanged-ID retry; full backend/frontend CI also passed on `747ee03`. Net production change is small and negative in line count. Tradeoff: parsed ingredient rows remain in memory across item synchronization. Full-catalog peak allocation/network-byte measurements are still needed before claiming a complete startup RAM/time benefit.

### Confirmed correctness findings

[Independent failing counterexamples](investigations/2026-10-26/domain-counterexamples.txt) on branch `experiment/116-domain-counterexamples` (`ff2d0a2`), unchanged application baseline `bb386ac`:

1. **Invalid pruning lower bound (KP-01):** root needs one intermediate; buying it costs 50 copper, crafting uses one owned bound raw input. The raw input's market replacement price is 100 copper, but its sale opportunity cost is zero because it is bound. The correct root input cost is zero; actual result is 50 copper. The direct-price estimate incorrectly suppresses the cheaper craft.
2. **Discarded batch surplus (KP-05):** each root needs five intermediates; one raw input crafts six intermediates. Five owned raw inputs produce thirty intermediates, sufficient for six roots. Actual resolver completes five roots. These are two test failures with expected economic results, not infrastructure/setup failures.

Recommendation: address correctness before treating current profitability as trustworthy in these cases. Fix pruning with a valid comparison/lower bound and preserve produced surplus through simulation/rollback. Both may affect runtime, so remeasure after correction; a faster wrong answer is not an optimization. The counterexamples are excluded `*IT` investigation tests and have not been merged into default CI or used to weaken an assertion.

### Browser findings and proposed UX changes

[Observed browser evidence](investigations/2026-10-26/browser-observations.json) covers the actual normal app, not mocked screen HTML. Bank/Materials were populated at desktop/mobile widths, with no document-wide horizontal overflow, repeated key form or page errors. All 174 occupied Bank slots had icon metadata; after waiting for visible image decoding, images displayed correctly. Pending offscreen lazy images are not icon failures. The initial observer had an incorrect Materials selector; corrected observation counts actual category sections. No icon regression is established by the early screenshots.

An actual Bank Reload issued only `GET /api/account/bank`. After intentionally aborting that read in the browser, previous slots disappeared and the retry button appeared. Removing the interception and retrying restored them. Neither Bank nor Materials displayed freshness wording in the observed screen. [Mobile Bank](investigations/2026-10-26/bank-390.png), [recoverable read failure](investigations/2026-10-26/bank-reload-failure-390.png).

A fresh Profit observation displayed 204 rows in 1,974 ms; returning from Bank took 71 ms and preserved search, and selecting a row worked. An earlier observation displayed a provisional result while refreshing. These are single observations on the developer app, not certification of the seven-second contract across stale/missing/concurrent scenarios. The first selection observer used the wrong ARIA attribute; rerunning against the implemented `aria-current` verified selection correctly. [Corrected Profit observation](investigations/2026-10-26/profit-observations.json).

Proposed UX improvements for review:

- Show when Bank/Materials last synchronized and distinguish re-reading the cache from requesting fresh game data. Avoid a misleading expectation from Reload alone.
- Keep an explicitly marked previous inventory visible during a recoverable reread/failure, with a concise retry. Never present it as newly refreshed/current.
- On narrow screens, improve the visible affordance for horizontally scrolling Bank slots; the current fixed-width grid clips additional columns inside its own scroll region. Consider a compact navigation treatment so the header consumes less of the initial viewport.
- Consider names/search and an owned-only filter for long material lists; mobile tooltips containing only item IDs are a weak discovery experience. These are feature suggestions, not new requirements.
- Prevent overlapping System Status polls and consider pausing them in hidden tabs, followed by an immediate read on return. Measure actual demand first; don't build a monitoring platform or distributed queue.
- Preserve the existing useful behavior: System Status-only key management, working icons, scoped state reset, preserved Profit search/navigation and immediate usable cached results.

### Database choice

Keep PostgreSQL as the control while measuring connection/query behavior. The existing live observation showed approximately 91 MiB sampled PostgreSQL RAM, with Java much larger. This weakens the case for an immediate database rewrite to save memory but does not establish engine superiority. Compare optimized PostgreSQL before spending effort porting arrays, UUID casts, Flyway migrations and snapshot/publication semantics to another engine.

SQLite's single concurrent writer is directly relevant to concurrent account/global/price writes; its suitability cannot be decided from read-only microbenchmarks. DuckDB is a secondary analytical candidate, not a drop-in replacement for the scoped publication contract. **Recommendation for this round: retain PostgreSQL and address the measured application/access costs first.** Reopen an alternative-engine prototype if the agreed deployment envelope or later measurements justify it. See primary-source links in the plan; no new database architecture decision is made here.

## Provisional recommendation order

This ranking is for Product Owner review. It authorizes no merge or automatic implementation run.

| Priority / decision | Current problem | Proposed change and expectation | Evidence / what is not measured |
| --- | --- | --- | --- |
| 1: prioritize correctness | Pruning overstates material cost; batch surplus understates craftability | Correct candidate comparison and surplus ownership/rollback. Expected example outcomes are 0 copper instead of 50, and six crafts instead of five | Both defects independently reproduced. Fixes are not implemented, so there are no measured after-results. Recheck performance because valid exploration can cost more CPU |
| 2: consider a managed bounded JDBC pool | Repeated connection creation adds overhead and leaves connection demand tied to caller concurrency | Reuse a bounded set of connections with lifecycle/schema/transaction reset and explicit overload behavior | Five-pair prototype improves high-load p95/CPU/throughput; low-load p95 not consistently better. One-CPU initialization fault found and corrected on experiment branch; production wiring/adoption verification still required |
| 3: consider request-worker simulation-state reuse | Large repeated inventory copies dominate sampled allocations | Reuse isolated worker state within a request and restore every recipe's changes | Five-pair thirteen-thousand-recipe trial improves high-load p95/CPU/throughput; heap effect is mixed. Longer journals, added logic and low-inventory qualification remain costs |
| 4: improve freshness and recovery UX | Reload implies more freshness than it provides; recoverable reads erase the previous view | Show source age, distinguish cache reread from refresh, retain a visibly dated last-good view with retry | Actual browser/network/failure observations support the problem. No replacement UI has been implemented or user-tested; expected reduction in confusion is a hypothesis |
| 5: consider recipe payload reuse | Global refresh downloads/parses identical detail batches twice | Retain parsed ingredient facts until item publication and use them for ingredient writes | Detail calls measured 2 -> 1; exact SQL publication and retry assertions pass. Extra temporary retention must be checked at full catalog size; no claimed percentage gain in total startup time |
| 6: consider orphan cleanup | Three obsolete classes provide misleading alternative paths | Remove 155 production lines without a new abstraction | Full application CI passed; maintenance benefit. Runtime speed/resource improvement is not claimed |
| 7: investigate remaining broad data/calculation work | Throughput plateaus and larger catalogs increase latency even with connection reuse | Profile remaining graph/index/input construction and safe dependency closure, then test bounded CPU/upstream admission and fairness | Larger-catalog measurements support the need; no measured after-result or chosen limit yet. Preserve account isolation, recursive dependencies and the seven-second UX obligation |
| 8: retain PostgreSQL for now | Engine suitability questioned; no evidence that replacing it addresses the leading costs | Optimize measured access patterns before paying for a SQL/transaction-porting experiment | Existing PG footprint is small relative to Java and connection reuse helps. No end-to-end alternative-engine comparison; this is a practical keep-for-now recommendation, not proof of universal superiority |

## Qualification gaps and subsequent work

- Both five-pair series and the combined constrained follow-up are complete, with run-level values, negative/neutral cases and raw evidence recorded. This concludes the initial measurement/experiment round, not every proposed test in the investigation plan.
- Before adopting either optimization, run the chosen final implementation through independent review and its normal functional/SQL/browser gates. Recorded speed results remain tied to their exact experimental commits.
- Hosting certification still requires an agreed workload and host envelope, realistic catalog/account diversity, per-route and complete-browser latency distributions, upstream latency/429/failure bursts, and sustained load/recovery. The exploratory 1/5/20 tiers are not an agreed hosting requirement. No one-hour soak was performed.
- Check small/empty inventories and buying-heavy settings before generalizing the state-reuse result. Retained journals may cost more than fresh empty states; numerical equality alone does not prove speed in that workload.
- Complete the refresh trigger/fault matrix before changing freshness, negative-cache durations or admission policies. Existing coalescing/isolation checks passed, but this is not proof of every multi-user failure scenario.
- A database replacement requires a bounded full-contract prototype only if these results or the eventual deployment envelope justify its cost. No replacement has been selected or implemented.

All these gaps stay explicit; neither CI success nor a provisional recommendation closes them. The Product Owner chooses which improvement to finalize, defer or reject.

## Reproduction and repository state

- Report and plan: draft PRs #118 and #117, respectively. They remain unmerged. Investigation parent #116 remains open.
- Pool: branch `experiment/116-bounded-jdbc-pool`; paired measurement commit `b7be0ee`, initialization correction `3289f89`. `scripts/investigations/compare-jdbc-pool.ps1` on that branch reproduces the alternating series against an explicitly supplied isolated database.
- State reuse: branch `experiment/116-reuse-simulation-state`, commit `bdec991`; `scripts/investigations/compare-simulation-state.ps1` runs five pairs, with both modes using pool size eight. The report's `scripts/investigations/summarize-simulation-state.py <raw-directory>` validates metadata, workload diagnostics and source identity before calculating results.
- Recipe fetch reuse: branch `experiment/116-reuse-recipe-payloads`, candidate `747ee03`; instrumented before-state `5f8881c`. Run `SyncFetchPublicationTest` and `RecipeSyncTest` against an isolated PostgreSQL database.
- Orphan cleanup: branch `experiment/116-remove-dead-price-path`, commit `081504c`. All required application CI checks passed; 155 source lines removed, no assertions removed.
- Counterexamples: branch `experiment/116-domain-counterexamples`, commit `ff2d0a2`. Explicitly run `InvestigationCounterexamplesIT`; two expected normative failures reproduce the defects on application baseline `bb386ac`.
- Normal app processes remain available at ports 5173/8080. Owned investigation containers and disposable volumes were removed after recording results. Experimental worktrees/branches remain available for review; the report branch contains evidence and analysis helpers, not adopted runtime changes.

## Selected implementations (2026-10-26)

The Product Owner selected six changes after the investigation. They are implemented in a separate worktree as a stack of draft PRs. They remain unmerged; `master` is still `bb386ac`. The normal running application has not been replaced.

| Selected change | Review artifact |
| --- | --- |
| Actual effective-cost recipe selection and journalled intermediate surplus | [PR #125](https://github.com/Nebettadjsr/GW2/pull/125) |
| Production bounded PostgreSQL pool, configuration and lifecycle | [PR #126](https://github.com/Nebettadjsr/GW2/pull/126) |
| Request-local simulation state reuse, including the simulator's remaining copy | [PR #127](https://github.com/Nebettadjsr/GW2/pull/127) |
| One recipe-detail download per batch, reused for ingredient publication | [PR #128](https://github.com/Nebettadjsr/GW2/pull/128) |
| Remove the three confirmed obsolete classes | [PR #129](https://github.com/Nebettadjsr/GW2/pull/129) |
| Bank/Materials actual refresh, backend freshness and retained error snapshots | [PR #130](https://github.com/Nebettadjsr/GW2/pull/130) |

Local combined verification: 950 default backend tests passed with one existing skip; 568 frontend tests passed; production frontend build/type checking passed. Explicit PostgreSQL account-isolation and fresh one-carrier pool-startup checks passed. The browser smoke uses controlled HTTP responses and passed at 1280px and 390px, including refresh, read failure/recovery, freshness, disconnect isolation and overflow checks. Native Codex reviews were requested for all six PRs; their current outcomes and CI results belong to GitHub.

Final state reuse is different from the experimental version: it removes the simulator's internal per-root copy as well as the outer repeated construction. A domain-only 13,000-recipe comparison after the calculation fixes produced identical complete results in every pair; five alternating pairs after warmup showed a median paired time reduction of 10.13%. Do not substitute this number for the earlier mixed HTTP experiment or compare the two percentages as if they measured the same workload.

Independent review caught that both sides of the seeded equivalence matrix accidentally disabled reuse. Commit `800bde1` fixes the candidate to use the enabled production default, and all 126 crafting tests then passed, including the 320 actual fresh-vs-reused comparisons. The concurrent-account and paired benchmark tests already used distinct modes. The final stack head `6951f3d` changes that one test line relative to the load/browser-qualified source; production code is unchanged. The fixed review thread is resolved, and a new Codex review was requested.

That repeat review completed with no findings. All six selected PRs have independent Codex review receipts without remaining findings. Required branch checks passed for all six heads; some duplicate PR-triggered runs were still finishing at the last check. All six remain draft and unmerged.

An additional empty-inventory check of the final state implementation matched every output but showed mixed timing: warmup 464/931 ms (fresh/reused), then alternating pairs 269/412, 217/220, 220/200, 214/205, 226/235 ms. Median paired change was +1.38%, with a larger cold/early JIT cost, so there is no demonstrated general speed benefit for empty inventories. Raw [empty-inventory pairs](investigations/2026-10-26/selected-state-empty-pairs.txt) and the [test-only overlay](investigations/2026-10-26/selected-state-empty-overlay.patch) preserve that qualification. This closes a targeted negative-case check, not the broader soak/hosting gaps above.

One combined qualification run used the final application source `1edc3cd`, real HTTP/PostgreSQL and the prior isolated multi-account test fixtures at 13,000 recipes. Account-specific markers remained isolated at 1/2/5/10/20 concurrent accounts, with no HTTP errors or workload diagnostics. At 20 accounts, mixed-route p95 was 1303.33 ms and throughput 35.73 requests/s. The JVM had six processors and a 2 GiB heap; the load client lived inside it and PostgreSQL was outside that envelope. This is a qualification run, not a paired speedup claim, browser latency measurement or hosting certification. KP-12 cross-source consistency remains open.

Raw results, prototype-metadata corrections, source hashes and limitations are recorded in [selected implementation provenance](investigations/2026-10-26/selected-improvements-provenance.json), [load results](investigations/2026-10-26/selected-improvements-load.json) and [state pairs](investigations/2026-10-26/selected-state-pairs.txt). Browser fixture screenshots: [Bank error on mobile](investigations/2026-10-26/selected-bank-error-mobile.png), [Materials desktop](investigations/2026-10-26/selected-materials-desktop.png).
