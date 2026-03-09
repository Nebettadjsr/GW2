package craft;

import java.util.Map;

public class CraftResult {
    public final int                   outputItemId;
    public final String                discipline;
    public final int                   craftableCount;
    public final Map<Integer, Integer> missingToBuy;
    public final Map<Integer, Integer> missingToBuyOne;

    public final int buyCostCopper;          // TOTAL for craftableCount
    public final int matsSellValueCopper;    // PER 1 craft
    public final int revenueCopper;          // PER 1 craft
    public final int profitCopper;           // PER 1 craft
    public final int totalProfitCopper;      // TOTAL

    public final Node tree;
    public final boolean earlyPruned;

    public CraftResult(int outputItemId, String discipline, int craftableCount,
                       Map<Integer, Integer> missingToBuy,
                       Map<Integer, Integer> missingToBuyOne,
                       int buyCostCopper,
                       int matsSellValueCopper,
                       int revenueCopper,
                       int profitCopper,
                       int totalProfitCopper,
                       Node tree,
                       boolean earlyPruned) {
        this.outputItemId = outputItemId;
        this.discipline = discipline;
        this.craftableCount = craftableCount;
        this.missingToBuy = missingToBuy;
        this.missingToBuyOne = missingToBuyOne;
        this.buyCostCopper = buyCostCopper;
        this.matsSellValueCopper = matsSellValueCopper;
        this.revenueCopper = revenueCopper;
        this.profitCopper = profitCopper;
        this.totalProfitCopper = totalProfitCopper;
        this.tree = tree;
        this.earlyPruned = earlyPruned;
    }
}