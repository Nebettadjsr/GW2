## Story ID

STORY-DOM-002

## Title

Implement discipline-aware, cost-based multiple-recipe selection

## Status

DONE

## Milestone

milestone-00

## Goal

Make `craft.CraftingResolver`'s recipe selection (`firstRecipeFor(...)` and its call sites) follow `DOMAIN_SPEC.md` §30 / DQ-003 when an item has more than one valid producing recipe, instead of returning the first allowed recipe in list order.

## Authoritative Source Documents / Sections

- `docs/DOMAIN_SPEC.md` §30 "Multiple Recipes Producing the Same Item", DQ-003 in §50.
- `docs/KNOWN_PROBLEMS.md` §3.2 — the confirmed conflict.
- `docs/TEST_STRATEGY.md` §6.8.
- The regression test(s) produced by STORY-DOM-001 (`src/test/java/craft/...` — exact file created by that story).
- `src/main/java/craft/CraftingResolver.java`.

## Context

STORY-DOM-001 adds a failing test proving the current gap. This story makes that test (and any other case implied by `DOMAIN_SPEC.md` §30) pass, with the smallest implementation change that does not disturb the already-correct craft-vs-buy selection covered by `craft.CraftingResolverCraftVsBuyTest`.

## Acceptance Criteria

- Given an item with multiple valid candidate recipes: a same-discipline match is preferred; among same-discipline matches, the lowest effective cost is preferred; otherwise the lowest effective cost among all valid candidates is chosen.
- The test(s) added by STORY-DOM-001 pass.
- `craft.CraftingResolverCraftVsBuyTest` still passes unchanged.

## Required Tests

- `./mvnw test` — both STORY-DOM-001's new test(s) and the existing craft-vs-buy test must pass, along with the full suite.

## Constraints

- Smallest possible code change (`CLAUDE.md` "Prefer small, focused changes").
- Do not alter craft-vs-buy selection behavior while making this change — it is already correct and covered by an existing test.
- Do not decouple `craft.*` from `repo.*` as part of this story — that is Phase 2 (`docs/KNOWN_PROBLEMS.md` §4.1, `docs/ROADMAP.md` §6), out of scope here.
- Follow `docs/CODING_GUIDELINES.md`.

## Definition of Done

- [x] STORY-DOM-001's test(s) pass.
- [x] Existing craft-vs-buy test still passes.
- [x] Implementation change is minimal — no unrelated refactor of `CraftingResolver`.
- [x] `agent/CLAUDE_RESULT.md` filled in.

## Result

Implemented. `firstRecipeFor(itemId, ctx, parentDiscipline)` now scores every allowed candidate
recipe for `itemId` with `estimateDirectCraftFloor(recipe, ctx)` (sum of each ingredient's direct
buy cost — the pre-existing craft-floor heuristic already used for the craft-vs-buy pre-filter)
and picks: the lowest-cost candidate whose `disciplinesText` shares a discipline with
`parentDiscipline`, or — if none share a discipline — the lowest-cost candidate overall. The
"parent recipe" is threaded down through a new `parentDiscipline` parameter added to the private
overload of `resolveNeed(...)` and to `tryCraft(...)`; `tryCraft` passes its own recipe's
`disciplinesText` to each child ingredient's `resolveNeed(...)` call. The existing public 5-arg
`resolveNeed(...)` is preserved unchanged (delegates with `parentDiscipline = null`, i.e. "no
parent" — falls straight to the cheapest-overall rule), so the one external caller
(`CraftingResolverPriceUnavailableTest`) needed no change. `resolveOneCraft`'s root call also goes
through this same overload, so the root item (which has no parent recipe) likewise falls to
cheapest-overall — consistent with DOMAIN_SPEC.md §30 rule 3, and not exercised differently by any
existing test since root items in all current tests have exactly one candidate recipe.

One bug was caught and fixed during implementation: the first cost-comparison draft used a strict
`cost < bestCost` check seeded at `Integer.MAX_VALUE`, which silently discarded the *only*
candidate whenever its cost came back as `Integer.MAX_VALUE` (`estimateDirectCraftFloor`'s
sentinel for "an ingredient has no direct buy price" — the normal case for any recipe whose
ingredients are themselves crafted, not bought). Fixed by seeding acceptance on `best == null`
instead of on the cost value.

Verified: `./mvnw -DskipITs test` — 5 tests run, 0 failures, 0 errors, `BUILD SUCCESS`. All 3
`CraftingResolverMultipleRecipeSelectionTest` cases pass, `CraftingResolverCraftVsBuyTest` and
`CraftingResolverPriceUnavailableTest` are unaffected.

## Blockers

None.
