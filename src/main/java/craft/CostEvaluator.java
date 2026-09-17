package craft;

import repo.RecipeRepository;
import repo.tp.TpPriceRepository;

import java.util.Map;

public class CostEvaluator {

    public CostEvaluationResult evaluate(
            RecipeRepository.Recipe recipe,
            RecipeSimulationResult sim,
            Map<Integer, TpPriceRepository.TpQuote> tp,
            CraftingSettings settings
                                        ) {

        int revenuePerCraft = computeRevenue(recipe, tp, settings);

        int buyCostPerCraft = 0;
        int oppCostPerCraft = 0;

        if (sim.getFirstCraft() != null) {
            buyCostPerCraft = sim.getFirstCraft().getBuyCostCopper();
            oppCostPerCraft = sim.getFirstCraft().getOpportunityCostCopper();
        }

        int profitPerCraft = revenuePerCraft - buyCostPerCraft - oppCostPerCraft;

        int totalProfit = profitPerCraft * sim.getCraftCount();

        return new CostEvaluationResult(
                revenuePerCraft,
                buyCostPerCraft,
                oppCostPerCraft,
                profitPerCraft,
                totalProfit
        );
    }

    private int computeRevenue(
            RecipeRepository.Recipe recipe,
            Map<Integer, TpPriceRepository.TpQuote> tp,
            CraftingSettings settings
                              ) {

        TpPriceRepository.TpQuote q = tp.get(recipe.outputItemId);

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