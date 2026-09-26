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
  covers recipes eligible for this system (§34–§35).
- **Owned-material pool** — the combined total of account material storage, account bank, and all
  character inventories, used only for economic analysis (§9).
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
- **Blocked reason** — the specific reason a requirement or recipe couldn't be resolved (e.g.
  `PRICE_UNAVAILABLE`, `BUYING_DISABLED`); shown instead of silently hiding the result (§42).
- **Craftable count** — how many times a recipe can be executed under the current settings,
  determined by a full recursive simulation, not just direct ingredients (§28).
- **Coordinated plan** — a Crafting Profit plan spanning multiple characters, where different steps
  may be assigned to different eligible characters (§2.2.1).
- **Transferable intermediate** — a crafted intermediate item that is not soulbound, and can
  therefore be produced by one character and used by another within a coordinated plan (§2.2.1).
- **Simulation cap** — the intentional 250-craft limit per recipe per calculation (§28, UD-003).
- **Non-Trading-Post material** — an item that cannot be traded on the Trading Post by its own
  classification, taken from the Trading Post's list of tradable items and never guessed from a
  missing price. Web Crafting Profit's "Allow non-Trading-Post materials" control decides whether
  paths consuming one may be used (§2.1.1, UD-009/UD-010).

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
considering a purchase. When disabled, owned copies are ignored and the plan only considers buying.

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
So "this can't currently be evaluated" (e.g. a missing price, buying disabled, over budget) stays
visibly distinct from "this was evaluated and isn't profitable." Hiding the row would make those two
very different situations look identical. See
[Unavailable prices and blocked results](README.md#unavailable-prices-and-blocked-results).

**What does "Allow non-Trading-Post materials" do?**
Switched on (the default), materials that can't be traded on the Trading Post may be used when you
own them or can craft them; one you can do neither with still makes the recipe unavailable. Switched
off, only paths consuming no such material are calculated, and alternative routes that avoid them are
evaluated instead. It changes the calculation the backend runs, not which of the returned rows are
displayed. See
[Allowing or excluding non-Trading-Post materials](README.md#allowing-or-excluding-non-trading-post-materials).

**Why is crafting simulation capped at 250?**
It's an intentional, project-chosen limit to keep calculations bounded, not a game rule. Craftable
count and total profit for a recipe never exceed what 250 crafts would produce, even if more
materials are available. See [The 250-craft simulation cap](README.md#the-250-craft-simulation-cap).

**Why doesn't Crafting Profit charge the Trading Post fee, but Ecto Salvage does?**
Both are deliberate, independent project decisions recorded in `DOMAIN_SPEC.md` (§25 and §46). They
are not meant to be numerically comparable to each other. See
[Selling fees](README.md#selling-fees-why-profit-and-ecto-salvage-differ).

**Why is Crafting Discovery still per-character while Profit can span all characters?**
Discovering a recipe is inherently something one specific character does (it affects that
character's known-recipe list and crafting progress), so it doesn't have a meaningful "all
characters" mode the way profit-seeking crafting does. See
[Who can craft what](README.md#who-can-craft-what-profit-scope-vs-discovery-character).

**Does the tool know if I've already used today's daily craft on an item?**
No — this is an intentional simplification. Daily-crafting state isn't tracked at all; the "buy
instead of craft" option exists specifically so daily-limited items are never planned to be crafted
in the first place. See [Daily-limited crafting](README.md#daily-limited-crafting).
