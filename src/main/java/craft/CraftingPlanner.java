package craft;

import repo.RecipeRepository;
import repo.tp.TpPriceRepository;

import java.util.*;

/**
 * Recursive planner:
 * - Inventory first
 * - If missing ingredient has a recipe -> craft it (recursively)
 * - Otherwise -> buy it (if allowed) or mark missing (if not allowed)
 */
public class CraftingPlanner {

    private final RecipeSimulator recipeSimulator = new RecipeSimulator();
    private final CostEvaluator costEvaluator = new CostEvaluator();

    public Map<Integer, CraftResult> evaluateAll(List<RecipeRepository.Recipe> recipes,
                                                 Map<Integer, Integer> inventory,
                                                 Map<Integer, TpPriceRepository.TpQuote> tp,
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
    public Map<Integer, CraftResult> evaluateAll(List<RecipeRepository.Recipe> recipes,
                                                 Map<Integer, Integer> sellableInventory,
                                                 Map<Integer, Integer> boundInventory,
                                                 Map<Integer, TpPriceRepository.TpQuote> tp,
                                                 CraftingSettings settings,
                                                 Set<Integer> allowedRecipeIds) {

        // Build lookup: output_item_id -> list of recipes that produce it
        Map<Integer, List<RecipeRepository.Recipe>> recipesByOutput = new HashMap<>();
        for (RecipeRepository.Recipe r : recipes) {
            recipesByOutput.computeIfAbsent(r.outputItemId, k -> new ArrayList<>()).add(r);
        }

        Map<Integer, CraftResult> out = new HashMap<>();

        PlannerContext ctx = new PlannerContext(recipesByOutput, tp, settings, allowedRecipeIds);
        for (RecipeRepository.Recipe r : recipes) {
            CraftResult cr = evaluateOneRecipeNew(r, sellableInventory, boundInventory, ctx);
            out.put(r.recipeId, cr);
        }

        return out;
    }

    private CraftResult evaluateOneRecipeNew(
            RecipeRepository.Recipe recipe,
            Map<Integer, Integer> baseInventory,
            Map<Integer, Integer> boundInventory,
            PlannerContext ctx
    ) {
        PlanState baseState = new PlanState(baseInventory, boundInventory);

        RecipeSimulationResult sim;

        boolean maySkipCheap =
                !ctx.settings.useOwnMats &&
                        !ctx.settings.allowBuying;

        if (maySkipCheap && !shouldSimulateRecipe(recipe, ctx)) {
            sim = new RecipeSimulationResult(recipe.recipeId, recipe.outputItemId);
        } else {
            sim = recipeSimulator.simulateRecipe(recipe, ctx, baseState);
        }
        CostEvaluationResult cost = costEvaluator.evaluate(recipe, sim, ctx.tp, ctx.settings);

        ResolvedNeed firstCraft = sim.getFirstCraft();
        Node tree = null;

        Map<Integer, Integer> missingToBuyOne = new HashMap<>();
        if (firstCraft != null) {
            collectBoughtItems(firstCraft, missingToBuyOne);
        }

        int totalBuyCost = computeBuyCostFromMissing(sim.getTotalMissingToBuy(), ctx.tp, ctx.settings);
        int buyCostOne = computeBuyCostFromMissing(missingToBuyOne, ctx.tp, ctx.settings);

        int revenueOne = cost.getRevenuePerCraft();
        int matsSellOne = cost.getOpportunityCostPerCraft();
        int profitOne = revenueOne - buyCostOne - matsSellOne;
        int totalProfit = profitOne * sim.getCraftCount();

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

    private int computeBuyCostFromMissing(Map<Integer, Integer> missing,
                                          Map<Integer, TpPriceRepository.TpQuote> tp,
                                          CraftingSettings settings) {
        if (missing == null || missing.isEmpty()) return 0;

        int sum = 0;

        for (var e : missing.entrySet()) {
            int itemId = e.getKey();
            int qty = e.getValue();
            if (qty <= 0) continue;

            TpPriceRepository.TpQuote q = tp.get(itemId);
            if (q == null) continue;

            Integer unit = settings.listingBuy ? q.buyUnit : q.sellUnit;
            if (unit == null) continue;

            sum += unit * qty;
        }

        return sum;
    }

    private boolean shouldSimulateRecipe(
            RecipeRepository.Recipe recipe,
            PlannerContext ctx
                                        ) {


        CraftingResolver resolver = new CraftingResolver();
        int sellUnit = resolver.resolveDirectSellUnit(recipe.outputItemId, ctx.tp, ctx.settings);
        if (sellUnit <= 0) return false;

        int revenue = sellUnit * recipe.outputCount;

        int minCost = 0;

        for (RecipeRepository.Ingredient ing : recipe.ingredients) {

            int buyUnit = resolver.resolveDirectBuyUnit(ing.itemId, ctx.tp, ctx.settings);
            int sellUnitMat = resolver.resolveDirectSellUnit(ing.itemId, ctx.tp, ctx.settings);

            int cheapest = Math.min(
                    buyUnit > 0 ? buyUnit : Integer.MAX_VALUE,
                    sellUnitMat > 0 ? sellUnitMat : Integer.MAX_VALUE
                                   );

            if (cheapest == Integer.MAX_VALUE) {
                return true; // unknown cost → simulate
            }

            minCost += cheapest * ing.count;
        }

        return minCost < revenue;
    }
}