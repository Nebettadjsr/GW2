## Story ID

STORY-WEB-016

## Title

Make Crafting Resolution compact with collapsed ingredient groups

## Status

TODO

## Milestone

milestone-05

## Goal

Let users scan required items, quantities, sourcing and crafters before expanding nested ingredients, without normal-view algorithm bookkeeping.

## Authoritative Source Documents / Sections

- docs/DOMAIN_SPEC.md section 2.1.1: compact resolution and fresh-summary presentation.
- docs/TARGET_ARCHITECTURE.md sections 12-14: backend authority, resolution consistency and special states.
- docs/ROADMAP.md supplied Phase 5: Crafting Profit resolution rendering and frontend interaction tests.
- docs/FRONTEND_UX_GUIDELINES.md: applicable detail, progressive disclosure and accessibility guidance.
- docs/TEST_STRATEGY.md sections 12 and 36: frontend verification and CI gate.
- agent/stories/STORY-WEB-007-profit-resolution-detail-view.md: existing tree and fresh-resolution behavior.
- agent/stories/STORY-WEB-015-profit-purchase-and-blocking-details.md: adjacent counted-craft purchases and concrete blocking explanations.
- agent/product-owner-requests/Request-012-crafting-tree.md.

## Context

WEB-007 records open recursive disclosures, detailed bookkeeping and a fresh-row summary. Request-012 changes their normal presentation. WEB-015 already owns counted-craft purchases and useful item-specific blocking explanations; this story complements that scope. Keep the existing fresh calculation because it supplies the authoritative tree; its duplicate row summary need not remain in the normal view.

## Acceptance Criteria

1. Each normal node shows item identity, required quantity, supplied sourcing labels (including mixed sourcing) and the supplied crafter under "Crafted by" where applicable. Preserve item icons/fallbacks and meaningful blocked/unavailable states, including PRICE_UNAVAILABLE and UNVALUED_NONTRADEABLE, without converting missing facts into zero or success.
2. Root and nested ingredient groups start collapsed. Their accessible disclosure controls expose all ordered children on demand, recursively, with no depth cap, merged occurrences or discarded requirements. Keep each visible node's compact summary outside its child disclosure. Newly selected or replaced resolution data starts collapsed; expanding one group does not automatically expand descendants.
3. Remove the permanent introductory paragraph beneath Crafting resolution and the normal per-node stock/crafted/bought/missing bookkeeping rows, producing recipe/recipe ID, crafts run and produced total. Keep useful blocking causes from WEB-015. Optional technical details may retain existing facts; adding a diagnostic feature is not required.
4. Assess cash, opportunity and effective cost rows against the compact-summary goal; record the retained/removed presentation choices and rationale in Result. Render retained amounts as supplied without aggregating inclusive costs or recalculating economics.
5. Remove the redundant normal-view THIS RECIPE IN THAT FRESH CALCULATION block. Retain the backend fresh-resolution request, response association, selection invalidation and distinct loading/error/absent-candidate behavior. Preserve a concise or optional basis explanation so the tree is not misrepresented as the counted-craft execution trace. Table values and WEB-015's counted-craft purchases retain their own basis.
6. Verify wide/narrow layouts and keyboard disclosure operation. Reuse existing components, preserve shared Discovery behavior where affected, and record any shared presentation impact. Update affected implementation documentation with the delivered behavior and verification limits.

## Required Tests

- Focused component tests for initially collapsed root/nested groups, independent keyboard expansion, repeated ordered items, replacement/selection reset, mixed sourcing, crafter label and required quantities.
- Rendering fixtures confirm removed prose/bookkeeping/fresh summary, retained economic values unchanged, useful blocking causes, special states and null-versus-zero distinctions.
- Retain focused response-association and loading/error/absent-candidate coverage; verify hiding the fresh summary neither replaces table values nor removes the resolution request.
- Explicit local browser smoke at wide and narrow widths: select a real backend result, expand its root and nested groups where present, compare displayed facts with its response, and check keyboard reachability. Use controlled deep/mixed/blocked fixtures for states absent from live data and report that distinction. Update affected browser assertions for intentional collapsed presentation.
- Run directly affected frontend tests and type checking; the story revision must pass the GitHub CI gate. No full local regression mandate.

## Constraints

- Presentation only: preserve backend calculations, DTO data and JavaFX coexistence. No frontend crafting reconstruction, economic calculation or contract redesign.
- No removal of useful errors or required quantities to achieve compactness. No arbitrary cleanup or new diagnostic subsystem.
- Do not modify the active STORY-WEB-013 or CURRENT_STORY.md. No performance-gate or milestone-closure claim.

## Dependencies

Completed STORY-WEB-007 and STORY-WEB-010. Execute after STORY-WEB-015 to preserve its selected-detail and item-specific blocking changes; both are already scoped and require no new decision.

## Definition of Done

Compact summaries and recursive collapsed disclosures satisfy the acceptance criteria, targeted component and browser evidence and limitations are recorded, affected documentation is updated, and the story revision passes the CI gate.

## Result

Not started.

## Blockers

None.
