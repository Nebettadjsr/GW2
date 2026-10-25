package tradingpost;

/**
 * The decided application profitability fee (DOMAIN_SPEC.md §25, resolved
 * {@code agent/user-decisions/UD-011-trading-post-sale-quantity-basis.md}): Trading Post fees are
 * 15% of the corresponding gross Sell Value, and they affect profit only. Pure domain arithmetic -
 * no persistence, transport, JavaFX or GW2 API type is involved, and nothing here reads a price,
 * selects a sale mode or knows which quantity basis its caller holds.
 *
 * <p><b>This is the profitability model, not a transaction model.</b> UD-011 explicitly waives
 * copper-level differences caused only by transaction rounding, minimum fees or sale grouping, so
 * one combined 15% is applied to whichever gross sell value the caller states.
 * {@link TradingPostSaleCalculator}'s separately rounded 5%/10% components with their 1-copper
 * minimums describe an actual single Trading Post transaction and must <em>not</em> be substituted
 * here (§25.1): on a 3-copper sale that calculator charges 2 copper of minimum fees, while this
 * policy charges none.
 *
 * <p><b>Applied exactly once.</b> Callers deduct {@link #feeOn} (or take {@link #netOfFee}) from a
 * gross sell value while computing a profit, and leave every displayed price, output revenue and
 * total sell value gross (§25, §2.1.1). Because the fee is a plain percentage of the value handed
 * in, a caller that applies it to a per-unit value and multiplies by a count charges the same rate
 * as one that applies it to the total; only the rounding remainder differs, which is exactly the
 * difference UD-011 waives.
 */
public final class TradingPostFeePolicy {

    /** DOMAIN_SPEC.md §25: the decided fee rate, as a percentage of the gross sell value. */
    public static final int PROFIT_FEE_PERCENT = 15;

    private static final int PERCENT_SCALE = 100;

    /** Largest gross sell value the exact integer arithmetic below evaluates without overflow. */
    public static final long MAX_SELL_VALUE_COPPER =
            (Long.MAX_VALUE - PERCENT_SCALE / 2) / PROFIT_FEE_PERCENT;

    private TradingPostFeePolicy() {}

    /**
     * The fee deducted when calculating profit from {@code grossSellValueCopper}: 15% of it, in
     * exact integer copper, rounded half away from zero. A zero sell value carries no fee - there is
     * no minimum, because §25's model charges a rate rather than a transaction.
     *
     * @throws IllegalArgumentException if the sell value is negative or above
     *                                  {@link #MAX_SELL_VALUE_COPPER}; a gross sell value is never
     *                                  either of those, so it states a caller defect rather than
     *                                  quietly returning a fee for it
     */
    public static long feeOn(long grossSellValueCopper) {
        if (grossSellValueCopper < 0) {
            throw new IllegalArgumentException(
                    "gross sell value must not be negative: " + grossSellValueCopper);
        }
        if (grossSellValueCopper > MAX_SELL_VALUE_COPPER) {
            throw new IllegalArgumentException(
                    "gross sell value exceeds the supported maximum of " + MAX_SELL_VALUE_COPPER
                            + " copper: " + grossSellValueCopper);
        }
        return (grossSellValueCopper * PROFIT_FEE_PERCENT + PERCENT_SCALE / 2) / PERCENT_SCALE;
    }

    /**
     * {@code grossSellValueCopper} less {@link #feeOn(long)} - the part of a sale that reaches a
     * profit calculation. The gross value itself is unchanged and stays the one that is displayed.
     */
    public static long netOfFee(long grossSellValueCopper) {
        return grossSellValueCopper - feeOn(grossSellValueCopper);
    }
}
