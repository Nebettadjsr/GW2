## Story ID

STORY-WEB-021

## Title

Give Ectoplasm results stable content hooks for browser checks

## Status

TODO

## Milestone

milestone-05

## Goal

Let Ectoplasm browser and component checks identify loaded, data-driven result regions without relying on styling classes or the always-rendered screen root.

## Authoritative Source Documents / Sections

- `agent/stories/STORY-WEB-019-layout-smoke-navigation-coverage.md`, Follow-up Findings F002–F003.
- `agent/stories/STORY-WEB-013-ectoplasm-salvage-page.md`, result, loading and failure presentation criteria.
- `docs/TEST_STRATEGY.md` §12.1–12.2, frontend state and browser verification.
- Supplied `docs/ROADMAP.md` Phase 5, Ectoplasm screen and rendering/interaction/state tests.

## Context

The screen's existing `ecto-screen` hook is present during loading and failure. Checks currently use presentational classes to locate populated result content. The same component also retains two scoped style rules for removed elements.

## Acceptance Criteria

1. Add stable `data-test` hooks to the Ectoplasm screen's data-driven result regions so checks can distinguish rendered answer content from loading and failure states.
2. Update relevant Ectoplasm browser and component assertions to use those hooks for content readiness and result checks instead of style-only selectors where a stable hook is appropriate.
3. Preserve existing checks for the four result scenarios, loading, failure, and reload behavior; a root hook alone must not count as a loaded answer.
4. Remove the unused `.result-conclusion` and `.tool-separator` scoped rules identified in the finding, without changing visible page behavior.

## Required Tests

- Run focused Ectoplasm component tests for loading, failure and populated results.
- Run the Ectoplasm and layout browser smoke checks that consume the new result hooks in a controlled browser runtime.

## Constraints

- Keep changes within Ectoplasm presentation and its checks; do not change backend contracts or economic calculations.
- Preserve existing visual and accessible meaning for result states.

## Dependencies

- STORY-WEB-019 (source of the finding; finish its smoke restructure first).

## Definition of Done

Acceptance criteria are met, named checks pass, and Result records the state and browser evidence.

## Result

Not started.

## Blockers

STORY-WEB-019 must complete first.
