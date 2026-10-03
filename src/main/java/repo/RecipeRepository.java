package repo;

import craft.Ingredient;
import craft.Recipe;
import craft.RecipeKnowledgePolicy;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads recipe/ingredient rows and maps them to the independent {@code craft.Recipe}/
 * {@code craft.Ingredient} domain types (STORY-DOM-017, TARGET_ARCHITECTURE.md section 10):
 * this class is the persistence-to-domain mapping boundary, so callers - including {@code craft.*} -
 * never see JDBC-shaped types.
 *
 * Recipe-known decisions (which recipe rows/ids count as "unlocked") are delegated to
 * {@link RecipeKnowledgePolicy} (STORY-DOM-018): this class only fetches the plain unlock facts
 * (which recipe ids appear in {@code account_recipes}/{@code character_recipes}) and applies the
 * policy in Java, rather than expressing the unlock rule itself as SQL. Recipe ownership is
 * account-wide for every entry point below, including the character-scoped ones - a recipe
 * unlocked by any character on the account counts as known regardless of which character is
 * selected (DOMAIN_SPEC.md DQ-010, STORY-DOM-019); {@code charName} only selects the connection's
 * eligibility filtering (crafting discipline), not recipe ownership.
 */
public class RecipeRepository {

    /**
     * Loads recipes already in DB (you sync unlocked recipes into recipes table).
     * If discipline = "All", returns all. Otherwise filters by discipline in recipes.disciplines array.
     */
    public List<Recipe> loadRecipes(String discipline) throws SQLException {
        try (Connection con = repo.Db.open()) {
            return loadRecipes(con, discipline);
        }
    }

    /**
     * Same query as {@link #loadRecipes(String)}, but runs against a caller-supplied
     * connection instead of opening one via {@link repo.Db#open()}. Exists so repository
     * integration tests can point this query at a disposable test schema/database
     * (docs/TEST_STRATEGY.md §31.2) without going through production connection config.
     */
    public List<Recipe> loadRecipes(Connection con, String discipline) throws SQLException {
        Map<Integer, List<Ingredient>> ingredientsByRecipe = loadIngredientsByRecipe(con);
        Set<Integer> accountUnlocked = loadAccountUnlockedRecipeIds(con);
        Set<Integer> unlockedByAnyCharacter = loadAnyCharacterUnlockedRecipeIds(con);

        String sqlAll = """
        SELECT r.recipe_id, r.output_item_id, r.output_item_count, r.min_rating, r.disciplines
        FROM recipes r
        ORDER BY r.recipe_id
    """;

        String sqlDisc = """
        SELECT r.recipe_id, r.output_item_id, r.output_item_count,r.min_rating, r.disciplines
        FROM recipes r
        WHERE ? = ANY(r.disciplines)
        ORDER BY r.recipe_id
    """;

        List<Recipe> out = new ArrayList<>();

        PreparedStatement ps = "All".equalsIgnoreCase(discipline)
                ? con.prepareStatement(sqlAll)
                : con.prepareStatement(sqlDisc);

        if (!"All".equalsIgnoreCase(discipline)) {
            ps.setString(1, discipline);
        }

        try (ps; ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int recipeId = rs.getInt("recipe_id");

                boolean known = RecipeKnowledgePolicy.isKnownAccountWide(
                        accountUnlocked.contains(recipeId),
                        unlockedByAnyCharacter.contains(recipeId));
                if (!known) continue;

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

        return out;
    }

    public List<Recipe> loadRecipesForCharacter(String charName, String discipline) throws SQLException {
        try (Connection con = repo.Db.open()) {
            return loadRecipesForCharacter(con, charName, discipline);
        }
    }

    /**
     * Same query as {@link #loadRecipesForCharacter(String, String)}, but runs against a
     * caller-supplied connection (see {@link #loadRecipes(Connection, String)} for why).
     */
    public List<Recipe> loadRecipesForCharacter(Connection con, String charName, String discipline) throws SQLException {
        Map<Integer, List<Ingredient>> ingredientsByRecipe = loadIngredientsByRecipe(con);
        Set<Integer> accountUnlocked = loadAccountUnlockedRecipeIds(con);
        Set<Integer> unlockedByAnyCharacter = loadAnyCharacterUnlockedRecipeIds(con);

        String sql = """
        SELECT r.recipe_id, r.output_item_id, r.output_item_count,r.min_rating, r.disciplines
        FROM recipes r
        WHERE ? = ANY(r.disciplines)
        ORDER BY r.recipe_id
    """;

        List<Recipe> out = new ArrayList<>();

        try (PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, discipline);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int recipeId = rs.getInt("recipe_id");

                    // Ownership is account-wide (DQ-010): unlocked by ANY character counts as known
                    // for the selected character too. charName selects the discipline/rating
                    // eligibility filtering elsewhere; it does not narrow ownership (STORY-DOM-019).
                    boolean known = RecipeKnowledgePolicy.isKnownAccountWide(
                            accountUnlocked.contains(recipeId),
                            unlockedByAnyCharacter.contains(recipeId));
                    if (!known) continue;

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
        try (Connection con = repo.Db.open()) {
            return loadAllRecipes(con);
        }
    }

    /**
     * Same query as {@link #loadAllRecipes()}, but runs against a caller-supplied connection
     * (see {@link #loadRecipes(Connection, String)} for why).
     */
    public List<Recipe> loadAllRecipes(Connection con) throws SQLException {
        Map<Integer, List<Ingredient>> ingredientsByRecipe = loadIngredientsByRecipe(con);

        String sql = """
        SELECT recipe_id, output_item_id, output_item_count,min_rating, disciplines
        FROM recipes
        ORDER BY recipe_id
    """;

        List<Recipe> out = new ArrayList<>();

        try (PreparedStatement ps = con.prepareStatement(sql);
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


    private Map<Integer, List<Ingredient>> loadIngredientsByRecipe(Connection con) throws SQLException {
        String sql = """
            SELECT recipe_id, item_id, count
            FROM recipe_ingredients
            ORDER BY recipe_id
        """;

        Map<Integer, List<Ingredient>> map = new HashMap<>();

        try (PreparedStatement ps = con.prepareStatement(sql);
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
     * - missing = recipe not known account-wide, per {@link RecipeKnowledgePolicy#isKnownAccountWide}
     *   (STORY-DOM-019: same account-wide ownership policy used by {@link #loadRecipesForCharacter}
     *   and {@link #loadRecipes(Connection, String)} - a recipe already known via ANY character's
     *   unlock is not a Discovery candidate for the selected character either)
     * - discipline filter: if discipline == "All" => no filter, else must be in recipes.disciplines
     */
    public List<Integer> loadMissingDiscoverableRecipeIdsForCharacter(String charName, String discipline) throws SQLException {
        try (Connection con = repo.Db.open()) {
            return loadMissingDiscoverableRecipeIdsForCharacter(con, charName, discipline);
        }
    }

    /**
     * Same query as {@link #loadMissingDiscoverableRecipeIdsForCharacter(String, String)}, but runs
     * against a caller-supplied connection (see {@link #loadRecipes(Connection, String)} for why).
     */
    public List<Integer> loadMissingDiscoverableRecipeIdsForCharacter(Connection con, String charName, String discipline) throws SQLException {
        Set<Integer> accountUnlocked = loadAccountUnlockedRecipeIds(con);
        Set<Integer> unlockedByAnyCharacter = loadAnyCharacterUnlockedRecipeIds(con);

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
            ORDER BY r.recipe_id
        """;

        List<Integer> ids = new ArrayList<>();

        try (PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, discipline == null ? "All" : discipline);
            ps.setString(2, discipline == null ? "All" : discipline);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int recipeId = rs.getInt(1);

                    // Ownership is account-wide (DQ-010); see loadRecipesForCharacter above.
                    boolean known = RecipeKnowledgePolicy.isKnownAccountWide(
                            accountUnlocked.contains(recipeId),
                            unlockedByAnyCharacter.contains(recipeId));
                    if (!known) ids.add(recipeId);
                }
            }
        }

        return ids;
    }

    private Set<Integer> loadAccountUnlockedRecipeIds(Connection con) throws SQLException {
        Set<Integer> ids = new HashSet<>();
        String sql = "SELECT recipe_id FROM account_recipes";
        try (PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) ids.add(rs.getInt(1));
        }
        return ids;
    }

    private Set<Integer> loadAnyCharacterUnlockedRecipeIds(Connection con) throws SQLException {
        Set<Integer> ids = new HashSet<>();
        String sql = "SELECT DISTINCT recipe_id FROM character_recipes";
        try (PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) ids.add(rs.getInt(1));
        }
        return ids;
    }

    /** Account-wide known recipes from either unlock source; recipe existence alone is not ownership. */
    public Set<Integer> loadKnownRecipeIds() throws SQLException {
        try (Connection con = repo.Db.open()) {
            Set<Integer> ids = loadAccountUnlockedRecipeIds(con);
            ids.addAll(loadAnyCharacterUnlockedRecipeIds(con));
            return Set.copyOf(ids);
        }
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
