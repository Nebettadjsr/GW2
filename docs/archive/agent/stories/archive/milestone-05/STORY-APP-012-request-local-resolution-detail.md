## Story ID

STORY-APP-012

## Title

Coordinate resolution detail from one request-local set of calculation inputs

## Status

DONE

## Milestone

milestone-05

## Goal

Provide the application prerequisite for resolution detail: calculate the selected row and invoke the existing semantic explainer using the same captured inputs, without retaining a prior request's service state or reloading data during explanation construction.

## Authoritative Source Documents / Sections

- Supplied `docs/ROADMAP.md` Phase 5: backend-authoritative resolution-tree rendering and JavaFX/web coexistence.
- `docs/TARGET_ARCHITECTURE.md` sections 13.1, 13.2, 13.3 and 13.5: explicit calculation inputs, fresh request-local lifetime, common row/tree inputs and migration constraints.
- `docs/CURRENT_ARCHITECTURE.md` sections 5.1, 5.2 and 5.12, "Single-craft resolution explanation": existing application boundaries and the remaining integration gap.
- `agent/stories/STORY-DOM-020-semantic-resolution-trace.md`: completed explainer, captured-input signatures, isolation guarantees and recorded limitations.
- `docs/KNOWN_PROBLEMS.md` CH-15 and resolved `agent/architect-requests/AR-003-resolution-root-identity.md`: TARGET_ARCHITECTURE section 13 owns the decided separation of requested identity from actual root sourcing; correcting CH-15 remains outside this application slice.

## Context

The semantic explainer exists but has no application caller. The detail contract requires a fresh calculation whose row and tree share loaded inputs; reading a previous table request's mutable service or loading inventory again would violate that contract. This story delivers the reusable application operation, not the HTTP schema or browser consumer. Resolved AR-003 establishes the root-identity contract in TARGET_ARCHITECTURE section 13; preserve the existing domain trace unchanged under that decision.

## Acceptance Criteria

1. Add application entry points for Profit and Discovery detail using explicit recipe identity and each flow's existing calculation inputs. Preserve coordinated Profit and individual Discovery scope, rating, settings and the separate nullable Discovery inventory-character semantics. Use established defaults/validation responsibilities; introduce no alternative business rules.
2. Each invocation loads a fresh calculation context and checks recipe membership against that operation's visible candidate set. Distinguish a recipe absent from that set from a visible row with an unavailable result and from an available blocked explanation. An empty Discovery operation cannot return an earlier operation's row, metadata or trace.
3. Capture the initial recipes, graph, inventory/binding pools, roster, quotes, settings and eligibility inputs needed by the existing calculations once per operation. Produce the normal selected-row summary and invoke `SingleCraftExplainer` from those same captured facts. The table simulation's exhausted inventory, budget or daily state must not become the explanation's starting state. No additional price, inventory or graph read occurs while constructing the explanation.
4. Preserve the explainer's semantic facts, nullable costs, repeated child occurrences and requested-versus-actually-selected recipe identities without relabelling or recomputing them, per TARGET_ARCHITECTURE section 13.3 and resolved AR-003. Enrich item names only from metadata captured for this operation; missing metadata remains absent. Do not resolve CH-15 here.
5. Keep all mutable calculation state local to the invocation and release it after success or failure. Successive and concurrent calls with different scopes/settings cannot contaminate one another. Do not add retained sessions, snapshot tokens, a task store or a claim of an atomic database-plus-graph snapshot.
6. Preserve existing table response contracts, economics and JavaFX in-process use. Ordinary table operations must not build semantic traces for all rows. Keep orchestration in the application layer and authoritative decisions in the existing domain; introduce no HTTP, JSON or JavaFX dependency into domain types.
7. Update the relevant CURRENT_ARCHITECTURE description and record actual evidence and limitations in Result. Explicitly retain the remaining HTTP mapping, execution-policy measurement, browser rendering and full-page performance work. Wire-contract conformance remains for the HTTP story.

## Required Tests

- Application tests using controlled loaders/adapters and the real explainer: assert row and tree receive identical captured facts and no second data read occurs during explanation; change a loader's subsequent answer to expose accidental reloads.
- Cover Profit coordinated and Discovery individual inputs, nullable inventory character, selected recipe outside the candidate set, available blocked versus unavailable results, and empty Discovery after a populated operation.
- Verify initial inventory/budget/daily state survives row evaluation for the explanation, repeated requests are independent, concurrent distinct scopes remain isolated and failure cannot leak state into a later request.
- Preserve split sourcing, null versus known-zero costs, special states and requested-versus-selected identity without reimplementing domain calculations in test expectations. Use existing domain fixtures where suitable.
- Run affected application/domain and HTTP contract regressions, the default backend suite, and targeted JavaFX compatibility checks for changed call boundaries. Record actual results and any unavailable checks. This is not browser timing or HTTP execution-policy evidence.

## Constraints

- Application orchestration and the minimum service seams needed for common captured inputs only; no browser, HTTP endpoint, DTO schema or synchronization change.
- No broad domain rewrite or correction of CH-15/CH-17. Preserve and expose current trace facts under TARGET_ARCHITECTURE section 13's resolved root contract.
- Avoid a generic snapshot framework or duplicated input-loading workflow. Reuse existing application loading and evaluation paths where compatible with request-local isolation.
- Do not change authoritative economic formulas, eligibility, simulation limits or table results to simplify explanation.

## Dependencies

- STORY-DOM-020 (DONE).
- Resolved AR-002 and AR-003 as incorporated in TARGET_ARCHITECTURE section 13; neither is an outstanding architecture blocker.

## Definition of Done

Both application flows can produce a selected row and existing semantic explanation from the same fresh captured inputs with isolation and compatibility evidence. Documentation records the application capability and remaining transport/browser work without claiming Phase 5 completion or redefining resolved AR-003.

## Result

**DONE — the application prerequisite only.** Both crafting flows can now produce a selected
recipe's row summary and the existing semantic explanation from one fresh, request-local set of
captured inputs. Nothing above the application layer exists: no detail route, no DTO, no wire
contract, no browser tree, no execution-policy or page-timing measurement, and no Phase 5 exit
criterion or milestone transition is claimed.

### What was implemented

**Application entry points** (AC 1, 2).
`CraftingProfitService.resolveDetail(recipeId, choice, settings)` and
`CraftingDiscoveryService.resolveDetail(recipeId, choice, settings, inventoryCharacterName)` take
explicit recipe identity plus each flow's *existing* calculation inputs. Profit keeps its
coordinated `DiscChoice` scope and `buildCoordinatedRoster(...)` roster; Discovery keeps its
individual scope, its rating ceiling, and its **separate nullable inventory character** with the
same unfiltered-pool fallback as `reload(...)`. No defaults, validation or business rules were
added — the loading steps are the ones `reload(...)` already performed, extracted verbatim.

**One fresh load per operation, membership checked against it** (AC 2, 3). Each service's
`reload(...)` was split into `loadCandidates(choice)` (visible set, graph recipes, allowed IDs) and
`loadCalculationInputs(...)` (quotes, item metadata, roster/inventory pools). `resolveDetail` runs
the same two steps into request-local records and never touches the `last*` fields. A recipe
outside the candidate set returns `Status.RECIPE_NOT_IN_CALCULATION` *before* any price, item or
inventory load. The split preserves `reload(...)`'s existing statement order exactly, including
Discovery's "no missing recipes" short-circuit before any inventory/price/item load and its
`lastAllowedRecipeIds = allowedRecipeIds` write before the inventory load.

**Row and tree from the same captured facts** (AC 3). The row comes from new
`CraftingPlanner.evaluateOne(...)`/`evaluateOneCoordinated(...)`, which are the table calculation
restricted to one recipe — same `PlannerContext`, same fresh `PlanState`, same
`evaluateOneRecipeNew` including its heuristic skip — and the tree from the existing
`SingleCraftExplainer` called with the *same* `CalculationInputs` value. Because each builds its
own `PlanState` from the captured initial inventory maps, the row's multi-craft simulation cannot
become the explanation's starting state, and no loader is called again while the explanation is
built.

**Result type** (AC 2, 4, 6). New `application.CraftingResolutionDetail`
(`recipeId`, `status`, `row`, `explanation`, `itemNames`) — calculation facts only, no JSON, HTTP
or JavaFX. `Status` is derived in the factories, never set by hand:
`RECIPE_NOT_IN_CALCULATION` / `AVAILABLE` (including a blocked explanation, which keeps its
reasons) / `RESULT_UNAVAILABLE` (`explanation.available() == false`). The explainer's facts are
passed through unchanged — no relabelling, no recomputation, no repair of requested-versus-selected
identity. `itemNames` is built only from the metadata that operation captured, keyed by traced item
ID; an item with no usable name stays absent rather than gaining `"Item <id>"`. CH-15 is untouched.

**Isolation is structural** (AC 5). Every mutable planning state lives inside one
planner/explainer call and is unreachable afterwards. There is no retained session, snapshot token,
TTL, task store, or claim of an atomic database-plus-graph snapshot, and `resolveDetail` neither
reads nor writes the reload-scoped caches, so a failure leaves nothing behind.

**Nothing existing changed** (AC 6). `reload(...)`, both HTTP table endpoints, `CraftResult`,
the row DTOs and the JavaFX lazy `RecipeTreeBuilder`/`Node` path are unchanged; a detail row carries
no legacy tree and no ordinary table operation builds a semantic trace. No domain type gained an
HTTP, JSON or JavaFX dependency.

### Evidence / actual verification

`./mvnw -o test` (default backend suite, no database): **Tests run: 344, Failures: 0, Errors: 0,
Skipped: 0 — BUILD SUCCESS**, up from 310. New:

- `craft.CraftingPlannerSingleRecipeTest` — **4/4**. `evaluateOne` equals
  `evaluateAll(...).get(id)` field by field across all four `useOwnMats`×`allowBuying`
  combinations with mixed sellable/bound inventory; `evaluateOneCoordinated` equals
  `evaluateAllCoordinated(...).get(id)` with a two-character roster and character-bound inventory;
  `allowedRecipeIds` restricts both identically; the caller's inventory map survives unchanged.
  No expectation computes economics by hand.
- `application.CraftingProfitResolutionDetailTest` — **13/13**. One load of each of the six
  loaders per operation; the explanation sees the full initial pool (2 units) while the row's
  simulation counted 2 crafts and left 1; a loader that answers differently from the second call
  onwards yields the *first* answer's explanation (record equality against a fixed-loader baseline)
  and a drained-loader baseline proves that assertion has teeth; scope drives both the candidate
  query and the roster; character-bound materials keep their owner and that owner is the assigned
  character; a `CHAR_DISCIPLINE` scope excluding that owner blocks; a recipe outside the visible
  set and a visible recipe absent from the graph both return `RECIPE_NOT_IN_CALCULATION` with no
  price/inventory load; item names only from captured metadata; A-B-A operations identical;
  **48 concurrent operations across two scopes/settings on one service all match their serial
  baselines**; a failed operation leaves the table cache intact and the next one succeeds;
  `resolveDetail` populates neither `getRawResultByRecipeId` nor `getResultByRecipeId` and builds
  no `Node` tree.
- `application.CraftingDiscoveryResolutionDetailTest` — **17/17**. The same isolation, single-read,
  captured-inventory, naming, A-B-A and 48-way concurrency checks on the individual path, plus:
  the row's `missingToBuyOne` equals the tree's bought quantities for the same first craft
  (`{200: 1}`, not vacuously empty); the rating ceiling excluding a recipe is
  `RECIPE_NOT_IN_CALCULATION`; **an empty Discovery operation after a populated reload returns no
  row, explanation or names**; a blocked explanation is `AVAILABLE` with its reasons while the same
  operation's row reports `craftableCount = 0` and `BUYING_DISABLED`; the domain's own
  `SingleCraftExplanation.unavailable(...)` maps to `RESULT_UNAVAILABLE` with no fabricated tree; a
  null inventory character falls back to the unfiltered pool; the inventory character is
  independent of the scope's crafting character.

Regressions: every pre-existing `craft.*`, `application.*`, `web.*` and `repo.*` suite passes
unchanged, including `application.CraftingProfitServiceTest` (8) and
`application.CraftingDiscoveryServiceTest` (9), which pin `reload(...)`'s load order,
short-circuit and cache behavior through the extraction, and the JavaFX-facing
`CraftingBlockedRowsTest`, `CraftingProfitViewScopeChangeTest`,
`CraftingProfitTotalProfitPresentationTest` and `CraftingProfitCoordinatedScopeTest`.
`CraftingProfitServiceRealDbPerfIT` and `CraftingProfitEquivalenceRealDbIT` were **not** run
(integration tests needing the real database).

Documentation: `docs/CURRENT_ARCHITECTURE.md` §2 (application package contents), §5.1 and §5.2 (one
paragraph each pointing at the new entry point), and §5.12, retitled and extended with the
application detail operation and a narrowed remaining-gap paragraph.

### Limitations and remaining uncertainty

- **Explicitly retained, not delivered:** the `POST /api/crafting/{profit,discovery}/resolution`
  routes, response DTOs and **wire-contract conformance** (§13.3's `consistency`, `calculatedAt`
  and echoed `calculation` are deliberately absent from the application type); the §13.5
  execution-policy measurement on representative real data; browser rendering; and the §33
  full-page performance gate. **No timing of any kind was measured** — an application test run is
  not execution-policy or browser evidence.
- **`RESULT_UNAVAILABLE` is currently unreachable through these flows.** `RecipeSimulator` records
  a blocked attempt whenever a craft fails, so the explainer always returns an available (possibly
  blocked) explanation. The state is required by §13.3 and is implemented and tested through the
  domain's own `SingleCraftExplanation.unavailable(...)` factory, but no fixture produces it from a
  real load. That is a fact about today's domain, not something this slice changed.
- **The row's heuristic skip and the explanation can disagree in one narrow case.** With
  `useOwnMats` and `allowBuying` both false, `evaluateOneRecipeNew`'s existing
  `shouldSimulateRecipe(...)` skip can return a row with no simulation while the explainer still
  produces a blocked tree with reasons. Both behaviors are pre-existing and preserved deliberately
  (AC 4 and AC 6); §13.3 already allows a blocked explanation alongside a row with no completed
  craft.
- **`reload(...)`'s failure-path field ordering was preserved deliberately**, including Discovery's
  write of `lastAllowedRecipeIds` before the inventory load. That pre-existing oddity was not
  "fixed" under this story.
- **CH-15 shows through unchanged**, as STORY-DOM-020 recorded: the root explains resolution of the
  selected recipe's *output*, so owned finished stock or another producing recipe can satisfy it.
  The requested identity stays on the envelope and the actual one on the node, per resolved AR-003.
  Correcting CH-15 remains out of scope.
- **Fixtures only.** No real-database comparison of a detail operation against a table row was run,
  and no measurement of what a full-graph detail load costs against real data — that is the HTTP
  story's execution-policy work.

## Blockers

None.
