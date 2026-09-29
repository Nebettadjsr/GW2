## Story ID

STORY-WEB-016

## Title

Make Crafting Resolution compact with collapsed ingredient groups

## Status

DONE

## Milestone

milestone-05

## Goal

Let users scan required items, quantities, sourcing and crafters before expanding nested ingredients, without normal-view algorithm bookkeeping.

## Authoritative Source Documents / Sections

- docs/DOMAIN_SPEC.md section 2.1.1: compact resolution and fresh-summary presentation.
- docs/TARGET_ARCHITECTURE.md sections 12-14: backend authority, resolution consistency and special states.
- docs/ROADMAP.md supplied Phase 5: Crafting Profit resolution rendering and frontend interaction tests.
- docs/FRONTEND_UX_GUIDELINES.md: applicable detail, progressive disclosure and accessibility guidance.
- docs/TEST_STRATEGY.md sections 12 and 36: frontend verification and CI gate.
- agent/stories/STORY-WEB-007-profit-resolution-detail-view.md: existing tree and fresh-resolution behavior.
- agent/stories/STORY-WEB-015-profit-purchase-and-blocking-details.md: adjacent counted-craft purchases and concrete blocking explanations.
- agent/product-owner-requests/Request-012-crafting-tree.md.

## Context

WEB-007 records open recursive disclosures, detailed bookkeeping and a fresh-row summary. Request-012 changes their normal presentation. WEB-015 already owns counted-craft purchases and useful item-specific blocking explanations; this story complements that scope. Keep the existing fresh calculation because it supplies the authoritative tree; its duplicate row summary need not remain in the normal view.

## Acceptance Criteria

1. Each normal node shows item identity, required quantity, supplied sourcing labels (including mixed sourcing) and the supplied crafter under "Crafted by" where applicable. Preserve item icons/fallbacks and meaningful blocked/unavailable states, including PRICE_UNAVAILABLE and UNVALUED_NONTRADEABLE, without converting missing facts into zero or success.
2. Root and nested ingredient groups start collapsed. Their accessible disclosure controls expose all ordered children on demand, recursively, with no depth cap, merged occurrences or discarded requirements. Keep each visible node's compact summary outside its child disclosure. Newly selected or replaced resolution data starts collapsed; expanding one group does not automatically expand descendants.
3. Remove the permanent introductory paragraph beneath Crafting resolution and the normal per-node stock/crafted/bought/missing bookkeeping rows, producing recipe/recipe ID, crafts run and produced total. Keep useful blocking causes from WEB-015. Optional technical details may retain existing facts; adding a diagnostic feature is not required.
4. Assess cash, opportunity and effective cost rows against the compact-summary goal; record the retained/removed presentation choices and rationale in Result. Render retained amounts as supplied without aggregating inclusive costs or recalculating economics.
5. Remove the redundant normal-view THIS RECIPE IN THAT FRESH CALCULATION block. Retain the backend fresh-resolution request, response association, selection invalidation and distinct loading/error/absent-candidate behavior. Preserve a concise or optional basis explanation so the tree is not misrepresented as the counted-craft execution trace. Table values and WEB-015's counted-craft purchases retain their own basis.
6. Verify wide/narrow layouts and keyboard disclosure operation. Reuse existing components, preserve shared Discovery behavior where affected, and record any shared presentation impact. Update affected implementation documentation with the delivered behavior and verification limits.

## Required Tests

- Focused component tests for initially collapsed root/nested groups, independent keyboard expansion, repeated ordered items, replacement/selection reset, mixed sourcing, crafter label and required quantities.
- Rendering fixtures confirm removed prose/bookkeeping/fresh summary, retained economic values unchanged, useful blocking causes, special states and null-versus-zero distinctions.
- Retain focused response-association and loading/error/absent-candidate coverage; verify hiding the fresh summary neither replaces table values nor removes the resolution request.
- Explicit local browser smoke at wide and narrow widths: select a real backend result, expand its root and nested groups where present, compare displayed facts with its response, and check keyboard reachability. Use controlled deep/mixed/blocked fixtures for states absent from live data and report that distinction. Update affected browser assertions for intentional collapsed presentation.
- Run directly affected frontend tests and type checking; the story revision must pass the GitHub CI gate. No full local regression mandate.

## Constraints

- Presentation only: preserve backend calculations, DTO data and JavaFX coexistence. No frontend crafting reconstruction, economic calculation or contract redesign.
- No removal of useful errors or required quantities to achieve compactness. No arbitrary cleanup or new diagnostic subsystem.
- Do not modify the active STORY-WEB-013 or CURRENT_STORY.md. No performance-gate or milestone-closure claim.

## Dependencies

Completed STORY-WEB-007 and STORY-WEB-010. Execute after STORY-WEB-015 to preserve its selected-detail and item-specific blocking changes; both are already scoped and require no new decision.

## Definition of Done

Compact summaries and recursive collapsed disclosures satisfy the acceptance criteria, targeted component and browser evidence and limitations are recorded, affected documentation is updated, and the story revision passes the CI gate.

## Result

DONE. **Presentation only — two Vue components and their checks. No backend file, DTO, route, request
body, association rule or economic value was touched**, and no crafting fact was reconstructed,
aggregated or recomputed in the browser.

### What each node now shows (AC1, AC2)

`frontend/src/crafting/ResolutionTreeNode.vue` renders a compact summary: the node's **own** item icon
and name (ID fallback preserved), the supplied `{n} needed`, one chip per **supplied** acquisition
method — a mixed requirement keeps `From stock` *and* `Bought`, it is not forced into one label — the
named character under `Crafted by …`, and the three inclusive costs on one labelled line. Every
supplied state and blocked reason keeps its own marker and its own sentence, so `PRICE_UNAVAILABLE`,
`UNVALUED_NONTRADEABLE`, `DAILY_LIMIT`, `NON_TRADEABLE_MATERIAL` and unrecognized codes all still
appear, still name their own item, and still never read as success. Children sit in a **collapsed**
native `<details>`/`<summary>` at every level including the root's, so the summary of each visible
node stays outside its child disclosure; expanding one group opens nothing beneath it, there is no
depth cap, no merged occurrence and no discarded requirement — the whole tree is in the DOM and
collapsing is presentation only. Because `open` is the element's own state, `CraftingResolution` keys
the tree on a counter it bumps per accepted answer, so a replacement or newly selected resolution
remounts collapsed instead of inheriting the previous selection's expansions.

`Crafted by` is rendered **only where the backend supplied a character**; the former
`Character: Not assigned` row is gone rather than printed on every requirement. An absent assignment
is not a fact about the requirement, and the field is untouched in the contract.

### What was removed (AC3, AC5)

The permanent introductory paragraph under *Crafting resolution* and the per-node `<dl>` rows for
`From stock`, `Crafted for this requirement`, `Bought`, `Missing`, `Producing recipe` / recipe ID,
`Crafts run`, `Produced in total` and `Character`. The `THIS RECIPE IN THAT FRESH CALCULATION` block —
heading, status chip, the five-value `<dl>` and its basis paragraph — is gone as well. All of those
values remain in `ResolutionNodeDto` / `CraftingRowDto` and in the response; nothing was deleted from
the contract, no calculation was removed, and the existing `Technical details` disclosure still shows
`consistency`, `treeBasis`, `treeStatus` and `calculatedAt` verbatim. Removing the fresh-row status
chip does not hide a state: the tree's own nodes carry every unresolved fact, and the `unavailable` /
`absent` / `failed` / `loading` phases are untouched, so "no result to explain" and "request did not
work" are still different answers. The **concise basis** AC5 requires is one muted line —
"A separate calculation of one output batch — not every craft the table counted." — which keeps the
tree from being read as the counted-craft execution trace without the paragraph. The counted-craft
purchase list and blocking causes WEB-015 owns are unchanged.

### Economic rows: retained/removed decisions and rationale (AC4)

- **Retained, unchanged in value: cash, opportunity and effective cost, on every node.** None of the
  three is derivable from the other two, each is the backend's own inclusive figure, and "what does
  this requirement cost me" is one of the questions the view exists to answer. They are printed
  exactly as supplied: a null stays `—`, a domain zero stays `0c`, and no descendant cost is folded
  into an ancestor.
- **Removed: their three-row `<dl>` grid, and the two-sentence basis paragraph repeated under every
  node.** The verbosity came from the layout and the repetition, not from the values — the same three
  amounts now occupy one wrapping line, and the statements they needed ("each cost includes
  everything below its own requirement", "`—` is a cost the backend could not establish, not zero")
  are made **once** under the tree instead of up to N times. On the live five-node tree used as
  evidence this removed 15 grid rows and 5 paragraphs while losing no figure.
- **Not aggregated and not recalculated.** No inclusive cost is summed, no per-craft figure is
  derived, and no fee or profit is computed anywhere in this change.

### Shared Discovery impact (AC6)

`CraftingResolution` and `ResolutionTreeNode` are shared by `SelectedResultDetail` (Profit) and
`SelectedDiscoveryDetail` (Discovery), so Discovery inherits the compact summaries, the collapsed
groups and the removed fresh-row block. That is the intended shared behavior under DOMAIN_SPEC 2.1.1;
Discovery's own table values, fee note, detail hooks and association checks are unaffected, and its
browser smoke gained an explicit collapsed-groups assertion.

### Tests run (exact commands and real output)

- `npx vitest run src/crafting/__tests__/CraftingResolution.spec.ts src/crafting/__tests__/SelectedResultDetail.spec.ts src/crafting/__tests__/discoveryResolutionAssociation.spec.ts src/crafting/__tests__/profitResolutionAssociation.spec.ts`
  → **Test Files 4 passed, Tests 93 passed**.
- `npx vitest run src/crafting` (the directly related module) → **Test Files 11 passed, Tests 220 passed**.
- `npm run type-check` → clean; `npm run build` → clean, 171.07 kB bundle.
- `npm run smoke:profit` (real Chrome, own stub origin `127.0.0.1:5176`) → **PASSED (38 steps)** at
  1440×900, 1440×420 and 360×800. New/updated evidence: *"child groups start collapsed and toggle one
  level from the keyboard — 2 groups, 0 open initially, "0" after Enter, 1 nested group(s) left
  collapsed"*, the removed rows asserted absent by hook **and** by label, `7 tree text/background
  pairs meet WCAG AA` (lowest 8.27:1) now sampling the new cost and crafter lines.
- `npm run smoke:discovery` → **PASSED (13 steps)**, including *"the fresh tree is shown beside the
  table row without repeating its figures"* and *"requirements rendered in order with supplied costs,
  groups collapsed — 3 nodes, 0 groups open"*.
- `npm run smoke:profit:live` against a freshly started backend (`./mvnw spring-boot:run`, PID 27896,
  port 8080) and a freshly started dev server (`npm run dev`, port 5176, proxying `/api`) on the real
  local database → **PASSED**. It now also compares every node's `{n} needed`, all three costs and
  every `Crafted by` line against the response body, asserts the groups start collapsed, and drives
  the keyboard disclosure: `{"recipeId":16,"nodes":3,"groups":1,"expandedByKeyboard":"0","nestedLeftCollapsed":0}`.
- **One-off live nested check** (uncommitted script, same running backend/dev server): recipe **30
  "Bronze Plated Dowel"** with buying enabled — a live tree of **5 nodes, depth 3, 2 groups**. Root
  and the nested group at `0.0` were each expanded from the keyboard independently; `node-name`,
  `node-requested`, `node-cash-cost`, `node-opportunity-cost`, `node-effective-cost` and
  `node-crafter` matched the response body element for element; `resolution-row` /
  `resolution-total-profit` absent; no sideways overflow at 1440×900 or 360×800 and the expansions
  survived the reflow. Output: `{"nodes":5,"depth":3,"groups":2,"nestedPath":"0.0","crafterLines":2,"wideOverflow":0,"narrowOverflow":0}`.
  Per `tasks/lessons.md`, only this session's own process trees were killed afterwards, by matched
  command line, and `curl` confirmed 5176 and 8080 released while a pre-existing dev server on 5173
  was left running.

### Verification limits

- Live data supplied a **depth-3, 5-node** tree at best. Deeper trees, mixed sourcing on one node,
  blocked/`PRICE_UNAVAILABLE`/`UNVALUED_NONTRADEABLE`/unrecognized-code states and the
  replacement-resets-to-collapsed case are evidenced by the **controlled fixtures** in
  `smoke:profit`, `smoke:discovery` and the component tests, not by live data.
- jsdom drives `<summary>` activation from a click, not from a key event, so the component test
  asserts the control is a focusable native `<summary>` and exercises activation; **real Enter/Space
  operation is evidenced by the browser runs only**.
- No full local regression run (GitHub Actions is the authoritative gate, TEST_STRATEGY 20/36). No
  TestFX, real-database `*IT` or performance measurement was taken — this change touches no JavaFX
  view, no database path, and makes no performance or milestone-closure claim.

### Documentation

`docs/CURRENT_ARCHITECTURE.md` only: §5.13's resolution bullets now describe the compact summary, the
collapsed disclosure and the remount key, the fresh-detail bullet records the removed row summary with
the contract explicitly unchanged, the component-tree captions and the `smoke:profit` table row were
updated. `docs/DOMAIN_SPEC.md` §2.1.1 already specified this presentation and needed no change — the
implementation was brought to the spec.

## Follow-up Findings

None.

## Blockers

None.
