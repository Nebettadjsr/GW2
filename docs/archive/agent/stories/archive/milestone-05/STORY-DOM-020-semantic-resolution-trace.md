## Story ID

STORY-DOM-020

## Title

Produce a semantic single-craft trace from authoritative resolution

## Status

DONE

## Milestone

milestone-05

## Goal

Provide the domain-level explanation needed by the decided resolution-detail contract, recording actual resolution choices for one craft without introducing a second resolver or changing existing calculation results.

## Authoritative Source Documents / Sections

- Supplied `docs/ROADMAP.md` Phase 5: resolution-tree rendering and special domain states using backend-provided values.
- `docs/TARGET_ARCHITECTURE.md` sections 13.2, 13.3 and 13.5: captured calculation inputs, semantic trace, single-craft basis, domain independence and verification constraints; established by resolved AR-002.
- `docs/DOMAIN_SPEC.md` sections 10, 11, 42, 44 and 48: inventory consumption, valuation/binding, blocked reasons, explanation and invariants.
- `docs/CURRENT_ARCHITECTURE.md` sections 5.1 and 5.2: existing resolver/state journal, coordinated Profit versus individual Discovery, and lazy legacy tree construction.

## Context

Phase 5 requires browser resolution details. The decided contract explicitly says that legacy Node.action text and first-recipe dependency expansion are insufficient evidence of actual resolution. Its domain facts must come from authoritative evaluation before an HTTP mapper or browser can consume them. This story implements that prerequisite only; the contract remains owned by TARGET_ARCHITECTURE section 13.

## Acceptance Criteria

1. Add a domain entry point and domain-owned trace representation for explaining one execution of an explicitly selected recipe from supplied initial calculation inputs. The root requests that recipe's output quantity. Support both the coordinated Profit and individual Discovery calculation paths using their existing eligibility, binding and valuation rules. Do not explain the next craft after exhausting a multi-craft simulation or multiply a recipe skeleton.
2. Obtain trace facts from the authoritative resolver's actual decisions. Expose the semantic facts required by section 13.3: item/recipe identity, requested and sourced quantities, craft count and production, assigned character, acquisition methods, domain states, blocked reasons, costs and ordered child occurrences. Item-name enrichment and transport envelopes are outside this domain slice. Representation and local class names remain implementation choices.
3. Respect section 13.3 quantity and cost semantics, including mixed sourcing, surplus production, inclusive ancestor costs, null complete costs for blocked requirements, known zero versus unknown value, and explicit UNVALUED_NONTRADEABLE. Preserve numeric precision. Do not infer missing states from display strings or raw quotes outside authoritative domain evaluation.
4. Speculative alternatives and rollback must not leak losing-path quantities, costs, inventory consumption or trace children into the accepted explanation. Retain the selected blocked attempt's explanatory facts when appropriate under section 13.3. Cycles terminate with a finite blocked node; repeated items in distinct branches remain distinct occurrences.
5. Explanation uses the supplied captured inputs without performing database, graph-cache or price reloads. It must not mutate the caller's initial inventory, budget or daily state, contaminate a subsequent explanation, or reuse mutable state from another calculation. A blocked first craft yields an explanation with reasons; absence of a calculation result remains distinguishable from an available blocked tree.
6. Keep trace production opt-in for selected-recipe detail. Existing table calculations must not eagerly construct semantic trees for every row. Preserve existing economic results and JavaFX/table contracts; no HTTP, JSON, JavaFX, persistence or external API dependency enters domain types. Do not replace the legacy JavaFX tree as part of this prerequisite.
7. Update CURRENT_ARCHITECTURE at the relevant owner to describe the implemented trace capability and remaining integration gap. Record evidence, actual verification and limitations in Result. Do not claim that detail endpoints, a browser tree, the full-page performance gate or Phase 5 completion are delivered.

## Required Tests

- Database-independent domain tests exercising the real resolver with controlled recipe, inventory and quote inputs: split inventory/craft/buy sourcing; batch surplus; repeated-item branches; allowed-recipe alternatives and losing-path rollback; coordinated character assignment and bound inventory; individual-character resolution; buying/budget restrictions; daily limits; missing prices; unvalued non-tradable inputs; cycles; blocked versus absent results.
- Assert semantic facts and quantity conservation, inclusive costs without double-counting, null versus zero and independent initial state. Include consecutive explanations with differing scopes/settings and a blocked attempt followed by a successful one to expose state leakage.
- Compare traced and ordinary evaluation on the same fixtures for preserved economic outcomes and final accepted state. Verify that ordinary table evaluation does not activate trace construction.
- Run the existing domain/application regressions affected by resolver or state changes and the default backend test suite. Use targeted compatibility checks for any touched JavaFX-facing boundary. Record actual results; a domain test run is not browser performance evidence.

## Constraints

- Implement the established AR-002 decision; do not redesign its contract or create competing business rules.
- Keep this slice inside domain trace production and the minimum integration seams needed to invoke it with captured inputs. Application request orchestration, HTTP routes, response DTOs, execution-policy measurements and browser rendering belong to subsequent Phase 5 work.
- No new calculation session, task store, persisted snapshot, synchronization behavior or database mutation.
- No broad resolver rewrite, speculative optimization or unrelated cleanup. Preserve existing domain behavior; report a genuine rule conflict rather than inventing a product decision.
- Preserve the active STORY-WEB-003 work. No frontend changes are required.

## Dependencies

Resolved AR-002, as incorporated in `docs/TARGET_ARCHITECTURE.md` section 13. No dependency on the unfinished STORY-WEB-003.

## Definition of Done

The authoritative domain resolver can produce the selected recipe's semantic single-craft explanation, meaningful semantic and compatibility tests pass, and documentation and Result distinguish the completed prerequisite from the remaining HTTP/browser integration. No milestone transition is declared.

## Result

**DONE — the domain prerequisite only.** The authoritative resolver can now produce a semantic
single-craft explanation of an explicitly selected recipe. Nothing is wired to a caller: there is no
detail endpoint, no DTO, no browser tree, no execution-policy or page-timing measurement, and no
Phase 5 exit criterion is claimed.

### What was implemented

**Entry point — `craft.SingleCraftExplainer`** (new). `explainIndividual(...)` and
`explainCoordinated(...)` take exactly the captured inputs the table calculations already take
(`CraftingPlanner.evaluateAll(...)`/`evaluateAllCoordinated(...)`: recipes, sellable/account-bound/
character-bound inventory, roster, quotes, settings, allowed recipe IDs) and return a
`SingleCraftExplanation`. Each call builds its own `PlannerContext` and `PlanState` from those
inputs — it opens no database connection, touches no graph cache, reloads no price, and reuses no
object from another calculation.

**Representation** (new, all in `craft`, no JSON/HTTP/JavaFX/persistence dependency):
`SingleCraftExplanation` (requested recipe identity, `available()`, nullable root),
`CraftTraceNode` (item, requested/inventory/crafted/bought/missing quantities, selected recipe,
craft count, produced quantity, assigned character, methods, states, blocked reasons, three nullable
inclusive costs, ordered children), `AcquisitionMethod`, `ResolutionState`. No item names, no
transport envelope, no presentation field.

**Facts come from the resolver's own decisions.** `CraftingResolver` gained a tracing mode
(`new CraftingResolver(true)`); when on, each resolved requirement records a
`ResolvedNeed.NodeTrace` — selected recipe, executions performed and produced, assigned character,
zero-valued owned consumption, and the craft attempt explaining the node. Nothing is re-derived from
`Node.action` text, a first-recipe expansion, raw quotes or a recipe skeleton.

**First craft, from the real phase order.** `RecipeSimulator` gained a package-private constructor
(resolver + "stop after the first accepted craft") and retention of the attempt that ended a phase
without acceptance (`RecipeSimulationResult.getBlockedAttempt()`, set and cleared with
`blockedReason`). The explanation is the simulation's first craft — never the craft after an
exhausted simulation, never a multiplied skeleton — including the zero-cash phase-1 pass when
`useOwnMats` and `allowBuying` are both on.

**Speculation cannot leak.** The records hang off the `ResolvedNeed`s themselves, so a craft that
loses to buying or to another eligible character is unreachable from the accepted result, together
with its quantities, costs, inventory consumption and children. Verified by two tests that assert
the winning sibling still holds the rolled-back inventory. A craft that was *attempted and blocked*
stays reachable as trace-only children, so a blocked requirement still explains itself; that link
does not change `ResolvedNeed.getChildren()`, so the legacy JavaFX tree is byte-for-byte unaffected.

**Cost and quantity semantics.** Costs are the resolver's existing inclusive values (an ancestor
already contains its descendants), integers throughout, and `null` exactly when the requirement is
not fully satisfied — so a blocked requirement has no misleading partial total while a
domain-established zero stays `0`. A missing purchase price yields `PRICE_UNAVAILABLE` and `null`,
never a zero cost. `UNVALUED_NONTRADEABLE` is raised where the resolver actually consumed sellable
owned quantity at a zero unit value; bound quantity's deliberate opportunity-cost exemption is not
marked. A budget-rejected craft keeps its established cost and reports `BLOCKED` +
`INSUFFICIENT_BUDGET`.

**Opt-in.** Table calculations construct their resolvers without tracing, so their `ResolvedNeed`s
carry no trace at all and no semantic tree is built per row; the "stop after the first craft" flag is
false for them, leaving the 250-batch loop and both phases exactly as before.

### Evidence / actual verification

`./mvnw test` (the default backend suite, no database): **Tests run: 310, Failures: 0, Errors: 0,
Skipped: 0 — BUILD SUCCESS**, up from 286 before this story. New:

- `craft.SingleCraftExplainerSemanticsTest` — **14/14**: split inventory/buy sourcing with
  non-double-counted inclusive costs; batch surplus (2 executions, 4 produced, 3 used, surplus 1);
  the same raw item in two branches as two occurrences consuming 1 and 2 of 3 owned units; craft-vs-buy
  rollback; a losing coordinated character attempt; the assigned character of a coordinated craft;
  soulbound consumption at zero opportunity cost; `RECIPE_NOT_ALLOWED`; a cycle terminating in a
  finite `CYCLE_DETECTED` node; `PRICE_UNAVAILABLE` with `null` (not zero) cost;
  `UNVALUED_NONTRADEABLE` at a known zero; bound quantity *not* marked unvalued; a daily limit;
  a budget rejection; blocked tree versus absent result. Quantity conservation
  (`requested == inventory + crafted + bought + missing`) asserted recursively on six trees.
- `craft.SingleCraftExplainerIsolationTest` — **5/5**: supplied sellable/account-bound/character-bound
  maps unmutated; identical repeated requests identical; a daily allowance not carried into the next
  explanation; a blocked explanation followed by a successful one; three consecutive explanations with
  different settings and scopes on one explainer, then the first request repeated and equal to itself.
- `craft.ResolutionTracePreservesCalculationTest` — **5/5**: an ordinary `RecipeSimulator` run leaves
  `getTrace() == null` on every node of `firstCraft`/`lastCraft`/`blockedAttempt` (this is the
  "no tree per row" check); traced and ordinary simulations agree on craft count, buy cost,
  opportunity cost, missing map and blocked reason across all four `useOwnMats`×`allowBuying`
  combinations; traced and ordinary `resolveOneCraft` leave identical final `PlanState` inventory,
  bound inventory, missing map and buy cost; the multi-craft table result (2 crafts,
  `BUYING_DISABLED`, 36 opportunity cost) is unchanged while the explanation stops at 1 craft;
  `CraftingPlanner.evaluateAll(...)` still returns `craftableCount=2`, `buyCostCopper=0`,
  `matsSellValueCopper=18` and a `null` tree.

Touched-boundary compatibility: the existing `craft.*` regressions (resolver bound-material,
coordinated-character, craft-vs-buy, budget, multiple-recipe, price-unavailable, recipe-not-allowed,
plan-state journal, heuristic-skip characterization) and the JavaFX-facing
`CraftingBlockedRowsTest`, `CraftingProfitViewScopeChangeTest`,
`CraftingProfitTotalProfitPresentationTest`, `CraftingProfitCoordinatedScopeTest` plus every
`application.*` and `web.*` suite all pass unchanged. `CraftingProfitEquivalenceRealDbIT` was **not**
run (integration test, needs the real database).

Documentation: `CURRENT_ARCHITECTURE.md` §2 (package contents) and a new §5.12 describing the
capability and the remaining integration gap.

### Limitations and remaining uncertainty

- **Not delivered, not claimed:** the `POST /api/crafting/{profit,discovery}/resolution` routes,
  response DTOs, application orchestration, item-name enrichment, the browser tree, the §33 full-page
  performance gate, any execution-policy measurement, and Phase 5 completion. No milestone transition
  is declared.
- **A domain test run is not browser performance evidence.** No timing of any kind was measured.
- **`KNOWN_PROBLEMS.md` CH-15 shows through.** `resolveOneCraft` resolves the selected recipe's
  *output*, so owned finished stock, or a different recipe for the same output, can satisfy the root.
  The explanation reports that faithfully — the requested recipe is on the explanation, the actually
  selected one on the node — rather than hiding it. Fixing it is that defect's scope, not this story's.
- **`UNVALUED_NONTRADEABLE` surfaces the valuation the domain performs today.** The recursive→vendor
  fallback of `DOMAIN_SPEC.md` §11.2 remains unimplemented (`KNOWN_PROBLEMS.md` CH-17); this story
  exposes the zero the resolver already assigns with its explicit state and invents no value.
- **One reading recorded rather than assumed:** a craft rejected only for budget keeps its
  established cost (its resolution *was* complete) instead of reporting `null`; `null` is used
  strictly for a requirement that is not fully satisfied.
- Verified against hand-built fixtures only; no real-database comparison of an explanation against a
  table row was run.

## Blockers

None.
