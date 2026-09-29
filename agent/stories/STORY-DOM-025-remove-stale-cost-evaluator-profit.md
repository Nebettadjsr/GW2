## Story ID

STORY-DOM-025

## Title

Remove the stale pre-fee profit calculation from CostEvaluator

## Status

DONE

## Milestone

milestone-05

## Goal

Prevent CostEvaluator from publishing profit values that use the superseded pre-fee formula or a different buy-cost basis from the authoritative crafting result.

## Authoritative Source Documents / Sections

- agent/stories/STORY-DOM-023-crafting-profit-fee-integration.md, Follow-up Findings F001 and Result.
- docs/DOMAIN_SPEC.md section 26, as cited by STORY-DOM-023.

## Context

DOM-023 records that CostEvaluator still populates CostEvaluationResult.profitPerCraft and totalProfit using a pre-fee formula, while the planner independently computes profit with a different buy-cost basis. No production caller currently reads those fields. The stale fields remain misleading if reused.

## Acceptance Criteria

1. CostEvaluator no longer publishes profit values that contradict the resolved fee policy or the authoritative crafting profit calculation.
2. Retain only CostEvaluator responsibilities and fields needed by its callers; do not create a second authoritative profit formula.
3. Focused tests establish the revised CostEvaluator result contract and preserve gross revenue and cost inputs used by the planner.
4. Record any compatibility impact for existing tests/callers in the story Result.

## Required Tests

- Focused CostEvaluator tests for revenue and cost outputs and the absence or revised contract of stale profit outputs.
- Directly affected tests must pass; the complete regression gate remains GitHub CI.

## Constraints

- Do not change the resolved fee model, planner buy-cost basis, gross revenue, or unrelated crafting behavior.
- No broad monetary refactor or speculative cleanup.

## Dependencies

Completed STORY-DOM-023.

## Definition of Done

CostEvaluator no longer exposes the stale pre-fee profit result, focused tests and Result document the contract, and the revision passes the GitHub CI gate.

## Result

DONE. **A deletion, not a rewrite:** no profit formula was added, moved or changed anywhere, and
`craft.CraftingPlanner.evaluateOneRecipeNew(...)` — DOM-023's single authoritative profit site, fee
included — was not touched.

**What was removed (AC 1, AC 2).** `craft.CostEvaluator.evaluate(...)` no longer computes
`revenue − buyCost − opportunityCost`, and `craft.CostEvaluationResult` no longer carries
`profitPerCraft`/`totalProfit`. The same edit removed `buyCostPerCraft` and the
`effectiveCostPerCraft` derived from it: neither had a reader, and their basis
(`sim.getFirstCraft().getBuyCostCopper()`) is not the planner's purchased-material basis
(`missingToBuyOne`), so AC 2's "retain only … fields needed by its callers" leaves exactly the two
figures `CraftingPlanner` reads — the gross `revenuePerCraft` (DOMAIN_SPEC.md §24/§25) and
`opportunityCostPerCraft` (§11.1). The result class now documents why it publishes no profit and
which site owns one.

**What was preserved (AC 3, constraints).** `evaluate(...)`'s signature, the revenue selection
(`listingSell ? sellUnit : buyUnit`, times `outputCount`) and §21's zero-revenue path for a missing
or partial quote are unchanged. The fee model, the planner's buy-cost basis, gross revenue and
total sell value were not touched.

**Compatibility impact (AC 4): none observed.** The removed members had **no reader in production
or test code** — `CraftingPlanner` was the only caller of `evaluate(...)` and already read only
`getRevenuePerCraft()`/`getOpportunityCostPerCraft()`. No test expectation was changed, weakened or
deleted; both test commands below compiled the entire test tree, which is the compile-time evidence
that nothing else referenced the removed members. No monetary value visible to any client moved.

**Tests.** New `src/test/java/craft/CostEvaluatorTest.java` (6 cases) states the revised contract:
instant-sell and listing-sell revenue carrying the recipe's output count (360c / 450c), a missing
quote, a quote with neither unit and a quote lacking the selected mode's unit each giving 0 revenue
while the owned-material cost is still reported, a simulation with no first craft reporting 0
opportunity cost, both figures staying per-craft against a craft count of 7, and a reflective
assertion that `CostEvaluationResult` declares exactly `getRevenuePerCraft` and
`getOpportunityCostPerCraft` — so a pre-fee profit cannot reappear on this result without a test
failing. Its fixture's first craft carries a deliberately non-zero buy cost that nothing returned
may vary with.

- `./mvnw -Dtest=CostEvaluatorTest,CraftingPlannerProfitFeeTest -DfailIfNoTests=false test`
  — **Tests run: 14, Failures: 0, Errors: 0, BUILD SUCCESS** (CostEvaluatorTest 6,
  CraftingPlannerProfitFeeTest 8, the latter unchanged).
- `./mvnw -Dtest='CraftingPlanner*Test' -DfailIfNoTests=false test` — **Tests run: 19, Failures: 0,
  Errors: 0, BUILD SUCCESS** (profit-fee 8, single-recipe 4, total-sell-value 6, heuristic-skip 1).

**Remaining uncertainty.** Suites outside those two commands, frontend tests, the browser smoke
scripts and the real-database `*IT` checks were not run locally; GitHub Actions is the
full-regression gate (TEST_STRATEGY.md §20/§36). Nothing user-visible changed, so no browser or
JavaFX evidence applies here. No Phase 5 completion is implied.

**Docs.** `docs/CURRENT_ARCHITECTURE.md` §9 item 7 owned the statement that
`CostEvaluationResult.getProfitPerCraft()`/`getTotalProfit()` are pre-fee figures no production code
reads; that clause now records the removal and what the result still carries. The §2/`CURRENT_STATE_SPEC.md`
class listings name the unchanged classes and needed no edit; `DOMAIN_SPEC.md` was not affected.

## Blockers

None.

## Follow-up Findings

F001: `move_backlog_entry_to_active()` and `_pull_bullet_from_backlog_section()` in
`agent/runtime/core/story_state.py` move only an entry's first line, so a multi-line queue entry's
indented `dependency note:`/`backlog entry:` continuation lines are left behind. This story's two
were orphaned under `## To Do` with no row above them (removed by hand while updating the entry).

## Follow-up Findings Disposition
F001: DEFERRED ? later milestone; planner runtime backlog parsing is outside the Phase 5 frontend migration scope, and no supplied Phase 5 requirement authorizes it.
