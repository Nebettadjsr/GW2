package repo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer 2 PostgreSQL integration test (docs/TEST_STRATEGY.md §31.2) for the bank read relocated
 * out of {@code BankView} by STORY-APP-009: slot ordering, the {@code items} LEFT JOIN's
 * icon/rarity mapping, empty slots (null item/count) and the empty-table case must behave exactly
 * as the view's own inline query did. Also verifies that the no-argument entry point goes through
 * the shared {@link Db#open()} configuration path rather than any view-local credentials.
 *
 * <p>Runs against a real local PostgreSQL server using a disposable, uniquely-named schema created
 * in {@link #createIsolatedSchema()} and dropped in {@link #dropIsolatedSchema()}, so the
 * developer's normal database is never read from or written to (same pattern as
 * {@link InventoryRepositoryOwnedInventoryTest}).
 */
class BankRepositoryTest {

    private final String schema = "test_bank_" + UUID.randomUUID().toString().replace("-", "");
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
                CREATE TABLE account_bank (
                    slot        INTEGER PRIMARY KEY,
                    item_id     INTEGER,
                    count       INTEGER,
                    binding     TEXT,
                    bound_to    TEXT,
                    charges     INTEGER,
                    stats_id    INTEGER,
                    stats_attrs JSONB,
                    fetched_at  TIMESTAMPTZ
                )
                """);
            st.execute("""
                CREATE TABLE items (
                    item_id      INTEGER PRIMARY KEY,
                    name         TEXT,
                    type         TEXT,
                    rarity       TEXT,
                    vendor_value INTEGER,
                    icon_path    TEXT,
                    fetched_at   TIMESTAMPTZ
                )
                """);
        }
    }

    @AfterEach
    void dropIsolatedSchema() throws Exception {
        System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
        if (con == null) return;
        try (Statement st = con.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            con.close();
        }
    }

    @Test
    void loadsEverySlotInSlotOrderWithJoinedIconAndRarity() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO items (item_id, name, rarity, icon_path) VALUES (19721, 'Glob of Ectoplasm', 'Rare', 'C:\\icons\\19721.png')");
            st.execute("INSERT INTO items (item_id, name, rarity, icon_path) VALUES (24277, 'Pile of Crystalline Dust', 'Basic', 'C:\\icons\\24277.png')");
            // Inserted out of slot order on purpose: ORDER BY slot must decide the result order.
            st.execute("INSERT INTO account_bank (slot, item_id, count) VALUES (2, 24277, 250)");
            st.execute("INSERT INTO account_bank (slot, item_id, count) VALUES (0, 19721, 5)");
        }

        List<BankRepository.BankSlotRow> slots = new BankRepository().loadBankSlots(con);

        assertEquals(2, slots.size());
        assertEquals(new BankRepository.BankSlotRow(0, 19721, 5, "C:\\icons\\19721.png", "Rare"), slots.get(0));
        assertEquals(new BankRepository.BankSlotRow(2, 24277, 250, "C:\\icons\\24277.png", "Basic"), slots.get(1));
    }

    @Test
    void emptySlotKeepsNullItemAndCount_andUnknownItemKeepsNullIconAndRarity() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO account_bank (slot, item_id, count) VALUES (0, NULL, NULL)");
            st.execute("INSERT INTO account_bank (slot, item_id, count) VALUES (1, 99999, 3)");
        }

        List<BankRepository.BankSlotRow> slots = new BankRepository().loadBankSlots(con);

        assertEquals(2, slots.size());

        BankRepository.BankSlotRow empty = slots.get(0);
        assertNull(empty.itemId());
        assertNull(empty.count());
        assertNull(empty.iconPath());

        BankRepository.BankSlotRow unjoined = slots.get(1);
        assertEquals(99999, unjoined.itemId());
        assertEquals(3, unjoined.count());
        assertNull(unjoined.iconPath());
        assertNull(unjoined.rarity());
    }

    @Test
    void emptyBankTableReturnsAnEmptyList() throws Exception {
        assertTrue(new BankRepository().loadBankSlots(con).isEmpty());
    }

    @Test
    void noArgLoadUsesTheSharedDbConnectionConfiguration() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO account_bank (slot, item_id, count) VALUES (7, 19721, 1)");
        }

        // Db.open() reads repo.AppConfig/EnvConfig, then honors this property to reach the
        // disposable schema - the same shared path every other repository uses.
        System.setProperty(Db.TEST_SCHEMA_PROPERTY, schema);

        List<BankRepository.BankSlotRow> slots = new BankRepository().loadBankSlots();

        assertEquals(1, slots.size());
        assertEquals(7, slots.get(0).slot());
        assertEquals(19721, slots.get(0).itemId());
    }
}
