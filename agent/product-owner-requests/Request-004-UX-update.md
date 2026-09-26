# Product Owner Request

## Status

RESOLVED

## Title

Improve Crafting Profit web UI usability, information layout, filtering, details, and item presentation

## Requested Change

Bring the Crafting Profit web view closer to the usability and information quality of the existing JavaFX application while following the new frontend UX/UI guidelines.

This is not a request to visually copy JavaFX. The web version should improve on it where appropriate, but must preserve the useful information hierarchy and interactions that existed there.

The current Crafting Profit page should be reworked as follows.

### 1. Favicon

Add favicon support to the web frontend.

The implementation should establish the expected favicon file path and filename in the frontend project and document exactly where the Product Owner should place the final icon file.

Use a sensible standard browser-compatible approach rather than embedding the final icon directly into source code.

---

### 2. Crafting Profit result columns

The main Crafting Profit result list should expose the most useful economic values for quickly comparing recipes.

Ensure the result table includes at least:

- recipe/item;
- craftable count;
- own materials value;
- profit per craft;
- total sell value;
- total profit;
- any other essential value already justified by the product/domain rules.

`Total sell value` means the applicable Trading Post sell value multiplied by the craftable quantity.

Do not add duplicate calculations in the frontend when the value belongs to backend/domain logic. Reuse or extend the backend API contract where necessary.

Avoid filling the main table with secondary or diagnostic values that are better placed in the detail view.

---

### 3. Entire recipe row selectable

The complete table row for a recipe should be clickable/selectable.

Selecting a row should populate the details area on the right.

The user should not have to click a small dedicated action control to inspect a result.

The currently selected row should be visually identifiable.

---

### 4. Selected-result detail panel

On sufficiently wide screens, selected-result details should remain in a dedicated panel to the right of the main result list.

The detail panel should include, where applicable:

- buy cost;
- Trading Post price for one output item;
- crafting/resolution tree;
- relevant selected-recipe information;
- shopping/material information where useful;
- a link to the relevant Guild Wars 2 Wiki page where a reliable URL can be constructed.

#### Buy cost

If a buy cost exists, show it prominently enough to be understood and use the established negative/cost visual treatment, including red where appropriate.

Color must not be the only indicator of meaning.

#### Remove unnecessary result summary

Remove the current `Result summary` section and its explanatory text if it merely repeats information already clearly presented elsewhere.

The details panel should prioritize useful selected-recipe information rather than generic explanatory text.

#### Trading Post unit price

Make the Trading Post price for one output item more visually obvious and clearly label what the value represents.

#### Crafting tree

The crafting tree must represent the actual resolution required for the selected result.

It should make clear:

- which steps are required;
- whether each step is crafted, bought, taken from inventory, blocked, etc.;
- the quantity required at each step;
- the output quantity produced by each crafting step where relevant.

The tree should explain the actual chosen resolution rather than merely showing recipe relationships.

#### Sticky detail panel

On desktop/wide layouts, the selected-result detail panel should remain visible while scrolling through the recipe list.

Use a sticky layout so the details do not disappear simply because the user scrolls farther down the table.

Responsive layouts may use another suitable presentation on smaller screens.

---

### 5. Calculation/result filters

Add user-facing filters for the Crafting Profit result list.

Required filters:

- checkbox, enabled by default:
  `Hide items with craftable count 0`

- checkbox:
  `Hide recipes not allowed`

- checkbox:
  `Hide recipes with profit per craft <= 0`

- maximum number of displayed recipes:
  default `250`

The user must be able to change the maximum result count.

Also provide a convenient `Show all` option which removes that display limit and shows the full matching result set.

These are result-display filters unless existing domain rules establish otherwise. They must not silently alter the underlying crafting calculation.

The UI should clearly distinguish filtering results from changing calculation rules.

---

### 6. Clarify the current Status column

Investigate and document the intended user-facing purpose of the current `Status` column and its labels, including values such as `BUYING_DISABLED`.

The Product Owner currently cannot determine from the UI what this column is intended to communicate.

Before preserving it in its current form:

- determine what each status represents;
- determine whether the information is actually useful in the main table;
- distinguish domain/user states from technical/internal states;
- replace technical terminology with user-facing wording where appropriate;
- move secondary information into the selected-result details if it does not need to be visible on every row.

The planner/implementation result should explain the intended meaning of the status values so the Product Owner can review whether the presentation is useful.

Do not remove meaningful blocked/unavailable information merely because the current wording is unclear.

---

### 7. Item and recipe icons throughout the application

Request architectural evaluation of realistic approaches for obtaining and presenting Guild Wars 2 item/recipe icons throughout the web application.

Icons are expected to be useful in areas including:

- Crafting Profit;
- Bank;
- Materials;
- recipe/detail views;
- future item-oriented views.

The Architect should evaluate realistic options and recommend an approach.

The evaluation should consider at least:

- the icon information already available through Guild Wars 2 data/API responses;
- using external ArenaNet/CDN icon URLs directly;
- backend-managed/local caching;
- browser caching;
- whether existing icon-sync/cache functionality can be reused;
- performance and network behavior;
- avoiding unnecessary duplicate storage;
- behavior when an icon is unavailable;
- deployment/container implications.

The goal is to establish one reusable application-wide icon strategy rather than every frontend view solving icon loading independently.

If this can be decided technically under Architect authority, the Architect should make the decision rather than escalate merely because several approaches are possible.

The planner should create an Architect Request for this question if no authoritative icon strategy already exists.

---

### 8. Preserve useful visual scanning

The existing JavaFX application uses visual distinctions to make economic results easy to scan quickly.

The web frontend should retain this benefit in a modern web-appropriate form.

In particular:

- positive/profitable values should be visually easy to identify;
- costs/negative values should be visually distinguishable;
- important totals should have appropriate emphasis;
- colors should be consistent across the application;
- color must not be the only carrier of meaning.

The goal is that a user can visually scan many crafting results and quickly identify relevant opportunities without reading every cell.

## Planner Resolution

2026-09-25: RESOLVED as planning coverage, not implementation completion.
Reused the authoritative requirements in docs/DOMAIN_SPEC.md section 2.1.1
(economic content, filters, selection, detail and state/visual meaning) and
docs/TARGET_ARCHITECTURE.md section 12.1 (favicon and resolved AR-004 icon
strategy); no duplicate requirements document or architecture decision created.

Request coverage:
- Item 1: agent/stories/STORY-WEB-009-replaceable-favicon.md establishes the
  replaceable asset and exact PO instructions.
- Item 2: agent/stories/STORY-WEB-008-profit-economic-columns.md supplies
  backend-owned total sell value and the required comparison columns with
  explicit per-craft/total bases.
- Items 3 and 5: reused
  agent/stories/STORY-WEB-006-profit-result-display-controls.md for whole-row
  selection, the required filters, adjustable limit and Show all.
- Items 4, 6 and 8: reused
  agent/stories/STORY-WEB-005-crafting-profit-information-hierarchy.md and
  agent/stories/STORY-WEB-007-profit-resolution-detail-view.md for useful detail,
  sticky layout, fresh authoritative resolution, state explanations and accessible
  economic scanning; WEB-008 completes the main-table economic presentation.
  The detail prerequisite remains
  agent/stories/STORY-API-008-crafting-resolution-endpoints.md, following completed
  agent/stories/STORY-APP-012-request-local-resolution-detail.md.
  TARGET_ARCHITECTURE section 13 owns the fresh single-output-requirement basis;
  it is not an exact trace of the earlier table result or all counted crafts.
- Item 7: consumed the resolved AR-004 decision at docs/TARGET_ARCHITECTURE.md
  section 12.1. Created
  agent/stories/STORY-API-009-web-item-icon-metadata.md for metadata sync,
  batching, URL/null contracts and JavaFX coexistence, and
  agent/stories/STORY-WEB-010-shared-item-icon-presentation.md for shared
  rendering, fallbacks, browser/CDN evidence and real-data performance
  reverification. Subsequent item views reuse that component and contract.

Added the four new milestone-05 stories to agent/stories/BACKLOG.md To Do in
dependency order after the existing batch. No existing story was duplicated.
Architecture-to-roadmap consistency checked: section 12.1's decided implementation
fits the supplied Phase 5 objective and existing performance/coexistence criteria.
The new stories provide executable coverage; no changed exit criterion, new
recurring obligation or roadmap sequencing change is needed. Phase 5 remains
incomplete: existing/new stories still need execution, Discovery and Ecto browser
work remains for a subsequent small batch, and the final full-page performance
assessment and bounded health review remain required. Request resolution records
complete planning coverage only, not delivered functionality or milestone closure.