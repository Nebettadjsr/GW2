package sync;

import model.AccountScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import repo.EnvConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** STORY-SYNC-007: global selection remains usable on the migrated, empty-account schema. */
class VersionedGlobalOperationsTest {

    private final String schema = "test_versioned_global_" + UUID.randomUUID().toString().replace("-", "");
    private Connection con;
    private UUID accountA;
    private UUID accountB;

    @BeforeEach
    void migrateAndSeed() throws Exception {
        con = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"));
        try (Statement statement = con.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        con.setSchema(schema);
        Flyway.configure().dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();

        accountA = UUID.randomUUID();
        accountB = UUID.randomUUID();
        try (Statement statement = con.createStatement()) {
            statement.execute("INSERT INTO items(item_id, name, icon_url) VALUES "
                    + "(10, 'shared', NULL), (11, 'ingredient-a', NULL), (20, 'bank-a', 'retained'), "
                    + "(21, 'bank-b', 'retained'), (22, 'materials-a', 'retained'), "
                    + "(23, 'materials-b', 'retained'), (24, 'character-a', 'retained'), "
                    + "(25, 'character-b', 'retained'), (30, 'output-b', NULL), "
                    + "(31, 'ingredient-b', NULL), (40, 'output-c', NULL), (41, 'ingredient-c', NULL)");
            statement.execute("INSERT INTO recipes(recipe_id, output_item_id) VALUES (1, 10), (2, 30), (3, 40)");
            statement.execute("INSERT INTO recipe_ingredients(recipe_id, item_id, count) VALUES (1, 11, 1), (2, 31, 1), (3, 41, 1)");
            statement.execute("INSERT INTO tp_tradeable_items(item_id) VALUES (10), (11), (20), (30), (31), (40), (41)");
            statement.execute("INSERT INTO account_registry(account_id) VALUES ('" + accountA + "'), ('" + accountB + "')");
            statement.execute("INSERT INTO account_recipes(account_id, recipe_id) VALUES ('" + accountA + "', 1), ('" + accountB + "', 2)");
            statement.execute("INSERT INTO account_bank(account_id, slot, item_id, count) VALUES ('" + accountA + "', 0, 20, 1), ('" + accountB + "', 0, 21, 1)");
            statement.execute("INSERT INTO account_materials(account_id, item_id, count) VALUES ('" + accountA + "', 22, 1), ('" + accountB + "', 23, 1)");
            statement.execute("INSERT INTO characters(account_id, character_id, name) VALUES ('" + accountA + "', 1001, 'A'), ('" + accountB + "', 1002, 'B')");
            statement.execute("INSERT INTO character_items(account_id, character_id, location, item_id, count) VALUES "
                    + "('" + accountA + "', 1001, 'bag', 24, 1), ('" + accountB + "', 1002, 'bag', 25, 1)");
        }
    }

    @AfterEach
    void dropSchema() throws Exception {
        if (con == null) return;
        try (Statement statement = con.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            con.close();
        }
    }

    @Test
    void globalIconMetadataSelectionWorksWithNoAccountRowsOrScope() throws Exception {
        try (Statement statement = con.createStatement()) {
            statement.execute("DELETE FROM character_items");
            statement.execute("DELETE FROM characters");
            statement.execute("DELETE FROM account_materials");
            statement.execute("DELETE FROM account_bank");
            statement.execute("DELETE FROM account_recipes");
            statement.execute("DELETE FROM account_registry");
        }
        assertEquals(List.of(10, 11, 30, 31, 40, 41), IconSync.globalReferencedOrUnknownItemIds(con));
    }

    @Test
    void iconMetadataAccountInputsAreScopedAndGlobalInputsRemainShared() throws Exception {
        assertEquals(List.of(10, 11, 30, 31, 40, 41), IconSync.globalReferencedOrUnknownItemIds(con));
        assertEquals(List.of(20, 22, 24), IconSync.accountReferencedItemIds(con, new AccountScope(accountA)));
        assertEquals(List.of(21, 23, 25), IconSync.accountReferencedItemIds(con, new AccountScope(accountB)));
    }

    @Test
    void profitAndDiscoverySelectionsKeepAccountRowsSeparateAndQuotesShared() throws Exception {
        var profit = new sync.tp.relevance.CraftingProfitItemCollector();
        var discovery = new sync.tp.relevance.DiscoveryItemCollector();
        assertEquals(Set.of(10, 11, 30, 31, 40, 41), profit.collectGlobal(con));
        assertEquals(Set.of(10, 11), profit.collect(con, new AccountScope(accountA)));
        assertEquals(Set.of(30, 31), profit.collect(con, new AccountScope(accountB)));
        assertEquals(Set.of(10, 11, 30, 31, 40, 41), discovery.collectGlobal(con));
        assertEquals(Set.of(30, 31, 40, 41), discovery.collect(con, new AccountScope(accountA)));
        assertEquals(Set.of(10, 11, 40, 41), discovery.collect(con, new AccountScope(accountB)));
    }

    @Test
    void sharedQuotesAreReusedAndOnlyRequiredMissingIdsAreSelected() throws Exception {
        try (Statement statement = con.createStatement()) {
            statement.execute("INSERT INTO tp_prices(item_id, buy_unit_price, sell_unit_price, fetched_at) VALUES (10, 1, 2, now())");
        }
        var profit = new sync.tp.relevance.CraftingProfitItemCollector();
        assertEquals(Set.of(11), profit.collect(con, new AccountScope(accountA)));
        assertEquals(Set.of(30, 31), profit.collect(con, new AccountScope(accountB)));
        assertEquals(Set.of(11, 30, 31, 40, 41), profit.collectGlobal(con));
    }

    private int quoteCount(int itemId) throws Exception {
        try (Statement statement = con.createStatement();
             ResultSet result = statement.executeQuery("SELECT count(*) FROM tp_prices WHERE item_id = " + itemId)) {
            result.next();
            return result.getInt(1);
        }
    }
}
