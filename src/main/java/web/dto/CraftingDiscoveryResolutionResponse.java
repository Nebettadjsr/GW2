package web.dto;

/**
 * Transport response body for {@code POST /api/crafting/discovery/resolution} (STORY-API-008,
 * TARGET_ARCHITECTURE.md §13.3's completed response envelope).
 *
 * <p>The same envelope as {@link CraftingProfitResolutionResponse} - identical field names, literals
 * and meanings, so a caller reads a resolution the same way on either route - differing only in the
 * shape of the echoed {@code calculation}, which is Discovery's own.
 *
 * <p>This type exists only at the HTTP boundary: it is not a domain, persistence or GW2 API model.
 */
public record CraftingDiscoveryResolutionResponse(int recipeId,
                                                 CalculationDto calculation,
                                                 String consistency,
                                                 String calculatedAt,
                                                 CraftingRowDto row,
                                                 String treeStatus,
                                                 String treeBasis,
                                                 ResolutionNodeDto tree) {

    /**
     * The effective inputs this calculation ran with, in the shape the Discovery table response
     * echoes them ({@link CraftingDiscoveryResponse}): the individual discipline+character scope, the
     * separate inventory character (null when none was supplied and the unfiltered owned pool was
     * used) and the effective settings, including the {@code dailyBuyInsteadOfCraft} value Discovery
     * fixes rather than accepts.
     */
    public record CalculationDto(CraftingDiscoveryResponse.EffectiveScopeDto scope,
                                 String inventoryCharacterName,
                                 CraftingDiscoveryResponse.EffectiveSettingsDto settings) {
    }
}
