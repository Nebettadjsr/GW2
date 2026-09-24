## Story ID

STORY-APP-005

## Title

Extract global data refresh and crafting graph rebuild application services

## Status

DONE

## Milestone

milestone-03

## Goal

Move the global synchronization action's orchestration into a named application service, with a named crafting graph rebuild use case, and make Gw2App delegate to that boundary.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md section 7, supplied Phase 3 objective and exit criteria for global data refresh, crafting graph rebuild and Gw2App handler migration.
- docs/TARGET_ARCHITECTURE.md section 8 (Application Layer) and section 25 (Application Tests).
- docs/CURRENT_ARCHITECTURE.md sections 5.4 (synchronization sequence), 7 (execution and reporting), and 9 item 4 (UI orchestration).

## Context

The documented Sync ALL tradeable Items action invokes tradeable-item synchronization, global recipe synchronization and CraftingGraphCache.rebuild directly. STORY-APP-004 covers account refresh and explicitly leaves global refresh and graph rebuild separately scoped. This story addresses those two closely related use cases without changing their existing behavior.

## Acceptance Criteria

1. A named global-data-refresh application service coordinates the existing action's tradeable-item synchronization, global recipe synchronization and graph rebuild in their existing order. Confirm exact operation selection and failure behavior during implementation and preserve them.
2. A named crafting-graph-rebuild application service owns invocation of the existing repository-backed cache rebuild. The global refresh service delegates its rebuild step to that use case; no competing graph-building algorithm is introduced.
3. Gw2App's global synchronization handler calls the application service instead of sync.*, repo.* or craft.* directly. Preserve background execution, JavaFX-thread status updates and success/failure reporting.
4. Both application services are free of JavaFX dependencies and expose replaceable infrastructure collaborators for deterministic application tests. Domain logic and persistence remain at their existing boundaries.
5. Preserve cache format, location and existing load-time auto-rebuild behavior. No new graph-rebuild button is required. Preserve first-time setup's distinct operation selection and ordering if shared orchestration is touched; do not replace setup with the global refresh workflow blindly.
6. Update affected sections of docs/CURRENT_ARCHITECTURE.md to describe the resulting boundary. Record verification evidence and limitations in Result without declaring all synchronization extraction or Phase 3 complete.

## Required Tests

- Fake-adapter application tests prove global refresh operation selection, ordering, one graph-rebuild delegation on success, and existing failure propagation/short-circuit behavior without live HTTP or a database.
- A focused graph-service test proves delegation to the existing cache rebuild and propagation of its failure without introducing a second calculation.
- A targeted deterministic JavaFX check with controlled service outcomes verifies global-action delegation and success/failure presentation while preserving background execution and UI-thread updates. Reuse the existing verification capability and record unavailable prerequisites or limitations.
- If setup orchestration or production cache behavior changes, run focused affected regressions; protect setup's distinct sequence with a fake-adapter check when touched.

## Constraints

- Behavior-preserving Phase 3 extraction only; no API, persistence, domain-rule, scheduling or threading redesign.
- Account refresh remains owned by STORY-APP-004. Trading Post price refresh remains separately scoped; tradeable-item synchronization in this global action does not by itself satisfy the price-refresh exit criterion.
- Do not broaden this work into unrelated view migrations or cleanup.

## Dependencies

None.

## Definition of Done

The global action delegates through tested application services for global refresh and graph rebuild, existing behavior is preserved, and the affected architecture documentation and story Result record the outcome and verification evidence.

## Result

Added `application.GlobalDataRefreshService` and `application.CraftingGraphRebuildService`
(application layer, `TARGET_ARCHITECTURE.md` §8) owning the "Sync ALL tradeable Items, Recipes
and (re)build Crafting Graph" orchestration. `GlobalDataRefreshService.refreshAll()` calls, in
the exact pre-extraction order, tradeable-item sync, global recipe sync, then delegates the
graph rebuild - verified against `Gw2App`'s original inline `btnSyncTpItems` handler before
extraction. A failure from any step propagates immediately and short-circuits the remaining
steps, matching the original straight-line sequence (no partial-completion handling existed
before, so none was introduced).

Since `sync.TpSync`/`sync.RecipeSync` are non-instantiable static utility classes with no
overridable seam, added `sync.GlobalDataRefreshGateway` - a thin instantiable wrapper delegating
to those static calls - as `GlobalDataRefreshService`'s sole `sync.*` collaborator, mirroring
`STORY-APP-004`'s `sync.AccountRefreshGateway` precedent. `CraftingGraphRebuildService` owns
invocation of the existing `repo.CraftingGraphCache.rebuild()`; `CraftingGraphCache` is already
an instantiable, non-final class with an overridable `rebuild()` method, so it needed no
additional gateway wrapper of its own - it is `CraftingGraphRebuildService`'s collaborator
directly, substituted with a fake subclass in tests. No competing graph-building algorithm was
introduced anywhere; `CraftingGraphRebuildService.rebuild()` calls the existing
`CraftingGraphCache.rebuild()` and nothing else. Neither service has a JavaFX import or performs
any domain calculation.

`Gw2App`'s "Sync ALL tradeable Items..." button handler now calls
`globalDataRefreshService.refreshAll()` instead of `TpSync.syncTpTradeableItems()`,
`RecipeSync.syncAllRecipesGlobalSafe()`, and constructing `RecipeRepository`/`CraftingGraphCache`
directly; the surrounding background `Thread`, `Platform.runLater(...)` status updates, and
success/failure text are unchanged. `Gw2App.start(Stage)` now delegates through a new
package-private `start(Stage, AccountRefreshService, GlobalDataRefreshService)` overload so a
deterministic JavaFX check can inject a controlled fake service for either button independently;
the existing two-arg `start(Stage, AccountRefreshService)` overload (added by `STORY-APP-004`)
is preserved unchanged in signature and now forwards to the three-arg overload with a real
`GlobalDataRefreshService`, so `Gw2AppSyncAccountIT` needed no changes.

Cache format, location (`crafting_graph_cache.json`, relative to the process working directory),
and `CraftingGraphCache.load()`'s existing `STORY-INFRA-002` auto-rebuild-when-missing behavior
are all unchanged - `CraftingGraphRebuildService` only wraps the existing `rebuild()` call, it
does not touch `load()`. No new graph-rebuild button was added.

`InitialSetupService.firstFill()` was not touched: it has its own distinct account/global-recipe/
TP-price/icon operation selection and order (and never calls the graph rebuild at all), unchanged
by this story, per the constraint not to substitute the broader global-refresh workflow into
setup.

Updated `docs/CURRENT_ARCHITECTURE.md` §5.4 (Sync ALL tradeable Items flow, new
`STORY-APP-005` paragraph), §6 (`Gw2App` table row, "Six named Application Layer boundaries"),
and §9 item 4 (narrowed to reflect only "First-time DB Setup" still calling `sync.*`/
`InitialSetupService` directly).

**Tests:**
- `src/test/java/application/GlobalDataRefreshServiceTest.java` (4 tests, fake
  `GlobalDataRefreshGateway` + a fake `CraftingGraphRebuildService` backed by a recording
  `CraftingGraphCache` subclass, no HTTP/DB): exact call order with exactly one graph-rebuild
  delegation on success; tradeable-item failure short-circuits recipes and the rebuild; recipe
  failure short-circuits the rebuild after tradeable items already ran; graph-rebuild failure
  propagates after both sync steps ran. All pass.
- `src/test/java/application/CraftingGraphRebuildServiceTest.java` (2 tests, fake
  `CraftingGraphCache` subclass, no DB/filesystem): delegation to the existing cache rebuild
  happens exactly once; its failure propagates unchanged. All pass.
- `src/test/java/Gw2AppSyncGlobalDataIT.java` (TestFX `ApplicationTest`, "IT" suffix so
  Surefire's default `test` goal skips it, run explicitly with
  `-Dtest=Gw2AppSyncGlobalDataIT`): clicks the real "Sync ALL tradeable Items, Recipes and
  (re)build Crafting Graph" button with a fake `GlobalDataRefreshService` (and an inert fake
  `AccountRefreshService`) injected via the new three-arg `start(...)` overload, asserts the
  service is invoked exactly once, off the JavaFX Application Thread, and that the status label
  reaches its success text. Passed against a real windowed session - this environment's
  `mvnw`/PowerShell toolchain was unavailable (the wrapper shells out to `powershell`, which is
  blocked in this sandbox), so this was compiled with `javac` against the local `.m2` repository
  and run directly via the `junit-platform-launcher` API instead of Surefire/Failsafe, matching
  `STORY-APP-004`'s precedent - the test itself is unchanged from what
  `./mvnw test -Dtest=Gw2AppSyncGlobalDataIT` would run.
- Regression: re-ran `application.AccountRefreshServiceTest`, `application.EctoSalvageServiceTest`,
  `application.CraftingDiscoveryServiceTest`, `application.CraftingProfitServiceTest`, and
  `Gw2AppSyncAccountIT` (existing suites) the same way - all still pass, confirming no unrelated
  behavior changed. The full main source tree (91 files) and full test source tree (48 files)
  both compile cleanly with no errors via the same `javac` toolchain.
- No focused `InitialSetupService` regression was added since that service was not modified.
- No live GW2 API/PostgreSQL Layer 2/4 regression was run in this environment (none was touched
  by this story - `TpSync`/`RecipeSync`/`CraftingGraphCache`'s own implementations are
  unchanged, only their call sites).

**Limitations / follow-up (not in this story's scope):** `Gw2App`'s "First-time DB Setup" button
still calls `InitialSetupService`/`sync.*` directly, matching `STORY-APP-004`'s equivalent
carve-out - not addressed here, per this story's own constraint to leave setup's distinct
sequence alone unless shared orchestration was touched (it was not: `GlobalDataRefreshGateway`/
`CraftingGraphRebuildService` are new, `InitialSetupService` calls the original static `sync.*`
methods directly as before). Trading Post price refresh (`TpSync.syncTpPricesForDiscovery/
ForProfit`) remains separately scoped to `STORY-APP-006`, unaffected by this story. This story
does not declare all synchronization extraction or Phase 3 complete.

## Blockers

None.
