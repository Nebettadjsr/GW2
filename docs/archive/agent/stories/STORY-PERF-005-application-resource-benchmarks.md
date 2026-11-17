## Story ID

STORY-PERF-005

## Title

Measure representative application process and database workloads

## Status

TODO

## Milestone

milestone-06

## Goal

Provide reproducible, lightweight measurements of the target application's resource use for hosting-capacity assessment.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md, Phase 6 performance measurement exit criterion.
- docs/TARGET_ARCHITECTURE.md section 17, Application resource measurements.
- docs/TEST_STRATEGY.md sections 26, 34 and 36 (test-quality and performance evidence boundaries).
- docs/QUALITY_METRICS.md, Test coverage KPI and Generation and review.

## Context

The Product Owner requests application measurements before the Phase 6 health review. Containers do not yet exist, so initial evidence concerns processes, HTTP operations, background jobs and database activity; it must be labeled as such.

## Acceptance Criteria

1. Establish deterministic or controlled representative workloads for small/large Crafting Profit calculations, full/incremental account sync, global data and price refresh, representative database reads/writes, typical HTTP requests, idle operation and practical concurrent requests. Use fakes/fixtures where CI cannot access a real account, network or seeded database; report any resulting representativeness limits.
2. Capture peak and idle application process memory, operation CPU time, workload CPU utilization, HTTP p95 latency/throughput, database storage including indexes, database reads/writes and affected rows where measurable, active/peak connections, network ingress/egress and background-job duration/CPU/memory/frequency where technically possible. Mark each unavailable KPI with a reason; do not invent a value.
3. Emit machine-readable results with commit, timestamp, workload/fixture version, environment, units and measurement boundaries so compatible runs can be compared. Distinguish process measurements from future container measurements.
4. Keep benchmarks separate from functional acceptance: errors or regressions produce diagnostics but no story, merge or CI gate rejection. Do not introduce paid services, monitoring infrastructure, containers or a scheduler solely for benchmarks.
5. Document how to reproduce the workloads and interpret their measurement limits without claiming measured hosting capacity.

## Required Tests

- QA verifies that fixture workloads exercise the intended application boundaries and that sample results have correct units, metadata and unavailable-value handling.
- QA checks measured p95/throughput and resource aggregation against independently known sample data; no numeric performance threshold or full regression run is required.
- Review affected coverage; benchmarks do not substitute for behavior tests.

## Constraints

- Measure the GW2 target application, never the agent/workflow runtime.
- No external account key, network access, display or local database contents required by the CI-capable workloads.
- QA owns tests/fixtures and concrete verification design; coding satisfies the QA plan without authoring tests.
- No container-specific values before actual container infrastructure exists.

## Dependencies

None.

## Definition of Done

- Representative measurement workloads and labeled results are reproducible with documented limits; QA's checks pass.
- The CI gate is green (verified by the pipeline after acceptance).

## Result

Not started.

## Blockers

None.
