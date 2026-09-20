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

        Map<Integer, List<RecipeRepository.Recipe>> recipesByOutput = buildRecipesByOutput(recipes);
        PlannerContext ctx = new PlannerContext(recipesByOutput, tp, settings, allowedRecipeIds);

        Map<Integer, CraftResult> out = new HashMap<>();
        for (RecipeRepository.Recipe r : recipes) {
            PlanState baseState = new PlanState(sellableInventory, boundInventory);
            out.put(r.recipeId, evaluateOneRecipeNew(r, baseState, ctx));
        }

        return out;
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
            List<RecipeRepository.Recipe> recipes,
            Map<Integer, Integer> sellableInventory,
            Map<Integer, Integer> accountBoundInventory,
            Map<String, Map<Integer, Integer>> characterBoundInventory,
            List<CharacterCraftingProfile> roster,
            Map<Integer, TpPriceRepository.TpQuote> tp,
            CraftingSettings settings,
            Set<Integer> allowedRecipeIds) {

        Map<Integer, List<RecipeRepository.Recipe>> recipesByOutput = buildRecipesByOutput(recipes);
        PlannerContext ctx = new PlannerContext(recipesByOutput, tp, settings, allowedRecipeIds, roster);

        Map<Integer, CraftResult> out = new HashMap<>();
        for (RecipeRepository.Recipe r : recipes) {
            PlanState baseState = new PlanState(sellableInventory, accountBoundInventory, characterBoundInventory);
            out.put(r.recipeId, evaluateOneRecipeNew(r, baseState, ctx));
        }

        return out;
    }

    private Map<Integer, List<RecipeRepository.Recipe>> buildRecipesByOutput(List<RecipeRepository.Recipe> recipes) {
        Map<Integer, List<RecipeRepository.Recipe>> recipesByOutput = new HashMap<>();
        for (RecipeRepository.Recipe r : recipes) {
            recipesByOutput.computeIfAbsent(r.outputItemId, k -> new ArrayList<>()).add(r);
        }
        return recipesByOutput;
    }

    private CraftResult evaluateOneRecipeNew(
            RecipeRepository.Recipe recipe,
            PlanState baseState,
            PlannerContext ctx
    ) {
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
                tree,
                sim.getBlockedReason()
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