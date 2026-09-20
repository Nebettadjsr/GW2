package sync;

import api.BatchUtils;
import api.Gw2ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import parser.IdListParser;
import parser.RecipeParser;

import java.sql.*;
import java.util.*;

public final class RecipeSync {

    private RecipeSync() {}

    public static void syncAllRecipesGlobalSafe() throws Exception {

        JsonNode idsRoot = Gw2ApiClient.getPublicArray(
                "https://api.guildwars2.com/v2/recipes");

        List<Integer> ids = IdListParser.parseIntArray(idsRoot, "recipes");

        System.out.println("Global recipes to sync (SAFE): " + ids.size());

        if (ids.isEmpty()) return;

        Set<Integer> neededItemIds = new HashSet<>();

        int done = 0;

        try (Connection con = Db.openConnection();
             PreparedStatement psNow = con.prepareStatement("SELECT now()")) {

            for (List<Integer> batch : BatchUtils.chunk(ids, SyncConstants.HTTP_IDS_BATCH)) {

                String idsParam = BatchUtils.idsParam(batch);
                if (idsParam.isBlank()) continue;

                String url = "https://api.guildwars2.com/v2/recipes?ids=" + idsParam;
                JsonNode root = Gw2ApiClient.getPublicArray(url);

                List<RecipeParser.RecipeRow> rows = new ArrayList<>(root.size());

                for (JsonNode r : root) {

                    var rr = RecipeParser.parseRecipe(r);
                    if (rr == null) continue;

                    rows.add(rr);

                    if (rr.outputItem() > 0)
                        neededItemIds.add(rr.outputItem());

                    for (var ing : RecipeParser.parseIngredients(r))
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

        syncRecipeIngredients(ids);
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


    private static void syncRecipeIngredients(List<Integer> ids) throws Exception {

        int ingDone = 0;

        try (Connection con = Db.openConnection()) {

            for (List<Integer> batch : BatchUtils.chunk(ids, SyncConstants.HTTP_IDS_BATCH)) {

                String idsParam = BatchUtils.idsParam(batch);
                if (idsParam.isBlank()) continue;

                String url = "https://api.guildwars2.com/v2/recipes?ids=" + idsParam;

                JsonNode root = Gw2ApiClient.getPublicArray(url);

                List<RecipeParser.IngredientRow> ingRows = new ArrayList<>();

                for (JsonNode r : root)
                    ingRows.addAll(RecipeParser.parseIngredients(r));

                con.setAutoCommit(false);

                try {
                    upsertRecipeIngredients(con, ingRows);
                    con.commit();
                } catch (Exception ex) {
                    con.rollback();
                    throw ex;
                }

                ingDone += batch.size();

                System.out.println(
                        "Global ingredients pass progress: "
                                + Math.min(ingDone, ids.size())
                                + " / "
                                + ids.size());
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