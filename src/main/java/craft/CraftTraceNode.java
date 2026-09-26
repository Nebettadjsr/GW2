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
 * <p>Cost semantics (DOMAIN_SPEC.md sections 11, 21 and 23, TARGET_ARCHITECTURE.md section 13.3):
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
        List<CraftTraceNode> children
) {

    public CraftTraceNode {
        methods = List.copyOf(methods);
        states = List.copyOf(states);
        blockedReasons = List.copyOf(blockedReasons);
        children = List.copyOf(children);
    }

    /** Output units produced beyond what this requirement used (DOMAIN_SPEC.md section 17). */
    public int surplusQuantity() {
        return Math.max(0, producedQuantity - craftedQuantity);
    }
}
