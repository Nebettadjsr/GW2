## Story ID

STORY-PERF-006

## Title

Publish nonblocking application performance reports in GitHub Actions

## Status

BLOCKED

## Milestone

milestone-06

## Goal

Run the application benchmarks automatically with normal development and on demand, with visible informational results available before the Phase 6 health review.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md, Phase 6 performance measurement exit criterion.
- docs/TARGET_ARCHITECTURE.md section 17, Application resource measurements.
- docs/TEST_STRATEGY.md sections 26 and 36 (functional gate ownership).

## Context

STORY-PERF-005 supplies benchmark workloads and report data. The Product Owner requires a dedicated GitHub Actions workflow whose failures never block feature delivery.

## Acceptance Criteria

1. Add a dedicated performance workflow triggered automatically by normal development activity and manually through GitHub Actions. It invokes the STORY-PERF-005 workloads without requiring an account key, live ArenaNet access, display or prefilled local database.
2. Publish measured values, unavailable KPIs and benchmark errors with useful diagnostics in the GitHub Actions job summary. Retain detailed reports as artifacts when useful, identified by commit, timestamp, workload, fixture and environment.
3. Show differences only against compatible previous reports and explain absent or incompatible history. Do not imply that process-level measurements are container measurements.
4. A benchmark failure, regression, unavailable infrastructure or missing report does not fail the main CI pipeline or impose a required status check on pull requests, merges, commits or story acceptance. Functional/security gates remain independent and unchanged.
5. Keep the workflow lightweight, with no daily/nightly schedule, extra monitoring service or duplicated local reporting store. Document the reporting workflow and its limits for the Phase 6 health review.

## Required Tests

- QA verifies the workflow trigger configuration, informational error path, summary/artifact metadata and compatibility comparison using controlled sample reports.
- Exercise a successful and failing workflow/report path without asserting a performance threshold; verify the established functional CI gate remains unaffected.

## Constraints

- CI changes here are explicitly required by the Product Owner's performance request; do not modify the agent development workflow.
- QA owns tests and fixtures; the coding agent satisfies QA checks without authoring tests.
- No container infrastructure or hosting-provider selection.

## Dependencies

STORY-PERF-005.

## Definition of Done

- Automatic and manual runs publish informational application measurements and diagnostics without blocking delivery; the first available result can inform the Phase 6 health review.
- The CI gate is green (verified by the pipeline after acceptance).

## Result

Not started.

## Blockers

Waiting on dependencies: depends on STORY-PERF-005, which is not DONE (currently TODO)
