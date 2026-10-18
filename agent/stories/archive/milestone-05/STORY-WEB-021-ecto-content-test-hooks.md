## Story ID

STORY-WEB-021

## Title

Give Ectoplasm results stable content hooks for browser checks

## Status

DONE

## Milestone

milestone-05

## Goal

Let Ectoplasm browser and component checks identify the current frontend-calculated result regions without relying on styling classes or the always-rendered screen root.

## Authoritative Source Documents / Sections

- `agent/stories/STORY-WEB-019-layout-smoke-navigation-coverage.md`, Follow-up Findings F002–F003.
- `agent/stories/STORY-WEB-019-layout-smoke-navigation-coverage.md`, Follow-up Finding F002: update the layout smoke Ectoplasm readiness check to use the stable result hook.
- Product Owner resolution establishing the frontend-owned Ectoplasm calculation as the canonical implementation.
- `docs/TEST_STRATEGY.md` §12.1–12.2, frontend state and browser verification.
- Supplied `docs/ROADMAP.md` Phase 5, Ectoplasm screen and rendering/interaction/state tests.

## Context

The Ectoplasm screen now intentionally performs its simple salvage calculation in the browser using the required item metadata, Trading Post prices and account Luck data.

The previous backend-calculated Ectoplasm screen is obsolete and must not be restored.

The screen's existing `ecto-screen` hook is always present and checks currently use presentational classes to identify populated calculation content. Stable semantic `data-test` hooks should identify the current data-driven regions instead.

The component also retains two scoped style rules for removed elements.

## Acceptance Criteria

1. Add stable `data-test` hooks to the current Ectoplasm screen's data-driven calculation/result regions so browser and component checks can identify when the required data has loaded and the frontend-owned result is available.
2. Update relevant Ectoplasm browser and component assertions, including the layout browser smoke check, to use those hooks for content readiness and result checks instead of style-only selectors where a stable hook is appropriate.
3. Verify the current frontend-owned behavior, including populated calculation results, input-driven recalculation, Trading Post price refresh behavior, account Luck presentation, and relevant warning/error states already supported by the screen. Tests must not introduce or require a backend Ectoplasm calculation operation.
4. A root hook alone must not count as evidence that the required data and calculated result are available.
5. Remove the unused `.result-conclusion` and `.tool-separator` scoped rules identified in the original finding without changing visible page behavior.

## Required Tests

- Run focused Ectoplasm component tests covering the current frontend-owned calculation and its relevant data/result states.
- Run the Ectoplasm and layout browser smoke checks that consume the new result hooks in a controlled browser runtime.
- Verify that the browser checks no longer depend on presentational style classes for result readiness where the new semantic hooks apply.

## Constraints

- Keep changes within Ectoplasm presentation, test hooks, and their checks.
- Do not introduce or restore a backend Ectoplasm calculation operation.
- Do not move the Ectoplasm economic calculation out of the browser.
- Preserve the current frontend-owned calculation behavior and economic rules.
- Preserve existing visual and accessible meaning.
- Do not preserve obsolete backend-calculated states merely because older tests or stories referenced them.

## Dependencies

- STORY-WEB-019 (DONE).

## Definition of Done

Stable semantic hooks identify the current frontend-calculated Ectoplasm result content, relevant component and browser checks use those hooks instead of presentation classes where appropriate, obsolete scoped styles are removed, and the named checks pass.

## Result

Three semantic `data-test` hooks now name the current, browser-calculated Ectoplasm regions, and the
Ectoplasm and layout checks read readiness from them instead of from `.salvage-calculation` /
`.account-luck`. No backend calculation operation was introduced; the screen still reads only
`/items/metadata`, `/items/prices` and `/account/luck`.

**`frontend/src/ecto/EctoSalvageScreen.vue` (AC 1, 4, 5).** A new `resultReady` computed —
`!loading && metadata.size > 0 && effectiveLuckCostCopper != null` — gates the `ecto-result` hook on
`.result-layout`, bound as `:data-test="resultReady ? 'ecto-result' : null"` so the attribute appears
only once the required metadata and Trading Post quotes arrived and the effective cost could be
computed. Rendering is unchanged: no `v-if` was added, moved or retargeted, so the warning text, the
"final cost will appear when Trading Post prices are available" fallback and the Luck fallbacks all
behave exactly as before. `.result-left` carries `data-test="ecto-calculation"` (the calculated
figures) and the Luck block carries `data-test="ecto-account-luck"`, keeping Luck identifiable apart
from the result. The unused `.result-conclusion` and `.tool-separator` scoped rules are deleted; both
were dead (no template or check referenced either), so no visible styling changed.

**Checks retargeted (AC 2).** `frontend/scripts/ecto-browser-smoke.mjs` waits for
`[data-test="ecto-result"]` and `[data-test="ecto-account-luck"]`, scopes its `figure()` lookup to
`[data-test="ecto-calculation"]`, names the hooks in its step line, and asserts the result hook is a
region of its own *inside* `ecto-screen` and that Luck is a separate element — so the root can never
stand in for loaded content. `frontend/scripts/layout-browser-smoke.mjs` readiness for Ectoplasm is
now `[data-test="ecto-result"]:has([data-test="ecto-account-luck"])`, with no presentational selector
left. `frontend/src/__tests__/App.spec.ts` asserts the result and Luck hooks instead of
`.salvage-calculation`, and `EctoSalvageScreen.spec.ts`'s `calculationFigure` helper is scoped to
`[data-test="ecto-calculation"]`.

**Behaviour verified (AC 3).** The existing Ectoplasm suite still covers the populated economics,
local recalculation on amount/method/tool/TP mode with `requests` staying at 3, the refresh that reads
only the two prices, Luck targets, progress and the 300% cap, and the assertion that
`/api/ecto/salvage` is never called. Two tests were added there: one proving the Luck figures sit
under their own hook and are not the result element, and one failing the prices read, which shows the
screen root and the Luck region present while `ecto-result` is correctly withheld and the warning
"Trading Post prices could not be read." is shown.

**Commands and results (all run in this invocation, from `frontend/`).**

- `npx vitest run src/ecto/__tests__/EctoSalvageScreen.spec.ts src/__tests__/App.spec.ts` →
  `Test Files 2 passed (2)`, `Tests 29 passed (29)`.
- `npx vitest run src/ecto/__tests__/EctoContentHooks.spec.ts` (prepared QA test, byte-for-byte
  unchanged — sha256 `30c99a46…1f06c13`, matching the plan's `protected_test_hashes`) →
  `Tests 1 failed | 1 passed (2)`. The two hook assertions pass; the one failure is the literal
  `'14,134'`, which this machine's ICU default locale (`de-DE`) renders as `14.134`. Preserved
  unchanged for independent review; see `CLAUDE_RESULT.md`.
- `npm run build` → `✓ built in 485ms` (vue-tsc clean).
- `npm run smoke:ecto` → `Ecto browser smoke PASSED (6 steps)`, step 1 reading
  `readiness taken from ecto-result / ecto-account-luck inside ecto-screen`.
- `npm run smoke:layout` → `Layout and accessibility browser check PASSED (13 steps)`, all six
  destinations ready and measured at three viewports.
- Coverage of the changed area:
  `npx vitest run --coverage --coverage.reporter=text --coverage.include='src/ecto/**' src/ecto/__tests__/EctoSalvageScreen.spec.ts`
  → `EctoSalvageScreen.vue` 93.56% statements, 91.58% branches, 93.75% functions, 96.68% lines.
- Swept-in maintainer work under `agent/runtime/**` that this commit will carry:
  `python -m pytest agent/runtime/tests -q --import-mode=importlib -o consider_namespace_packages=true`
  → `417 passed, 50 subtests passed`.

## Blockers

None.

## Follow-up Findings

F001: `frontend/src/ecto/useEctoSalvage.ts` is referenced by nothing (`rg useEctoSalvage` matches only
its own declaration) and still models the removed backend `/api/ecto/salvage` calculation, as do
`EctoSalvage` / `EctoSalvageScenario` in `frontend/src/api/types.ts`. At 0% coverage it holds the
`src/ecto` package to 80.76% statements while the screen itself measures 93.56%.

F002: `frontend/scripts/ecto-live-smoke.mjs` (`npm run smoke:ecto:live`) still calls
`/api/ecto/salvage` and waits for `[data-test="ecto-scenario-table"]`, neither of which the current
screen has, so that live check cannot pass against the frontend-owned calculation.

F003: the prepared QA test's literal `'14,134'` depends on the test runner's ICU default locale. Every
other frontend expectation formats through `toLocaleString()` for exactly this reason, so the suite is
green on an `en-US` runner (CI, `ubuntu-latest`) and red on a `de-DE` workstation.

## Follow-up Findings Disposition

F001: DISMISSED — legacy browser API cleanup is explicitly not required for Phase 5 feature behavior; supplied Phase 5 excerpt, KP-25
F002: FOLLOW-UP STORY — STORY-WEB-029
F003: FOLLOW-UP STORY — STORY-WEB-030
