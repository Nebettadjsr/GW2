package craft;

import java.util.List;

/**
 * Planner factory for tests written against the pre-acceptance menu (use/value own, promotions allowed, reduce by
 * every owned output). The accepted product defaults are all off; these tests state the legacy menu explicitly so
 * their stock/promotion/valuation assertions keep exercising those paths instead of being weakened.
 */
public final class LegacyForge {
    public static final ForgeSettings SETTINGS = new ForgeSettings(ForgeSettings.PriceMode.INSTANT_BUY, true, true,
            ForgeSettings.DailyMode.CRAFT, true, ForgeSettings.ReduceMode.YES);

    private LegacyForge() {}

    public static ForgeProjectPlanner planner(List<Recipe> producers) {
        return new ForgeProjectPlanner(producers, SETTINGS);
    }
}
