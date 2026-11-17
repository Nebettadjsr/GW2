## Story ID

STORY-API-011

## Title

Enforce account isolation across HTTP workflows

## Status

BLOCKED

## Milestone

milestone-06

## Goal

Integrate validated access, scoped persistence and task ownership across every account-dependent HTTP workflow, proving isolation from request admission through calculation and asynchronous observation.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md, Phase 6: API/application isolation and cross-account integration tests.
- docs/TARGET_ARCHITECTURE.md sections 7.1-7.4, 8.1-8.2 and 9.1.
- docs/DOMAIN_SPEC.md sections 6, 9, 11.1, 39-40 and 55, existing account-sensitive inputs.
- docs/TEST_STRATEGY.md sections 8-11, 26.1, 31.2, 35 and 36.
- docs/QUALITY_METRICS.md, Test coverage KPI and Generation and review.
- agent/stories/STORY-API-010-validated-account-access-foundation.md.
- agent/stories/STORY-APP-015-account-scoped-repositories-and-publication.md.
- agent/stories/STORY-SYNC-006-account-scoped-refresh-and-task-ownership.md.

## Context

The access foundation deliberately excludes enforcement across existing routes. Scoped repositories and workers alone cannot prevent unauthorized account reads, cached calculations or task disclosure. This story closes those HTTP/application integration gaps without changing economic rules.

## Acceptance Criteria

1. Inventory and classify existing routes and application entry points by account/global data consumption. Before account lookup, cache access, calculation or admission, enforce a live session, matching X-Account-Context and grants for every consumed account source. Apply this to bank/materials/Luck, selectors/characters, Profit/Discovery calculations and details, refreshes, freshness/status and task/history projections. Pure global operations remain account-independent.
2. Pass the trusted immutable context explicitly through application services, snapshots and account-dependent caches/results. Selected characters and any caller-supplied account assertion must belong to that scope. Eliminate configured/default/last-used-account fallbacks from multi-user workflows; domain calculations continue receiving scoped ordinary inputs.
3. Use API-010 error semantics: 401 for missing/expired access, 403 for context/grant/account mismatch and safe retryable 503 for unavailable proof. Authorization rejection discloses no account existence, rows or freshness. Account responses use no-store and identify their access context. Protect state changes with the established origin/CSRF checks.
4. Cached reads/calculations with a live lease require no GW2 key. When refresh is needed without a supplied key, return the authorized usable result with explicit credential-required refresh state, or pending/no-result if inputs are unavailable. Do not claim admission or use a configured credential. Explicit keyed operations invoke SYNC-006 validation/admission. Preserve existing feature-specific source requirements and freshness/recovery semantics, including Profit's exclusion of Luck.
5. Task lookup, result, failure, listing/history and System Status account projections require live account/grant authorization. Foreign, expired and unknown account task identifiers have the same 404 after authentication. Another sufficiently authorized session for the same GUID may observe retained work. Global diagnostics expose only global execution facts and no account-derived demand or associations.
6. Successful authorized use updates scoped activity monotonically without advancing freshness; rejected proof/scope and failed task polling do not advance activity. No session/key becomes persistent account data. Retained runtime entry points cannot bypass scoped access against the new schema.
7. Document the integrated browser-facing contracts and classify any disabled legacy entry point explicitly; do not claim completed browser credential handling in this story.

## Required Tests

- HTTP contract tests cover each classified account route family, success with independently distinguishable A/B fixtures, missing/expired session, insufficient grants, foreign character/account assertion and stale-tab context mismatch. Assert rejection starts no account work or task.
- Successive and concurrent A/B requests cover calculations, recursive detail, cached results, freshness and diagnostics. Lower-grant replacement keys cannot access cached data from higher-grant sessions. No error or response contains secrets or foreign-account data.
- Real task facility with controlled workers proves admission, execution and observation scope, foreign/unknown 404 equivalence, same-GUID replacement-session observation and no status access after lease expiry.
- End-to-end HTTP/application/real isolated PostgreSQL checks using production migrations prove read/write/sync/calculation isolation, same-GUID dataset reuse and scoped activity. Compare affected route output to the same scoped application use case without duplicating domain mathematics. Name and run the excluded *IT checks explicitly; use disposable fixtures rather than the normal developer database for isolation checks.
- Verify valid cached access during upstream outage, credential-required refresh without a key, keyed retry and no fabricated freshness. Add regressions for any confirmed defect; normal automated checks remain offline.

## Constraints

- Preserve domain rules and shared global caches; no separate application login, new credential store or global mutable current-account state.
- Do not change agent/runtime/, local_bridge/ or CI workflow configuration.

## Dependencies

STORY-API-010, STORY-APP-015, STORY-SYNC-006

## Definition of Done

- Route classification, enforcement and cross-layer isolation evidence satisfy the criteria, with targeted tests and explicit commands for excluded checks.
- Review relevant backend coverage reports against docs/QUALITY_METRICS.md; report unresolved quality gaps without claiming a new clean CI baseline or altering scope.
- Update current architecture/API documentation for delivered multi-user route behavior.
- The CI gate is green (verified by the pipeline after acceptance).

## Result

Not started.

## Blockers

Waiting on dependencies: depends on STORY-API-010, which is not DONE (currently TODO); depends on STORY-APP-015, which is not DONE (currently BLOCKED); depends on STORY-SYNC-006, which is not DONE (currently BLOCKED)
