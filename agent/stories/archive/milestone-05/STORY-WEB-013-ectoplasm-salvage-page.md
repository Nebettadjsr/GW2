## Story ID

STORY-WEB-013

## Title

Expose the existing Ectoplasm calculation through HTTP and a browser screen

## Status

DONE

## Milestone

milestone-05

## Goal

Provide the Phase 5 Ectoplasm Salvage screen over a thin HTTP adapter to the existing application service, displaying its four fee-inclusive scenarios without duplicating calculations in the browser.

## Authoritative Source Documents / Sections

- docs/ROADMAP.md, supplied Phase 5 section: Ectoplasm screen, backend authority, frontend interaction tests and JavaFX coexistence.
- agent/PROJECT_STATE.md: remaining Ectoplasm browser workflow and HTTP prerequisite.
- docs/CURRENT_ARCHITECTURE.md section 5.3: EctoSalvageService.calculate(), live quote acquisition and four scenarios.
- docs/DOMAIN_SPEC.md sections 2.3, 20 and 45-47: separate salvage feature, quote semantics, net proceeds and Luck costs.
- docs/TARGET_ARCHITECTURE.md sections 4.1, 8-9, 12-12.1 and 23: Vue/TypeScript, application/HTTP boundaries, shared presentation assets and measured execution policy.
- docs/FRONTEND_UX_GUIDELINES.md: applicable application navigation, shared presentation, responsive layout, state and accessibility sections, referenced by TARGET_ARCHITECTURE section 12.
- docs/TEST_STRATEGY.md section 12: frontend rendering, interaction and state verification.

## Context

The documented desktop flow already delegates live Ecto/Dust price acquisition and all four Ecto-buy/Dust-sell calculations to EctoSalvageService. This differs deliberately from the crafting screens' database-backed quotes. The supplied backlog contains no Ectoplasm HTTP/browser story. This bounded vertical slice adds transport and presentation for that existing parameterless use case; it does not redesign salvage economics. Shared icon infrastructure remains owned by API-009 and WEB-010.

## Acceptance Criteria

1. Add and document an HTTP operation for the existing EctoSalvageService.calculate() use case on the established backend runtime. Its adapter delegates once per accepted calculation and maps transport-only values; it contains no quote acquisition sequence or economic formula. Document the route, method, accepted input, response units/nullability and failures. Reject unsupported calculation inputs before delegation; do not introduce adjustable economic parameters or a new calculator.
2. Return the application/domain-provided four buy/sell scenarios and the price/result values needed by the existing presentation, including gross raw quotes/recovered sale values distinguished from fee-inclusive economic profit and cost per 1000 Luck under DOMAIN_SPEC sections 25 and 46. Preserve precision, signs and unavailable values. If an existing presentation value needs exposing, pass it from its authoritative backend owner rather than derive it in the HTTP mapper or browser. Consume DOM-024's policy alignment when available; preserve yield assumptions and apply fees exactly once in the domain; label assumptions as expected values rather than guaranteed drops.
3. Keep live Ecto/Dust quote fetching behind the existing application gateway. Do not replace it with synchronized database prices, add a sync prerequisite, or expose GW2 transport models/secrets. Obtain representative runtime measurements and apply TARGET_ARCHITECTURE section 23's existing measured execution policy; document the evidence and any reused task/status contract before implementing its browser consumption.
4. Add an addressable Ectoplasm Salvage destination in the established shell. Opening the screen loads the backend calculation; an explicit reload requests a fresh calculation. Present all four combinations with unambiguous instant/order buying and instant/listing selling labels, money/Luck units and concise fee-inclusive wording. Display backend values using shared formatting without recomputing fees, yields, profit, net cost or Luck costs.
5. Distinguish loading, successful results and request failure. A failed reload must not present an earlier answer as fresh or missing values as zero. Suppress duplicate in-flight reloads and prevent superseded responses or responses after leaving the screen from replacing the current screen state. Surface safe, useful errors and an explicit retry without leaking backend exception details.
6. Apply the established shell, shared styles and applicable UX guidelines, with keyboard-reachable controls, visible focus, non-color-only gain/loss meaning and narrow-screen readability. Reuse WEB-010's icon component and backend metadata when available; otherwise use the established neutral fallback. Do not add a competing cache, direct upstream image fallback or browser GW2 call. Record any remaining shared-icon integration dependency explicitly.
7. JavaFX retains its existing in-process service call and fee-inclusive behavior. Neither client acquires a second economic implementation; no changes to general crafting calculations or synchronization behavior are included.
8. Record the HTTP contract, browser flow, measured execution policy, verification evidence and limitations in CURRENT_ARCHITECTURE and this story's Result. No Crafting Profit performance or Phase 5 completion claim follows from Ectoplasm timing.

## Required Tests

- HTTP contract tests with controlled service results: exactly-once delegation, every scenario/value preserved, nullable/unavailable versus zero values, rejected input without delegation, and sanitized failures. Verify independent requests cannot surface another request's result.
- Relevant existing Ecto application/domain regressions and a focused JavaFX compatibility check if shared backend files change; preserve the decided fee policy and established yield behavior rather than add a parallel formula in tests at the transport layer.
- Frontend component/interaction tests with deliberately distinctive backend-provided numbers: all four scenarios and bases rendered unchanged, loading/failure/retry, failed reload, duplicate suppression and late-response isolation. Assert the browser does not derive result values from supplied quotes.
- Type checking/build and a real-browser controlled-response check of navigation, reload, keyboard operation and narrow layout. Inspect network evidence to verify calculation/price requests go only to the backend. Controlled origins must be isolated from any live backend proxy.
- Representative live backend timing for section 23 and a bounded browser-to-backend integration check comparing rendered scenario values with that response. Distinguish controlled-response evidence from live evidence; record unavailable live verification honestly rather than invent timings or success.

## Constraints

- One existing use case only; no user-selectable yields, additional salvage items, inventory consumption, new synchronization operation or economic redesign.
- Vue 3 and strict TypeScript; keep JavaFX, HTTP, JSON and infrastructure dependencies outside the domain.
- Reuse existing application, HTTP error and presentation patterns. Route naming and local DTO structure are routine implementation choices, not a new architectural decision.
- Shared icon delivery remains API-009/WEB-010's responsibility; do not modify the active icon story or duplicate its infrastructure.
- Do not alter Crafting Profit's separate fee rule or claim its full-page performance gate is satisfied.

## Dependencies

Existing EctoSalvageService and EctoSalvageCalculator documented in CURRENT_ARCHITECTURE section 5.3; completed STORY-WEB-004 shell. No unfinished story blocks the independent workflow. Reuse STORY-WEB-010 for shared icon integration when available.

## Definition of Done

The browser exposes the existing Ectoplasm use case through a documented thin HTTP boundary; all four scenarios display authoritative values, required verification is recorded, JavaFX coexistence is preserved and delivered behavior/limitations are documented at their owners.

## Result

DONE. Backend HTTP boundary plus browser screen over the **existing** `EctoSalvageService.calculate()`;
no salvage economics were added, moved or re-derived anywhere.

**An interrupted attempt was resumed, not restarted.** Already on disk and uncommitted when this
session started: `web.EctoSalvageApiController`, `web.EctoSalvageApiExceptionHandler`,
`web.dto.EctoSalvageResponse`, `web.EctoSalvageApiControllerTest`, the `EctoSalvageService` bean and the
two boot-test assertions, `ecto.EctoSalvageCalculator.SELL_FEE_PERCENT` with its pinning test,
`frontend/src/api/ectoApi.ts` and the `types.ts` shapes, `frontend/src/ecto/` (screen, composable, three
test files), the `ecto` destination in `destinations.ts`/`App.vue`, the `App.spec.ts` additions and
`frontend/scripts/ecto-browser-smoke.mjs`. That code was **verified against the acceptance criteria
rather than rewritten.** What was missing and is now added: `frontend/scripts/ecto-live-smoke.mjs` (a
file `package.json`'s `smoke:ecto:live` script already pointed at but which did not exist, so AC 3's
measured-execution evidence and the live comparison had no harness), every verification run, and all
documentation. No defect was found in the resumed code — every check below passed on its first run.

**AC 1 — the route.** `GET /api/ecto/salvage`, JSON, **no** accepted input: any query parameter is
rejected 400 `UNSUPPORTED_REQUEST` *before* the service is called (`request.getParameterMap()`, not the
raw query string), so no caller is told a parameter was honoured. One `calculate()` call per accepted
request, then a field-for-field copy; no price fetch, no quote sequence, no fee, no yield scaling and no
profit/net-cost/Luck-cost arithmetic at the boundary. No adjustable economic parameter and no second
calculator exist. Contract documented in `CURRENT_ARCHITECTURE.md` §5.15.

**AC 2 — values.** All four scenarios in their own named fields (`instantBuyInstantSell`,
`instantBuyListingSell`, `listingBuyInstantSell`, `listingBuyListingSell`), seven copper integers each:
gross `ectoAcquisitionCostCopper`/`dustGrossUnitPriceCopper` kept apart from the fee-inclusive
`dustNetUnitPriceCopper`, `netValueOfRecoveredDustCopper`, `netCostPerEctoCopper`,
`profitPerEctoCopper` and `costPer1000LuckCopper` (§25, §46–§47), signs and supplied zeros preserved, an
unavailable result four **nulls** and never zeros (§21). The assumptions group is read from its domain
owner — `LUCK_PER_ECTO`, `DUST_PER_ECTO`, `ECTOS_PER_1000_LUCK` and the new `SELL_FEE_PERCENT`, which
exists so the boundary states the fee without a second copy of it; `EctoSalvageCalculatorTest` pins it
to the multiplier the calculation actually deducts and the existing `0.85` arithmetic is untouched, so
the fee is still applied exactly once, in the domain, on the Dust side only. Yields are labelled
expected values over many salvages, not guaranteed drops (§45, §47). **DOM-024 is still TODO**, so there
was no policy alignment to consume; this story changed no economics, so nothing here pre-empts it.

**AC 3 — live quotes and measured execution.** Quote acquisition stays behind
`application.EctoSalvageService` → `api.tp.EctoLivePriceGateway`. No synchronized price row, no sync
prerequisite, no GW2 transport model or secret crosses the boundary, and nothing in `frontend/`
contacts ArenaNet (asserted in both browser checks). Measured before the browser consumption was
accepted, with `ecto-live-smoke.mjs` against the real backend and the real Trading Post — **15
sequential samples in two runs**: `[317, 141, 6121, 294, 97]` ms (median 294, min 97) and
`[164, 116, 62, 1357, 171, 471, 75, 73, 396, 218]` ms (median 164, min 62). Median well under 300 ms
with one 6.1 s upstream outlier, so per §23/UD-007 the route answers **synchronously** — no background
task, no status endpoint, no reused task/status contract. The outlier is why the screen has a loading
state and suppresses duplicate in-flight reloads instead of assuming an instant answer.

**AC 4 — destination.** `#/ecto` in the established shell, its own document title and page heading,
`nav-ecto` marked current. Opening it issues exactly one `GET /api/ecto/salvage`; the explicit "Reload
calculation" action issues exactly one more. Deliberately **not** `KeepAlive`d — no scope, settings,
search or selection to preserve, and a live price snapshot should not be re-presented as current
(asserted: bank → ecto → bank → ecto produces two calculations). All four combinations are shown with
"Instant buy"/"Buy order" and "Instant sell"/"Listing sell" spelled out (§20), money/Luck units stated,
gross versus after-fee stated per column, and every figure rendered through the shared
`formatCopper`/`formatSignedCopper` only.

**AC 5 — states.** Loading (`role="status"`), result, no-result and failure are four distinct
renderings and never overlap. A failed reload drops the previous answer rather than presenting stale
prices as fresh; a missing value is never zero; `resultAvailable: false` is a warning that says it is an
answer, not a failure. A reload asked for while one is in flight is **suppressed** (the control is
disabled and the composable refuses re-entry), superseded answers and answers arriving after the scope
is disposed are discarded, and the failure notice carries only the backend's own sanitized code plus an
explicit "Try again".

**AC 6 — shell and UX.** `PageHeader`, shared tokens, the shared `.table-region` scroll pattern, a
keyboard-reachable reload (one Tab, visible outline measured, Enter activates), gain/loss written as
`gain`/`loss`/`break-even` beside the signed figure, and no sideways scroll at 360 px (the 987 px of
columns scroll inside their own 334 px focusable region). `ItemIcon` is reused with **no** source, since
this route carries no item metadata, so the established neutral placeholder stands in — no URL is built,
no upstream fallback exists and no competing cache was added. **Remaining shared-icon dependency,
recorded:** real images for Ecto and Dust need `iconUrl` on this response, i.e. `STORY-API-009`'s
metadata contract extended to this route; that is not part of this story and WEB-010 was not modified.

**AC 7 — JavaFX.** `EctoView` still calls the same service in process; service, gateway, calculator
arithmetic and the view are unchanged (the only change to a shared backend file is the additive
`SELL_FEE_PERCENT` constant). Confirmed by `EctoSalvageViewIT` (TestFX, real desktop) —
**Tests run: 1, Failures: 0, Errors: 0, BUILD SUCCESS**. No crafting calculation or synchronization
behavior was touched, and Crafting Profit's separate fee rule is unchanged.

**AC 8 — documentation.** `docs/CURRENT_ARCHITECTURE.md` only: new §5.15 (route, statuses, what the
boundary does not do, shared-bean reasoning, the measured policy and its limits), §5.3's note that the
HTTP path is a second *caller* rather than a second implementation, §2/§3/§4's `web` entries and
dependency arrow, and §5.11's file layout, new "Ectoplasm Salvage page" block, corrected "Not built yet"
paragraph (the Ectoplasm screen is no longer missing; six destinations) and two new command rows.
`TEST_STRATEGY.md` needed nothing: these checks are existing verification levels, not a new one.

## Verification (commands run and their real output)

| Command | Result |
|---|---|
| `./mvnw test -Dtest='EctoSalvageApiControllerTest,EctoSalvageCalculatorTest,EctoSalvageServiceTest'` | **Tests run: 24, Failures: 0, Errors: 0** (12 + 8 + 4), BUILD SUCCESS |
| `./mvnw test -Dtest='Gw2ApiApplicationBootTest'` | **Tests run: 7, Failures: 0, Errors: 0**, BUILD SUCCESS (route mapped at `/api/ecto/salvage`, bean wired, nothing called) |
| `./mvnw test -Dtest='EctoSalvageViewIT'` | **Tests run: 1, Failures: 0, Errors: 0**, BUILD SUCCESS (local TestFX, real desktop) |
| `npx vitest run src/ecto src/__tests__/App.spec.ts` | **3 files, 36 tests passed** |
| `npm run build` | type-check and production build clean (173.40 kB JS / 54.58 kB gzip) |
| `npm run smoke:ecto` | **PASSED, 10 steps** in real Chrome against the script's own stub origin on `127.0.0.1:5182` |
| `npm run smoke:ecto:live` | **PASSED twice** against the real backend: 5 and 10 timed samples, opening and reload both compared field for field |

Controlled-response evidence (`smoke:ecto`, every unit test) is deliberately separate from live
evidence (`smoke:ecto:live`). The stub serves **incoherent** numbers — no field is the arithmetic
consequence of another and the fee percentage is 12, not the project's 15 — so a page that recomputed
profit, net cost, recovered Dust value or Luck cost, or stated the fee from its own knowledge, would
disagree with the check instead of agreeing with itself. It also covers what live data would not
produce: a 502 failure with retry, `resultAvailable: false`, a 700 ms slow calculation with the reload
disabled and no duplicate request, the 360 px layout and a request audit showing the page calls only its
own route and never leaves its origin.

Live run environment: backend `./mvnw spring-boot:run` on 8080 against the real GW2 Trading Post, dev
server `npx vite --host 127.0.0.1 --port 5183 --strictPort` proxying `/api` to it. **5173 was already
occupied by a pre-existing dev server**, so a dedicated port was used rather than assuming ownership of
that one; afterwards only the two process trees this session started were killed (identified by command
line and creation time) and both ports were confirmed released by a failed `curl` — the pre-existing
server was left running. Live values rendered exactly as received: item 19 721 / 24 277, fee 15 %,
instant-buy/instant-sell acquisition 1835 c, profit −1186 c displayed `-11s 86c`, cost per 1000 Luck
59 300 c.

## Limitations

- **No `TARGET_ARCHITECTURE.md` §33 claim and no Phase 5 completion claim** follows from any of this.
  The §23 figures are this route's backend request time only; the Ectoplasm page has no full-page
  timing, and nothing here says anything about Crafting Profit's performance gate.
- Real item images on this screen remain an open shared-icon dependency (AC 6 above).
- A **live** no-result and a **live** upstream failure were not observed — the Trading Post answered
  every one of the 17 live calculations. Both situations are controlled-response evidence only.
- The 6.1 s outlier was observed once and not diagnosed further; it is attributed to the upstream GW2
  API because the route does nothing else. A sustained upstream slowdown could still make the
  synchronous choice worth revisiting.
- `DOM-024` (Ectoplasm fee-policy alignment) and `DOM-023` remain TODO; this boundary exposes today's
  domain policy unchanged and will follow whatever those stories decide.

## Blockers

None.
