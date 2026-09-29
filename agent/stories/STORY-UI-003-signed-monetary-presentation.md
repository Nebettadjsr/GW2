## Story ID

STORY-UI-003

## Title

Preserve signs and correct cost labels in JavaFX monetary presentation

## Status

TODO

## Milestone

milestone-05

## Goal

Ensure JavaFX crafting views present signed monetary values and purchase-cost units consistently with the supplied authoritative values.

## Authoritative Source Documents / Sections

- `docs/KNOWN_PROBLEMS.md` KP-09: observed monetary sign and purchase-cost presentation defects.
- `agent/stories/STORY-DOM-024-ectoplasm-fee-policy-alignment.md` Follow-up Findings F001: remaining `formatSigned` callers and ineffective signed flag.

## Context

DOM-024 corrected its Ectoplasm call sites without changing `CoinUtils.formatSigned`. The finding identifies remaining Crafting Profit and Discovery call sites, including a Profit conditional whose two branches are identical. KP-09 also records purchase-cost labels that do not match the supplied value basis.

## Acceptance Criteria

1. Negative monetary values in the affected JavaFX crafting views are visibly presented as losses, using the supplied value without recalculating it.
2. Signed and unsigned presentation paths behave according to their intended labels; remove any conditional whose branches are identical if it is part of the affected presentation.
3. Purchase-cost labels and explanations match the supplied total-plan or single-craft value basis.
4. Record affected views and verification evidence in Result; do not change domain economics or Ectoplasm fee policy.

## Required Tests

- Targeted unit tests for signed monetary formatting, including negative, zero and positive values.
- Targeted JavaFX view verification for affected Profit and Discovery values and purchase-cost labels.

## Constraints

- Presentation only; preserve authoritative domain values and existing economics.
- Do not broaden into unrelated monetary formatting or UI cleanup.

## Dependencies

None.

## Definition of Done

Affected JavaFX crafting views preserve monetary signs and accurately describe supplied cost units, targeted tests and UI verification are recorded, and the story revision passes the CI gate.

## Result

Not started.

## Blockers

None.
