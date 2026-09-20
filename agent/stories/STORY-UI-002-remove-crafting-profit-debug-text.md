## Story ID

STORY-UI-002

## Title

Remove technical status text below the Crafting Profit title

## Status

DONE

## Milestone

milestone-01

## Goal

Remove the PO-reported developer counters from normal Crafting Profit presentation while retaining user-facing errors and useful internal diagnostics.

## Authoritative Source Documents / Sections

- Supplied Phase 1 debug-instrumentation cleanup item.
- docs/KNOWN_PROBLEMS.md §7.7.
- agent/stories/STORY-UI-001-javafx-verification-capability.md (verification capability).
- PO request: agent/product-owner-requests/remove-crafting-profit-debug-status-text.md.

## Context

The reported status line is distinct from the previously removed planner console debug output. Its current implementation must be verified by the implementation agent; no source inspection occurred during planning.

## Acceptance Criteria

- Remove the reported loaded-recipe/row/missing-data diagnostic line from the normal Crafting Profit view, including after refresh.
- Preserve genuine user-facing error messages and useful existing internal logs; no need to introduce new logging infrastructure.
- Retain the title and remove empty layout space left by the line.
- Record verification and update KNOWN_PROBLEMS.md §7.7 accurately.

## Required Tests

- Use STORY-UI-001's real-view capability to reproduce the visible line before removal and verify its absence after load/refresh, with the title retained. Verify a supported error state still displays its user-facing error.
- Run relevant UI regressions; record actual outcomes and any manual layout check.

## Constraints

Crafting Profit only; no calculation changes, broad UI redesign or removal of user-relevant error/blocked-result information.

## Dependencies

STORY-UI-001 (DONE).

## Definition of Done

Reported line removed, title/layout and error behavior verified, relevant regressions passing, result and problem status updated.

## Result

Reproduced the reported line first: `CraftingProfitView`'s reload success handler
(`Runnable reloadTable`, background thread) built `String dbg = "rows=" + totalRows + " missingLines="
+ ... + " zeroBuyPrice=" + ...` from a loop over `controller.getResultByRecipeId(...)`/`controller.tpQuote(...)`,
then set the shared `statusLabel` (directly below the "Crafting Profit Analyzer" title) to
`"✅ Loaded " + visibleRows.size() + " recipes.  |  " + dbg` on every successful load, including
every manual "Refresh" and the 90s auto-refresh cycle — matching the PO's quoted example exactly.

Fix: kept the same diagnostic computation (still useful for development, per the constraint not to
lose it) but routed it to `System.out.println("Crafting Profit reload: rows=..." + ...)` instead of
the UI, and changed the success-path `statusLabel.setText(...)` to `""`. The PO's request and this
story's own acceptance text both treat "Loaded N recipes" itself as part of the reported
developer-facing line (not just the counters), so the whole line is gone on success, not paraphrased.
The title `Label` and the shared `statusLabel` `Label` are otherwise unchanged — no separate
fixed-height container existed for the removed text, so no extra blank space is left behind (the
`Label` simply renders empty). Genuine user-facing messages on the same `statusLabel` — `"❌ DB load
failed: ..."`, `"⚠️ Auto-refresh failed: ..."`, `"✅ TP refreshed."`, `"❌ TP refresh failed: ..."` —
were not touched. Gave `statusLabel` a stable id (`craftingProfitStatusLabel`) so tests can look it
up directly instead of matching on its (now frequently empty) text.

Verification (STORY-UI-001 real-view capability, `uiverify` package):
- `CraftingProfitViewSmokeIT` (existing real-view smoke test, extended): after the view's own
  initial load and again after an explicit "Refresh" click, asserts the title is still present and
  `statusLabel`'s text contains none of `rows=`, `missingLines=`, `missingTp=`, `zeroBuyPrice=`, or
  the word "loaded".
- `CraftingProfitViewErrorStatusIT` (new): seeds one recipe so `tpRepo.loadTpQuotes` actually runs,
  then drops the `tp_prices` table out from under the fixture schema before the view's own initial
  reload, forcing a real `SQLException` out of `CraftingProfitController.reload`; asserts the title
  stays and `statusLabel` shows the real `"❌ DB load failed: ..."` message from `CraftingProfitView`'s
  existing catch block — proving error reporting was not collateral damage.

Out of scope, left unchanged and flagged for a possible separate PO/story: the same `statusLabel` is
also briefly set to a distinct developer-facing string, `"MaxBuy UI=" + fieldText + " => " + copper +
" copper"`, synchronously at the very start of every reload (immediately after "Loading from DB...",
which it overwrites before the user can see it). This is a different debug statement from the one
the PO reported and from KNOWN_PROBLEMS.md §7.7's scope, and remains transient (overwritten by the
success/error outcome exactly as before this change) — not a regression, but the same category of
issue this story's PO intent describes.

`docs/KNOWN_PROBLEMS.md` §7.7: Open → Resolved, with this evidence.

## Blockers

None.
