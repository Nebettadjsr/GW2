# AR-007 - Cached Crafting Profit refresh contract

## Status

RESOLVED

## Architecture Question

What backend/application/HTTP freshness contract should implement the confirmed stale-while-revalidate behavior in UD-015 for Crafting Profit, coordinating separately refreshed account sources and shared Trading Post prices, while keeping authoritative calculations backend-owned? Reconcile TARGET_ARCHITECTURE sections 9 and 17 with UD-013 through UD-015 and identify the implementation and verification boundaries needed for subsequent Phase 5 planning.

## Context and Constraints

The supplied Phase 5 scope requires normal browser workflows to refresh needed account and price data and verifies Crafting Profit performance. UD-015 supplies the product behavior: usable cached results are provisional, refreshing happens asynchronously, successful refresh triggers recalculation, state is preserved, and failure/timeout leaves cached results with bounded recovery. UD-014 delegates to UD-015. UD-013 accepts reproducible measured compliance without an additional manual acceptance gate.

TARGET_ARCHITECTURE section 9 specifies the shared ten-minute TP cache and on-demand refresh but does not define cached-result freshness/task reporting or asynchronous recalculation. Section 17 does not distinguish initial cached-page completion from subsequent refresh/recalculation. TEST_STRATEGY section 34 still requires explicit manual acceptance and uses a completion sample spanning subsequent page changes. CURRENT_ARCHITECTURE section 7 documents account-task waiting followed by one retry; it is current-state evidence, not authority to override the confirmed decision.

STORY-WEB-033 is active and must remain untouched by this planner. Its existing constraints prohibit serving stale data, while UD-015 explicitly requires it and names that story's overlapping-scope repair. Its original contract covers account recovery only, not the new TP refresh contract. Do not assume the active implementation has delivered the newer behavior. The architect should identify this conflict and its planning consequences without editing the active story or selecting implementation work.

Preserve source-specific freshness markers, account-sync concurrency guarantees, separate account/TP mechanisms, shared TP caching, existing fee mathematics and backend calculation ownership. No new distributed job platform or broad refactoring is requested. The product behavior is already decided; do not reopen it as a new user question merely because the older documents differ. Select only the technical boundaries and contract not established by those documents, and reconcile architecture at its owning documents. Identify any test-methodology wording requiring its owner's subsequent reconciliation; do not claim measurement acceptance from historical fresh-cache evidence.

## Authoritative References

- docs/ROADMAP.md, supplied Phase 5 objective and normal-feature-refresh/performance exit criteria.
- agent/user-decisions/UD-013-phase-five-performance-user-acceptance.md, Resolution.
- agent/user-decisions/UD-014-account-refresh-performance-budget-scope.md, Resolution.
- agent/user-decisions/UD-015-trading-post-refresh-performance-budget-scope.md, Resolution.
- docs/TARGET_ARCHITECTURE.md sections 4.1, 9, 10.2, 10.3, 17 and 21.
- docs/CURRENT_ARCHITECTURE.md section 7.
- docs/TEST_STRATEGY.md section 34.
- agent/stories/STORY-WEB-033-recover-overlapping-account-refresh-scopes.md, Acceptance Criteria and Constraints.
- agent/stories/STORY-PERF-003-refresh-phase-five-page-budget-evidence.md, Follow-up Findings F001 and its existing disposition.
- agent/stories/STORY-QUALITY-005-phase-five-completion-review.md, Result findings F2 and F5.

## Blocked Work

New Phase 5 implementation contracts for cached TP/account freshness presentation and asynchronous recalculation, any residual work after STORY-WEB-033, and the updated initial-page/refresh performance verification contract. Milestone closure remains unavailable until that behavior and its required measurements are evidenced. This request does not stop or modify the active story's execution.

## Blocking User Decision

None.

## Architect Decision

Class C — architecturally significant technical contract; product behavior is already resolved by UD-013 through UD-015. No further User Decision is required.

Select application-owned stale-while-revalidate coordination: return synchronous backend calculations from usable last-good cached inputs with a versioned freshness envelope; refresh required account sources and shared TP prices through separate existing in-process tasks; poll task status and automatically request a new backend calculation for the browser's current settings. Confirm freshness for every required source and required TP coverage against the inputs actually used before declaring the rendered replacement final. Task success alone is insufficient, including when an overlapping task has narrower scope or changed account data expands TP requirements. Preserve account concurrency exclusion, source-specific markers/TTL, the shared ten-minute TP cache, existing fee mathematics and domain states. Keep partial writes out of cached input capture and correlate summary/detail input versions. Bound the whole recovery cycle by the existing five-minute deadline, including successive tasks and recalculation; failures retain usable displayed results and expose retry.

The full normative contract is TARGET_ARCHITECTURE sections 9.1 and 17. ADR-007 records the alternatives and rationale. Engineering judgment favors this fit because the existing task/HTTP boundary can supply it without storing per-page calculation jobs or introducing deployment machinery. The runner-up, server-owned saved calculation jobs, adds settings/result retention and supersession lifecycle without evidence that calculation runtime itself needs asynchronous execution. Blocking until fresh contradicts UD-015; push/distributed coordination adds no demonstrated benefit for this single-instance deployment.

## Resolution

Updated docs/TARGET_ARCHITECTURE.md sections 9 and 17 and added docs/architecture/decisions/ADR-007-cached-crafting-refresh-contract.md. The architecture now separates initial cached-page completion within seven seconds from separately measured asynchronous refresh and recalculation, and records UD-013's measured-compliance acceptance policy without a manual confirmation gate. No implementation delivery, performance compliance or phase closure is claimed. CURRENT_ARCHITECTURE remains current-state evidence and was not rewritten as target behavior.

Planner follow-up: reconcile STORY-WEB-033's older prohibition on stale results and requirement to refresh before delivering results with UD-015, using the permitted story lifecycle after assessing ongoing execution; do not assume its overlapping-scope repair delivers provisional caching or TP refresh. Identify residual implementation boundaries for last-good input capture/publication, application freshness/required-input coordination, Crafting Profit HTTP gating and TP task status, browser polling/replacement/context preservation, and compatible resolution details. Existing source-marker and account exclusion guarantees remain mandatory. No active story, backlog or planner state was changed by this run.

Have TEST_STRATEGY section 34's owner reconcile its user-confirmation wording and quiescence sampling: independently identify complete rendering of the initial calculation version and later refreshed versions, recording initial elapsed time, refresh/terminal timings, recalculation/rendering and total final-result time. Retain real-data startup/repeat/All-scope evidence, effective settings, stage attribution, invariant/equivalence checks and isolated normal correctness tests. Include fresh, account-expired, TP-expired, both-expired, missing-cache, insufficient-scope overlap and failure/timeout states; a loading placeholder or historical fresh-cache run cannot establish acceptance. No numerical refresh-speed budget is invented; five-minute recovery remains bounded. Preserve TEST_STRATEGY test-layer requirements and QUALITY_METRICS coverage reporting; independent QA must prepare subsequent story plans.

Disposition of STORY-PERF-003 F001 and STORY-QUALITY-005 F2/F5, roadmap exit-criterion markings and Phase 5 closure remain planner-owned. UD-013 removes the manual acceptance policy question, but does not accept unmeasured behavior. Closure remains unavailable until implementation, required measurements and quality gates evidence the confirmed contract. Other feature freshness policies and future distributed coordination are outside this request.
