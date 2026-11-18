package repo;

import model.AccountScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Layer 2 PostgreSQL integration test (docs/TEST_STRATEGY.md §31.2) for
 * {@code InventoryRepository.loadOwnedInventoryForCharacter(Connection, String)}
 * (DOMAIN_SPEC.md §11.1 / DQ-007, STORY-DOM-011): verifies the real {@code binding}/
 * {@code bound_to} column filtering against Postgres, since that filtering is the part of
 * this story's repository method that domain-level unit tests (which start from an
 * already-split {@code PlanState}) cannot exercise.
 *
 * Runs against a disposable, uniquely-named schema on the local Postgres server, following
 * the same pattern as {@link InventoryRepositoryOwnedInventoryTest} (STORY-TEST-002).
 */
class InventoryRepositoryBoundMaterialTest {

    private static final AccountScope A = AccountScope.of("10000000-0000-0000-0000-00000000000a");

    private final String schema = "test_bound_" + UUID.randomUUID().toString().replace("-", "");
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
            st.execute("INSERT INTO account_registry (account_id) VALUES ('10000000-0000-0000-0000-00000000000a')");
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
    void classifiesUnboundAccountBoundAndSoulboundRowsForTheSelectedCharacter() throws Exception {
        int unboundItemId = 100;
        int accountBoundItemId = 200;
        int soulboundItemId = 300;

        String selectedCharacter = "Selected Hero";
        String otherCharacter = "Other Hero";

        try (Statement st = con.createStatement()) {
            // Ordinary tradable material storage entry - binding absent.
            st.execute("INSERT INTO account_materials (account_id, item_id, count) VALUES ('10000000-0000-0000-0000-00000000000a', " + unboundItemId + ", 4)");

            // Account-bound bank item - usable by any character.
            st.execute("INSERT INTO account_bank (account_id, slot, item_id, count, binding) " +
                    "VALUES ('10000000-0000-0000-0000-00000000000a', 1, " + accountBoundItemId + ", 2, 'Account')");

            st.execute("INSERT INTO characters (account_id, character_id, name) VALUES ('10000000-0000-0000-0000-00000000000a', 1, '" + selectedCharacter + "')");
            st.execute("INSERT INTO characters (account_id, character_id, name) VALUES ('10000000-0000-0000-0000-00000000000a', 2, '" + otherCharacter + "')");

            // Soulbound to the selected character - usable.
            st.execute("INSERT INTO character_items (account_id, character_id, location, bag_index, slot_index, item_id, count, binding, bound_to) " +
                    "VALUES ('10000000-0000-0000-0000-00000000000a', 1, 'BAG', 0, 0, " + soulboundItemId + ", 3, 'Character', '" + selectedCharacter + "')");

            // Soulbound to a different character - must be excluded entirely.
            st.execute("INSERT INTO character_items (account_id, character_id, location, bag_index, slot_index, item_id, count, binding, bound_to) " +
                    "VALUES ('10000000-0000-0000-0000-00000000000a', 2, 'BAG', 0, 0, " + soulboundItemId + ", 7, 'Character', '" + otherCharacter + "')");
        }

        Map<Integer, InventoryRepository.OwnedQuantity> owned =
                new InventoryRepository().loadOwnedInventoryForCharacter(con, A, selectedCharacter);

        InventoryRepository.OwnedQuantity unbound = owned.get(unboundItemId);
        assertEquals(4, unbound.sellableQty());
        assertEquals(0, unbound.boundQty());

        InventoryRepository.OwnedQuantity accountBound = owned.get(accountBoundItemId);
        assertEquals(0, accountBound.sellableQty());
        assertEquals(2, accountBound.boundQty());

        InventoryRepository.OwnedQuantity soulbound = owned.get(soulboundItemId);
        assertEquals(0, soulbound.sellableQty());
        // Only the 3 bound-to-selected-character units are usable - the 7 bound-to-other-character
        // units are excluded entirely, not merely marked unusable.
        assertEquals(3, soulbound.boundQty());
    }

    @Test
    void soulboundRowBoundToAnotherCharacter_isExcludedEntirelyWhenNoOtherOwnedQuantityExists() throws Exception {
        int soulboundOnlyItemId = 900;

        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO characters (account_id, character_id, name) VALUES ('10000000-0000-0000-0000-00000000000a', 1, 'Other Hero')");
            st.execute("INSERT INTO character_items (account_id, character_id, location, bag_index, slot_index, item_id, count, binding, bound_to) " +
                    "VALUES ('10000000-0000-0000-0000-00000000000a', 1, 'BAG', 0, 0, " + soulboundOnlyItemId + ", 9, 'Character', 'Other Hero')");
        }

        Map<Integer, InventoryRepository.OwnedQuantity> owned =
                new InventoryRepository().loadOwnedInventoryForCharacter(con, A, "Selected Hero");

        assertFalse(owned.containsKey(soulboundOnlyItemId));
    }

    @Test
    void loadOwnedInventory_isUnaffectedByBindingColumns() throws Exception {
        int itemId = 19721;

        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO account_materials (account_id, item_id, count) VALUES ('10000000-0000-0000-0000-00000000000a', " + itemId + ", 5)");
            st.execute("INSERT INTO characters (account_id, character_id, name) VALUES ('10000000-0000-0000-0000-00000000000a', 1, 'Selected Hero')");
            st.execute("INSERT INTO character_items (account_id, character_id, location, bag_index, slot_index, item_id, count, binding, bound_to) " +
                    "VALUES ('10000000-0000-0000-0000-00000000000a', 1, 'BAG', 0, 0, " + itemId + ", 7, 'Character', 'Someone Else')");
        }

        // The existing flat, binding-unaware method must remain unchanged: it still sums
        // every character_items row regardless of binding/bound_to.
        Map<Integer, Integer> flat = new InventoryRepository().loadOwnedInventory(con, A);
        assertEquals(12, flat.get(itemId));
    }

    @Test
    void coordinatedInventory_keepsSharedAndEachOwnersSoulboundQuantitiesSeparate() throws Exception {
        String alice = "Alice";
        String bea = "Bea";
        String outsideRoster = "Outside Roster";

        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO account_materials (account_id, item_id, count) VALUES ('10000000-0000-0000-0000-00000000000a', 100, 4)");
            st.execute("INSERT INTO account_bank (account_id, slot, item_id, count, binding) VALUES ('10000000-0000-0000-0000-00000000000a', 1, 200, 2, 'Account')");
            st.execute("INSERT INTO characters (account_id, character_id, name) VALUES ('10000000-0000-0000-0000-00000000000a', 1, '" + alice + "')");
            st.execute("INSERT INTO characters (account_id, character_id, name) VALUES ('10000000-0000-0000-0000-00000000000a', 2, '" + bea + "')");
            st.execute("INSERT INTO characters (account_id, character_id, name) VALUES ('10000000-0000-0000-0000-00000000000a', 3, '" + outsideRoster + "')");
            st.execute("INSERT INTO character_items (account_id, character_id, location, bag_index, slot_index, item_id, count, binding, bound_to) "
                    + "VALUES ('10000000-0000-0000-0000-00000000000a', 1, 'BAG', 0, 0, 300, 3, 'Character', '" + alice + "')");
            st.execute("INSERT INTO character_items (account_id, character_id, location, bag_index, slot_index, item_id, count, binding, bound_to) "
                    + "VALUES ('10000000-0000-0000-0000-00000000000a', 2, 'BAG', 0, 0, 300, 5, 'Character', '" + bea + "')");
            st.execute("INSERT INTO character_items (account_id, character_id, location, bag_index, slot_index, item_id, count, binding, bound_to) "
                    + "VALUES ('10000000-0000-0000-0000-00000000000a', 3, 'BAG', 0, 0, 300, 7, 'Character', '" + outsideRoster + "')");
        }

        InventoryRepository.CoordinatedInventory inventory =
                new InventoryRepository().loadOwnedInventoryForCharacters(con, A, Set.of(alice, bea));

        assertEquals(4, inventory.sellable().get(100));
        assertEquals(2, inventory.accountBound().get(200));
        assertEquals(3, inventory.characterBound().get(alice).get(300));
        assertEquals(5, inventory.characterBound().get(bea).get(300));
        assertFalse(inventory.characterBound().containsKey(outsideRoster));
    }
}
