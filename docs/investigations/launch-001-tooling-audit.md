# STORY-LAUNCH-001 tooling audit

Every tracked file in the audited areas, with its disposition. "Retained replacement" names the check that still
covers any behavior that remains required.

## frontend/scripts

| File | Disposition | Reason / retained replacement |
|---|---|---|
| interaction-record.mjs, interactionRecorder.mjs, __tests__/interactionRecorder.spec.mjs | Removed | Recording/replay tooling; no correctness check imports it |
| journey-runner.mjs, journeys.mjs, journeyCommon.mjs, journeySyntheticApp.mjs, test-interaction-browser.mjs, examples/interaction-synthetic.scenario.json, __tests__/journeyCommon.spec.mjs, __tests__/journeyRunner.browser.spec.mjs | Removed | Capacity journeys and their dedicated tests; behavior coverage stays in the `smoke:*` checks |
| profit-page-perf.mjs, profitTiming.mjs, __tests__/profitTiming.spec.mjs | Removed | Timing injector and its tests; no behavior assertion beyond timing |
| recordingProxy.mjs | Moved to requestObserver.mjs | Still needed by `icon-live-check.mjs` to count backend requests; per-request timing removed, API otherwise unchanged |
| icon-live-check.mjs | Kept, refactored | Imports requestObserver.mjs |
| bundleFreshness.mjs (+ spec), resolveBrowserPath.mjs, stubOrigin.mjs, upstream-proxy.mjs | Kept | Shared functional helpers |
| all `*-browser-smoke.mjs`, `*-live-smoke.mjs`, `profit-envelope-live-check.mjs`, `stack-browser-smoke.mjs`, `inventory-refresh-smoke.mjs` | Kept | Focused correctness/smoke checks |
| `package.json` scripts `perf:profit-page`, `record:interaction`, `journey*`, `test:journey-browser`, `test:interaction-browser` | Removed | Pointed only at removed files |

## scripts and .github

| File | Disposition |
|---|---|
| scripts/guest_observe.py, host_resources.py, container_measure.py | Removed (resource collectors) |
| .github/scripts/test_guest_observe.py, test_host_resources.py | Removed with their collectors |
| .github/workflows/performance.yml, .github/scripts/performance_summary.py, test_performance_summary.py | Removed (informational benchmark workflow/report) |
| scripts/compose-smoke.sh, container-smoke.sh, frontend-container-smoke.sh | Kept (stack/container smoke) |
| scripts/investigations/*.py | Kept (historical investigation summaries; not referenced by active docs) |
| other .github/scripts and workflows | Kept |

## backend/src/test/java/perf

| File | Disposition |
|---|---|
| ApplicationBenchmarkBoundaryIT, ApplicationBenchmarkHarness, ApplicationResourceBenchmarkIT | Removed |
| ConnectionObserver, DatabaseMetrics, DatabaseProbe, DatabaseProbeTest, Metric, ProcessProbe, Stats | Removed (benchmark-only; DatabaseProbe is used by no correctness or smoke check) |
| MeasurementReport, MeasurementReportTest | Removed (report generator) |
| FixtureLoader, FixtureProvenance, FixtureSnapshot, SnapshotImporter, SnapshotTestSupport, SyntheticFixtures | Moved to `support.fixture` (test-only fixture support) |
| SyntheticFixturesTest, FixtureProvenanceTest, SnapshotImporterTest, PopulatedFixtureHttpTest, PopulatedFixtureIsolationIT | Moved to `support.fixture` |

Ownership boundary: `support.fixture` holds test-only populated-fixture and account-isolation support; nothing in it is
benchmark-related and production code never imports it.

## Test changes

- `FixtureProvenanceTest`: the report-compatibility test became `absentProvenanceSaysAbsentAndIsNeverComparable`; the synthetic test asserts the same provenance fields directly on `FixtureProvenance.of(...)` instead of through `MeasurementReport`. All comparability assertions are unchanged.
- `PopulatedFixtureIsolationIT`: the optional local-snapshot test no longer writes a readiness report; it still imports the snapshot into a fresh schema.
- `FullCatalogSyncTest`: the `resourceEvidence_...` test (heap/RSS sampling, printed timings) became `multiBatchCatalog_requestsEveryDetailBatchAndStoresEveryItem`; both correctness assertions (21 requests, 4000 stored items) are kept.
- Removed with their tooling: MeasurementReportTest, DatabaseProbeTest, interactionRecorder/journeyCommon/journeyRunner/profitTiming specs and the Python collector/summary tests.
