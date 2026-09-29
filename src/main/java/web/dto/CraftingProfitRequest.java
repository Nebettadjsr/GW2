package web.dto;

/**
 * Transport request body for {@code POST /api/crafting/profit} (STORY-API-001,
 * TARGET_ARCHITECTURE.md §9).
 *
 * <p>Every member is optional and nullable so the request carries "not supplied" distinctly from a
 * supplied value; {@code web.CraftingProfitApiMapper} fills in the documented defaults and
 * validates the result before any calculation runs. The whole body may also be omitted, which
 * requests the default All-scope calculation.
 *
 * <p>This type exists only at the HTTP boundary: it is not a domain, persistence or GW2 API model.
 */
public record CraftingProfitRequest(ScopeDto scope, SettingsDto settings) {

    /**
     * Requested calculation scope, mapping onto {@code repo.DiscChoice}.
     *
     * @param kind          {@code ALL}, {@code DISCIPLINE} or {@code CHARACTER_DISCIPLINE};
     *                      defaults to {@code ALL}
     * @param discipline    required for {@code DISCIPLINE} and {@code CHARACTER_DISCIPLINE}
     * @param characterName required for {@code CHARACTER_DISCIPLINE}
     * @param rating        the selector's displayed rating for {@code CHARACTER_DISCIPLINE};
     *                      defaults to 0. Carried for round-tripping only - the calculation reads
     *                      ratings from synced character data, not from this field.
     */
    public record ScopeDto(String kind, String discipline, String characterName, Integer rating) {
    }

    /**
     * Requested calculation settings, mapping onto {@code craft.CraftingSettings}. Each omitted
     * field defaults to the value the JavaFX Crafting Profit view opens with, so an empty request
     * body reproduces the default page load.
     *
     */
    public record SettingsDto(Boolean useOwnMats,
                              Boolean allowBuying,
                              Integer maxBuyCopper,
                              Boolean listingSell,
                              Boolean listingBuy,
                              Boolean dailyBuyInsteadOfCraft) {
    }
}
