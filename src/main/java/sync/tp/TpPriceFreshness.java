package sync.tp;

import java.time.Duration;
import java.time.Instant;

/** Shared freshness rule used by every feature's relevant-ID collector. */
public final class TpPriceFreshness {
    public static final Duration TTL = Duration.ofMinutes(10);
    public static final String STALE_SQL_PREDICATE = "tp.item_id IS NULL "
            + "OR tp.fetched_at IS NULL "
            + "OR tp.fetched_at < (now() - interval '" + TTL.toMinutes() + " minutes')";

    private TpPriceFreshness() {}

    public static boolean isFresh(Instant fetchedAt, Instant now) {
        return fetchedAt != null && !fetchedAt.isBefore(now.minus(TTL));
    }
}
