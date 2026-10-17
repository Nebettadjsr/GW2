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

2026-10-16: Retained OPEN. The supplied Phase 5 browser-completion criteria still do not authorize the independent backend package coverage series. `docs/QUALITY_METRICS.md`, Test coverage KPI and Establishing the clean baseline, requires identified clean-commit CI evidence; none is supplied for sizing package starting values and targets. Existing `agent/stories/STORY-PERF-004-cached-profit-page-acceptance.md` covers the remaining Phase 5 acceptance evidence, not this request. No speculative package targets, duplicate stories, or standalone cosmetic smoke repairs were created. Remaining processing requires an authorized planning scope and fresh CI coverage evidence, not a new product or architecture decision.

2026-10-12: Retained OPEN after Phase 5 review. The requested backend package series still needs an authorized scope and clean-commit CI coverage evidence under `docs/QUALITY_METRICS.md`, Establishing the clean baseline. No such artifact is supplied. The new `agent/stories/STORY-APP-014-cached-profit-refresh-coordination.md`, `agent/stories/STORY-WEB-034-provisional-profit-refresh-workflow.md` and `agent/stories/STORY-PERF-004-cached-profit-page-acceptance.md` implement and verify the resolved Phase 5 refresh requirement, not the requested coverage series. No cosmetic smoke-reporting story or speculative package target was created.

2026-10-11 (current-evidence disposition): Retained OPEN. The backend package series remains outside the supplied Phase 5 browser-completion scope. docs/QUALITY_METRICS.md, Test coverage KPI and Establishing the clean baseline, requires clean-commit CI evidence; no fresh artifact is supplied for sizing targets. agent/stories/STORY-PERF-003-refresh-phase-five-page-budget-evidence.md supplies timing, not coverage. agent/stories/STORY-WEB-033-recover-overlapping-account-refresh-scopes.md repairs its verified browser-workflow F002 defect and does not represent the requested series. F003 was dismissed without a cosmetic reporting story. No new product/architecture decision is needed for this request; it awaits an authorized scope and fresh CI evidence.

2026-10-11 (post-health-review planning): Retained OPEN. Reviewed `agent/stories/STORY-QUALITY-005-phase-five-completion-review.md` Result sections 4 and 5 F8 and `docs/QUALITY_METRICS.md` Test coverage KPI / Establishing the clean baseline. The requested backend package series is not represented by the browser-completion scope or the completed bounded review, and no fresh CI starting values were supplied. No package targets, duplicate review or cosmetic smoke stories were created. `agent/stories/STORY-PERF-003-refresh-phase-five-page-budget-evidence.md` addresses only the current Phase 5 performance evidence gap, not this request. The request remains available for an authorized planning scope and a fresh CI baseline; no additional product or architecture decision is needed for it.

OPEN — Reviewed against the supplied Phase 5 scope. The request asks for Java backend package coverage work, but the supplied Phase 5 objective and exit criteria concern browser workflows and acceptance; they do not authorize this separate backend coverage batch. The requested CI refresh also cannot be represented by the existing provisional 2026-10-03 working-tree snapshot in `docs/QUALITY_METRICS.md` §23. No coverage targets or stories were created from the stale package values. Revisit in a planning scope that authorizes backend quality work and after a fresh CI artifact is available.

2026-10-10: Retained OPEN after reviewing the request in full. `docs/QUALITY_METRICS.md` sections "Provisional measured working-tree snapshot" and "Establishing the clean baseline" still distinguish the local snapshot from clean-commit CI evidence. This pass cannot assert new package starting values or mark the requested series fully covered. Existing Phase 5 feature verification remains represented by `agent/stories/STORY-WEB-032-root-sourcing-browser-coverage.md` and the bounded closure assessment by `agent/stories/STORY-QUALITY-005-phase-five-completion-review.md`; neither represents the requested backend coverage series. No new cosmetic smoke-reporting stories were created. The remaining request needs an authorized planning scope and a fresh CI baseline; no Product Owner ambiguity or architecture question requires a new UD or AR.

2026-10-11: Retained OPEN under the supplied Phase 5 lock. Reviewed `docs/QUALITY_METRICS.md` "Test coverage KPI" and "Establishing the clean baseline": the 90% KPI is a warning target, and the provisional numbers cannot establish clean CI starting values. No fresh CI artifact was supplied. The requested independent Java package coverage series remains outside the supplied browser completion scope; `agent/stories/STORY-QUALITY-005-phase-five-completion-review.md` already covers the required bounded Phase 5 assessment, not that series. No duplicate review, speculative coverage targets, cosmetic smoke follow-up stories, UD or AR were created. Remaining work still requires an authorized planning scope and fresh CI evidence.
