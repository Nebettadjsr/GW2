## Story ID

STORY-DOM-019

## Title

Align recipe loading and Discovery with decided account-wide knowledge

## Status

DONE

## Milestone

milestone-02

## Goal

Resolve the Phase 2 recipe-knowledge agreement blocker by applying the already-decided account-wide ownership rule consistently to recipe loading and Discovery, while retaining character-specific eligibility.

## Authoritative Source Documents / Sections

- `docs/ROADMAP.md` section 6, Phase 2 partially satisfied recipe-unlock exit criterion.
- `docs/DOMAIN_SPEC.md` sections 6, 34, 35 and DQ-010 (DECIDED).
- `docs/KNOWN_PROBLEMS.md` section 4.2.
- `docs/TARGET_ARCHITECTURE.md` sections 7 and 10.
- `agent/stories/STORY-DOM-018-extract-recipe-knowledge-policy.md`, Result and Blockers.

## Context

STORY-DOM-018 preserved and characterized a discrepancy: loadRecipes considers unlocks by any character, whereas loadRecipesForCharacter and loadMissingDiscoverableRecipeIdsForCharacter consider only the selected character's unlocks in addition to account_recipes. DOMAIN_SPEC sections 34/35 and decided DQ-010 already establish account-wide ownership; implementing that rule requires no new product decision. This separate correction follows the completed behavior-preserving extraction and must not bundle another structural refactor.

KNOWN_PROBLEMS section 4.2 currently groups loadRecipesForCharacter with the account-wide path, contradicting STORY-DOM-018's detailed characterization. Verify and reconcile this documentation against implementation evidence within this story.

## Acceptance Criteria

1. For the same account unlock facts, loadRecipes, loadRecipesForCharacter and loadMissingDiscoverableRecipeIdsForCharacter use the independent domain policy to recognize a recipe as known when account_recipes or any character's character_recipes records its unlock. A recipe known only by another character is not a Discovery candidate for the selected character.
2. Ownership remains separate from selected-character discipline/rating eligibility and discoverability. Preserve existing eligibility filters, mapping, public calling contracts and calculation behavior except for the documented ownership correction. Globally existing recipes are not automatically known.
3. Keep persistence fact retrieval and mapping in repo.* and knowledge decisions in the independent domain policy; introduce no JDBC, SQL, repository-owned or transport dependencies into the domain.
4. Replace the cross-check regression's expected disagreement with assertions of the decided rule, including the character-specific loading entry point. Explicitly document the intentional behavior change from STORY-DOM-018's characterization.
5. Reconcile KNOWN_PROBLEMS section 4.2 and its summary with verified results, including its inaccurate account-wide characterization of loadRecipesForCharacter. Update only evidenced current-architecture statements and the Phase 2 unlock criterion if satisfied. Do not declare Phase 2 complete: its bounded project-health review remains required.

## Required Tests

- Focused pure-domain policy tests for account unlock, any-character unlock, both and neither, without database/repository dependencies.
- PostgreSQL integration regressions using the established disposable-schema approach for all three entry points: account-only unlock, selected-character-only unlock, other-character-only unlock without an account_recipes row, and no unlock. Compare knowledge decisions independently of eligibility filters; retain negative checks for discoverability and character eligibility.
- Run existing Phase 0/1 domain regressions to protect calculation behavior and targeted existing Discovery integration/UI checks affected by the changed candidate filtering. Record actual commands, results and limitations; do not claim unexecuted verification.

## Constraints

One separately scoped domain correction implementing an existing decision. No new product semantics, broad refactor, schema or sync redesign, UI redesign, unrelated defect fixes, or later-milestone work. Do not change character discipline/rating rules to force result-set equality. Preserve the domain/persistence boundary established by STORY-DOM-017 and STORY-DOM-018.

## Dependencies

STORY-DOM-017 and STORY-DOM-018 (both DONE).

## Definition of Done

The acceptance criteria have recorded implementation and test evidence; Result identifies the corrected entry points and intentional behavior change, and authoritative defect/status documentation matches that evidence. Any remaining knowledge disagreement or verification limitation is explicit. Subsequent planning assesses readiness for the required Phase 2 project-health review.

## Result

Corrected entry points: `repo.RecipeRepository.loadRecipesForCharacter` and
`loadMissingDiscoverableRecipeIdsForCharacter` now determine recipe ownership via
`craft.RecipeKnowledgePolicy.isKnownAccountWide(accountUnlocked, unlockedByAnyCharacter)` — the same
function and the same two facts already used by `loadRecipes` — instead of the narrower
`isKnownByCharacter(accountUnlocked, unlockedByThisCharacter)`. `isKnownByCharacter` and its backing
private helper `loadCharacterUnlockedRecipeIds` were removed from `RecipeRepository`/
`RecipeKnowledgePolicy` since no caller needs a narrower-than-account-wide ownership check anymore.

**Intentional behavior change (STORY-DOM-018 characterization no longer holds):** a recipe unlocked
only by a different character (no `account_recipes` row) is now (a) included by
`loadRecipesForCharacter` for the selected character, and (b) excluded from
`loadMissingDiscoverableRecipeIdsForCharacter`'s missing/discoverable list for the selected
character — matching `DOMAIN_SPEC.md` §34/35 and decided `DQ-010`. Selected-character discipline
filtering (SQL `WHERE ? = ANY(r.disciplines)`) and the caller-side minimum-rating filter
(`CraftingDiscoveryController.reload`, unchanged) remain exactly as before; only the ownership fact
changed. `charName` is retained in both method signatures (public calling contract preserved) but no
longer narrows ownership — it exists only where the method needs a specific character's discipline
context to be supplied by the caller.

**Tests:**
- `craft.RecipeKnowledgePolicyTest` — kept the four `isKnownAccountWide` combinations (account-only,
  any-character-only, both, neither); removed the now-deleted `isKnownByCharacter` cases.
- `repo.RecipeRepositoryTest` — flipped `loadRecipesForCharacter_knownOnlyByAnotherCharacter_*` from
  `_isExcluded` to `_isIncluded`; added
  `loadMissingDiscoverableRecipeIdsForCharacter_knownOnlyByAnotherCharacter_isExcluded` (new case:
  other-character-only unlock, no `account_recipes` row); replaced the cross-check test
  (`knowledgeCrossCheck_...DisagreesBetween...`) with
  `knowledgeCrossCheck_recipeKnownOnlyByAnotherCharacterAgreesAcrossAllThreeEntryPoints`, asserting
  agreement across `loadRecipes`, `loadRecipesForCharacter`, and
  `loadMissingDiscoverableRecipeIdsForCharacter` for the same unlock facts. All pre-existing
  account-only/this-character-only/discoverable-flag/discipline-filter cases were left unchanged and
  still pass, confirming eligibility filtering and mapping are untouched.

**Commands run:**
- `./mvnw -o -q test -Dtest=RecipeKnowledgePolicyTest,RecipeRepositoryTest` — 4 + 17 tests, 0
  failures/errors (integration tests ran against a real local PostgreSQL disposable schema, per
  `docs/TEST_STRATEGY.md` §31.2).
- `./mvnw -o test` (full default suite, `*IT.java` excluded by Surefire's default include pattern as
  before) — 86 tests, 0 failures/errors, `BUILD SUCCESS`. This covers all Phase 0/1 domain
  regressions (`craft.*`), `CraftingProfitCoordinatedScopeTest`, `CraftingBlockedRowsTest`, and every
  `repo.*`/`sync.*` integration test.

**Verification limitation:** the TestFX/desktop `*IT` suite (including
`CraftingDiscoveryViewBlockedRowIT`, `CraftingProfitViewSmokeIT`, etc.) was not run — these require a
real windowed desktop session per their own doc comments and are excluded from the default `test`
goal by design. Inspected `CraftingDiscoveryViewBlockedRowIT`'s fixture directly instead: it seeds
only one character with no cross-character unlock data, so the account-wide ownership correction
made here cannot change its outcome; no other existing UI/integration test seeds a multi-character
unlock-disagreement fixture, so none needed re-running for this change.

**Documentation reconciled:** `docs/KNOWN_PROBLEMS.md` §4.2 (now "resolved", disagreement corrected,
`isKnownByCharacter` reference removed) and its §6 summary table row; `docs/ROADMAP.md` §6 (recipe-
unlock exit criterion and the corresponding high-level story bullet marked done, satisfied by
`STORY-DOM-018`+`STORY-DOM-019` together); `docs/CURRENT_ARCHITECTURE.md` §9 item 2 and the related
§4 bullet (both updated to describe the single shared `isKnownAccountWide` policy, no SQL CTE, no
per-character exception). Phase 2 is **not** declared complete: its Exit Criteria list's first
bullet (bounded `PROJECT HEALTH REVIEW`, `TARGET_ARCHITECTURE.md` §34) remains unchecked in
`docs/ROADMAP.md` §6, per this story's Definition of Done.

## Blockers

None.
