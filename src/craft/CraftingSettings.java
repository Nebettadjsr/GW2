package craft;

public class CraftingSettings {
    public final boolean includeBank;
    public final boolean allowBuying;
    public final int maxBuyCopper;      // parse "20g" later
    public final boolean listingSell;   // false=instant sell, true=listing sell
    public final boolean listingBuy;


    public CraftingSettings(boolean includeBank, boolean allowBuying, int maxBuyCopper,
                            boolean listingSell, boolean listingBuy) {
        this.includeBank = includeBank;
        this.allowBuying = allowBuying;
        this.maxBuyCopper = maxBuyCopper;
        this.listingSell = listingSell;
        this.listingBuy = listingBuy;
    }

}
