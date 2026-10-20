## Story ID

STORY-APP-015

## Title

Scope account repositories and atomic source publication

## Status

BLOCKED

## Milestone

milestone-06

## Goal

Require explicit trusted account scope for persistence and calculation-input snapshots, with atomic per-source publication and monotonic account activity.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md, Phase 6: persistence isolation, stable account reuse, activity and account-agnostic domain logic.
- docs/TARGET_ARCHITECTURE.md sections 7.1-7.4 and 8.1, scoped persistence and publication.
- agent/user-decisions/UD-016-database-is-a-disposable-cache.md.
- docs/CURRENT_ARCHITECTURE.md sections 5 and 7, current account coordination and snapshot behavior.
- docs/DOMAIN_SPEC.md sections 6, 9 and 11.1, account material pool, recipe knowledge and binding.
- docs/TEST_STRATEGY.md sections 8-9, 26.1, 31.2 and 36.
- docs/QUALITY_METRICS.md, Test coverage KPI and Generation and review.
- agent/stories/STORY-INFRA-004-versioned-account-schema-and-legacy-preservation.md.

## Context

The account schema must be consumed without default-account fallbacks. Current documented source transactions and REPEATABLE READ input snapshots must retain their consistency guarantees while becoming account-scoped. HTTP admission and upstream credential handling are separate work.

## Acceptance Criteria

1. Account registry registration/upsert is idempotent for the verified stable GUID, including concurrent callers. Replacement keys do not form new persistence identities. Require explicit trusted scope at account repository/application persistence ports; no configured/last-used account overload or global mutable current account selects records.
2. Scope every account read, join, aggregate, existence check, upsert, replacement, stale-row removal and source marker by account, covering bank, materials, account recipes, Luck, roster, disciplines, character recipes and inventory/equipment. Reject a nested character belonging to another account. Global metadata, graph and TP repositories remain shared.
3. Fetch/validation remains outside database transactions. Provide source publication that commits data, stale-row removals, completion timestamp, account/source-local generation and successful-sync activity atomically. Characters publish the roster and all character payloads together. Successful empty/unchanged sources have valid markers and advance their generation; failed validation/commit leaves last-good rows and markers untouched. Independent successful sources may publish despite another source's failure.
4. Serialize publication writers per verified account without serializing unrelated accounts. Fresh synchronization populates the reset account cache; no legacy archive promotion or ownership inference participates in publication.
5. Preserve one REPEATABLE READ calculation-input transaction across the requesting account's rows/markers and shared inputs. Snapshot metadata captures that account and the generations actually read. Retire singleton material-sync authority; after reset, missing source markers remain missing until a successful fresh publication. Ordinary reads never mark data fresh.
6. Implement activity persistence per section 7.1: UTC creation, monotonic last_seen_at for successful authorized use and last_successful_sync_at for successful publication. Invalid proof/rejected scope does not advance activity; missing historical activity stays unknown. Preserve the policy that cleanup requires measured impact and a later explicit threshold decision; add no cleaner.
7. Preserve existing recipe knowledge, account material-pool and binding semantics by supplying scoped data to existing domain rules. Domain types/calculations gain no sessions, keys, JDBC or authentication dependencies. Adapt affected callers so no retained runnable path silently uses the old unscoped schema.

## Required Tests

- Real isolated PostgreSQL tests against production migrations with two accounts sharing item/recipe identifiers and overlapping character/slot inputs. Assert complete read/write/delete/aggregate isolation and foreign-child rejection across all account source families.
- Verify atomic rollback on mid-publication failure, successful empty/unchanged generations, source independence, concurrent same-account writers, unrelated-account progress and monotonic activity.
- Verify REPEATABLE READ input consistency during concurrent publication, captured account/source generations, and shared global quote/reference reuse across both accounts.
- Application tests use independently specified fixtures to prove scoped owned-material/recipe inputs and unchanged domain expectations. Add regression tests for any confirmed defect found during implementation.
- Use default CI-selected tests for deterministic checks; name and run any real-database check excluded from the gate explicitly. No live key/network, existing local data or display requirement.

## Constraints

- Implement the established persistence contract; do not create a new identity or recipe policy.
- This story does not complete session HTTP enforcement, multi-user refresh admission, task observation or browser credential handling.
- Do not change agent/runtime/, local_bridge/ or CI workflow configuration.

## Dependencies

STORY-INFRA-004

## Definition of Done

- Scoped persistence and publication satisfy the criteria and targeted tests; affected callers compile and maintain explicit scope.
- Review relevant package/branch coverage reports under docs/QUALITY_METRICS.md and report unresolved gaps without speculative targets or scope exclusions.
- Current architecture documentation describes delivered persistence, activity and snapshot behavior, with remaining HTTP/sync work clearly distinguished.
- The CI gate is green (verified by the pipeline after acceptance).

## Result

Not started.

## Blockers

Waiting on dependencies: depends on STORY-INFRA-004, which is not DONE (currently TODO)
