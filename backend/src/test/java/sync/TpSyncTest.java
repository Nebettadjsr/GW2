package sync;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import repo.EnvConfig;
import util.TpPrice;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer 2 PostgreSQL integration test (docs/TEST_STRATEGY.md §31.2) for {@code TpSync}'s
 * {@code tp_prices} upsert write path: NULL-preservation for quotes without market data
 * (docs/TEST_STRATEGY.md §6.11/§7 - "unknown TP price != zero-cost purchase") and
 * upsert-on-conflict for repeated syncs of the same item, against a real Postgres engine.
 *
 * Runs against a disposable, uniquely-named schema on the local Postgres server, following the
 * same pattern as {@code CharacterSyncTest} (STORY-TEST-003) and
 * {@code repo.tp.TpPriceRepositoryTest} (STORY-TEST-005). Seeded rows are already-constructed
 * {@link TpPrice} values passed directly to {@code TpSync.upsertTpPrices} - no live GW2 API call
 * is made, and {@code syncTpPrices}'s fetch path is never exercised.
 */
class TpSyncTest {

    private final String schema = "test_tpsync_" + UUID.randomUUID().toString().replace("-", "");
    private Connection con;

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        con = DriverManager.getConnection(
                EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD"));

        try (Statement st = con.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
        }
        con.setSchema(schema);

        try (Statement st = con.createStatement()) {
            st.execute("""
                CREATE TABLE tp_prices (
                    item_id         INTEGER PRIMARY KEY,
                    buy_quantity    BIGINT,
                    buy_unit_price  INTEGER,
                    sell_quantity   BIGINT,
                    sell_unit_price INTEGER,
                    fetched_at      TIMESTAMPTZ
                )
                """);
        }
    }

    @AfterEach
    void dropIsolatedSchema() throws Exception {
        if (con == null) return;
        try (Statement st = con.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            con.close();
        }
    }

    // Postgres timestamptz has microsecond precision; truncate so round-tripped values compare equal.
    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void upsertTpPrices_quoteWithMarketData_writesAllFourColumnsAsGivenNonNullValues() throws Exception {
        int itemId = 19721;
        TpPrice quote = new TpPrice(itemId, 50L, 100, 30L, 150);

        TpSync.upsertTpPrices(con, List.of(quote), ts(Instant.now()));

        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT buy_quantity, buy_unit_price, sell_quantity, sell_unit_price " +
                             "FROM tp_prices WHERE item_id = " + itemId)) {
            assertTrue(rs.next());
            assertEquals(50L, rs.getLong("buy_quantity"));
            assertEquals(100, rs.getInt("buy_unit_price"));
            assertEquals(30L, rs.getLong("sell_quantity"));
            assertEquals(150, rs.getInt("sell_unit_price"));
        }
    }

    @Test
    void upsertTpPrices_quoteWithoutMarketData_writesAllFourColumnsAsNullNotZero() throws Exception {
        int itemId = 19722;
        TpPrice quote = TpPrice.noData(itemId);

        TpSync.upsertTpPrices(con, List.of(quote), ts(Instant.now()));

        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT buy_quantity, buy_unit_price, sell_quantity, sell_unit_price " +
                             "FROM tp_prices WHERE item_id = " + itemId)) {
            assertTrue(rs.next());
            assertNull(rs.getObject("buy_quantity"));
            assertNull(rs.getObject("buy_unit_price"));
            assertNull(rs.getObject("sell_quantity"));
            assertNull(rs.getObject("sell_unit_price"));
        }
    }

    @Test
    void upsertTpPrices_reRunForSameItemId_updatesExistingRowInPlaceRatherThanInserting() throws Exception {
        int itemId = 19723;
        Instant t1 = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant t2 = Instant.now();

        TpSync.upsertTpPrices(con, List.of(new TpPrice(itemId, 10L, 20, 30L, 40)), ts(t1));
        TpSync.upsertTpPrices(con, List.of(new TpPrice(itemId, 11L, 21, 31L, 41)), ts(t2));

        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*), MAX(buy_unit_price), MAX(fetched_at) " +
                             "FROM tp_prices WHERE item_id = " + itemId)) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals(21, rs.getInt(2));
            assertEquals(ts(t2), rs.getTimestamp(3));
        }
    }
}
