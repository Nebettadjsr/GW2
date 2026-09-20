# UD-004 — All-characters calculation semantics

## Status

RESOLVED

## Decision Needed

1. Should `All characters` display independent plans for each eligible character, or a coordinated account plan assigning crafting steps to eligible characters? For example, if A and B each own one soulbound copy of an ingredient and a craft requires two, those copies cannot simply be combined for a craft by either character under §11.1. Specify the intended result and whether cross-character crafting of transferable intermediates is part of this mode.
2. For Discovery, should results identify each character who can discover a recipe, or aggregate recipes discoverable by at least one character? How should character-specific eligibility and costs be presented in an aggregate result?
3. Confirm whether the requested `All characters` default replaces UD-001's explicit first-synced-character default. The request conditions its default change on existing authoritative behavior, and UD-001 establishes that behavior.

## Why This Is Needed

DOMAIN_SPEC.md §11.1 restricts soulbound use to the bound character, while §6–8 and §34–38 define character-specific recipe/discovery eligibility. The request requires considering all characters without ignoring binding, but does not specify how multiple valid character plans become displayed results. These alternatives change domain and product behavior rather than merely implementation details.

## Context

- Product Owner request: `all-characters-crafting-calculation.md`.
- Unambiguous intended behavior from both inbox requests is now owned by DOMAIN_SPEC.md §2.2.1; neither request is deleted or reported processed in this NEEDS_USER pass.
- STORY-DOM-012 implements individual-character selection. Its Result explicitly says interactive verification of recalculation and displayed effects was not completed.
- The second request, `preserve-crafting-view-state-and-verify-character-selection.md`, still requires concrete verification/fix planning after this decision is resolved. Its reported refresh reset and possible selection defect are observations, not independently confirmed implementation facts. Verify the selector-to-controller-to-domain-to-displayed-results path with meaningfully different bound-material data; fix only demonstrated defects. Session refresh preservation is specified in §2.2.1.
- Resolved UD-003 already supplies the simulation-cap policy; it does not answer the cross-character questions.

## Blocks

- Planning implementation of the requested all-characters mode in Phase 1.
- Completion of this inbox-processing pass; no speculative stories are created.
- Existing STORY-DOM-013 remains independently executable. The supplied To Do queue contains only milestone-01 work.

## External Input Possibly Required

Project owner choice of cross-character planning/discovery semantics and initial selection behavior.

## User Decision

1. `All characters` for Crafting Profit means a coordinated account-wide crafting plan across all synced characters.

   The complete crafting tree does not need to be executable by one single character.

   Different crafting steps may be assigned to different characters when required or beneficial.

   Example:
    - Character A crafts a transferable intermediate material.
    - Character B crafts another transferable intermediate.
    - Character C uses those transferable intermediates to craft the final item.

   This is intended and must be supported.

   Character-bound / soulbound materials remain restricted to the character they belong to.

   Bound materials from different characters must never be pooled together as if one character owned all of them.

   A crafting step that consumes character-bound material must therefore be assigned to a character who actually owns and may legally use that material.

   Transferable intermediate results may be passed between characters and used by later crafting steps.

   The planner/calculation must therefore validate character eligibility per crafting step, not require the entire crafting tree to belong to one character.

2. `All characters` does not apply to Crafting Discovery.

   Crafting Discovery keeps individual-character selection only.

   Do not add an `All characters` option to the Crafting Discovery character selector.

3. For Crafting Profit, `All characters` becomes the first selector entry and the default selection.

   This replaces UD-001's previous first-synced-character default for Crafting Profit.

   Crafting Discovery keeps its existing individual-character default behavior.

## Resolution

Planning note (2026-09-20): The explicit PO request `agent/product-owner-requests/consolidate-crafting-profit-character-and-discipline-selection.md` supersedes the separate selector presentation in point 3. Current scope selection is owned by `docs/DOMAIN_SPEC.md` §2.2.1. The coordinated planning decision and Discovery exclusion remain in effect; the original user answer is retained above as history.

RESOLVED.