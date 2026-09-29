# User Intervention

## Status

RESOLVED

## Story

STORY-WEB-018

## Reason

Claude Code exited with code 1 on 2 consecutive runs without any capacity signal, so this is a failing invocation rather than a usage pause. The orchestrator continues with other work; this story needs a human to inspect the run.

## Claude Response

# Implementation Result — STORY-WEB-022

Story: `agent/stories/STORY-WEB-022-align-ecto-page-label.md` (matches `agent/CURRENT_STORY.md`)
Status: DONE

## What changed

Two step-output strings in two local browser smoke scripts. No assertion, no component, no destination
label and no calculation was touched in this pass.

- `frontend/scripts/ecto-browser-smoke.mjs` — step 1 is now named
  `destination, the "Ecto Salvage" title and heading, and three current input reads`.
- `frontend/scripts/layout-browser-smoke.mjs` — the per-viewport step line now reports the headings it
  compared (`heading and title verified for … ecto "Ecto Salvage" …`) instead of claiming only "reflow".

## Why

The outstanding deficiency was the evidence, not the label. The maintainer's commits `72fc59f` (the
`destinations.ts` label edit and the layout check's `Ecto Salvage` expectation) and `4c2fe50` (the
rewrite of `ecto-browser-smoke.mjs` against the locally calculating screen, plus the replacement of the
stale `ecto` readiness selector with `.panel:has(.salvage-calculation):has(.account-luck)`) removed both
blockers this story had recorded. This pass verified on the current tree that both shipped scripts now
reach and execute their Ecto heading and title assertions, made that visible in each script's own
output, and proved with throwaway controls that the assertions are load-bearing.

`STORY-WEB-021`'s work was preserved: the `ecto` readiness selector is still the maintainer's
style-class selector rather than a `data-test` hook, and no result-scenario, loading, failure or reload
assertion was added, moved or removed.

## Tests run (real output)

From `frontend/`, real Chrome, built bundle, stub origins on `127.0.0.1` in each script's own process:

- `npm run build` — clean, `✓ built in 750ms`.
- `npm run smoke:ecto` (port 5192) — **PASSED (6 steps)**, step 1
  `ok   destination, the "Ecto Salvage" title and heading, and three current input reads`.
- `npm run smoke:layout` (port 5193) — **PASSED (13 steps)**, label named in all three per-viewport
  lines at 1440×900, 768×1024 and 360×800.
- Controls from throwaway copies (`scripts/.tmp-control-layout-label.mjs`,
  `scripts/.tmp-control-ecto-label.mjs`, differing only in expecting `Ectoplasm Salvage`; both deleted,
  `git status` clean): layout `FAILED after 2 step(s): ecto at desktop 1440×900: the page heading did
  not read "Ectoplasm Salvage".`; ecto `FAILED after 0 step(s): The document title is stale.`
- `npx vitest run src/__tests__/App.spec.ts src/ecto/__tests__/EctoSalvageScreen.spec.ts` —
  `Test Files 2 passed (2) | Tests 27 passed (27)`.

Neither browser script runs in CI (`TEST_STRATEGY.md` §12.2); GitHub Actions remains the
full-regression gate.

## Remaining uncertainty

None for this story's criteria. The browser checks are local-only by design, so their evidence must be
re-taken by hand whenever the Ecto screen changes.

## Follow-up Findings

F001: `STORY-WEB-021` is still TODO and its AC 3 — "preserve existing checks for the four result
scenarios, loading, failure, and reload behavior" — describes the backend-calculated screen that
`4c2fe50` replaced. `ecto-browser-smoke.mjs` no longer has four backend scenarios, an `ecto-reload`
control or `ecto-loading`/`ecto-error`/`ecto-unavailable` states to preserve. That criterion needs
restating against what the page now does before the story is executed.

F002: nothing imports `frontend/src/api/ectoApi.ts` or `frontend/src/ecto/useEctoSalvage.ts` — the
screen computes from `/api/items/metadata`, `/api/items/prices` and `/api/account/luck` instead. Both
modules, and the `EctoSalvage*` interfaces in `frontend/src/api/types.ts` they use, are dead code that
still documents a removed route. Deciding whether the backend route returns is a product/architecture
call, not a label fix.

## User Resolution

RESOLVED

## Resolution Notes

The Product Owner confirms that the current frontend-owned Ectoplasm Salvage implementation is intentional and supersedes the previous backend-owned implementation.

The Ectoplasm calculation is simple enough to run directly in the browser. The former dedicated backend calculation route must not be restored.

Consequently:

- `STORY-WEB-021` must be updated to reflect the current frontend-owned calculation flow. Acceptance criteria referring to the removed backend result scenarios, backend loading/failure states, or reload behavior are obsolete.
- `frontend/src/api/ectoApi.ts`, `frontend/src/ecto/useEctoSalvage.ts`, obsolete `EctoSalvage*` API interfaces, and other code used exclusively by the removed backend Ectoplasm flow may be removed as orphaned code.
- The JavaFX user interface is obsolete now that the browser frontend provides the application's user-facing screens. JavaFX UI code and code exclusively supporting that interface may be removed.
- Shared domain and backend functionality still required by the browser application must be preserved.
- The cleanup should include other clearly orphaned code encountered as a consequence of these superseded implementations, provided it has no remaining consumer.

The frontend-owned Ectoplasm calculation and browser UI are the canonical architecture going forward. The planner may align dependent stories and architecture documentation with this decision.