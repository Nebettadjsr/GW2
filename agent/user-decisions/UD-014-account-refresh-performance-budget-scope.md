## Status

OPEN

## Decision Needed

Does the Crafting Profit 7-second navigation-to-complete-page budget include time spent waiting for an automatic account synchronization when account data is stale? If that wait is excluded, specify the timing boundary and what must still meet the 7-second requirement. If it is included, confirm that stale-account navigation must be verified against the same complete-page budget.

## Why This Is Needed

The Phase 5 health review F5 identifies an intended automatic freshness workflow that may wait for account synchronization, but no authoritative exception or separate timing boundary. Choosing one would change the product performance requirement.

## Context

- docs/ROADMAP.md, supplied Phase 5 performance and normal-feature-refresh exit criteria.
- docs/TARGET_ARCHITECTURE.md section 17 budgets navigation/request initiation through complete usable page content.
- docs/TEST_STRATEGY.md section 34 owns the real-data measurement procedure.
- agent/stories/STORY-QUALITY-005-phase-five-completion-review.md, Result sections 1(e), 5 F5 and 7: automatic refresh waiting is reported, but no stale-account real-data performance measurement was taken. A possible breach is not a measured regression.
- STORY-PERF-002 used fresh data. STORY-PERF-003 refreshes that independent case without assuming an exception for stale data.

## Blocks

Final disposition of health-review F5 and confirmation of the performance criterion's stale-account scope before Phase 5 closure. Dependent verification or remediation can be sized only after the answer. Does not block fresh-account measurements in STORY-PERF-003.

## External Input Possibly Required

Product Owner definition of the performance scope; subsequent real-account synchronization evidence if the answer requires that case to be measured.

## User Decision

TODO

## Resolution

TODO
