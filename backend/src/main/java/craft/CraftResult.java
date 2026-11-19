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
    public final int totalMatsSellValueCopper; // TOTAL opportunity value of consumed owned materials
    public final Map<Integer, MaterialPurchaseCost> materialPurchaseCosts;
    /** Selected unit and total purchase values for the one-attempt missing-material list. */
    public final Map<Integer, MaterialPurchaseCost> materialPurchaseCostsOne;
    public final int revenueCopper;          // PER 1 craft, GROSS (DOMAIN_SPEC.md §24)

    /**
     * PER 1 craft, after DOMAIN_SPEC.md §25's 15% Trading Post fee on {@link #revenueCopper}
     * (§26): the gross revenue above is <em>not</em> the fee-adjusted value this was built from.
     */
    public final int profitCopper;

    /** TOTAL, on the same basis as {@link #profitCopper} (DOMAIN_SPEC.md §27). */
    public final int totalProfitCopper;

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

    /**
     * True when an owned sellable input consumed by an accepted craft had no Trading Post value, no
     * crafting cost and no vendor value (DOMAIN_SPEC.md 11.2). Its cost was taken as 0 only because
     * it could not be modelled, so the profit figures are not a confident result.
     */
    public boolean unvaluedOwnedInput;
    /**
     * True while some consumed currency still has no gold valuation (wallet-owned units, or a craft the wallet
     * blocked): the gold margin is then conditional. False when no currency is consumed or every unit was
     * produced by priced crafting batches already charged in the costs.
     */
    public boolean unpricedCurrency;
    /**
     * The currency ids (never item ids) behind {@link #unpricedCurrency}; consumed currencies not listed here were
     * valued (crafted batches or the approved Research Note estimate). Empty when nothing is unpriced.
     */
    public java.util.Set<Integer> unpricedCurrencyIds = java.util.Set.of();

    /**
     * Currency (by currency id, never item id) one craft consumes including nested crafts; empty for
     * item-only results. Actual wallet demand, kept apart from any gold valuation (none is applied).
     */
    public Map<Integer, Integer> currencyPerCraft = Map.of();
    /** Currency consumed by the {@link #craftableCount} accepted crafts. */
    public Map<Integer, Integer> currencyTotal = Map.of();
    /** KNOWN when a wallet balance was available, UNKNOWN when a currency recipe had none, NOT_REQUIRED otherwise. */
    public String walletState = "NOT_REQUIRED";
    /**
     * Research Notes paid from the existing wallet and valued by the approved estimate (per first craft / all
     * accepted crafts), with the whole-copper amount already deducted from {@link #profitCopper} and
     * {@link #totalProfitCopper}. Zero when no estimate applied. Separate from own-item and bought-material totals.
     */
    public int estimatedNotesPerCraft;
    public int estimatedNotesTotal;
    public int estimatedNotesCostPerCraftCopper;
    public int estimatedNotesCostTotalCopper;
    /** The shared immutable estimate that was applied (selected-detail data only); null when none applied. */
    public ResearchNoteEstimate noteEstimate;
    /**
     * True when the per-craft economics describe a hypothetical craft because the wallet blocked every real
     * craft (craftableCount stays 0): output quote and material costs remain valid and visible.
     */
    public boolean economicsFromHypotheticalCraft;
    /**
     * Verified variable output of the root recipe (exact mean plus guaranteed min/max), or null for a fixed
     * output. When set, the revenue/profit figures are expected (estimated) economics, not guaranteed yield.
     */
    public ExpectedYield expectedYield;
    /** True when this root or any nested step is a variable-output Forge route: profit/feasibility is estimated. */
    public boolean estimated;
    /** Expected sellable output units of the accepted crafts after the reused self-seed units are removed. */
    public java.math.BigDecimal expectedSellableUnitsTotal;
    /** Guaranteed sellable output units of the accepted crafts (minimum yield less reused seed units). */
    public int guaranteedSellableUnitsTotal;
    /** Self-seed units supplied by an earlier batch's guaranteed minimum instead of being bought again. */
    public int reusedSeedUnits;

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
                totalProfitCopper, revenueCopper * craftableCount, tree, blockedReason,
                matsSellValueCopper * craftableCount, Map.of());
    }

    public CraftResult(int outputItemId, String discipline, int craftableCount,
                       Map<Integer, Integer> missingToBuy,
                       Map<Integer, Integer> missingToBuyOne,
                       int buyCostCopper, int matsSellValueCopper, int revenueCopper,
                       int profitCopper, int totalProfitCopper, int totalSellValueCopper,
                       Node tree, BlockedReason blockedReason) {
        this(outputItemId, discipline, craftableCount, missingToBuy, missingToBuyOne,
                buyCostCopper, matsSellValueCopper, revenueCopper, profitCopper, totalProfitCopper,
                totalSellValueCopper, tree, blockedReason, matsSellValueCopper * craftableCount, Map.of());
    }

    public CraftResult(int outputItemId, String discipline, int craftableCount,
                       Map<Integer, Integer> missingToBuy, Map<Integer, Integer> missingToBuyOne,
                       int buyCostCopper, int matsSellValueCopper, int revenueCopper, int profitCopper,
                       int totalProfitCopper, int totalSellValueCopper, Node tree, BlockedReason blockedReason,
                       int totalMatsSellValueCopper,
                       Map<Integer, MaterialPurchaseCost> materialPurchaseCosts) {
        this(outputItemId, discipline, craftableCount, missingToBuy, missingToBuyOne, buyCostCopper,
                matsSellValueCopper, revenueCopper, profitCopper, totalProfitCopper, totalSellValueCopper,
                tree, blockedReason, totalMatsSellValueCopper, materialPurchaseCosts, Map.of());
    }

    public CraftResult(int outputItemId, String discipline, int craftableCount,
                       Map<Integer, Integer> missingToBuy, Map<Integer, Integer> missingToBuyOne,
                       int buyCostCopper, int matsSellValueCopper, int revenueCopper, int profitCopper,
                       int totalProfitCopper, int totalSellValueCopper, Node tree, BlockedReason blockedReason,
                       int totalMatsSellValueCopper,
                       Map<Integer, MaterialPurchaseCost> materialPurchaseCosts,
                       Map<Integer, MaterialPurchaseCost> materialPurchaseCostsOne) {
        this.blockedReason = blockedReason;
        this.outputItemId = outputItemId;
        this.discipline = discipline;
        this.craftableCount = craftableCount;
        this.missingToBuy = missingToBuy;
        this.missingToBuyOne = missingToBuyOne;
        this.buyCostCopper = buyCostCopper;
        this.matsSellValueCopper = matsSellValueCopper;
        this.totalMatsSellValueCopper = totalMatsSellValueCopper;
        this.materialPurchaseCosts = Map.copyOf(materialPurchaseCosts);
        this.materialPurchaseCostsOne = Map.copyOf(materialPurchaseCostsOne);
        this.revenueCopper = revenueCopper;
        this.profitCopper = profitCopper;
        this.totalProfitCopper = totalProfitCopper;
        this.totalSellValueCopper = totalSellValueCopper;
        this.tree = tree;
    }
}
