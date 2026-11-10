package application;

import org.junit.jupiter.api.Test;
import repo.SharedDataSummaryRepository;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class OwnerCatalogSummaryServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    @Test
    void failedQueriesAreUnavailableNeverZero() {
        var repo = new SharedDataSummaryRepository() {
            @Override public Coverage items(java.sql.Connection c) { return new Coverage(2, 1, 1, NOW, NOW); }
            @Override public Coverage recipes(java.sql.Connection c) throws SQLException { throw new SQLException("boom"); }
            @Override public Coverage tradingPostPrices(java.sql.Connection c) { throw new IllegalStateException("x"); }
            @Override public AccountUsage accounts(java.sql.Connection c, Instant at) throws SQLException { throw new SQLException("boom"); }
        };
        var summary = new OwnerCatalogSummaryService(() -> (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {java.sql.Connection.class}, (p, m, a) -> null),
                repo, Clock.fixed(NOW, ZoneOffset.UTC)).read();
        assertEquals("AVAILABLE", summary.items().state());
        assertEquals(2L, summary.items().storedRows());
        assertEquals("UNAVAILABLE", summary.recipes().state());
        assertNull(summary.recipes().storedRows());
        assertEquals("UNAVAILABLE", summary.tradingPostPrices().state());
        assertEquals("UNAVAILABLE", summary.accounts().state());
        assertNull(summary.accounts().totalAccounts());
    }

    @Test
    void accountsAreEvaluatedAtTheClockInstant() {
        var seen = new java.util.ArrayList<Instant>();
        var repo = new SharedDataSummaryRepository() {
            @Override public Coverage items(java.sql.Connection c) { return new Coverage(0, 0, 0, null, null); }
            @Override public Coverage recipes(java.sql.Connection c) { return items(c); }
            @Override public Coverage tradingPostPrices(java.sql.Connection c) { return items(c); }
            @Override public AccountUsage accounts(java.sql.Connection c, Instant at) { seen.add(at); return new AccountUsage(4, 1); }
        };
        var s = new OwnerCatalogSummaryService(() -> null, repo, Clock.fixed(NOW, ZoneOffset.UTC)).read();
        assertEquals(java.util.List.of(NOW), seen);
        assertEquals(4L, s.accounts().totalAccounts());
        assertEquals(1L, s.accounts().activeLast60Minutes());
    }
}
