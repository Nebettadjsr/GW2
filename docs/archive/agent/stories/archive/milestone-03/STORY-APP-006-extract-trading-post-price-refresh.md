## Story ID

STORY-APP-006

## Title

Extract the Trading Post price refresh application use case

## Status

DONE

## Milestone

milestone-03

## Goal

Introduce a named Trading Post price-refresh application service and route existing price-refresh orchestration through it, preserving operation selection and behavior.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md section 7, supplied Phase 3: named Trading Post price-refresh service exit criterion and application-service migration objective.
- docs/TARGET_ARCHITECTURE.md section 8 (Application Layer) and section 25 (Application Tests).
- docs/CURRENT_ARCHITECTURE.md section 5.4 (InitialSetupService price synchronization).
- STORY-APP-004 and STORY-APP-005 Constraints: Trading Post price refresh remains separately scoped.

## Context

The existing Phase 3 stories cover crafting calculations, Ectoplasm, account refresh, global refresh and graph rebuild. They do not cover the required Trading Post price-refresh service. CURRENT_ARCHITECTURE documents InitialSetupService invoking discovery and profit price synchronization. Exact additional callers and their behavior must be established during implementation, not assumed from this planning pass.

## Acceptance Criteria

1. A named application service owns orchestration of existing Trading Post price-refresh operations, including the documented discovery and profit variants. It has no JavaFX dependency and uses replaceable infrastructure collaborators.
2. Establish existing price-refresh call sites, inputs, operation selection, ordering and failure behavior during implementation. Route existing UI/controller price-refresh calls through the application boundary and record the migrated callers in Result; preserve their distinct scopes rather than refreshing every price indiscriminately.
3. Route InitialSetupService's documented discovery/profit price-refresh steps through the service, preserving setup's existing sequence and failure behavior. Do not substitute a broader workflow for those steps.
4. Preserve existing item relevance, quote and persistence semantics. Keep infrastructure synchronization in its adapter and calculations in the domain; do not introduce a competing pricing calculation.
5. Preserve affected UI background execution, refresh triggers, selection/state preservation and success/error presentation. Do not introduce new controls or scheduling behavior.
6. Update the affected flow descriptions in docs/CURRENT_ARCHITECTURE.md and record verification evidence and limitations in Result. Do not declare Phase 3 complete.

## Required Tests

- Fake-adapter application tests verify operation selection, forwarded scope/inputs, existing ordering where applicable, and success/failure propagation without live HTTP or a database.
- A focused setup regression verifies that substituting the application service preserves its existing price-refresh steps and surrounding operation sequence.
- For migrated UI callers, use controlled service outcomes with the existing JavaFX verification capability to check delegation, failure presentation and preservation of relevant view state. Record unavailable prerequisites or limitations.
- Run affected existing synchronization/persistence regressions if production adapter behavior is touched; no automatic broad test expansion.

## Constraints

- Behavior-preserving Phase 3 extraction only; no new domain rules, synchronization policy, threading redesign or persistence redesign.
- Keep tradeable-item synchronization distinct from price refresh. Global refresh and graph rebuild remain owned by STORY-APP-005; account refresh by STORY-APP-004; Ectoplasm by STORY-APP-003.
- Limit changes to the price-refresh boundary and necessary callers/test seams. Do not broaden into unrelated view migrations or cleanup.

## Dependencies

None.

## Definition of Done

The named price-refresh service is used by the existing price-refresh callers, behavior is preserved with targeted verification evidence, and affected architecture documentation and Result describe the resulting boundary and any limitations.

## Result

Added `application.TradingPostPriceRefreshService` (`TARGET_ARCHITECTURE.md` §8) owning
orchestration of Trading Post price refresh, exposing the two pre-existing, distinctly scoped
variants as `refreshForDiscovery()`/`refreshForProfit()`. Since `sync.TpSync` is a
non-instantiable static utility class with no seam of its own, added `sync.TradingPostPriceRefreshGateway`
as the service's sole collaborator - a thin instantiable wrapper delegating to
`TpSync.syncTpPricesForDiscovery()`/`syncTpPricesForProfit()`, mirroring `STORY-APP-004`/
`STORY-APP-005`'s `AccountRefreshGateway`/`GlobalDataRefreshGateway` precedent. Neither class has
a JavaFX import or performs any calculation; item relevance selection
(`sync.tp.relevance.DiscoveryItemCollector`/`CraftingProfitItemCollector`), quote fetching and
`tp_prices` upsert persistence are entirely unchanged - this story only replaces the call sites.

**Established call sites (by reading the pre-extraction source before changing it):** exactly
three - `CraftingDiscoveryView`'s "Refresh Trade Post Prices" button (`TpSync.syncTpPricesForDiscovery()`),
`CraftingProfitView`'s "Refresh Trade Post Prices" button (`TpSync.syncTpPricesForProfit()`), and
`InitialSetupService.firstFill()` (both, discovery then profit, positioned after
`TpSync.syncTpTradeableItems()` and before `IconSync`). No other caller existed. Tradeable-item
synchronization (`TpSync.syncTpTradeableItems()`) is a separate operation already owned by
`STORY-APP-005`'s `GlobalDataRefreshService`/left untouched in `InitialSetupService`; this story
does not route it through the new service, matching the constraint that it stay distinct from
price refresh.

**Migrated callers:**
- `CraftingDiscoveryView`/`CraftingProfitView`: each view's "Refresh Trade Post Prices" button
  handler now calls `tradingPostPriceRefreshService.refreshForDiscovery()`/`refreshForProfit()`
  instead of `TpSync` directly. Added a `show(Stage, Runnable, TradingPostPriceRefreshService)`
  overload to each view (mirroring `EctoView`'s `STORY-APP-003` precedent); the existing two-arg
  `show(Stage, Runnable)` now forwards to it with a real service instance, so `Gw2App`'s existing
  two-arg call sites are unchanged. Background `Thread`/`Platform.runLater` execution, the
  existing success (`"✅ TP refreshed."` then `reloadTable.run()`) and failure
  (`"❌ TP refresh failed: " + ex.getMessage()"`) status text, and the "Refresh" button's own
  selection/scope/sort/search-preserving reload are all unchanged - only the `TpSync` call itself
  was replaced. Added `statusLabel.setId("craftingDiscoveryStatusLabel")` to `CraftingDiscoveryView`
  (matching `CraftingProfitView`'s pre-existing `#craftingProfitStatusLabel`) purely as a test
  lookup seam; no other UI change.
- `InitialSetupService.firstFill()`: replaced its two inline `TpSync.syncTpPricesForDiscovery()`/
  `syncTpPricesForProfit()` calls with a new package-private `syncTpPrices(TradingPostPriceRefreshService)`
  method called at the exact same point in the sequence, preserving discovery-then-profit order
  and propagate-and-stop failure behavior (unchanged - no partial-completion handling existed
  before). `firstFill()`'s public no-arg signature and every other step
  (account/global-recipe/tradeable-item/icon sync) are untouched.

**Preserved semantics:** item relevance (`DiscoveryItemCollector`/`CraftingProfitItemCollector`),
quote fetching/batching, and `tp_prices` upsert persistence remain entirely inside `TpSync` -
the application/gateway layers add no calculation and do not touch the SQL or fetch logic.

**Tests:**
- `application.TradingPostPriceRefreshServiceTest` (4 tests, fake `TradingPostPriceRefreshGateway`,
  no HTTP/DB): `refreshForDiscovery()`/`refreshForProfit()` each delegate to their own gateway
  call, and each variant's failure propagates unchanged.
- `InitialSetupServiceTest` (3 tests, fake gateway via the same fake-service pattern, no HTTP/DB):
  `InitialSetupService.syncTpPrices(...)` calls discovery then profit in that order; a discovery
  failure short-circuits profit; a profit failure propagates after discovery already ran. Only
  this package-private seam is exercised - `firstFill()`'s surrounding account/global-recipe/
  tradeable-item/icon steps are unchanged static calls with no seam of their own and are not
  mockable without a live GW2 API/PostgreSQL connection, so they are not covered here (see
  Limitations below).
- `CraftingProfitViewTpRefreshIT`/`CraftingDiscoveryViewTpRefreshIT` (new, default package like
  `EctoSalvageViewIT`, TestFX `ApplicationTest`, "IT" suffix so Surefire's default `test` goal
  skips them): inject a fake `TradingPostPriceRefreshService` via the new three-arg `show(...)`
  overload, seed a minimal disposable Postgres schema (`uiverify.CraftingUiTestFixtures`), click
  the real "Refresh Trade Post Prices" button, and assert exactly-one delegation to the
  view-specific variant, that the search filter is preserved across the refresh, and (failure
  case) that the real `"❌ TP refresh failed: ..."` status text is shown. Discovered during
  verification that the pre-existing success handler's `"✅ TP refreshed."` text is unconditionally
  overwritten within the same JavaFX pulse by the subsequent `reloadTable.run()`'s own status text
  (`CraftingProfitView` immediately sets `"Loading from DB..."`; `CraftingDiscoveryView` sets
  `"Loading missing discoverable recipes..."`) - a pre-existing UI quirk, unchanged and
  unintroduced by this story, that makes the transient success text unobservable by an external
  poll. The success tests instead assert the fake service's call count directly
  (`CraftingProfitViewTpRefreshIT`) or the subsequent reload's own stable completion text
  (`CraftingDiscoveryViewTpRefreshIT`, `"✅ Loaded N missing discoverable recipes."`), both of
  which still prove the refresh-then-reload sequence ran end to end through the new service.
- Ran against a real windowed TestFX session and the developer's local Postgres (`./mvnw test
  -Dtest=...`), not merely compiled: all 4 new IT tests pass, all 7 new unit tests pass, the full
  default (non-IT) suite (36 test classes) passes with 0 failures/errors, and the directly related
  existing IT regressions (`EctoSalvageViewIT`, `Gw2AppSyncAccountIT`, `Gw2AppSyncGlobalDataIT`,
  `uiverify.CraftingProfitViewRefreshPreservationIT`, `uiverify.CraftingDiscoveryViewRefreshPreservationIT`,
  `uiverify.CraftingProfitViewErrorStatusIT`) all still pass unchanged.

Updated `docs/CURRENT_ARCHITECTURE.md` §5.4 (new Trading Post price-refresh flow diagram entries
and `STORY-APP-006` paragraph), §6 (`CraftingProfitView`/`CraftingDiscoveryView` table rows,
"Eight named Application Layer boundaries"), and §9 item 4 (narrowed to describe
`InitialSetupService`'s two price-refresh steps now routing through the service while its other
steps remain direct).

**Limitations / follow-up (not in this story's scope):** `InitialSetupService.firstFill()`'s
account/global-recipe/tradeable-item/icon steps remain direct static calls with no test seam of
their own (unchanged, out of scope per the constraint not to substitute a broader workflow) - no
regression exercises the full `firstFill()` sequence end to end; only the price-refresh seam this
story introduced is unit-tested. `Gw2App`'s "First-time DB Setup" button still calls
`InitialSetupService`/`sync.*` directly (unchanged, `STORY-APP-007`'s scope). The pre-existing
transient-success-status UI quirk noted above was observed, not fixed (behavior-preservation
constraint). This story does not declare Phase 3 or all synchronization extraction complete.

## Blockers

None.
