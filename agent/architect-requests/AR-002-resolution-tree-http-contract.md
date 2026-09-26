## Status

RESOLVED

## Architecture Question

What HTTP contract and calculation-context lifecycle should expose backend-authoritative resolution trees for Crafting Profit and Crafting Discovery to the Phase 5 browser frontend?

Resolve the transport schema explicitly marked TBD in TARGET_ARCHITECTURE.md section 13, and how a detail request obtains the corresponding calculation context when the existing calculation endpoints create a fresh application-service instance per request. Establish how the browser associates a tree with its selected recipe, scope and settings without implying that a newly calculated detail necessarily represents an earlier table result.

## Context and Constraints

The supplied Phase 5 roadmap requires a crafting-profit table and resolution-tree view, discovery results, backend-provided special domain states, and JavaFX/web coexistence. The framework/language decision is already established by AR-001 and TARGET_ARCHITECTURE.md section 4.1.

CURRENT_ARCHITECTURE.md sections 5.5 and 5.6 document fresh application services per HTTP calculation request and row responses without trees. Section 5.5 describes lazy tree building for a selected row and a future detail endpoint. Section 5.6 records Discovery's state retention after an empty reload, making context isolation material. Section 5.11 and STORY-WEB-001 confirm that the browser has no tree contract or tree view.

The unresolved choice concerns the boundary and lifetime of calculation context, not component naming or local implementation structure. Decide whether detail is independently calculated from explicit inputs or tied to retained calculation state, and document the resulting consistency and lifecycle guarantees. Preserve the simplest design that meets those guarantees; do not assume a cache, session store or task mechanism is necessary.

Backend/domain logic must decide recipe resolution, quantities, costs and domain states. Transport must contain structured data rather than JavaFX widgets or frontend styles. Frontend code may render and expand trees but must not reconstruct resolution from quotes or missing-material maps. Preserve JavaFX's existing application-service access and the Phase 5 full-page performance requirement; no new performance evidence is asserted here.

## Authoritative References

- Supplied docs/ROADMAP.md Phase 5, Objective, Exit Criteria and High-Level Stories: resolution-tree presentation, backend authority, coexistence and full-browser-page performance.
- docs/TARGET_ARCHITECTURE.md sections 4.1, 12, 13 and 14: established frontend technology, presentation boundary, TBD tree schema and domain-state ownership.
- docs/CURRENT_ARCHITECTURE.md sections 5.5, 5.6 and 5.11: request-local calculation services, omitted trees, lazy detail and current browser limitations.
- agent/stories/STORY-WEB-001-crafting-profit-table.md, recorded Result: table implemented without a resolution-tree contract or frontend tree inference.

## Blocked Work

Planning the Phase 5 resolution-tree HTTP implementation and its browser consumer is blocked until the transport and calculation-context guarantees are established. The existing STORY-WEB-003 bank/materials presentation task remains executable and is unaffected. This request does not block all Phase 5 work or request milestone closure.

## Blocking User Decision

None.

## Architect Decision

Class C ? architecturally significant technical decision; no Product Owner decision required.

Select fresh, request-local resolution detail from explicit calculation inputs and recipe ID.
TARGET_ARCHITECTURE section 13 defines the two POST operations, effective-input echoes,
recursive semantic nodes, errors, single-craft basis, consistency limits and browser generation
checks. Detail is a newly calculated explanation, not retrieval of the previous table result.
JavaFX retains in-process service access; the table contracts remain compatible.

Architectural judgment: this extends existing request isolation with the least operational
complexity. The runner-up, retained immutable calculation contexts keyed by ID, could explain
an exact historical table result but introduces retention, eviction, expiry and restart rules
without an established historical-snapshot requirement. Embedding all trees would add
unselected work to page loading. No performance advantage is asserted; UD-007 still requires
measurement before the detail endpoint execution policy is finalized. ADR-002 records rationale.

## Resolution

Replaced TARGET_ARCHITECTURE section 13's TBD with the normative transport and context
contract; recorded significant rationale and alternatives in ADR-002. No current-state,
implementation, story, backlog or roadmap edits were made. Blocking User Decision remains None.

Planner follow-up: turn the decided contract into bounded domain/application trace, HTTP and
browser work, preserving existing domain rules and JavaFX access. Include isolation, missing
candidate, blocked/unavailable, null-cost and browser-race verification, representative detail
runtime measurements under UD-007 and full-page performance verification under section 33.
The decision unblocks contract planning, not Phase 5 completion.

Relevant discovered limitation: craft.RecipeTreeBuilder currently expands the first producing
recipe with craft/base/cycle actions; it does not carry actual inventory/buy choices, quantities,
costs or semantic states. A DTO wrapper over that skeleton cannot meet this contract. Planner
must account for backend semantic trace support before claiming resolution-tree functionality;
no implementation was attempted here. Request-local captured inputs also do not establish an
atomic database/graph snapshot during concurrent synchronization; future synchronization
consistency remains the separate concern identified by UD-007. No broader review was undertaken.
