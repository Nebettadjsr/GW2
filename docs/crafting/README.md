# Understanding the Crafting Calculation

This guide explains, in plain language, how **Crafting Profit** and **Crafting Discovery** decide
what you can craft, what it costs, and whether it is worth it. It is written for a reader who knows
Guild Wars 2 only casually and has never looked at this project's source code.

For glossary terms and short answers to common questions, see [GLOSSARY.md](GLOSSARY.md).

## What this guide is (and isn't)

This is a maintained, human-readable **summary** of the project's authoritative rules. It is
allowed to repeat information that also lives in the documents below — that duplication is an
explicit, intentional exception (`docs/TARGET_ARCHITECTURE.md` §35), made specifically so a reader
does not have to open several technical documents to understand one calculation.

The authoritative sources remain:

- [`docs/DOMAIN_SPEC.md`](../DOMAIN_SPEC.md) — the normative rules this guide explains. Section
  numbers below (e.g. "§22") refer to this document.
- [`docs/TARGET_ARCHITECTURE.md`](../TARGET_ARCHITECTURE.md) §35 — why this guide exists and the
  policy for keeping it up to date.

If anything here ever disagrees with `DOMAIN_SPEC.md`, the spec wins — treat the disagreement as a
documentation bug in this guide, not as a new rule.

This guide covers **Crafting Profit** and **Crafting Discovery** in depth, since they share one
calculation engine. The separate **Ectoplasm Salvage** calculator is mentioned only where it helps
contrast a rule (for example, Trading Post fees); it is not covered end-to-end here.

## The three features at a glance

- **Crafting Profit** — "Of everything I could craft right now, what's actually worth crafting?"
  Scans recipes you (or your account) could use and reports how many times you could craft each
  one and what profit that would produce.
- **Crafting Discovery** — "Which recipes could this specific character discover next, and what
  would that cost?" Discovery is about *learning* new recipes, not about maximizing profit.
- **Ectoplasm Salvage** — a separate, small calculator for the economics of salvaging Globs of
  Ectoplasm for Luck. Not part of the shared crafting engine described here.

## Recipes, disciplines and crafting rating

A **recipe** turns some ingredients into an output item (possibly more than one copy of it — see
[Craft count, produced quantity and requested quantity](#craft-count-produced-quantity-and-requested-quantity)).
The same item can have more than one recipe that produces it (`DOMAIN_SPEC.md` §5).

Every recipe belongs to one or more **crafting disciplines** (Armorsmith, Chef, Weaponsmith, and so
on) and has a minimum **crafting rating** a character must have reached in that discipline to use
it (§7–§8). These are Guild Wars 2 mechanics, not project choices: a recipe simply cannot be used by
a character who lacks the discipline or hasn't reached the required rating yet.

### Choosing between multiple recipes for the same item

When an ingredient itself has more than one valid recipe, the tool has to pick one. This priority
order is a **project decision** (§30, decision DQ-003), not a Guild Wars 2 rule — the game doesn't
tell you which recipe is "best":

1. Prefer a recipe usable by the same crafting discipline as the recipe that needed the ingredient
   (avoids pointless discipline switching).
2. Among same-discipline candidates, prefer the one with the lowest total effective cost (see
   [Opportunity cost](#opportunity-cost-of-owned-materials)).
3. Otherwise, prefer the cheapest valid recipe from whatever remains.

## Recipe unlocks and recipe knowledge

A recipe existing in Guild Wars 2 doesn't mean it's usable yet. The tool distinguishes (§6):

- recipes that exist globally in the game,
- recipes your **account** has unlocked (an unlock is account-wide, §34 — once any character learns
  a recipe, it counts as known for every character),
- recipes a **specific character** can currently use (needs the right discipline and rating too),
- recipes nobody has discovered yet.

Crafting Profit only offers recipes your account already knows. Crafting Discovery is specifically
about the last category — see [Recipe discovery](#recipe-discovery) below.

## Who can craft what: Profit scope vs. Discovery character

Crafting Profit and Crafting Discovery pick their working set of characters differently (this is a
project decision recorded in `DOMAIN_SPEC.md` §2.2.1, so it applies consistently across both views):

- **Crafting Profit** has one scope selector (its former separate character selector was removed).
  It offers:
  - **`All`** (the default) — coordinate a plan across every synced character.
  - **A specific discipline** (e.g. "Chef") — coordinate a plan across every synced character who
    has that discipline.
  - **A specific character + discipline** (e.g. "Armorsmith lvl 500 — Nbt Anch") — restrict the
    plan to exactly that one character and discipline.
- **Crafting Discovery** keeps its own, separate **character** selector and **discipline**
  selector — it always analyzes one specific character, because discovering a recipe is inherently
  something one character does.

### Coordinated crafting and transferable intermediates

When Crafting Profit's scope includes more than one character (`All` or a discipline), it does not
require the whole crafting tree to be craftable by a single character. Instead it builds **one
coordinated plan** and assigns each crafting step to whichever eligible character can perform it
(§2.2.1). For example: Character A crafts an intermediate part, Character B crafts a second
intermediate part, and Character C combines both into the final item — as long as intermediates are
freely tradeable/transferable between characters (not soulbound), this is a valid, intentional
result, not a bug.

Every step is still checked individually for discipline and rating eligibility — "the plan as a
whole is possible" never overrides "this specific character can actually perform this specific
step."

## Account-bound and soulbound materials

Guild Wars 2 has real binding rules, and the tool respects them (§11.1):

- **Account-bound** materials can be used by *any* character on the account.
- **Soulbound** materials can only be used by the *one* character they are bound to. A copy
  soulbound to Character A can never be used to satisfy Character B's step, even in a coordinated
  `All`-scope plan — and two characters each holding one soulbound copy can't be pooled together to
  satisfy a step that needs two.
- Neither account-bound nor soulbound materials can be sold on the Trading Post, so consuming them
  never has a Trading-Post-based opportunity cost (see below).

This is why coordinated crafting assigns steps to specific characters instead of treating all
account materials as one interchangeable pile: bound materials genuinely aren't interchangeable.

## The owned-material pool: bank, storage and characters

For economic analysis, "materials you own" means the combined total of (§9, decision DQ-006):

```text
owned_quantity(item) = material_storage(item) + bank(item) + all character_inventories(item)
```

This is an intentional project modeling choice for *economic* analysis, not a claim about what
Guild Wars 2's in-game crafting station can physically reach at a given moment. If 500 Wood sits in
one character's bags and the bank is otherwise empty, the tool still counts 500 Wood as "owned" —
even though in-game you'd need to move it to a shared location before crafting with it there. Which
character(s) can actually *use* a given owned unit still depends on binding (above): unbound and
account-bound materials are shared across the whole roster; soulbound materials stay scoped to
their owner.

Within one crafting calculation, an owned unit is only ever counted once. If two different
ingredients in the same plan both need Wood, the simulation consumes owned Wood for the first need
before considering the second — it does not let the same 10 Wood "satisfy" two separate 8-Wood and
5-Wood requirements simultaneously (§10).

## Opportunity cost of owned materials

Owned materials are not economically free just because using them costs no cash. If a material
could otherwise be sold on the Trading Post, consuming it sacrifices that sale — its
**opportunity cost** (§11):

```text
opportunity_cost = owned_quantity_consumed × sell_value_per_unit
```

**Worked example** (illustrative numbers, not a real application run): you own 10 units of an
item worth 1 silver each on the Trading Post. Crafting with all 10 costs you 0 copper in cash, but
its opportunity cost is 10 silver — the amount you gave up not selling them. "Cash spent = 0" does
**not** mean "economic cost = 0." Bound materials (above) are the one exception: since they can't
be sold at all, they carry no Trading Post opportunity cost.

## Buying missing materials and budgets

If "use own materials" leaves a shortfall, you can allow the tool to fill the gap by buying on the
Trading Post (§18):

- **Buying disabled**: an unmet requirement is simply reported as blocked — the tool never invents
  a purchase to make a result look complete.
- **Buying enabled**: missing tradable materials may be bought at the selected buy price (see price
  modes below), and that cost is added to the plan's total.

You can also cap how much cash the plan is allowed to spend on purchases (§19). Zero or a negative
value means no limit. When a positive budget is set, any plan whose total purchase cost would
exceed it is not treated as feasible.

## Allowing or excluding non-Trading-Post materials

Some materials can't be traded on the Trading Post at all — bound items, rewards and similar. The
web Crafting Profit page has a calculation control for them, **"Allow non-Trading-Post materials"**,
which opens switched **on** (§2.1.1, decisions UD-009 and UD-010). It is a calculation setting, not a
display filter: switching it asks the backend for a new calculation rather than hiding rows you
already have.

- **On** (the default, and how the tool has always behaved): such a material may be used when you
  own it or can craft it, under the same binding, buying, budget, daily and scope rules as anything
  else. If a recipe needs one that you neither own nor can craft, the recipe stays unavailable — the
  tool never invents a way to acquire it, because there isn't one.
- **Off**: the calculation only uses paths that consume no such material anywhere in them, including
  owned copies, bound copies and intermediates several steps down. Alternative paths that avoid them
  are still evaluated normally, so a recipe with a second, dearer route that uses only tradable
  materials is calculated along that route instead of disappearing.

Whether an item counts as non-Trading-Post is a fact about the item, taken from the Trading Post's
own list of tradable items — never guessed from a missing price. An ordinary tradable material with
no usable quote is still `PRICE_UNAVAILABLE` (below), not "non-Trading-Post".

Results restricted this way are kept rather than deleted: the recipe stays in the list with the
reason `NON_TRADEABLE_MATERIAL`, and the selected result explains it beside the material it applies
to. The ordinary display filters decide what you actually see. This control applies only to the web
Crafting Profit page — Crafting Discovery and the JavaFX windows are unaffected.

## Choosing between crafting and buying an ingredient

When an ingredient could be either crafted or bought, the tool picks whichever path has the lower
**total effective cost** — cash cost plus opportunity cost — not whichever path spends less cash
right now (§22, decision DQ-002). This matters because owned materials are never treated as "free"
just because using them requires no new purchase.

**Worked example** (from `DOMAIN_SPEC.md` §22, illustrative numbers):

| Option | Cash cost | Opportunity cost | Effective cost |
|---|---|---|---|
| A — craft the intermediate from owned materials | 0 copper | 80 copper | 80 copper |
| B — buy the intermediate instead | 60 copper | 0 copper | 60 copper |

Even though Option A spends no cash, the tool chooses **Option B**, because 60 copper of total
economic cost is lower than 80. The cash figure (60 copper) is still tracked and shown separately,
since it's what you'd actually need to spend.

## Trading Post price modes: instant vs. listing

The Guild Wars 2 Trading Post exposes two prices, and which one applies depends on the transaction
(§20):

| Action | Mode | Price used |
|---|---|---|
| Buying | **Instant buy** — accept an existing sell listing | the *sell* listing price |
| Buying | **Listing buy** — place/match a buy order and wait | the *buy* order price |
| Selling | **Instant sell** — accept an existing buy order | the *buy* order price |
| Selling | **Listing sell** — list the item and wait for a buyer | the *sell* listing price |

These terms are kept explicit throughout the tool (and this guide) because "buy price" and "sell
price" are genuinely ambiguous without saying whose side of the trade you mean.

## Selling fees: why Profit and Ecto Salvage differ

Guild Wars 2 charges a 15% Trading Post fee on sales. The two features intentionally treat this
differently — both are documented **project decisions**, not an inconsistency:

- **Crafting Profit** does **not** deduct the 15% selling fee from output revenue (§25, decision
  DQ-001). `profit_per_craft = output_revenue - purchased_material_cost - opportunity_cost`, using
  the raw selected Trading Post price as revenue.
- **Ectoplasm Salvage** *does* deduct the fee from Crystalline Dust's sale proceeds before computing
  net cost per Ecto and cost per 1000 Luck (§46–§47): `dust_sale_price = selected gross price × 0.85`,
  applied only to the recovered-material sale side, never to the ecto acquisition cost.

If you compare numbers between the two features, remember they are not using the same revenue
convention.

## Unavailable prices and blocked results

A missing Trading Post price is never treated as "free" (§21). If a required purchase has no usable
quote, or a quote of zero or less, the requirement becomes **price unavailable** rather than a
silently-completed calculation. The same principle extends to every reason a crafting path can fail
— the tool keeps the *reason* rather than just showing or hiding a row (§42):

| Status | Meaning |
|---|---|
| `NO_RECIPE` | No valid recipe is available for this item under the current settings. |
| `BUYING_DISABLED` | A shortfall exists and buying is turned off, so it can't be filled. |
| `DAILY_LIMIT` | This item is daily-limited and the "buy instead of craft" behavior applies. |
| `CYCLE_DETECTED` | Resolving this item's ingredients looped back on itself; that path stops rather than recursing forever (§15). |
| `PRICE_UNAVAILABLE` | A required purchase has no usable Trading Post price. |
| `RECIPE_NOT_ALLOWED` | The only recipe(s) found aren't allowed in this analysis (wrong discipline/character/context, §29). |
| `INSUFFICIENT_BUDGET` | Completing the plan would exceed the purchase budget. |
| `NON_TRADEABLE_MATERIAL` | The path needs a material that can't be traded on the Trading Post while "Allow non-Trading-Post materials" is switched off (above). |

Both views show a **Status / requirements** column carrying this reason, and replace numeric
buy-cost/revenue/profit cells with literal "Unavailable" text for a blocked row rather than a
fabricated zero or a completed-looking profit figure. This keeps two different questions visibly
separate: *can this be crafted at all* versus *is crafting it profitable* (§41) — a blocked row is
not the same thing as an unprofitable one.

## Daily-limited crafting

Some Guild Wars 2 recipes can only be crafted a limited number of times per day — a real game
mechanic. The tool intentionally does **not** track whether you've already used today's allowance
on a given item (§31, decision DQ-005): daily craft state simply isn't part of what gets
synchronized from the account.

If you turn on the "buy instead of craft" option for daily items (`dailyBuyInsteadOfCraft`), the
tool never plans to craft a daily-limited item at all — it only ever consumes what you already own
and/or buys the rest (§32–§33):

- **With "use own materials" on**: use existing owned copies first, then buy any remaining amount
  if buying is allowed; never craft more.
- **With "use own materials" off**: ignore any owned copies, buy the required amount if allowed;
  never craft.

If the remaining amount can't be bought under current settings, the requirement is blocked
(`DAILY_LIMIT`).

## Recipe discovery

Crafting Discovery answers a different question from Crafting Profit: not "what's profitable" but
"what can this character discover next." A recipe qualifies as a discovery candidate only when all
of the following hold at once (§35):

```text
recipe is discoverable through normal ingredient discovery
AND recipe is not already unlocked account-wide
AND the selected character has the recipe's crafting discipline
AND recipe.minimum_rating <= character.current_rating
```

Recipes learned some other way (for example, vendor-sold recipe scrolls) are not eligible for the
Discovery system and are not presented as discovery candidates (§34). Once *any* character on the
account unlocks a recipe, it stops appearing as undiscovered for every other character too, since
unlocks are account-wide.

Discovery also shows an economic comparison (resulting item value vs. materials consumed/bought),
but a negative discovery profit does **not** make a discovery a bad idea — discovering a recipe has
real value beyond immediate profit (crafting rating progress), so profit here is informational,
not an eligibility filter (§37).

## The 250-craft simulation cap

To keep calculations bounded, the simulation intentionally stops evaluating further crafts of the
same recipe once it reaches **250 crafts**, even if materials would allow more (§28, resolved
decision UD-003). This is a deliberate project/performance limit, not a Guild Wars 2 rule, and
there is currently no separate visible "this was capped" indicator beyond the number itself.

**Worked example** (illustrative, not a measured result): suppose you own enough materials to
craft a recipe 300 times, each craft producing 5 copper of profit. The displayed craftable count
will be **250**, not 300, and total profit will be `250 × 5 = 1,250 copper` (12 silver 50 copper) —
not the 1,500 copper that would be mathematically available. The un-simulated 50 crafts are not
reported as "unprofitable"; they simply were never evaluated.

## Craft count, produced quantity and requested quantity

A single recipe execution ("one craft") can produce more than one copy of its output item, so the
tool distinguishes three different numbers (§16):

- **Craft operations** — how many times the recipe is actually executed.
- **Produced quantity** — total output items that results in.
- **Requested quantity** — how many output items were actually needed.

**Worked example**: a recipe produces 3 items per craft, and 5 are needed.
`craft operations = ceil(5 / 3) = 2`, so `produced quantity = 6`, leaving `1` excess beyond the
`5` requested. That leftover unit is not wasted — it stays available for reuse within the same
simulation (for another branch needing the same item, or a later repeat of the same recipe) before
any additional copies are obtained (§17). Only once the entire requested plan finishes does any
still-unused surplus stop receiving further credit — it is not retroactively turned into extra
profit.

## Profit per craft, profit per output item and total profit

These are three related but distinct numbers, and the tool keeps them separate rather than
collapsing them into one figure (§24–§27):

```text
profit_per_craft = output_revenue - purchased_material_cost - owned_material_opportunity_cost
total_profit      = profit_per_craft × craft_count
```

where `output_revenue = selected_output_sell_price × recipe_output_count` (i.e. revenue for *one*
craft, which may cover more than one output item if the recipe has an output count above 1).
`total_profit` is only meaningful because `craft_count` itself already comes from a simulation that
prevents material reuse — it is not a naive "multiply by however many you'd like."

**Worked example** (illustrative, not a measured result): a recipe produces 2 Potions per craft.
Each Potion sells (instant sell, no Trading-Post-fee deduction — see
[Selling fees](#selling-fees-why-profit-and-ecto-salvage-differ)) for 20 copper, and each craft
needs 2 Herbs bought at 10 copper each, with nothing owned:

```text
output_revenue per craft = 2 Potions × 20 copper = 40 copper
purchased_material_cost per craft = 2 Herbs × 10 copper = 20 copper
opportunity_cost per craft = 0 (nothing owned)

profit_per_craft      = 40 - 20 - 0 = 20 copper
profit per output item = 20 copper / 2 Potions = 10 copper per Potion
```

If the simulation reaches the 250-craft cap for this recipe, `total_profit = 250 × 20 copper =
5,000 copper` (50 silver) — again, capped at 250 crafts regardless of how many more might otherwise
be possible.

## Assumptions and data freshness

Two kinds of "how fresh is this" matter, and both are worth remembering when reading a result
(§39–§40):

- **Account data** (inventory, bank, recipes known) reflects the last time the account was
  synchronized from the Guild Wars 2 API, which can lag behind actions just taken in-game.
- **Trading Post prices** reflect the last price refresh, not a live, guaranteed future transaction
  price — actual prices can move between analysis and any real purchase/sale.

Every number in this guide's worked examples is illustrative only. It demonstrates how the rules
combine, not an actual measured application result.

## Known implementation gaps

Most historical gaps between this calculation and `DOMAIN_SPEC.md` recorded in
[`docs/KNOWN_PROBLEMS.md`](../KNOWN_PROBLEMS.md) §3 have since been resolved (see that document's
per-item Status lines for evidence). One user-visible caveat remains open at the time of writing:

- When "use own materials" is off **and** buying is disabled at the same time, an internal
  worthiness check estimates a recipe's value from its *direct* ingredients' immediate Trading Post
  prices only, without considering that a sub-ingredient might be cheaper to craft recursively. In
  that specific settings combination, a recipe could be skipped (reported as 0 craftable) even
  though a fuller recursive evaluation would have found it viable (`docs/KNOWN_PROBLEMS.md` §7.4).
  This does not affect the common case where either "use own materials" or "buy missing mats" is
  enabled.

If you notice a result that seems to disagree with the rules in this guide, check
`docs/KNOWN_PROBLEMS.md` first — it may already be a documented, tracked gap rather than a new one.

## Where to go next

- [GLOSSARY.md](GLOSSARY.md) — terms and frequently asked questions.
- [`docs/DOMAIN_SPEC.md`](../DOMAIN_SPEC.md) — the full authoritative rule set this guide summarizes.
- [`docs/TARGET_ARCHITECTURE.md`](../TARGET_ARCHITECTURE.md) §35 — why this guide exists and how it
  is meant to be kept up to date.
- [`docs/KNOWN_PROBLEMS.md`](../KNOWN_PROBLEMS.md) — current defects, risks and technical debt.
