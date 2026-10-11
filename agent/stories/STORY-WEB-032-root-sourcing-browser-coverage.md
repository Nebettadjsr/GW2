## Story ID

STORY-WEB-032

## Title

Verify root sourcing in browser resolution details

## Status

DONE

## Milestone

milestone-05

## Goal

Add browser coverage that verifies the frontend renders backend-provided root-sourcing details when a requested recipe is resolved through a different actual root recipe.

## Authoritative Source Documents / Sections

- Supplied Phase 5 roadmap excerpt: finish browser coverage and acceptance; render backend-provided resolution details.
- `docs/TARGET_ARCHITECTURE.md` §10.2, Resolution tree.
- `docs/TEST_STRATEGY.md` §12.2, Frontend verification levels.
- `agent/stories/STORY-WEB-024-repair-fresh-build-smoke-contracts.md`, Follow-up Finding F004.

## Context

The existing browser smoke fixtures use a tree whose root is the requested recipe, so they do not exercise the conditional root-sourcing presentation. The finding reports that `describeRootSourcing` has no browser coverage. Cover the alternate-root case with backend-provided detail and keep expected presentation independent of values derived from the page.

## Acceptance Criteria

1. A browser-level check supplies a requested recipe whose resolution tree has a different actual root recipe and verifies the corresponding root-sourcing description is rendered.
2. The check verifies the requested recipe identity and actual root sourcing remain distinct and consistent with the supplied backend detail.
3. Existing root-equals-requested-recipe behavior remains covered and does not display a misleading alternate-root description.

## Required Tests

- Add or update a controlled browser smoke fixture covering both the alternate-root and same-root cases, and run the affected smoke check.
- Run relevant frontend component tests and review affected coverage; report any unresolved coverage gap for the changed presentation path.

## Constraints

Do not recalculate or infer resolution sourcing in the frontend. Preserve the backend-provided resolution contract and existing product presentation. Keep the change scoped to browser coverage and directly required test hooks.

## Dependencies

None.

## Definition of Done

Both root identity cases are meaningfully verified in a browser, expected values are independently established by the fixture, affected coverage and unresolved quality gaps are reviewed, and the CI gate is green.

## Result

**What the tree already contained.** The alternate-root browser fixture and both assertions this
story asks for were already in `frontend/scripts/profit-browser-smoke.mjs`, committed by
STORY-WEB-028 (`23a6d90`): `SUBSTITUTE_ROOT = { recipeId: 7, row: 'Self-Referential Ingot',
rootRecipeId: 4_242 }` (line 271), `resolutionTree()` giving recipe 7 the root recipe 4242 while
every other recipe's root carries the requested recipe, step 16 reading the rendered sentence, and
step 5c requiring no sentence for the same-root case. So the remaining work was to verify both cases
in a browser with the shipped command, to make the verification legible in the run's own output, and
to review the affected coverage — not to re-add coverage that exists.

**What changed.** One file, `frontend/scripts/profit-browser-smoke.mjs` (9 insertions, 3 deletions),
both edits on the same-root suppression assertion in step 5c; no assertion, fixture value, expected
value or application behavior changed:

- its recorded step line now names the comparison it made (`… and no root-sourcing sentence for
  recipe 1, whose supplied root carries that same recipe`), so a reader of the passing run can tell
  the suppression case was checked. Previously that step line named only the tree, the request and
  the values, and the suppression assertion was invisible in the output.
- its failure message now reports the sentence that was found instead of asserting a cause
  (`A root carrying the requested recipe was given a sourcing sentence anyway: "…"`). The assertion
  fires whenever the hook is present at all, and the old wording described only one of the two ways
  that can happen.

**Evidence 1 — the shipped command, both cases (AC 1, 2, 3).** `cd frontend && npm run build` (exit
0) then `npm run smoke:profit > smoke.log 2>&1` — **exit 0, 35 steps, 5,294 bytes in 47 lines**. The
complete capture; the trailing `exit=0` line was appended by the capturing shell (`echo "exit=$?"`),
everything above it is the run's own stdout+stderr:

```
> gw2-tool-frontend@0.1.0 smoke:profit
> node scripts/profit-browser-smoke.mjs

Browser : C:/Program Files/Google/Chrome/Application/chrome.exe
Bundle  : dist/index.html built 2026-10-10T20:36:39.343Z; newest build input src/ecto/__tests__/renderedNumbers.spec.ts 2026-10-10T03:57:57.875Z
Page    : http://127.0.0.1:5176 (stub backend in this process)

  ok   page served by this script — 12 requests answered so far
  ok   results and detail side by side at 1440×900 — results 968px, detail 416px, no page overflow
  ok   detail region states its empty case
  ok   result selection is keyboard-operable — Enter selected "Deldrimor Steel Ingot", focus kept and visible
  ok   selection is marked by a glyph, a surface and aria-current — hidden label "Selected"
  ok   selection follows the recipe, not the row position — "Deldrimor Steel Ingot" still selected after re-sorting
  ok   detail separates calculation, quote, resolution and materials — 14 labels present in their own regions; no per-craft basis and no calculated shopping-list total
  ok   only the profit is marked after 15% TP fees; gross values unchanged — profit +14g 81s 40c, sell value 148g 14s 1c, materials 5g 18s 52c own and 9g 87s 65c bought
  ok   purchases are the counted crafts’ only, with quantities and supplied quotes — Mithril Ore ×60 Price / item: 1s 20c · Total: 74s 4c | Item #19701 ×5 Price / item: Unavailable · Total: Unavailable
  ok   no introductory sentence under the heading — heading "Crafting Profit"
  ok   costs read as costs, the revenue as a revenue and the quote as a single-item price — "Bought materials"/"Own materials" in rgb(255, 153, 144), "Total sell value" in rgb(136, 212, 161), profit row at weight 600
  ok   the backend tree is rendered whole, in order, in the compact presentation — 7 nodes, one detail request carrying scope and settings only, one effective value per requirement, no raw Resolver codes, and no root-sourcing sentence for recipe 1, whose supplied root carries that same recipe
  ok   the item with no price is named beside its own requirement — Not available on TP · GW2 Wiki: Charged Core
  ok   no generic row-state label or sentence on an unblocked result — none of detail-status, detail-status-explanation, detail-budget-context, detail-affected-item, detail-state-code, detail-diagnostics is rendered
  ok   child groups start collapsed and toggle one level from the keyboard — 2 groups, 0 open initially, "0" after Enter, 1 nested group(s) left collapsed
  ok   the whole detail stays reachable when it is taller than its box — 996px of detail in a 866px box, foot reached at y=793
  ok   the detail stays pinned beside a longer list at 1440×420 — top 16px after scrolling 120px past a 535px list, panel 388px
  ok   7 tree text/background pairs meet WCAG AA — lowest method chip at 8.27:1
  ok   Displayed results filters open enabled; maximum and Show all are beside the results title — 5 of 8 rows listed, maximum 250 rendered in the results toolbar
  ok   each filter reverses on its own, and Tab reaches them — 7 rows with only "not allowed" back on; filter-zero-craftable
  ok   zero matches names every restriction in force — Currently applied: search “no recipe is called this” · hiding recipes reported as not allowed · 
  ok   Show all reveals the matching set, not an unfiltered one — 1 → 5 rows, maximum suspended but kept at 1
  ok   display controls submitted no calculation and no detail request — 2 calculations and 1 details before and after
  ok   a click anywhere on a row selects its recipe — "Bolt of Damask" selected from a value cell, replacing "Deldrimor Steel Ingot"
  ok   a replacement calculation clears a removed selection — detail returned to its empty case
  ok   detail reflows below the results at 360×800 — region 334px holds 886px of columns, page does not scroll sideways
  ok   narrow layout keeps every row, usable controls and row selection — 8 rows, controls 302px, detail opened by row click
  ok   reflows at 200% text size — 1440px content in 1440px
  ok   required economic columns, no State column and no row-state label at 1440px — 7 columns over 8 rows, first total sell value 148g 14s 1c
  ok   required economic columns, no State column and no row-state label at 360px — 7 columns over 8 rows, first total sell value 148g 14s 1c
  ok   the counted crafts stay stated and the limit is not explained at 1440px — purchase cost 24g 0s 0c for the 4 crafts counted, Glob of Ectoplasm ×20
  ok   the counted crafts stay stated and the limit is not explained at 360px — purchase cost 24g 0s 0c for the 4 crafts counted, Glob of Ectoplasm ×20
  ok   a row blocked because buying is off carries no state label or explanation — "Elonian Leather Square" under "For all 0 crafts counted"
  ok   a root produced by another recipe is named as such — "Recipe 4242 was selected for this requirement, not the requested recipe 7." above the requirements of "Self-Referential Ingot"
  ok   read-only, no synchronization, no page error — 11 calculations, 8 detail requests, 10 selector reads

Crafting Profit hierarchy browser check PASSED (35 steps).
Every backend answer was produced by this script; no real data was read or written.
exit=0
```

Both root identity cases are named by that output. The alternate-root case (AC 1, 2): the step line
`a root produced by another recipe is named as such` quotes the rendered sentence
`"Recipe 4242 was selected for this requirement, not the requested recipe 7."`, and the step also
asserts the detail request it was rendered from carried `recipeId` 7 (`JSON.parse(substituteRequest
.body).recipeId === SUBSTITUTE_ROOT.recipeId`) and that the sentence sits above
`[data-test="resolution-tree"]`, not inside it. The expected sentence is built from the fixture's own
`SUBSTITUTE_ROOT` identities, never read back from the page. The same-root case (AC 3): the step line
`the backend tree is rendered whole …` now ends with `no root-sourcing sentence for recipe 1, whose
supplied root carries that same recipe`, from an assertion requiring both that
`[data-test="resolution-root-sourcing"]` is absent and that the detail text does not contain
`is the recipe selected for this requirement`.

**Evidence 2 — control: the alternate-root assertion fails when the root stops differing.**
Regenerate with one line changed, run it, delete it:

```
cd frontend/scripts && sed "s/rootRecipeId: 4_242 }/rootRecipeId: 7 }/" profit-browser-smoke.mjs > .tmp-control-same-root.mjs
cd .. && GW2_PROFIT_SMOKE_PORT=5186 node scripts/.tmp-control-same-root.mjs
rm scripts/.tmp-control-same-root.mjs
```

`diff --strip-trailing-cr` against the shipped file is exactly one line:

```
271c271
< const SUBSTITUTE_ROOT = { recipeId: 7, row: 'Self-Referential Ingot', rootRecipeId: 4_242 }
---
> const SUBSTITUTE_ROOT = { recipeId: 7, row: 'Self-Referential Ingot', rootRecipeId: 7 }
```

Its run reached step 16 and failed there — **exit 1 after 33 recorded steps**; the
first 33 lines are identical to the passing run's, so only the head and tail are quoted:

```
Browser : C:/Program Files/Google/Chrome/Application/chrome.exe
Bundle  : dist/index.html built 2026-10-10T20:36:39.343Z; newest build input src/ecto/__tests__/renderedNumbers.spec.ts 2026-10-10T03:57:57.875Z
Page    : http://127.0.0.1:5186 (stub backend in this process)

  ok   page served by this script — 12 requests answered so far
  …
  ok   a row blocked because buying is off carries no state label or explanation — "Elonian Leather Square" under "For all 0 crafts counted"

Crafting Profit hierarchy browser check FAILED after 33 step(s): The root's own producing recipe is not described by the supplied identities: {"text":null,"aboveTree":false}
exit=1
```

**Evidence 3 — control: the suppression assertion fails when a same-root row is given a sentence.**
Same procedure, changing which recipe gets the substitute root so step 5c's own row (recipe 1) is
answered with root recipe 4242:

```
cd frontend/scripts && sed "s/const SUBSTITUTE_ROOT = { recipeId: 7,/const SUBSTITUTE_ROOT = { recipeId: 1,/" profit-browser-smoke.mjs > .tmp-control-alt-root-for-step5c.mjs
cd .. && GW2_PROFIT_SMOKE_PORT=5187 node scripts/.tmp-control-alt-root-for-step5c.mjs
rm scripts/.tmp-control-alt-root-for-step5c.mjs
```

One-line diff (`271c271`: `recipeId: 7,` → `recipeId: 1,`). Complete capture — **exit 1 after 11
recorded steps**:

```
Browser : C:/Program Files/Google/Chrome/Application/chrome.exe
Bundle  : dist/index.html built 2026-10-10T20:36:39.343Z; newest build input src/ecto/__tests__/renderedNumbers.spec.ts 2026-10-10T03:57:57.875Z
Page    : http://127.0.0.1:5187 (stub backend in this process)

  ok   page served by this script — 12 requests answered so far
  ok   results and detail side by side at 1440×900 — results 968px, detail 416px, no page overflow
  ok   detail region states its empty case
  ok   result selection is keyboard-operable — Enter selected "Deldrimor Steel Ingot", focus kept and visible
  ok   selection is marked by a glyph, a surface and aria-current — hidden label "Selected"
  ok   selection follows the recipe, not the row position — "Deldrimor Steel Ingot" still selected after re-sorting
  ok   detail separates calculation, quote, resolution and materials — 14 labels present in their own regions; no per-craft basis and no calculated shopping-list total
  ok   only the profit is marked after 15% TP fees; gross values unchanged — profit +14g 81s 40c, sell value 148g 14s 1c, materials 5g 18s 52c own and 9g 87s 65c bought
  ok   purchases are the counted crafts’ only, with quantities and supplied quotes — Mithril Ore ×60 Price / item: 1s 20c · Total: 74s 4c | Item #19701 ×5 Price / item: Unavailable · Total: Unavailable
  ok   no introductory sentence under the heading — heading "Crafting Profit"
  ok   costs read as costs, the revenue as a revenue and the quote as a single-item price — "Bought materials"/"Own materials" in rgb(255, 153, 144), "Total sell value" in rgb(136, 212, 161), profit row at weight 600

Crafting Profit hierarchy browser check FAILED after 11 step(s): A root carrying the requested recipe was given a sourcing sentence anyway: "Recipe 4242 was selected for this requirement, not the requested recipe 1."
exit=1
```

Neither control copy is in the tree: both were deleted and `git status --short frontend/` lists only
`M frontend/scripts/profit-browser-smoke.mjs`. The passing run in Evidence 1 was made on the shipped
file, after the controls, with no throwaway copy present.

**Evidence 4 — component tests and coverage of the changed presentation path.**
`cd frontend && npx vitest run src/crafting/__tests__/CraftingResolution.spec.ts
src/crafting/__tests__/profitResolutionAssociation.spec.ts --coverage --coverage.reporter=text
--coverage.include='src/crafting/CraftingResolution.vue'
--coverage.include='src/crafting/resolutionPresentation.ts'` — complete capture:

```
 RUN  v5.0.1 C:/Users/Administrator/IdeaProjects/GW2/frontend
      Coverage enabled with v8

 Test Files  2 passed (2)
      Tests  47 passed (47)
   Start at  22:39:34
   Duration  4.08s (environment 63%, tests 16%, transform 15%, import 5%, worker 1%)

 % Coverage report from v8
-------------------|---------|----------|---------|---------|-------------------
File               | % Stmts | % Branch | % Funcs | % Lines | Uncovered Line #s
-------------------|---------|----------|---------|---------|-------------------
All files          |   93.54 |    93.18 |     100 |   96.29 |
 ...Resolution.vue |   94.73 |    93.33 |     100 |     100 | 32-41
 ...resentation.ts |   91.66 |    92.85 |     100 |   91.66 | 36
-------------------|---------|----------|---------|---------|-------------------

=============================== Coverage summary ===============================
Statements   : 93.54% ( 29/31 )
Branches     : 93.18% ( 41/44 )
Functions    : 100% ( 8/8 )
Lines        : 96.29% ( 26/27 )
================================================================================
exit=0
```

Coverage review: the two files carrying the root-sourcing decision are at 93.54% statement and
93.18% branch from these two suites alone, above the 90% target. `CraftingResolution.vue`'s lines
32–41 are the `tree`/`rootSourcing` computed bodies, reported as partially covered because the
`detail === null` and `requestedRecipeId === null` guards are not both exercised by these two files;
`CraftingResolution.spec.ts` covers the phase matrix that reaches them. The one uncovered line,
`resolutionPresentation.ts:36`, is the equal-identity return of `describeRootSourcing` — unreachable
through either page, because `CraftingResolution.vue` returns `null` for that case before calling it;
recorded as F001 rather than covered, since a test for it would assert on a branch no caller can
reach. No unresolved quality gap otherwise.

**Scope and constraints.** No frontend source file changed, so the backend-provided resolution
contract and the rendered presentation are untouched and nothing in the frontend recalculates
sourcing. Checked against the whole tree rather than the story's own diff: `git status --short` holds
no other modified product file (only `agent/` documents, logs and the QA plan), so no further suite
covers this commit's content. Layers the CI gate cannot run are unaffected; `smoke:discovery` covers
the third root case (no recipe at the root) and was not touched or re-run. Verification used ports
5176, 5186 and 5187; each script closed its own stub and browser, and all three ports refuse
connections afterwards (`curl` exit 7 / `000`).

## Follow-up Findings

F001: `resolutionPresentation.ts`'s `describeRootSourcing` has an equal-identity branch
(`The requested recipe N is the recipe selected for this requirement.`) that no caller can reach —
`CraftingResolution.vue`'s `rootSourcing` returns `null` whenever `root.recipeId === requestedRecipeId`,
and that sentence appears nowhere else in `frontend/src`. It is the only uncovered line in the
root-sourcing path and both browser checks assert its wording is absent from the page.

## Follow-up Findings Disposition

F001: DISMISSED — Optional cleanup of an unreachable helper branch, not a verified app defect or unmet Phase 5 requirement. Current `frontend/src/crafting/CraftingResolution.vue` suppresses equal-identity roots before calling `describeRootSourcing`; the only call site is that component. `frontend/src/crafting/resolutionPresentation.ts` retains the unused sentence. This story's Result verifies same-root suppression and alternate-root presentation in the browser and records the coverage limitation. No additional implementation or coverage story is required by `docs/TARGET_ARCHITECTURE.md` §10.2 or the supplied Phase 5 browser acceptance criteria.

## Blockers

None.
