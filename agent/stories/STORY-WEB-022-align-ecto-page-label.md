## Story ID

STORY-WEB-022

## Title

Align the Ecto Salvage page label and browser checks

## Status

DONE

## Milestone

milestone-05

## Goal

Make the Ecto Salvage screen heading, navigation label, document title, and browser smoke expectations agree after the recorded screen rename.

## Authoritative Source Documents / Sections

- `agent/stories/STORY-PERF-002-phase-five-crafting-profit-page-budget.md` — Follow-up Findings F002.
- `docs/TEST_STRATEGY.md` §12.2 — browser checks for page titles and destinations.
- `docs/TARGET_ARCHITECTURE.md` §12 — frontend presentation responsibilities.

## Context

STORY-PERF-002 records that the screen heading changed to `Ecto Salvage`, while the destination label and document title still say `Ectoplasm Salvage`. The Ecto and layout browser smoke checks still expect the older heading. This inconsistent presentation and the stale assertions need one focused correction.

## Acceptance Criteria

1. The Ecto Salvage heading, navigation destination label, and document title use the recorded screen label consistently.
2. The Ecto and layout browser smoke checks assert the consistent label and no longer expect the old heading.
3. Other destination labels and Ecto result behavior remain intact.
4. Record the browser verification evidence and any limitation in Result.

## Required Tests

- Run `npm run smoke:ecto` and `npm run smoke:layout` in a real browser against the current frontend build; record if either script uses a different command name.
- Run focused frontend component tests for the Ecto heading or destination label when such tests exist; add a focused assertion if needed to protect the chosen label.

## Constraints

- Keep this change in frontend presentation and browser checks; do not alter Ectoplasm domain calculations or backend API contracts.
- Follow `docs/TEST_STRATEGY.md` §12.2 for browser smoke evidence.

## Dependencies

None.

## Definition of Done

Acceptance criteria are met, the named focused checks pass, the browser smoke evidence is recorded, and the GitHub CI gate is green.

## Result

`Ecto Salvage` — the name the screen's own `PageHeader` already carried — is the one label, and the
places that said `Ectoplasm Salvage` follow it. The outstanding deficiency was not the label but the
evidence: neither checked-in browser script reached its Ecto label assertion, so the aligned label was
never actually read on a rendered page by a script this repository ships. Both scripts now reach those
assertions and both pass.

### What the tree already carried, and what this pass did

The one-line label edit (`frontend/src/shell/destinations.ts:28`, `label: 'Ecto Salvage'`, which feeds
both the navigation link and `documentTitleOf()`) and the layout check's `Ecto Salvage` heading
expectation were swept into the maintainer's commit `72fc59f`. The maintainer's `4c2fe50` then removed
both blockers this story had recorded: it rewrote `frontend/scripts/ecto-browser-smoke.mjs` against the
locally calculating screen, and replaced the layout check's stale `ecto` readiness selector with
`.panel:has(.salvage-calculation):has(.account-luck)`. This pass verified that on the current tree,
made the label evidence visible in each script's own output, and proved the assertions are load-bearing.

### AC 1 — heading, navigation label and document title agree

`destinations.ts:28` is `label: 'Ecto Salvage'`; the `h1` is `Ecto Salvage`
(`EctoSalvageScreen.vue:458`'s `PageHeader`). All three strings were read off a real rendered page in
the runs below, not off the source.

### AC 2 — both shipped browser checks assert the label, and reach it

- `frontend/scripts/ecto-browser-smoke.mjs:106/107` — `Ecto Salvage · GW2 Crafting Tool` and
  `Ecto Salvage`, in step 1, after the waits on `.salvage-calculation` and `.account-luck`.
- `frontend/scripts/layout-browser-smoke.mjs:46` — the `ecto` area's `heading` is `Ecto Salvage`; the
  per-area loop compares it against `[data-page-heading]` (line 503) and derives the document-title
  expectation from the same field (line 507), for every area at every viewport.

Two edits were made here, both to the step output rather than to any assertion: the ecto check's step 1
is now named `destination, the "Ecto Salvage" title and heading, and three current input reads`, and the
layout check's per-viewport line now reports the headings it compared. A step line that only claimed
"reflow" is what made it impossible to tell from a passing run whether the label had been read at all.

**STORY-WEB-021's work was left untouched.** The `ecto` readiness selector is still the maintainer's
style-class selector, not a `data-test` hook — replacing it is that story's AC 1/AC 2 — and no
result-scenario, loading, failure or reload assertion in either script was added, moved or removed.

### AC 3 — other labels and the Ecto results are intact

`git diff` on this story's tree shows changes to exactly two files, both under `frontend/scripts/`: no
component, no destination label, no calculation and no API contract was touched. The other five
destination labels are byte-identical, and the 12 `EctoSalvageScreen` tests over the locally computed
figures pass unchanged.

### AC 4 — browser evidence, taken from the shipped scripts

Real Chrome (`C:/Program Files/Google/Chrome/Application/chrome.exe`), the built bundle, stub origins in
each script's own process on `127.0.0.1`. Neither script runs in CI (`TEST_STRATEGY.md` §12.2).

- `npm run smoke:ecto` (port 5192) — **PASSED (6 steps)**, step 1:
  `ok   destination, the "Ecto Salvage" title and heading, and three current input reads`.
- `npm run smoke:layout` (port 5193) — **PASSED (13 steps)**, with the label named in all three
  per-viewport lines, e.g.
  `ok   all 6 areas are named, marked and reflow at phone 360×800 — no page-level horizontal scrolling; heading and title verified for crafting "Crafting Profit", discovery "Crafting Discovery", ecto "Ecto Salvage", synchronization "Synchronization", bank "Bank", materials "Materials"`
  — i.e. `#/ecto` rendered `Ecto Salvage`, titled `Ecto Salvage · GW2 Crafting Tool`, with `nav-ecto`
  marked current, at 1440×900, 768×1024 and 360×800.

**Controls, run from throwaway copies so no shipped file was edited.** `scripts/.tmp-control-layout-label.mjs`
and `scripts/.tmp-control-ecto-label.mjs` differ from the shipped scripts only in expecting the old
label (`diff` output: one line in the layout copy, two in the ecto copy):

- layout control (port 5190) — `FAILED after 2 step(s): ecto at desktop 1440×900: the page heading did
  not read "Ectoplasm Salvage".`
- ecto control (port 5191) — `FAILED after 0 step(s): The document title is stale.`

Each fails on the label assertion itself and nowhere else, which is what establishes that those
assertions execute and would fail if the alignment were removed. Both copies were deleted; `git status`
confirms nothing untracked remains under `frontend/`.

**Limitation:** none outstanding for this story. Both checks are local-only by design — GitHub Actions
does not run browser smoke (`TEST_STRATEGY.md` §12.2) — so the evidence above is the gate for this
criterion and has to be re-taken by hand when the screen changes.

### Focused component tests

`frontend/src/__tests__/App.spec.ts`'s Ecto destination test protects the label without a browser: the
title expectation at line 191, the heading at 194, and at line 198
`expect(open.find('[data-test="nav-ecto"]').text()).toBe(heading.text())` — the navigation label and the
page's own `h1` must be the same string, which is exactly the divergence that occurred. It cannot pass
vacuously: a missing `nav-ecto` yields `''`, which is not the non-empty heading.

**Commands run and their real output** (from `frontend/`):

- `npm run build` (`vue-tsc --noEmit` then `vite build`): clean, `✓ built in 750ms`.
- `npx vitest run src/__tests__/App.spec.ts src/ecto/__tests__/EctoSalvageScreen.spec.ts`:
  **`Test Files 2 passed (2) | Tests 27 passed (27)`**; of those,
  `npx vitest run src/ecto/__tests__/EctoSalvageScreen.spec.ts` alone is **`Tests 12 passed (12)`**.
- The four browser runs quoted under AC 4.

No broader suite was run: this pass changed two step-output strings in two local browser scripts, and
`git status` shows no other product file in the tree for the commit to carry. GitHub Actions remains the
full-regression gate (`TEST_STRATEGY.md` §20/§36).

No documentation update is due. `CURRENT_ARCHITECTURE.md` §5.11 describes the browser page under the
heading **Ecto Salvage page** and describes what it loads, never quoting a navigation label or document
title; no other doc owns those literals. This pass changed only two step-output strings in two local
browser scripts, which no doc describes.

## Blockers

None.

## Follow-up Findings Disposition

F001: ALREADY COVERED — STORY-WEB-021 has been revised to test the current frontend-owned Ectoplasm calculation and its relevant data/result states. It must not depend on or recreate the obsolete backend-calculated result states.

F002: FOLLOW-UP CLEANUP — The Product Owner confirmed that the frontend-owned Ectoplasm calculation is intentional and canonical. The former backend Ectoplasm calculation route must not be restored. Unused `frontend/src/api/ectoApi.ts`, `frontend/src/ecto/useEctoSalvage.ts`, obsolete `EctoSalvage*` API interfaces, and equivalent orphaned code belonging exclusively to the removed backend flow may be removed as cleanup.

STORY-WEB-023 does not represent the current architecture and must not be used to replace the local Ectoplasm calculator or reintroduce a backend-owned Ectoplasm calculation.
## Follow-up Findings Disposition

F001: ALREADY COVERED ? STORY-WEB-021 AC 3 now checks the backend-calculated states after STORY-WEB-023.
F002: FOLLOW-UP STORY ? STORY-WEB-023 replaces the local calculator and reconciles unused Ectoplasm API client modules and types.