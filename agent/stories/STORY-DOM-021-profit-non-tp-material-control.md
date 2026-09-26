## Story ID

STORY-DOM-021

## Title

Implement the decided Profit non-Trading-Post material calculation option

## Status

DONE

## Milestone

milestone-05

## Goal

Implement DOMAIN_SPEC section 2.1.1's enabled and disabled material-path rule consistently through web Profit table calculation and fresh resolution detail, with an explicit calculation control.

## Authoritative Source Documents / Sections

- docs/DOMAIN_SPEC.md sections 2.1.1, 11.1-11.2, 21 and 42.
- agent/user-decisions/UD-009-non-trading-post-material-control.md and UD-010-non-tp-control-disabled-behavior.md, resolved Product Owner answers.
- Supplied docs/ROADMAP.md Phase 5 backend-authoritative controls and special-state presentation.
- docs/TARGET_ARCHITECTURE.md sections 12-14, 26 and 35; docs/FRONTEND_UX_GUIDELINES.md calculation controls and contextual state presentation.
- agent/stories/STORY-APP-012-request-local-resolution-detail.md, STORY-API-008-crafting-resolution-endpoints.md and STORY-WEB-008-profit-economic-columns.md.

## Context

The formerly missing disabled behavior is now explicitly decided. This is a web Profit rule, not a display filter and not a Discovery/JavaFX change. Existing table and detail paths share the authoritative resolver; implement the same policy there without a second algorithm. Domain classification facts must come through existing backend boundaries, independently of quote availability or icon metadata.

## Acceptance Criteria

1. Record the existing classification/settings flow before changing it, then implement section 2.1.1's decided rule in the authoritative domain path. Disabled rejects paths consuming non-TP materials even when owned, bound or recursively craftable; valid alternatives remain eligible. Enabled permits usable owned/craftable non-TP paths under existing restrictions and never invents external acquisition for an unsatisfied non-TP requirement.
2. Carry nontradeability as domain facts through existing adapters; never infer classification from a missing/zero quote, missing icon or unavailable image. Preserve PRICE_UNAVAILABLE for normally tradeable items. Do not invent unknown classification behavior if existing authoritative inputs cannot establish it; report the concrete gap through the normal blocker workflow.
3. Pass the option explicitly through web Profit application requests to both table simulation and fresh-detail evaluation. Both use the same setting and captured inputs; defaults open enabled. Preserve old caller behavior for JavaFX and Discovery and keep existing constructors/call contracts compatible where needed. No frontend-only exclusion or post-calculation row deletion.
4. Extend Profit request validation and effective echoes consistently for table and resolution. Ensure detail association includes the option so late results from the previous value cannot appear under the current setting. Keep domain types free of HTTP, JSON, persistence and JavaFX dependencies.
5. Add Allow non-Trading-Post materials to calculation/material controls, with concise help conveying the decided rule. Toggling recalculates Profit through the API; it is not a result-display filter. Preserve valid controls on refresh and distinguish it from the three display filters.
6. Retain blocked results and explain the material restriction from backend facts in selected details and affected tree nodes where supplied. Preserve ordinary domain explanations and the temporary cycle diagnostic; do not restore a general State column. Use an explicit backend restriction representation if existing reasons cannot truthfully distinguish the policy; do not overload a misleading reason or infer it in Vue.
7. Preserve binding, scope, buying, budget, daily, valuation and simulation-cap behavior outside the new policy. Alternative-path exploration must retain rollback/inventory isolation. Update affected contract documentation, CURRENT_ARCHITECTURE and the human-readable crafting guide under section 35 with actual behavior, without claiming milestone completion.

## Required Tests

- Focused domain cases: owned unbound and bound non-TP ingredients; recursive intermediates; an unsatisfied non-TP requirement; a valid alternative that avoids non-TP consumption; rejected-path rollback; both setting values. Assert existing ownership/scope/budget restrictions still apply.
- Normally tradeable missing-price cases stay PRICE_UNAVAILABLE; pure tradeable paths remain equivalent. Characterize unchanged Discovery and JavaFX/default-caller results rather than applying the new policy globally.
- Application/HTTP tests for default and explicit values, validation, effective echo, request isolation, and table/detail agreement on the rule. Verify rejected results remain represented with truthful explanations.
- Browser tests for grouping, default, recalculation, refresh preservation, option-sensitive stale-detail rejection and contextual reasons; verify no client calculation. A read-only live check may establish integration but must distinguish absent live non-TP cases from controlled evidence.
- Affected domain/application/API/frontend checks, frontend type checking and build; no speculative broad test expansion. Record performance impact evidence for the final Phase 5 gate without substituting backend timings for full-page acceptance.

## Constraints

- Do not modify the active API-009 story or redesign icon delivery. No speculative metadata sync or third-party acquisition system.
- No change to Discovery or JavaFX behavior and no restriction inferred from price availability. Preserve the shared backend boundary and existing detail consistency contract.
- Routine internal type/field naming belongs to implementation; unresolved product or architecture choices must be reported rather than guessed.

## Dependencies

UD-009 and UD-010 are RESOLVED and recorded in DOMAIN_SPEC section 2.1.1. STORY-APP-012, STORY-API-008 and STORY-WEB-008 are DONE.

## Definition of Done

The decided web Profit control is implemented end to end with authoritative path enforcement, table/detail consistency, unchanged other callers, meaningful tests and updated documentation. Remaining performance/review gates stay open.

## Result

DONE. An interrupted attempt was **resumed, not restarted**: the domain/application/web/frontend
implementation and its two new Java suites were already on disk, so this session verified them
against the acceptance criteria, fixed the two things that did not actually work, added the coverage
that was missing (the repository integration test, two detail-association tests, the browser steps),
recorded the performance evidence and wrote the documentation. No Phase 5 exit criterion is claimed.

### AC 1 — the flow as it was before the change, then the decided rule

Recorded by reading the pre-change code, not inferred:

- `craft.CraftingSettings` carried exactly six fields (`useOwnMats`, `allowBuying`, `maxBuyCopper`,
  `listingSell`, `listingBuy`, `dailyBuyInsteadOfCraft`). **No domain type carried item tradeability
  at all**, so there was no classification to switch on.
- `PlannerContext` held `recipesByOutput`, `tp`, `settings`, `allowedRecipeIds`, `coordinatedRoster`
  and the two memo tables; `CraftingProfitService` loaded quotes (`TpPriceRepository`) and item
  metadata (`ItemRepository`) and handed them to `CraftingPlanner.evaluateAllCoordinated(...)`.
- The only tradeability-shaped facts in the domain were **valuation** ones and are unchanged by this
  story: `CraftingResolver` marks `ResolutionState.UNVALUED_NONTRADEABLE` when owned *sellable*
  quantity was consumed at a sell unit of `<= 0` (§11.2 — "valued at zero, not free"), which **is**
  derived from the quote. It is a valuation note, never a policy input, and this story neither reads
  nor changes it.
- `tp_tradeable_items` existed and was read only by `sync.tp.relevance.*ItemCollector` to choose
  which items to price. Nothing in `craft.*`, `application.*` or `web.*` consulted it.

The decided rule now lives in the authoritative resolver: `CraftingSettings.allowNonTradeableMaterials`
(the policy) plus `craft.MaterialTradeability` (the classification), asked through
`PlannerContext.excludesNonTradeableMaterial(itemId)`. With the option off, `CraftingResolver.tryCraft`
blocks a forbidden ingredient **before** any owned (unbound, account-bound or soulbound) quantity is
taken and before a crafting path for it is attempted, so the path is rejected and rolled back rather
than repriced; §30's candidate comparison first considers only candidates consuming no such material,
so a valid alternative wins instead of losing on cost, and when none exists the consuming candidate is
still attempted so the explanation names the material. With it on, nothing is restricted: an owned or
craftable non-TP material is used under the ordinary rules and an unsatisfiable requirement stays
unavailable (`PRICE_UNAVAILABLE`), with no invented external acquisition.

### AC 2 — classification as a fact, through the existing boundary

`repo.tp.TpTradeableItemRepository` maps `tp_tradeable_items` — the Trading Post's own tradeable-item
listing, rewritten by global synchronization — into `craft.MaterialTradeability`. It consults no
quote, icon or image, and `craft.*` never sees JDBC. `MaterialTradeability.noneKnown()` asserts
nothing, so a caller supplying no classification is unrestricted. Proven by test: a classified item
**with a full quote** is still restricted, and an unclassified item **with no quote** is still
`PRICE_UNAVAILABLE`.

One concrete gap is recorded rather than guessed around, in `KNOWN_PROBLEMS.md` §7.12: because an
item absent from the listing is non-Trading-Post, a never-synchronized or half-rewritten
`tp_tradeable_items` classifies *everything* as non-Trading-Post. That is what the stored data says
and it surfaces as visibly blocked results, but the staleness of the classification itself is not
reported to the user. The enabled default is unaffected — no query is issued at all.

### AC 3/AC 4 — one setting, both routes, old callers untouched

`SettingsDto`/`EffectiveSettingsDto` gained `allowNonTradeableMaterials` with the same
omitted-means-default and echo handling as every other member; the default is **enabled**, so an empty
body calculates exactly as before. `CraftingProfitService` captures the classification into the same
`CalculationInputs` record both `reload(...)` and `resolveDetail(...)` build their result from, and
keeps it in `lastTradeability` for the lazy tree build, so table and fresh detail apply the same rule
to the same facts. The resolution route takes and echoes the field, and `calculationKey` includes it,
so an answer produced under the previous value is refused instead of shown. Old contracts are kept:
the six-argument `CraftingSettings` constructor (enabled), the eight-argument planner/explainer
entry points (`noneKnown()`), and a `CraftingProfitService` constructor without the new adapter —
JavaFX Profit, JavaFX Discovery and HTTP Discovery are unchanged, and their results are characterized
by test. No domain type imports HTTP, JSON, JDBC or JavaFX.

### AC 5/AC 6 — the control and the explanation

"Allow non-Trading-Post materials" is a checkbox in the *Calculation* fieldset (not the *Displayed
results* subgroup) with a help sentence bound by `aria-describedby` stating both values' behavior.
Toggling emits the whole echoed settings object, so one calculation request goes out and the returned
rows are what is listed — nothing is hidden, dropped or classified in the browser, and the three
display filters are untouched by it either way. The effective-settings summary words the rule in
force, and it survives an explicit reload. `craft.BlockedReason.NON_TRADEABLE_MATERIAL` is the
explicit backend representation (no existing reason could distinguish a chosen restriction from a
missing price or a missing recipe); it is detail-only, worded in `rowState.ts` and
`resolutionPresentation.ts`, rendered at the affected tree node, and deliberately **not** a row tag —
no State column returns, and the temporary `CYCLE_DETECTED` diagnostic is untouched.

### AC 7 — unchanged elsewhere, documentation

Binding, scope, buying, budget, daily, valuation and the §28 simulation cap are outside the new
policy and covered by re-run tests, including a budget-restricted tradeable path that still reports
`INSUFFICIENT_BUDGET` while the option is off. Alternative-path exploration keeps its
rollback/isolation: a rejected path leaves the inventory pools, `missingToBuy` and buy cost exactly as
they were. Documentation updated: `DOMAIN_SPEC.md` §42 (the new reason and what it is *not*),
`CURRENT_ARCHITECTURE.md` §2/§4/§5.1/§5.5/§5.11, `TARGET_ARCHITECTURE.md` §13.1's settings list,
`KNOWN_PROBLEMS.md` §7.12, and the §35 guide (`docs/crafting/README.md` new section plus the status
table, `GLOSSARY.md` term and FAQ entry).

### Two defects found in the interrupted work, and fixed

1. `application.CraftingProfitServiceTest`'s recording planner still overrode the **eight**-argument
   `evaluateAllCoordinated`, which the service no longer calls — it captured nothing and five tests
   failed with NPEs. It now overrides the classification-aware entry point and also captures the
   classification.
2. The new frontend test looked for `[data-test="resolution-node"]`, which does not exist (the hook is
   `tree-node`), and matched node text that a parent also contains — so it would have passed on the
   root's own reason. It now matches each node by its own `node-name` and asserts the tradeable
   sibling in the same craft carries no restriction.

### Tests run

| Command | Result |
| --- | --- |
| `./mvnw -o test -Dtest='craft.*Test'` | **82 passed**, 0 failures (incl. 14 new `CraftingResolverNonTradeableMaterialTest`) |
| `./mvnw -o test -Dtest='web.*Test,application.*Test,repo.tp.*Test'` | **all green**, incl. 4 new `CraftingProfitNonTradeableMaterialFlowTest`, 5 new `TpTradeableItemRepositoryTest`, 22 `CraftingProfitApiControllerTest`, 28 `CraftingProfitResolutionApiControllerTest`, 8 `CraftingProfitServiceTest` |
| `npx vitest run` (frontend) | **215 passed**, 15 files |
| `npm run type-check` / `npm run build` | clean / built |
| `npm run smoke:profit` | **PASSED, 30 steps** (real Chrome, stub origin) |
| `npm run smoke:layout` | **PASSED, 11 steps**, 20 contrast samples |
| `./mvnw -o test -Dtest=CraftingProfitApiRealDbPerfIT` | 2 tests, real database — see below |

Performance evidence (real database, All scope, backend request time only): the default (option
enabled) measured 1980 ms cold / 795–895 ms warm, 3176 rows, 1,725,390 bytes; with the option off
1054 ms cold / 707–920 ms warm, 3176 rows, 1,719,875 bytes. The extra classification query is one
indexed `item_id = ANY(?)` read and does not move the request out of its existing range; the
differing response size on identical rows is the restriction actually applying to live data. This is
**backend request time, not §33 full-page acceptance**, and the Phase 5 page-load gate stays open.

### Remaining uncertainty and limits

- The live figures above are the only real-data evidence; the browser check runs against a stub
  origin, so its evidence is structural and interactional, never real data or performance. No live
  read-only check isolated a *named* non-Trading-Post recipe — the controlled tests own that rule.
- `KNOWN_PROBLEMS.md` §7.12's stale/empty-classification case is recorded, not fixed.
- `ResolutionState.UNVALUED_NONTRADEABLE` still derives from a `<= 0` sell unit (§11.2). It is
  untouched by this story, but it shares the word "nontradeable" with the new classification while
  meaning something else.
- TestFX/JavaFX verification was not run: no JavaFX file changed, and JavaFX keeps the enabled default
  by construction.

## Blockers

None.
