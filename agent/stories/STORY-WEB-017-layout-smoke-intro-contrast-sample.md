## Story ID

STORY-WEB-017

## Title

Keep browser layout smoke contrast sampling valid after Profit intro removal

## Status

DONE

## Milestone

milestone-05

## Goal

Restore the browser layout smoke script's page-intro contrast sample so the removal of the Crafting Profit introduction does not silently reduce its measured contrast coverage.

## Authoritative Source Documents / Sections

- agent/stories/STORY-WEB-015-profit-purchase-and-blocking-details.md, Follow-up Findings F001: the `page intro` sample on `AREAS[0]` no longer finds an element after the Profit intro was removed.
- Supplied docs/ROADMAP.md Phase 5: frontend tests focused on rendering, interaction and state.
- docs/TEST_STRATEGY.md section 12: frontend rendering and interaction test focus.

## Context

The layout browser smoke currently samples contrast on Crafting Profit (`AREAS[0]`), whose introduction was removed under WEB-015. The finding states that this sample now silently contributes no measurement while the existing sample-count floor still passes. Preserve the intended sample by targeting an existing page that has an introduction, rather than changing product presentation or weakening the smoke check.

## Acceptance Criteria

1. The `page intro` contrast sample targets an existing rendered introduction on a page that retains one.
2. The smoke check fails clearly if that target is absent or cannot be measured; it must not silently count a missing sample as successful coverage.
3. The change preserves the existing contrast sampling behavior and does not reintroduce the removed Crafting Profit introduction.
4. Record the focused verification and any limitations in the story Result.

## Required Tests

- Run the layout browser smoke and confirm the page-intro sample is measured on its new target.
- Add or adjust a focused check proving a missing sample target is reported as a failure rather than silently omitted.

## Constraints

- Keep this limited to the identified browser smoke coverage gap; do not broaden layout or contrast policy.
- Do not alter product-facing page content to satisfy the smoke script.

## Dependencies

Completed STORY-WEB-015.

## Definition of Done

The layout smoke measures a valid page-intro contrast pair, missing targets cannot silently pass, and the focused verification is recorded.

## Result

**DONE. One file changed: `frontend/scripts/layout-browser-smoke.mjs`.** No product code, component,
style, token, page content, backend file or contract was touched, and the removed Crafting Profit
introduction was not reintroduced — `CraftingProfitScreen.vue` still passes no `intro` to `PageHeader`.

### AC 1 — the pair is measured where an introduction is still rendered

`measureContrast` gained one optional argument, `{ only }`, which restricts the set to the named pairs.
Step 6 now measures the Crafting Profit page exactly as before and then opens the area named by the new
`INTRO_AREA` constant — `AREAS.find(area => area.id === 'synchronization')`, resolved by id rather than
by index so reordering `AREAS` cannot silently retarget it — and takes the `page intro` pair there, on
`SyncScreen.vue`'s own introductory sentence (`[data-test="page-intro"]`, `.page-head__intro`). The two
sets are concatenated, so the existing WCAG comparison, the failure report and the `worst` line all run
over one combined set as before. The sample's definition stays in the single shared list next to the
other fifteen; only where it is taken changed.

Measured: **`page intro` on synchronization at 9.86:1**, and the run's total rose from 19 to **20**
measured pairs — the one pair WEB-015 F001 reported as lost.

### AC 2 — a missing target fails, it can no longer be skipped

`sample()` still skips a null element, because most of the sixteen selectors are page-dependent. What is
new is `assertMeasured(samples, required, where)`, called with `[INTRO_SAMPLE]` immediately after the
measurement and before the sample-count floor. It fails the run when a required name is absent from the
returned set, when no `sample` call produced it (a renamed or mistyped sample name, which the in-browser
side cannot detect), or when its ratio is not finite — the three ways a pair can be unmeasured. The
message names the pair and says it cannot count as coverage.

A new step 7 is the negative control, and it proves the guard rather than asserting the guard's own
wording: it removes the rendered `[data-test="page-intro"]` from the loaded Synchronization page
(asserting the removal took effect first, so the control cannot pass vacuously), re-measures with the
same `only` filter, requires the returned set to be empty, and then requires `assertMeasured` to throw
with the pair's name in the message. Had this guard existed under WEB-015, the removal of the
introduction would have failed this script instead of quietly reducing its coverage. Step 8 reloads
Crafting Profit, so the removed element cannot leak into the reduced-motion or request assertions.

### AC 3 — existing sampling behavior preserved

The contrast mathematics, the sixteen selectors, the five probed status tones, the large-text rule, the
4.5/3.0 thresholds, the `samples.length >= 15` floor and the three display filters switched off before
measuring are all unchanged; `only` defaults to `null`, so the Crafting Profit pass measures precisely
what it measured before. Steps 1–5 are untouched. The floor was deliberately **not** raised and no other
pair was made required — that would be the broader contrast policy this story's constraints exclude (see
F003).

### AC 4 — verification and limitations

Run from `frontend/`, on `GW2_LAYOUT_SMOKE_PORT=5186` (5175 and 5186 were both probed with `curl` first
and had no listener; the stub binds `127.0.0.1` and the run asserts it served the page itself):

- `npm run build` — clean, 102 modules (the script drives `dist/`).
- `GW2_LAYOUT_SMOKE_PORT=5186 npm run smoke:layout` — **PASSED (12 steps)**, real Chrome, every backend
  answer produced by the script. The two decisive lines:
  - `ok  20 rendered text/background pairs meet WCAG AA — lowest primary action at 4.92:1; page intro
    measured on synchronization at 9.86:1`
  - `ok  a missing contrast target fails the check — control: no measurable element for page intro. That
    contrast pair was not measured, so it cannot count as coverage — point the sample at a rendered
    element.`

**Limitation, stated rather than worked around: that run required a temporary local edit to an unrelated
step, which was reverted and is not part of this change.** Step 4 (keyboard focus order) fails on the
current tree for a reason that predates this story and has nothing to do with contrast: it tabs six times
and demands exactly four navigation stops, while `shell/destinations.ts` now has six destinations, so the
first attempt ended at `Expected the four destinations in the focus order, saw nav-crafting,
nav-discovery, nav-ecto, nav-synchronization, nav-bank`. Nothing downstream of step 4 — including the
contrast step this story is about — can run until that is fixed. To obtain the evidence above, `6`/`4`
were temporarily raised to `7`/`6` in that step, the script was run, and both literals were then restored;
`git diff` confirms the committed change contains no edit to `focusWalk`'s call or to the `navStops`
assertion. That defect is recorded as F001 for planner disposition, not fixed here.

Further limits: contrast is measured on the treatments this stub's fixtures put on screen, at the
default viewport, in one browser (Chrome), with no real data — the script's standing constraints
(TARGET_ARCHITECTURE 33). No Vitest, Java or TestFX suite was run, because no source file changed; the
full regression remains the GitHub Actions gate (TEST_STRATEGY 20/36). No performance and no Phase 5
closure claim is made.

### Documentation

None required. `CURRENT_ARCHITECTURE.md` (§ the `scripts/` tree entry and the `npm run smoke:layout`
row) and `TEST_STRATEGY.md` §12's layout row describe this script as checking "computed text/background
contrast" without naming a page or a pair, which is still exactly what it does, so neither file's owned
information changed.

## Follow-up Findings

F001: `frontend/scripts/layout-browser-smoke.mjs` step 4 asserts `navStops.length === 4` over a
six-step `focusWalk`, but `shell/destinations.ts` has had six destinations since Crafting Discovery and
Ectoplasm Salvage were added, so `npm run smoke:layout` fails there on the current tree and every step
after it — contrast, reduced motion and the no-synchronization assertion — never runs. Deriving the
expected count from the rendered navigation would keep it correct as destinations change.

F002: the same script's `AREAS` still lists four of the six destinations, so Crafting Discovery and
Ectoplasm Salvage get no per-URL heading/title/`aria-current` check and no reflow check at the three
viewport widths, while step 1's record line claims "all four areas".

F003: only the `page intro` pair is required by name; the other fifteen selectors in `measureContrast`
are still skipped silently when absent, and the `samples.length >= 15` floor stays 4 below the 19 pairs
the Crafting Profit pass actually takes, so the same class of silent loss remains possible for
`.column-note`, `.recipe-ids`, `.status--*` and the rest. Deciding which of those must always be
measurable (and raising the floor to match) is a contrast-policy question this story's constraints kept
out of scope.

## Blockers

None.
