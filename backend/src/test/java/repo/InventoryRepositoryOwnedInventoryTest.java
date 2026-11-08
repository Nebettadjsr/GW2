package repo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Layer 2 PostgreSQL integration test (docs/TEST_STRATEGY.md §31.2) for
 * {@code DOMAIN_SPEC.md} §9 / DQ-006: the owned-material pool must sum
 * {@code account_materials} + {@code account_bank} + {@code character_items}.
 *
 * Runs against a real local PostgreSQL server using a disposable, uniquely-named schema
 * created in {@link #createIsolatedSchema()} and dropped in {@link #dropIsolatedSchema()},
 * so the developer's normal/working database (docs/TEST_STRATEGY.md §9) is never read from
 * or written to — see this story's Result section for why a disposable schema on the
 * project's already-required Postgres server was chosen over Testcontainers/Docker.
 */
class InventoryRepositoryOwnedInventoryTest {

    private final String schema = "test_inv_" + UUID.randomUUID().toString().replace("-", "");
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
    void sourceProtectionKeepsOtherStoresUsableAndChangesCraftableQuantityWithoutChangingCachedStock() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO account_materials (item_id, count) VALUES (19718, 250)");
            st.execute("INSERT INTO account_bank (slot, item_id, count) VALUES (1, 19718, 70)");
            st.execute("INSERT INTO characters (character_id, name) VALUES (1, 'Crafter')");
            st.execute("INSERT INTO character_items (character_id, location, item_id, count) VALUES (1, 'BAG', 19718, 12)");
        }
        var repository = new InventoryRepository();
        var recipe = new craft.Recipe(1, 500, 1, 0, "Artificer", List.of(new craft.Ingredient(19718, 4)));
        var roster = List.of(new craft.CharacterCraftingProfile("Crafter", Map.of("Artificer", 500)));
        int[] expectedStock = {332, 262, 82, 12};
        int[] expectedCrafts = {83, 65, 20, 3};
        for (int combination = 0; combination < 4; combination++) {
            Set<Integer> bankIds = (combination & 1) == 0 ? Set.of() : Set.of(19718);
            Set<Integer> materialIds = (combination & 2) == 0 ? Set.of() : Set.of(19718);
            var inventory = repository.loadOwnedInventoryForCharacters(con, null, Set.of("Crafter"), bankIds, materialIds);
            assertEquals(expectedStock[combination], inventory.sellable().get(19718));
            var settings = new craft.CraftingSettings(true, false, 0, false, false, false,
                    true, Set.of(), bankIds, materialIds);
            var result = new craft.CraftingPlanner().evaluateAllCoordinated(List.of(recipe), inventory.sellable(),
                    inventory.accountBound(), inventory.characterBound(), roster, Map.of(), settings, Set.of(1)).get(1);
            assertEquals(expectedCrafts[combination], result.craftableCount);
        }
        assertEquals(332, repository.loadOwnedInventory(con).get(19718), "stored quantities are unchanged");
    }

    @Test
    void loadOwnedInventory_sumsAccountMaterialsBankAndCharacterItems_forSameItem() throws Exception {
        int itemId = 19721;

        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO account_materials (item_id, count) VALUES (" + itemId + ", 5)");
            st.execute("INSERT INTO account_bank (slot, item_id, count) VALUES (1, " + itemId + ", 3)");
            st.execute("INSERT INTO characters (character_id, name) VALUES (1, 'Test Character')");
            st.execute("INSERT INTO character_items (character_id, location, bag_index, slot_index, item_id, count) " +
                    "VALUES (1, 'BAG', 0, 0, " + itemId + ", 7)");
        }

        Map<Integer, Integer> owned = new InventoryRepository().loadOwnedInventory(con);

        assertEquals(15, owned.get(itemId));
    }
}
