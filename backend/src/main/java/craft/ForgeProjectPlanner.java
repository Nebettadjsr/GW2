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
    /** Largest integer a JSON number carries exactly (2^53-1); every emitted quantity/copper value stays within it. */
    public static final long MAX_SAFE = 9_007_199_254_740_991L;

    public static long mul(long a, long b) {
        long r = Math.multiplyExact(a, b);
        if (Math.abs(r) > MAX_SAFE) throw new ArithmeticException("beyond the exact JSON integer range");
        return r;
    }

    public static long add(long a, long b) {
        long r = Math.addExact(a, b);
        if (Math.abs(r) > MAX_SAFE) throw new ArithmeticException("beyond the exact JSON integer range");
        return r;
    }

    public static long sub(long a, long b) {
        long r = Math.subtractExact(a, b);
        if (Math.abs(r) > MAX_SAFE) throw new ArithmeticException("beyond the exact JSON integer range");
        return r;
    }

    public record CraftStep(int recipeId, int outputItemId, long crafts, long outputUnits, RecipeSource source) { }

    /** Per item: total demand, what stock covered, and what must still be acquired (a leaf the plan cannot produce). */
    public record ItemLine(int itemId, long needed, long fromOwned, long missing, Long knownUnitCopper) { }

    /**
     * One verified vendor purchase batch the plan actually pays for ({@code purchases} buys of {@code outputCount}
     * units each; {@code units} may exceed demand, the rest stays conserved surplus). Cash is real copper; a
     * currency-only offer has {@code copper == 0} and a non-empty {@code currencies} (never a free purchase).
     */
    public record VendorPurchase(int itemId, long purchases, long units, long copper,
                                 List<CurrencyLine> currencies, String hint) { }

    /** {@code owned}/{@code missing} are null when the wallet is unknown (never defaulted to zero). */
    public record CurrencyLine(int currencyId, long needed, Long owned, Long missing) { }
    // For Research Notes, {@code missing} is the demand still uncovered AFTER planned Note production (notes.shortfall is the pre-production deficit).

    /**
     * One node of the hierarchical how-to. Explicit partition: {@code needed == fromOwned + fromSurplus + toCraft +
     * toAcquire}. {@code toCraft} is planned work, NOT owned progress; {@code toAcquire} is the part no producer
     * can supply (buy/obtain it). A node cut off by a cycle or bound carries {@code cutoff} instead of children.
     */
    public record Node(int itemId, long needed, long fromOwned, long fromSurplus, long toCraft, long toAcquire,
                       Integer recipeId, RecipeSource source, long crafts, int minRating, String disciplines,
                       String cutoff, List<Node> children, Boolean recipeKnown, boolean performable,
                       String assignedCharacter, String hint, long produced, long newSurplus) { }

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

    /**
     * Research Note facts of the selected how-to (null when it needs no Notes). {@code walletUsed}/{@code shortfall} are
     * null for an unknown wallet (never defaulted to zero, no production promised). {@code containersNeeded} is the
     * Peer Reviewed Research containers covering the shortfall BEFORE production (owned ones first, the rest crafted);
     * {@code ownedContainers}/{@code ownedNotes} are already owned progress to open, {@code containersCrafted}/
     * {@code producedNotes} planned work (locked when {@code producerLocked}), {@code acquireContainers} containers no
     * producer supplies (they claim NO Notes, and no surplus), {@code uncoveredNotes} the shortfall still uncovered;
     * {@code surplus} is the Notes opened beyond the shortfall. {@code estimateCopper} values ONLY wallet-used Notes at the cheaper
     * fully priced recipe's replacement cost (null = unavailable); produced Notes are charged by their inputs.
     */
    public record Notes(long needed, boolean walletKnown, Long walletUsed, Long shortfall, long containersNeeded,
                        long containersCrafted, long producedNotes, long surplus, Integer producerRecipeId,
                        ResearchNoteEstimate estimate, Long estimateCopper, String estimateIssue,
                        long ownedContainers, long ownedNotes, long acquireContainers, long uncoveredNotes,
                        boolean producerLocked) { }

    /** Opportunity value of consumed owned intermediates at the selected quote: known subtotal plus unpriced remainder. */
    public record OwnValue(long knownCopper, List<Integer> unpricedItemIds) { }

    /**
     * Planning estimate for Mystic Clovers produced by the owner-selected one-Clover Forge attempt (about 31% per
     * attempt, verified on the Mystic Clover wiki page): never owned stock, never guaranteed output.
     */
    public record CloverEstimate(long missing, long attempts, int percent) { }

    public record Project(int recipeId, int outputItemId, long batches, long guaranteedOutput,
                          List<CraftStep> steps, List<ItemLine> items, List<CurrencyLine> currencies,
                          Node tree, long knownCopper, List<Integer> unpricedItemIds, boolean complete, String incompleteReason,
                          Seed seed, Economics economics, Notes notes,
                          List<VendorPurchase> vendorPurchases, ForgeSettings settings, OwnValue ownValue,
                          CloverEstimate clover, long rootOwned) { }

    public static final int MYSTIC_CLOVER = 19675;
    public static final int CLOVER_PERCENT = 31;

    private final Map<Integer, Recipe> producerByItem = new java.util.HashMap<>();
    private final Map<Integer, Recipe> noteRecipes = new TreeMap<>();
    private static final int NOTES = CurrencyProduction.RESEARCH_NOTE_CURRENCY_ID;

    /** @param producers every recipe that may supply an intermediate (ordinary graph recipes, Forge, vendor) */
    public ForgeProjectPlanner(List<Recipe> producers) {
        List<Recipe> sorted = new ArrayList<>(producers);
        sorted.sort(Comparator.<Recipe>comparingInt(r -> rank(r.source)).thenComparingInt(r -> r.recipeId));
        for (Recipe r : sorted) {
            if (r.outputItemId == CurrencyProduction.PEER_REVIEWED_RESEARCH_ITEM_ID
                    && ResearchNoteEstimate.VARIANT_RECIPE_IDS.contains(r.recipeId)) noteRecipes.put(r.recipeId, r);
            if (r.outputCount <= 0) continue;
            // Random outcomes never feed a guaranteed plan, except a verified self-seeded promotion whose
            // guaranteed MINIMUM (net of the reused seed) is the only stock it ever claims.
            if (r.hasExpectedYield() && !CraftingResolver.isPromotion(r)) continue;
            Recipe have = producerByItem.get(r.outputItemId);
            if (have == null || (have.hasExpectedYield() && !r.hasExpectedYield())) producerByItem.put(r.outputItemId, r); // fixed producers win
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
            // A path that reaches Research Notes needs both Note variants' prices, independent of stock/unlock.
            if (r.currencies.stream().anyMatch(c -> c.currencyId() == NOTES)) {
                for (Recipe v : noteRecipes.values()) if (!seen.contains(v.recipeId)) queue.add(v);
                ids.add(CurrencyProduction.PEER_REVIEWED_RESEARCH_ITEM_ID);
            }
            for (Ingredient i : r.ingredients) {
                ids.add(i.itemId);
                Recipe p = producerByItem.get(i.itemId);
                if (p != null && !seen.contains(p.recipeId)) queue.add(p);
                if (i.itemId == MYSTIC_CLOVER) {
                    Recipe ref = ForgeReferenceRecipes.all().get(0).asPlanningTarget();
                    if (!seen.contains(ref.recipeId)) queue.add(ref); // the estimated attempt's inputs are priced path items
                }
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
        return plan(target, quantity, batchTarget, inv, ctx, wallet, prices, ForgeSettings.DEFAULT);
    }

    public Project plan(Recipe target, long quantity, boolean batchTarget, Inventory inv, Context ctx,
                        Map<Integer, Long> wallet, Map<Integer, PriceQuote> prices, ForgeSettings settings) {
        if (target == null || !target.isMysticForge()) throw new IllegalArgumentException("target must be a Mystic Forge recipe");
        if (quantity <= 0) throw new IllegalArgumentException("batches must be positive");
        Run run = new Run(new PlanState(inv.sellable(), inv.accountBound(), inv.characterBound()), prices, ctx, settings);
        long batches = 0;
        long netOut = -1; // batch target: NET guaranteed output (seed reuse deducted); -1 until computed
        Node tree = null;
        long rootOwnedOut = 0;
        Notes notes = null;
        try {
            long outUnits;
            long rootOwned = 0;
            if (batchTarget) {
                batches = quantity;
                outUnits = mul(batches, (long) target.outputCount);
                // A self-seeded expected-yield root's guaranteed stock is NET of the reused seed (gross stays in seed facts/steps).
                if (target.hasExpectedYield() && target.selfSeedUnits() > 0) outUnits = seedOf(target, batches).netGuaranteedOutput();
            } else {
                outUnits = quantity;
                rootOwned = run.consumeRoot(target.outputItemId, quantity);
                rootOwnedOut = rootOwned;
                batches = Math.ceilDiv(quantity - rootOwned, (long) target.outputCount);
                if (rootOwned > 0) run.fromOwned.merge(target.outputItemId, rootOwned, ForgeProjectPlanner::add);
                run.demand.merge(target.outputItemId, quantity, ForgeProjectPlanner::add);
            }
            if (batchTarget) netOut = outUnits;
            List<Node> kids = batches > 0 ? run.craft(target, batches, 0, new HashSet<>()) : List.of();
            long surplusOut = batches > 0 && !batchTarget ? sub(mul(batches, (long) target.outputCount), quantity - rootOwned) : 0;
            boolean fixedRoot = !batchTarget;
            tree = new Node(target.outputItemId, outUnits, rootOwned, 0, fixedRoot ? outUnits - rootOwned : outUnits, 0,
                    target.recipeId, target.source, batches, target.minRating, target.disciplinesText, run.reason, kids,
                    null, true, null, null, mul(batches, (long) target.outputCount),
                    fixedRoot ? Math.max(0, surplusOut) : 0); // only a fixed-output desired-quantity root has round-up surplus
            if (fixedRoot && surplusOut > 0) run.surplus.merge(target.outputItemId, surplusOut, ForgeProjectPlanner::add);
            if (run.reason == null) {
                int stepsBefore = run.steps.size();
                notes = planNotes(run, wallet, prices, tree, kids);
                if (notes != null && run.steps.size() > stepsBefore) {
                    // Note production precedes every craft that consumes its Notes (steps are executable in order).
                    List<CraftStep> produced = new ArrayList<>(run.steps.subList(stepsBefore, run.steps.size()));
                    run.steps.subList(stepsBefore, run.steps.size()).clear();
                    run.steps.addAll(0, produced);
                }
                if (notes != null && notes.containersNeeded() > 0 && run.noteNode != null) {
                    kids = new ArrayList<>(kids);
                    kids.add(0, run.noteNode);
                    tree = new Node(tree.itemId(), tree.needed(), tree.fromOwned(), tree.fromSurplus(), tree.toCraft(),
                            tree.toAcquire(), tree.recipeId(), tree.source(), tree.crafts(), tree.minRating(),
                            tree.disciplines(), run.reason, kids, null, true, null, null, tree.produced(), tree.newSurplus());
                }
            }
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
            Long unit = unitPrice(prices.get(id), settings.priceMode());
            if (missing > 0 && unit != null) {
                try { copper = add(copper, mul(unit, missing)); }
                catch (ArithmeticException ex) { run.stop("gold cost exceeds the supported range"); }
            } else if (missing > 0) unpriced.add(id);
            items.add(new ItemLine(id, e.getValue(), have, missing, unit));
        }
        List<CurrencyLine> currencies = new ArrayList<>();
        for (var e : run.currencyDemand.entrySet()) {
            Long have = wallet == null ? null : Math.max(0, wallet.getOrDefault(e.getKey(), 0L));
            Long missing = have == null ? null : Math.max(0, e.getValue() - have);
            // Research Notes: what remains to acquire is the authoritative UNCOVERED demand after Note production;
            // the before-production deficit stays available as notes.shortfall.
            if (e.getKey() == NOTES && notes != null && notes.walletKnown() && missing != null) missing = notes.uncoveredNotes();
            currencies.add(new CurrencyLine(e.getKey(), e.getValue(), have, missing));
        }
        List<VendorPurchase> vendorPurchases = new ArrayList<>();
        for (Recipe v : run.vendorRecipes.values()) {
            long buys = run.stepByRecipe.containsKey(v.recipeId) ? run.stepByRecipe.get(v.recipeId).crafts() : 0L;
            if (buys <= 0) continue;
            try {
                List<CurrencyLine> cost = new ArrayList<>();
                for (CurrencyRequirement c : v.currencies) cost.add(new CurrencyLine(c.currencyId(), mul((long) c.count(), buys), null, null));
                vendorPurchases.add(new VendorPurchase(v.outputItemId, buys, mul(buys, (long) v.outputCount),
                        mul((long) v.vendorCopper, buys), List.copyOf(cost), VendorAcquisitions.hintFor(v.outputItemId)));
            } catch (ArithmeticException ex) { run.stop("quantity exceeds the supported range"); }
        }
        long out;
        try { out = batchTarget ? netOut : mul(batches, (long) target.outputCount); } catch (ArithmeticException e) { out = -1; } // -1: unavailable, never a clamped number
        Seed seed = null;
        Economics econ = null;
        if (run.reason == null && (target.hasExpectedYield() || !batchTarget)) {
            try {
                if (target.hasExpectedYield()) seed = seedOf(target, batches);
                econ = economics(target, batches, seed, prices, copper, settings);
            } catch (ArithmeticException e) { seed = null; econ = null; run.stop("quantity exceeds the supported range"); }
        }
        if (run.cloverMissing > 0) out = -1; // random Clovers never guarantee downstream completion
        if (run.reason != null) out = -1; // a stopped run (including late economics overflow) promises no guaranteed output
        OwnValue ownValue = null;
        if (settings.valueOwn()) {
            long known = 0;
            List<Integer> unknown = new ArrayList<>();
            try {
                for (var e : run.fromOwned.entrySet()) {
                    if (e.getKey() == target.outputItemId || e.getValue() <= 0) continue; // the root output is not an input
                    Long unit = unitPrice(prices.get(e.getKey()), settings.priceMode());
                    if (unit == null) unknown.add(e.getKey()); else known = add(known, mul(unit, e.getValue()));
                }
            } catch (ArithmeticException ex) { run.stop("own-material value exceeds the supported range"); out = -1; }
            java.util.Collections.sort(unknown);
            ownValue = new OwnValue(known, List.copyOf(unknown));
        }
        CloverEstimate clover = run.cloverMissing > 0 ? new CloverEstimate(run.cloverMissing, run.cloverAttempts, CLOVER_PERCENT) : null;
        return new Project(target.recipeId, target.outputItemId, batches, out, List.copyOf(run.steps), items, currencies,
                tree, copper, unpriced, run.reason == null, run.reason, seed, econ, notes, List.copyOf(vendorPurchases),
                settings, ownValue, clover, rootOwnedOut);
    }

    /**
     * Research Notes of the whole selected how-to, aggregated once: a KNOWN wallet pays its available amount once and
     * the shortfall is covered by Peer Reviewed Research containers (owned first, rest crafted through the cheaper
     * eligible fully priced recipe, sharing this run's inventory/surplus). An unknown wallet promises nothing.
     */
    private Notes planNotes(Run run, Map<Integer, Long> wallet, Map<Integer, PriceQuote> prices, Node tree, List<Node> kids) {
        Long needed = run.currencyDemand.get(NOTES);
        if (needed == null || needed <= 0) return null;
        CurrencyProduction production = CurrencyProduction.forCurrency(NOTES).orElseThrow();
        Map<Integer, List<Recipe>> byOutput = new java.util.HashMap<>();
        for (Recipe r : noteRecipes.values()) byOutput.computeIfAbsent(r.outputItemId, k -> new ArrayList<>()).add(r);
        ResearchNoteEstimate estimate = ResearchNoteEstimate.compute(byOutput, prices,
                new CraftingSettings(true, true, 0, true, false, false));
        if (wallet == null) {
            return new Notes(needed, false, null, null, 0, 0, 0, 0, null, estimate, null, "wallet unknown", 0, 0, 0, 0, false);
        }
        long have = Math.max(0, wallet.getOrDefault(NOTES, 0L));
        long used = Math.min(needed, have);
        long shortfall = needed - used;
        Long cost = null;
        String issue = null;
        if (used > 0) {
            if (!estimate.available()) issue = "no completely priced Research Note recipe";
            else {
                try { cost = estimate.costCopperExact(used); }
                catch (ArithmeticException e) {
                    issue = "estimate exceeds the supported range";
                    run.stop("Research Note estimate exceeds the supported range");
                }
            }
        } else cost = 0L;
        long containers = Math.ceilDiv(shortfall, (long) production.yield());
        if (run.reason != null) {
            // Valuation range stop: keep the safe Note facts, plan no production, retain the whole deficit.
            return new Notes(needed, true, used, shortfall, 0, 0, 0, 0, null, estimate, cost, issue, 0, 0, 0, shortfall, false);
        }
        Recipe producer = containers > 0 ? chooseNoteProducer(run, estimate) : null;
        long crafted = 0;
        long ownedContainers = 0, acquireContainers = 0;
        boolean locked = false;
        if (containers > 0) {
            if (producer != null) run.noteProducer = producer;
            long before = run.crafted(CurrencyProduction.PEER_REVIEWED_RESEARCH_ITEM_ID);
            Node n = run.need(CurrencyProduction.PEER_REVIEWED_RESEARCH_ITEM_ID, containers, 1, new HashSet<>(), null, false);
            if (n == null) {
                return new Notes(needed, true, used, shortfall, 0, 0, 0, 0, null, estimate, cost, issue, 0, 0, 0, shortfall, false);
            }
            run.noteNode = n;
            crafted = run.crafted(CurrencyProduction.PEER_REVIEWED_RESEARCH_ITEM_ID) - before;
            ownedContainers = add(n.fromOwned(), n.fromSurplus());
            acquireContainers = n.toAcquire();
            locked = crafted > 0 && !n.performable();
        }
        long yield = production.yield();
        long covered = add(mul(ownedContainers, yield), mul(crafted, yield));
        // Only containers that are owned or planned for crafting claim Notes; a toAcquire-only container claims none.
        long surplus = Math.max(0, sub(covered, shortfall));
        return new Notes(needed, true, used, shortfall, containers, crafted,
                mul(crafted, yield), surplus,
                producer == null ? null : producer.recipeId, estimate, cost, issue,
                ownedContainers, mul(ownedContainers, yield), acquireContainers, Math.max(0, sub(shortfall, covered)), locked);
    }

    /**
     * Explicit tiers: eligible and fully priced first, then fully priced, then the lowest recipe id (shown
     * locked/unpriced). Within a priced tier the cheaper batch wins; ties keep the lower recipe id.
     */
    private Recipe chooseNoteProducer(Run run, ResearchNoteEstimate estimate) {
        Recipe best = null;
        int bestTier = Integer.MAX_VALUE;
        long bestCost = Long.MAX_VALUE;
        for (Recipe r : noteRecipes.values()) {
            ResearchNoteEstimate.Variant v = estimate.variants().stream().filter(x -> x.recipeId() == r.recipeId).findFirst().orElse(null);
            boolean priced = v != null && v.priced();
            boolean eligible = run.assign(r) != null && !Boolean.FALSE.equals(
                    run.ctx.knownRecipeIds() == null ? null : run.ctx.knownRecipeIds().contains(r.recipeId));
            int tier = priced ? (eligible ? 0 : 1) : 2;
            long cost = priced ? v.batchCostCopper() : 0;
            boolean better = best == null || tier < bestTier
                    || (tier == bestTier && (cost < bestCost || (cost == bestCost && r.recipeId < best.recipeId)));
            if (better) { best = r; bestTier = tier; bestCost = cost; }
        }
        return best;
    }

    private static long safeCopper(long v) {
        if (Math.abs(v) > MAX_SAFE) throw new ArithmeticException("beyond the exact JSON integer range");
        return v;
    }

    /** Cost side of the Trading Post: the lowest listing (ask, sell_unit_price). */
    private static Long unitPrice(PriceQuote q) { return unitPrice(q, ForgeSettings.PriceMode.INSTANT_BUY); }

    /** Instant buy = ask (sell_unit_price); Buy order = bid (buy_unit_price). A missing quote is unknown, never free. */
    private static Long unitPrice(PriceQuote q, ForgeSettings.PriceMode mode) {
        if (q == null) return null;
        Integer v = mode == ForgeSettings.PriceMode.BUY_ORDER ? q.buyUnit : q.sellUnit;
        return v != null && v > 0 ? (long) v : null;
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
        return add(s, mul(batches - 1, s - reusedPerBatch));
    }

    /** Guaranteed NET units of {@code b} batches of a self-seeded promotion: one external seed, then minimum-only reuse. */
    static long promotionNet(Recipe r, long b) {
        long reused = Math.min(r.selfSeedUnits(), r.expectedYield.min());
        return add(mul(b, (long) r.expectedYield.min()), -mul(b - 1, reused));
    }

    /** Smallest batch count whose guaranteed net output covers {@code units} (never an expected mean). */
    static long promotionBatches(Recipe r, long units) {
        long min = r.expectedYield.min();
        long reused = Math.min(r.selfSeedUnits(), min);
        long gain = min - reused; // > 0 for a promotion
        return units <= min ? 1 : add(1, Math.ceilDiv(units - min, gain));
    }

    private static Seed seedOf(Recipe r, long batches) {
        ExpectedYield y = r.expectedYield;
        long s = r.selfSeedUnits();
        long external = seedExternalUnits(r, batches);
        long totalSeed = mul(batches, s);
        long reused = totalSeed - external;
        long gross = mul(batches, (long) y.min());
        BigDecimal grossExp = y.mean().multiply(BigDecimal.valueOf(batches));
        return new Seed(external, reused, gross, gross - reused, grossExp, grossExp.subtract(BigDecimal.valueOf(reused)));
    }

    /**
     * Buy-all-direct-inputs -> instantly-sell-outputs comparison (not the how-to's cheapest path, and owned stock is
     * never free). Each direct input costs its valid Trading Post ask, else a verified vendor purchase (cash counted
     * once, currencies kept separate and never converted to gold), else it is listed as unpriced. A self-seed counts
     * only the external units. Proceeds use the bid; the 15% fee is applied once over the gross, half-up. Range overflow propagates (ArithmeticException) so the caller stops the run instead of returning a complete-looking null.
     */
    private Economics economics(Recipe r, long batches, Seed seed, Map<Integer, PriceQuote> prices, long shopping, ForgeSettings settings) {
        long gold = 0;
        Map<Integer, Long> currency = new TreeMap<>();
        List<Integer> unpricedInputs = new ArrayList<>();
        boolean conditional = false;
        gold = mul((long) r.vendorCopper, batches);
        for (CurrencyRequirement c : r.currencies) {
            currency.merge(c.currencyId(), mul((long) c.count(), batches), ForgeProjectPlanner::add);
            conditional = true;
        }
        for (Ingredient i : r.ingredients) {
            boolean seedInput = seed != null && i.itemId == r.outputItemId;
            long qty = seedInput ? seed.externalSeedUnits() : mul((long) i.count, batches);
            if (qty <= 0) continue;
            Long ask = unitPrice(prices.get(i.itemId), settings.priceMode());
            Recipe vendor = producerByItem.get(i.itemId);
            if (ask != null) gold = add(gold, mul(ask, qty));
            else if (vendor != null && vendor.isVendor()) {
                long buys = Math.ceilDiv(qty, (long) vendor.outputCount);
                gold = add(gold, mul((long) vendor.vendorCopper, buys));
                for (CurrencyRequirement c : vendor.currencies) {
                    currency.merge(c.currencyId(), mul((long) c.count(), buys), ForgeProjectPlanner::add);
                    conditional = true;
                }
            } else if (!unpricedInputs.contains(i.itemId)) unpricedInputs.add(i.itemId);
        }
        Long bid = bidPrice(prices.get(r.outputItemId));
        Long gross = null, fee = null, net = null;
        if (bid != null) {
            gross = seed != null
                    ? safeCopper(ExpectedYield.floorCopper(seed.netExpectedOutput(), (int) Math.min(Integer.MAX_VALUE, bid)))
                    : mul(bid, mul(batches, (long) r.outputCount));
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
        final Map<Integer, Recipe> vendorRecipes = new TreeMap<>();
        final List<CraftStep> steps = new ArrayList<>();
        long vendorCopper;
        Recipe noteProducer;
        Node noteNode;
        int nodes;
        String reason;

        final ForgeSettings settings;
        long cloverMissing;
        long cloverAttempts;

        Run(PlanState state, Map<Integer, PriceQuote> prices, Context ctx, ForgeSettings settings) {
            this.state = state; this.prices = prices; this.ctx = ctx; this.settings = settings;
        }

        long crafted(int outputItemId) {
            return stepByRecipe.values().stream().filter(st -> st.outputItemId() == outputItemId)
                    .mapToLong(CraftStep::crafts).sum();
        }

        void stop(String why) { if (reason == null) reason = why; }

        /** Takes owned stock through the shared binding rules for {@code assignee}; returns units used. */
        long consume(int itemId, long qty, String assignee) {
            if (!settings.useOwn()) return 0; // intermediates are planned without owned stock
            int ask = (int) Math.min(Integer.MAX_VALUE, qty);
            return ask <= 0 ? 0 : state.consumeInventoryWithBinding(itemId, ask, assignee).total();
        }

        /** Owned goal output counts once from actual output ownership, independent of the input assignee. */
        long consumeRoot(int itemId, long qty) {
            int ask = (int) Math.min(Integer.MAX_VALUE, qty);
            if (ask <= 0) return 0;
            return switch (settings.reduce()) {
                case NO -> 0;
                case YES -> state.consumeOwnedOutput(itemId, ask).total();
                case TRADABLE -> state.consumeSellableOutput(itemId, ask);
                case UNTRADABLE -> state.consumeBoundOutput(itemId, ask);
            };
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
                vendorCopper = add(vendorCopper, mul((long) r.vendorCopper, crafts));
                if (r.isVendor()) vendorRecipes.putIfAbsent(r.recipeId, r);
                for (CurrencyRequirement c : r.currencies) {
                    currencyDemand.merge(c.currencyId(), mul((long) c.count(), crafts), ForgeProjectPlanner::add);
                }
                path.add(r.recipeId);
                for (Ingredient i : r.ingredients) {
                    boolean seed = r.hasExpectedYield() && i.itemId == r.outputItemId;
                    long qty = seed ? seedQuantity(r, i, crafts) : mul((long) i.count, crafts);
                    Node n = need(i.itemId, qty, depth + 1, path, assignee, seed);
                    if (n != null) kids.add(n);
                }
                path.remove(r.recipeId);
                steps.removeIf(s -> s.recipeId() == r.recipeId);
                long total = add(stepByRecipe.containsKey(r.recipeId) ? stepByRecipe.get(r.recipeId).crafts() : 0L, crafts);
                CraftStep step = new CraftStep(r.recipeId, r.outputItemId, total, mul(total, (long) r.outputCount), r.source);
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
            return add(i.count, mul(crafts - 1, i.count - reusedPerBatch));
        }

        Node need(int itemId, long qty, int depth, Set<Integer> path, String assignee, boolean seedLeaf) {
            if (reason != null || qty <= 0) return null;
            demand.merge(itemId, qty, ForgeProjectPlanner::add);
            long use = consume(itemId, qty, assignee);
            if (use > 0) fromOwned.merge(itemId, use, ForgeProjectPlanner::add);
            long unmet = qty - use;
            long spare = Math.min(surplus.getOrDefault(itemId, 0L), unmet);
            if (spare > 0) { surplus.merge(itemId, -spare, Long::sum); unmet -= spare; } // conserved craft surplus, not owned stock
            if (unmet == 0) return new Node(itemId, qty, use, spare, 0, 0, null, null, 0, 0, "", null, List.of(), null, true, null, null, 0, 0);
            if (itemId == MYSTIC_CLOVER && !seedLeaf) return cloverEstimate(itemId, qty, use, spare, unmet, depth, path);
            Recipe p = seedLeaf ? null
                    : itemId == CurrencyProduction.PEER_REVIEWED_RESEARCH_ITEM_ID && noteProducer != null ? noteProducer
                    : producerByItem.get(itemId);
            // Daily Buy: the shortfall is purchased at the selected price, never recursively crafted.
            if (p != null && settings.daily() == ForgeSettings.DailyMode.BUY && DailyCrafts.isDailyOutput(itemId)) p = null;
            // Promotions: optional material-promotion producers only (the selected root is not expanded here).
            if (p != null && !settings.promotions() && CraftingResolver.isPromotion(p)) p = null; // required fixed Forge gifts stay
            if (p == null || path.contains(p.recipeId)) {
                leaf.merge(itemId, unmet, ForgeProjectPlanner::add);
                String cut = p == null ? null : "cycle: this item's producer is already being expanded above";
                return new Node(itemId, qty, use, spare, 0, unmet, null, null, 0, 0, "", cut, List.of(), null, true, null, null, 0, 0);
            }
            long crafts, produced;
            if (p.hasExpectedYield()) {
                crafts = promotionBatches(p, unmet);
                produced = promotionNet(p, crafts);
            } else {
                crafts = Math.ceilDiv(unmet, (long) p.outputCount);
                produced = mul(crafts, (long) p.outputCount);
            }
            long extra = sub(produced, unmet);
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
            // Surplus exists only after the craft's own inputs are secured; it must not finance that craft.
            if (extra > 0 && reason == null) surplus.merge(itemId, extra, ForgeProjectPlanner::add);
            return new Node(itemId, qty, use, spare, unmet, 0, p.recipeId, p.source, crafts, p.minRating,
                    p.disciplinesText, reason, kids, known, performable, who,
                    p.isVendor() ? (VendorAcquisitions.merchantStep(p.outputItemId) != null ? VendorAcquisitions.merchantStep(p.outputItemId) : VendorAcquisitions.hintFor(p.outputItemId))
                            : DailyCrafts.isDailyOutput(itemId) ? "Daily/time-gated output: not every batch is available today" : null,
                    produced, extra);
        }

        /** Estimated, never guaranteed: ceil(missing / 31%) one-Clover Forge attempts, inputs charged once. */
        Node cloverEstimate(int itemId, long qty, long use, long spare, long unmet, int depth, Set<Integer> path) {
            cloverMissing = add(cloverMissing, unmet);
            cloverAttempts = Math.ceilDiv(mul(cloverMissing, 100L), (long) CLOVER_PERCENT); // rounded up once, aggregate
            long delta = cloverAttempts - Math.ceilDiv(mul(cloverMissing - unmet, 100L), (long) CLOVER_PERCENT);
            List<Node> kids = new ArrayList<>();
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES) { stop("planning bound reached"); return null; }
            for (Ingredient i : ForgeReferenceRecipes.all().get(0).ingredients()) {
                Node n = need(i.itemId, mul((long) i.count, delta), depth + 1, path, null, false);
                if (n != null) kids.add(n);
            }
            // crafts carries the typed attempt count charged by THIS demand (aggregate delta), not the standalone ceil.
            return new Node(itemId, qty, use, spare, 0, unmet, null, null, delta, 0, "", reason, kids, null, true, null,
                    "Estimated: Mystic Forge one-Clover attempt (1 Obsidian Shard, 1 Mystic Coin, 1 Glob of Ectoplasm, 6 Philosopher's Stones), about "
                            + CLOVER_PERCENT + "% chance per attempt - " + delta + " attempts to cover " + unmet + " missing Clovers; not guaranteed",
                    0, 0);
        }
    }
}
