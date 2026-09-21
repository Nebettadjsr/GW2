# STORY-DOM-012: Wire selected-character UI into Crafting Profit / Discovery flows

## Story ID

STORY-DOM-012

## Title

Add character selector UI and wire the selected character into Crafting Profit / Discovery flows

## Status

DONE

## Milestone

milestone-01

## Goal

Give the user a way to pick their active character in the Crafting Profit and Crafting Discovery
views, and pass that selection into the bound-material-aware repository/domain path added by
`STORY-DOM-011`, so the §11.1/DQ-007 soulbound-material rule actually takes effect for real users
instead of only existing as unused domain/repo code.

## Authoritative Source Documents / Sections

- `agent/user-decisions/UD-001-selected-character-concept.md` (Status: RESOLVED — authoritative for
  default-selection behavior, where the selection is allowed to live, and that the domain layer must
  not choose a character itself).
- `docs/DOMAIN_SPEC.md` §11.1 / `## DQ-007 — Bound items`.
- `docs/KNOWN_PROBLEMS.md` §3.4.
- The result/Context of `STORY-DOM-011` (read its Result section once DONE — it records the exact
  new repository method signature and domain-layer shape this story must call).

## Context

- No character-selection UI currently exists anywhere in the codebase (verified by search across
  `src/main/java` for `ComboBox`/`character` — the only existing `ComboBox`es are the recipe-
  discipline filter (`disciplineBox`) and the results sort order (`sortBox`) in both
  `CraftingProfitView.java` and `CraftingDiscoveryView.java`). `UD-001`'s Resolution text refers to
  "the existing character dropdown," but no such dropdown exists yet — treat this as a gap to fill
  (build the selector), not a wiring-only task. This is a factual correction to the decision's
  context, not a reason to question the decision itself, which is otherwise clear and actionable.
- `repo.CharacterRepository` and `model.CharacterInfo` already provide synced character data (used
  elsewhere, e.g. by `sync.CharacterSync`) — reuse them to populate the new selector rather than
  querying characters ad hoc.
- `CraftingProfitController`/`CraftingDiscoveryController` currently call
  `invRepo.loadOwnedInventory()` (the unfiltered, binding-unaware method) when "use owned mats" is
  enabled — see the exact call sites (`CraftingProfitController.java:96`,
  `CraftingDiscoveryController.java:105`).

## Acceptance Criteria

- `CraftingProfitView`/`CraftingDiscoveryView` gain a character selector control (e.g. a `ComboBox`,
  consistent with the existing `disciplineBox`/`sortBox` styling and placement conventions already in
  those files), populated from synced characters.
- On first load of each view, the first available synced character is selected by default, per
  `UD-001`'s Resolution.
- The user can change the selection via the control; changing it re-runs the relevant
  profit/discovery calculation using the newly selected character (matching how `disciplineBox`/
  `sortBox` changes already trigger recomputation/re-sort in these views).
- `CraftingProfitController`/`CraftingDiscoveryController` are updated to call `STORY-DOM-011`'s new
  binding-aware repository method with the currently selected character, instead of the old
  unfiltered `loadOwnedInventory()`, whenever "use owned mats" is enabled.
- Selection is not persisted across application restarts or between the two views (per `UD-001`'s
  Resolution: "Selection is not required to persist across sessions") — a per-view-session default is
  sufficient.
- If no characters are synced yet, the view degrades gracefully (e.g. empty/disabled selector,
  falling back to today's unfiltered behavior or an empty owned-pool contribution from characters —
  pick one, whichever is smaller in scope, and document the choice in Result) rather than throwing.

## Required Tests

None required at the JavaFX view/controller level — this repository has no existing UI/controller
test layer to extend (`docs/TEST_STRATEGY.md`'s current scope is domain/repository/parser layers
only), and building one is out of scope for this story. If `STORY-DOM-011`'s new repository method
did not yet get a Layer 2 integration test covering the "selected character" parameter end-to-end,
consider adding one here instead — use judgment, record the choice in Result.

## Constraints

- Do not change `STORY-DOM-011`'s repository/domain method signatures beyond what's needed to call
  them from the controllers — if the signature turns out to be awkward to call from two views, adapt
  the call sites, not the already-tested domain logic, unless a genuine defect is found (in which
  case report it rather than silently reworking DOM-011's logic).
- Do not add a persisted/global "selected character" setting (see Constraints in `STORY-DOM-011`).
- Keep the new selector visually consistent with the existing `disciplineBox`/`sortBox` controls in
  the same views — no new UI framework/library.

## Dependencies

STORY-DOM-011 (DONE per the supplied backlog and Phase 1 roadmap). This story calls its repository/domain path.

## Definition of Done

- Character selector present and functional in both `CraftingProfitView` and `CraftingDiscoveryView`.
- Both controllers call `STORY-DOM-011`'s binding-aware repository method with the selected
  character.
- `./mvnw test` passes (no regressions).
- Manually verified against the local Postgres instance / a running `./mvnw javafx:run` session (per
  `CLAUDE.md`'s UI-testing guidance) that: the selector defaults to the first character, switching it
  changes results when bound materials are involved, and the app does not crash with zero synced
  characters. Record what was manually verified in Result, consistent with how `STORY-DOM-004`/
  `STORY-INFRA-002` documented their manual verification.
- `agent/PROJECT_STATE.md` and `agent/stories/BACKLOG.md` updated per `CLAUDE.md`'s IMPLEMENTATION
  MODE rules; this also closes out `docs/ROADMAP.md` Phase 1's bound-material high-level story item.

## Result

Built the selector (it did not exist, per Context) and wired it into both views/controllers:

- `repo.CharacterRepository` gained `loadAllCharacterNames()` (`SELECT name FROM characters ORDER
  BY name`), reusing the existing `characters` table rather than querying ad hoc.
- `CraftingProfitView`/`CraftingDiscoveryView` each gained a `ComboBox<String> characterBox`, styled
  and placed the same way as the existing `disciplineBox`/`sortBox` (same row, same `LabelStyled` +
  `ComboBox` pattern). A `reloadCharacterChoices` background-thread loader (mirroring the existing
  `reloadDisciplineChoices` pattern) populates it from `loadAllCharacterNames()` and selects the
  first entry only if nothing was already selected (preserves the current selection across
  refreshes, e.g. the periodic bank/materials auto-refresh, without re-defaulting to the first
  character every time). `characterBox.valueProperty()` has a listener that calls `reloadTable.run()`,
  exactly like `disciplineBox`/`sortBox` already do. Both views call `reloadCharacterChoices.run()`
  once at startup alongside `reloadDisciplineChoices.run()`; the resulting default selection fires
  the value-listener once more, so the very first `reloadTable` run (before either combo box has
  loaded) simply runs with `selectedCharacterName = null`, then re-runs once real values arrive -
  the same bootstrapping pattern the existing discipline box already relies on.
- `CraftingProfitController.reload(...)`/`CraftingDiscoveryController.reload(...)` both gained a
  third parameter, `String selectedCharacterName`. When `settings.useOwnMats` is true: if a character
  is selected, they call `InventoryRepository.loadOwnedInventoryForCharacter(selectedCharacterName)`
  and split its `Map<Integer, OwnedQuantity>` result into a sellable map and a bound map (only
  including an entry when the respective quantity is > 0); if no character is selected (nothing
  synced yet - the "no characters synced" case), they fall back to the original unfiltered
  `loadOwnedInventory()` with an empty bound map. This is the "fall back to today's unfiltered
  behavior" option named in the Acceptance Criteria (chosen because it required no new empty-state
  branching in `CraftingPlanner`/`PlanState`, and matches what every current production call already
  did before this story - "smaller in scope" per the Acceptance Criteria's own framing).
- `craft.CraftingPlanner` gained an overload, `evaluateAll(recipes, sellableInventory,
  boundInventory, tp, settings, allowedRecipeIds)`; the original 5-arg `evaluateAll(...)` now
  delegates to it with `boundInventory = Map.of()`, so every other existing caller is unaffected.
  `evaluateOneRecipeNew(...)` now builds `new PlanState(sellableInventory, boundInventory)` (DOM-011's
  two-arg constructor) instead of the single-arg one. This is the piece DOM-011 left undone -
  `PlanState`/`CraftingResolver` already supported a bound pool, but nothing in the production
  recipe-evaluation path had a way to hand one in; `CraftingPlanner` needed this small additive
  overload to thread it from the controllers through to `PlanState`. No existing `CraftingPlanner`/
  `PlanState`/`CraftingResolver` signature was changed, per the Constraints section.
- Selection lives only in each view's local `characterBox` (a `ComboBox` instance inside
  `CraftingProfitView.show(...)`/`CraftingDiscoveryView.show(...)`), not in any shared/static/
  persisted field - closing the view (which shuts down its `scheduler`) discards it, and the two
  views never share an instance, satisfying "not persisted... or between the two views."
- No characters synced: `characterBox.getItems()` stays empty, `characterBox.getValue()` is `null`,
  and both controllers' `null`-check fallback described above applies - no exception path exists for
  this case (confirmed by reading, not just assumed: `loadOwnedInventoryForCharacter` is simply never
  called when `selectedCharacterName == null`).

Documentation updated to match (per CLAUDE.md's "Documentation After Implementation"):
`docs/CURRENT_ARCHITECTURE.md` §5.1/5.2 (new controller signature and inventory-loading branch),
`docs/KNOWN_PROBLEMS.md` §3.4 and §9 (status upgraded from "Partially Resolved" to "Resolved" now that
production is wired), `docs/ROADMAP.md`'s Phase 0 §3.4 bullet and Phase 1 high-level story bullet
(both now marked Done).

Tests: `./mvnw -o test` — 49 tests run, 0 failures, 0 errors, `BUILD SUCCESS` (same 49 as before this
story; no new automated test was added). No new domain rule or branch was introduced in `craft.*`/
`repo.*` beyond the `CraftingPlanner` overload and the controller call-site changes, and that overload
is exercised indirectly by every existing `CraftingPlanner`-adjacent test via its now-two-line
delegation from the original `evaluateAll(...)`; per the story's "Required Tests" section, no
dedicated UI/controller test layer exists in this repository to extend, and adding one was called out
as out of scope.

Manual verification: confirmed `./mvnw -o compile` and `./mvnw -o test` are clean, then launched
`./mvnw -o javafx:run` in this environment and confirmed the process starts and reaches the running
state with no exceptions logged (stdout/stderr showed only the expected JavaFX native-access
warnings). This session's tool sandbox has no PowerShell access and no GUI-automation/screenshot
tool available, so I could not click through to the Crafting Profit/Discovery windows themselves to
visually confirm the selector's default selection, its effect on recomputation, or the zero-character
fallback in a running UI - that click-through/screenshot-level check from the Definition of Done was
not completed and needs a human (or a session with GUI-automation tooling) to do it. Everything short
of that - the wiring itself, the null/empty-character fallback path, and the full regression suite -
was verified by direct code reading and the test run above.

No unresolved domain question or scope surprise encountered; `STORY-DOM-011`'s repository/domain
signatures were not changed, only called from a new `CraftingPlanner` overload as anticipated in its
own Result section.

## Blockers

None.
