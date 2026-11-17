## Story ID

STORY-UI-001

## Title

Establish repeatable JavaFX verification for Phase 1 crafting views

## Status

DONE

## Milestone

milestone-01

## Goal

Provide a reusable automated UI-verification capability that can execute the outstanding user-facing checks in STORY-DOM-013, STORY-DOM-014 and STORY-DOM-015.

## Authoritative Source Documents / Sections

- Supplied Phase 1 objective and exit criterion requiring visible blocked/unavailable crafting results.
- STORY-DOM-013, Required Tests and Definition of Done.
- STORY-DOM-014, Required Tests.
- STORY-DOM-015, Required Tests and Result (interactive verification limitation).
- docs/TARGET_ARCHITECTURE.md §25, Existing JavaFX UI Verification Capability (permanent owner of the processed automated-javafx-ui-verification.md request).

## Context

STORY-DOM-015 records repository/planner evidence and application startup, but explicitly does not claim an executed interactive session. Existing domain stories already own the behavioral acceptance cases. This story establishes their reusable verification mechanism without duplicating their implementation scope. Tool versions and compatibility remain unverified in planning.

## Acceptance Criteria

- During implementation, inspect the actual JavaFX/Maven/test setup and evaluate TestFX or an equivalent maintained approach. Record compatibility evidence from a working minimal prototype before adopting substantial tooling; do not assume versions or compatibility.
- Provide a repeatable local command that launches the application under controlled test conditions, detects readiness or startup failure, and terminates the application and test resources cleanly on success and failure.
- Demonstrate a minimal smoke test against a real crafting view that selects an available ComboBox entry, activates a relevant button, and asserts observable TableView or displayed-state output. A test of standalone demonstration controls is insufficient.
- Supply reusable control lookup, bounded waiting, displayed-state inspection and optional screenshot capture suitable for the existing stories' selection, refresh, empty/error and blocked-row checks. Avoid fixed coordinates and arbitrary timing assumptions.
- Keep data deterministic: use controlled fixtures or a disposable test database when persistence is required, never the developer's normal account database or live GW2 API as a normal test dependency. Introduce only the minimal test seams needed; preserve production correctness.
- Document the established test layer, command, desktop/session prerequisites, fixture lifecycle, diagnostics and limitations in docs/TEST_STRATEGY.md. Document any useful repository-scoped PowerShell interim procedure and its limitations; manual fallback must identify the specific impractical automation case.
- Leave the broader behavior matrix to STORY-DOM-013 through STORY-DOM-015. Record actual commands and outcomes, including failures or unexecuted checks, without claiming UI coverage from compile success or source inspection.

## Required Tests

- Run the real-view smoke test repeatedly to demonstrate stable startup, control interaction, observable assertions and cleanup.
- Exercise startup/verification failure handling and confirm bounded termination and useful diagnostics.
- Exercise screenshot capture and verify that a usable artifact is produced when requested.
- Run existing relevant regression tests after any test-seam changes; verify the established non-UI test invocation remains usable.

## Constraints

Phase 1 only; no broad restructuring, production behavior changes merely for test convenience, or speculative tooling permissions. Tool choice is an implementation evaluation under TARGET_ARCHITECTURE.md §25, not a preselected dependency. Keep domain and PostgreSQL correctness tests intact; UI tests do not replace them.

## Dependencies

None. Use existing crafting behavior for the capability smoke test; do not require the pending All characters implementation to establish the harness.

## Definition of Done

Reusable capability demonstrated against the actual application, required checks executed and recorded, methodology documented, and existing crafting stories able to use the command for their own acceptance cases.

## Result

Established a TestFX-based JavaFX UI-verification layer (`docs/TEST_STRATEGY.md` §32), reusable by STORY-DOM-013/014/015. Most of the harness (`uiverify.JavaFxUiSupport`, `uiverify.CraftingUiTestFixtures`, `uiverify.CraftingProfitViewSmokeIT`, `uiverify.TestFxPrototypeIT`, and the `testfx-junit5:4.0.18` dependency) already existed on disk from an earlier interrupted session; this pass verified it against the real application, found and fixed three real defects blocking it, and wrote the required `docs/TEST_STRATEGY.md` documentation (none previously existed).

Defects found and fixed:
- `CraftingUiTestFixtures` pre-created its crafting-graph-cache temp file via `Files.createTempFile`, leaving an empty file where `CraftingGraphCache.load()` expected either a missing file (rebuild) or valid JSON (parse) — caused `MismatchedInputException` on every run. Fixed by deleting the reserved path immediately after creating it.
- `CraftingProfitView`'s real "Refresh" button (`btnRefresh`) was fully implemented and wired (`setOnAction`) but never added to any visible container — a pre-existing defect confirmed present at `HEAD`, unrelated to this story or the other in-flight uncommitted changes in this working tree. Fixed by adding it to `filterRow3`; no calculation/domain logic touched.
- The prototype class `TestFxPrototypeIT` starts with `"Test"`, so it matched Surefire's default `**/Test*.java` inclusion pattern and silently ran as part of the normal `./mvnw test` goal despite its own "IT suffix excludes it" comment. Renamed to `FxCompatibilityPrototypeIT`, confirmed excluded by diffing `target/surefire-reports/` before/after a default run.

Verified: `./mvnw test -Dtest=FxCompatibilityPrototypeIT` (compatibility prototype) and `./mvnw test -Dtest=CraftingProfitViewSmokeIT` (real-view smoke test: ComboBox selection, Refresh button click, TableView assertion, screenshot capture) both pass individually, together, and on repeated runs; screenshot artifact confirmed non-empty. Startup and control-lookup failure handling were exercised for real while fixing the two defects above — both failed in ~3s with a clear diagnostic and no hang, and JUnit's extension/`@AfterAll` lifecycle still tore down the FX toolkit and dropped the fixture schema on those failing runs. `./mvnw test` (default goal) passes before and after these changes and does not select either UI test.

Full detail, exact commands, prerequisites, fixture lifecycle, and limitations (headful-only; no CI headless config; no PowerShell fallback needed — none of this story's or the dependent stories' checks were found to be impractical through TestFX) are recorded in `docs/TEST_STRATEGY.md` §32, per that document's ownership of "how this layer is tested" versus `docs/TARGET_ARCHITECTURE.md`'s ownership of "that this capability must exist."

Out of scope, left to the owning stories: the STORY-DOM-013/014/015 behavior matrix itself (blocked-row visibility, All-characters coordination, refresh/sort/filter preservation, zero-character handling) — none of those checks were executed here.

## Blockers

None.
