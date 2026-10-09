## Story ID

STORY-WEB-030

## Title

Align layout fixtures and Discovery live smoke contract

## Status

TODO

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

Not started.

## Blockers

None.
