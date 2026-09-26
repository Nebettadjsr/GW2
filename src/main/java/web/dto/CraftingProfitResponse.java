package web.dto;

import java.util.List;

/**
 * Transport response body for {@code POST /api/crafting/profit} (STORY-API-001,
 * TARGET_ARCHITECTURE.md §9).
 *
 * <p>Carries the effective scope/settings actually used (so a caller that relied on defaults can
 * see what was applied) and one row per recipe visible in that scope. Row content, including what
 * is deliberately left out of it, is documented on {@link CraftingRowDto}, which this route shares
 * with Crafting Discovery (STORY-API-002).
 */
public record CraftingProfitResponse(EffectiveScopeDto scope,
                                     EffectiveSettingsDto settings,
                                     int rowCount,
                                     List<CraftingRowDto> rows) {

    /** The scope the calculation ran with, after defaults were applied. */
    public record EffectiveScopeDto(String kind, String discipline, String characterName, int rating) {
    }

    /**
     * The settings the calculation ran with, after defaults were applied. {@code
     * allowNonTradeableMaterials} is echoed like every other setting, so a caller can see which
     * non-Trading-Post material rule produced these rows and can send the same one back with a
     * resolution request (DOMAIN_SPEC.md §2.1.1).
     */
    public record EffectiveSettingsDto(boolean useOwnMats,
                                       boolean allowBuying,
                                       int maxBuyCopper,
                                       boolean listingSell,
                                       boolean listingBuy,
                                       boolean dailyBuyInsteadOfCraft,
                                       boolean allowNonTradeableMaterials) {
    }
}
