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
        public final String disciplinesText; // "Artificer, Tailor, ..."
        public final List<Ingredient> ingredients;

        public Recipe(int recipeId, int outputItemId, int outputCount, String disciplinesText, List<Ingredient> ingredients) {
            this.recipeId = recipeId;
            this.outputItemId = outputItemId;
            this.outputCount = outputCount;
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

        String sqlAll = """
            SELECT recipe_id, output_item_id, output_item_count, disciplines
            FROM recipes
            ORDER BY recipe_id
        """;

        String sqlDisc = """
            SELECT recipe_id, output_item_id, output_item_count, disciplines
            FROM recipes
            WHERE ? = ANY(disciplines)
            ORDER BY recipe_id
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

                    // pick all discipline as "label"
                    Array discsArr = rs.getArray("disciplines");
                    String disciplinesText = ""; // default if null/empty

                    if (discsArr != null) {
                        String[] discs = (String[]) discsArr.getArray();
                        if (discs != null && discs.length > 0) {
                            disciplinesText = String.join(", ", discs);
                        }
                    }


                    List<Ingredient> ings = ingredientsByRecipe.getOrDefault(recipeId, List.of());
                    out.add(new Recipe(recipeId, outputItemId, outputCount, disciplinesText, ings));
                }
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
}