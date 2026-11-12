package repo;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real-database contract for the owner aggregates, in an isolated per-test schema. */
class SharedDataSummaryRepositoryTest {
    private final String schema = "test_shared_summary_" + UUID.randomUUID().toString().replace("-", "");
    private final SharedDataSummaryRepository repo = new SharedDataSummaryRepository();
    private Connection con;

    @BeforeEach
    void setUp() throws Exception {
        con = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD"));
        exec("CREATE SCHEMA " + schema);
        Flyway.configure().dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        exec("SET search_path TO " + schema);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (con == null) return;
        try {
            exec("SET search_path TO public");
            exec("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally { con.close(); }
    }

    private void exec(String sql) throws SQLException {
        try (Statement s = con.createStatement()) { s.execute(sql); }
    }

    @Test
    void emptyTablesReportZeroRowsAndNoTimestamps() throws Exception {
        for (var c : new SharedDataSummaryRepository.Coverage[] {repo.items(con), repo.recipes(con), repo.tradingPostPrices(con)}) {
            assertEquals(new SharedDataSummaryRepository.Coverage(0, 0, 0, null, null), c);
        }
        assertEquals(new SharedDataSummaryRepository.AccountUsage(0, 0),
                repo.accounts(con, Instant.parse("2026-11-10T12:00:00Z")));
    }

    @Test
    void itemPlaceholdersAreCountedButNeverTimestamped() throws Exception {
        exec("INSERT INTO items (item_id, fetched_at) VALUES (1, NULL), (2, NULL), "
                + "(3, '2026-11-01T10:00:00Z'), (4, '2026-11-05T10:00:00Z'), (5, '2026-11-03T10:00:00Z')");
        var c = repo.items(con);
        assertEquals(5, c.storedRows());
        assertEquals(3, c.timestampedRows());
        assertEquals(2, c.unknownTimestampRows());
        assertEquals(Instant.parse("2026-11-01T10:00:00Z"), c.oldestFetchedAt());
        assertEquals(Instant.parse("2026-11-05T10:00:00Z"), c.newestFetchedAt());
    }

    @Test
    void allUntimestampedRecipesKeepTimestampsUnknown() throws Exception {
        exec("INSERT INTO recipes (recipe_id) VALUES (1), (2)");
        assertEquals(new SharedDataSummaryRepository.Coverage(2, 0, 2, null, null), repo.recipes(con));
        exec("INSERT INTO recipes (recipe_id, fetched_at) VALUES (3, '2026-11-02T00:00:00Z')");
        var c = repo.recipes(con);
        assertEquals(3, c.storedRows());
        assertEquals(1, c.timestampedRows());
        assertEquals(c.oldestFetchedAt(), c.newestFetchedAt());
    }

    @Test
    void priceRowsWithAbsentSidesAreStoredEntriesNotOffers() throws Exception {
        exec("INSERT INTO tp_prices (item_id, buy_unit_price, sell_unit_price, fetched_at) VALUES "
                + "(1, 10, 20, '2026-11-02T00:00:00Z'), (2, NULL, NULL, '2026-11-04T00:00:00Z'), (3, NULL, NULL, NULL)");
        var c = repo.tradingPostPrices(con);
        assertEquals(3, c.storedRows());
        assertEquals(2, c.timestampedRows());
        assertEquals(Instant.parse("2026-11-02T00:00:00Z"), c.oldestFetchedAt());
        assertEquals(Instant.parse("2026-11-04T00:00:00Z"), c.newestFetchedAt());
    }

    @Test
    void activeWindowIsInclusiveAtExactlySixtyMinutes() throws Exception {
        Instant now = Instant.parse("2026-11-10T12:00:00Z");
        exec("INSERT INTO account_registry (account_id, last_seen_at, last_successful_sync_at) VALUES "
                + "('00000000-0000-0000-0000-000000000001', NULL, NULL),"
                + "('00000000-0000-0000-0000-000000000002', '2026-11-10T10:59:59Z', NULL),"
                + "('00000000-0000-0000-0000-000000000003', '2026-11-10T11:00:00Z', NULL),"
                + "('00000000-0000-0000-0000-000000000004', '2026-11-10T11:00:01Z', NULL),"
                + "('00000000-0000-0000-0000-000000000005', '2026-11-10T12:00:00Z', NULL),"
                // sync-only activity does not qualify
                + "('00000000-0000-0000-0000-000000000006', NULL, '2026-11-10T11:59:00Z')");
        var u = repo.accounts(con, now);
        assertEquals(6, u.totalAccounts());
        assertEquals(3, u.activeLast60Minutes());
    }

    @Test
    void queryFailureSurfacesAsSqlException() throws Exception {
        exec("DROP TABLE tp_prices");
        assertThrows(SQLException.class, () -> repo.tradingPostPrices(con));
    }
}
