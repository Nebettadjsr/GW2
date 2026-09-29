## Status

RESOLVED

## Architecture Question

Which boundary owns Ectoplasm Salvage economics for the Phase 5 web screen, and how should the screen obtain refreshed results when its inputs change? Reconcile the browser-local calculation described in `docs/DOMAIN_SPEC.md` ?2.3 with backend ownership in `docs/TARGET_ARCHITECTURE.md` ?4.1/?12 and the completed backend HTTP route in `STORY-WEB-013`. State the disposition of that route and its browser client types/modules, and which authoritative documents need correction.

## Context and Constraints

- The supplied Phase 5 objective and exit criteria require the frontend to use backend-provided results and avoid independent authoritative domain calculations.
- `docs/DOMAIN_SPEC.md` ?2.3 now describes local browser calculations from metadata, quotes and Luck data with immediate input changes. Its economic rules remain in ??45?47.
- `STORY-WEB-013` records a completed backend calculation endpoint and a browser consumer. `STORY-WEB-022` reports that the current screen instead performs local calculation and that the old client modules are unused; these are implementation findings, not an architecture decision.
- Preserve the stated product interaction and economic intent where the architecture allows it. Do not infer whether an exception, restored backend calculation, or a revised API contract is intended.

## Authoritative References

- Supplied `docs/ROADMAP.md` Phase 5 ?9, Objective and Exit Criteria.
- `docs/TARGET_ARCHITECTURE.md` ?4.1 and ?12.
- `docs/DOMAIN_SPEC.md` ?2.3 and ??45?47.
- `agent/stories/STORY-WEB-013-ectoplasm-salvage-page.md` and `agent/stories/STORY-WEB-022-align-ecto-page-label.md`.

## Blocked Work

- Restating and executing `STORY-WEB-021`'s Ectoplasm content checks against an authoritative screen contract.
- Planning any Phase 5 repair of the Ectoplasm calculation boundary or removal/restoration of the now-unused Ectoplasm API client modules.
- Confirming the Ectoplasm part of the Phase 5 frontend exit criteria and completing the Phase 5 health review.

## Blocking User Decision

None.

## Architect Decision

Class C — a significant technical decision about the frontend/backend calculation boundary and HTTP contract, applying the existing Class A rule that authoritative domain results belong in the backend.

The backend owns Ectoplasm Salvage yields, tool costs, fees, effective costs, Luck costs and Magic Find target economics. Restore a revised, input-bearing calculation API. The browser obtains Ecto/Dust quotes through backend reads, submits the current quote snapshot and selected amount, method, tool and TP modes, and automatically requests a new result when a control changes. The backend validates inputs, uses its domain rules and account Luck boundary, and returns the result with its input/quote basis. A manual TP refresh rereads only Ecto and Dust quotes and then recalculates. The browser renders backend results and manages pending, failure and superseded-response states; it does not keep economic formulas or authoritative assumptions.

The runner-up was retaining browser-local calculation to preserve synchronous updates. It would duplicate fee, yield and tool economics and fail the Phase 5 backend-authority criterion. Restoring STORY-WEB-013's old parameterless GET contract unchanged is also insufficient: it has no selected controls or Magic Find target results. The revised API preserves automatic updates without live TP fetching for each control change. The current removed route remains removed until the new contract is implemented; unused `ectoApi.ts`, `useEctoSalvage.ts` and `EctoSalvage*` transport types must be revised for that contract or deleted, not retained as dead descriptions of the old route.

## Resolution

Recorded the target boundary in `docs/TARGET_ARCHITECTURE.md` §12 and its rationale in `docs/architecture/decisions/ADR-006-backend-ectoplasm-salvage-calculation.md`. No implementation or current-state documentation was changed: `docs/CURRENT_ARCHITECTURE.md` correctly records the local calculator and removed route as current facts.

The planner must arrange correction of `docs/DOMAIN_SPEC.md` §2.3's browser-local and “without another calculation request” mechanism, preserving its automatic input response, selective TP refresh and economic intent in §§45–47. It must reconcile the Phase 5 implementation and exit evidence, revise `STORY-WEB-021`'s Ectoplasm content checks against the backend-result screen, and plan the revised API and browser client cleanup. No new product behavior or User Decision is required. Any separate architecture issue found during that work needs its own request.

