package sync;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import parser.RecipeParser;
import repo.EnvConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer 2 PostgreSQL integration test (docs/TEST_STRATEGY.md §31.2) for {@code RecipeSync}'s two
 * database-write flows: {@code upsertRecipes} ({@code recipes}, including its {@code disciplines}/
 * {@code flags} array columns and {@code guild_ingredients} jsonb column) and
 * {@code upsertRecipeIngredients} ({@code recipe_ingredients}). This is the write-side counterpart
 * to {@code repo.RecipeRepositoryTest} (STORY-TEST-004), which already covers the read side.
 *
 * Runs against a disposable, uniquely-named schema on the local Postgres server, following the
 * same pattern as {@code TpSyncTest} (STORY-TEST-006) and {@code AccountSyncTest}
 * (STORY-TEST-007). Seeded rows are already-constructed {@link RecipeParser.RecipeRow}/
 * {@link RecipeParser.IngredientRow} values - no live GW2 API call is made, and
 * {@code syncAllRecipesGlobalSafe}'s fetch path (including its {@code ItemSync} call) is never
 * exercised.
 */
class RecipeSyncTest {

    private final String schema = "test_recipesync_" + UUID.randomUUID().toString().replace("-", "");
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
                CREATE TABLE recipes (
                    recipe_id         INTEGER PRIMARY KEY,
                    type              TEXT,
                    output_item_id    INTEGER,
                    output_item_count INTEGER,
                    time_to_craft_ms  INTEGER,
                    min_rating        INTEGER,
                    chat_link         TEXT,
                    disciplines       TEXT[],
                    flags             TEXT[],
                    guild_ingredients JSONB,
                    fetched_at        TIMESTAMPTZ
                )
                """);
            st.execute("""
                CREATE TABLE recipe_ingredients (
                    recipe_id INTEGER NOT NULL REFERENCES recipes(recipe_id),
                    item_id   INTEGER NOT NULL,
                    count     INTEGER,
                    PRIMARY KEY (recipe_id, item_id)
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

    // Postgres timestamptz has microsecond precision; truncate so round-tripped values compare equal.
    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant.truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void upsertRecipes_initialInsert_writesAllColumnsIncludingArraysAndJsonb() throws Exception {
        int recipeId = 7001;
        var row = new RecipeParser.RecipeRow(
                recipeId, "Refinement", 1001, 1, 400, 2000,
                new String[]{"Weaponsmith", "Armorsmith"},
                new String[]{"AutoLearned"},
                "[&AgEA]",
                "{\"guild_upgrade_id\":55,\"count\":1}");

        RecipeSync.upsertRecipes(con, List.of(row), ts(Instant.now()));

        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT type, output_item_id, output_item_count, time_to_craft_ms, min_rating, chat_link, " +
                             "(disciplines = ARRAY['Weaponsmith','Armorsmith']) AS disciplines_match, " +
                             "(flags = ARRAY['AutoLearned']) AS flags_match, " +
                             "(guild_ingredients = '{\"guild_upgrade_id\":55,\"count\":1}'::jsonb) AS guild_match " +
                             "FROM recipes WHERE recipe_id = " + recipeId)) {
            assertTrue(rs.next());
            assertEquals("Refinement", rs.getString("type"));
            assertEquals(1001, rs.getInt("output_item_id"));
            assertEquals(1, rs.getInt("output_item_count"));
            assertEquals(2000, rs.getInt("time_to_craft_ms"));
            assertEquals(400, rs.getInt("min_rating"));
            assertEquals("[&AgEA]", rs.getString("chat_link"));
            assertTrue(rs.getBoolean("disciplines_match"));
            assertTrue(rs.getBoolean("flags_match"));
            assertTrue(rs.getBoolean("guild_match"));
        }
    }

    @Test
    void upsertRecipes_reRunForSameRecipeId_updatesExistingRowInPlaceRatherThanInserting() throws Exception {
        int recipeId = 7002;
        Instant t1 = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant t2 = Instant.now();

        var initial = new RecipeParser.RecipeRow(
                recipeId, "Refinement", 1001, 1, 300, 1000,
                new String[]{"Weaponsmith"}, new String[]{}, null, null);
        RecipeSync.upsertRecipes(con, List.of(initial), ts(t1));

        var updated = new RecipeParser.RecipeRow(
                recipeId, "Refinement", 1001, 1, 350, 1000,
                new String[]{"Weaponsmith"}, new String[]{}, null, null);
        RecipeSync.upsertRecipes(con, List.of(updated), ts(t2));

        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*), MAX(min_rating), MAX(fetched_at) " +
                             "FROM recipes WHERE recipe_id = " + recipeId)) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals(350, rs.getInt(2));
            assertEquals(ts(t2), rs.getTimestamp(3));
        }
    }

    @Test
    void upsertRecipeIngredients_initialInsert_writesRowForGivenRecipeId() throws Exception {
        int recipeId = 7003;
        RecipeSync.upsertRecipes(con, List.of(minimalRecipeRow(recipeId)), ts(Instant.now()));

        var ingredient = new RecipeParser.IngredientRow(recipeId, 19721, 5);
        RecipeSync.upsertRecipeIngredients(con, List.of(ingredient));

        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count FROM recipe_ingredients WHERE recipe_id = " + recipeId + " AND item_id = 19721")) {
            assertTrue(rs.next());
            assertEquals(5, rs.getInt("count"));
        }
    }

    @Test
    void upsertRecipeIngredients_reRunForSameRecipeAndItemId_updatesCountInPlaceRatherThanDuplicating() throws Exception {
        int recipeId = 7004;
        int itemId = 19722;
        RecipeSync.upsertRecipes(con, List.of(minimalRecipeRow(recipeId)), ts(Instant.now()));

        RecipeSync.upsertRecipeIngredients(con, List.of(new RecipeParser.IngredientRow(recipeId, itemId, 3)));
        RecipeSync.upsertRecipeIngredients(con, List.of(new RecipeParser.IngredientRow(recipeId, itemId, 9)));

        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*), MAX(count) FROM recipe_ingredients " +
                             "WHERE recipe_id = " + recipeId + " AND item_id = " + itemId)) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals(9, rs.getInt(2));
        }
    }

    private static RecipeParser.RecipeRow minimalRecipeRow(int recipeId) {
        return new RecipeParser.RecipeRow(
                recipeId, "Refinement", 1001, 1, 0, 0,
                new String[]{}, new String[]{}, null, null);
    }
}
