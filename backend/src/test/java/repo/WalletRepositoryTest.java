package repo;

import model.AccountScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Issue #372: wallet reads are account-isolated and stale/never-published wallets stay unknown. */
class WalletRepositoryTest {

    private static final String A_ID = "10000000-0000-0000-0000-00000000000a";
    private static final String B_ID = "10000000-0000-0000-0000-00000000000b";
    private final String schema = "test_wallet_" + UUID.randomUUID().toString().replace("-", "");
    private Connection con;

    @BeforeEach
    void setUp() throws Exception {
        con = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"));
        try (Statement st = con.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
        }
        Flyway.configure().dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        con.setSchema(schema);
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO account_registry (account_id) VALUES ('" + A_ID + "'), ('" + B_ID + "')");
            st.execute("INSERT INTO account_wallet (account_id, currency_id, value) VALUES ('" + A_ID + "', 61, 309),"
                    + " ('" + B_ID + "', 61, 5)");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            con.close();
        }
    }

    private void marker(String account, Instant at) throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO account_source_state (account_id, source, generation, completed_at) VALUES ('"
                    + account + "', 'WALLET', 1, '" + at + "')");
        }
    }

    @Test
    void noMarkerMeansUnknownNotZero() throws Exception {
        assertTrue(new WalletRepository().load(con, AccountScope.of(A_ID)).isEmpty());
    }

    @Test
    void freshMarkerLoadsOnlyThatAccountsBalances() throws Exception {
        marker(A_ID, Instant.now());
        assertEquals(Map.of(61, 309), new WalletRepository().load(con, AccountScope.of(A_ID)).orElseThrow());
        assertTrue(new WalletRepository().load(con, AccountScope.of(B_ID)).isEmpty(), "B has no marker of its own");
    }

    @Test
    void callWithoutAMaxAgeAppliesNoLimit() throws Exception {
        marker(A_ID, Instant.now().minus(java.time.Duration.ofDays(2)));
        assertEquals(Map.of(61, 309), new WalletRepository().load(con, AccountScope.of(A_ID)).orElseThrow());
    }

    private static final java.time.Duration MAX_AGE = java.time.Duration.ofMinutes(30);

    @Test
    void expiredWalletIsUnknownAtTheInjectedClockButBoundaryAgeIsStillFresh() throws Exception {
        Instant published = Instant.parse("2026-11-19T10:00:00Z");
        marker(A_ID, published);
        WalletRepository repo = new WalletRepository();
        AccountScope a = AccountScope.of(A_ID);
        assertEquals(Map.of(61, 309), repo.load(con, a, published.plus(MAX_AGE), MAX_AGE).orElseThrow(),
                "exactly maxAge old is not yet stale");
        assertTrue(repo.load(con, a, published.plus(MAX_AGE).plusSeconds(1), MAX_AGE).isEmpty(),
                "past maxAge the balance is unknown, never the old 309 Notes");
    }

    @Test
    void freshPublishedEmptyWalletIsKnownZeroButNoPublicationIsUnknown() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("DELETE FROM account_wallet WHERE account_id='" + A_ID + "'");
        }
        Instant now = Instant.parse("2026-11-19T10:00:00Z");
        marker(A_ID, now);
        WalletRepository repo = new WalletRepository();
        assertEquals(Map.of(), repo.load(con, AccountScope.of(A_ID), now, MAX_AGE).orElseThrow());
        assertTrue(repo.load(con, AccountScope.of(B_ID), now, MAX_AGE).isEmpty());
    }
}
