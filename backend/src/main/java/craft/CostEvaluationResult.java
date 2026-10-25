package craft;

/**
 * The per-craft economic inputs {@link CostEvaluator} derives for one recipe evaluation: the gross
 * output revenue of DOMAIN_SPEC.md section 24/25 and the owned-material opportunity cost of the
 * first craft.
 *
 * <p>It deliberately publishes no profit. Profit is DOMAIN_SPEC.md section 26's fee-inclusive
 * figure, and {@link CraftingPlanner#evaluateOneRecipeNew} is its single authoritative site: it
 * charges the decided Trading Post fee and uses its own purchased-material basis
 * ({@code missingToBuyOne}), which is not the basis available here (STORY-DOM-023 F001 /
 * STORY-DOM-025). A second profit on this result could only disagree with that one.
 */
public class CostEvaluationResult {

    private final int revenuePerCraft;
    private final int opportunityCostPerCraft;

    public CostEvaluationResult(
            int revenuePerCraft,
            int opportunityCostPerCraft
                               ) {
        this.revenuePerCraft = revenuePerCraft;
        this.opportunityCostPerCraft = opportunityCostPerCraft;
    }

    /** Gross output sell value for one craft execution, before any Trading Post fee (section 24/25). */
    public int getRevenuePerCraft() {
        return revenuePerCraft;
    }

    /** Sale value forgone by consuming owned sellable material in the first craft (section 11.1). */
    public int getOpportunityCostPerCraft() {
        return opportunityCostPerCraft;
    }
}
