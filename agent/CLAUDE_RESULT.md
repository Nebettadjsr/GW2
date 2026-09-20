# CLAUDE_RESULT

Reusable template. Claude Code fills this in after executing the story currently in `agent/CURRENT_STORY.md`, then reports back with this file's content. One result per story. When the story is archived under `agent/stories/`, this filled-in copy is archived alongside it.

Do not leave placeholder angle brackets in a submitted result — replace every `<...>` field.

`agent/PROJECT_STATE.md` is planner-owned continuity state (see `CLAUDE.md` § Documentation Ownership) — normal story execution must not update it. Answer the "PROJECT_STATE.md Update Needed?" section below "No" unless the active story's own Definition of Done explicitly requires touching it. Route what used to go there instead: completed-story history to `agent/stories/BACKLOG.md` `## Done` (via the story's own file, already covered above under "Files Materially Changed"), known defects/risks to `docs/KNOWN_PROBLEMS.md`, current architecture facts to `docs/CURRENT_ARCHITECTURE.md`, and roadmap exit-criteria state to `docs/ROADMAP.md`.

---

## Story ID

STORY-DOM-012

## Final Status

DONE

## Summary

Added a "selected character" `ComboBox<String>` to `CraftingProfitView` and `CraftingDiscoveryView`
(no such control existed anywhere before this story, per the story's own Context section) and wired
it into `CraftingProfitController`/`CraftingDiscoveryController`, which now call
`STORY-DOM-011`'s `InventoryRepository.loadOwnedInventoryForCharacter(...)` with the selected
character whenever "use own mats" is enabled, instead of the old binding-unaware
`loadOwnedInventory()`. This gives `DOMAIN_SPEC.md` §11.1/DQ-007's soulbound-material rule (built but
unused in `STORY-DOM-011`) its first real effect for actual users.

## Files Materially Changed

- `src/main/java/repo/CharacterRepository.java` — added `loadAllCharacterNames()` (`SELECT name FROM
  characters ORDER BY name`) to populate the new selector.
- `src/main/java/CraftingProfitView.java` / `src/main/java/CraftingDiscoveryView.java` — added a
  `characterBox` `ComboBox<String>`, styled/placed like the existing `disciplineBox`/`sortBox`; a
  `reloadCharacterChoices` background loader (mirroring the existing `reloadDisciplineChoices`
  pattern) populates it and defaults to the first entry only when nothing is already selected; a
  `valueProperty()` listener triggers `reloadTable.run()`, matching how `disciplineBox`/`sortBox`
  already trigger recomputation.
- `src/main/java/CraftingProfitController.java` / `src/main/java/CraftingDiscoveryController.java` —
  `reload(...)` gained a third parameter, `String selectedCharacterName`. When `useOwnMats` is on: a
  non-null selection calls `loadOwnedInventoryForCharacter(...)` and splits its result into
  sellable/bound maps; a null selection (no characters synced yet) falls back to the original
  unfiltered `loadOwnedInventory()` — the smaller-scope degrade-gracefully option named in the
  Acceptance Criteria.
- `src/main/java/craft/CraftingPlanner.java` — added an `evaluateAll(...)` overload taking separate
  `sellableInventory`/`boundInventory` maps, threading them into `PlanState`'s existing two-arg
  constructor (`STORY-DOM-011`). The original 5-arg `evaluateAll(...)` now delegates to it with an
  empty bound map, so every other existing caller is unaffected. This was the missing link:
  `STORY-DOM-011` gave `PlanState`/`CraftingResolver` a bound pool, but nothing in the production
  recipe-evaluation path could hand one in until this overload existed.
- `docs/CURRENT_ARCHITECTURE.md` — §5.1/§5.2 updated to describe the character-selector control, the
  new controller signature, and the character/no-character inventory-loading branch.
- `docs/KNOWN_PROBLEMS.md` — §3.4 status upgraded from "Partially Resolved" to "Resolved" (now wired
  into production); §9 ranking and closing summary updated to match.
- `docs/ROADMAP.md` — Phase 0's §3.4 characterization-test bullet and Phase 1's bound-material
  high-level story bullet both marked Done.
- `agent/stories/STORY-DOM-012-selected-character-ui-wiring.md` — Status → DONE, Result filled in.
- `agent/stories/BACKLOG.md` — moved the story from "Active" to "Done" (index only).

## Tests Added

None. Per the story's "Required Tests" section, this repository has no UI/controller test layer to
extend, and building one was explicitly called out as out of scope. `STORY-DOM-011` already added the
required domain/repo-level tests for the underlying rule; this story only wires production call
sites to the already-tested path.

## Tests Run

- `./mvnw -o compile`
- `./mvnw -o test` (full suite)

## Test Result

- `./mvnw -o compile`: `BUILD SUCCESS`, no errors.
- `./mvnw -o test`: 49 tests run, 0 failures, 0 errors, `BUILD SUCCESS` — identical count to before
  this story (no new automated test was required or added; no regression).

## Definition-of-Done Status

- [x] Character selector present and functional in both `CraftingProfitView` and
      `CraftingDiscoveryView`.
- [x] Both controllers call `STORY-DOM-011`'s binding-aware repository method with the selected
      character.
- [x] `./mvnw test` passes (no regressions).
- [~] Manually verified against a running `./mvnw javafx:run` session — partially completed; see
      "Discovered Issues" below for exactly what was and wasn't verified and why.
- [x] `agent/stories/BACKLOG.md` updated. `agent/PROJECT_STATE.md` intentionally not touched — see
      "PROJECT_STATE.md Update Needed?" below.

## Discovered Issues

This session's tool sandbox has no PowerShell access (explicitly denied by the harness's permission
mode) and no GUI-automation/screenshot tool available. I launched `./mvnw -o javafx:run` and confirmed
the process starts and reaches a running state with no exceptions in its output (only the expected
JavaFX native-access warnings), then stopped it — a pre-existing, unrelated `Gw2App`/IntelliJ process
was already running on this machine before this session started and was left untouched throughout. I
could not click into the Crafting Profit/Discovery windows themselves to visually confirm the
selector's default selection, its effect on recomputation when switched, or the zero-character
fallback behavior in a running UI. That click-through/screenshot-level check named in the story's
Definition of Done was **not completed** and needs either a human or a session with GUI-automation
tooling to finish. Everything short of that — the wiring itself, the null/empty-character fallback
branch, and the full regression suite — was verified by direct code reading and the test run above,
per `CLAUDE.md`'s "if you can't test the UI, say so explicitly rather than claiming success."

No domain conflict or scope surprise was found. `STORY-DOM-011`'s repository/domain method signatures
were not changed — only called from a new, additive `CraftingPlanner` overload, exactly as
`STORY-DOM-011`'s own Result section anticipated this story would need to do.

## Blockers

None outright, but see "Discovered Issues": the UI click-through/screenshot portion of manual
verification is incomplete and should be finished by a human (or a GUI-capable session) before
treating this as fully closed out end-to-end.

## Follow-Up Recommendations

- Someone with an interactive desktop session should run `./mvnw javafx:run`, open both Crafting
  Profit and Crafting Discovery, and confirm: the Character combo box defaults to the first synced
  character, switching it changes results when bound materials are involved, and the app doesn't
  crash with zero synced characters (it shouldn't — the code path was traced and there's no
  exception-prone branch for that case — but this wasn't visually confirmed).
- `docs/KNOWN_PROBLEMS.md` §3.5 (controller-layer row filtering for price-unavailable rows) remains
  the only open item from the original §3.x conflict list; already tracked as `STORY-DOM-013` in
  `agent/stories/BACKLOG.md`'s "To Do" section.

## PROJECT_STATE.md Update Needed?

No. This story's Definition of Done does not explicitly require a `PROJECT_STATE.md` change; per
`CLAUDE.md`, `agent/PROJECT_STATE.md` is planner-owned and was not touched by this implementation
pass. `docs/ROADMAP.md`'s own Phase 0/Phase 1 bullets were updated directly instead (see "Files
Materially Changed"), since `ROADMAP.md` is the documented owner of that exit-criteria state.

## ROADMAP.md Update Needed?

Yes — done in this pass. Unlike the multi-story exit-criterion checkboxes left for a future planning
run in prior stories' precedent, this story's own two `ROADMAP.md` bullets (Phase 0's §3.4
characterization-test note and Phase 1's bound-material high-level story) describe exactly this
story's own completion, not a criterion spanning several still-in-flight stories, so they were marked
Done directly.
