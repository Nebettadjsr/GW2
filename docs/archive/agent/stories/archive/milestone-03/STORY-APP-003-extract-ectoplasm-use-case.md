## Story ID

STORY-APP-003

## Title

Route Ectoplasm Salvage through an application service

## Status

DONE

## Milestone

milestone-03

## Goal

Introduce one Ectoplasm Salvage application use case that coordinates price acquisition and the existing domain calculation, and migrate EctoView to consume its results.

## Authoritative Source Documents / Sections

- Supplied docs/ROADMAP.md section 7, Phase 3 objective, Ectoplasm convergence exit criterion and application-service migration stories.
- docs/TARGET_ARCHITECTURE.md section 8 (Application Layer) and section 25 (Application Tests).
- docs/CURRENT_ARCHITECTURE.md section 5.3 (sole Ectoplasm Salvage flow).
- docs/KNOWN_PROBLEMS.md sections 3.6 (resolved duplication and implemented fee behavior) and 4.4 (remaining application-boundary gap).

## Context

The documented historical duplicate Main.java has already been deleted. EctoView still acquires prices directly and calls the plain EctoSalvageCalculator without an application boundary. The remaining work is orchestration extraction, preserving the already-established calculation.

## Acceptance Criteria

1. A named application service coordinates price acquisition and invokes the single Ectoplasm domain calculator, returning results suitable for presentation without JavaFX dependencies.
2. EctoView calls that service for the use case instead of directly acquiring GW2 prices or invoking the domain calculator. Grid rendering and presentation formatting remain in the view.
3. Preserve the existing calculation across all four Ecto-buy/Dust-sell combinations: deduct the 15% Trading Post fee exactly once from recovered Dust sale proceeds, leave Ecto acquisition cost and expected-yield assumptions unchanged, and preserve profit and cost-per-1000-Luck results.
4. Reuse the existing calculator as the single domain calculation, placing it where the application layer can access it without depending on presentation classes. Do not restore Main.java or introduce a competing formula.
5. Preserve raw and net-of-fee price presentation, fee-inclusive wording, and existing refresh and failure behavior. Infrastructure price acquisition is replaceable with a fake for application tests.
6. Update the affected flow in docs/CURRENT_ARCHITECTURE.md and the remaining Ecto-boundary status in docs/KNOWN_PROBLEMS.md section 4.4, without marking unrelated synchronization work complete. Record verification evidence and limitations in Result.

## Required Tests

- Application orchestration tests using fake price acquisition: verify quote handoff, all four combinations, returned calculation results and failure propagation without live HTTP or a database.
- Run the existing EctoSalvageCalculator regression tests to protect fee and yield behavior.
- Run the existing Ecto fee-notice real-view regression and add a targeted deterministic view check using controlled service results to verify displayed profit/Luck values and refresh delegation. Record any unavailable prerequisites or verification limitations.

## Constraints

- Behavior-preserving Phase 3 extraction only; no new fee, yield or pricing rules.
- Keep calculation in the domain and infrastructure coordination in the application service.
- Limit changes to the Ecto use case and necessary seams; no unrelated synchronization or architecture implementation.

## Dependencies

None.

## Definition of Done

EctoView consumes the named application use case, one domain calculation remains authoritative, acceptance criteria have verification evidence, and affected architecture/problem documentation matches the resulting boundary.

## Result

Introduced `application.EctoSalvageService` (existing `application` package, `TARGET_ARCHITECTURE.md`
§8) as the single named application use case for the Ectoplasm Salvage flow, and migrated `EctoView`
to consume it instead of acquiring prices or invoking the domain calculator directly.

**Structural changes:**

- Moved `EctoSalvageCalculator` (unchanged calculation) out of the default package into a new `ecto`
  domain package (`ecto.EctoSalvageCalculator`), since a named package cannot import a default-package
  class — this is the seam acceptance criterion 4 required. `EctoSalvageCalculatorTest` moved with it
  (`src/test/java/ecto/`), unchanged apart from its package declaration.
- Added `api.tp.EctoLivePriceGateway`: the exact same direct `HttpClient` call to
  `/v2/commerce/prices` that `EctoView.fetchTpQuotes(...)` used to make, relocated unchanged and
  returning `craft.PriceQuote` (the existing independent TP-quote domain type, reused instead of
  introducing a duplicate) rather than a view-local nested class. This is the infrastructure seam:
  overridable/fakeable by application tests, distinct from the DB-backed
  `repo.tp.TpPriceRepository` the Crafting flows use — Ecto's live, unsynchronized lookup semantics
  are unchanged.
- Added `application.EctoSalvageService`: `calculate()` fetches Ecto/Dust quotes via the gateway,
  applies the same Instant-Buy/Listing-Buy/Instant-Sell/Listing-Sell quote-side mapping `EctoView`
  used to do inline, and invokes `EctoSalvageCalculator.evaluate(...)` for all four combinations,
  returning an `EctoScenarios` record. Returns `EctoScenarios.UNAVAILABLE` (all four fields `null`)
  when either quote is missing — mirroring the pre-extraction "ecto == null || dust == null" branch
  exactly — rather than throwing, so `EctoView` keeps its distinct "missing data" vs. "fetch failed"
  status messages unchanged.
- `EctoView` now calls `new EctoSalvageService().calculate()` (via a new `show(Stage, Runnable,
  EctoSalvageService)` overload; the original two-arg `show(Stage, Runnable)` delegates to it, so
  `Gw2App`'s call site is unchanged) instead of its own `fetchTpQuotes`/`TpQuote`/quote-mapping
  helpers, all of which were deleted. `fillProfitGrid`/`fillLuckGrid` and the price labels now read
  straight from the returned `ScenarioResult` records' own `ectoAcquisitionCost`/
  `dustGrossUnitPrice`/`dustNetUnitPrice`/`profitPerEcto`/`costPer1000Luck` fields — no calculation
  logic remains in the view. Icon fetching (`fetchItemIcons`) is unchanged and stays in the view: it
  is presentation support, not part of the price/calculation use case acceptance criterion 2 scopes
  to the service.
- Added `id`s (`ectoStatusLabel`/`ectoProfitGrid`/`ectoLuckGrid`) to three existing view nodes —
  the only view-side test seam added — purely for the new deterministic test below; no visual or
  behavioral change.

**Acceptance criteria:**

1. Done — `application.EctoSalvageService.calculate()` coordinates `api.tp.EctoLivePriceGateway`
   price acquisition and `ecto.EctoSalvageCalculator.evaluate(...)`, returning a plain
   `EctoScenarios` record (no JavaFX dependency, verified by import inspection).
2. Done — `EctoView` calls `ectoSalvageService.calculate()` for the use case; grid rendering
   (`fillProfitGrid`/`fillLuckGrid`/`setCell`) and price-label formatting (`CoinUtils`) remain in
   the view, now reading pre-computed fields instead of recomputing them.
3. Done — the four Ecto-buy/Dust-sell combinations, the single 15% fee deduction on Dust proceeds,
   and the unchanged Ecto-cost/yield assumptions are unchanged: `EctoSalvageCalculator`'s logic was
   moved verbatim (no line changed beyond the `package ecto;` declaration), and
   `EctoSalvageCalculatorTest`'s 7 pre-existing regression assertions still pass unmodified.
4. Done — `ecto.EctoSalvageCalculator` is the sole calculator, reachable from
   `application.EctoSalvageService`; `Main.java` was not restored and no competing formula was
   introduced (confirmed: repository-wide search for `EctoSalvageCalculator`/duplicate fee math
   finds only the one class and its test).
5. Done — raw vs. net-of-fee price presentation, the fee-inclusive notice wording, and the existing
   refresh-on-open/failure-status behavior (`"❌ Failed to load prices (missing data)"` for a missing
   quote vs. `"❌ Failed to load prices: " + ex.getMessage()` for a thrown exception) are byte-for-byte
   preserved — confirmed by the unmodified `uiverify.EctoFeeNoticeSmokeIT` still passing and by
   direct code reading of the preserved `Platform.runLater`/try-catch structure.
   `api.tp.EctoLivePriceGateway` is a plain, non-final instance class with an overridable
   `fetchQuotes(...)` method — replaced with a fake subclass in `EctoSalvageServiceTest`.
6. Done — `docs/CURRENT_ARCHITECTURE.md` §2, §3, §4, §5.3, §6, §9 item 3 updated to describe the
   new boundary; `docs/KNOWN_PROBLEMS.md` §4.4's Status paragraph updated to record the Ecto part of
   the gap as resolved by this story, while explicitly keeping `Gw2App`'s unrelated direct
   `sync.*`/`repo.*` calls open and scheduled for Phase 3 (not marked complete).

**Tests run:**

- New `application.EctoSalvageServiceTest` (fake `EctoLivePriceGateway` subclass, no HTTP): quote
  handoff (captures the exact `int[]` passed to `fetchQuotes`) and all four
  combinations evaluated correctly (cross-checked against direct `EctoSalvageCalculator.evaluate(...)`
  calls), missing-Ecto-quote and missing-Dust-quote both return `EctoScenarios.UNAVAILABLE`, and a
  gateway exception propagates unwrapped from `calculate()`. 4/4 pass.
- Existing `ecto.EctoSalvageCalculatorTest` (moved, unchanged): 7/7 pass — fee/yield math unaffected
  by the extraction.
- Existing real-view `uiverify.EctoFeeNoticeSmokeIT` (unmodified): 1/1 pass — the fee-inclusive
  notice still renders via the real `Gw2App` → button-click → `EctoView.show(Stage, Runnable)` path.
- New real-view `EctoSalvageViewIT` (default package, so it can call `EctoView.show(Stage, Runnable,
  EctoSalvageService)` directly instead of via reflection): injects a fake `EctoSalvageService`
  returning four fixed `ScenarioResult`s, waits for the async load to complete, then reads the real
  `GridPane` cells (by `id`, via the same `GridPane.getColumnIndex`/`getRowIndex` inspection
  `EctoView.setCell` itself uses) and asserts they show the exact `CoinUtils.formatSigned`/`format`
  strings for the injected profit/Luck-cost values, and that the fake service's `calculate()` was
  invoked exactly once — proving `EctoView` renders the service's results and delegates its one load
  ("refresh") action to the service rather than doing its own HTTP call. 1/1 pass.
- Full default suite: `./mvnw -o test` — 107 tests, 0 failures/errors (up from the pre-story 103:
  `EctoSalvageCalculatorTest`'s 7 tests moved package with no count change, plus the 4 new
  `EctoSalvageServiceTest` tests).
- Both "IT"-suffixed real-view tests re-run individually to confirm (`-Dtest=EctoFeeNoticeSmokeIT`,
  `-Dtest=EctoSalvageViewIT`): both pass; a real display/JavaFX session and outbound network access
  to `api.guildwars2.com` were both available in this environment (confirmed by the full-suite
  PostgreSQL-backed repository tests and these two TestFX runs all passing without skips).

**Limitations / non-scope:**

- `Gw2App`'s own button handlers still call `sync.*`/construct repository instances directly — that
  gap is explicitly out of this story's scope (Constraints: "no unrelated synchronization... work")
  and remains open per `docs/KNOWN_PROBLEMS.md` §4.4's "Still open" note and `docs/ROADMAP.md` §7.
- `docs/ROADMAP.md` §7's Phase 3 exit criteria were not marked complete: they require application
  services for several other flows (account refresh, global refresh, TP price refresh, graph
  rebuild) not touched by this story, plus the `Gw2App` item above. `docs/ROADMAP.md` is that
  document's own owner and was intentionally left unedited per the Documentation Map.
- `EctoLivePriceGateway.fetchQuotes(...)`'s live-network path itself (as opposed to the orchestration
  around it) is exercised only indirectly, by `EctoFeeNoticeSmokeIT`'s real button-click path hitting
  the real GW2 API — there is no dedicated `api.tp.EctoLivePriceGatewayTest` for the HTTP/JSON
  parsing in isolation, matching this story's Required Tests scope (application-layer + calculator +
  view regressions) and the pre-existing precedent that this exact HTTP/JSON logic had no such
  dedicated test before the move either (behavior-preserving relocation, not new-code coverage
  expansion).

## Blockers

None.
