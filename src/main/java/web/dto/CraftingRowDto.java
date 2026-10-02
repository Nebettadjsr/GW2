package web.dto;

import java.util.List;

/**
 * One visible recipe and its coordinated calculation result, as reported by the crafting
 * calculation routes (STORY-API-001 Profit, STORY-API-002 Discovery).
 *
 * <p>Shared because both routes project the same pair of domain objects - a {@code craft.Recipe}
 * and the {@code craft.CraftResult} the planner produced for it - so a caller reads a row the same
 * way on either route. It carries the authoritative values the domain produced; nothing here is
 * recomputed or re-derived.
 *
 * <p>When the calculation produced no result for this recipe, {@code resultAvailable} is false and
 * every result-derived field is null; the recipe itself is still reported rather than omitted.
 *
 * <p>Presentation-level text produced by the JavaFX layer (status labels, "no TP" markers,
 * missing-material summaries, search blobs) is deliberately absent: it is a rendering rule, not
 * domain state. The per-row resolution tree (TARGET_ARCHITECTURE.md §13) is likewise not part of
 * this contract - the application services build it lazily per selected row, so exposing it belongs
 * to a separate detail route.
 *
 * <p>This type exists only at the HTTP boundary: it is not a domain, persistence or GW2 API model.
 *
 * @param craftableCount      completed crafts the domain determined were possible
 * @param buyCostCopper       total buy cost for {@code craftableCount} crafts
 * @param matsSellValueCopper material sell value per single craft
 * @param totalMatsSellValueCopper authoritative total opportunity value of owned materials consumed
 * @param revenueCopper       gross revenue per single craft, with no selling fee deducted
 *                            (DOMAIN_SPEC.md 24/25)
 * @param profitCopper        profit per single craft, after DOMAIN_SPEC.md 25's 15% Trading Post fee
 *                            on that gross revenue. Copied from {@code craft.CraftResult}; no fee is
 *                            applied, re-applied or reversed at this boundary
 * @param totalSellValueCopper the domain's authoritative total sell value for {@code craftableCount}
 *                            crafts (DOMAIN_SPEC.md 2.1.1): the applicable Trading Post sell value of
 *                            everything those crafts produce, with the recipe's output quantity
 *                            already in it and no selling fee deducted (DOMAIN_SPEC.md 25). Copied
 *                            from {@code craft.CraftResult}; never recomputed here from revenue and
 *                            count, so a value that disagrees with that product is still reported as
 *                            the domain stated it
 * @param totalProfitCopper   the domain's authoritative total profit, on the same after-fee basis as
 *                            {@code profitCopper} (DOMAIN_SPEC.md 27), never recomputed here
 * @param blockedReason       {@code craft.BlockedReason} name; {@code NONE} when not blocked
 * @param outputPrice         the raw trading-post quote for the output item, null when unquoted
 * @param missingToBuy        still-missing materials for {@code craftableCount} crafts
 * @param missingToBuyOne     still-missing materials for one further craft
 * @param iconUrl             this application's image URL for the recipe's <em>output item</em>
 *                            (TARGET_ARCHITECTURE.md §12.1) - a recipe has no icon of its own - or
 *                            null when that item's retained metadata is absent or not an accepted
 *                            source. Display metadata only: it never affects craftability, a result's
 *                            availability or any economic value
 */
public record CraftingRowDto(int recipeId,
                             int outputItemId,
                             String outputName,
                             int outputCount,
                             String disciplines,
                             int minRating,
                             boolean resultAvailable,
                             Integer craftableCount,
                             Integer buyCostCopper,
                             Integer matsSellValueCopper,
                             Integer totalMatsSellValueCopper,
                             Integer revenueCopper,
                             Integer profitCopper,
                             Integer totalSellValueCopper,
                             Integer totalProfitCopper,
                             String blockedReason,
                             TradingPostQuoteDto outputPrice,
                             List<MissingItemDto> missingToBuy,
                             List<MissingItemDto> missingToBuyOne,
                             List<String> ingredientNames,
                             String iconUrl) {
}
