## Story ID

STORY-DOM-021

## Title

Implement the decided Profit non-Trading-Post material calculation option

## Status

TODO

## Milestone

milestone-05

## Goal

Implement DOMAIN_SPEC section 2.1.1's enabled and disabled material-path rule consistently through web Profit table calculation and fresh resolution detail, with an explicit calculation control.

## Authoritative Source Documents / Sections

- docs/DOMAIN_SPEC.md sections 2.1.1, 11.1-11.2, 21 and 42.
- agent/user-decisions/UD-009-non-trading-post-material-control.md and UD-010-non-tp-control-disabled-behavior.md, resolved Product Owner answers.
- Supplied docs/ROADMAP.md Phase 5 backend-authoritative controls and special-state presentation.
- docs/TARGET_ARCHITECTURE.md sections 12-14, 26 and 35; docs/FRONTEND_UX_GUIDELINES.md calculation controls and contextual state presentation.
- agent/stories/STORY-APP-012-request-local-resolution-detail.md, STORY-API-008-crafting-resolution-endpoints.md and STORY-WEB-008-profit-economic-columns.md.

## Context

The formerly missing disabled behavior is now explicitly decided. This is a web Profit rule, not a display filter and not a Discovery/JavaFX change. Existing table and detail paths share the authoritative resolver; implement the same policy there without a second algorithm. Domain classification facts must come through existing backend boundaries, independently of quote availability or icon metadata.

## Acceptance Criteria

1. Record the existing classification/settings flow before changing it, then implement section 2.1.1's decided rule in the authoritative domain path. Disabled rejects paths consuming non-TP materials even when owned, bound or recursively craftable; valid alternatives remain eligible. Enabled permits usable owned/craftable non-TP paths under existing restrictions and never invents external acquisition for an unsatisfied non-TP requirement.
2. Carry nontradeability as domain facts through existing adapters; never infer classification from a missing/zero quote, missing icon or unavailable image. Preserve PRICE_UNAVAILABLE for normally tradeable items. Do not invent unknown classification behavior if existing authoritative inputs cannot establish it; report the concrete gap through the normal blocker workflow.
3. Pass the option explicitly through web Profit application requests to both table simulation and fresh-detail evaluation. Both use the same setting and captured inputs; defaults open enabled. Preserve old caller behavior for JavaFX and Discovery and keep existing constructors/call contracts compatible where needed. No frontend-only exclusion or post-calculation row deletion.
4. Extend Profit request validation and effective echoes consistently for table and resolution. Ensure detail association includes the option so late results from the previous value cannot appear under the current setting. Keep domain types free of HTTP, JSON, persistence and JavaFX dependencies.
5. Add Allow non-Trading-Post materials to calculation/material controls, with concise help conveying the decided rule. Toggling recalculates Profit through the API; it is not a result-display filter. Preserve valid controls on refresh and distinguish it from the three display filters.
6. Retain blocked results and explain the material restriction from backend facts in selected details and affected tree nodes where supplied. Preserve ordinary domain explanations and the temporary cycle diagnostic; do not restore a general State column. Use an explicit backend restriction representation if existing reasons cannot truthfully distinguish the policy; do not overload a misleading reason or infer it in Vue.
7. Preserve binding, scope, buying, budget, daily, valuation and simulation-cap behavior outside the new policy. Alternative-path exploration must retain rollback/inventory isolation. Update affected contract documentation, CURRENT_ARCHITECTURE and the human-readable crafting guide under section 35 with actual behavior, without claiming milestone completion.

## Required Tests

- Focused domain cases: owned unbound and bound non-TP ingredients; recursive intermediates; an unsatisfied non-TP requirement; a valid alternative that avoids non-TP consumption; rejected-path rollback; both setting values. Assert existing ownership/scope/budget restrictions still apply.
- Normally tradeable missing-price cases stay PRICE_UNAVAILABLE; pure tradeable paths remain equivalent. Characterize unchanged Discovery and JavaFX/default-caller results rather than applying the new policy globally.
- Application/HTTP tests for default and explicit values, validation, effective echo, request isolation, and table/detail agreement on the rule. Verify rejected results remain represented with truthful explanations.
- Browser tests for grouping, default, recalculation, refresh preservation, option-sensitive stale-detail rejection and contextual reasons; verify no client calculation. A read-only live check may establish integration but must distinguish absent live non-TP cases from controlled evidence.
- Affected domain/application/API/frontend checks, frontend type checking and build; no speculative broad test expansion. Record performance impact evidence for the final Phase 5 gate without substituting backend timings for full-page acceptance.

## Constraints

- Do not modify the active API-009 story or redesign icon delivery. No speculative metadata sync or third-party acquisition system.
- No change to Discovery or JavaFX behavior and no restriction inferred from price availability. Preserve the shared backend boundary and existing detail consistency contract.
- Routine internal type/field naming belongs to implementation; unresolved product or architecture choices must be reported rather than guessed.

## Dependencies

UD-009 and UD-010 are RESOLVED and recorded in DOMAIN_SPEC section 2.1.1. STORY-APP-012, STORY-API-008 and STORY-WEB-008 are DONE.

## Definition of Done

The decided web Profit control is implemented end to end with authoritative path enforcement, table/detail consistency, unchanged other callers, meaningful tests and updated documentation. Remaining performance/review gates stay open.

## Result

Not started.

## Blockers

None.
