## Story ID

STORY-SYNC-003

## Title

Optional Layer 4 live GW2 API smoke tests (excluded from default `mvn test`)

## Status

DONE

## Milestone

milestone-00

## Goal

Add the project's first Layer 4 ("Optional Live GW2 API Smoke Tests", `docs/TEST_STRATEGY.md` §31.4) coverage: a small number of explicitly-invoked tests that call the real, live Guild Wars 2 API for a few critical public endpoints, kept fully out of the default deterministic `./mvnw test` run. This closes the last remaining unchecked box in `docs/ROADMAP.md` §4 Phase 0's exit criteria list ("Live GW2 API smoke tests exist for selected critical endpoints, but are optional/manual and not part of the default deterministic test suite") — currently no such tests exist at all.

## Authoritative Source Documents / Sections

- `docs/TEST_STRATEGY.md` §10 "GW2 API Adapter Tests" and §31.4 "Layer 4 — Optional Live GW2 API Smoke Tests" (defines the required properties: selected critical endpoints only, excluded from the default Surefire `test` goal, explicitly invoked, allowed to fail on network/API unavailability without affecting the normal suite, never the *only* coverage for parser/sync behavior — Layer 3 fixture tests remain authoritative).
- `docs/ROADMAP.md` §4 "Phase 0 — Build & Test Foundation", the exit criterion named in the Goal above.
- `src/main/java/api/Gw2ApiClient.java` (`getPublic`/`getPublicArray` — public, unauthenticated GW2 API access; prefer these over `getAuth` so the smoke test does not depend on a configured API key).
- `agent/stories/STORY-SYNC-002-recipe-parser-fixture-test.md` (the sibling Layer 3 fixture test for `RecipeParser` — this story is the live-network counterpart; do not duplicate its fixture-capture work, and reuse the same public recipe/item endpoints it identifies as not requiring auth).

## Context

The project has no mechanism today for excluding a test class from the default `./mvnw test` run — every test under `src/test/java` currently runs as part of the normal Surefire execution. `docs/TEST_STRATEGY.md` §31.4 leaves "the exact mechanism... to the implementing story" (e.g. a Surefire exclude pattern/naming convention, a JUnit 5 `@Tag` combined with a `groups`/profile exclusion, or a separate Maven profile). `Gw2ApiClient.getPublic(...)`/`getPublicArray(...)` already provide unauthenticated access suitable for public endpoints like item and recipe detail lookups, so this story does not need the project's API key at all.

## Acceptance Criteria

- A small number (2-4) of critical, public GW2 API endpoints relevant to this project's sync flows are chosen and smoke-tested live (e.g. an item detail lookup, a recipe detail lookup, and/or a Trading Post price lookup) — each test makes a real HTTP call and asserts a minimal sanity shape (e.g. expected fields present, expected id echoed back), not a full parser-level assertion (that remains Layer 3's job).
- These tests are excluded from the default `./mvnw test` run — running `./mvnw test` with no special flags must not execute them and must not fail or hang because of network unavailability.
- A documented, explicit command (e.g. a Maven profile flag, a `-Dgroups=...`/`-Dtest=...` invocation, or equivalent) exists to run them on demand; the mechanism chosen and the exact command are recorded in this story's Result section.
- If the live API is unreachable when verifying this story's own work, that failure is expected/acceptable for this test category and must not be worked around by weakening the assertions — note the verification outcome (reachable or not) in the Result section either way.
- No production code changes — this is test-only, additive infrastructure.

## Required Tests

- The new Layer 4 smoke test(s) themselves (this story's entire deliverable), run manually at least once during this story's execution to confirm they work against the real API.
- `./mvnw test` — confirm the full existing default suite still passes unchanged and that the new smoke test(s) do **not** run as part of it.

## Constraints

- Do not require the project's GW2 API key for these tests — use only public, unauthenticated endpoints (`Gw2ApiClient.getPublic`/`getPublicArray`).
- Do not let these tests become part of the default `mvn test` execution under any circumstance — verify this explicitly (e.g. temporarily disconnect network or point at an invalid host and confirm `./mvnw test` still passes) before declaring the story done.
- Do not use these tests as the only coverage for parser/sync behavior — Layer 3 (`STORY-SYNC-002`, when done) remains the authoritative, always-run coverage; this story only adds a manual reality-check.
- Follow `docs/CODING_GUIDELINES.md`.

## Dependencies

None. (Does not require `STORY-SYNC-002` to be done first, though both address related but distinct exit-criteria items.)

## Definition of Done

- [x] 2-4 live smoke tests added against selected public GW2 API endpoints, passing when the live API is reachable.
- [x] Tests are excluded from the default `./mvnw test` run, verified explicitly.
- [x] A documented on-demand command exists to run them, recorded in the Result section.
- [x] `./mvnw test` shows the full existing default suite passing, unaffected by the new tests.
- [x] `agent/runtime/artifacts/CLAUDE_RESULT.md` filled in.

## Result

Added `src/test/java/api/Gw2ApiLiveSmokeIT.java` with 3 live tests against public, unauthenticated
GW2 API endpoints (`Gw2ApiClient.getPublic(...)`, no API key involved):

- `itemDetailLookupReturnsExpectedItem` — `GET /v2/items/19721` (Glob of Ectoplasm), asserts the
  echoed `id` and non-blank `name`/`type`.
- `recipeDetailLookupReturnsExpectedRecipe` — `GET /v2/recipes/7319`, the same real recipe already
  captured as a fixture by `parser.RecipeParserTest` (`STORY-SYNC-002`); asserts the echoed `id`,
  `output_item_id`, and a non-empty `disciplines` array.
- `tradingPostPriceLookupReturnsExpectedPrice` — `GET /v2/commerce/prices/19721`; asserts the
  echoed `id` and that `buys`/`sells` both carry a `unit_price` field. (Recipe 7319's own output
  item, 46742/Lump of Mithrillium, was tried first but turned out to be flagged
  `NoSell`/`AccountBound` — its live `/v2/commerce/prices/46742` call correctly 404s. Item 19721
  (Glob of Ectoplasm), the recipe's own ingredient, was used instead since it is genuinely
  tradable — this is real, observed API behavior, not a test defect.)

**Exclusion mechanism (naming convention):** the class is named `Gw2ApiLiveSmokeIT` (an "IT"
suffix, not "Test") specifically because Surefire 3.6.0's default include patterns
(`**/Test*.java`, `**/*Test.java`, `**/*Tests.java`, `**/*TestCase.java`) never match it — no
`pom.xml` change was needed. The class still gets compiled as part of `test-compile` (so it stays
type-checked and won't silently rot), it is simply never selected for execution by the default
`test` goal.

**On-demand command:** `./mvnw test -Dtest=Gw2ApiLiveSmokeIT`

**Verification performed:**
- `./mvnw test` (default, network reachable): 22 tests run (unchanged from before this story),
  `Gw2ApiLiveSmokeIT` does not appear in the "Running ..." list at all, `BUILD SUCCESS`.
- `./mvnw -o test` (fully offline, Maven itself forbidden from any network access — a stronger
  check than "point at an invalid host," since it also rules out any incidental network use):
  same 22 tests, `BUILD SUCCESS`. This confirms the default suite cannot fail or hang on network
  unavailability because of these tests, since they are never invoked by the default goal at all.
- `./mvnw test -Dtest=Gw2ApiLiveSmokeIT` (explicit invocation, live API reachable): all 3 tests
  passed against the real API — **live API was reachable during this story's verification.**

No production code was changed — test-only, additive infrastructure, per the Constraints.

## Blockers

None.
