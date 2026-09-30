package web.dto;

/**
 * A material the calculation still needs. Includes the raw Trading Post quote for consumers that
 * need it, plus the selected acquisition price and total that the calculation actually used.
 *
 * <p>Shared by every crafting calculation route (STORY-API-001, STORY-API-002).
 *
 * @param itemName null when the item is not in the loaded item set, so "unknown" stays
 *                 distinguishable from a blank name
 * @param iconUrl  this application's image URL for the material (TARGET_ARCHITECTURE.md §12.1), or
 *                 null when the item's retained metadata is absent or not an accepted source. It is
 *                 display metadata only: a missing icon changes no quantity and no price
 */
public record MissingItemDto(int itemId,
                             String itemName,
                             int quantity,
                             TradingPostQuoteDto price,
                             String iconUrl,
                             Integer purchaseUnitPriceCopper,
                             Integer totalPurchaseCostCopper) {
}
