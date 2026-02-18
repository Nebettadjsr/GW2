package craft;

import java.util.Map;

public class CraftResult {
    public final int                   outputItemId;
    public final String                discipline;
    public final int                   craftableCount;
    public final Map<Integer, Integer> missingToBuy;

    public final int buyCostCopper;          // TOTAL for craftableCount
    public final int matsSellValueCopper;    // PER 1 craft
    public final int revenueCopper;          // PER 1 craft
    public final int profitCopper;           // PER 1 craft
    public final int totalProfitCopper;      // TOTAL

    public final Node tree;

    public CraftResult(int outputItemId, String discipline, int craftableCount,
                       Map<Integer, Integer> missingToBuy,
                       int buyCostCopper,
                       int matsSellValueCopper,
                       int revenueCopper,
                       int profitCopper,
                       int totalProfitCopper,
                       Node tree) {
        this.outputItemId = outputItemId;
        this.discipline = discipline;
        this.craftableCount = craftableCount;
        this.missingToBuy = missingToBuy;
        this.buyCostCopper = buyCostCopper;
        this.matsSellValueCopper = matsSellValueCopper;
        this.revenueCopper = revenueCopper;
        this.profitCopper = profitCopper;
        this.totalProfitCopper = totalProfitCopper;
        this.tree = tree;
    }
}
