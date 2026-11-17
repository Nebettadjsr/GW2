# ADR-007 — Cached Crafting Profit refresh coordination

## Status

ACCEPTED

## Context

AR-007 asks for the technical contract that implements resolved UD-015 without making Crafting Profit wait for account synchronization or on-demand TP fetches. UD-014 delegates its account-budget question to UD-015; UD-013 makes reproducible measured compliance sufficient without a separate manual acceptance gate. These decisions settle product behavior, not implementation readiness.

Observed documentation evidence: CURRENT_ARCHITECTURE section 7 describes a blocking account freshness response and one retry, and section 5 describes the shared TP cache/task facility. STORY-PERF-003 F001 records a 17,206 ms expired-price page opening with synchronous upstream work, while its fresh-price measurements were compliant. That observation motivates removing the external wait from the initial response; it is not a benchmark of the selected design. STORY-QUALITY-005 F2/F5 identify acceptance-policy and stale-account timing gaps. STORY-WEB-033 addresses insufficient-scope account-task reuse and prohibits stale serving in its older contract; it does not establish delivery of UD-015 or the TP contract. The working tree contains ongoing implementation changes, which this decision neither modifies nor certifies.

## Constraints

- Product behavior is already fixed by UD-013 through UD-015; economic calculations and TP fees remain unchanged and backend-owned.
- Preserve source-specific completion markers, account-write exclusion, separate account/TP refresh, shared ten-minute TP caching and existing batching.
- Current single-user, single-backend-instance deployment needs no distributed coordination or new service.
- Preserve compatible row/detail calculation inputs, explicit unavailable/blocked domain states and backend-only secrets.
- QA owns subsequent story plans; TEST_STRATEGY owns methodology and QUALITY_METRICS owns coverage reporting. No test baseline or measurement acceptance changes here.

## Decision

Class C: select application-owned stale-while-revalidate coordination with a versioned freshness envelope, synchronous cached calculations, separate existing in-process refresh tasks, and browser polling followed by automatic backend recalculation requests. The normative contract is TARGET_ARCHITECTURE sections 9.1 and 17; this ADR records rationale rather than defining a second API specification.

## Rationale

Engineering judgment: this is the smallest fit for the existing HTTP/task boundary and a single-maintainer migration. The browser already owns presentation state and task feedback; resubmitting current settings avoids server-side storage and expiry of per-page calculation requests. All authoritative freshness decisions, required-input selection and calculations stay in the application/backend. Input versions and last-good publication make provisional rows explainable and prevent task completion or partially written data from masquerading as a fresh result.

Separate account and shared TP operations preserve their distinct scopes and caching policies. Rechecking uncovered requirements after reused tasks handles overlapping scopes without widening account-write concurrency. A single recovery deadline prevents an unbounded chain of tasks from keeping the browser animated indefinitely. This reuses existing technology rather than adding ecosystem churn, recurring costs or operational moving parts. Its costs are additional application/HTTP state and client correlation, and maintaining last-good input views during incremental writes. Those are bounded responsibilities that can be implemented incrementally. Transport presentation is reversible without moving economic logic or changing deployment.

## Alternatives Considered

- **Server-owned saved calculation jobs that publish the final replacement:** the viable runner-up centralizes recalculation lifecycle, but needs stored request/settings/result state, retention, supersession and result retrieval on top of existing refresh tasks. No measured calculation bottleneck or current deployment requirement justifies it. Retain it only as a possible later response to measurement under UD-007.
- **Block the initial HTTP request until every refresh completes:** simpler result freshness but conflicts with UD-015 and retains the external-fetch wait evidenced in F001. A longer timeout does not provide the confirmed cached-page behavior.
- **SSE/WebSocket push or a distributed job/cache platform:** adds connection/reconnection or deployment coordination responsibilities without a demonstrated requirement at this project's scale. Polling existing HTTP task status is sufficient; no new library selection is necessary for this decision.

## Consequences

Implementation must adapt Crafting Profit's freshness gate and synchronous TP refresh path, capture compatible last-good inputs, report freshness without changing domain states, coordinate remaining source/ID demands, and correlate replacement calculations/details in the browser. The selected design is not a measured optimization of the domain algorithm and does not authorize altering profit mathematics.

Verification boundaries: domain/result invariants continue to cover quantities, costs, profit and shopping-list agreement; application tests use controlled clocks and fake ports for input capture, freshness, scope coverage and orchestration; PostgreSQL integration verifies source data/marker publication and failure behavior; adapter fixtures cover TP batching and unavailable-price outcomes; HTTP/task tests exercise actual asynchronous admission and safe status/response semantics; frontend tests cover current-context replacement, preserved state and bounded failures. Normal correctness tests stay isolated from live APIs and the user's database. Representative real-data browser runs supply performance evidence separately. Preserve line/branch KPI reporting and affected critical-branch review; coverage is not proof of behavior.

Planner follow-up must reconcile the active story's older stale-data prohibition without assuming it implemented this contract; identify residual account and TP behavior after its result; obtain independently prepared QA plans and updated measurements; and disposition PERF-003 F001 and QUALITY-005 F2/F5. TEST_STRATEGY section 34's owner must remove the manual acceptance gate and split initial-version completion from later refresh/recalculation, preserving reproducibility, full rendering, invariants and real-data equivalence requirements. The roadmap's historical Done markings need planner assessment against the newly confirmed behavior and evidence before closure. This ADR creates no stories and changes no active execution.

## References

- AR-007-cached-crafting-refresh-contract.md
- UD-013-phase-five-performance-user-acceptance.md, Resolution
- UD-014-account-refresh-performance-budget-scope.md, Resolution
- UD-015-trading-post-refresh-performance-budget-scope.md, Resolution
- TARGET_ARCHITECTURE sections 4.1, 9.1, 10.2–10.3, 17 and 21
- CURRENT_ARCHITECTURE sections 5 and 7; ROADMAP section 9
- TEST_STRATEGY sections 7–9, 34 and 35.3; QUALITY_METRICS, Test coverage KPI
- STORY-WEB-033, Acceptance Criteria and Constraints
- STORY-PERF-003, Follow-up Findings F001 and disposition
- STORY-QUALITY-005, Result findings F2 and F5
