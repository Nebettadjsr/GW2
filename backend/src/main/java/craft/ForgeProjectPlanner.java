package craft;

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

    public record CurrencyLine(int currencyId, long needed, long owned, long missing) { }

    public record Project(int recipeId, int outputItemId, long batches, long guaranteedOutput,
                          List<CraftStep> steps, List<ItemLine> items, List<CurrencyLine> currencies,
                          long knownCopper, List<Integer> unpricedItemIds, boolean complete, String incompleteReason) { }

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

    public Project plan(Recipe target, long batches, Map<Integer, Long> owned, Map<Integer, Long> wallet,
                        Map<Integer, PriceQuote> prices) {
        if (target == null || !target.isMysticForge()) throw new IllegalArgumentException("target must be a Mystic Forge recipe");
        if (batches <= 0) throw new IllegalArgumentException("batches must be positive");
        Run run = new Run(new java.util.HashMap<>(owned), prices);
        try {
            run.craft(target, batches, 0, new HashSet<>());
        } catch (ArithmeticException e) {
            run.stop("quantity exceeds the supported range");
        }
        List<ItemLine> items = new ArrayList<>();
        List<Integer> unpriced = new ArrayList<>();
        long copper = run.vendorCopper;
        for (var e : run.demand.entrySet()) {
            int id = e.getKey();
            long missing = run.leaf.getOrDefault(id, 0L);
            Long unit = null;
            PriceQuote q = prices.get(id);
            if (missing > 0 && q != null && q.sellUnit != null) {
                unit = (long) q.sellUnit;
                try { copper = Math.addExact(copper, Math.multiplyExact(unit, missing)); }
                catch (ArithmeticException ex) { run.stop("gold cost exceeds the supported range"); }
            } else if (missing > 0) unpriced.add(id);
            items.add(new ItemLine(id, e.getValue(), run.fromOwned.getOrDefault(id, 0L), missing, unit));
        }
        List<CurrencyLine> currencies = new ArrayList<>();
        for (var e : run.currencyDemand.entrySet()) {
            long have = Math.max(0, wallet.getOrDefault(e.getKey(), 0L));
            currencies.add(new CurrencyLine(e.getKey(), e.getValue(), have, Math.max(0, e.getValue() - have)));
        }
        long out;
        try { out = Math.multiplyExact(batches, (long) target.outputCount); } catch (ArithmeticException e) { out = Long.MAX_VALUE; }
        return new Project(target.recipeId, target.outputItemId, batches, out, List.copyOf(run.steps), items, currencies,
                copper, unpriced, run.reason == null, run.reason);
    }

    private final class Run {
        final Map<Integer, Long> stock;
        final Map<Integer, PriceQuote> prices;
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

        Run(Map<Integer, Long> stock, Map<Integer, PriceQuote> prices) { this.stock = stock; this.prices = prices; }

        void stop(String why) { if (reason == null) reason = why; }

        void craft(Recipe r, long crafts, int depth, Set<Integer> path) {
            if (reason != null) return;
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES) { stop("planning bound reached"); return; }
            vendorCopper = Math.addExact(vendorCopper, Math.multiplyExact((long) r.vendorCopper, crafts));
            for (CurrencyRequirement c : r.currencies) {
                currencyDemand.merge(c.currencyId(), Math.multiplyExact((long) c.count(), crafts), Math::addExact);
            }
            path.add(r.recipeId);
            for (Ingredient i : r.ingredients) need(i.itemId, Math.multiplyExact((long) i.count, crafts), depth + 1, path);
            path.remove(r.recipeId);
            steps.removeIf(s -> s.recipeId() == r.recipeId);
            long total = Math.addExact(stepByRecipe.containsKey(r.recipeId) ? stepByRecipe.get(r.recipeId).crafts() : 0L, crafts);
            CraftStep step = new CraftStep(r.recipeId, r.outputItemId, total, Math.multiplyExact(total, (long) r.outputCount), r.source);
            stepByRecipe.put(r.recipeId, step);
            steps.add(step);
        }

        void need(int itemId, long qty, int depth, Set<Integer> path) {
            if (reason != null || qty <= 0) return;
            demand.merge(itemId, qty, Math::addExact);
            long have = stock.getOrDefault(itemId, 0L);
            long use = Math.min(have, qty);
            if (use > 0) {
                stock.put(itemId, have - use);
                fromOwned.merge(itemId, use, Math::addExact);
            }
            long unmet = qty - use;
            long spare = Math.min(surplus.getOrDefault(itemId, 0L), unmet);
            if (spare > 0) { surplus.merge(itemId, -spare, Long::sum); unmet -= spare; } // conserved craft surplus, not owned stock
            if (unmet == 0) return;
            Recipe p = producerByItem.get(itemId);
            if (p == null || path.contains(p.recipeId)) {
                leaf.merge(itemId, unmet, Math::addExact);
                return;
            }
            long crafts = Math.ceilDiv(unmet, (long) p.outputCount);
            long extra = Math.subtractExact(Math.multiplyExact(crafts, (long) p.outputCount), unmet);
            if (extra > 0) surplus.merge(itemId, extra, Math::addExact);
            craft(p, crafts, depth, path);
        }
    }
}
