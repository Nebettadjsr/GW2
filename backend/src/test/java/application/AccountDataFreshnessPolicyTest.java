package application;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class AccountDataFreshnessPolicyTest {
    private final Instant now = Instant.parse("2026-10-03T12:00:00Z");
    private final Duration maxAge = Duration.ofMinutes(15);

    @Test void missingTimestampIsStale() { assertTrue(AccountDataFreshnessPolicy.isStale(Optional.empty(), now, maxAge)); }
    @Test void timestampAtAgeLimitIsFreshAndOlderTimestampIsStale() {
        assertFalse(AccountDataFreshnessPolicy.isStale(Optional.of(now.minus(maxAge)), now, maxAge));
        assertTrue(AccountDataFreshnessPolicy.isStale(Optional.of(now.minus(maxAge).minusNanos(1)), now, maxAge));
    }
    @Test void futureTimestampIsStale() { assertTrue(AccountDataFreshnessPolicy.isStale(Optional.of(now.plusSeconds(1)), now, maxAge)); }
}
