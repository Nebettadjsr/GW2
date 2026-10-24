package repo;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Production-migration contract checks for the account ownership boundary (STORY-APP-015).
 * Each run gets a fresh PostgreSQL schema and applies the same versioned chain used at startup.
 */
class AccountScopedSchemaTest {

    private static final UUID ACCOUNT_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACCOUNT_B = UUID.fromString("20000000-0000-0000-0000-000000000002");

    private final String schema = "test_account_scope_" + UUID.randomUUID().toString().replace("-", "");
    private Connection admin;

    @BeforeEach
    void setUp() throws Exception {
        admin = connect();
        try (Statement statement = admin.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        Flyway.configure()
                .dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        try (Statement statement = admin.createStatement()) {
            statement.execute("SET search_path TO " + schema);
            statement.execute("INSERT INTO account_registry (account_id) VALUES ('" + ACCOUNT_A + "'), ('" + ACCOUNT_B + "')");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (admin == null) return;
        try (Statement statement = admin.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            admin.close();
        }
    }

    @Test
    void accountOwnedTablesAllowTheSameItemAndRecipeKeysWithoutCrossAccountOverwrite() throws Exception {
        execute("INSERT INTO account_bank (account_id, slot, item_id, count) VALUES ('" + ACCOUNT_A + "', 3, 19721, 2), ('" + ACCOUNT_B + "', 3, 19721, 7)");
        execute("INSERT INTO account_materials (account_id, item_id, count) VALUES ('" + ACCOUNT_A + "', 19721, 11), ('" + ACCOUNT_B + "', 19721, 19)");
        execute("INSERT INTO account_recipes (account_id, recipe_id) VALUES ('" + ACCOUNT_A + "', 7319), ('" + ACCOUNT_B + "', 7319)");
        execute("INSERT INTO account_luck (account_id, consumed_luck) VALUES ('" + ACCOUNT_A + "', 10), ('" + ACCOUNT_B + "', 20)");

        assertEquals(2, count("SELECT count FROM account_bank WHERE account_id = '" + ACCOUNT_A + "' AND slot = 3"));
        assertEquals(7, count("SELECT count FROM account_bank WHERE account_id = '" + ACCOUNT_B + "' AND slot = 3"));
        assertEquals(11, count("SELECT count FROM account_materials WHERE account_id = '" + ACCOUNT_A + "' AND item_id = 19721"));
        assertEquals(19, count("SELECT count FROM account_materials WHERE account_id = '" + ACCOUNT_B + "' AND item_id = 19721"));
        assertEquals(1, count("SELECT count(*) FROM account_recipes WHERE account_id = '" + ACCOUNT_A + "' AND recipe_id = 7319"));
        assertEquals(1, count("SELECT count(*) FROM account_recipes WHERE account_id = '" + ACCOUNT_B + "' AND recipe_id = 7319"));
        assertEquals(10, count("SELECT consumed_luck FROM account_luck WHERE account_id = '" + ACCOUNT_A + "'"));
        assertEquals(20, count("SELECT consumed_luck FROM account_luck WHERE account_id = '" + ACCOUNT_B + "'"));
    }

    @Test
    void characterChildrenCannotBeInsertedUnderAnotherAccountsScope() throws Exception {
        execute("INSERT INTO characters (account_id, character_id, name) VALUES ('" + ACCOUNT_A + "', 41, 'Shared Name'), ('" + ACCOUNT_B + "', 41, 'Shared Name')");
        execute("INSERT INTO character_crafting (account_id, character_id, discipline, rating) VALUES ('" + ACCOUNT_A + "', 41, 'Artificer', 400), ('" + ACCOUNT_B + "', 41, 'Artificer', 300)");
        execute("INSERT INTO character_recipes (account_id, character_id, recipe_id) VALUES ('" + ACCOUNT_A + "', 41, 7319), ('" + ACCOUNT_B + "', 41, 7319)");
        execute("INSERT INTO character_items (account_id, character_id, location, bag_index, slot_index, item_id, count) VALUES ('" + ACCOUNT_A + "', 41, 'bag', 0, 0, 19721, 2), ('" + ACCOUNT_B + "', 41, 'bag', 0, 0, 19721, 5)");

        assertEquals(2, count("SELECT count(*) FROM character_crafting WHERE account_id IN ('" + ACCOUNT_A + "', '" + ACCOUNT_B + "') AND character_id = 41"));
        assertEquals(2, count("SELECT count(*) FROM character_recipes WHERE account_id IN ('" + ACCOUNT_A + "', '" + ACCOUNT_B + "') AND character_id = 41"));
        assertEquals(2, count("SELECT count(*) FROM character_items WHERE account_id IN ('" + ACCOUNT_A + "', '" + ACCOUNT_B + "') AND character_id = 41"));

        assertThrows(SQLException.class, () -> execute("INSERT INTO character_recipes (account_id, character_id, recipe_id) VALUES ('" + ACCOUNT_A + "', 999, 7319)"));
        assertThrows(SQLException.class, () -> execute("INSERT INTO character_items (account_id, character_id, location, bag_index, slot_index, item_id, count) VALUES ('" + ACCOUNT_B + "', 999, 'bag', 1, 1, 19721, 1)"));
    }

    @Test
    void resetSchemaHasNoSuccessfulSourceMarkersOrFabricatedActivity() throws Exception {
        assertEquals(0, count("SELECT count(*) FROM account_source_state"));
        assertEquals(0, count("SELECT count(*) FROM pg_class WHERE relname = 'account_materials_sync' AND relnamespace = current_schema()::regnamespace"));
        assertEquals(0, count("SELECT count(*) FROM account_registry WHERE last_seen_at IS NOT NULL OR last_successful_sync_at IS NOT NULL"));
        assertEquals(2, count("SELECT count(*) FROM account_registry WHERE created_at IS NOT NULL"));
    }

    @Test
    void accountSourceRowsAndDeletesRemainScopedAcrossAllSourceFamilies() throws Exception {
        execute("INSERT INTO account_bank (account_id, slot, item_id, count) VALUES ('" + ACCOUNT_A + "', 1, 100, 2), ('" + ACCOUNT_B + "', 1, 100, 9)");
        execute("INSERT INTO account_materials (account_id, item_id, count) VALUES ('" + ACCOUNT_A + "', 100, 3), ('" + ACCOUNT_B + "', 100, 8)");
        execute("INSERT INTO account_recipes (account_id, recipe_id) VALUES ('" + ACCOUNT_A + "', 200), ('" + ACCOUNT_B + "', 200)");
        execute("INSERT INTO account_luck (account_id, consumed_luck) VALUES ('" + ACCOUNT_A + "', 30), ('" + ACCOUNT_B + "', 80)");
        execute("INSERT INTO characters (account_id, character_id, name) VALUES ('" + ACCOUNT_A + "', 51, 'Shared'), ('" + ACCOUNT_B + "', 51, 'Shared')");
        execute("INSERT INTO character_crafting (account_id, character_id, discipline, rating) VALUES ('" + ACCOUNT_A + "', 51, 'Artificer', 100), ('" + ACCOUNT_B + "', 51, 'Artificer', 400)");
        execute("INSERT INTO character_recipes (account_id, character_id, recipe_id) VALUES ('" + ACCOUNT_A + "', 51, 200), ('" + ACCOUNT_B + "', 51, 200)");
        execute("INSERT INTO character_items (account_id, character_id, location, bag_index, slot_index, item_id, count) VALUES ('" + ACCOUNT_A + "', 51, 'bag', 0, 0, 100, 2), ('" + ACCOUNT_B + "', 51, 'bag', 0, 0, 100, 9)");
        execute("INSERT INTO account_source_state (account_id, source, generation, completed_at) VALUES ('" + ACCOUNT_A + "', 'bank', 1, now()), ('" + ACCOUNT_B + "', 'bank', 1, now())");

        execute("DELETE FROM account_bank WHERE account_id = '" + ACCOUNT_A + "'");
        execute("DELETE FROM account_materials WHERE account_id = '" + ACCOUNT_A + "'");
        execute("DELETE FROM account_recipes WHERE account_id = '" + ACCOUNT_A + "'");
        execute("DELETE FROM account_luck WHERE account_id = '" + ACCOUNT_A + "'");
        execute("DELETE FROM characters WHERE account_id = '" + ACCOUNT_A + "'");
        execute("DELETE FROM account_source_state WHERE account_id = '" + ACCOUNT_A + "'");

        assertEquals(0, count("SELECT count(*) FROM account_bank WHERE account_id = '" + ACCOUNT_A + "'"));
        assertEquals(9, count("SELECT count FROM account_bank WHERE account_id = '" + ACCOUNT_B + "' AND slot = 1"));
        assertEquals(0, count("SELECT count(*) FROM account_materials WHERE account_id = '" + ACCOUNT_A + "'"));
        assertEquals(8, count("SELECT count FROM account_materials WHERE account_id = '" + ACCOUNT_B + "' AND item_id = 100"));
        assertEquals(0, count("SELECT count(*) FROM account_recipes WHERE account_id = '" + ACCOUNT_A + "'"));
        assertEquals(1, count("SELECT count(*) FROM account_recipes WHERE account_id = '" + ACCOUNT_B + "' AND recipe_id = 200"));
        assertEquals(0, count("SELECT count(*) FROM account_luck WHERE account_id = '" + ACCOUNT_A + "'"));
        assertEquals(80, count("SELECT consumed_luck FROM account_luck WHERE account_id = '" + ACCOUNT_B + "'"));
        assertEquals(0, count("SELECT count(*) FROM characters WHERE account_id = '" + ACCOUNT_A + "'"));
        assertEquals(1, count("SELECT count(*) FROM character_crafting WHERE account_id = '" + ACCOUNT_B + "' AND rating = 400"));
        assertEquals(1, count("SELECT count(*) FROM character_recipes WHERE account_id = '" + ACCOUNT_B + "' AND recipe_id = 200"));
        assertEquals(1, count("SELECT count(*) FROM character_items WHERE account_id = '" + ACCOUNT_B + "' AND count = 9"));
        assertEquals(0, count("SELECT count(*) FROM account_source_state WHERE account_id = '" + ACCOUNT_A + "'"));
        assertEquals(1, count("SELECT count(*) FROM account_source_state WHERE account_id = '" + ACCOUNT_B + "' AND source = 'bank' AND generation = 1"));
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = admin.createStatement()) {
            statement.execute(sql);
        }
    }

    private int count(String sql) throws SQLException {
        try (var statement = admin.createStatement(); var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                EnvConfig.require("DATABASE_PASSWORD"));
    }
}
