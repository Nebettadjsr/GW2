package craft;

import java.util.List;

/**
 * One explained requirement in a single-craft resolution trace: what was needed, where its quantity
 * actually came from, what that cost, and - for a crafted requirement - which recipe the resolver
 * selected, who was assigned to perform it and what its own ingredients required.
 *
 * <p>Every field records a decision the authoritative resolver made
 * ({@link CraftingResolver}/{@link RecipeSimulator}); nothing here is re-derived from prices,
 * recipes or display text. Item names, transport shapes and presentation belong to layers above
 * the domain.
 *
 * <p>Quantity semantics (DOMAIN_SPEC.md sections 12, 17 and 48):
 * {@code requestedQuantity == inventoryQuantity + craftedQuantity + boughtQuantity +
 * missingQuantity}. {@code craftedQuantity} is the produced quantity <em>used here</em>, which is
 * less than {@code producedQuantity} when a recipe's batch size overshoots the requirement.
 *
 * <p>Cost semantics (DOMAIN_SPEC.md sections 11, 21 and 23, ARCHITECTURE.md section 7):
 * the three cost fields are copper amounts for this requirement <em>including</em> its descendants,
 * so an ancestor's cost must never be summed with its children's again. They are {@code null} when
 * the requirement was not fully satisfied and therefore has no complete value - which is different
 * from a known zero.
 *
 * @param itemId               the required item.
 * @param requestedQuantity    units this occurrence required.
 * @param inventoryQuantity    units taken from owned inventory.
 * @param craftedQuantity      crafted units used for this requirement.
 * @param boughtQuantity       units purchased on the Trading Post.
 * @param missingQuantity      units that could not be obtained at all.
 * @param recipeId             recipe the resolver selected or attempted here, or null if none.
 * @param craftCount           completed executions of that recipe; 0 when no craft completed.
 * @param producedQuantity     output units those executions produced; may exceed the units used.
 * @param characterName        character assigned to perform the craft, or null.
 * @param methods              acquisition methods that actually contributed, in a fixed order.
 * @param states               domain states that apply, in a fixed order; may be empty.
 * @param blockedReasons       explicit reasons this requirement is blocked; empty when it is not.
 * @param cashCostCopper       inclusive purchase cost, or null when no complete value exists.
 * @param opportunityCostCopper inclusive opportunity cost of consumed owned materials, or null.
 * @param effectiveCostCopper  inclusive cash plus opportunity cost, or null.
 * @param children             ingredient requirements of the selected (or attempted) craft, in
 *                             recipe order; empty for a requirement with no crafting path.
 */
public record CraftTraceNode(
        int itemId,
        int requestedQuantity,
        int inventoryQuantity,
        int craftedQuantity,
        int boughtQuantity,
        int missingQuantity,
        Integer recipeId,
        int craftCount,
        int producedQuantity,
        String characterName,
        List<AcquisitionMethod> methods,
        List<ResolutionState> states,
        List<BlockedReason> blockedReasons,
        Integer cashCostCopper,
        Integer opportunityCostCopper,
        Integer effectiveCostCopper,
        List<CraftTraceNode> children,
        List<CurrencyQuantity> currencies,
        SourceFacts source
) {

    /**
     * Accepted non-ordinary source facts of one requirement, recorded by the resolver (never re-derived from a
     * catalog at the transport boundary). Units: {@code grossProduced} is everything the {@code batches}
     * guaranteed-minimum executions physically produced; {@code reusedSeed} are units a later batch of the same
     * plan consumed as its own seed (never sold or stock for anyone else); {@code guaranteedNet} is what remains
     * and is the only quantity that may become inventory; {@code used} is what this requirement consumed of it and
     * {@code surplus} what it retained. {@code expectedPerBatch}/{@code min}/{@code max} are present only for a
     * verified variable output, and {@code expectedNet} (mean x batches - reusedSeed) only then.
     */
    public record SourceFacts(String kind, java.math.BigDecimal expectedPerBatch, Integer min, Integer max,
                              int batches, int grossProduced, int reusedSeed, int guaranteedNet,
                              java.math.BigDecimal expectedNet, int used, int surplus) {
        public static final String MYSTIC_FORGE = "MYSTIC_FORGE";
        public static final String VENDOR = "VENDOR";
    }

    /** Node without source facts. */
    public CraftTraceNode(int itemId, int requestedQuantity, int inventoryQuantity, int craftedQuantity,
                          int boughtQuantity, int missingQuantity, Integer recipeId, int craftCount,
                          int producedQuantity, String characterName, List<AcquisitionMethod> methods,
                          List<ResolutionState> states, List<BlockedReason> blockedReasons,
                          Integer cashCostCopper, Integer opportunityCostCopper, Integer effectiveCostCopper,
                          List<CraftTraceNode> children, List<CurrencyQuantity> currencies) {
        this(itemId, requestedQuantity, inventoryQuantity, craftedQuantity, boughtQuantity, missingQuantity,
                recipeId, craftCount, producedQuantity, characterName, methods, states, blockedReasons,
                cashCostCopper, opportunityCostCopper, effectiveCostCopper, children, currencies, null);
    }

    /**
     * Exact units of one currency (never an item id) this requirement's craft path consumes or would consume, and
     * where they come from: the captured wallet balance, surplus an earlier production batch of the same plan left,
     * newly produced units (their batches are child requirements, charged there), or a missing remainder.
     * {@code walletBalance} is the captured balance before the plan spent anything, null when unknown.
     */
    public record CurrencyQuantity(int currencyId, int quantity, int fromWallet, int fromProduced, int crafted,
                                   int missing, Integer walletBalance) {
        /** Quantity only, with no recorded sourcing. */
        public CurrencyQuantity(int currencyId, int quantity) {
            this(currencyId, quantity, 0, 0, 0, 0, null);
        }
    }

    /** Item-only node: no currency requirement. */
    public CraftTraceNode(int itemId, int requestedQuantity, int inventoryQuantity, int craftedQuantity,
                          int boughtQuantity, int missingQuantity, Integer recipeId, int craftCount,
                          int producedQuantity, String characterName, List<AcquisitionMethod> methods,
                          List<ResolutionState> states, List<BlockedReason> blockedReasons,
                          Integer cashCostCopper, Integer opportunityCostCopper, Integer effectiveCostCopper,
                          List<CraftTraceNode> children) {
        this(itemId, requestedQuantity, inventoryQuantity, craftedQuantity, boughtQuantity, missingQuantity,
                recipeId, craftCount, producedQuantity, characterName, methods, states, blockedReasons,
                cashCostCopper, opportunityCostCopper, effectiveCostCopper, children, List.of());
    }

    public CraftTraceNode {
        methods = List.copyOf(methods);
        states = List.copyOf(states);
        blockedReasons = List.copyOf(blockedReasons);
        children = List.copyOf(children);
        currencies = List.copyOf(currencies);
    }

    /** Output units produced beyond what this requirement used (DOMAIN_SPEC.md section 17). */
    public int surplusQuantity() {
        return Math.max(0, producedQuantity - craftedQuantity);
    }
}
