# Crafting Guide — Glossary & FAQ

Companion to [README.md](README.md), which explains the full calculation flow. Section numbers
(e.g. "§22") refer to [`docs/DOMAIN_SPEC.md`](../DOMAIN_SPEC.md), the authoritative source.

## Glossary

- **Crafting discipline** — a craft profession (Armorsmith, Chef, Weaponsmith, ...). A recipe
  belongs to one or more disciplines; a character may have several, each at its own rating (§7).
- **Crafting rating** — a character's skill level within one discipline. A recipe has a minimum
  rating requirement; a character below it cannot use that recipe (§8).
- **Recipe knowledge / recipe unlock** — whether a recipe has been learned. Unlocks are
  account-wide: once any character learns a recipe, every character is considered to know it (§6,
  §34).
- **Recipe discovery** — the Guild Wars 2 system for learning a recipe you don't know yet by
  combining ingredients, as opposed to buying/finding a recipe scroll. Crafting Discovery only
  covers recipes eligible for this system (§34–§35), evaluates one attempt per candidate, and uses
  one selected character for both eligibility and owned materials.
- **Discovery attempt** — one execution of a candidate recipe to learn it. It has no cumulative
  craft-count or max-buy budget; its output valuation still uses the recipe's actual output quantity.
- **Owned-material pool** — the combined total of account material storage, account bank, and all
  character inventories, used only for economic analysis (§9).
- **Material reservation / exclusion** — stock kept out of Crafting Profit when **Respect excluded
  items/ mats** is enabled. Bank and Materials are independent sources; a reservation keeps ALL
  units or a fixed finite quantity (§9.1).
- **Finite reservation** — a saved amount to keep, independent of current stock. Bank may allocate
  it across individual current stacks; rearranging stacks keeps the amount, not old slot identity.
- **Account-bound** — an item usable by any character on the account, but not sellable on the
  Trading Post (§11.1).
- **Soulbound** — an item usable only by the one character it's bound to, and not sellable (§11.1).
- **Opportunity cost** — the value given up by consuming an owned, sellable item instead of selling
  it, even though doing so spends no cash (§11).
- **Effective (economic) cost** — cash cost plus opportunity cost; the number the tool actually
  minimizes when choosing between crafting and buying an ingredient (§22–§23).
- **Instant buy / listing buy** — the two ways to buy on the Trading Post: accept an existing sell
  listing now (instant), or place/match a buy order and wait (listing) (§20).
- **Instant sell / listing sell** — the two ways to sell: accept an existing buy order now
  (instant), or list the item and wait for a buyer (listing) (§20).
- **Blocked reason** — the backend diagnostic reason a requirement or recipe could not proceed
  (e.g. `PRICE_UNAVAILABLE`, `BUYING_DISABLED`). It can explain why further crafts stopped after
  valid earlier crafts. Normal tables omit generic state labels; tree messages stay item-specific (§42).
- **Craftable count** — how many times a recipe can be executed under the current settings,
  determined by a full recursive simulation, not just direct ingredients (§28).
- **Currency cost / conditional margin** — a recipe can spend wallet currencies such as Research Notes
  (for example 30 per Piece of Dragon Jade). Currencies are listed separately and not priced in gold, so the
  gold margin shown is *conditional* (marked `*`), not a guaranteed profit. An unavailable wallet means the
  craftable count is unknown; an empty published wallet means none can be afforded.
- **Coordinated plan** — a Crafting Profit plan spanning multiple characters, where different steps
  may be assigned to different eligible characters (§2.2.1).
- **Transferable intermediate** — a crafted intermediate item that is not soulbound, and can
  therefore be produced by one character and used by another within a coordinated plan (§2.2.1).
- **Simulation cap** — the intentional 250-craft limit per recipe per calculation (§28, UD-003).
- **Material demand bound** — at most 2,500 required units of any one material item ID in a
  candidate plan, counting all its accepted batches and recursive requirements (§42).
- **Numeric range guard** — rejects quantity/money arithmetic outside the supported range instead
  of wrapping, clamping or inventing a price or profit (`VALUE_RANGE`, §42).
- **Provisional result** — an ordinary, fully calculated result whose account data or Trading Post
  prices had expired when it was calculated. The numbers are the normal ones for those inputs; what
  is provisional is the inputs, not the mathematics (§39–§40).
- **"Stale data – refreshing…"** — the Crafting Profit label shown beside a provisional result
  while the refresh it needs is running. It disappears only once the refreshed inputs have produced
  a replacement result; a finished refresh task on its own does not remove it.

## FAQ

**What is a crafting discipline?**
A craft profession, like Armorsmith or Chef. Recipes require a specific discipline (or one of
several); characters have their own rating per discipline they've trained. See
[Recipes, disciplines and crafting rating](README.md#recipes-disciplines-and-crafting-rating).

**What is a recipe unlock?**
Learning a recipe so it becomes usable. Unlocks apply to the whole account — once one character
learns a recipe, it's available to all characters (subject to their own discipline/rating). See
[Recipe unlocks and recipe knowledge](README.md#recipe-unlocks-and-recipe-knowledge).

**What is soulbound vs. account-bound?**
Both mean "can't be sold on the Trading Post." Account-bound items can be used by any of your
characters; soulbound items are locked to the one character they're bound to. See
[Account-bound and soulbound materials](README.md#account-bound-and-soulbound-materials).

**Why can multiple characters participate in one crafting tree?**
Because Crafting Profit's `All` and discipline scopes build one coordinated account-wide plan
instead of requiring a single character to do everything. One character can craft an intermediate
that a different, eligible character then uses in a later step. See
[Coordinated crafting and transferable intermediates](README.md#coordinated-crafting-and-transferable-intermediates).

**Why can't soulbound materials simply be pooled?**
Because they're not actually interchangeable in the game — a soulbound copy only exists for its
owning character. Pooling two characters' soulbound copies to satisfy one step would report a plan
as possible when it genuinely isn't. See
[Account-bound and soulbound materials](README.md#account-bound-and-soulbound-materials).

**What does "use own mats" mean?**
When enabled, the calculation consumes materials you already own (from the combined
[owned-material pool](README.md#the-owned-material-pool-bank-storage-and-characters)) before
considering a purchase. When disabled, owned copies are ignored.
Recursive crafting remains available under the normal recipe rules with either setting. Disabling
owned stock does not itself enable purchases; **Allow buying** independently controls buying.

**Why does an owned tradable material still have an opportunity cost?**
Because using it up means you can no longer sell it. "No cash spent" isn't the same as "no economic
cost" — see the worked example under
[Opportunity cost of owned materials](README.md#opportunity-cost-of-owned-materials).

**What is the difference between instant buy and listing buy?**
Instant buy accepts an existing sell listing immediately, at the sell-listing price. Listing buy
places or matches a buy order and waits, at the buy-order price. See
[Trading Post price modes](README.md#trading-post-price-modes-instant-vs-listing).

**What is the difference between instant sell and listing sell?**
Instant sell accepts an existing buy order immediately, at the buy-order price. Listing sell lists
the item and waits for a buyer, at the sell-listing price. See
[Trading Post price modes](README.md#trading-post-price-modes-instant-vs-listing).

**Why can a result be blocked instead of simply omitted?**
So "this can't currently be evaluated" (e.g. a missing price, buying disabled, over budget) remains
distinct from "this was evaluated and isn't profitable." The API keeps the reason; normal tables
omit generic state badges and the resolution tree shows affected items and actual sourcing.
Display filters can hide rows without changing their backend result. See
[Unavailable prices and blocked results](README.md#unavailable-prices-and-blocked-results).

**Why is crafting simulation capped at 250?**
It's an intentional, project-chosen limit to keep calculations bounded, not a game rule. Craftable
count and total profit for a recipe never exceed what 250 crafts would produce, even if more
materials are available. See [The 250-craft simulation cap](README.md#the-250-craft-simulation-cap).

**Why did a plan stop before 250 crafts?**
Inventory, buying settings, recipe eligibility, budget and daily allowances can stop it sooner.
The separate **2,500-unit limit per material** and numeric-range checks can also stop a large
plan. Earlier complete Profit batches remain valid. See
[The separate material and numeric bounds](README.md#the-separate-material-and-numeric-bounds).

**Can I keep 250 Ectos while using the rest?**
Yes. In Bank or Materials, right-click Ectos, choose **Reserve quantity…** and enter 250.
Enable **Respect excluded items/ mats** in Crafting Profit. With 300 in that selected store,
50 remain usable. Reservations save automatically for this account in this browser. See
[Keeping materials for later](README.md#keeping-materials-for-later).

**Why is Total Sell Value minus my costs more than the Total Profit shown?**
Because the 15% Trading Post selling fee is deducted from profit only (`DOMAIN_SPEC.md` §25,
decision UD-011). Every displayed price — item sell price, Output Revenue, Total Sell Value, Instant
Buy / Instant Sell — stays gross, so the gap between them is the fee. The calculation details label
Profit and Total Profit "after 15% TP fees" for that reason. See
[Selling fees](README.md#selling-fees-the-15-deducted-from-profit).

**Why does Crafting Discovery use one character for both eligibility and inventory?**
A discovery attempt is performed by one character, so that character's discipline/rating and
character-bound materials must be evaluated together. Discovery also considers one attempt per
recipe, rather than a cumulative craft count or max-buy budget. See
[Who can craft what](README.md#who-can-craft-what-profit-scope-vs-discovery-character).

**Does the tool know if I've already used today's daily craft on an item?**
No — this is an intentional simplification. Daily-crafting state isn't tracked at all. When
`allowDailyCrafts` is enabled, the planner models one daily craft operation per output item in a
planning state; buying and owned stock remain controlled by their own settings. See
[Daily-limited crafting](README.md#daily-limited-crafting).
