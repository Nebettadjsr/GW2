package web.dto;

/**
 * Transport request body for {@code POST /api/crafting/discovery/resolution} (STORY-API-008,
 * TARGET_ARCHITECTURE.md §13.1).
 *
 * <p>The Discovery counterpart of {@link CraftingProfitResolutionRequest}: the same two required
 * members, with {@code calculation} being the existing Discovery table request contract
 * ({@link CraftingDiscoveryRequest}). That keeps Discovery's own inputs intact on this route - a
 * required individual discipline+character scope, the separate nullable
 * {@code inventoryCharacterName} with its unfiltered-pool fallback, and Discovery's own settings
 * defaults with {@code dailyBuyInsteadOfCraft} fixed to false rather than accepted as an input.
 *
 * <p>This type exists only at the HTTP boundary: it is not a domain, persistence or GW2 API model.
 *
 * @param recipeId the recipe's own identity, never merely its output item ID; required and positive
 * @param calculation the Discovery calculation to resolve it in; required
 */
public record CraftingDiscoveryResolutionRequest(Integer recipeId,
                                                CraftingDiscoveryRequest calculation) {
}
