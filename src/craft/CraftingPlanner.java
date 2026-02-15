package craft;

import repo.RecipeRepository;
import repo.TpPriceRepository;

import java.util.*;

/**
 * Recursive planner:
 * - Inventory first
 * - If missing ingredient has a recipe -> craft it (recursively)
 * - Otherwise -> buy it (if allowed) or mark missing (if not allowed)
 *
 * CraftableCount is computed via binary search using canCraft(n).
 *
 * Notes:
 * - We create a Node tree representing how the planner satisfied needs.
 * - For simplicity we pick the FIRST recipe that produces an item (later we can choose best recipe).
 */
public class CraftingPlanner {

    private static final int MAX_CRAFT_CAP = 10_000;

    public Map<Integer, CraftResult> evaluateAll(List<RecipeRepository.Recipe> recipes,
                                                 Map<Integer, Integer> inventory,
                                                 Map<Integer, TpPriceRepository.TpQuote> tp,
                                                 CraftingSettings settings) {

        // Build lookup: output_item_id -> list of recipes that produce it
        Map<Integer, List<RecipeRepository.Recipe>> recipesByOutput = new HashMap<>();
        for (RecipeRepository.Recipe r : recipes) {
            recipesByOutput.computeIfAbsent(r.outputItemId, k -> new ArrayList<>()).add(r);
        }

        Map<Integer, CraftResult> out = new HashMap<>();

        for (RecipeRepository.Recipe r : recipes) {
            CraftResult cr = evaluateOneRecipeRecursive(r, inventory, tp, settings, recipesByOutput);
            out.put(r.recipeId, cr);
        }

        return out;
    }

    private CraftResult evaluateOneRecipeRecursive(RecipeRepository.Recipe recipe,
                                                   Map<Integer, Integer> baseInventory,
                                                   Map<Integer, TpPriceRepository.TpQuote> tp,
                                                   CraftingSettings settings,
                                                   Map<Integer, List<RecipeRepository.Recipe>> recipesByOutput) {

        // --- DAILY COOLDOWN HANDLING (buy instead of craft) ---
        if (DailyCrafts.isDailyOutput(recipe.outputItemId) && settings.dailyBuyInsteadOfCraft) {

            // If buying is not allowed -> this mode yields 0 craftable
            if (!settings.allowBuying) {
                return new CraftResult(
                        recipe.outputItemId,
                        recipe.disciplinesText,
                        0,
                        Map.of(),
                        0,
                        0,
                        0,
                        0,
                        new Node(recipe.outputItemId, recipe.outputCount, "daily-buy-disabled", List.of())
                );
            }

            TpPriceRepository.TpQuote outQ = tp.get(recipe.outputItemId);

            // BUY price of the OUTPUT item
            int buyUnit = 0;
            if (outQ != null) {
                // Instant buy = sellUnit, Listing buy = buyUnit
                Integer v = settings.listingBuy ? outQ.buyUnit : outQ.sellUnit;
                buyUnit = (v == null) ? 0 : v;
            }

            // SELL revenue of the OUTPUT item
            int sellUnit = 0;
            if (outQ != null) {
                // Listing sell = sellUnit, Instant sell = buyUnit
                Integer v = settings.listingSell ? outQ.sellUnit : outQ.buyUnit;
                sellUnit = (v == null) ? 0 : v;
            }

            int buyCostPerCraft  = buyUnit  * recipe.outputCount;
            int revenuePerCraft  = sellUnit * recipe.outputCount;
            int profitPerCraft   = revenuePerCraft - buyCostPerCraft;

            // how many can we "do" in buy-mode? = limited by budget (or capped)
            int craftableCount;
            if (buyCostPerCraft <= 0) {
                craftableCount = 0; // no price -> can't evaluate
            } else if (settings.maxBuyCopper <= 0) {
                craftableCount = MAX_CRAFT_CAP; // unlimited budget -> cap
            } else {
                craftableCount = settings.maxBuyCopper / buyCostPerCraft;
            }

            int totalBuyCost = buyCostPerCraft * craftableCount;
            int totalProfit  = profitPerCraft  * craftableCount;

            Map<Integer, Integer> missingToBuy = new HashMap<>();
            if (craftableCount > 0) {
                missingToBuy.put(recipe.outputItemId, recipe.outputCount * craftableCount);
            }

            Node tree = new Node(recipe.outputItemId, recipe.outputCount, "buy", List.of());

            return new CraftResult(
                    recipe.outputItemId,
                    recipe.disciplinesText,
                    craftableCount,

                    missingToBuy,       // totals in buy-mode
                    totalBuyCost,       // totals in buy-mode

                    revenuePerCraft,    // per craft
                    profitPerCraft,     // per craft
                    totalProfit,        // total
                    tree               // readable: “buy output”
            );
        }



        int craftableCount = computeMaxCraftable(recipe, baseInventory, tp, settings, recipesByOutput);

// Always compute per-craft view (for “profit per craft”)
        PlanRun one = simulateCraft(recipe, 1, baseInventory, tp, settings, recipesByOutput);

// Compute totals based on craftableCount (for “total profit” + correct Buy cost column)
        PlanRun max = (craftableCount > 0)
                ? simulateCraft(recipe, craftableCount, baseInventory, tp, settings, recipesByOutput)
                : new PlanRun(Map.of(), 0, one.tree); // or simulateCraft(recipe, 0, ...) if you allow 0

// Sell revenue per craft
        int outUnit = 0;
        TpPriceRepository.TpQuote outQ = tp.get(recipe.outputItemId);
        if (outQ != null) {
            Integer v = settings.listingSell ? outQ.sellUnit : outQ.buyUnit;
            outUnit = (v == null) ? 0 : v;
        }
        int revenuePerCraft = outUnit * recipe.outputCount;

// Profit per craft (uses ONE-craft buy cost)
        int profitPerCraft = revenuePerCraft - one.buyCostCopper;

// Totals (uses craftableCount-craft buy cost)
        int totalRevenue = revenuePerCraft * craftableCount;
        int totalProfit  = totalRevenue - max.buyCostCopper;

// IMPORTANT: return totals where your UI expects totals
        return new CraftResult(
                recipe.outputItemId,
                recipe.disciplinesText,
                craftableCount,

                max.missingToBuy,          // totals: missing/buy list for craftableCount
                max.buyCostCopper,         // totals: buy cost for craftableCount

                revenuePerCraft,           // per craft (rename column to “Item sell price”)
                profitPerCraft,            // per craft (rename to “Profit per craft”)
                totalProfit,               // NEW field you add
                one.tree                   // tree for ONE craft (nice to read)
        );

    }

    // ----------------------------
    // Max craftable count
    // ----------------------------

    private int computeMaxCraftable(RecipeRepository.Recipe recipe,
                                    Map<Integer, Integer> baseInventory,
                                    Map<Integer, TpPriceRepository.TpQuote> tp,
                                    CraftingSettings settings,
                                    Map<Integer, List<RecipeRepository.Recipe>> recipesByOutput) {

        int lo = 0;
        int hi = 1;

        // Exponential search for an upper bound
        while (hi < MAX_CRAFT_CAP && canCraft(recipe, hi, baseInventory, tp, settings, recipesByOutput)) {
            lo = hi;
            hi = hi * 2;
        }

        if (hi > MAX_CRAFT_CAP) hi = MAX_CRAFT_CAP;

        // Binary search between lo..hi
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (canCraft(recipe, mid, baseInventory, tp, settings, recipesByOutput)) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }

        return lo;
    }

    private boolean canCraft(RecipeRepository.Recipe recipe,
                             int crafts,
                             Map<Integer, Integer> baseInventory,
                             Map<Integer, TpPriceRepository.TpQuote> tp,
                             CraftingSettings settings,
                             Map<Integer, List<RecipeRepository.Recipe>> recipesByOutput) {

        PlanRun run = simulateCraft(recipe, crafts, baseInventory, tp, settings, recipesByOutput);

        if (!settings.allowBuying) {
            // no buying allowed -> must have no missing mats at all
            return run.missingToBuy.isEmpty();
        }

        // buying allowed -> budget check (if budget <= 0 treat as unlimited)
        if (settings.maxBuyCopper <= 0) return true;

        return run.buyCostCopper <= settings.maxBuyCopper;
    }

    // ----------------------------
    // Simulation for N crafts
    // ----------------------------

    private PlanRun simulateCraft(RecipeRepository.Recipe recipe,
                                  int crafts,
                                  Map<Integer, Integer> baseInventory,
                                  Map<Integer, TpPriceRepository.TpQuote> tp,
                                  CraftingSettings settings,
                                  Map<Integer, List<RecipeRepository.Recipe>> recipesByOutput) {

        // Work on a copy so each evaluation is isolated
        Map<Integer, Integer> inv = new HashMap<>(baseInventory);

        Map<Integer, Integer> missingToBuy = new HashMap<>();
        IntBox buyCost = new IntBox(0);
        Set<Integer> visiting = new HashSet<>();

        // Root describes: "I need output items"
        Node root = new Node(
                recipe.outputItemId,
                recipe.outputCount * crafts,
                "need",
                new ArrayList<>()
        );

        // Fill children = ingredient needs
        for (RecipeRepository.Ingredient ing : recipe.ingredients) {
            int needQty = ing.count * crafts;
            Node child = obtain(ing.itemId, needQty, inv, recipesByOutput, tp, settings, missingToBuy, buyCost, visiting);
            // root.children is List<Node>; we created it as ArrayList, so safe to add
            root.children.add(child);
        }

        return new PlanRun(missingToBuy, buyCost.value, root);
    }

    /**
     * Try to obtain qtyNeeded of itemId.
     * Order: inventory -> craft -> buy/mark missing
     *
     * Returns a Node describing what happened.
     */
    private Node obtain(int itemId,
                        int qtyNeeded,
                        Map<Integer, Integer> inv,
                        Map<Integer, List<RecipeRepository.Recipe>> recipesByOutput,
                        Map<Integer, TpPriceRepository.TpQuote> tp,
                        CraftingSettings settings,
                        Map<Integer, Integer> missingToBuy,
                        IntBox buyCost,
                        Set<Integer> visiting) {

        if (qtyNeeded <= 0) return new Node(itemId, 0, "need", List.of());

        int original = qtyNeeded;
        List<Node> children = new ArrayList<>();

        // 1) Use inventory
        int have = inv.getOrDefault(itemId, 0);
        if (have >= qtyNeeded) {
            inv.put(itemId, have - qtyNeeded);
            return new Node(itemId, original, "inventory", List.of());
        } else if (have > 0) {
            inv.remove(itemId);
            children.add(new Node(itemId, have, "inventory", List.of()));
            qtyNeeded -= have;
        }

        // 2) Craft if possible (and no cycle)
        List<RecipeRepository.Recipe> producing = recipesByOutput.get(itemId);
        if (producing != null && !producing.isEmpty() && !visiting.contains(itemId)) {
            visiting.add(itemId);

            RecipeRepository.Recipe r = producing.get(0); // v1: first recipe
            int times = ceilDiv(qtyNeeded, r.outputCount);

            List<Node> craftKids = new ArrayList<>();
            for (RecipeRepository.Ingredient sub : r.ingredients) {
                craftKids.add(obtain(
                        sub.itemId,
                        sub.count * times,
                        inv,
                        recipesByOutput,
                        tp,
                        settings,
                        missingToBuy,
                        buyCost,
                        visiting
                                    ));
            }

            int produced = times * r.outputCount;
            int leftover = produced - qtyNeeded;
            if (leftover > 0) inv.merge(itemId, leftover, Integer::sum);

            visiting.remove(itemId);

            Node craftNode = new Node(itemId, qtyNeeded, "craft", craftKids);

            if (children.isEmpty()) return craftNode;

            children.add(craftNode);
            return new Node(itemId, original, "need", children);
        }

        // 3) Not craftable (or cycle) -> buy or mark missing
        missingToBuy.merge(itemId, qtyNeeded, Integer::sum);

        if (settings.allowBuying) {
            // Instant buy cost = sell_unit_price
            TpPriceRepository.TpQuote q = tp.get(itemId);
            int unit = 0;
            if (q != null) {
                // Instant buy = sellUnit, Listing buy = buyUnit
                Integer v = settings.listingBuy ? q.buyUnit : q.sellUnit;
                unit = (v == null) ? 0 : v;
            }
            buyCost.value += unit * qtyNeeded;

            Node buyNode = new Node(itemId, qtyNeeded, "buy", List.of());
            if (children.isEmpty()) return buyNode;

            children.add(buyNode);
            return new Node(itemId, original, "need", children);
        }

        Node missNode = new Node(itemId, qtyNeeded, "missing", List.of());
        if (children.isEmpty()) return missNode;

        children.add(missNode);
        return new Node(itemId, original, "need", children);
    }

    // ----------------------------
    // Helpers
    // ----------------------------

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    private static class IntBox {
        int value;
        IntBox(int value) { this.value = value; }
    }

    private static class PlanRun {
        final Map<Integer, Integer> missingToBuy;
        final int buyCostCopper;
        final Node tree;

        PlanRun(Map<Integer, Integer> missingToBuy, int buyCostCopper, Node tree) {
            this.missingToBuy = missingToBuy;
            this.buyCostCopper = buyCostCopper;
            this.tree = tree;
        }
    }
}