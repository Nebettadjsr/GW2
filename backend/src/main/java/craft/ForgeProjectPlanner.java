package craft;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Exact demand expansion for ONE selected Mystic Forge target (a project, not a profit maximisation): it plans
 * {@code batches} crafts of that recipe and nothing else, whatever its cost or the wallet. Deterministic and cheap:
 * owned stock is aggregated once and consumed in expansion order (owned intermediates before unmet demand), a
 * craft's surplus output is conserved for later demand, and each unmet item is produced by one fixed-output
 * producer (ordinary crafting, then Mystic Forge, then vendor; lowest recipe id) or reported as a leaf to acquire.
 * It never claims global optimality, never prices a currency in gold, never treats a random-output recipe as a
 * producer, and never defaults an unknown price to zero: unpriced leaves are listed, and the known gold cost
 * covers only what was actually priced. Quantities are checked; a hit bound yields a truthful incomplete result.
 */
public final class ForgeProjectPlanner {

    public static final int MAX_DEPTH = 24;
    public static final int MAX_NODES = 20_000;

    public record CraftStep(int recipeId, int outputItemId, long crafts, long outputUnits, RecipeSource source) { }

    /** Per item: total demand, what stock covered, and what must still be acquired (a leaf the plan cannot produce). */
    public record ItemLine(int itemId, long needed, long fromOwned, long missing, Long knownUnitCopper) { }

    /** {@code owned}/{@code missing} are null when the wallet is unknown (never defaulted to zero). */
    public record CurrencyLine(int currencyId, long needed, Long owned, Long missing) { }

    /**
     * One node of the hierarchical how-to. Explicit partition: {@code needed == fromOwned + fromSurplus + toCraft +
     * toAcquire}. {@code toCraft} is planned work, NOT owned progress; {@code toAcquire} is the part no producer
     * can supply (buy/obtain it). A node cut off by a cycle or bound carries {@code cutoff} instead of children.
     */
    public record Node(int itemId, long needed, long fromOwned, long fromSurplus, long toCraft, long toAcquire,
                       Integer recipeId, RecipeSource source, long crafts, int minRating, String disciplines,
                       String cutoff, List<Node> children, Boolean recipeKnown, boolean performable,
                       String assignedCharacter, String hint) { }

    /** Owned stock in its binding pools (never collapsed): sellable, account-bound and per-owner character/Bank stock. */
    public record Inventory(Map<Integer, Integer> sellable, Map<Integer, Integer> accountBound,
                            Map<String, Map<Integer, Integer>> characterBound) {
        public static Inventory shared(Map<Integer, Long> owned) {
            Map<Integer, Integer> m = new java.util.HashMap<>();
            owned.forEach((k, v) -> m.put(k, (int) Math.min(Integer.MAX_VALUE, Math.max(0, v))));
            return new Inventory(m, Map.of(), Map.of());
        }
    }

    /** Roster ratings and known recipe ids ({@code knownRecipeIds} null = unknown, never "none known"). */
    public record Context(List<CharacterCraftingProfile> roster, Set<Integer> knownRecipeIds) {
        public static final Context NONE = new Context(List.of(), null);
    }

    /** Self-seed accounting of an expected-yield root: one external seed, then guaranteed reuse only. */
    public record Seed(long externalSeedUnits, long reusedSeedUnits, long grossGuaranteedOutput,
                       long netGuaranteedOutput, BigDecimal grossExpectedOutput, BigDecimal netExpectedOutput) { }

    /**
     * Conditional economics of a tradable promotion batch plan. Null money = not claimable (unpriced inputs/output).
     * Currencies are never converted to gold; {@code conditional} is true when currencies or unpriced parts remain.
     */
    public record Economics(Integer outputUnitSellCopper, Long expectedGrossProceedsCopper, Long feeCopper,
                            Long expectedNetProceedsCopper, long knownShoppingCopper, long goldInputCopper,
                            List<CurrencyLine> currencyInputs, List<Integer> unpricedInputItemIds,
                            Long marginOnShoppingCopper, Long marginCopper, boolean conditional, boolean fixedOutput) { }

    public record Project(int recipeId, int outputItemId, long batches, long guaranteedOutput,
                          List<CraftStep> steps, List<ItemLine> items, List<CurrencyLine> currencies,
                          Node tree, long knownCopper, List<Integer> unpricedItemIds, boolean complete, String incompleteReason,
                          Seed seed, Economics economics) { }

    private final Map<Integer, Recipe> producerByItem = new java.util.HashMap<>();

    /** @param producers every recipe that may supply an intermediate (ordinary graph recipes, Forge, vendor) */
    public ForgeProjectPlanner(List<Recipe> producers) {
        List<Recipe> sorted = new ArrayList<>(producers);
        sorted.sort(Comparator.<Recipe>comparingInt(r -> rank(r.source)).thenComparingInt(r -> r.recipeId));
        for (Recipe r : sorted) {
            if (r.hasExpectedYield() || r.outputCount <= 0) continue; // random outcomes never feed a guaranteed plan
            producerByItem.putIfAbsent(r.outputItemId, r);
        }
    }

    private static int rank(RecipeSource s) {
        return s == RecipeSource.VENDOR ? 2 : s == RecipeSource.MYSTIC_FORGE ? 1 : 0;
    }

    /**
     * Item ids of the selected target's public path: its output, direct inputs and, recursively, the inputs of each
     * fixed-output producer reachable from them. Independent of owned stock and prices (structure only), bounded by
     * {@link #MAX_NODES}; never the whole catalogue.
     */
    public Set<Integer> pathItemIds(Recipe target) {
        Set<Integer> ids = new java.util.TreeSet<>();
        java.util.ArrayDeque<Recipe> queue = new java.util.ArrayDeque<>();
        Set<Integer> seen = new HashSet<>();
        queue.add(target);
        ids.add(target.outputItemId);
        while (!queue.isEmpty() && seen.size() <= MAX_NODES) {
            Recipe r = queue.poll();
            if (!seen.add(r.recipeId)) continue;
            for (Ingredient i : r.ingredients) {
                ids.add(i.itemId);
                Recipe p = producerByItem.get(i.itemId);
                if (p != null && !seen.contains(p.recipeId)) queue.add(p);
            }
        }
        ids.removeIf(id -> id <= 0);
        return ids;
    }

    /** Batch semantics with only shared stock and no roster (unbound-map convenience for simple callers/tests). */
    public Project plan(Recipe target, long batches, Map<Integer, Long> owned, Map<Integer, Long> wallet,
                        Map<Integer, PriceQuote> prices) {
        return plan(target, batches, true, Inventory.shared(owned), Context.NONE, wallet, prices);
    }

    /** Fixed target: {@code quantity} is the desired TOTAL output; already-owned output is consumed first. */
    public Project planOutput(Recipe target, long quantity, Inventory inv, Context ctx, Map<Integer, Long> wallet,
                              Map<Integer, PriceQuote> prices) {
        return plan(target, quantity, false, inv, ctx, wallet, prices);
    }

    /**
     * @param quantity batches when {@code batchTarget}, else the desired total output units
     * @param wallet   null = whole wallet unknown; a known map treats an absent currency as exactly 0
     */
    public Project plan(Recipe target, long quantity, boolean batchTarget, Inventory inv, Context ctx,
                        Map<Integer, Long> wallet, Map<Integer, PriceQuote> prices) {
        if (target == null || !target.isMysticForge()) throw new IllegalArgumentException("target must be a Mystic Forge recipe");
        if (quantity <= 0) throw new IllegalArgumentException("batches must be positive");
        Run run = new Run(new PlanState(inv.sellable(), inv.accountBound(), inv.characterBound()), prices, ctx);
        long batches = 0;
        Node tree = null;
        try {
            long outUnits;
            long rootOwned = 0;
            if (batchTarget) {
                batches = quantity;
                outUnits = Math.multiplyExact(batches, (long) target.outputCount);
            } else {
                outUnits = quantity;
                rootOwned = run.consumeRoot(target.outputItemId, quantity);
                batches = Math.ceilDiv(quantity - rootOwned, (long) target.outputCount);
                if (rootOwned > 0) run.fromOwned.merge(target.outputItemId, rootOwned, Math::addExact);
                run.demand.merge(target.outputItemId, quantity, Math::addExact);
            }
            List<Node> kids = batches > 0 ? run.craft(target, batches, 0, new HashSet<>()) : List.of();
            long surplusOut = batches > 0 ? Math.subtractExact(Math.multiplyExact(batches, (long) target.outputCount), quantity - rootOwned) : 0;
            boolean fixedRoot = !batchTarget;
            tree = new Node(target.outputItemId, outUnits, rootOwned, 0, fixedRoot ? outUnits - rootOwned : outUnits, 0,
                    target.recipeId, target.source, batches, target.minRating, target.disciplinesText, run.reason, kids,
                    null, true, null, null);
            if (fixedRoot && surplusOut > 0) run.surplus.merge(target.outputItemId, surplusOut, Math::addExact);
        } catch (ArithmeticException e) {
            run.stop("quantity exceeds the supported range");
        }
        List<ItemLine> items = new ArrayList<>();
        List<Integer> unpriced = new ArrayList<>();
        long copper = run.vendorCopper;
        for (var e : run.demand.entrySet()) {
            int id = e.getKey();
            long missing = run.leaf.getOrDefault(id, 0L);
            long have = run.fromOwned.getOrDefault(id, 0L);
            Long unit = unitPrice(prices.get(id));
            if (missing > 0 && unit != null) {
                try { copper = Math.addExact(copper, Math.multiplyExact(unit, missing)); }
                catch (ArithmeticException ex) { run.stop("gold cost exceeds the supported range"); }
            } else if (missing > 0) unpriced.add(id);
            items.add(new ItemLine(id, e.getValue(), have, missing, unit));
        }
        List<CurrencyLine> currencies = new ArrayList<>();
        for (var e : run.currencyDemand.entrySet()) {
            Long have = wallet == null ? null : Math.max(0, wallet.getOrDefault(e.getKey(), 0L));
            currencies.add(new CurrencyLine(e.getKey(), e.getValue(), have,
                    have == null ? null : Math.max(0, e.getValue() - have)));
        }
        long out;
        try { out = Math.multiplyExact(batches, (long) target.outputCount); } catch (ArithmeticException e) { out = Long.MAX_VALUE; }
        Seed seed = null;
        Economics econ = null;
        if (run.reason == null && (target.hasExpectedYield() || !batchTarget)) {
            if (target.hasExpectedYield()) seed = seedOf(target, batches);
            econ = economics(target, batches, seed, prices, copper);
        }
        return new Project(target.recipeId, target.outputItemId, batches, out, List.copyOf(run.steps), items, currencies,
                tree, copper, unpriced, run.reason == null, run.reason, seed, econ);
    }

    /** Cost side of the Trading Post: the lowest listing (ask, sell_unit_price). */
    private static Long unitPrice(PriceQuote q) {
        return q != null && q.sellUnit != null && q.sellUnit > 0 ? (long) q.sellUnit : null;
    }

    /** Instant-sell side of the Trading Post: the highest bid (buy_unit_price). */
    private static Long bidPrice(PriceQuote q) {
        return q != null && q.buyUnit != null && q.buyUnit > 0 ? (long) q.buyUnit : null;
    }

    /** Units of the root's own output consumed per batch as the seed (summed over such ingredients). */
    static long seedExternalUnits(Recipe r, long batches) {
        long s = r.selfSeedUnits();
        if (s == 0 || !r.hasExpectedYield()) return 0;
        long reusedPerBatch = Math.min(s, r.expectedYield.min());
        return Math.addExact(s, Math.multiplyExact(batches - 1, s - reusedPerBatch));
    }

    private static Seed seedOf(Recipe r, long batches) {
        ExpectedYield y = r.expectedYield;
        long s = r.selfSeedUnits();
        long external = seedExternalUnits(r, batches);
        long totalSeed = Math.multiplyExact(batches, s);
        long reused = totalSeed - external;
        long gross = Math.multiplyExact(batches, (long) y.min());
        BigDecimal grossExp = y.mean().multiply(BigDecimal.valueOf(batches));
        return new Seed(external, reused, gross, gross - reused, grossExp, grossExp.subtract(BigDecimal.valueOf(reused)));
    }

    /**
     * Buy-all-direct-inputs -> instantly-sell-outputs comparison (not the how-to's cheapest path, and owned stock is
     * never free). Each direct input costs its valid Trading Post ask, else a verified vendor purchase (cash counted
     * once, currencies kept separate and never converted to gold), else it is listed as unpriced. A self-seed counts
     * only the external units. Proceeds use the bid; the 15% fee is applied once over the gross, half-up.
     */
    private Economics economics(Recipe r, long batches, Seed seed, Map<Integer, PriceQuote> prices, long shopping) {
        long gold = 0;
        Map<Integer, Long> currency = new TreeMap<>();
        List<Integer> unpricedInputs = new ArrayList<>();
        boolean conditional = false;
        try {
            gold = Math.multiplyExact((long) r.vendorCopper, batches);
            for (CurrencyRequirement c : r.currencies) {
                currency.merge(c.currencyId(), Math.multiplyExact((long) c.count(), batches), Math::addExact);
                conditional = true;
            }
            for (Ingredient i : r.ingredients) {
                boolean seedInput = seed != null && i.itemId == r.outputItemId;
                long qty = seedInput ? seed.externalSeedUnits() : Math.multiplyExact((long) i.count, batches);
                if (qty <= 0) continue;
                Long ask = unitPrice(prices.get(i.itemId));
                Recipe vendor = producerByItem.get(i.itemId);
                if (ask != null) gold = Math.addExact(gold, Math.multiplyExact(ask, qty));
                else if (vendor != null && vendor.isVendor()) {
                    long buys = Math.ceilDiv(qty, (long) vendor.outputCount);
                    gold = Math.addExact(gold, Math.multiplyExact((long) vendor.vendorCopper, buys));
                    for (CurrencyRequirement c : vendor.currencies) {
                        currency.merge(c.currencyId(), Math.multiplyExact((long) c.count(), buys), Math::addExact);
                        conditional = true;
                    }
                } else if (!unpricedInputs.contains(i.itemId)) unpricedInputs.add(i.itemId);
            }
        } catch (ArithmeticException e) {
            return null;
        }
        Long bid = bidPrice(prices.get(r.outputItemId));
        Long gross = null, fee = null, net = null;
        if (bid != null) {
            try {
                gross = seed != null
                        ? ExpectedYield.floorCopper(seed.netExpectedOutput(), (int) Math.min(Integer.MAX_VALUE, bid))
                        : Math.multiplyExact(bid, Math.multiplyExact(batches, (long) r.outputCount));
            } catch (ArithmeticException e) { return null; }
            fee = gross > 0 ? tradingpost.TradingPostFeePolicy.feeOn(gross) : 0L;
            net = gross - fee;
        }
        boolean full = unpricedInputs.isEmpty();
        List<CurrencyLine> lines = new ArrayList<>();
        currency.forEach((id, n) -> lines.add(new CurrencyLine(id, n, null, null)));
        return new Economics(bid == null ? null : (int) Math.min(Integer.MAX_VALUE, bid), gross, fee, net, shopping,
                gold, lines, List.copyOf(unpricedInputs), net != null && full ? net - shopping : null,
                net != null && full ? net - gold : null, conditional || !full, seed == null);
    }

    private final class Run {
        final PlanState state;
        final Map<Integer, PriceQuote> prices;
        final Context ctx;
        final Map<Integer, Long> surplus = new java.util.HashMap<>();
        final Map<Integer, Long> demand = new TreeMap<>();
        final Map<Integer, Long> fromOwned = new java.util.HashMap<>();
        final Map<Integer, Long> leaf = new java.util.HashMap<>();
        final Map<Integer, Long> currencyDemand = new TreeMap<>();
        final Map<Integer, CraftStep> stepByRecipe = new TreeMap<>();
        final List<CraftStep> steps = new ArrayList<>();
        long vendorCopper;
        int nodes;
        String reason;

        Run(PlanState state, Map<Integer, PriceQuote> prices, Context ctx) {
            this.state = state; this.prices = prices; this.ctx = ctx;
        }

        void stop(String why) { if (reason == null) reason = why; }

        /** Takes owned stock through the shared binding rules for {@code assignee}; returns units used. */
        long consume(int itemId, long qty, String assignee) {
            int ask = (int) Math.min(Integer.MAX_VALUE, qty);
            return ask <= 0 ? 0 : state.consumeInventoryWithBinding(itemId, ask, assignee).total();
        }

        /** Owned goal output counts once from actual output ownership, independent of the input assignee. */
        long consumeRoot(int itemId, long qty) {
            int ask = (int) Math.min(Integer.MAX_VALUE, qty);
            return ask <= 0 ? 0 : state.consumeOwnedOutput(itemId, ask).total();
        }

        /** Deterministic eligible character holding the most relevant stock; null when none can perform it. */
        String assign(Recipe r) {
            Set<String> disc = new HashSet<>();
            if (r.disciplinesText != null) for (String d : r.disciplinesText.split(",")) if (!d.isBlank()) disc.add(d.trim());
            String best = null;
            long bestScore = -1;
            for (CharacterCraftingProfile p : ctx.roster()) {
                boolean ok = false;
                for (String d : disc) {
                    Integer rating = p.ratingByDiscipline().get(d);
                    if (rating != null && rating >= r.minRating) { ok = true; break; }
                }
                if (!ok) continue;
                long score = 0;
                for (Ingredient i : r.ingredients) {
                    score += state.carriedQuantity(p.name(), i.itemId)
                            + state.carriedQuantity(PlanState.bankOwnerKey(p.name()), i.itemId);
                }
                if (score > bestScore || (score == bestScore && p.name().compareTo(best) < 0)) { best = p.name(); bestScore = score; }
            }
            return best;
        }

        /**
         * A Forge step may use Bank stock of every owner plus the carried stock of ONE character: the one holding the
         * most relevant ingredients (deterministic, ties by name). Carried stock of different owners is never pooled.
         */
        String forgeAssignee(Recipe r) {
            String best = null;
            long bestScore = 0;
            for (String key : new java.util.TreeSet<>(state.characterBoundInventory.keySet())) {
                if (key.startsWith(PlanState.BANK_PREFIX)) continue;
                long score = 0;
                for (Ingredient i : r.ingredients) score += state.carriedQuantity(key, i.itemId);
                if (score > bestScore) { best = key; bestScore = score; }
            }
            return PlanState.forgeAssignee(best);
        }

        List<Node> craft(Recipe r, long crafts, int depth, Set<Integer> path) {
            return craft(r, crafts, depth, path, r.isMysticForge() ? forgeAssignee(r) : null);
        }

        List<Node> craft(Recipe r, long crafts, int depth, Set<Integer> path, String assignee) {
            List<Node> kids = new ArrayList<>();
            if (reason != null) return kids;
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES) { stop("planning bound reached"); return kids; }
            try {
                vendorCopper = Math.addExact(vendorCopper, Math.multiplyExact((long) r.vendorCopper, crafts));
                for (CurrencyRequirement c : r.currencies) {
                    currencyDemand.merge(c.currencyId(), Math.multiplyExact((long) c.count(), crafts), Math::addExact);
                }
                path.add(r.recipeId);
                for (Ingredient i : r.ingredients) {
                    boolean seed = r.hasExpectedYield() && i.itemId == r.outputItemId;
                    long qty = seed ? seedQuantity(r, i, crafts) : Math.multiplyExact((long) i.count, crafts);
                    Node n = need(i.itemId, qty, depth + 1, path, assignee, seed);
                    if (n != null) kids.add(n);
                }
                path.remove(r.recipeId);
                steps.removeIf(s -> s.recipeId() == r.recipeId);
                long total = Math.addExact(stepByRecipe.containsKey(r.recipeId) ? stepByRecipe.get(r.recipeId).crafts() : 0L, crafts);
                CraftStep step = new CraftStep(r.recipeId, r.outputItemId, total, Math.multiplyExact(total, (long) r.outputCount), r.source);
                stepByRecipe.put(r.recipeId, step);
                steps.add(step);
            } catch (ArithmeticException e) {
                stop("quantity exceeds the supported range");
            }
            return kids;
        }

        /** One external seed, then each later batch reuses only the guaranteed minimum output. */
        long seedQuantity(Recipe r, Ingredient i, long crafts) {
            long reusedPerBatch = Math.min(i.count, r.expectedYield.min());
            return Math.addExact(i.count, Math.multiplyExact(crafts - 1, i.count - reusedPerBatch));
        }

        Node need(int itemId, long qty, int depth, Set<Integer> path, String assignee, boolean seedLeaf) {
            if (reason != null || qty <= 0) return null;
            demand.merge(itemId, qty, Math::addExact);
            long use = consume(itemId, qty, assignee);
            if (use > 0) fromOwned.merge(itemId, use, Math::addExact);
            long unmet = qty - use;
            long spare = Math.min(surplus.getOrDefault(itemId, 0L), unmet);
            if (spare > 0) { surplus.merge(itemId, -spare, Long::sum); unmet -= spare; } // conserved craft surplus, not owned stock
            if (unmet == 0) return new Node(itemId, qty, use, spare, 0, 0, null, null, 0, 0, "", null, List.of(), null, true, null, null);
            Recipe p = seedLeaf ? null : producerByItem.get(itemId);
            if (p == null || path.contains(p.recipeId)) {
                leaf.merge(itemId, unmet, Math::addExact);
                String cut = p == null ? null : "cycle: this item's producer is already being expanded above";
                return new Node(itemId, qty, use, spare, 0, unmet, null, null, 0, 0, "", cut, List.of(), null, true, null, null);
            }
            long crafts = Math.ceilDiv(unmet, (long) p.outputCount);
            long extra = Math.subtractExact(Math.multiplyExact(crafts, (long) p.outputCount), unmet);
            if (extra > 0) surplus.merge(itemId, extra, Math::addExact);
            Boolean known = p.source == RecipeSource.ORDINARY && ctx.knownRecipeIds() != null
                    ? ctx.knownRecipeIds().contains(p.recipeId) : null;
            String who = null;
            boolean performable = true;
            String childAssignee = p.isMysticForge() ? forgeAssignee(p) : null;
            if (p.source == RecipeSource.ORDINARY) {
                who = assign(p);
                performable = who != null && !Boolean.FALSE.equals(known);
                childAssignee = who;
            }
            List<Node> kids = craft(p, crafts, depth, path, childAssignee);
            return new Node(itemId, qty, use, spare, unmet, 0, p.recipeId, p.source, crafts, p.minRating,
                    p.disciplinesText, reason, kids, known, performable, who,
                    p.isVendor() ? VendorAcquisitions.hintFor(p.outputItemId) : null);
        }
    }
}
