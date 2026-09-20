## Story ID

STORY-DOM-015

## Title

Preserve crafting view selections and verify character-sensitive results

## Status

BLOCKED

## Milestone

milestone-01

## Goal

Preserve valid session selections during data refreshes in both crafting views and verify that individual-character changes reach displayed calculations, as required by DOMAIN_SPEC.md §2.2.1.

## Authoritative Source Documents / Sections

- Supplied Phase 1 objective: agreement with DOMAIN_SPEC.md in the current structure.
- docs/DOMAIN_SPEC.md §2.2.1 and §11.1.
- STORY-DOM-012, Acceptance Criteria and Result (incomplete interactive verification).
- agent/user-decisions/UD-004-all-characters-calculation-semantics.md (RESOLVED).

## Context

STORY-DOM-012 records a selection listener and preservation of character choice, but explicitly lacks interactive verification. The product observations do not establish that the selector is defective; establish behavior with different relevant bound-material data.

## Acceptance Criteria

- Trace individual-character selection through each view, controller, domain calculation and displayed list using characters whose relevant bound inventory differs. Fix only demonstrated defects; if correct, record evidence without unnecessary behavior changes.
- Automatic and manual data refreshes preserve valid character, sort mode, sort direction and comparable existing filter/sort selections in both views for the current session.
- Initial creation uses authoritative defaults. Removed options fall back gracefully; invalid selections are not retained.
- Updated results use current selections rather than resetting controls to defaults; verify visible recalculation when relevant bound-material differences affect the plan.
- Keep both views consistent without introducing persisted settings. Record the actual cause of any confirmed refresh or selection defect and the evidence for its correction.

## Required Tests

- For each demonstrated defect, add a failing regression before the fix at the narrowest practical boundary; verify refresh state preservation and invalid-option fallback.
- Verify both flows with distinct relevant bound-material data, including a control where identical results are valid; exercise selector-to-displayed-result behavior.
- Use STORY-UI-001's reusable capability to exercise manual and automatic refresh after changing sort mode, direction, character and existing comparable filters in the real views. Automate selector-to-displayed-result checks, initial defaults and invalid-option fallback. The existing Result's source inspection and repository/planner tests do not substitute for these UI checks; record actual executed evidence and any limitations.
- Use existing PostgreSQL integration coverage for binding-dependent data semantics, extending it if the fix changes that path; run relevant regressions and the project suite.

## Constraints

Keep work in the current structure and limited to the specified Phase 1 behavior. No performance optimization, broad restructuring, new framework, or invented domain rules. Follow the failing-test-first bug-fix workflow; do not claim unexecuted verification.

## Dependencies

STORY-DOM-013; STORY-DOM-014 (establishes final Profit selector semantics); STORY-DOM-012 (DONE).

## Definition of Done

Acceptance criteria met, required verification completed and recorded in Result, relevant regression suite passing, and authoritative documentation consistent with the implemented behavior.

## Result

Verified the parts of this story that do not depend on `STORY-DOM-014`'s still-undelivered `All
characters` selector semantics; found no refresh/selection defect in either view. One genuine gap
against `DOMAIN_SPEC.md` §2.2.1 was confirmed, but it is squarely `STORY-DOM-014`'s scope (a listed
Dependency of this story) rather than a demonstrated bug in the code this story owns — see Blockers.

**Individual-character selection reaches the domain calculation and displayed results (AC1, AC4):**

- Traced the path by direct reading: `CraftingProfitView`'s `characterBox` value listener
  (`CraftingProfitView.java:470`) calls `reloadTable.run()`, which reads
  `characterBox.getValue()` (line 355) and passes it to `CraftingProfitController.reload(...)`
  (line 359). The controller (`CraftingProfitController.java:98-115`) calls
  `InventoryRepository.loadOwnedInventoryForCharacter(selectedCharacterName)` when a character is
  selected, splits the returned `OwnedQuantity` map into sellable/bound maps, and passes both into
  `CraftingPlanner.evaluateAll(recipes, sellableInv, boundInv, tp, settings, allowedRecipeIds)`
  (`STORY-DOM-012`'s overload), whose `CraftResult`s become the displayed `UiRow`s. The identical
  pattern exists in `CraftingDiscoveryView`/`CraftingDiscoveryController`. This confirms the wiring
  `STORY-DOM-012` left unverified is intact and unchanged.
- Added `repo.CharacterSelectionCraftingPlanIntegrationTest` (new Layer 2 PostgreSQL integration
  test, `docs/TEST_STRATEGY.md` §31.2), the specific piece `STORY-DOM-012` explicitly left as
  unexecuted interactive verification: it creates two characters against a disposable schema, gives
  one of them the 5 soulbound units a test recipe needs (the other owns none), reproduces
  `CraftingProfitController.reload(...)`'s exact sellable/bound split from real
  `InventoryRepository.loadOwnedInventoryForCharacter(...)` rows, and feeds both split maps into the
  same production `CraftingPlanner.evaluateAll(...)` overload the controllers call. Result: the
  character owning the soulbound material gets `craftableCount=1` at zero buy cost and zero
  opportunity cost (bound materials are free and never bought, per §11.1); the other character gets
  `craftableCount=0` (the item has no Trading Post price and none is owned, so the recipe is
  correctly blocked rather than silently craftable) — a real, demonstrated difference driven purely
  by character selection. A second, ordinary-tradable-ingredient recipe that neither character owns
  produces byte-identical `craftableCount`/`buyCostCopper` for both characters, serving as the
  required control case proving identical results alone are not evidence of a defect. `CraftingPlanner`/
  `CraftingProfitController`/`CraftingDiscoveryController` were not modified — no defect was found in
  this path, so none was fixed, per this story's "fix only demonstrated defects" constraint.
- A full `CraftingProfitController`-level (rather than repository+planner-level) integration test
  was considered and rejected: the controller opens its own connection via `repo.Db.open()` against
  the developer's configured database rather than an injectable connection, so pointing it at a
  disposable test schema would violate `TEST_STRATEGY.md` §9's "must not require the developer's
  normal database" rule, and no controller/view test layer exists in this repository to extend
  (confirmed by `STORY-DOM-012`'s own Result). The new test instead calls the same two production
  methods, in the same order, with the same real-Postgres-backed data shape, which is the closest
  available proof without a live UI session.

**Refresh preserves valid character/sort/filter selections in both views (AC2, AC3 partial, AC4):**

- Confirmed by direct reading that neither view's auto-refresh scheduler nor its manual "Refresh"
  button ever calls `reloadCharacterChoices`/`reloadDisciplineChoices` — those `Runnable`s are only
  invoked once, at `show(...)` startup (`CraftingProfitView.java:793-794`,
  `CraftingDiscoveryView.java:575-576`; confirmed via a repository-wide search for both call sites).
  Both refresh paths (auto-refresh timers and the manual `Refresh`/`Refresh Trade Post Prices`
  buttons) call only `reloadTable.run()`, which reads the combo boxes'/checkboxes'/search field's
  *current* values without touching their contents or selection. This means character selection,
  discipline selection, sort mode, "use own mats"/"buy missing mats" checkboxes, buy/sell mode radio
  buttons, and the search text are all structurally immune to being reset by a refresh in either
  view today — there is nothing in the refresh code path capable of resetting them. No regression
  test was added for this because there is no reachable code path that could currently regress it;
  a test asserting "refresh doesn't call X" would only be testing the absence of a call, which the
  repository-wide search already establishes more directly than a test could.
- `characterBox`'s own reload logic (`reloadCharacterChoices`, used only at startup) already
  implements exactly the "removed options fall back gracefully, invalid selections are not
  retained" rule from AC3: it preserves the previous selection only if it still exists in the
  reloaded name list, and falls back to the first available character otherwise (both
  `CraftingProfitView.java:190-196` and the identical logic in `CraftingDiscoveryView.java:160-166`).
  This logic is unreached today (nothing calls it more than once per view instance), so this is a
  code-reading confirmation that the fallback rule is implemented correctly if/when it does run
  (e.g. a future story that re-syncs the character list mid-session), not a claim that it was
  exercised this session.
- `disciplineBox`'s reload logic (`reloadDisciplineChoices`) does **not** implement the same
  "preserve previous selection" fallback — it unconditionally calls `selectFirst()` every time it
  runs. This is a latent inconsistency with `characterBox`'s fallback logic, but it is not a
  demonstrated defect: this code path is also only ever invoked once at startup in both views (same
  search as above), so it cannot currently reset a discipline selection during any refresh. Per this
  story's "fix only demonstrated defects" constraint, this was left unchanged and is not being
  reported as a new `KNOWN_PROBLEMS.md` entry, since it has no observable effect under any currently
  reachable code path.
- Manual verification limitation (same as `STORY-DOM-012`'s Result): confirmed `./mvnw -o compile`
  and the full `./mvnw -o test` suite are clean, then launched `./mvnw -o javafx:run` and confirmed
  it reaches the running state with no exceptions (only the expected JavaFX native-access warnings).
  This session has no GUI-automation/screenshot tool, so the actual interactive click-through
  (change character/sort/filter, trigger a refresh, visually confirm the selection widgets keep
  their value and the table recalculates) was not performed and is not claimed as verified — the
  conclusions above rest on direct code reading (which combo/refresh code paths exist and call what)
  plus the new automated test proving the calculation itself responds to character selection, not on
  an executed UI session.

**Authoritative defaults (AC3) — confirmed gap, not fixed here:**

- Crafting Discovery's existing individual-character default (first synced character) matches its
  own unchanged authoritative behavior per `UD-004` point 2 — no defect.
- Crafting Profit's `characterBox` has **no** `All characters` entry at all today — it only lists
  individual synced character names and defaults to the first one (`UD-001`'s default). Per the
  RESOLVED `UD-004` and `DOMAIN_SPEC.md` §2.2.1, Crafting Profit must offer `All characters` as the
  first entry and default selection. This is a real, confirmed non-conformance, documented as new
  `docs/KNOWN_PROBLEMS.md` §3.7. It was **not fixed** in this story: `STORY-DOM-014` is the story
  explicitly scoped (and listed as this story's own Dependency) to add that entry together with the
  coordinated multi-character planning logic (`UD-004`'s point 1: per-step character eligibility,
  transferable intermediates, soulbound-per-step enforcement) it requires to mean anything. Adding
  only the selector entry here, without that planning logic, would make `All characters` selectable
  while silently computing something other than what `UD-004` defines it to mean — a worse outcome
  than leaving it absent. `STORY-DOM-013` (blocked/unavailable row exposure, also a listed Dependency)
  was checked and found **not** to actually block this story's verification work: the test scenario
  above deliberately used a recipe that stays visible/craftable for at least one character rather
  than relying on a blocked row being surfaced, so `STORY-DOM-013`'s still-open controller-level row
  filtering (`KNOWN_PROBLEMS.md` §3.5) did not need to be resolved first.

**Tests run:** `./mvnw -o test` — 50 tests, 0 failures, 0 errors, `BUILD SUCCESS` (49 pre-existing +
1 new `repo.CharacterSelectionCraftingPlanIntegrationTest`, run both in isolation and as part of the
full suite). `./mvnw -o compile` clean. `./mvnw -o javafx:run` reaches running state with no
exceptions.

**Why this story is BLOCKED rather than DONE:** its own Dependencies field lists `STORY-DOM-014`
("establishes final Profit selector semantics") as a prerequisite, and `STORY-DOM-014` is still
TODO. AC3 ("Initial creation uses authoritative defaults") cannot be met for Crafting Profit until
that story lands, since the authoritative default (`All characters`, per `UD-004`) does not exist in
the codebase yet. Implementing it here would mean silently absorbing `STORY-DOM-014`'s scope into
this story, which `CLAUDE.md` directs against ("do not broaden scope automatically," "never activate
the next story"). All other acceptance criteria (individual-character tracing/verification, refresh
preservation in both views, Discovery's default, view consistency for everything except the pending
`All characters` entry) are verified and met, with evidence above and in the new test/documentation.

## Blockers

Blocked on `STORY-DOM-014` (TODO) delivering Crafting Profit's `All characters` selector entry and
its required coordinated multi-character planning logic, per the RESOLVED `UD-004` and
`DOMAIN_SPEC.md` §2.2.1. This story cannot reach its Definition of Done (all acceptance criteria met)
until that dependency lands; re-run this story's verification against Crafting Profit's `All
characters` mode once `STORY-DOM-014` is DONE. Tracked as `docs/KNOWN_PROBLEMS.md` §3.7.
