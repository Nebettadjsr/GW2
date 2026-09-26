package repo.tp;

import craft.MaterialTradeability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import repo.EnvConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer 2 PostgreSQL integration test (docs/TEST_STRATEGY.md §31.2) for
 * {@link TpTradeableItemRepository#loadTradeability(Connection, Set)}: that the Trading Post
 * tradeability <em>classification</em> comes from {@code tp_tradeable_items} alone
 * (DOMAIN_SPEC.md §2.1.1, STORY-DOM-021), is reported per requested item, and is never read out of
 * price data.
 *
 * <p>Runs against a disposable, uniquely-named schema on the local Postgres server, following
 * {@link TpPriceRepositoryTest}'s pattern.
 */
class TpTradeableItemRepositoryTest {

    private final String schema = "test_tradeable_" + UUID.randomUUID().toString().replace("-", "");
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
                CREATE TABLE tp_tradeable_items (
                    item_id    INTEGER PRIMARY KEY,
                    fetched_at TIMESTAMPTZ
                )
                """);
            // Present only so a test can prove the classification does not consult it.
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

    private void listAsTradeable(int... itemIds) throws Exception {
        try (Statement st = con.createStatement()) {
            for (int itemId : itemIds) {
                st.execute("INSERT INTO tp_tradeable_items (item_id, fetched_at) "
                        + "VALUES (" + itemId + ", now())");
            }
        }
    }

    @Test
    void loadTradeability_itemListedByTheTradingPost_isNotClassifiedAsNonTradeable() throws Exception {
        listAsTradeable(19_721);

        MaterialTradeability tradeability =
                new TpTradeableItemRepository().loadTradeability(con, Set.of(19_721));

        assertFalse(tradeability.isNonTradeable(19_721));
        assertTrue(tradeability.nonTradeableItemIds().isEmpty());
    }

    @Test
    void loadTradeability_itemAbsentFromTheListing_isClassifiedAsNonTradeable() throws Exception {
        MaterialTradeability tradeability =
                new TpTradeableItemRepository().loadTradeability(con, Set.of(19_722));

        assertTrue(tradeability.isNonTradeable(19_722));
        assertEquals(Set.of(19_722), tradeability.nonTradeableItemIds());
    }

    @Test
    void loadTradeability_classifiesEachRequestedItemSeparatelyAndNothingElse() throws Exception {
        listAsTradeable(1, 3);

        MaterialTradeability tradeability =
                new TpTradeableItemRepository().loadTradeability(con, Set.of(1, 2));

        // Item 3 is listed but was not asked about, so it is not part of the answer either way.
        assertEquals(Set.of(2), tradeability.nonTradeableItemIds());
        assertFalse(tradeability.isNonTradeable(1));
        assertFalse(tradeability.isNonTradeable(3));
    }

    @Test
    void loadTradeability_ignoresPriceDataEntirely() throws Exception {
        // A listed item with no price row at all, and an unlisted one with a full quote: the
        // classification must follow the listing, not the prices (DOMAIN_SPEC.md §2.1.1, §21).
        listAsTradeable(50);
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO tp_prices (item_id, buy_unit_price, sell_unit_price) "
                    + "VALUES (51, 100, 150)");
        }

        MaterialTradeability tradeability =
                new TpTradeableItemRepository().loadTradeability(con, Set.of(50, 51));

        assertFalse(tradeability.isNonTradeable(50), "a listed item without a quote stays tradeable");
        assertTrue(tradeability.isNonTradeable(51), "a quoted item that is not listed is non-tradeable");
    }

    @Test
    void loadTradeability_emptyRequest_asksNothingAndClassifiesNothing() throws Exception {
        MaterialTradeability tradeability =
                new TpTradeableItemRepository().loadTradeability(con, Set.of());

        assertSame(MaterialTradeability.noneKnown(), tradeability);
        assertTrue(tradeability.nonTradeableItemIds().isEmpty());
    }
}
