## Story ID

STORY-SYNC-002

## Title

Recipe parser test using a captured real GW2 API payload fixture (Layer 3)

## Status

DONE

## Milestone

milestone-00

## Goal

Give `parser.RecipeParser` (`parseRecipe`/`parseIngredients`) its first automated test, and make it the first Layer 3 ("GW2 API Contract/Fixture Tests", `docs/TEST_STRATEGY.md` §31.3) test in the project: one built from a captured real Guild Wars 2 API JSON response saved as a test resource, not a hand-typed approximation. This directly advances `docs/ROADMAP.md` §4 Phase 0's exit criterion "Parser/sync logic for Guild Wars 2 API data is covered by tests using captured real API payloads as fixtures," which currently has zero coverage — the only existing parser test, `parser.CharacterItemsParserTest`, uses hand-typed inline JSON rather than a captured payload, and no other parser under `src/main/java/parser/` has any test at all.

## Authoritative Source Documents / Sections

- `docs/TEST_STRATEGY.md` §10 "GW2 API Adapter Tests" and §31.3 "Layer 3 — GW2 API Contract/Fixture Tests" (the required properties: captured real responses, deterministic/offline, part of the normal `./mvnw test` suite, fixtures trimmed but not hand-written from memory).
- `docs/ROADMAP.md` §4 "Phase 0 — Build & Test Foundation" exit criterion "Parser/sync logic for Guild Wars 2 API data is covered by tests using captured real API payloads as fixtures."
- `src/main/java/parser/RecipeParser.java` (`parseRecipe`, `parseIngredients` — the methods under test).
- `src/test/java/parser/CharacterItemsParserTest.java` (existing parser test for style/structure reference; note it is *not* itself a Layer 3 example — do not copy its hand-typed-JSON approach).

## Context

`RecipeParser` is a pure function over a Jackson `JsonNode` (no I/O, no repository/database dependency), so it is straightforward to unit test directly — the blocker so far has simply been that no fixture-based parser test exists yet in this project to establish the pattern. The GW2 API's recipe detail endpoint (`GET https://api.guildwars2.com/v2/recipes/:id`) and the corresponding item detail endpoint are public and require no API key. Capture one (or a small handful of) real recipe JSON response(s) — ideally one with a non-trivial ingredient list and non-null `guild_ingredients`/`chat_link` fields, per `TEST_STRATEGY.md` §31.3's "preserve the real API's relevant structure" — save as a trimmed test resource file, and write assertions against `RecipeParser`'s output for that captured payload.

## Acceptance Criteria

- At least one real recipe JSON response is captured from the live GW2 API (`GET /v2/recipes/:id`, no auth required) and saved as a test resource (e.g. under `src/test/resources/parser/`), trimmed only if needed to keep the fixture small while preserving its real structure (`docs/TEST_STRATEGY.md` §31.3) — not hand-typed from memory.
- A new test in `src/test/java/parser/` loads that fixture file and asserts `RecipeParser.parseRecipe(...)` and `RecipeParser.parseIngredients(...)` correctly extract the recipe's id, output item/count, disciplines, flags, and ingredient rows from it.
- The test is fully offline and deterministic at run time (no live network call during `./mvnw test` — the captured fixture file is the only input).
- `./mvnw test` passes, including this new test and the full existing suite.

## Required Tests

- The new fixture-based `RecipeParser` test itself (this story's entire deliverable).
- `./mvnw test` — full existing suite must continue to pass unchanged.

## Constraints

- Do not change `RecipeParser`'s production behavior — test-only change, unless parsing the captured payload surfaces an actual defect, in which case stop and report it rather than silently fixing it (per `CLAUDE.md`'s bug-fix workflow — a fix needs its own story/decision, not a silent bundle into a test-only story).
- Do not use the live API at test-execution time — capture the payload once, save it as a resource, and test against the saved file.
- Do not require the project's own GW2 API key for this — the recipe/item detail endpoints used here are public.
- Follow `docs/CODING_GUIDELINES.md`.

## Dependencies

None.

## Definition of Done

- [x] A captured real recipe JSON payload is saved as a test resource.
- [x] A new test in `src/test/java/parser/` exercises `RecipeParser.parseRecipe`/`parseIngredients` against that fixture and passes.
- [x] No production code changed (unless a defect was found and explicitly reported per the Constraints above).
- [x] `./mvnw test` shows the new test passing alongside the full existing suite.
- [x] `agent/runtime/artifacts/CLAUDE_RESULT.md` filled in.

## Result

Captured `GET https://api.guildwars2.com/v2/recipes/7319` (no auth required) verbatim via `curl`
and saved it unmodified as `src/test/resources/parser/recipe_7319.json` — no trimming was
needed, the real response is already small (3 ingredients, 6 disciplines, one flag, a non-null
`chat_link`, and a present-but-empty `guild_ingredients` array; a broader scan of the `/v2/recipes`
listing found no live recipe with a non-empty `guild_ingredients`, so that field's "ideally
non-null" preference in the Context section could not be fully satisfied — the field is still
present and exercised as an empty array, which is itself real API structure, not invented).

Added `src/test/java/parser/RecipeParserTest.java` with two tests loading that fixture from the
classpath:
- `parseRecipeExtractsFieldsFromCapturedApiPayload` — asserts `parseRecipe(...)`'s id, type,
  output item/count, min rating, craft time, disciplines, flags, chat link, and
  `guildIngredientsJson` against the captured payload.
- `parseIngredientsExtractsRowsFromCapturedApiPayload` — asserts `parseIngredients(...)`
  returns the 3 expected `IngredientRow`s in order.

No production code was changed; parsing the captured payload surfaced no defect in
`RecipeParser`. `./mvnw test` runs 16 tests (14 pre-existing + 2 new), 0 failures, 0 errors,
`BUILD SUCCESS`.

## Blockers

None.
