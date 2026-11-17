## Story ID

STORY-WEB-012

## Title

Build the Crafting Discovery browser workflow over existing APIs

## Status

DONE

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

DONE, **frontend only** — nothing under `src/`, `pom.xml` or the backend changed, and no backend test
was run or needed.

**An interrupted attempt was resumed, not restarted.** The previous session had committed the whole
production implementation and the Discovery test fixtures in `04d7555` and stopped before writing any
test. On disk already: `CraftingDiscoveryScreen.vue`, `DiscoveryScopeSelector.vue`,
`DiscoverySettingsForm.vue`, `DiscoveryTable.vue`, `SelectedDiscoveryDetail.vue`,
`useCraftingDiscovery.ts`, `useDiscoveryTableView.ts`, `useDiscoveryResolution.ts`,
`discoveryScopeOptions.ts`, the shared `useResolutionDetail.ts` extraction, the `discovery` navigation
destination and the `DISCOVERY_SETTINGS`/`discoveryRows`/`echoedDiscoveryResponse`/
`discoveryResolutionResponse` fixtures with the `FakeCraftingApi` Discovery handlers. That code was
verified rather than rewritten; this session added the tests, the browser evidence and the
documentation, and fixed the three defects the new tests exposed.

**Three defects found and fixed in that existing code.**

1. *A reload silently rewrote the inventory character.* `reconcileInventoryCharacter` re-derived the
   value from the currently selected scope on every selector read whenever the user had not explicitly
   picked one, while a scope change deliberately does not reconcile. Selecting Chef/Sat Anat and then
   pressing Reload therefore submitted `inventoryCharacterName: "Sat Anat"` where the result on screen
   had been calculated with `"Nbt Anch"` — a changed calculation input from an action that must
   preserve them (criterion 5). It now re-derives only when no value is in effect or the selector no
   longer offers the one that is; an explicit "No character" still survives a reload, which is why
   `inventoryChosen` is kept.
2. *The page claimed "no character" before it knew.* The results region tested
   `!canCalculate && selectorError === null` before `isLoading`, and `isLoading` stayed false during
   the selector read, so first paint asserted "No character with a crafting discipline is available …
   Synchronize the account" while that read was still in flight. `open()`/`reload()` now enter the
   loading state before reading the selector and both the region and the detail placeholder order
   loading first. Found because the live check read the page immediately after `domcontentloaded`.
3. *A live-check failure could not be seen.* `discovery-live-smoke.mjs` created `waitForResponse`
   promises it does not always await (a reload may drop the selected recipe); when the browser closed,
   that rejection became an uncaught exception and replaced the real assertion message. Every wait is
   now marked handled and the run reports its own error.

**What the page does** is recorded in `CURRENT_ARCHITECTURE.md` §5.11 ("Crafting Discovery page"),
which this story updated along with the file layout, the five-destination shell, the two new commands,
the corrected "Not built yet" paragraph and §5.13's stale "no browser consumer" claim. In summary:
`#/discovery` is a first-class addressable destination on its own `KeepAlive` boundary; scope choices
are individual character disciplines built from §5.10 with the supplied rating and no All entry; the
separate `inventoryCharacterName` control keeps "No character" as a real omission; no character or a
failed selector read sends no calculation; settings state comes from Discovery's own echo and never
Profit's; the fixed daily value is reported, not sent, and `allowNonTradeableMaterials` is unreachable;
six columns each state their per-craft/total basis; level sorting works both ways and opens highest
first; **there is no profit filter**, so negative and zero-profit candidates stay listed; §25's
gross/after-15%-fees note is carried; and selection lazily posts one resolution request carrying the
table's echoed scope, nullable inventory character and settings, checked back against all three.

**Acceptance criterion 8 (icons) is met, not deferred.** WEB-010 is DONE, so rows, the detail heading
and both material lists consume `ItemIcon` with the backend's `iconUrl` verbatim; there is no competing
image mechanism and no GW2 request from the browser. No outstanding icon integration to record.

**Tests written and run** (exact commands and real output):

- `npx vitest run src/crafting/__tests__/CraftingDiscoveryScreen.spec.ts` — **33 passed**. New file:
  scope/rating/inventory request bodies, no request without a character, selector failure and retry,
  loading-before-no-character, Discovery's echoed defaults against Profit's, only the five accepted
  settings, losses and break-even kept with no profit filter offered, supplied economics with
  non-derivable totals (2222 ≠ 5 × 380, 1650 ≠ 4 × 450), nulls not zeroed, the fee note, both material
  lists, the echoed budget, level/sell-value/profit sorting in both directions, search over name,
  level and state words, whole-row and keyboard selection, reload preservation, selection clearing,
  superseded answers, the grouping pattern and the shared icon component.
- `npx vitest run src/crafting/__tests__/discoveryResolutionAssociation.spec.ts` — **23 passed**. New
  file: lazy detail, the exact request body including the nullable inventory character, changed
  scope/inventory/settings, sorting and searching sending nothing, reload invalidation, superseded
  answers, A → B → A, mismatched recipe/inventory/pool/settings echoes, empty-after-populated, 404,
  `RESULT_UNAVAILABLE`, transport failure, leave/return under `KeepAlive`, and the fresh row and tree
  shown beside the table row with truthful basis and root-sourcing labels. Node-level presentation is
  deliberately not duplicated — `CraftingResolution.spec.ts` owns it and is unchanged.
- `npx vitest run src/crafting src/__tests__` — **12 files, 219 passed**. `App.spec.ts` needed two
  changes: its exact navigation-link list asserted the four destinations from before this story and
  now includes `#/discovery` (the `tasks/lessons.md` "a check that asserts an absence expires the day
  the feature arrives" pattern — it was the one baseline failure at the start of this session), and it
  gained two tests driving the real HTTP client through a body-aware `fetch` stub: Discovery as its own
  addressable destination with its own title and focused heading, and the two crafting screens staying
  independent with no second calculation on return and only the detail re-requested.
- `npx vitest run` — **19 files, 289 passed** (whole frontend suite, 16s). Run once at the end as a
  closing check; GitHub Actions remains the authoritative full-regression gate.
- `npm run type-check` (`vue-tsc --noEmit`) — clean, exit 0. `npm run build` — type-check plus
  `vite build`, **✓ built in 642ms**, 97 modules.
- `npm run smoke:discovery` (new `scripts/discovery-browser-smoke.mjs`, controlled stub origin, real
  Chrome) — **PASSED, 13 steps**: served-by-this-script identity assertion, the addressable
  destination, list and detail side by side at 1440×900 (list 968px, detail 416px, no page overflow),
  the Calculation/Displayed-results grouping with no profit filter, individual character disciplines
  with supplied ratings, five candidates including a loss and an exact zero, Enter-to-select with
  visible retained focus, selection marked for sight and assistive technology, the fresh row
  (+42g 42s 42c) kept apart from the table row (+14g 81s 40c), requirements in supplied order with the
  root's own inclusive cost and a null cost left as `—`, keyboard sort reversal and search issuing no
  request while keeping the valid detail, the detail below the list at 360×800 with the table region
  focusable, and a final audit that only Discovery's own routes were called, no Profit-only setting was
  ever sent, and nothing left the origin.
- `npm run smoke:discovery:live` (new `scripts/discovery-live-smoke.mjs`) against the **real backend
  already running on 8080 with a populated database**, through a dev server this session started on an
  isolated port 5193 — **PASSED**. Armorsmith/Nbt Anch rating 500, `inventoryCharacterName: "Nbt Anch"`,
  `rowCount 672` and **672 rendered rows matching the response field for field** (name, recipe id,
  level, craftable, buy cost, sell value, signed profit), of which **637 had non-positive profit and
  were all still listed** — direct evidence that Profit's filter was not imported as eligibility.
  Recipe 291 resolved to a real 4-node tree, `SINGLE_OUTPUT_REQUIREMENT`/`FRESH_CALCULATION`, node
  labels matching the backend's own depth-first order, and a reload re-sent the same scope and
  inventory character and re-requested the detail. Read-only throughout: no synchronization control was
  touched and the check fails rather than synchronizing when the selector offers no character. The dev
  server was then stopped and **port release was confirmed by a failed `curl`**, killing the `vite`
  child by command line after `TaskStop` left it listening (`tasks/lessons.md`); the pre-existing
  backend on 8080 was left running.

**Verification limits, stated honestly.** The eligibility rules themselves — the rating filter, the
account-wide recipe-knowledge rule and normal-discovery eligibility — are backend-owned and were
**not** re-verified here; `smoke:discovery`'s special states (blocked, no-result, null-versus-zero,
mismatched echoes, 404, `RESULT_UNAVAILABLE`) are **controlled-response evidence only**, and the live
run confirms rendering fidelity against whatever the real backend returned, never that a value is
domain-correct. No `TARGET_ARCHITECTURE.md` §33 timing is claimed for this page: Discovery is untimed,
has no display limit and no virtualization, so 672 live candidates put 672 rows in the DOM — recorded
in §5.11 rather than measured. Discovery's DOM-023 consumption is by construction (it displays the
shared row's supplied `profitCopper`/`totalProfitCopper` and recreates no fee); DOM-023 is still TODO,
so when it lands, only those supplied numbers change and this page needs no edit.

## Blockers

None.
