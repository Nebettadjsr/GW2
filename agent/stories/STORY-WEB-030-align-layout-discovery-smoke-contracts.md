## Story ID

STORY-WEB-030

## Title

Align layout fixtures and Discovery live smoke contract

## Status

DONE

## Milestone

milestone-05

## Goal

Make the layout smoke fixture satisfy the current Crafting Profit response contract, repair the Discovery live smoke check against the current API contract, and remove locale-dependent Ectoplasm test expectations.

## Authoritative Source Documents / Sections

- Supplied Phase 5 roadmap excerpt: complete and verify browser feature workflows; browser smoke coverage is tracked in canonical stories and TEST_STRATEGY.md.
- `docs/TEST_STRATEGY.md` browser smoke and frontend test sections.
- `agent/stories/STORY-WEB-020-layout-smoke-total-sell-value.md`, Follow-up Findings F001?F003.
- `agent/stories/STORY-WEB-021-ecto-content-test-hooks.md`, Follow-up Findings F003.
- `agent/stories/STORY-WEB-028-align-browser-workflow-contracts.md`, Result and Follow-up Findings F001.

## Context

The shared layout fixture still gives an unavailable result non-null result-derived values and omits required nullable response fields. Discovery's live check still expects a response field removed from the API contract. The Ectoplasm test expectation embeds a locale-specific number string, making it dependent on the runner's ICU locale.

## Acceptance Criteria

1. Every layout fixture row supplies the current `CraftingRow` response fields with contract-valid values, including explicit nulls where appropriate; unavailable rows do not supply result-derived values.
2. The Discovery live smoke assertions match the current response contract and no longer require `inventoryCharacterName`.
3. The Ectoplasm numeric expectation is independent of the machine's default locale while still checking the intended formatted value.
4. The live check and controlled fixture continue to assert backend-provided authoritative values without calculating them in the browser.

## Required Tests

- Run the layout browser smoke check and verify the valid, unavailable, and missing-price fixture cases.
- Run the Discovery live smoke check against the current backend and verify its response and rendered details.
- Run the affected Ectoplasm component test under at least two available locales, or use an explicit locale-independent assertion with equivalent evidence.
- Run relevant frontend component tests and review affected coverage; review unresolved quality gaps for the changed smoke and fixture paths.

## Constraints

Do not change the backend API or authoritative calculations. Do not change accepted product presentation to satisfy an obsolete assertion. Keep live checks read-only.

## Dependencies

None.

## Definition of Done

The layout and Discovery checks pass against the current application/API contracts, the Ectoplasm expectation is locale-independent, and relevant coverage reports and unresolved quality gaps are reviewed. The CI gate is green.

## Result

All four acceptance criteria are met and every required check was run on a shipped command.

**AC1 — layout fixture against the current `CraftingRow` contract.**
`frontend/scripts/layout-browser-smoke.mjs`'s shared fixture now supplies every required
`CraftingRow` member on every row, `iconUrl` included as an explicit null rather than an omission,
and every `MissingItem` member (`purchaseUnitPriceCopper`, `totalPurchaseCostCopper`, `iconUrl`) on
the unpriced-material rows. The unavailable row (recipe 4) previously kept the available row's
`matsSellValueCopper` 4 321 and `revenueCopper` 111 110 while declaring `resultAvailable: false`, so
the page printed an "Own materials" amount for a calculation that produced none; all nine
result-derived fields are now null there. A new `checkRowContract` step validates the fixture itself
before any row reaches a page — required names present, nothing result-derived on a resultless row,
and each distinguished case actually present — because the on-screen comparisons read each cell
against the value this very fixture supplied and would otherwise have agreed with an impossible row.
`checkSellValues` became the field-parameterised `checkSuppliedColumn`, and the profit table's
per-craft "Own materials" column is now compared too, which is the cell that shows the missing
marker on screen for the resultless row.

**AC2 — Discovery live smoke against the current response contract.**
`checkContract` no longer requires `inventoryCharacterName`; its *presence* in the response now means
an old build is serving the origin, matching `web.dto.CraftingDiscoveryResponse`'s single combined
character/discipline scope. The detail check compares the echoed
`calculation.settings` against the table response's settings instead, and asserts the request carries
no separate inventory character. Three further contract repairs were needed for the check to reach
its own assertions: the removed `discovery-craftable` column is no longer read, the profit column is
compared against `totalProfitCopper` (what `DiscoveryTable.vue` renders) rather than `profitCopper`,
the detail total is read from `discovery-detail-profit` rather than the renamed
`discovery-detail-total-profit`, and the rendered list is compared against the response sliced to the
page's own displayed maximum read from `discovery-max-displayed`/`discovery-show-all`
(`DOMAIN_SPEC.md` 2.1.1, initially 250) — a live result of 672 rows is the page working as specified,
not a mismatch.

**AC3 — locale-independent Ectoplasm expectation.**
`(14_134).toLocaleString()` on both sides of the assertion restated the runner's locale back to
itself instead of naming the value. The expectations in `EctoContentHooks.spec.ts` and
`EctoSalvageScreen.spec.ts` now read the rendered region through
`frontend/src/ecto/__tests__/renderedNumbers.ts`'s `withoutDigitGrouping` and name the integer
(`14134`, `10457`, `1046`, `1032`, `1200`). `renderedNumbers.spec.ts` establishes the helper's
locale independence across `en-US`, `de-DE`, `fr-FR`, `es-ES` and `en-GB`, asserts that at least two
of those renderings differ (so the check is not a tautology), and asserts it does not turn one number
into another.

**AC4 — no browser-side calculation.** Every expected amount in both scripts is a value the
fixture or the response supplied, passed only through the page's own `formatCopper` text form. The
single arithmetic in the live check is `min(maximum, rowCount)`, a display count, not an economic
value.

### Tests run (exact commands and real output)

From `frontend/`:

1. `npm test -- --run src/ecto/__tests__/renderedNumbers.spec.ts src/ecto/__tests__/EctoContentHooks.spec.ts src/ecto/__tests__/EctoSalvageScreen.spec.ts`
   → `Test Files 3 passed (3) / Tests 19 passed (19)`.
2. `npm test -- --run src/crafting/__tests__/CraftingDiscoveryScreen.spec.ts src/crafting/__tests__/discoveryResolutionAssociation.spec.ts`
   → `Test Files 2 passed (2) / Tests 32 passed (32)`.
3. `npm run smoke:layout` → `Layout and accessibility browser check PASSED (15 steps).` The two step
   lines that carry this story's assertions:
   - `ok every controlled row is a valid CraftingRow answer — 5 rows: 4 with a supplied result, 1 with an unpriced material, 1 with no result and every result-derived field null`
   - `ok both tables show the supplied total sell value, and no result means no amount — crafting "Total sell value": recipe 1 128g 45s 9c, recipe 2 0c, recipe 5 128g 45s 9c, recipe 3 128g 45s 9c, recipe 4 —; crafting "Own materials": recipe 1 43s 21c, recipe 2 43s 21c, recipe 5 43s 21c, recipe 3 43s 21c, recipe 4 —; discovery "Sell value": recipe 1 128g 45s 9c, recipe 2 0c, recipe 3 128g 45s 9c, recipe 4 —, recipe 5 128g 45s 9c`
   The valid, zero-count, missing-ingredient-price and unavailable cases are all on screen, and the
   unavailable row reads the missing marker in both money columns.
4. `npm run smoke:discovery:live`, against a backend and dev server started fresh for this run
   (`./mvnw spring-boot:run` on Java 25.0.1, PID 23528, Spring Boot 4.1.1, started 17:39; `npm run dev`
   on 5173) over the populated local database (9 457 cached price items, 17 character/discipline
   options after the backend's own account refresh) →
   `Discovery live smoke PASSED: listed rows, one fresh tree and a reload matched the actual responses.`
   Its printed evidence: `rowCount 672, displayedMaximum 250, rendered 250,
   nonPositiveProfitCandidatesKept 626`; detail `recipeId 291, nodes 12, treeBasis
   SINGLE_OUTPUT_REQUIREMENT, consistency FRESH_CALCULATION`, with the echoed
   `calculation` carrying only `scope` and `settings` — no `inventoryCharacterName` on the response,
   the request or the reload. The run is read-only: it clicked no synchronization control and wrote
   nothing.
5. `npm run type-check` (`vue-tsc --noEmit`) → clean, covering the new helper module.

**Two-locale evidence for AC3.** This workstation's default ICU locale is `de-DE`, CI's is `en-US`,
so the shipped expectations are exercised under both. That the difference is load-bearing inside the
vitest environment — not only in plain Node — was established with a throwaway control spec (deleted
after the run, per `tasks/lessons.md`): it printed
`default locale=de-DE; toLocaleString()=14.134; rendered="Consumed Luck14.134Luck-based Magic Find…"; ungrouped="Consumed Luck14134Luck-based…"`
and passed both `expect(withoutDigitGrouping(rendered)).toContain('14134')` and
`expect(rendered).not.toContain('14,134')`. The old-style expectation would have agreed with either
locale without naming either value; an `en-US` literal would fail here outright.

### Coverage and unresolved quality gaps

`npx vitest run src/ecto src/crafting --coverage --coverage.include='src/ecto/**' --coverage.include='src/crafting/**'`:
89.39% statements / 86.54% branch / 91.62% line over those two areas. The new helper is fully
covered — `renderedNumbers.ts` 100% on all four measures, measured on its own include. The screens
this story's checks exercise are at or near the 90% aim
(`EctoSalvageScreen.vue` 94.05%/92.05%, `CraftingProfitScreen.vue` 91.12%/86.13%,
`SelectedDiscoveryDetail.vue` 100%/93.33%, `useDiscoveryTableView.ts` 91.37%/82.69%).

Unresolved gaps, all pre-existing and none introduced here: the two standalone smoke scripts are not
instrumented by vitest at all, so no coverage figure describes the paths this story changed — the
run output quoted above is the only evidence for them, which is why both commands were executed
rather than reasoned about. `DiscoverySettingsForm.vue` (52.7%/60.6%) and `useIngredientSearch.ts`
(72.72%/45.45%) are below the aim, and `src/ecto/useEctoSalvage.ts` is at 0%/0%; none of the three is
touched by this story (see F002).

## Blockers

None.

## Follow-up Findings

F001: the first `npm run smoke:discovery:live` run against the freshly started backend failed with
`/api/crafting/discovery: expected HTTP 200 — 503 !== 200`, and the identical request succeeded
moments later. The 503 is the backend's own price-staleness guard on a cold cache, not a page
defect, but neither live check waits for or distinguishes it, so a first run after a restart reports
a contract failure for an environment condition. The account-staleness guard behaves the same way on
`/api/crafting/selector-options`, which returned `ACCOUNT_DATA_STALE` with a task URL before the
character options became readable.

F002: `frontend/src/ecto/useEctoSalvage.ts` exports `useEctoSalvage` and has no caller anywhere in
`frontend/src` — `EctoSalvageScreen.vue` does not use it — which is why it measures 0% statements and
0% branch. It is unreferenced code carrying a coverage penalty, not an untested dependency of a
rendered screen.

F003: `frontend/src/crafting/` has no spec for `DiscoveryTable.vue` itself. Its column-to-field
mapping is only covered indirectly through `CraftingDiscoveryScreen.spec.ts`, and two of the
contract drifts this story had to repair in the live check — the removed craftable column and the
profit column's switch to `totalProfitCopper` — live exactly there.
