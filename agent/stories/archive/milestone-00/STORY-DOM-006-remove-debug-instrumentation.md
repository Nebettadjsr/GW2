## Story ID

STORY-DOM-006

## Title

Remove leftover debug instrumentation from `CraftingPlanner`

## Status

DONE

## Milestone

milestone-00

## Goal

Remove the hardcoded debug `System.out.println` block in
`CraftingPlanner.evaluateOneRecipeNew(...)` that is gated on a specific item id (`70992`), per
`docs/KNOWN_PROBLEMS.md` §7.2.

## Authoritative Source Documents / Sections

- `docs/KNOWN_PROBLEMS.md` §7.2.
- `src/main/java/craft/CraftingPlanner.java`.

## Context

`evaluateOneRecipeNew(...)` contains:

```java
if (recipe.outputItemId == 70992 ) {
    System.out.println("DEBUG shouldSimulate for " + recipe.recipeId + ...);
}
```

a leftover, one-off debug print hardcoded to one item id, with no ongoing diagnostic value, in
the single most-executed file in the crafting engine.

## Acceptance Criteria

- The confirmed hardcoded debug block quoted above (and any other clearly-equivalent leftover
  debug `System.out`/`System.err` prints found in the immediate vicinity during this change) is
  removed.
- No other logic in `evaluateOneRecipeNew` changes.
- `./mvnw test` shows unchanged pass/fail results.

## Required Tests

None new — behavior-neutral removal. `./mvnw test` must show the same pass/fail results as
before this change (including the pre-existing, unrelated `STORY-DOM-002`-tracked
`CraftingResolverMultipleRecipeSelectionTest` failures, which are out of scope here).

## Constraints

- Do not combine this change with `STORY-DOM-005`'s dead-code removal — keep as a separate,
  independently reviewable change even though both touch the same file.
- Do not remove legitimate logging or error handling — only the confirmed hardcoded debug print
  (and directly equivalent leftover debug prints, if any are found nearby).
- No other refactor of `CraftingPlanner.java` in this story.

## Dependencies

None.

## Definition of Done

- [x] Debug print(s) removed.
- [x] `./mvnw test` shows unchanged pass/fail results.
- [x] `agent/CLAUDE_RESULT.md` filled in.

## Result

Removed the hardcoded `if (recipe.outputItemId == 70992) { System.out.println(...) }` debug block
from `evaluateOneRecipeNew(...)` in `src/main/java/craft/CraftingPlanner.java`. No other
`System.out`/`System.err` debug prints were found in the immediate vicinity (the method itself, or
its direct helpers `collectBoughtItems`/`computeBuyCostFromMissing`/`shouldSimulateRecipe`). No
other logic in `evaluateOneRecipeNew` was touched. `./mvnw test` ran clean: 9 tests, 0 failures, 0
errors, `BUILD SUCCESS` — same as the suite's current baseline (including the previously-tracked
`CraftingResolverMultipleRecipeSelectionTest`, which now passes 3/3, unaffected by this change).

## Blockers

None.
