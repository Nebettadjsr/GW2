## Story ID

STORY-SYNC-001

## Title

Sync character bag and equipment inventory into `character_items`

## Status

DONE

## Milestone

milestone-00

## Goal

Populate the existing but currently-unused `character_items` table by parsing each character's
`bags` and `equipment` arrays out of the character-detail JSON that `sync.CharacterSync` already
fetches, so a follow-up story can extend the owned-material pool to include character
inventories (`docs/KNOWN_PROBLEMS.md` §3.3) and future bound-material work (§3.4) has real
binding data to use. This story is sync/persistence only — it does not change planner/domain
behavior.

## Authoritative Source Documents / Sections

- `docs/DOMAIN_SPEC.md` §9, DQ-006 (owned pool must include all character inventories) — motivation only.
- `docs/DOMAIN_SPEC.md` §11.1, DQ-007 (binding rules) — motivation only for capturing `binding`/`bound_to` now.
- `docs/KNOWN_PROBLEMS.md` §3.3 — the confirmed conflict this unblocks.
- `docs/ROADMAP.md` Phase 1 ("Extend the owned-material pool..." — notes the sync prerequisite).
- `src/main/java/sync/CharacterSync.java` — existing per-character fetch/upsert pattern to extend (`fetchCharacterDetails`, `replaceCharacterCrafting`, `replaceCharacterRecipes`).
- `src/main/java/parser/BankParser.java` and `src/main/java/model/BankSlot.java` — existing binding/bound_to slot-parsing pattern to mirror.
- `src/PostgreSQL Query to create DB` — `character_items` table definition (columns: `character_id`, `location` ('BAG'/'EQUIPMENT'), `bag_index`, `slot_index`, `equipment_slot`, `item_id`, `count`, `binding`, `bound_to`, `fetched_at`; unique constraint on `(character_id, location, bag_index, slot_index, equipment_slot)`).

## Context

`CharacterSync.fetchCharacterDetails(name)` already retrieves the full GW2 API character-detail
JSON for each character but `syncCharactersCraftingAndRecipes(...)` only extracts the
`crafting` and `recipes` nodes from it, discarding the rest. The GW2 API character-detail
response includes `bags` (an array of bag objects, each with an `inventory` array of item slots
— some slots `null`/empty) and `equipment` (a flat array of equipped items, each carrying its
own `slot` name) when the API key has the `inventories` scope. The `character_items` table
already exists in schema for exactly this data but nothing currently writes to it.

Per-item fields to persist (`item_id`, `count`, `binding`, `bound_to`) are structurally the same
concepts `BankParser.parseSlot(...)` already extracts from `account_bank` API responses — reuse
that shape/approach rather than inventing a new one.

## Acceptance Criteria

- Bag slots are parsed with `location = 'BAG'`, using bag position as `bag_index` and slot
  position within that bag as `slot_index`; empty/null slots are not inserted.
- Equipment entries are parsed with `location = 'EQUIPMENT'`, using the API's own equipment slot
  name as `equipment_slot` (`bag_index`/`slot_index` left null for these rows).
- Each inserted/updated row carries `item_id`, `count`, `binding`, `bound_to` as returned by the
  API (null where absent).
- Rows are upserted against the existing `uq_character_items_slot` unique constraint.
- Stale rows for a character (present from a previous run but no longer returned this run) are
  removed, following the same `fetched_at`-based delete-stale pattern already used in
  `replaceCharacterCrafting`/`replaceCharacterRecipes`.
- The new inventory sync is invoked as part of the same per-character flow that already fetches
  `crafting`/`recipes` (either inline in `syncCharactersCraftingAndRecipes` or a clearly named
  sibling method called from the same place), so a normal character refresh populates
  `character_items` without a separate manual trigger.
- No changes to `craft.*`, `repo.InventoryRepository`, or any planner/domain logic in this story.

## Required Tests

- If new parsing logic is introduced (e.g. a `bags`/`equipment` parser mirroring
  `BankParser.parseSlot`), add a unit test under `src/test/java/parser/` covering: a populated
  bag slot with binding/bound_to, an empty bag slot (must not produce a row), and an equipment
  entry.
- Do not add a live-database or live-GW2-API test (`docs/TEST_STRATEGY.md` §14) — repository
  integration test tooling against Postgres is not yet decided (`docs/TEST_STRATEGY.md` §9/§29).
- Confirm `./mvnw test` still passes in full afterward.

## Constraints

- Do not modify `repo.InventoryRepository` or any `craft.*` domain code — this is data sync only.
- Do not use the live GW2 API in any automated test.
- Follow the existing upsert + delete-stale idiom already used in `CharacterSync` rather than
  inventing a new persistence pattern.
- Small, focused change — do not refactor `CharacterSync`'s existing crafting/recipes sync
  methods beyond what's needed to add the inventory sync alongside them.
- If the configured API key lacks the `inventories` scope and the API returns no `bags`/
  `equipment` data, handle this the same way `crafting`/`recipes` already handle a missing
  section (skip gracefully) — do not treat it as a hard failure of the whole sync run.

## Dependencies

None.

## Definition of Done

- [x] Character bags and equipment are parsed from the existing character-detail fetch and
      written into `character_items` with correct `location`/`bag_index`/`slot_index`/
      `equipment_slot`/`item_id`/`count`/`binding`/`bound_to`.
- [x] Stale rows from a previous run are removed per character.
- [x] New parsing logic has at least one unit test not requiring a live database or live API.
- [x] `./mvnw test` passes (no regression in the existing suite).
- [x] `agent/runtime/artifacts/CLAUDE_RESULT.md` filled in.

## Result

Added a new parser, `parser.CharacterItemsParser`, with two entry points:

- `parseBags(JsonNode bagsArr)` — iterates the `bags` array by position (`bag_index`), then each
  bag's `inventory` array by position (`slot_index`); null bags and null/absent-`id` slots are
  skipped (no row emitted), matching `BankParser.parseSlot`'s item-field extraction
  (`item_id`/`count`/`binding`/`bound_to`).
- `parseEquipment(JsonNode equipmentArr)` — iterates the flat `equipment` array; each entry's own
  `slot` field becomes `equipment_slot`; entries missing `id` or a blank/missing `slot` name are
  skipped.

Both return `List<model.CharacterItemRow>` (new record: `location`, `bagIndex`, `slotIndex`,
`equipmentSlot`, `itemId`, `count`, `binding`, `boundTo`).

`parser.CharacterParser` gained two accessors mirroring the existing `craftingNode`/`recipesNode`:
`bagsNode(JsonNode)` and `equipmentNode(JsonNode)`.

`sync.CharacterSync.syncCharactersCraftingAndRecipes()` now also parses `CharacterItemsParser
.parseBags(...)` + `.parseEquipment(...)` per character (combined into one list on the existing
per-character `CharPayload` record) and, inside the same per-character transaction that already
calls `replaceCharacterCrafting`/`replaceCharacterRecipes`, calls a new sibling method
`replaceCharacterItems(...)`. That method follows the exact same upsert-then-delete-stale idiom:
`INSERT ... ON CONFLICT (character_id, location, bag_index, slot_index, equipment_slot) DO UPDATE
SET ...` against `uq_character_items_slot`, followed by `DELETE ... WHERE character_id = ? AND
fetched_at < ?`.

**Note on the `count` column and one non-domain persistence decision:** `character_items.count` is
`NOT NULL DEFAULT 1`, but the GW2 API commonly omits `count` for equipment entries (a singular
equipped item). Binding `null` there via `setNull` would violate the `NOT NULL` constraint (unlike
omitting the column, `DEFAULT` does not apply to an explicit `NULL` bind). Rather than treat a
missing equipment count as a hard failure, `replaceCharacterItems` binds `row.count() != null ?
row.count() : 1`, applying the column's own already-declared default at the call site. This is a
mechanical fix to satisfy an existing schema constraint, not a new domain rule — flagging it here
per CLAUDE.md's "report ambiguity" guidance rather than silently deciding it invisibly.

**Note on the unique constraint and `NULL` columns:** `uq_character_items_slot` does not use
`NULLS NOT DISTINCT`, so for `EQUIPMENT` rows (`bag_index`/`slot_index` always `NULL`), Postgres
does not consider two rows with the same `equipment_slot` as conflicting, and `ON CONFLICT` will
not fire between them — a re-run inserts a new row alongside the old one rather than updating it
in place. This does not cause lasting duplication: the existing `fetched_at`-based delete-stale
step that runs immediately after (same transaction) always removes the old row, since it carries
the previous run's `fetched_at < runTs`, leaving only the current run's row. Net table state is
therefore correct after every sync, matching the `crafting`/`recipes` idiom's actual guarantee
("converges to current state after a full sync"), even though the equipment-row path is technically
insert-then-delete-old rather than a true in-place `UPDATE`. Not treated as a blocker since it
produces no observably wrong end state.

Files added:
- `src/main/java/model/CharacterItemRow.java`
- `src/main/java/parser/CharacterItemsParser.java`
- `src/test/java/parser/CharacterItemsParserTest.java` (3 tests: populated bag slot with
  binding/bound_to + adjacent empty slot skipped; equipment entry; null-input arrays return empty
  lists)

Files changed:
- `src/main/java/parser/CharacterParser.java` — added `bagsNode`/`equipmentNode`.
- `src/main/java/sync/CharacterSync.java` — `CharPayload` gained an `items` field, populated
  per-character; new private `replaceCharacterItems(...)` called alongside
  `replaceCharacterCrafting`/`replaceCharacterRecipes` in the same transaction.

No changes to `craft.*`, `repo.InventoryRepository`, or any planner/domain logic.

`./mvnw -DskipITs test`: 8 tests run, 0 failures, 0 errors, `BUILD SUCCESS` (5 pre-existing +
3 new `CharacterItemsParserTest` tests). No live database or live GW2 API used in any test.

## Blockers

None.
