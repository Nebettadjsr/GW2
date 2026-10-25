# Software Quality Metrics

## Test coverage KPI

Test coverage is a permanent KPI for the Maven Java backend and Vue/TypeScript frontend.
The long-term target is **at least 90% line and branch coverage** in each
relevant area. It is a warning target and is not currently a build gate.

The GitHub Actions `Coverage KPI report` job publishes a combined summary to the workflow run
and the `coverage-reports` artifact. It reports each language/module and package breakdown, as
well as a weighted aggregate over measured lines and branches. Missing reports are marked
unavailable. Aggregation never fills unavailable coverage with zero or estimates.

The retired Python harness is not part of current application coverage. Its last
measured values remain below as historical evidence for the pre-migration snapshot.

Coverage is a minimum signal, not proof of correctness. Review affected modules and critical
branches individually. Do not pad tests, omit legitimate source files, or change measurement
scope to improve the number. Exceptions for generated or impractical-to-unit-test code must be
specific and documented in `docs/TEST_STRATEGY.md` and the report configuration.

## Current clean CI baseline

Measured on clean `master` commit `f2fea26157e4767865006ee9b3f5d3342ab8654d` in
[CI run 40107414677](https://github.com/Nebettadjsr/GW2/actions/runs/40107414677),
after the GW2 API boundary test pilot and issue-closure fix. All three jobs succeeded. The
`coverage-reports` artifact (ID `12565690292`) contains the raw JaCoCo and
Vitest reports and `coverage-kpi/coverage-kpi.json`. The KPI remains warning-only.

| Area | Line | Branch | Gap to 90% line / branch |
|---|---:|---:|---:|
| Java backend | 79.84% (4016/5030) | 68.31% (1526/2234) | 10.16 pp / 21.69 pp |
| Frontend TypeScript | 95.50% (1572/1646) | 87.49% (1357/1551) | 0 pp / 2.51 pp |
| **Combined (weighted)** | **83.70% (5588/6676)** | **76.17% (2883/3785)** | **6.30 pp / 13.83 pp** |

For the open backend coverage request #21, the same artifact measures `sync`
at 43.08% line / 36.67% branch, `repo` at 76.09% / 57.92%, `parser` at
63.25% / 41.96%, `api.tp` at 3.49% / 0%, `api` at 31.65% / 73.33%, and
`model` at 45.16% / 33.33%. The artifact has the complete package breakdown.
These measurements include the tests merged in #36 and #40. The baseline is a measured
snapshot, not a requirement to reach 90% in one story.

## Pre-migration clean CI snapshot

Measured from CI run
[40096904227](https://github.com/Nebettadjsr/GW2/actions/runs/40096904227) on clean `master`
commit `459f728cdf12fcfed23809d3b1b42d400d4e5fd5`. All four jobs (Maven, Vitest, pytest-cov,
Coverage KPI report) succeeded. Values come from the run's `coverage-reports` artifact
(ID `12561400964`): `coverage-kpi/coverage-kpi.json` and `.md`, cross-checked against the raw
`coverage-input/java-coverage/jacoco.xml`. The artifact does not carry test counts, so none are
listed here. Java and frontend package figures remain valid evidence for that commit. The Python
and three-area combined figures record the retired harness and are not current CI targets. The
current two-area baseline is recorded above.
The 90% target remains an informational warning KPI, not a build gate.

| Area | Line | Branch | Gap to 90% line / branch |
|---|---:|---:|---:|
| Java backend | 78.91% (3969/5030) | 65.89% (1472/2234) | 11.09 pp / 24.11 pp |
| Frontend TypeScript | 95.50% (1572/1646) | 87.49% (1357/1551) | 0 pp / 2.51 pp |
| Python agent runtime + `workflow_service` | 80.80% (4726/5849) | 72.63% (1595/2196) | 9.20 pp / 17.37 pp |
| **Combined (weighted)** | **81.97%** | **73.97%** | **8.03 pp / 16.03 pp** |

Coverage is measured per Java package, frontend source area, and Python package. At area level,
every measure is below target except the frontend line measure. Definitions and exclusions are
unchanged. `unavailable` means the tool has no branch counter for that module (`web.dto`,
`main.ts`); it is not counted as zero.

### Change from the retired provisional snapshot

The earlier 2026-10-03 figures (dirty working tree at `c05080f`, Python measured on
`agent/runtime` only) are retired and no longer authoritative. They are not a like-for-like
comparison with this baseline, but the measured movement is:

| Area | Line | Branch |
|---|---:|---:|
| Java backend | 58.09% → 78.91% (+20.82 pp) | 52.72% → 65.89% (+13.17 pp) |
| Frontend TypeScript | 92.88% → 95.50% (+2.62 pp) | 85.22% → 87.49% (+2.27 pp) |
| Python | 79.52% → 80.80% (+1.28 pp) | 70.14% → 72.63% (+2.49 pp) |
| Combined | 69.51% → 81.97% (+12.46 pp) | 65.86% → 73.97% (+8.11 pp) |

Python scope now also includes `workflow_service` and `agent.runtime.qa`, which the provisional
snapshot did not report. Java `infra.db` is newly reported. Largest remaining Java gaps for #21 by
uncovered lines: `sync` (432 missed lines), `repo` (142), `parser` (90), `api.tp` (83), `api` (72).

### Package/module breakdown

Each cell is `line / branch` coverage.

| Java package | Coverage (line / branch) |
|---|---:|
| `api` | 8.86% / 6.67% |
| `api.tp` | 3.49% / 0.00% |
| `application` | 93.01% / 78.40% |
| `application.icons` | 96.97% / 100.00% |
| `craft` | 92.44% / 84.02% |
| `infra.db` | 79.17% / 65.38% |
| `infra.icons` | 90.13% / 78.68% |
| `luck` | 89.47% / 72.22% |
| `model` | 45.16% / 33.33% |
| `parser` | 45.78% / 26.79% |
| `repo` | 76.09% / 57.92% |
| `repo.tp` | 69.57% / 66.67% |
| `sync` | 43.08% / 36.67% |
| `sync.tp` | 100.00% / 100.00% |
| `sync.tp.relevance` | 85.71% / 77.27% |
| `tradingpost` | 100.00% / 100.00% |
| `util` | 100.00% / 78.57% |
| `web` | 97.63% / 93.16% |
| `web.dto` | 100.00% / unavailable |
| `web.task` | 98.51% / 88.89% |

| Frontend source area | Coverage (line / branch) |
|---|---:|
| `App.vue` | 100.00% / 83.33% |
| `account` | 98.88% / 87.14% |
| `api` | 85.92% / 70.59% |
| `crafting` | 95.31% / 87.55% |
| `ecto` | 97.40% / 93.27% |
| `items` | 100.00% / 90.00% |
| `main.ts` | 0.00% / unavailable |
| `shell` | 100.00% / 84.00% |
| `sync` | 95.57% / 84.50% |

| Python package | Coverage (line / branch) |
|---|---:|
| `agent.runtime.core` | 75.76% / 68.86% |
| `agent.runtime.evaluation` | 93.55% / 93.18% |
| `agent.runtime.human` | 89.71% / 80.92% |
| `agent.runtime.qa` | 77.75% / 67.89% |
| `agent.runtime.runners` | 73.60% / 56.06% |
| `agent.runtime.support` | 86.67% / 79.46% |
| `workflow_service` | 86.24% / 80.10% |

## Generation and review

See `docs/TEST_STRATEGY.md` §26 for commands and report paths. A local combined report can be
generated with:

```text
python .github/scripts/publish_coverage_kpi.py
```

The script writes `quality-reports/coverage-kpi.md` and `quality-reports/coverage-kpi.json`.
HTML reports remain in the tool-specific output directories.

### Refreshing the baseline

The snapshot above is replaced only by a later successful CI run on a clean commit: record
the run link, exact commit SHA, and the `coverage-reports` artifact values, and mark the old
snapshot as retired.
