## Story ID

STORY-DOM-007

## Title

Characterization test: bound/soulbound materials are not distinguished from normal owned inventory

## Status

DONE

## Milestone

milestone-00

## Goal

Add a craft-domain-level characterization test capturing the current (non-compliant) behavior described in `docs/KNOWN_PROBLEMS.md` §3.4: the crafting engine has no concept of account-bound or soulbound materials, so an owned quantity that should be off-limits to the currently-selected character (per `docs/DOMAIN_SPEC.md` §11.1) is today consumed exactly like any other owned, tradable material, with no rejection and no distinct `BlockedReason`. This story does **not** implement bound-material handling — it only records current behavior ahead of that (separately blocked, design-dependent) fix, per `docs/ROADMAP.md` Phase 0's own "Add a regression/characterization test for bound-material handling" item and `docs/TEST_STRATEGY.md` §4.2's characterization-test workflow.

## Authoritative Source Documents / Sections

- `docs/DOMAIN_SPEC.md` §11.1 "Bound Materials" (soulbound/account-bound rules — already decided, not an open question).
- `docs/KNOWN_PROBLEMS.md` §3.4 (the confirmed conflict this test characterizes).
- `docs/TEST_STRATEGY.md` §4.2 (characterization tests), §6.7 (Bound Materials test list), §16 (named regression cases), §24 (priority order).
- `docs/ROADMAP.md` §4 "Phase 0 — Build & Test Foundation" (this test is one of that phase's listed, not-yet-done High-Level Stories, and its exit criterion 3).
- `src/main/java/craft/PlanState.java`, `src/main/java/craft/CraftingResolver.java`, `src/test/java/craft/CraftTestFixtures.java`.

## Context

`repo.InventoryRepository` already reads `binding`/`bound_to` columns are present in `account_bank`/`character_items` at the schema level (per `STORY-SYNC-001`'s result), but nothing under `src/main/java/craft/` reads or reacts to binding at all — confirmed by search (`docs/KNOWN_PROBLEMS.md` §3.4). `craft.PlanState` is constructed from a flat `Map<Integer, Integer>` owned-quantity map with no per-unit binding metadata, so the domain engine cannot currently express "this owned quantity is only usable by character X" even in principle. A "selected character" concept would be needed to fix this (tracked as blocked, unwritten work in `agent/stories/BACKLOG.md`'s "Not Yet Written" section) — this story is scoped strictly to documenting current behavior, not building that concept.

## Acceptance Criteria

- A new test (or tests) in `src/test/java/craft/`, using `CraftTestFixtures`, demonstrates that when an owned quantity is set up to represent a soulbound-to-another-character material (per the scenario in `DOMAIN_SPEC.md` §11.1 — "must not be counted as usable inventory for the selected character"), the current resolver/planner still consumes it as ordinary usable owned inventory: no rejection occurs, no reduced/zeroed usable quantity results, and no `BlockedReason` reflecting binding is ever set (because none exists today).
- The test's name and a short comment make clear it is a **characterization test of current, spec-non-compliant behavior**, not a statement that this behavior is correct (`docs/TEST_STRATEGY.md` §4.2, §16 naming style, e.g. `shouldCurrentlyIgnoreSoulboundRestrictionsWhenConsumingOwnedMaterials`).
- The test must not require a live PostgreSQL connection or the "selected character" concept to exist — it operates purely on the existing flat owned-quantity input the domain layer already accepts.
- `./mvnw test` passes, including this new test and the full existing suite.

## Required Tests

- The new characterization test itself (this story's entire deliverable).
- `./mvnw test` — full existing suite must continue to pass unchanged.

## Constraints

- Do not implement soulbound/account-bound filtering, a "selected character" concept, or any change to `PlanState`/`CraftingResolver`/`InventoryRepository` production code in this story — test-only change.
- Do not attempt to resolve the "selected character" design blocker noted in `agent/stories/BACKLOG.md` — that remains explicitly out of scope here.
- Follow `docs/CODING_GUIDELINES.md`.

## Dependencies

None.

## Definition of Done

- [x] New characterization test added under `src/test/java/craft/`, passing, and clearly named/commented as characterizing current (non-compliant) behavior.
- [x] No production code changed.
- [x] `./mvnw test` shows the new test passing alongside the full existing suite.
- [x] `agent/runtime/artifacts/CLAUDE_RESULT.md` filled in.

## Result

Added `src/test/java/craft/CraftingResolverBoundMaterialCharacterizationTest.java` with
`shouldCurrentlyIgnoreSoulboundRestrictionsWhenConsumingOwnedMaterials`. It builds a `PlanState`
whose flat owned-quantity map contains a quantity representing a material soulbound to another
character (per `DOMAIN_SPEC.md` §11.1's scenario), then calls `CraftingResolver.resolveNeed(...)`
requesting exactly that quantity. It asserts the current (non-compliant) behavior: the quantity is
fully consumed from `PlanState.inventory` as ordinary usable stock (`qtyFromInventory` equals the
full request, `state.inventory` decremented to 0), `qtyBlocked` is 0, `BlockedReason.NONE` is set
(no binding-aware reason exists), and `isFullySatisfied()` is true. The class-level Javadoc and
test name both state explicitly that this documents current, spec-non-compliant behavior, not
correct behavior. No production code was touched — this is a test-only change, per the story's
constraints. `./mvnw -DskipITs test` (twice, once quiet then once verbose to confirm the new class
ran): 9 tests run, 0 failures, 0 errors, `BUILD SUCCESS` (8 pre-existing + 1 new). No other stories
or the "selected character" design blocker (`agent/user-decisions/UD-001-selected-character-concept.md`)
were touched.

## Blockers

None.
