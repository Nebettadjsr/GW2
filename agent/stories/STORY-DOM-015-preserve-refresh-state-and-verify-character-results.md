## Story ID

STORY-DOM-015

## Title

Preserve crafting view selections and verify character-sensitive results

## Status

TODO

## Milestone

milestone-01

## Goal

Preserve valid session selections during data refreshes in both crafting views and verify that individual-character changes reach displayed calculations, as required by DOMAIN_SPEC.md §2.2.1.

## Authoritative Source Documents / Sections

- Supplied Phase 1 objective: agreement with DOMAIN_SPEC.md in the current structure.
- docs/DOMAIN_SPEC.md §2.2.1 and §11.1.
- STORY-DOM-012, Acceptance Criteria and Result (incomplete interactive verification).
- agent/user-decisions/UD-004-all-characters-calculation-semantics.md (RESOLVED).

## Context

STORY-DOM-012 records a selection listener and preservation of character choice, but explicitly lacks interactive verification. The product observations do not establish that the selector is defective; establish behavior with different relevant bound-material data.

## Acceptance Criteria

- Trace individual-character selection through each view, controller, domain calculation and displayed list using characters whose relevant bound inventory differs. Fix only demonstrated defects; if correct, record evidence without unnecessary behavior changes.
- Automatic and manual data refreshes preserve valid character, sort mode, sort direction and comparable existing filter/sort selections in both views for the current session.
- Initial creation uses authoritative defaults. Removed options fall back gracefully; invalid selections are not retained.
- Updated results use current selections rather than resetting controls to defaults; verify visible recalculation when relevant bound-material differences affect the plan.
- Keep both views consistent without introducing persisted settings. Record the actual cause of any confirmed refresh or selection defect and the evidence for its correction.

## Required Tests

- For each demonstrated defect, add a failing regression before the fix at the narrowest practical boundary; verify refresh state preservation and invalid-option fallback.
- Verify both flows with distinct relevant bound-material data, including a control where identical results are valid; exercise selector-to-displayed-result behavior.
- Exercise manual and automatic refresh after changing sort mode, direction, character and existing comparable filters. Record actual interactive evidence and any limitations.
- Use existing PostgreSQL integration coverage for binding-dependent data semantics, extending it if the fix changes that path; run relevant regressions and the project suite.

## Constraints

Keep work in the current structure and limited to the specified Phase 1 behavior. No performance optimization, broad restructuring, new framework, or invented domain rules. Follow the failing-test-first bug-fix workflow; do not claim unexecuted verification.

## Dependencies

STORY-DOM-013; STORY-DOM-014 (establishes final Profit selector semantics); STORY-DOM-012 (DONE).

## Definition of Done

Acceptance criteria met, required verification completed and recorded in Result, relevant regression suite passing, and authoritative documentation consistent with the implemented behavior.

## Result

Not started.

## Blockers

None.

