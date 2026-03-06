package craft;

import repo.RecipeRepository;
import repo.tp.TpPriceRepository;

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

    private static final int MAX_CRAFT_CAP = 250;
    private final RecipeSimulator recipeSimulator = new RecipeSimulator();
    private final CostEvaluator costEvaluator = new CostEvaluator();
    private final ResolvedNeedMapper resolvedNeedMapper = new ResolvedNeedMapper();
    private final RecipeTreeBuilder recipeTreeBuilder = new RecipeTreeBuilder();

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

        PlannerContext ctx = new PlannerContext(recipesByOutput, tp, settings);

        for (RecipeRepository.Recipe r : recipes) {
            CraftResult cr = evaluateOneRecipeNew(r, inventory, ctx);
            out.put(r.recipeId, cr);
        }

        return out;
    }

    private CraftResult evaluateOneRecipeRecursive(
            RecipeRepository.Recipe recipe,
            Map<Integer, Integer> baseInventory,
            PlannerContext ctx) {

        // --- DAILY COOLDOWN HANDLING (buy instead of craft) ---
        if (DailyCrafts.isDailyOutput(recipe.outputItemId) && ctx.settings.dailyBuyInsteadOfCraft) {

            // If buying is not allowed -> this mode yields 0 craftable
            if (!ctx.settings.allowBuying) {
                return new CraftResult(
                        recipe.outputItemId,
                        recipe.disciplinesText,
                        0,
                        Map.of(),
                        Map.of(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        new Node(recipe.outputItemId, recipe.outputCount, "daily-buy-disabled", List.of())
                );
            }

            TpPriceRepository.TpQuote outQ = ctx.tp.get(recipe.outputItemId);

            // BUY price of the OUTPUT item
            int buyUnit = 0;
            if (outQ != null) {
                // Instant buy = sellUnit, Listing buy = buyUnit
                Integer v = ctx.settings.listingBuy ? outQ.buyUnit : outQ.sellUnit;
                buyUnit = (v == null) ? 0 : v;
            }

            // SELL revenue of the OUTPUT item
            int sellUnit = 0;
            if (outQ != null) {
                // Listing sell = sellUnit, Instant sell = buyUnit
                Integer v = ctx.settings.listingSell ? outQ.sellUnit : outQ.buyUnit;
                sellUnit = (v == null) ? 0 : v;
            }

            int buyCostPerCraft  = buyUnit  * recipe.outputCount;
            int revenuePerCraft  = sellUnit * recipe.outputCount;
            int profitPerCraft   = revenuePerCraft - buyCostPerCraft;

            // how many can we "do" in buy-mode? = limited by budget (or capped)
            int craftableCount;
            if (buyCostPerCraft <= 0) {
                craftableCount = 0; // no price -> can't evaluate
            } else if (ctx.settings.maxBuyCopper <= 0) {
                craftableCount = MAX_CRAFT_CAP; // unlimited budget -> cap
            } else {
                craftableCount = ctx.settings.maxBuyCopper / buyCostPerCraft;
            }

            int totalProfit  = profitPerCraft  * craftableCount;

            Map<Integer, Integer> missingToBuy = new HashMap<>();
            if (craftableCount > 0) {
                missingToBuy.put(recipe.outputItemId, recipe.outputCount * craftableCount);
            }
            Map<Integer, Integer> missingToBuyOne = new HashMap<>();
            if (recipe.outputCount > 0) {
                missingToBuyOne.put(recipe.outputItemId, recipe.outputCount);
            }

            Node tree = new Node(recipe.outputItemId, recipe.outputCount, "buy", List.of());

            return new CraftResult(
                    recipe.outputItemId,
                    recipe.disciplinesText,
                    craftableCount,
                    missingToBuy,
                    missingToBuyOne,
                    buyCostPerCraft,  // PER 1 craft
                    0,                // matsSellPerCraft (not applicable here)
                    revenuePerCraft,  // PER 1 craft
                    profitPerCraft,   // PER 1 craft
                    totalProfit,      // TOTAL
                    tree
            );
        }



        int craftableCount = computeMaxCraftable(recipe, baseInventory, ctx);

        PlanRun one = simulateCraft(recipe, 1, baseInventory, ctx);
        PlanRun max = (craftableCount > 0)
                      ? simulateCraft(recipe, craftableCount, baseInventory, ctx)
                      : new PlanRun(Map.of(), 0, one.tree);

// --- PER 1 craft output sell revenue (GROSS from TP quote) ---
        int outUnit = 0;
        TpPriceRepository.TpQuote outQ = ctx.tp.get(recipe.outputItemId);
        if (outQ != null) {
            Integer v = ctx.settings.listingSell ? outQ.sellUnit : outQ.buyUnit; // your existing mapping
            outUnit = (v == null) ? 0 : v;
        }
        int revenuePerCraftGross = outUnit * recipe.outputCount;

// --- PER 1 craft mats sell value (GROSS) from leaf materials ---
        int matsSellGross = computeMatsSellValueFromTree(one.tree, ctx.tp, ctx.settings);

        // --- Buy cost per craft (ONLY ONE craft!) ---
        int buyCostPerCraft = one.buyCostCopper;

// --- "Value add" profit per craft ---
        int profitPerCraft = revenuePerCraftGross - buyCostPerCraft - matsSellGross;


// --- TOTAL profit: if buying enabled, use real cash profit ---
        int totalProfit = profitPerCraft * craftableCount;

// IMPORTANT: buyCostCopper field should now be PER 1 craft (not max)
        return new CraftResult(
                recipe.outputItemId,
                recipe.disciplinesText,
                craftableCount,

                max.missingToBuy,      // keep TOTAL missing list for craftableCount (shopping list)
                one.missingToBuy,
                buyCostPerCraft,       // PER 1 craft  ✅ (THIS is your requested change)

                matsSellGross,           // PER 1 craft (net)
                revenuePerCraftGross,    // PER 1 craft (net)
                profitPerCraft,        // PER 1 craft (value add)
                totalProfit,           // TOTAL (cash profit if buying enabled)

                one.tree
        );


    }

    // ----------------------------
    // Max craftable count
    // ----------------------------

    private int computeMaxCraftable(
            RecipeRepository.Recipe recipe,
            Map<Integer, Integer> baseInventory,
            PlannerContext ctx) {

        int lo = 0;
        int hi = 1;

        // Exponential search for an upper bound
        while (hi < MAX_CRAFT_CAP && canCraft(recipe, hi, baseInventory, ctx)) {
            lo = hi;
            hi = hi * 2;
        }

        if (hi > MAX_CRAFT_CAP) hi = MAX_CRAFT_CAP;

        // Binary search between lo..hi
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (canCraft(recipe, mid, baseInventory, ctx)) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }

        return lo;
    }

    private boolean canCraft(
            RecipeRepository.Recipe recipe,
            int crafts,
            Map<Integer, Integer> baseInventory,
            PlannerContext ctx) {

        PlanRun run = simulateCraft(recipe, crafts, baseInventory, ctx);

        if (!ctx.settings.allowBuying) {
            // no buying allowed -> must have no missing mats at all
            return run.missingToBuy.isEmpty();
        }

        // buying allowed -> budget check (if budget <= 0 treat as unlimited)
        if (ctx.settings.maxBuyCopper <= 0) return true;

        return run.buyCostCopper <= ctx.settings.maxBuyCopper;
    }

    // ----------------------------
    // Simulation for N crafts
    // ----------------------------

    private PlanRun simulateCraft(
            RecipeRepository.Recipe recipe,
            int crafts,
            Map<Integer, Integer> baseInventory,
            PlannerContext ctx) {

        PlanState state = new PlanState(baseInventory);

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
            Node child = obtain(
                    ing.itemId,
                    needQty,
                    ctx,
                    state
                               );
            // root.children is List<Node>; we created it as ArrayList, so safe to add
            root.children.add(child);
        }

        return new PlanRun(state.missingToBuy, state.buyCostCopper, root);
    }

    /**
     * Try to obtain qtyNeeded of itemId.
     * Order: inventory -> craft -> buy/mark missing
     *
     * Returns a Node describing what happened.
     */
    private Node obtain(int itemId,
                        int qtyNeeded,
                        PlannerContext ctx,
                        PlanState state) {

        if (qtyNeeded <= 0) return new Node(itemId, 0, "need", List.of());

        int original = qtyNeeded;
        List<Node> children = new ArrayList<>();

        // 1) Use inventory
        int have = state.inventory.getOrDefault(itemId, 0);
        boolean isDaily = DailyCrafts.isDailyOutput(itemId);

        if (have >= qtyNeeded) {
            state.inventory.put(itemId, have - qtyNeeded);
            return new Node(itemId, original, "inventory", List.of());
        } else if (have > 0) {
            state.inventory.remove(itemId);
            children.add(new Node(itemId, have, "inventory", List.of()));
            qtyNeeded -= have;
        }
        if (isDaily) {
            // If the user chose "buy dailies", we NEVER craft the daily item.
            // We also generally can't buy it (most are non-tradable), so we mark it as daily-blocked.
            if (ctx.settings.dailyBuyInsteadOfCraft) {
                return new Node(itemId, original, "daily-blocked", List.of());
            }

            // User chose "craft dailies": allow crafting at most ONCE per simulation run.
            int left = state.dailyLeft.getOrDefault(itemId, 1);
            if (left <= 0) {
                return new Node(itemId, original, "daily-capped", List.of());
            }

        }

        // 2) Craft if possible (and no cycle)
        List<RecipeRepository.Recipe> producing = ctx.recipesByOutput.get(itemId);
        if (producing != null && !producing.isEmpty() && !state.visiting.contains(itemId)) {
            state.visiting.add(itemId);

            RecipeRepository.Recipe r = producing.get(0); // v1: first recipe
            int times = ceilDiv(qtyNeeded, r.outputCount);

// DAILY: limit crafting to at most once
            if (isDaily) {
                int left = state.dailyLeft.getOrDefault(itemId, 1);
                times = Math.min(times, left);
            }

            if (times > 0) {
                if (isDaily) {
                    int left = state.dailyLeft.getOrDefault(itemId, 1);
                    state.dailyLeft.put(itemId, left - times);
                }

                List<Node> craftKids = new ArrayList<>();
                for (RecipeRepository.Ingredient sub : r.ingredients) {
                    craftKids.add(obtain(
                            sub.itemId,
                            sub.count * times,
                            ctx,
                            state
                                        ));
                }

                int produced = times * r.outputCount;
                int stillNeeded = qtyNeeded - produced;

                int leftover = produced - qtyNeeded;
                if (leftover > 0) {
                    state.inventory.merge(itemId, leftover, Integer::sum);
                }

                state.visiting.remove(itemId);

                Node craftNode = new Node(itemId, produced, "craft", craftKids);

                // If daily child blocked and user wants to buy dailies,
                // try buying this parent instead.
                if (ctx.settings.dailyBuyInsteadOfCraft && ctx.settings.allowBuying) {
                    boolean hasDailyBlockedChild = craftKids.stream().anyMatch(n ->
                                                                                       "daily-blocked".equals(n.action) || "daily-capped".equals(n.action)
                                                                              );

                    if (hasDailyBlockedChild) {
                        TpPriceRepository.TpQuote q = ctx.tp.get(itemId);
                        Integer unitObj = null;
                        if (q != null) unitObj = ctx.settings.listingBuy ? q.buyUnit : q.sellUnit;
                        int unit = (unitObj == null) ? 0 : unitObj;

                        if (unit > 0) {
                            state.missingToBuy.merge(itemId, qtyNeeded, Integer::sum);
                            state.buyCostCopper += unit * qtyNeeded;
                            return new Node(itemId, original, "buy(daily-parent)", List.of());
                        }

                        return new Node(itemId, original, "daily-chain-blocked", craftKids);
                    }
                }

                // crafted enough
                if (stillNeeded <= 0) {
                    if (children.isEmpty()) return craftNode;

                    children.add(craftNode);
                    return new Node(itemId, original, "need", children);
                }

                // crafted only part of what we needed
                children.add(craftNode);

                // remaining quantity: cannot craft more daily items today
                state.missingToBuy.merge(itemId, stillNeeded, Integer::sum);

                if (ctx.settings.allowBuying) {
                    TpPriceRepository.TpQuote q = ctx.tp.get(itemId);
                    int unit = 0;
                    if (q != null) {
                        Integer v = ctx.settings.listingBuy ? q.buyUnit : q.sellUnit;
                        unit = (v == null) ? 0 : v;
                    }

                    state.buyCostCopper += unit * stillNeeded;
                    children.add(new Node(itemId, stillNeeded, "buy", List.of()));
                } else {
                    children.add(new Node(itemId, stillNeeded, "missing", List.of()));
                }

                return new Node(itemId, original, "need", children);
            }
        }

        // 3) Not craftable (or cycle) -> buy or mark missing
        state.missingToBuy.merge(itemId, qtyNeeded, Integer::sum);

        if (ctx.settings.allowBuying) {
            // Instant buy cost = sell_unit_price
            TpPriceRepository.TpQuote q = ctx.tp.get(itemId);
            int unit = 0;
            if (q != null) {
                // Instant buy = sellUnit, Listing buy = buyUnit
                Integer v = ctx.settings.listingBuy ? q.buyUnit : q.sellUnit;
                unit = (v == null) ? 0 : v;
            }
            state.buyCostCopper += unit * qtyNeeded;

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

    private int computeMatsSellValueFromTree(Node n,
                                             Map<Integer, TpPriceRepository.TpQuote> tp,
                                             CraftingSettings settings) {
        if (n == null) return 0;

        // If it has children, it’s not a leaf material -> sum children
        if (n.children != null && !n.children.isEmpty()) {
            int sum = 0;
            for (Node ch : n.children) {
                sum += computeMatsSellValueFromTree(ch, tp, settings);
            }
            return sum;
        }

        // Leaf: treat as a base material amount that could be sold
        // We want to count inventory/missing/buy leaves (not "need")
        if (!"inventory".equals(n.action)) return 0;

        TpPriceRepository.TpQuote q = tp.get(n.itemId);
        if (q == null) return 0;

        Integer unit = settings.listingSell ? q.sellUnit : q.buyUnit;
        if (unit == null) return 0;

        return unit * n.qty;
    }

    private CraftResult evaluateOneRecipeNew(
            RecipeRepository.Recipe recipe,
            Map<Integer, Integer> baseInventory,
            PlannerContext ctx
                                            ) {
        PlanState baseState = new PlanState(baseInventory);

        RecipeSimulationResult sim = recipeSimulator.simulateRecipe(recipe, ctx, baseState);
        CostEvaluationResult cost = costEvaluator.evaluate(recipe, sim, ctx.tp, ctx.settings);

        ResolvedNeed firstCraft = sim.getFirstCraft();
        Node tree = recipeTreeBuilder.buildTree(recipe, ctx);

        Map<Integer, Integer> missingToBuyOne = new HashMap<>();
        if (firstCraft != null) {
            collectBoughtItems(firstCraft, missingToBuyOne);
        }

        return new CraftResult(
                recipe.outputItemId,
                recipe.disciplinesText,
                sim.getCraftCount(),
                sim.getTotalMissingToBuy(),
                missingToBuyOne,
                cost.getBuyCostPerCraft(),
                cost.getOpportunityCostPerCraft(),
                cost.getRevenuePerCraft(),
                cost.getProfitPerCraft(),
                cost.getTotalProfit(),
                tree
        );
    }

    private void collectBoughtItems(ResolvedNeed need, Map<Integer, Integer> out) {
        if (need == null) return;

        if (need.getQtyBought() > 0) {
            out.merge(need.getItemId(), need.getQtyBought(), Integer::sum);
        }

        for (ResolvedNeed child : need.getChildren()) {
            collectBoughtItems(child, out);
        }
    }

}