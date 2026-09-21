package repo.tp;

import craft.PriceQuote;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import repo.EnvConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer 2 PostgreSQL integration test (docs/TEST_STRATEGY.md §31.2) for
 * {@link TpPriceRepository#loadTpQuotes(Connection, Set)}: NULL-preservation of
 * {@code buy_unit_price}/{@code sell_unit_price} (docs/TEST_STRATEGY.md §6.11/§7 —
 * "unknown TP price != zero-cost purchase"), missing-item omission, and batched-lookup
 * keying, against a real Postgres engine.
 *
 * Runs against a disposable, uniquely-named schema on the local Postgres server, following the
 * same pattern as {@link repo.InventoryRepositoryOwnedInventoryTest}/{@link repo.RecipeRepositoryTest}
 * (STORY-TEST-002/STORY-TEST-004).
 */
class TpPriceRepositoryTest {

    private final String schema = "test_tp_" + UUID.randomUUID().toString().replace("-", "");
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

    @Test
    void loadTpQuotes_itemWithBothPrices_returnsBothAsNonNullValues() throws Exception {
        int itemId = 19721;

        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO tp_prices (item_id, buy_unit_price, sell_unit_price) " +
                    "VALUES (" + itemId + ", 100, 150)");
        }

        Map<Integer, PriceQuote> quotes =
                new TpPriceRepository().loadTpQuotes(con, Set.of(itemId));

        PriceQuote q = quotes.get(itemId);
        assertEquals(100, q.buyUnit);
        assertEquals(150, q.sellUnit);
    }

    @Test
    void loadTpQuotes_itemWithBothPricesNull_returnsNullNotZeroForBothFields() throws Exception {
        int itemId = 19722;

        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO tp_prices (item_id, buy_unit_price, sell_unit_price) " +
                    "VALUES (" + itemId + ", NULL, NULL)");
        }

        Map<Integer, PriceQuote> quotes =
                new TpPriceRepository().loadTpQuotes(con, Set.of(itemId));

        PriceQuote q = quotes.get(itemId);
        assertNull(q.buyUnit);
        assertNull(q.sellUnit);
    }

    @Test
    void loadTpQuotes_itemWithOnlySellPriceNull_returnsNullOnlyForSellField() throws Exception {
        int itemId = 19723;

        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO tp_prices (item_id, buy_unit_price, sell_unit_price) " +
                    "VALUES (" + itemId + ", 200, NULL)");
        }

        Map<Integer, PriceQuote> quotes =
                new TpPriceRepository().loadTpQuotes(con, Set.of(itemId));

        PriceQuote q = quotes.get(itemId);
        assertEquals(200, q.buyUnit);
        assertNull(q.sellUnit);
    }

    @Test
    void loadTpQuotes_itemNotInTpPrices_isAbsentFromResultingMap() throws Exception {
        int itemId = 19724;

        Map<Integer, PriceQuote> quotes =
                new TpPriceRepository().loadTpQuotes(con, Set.of(itemId));

        assertFalse(quotes.containsKey(itemId));
        assertTrue(quotes.isEmpty());
    }

    @Test
    void loadTpQuotes_batchedLookup_returnsOnlyRequestedAndPresentRowsCorrectlyKeyed() throws Exception {
        int itemA = 1;
        int itemB = 2;
        int itemC = 3; // present in table but not requested
        int itemD = 4; // requested but not present in table

        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO tp_prices (item_id, buy_unit_price, sell_unit_price) VALUES " +
                    "(" + itemA + ", 10, 20), " +
                    "(" + itemB + ", 30, 40), " +
                    "(" + itemC + ", 999, 999)");
        }

        Map<Integer, PriceQuote> quotes =
                new TpPriceRepository().loadTpQuotes(con, Set.of(itemA, itemB, itemD));

        assertEquals(2, quotes.size());

        assertEquals(10, quotes.get(itemA).buyUnit);
        assertEquals(20, quotes.get(itemA).sellUnit);

        assertEquals(30, quotes.get(itemB).buyUnit);
        assertEquals(40, quotes.get(itemB).sellUnit);

        assertFalse(quotes.containsKey(itemC));
        assertFalse(quotes.containsKey(itemD));
    }

    @Test
    void loadTpQuotes_emptyItemIdSet_returnsEmptyMapWithoutQueryingDatabase() throws Exception {
        Map<Integer, PriceQuote> quotes =
                new TpPriceRepository().loadTpQuotes(con, Set.of());

        assertTrue(quotes.isEmpty());
    }
}
