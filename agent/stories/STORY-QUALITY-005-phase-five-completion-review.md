## Story ID

STORY-QUALITY-005

## Title

Review Phase 5 project health before milestone completion

## Status

BLOCKED

## Milestone

milestone-05

## Goal

Perform the bounded PROJECT HEALTH REVIEW required before Phase 5 closure and record concrete findings and their disposition for planner assessment.

## Authoritative Source Documents / Sections

- Supplied docs/ROADMAP.md Phase 5 exit criteria.
- docs/TARGET_ARCHITECTURE.md section 21, PROJECT HEALTH REVIEW policy.
- docs/TEST_STRATEGY.md sections 12 and 36.

## Context

The Phase 5 exit criteria require a bounded health review before milestone completion. The review should occur after the integrated frontend work and real-user performance assessment are substantially complete. It records findings; it does not itself close the milestone.

The former STORY-UI-003 dependency was dropped from this story on 2026-10-03. That story is
SUPERSEDED rather than DONE, so keeping it as a dependency would hold this review blocked
permanently. Its legacy JavaFX presentation defect stays recorded as `docs/KNOWN_PROBLEMS.md` KP-09
and is resolved by JavaFX removal, not by Phase 5 closure.

## Acceptance Criteria

1. Perform the bounded review under TARGET_ARCHITECTURE section 21 and record evidence, concrete findings, and limitations in this story Result.
2. Assess findings against Phase 5 scope and identify explicit disposition or destination for each blocking finding and any proposed transfer.
3. Keep review scope bounded; do not automatically create remediation work or claim milestone completion.

## Required Tests

- Assess relevant existing verification evidence and run only targeted checks needed to resolve concrete review questions; report evidence and limitations.

## Constraints

- Non-Goals: no broad bug hunting, automatic broad regression testing, arbitrary test expansion, speculative cleanup/refactoring, or later-milestone architecture implementation.
- Planned future replacement alone is not evidence of current debt.
- Preserve review first, remediation later; unresolved findings may remain for a subsequent planning pass.

## Dependencies

- STORY-PERF-002
- STORY-WEB-019
- STORY-SYNC-005
- STORY-WEB-018
- STORY-WEB-020
- STORY-WEB-021
- STORY-WEB-022

## Definition of Done

The bounded health review, evidence, findings, limitations, and explicit disposition of blocking findings and proposed transfers are recorded in Result.

## Result

Not started.

## Blockers

STORY-WEB-018, STORY-WEB-020 and STORY-WEB-021 must be substantially complete before this review.
STORY-PERF-002, STORY-WEB-019, STORY-SYNC-005 and STORY-WEB-022 are DONE. STORY-UI-003 is SUPERSEDED
by the Product Owner's decision that the legacy JavaFX UI is obsolete and removable, so it no longer
blocks this review.

## Follow-up Findings

None.
