# Application resource measurements

Owner of: how to reproduce and read the STORY-PERF-005 application measurements. The requirement lives in
`TARGET_ARCHITECTURE.md` section 17; test-layer rules live in `TEST_STRATEGY.md`. These measurements are
informational: they never gate a story, merge, pull request or CI run, and they do not state hosting capacity.

## What is measured

The code is under `backend/src/test/java/perf`. It starts the real Spring Boot application (real HTTP, controllers,
application services, scoped repositories, the production Flyway chain, the shared crafting graph cache) against
a disposable, uniquely named PostgreSQL schema that it drops afterwards. Only the outside edges are fixtures:
the account proof port, the upstream account GETs, and the global-data and Trading Post price refresh gateways.
No GW2 key, network access or populated database is needed. Fixture version: `perf-fixtures-2`
(raw materials, a binary tree of Chef recipes, deterministic prices, bank/material/recipe/Luck responses).
Version 2 also substitutes the account item-metadata upstream edge introduced by normal icon repair;
synthetic IDs receive no public metadata, so no ArenaNet request is made by account synchronization.
Reports from version 1 are not compared with this fixture version.

| Workload | Boundary exercised |
|---|---|
| `idle` | process with no requests or tasks |
| `account_sync_full`, `account_sync_incremental` | `POST /api/sync/account` to task success; incremental repeats after about 5% of upstream rows change (the application always re-fetches and republishes whole sources) |
| `price_refresh` | `POST /api/prices/refresh` (Profit) to task success; the gateway writes the price rows by JDBC |
| `profit_small`, `profit_large` | `POST /api/crafting/profit`, All scope with an account roster, over 200 / 5,000 recipes; one cold call then repeats with a changed buy budget so the result cache cannot answer |
| `global_refresh` | `POST /api/sync/global` including the real crafting-graph rebuild; the gateway writes recipes by JDBC |
| `http_typical` | sequential status, bank, materials, selector-options reads |
| `database_reads` | `TpPriceRepository` and `ItemRepository` reads of every fixture item |
| `http_concurrent` | 8 clients issuing a profit/status/bank/selector mix |

## Reproduce

```
cd backend
export DATABASE_URL=jdbc:postgresql://localhost:5432/<disposable db> DATABASE_USER=... DATABASE_PASSWORD=... GW2_API_KEY=placeholder
./mvnw test -Dtest=ApplicationResourceBenchmarkIT -DfailIfNoSpecifiedTests=false
```

Output (relative to `backend/`): `target/perf/application-resources.json` (machine-readable) and `application-resources.md`
(`-Dgw2.perf.output-dir=<dir>` overrides). Sizes are in `ApplicationResourceBenchmarkIT.sizes()`. The default
suite runs only `MeasurementReportTest` (report math, units, metadata, unavailable-value and observer-failure
handling; no database). `ApplicationBenchmarkBoundaryIT` (tiny sizes, no timing assertions) proves each workload
reaches its boundary and the connection observer neither opens a session per sample nor counts itself. It runs real
workloads and asserts every diagnostic is empty, so it is deliberately an excluded `*IT`, not part of the normal
gate (informational/nonblocking separation; its behavior assertions are unchanged). Run it explicitly:

```
./mvnw test -Dtest=ApplicationBenchmarkBoundaryIT -DfailIfNoSpecifiedTests=false
```

The JSON carries `schemaVersion`, `commit`, `timestamp`, `fixtureVersion`, `environment`, and per workload
the metrics (`value`, `unit`, `boundary`, or `value: null` with `unavailableReason`), limits and diagnostics.
Compare two runs only when `schemaVersion`, `fixtureVersion`, the sizes and the environment match.
`measurementScope` is `application-process`; container figures are a separate report (see Container measurements below).

## KPIs and limits

- Process memory (heap, resident set from `/proc`) and CPU time/utilization are for the whole JVM, which also
  hosts the in-process load-generating client and fixture writers. Peaks are sampled every 20 ms, so they are
  lower bounds. RSS is unavailable off Linux; CPU time is unavailable on a JVM that cannot report it.
- HTTP p95 is nearest-rank over the workload's requests, measured at the client including body download.
  Throughput is requests over workload wall time. Profit and sync workloads have few samples; read p95 as indicative.
- Database rows inserted/updated/deleted/read come from `pg_stat_all_tables` for the test schema after a
  1.3 s settle (PostgreSQL publishes statistics with a delay). Storage is tables plus indexes plus TOAST at the
  end of each workload. Connection KPIs come from one
  persistent observer session opened before the timed window (not a connection per sample), which reads open and
  active client-backend counts in a single `pg_stat_activity` query every 25 ms. Sessions tagged with application
  name `gw2-perf-observer` (the observer and the statistics probes) are excluded from both counts. The counts are
  database-wide, not per schema: other clients of the database, fixture writers and the observer's own small query
  load are not excluded. `db_connections_open_peak` counts every state; `db_connections_active_peak` counts state
  `active` (point samples, short statements are mostly missed). Both are lower bounds; the application opens a
  connection per call (no pool). If the observer cannot open or a query fails, both are unavailable with the reason
  and a diagnostic.
- Asynchronous workloads (account sync, global and price refresh) record the submission and every task-status
  poll in the same HTTP figures (request count, p95, throughput, body bytes, errors). Polls are every 50 ms, so
  `background_job_duration` has 50 ms granularity, and the polls themselves add a small load to the measured process.
- `database_reads` runs repository calls without HTTP, so it reports `repository_read_latency_p95`,
  `repository_read_throughput` and `repository_read_rounds`, never `http_*` or `network_*` KPIs. A database
  probe failure yields unavailable KPIs (with unit and reason) plus a diagnostic, not a number or an exception.
- The profit workloads run after an owned Chef character is seeded outside any measured window and report
  `craftable_rows`; a diagnostic is raised if no row actually crafted.
- Network ingress/egress is HTTP body bytes only. JDBC wire bytes and upstream traffic are marked unavailable
  (offline fixtures send nothing upstream; there are no per-process network counters without OS tooling).
- Background-job duration is accept-to-terminal-state. Per-job CPU and memory are unavailable because tasks
  share pool threads and heap; the workload-window process figures are reported instead. Frequency is the
  configured global-refresh interval, not an observed rate.
- Fixture data is synthetic and the global/price refresh gateways bypass upstream fetching and parsing, so
  those workloads understate real refresh cost. Runs on shared CI runners are noisy.
- Nothing here is a measurement of hosting capacity.

## Automatic reporting in GitHub Actions

`.github/workflows/performance.yml` (job "Performance report (informational)") runs on every push, pull request
and manually (`workflow_dispatch`); there is no schedule. It runs `ApplicationResourceBenchmarkIT` and
`ApplicationBenchmarkBoundaryIT` against a job-local PostgreSQL service with a placeholder key, no live ArenaNet
access and no display. `ci.yml` never runs them.

- It is not a required check and must stay out of `.github/scripts/merge_ready_pr.py` `REQUIRED_CHECKS` and branch
  protection. The job and measuring steps are `continue-on-error`, and `.github/scripts/performance_summary.py`
  always exits 0, so a benchmark failure, setup failure or missing report never fails CI or a merge.
- The job summary lists every KPI, each unavailable KPI with its reason, benchmark diagnostics, and (when the
  benchmark step failed) the tail of the Maven log. If no valid report exists, the summary says so and still
  records commit, run and outcome.
- Artifact `application-performance-<commit>-<run id>` (30 days) holds `application-resources.json/.md`,
  `maven.log`, `summary.md` and `metadata.json` (commit, timestamp, workloads, fixture version, environment,
  run URL, benchmark outcome). The artifact store is the only history; there is no separate database.
- Deltas appear only against the latest earlier completed `master` run's report, and only if `schemaVersion`,
  `fixtureVersion`, `measurementScope` and the whole `environment` (which includes the benchmark sizes, runner,
  Java, PostgreSQL and CPU/heap figures) are identical. Otherwise the summary states why no comparison is shown
  (no history, expired artifact, or the differing fields). Deltas are never a pass/fail result.
- Limits: measurements are application-process, not container; hosted runners are shared and noisy, so small
  changes are not meaningful. Artifacts expire, so the first compatible comparison may be absent.
- Phase 6 health review: read the latest `master` run's summary and artifact as the starting evidence.

Controlled checks: `python -m unittest discover -s .github/scripts` (`test_performance_summary.py` covers
compatible/incompatible/absent history, unavailable KPIs, missing and corrupt reports, and the workflow's
trigger and nonblocking configuration).

## Container measurements (STORY-PERF-007)

Scope `container-stack`, separate from the application-process report above and never merged with it.
`scripts/container_measure.py` builds and starts `docker/compose.yaml` under its own unique project
`gw2-cmeasure-<id>` on an ephemeral loopback port with a throwaway database password and an isolated, disposable
PostgreSQL volume; it never reads a maintainer `.env`, `DATABASE_*` or `GW2_API_KEY`, and on exit runs
`compose down -v` for that project and removes only its two built images. It refuses to start if the project already has
containers, networks or volumes, or any expected-name network, volume or built image (including unlabelled leftovers).

```
python3 scripts/container_measure.py --output-dir container-perf-out [--id <lowercase-id>] [--idle-seconds 20] [--traffic-seconds 30] [--strict]
```

Needs Docker with Compose v2 and Python 3 (no packages). Output: `container-resources.json` (schema `container-2`,
workload `container-workload-2`) and `container-resources.md`. Without `--strict` the exit status is 0 even when a
measurement fails; failures appear in `diagnostics`. `.github/scripts/performance_summary.py --container` renders it.

Recorded: actual image ids and Docker-reported sizes per service (application image ids are provenance and are *not*
a comparison condition; the PostgreSQL image id and application runtime base layers are), one `up --build --wait` time to all-services-healthy (includes the
image build, so cache state changes it; per-service readiness is not reported), idle and `http_global` Docker stats
peaks/means (CPU, memory) per service, client-side request count, errors, p95, throughput and body bytes for sequential
GETs of `/`, `/api/system/status` and `/api/crafting/ingredient-search?query=wood` through the frontend proxy, and
post-traffic `pg_database_size` and connection count from inside the postgres container.

Limits: CPU is Docker's percent of one core (above 100 on several cores) and memory is `docker stats` usage; both are
~1 s-spaced point samples taken by an `docker stats --no-stream` call that takes seconds, so "peak" is an observed
sample maximum and only a few samples exist per window. A separate sequential traffic thread keeps issuing requests while
the observer collects Docker samples, so workload CPU is not sampled only in request-free pauses. Version 1 used paused
traffic and is incompatible with this corrected workload. The observed window can include a final sample/request drain.
The client shares the host with the stack. Network and block
I/O counters and per-service readiness are not measured. Account sync, crafting profit/discovery and live-upstream
workloads are not measured in container mode (no GW2 key, no proof bypass); the application-process fixture reports
cover those. A global-read workload is not evidence about the seven-second crafting requirement, container resource limits
are not set, and nothing here is a hosted-deployment or capacity statement. Runs on shared runners are noisy.

Workflow: the same Performance job runs the container step after the application steps, with its own
`continue-on-error` steps and artifact `container-performance-<commit>-<run id>` (`container-resources.json/.md`,
`container.log`, `summary.md`, `metadata.json`) and a "Container performance" job-summary section. Deltas appear only
against the latest earlier completed `master` container report with identical `schemaVersion`, `workloadVersion`,
`measurementScope` and `environment` (engine/OS/architecture/CPU/memory, workload parameters, PostgreSQL image id and runtime base layers);
otherwise the reason is stated. Failure, missing report or an unavailable metric never fails CI or a merge.
The workflow uses `--strict` inside a nonblocking step so a failed measurement has an explicit failure outcome.
Runtime base layers come from actual image inspection, excluding the two application filesystem layers in each current
Dockerfile (backend: user/directory setup and jar; frontend: template and dist). Update this boundary if those Dockerfiles
add or remove filesystem layers; unknown runtime metadata prevents comparison.

### Whole-host Linux observations and Oracle budget context (STORY-KPI-001)

Schema `container-2` adds, from the same JSON, top-level `hostObservations`, `boundaries`, `budget` and `qualification`
sections (rendered in `container-resources.md` and the workflow summary). During each idle and traffic window the script
also samples `/proc/stat` (aggregate `cpu` line; busy = all fields except idle and iowait, guest time not double counted)
and `/proc/meminfo`. Host CPU is percent of the whole host between consecutive snapshots (the first snapshot yields no
value; zero-duration, counter-reset and malformed input are `unavailable` with a reason). Host memory reports `MemTotal`,
last `MemAvailable` and used = `MemTotal - MemAvailable`, with mean/peak over the window's samples. Logical CPU count comes
from `os.cpu_count()`. Non-Linux hosts, unreadable counters or parse failures give `unavailable` plus `unavailableReason`,
never zero, and appear in `diagnostics`; the per-service report is unchanged.

Boundaries: `container-stack` per-service Docker figures (percent of one core) are not summed into or subtracted from the
`whole-host-linux` figures. The reporter, Docker CLI calls and the traffic driver share the host and its CPU; the
host-minus-container difference is unattributed, and container memory is never added to host used memory.

Budget: Oracle `VM.Standard.A1.Flex`, Linux ARM64, 2 OCPU and 12 GB (12e9 bytes) for the whole deployment (OS, container
engine, PostgreSQL, backend, frontend, measurement overhead), not a JVM allowance; checked 2026-11-07, source
`docs/ROADMAP.md`; tenancy availability unverified. CPU comparison: busy logical CPUs = host CPU % / 100 x logical CPUs vs
2 (one local logical CPU treated as one OCPU, arithmetic only). Statuses: `within-local-budget`, `over-local-budget`,
`unavailable`; with absolute and percentage headroom when valid. Overall `qualification.status` is always `not-qualified`
here, with `tenancyVerified: false` and `nativeTargetHost: false`; `native-oracle-a1` is reserved for later verified
evidence. Architecture classification: `local-x86-64`, `local-or-ci-arm64` (non-Linux), `non-oracle-native-arm64`
(Linux), `unknown`. Point samples (seconds apart) are not sustained peaks; none of this is capacity evidence.

Earlier ad-hoc observations: `scripts/container-smoke.sh` prints `image_bytes`, `build_ms` and `startup_to_readiness_ms` for a single image; they are not part of the informational reports.

## Populated fixtures and optional local snapshot (STORY-KPI-007)

Offline inputs for later capacity discovery; nothing here runs a sustained load. Code: `backend/src/test/java/perf`
(`SyntheticFixtures`, `SnapshotImporter`, `FixtureLoader`, `FixtureProvenance`). Workload assumptions: `docs/PERFORMANCE_WORKLOAD_PROFILE.md`.

- **Synthetic default (`kpi-fixtures-1`)**: five independent principals (two populated, one empty-granted, one stale, one
  failed/partial) sharing item/recipe IDs and overlapping character IDs, covering Materials, Bank, Characters, bags,
  saved equipment, Builds, recipes and Status sources. Expected results are literals in `SyntheticFixtures.expected()`.
  Account count is coverage, not a capacity target. Default tests: `mvn test -Dtest='SyntheticFixturesTest,SnapshotImporterTest,FixtureProvenanceTest'`
  (no database, no network). Default CI (Surefire `*Test`, disposable PostgreSQL via `DATABASE_URL/USER/PASSWORD`) also runs
  `PopulatedFixtureHttpTest`: the running application, independent synthetic proof keys/sessions, real HTTP reads of Bank,
  Materials, Characters, equipment/inventory, Crafting Profit (with source-specific bank/material protection) and Status, grants,
  and the ordinary-versus-owner boundary (owner configured through `gw2.owner.account-guid`), with hand-written outcomes.
  `PopulatedFixtureIsolationIT` (explicit, local: Surefire only, so name it with `-Dtest`) adds repository-level checks:
  `mvn test -Dtest=PopulatedFixtureIsolationIT`. Both create and drop a `kpi_*` schema, never `public`.
- **Optional local snapshot**: shape `local-postgres-jsonl-1` (`manifest.json` + one `<table>.jsonl` per allowlisted table,
  `to_jsonb(row)` per line, hashes over exact bytes). Opt in with `-Dgw2.perf.snapshot-dir=<ignored dir>` or `GW2_PERF_SNAPSHOT_DIR`;
  absent means *unavailable*. Run `mvn -f backend/pom.xml test -Dtest=PopulatedFixtureIsolationIT -Dgw2.perf.snapshot-dir=<dir>` (Surefire; there is no Failsafe `-Dit.test`): it validates manifest
  version/format, allowlisted names, counts, hashes, UTF-8, schema/publication relationships, remaps account GUIDs, character
  IDs/names and `bound_to` to fake principals, loads into a fresh `kpi_*` schema and writes `target/perf/snapshot-readiness.{json,md}`.
  Unsupported, incomplete or corrupt input fails with a message naming tables and counts only. A clock shift is opt-in
  (`SnapshotImporter.shiftClock`), uniform and reported as fixture preparation; the snapshot is never claimed current.
- **Reports**: `fixtureProvenance` (additive; `schemaVersion` stays `1`) carries kind, version, fixture and expected-result identity,
  topology kind (`synthetic` or `locally-captured-cache`; public-only capture stays `unavailable`), row-count coverage, comparison
  eligibility and `oracleQualification: not-qualified`. Reports without it say `absent`; different identities are incomparable.
  `.github/scripts/performance_summary.py` applies the same rule when choosing a previous report to compare: mismatched kind,
  fixture/expected-result identity or topology gives no delta; identical `synthetic-populated` identities and the historical
  scalable generator (versioned by `fixtureVersion`) still compare; local snapshots never do. The report `commit` is the code
  that ran; the capture's revision is `fixtureProvenance.sourceRevision`.
- Older optional equipment/build coverage (a `parent_generation` behind the CHARACTERS marker) is a valid retained state and is
  imported as is; coverage ahead of the parent marker is rejected.
- Never commit, upload or log a real snapshot; keep it under an ignored path. Default CI never reads it.

## Steady warm capacity discovery (STORY-KPI-002) — decision core

Capacity is a **measurement result**, never a requirement. The reference is exactly **2000 ms** of browser
initiation-to-rendered-usability: a sample is *over* only when strictly greater than 2000 ms (exactly 2000 is not over). It is not a
p95/mean SLA; every over-reference sample is kept.

**Delivered (default Surefire, fake runners, no capacity experiment in CI):**
`mvn -f backend/pom.xml test -Dtest='CapacityCoordinatorTest,CapacityReportTest'`

- `CapacityConfig` (defaults: levels 1,2,4,8,16,32,64; max 64 users; 30 min per profile; 3 confirmations; 1 browser probe context,
  max 2; finite window/drain timeouts), `WorkloadProfile` (browse-heavy / balanced / crafting-context cadence and account/crafting
  mix — **assumptions, not measured averages**; seeded random per-user phases; open-loop `plan(...)` offered independently of
  response completion; local-only actions such as sort/search/checkbox are not journeys and schedule nothing).
- `CapacityCoordinator` over a `StageRunner`: no-load baseline, warmup (kept separate from measured stages), coarse ramp, integer
  refinement between last pass and first breach, then confirmations of the candidate and the adjacent breached level. Outcomes:
  `BOUNDARY_CONFIRMED`, `BELOW_MINIMUM_CONFIRMED`, `UNSTABLE` (interval), `PROVISIONAL` (breach seen, budget ended),
  `CENSORED_NOT_REACHED` (ceiling/budget before any breach — never "capacity = ceiling"), `INVALID`.
  A stage is evidence only if every enabled journey has a browser sample, all content is final usable and correct, and the
  generator was not saturated. Workers are drained before teardown; if they cannot drain the outcome carries `cleanupRequired` and
  isolated resources must be retained.
- `CapacityReport`: JSON (`capacity-1`) + Markdown + a checksummed companion JSONL (`capacity-sample-1`: count, bytes, SHA-256,
  artifact id) with every sample; per-journey min/p50/p90/p95/p99/max, over-reference counts, low-sample warnings, scheduled vs
  achieved/late/missed starts. No GUIDs, names, keys, cookies, bodies or filesystem paths (metadata paths are rejected).

**Runtime (explicit, local-only; never part of default CI):** `backend/src/test/java/perf/CapacityExperimentIT`
starts the real Spring/Flyway/PostgreSQL application on loopback against a fresh `kpi_` schema (never `public`), loads the
fixture through `FixtureLoader`, and starts `frontend/scripts/capacity-probe.mjs`, which serves the **unchanged built
`frontend/dist`** and forwards `/api/` same-origin to that application. Sparse sequential real-Chromium probes (default 1,
max 2 contexts; existing `data-test` markers only) measure initiation to rendered usable page for all eight journeys at every
stage; the open-loop seeded protocol generator (`CapacityStageRunner`) loads the same application with independent HTTP
sessions and account partitions. Browser samples alone decide the boundary; slow protocol samples are retained and reported
separately. A level of L users is `min(probes, L)` browser principals plus protocol users, so probes are inside L. Proof,
account, global and price edges are controlled fakes; any unexpected external call fails the run.

Prerequisites: `DATABASE_URL`/`DATABASE_USER`/`DATABASE_PASSWORD`, `npm ci && npm run build` in `frontend/`, Chromium
(`GW2_BROWSER_PATH` or a discovered Chrome/Chromium). Commands (run in `backend/`):

- Boundary check (2 principals, all journeys, no capacity claim, no timing assertion):
  `mvn test -Dtest=CapacityExperimentIT`
- Experiment: `mvn test -Dtest=CapacityExperimentIT -Dgw2.capacity.mode=experiment` with optional
  `-Dgw2.capacity.profile=browse-heavy|balanced|crafting-context`, `.seed`, `.levels=1,2,4`, `.max-users`, `.window-ms`,
  `.budget-ms`, `.confirmations`, `.probes` (1..2). Output: `backend/target/capacity/capacity-<profile>-<seed>.{json,md,jsonl}`.
- Private snapshot: set `GW2_PERF_SNAPSHOT_DIR` (or the existing system property) to the ignored local directory; otherwise the
  synthetic fixture is used. Reports carry original and prepared identities and the warm policy, never the path.

Warm preparation happens outside every measured window: a single clock delta moves stored timestamps to now (disclosed as
fixture preparation, not evidence the capture was fresh); production freshness checks are untouched. Per-principal results
are compared with the serial no-load baseline (bank/materials/profit fingerprints and counts) as *consistency* evidence, not an
independent economics oracle. Resource observations are reported `unavailable` (no probe is attached to this local run).
Cleanup: workers (protocol, browser, application tasks) are stopped and drained first; the browser process is closed and the
owned schema dropped afterwards. If anything cannot drain the report says `cleanupRequired` and the schema is not asserted away.

Workload semantics (assumptions, configurable): a crafting workflow is 5–30 PROFIT requests (`ALL` scope, the frontend default)
spread within one minute, then one recipe detail (resolving a row of that actor's own result), then the in-game pause. Each user
has one continuous seeded timeline and every measured window is the next slice of it, so phases continue across stages. A Status
visit adds the mounted 5 s poll only until the actor navigates away; checkboxes, sorting, Profit search/limit and tab inspection
issue no requests. Per stage the report's `accounting` lists, separately for journeys and polls, offered/submitted/started/
completed/failed/pending/missed/late/in-flight peak (starts are recorded inside the worker, so executor queueing shows as start
delay), plus actual HTTP calls by status and browser API/poll counts. Any undelivered offered demand marks the stage
generator-saturated and it is not capacity evidence. Browser probes are sparse: by default one sample of every enabled journey
per measured stage, sequential, spread across the window and counted inside L. The browser's HTTP cache is NOT disabled: offline
enforcement uses name-resolution blocking plus request observation (no `context.route`), and the boundary check asserts that a
reload serves build assets from cache. Deterministic icon bytes remain a disclosed limitation.

Limitations: the generator and Chromium share the host with the application and PostgreSQL; logical cores are not Oracle OCPU.
No capacity is claimed until the supervisor runs the representative experiment; cold start, refresh/expiry, fault, restart and
owner overlap remain the separate DRAFT STORY-KPI-008.

### Capacity boundaries: instrumented vs uninstrumented, and report fields (STORY-KPI-002 corrections)

- `backend/pom.xml` enables JaCoCo by default, so default native CI/coverage runs are *instrumented*. Fresh Profit
  measured about 0.9–1.1 s uninstrumented against about 2.7 s instrumented. For capacity/timing runs use the
  separate uninstrumented boundary: `mvn -f backend/pom.xml -Djacoco.skip=true -Dtest=CapacityExperimentIT test`.
  The report records only a boolean `coverageAgentPresent` (no raw JVM arguments or paths). Default CI is unchanged.
- Expired stages are retained: a baseline/warmup/measured stage that crosses the warm deadline keeps all its samples,
  marked with an `expired:` invalid reason; it is never counted as passing, breaching or confirmed capacity.
- Provenance: `sourceRevision` is the fixture capture revision; `executingCommit` is the code that ran;
  `runStartedUtc` is captured at start (`runFinishedUtc` at the end); `configIdentity` is a SHA-256 of the normalized
  configuration; also `sharedCatalogIdentity`, `expectedResultIdentity` (unavailable for private input) and aggregate coverage counts.
- Warm preparation rewrites publication and operational timestamps only; the business timestamp `characters.created_at_gw` is preserved.
- Samples carry a nullable `firstUsableMillis` (possibly saved/retained content) separate from the final `durationMillis`.
- `browserProbeCalls` counts actual probe journey calls; browser HTTP/poll counts are whole-window deltas (task polls and
  `/api/system/status` polls). An unreadable counters read invalidates the stage instead of counting zero.
- Known limits: render correlation with the matched response is implemented for the roster count only; other journeys
  still rely on the existing usable markers plus exact request matching.
