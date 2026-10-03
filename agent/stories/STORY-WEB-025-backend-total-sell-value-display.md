## Story ID

STORY-WEB-025

## Title

Render backend total sell value in Crafting Profit

## Status

SUPERSEDED

## Milestone

milestone-05

## Goal

Render the backend-provided total sell value in Crafting Profit without recomputing it from revenue and craft count in the view.

## Authoritative Source Documents / Sections

- `agent/stories/STORY-DOM-023-crafting-profit-fee-integration.md`, Follow-up Finding F002.
- `docs/DOMAIN_SPEC.md` §2.1.1 and §25, authoritative economic values and presentation.
- Supplied `docs/ROADMAP.md` Phase 5, browser rendering of backend-provided authoritative values.

## Context

The finding states that the Crafting Profit view computes its “Total sell value” as revenue multiplied by craftable count. Phase 5 requires browser workflows to render backend-owned authoritative results; STORY-WEB-020 separately improves layout-fixture coverage of that value.

## Acceptance Criteria

1. Use the backend-provided total sell value in the Crafting Profit row presentation.
2. Remove view-side derivation of total sell value from revenue and craftable count.
3. Preserve unavailable/null presentation and existing backend values.
4. Add focused component evidence with supplied values that differ from any tempting client-side multiplication.

## Required Tests

- Focused Crafting Profit component tests for backend total sell value, a deliberately non-multiplicative value, and unavailable values.
- Run the relevant browser smoke check if its fixture covers the changed column.

## Constraints

- Do not change backend economics or recalculate domain results in the frontend.
- Keep the change scoped to consuming and displaying the existing authoritative field.

## Dependencies

None.

## Definition of Done

Crafting Profit displays the supplied total sell value, focused tests cover authoritative and unavailable values, and the result records evidence.

## Result

SUPERSEDED - withdrawn as duplicate work. Not implemented, and must not be.

This story was created on 2026-10-03 from `STORY-DOM-023` Follow-up Finding F002. It restates work
that is already finished, and it restates it against the wrong interface.

What F002 actually says: "`CraftingProfitView`'s 'Total sell value' column computes
`revenue x craftableCount` in the view instead of carrying `CraftResult.totalSellValueCopper`
through `CraftingProfitController.UiRow` ... `STORY-WEB-008` avoided [it] on the web row", and it
closes with "The formula is identical today, so no value disagrees; it is a second derivation site,
not a defect." The finding is about the legacy **JavaFX** view, and it is explicitly not a defect.

This story's Goal, Context and Required Tests instead describe the **browser** Crafting Profit
screen, where no such derivation exists:

- `frontend/src/crafting/CraftingProfitTable.vue:50` renders `formatCopper(row.totalSellValueCopper)`
  straight from the backend field, and the component's own doc comment states that
  `totalSellValueCopper` and `totalProfitCopper` "are the backend's own totals, not this table's
  product of a count and a per-craft value".
- `frontend/src/crafting/useProfitTableView.ts` sorts on the supplied field for the same reason.
- No `revenueCopper x craftableCount` expression exists anywhere in `frontend/src/`. The only
  remaining `craftableCount *` is `SelectedResultDetail.vue:49` (`craftableCount * outputCount`),
  which is the root output *quantity* that `docs/TARGET_ARCHITECTURE.md` section 10.2 prescribes,
  not a sell value.

The JavaFX half the finding does describe was completed by `STORY-APP-013` ("Carry authoritative
total sell value into the JavaFX Profit view", DONE): `CraftingProfitController.java:37-41` documents
the value as "carried unchanged ... rather than revenueCopper and craftableCount", and
`CraftingProfitView.java:601` binds the column directly to that property.

Root cause of the duplicate: `STORY-DOM-023`'s existing disposition read
`F002: ALREADY COVERED ? STORY-APP-013 ...`, with a literal `?` where an em dash belonged. The
separator pattern in `agent/runtime/core/follow_up_findings.py` did not accept `?`, so the harness
reported the finding as undisposed, re-supplied it to the planner, and the planner replaced the
correct disposition with this story. That pattern now accepts any separator, and
`STORY-DOM-023`'s disposition has been restored.

## Blockers

None. Withdrawn as duplicate work, not blocked.

## Follow-up Findings

None.
