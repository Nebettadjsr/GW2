package web.dto;

/**
 * Transport request body for {@code POST /api/crafting/discovery/resolution} (STORY-API-008,
 * TARGET_ARCHITECTURE.md §13.1).
 *
 * <p>The Discovery counterpart of {@link CraftingProfitResolutionRequest}: the same two required
 * members, with {@code calculation} being the existing Discovery table request contract
 * ({@link CraftingDiscoveryRequest}). That keeps Discovery's own inputs intact on this route: one
 * required individual character/discipline scope, which also determines the owned-inventory
 * context, and Discovery's settings. Daily crafting remains fixed by the application rather than
 * accepted as an input.
 *
 * <p>This type exists only at the HTTP boundary: it is not a domain, persistence or GW2 API model.
 *
 * @param recipeId the recipe's own identity, never merely its output item ID; required and positive
 * @param calculation the Discovery calculation to resolve it in; required
 */
public record CraftingDiscoveryResolutionRequest(Integer recipeId,
                                                CraftingDiscoveryRequest calculation) {
}
