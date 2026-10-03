package application;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Pure age rule shared by source freshness checks; absent, future and over-age stamps are stale. */
public final class AccountDataFreshnessPolicy {
    private AccountDataFreshnessPolicy() {}
    public static boolean isStale(Optional<Instant> fetchedAt, Instant now, Duration maxAge) {
        if (maxAge.isNegative() || maxAge.isZero()) throw new IllegalArgumentException("maxAge must be positive");
        return fetchedAt.isEmpty() || fetchedAt.get().isAfter(now)
                || Duration.between(fetchedAt.get(), now).compareTo(maxAge) > 0;
    }
}
