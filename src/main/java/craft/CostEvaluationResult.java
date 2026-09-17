package craft;

public class CostEvaluationResult {

    private final int revenuePerCraft;
    private final int buyCostPerCraft;
    private final int opportunityCostPerCraft;
    private final int effectiveCostPerCraft;
    private final int profitPerCraft;
    private final int totalProfit;

    public CostEvaluationResult(
            int revenuePerCraft,
            int buyCostPerCraft,
            int opportunityCostPerCraft,
            int profitPerCraft,
            int totalProfit
                               ) {
        this.revenuePerCraft = revenuePerCraft;
        this.buyCostPerCraft = buyCostPerCraft;
        this.opportunityCostPerCraft = opportunityCostPerCraft;
        this.effectiveCostPerCraft = buyCostPerCraft + opportunityCostPerCraft;
        this.profitPerCraft = profitPerCraft;
        this.totalProfit = totalProfit;
    }

    public int getRevenuePerCraft() {
        return revenuePerCraft;
    }

    public int getBuyCostPerCraft() {
        return buyCostPerCraft;
    }

    public int getOpportunityCostPerCraft() {
        return opportunityCostPerCraft;
    }

    public int getEffectiveCostPerCraft() {
        return effectiveCostPerCraft;
    }

    public int getProfitPerCraft() {
        return profitPerCraft;
    }

    public int getTotalProfit() {
        return totalProfit;
    }
}