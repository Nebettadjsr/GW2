## Story ID

STORY-WEB-012

## Title

Build the Crafting Discovery browser workflow over existing APIs

## Status

UNFINISHED

## Milestone

milestone-05

## Goal

Provide the dedicated Discovery page, comparison list and selected-recipe resolution workflow defined in DOMAIN_SPEC section 2.2.2, consuming existing backend results.

## Authoritative Source Documents / Sections

- Supplied docs/ROADMAP.md Phase 5 Discovery, resolution, frontend tests and shared-backend criteria.
- docs/DOMAIN_SPEC.md sections 2.2-2.2.2, 11.1, 34-38; resolved UD-001 and UD-004.
- docs/TARGET_ARCHITECTURE.md sections 4.1, 12-14; docs/FRONTEND_UX_GUIDELINES.md application structure, comparison/detail, accessibility and shared presentation rules.
- docs/CURRENT_ARCHITECTURE.md sections 5.6, 5.10, 5.11 and 5.13.
- agent/stories/STORY-API-006-crafting-selector-options.md, STORY-API-008-crafting-resolution-endpoints.md and STORY-WEB-007-profit-resolution-detail-view.md.

## Context

The table and fresh-detail Discovery APIs already exist; the supplied completed-story evidence explicitly records no browser Discovery consumer. Existing shell, money formatting, selection and semantic-tree components provide reusable presentation. API-009 and WEB-010 separately own shared icons. This story builds one integrated consumer without a new eligibility or economic engine.

## Acceptance Criteria

1. Add a first-class, addressable Discovery destination to main navigation with page title, grouped controls, comparison list and dedicated responsive detail area. Follow the shared UX rules and apply the corrected Profit grouping pattern when available.
2. Build individual character/discipline choices from selector API facts, carrying the supplied current rating; never offer All or invent a rating. Preserve the separate inventoryCharacterName API input and established individual-character initial selection behavior; label its inventory role if exposed separately. Submit scope and inventory inputs explicitly for the intended selection, without changing the backend's null fallback or Profit scope behavior. Show a clear no-character/selector-failure state without sending an invalid calculation.
3. Consume POST /api/crafting/discovery with its own defaults and effective-input echo. Expose applicable owned-material, buying, budget and price-mode controls. Do not send the Profit-only non-TP setting or the fixed daily setting. Let the backend enforce rating, account-wide knowledge and normal-discovery eligibility; never recreate those rules or silently remove negative-profit candidates.
4. Show supplied recipe name, recipe level, missing-material cost, output Trading Post sell value and profit per craft with explicit per-craft/total bases. Provide level sorting including descending, output sell-value sorting, profit sorting and text search to narrow the list. Display supplied economics unchanged, including nulls, blocked results and signed values; no composite XP/profit score or derived shopping total. Apply DOMAIN_SPEC section 25: displayed prices/output value remain gross and profit detail carries a concise after-15%-fees note. Consume DOM-023's corrected shared results without recreating fees.
5. Select by recipe ID using whole-row and keyboard interaction. Preserve valid selection, scope, search and sort on refresh/navigation; clear selection if the current result set no longer contains it. Handle loading, empty success, failure and retry distinctly; ignore superseded table answers.
6. Lazily call POST /api/crafting/discovery/resolution for selection using the table's echoed effective calculation, including its nullable inventory character. Apply section 13.4 generation and identity checks on selection, reload, input changes and leaving the page, including A-to-B-to-A. Sorting/search alone sends no calculation. Display the returned fresh row and tree together without replacing the table row, with truthful single-output-requirement and actual-root-sourcing labels.
7. Reuse semantic-tree/detail presentation for supplied recipe information, ordered material requirements and inventory/crafted/bought/missing contributions, both supplied material lists with their bases, inclusive costs and contextual reasons. Keep unavailable/zero, unknown codes, RESULT_UNAVAILABLE, 404 and request failure distinct; clear stale trees and preserve complete accessible content. Never reconstruct absent quantities or recipes.
8. Consume shared icons through WEB-010's component when available, using only backend iconUrl and stable fallback otherwise. Do not implement a competing image mechanism or query GW2 from the browser. Record outstanding icon integration explicitly if WEB-010 is unfinished; its infrastructure work must not block the independent Discovery workflow.

## Required Tests

- API-boundary/component tests for exact scope/rating/inventory inputs, echoed Discovery defaults, no invalid request without a character, no Profit-only settings, empty/failure/retry and superseded answers.
- Rendering/interaction tests using deliberately non-derivable supplied totals, nulls, losses, all required sort keys/search, refresh preservation and keyboard recipe selection. Verify candidates are consumed from the backend, not reclassified in Vue.
- Discovery-specific fresh-detail association tests including changed inventory character, A-to-B-to-A, leave/return, empty-after-populated, mismatched echo, 404, unavailable and transport failure; reuse existing semantic-tree tests where unchanged.
- Real-browser controlled-response wide/narrow and keyboard checks, plus read-only real-backend comparison of displayed rows and a selected tree against the actual responses. Record which eligibility/special-state cases are controlled evidence only. No implicit live synchronization.
- Affected frontend suites, type checking and build; targeted backend tests only if an actual adapter change is necessary.

## Constraints

- Backend/application/domain authority remains unchanged. No JavaFX migration through HTTP, backend eligibility redesign, new task transport or economic calculation in Vue.
- Reuse existing components where responsibilities match, preserving Profit behavior. Do not import Profit's nonpositive-profit default filter as Discovery eligibility.
- No performance or milestone completion claim from a partial table or stubbed browser run.

## Dependencies

Completed Discovery table API (STORY-API-002), STORY-API-006, STORY-API-008 and STORY-WEB-007. WEB-011 is queued first for shared presentation consistency; Discovery can proceed using authoritative grouping requirements independently. Shared icon integration uses WEB-010 when available.

## Definition of Done

Discovery is reachable and usable with backend-authoritative results and fresh details, required controls and browser evidence. CURRENT_ARCHITECTURE and this Result describe delivered behavior and any shared-icon integration dependency honestly.

## Result

Not started.

## Blockers

None.
