package web.dto;

/**
 * Raw {@code craft.PriceQuote} pass-through; either side may be null when the item is unquoted.
 *
 * <p>Shared by every crafting calculation route (STORY-API-001, STORY-API-002): a caller renders
 * its own price-unavailable marker from these values, so the API never decides that display rule.
 *
 * <p>This type exists only at the HTTP boundary: it is not a domain, persistence or GW2 API model.
 */
public record TradingPostQuoteDto(Integer buyUnitCopper, Integer sellUnitCopper) {
}
