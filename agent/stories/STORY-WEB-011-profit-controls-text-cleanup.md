## Story ID

STORY-WEB-011

## Title

Complete Crafting Profit control grouping and concise presentation

## Status

DONE

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

Presentation-only, and **frontend-only**: every change is under `frontend/src/` and
`frontend/scripts/`, plus `docs/CURRENT_ARCHITECTURE.md`. No file under `src/`, no `pom.xml`, no
resource and no HTTP contract was touched, so no domain rule, backend behavior or JavaFX screen
changed and the Java suite was deliberately not re-run.

### 1. Grouping (AC 1)

The "Calculation controls" panel now holds two fieldsets instead of one open cluster plus a
disclosure:

- **Calculation** (`data-test="calculation-controls"`) — the Discipline scope selector and the
  "Price and material settings" disclosure, whose summary still words the backend's echoed settings.
- **Displayed results** (`ResultDisplayControls.vue`, legend renamed from "Result display") — the
  search, the three filters, the maximum and Show all.

The separate top-level section inside *Opportunities* is gone; the fieldset's own legend and border
carry the separation that its explanatory paragraph used to assert. The **search moved with them**:
it narrows the rows already returned exactly as the filters do, so leaving it in the Calculation
subgroup would have mislabelled it. It is passed in through a `#search` slot, so its state and its
handler stay on the screen component.

Because the controls are now part of the panel they render whenever the panel does — including
while a calculation is in flight and after a failed one — instead of only alongside a settled
result.

### 2. Defaults and reversibility (AC 2)

`useProfitTableView` opens `hideZeroCraftable`, `hideNotAllowed` and `hideNonPositiveProfit` all
`true` (previously only the first). Each is an independent `ref`, so each reverses on its own; the
matching logic was **not** touched, so the null/unavailable distinctions it already held are intact —
a null count is not `0`, a null profit is not "0 or less", and `resultAvailable: false` is not a
recipe reported as not allowed. `INITIAL_MAX_DISPLAYED` is still 250 and Show all still reveals the
full *matching* set. No code path from any of these reaches `craftingApi`; the browser check
re-verifies that the calculation and detail request counts are unchanged across every control.

### 3. Removed text (AC 3)

Gone: the display group's note ("These change which of the calculated recipes are listed below…"),
the screen's `table-note` paragraph (row-selection/keyboard instructions plus the per-craft/total and
table-scrolling explanations), the `limit-note` paragraph, the "Show all is on, so the maximum above
is not applied" paragraph and the detail's "Its details stay available until…" sentence. Filter
labels were shortened to *Hide craftable count 0*, *Hide recipes not allowed*, *Hide profit per craft
≤ 0*, and the maximum to *Show at most N* / *Show all*.

Kept: the compact "Showing *n* of *m* matching recipes · *k* calculated for this scope" count, which
is now the only thing that reports a cut-off list; the refused-maximum warning (an actionable error
state); the empty-list notice naming every restriction in force with the controls still on screen;
the hidden-selection notice naming which control is holding the selected row back; and every
accessible semantic — the table's visually hidden `<caption>` still states the per-craft/total bases
and how a row is opened, and the column headings still carry the basis visually. Instead of the
removed "Show all is on" prose, Show all **disables** the maximum input while keeping its value.

### 4. Detail wording (AC 4)

The quote heading is now the concise **"Trading Post price / item"** (was "Trading Post price for one
*Item Name*"), its rows are "Instant buy"/"Instant sell" rather than repeating "one item", and the
"Each is the price of a single item. One craft of this recipe produces N." paragraph is gone —
`Output quantity` stays in the *For one craft* group, which is where the recipe/crafting information
is. The "no shopping total is worked out here" note under *Materials still to buy* went too; both
lists keep their own basis headings. Untouched: the per-craft and totals groups and all their bases,
the state sentence and its wording for every ordinary blocked reason, the budget context line, the
`CYCLE_DETECTED` row diagnostic, the special states, the technical-details disclosure and the fresh
resolution region with its basis/consistency values.

### 5. Preserved behavior (AC 5)

Selection is still by `recipeId`, whole-row click and the keyboard-operable row button; focus,
`aria-current`, the marker glyph and the hidden "Selected" label are unchanged, as is control state
across reload, scope change and leaving/returning to the screen. The only shared file touched is
`scripts/layout-browser-smoke.mjs`, where the crafting page's contrast sampling now switches the
three filters off so the loss and zero-count treatments are still rendered to be measured; Bank,
Materials and Synchronization are untouched and their checks still pass.

### 6. Tests run

- `npx vitest run src/crafting` — **8 files, 137 tests passed**. Updated: the defaults case in
  `useProfitTableView.spec.ts` (all three on, plus independent reversal), and in
  `CraftingProfitScreen.spec.ts` the placement/removed-prose case, the reversible-filter case, the
  counts case, a new Show-all/maximum-suspension case and the helpers that need every row listed.
  `SelectedResultDetail.spec.ts` now asserts the concise label and the absence of the removed prose.
- `npx vitest run src/__tests__/App.spec.ts` — **11 tests passed** (it drives the Profit screen's
  search and rows through the shell).
- `npm run type-check` (`vue-tsc --noEmit`) — clean; `npm run build` — built.
- `npm run smoke:profit` — **PASSED, 26 steps**, real Chrome at 1440×900, 1440×420 and 360×800. It
  now asserts the controls are inside the panel and not in the results region, both legends, that the
  search is in the group, all three filters enabled at 250, that the three removed paragraphs are
  absent while the table caption is not, independent reversal of each filter, the concise quote label
  with the output quantity still present, Show all suspending but keeping the maximum, Tab reaching
  the filters with visible focus, and zero calculation/detail requests from any of it.
- `npm run smoke:layout` — **PASSED, 11 steps**, 20 contrast samples, all four areas reflowing.
- No backend suite: nothing outside the browser client changed.

### 7. Judgement calls and remaining uncertainty

- Moving the search into the Displayed results subgroup is not spelled out in §2.1.1, which names
  only the four display controls. It follows from the subgroup being "clearly distinct from
  calculation settings" — the search is not one — and the screen already counted it among the
  restrictions it names when the list empties.
- "Keep output quantity with recipe/crafting information" is satisfied by leaving `Output quantity`
  in the *For one craft* group rather than duplicating it onto the identity line.
- The browser evidence is structural, from a stub origin; it establishes grouping, wording,
  reflow and interaction, never real data or performance.

## Blockers

None.
