package web.dto;

/**
 * Transport request body for {@code POST /api/crafting/discovery} (STORY-API-002,
 * TARGET_ARCHITECTURE.md §9).
 *
 * <p>Mirrors the inputs the existing Crafting Discovery use case accepts
 * ({@code application.CraftingDiscoveryService#reload}): a discipline+character scope, the
 * separately selected character whose owned inventory is used, and the calculation settings.
 *
 * <p>Unlike the Profit route, the body is <em>required</em>: Discovery has no All-scope reading.
 * The JavaFX Discovery page offers only "Discipline lvl — Character" entries and refuses to
 * calculate without one, so there is no meaningful default scope to fall back on
 * (CURRENT_ARCHITECTURE.md §5.2). Settings fields remain individually optional and nullable, so
 * "not supplied" stays distinct from a supplied value and
 * {@code web.CraftingDiscoveryApiMapper} can fill in the documented defaults and validate the
 * result before any calculation runs.
 *
 * <p>This type exists only at the HTTP boundary: it is not a domain, persistence or GW2 API model.
 *
 * @param inventoryCharacterName the character whose owned inventory the calculation may consume,
 *                               matching Discovery's separate Character selector. Optional and
 *                               independent of {@link ScopeDto#characterName()}; when omitted (and
 *                               {@code useOwnMats} is on) the calculation falls back to the
 *                               unfiltered owned-inventory pool exactly as the JavaFX page does
 *                               with no character selected (STORY-DOM-012).
 */
public record CraftingDiscoveryRequest(ScopeDto scope,
                                       String inventoryCharacterName,
                                       SettingsDto settings) {

    /**
     * The discipline+character combination whose still-missing DISCOVERABLE recipes are listed,
     * mapping onto {@code repo.DiscChoice.charDiscipline(...)}. All three fields are required:
     * Discovery is individual-character only, and {@code rating} drives a real filter, so
     * defaulting it would silently answer with an emptier result than the caller intended.
     *
     * @param discipline    the crafting discipline to list, e.g. {@code "Chef"}. The repository
     *                      treats the literal {@code "All"} as "every discipline"; no other value
     *                      is interpreted.
     * @param characterName the character the combination belongs to. Recipe ownership itself is
     *                      account-wide (DOMAIN_SPEC.md §34 / DQ-010), so this names the selected
     *                      combination rather than narrowing knowledge.
     * @param rating        that character's crafting rating in {@code discipline}; recipes above it
     *                      are filtered out ({@code recipe.minRating <= rating}).
     */
    public record ScopeDto(String discipline, String characterName, Integer rating) {
    }

    /**
     * Requested calculation settings, mapping onto {@code craft.CraftingSettings}. Each omitted
     * field defaults to the value the JavaFX Crafting Discovery view opens with - which differs
     * from the Profit view's defaults, notably in buying being on by default.
     *
     * <p>{@code craft.CraftingSettings#allowDailyCrafts} is deliberately not an input here:
     * the Discovery flow fixes it to true to preserve its existing daily crafting behavior, and
     * this route maps the existing contract rather than widening it. The value actually used is still
     * echoed in the response's effective settings.
     */
    public record SettingsDto(Boolean useOwnMats,
                              Boolean allowBuying,
                              Integer maxBuyCopper,
                              Boolean listingSell,
                              Boolean listingBuy) {
    }
}
