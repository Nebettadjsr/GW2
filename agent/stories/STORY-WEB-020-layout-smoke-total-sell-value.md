## Story ID

STORY-WEB-020

## Title

Exercise backend total sell value in the shared layout smoke fixture

## Status

TODO

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

Not started.

## Blockers

None.

## Follow-up Findings

None.
