## Story ID

STORY-DOM-022

## Title

Establish a shared copper-accurate Trading Post sale fee calculation

## Status

TODO

## Milestone

milestone-05

## Goal

Provide a reusable backend/domain calculation of gross sale value, listing fee, exchange fee and net proceeds for an explicitly specified sale, without selecting the unresolved crafting or salvage sale-grouping policy.

## Authoritative Source Documents / Sections

- docs/DOMAIN_SPEC.md sections 3, 20, 24-27, 37 and 45-47, DQ-001: Request-008's authoritative fee requirement and preserved unrelated economics.
- docs/TARGET_ARCHITECTURE.md sections 7-12: shared domain authority, thin adapters and presentation-only frontend.
- docs/CURRENT_ARCHITECTURE.md sections 5.1-5.3: crafting application boundaries and the separate Ectoplasm calculator.
- docs/ROADMAP.md, supplied Phase 5 section: backend-provided economic results and JavaFX/web coexistence.
- agent/user-decisions/UD-011-trading-post-sale-quantity-basis.md: unresolved consumer grouping, outside this independent primitive's scope.

## Context

The Product Owner superseded the documented Crafting Profit fee exemption and requires accurate copper amounts across sale-revenue consumers. The supplied completed WEB-008 record describes gross total sell value; CURRENT_ARCHITECTURE documents shared crafting results and a separate fee-inclusive Ectoplasm model. Neither establishes accurate common rounding. Implementation inspection must establish the actual call paths; no current code defect beyond the reported behavior is asserted here. UD-011 blocks consumer integration, but not a calculation whose caller supplies an explicit sale basis.

## Acceptance Criteria

1. Inspect the existing monetary/domain concepts and trace where sale revenue enters Crafting Profit, Discovery, their shared table/fresh-detail results and Ectoplasm profit/Luck costs. Record a concise consumer map and concrete integration consequences in Result; distinguish observed code from documentation. Reuse an existing suitable domain concept rather than introduce a second fee calculator unnecessarily.
2. Verify the game's listing/exchange fee rounding, minimum amounts and transaction quantity basis with reliable source or reproducible game evidence. Record sources, date and discriminating copper examples. Request-008's 300c example alone cannot establish rounding. If evidence cannot establish an edge rule, report that precise limitation rather than substitute a guessed 15% formula or claim accuracy.
3. Implement a pure backend/domain calculation accepting an explicit sale amount/basis and returning authoritative gross copper, separate 5% listing and 10% exchange fees, total fees and net proceeds. Use exact integer/rational arithmetic as appropriate, with documented supported inputs, zero-sale behavior, invalid-input handling and overflow safety. Match the verified actual-sale rules. Do not round a combined 15% if the two fee components have separate rounding.
4. Preserve the original market quote and distinguish it from fee-adjusted net proceeds. Both instant and listing sales must use their selected gross price without a second fee deduction. No fee calculation belongs in HTTP mapping, JavaFX or Vue.
5. Do not wire this primitive into crafting per-craft/total results or Ectoplasm's expected-yield calculation until UD-011 is resolved and integration is separately scoped. Do not choose per-item, per-craft, all-crafts or fractional-yield grouping implicitly through an API default. Document the explicit input basis so later callers cannot confuse those quantities.
6. Record verified fee semantics at DOMAIN_SPEC section 25 and implementation facts at CURRENT_ARCHITECTURE as applicable. Keep consumer-policy questions in UD-011. Report that existing displayed profits remain uncorrected until integration; do not claim Request-008 or Phase 5 complete.

## Required Tests

- Database-independent fee tests against independently established examples: 300c gross produces 15c listing, 30c exchange and 255c net; small copper amounts, boundary values and amounts distinguishing separate fee rounding from a combined multiplier.
- Verified minimum-fee cases, no-sale and rejected-input behavior, explicit multi-item sale quantities, and supported numeric limits/overflow handling. Expected fixtures must not merely call the production formula again.
- Assert gross minus the two fee components equals returned net and that input market prices are unchanged. Run affected existing domain tests if an existing shared abstraction is changed; record precisely what was verified. No live trade or account mutation is required.

## Constraints

- No frontend/domain duplication, economic-policy invention or broad monetary refactor. No HTTP, JSON, persistence, JavaFX or GW2 API models in the domain calculation.
- Preserve owned-material valuation, buying costs/budgets, recipe eligibility, resolution, quantities and yield assumptions.
- Do not modify active STORY-API-009 or icon infrastructure. Routine type naming and placement within established domain boundaries belong to implementation.
- Review evidence and implement this bounded primitive only; do not commission or implement speculative dependent fixes from the consumer map.

## Dependencies

None. UD-011 blocks consumer integration only; this story receives an explicit sale basis.

## Definition of Done

A tested shared domain fee calculation matches evidenced actual-sale behavior, with documented input basis and source-backed edge cases. The consumer map and outstanding integration policy are recorded without claiming delivered profit correction.

## Result

Not started.

## Blockers

None.
