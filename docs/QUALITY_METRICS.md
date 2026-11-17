# Software quality metrics

## Policy

The Java backend and Vue/TypeScript frontend have a long-term target of at least 90% line and branch coverage. This is an informational warning target, not a build gate. Meaningful behavior assertions take priority over a percentage. Never remove source from measurement, weaken assertions or add empty tests to improve the number.

The GitHub Actions `Coverage KPI report` job publishes per-module/package rates and a weighted combined view. Missing measurements remain unavailable, rather than zero. The default suites and measurement configuration are defined in [Test strategy](TEST_STRATEGY.md) and `.github/workflows/ci.yml`; the retired Python harness is outside the current application coverage scope.

## Verified CI baseline

Source: STORY-COV-006 candidate `8e3942c5994addaf48e39e1449880be09b065864`, successful
[CI run 44135086310](https://github.com/Nebettadjsr/GW2/actions/runs/44135086310), generated
17 November 2026. This PR-triggered run measured GitHub merge revision
`c227a04bf1d09850818c9ba918ea486adf47d3ad`, as recorded in `coverage-kpi.json`.
Downloaded and inspected that day: `coverage-reports` artifact `14350698995`, with its
raw JaCoCo counters cross-checked. These numbers describe that identified run, not later
review corrections. Final-head CI and owner acceptance still require their own evidence.

| Area | Line coverage | Branch coverage |
| --- | --- | --- |
| Java backend | 91.40% (10,522 / 11,512) | 81.42% (5,376 / 6,603) |
| Frontend TypeScript | 96.43% (4,053 / 4,203) | 86.49% (4,244 / 4,907) |
| Combined weighted | 92.75% (14,575 / 15,715) | 83.58% (9,620 / 11,510) |

Both application modules exceed the line target; both still have branch gaps. A combined percentage does not establish adequate coverage of any individual critical path.

## Browser journey evidence

[STORY-COV-006 / issue #243](https://github.com/Nebettadjsr/GW2/issues/243) implements a separate
controlled real-browser gate: `npm run smoke:ci` invokes
`node scripts/run-desktop-journeys.mjs` after the frontend build in the required frontend
CI job. Its named journeys cover public reading, connection, failure/recovery, account
switching, the supported-tool viewport boundary and reservations. The synthetic API origin
does not establish real backend/SQL/upstream behavior or production capacity.

Report journey/step outcomes, exact source revision, browser/build scope and available bounded
failure diagnostics. Default output is `frontend/quality-reports/desktop-journeys/`, overridable
with `GW2_SMOKE_OUTPUT`; CI uploads available results/screenshots/traces as
`desktop-browser-journeys` with `if: always()` and seven-day retention.
A failed journey fails the frontend job. Missing or incomplete artifacts are not a passed run.

These are **journey outcomes, not line/branch coverage**. Do not add browser step counts to
Vitest test counts or JaCoCo/Vitest covered-line totals, and do not claim that controlled
journeys increased the percentage without a separately measured coverage change. The
existing KPI baseline above remains unchanged. [Test strategy §12.5](TEST_STRATEGY.md#125-automatic-desktop-user-journeys)
owns commands, viewport checks and evidence limits.

Independent local QA on `2d9e389760854fcf69ed46eea46ce4be266e872f` passed 27 focused
automated checks, the fresh type-checking production build and all six curated controlled
browser journeys. These are scoped local results, not a new full-suite coverage measurement.
CI run `44135086310` also passed its Chromium installation, browser-journey and evidence-upload
steps for the earlier identified candidate. Subsequent review identified in-flight Profit and
Ecto resize-state gaps requiring additional regressions and correction; the earlier six-journey
pass does not establish their resolution. Final-head CI, review closure and owner visual
acceptance remain pending. No merge, deployment or real-upstream success is implied.

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
