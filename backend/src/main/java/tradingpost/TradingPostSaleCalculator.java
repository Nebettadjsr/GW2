package tradingpost;

/**
 * Pure domain calculation of the Guild Wars 2 Trading Post selling fees for one explicitly
 * specified sale (DOMAIN_SPEC.md §25). No persistence, transport, JavaFX or GW2 API type is
 * involved.
 *
 * <p><b>Input basis.</b> The caller states the sale completely: {@code grossUnitPriceCopper} is
 * the market quote it has already selected under §20's instant-sell / listing-sell rules, and
 * {@code quantity} is how many items that <em>one</em> Trading Post transaction sells. This class
 * never selects a sale mode, re-reads a price, or assumes a quantity, so it can neither deduct a
 * fee twice nor silently pick the crafting/salvage sale grouping that
 * {@code agent/user-decisions/UD-011-trading-post-sale-quantity-basis.md} still owns. A caller
 * holding a per-craft, all-crafts or fractional expected quantity must decide which of those it is
 * selling before calling.
 *
 * <p><b>Verified fee rules</b> (Guild Wars 2 Wiki, "Trading Post" §Additional fees, retrieved
 * 2026-09-26): both fees are charged on the <em>total</em> sale price, the listing fee is 5% with a
 * minimum of 1 copper, and the exchange fee is 10% with a minimum of 1 copper. The two components
 * are rounded separately; a single 15% multiplier is not equivalent.
 *
 * <p><b>Unverified edge rule.</b> No reliable source states the rounding direction the game applies
 * to a fractional copper fee. Rounding up is ruled out by inference — it would make both documented
 * 1-copper minimums unreachable, and therefore meaningless — so {@link #feeOn} rounds half away
 * from zero, matching the only independent implementation that could be inspected (the gw2tp.com
 * calculator). Rounding down is not excluded by the available evidence. That single method is the
 * one place to correct if in-game evidence settles it.
 */
public final class TradingPostSaleCalculator {

    public static final int LISTING_FEE_PERCENT = 5;
    public static final int EXCHANGE_FEE_PERCENT = 10;
    public static final int MINIMUM_FEE_COPPER = 1;

    private static final int PERCENT_SCALE = 100;

    /** Largest gross sale the exact integer arithmetic below evaluates without overflowing a long. */
    public static final long MAX_GROSS_SALE_COPPER =
            (Long.MAX_VALUE - PERCENT_SCALE / 2) / EXCHANGE_FEE_PERCENT;

    private TradingPostSaleCalculator() {}

    /**
     * Fees and net proceeds of selling {@code quantity} items at {@code grossUnitPriceCopper} each
     * in one Trading Post transaction.
     *
     * <p>A sale worth nothing — zero quantity or a zero unit price — is not a transaction and
     * carries no fee. Both minimum fees do apply to a very small non-zero sale, so net proceeds can
     * be negative there; that is the real economic result, not a defect.
     *
     * @throws IllegalArgumentException if either argument is negative, or the gross sale exceeds
     *                                  {@link #MAX_GROSS_SALE_COPPER}
     */
    public static SaleProceeds forSale(long grossUnitPriceCopper, long quantity) {
        if (grossUnitPriceCopper < 0) {
            throw new IllegalArgumentException(
                    "gross unit price must not be negative: " + grossUnitPriceCopper);
        }
        if (quantity < 0) {
            throw new IllegalArgumentException("sale quantity must not be negative: " + quantity);
        }

        long grossCopper = grossSaleValue(grossUnitPriceCopper, quantity);
        if (grossCopper == 0) {
            return new SaleProceeds(grossUnitPriceCopper, quantity, 0, 0, 0, 0, 0);
        }

        long listingFeeCopper = feeOn(grossCopper, LISTING_FEE_PERCENT);
        long exchangeFeeCopper = feeOn(grossCopper, EXCHANGE_FEE_PERCENT);
        long totalFeesCopper = listingFeeCopper + exchangeFeeCopper;

        return new SaleProceeds(
                grossUnitPriceCopper,
                quantity,
                grossCopper,
                listingFeeCopper,
                exchangeFeeCopper,
                totalFeesCopper,
                grossCopper - totalFeesCopper);
    }

    private static long grossSaleValue(long grossUnitPriceCopper, long quantity) {
        long grossCopper;
        try {
            grossCopper = Math.multiplyExact(grossUnitPriceCopper, quantity);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "gross sale exceeds the supported maximum of " + MAX_GROSS_SALE_COPPER
                            + " copper: " + grossUnitPriceCopper + " x " + quantity, overflow);
        }
        if (grossCopper > MAX_GROSS_SALE_COPPER) {
            throw new IllegalArgumentException(
                    "gross sale exceeds the supported maximum of " + MAX_GROSS_SALE_COPPER
                            + " copper: " + grossCopper);
        }
        return grossCopper;
    }

    /** One fee component of a non-zero sale: exact integer percent, rounded half away from zero. */
    private static long feeOn(long grossCopper, int feePercent) {
        long rounded = (grossCopper * feePercent + PERCENT_SCALE / 2) / PERCENT_SCALE;
        return Math.max(MINIMUM_FEE_COPPER, rounded);
    }

    /**
     * One priced Trading Post sale. The first two components echo the caller's stated sale
     * unchanged, so the gross market quote stays available next to the fee-adjusted result rather
     * than being replaced by it.
     *
     * @param grossUnitPriceCopper the market quote the caller selected, unchanged
     * @param quantity             items sold in this one transaction, unchanged
     * @param grossCopper          authoritative gross sale value, {@code unit price x quantity}
     * @param listingFeeCopper     5% listing fee on {@code grossCopper}, minimum 1 copper
     * @param exchangeFeeCopper    10% exchange fee on {@code grossCopper}, minimum 1 copper
     * @param totalFeesCopper      {@code listingFeeCopper + exchangeFeeCopper}
     * @param netProceedsCopper    {@code grossCopper - totalFeesCopper}; may be negative for a very
     *                             small sale where both minimum fees apply
     */
    public record SaleProceeds(
            long grossUnitPriceCopper,
            long quantity,
            long grossCopper,
            long listingFeeCopper,
            long exchangeFeeCopper,
            long totalFeesCopper,
            long netProceedsCopper) {}
}
