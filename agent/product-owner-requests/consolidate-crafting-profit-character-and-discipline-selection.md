# Product Owner Request

## Status

RESOLVED

## Title

Consolidate Crafting Profit character selection into the Discipline selector

## Requested Change

Remove the separate `Character` selector from the Crafting Profit view.

The existing `Discipline` selector should become the single control for selecting the scope of the crafting calculation.

Its existing entries already represent both generic crafting disciplines and specific character/discipline combinations. Extend their meaning as follows.

### All

Selecting:

`All`

means:

- use all synced characters;
- use the coordinated multi-character crafting behavior already defined for `All characters`;
- different characters may perform different crafting steps;
- transferable intermediate items may move between characters;
- character-bound / soulbound materials remain usable only by the character that owns them.

This becomes the replacement for the separate `Character = All characters` selection.

### Generic discipline

Selecting a generic discipline such as:

- Chef
- Armorsmith
- Artificer
- Tailor
- Leatherworker
- Jeweler
- Scribe

means:

- consider all synced characters that have that crafting discipline;
- allow those eligible characters to participate in the coordinated crafting calculation;
- continue respecting character-specific bound-material ownership and crafting eligibility.

Example:

Selecting `Chef` calculates using all characters that have the Chef crafting discipline.

### Specific character / discipline

Existing character-specific entries such as:

`Armorsmith lvl 500 — Nbt Anch`

mean:

- restrict the crafting calculation to that specific character for that crafting discipline.

This provides the user with a more specific scope when desired without requiring a separate Character selector.

## Why / Product Intent

The separate Discipline and Character selectors overlap conceptually and make the Crafting Profit UI more complicated than necessary.

The Discipline selector already contains:

- `All`;
- generic disciplines;
- character-specific discipline entries.

It can therefore represent the desired calculation scope by itself.

The UI should expose one clear selector rather than requiring the user to understand the interaction between two overlapping controls.

## Constraints

- Remove the separate Character selector from Crafting Profit.
- Do not remove the existing character-specific entries from the Discipline selector.
- `All` must use the coordinated all-character crafting semantics already established by the project.
- Generic discipline entries must include all characters with that discipline, not arbitrarily select one character.
- Character-bound materials must never be pooled between characters.
- Transferable crafted intermediates may be passed between eligible characters.
- Specific character/discipline entries must restrict the calculation to that character.
- Preserve existing recipe discipline and crafting-level eligibility rules.
- Avoid duplicating the multi-character planning implementation; reuse the existing authoritative behavior.
- This request applies to Crafting Profit only unless an authoritative requirement explicitly states otherwise.

## Additional Context

The current Crafting Profit UI has both:

- a `Discipline` selector;
- a separate `Character` selector.

The Discipline selector already contains entries such as:

- `All`
- `Armorsmith`
- `Artificer`
- `Chef`
- `Armorsmith lvl 500 — <character>`
- `Artificer lvl 500 — <character>`

The desired result is to use this existing hierarchy as the complete calculation-scope selector and remove the redundant Character control.

## Planner Resolution

Updated `docs/DOMAIN_SPEC.md` §2.2.1 first, then reused and revised `agent/stories/STORY-DOM-014-coordinate-all-characters-crafting.md` for the single Discipline scope selector and its three modes. Updated remaining verification in `agent/stories/STORY-DOM-015-preserve-refresh-state-and-verify-character-results.md`, the tracking entry in `docs/KNOWN_PROBLEMS.md` §3.7, and `agent/stories/BACKLOG.md`. No duplicate coordination story was created; Discovery retains its existing behavior. Resolution means planning coverage, not completed implementation.