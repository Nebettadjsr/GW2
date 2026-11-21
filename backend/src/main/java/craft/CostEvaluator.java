package craft;

import java.util.Map;

public class CostEvaluator {

    public CostEvaluationResult evaluate(
            Recipe recipe,
            RecipeSimulationResult sim,
            Map<Integer, PriceQuote> tp,
            CraftingSettings settings
                                        ) {

        int revenuePerCraft = computeRevenue(recipe, tp, settings);

        int oppCostPerCraft = 0;

        if (sim.getFirstCraft() != null) {
            oppCostPerCraft = sim.getFirstCraft().getOpportunityCostCopper();
        }

        return new CostEvaluationResult(
                revenuePerCraft,
                oppCostPerCraft
        );
    }

    static int computeRevenue(
            Recipe recipe,
            Map<Integer, PriceQuote> tp,
            CraftingSettings settings
                              ) {

        Integer unit = outputUnit(recipe, tp, settings);

        if (unit == null) {
            return 0;
        }

        if (recipe.hasExpectedYield()) {
            // Expected economics: exact decimal mean, floored once at the copper boundary (no truncated mean).
            return Math.toIntExact(ExpectedYield.floorCopper(recipe.expectedYield.mean(), unit));
        }

        return Math.multiplyExact(unit, recipe.outputCount);
    }

    /** Applicable output unit quote, or null when there is no usable quote (never a fabricated value). */
    static Integer outputUnit(Recipe recipe, Map<Integer, PriceQuote> tp, CraftingSettings settings) {
        PriceQuote q = tp.get(recipe.outputItemId);
        if (q == null) {
            return null;
        }
        return settings.listingSell ? q.sellUnit : q.buyUnit;
    }
}