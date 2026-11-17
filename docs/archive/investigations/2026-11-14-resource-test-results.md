# Authorized local browser / VM run — 2026-11-14

## Outcome

The owner explicitly authorized Docker, VM startup and measurement on 2026-11-14. These are new observations;
the 2026-11-13 measurements remain excluded. The test VM was shut down at **00:02:12 UTC on 2026-11-14**
and verified **Off, 0 assigned memory**. Owned browser runners and observers were stopped.

**No independent-user capacity has been established.** Only the owner's real account was available.
Searching developer repositories, forum archives and public posts did not produce an additional usable key.
One [explicit public account offer](https://www.reddit.com/r/Guildwars2/comments/5n6t6a/long_term_gw2_trader_ama/)
limited its availability to a few days; it was excluded. No other person's account was accessed. No demo
accounts or cloned accounts were substituted.

The main scripts produced **17 completed runs / 199 completed actions**, plus **two watchdog-aborted
Characters runs without result artifacts**. Diagnostic runs are separate. The aborted attempts remain
failures to obtain a result; successful retries do not establish their cause or count them as passes.

## Environment and method

| Component | Observed configuration |
| --- | --- |
| VM | Hyper-V `GW2-resource-test`; Ubuntu 24.04 amd64; 2 vCPUs |
| Memory | Fixed 11,999,903,744 bytes; dynamic memory and guest swap disabled |
| Disk | 50 GiB dynamic VHDX; guest filesystem 50,884,108,288 bytes |
| Stack | Nginx, Java backend and PostgreSQL inside the VM; healthy, zero container restarts before shutdown |
| Generator | Headless Chrome on the same Windows physical host, outside the VM; six host logical CPUs |
| Application | `d68abeb81fb287dc85aec749eef7a7be7525b552`, including the separately held local #188 candidate |
| Main tooling | `a59f169` checkout, incorporating merged PR #197 source `7e6d26a8c0dcc149931610dd5609540092e18a10` |
| Data | 1 real account, 9 characters, 680 material rows, 788 account recipe rows, 210 bank slots |
| Shared catalog present | 32,814 items, 13,213 recipes, 2,999 TP price rows; not a claim of complete live catalog coverage |

The guest address changed to `172.24.59.31`; only the lab certificate/origin setting was updated. Product
source, authentication and calculation behavior were not changed. This local Intel VM is an approximation
of the selected resource envelope, not an Oracle ARM benchmark.

The three committed journeys drive actual Crafting, Bank/Materials and Characters pages. Each context
authenticates separately but all resolve to **one verified account**. The timings start after bootstrap and
measure the action until its rendered final checkpoint is first observed to hold for a subsequent 500 ms confirmation.
The confirmation interval and nominal one-second pauses are separate. Those pauses and scripted actions
remain **provisional, not recorded owner behavior**. Saved-content visibility and final refreshed data are
separate observations.

**Navigation measurement limitation:** initial page navigation uses `page.goto()` with Playwright's default
`load` wait before checking the rendered predicate. Although the predicate does not inspect image completion,
that navigation wait can include image loading. Initial-opening durations are therefore upper bounds on
data usability, not isolated click-to-data latency. In-app checkbox/section actions do not use that navigation
helper. Correcting the helper and repeating affected openings is required before using them for a capacity
boundary. The installed Playwright implementation confirms the default in `lib/coreBundle.js`.

Normal account bootstrap initially needed 43.1 seconds for Crafting to settle. That bootstrap is excluded
from the action table, not from the user-visible cold/refresh limitations. A later timed Crafting opening
also overlapped a TP refresh and took 10.4 seconds.

## Action timings

Values are observed ranges in milliseconds across completed actions, not percentile service guarantees.
There were three single-session runs per journey; each two-session stage and Crafting/Characters
four-session stage ran once. Bank/Materials at four sessions ran twice. The completed eight-session
Characters confirmation used the unchanged committed runner; its earlier logged diagnostic is excluded.

| Journey | 1 session | 2 sessions | 4 sessions | 8 sessions |
| --- | ---: | ---: | ---: | ---: |
| Crafting, all actions | 282–10,407 | 266–2,738 | 358–5,584 | Not run |
| Bank/Materials, all actions | 41–717 | 66–878 | 54–5,666 | Not run |
| Characters, all actions | 33–143 | 26–193 | 51–431 | 40–983 |

Crafting includes a faster result-inspection action. Its **calculation-triggering actions** took
1,690–10,407 ms at one session, 2,011–2,738 ms at two, and 2,294–5,584 ms at four. The two-second final-data
reference is therefore already missed at low concurrency. The three single-session openings were
2,399 / 10,407 / 1,790 ms; the middle run included automatic price refresh.

Bank/Materials' first four-session run had one 2,144 ms return to Materials. In the second, opening Bank
took 4,662–5,666 ms; subsequent page operations were 54–1,394 ms. Automatic refresh overlaps these timings;
these results do not establish the same latency for already-current saved reads. Saved Bank content was
observed after 146–363 ms in that second run. Refresh, image work and the navigation wait are not isolated
from one another by this measurement.

Characters' completed runs show that current saved data can render quickly with eight sessions. An earlier
one-session attempt hit its 240-second external watchdog; the first eight-session attempt hit 180 seconds.
During the latter, account-refresh tasks took approximately 28.7 and 30.4 seconds and eventually succeeded.
The runner did not preserve action progress before those aborts, so their exact blocking step remains unknown.
A temporary ignored copy with start/end progress logging completed all 40 eight-session actions; the normal
runner then also completed all 40. No product fix or resolution of the aborted-run cause is claimed.

Completed Bank/Materials journeys observed unchanged saved-selection digests, zero preference writes and
zero invoked save controls. Completed main runs reported zero page errors and browser disconnects. Bounded
Bank request traces overflowed: counts and error summaries from those traces describe retained records only.
Server logs provide a separate view of completed wire requests.

## Resources and requests

Three guest-observer windows collected 643 points. A fresh clock probe estimated guest minus generator at
−208 ms, with approximately ±174 ms request-bracketing uncertainty. Fifteen completed main runs have at
least one wholly contained CPU, memory and container interval; the Characters retry and second Crafting
baseline fell in a sampling gap. This is not continuous per-action coverage. Short action CPU peaks cannot
be inferred from these samples.

At four Crafting sessions, nine contained whole-VM CPU intervals averaged **88.5%**, peaking at **99.75% of
the two-vCPU VM**. The backend reached **198.43% container CPU**, where 200% represents two fully occupied
CPUs. Its 12 completed profit requests had a 3,780.5 ms median and 5,212 ms maximum at Nginx. CPU pressure
is consistent with the calculation slowdown; a code-level cause needs profiling.

Whole-VM used memory across all observed windows peaked at **2,987,393,024 bytes**; backend container memory
peaked around 2.28 GB. Those maxima include bootstrap and diagnostics. They do not establish JVM heap limits,
long-duration growth or observer overhead. The observer was not separately cost-qualified in this run.

The physical Windows host reached 100% sampled CPU during the run; the logged eight-session diagnostic
also reached 97%. Minimum sampled free host RAM was 28,839,657,472 bytes. All 282 host samples reported
GW2 **not running**. Other host work was not isolated, so generator/host contention remains a limitation.
Main-run Node event-loop maxima reached 185 ms; Chrome CPU was not separately collected. The ladder stopped
after the calculation breaches, refresh-related aborts and eight-session confirmation; eight is not a maximum.

Nginx records completed requests, with query strings omitted. Its business-API rate excludes item icons and
other resource images. In the eight-session Characters confirmation it logged 37 completed business requests
over 8.648 seconds: **4.28/s average, maximum 16 in a one-second bucket**. At four Crafting sessions it logged
26 over 18.361 seconds: **1.42/s average**, including 12 profit calculations. Completed main-run windows had
no logged HTTP 4xx/5xx business responses. These are observed workload rates, not backend request ceilings.
Browser events can include cache hits and are not wire-request counts. Window overlap is not causal
action correlation. Outbound ArenaNet rate, inflight server work, DB-pool saturation and a backend maximum
request rate were not measured.

## Database footprint

The final read-only snapshot contained **one account**. It recorded **610,740 bytes (~0.58 MiB)** of row
payload across account-keyed tables and **3,383,296 bytes (~3.23 MiB)** allocated to those relations including
indexes/free space. The entire database was **32,577,203 bytes (~31.07 MiB)**, including shared catalog and
database allocation. Zero temporary-query bytes and deadlocks were observed in the final cumulative counters.

These are one populated account's current storage observations. Row payload is not physical marginal disk
space, and relation allocation contains minimum page/index overhead and cache churn. A per-user storage
growth slope and account-to-account variation remain unmeasured. No copied accounts were created to imply
otherwise. Point-in-time DB counters are retained; rollbacks are not classified as application failures.

## Evidence and next work

Local, ignored evidence lives in `target/supervision/resource-test-20261114/`: run JSON and watchdog records,
host and guest samples, aligned windows, clock estimate, bounded Nginx logs, final DB point, image/source
identities and confirmed shutdown. `summary.json`, `server-summary.json` and `resource-summary.json` summarize
the raw files. Diagnostic files are explicitly named and excluded from the main aggregates. No keys, cookies
or browser authentication state were exported.

The unchanged tooling retains its literal `preparation-only` / `not-qualified` artifact labels. This report
records the newly authorized execution; those labels have not been changed into a qualification claim.

The useful next work is correcting the initial-navigation measurement, a bounded profile of the profit
calculation, progress preservation/reproduction of the stale-refresh Characters aborts, and actual owner
interaction recordings. A representative independent
account capacity result still needs additional populated real accounts; target Oracle CPU/storage behavior
and backend saturation require their own evidence. Existing workflow holds, including #188 visual approval,
remain separate from this measurement run.
