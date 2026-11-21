package craft;

/**
 * The Forge equipment calculation menu (backend authority). Defaults: instant buy, daily outputs crafted,
 * with owned-material use, owned-material valuation, promotions and owned-output reduction switched off.
 */
public record ForgeSettings(PriceMode priceMode, boolean useOwn, boolean valueOwn, DailyMode daily,
                            boolean promotions, ReduceMode reduce) {
    public enum PriceMode { INSTANT_BUY, BUY_ORDER }
    public enum DailyMode { CRAFT, BUY }
    public enum ReduceMode { NO, YES, TRADABLE, UNTRADABLE }

    public static final ForgeSettings DEFAULT = new ForgeSettings(PriceMode.INSTANT_BUY, false, false, DailyMode.CRAFT,
            false, ReduceMode.NO);

    public ForgeSettings {
        if (priceMode == null || daily == null || reduce == null) throw new IllegalArgumentException("incomplete settings");
    }

    /** Stable identity text for source/calculation identity. */
    public String fingerprint() {
        return "fs-v1:" + priceMode + ":" + useOwn + ":" + valueOwn + ":" + daily + ":" + promotions + ":" + reduce;
    }
}
