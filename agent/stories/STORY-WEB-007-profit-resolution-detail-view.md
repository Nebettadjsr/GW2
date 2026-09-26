## Story ID

STORY-WEB-007

## Title

Render authoritative Crafting Profit resolution in sticky selected-result details

## Status

DONE

## Milestone

milestone-05

## Goal

Replace the Crafting steps placeholder with a backend-provided semantic resolution
tree and improve selected-result details, without reconstructing crafting choices
or misrepresenting a fresh explanation as the earlier table calculation.

## Authoritative Source Documents / Sections

- Supplied docs/ROADMAP.md Phase 5: resolution tree, special states and backend-only calculations.
- docs/TARGET_ARCHITECTURE.md sections 12 and 13.1-13.5: browser responsibility and decided detail contract.
- docs/DOMAIN_SPEC.md sections 2.1.1, 42 and 44: selected detail, meaningful reasons and explanation.
- docs/FRONTEND_UX_GUIDELINES.md section 4 and applicable state, money and accessibility guidance, referenced by TARGET_ARCHITECTURE section 12.
- agent/stories/STORY-WEB-005-crafting-profit-information-hierarchy.md, Result.
- agent/stories/STORY-API-008-crafting-resolution-endpoints.md: prerequisite wire contract and execution-policy evidence.

## Context

WEB-005 already provides a selected-result panel but explicitly does not deliver
the tree. APP-012 and API-008 supply the decided fresh detail operation. AR-003's
root identity decision is established; this story consumes it without changing
the domain's sourcing behavior. Request-004 asks for a usable sticky detail panel.

## Acceptance Criteria

1. Request Profit detail for the selected recipe with the table's effective
   calculation inputs, consuming API-008's completed contract and documented
   execution policy. Do not guess task transport if API-008 is blocked on it.
   Selection is lazy: no detail request for every table row.
2. Implement section 13.4's generation/identity/input checks, including A-to-B-to-A,
   calculation changes, table reload and leaving the view. Invalidate detail when
   required even if the page is kept alive. Late answers must not revive an invalid
   selection. Sorting alone does not create a new calculation identity.
3. Render the returned row and tree together as fresh detail with the specified
   single-output-requirement basis and consistency limit. Keep requested recipe
   identity separate from actual root sourcing, including inventory-only roots,
   another producing recipe and blocked attempts. Do not silently replace table
   values with the freshly returned row or call this a trace of all counted crafts.
4. Display backend-provided quantities, methods, producing recipe/character where
   present, produced quantities, costs, states and ordered child occurrences.
   Expansion may be interactive but no tree is silently truncated. Do not aggregate
   inclusive costs, merge repeated items, infer missing quantities or derive states
   from raw prices. Preserve null versus zero and visibly represent unknown codes.
5. Distinguish loading, blocked available tree, unavailable result, absent fresh
   candidate and transport failure. Errors/unavailability clear the active tree.
   Present PRICE_UNAVAILABLE and UNVALUED_NONTRADEABLE in understandable terms
   without hiding the backend facts or claiming an unvalued item has no relevance.
6. On wide screens make the right-hand panel sticky and its full contents reachable
   even when taller than the viewport; preserve usable narrow-screen flow. Emphasize
   buy cost with cost wording and established cost color, and clearly identify the
   selected Trading Post quote as the price for one output item. Keep useful material
   lists and remove only redundant Result summary content, not distinct facts.
   Add a safely constructed Wiki link only where the item identity/name provides a
   reliable target; omit a speculative link when metadata is insufficient.
7. Explain each displayed blocked reason's meaning and the table-versus-detail
   placement rationale in Result for PO review, reusing WEB-005's wording where
   correct. In particular BUYING_DISABLED describes inability to buy a requirement
   for further resolution, not necessarily invalidation of completed crafts.
   Keep technical request failures distinct from these domain conditions.
8. Update the relevant CURRENT_ARCHITECTURE description with actual behavior and
   verification limits. Preserve JavaFX/in-process behavior and table contracts.
   No browser performance-gate or milestone completion claim is made by this slice.

## Required Tests

- Controlled API/component tests for explicit inputs and matching echoes, fresh
  row/tree association, A-to-B-to-A, superseded response, reload, scope/settings
  changes and kept-alive navigation invalidation.
- Rendering fixtures for split sourcing, surplus output, repeated child occurrences,
  inventory-only/different-recipe roots, blocked attempt, null/zero costs, unavailable
  result, 404 candidate absence, API failures, special and unknown state codes.
  Assert supplied facts rather than calculating expected domain results in browser code.
- Real-browser targeted checks for selection-to-detail, keyboard expansion, sticky
  long detail accessibility, narrow-layout reflow and prominent cost/unit-price
  labels. Controlled origins must not accidentally proxy live synchronization.
- Read-only smoke against the real detail backend where available, comparing
  displayed facts with its response; distinguish this from controlled-state tests.
- Affected frontend tests, type checking and build. Record unavailable evidence
  honestly; use targeted compatibility checks only if shared boundaries change.

## Constraints

- Profit browser consumer only; no Discovery page or alternate wire contract.
- No new resolver, client economic calculation, icon strategy, CH-15/CH-17 fix or
  invented shopping totals. Follow section 13's authoritative basis and identity.
- Do not modify sync behavior or trigger live synchronization as a test fixture.

## Dependencies

- STORY-API-008 must be DONE with execution policy settled before implementation.
- STORY-WEB-005 (DONE); STORY-WEB-006 for integration with result controls/selection.

## Definition of Done

Profit selection presents truthful fresh backend detail and a usable semantic tree
with response isolation, responsive sticky presentation, targeted verification and
documented state meanings. Outstanding Phase 5 work remains explicitly open.

## Result

DONE. The Crafting Profit detail panel now renders `STORY-API-008`'s `POST
/api/crafting/profit/resolution` answer — the freshly calculated row and the semantic resolution tree
— in place of `STORY-WEB-005`'s Crafting steps placeholder, with §13.4's association rules, five
distinct situations and a sticky wide-screen panel. **Browser consumer only**: no file under `src/`,
no resource and no `pom.xml` was touched (verified by modification time), so no domain rule, HTTP
contract or JavaFX behavior changed and the Java suite was deliberately not re-run. **No
`TARGET_ARCHITECTURE.md` §33 page-timing claim, no Phase 5 exit criterion and no milestone transition
is claimed.**

An interrupted attempt was **resumed, not restarted**: the production files, the three new/extended
frontend suites and the extended `smoke:profit` script were already in place. This session re-verified
all of it, found and fixed two real defects the interrupted run had stopped inside (below), extended
the live `smoke:browser` check — which was still asserting the *placeholder* — to compare the rendered
tree against the real response, and wrote the documentation and records. Nothing already correct was
re-implemented.

### What changed

| File | Change |
|---|---|
| `frontend/src/api/types.ts` | `+` `ResolutionNode`, `ResolutionCalculation`, `CraftingProfitResolution{Request,Response}`, `RECIPE_NOT_IN_CALCULATION` |
| `frontend/src/api/craftingApi.ts` | `+` `resolveProfitDetail` on the existing interface (third route) |
| `frontend/src/crafting/useProfitResolution.ts` | **new** — lazy request, `ResolutionPhase`, generation/echo checks, `calculationKey`, `scopeRequestOf` |
| `frontend/src/crafting/resolutionPresentation.ts` | **new** — method/state/blocked-reason wording, unknown-code handling, root-sourcing sentence |
| `frontend/src/crafting/ResolutionTreeNode.vue` | **new** — one requirement, recursive children in an open disclosure |
| `frontend/src/crafting/CraftingResolution.vue` | **new** — the five situations, the basis wording, the fresh row |
| `frontend/src/crafting/SelectedResultDetail.vue` | Crafting steps placeholder → Crafting resolution region; summary retitled "Table calculation"; wiki link; cost/quote emphasis; resolution literals in the disclosure |
| `frontend/src/crafting/recipeLabel.ts` | `+` `wikiUrl` (percent-encoded article title, null when unnamed) |
| `frontend/src/crafting/CraftingProfitScreen.vue` | `+` `useProfitResolution`, `calculationInputs`, the invalidation watcher, `onActivated`/`onDeactivated`, the panel's `tabindex` |
| `frontend/src/styles.css` | `+` sticky/scrollable `.results-split__aside` at ≥64rem, `.money--cost`, `.detail-values__lead` |
| `frontend/src/crafting/__tests__/fixtures.ts` | `+` resolution fixtures: crafted/inventory-only/other-recipe/blocked trees, response builder, `Deferred` |
| `frontend/src/crafting/__tests__/CraftingResolution.spec.ts` | **new**, 21 tests |
| `frontend/src/crafting/__tests__/profitResolutionAssociation.spec.ts` | **new**, 14 tests |
| `frontend/src/crafting/__tests__/SelectedResultDetail.spec.ts` | `+` fresh-vs-table separation, cost emphasis, unit-price wording, wiki link, literals |
| `frontend/scripts/profit-browser-smoke.mjs` | `+` a scripted detail route and steps 5c–5g; `pageOverflow` now names the overflowing elements |
| `frontend/scripts/browser-smoke.mjs` | `+` reads the real detail response and compares the rendered tree, root quantity and envelope literals against it |
| `docs/CURRENT_ARCHITECTURE.md` | §5.11: title, file layout, the stale Crafting-steps bullet, a new resolution-detail block, "what the frontend does not do", "not built yet", the commands table and the smoke paragraph |
| `docs/TEST_STRATEGY.md` | §12.1 rules 4–5, §12.2 two rows, §12.4 rules 5–6 |

### Two defects found and fixed this session

1. **The sticky panel could not be proven sticky, and the check as written failed against correct
   behavior.** With six stub rows the comparison column is *shorter* than the detail panel, so the
   grid row is the panel's own height and `position: sticky` has no travel — scrolling to the page
   foot legitimately moves the panel with it. The assertion "the panel did not scroll out of view"
   therefore failed at `top: -16.5px`. Fixed in the check, not by loosening it: reachability of the
   panel's last line is asserted at 1440×900, and **pinning** is asserted at 1440×420 where the list
   is measurably the taller column — the panel stays at exactly its declared 16px offset after the
   page scrolls 120px past the split, with a separate assertion that it had left its flow position.
2. **A narrow viewport scrolled sideways once a detail was open** (397px of content in 360px). The
   shared `.status` treatment and the tree's own `.chip` keep their text on one line, which is right
   in a table cell but not for an unrecognized code of unknown length inside a nested node: the
   323px-wide `SOME_REASON_ADDED_LATER (not recognized)` pill pushed the whole grid column past the
   viewport. `white-space` is now relaxed with `overflow-wrap: anywhere` **inside the tree only**;
   nothing is shortened, abbreviated or dropped to fit. `pageOverflow` now reports the offending
   elements, because a scroll-width number alone did not identify the cause.

### How the acceptance criteria are met

1. **Lazy request with the table's own effective inputs.** `syncResolution` fires from one watcher on
   selected recipe + calculation identity; the comparison table itself issues nothing, and
   `requestCount` makes that assertable. The body is `recipeId` plus `calculation` built from the
   scope and settings **the table response echoed** (`profit.effectiveScope` / `profit.settings`), with
   an effective scope member reported as null omitted rather than sent as null. No row number, price,
   material map or context identifier is included. API-008 fixed the execution policy as synchronous
   HTTP 200, so nothing here polls, retains a token or assumes a task transport.
2. **§13.4 association.** A private generation counter is incremented by *every* invalidating event:
   changed selection, changed calculation identity, a started reload (which empties
   `calculationInputs` while in flight), and `onDeactivated` while the page is kept alive —
   `onActivated` then asks again, freshly. An answer whose generation is stale returns without
   touching state, which is what makes **A → B → A** safe. An accepted answer must also echo the
   recipe id and the same inputs, or it is reported as a mismatch with no tree. `calculationKey` is
   built from the echoed scope and settings **only**, so sorting, searching, the filters and the
   display maximum never invalidate a detail.
3. **Row and tree together, as fresh detail.** The region states in words that it is a separate
   calculation of **one output batch** of the requested recipe from that calculation's own starting
   inventory, budget and daily state — "not every craft the table counted, and not a promise that the
   requested recipe was the one executed" — and that its numbers may differ from the table's, with
   neither replacing the other. The table's row is never overwritten; the fresh row sits under its own
   heading, with its totals basis worded from the *fresh* `craftableCount`. `describeRootSourcing`
   compares supplied identities only and produces one of three sentences: the requested recipe was
   selected, another recipe was, or none was (inventory-only). A blocked attempted root keeps its
   recipe with zero completed crafts.
4. **Node facts printed, never computed.** Every supplied quantity, the producing recipe or "None
   selected", craft count, produced quantity, character or "Not assigned", the three inclusive costs,
   methods, states, blocked reasons and children in the resolver's order. Each node states that its
   costs already include everything below it, so nothing is added twice; `null` renders `—` with the
   sentence that it could not be established and a domain zero renders `0c`. Two occurrences of one
   item stay two nodes keyed by the child-index path. Children are an **open** `<details>` per node:
   the whole tree is in the DOM from the start, collapsing is the user's choice, and a note says so.
   Unknown method/state/reason codes render as `<CODE> (not recognized)` with the dashed
   `status--unknown`/`chip--unknown` treatment, never as success.
5. **Five situations, never a residual tree.** `loading` (a `role="status"` notice), `ready` (a tree,
   whose nodes may still be blocked), `unavailable` (`RESULT_UNAVAILABLE`, worded as the
   calculation's own answer), `absent` (404, worded as this fresh calculation no longer offering the
   recipe and explicitly *not* as the recipe being gone from the database) and `failed` (worded as a
   request that did not work, "not something the calculation established"). The last three set
   `detail`/tree to null. `PRICE_UNAVAILABLE` reads "No purchase price is available for this item. A
   cost shown as missing is unknown, not zero."; `UNVALUED_NONTRADEABLE` reads "This item cannot be
   traded, so the calculation established a value of 0 copper for it. That is a known zero rather than
   a missing price, and the item is still a real requirement of this craft."
6. **Sticky, prominent cost, single-item quote, lists kept, safe wiki link.** From 64rem the panel is
   `position: sticky` with `top: var(--space-4)`, `max-height: calc(100vh - 2 * var(--space-4))`, its
   own `overflow-y` and `tabindex="0"`, so content taller than the viewport is reachable by keyboard
   as well as pointer; below 64rem it is an ordinary block under the comparison region. Buy cost is
   labelled "Cost of materials to buy" and carries `.money--cost` (the established cost color, shared
   with loss) at the larger `.detail-values__lead` size — measured at 16px in the cost color against
   14px in the ordinary color. The quote heading is "Trading Post price for one <item>" with
   per-item rows and the sentence "Each is the price of a single item. One craft of this recipe
   produces N." Both material lists are kept with their separate bases, and unsupplied / empty /
   nothing-to-buy stay three messages. The wiki link is built only from a supplied name,
   percent-encoded into the article path, and is **omitted with a stated reason** when the backend
   supplied none — an item id is not a wiki address. **On removal:** the only content this story
   removed is the Crafting steps placeholder and its "no resolution tree is displayed" prose, replaced
   by the real resolution; the summary group was retitled "Table calculation" so the two calculations
   are distinguishable. Nothing else in the summary was dropped, because nothing in it is duplicated
   elsewhere — the table shows six columns and none of output quantity, revenue, own-materials value
   or the quote is among them, and the fresh row's four values are a *different calculation's*
   answer, not a repeat of the table's. See the placement rationale in AC 7.
7. **Blocked-reason meanings, and the placement rationale, for PO review.** The wording is
   `rowState.ts`'s, reused with the one adjustment a node needs: a row's `blockedReason` names why the
   *next* craft could not complete (so it reads "Further crafting is blocked because … The N crafts
   already counted stay valid"), while a node's reason names why *this requirement* could not be
   resolved (so it reads "Blocked because …"). Both are the domain's own meaning, not a new one.

   | Code | Node label | Node sentence completes "Blocked because …" |
   |---|---|---|
   | `NO_RECIPE` | No recipe | this item has no usable recipe |
   | `BUYING_DISABLED` | Buying is off | it would have to be bought to resolve this requirement, and buying is switched off |
   | `DAILY_LIMIT` | Daily limit | this item is limited to a daily amount |
   | `CYCLE_DETECTED` | Recipe loop | its recipe ends up depending on itself |
   | `PRICE_UNAVAILABLE` | Price missing | no price is available for it |
   | `RECIPE_NOT_ALLOWED` | Recipe not allowed | its recipe is not allowed by the current settings |
   | `INSUFFICIENT_BUDGET` | Over the buy limit | buying it costs more than the maximum buy setting allows |

   **`BUYING_DISABLED` in particular** is worded as an inability to *buy a requirement for further
   resolution*, never as invalidation of crafts that completed. Several reasons on one node are all
   named and joined; none is dropped for brevity. **Table versus detail:** the comparison table keeps
   only the six columns worth comparing across recipes, with the state as a short label; every reason
   *explanation*, the requirement it applies to, the costs, the quantities and the tree live in the
   detail panel, because they are about one recipe and are unreadable as a column. **Technical
   request failures are a separate axis**: `failed` (transport/HTTP) and `absent` (404) are worded as
   things that happened to the *request*, and `unavailable` as the calculation's own answer — none of
   the three is ever presented as a domain blocked reason, and none of them leaves a tree on screen.
8. **Documentation and preserved behavior.** `docs/CURRENT_ARCHITECTURE.md` §5.11 describes the actual
   behavior and its verification limits, and its three stale statements (the Crafting-steps
   placeholder, "no frontend file calls them", the two-route client) are corrected. Table contracts
   are untouched — the six columns, the sorting keys, the display controls and the selection rules are
   as `STORY-WEB-005`/`STORY-WEB-006` left them, and the table still issues no per-row detail request.
   JavaFX and the backend are untouched at the file level.

### Verification (actual runs)

- `npm test` — **Test Files 15 passed (15), Tests 189 passed (189)** (was 150 after `STORY-WEB-006`;
  **+39**: the two new suites are 21 + 14, the remaining 4 are additions to the existing detail and
  formatting suites). Re-run after the CSS fix. Per-file, as counted from this run:
  `CraftingResolution` 21, `profitResolutionAssociation` 14, `SelectedResultDetail` 15,
  `CraftingProfitScreen` 35, `useProfitTableView` 16, `formatCopper` 7, `rowState` 5.
- `npm run type-check` — clean. `npm run build` — OK, `131.03 kB` JS / `44.94 kB` gzip,
  `16.31 kB` CSS / `3.48 kB` gzip (was 115.63 / 40.80 and 14.38 / 3.16).
- `CraftingResolution.spec.ts` **21**: the five situations each in isolation and none leaving a tree;
  the fresh-calculation/one-output-batch basis present and "every counted craft" *absent*; the
  requested recipe as the selected one, an **inventory-only** root not read as an execution,
  **another producing recipe** keeping its id/executions/children, a **blocked attempted** recipe with
  no completed craft; every supplied quantity/method/recipe/character rendered; inclusive costs
  printed as supplied with no child folded into a parent; **known zero apart from an unestablished
  cost**; **repeated items as separate ordered occurrences**; the whole tree rendered in order with
  every child group open; the item-id fallback; every blocked reason named with none dropped;
  `BUYING_DISABLED` worded about the requirement and not about lost crafts; an unknown code shown as
  itself and never as success.
- `profitResolutionAssociation.spec.ts` **14**: no request until a selection; exactly the recipe id
  and the table's echoed inputs and nothing else; a changed scope and changed settings carried into
  the next request; **no request for sorting, searching or a display filter**; invalidate-and-ask on
  reload; a **superseded** answer ignored; the first answer of **A → B → A** ignored; an answer
  echoing a different recipe or different inputs refused; the detail cleared when a replacement result
  set drops the recipe; 404 as its own situation; a technical failure kept distinct and clearing the
  tree; `RESULT_UNAVAILABLE` without a tree; **invalidate on leaving and ask freshly on return** while
  the page is kept alive; and an answer arriving *while away* unable to revive the detail.
- `SelectedResultDetail.spec.ts` **15**: the table calculation kept apart from the fresh
  explanation, buy cost emphasized as a cost, the quote named as the price of one output item, the
  wiki link only when named, and the backend's resolution literals in the technical disclosure —
  alongside every pre-existing assertion, none weakened.
- `npm run smoke:profit` — **PASSED, 24 steps** (was 18) in real Chrome against a controlled origin on
  `127.0.0.1:5196`, with the stub answering `/api/crafting/profit/resolution` from a tree carrying
  split sourcing, a repeated item, a known zero, a missing price and an unrecognized
  method/state/reason. New evidence: **one** detail request per selection carrying scope and settings
  only (asserted absent: `row`, `prices`); the 7-node tree rendered **whole and in order**; the
  requested-recipe sentence, "one output batch", "Price missing", "Not tradable, valued at zero",
  `SOME_STATE_ADDED_LATER (not recognized)` and "already includes everything below" all present;
  keyboard focus reaching a child group and **Enter collapsing and re-expanding** it; 4937px of detail
  scrollable inside an 866px box with its last line reached at y=795; the panel **pinned at 16px** at
  1440×420 beside a 732px list; **6 tree text/background pairs meeting WCAG AA, lowest 8.27:1**; the
  display controls still causing **zero** calculations and **zero** detail requests; and at 360×800
  the detail below the results with **no page-level horizontal scrolling** after the fix.
- `npm run smoke:layout` — **PASSED, 11 steps** unchanged (**21 pairs meet WCAG AA**, lowest 4.92:1).
  `npm run smoke:sync` — **PASSED, 9 steps** unchanged.
- **Live, read-only, against the real backend** (`smoke:browser`) — **PASSED, 11 steps** (was 9):
  3175 rows calculated, first total `+26g 41s 76c`; selecting a real row sent **one** detail request
  and **no** further table calculation; the response was HTTP 200 and the rendered tree matched it —
  **3 nodes in the backend's own depth-first order**, root requested quantity 1, root recipe **7851**
  for requested recipe **7851** — with `FRESH_CALCULATION`, `SINGLE_OUTPUT_REQUIREMENT`, `AVAILABLE`
  and the response's `calculatedAt` all shown verbatim in the technical disclosure. `smoke:account` —
  **PASSED, 8 steps** (180 slots / 17 empty in supplied order, 9 categories / 505 stacks), unchanged.
- Per `tasks/lessons.md`: every port was probed on **both** address families before use (5196/5197/
  5201 for the controlled origins, 5198/5200 for dev servers, 8091 for the backend); pre-existing
  IPv6-only listeners on 5173/5199 and the pre-existing servers on 8080 were left untouched. Afterwards
  each process this session started was identified by its own command line (via
  `Get-CimInstance Win32_Process`, since `WMIC` and `netstat` both return nothing in this sandbox) and
  killed with `taskkill /T /F`, and all three ports were then **probed refused on both families** while
  the pre-existing ones still answered.

### Limitations and what this does not establish

- **`TARGET_ARCHITECTURE.md` §33 is not measured.** No navigation-to-complete-page timing was taken,
  and §33 explicitly includes detail loaded as part of a page. The live run's tree was **3 nodes**;
  nothing here says how a deep tree or a 3175-row page plus a detail behaves. Phase 5's performance
  gate and its review gate remain open, and no exit criterion or milestone transition is claimed.
- **The live comparison covered one recipe with one shape.** The real backend answered `CRAFT` of the
  requested recipe, `AVAILABLE`, no blocked reason and no state flag — the same shape API-008's own
  real-data run found. Inventory-only and alternative-recipe roots, blocked attempts, split sourcing,
  cycles, `PRICE_UNAVAILABLE`, `UNVALUED_NONTRADEABLE`, `RESULT_UNAVAILABLE`, unknown codes, the 404
  and transport failures are **controlled-response evidence only**; none was manufactured against the
  real database, and none should be reported as observed live.
- **A long-running older backend answers the detail route with Spring's default 404.** The process
  already listening on 8080 predates API-008, so the first live attempt read `RECIPE_NOT_IN_CALCULATION`
  where the truth was an unmapped path. The run recorded above used a **separate backend started from
  the current source on 8091** with a dev server proxying to it; the pre-existing process was left
  alone. This is an environment fact, not a frontend defect, and `smoke:browser` now records the
  situation rather than a comparison when the route is unavailable. Anyone re-running it must confirm
  the backend they point at is newer than API-008.
- **Rendering is unbounded by design.** Every node of a returned tree enters the DOM at once (no
  virtualization, no paging, no depth cap), which is what "no tree is silently truncated" requires;
  the deepest tree measured live was depth 2. Combined with the still-unvirtualized 3175-row table,
  this is the open performance question above, not a solved one.
- **Nothing was added for the Discovery detail route.** `POST /api/crafting/discovery/resolution`
  exists and has no browser consumer; there is no Discovery page.
- **`KNOWN_PROBLEMS.md` CH-15 and CH-17 show through unchanged**, exactly as §13.5 permits: the
  envelope carries the requested identity and each node its actual sourcing, and the browser labels
  the difference instead of hiding or correcting it. No domain correction was attempted here.
- **No screen-reader run and no WCAG conformance claim.** Contrast is 6 measured tree pairs plus
  `smoke:layout`'s 21; `forced-colors`, `prefers-contrast`, true browser page zoom and the
  selected/disabled combinations were not measured. Keyboard coverage is the focus walk, Enter on a
  child group and the panel's focusable scroll container.
- **The rest of `DOMAIN_SPEC.md` §2.1.1 is untouched and now partly ahead of the implementation.**
  Its current text also asks for total sell value and the remaining columns (`STORY-WEB-008`), for the
  display controls to move into the Calculation controls panel as a "Displayed results" subgroup, for
  the State column and the row-selection/filter/limit explanatory paragraphs to be removed, for a
  concise "Trading Post price / item" label, for the row-level `CYCLE_DETECTED` diagnostic, and for an
  "Allow non-Trading-Post materials" control (pending **UD-009**). None of that is in this story's
  acceptance criteria — and two of those items would contradict AC 6's explicit requirements — so none
  was done. **Planner action:** that cleanup (Request-006) has no story yet; WEB-008 as backlogged
  covers the columns only.
- The panel's sticky pinning was measured at 1440×420 because the stub's short list makes it
  observable; at 1440×900 with six rows the panel is the taller column and correctly has nowhere to
  travel. Pinning against a live 3175-row list was not separately measured.

## Blockers

None.
