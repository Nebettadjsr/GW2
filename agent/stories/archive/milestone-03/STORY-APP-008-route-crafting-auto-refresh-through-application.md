## Story ID

STORY-APP-008

## Title

Route crafting auto-refresh through application services

## Status

DONE

## Milestone

milestone-03

## Goal

Move the crafting views' remaining automatic account-synchronization orchestration behind the application boundary while preserving their existing refresh behavior.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md section 7, supplied Phase 3 objective and high-level story to update JavaFX views/controllers to call application services only.
- docs/TARGET_ARCHITECTURE.md section 8 (Application Layer) and section 25 (Application Tests).
- agent/stories/STORY-APP-004-extract-account-refresh-use-case.md, Result: both crafting views' periodic timers still call AccountSync/CharacterSync directly; Constraints distinguish this from the Sync Account action.
- agent/stories/STORY-APP-007-extract-initial-setup-use-case.md, Constraints: preserve distinct workflows instead of blindly substituting broader use cases.

## Context

The existing account-refresh service covers the home-screen action. STORY-APP-004 explicitly leaves direct synchronization calls in CraftingProfitView and CraftingDiscoveryView auto-refresh. Their exact operation selection, timing and failure semantics must be established during implementation; they are not assumed identical to the home-screen action.

## Acceptance Criteria

1. Establish and record each view's existing automatic refresh sequence, inputs, scheduling/lifecycle behavior, reload ordering and failure handling before extraction.
2. Route automatic synchronization in both views through named application-service operations, removing their direct AccountSync/CharacterSync orchestration. Reuse AccountRefreshService where semantics match; preserve narrower or distinct sequences through appropriately scoped application operations where necessary.
3. Keep orchestration independent of JavaFX with replaceable infrastructure collaborators. Leave timers, view lifecycle, background execution and presentation updates in the presentation layer.
4. Preserve synchronization scope/order, failure propagation, refresh cadence, scope selections, filters and sorting. Do not introduce additional synchronization merely to reuse a broader service.
5. Update affected descriptions in docs/CURRENT_ARCHITECTURE.md and record targeted verification and limitations in Result; do not claim milestone completion.

## Required Tests

- Fake-adapter application tests cover each distinct established sequence, forwarded inputs and failure short-circuit behavior without live HTTP or a database.
- Deterministically trigger the existing automatic-refresh callback in each real JavaFX view using controlled service outcomes; verify delegation, reload/status behavior on success and failure, and preservation of the existing selected scope/filter/sort state. Avoid waiting for a real timer interval or requiring live API access.
- Run affected existing account-refresh and crafting refresh-preservation regressions; document unavailable prerequisites rather than claiming unexecuted checks passed.

## Constraints

- Behavior-preserving Phase 3 extraction only; no new scheduling policy, UI features or domain rules.
- Do not merge workflows with different synchronization semantics or duplicate infrastructure implementations.
- No broad cleanup, automatic full regression campaign or milestone closure.

## Dependencies

- STORY-APP-004 (DONE): existing account-refresh application boundary and gateway.

## Definition of Done

Both automatic-refresh paths delegate synchronization through application services, existing behavior is protected by targeted checks, and Result plus architecture documentation describe the migrated boundary and any limitations.

## Result

### Established pre-extraction behavior (AC 1)

Read from the two views before any change; both timers were structurally identical apart from the
synchronization sequence and two label details.

| | `CraftingProfitView` | `CraftingDiscoveryView` |
| --- | --- | --- |
| Interval | `REFRESH_SECONDS = 90` | `REFRESH_SECONDS = 120` |
| Scheduler | `static ScheduledExecutorService`, `shutdownNow()` on reopen/back, `scheduleAtFixedRate(..., 1, 1, SECONDS)` | identical |
| Sync sequence | `AccountSync.syncAccountMaterials()`, `AccountSync.syncAccountRecipes()` | `AccountSync.syncAccountBank()`, `syncAccountMaterials()`, `syncAccountRecipes()`, `CharacterSync.syncCharactersCraftingAndRecipes()` |
| On success | reset countdown, then `Platform.runLater`: status `"🔄 Auto-refreshed Bank + Materials"`, `reloadTable.run()` | same, plus `lastRefreshLabel` → `"Last refresh: just now"` before the status text |
| On failure | `ex.printStackTrace()`, reset countdown anyway ("otherwise it spams"), `Platform.runLater`: status `"⚠️ Auto-refresh failed: " + message`; **no** reload | same, plus `lastRefreshLabel` → `"Last refresh: FAILED"` |
| Failure scope | any step throwing short-circuits the rest (straight-line sequence, no partial handling) | identical |
| Countdown label | `"Auto-refresh in: " + n + "s / 90s"`, updated every tick via `Platform.runLater` | `"Auto-refresh in: " + n + "s"` |

The two sequences are genuinely different: Profit's is *not* a subset the broader service could be
substituted for without adding a bank sync and a full character sync it never performed.
Discovery's is byte-for-byte the Sync Account sequence `application.AccountRefreshService.refreshAll()`
already owned (STORY-APP-004).

### Extraction (AC 2, 3, 4)

- `application.AccountRefreshService` gained one named operation,
  `refreshMaterialsAndRecipes()` → `gateway.syncAccountMaterials()`, `gateway.syncAccountRecipes()`,
  for Profit's narrower sequence. `refreshAll()` is reused unchanged for Discovery. Adding a second
  named variant to the existing service (rather than a new service or a widened `refreshAll()`)
  follows `application.TradingPostPriceRefreshService`'s precedent of one service owning two
  distinctly scoped variants over the same gateway, and keeps one authoritative account-refresh
  orchestration. No new gateway or infrastructure implementation was added — `sync.AccountRefreshGateway`
  already exposed both methods.
- Both views now call the service instead of `sync.AccountSync`/`sync.CharacterSync`; neither view
  imports `sync.*` any more (verified by grep — no default-package class does).
- Each view's timer body was extracted verbatim into a local `Runnable autoRefresh` that the
  scheduler tick invokes when the countdown reaches zero, with only the sync calls replaced. The
  countdown, its per-second label update, the reset-on-both-outcomes behavior, the scheduler and its
  shutdown, `Platform.runLater` status/label updates and the post-refresh `reloadTable.run()` all
  stay in the presentation layer. The service has no JavaFX import and no scheduling knowledge.
- Injection: each view gained
  `show(Stage, Runnable, TradingPostPriceRefreshService, AccountRefreshService)`; the existing
  three-argument overload forwards to it with a real service, so `Gw2App` and the existing
  `*TpRefreshIT` call sites are unchanged.
- Test seam: each view publishes its refresh `Runnable` in a package-private static
  `autoRefreshTask` field (documented as such, unread by production code), so a UI check runs the
  scheduler's actual task without waiting out a 90/120-second countdown. This mirrors the
  established seam practice in this codebase (`Gw2App.start(Stage, AccountRefreshService)`,
  `repo.Db.TEST_SCHEMA_PROPERTY`).
- Nothing was merged, widened or added: Profit still never syncs the bank or characters, cadence and
  ordering are unchanged, and no new scheduling policy or UI behavior was introduced.

### Verification

All runs below are `./mvnw -o` against the real local toolchain, a real windowed TestFX session and
local PostgreSQL.

- `application.AccountRefreshServiceTest` — 7 tests (4 pre-existing + 3 new, fake
  `AccountRefreshGateway`, no HTTP/DB): `refreshMaterialsAndRecipes()` calls exactly
  materials-then-recipes (asserting the bank/character steps are *absent*), a materials failure
  short-circuits recipes, and a recipes failure propagates after materials already ran. Pass.
- `CraftingProfitViewAutoRefreshIT` (new, 2 tests) — triggers the real view's published auto-refresh
  task from a background thread: asserts exactly-once delegation to `refreshMaterialsAndRecipes()`,
  that `refreshAll()` is never called, that the call is off the JavaFX thread, that the reload runs
  afterwards (status label, pre-marked with a sentinel, returns to the reload's completion value),
  and that scope/search filter/sort survive; second test asserts the `"⚠️ Auto-refresh failed"`
  status on a failing service. Pass.
- `CraftingDiscoveryViewAutoRefreshIT` (new, 2 tests) — same shape for `refreshAll()`, additionally
  asserting `refreshMaterialsAndRecipes()` is never called and that `"Last refresh: just now"` /
  `"Last refresh: FAILED"` are set on the respective paths. Pass.
- Regressions: `CraftingProfitViewTpRefreshIT`, `CraftingDiscoveryViewTpRefreshIT`,
  `uiverify.CraftingProfitViewRefreshPreservationIT`, `Gw2AppSyncAccountIT` — 6 tests, pass.
- Full default suite: `./mvnw -o test` — **143 tests, 0 failures, 0 errors**.

### Limitations

- The real 90/120-second countdown and the per-second label tick are still not executed by any
  automated check; the tests drive the scheduler's task directly, as the story required. The
  countdown-reset lines are therefore covered by reading, not by assertion.
- The success-path status text (`"🔄 Auto-refreshed Bank + Materials"`) remains unobservable to an
  external poll because the immediately following `reloadTable.run()` overwrites it within the same
  JavaFX pulse — the same pre-existing quirk STORY-APP-006 documented for the TP-refresh button.
  Unchanged by this story.
- The gateway's own `AccountSync`/`CharacterSync` calls are unchanged and were not re-verified
  against the live GW2 API; the existing `sync.AccountSyncTest`/`sync.CharacterSyncTest` write-path
  regressions cover them and still pass.
- Milestone/Phase 3 completion is **not** claimed. `BankView`/`MaterialsView` still bypass
  `repo.*`/the application layer entirely (`STORY-APP-009`'s scope).

### Documents updated

`docs/CURRENT_ARCHITECTURE.md` §5.4 (both auto-refresh flows added to the synchronization map, plus
a STORY-APP-008 paragraph) and §6 (both view rows, boundary-count sentence: no tenth boundary was
added). No other owned document's facts changed.

## Blockers

None.
