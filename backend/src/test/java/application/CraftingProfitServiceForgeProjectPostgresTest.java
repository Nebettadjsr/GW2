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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migrated-PostgreSQL boundary of {@link CraftingProfitService#planForgeProject}: real repositories over a disposable
 * schema (never public). Abyssal Scepter (forge id -3105801) needs 20852x1, 19976x100, 12976x250, 19721x250.
 * Account A carries only 20852x1 (character-bound to Alpha); B has a full set in its Bank; C owns nothing. Each account
 * sees only its own stock, and the unmet part of a target is reported missing rather than hidden.
 */
class CraftingProfitServiceForgeProjectPostgresTest {

    private static final String ACC_A = "10000000-0000-0000-0000-00000000000a";
    private static final String ACC_B = "10000000-0000-0000-0000-00000000000b";
    private static final String ACC_C = "10000000-0000-0000-0000-00000000000c";
    private static final int SCEPTER_RECIPE = -3105801;
    private static final int[][] INPUTS = {{20852, 1}, {19976, 100}, {12976, 250}, {19721, 250}};

    private final String schema = "test_fproject_" + UUID.randomUUID().toString().replace("-", "");
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
        sql("INSERT INTO account_registry (account_id) VALUES ('" + ACC_A + "'), ('" + ACC_B + "'), ('" + ACC_C + "')");
        sql("INSERT INTO characters (account_id, character_id, name) VALUES ('" + ACC_A + "', 1, 'Alpha')");
        sql("INSERT INTO character_items (account_id, character_id, location, bag_index, slot_index, item_id, count, binding, bound_to) VALUES ('"
                + ACC_A + "', 1, 'BAG', 0, 0, 20852, 1, 'Character', 'Alpha')");
        int slot = 0;
        for (int[] in : INPUTS) {
            sql("INSERT INTO account_bank (account_id, slot, item_id, count) VALUES ('" + ACC_B + "', " + slot++ + ", " + in[0] + ", " + in[1] + ")");
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

    private static long owned(CraftingProfitService.ForgeProjectData d, int itemId) {
        return d.project().items().stream().filter(i -> i.itemId() == itemId).mapToLong(i -> i.fromOwned()).sum();
    }

    private static long missing(CraftingProfitService.ForgeProjectData d, int itemId) {
        return d.project().items().stream().filter(i -> i.itemId() == itemId).mapToLong(i -> i.missing()).sum();
    }

    @Test
    void eachAccountPlansExactlyOneTargetFromItsOwnPoolsOnly() throws Exception {
        var a = service(con, AccountScope.of(UUID.fromString(ACC_A))).planForgeProject(SCEPTER_RECIPE, 1, craft.LegacyForge.SETTINGS);
        var b = service(con, AccountScope.of(UUID.fromString(ACC_B))).planForgeProject(SCEPTER_RECIPE, 1, craft.LegacyForge.SETTINGS);
        var c = service(con, AccountScope.of(UUID.fromString(ACC_C))).planForgeProject(SCEPTER_RECIPE, 1, craft.LegacyForge.SETTINGS);

        assertEquals(1, a.project().batches());
        assertEquals(1, owned(a, 20852), "A's own carried stock feeds the Forge step via its single carried owner");
        assertEquals(0, missing(a, 20852));
        assertEquals(100, missing(a, 19976), "B's Bank is invisible to A");
        assertEquals(250, missing(a, 12976));
        assertFalse(a.walletKnown(), "no wallet grant: unknown, never zero");
        for (int[] in : INPUTS) {
            assertEquals(in[1], owned(b, in[0]), "B's Bank stock covers the whole target");
            assertEquals(0, missing(b, in[0]));
            assertEquals(0, owned(c, in[0]), "C owns nothing of A's or B's stock");
            if (in[0] != 20852) assertEquals(in[1], missing(c, in[0]));
        }
        assertTrue(b.project().complete());
        // C has no Scroll: it is bought from the Spirit Shard vendor (50 shards), wallet unknown -> null, not zero.
        var shards = c.project().currencies().stream().filter(x -> x.currencyId() == 23).findFirst().orElseThrow();
        assertEquals(50, shards.needed());
        assertNull(shards.owned());
        assertTrue(a.project().currencies().isEmpty(), "A owns the Scroll, so no shards are demanded");
    }

    @Test
    void retainedQuoteOfRetiredItemIsNotACurrentAskButTradeableQuoteIs() throws Exception {
        sql("INSERT INTO tp_prices (item_id, buy_unit_price, sell_unit_price, fetched_at) VALUES (19976, 50, 60, now()), (12976, 70, 80, now())");
        sql("INSERT INTO tp_tradeable_items (item_id) VALUES (12976)"); // 19976 was retired from the tradeable catalogue
        var c = service(con, AccountScope.of(UUID.fromString(ACC_C))).planForgeProject(SCEPTER_RECIPE, 1, craft.LegacyForge.SETTINGS);
        var retired = c.project().items().stream().filter(i -> i.itemId() == 19976).findFirst().orElseThrow();
        var current = c.project().items().stream().filter(i -> i.itemId() == 12976).findFirst().orElseThrow();
        assertNull(retired.knownUnitCopper(), "a retained quote of a retired item must not price the leaf");
        assertTrue(c.project().unpricedItemIds().contains(19976));
        assertNotNull(current.knownUnitCopper(), "a current tradeable item keeps its quote");
        assertFalse(c.project().unpricedItemIds().contains(12976));
    }

    @Test
    void ownedValuationDependenciesOfAlternativeProducersJoinTheProjectPriceDemand() throws Exception {
        // 19976 (a path item B owns, unquoted) has two ordinary producers; only one is the structural route, the other's
        // ingredient (7002) is valuation-only. Both producers' ingredients must be demanded for the owning account.
        var graph = new CraftingGraph(List.of(
                new Recipe(901, 19976, 1, 0, "Artificer", List.of(new craft.Ingredient(7001, 1))),
                new Recipe(902, 19976, 1, 0, "Artificer", List.of(new craft.Ingredient(7002, 1)))));
        var b = service(con, AccountScope.of(UUID.fromString(ACC_B)), graph);
        var c = service(con, AccountScope.of(UUID.fromString(ACC_C)), graph);
        var structural = b.forgeProjectItemIds(SCEPTER_RECIPE);
        assertTrue(structural.contains(7001), "the structural producer's ingredient is already a path item");
        assertFalse(structural.contains(7002));

        var withValue = b.forgeProjectPriceIds(SCEPTER_RECIPE, craft.LegacyForge.SETTINGS);
        assertTrue(withValue.containsAll(structural));
        assertTrue(withValue.contains(7001) && withValue.contains(7002), "every alternative producer's ingredient is covered");
        var extra = new java.util.TreeSet<>(withValue);
        extra.removeAll(structural);
        assertEquals(Set.of(7002), extra, "no global or unrelated ids are added");

        var noValue = new craft.ForgeSettings(craft.LegacyForge.SETTINGS.priceMode(), true, false,
                craft.LegacyForge.SETTINGS.daily(), craft.LegacyForge.SETTINGS.promotions(), craft.LegacyForge.SETTINGS.reduce());
        assertEquals(structural, b.forgeProjectPriceIds(SCEPTER_RECIPE, noValue), "valueOwn=false adds no valuation work");
        assertEquals(c.forgeProjectItemIds(SCEPTER_RECIPE), c.forgeProjectPriceIds(SCEPTER_RECIPE, craft.LegacyForge.SETTINGS),
                "an account owning nothing has no owned valuation closure");

        // The valuation reads the quote of the valuation-only ingredient once it is retained: observed authoritative value.
        var before = b.planForgeProject(SCEPTER_RECIPE, 1, craft.LegacyForge.SETTINGS).project().ownValue();
        assertTrue(before.unpricedItemIds().contains(19976), "no quote anywhere: owned 19976 stays unvalued");
        sql("INSERT INTO tp_prices (item_id, buy_unit_price, sell_unit_price, fetched_at) VALUES (7002, 5, 7, now())");
        sql("INSERT INTO tp_tradeable_items (item_id) VALUES (7002)");
        var after = b.planForgeProject(SCEPTER_RECIPE, 1, craft.LegacyForge.SETTINGS).project().ownValue();
        assertFalse(after.unpricedItemIds().contains(19976), "the valuation-only quote now prices the owned item");
        assertTrue(after.knownCopper() > before.knownCopper());
    }

    private static CraftingProfitService service(Connection con, AccountScope scope) {
        return service(con, scope, new CraftingGraph(List.of()));
    }

    private static CraftingProfitService service(Connection con, AccountScope scope, CraftingGraph graph) {
        return new CraftingProfitService(new RecipeRepository(), new InventoryRepository(), new TpPriceRepository(),
                new ItemRepository(), new CharacterRepository(), new CraftingGraphCache(new RecipeRepository()) {
            @Override public CraftingGraph load() { return graph; }
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
