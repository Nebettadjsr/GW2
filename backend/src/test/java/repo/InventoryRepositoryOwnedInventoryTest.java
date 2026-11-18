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

    private static final AccountScope A = AccountScope.of("10000000-0000-0000-0000-00000000000a");

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
    void sourceProtectionKeepsOtherStoresUsableAndChangesCraftableQuantityWithoutChangingCachedStock() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO account_materials (account_id, item_id, count) VALUES ('10000000-0000-0000-0000-00000000000a', 19718, 250)");
            st.execute("INSERT INTO account_bank (account_id, slot, item_id, count) VALUES ('10000000-0000-0000-0000-00000000000a', 1, 19718, 70)");
            st.execute("INSERT INTO characters (account_id, character_id, name) VALUES ('10000000-0000-0000-0000-00000000000a', 1, 'Crafter')");
            st.execute("INSERT INTO character_items (account_id, character_id, location, item_id, count) VALUES ('10000000-0000-0000-0000-00000000000a', 1, 'BAG', 19718, 12)");
        }
        var repository = new InventoryRepository();
        var recipe = new craft.Recipe(1, 500, 1, 0, "Artificer", List.of(new craft.Ingredient(19718, 4)));
        var roster = List.of(new craft.CharacterCraftingProfile("Crafter", Map.of("Artificer", 500)));
        int[] expectedStock = {332, 262, 82, 12};
        int[] expectedCrafts = {83, 65, 20, 3};
        for (int combination = 0; combination < 4; combination++) {
            Set<Integer> bankIds = (combination & 1) == 0 ? Set.of() : Set.of(19718);
            Set<Integer> materialIds = (combination & 2) == 0 ? Set.of() : Set.of(19718);
            var inventory = repository.loadOwnedInventoryForCharacters(con, A, Set.of("Crafter"), bankIds, materialIds);
            assertEquals(expectedStock[combination], inventory.sellable().get(19718));
            var settings = new craft.CraftingSettings(true, false, 0, false, false, false,
                    true, Set.of(), bankIds, materialIds);
            var result = new craft.CraftingPlanner().evaluateAllCoordinated(List.of(recipe), inventory.sellable(),
                    inventory.accountBound(), inventory.characterBound(), roster, Map.of(), settings, Set.of(1)).get(1);
            assertEquals(expectedCrafts[combination], result.craftableCount);
        }
        assertEquals(332, repository.loadOwnedInventory(con, A).get(19718), "stored quantities are unchanged");
    }

    @Test
    void loadOwnedInventory_sumsAccountMaterialsBankAndCharacterItems_forSameItem() throws Exception {
        int itemId = 19721;

        try (Statement st = con.createStatement()) {
            st.execute("INSERT INTO account_materials (account_id, item_id, count) VALUES ('10000000-0000-0000-0000-00000000000a', " + itemId + ", 5)");
            st.execute("INSERT INTO account_bank (account_id, slot, item_id, count) VALUES ('10000000-0000-0000-0000-00000000000a', 1, " + itemId + ", 3)");
            st.execute("INSERT INTO characters (account_id, character_id, name) VALUES ('10000000-0000-0000-0000-00000000000a', 1, 'Test Character')");
            st.execute("INSERT INTO character_items (account_id, character_id, location, bag_index, slot_index, item_id, count) " +
                    "VALUES ('10000000-0000-0000-0000-00000000000a', 1, 'BAG', 0, 0, " + itemId + ", 7)");
        }

        Map<Integer, Integer> owned = new InventoryRepository().loadOwnedInventory(con, A);

        assertEquals(15, owned.get(itemId));
    }
}
