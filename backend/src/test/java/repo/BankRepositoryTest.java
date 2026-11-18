package repo;

import model.AccountScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static support.fixture.CachedItemNameFixtures.itemName;
import static support.fixture.CachedItemNameFixtures.withItemName;
import static support.fixture.BankBindingFixtures.withBinding;

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

    private static final AccountScope A = AccountScope.of("10000000-0000-0000-0000-00000000000a");
    private static final AccountScope B = AccountScope.of("20000000-0000-0000-0000-00000000000b");

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
        Flyway.configure()
                .dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();
        con.setSchema(schema);
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO account_registry (account_id) VALUES ('" + A.accountId() + "'), ('"
                    + B.accountId() + "')");
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
            // 19721 carries retained upstream icon metadata; 24277 has none, so the batch read is
            // proven to supply a present and an absent icon source without a second query.
            st.execute("INSERT INTO items (item_id, name, rarity, icon_url) VALUES (19721, 'Glob of Ectoplasm', 'Rare', 'https://render.guildwars2.com/file/ABCD/1.png')");
            st.execute("INSERT INTO items (item_id, name, rarity) VALUES (24277, 'Pile of Crystalline Dust', 'Basic')");
            // Inserted out of slot order on purpose: ORDER BY slot must decide the result order.
            st.execute("INSERT INTO account_bank (account_id, slot, item_id, count) VALUES ('" + A.accountId() + "', 2, 24277, 250)");
            st.execute("INSERT INTO account_bank (account_id, slot, item_id, count) VALUES ('" + A.accountId() + "', 0, 19721, 5)");
        }

        List<BankRepository.BankSlotRow> slots = new BankRepository().loadBankSlots(con, A);

        assertEquals(2, slots.size());
        assertEquals(withItemName(new BankRepository.BankSlotRow(
                0, 19721, 5, "https://render.guildwars2.com/file/ABCD/1.png", "Rare"), "Glob of Ectoplasm"),
                slots.get(0));
        assertEquals(withItemName(new BankRepository.BankSlotRow(2, 24277, 250, null, "Basic"),
                "Pile of Crystalline Dust"), slots.get(1));
    }

    @Test
    void emptySlotKeepsNullItemAndCount_andUnknownItemKeepsNullIconAndRarity() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO account_bank (account_id, slot, item_id, count) VALUES ('" + A.accountId() + "', 0, NULL, NULL)");
            st.execute("INSERT INTO account_bank (account_id, slot, item_id, count) VALUES ('" + A.accountId() + "', 1, 99999, 3)");
        }

        List<BankRepository.BankSlotRow> slots = new BankRepository().loadBankSlots(con, A);

        assertEquals(2, slots.size());

        BankRepository.BankSlotRow empty = slots.get(0);
        assertNull(empty.itemId());
        assertNull(empty.count());
        assertNull(itemName(empty));

        BankRepository.BankSlotRow unjoined = slots.get(1);
        assertEquals(99999, unjoined.itemId());
        assertEquals(3, unjoined.count());
        assertNull(unjoined.rarity());
        assertNull(itemName(unjoined));
    }

    @Test
    void emptyBankReturnsAnEmptyList() throws Exception {
        assertTrue(new BankRepository().loadBankSlots(con, A).isEmpty());
    }

    @Test
    void batchRowsKeepEachSameItemStacksRawBindingAndOwnerIncludingNulls() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO account_bank (account_id, slot, item_id, count, binding, bound_to) VALUES"
                    + " ('" + A.accountId() + "', 9, 200, 250, 'Character', 'Bran'), ('" + A.accountId() + "', 4, 200, 250, 'Character', 'Aria'),"
                    + " ('" + A.accountId() + "', 0, 200, 250, 'Account', NULL), ('" + A.accountId() + "', 30, 200, 250, NULL, NULL),"
                    + " ('" + A.accountId() + "', 31, 99999, 1, 'Unrecognized binding', ''), ('" + A.accountId() + "', 32, NULL, NULL, NULL, NULL),"
                    + " ('" + B.accountId() + "', 0, 200, 999, 'Character', 'Other')");
        }

        List<BankRepository.BankSlotRow> slots = new BankRepository().loadBankSlots(con, A);

        assertEquals(List.of(
                withBinding(new BankRepository.BankSlotRow(0, 200, 250, null, null), "Account", null),
                withBinding(new BankRepository.BankSlotRow(4, 200, 250, null, null), "Character", "Aria"),
                withBinding(new BankRepository.BankSlotRow(9, 200, 250, null, null), "Character", "Bran"),
                withBinding(new BankRepository.BankSlotRow(30, 200, 250, null, null), null, null),
                withBinding(new BankRepository.BankSlotRow(31, 99999, 1, null, null), "Unrecognized binding", ""),
                withBinding(new BankRepository.BankSlotRow(32, null, null, null, null), null, null)), slots);
    }

    @Test
    void noArgLoadUsesTheSharedDbConnectionConfiguration() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO account_bank (account_id, slot, item_id, count) VALUES ('" + A.accountId() + "', 7, 19721, 1)");
        }

        // Db.open() reads repo.AppConfig/EnvConfig, then honors this property to reach the
        // disposable schema - the same shared path every other repository uses.
        System.setProperty(Db.TEST_SCHEMA_PROPERTY, schema);

        List<BankRepository.BankSlotRow> slots = new BankRepository().loadBankSlots(A);

        assertEquals(1, slots.size());
        assertEquals(7, slots.get(0).slot());
        assertEquals(19721, slots.get(0).itemId());
    }
}
