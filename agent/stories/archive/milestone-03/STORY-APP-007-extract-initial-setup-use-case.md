## Story ID

STORY-APP-007

## Title

Extract the first-time setup application use case

## Status

DONE

## Milestone

milestone-03

## Goal

Move first-time setup orchestration behind a named application service so Gw2App's setup button delegates through the application boundary while preserving its existing behavior.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md section 7, supplied Phase 3: Gw2App button handlers must call application services instead of sync/repo/craft directly; synchronization extraction explicitly includes InitialSetupService.
- docs/TARGET_ARCHITECTURE.md section 8 (Application Layer) and section 25 (Application Tests).
- docs/CURRENT_ARCHITECTURE.md sections 5.4 and 9 item 4: existing setup sequence and remaining Gw2App setup bypass.
- STORY-APP-005 Result and Constraints: first-time setup remains outside the global-refresh extraction.
- STORY-APP-006 Acceptance Criteria 3: setup's price-refresh steps delegate to the price-refresh service while retaining their sequence.

## Context

The account and global refresh buttons have application services, but first-time setup remains a separate flow. Its documented sequence includes account synchronization, global recipes, tradeable items, discovery/profit prices and icons. It differs from the account/global refresh use cases, so replacing it with those broader workflows without checking their behavior could add or reorder operations. STORY-APP-006 handles its price-refresh boundary; this story handles the enclosing setup use case and button delegation.

## Acceptance Criteria

1. Establish the existing setup handler and InitialSetupService operation sequence, inputs, configuration use, failure behavior and any surrounding synchronization calls during implementation; record the resulting migrated boundary in Result.
2. A named service in the application layer owns the existing first-time setup orchestration with replaceable infrastructure collaborators and no JavaFX dependency. Relocate or retire the former orchestration so there is one authoritative setup workflow.
3. Gw2App's first-time setup handler delegates setup to this application service and no longer calls sync.*, repo.* or craft.* directly for that action. Keep background execution and success/error status rendering in the presentation layer.
4. Preserve the existing setup operation scope, order, configuration and failure behavior, including icon steps. Reuse STORY-APP-006's price-refresh application service. Reuse other services only where their semantics match the existing steps; do not introduce character refresh, graph rebuild or broader synchronization merely by composing existing use cases.
5. Preserve synchronization, persistence and domain behavior; keep infrastructure operations in adapters and avoid duplicating their implementation in the application layer.
6. Update the affected setup descriptions in docs/CURRENT_ARCHITECTURE.md and record targeted verification evidence and limitations in Result. Do not declare Phase 3 complete.

## Required Tests

- Fake-adapter application tests verify the established operation order, forwarded inputs/configuration, exactly-once delegation and existing failure/short-circuit behavior without live HTTP or a database.
- A focused real-window JavaFX check with controlled service outcomes verifies setup-button delegation off the FX thread and success/error status rendering using the existing verification capability.
- Reuse or adapt STORY-APP-006's setup regression to verify the price-refresh steps remain in their original surrounding sequence. Run affected existing adapter regressions if adapter behavior changes; record unavailable prerequisites honestly.

## Constraints

- Behavior-preserving Phase 3 extraction only: no new setup policy, controls, scheduling, persistence design or domain rules.
- Scope is the first-time setup workflow and its button, with only necessary collaborators and test seams. Other view refresh migrations remain separate work.
- Do not merge distinct account/global/setup workflows solely to remove superficially similar calls.
- No broad cleanup, automatic full regression campaign or milestone completion assessment.

## Dependencies

- STORY-APP-006: setup's discovery/profit price-refresh application boundary must be available before this story is implemented.

## Definition of Done

The setup button uses the named application service, one authoritative setup orchestration preserves the established behavior, and targeted verification plus updated architecture documentation describe the resulting boundary and limitations.

## Result

**Established sequence (by reading the pre-extraction source before changing it):** the top-level
`InitialSetupService.firstFill()` called, in order: `AccountSync.syncAccountBank/Materials/Recipes()`,
`RecipeSync.syncAllRecipesGlobalSafe()`, `TpSync.syncTpTradeableItems()`, then (already routed
through `application.TradingPostPriceRefreshService` by `STORY-APP-006`)
`refreshForDiscovery()`/`refreshForProfit()`, then `IconSync.syncItemIconUrls()` and
`syncItemIconsToDisk(Path.of(repo.AppConfig.ICON_CACHE_DIR))`. No partial-completion handling
existed - any step's exception propagated up through `Gw2App`'s button handler unchanged. The
icon-cache-directory path was read at `firstFill()` call time (lazily), not at any earlier point.
`Gw2App`'s "First-time DB Setup" handler showed a confirmation `Alert` first, then ran
`InitialSetupService.firstFill()` on a daemon `Thread`, reporting `"✅ Initial fill finished."` or
`"❌ Initial fill failed: " + ex.getMessage()"` back via `Platform.runLater`.

Added `application.InitialSetupService` (`TARGET_ARCHITECTURE.md` §8) owning this orchestration.
`firstFill()` reproduces the exact established order and short-circuit-on-first-failure behavior.
It coordinates four collaborators rather than one broader use case, because setup's step
selection and order differ from both `application.AccountRefreshService`'s and
`application.GlobalDataRefreshService`'s workflows (per Constraint: do not introduce character
refresh, graph rebuild or broader synchronization by composing those use cases):
- `sync.AccountRefreshGateway` - its `syncAccountBank/Materials/Recipes()` methods only (not
  `syncCharacterCraftingAndRecipes()`, which `AccountRefreshService` adds and setup never called).
- `sync.GlobalDataRefreshGateway` - its `syncAllRecipesGlobalSafe()`/`syncTpTradeableItems()`
  methods only, called in setup's own recipes-then-items order (the reverse of
  `GlobalDataRefreshService.refreshAll()`'s items-then-recipes order), and with no graph-rebuild
  call (which `GlobalDataRefreshService` always adds and setup never called).
- `application.TradingPostPriceRefreshService` - reused unchanged (`STORY-APP-006`), called
  directly as a service-to-service collaborator instead of through the old
  `syncTpPrices(TradingPostPriceRefreshService)` test seam, which is no longer needed now that
  the whole class is constructor-injectable.
- `sync.IconSyncGateway` (new) - a thin instantiable wrapper over the non-instantiable `IconSync`
  statics' `syncItemIconUrls()`/`syncItemIconsToDisk(Path)`, mirroring
  `AccountRefreshGateway`/`GlobalDataRefreshGateway`/`TradingPostPriceRefreshGateway`'s precedent.

None of the four collaborators or `InitialSetupService` itself has a JavaFX dependency; all
infrastructure work stays in `sync.*`/`IconSync`/`AppConfig`, unchanged. `firstFill()` still reads
`repo.AppConfig.ICON_CACHE_DIR` at call time (inline, not cached in a field), preserving the
original's lazy-configuration-read timing exactly - moving it to constructor-injected state would
have forced `AppConfig`'s environment-variable checks to run at every `Gw2App` startup instead of
only when Setup is actually run, a behavior change this story does not make.

**Retired the former orchestration:** deleted the top-level `InitialSetupService.java` (default
package) and its `InitialSetupServiceTest.java` (which only covered the `STORY-APP-006`
price-refresh seam) - one authoritative setup workflow (`application.InitialSetupService`) now
exists.

**Gw2App:** the "First-time DB Setup" button handler now calls `initialSetupService.firstFill()`
instead of the static `InitialSetupService.firstFill()`; it no longer references `sync.*`,
`repo.*` or `craft.*` for this action (nor did it before - the old class did that on its behalf,
now `application.InitialSetupService` does). The confirmation `Alert`, background `Thread`,
`Platform.runLater` success/error status rendering (`"✅ Initial fill finished."` /
`"❌ Initial fill failed: " + ex.getMessage()"`) and disable/enable of the button are all
unchanged. Added a new package-private
`start(Stage, AccountRefreshService, GlobalDataRefreshService, InitialSetupService)` overload
(mirroring `STORY-APP-004`/`STORY-APP-005`'s precedent) for deterministic JavaFX injection; the
existing one/two/three-arg overloads now forward to it with a real `new InitialSetupService()`,
so every existing call site (`Gw2AppSyncAccountIT`, `Gw2AppSyncGlobalDataIT`) is unchanged.

**Tests:**
- `application.InitialSetupServiceTest` (5 tests, fake `AccountRefreshGateway`/
  `GlobalDataRefreshGateway`/`TradingPostPriceRefreshGateway`/`IconSyncGateway`, no live HTTP or
  database - the icon step's `AppConfig.ICON_CACHE_DIR` read is a local environment/.env lookup,
  unchanged from the pre-extraction code, not a live call): the full established call order across
  all nine steps plus the icon step's forwarded path; a first-step (bank) failure short-circuiting
  every later step; a global-recipes failure short-circuiting tradeable-items/prices/icons while
  the account steps already ran; a discovery-price failure short-circuiting profit-price/icons
  while the account/global steps already ran; and an icon-URL failure propagating after every
  earlier step ran while short-circuiting the icon-disk step.
- `Gw2AppFirstSetupIT` (new, default package like `Gw2AppSyncAccountIT`, TestFX `ApplicationTest`,
  "IT" suffix so Surefire's default `test` goal skips it): injects a fake `InitialSetupService`
  via the new four-arg `start(...)` overload, clicks the real "First-time DB Setup" button,
  dismisses the real confirmation `Alert` via its "OK" button (TestFX interacts with the `Alert`'s
  nested event loop normally), and asserts exactly-one delegation to the service off the JavaFX
  Application Thread plus the real `"✅ ..."` success status text.
- Ran against a real windowed TestFX session (`./mvnw test -Dtest=...`), not merely compiled: the
  new `Gw2AppFirstSetupIT` passes; the related existing IT regressions
  (`Gw2AppSyncAccountIT`, `Gw2AppSyncGlobalDataIT`, `EctoSalvageViewIT`,
  `CraftingProfitViewTpRefreshIT`, `CraftingDiscoveryViewTpRefreshIT`) all still pass unchanged;
  the full default (non-IT) suite (34 test classes, 126 tests, including the new
  `application.InitialSetupServiceTest`) passes with 0 failures/errors.

Updated `docs/CURRENT_ARCHITECTURE.md` §5.4 (new setup flow diagram entries and a
`STORY-APP-007` paragraph), §6 (`Gw2App` table row, "Nine named Application Layer boundaries"),
and §9 item 4 (marked Resolved - all three `Gw2App` sync buttons now delegate to named
application services). Updated `docs/KNOWN_PROBLEMS.md` §4.4 (overall status Resolved - both its
Ecto and `Gw2App` observed facts are now closed), §7.3 (class location correction) and its closing
"Still-open work" summary.

**Limitations / follow-up (not in this story's scope):** Phase 3 is not declared complete - this
story only closes `Gw2App`'s button-handler gap; `BankView`/`MaterialsView` still bypass `repo.*`
directly (`docs/KNOWN_PROBLEMS.md` §2.2, unrelated, separately scoped). `STORY-PERF-001`'s
blocking performance gate (`TARGET_ARCHITECTURE.md` §33) is untouched by this story and remains
open. No PROJECT HEALTH REVIEW was run (out of this story's scope per its Constraints).

## Blockers

None.
