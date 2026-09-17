package repo;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RecipeRepository {

    public static class Ingredient {
        public final int itemId;
        public final int count;
        public Ingredient(int itemId, int count) {
            this.itemId = itemId;
            this.count = count;
        }
    }

    public static class Recipe {
        public final int recipeId;
        public final int outputItemId;
        public final int outputCount;
        public final int minRating;
        public final String disciplinesText; // "Artificer, Tailor, ..."
        public final List<Ingredient> ingredients;

        public Recipe(int recipeId, int outputItemId, int outputCount, int minRating, String disciplinesText, List<Ingredient> ingredients) {
            this.recipeId = recipeId;
            this.outputItemId = outputItemId;
            this.outputCount = outputCount;
            this.minRating = minRating;
            this.disciplinesText = disciplinesText;
            this.ingredients = ingredients;
        }
    }

    /**
     * Loads recipes already in DB (you sync unlocked recipes into recipes table).
     * If discipline = "All", returns all. Otherwise filters by discipline in recipes.disciplines array.
     */
    public List<Recipe> loadRecipes(String discipline) throws SQLException {
        Map<Integer, List<Ingredient>> ingredientsByRecipe = loadIngredientsByRecipe();

        String unlockedCte = """
        WITH unlocked AS (
            SELECT recipe_id FROM account_recipes
            UNION
            SELECT recipe_id FROM character_recipes
        )
    """;

        String sqlAll = unlockedCte + """
        SELECT r.recipe_id, r.output_item_id, r.output_item_count, r.min_rating, r.disciplines
        FROM recipes r
        JOIN unlocked u ON u.recipe_id = r.recipe_id
        ORDER BY r.recipe_id
    """;

        String sqlDisc = unlockedCte + """
        SELECT r.recipe_id, r.output_item_id, r.output_item_count,r.min_rating, r.disciplines
        FROM recipes r
        JOIN unlocked u ON u.recipe_id = r.recipe_id
        WHERE ? = ANY(r.disciplines)
        ORDER BY r.recipe_id
    """;

        List<Recipe> out = new ArrayList<>();

        try (Connection con = repo.Db.open()) {
            PreparedStatement ps = "All".equalsIgnoreCase(discipline)
                    ? con.prepareStatement(sqlAll)
                    : con.prepareStatement(sqlDisc);

            if (!"All".equalsIgnoreCase(discipline)) {
                ps.setString(1, discipline);
            }

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int recipeId = rs.getInt("recipe_id");
                    int outputItemId = rs.getInt("output_item_id");
                    int outputCount = rs.getInt("output_item_count");
                    int outputMinRating = rs.getInt("min_rating");

                    Array discsArr = rs.getArray("disciplines");
                    String disciplinesText = "";
                    if (discsArr != null) {
                        String[] discs = (String[]) discsArr.getArray();
                        if (discs != null && discs.length > 0) disciplinesText = String.join(", ", discs);
                    }

                    List<Ingredient> ings = ingredientsByRecipe.getOrDefault(recipeId, List.of());
                    out.add(new Recipe(recipeId, outputItemId, outputCount, outputMinRating, disciplinesText, ings));
                }
            }
        }

        return out;
    }

    public List<Recipe> loadRecipesForCharacter(String charName, String discipline) throws SQLException {
        Map<Integer, List<Ingredient>> ingredientsByRecipe = loadIngredientsByRecipe();

        String sql = """
        WITH unlocked AS (
            SELECT recipe_id FROM account_recipes
            UNION
            SELECT cr.recipe_id
            FROM character_recipes cr
            JOIN characters c ON c.character_id = cr.character_id
            WHERE c.name = ?
        )
        SELECT r.recipe_id, r.output_item_id, r.output_item_count,r.min_rating, r.disciplines
        FROM recipes r
        JOIN unlocked u ON u.recipe_id = r.recipe_id
        WHERE ? = ANY(r.disciplines)
        ORDER BY r.recipe_id
    """;

        List<Recipe> out = new ArrayList<>();

        try (Connection con = repo.Db.open();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, charName);
            ps.setString(2, discipline);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int recipeId = rs.getInt("recipe_id");
                    int outputItemId = rs.getInt("output_item_id");
                    int outputCount = rs.getInt("output_item_count");
                    int outputMinRating = rs.getInt("min_rating");

                    Array discsArr = rs.getArray("disciplines");
                    String disciplinesText = "";
                    if (discsArr != null) {
                        String[] discs = (String[]) discsArr.getArray();
                        if (discs != null && discs.length > 0) disciplinesText = String.join(", ", discs);
                    }

                    List<Ingredient> ings = ingredientsByRecipe.getOrDefault(recipeId, List.of());
                    out.add(new Recipe(recipeId, outputItemId, outputCount, outputMinRating, disciplinesText, ings));
                }
            }
        }

        return out;
    }
    /**
     * Loads ALL recipes from DB (no account filter).
     * Used by planner so sub-recipes can be crafted.
     */
    public List<Recipe> loadAllRecipes() throws SQLException {
        Map<Integer, List<Ingredient>> ingredientsByRecipe = loadIngredientsByRecipe();

        String sql = """
        SELECT recipe_id, output_item_id, output_item_count,min_rating, disciplines
        FROM recipes
        ORDER BY recipe_id
    """;

        List<Recipe> out = new ArrayList<>();

        try (Connection con = repo.Db.open();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                int recipeId = rs.getInt("recipe_id");
                int outputItemId = rs.getInt("output_item_id");
                int outputCount = rs.getInt("output_item_count");
                int outputMinRating = rs.getInt("min_rating");

                Array discsArr = rs.getArray("disciplines");
                String disciplinesText = "";

                if (discsArr != null) {
                    String[] discs = (String[]) discsArr.getArray();
                    if (discs != null && discs.length > 0) {
                        disciplinesText = String.join(", ", discs);
                    }
                }

                List<Ingredient> ings =
                        ingredientsByRecipe.getOrDefault(recipeId, List.of());

                out.add(new Recipe(recipeId, outputItemId, outputCount, outputMinRating, disciplinesText, ings));
            }
        }

        return out;
    }


    private Map<Integer, List<Ingredient>> loadIngredientsByRecipe() throws SQLException {
        String sql = """
            SELECT recipe_id, item_id, count
            FROM recipe_ingredients
            ORDER BY recipe_id
        """;

        Map<Integer, List<Ingredient>> map = new HashMap<>();

        try (Connection con = repo.Db.open();
             PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                int recipeId = rs.getInt("recipe_id");
                int itemId = rs.getInt("item_id");
                int count = rs.getInt("count");

                map.computeIfAbsent(recipeId, k -> new ArrayList<>())
                        .add(new Ingredient(itemId, count));
            }
        }

        return map;
    }

    /**
     * Missing DISCOVERABLE recipes for a given character + discipline.
     *
     * Rules:
     * - discoverable = flags is NULL OR empty array (your " {} " case)
     * - missing = NOT in account_recipes AND NOT in character_recipes(for that char)
     * - discipline filter: if discipline == "All" => no filter, else must be in recipes.disciplines
     */
    public List<Integer> loadMissingDiscoverableRecipeIdsForCharacter(String charName, String discipline) throws SQLException {

        String sql = """
            SELECT r.recipe_id
            FROM recipes r
            WHERE
                -- discoverable only
                (r.flags IS NULL OR cardinality(r.flags) = 0)

                -- discipline filter
                AND (
                    ? = 'All'
                    OR ? = ANY(r.disciplines)
                )

                -- not unlocked on account
                AND NOT EXISTS (
                    SELECT 1 FROM account_recipes ar
                    WHERE ar.recipe_id = r.recipe_id
                )

                -- not discovered on that character
                AND NOT EXISTS (
                    SELECT 1
                    FROM character_recipes cr
                    JOIN characters c ON c.character_id = cr.character_id
                    WHERE c.name = ?
                      AND cr.recipe_id = r.recipe_id
                )
            ORDER BY r.recipe_id
        """;

        List<Integer> ids = new ArrayList<>();

        try (Connection con = repo.Db.open();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, discipline == null ? "All" : discipline);
            ps.setString(2, discipline == null ? "All" : discipline);
            ps.setString(3, charName);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt(1));
            }
        }

        return ids;
    }

    public int countRecipes() throws SQLException {
        String sql = "SELECT COUNT(*) FROM recipes";
        try (Connection conn = repo.Db.open();
             var ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    public int countRecipeIngredients() throws SQLException {
        String sql = "SELECT COUNT(*) FROM recipe_ingredients";
        try (Connection conn = repo.Db.open();
             var ps = conn.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }
}