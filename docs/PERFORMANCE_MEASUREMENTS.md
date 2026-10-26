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

Needs Docker with Compose v2 and Python 3 (no packages). Output: `container-resources.json` (schema `container-1`,
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

Earlier ad-hoc observations: `scripts/container-smoke.sh` prints `image_bytes`, `build_ms` and `startup_to_readiness_ms` for a single image; they are not part of the informational reports.
