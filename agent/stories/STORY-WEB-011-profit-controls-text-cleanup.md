## Story ID

STORY-WEB-011

## Title

Complete Crafting Profit control grouping and concise presentation

## Status

TODO

## Milestone

milestone-05

## Goal

Bring the existing Profit controls and explanatory text into alignment with DOMAIN_SPEC section 2.1.1 while preserving calculation and selection behavior.

## Authoritative Source Documents / Sections

- docs/DOMAIN_SPEC.md sections 2.1.1 and 2.2.1.
- docs/TARGET_ARCHITECTURE.md section 12; docs/FRONTEND_UX_GUIDELINES.md sections 2 and 4 and applicable accessibility/presentation rules.
- Supplied docs/ROADMAP.md Phase 5 frontend migration and backend-authority criteria.
- agent/stories/STORY-WEB-006-profit-result-display-controls.md, STORY-WEB-007-profit-resolution-detail-view.md and STORY-WEB-008-profit-economic-columns.md, Results.

## Context

The completed stories cover filtering, selection, economics and fresh details. Their recorded results leave the combined controls panel, revised defaults and concise text outstanding. Request-006-UI-cleanup is represented by DOMAIN_SPEC section 2.1.1. This correction precedes extending the presentation patterns to Discovery.

## Acceptance Criteria

1. Place display controls in the Calculation controls panel as a compact Displayed results subgroup clearly distinct from calculation settings; remove the separate top-level Result display section.
2. All three established filters open enabled, remain independently reversible, and preserve the existing null/unavailable distinctions. Retain the initial maximum of 250 and Show all over the matching set. None changes calculation inputs, triggers a calculation or changes the simulation cap.
3. Remove permanent selection/keyboard instructions, the explanatory filter paragraph and dynamic limit paragraph. Retain concise labels and optional compact counts, accessible names, actionable empty/error states, hidden-selection information and necessary recipe-specific explanations.
4. Use the concise Trading Post price / item label; retain output quantity with recipe information and all monetary bases. Remove redundant summary/prose only where the same information remains clear. Preserve ordinary blocked reasons in detail, the cycle diagnostic, special states and fresh-detail basis.
5. Preserve selection by recipe identity, whole-row and keyboard selection, visible focus, valid controls on reload and navigation, and wide/narrow usability. Shared changes must preserve other screens.

## Required Tests

- Targeted component/interaction checks for three initial filters, independent reversal, placement, zero calculation/detail requests from display controls, state preservation and zero versus null.
- Real-browser wide/narrow and keyboard checks for grouped controls and reachable details; verify removed text without discarding accessible semantics or domain explanations.
- Affected frontend tests, type checking and build. No broad backend regression run for a presentation-only change.

## Constraints

- No domain, HTTP, JavaFX or icon-delivery redesign. The non-TP calculation control is separately scoped.
- Follow the frontend UX guidelines at implementation time. Do not use reduced display counts as performance acceptance.

## Dependencies

STORY-WEB-006, STORY-WEB-007 and STORY-WEB-008 are DONE.

## Definition of Done

The requested grouping, defaults and concise wording are implemented with focused browser evidence; actual presentation is recorded in CURRENT_ARCHITECTURE and this Result. No Phase 5 closure is claimed.

## Result

Not started.

## Blockers

None.
