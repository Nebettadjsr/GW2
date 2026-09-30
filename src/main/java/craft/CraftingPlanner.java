package craft;

import tradingpost.TradingPostFeePolicy;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Recursive planner:
 * - Inventory first
 * - If missing ingredient has a recipe -> craft it (recursively)
 * - Otherwise -> buy it (if allowed) or mark missing (if not allowed)
 */
public class CraftingPlanner {

    private final CostEvaluator costEvaluator = new CostEvaluator();

    public Map<Integer, CraftResult> evaluateAll(List<Recipe> recipes,
                                                 Map<Integer, Integer> inventory,
                                                 Map<Integer, PriceQuote> tp,
                                                 CraftingSettings settings,
                                                 Set<Integer> allowedRecipeIds) {
        return evaluateAll(recipes, inventory, Map.of(), tp, settings, allowedRecipeIds);
    }

    /**
     * @param sellableInventory ordinary unbound/tradable owned quantity, priced exactly as
     *                          {@link #evaluateAll(List, Map, Map, CraftingSettings, Set)} always has.
     * @param boundInventory    account-bound/soulbound-and-usable-by-the-selected-character owned
     *                          quantity (DOMAIN_SPEC.md section 11.1 / DQ-007); consumed first and
     *                          never charged Trading-Post opportunity cost. Empty for callers that
     *                          don't have a selected character, so behavior is unchanged for them.
     */
    public Map<Integer, CraftResult> evaluateAll(List<Recipe> recipes,
                                                 Map<Integer, Integer> sellableInventory,
                                                 Map<Integer, Integer> boundInventory,
                                                 Map<Integer, PriceQuote> tp,
                                                 CraftingSettings settings,
                                                 Set<Integer> allowedRecipeIds) {

        Map<Integer, List<Recipe>> recipesByOutput = buildRecipesByOutput(recipes);
        PlannerContext ctx = new PlannerContext(recipesByOutput, tp, settings, allowedRecipeIds);

        // Every recipe's simulation only reads shared/immutable inputs (ctx, sellableInventory,
        // boundInventory) and writes to its own fresh PlanState/RecipeSimulator, so recipes are
        // evaluated independently of one another; the profiled bottleneck (STORY-PERF-001) is
        // this per-recipe simulation cost, not I/O, so distributing it across cores is a bounded,
        // result-preserving optimization rather than an assumed one.
        return recipes.parallelStream().collect(Collectors.toConcurrentMap(
                r -> r.recipeId,
                r -> evaluateOneRecipeNew(r, new PlanState(sellableInventory, boundInventory), ctx, new RecipeSimulator()),
                (a, b) -> b
        ));
    }

    /**
     * Coordinated multi-character planning (DOMAIN_SPEC.md section 2.2.1 / STORY-DOM-014): unlike
     * {@link #evaluateAll(List, Map, Map, Map, CraftingSettings, Set)}, {@code roster} identifies
     * every candidate character and their crafting ratings, so each recipe/sub-recipe execution
     * may be assigned to whichever eligible candidate can perform it, and {@code
     * characterBoundInventory} keeps each character's soulbound owned quantity usable only by a
     * step assigned to that exact character. {@code accountBoundInventory} (account-bound, no TP
     * opportunity cost) and {@code sellableInventory} (ordinary tradable) remain shared account-
     * wide pools, exactly as the legacy overload's single {@code boundInventory} always was.
     */
    public Map<Integer, CraftResult> evaluateAllCoordinated(
            List<Recipe> recipes,
            Map<Integer, Integer> sellableInventory,
            Map<Integer, Integer> accountBoundInventory,
            Map<String, Map<Integer, Integer>> characterBoundInventory,
            List<CharacterCraftingProfile> roster,
            Map<Integer, PriceQuote> tp,
            CraftingSettings settings,
            Set<Integer> allowedRecipeIds) {

        Map<Integer, List<Recipe>> recipesByOutput = buildRecipesByOutput(recipes);
        PlannerContext ctx = new PlannerContext(recipesByOutput, tp, settings, allowedRecipeIds,
                roster);

        // See evaluateAll(...)'s parallelization note: identical independence argument applies
        // here, including for the coordinated per-character trial paths, since each recipe still
        // only ever mutates its own fresh PlanState.
        return recipes.parallelStream().collect(Collectors.toConcurrentMap(
                r -> r.recipeId,
                r -> evaluateOneRecipeNew(
                        r,
                        new PlanState(sellableInventory, accountBoundInventory, characterBoundInventory),
                        ctx,
                        new RecipeSimulator())
        ));
    }

    /**
     * The result for exactly one recipe, computed the way
     * {@link #evaluateAll(List, Map, Map, Map, CraftingSettings, Set)} computes that recipe's
     * entry: the same context, the same fresh {@link PlanState} built from the supplied initial
     * inventory, and the same per-recipe evaluation - including its heuristic skip. Provided for
     * the selected-recipe detail operation (TARGET_ARCHITECTURE.md section 13.2), which needs one
     * row rather than a whole table; it is an entry point, not a second set of rules.
     */
    public CraftResult evaluateOne(Recipe recipe,
                                   List<Recipe> recipes,
                                   Map<Integer, Integer> sellableInventory,
                                   Map<Integer, Integer> boundInventory,
                                   Map<Integer, PriceQuote> tp,
                                   CraftingSettings settings,
                                   Set<Integer> allowedRecipeIds) {

        PlannerContext ctx = new PlannerContext(
                buildRecipesByOutput(recipes), tp, settings, allowedRecipeIds);

        return evaluateOneRecipeNew(
                recipe, new PlanState(sellableInventory, boundInventory), ctx, new RecipeSimulator());
    }

    /**
     * As {@link #evaluateOne(Recipe, List, Map, Map, Map, CraftingSettings, Set)}, but on the
     * coordinated-roster path, computing exactly that recipe's entry of
     * {@link #evaluateAllCoordinated(List, Map, Map, Map, List, Map, CraftingSettings, Set)}.
     */
    public CraftResult evaluateOneCoordinated(
            Recipe recipe,
            List<Recipe> recipes,
            Map<Integer, Integer> sellableInventory,
            Map<Integer, Integer> accountBoundInventory,
            Map<String, Map<Integer, Integer>> characterBoundInventory,
            List<CharacterCraftingProfile> roster,
            Map<Integer, PriceQuote> tp,
            CraftingSettings settings,
            Set<Integer> allowedRecipeIds) {

        PlannerContext ctx = new PlannerContext(
                buildRecipesByOutput(recipes), tp, settings, allowedRecipeIds, roster);

        return evaluateOneRecipeNew(
                recipe,
                new PlanState(sellableInventory, accountBoundInventory, characterBoundInventory),
                ctx,
                new RecipeSimulator());
    }

    static Map<Integer, List<Recipe>> buildRecipesByOutput(List<Recipe> recipes) {
        Map<Integer, List<Recipe>> recipesByOutput = new HashMap<>();
        for (Recipe r : recipes) {
            recipesByOutput.computeIfAbsent(r.outputItemId, k -> new ArrayList<>()).add(r);
        }
        return recipesByOutput;
    }

    private CraftResult evaluateOneRecipeNew(
            Recipe recipe,
            PlanState baseState,
            PlannerContext ctx,
            RecipeSimulator recipeSimulator
    ) {
        RecipeSimulationResult sim;

        // A disabled inventory or buying source does not disable recursive crafting. In
        // particular, direct-price profitability estimates cannot safely prove a recipe
        // uncraftable when its ingredients may themselves be crafted.
        sim = recipeSimulator.simulateRecipe(recipe, ctx, baseState);
        CostEvaluationResult cost = costEvaluator.evaluate(recipe, sim, ctx.tp, ctx.settings);

        ResolvedNeed firstCraft = sim.getFirstCraft();
        Node tree = null;

        Map<Integer, Integer> missingToBuyOne = new HashMap<>();
        if (firstCraft != null) {
            collectBoughtItems(firstCraft, missingToBuyOne);
        }

        Map<Integer, MaterialPurchaseCost> purchaseCosts = purchaseCosts(
                sim.getTotalMissingToBuy(), ctx.tp, ctx.settings);
        int totalBuyCost = sumPurchaseCosts(purchaseCosts);
        int buyCostOne = sumPurchaseCosts(purchaseCosts(missingToBuyOne, ctx.tp, ctx.settings));

        int revenueOne = cost.getRevenuePerCraft();
        int matsSellOne = cost.getOpportunityCostPerCraft();

        // DOMAIN_SPEC.md §25/§26 (resolved UD-011): profit is the gross output sell value less the
        // decided 15% Trading Post fee, purchased-material cost and owned-material opportunity cost.
        // The fee is charged here, once, and only against the profit: revenueOne stays the gross
        // figure §24 defines, which is what the totals, the market displays and the availability
        // predicates below and downstream go on reading. The fee never exceeds the revenue it is
        // taken from, so it still fits an int.
        //
        // A revenue of zero or less is not a sale and carries no fee: that is the value an
        // unavailable or non-positive output quote already reports (DOMAIN_SPEC.md §21), and the
        // downstream availability predicates still see exactly the figure they saw before.
        int feeOne = revenueOne > 0 ? (int) TradingPostFeePolicy.feeOn(revenueOne) : 0;
        int profitOne = revenueOne - feeOne - buyCostOne - matsSellOne;

        // Owned-material use can vary by execution, so the total uses full-plan aggregates below.

        // DOMAIN_SPEC.md §2.1.1: §25's per-execution output revenue - which already carries the
        // recipe's output quantity and deducts no selling fee - for §28's craftable count. Built
        // from the two figures above rather than from a second resolver or price read.
        int totalSellValue = revenueOne * sim.getCraftCount();
        // Inventory consumed can vary between executions, so total profit uses costs from the
        // accepted full plan instead of scaling the first craft's opportunity cost.
        int totalProfit = totalSellValue - feeOne * sim.getCraftCount()
                - sim.getBuyCostTotal() - sim.getOpportunityCostTotal();

        return new CraftResult(
                recipe.outputItemId,
                recipe.disciplinesText,
                sim.getCraftCount(),
                sim.getTotalMissingToBuy(),
                missingToBuyOne,
                totalBuyCost,
                matsSellOne,
                revenueOne,
                profitOne,
                totalProfit,
                totalSellValue,
                tree,
                sim.getBlockedReason(),
                sim.getOpportunityCostTotal(),
                purchaseCosts
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

    private Map<Integer, MaterialPurchaseCost> purchaseCosts(Map<Integer, Integer> missing,
                                                              Map<Integer, PriceQuote> tp,
                                                              CraftingSettings settings) {
        Map<Integer, MaterialPurchaseCost> costs = new HashMap<>();
        for (Map.Entry<Integer, Integer> entry : missing.entrySet()) {
            PriceQuote quote = tp.get(entry.getKey());
            Integer unit = quote == null ? null : settings.listingBuy ? quote.buyUnit : quote.sellUnit;
            Integer total = unit == null ? null : unit * entry.getValue();
            costs.put(entry.getKey(), new MaterialPurchaseCost(unit, total));
        }
        return Map.copyOf(costs);
    }

    private int sumPurchaseCosts(Map<Integer, MaterialPurchaseCost> costs) {
        return costs.values().stream()
                .map(MaterialPurchaseCost::totalPriceCopper)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
    }

}
