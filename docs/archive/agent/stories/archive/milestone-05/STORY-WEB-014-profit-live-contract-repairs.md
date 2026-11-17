## Story ID

STORY-WEB-014

## Title

Repair reported Profit value, resolution and calculation-control integration failures

## Status

DONE

## Milestone

milestone-05

## Goal

Make the integrated Profit page reliably display authoritative gross total sell value and valid selected resolution trees, and preserve the non-TP setting through recalculation.

## Authoritative Source Documents / Sections

- docs/DOMAIN_SPEC.md sections 2.1.1 and 2.2.1.
- docs/TARGET_ARCHITECTURE.md sections 12, 13.4 and 13.5.
- Supplied docs/ROADMAP.md Phase 5: Profit, resolution, backend authority and coexistence.
- agent/stories/STORY-WEB-007-profit-resolution-detail-view.md, Acceptance Criteria and Result.
- agent/stories/STORY-WEB-008-profit-economic-columns.md, Acceptance Criteria and Result.
- agent/stories/STORY-DOM-021-profit-non-tp-material-control.md, Result.
- agent/product-owner-requests/Request-010-polish-craft-profit.md.
- docs/TEST_STRATEGY.md sections 12 and 36; docs/FRONTEND_UX_GUIDELINES.md applicable controls and state guidance.

## Context

Request-010 reports a blank total sell value, a failed resolution request and a checkbox that immediately resets despite completed implementation stories. These are PO observations, not independently reproduced defects. Existing stories own the original features; this story closes the reported integration gaps without rebuilding them. DOM-023 separately owns the fee change and preservation of gross values.

## Acceptance Criteria

1. Reproduce and trace each reported symptom through the running frontend and backend at the tested revision. Record request/response and state evidence, distinguishing stale runtime or incompatible served assets from implementation defects. Correct confirmed causes within existing boundaries; do not assume a cause from the report alone.
2. An available Profit result displays backend totalSellValueCopper in table and applicable selected detail. Confirm the domain-owned gross value includes output quantity per execution and counted executions exactly once. Preserve zero versus unavailable and both quote modes. Reuse WEB-008's field and calculation; no browser fallback multiplication, fee deduction from gross values or second economic model.
3. Selecting a normal valid candidate with unchanged effective inputs renders the actual backend resolution tree through the established endpoint. Repair erroneous routing, identity or input association if evidenced. Keep genuine fresh-candidate absence, unavailable results and transport failures truthful; do not fabricate a tree, suppress legitimate 404s or change the established single-output-requirement basis.
4. Toggling Allow non-Trading-Post materials both ways retains the selected state and submits it to the table calculation. Fresh detail uses the resulting effective setting. Recalculation, explicit refresh and delayed/superseded responses must not silently reset it. Preserve UD-009/UD-010 semantics and the enabled initial default; no new cross-session persistence requirement.
5. Preserve stale-response isolation, display filters, selected-recipe identity, existing API contracts and JavaFX coexistence. DOM-023 remains the owner of fee-policy integration. Record all three outcomes, concrete fixes or reproducible evidence of already-correct behavior, runtime prerequisites and any unavailable verification in Result. Update implementation documentation only where behavior changes.

## Required Tests

- Targeted regression tests at each evidenced failure boundary, reusing existing fixtures for gross multi-output/multi-craft totals, zero/null and both price modes. Mapping/rendering tests assert distinctive supplied values instead of duplicating the domain formula.
- Targeted table-to-resolution checks for valid identity/settings, genuine absent candidates, failures and stale responses.
- Interaction tests for toggling both directions, in-flight recalculation, refresh and matching table/detail settings.
- Explicit local real-browser smoke against the running backend for all three symptoms, recording revision, selected settings, request/response facts and displayed values/tree/control state. Controlled fixtures complement but do not replace this integration evidence. Do not trigger live synchronization implicitly.
- Directly affected local tests; complete suites, type checking and build remain the GitHub CI gate under TEST_STRATEGY section 36.

## Constraints

- No source-level redesign, new resolver, frontend calculations, new endpoint contract or changed product policy.
- Do not alter the active WEB-012 story or CURRENT_STORY.md. Preserve shared Discovery consumers when implementation changes shared code.
- Presentation removals and item-specific blocking explanations belong to WEB-015. No performance-gate or milestone-completion claim.

## Dependencies

Completed STORY-WEB-007, STORY-WEB-008 and STORY-DOM-021. No unfinished prerequisite; coordinate with DOM-023's existing fee scope without duplicating it.

## Definition of Done

The three reported integration symptoms are repaired or disproved with concrete current-runtime evidence, regression checks cover confirmed causes, local browser evidence and limitations are recorded, and the story revision passes the CI gate.

## Result

### Closure after backend restart

2026-09-27: Closed at the user's explicit request after the user stopped and restarted
the backend and the strict real-browser check confirmed all three symptoms resolved.
`npm run smoke:profit:live` exited 0 against `http://localhost:5173`, proxying to the
restarted backend on port 8080. Checkout remains
`9e211b6a3f8b870585b25fd5668dc1cf10f35ec3` plus the working-tree changes documented below.
No synchronization or database mutation was performed.

- Recipe 16: output quantity 5, counted executions 81. Instant-sell gross total
  `22680` copper displayed as `2g 26s 80c`; listing-sell gross total `36450` displayed
  as `3g 64s 50c`. Table, selected detail and fresh resolution summary matched the
  supplied backend values. Switching back to instant sell restored `22680`.
- Every selected-detail request returned HTTP 200 with matching recipe identity and
  effective calculation inputs, `AVAILABLE`, `SINGLE_OUTPUT_REQUIREMENT`, and a
  three-node tree whose complete ordered item-name sequence matched the rendered tree.
- The non-TP setting opened enabled. Both false and true were submitted, echoed and
  retained by the checkbox; explicit reloads preserved each state and fresh detail
  used the matching setting. All seven checked resolution responses succeeded.
- Fixed the smoke script's selected-row locator to include the existing accessible
  `Selected` suffix. This was a verification-script defect exposed by its first
  positive run, not an application defect.

Conclusion: an outdated running backend caused the reported failures; restarting it
restored the already implemented contracts. No production calculation or UI change
was needed. The preceding live-runtime blocker is resolved.

Verification limits remain explicit: the normal targeted frontend test command was
retried and still failed during Vite configuration loading with filesystem `EPERM`.
The earlier qualified 104-test result remains the available component evidence.
Backend regression suites and the story revision's CI gate have not been verified
in this session. DONE records the user's requested closure after successful live
confirmation, not a claim that those outstanding checks passed. No milestone or
performance acceptance is implied; WEB-012 and CURRENT_STORY remain untouched.

### Initial investigation (before restart)

2026-09-27: All three reported symptoms reproduced in real Chrome against the existing
Vite origin `http://localhost:5173` and its backend `http://localhost:8080`. Tested checkout:
`9e211b6a3f8b870585b25fd5668dc1cf10f35ec3` plus the pre-existing working-tree Discovery
changes and this story's verification changes. The exact revision of the already running JVM
is unknown; it demonstrably serves an older/incompatible contract. No live synchronization
was triggered, no database fixture was installed, and no existing server was stopped.

Observed request/response and browser evidence:

- Default `POST /api/crafting/profit` with `{}` returned HTTP 200 and 3,176 rows.
  Echoed defaults: All scope, own materials true, buying false, max buy 10,000 copper,
  instant sell/buy, daily buy instead of craft true. The settings object omitted
  `allowNonTradeableMaterials`; rows omitted `totalSellValueCopper` entirely (not JSON null).
  Example recipe 1, Soft Wood Plank: output count 1, craftable count 250, revenue 45 copper.
  Chrome displayed `—` in Total sell value. This is missing transport data, not evidence
  of a wrong gross-value formula.
- Selecting a normal displayed recipe issued one lazy request to
  `/api/crafting/profit/resolution`, which returned HTTP 404. The existing browser smoke
  selected +2 Agony Infusion and observed the same failure. A direct request for recipe 1
  with `calculation.scope.kind=ALL` returned the generic Spring body with `error: "Not Found"`
  and `path: "/api/crafting/profit/resolution"`, not `RECIPE_NOT_IN_CALCULATION`.
  Chrome truthfully displayed `UNEXPECTED_STATUS: The backend answered with HTTP 404.`
- The non-TP checkbox initially appeared unchecked. Checking it sent
  `settings.allowNonTradeableMaterials: true` with the other effective defaults. The 200
  response again omitted this setting; Chrome then showed it unchecked. The request contained
  the user's choice, but the incompatible response erased it when the frontend applied the
  backend's effective settings.

Source trace (not a claim about the old running JVM): `CostEvaluator.computeRevenue()`
includes output quantity once; `CraftingPlanner` multiplies that gross per-execution revenue
by craft count once. `CraftingRowMapper` copies `totalSellValueCopper` to the shared DTO.
The current controller declares the resolution route, and the Profit API mapper preserves
explicit false with an enabled default for an omitted non-TP setting. Existing domain and
HTTP tests cover gross multi-output/multi-craft, both quote modes, zero/null, valid detail,
genuine candidate absence and material-rule propagation. No current-source production defect
was established, so no alternative economics, synthetic tree or settings fallback was added.
The concrete runtime remedy is rebuilding/restarting the backend serving the frontend origin.
README now explains that Vite updates cannot refresh an already running Java process.

Verification changes:

- Added `npm run smoke:profit:live`. It requires the real running application and retained
  populated data, rejects missing fields/routes, compares supplied gross values in table and
  detail, compares the returned tree's full ordered item-name sequence, checks identity and
  effective settings, toggles non-TP off/on with a reload in each state, and checks both sell
  modes. It requires an available multi-output/multi-craft candidate and derives no economics.
  Missing live prerequisites fail explicitly rather than count as a pass.
- Added a component regression toggling off then on while both calculations are in flight,
  accepting the newer response before the older one, preserving selected recipe identity and
  checking matching detail settings and an explicit reload. Existing fixtures and association
  tests retain coverage for truthful absence/failure and stale detail responses.

Executed checks and limits:

- Existing `smoke:browser`: passed 11 steps against the incompatible runtime, explicitly
  reporting that the resolution 404 was not comparable. That permissive result does not prove
  this story's acceptance criteria; the new strict check closes that verification gap.
- New `smoke:profit:live`: failed as intended with
  `Missing non-TP setting: rebuild/restart the backend serving this origin`. Its positive
  end-to-end path remains unverified until a current backend is available.
- New script passes `node --check`. Changed tracked code passes `git diff --check`.
- Normal targeted Vitest invocation could not load Vite configuration because filesystem
  canonicalization returned `EPERM` for `C:\Users\Administrator`. A programmatic invocation
  with the same Vue plugin, alias and jsdom environment, plus preserved symlink paths, executed
  `CraftingProfitScreen`, `profitResolutionAssociation`, `SelectedResultDetail` and
  `CraftingResolution`: 104 tests passed across four files. However, its dependency preflight
  also hit the same filesystem restriction and the process exited 1. This is qualified test
  evidence, not a clean verification command or CI pass.
- A separate current backend on port 8081 was attempted without stopping the existing server.
  The Windows Maven wrapper failed resolving its distribution (`NULL` array); directly
  invoking the installed Maven 3.9.16 then failed with `AccessDeniedException` reading the
  JDK 25.0.1 master `conf/security/java.security` path. No new backend started. Backend tests
  and a current-backend browser pass therefore remain unavailable in this environment.

Remaining verification after restoring normal local filesystem access: start the current
backend with the existing database/environment configuration, ensure Vite proxies to that
process, run `npm run smoke:profit:live`, and run the four frontend suites above plus
`CraftingPlannerTotalSellValueTest,CraftingProfitApiControllerTest,` +
`CraftingProfitResolutionApiControllerTest,CraftingProfitResolutionDetailTest,` +
`CraftingProfitNonTradeableMaterialFlowTest` through Maven. Zero/null and stale-response cases
are fixture coverage; they were not observed on a current live runtime. Full suites,
type-check/build and the story revision's CI gate remain pending. No milestone completion,
performance, fee-policy change or completed-story claim is made. WEB-012 and CURRENT_STORY
were not changed by this work.

## Blockers

No remaining reproduced runtime blocker. The user restarted the backend and the strict
live smoke passed. Local test-runner filesystem restrictions and unverified CI are recorded
above as verification limitations of this user-requested closure.
