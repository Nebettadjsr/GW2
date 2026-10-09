## Story ID

STORY-WEB-020

## Title

Exercise backend total sell value in the shared layout smoke fixture

## Status

DONE

## Milestone

milestone-05

## Goal

Make the Crafting Profit and Discovery layout browser check render and measure a backend-supplied total sell value instead of a placeholder in every fixture row, and bring that shared fixture back into agreement with the current backend contract.

## Authoritative Source Documents / Sections

- `agent/stories/STORY-WEB-019-layout-smoke-navigation-coverage.md`, Follow-up Findings F001.
- `docs/TEST_STRATEGY.md` §12.1–12.2, controlled fixture values and browser layout checks.
- Supplied `docs/ROADMAP.md` Phase 5, frontend rendering from backend-provided values and frontend tests.
- `frontend/src/api/types.ts`, the current `CraftingRow`, `EffectiveDiscoverySettings` and `CraftingDiscoveryResponse` shapes.

## Context

The shared `profitRows()` fixture in `frontend/scripts/layout-browser-smoke.mjs` omits
`totalSellValueCopper`. The layout check therefore does not exercise the money presentation for the
Total sell value column on either screen, although both tables render it — `CraftingProfitTable.vue`
as "Total sell value" and `DiscoveryTable.vue` as "Sell value".

The same fixture has since drifted from the backend contract in two further ways, found during the
2026-10-03 story audit. `CraftingRow` gained `totalMatsSellValueCopper` (total owned-material
opportunity value across the counted crafts), which the fixture does not supply. The script's
Discovery stub still answers with `inventoryCharacterName` and a settings `maxBuyCopper`, both of
which were removed from `CraftingDiscoveryResponse` and `EffectiveDiscoverySettings` when Discovery
moved to one combined character/discipline scope. Correcting these alongside the missing total keeps
the check measuring the contract the browser actually receives, and is a single edit to the same
fixture rather than separate work.

## Acceptance Criteria

1. Supply `totalSellValueCopper` in representative shared Profit/Discovery fixture rows so the Total sell value cells render monetary values during layout checks.
2. Make at least one supplied total deliberately differ from a simple multiplication of other fixture fields, preserving the check's ability to expose client-side derivation.
3. Supply `totalMatsSellValueCopper` in the same representative rows, consistent with each row's other supplied values and with its null/unavailable cases.
4. Remove the obsolete `inventoryCharacterName` field and the obsolete settings `maxBuyCopper` field from the script's Discovery stub response so it matches the current `CraftingDiscoveryResponse` and `EffectiveDiscoverySettings`. Leave Profit's own `maxBuyCopper`, which the Profit contract still has.
5. Preserve existing gain, loss, and unavailable fixture cases and their intended presentation; do not add a browser calculation.
6. Confirm the layout check reaches its relevant contrast and responsive assertions with the populated column.

## Required Tests

- Run `npm run smoke:layout` in a controlled browser runtime and verify the populated Profit and Discovery rows and existing layout/contrast assertions.

## Constraints

- Change fixture data and only directly necessary smoke assertions; do not alter authoritative backend values or frontend economic calculations.
- Keep the check focused on presentation rather than domain arithmetic.
- Correct the fixture towards the current contract only. Do not reshape the Discovery request/response contract itself, reintroduce a separate Discovery inventory character, or restore a Discovery buying budget.

## Dependencies

- STORY-WEB-019 (source of the finding; finish its layout smoke restructure first).

## Definition of Done

Acceptance criteria are met, the shared layout fixture agrees with the current backend contract, the named browser smoke check passes, and Result records the fixture and verification evidence.

## Result

Changed one file, `frontend/scripts/layout-browser-smoke.mjs`.

**Fixture (AC 1–3, 5).** The shared `profitRows()` factory now supplies
`totalSellValueCopper: 1_284_509` and `totalMatsSellValueCopper: 51_852`, with per-row overrides:
the zero-count `BUYING_DISABLED` row supplies `0` for both (a rendered `0c`, not the missing
marker), the `PRICE_UNAVAILABLE` row keeps the supplied totals because its output is still quoted
and only an ingredient price is missing, and the `resultAvailable: false` row supplies `null` for
both. The gain, loss, zero-count and unavailable cases, and every other fixture value, are
unchanged; no arithmetic was added to the browser. The total sell value is deliberately
non-derivable — 1 284 509 is neither 12 × 111 110 (= 1 333 320) nor 1 × 111 110 — while the
owned-material total stays the 12 × 4 321 its per-craft basis implies, which no table scales.

**Discovery stub (AC 4).** `inventoryCharacterName` and `settings.maxBuyCopper` are gone from the
`/api/crafting/discovery` answer, so its envelope is now exactly `CraftingDiscoveryResponse` and
`EffectiveDiscoverySettings`. The Profit answer keeps its own `maxBuyCopper`. Neither the request
contract, a separate Discovery inventory character nor a Discovery budget was reintroduced.

**Assertion (AC 1, 6).** The populated column was previously invisible in the run's output, so the
script gained one step that names what it compared. New step 4 opens Crafting Profit at 1440×900,
switches the three display filters off so all five fixture rows are on screen, and compares every
rendered `total-sell-value` cell against the total the stub itself supplied for that row id, then
repeats it for Discovery's `discovery-sell-value` column. It also fails when no row is on screen,
when fewer than the five supplied rows rendered, and when the column holds no monetary amount at
all. The previously duplicated display-filter list is now one `DISPLAY_FILTERS` constant used by
step 4 and step 8; the later steps are renumbered 5–11 accordingly.

**Verification.** In `frontend/`, against the shipped file:

- `npm run build` — `vue-tsc --noEmit` clean, `✓ built in 1.03s`, 109 modules.
- `npm run smoke:layout` — **PASSED (14 steps)**, bundle `dist/index.html built
  2026-10-09T16:50:55.779Z`, stub origin `http://127.0.0.1:5175`, Chrome
  `C:/Program Files/Google/Chrome/Application/chrome.exe`, 87 API requests, all reads, no page
  error. The new step line reads:
  `ok both tables show the supplied total sell value — crafting "Total sell value": recipe 1 128g
  45s 9c, recipe 2 0c, recipe 5 128g 45s 9c, recipe 3 128g 45s 9c, recipe 4 —; discovery "Sell
  value": recipe 1 128g 45s 9c, recipe 2 0c, recipe 3 128g 45s 9c, recipe 4 —, recipe 5 128g 45s
  9c`. The responsive and contrast steps were reached with the populated column:
  `ok table scrolling stays inside its own region at 360px — region 334px wide holds 886px of
  columns`, `ok all 6 areas are named, marked and reflow at phone 360×800`, and `ok 17 rendered
  text/background pairs meet WCAG AA — lowest primary action at 4.92:1`.

Two controls were run from throwaway copies (`scripts/.tmp-control-*.mjs`), never from the shipped
file, and both were deleted — `git status` lists only `frontend/scripts/layout-browser-smoke.mjs`
as modified under `frontend/`:

- expectation changed to the derived product: FAILED at step 4 with `"Total sell value" did not
  show the supplied total for recipe 1 (128g 45s 9c, supplied 133g 33s 20c)` and the same for
  recipes 3 and 5 — the page displays the supplied total, and the step tells the two apart.
- `totalSellValueCopper` omitted again (the pre-change state): FAILED at step 4 with `"Total sell
  value" rendered no monetary amount at all, only —, —, —, —, —`.

The commit also carries the maintainer's uncommitted `agent/runtime/support/git_sync.py` rework, so
the harness suites covering it were run with the gate's own import mode:
`python -m pytest agent/runtime/tests/test_ci_verification.py agent/runtime/tests/test_pipeline_recovery.py agent/runtime/tests/test_completion_recovery.py -q --import-mode=importlib -o consider_namespace_packages=true`
→ `64 passed in 6.08s`. (Without CI's `--import-mode=importlib`, the same selection reports nine
failures from the test package being imported twice under two module names; that is the local
invocation, not the code.)

**Not established.** The populated column did not change the measured 360px region width (886px
with and without the supplied totals — the column is sized by its heading and note, not by the
money text). No Vitest run was needed: no `src/` file changed and no component test imports this
script. Nothing here says anything about the backend's own totals, about real data, or about
conformance beyond the pairs and widths this script measured.

## Blockers

None.

## Follow-up Findings

- F001: the same fixture's unavailable row (`recipe 4`, `resultAvailable: false`) still supplies
  non-null `matsSellValueCopper` (4 321) and `revenueCopper` (111 110), although
  `frontend/src/api/types.ts` states every result-derived field is null when `resultAvailable` is
  false — so that row renders an "Own materials" amount for a calculation that produced no result.
- F002: no fixture row in that script supplies the required `CraftingRow.iconUrl`, and its
  `missingToBuy`/`missingToBuyOne` entries omit `MissingItem.purchaseUnitPriceCopper`,
  `totalPurchaseCostCopper` and `iconUrl`, so those fields are `undefined` rather than a supplied
  value or null in the controlled answer.
- F003: `frontend/scripts/discovery-live-smoke.mjs` still requires the removed response field —
  `checkContract` asserts `Object.hasOwn(body, 'inventoryCharacterName')` and `checkDetail`
  compares `body.calculation.inventoryCharacterName` — while `CraftingDiscoveryApiControllerTest`
  asserts the backend no longer sends it, so that live check must fail at its contract step.

## Follow-up Findings Disposition

F001: FOLLOW-UP STORY ? STORY-WEB-030
F002: FOLLOW-UP STORY ? STORY-WEB-030
F003: FOLLOW-UP STORY ? STORY-WEB-030
