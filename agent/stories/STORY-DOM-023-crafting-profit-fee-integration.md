## Story ID

STORY-DOM-023

## Title

Apply the decided Trading Post fee model to crafting profits and presentation

## Status

TODO

## Milestone

milestone-05

## Goal

Make Crafting Profit and Discovery use the resolved 15% profitability policy through the shared domain, application, HTTP, browser and JavaFX paths while preserving gross displayed prices.

## Authoritative Source Documents / Sections

- docs/DOMAIN_SPEC.md sections 2.1.1, 24-27 and 37: economic policy and presentation.
- agent/user-decisions/UD-011-trading-post-sale-quantity-basis.md: resolved product answer.
- agent/stories/STORY-DOM-022-trading-post-sale-fee-calculation.md, Result consumer map and limitations.
- docs/TARGET_ARCHITECTURE.md sections 12-14: backend authority, shared row and fresh-detail contract, JavaFX coexistence.
- Supplied docs/ROADMAP.md Phase 5: authoritative browser results and shared backend.
- docs/TEST_STRATEGY.md sections 12 and 36: targeted frontend checks and CI gate.

## Context

DOM-022 records that crafting revenue is gross and shared by Profit, Discovery, table/detail DTOs and JavaFX. Its explicit-sale primitive is not wired into those paths. UD-011 now chooses a simpler percentage model, superseding the earlier transaction-rounding request. Do not blindly reduce computeRevenue: its gross value also drives market displays and availability predicates. Implementation must confirm the recorded consumer map against the current revision after the active DOM-021 finishes; this story must preserve that story's non-TP policy.

## Acceptance Criteria

1. Trace and correct the shared authoritative calculation of per-craft and total profit under DOMAIN_SPEC section 25. Apply 15% to the corresponding sell value once, preserving existing quantity and material-cost aggregation. Cover both chosen sale-price modes and both Profit and Discovery consumers.
2. Preserve gross revenue/output value, total sell value and instant buy/sell quotes. Preserve gross-based availability predicates and all existing null/unavailable distinctions. Do not modify resolution, eligibility, inventory opportunity cost, buying costs, budgets, counts or non-TP policy to compensate for the changed profit.
3. Use one backend/domain owner for the decided percentage policy, reusing suitable monetary concepts. Do not substitute DOM-022's separately rounded minimum-fee calculation for that model. No independent arithmetic in application mapping, HTTP, JavaFX or Vue.
4. Carry corrected profit fields through both services, both table routes and both fresh-detail routes. Preserve fresh response association, quantity bases and tree semantics. Existing profit filters, sorting and rankings consume the corrected backend values; Discovery losses remain eligible.
5. In the Profit browser calculation details label Profit and Total Profit with a small 'after 15% TP fees' note. Preserve gross Output Revenue, Total Sell Value and market-price labels. Align shared Discovery presentation where already present; WEB-012 owns the new Discovery workflow and must consume these corrected values.
6. Ensure JavaFX consumes the same corrected backend profit and gross prices. Correct any tooltip or notice claiming fees still need deduction from displayed profit; do not move JavaFX to HTTP.
7. Update delivered contract/implementation documentation and calculation guidance as required by existing documentation policy. Record the consumer verification and limitations in Result. Do not claim Phase 5 performance or completion.

## Required Tests

- Focused domain fixtures: 300c gross less 204c own materials gives 51c profit; purchased costs, loss/zero profit, multi-output and multi-craft totals, both sale modes and unavailable prices. Include a small-copper case that distinguishes the decided percentage model from minimum transaction fees; no new exact-sale rounding requirement.
- Focused application/HTTP tests demonstrate both Profit and Discovery table/fresh-detail results retain gross values and corrected authoritative profits, including null results. Test projections with fixed distinctive values rather than reimplementing formulas outside domain tests.
- Browser component checks for gross labels, fee notes, backend-provided profits and resulting profit sort/filter behavior; preserve losses in Discovery.
- Explicit local browser smoke of Profit table and selected detail against a backend response. Explicit local TestFX check of affected JavaFX profit presentation/tooltip and gross values. Record unavailable interactive verification as a limitation, never a pass.
- Run directly affected tests locally; the complete regression/type-check/build suites remain the GitHub CI gate under TEST_STRATEGY section 36.

## Constraints

- Preserve DOM-021's completed behavior when implementation reaches this story; do not edit the currently active story.
- No transaction-rounding research, frontend fee formula, broad monetary refactor, new endpoint design or resolver retuning.
- Do not build Discovery's page here; WEB-012 owns that workflow.
- Ectoplasm integration is separately scoped in STORY-DOM-024. Keep the shared percentage-policy owner reusable within existing domain boundaries.

## Dependencies

Resolved UD-011; completed STORY-DOM-022, STORY-WEB-007 and STORY-WEB-008. No unresolved decision blocks this work. Integrate against the current shared-domain revision without overwriting DOM-021 changes.

## Definition of Done

Corrected backend profits and unchanged gross values reach both client families, required targeted evidence is recorded, documentation reflects delivered behavior, and the story revision passes the CI gate.

## Result

Not started.

## Blockers

None.

