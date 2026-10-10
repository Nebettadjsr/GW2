# Software Quality Metrics

## Test coverage KPI

Test coverage is a permanent KPI for the Maven Java backend, Vue/TypeScript frontend, and
Python agent runtime. The long-term target is **at least 90% line and branch coverage** in each
relevant area. It is a warning target and is not currently a build gate.

The GitHub Actions `Coverage KPI report` job publishes a combined summary to the workflow run
and the `coverage-reports` artifact. It reports each language/module and package breakdown, as
well as a weighted aggregate over measured lines and branches. Missing reports are marked
unavailable. Aggregation never fills unavailable coverage with zero or estimates.

The Python area covers the whole agent workflow: `agent/runtime` and, since 2026-10-10, the n8n
pipeline bridge `local_bridge` (its `test_*` files excluded like the runtime's tests). Snapshots
taken before that date measured `agent/runtime` only.

Coverage is a minimum signal, not proof of correctness. Review affected modules and critical
branches individually. Do not pad tests, omit legitimate source files, or change measurement
scope to improve the number. Exceptions for generated or impractical-to-unit-test code must be
specific and documented in `docs/TEST_STRATEGY.md` and the report configuration.

## Provisional measured working-tree snapshot

Measured on **2026-10-03** at HEAD `c05080f7a39f09b0660490fc66fa24ee8449ffdc`. The working
tree was dirty before and after this task and includes unrelated in-progress files. These are
measured results for the current checkout, **not a clean-commit baseline**. A clean baseline is
pending; no commit was created because this task does not invoke the repository's story
publication workflow. The report measured all three areas:

| Area | Line | Branch | Gap to 90% line / branch | Suite/build result |
|---|---:|---:|---:|---|
| Java backend | 58.09% | 52.72% | 31.91 pp / 37.28 pp | 625 passed |
| Frontend TypeScript | 92.88% | 85.22% | 0 pp / 4.78 pp | 307 passed; type-check and build passed |
| Python agent runtime | 79.52% | 70.14% | 10.48 pp / 19.86 pp | 365 passed, 49 subtests passed |
| **Combined (weighted)** | **69.51%** | **65.86%** | **20.49 pp / 24.14 pp** | Not a separate test run |

Coverage is measured by packages under Java, source feature directories in the frontend, and
Python runtime packages; the CI artifact lists the complete breakdown. At the area level, each
line and branch measure is below target except the frontend line measure. The four former Python
failures are resolved by aligning fixtures and documentation with the configured 50% weekly
remaining threshold. All subsystem suites now pass. These values are measured, not estimates.
Routine run-by-run values remain in CI summaries and artifacts.

Verification used the full Maven, Vitest-with-coverage, and pytest-cov suites. The original
unittest discovery command also exits successfully after the capacity fixture correction. The
frontend `npm run build` performs `vue-tsc --noEmit` before Vite production bundling; both passed.
The GitHub Actions workflow was parsed and its coverage job reviewed locally, but no remote
Actions run was triggered or inspected. CI status therefore remains unverified remotely.

### Package/module breakdown

Each cell is `line / branch` coverage. `—` means the tool has no branch counter for that module.

| Java package | Coverage |
|---|---:|
| default package | 10.65% / 14.63% |
| `api` | 8.86% / 6.67% |
| `api.tp` | 3.49% / 0.00% |
| `application` | 93.82% / 71.25% |
| `application.icons` | 96.97% / 100.00% |
| `craft` | 92.44% / 84.02% |
| `ecto` | 100.00% / 100.00% |
| `infra.icons` | 89.29% / 78.68% |
| `luck` | 89.47% / 72.22% |
| `model` | 47.62% / 50.00% |
| `parser` | 45.78% / 26.79% |
| `repo` | 85.05% / 64.39% |
| `repo.tp` | 78.26% / 66.67% |
| `sync` | 37.53% / 33.68% |
| `sync.tp` | 100.00% / 100.00% |
| `sync.tp.relevance` | 0.00% / 0.00% |
| `tradingpost` | 100.00% / 100.00% |
| `util` | 30.00% / 28.95% |
| `web` | 93.68% / 82.59% |
| `web.dto` | 100.00% / — |
| `web.task` | 98.51% / 88.89% |

| Frontend source area | Coverage |
|---|---:|
| `App.vue` | 100.00% / 94.44% |
| `account` | 98.88% / 86.84% |
| `api` | 84.06% / 70.59% |
| `crafting` | 94.02% / 85.63% |
| `ecto` | 84.40% / 83.70% |
| `items` | 100.00% / 90.00% |
| `main.ts` | 0.00% / — |
| `shell` | 100.00% / 86.21% |
| `sync` | 95.57% / 85.58% |

| Python agent-runtime package | Coverage |
|---|---:|
| `agent.runtime.core` | 76.51% / 68.56% |
| `agent.runtime.evaluation` | 94.29% / 94.44% |
| `agent.runtime.human` | 89.85% / 81.51% |
| `agent.runtime.runners` | 79.35% / 58.47% |
| `agent.runtime.support` | 85.11% / 73.08% |

## Generation and review

See `docs/TEST_STRATEGY.md` §26 for commands and report paths. A local combined report can be
generated with:

```text
python .github/scripts/publish_coverage_kpi.py
```

The script writes `quality-reports/coverage-kpi.md` and `quality-reports/coverage-kpi.json`.
HTML reports remain in the tool-specific output directories.

### Establishing the clean baseline

The current numbers cannot be promoted to a clean baseline without a commit identifying the
complete source and test state. To establish one, first have the owner of the unrelated dirty
work finish or preserve it separately; then run `git status --short` and confirm only the
coverage/failure-fix files intended for this baseline remain. Activate/complete the appropriate
story through the repository workflow so it commits only that scoped work. Check out the
resulting SHA with a clean worktree, run the full three-suite CI commands, and retain the
`coverage-reports` artifact and workflow SHA. Replace this provisional snapshot only with those
clean-commit results. Do not stage or commit the other in-progress files as part of baseline
creation.
