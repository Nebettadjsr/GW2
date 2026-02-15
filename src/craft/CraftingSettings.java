package craft;

public class CraftingSettings {
    public final boolean includeBank;
    public final boolean allowBuying;
    public final int maxBuyCopper;
    public final boolean listingSell;   // false=instant sell, true=listing sell
    public final boolean listingBuy;
    public final boolean dailyBuyInsteadOfCraft; // true = treat daily items as "buy", not "craft"


    public CraftingSettings(boolean includeBank,
                            boolean allowBuy,
                            int maxBuyCopper,
                            boolean listingSell,
                            boolean listingBuy,
                            boolean dailyBuyInsteadOfCraft) {
        this.includeBank = includeBank;
        this.allowBuying = allowBuy;
        this.maxBuyCopper = maxBuyCopper;
        this.listingSell = listingSell;
        this.listingBuy = listingBuy;
        this.dailyBuyInsteadOfCraft = dailyBuyInsteadOfCraft;
    }

}