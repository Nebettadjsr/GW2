## Story ID

STORY-WEB-013

## Title

Expose the existing Ectoplasm calculation through HTTP and a browser screen

## Status

TODO

## Milestone

milestone-05

## Goal

Provide the Phase 5 Ectoplasm Salvage screen over a thin HTTP adapter to the existing application service, displaying its four fee-inclusive scenarios without duplicating calculations in the browser.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md, supplied Phase 5 section: Ectoplasm screen, backend authority, frontend interaction tests and JavaFX coexistence.
- agent/PROJECT_STATE.md: remaining Ectoplasm browser workflow and HTTP prerequisite.
- docs/CURRENT_ARCHITECTURE.md section 5.3: EctoSalvageService.calculate(), live quote acquisition and four scenarios.
- docs/DOMAIN_SPEC.md sections 2.3, 20 and 45-47: separate salvage feature, quote semantics, net proceeds and Luck costs.
- docs/TARGET_ARCHITECTURE.md sections 4.1, 8-9, 12-12.1 and 23: Vue/TypeScript, application/HTTP boundaries, shared presentation assets and measured execution policy.
- docs/FRONTEND_UX_GUIDELINES.md: applicable application navigation, shared presentation, responsive layout, state and accessibility sections, referenced by TARGET_ARCHITECTURE section 12.
- docs/TEST_STRATEGY.md section 12: frontend rendering, interaction and state verification.

## Context

The documented desktop flow already delegates live Ecto/Dust price acquisition and all four Ecto-buy/Dust-sell calculations to EctoSalvageService. This differs deliberately from the crafting screens' database-backed quotes. The supplied backlog contains no Ectoplasm HTTP/browser story. This bounded vertical slice adds transport and presentation for that existing parameterless use case; it does not redesign salvage economics. Shared icon infrastructure remains owned by API-009 and WEB-010.

## Acceptance Criteria

1. Add and document an HTTP operation for the existing EctoSalvageService.calculate() use case on the established backend runtime. Its adapter delegates once per accepted calculation and maps transport-only values; it contains no quote acquisition sequence or economic formula. Document the route, method, accepted input, response units/nullability and failures. Reject unsupported calculation inputs before delegation; do not introduce adjustable economic parameters or a new calculator.
2. Return the application/domain-provided four buy/sell scenarios and the price/result values needed by the existing presentation, including raw quotes distinguished from fee-adjusted Dust proceeds, profit and cost per 1000 Luck. Preserve precision, signs and unavailable values. If an existing presentation value needs exposing, pass it from its authoritative backend owner rather than derive it in the HTTP mapper or browser. Preserve existing yield assumptions and fee application exactly once; label assumptions as expected values rather than guaranteed drops.
3. Keep live Ecto/Dust quote fetching behind the existing application gateway. Do not replace it with synchronized database prices, add a sync prerequisite, or expose GW2 transport models/secrets. Obtain representative runtime measurements and apply TARGET_ARCHITECTURE section 23's existing measured execution policy; document the evidence and any reused task/status contract before implementing its browser consumption.
4. Add an addressable Ectoplasm Salvage destination in the established shell. Opening the screen loads the backend calculation; an explicit reload requests a fresh calculation. Present all four combinations with unambiguous instant/order buying and instant/listing selling labels, money/Luck units and concise fee-inclusive wording. Display backend values using shared formatting without recomputing fees, yields, profit, net cost or Luck costs.
5. Distinguish loading, successful results and request failure. A failed reload must not present an earlier answer as fresh or missing values as zero. Suppress duplicate in-flight reloads and prevent superseded responses or responses after leaving the screen from replacing the current screen state. Surface safe, useful errors and an explicit retry without leaking backend exception details.
6. Apply the established shell, shared styles and applicable UX guidelines, with keyboard-reachable controls, visible focus, non-color-only gain/loss meaning and narrow-screen readability. Reuse WEB-010's icon component and backend metadata when available; otherwise use the established neutral fallback. Do not add a competing cache, direct upstream image fallback or browser GW2 call. Record any remaining shared-icon integration dependency explicitly.
7. JavaFX retains its existing in-process service call and fee-inclusive behavior. Neither client acquires a second economic implementation; no changes to general crafting calculations or synchronization behavior are included.
8. Record the HTTP contract, browser flow, measured execution policy, verification evidence and limitations in CURRENT_ARCHITECTURE and this story's Result. No Crafting Profit performance or Phase 5 completion claim follows from Ectoplasm timing.

## Required Tests

- HTTP contract tests with controlled service results: exactly-once delegation, every scenario/value preserved, nullable/unavailable versus zero values, rejected input without delegation, and sanitized failures. Verify independent requests cannot surface another request's result.
- Relevant existing Ecto application/domain regressions and a focused JavaFX compatibility check if shared backend files change; preserve the established fee/yield behavior rather than add a parallel formula in tests at the transport layer.
- Frontend component/interaction tests with deliberately distinctive backend-provided numbers: all four scenarios and bases rendered unchanged, loading/failure/retry, failed reload, duplicate suppression and late-response isolation. Assert the browser does not derive result values from supplied quotes.
- Type checking/build and a real-browser controlled-response check of navigation, reload, keyboard operation and narrow layout. Inspect network evidence to verify calculation/price requests go only to the backend. Controlled origins must be isolated from any live backend proxy.
- Representative live backend timing for section 23 and a bounded browser-to-backend integration check comparing rendered scenario values with that response. Distinguish controlled-response evidence from live evidence; record unavailable live verification honestly rather than invent timings or success.

## Constraints

- One existing use case only; no user-selectable yields, additional salvage items, inventory consumption, new synchronization operation or economic redesign.
- Vue 3 and strict TypeScript; keep JavaFX, HTTP, JSON and infrastructure dependencies outside the domain.
- Reuse existing application, HTTP error and presentation patterns. Route naming and local DTO structure are routine implementation choices, not a new architectural decision.
- Shared icon delivery remains API-009/WEB-010's responsibility; do not modify the active icon story or duplicate its infrastructure.
- Do not alter Crafting Profit's separate fee rule or claim its full-page performance gate is satisfied.

## Dependencies

Existing EctoSalvageService and EctoSalvageCalculator documented in CURRENT_ARCHITECTURE section 5.3; completed STORY-WEB-004 shell. No unfinished story blocks the independent workflow. Reuse STORY-WEB-010 for shared icon integration when available.

## Definition of Done

The browser exposes the existing Ectoplasm use case through a documented thin HTTP boundary; all four scenarios display authoritative values, required verification is recorded, JavaFX coexistence is preserved and delivered behavior/limitations are documented at their owners.

## Result

Not started.

## Blockers

None.
