package domain.tp;

import java.time.Duration;
import java.time.Instant;

/** Shared freshness rule used by every feature's relevant-ID collector. */
public final class TpPriceFreshness {
    public static final Duration TTL = Duration.ofMinutes(10);

    private TpPriceFreshness() {}

    public static boolean isFresh(Instant fetchedAt, Instant now) {
        return fetchedAt != null && !fetchedAt.isBefore(now.minus(TTL));
    }
}
