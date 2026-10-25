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

    private int computeRevenue(
            Recipe recipe,
            Map<Integer, PriceQuote> tp,
            CraftingSettings settings
                              ) {

        PriceQuote q = tp.get(recipe.outputItemId);

        if (q == null) {
            return 0;
        }

        Integer unit = settings.listingSell ? q.sellUnit : q.buyUnit;

        if (unit == null) {
            return 0;
        }

        return unit * recipe.outputCount;
    }
}