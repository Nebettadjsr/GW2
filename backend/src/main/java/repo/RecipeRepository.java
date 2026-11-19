package repo;

import craft.CurrencyRequirement;
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
 * {@code craft.Ingredient} domain types:
 * this class is the persistence-to-domain mapping boundary, so callers - including {@code craft.*} -
 * never see JDBC-shaped types.
 *
 * Recipe-known decisions (which recipe rows/ids count as "unlocked") are delegated to
 * {@link RecipeKnowledgePolicy}: this class only fetches the plain unlock facts
 * (which recipe ids appear in {@code account_recipes}/{@code character_recipes}) and applies the
 * policy in Java, rather than expressing the unlock rule itself as SQL. Recipe ownership is
 * account-wide for every entry point below, including the character-scoped ones - a recipe
 * unlocked by any character on the account counts as known regardless of which character is
 * selected (DOMAIN_SPEC.md DQ-010); {@code charName} only selects the connection's
 * eligibility filtering (crafting discipline), not recipe ownership.
 */
public class RecipeRepository {

    /**
     * The recipe read the scoped entry points share: the caller supplies the account's unlock facts
     * and the knowledge decision itself stays in {@link RecipeKnowledgePolicy}.
     *
     * @param unfilteredWhenAll {@code true} for the {@link #loadRecipes} family, where the
     *                          discipline {@code "All"} means no discipline filter at all;
     *                          {@code false} for the character-scoped family, which always filters
     *                          by the named discipline
     */
    private List<Recipe> readKnownRecipes(Connection con,
                                          String discipline,
                                          boolean unfilteredWhenAll,
                                          Set<Integer> accountUnlocked,
                                          Set<Integer> unlockedByAnyCharacter) throws SQLException {
        Map<Integer, List<Ingredient>> ingredientsByRecipe = loadIngredientsByRecipe(con);
        Map<Integer, List<CurrencyRequirement>> currencies = loadCurrencyRequirements(con);
        Set<Integer> unsupported = loadUnsupportedRecipeIds(con);

        String sqlAll = """
        SELECT r.recipe_id, r.output_item_id, r.output_item_count, r.min_rating, r.disciplines,
               ('AutoLearned' = ANY(r.flags)) AS auto_learned
        FROM recipes r
        ORDER BY r.recipe_id
    """;

        String sqlDisc = """
        SELECT r.recipe_id, r.output_item_id, r.output_item_count,r.min_rating, r.disciplines,
               ('AutoLearned' = ANY(r.flags)) AS auto_learned
        FROM recipes r
        WHERE ? = ANY(r.disciplines)
        ORDER BY r.recipe_id
    """;

        boolean unfiltered = unfilteredWhenAll && "All".equalsIgnoreCase(discipline);

        List<Recipe> out = new ArrayList<>();

        PreparedStatement ps = unfiltered
                ? con.prepareStatement(sqlAll)
                : con.prepareStatement(sqlDisc);

        if (!unfiltered) {
            ps.setString(1, discipline);
        }

        try (ps; ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int recipeId = rs.getInt("recipe_id");
                if (unsupported.contains(recipeId)) continue;

                // AutoLearned recipes are not listed in unlock facts; the flag itself is the knowledge.
                boolean known = rs.getBoolean("auto_learned") || RecipeKnowledgePolicy.isKnownAccountWide(
                        accountUnlocked.contains(recipeId),
                        unlockedByAnyCharacter.contains(recipeId));
                if (!known) continue;

                out.add(mapRecipe(rs, recipeId, ingredientsByRecipe, currencies));
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
     * (so one calculation can read inside a single snapshot).
     */
    public List<Recipe> loadAllRecipes(Connection con) throws SQLException {
        Map<Integer, List<Ingredient>> ingredientsByRecipe = loadIngredientsByRecipe(con);
        Map<Integer, List<CurrencyRequirement>> currencies = loadCurrencyRequirements(con);
        Set<Integer> unsupported = loadUnsupportedRecipeIds(con);

        String sql = """
        SELECT recipe_id, output_item_id, output_item_count,min_rating, disciplines
        FROM recipes r
        ORDER BY recipe_id
    """;

        List<Recipe> out = new ArrayList<>();

        try (PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                int recipeId = rs.getInt("recipe_id");
                if (unsupported.contains(recipeId)) continue;
                out.add(mapRecipe(rs, recipeId, ingredientsByRecipe, currencies));
            }
        }

        return out;
    }


    /**
     * Currency requirements per recipe id, kept in their own namespace (never item ids) and attached to
     * every loaded {@link Recipe}. Requirements of an unrecognised kind are not currencies; full-catalog
     * validation rejects such a payload instead of publishing it, so none are expected here.
     */
    public Map<Integer, List<CurrencyRequirement>> loadCurrencyRequirements(Connection con) throws SQLException {
        Map<Integer, List<CurrencyRequirement>> map = new HashMap<>();
        try (PreparedStatement ps = con.prepareStatement("""
                SELECT recipe_id, ref_id, count FROM recipe_non_item_requirements
                WHERE kind = 'Currency' ORDER BY recipe_id, ref_id""");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                map.computeIfAbsent(rs.getInt(1), k -> new ArrayList<>())
                        .add(new CurrencyRequirement(rs.getInt(2), rs.getInt(3)));
            }
        }
        return map;
    }

    /**
     * Recipes carrying a requirement kind this application cannot honour (for example a guild upgrade).
     * They are quarantined from every load, so a legacy cached row can never be used as a free item-only recipe.
     */
    public Set<Integer> loadUnsupportedRecipeIds(Connection con) throws SQLException {
        Set<Integer> ids = new java.util.HashSet<>();
        try (PreparedStatement ps = con.prepareStatement(
                """
                SELECT recipe_id FROM recipe_non_item_requirements WHERE kind <> 'Currency'
                UNION
                SELECT recipe_id FROM recipes
                WHERE jsonb_typeof(guild_ingredients) = 'array' AND jsonb_array_length(guild_ingredients) > 0
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) ids.add(rs.getInt(1));
        }
        return ids;
    }

    /** Maps the current {@code recipes} row (id column already read) with its ingredients; one mapping for every read. */
    private static Recipe mapRecipe(ResultSet rs, int recipeId, Map<Integer, List<Ingredient>> ingredientsByRecipe,
                                    Map<Integer, List<CurrencyRequirement>> currencies)
            throws SQLException {
        Array discsArr = rs.getArray("disciplines");
        String disciplinesText = "";
        if (discsArr != null) {
            String[] discs = (String[]) discsArr.getArray();
            if (discs != null && discs.length > 0) disciplinesText = String.join(", ", discs);
        }
        return new Recipe(recipeId, rs.getInt("output_item_id"), rs.getInt("output_item_count"),
                rs.getInt("min_rating"), disciplinesText, ingredientsByRecipe.getOrDefault(recipeId, List.of()),
                currencies.getOrDefault(recipeId, List.of()));
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
     * The discoverable-candidate read the scoped entry points share; see
     * {@link #readKnownRecipes} for why the unlock facts are a parameter.
     *
     * <p>Missing DISCOVERABLE recipes for one account's character + discipline:
     * <ul>
     *   <li>discoverable = flags is NULL or an empty array;</li>
     *   <li>missing = not known account-wide, per {@link RecipeKnowledgePolicy#isKnownAccountWide}
     *       (known via ANY of the account's characters is not a Discovery candidate);</li>
     *   <li>discipline filter: "All" (or null) means none, else it must be in recipes.disciplines.</li>
     * </ul>
     */
    private List<Integer> readMissingDiscoverable(Connection con,
                                                  String discipline,
                                                  Set<Integer> accountUnlocked,
                                                  Set<Integer> unlockedByAnyCharacter) throws SQLException {
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

    /**
     * One account's known recipes, from either unlock source.
     *
     * <p>Both reads carry the account GUID, and
     * the account-wide ownership decision stays in {@link RecipeKnowledgePolicy} - the domain policy
     * is unchanged and simply receives already-scoped inputs.
     *
     * <p>The shared crafting graph in {@code recipes}/{@code recipe_ingredients} stays global:
     * knowledge selects from it, it is never partitioned per account.
     */
    public Set<Integer> loadKnownRecipeIds(Connection con, model.AccountScope scope) throws SQLException {
        Set<Integer> ids = loadAccountUnlockedRecipeIds(con, scope);
        ids.addAll(loadAnyCharacterUnlockedRecipeIds(con, scope));
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT recipe_id FROM recipes WHERE 'AutoLearned' = ANY(flags)");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) ids.add(rs.getInt(1));
        }
        return Set.copyOf(ids);
    }

    /**
     * Recipes one account knows, restricted by discipline ({@code "All"} = no filter), ordered by recipe id.
     * Recipe existence alone is not ownership.
     */
    public List<Recipe> loadRecipes(Connection con, model.AccountScope scope, String discipline) throws SQLException {
        return readKnownRecipes(con, discipline, true,
                loadAccountUnlockedRecipeIds(con, scope), loadAnyCharacterUnlockedRecipeIds(con, scope));
    }

    /**
     * Recipes one account knows for a selected character's discipline.
     *
     * <p>{@code charName} still selects discipline/rating eligibility elsewhere and does not narrow
     * ownership (DQ-010): a recipe unlocked by any of this account's characters
     * counts as known for the selected one. What scope adds is that "any character" means any
     * character of <em>this</em> account.
     */
    public List<Recipe> loadRecipesForCharacter(Connection con, model.AccountScope scope,
                                                String charName, String discipline) throws SQLException {
        return readKnownRecipes(con, discipline, false,
                loadAccountUnlockedRecipeIds(con, scope), loadAnyCharacterUnlockedRecipeIds(con, scope));
    }

    /**
     * Discoverable recipe ids one account does not yet know; see
     * {@link #readMissingDiscoverable}.
     */
    public List<Integer> loadMissingDiscoverableRecipeIdsForCharacter(Connection con, model.AccountScope scope,
                                                                      String charName, String discipline)
            throws SQLException {
        return readMissingDiscoverable(con, discipline,
                loadAccountUnlockedRecipeIds(con, scope), loadAnyCharacterUnlockedRecipeIds(con, scope));
    }

    private Set<Integer> loadAccountUnlockedRecipeIds(Connection con, model.AccountScope scope) throws SQLException {
        ManagedSchema.requireManagedAccountSchema(con, "account_recipes");
        return scopedIds(con, scope, "SELECT recipe_id FROM account_recipes WHERE account_id = ?");
    }

    private Set<Integer> loadAnyCharacterUnlockedRecipeIds(Connection con, model.AccountScope scope) throws SQLException {
        ManagedSchema.requireManagedAccountSchema(con, "character_recipes");
        return scopedIds(con, scope, "SELECT DISTINCT recipe_id FROM character_recipes WHERE account_id = ?");
    }

    private static Set<Integer> scopedIds(Connection con, model.AccountScope scope, String sql) throws SQLException {
        Set<Integer> ids = new HashSet<>();
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setObject(1, scope.accountId());
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

    public int countRecipeCurrencyRequirements() throws SQLException {
        try (Connection conn = repo.Db.open();
             var ps = conn.prepareStatement("SELECT COUNT(*) FROM recipe_non_item_requirements");
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
