package tradingpost;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * DOMAIN_SPEC.md §25's decided profitability fee (resolved UD-011): 15% of the gross sell value
 * handed in, charged on nothing else. Every expected amount below is written out by hand from that
 * rule rather than produced by calling the production formula again.
 */
class TradingPostFeePolicyTest {

    @Test
    void theDecidedRateIsFifteenPercentOfTheStatedGrossSellValue() {
        assertEquals(45, TradingPostFeePolicy.feeOn(300));
        assertEquals(255, TradingPostFeePolicy.netOfFee(300));
        assertEquals(15, TradingPostFeePolicy.PROFIT_FEE_PERCENT);
    }

    @Test
    void aSellValueOfNothingCarriesNoFee() {
        assertEquals(0, TradingPostFeePolicy.feeOn(0));
        assertEquals(0, TradingPostFeePolicy.netOfFee(0));
    }

    /**
     * §25.1: the transaction model's separately rounded components and 1-copper minimums must not be
     * imposed on this one. Three copper is the case that tells them apart - a real transaction would
     * be charged both minimums, and the decided percentage charges nothing at all.
     */
    @Test
    void smallCopperAmountsCarryNoMinimumFeeUnlikeTheTransactionCalculator() {
        assertEquals(0, TradingPostFeePolicy.feeOn(3));
        assertEquals(3, TradingPostFeePolicy.netOfFee(3));

        long transactionFees = TradingPostSaleCalculator.forSale(3, 1).totalFeesCopper();
        assertEquals(2, transactionFees, "both 1-copper minimums bind on a real 3c transaction");
        assertNotEquals(transactionFees, TradingPostFeePolicy.feeOn(3),
                "§25's percentage model is not §25.1's minimum-fee transaction model");

        // 1c and 4c likewise: the minimum-fee model charges 2c for each, the percentage model 0c/1c.
        assertEquals(0, TradingPostFeePolicy.feeOn(1));
        assertEquals(1, TradingPostFeePolicy.feeOn(4));
    }

    /** A fractional copper is rounded half away from zero; the policy states one rate, not two. */
    @Test
    void fractionalCopperFeesRoundHalfAwayFromZero() {
        assertEquals(2, TradingPostFeePolicy.feeOn(10));   // 1.5, away from zero
        assertEquals(1, TradingPostFeePolicy.feeOn(9));    // 1.35
        assertEquals(2, TradingPostFeePolicy.feeOn(11));   // 1.65
        assertEquals(1, TradingPostFeePolicy.feeOn(7));    // 1.05
        assertEquals(150, TradingPostFeePolicy.feeOn(1_000));
    }

    @Test
    void theNetIsTheGrossLessExactlyOneFee() {
        for (long gross : new long[]{0, 1, 3, 7, 10, 99, 300, 1_000, 123_456, 9_999_999}) {
            assertEquals(gross - TradingPostFeePolicy.feeOn(gross), TradingPostFeePolicy.netOfFee(gross),
                    "gross " + gross);
        }
    }

    @Test
    void anImpossibleSellValueIsRejectedRatherThanPricedAnyway() {
        assertThrows(IllegalArgumentException.class, () -> TradingPostFeePolicy.feeOn(-1));
        assertThrows(IllegalArgumentException.class,
                () -> TradingPostFeePolicy.feeOn(TradingPostFeePolicy.MAX_SELL_VALUE_COPPER + 1));
        assertEquals(614_891_469_123_651_717L, TradingPostFeePolicy.MAX_SELL_VALUE_COPPER);
        assertEquals(92_233_720_368_547_758L,
                TradingPostFeePolicy.feeOn(TradingPostFeePolicy.MAX_SELL_VALUE_COPPER),
                "the documented ceiling is still evaluated, not rejected");
    }
}
