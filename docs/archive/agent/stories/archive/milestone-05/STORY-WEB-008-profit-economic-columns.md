## Story ID

STORY-WEB-008

## Title

Expose backend total sell value and complete Profit comparison columns

## Status

DONE

## Milestone

milestone-05

## Goal

Complete the Crafting Profit comparison table's required economic content, with total sell value calculated authoritatively in the backend and rendered unchanged in the browser.

Apply DOMAIN_SPEC section 2.1.1's requested placement of ordinary restrictions in
selected-result details while retaining a minimal temporary cycle diagnostic.

## Authoritative Source Documents / Sections

- Supplied docs/ROADMAP.md Phase 5: crafting profit screen and backend-only calculations.
- docs/DOMAIN_SPEC.md sections 2.1.1, 25-28: required columns, revenue basis and craft count.
- docs/TARGET_ARCHITECTURE.md sections 12-13: frontend responsibility and fresh detail contract.
- agent/stories/STORY-WEB-005-crafting-profit-information-hierarchy.md, Result: current columns and monetary bases.
- agent/stories/STORY-WEB-006-profit-result-display-controls.md and STORY-WEB-007-profit-resolution-detail-view.md: integration with display controls and fresh detail.

## Context

Request-004 item 2 is not covered by the existing table/detail stories. WEB-005 records own-materials value in detail and per-craft revenue, but no backend total-sell-value field. DOMAIN_SPEC section 2.1.1 already settles the formula and fee treatment; no new economic decision is required.

Request-006's presentation requirements are now owned by DOMAIN_SPEC section
2.1.1 and fit this existing table/detail slice. Its separate non-TP-material
calculation control has partial semantics recorded from resolved UD-009 in
DOMAIN_SPEC section 2.1.1; its disabled behavior awaits UD-010. It is not part
of this story.

## Acceptance Criteria

1. Produce total sell value behind the application boundary from section 25's authoritative per-execution output revenue and section 28's craftable count, including recipe output quantity exactly once and preserving the no-additional-TP-fee rule. Reuse existing revenue and simulation results; do not introduce another resolver or alter craftable count.
2. Expose the value through an additive, documented HTTP field in Profit rows and the corresponding fresh-detail row. Keep shared row projections coherent without changing Discovery rules. HTTP mapping copies the backend value and performs no economic calculation. Preserve known zero versus unavailable/null according to existing result availability and price semantics.
3. The main Profit table includes recipe/item, craftable count, own materials value, profit per craft, total sell value and total profit. Reuse supplied own-materials value and label its existing per-craft basis honestly; do not invent a total from it. Label totals and per-craft values clearly and retain meaningful blocked/unavailable information.
4. Render and sort supplied monetary values without recomputation. Keep display filters, selection identity, fresh-detail consistency and responsive table scrolling working. Use shared sign/cost wording and color treatments, with non-color cues and emphasis for totals; keep secondary diagnostics in detail.
5. Preserve JavaFX behavior and existing calculation results. Update CURRENT_ARCHITECTURE's affected contract/frontend descriptions and record implemented field names, bases, tests and limitations in Result. No full-page performance or milestone completion claim.
6. Remove the general State/Status column and ordinary row labels for BUYING_DISABLED, NO_RECIPE, DAILY_LIMIT, RECIPE_NOT_ALLOWED and INSUFFICIENT_BUDGET. Preserve these backend facts in concise selected-result explanations and relevant supplied tree-node context. Explain limits on further crafting without negating completed crafts. Display supplied costs and configured budget where useful; never invent a missing acquisition amount, sum inclusive tree costs or infer non-TP eligibility from a reason code.
7. Keep CYCLE_DETECTED visible through a minimal row diagnostic until the Product Owner explicitly removes it, marking this presentation as temporary technical debt. Preserve price/unavailable-result and null-versus-zero meaning without restoring a general status column. Raw codes belong only in secondary technical information. Follow docs/FRONTEND_UX_GUIDELINES.md and DOMAIN_SPEC section 2.1.1.

## Required Tests

- Backend tests for total sell value with multi-output recipes, multiple crafts, zero crafts, both sell-price modes and unavailable prices/results; assert no extra fee and unchanged existing economic facts.
- HTTP mapping tests including a supplied total deliberately different from a mapper-recomputed product, and fresh-detail versus table row projection compatibility.
- Frontend fixtures with deliberately non-derived totals, null/zero/positive values, required headers and bases, sorting and display-control/selection integration; assert no browser economic calculation.
- Targeted browser checks at wide/narrow sizes; affected backend/frontend checks, type checking and frontend build. Verify JavaFX compatibility if shared result signatures change.
- Controlled rendering cases for each moved reason, a positive craft count with further crafting blocked, downstream NO_RECIPE context, supplied budget/cost information, retained cycle diagnostics, unavailable prices/results and unknown codes. Verify ordinary labels and the general State/Status column are absent while detail preserves their meaning, including after selection and fresh-detail replacement.

## Constraints

- Economic behavior is owned by DOMAIN_SPEC; do not modify valuation, sourcing, fees or simulation limits.
- No icon implementation, new filters or broad redesign in this slice.
- Do not implement the non-Trading-Post-material calculation control or guess UD-010's answer. Presentation changes must not alter backend reasons, eligibility, buying, budgets, daily restrictions or scope rules.
- Any overflow handling must follow established backend monetary conventions rather than silent truncation.

## Dependencies

- STORY-WEB-006 and STORY-WEB-007 completed for table/detail integration.
- STORY-API-008 completed for the fresh-detail row projection.

## Definition of Done

Required economic columns render authoritative backend values, with documented monetary bases and focused backend/transport/browser verification.

## Result

Done. Total sell value is produced in the domain, carried through the shared HTTP row projection
unchanged, and rendered and sorted in the browser without recomputation; the comparison table now
carries DOMAIN_SPEC 2.1.1's required economic content and no general State column.

### Implemented field names and monetary bases

| Layer | Name | Basis |
| --- | --- | --- |
| Domain | `craft.CraftResult.totalSellValueCopper` | TOTAL for `craftableCount` crafts |
| HTTP | `CraftingRowDto.totalSellValueCopper` (JSON `totalSellValueCopper`) | same; `Integer`, null when `resultAvailable` is false |
| Browser | `CraftingRow.totalSellValueCopper` | same; rendered by `formatCopper`, `—` when null |

`CraftingPlanner.evaluateOneRecipeNew` computes it once as `revenueOne * sim.getCraftCount()` — §25's
per-execution output revenue (already carrying `recipe.outputCount` and deducting no Trading Post
fee) times §28's craftable count. No new resolver, price read or simulation was introduced, and
`craftableCount`, `revenueCopper`, `profitCopper` and `totalProfitCopper` are unchanged. It is a
carried field, not a derived accessor, so a value that disagrees with `revenue × count` is still
reported as the domain stated it. `CraftResult`'s two pre-existing constructors were kept and now
delegate, taking the product when a caller states no total of its own; a new 13-argument constructor
takes it explicitly and is what the planner and both lazy-tree copies in `CraftingProfitService` /
`CraftingDiscoveryService` use, so a rebuilt result carries the planner's figure rather than
re-deriving it. `CraftingRowMapper` copies the value into both the table rows and the fresh-detail
row and performs no arithmetic; Discovery gets the field because the projection is shared, with no
Discovery rule changed.

Comparison columns are now Recipe, Disciplines, Craftable *crafts*, Own materials *cost, per craft*,
Profit *per craft*, Total sell value *all crafts*, Total profit *all crafts*, sortable on exactly
those seven keys. The own-materials column shows the supplied `matsSellValueCopper` under its own
per-craft label and is never scaled into a total. The two totals are emphasized by font weight and by
their column notes, not by color; the own-materials figure uses the shared `.money--cost` treatment
with the word "cost" in its heading. The detail's totals group and the fresh-detail row both gained a
"Total sell value" entry.

The general State column is gone. `BUYING_DISABLED`, `NO_RECIPE`, `DAILY_LIMIT`,
`RECIPE_NOT_ALLOWED` and `INSUFFICIENT_BUDGET` carry no row label; the selected result states each in
words, still saying *further* crafting is blocked while the counted crafts stay valid, beside the
supplied buy cost and — for a budget restriction only, and only when the backend echoed one — the
configured maximum buy (new `settings` prop on `SelectedResultDetail`). No missing acquisition amount
is invented, no inclusive tree cost is summed, and nothing about non-TP eligibility is inferred.
Downstream reasons such as `NO_RECIPE` remain at the affected tree node through the existing
`resolutionPresentation` wording.

New `rowState.rowDiagnostic` keeps a few words beside the recipe name for four situations only:
`CYCLE_DETECTED` ("Recipe loop"), `PRICE_UNAVAILABLE` ("Price missing"), `resultAvailable: false`
("No result") and an unreported or unrecognized code (worded, never the raw code — raw codes stay in
the detail's "Technical details" disclosure). It is null for `NONE` and for all five moved reasons.

### Tests

- New `craft.CraftingPlannerTotalSellValueTest` (6): multi-output recipe over five crafts, both
  sell-price modes, zero crafts, an unavailable output price, buying-on proceeds versus profit, and
  exact pinned `buyCost`/`matsSellValue`/`profit`/`totalProfit` figures as an unchanged-economics
  guard. No-fee is asserted as exact equality.
- `web.CraftingProfitApiControllerTest` +2: a supplied total (9999) deliberately different from the
  mapper-recomputable product (4 × 7890), and known-zero versus absent-result.
- `web.CraftingProfitResolutionApiControllerTest` +1: the fresh-detail row carries the supplied total
  and its serialized field set equals `CraftingRowDto`'s record components, so the two routes cannot
  drift apart.
- Frontend +18 across `rowState`, `useProfitTableView`, `SelectedResultDetail`, `CraftingResolution`
  and `CraftingProfitScreen`, on fixtures whose totals are deliberately not the products of their
  parts (2222 ≠ 5 × 380, 1650 ≠ 4 × 450), covering null/zero/positive, the required headers and
  bases, sorting by both new keys with nulls last, each moved reason rendering with no row label but
  a full detail explanation, the retained cycle diagnostic, budget/cost context, unknown codes and
  fresh-detail consistency.
- Backend `./mvnw -o test`: **Tests run: 391, Failures: 0, Errors: 0** (BUILD SUCCESS).
  Frontend `npm test`: **207 passed**. `npm run type-check` and `npm run build` clean.
- `npm run smoke:profit` (real Chrome against this script's own stub origin on 127.0.0.1:5176):
  **26 steps passed**, including a new step asserting the required columns, the absent State column,
  the supplied total sell value (148g 14s 1c, not the 133g 33s 20c a derived one would give), the
  retained "Recipe loop" diagnostic and the absence of every moved code from the table — at 1440px
  and 360px, with the table's own region still scrolling (868px of columns in 334px) and no page
  overflow.

### JavaFX

No file under `src/main/java` outside `craft/CraftResult.java`, `craft/CraftingPlanner.java`,
`application/Crafting{Profit,Discovery}Service.java`, `web/CraftingRowMapper.java` and
`web/dto/CraftingRowDto.java` was touched; no JavaFX view, controller or presentation class changed.
`CraftResult`'s existing constructor signatures were preserved, so nothing JavaFX calls changed
shape, and the whole module compiles with the full suite green.

### Limitations and remaining uncertainty

- `totalSellValueCopper` is an `int` computed by plain multiplication, exactly as `totalProfitCopper`
  has always been. It therefore shares that field's existing (undocumented, pre-existing) overflow
  exposure; no new convention was invented and no saturating or widening behavior was added. Under
  UD-003's 250-craft cap this needs a per-execution revenue above ~8.6 million copper to matter.
- An unavailable output price still yields `revenueCopper: 0`, so the total is a known `0` rather
  than null. That is existing §25 revenue semantics, unchanged here; null appears only where the
  whole result is unavailable.
- `rowDiagnostic`'s `CYCLE_DETECTED` branch is temporary presentation technical debt, recorded in
  `KNOWN_PROBLEMS.md` §7.11 and to be removed only on the Product Owner's request.
- No full-page performance claim is made: the browser check ran against a stub origin with seven
  rows and evidences structure, layout and interaction only. The milestone is not closed by this
  story.
- Accessibility was verified only to the extent the existing smoke check covers (focus, keyboard
  selection, reflow at 360/1440px and 200% text, contrast of the tree treatments); the two new
  columns' contrast was not separately measured, since both reuse existing `.money` treatments.

## Blockers

None.
