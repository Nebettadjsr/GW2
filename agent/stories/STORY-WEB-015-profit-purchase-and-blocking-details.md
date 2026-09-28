## Story ID

STORY-WEB-015

## Title

Simplify Profit purchase details and identify concrete blocking materials

## Status

DONE

## Milestone

milestone-05

## Goal

Make selected Profit details show the purchases needed for counted crafts and concrete blocking causes without redundant labels or introductory prose.

## Authoritative Source Documents / Sections

- docs/DOMAIN_SPEC.md section 2.1.1: purchase basis, useful blocking information and concise presentation.
- docs/TARGET_ARCHITECTURE.md sections 12-13: backend authority and truthful fresh resolution.
- Supplied docs/ROADMAP.md Phase 5: Profit resolution and special states.
- agent/stories/STORY-WEB-007-profit-resolution-detail-view.md, Result: distinct material-list bases and node reasons.
- agent/stories/STORY-WEB-011-profit-controls-text-cleanup.md: existing presentation scope.
- agent/product-owner-requests/Request-010-polish-craft-profit.md.
- docs/FRONTEND_UX_GUIDELINES.md applicable detail, state and accessibility guidance; docs/TEST_STRATEGY.md sections 12 and 36.

## Context

The completed detail story explicitly retained two purchase-list bases and generic node labels. Request-010 now narrows the visible purchase list to counted crafts and requires item-specific missing-price explanations. This is incremental product presentation work, not another resolution model. WEB-014 handles reported integration failures; DOM-023 owns fee integration.

## Acceptance Criteria

1. When buying is enabled, present backend-provided materials still to buy for the already calculated craft count, including item identity, quantity and relevant supplied price information. Remove the separate FOR ONE FURTHER CRAFT section. Do not substitute the fresh single-output tree or its costs for counted-craft purchases, aggregate inclusive node costs or invent missing totals.
2. Review selected-detail labels and remove redundant Buying is off, Over the buy limit and Not blocked labels wherever their information is already clear. Keep concrete useful causes and distinguish limits on further crafting from invalidation of counted crafts. Show the affected required purchase and supplied cost/budget facts for budget blocks where available; do not manufacture amounts.
3. Missing-price explanations identify the affected item by backend name or item ID beside the relevant explanation/node. Trace existing authoritative item/reason information through the existing application/HTTP/presentation boundaries if it is lost. Never guess the item from a generic root reason or imply that a fresh tree proves the cause of an earlier counted-craft stop. Preserve truthful calculation bases and report any unmet context requirement explicitly rather than claim completion.
4. Remove the exact introductory sentence identified in DOMAIN_SPEC section 2.1.1. Retain accessible controls, meaningful errors, special/unavailable states, null-versus-zero distinctions and the existing temporary cycle diagnostic.
5. Reuse existing detail/tree components and response facts. Preserve valid selection, response association, gross values and non-TP control behavior. Shared changes preserve Discovery presentation and JavaFX behavior. Record delivered behavior and targeted evidence in Result and update affected implementation documentation.

## Required Tests

- Targeted component fixtures for counted-craft purchases with quantities/prices, empty and unavailable lists, buying disabled and removal of the extra section and introductory sentence.
- Targeted rendering checks for completed crafts with a subsequent budget/price limit, nested affected-item identity including name fallback to item ID, unknown/unavailable states, and absence of redundant success/status labels while useful causes remain.
- If application/transport changes are needed, focused projection tests prove the existing backend item/reason facts survive unchanged without new calculation semantics.
- Explicit local browser smoke for selected details at wide/narrow widths, keyboard access and item-specific price/budget explanations; use controlled fixtures for rare states and identify their limits. Confirm normal live-backend selection still displays its tree.
- Directly affected tests locally; complete suites remain the GitHub CI gate under TEST_STRATEGY section 36.

## Constraints

- No new domain calculation, independent browser economics, resolution-basis change, arbitrary cleanup or speculative diagnostics.
- Do not change the active WEB-012 story or CURRENT_STORY.md. No broad regression mandate or Phase 5 closure claim.

## Dependencies

Completed STORY-WEB-007, STORY-WEB-008, STORY-WEB-011 and STORY-WEB-014. WEB-014's
runtime-contract symptoms are closed and are not reopened here; this story remains the
executable work for the presentation requirements that Request-011 confirms are still open.
Presentation work has no unresolved decision prerequisite.

## Definition of Done

Counted-craft purchase information and item-specific blocking causes are understandable and verified, redundant content is removed without losing useful facts, evidence and limitations are recorded, and the story revision passes the CI gate.

## Result

**DONE. Frontend presentation only — no backend, DTO, route or domain change was needed or made.**

### AC 1 — purchases are the counted crafts' only

`SelectedResultDetail.vue` renders one purchase list, `row.missingToBuy`, under the basis heading the
contract gives it ("For all N crafts counted"). The `FOR ONE FURTHER CRAFT` section is gone: heading,
list, item lines and its three-way empty state. `missingToBuyOne` remains untouched in
`CraftingRowDto`, `types.ts` and the responses; it is simply not read by this component, and
`SelectedDiscoveryDetail.vue` still displays its own lists unchanged. Nothing was substituted for the
removed list — no fresh-tree node, no inclusive node cost, no invented total. Each line is still the
supplied quantity plus that material's own supplied quote, with "No price supplied" kept distinct from
a zero, and the unsupplied / empty / nothing-to-buy answers stay three different messages.

### AC 2 — redundant labels removed, causes kept

`rowState.describeRowState` gained one additive field, `labelAddsMeaning`, and the Profit detail shows
the short status chip only when it is true. It is false for every reason this client has wording for —
including DOMAIN_SPEC §2.1.1's three named examples, "Buying is off", "Over the buy limit" and "Not
blocked" — because the sentence underneath states the same cause in full. It is true for the three
states whose sentence is all there is: no calculated result, no reported state, and a code this client
cannot word; those keep their chip and their `status--unknown` treatment, so removing a marker can
never read as success. The label itself is still produced, so the search index
(`useProfitTableView`/`useDiscoveryTableView`) and Discovery's own detail are unaffected.

The same redundancy existed once more inside the shared resolution region, where the fresh row's chip
stands alone with no sentence: there the *success* chip is dropped and every blocked, none-craftable,
unavailable and unrecognized one is kept, because dropping those would delete the only statement of
them.

The further-crafting distinction is unchanged and asserted: a blocked row with counted crafts still
reads "Further crafting is blocked because … The N crafts already counted stay valid." For a budget
block the detail states the backend's echoed maximum buy (and nothing when no settings were echoed),
beside the supplied `buyCostCopper` for the counted crafts and the counted-craft purchase list. No
amount is computed, scaled or filled in.

### AC 3 — the affected item, where the backend supplies one

`resolutionPresentation.ts` now passes each node's own `nodeLabel` into its state and blocked-reason
wording, so a missing price reads "No purchase price is available for Pile of Dust" / "Blocked because
no price is available for Item #502" at the requirement it arrived on, at any depth, falling back to
the item id when the backend supplied no name. The item is never taken from an ancestor, a sibling or
the requested recipe.

**Unmet context requirement, stated rather than worked around:** a *row's* `blockedReason` is a single
`craft.BlockedReason` value and `craft.CraftResult` carries no item beside it, so there is no
authoritative row-level item to trace. `web.CraftingResolutionMapper` and `ResolutionNodeDto` were
read and already carry each node's `itemId`, `itemName`, `states` and `blockedReasons` intact, so no
application/HTTP projection change was required. Rather than guess the item from the generic root
reason or borrow it from the fresh single-batch tree, the detail says plainly, for a missing price and
for a budget limit, that this result names no item for it and points at the purchase lines and
requirements that do.

### AC 4 — introductory sentence

`CraftingProfitScreen.vue` passes no `intro` to `PageHeader`, so the exact sentence and the
`page-intro` element are gone from that page; `PageHeader`'s prop is optional and the five other pages
keep theirs. Everything §2.1.1 requires kept is still there: keyboard row selection and focus,
`aria-current`, the request-error and empty states, the three display filters, the null-versus-zero
`—`, blocked explanations, and the temporary `CYCLE_DETECTED` row diagnostic (still the only
row-level state chip, unchanged).

### AC 5 — reuse and non-regression

No component, hook or response field was added. Changes are confined to
`SelectedResultDetail.vue`, `CraftingResolution.vue`, `ResolutionTreeNode.vue`,
`resolutionPresentation.ts`, `rowState.ts` and `CraftingProfitScreen.vue`. Selection by recipe
identity, response association, gross values, the non-TP calculation control and its recalculation
behavior are untouched, and the whole Discovery suite passes unchanged. JavaFX was not involved: no
file under `src/main/java` was modified.

### Evidence

All commands run from `frontend/`.

- `npm run type-check` (`vue-tsc --noEmit`) — clean.
- `npx vitest run src/crafting` — **11 files, 212 tests passed** (was 210: 2 new cases plus assertions
  added to existing ones). New/updated: `SelectedResultDetail.spec.ts` (one purchase list and the
  removed section including its empty state, the three labels absent with their causes kept, the three
  labels retained, the budget facts with and without echoed settings, the missing-price item pointer,
  the field-absent purchase answer), `rowState.spec.ts` (`labelAddsMeaning` for every reason and for
  the three unknown states), `CraftingResolution.spec.ts` (item-named missing price at depth 3 with
  id fallback and an unaffected parent, the fresh-row chip rule), `CraftingProfitScreen.spec.ts` (no
  intro sentence), `itemIcons.spec.ts` (the removed list's icon must not reach the screen).
- `npx vitest run` — **21 files, 318 tests passed**, run because `rowState.ts` and
  `resolutionPresentation.ts` are shared with Discovery.
- `npm run build` then `npm run smoke:profit` — **PASSED, 37 steps** in real Chrome against the
  script's own stub origin on `127.0.0.1:5176` (probed free first, asserted to have served the page).
  New coverage: the counted-craft purchase list with quantities and supplied quotes and no
  one-further-craft section, the absent introductory sentence, the missing price naming **Charged
  Core** at its own requirement while its priced parent carries nothing, no "Not blocked" chip with
  its sentence kept, and a new `INSUFFICIENT_BUDGET` fixture row checked at **1440px and 360px** —
  four crafts counted stay valid, maximum buy `25g 0s 0c` echoed, `24g 0s 0c` of counted-craft
  purchase, the affected purchase listed, the unnamed further purchase declared, no horizontal page
  scrolling. `Buying is off` is additionally checked through keyboard selection (Enter on the row
  control, focus retained).
- `npm run smoke:profit:live` — **PASSED** against the current backend (`./mvnw spring-boot:run`,
  freshly compiled) and a `vite` dev server started for this check, on the real populated database.
  Normal live selection still displays its tree: recipe 16, three nodes rendered in exactly the
  response's order, across both non-TP states, both reloads and both sell modes. Both processes were
  started by this session, identified by PID and command line, and terminated afterwards with the
  ports probed to confirm release; three pre-existing dev servers on 5173–5175 were left running.

**Re-verified after the attempt was interrupted.** The story file carried this Result while `Status`
still read `UNFINISHED`, so every reproducible check above was run again on the final working tree
before the status was set: `npm run type-check` clean, `npx vitest run src/crafting` **11 files / 212
tests passed**, `npx vitest run` **21 files / 318 tests passed**, `npm run build` (102 modules) then
`npm run smoke:profit` **PASSED, 37 steps** — all matching the recorded figures exactly. The live
check was re-run too: `GW2_FRONTEND_URL=http://127.0.0.1:5178 npm run smoke:profit:live` **PASSED**
against a freshly compiled backend (`./mvnw -o compile`, then `./mvnw spring-boot:run`, app PID 7620
on 8080) and a `vite --port 5178 --strictPort --host 127.0.0.1` dev server started for this check,
confirming that normal live selection still displays its tree — recipe 16, three nodes,
`SINGLE_OUTPUT_REQUIREMENT`, both non-TP states, both reloads and both sell modes, gross totals
`2g 29s 60c` / `3g 69s 0c`. Both processes were matched by command line and creation time, killed with
`taskkill /T /F`, and 8080 and 5178 probed to confirm release. A **pre-existing** `mvn spring-boot:run`
tree created 25.09.2026 (PIDs 3600/21272), which was holding no port, was identified as not this
session's and deliberately left running. The only code change made while resuming was removing one
stray blank line left at the deleted section's site in `SelectedResultDetail.vue`.

### CI fix after commit 3f9ed9c

The authoritative CI run for this story's commit failed one agent-runtime test,
`test_planning_holds.PlanningHoldTest.test_true_flag_continues_bounded_pass_then_false_holds`
(line 145: the second `plan_if_useful(idle=True)` returned `False`). It is unrelated to this
story's frontend change and was not caused by it — the trigger was the backlog reaching **three**
ready stories while `PLANNING_TRIGGER_MAX_READY_STORIES` is 2. Reproduced locally with
`python -m unittest agent.runtime.tests.test_planning_holds.PlanningHoldTest.test_true_flag_continues_bounded_pass_then_false_holds`
(same assertion, same line).

Two real defects, both fixed; no test was weakened.

1. **Code (the cause).** In `CapacityScheduler.plan_if_useful`, the "bounded follow-up pass"
   branch that honours a held `independent_work_remaining: True` sat *after* the trigger gate
   `if not (force or queue_low or changed_since_hold or (idle and planning_hold is None))`.
   From a held state nothing else can change, so that branch was unreachable unless the queue
   happened to be low or the caller forced a pass — the planner's flag was silently ignored on a
   stocked queue, contradicting the branch's own comment and the `_hold_planning` comment
   ("independent_work_remaining=True still permits one follow-up pass"). The predicate is now
   named once as `follow_up_requested` and is a trigger in its own right, reused by the three
   places that already tested it. Bounding is unchanged: the follow-up pass rewrites the hold, so
   a result reporting `False` holds again on the next idle cycle — asserted by the test's third
   call and `plan.call_count == 2`.
2. **Test isolation.** `PlanningHoldTest.setUp` patches the `orchestrator` module's paths but not
   `story_state`'s, so `get_selectable_story_candidates()` read the **real repository's** backlog
   and the queue-depth trigger flipped with it — the test had been passing only by accident of a
   short queue. `setUp` now patches `loop.get_selectable_story_candidates` to a stocked
   three-story queue, which is the condition these hold tests are about. This keeps the test
   meaningful rather than masking the fix: with the stocked queue the assertion can only pass via
   the code fix above, verified by restoring the pre-fix `orchestrator.py` and watching the same
   test fail again, then restoring the fix.

Evidence (repository root):

- `python -m unittest agent.runtime.tests.test_planning_holds -v` — **Ran 15 tests, OK**
  (the reported test and the rest of the module's holds).
- `python -m unittest agent.runtime.tests.test_orchestration_flow
  agent.runtime.tests.test_capacity_scheduler agent.runtime.tests.test_codex_capacity
  agent.runtime.tests.test_milestone_planning` — **Ran 56 tests, OK**; these are every module
  that exercises `plan_if_useful`, including
  `IdlePlanningTriggerTests.test_independent_work_remaining_allows_bounded_followup` and
  `test_stocked_queue_and_unchanged_inputs_do_not_idle_replan`.

No frontend, backend or documentation content was touched by this fix; only
`agent/runtime/core/orchestrator.py` and `agent/runtime/tests/test_planning_holds.py`.

### Limits

Rare states (unnamed items, absent fields, unrecognized codes, the budget limit) are evidenced through
**controlled fixtures** in Vitest and in the stub-backed browser check — the live database offered no
such row, and the live run therefore only confirms that a normal selection still renders its tree and
its gross values. No performance, timing or Phase 5 closure claim is made or implied. `smoke:layout`,
`smoke:discovery` and the Java suites were not run locally; GitHub Actions is the full-regression gate
(`TEST_STRATEGY.md` §20/§36).

### Documentation

`docs/CURRENT_ARCHITECTURE.md` §5.11 only: the detail region's single purchase list and the retained
undisplayed contract field, the absent page intro, the status-label rule with the three states that
keep their label and the shared fresh-row chip, and the item-named missing price in
`resolutionPresentation`. `DOMAIN_SPEC.md` owns these rules already and needed no change; no domain,
test-strategy or roadmap content was touched.

## Follow-up Findings

F001: `frontend/scripts/layout-browser-smoke.mjs` measures text/background contrast on `AREAS[0]`,
which is Crafting Profit, and its `sample('page intro', …)` now finds no element there, so that pair
is silently no longer measured (the `samples.length >= 15` floor still passes). Pointing that one
sample at a page that still has an intro would restore the coverage.

## Blockers

None.
