package repo.tp;

import domain.tp.TpPriceFreshness;

/** The SQL form of {@link TpPriceFreshness}: the same ten-minute rule, written for the {@code tp_prices} table. */
public final class TpPriceFreshnessSql {
    /** True for a missing row, a missing observation time or one older than {@link TpPriceFreshness#TTL}. */
    public static final String STALE_PREDICATE = "tp.item_id IS NULL "
            + "OR tp.fetched_at IS NULL "
            + "OR tp.fetched_at < (now() - interval '" + TpPriceFreshness.TTL.toMinutes() + " minutes')";

    private TpPriceFreshnessSql() {}
}
