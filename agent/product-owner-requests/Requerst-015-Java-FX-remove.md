# PO Request — Complete Removal of JavaFX and Legacy Desktop UI

## Goal

Completely remove the JavaFX desktop UI and all remaining JavaFX-specific code, dependencies, resources, configuration, and obsolete infrastructure from the GW2 project.

The project is transitioning toward a web-based application. JavaFX is no longer part of the intended product architecture and must not remain as a parallel or legacy UI implementation.

## Milestone Priority — Mandatory

**The complete removal must be finished within the CURRENT milestone, before its final Health Review and milestone closure.**

The Planner must incorporate this work into the remaining current-milestone scope.

Do not defer JavaFX removal to a future milestone.

Update the ROADMAP and any future milestones that currently reference JavaFX, desktop UI maintenance, desktop-specific features, or eventual JavaFX retirement.

Remove obsolete work and adjust dependencies and milestones accordingly.

## Requirements

### 1. Remove JavaFX Completely

Identify and remove all JavaFX-specific components, including:

- JavaFX views, controllers, application entry points, and UI logic.
- FXML files, stylesheets, images, and other resources used exclusively by the desktop UI.
- JavaFX dependencies, plugins, and build configuration.
- Desktop-specific initialization, packaging, and startup scripts.
- Obsolete adapters, utilities, and configuration supporting only the JavaFX application.
- JavaFX-specific tests, fixtures, and test infrastructure that are no longer relevant.
- Unused code made obsolete by removing the desktop application.

Do not retain unused compatibility layers or deprecated JavaFX implementations for hypothetical future use.

### 2. Preserve Application Functionality

Removing JavaFX must not unintentionally remove functionality required by the web application or backend.

Before deleting shared components, identify their remaining consumers.

Preserve reusable domain logic, crafting calculations, repositories, synchronization capabilities, and other application services where still required.

If necessary, separate reusable functionality from JavaFX dependencies before removing the UI.

Do not automatically reimplement obsolete desktop functionality in the web frontend unless it is required by the existing product specifications.

### 3. Architecture and Documentation

Update the relevant documentation, including:

- `docs/TARGET_ARCHITECTURE.md`
- `docs/CURRENT_ARCHITECTURE.md`
- `docs/ROADMAP.md`
- Other affected architecture, runtime, testing, and product documents.

The target architecture must clearly establish that the application no longer supports or plans to support JavaFX.

Remove outdated references to JavaFX as an active or planned application component.

Historical documentation may retain references where necessary to describe previous decisions.

### 4. Roadmap and Future Milestone Cleanup

Review all remaining and future milestones for JavaFX-related work.

- Remove stories and planned tasks that exist exclusively to maintain or extend JavaFX.
- Remove future JavaFX retirement tasks, since retirement must happen in the current milestone.
- Reevaluate dependencies on removed desktop components.
- Preserve functional requirements that remain relevant to the web application.
- Adjust milestone descriptions and sequencing to reflect the web-only direction.
- Avoid generating replacement work for functionality that is no longer part of the product scope.

The Planner must ensure that these changes are reflected in the actual ROADMAP and backlog, not only in TARGET_ARCHITECTURE.

### 5. Build, Tests, and CI

After removal:

- The application must build without JavaFX dependencies.
- Backend and web functionality must remain operational.
- Existing relevant automated tests must continue to pass.
- Remove obsolete JavaFX-specific build flags and CI configuration.
- Ensure no production entry point depends on JavaFX.
- Verify that no JavaFX-specific imports or runtime dependencies remain in the active application.

Normal QA requirements and existing CI quality gates apply to this work.

## Constraints

- Complete the removal within the current milestone.
- Do not introduce replacement desktop frameworks.
- Do not add new web features solely to compensate for removed JavaFX functionality.
- Preserve existing backend behavior and product requirements.
- Do not perform unrelated refactoring.
- Do not retain obsolete code solely for backward compatibility with the retired desktop application.
- Do not modify the AI development orchestrator or its internal workflow unless genuinely required by the removal.

## Definition of Done

1. JavaFX and all exclusively JavaFX-related remnants are removed from the active project.
2. The application builds and passes its relevant tests without JavaFX.
3. No active runtime path requires the desktop application.
4. Shared backend functionality remains intact.
5. TARGET_ARCHITECTURE and CURRENT_ARCHITECTURE reflect the web-only application.
6. ROADMAP and future milestones no longer contain obsolete JavaFX-related work.
7. All required changes are completed and verified before the current milestone is closed.

**Expected Outcome:** The GW2 project has a clean, web-focused architecture without legacy JavaFX components, dependencies, or future maintenance obligations.

## Status

OPEN

## Planner Resolution

2026-10-21: Added mandatory removal before the Phase 6 health review to `docs/ROADMAP.md` §10 and web-only intent to `docs/TARGET_ARCHITECTURE.md` §§2, 14 and 16. Created `agent/stories/STORY-UI-004-remove-legacy-javafx-application.md` and `agent/stories/STORY-INFRA-005-remove-javafx-build-and-test-remnants.md` for removal, shared-consumer preservation, build/test cleanup and updates to current architecture/runtime/testing documents; requested their backlog additions in this pass. This request remains OPEN because its explicit review and correction of later roadmap milestones cannot be performed under this run's Phase 6 lock. The milestone may not close before the requested removal is verified.