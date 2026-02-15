package craft;

import java.util.Map;

public class CraftResult {
    public final int outputItemId;
    public final String discipline;
    public final int craftableCount;
    public final Map<Integer, Integer> missingToBuy;
    public final int buyCostCopper;
    public final int revenueCopper;
    public final int profitCopper;
    public final int totalProfitCopper;

    public final Node tree; // NEW

    public CraftResult(int outputItemId, String discipline, int craftableCount,
                       Map<Integer, Integer> missingToBuy,
                       int buyCostCopper, int revenueCopper, int profitCopper, int totalProfitCopper,
                       Node tree) {
        this.outputItemId = outputItemId;
        this.discipline = discipline;
        this.craftableCount = craftableCount;
        this.missingToBuy = missingToBuy;
        this.buyCostCopper = buyCostCopper;
        this.revenueCopper = revenueCopper;
        this.profitCopper = profitCopper;
        this.totalProfitCopper = totalProfitCopper;
        this.tree = tree;
    }
}