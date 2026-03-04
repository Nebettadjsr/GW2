import api.BatchUtils;
import api.Gw2ApiClient;
import api.TpPriceApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import model.BankSlot;
import model.CharacterInfo;
import model.MaterialStack;
import parser.*;
import repo.AppConfig;
import util.DbBind;
import util.TpPrice;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public class Gw2DbSync {

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(AppConfig.DB_URL, AppConfig.DB_USER, AppConfig.DB_PASS);
    }

    // ---------------------------
    // 1) Account sync
    // ---------------------------

    public static void syncAccountBank() throws IOException, InterruptedException, SQLException {
        String url = "https://api.guildwars2.com/v2/account/bank";
        JsonNode root = Gw2ApiClient.getAuth(url);

        if (!root.isArray()) {
            throw new RuntimeException("Unexpected JSON (bank): not an array");
        }

        String sql = """
            INSERT INTO account_bank
              (slot, item_id, count, binding, bound_to, charges, stats_id, stats_attrs, fetched_at)
            VALUES
              (?, ?, ?, ?, ?, ?, ?, ?::jsonb, now())
            ON CONFLICT (slot) DO UPDATE SET
              item_id     = EXCLUDED.item_id,
              count       = EXCLUDED.count,
              binding     = EXCLUDED.binding,
              bound_to    = EXCLUDED.bound_to,
              charges     = EXCLUDED.charges,
              stats_id    = EXCLUDED.stats_id,
              stats_attrs = EXCLUDED.stats_attrs::jsonb,
              fetched_at  = EXCLUDED.fetched_at
            """;

        try (Connection con = openConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            con.setAutoCommit(false);

            for (int slot = 0; slot < root.size(); slot++) {
                JsonNode entry = root.get(slot);

                BankSlot row = BankParser.parseSlot(slot, entry);

                ps.setInt(1, row.slot());

                ps.setInt(1, row.slot());

                DbBind.setIntOrNull(ps, 2, row.itemId());
                DbBind.setIntOrNull(ps, 3, row.count());

                DbBind.setStringOrNull(ps, 4, row.binding());
                DbBind.setStringOrNull(ps, 5, row.boundTo());

                DbBind.setIntOrNull(ps, 6, row.charges());

                DbBind.setIntOrNull(ps, 7, row.statsId());
                DbBind.setStringOrNull(ps, 8, row.statsAttrsJson());

                ps.addBatch();
            }

            ps.executeBatch();
            con.commit();
        }
    }

    public static void syncAccountMaterials() throws Exception {
        String url = "https://api.guildwars2.com/v2/account/materials";
        JsonNode root = Gw2ApiClient.getAuth(url);

        String sql = """
        INSERT INTO account_materials (item_id, category, count, binding, fetched_at)
        VALUES (?, ?, ?, ?, now())
        ON CONFLICT (item_id) DO UPDATE SET
          category  = EXCLUDED.category,
          count     = EXCLUDED.count,
          binding   = EXCLUDED.binding,
          fetched_at= EXCLUDED.fetched_at
        """;

        try (Connection con = openConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            con.setAutoCommit(false);

            for (JsonNode entry : root) {
                MaterialStack row = MaterialParser.parse(entry);
                if (row == null) continue;

                ps.setInt(1, row.itemId());
                ps.setInt(2, row.category());
                ps.setInt(3, row.count());

                DbBind.setStringOrNull(ps, 4, row.binding());

                ps.addBatch();
            }

            ps.executeBatch();
            con.commit();
        }
    }

    public static void syncAccountRecipes() throws Exception {
        String url = "https://api.guildwars2.com/v2/account/recipes";
        JsonNode root = Gw2ApiClient.getAuth(url);

        String deleteSql = "DELETE FROM account_recipes";
        String insertSql = """
        INSERT INTO account_recipes (recipe_id, fetched_at)
        VALUES (?, now())
        ON CONFLICT (recipe_id) DO UPDATE SET fetched_at = EXCLUDED.fetched_at
        """;

        try (Connection con = openConnection();
             Statement st = con.createStatement();
             PreparedStatement ps = con.prepareStatement(insertSql)) {

            con.setAutoCommit(false);

            st.executeUpdate(deleteSql);

            for (JsonNode idNode : root) {
                Integer id = RecipeIdParser.parse(idNode);
                if (id == null) continue;
                ps.setInt(1, id);
                ps.addBatch();
            }

            ps.executeBatch();
            con.commit();
        }
    }


    public static void syncCharactersCraftingAndRecipes() throws Exception {
        List<String> names = fetchCharacterNames();
        if (names.isEmpty()) return;

        try (Connection con = openConnection()) {
            con.setAutoCommit(false);

            for (String name : names) {
                JsonNode cNode = fetchCharacterDetails(name);

                CharacterInfo info = CharacterParser.parseInfo(cNode);
                if (info == null) {
                    // skip bad payload
                    con.commit();
                    continue;
                }

                long characterId = upsertCharacter(con, info);

                var craftingRows = CharacterCraftingParser.parse(cNode.get("crafting"));
                replaceCharacterCrafting(con, characterId, craftingRows);

                var recipeRows = CharacterRecipesParser.parse(cNode.get("recipes"));
                replaceCharacterRecipes(con, characterId, recipeRows);

                con.commit(); // commit per char (safer)
            }
        }
    }

    public static List<String> fetchCharacterNames() throws Exception {
        String url = "https://api.guildwars2.com/v2/characters";
        JsonNode root = Gw2ApiClient.getAuth(url);

        List<String> names = new ArrayList<>();
        for (JsonNode n : root) names.add(n.asText());
        return names;
    }

    public static JsonNode fetchCharacterDetails(String characterName) throws Exception {
        String enc = URLEncoder.encode(characterName, StandardCharsets.UTF_8).replace("+", "%20");
        String url = "https://api.guildwars2.com/v2/characters/" + enc;
        return Gw2ApiClient.getAuth(url);
    }

    private static long upsertCharacter(Connection con, model.CharacterInfo c) throws SQLException {
        String sql = """
        INSERT INTO characters (name, profession, race, gender, level, created_at_gw, fetched_at)
        VALUES (?, ?, ?, ?, ?, ?::timestamptz, now())
        ON CONFLICT (name) DO UPDATE SET
          profession    = EXCLUDED.profession,
          race          = EXCLUDED.race,
          gender        = EXCLUDED.gender,
          level         = EXCLUDED.level,
          created_at_gw = EXCLUDED.created_at_gw,
          fetched_at    = EXCLUDED.fetched_at
        RETURNING character_id
        """;

        try (PreparedStatement ps = con.prepareStatement(sql)) {
            util.DbBind.setStringOrNull(ps, 1, c.name());
            util.DbBind.setStringOrNull(ps, 2, c.profession());
            util.DbBind.setStringOrNull(ps, 3, c.race());
            util.DbBind.setStringOrNull(ps, 4, c.gender());

            util.DbBind.setIntOrNull(ps, 5, c.level());
            util.DbBind.setStringOrNull(ps, 6, c.createdIso());

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("Upsert character failed (no RETURNING row)");
                return rs.getLong(1);
            }
        }
    }

    private static void replaceCharacterCrafting(Connection con, long characterId, List<model.CharacterCraftingRow> rows) throws SQLException {
        try (PreparedStatement del = con.prepareStatement("DELETE FROM character_crafting WHERE character_id = ?")) {
            del.setLong(1, characterId);
            del.executeUpdate();
        }

        if (rows == null || rows.isEmpty()) return;

        String ins = """
        INSERT INTO character_crafting (character_id, discipline, rating, is_active, fetched_at)
        VALUES (?, ?, ?, ?, now())
        ON CONFLICT (character_id, discipline) DO UPDATE SET
          rating     = EXCLUDED.rating,
          is_active  = EXCLUDED.is_active,
          fetched_at = EXCLUDED.fetched_at
        """;

        try (PreparedStatement ps = con.prepareStatement(ins)) {
            for (var row : rows) {
                ps.setLong(1, characterId);
                ps.setString(2, row.discipline());
                ps.setInt(3, row.rating());
                ps.setBoolean(4, row.active());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private static void replaceCharacterRecipes(Connection con, long characterId, List<model.CharacterRecipeRow> rows) throws SQLException {
        try (PreparedStatement del = con.prepareStatement("DELETE FROM character_recipes WHERE character_id = ?")) {
            del.setLong(1, characterId);
            del.executeUpdate();
        }

        if (rows == null || rows.isEmpty()) return;

        String ins = """
    INSERT INTO character_recipes (character_id, recipe_id, fetched_at)
    VALUES (?, ?, now())
    ON CONFLICT (character_id, recipe_id) DO UPDATE SET
      fetched_at = EXCLUDED.fetched_at
    """;

        try (PreparedStatement ps = con.prepareStatement(ins)) {
            for (var row : rows) {
                ps.setLong(1, characterId);
                ps.setInt(2, row.recipeId());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // ---------------------------
    // 4) TP Prices
    // ---------------------------

    public static void syncTpPricesRelevant() throws Exception {
        Set<Integer> itemIds = new HashSet<>();

        try (Connection con = openConnection();
             Statement st = con.createStatement()) {

            try (ResultSet rs = st.executeQuery("""
            SELECT DISTINCT item_id FROM account_bank WHERE item_id IS NOT NULL
        """)) {
                while (rs.next()) itemIds.add(rs.getInt(1));
            }

            try (ResultSet rs = st.executeQuery("""
            SELECT DISTINCT item_id FROM account_materials WHERE item_id IS NOT NULL
        """)) {
                while (rs.next()) itemIds.add(rs.getInt(1));
            }

            try (ResultSet rs = st.executeQuery("""
            SELECT DISTINCT r.output_item_id FROM recipes r WHERE r.output_item_id IS NOT NULL
        """)) {
                while (rs.next()) itemIds.add(rs.getInt(1));
            }

            try (ResultSet rs = st.executeQuery("""
            SELECT DISTINCT ri.item_id FROM recipe_ingredients ri WHERE ri.item_id IS NOT NULL
        """)) {
                while (rs.next()) itemIds.add(rs.getInt(1));
            }
        }

        List<Integer> ids = new ArrayList<>(itemIds);
        Collections.sort(ids);

        System.out.println("TP price items to fetch: " + ids.size());
        if (ids.isEmpty()) return;

        String upsertSql = """
        INSERT INTO tp_prices
        (item_id, buy_quantity, buy_unit_price, sell_quantity, sell_unit_price, fetched_at)
        VALUES (?, ?, ?, ?, ?, now())
        ON CONFLICT (item_id) DO UPDATE SET
          buy_quantity = EXCLUDED.buy_quantity,
          buy_unit_price = EXCLUDED.buy_unit_price,
          sell_quantity = EXCLUDED.sell_quantity,
          sell_unit_price = EXCLUDED.sell_unit_price,
          fetched_at = EXCLUDED.fetched_at
        """;

        int done = 0;

        for (List<Integer> batch : BatchUtils.chunk(ids, 200)) {

            // 1) HTTP + parsing FIRST (no DB connection held)
            List<TpPrice> quotes = fetchTpBatchParsed(batch);

            // 2) DB work SECOND (short transaction)
            try (Connection con = openConnection();
                 PreparedStatement ps = con.prepareStatement(upsertSql)) {

                con.setAutoCommit(false);

                for (TpPrice q : quotes) {
                    if (q == null) continue;

                    if (!q.hasMarketData()) {
                        upsertTpRow(ps, q.itemId(), null, null, null, null);
                    } else {
                        upsertTpRow(ps, q.itemId(), q.buyQty(), q.buyUnit(), q.sellQty(), q.sellUnit());
                    }
                }

                ps.executeBatch();
                con.commit();
            }

            done += batch.size();
            System.out.println("Fetched TP progress: " + Math.min(done, ids.size()) + " / " + ids.size());
        }

        System.out.println("✅ TP prices synced.");
    }

    private static List<TpPrice> fetchTpBatchParsed(List<Integer> batch) throws IOException, InterruptedException {
        api.TpPriceApi.BatchResult br = api.TpPriceApi.fetchBatch(batch);

        List<TpPrice> out = new ArrayList<>();

        // 404: retry individually
        if (br.statusCode() == 404) {
            for (int id : batch) {
                JsonNode one = api.TpPriceApi.fetchSingle(id);
                if (one == null) continue;

                TpPrice q = TpPriceParser.parse(one);
                if (q != null) out.add(q);
            }
            return out;
        }

        // 200/206 normal
        for (JsonNode p : br.array()) {
            TpPrice q = TpPriceParser.parse(p);
            if (q != null) out.add(q);
        }

        // 206: retry missing individually
        if (br.statusCode() == 206) {
            Set<Integer> returned = br.returnedIds();
            for (int id : batch) {
                if (returned.contains(id)) continue;

                JsonNode one = api.TpPriceApi.fetchSingle(id);
                if (one == null) continue;

                TpPrice q = TpPriceParser.parse(one);
                if (q != null) out.add(q);
            }
        }

        return out;
    }

    private static void upsertTpRow(PreparedStatement ps, int itemId,
                                    Long buyQty, Integer buyUnit,
                                    Long sellQty, Integer sellUnit) throws SQLException {

        ps.setInt(1, itemId);
        util.DbBind.setLongOrNull(ps, 2, buyQty);
        util.DbBind.setIntOrNull(ps, 3, buyUnit);
        util.DbBind.setLongOrNull(ps, 4, sellQty);
        util.DbBind.setIntOrNull(ps, 5, sellUnit);

        ps.addBatch();
    }

    // ---------------------------
    // 5) Icons
    // ---------------------------

    public static void syncItemIconUrls() throws Exception {
        List<Integer> ids = new ArrayList<>();

        // 1) Read ids from DB (short DB use)
        try (Connection con = openConnection();
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("""
             SELECT item_id
             FROM items
             WHERE icon_url IS NULL
             ORDER BY item_id
         """)) {

            while (rs.next()) ids.add(rs.getInt(1));
        }

        System.out.println("Items missing icon_url: " + ids.size());
        if (ids.isEmpty()) return;

        final int batchSize = 200;

        String updateSql = """
        UPDATE items
        SET icon_url = ?
        WHERE item_id = ?
        """;

        int done = 0;

        for (List<Integer> batch : BatchUtils.chunk(ids, batchSize)) {
            String idsParam = BatchUtils.idsParam(batch);
            if (idsParam.isBlank()) continue;

            // 2) HTTP FIRST (no DB connection held)
            String url = "https://api.guildwars2.com/v2/items?ids=" + idsParam;
            JsonNode root = Gw2ApiClient.getPublicArray(url);

            // Collect updates in memory
            List<int[]> updates = new ArrayList<>(); // [itemId, dummy]
            List<String> iconUrls = new ArrayList<>();

            for (JsonNode item : root) {
                if (item == null || item.isNull()) continue;

                int itemId = item.path("id").asInt(0);
                if (itemId <= 0) continue;

                String iconUrl = item.hasNonNull("icon") ? item.get("icon").asText() : null;
                if (iconUrl == null || iconUrl.isBlank()) continue;

                updates.add(new int[]{itemId});
                iconUrls.add(iconUrl);
            }

            // 3) DB SECOND (short transaction)
            int updatedThisBatch = 0;

            try (Connection con = openConnection();
                 PreparedStatement psUpdate = con.prepareStatement(updateSql)) {

                con.setAutoCommit(false);

                for (int i = 0; i < updates.size(); i++) {
                    int itemId = updates.get(i)[0];
                    String iconUrl = iconUrls.get(i);

                    psUpdate.setString(1, iconUrl);
                    psUpdate.setInt(2, itemId);
                    psUpdate.addBatch();
                    updatedThisBatch++;
                }

                psUpdate.executeBatch();
                con.commit();
            }

            done += batch.size();
            System.out.println("Updated icon_url batch: +" + updatedThisBatch + " | progress " +
                                       Math.min(done, ids.size()) + " / " + ids.size());
        }

        System.out.println("✅ items.icon_url populated.");
    }

    public static void syncItemIconsToDisk(Path iconBaseDir) throws Exception {
        Path itemsDir = iconBaseDir.resolve("items");
        Files.createDirectories(itemsDir);

        String selectSql = """
        SELECT item_id, icon_url
        FROM items
        WHERE icon_url IS NOT NULL
          AND (icon_path IS NULL OR icon_path = '')
        ORDER BY item_id
        """;

        String updateSql = """
        UPDATE items
        SET icon_path = ?
        WHERE item_id = ?
        """;

        // 1) DB FIRST (read what we need) — no downloads yet
        record IconJob(int itemId, String iconUrl) {}
        List<IconJob> jobs = new ArrayList<>();

        try (Connection con = openConnection();
             PreparedStatement psSelect = con.prepareStatement(selectSql);
             ResultSet rs = psSelect.executeQuery()) {

            while (rs.next()) {
                int itemId = rs.getInt("item_id");
                String iconUrl = rs.getString("icon_url");
                if (iconUrl == null || iconUrl.isBlank()) continue;

                jobs.add(new IconJob(itemId, iconUrl));
            }
        }

        System.out.println("Icon jobs to process: " + jobs.size());
        if (jobs.isEmpty()) return;

        // We'll batch DB updates
        record IconUpdate(int itemId, String iconPath) {}
        List<IconUpdate> updates = new ArrayList<>();

        int downloaded = 0;
        int skippedNoUrl = 0;
        int skippedAlreadyExists = 0;
        int failed = 0;

        // 2) IO (downloads) — no DB connection held
        for (IconJob job : jobs) {
            int itemId = job.itemId();
            String iconUrl = job.iconUrl();

            if (iconUrl == null || iconUrl.isBlank()) {
                skippedNoUrl++;
                continue;
            }

            Path target = itemsDir.resolve(itemId + ".png");

            // If already on disk -> just update DB
            if (Files.exists(target) && Files.size(target) > 0) {
                updates.add(new IconUpdate(itemId, target.toString()));
                skippedAlreadyExists++;
                continue;
            }

            try {
                var res = Gw2ApiClient.getBytesResponse(iconUrl);

                if (res.statusCode() != 200 || res.body() == null || res.body().length == 0) {
                    System.out.println("Icon download failed item " + itemId + " HTTP " + res.statusCode());
                    failed++;
                    continue;
                }

                Path tmp = target.resolveSibling(itemId + ".png.tmp");
                Files.write(tmp, res.body(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

                updates.add(new IconUpdate(itemId, target.toString()));
                downloaded++;

            } catch (Exception ex) {
                System.out.println("Icon download exception item " + itemId + ": " + ex.getMessage());
                failed++;
            }

            // Flush DB updates in chunks so memory doesn't grow forever
            if (updates.size() >= 200) {
                flushIconPathUpdates(updateSql, updates);
                updates.clear();
                System.out.println("Downloaded icons: " + downloaded + " | failed=" + failed);
            }
        }

        // Final flush
        flushIconPathUpdates(updateSql, updates);

        System.out.println("✅ Item icons synced. Downloaded=" + downloaded +
                                   " skippedNoUrl=" + skippedNoUrl +
                                   " skippedAlreadyExists=" + skippedAlreadyExists +
                                   " failed=" + failed);
    }

    /** Writes icon_path updates in a short transaction. */
    private static void flushIconPathUpdates(String updateSql, List<?> updatesRaw) throws SQLException {
        if (updatesRaw == null || updatesRaw.isEmpty()) return;

        // We know the list holds IconUpdate records from above
        @SuppressWarnings("unchecked")
        List<Object> updates = (List<Object>) updatesRaw;

        try (Connection con = openConnection();
             PreparedStatement psUpdate = con.prepareStatement(updateSql)) {

            con.setAutoCommit(false);

            for (Object o : updates) {
                // unpack record without needing it visible here
                // (works because record has accessor methods)
                int itemId;
                String iconPath;

                try {
                    itemId = (int) o.getClass().getMethod("itemId").invoke(o);
                    iconPath = (String) o.getClass().getMethod("iconPath").invoke(o);
                } catch (Exception e) {
                    throw new SQLException("Bad update row type: " + o.getClass(), e);
                }

                psUpdate.setString(1, iconPath);
                psUpdate.setInt(2, itemId);
                psUpdate.addBatch();
            }

            psUpdate.executeBatch();
            con.commit();
        }
    }

// -------- Initial fill helpers --------

    public static void syncItemsByIds(Set<Integer> itemIds) throws Exception {
        if (itemIds == null || itemIds.isEmpty()) return;

        final int batchSize = 200;

        String itemSql = """
        INSERT INTO items (item_id, name, type, rarity, vendor_value, fetched_at)
        VALUES (?, ?, ?, ?, ?, now())
        ON CONFLICT (item_id) DO UPDATE SET
          name = EXCLUDED.name,
          type = EXCLUDED.type,
          rarity = EXCLUDED.rarity,
          vendor_value = EXCLUDED.vendor_value,
          fetched_at = EXCLUDED.fetched_at
        """;

        List<Integer> idsList = new ArrayList<>(itemIds);

        int done = 0;

        for (List<Integer> batch : BatchUtils.chunk(idsList, batchSize)) {
            String idsParam = BatchUtils.idsParam(batch);
            if (idsParam.isBlank()) continue;

            // 1) HTTP FIRST (no DB connection held)
            String url = "https://api.guildwars2.com/v2/items?ids=" + idsParam;
            JsonNode root = Gw2ApiClient.getPublicArray(url);

            // Parse into lightweight in-memory rows
            record ItemRow(int itemId, String name, String type, String rarity, int vendorValue) {}
            List<ItemRow> rows = new ArrayList<>(root.size());

            for (JsonNode it : root) {
                if (it == null || it.isNull()) continue;

                int itemId = it.path("id").asInt(0);
                if (itemId <= 0) continue;

                String name = it.hasNonNull("name") ? it.get("name").asText() : null;
                String type = it.hasNonNull("type") ? it.get("type").asText() : null;
                String rarity = it.hasNonNull("rarity") ? it.get("rarity").asText() : null;
                int vendorValue = it.hasNonNull("vendor_value") ? it.get("vendor_value").asInt() : 0;

                rows.add(new ItemRow(itemId, name, type, rarity, vendorValue));
            }

            // 2) DB SECOND (short transaction)
            try (Connection con = openConnection();
                 PreparedStatement ps = con.prepareStatement(itemSql)) {

                con.setAutoCommit(false);

                for (ItemRow r : rows) {
                    ps.setInt(1, r.itemId());
                    ps.setString(2, r.name());
                    ps.setString(3, r.type());
                    ps.setString(4, r.rarity());
                    ps.setInt(5, r.vendorValue());
                    ps.addBatch();
                }

                ps.executeBatch();
                con.commit();
            }

            done += batch.size();
            System.out.println("Items batch progress: " + Math.min(done, idsList.size()) + " / " + idsList.size());
        }
    }

    public static void syncAllRecipesGlobalSafe() throws Exception {

        final int batchSize = 200;

        // 1) Fetch all recipe IDs (HTTP only)
        JsonNode idsRoot = Gw2ApiClient.getPublicArray("https://api.guildwars2.com/v2/recipes");

        List<Integer> ids = new ArrayList<>(idsRoot.size());
        for (JsonNode n : idsRoot) {
            int id = n.asInt(0);
            if (id > 0) ids.add(id);
        }

        System.out.println("Global recipes to sync (SAFE): " + ids.size());
        if (ids.isEmpty()) return;

        // SQL: recipes upsert (no ingredients)
        String recipeSql = """
        INSERT INTO recipes
        (recipe_id, type, output_item_id, output_item_count, min_rating, time_to_craft_ms, disciplines, flags, chat_link, guild_ingredients, fetched_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now())
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

        // SQL: ingredients upsert
        String ingredientSql = """
        INSERT INTO recipe_ingredients (recipe_id, item_id, count)
        VALUES (?, ?, ?)
        ON CONFLICT (recipe_id, item_id) DO UPDATE SET count = EXCLUDED.count
        """;

        // Collect items needed (outputs + ingredients) so FKs are satisfied
        Set<Integer> neededItemIds = new HashSet<>();

        // ---------------------------
        // Pass 1: HTTP batch -> parse -> short DB tx to upsert recipes
        // ---------------------------
        record RecipeRow(
                int recipeId,
                String type,
                int outputItem,
                int outputCount,
                int minRating,
                int craftTime,
                String[] discs,
                String[] flags,
                String chatLink,
                String guildIngredientsJson
        ) {}

        int done = 0;

        for (List<Integer> batch : BatchUtils.chunk(ids, batchSize)) {
            String idsParam = BatchUtils.idsParam(batch);
            if (idsParam.isBlank()) continue;

            // 1) HTTP FIRST
            String url = "https://api.guildwars2.com/v2/recipes?ids=" + idsParam;
            JsonNode root = Gw2ApiClient.getPublicArray(url);

            // 2) Parse in memory
            List<RecipeRow> rows = new ArrayList<>(root.size());

            for (JsonNode r : root) {
                if (r == null || r.isNull()) continue;

                int recipeId = r.path("id").asInt(0);
                if (recipeId <= 0) continue;

                String type = r.path("type").asText(null);
                int outputItem = r.path("output_item_id").asInt(0);
                int outputCount = r.path("output_item_count").asInt(0);
                int minRating = r.path("min_rating").asInt(0);
                int craftTime = r.path("time_to_craft_ms").asInt(0);

                if (outputItem > 0) neededItemIds.add(outputItem);

                // disciplines
                JsonNode discsNode = r.get("disciplines");
                String[] discs = (discsNode != null && discsNode.isArray())
                                 ? new String[discsNode.size()]
                                 : new String[0];
                for (int i = 0; i < discs.length; i++) discs[i] = discsNode.get(i).asText();

                // flags
                JsonNode flagsNode = r.get("flags");
                String[] flags = (flagsNode != null && flagsNode.isArray())
                                 ? new String[flagsNode.size()]
                                 : new String[0];
                for (int i = 0; i < flags.length; i++) flags[i] = flagsNode.get(i).asText();

                String chatLink = r.hasNonNull("chat_link") ? r.get("chat_link").asText() : null;
                String guildIngredientsJson = r.hasNonNull("guild_ingredients") ? r.get("guild_ingredients").toString() : null;

                rows.add(new RecipeRow(
                        recipeId, type, outputItem, outputCount, minRating, craftTime,
                        discs, flags, chatLink, guildIngredientsJson
                ));

                // collect ingredient item ids (do NOT insert yet)
                JsonNode ingredients = r.get("ingredients");
                if (ingredients != null && ingredients.isArray()) {
                    for (JsonNode ing : ingredients) {
                        int itemId = ing.path("item_id").asInt(0);
                        if (itemId > 0) neededItemIds.add(itemId);
                    }
                }
            }

            // 3) DB SECOND (short transaction)
            try (Connection con = openConnection();
                 PreparedStatement ps = con.prepareStatement(recipeSql)) {

                con.setAutoCommit(false);

                for (RecipeRow rr : rows) {
                    ps.setInt(1, rr.recipeId());
                    ps.setString(2, rr.type());
                    ps.setInt(3, rr.outputItem());
                    ps.setInt(4, rr.outputCount());
                    ps.setInt(5, rr.minRating());
                    ps.setInt(6, rr.craftTime());
                    ps.setArray(7, con.createArrayOf("text", rr.discs()));
                    ps.setArray(8, con.createArrayOf("text", rr.flags()));
                    ps.setString(9, rr.chatLink());
                    ps.setString(10, rr.guildIngredientsJson());
                    ps.addBatch();
                }

                ps.executeBatch();
                con.commit();
            }

            done += batch.size();
            System.out.println("Global recipes-only progress: " + Math.min(done, ids.size()) + " / " + ids.size());
        }

        System.out.println("Global items needed: " + neededItemIds.size());

        // Ensure items exist before ingredient inserts (FK safety)
        syncItemsByIds(neededItemIds);

        // ---------------------------
        // Pass 2: HTTP batch -> parse -> short DB tx to upsert ingredients
        // ---------------------------
        record IngRow(int recipeId, int itemId, int count) {}

        int ingDone = 0;

        for (List<Integer> batch : BatchUtils.chunk(ids, batchSize)) {
            String idsParam = BatchUtils.idsParam(batch);
            if (idsParam.isBlank()) continue;

            // 1) HTTP FIRST
            String url = "https://api.guildwars2.com/v2/recipes?ids=" + idsParam;
            JsonNode root = Gw2ApiClient.getPublicArray(url);

            // 2) Parse in memory
            List<IngRow> rows = new ArrayList<>();

            for (JsonNode r : root) {
                if (r == null || r.isNull()) continue;

                int recipeId = r.path("id").asInt(0);
                if (recipeId <= 0) continue;

                JsonNode ingredients = r.get("ingredients");
                if (ingredients == null || !ingredients.isArray()) continue;

                for (JsonNode ing : ingredients) {
                    int itemId = ing.path("item_id").asInt(0);
                    int count = ing.path("count").asInt(0);
                    if (itemId <= 0 || count <= 0) continue;

                    rows.add(new IngRow(recipeId, itemId, count));
                }
            }

            // 3) DB SECOND (short transaction)
            try (Connection con = openConnection();
                 PreparedStatement psIng = con.prepareStatement(ingredientSql)) {

                con.setAutoCommit(false);

                int counter = 0;

                for (IngRow ir : rows) {
                    psIng.setInt(1, ir.recipeId());
                    psIng.setInt(2, ir.itemId());
                    psIng.setInt(3, ir.count());
                    psIng.addBatch();

                    if (++counter % 5000 == 0) {
                        psIng.executeBatch();
                        con.commit();
                    }
                }

                psIng.executeBatch();
                con.commit();
            }

            ingDone += batch.size();
            System.out.println("Global ingredients pass progress: " + Math.min(ingDone, ids.size()) + " / " + ids.size());
        }

        System.out.println("✅ Global recipes + items + ingredients synced (SAFE).");
    }

}