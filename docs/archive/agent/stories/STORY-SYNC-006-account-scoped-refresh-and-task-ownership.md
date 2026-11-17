## Story ID

STORY-SYNC-006

## Title

Scope account refresh admission, execution and task ownership

## Status

BLOCKED

## Milestone

milestone-06

## Goal

Run account synchronization with explicitly verified account scope and transient operation credentials, preserving per-account exclusion and shared global refresh coordination.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md, Phase 6: stable identity reuse, account synchronization isolation and global concurrency review.
- docs/TARGET_ARCHITECTURE.md sections 7.2-7.4, 8.1-8.2 and 9-9.1.
- docs/CURRENT_ARCHITECTURE.md section 5, existing account, TP and global coordination.
- docs/DOMAIN_SPEC.md sections 6, 9, 11.1 and 39-40, scoped inputs and existing freshness rules.
- docs/TEST_STRATEGY.md sections 8-10, 26.1, 31.2-31.3, 35.3 and 36.
- docs/QUALITY_METRICS.md, Test coverage KPI and Generation and review.
- agent/stories/STORY-API-010-validated-account-access-foundation.md.
- agent/stories/STORY-APP-015-account-scoped-repositories-and-publication.md.

## Context

The existing coordinator accumulates account demands under a process-wide account operation. The resolved access contract requires account-specific exclusion, credential-aware admission and immutable worker scope. Persistence publication is supplied by APP-015; route-wide enforcement follows separately.

## Acceptance Criteria

1. All account refresh entry points accept trusted immutable account context and an explicitly supplied transient operation key. Revalidate identity and required grants before admission through API-010 facilities; reject mismatched/insufficient credentials without admission, upstream source fetching or publication. Never fall back to the deployment key or another account's credential.
2. Capture account GUID and operation grants in each account task. Workers retain that scope even after session expiry, logout or browser account replacement. Pass it to every source fetch/publication, including character children, source markers and activity; replacement keys for the same GUID reuse the same persisted dataset.
3. Permit at most one unfinished ACCOUNT_SYNC task per verified account in the process while allowing different accounts to progress independently. Additional demands pass their own grant checks; extend active work only when its credential covers those sources. Otherwise wait for terminal work, recheck source freshness and admit remaining work with a suitable validated credential. Omitted sources never acquire successful completion by association.
4. Hold keys only in private admitted-operation memory, release references on every terminal path and keep them out of status, task metadata, history, logs and errors. Retained task records include the owner and required grants for later authorized observation, without storing raw upstream responses or credentials.
5. Preserve partial-source success, atomic last-good publication, successful empty-source semantics and captured generation metadata from APP-015. Account refresh coordination and required-source accumulation are keyed by account; ordinary admission/polling never advances freshness.
6. Review all global refresh entry points used by multi-user workflows. Preserve the one shared Profit/Discovery TP cache, ten-minute freshness, required-ID-only batches and global coalescing with rechecks of uncovered demands. Scheduler/manual metadata checks and graph work continue through shared in-process coordination; centralize any uncovered duplicate entry path found by this review. No full-catalog price timer or per-user global refresh pipeline is introduced.
7. Shared task/status records expose only global execution facts. Keep account-derived item demands, caller identity and calculation associations out of public global task payloads; account associations remain in scoped application context. Document the reviewed operations and delivered coordination boundaries.

## Required Tests

- Controlled offline GW2 fixtures verify same-GUID replacement keys, differing grants, mismatch, invalid keys, transient failures and partial source success through the real admission/application boundary.
- Use the real in-process task facility and latches to prove same-account exclusion, different-account progress, narrow-to-wide demand handling, credential restrictions and worker scope after originating-session termination. Verify terminal cleanup and sanitized success/failure metadata.
- Real isolated PostgreSQL using production migrations: two accounts synchronize overlapping source identifiers without cross-account changes; a failed source preserves its generation and last-good rows while another successful source commits.
- Concurrent multi-account Profit/Discovery demands reuse fresh quotes, coalesce missing/stale IDs and eventually cover additional IDs without exposing account associations. Scheduler/manual global requests share exclusion and graph publication stays coherent.
- Name and run any PostgreSQL *IT check excluded from the default suite explicitly. Normal tests require no live API, key, display or developer database. Add requirement-based regression tests for confirmed defects.

## Constraints

- Follow established account/session/publication contracts; do not add domain calculations, distributed locks, durable queues or cleanup execution.
- HTTP route-wide authorization and browser lifecycle are separate dependent stories.
- Do not change agent/runtime/, local_bridge/ or CI workflow configuration.

## Dependencies

STORY-API-010, STORY-APP-015

## Definition of Done

- Scoped refresh/task behavior and global concurrency review satisfy the criteria with targeted tests and explicit verification limitations.
- Review affected coverage reports under docs/QUALITY_METRICS.md and report unresolved gaps without changing exclusions or inventing package baselines.
- Update current architecture/runtime documentation for delivered behavior and remaining integration boundaries.
- The CI gate is green (verified by the pipeline after acceptance).

## Result

Not started.

## Blockers

Waiting on dependencies: depends on STORY-API-010, which is not DONE (currently TODO); depends on STORY-APP-015, which is not DONE (currently BLOCKED)
