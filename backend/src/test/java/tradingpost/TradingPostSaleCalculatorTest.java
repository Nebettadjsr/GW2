package tradingpost;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DOMAIN_SPEC.md §25: one explicitly specified Trading Post sale yields a gross value, a separately
 * rounded 5% listing fee and 10% exchange fee (each with a 1-copper minimum), and the net proceeds
 * left over. Every expected amount below is written out by hand from the documented rule, never by
 * re-running the production formula.
 */
class TradingPostSaleCalculatorTest {

    @Test
    void productOwnerExample_300CopperGross_isSplitInto15And30CopperFees() {
        // Request-008: 5% of 300 = 15, 10% of 300 = 30, leaving 255.
        var sale = TradingPostSaleCalculator.forSale(300, 1);

        assertEquals(300, sale.grossCopper());
        assertEquals(15, sale.listingFeeCopper());
        assertEquals(30, sale.exchangeFeeCopper());
        assertEquals(45, sale.totalFeesCopper());
        assertEquals(255, sale.netProceedsCopper());
    }

    @Test
    void roundsTheTwoFeeComponentsSeparately_notOneCombined15Percent() {
        // 15c gross: 5% = 0.75 -> 1, 10% = 1.5 -> 2, so 3c of fees and 12c net.
        // A combined 15% would be 2.25 -> 2 (13c net), and 15 * 0.85 = 12.75 -> 13.
        var sale = TradingPostSaleCalculator.forSale(15, 1);

        assertEquals(1, sale.listingFeeCopper());
        assertEquals(2, sale.exchangeFeeCopper());
        assertEquals(3, sale.totalFeesCopper());
        assertEquals(12, sale.netProceedsCopper());
        assertNotEquals(2, sale.totalFeesCopper(), "a single rounded 15% would give 2c of fees");
        assertNotEquals(13, sale.netProceedsCopper(), "gross * 0.85 would give 13c net");
    }

    @Test
    void roundsAFractionalFeeHalfAwayFromZero() {
        // 10c gross: 5% = 0.5 -> 1. 30c gross: 5% = 1.5 -> 2, 10% = 3.0 stays 3.
        assertEquals(1, TradingPostSaleCalculator.forSale(10, 1).listingFeeCopper());
        assertEquals(2, TradingPostSaleCalculator.forSale(30, 1).listingFeeCopper());
        assertEquals(3, TradingPostSaleCalculator.forSale(30, 1).exchangeFeeCopper());

        // 21c gross: 5% = 1.05 -> 1 and 10% = 2.1 -> 2 both round down.
        assertEquals(1, TradingPostSaleCalculator.forSale(21, 1).listingFeeCopper());
        assertEquals(2, TradingPostSaleCalculator.forSale(21, 1).exchangeFeeCopper());
    }

    @Test
    void appliesTheOneCopperMinimumToEachFeeComponent() {
        // 3c gross: 5% = 0.15 and 10% = 0.3 both round to 0, so both minimums bind.
        var sale = TradingPostSaleCalculator.forSale(3, 1);

        assertEquals(1, sale.listingFeeCopper());
        assertEquals(1, sale.exchangeFeeCopper());
        assertEquals(2, sale.totalFeesCopper());
        assertEquals(1, sale.netProceedsCopper());
    }

    @Test
    void netProceedsGoNegativeWhenBothMinimumFeesExceedTheSale() {
        var sale = TradingPostSaleCalculator.forSale(1, 1);

        assertEquals(2, sale.totalFeesCopper());
        assertEquals(-1, sale.netProceedsCopper());
    }

    @Test
    void chargesFeesOnTheWholeTransaction_notPerItem() {
        // 3 items at 7c is a 21c sale: 1c + 2c of fees. Charged per item it would instead be
        // 3 x (1c minimum listing + 1c exchange) = 6c, which this basis deliberately is not.
        var sale = TradingPostSaleCalculator.forSale(7, 3);

        assertEquals(21, sale.grossCopper());
        assertEquals(1, sale.listingFeeCopper());
        assertEquals(2, sale.exchangeFeeCopper());
        assertEquals(3, sale.totalFeesCopper());
        assertEquals(18, sale.netProceedsCopper());
    }

    @Test
    void noSaleCarriesNoFee() {
        var zeroQuantity = TradingPostSaleCalculator.forSale(500, 0);
        assertEquals(0, zeroQuantity.grossCopper());
        assertEquals(0, zeroQuantity.totalFeesCopper());
        assertEquals(0, zeroQuantity.netProceedsCopper());

        var zeroPrice = TradingPostSaleCalculator.forSale(0, 500);
        assertEquals(0, zeroPrice.grossCopper());
        assertEquals(0, zeroPrice.totalFeesCopper());
        assertEquals(0, zeroPrice.netProceedsCopper());
    }

    @Test
    void preservesTheStatedMarketQuoteAlongsideTheNetResult() {
        var sale = TradingPostSaleCalculator.forSale(1234, 7);

        assertEquals(1234, sale.grossUnitPriceCopper());
        assertEquals(7, sale.quantity());
        assertEquals(8638, sale.grossCopper());
    }

    @Test
    void instantAndListingSalePricesEachLoseExactlyOneSetOfFees() {
        // The caller picks the price under DOMAIN_SPEC.md §20; each quote is charged once, on itself.
        var instantSell = TradingPostSaleCalculator.forSale(200, 1);   // 10c + 20c
        var listingSell = TradingPostSaleCalculator.forSale(240, 1);   // 12c + 24c

        assertEquals(170, instantSell.netProceedsCopper());
        assertEquals(204, listingSell.netProceedsCopper());
    }

    @Test
    void grossMinusBothFeesAlwaysEqualsNetProceeds() {
        for (long unitPrice = 0; unitPrice <= 400; unitPrice++) {
            for (long quantity : new long[]{0, 1, 2, 17, 250}) {
                var sale = TradingPostSaleCalculator.forSale(unitPrice, quantity);

                assertEquals(unitPrice * quantity, sale.grossCopper());
                assertEquals(sale.listingFeeCopper() + sale.exchangeFeeCopper(),
                        sale.totalFeesCopper());
                assertEquals(sale.grossCopper() - sale.totalFeesCopper(),
                        sale.netProceedsCopper());
            }
        }
    }

    @Test
    void supportsTheLargestDocumentedGrossSale() {
        var sale = TradingPostSaleCalculator.forSale(
                TradingPostSaleCalculator.MAX_GROSS_SALE_COPPER, 1);

        assertEquals(TradingPostSaleCalculator.MAX_GROSS_SALE_COPPER, sale.grossCopper());
        assertEquals(sale.grossCopper() - sale.totalFeesCopper(), sale.netProceedsCopper());
        assertTrue(sale.listingFeeCopper() > 0 && sale.exchangeFeeCopper() > 0);
    }

    @Test
    void rejectsSalesBeyondTheSupportedGrossValue() {
        assertThrows(IllegalArgumentException.class, () -> TradingPostSaleCalculator.forSale(
                TradingPostSaleCalculator.MAX_GROSS_SALE_COPPER + 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> TradingPostSaleCalculator.forSale(Long.MAX_VALUE, 2));
    }

    @Test
    void rejectsNegativePricesAndQuantities() {
        assertThrows(IllegalArgumentException.class, () -> TradingPostSaleCalculator.forSale(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> TradingPostSaleCalculator.forSale(100, -1));
    }
}
