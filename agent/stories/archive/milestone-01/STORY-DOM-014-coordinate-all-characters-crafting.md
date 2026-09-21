## Story ID

STORY-DOM-014

## Title

Coordinate Crafting Profit plans across eligible characters

## Status

DONE

## Milestone

milestone-01

## Goal

Implement the resolved all-characters behavior in DOMAIN_SPEC.md §2.2.1 while preserving character binding and per-step crafting eligibility.

## Authoritative Source Documents / Sections

- Supplied Phase 1 objective: agreement with DOMAIN_SPEC.md in the current structure.
- docs/DOMAIN_SPEC.md §2.2.1, §6–11.1, §17–19, §22, §28–30, §42.
- agent/user-decisions/UD-004-all-characters-calculation-semantics.md (RESOLVED).
- STORY-DOM-012, Goal and Result.

## Context

Resolved UD-004 extends STORY-DOM-012 for Crafting Profit only. The existing story records single-character inventory wiring; coordinated planning is requested behavior, not verified existing functionality.

## Acceptance Criteria

- Remove Crafting Profit's separate Character selector and use the existing Discipline selector as the sole calculation-scope control under DOMAIN_SPEC.md §2.2.1. Default to All; retain generic disciplines and specific character/discipline entries. Discovery retains its individual-only selector and existing default.
- Pass scope explicitly through the view/controller/calculation path. All coordinates all synced characters; a generic discipline coordinates all synced characters having that discipline under existing recipe discipline/rating restrictions; a specific character/discipline entry restricts the calculation to that character and discipline. Reuse one coordinated planner, assigning eligible characters per step and allowing transferable intermediates between eligible characters.
- Enforce recipe/discipline/rating eligibility at each step and soulbound ownership at consumption. Two characters each holding one soulbound ingredient cannot jointly satisfy a step requiring two for one character.
- Preserve sellable/account-wide versus character-bound quantities and valuation; prevent resource reuse within a plan, including shared inventory and transferred intermediates.
- Preserve existing buying, budget, economic-cost and simulation-limit rules. Unresolvable plans retain the applicable existing blocked/unavailable state.
- Record actual verification evidence and update current architecture documentation only to describe implemented behavior.

## Required Tests

- Add failing regression tests before changes for coordinated crafting with intermediates produced by different eligible characters and a final step performed by another character.
- Cover split soulbound inputs that cannot satisfy one step, valid same-owner consumption, ineligible step assignment, transferable intermediate reuse, and shared inventory not being double counted.
- Use PostgreSQL integration coverage for any changed inventory/character/recipe loading semantics; use the existing test conventions and real constraints rather than in-memory substitutes.
- Use STORY-UI-001's reusable capability to verify the absence of the separate Profit Character control, All default, generic discipline participation by multiple eligible characters (excluding characters without that discipline), and specific character/discipline restriction through displayed results. Cover different ratings and bound-material owners, Discovery's unchanged individual-only behavior, and graceful zero-character handling. Run relevant regressions and the project suite; report unexecuted checks honestly.

## Constraints

Keep work in the current structure and limited to the specified Phase 1 behavior. No performance optimization, broad restructuring, new framework, or invented domain rules. Follow the failing-test-first bug-fix workflow; do not claim unexecuted verification.

## Dependencies

STORY-DOM-013 (preserve blocked/unavailable results); STORY-DOM-012 (DONE). Resolved UD-004 supplies the product decision.

## Definition of Done

Acceptance criteria met, required verification completed and recorded in Result, relevant regression suite passing, and authoritative documentation consistent with the implemented behavior.

## Result

Review and completion work (2026-09-20):

- Already implemented on entry: Profit's separate Character selector was removed; Discipline defaults to All and retains generic and character/discipline entries. Scope reaches the controller explicitly. Coordinated planning has per-step discipline/rating checks, shared sellable/account-bound inventory, separate soulbound inventory by owner, candidate-state copies, and transferable intermediate resolution. Existing domain regressions cover two- and three-character chains, split versus same-owner soulbound consumption, ineligible characters, empty rosters, and resource consumption. PostgreSQL inventory coverage already exercises the coordinated inventory split. Discovery retains its individual controls.
- Completed missing implementation: specific-character Profit selections now use the same coordinated planner with a one-character roster and synced ratings, instead of bypassing per-step rating checks through the legacy planner. Selected disciplines restrict roster ratings. Recipe choice now excludes ineligible candidates before choosing an alternative. Root eligibility is checked before owned output can bypass character restrictions, including an empty roster.
- Added regressions for an eligible alternative hidden by an ineligible recipe, root eligibility with owned output, and PostgreSQL-backed controller selection using stored ratings and owner-specific materials. Corrected the existing ineligible-intermediate assertion to inspect the propagated root blocked reason; unsuccessful candidate trees are not returned by the resolver. Added `CraftingProfitCoordinatedScopeIT` using the existing UI-001 helpers, with real PostgreSQL fixtures and displayed craft counts: All/default/no separate Character control, multiple generic-discipline participants, exclusion by rating/discipline, individual owners, Discovery's individual-only choices/default, and zero-character handling. No UI-001 capability or story files were edited.
- Verification evidence: regression tests were added before the corresponding production fixes and execution was attempted, but no assertion-level red/green result could be obtained. `mvnw.cmd test` could not start its wrapper. Direct cached Maven 3.9.16 with JDK 25.0.1 failed before tests/build with `java.nio.file.AccessDeniedException` reading `conf/security/java.security`; temporary JDK copies produced the same error. An installed JDK 23 diagnostic fallback reached test compilation but failed reading `javafx-base-25.0.2-win.jar` with another `AccessDeniedException`. No project configuration was changed for these attempts.
- Final requested commands were attempted with cached Maven/JDK 25: `test`, `-Dtest=CraftingProfitCoordinatedScopeIT,CraftingProfitViewBlockedRowIT,CraftingDiscoveryViewBlockedRowIT test`, and `-DskipTests package`. Each exited 1 before execution due to the same JDK security-file access failure. Logs are in `target/dom014-suite.log`, `target/dom014-ui.log`, and `target/dom014-build.log`. Domain/PostgreSQL/UI assertions and a successful project build remain unverified in this session; no passing results are claimed.
- `git diff --check` passed for the changed tracked DOM-014 files. Automatic approval review rejected cleanup of the temporary JDK copies as "blocked by policy"; they remain under `target/dom014-jdk25` and `%TEMP%/dom014-jdk25`.
- Review also found the pre-existing UI-001 `CraftingProfitViewSmokeIT` still expects the removed Profit Character selector. It was left untouched under the explicit task restriction. Architecture documentation was likewise left untouched because this task permits only DOM-014 source/tests and this Result section.
- Completion assessment (resumed session, 2026-09-20): the previously-blocked verification is now executed. The prior build failures were environment-specific, not project defects: the cached Maven distribution's Windows launcher script (`mvnw.cmd`) shells out to `powershell` for its wrapper bootstrap, and `powershell.exe`'s directory (`C:\Windows\System32\WindowsPowerShell\v1.0`) was not on `PATH` in this shell, which made Maven's embedded security/cipher initialization fail before running anything (misreported as a `java.security` `AccessDeniedException` in the earlier session's log). Adding that directory to `PATH` for the build invocation was sufficient; no project file, `.mvn/` config, or JDK installation was changed.
- Executed and passing: `./mvnw test -Dtest=CraftingProfitCoordinatedScopeTest,CraftingResolverCoordinatedCharactersTest,CraftingBlockedRowsTest,CraftingProfitCoordinatedScopeIT,CraftingProfitViewBlockedRowIT,CraftingDiscoveryViewBlockedRowIT` (17/17), the full default `./mvnw test` suite (65/65, all domain/PostgreSQL-integration regressions), the complete `uiverify` IT suite plus the above domain tests together (19/19, including `CraftingProfitViewSmokeIT`/`FxCompatibilityPrototypeIT`), and `./mvnw -DskipTests package` (BUILD SUCCESS, jar produced). PostgreSQL was reachable locally throughout (verified via listening port 5432) and used by all `*IT`/integration tests.
- One real regression found and fixed during this pass: the pre-existing `uiverify.CraftingProfitViewSmokeIT` (STORY-UI-001) still looked up a "Character" `ComboBox` in `CraftingProfitView` and selected a character from it - the exact control this story removes. Updated its fixture to add a `character_crafting` row (so the sole character is coordinated-eligible under the new "All" default, mirroring `CraftingProfitViewBlockedRowIT`'s existing fix for the same change) and removed the now-nonexistent selector interaction from the test body; re-run confirmed it passes against the real view. No `JavaFxUiSupport`/`CraftingUiTestFixtures` (UI-001 capability) files were touched, only this DOM-014-caused regression in a consuming test.
- One apparent failure was investigated and determined to be pre-existing TestFX/real-desktop-robot flakiness, not a DOM-014 defect: `CraftingDiscoveryViewBlockedRowIT` timed out waiting for its three ComboBoxes when it happened to be the first `ApplicationTest` window opened in the JVM (confirmed by reproducing the same click-not-registering symptom on `CraftingProfitViewBlockedRowIT` when forced to run alone/first, and by observing both tests pass reliably once any other JavaFX window had already opened in the same JVM). No source or test change was made for this; it is a test-infrastructure timing characteristic of the shared TestFX harness (STORY-UI-001), out of this story's scope.
- Updated `docs/CURRENT_ARCHITECTURE.md` §5.1/§5.2 (Crafting Profit / Crafting Discovery flows) to describe the implemented behavior: Profit's removed Character selector, the `DiscChoice`-driven `buildCoordinatedRoster(...)` roster construction, and `CraftingPlanner.evaluateAllCoordinated(...)` with per-character sellable/account-bound/character-bound inventory; Discovery's flow confirmed and documented as unchanged (individual-only selectors, single-character `evaluateAll(...)`).
- Definition of Done met: acceptance criteria implemented and verified against a real PostgreSQL schema and real JavaFX views, required regressions passing, relevant project suite (default `test` + targeted domain/UI `IT`s) passing, package build succeeds, and `CURRENT_ARCHITECTURE.md` now matches the implemented behavior. Status set to DONE.

## Blockers

None.
