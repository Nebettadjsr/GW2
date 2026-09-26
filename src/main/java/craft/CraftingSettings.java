package craft;

public class CraftingSettings {
    public final boolean useOwnMats;
    public final boolean allowBuying;
    public final int maxBuyCopper;
    public final boolean listingSell;   // false=instant sell, true=listing sell
    public final boolean listingBuy;
    public final boolean dailyBuyInsteadOfCraft; // true = treat daily items as "buy", not "craft"

    /**
     * DOMAIN_SPEC.md section 2.1.1 / UD-009 / UD-010: whether a calculation path may consume a
     * material that cannot be traded on the Trading Post by its item classification.
     *
     * <p>{@code true} (the value every existing caller gets) is the decided default and the behavior
     * this application always had: an owned or craftable non-Trading-Post material is usable under
     * the ordinary inventory/binding, buying, budget, daily and scope rules, and a non-Trading-Post
     * requirement with no owned or craftable acquisition path stays unavailable rather than being
     * pretended into existence.
     *
     * <p>{@code false} restricts the calculation to paths that consume no non-Trading-Post material
     * at all - including owned, account-bound, recursively craftable and intermediate ones - while
     * alternative paths that avoid them are still evaluated normally. Which items those are is not a
     * setting: it is the separate classification fact {@link MaterialTradeability} carries.
     */
    public final boolean allowNonTradeableMaterials;

    /**
     * As {@link #CraftingSettings(boolean, boolean, int, boolean, boolean, boolean, boolean)} with
     * {@link #allowNonTradeableMaterials} enabled - the decided default and the behavior every
     * caller had before that option existed.
     */
    public CraftingSettings(boolean useOwnMats,
                            boolean allowBuy,
                            int maxBuyCopper,
                            boolean listingSell,
                            boolean listingBuy,
                            boolean dailyBuyInsteadOfCraft) {
        this(useOwnMats, allowBuy, maxBuyCopper, listingSell, listingBuy, dailyBuyInsteadOfCraft, true);
    }

    public CraftingSettings(boolean useOwnMats,
                            boolean allowBuy,
                            int maxBuyCopper,
                            boolean listingSell,
                            boolean listingBuy,
                            boolean dailyBuyInsteadOfCraft,
                            boolean allowNonTradeableMaterials) {
        this.useOwnMats = useOwnMats;
        this.allowBuying = allowBuy;
        this.maxBuyCopper = maxBuyCopper;
        this.listingSell = listingSell;
        this.listingBuy = listingBuy;
        this.dailyBuyInsteadOfCraft = dailyBuyInsteadOfCraft;
        this.allowNonTradeableMaterials = allowNonTradeableMaterials;
    }

}