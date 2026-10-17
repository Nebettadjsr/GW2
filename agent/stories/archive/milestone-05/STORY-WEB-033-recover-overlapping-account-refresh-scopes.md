## Story ID

STORY-WEB-033

## Title

Recover browser workflows when overlapping account refreshes need different sources

## Status

SUPERSEDED

## Milestone

milestone-05

## Goal

Complete automatic freshness recovery when a reused account task covers fewer sources than the browser request needs, without a manual retry or System Status visit.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md, supplied Phase 5 normal-feature account/price refresh criterion.
- docs/TARGET_ARCHITECTURE.md sections 9 and 10.1.
- docs/DOMAIN_SPEC.md sections 2.2.1 and 39.
- docs/CURRENT_ARCHITECTURE.md sections 5 Coordination limits and 7.
- docs/TEST_STRATEGY.md sections 8, 11, 12, 14, 35.3 and 36.
- docs/QUALITY_METRICS.md, Test coverage KPI and Establishing the clean baseline.
- docs/bugs/README.md and docs/bugs/BUG-REPORT-TEMPLATE.md.
- agent/stories/STORY-PERF-003-refresh-phase-five-page-budget-evidence.md, F002.

## Context

F002 records a selector-options request starting a CHARACTERS-only ACCOUNT_SYNC task. A concurrent Profit request needs CHARACTERS, BANK, ACCOUNT_RECIPES and MATERIALS but receives that task's ID. After it succeeds, the frontend's single retry still receives ACCOUNT_DATA_STALE for the other sources. Targeted inspection of AccountDataFreshnessInterceptor, BackgroundTaskService.submitOrExisting and frontend/src/api/http.ts confirms this path remains. No queued story covers it. Correctness recovery is independent of unresolved performance-budget decisions.

## Acceptance Criteria

1. Reproduce deterministically: hold a CHARACTERS-only refresh, then request Profit with all four sources stale. After successful refresh work, the original browser operation reaches its successful response without manual retry, including when successive tasks are needed.
2. A reused task's success is not proof of freshness for all caller sources. Recheck freshness and refresh missing required sources before delivering results. Cover sufficient and insufficient task reuse.
3. Preserve at most one unfinished ACCOUNT_SYNC task per backend process, per-feature source scopes and source-specific completion markers. Never mark unrefreshed or failed sources fresh or introduce concurrent account writes as a workaround.
4. Recovery is bounded: repeated stale responses cannot loop indefinitely or restart the existing five-minute overall recovery deadline. Failed tasks and exhausted recovery expose the existing failure/timeout behavior. Fresh requests start no refresh.
5. Cover overlapping Bank/Materials and crafting requests through the shared recovery path; retain submitted scope/settings and valid display selections. Domain mathematics and success payloads remain unchanged.
6. Create a confirmed-defect registry record linking F002, this story, the correction and regression verification under the registry lifecycle.

## Required Tests

- Controlled-task regression for CHARACTERS-only refresh followed by Profit's four-source requirement: assert remaining sources are actually refreshed and the browser receives successful results, not merely a successful task ID.
- Backend freshness/task checks for sufficient and insufficient reuse, concurrency exclusion, successful markers and failed refreshes, with fakes and latches rather than live API calls or sleeps.
- Frontend HTTP recovery checks for successive stale responses with different tasks, eventual success, failure, an overall bounded timeout and fresh-request behavior, using controlled time/transport.
- Affected component checks retaining scope/settings/selections through recovery; run affected existing HTTP/task suites locally. Tests must run in CI without network, real account data, display or user database. No full local regression run is required.

## Constraints

- Use existing current single-user application/task boundaries; no new job platform, tenancy design or broad layering refactor.
- Do not decide performance-budget scope, alter freshness TTLs, remove required refreshes or serve stale data as a workaround.
- Limit production edits to this confirmed defect and necessary test seams. Preserve calculation ownership.
- Do not change agent/runtime/, local_bridge/, build/CI configuration or measurement exclusions.

## Dependencies

- STORY-PERF-003

## Definition of Done

The regression and affected tests exist and pass, with successful recovery and bounded failure evidenced. The bug record links correction and tests. Review affected backend/frontend coverage reports against the 90% warning KPI and record meaningful changes and unresolved gaps without claiming a clean CI baseline from local results. The pipeline owns the full CI gate after acceptance.

## Result

SUPERSEDED by Product Owner decision UD-015 (2026-10-12). Not implemented, and must not be in this form.

This story requires refreshing stale account sources before delivering results and forbids serving
stale data. UD-015 (with UD-013/UD-014, AR-007 and ADR-007) replaces that with stale-while-revalidate:
cached results are shown immediately as provisional while the required sources refresh
asynchronously, then recalculated. The overlapping-refresh-scope defect from STORY-PERF-003 F002
remains required (UD-015 point 8) and must be planned within the UD-015 work, not from this story.
The partial implementation started on 2026-10-11 was rolled back by the maintainer.

## Blockers

None. Superseded, not blocked.
