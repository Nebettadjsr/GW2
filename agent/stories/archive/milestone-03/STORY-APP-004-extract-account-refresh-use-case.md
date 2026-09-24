## Story ID

STORY-APP-004

## Title

Route account refresh through an application service

## Status

DONE

## Milestone

milestone-03

## Goal

Introduce a named account-refresh application use case, migrate Gw2App's account sync action to it, and resolve the unused AccountRefreshService orchestration.

## Authoritative Source Documents / Sections

- Supplied docs/ROADMAP.md section 7, Phase 3: account-refresh application service, Gw2App handler migration, and AccountRefreshService dead-call exit criteria.
- docs/TARGET_ARCHITECTURE.md section 8 (Application Layer) and section 25 (Application Tests).
- docs/CURRENT_ARCHITECTURE.md sections 5.4, 6 and 7 (account sync calls, unused service and UI execution/reporting).
- docs/KNOWN_PROBLEMS.md section 8 (AccountRefreshService has no Gw2App call site).

## Context

The documented Sync Account action directly invokes account bank/material/recipe synchronization and character crafting/recipe synchronization. AccountRefreshService.refreshAll() duplicates a subset of setup's account synchronization but is unused by Gw2App. Profit and Discovery already have application services; the existing Ectoplasm story does not cover account refresh.

## Acceptance Criteria

1. A named application service owns the existing account-refresh orchestration, including the account bank, materials, recipes and character synchronization operations currently invoked by the Sync Account action. Establish the exact existing call order and failure behavior during implementation and preserve them.
2. Gw2App's Sync Account handler delegates to this service instead of calling sync.*, repo.* or craft.* directly. Preserve background execution, UI-thread status updates and existing success/failure reporting.
3. Resolve the existing AccountRefreshService dead-call situation by adapting and wiring it as the application use case or replacing it and removing the unused implementation. Keep one authoritative account-refresh orchestration, without competing unused service code.
4. The service has no JavaFX dependency and coordinates replaceable infrastructure collaborators; it does not acquire presentation responsibilities or introduce domain calculations.
5. Preserve first-time setup behavior. If consolidating shared account operations changes InitialSetupService, retain its existing operation selection, order and failure behavior; do not substitute the broader account-refresh workflow blindly.
6. Update docs/CURRENT_ARCHITECTURE.md's affected account flow and docs/KNOWN_PROBLEMS.md section 8's dead-call entry to match the result. Record verification evidence and limitations without declaring other synchronization use cases or Phase 3 complete.

## Required Tests

- Fake/in-memory-adapter application tests verify the account action's existing operation selection and order, successful completion, and failure propagation/short-circuit behavior without live HTTP or a database.
- A targeted deterministic JavaFX check with controlled service behavior verifies Sync Account delegates and presents success/failure results while retaining background execution and UI-thread updates. Reuse the established UI verification capability; record any unavailable prerequisite or limitation.
- If shared setup orchestration changes, add a focused fake-adapter regression protecting its existing operation selection and ordering. Run affected existing account/character synchronization tests where production adapter behavior is touched.

## Constraints

- Behavior-preserving Phase 3 application-service extraction only.
- Keep existing synchronization persistence and domain semantics; do not change API-key persistence, add scheduling, or redesign threading.
- Limit setup edits to necessary account-orchestration reuse. Global data refresh, Trading Post price refresh and graph rebuild remain separately scoped work.

## Dependencies

None.

## Definition of Done

The account action consumes a tested application use case, the dead account-refresh implementation is wired or removed, existing behavior is preserved, and affected authoritative documentation and story Result contain the outcome and verification evidence.

## Result

Added `application.AccountRefreshService` (application layer, TARGET_ARCHITECTURE.md §8) owning the
account-refresh orchestration: `refreshAll()` calls, in the exact pre-extraction order, account
bank sync, account materials sync, account recipes sync, then character crafting/recipes sync -
verified against `Gw2App`'s original inline Sync Account handler before extraction. A failure from
any step propagates immediately and short-circuits the remaining steps, matching the original
straight-line sequence (no partial-completion handling existed before, so none was introduced).

Since `sync.AccountSync`/`sync.CharacterSync` are non-instantiable static utility classes with no
overridable seam, added `sync.AccountRefreshGateway` - a thin instantiable wrapper delegating to
those static calls - as the service's sole, replaceable infrastructure collaborator (constructor
injection, matching the `EctoLivePriceGateway`/`application.EctoSalvageService` pattern from
STORY-APP-003). The service has no JavaFX import and performs no domain calculation.

`Gw2App`'s "Sync Account" button handler now calls `accountRefreshService.refreshAll()` instead of
`AccountSync.syncAccountBank/Materials/Recipes()` and `CharacterSync.syncCharactersCraftingAndRecipes()`
directly; the surrounding background `Thread`, `Platform.runLater(...)` status updates, and
success/failure text are unchanged. `Gw2App.start(Stage)` now delegates to a new
package-private `start(Stage, AccountRefreshService)` overload so a deterministic JavaFX check can
inject a controlled fake service (mirrors `EctoView.show(Stage, Runnable, EctoSalvageService)`).
Added `status.setId("homeStatusLabel")` (previously unset) so that check can locate the status
label deterministically.

Deleted the previously unused top-level `AccountRefreshService.refreshAll()` (docs/KNOWN_PROBLEMS.md
§8's dead-call entry) - its orchestration is now the one authoritative implementation in
`application.AccountRefreshService`; no competing unused service code remains.

`InitialSetupService.firstFill()` was not touched: its own account bank/materials/recipes calls,
operation selection and order are unchanged, per the story's "do not substitute the broader
account-refresh workflow blindly" constraint and "limit setup edits to necessary account-orchestration
reuse."

Updated `docs/CURRENT_ARCHITECTURE.md` §5.4 (Sync Account flow, dead-call sentence) and §6 (Gw2App
table row, Application Layer boundary count) and `docs/KNOWN_PROBLEMS.md` §8 (dead-call entry marked
resolved).

**Tests:**
- `src/test/java/application/AccountRefreshServiceTest.java` (4 tests, fake `AccountRefreshGateway`,
  no HTTP/DB): exact call order; bank failure short-circuits everything; materials failure
  short-circuits recipes/characters after bank already ran; character-sync failure propagates after
  every account step ran. All pass.
- `src/test/java/Gw2AppSyncAccountIT.java` (TestFX `ApplicationTest`, "IT" suffix so Surefire's
  default `test` goal skips it, run explicitly with `-Dtest=Gw2AppSyncAccountIT`): clicks the real
  "Sync Account" button with a fake `AccountRefreshService` injected via the new
  `start(Stage, AccountRefreshService)` overload, asserts the service is invoked exactly once, off
  the JavaFX Application Thread, and that the status label reaches its success text. Passed against
  a real windowed session (this environment's `mvnw`/PowerShell toolchain was unavailable, so this
  was compiled with `javac` against the local `.m2` repository and run directly via the
  `junit-platform-launcher` API instead of Surefire/Failsafe - the test itself is unchanged from
  what `./mvnw test -Dtest=Gw2AppSyncAccountIT` would run).
- Regression: re-ran `application.EctoSalvageServiceTest`, `application.CraftingDiscoveryServiceTest`,
  `application.CraftingProfitServiceTest` (existing application-layer suites) the same way - all
  still pass, confirming no unrelated behavior changed.
- No focused `InitialSetupService` regression was added since that service was not modified.

**Limitations / follow-up (not in this story's scope):** `CraftingProfitView` and
`CraftingDiscoveryView` each run their own periodic auto-refresh timer that calls
`AccountSync`/`CharacterSync` directly (a separate feature from the "Sync Account" button); this
story's Constraints scope it to the Sync Account action and the dead `AccountRefreshService` only,
so that duplication was left untouched and is not otherwise tracked as a known problem. This story
does not declare other synchronization use cases or Phase 3 complete.

## Blockers

None.
