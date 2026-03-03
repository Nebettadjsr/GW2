import api.BatchUtils;
import api.Gw2ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import model.BankSlot;
import model.CharacterInfo;
import model.MaterialStack;
import parser.*;
import repo.AppConfig;
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

                if (row.itemId() == null) ps.setNull(2, Types.INTEGER); else ps.setInt(2, row.itemId());
                if (row.count()  == null) ps.setNull(3, Types.INTEGER); else ps.setInt(3, row.count());

                if (row.binding() == null) ps.setNull(4, Types.VARCHAR); else ps.setString(4, row.binding());
                if (row.boundTo() == null) ps.setNull(5, Types.VARCHAR); else ps.setString(5, row.boundTo());

                if (row.charges() == null) ps.setNull(6, Types.INTEGER); else ps.setInt(6, row.charges());

                if (row.statsId() == null) ps.setNull(7, Types.INTEGER); else ps.setInt(7, row.statsId());
                if (row.statsAttrsJson() == null) ps.setNull(8, Types.VARCHAR); else ps.setString(8, row.statsAttrsJson());

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

                if (row.binding() != null) ps.setString(4, row.binding());
                else ps.setNull(4, Types.VARCHAR);

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
            ps.setString(1, c.name());
            ps.setString(2, c.profession());
            ps.setString(3, c.race());
            ps.setString(4, c.gender());

            if (c.level() != null) ps.setInt(5, c.level());
            else ps.setNull(5, Types.INTEGER);

            if (c.createdIso() != null) ps.setString(6, c.createdIso());
            else ps.setNull(6, Types.VARCHAR);

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
                SELECT DISTINCT item_id
                FROM account_bank
                WHERE item_id IS NOT NULL
            """)) {
                while (rs.next()) itemIds.add(rs.getInt(1));
            }

            try (ResultSet rs = st.executeQuery("""
                SELECT DISTINCT item_id
                FROM account_materials
                WHERE item_id IS NOT NULL
            """)) {
                while (rs.next()) itemIds.add(rs.getInt(1));
            }

            try (ResultSet rs = st.executeQuery("""
                SELECT DISTINCT r.output_item_id
                FROM recipes r
                WHERE r.output_item_id IS NOT NULL
            """)) {
                while (rs.next()) itemIds.add(rs.getInt(1));
            }

            try (ResultSet rs = st.executeQuery("""
                SELECT DISTINCT ri.item_id
                FROM recipe_ingredients ri
                WHERE ri.item_id IS NOT NULL
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

        try (Connection con = openConnection();
             PreparedStatement ps = con.prepareStatement(upsertSql)) {

            con.setAutoCommit(false);

            int done = 0;

            for (List<Integer> batch : BatchUtils.chunk(ids, 200)) {
                String idsParam = BatchUtils.idsParam(batch);
                if (idsParam.isBlank()) continue;

                String url = "https://api.guildwars2.com/v2/commerce/prices?ids=" + idsParam;

                HttpResponse<String> res = Gw2ApiClient.getPublicResponse(url);
                int code = res.statusCode();

                // 1) If batch request failed (404 or anything not 200/206) -> retry individually
                if (code == 404) {
                    System.out.println("TP batch HTTP 404. Retrying individually. Example ids=" +
                                               batch.stream().limit(10).toList() + (batch.size() > 10 ? " ..." : ""));

                    for (int id : batch) {
                        JsonNode one = fetchTpSingle(id);
                        if (one == null) continue;

                        TpPrice q = TpPriceParser.parse(one);
                        if (q == null) continue;

                        if (!q.hasMarketData()) upsertTpRow(ps, q.itemId(), null, null, null, null);
                        else upsertTpRow(ps, q.itemId(), q.buyQty(), q.buyUnit(), q.sellQty(), q.sellUnit());
                    }

                    ps.executeBatch();
                    con.commit();

                    done += batch.size();
                    System.out.println("Fetched TP batch: " + Math.min(done, ids.size()) + " / " + ids.size());
                    continue;
                }

                if (code != 200 && code != 206) {
                    throw new RuntimeException("TP price fetch failed: HTTP " + code + " body=" + res.body());
                }

                JsonNode root = Gw2ApiClient.readJson(res.body());

                Set<Integer> returned = new HashSet<>();

                for (JsonNode p : root) {
                    TpPrice q = TpPriceParser.parse(p);
                    if (q == null) continue;

                    int itemId = q.itemId();
                    returned.add(itemId);

                    if (!q.hasMarketData()) upsertTpRow(ps, itemId, null, null, null, null);
                    else upsertTpRow(ps, itemId, q.buyQty(), q.buyUnit(), q.sellQty(), q.sellUnit());
                }

                if (code == 206) {
                    List<Integer> missing = new ArrayList<>();
                    for (int id : batch) if (!returned.contains(id)) missing.add(id);

                    if (!missing.isEmpty()) {
                        System.out.println("TP batch HTTP 206. Missing=" + missing.size() + " -> retry individually.");

                        for (int id : missing) {
                            JsonNode one = fetchTpSingle(id);
                            if (one == null) continue;

                            TpPrice q = TpPriceParser.parse(one);
                            if (q == null) continue;

                            if (!q.hasMarketData()) upsertTpRow(ps, q.itemId(), null, null, null, null);
                            else upsertTpRow(ps, q.itemId(), q.buyQty(), q.buyUnit(), q.sellQty(), q.sellUnit());
                        }
                    }
                }

                ps.executeBatch();
                con.commit();

                done += batch.size();
                System.out.println("Fetched TP batch: " + Math.min(done, ids.size()) + " / " + ids.size());
            }

            System.out.println("✅ TP prices synced.");
        }
    }

    private static void upsertTpRow(PreparedStatement ps, int itemId,
                                    Long buyQty, Integer buyUnit,
                                    Long sellQty, Integer sellUnit) throws SQLException {
        ps.setInt(1, itemId);

        if (buyQty == null) ps.setNull(2, Types.BIGINT); else ps.setLong(2, buyQty);
        if (buyUnit == null) ps.setNull(3, Types.INTEGER); else ps.setInt(3, buyUnit);

        if (sellQty == null) ps.setNull(4, Types.BIGINT); else ps.setLong(4, sellQty);
        if (sellUnit == null) ps.setNull(5, Types.INTEGER); else ps.setInt(5, sellUnit);

        ps.addBatch();
    }


    private static JsonNode fetchTpSingle(int itemId) throws IOException, InterruptedException {
        String url = "https://api.guildwars2.com/v2/commerce/prices/" + itemId;
        HttpResponse<String> res = Gw2ApiClient.getPublicResponse(url);
        if (res.statusCode() != 200) return null;
        return Gw2ApiClient.readJson(res.body());
    }


    // ---------------------------
    // 5) Icons
    // ---------------------------

    public static void syncItemIconUrls() throws Exception {
        List<Integer> ids = new ArrayList<>();

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

        String updateSql = """
        UPDATE items
        SET icon_url = ?
        WHERE item_id = ?
        """;

        try (Connection con = openConnection();
             PreparedStatement psUpdate = con.prepareStatement(updateSql)) {

            con.setAutoCommit(false);

            for (List<Integer> batch : BatchUtils.chunk(ids, 200)) {
                String idsParam = BatchUtils.idsParam(batch);
                if (idsParam.isBlank()) continue;

                String url = "https://api.guildwars2.com/v2/items?ids=" + idsParam;
                JsonNode root = Gw2ApiClient.getPublicArray(url);

                int updatedThisBatch = 0;
                for (JsonNode item : root) {
                    int itemId = item.get("id").asInt();
                    JsonNode iconNode = item.get("icon");
                    if (iconNode == null || iconNode.isNull()) continue;

                    String iconUrl = iconNode.asText();
                    if (iconUrl == null || iconUrl.isBlank()) continue;

                    psUpdate.setString(1, iconUrl);
                    psUpdate.setInt(2, itemId);
                    psUpdate.addBatch();
                    updatedThisBatch++;
                }

                psUpdate.executeBatch();
                con.commit();
                System.out.println("Updated icon_url batch: +" + updatedThisBatch);
            }
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

        int downloaded = 0;
        int skippedNoUrl = 0;

        try (Connection con = openConnection();
             PreparedStatement psSelect = con.prepareStatement(selectSql);
             PreparedStatement psUpdate = con.prepareStatement(updateSql)) {

            con.setAutoCommit(false);

            try (ResultSet rs = psSelect.executeQuery()) {
                while (rs.next()) {
                    int itemId = rs.getInt("item_id");
                    String iconUrl = rs.getString("icon_url");

                    if (iconUrl == null || iconUrl.isBlank()) {
                        skippedNoUrl++;
                        continue;
                    }

                    Path target = itemsDir.resolve(itemId + ".png");

                    if (Files.exists(target) && Files.size(target) > 0) {
                        psUpdate.setString(1, target.toString());
                        psUpdate.setInt(2, itemId);
                        psUpdate.addBatch();
                        continue;
                    }

                    HttpResponse<byte[]> res = Gw2ApiClient.getBytesResponse(iconUrl);
                    if (res.statusCode() != 200 || res.body() == null || res.body().length == 0) {
                        System.out.println("Icon download failed item " + itemId + " HTTP " + res.statusCode());
                        continue;
                    }

                    Path tmp = target.resolveSibling(itemId + ".png.tmp");
                    Files.write(tmp, res.body(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

                    psUpdate.setString(1, target.toString());
                    psUpdate.setInt(2, itemId);
                    psUpdate.addBatch();

                    downloaded++;
                    if (downloaded % 100 == 0) {
                        psUpdate.executeBatch();
                        con.commit();
                        System.out.println("Downloaded icons: " + downloaded);
                    }
                }
            }

            psUpdate.executeBatch();
            con.commit();
        }

        System.out.println("✅ Item icons synced. Downloaded=" + downloaded + " skippedNoUrl=" + skippedNoUrl);
    }

// -------- Initial fill helpers --------

    public static void syncItemsByIds(Set<Integer> itemIds) throws Exception {
        if (itemIds == null || itemIds.isEmpty()) return;

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


        try (Connection con = openConnection();
             PreparedStatement ps = con.prepareStatement(itemSql)) {

            con.setAutoCommit(false);

            for (List<Integer> batch : BatchUtils.chunk(idsList, 200)) {
                String idsParam = BatchUtils.idsParam(batch);
                if (idsParam.isBlank()) continue;

                String url = "https://api.guildwars2.com/v2/items?ids=" + idsParam;
                JsonNode root = Gw2ApiClient.getPublicArray(url);

                for (JsonNode it : root) {
                    int itemId = it.get("id").asInt();
                    String name = it.hasNonNull("name") ? it.get("name").asText() : null;
                    String type = it.hasNonNull("type") ? it.get("type").asText() : null;
                    String rarity = it.hasNonNull("rarity") ? it.get("rarity").asText() : null;
                    int vendorValue = it.hasNonNull("vendor_value") ? it.get("vendor_value").asInt() : 0;

                    ps.setInt(1, itemId);
                    ps.setString(2, name);
                    ps.setString(3, type);
                    ps.setString(4, rarity);
                    ps.setInt(5, vendorValue);
                    ps.addBatch();
                }

                ps.executeBatch();
                con.commit();
            }
        }
    }

    public static void syncAllRecipesGlobalSafe() throws Exception {

        final int batchSize = 200;

        // 1) Fetch all recipe IDs
        JsonNode idsRoot = Gw2ApiClient.getPublicArray("https://api.guildwars2.com/v2/recipes");

        List<Integer> ids = new ArrayList<>(idsRoot.size());
        for (JsonNode n : idsRoot) {
            int id = n.asInt(0);
            if (id > 0) ids.add(id);
        }

        System.out.println("Global recipes to sync (SAFE): " + ids.size());
        if (ids.isEmpty()) return;

        // Upsert recipes (NO ingredients here)
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

        // We will collect all item IDs needed (outputs + ingredients)
        Set<Integer> neededItemIds = new HashSet<>();

        // ---------------------------
        // Pass 1: recipes only + collect all item ids
        // ---------------------------
        try (Connection con = openConnection();
             PreparedStatement psRecipe = con.prepareStatement(recipeSql)) {

            con.setAutoCommit(false);

            int done = 0;

            for (List<Integer> batch : BatchUtils.chunk(ids, batchSize)) {
                String idsParam = BatchUtils.idsParam(batch);
                if (idsParam.isBlank()) continue;

                String url = "https://api.guildwars2.com/v2/recipes?ids=" + idsParam;
                JsonNode root = Gw2ApiClient.getPublicArray(url);

                for (JsonNode r : root) {
                    int recipeId    = r.path("id").asInt(0);
                    if (recipeId <= 0) continue;

                    String type     = r.path("type").asText(null);
                    int outputItem  = r.path("output_item_id").asInt(0);
                    int outputCount = r.path("output_item_count").asInt(0);
                    int minRating   = r.path("min_rating").asInt(0);
                    int craftTime   = r.path("time_to_craft_ms").asInt(0);

                    if (outputItem > 0) neededItemIds.add(outputItem);

                    // disciplines text[]
                    JsonNode discsNode = r.get("disciplines");
                    String[] discs = (discsNode != null && discsNode.isArray())
                                     ? new String[discsNode.size()]
                                     : new String[0];
                    for (int d = 0; d < discs.length; d++) discs[d] = discsNode.get(d).asText();

                    // flags text[]
                    JsonNode flagsNode = r.get("flags");
                    String[] flags = (flagsNode != null && flagsNode.isArray())
                                     ? new String[flagsNode.size()]
                                     : new String[0];
                    for (int f = 0; f < flags.length; f++) flags[f] = flagsNode.get(f).asText();

                    String chatLink = r.hasNonNull("chat_link") ? r.get("chat_link").asText() : null;
                    String guildIngredientsJson = r.hasNonNull("guild_ingredients") ? r.get("guild_ingredients").toString() : null;

                    psRecipe.setInt(1, recipeId);
                    psRecipe.setString(2, type);
                    psRecipe.setInt(3, outputItem);
                    psRecipe.setInt(4, outputCount);
                    psRecipe.setInt(5, minRating);
                    psRecipe.setInt(6, craftTime);
                    psRecipe.setArray(7, con.createArrayOf("text", discs));
                    psRecipe.setArray(8, con.createArrayOf("text", flags));
                    psRecipe.setString(9, chatLink);
                    psRecipe.setString(10, guildIngredientsJson);
                    psRecipe.addBatch();

                    // collect ingredient item ids (but DO NOT insert yet)
                    JsonNode ingredients = r.get("ingredients");
                    if (ingredients != null && ingredients.isArray()) {
                        for (JsonNode ing : ingredients) {
                            int itemId = ing.path("item_id").asInt(0);
                            if (itemId > 0) neededItemIds.add(itemId);
                        }
                    }
                }

                psRecipe.executeBatch();
                con.commit();

                done += batch.size();
                System.out.println("Global recipes-only progress: " + Math.min(done, ids.size()) + " / " + ids.size());
            }
        }

        System.out.println("Global items needed: " + neededItemIds.size());

        // 2) Insert/Upsert ALL needed items (now FK can be satisfied)
        syncItemsByIds(neededItemIds);

        // ---------------------------
        // Pass 2: now insert ingredients safely
        // ---------------------------
        String ingredientSql = """
        INSERT INTO recipe_ingredients (recipe_id, item_id, count)
        VALUES (?, ?, ?)
        ON CONFLICT (recipe_id, item_id) DO UPDATE SET count = EXCLUDED.count
        """;

        try (Connection con = openConnection();
             PreparedStatement psIng = con.prepareStatement(ingredientSql)) {

            con.setAutoCommit(false);

            int counter = 0;
            int done = 0;

            for (List<Integer> batch : BatchUtils.chunk(ids, batchSize)) {
                String idsParam = BatchUtils.idsParam(batch);
                if (idsParam.isBlank()) continue;

                String url = "https://api.guildwars2.com/v2/recipes?ids=" + idsParam;
                JsonNode root = Gw2ApiClient.getPublicArray(url);

                for (JsonNode r : root) {
                    int recipeId = r.path("id").asInt(0);
                    if (recipeId <= 0) continue;

                    JsonNode ingredients = r.get("ingredients");
                    if (ingredients == null || !ingredients.isArray()) continue;

                    for (JsonNode ing : ingredients) {
                        int itemId = ing.path("item_id").asInt(0);
                        int count  = ing.path("count").asInt(0);
                        if (itemId <= 0 || count <= 0) continue;

                        psIng.setInt(1, recipeId);
                        psIng.setInt(2, itemId);
                        psIng.setInt(3, count);
                        psIng.addBatch();

                        if (++counter % 5000 == 0) {
                            psIng.executeBatch();
                            con.commit();
                            System.out.println("Global ingredients inserted: " + counter);
                        }
                    }
                }

                done += batch.size();
                System.out.println("Global ingredients pass progress: " + Math.min(done, ids.size()) + " / " + ids.size());
            }

            psIng.executeBatch();
            con.commit();
        }

        System.out.println("✅ Global recipes + items + ingredients synced (SAFE).");
    }

}