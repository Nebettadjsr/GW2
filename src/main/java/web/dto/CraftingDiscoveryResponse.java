package web.dto;

import java.util.List;

/** Response for one-craft Discovery candidates; the scope identifies both character eligibility and inventory. */
public record CraftingDiscoveryResponse(EffectiveScopeDto scope,
                                        EffectiveSettingsDto settings,
                                        int rowCount,
                                        List<CraftingRowDto> rows) {
    public record EffectiveScopeDto(String discipline, String characterName, int rating) { }

    public record EffectiveSettingsDto(boolean useOwnMats,
                                       boolean allowBuying,
                                       boolean listingSell,
                                       boolean listingBuy,
                                       boolean allowDailyCrafts) { }
}
