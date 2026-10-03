## Story ID

STORY-WEB-024

## Title

Repair browser smoke assertions against current workflow contracts

## Status

UNFINISHED

## Milestone

milestone-05

## Goal

Restore meaningful layout, Crafting Profit, and Discovery smoke coverage where existing browser assertions or readiness selectors contradict the current frontend workflows.

## Authoritative Source Documents / Sections

- `agent/stories/STORY-WEB-018-fresh-build-browser-smoke-checks.md`, Follow-up Findings F002-F004.
- `docs/TEST_STRATEGY.md` §12.2, browser smoke checks.
- Supplied `docs/ROADMAP.md` Phase 5, browser coverage and acceptance.

## Context

STORY-WEB-018 records three smoke scripts that proceed past bundle validation but fail against selectors or expectations inconsistent with current workflow content: layout waits for the removed `sync-controls` hook, Profit expects a phrase contradicted by its component test, and Discovery reads legends from non-legend control containers. These failures prevent the scripts from establishing their intended workflow evidence.

## Acceptance Criteria

1. Align the layout smoke synchronization readiness selector with a current stable workflow hook and retain its later layout assertions.
2. Reconcile the Profit smoke detail assertion with the current selected-result detail contract and component expectations.
3. Reconcile the Discovery control-group assertion with its current rendered control structure while preserving useful workflow checks.
4. Record focused verification for all affected smoke scripts and any remaining limitations.

## Required Tests

- Run the layout, Profit, and Discovery browser smoke checks in a controlled browser runtime and record their results.

## Constraints

- Keep changes within smoke assertions and directly necessary stable hooks.
- Do not weaken workflow or API assertions to make a script pass.
- Do not change product behavior merely to satisfy stale smoke expectations.

## Dependencies

- STORY-WEB-018 (DONE; published as commit 2f9b2d5 and verified by that commit's CI run).

## Definition of Done

The three affected smoke checks use current workflow contracts, retain their intended assertions, and record verification evidence.

## Result

**DONE.** All three affected smoke checks now pass against the current build: `smoke:layout`
**PASSED (13 steps)**, `smoke:profit` **PASSED (34 steps)**, `smoke:discovery` **PASSED (13
steps)**, every run on the same bundle (`dist/index.html built 2026-10-03T17:07:51.824Z`).

Changed: the three smoke scripts, plus one hook line each in `BankScreen.vue` and
`MaterialsScreen.vue`. `frontend/scripts/discovery-browser-smoke.mjs` already carried the
maintainer's own reconciliation of its control-group and inventory-selector steps in the working
tree; that work is preserved and only the two steps below it were repaired.

### AC 1 — layout synchronization readiness

`layout-browser-smoke.mjs` waited for `[data-test="sync-controls"]`, which `SyncScreen.vue` does not
render. It now waits for
`[data-test="price-cache-status"] [data-test="price-cache-health"].status--success` — the hook and
the tone class `SyncScreen.spec.ts` asserts — and the stub answers `/api/system/status` so that
state is reachable. The selector is data-driven on purpose: a page still showing "Loading status…"
or "Status unavailable" fails the wait. Every later assertion is retained and now actually runs, as
the step lines show (headings and titles for all six areas at three viewports, table region, zoom,
focus order, keyboard navigation, contrast, reduced motion, no-synchronization).

Two further blockers surfaced once the script got past that wait, both repaired inside the
story's "smoke assertions and directly necessary stable hooks" constraint:

- `INTRO_AREA` measured the `page intro` contrast pair on `synchronization`, which passes no
  `intro` to `PageHeader.vue`, so the pair measured nothing. It is taken on `discovery` now
  (9.86:1), one of the two areas that still render an introduction.
- `BankScreen.vue` and `MaterialsScreen.vue` render their own `<h1>` without the
  `tabindex="-1" data-page-heading data-test="page-heading"` hooks `PageHeader.vue` carries, so
  `[data-page-heading]` was absent on both areas and `App.vue:56`'s post-navigation
  `.focus()` was a no-op there. Those three attributes were added to the two headings — the shell's
  existing focus behavior, not a new one. Nothing else in either file changed. See F001.

### AC 2 — Profit selected-result detail

The detail region had drifted much further than the one phrase F003 named; `SelectedResultDetail.vue`
is now a single compact accounting list. Reconciled against the component and its spec:

| Step | Was | Now |
| --- | --- | --- |
| 5 | required `For one craft`, `Output revenue`, `Cost of materials to buy`, `Output quantity`, `Instant buy` | requires `Price 1 item (Instant sell)`, `No. craftable Items`, `Total sell value`, `Own materials`, `Bought materials`, `Profit` in `detail-calculation`; the quote labels in `detail-output-quote`; and requires `For one craft` to be **absent**, as `SelectedResultDetail.spec.ts:104` does |
| 5z | two fee notes, per-craft profit `+1g 23s 45c` | one `.value-note` on `detail-total-profit-fee-note`, and the four supplied gross/net values compared by name |
| 5a | `×60`, `Instant buy 1s 20c`, `No price supplied` | `Price / item: …· Total: …` per `materialQuoteText`, each line read from its own `.material-name`/`.material-quantity`/`.meta` element |
| 5b | `detail-totals`/`detail-per-craft` hooks, cost font size | each value matched to the `dt` before its own `dd`; costs share the negative treatment, the revenue differs from both it and plain text, the profit row is the emphasized one |
| 5c | required `Price missing`, `Not tradable, valued at zero`, `SOME_STATE_ADDED_LATER (not recognized)` | requires the compact tree's own content (`60 needed`, sourcing chips, `Crafted by Nbt Anch`, `Value: 10g 30s 86c`, both group summaries) and requires six raw Resolver codes to be absent |
| 5c2 | `node-state-explanation`/`node-blocked-explanation` | `node-player-status` reads `Not available on TP · GW2 Wiki: Charged Core`, its `Value: —` stays missing, and the priced parent carries no mark |
| 5c3 | required the sentence `Nothing blocked the calculation for this recipe.` | requires all six row-state hooks absent, as `keepsGenericRowStateExplanationsOutOfTheNormalSelectedResult` does |
| 5e | scrolled to `detail-diagnostics` | scrolls to the last `missing-item`, the detail's current last line |
| 5g | sampled `.chip--unknown`, `node-state`, `.node__costs`, `.node__note` | samples the seven pairs the compact tree renders, with every group opened first and a named-pair guard instead of a bare count floor |
| 6 | `closest('.panel')`, `legend` only | reads each group's accessible name from `aria-labelledby` or its `legend`, and requires both groups inside the `aria-label`led `.controls-panel` |
| 15 | required a `Recipe loop` chip | requires no row-state label at all, plus nine forbidden strings — `DOMAIN_SPEC.md` 2.1.1: the comparison table displays no generic row/domain state label, code or explanation |
| 15b/15c | required `Further crafting is blocked`, the echoed maximum-buy sentence, the affected-item sentence, `buying is switched off` | require those four hooks and four phrases absent, and the counted crafts still stated: `24g 0s 0c` purchase cost, `+14g 81s 40c`, `For all 4 crafts counted` / `For all 0 crafts counted` with `Nothing needs to be bought.` |

The fixture was completed to the current contract rather than the assertions loosened:
`totalMatsSellValueCopper` on the rows and `purchaseUnitPriceCopper`/`totalPurchaseCostCopper` on
every `MissingItem`, each a value the page cannot derive (74s 4c is not 60 × 1s 20c, 5g 18s 52c is
not 12 × 43s 21c), so a view computing instead of reading disagrees visibly.

### AC 3 — Discovery

The maintainer's working-tree version already reconciled the control-group assertion (membership in
the `aria-label`led panel instead of `legend` text) and the inventory selector, and reached step 8.
Two steps below it were still stale, both because `CraftingResolution` is used in
`selected-result-mode`: `resolution-basis` and `resolution-root-sourcing` are not rendered in the
compact presentation (and this fixture's tree root *is* the requested recipe, so `rootSourcing` has
nothing to report), and `node-effective-cost` is replaced by `node-value`. The basis/sourcing
assertion is now the compact contract — none of `resolution-basis`, `resolution-tree-note`,
`resolution-root-sourcing` rendered, and neither phrase in the detail text — and the two cost
assertions read `Value: 78g 33s 32c` (the backend's own inclusive figure, not the children added
up) and `Value: —`.

### AC 4 — verification evidence

Commands run from `frontend/`, real Chrome, each script's stub origin on `127.0.0.1` in its own
process. Build: `npm run build` → `✓ built in 1.15s` (its `vue-tsc --noEmit` type-check clean).

- `npm run smoke:layout` — **PASSED (13 steps)**, 78 API requests, all reads.
- `npm run smoke:profit` — **PASSED (34 steps)**, 10 calculations, 7 detail requests, 9 selector
  reads; step lines name the labels and values compared, e.g. `detail separates calculation, quote,
  resolution and materials — 14 labels present in their own regions; no per-craft basis and no
  calculated shopping-list total` and `the counted crafts stay stated and the limit is not explained
  at 1440px — purchase cost 24g 0s 0c for the 4 crafts counted, Glob of Ectoplasm ×20`.
- `npm run smoke:discovery` — **PASSED (13 steps)**, 3 table and 1 detail request, no off-origin
  request.
- Controls, run from throwaway copies next to the scripts and deleted afterwards (`git status`
  lists no `.tmp-control-no-status.mjs` or `.tmp-control-listing-sell.mjs`; the tracked
  `.tmp-control-unrelated-call.mjs` is STORY-WEB-018 F001, untouched):
  - `.tmp-control-no-status.mjs` (layout copy with the `/api/system/status` answer removed) —
    exit **1**, `FAILED after 2 step(s): page.waitForSelector: Timeout 8000ms exceeded … waiting
    for locator('[data-test="price-cache-status"] [data-test="price-cache-health"].status--success')`.
    The new readiness selector really gates on the status answer rather than matching markup that is
    always present.
  - `.tmp-control-listing-sell.mjs` (profit copy with the echoed `listingSell: true`) — exit **1**,
    `FAILED after 6 step(s): the calculation list did not contain "Price 1 item (Instant sell)"`.
    The reconciled label is compared against the settings the stub echoed, not matched by accident.
- Unit suites covering the two product files the commit carries, and the shell that reads their new
  hook: `npx vitest run src/account/__tests__` → `Test Files 3 passed (3) | Tests 29 passed (29)`;
  `npx vitest run src/shell src/sync/__tests__/SyncScreen.spec.ts` → `Test Files 1 passed (1) |
  Tests 19 passed (19)`; `npx vitest run src/__tests__/App.spec.ts` → `Test Files 1 passed (1) |
  Tests 15 passed (15)`.

No Java or TestFX suite was run: `git status` shows the commit carries only these five files plus
the two untracked `docs/images/*.jpg` the maintainer added, none of which reaches backend code.
None of these browser scripts runs in CI (`TEST_STRATEGY.md` §12.2); GitHub Actions remains the
full-regression gate.

### Remaining uncertainty

The scripts now encode the product's current compact presentation, which means several assertions
are absence claims (no row-state block, no raw Resolver codes, no basis prose). Those are each
backed by a named component-spec expectation or a `DOMAIN_SPEC.md` 2.1.1 sentence, but an absence
claim stops being evidence the day the feature returns deliberately. Two presentation branches are
now unreachable from either crafting page and therefore unverified in a browser (F003, F004), and
one placement in `CraftingProfitScreen.vue` deviates from 2.1.1 (F002) — the Profit check reports
where the result maximum is rendered rather than asserting either placement, so neither the
deviation nor a future correction is baked in.

## Blockers

None.

## Follow-up Findings

F001: `BankScreen.vue` and `MaterialsScreen.vue` build their own page header instead of using
`PageHeader.vue`, so the `tabindex="-1" data-page-heading data-test="page-heading"` contract is now
written out in three places, and neither screen can carry an `intro` or a `page-actions` group. This
story added only the three focus hooks; adopting the shared component would also move their reload
buttons into its `actions` slot.

F002: `DOMAIN_SPEC.md` 2.1.1 lists the changeable maximum displayed recipe count and Show all among
the result-display controls to be placed inside the Calculation controls panel's Displayed results
subgroup, but `CraftingProfitScreen.vue` renders them in the results toolbar
(`[data-test="opportunities-limit"]`, inside `[data-test="results-region"]`). The three filters and
the search are in the panel as specified.

F003: `ResolutionTreeNode.vue`'s `compact-value` prop is documented as "Profit shows effective
value; Discovery retains the separate cost figures", but `CraftingResolution.vue` passes
`:compact-value="selectedResultMode"` and `SelectedDiscoveryDetail.vue` sets `selected-result-mode`,
so Discovery is compact too. Its non-compact branch — `node-costs`, `node-cash-cost`,
`node-opportunity-cost`, `node-effective-cost`, `resolution-basis`, `resolution-tree-note` — is
unreachable from either crafting page.

F004: `resolutionPresentation.ts`'s `describeRootSourcing` has no browser coverage left. Both
crafting pages use `selected-result-mode`, where `CraftingResolution.vue` returns null whenever the
tree root carries the requested recipe id, and both smoke fixtures build their tree that way — so
none of its three sentences is rendered in `smoke:profit` or `smoke:discovery`.
