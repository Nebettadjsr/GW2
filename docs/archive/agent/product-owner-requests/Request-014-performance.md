# PO Request — Application Performance Monitoring & Hosting Resource Requirements

## Goal

Establish automated performance and resource measurements for the GW2 application to support future decisions about free-tier or low-cost hosting providers.

The application is still under development. No hosting provider has been selected, and deployment is not part of this request.

The measurements should help determine the application's actual resource requirements and identify performance changes as development progresses.

## Milestone Priority

**Implementation must begin in the CURRENT milestone, before its final Health Review is completed.**

The Planner must account for this request when reviewing the remaining milestone work and schedule the initial performance measurement capability accordingly.

Do not defer the entire request to a later milestone merely because deployment is planned for the future.

The first measurements should be available by the current milestone's Health Review, allowing subsequent development to establish a performance history.

## Required KPIs

Measure the following metrics for the GW2 target application wherever technically possible:

| KPI | Measurement |
|---|---|
| Peak RAM | Maximum application process memory consumption |
| Idle RAM | Memory consumption while the application is idle |
| CPU Time | CPU time consumed by individual operations |
| CPU Utilization | CPU usage under different workloads |
| Response Time (p95) | 95th-percentile HTTP response latency |
| Throughput | Requests processed per second |
| Database Storage | Database size, including tables and indexes |
| Database Reads/Writes | Read and write operations, including affected rows where measurable |
| Database Connections | Number of active and peak concurrent connections |
| Network Traffic | Incoming and outgoing data volume |
| Background Jobs | Execution time, CPU usage, memory usage, and execution frequency |
| Container Startup | Time from container start until application readiness |
| Container Image Size | Size of the deployable container image |

### Container-Specific Measurements

**Container-specific KPIs must only be implemented and measured once the project actually uses containers.**

Do not introduce Docker or container infrastructure solely to satisfy this request.

Until containers exist, collect equivalent application-level measurements where possible, including process memory, CPU time, execution duration, and database activity.

Once containerization is introduced, extend the existing measurements with actual container resource consumption, startup behavior, and image size.

Clearly distinguish process measurements from container measurements. Do not present estimates as measured container values.

## Benchmark Workloads

Measure representative operations of the actual GW2 application, including:

- Crafting profitability calculations with small and large input datasets.
- Full and incremental account synchronization.
- Global GW2 API data synchronization and price refreshes.
- Representative application database reads and writes.
- Typical HTTP API requests.
- Idle application resource consumption.
- Concurrent request workloads, where practical.

Use deterministic fixtures and representative test datasets wherever possible to keep results comparable.

Measurements must concern the target application, **not the AI development orchestrator, coding agents, token consumption, or workflow execution overhead**.

## GitHub Actions Integration

Implement a dedicated GitHub Actions performance measurement workflow.

Requirements:

1. Execute automatically as part of the normal GitHub development pipeline.
2. Allow manual execution through GitHub Actions.
3. Display measurement results directly in the GitHub Actions job summary.
4. Store detailed reports as GitHub Actions artifacts where useful.
5. Include the Git commit, execution timestamp, benchmark workload, and relevant test environment information.
6. Keep the implementation lightweight and reuse existing testing infrastructure wherever practical.
7. Do not introduce separate monitoring infrastructure or duplicate local report files unless technically necessary.

### Non-Blocking Execution — Mandatory

**Performance measurements are informational only and must have no consequences for story acceptance or delivery.**

- A failed performance test must not fail the main CI pipeline.
- A performance regression must not block a pull request, merge, commit, or story completion.
- Missing measurements or unavailable benchmark infrastructure must not block development.
- Performance results must not influence the pass/fail verdict of the story being implemented.
- Performance failures should remain visible in the GitHub report with useful diagnostics.
- Existing functional tests, security checks, and other established quality gates must remain unaffected.

The performance workflow should be independently non-blocking, including when the benchmark job itself encounters an execution error.

## Reporting and Historical Comparison

Reports should show measured values and, when previous compatible measurements are available, their differences.

Historical comparisons must account for relevant differences in test data, workload, and execution environment.

Use GitHub's existing reporting and artifact capabilities initially. A separate metrics database, monitoring service, or continuously running infrastructure is not required.

Preserve enough information to support future hosting-capacity assessments, while keeping the implementation simple.

## Architecture and Documentation

Update the relevant architecture and quality documentation to establish:

- The goal of eventual free-tier or low-cost hosting.
- The requirement to measure actual application resource consumption.
- A preference for container-compatible, provider-independent deployment where practical.
- The distinction between current application measurements and future container measurements.
- The informational, non-blocking status of performance benchmarks.

Cloudflare, Oracle Cloud, and other providers may serve as future comparison candidates, but this request must not commit the project to a particular provider.

## Constraints

- No immediate deployment.
- No mandatory hosting-provider selection.
- No premature containerization.
- No unnecessary refactoring of existing application functionality.
- No paid services or additional monitoring infrastructure.
- No scheduled daily or nightly benchmark runs independent of normal development activity.
- No performance-based rejection of otherwise completed stories.
- Maintain separation between functional quality assurance and informational performance measurement.

## Expected Outcome

By the end of the current milestone, the project should have an initial, automatically executed, non-blocking performance measurement workflow with results visible in GitHub Actions.

As development continues, the measurements should provide increasingly useful evidence for future resource planning and hosting-provider selection.

Container-specific measurements will be added when the corresponding container infrastructure becomes available.

## Status

RESOLVED

## Planner Resolution

2026-10-21: Recorded the intended application measurement, informational workflow and deferred actual-container measurement requirements in `docs/TARGET_ARCHITECTURE.md` §17 and the current-milestone delivery requirement in `docs/ROADMAP.md` §10. Created `agent/stories/STORY-PERF-005-application-resource-benchmarks.md` and `agent/stories/STORY-PERF-006-nonblocking-performance-workflow.md`, and requested both backlog additions. These artifacts cover the requested planning scope; RESOLVED records planning coverage, not implementation or measured results.