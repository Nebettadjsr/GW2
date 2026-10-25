package craft;

public class CraftingSettings {
    public final boolean useOwnMats;
    public final boolean allowBuying;
    public final int maxBuyCopper;
    public final boolean listingSell;   // false=instant sell, true=listing sell
    public final boolean listingBuy;
    public final boolean allowDailyCrafts;

    public CraftingSettings(boolean useOwnMats,
                            boolean allowBuy,
                            int maxBuyCopper,
                            boolean listingSell,
                            boolean listingBuy,
                            boolean allowDailyCrafts) {
        this.useOwnMats = useOwnMats;
        this.allowBuying = allowBuy;
        this.maxBuyCopper = maxBuyCopper;
        this.listingSell = listingSell;
        this.listingBuy = listingBuy;
        this.allowDailyCrafts = allowDailyCrafts;
    }

}
