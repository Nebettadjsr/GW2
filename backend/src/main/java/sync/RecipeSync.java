package sync;

import api.BatchUtils;
import api.Gw2ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import parser.IdListParser;
import parser.RecipeParser;
import repo.Db;

import java.sql.*;
import java.util.*;

public final class RecipeSync {

    private RecipeSync() {}

    /** Test seam: upstream fetch. Production keeps the real HTTP client. */
    static volatile PublicArrayFetcher fetcher = Gw2ApiClient::getPublicArray;

    public static boolean syncAllRecipesGlobalSafe() throws Exception {

        JsonNode idsRoot = fetcher.get(
                "https://api.guildwars2.com/v2/recipes");

        List<Integer> ids = IdListParser.parseIntArray(idsRoot, "recipes");

        System.out.println("Global recipes to sync (SAFE): " + ids.size());

        Set<Integer> remoteIds = new HashSet<>(ids);
        Set<Integer> localIds = new HashSet<>();
        try (Connection con = Db.open(); PreparedStatement ps = con.prepareStatement("SELECT recipe_id FROM recipes"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) localIds.add(rs.getInt(1));
        }
        if (!IdListDiff.between(localIds, remoteIds).changed() && isPublicationComplete()) return false;

        // Recipe batches commit before items/ingredients are fetched (no transaction spans HTTP),
        // so record the unfinished state first; only a full run marks it complete again.
        markPublication(false);

        Set<Integer> neededItemIds = new HashSet<>();
        List<RecipeBatch> fetchedBatches = new ArrayList<>();

        int done = 0;

        try (Connection con = Db.open();
             PreparedStatement psNow = con.prepareStatement("SELECT now()")) {

            for (List<Integer> batch : BatchUtils.chunk(ids, SyncConstants.HTTP_IDS_BATCH)) {

                String idsParam = BatchUtils.idsParam(batch);
                if (idsParam.isBlank()) continue;

                String url = "https://api.guildwars2.com/v2/recipes?ids=" + idsParam;
                JsonNode root = fetcher.get(url);

                List<RecipeParser.RecipeRow> rows = new ArrayList<>(root.size());
                List<RecipeParser.IngredientRow> ingredientRows = new ArrayList<>();

                for (JsonNode r : root) {

                    var rr = RecipeParser.parseRecipe(r);
                    if (rr == null) continue;

                    rows.add(rr);

                    if (rr.outputItem() > 0)
                        neededItemIds.add(rr.outputItem());

                    var ingredients = RecipeParser.parseIngredients(r);
                    ingredientRows.addAll(ingredients);
                    for (var ing : ingredients)
                        if (ing.itemId() > 0)
                            neededItemIds.add(ing.itemId());
                }

                con.setAutoCommit(false);

                try {

                    Timestamp runTs;

                    try (ResultSet rs = psNow.executeQuery()) {
                        if (!rs.next())
                            throw new SQLException("SELECT now() returned no row");
                        runTs = rs.getTimestamp(1);
                    }

                    upsertRecipes(con, rows, runTs);
                    con.commit();
                    fetchedBatches.add(new RecipeBatch(List.copyOf(batch), List.copyOf(ingredientRows)));

                } catch (Exception ex) {
                    con.rollback();
                    throw ex;
                }

                done += batch.size();

                System.out.println(
                        "Global recipes-only progress: "
                                + Math.min(done, ids.size())
                                + " / "
                                + ids.size());
            }
        }

        System.out.println("Global items needed: " + neededItemIds.size());

        ItemSync.syncItemsByIds(neededItemIds);

        syncRecipeIngredients(fetchedBatches);
        removeDeletedRecipes(remoteIds);
        markPublication(true);
        return true;
    }

    private static boolean isPublicationComplete() throws SQLException {
        try (Connection con = Db.open();
             PreparedStatement ps = con.prepareStatement("SELECT complete FROM recipe_catalog_publication");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    private static void markPublication(boolean complete) throws SQLException {
        try (Connection con = Db.open();
             PreparedStatement ps = con.prepareStatement("""
                     INSERT INTO recipe_catalog_publication (singleton, complete, updated_at)
                     VALUES (TRUE, ?, now())
                     ON CONFLICT (singleton) DO UPDATE SET complete = EXCLUDED.complete, updated_at = now()
                     """)) {
            ps.setBoolean(1, complete);
            ps.executeUpdate();
        }
    }

    private static void removeDeletedRecipes(Set<Integer> remoteIds) throws SQLException {
        try (Connection con = Db.open()) {
            con.setAutoCommit(false);
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM recipe_ingredients WHERE NOT (recipe_id = ANY (?))")) {
                Integer[] ids = remoteIds.toArray(Integer[]::new);
                ps.setArray(1, con.createArrayOf("int4", ids));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM recipes WHERE NOT (recipe_id = ANY (?))")) {
                Integer[] ids = remoteIds.toArray(Integer[]::new);
                ps.setArray(1, con.createArrayOf("int4", ids));
                ps.executeUpdate();
            }
            con.commit();
        }
    }

    /**
     * Upserts a batch of already-parsed {@link RecipeParser.RecipeRow} values into {@code recipes}.
     * Extracted from {@link #syncAllRecipesGlobalSafe} so the write path is callable independently
     * of the live GW2 fetch in tests, mirroring {@link TpSync#upsertTpPrices}'s precedent.
     */
    static void upsertRecipes(Connection con, List<RecipeParser.RecipeRow> rows, Timestamp runTs) throws SQLException {

        String recipeSql = """
        INSERT INTO recipes
        (recipe_id, type, output_item_id, output_item_count, min_rating, time_to_craft_ms, disciplines, flags, chat_link, guild_ingredients, fetched_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
        ON CONFLICT (recipe_id) DO UPDATE SET
          type = EXCLUDED.type,
          output_item_id = EXCLUDED.output_item_id,
          output_item_count = EXCLUDED.output_item_count,
          min_rating = EXCLUDED.min_rating,
          time_to_craft_ms = EXCLUDED.time_to_craft_ms,
          disciplines = EXCLUDED.disciplines,
          flags = EXCLUDED.flags,
          chat_link = EXCLUDED.chat_link,
          guild_ingredients = EXCLUDED.guild_ingredients::jsonb,
          fetched_at = EXCLUDED.fetched_at
        """;

        try (PreparedStatement psRecipe = con.prepareStatement(recipeSql)) {

            for (var rr : rows) {

                psRecipe.setInt(1, rr.recipeId());
                psRecipe.setString(2, rr.type());
                psRecipe.setInt(3, rr.outputItem());
                psRecipe.setInt(4, rr.outputCount());
                psRecipe.setInt(5, rr.minRating());
                psRecipe.setInt(6, rr.craftTime());
                psRecipe.setArray(7, con.createArrayOf("text", rr.disciplines()));
                psRecipe.setArray(8, con.createArrayOf("text", rr.flags()));
                psRecipe.setString(9, rr.chatLink());
                psRecipe.setString(10, rr.guildIngredientsJson());
                psRecipe.setTimestamp(11, runTs);

                psRecipe.addBatch();
            }

            psRecipe.executeBatch();
        }
    }


    private record RecipeBatch(List<Integer> ids, List<RecipeParser.IngredientRow> ingredients) { }

    private static void syncRecipeIngredients(List<RecipeBatch> batches) throws Exception {

        int ingDone = 0;

        try (Connection con = Db.open()) {

            for (RecipeBatch batch : batches) {

                con.setAutoCommit(false);

                try {
                    try (PreparedStatement delete = con.prepareStatement(
                            "DELETE FROM recipe_ingredients WHERE recipe_id = ANY (?)")) {
                        delete.setArray(1, con.createArrayOf("int4", batch.ids().toArray(Integer[]::new)));
                        delete.executeUpdate();
                    }
                    upsertRecipeIngredients(con, batch.ingredients());
                    con.commit();
                } catch (Exception ex) {
                    con.rollback();
                    throw ex;
                }

                ingDone += batch.ids().size();

                System.out.println(
                        "Global ingredients pass progress: "
                                + ingDone
                                + " / "
                                + batches.stream().mapToInt(b -> b.ids().size()).sum());
            }
        }

        System.out.println("✅ Global recipes + items + ingredients synced.");
    }

    /**
     * Upserts a batch of already-parsed {@link RecipeParser.IngredientRow} values into
     * {@code recipe_ingredients}, flushing every {@link SyncConstants#DB_FLUSH_BATCH} rows.
     * Extracted from {@link #syncRecipeIngredients} so the write path is callable independently of
     * the live GW2 fetch in tests, mirroring {@link TpSync#upsertTpPrices}'s precedent. The
     * surrounding autocommit/commit/rollback control stays with the caller, as in the original code.
     */
    static void upsertRecipeIngredients(Connection con, List<RecipeParser.IngredientRow> ingRows) throws SQLException {

        String ingredientSql = """
        INSERT INTO recipe_ingredients (recipe_id, item_id, count)
        VALUES (?, ?, ?)
        ON CONFLICT (recipe_id, item_id) DO UPDATE SET count = EXCLUDED.count
        """;

        try (PreparedStatement psIng = con.prepareStatement(ingredientSql)) {

            int counter = 0;

            for (var ir : ingRows) {

                psIng.setInt(1, ir.recipeId());
                psIng.setInt(2, ir.itemId());
                psIng.setInt(3, ir.count());

                psIng.addBatch();

                if (++counter % SyncConstants.DB_FLUSH_BATCH == 0) {

                    psIng.executeBatch();
                    con.commit();

                    psIng.clearBatch();
                    con.setAutoCommit(false);
                }
            }

            psIng.executeBatch();
        }
    }
}
