package web.dto;

import java.util.List;

/** Response for one-craft Discovery candidates; the scope identifies both character eligibility and inventory. */
public record CraftingDiscoveryResponse(EffectiveScopeDto scope,
                                        EffectiveSettingsDto settings,
                                        int rowCount,
                                        List<CraftingRowDto> rows,
                                        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                                        DiscoveryFreshnessDto freshness) {
    public CraftingDiscoveryResponse(EffectiveScopeDto scope, EffectiveSettingsDto settings, int rowCount,
                                     List<CraftingRowDto> rows) {
        this(scope, settings, rowCount, rows, null);
    }

    public record EffectiveScopeDto(String discipline, String characterName, int rating) { }

    public record EffectiveSettingsDto(boolean useOwnMats,
                                       boolean allowBuying,
                                       boolean listingSell,
                                       boolean listingBuy,
                                       boolean allowDailyCrafts) { }
}
