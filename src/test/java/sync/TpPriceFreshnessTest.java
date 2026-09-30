package sync;

import org.junit.jupiter.api.Test;
import sync.tp.TpPriceFreshness;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TpPriceFreshnessTest {
    private static final Instant NOW = Instant.parse("2026-09-30T12:00:00Z");

    @Test
    void freshQuotesAreReusedAtAndBeforeTheTenMinuteBoundary() {
        assertTrue(TpPriceFreshness.isFresh(NOW.minusSeconds(600), NOW));
        assertTrue(TpPriceFreshness.isFresh(NOW.minusSeconds(599), NOW));
    }

    @Test
    void missingAndOlderThanTenMinuteQuotesAreStale() {
        assertFalse(TpPriceFreshness.isFresh(null, NOW));
        assertFalse(TpPriceFreshness.isFresh(NOW.minusSeconds(601), NOW));
    }
}
