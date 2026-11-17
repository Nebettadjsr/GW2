# UD-001 — Selected-character concept required for soulbound material handling

## Status

RESOLVED

## Decision Needed

How should the crafting planner represent "the currently selected character" so that soulbound-material usability and account-bound Trading-Post opportunity cost (`docs/DOMAIN_SPEC.md` §11.1, DQ-007) can be evaluated per the domain spec? At minimum this needs: where the selection lives (a persisted setting vs. a per-run input), what identifies a character in that selection (in-game character name vs. a synced `characters` row), and how/if a user changes it.

## Why This Is Needed

`docs/DOMAIN_SPEC.md` §11.1/DQ-007 requires soulbound items to be usable only by "the selected character," but no such concept exists anywhere in the planner input today — `craft.PlanState` is built from a flat, character-agnostic owned-quantity map (confirmed conflict: `docs/KNOWN_PROBLEMS.md` §3.4). This is a product/UX and architecture decision (how a user picks a character, how that selection flows into the domain layer), not something derivable from the domain spec's item-usability rule or from existing code.

## Context

- `docs/DOMAIN_SPEC.md` §11.1 defines the usability rule itself (already decided) — it does not define how "the selected character" is determined, which is the open part.
- `docs/KNOWN_PROBLEMS.md` §3.4 is the confirmed conflict this decision unblocks.
- `STORY-SYNC-001` (DONE) already syncs `binding`/`bound_to` data into `character_items`, so the underlying data this concept would consume already exists; it does not itself design the concept.
- `STORY-DOM-007` (characterization test) does not require this decision and is not blocked by it — it only documents current (non-compliant) behavior using the existing flat owned-quantity input.

## Blocks

- The as-yet-unwritten bound/soulbound-material implementation story (tracked in `agent/stories/BACKLOG.md`'s "Not Yet Written" section).
- No currently existing story under `agent/stories/` depends on this decision.

## External Input Possibly Required

- product/UI decision (how a user selects/changes their active character)
- architecture choice (where the selection is held/persisted)

## User Decision

The currently selected character is owned by the UI/application calling context.

On first load of the relevant page/view, the first available synced character is selected by default. The user can change the selected character through the existing character dropdown.

The selected character is a per-session/per-view input and does not need to be persisted as a global setting.

Every crafting/planning/simulation call that requires character-specific material eligibility must receive the currently selected character explicitly from the calling layer.

The domain layer must not choose a character itself.

The GW2 character name may be used as the character identifier. Introducing a separate application/domain character ID is not required solely for this feature. If an existing database row ID is useful internally in adapters/application code, it may be used there without changing the domain rule.

## Resolution

The selected-character concept is defined as an explicit per-run input originating from the UI/application layer:

UI character dropdown
→ selected character
→ application/calling code
→ crafting planner input
→ soulbound-material eligibility

Rules:

- First page/view load selects the first available synced character.
- User may change the selection through the existing dropdown.
- Selection is not required to persist across sessions.
- The currently selected character must be passed explicitly into character-sensitive crafting calculations.
- Soulbound materials are usable only when their `bound_to` character matches the selected character.
- Account-bound materials remain account-wide as defined by the existing domain rules.
- Character selection logic must remain outside the domain layer.
- GW2 character name is an acceptable identifier for this purpose.