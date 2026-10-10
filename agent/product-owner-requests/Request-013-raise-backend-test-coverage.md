# Product Owner Request

## Status

OPEN

## Title

Raise test coverage in the least-covered backend areas

## Requested Change

Plan a short series of current-phase stories that add meaningful tests to the least-covered Java
backend packages. The goal for this round is a nudge, not the full target: **raise line and branch
coverage by roughly 5–10 percentage points in each targeted package**.

Start with the packages furthest below the 90% KPI in `docs/QUALITY_METRICS.md`:

| Package | Line / branch (2026-10-03 snapshot) |
| --- | --- |
| `api` | 8.86% / 6.67% |
| `api.tp` | 3.49% / 0.00% |
| `sync.tp.relevance` | 0.00% / 0.00% |
| default package | 10.65% / 14.63% |
| `util` | 30.00% / 28.95% |
| `sync` | 37.53% / 33.68% |
| `parser` | 45.78% / 26.79% |
| `model` | 47.62% / 50.00% |

Expected shape of the work:

- First, refresh `docs/QUALITY_METRICS.md` from the latest CI coverage artifacts. That snapshot is
  from a dirty working tree, so the starting point should be re-measured before stories are sized.
- One story per package or small group of related packages. Each story names its starting and target
  coverage, and its Result reports the measured change.
- Prefer behaviour that matters to the crafting/Trading Post calculations and to GW2 API sync:
  parsing of API responses, sync edge cases (missing, partial or changed items), and utility
  functions the calculations rely on.

## Why / Product Intent

Recent stories mostly aligned browser smoke scripts and their follow-up findings. Smoke scripts run
locally only and do not move the coverage KPI. Meanwhile the Java backend sits around 58% line /
53% branch, far below the 90% target, and its weakest packages handle external data (API parsing and
sync), where regressions are most likely and least visible.

## Constraints

- Tests must assert real behaviour with independently established expected values, as required by
  `docs/TEST_STRATEGY.md` and `CLAUDE.md`. Do not pad coverage, and do not change measurement scope or
  exclusions to raise the numbers.
- Tests must run in CI: no dependency on the local database contents, a real GW2 API key, network
  access or a display. Use fixtures and fakes.
- Production code changes only where a test exposes a real defect. Record such defects as findings or
  bug registry entries per the normal process.
- GW2 application stories must not change the agent workflow (`agent/runtime/`, `local_bridge/`,
  `.github/workflows/`). Agent-workflow problems are handled separately from the app backlog.
- Do not open follow-up stories for cosmetic browser-smoke reporting issues while this request is
  open, unless the issue hides a real app defect.

## Additional Context

The CI "Coverage KPI report" job publishes the combined coverage report. The project-wide 90% KPI is
a warning, not a build gate (`docs/QUALITY_METRICS.md`), so these stories are improvement work and
should not block feature work that is already queued.

## Planner Resolution

OPEN — Reviewed against the supplied Phase 5 scope. The request asks for Java backend package coverage work, but the supplied Phase 5 objective and exit criteria concern browser workflows and acceptance; they do not authorize this separate backend coverage batch. The requested CI refresh also cannot be represented by the existing provisional 2026-10-03 working-tree snapshot in `docs/QUALITY_METRICS.md` §23. No coverage targets or stories were created from the stale package values. Revisit in a planning scope that authorizes backend quality work and after a fresh CI artifact is available.
