package ecto;

/**
 * Pure Ectoplasm Salvage domain calculation (DOMAIN_SPEC.md §45-47), kept free of JavaFX so it can
 * be exercised by deterministic unit tests. {@code application.EctoSalvageService} is the sole caller.
 */
public final class EctoSalvageCalculator {

    public static final double LUCK_PER_ECTO = 20.0;
    public static final double DUST_PER_ECTO = 0.75;
    public static final int ECTOS_PER_1000_LUCK = 50;

    /**
     * The percentage form of the same fee, for boundaries that have to state it (DOMAIN_SPEC.md §25).
     *
     * <p>Declared separately from {@link #SELL_FEE_MULTIPLIER} rather than derived from it: deriving
     * the multiplier from this would change the existing double arithmetic, and the existing
     * calculation must keep producing exactly the values it produces today. {@code
     * EctoSalvageCalculatorTest} pins the two to each other so they cannot drift apart silently.
     */
    public static final int SELL_FEE_PERCENT = 15;

    /** The project's Trading Post selling fee: 15% deducted from a Trading Post sale (DOMAIN_SPEC.md §46). */
    private static final double SELL_FEE_MULTIPLIER = 0.85;

    private EctoSalvageCalculator() {}

    /** §46 "dust_sale_price": a raw Trading Post quote reduced to net proceeds of one Trading Post sale. */
    public static int netSaleProceeds(int grossUnitPrice) {
        return (int) Math.round(grossUnitPrice * SELL_FEE_MULTIPLIER);
    }

    /**
     * One Ecto-buy / Dust-sell scenario (DOMAIN_SPEC.md §46-47). {@code ectoAcquisitionCost} and the
     * expected yields are never fee-adjusted; only {@code dustGrossUnitPrice} passes through the
     * Trading Post selling fee, once, on its way to {@code netValueOfRecoveredDust}.
     */
    public record ScenarioResult(
            int ectoAcquisitionCost,
            int dustGrossUnitPrice,
            int dustNetUnitPrice,
            int netValueOfRecoveredDust,
            int netCostPerEcto,
            int profitPerEcto,
            int costPer1000Luck) {}

    public static ScenarioResult evaluate(int ectoAcquisitionCost, int dustGrossUnitPrice) {
        int dustNetUnitPrice = netSaleProceeds(dustGrossUnitPrice);
        int netValueOfRecoveredDust = (int) Math.round(dustNetUnitPrice * DUST_PER_ECTO);
        int netCostPerEcto = ectoAcquisitionCost - netValueOfRecoveredDust;
        int profitPerEcto = -netCostPerEcto;
        int costPer1000Luck = netCostPerEcto * ECTOS_PER_1000_LUCK;

        return new ScenarioResult(
                ectoAcquisitionCost,
                dustGrossUnitPrice,
                dustNetUnitPrice,
                netValueOfRecoveredDust,
                netCostPerEcto,
                profitPerEcto,
                costPer1000Luck);
    }
}
