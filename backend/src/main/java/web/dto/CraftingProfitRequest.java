package web.dto;

/**
 * Transport request body for {@code POST /api/crafting/profit}.
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
     * field has the default documented on {@code web.CraftingProfitApiMapper}, so an empty request
     * body is the default All-scope calculation.
     *
     * <p>{@code useOwnMats}, {@code allowBuying} and {@code allowDailyCrafts} are independent:
     * owned inventory, Trading Post purchases and daily craft operations are controlled only by
     * their corresponding field. {@code allowDailyCrafts} defaults to false.
     */
    public record SettingsDto(Boolean useOwnMats,
                              Boolean allowBuying,
                              Integer maxBuyCopper,
                              Boolean listingSell,
                              Boolean listingBuy,
                              Boolean allowDailyCrafts,
                              Boolean materialProtectionEnabled,
                              java.util.List<Integer> protectedItemIds,
                              java.util.List<Integer> protectedBankItemIds,
                              java.util.List<Integer> protectedMaterialItemIds,
                              java.util.Map<?, ?> protectedBankQuantities,
                              java.util.Map<?, ?> protectedMaterialQuantities,
                              Object protectedBankAllocations) {
    }
}
