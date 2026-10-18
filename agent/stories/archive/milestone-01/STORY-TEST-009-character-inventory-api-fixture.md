## Story ID

STORY-TEST-009

## Title

Verify character inventory and binding parsing with captured GW2 API fixtures

## Status

DONE

## Milestone

milestone-01

## Goal

Close the concrete Layer 3 character-inventory parsing evidence gap identified by STORY-QUALITY-001, satisfying Phase 1's inherited API-data verification prerequisite for owned-material and binding behavior.

## Authoritative Source Documents / Sections

- `docs/ROADMAP.md`, Phase 1 Dependencies and Exit Criteria: real persistence/API evidence for character inventory and binding behavior.
- `agent/stories/STORY-QUALITY-001-phase-one-completion-review.md`, Result / Verification gap: CharacterItemsParserTest uses hand-typed JSON rather than captured API responses.
- `docs/TEST_STRATEGY.md` §31.3: captured real payloads, deterministic offline parser/sync tests, minimal fixtures preserving relevant API structure.
- `docs/TARGET_ARCHITECTURE.md` §34: inherited prerequisite gaps are assessed within the current milestone; review completion does not itself close the milestone.

## Context

The completed review records PostgreSQL, domain and UI evidence but identifies missing captured-payload evidence for the parser feeding character_items, binding and bound_to. The planner treats this specific gap as blocking Phase 1 closure because its supplied Dependencies explicitly require Layer 3 evidence for this behavior. Other test layers do not establish the real API payload contract. This story addresses that finding without reopening the archived prerequisite milestone or repeating the health review.

## Acceptance Criteria

- Add minimal test resources derived from captured real GW2 character API responses, preserving the relevant bags/equipment and binding/owner structure. Record their provenance and any trimming or consistent anonymization; do not fabricate missing API shapes.
- Exercise the production character-item parser with these resources and assert the item identity/count and binding/owner outputs consumed by character inventory synchronization, covering bag/equipment items and the unbound, account-bound and soulbound cases relevant to the finding.
- Tests run deterministically offline in the normal test suite, without a live API key or dependence on the developer's database. Keep credentials out of fixtures.
- Preserve useful existing edge-case tests; hand-written examples alone must no longer be the evidence for the real payload contract.
- Record targeted verification results and how the captured fixtures close the review finding. If genuine captures cannot be obtained, record the precise blocker rather than substituting invented payloads or claiming completion.

## Required Tests

- Run the character-item parser fixture tests and existing parser regressions affected by the change.
- Verify that the new tests are included in the normal test suite and execute offline, following TEST_STRATEGY.md §31.3.
- If a concrete behavior defect is exposed, preserve the failing regression evidence and follow TEST_STRATEGY.md §17; report scope implications explicitly.

## Constraints

- Scope is the recorded character-inventory API fixture gap, not new domain behavior, persistence redesign or test infrastructure.
- Reuse the existing test framework and fixture conventions.
- No broad bug hunting, automatic broad regression campaign, arbitrary coverage expansion, speculative cleanup/refactoring or later-milestone architecture work.
- Do not infer authentic API structures from the current hand-written test or silently waive the captured-payload requirement.

## Dependencies

STORY-QUALITY-001 (DONE).

## Definition of Done

- Captured fixtures and offline parser assertions satisfy the acceptance criteria.
- Required verification is recorded in Result with fixture provenance and any remaining limitations.
- The specific review finding has evidence suitable for a subsequent Phase 1 completion assessment.

## Result

Captured a real character-detail payload live via `GET https://api.guildwars2.com/v2/characters/:name`
(authenticated, requires the `characters` and `inventories` scopes — confirmed present on the
project's local `.env` `GW2_API_KEY` via `GET /v2/tokeninfo` before use) for each of the developer's
real GW2 characters, then selected one real bag (two slots of it, plus one item from a second bag)
and one real equipment array that together already exhibited all three binding cases relevant to the
finding without any invented data:
- unbound (no `binding` field at all — e.g. real item id 9285/21683),
- account-bound (`"binding": "Account"`, no `bound_to`) — e.g. item id 97254/72446,
- soulbound (`"binding": "Character"` with `bound_to` equal to the owning character's name) — e.g.
  item id 19575/63602.

Trimmed the captured JSON to a minimal fixture preserving this real structure (dropped unrelated
top-level fields — crafting, recipes, specializations, equipment_pvp, etc. — and unrelated nested
fields on equipment entries such as `stats`/`infusions`/`dyes`/`upgrades` that this parser does not
read; kept `size`/`id` bag-container fields, null inventory slots, and multi-bag indexing intact) and
saved it as `src/test/resources/parser/character_inventory_fixture.json`. The real character name
("Nebet Ta Djsr") was consistently replaced with an anonymized placeholder ("Fixture Character One")
everywhere it appeared — the top-level `name` field and both `bound_to` values — since it is
account-identifying; no other captured field was altered, invented, or hand-typed from memory. Item
ids, bag ids, slot names, and binding values are all real values taken from the live response. The
raw, unanonymized capture used only for selection was discarded after building the fixture and was
never written into the repository.

Rewrote `src/test/java/parser/CharacterItemsParserTest.java`'s two main-path tests
(`parseBagsExtractsUnboundAccountBoundAndSoulboundItemsFromCapturedApiPayload`,
`parseEquipmentExtractsSoulboundAndAccountBoundEntriesFromCapturedApiPayload`) to load this fixture
from the classpath and assert `CharacterItemsParser.parseBags`/`parseEquipment`'s item id, count,
location, bag/slot index or equipment slot, binding, and `bound_to` outputs against it, covering the
unbound/account-bound/soulbound cases, null-slot skipping, and multi-bag indexing. Preserved the
existing structural edge-case test (`parseBagsSkipsNullBagAndReturnsEmptyForMissingArray`, null/absent
top-level array handling) unchanged, since it is not payload-realism evidence and remains useful on
its own. No production code was changed; parsing the captured payload surfaced no defect in
`CharacterItemsParser`.

**Tests run:** `./mvnw -Dtest=CharacterItemsParserTest test` — 3 tests, 0 failures/errors. Full suite
`./mvnw test` — 72 tests, 0 failures/errors, `BUILD SUCCESS` (includes the new fixture-based tests
plus all pre-existing tests, confirming no regression).

This closes the STORY-QUALITY-001 finding: `CharacterItemsParser` (feeding `character_items`,
`binding`, `bound_to`) is now evidenced against a captured real API payload, not solely hand-typed
JSON, satisfying `docs/TEST_STRATEGY.md` §31.3 and `docs/ROADMAP.md`'s Phase 1 dependency on real
API evidence for character inventory/binding behavior. Fixtures run fully offline and deterministically
in the normal `./mvnw test` suite; no live API key or database is required at test time — the API key
was only used once, ahead of time, to produce the committed fixture file.

No remaining uncertainty: genuine captures were obtained and are sufficient to cover all three binding
cases named by the finding.

## Blockers

None.
