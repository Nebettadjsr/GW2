package ecto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DOMAIN_SPEC.md §45-47 / DQ-011: the Ectoplasm Salvage calculation deducts the project's 15%
 * Trading Post selling fee from recovered Dust proceeds exactly once, leaves Ecto acquisition cost
 * and the expected yield assumptions untouched, and derives cost-per-1000-Luck from that same
 * fee-inclusive net cost.
 */
class EctoSalvageCalculatorTest {

    // Distinct quotes for the four Ecto-buy / Dust-sell combinations.
    private static final int ECTO_INSTANT_BUY = 1000;
    private static final int ECTO_LISTING_BUY = 900;
    private static final int DUST_INSTANT_SELL = 200;
    private static final int DUST_LISTING_SELL = 240;

    @Test
    void instantBuyInstantSellDeductsFeeOnceFromDustProceeds() {
        var r = EctoSalvageCalculator.evaluate(ECTO_INSTANT_BUY, DUST_INSTANT_SELL);

        // dustNet = round(200 * 0.85) = 170; netValue = round(170 * 0.75) = 128
        assertEquals(170, r.dustNetUnitPrice());
        assertEquals(128, r.netValueOfRecoveredDust());
        assertEquals(872, r.netCostPerEcto());
        assertEquals(-872, r.profitPerEcto());
        assertEquals(43600, r.costPer1000Luck());
    }

    @Test
    void instantBuyListingSellDeductsFeeOnceFromDustProceeds() {
        var r = EctoSalvageCalculator.evaluate(ECTO_INSTANT_BUY, DUST_LISTING_SELL);

        // dustNet = round(240 * 0.85) = 204; netValue = round(204 * 0.75) = 153
        assertEquals(204, r.dustNetUnitPrice());
        assertEquals(153, r.netValueOfRecoveredDust());
        assertEquals(847, r.netCostPerEcto());
        assertEquals(-847, r.profitPerEcto());
        assertEquals(42350, r.costPer1000Luck());
    }

    @Test
    void listingBuyInstantSellDeductsFeeOnceFromDustProceeds() {
        var r = EctoSalvageCalculator.evaluate(ECTO_LISTING_BUY, DUST_INSTANT_SELL);

        assertEquals(170, r.dustNetUnitPrice());
        assertEquals(128, r.netValueOfRecoveredDust());
        assertEquals(772, r.netCostPerEcto());
        assertEquals(-772, r.profitPerEcto());
        assertEquals(38600, r.costPer1000Luck());
    }

    @Test
    void listingBuyListingSellDeductsFeeOnceFromDustProceeds() {
        var r = EctoSalvageCalculator.evaluate(ECTO_LISTING_BUY, DUST_LISTING_SELL);

        assertEquals(204, r.dustNetUnitPrice());
        assertEquals(153, r.netValueOfRecoveredDust());
        assertEquals(747, r.netCostPerEcto());
        assertEquals(-747, r.profitPerEcto());
        assertEquals(37350, r.costPer1000Luck());
    }

    @Test
    void zeroProfitBoundaryWhenNetDustValueExactlyCoversEctoCost() {
        // dustGross=100 -> dustNet=round(85)=85 -> netValue=round(85*0.75)=64
        var r = EctoSalvageCalculator.evaluate(64, 100);

        assertEquals(64, r.netValueOfRecoveredDust());
        assertEquals(0, r.netCostPerEcto());
        assertEquals(0, r.profitPerEcto());
        assertEquals(0, r.costPer1000Luck());
    }

    @Test
    void negativeProfitWhenNetDustValueFallsShortOfEctoCost() {
        // Same net dust value (64) as the zero-profit case, but a higher Ecto acquisition cost.
        var r = EctoSalvageCalculator.evaluate(70, 100);

        assertEquals(64, r.netValueOfRecoveredDust());
        assertEquals(6, r.netCostPerEcto());
        assertEquals(-6, r.profitPerEcto());
        assertEquals(300, r.costPer1000Luck());
    }

    /**
     * The percentage the boundaries state and the multiplier this calculator applies are two
     * declarations of one domain rule (DOMAIN_SPEC.md §25), so they are pinned to each other: a
     * changed fee that reached only one of them fails here rather than producing a screen whose
     * stated percentage disagrees with its own numbers.
     */
    @Test
    void theStatedFeePercentageIsTheOneTheCalculationActuallyDeducts() {
        assertEquals(15, EctoSalvageCalculator.SELL_FEE_PERCENT);

        int gross = 10_000;
        assertEquals(gross - (gross * EctoSalvageCalculator.SELL_FEE_PERCENT / 100),
                EctoSalvageCalculator.netSaleProceeds(gross));
    }

    @Test
    void ectoAcquisitionCostAndExpectedYieldsAreUnchangedAndNeverFeeAdjusted() {
        assertEquals(20.0, EctoSalvageCalculator.LUCK_PER_ECTO);
        assertEquals(0.75, EctoSalvageCalculator.DUST_PER_ECTO);
        assertEquals(50, EctoSalvageCalculator.ECTOS_PER_1000_LUCK);

        var r = EctoSalvageCalculator.evaluate(ECTO_INSTANT_BUY, DUST_INSTANT_SELL);

        // Acquisition cost passes through untouched: it is the raw quote, not a fee-adjusted value.
        assertEquals(ECTO_INSTANT_BUY, r.ectoAcquisitionCost());

        // Cost-per-1000-Luck is exactly the fee-inclusive net cost scaled by the ecto count for
        // 1000 Luck; no separate deduction is ever applied to Luck itself.
        assertEquals(r.netCostPerEcto() * EctoSalvageCalculator.ECTOS_PER_1000_LUCK, r.costPer1000Luck());
    }
}
