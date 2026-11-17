# Software quality metrics

## Policy

The Java backend and Vue/TypeScript frontend have a long-term target of at least 90% line and branch coverage. This is an informational warning target, not a build gate. Meaningful behavior assertions take priority over a percentage. Never remove source from measurement, weaken assertions or add empty tests to improve the number.

The GitHub Actions `Coverage KPI report` job publishes per-module/package rates and a weighted combined view. Missing measurements remain unavailable, rather than zero. The default suites and measurement configuration are defined in [Test strategy](TEST_STRATEGY.md) and `.github/workflows/ci.yml`; the retired Python harness is outside the current application coverage scope.

## Verified CI baseline

Source: [PR #242](https://github.com/Nebettadjsr/GW2/pull/242) test commit `5aa6c2bcf9168397e470a6cc00ae80645cfb7497`, successful [CI run 44032869374](https://github.com/Nebettadjsr/GW2/actions/runs/44032869374), generated 17 November 2026. Downloaded and inspected that day: `coverage-reports` artifact `14306345927`, `coverage-kpi/coverage-kpi.json`, cross-checked with its `coverage-input/java-coverage/jacoco.xml`. These numbers describe that commit. Later source, test or browser-journey changes require their own evidence; do not infer a new result from this baseline.

| Area | Line coverage | Branch coverage |
| --- | --- | --- |
| Java backend | 91.36% (10,517 / 11,512) | 81.42% (5,376 / 6,603) |
| Frontend TypeScript | 96.43% (4,026 / 4,175) | 86.48% (4,209 / 4,867) |
| Combined weighted | 92.71% (14,543 / 15,687) | 83.57% (9,585 / 11,470) |

Both application modules exceed the line target; both still have branch gaps. A combined percentage does not establish adequate coverage of any individual critical path.

## Browser journey evidence

[STORY-COV-006 / issue #243](https://github.com/Nebettadjsr/GW2/issues/243) adds a separate
controlled real-browser gate: `npm run smoke:ci` invokes
`node scripts/run-desktop-journeys.mjs` after the frontend build in the required frontend
CI job. Its named journeys cover public reading, connection, failure/recovery, account
switching, the supported-tool viewport boundary and reservations. The synthetic API origin
does not establish real backend/SQL/upstream behavior or production capacity.

Report journey/step outcomes, exact source revision, browser/build scope and available bounded
failure diagnostics. Default output is `frontend/quality-reports/desktop-journeys/`, overridable
with `GW2_SMOKE_OUTPUT`; CI uploads available results/screenshots/traces with `if: always()`.
A failed journey fails the frontend job. Missing or incomplete artifacts are not a passed run.

These are **journey outcomes, not line/branch coverage**. Do not add browser step counts to
Vitest test counts or JaCoCo/Vitest covered-line totals, and do not claim that controlled
journeys increased the percentage without a separately measured coverage change. The
existing KPI baseline above remains unchanged. Integration and journey results for #243 are
pending the implementation's actual run; no pass count, new percentage or deployment is
asserted here. [Test strategy §12.5](TEST_STRATEGY.md#125-automatic-desktop-user-journeys)
owns commands, viewport checks and evidence limits.

## Current improvement candidates

Selected package figures from the same artifact:

| Area | Lines | Branches | Reason to inspect |
| --- | --- | --- | --- |
| Backend `web.access` | 95.27% | 92.14% | Account route mapping and session/recovery coverage added in #111; named remaining defensive gaps below. |
| Backend `sync` | 81.40% | 75.67% | Many remaining lines and upstream/publication failure branches. |
| Backend `application.access` | 95.59% | 80.82% | Account proof, expiry and access-policy branches. |
| Backend `parser` | 98.12% | 73.81% | Malformed and optional payload branches; prioritize meaningful input cases. |
| Backend `infra.db` | 79.17% | 65.38% | Connection/pool lifecycle and failure handling. |
| Frontend `characters` | 95.95% | 78.19% | Equipment/build/inventory state and unavailable-data branches. |
| Frontend `account` | 94.04% | 81.48% | Connection restoration, switching and deletion behavior. |
| Frontend `home` | 67.57% | 88.46% | Inspect uncovered behavior before adding static-content assertions. |

[Issue #21](https://github.com/Nebettadjsr/GW2/issues/21) remains the broader request. Refresh class/method counters from the current artifact before choosing another slice. A low package percentage alone does not justify a production rewrite.

## Account-boundary improvement (#111)

Independent QA added 72 deterministic HTTP cases using the production guard registration,
session controller and access/session services with a controlled proof port and clock.
The focused check passed 103 cases, including 31 unchanged adjacent cases; the full CI run
above then passed backend/frontend suites, build and workflow checks. Production code and
existing assertions did not change.

Compared with [baseline CI 43821055103](https://github.com/Nebettadjsr/GW2/actions/runs/43821055103)
at `09d3443f1cefb7784e116fe4f097738f15299809`:

| Boundary | Lines before / after | Branches before / after |
| --- | --- | --- |
| `web.access` | 251 / 282 of 296 | 166 / 211 of 229 |
| `AccountAccessHttp` | 32 / 33 of 33 | 28 / 44 of 46 |
| `AccountAccessInterceptor` | 121 / 128 of 135 | 101 / 121 of 130 |
| `AccountAccessApiController` | 53 / 75 of 76 | 15 / 24 of 25 |
| `RequestAccountContext` | 7 / 8 of 8 | 2 / 2 of 2 |

The scoped operation mapping is now fully covered (16/16 lines, 15/15 branches).
Tests verify refusal before dispatch, account/context isolation, minimal grants and URL
restrictions, absolute expiry, renewal, logout, origin/cookie behavior, transient outages
and deletion-busy installation. They make no claim about live ArenaNet availability or
production capacity.

Remaining boundary gaps include defensive null HTTP inputs, the post-proof renewal
context-mismatch catch, interceptor error/lifecycle cleanup and query-parser edges.
Owner-access and deletion-controller coverage also remain candidates where a meaningful
behavioral case is missing; do not reactivate retired unscoped paths to chase percentages.

## Reading and refreshing reports

Backend XML/HTML reports are in `backend/target/site/jacoco/`; frontend reports are in `frontend/coverage/`. CI keeps `java-coverage`, `frontend-coverage` and the combined `coverage-reports` artifacts. The summary includes generated UTC time, source revision, module/package counts and the warning-only policy.

For a scoped local Java check, run `mvnw.cmd -Dtest=<related classes> test jacoco:report` from `backend/`. Its counters describe only those selected tests and must not replace the complete CI baseline. CI runs the full default suites. `python .github/scripts/publish_coverage_kpi.py` combines supplied report inputs; it does not run tests.

Replace the baseline only with a later successful CI run on an identified clean commit. Record the run, artifact, raw counts and measurement scope. Preserve a dated old snapshot in [the quality archive](archive/quality/QUALITY_METRICS-2026-10-25.md) when useful; do not retain obsolete tables as current targets.
