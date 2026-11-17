## Story ID

STORY-WEB-005

## Title

Separate Crafting Profit comparison results, calculation controls and selected details

## Status

DONE

## Milestone

milestone-05

## Goal

Make the existing Crafting Profit screen usable for scanning opportunities and examining one result by applying the authoritative UX hierarchy without recomputing backend values or waiting for the unfinished resolution API.

## Authoritative Source Documents / Sections

- Supplied `docs/ROADMAP.md` Phase 5: Crafting Profit table/details, backend-authoritative rendering and frontend interaction tests.
- `docs/TARGET_ARCHITECTURE.md` sections 12–14: frontend responsibilities, resolution detail boundary and special domain states.
- `docs/FRONTEND_UX_GUIDELINES.md` sections 3–8: responsive comparison/detail layout, economic presentation, state, accessibility and verification.
- `docs/CURRENT_ARCHITECTURE.md` section 5.11 and STORY-WEB-001: existing Profit table, inputs, supplied totals and stale-response protection.
- `agent/product-owner-requests/Request-003-UX-guidelines.md`: Crafting Profit first-draft expectations and immediate correction.

## Context

The Product Owner rejects a full-width table exposing every field. The existing table contract already supplies summary and material information that can be presented separately. The semantic tree requires STORY-DOM-020 and subsequent API/browser integration under the resolved section 13 contract; this bounded correction does not claim to deliver that tree.

## Acceptance Criteria

1. Review the actual Profit screen against guidelines sections 3–7 and record the concrete structural changes in Result. Use STORY-WEB-004's shared shell and visual conventions.
2. Group calculation scope/settings separately from primary results. Keep search, sorting and reload easy to locate, preserving effective settings, valid control state and the existing protection against superseded responses. Do not introduce a second set of backend defaults.
3. Reduce the default comparison table to a purposeful set emphasizing identification, backend profitability, craftability and concise availability/blocking information. Label per-craft versus total money values clearly; render and sort authoritative totals directly. Move supplementary quote/cost/material fields into selected-result details rather than dropping access to useful information.
4. Provide keyboard-operable result selection with visible selection and a separately labelled detail region containing the selected row's supplied summary and available material requirements. Preserve null versus zero, blocked/unavailable rows and any supplied special states. Use user-oriented explanations and secondary diagnostic disclosure. Do not fabricate metadata absent from the API or calculate a new shopping list from raw quantities.
5. Selection uses recipe identity, not row position. Sorting may retain a valid selected recipe; a replacement calculation or removal of the selected recipe clears or explicitly refreshes details so stale values never appear under a different result. A late table response must not replace current selection details. Preserve valid search/sort/settings during explicit refresh.
6. On wide viewports, provide balanced results/detail regions; on narrow viewports, reflow details below the results or use an accessible dedicated detail presentation. Bound widths, preserve usable controls and keep unavoidable table scrolling local. Economic values are scannable with consistent formatting and semantic color accompanied by text/signs/structure.
7. Provide honest loading, empty, error, unavailable and blocked presentations in the relevant regions. Verify keyboard access, focus visibility, labelled controls, contrast, zoom and reflow against guidelines section 7.
8. Do not call unimplemented resolution routes or synthesize a tree from existing prices/material maps. Keep the layout ready for the distinct tree and material regions required by guidelines section 4, but describe the current detail's actual basis truthfully. Record the remaining semantic-tree/API integration in Result; this story does not satisfy the full resolution-tree exit criterion.
9. Update the current frontend description at its authoritative owner and record actual checks and limitations. Preserve backend contracts, authoritative calculations and JavaFX coexistence.

## Required Tests

- Component tests using controlled supplied values for compact table versus selected-detail rendering, backend totals deliberately different from any client-side product, null/zero distinctions, negative/positive results, blocked/unavailable and unfamiliar states where the contract permits them.
- Interaction tests for keyboard result selection, details under sorting/filtering, selection invalidation on reload/input changes, superseded responses, empty/error recovery and preservation of valid controls.
- Affected existing frontend regressions, type checking and production build; real-browser wide/narrow layout and keyboard checks with recorded viewport evidence. Verify the complete returned result set remains accessible, including through search/sort.
- Exercise the actual read-only backend path where available to confirm unchanged request/response use. Record limitations; these functional checks are not the separate section 33 full-page performance acceptance evidence.

## Constraints

- Presentation/interaction correction only; no domain calculation, API-shape change, automatic synchronization or direct GW2 call.
- Follow existing frontend technology and shared patterns; do not use a broad redesign to add unrelated features.
- No silent result omission, fabricated resolution, inferred procurement total, or completed-performance claim. Subsequent tree work must follow TARGET_ARCHITECTURE section 13.

## Dependencies

- STORY-WEB-004.
- STORY-WEB-001 (DONE). No dependency on STORY-DOM-020 for this existing-payload presentation correction.

## Definition of Done

Crafting Profit separates controls, comparison and selected details in a usable responsive layout, backend values remain authoritative, relevant rendering/interaction/browser checks pass, and Result identifies the unfinished tree integration without claiming Phase 5 completion.

## Result

Crafting Profit is now three groups — calculation controls, a six-column comparison table, and a
separately labelled detail region for the one selected result — laid out side by side on a wide
viewport and stacked on a narrow one. **No file under `src/`, no `pom.xml` and no resource was
touched** (verified by modification time: nothing under `src/` changed after 12:14, before this
session's first edit at 12:37), so no domain rule, HTTP contract or JavaFX behavior changed and the
Java suite was deliberately not re-run — there was nothing in it to affect.

### 1. Gaps found in the implemented screen (AC 1)

Inspected in the browser and in source before changing anything, against `FRONTEND_UX_GUIDELINES.md`
§3–§7. `STORY-WEB-004`'s shell, tokens, panels, notices, status pills and `.table-region` were
already in place and are reused unchanged; these are the gaps that story explicitly left open.

- **§4 one flat table of fourteen columns.** Recipe, Per craft, Disciplines, Min rating, Craftable,
  Profit / craft, Total profit, Buy cost, Mats value, Revenue, Status, Output TP buy, Output TP sell,
  Still missing — every backend field in the scanning view, with 3175 live rows behind it.
- **§4/§5 the two money bases were silently mixed.** "Buy cost" sat between per-craft values, but
  `buyCostCopper` is a **total for every craft counted** while `revenueCopper`, `matsSellValueCopper`
  and `profitCopper` are **per single craft** (`craft.CraftResult`, `web.dto.CraftingRowDto`). Only
  "Profit / craft" and "Total profit" said anything about their basis at all.
- **§4 no selection and no detail of any kind.** Nothing could be chosen, so "details for the
  currently selected result" and the labelled tree/material regions the Product Owner named did not
  exist; `missingToBuyOne` was never rendered anywhere, and `missingToBuy` was truncated to the first
  three items with "(+n more)" — the rest of the list was unreachable in the client.
- **§5/§13 raw enums as the user-facing status.** `NONE`, `PRICE_UNAVAILABLE`, `BUYING_DISABLED` and
  `NO RESULT` were the status text. `NONE` in particular tells a user nothing, and an unfamiliar code
  had no defined treatment.
- **§5 profit and loss were told apart by color and a minus sign only**, and the positive case had no
  sign of its own.
- **§5 recipe *and* item id on every row** in the primary workflow, while a row whose `outputName`
  the backend did not supply rendered as an empty cell — the TypeScript type claimed `string` where
  `web.CraftingRowMapper` returns null.
- **§3 the six settings controls were permanently open** above the results, so the controls panel was
  taller than the first screenful of opportunities.
- **§8 no browser evidence for any of this** existed; `smoke:layout` covered the shell only.

### 2. What was built

**Comparison table reduced to six columns** (`CraftingProfitTable.vue`): Recipe (a `<th scope="row">`,
so every value in the row has a name as well as a column), Disciplines, Craftable, Profit *per craft*,
Total profit *all crafts*, State. Each money header carries its basis as a second line, and the
table's `<caption>` states it again for assistive technology. Sorting is offered on exactly those six
keys; `totalProfitCopper` desc remains the default and is still sorted by the supplied value.

**Everything else moved, not dropped** (`SelectedResultDetail.vue`): a labelled region with a
per-craft group (output quantity, revenue, own materials given up, profit), a totals group headed
"For all N crafts counted" (crafts possible, cost of materials to buy, total profit), the output
item's raw trading-post quote, a **Crafting steps** region, a **Materials still to buy** region with
**both** supplied lists — `missingToBuy` under the totals basis and `missingToBuyOne` under "For one
further craft", each line a supplied quantity and its own quote, **complete, no longer truncated** —
and a closed "Technical details" disclosure holding recipe id, output item id, the result-supplied
flag and the raw state code.

**Selection by recipe identity** (`useProfitTableView.ts`): `selectedRecipeId` plus a `selectedRow`
computed *read from the current rows*. Sorting and searching keep a valid selection; a replacement
calculation shows that recipe's new values; a recipe the newest result set no longer contains is
deselected by a watcher rather than lying dormant; a superseded response never reaches `rows`, so it
can never reach the detail. The control is an ordinary `<button>` carrying the recipe name — Tab and
Enter/Space, no custom widget — and the selected row is marked by `aria-current="true"` on both the
row and the button, a raised surface, a highlight border, a "▸" glyph and a visually hidden
"Selected".

**Domain states in words** (`rowState.ts`): each of the seven `craft.BlockedReason` names of
`DOMAIN_SPEC.md` §42 has its own wording, stated as *further* crafting being blocked when the backend
still counted crafts — which is what the field means (`craft.CraftResult`: "completed crafts remain
valid"), and the distinction the JavaFX `CraftingResultPresentation` already drew. `NONE` with no
craftable count reads "None craftable", `resultAvailable: false` reads "No result", an absent state
reads "State not reported", and an unrecognized code is shown as itself and is never toned as
success. The raw code survives only in the disclosure, and the search still matches both it and the
displayed words.

**Signed money** (`formatSignedCopper`, `moneyTone`, shared `.money--gain`/`.money--loss`): the
written sign distinguishes gain from loss, the tone only reinforces it, and a supplied `0c` and an
unsupplied `—` both stay neutral and stay distinct.

**Layout** (shared `.results-split` in `styles.css`): side by side from 64rem with the detail bounded
at 20–26rem, stacked below under that width, `minmax(0, 1fr)` so the table region shrinks instead of
stretching the page. Shared additions: `.results-split*`, `.visually-hidden`, `.status--caution`,
`.money--*`. The settings became a closed "Price and material settings" disclosure whose summary line
words the settings **the backend echoed** — no default is repeated in the browser — while scope,
search and Reload results stay in the open.

**Identification fixed** (`api/types.ts`, `recipeLabel.ts`): `outputName` is now typed `string | null`
as `web.CraftingRowMapper` actually returns it, and a row without a name is identified as
`Item #<outputItemId>` (§5 allows the id as the fallback). This was in scope because the recipe name
is now the accessible name of a control. The row now shows `recipe <id>` only; the output item id
moved to the detail.

### 3. Judgement calls

- **Sorting by buy cost, revenue, mats value, output count and min rating is gone**, because those
  columns are gone. Reducing the table was the story's point; the values themselves remain reachable
  per recipe in the detail. Recorded rather than hidden.
- **A search that hides the selected row does not clear the detail.** The recipe is still in the
  loaded result set, so the detail stays and says the search is hiding its row. Only removal from the
  *result set* deselects.
- **One tab stop per row.** 3175 rows is 3175 tab stops. §7 prefers ordinary semantic controls over
  custom widgets, so a native button won over a roving-tabindex grid; the cost is recorded below.
- **The caption is visually hidden.** The table is the horizontal scroll container's content, so a
  visible caption would be laid out at the full column width and stop wrapping. The same sentence is
  on screen above the region instead.
- **A "Crafting steps" region exists and says it is empty.** §4 asks for the tree as its own labelled
  region; an honest "not available yet, and this page never reconstructs one" keeps the layout ready
  without fabricating anything.

### 4. Verification

- `npm test` **132/132** in 13 files (98 before), controlled responses only.
  `crafting/__tests__/CraftingProfitScreen.spec.ts` (25, was 14) adds the six-column set, the
  keyboard-operable control, the selection marking, selection surviving a re-sort, a replacement
  calculation's values under the same recipe, the detail clearing when the selected recipe is dropped,
  **a superseded response leaving the selected detail alone**, search/sort/selection surviving an
  explicit reload, the detail staying reachable while the search hides its row, the effective settings
  readable while their group is closed, and the detail's loading/error/empty wording. New
  `SelectedResultDetail.spec.ts` (11): the per-craft/total bases, the supplied total **deliberately
  different** from count × per-craft profit, loss by sign *and* tone, null kept apart from zero, the
  blocked explanation without its code, the two material lists and their bases, an empty list kept
  apart from an unsupplied one, the no-tree statement, and the item-id fallback. New `rowState.spec.ts`
  (6) including an unrecognized code never toned as success. `useProfitTableView.spec.ts` (8, was 4)
  adds selection through sorting/filtering, reading the selected row from the current result set, and
  the clear-on-removal that does not later resurrect. `formatCopper.spec.ts` (6, was 3) adds the sign
  and tone rules.
- `npm run type-check` clean; `npm run build` OK — 109.58 kB JS (39.15 kB gzip), 13.91 kB CSS
  (3.10 kB gzip).
- **New `npm run smoke:profit`** (`scripts/profit-browser-smoke.mjs`), installed Chrome via
  `playwright-core` against a controlled origin in its own process: **PASSED, 12 steps**. At
  **1440×900** results 968px and detail 416px side by side with no page overflow; the empty detail
  states its case; **focus reached a row control with a visible outline and Enter selected
  "Deldrimor Steel Ingot"**, focus staying on the control; exactly one row marked, with the glyph and
  the hidden "Selected" label; re-sorting by name left **the same recipe** selected and the detail
  unchanged; ten expected detail labels present including "For all 12 crafts counted", "For one
  further craft", "Crafting steps", "Not available yet" and the unnamed material's `Item #19701`; a
  scope change that drops the selected recipe **cleared the detail and the marking while the search
  box kept its value**; at **360×800** the detail sat below the results with **no page-level
  horizontal scrolling** and the table's own region held 767px of columns in 334px; all 5 rows still
  present and selectable there; **200% root font size** still reflowed (1440px in 1440px); and across
  the run there was no `/api/sync` or `/api/prices` call and no page error.
- `npm run smoke:layout` **PASSED, 11 steps** after updating its contrast samples to the new
  treatments: **21 rendered text/background pairs meet WCAG AA**, lowest the primary action at
  **4.92:1** — the set now includes the column basis note, the recipe identifier, the settings summary,
  the three row-state tones and **both money tones**; four areas reflow at 1440/768/360 with no
  page-level horizontal scrolling; 200% text size reflows; the keyboard walk and visible focus are
  unchanged; 5 controls at `0s` under reduced motion; 37 API requests, none a synchronization.
- `npm run smoke:sync` **PASSED, 9 steps**, unchanged — the synchronization contract was not touched.
- Live read-only regressions against the running backend and the user's real PostgreSQL:
  `npm run smoke:browser` **PASSED, 9 steps** (was 8) — 30 selector entries, HTTP 200, **3175 rows**,
  first total `+26g 41s 76c` (the same `26g 41s 76c` `STORY-WEB-004` recorded, now signed), sort,
  search, scope change to `DISCIPLINE / Chef` → 433 rows, and a **new step**: clicking a real row
  opened the detail for **that** recipe with the summary/materials/steps regions present and
  **no further backend request**, which is the request/response use being unchanged. `npm run
  smoke:account` **PASSED, 8 steps** (180 bank slots, 17 empty, supplied order; 9 categories / 505
  stacks; no `/api/sync` call), confirming the shared-stylesheet additions did not disturb §5.12's
  screens.
- Environment discipline per `tasks/lessons.md`: the controlled checks ran on **5186/5187/5188**,
  probed free on both address families first; the live checks used a dev server this session started
  on **127.0.0.1:5192** (`--strictPort`, HTTP 200 confirmed before any check). Stopping the background
  task left the `vite` child listening — exactly the recorded failure — so the owning PID was found
  via `WMIC process … get ProcessId,CommandLine`, killed with `taskkill /PID … /T /F`, and the port
  then **probed and confirmed refused on both families**. The four pre-existing `npm run dev` servers
  were identified and left untouched.

### 5. Remaining work and limitations

- **The semantic resolution tree is not delivered and no Phase 5 exit criterion is claimed.**
  `STORY-DOM-020` has since supplied the domain trace (`craft.SingleCraftExplainer`), but nothing
  above it exists: `STORY-APP-012` must coordinate the selected-row detail in the application layer,
  a bounded API story must then implement `POST /api/crafting/profit/resolution` under
  `TARGET_ARCHITECTURE.md` §13 — including §13.1's explicit-input rule (the browser copies the table
  response's effective `scope`/`settings`, never row numbers, prices or material maps), §13.2's
  fresh-context/consistency wording and §13.4's missing-result and late-browser-response handling —
  and only then can a frontend story fill the **Crafting steps** region from that route. This story
  calls no detail route and builds no tree; the region says so on screen.
- **`missingToBuy`'s basis is the backend's, and is labelled as such.** It is the total across every
  craft counted and `missingToBuyOne` is one further craft; neither is summed, priced out or turned
  into a procurement quantity here. No shopping list in the §4 sense exists yet.
- **One tab stop per row**, so reaching a row far down the 3175 by keyboard alone is slow. No
  virtualization, paging or roving tabindex; the table header still does not stay visible while
  scrolling (`STORY-WEB-004`'s recorded trade).
- **No real assistive-technology run.** `aria-current`, the row headers, the caption, the accessible
  names and the hidden "Selected" were verified mechanically; no screen reader was used, so how the
  selection is announced in NVDA/JAWS/VoiceOver is unverified. Full WCAG 2.2 AA conformance is **not**
  claimed.
- **Contrast is measured, not exhaustive** — 21 rendered pairs plus the five probe tones; the selected
  row's own combinations and the disabled-control pair were not measured, and `forced-colors` /
  `prefers-contrast` were not tested. **Zoom was checked as 200% root font size** and as a 360px
  viewport, not as true browser page zoom.
- **§33 full-page performance is neither measured nor claimed**, and no milestone transition is
  claimed. Adding a detail region to a 3175-row page has not been timed.

## Blockers

None.
