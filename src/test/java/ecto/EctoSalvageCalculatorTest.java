package ecto;

import org.junit.jupiter.api.Test;
import tradingpost.TradingPostFeePolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DOMAIN_SPEC.md §25 and §45-47 (STORY-DOM-024): the Ectoplasm Salvage calculation scales the
 * expected Dust yield onto the selected gross quote first, then deducts the shared 15% Trading Post
 * selling fee from that expected gross recovered value exactly once. Ecto acquisition cost, the
 * expected yields and both Dust quotes stay gross, and cost-per-1000-Luck comes from the same
 * fee-inclusive net cost.
 *
 * <p>Every expected amount below is written out by hand from the spec's own rule - none is
 * recomputed from the production formula - so a calculation that changed its order, its rounding or
 * the number of deductions fails here instead of agreeing with itself.
 */
class EctoSalvageCalculatorTest {

    // Distinct quotes for the four Ecto-buy / Dust-sell combinations.
    private static final int ECTO_INSTANT_BUY = 1000;
    private static final int ECTO_LISTING_BUY = 900;
    private static final int DUST_INSTANT_SELL = 200;
    private static final int DUST_LISTING_SELL = 240;

    // 200c × 0.75 = 150c gross recovered; 15% of that is 22.5c -> 23c fee; 150 - 23 = 127c.
    private static final int NET_AT_INSTANT_SELL = 127;
    // 240c × 0.75 = 180c gross recovered; 15% of that is exactly 27c; 180 - 27 = 153c.
    private static final int NET_AT_LISTING_SELL = 153;

    @Test
    void instantBuyInstantSellDeductsTheFeeOnceFromTheExpectedGrossRecoveredValue() {
        var r = EctoSalvageCalculator.evaluate(ECTO_INSTANT_BUY, DUST_INSTANT_SELL);

        assertEquals(150, r.expectedGrossRecoveredDustValue());
        assertEquals(NET_AT_INSTANT_SELL, r.netValueOfRecoveredDust());
        assertEquals(873, r.netCostPerEcto());     // 1000 - 127
        assertEquals(-873, r.profitPerEcto());
        assertEquals(43_650, r.costPer1000Luck()); // 873 × 50
    }

    @Test
    void instantBuyListingSellDeductsTheFeeOnceFromTheExpectedGrossRecoveredValue() {
        var r = EctoSalvageCalculator.evaluate(ECTO_INSTANT_BUY, DUST_LISTING_SELL);

        assertEquals(180, r.expectedGrossRecoveredDustValue());
        assertEquals(NET_AT_LISTING_SELL, r.netValueOfRecoveredDust());
        assertEquals(847, r.netCostPerEcto());     // 1000 - 153
        assertEquals(-847, r.profitPerEcto());
        assertEquals(42_350, r.costPer1000Luck());
    }

    @Test
    void listingBuyInstantSellDeductsTheFeeOnceFromTheExpectedGrossRecoveredValue() {
        var r = EctoSalvageCalculator.evaluate(ECTO_LISTING_BUY, DUST_INSTANT_SELL);

        assertEquals(150, r.expectedGrossRecoveredDustValue());
        assertEquals(NET_AT_INSTANT_SELL, r.netValueOfRecoveredDust());
        assertEquals(773, r.netCostPerEcto());     // 900 - 127
        assertEquals(-773, r.profitPerEcto());
        assertEquals(38_650, r.costPer1000Luck());
    }

    @Test
    void listingBuyListingSellDeductsTheFeeOnceFromTheExpectedGrossRecoveredValue() {
        var r = EctoSalvageCalculator.evaluate(ECTO_LISTING_BUY, DUST_LISTING_SELL);

        assertEquals(180, r.expectedGrossRecoveredDustValue());
        assertEquals(NET_AT_LISTING_SELL, r.netValueOfRecoveredDust());
        assertEquals(747, r.netCostPerEcto());     // 900 - 153
        assertEquals(-747, r.profitPerEcto());
        assertEquals(37_350, r.costPer1000Luck());
    }

    /**
     * The acquisition side is independent of the sale side: the four scenarios above differ only by
     * which Ecto quote and which Dust quote they were given, and neither quote is altered on the way
     * through.
     */
    @Test
    void bothQuotesArePassedThroughGrossInEveryScenario() {
        var instantInstant = EctoSalvageCalculator.evaluate(ECTO_INSTANT_BUY, DUST_INSTANT_SELL);
        var listingListing = EctoSalvageCalculator.evaluate(ECTO_LISTING_BUY, DUST_LISTING_SELL);

        assertEquals(ECTO_INSTANT_BUY, instantInstant.ectoAcquisitionCost());
        assertEquals(DUST_INSTANT_SELL, instantInstant.dustGrossUnitPrice());
        assertEquals(ECTO_LISTING_BUY, listingListing.ectoAcquisitionCost());
        assertEquals(DUST_LISTING_SELL, listingListing.dustGrossUnitPrice());
    }

    /**
     * §46: the expected yield is scaled onto the gross quote first and the fee lands on that
     * aggregate. Rounding each Dust unit into a modeled sale first - the superseded order - would
     * take 15% off 200c to get 170c and then scale, reaching 128c instead of 127c.
     */
    @Test
    void theYieldIsScaledBeforeTheFeeRatherThanAfterAModeledPerUnitSale() {
        var r = EctoSalvageCalculator.evaluate(0, DUST_INSTANT_SELL);

        assertEquals(150, r.expectedGrossRecoveredDustValue(),
                     "the expected gross recovered value is 0.75 × the gross quote, fee-free");
        assertEquals(127, r.netValueOfRecoveredDust(),
                     "the superseded per-unit-sale order would have produced 128c here");
    }

    /**
     * A fractional expected Dust yield is an expected value, not a whole modeled sale: 0.75 of a
     * 201c quote is 150.75c and is carried as such into the fee, rather than being cut down to a
     * whole Dust unit or rounded up into one extra sale.
     */
    @Test
    void aFractionalExpectedYieldIsCarriedIntoTheValueRatherThanRoundedIntoWholeSales() {
        // 201 × 0.75 = 150.75c -> 151c; 15% of 151c is 22.65c -> 23c; 151 - 23 = 128c.
        var oddQuote = EctoSalvageCalculator.evaluate(1000, 201);
        assertEquals(151, oddQuote.expectedGrossRecoveredDustValue());
        assertEquals(128, oddQuote.netValueOfRecoveredDust());
        assertEquals(872, oddQuote.netCostPerEcto());

        // 2 × 0.75 = 1.5c -> 2c. Neither truncated to one whole Dust unit's 2c-quote sale nor
        // inflated to a second one.
        var tinyQuote = EctoSalvageCalculator.evaluate(1000, 2);
        assertEquals(2, tinyQuote.expectedGrossRecoveredDustValue());
        // 15% of 2c is 0.3c -> 0c: §25's model charges a rate, and §25.1's transaction minimums are
        // explicitly not substituted for it.
        assertEquals(2, tinyQuote.netValueOfRecoveredDust());
        assertEquals(998, tinyQuote.netCostPerEcto());
    }

    /** A quote of zero recovers nothing, which is not a sale and therefore carries no fee. */
    @Test
    void anUnquotedDustSideRecoversNothingAndIsChargedNothing() {
        var r = EctoSalvageCalculator.evaluate(1000, 0);

        assertEquals(0, r.expectedGrossRecoveredDustValue());
        assertEquals(0, r.netValueOfRecoveredDust());
        assertEquals(1000, r.netCostPerEcto());
        assertEquals(-1000, r.profitPerEcto());
    }

    @Test
    void zeroProfitBoundaryWhenTheNetRecoveredValueExactlyCoversTheEctoCost() {
        var r = EctoSalvageCalculator.evaluate(NET_AT_INSTANT_SELL, DUST_INSTANT_SELL);

        assertEquals(127, r.netValueOfRecoveredDust());
        assertEquals(0, r.netCostPerEcto());
        assertEquals(0, r.profitPerEcto());
        assertEquals(0, r.costPer1000Luck());
    }

    @Test
    void aLossKeepsItsSignAndIsNotTheSameAsBreakingEven() {
        // Same net recovered value as the break-even case, 3c more paid for the ecto.
        var r = EctoSalvageCalculator.evaluate(130, DUST_INSTANT_SELL);

        assertEquals(127, r.netValueOfRecoveredDust());
        assertEquals(3, r.netCostPerEcto());
        assertEquals(-3, r.profitPerEcto());
        assertEquals(150, r.costPer1000Luck());
    }

    @Test
    void aGainIsReportedAsPositiveProfitAndNegativeNetCost() {
        var r = EctoSalvageCalculator.evaluate(100, DUST_INSTANT_SELL);

        assertEquals(127, r.netValueOfRecoveredDust());
        assertEquals(-27, r.netCostPerEcto());
        assertEquals(27, r.profitPerEcto());
        assertEquals(-1350, r.costPer1000Luck());
    }

    /**
     * The deduction is the shared policy's percentage, not a second rate this calculator owns: on a
     * gross recovered value the rate divides exactly, the arithmetic is visible without any rounding
     * rule at all (STORY-DOM-023's {@code tradingpost.TradingPostFeePolicy} is the single owner).
     */
    @Test
    void theDeductedRateIsTheSharedPolicysOwnPercentage() {
        // 267c × 0.75 = 200.25c -> 200c gross recovered, of which 15% is exactly 30c.
        var r = EctoSalvageCalculator.evaluate(1000, 267);

        assertEquals(200, r.expectedGrossRecoveredDustValue());
        assertEquals(170, r.netValueOfRecoveredDust());
        assertEquals(200 - (200 * TradingPostFeePolicy.PROFIT_FEE_PERCENT / 100),
                     r.netValueOfRecoveredDust(),
                     "the fee actually deducted must be the percentage the boundaries state");
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
