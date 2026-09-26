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

    /**
     * TOTAL applicable Trading Post sell value of everything {@code craftableCount} crafts produce
     * (DOMAIN_SPEC.md §2.1.1): {@link #revenueCopper} - §25's per-execution output revenue, which
     * already carries the recipe's output quantity and deducts no selling fee - times
     * {@link #craftableCount}, §28's craftable count. It is a carried value, not a derived one:
     * whoever reads it reads what the planner produced, exactly as {@link #totalProfitCopper} works.
     */
    public final int totalSellValueCopper;   // TOTAL

    /** Reason the next craft could not complete; completed crafts remain valid. */
    public final BlockedReason blockedReason;
    public final Node tree;

    public CraftResult(int outputItemId, String discipline, int craftableCount,
                       Map<Integer, Integer> missingToBuy,
                       Map<Integer, Integer> missingToBuyOne,
                       int buyCostCopper,
                       int matsSellValueCopper,
                       int revenueCopper,
                       int profitCopper,
                       int totalProfitCopper,
                       Node tree) {
        this(outputItemId, discipline, craftableCount, missingToBuy, missingToBuyOne,
                buyCostCopper, matsSellValueCopper, revenueCopper, profitCopper,
                totalProfitCopper, tree, BlockedReason.NONE);
    }

    /**
     * For callers that state no total sell value of their own; it takes the one
     * {@link #totalSellValueCopper} describes. Producers that hold the planner's own figure should
     * pass it to the constructor below instead of letting it be worked out again here.
     */
    public CraftResult(int outputItemId, String discipline, int craftableCount,
                       Map<Integer, Integer> missingToBuy,
                       Map<Integer, Integer> missingToBuyOne,
                       int buyCostCopper, int matsSellValueCopper, int revenueCopper,
                       int profitCopper, int totalProfitCopper, Node tree,
                       BlockedReason blockedReason) {
        this(outputItemId, discipline, craftableCount, missingToBuy, missingToBuyOne,
                buyCostCopper, matsSellValueCopper, revenueCopper, profitCopper,
                totalProfitCopper, revenueCopper * craftableCount, tree, blockedReason);
    }

    public CraftResult(int outputItemId, String discipline, int craftableCount,
                       Map<Integer, Integer> missingToBuy,
                       Map<Integer, Integer> missingToBuyOne,
                       int buyCostCopper, int matsSellValueCopper, int revenueCopper,
                       int profitCopper, int totalProfitCopper, int totalSellValueCopper,
                       Node tree, BlockedReason blockedReason) {
        this.blockedReason = blockedReason;
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
        this.totalSellValueCopper = totalSellValueCopper;
        this.tree = tree;
    }
}