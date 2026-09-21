## Story ID

STORY-DOM-018

## Title

Extract recipe knowledge policy and verify Discovery consistency

## Status

DONE

## Milestone

milestone-02

## Goal

Express recipe-known status as a persistence-independent domain/application concept used by both recipe loading and Discovery, preserving existing behavior and verifying that both paths agree on account-wide knowledge.

## Authoritative Source Documents / Sections

- `docs/ROADMAP.md` section 6, Phase 2 unlock-rule exit criterion and behavior-preserving dependency.
- `docs/KNOWN_PROBLEMS.md` section 4.2.
- `docs/TARGET_ARCHITECTURE.md` sections 7 and 10.
- `docs/DOMAIN_SPEC.md` sections 6, 34 and 35, and DQ-010.
- `agent/stories/STORY-DOM-017-independent-crafting-domain-types.md`, completed independent types and persistence mapping boundary.

## Context

Phase 2 explicitly requires moving recipe-unlock logic out of its SQL-only representation and confirming agreement between `RecipeRepository.loadRecipes` and `loadMissingDiscoverableRecipeIdsForCharacter`. The known-problem record identifies the second query as a separate implementation whose full semantics have not yet been confirmed. Account-wide knowledge must remain distinct from character discipline/rating eligibility and Discovery's other filters.

## Acceptance Criteria

1. Before restructuring, characterize how both existing query paths determine recipe-known status, recording the inputs, cases and any concrete discrepancy. Compare knowledge decisions independently of their different eligibility filters; do not assume their final result sets should be identical.
2. A domain/application-level recipe knowledge policy operates on independent values or domain objects without JDBC, SQL, repository-owned types or transport dependencies. Persistence supplies facts through the established mapping boundary rather than remaining the sole owner of the unlock rule.
3. Both recipe loading and missing-discoverable-recipe selection use that policy for recipe-known decisions. Verification demonstrates agreement for the same account knowledge facts, including a recipe learned by another character on the account.
4. Preserve existing query-facing contracts, recipe mapping, character discipline/rating eligibility, discoverability filtering and calculation behavior. Preserve distinctions between globally existing, account-known, character-usable and undiscovered recipes.
5. If characterization exposes a pre-existing semantic conflict, record it with evidence and report it as a blocker to claiming agreement; do not silently fix a domain bug within this behavior-preserving refactor or invent a new rule.

## Required Tests

- Add focused characterization checks before restructuring for the actual knowledge cases in both existing paths, using the existing PostgreSQL integration-test approach.
- Unit-test the extracted policy using independent inputs without a database or repository. Cover the knowledge branches identified during characterization and account-wide ownership.
- Run targeted integration checks for both repository entry points, proving policy wiring and parity of knowledge decisions while preserving their distinct eligibility filters.
- Run the existing Phase 0/1 domain tests to protect behavior preservation and inspect the new policy's dependencies for domain independence. Record actual commands, results and any verification limitations.

## Constraints

Behavior-preserving extraction only. No schema changes, new product semantics, broad repository redesign, UI redesign, connection-helper consolidation or later-milestone implementation. Ordinary class and method design remains an implementation choice. Keep database retrieval and mapping outside the independent policy.

## Dependencies

STORY-DOM-017 (DONE).

## Definition of Done

Acceptance criteria and required verification have recorded evidence. Result identifies the policy boundary, both integration points, characterized cases and any unresolved discrepancy. Update `docs/KNOWN_PROBLEMS.md` section 4.2 and relevant `docs/CURRENT_ARCHITECTURE.md` statements only as supported by implementation evidence. Do not mark the unlock exit criterion satisfied if agreement remains unverified or blocked.

## Result

**Characterization (AC1).** Read both existing query paths in full before changing anything:

- `RecipeRepository.loadRecipes(Connection, discipline)` (no character) selected recipes joined
  against a `WITH unlocked AS (SELECT recipe_id FROM account_recipes UNION SELECT recipe_id FROM
  character_recipes)` CTE — i.e. a recipe counted as known if it was in `account_recipes` **or in
  `character_recipes` for *any* character**, with no character filter at all.
- `RecipeRepository.loadRecipesForCharacter(Connection, charName, discipline)` used the same shape
  of CTE, but the `character_recipes` half was filtered to the *given* character only.
- `RecipeRepository.loadMissingDiscoverableRecipeIdsForCharacter(charName, discipline)` used two
  `NOT EXISTS` conditions with the same semantics as `loadRecipesForCharacter`'s per-character check:
  not in `account_recipes`, and not in `character_recipes` for the *given* character (not any
  character).

Comparing these independently of their (deliberately different) eligibility filters — discipline
filter, discoverable-flags filter, min-rating filter — found that `loadRecipes` (no character) and
`loadMissingDiscoverableRecipeIdsForCharacter` use **different unlock facts** for what should be the
same "recipe known" question per `DOMAIN_SPEC.md` §34/§35 ("unlocked anywhere on the account,
regardless of which character..."; "once any character unlocks the recipe, it must no longer appear
as undiscovered for other characters on the account"): `loadRecipes` looks at unlocks by *any*
character, while the discoverable query only looks at the *selected* character's own unlocks. A
recipe known only via a different character's `character_recipes` row (not yet reflected in
`account_recipes`) is treated as "known" by `loadRecipes` but still reported as missing/undiscovered
by the discoverable query for another character. `loadRecipesForCharacter` and the discoverable query
already agreed with each other (both use the per-character check) — the disagreement is specifically
between the account-wide entry point and the discoverable entry point.

**Policy extraction (AC2).** Added `craft.RecipeKnowledgePolicy` (no JDBC/SQL/repository/transport
import — verified by inspection): two pure functions over plain boolean unlock facts,
`isKnownAccountWide(unlockedForAccount, unlockedByAnyCharacter)` and
`isKnownByCharacter(unlockedForAccount, unlockedByThisCharacter)`, matching the two distinct concepts
found during characterization. `RecipeRepository` no longer expresses the unlock rule as SQL joins;
it fetches plain id sets (`account_recipes` ids, `character_recipes` ids for a given character or for
any character) and calls the policy in Java per candidate row, per `TARGET_ARCHITECTURE.md` §10's
mapping boundary.

**Wiring (AC3).** `loadRecipes`/`loadRecipesForCharacter` (recipe loading) and
`loadMissingDiscoverableRecipeIdsForCharacter` (Discovery) all now call `RecipeKnowledgePolicy` for
their recipe-known decisions: the former two via `isKnownAccountWide`/`isKnownByCharacter`
respectively, matching their existing fact scope; the discoverable query via `isKnownByCharacter`,
preserving its existing (narrower) fact scope exactly. A new integration test,
`RecipeRepositoryTest#knowledgeCrossCheck_recipeKnownOnlyByAnotherCharacterDisagreesBetweenLoadRecipesAndMissingDiscoverable`,
feeds both entry points the same account knowledge facts (a recipe known only via one character's
`character_recipes` row) and asserts the actual, current outcome: `loadRecipes` treats it as known
account-wide while `loadMissingDiscoverableRecipeIdsForCharacter` still reports it as missing for a
different character. This demonstrates the two entry points now share the same policy code, while
proving they do not yet agree for this case.

**Behavior preservation (AC4).** `loadMissingDiscoverableRecipeIdsForCharacter` added a
`Connection`-accepting overload (same pattern as `loadRecipes(Connection, ...)` from
`STORY-DOM-017`) but its filtering outcome — discoverable-flags condition, discipline condition, and
the account/this-character unlock exclusion — is byte-for-byte the same decision as before, just
computed via `RecipeKnowledgePolicy.isKnownByCharacter` in Java instead of two SQL `NOT EXISTS`
clauses. `loadRecipes`/`loadRecipesForCharacter` likewise preserve their existing discipline filter,
recipe mapping, and ingredient attachment; only the unlock decision moved from a SQL join to a
Java-side policy call over pre-fetched id sets. The distinction between globally existing
(`loadAllRecipes`, unchanged), account-known (`isKnownAccountWide`), character-usable
(`isKnownByCharacter`, combined with the existing discipline/min-rating filters), and undiscovered
recipes is preserved.

**Discrepancy reported, not fixed (AC5).** The confirmed conflict from characterization is recorded
with evidence in `docs/KNOWN_PROBLEMS.md` §4.2 and `docs/ROADMAP.md` §6, and is *not* silently fixed:
`loadMissingDiscoverableRecipeIdsForCharacter` still excludes based only on the selected character's
own knowledge (its pre-existing behavior), not on account-wide knowledge via any character, even
though `DOMAIN_SPEC.md` §35 literally reads as requiring the latter. Deciding whether to change that
query's exclusion fact scope is a domain-rule decision outside this behavior-preserving refactor's
scope, so the Phase 2 "confirmed to agree" exit criterion in `docs/ROADMAP.md` §6 is explicitly left
un-checked-off, per this story's Definition of Done.

**Tests run:**
- `./mvnw -q -o compile` and `./mvnw -q -o test-compile` — both clean.
- `./mvnw -q -o test -Dtest="craft.RecipeKnowledgePolicyTest,repo.RecipeRepositoryTest" -DfailIfNoTests=false` — 8 policy unit tests (all boolean-fact combinations for both policy methods) + 16 `RecipeRepositoryTest` cases (7 pre-existing + 9 new: `loadRecipesForCharacter` known-via-account/known-via-own-character/excluded-via-other-character, `loadMissingDiscoverableRecipeIdsForCharacter` included/excluded-via-account/excluded-via-own-character/excluded-via-non-discoverable-flags, and the cross-check discrepancy test), all against a disposable PostgreSQL schema — **0 failures, 0 errors**.
- `./mvnw -q -o test` (full suite) — **98 tests, 0 failures, 0 errors, 0 skipped**, including the existing `uiverify.CraftingDiscoveryViewBlockedRowIT`/`CraftingDiscoveryViewRefreshPreservationIT` (real-view TestFX ITs exercising the discoverable path end-to-end) and all Phase 0/1 `craft.*` domain tests, confirming no regression.
- Dependency inspection: `craft/RecipeKnowledgePolicy.java` has zero `import` statements — no JDBC/SQL/repository/transport dependency.

**Documentation updated:** `docs/KNOWN_PROBLEMS.md` §4.2 and its §6 summary row (policy extracted,
conflict confirmed and evidenced, not resolved); `docs/CURRENT_ARCHITECTURE.md` §9 item 2 (marked
resolved as a mixed-responsibility issue, conflict cross-referenced to `KNOWN_PROBLEMS.md` §4.2);
`docs/ROADMAP.md` §6 (exit criterion and high-level story bullet marked "partially done," explicitly
not fully satisfied).

**Remaining uncertainty:** Whether `loadMissingDiscoverableRecipeIdsForCharacter` should be changed
to use `isKnownAccountWide` (matching `DOMAIN_SPEC.md` §35 literally) is an open domain-rule decision
for a future story — not attempted here, per this story's constraints.

## Blockers

Milestone-level: the Phase 2 "recipe is unlocked... confirmed to agree" exit criterion
(`docs/ROADMAP.md` §6) remains open. A future story must decide whether
`loadMissingDiscoverableRecipeIdsForCharacter` should exclude based on account-wide knowledge (any
character) rather than the selected character's own knowledge, per the confirmed conflict recorded
in `docs/KNOWN_PROBLEMS.md` §4.2. This story itself has no blockers against its own Definition of
Done, which explicitly permits reporting rather than fixing this conflict.
