# GW2 Tool — Domain Specification

## 1. Purpose

This document defines the business/domain rules of the GW2 Tool independently from its technical implementation.

It describes:

- what data means,
- how crafting availability is determined,
- how owned materials are treated,
- how missing materials are resolved,
- how Trading Post prices are interpreted,
- how crafting profit is calculated,
- how recipe discovery works,
- how special crafting restrictions are handled.

This document is the authoritative source for intended domain behavior.

Implementation details such as JavaFX, PostgreSQL, Java classes, REST APIs, containers, or frontend technology do not belong here unless they affect domain semantics.

---

# 2. Domain Objectives

The application supports three major domain use cases.

## 2.1 Crafting Profit Analysis

Answer:

> Which items can the user craft, what resources are required, and is crafting them economically worthwhile?

The analysis may consider:

- owned materials,
- craftable intermediate materials,
- purchasable materials,
- character recipe knowledge,
- crafting discipline,
- Trading Post prices,
- daily crafting restrictions.

---

## 2.1.1 Crafting Profit result presentation

The web view must preserve useful information hierarchy and interactions without
visually copying JavaFX. The comparison table must include recipe/item, craftable
count, own materials value, profit per craft, total sell value and total profit.
Label each monetary value's per-craft or total basis; keep secondary diagnostics
in selected-result details. Total sell value is the applicable Trading Post sell
value for the craftable output quantity: section 24's gross revenue per execution
times section 28's craftable count, including recipe output quantity. It remains
gross market value, not received revenue. Output Revenue, Total Sell Value and
Instant Buy / Instant Sell displays retain gross Trading Post values. In calculation
details, Profit and Total Profit carry a small explanatory note, such as
"after 15% TP fees". Section 25 owns the decided fee policy; separate rounded
listing/exchange-fee and net-revenue displays are not required. All amounts come
from the backend; no presentation layer calculates fees or profit.

The entire recipe row must be selectable, with an accessible keyboard equivalent
and a visible selection indicator. On wide screens, selection populates a dedicated
right-hand detail panel that remains visible while scrolling; small screens may
use a suitable responsive arrangement. Show applicable buy cost prominently with
cost wording and the negative/cost visual treatment, a clearly labelled Trading
Post price for one output item, useful recipe and shopping/material information,
and a GW2 Wiki link when a reliable URL can be constructed. Remove a redundant
Result summary and explanatory text when the same information is already clear.
Resolution detail follows TARGET_ARCHITECTURE section 13's decided basis and
consistency contract: show actual sourcing, required quantities and produced
quantities, not merely recipe relationships or an invented execution trace.

For a valid selected result still present in the fresh calculation, display the
actual backend resolution tree; a failed explanation request is not a substitute.
Preserve the legitimate fresh-candidate absence and error behavior defined by
that contract. When buying is enabled, show the materials still to buy for the
already calculated craft count, with their quantities and supplied price
information. Do not display a separate "FOR ONE FURTHER CRAFT" purchase section.

Selected details must not repeat generic labels such as "Buying is off", "Over
the buy limit" or "Not blocked" where surrounding content already explains the
state. Retain useful causes, including the affected requirement and supplied
purchase/budget information. A missing-price explanation must identify the
affected item by name or item ID, not only say "Price missing". Preserve the
distinction between limitations on further crafting and crafts already counted.
Remove the introductory sentence "Crafting opportunities the backend calculated
for the selected scope, with the profit it reported for each."

Place result-display controls inside the Calculation controls panel, in a compact
Displayed results subgroup distinct from the Calculation subgroup. Do not retain
a separate top-level Result display section. Provide these display controls:

- Hide items with craftable count 0, enabled by default.
- Hide recipes not allowed, enabled by default.
- Hide recipes with profit per craft <= 0, enabled by default.
- A changeable maximum displayed recipe count, initially 250, and Show all to
  remove that display limit for the full matching set.

These controls do not change the underlying calculation or section 28's separate
simulation cap. Preserve valid controls on refresh under section 2.2.1. Unknown
or unavailable facts must not be silently converted into zero or success.

All three filters can be disabled independently. Communicate ordinary behavior
through concise labels and grouping: remove permanent row-selection/keyboard
instructions, the paragraph explaining display filters, and the dynamic paragraph
explaining the display limit. A compact displayed/matching count may remain.
Column headings must communicate per-craft versus total values. Use a concise
"Trading Post price / item" label instead of generic unit-price explanatory prose;
keep output quantity with recipe/crafting information. Preserve keyboard selection,
accessibility semantics, errors, blocked explanations and necessary recipe-specific
domain information.

Blocked/unavailable information remains meaningful even where further crafting
is blocked after some crafts succeed. Preserve all backend reasons, but remove
the general State/Status comparison column. BUYING_DISABLED, NO_RECIPE,
DAILY_LIMIT, RECIPE_NOT_ALLOWED and INSUFFICIENT_BUDGET belong in selected-result
details as concise contextual explanations, not repeated ordinary row labels.
Explain downstream requirements beside the affected material/tree node when the
backend supplies that context. Describe buying disabled, missing usable recipes,
scope restrictions, daily restrictions and budget limits in user-facing language;
show useful supplied purchase costs and budget where available without inventing
missing amounts or recomputing economics. Raw enums may remain in secondary
technical information. Preserve unavailable-versus-zero distinctions and meaningful
price/unavailable-result information; do not imply success by removing a column.

Retain a minimal row-level CYCLE_DETECTED diagnostic, explicitly temporary
presentation technical debt, until the Product Owner requests its removal. It
does not justify retaining a general State/Status column.

Provide an explicit calculation/material control labelled "Allow
non-Trading-Post materials", grouped with calculation rules rather than display
filters. UD-009 establishes that it opens enabled and applies only to web Crafting
Profit, without changing Discovery or JavaFX behavior. Non-Trading-Post means
nontradeable by item/domain classification, not a missing selected quote for a
normally tradeable item; PRICE_UNAVAILABLE remains unchanged.

With the option enabled, owned non-TP materials and materials produced through
allowed crafting paths, including recursive intermediates, remain usable under
existing inventory/binding, buying, budget, daily and scope rules. Evaluate valid
alternative sourcing paths normally. Under this rule a plan is unavailable only
when a required non-TP material has neither usable owned inventory nor an allowed
crafting acquisition path; do not pretend it can be acquired externally. Preserve
blocked results in the backend/domain; normal display filters may hide them.
Explain the restriction in selected-result details, preferably at the affected
material/tree node, without a normal state column. Valuation under section 11.2
is unchanged.

With the option disabled, exclude calculation paths that consume a non-TP
material, including owned, account-bound, recursively craftable and intermediate
ingredients. Continue evaluating alternative valid sourcing/crafting paths that
do not consume non-TP materials. Retain blocked/domain information for selected
details; ordinary result-display filters determine visibility. This is the
disabled behavior decided in UD-010, not a reclassification of missing quotes.

The non-Trading-Post-material control must retain the user's selected value
during recalculation and valid refreshes. Both table and fresh-detail calculations
must use that setting; changing it triggers recalculation without silently
reverting the selection.

Profit, costs and important totals must be easy to scan with consistent application
colors and emphasis; signs, wording or other non-color cues must carry meaning too.

## 2.2 Crafting Discovery Assistance

Answer:

> Which currently undiscovered recipes can a selected character discover at their current crafting level, and what would those discoveries cost or return?

The analysis considers:

- selected character,
- selected crafting discipline,
- current crafting rating,
- recipes already known,
- discoverable recipes,
- required materials,
- owned materials,
- Trading Post prices.

---

## 2.2.1 Crafting View Selection and Refresh Behavior

Crafting Profit and Crafting Discovery must preserve valid user selections during automatic and manual data refreshes within the current view/application session. This includes character selection, sort mode, ascending/reverse direction where available, and comparable existing filter/sort controls. Refreshes update results without resetting these controls to defaults. Initial view creation may use the defined defaults; an option that no longer exists must fall back gracefully. Persistence across application restarts is not required.

Changing the selected character must recalculate and display results for that character. Relevant differences in usable character-bound inventory must affect the calculation; identical results alone do not establish a defect.

Crafting Profit uses its existing `Discipline` selector as the sole calculation-scope control; remove its separate `Character` selector. `All` is the initial default and selects all synced characters using the coordinated planning semantics below. A generic discipline selects all synced characters having that discipline for coordinated planning, respecting the existing recipe discipline and rating restrictions. A specific character/discipline entry (for example, `Armorsmith lvl 500 — Nbt Anch`) restricts calculation to that character and discipline. Retain these character-specific entries. This presentation supersedes UD-004's separate `All characters` selector requirement while retaining its coordinated planning decision. Crafting Discovery retains individual-character selection and its existing default; this consolidation applies only to Crafting Profit.

In Crafting Profit, `All characters` means a coordinated account-wide plan across all synced characters. Different crafting steps may be assigned to different eligible characters; the complete tree need not be executable by one character. Transferable intermediates may pass between characters. Eligibility must be validated per step, including recipe and crafting requirements. Soulbound inputs remain usable only by their owning character under §11.1; copies bound to different characters must never be pooled to satisfy a single character's step. Account-wide/sellable and character-bound resources remain distinct, and quantities must not be reused within a plan (§10).

## 2.2.2 Crafting Discovery web presentation

Provide a dedicated Crafting Discovery Helper page in the main navigation.
JavaFX is a functional reference, not a visual specification. Follow the shared
frontend UX guidelines with grouped controls, a scan-friendly comparison list
and a dedicated selected-result detail area.

Let the user select a character and an available crafting discipline, using
backend-provided character/rating facts and the established eligibility rules in
sections 34-35. The backend must exclude recipes above the character's applicable
rating, already-known recipes under account-wide ownership semantics, and recipes
that cannot be learned through normal ingredient-combination discovery, including
vendor/scroll-only recipes. The browser must not reimplement these rules.

Show item/recipe name, recipe level, missing-material cost, crafted-output Trading
Post sell value and profit per craft from authoritative backend values, with
clear monetary bases. Offer sorting by recipe level (including highest first),
output sell value and profit per craft, plus search/filtering to narrow the list.
Keep leveling relevance separate from profitability per sections 37-38; do not
invent an XP/profit score or make negative profit a discovery exclusion rule.
Preserve valid scope, search and sort selections on refresh under section 2.2.1.

Reuse applicable owned-material, buying, budget and Trading Post price-mode
controls with existing Discovery semantics. Do not change Profit behavior.
Selection uses recipe identity, not table position. Where supported by supplied
data, details show recipe information, material requirements, owned/missing
quantities, buy requirements/costs, the resolution tree, shopping/material lists
and contextual blocked/unavailable explanations. Follow TARGET_ARCHITECTURE
section 13's fresh-detail consistency contract; do not derive missing economics.

Use the application-wide icon strategy when available, established signed money
formatting/treatments, keyboard-operable selection and clear loading, empty,
blocked and error states. Keep raw backend diagnostics secondary.

## 2.3 Ectoplasm Salvage Analysis

Answer:

> What is the effective economic cost of salvaging Glob of Ectoplasm for Luck after accounting for the value of recovered materials?

This feature is separate from the general crafting planner.

---

# 3. Monetary Unit

All internal monetary calculations should use:

```text
1 copper = base unit
100 copper = 1 silver
100 silver = 1 gold
10,000 copper = 1 gold
```

Domain calculations should avoid floating-point money wherever possible.

---

# 4. Items

An item is primarily identified by its Guild Wars 2 item ID.

Relevant domain properties may include:

- item ID,
- name,
- type,
- rarity,
- level,
- vendor value,
- Trading Post availability.

An item may be:

- owned,
- craftable,
- purchasable,
- sellable,
- account-bound,
- otherwise unavailable.

These states are not mutually exclusive.

For example, an item may be both:

- owned,
- craftable,
- purchasable.

---

# 5. Recipes

A recipe describes a transformation:

```text
ingredients
    ↓
recipe
    ↓
output item × output count
```

A recipe has at least:

- recipe ID,
- output item ID,
- output quantity,
- required ingredients,
- ingredient quantities,
- one or more crafting disciplines,
- minimum crafting rating.

A single item may potentially have more than one recipe producing it.

This is important because:

```text
item != recipe
```

The same item may have multiple possible production paths.

---

# 6. Recipe Knowledge

The system must distinguish between:

- recipes that exist globally,
- recipes known by the account,
- recipes usable by a particular character,
- recipes not yet discovered.

A recipe existing in Guild Wars 2 does not automatically mean the selected character may use it.

---

# 7. Crafting Discipline

Recipes belong to one or more crafting disciplines.

Examples include:

- Armorsmith,
- Artificer,
- Chef,
- Huntsman,
- Jeweler,
- Leatherworker,
- Scribe,
- Tailor,
- Weaponsmith.

A character may have:

- multiple crafting disciplines,
- different ratings per discipline.

When analysis is restricted to a character and discipline, recipes outside that allowed set must not be treated as directly craftable by that character.

---

# 8. Crafting Rating

Each recipe has a minimum crafting rating.

A character may only use/discover a recipe when the relevant crafting requirements are satisfied.

For discovery analysis, a recipe with:

```text
recipe.minimum_rating > character.current_rating
```

must not be presented as currently discoverable.

---

# 9. Owned Material Pool

For economic analysis, the application treats owned crafting resources as a logical combined account pool.

The pool includes items from:

- account material storage,
- account bank,
- all character inventories.

Conceptually:

```text
owned_quantity(item)
    =
material_storage(item)
    +
bank(item)
    +
character_inventories(item)
```

All character inventories are included in the logical material pool whenever owned materials are enabled.

The purpose of this pool is economic analysis.

It does not necessarily represent what the Guild Wars 2 crafting UI can physically access at a particular moment.

---

# 10. Inventory Consumption Rule

When the option to use owned materials is enabled, owned materials should be consumed before obtaining additional copies.

Conceptually:

```text
need 10 Iron Ingot
own   6 Iron Ingot

→ consume 6 owned
→ resolve remaining need of 4
```

Owned material quantities must not be reused multiple times within one simulated crafting plan.

Example:

```text
Own:
10 Wood

Recipe A requires:
8 Wood

Recipe B / intermediate step requires:
5 Wood
```

Within a single simulation the system must not treat all 10 Wood as available independently to both requirements.

State must be consumed as the simulation progresses.

---

# 11. Opportunity Cost of Owned Materials

Owned materials are not economically free.

If an owned material could otherwise be sold, consuming it has an opportunity cost.

Conceptually:

```text
opportunity_cost
    =
owned_quantity_consumed
    ×
sell_value_per_unit
```

Therefore:

```text
cash spent = 0
```

does not imply:

```text
economic cost = 0
```

Example:

```text
Own material:
10 × item worth 1 silver each

Cash required:
0

Opportunity cost:
10 silver
```

This distinction is central to meaningful profit calculations.

## 11.1 Bound Materials

Binding affects whether an owned material can be used by the selected character.

### Account-bound items

Account-bound materials may be used by any eligible character on the account.

Because they cannot be sold on the Trading Post, they do not receive a normal Trading Post opportunity cost.

### Soulbound items

Soulbound materials may only be used for crafting by the character to which they are bound.

A soulbound item held by or bound to another character must not be counted as usable inventory for the selected character.

Because soulbound items cannot be sold on the Trading Post, they do not receive a normal Trading Post opportunity cost.

## 11.2 Non-Tradable Material Valuation

Non-tradable materials are valued using the following fallback order:

1. If the item has a crafting recipe, use its calculated crafting cost as its economic value. The recipe may be considered for valuation even when it is not currently unlocked by the account.
2. If the item cannot be crafted, use its vendor sell value if one exists.
3. If neither a crafting cost nor a vendor sell value exists, assign an economic value of `0`.

The crafting-cost calculation in step 1 is recursive and follows the same economic-cost rules as other crafting calculations.

An item valued at `0` by this fallback must not be treated as genuinely free. It represents an acquisition requirement whose economic value cannot be modeled from the available market, crafting, or vendor data.

Such an item must receive an explicit domain state, for example:

```text
UNVALUED_NONTRADEABLE
```

The resolution tree must expose this state so the UI can visibly highlight the item.

The specific color or visual treatment belongs to the UI layer, not the domain model.

---

# 12. Acquisition Methods

A required item may potentially be satisfied by:

1. owned inventory,
2. crafting,
3. Trading Post purchase,
4. a mixture of these methods,
5. or not at all.

A resolved requirement should conceptually record where its quantity came from.

For example:

```text
Need: 20 items

10 inventory
 6 crafted
 4 bought
```

---

# 13. Recursive Crafting

Crafting requirements are recursive.

Example:

```text
Sword
├── Blade
│   ├── Iron Ingot
│   │   └── Iron Ore
│   └── ...
└── Hilt
    └── ...
```

If an ingredient is itself craftable, the planner may recursively evaluate that crafting path.

Therefore recipe resolution must operate on a dependency graph rather than only direct recipe ingredients.

---

# 14. Crafting Graph

The logical crafting graph represents relationships between:

```text
output items
    ↓
recipes
    ↓
ingredients
    ↓
possible sub-recipes
```

The graph may contain multiple levels.

The graph must support cycle detection because recursive data must never cause infinite resolution.

---

# 15. Cycle Protection

If recursive resolution encounters an item already being resolved in the same active dependency path, that path must stop.

Conceptually:

```text
A requires B
B requires C
C requires A
```

must result in a blocked path rather than infinite recursion.

A cycle is a resolution failure for that path.

---

# 16. Output Quantity

Recipes may create more than one output item per craft.

Example:

```text
Recipe:
3 × Item X

Need:
5 × Item X
```

Required craft operations:

```text
ceil(5 / 3) = 2 crafts
```

Result:

```text
6 produced
5 required
1 excess
```

The planner must distinguish:

- craft operations,
- produced quantity,
- requested quantity.

---

# 17. Excess Production

Recursive crafting may produce more units than immediately required because recipes have fixed output quantities.

Excess intermediate output remains available within the same crafting simulation and must be reused before obtaining additional copies.

Example:

```text
Recipe A produces 6 × Intermediate Item
Recipe B requires 5 × Intermediate Item
```

After Recipe B consumes 5 units:

```text
1 × Intermediate Item
```

remains in the simulation inventory.

If another branch in the same crafting tree requires 4 additional copies, the planner must reuse the existing 1 and obtain only the remaining 3.

This reuse also applies across multiple executions of the same requested final recipe when calculating a multi-craft plan.

However, once the complete requested final crafting plan has finished, any remaining intermediate surplus receives no separate economic value or profit credit.

In summary:

- reuse intermediate leftovers within the same simulation,
- retain leftovers across branches and repeated final crafts,
- do not credit remaining intermediate surplus after the final plan completes.


# 18. Buying Materials

Buying may be enabled or disabled by the user.

## Buying Disabled

If a required quantity cannot be satisfied using allowed inventory/crafting paths:

```text
requirement = blocked / missing
```

No hypothetical purchase may silently satisfy it.

## Buying Enabled

Missing tradable materials may be purchased using Trading Post prices.

The resulting purchase must contribute to:

- required cash,
- missing/to-buy list,
- total acquisition plan.

---

# 19. Buying Budget

The user may specify a maximum amount of copper available for purchases.

Conceptually:

```text
max_buy_copper
```

A value of zero or below currently represents no effective purchase limit.

When a positive budget is active:

```text
total_required_purchase_cost
    <=
max_buy_copper
```

must hold for a plan to be considered feasible under that budget.

---

# 20. Trading Post Price Semantics

Guild Wars 2 Trading Post data exposes two relevant prices.

For this application they are interpreted according to the transaction mode.

## Buying

### Instant Buy

The buyer accepts an existing sell listing.

Use:

```text
sell_unit_price
```

### Listing Buy / Buy Order

The buyer places or matches the buy side.

Use:

```text
buy_unit_price
```

---

## Selling

### Instant Sell

The seller accepts an existing buy order.

Use:

```text
buy_unit_price
```

### Listing Sell

The seller lists the item for buyers.

Use:

```text
sell_unit_price
```

These terms must remain explicit because `buy price` and `sell price` are ambiguous without specifying whose perspective is meant.

---

# 21. Missing Trading Post Price

A missing Trading Post price is not equivalent to a free item.

If a required purchase has:

```text
no price
```

or:

```text
price <= 0
```

the system must not interpret its purchase cost as zero.

The item should instead be treated as:

```text
price unavailable
```

or:

```text
not purchasable through the selected method
```

Profit calculations requiring that purchase are therefore incomplete or invalid.

---

# 22. Choosing Between Craft and Buy

If an ingredient may either be crafted or purchased, the planner must select the valid acquisition path with the lowest total effective economic cost.

The optimization objective is:

```text
minimize total economic cost
```

not:

```text
minimize immediate cash spending
```

This means owned materials are not automatically preferred merely because they require no new cash expenditure.

Example:

```text
Option A — craft intermediate item
Cash cost: 0
Opportunity cost of owned materials: 80 copper
Effective economic cost: 80 copper

Option B — buy intermediate item
Cash cost: 60 copper
Opportunity cost: 0
Effective economic cost: 60 copper
```

The planner should choose Option B because its total economic cost is lower.

Cash purchase cost must still be tracked separately because it is needed for:

- buying-budget constraints,
- user-facing cost breakdowns,
- explaining how much additional gold must actually be spent.


# 23. Effective Economic Cost

For a resolved requirement, effective economic cost conceptually consists of:

```text
effective_cost
    =
cash_purchase_cost
    +
opportunity_cost_of_owned_materials
```

Crafted intermediate components inherit the costs of the resources used to produce them.

---

# 24. Crafting Revenue

The gross value of one recipe execution is:

```text
gross_revenue_per_craft
    =
selected_output_sell_price
    ×
recipe_output_count
```

The selected output price depends on the configured sale mode:

- instant sell,
- listing sell.

---

# 25. Trading Post Selling Fees

Resolved UD-011 supersedes Request-008's copper-accurate transaction-model
requirement. For application profitability, TP fees are 15% of the corresponding
gross Sell Value. Copper-level differences due only to transaction rounding,
minimum fees or sale grouping need not be modeled separately.

```text
profit = sell_value - (0.15 * sell_value) - own_material_value - buy_cost
```

Apply this rule to both per-craft Profit and Total Profit, using each value's
corresponding quantity basis. Apply it consistently to Crafting Profit, Discovery
informational profit, profitability comparisons/ranking and Ectoplasm profit.
Compute monetary results in the backend/domain and deduct fees exactly once.
Existing monetary precision/formatting conventions may be retained; no separate
transaction grouping model or rounding investigation is required.

Displayed Trading Post prices, Output Revenue, Total Sell Value and Instant Buy /
Instant Sell prices remain gross. Fees affect profit, not those displayed prices.
The 300c gross / 204c material example gives 51c profit. Recipe eligibility,
owned-material valuation, missing-material resolution, craft quantities, buying
costs, budgets and expected salvage yields remain unchanged.

## 25.1 Verified fee semantics

STORY-DOM-022 records the evidence and limitations of the existing explicit-sale
primitive, including unverified rounding direction. That historical calculation
does not define application profitability after resolved UD-011. Do not impose
its separately rounded component/minimum fees on section 25's 15% model, or
describe an inferred rounding direction as verified game behavior.

---

# 26. Profit Per Craft

Profit per craft is the gross output sell value less section 25's 15% fee,
purchased-material cost and owned-material opportunity cost. Purchased-material
cost remains the actual copper required for missing materials; opportunity cost
remains the value sacrificed by consuming existing inventory. Displayed Output
Revenue remains gross, not the fee-adjusted intermediate used for profit.

---

# 27. Total Profit

Apply section 25's 15% fee to the corresponding total gross Sell Value,
subtracting the existing total purchased-material and owned-material costs.
The existing conceptual relationship of per-craft profit times craft count
remains applicable under the same cost aggregation; UD-011 requires no separate
transaction-rounding or sale-grouping adjustment. Craft count must still come
from a resource simulation that prevents material reuse.

---

# 28. Craftable Count

Craftable count answers:

> How many times can this recipe be executed under the selected constraints?

Constraints may include:

- available inventory,
- ability to recursively craft ingredients,
- buying enabled/disabled,
- purchase budget,
- daily crafting limits,
- usable recipes.

Craftable count must not be determined solely by inspecting direct ingredients.

Recursive requirements must be included.

Per resolved UD-003, simulation intentionally evaluates at most 250 crafts of a recipe, even when materials would permit more. This is an intentional domain limit; no additional user-visible capped indicator is required.

---

# 29. Allowed Recipe Set

Not every known recipe in the global crafting graph is automatically allowed during a particular analysis.

The allowed recipe set depends on context.

Examples:

### All Recipes Mode

A broad recipe set may be available.

### Discipline Mode

Only recipes compatible with the selected discipline should be treated as allowed where character restrictions apply.

### Character + Discipline Mode

Only recipes the selected character can legitimately use should be considered allowed.

### Discovery Mode

The semantics differ further because the purpose is specifically to analyze recipes not yet known.

The planner must therefore receive an explicit allowed recipe set rather than assuming every recipe in the global graph can be used.

---

# 30. Multiple Recipes Producing the Same Item

An item may have multiple recipes that produce it.

When selecting a recipe for an intermediate item, use the following priority:

1. Prefer a valid recipe that can be crafted by the same crafting discipline as the parent recipe.
2. If multiple valid recipes remain within that discipline, choose the recipe with the lowest total effective economic cost.
3. If no valid recipe exists within the parent's crafting discipline, choose the economically cheapest valid recipe from the remaining available recipes.

The purpose of the first rule is to avoid unnecessary character or crafting-discipline switching where possible.

The implementation must not simply select the first recipe returned by the data source.


# 31. Daily-Limited Crafts

Some Guild Wars 2 items are produced by daily-limited crafting recipes.

The application does **not** track whether the account has already consumed the current day's daily crafting allowance.

Daily crafting state is therefore not part of the account model.

---

# 32. Daily Craft Strategy

When the user selects the behavior currently represented by `dailyBuyInsteadOfCraft`, daily-limited items must never be crafted by the planner.

The rule depends on whether owned materials are enabled.

## Use Own Materials = true

For a required daily-limited item:

1. use existing owned copies first,
2. buy any remaining required quantity if buying is allowed and the item is purchasable,
3. do not craft additional copies.

Conceptually:

```text
owned first
→ buy missing quantity
→ never craft
```

## Use Own Materials = false

For a required daily-limited item:

1. ignore owned copies,
2. buy the required quantity if buying is allowed and the item is purchasable,
3. do not craft any copies.

Conceptually:

```text
do not use owned
→ buy required quantity
→ never craft
```

If the required quantity cannot be purchased under the selected settings, the requirement becomes blocked.


# 33. Owned Daily Materials

Owned daily-limited items are ordinary owned inventory for the purpose of consumption.

When `useOwnMats` is enabled, existing owned copies may be used before purchases.

When `useOwnMats` is disabled, existing owned copies are ignored.

The application does not attempt to infer or track whether additional copies could still be crafted today.


# 34. Recipe Discovery

Normal crafting recipe unlocks are treated as account-wide.

A recipe is considered already learned if it has been unlocked anywhere on the account, regardless of which character originally discovered or learned it.

The Discovery feature concerns recipes that are:

- discoverable through the normal Guild Wars 2 Discovery system,
- not already unlocked account-wide,
- compatible with the selected character's crafting discipline,
- usable at the selected character's current crafting rating.

Character-specific information determines whether the selected character can currently discover or craft the recipe. Recipe ownership itself is account-wide.

Recipes learned through mechanisms other than normal ingredient discovery must not be presented as discoverable recipes when they are not eligible for the Discovery system.

---

# 35. Discovery Candidate Rule

A recipe may appear as a discovery candidate only when:

```text
recipe is discoverable
AND recipe is not unlocked account-wide
AND selected character has the required crafting discipline
AND recipe.minimum_rating <= character.rating
```

Once any character unlocks the recipe, it must no longer appear as undiscovered for other characters on the account.

Additional Guild Wars 2-specific discovery restrictions should be documented if identified.


# 36. Discovery Cost

The discovery helper should distinguish:

```text
materials already owned
```

from:

```text
materials that must be purchased
```

The displayed cash requirement should represent the additional money necessary under the selected settings.

Owned materials may still have economic opportunity cost.

---

# 37. Discovery Profit

Discovery may display an economic comparison between:

- resulting item's Trading Post value,
- consumed materials,
- purchased materials.

However, discovering a recipe has a primary non-profit utility:

```text
unlock recipe + gain crafting progress/XP
```

Therefore a negative immediate crafting profit does not make a discovery invalid.

Profit is informational rather than an eligibility rule.

Any Trading Post sale revenue in this profit comparison follows section 25.

---

# 38. Discovery Level Relevance

Recipes close to the current crafting rating may be more useful for crafting progression than significantly lower-level recipes.

If future versions rank recipes based on leveling efficiency, that ranking must be modeled separately from pure economic profitability.

Do not combine:

```text
best profit
```

and:

```text
best crafting XP/progression
```

into a single undefined score.

---

# 39. Account Data Freshness

Guild Wars 2 API state may lag behind actions performed in-game.

Examples include:

- newly discovered recipes,
- newly consumed materials,
- changed inventory.

Therefore a valid domain result is always based on:

```text
latest synchronized account snapshot
```

rather than guaranteed real-time game state.

The UI should eventually make snapshot freshness visible where relevant.

---

# 40. Price Data Freshness

Trading Post prices represent a synchronized price snapshot.

Profit calculations should be understood as:

```text
profit based on available price snapshot
```

not a guaranteed future transaction result.

Prices may change between analysis and actual purchase/sale.

---

# 41. Separation of Feasibility and Profitability

The following are different questions:

```text
Can this item be crafted?
```

```text
Can this item be crafted without buying anything?
```

```text
Can this item be crafted within the user's budget?
```

```text
Is crafting this item profitable?
```

```text
Is crafting it better than selling the materials?
```

These concepts must not be represented by a single boolean.

Future domain models should preserve them separately.

---

# 42. Blocked Reasons

A crafting path may fail for different reasons.

Relevant domain reasons include at least:

```text
NO_RECIPE
BUYING_DISABLED
DAILY_LIMIT
CYCLE_DETECTED
PRICE_UNAVAILABLE
RECIPE_NOT_ALLOWED
INSUFFICIENT_BUDGET
NON_TRADEABLE_MATERIAL
```

`NON_TRADEABLE_MATERIAL` is section 2.1.1's material restriction: the requirement could only be met
by consuming a material that is not tradeable on the Trading Post while that calculation was asked
not to use such a path. It is a chosen restriction, not a missing price and not a missing recipe, so
it never replaces `PRICE_UNAVAILABLE` or `NO_RECIPE` and never appears while the option is enabled.

All of these are represented explicitly in the implementation (`craft.BlockedReason`) and are assigned by the planner rather than only declared (`docs/KNOWN_PROBLEMS.md` §3.5).

The domain should ultimately preserve the reason rather than returning only:

```text
cannot craft
```

because different reasons require different user actions.

---

# 43. Economic Result Structure

A useful domain-level crafting result should conceptually contain:

```text
recipe
output item
output quantity

craftable count

owned materials consumed
items recursively crafted
items purchased
blocked requirements

cash purchase cost
opportunity cost
output revenue
profit per craft
total profit

resolution / dependency tree
```

This structure should exist independently from how the UI displays it.

---

# 44. Resolution Tree

A crafting result should be explainable.

Example:

```text
Greatsword
├── Blade — CRAFT
│   ├── Iron Ingot — INVENTORY
│   └── Mithril Ingot — BUY
│
└── Hilt — CRAFT
    └── Wood Plank — INVENTORY
```

The resolution tree is important because a final number alone does not explain:

- why buying is required,
- which owned materials are consumed,
- which intermediate items are crafted,
- why a path is blocked.

The calculation engine should therefore remain capable of producing such an explanation.

---

# 45. Ectoplasm Salvage Domain

The Ectoplasm calculator currently models:

```text
Glob of Ectoplasm
    ↓ salvage
Crystalline Dust + Luck
```

Relevant parameters include:

- ectoplasm acquisition cost,
- expected Dust per Ectoplasm,
- expected Luck per Ectoplasm,
- Dust sale value.

Trading Post sales of recovered materials use net proceeds under sections 25 and
46. The fee policy is shared; the expected salvage-yield model remains distinct.

---

# 46. Ectoplasm Net Cost

Economic net cost per Ectoplasm is acquisition cost minus expected recovered
Dust value after section 25's 15% deduction. Calculate expected gross recovered
value from the unchanged expected Dust yield and selected gross Dust sale price;
do not round each Dust unit into a modeled sale transaction before scaling yield.

Profit is expected recovered sale value minus the 15% fee and acquisition cost.
Section 47 retains its existing fee-inclusive economic net-cost basis for Luck.
These economic costs are distinct from displayed market prices: Dust quotes
and gross recovered sale values remain gross. Apply no selling fee to acquisition
cost or Luck, and do not deduct fees twice. Preserve instant/listing sale modes,
instant/order acquisition modes and existing yields. Expected values are not
guaranteed integer drops.

---

# 47. Cost per 1000 Luck

Conceptually:

```text
ectos_required
    =
1000 / expected_luck_per_ecto
```

and:

```text
cost_per_1000_luck
    =
net_cost_per_ecto
    ×
ectos_required
```

Expected drop-rate parameters must be treated as configurable/empirical assumptions rather than absolute game guarantees.

---

# 48. Domain Invariants

The following rules should eventually be enforced by automated tests.

### Inventory must not become negative

```text
remaining_inventory >= 0
```

### Material quantities must not be duplicated

The same owned item quantity cannot satisfy multiple independent needs within one simulation.

### Required quantities must be conserved

For a fully resolved requirement:

```text
requested quantity
=
inventory quantity used
+
crafted quantity used
+
purchased quantity
```

subject to explicit handling of recipe overproduction.

### Money must not appear from missing price data

Unknown prices must never become zero-cost purchases.

### Recursive resolution must terminate

Cycles must not cause infinite recursion.

### Recipe restrictions must be respected

Recipes outside the allowed set must not be silently used.

### Buying-disabled mode must not buy

If buying is disabled:

```text
purchased quantity = 0
purchase cost = 0
```

### Budget must be respected

If a positive purchase budget exists:

```text
purchase cost <= budget
```

for any result reported as feasible.

---

# 49. Example Domain Scenario

Assume:

```text
Recipe: Fancy Sword

Output:
1 × Fancy Sword

Ingredients:
2 × Metal Bar
1 × Wooden Handle
```

The user owns:

```text
1 × Metal Bar
10 × Wood
```

Available recipes:

```text
Metal Bar:
2 × Ore → 1 × Metal Bar

Wooden Handle:
3 × Wood → 1 × Wooden Handle
```

The planner should reason:

```text
Fancy Sword
│
├── Metal Bar ×2
│   ├── 1 from inventory
│   └── 1 crafted
│       └── Ore ×2
│           └── buy or inventory
│
└── Wooden Handle ×1
    └── crafted
        └── Wood ×3
            └── inventory
```

The resulting economic cost consists of:

```text
opportunity cost:
1 owned Metal Bar
3 owned Wood

+

cash cost:
2 Ore, if not owned

=

effective material cost
```

Profit then compares that effective cost against the economic value of the Fancy Sword.

---

# 50. Domain Decision Register

The previously open domain questions have been resolved as follows.

## DQ-001 — Trading Post fees

Resolved UD-011 establishes section 25's 15% profitability model with gross
displayed prices; transaction-rounding and sale-grouping differences are outside
the required application model.

**Status:** DECIDED

---

## DQ-002 — Craft-vs-buy optimization

Choose the valid acquisition path with the lowest total effective economic cost.

**Status:** DECIDED

---

## DQ-003 — Multiple recipes

Prefer a valid recipe from the same crafting discipline as the parent recipe. If several remain, choose the economically cheapest valid recipe. If none exist in the same discipline, choose the economically cheapest valid recipe from the remaining options.

**Status:** DECIDED

---

## DQ-004 — Daily crafts

When daily items are configured as buy-instead-of-craft, never craft them.

- With owned materials enabled: consume owned copies first, then buy missing quantities.
- With owned materials disabled: ignore owned copies and buy the required quantity.

**Status:** DECIDED

---

## DQ-005 — Daily account state

The application does not track whether the account has already used the current day's daily crafting allowance.

**Status:** DECIDED

---

## DQ-006 — Character inventory scope

All character inventories are included in the logical owned-material pool.

**Status:** DECIDED

---

## DQ-007 — Bound items

Account-bound materials may be used by any eligible character. Soulbound materials may only be used by the character to which they are bound. Items that cannot be sold on the Trading Post do not receive normal Trading Post opportunity cost.

**Status:** DECIDED

---

## DQ-008 — Non-tradable materials

Use crafting cost when possible, otherwise vendor sell value, otherwise `0`. Items falling back to `0` must be explicitly marked as unvalued non-tradable requirements.

**Status:** DECIDED

---

## DQ-009 — Excess intermediate output

Reuse intermediate leftovers within the same crafting simulation, including across multiple executions of the final recipe. Do not assign separate economic credit to leftovers remaining after the plan finishes.

**Status:** DECIDED

---

## DQ-010 — Discovery recipe ownership semantics

Normal recipe unlocks are account-wide. Character-specific information determines whether the selected character can currently discover or craft the recipe.

**Status:** DECIDED

---

## DQ-011 — Ectoplasm Salvage fee model

`EctoView` remains the sole Ectoplasm Salvage implementation. The Product Owner request `ecto-salvage-profit-include-tp-fees.md` supersedes UD-002's no-fee decision: apply §46 to the four Ecto-buy/Dust-sell scenarios and the derived Luck costs. Replace the fee-exclusion warning with a short notice that displayed results include Trading Post selling fees. Raw price quotations remain distinguishable from net sale proceeds. This decision is scoped only to this view.

**Status:** DECIDED


# 51. Rules for Future Implementation Work

The rules in this document are authoritative domain requirements unless a section explicitly identifies itself as descriptive current behavior.

When implementation behavior conflicts with a defined domain rule:

1. report the conflict,
2. create or update tests for the intended domain behavior,
3. change the implementation toward the defined rule,
4. avoid unrelated refactoring in the same change.

Claude must not reinterpret a defined rule merely because the existing implementation behaves differently.

If a new ambiguity is discovered that is not covered by this specification, it should be documented as a new domain question before implementing a behavior that materially affects calculation results.


# 52. Relationship to Tests

Each stable domain rule should eventually have one or more automated tests.

Example:

```text
DOMAIN RULE:
Owned materials are consumed before purchases.

TEST:
Given 7 owned units
and requirement of 10
when buying is enabled
then:
7 come from inventory
3 are purchased.
```

The domain specification therefore acts as the source for future characterization and acceptance tests.

---

# 53. Relationship to Claude

Claude may use this document as the authoritative source for intended domain behavior.

The following distinction must be maintained:

```text
DEFINED RULE
```

A behavior explicitly specified in this document. It may be implemented and tested.

```text
CURRENT BEHAVIOR
```

A behavior observed in the existing application that is documented only for compatibility or migration purposes.

If implementation and a DEFINED RULE conflict, Claude should report the conflict and implement toward the DEFINED RULE unless explicitly instructed otherwise.

Claude must not invent new domain rules to resolve material ambiguities. Newly discovered ambiguities should be documented before they are implemented.


# 54. Status

This document defines the current intended domain behavior of the GW2 Tool.

It was reconstructed from:

- existing application behavior,
- existing source code,
- project documentation,
- explicit domain decisions made during the redesign process.

The previously identified domain questions DQ-001 through DQ-010 are resolved and incorporated into the normative sections of this specification.

This document should now serve as the primary domain reference for:

- characterization tests,
- regression tests,
- refactoring decisions,
- architecture work,
- Claude coding-agent instructions.

Future changes to business behavior should update this specification before or together with the corresponding implementation change.
