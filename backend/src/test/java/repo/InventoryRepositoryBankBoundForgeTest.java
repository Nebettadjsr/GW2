package repo;

import model.AccountScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import craft.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.*;

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
class InventoryRepositoryBankBoundForgeTest {

    private static final AccountScope A = AccountScope.of("10000000-0000-0000-0000-00000000000a");

    private final String schema = "test_bankforge_" + UUID.randomUUID().toString().replace("-", "");
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

    private static final String ACC = "'10000000-0000-0000-0000-00000000000a'";
    private static final int IN = 900_002, OUT = 900_001, PROTECTED = 900_005;

    private void sql(String s) throws Exception {
        try (Statement st = con.createStatement()) { st.execute(s); }
    }

    private void seed() throws Exception {
        sql("INSERT INTO characters (account_id, character_id, name) VALUES (" + ACC + ", 1, 'Alpha')");
        // Bank-bound to Alpha, Bank-bound to Beta (not in the roster), and one carried Alpha unit.
        sql("INSERT INTO account_bank (account_id, slot, item_id, count, binding, bound_to) VALUES (" + ACC + ", 1, " + IN + ", 1, 'Character', 'Alpha')");
        sql("INSERT INTO account_bank (account_id, slot, item_id, count, binding, bound_to) VALUES (" + ACC + ", 2, " + IN + ", 1, 'Character', 'Beta')");
        sql("INSERT INTO account_bank (account_id, slot, item_id, count, binding, bound_to) VALUES (" + ACC + ", 3, " + PROTECTED + ", 1, 'Character', 'Beta')");
        sql("INSERT INTO character_items (account_id, character_id, location, bag_index, slot_index, item_id, count, binding, bound_to) " +
                "VALUES (" + ACC + ", 1, 'BAG', 0, 0, " + IN + ", 1, 'Character', 'Alpha')");
    }

    private static CraftingSettings settings() {
        return new CraftingSettings(true, false, 0, false, false, false).withMysticForge(true);
    }

    private static CraftResult plan(int need, InventoryRepository.CoordinatedInventory inv, List<CharacterCraftingProfile> roster) {
        Recipe forge = new Recipe(-900_001, OUT, 1, 0, "", List.of(new Ingredient(IN, need)), List.of(), RecipeSource.MYSTIC_FORGE);
        return new CraftingPlanner().evaluateAllCoordinated(List.of(forge), inv.sellable(), inv.accountBound(),
                inv.characterBound(), roster, Map.of(OUT, new PriceQuote(1000, null)), settings(),
                java.util.Set.of(forge.recipeId)).get(forge.recipeId);
    }

    @Test
    void bankBoundProvenanceIsKeptApartFromCarriedStockAndFeedsForgeWithAnEmptyRoster() throws Exception {
        seed();
        InventoryRepository.CoordinatedInventory inv = new InventoryRepository()
                .loadOwnedInventoryForCharacters(con, A, Set.of("Alpha"));
        assertEquals(Map.of(IN, 1), inv.characterBound().get("Alpha"), "carried stock stays under the real owner");
        assertEquals(Map.of(IN, 1), inv.characterBound().get(PlanState.bankOwnerKey("Alpha")));
        assertEquals(Map.of(IN, 1, PROTECTED, 1), inv.characterBound().get(PlanState.bankOwnerKey("Beta")),
                "off-roster Bank-bound stock is kept (Forge-only), never treated as carried");
        assertTrue(inv.sellable().isEmpty() && inv.accountBound().isEmpty());

        // Empty/untrained roster: both Bank-bound units (two different owners) satisfy one Forge step of 2...
        InventoryRepository.CoordinatedInventory noRoster = new InventoryRepository()
                .loadOwnedInventoryForCharacters(con, A, Set.of());
        assertNull(noRoster.characterBound().get("Alpha"), "carried stock of a non-roster character is excluded");
        assertEquals(1, plan(2, noRoster, List.of()).craftableCount);
        // ...but a third unit exists only as carried Alpha stock, which an empty roster cannot use.
        assertEquals(0, plan(3, noRoster, List.of()).craftableCount);
        // With Alpha in the roster the carried unit joins: 3 units in total.
        assertEquals(1, plan(3, inv, List.of(new CharacterCraftingProfile("Alpha", Map.of()))).craftableCount);
        assertEquals(0, plan(4, inv, List.of(new CharacterCraftingProfile("Alpha", Map.of()))).craftableCount);
    }

    @Test
    void protectedAndFiniteReservedBankStockIsRemovedBeforeForgeUse() throws Exception {
        seed();
        InventoryRepository.CoordinatedInventory protectedSlot = new InventoryRepository()
                .loadOwnedInventoryForCharacters(con, A, Set.of(), Set.of(PROTECTED), Set.of());
        assertNull(protectedSlot.characterBound().get(PlanState.bankOwnerKey("Beta")).get(PROTECTED));

        InventoryRepository.CoordinatedInventory reserved = new InventoryRepository()
                .loadOwnedInventoryForCharacters(con, A, Set.of(), Set.of(), Set.of(), Map.of(IN, 1), Map.of());
        int bankLeft = reserved.characterBound().values().stream().mapToInt(m -> m.getOrDefault(IN, 0)).sum();
        // 2 Bank units - 1 reserved, plus nothing from carried (Alpha is outside this empty roster) = 1 left.
        assertEquals(1, bankLeft);
        assertEquals(0, plan(2, reserved, List.of()).craftableCount);
        assertEquals(1, plan(1, reserved, List.of()).craftableCount);
    }
}
