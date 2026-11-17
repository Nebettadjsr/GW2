## Story ID

STORY-API-008

## Title

Expose request-local crafting resolution through the decided HTTP contract

## Status

DONE

## Milestone

milestone-05

## Goal

Expose Profit and Discovery resolution detail from APP-012 through thin HTTP adapters implementing TARGET_ARCHITECTURE section 13, enabling browser rendering of authoritative explanations without reconstructing domain results.

## Authoritative Source Documents / Sections

- Supplied `docs/ROADMAP.md` Phase 5 objective and exit criteria: backend-authoritative resolution trees, special states and JavaFX/web coexistence.
- `docs/TARGET_ARCHITECTURE.md` sections 13.1–13.5: decided detail operations, lifetime, schema, identity, errors and execution-policy measurement; section 23: long-running-operation policy.
- Resolved AR-002 and AR-003, incorporated in `docs/TARGET_ARCHITECTURE.md` section 13; that section owns the contract.
- `agent/stories/STORY-APP-012-request-local-resolution-detail.md`: common captured-input application operation and remaining transport work.
- `agent/stories/STORY-DOM-020-semantic-resolution-trace.md`: authoritative semantic trace and preserved limitations.

## Context

The completed domain trace and queued application prerequisite do not provide HTTP detail. The architect has resolved the root-identity question: the envelope identifies the requested recipe while tree nodes describe actual sourcing. This story integrates the decided contract after APP-012; the browser consumer remains a subsequent slice.

## Acceptance Criteria

1. Add both POST resolution operations specified in section 13.1. Validate the required recipe identity and nested calculation before invoking application work, reusing each table operation's established defaults and validation. Preserve Profit scope and Discovery's separate nullable inventory character and fixed daily setting.
2. Delegate each valid operation to APP-012 with fresh request-local state. Map its selected row and explanation from their shared captured inputs; do not access a previous table request, reload inputs in the mapper, or create an alternative resolver.
3. Implement the complete response envelope and recursive node shape of section 13.3 with transport-only DTOs. Preserve ordered repeated occurrences, nullable names/costs, known zero, precision, special states, blocked reasons and actual sourcing facts. The original `SINGLE_OUTPUT_REQUIREMENT` basis was superseded for Crafting Profit by the full selected-result output quantity (see the Result addendum); Discovery retains the original basis. Preserve `FRESH_CALCULATION`, keep requested identity separate from every node's actual recipe identity, and never invent a requested-recipe root or ingredient path.
4. Implement section 13.4 outcomes: absent fresh candidate is 404 RECIPE_NOT_IN_CALCULATION; blocked explanations and unavailable calculation results are completed 200 outcomes with distinct tree status/nullability. Preserve existing safe 400/503/500 error handling without leaking database, credential or exception details.
5. Measure both detail operations on representative real database inputs before fixing the execution policy, following sections 13.5 and 23. Record scopes/settings, data scale, first/repeat timings and limitations. Use synchronous completion only when measurements support it. If task transport needs an unsettled architecture decision, report the concrete evidence and blocker for architect routing before shipping an assumed transport. Do not claim browser performance from these measurements.
6. Preserve existing table contracts and their lack of eager per-row semantic traces, and JavaFX's in-process access. Domain/application logic must not acquire transport dependencies. No synchronization or persistent inventory mutation is triggered by detail.
7. Update the relevant CURRENT_ARCHITECTURE contract description and record actual verification, execution-policy evidence and remaining browser work in Result. Do not declare Phase 5 complete.

## Required Tests

- HTTP contract tests for both operations: valid input and explicit effective-input echo; required/invalid recipe and calculation inputs rejected before delegation; defaults and Discovery inventory-character semantics preserved; exact envelope/node fields and safe error outcomes.
- Mapping cases covering inventory-only roots, a different actual producing recipe, blocked attempted roots, repeated child occurrences, split sourcing, unavailable result versus available blocked tree, null versus known-zero cost, PRICE_UNAVAILABLE and UNVALUED_NONTRADEABLE. Assert supplied values rather than duplicating resolver calculations.
- Successive/concurrent request isolation and empty Discovery after a populated request; verify controllers delegate once and cannot expose a prior result.
- Representative read-only real-database comparison between HTTP output and application detail, plus first/repeat detail timings for the execution-policy decision. Report unavailable evidence as a limitation/blocker, never a fabricated pass.
- Affected application and HTTP regressions and default backend suite; targeted JavaFX compatibility checks if shared call boundaries change. No full-browser performance claim or automatic broad UI suite.

## Constraints

- HTTP integration only; no browser consumer, new economic rules, CH-15/CH-17 correction, synchronization workflow or broad service refactor.
- Section 13 remains the schema owner; implementation must consume its decision rather than redefine identities, cost basis or calculation consistency.
- Do not sum descendant costs, infer domain states from quotes, truncate trees silently or serialize persistence/GW2 response objects.
- No retained calculation sessions or new task infrastructure assumed without the measurement and architecture process required above.

## Dependencies

- STORY-APP-012 must be DONE before implementation.
- STORY-DOM-020 (DONE); resolved AR-002 and AR-003 as incorporated in TARGET_ARCHITECTURE section 13.

## Definition of Done

Both detail operations implement the decided contract over APP-012, with contract/isolation/compatibility verification and representative real-data execution-policy evidence recorded. Documentation identifies the remaining browser integration and full-page performance work. Any unresolved architecture requirement is reported as a blocker rather than marked complete.

## Result

DONE. `POST /api/crafting/profit/resolution` and `POST /api/crafting/discovery/resolution` implement
`TARGET_ARCHITECTURE.md` §13.1–§13.4 over `STORY-APP-012`'s application operation. **HTTP integration
only** — no browser consumer, no §33 page timing, no economic rule, no CH-15/CH-17 correction and no
Phase 5 exit criterion or milestone transition is claimed.

An interrupted attempt was **resumed, not restarted**: the production files, both contract suites and
the real-database IT were already in place, and `docs/CURRENT_ARCHITECTURE.md` §5.13 was already
written. This session re-verified all of it (full default suite plus a fresh real-database run whose
figures are the ones recorded below), completed the documentation that was still missing (§2 package
layout, §3 `web` responsibilities, §4 import direction, and three stale "no detail route exists"
statements in §5.5 and §5.11) and wrote the records. Nothing was re-implemented.

### What changed

| File | Change |
|---|---|
| `web/CraftingProfitApiController.java` | `+ POST /profit/resolution` — validate, one fresh service, `resolveDetail`, map |
| `web/CraftingDiscoveryApiController.java` | `+ POST /discovery/resolution` — the same, with Discovery's inventory character |
| `web/CraftingResolutionMapper.java` | **new** — the shared half: required-input rules, envelope literals, row projection, recursive node copy |
| `web/RecipeNotInCalculationException.java` | **new** — the one non-200 outcome of §13.4 |
| `web/ApiExceptionHandler.java` | `+` 404 `RECIPE_NOT_IN_CALCULATION`; 400/503/500 unchanged |
| `web/dto/Crafting{Profit,Discovery}ResolutionRequest.java` | **new** — `recipeId` + the existing table request as `calculation` |
| `web/dto/Crafting{Profit,Discovery}ResolutionResponse.java` | **new** — the §13.3 envelope, with each route's own echoed `calculation` |
| `web/dto/ResolutionNodeDto.java` | **new** — the recursive node, shared by both routes |
| `web/CraftingProfitResolutionApiControllerTest.java` | **new**, 26 tests |
| `web/CraftingDiscoveryResolutionApiControllerTest.java` | **new**, 11 tests |
| `web/CraftingResolutionApiRealDbIT.java` | **new**, 4 checks (real database, `*IT`, explicit run only) |
| `web/Gw2ApiApplicationBootTest.java` | `+1` test: both decided paths really mapped in the real context |
| `docs/CURRENT_ARCHITECTURE.md` | §2, §3, §4, §5.5, §5.11 (two stale claims), new §5.13 |
| `docs/TEST_STRATEGY.md` | §35.2: recursive-payload comparison and probe-the-measured-case methodology |
| `docs/KNOWN_PROBLEMS.md` | §7.10: two further read-only routes, write count unchanged at four |

### How the acceptance criteria are met

1. **Both operations, nested existing contract.** `calculation` *is* `CraftingProfitRequest` /
   `CraftingDiscoveryRequest`, so each route's own scope kinds, defaults, validation messages and
   rating semantics apply unchanged and no calculation control was added. `requireBody` /
   `requireRecipeId` (positive) / `requireCalculation` run **before** `Supplier.get()`, so a rejected
   request creates no service. Profit keeps its coordinated scope; Discovery keeps its required
   individual scope, its **separate nullable** `inventoryCharacterName` (echoed as null when absent,
   with the service's unfiltered-pool fallback) and `dailyBuyInsteadOfCraft` **fixed to false and
   reported, never accepted**.
2. **One fresh request-local delegation.** Each route calls `resolveDetail(...)` **once** on a
   service obtained from the same per-request `Supplier` seam the table route uses; no `last*` field
   is read or written. Row *and* tree are mapped from the single returned `CraftingResolutionDetail`
   — the mapper is stateless, touches no repository, graph or clock-dependent domain state, and
   therefore cannot reload inputs or reach a previous request. No second resolver exists at the
   boundary.
3. **The envelope and node shape.** `recipeId`, `calculation`, `consistency: FRESH_CALCULATION`,
   `calculatedAt` (UTC ISO-8601 completion instant), `row` (the *shared* `CraftingRowDto` via the
   *shared* `CraftingRowMapper`), `treeStatus`, route-specific `treeBasis`, `tree`.
   `ResolutionNodeDto` is a plain copy of `craft.CraftTraceNode`: ordered children with repeated
   occurrences kept separate, nullable `itemName`, nullable `recipeId`, enum facts as their own names
   (so an unknown future state stays visible), and the three inclusive costs as nullable integers with
   domain precision and **no** rounding, summing or repair. Requested identity lives only on the
   envelope; every node carries the recipe actually selected or attempted. Nothing invents a
   requested-recipe root or an ingredient path.
4. **§13.4 outcomes.** Absent fresh candidate → 404 `{ "error": "RECIPE_NOT_IN_CALCULATION" }` whose
   message carries the caller's own recipe ID only. A blocked explanation → **200** with
   `treeStatus: AVAILABLE` and its reasons; no calculation result → **200** with
   `treeStatus: RESULT_UNAVAILABLE` and `tree` null, never a fabricated empty tree. 400
   `INVALID_REQUEST` / `MALFORMED_REQUEST`, 503 `DATA_STORE_UNAVAILABLE` and 500 `CALCULATION_FAILED`
   keep their existing codes and fixed messages, with detail logged server-side only.
5. **Execution policy fixed by measurement** — figures below.
6. **Preserved.** Neither table route, its response shape nor its empty-list 200 changed, and neither
   attaches a tree per row. JavaFX still calls the application services in process; verified by
   `grep` that nothing outside `src/main/java/web/` imports Spring, a servlet type or `web.*`, and
   that no `craft.*`/`application.*`/`web.*` file imports JavaFX. Detail is read-only: no write, no
   synchronization, no GW2 API call, no persisted-inventory consumption.
7. **Documented** at §5.13 (plus the four other sections above), with the browser work and the
   absent §33 claim stated there and here.

### Verification (actual runs)

- `./mvnw -o test` on the final tree: **Tests run: 382, Failures: 0, Errors: 0, Skipped: 0 —
  `BUILD SUCCESS`** (was 344 after `STORY-APP-012`; +38 = 26 + 11 + 1).
- `CraftingProfitResolutionApiControllerTest` **26**, standalone MockMvc + the controller's own
  `Supplier` seam + a recording stub, asserting serialized JSON (`jsonPath`): explicit inputs reaching
  the service unchanged and echoed back; omitted members falling back to the *table route's* defaults;
  the two literals plus a `calculatedAt` bounded by the request; the row asserted field by field
  including the supplied `totalProfitCopper` (deliberately **not** count × per-craft profit, so a
  recomputation fails); the requested recipe's own root; an **inventory-only root** with null
  `recipeId`, zero craft/production and **no children**; **another producing recipe** keeping its own
  ID, executions and children; a **blocked attempted root** keeping its recipe with zero completed
  crafts and **null** complete cost; **repeated occurrences** of one item staying separate and ordered;
  **split sourcing** listing every contributing method; **known zero** (`UNVALUED_NONTRADEABLE` at 0)
  distinct from `PRICE_UNAVAILABLE`'s absent cost, plus `DAILY_LIMIT`; a missing name **absent** rather
  than fabricated; a **cycle** node finite with no back-link serialized; `RESULT_UNAVAILABLE` as a 200
  with no tree; 404 with the documented body; absent body / absent `recipeId` / non-positive
  `recipeId` / absent `calculation` / invalid nested calculation / malformed JSON all rejected **with
  no service created**; 503 and 500 bodies asserted free of `jdbc`, credentials, SQL and exception
  text; successive requests each on their own service; **concurrent** requests with distinct recipes
  not mixing; and a not-in-calculation outcome unable to expose an earlier request's tree.
- `CraftingDiscoveryResolutionApiControllerTest` **11**, covering what differs rather than repeating
  the shared cases: scope + inventory character + settings reaching the service unchanged and echoed;
  an **omitted inventory character arriving as null and echoed as null**; Discovery's **own** settings
  defaults (not Profit's) with the daily flag reported as false; the required scope validated by the
  table route's rules before any calculation; the recipe-ID rules; row/tree from this operation's own
  facts; blocked 200 versus unavailable 200; 404; safe 503/500; **an empty calculation after a
  populated one unable to surface the earlier tree**; concurrent isolation.
- `Gw2ApiApplicationBootTest` **6** (was 5): the new test reads
  `RequestMappingHandlerMapping` in the real context and asserts both decided POST paths are mapped —
  a standalone MockMvc setup cannot prove the path. No call is made, so no connection opens.
- **Real database, read-only** (`CraftingResolutionApiRealDbIT`, `*IT`, explicit run; re-run this
  session): **Tests run: 4, Failures: 0, Errors: 0 — `BUILD SUCCESS`**. Each case compares the HTTP
  response against `resolveDetail(...)` called in process with identical inputs, walking row **and the
  complete recursive tree in order** — 5, 5 and 15 nodes compared, every node's fields, child count
  and child order.
- Affected regressions pass unchanged: both table contract suites (17 + 20), both application detail
  suites (13 + 17), the domain explainer suites (14 + 5 + 5), `CraftingPlannerSingleRecipeTest` (4),
  and the JavaFX-facing crafting suites in the default goal (`CraftingProfitViewScopeChangeTest`,
  `CraftingProfitTotalProfitPresentationTest`, `CraftingProfitCoordinatedScopeTest`,
  `CraftingBlockedRowsTest`, plus the `uiverify`/`*ViewIT` TestFX checks). No call boundary shared with
  JavaFX changed, so nothing beyond the default goal's JavaFX coverage was run.

### Execution-policy evidence (§13.5 / §23 / UD-007)

Measured **before** fixing the policy, on the developer's real PostgreSQL (no schema override), wall
clock from issuing the request to holding the complete response body; context/server startup is
outside the timer. Crafting graph: **13 198** recipes in all cases.

| Case | Scope / settings | Data scale | First | Repeats | Max |
|---|---|---|---|---|---|
| Profit detail | `ALL`, Profit view defaults (useOwnMats, no buying, 1g budget, instant sell/buy, daily bought) | 3 175 visible recipes; recipe 484; 5 nodes, depth 2; 2 668 B | 400 ms | 411 / 265 / 263 ms | **411 ms** |
| Profit detail, deep tree | `ALL`, useOwnMats, **buying enabled**, 1000g budget | 3 175 visible; recipe 10 207 (chosen by probing 12 candidates, deepest found 15 nodes); 15 nodes, depth 3; 7 541 B | 397 ms | 513 / 402 / 399 ms | **513 ms** |
| Discovery detail | Armorsmith 500 — `Nbt Anch`, inventory character `Nbt Anch`, Discovery defaults (buying on, 20g budget, daily fixed false) | 672 visible; recipe 494; 5 nodes, depth 2; 3 030 B | 410 ms | 221 / 334 / 328 ms | **410 ms** |
| Not-in-calculation | `ALL`, Profit defaults | candidate + graph load only | 244 ms | — | **244 ms** |

**Decision: synchronous HTTP 200 completion** for both operations — the maximum observed is 513 ms,
well inside a normal request, and the payloads are single-digit kilobytes. No task, status route,
retained session or new task infrastructure was introduced, and §23's asynchronous path is therefore
not entered. No unsettled architecture decision blocks this; nothing was assumed about a task
transport. **These are backend request timings only** and prove nothing about a browser page.

### Limitations and remaining work

- **No browser consumer.** The frontend calls neither route: request/generation association, tree
  rendering, the requested-versus-actual labelling and late-response handling of §13.3/§13.4 remain
  (`STORY-WEB-007`). `TARGET_ARCHITECTURE.md` §33's navigation-to-complete-page timing is **not**
  claimed or measured here.
- **Real-data coverage is what the live database produced**: all three measured roots resolved as
  `CRAFT` of the requested recipe with `treeStatus: AVAILABLE`, no blocked reason and no state flag.
  Inventory-only and alternative-recipe roots, blocked attempts, split sourcing, cycles,
  `PRICE_UNAVAILABLE`, `UNVALUED_NONTRADEABLE`, `RESULT_UNAVAILABLE`, 503 and 500 are covered by
  **controlled-response contract tests only**; none was manufactured on the real database.
  `RESULT_UNAVAILABLE` also remains unreachable through the loaded flows for the reason
  `STORY-APP-012` recorded (`RecipeSimulator` always records a blocked attempt).
- **Equivalence caveat** (§13.2): HTTP and in-process are two fresh calculations moments apart, so a
  price change or synchronization between them would read as a mismatch; both runs are read-only with
  no synchronization in between.
- Measurements are one machine, one warm backend, one database, three cases and three repeats each —
  not a load test, and no concurrency timing was taken. The timing checks assert nothing by design.
- `KNOWN_PROBLEMS.md` **CH-15** and **CH-17** show through the tree unchanged, as §13.5 permits:
  requested identity on the envelope, actual sourcing on the node.
- Both routes are unauthenticated like every existing one (§20 TBD). They add no write, but do return
  recipe/price/inventory-derived detail.
- Observed, not fixed (pre-existing, created by `STORY-DOM-020`/`STORY-APP-012`'s numbering):
  `docs/CURRENT_ARCHITECTURE.md` has **two** sections numbered §5.12 — the account-inventory HTTP
  flows and the single-craft resolution explanation — so a "§5.12" cross-reference is ambiguous.
  Renumbering touches other stories' sections and their references, so it was left alone rather than
  changed outside this story's scope.

## Blockers

None.

## Supersession (2026-09-30)

The recorded Discovery daily value above describes the earlier contract. UD-012 renames the domain
setting to `allowDailyCrafts` and fixes Discovery's effective value to true, preserving its prior
ability to craft daily outputs under the clarified independent-setting semantics. The Profit API
accepts `allowDailyCrafts` directly.

The Profit tree quantity basis was also superseded after implementation: it now
resolves the fresh detail row's complete counted result. The row's
`craftableCount` is recipe executions and `outputCount` is units per execution;
accepted executions are replayed through the domain resolver and their actual
trace quantities and inclusive costs are aggregated. Profit responses report
`SELECTED_RESULT_OUTPUT_QUANTITY` when crafts were counted, or
`FIRST_BLOCKED_ATTEMPT` for a zero-count row. Discovery retains
`SINGLE_OUTPUT_REQUIREMENT`. See `DOMAIN_SPEC.md` section 2.1.1,
`TARGET_ARCHITECTURE.md` section 10.2 and the current-architecture resolution
contract for active behavior.
