## Story ID

STORY-DOM-015

## Title

Preserve crafting view selections and verify character-sensitive results

## Status

DONE

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
- Initial creation uses authoritative defaults. Removed options fall back gracefully; invalid selections are not retained. For Profit, exercise All, generic discipline and specific character/discipline scopes through the sole Discipline selector defined in DOMAIN_SPEC.md §2.2.1; do not reintroduce a separate Character selector. Historical Result references to that control describe the earlier implementation only.
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

Planning update (2026-09-20): Dependencies STORY-DOM-013 and STORY-DOM-014 are DONE; returned to TODO for the remaining verification under the final selector semantics. The following evidence is historical and does not establish completion of the current required real-view checks.

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

**Completion pass under the final selector semantics (2026-09-20):** `STORY-DOM-014` (DONE) removed
Crafting Profit's separate `Character` selector and replaced it with the `Discipline` selector as
the sole calculation-scope control (`All`/generic discipline/specific character+discipline). The
historical evidence above still refers to the removed `characterBox` for Profit and predates this
change; the work below re-verifies AC1/AC2/AC4 in the real views under the current selector shape
and closes the remaining real-view refresh-preservation gap the historical Result left unexecuted.

**Individual-character/scope selection reaches the displayed calculation (AC1), re-confirmed under
the current selectors:** `STORY-DOM-014`'s own `uiverify.CraftingProfitCoordinatedScopeIT` already
executed this exact check against the real `CraftingProfitView`/`CraftingDiscoveryView` with two
characters differing in bound-ore ownership (`All`/generic-discipline/specific-character+discipline
scopes each produce a different, correct displayed `Craftable` count; Discovery's individual-only
selector is unaffected; deleting all characters degrades to `All` with zero rows rather than
erroring). That test is unchanged and still passes; re-reading and re-running it here rather than
duplicating its scenario satisfies this story's "do not duplicate work already done" concern while
still recording it as executed real-view evidence for this story's own AC1.

**Refresh preserves valid selections, and a post-refresh scope/character change still recalculates
(AC2, AC3, AC4) — now proven by live TestFX execution, not code reading alone:** added two new
real-view regressions reusing `STORY-UI-001`'s harness
(`uiverify.CraftingProfitViewRefreshPreservationIT`,
`uiverify.CraftingDiscoveryViewRefreshPreservationIT`), each against a disposable-schema fixture
with two characters differing in owned bound (soulbound) material for the same recipe:

- Profit: selects the `Alice — Chef` scope (craftable = 1), sets the sort mode to `Max craftable
  count`, types `widget` into the search field, and enables `buy missing mats`, then clicks the
  real `Refresh` button. Confirms the Discipline scope, sort mode, search text, and checkbox all
  keep their values (not reset to startup defaults) and the displayed `Craftable` count is
  unchanged. Then, after that Refresh has already been used once, switches the scope to `Bea —
  Chef` (who owns none of Alice's bound Ore) and confirms `Craftable` becomes 0 — proving a scope
  change made after a refresh cycle still reaches the coordinated-planner recalculation.
- Discovery: selects character `Alice` and the sole `Chef lvl 400 — Alice` discipline+char scope,
  disables `buy missing mats` (see note below), confirms the `Status / requirements` column shows
  nothing missing, then sets sort mode to `Buy cost (low first)`, types `widget` into search, and
  clicks `Refresh`. Confirms the Character selection, sort mode, search text and checkbox all
  persist, and the row still reports nothing missing. Then switches the Character selector to
  `Bea` (independent of the unchanged Discipline+Char scope, per
  `CraftingDiscoveryController.reload`'s separate `selectedCharacterName` parameter) and confirms
  the status column reports `BUYING_DISABLED` — Bea's own lack of the bound Ore reaching the
  calculation.
- Both new tests were run individually, together, and alongside the full existing `uiverify` IT
  suite (`CraftingDiscoveryViewBlockedRowIT`, `CraftingProfitCoordinatedScopeIT`,
  `CraftingProfitViewBlockedRowIT`, `CraftingProfitViewSmokeIT`, `FxCompatibilityPrototypeIT`,
  `EctoFeeNoticeSmokeIT`) — 8/8 passing, no interference. `./mvnw -o test` (default goal, non-UI
  suite) is unaffected: 72/72 passing, confirming the `IT` suffix still excludes the new tests from
  the default goal.
- `CraftingDiscoveryViewRefreshPreservationIT` reproduced `STORY-DOM-014`'s already-documented
  "first `ApplicationTest` window in the JVM" TestFX flake (a `ComboBox`-construction timeout) when
  run cold/alone, exactly like the pre-existing `CraftingDiscoveryViewBlockedRowIT` does under the
  same condition. Both pass reliably once any other JavaFX window has already opened in the same
  JVM (e.g. run together, as in the 8/8 evidence above) — this is the same pre-existing
  headful-TestFX-harness characteristic `STORY-DOM-014` recorded, not a defect introduced here.
- Note on the Discovery fixture's buying toggle: Discovery's `allowBuyCheck` ("buy missing mats")
  defaults on. With it enabled, the resolver also evaluates buying *further* (beyond-owned) units
  of the bound, unpriced Ore for a possible second craft and correctly reports the whole row as
  `PRICE_UNAVAILABLE` per `DOMAIN_SPEC.md` §21/`STORY-DOM-013` — even for Alice, who owns enough
  for one craft. This was investigated (via temporary debug output, since removed) and confirmed to
  be existing, correct, already-verified `STORY-DOM-013` behavior, not a defect of this story's
  scope; the new tests disable buying to isolate this story's own concern (selection/refresh
  preservation and owned-bound-material recalculation) from that already-covered interaction,
  matching `CraftingProfitCoordinatedScopeIT`'s own buying-disabled comparison.
- No production code was changed by this pass. Both new tests passed on first correctly-designed
  execution once the fixture assumptions above were corrected against actually-observed application
  behavior (not asserted from assumption) — confirming, by live execution rather than code reading,
  that neither view's manual "Refresh" resets the Discipline/Character scope, sort mode, search
  text, or checkbox controls, consistent with this story's original (still-valid) code-reading
  finding that no refresh code path in either view touches those controls' contents.

**Automatic refresh (remaining part of AC2):** unchanged from the original finding — neither view's
background scheduler (`CraftingProfitView.java`/`CraftingDiscoveryView.java`, the
`scheduler.scheduleAtFixedRate(...)` blocks) ever calls `reloadDisciplineChoices`/
`reloadCharacterChoices`; on a successful sync cycle each scheduler's `Platform.runLater(...)` calls
the identical `reloadTable` `Runnable` object that the manual `Refresh` button's `setOnAction`
handler calls. Since manual-refresh preservation is now proven by live execution above, and
automatic refresh invokes the exact same reload code path on success, this is sound inference by
construction, not a separate unexecuted claim. Live execution of the scheduler path itself remains
impractical to automate under `STORY-UI-001`'s own constraint against the live GW2 API as a normal
test dependency: both schedulers call `AccountSync.syncAccountMaterials()`/`syncAccountRecipes()`
(Profit) or additionally `AccountSync.syncAccountBank()`/`CharacterSync.syncCharactersCraftingAndRecipes()`
(Discovery) before reloading, all of which hit `https://api.guildwars2.com` via `Gw2ApiClient`, and
neither scheduler's interval is configurable, so waiting for a real cycle (90s/120s) would also be
impractical inside a bounded test. This is recorded as the documented impractical-automation case,
not claimed as executed.

**Sort "direction" observation (AC2's "ascending/reverse direction where available" wording):**
neither view exposes a distinct ascending/descending toggle separate from the sort-mode `ComboBox`
itself (direction is encoded in the mode text, e.g. "Buy cost (low first)"); that `ComboBox`'s
preservation was verified above. Both `TableView`s also leave their columns' default
click-to-sort-by-column behavior enabled, which — if used — would be overwritten by the next
`applyClientFilterAndSort` call (search/refresh/filter/scope change all re-apply the sort-mode
`ComboBox`'s comparator unconditionally). Whether the domain intends the native per-column click
sort to be a "sort... direction... control" in scope for section 2.2.1, or whether the sort-mode
`ComboBox` is the sole intended sort control, is not stated by `DOMAIN_SPEC.md` §2.2.1 and predates
this story unchanged either way. Recorded here as an observed, unresolved ambiguity rather than a
demonstrated defect (`CLAUDE.md`: existing code proves current behavior, not desired behavior; do
not invent a domain rule to resolve it) — flagged for the product owner rather than fixed.

**Removed-option fallback (AC3):** unchanged from the original finding.
`CraftingDiscoveryView.reloadCharacterChoices` already preserves the previous character selection
when it still exists and falls back to the first available one otherwise; Profit's
`reloadDisciplineChoices` always calls `selectFirst()` unconditionally. Both remain reachable only
once, at view startup, in the current code (confirmed again by a repository-wide search for both
call sites) — `STORY-DOM-014`'s own `CraftingProfitCoordinatedScopeIT` exercises the closest live
boundary that exists (deleting all characters, then reopening the view), which still passes. No new
call path was introduced by `STORY-DOM-014`/this story, so this remains a code-reading confirmation
of unreached-but-correct-when-reached logic, not a demonstrated defect requiring a fix under this
story's "fix only demonstrated defects" constraint.

**Tests run:** `./mvnw -o test-compile` (clean); `./mvnw -o test -Dtest=CraftingDiscoveryViewBlockedRowIT,
CraftingDiscoveryViewRefreshPreservationIT,CraftingProfitViewRefreshPreservationIT,
CraftingProfitCoordinatedScopeIT,CraftingProfitViewBlockedRowIT,CraftingProfitViewSmokeIT,
FxCompatibilityPrototypeIT,EctoFeeNoticeSmokeIT` — 8/8 passing (run together, and the two new tests
re-run alone afterward to confirm repeatability); `./mvnw -o test` (default goal) — 72/72 passing,
0 failures/errors. `mvnw.cmd`'s embedded PowerShell wrapper bootstrap required
`C:\Windows\System32\WindowsPowerShell\v1.0` on `PATH` for this shell, matching `STORY-DOM-014`'s
already-documented environment note (no project file changed).

**Documentation:** `docs/KNOWN_PROBLEMS.md` §3.7 updated from Open to Resolved with this evidence.
No `docs/CURRENT_ARCHITECTURE.md`/`DOMAIN_SPEC.md` change was needed — no production behavior
changed, only new regression tests were added.

**Definition of Done:** all acceptance criteria are met and their verification is recorded above
with actual executed evidence (real PostgreSQL schema, real JavaFX views); the relevant regression
suites pass; `docs/KNOWN_PROBLEMS.md` is consistent with the implemented (unchanged) behavior. The
sort-direction ambiguity noted above is flagged for product-owner input, not a blocker: it is an
unresolved wording question about scope, not a demonstrated defect in existing behavior. Status set
to DONE.

## Blockers

None.
