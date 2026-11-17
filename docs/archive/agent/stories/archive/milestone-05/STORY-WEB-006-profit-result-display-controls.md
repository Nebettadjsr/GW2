## Story ID

STORY-WEB-006

## Title

Add Crafting Profit display filters and whole-row selection

## Status

DONE

## Milestone

milestone-05

## Goal

Make the supplied Crafting Profit results easier to browse using display-only
filters, an adjustable result limit and whole-row selection, preserving backend
authority and accessible interaction.

## Authoritative Source Documents / Sections

- Supplied docs/ROADMAP.md Phase 5: crafting profit screen and frontend interaction tests.
- docs/DOMAIN_SPEC.md sections 2.1.1 and 2.2.1: required controls and refresh preservation.
- docs/TARGET_ARCHITECTURE.md section 12: presentation-only frontend responsibility.
- docs/FRONTEND_UX_GUIDELINES.md section 4 and applicable interaction/accessibility guidance, referenced by TARGET_ARCHITECTURE section 12.
- agent/stories/STORY-WEB-005-crafting-profit-information-hierarchy.md, Result: existing comparison table, recipe identity selection and state presentation.

## Context

WEB-005 supplies six comparison columns and an accessible recipe-name selection
button. Request-004 requires whole-row interaction and a bounded, filterable view.
This slice uses existing supplied values; new economic fields and resolution HTTP
consumption are separate work.

## Acceptance Criteria

1. Provide the three checkboxes and editable maximum/Show all behavior specified
   by DOMAIN_SPEC section 2.1.1. Initially enable only the required zero-count
   filter; the other optional filters start unchecked. Initially limit to 250
   matching recipes. Apply search and filters before the existing sort and display
   limit so Show all reveals the full matching set, not an unrelated unfiltered set.
2. Evaluate filters against supplied craftable count, blocked reason and per-craft
   profit only. Missing/unavailable values are not coerced to zero, and a technical
   failure is not treated as RECIPE_NOT_ALLOWED. Combined filters and zero matches
   remain understandable; communicate displayed versus matching result counts.
3. Changing display controls never submits a calculation or synchronization,
   changes its inputs, or changes the backend's per-recipe simulation cap. Clearly
   distinguish these controls from calculation settings. Preserve valid controls
   through reload and the existing within-session navigation behavior.
4. Clicking any non-interactive area of a recipe row selects its recipe and shows
   the existing details. Retain a keyboard equivalent, visible focus and a non-color
   selection cue. Links and other nested controls retain their own behavior and do
   not cause duplicate selection actions. Use recipe identity, never row index.
5. Sorting/filtering/limiting must not associate detail with a different recipe.
   If a selected recipe is hidden by display controls, keep its identity/detail
   explicitly identified as outside the displayed list, or clear selection; record
   the chosen presentation behavior. A replacement result set that lacks that
   recipe still clears it, as WEB-005 requires.
6. Record control behavior and actual checks in Result and update the relevant
   CURRENT_ARCHITECTURE frontend description. Do not claim the Phase 5 full-page
   performance criterion based on limiting displayed rows.

## Required Tests

- Frontend interaction tests for defaults, each filter, combinations, boundary
  values 0/negative/positive/null, RECIPE_NOT_ALLOWED versus other states, adjustable
  limit and Show all after search/sort/filtering; assert no API calculation call.
- Selection tests for row background clicks, keyboard operation, nested controls,
  selection through reorder/filter changes and removal on replacement results;
  preserve existing late-response isolation tests.
- Targeted real-browser checks on wide and narrow layouts for usable controls,
  keyboard focus, whole-row selection and clear empty/limited states, using
  controlled data without triggering live synchronization.
- Run affected frontend tests, type checking and build; record results and limits.

## Constraints

- No new domain calculations, backend fields, icon delivery or economic formulas.
- Reuse existing shared presentation and state wording. No broad redesign.
- Filtering may hide a row by explicit user controls; do not change the underlying
  result set or reinterpret blocked/unavailable results as successful or absent.

## Dependencies

STORY-WEB-005 (DONE).

## Definition of Done

The required result controls and accessible whole-row selection work over supplied
results with focused interaction/browser evidence, preserved calculation behavior
and updated documentation. No milestone completion is asserted.

## Result

Crafting Profit now carries DOMAIN_SPEC §2.1.1's result-display controls — three filters, a
changeable maximum and Show all — in their own group inside the results region, and a recipe row is
selectable anywhere on its surface. **No file under `src/`, no `pom.xml` and no resource was
touched**, so no domain rule, HTTP contract or JavaFX behavior changed and the Java suite was
deliberately not re-run; every change is under `frontend/src/` and `frontend/scripts/`.

### 1. What the controls are and where they live (AC 1, AC 3)

New `ResultDisplayControls.vue` is a `<fieldset>` legended **Result display**, rendered inside the
*Opportunities* region — not in the "Calculation controls" panel, which is the visible separation
§2.1.1 asks for — with a note in the group itself: *"These change which of the calculated recipes are
listed below. They never recalculate anything, never change the calculation controls above, and never
change how far the backend simulated each recipe."*

- **Hide recipes with a craftable count of 0** — checked initially, the only one that is.
- **Hide recipes reported as not allowed** — unchecked initially.
- **Hide recipes with a profit per craft of 0 or less** — unchecked initially.
- **Show at most N recipes**, initially **250**, plus **Show all matching recipes**.

They are plain refs in `useProfitTableView`, so no code path from any of them reaches
`craftingApi.calculateProfit`. The calculation is still requested for exactly four reasons: opening
the screen, a changed scope, changed settings and an explicit reload. Nothing in a request body
changed, so §28's per-recipe simulation cap is untouched — the controls never had a way to reach it.

### 2. Order of operations (AC 1)

`matchingRows` = search → three filters → sort. `visibleRows` = `matchingRows` cut off at the
maximum, or all of it when Show all is on. Because the cut-off is last, **Show all reveals the rest of
that matching set**, not an unfiltered one — asserted in the unit suite and in the browser (a search,
a filter and a re-sort in force, 1 row → 4, and the two zero-count rows still absent).

### 3. What each filter reads, and what it refuses to assume (AC 2)

Each test reads one supplied field, and "not supplied" is its own answer:

| Filter | Hides | Deliberately keeps |
|---|---|---|
| zero count | `craftableCount === 0` | `null` count; anything not exactly `0` |
| not allowed | `resultAvailable && blockedReason === 'RECIPE_NOT_ALLOWED'` | every other blocked reason; **every row with no calculated result** |
| profit ≤ 0 | `profitCopper !== null && profitCopper <= 0` | `null` profit |

The not-allowed test reads `resultAvailable` as well as the code precisely so a **technical failure is
never presented as "the recipe is not allowed"**, even for a row that contradicts the contract by
carrying both; there is a fixture for exactly that.

Counts are communicated as three separate numbers — *"Showing 3 of 3 matching recipes · 4 calculated
for this scope"* — with a cut-off list additionally saying how many further matching recipes the
maximum is holding back. A list the controls empty says the calculation's rows are **all still held**
and names every restriction in force ("Currently applied: search “…” · hiding recipes reported as not
allowed · hiding a profit per craft of 0 or less"), with the controls still on screen to undo.

### 4. Whole-row selection (AC 4)

`<tr>` gained a click handler that emits `select(row.recipeId)` — recipe identity, never an index —
after `event.target.closest('a, button, input, select, textarea, label, summary, [role="button"],
[role="link"]')` returns null. A click that started inside the recipe button is therefore that
button's click alone: **one click, one selection action**, asserted directly on the child component's
emitted events. The row is deliberately *not* a control: the button stays the single tab stop, the
accessible name and the Enter/Space route, and the selection cue is unchanged (`aria-current`, a
raised surface, a highlight border, a "▸" glyph and a visually hidden "Selected" — never color alone).
Rows get `cursor: pointer` and a hover surface so the pointer affordance is visible.

### 5. A hidden selection (AC 5) — chosen presentation behavior

**The selection is kept and explicitly identified as outside the displayed list.** The composable
returns `selectionHiddenReason`: `'limited'` when the recipe is in `matchingRows` but past the
maximum, `'filtered'` when the search or a filter removed it, `null` otherwise. The detail region
words each case ("…it is further down the matching results than the display maximum reaches. Switch on
Show all to list it." / "…the current search or display filters hide its row.") and keeps showing that
recipe's own values, so sorting, filtering or limiting can never pair a detail with a different
recipe. Only a **replacement result set that no longer contains the recipe** clears it — WEB-005's
watcher is unchanged and is re-asserted here.

### 6. Judgement calls

- **The maximum is committed on `change`, not per keystroke.** Applying every keystroke would make
  clearing the box to retype briefly mean "show 0" and flash a refusal message.
- **An unusable maximum is refused, not clamped.** Anything that is not a whole number ≥ 1 leaves the
  effective maximum alone, puts it back in the control and says so in a warning notice. Clamping
  silently would answer a question the user did not ask.
- **Show all does not erase the typed maximum.** Switching it off returns to the number that was
  typed; while it is on, a note says the maximum is not being applied.
- **The three counts are always stated in the same shape**, even when all three are equal. Uniform
  wording is one thing to read and one thing to test; a conditional phrasing is neither.

### 7. Verification

- `npm test` **150/150** in 13 files (was 132), controlled responses only.
  `CraftingProfitScreen.spec.ts` **35** (was 25) adds: the specified initial control state and the
  controls' position inside the results region; each filter applied with `api.profitRequests`
  asserted to still be `[{}]`; all three combined leaving the null-profit blocked row listed;
  displayed/matching/calculated counts and the cut-off note; the maximum applied **after** search,
  filters and sort with Show all revealing the matching set and keeping the typed maximum; `0`
  refused with the effective maximum restored; an emptied list naming every restriction and **not**
  naming a filter that is off; a click on a **value cell** selecting that row; the nested button
  emitting `select` **exactly once**; a selected recipe hidden first by the maximum then by a filter
  keeping its detail, and a replacement result set without it still clearing it; and every display
  control surviving an explicit reload while the reload's request body stays scope+settings only.
  `useProfitTableView.spec.ts` **16** (was 8) adds the defaults, zero-versus-null-versus-negative
  counts, not-allowed versus other states versus a technical failure, zero/negative/positive/null
  profit, the three filters combined with the search down to zero matches, the cut-off applied last,
  `setMaxDisplayed` refusing `0`/negative/`NaN` and truncating a fraction, and the two hidden-selection
  reasons. `SelectedResultDetail.spec.ts` **11** re-points its hidden-row test at the two new reasons.
  `App.spec.ts` extends the keep-alive test so the display controls survive leaving and returning with
  no second calculation. Every pre-existing assertion was kept; the tests that predate this story and
  need all four fixture rows now switch the default zero-count filter off through the real control
  (`openScreenListingEveryRow`) rather than having their expectations weakened, and the late-response
  isolation tests are untouched.
- `npm run type-check` clean; `npm run build` OK — 115.63 kB JS (40.80 kB gzip), 14.38 kB CSS
  (3.16 kB gzip).
- `npm run smoke:profit` **PASSED, 18 steps** (was 12) in installed Chrome via `playwright-core`
  against a controlled origin, stub row set extended with a `RECIPE_NOT_ALLOWED` row (6 rows now).
  New and changed steps: the controls open **inside the results region** with zero-count on, the other
  two off, maximum `250` and Show all off, listing **4 of 6** rows — both supplied counts of `0`
  absent and the **null-count** row present; each filter removing only what it names, with the
  null-profit row surviving the "0 or less" filter; **Tab from the search box reaching a filter with a
  visible focus outline**; a search that matches nothing producing the "returned 6 recipes … still
  holds all of them" message, naming search/not-allowed/profit-per-craft and **not** naming the filter
  that was off, with the controls still present; a maximum of `1` cutting the list to one row, the
  cut-off note saying what it withheld, and the **selected recipe** identified as outside the list
  while keeping its own detail; Show all restoring **1 → 4** rows (not 6) with the maximum still `1`
  and the hidden-selection message gone; **zero calculations submitted across all of it** (2 before,
  2 after); a click on a **value cell** moving the selection from "Deldrimor Steel Ingot" to "Bolt of
  Damask" with exactly one row marked; and at **360×800** the controls unclipped at 336px, all 6 rows
  back with the filter off, and a row-background click opening that row's detail with no page overflow.
  The pre-existing side-by-side, keyboard-Enter, sort-preservation, detail-label, replacement-clearing,
  reflow and 200%-zoom steps all still pass unchanged.
- `npm run smoke:layout` **PASSED, 11 steps**, unchanged — **21 rendered pairs meet WCAG AA**, lowest
  4.92:1. The new controls introduce no color of their own: they use the shared `fieldset`/`legend`,
  `label`, `input`, `.meta` and `.notice--warning` treatments already in that sample.
- `npm run smoke:sync` **PASSED, 9 steps**, unchanged.
- Environment discipline per `tasks/lessons.md`: 5193/5194/5195 were probed free on **both** address
  families before use, each script binds and opens `127.0.0.1` and asserts it served the page itself,
  and all three ports were **probed and confirmed refused on both families** afterwards. No dev
  server and no backend was started, so no live data was read or written by any check.

### 8. Limitations

- **No Phase 5 performance claim, and the maximum is not a performance mechanism.** §33 full-page
  timing was neither measured nor attempted. There is still no virtualization and no paging: Show all
  over the live default scope puts all 3175 rows in the DOM, and one tab stop per row remains.
- **No live-backend run.** `smoke:browser` and `smoke:account` need the backend and a dev server and
  were not run; nothing outside `frontend/src/crafting`, `frontend/src/__tests__` and
  `frontend/scripts/profit-browser-smoke.mjs` changed, and no shared stylesheet token was added.
  So every filter/limit behavior here is controlled-response and controlled-origin evidence, not
  evidence over the 3175 real rows.
- **No screen-reader run.** The checkbox labels, the warning notice's `role="status"`, the counts and
  the hidden-selection message were verified mechanically and by Tab order; how they are announced in
  NVDA/JAWS/VoiceOver is unverified, and WCAG 2.2 AA conformance is **not** claimed.
- **The rest of §2.1.1 is untouched and still outstanding**: the total-sell-value column, removal of
  the State column, the wiki link, prominent buy cost in the detail, the CYCLE_DETECTED row-level
  diagnostic and the "Allow non-Trading-Post materials" control (still pending **UD-009**). Those are
  `STORY-WEB-008` and later work; this story changed no column and no economic presentation.
- **The maximum's floor is 1 and there is no ceiling.** A very large typed maximum is accepted and
  simply lists everything that matches, which is what Show all does anyway.
- **Nothing is persisted.** The controls survive navigation (`<KeepAlive>`) and an explicit reload,
  but a browser reload starts at the defaults — consistent with the rest of the frontend, and §2.2.1
  does not require restart persistence.

## Blockers

None.
