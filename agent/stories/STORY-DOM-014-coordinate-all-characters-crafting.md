## Story ID

STORY-DOM-014

## Title

Coordinate Crafting Profit plans across eligible characters

## Status

TODO

## Milestone

milestone-01

## Goal

Implement the resolved all-characters behavior in DOMAIN_SPEC.md §2.2.1 while preserving character binding and per-step crafting eligibility.

## Authoritative Source Documents / Sections

- Supplied Phase 1 objective: agreement with DOMAIN_SPEC.md in the current structure.
- docs/DOMAIN_SPEC.md §2.2.1, §6–11.1, §17–19, §22, §28–30, §42.
- agent/user-decisions/UD-004-all-characters-calculation-semantics.md (RESOLVED).
- STORY-DOM-012, Goal and Result.

## Context

Resolved UD-004 extends STORY-DOM-012 for Crafting Profit only. The existing story records single-character inventory wiring; coordinated planning is requested behavior, not verified existing functionality.

## Acceptance Criteria

- Crafting Profit offers All characters first and selects it initially; individual-character behavior remains available. Discovery retains its individual-only selector and existing default.
- Pass the selected planning mode explicitly through the view/controller/calculation path. All characters produces a coordinated account plan with eligible characters assigned per crafting step, allowing transferable intermediates between characters.
- Enforce recipe/discipline/rating eligibility at each step and soulbound ownership at consumption. Two characters each holding one soulbound ingredient cannot jointly satisfy a step requiring two for one character.
- Preserve sellable/account-wide versus character-bound quantities and valuation; prevent resource reuse within a plan, including shared inventory and transferred intermediates.
- Preserve existing buying, budget, economic-cost and simulation-limit rules. Unresolvable plans retain the applicable existing blocked/unavailable state.
- Record actual verification evidence and update current architecture documentation only to describe implemented behavior.

## Required Tests

- Add failing regression tests before changes for coordinated crafting with intermediates produced by different eligible characters and a final step performed by another character.
- Cover split soulbound inputs that cannot satisfy one step, valid same-owner consumption, ineligible step assignment, transferable intermediate reuse, and shared inventory not being double counted.
- Use PostgreSQL integration coverage for any changed inventory/character/recipe loading semantics; use the existing test conventions and real constraints rather than in-memory substitutes.
- Verify Profit default/mode switching through displayed results, Discovery individual-only behavior, and graceful zero-character handling. Run relevant regressions and the project suite; report unexecuted checks honestly.

## Constraints

Keep work in the current structure and limited to the specified Phase 1 behavior. No performance optimization, broad restructuring, new framework, or invented domain rules. Follow the failing-test-first bug-fix workflow; do not claim unexecuted verification.

## Dependencies

STORY-DOM-013 (preserve blocked/unavailable results); STORY-DOM-012 (DONE). Resolved UD-004 supplies the product decision.

## Definition of Done

Acceptance criteria met, required verification completed and recorded in Result, relevant regression suite passing, and authoritative documentation consistent with the implemented behavior.

## Result

Not started.

## Blockers

None.

