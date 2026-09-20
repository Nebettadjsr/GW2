package repo;

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
        con.setSchema(schema);

        try (Statement st = con.createStatement()) {
            st.execute("""
                CREATE TABLE account_materials (
                    item_id     INTEGER PRIMARY KEY,
                    category    INTEGER,
                    count       INTEGER,
                    binding     TEXT,
                    fetched_at  TIMESTAMPTZ
                )
                """);
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
                CREATE TABLE characters (
                    character_id BIGSERIAL PRIMARY KEY,
                    name         TEXT NOT NULL UNIQUE
                )
                """);
            st.execute("""
                CREATE TABLE character_items (
                    character_id    BIGINT NOT NULL REFERENCES characters(character_id) ON DELETE CASCADE,
                    location        TEXT NOT NULL,
                    bag_index       INTEGER,
                    slot_index      INTEGER,
                    equipment_slot  TEXT,
                    item_id         INTEGER NOT NULL,
                    count           INTEGER NOT NULL DEFAULT 1,
                    binding         TEXT,
                    bound_to        TEXT,
                    fetched_at      TIMESTAMPTZ NOT NULL DEFAULT now()
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
    void classifiesUnboundAccountBoundAndSoulboundRowsForTheSelectedCharacter() throws Exception {
        int unboundItemId = 100;
        int accountBoundItemId = 200;
        int soulboundItemId = 300;

        String selectedCharacter = "Selected Hero";
        String otherCharacter = "Other Hero";

        try (Statement st = con.createStatement()) {
            // Ordinary tradable material storage entry - binding absent.
            st.execute("INSERT INTO account_materials (item_id, count) VALUES (" + unboundItemId + ", 4)");

            // Account-bound bank item - usable by any character.
            st.execute("INSERT INTO account_bank (slot, item_id, count, binding) " +
                    "VALUES (1, " + accountBoundItemId + ", 2, 'Account')");

            st.execute("INSERT INTO characters (character_id, name) VALUES (1, '" + selectedCharacter + "')");
            st.execute("INSERT INTO characters (character_id, name) VALUES (2, '" + otherCharacter + "')");

            // Soulbound to the selected character - usable.
            st.execute("INSERT INTO character_items (character_id, location, bag_index, slot_index, item_id, count, binding, bound_to) " +
                    "VALUES (1, 'BAG', 0, 0, " + soulboundItemId + ", 3, 'Character', '" + selectedCharacter + "')");

            // Soulbound to a different character - must be excluded entirely.
            st.execute("INSERT INTO character_items (character_id, location, bag_index, slot_index, item_id, count, binding, bound_to) " +
                    "VALUES (2, 'BAG', 0, 0, " + soulboundItemId + ", 7, 'Character', '" + otherCharacter + "')");
        }

        Map<Integer, InventoryRepository.OwnedQuantity> owned =
                new InventoryRepository().loadOwnedInventoryForCharacter(con, selectedCharacter);

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
            st.execute("INSERT INTO characters (character_id, name) VALUES (1, 'Other Hero')");
            st.execute("INSERT INTO character_items (character_id, location, bag_index, slot_index, item_id, count, binding, bound_to) " +
                    "VALUES (1, 'BAG', 0, 0, " + soulboundOnlyItemId + ", 9, 'Character', 'Other Hero')");
        }

        Map<Integer, InventoryRepository.OwnedQuantity> owned =
                new InventoryRepository().loadOwnedInventoryForCharacter(con, "Selected Hero");

        assertFalse(owned.containsKey(soulboundOnlyItemId));
    }

    @Test
    void loadOwnedInventory_isUnaffectedByBindingColumns() throws Exception {
        int itemId = 19721;

        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO account_materials (item_id, count) VALUES (" + itemId + ", 5)");
            st.execute("INSERT INTO characters (character_id, name) VALUES (1, 'Selected Hero')");
            st.execute("INSERT INTO character_items (character_id, location, bag_index, slot_index, item_id, count, binding, bound_to) " +
                    "VALUES (1, 'BAG', 0, 0, " + itemId + ", 7, 'Character', 'Someone Else')");
        }

        // The existing flat, binding-unaware method must remain unchanged: it still sums
        // every character_items row regardless of binding/bound_to.
        Map<Integer, Integer> flat = new InventoryRepository().loadOwnedInventory(con);
        assertEquals(12, flat.get(itemId));
    }

    @Test
    void coordinatedInventory_keepsSharedAndEachOwnersSoulboundQuantitiesSeparate() throws Exception {
        String alice = "Alice";
        String bea = "Bea";
        String outsideRoster = "Outside Roster";

        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO account_materials (item_id, count) VALUES (100, 4)");
            st.execute("INSERT INTO account_bank (slot, item_id, count, binding) VALUES (1, 200, 2, 'Account')");
            st.execute("INSERT INTO characters (character_id, name) VALUES (1, '" + alice + "')");
            st.execute("INSERT INTO characters (character_id, name) VALUES (2, '" + bea + "')");
            st.execute("INSERT INTO characters (character_id, name) VALUES (3, '" + outsideRoster + "')");
            st.execute("INSERT INTO character_items (character_id, location, bag_index, slot_index, item_id, count, binding, bound_to) "
                    + "VALUES (1, 'BAG', 0, 0, 300, 3, 'Character', '" + alice + "')");
            st.execute("INSERT INTO character_items (character_id, location, bag_index, slot_index, item_id, count, binding, bound_to) "
                    + "VALUES (2, 'BAG', 0, 0, 300, 5, 'Character', '" + bea + "')");
            st.execute("INSERT INTO character_items (character_id, location, bag_index, slot_index, item_id, count, binding, bound_to) "
                    + "VALUES (3, 'BAG', 0, 0, 300, 7, 'Character', '" + outsideRoster + "')");
        }

        InventoryRepository.CoordinatedInventory inventory =
                new InventoryRepository().loadOwnedInventoryForCharacters(con, Set.of(alice, bea));

        assertEquals(4, inventory.sellable().get(100));
        assertEquals(2, inventory.accountBound().get(200));
        assertEquals(3, inventory.characterBound().get(alice).get(300));
        assertEquals(5, inventory.characterBound().get(bea).get(300));
        assertFalse(inventory.characterBound().containsKey(outsideRoster));
    }
}
