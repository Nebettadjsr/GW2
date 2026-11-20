# GW2 Tool — Domain Specification

## 1. Purpose

### Product risk policy

Read-only GW2 API keys provide account identification and ArenaNet game-data access. Apply the proportionate handling policy in [UD-018](archive/agent/user-decisions/UD-018-proportionate-gw2-key-handling.md); high-sensitivity credential infrastructure is not a product requirement. Account separation and correct calculations remain required. Cached GW2 data is disposable and re-fetchable under UD-016. [Architecture](ARCHITECTURE.md#5-account-access-credentials-and-deletion) owns storage and transport choices.

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

### Supported browser viewport

**Product Owner revision, 18 November 2026; STORY-COV-006 (#243).** This replaces the
17 November blocking proposal. Crafting Profit, Discovery, Ecto Salvage, System Status,
Bank, Materials and Characters use a desktop layout with a **1024 CSS pixel** baseline.
Below that viewport width, show this informational notice above the actual tool content:
**"This page is not optimized for mobile use. You can scroll horizontally to use the whole page."**
Do not include "yet" or imply that mobile optimization is planned.

Keep tool content available on narrower screens using horizontal scrolling over the desktop
canvas. The notice must fit the viewport. Do not hide, disable, make inert or defer a tool
because the viewport is narrow. Initial narrow tool URLs use normal account restoration,
authorization and data loading. At 1024 pixels and wider, omit the notice. Determine its
visibility from the viewport, not the user agent. Dedicated phone/tablet tool layouts and
small-screen usability optimization are not product requirements.

Width changes alone must not disconnect, clear keys/drafts/reservations, change the route,
reset selections or inputs, discard pending answers, duplicate calculation/proof/refetch
work, or automatically close/reopen native dialogs. Native modals retain ordinary explicit
Close/Escape behavior. Actual navigation, account expiry, grants and refresh retain their
existing semantics. The notice is presentation only, not an account-access boundary.

Keep desktop keyboard access, focus, zoom and short-height scrolling usable. Home, its guide
tabs, Legal notice, Privacy and Cookies & browser storage continue to reflow at narrow widths
without the tool's wide canvas or notice. Merely opening those public pages issues no account
requests or retained-key restoration.

---

# 2. Domain Objectives

The application supports three calculation use cases, complemented by account inspection,
material reservations, account controls and a public guide. Sections 55–59 describe those
additional product workflows.

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

## 2.1.0 Currency requirements (owner decision, issue #372)

Recipes may consume currencies (for example 30 Research Notes for Piece of Dragon Jade). Currency ids are a separate namespace from item ids; requirements are typed, exact and per craft.

- First stage: show separate currency costs and a conditional gold margin; verified conversions come later. Random/general conversion, custom per-currency gold values and salvage estimators remain deferred; the only implemented exceptions are the fixed-yield Peer Reviewed Research production of Research Notes and the approved Research Note estimate described below.
- An owned wallet balance decides what is already available; an unknown owned balance is not zero, and feasibility may still be funded by a verified production source. Valuation decides its economic cost. An unavailable wallet is unknown, never zero or unlimited, and must not disable item-only workflows.
- An unpriced currency is never valued at zero. A gold margin with unpriced currencies is conditional, not a complete profit. Each currency is listed with its exact backend quantity; apart from the approved Research Note estimate below, no gold value or break-even estimate is shown or invented (owner direction, 19 Nov 2026). Owner-requested presentation: the overview marks only the total-profit cell with a small caution asterisk (title names the currencies) and keeps the normal profit colors; the selected-result Calculation grid lists each currency between Own materials and Bought materials (counted crafts, or one craft when the economics are hypothetical) and states "Excluding costs for other currencies" below the fee note; Crafting resolution lists each node's own currency units (backend-counted per node, including nested and blocked attempts) beside its ingredient requirements, never as an item.
- Currency quantities and gold valuation basis stay separate, so a valuation never changes wallet demand. A currency is never valued from the recipe being evaluated.
- The cached wallet is read only for a request whose validated session context permits `WALLET_READ` (wallet grant and endpoint); the raw GW2 key is not needed or resent for reads, and a key without the grant for the same account never sees or uses the wallet. For a keyed read both the session and the presented key's proven credential scope must permit `WALLET_READ` (Profit and Discovery, table and detail alike); keyless reads keep the validated session authority. A wallet never published is unknown (not zero); a fully published wallet without a currency's row is a known zero (`INSUFFICIENT_CURRENCY`). The WALLET source follows the configured account freshness policy, joins the calculation identity/freshness only when authorized, may be refreshed on demand, and never blocks item-only rows: missing/stale/failed wallet leaves currency recipes inspectable with unknown feasibility. A balance older than the configured account max-age at the calculation's evaluation instant is not affordability evidence: it is withheld from the planner (unknown, not the old balance) while the stale WALLET source still demands refresh; a balance exactly max-age old is still fresh.
- A route that spends an unpriced currency (itself or nested) has only a partial gold cost: it never wins a cost comparison against a fully valued purchase or craft route, and remains the conditional choice only when no fully valued feasible route exists. Exact Notes spending and wallet feasibility are unchanged.
- **Research Notes can be crafted (owner decision).** When a recipe's Research Note (currency 61) demand exceeds what the captured wallet and surplus from earlier batches of the same plan provide, the shortfall is sourced by crafting Peer Reviewed Research (item 104934; recipes 15120 and 15131, both normal recipes). One such item immediately credits exactly 150 Notes (ArenaNet "Easier Acquisition of Research Notes"); this fixed yield is a small verified rule (`CurrencyProduction`), not parsed from descriptions, not random salvage and not a Trading Post sale (the output has no quote and is never sold). Batches are `ceil(shortfall/150)`; unused Notes stay available to later crafts of the same plan. Source order is: owned wallet, plan surplus, new batches. The batches go through ordinary recipe selection and every normal rule (known/eligible characters, own/reserved materials, buying, cumulative budget, daily/depth/cycle limits); a rejected attempt rolls back materials, wallet use, surplus credit and costs. The economic cost of crafted Notes is the actual full material/cash cost of the batches, charged once; owned wallet Notes are valued only by the approved estimate below (and stay unpriced when no valid estimate exists). The gold margin is conditional (star/exclusion note) only while some consumed currency is still unpriced (another currency, or wallet Notes without a valid estimate); a plan whose Notes were all crafted or estimated is not conditional. An unknown wallet contributes nothing: a verified production path may still cover the whole requirement, otherwise the craft stays blocked/unknown. Other currencies have no verified production path.
- **Research Note estimate (owner-approved exception, issue #372).** Wallet Notes are valued at an estimated replacement cost derived by the backend from both actual Peer Reviewed Research recipes (15120 and 15131; 150 Notes per item): each is priced from complete ingredient quotes in the calculation's captured price set using the selected buy-price mode, independent of owned stock (owned materials do not become free). The cheaper fully priced variant is the basis (no averaging or threshold); both variants are reported in selected-detail data even when one is unavailable. If neither can be completely priced the Notes stay unpriced and the margin conditional - never zero. Only Notes consumed from the existing wallet are valued; Notes from newly produced batches (and surplus reused from them) are already charged by the batches' actual full cost and are not valued again. Source order, batching/surplus, protections, wallet authority/freshness, affordability and rollback are unchanged; valuation consumes no cash, budget or materials. The per-Note rate stays fractional: the wallet-Note count is multiplied by the batch cost and divided by the batch yield, and the aggregate is rounded up once to whole copper (per first craft and for all counted crafts separately; never per Note), with overflow guarded. The amount is deducted from per-craft and total profit and counts in route cost comparison; it is separate from own-item and bought-material totals. Presentation: the Research Notes row shows the exact quantity and, on a second indented row, a small "Estimated value" button beside the estimated deduction; the button opens a native dialog with one side-by-side card per actual recipe (ingredient quantity, icon, wiki-linked name and total cost; full-batch cost and a backend-computed 100-Note cost of ceil(batch cost * 100 / notes per batch), unavailable while not completely priced; cheaper basis marked); the overview shows only a small "≈" beside the total profit and keeps normal profit colors. The exclusion warning remains only for costs not included: the backend carries the exact `unpricedCurrencyIds` and the overview names only those currencies, never estimated or crafted Notes. Scoped price relevance always includes both estimate producer recipes (15120, 15131) and their ingredients, independent of recipe knowledge, so their quotes are requested, covered and fingerprinted. Random salvage, custom rates and generalized conversion remain deferred.
- Implemented: typed requirements are stored (`recipe_non_item_requirements`), carried through graph/planner/transport/display, and spent transactionally from one wallet pool (rolled back with failed alternatives). Recipes with an unsupported requirement kind (e.g. guild upgrades) are never loaded, so they cannot be costed as free or item-only.

## 2.1.1 Crafting Profit result presentation

For the human-facing names used for Trading Post quote sides and crafting values, see the canonical [Crafting Glossary](crafting/GLOSSARY.md). This specification remains authoritative for their domain meaning; the glossary explains how those concepts appear in the interface.

The web view must preserve useful information hierarchy and interactions. The
comparison table must include recipe/item, craftable
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
right-hand detail panel that remains visible while scrolling; adapt within the supported
desktop viewport contract above. Show applicable buy cost prominently with
cost wording and the negative/cost visual treatment, a clearly labelled Trading
Post price for one output item, useful recipe and shopping/material information,
and a GW2 Wiki link when a reliable URL can be constructed. Remove a redundant
Result summary and explanatory text when the same information is already clear.
Crafting Profit resolution explains the complete output quantity represented by
the selected result. Its requested quantity is based on the selected row's
`craftableCount` (completed recipe executions) multiplied by the recipe's
`outputCount` (items produced per execution). The backend resolver must resolve
those executions against the real inventory, settings, eligibility, prices and
recursive recipes; the frontend renders the resulting quantities without
reconstructing them. Crafting Discovery may continue to explain one output
batch because it has no selected Profit result quantity. See
`ARCHITECTURE.md` for the transport and resolution contract.
For a Profit row with zero counted crafts, the tree may show its first blocked
attempt and must identify that basis rather than implying a counted output.

Crafting Profit and Discovery search match output names, direct ingredient names,
and possible indirect ingredient dependencies through any number of static recipe
relationships. Search only filters recipes already returned in the current
calculation result; it does not request additional calculations. A possible
dependency match does not mean the resolver actually crafted or consumed that
intermediate: search ignores inventory, character eligibility, buying settings,
resolver path selection and profitability. This search behavior does not alter
either feature's calculation rules.

Crafting Resolution uses compact summaries and progressive disclosure. Each
requirement shows item identity, required quantity (for example, "20 needed"),
backend-provided sourcing labels such as "From stock", "Crafted" and "Bought",
and the supplied crafting character under "Crafted by" where applicable. Preserve
multiple sourcing methods when supplied; do not force a mixed requirement into
one label. Child ingredient groups start collapsed at every level, including the
root's children. Users can expand each group independently to inspect the complete
ordered nested structure; collapsed presentation must not discard requirements.

Remove the permanent introductory explanation beneath "Crafting resolution".
Normal nodes omit bookkeeping rows for stock/crafted/bought/missing quantities,
producing recipe and recipe ID, crafts run and produced total. This does not hide
meaningful blocked/unavailable explanations. Diagnostic values remain in the
backend/API contract without adding visible technical-detail sections. The normal
Profit and Discovery trees show, for each requirement, `Value per item` and
`Total value for N item(s)` on separate lines. The total is the backend's unchanged
`effectiveCostCopper` field. The per-item figure is the backend-projected
`effectiveUnitCostCopper`, a display-only average of that total over the node's
`requestedQuantity`, rounded to whole copper HALF_UP and marked `≈` when
`effectiveUnitCostApproximate` is true. It is null (shown `—`) when the total is
missing or the quantity is not positive, and is not a Trading Post quote, resolver
input or sum of children. The total is the
economic cost of everything consumed by that requirement: the opportunity value of owned sellable
materials plus the actual acquisition cost of materials that must be bought. It is inclusive of
descendants exactly once, so a parent includes a crafted child's cost without adding the child's
materials again. Owned bound/non-tradeable materials use the recursive economic valuation in
section 11.2 rather than an automatic zero; unavailable valuations remain unknown. The selected root covers the full counted quantity
and reconciles with Calculation's total Own materials plus Bought materials. Do not add a per-node
technical disclosure for the cash, opportunity and effective values; those remain available in the
backend/API contract, with missing distinct from known zero.

The normal selected detail should omit the redundant "THIS RECIPE IN THAT FRESH
CALCULATION" summary. Preserve the backend-owned fresh resolution and its
response association. For Profit, resolve the selected detail row's full
counted output quantity, while keeping the table's earlier values intact; the
detail response remains a fresh calculation and can differ when captured inputs
change between requests. Do not imply that a zero-count row has counted output.

For a valid selected result still present in the fresh calculation, display the
actual backend resolution tree; a failed explanation request is not a substitute.
Preserve the legitimate fresh-candidate absence and error behavior defined by
that contract. When buying is enabled, show the materials still to buy for the
already calculated craft count, with their quantities, the selected acquisition price per item
actually used by the calculation, and the authoritative total purchase cost. Do not show both raw
Trading Post quote alternatives in this shopping list. Do not display a separate "FOR ONE FURTHER
CRAFT" purchase section.

Selected details must not expose generic resolver labels such as "Blocked",
"Not blocked", "Recipe not allowed", "Buying disabled", "Daily limit" or
"Recipe loop" in the normal presentation. Keep the original row, state and
blocked-reason codes in the backend/API for diagnostics and tests; do not render
expandable Technical details sections in the crafting detail panels or tree. In the
resolution tree, describe an item with no usable recipe path as "Not craftable"
and an item with no usable Trading Post quote as "Not available on
TP"; link each named requirement to its GW2 Wiki article. A failed or incomplete
resolution is not proof that no crafting path exists; use "Not craftable" only
when no usable recipe path is present. Do not distinguish
why a quote is unavailable in the normal view. Keep the supplied source
quantities visible as From stock, Crafted and Bought. These labels describe the
resolution data, not a new acquisition decision. Preserve the distinction
between limitations on further crafting and crafts already counted.
Remove the introductory sentence "Crafting opportunities the backend calculated
for the selected scope, with the profit it reported for each."

Place the three result filters and the search control inside the Calculation
controls panel, in a compact Displayed results subgroup distinct from the
Calculation subgroup. Do not retain a separate top-level Result display
section. Provide these filters:

- Hide items with craftable count 0, enabled by default.
- Hide recipes not allowed, enabled by default.
- Hide recipes with profit per craft <= 0, enabled by default.

Place the changeable maximum displayed recipe count, initially 250, and Show all
beside the results table title, because they directly govern how many matching
rows that table displays. Show all removes the display limit for the full
matching set. This placement is the confirmed Product Owner decision and
supersedes earlier placement requirements that put these two controls inside
the Calculation controls panel.

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
accessibility semantics, errors and necessary recipe-specific domain information.

The normal comparison table and Selected Result panel present actionable crafting
and economic information. They do not display generic row/domain state labels,
codes, explanations or context derived from those states, including unknown states
and unavailable-result markers. The raw supplied row state remains available in the
backend/API for diagnostics and automated tests. Preserve the semantic meaning of all row
states in section 42. Keep item-specific sourcing and blocking information in the
Crafting Resolution tree, attached to the affected requirement or item.

Keep UI/request/data-state messages: loading, API/request errors, empty-result
messages, missing/null-data representation, and notices explaining why a selected
row is hidden by search, filters or the display limit. Unknown or missing values
must remain distinct from zero and success. Preserve all backend reasons and their
domain meaning without requiring generic row-state prose in the normal view.

Crafting Profit always uses the normal acquisition rules for each required material.
Usable owned inventory may be consumed under the binding rules; an allowed crafting
path may produce the requirement; and a normal Trading Post purchase may supply it
when buying is enabled and a usable quote exists. If none of these paths satisfies
a requirement, the parent craft is unavailable. Absence from the synchronized
`tp_tradeable_items` list or `/v2/commerce/prices` ID list is not itself a reason
to reject an ingredient. A missing usable purchase quote still follows the normal
`PRICE_UNAVAILABLE` behavior. This is the former non-Trading-Post control's enabled
behavior, made permanent by the Product Owner's 2026-09-29 decision; the control and
its restrictive blocked reason have been removed. Crafting Discovery continues to
use the same normal resolver behavior.

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
Follow the shared
frontend UX guidelines with grouped controls, a scan-friendly comparison list
and a dedicated selected-result detail area.

Let the user select a character and an available crafting discipline, using
backend-provided character/rating facts and the established eligibility rules in
sections 34-35. The backend must exclude recipes above the character's applicable
rating, already-known recipes under account-wide ownership semantics, and recipes
that cannot be learned through normal ingredient-combination discovery, including
vendor/scroll-only recipes. The browser must not reimplement these rules.

Discovery evaluates exactly one discovery attempt for each candidate using the
selected character for discipline, eligibility and owned inventory. There is no
independent inventory-character selection, cumulative purchase budget, or
craftable-count concept. One attempt may produce multiple output items; use the
recipe's output quantity when valuing its result.

Show item/recipe name, recipe level, materials-to-buy cost, output sell value and
profit for the one attempt from authoritative backend values. Offer sorting by
recipe level, materials-to-buy cost, output sell value and profit, plus
search/filtering to narrow the list.
Keep leveling relevance separate from profitability per sections 37-38; do not
invent an XP/profit score or make negative profit a discovery exclusion rule.
Preserve valid scope, search and sort selections on refresh under section 2.2.1.

Reuse applicable owned-material, buying and Trading Post price-mode controls.
There is no Discovery budget control; this does not change Crafting Profit's
max-buy behavior. Use the selected character as the sole inventory context.
Selection uses recipe identity, not table position. Where supported by supplied
data, details show recipe information, material requirements, owned/missing
quantities, buy requirements/costs, the resolution tree, shopping/material lists
and the same actionable item statuses as Crafting Profit. Follow the fresh-detail
consistency contract in ARCHITECTURE.md; do not derive missing economics.

Use the application-wide icon strategy when available, established signed money
formatting/treatments, keyboard-operable selection and clear loading, empty,
blocked and error states. Keep raw backend diagnostics secondary.

## 2.3 Ectoplasm Salvage Analysis

Answer:

> What is the effective economic cost of salvaging Glob of Ectoplasm for Luck after accounting for the value of recovered materials?

This feature is separate from the general crafting planner.

The web page calculates expected salvage economics locally after loading item metadata, Ecto and
Crystalline Dust Trading Post quotes, and the account's Luck/Magic Find read model. Changing the
Ecto amount, salvage method or exact tool, or either TP price mode updates the result without another
calculation request. A manual TP refresh rereads only those two items' prices; it does not require
reloading metadata or account Luck.

Consumed Ectos retain their market value even when already owned. Instant Buy or Buy Order selects
the unit price used for that consumed value, and the page explains this choice. Expected Dust value
uses the selected Instant Sell or Listing Sell quote after the 15% TP selling fee. The selected tool's
coin usage cost contributes to effective Luck cost; Black Lion Gem cost is displayed separately and
is not converted to coin. The result separates Ecto value consumed, tool cost, net Dust value and
effective cost. Magic Find targets use the same selected yields, tool and TP valuation, while the
target table distinguishes Ecto value consumed from effective cost. Salvage yields are statistical
averages, not guaranteed drops. The shared TP price warning describes the unit-quote quantity
limitation in section 20.

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

## 9.1 Protected materials in Crafting Profit

The user can protect items independently in Bank and Materials. With protection enabled, only
the owned quantity in the selected store is unavailable to Crafting Profit. The same item can
be protected in Bank while its material-storage quantity remains usable, or vice versa;
selecting both protects both stores. Character inventory is unaffected by these selections.

A reservation either excludes ALL units of the item in that store or keeps a fixed finite
quantity. For example, 300 owned with a target of 250 leaves 50 usable; stock of 200 leaves
none usable; later stock of 400 leaves 150 usable. The saved target does not shrink to current
stock and survives low stock or absence. It is subtracted once per item and store across
duplicate stacks before aggregation. It is independent of section 42's 2,500-unit plan-demand
cap. A target accepts whole quantities from 0 through 2,147,483,647; zero clears it. An invalid
entry or manual stack total outside this range leaves the previous valid reservation unchanged.
Where an ALL selection and a finite target overlap, ALL wins.

**Bank stacks (Product Owner decision, 2026-11-16; approved and shipped).** Clicking one Bank
stack changes only that stack's allocation: a fully reserved stack becomes unreserved; an
unreserved or partially reserved stack becomes fully reserved. Other current stack allocations
stay unchanged, and their new sum becomes the fixed item target. Numeric entry allocates the
target across current same-item stacks in ascending Bank slot order: four stacks of 250 with
target 300 reserve `[250, 50, 0, 0]`. If the player rearranges or splits stacks, preserve the
item's reserved amount and redistribute it across its current stacks, rather than attaching
the reservation permanently to an old slot.

To preserve the clicked distribution while the observed layout still matches, requests may
carry `protectedBankAllocations`: a complete guarded group per item of
`{slot,itemId,reservedUnits,observedCount,binding,boundTo}`, including zero allocations for all
current positive-count rows. Only a complete matching current layout uses those hints. A
change to item, count, binding, owner or row membership falls back to the fixed target in
ascending current slot order. Reserve trusted rows before binding/owner classification; do
not treat client hints as inventory facts. Materials item-wide actions and legacy/bulk ALL
selections retain their store-wide meaning. The store-specific scope was confirmed on
2026-11-08; the subsequent stack correction does not change Materials behavior.

Protection restricts owned-stock availability; it does not ban the item from the recipe graph. Only when the "Allow buying" checkbox is enabled may the calculation buy replacements, under the existing prices, budget and acquisition rules. Existing intermediate-crafting rules still apply without consuming protected owned copies, including indirectly through subrecipes. Do not remove or overwrite actual cached inventory to apply protection.

Disabled protection or an empty saved selection preserves ordinary calculation behavior. List results, selected-result details, quantities, costs and shopping lists must use the same effective protection settings, including finite quantities and Bank allocation hints. Any calculated-result identity/cache must distinguish those settings. Discovery and Ecto behavior are unchanged.

Selection and activation preferences are remembered per verified GW2 account on the same browser across restarts; cross-device synchronization is not required. They must never leak between accounts. The autosave, quantity and preset rules belong in `FRONTEND_UX_GUIDELINES.md`, section 2.1.

Every material-storage entry remains selectable. In Bank, hide the protection checkbox only when the shared recipe catalog is complete, the item is neither a normal recipe ingredient nor a material-storage catalog entry, and the public item flags explicitly exclude Mystic Forge use (`NoMysticForge`). Unknown metadata or incomplete recipe coverage must not hide a checkbox. Bank bulk actions affect only currently displayed selectable item types; stored selections for absent items and the other storage page remain unchanged. This eligibility does not depend on the current character, learned recipes or calculation filters.

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

They do not receive a direct Trading Post resale opportunity cost. Their consumed economic value must nevertheless follow section 11.2; account binding is not a reason to assign zero crafting-input value.

### Soulbound items

Soulbound materials may only be used for crafting by the character to which they are bound.

A soulbound item held by or bound to another character must not be counted as usable inventory for the selected character.

They do not receive a direct Trading Post resale opportunity cost. Their consumed economic value must nevertheless follow section 11.2; soulbinding restricts usability, not whether a value can be established.

## 11.2 Non-Tradable Material Valuation

Non-tradable materials are valued using the following fallback order:

1. If the item has a crafting recipe, use its calculated crafting cost as its economic value. The recipe may be considered for valuation even when it is not currently unlocked by the account.
2. If the item cannot be crafted, use its vendor sell value if one exists.
3. If neither a crafting cost nor a vendor sell value exists, assign an economic value of `0`.

The crafting-cost calculation in step 1 is recursive and follows the same economic-cost rules as other crafting calculations. Sum ingredient quantities times their unit values and divide by recipe output quantity for the produced unit value. Resolve unpriced child ingredients recursively, falling back to vendor sell values where crafting cannot establish a value. This applies to owned account-bound and soulbound inputs, including daily materials such as Lump of Mithrillium. Being already owned or time-gated does not make their material inputs free.

This is valuation, not an instruction to purchase or actually recraft owned stock: actual buying still requires "Allow buying". Do not add a crafted item's value and its ingredient costs twice. Economic values must reconcile between the tree, Own materials and total profit. Detect cycles and unresolved leaves rather than treating an unresolved cost as a fully priced recipe. Do not invent a gold price for the daily cooldown itself.

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

The GW2 API price response supplies current buy/sell unit quotes but not enough market depth across
price levels to establish the execution price for a requested quantity. Calculated Trading Post
purchase costs and sale proceeds therefore extrapolate a unit quote across that quantity. The
available quantity at the quoted price may be insufficient, and a large transaction may cross
multiple price levels. Calculation pages must disclose this limitation and advise users to check
current prices and available quantities in-game before committing significant gold.

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

Crafting Profit and Discovery monetary results are calculated in the backend/domain
and deduct fees exactly once. Ectoplasm Salvage is the explicit exception defined
in sections 2.3 and 46: its fee-adjusted economics are calculated locally by the
browser using the same domain fee policy.

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
Use the total costs of the accepted resource simulation: owned-material use can
vary across executions as inventory is consumed, so the first execution's
opportunity cost is not multiplied to form the total. Apply the fee per
execution on the same basis as per-craft profit; UD-011 requires no separate
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

Section 42's separate material-demand and numeric-range bounds can stop the plan sooner.
The 250-craft simulation cap, the 2,500-unit per-material demand bound, and the changeable
250-row display limit are three different limits.

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

Keep the target candidate set separate from the resolver's intermediate recipe set. Discovery candidates contain only undiscovered recipes eligible for normal ingredient discovery by the selected character. Ingredient resolution may use recipes already known account-wide and undiscovered recipes that are themselves normally discoverable and usable by that character. A recipe's mere presence in the global graph does not make it usable; unknown non-discoverable recipes remain excluded. The final target may be undiscovered because Discovery evaluates that one attempt.

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

`allowDailyCrafts` controls only whether the planner may execute an available daily/time-gated
craft. It does not enable buying. An enabled daily craft may be performed at most once per output
item in one planning state; the recipe's full output batch is produced. Normal parent and higher-
level crafting then continues. Any further required tradable materials can be bought only when
`allowBuying` is enabled.

When `allowDailyCrafts` is false, the planner does not execute a daily/time-gated recipe. It may
still consume an already-owned daily item when `useOwnMats` is true, or buy the requirement when
`allowBuying` is true. With both unavailable, the parent craft is blocked naturally.

The settings remain independent: disabling owned materials prevents owned inventory from
satisfying any ingredient; disabling buying prevents Trading Post purchases at every level; and
neither changes whether recursive non-daily crafting is available.


# 33. Owned Daily Materials

Owned daily-limited items are ordinary owned inventory for the purpose of consumption.

When `useOwnMats` is enabled, existing owned copies may be used before purchases.

When `useOwnMats` is disabled, existing owned copies are ignored, regardless of daily-craft or
buying settings.

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

Show snapshot freshness where it affects the result, using the established compact
updating/error presentation rather than a routine timestamp paragraph on every inventory
page. Sections 2.2.1 and 57 own selection preservation and automatic refresh behavior.

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
MATERIAL_LIMIT
VALUE_RANGE
```

All of these are represented explicitly in the implementation (`craft.BlockedReason`) and are
assigned by the planner rather than only declared. `NONE` is the additional nonblocking enum value.

For Crafting Profit rows, `resultAvailable` and `blockedReason` are separate
contract fields. Their meanings are:

| Value | Meaning |
| --- | --- |
| `resultAvailable = false` | The backend supplied no calculated result. Numeric nulls remain unavailable; this is not a zero result or a successful unblocked result. |
| Available result with null/unreported `blockedReason` | A result exists, but no state was supplied. No conclusion about blocking or success may be inferred from the missing state. |
| `NONE` | No blocking reason was reported. It does not itself promise that any craft was counted; `craftableCount` carries that information. |
| `NO_RECIPE` | A required item had no usable recipe path. |
| `BUYING_DISABLED` | A required item could not be bought because buying was disabled. Other allowed acquisition paths are independent. |
| `DAILY_LIMIT` | A required daily/time-gated craft or amount could not be performed under the applicable daily allowance. Already available daily materials and successful crafts remain valid. |
| `CYCLE_DETECTED` | The attempted recipe dependency path depends on an item/recipe already active in that path. |
| `PRICE_UNAVAILABLE` | A required purchase quote was missing or unusable. A missing price is not a zero price. |
| `RECIPE_NOT_ALLOWED` | A recipe was rejected by recipe/allowed-set restrictions or coordinated character eligibility. |
| `INSUFFICIENT_BUDGET` | The next required purchase exceeded the configured maximum-buy constraint. The constraint applies to the purchase path, not to the value of already counted crafts. |
| `MATERIAL_LIMIT` | A candidate recipe plan would require more than 2,500 units of one material item ID (see "Material demand bound" below). |
| `WORK_LIMIT` | The root's deterministic speculative-work budget was spent (see "Work budget" below). The outcome is limited, never exact. |
| `VALUE_RANGE` | A quantity, copper price, total, fee or profit in the attempt fell outside the supported range of the calculation; nothing is wrapped, clamped or invented. |
| Unknown/future code | Preserve the supplied code as an unknown state. Do not map it to `NONE`, zero, success or a known reason. |

**Work budget.** One root evaluation may make at most 50,000 craft-step attempts (`PlanState.MAX_WORK_UNITS`),
counted across every rollback, nested ordinary/Forge candidate, owner trial, seed-preserving alternative, budget
retry and currency/hypothetical retry (a copy of the state inherits the count; a new root starts fresh). The count is
deterministic (attempts, never time) and is never refunded by rollback. Once an attempt is refused, any plan assembled
from that root is rejected as `WORK_LIMIT`, even if buying could fill the gap, because unevaluated alternatives make it
non-exact. The root keeps its fully accepted, exactly valued prefix of crafts and its actual output quote. With no
accepted craft, cost, profit and total fields are `null` (unknown), never `0` and never a single-craft margin from
partial inputs; `blockedReason: WORK_LIMIT` is the limitation status. Cancellation still interrupts the worker.

**Material demand bound.** One candidate recipe plan may require at most 2,500 units of any one
material item ID. Demand is the total of every ingredient requirement for that item ID in the plan
(repeated sibling and recursive requirements, and all accepted Profit batches), whether the units
are owned, surplus-reused, crafted or purchased. Each candidate recipe has independent accounting,
and a speculative alternative that is abandoned releases its demand. Multiplication, ceiling
division and accumulated demand are validated before stock is consumed, a price applied or a
requirement descended into; the first excess ends that attempt at once. An excess rejects the whole
affected root attempt (never a truncated recipe) and rolls back inventory, surplus, daily
allowances, costs and demand; a valid alternative recipe or direct purchase that fits is still
preferred. Profit keeps earlier fully accepted batches as a smaller valid plan, the failed next
attempt adding nothing (the 250-craft maximum is unchanged); Discovery, being one attempt, is
blocked entirely. Independently, every copper product, sum, comparison, revenue, fee and profit is
computed exactly and must fit the supported 32-bit copper range; otherwise the attempt is blocked
as `VALUE_RANGE` rather than wrapping, clamping or reporting a fabricated profit.

A non-`NONE` reason may explain why further crafting stopped after a positive
`craftableCount`; those already-counted crafts remain valid. When the count is zero,
the same reason describes why no craft could be completed. A null count remains
unknown rather than zero. These meanings are domain semantics and do not require
the normal Crafting Profit table or Selected Result panel to print explanatory copy;
see section 2.1.1 for the display contract.

The domain should preserve the reason rather than returning only:

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
- Dust sale value,
- selected salvage tool's coin cost per use and any separately shown Gem cost.

Trading Post sales of recovered materials use net proceeds under sections 25 and
46. The fee policy is shared; the expected salvage-yield model remains distinct.

---

# 46. Ectoplasm Net Cost

Economic net cost per Ectoplasm is acquisition cost minus expected recovered
Dust value after section 25's 15% deduction. Calculate expected gross recovered
value from the unchanged expected Dust yield and selected gross Dust sale price;
do not round each Dust unit into a modeled sale transaction before scaling yield.

The displayed effective Luck cost also includes the selected salvage tool's coin usage
cost. For a selected batch, it is consumed Ecto value plus tool coin cost minus expected
Dust proceeds after fees. Any Gem usage is an additional separately displayed cost,
not converted into coin or silently treated as included in that coin result.

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
(net_cost_per_ecto + selected_tool_coin_cost_per_use)
    ×
ectos_required
```

Expected drop-rate parameters must be treated as configurable/empirical assumptions rather than absolute game guarantees.

Magic Find targets use the account's remaining Luck and round the required number of
Ectos up to a whole Ecto. Use the same selected yields, price modes and salvage tool as
the main result; distinguish consumed Ecto value from effective cost after recovered
Dust and tool costs. Missing account Luck does not mean zero consumed Luck.

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

### Material demand and numeric values must stay within their bounds

Enforce section 42's 2,500-unit demand bound before expanding an excessive requirement.
Quantity and copper arithmetic must not wrap, clamp or create negative purchase costs.
An out-of-range attempt is rejected and rolled back; earlier accepted batches stay valid.

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

`allowDailyCrafts` independently controls whether one available daily craft operation may be
performed per output item in a planning state. When disabled, daily outputs are not crafted.
Owned stock remains governed only by `useOwnMats`, and Trading Post purchases remain governed only
by `allowBuying`.

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

The browser Ecto Salvage page is the canonical Ectoplasm Salvage implementation
and uses the frontend-owned calculation defined in section 2.3. The Product Owner
request `ecto-salvage-profit-include-tp-fees.md` supersedes UD-002's no-fee
decision: apply section 46 to the four Ecto-buy/Dust-sell scenarios and the derived
Luck costs. Displayed results include Trading Post selling fees while raw price
quotations remain distinguishable from net sale proceeds.

The obsolete JavaFX `EctoView` and the former backend-owned Ecto calculation flow
are not requirements; see `ARCHITECTURE.md`. This decision
remains scoped to Ectoplasm Salvage.

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

The domain questions DQ-001 through DQ-011 are resolved and incorporated into the normative
sections of this specification. Their historical records provide rationale; this document
owns the rules that continue to apply.

This document should now serve as the primary domain reference for:

- characterization tests,
- regression tests,
- refactoring decisions,
- architecture work,
- Claude coding-agent instructions.

Future changes to business behavior should update this specification before or together with the corresponding implementation change.

---

# 55. Consumed Account Luck and Luck-Derived Magic Find

Consumed Luck belongs to the GW2 account identified by `/v2/account`'s stable `id`. The authenticated `/v2/account/luck` response is the cumulative consumed amount; its empty array represents zero. A missing or malformed response is an error, not zero. Consumption can exceed the amount required for the 300% Luck-derived Magic Find cap; the cumulative consumed amount remains visible while the derived percentage and remaining-to-cap value stay capped.

The canonical global progression is the exact [GW2 Wiki Luck progression table](https://wiki.guildwars2.com/wiki/Luck#Progression), represented by the cumulative Luck threshold for every integer Luck-derived Magic Find percentage from 0 to 300. The 0% threshold is zero and the 300% threshold is 4,295,450. This progression excludes Magic Find from achievements, equipment, guild bonuses and other sources.

For nonnegative consumed Luck, the current Luck-derived percentage is the greatest percentage whose cumulative threshold has been reached, capped at 300. Before the cap, the next threshold is the threshold for current percentage +1; remaining Luck is that threshold minus consumed Luck. At the cap there is no next +1% threshold, and remaining Luck to the cap is zero. For any later target through 300%, its cumulative requirement comes from that target's table row, and remaining Luck is the nonnegative difference from the consumed amount.

---

# 56. Character Overview and Details

**Build reference metadata (STORY-CHAR-009, delivered).** Saved builds are enriched at read time with globally shared, deduplicated names/images for referenced specializations, traits, skills and pets, and with the swap skill related to each STRING legend id (legends have no native name/icon). Product Owner decision: specializations show full trait rows, so the metadata also holds the minor/major choice ids of the specializations actually used (no full catalogs). Metadata is repaired only inside the existing optional build refresh (stored references before, and those newly stored after, the account publication, outside its transaction); an ordinary read never calls upstream. Failure or missing data leaves valid builds published with explicit `resolved:false` references. Images are served at `/api/resources/{specializations|traits|skills|pets}/{id}/icon/{key}.{ext}`, reusing the item image cache and validation.

Provide an account-scoped character overview and a selectable character detail view. The overview presents concise character information; equipment inspection is the primary goal, followed by inventory and builds. The user must be able to identify which characters have ascended/legendary gear and which equipment slots contain lower-quality gear or are empty, without inferring an optimal build or prescribing purchases.

Equipment details cover armor, weapons and other equipped items, with item identity, slot and actual rarity. Determine quality from item metadata, not the equipped skin or icon color. Distinguish an empty slot from missing, unavailable or stale data. Do not count equipment-template references to one item as multiple owned copies or feed duplicate references into the crafting inventory pool.

The Product Owner approved the roster, shared character summary, Equipment/Inventory and
centered Builds presentation on 2026-11-08; exact layout and sorting are owned by UX
guidelines §2.2. Playtime, deaths, creation date and next birthday come from the account-scoped
backend projection. Overview sorting defaults to descending playtime and must not cause
per-character detail downloads. Builds shows saved land/aquatic skills and complete
specialization trait choices, with selected traits distinguished from other choices.

The attribute block is a backend projection of level-80 land base attributes plus the inspected equipment, including supported intrinsic stats, upgrades, infusions and shared Jade Bot equipment. Keep weapon-set projections distinct. Apply only explicitly modelled passive build effects from the same character's saved active build with compatible parent coverage; never parse arbitrary trait text as a rule or claim a complete combat simulation. The initial reviewed passives include Necromancer Spiteful Fortitude and Death Perception. Unmodelled or incompatible build data leaves equipment values available with incomplete calculation metadata. User options select PvE/WvW and Reinforced Armor (off by default because the API cannot observe the buff). Food, boons, temporary effects, shroud and kill stacks are not presumed active. Backend diagnostics retain these limitations; normal tooltips show useful item effects rather than technical caveats. Matching one character's screenshot does not establish exact in-game parity for every build.

Inventory shows the selected character's items and quantities as a **bag layout with empty slots** (Product Owner confirmed 2026-11-07): bags in position order, each installed bag with its container and capacity, every capacity-defined cell occupied or empty, and null/uninstalled bag positions shown exactly as the upstream snapshot supplies them (no inferred locked or purchasable slots). Repeated item IDs in different cells are independent stacks. Presentation (STORY-CHAR-011): the selected character's Inventory view has no facts column (the character summary is shared above the Equipment/Inventory tabs, see UX guidelines): one continuous ten-column grid of the installed bags' slots in supplied bag and slot order in the centre (no per-bag headings or grids; holes and duplicates preserved; original bag and slot coordinates kept), and a compact vertical bag-position rail on the right with the supplied container icons, uninstalled positions marked honestly, and names/capacity in accessible labels. The selected character's name is the detail page title, preceded by a small local base-profession glyph, with the subtitle `Lvl {level} {race} {active elite specialization or base profession}`; the elite is named only from the character's single upstream-active saved build tab and a unique resolved elite specialization of that character's own profession whose build coverage matches the current `CHARACTERS` parent generation, otherwise the base profession is shown. Routine freshness prose is not shown (the underlying source and timestamp state is unchanged). The layout is read-only. Layout is served only from a `CHARACTERS` generation that published a bag manifest; an older generation without one is reported as refresh-required, never as an empty inventory. Equipment never contributes inventory quantities. Builds and equipment expose all saved, available tabs supplied by the API, in their in-game slot order. On initial entry, select the currently active in-game tab for each section. The user can inspect another tab without changing the character's actual in-game active build/equipment; these are read-only views. Build and equipment tabs have independent selection and active-state information.

Story is deferred by Product Owner decision (2026-11-07). Equipment, inventory and builds remain the current scope. Do not substitute backstory choices or partial quest history for the deferred Story Journal.

Discovered recipes are excluded from this view. Map completion is deferred pending a later Product Owner decision. Public sharing, exports, editing in-game equipment/builds and a build-optimization engine are not requested by this feature. Screenshots illustrate the desired overview-to-details interaction; they do not implicitly require every field/control shown there.

Reuse verified-account access and backend-owned GW2 calls. Character reads/refresh failures
and missing grants must not appear as an empty roster or empty equipment. Preserve useful
last-synchronized data with clear freshness/error state where appropriate. The retired
pre-release capacity-measurement programme is not a character-feature acceptance requirement.

---

# 57. System Status Roles and Automatic Inventory Freshness

**Own-account saved-data overview (2026-11-11).** The generic status response may carry `accountOverview` for the verified session's own account only: `characters` (published character rows), `recipes` (distinct IDs in the account-wide unlock table, never global, automatic, discovered or character-only knowledge) and `materials` (`count` = distinct item IDs with positive quantity, `total` = 64-bit sum of positive quantities). Each source is included only if the live proof's grants **and** URL restrictions permit its operation, and is evaluated independently from its `account_source_state` marker and rows in one read-only REPEATABLE READ transaction: no marker = `NOT_LOADED` (stray rows ignored); marker with no rows = known zero; `CURRENT`/`STALE` uses the configured `gw2.account-data.max-age-ms` and preserves the completion time; a query failure is `UNAVAILABLE`, never zero. No Bank/inventory rows, upstream calls, writes or owner/cross-account aggregates are involved, and opening Status triggers no synchronization.

System Status has an ordinary account view and an owner/admin view. Ordinary users can manage their own API-key connection, see the age/state of their account data and explicitly refresh their account sources. They may see generic shared-system health labels (for example healthy, updating or having issues), not privileged diagnostic payloads or global administration controls.

Only the Product Owner's verified GW2 account may use owner administration: detailed shared-system diagnostics and explicit global refresh controls for the full GW2 item catalog, full recipe catalog and all Trading Post prices. Full TP refresh means the applicable tradable catalog, not only a requesting user's currently demanded items. Define further admin actions explicitly when requested; this does not authorize account impersonation or disclosure of other users' keys. Owner permissions apply at the backend, including direct endpoint/task/diagnostic access, not just button visibility.

Owner shared-data coverage (revised 2026-11-10): beside each full-catalog action the owner sees honest read-only coverage of the stored shared rows. Items: total stored, rows with a fetch time, known-only placeholder rows without one; recipes and Trading Post prices: stored unique rows. Oldest/newest fetch are `MIN/MAX(fetched_at)` over timestamped rows only; they are recorded per-record fetch times, not job completion times and not proof that a full refresh succeeded. Trading Post counts are stored price entries, not currently available offers (an unavailable item may be a row with absent sides). A failed query is reported as unavailable, never zero. The owner also sees aggregate account counts only: total stored accounts and accounts whose last authorized use (`last_seen_at`; sync completion does not count) is within 60 minutes of one captured instant, boundary inclusive. No identifiers are returned. These aggregates are read only after owner authorization.

Full-catalog semantics (Product Owner decision, 2026-11-08, STORY-STATUS-004): the owner's full item and full recipe refresh re-download the detail of **every** ID in one captured authoritative ID snapshot (`/v2/items`, `/v2/recipes`), including unchanged IDs, so in-place edits are picked up; coverage-only reconciliation is not the behavior. Items absent from the snapshot are removed after all batches validated, except those a `recipe_ingredients` row still references (kept as disposable referential metadata until a later run). Full Trading Post refresh snapshots every ID in `tp_tradeable_items` regardless of cached price age. The ordinary scheduler's cheap unchanged-ID skip and Profit/Discovery's source-scoped, ten-minute demand are unchanged.

Bank, Materials and Characters must not expose manual refresh or saved-reload buttons. Show the saved account-scoped view immediately when available; retain its source timestamps internally without routine freshness paragraphs. Automatically request permitted stale sources while retaining the old view and showing a compact updating hint. Keep equipment and the inspected saved template visible throughout refresh. Replace data after successful publication; unknown task outcome is not success. On failure retain the prior view with stale/error information and direct users to System Status. Missing data triggers the initial-load path rather than a misleading empty inventory.

Navigation refreshes only page-relevant sources: Bank uses BANK; Materials uses MATERIALS;
Characters uses CHARACTERS and required optional character details; Crafting Profit uses its
required recipes, Bank, Materials and Characters; Ecto uses Luck. Opening Characters does
not refresh recipes or materials. The explicit full-account operation remains in System
Status. Shared administration and its diagnostic responses enforce the owner boundary at
the backend; opening a page never grants administrative authority.

Use the same freshness/admission principles as Crafting Profit. Coalesce same-account refresh work across views, respect grants and source coverage, and avoid retry loops or per-view background jobs. Exact freshness/retry policy follows the existing contracts and must be explicit in the implementation issue. Account changes must clear old account data and prevent stale responses from being displayed under the new account.

## 57.1 Owner application activity and log downloads

Application activity is a separate owner-only view, not a public diagnostic endpoint or a
load-generation tool. It reports observed request counts, failures, latency summaries,
database size and distinct verified accounts with eligible foreground activity in the
trailing five minutes. Keep that five-minute estimate distinct from the stored-account
and last-60-minute account counts. Startup, interrupted observation, unavailable history
and truncated data must remain explicit; they are not known zero or capacity estimates.

Observe actual application requests without introducing visitor heartbeats. Session,
system/owner, health, asset requests and task-status polling do not create application
activity records. Persist minute aggregates only for minutes containing observed activity;
idle periods create no empty history records. Stored aggregates do not contain account
IDs, character names, IP addresses or API keys. In-memory identities used to count
distinct foreground accounts are not exported.

The owner chooses one retained UTC day or week from a readable dropdown, newest first
within each group, with the newest available day selected initially. The server supplies
each file's interval, filename, size and partial/open/closed/pruned/truncated state.
Download exactly that server-described CSV; do not ask the owner to enter raw timestamps
or silently substitute another interval if the chosen file has expired. The normal view
loads when opened or explicitly refreshed, not through a background polling timer.
Storage limits are described in [Architecture](ARCHITECTURE.md); current host logging
settings are in [Current state](CURRENT_STATE_SPEC.md).

---

# 58. Account Connection, Saved Data and Deletion

No separate application sign-up, email or password is required. A read-only GW2 API key
proves the ArenaNet account and determines which permitted game-data operations are
available. Identify saved data by the verified stable account ID, never by a client-chosen
account ID or the key string. A different valid key for the same account reaches the same
saved game data; another account cannot read or act on it. Respect both key permissions
and any upstream URL restrictions.

The browser retains the key for its tab session so navigation and reloads do not require
another paste. A fresh tab normally starts without it; duplicated or restored tabs may
retain it. Clearing cached images/files alone does not reliably remove it. The backend
uses the key transiently for proof and necessary upstream work and does not persist it
in the database or application logs. Storage and transport details belong to the
architecture document.

An authorised application session lasts at most 15 minutes after proof (and no longer
than an upstream credential expiry). A retained valid key can renew it on demand. Key
revocation at ArenaNet prevents a new proof, but an existing app session can remain valid
until its lease expires. Replacement, disconnect and account change discard old visible
account state and reject late responses associated with it.

**Disconnect** forgets this tab's key and account state and requests the end of its server
session. It does not delete cached database data or browser material preferences. If
server logout fails, local disconnect still takes effect and the failure is disclosed.

**Delete cached data** is an explicit, confirmed action for the verified current account.
It removes that account's cached database records, invalidates all of its current app
sessions, clears its saved material reservations/preferences in this browser, and signals
other same-origin tabs to stop using the deleted account. Other devices' local preferences
must be cleared there. Deletion does not change the GW2 account, revoke its ArenaNet key,
delete shared game data or erase aggregate operational history/provider records. Later
explicit connection can fetch the account again; there is no automatic re-proof/retry
that silently repopulates deleted data.

Unfinished account-refresh work prevents deletion until it finishes. A server-rejected
deletion leaves database data and sessions unchanged. A denied, failed or unknown response
must not clear local preferences or report success; another attempt requires fresh
confirmation. Do not infer the server's final state from an unknown network outcome or a
cancelled dialog. Account caches
have no automatic inactivity expiry and remain until explicitly deleted or the disposable
cache is cleared. No backup, archive or preservation process is required for these caches.

Saved game data includes the verified account ID, Bank and Materials items, unlocked
recipes, character names/profiles/disciplines, inventory/equipment/builds, account Luck
and source/activity timestamps. Account IDs and character names are account-linked data,
not a guarantee of anonymity. Do not claim the site processes no personal data merely
because it does not ask for real names or email addresses.

---

# 59. Public Home, Guide and Information Pages

Home is the default landing page at `/welcome`. It welcomes players, explains who the
tools are for, outlines the available features and account-to-crafting workflow, and
offers personal GW2 beginner tips. The guide is an approachable account of useful
experiences, not an exhaustive game manual or a promised gold-per-hour farming route.
Its reading tabs and screenshot layout follow `FRONTEND_UX_GUIDELINES.md` §2.5.

Home and the footer's Legal notice, Privacy and Cookies & browser storage pages are
readable without a connection. Opening them must not restore a retained key, fetch
account data or start account synchronization. Normal navigation preserves already
mounted account screens and their useful selections/results.

Legal information identifies the operator and contact, describes the project as an
independent unofficial fan project with no ArenaNet/NCSOFT affiliation or endorsement,
credits the rights holders of game content, and links the public source repository,
licence and contribution channels. The application licence does not relicense game
artwork or other third-party content. Privacy/storage explanations must match actual
key/session retention, account-linked caching, deletion, hosting, contact handling and
limited operational monitoring. Advertising trackers and third-party analytics are
not part of the current product; do not imply a consent banner is needed for trackers
the site does not use.

Only Home is intended for search-engine indexing. Account/tool and legal/storage pages
remain excluded. The crawler policy permits the exact `/welcome` document and required
static assets while disallowing other paths, and the application sets the corresponding
index/noindex page metadata. Crawler directives express indexing preferences; they do
not make public legal pages private or replace account authorization.

## Mystic Forge recipes

- Crafting Profit is ordinary crafting only. `allowMysticForge` no longer gates anything: a stale client or saved `true` is ignored at the service boundary (`CraftingSettings.ordinaryOnly`) and never re-enables Forge roots or nested Forge routes in Profit/Discovery. Forge is planned only by the dedicated exact-target project below.
- **Exact Forge project (`POST /api/mystic-forge/project`, `craft.ForgeProjectPlanner`).** One selected target, no budget, no per-root loop, no profit maximisation. Producer choice is a deliberate deterministic structural policy, not an optimiser: an ordinary recipe first, then a Forge recipe, then a verified vendor purchase, with a deterministic item/recipe-id tie break; it never searches for the globally cheapest acquisition (ordinary Crafting Profit's cheapest-route rules are unchanged).  Fixed-output roots take the desired TOTAL output (already-owned root output is consumed first); random/expected roots and Clover references take batches. Owned stock keeps its binding pools (sellable, account-bound, per-owner carried and Bank namespaces) and is consumed through `PlanState.consumeInventoryWithBinding`: a Forge step uses every owner's Bank-bound stock plus the carried stock of ONE deterministic holder; an ordinary step uses only its deterministic assigned eligible character's carried and own Bank stock (eligibility from roster ratings; unlocked from the account's known recipe ids). A locked or unassignable ordinary step still lists its inputs but is `performable=false`. A currency absent from a KNOWN wallet map is exactly 0; only a null wallet is unknown. A quote with a null/zero/negative sell price is unpriced, never free; unpriced leaves keep their siblings and counts. Node partition: `needed = fromOwned + fromSurplus + toCraft + toAcquire` (planned crafts are never owned). Self-seeded expected roots use ONE external seed and guaranteed-minimum reuse only: gross guaranteed `b*min`, reused `(b-1)*min(seed,min)`, external `seed + (b-1)*(seed-min(seed,min))`, guaranteed net = gross - reused, expected net = `b*mean - reused` (Gossamer 2 batches: 1 external, 20 gross, 1 reused, 19 net, 36.84 expected; Vicious Claw 50 batches: 1, 250, 49, 201, 296.50). Promotion economics (backend only) compare BUYING every direct recipe input and instantly selling the outputs (a deliberately simple structural path, not a globally cheapest acquisition search; owned stock is never free and changes only remaining shopping): inputs cost their valid Trading Post ask, else a verified vendor purchase (cash counted once, currencies such as Spirit Shards listed separately and never converted to gold), else they are unpriced; a self-seed counts only its external units. Proceeds use the BID (instant sell); expected gross `floor(expected net x bid)` (fixed outputs: bid x exact output count), 15% fee once over the gross half-up via `TradingPostFeePolicy`. `marginCopper` = net - gold input, null when any input is unpriced (then `goldInputCopper` is a known subtotal plus `unpricedInputs`); it is conditional whenever a currency or unpriced part exists, even if the vendor item is currently owned. `marginOnShoppingCopper` subtracts only remaining shopping and must never be shown as profit. For a fixed-output equipment project the API replaces both with full-path figures from actual consumed quantities: `marginOnShoppingCopper` = net proceeds - remaining purchases (cash only, owned inputs excluded) and `marginCopper` = that, minus the known `ownValue` once when valueOwn=Yes (null when own value or any leaf price is unknown); the direct-input buy-all comparison above remains for promotion batch roots. Gossamer 1 batch (bid 100/ask 110, silk 2c x250, seed 110c, dust 10c x5, Stones via Shards): gold input 660, gross 1892, fee 284, net 1608, margin 948 conditional; 2 batches: 1210, 3684, 553, 3131, 1921. Fixed-output conversions get exact-count economics with no yield label; Clover references never get economics. **Research Notes in a project.** Currency 61 demand of the whole selected how-to (ordinary + Forge steps) is aggregated once. A KNOWN wallet pays `min(needed, wallet)` once; the shortfall needs `ceil(shortfall/150)` Peer Reviewed Research containers (`CurrencyProduction`), owned containers first, the rest crafted through the cheaper eligible fully priced of recipes 15120/15131 (else the cheapest priced, else the lowest id, shown locked/unpriced - never executable by claim), sharing this project's inventory, surplus and binding rules (`notes` in the project DTO; production is planned work). A null wallet stays unknown: no production count or deficit is promised. Only wallet-used Notes get the approved replacement-cost estimate (aggregate rounded up once, overflow checked; unavailable with an issue when no variant is completely priced, never 0); produced Notes are charged by their inputs and never also valued. Other currencies stay separate. A path reaching Research Notes always requests both variants' ingredient quotes regardless of stock or unlock. An expected self-seeded root's tree quantities (`needed`/`toCraft`) are the NET guaranteed output (Gossamer 2 batches: 19; gross 20 stays in seed facts and step production). An overflowing shopping line total is unavailable (null) in an incomplete project, never an HTTP error or a saturated value. **Exact integer range:** every Forge quantity and copper value is checked against 2^53-1 (`ForgeProjectPlanner.mul/add/sub`, not just `Long`): a product, nested multiplication or cumulative sum beyond it stops the plan as incomplete (`quantity exceeds the supported range`, `goldCostPartial` true), keeps the safe subtotals, nulls economics and an unavailable guaranteed output, and never rounds or clamps. Any range stop (comparison, own-value or cost/margin sum) leaves no authoritative full margin or profit (null), only safe subfacts. A node also carries `produced` (units the step actually makes/buys, e.g. 10 Philosopher's Stones for 5 needed) and `newSurplus` (kept for later demand, never paid twice). Research Note containers are reported separately as owned (to open), planned crafted (locked when the recipe is not unlocked) and acquire-only (no producer: they claim no Notes and no surplus); the shortfall is stated BEFORE production (`notes.shortfall`) while the Research Note currency line's `missing` (Shopping "Still to acquire") is the authoritative UNCOVERED demand AFTER planned production (`notes.uncoveredNotes`; null for an unknown wallet); Note production steps/nodes precede every craft that consumes the Notes, `surplus = max(0, owned+crafted Notes - shortfall)`. The client keeps a real incomplete result (output or `resultAvailable`) even without a tree; only a no-result answer is pending. Mystic Runestone (79418) is a verified vendor purchase of 10000c per unit (not its 330c sell value) with a Scholar of Secrets hint. **Forge menu settings and owner-selected routes (issue #378).** `craft.ForgeSettings` (request fields `priceMode`, `useOwn`, `valueOwn`, `daily`, `promotions`, `reduce`; absent = defaults INSTANT_BUY/yes/yes/CRAFT/allow/YES, unknown values are HTTP 400) is echoed as `settings` and is part of the project source identity. Instant buy = ask, Buy order = bid; a missing quote is unknown. `useOwn=false` plans intermediates without owned stock (`true` includes every eligible owned unit, Bank/Materials reservations included). `valueOwn` only adds `ownValue` and never changes consumed quantities: consumed input stock keeps its pools and is valued once through the ordinary section 11/11.2 rules (`OwnedValuer`/`CraftingResolver.ownedValuer`): sellable copies at the instant-sell bid independent of the acquisition price mode, bound copies (and unquoted sellable ones) at recursive crafting cost then vendor sell value regardless of recipe unlock, aggregate rounded up once with exact arithmetic over the 2^53-1 range; unvalued items stay explicit (never free), nothing is bought, and a range overflow stops the project as incomplete (`own-material value exceeds the supported range`). Daily outputs (`DailyCrafts`) are Craft (producer kept, daily/time-gated hint) or Buy (owned stock then purchase of the shortfall, no recursive crafting). Promotions=Do not allow removes only producers classified by `CraftingResolver.isPromotion` (guaranteed-gain self-seeded Forge promotions), never the selected root, required fixed gifts (Gift of Fortune/Mastery/...) or the Clover attempt; Allow plans each verified self-seeded promotion (`isPromotion`) with GUARANTEED NET output only: `net(b) = b*min - (b-1)*min(seed,min)`, batches `1 + ceil((units-min)/(min-min(seed,min)))` (Gossamer min10/seed1 for 20 units = 3 batches, net 28, not 2 with net 19; Vicious Claw min5/seed1, 50 batches = net 201); the seed is acquired externally once, expected means are never inventory, and a fixed producer for the same item always wins over a promotion. Do not allow leaves the purchase/acquisition leaf. A project with any estimated Clover (`clover` present) publishes no guaranteed output; each Clover node's `crafts` is the typed attempt count charged by that demand (aggregate delta: two one-Clover demands = 4 + 3 attempts). A whole-amount buy total (`unitBuy x target`) outside the exact range makes the project incomplete (`quantity exceeds the supported range`, no guaranteed output, null total/savings). `reduce` is a root-target policy: NO / YES (all owned) / TRADABLE (sellable pool) / UNTRADABLE (character, Bank and account-bound pools). `comparison` is backend-only: unit buy/sell quotes, whole-amount buy total and savings (null when anything is unpriced; `conditional` with currencies/estimates; `excludesOwnValue` when valueOwn=No). **Owner decision superseding the earlier acquire-only nested Clover rule for projects:** a nested Mystic Clover shortfall is planned as an explicit ESTIMATE of the one-Clover Forge attempt (1 Obsidian Shard 19925, 1 Mystic Coin 19976, 1 Glob of Ectoplasm 19721, 6 Philosopher's Stones 20796) at the wiki's about 31% (`ceil(missing*100/31)` attempts, rounded up once for the aggregate: 18 missing = 59 attempts = 59 each Obsidian/Coin/Ecto and 354 Stones = 36 purchases/360 units/36 Spirit Shards). Allowed owned Clovers are consumed first; inputs go through the shared inventory/vendor/currency path and are charged once with direct needs; expected Clovers are never owned or guaranteed stock, no failure byproduct is credited, and `clover` marks the estimate. Global fixed-yield validation and the reference catalogue are unchanged. Forge-project-only vendor facts (`VendorAcquisitions.forgeProjectRecipes`, not ordinary Profit/Discovery): Obsidian Shard from Tactician Deathstrider for 2,100 Karma (currency 2), Bloodstone Shard from Miyani for 200 Spirit Shards; vendor nodes carry the WHERE text used as the crafting-step merchant instruction.
- Forge recipes are a separate source (`RecipeSource.MYSTIC_FORGE`) from the bundled backend catalog `mystic-forge/catalog.json`, generated offline from the ArenaNet-hosted GW2 Wiki (Semantic MediaWiki `[[Has recipe source::Mystic forge]]`; contributors GFDL 1.3, game assets ArenaNet). The backend never calls the Wiki at runtime. Recipe ids are the negated wiki forge id, so they cannot collide with ArenaNet recipe ids, account unlocks or selected-detail ids; they are never sent to ArenaNet.
- **Vendor acquisitions of Forge inputs.** Philosopher's Stone (10 for 1 Spirit Shard), Mystic Crystal (5 for 3 Shards), Eldritch Scroll (1 for 50 Shards), Bottle of Elonian Wine (1 for 2504c) and Mystic Runestone (1 for 10000c) are the verified vendor purchases (`craft.VendorAcquisitions`, mirrored by `verified-facts.json`; no general vendor engine). They are synthetic recipe candidates (ids at or below -1,000,000,000, never roots, unlocks, Discovery candidates or ArenaNet requests) used by a Forge project as the last producer after owned stock and plan surplus (an exact project needs no flag or Profit budget). Purchases are whole batches charged once: surplus is reused by later crafts of the same plan, Shard needs are recorded as unpriced currency 23 with the exact quantity against the known wallet (`owned`/`missing`, both null when the wallet is unknown, never zero or unlimited); a project is never blocked or rolled back by missing currency; vendor cash is bought-material cost in the project's gold total. The project node (`NodeDto`) is a bought acquisition (`recipeSource = VENDOR`, batches in `crafts`), and shopping lists the full batch bought, priced from the vendor cash rather than a Trading Post quote. Forge reference item ids missing from the items table are fetched through the shared batched item sync on the project's scoped price demand.
- **Catalogue/project flow.** The Mystic Forge destination lists the public cached catalogue (name, type/subtype, tab, search, cached quote); selecting a row plans exactly that recipe and target through `POST /api/mystic-forge/project {recipeId,target}`. There is no browser flag or stored preference, and the request carries no reservation/protection data. Catalogue output metadata (including Weapon/Armor subtype) and tradeable quotes are completed in the background under the ten-minute policy; a first-use or stale account source runs the existing keyed data refresh with a bounded retry, then rereads only the selected project. The detail shows backend expected/min/max and states that only the guaranteed minimum counts as stock.
- A Forge step needs no discipline, rating, unlock or crafting character; ordinary steps nested around it retain their eligibility, bindings, currency and stock requirements. Missing purchases are listed for the exact project without Crafting Profit's buying toggle or budget. Owner decision: a Forge project always counts all owned materials, including quantities reserved or protected in Bank/Materials for ordinary Profit; there is no Forge reservation control, payload or server preference, and ordinary Profit's protections and reservations are unchanged. **Bank provenance:** since 2022-10-18 the Mystic Forge accepts Bank items, including equipment soulbound to another character (GW2 Wiki, Mystic Forge). Character-bound stock in the Bank is therefore kept apart from carried (bag/equipment) stock, whatever its owner, even an off-roster or unknown one, and may feed any Forge step, with an empty or untrained roster too. Ordinary crafting still uses it only for its bound owner. Carried soulbound stock stays usable only by its real owner (a Forge step by that owner may combine it with any Bank-bound stock), never pooled across owners, and carried items are never assumed to be in the Bank. Each physical unit has one debit authority in the plan state, so one Bank copy is never spent by both an ordinary and a Forge route; Forge projects count every owned unit (no protection or reservation removes stock from a Forge plan); rejected routes roll the stock back.
- **Catalog generation.** `python3 backend/tools/mystic-forge/generate_catalog.py` (standard library only) rebuilds `catalog.json` from `backend/tools/mystic-forge/source/` (`raw-recipes.json.gz` SMW rows, `page-templates.json.gz` comment-stripped `{{recipe}}` templates with page revision, `verified-facts.json` hashes and verified promotion means); `--check` fails when the bundled file is stale (the catalog path is pinned to LF in `.gitattributes` so Windows checkouts compare equal). CI's existing workflow-helper step runs the generator's offline unit tests and `--check`. `--refresh` is an explicit, network-only re-capture with finite timeouts, bounded retries and 50-row pagination; any HTTP/JSON failure or short listing aborts before anything is written and files are replaced atomically (an HTTP failure never publishes an empty catalog). After a refresh, update the hashes in `verified-facts.json`. Default tests and CI never call the Wiki.
- **Acceptance.** An SMW row is included only when it has a positive wiki forge id, output id and quantity, exactly the four unique ingredient slots 1..4 with positive ids/counts (repeated materials in different slots add), and exactly one source template on the same page whose four `ingredientN` quantities/names, `quantity` (default 1) and optional `output item id` match it. Rows missing the new fields (older/custom pages), unmatched or ambiguous templates, a duplicated forge id, display-only/average rows (quantity `0.31`) and templates marked random/average/alternative are excluded with counted reasons in `catalog.json` `coverage.excludedByReason`; no ID is guessed. A fixed row on a page that mentions randomness is also excluded (`randomMentioned` is only a conservative exclusion signal, never a classifier). Coverage is the verified subset, not "all Forge recipes".
- **Expected yields.** Template `upper quantity` marks a random output; SMW's lower `quantity` is the guaranteed minimum, the upper quantity the maximum. A mean is attached only for the exact verified Material Promotion shapes: common T5->T6 (`250` lower + `1` seed + `5` Crystalline Dust + Stones, 10-40, mean 18.92), common lower tiers (`250`/`1`/`5` of a tier dust + Stones, 40-190/200, 86.34), fine T5->T6 (`50`, 5-12, 6.91) and fine lower tiers (`50`, 6/7-40, 18.51). Any other random row has no verified mean and is excluded, never fixed or profitable. A min/max range or midpoint is never a mean.
- **Runtime adapter.** `parser.MysticForgeCatalogLoader` is strictly typed (no coercion of `1.5` or `"1"`, positive ids/counts, the mean a decimal string within min..max and `min == outputCount`); an invalid recipe is rejected with a counted reason, all copies of a duplicated forge id are rejected, and a missing/unreadable/wrong-schema resource yields an empty catalog whose `status()` names the problem (logged). Page and revision are exposed through `MysticForgeCatalog.provenance`. The catalog's content fingerprint (SHA-256 over recipes, yields, provenance) joins a project's source identity together with the project catalogue, vendor-fact and reference-recipe fingerprints and the quotes of the project's own path (including both Research Note variants when Notes are reached); there is no enabled flag, and a changed catalogue is a different generation.
- The canonical attribution and full GFDL 1.3 text are the bundled `mystic-forge/NOTICE.md`, which the root `THIRD_PARTY_NOTICES.md` links to; the MIT application license stays separate.
- **Project bounds.** Only the selected project's structure is expanded, with a maximum depth of 24 and 20,000 work nodes; a hit bound is an explicitly incomplete plan. Expected-yield recipes never supply guaranteed intermediate stock. Root batch targets have no fixed-quantity rounding surplus; their seed summary distinguishes gross production, reused seeds and net output.
- Crafting Profit and Discovery, including their shared ingredient search, traverse ordinary recipes only. Forge catalogue search remains in the dedicated destination.
