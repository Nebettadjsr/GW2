package repo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Layer 2 PostgreSQL integration test (docs/TEST_STRATEGY.md §31.2) for
 * {@link RecipeRepository#loadRecipes(Connection, String)}: the account/character unlock
 * {@code UNION}, the {@code discipline = ANY(r.disciplines)} filter, the {@code NULL}/empty
 * {@code disciplines} fallback, and ingredient-to-recipe attachment.
 *
 * Runs against a disposable, uniquely-named schema on the local Postgres server, following the
 * same pattern as {@link InventoryRepositoryOwnedInventoryTest}/{@link InventoryRepositoryBoundMaterialTest}
 * (STORY-TEST-002/STORY-DOM-011).
 */
class RecipeRepositoryTest {

    private final String schema = "test_recipe_" + UUID.randomUUID().toString().replace("-", "");
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
                    output_item_id    INTEGER NOT NULL,
                    output_item_count INTEGER NOT NULL,
                    min_rating        INTEGER NOT NULL,
                    disciplines       TEXT[],
                    flags             TEXT[]
                )
                """);
            st.execute("""
                CREATE TABLE recipe_ingredients (
                    recipe_id INTEGER NOT NULL REFERENCES recipes(recipe_id),
                    item_id   INTEGER NOT NULL,
                    count     INTEGER NOT NULL
                )
                """);
            st.execute("""
                CREATE TABLE account_recipes (
                    recipe_id INTEGER PRIMARY KEY REFERENCES recipes(recipe_id)
                )
                """);
            st.execute("""
                CREATE TABLE characters (
                    character_id BIGSERIAL PRIMARY KEY,
                    name         TEXT NOT NULL UNIQUE
                )
                """);
            st.execute("""
                CREATE TABLE character_recipes (
                    character_id BIGINT NOT NULL REFERENCES characters(character_id) ON DELETE CASCADE,
                    recipe_id    INTEGER NOT NULL REFERENCES recipes(recipe_id)
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
    void loadRecipes_returnsRecipeUnlockedOnlyViaAccountRecipes() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("""
                INSERT INTO recipes (recipe_id, output_item_id, output_item_count, min_rating, disciplines)
                VALUES (1, 100, 1, 0, ARRAY['Chef'])
                """);
            st.execute("INSERT INTO account_recipes (recipe_id) VALUES (1)");
        }

        List<RecipeRepository.Recipe> recipes = new RecipeRepository().loadRecipes(con, "All");

        assertEquals(1, recipes.size());
        assertEquals(1, recipes.get(0).recipeId);
    }

    @Test
    void loadRecipes_returnsRecipeUnlockedOnlyViaCharacterRecipes() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("""
                INSERT INTO recipes (recipe_id, output_item_id, output_item_count, min_rating, disciplines)
                VALUES (2, 200, 1, 0, ARRAY['Chef'])
                """);
            st.execute("INSERT INTO characters (character_id, name) VALUES (1, 'Test Character')");
            st.execute("INSERT INTO character_recipes (character_id, recipe_id) VALUES (1, 2)");
        }

        List<RecipeRepository.Recipe> recipes = new RecipeRepository().loadRecipes(con, "All");

        assertEquals(1, recipes.size());
        assertEquals(2, recipes.get(0).recipeId);
    }

    @Test
    void loadRecipes_excludesRecipeUnlockedByNeitherAccountNorCharacter() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("""
                INSERT INTO recipes (recipe_id, output_item_id, output_item_count, min_rating, disciplines)
                VALUES (3, 300, 1, 0, ARRAY['Chef'])
                """);
        }

        List<RecipeRepository.Recipe> recipes = new RecipeRepository().loadRecipes(con, "All");

        assertTrue(recipes.isEmpty());
    }

    @Test
    void loadRecipes_disciplineFilter_includesMatchingAndExcludesNonMatchingDiscipline() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("""
                INSERT INTO recipes (recipe_id, output_item_id, output_item_count, min_rating, disciplines)
                VALUES (4, 400, 1, 0, ARRAY['Chef', 'Artificer'])
                """);
            st.execute("""
                INSERT INTO recipes (recipe_id, output_item_id, output_item_count, min_rating, disciplines)
                VALUES (5, 500, 1, 0, ARRAY['Tailor'])
                """);
            st.execute("INSERT INTO account_recipes (recipe_id) VALUES (4), (5)");
        }

        List<RecipeRepository.Recipe> chefRecipes = new RecipeRepository().loadRecipes(con, "Chef");

        assertEquals(1, chefRecipes.size());
        assertEquals(4, chefRecipes.get(0).recipeId);
    }

    @Test
    void loadRecipes_nullDisciplines_producesEmptyDisciplinesTextInsteadOfFailing() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("""
                INSERT INTO recipes (recipe_id, output_item_id, output_item_count, min_rating, disciplines)
                VALUES (6, 600, 1, 0, NULL)
                """);
            st.execute("INSERT INTO account_recipes (recipe_id) VALUES (6)");
        }

        List<RecipeRepository.Recipe> recipes = new RecipeRepository().loadRecipes(con, "All");

        assertEquals(1, recipes.size());
        assertEquals("", recipes.get(0).disciplinesText);
    }

    @Test
    void loadRecipes_emptyDisciplines_producesEmptyDisciplinesTextAndIsExcludedByDisciplineFilter() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("""
                INSERT INTO recipes (recipe_id, output_item_id, output_item_count, min_rating, disciplines)
                VALUES (7, 700, 1, 0, ARRAY[]::TEXT[])
                """);
            st.execute("INSERT INTO account_recipes (recipe_id) VALUES (7)");
        }

        List<RecipeRepository.Recipe> allRecipes = new RecipeRepository().loadRecipes(con, "All");
        assertEquals(1, allRecipes.size());
        assertEquals("", allRecipes.get(0).disciplinesText);

        List<RecipeRepository.Recipe> chefRecipes = new RecipeRepository().loadRecipes(con, "Chef");
        assertTrue(chefRecipes.isEmpty());
    }

    @Test
    void loadRecipes_attachesIngredientsToOwningRecipeOnly_includingZeroIngredientRecipe() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("""
                INSERT INTO recipes (recipe_id, output_item_id, output_item_count, min_rating, disciplines)
                VALUES (8, 800, 1, 0, ARRAY['Chef']),
                       (9, 900, 1, 0, ARRAY['Chef']),
                       (10, 1000, 1, 0, ARRAY['Chef'])
                """);
            st.execute("INSERT INTO account_recipes (recipe_id) VALUES (8), (9), (10)");

            // Recipe 8 has two ingredients, recipe 9 has one distinct ingredient, recipe 10 has none.
            st.execute("INSERT INTO recipe_ingredients (recipe_id, item_id, count) VALUES (8, 1, 2), (8, 2, 3)");
            st.execute("INSERT INTO recipe_ingredients (recipe_id, item_id, count) VALUES (9, 5, 1)");
        }

        List<RecipeRepository.Recipe> recipes = new RecipeRepository().loadRecipes(con, "All");
        assertEquals(3, recipes.size());

        RecipeRepository.Recipe recipe8 = recipes.stream().filter(r -> r.recipeId == 8).findFirst().orElseThrow();
        RecipeRepository.Recipe recipe9 = recipes.stream().filter(r -> r.recipeId == 9).findFirst().orElseThrow();
        RecipeRepository.Recipe recipe10 = recipes.stream().filter(r -> r.recipeId == 10).findFirst().orElseThrow();

        assertEquals(2, recipe8.ingredients.size());
        assertTrue(recipe8.ingredients.stream().anyMatch(i -> i.itemId == 1 && i.count == 2));
        assertTrue(recipe8.ingredients.stream().anyMatch(i -> i.itemId == 2 && i.count == 3));

        assertEquals(1, recipe9.ingredients.size());
        assertEquals(5, recipe9.ingredients.get(0).itemId);

        assertTrue(recipe10.ingredients.isEmpty());
    }

    @Test
    void loadAllRecipes_ignoresUnlockStatus_returningEveryRecipeRegardlessOfAccountOrCharacterUnlock() throws Exception {
        try (Statement st = con.createStatement()) {
            st.execute("""
                INSERT INTO recipes (recipe_id, output_item_id, output_item_count, min_rating, disciplines)
                VALUES (11, 1100, 1, 0, ARRAY['Chef'])
                """);
        }

        List<RecipeRepository.Recipe> recipes = new RecipeRepository().loadAllRecipes(con);

        assertEquals(1, recipes.size());
        assertEquals(11, recipes.get(0).recipeId);
    }
}
