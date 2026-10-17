## Story ID

STORY-WEB-001

## Title

Build the Vue Crafting Profit table against the backend API

## Status

DONE

## Milestone

milestone-05

## Goal

Deliver the first usable browser Crafting Profit screen: scope/settings input, table results, search/sort and manual reload, displaying authoritative backend values through the existing HTTP API.

## Authoritative Source Documents / Sections

- Supplied `docs/ROADMAP.md` Phase 5 objective, Crafting Profit screen and frontend-test high-level stories, backend-only calculations and JavaFX coexistence exit criteria.
- `docs/TARGET_ARCHITECTURE.md` sections 4.1 and 30: decided frontend technology and verification constraints; sections 12 and 14: presentation responsibilities and domain states; section 33: full-page performance requirement.
- `docs/CURRENT_ARCHITECTURE.md` section 5.5: existing Profit request defaults, response fields and errors.
- `docs/DOMAIN_SPEC.md` sections 2.1, 2.2.1 and 21: purpose, selection/refresh behavior and unavailable prices.
- `agent/stories/STORY-API-006-crafting-selector-options.md`: browser selector facts.

## Context

AR-001 is resolved; `TARGET_ARCHITECTURE.md` section 4.1 owns the Vue 3/strict TypeScript decision. The existing Profit API supplies authoritative results but no resolution tree. This story is a bounded table slice, not completion of the entire screen or milestone. Tree presentation, synchronization controls, other screens, final real-database performance verification and the milestone health review remain subsequent Phase 5 work.

## Acceptance Criteria

1. Establish a browser-rendered Vue 3 client with strict TypeScript and Composition API single-file components using `<script setup lang="ts">`. Use compatible stable dependencies locked reproducibly, documented local start/build/test/type-check commands, and a working browser-to-backend HTTP connection. Keep the frontend separate from Java backend/domain code and avoid a server-side JavaScript application runtime.
2. Opening Crafting Profit loads the selector facts through STORY-API-006 and submits the existing `POST /api/crafting/profit` contract. Start with All and the documented API defaults. Offer All, generic discipline and character/discipline scope choices through a sole Discipline selector, and input controls for the existing supported Profit settings. Leave eligibility and validation authoritative in the backend.
3. Render backend recipe identity, craftable count, per-craft and total profit, costs, missing requirements and available status data. Display and sort `totalProfitCopper` directly. No client-side profit, fee, valuation, crafting, inventory or eligibility calculation is introduced. Currency formatting is presentation only.
4. Preserve unavailable rows and `resultAvailable: false`; show returned blocked reasons, including `PRICE_UNAVAILABLE`, without replacing missing values with zero or suggesting incomplete results are profitable. Render special states actually returned by this contract; do not infer tree-only states from prices or invent missing response fields.
5. Provide loading, empty and request-failure states, search, sorting and manual reload. Preserve valid scope/settings/search/sort selections on reload. A scope/settings change requests fresh backend results; a late response for an older selection must not replace the current selection's results. An unavailable selected option falls back gracefully under the established domain selection semantics.
6. Verify the real browser can retrieve and display results from the actual backend. Frontend calls only this backend for application data and operations; database/GW2 credentials stay backend-only. JavaFX remains usable through its existing shared application services.
7. Document the new frontend and local verification path at the appropriate documentation owners. Record what was actually tested and any remaining contract limits. Preserve the section 33 budget in design; this partial screen does not claim final browser performance acceptance.

## Required Tests

- Component/interaction tests with controlled HTTP responses: default request, scope/settings changes, preserved refresh controls, search/sort, empty/loading/error states and out-of-order responses.
- Use a fixture whose supplied total differs from count multiplied by per-craft profit to prove the frontend displays and sorts the supplied value; cover unavailable rows and missing numeric values without repeating domain formulas in frontend tests.
- Strict TypeScript check and production frontend build; a browser smoke check against the running backend for selector load, calculation response and rendered table. Record database/environment used without exposing secrets; mocked checks do not establish real-data performance.

## Constraints

- Follow the technology decision at `TARGET_ARCHITECTURE.md` section 4.1; routine local build/test tooling must remain compatible with it. Escalate a materially different architectural choice rather than silently deciding it.
- No resolution-tree contract/design, automatic refresh workflow, sync-trigger UI or other screen in this slice.
- No domain calculation duplication, direct GW2 API access, database access or frontend secrets.
- No reduction of requested results, hidden preloading or stale-result substitution to appear faster. Backend-only timings do not satisfy section 33.
- Do not remove JavaFX or change its calculations or workflows.

## Dependencies

STORY-API-006.

## Definition of Done

The browser table works against the backend with meaningful rendering/interaction checks, strict typing and a reproducible build. Documentation and Result record actual evidence and limitations. Remaining Phase 5 exit criteria are not declared satisfied by this slice.

## Result

DONE. A browser Crafting Profit table exists in `frontend/` and was verified against the running
backend and the real database.

### Resumed work, not restarted

An interrupted attempt had left most of `frontend/` in place. It was inspected rather than rebuilt;
the screen, composables, API client and four test files were kept as found. What was actually
missing or wrong:

1. **Two failing tests** (`npm test` reported 24/26 on the found tree), both real defects, not test
   noise:
   - `FakeCraftingApi` always echoed fixed settings, while the real backend echoes back the
     settings it calculated with (`CraftingProfitApiMapper.toEffectiveSettings`). The screen takes
     its control state from that echo, so the stand-in disagreed with the contract it stood in for
     and a changed setting appeared to revert on reload. Fixed by making the stand-in echo the
     request (`echoedProfitResponse`).
   - The scope selector and settings form were `:disabled` while a request was in flight. A
     disabled control cannot receive an event, so the out-of-order test's *second* scope change
     never fired and the late response legitimately won. This also made AC 5's superseding
     unreachable in the real UI — a calculation takes seconds, and the requirement presupposes the
     user can change selection during one. Both controls now stay enabled; Reload alone stays
     disabled while loading, as a busy affordance for what would be a duplicate request. The
     request-id guard in `useCraftingProfit` was already correct and was not changed.
2. **`scripts/browser-smoke.mjs` did not exist**, though `package.json` already declared
   `smoke:browser`. Written for this story.
3. **No `frontend/.gitignore`**, so `node_modules/` and `dist/` would have been committed. Added;
   `git add -An frontend` now stages 26 source files and nothing generated.

### Evidence

All commands run on the final tree.

| Check | Result |
|---|---|
| `npm run type-check` (`vue-tsc --noEmit`, strict) | clean |
| `npm test` | **4 files, 26 tests, 26 passed** |
| `npm run build` | type-check + build OK — `dist/` 74.99 kB JS (28.73 kB gzip), 2.49 kB CSS |
| `npm run smoke:browser` | **PASSED, 8 steps** (real Chrome via `playwright-core`) |
| `./mvnw test` | **270 tests, 0 failures, `BUILD SUCCESS`** |

**Browser smoke, against the running backend and the user's real PostgreSQL database**
(Chrome 
`C:/Program Files/Google/Chrome/Application/chrome.exe`, backend `./mvnw spring-boot:run` on
`localhost:8080`, dev server on `localhost:5173`, Windows 10, 2026-09-25; no credential read or
recorded): selector loaded from the backend with **30 entries, first "All"**; `POST
/api/crafting/profit` → HTTP 200; **3151 rows rendered**; sort and search applied over them; a
scope change produced a further HTTP 200 and `scope DISCIPLINE / Chef` with **433 rows**; no page
errors and **no non-backend API call observed** — the run asserts on the browser's own network
records, so only `/api/crafting/selector-options` and `/api/crafting/profit` were contacted.

**Displayed value is the backend's, proven on real data.** The default sort is
`totalProfitCopper` descending. The browser's top row read `29g 14s 80c`; the maximum
`totalProfitCopper` in the raw response body was `291480`, and `29×10000 + 14×100 + 80 = 291480`
exactly. The value is displayed and ordered as supplied, not recomputed.

**JavaFX unaffected.** No Java, JavaFX or application-service file was modified by this story; the
270-test suite passing on the final tree is the check, not an inference.

### Section 33

Not claimed and not satisfied by this slice. Recorded only as an observation: three consecutive
navigations to a populated, sortable 3151-row table took **3.26 s / 3.23 s / 3.18 s** from
navigation to interactive. That was measured through the Vite **dev server** (not production
assets), on one machine, for the default All scope only, with the backend already warm, and outside
the procedure `TEST_STRATEGY.md` §34 owns. It is evidence the design has not spent the budget, not
acceptance of it.

### Contract limits and what was *not* proven live

- **`PRICE_UNAVAILABLE` and `resultAvailable: false` were not observed in the browser.** The real
  default-scope response contained only `NONE` (72), `BUYING_DISABLED` (3040) and
  `RECIPE_NOT_ALLOWED` (39), and zero unavailable results. Both states are covered at component
  level with controlled responses (`—` rather than `0`, row preserved, reason shown). The live run
  did not exercise them.
- **No resolution tree.** `POST /api/crafting/profit` does not carry one (`CURRENT_ARCHITECTURE.md`
  §5.5), and none was inferred from prices. `missingToBuyOne` is returned by the contract and typed
  but is not rendered; only `missingToBuy` is shown, truncated to three items.
- **No response validation.** `api/types.ts` describes transport shape only; TypeScript does not
  check what the backend actually sent, per `TARGET_ARCHITECTURE.md` §4.1.
- **All 3151 rows are rendered into the DOM** — no virtualization, no pagination. Acceptable at
  this scale on the measurement above; it is the first thing to re-examine if §33 is later measured
  against production assets.
- **Deployment serving is out of scope.** Only the dev-server path was built and verified; `/api`
  reaches the backend through the dev server's proxy, so a deployment would need its own
  equivalent. No CORS configuration was added to the backend, and none was needed.

### Documentation

- `docs/CURRENT_ARCHITECTURE.md` — new **§5.11 Browser frontend**: technology as built, file
  layout, runtime and secret boundary, data flow including the empty-body first request and the
  superseding rule, an explicit statement of what the frontend does not calculate, and the local
  command table.
- `docs/TEST_STRATEGY.md` — §12 present tense; new **§12.1** (the three rules the two found defects
  came from: a stand-in must answer like its contract, a fixture must be able to expose a derived
  value, a test must be able to reach the interaction it claims to cover) and **§12.2** (what each
  verification level does and does not establish; smoke ≠ §33).
- `README.md` — Technology Stack, and a *Browser (Crafting Profit only, in progress)* run path
  alongside the unchanged desktop one.
- `docs/ROADMAP.md` untouched: phases and exit criteria are unchanged by this slice.

No Phase 5 exit criterion, milestone transition or performance acceptance is declared.

## Blockers

None.
