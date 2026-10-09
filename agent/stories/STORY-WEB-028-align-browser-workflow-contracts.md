## Story ID

STORY-WEB-028

## Title

Align remaining browser workflow contracts and coverage

## Status

DONE

## Milestone

milestone-05

## Goal

Resolve the remaining Phase 5 browser consistency findings recorded by STORY-WEB-024 and verify that shared page structure, compact resolution presentation, and root-sourcing presentation match their current authoritative contracts. Preserve the confirmed Crafting Profit placement of the maximum and Show all beside the results title.

## Authoritative Source Documents / Sections

- `agent/stories/STORY-WEB-024-repair-fresh-build-smoke-contracts.md`, Follow-up Findings F001-F004.
- `docs/DOMAIN_SPEC.md` §2.1.1, current Crafting Profit display-control placement.
- `docs/TEST_STRATEGY.md` §§12.1-12.2, frontend and browser smoke verification.
- Supplied `docs/ROADMAP.md` Phase 5, browser rendering, coverage, and acceptance.

## Context

STORY-WEB-024 records four remaining concerns: Bank and Materials duplicate the shared page header structure; its F002 described the maximum displayed recipe count and Show all controls being outside the Calculation controls subgroup as a discrepancy; the compact-value prop documentation disagrees with the presentation used by both crafting workflows; and browser smoke fixtures no longer exercise `describeRootSourcing` output. The F002 placement requirement is superseded by the later confirmed Product Owner decision and the reconciled `DOMAIN_SPEC.md` §2.1.1. Confirm each remaining concern against the current implementation before changing it. Preserve established product behavior and backend-provided authoritative values.

## Acceptance Criteria

1. Reconcile Bank and Materials page headers with the shared page-header contract, preserving current navigation focus behavior and reload actions.
2. Preserve the maximum displayed recipe count and Show all beside the results table title, as specified by the current DOMAIN_SPEC §2.1.1. Verify that the smoke check asserts this placement and that the separate calculation and filter groups retain their accessible grouping. Do not move these controls into the Calculation controls panel.
3. Make the compact-value documentation and component behavior accurately describe the presentation used by Crafting Profit and Discovery; remove unreachable presentation branches only if the current implementation confirms they are unused and removal is within this scope.
4. Add browser smoke coverage that reaches and verifies the root-sourcing presentation produced by `describeRootSourcing`, using fixture data that actually renders that presentation.
5. Add or update focused component/browser checks for each changed contract and retain meaningful existing workflow assertions.
6. If any recorded concern is already resolved or superseded by a later confirmed Product Owner decision, preserve that implementation and record the confirming evidence and disposition in Result rather than reintroducing the issue.

## Required Tests

- Focused component tests for any changed page-header, Crafting Profit control, or resolution presentation behavior.
- Controlled-browser smoke checks for affected navigation/layout behavior and both crafting workflows, including a case that renders root-sourcing text.
- Review affected frontend coverage reports and unresolved quality gaps as required by `docs/TEST_STRATEGY.md`.

## Constraints

- Keep changes within the four findings and their direct tests.
- Preserve domain behavior and backend ownership of authoritative crafting results.
- Do not weaken existing browser assertions to make checks pass.
- Do not restore obsolete Ectoplasm backend calculation behavior.

## Dependencies

- STORY-WEB-024 (DONE; source of findings).

## Definition of Done

Each finding is checked against the current implementation; applicable contracts are aligned; focused component and browser checks pass; affected coverage is reviewed; and Result records verification evidence and any finding already resolved without code changes.

## Result

**COMPLETE.** Each of STORY-WEB-024's four concerns was checked against the current implementation
first: F001 and F003 were real and are now fixed, F004 was real and is now covered in both controlled
browser checks, and F002 is superseded — its placement was preserved untouched and is asserted where it
already was.

### AC 1 — Bank and Materials use the shared page header

`BankScreen.vue` and `MaterialsScreen.vue` rendered their own `<header>` plus an `<h1>` carrying
hand-copied `tabindex="-1" data-page-heading data-test="page-heading"` attributes. Both now render
`@/shell/PageHeader.vue` with `heading="Bank"` / `heading="Materials"` and their existing reload button
in its `#actions` slot, so the focus contract exists in one place and each reload control sits in the
shared `page-actions` group. `onReload` and every notice, empty-state and data region are unchanged;
the two screens' own `.bank-page-header` / `.materials-page-header` rules are deleted, with the vertical
rhythm now coming from the global `.screen` gap as on every other page.

Verified: the prepared QA test, which failed before the change for the right reason —
`npx vitest run src/account/__tests__/AccountPageHeaderContract.spec.ts` →
`Cannot call find on an empty DOMWrapper` at `pageHeader.find('[data-test="page-heading"]')`, i.e. no
`.page-head` existed, `Tests 2 failed (2)`; after the change `Tests 2 passed (2)`. The file is
unmodified (`sha256 23b1dd83…5531b`, the plan's `protected_test_hashes` value). Navigation focus itself
is evidenced in the browser by `npm run smoke:layout` step 9: *"destinations are operable by keyboard —
Enter opened Bank and focused its heading"*, and all six areas still report their own heading at three
viewports.

### AC 2 — maximum and Show all stay beside the results title (no change)

Confirmed against `DOMAIN_SPEC.md` §2.1.1 ("Place the changeable maximum displayed recipe count,
initially 250, and Show all beside the results table title … This placement is the confirmed Product
Owner decision"). `CraftingProfitScreen.vue` already renders them in `[data-test="opportunities-limit"]`
inside the results toolbar and was not touched. The placement and the two accessible groupings are
asserted, not merely reported:

- `npx vitest run src/crafting/__tests__/CraftingProfitScreen.spec.ts -t
  "keepsSearchAndFiltersInDisplayedResultsAndPlacesTheLimitInTheOpportunitiesToolbar"` →
  `Tests 1 passed | 45 skipped (46)`. It requires the limit's two controls *outside*
  `display-controls`, the `opportunities-limit` cell to be the toolbar child right after the
  Opportunities heading, and the `Displayed results` / `Calculation` group names.
- `npm run smoke:profit` step 6 (`displayControlState`) checks
  `limitInResultsRegion && limitBesideResultsTitle` — the limit inside `results-region` and sharing its
  parent with `#crafting-results-heading` — plus `legend === 'Displayed results'` and
  `calculationLegend === 'Calculation'` inside the one `aria-label`led `.controls-panel`. Step line:
  *"Displayed results filters open enabled; maximum and Show all are beside the results title — 5 of 8
  rows listed, maximum 250 rendered in the results toolbar"*. Step 13 re-checks the placement at 360px.

### AC 3 — one compact presentation, documented as such

F003 is confirmed by the code: `SelectedResultDetail.vue` and `SelectedDiscoveryDetail.vue` both passed
`selected-result-mode` unconditionally, so `CraftingResolution.vue`'s `!selectedResultMode` branch and
`ResolutionTreeNode.vue`'s `v-else` cost row were unreachable from either crafting page, while the prop
comment claimed "Discovery retains the separate cost figures". Removal is what §2.1.1 asks for
anyway — "Do not add a per-node technical disclosure for the cash, opportunity and effective values" —
and Discovery's one-batch basis is permissive there ("*may* continue to explain one output batch"),
with §2.2.2 asking Discovery for "the same actionable item statuses as Crafting Profit". So the branches
were removed rather than documented as dead:

- `ResolutionTreeNode.vue`: the `compactValue` prop, the `node-costs` / `node-cash-cost` /
  `node-opportunity-cost` / `node-effective-cost` row and its `.node__costs` / `.node__cost` rules are
  gone; `node-value` is now unconditional, with its source field and the diagnostics-only alternative
  named in a comment.
- `CraftingResolution.vue`: the `selectedResultMode` prop, the `resolution-basis` paragraph, the
  "What supplied the output" heading and the `resolution-tree-note` paragraph are gone. Root-sourcing
  suppression is now unconditional (`root.recipeId === requestedRecipeId` → no sentence), which is
  exactly what both callers already got; both component docs now describe the single presentation.
- The two detail components drop the attribute they passed.

Verified: `npx vitest run src/account src/crafting src/__tests__/App.spec.ts src/shell` →
`Test Files 17 passed (17) | Tests 246 passed (246)`; `npm run build` type-checks clean
(`vue-tsc --noEmit`, `✓ built in 502ms`). One focused test was added for the changed contract,
`showsOneValuePerRequirementAndNeverTheSeparateCostFigures` (four `node-value` elements, none of the
four removed hooks, no "Opportunity cost" text). No existing expectation was relaxed: the only edit to
an existing test is dropping the now-nonexistent prop from `CraftingResolution.spec.ts`'s `render`
helper, which no test passed explicitly.

### AC 4 — root-sourcing presentation reached in the browser

F004 is confirmed: both smoke fixtures built the tree root with the requested recipe id, so
`describeRootSourcing` produced nothing on screen. Both controlled fixtures now reach it, each through a
different one of its two renderable sentences, and the existing suppression assertions are kept for the
recipes whose roots do carry the requested recipe:

- `profit-browser-smoke.mjs`: the new `SUBSTITUTE_ROOT` makes recipe 7's answer carry a root with
  `recipeId: 4242`. New step 16 selects "Self-Referential Ingot", checks the last detail request was
  recipe 7's, and requires the sentence to read exactly *"Recipe 4242 was selected for this
  requirement, not the requested recipe 7."* and to sit above the requirements rather than inside the
  tree. Step line: *"a root produced by another recipe is named as such — "Recipe 4242 was selected for
  this requirement, not the requested recipe 7." above the requirements of "Self-Referential Ingot""*.
  Step 5c's absence assertion for recipe 1 is unchanged.
- `discovery-browser-smoke.mjs`: the new `STOCK_ROOT` answers candidate 3 with a root supplied from
  stock and no recipe at all. New step 9b selects "Break-even Discovery" and requires *"No recipe was
  selected for this requirement, so the output shown here was not crafted by the requested recipe 3."*,
  and ties the sentence to the supplied tree it describes (`From stock ×2`, `Value: 65s 43c`, one
  requirement). Step 8 keeps its suppression assertion for candidate 1.

Both fixtures remain this process's own responses — no backend, database or GW2 API is involved.

### AC 5 / AC 6 — checks run, and the superseded finding preserved

F002 is recorded as superseded by the confirmed Product Owner decision now in `DOMAIN_SPEC.md` §2.1.1;
`CraftingProfitScreen.vue` was not edited, and the existing component and browser assertions that pin
that placement are preserved and were run (AC 2 above). The absence assertions that STORY-WEB-024 left
in both scripts for the removed branches are kept as reintroduction guards, with their comments
corrected to say that the branch no longer exists rather than that the other page selects it.

**Commands run, all from `frontend/`, exact results:**

| Command | Result |
| --- | --- |
| `npx vitest run src/account/__tests__/AccountPageHeaderContract.spec.ts` (before) | `Tests 2 failed (2)` — no `.page-head` |
| `npx vitest run src/account/__tests__/AccountPageHeaderContract.spec.ts` (after) | `Test Files 1 passed (1) \| Tests 2 passed (2)` |
| `npx vitest run src/crafting/__tests__/CraftingProfitScreen.spec.ts -t "keepsSearch…Toolbar"` | `Tests 1 passed \| 45 skipped (46)` |
| `npx vitest run src/account src/crafting src/__tests__/App.spec.ts src/shell` | `Test Files 17 passed (17) \| Tests 246 passed (246)` |
| `npm run build` | `vue-tsc --noEmit` clean, `✓ built in 502ms` |
| `npm run smoke:profit` | **PASSED (35 steps)**, 11 calculations, 8 detail requests, no page error |
| `npm run smoke:discovery` | **PASSED (14 steps)**, 3 table and 2 detail requests, no off-origin request |
| `npm run smoke:layout` | **PASSED (13 steps)**, 79 API requests, all reads |

All three smoke runs were made on the same bundle (`dist/index.html built 2026-10-07T05:24:22.347Z`)
and against each script's own stub origin on `127.0.0.1`; each reported *"page served by this script"*
before touching a control.

**Coverage of the affected areas** (`npx vitest run --coverage src/account src/crafting src/ecto
src/sync src/shell src/__tests__`, `Tests 287 passed (287)`, read from `coverage-summary.json`):

| File | Lines | Branches |
| --- | --- | --- |
| `account/BankScreen.vue` | 100% | 95.65% |
| `account/MaterialsScreen.vue` | 100% | 100% |
| `shell/PageHeader.vue` | 100% | 100% |
| `crafting/CraftingResolution.vue` | 100% | 93.33% |
| `crafting/ResolutionTreeNode.vue` | 100% | 100% |
| `crafting/SelectedResultDetail.vue` | 100% | 94.87% |
| `crafting/SelectedDiscoveryDetail.vue` | 100% | 93.33% |
| `crafting/resolutionPresentation.ts` | 91.66% | 92.85% |

Every affected file is above the 90% target. The one uncovered line is
`resolutionPresentation.ts:36` — `describeRootSourcing`'s "The requested recipe N is the recipe selected
for this requirement." sentence, which its only caller now suppresses in every case. It was left in
place deliberately: the helper is a pure function whose contract covers all three identity relations,
and the suppression belongs in the one component that decides what to render (see F003 below).

### Remaining uncertainty

`npm run smoke:profit:live` and `npm run smoke:account` were not run — both need a running backend and
the maintainer's populated database (`TEST_STRATEGY.md` §31.4). `smoke:account` needs no change (it
drives `[data-test="bank-reload"]`, which is preserved). `profit-live-smoke.mjs` did need one: its
`checkCompactSummaries` read `node-effective-cost`, a hook the Profit page stopped rendering on
2026-09-30 in the maintainer's commit `5a6593b` (it was correct when STORY-WEB-016 wrote it, before that
refactor introduced the compact/non-compact split). It is retargeted to `node-value` with the
`Value: …` prefix, comparing the same supplied `effectiveCostCopper` values; that one line is the only
change in this story whose passing run could not be produced here (F001).

## Blockers

None.

## Follow-up Findings

F001: no gate or local command runs `smoke:profit:live` / `smoke:discovery:live`, so a presentation
change can leave them reading hooks the page no longer renders without anything failing —
`profit-live-smoke.mjs`'s `node-effective-cost` read survived seven days and three stories that way.
Its retarget to `node-value` in this story is itself unverified until someone runs it against a live
backend.

F002: `ResolutionTreeNode.vue` still carries scoped rules with no matching markup —
`.node__codes`, `.node__codes .status`, `.chip--unknown` and `.node__note` — left over from the
state/reason-code rows and per-node notes earlier stories removed. They are unrelated to the cost row
this story deleted.

F003: `describeRootSourcing`'s matching-identity sentence
(`resolutionPresentation.ts:36`) is now unreachable from its only caller, since
`CraftingResolution.vue` suppresses that case for both workflows. It is the one uncovered line in that
file; the alternative — deleting the branch — would make a direct caller passing a matching id produce
the opposite claim, so the decision between keeping the helper's complete contract and narrowing it is
worth taking deliberately rather than for coverage.
