package ecto;

import tradingpost.TradingPostFeePolicy;

/**
 * Pure Ectoplasm Salvage domain calculation (DOMAIN_SPEC.md §45-47), kept free of JavaFX so it can
 * be exercised by deterministic unit tests. {@code application.EctoSalvageService} is the sole caller.
 *
 * <p><b>The fee is the shared one.</b> Since STORY-DOM-024 the selling fee comes from
 * {@link TradingPostFeePolicy}, the single backend owner of §25's decided percentage policy - this
 * class holds no fee rate, multiplier or rounding rule of its own. It is charged <b>once</b>, on the
 * expected <em>gross</em> recovered Dust value of one Ectoplasm, which is the expected Dust yield
 * scaled onto the selected gross Dust quote. No Dust unit is rounded into a modeled sale transaction
 * before the yield is applied, and no fractional expected drop is treated as an actual sale (§46,
 * resolved UD-011).
 */
public final class EctoSalvageCalculator {

    public static final double LUCK_PER_ECTO = 20.0;
    public static final double DUST_PER_ECTO = 0.75;
    public static final int ECTOS_PER_1000_LUCK = 50;

    private EctoSalvageCalculator() {}

    /**
     * One Ecto-buy / Dust-sell scenario (DOMAIN_SPEC.md §46-47).
     *
     * <p>{@code ectoAcquisitionCost}, {@code dustGrossUnitPrice} and
     * {@code expectedGrossRecoveredDustValue} are gross: no selling fee is in any of them, and they
     * are the values a display shows as prices. The remaining fields are economic results the
     * selling fee has been applied to exactly once, on the Dust side only - they are costs, not
     * market prices.
     *
     * @param expectedGrossRecoveredDustValue what the expected Dust from one Ectoplasm is worth at
     *                                        the scenario's gross quote, before any fee
     * @param netValueOfRecoveredDust         that same expected value less {@link TradingPostFeePolicy}'s fee
     */
    public record ScenarioResult(
            int ectoAcquisitionCost,
            int dustGrossUnitPrice,
            int expectedGrossRecoveredDustValue,
            int netValueOfRecoveredDust,
            int netCostPerEcto,
            int profitPerEcto,
            int costPer1000Luck) {}

    public static ScenarioResult evaluate(int ectoAcquisitionCost, int dustGrossUnitPrice) {
        // Yield first, fee second: the expected Dust of one Ectoplasm is a fraction, and §46 forbids
        // rounding it into whole modeled sales. Rounding lands once, here, on the aggregate copper
        // value - half away from zero, since a quote is never negative.
        int expectedGrossRecoveredDustValue = (int) Math.round(dustGrossUnitPrice * DUST_PER_ECTO);

        // Nothing recovered is nothing sold, so it carries no fee (the same rule
        // craft.CraftingPlanner applies to a non-positive revenue).
        int netValueOfRecoveredDust = expectedGrossRecoveredDustValue > 0
                ? (int) TradingPostFeePolicy.netOfFee(expectedGrossRecoveredDustValue)
                : expectedGrossRecoveredDustValue;

        int netCostPerEcto = ectoAcquisitionCost - netValueOfRecoveredDust;
        int profitPerEcto = -netCostPerEcto;
        int costPer1000Luck = netCostPerEcto * ECTOS_PER_1000_LUCK;

        return new ScenarioResult(
                ectoAcquisitionCost,
                dustGrossUnitPrice,
                expectedGrossRecoveredDustValue,
                netValueOfRecoveredDust,
                netCostPerEcto,
                profitPerEcto,
                costPer1000Luck);
    }
}
