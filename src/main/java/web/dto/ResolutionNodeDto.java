package web.dto;

import java.util.List;

/**
 * One requirement in a resolution tree, as reported by both detail routes (STORY-API-008,
 * TARGET_ARCHITECTURE.md §13.3's recursive node shape).
 *
 * <p>A transport copy of the domain's {@code craft.CraftTraceNode} and nothing more. Every value is
 * the one the authoritative resolver produced: no quantity or cost is repaired, summed or rounded
 * here, no state is inferred from a price quote, and no node is dropped or truncated. Enum-valued
 * domain facts are carried as their own names, so a state or reason this schema does not yet
 * enumerate stays visible instead of silently reading as success.
 *
 * <p>Identity is the node's own: {@link #recipeId()} is the recipe the resolver <em>actually</em>
 * selected or attempted for this requirement - null when no crafting path was selected - and it is
 * unrelated to the requested recipe on the response envelope, which may differ (§13.3, AR-003). For
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
 * @param itemName           null when this operation captured no usable name; display may fall back
 *                           to the ID
 * @param methods            the acquisition methods that actually contributed
 *                           ({@code craft.AcquisitionMethod} names); several for split sourcing,
 *                           empty when nothing succeeded
 * @param states             the domain states that apply ({@code craft.ResolutionState} names);
 *                           these may coexist with methods
 * @param blockedReasons     the explicit reasons this requirement is blocked
 *                           ({@code craft.BlockedReason} names); empty when it is not
 * @param children           ingredient requirements of the selected or attempted craft, in the
 *                           order the resolver produced them; repeated items in different branches
 *                           remain separate occurrences
 * @param iconUrl            this application's image URL for <em>this node's own item</em>
 *                           (TARGET_ARCHITECTURE.md §12.1), never the requested recipe's output and
 *                           never derived from how the requirement was sourced; null when the
 *                           operation captured no accepted source for that item. Display metadata
 *                           only: it changes no quantity, cost or state
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
                                List<ResolutionNodeDto> children,
                                String iconUrl) {
}
