# Software quality metrics

## Policy

The Java backend and Vue/TypeScript frontend have a long-term target of at least 90% line and branch coverage. This is an informational warning target, not a build gate. Meaningful behavior assertions take priority over a percentage. Never remove source from measurement, weaken assertions or add empty tests to improve the number.

The GitHub Actions `Coverage KPI report` job publishes per-module/package rates and a weighted combined view. Missing measurements remain unavailable, rather than zero. The default suites and measurement configuration are defined in [Test strategy](TEST_STRATEGY.md) and `.github/workflows/ci.yml`; the retired Python harness is outside the current application coverage scope.

## Verified CI baseline

Source: clean `master` commit `09d3443f1cefb7784e116fe4f097738f15299809`, successful [CI run 43821055103](https://github.com/Nebettadjsr/GW2/actions/runs/43821055103), generated 16 November 2026. Downloaded and inspected on 17 November 2026: `coverage-reports` artifact `14204318123`, `coverage-kpi/coverage-kpi.json`, cross-checked with its `coverage-input/java-coverage/jacoco.xml`. These are results for that commit, not claims about untested future changes.

| Area | Line coverage | Branch coverage |
| --- | --- | --- |
| Java backend | 91.07% (10,484 / 11,512) | 80.71% (5,329 / 6,603) |
| Frontend TypeScript | 96.43% (4,026 / 4,175) | 86.48% (4,209 / 4,867) |
| Combined weighted | 92.50% (14,510 / 15,687) | 83.16% (9,538 / 11,470) |

Both application modules exceed the line target; both still have branch gaps. A combined percentage does not establish adequate coverage of any individual critical path.

## Current improvement candidates

Selected package figures from the same artifact:

| Area | Lines | Branches | Reason to inspect |
| --- | --- | --- | --- |
| Backend `web.access` | 84.80% | 72.49% | Account route mapping, session/recovery and refusal behavior; prepared issue #111. |
| Backend `sync` | 81.40% | 75.67% | Many remaining lines and upstream/publication failure branches. |
| Backend `application.access` | 93.53% | 79.45% | Account proof, expiry and access-policy branches. |
| Backend `parser` | 98.12% | 73.81% | Malformed and optional payload branches; prioritize meaningful input cases. |
| Backend `infra.db` | 79.17% | 65.38% | Connection/pool lifecycle and failure handling. |
| Frontend `characters` | 95.95% | 78.19% | Equipment/build/inventory state and unavailable-data branches. |
| Frontend `account` | 94.04% | 81.48% | Connection restoration, switching and deletion behavior. |
| Frontend `home` | 67.57% | 88.46% | Inspect uncovered behavior before adding static-content assertions. |

[Issue #21](https://github.com/Nebettadjsr/GW2/issues/21) remains the broader request. [Issue #111](https://github.com/Nebettadjsr/GW2/issues/111) is the prepared account-boundary slice. Refresh class/method counters from the current artifact before choosing exact cases. A low package percentage alone does not justify a production rewrite.

## Reading and refreshing reports

Backend XML/HTML reports are in `backend/target/site/jacoco/`; frontend reports are in `frontend/coverage/`. CI keeps `java-coverage`, `frontend-coverage` and the combined `coverage-reports` artifacts. The summary includes generated UTC time, source revision, module/package counts and the warning-only policy.

For a scoped local Java check, run `mvnw.cmd -Dtest=<related classes> test jacoco:report` from `backend/`. Its counters describe only those selected tests and must not replace the complete CI baseline. CI runs the full default suites. `python .github/scripts/publish_coverage_kpi.py` combines supplied report inputs; it does not run tests.

Replace the baseline only with a later successful CI run on an identified clean commit. Record the run, artifact, raw counts and measurement scope. Preserve a dated old snapshot in [the quality archive](archive/quality/QUALITY_METRICS-2026-10-25.md) when useful; do not retain obsolete tables as current targets.
