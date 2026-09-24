# User Intervention

## Status

RESOLVED

## Story

STORY-PERF-001

## Reason

Automatic retries exhausted (2/2) without the evaluator confirming completion.

## Claude Response

# CLAUDE_RESULT — STORY-PERF-001

## What changed

Resumed an interrupted attempt on this story (a real-database diagnostic test and temporary
instrumentation were already in place, uncommitted). Measured the real baseline, implemented
two bounded, result-preserving performance fixes, re-measured, and stopped to ask for a
Product Owner decision on the remaining gap — per this story's own acceptance criteria, a
material architecture/correctness trade-off requires the user's decision, not the implementer's
guess.

- **Baseline (real PostgreSQL database, All scope, view defaults):** total backend time 67.39 s,
  of which `craft.CraftingPlanner.evaluateAllCoordinated(...)` alone took 66.30 s (98%). Every
  `repo.*` DB stage combined took under 1 second. Temporary `AtomicLong` counters (added by the
  interrupted attempt, still in `craft/PlanState.java`/`craft/PlannerContext.java`) showed
  4,252,938 `PlanState` deep copies and 2,402,533 `eligibleCharactersFor(...)` calls for one
  All-scope run.
- **Root cause (evidence, not guessed):** `RecipeSimulator.simulatePhase(...)` recomputes the
  full recursive ingredient tree from scratch for up to 250 sequential per-recipe batch
  iterations; `PlanState`'s copy constructor/`copyFrom(...)` deep-copies the entire
  inventory/bound-inventory maps (830 entries on this database) on every one of those millions
  of copies, regardless of call depth.
- **`src/main/java/craft/CraftingResolver.java`:** in `tryCraft(...)`, when exactly one roster
  character is eligible for a craft step, apply that attempt directly to the live `PlanState`
  instead of a speculative trial-copy-then-compare-then-copy-back (a single candidate is always
  "best", so the trial was pure overhead). Multi-candidate behavior is untouched.
- **`src/main/java/craft/CraftingPlanner.java`:** removed the shared, non-thread-safe
  `RecipeSimulator` instance field; `evaluateAll(...)`/`evaluateAllCoordinated(...)` now run the
  (already mutually independent) per-recipe loop via `recipes.parallelStream()`, each recipe
  getting its own fresh `RecipeSimulator`/`CraftingResolver`. `evaluateOneRecipeNew(...)` takes
  the simulator as a parameter instead of reading the removed field.
- **Re-measurement:** extended the existing diagnostic
  (`src/test/java/application/CraftingProfitServiceRealDbPerfIT.java`) to run the identical
  pipeline twice in one JVM (FIRST/cold, REPEAT/warm), satisfying the "first opening and repeat
  openings" baseline requirement. FIRST: 22.43 s total (21.75 s planner). REPEAT: 21.99 s total
  (21.45 s planner). ~3.1x improvement over baseline; FIRST and REPEAT are within 2% of each
  other, so this is a genuine computational cost, not a JIT/cache warm-up artifact.
- Updated `docs/KNOWN_PROBLEMS.md` §7.9 and `docs/CURRENT_ARCHITECTURE.md` §5.1 with the
  measured evidence and the implemented changes; updated the single `BACKLOG.md` entry for this
  story. Full before/after data, root-cause evidence, and the decision request are recorded in
  `agent/stories/STORY-PERF-001-crafting-profit-page-load-budget.md`'s Result section.

## Why

`docs/TARGET_ARCHITECTURE.md` §33 requires the Crafting Profit page to reach
complete-interactive-page in ≤7 seconds on the user's real database, confirmed as a Phase 3
blocker by `ROADMAP.md` and `KNOWN_PROBLEMS.md` §7.9. The two changes made here are safe,
verified-behavior-identical wins available without deciding anything material; closing the
remaining gap needs a decision the story explicitly reserves for the Product Owner.

## Tests run

- Full default `./mvnw test` suite: **43/43 test classes, 0 failures/errors** — including every
  `craft.CraftingResolver*Test` (10 classes), `craft.CraftingPlannerHeuristicSkipCharacterizationTest`,
  `application.CraftingProfitServiceTest` (8 tests), `CraftingProfitCoordinatedScopeTest`,
  `CraftingBlockedRowsTest`, `repo.CharacterSelectionCraftingPlanIntegrationTest` — confirming
  the two optimizations produce byte-identical results to before.
- `application.CraftingProfitServiceRealDbPerfIT` (excluded from the default suite by its `IT`
  suffix; not pass/fail, evidence-gathering only): run before and after the code changes,
  against the developer's real PostgreSQL database, producing the baseline/after numbers above.
- No new correctness tests were added because no domain rule/output changed; the required
  regression coverage is the existing suite proving that, which it does.

## Remaining uncertainty

- **The ≤7-second acceptance limit is not met.** Backend time is still ~22 s, over 3x the
  budget. This is reported as a partial result, not a completed story.
- **Stopped to ask, not guessed:** closing the gap further requires either restructuring
  `PlanState`'s full-map-copy-per-attempt design (larger, more surgical, more certainly
  behavior-preserving) or replacing `RecipeSimulator`'s linear 250-iteration batch loop with a
  binary-search variant (bigger potential win, but correctness depends on an unproven
  monotonicity assumption across all settings combinations). Both are flagged in the story's
  Result section as material architecture/correctness trade-offs needing the Product Owner's
  explicit decision before implementation, per this story's own acceptance criteria.
- The Product Owner's verbal "~20 seconds" report matches neither the 67.4 s baseline nor the
  ~22 s post-fix time; recorded as an open, unresolved discrepancy.
- No genuine UI-level (TestFX/real view) timing was run, since ~22 s of backend-only time
  already exceeds the 7 s full-page budget by 3x+ and a UI measurement could only add time, not
  change that conclusion.
- Temporary debug instrumentation (`PlanState.DEBUG_COPY_COUNT`,
  `PlannerContext.DEBUG_ELIGIBLE_CALLS`) and the new diagnostic IT test remain in the working
  tree, intentionally left in place pending the Product Owner's decision on next steps (both are
  documented as "known temporary artifacts" in the story file).
- Explicit dated user confirmation has not been obtained (and could not be, since the target
  isn't met yet) — the acceptance gate stays open; this story does not authorize Phase 3
  closure or Phase 4 start.

## User Resolution

ACCEPTED — 2026-09-23

## Resolution Notes

The Product Owner accepts the completed STORY-PERF-001 result.

The real UI navigation-to-complete-page measurements now meet the required ≤7 second performance target, including the measured first opening after startup and repeat openings. The performance issue is considered resolved.

The implemented behavior-preserving optimizations, including the PlanState undo-journal redesign, are accepted.

The reported `missingToBuy` iteration-order difference is also acceptable. No defined ordering for the truncated first-two-items presentation is required at this time, and no follow-up work is requested for it.

STORY-PERF-001 may be completed and its performance gate closed. Normal roadmap execution may resume according to the existing planner/roadmap process. This acceptance does not add or reorder any other roadmap work.