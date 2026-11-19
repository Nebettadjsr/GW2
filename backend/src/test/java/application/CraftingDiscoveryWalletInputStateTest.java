package application;

import model.AccountScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import repo.Db;
import repo.EnvConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #372: Discovery tracks the optional WALLET source only with wallet authority, using the real service
 * and real source-state markers in an isolated schema. Unpublished stays an explicit, non-blocking unknown,
 * an expired publication is stale, and without authority WALLET is never queried or requested.
 */
class CraftingDiscoveryWalletInputStateTest {

    private static final String A = "10000000-0000-0000-0000-00000000000a";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final String schema = "test_disc_wallet_" + UUID.randomUUID().toString().replace("-", "");
    private Connection admin;
    private String previous;

    @BeforeEach
    void setUp() throws Exception {
        admin = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"));
        try (Statement s = admin.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        Flyway.configure().dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        admin.setSchema(schema);
        try (Statement s = admin.createStatement()) {
            s.execute("INSERT INTO account_registry (account_id) VALUES ('" + A + "')");
        }
        for (String source : CraftingDiscoveryService.SOURCES) marker(source, NOW.minusSeconds(60));
        previous = System.getProperty(Db.TEST_SCHEMA_PROPERTY);
        System.setProperty(Db.TEST_SCHEMA_PROPERTY, schema);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (previous == null) System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
        else System.setProperty(Db.TEST_SCHEMA_PROPERTY, previous);
        try (Statement s = admin.createStatement()) {
            s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            admin.close();
        }
    }

    private void marker(String source, Instant at) throws Exception {
        try (Statement s = admin.createStatement()) {
            s.execute("INSERT INTO account_source_state (account_id, source, generation, completed_at) VALUES ('"
                    + A + "', '" + source + "', 1, '" + at + "')");
        }
    }

    private CraftingDiscoveryService service(boolean wallet) {
        return new CraftingDiscoveryService(null, null, null, null, (scope, clock) -> null)
                .withFreshness(CLOCK, Duration.ofHours(1)).forScope(AccountScope.of(A), wallet);
    }

    @Test
    void authorizedUnpublishedWalletIsRequestedButNeverPendingOrBlocking() throws Exception {
        var state = service(true).inputState();
        assertTrue(state.missing().isEmpty(), "item-only inputs stay usable");
        assertEquals(Set.of("WALLET"), state.optionalUnpublished());
        assertEquals(Set.of("WALLET"), state.needingRefresh());
    }

    @Test
    void authorizedStaleWalletIsIdentifiedForRefresh() throws Exception {
        marker("WALLET", NOW.minus(Duration.ofHours(3)));
        var state = service(true).inputState();
        assertEquals(Set.of("WALLET"), state.stale());
        assertTrue(state.missing().isEmpty());
        assertTrue(state.optionalUnpublished().isEmpty());
    }

    @Test
    void authorizedFreshWalletNeedsNothing() throws Exception {
        marker("WALLET", NOW.minusSeconds(30));
        assertTrue(service(true).inputState().needingRefresh().isEmpty());
    }

    @Test
    void withoutAuthorityWalletIsNeverTrackedWhateverItsMarker() throws Exception {
        assertTrue(service(false).inputState().needingRefresh().isEmpty(), "unpublished wallet not requested");
        marker("WALLET", NOW.minus(Duration.ofHours(3)));
        var state = service(false).inputState();
        assertFalse(state.stale().contains("WALLET"));
        assertTrue(state.needingRefresh().isEmpty());
        assertFalse(CraftingDiscoveryService.SOURCES.contains("WALLET"), "never a mandatory source");
    }

    @Test
    void missingRequiredSourceStillPendsIndependentlyOfWallet() throws Exception {
        try (Statement s = admin.createStatement()) {
            s.execute("DELETE FROM account_source_state WHERE source = 'BANK'");
        }
        var state = service(true).inputState();
        assertEquals(Set.of("BANK"), state.missing());
        assertEquals(Set.of("WALLET"), state.optionalUnpublished());
    }
}
