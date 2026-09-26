package craft;

public enum BlockedReason {
    NONE,
    NO_RECIPE,
    PRICE_UNAVAILABLE,
    BUYING_DISABLED,
    DAILY_LIMIT,
    CYCLE_DETECTED,
    RECIPE_NOT_ALLOWED,
    INSUFFICIENT_BUDGET,

    /**
     * The requirement could only be met by consuming a material that cannot be traded on the Trading
     * Post, and the calculation was asked not to use such a path
     * ({@code CraftingSettings.allowNonTradeableMaterials} off; DOMAIN_SPEC.md section 2.1.1,
     * UD-010).
     *
     * <p>It is a restriction the user chose, not a missing price and not a missing recipe: a normally
     * tradeable item with no usable quote remains {@link #PRICE_UNAVAILABLE}, and an item with no
     * usable recipe remains {@link #NO_RECIPE}. This reason never appears while the option is on.
     */
    NON_TRADEABLE_MATERIAL
}