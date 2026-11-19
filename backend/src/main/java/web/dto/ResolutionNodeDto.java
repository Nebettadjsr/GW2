package web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * One requirement in a resolution tree, as reported by both detail routes (STORY-API-008's recursive node shape).
 *
 * <p>A transport copy of the domain's {@code craft.CraftTraceNode}, with an optional Discovery-only
 * recipe-knowledge label derived from the explicit account-known and eligible-candidate sets. No
 * domain quantity or total cost is repaired, summed or rounded. The mapper supplies a separate
 * display-only rounded unit average; no state is inferred from a price quote and no node is dropped
 * or truncated. Enum-valued
 * domain facts are carried as their own names, so a state or reason this schema does not yet
 * enumerate stays visible instead of silently reading as success.
 *
 * <p>Identity is the node's own: {@link #recipeId()} is the recipe the resolver <em>actually</em>
 * selected or attempted for this requirement - null when no crafting path was selected - and it is
 * unrelated to the requested recipe on the response envelope, which may differ (AR-003). For
 * a requirement satisfied from owned stock there is no recipe, no craft and no child: none is
 * invented.
 *
 * <p>The three cost fields already include this requirement's descendants, so a consumer must not
 * add children's costs to a parent's again. They are null when no complete value could be
 * established, which is distinct from a domain-established zero (an unvalued non-tradable item
 * stays zero, with its own state).
 *
 * <p>This type exists only at the HTTP boundary: it is not a domain, persistence or GW2 API model.
 *
 * @param effectiveUnitCostCopper display-only average of {@code effectiveCostCopper} over
 *                           {@code requestedQuantity}, whole copper rounded HALF_UP; null when the
 *                           total is missing or the quantity is not positive. Not a quote
 * @param effectiveUnitCostApproximate true exactly when a known total does not divide evenly by a
 *                           positive requested quantity, so the unit value was rounded
 * @param itemName           null when this operation captured no usable name; display may fall back
 *                           to the ID
 * @param methods            the acquisition methods that actually contributed
 *                           ({@code craft.AcquisitionMethod} names); several for split sourcing,
 *                           empty when nothing succeeded
 * @param states             the domain states that apply ({@code craft.ResolutionState} names);
 *                           these may coexist with methods
 * @param blockedReasons     the explicit reasons this requirement is blocked
 *                           ({@code craft.BlockedReason} names); empty when it is not
 * @param currencies         exact currency units this node's own craft path consumes (or would, when blocked);
 *                           empty for item-only requirements. Currency ids are not item ids
 * @param children           ingredient requirements of the selected or attempted craft, in the
 *                           order the resolver produced them; repeated items in different branches
 *                           remain separate occurrences
 * @param iconUrl            this application's image URL for <em>this node's own item</em>
 *                           (application icon route), never the requested recipe's output and
 *                           never derived from how the requirement was sourced; null when the
 *                           operation captured no accepted source for that item. Display metadata
 *                           only: it changes no quantity, cost or state
 * @param recipeKnowledge    Discovery-only {@code KNOWN}/{@code TO_DISCOVER}, null for ordinary
 *                           ingredients and all Crafting Profit tree nodes
 */
public record ResolutionNodeDto(int itemId,
                                String itemName,
                                int requestedQuantity,
                                int inventoryQuantity,
                                int craftedQuantity,
                                int boughtQuantity,
                                int missingQuantity,
                                Integer recipeId,
                                int craftCount,
                                int producedQuantity,
                                String characterName,
                                List<String> methods,
                                List<String> states,
                                List<String> blockedReasons,
                                Integer cashCostCopper,
                                Integer opportunityCostCopper,
                                Integer effectiveCostCopper,
                                Integer effectiveUnitCostCopper,
                                boolean effectiveUnitCostApproximate,
                                List<ResolutionNodeDto> children,
                                String iconUrl,
                                @JsonInclude(JsonInclude.Include.NON_NULL) String recipeKnowledge,
                                List<CurrencyQuantityDto> currencies,
                                @JsonInclude(JsonInclude.Include.NON_NULL) ForgeStepDto forgeStep) {

    /** Node whose craft step is not a Mystic Forge route. */
    public ResolutionNodeDto(int itemId, String itemName, int requestedQuantity, int inventoryQuantity,
                             int craftedQuantity, int boughtQuantity, int missingQuantity, Integer recipeId,
                             int craftCount, int producedQuantity, String characterName, List<String> methods,
                             List<String> states, List<String> blockedReasons, Integer cashCostCopper,
                             Integer opportunityCostCopper, Integer effectiveCostCopper,
                             Integer effectiveUnitCostCopper, boolean effectiveUnitCostApproximate,
                             List<ResolutionNodeDto> children, String iconUrl, String recipeKnowledge,
                             List<CurrencyQuantityDto> currencies) {
        this(itemId, itemName, requestedQuantity, inventoryQuantity, craftedQuantity, boughtQuantity,
                missingQuantity, recipeId, craftCount, producedQuantity, characterName, methods, states,
                blockedReasons, cashCostCopper, opportunityCostCopper, effectiveCostCopper,
                effectiveUnitCostCopper, effectiveUnitCostApproximate, children, iconUrl, recipeKnowledge,
                currencies, null);
    }

    /**
     * Backend-computed Mystic Forge metadata for a node whose selected recipe is a Forge recipe. The node's
     * {@code producedQuantity}/{@code craftedQuantity} stay the GUARANTEED (minimum) planning quantities the
     * resolver counted; {@code expectedProducedTotal} is mean x counted crafts (exact decimal string, null for a
     * fixed-output recipe) and is an estimate, never stock. {@code guaranteedProducedTotal} equals the node's
     * {@code producedQuantity}.
     */
    public record ForgeStepDto(String source, String expectedOutputPerCraft, Integer minOutput, Integer maxOutput,
                               String expectedProducedTotal, int guaranteedProducedTotal) { }

    /** Item-only node (no currency requirement). */
    public ResolutionNodeDto(int itemId, String itemName, int requestedQuantity, int inventoryQuantity,
                             int craftedQuantity, int boughtQuantity, int missingQuantity, Integer recipeId,
                             int craftCount, int producedQuantity, String characterName, List<String> methods,
                             List<String> states, List<String> blockedReasons, Integer cashCostCopper,
                             Integer opportunityCostCopper, Integer effectiveCostCopper,
                             Integer effectiveUnitCostCopper, boolean effectiveUnitCostApproximate,
                             List<ResolutionNodeDto> children, String iconUrl, String recipeKnowledge) {
        this(itemId, itemName, requestedQuantity, inventoryQuantity, craftedQuantity, boughtQuantity,
                missingQuantity, recipeId, craftCount, producedQuantity, characterName, methods, states,
                blockedReasons, cashCostCopper, opportunityCostCopper, effectiveCostCopper,
                effectiveUnitCostCopper, effectiveUnitCostApproximate, children, iconUrl, recipeKnowledge,
                List.of());
    }

    /** Exact units of one currency (its own namespace, never an item id) for this node's craft path. */
    public record CurrencyQuantityDto(int currencyId, String name, int quantity, int fromWallet, int fromProduced,
                                      int crafted, int missing, Integer walletBalance, String iconUrl) {
        /** Quantity only, with no recorded sourcing. */
        public CurrencyQuantityDto(int currencyId, String name, int quantity) {
            this(currencyId, name, quantity, 0, 0, 0, 0, null, null);
        }
    }
}
