package web.dto;

import java.util.List;

/**
 * Transport response body for {@code POST /api/crafting/discovery} (STORY-API-002,
 * TARGET_ARCHITECTURE.md §9).
 *
 * <p>Carries the effective scope/settings actually used (so a caller that relied on defaults can
 * see what was applied) and one row per still-missing DISCOVERABLE recipe in that scope. Row
 * content, including what is deliberately left out of it, is documented on {@link CraftingRowDto},
 * which this route shares with Crafting Profit (STORY-API-001).
 *
 * <p>A scope whose character+discipline has nothing left to discover is an empty {@code rows} list
 * with {@code rowCount} 0 and HTTP 200 - the transport form of the application service's own
 * empty-result short-circuit (CURRENT_ARCHITECTURE.md §5.2), not a missing resource.
 *
 * @param inventoryCharacterName the character whose owned inventory was used, echoed back; null
 *                               when none was supplied and the unfiltered pool was used
 */
public record CraftingDiscoveryResponse(EffectiveScopeDto scope,
                                        String inventoryCharacterName,
                                        EffectiveSettingsDto settings,
                                        int rowCount,
                                        List<CraftingRowDto> rows) {

    /** The discipline+character scope the calculation ran with. */
    public record EffectiveScopeDto(String discipline, String characterName, int rating) {
    }

    /**
     * The settings the calculation ran with, after defaults were applied.
     *
     * <p>{@code dailyBuyInsteadOfCraft} is reported for completeness even though it is not a
     * request field on this route: Discovery fixes it to false, and the caller can see that.
     */
    public record EffectiveSettingsDto(boolean useOwnMats,
                                       boolean allowBuying,
                                       int maxBuyCopper,
                                       boolean listingSell,
                                       boolean listingBuy,
                                       boolean dailyBuyInsteadOfCraft) {
    }
}
