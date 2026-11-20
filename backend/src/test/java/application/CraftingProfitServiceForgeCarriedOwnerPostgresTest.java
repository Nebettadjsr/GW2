package application;

import craft.CraftResult;
import craft.CraftingGraph;
import craft.CraftingPlanner;
import craft.CraftingSettings;
import craft.PriceQuote;
import craft.Recipe;
import model.AccountScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import repo.CharacterRepository;
import repo.CraftingGraphCache;
import repo.DiscChoice;
import repo.EnvConfig;
import repo.InventoryRepository;
import repo.ItemRepository;
import repo.RecipeRepository;
import repo.tp.TpPriceRepository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migrated-PostgreSQL boundary: the real {@link InventoryRepository} feeds {@link CraftingProfitService} for an
 * account whose only stock is soulbound items carried by a character with no trained discipline. Abyssal Scepter
 * (forge id 3105801) consumes 20852x1, 19976x100, 12976x250, 19721x250. Account B owns one more full set in its Bank;
 * it must never count for account A. Runs in a disposable schema, never public; no owner data, no network.
 */
class CraftingProfitServiceForgeCarriedOwnerPostgresTest {

    private static final String ACC_A = "10000000-0000-0000-0000-00000000000a";
    private static final String ACC_B = "10000000-0000-0000-0000-00000000000b";
    private static final int SCEPTER_RECIPE = -3105801;
    private static final int[][] INPUTS = {{20852, 1}, {19976, 100}, {12976, 250}, {19721, 250}};

    private final String schema = "test_fcarried_" + UUID.randomUUID().toString().replace("-", "");
    private Connection con;

    @BeforeEach
    void createIsolatedSchema() throws Exception {
        con = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD"));
        sql("CREATE SCHEMA " + schema);
        Flyway.configure().dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
        con.setSchema(schema);
        sql("INSERT INTO account_registry (account_id) VALUES ('" + ACC_A + "'), ('" + ACC_B + "')");
        sql("INSERT INTO characters (account_id, character_id, name) VALUES ('" + ACC_A + "', 1, 'Alpha')");
        int slot = 0;
        for (int[] in : INPUTS) {
            // A: carried, character-bound to the untrained Alpha. B: shared Bank stock (one full set).
            sql("INSERT INTO character_items (account_id, character_id, location, bag_index, slot_index, item_id, count, binding, bound_to) VALUES ('"
                    + ACC_A + "', 1, 'BAG', 0, " + slot + ", " + in[0] + ", " + in[1] + ", 'Character', 'Alpha')");
            sql("INSERT INTO account_bank (account_id, slot, item_id, count) VALUES ('" + ACC_B + "', " + slot + ", " + in[0] + ", " + in[1] + ")");
            slot++;
        }
    }

    @AfterEach
    void dropIsolatedSchema() throws Exception {
        if (con == null) return;
        try {
            sql("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            con.close();
        }
    }

    private void sql(String s) throws Exception {
        try (Statement st = con.createStatement()) { st.execute(s); }
    }

    @Test
    void untrainedCarriedOwnerFeedsForgeForItsAccountOnlyAndForgeOffIsUnchanged() throws Exception {
        AccountScope a = AccountScope.of(UUID.fromString(ACC_A));
        AccountScope b = AccountScope.of(UUID.fromString(ACC_B));

        // Issue #378 product change: Forge left Profit; stale opt-ins are ignored for every account.
        for (AccountScope scope : new AccountScope[] {a, b}) {
            var stale = service(con, scope).reload(DiscChoice.all(), settings(true));
            assertNull(stale.resultsByRecipeId().get(SCEPTER_RECIPE));
            assertTrue(stale.visibleRecipes().isEmpty());
            var off = service(con, scope).reload(DiscChoice.all(), settings(false));
            assertNull(off.resultsByRecipeId().get(SCEPTER_RECIPE));
        }
    }

    private static CraftingSettings settings(boolean forge) {
        return new CraftingSettings(true, false, 0, false, false, false).withMysticForge(forge);
    }

    private static CraftingProfitService service(Connection con, AccountScope scope) {
        return new CraftingProfitService(new RecipeRepository() {
            @Override public List<Recipe> loadRecipes(Connection con, AccountScope scope, String discipline) {
                return List.of();
            }
        }, new InventoryRepository(), new TpPriceRepository() {
            @Override public Map<Integer, PriceQuote> loadTpQuotes(Connection con, Set<Integer> ids) {
                return Map.of(31058, new PriceQuote(1000, 1100));
            }
        }, new ItemRepository() {
            @Override public Map<Integer, ItemInfo> loadItems(Connection con, Set<Integer> ids) { return Map.of(); }
        }, new CharacterRepository() {
            @Override public List<DiscRow> loadAllCharacterCrafting(Connection con, AccountScope scope) { return List.of(); }
        }, new CraftingGraphCache(new RecipeRepository()) {
            @Override public CraftingGraph load() { return new CraftingGraph(List.of()); }
            @Override public CraftingGraph loadForSnapshot(Connection con) { return load(); }
        }, new CraftingPlanner(), session(con, scope));
    }

    private static ProfitInputSession session(Connection con, AccountScope scope) {
        try {
            var c = ProfitInputSession.class.getDeclaredConstructor(Connection.class, AccountScope.class);
            c.setAccessible(true);
            return c.newInstance(con, scope);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
