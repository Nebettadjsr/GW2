# Software quality metrics

## Policy

The Java backend and Vue/TypeScript frontend have a long-term target of at least 90% line and branch coverage. This is an informational warning target, not a build gate. Meaningful behavior assertions take priority over a percentage. Never remove source from measurement, weaken assertions or add empty tests to improve the number.

The GitHub Actions `Coverage KPI report` job publishes per-module/package rates and a weighted combined view. Missing measurements remain unavailable, rather than zero. The default suites and measurement configuration are defined in [Test strategy](TEST_STRATEGY.md) and `.github/workflows/ci.yml`; the retired Python harness is outside the current application coverage scope.

## Verified CI baseline

Source: STORY-COV-007 source/test revision `14a3054e31588499e197f8d110facffeb69538f2`,
successful [CI run 44260769452](https://github.com/Nebettadjsr/GW2/actions/runs/44260769452),
generated 18 November 2026. The explicitly dispatched run measured the material-catalog
publication tests and minimal fetch seam, as recorded in `coverage-kpi.json`. Downloaded
and inspected that day: `coverage-reports` artifact `14408340797`, with raw JaCoCo counters
cross-checked. These numbers describe that identified run. Later documentation-only commits
do not constitute another measured result; final-head CI and owner acceptance remain separate.

| Area | Line coverage | Branch coverage |
| --- | --- | --- |
| Java backend | 91.69% (10,557 / 11,514) | 81.58% (5,387 / 6,603) |
| Frontend TypeScript | 96.51% (4,043 / 4,189) | 86.50% (4,216 / 4,874) |
| Combined weighted | 92.98% (14,600 / 15,703) | 83.67% (9,603 / 11,477) |

Both application modules exceed the line target; both still have branch gaps. A combined percentage does not establish adequate coverage of any individual critical path.

## Browser journey evidence

[STORY-COV-006 / issue #243](https://github.com/Nebettadjsr/GW2/issues/243) implements a separate
controlled real-browser gate: `npm run smoke:ci` invokes
`node scripts/run-desktop-journeys.mjs` after the frontend build in the required frontend
CI job. Its named journeys cover public reading, connection, failure/recovery, account
switching, narrow-screen notice/scrolling and reservations. The synthetic API origin
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

**Warning-only evidence (18 November):** independent local QA on `1d017fc` passed 30 focused
checks, the fresh type-checking production build and all ten curated browser journeys.
CI `44252493662` also passed; downloaded browser artifact `14404681915` confirms ten passes,
both child commands exiting zero and no timeout. Initial narrow tools load normally, actual
document scrolling reaches the rightmost tool action, pending Profit and Ecto state survive
resize without duplicate work, and Help/Delete confirmation remains modal until ordinary
close/cancel. No deletion is submitted. Public reading, recovery, account isolation and
reservation-to-Profit journeys remain covered.

The 18 November owner revision explicitly supersedes prior blocking/no-request and
automatic-modal-close assertions. Independent QA replaced them with visible-content,
normal-loading and modal-retention assertions, then corrected a resize-render race by
waiting for the notice to detach before the existing exact-1024px absence assertion.
This adds no retries or threshold relaxation. Production source is unchanged at QA
correction `0c24106`, which independently passes the same
30 focused checks, fresh build and ten browser journeys. Removing obsolete admission/width-gate
source also changes measured denominators; the small percentage change is not evidence
of broader browser coverage. Final-head CI/review and owner visual acceptance remain
required; [PR #244](https://github.com/Nebettadjsr/GW2/pull/244) owns live status.
No merge, deployment or real-upstream success is implied by these measurements.

## Current improvement candidates

Selected package figures from the same artifact:

| Area | Lines | Branches | Reason to inspect |
| --- | --- | --- | --- |
| Backend `web.access` | 95.27% | 92.14% | Account route mapping and session/recovery coverage added in #111; named remaining defensive gaps below. |
| Backend `sync` | 83.22% | 76.44% | Material catalog publication covered in #245; active metadata/failure paths remain. |
| Backend `application.access` | 95.59% | 80.82% | Account proof, expiry and access-policy branches. |
| Backend `parser` | 98.12% | 73.81% | Malformed and optional payload branches; prioritize meaningful input cases. |
| Backend `infra.db` | 79.17% | 65.38% | Connection/pool lifecycle and failure handling. |
| Frontend `characters` | 95.95% | 78.19% | Equipment/build/inventory state and unavailable-data branches. |
| Frontend `account` | 94.62% | 81.64% | Connection restoration, switching and deletion behavior. |
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

## Material-catalog publication improvement (#245)

Independent QA added five real-PostgreSQL publication cases and one global-refresh
failure/status/retry case. They execute ordered replacement and scoped repository reads,
stale cleanup, unchanged account quantities/markers, upstream failure, empty refusal,
real item-insert and late-delete failures, rollback and explicit recovery. SQL faults
are injected only into generated disposable schemas. No existing assertion was weakened.
Claude added only the package-private `PublicArrayFetcher` overload/public delegation;
the endpoint, SQL and transaction remain unchanged.

Before: merged `aa66d20`, full CI `44256050562`. After: `14a3054`, full CI `44260769452`.
Downloaded raw counters establish:

| Scope | Lines before / after | Branches before / after |
| --- | --- | --- |
| `MaterialCategorySync` | 8 / 45 → 45 / 47 | 4 / 12 → 12 / 12 |
| `sync` package | 1,580 / 1,941 → 1,617 / 1,943 | 787 / 1,040 → 795 / 1,040 |
| Backend overall | 10,520 / 11,512 → 10,557 / 11,514 | 5,376 / 6,603 → 5,387 / 6,603 |

The material class is now 95.74% lines / 100% branches. Its remaining two lines are the
public no-argument delegation to the real HTTP client; deliberately calling live ArenaNet
would not improve this offline transaction evidence. `GlobalDataRefreshService` remains
29/29 lines and 3/6 branches: its additional failure story improves behavior evidence
without claiming another measured branch. The other `refreshAll` decisions remain candidates.
The broader backend branch target is not met; #21 remains open. Browser coverage is unchanged.

## Reading and refreshing reports

Backend XML/HTML reports are in `backend/target/site/jacoco/`; frontend reports are in `frontend/coverage/`. CI keeps `java-coverage`, `frontend-coverage` and the combined `coverage-reports` artifacts. The summary includes generated UTC time, source revision, module/package counts and the warning-only policy.

For a scoped local Java check, run `mvnw.cmd -Dtest=<related classes> test jacoco:report` from `backend/`. Its counters describe only those selected tests and must not replace the complete CI baseline. CI runs the full default suites. `python .github/scripts/publish_coverage_kpi.py` combines supplied report inputs; it does not run tests.

Replace the baseline only with a later successful CI run on an identified clean commit. Record the run, artifact, raw counts and measurement scope. Preserve a dated old snapshot in [the quality archive](archive/quality/QUALITY_METRICS-2026-10-25.md) when useful; do not retain obsolete tables as current targets.
