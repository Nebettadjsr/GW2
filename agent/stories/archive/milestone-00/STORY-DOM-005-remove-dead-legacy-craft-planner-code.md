## Story ID

STORY-DOM-005

## Title

Remove dead legacy craft-vs-buy code path in `CraftingPlanner`

## Status

DONE

## Milestone

milestone-00

## Goal

Delete the confirmed-unreachable `canCraft(...)`, `simulateCraft(...)`, `obtain(...)` methods
and the private `PlanRun` class from `craft/CraftingPlanner.java`, per
`docs/KNOWN_PROBLEMS.md` §7.1. This code is a superseded "v1: first recipe" implementation with
no callers, replaced by `CraftingResolver`/`RecipeSimulator`.

## Authoritative Source Documents / Sections

- `docs/KNOWN_PROBLEMS.md` §7.1.
- `docs/ROADMAP.md` Phase 1 Exit Criteria: "The dead legacy craft-vs-buy code path is removed once confirmed unreachable."
- `src/main/java/craft/CraftingPlanner.java`.

## Context

`docs/KNOWN_PROBLEMS.md` §7.1 documents that `canCraft`/`simulateCraft`/`obtain`/`PlanRun` have
no external callers (confirmed at the time by a full-file read and a repository-wide search),
are not used by `evaluateAll`/`evaluateOneRecipeNew` (the active path), and carry a comment
identifying them as an older implementation (`// v1: first recipe`).

## Acceptance Criteria

- Re-confirm via a repository-wide search, at execution time, that `canCraft`, `simulateCraft`,
  `obtain`, and `PlanRun` have no callers anywhere under `src/` — do not rely solely on the
  prior finding in `KNOWN_PROBLEMS.md`, since the codebase may have changed since that document
  was written.
- If re-verification finds an actual caller (i.e. the code is no longer dead, or the document
  was wrong), stop and report this instead of deleting anything — treat it as a materially
  different situation than assumed, per `CLAUDE.md`'s "report rather than improvise" rule.
- Otherwise, delete the confirmed-dead methods and class.
- No behavior change to any currently-reachable code path.

## Required Tests

None new — this is dead-code removal, not a behavior change. `./mvnw test` must show the same
pass/fail results as before this change (including the pre-existing, unrelated
`STORY-DOM-002`-tracked `CraftingResolverMultipleRecipeSelectionTest` failures, which are out of
scope here).

## Constraints

- Do not touch the active `evaluateAll`/`evaluateOneRecipeNew` path.
- Do not combine this change with `STORY-DOM-006`'s debug-instrumentation removal — keep as
  separate, independently reviewable changes even though both touch the same file
  (`CLAUDE.md`: do not combine unrelated refactoring/cleanup).
- Do not perform any other cleanup or refactor of `CraftingPlanner.java` in this story beyond
  the confirmed-dead removal.

## Dependencies

None.

## Definition of Done

- [x] Dead code re-confirmed unreachable at execution time and removed.
- [x] `./mvnw test` shows unchanged pass/fail results.
- [x] `agent/runtime/artifacts/CLAUDE_RESULT.md` filled in.

## Result

Re-confirmed via `grep`-equivalent repository-wide search under `src/` that `canCraft(`,
`simulateCraft(`, `obtain(`, and `PlanRun` had no matches outside `CraftingPlanner.java` itself
(all matches were the dead methods calling each other). Deleted `canCraft(...)`,
`simulateCraft(...)`, `obtain(...)`, the private `PlanRun` class, and the now-orphaned private
`ceilDiv(...)` helper (only ever called from the deleted `obtain(...)`; unrelated to the
`ceilDiv` helpers in `CraftingResolver`/`RecipeTreeBuilder`, which are separate private methods
in their own classes) from `craft/CraftingPlanner.java`. Also updated the class-level Javadoc,
which described the deleted v1 approach by name (`canCraft(n)`, "pick the FIRST recipe") — left
as-is it would have been a dangling reference to removed code, so it was trimmed to the still-accurate
3-line summary of the active behavior; no other comments or logic in the file were touched.
`Node` and `PlanState` (used by the deleted code) remain unchanged and in place, since both are
still used by `CraftingResolver`, `RecipeSimulator`, `RecipeTreeBuilder`, `ResolvedNeedMapper`, and
tests.

Verified with a full clean rebuild: `./mvnw -DskipITs clean test` — 5 tests run, 0 failures, 0
errors, `BUILD SUCCESS`, matching the pre-change baseline exactly (same 3 test classes, same
counts). The active `evaluateAll`/`evaluateOneRecipeNew` path was not touched.

## Blockers

None.
