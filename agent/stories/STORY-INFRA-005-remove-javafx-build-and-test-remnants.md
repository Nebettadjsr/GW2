## Story ID

STORY-INFRA-005

## Title

Remove JavaFX dependencies and remaining desktop build infrastructure

## Status

BLOCKED

## Milestone

milestone-06

## Goal

Finish the JavaFX removal by eliminating obsolete build, test and configuration dependencies after its application code is retired.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md, Phase 6 JavaFX removal exit criterion.
- docs/TARGET_ARCHITECTURE.md sections 2, 13 and 14.
- docs/TEST_STRATEGY.md sections 26, 32 and 36.
- docs/QUALITY_METRICS.md, Test coverage KPI and Generation and review.

## Context

STORY-UI-004 removes the desktop application. Its build and test infrastructure must then be removed without damaging backend or web functionality.

## Acceptance Criteria

1. Remove JavaFX dependencies, plugins, classifiers, desktop-only build flags, packaging/startup scripts and configuration after verifying no retained production consumer needs them.
2. Remove JavaFX-specific test fixtures, runners and CI steps that have no retained subject; keep meaningful backend and browser verification. Remove obsolete desktop-only resources or unused compatibility code missed by the first story.
3. The application builds and the backend starts without JavaFX artifacts or a display. No active production entry point or active source import depends on JavaFX.
4. Update affected current-state, current-architecture and testing documentation to match the delivered build/runtime and verification setup; preserve historical records as history.

## Required Tests

- QA specifies targeted build, backend-startup and dependency/import checks demonstrating absence of JavaFX from active application paths.
- QA verifies affected backend and browser behavior with existing or new meaningful checks and reviews coverage impact on retained code.
- No TestFX run is required after its subject has been removed; any retained behavior previously verified only through TestFX receives an appropriate non-desktop check.

## Constraints

- No replacement desktop framework, speculative compatibility layer, unrelated refactoring or new browser features.
- QA owns all tests and fixtures; the coding agent does not write or maintain them.
- Do not modify the agent workflow.

## Dependencies

STORY-UI-004.

## Definition of Done

- No active application code, build dependency, startup path or relevant test infrastructure requires JavaFX; backend/web checks pass and documentation matches.
- The CI gate is green (verified by the pipeline after acceptance).

## Result

Not started.

## Blockers

Waiting on dependencies: depends on STORY-UI-004, which is not DONE (currently TODO)
