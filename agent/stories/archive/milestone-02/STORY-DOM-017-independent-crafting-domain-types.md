## Story ID

STORY-DOM-017

## Title

Separate crafting domain types from repository types

## Status

DONE

## Milestone

milestone-02

## Goal

Make the crafting engine and its existing domain tests operate on independent domain objects, with persistence-to-domain mapping owned by `repo.*`, while preserving current behavior.

## Authoritative Source Documents / Sections

- `docs/ROADMAP.md` §6, Phase 2 objective, independent-types/mapping/import/test exit criteria, and behavior-preserving dependency.
- `docs/TARGET_ARCHITECTURE.md` §7 Domain Independence Rule, §10 Persistence Boundary, and §25 Domain Tests.
- `docs/KNOWN_PROBLEMS.md` §4.1.

## Context

The current-phase evidence identifies repository-nested `Recipe`, `Ingredient`, and `TpQuote` classes as crafting-domain data types. Separating these types and porting their consumers and tests together establishes the Phase 2 domain boundary without changing calculation rules.

## Acceptance Criteria

1. Independent recipe, ingredient, and price-quote domain types replace repository-owned types used by the crafting engine. Preserve all data and semantics required by existing consumers, including unavailable/null quotes.
2. Conversion from persistence representations to domain objects lives in `repo.*`; domain objects and calculation code do not depend on JDBC, SQL, database classes, or transport objects.
3. `craft.*` has no imports from `repo.*` and does not indirectly retain repository-owned types in its domain signatures. Adapt affected callers at the boundary so existing application behavior remains intact.
4. Existing Phase 0/1 domain tests and shared domain fixtures construct only independent types, with no repository construction or database required. Preserve their existing behavioral assertions and characterization expectations.
5. Existing recipe/ingredient and TP-quote persistence behavior remains intact through the mapping, including missing quotes and nullable prices. No domain bug fixes are bundled with the refactor.

## Required Tests

- Run the existing Phase 0/1 domain tests after migration; record actual commands and results, including any inability to execute them.
- Run the existing recipe and TP-price repository integration checks against the mapping boundary; add targeted mapping assertions only where existing checks do not protect data preservation.
- Verify the absence of `repo.*` imports and repository-owned types in crafting-domain code and domain fixtures/tests by a focused dependency inspection.
- Compile affected callers and use existing targeted checks for affected calculation paths; report concrete verification limitations.

## Constraints

Behavior-preserving refactor only. Preserve current selection, inventory/binding, coordinated-character, pricing, blocked-result, and calculation semantics. Do not extract unlock policy in this story, consolidate connection helpers, change database schema, redesign the UI, introduce a backend module, or implement later-milestone architecture. Choose ordinary class/package and mapping details during implementation within the stated boundary.

## Dependencies

None.

## Definition of Done

Acceptance criteria are met; required checks have recorded evidence and limitations; the Result identifies the domain types, mapping boundary, and migrated test coverage. Update relevant current-architecture and known-problem statements only where the implementation provides evidence of changed facts.

## Result

Introduced independent crafting-domain types `craft.Recipe`, `craft.Ingredient`, and `craft.PriceQuote`
(same field shapes as the repository-nested classes they replace, including `PriceQuote`'s nullable
`buyUnit`/`sellUnit`). `repo.RecipeRepository` and `repo.tp.TpPriceRepository` no longer declare nested
`Recipe`/`Ingredient`/`TpQuote` classes; their existing `loadRecipes`/`loadRecipesForCharacter`/
`loadAllRecipes`/`loadTpQuotes` methods now map JDBC rows directly into the independent types, so
`repo.*` is the sole persistence-to-domain mapping boundary (TARGET_ARCHITECTURE.md section 10).

`craft.CraftingResolver`, `CraftingPlanner`, `RecipeSimulator`, `PlannerContext`, `CraftingGraph`,
`RecipeTreeBuilder`, and `CostEvaluator` were updated to use the independent types directly and no
longer import anything from `repo.*`. `craft.CraftingGraphCache` (the on-disk crafting-graph cache,
which depends on `RecipeRepository` for DB loads/counts and on Jackson for JSON serialization - both
excluded from the domain layer by TARGET_ARCHITECTURE.md section 7) moved to `repo.CraftingGraphCache`
together with its serialization DTO (`repo.CraftingGraphDto`), since it is a persistence/cache
concern, not domain logic; callers (`CraftingProfitController`, `CraftingDiscoveryController`,
`Gw2App`, and the `uiverify.CraftingUiTestFixtures` test fixture) were updated to reference the new
location. This was a necessary boundary decision beyond a pure rename: leaving the cache in `craft.*`
would have kept a `repo.*` import (and a JSON-transport DTO) inside the domain package.

Controller/UI-layer callers (`CraftingProfitController`, `CraftingDiscoveryController`,
`CraftingDiscoveryView`, `CraftingResultPresentation`) were adapted to reference `craft.Recipe`/
`craft.Ingredient`/`craft.PriceQuote` instead of the removed repository-nested types; they still
construct `RecipeRepository`/`TpPriceRepository` instances directly (unchanged - these are UI/adapter
classes outside `craft.*`, not domain code, and this story's scope is the `craft.*` boundary per its
Constraints).

All `craft.*` domain tests, `CraftTestFixtures`, `CraftingBlockedRowsTest`, and the `repo.*` persistence
integration tests (`RecipeRepositoryTest`, `TpPriceRepositoryTest`, `CharacterSelectionCraftingPlanIntegrationTest`)
were updated to construct/reference the independent types; no domain test's behavioral assertions were
changed, only the types referenced. `craft.*` test fixtures now construct only independent types with
no `repo.*` import and no database involved.

**Tests run:**
- `./mvnw -q -o compile` and `./mvnw -q -o test-compile` — both clean (offline, against the vendored/cached dependencies already present).
- `./mvnw -q -o test -Dtest="craft.**,CraftingBlockedRowsTest" -DfailIfNoTests=false` — the Maven Surefire invocation in this environment ran the full existing suite (not just the requested filter): 81 tests across 33 classes, **0 failures, 0 errors, 0 skipped**. This included every `craft.*` domain test, `CraftingBlockedRowsTest`, the `repo.RecipeRepositoryTest`/`repo.tp.TpPriceRepositoryTest`/`repo.CharacterSelectionCraftingPlanIntegrationTest` PostgreSQL integration tests, and the `uiverify.*` real-view TestFX ITs (which exercise the relocated `repo.CraftingGraphCache` end-to-end via `CraftingUiTestFixtures`) — confirming behavior preservation across the whole boundary, not just the domain-test subset the story requires.

**Dependency-inspection verification (AC3):** `grep -rn "^import repo" src/main/java/craft src/test/java/craft` and a repo-wide search for `RecipeRepository.Recipe`/`RecipeRepository.Ingredient`/`TpPriceRepository.TpQuote` both return no matches - `craft.*` has no `repo.*` imports anywhere, in production or test code.

**Documentation updated:** `docs/CURRENT_ARCHITECTURE.md` section 9 item 1 (marked resolved),
`docs/KNOWN_PROBLEMS.md` section 4.1 and its section 9 summary line (marked resolved), and
`docs/ROADMAP.md` section 6's now-true exit criteria/high-level-story bullets (marked done), following
the existing `**(Done)**`/`(resolved)` conventions used elsewhere in those files. The remaining Phase 2
exit criteria (PROJECT HEALTH REVIEW, moving the "recipe is unlocked" rule out of SQL, and consolidating
`repo.Db`/`sync.Db`) are explicitly out of this story's Constraints and remain open for later stories.

**Remaining uncertainty:** None identified against the stated acceptance criteria. No domain bug fixes
were bundled with this refactor; all changes are type/package relocations and import adjustments.

## Blockers

None.
