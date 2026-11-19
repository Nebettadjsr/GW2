package sync;

import api.BatchUtils;
import api.Gw2ApiClient;
import api.TaskDeadline;
import com.fasterxml.jackson.databind.JsonNode;
import parser.IdListParser;
import parser.RecipeParser;
import repo.Db;

import java.sql.*;
import java.util.*;

public final class RecipeSync {

    private RecipeSync() {}

    /**
     * The one explicitly supported recipe schema, used for the ID catalog, every batched detail read and
     * validation alike (never a global {@code latest}, never mixed): the default schema omits recipes such as
     * 13839 that only exist with typed {@code {type,id,count}} ingredients. Item and currency ids are
     * different namespaces in this schema.
     */
    public static final String SCHEMA_VERSION = "2022-03-09T02:00:00.000Z";
    public static final String CATALOG_URL = "https://api.guildwars2.com/v2/recipes?v=" + SCHEMA_VERSION;

    static String detailUrl(String idsParam) {
        return "https://api.guildwars2.com/v2/recipes?ids=" + idsParam + "&v=" + SCHEMA_VERSION;
    }

    /** Test seam: upstream fetch. Production keeps the real HTTP client. */
    static volatile PublicArrayFetcher fetcher = Gw2ApiClient::getPublicArray;

    public static boolean syncAllRecipesGlobalSafe() throws Exception {

        JsonNode idsRoot = fetcher.get(
                CATALOG_URL);

        List<Integer> ids = IdListParser.parseIntArray(idsRoot, "recipes");

        System.out.println("Global recipes to sync (SAFE): " + ids.size());

        Set<Integer> remoteIds = new HashSet<>(ids);
        Set<Integer> localIds = new HashSet<>();
        try (Connection con = Db.open(); PreparedStatement ps = con.prepareStatement("SELECT recipe_id FROM recipes"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) localIds.add(rs.getInt(1));
        }
        // Complete observed catalog: stored ids plus ids the last complete publication knowingly skipped.
        Set<Integer> observed = new HashSet<>(localIds);
        observed.addAll(loadSkippedIds());
        if (!IdListDiff.between(observed, remoteIds).changed() && isPublicationComplete()) return false;
        Set<Integer> skippedIds = new HashSet<>();
        boolean incompleteDetails = false;

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

                String url = detailUrl(idsParam);
                JsonNode root = fetcher.get(url);

                List<RecipeParser.RecipeRow> rows = new ArrayList<>(root.size());
                List<RecipeParser.IngredientRow> ingredientRows = new ArrayList<>();
                List<RecipeParser.RequirementRow> requirementRows = new ArrayList<>();

                Set<Integer> requested = new HashSet<>(batch);
                Set<Integer> accountedIds = new HashSet<>();
                Set<Integer> duplicateIds = new HashSet<>();
                // Quarantine every representation of a repeated id before collecting requirements;
                // choosing either record or merging them would invent an unproven recipe.
                for (JsonNode r : root) {
                    var rr = RecipeParser.parseRecipe(r);
                    if (rr != null && requested.contains(rr.recipeId()) && !accountedIds.add(rr.recipeId())) {
                        duplicateIds.add(rr.recipeId());
                    }
                }
                for (JsonNode r : root) {

                    var rr = RecipeParser.parseRecipe(r);
                    if (rr == null || !requested.contains(rr.recipeId()) || duplicateIds.contains(rr.recipeId())) continue;
                    if (RecipeParser.hasUnsupportedRequirement(r)) {
                        // Unsupported ingredient type: never publish a recipe with a silently dropped requirement.
                        System.out.println("Skipping recipe " + rr.recipeId() + ": unsupported ingredient type");
                        // Also drop any legacy cached row for it (removeDeletedRecipes below): otherwise its
                        // cleared ingredients would leave an old guild recipe usable as a free item-only recipe.
                        remoteIds.remove(rr.recipeId());
                        skippedIds.add(rr.recipeId());
                        continue;
                    }

                    try {
                        // Same strict ingredient validation as the full refresh: a recipe whose requirement is
                        // malformed (missing/nonpositive/overflowing id or count) is quarantined whole.
                        FullCatalogSupport.requireIngredients(r);
                    } catch (IllegalStateException malformed) {
                        System.out.println("Skipping recipe " + rr.recipeId() + ": malformed record");
                        remoteIds.remove(rr.recipeId());
                        skippedIds.add(rr.recipeId());
                        incompleteDetails = true;
                        continue;
                    }

                    rows.add(rr);

                    if (rr.outputItem() > 0)
                        neededItemIds.add(rr.outputItem());

                    requirementRows.addAll(RecipeParser.parseNonItemRequirements(r));
                    var ingredients = RecipeParser.parseIngredients(r);
                    ingredientRows.addAll(ingredients);
                    for (var ing : ingredients)
                        if (ing.itemId() > 0)
                            neededItemIds.add(ing.itemId());
                }

                // Requested ids the successful response omitted or returned unparseable have no proven
                // requirement set: quarantine them whole (no stored recipe, no cleared-then-free row) and
                // never clear requirements on their behalf.
                List<Integer> provenIds = new ArrayList<>(batch.size());
                for (Integer id : batch) {
                    if (accountedIds.contains(id) && !duplicateIds.contains(id)) {
                        provenIds.add(id);
                    } else {
                        System.out.println("Skipping recipe " + id + ": omitted, duplicate or malformed detail record");
                        remoteIds.remove(id);
                        skippedIds.add(id);
                        incompleteDetails = true;
                    }
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
                    fetchedBatches.add(new RecipeBatch(List.copyOf(provenIds), List.copyOf(ingredientRows), List.copyOf(requirementRows)));

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

        syncRecipeIngredients(fetchedBatches, TaskDeadline.NONE);
        removeDeletedRecipes(remoteIds, TaskDeadline.NONE);
        // Transient missing/malformed details must be retried even when the catalog IDs are unchanged.
        // Only an entirely accounted-for publication may use its skipped IDs for the fast path.
        markPublication(!incompleteDetails, TaskDeadline.NONE, skippedIds);
        return true;
    }

    /**
     * Owner full recipe-catalog synchronization: the same publication protocol as
     * {@link #syncAllRecipesGlobalSafe()} (incomplete before detail batches, committed disposable
     * batches stay after a failure, complete only after the whole sequence), but it never skips on an
     * unchanged ID list, re-downloads every captured recipe, and rejects incomplete or malformed
     * detail batches. Graph invalidation and rebuild belong to the calling application service.
     */
    static void syncAllRecipesFull(TaskDeadline deadline) throws Exception {
        deadline.check();
        List<Integer> ids = FullCatalogSupport.parseIdSnapshot(fetcher.get(CATALOG_URL), "recipes");
        Set<Integer> remoteIds = new HashSet<>(ids);
        Set<Integer> skippedIds = new HashSet<>();

        markPublication(false, deadline);

        Set<Integer> neededItemIds = new HashSet<>();
        List<RecipeBatch> fetchedBatches = new ArrayList<>();

        try (Connection con = Db.open();
             PreparedStatement psNow = con.prepareStatement("SELECT now()")) {
            for (List<Integer> batch : BatchUtils.chunk(ids, SyncConstants.HTTP_IDS_BATCH)) {
                deadline.check();
                JsonNode root = fetcher.get(detailUrl(BatchUtils.idsParam(batch)));
                deadline.check();
                List<RecipeParser.RecipeRow> rows = FullCatalogSupport.parseExact(
                        root, batch, n -> RecipeParser.parseRecipe(FullCatalogSupport.requireRecipe(n)),
                        RecipeParser.RecipeRow::recipeId, "recipes");
                List<RecipeParser.IngredientRow> ingredientRows = new ArrayList<>();
                List<RecipeParser.RequirementRow> requirementRows = new ArrayList<>();
                // Structurally valid records with a known unsupported requirement kind (e.g. GuildUpgrade) are
                // never published - no free/omitted requirement - but must not abort the supported catalog.
                Set<Integer> unsupportedIds = new HashSet<>();
                for (JsonNode r : root) {
                    if (RecipeParser.hasUnsupportedRequirement(r)) {
                        unsupportedIds.add(r.path("id").asInt());
                        continue;
                    }
                    requirementRows.addAll(RecipeParser.parseNonItemRequirements(r));
                    var ingredients = RecipeParser.parseIngredients(r);
                    ingredientRows.addAll(ingredients);
                    for (var ing : ingredients) if (ing.itemId() > 0) neededItemIds.add(ing.itemId());
                }
                if (!unsupportedIds.isEmpty()) {
                    rows = new ArrayList<>(rows);
                    rows.removeIf(rr -> unsupportedIds.contains(rr.recipeId()));
                    remoteIds.removeAll(unsupportedIds);
                    skippedIds.addAll(unsupportedIds);
                }
                for (var rr : rows) if (rr.outputItem() > 0) neededItemIds.add(rr.outputItem());

                con.setAutoCommit(false);
                try {
                    deadline.limitTransaction(con);
                    Timestamp runTs;
                    try (ResultSet rs = psNow.executeQuery()) {
                        if (!rs.next()) throw new SQLException("SELECT now() returned no row");
                        runTs = rs.getTimestamp(1);
                    }
                    upsertRecipes(con, rows, runTs);
                    deadline.check();
                    con.commit();
                    fetchedBatches.add(new RecipeBatch(List.copyOf(batch), List.copyOf(ingredientRows), List.copyOf(requirementRows)));
                    deadline.check();
                } catch (Exception ex) {
                    con.rollback();
                    throw ex;
                }
            }
        }

        ItemSync.syncItemsStrict(neededItemIds, deadline);
        syncRecipeIngredients(fetchedBatches, deadline);
        deadline.check();
        removeDeletedRecipes(remoteIds, deadline);
        deadline.check();
        markPublication(true, deadline, skippedIds);
        deadline.check();
    }

    private static Set<Integer> loadSkippedIds() throws SQLException {
        try (Connection con = Db.open();
             PreparedStatement ps = con.prepareStatement("SELECT skipped_recipe_ids FROM recipe_catalog_publication");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return Set.of();
            Array array = rs.getArray(1);
            if (array == null) return Set.of();
            return new HashSet<>(Arrays.asList((Integer[]) array.getArray()));
        }
    }

    private static boolean isPublicationComplete() throws SQLException {
        try (Connection con = Db.open();
             PreparedStatement ps = con.prepareStatement("SELECT complete FROM recipe_catalog_publication");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() && rs.getBoolean(1);
        }
    }

    private static void markPublication(boolean complete) throws SQLException {
        try {
            markPublication(complete, TaskDeadline.NONE);
        } catch (InterruptedException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Publication flag write inside one transaction limited to the remaining task budget. */
    private static void markPublication(boolean complete, TaskDeadline deadline)
            throws SQLException, InterruptedException {
        markPublication(complete, deadline, null);
    }

    /** {@code skipped} is stored only for a complete publication; an unfinished marker never claims a catalog. */
    private static void markPublication(boolean complete, TaskDeadline deadline, Set<Integer> skipped)
            throws SQLException, InterruptedException {
        deadline.check();
        try (Connection con = Db.open()) {
            con.setAutoCommit(false);
            try {
                deadline.limitTransaction(con);
                writePublication(con, complete, skipped);
                deadline.check();
                con.commit();
            } catch (Exception ex) {
                con.rollback();
                throw ex;
            }
        }
    }

    private static void writePublication(Connection con, boolean complete, Set<Integer> skipped) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                     INSERT INTO recipe_catalog_publication (singleton, complete, updated_at, skipped_recipe_ids)
                     VALUES (TRUE, ?, now(), ?)
                     ON CONFLICT (singleton) DO UPDATE SET complete = EXCLUDED.complete, updated_at = now(),
                         skipped_recipe_ids = CASE WHEN EXCLUDED.complete THEN EXCLUDED.skipped_recipe_ids
                                                   ELSE recipe_catalog_publication.skipped_recipe_ids END
                     """)) {
            ps.setBoolean(1, complete);
            ps.setArray(2, con.createArrayOf("int4",
                    (complete && skipped != null ? skipped : Set.<Integer>of()).toArray(Integer[]::new)));
            ps.executeUpdate();
        }
    }

    private static void removeDeletedRecipes(Set<Integer> remoteIds, TaskDeadline deadline) throws Exception {
        deadline.check();
        try (Connection con = Db.open()) {
            con.setAutoCommit(false);
            deadline.limitTransaction(con);
            Integer[] ids = remoteIds.toArray(Integer[]::new);
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM recipe_ingredients WHERE NOT (recipe_id = ANY (?))")) {
                ps.setArray(1, con.createArrayOf("int4", ids));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM recipes WHERE NOT (recipe_id = ANY (?))")) {
                ps.setArray(1, con.createArrayOf("int4", ids));
                ps.executeUpdate();
            }
            deadline.check();
            con.commit();
        }
    }

    /**
     * Upserts a batch of already-parsed {@link RecipeParser.RecipeRow} values into {@code recipes}.
     * Separate from the fetch so the write path is callable independently of the live GW2 API in tests.
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


    private record RecipeBatch(List<Integer> ids, List<RecipeParser.IngredientRow> ingredients,
                         List<RecipeParser.RequirementRow> requirements) { }

    private static void syncRecipeIngredients(List<RecipeBatch> batches, TaskDeadline deadline) throws Exception {

        int ingDone = 0;
        int total = batches.stream().mapToInt(batch -> batch.ids().size()).sum();

        try (Connection con = Db.open()) {

            for (RecipeBatch batch : batches) {

                deadline.check();
                con.setAutoCommit(false);

                try {
                    deadline.limitTransaction(con);
                    try (PreparedStatement delete = con.prepareStatement(
                            "DELETE FROM recipe_ingredients WHERE recipe_id = ANY (?)")) {
                        delete.setArray(1, con.createArrayOf("int4", batch.ids().toArray(Integer[]::new)));
                        delete.executeUpdate();
                    }
                    try (PreparedStatement delete = con.prepareStatement(
                            "DELETE FROM recipe_non_item_requirements WHERE recipe_id = ANY (?)")) {
                        delete.setArray(1, con.createArrayOf("int4", batch.ids().toArray(Integer[]::new)));
                        delete.executeUpdate();
                    }
                    upsertRecipeIngredients(con, batch.ingredients());
                    upsertNonItemRequirements(con, batch.requirements());
                    deadline.check();
                    con.commit();
                    deadline.check();
                } catch (Exception ex) {
                    con.rollback();
                    throw ex;
                }

                ingDone += batch.ids().size();

                System.out.println(
                        "Global ingredients pass progress: "
                                + ingDone
                                + " / "
                                + total);
            }
        }

        System.out.println("✅ Global recipes + items + ingredients synced.");
    }

    /** Writes typed non-item requirements (currencies); a repeated (recipe, kind, id) keeps the latest count. */
    static void upsertNonItemRequirements(Connection con, List<RecipeParser.RequirementRow> rows) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement("""
                INSERT INTO recipe_non_item_requirements (recipe_id, kind, ref_id, count)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (recipe_id, kind, ref_id) DO UPDATE SET count = EXCLUDED.count
                """)) {
            for (var r : rows) {
                ps.setInt(1, r.recipeId());
                ps.setString(2, r.kind());
                ps.setInt(3, r.refId());
                ps.setInt(4, r.count());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    /**
     * Upserts a batch of already-parsed {@link RecipeParser.IngredientRow} values into
     * {@code recipe_ingredients}, flushing every {@link SyncConstants#DB_FLUSH_BATCH} rows.
     * Separate from the fetch so the write path is callable independently of the live GW2 API in
     * tests. Full flushes only execute the JDBC batch; this helper never commits, so the caller's
     * transaction owns commit/rollback for the item and currency requirement tables of one fetched
     * detail batch together (both publish atomically or neither does).
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
                    psIng.clearBatch();
                }
            }

            psIng.executeBatch();
        }
    }
}
