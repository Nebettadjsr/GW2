## Story ID

STORY-TEST-001

## Title

Shared in-memory domain test fixture for `craft.*` tests

## Status

DONE

## Milestone

milestone-00

## Goal

Extract a small, reusable, hand-built set of test items/recipes/prices (and a helper for constructing `PlannerContext`/`PlanState`/`CraftingSettings`) from the pattern already used in `CraftingResolverCraftVsBuyTest`, so future domain tests don't each hand-roll the same boilerplate.

## Authoritative Source Documents / Sections

- `docs/TEST_STRATEGY.md` §15 — prefer small, purpose-built fixtures over the full production crafting graph.
- `docs/TEST_STRATEGY.md` §22 — tests must be understandable without large hidden setup; a shared fixture must not violate this by hiding too much.
- `src/test/java/craft/CraftingResolverCraftVsBuyTest.java` — existing test; the pattern to extract from.
- `docs/KNOWN_PROBLEMS.md` §4.1 — the domain still depends on `repo.RecipeRepository`/`repo.tp.TpPriceRepository` nested types today; this fixture will necessarily use them too until Phase 2 decouples the domain. That is expected here, not a defect to fix in this story.

## Context

Upcoming domain regression tests — starting with `agent/stories/STORY-DOM-001-multiple-recipe-selection-test.md`, queued next — will otherwise each duplicate the same `Recipe`/`Ingredient`/`TpQuote`/`PlannerContext`/`PlanState` construction already written once in `CraftingResolverCraftVsBuyTest`. This story extracts a minimal, named set of helpers so new tests can focus on the behavior under test. This is test infrastructure only — no production behavior changes.

This story runs before STORY-DOM-001 specifically so STORY-DOM-001 can use the resulting fixture instead of hand-rolling its own setup a second time.

## Acceptance Criteria

- A small fixture/helper exists under `src/test/java/craft/` (e.g. a package-private support class) providing a few named, minimal recipes/items/prices and/or builder methods reusable across multiple tests.
- The fixture is deliberately small — not a generic recipe-graph builder framework (`docs/TEST_STRATEGY.md` §15).
- `CraftingResolverCraftVsBuyTest` is **not** required to be refactored to use it — this story adds support for tests going forward, it does not touch already-passing, already-reviewed tests.

## Required Tests

- No new behavior to test directly (this is test-support code).
- `./mvnw test` must still pass in full — confirms no regression from adding the new file.

## Constraints

- Do not modify `CraftingResolverCraftVsBuyTest.java` or any production code.
- Keep the fixture minimal — only what the next 1–2 known stories (multi-recipe selection, price-unavailable) will need. Do not build a speculative, general-purpose test framework.
- Follow `docs/CODING_GUIDELINES.md`'s version-agnostic guidance (naming, single responsibility, no dead comments).

## Definition of Done

- [x] Fixture/helper class added under `src/test/java/craft/`.
- [x] `./mvnw test` passes (existing test unaffected).
- [x] No production code changed.
- [x] `agent/runtime/artifacts/CLAUDE_RESULT.md` filled in.

## Result

Added package-private `src/test/java/craft/CraftTestFixtures.java` with small static
helpers (`ingredient`, `recipe`, `quote`, `noQuote`, `defaultSettings`, `context`,
`state`, `emptyState`) that build the same `Recipe`/`Ingredient`/`TpQuote`/
`PlannerContext`/`PlanState`/`CraftingSettings` objects `CraftingResolverCraftVsBuyTest`
already constructs by hand, without introducing a generic recipe-graph builder.
`CraftingResolverCraftVsBuyTest` was left untouched, as required.
`./mvnw test` passes (1 test, 0 failures) and the new file compiles into
`target/test-classes/craft/CraftTestFixtures.class`.

## Blockers

None.
