## Story ID

STORY-WEB-019

## Title

Align layout browser smoke navigation coverage with current destinations

## Status

UNFINISHED

## Milestone

milestone-05

## Goal

Keep the layout browser smoke check's keyboard-navigation assertions and destination coverage aligned with the application's current navigation destinations.

## Authoritative Source Documents / Sections

- `agent/stories/STORY-WEB-017-layout-smoke-intro-contrast-sample.md`, Follow-up Findings Disposition (F001, F002)
- `docs/TEST_STRATEGY.md` ?12

## Context

STORY-WEB-017 records that the layout smoke check expects four navigation stops even though the application exposes six destinations, and that its `AREAS` list omits Crafting Discovery and Ectoplasm Salvage. The smoke check should exercise the current navigation destinations without changing the separate contrast-sampling policy.

## Acceptance Criteria

1. The keyboard-navigation assertion verifies the current destination set and does not fail because the expected stop count is stale.
2. The layout smoke check's destination coverage includes Crafting Discovery and Ectoplasm Salvage as well as the destinations it already covers.
3. Existing layout and contrast assertions remain active, with useful failures when a destination is missing.
4. Record the updated destination coverage and verification evidence in Result.

## Required Tests

- Run the layout browser smoke check in Chrome and verify its keyboard-navigation and destination coverage assertions.

## Constraints

- Keep this scoped to the browser smoke check; do not change product navigation or layout behavior.
- Do not broaden which contrast selector pairs are required; retain the existing required page-intro pair.

## Dependencies

None.

## Definition of Done

Acceptance criteria are met, the named browser smoke check passes, and the Result records the evidence and any limitations.

## Result

**DONE. One file changed: `frontend/scripts/layout-browser-smoke.mjs`.** No product code, component,
style, token, page content, route, backend file or contract was touched: navigation and layout behavior
are exactly as they were, and `shell/destinations.ts` is unchanged.

### AC 1 — the keyboard assertion is derived from the rendered navigation, not from a literal

New `renderedDestinations(page)` reads the `data-test` names of `[data-test="screen-nav"]
.site-nav__link` in document order. Step 5 walks `navStopNames.length + 1` stops — the skip link plus
one per rendered destination instead of a hard-coded six — and asserts that the stops after the skip
link are *exactly* that list, in that order (`walk.slice(1)` compared element by element, rather than
the old `filter(...).length === 4`, so an interleaved non-navigation stop is also a failure and is
named). Nothing in the file now states how many destinations exist, so the shell adding or removing one
cannot make this step stale again. The old literals were the defect WEB-017 F001 reported: with six
destinations, `focusWalk(page, 6)` plus `navStops.length === 4` failed on the current tree and blocked
every step after it.

Measured: `skip link → nav-crafting → nav-discovery → nav-ecto → nav-synchronization → nav-bank →
nav-materials` — seven stops, all with a visible focus outline, on the unmodified script.

### AC 2 — Crafting Discovery and Ectoplasm Salvage are covered

`AREAS` now holds all six destinations in the shell's own order, with the two that were missing:
`discovery` (heading `Crafting Discovery`, ready `[data-test="discovery-table"]`) and `ecto` (heading
`Ectoplasm Salvage`, ready `[data-test="ecto-scenario-table"]`). Both therefore get the full per-URL
treatment every other area already had: reached by their own URL with a reload, page heading, document
title, `aria-current` destination marking, and the page-level horizontal-overflow check at all three
viewport widths. Step 2's record line is now derived (`all ${AREAS.length} areas reflow at …`), so it
can no longer claim "all four areas" while covering a different number.

The two pages need answers to render, so `answerApi` gained `POST /api/crafting/discovery` and
`GET /api/ecto/salvage`. The Discovery response reuses the existing `profitRows()` fixture — Discovery's
rows are the same transport shape as Profit's, and what this check needs from that route is a rendered
comparison list, not a second set of candidates — with Discovery's own echoed scope, inventory
character and settings. The Ectoplasm answer is a new compact fixture: `resultAvailable: true`, all four
scenarios (a loss, a gain and a break-even, so that page's money treatments are on screen too) and a
fee percentage that is deliberately not 15, so a page stating the fee from its own knowledge instead of
from the answer would print the wrong number. Both are answered from this process; the stub still binds
`127.0.0.1` and the run still asserts it served the page itself.

### AC 3 — existing assertions stay active, and a missing destination fails usefully

Every existing assertion is unchanged: the 360px table-region check, the 200% text-size reflow, Enter on
a focused destination moving focus to the new heading, the whole of `measureContrast` with its sixteen
selectors, five probed status tones, large-text rule, 4.5/3.0 thresholds and `samples.length >= 15`
floor, the required `page intro` pair with its negative control, reduced motion, and the
no-synchronization/no-foreign-call/no-page-error step. The required contrast pairs were **not** widened —
`page intro` remains the only one required by name (WEB-017 F003 stays out of scope). The six former
`AREAS[0]` references became `PROFIT_AREA = areaOf('crafting')`, following the id-resolution rule
WEB-017 established for `INTRO_AREA`, so extending the list cannot silently retarget a step.

The durable part of AC 3 is a new step 1: the rendered destinations and `AREAS` must agree, both ways.
A destination the navigation offers with no `AREAS` entry fails by name ("add an AREAS entry naming its
heading and its ready selector, or that destination stays unchecked"), and an `AREAS` entry the
navigation no longer offers fails by name too. That is what makes WEB-017 F002 unrepeatable: a
destination cannot be added to the shell and silently go unchecked here.

Both branches were proven to fail, by two temporary local edits that were reverted (the final file
contains neither):

- removing the `ecto` entry → `FAILED after 1 step(s): The navigation offers ecto, which this check does
  not cover — add an AREAS entry naming its heading and its ready selector, or that destination stays
  unchecked.`
- adding a `nowhere` entry → `FAILED after 1 step(s): This check covers nowhere, which the navigation no
  longer offers: crafting, discovery, ecto, synchronization, bank, materials.`

### AC 4 — verification evidence

Run from `frontend/` on `GW2_LAYOUT_SMOKE_PORT=5187` (5175, 5186 and 5187 were probed with `curl` first
and had no listener; 5187 was probed again afterwards and is released):

- `npm run build` — clean, 102 modules, `✓ built in 414ms` (the script drives `dist/`).
- `GW2_LAYOUT_SMOKE_PORT=5187 npm run smoke:layout` — **PASSED (13 steps)**, real Chrome
  (`C:/Program Files/Google/Chrome/Application/chrome.exe`), every backend answer produced by the
  script, `57 API requests, all reads`. The decisive lines:
  - `ok  all 6 rendered destinations are covered by this check — crafting, discovery, ecto,
    synchronization, bank, materials`
  - `ok  all 6 areas reflow at desktop 1440×900 / tablet 768×1024 / phone 360×800 — no page-level
    horizontal scrolling` (three lines)
  - `ok  keyboard focus order and visible focus — skip link → nav-crafting → nav-discovery → nav-ecto →
    nav-synchronization → nav-bank → nav-materials`
  - `ok  20 rendered text/background pairs meet WCAG AA — lowest primary action at 4.92:1; page intro
    measured on synchronization at 9.86:1` — unchanged from WEB-017, as intended.
- The final run above was made on the restored file; `git diff --stat` reports one changed file,
  `frontend/scripts/layout-browser-smoke.mjs`.

**Limitations.** This is a stubbed structural check: it evidences navigation, layout, focus and computed
contrast on the treatments this script's fixtures put on screen, in one browser, with no real data and
no performance claim (`TARGET_ARCHITECTURE.md` 33). The two added pages are exercised for their
structure only — nothing here verifies a Discovery or Ectoplasm domain value, which is what
`smoke:discovery`, `smoke:ecto` and their live variants are for. Unlike WEB-017's run, no temporary edit
was needed to reach the later steps. No Vitest, Java or TestFX suite was run, because no source file
changed and this script is not part of the automated gate; GitHub Actions remains the full-regression
gate (`TEST_STRATEGY.md` §20/§36).

### Documentation

None required. `CURRENT_ARCHITECTURE.md`'s `scripts/` tree entry and its `npm run smoke:layout` row, and
`TEST_STRATEGY.md` §12's layout row, describe this script as checking navigation, viewports, zoom,
keyboard focus, contrast and "per-destination URLs and titles" without naming a count or a destination
set — still exactly what it does, so no file's owned information changed.

## Follow-up Findings

F001: `layout-browser-smoke.mjs`'s shared `profitRows()` fixture supplies no `totalSellValueCopper`, so
the Profit table's "Total sell value" column — and now the Discovery table's, which reuses the same
fixture — renders the no-value placeholder on every row throughout this check. That column's money
treatment is therefore never laid out or contrast-measured, while the rest of the fixture deliberately
covers a gain, a loss and null cases. Adding the field (a value that is deliberately not the product of
its parts, as the Discovery and Profit smoke fixtures already do) would close the gap without touching
any assertion.

## Blockers

None.
