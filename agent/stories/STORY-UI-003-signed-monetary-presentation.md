## Story ID

STORY-UI-003

## Title

Preserve signs and correct cost labels in JavaFX monetary presentation

## Status

SUPERSEDED

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

SUPERSEDED — not implemented, and must not be implemented as written.

This story asked for presentation fixes inside the legacy JavaFX crafting views. After it was
written, the Product Owner decided that the JavaFX user interface is obsolete and removable:

- The resolution notes on `agent/user-interventions/UI-002-STORY-SYNC-005.md`,
  `UI-003-STORY-UI-003.md`, `UI-004-STORY-WEB-018.md` and `UI-005-STORY-WEB-020.md` each state that
  "the JavaFX user interface is no longer required" and that JavaFX UI code, and code existing
  exclusively to support it, may be removed.
- `docs/TARGET_ARCHITECTURE.md` §1 records the browser frontend as the canonical user interface and
  the legacy JavaFX UI as not part of the target architecture; §13 and the Decided list repeat that
  JavaFX is obsolete and removable.
- `docs/KNOWN_PROBLEMS.md` KP-09 was retitled "Legacy JavaFX monetary presentation …" to mark it as
  a legacy-interface finding rather than browser behavior.

Spending implementation effort on a UI scheduled for removal conflicts with that decision, so the
story is retired rather than updated. Its Required Tests also depended on TestFX JavaFX view
verification, which the CI gate cannot run (`docs/TEST_STRATEGY.md` §32, §36.4).

The underlying defect is real and still present — `util/CoinUtils.java:53` still calls
`format(Math.abs(copper))`, and `CraftingProfitView.java:900` still evaluates
`(signed ? CoinUtils.formatSigned(v) : CoinUtils.formatSigned(v))`, a conditional whose two branches
are identical. It is not lost: it remains recorded under `docs/KNOWN_PROBLEMS.md` KP-09 and is
resolved by removing the affected views, which `docs/ROADMAP.md` now allows at any point from
Phase 5 onward. The equivalent browser presentation is already correct and is not affected by this
story.

No JavaFX code was changed by this disposition, and JavaFX removal is explicitly not started here.

## Blockers

None. Retired by product decision, not blocked.

## Follow-up Findings

None.
