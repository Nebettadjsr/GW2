# Request-006-simplify-crafting-profit-state-and-non-tp-material-control.md

# Product Owner Request

## Status

RESOLVED

## Title

Simplify Crafting Profit state presentation and add control for non-Trading-Post materials

## Requested Change

Rework how blocked/state information is presented in Crafting Profit.

The current `State` / `Status` column contains information that is mostly not useful for comparing recipes in the primary results table.

Most state information should therefore be moved out of the comparison table and into the selected-result detail panel, where it can explain why further crafting is limited or blocked without cluttering every row.

### 1. Remove ordinary state labels from the comparison table

The following states should no longer require a dedicated visible table column:

- `BUYING_DISABLED`
- `NO_RECIPE`
- `DAILY_LIMIT`
- `RECIPE_NOT_ALLOWED`
- `INSUFFICIENT_BUDGET`

These states remain meaningful domain information and must not be discarded.

They should instead be presented as concise contextual notes in the selected-result detail panel when relevant.

The primary Crafting Profit table is for comparing recipes and economic results, not for displaying internal resolution reasons on every row.

---

### 2. BUYING_DISABLED

`BUYING_DISABLED` does not need to be shown in the primary table.

If buying is disabled by the user and further crafting would require purchased materials, this follows directly from the selected calculation settings.

Where useful, the selected-result details may explain:

> Further crafting requires purchasing materials, but buying is disabled.

Do not repeat this information as a status label on every affected recipe row.

---

### 3. NO_RECIPE

`NO_RECIPE` should not be displayed in the primary result table.

This state normally describes a downstream requirement for which no usable crafting recipe exists.

That information belongs in the crafting/resolution tree, where the affected material and its role in the selected recipe can be understood in context.

If the selected recipe can currently be crafted because the required item already exists in inventory, the existence of an uncraftable downstream material is not useful comparison information for the main table.

The resolution/detail view should expose the affected material and explain why further acquisition cannot proceed.

---

### 4. Add calculation control for non-Trading-Post materials

Add a user-facing calculation option for recipes that depend on materials which cannot be purchased through the Trading Post.

Preferred UI wording:

`Allow non-Trading-Post materials`

Suggested help/description text:

> Allow crafting plans that require materials which cannot be purchased on the Trading Post.

The exact domain behavior must be reviewed against the existing non-tradable material and valuation rules before implementation.

The intent is to give the user an explicit choice over whether recipes depending on such materials should be considered by Crafting Profit instead of communicating the condition through `NO_RECIPE` or similar table-state labels.

This control should be grouped with the other calculation/material controls and must clearly be a calculation rule rather than a result-display filter.

The planner must ensure that the resulting behavior is defined authoritatively in `DOMAIN_SPEC.md` before implementation if the existing rules do not already define it sufficiently.

---

### 5. DAILY_LIMIT

`DAILY_LIMIT` does not need to appear in the primary results table.

Daily-craft behavior is already controlled through the relevant calculation setting.

If a selected recipe is affected by a daily restriction, show a concise explanation in the detail panel.

Do not repeat the setting's consequence as a status label across the result list.

---

### 6. RECIPE_NOT_ALLOWED

`RECIPE_NOT_ALLOWED` does not need to appear in the primary table.

Recipes that are outside the currently allowed character/discipline/recipe scope should be handled through the existing calculation scope and result filtering.

If such a recipe is still present in the loaded result set for a valid reason, the selected-result details may explain that the recipe is unavailable under the current scope.

Do not treat this as primary comparison information.

---

### 7. INSUFFICIENT_BUDGET

`INSUFFICIENT_BUDGET` does not need to appear in the primary table.

The configured purchase budget already communicates the user's constraint.

If the selected result cannot continue because further purchases would exceed that budget, explain this in the selected-result detail panel.

Where possible, show the useful underlying information, such as the required purchase cost and configured budget, rather than only displaying the enum/state name.

---

### 8. CYCLE_DETECTED

Keep `CYCLE_DETECTED` visible for now as diagnostic information.

This represents an unexpected/problematic crafting dependency condition rather than normal user-facing calculation behavior.

It should be clearly treated as temporary diagnostic information.

Do not use the existence of `CYCLE_DETECTED` as a reason to retain a general State/Status column containing all other states.

Mark this presentation as temporary technical debt.

It must remain until the Product Owner explicitly requests its removal.

---

### 9. Selected-result detail presentation

The right-hand selected-result panel becomes the primary location for meaningful blocked/restriction explanations.

When relevant, it should explain conditions in user-facing language alongside the affected recipe/material/tree node.

Prefer explanations such as:

- buying is disabled;
- purchase budget would be exceeded;
- material cannot be purchased on the Trading Post;
- no crafting recipe is available for this requirement;
- recipe is unavailable under the current scope;
- daily crafting restriction applies.

Do not expose raw enum names as the normal user-facing wording.

Raw state codes may remain inside secondary technical/debug information if useful.

---

### 10. Primary result table

After this change, the normal Crafting Profit result table should focus on information useful for scanning and comparing opportunities, such as:

- recipe/item;
- craftable quantity;
- own materials value;
- profit per craft;
- total sell value;
- total profit.

A dedicated State/Status column should not remain merely because backend state information exists.

If `CYCLE_DETECTED` temporarily requires a row-level indicator, use the smallest reasonable diagnostic presentation rather than retaining all ordinary states in that column.

## Why / Product Intent

The Crafting Profit table should help the user quickly compare crafting opportunities.

Most current state labels describe consequences of settings the user already selected or details of downstream recipe resolution.

For example:

- if buying is disabled, the user already knows buying is disabled;
- if a purchase exceeds the configured budget, the budget setting already explains the constraint;
- if a downstream material cannot be crafted, the crafting tree is the useful place to show which material caused the problem;
- if a recipe is outside the allowed recipe scope, that is primarily scope/detail information.

Repeating these conditions across many rows adds visual noise without helping comparison.

Detailed reasons remain valuable, but they should appear where their context is visible: in the selected-result details and resolution tree.

`CYCLE_DETECTED` is retained temporarily because it is useful diagnostic information while the crafting graph is still being developed and verified.

## Constraints

- Do not remove blocked-reason information from the domain/backend.
- Change presentation and calculation behavior only where explicitly defined.
- Raw technical state codes should not be the normal user-facing UI.
- Preserve meaningful explanations in selected-result details and the crafting tree.
- The new non-Trading-Post-material option must have clearly defined domain semantics before implementation.
- Existing buying, budget, daily-craft and allowed-recipe behavior must not silently change.
- `CYCLE_DETECTED` remains available until explicitly removed by the Product Owner.
- Follow `docs/FRONTEND_UX_GUIDELINES.md`.

## Additional Context

The current domain specification explicitly models blocked reasons because different causes may require different actions. That information remains useful and should stay available in the result/detail model. The requested change concerns where that information is shown, not whether the backend retains it.

The Product Owner does not currently consider most blocked reasons useful enough to justify a permanent column in the main comparison table.

## Planner Resolution

2026-09-26: RESOLVED as planning coverage. The resolved Product Owner answer
was present in agent/user-decisions/UD-010-non-tp-control-disabled-behavior.md
under the wrong heading. Restored its User Decision heading without changing
the answer and applied the disabled behavior to docs/DOMAIN_SPEC.md section
2.1.1, complementing UD-009's enabled behavior. Created
agent/stories/STORY-DOM-021-profit-non-tp-material-control.md and queued it in
agent/stories/BACKLOG.md for domain enforcement, API propagation, the browser
calculation control and consistent selected-detail explanations. Reused
agent/stories/STORY-WEB-008-profit-economic-columns.md for ordinary state
placement, economic columns and the retained temporary cycle diagnostic, and
agent/stories/STORY-WEB-007-profit-resolution-detail-view.md for the tree.
No open product decision remains for this request. Delivery is not claimed.