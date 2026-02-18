import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import repo.AppConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public class Gw2DbSync {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---------------------------
    // Helpers (single source of truth)
    // ---------------------------

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(AppConfig.DB_URL, AppConfig.DB_USER, AppConfig.DB_PASS);
    }

    private static HttpRequest.Builder authRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + AppConfig.API_KEY.trim());
    }

    private static HttpRequest.Builder publicRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json");
    }

    // ---------------------------
    // 1) Account sync
    // ---------------------------

    public static void syncAccountBank() throws IOException, InterruptedException, SQLException {
        String url = "https://api.guildwars2.com/v2/account/bank";

        HttpRequest req = authRequest(url).GET().build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

        int code = res.statusCode();
        if (code != 200 && code != 206) {
            throw new RuntimeException("Bank fetch failed: HTTP " + code + " body=" + res.body());
        }

        JsonNode root = MAPPER.readTree(res.body());
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

                ps.setInt(1, slot);

                if (entry == null || entry.isNull()) {
                    ps.setNull(2, Types.INTEGER);
                    ps.setNull(3, Types.INTEGER);
                    ps.setNull(4, Types.VARCHAR);
                    ps.setNull(5, Types.VARCHAR);
                    ps.setNull(6, Types.INTEGER);
                    ps.setNull(7, Types.INTEGER);
                    ps.setNull(8, Types.VARCHAR);
                } else {
                    ps.setInt(2, entry.path("id").asInt());
                    ps.setInt(3, entry.path("count").asInt());

                    JsonNode binding = entry.get("binding");
                    if (binding != null && !binding.isNull()) ps.setString(4, binding.asText());
                    else ps.setNull(4, Types.VARCHAR);

                    JsonNode boundTo = entry.get("bound_to");
                    if (boundTo != null && !boundTo.isNull()) ps.setString(5, boundTo.asText());
                    else ps.setNull(5, Types.VARCHAR);

                    JsonNode charges = entry.get("charges");
                    if (charges != null && !charges.isNull()) ps.setInt(6, charges.asInt());
                    else ps.setNull(6, Types.INTEGER);

                    JsonNode stats = entry.get("stats");
                    if (stats != null && !stats.isNull()) {
                        JsonNode statsId = stats.get("id");
                        if (statsId != null && !statsId.isNull()) ps.setInt(7, statsId.asInt());
                        else ps.setNull(7, Types.INTEGER);

                        JsonNode attrs = stats.get("attributes");
                        if (attrs != null && !attrs.isNull()) ps.setString(8, attrs.toString());
                        else ps.setNull(8, Types.VARCHAR);
                    } else {
                        ps.setNull(7, Types.INTEGER);
                        ps.setNull(8, Types.VARCHAR);
                    }
                }

                ps.addBatch();
            }

            ps.executeBatch();
            con.commit();
        }
    }

    public static void syncAccountMaterials() throws Exception {
        String url = "https://api.guildwars2.com/v2/account/materials";

        HttpRequest req = authRequest(url).GET().build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

        if (res.statusCode() != 200) {
            throw new RuntimeException("Materials fetch failed: HTTP " + res.statusCode() + " body=" + res.body());
        }

        JsonNode root = MAPPER.readTree(res.body());
        if (!root.isArray()) throw new RuntimeException("Unexpected JSON (materials): not an array");

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
                int itemId = entry.path("id").asInt();
                int category = entry.path("category").asInt();
                int count = entry.path("count").asInt();

                ps.setInt(1, itemId);
                ps.setInt(2, category);
                ps.setInt(3, count);

                JsonNode binding = entry.get("binding");
                if (binding != null && !binding.isNull()) ps.setString(4, binding.asText());
                else ps.setNull(4, Types.VARCHAR);

                ps.addBatch();
            }

            ps.executeBatch();
            con.commit();
        }
    }

    public static void syncAccountRecipes() throws Exception {
        String url = "https://api.guildwars2.com/v2/account/recipes";

        HttpRequest req = authRequest(url).GET().build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

        if (res.statusCode() != 200) {
            throw new RuntimeException("Recipes fetch failed: HTTP " + res.statusCode() + " body=" + res.body());
        }

        JsonNode root = MAPPER.readTree(res.body());
        if (!root.isArray()) throw new RuntimeException("Unexpected JSON (recipes): not an array");

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
                ps.setInt(1, idNode.asInt());
                ps.addBatch();
            }

            ps.executeBatch();
            con.commit();
        }
    }

    // ---------------------------
    // 2) Recipes + Ingredients
    // ---------------------------

    public static void syncRecipesAndIngredients() throws Exception {
        List<Integer> recipeIds = new ArrayList<>();

        try (Connection con = openConnection();
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT recipe_id FROM account_recipes")) {

            while (rs.next()) recipeIds.add(rs.getInt(1));
        }

        System.out.println("Recipes to fetch: " + recipeIds.size());
        if (recipeIds.isEmpty()) return;

        String recipeSql = """
        INSERT INTO recipes
        (recipe_id, type, output_item_id, output_item_count, min_rating, time_to_craft_ms, disciplines, fetched_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, now())
        ON CONFLICT (recipe_id) DO UPDATE SET
          type = EXCLUDED.type,
          output_item_id = EXCLUDED.output_item_id,
          output_item_count = EXCLUDED.output_item_count,
          min_rating = EXCLUDED.min_rating,
          time_to_craft_ms = EXCLUDED.time_to_craft_ms,
          disciplines = EXCLUDED.disciplines,
          fetched_at = EXCLUDED.fetched_at
        """;

        String ingredientSql = """
        INSERT INTO recipe_ingredients
        (recipe_id, item_id, count)
        VALUES (?, ?, ?)
        ON CONFLICT (recipe_id, item_id) DO UPDATE SET
          count = EXCLUDED.count
        """;

        try (Connection con = openConnection();
             PreparedStatement psRecipe = con.prepareStatement(recipeSql);
             PreparedStatement psIng = con.prepareStatement(ingredientSql)) {

            con.setAutoCommit(false);

            int batchSize = 200;

            for (int i = 0; i < recipeIds.size(); i += batchSize) {
                List<Integer> batch = recipeIds.subList(i, Math.min(i + batchSize, recipeIds.size()));
                String idsParam = batch.stream().map(String::valueOf).collect(Collectors.joining(","));

                String url = "https://api.guildwars2.com/v2/recipes?ids=" + idsParam;

                HttpRequest req = publicRequest(url).GET().build();
                HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

                int code = res.statusCode();
                if (code != 200 && code != 206) {
                    throw new RuntimeException("Recipe fetch failed: HTTP " + code + " body=" + res.body());
                }

                JsonNode root = MAPPER.readTree(res.body());
                if (!root.isArray()) throw new RuntimeException("Unexpected JSON (recipes batch): " + res.body());

                Set<Integer> returned = new HashSet<>();

                for (JsonNode r : root) {
                    int recipeId = r.get("id").asInt();
                    returned.add(recipeId);
                    addRecipeAndIngredientsToBatch(con, r, psRecipe, psIng);
                }

                if (code == 206) {
                    List<Integer> missing = new ArrayList<>();
                    for (int id : batch) if (!returned.contains(id)) missing.add(id);

                    System.out.println("HTTP 206: missing in batch = " + missing.size());

                    for (int missId : missing) {
                        String u2 = "https://api.guildwars2.com/v2/recipes/" + missId;

                        HttpRequest r2 = publicRequest(u2).GET().build();
                        HttpResponse<String> res2 = CLIENT.send(r2, HttpResponse.BodyHandlers.ofString());

                        if (res2.statusCode() == 200) {
                            JsonNode rr = MAPPER.readTree(res2.body());
                            addRecipeAndIngredientsToBatch(con, rr, psRecipe, psIng);
                            continue;
                        }

                        if (res2.statusCode() == 404) {
                            List<Integer> resolvedRecipeIds = searchRecipesByOutputItem(missId);

                            if (!resolvedRecipeIds.isEmpty()) {
                                System.out.println("Resolved missing id " + missId + " as output item -> recipes " + resolvedRecipeIds);

                                String ids3 = resolvedRecipeIds.stream().map(String::valueOf).collect(Collectors.joining(","));
                                String u3 = "https://api.guildwars2.com/v2/recipes?ids=" + ids3;

                                HttpRequest r3 = publicRequest(u3).GET().build();
                                HttpResponse<String> res3 = CLIENT.send(r3, HttpResponse.BodyHandlers.ofString());

                                if (res3.statusCode() == 200 || res3.statusCode() == 206) {
                                    JsonNode arr = MAPPER.readTree(res3.body());
                                    if (arr.isArray()) {
                                        for (JsonNode rec : arr) {
                                            addRecipeAndIngredientsToBatch(con, rec, psRecipe, psIng);
                                        }
                                    }
                                } else {
                                    System.out.println("Resolved recipes fetch failed for output item " + missId + " HTTP " + res3.statusCode());
                                }
                            } else {
                                System.out.println("Still missing recipe " + missId + " HTTP 404 (no output-search results)");
                            }
                        }
                    }
                }
            }

            psRecipe.executeBatch();
            psIng.executeBatch();
            con.commit();
            System.out.println("✅ Recipes + ingredients synced.");
        }
    }

    private static List<Integer> searchRecipesByOutputItem(int outputItemId) throws Exception {
        String url = "https://api.guildwars2.com/v2/recipes/search?output=" + outputItemId;

        HttpRequest req = publicRequest(url).GET().build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) return List.of();

        JsonNode root = MAPPER.readTree(res.body());
        if (!root.isArray()) return List.of();

        List<Integer> ids = new ArrayList<>();
        for (JsonNode n : root) ids.add(n.asInt());
        return ids;
    }

    private static void addRecipeAndIngredientsToBatch(Connection con,
                                                       JsonNode r,
                                                       PreparedStatement psRecipe,
                                                       PreparedStatement psIng) throws Exception {

        int recipeId    = r.get("id").asInt();
        String type     = r.get("type").asText();
        int outputItem  = r.get("output_item_id").asInt();
        int outputCount = r.get("output_item_count").asInt();
        int minRating   = r.get("min_rating").asInt();
        int craftTime   = r.get("time_to_craft_ms").asInt();

        JsonNode discsNode = r.get("disciplines");
        String[] discs = (discsNode != null && discsNode.isArray())
                         ? new String[discsNode.size()]
                         : new String[0];

        for (int d = 0; d < discs.length; d++) discs[d] = discsNode.get(d).asText();

        psRecipe.setInt(1, recipeId);
        psRecipe.setString(2, type);
        psRecipe.setInt(3, outputItem);
        psRecipe.setInt(4, outputCount);
        psRecipe.setInt(5, minRating);
        psRecipe.setInt(6, craftTime);
        psRecipe.setArray(7, con.createArrayOf("text", discs));
        psRecipe.addBatch();

        JsonNode ingredients = r.get("ingredients");
        if (ingredients != null && ingredients.isArray()) {
            for (JsonNode ing : ingredients) {
                psIng.setInt(1, recipeId);
                psIng.setInt(2, ing.get("item_id").asInt());
                psIng.setInt(3, ing.get("count").asInt());
                psIng.addBatch();
            }
        }
    }

    // ---------------------------
    // 3) Items (metadata)
    // ---------------------------

    public static void syncItems() throws Exception {
        Set<Integer> itemIds = new HashSet<>();

        try (Connection con = openConnection();
             Statement st = con.createStatement()) {

            try (ResultSet rs = st.executeQuery("SELECT DISTINCT item_id FROM account_bank WHERE item_id IS NOT NULL")) {
                while (rs.next()) itemIds.add(rs.getInt(1));
            }

            try (ResultSet rs = st.executeQuery("SELECT DISTINCT item_id FROM account_materials WHERE item_id IS NOT NULL")) {
                while (rs.next()) itemIds.add(rs.getInt(1));
            }

            try (ResultSet rs = st.executeQuery("SELECT DISTINCT output_item_id FROM recipes WHERE output_item_id IS NOT NULL")) {
                while (rs.next()) itemIds.add(rs.getInt(1));
            }

            try (ResultSet rs = st.executeQuery("SELECT DISTINCT item_id FROM recipe_ingredients WHERE item_id IS NOT NULL")) {
                while (rs.next()) itemIds.add(rs.getInt(1));
            }
        }

        System.out.println("Items to fetch: " + itemIds.size());
        if (itemIds.isEmpty()) return;

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

        try (Connection con = openConnection();
             PreparedStatement ps = con.prepareStatement(itemSql)) {

            con.setAutoCommit(false);

            List<Integer> idsList = new ArrayList<>(itemIds);
            int batchSize = 200;

            for (int i = 0; i < idsList.size(); i += batchSize) {
                List<Integer> batch = idsList.subList(i, Math.min(i + batchSize, idsList.size()));
                String idsParam = batch.stream().map(String::valueOf).collect(Collectors.joining(","));

                String url = "https://api.guildwars2.com/v2/items?ids=" + idsParam;

                HttpRequest req = publicRequest(url).GET().build();
                HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

                int code = res.statusCode();
                if (code != 200 && code != 206) {
                    throw new RuntimeException("Items fetch failed: HTTP " + code + " body=" + res.body());
                }

                JsonNode root = MAPPER.readTree(res.body());
                if (!root.isArray()) throw new RuntimeException("Unexpected JSON (items batch): " + res.body());

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

                System.out.println("Fetched items batch: " + i + " / " + idsList.size());
            }

            ps.executeBatch();
            con.commit();
            System.out.println("✅ Items synced.");
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

            final int batchSize = 200;

            for (int i = 0; i < ids.size(); i += batchSize) {
                List<Integer> batch = ids.subList(i, Math.min(i + batchSize, ids.size()))
                        .stream()
                        .filter(x -> x != null && x > 0)
                        .toList();
                if (batch.isEmpty()) continue;
                String idsParam = batch.stream().map(String::valueOf).collect(Collectors.joining(","));

                String url = "https://api.guildwars2.com/v2/commerce/prices?ids=" + idsParam;

                HttpRequest req = publicRequest(url).GET().build();
                HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

                int code = res.statusCode();

// 1) If batch request failed (404 or anything not 200/206) -> retry individually
                if (code == 404) {
                    System.out.println("TP batch HTTP 404. Retrying individually. Example ids=" +
                                               batch.stream().limit(10).toList() + (batch.size() > 10 ? " ..." : ""));

                    for (int id : batch) {
                        JsonNode one = fetchTpSingle(id);
                        if (one == null) {
                            // IMPORTANT: do NOT overwrite DB with NULL. Just skip and keep old value.
                            continue;
                        }

                        JsonNode buys = one.get("buys");
                        JsonNode sells = one.get("sells");

                        // If GW2 says null -> real "no TP data" -> write NULLs
                        if (buys == null || sells == null || buys.isNull() || sells.isNull()) {
                            upsertTpRow(ps, id, null, null, null, null);
                            continue;
                        }

                        long buyQty = buys.path("quantity").asLong();
                        int buyUnit = buys.path("unit_price").asInt();
                        long sellQty = sells.path("quantity").asLong();
                        int sellUnit = sells.path("unit_price").asInt();

                        upsertTpRow(ps, id, buyQty, buyUnit, sellQty, sellUnit);
                    }

                    ps.executeBatch();
                    con.commit();
                    continue;
                }

                if (code != 200 && code != 206) {
                    throw new RuntimeException("TP price fetch failed: HTTP " + code + " body=" + res.body());
                }

// 2) Normal batch parse (200/206)
                JsonNode root = MAPPER.readTree(res.body());
                if (!root.isArray()) throw new RuntimeException("Unexpected JSON (tp_prices batch): " + res.body());

                Set<Integer> returned = new HashSet<>();

                for (JsonNode p : root) {
                    int itemId = p.get("id").asInt();
                    returned.add(itemId);

                    JsonNode buys = p.get("buys");
                    JsonNode sells = p.get("sells");

                    if (buys == null || sells == null || buys.isNull() || sells.isNull()) {
                        // REAL no TP data
                        upsertTpRow(ps, itemId, null, null, null, null);
                        continue;
                    }

                    long buyQty = buys.path("quantity").asLong();
                    int buyUnit = buys.path("unit_price").asInt();
                    long sellQty = sells.path("quantity").asLong();
                    int sellUnit = sells.path("unit_price").asInt();

                    upsertTpRow(ps, itemId, buyQty, buyUnit, sellQty, sellUnit);
                }

// 3) If 206, retry missing individually (instead of writing NULL blindly)
                if (code == 206) {
                    List<Integer> missing = new ArrayList<>();
                    for (int id : batch) if (!returned.contains(id)) missing.add(id);

                    if (!missing.isEmpty()) {
                        System.out.println("TP batch HTTP 206. Missing=" + missing.size() + " -> retry individually.");

                        for (int id : missing) {
                            JsonNode one = fetchTpSingle(id);
                            if (one == null) {
                                // don't overwrite DB
                                continue;
                            }

                            JsonNode buys = one.get("buys");
                            JsonNode sells = one.get("sells");

                            if (buys == null || sells == null || buys.isNull() || sells.isNull()) {
                                upsertTpRow(ps, id, null, null, null, null);
                                continue;
                            }

                            long buyQty = buys.path("quantity").asLong();
                            int buyUnit = buys.path("unit_price").asInt();
                            long sellQty = sells.path("quantity").asLong();
                            int sellUnit = sells.path("unit_price").asInt();

                            upsertTpRow(ps, id, buyQty, buyUnit, sellQty, sellUnit);
                        }
                    }
                }

                ps.executeBatch();
                con.commit();


                System.out.println("Fetched TP batch: " + Math.min(i + batchSize, ids.size()) + " / " + ids.size());
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

    /** Fetch ONE item price safely. Returns null if request totally failed. */
    private static JsonNode fetchTpSingle(int itemId) throws IOException, InterruptedException {
        String url = "https://api.guildwars2.com/v2/commerce/prices/" + itemId;
        HttpRequest req = publicRequest(url).GET().build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) return null;
        return MAPPER.readTree(res.body());
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

            int batchSize = 200;

            for (int i = 0; i < ids.size(); i += batchSize) {
                List<Integer> batch = ids.subList(i, Math.min(i + batchSize, ids.size()));
                String idsParam = batch.stream().map(String::valueOf).collect(Collectors.joining(","));

                String url = "https://api.guildwars2.com/v2/items?ids=" + idsParam;

                HttpRequest req = publicRequest(url).GET().build();
                HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

                int code = res.statusCode();
                if (code != 200 && code != 206) {
                    throw new RuntimeException("Items fetch failed: HTTP " + code + " body=" + res.body());
                }

                JsonNode root = MAPPER.readTree(res.body());
                if (!root.isArray()) throw new RuntimeException("Unexpected JSON (items batch): " + res.body());

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

                System.out.println("Updated icon_url batch: " + (i) + " / " + ids.size() + " (+" + updatedThisBatch + ")");
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

                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(iconUrl))
                            .header("Accept", "image/png,image/*;q=0.9,*/*;q=0.8")
                            .GET()
                            .build();

                    HttpResponse<byte[]> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
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

    public static void syncAllRecipesAndIngredientsGlobal() throws Exception {
        // 1) fetch all recipe IDs
        String idsUrl = "https://api.guildwars2.com/v2/recipes";
        HttpRequest idsReq = publicRequest(idsUrl).GET().build();
        HttpResponse<String> idsRes = CLIENT.send(idsReq, HttpResponse.BodyHandlers.ofString());

        if (idsRes.statusCode() != 200) {
            throw new RuntimeException("All-recipes id fetch failed: HTTP " + idsRes.statusCode() + " body=" + idsRes.body());
        }

        JsonNode idsRoot = MAPPER.readTree(idsRes.body());
        if (!idsRoot.isArray()) throw new RuntimeException("Unexpected JSON (/v2/recipes): " + idsRes.body());

        List<Integer> ids = new ArrayList<>(idsRoot.size());
        for (JsonNode n : idsRoot) ids.add(n.asInt());

        System.out.println("Global recipes to sync: " + ids.size());
        if (ids.isEmpty()) return;

        // 2) upsert SQL (includes flags/chat_link/guild_ingredients if present in your table)
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

        String deleteIngSql = "DELETE FROM recipe_ingredients WHERE recipe_id = ?";
        String ingredientSql = """
        INSERT INTO recipe_ingredients (recipe_id, item_id, count)
        VALUES (?, ?, ?)
        ON CONFLICT (recipe_id, item_id) DO UPDATE SET count = EXCLUDED.count
        """;

        try (Connection con = openConnection();
             PreparedStatement psRecipe = con.prepareStatement(recipeSql);
             PreparedStatement psDelIng = con.prepareStatement(deleteIngSql);
             PreparedStatement psIng = con.prepareStatement(ingredientSql)) {

            con.setAutoCommit(false);

            final int batchSize = 200;
            for (int i = 0; i < ids.size(); i += batchSize) {
                List<Integer> batch = ids.subList(i, Math.min(i + batchSize, ids.size()));
                String idsParam = batch.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));

                String url = "https://api.guildwars2.com/v2/recipes?ids=" + idsParam;
                HttpRequest req = publicRequest(url).GET().build();
                HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

                int code = res.statusCode();
                if (code != 200 && code != 206) {
                    throw new RuntimeException("Global recipe batch fetch failed: HTTP " + code + " body=" + res.body());
                }

                JsonNode root = MAPPER.readTree(res.body());
                if (!root.isArray()) throw new RuntimeException("Unexpected JSON (recipes batch): " + res.body());

                for (JsonNode r : root) {
                    int recipeId    = r.path("id").asInt();
                    String type     = r.path("type").asText(null);
                    int outputItem  = r.path("output_item_id").asInt();
                    int outputCount = r.path("output_item_count").asInt();
                    int minRating   = r.path("min_rating").asInt(0);
                    int craftTime   = r.path("time_to_craft_ms").asInt(0);

                    // disciplines text[]
                    JsonNode discsNode = r.get("disciplines");
                    String[] discs = (discsNode != null && discsNode.isArray())
                                     ? new String[discsNode.size()]
                                     : new String[0];
                    for (int d = 0; d < discs.length; d++) discs[d] = discsNode.get(d).asText();

                    // flags text[] (optional)
                    JsonNode flagsNode = r.get("flags");
                    String[] flags = (flagsNode != null && flagsNode.isArray())
                                     ? new String[flagsNode.size()]
                                     : new String[0];
                    for (int f = 0; f < flags.length; f++) flags[f] = flagsNode.get(f).asText();

                    String chatLink = r.hasNonNull("chat_link") ? r.get("chat_link").asText() : null;

                    // guild_ingredients (optional JSON array/object)
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

                    // IMPORTANT: replace ingredients for this recipe to avoid stale rows
                    psDelIng.setInt(1, recipeId);
                    psDelIng.addBatch();

                    JsonNode ingredients = r.get("ingredients");
                    if (ingredients != null && ingredients.isArray()) {
                        for (JsonNode ing : ingredients) {
                            psIng.setInt(1, recipeId);
                            psIng.setInt(2, ing.path("item_id").asInt());
                            psIng.setInt(3, ing.path("count").asInt());
                            psIng.addBatch();
                        }
                    }
                }

                psRecipe.executeBatch();
                psDelIng.executeBatch();
                psIng.executeBatch();
                con.commit();

                System.out.println("Global recipe sync progress: " + Math.min(i + batchSize, ids.size()) + " / " + ids.size());
            }

            System.out.println("✅ Global recipes + ingredients synced.");
        }
    }

    public static void initialFill(Path iconBaseDir) throws Exception {
        // Assumption: account_recipes already synced (done by InitialSetupService)
        // 1) sync recipes + ingredients (fills recipes + recipe_ingredients)
        //    BUT recipe_ingredients has FK -> items, so we must ensure items exist first.
        //    Therefore we do: recipes only -> items -> ingredients.
        //
        // Your current syncRecipesAndIngredients() does recipes + ingredients together,
        // so we need to split the workflow. Fastest workaround:
        //   - temporarily disable ingredient insert until items exist.
        // We do it by:
        //   A) syncing recipes into recipes table only
        //   B) collecting item ids
        //   C) syncing items
        //   D) syncing ingredients

        // Step A+B+C+D implemented by: syncAccountRecipes -> syncRecipesOnly -> syncItems -> syncIngredientsOnly
        // We'll reuse your existing parsing logic with two tiny helper methods below.

        List<Integer> recipeIds = loadAccountRecipeIds();
        if (recipeIds.isEmpty()) {
            System.out.println("Initial fill: no account recipes found.");
            return;
        }

        // 1) recipes only + collect needed ids + ingredient rows in memory
        Set<Integer> neededItemIds = new HashSet<>();
        List<IngredientRow> ingredientRows = new ArrayList<>();
        upsertRecipesOnlyAndCollect(recipeIds, neededItemIds, ingredientRows);

        // 2) items (must exist before we insert ingredients due to FK)
        syncItemsByIds(neededItemIds);

        // 3) now safe to write recipe_ingredients
        upsertIngredients(ingredientRows);

        // 4) extras
        syncTpPricesRelevant();
        syncItemIconUrls();
        syncItemIconsToDisk(iconBaseDir);

        System.out.println("✅ Initial fill complete.");
    }
// -------- Initial fill helpers --------

    private static class IngredientRow {
        final int recipeId;
        final int itemId;
        final int count;
        IngredientRow(int recipeId, int itemId, int count) {
            this.recipeId = recipeId;
            this.itemId = itemId;
            this.count = count;
        }
    }

    private static List<Integer> loadAccountRecipeIds() throws SQLException {
        List<Integer> ids = new ArrayList<>();
        try (Connection con = openConnection();
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT recipe_id FROM account_recipes ORDER BY recipe_id")) {
            while (rs.next()) ids.add(rs.getInt(1));
        }
        return ids;
    }

    private static void upsertRecipesOnlyAndCollect(
            List<Integer> recipeIds,
            Set<Integer> neededItemIds,
            List<IngredientRow> ingredientRows
    ) throws Exception {

        String recipeSql = """
        INSERT INTO recipes
        (recipe_id, type, output_item_id, output_item_count, min_rating, time_to_craft_ms, disciplines, fetched_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, now())
        ON CONFLICT (recipe_id) DO UPDATE SET
          type = EXCLUDED.type,
          output_item_id = EXCLUDED.output_item_id,
          output_item_count = EXCLUDED.output_item_count,
          min_rating = EXCLUDED.min_rating,
          time_to_craft_ms = EXCLUDED.time_to_craft_ms,
          disciplines = EXCLUDED.disciplines,
          fetched_at = EXCLUDED.fetched_at
        """;

        try (Connection con = openConnection();
             PreparedStatement psRecipe = con.prepareStatement(recipeSql)) {

            con.setAutoCommit(false);
            final int batchSize = 200;

            for (int i = 0; i < recipeIds.size(); i += batchSize) {
                List<Integer> batch = recipeIds.subList(i, Math.min(i + batchSize, recipeIds.size()));
                String idsParam = batch.stream().map(String::valueOf).collect(Collectors.joining(","));
                String url = "https://api.guildwars2.com/v2/recipes?ids=" + idsParam;

                HttpRequest req = publicRequest(url).GET().build();
                HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

                int code = res.statusCode();
                if (code != 200 && code != 206) {
                    throw new RuntimeException("Recipe fetch failed: HTTP " + code + " body=" + res.body());
                }

                JsonNode root = MAPPER.readTree(res.body());
                if (!root.isArray()) throw new RuntimeException("Unexpected JSON (recipes batch): " + res.body());

                for (JsonNode r : root) {
                    int recipeId    = r.get("id").asInt();
                    String type     = r.path("type").asText(null);
                    int outputItem  = r.path("output_item_id").asInt();
                    int outputCount = r.path("output_item_count").asInt();
                    int minRating   = r.path("min_rating").asInt(0);
                    int craftTime   = r.path("time_to_craft_ms").asInt(0);

                    if (outputItem > 0) neededItemIds.add(outputItem);

                    JsonNode discsNode = r.get("disciplines");
                    String[] discs = (discsNode != null && discsNode.isArray())
                            ? new String[discsNode.size()]
                            : new String[0];
                    for (int d = 0; d < discs.length; d++) discs[d] = discsNode.get(d).asText();

                    psRecipe.setInt(1, recipeId);
                    psRecipe.setString(2, type);
                    psRecipe.setInt(3, outputItem);
                    psRecipe.setInt(4, outputCount);
                    psRecipe.setInt(5, minRating);
                    psRecipe.setInt(6, craftTime);
                    psRecipe.setArray(7, con.createArrayOf("text", discs));
                    psRecipe.addBatch();

                    JsonNode ingredients = r.get("ingredients");
                    if (ingredients != null && ingredients.isArray()) {
                        for (JsonNode ing : ingredients) {
                            int itemId = ing.path("item_id").asInt();
                            int count  = ing.path("count").asInt();
                            if (itemId > 0) {
                                neededItemIds.add(itemId);
                                ingredientRows.add(new IngredientRow(recipeId, itemId, count));
                            }
                        }
                    }
                }

                psRecipe.executeBatch();
                con.commit();
                System.out.println("Recipes-only batch: " + Math.min(i + batchSize, recipeIds.size()) + " / " + recipeIds.size());
            }
        }
    }

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
        final int batchSize = 200;

        try (Connection con = openConnection();
             PreparedStatement ps = con.prepareStatement(itemSql)) {

            con.setAutoCommit(false);

            for (int i = 0; i < idsList.size(); i += batchSize) {
                List<Integer> batch = idsList.subList(i, Math.min(i + batchSize, idsList.size()));
                String idsParam = batch.stream().map(String::valueOf).collect(Collectors.joining(","));

                String url = "https://api.guildwars2.com/v2/items?ids=" + idsParam;
                HttpRequest req = publicRequest(url).GET().build();
                HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

                int code = res.statusCode();
                if (code != 200 && code != 206) {
                    throw new RuntimeException("Items fetch failed: HTTP " + code + " body=" + res.body());
                }

                JsonNode root = MAPPER.readTree(res.body());
                if (!root.isArray()) throw new RuntimeException("Unexpected JSON (items batch): " + res.body());

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
                System.out.println("Items batch: " + Math.min(i + batchSize, idsList.size()) + " / " + idsList.size());
            }
        }
    }

    private static void upsertIngredients(List<IngredientRow> rows) throws SQLException {
        if (rows.isEmpty()) return;

        String ingredientSql = """
        INSERT INTO recipe_ingredients (recipe_id, item_id, count)
        VALUES (?, ?, ?)
        ON CONFLICT (recipe_id, item_id) DO UPDATE SET count = EXCLUDED.count
        """;

        try (Connection con = openConnection();
             PreparedStatement ps = con.prepareStatement(ingredientSql)) {

            con.setAutoCommit(false);

            int n = 0;
            for (IngredientRow r : rows) {
                ps.setInt(1, r.recipeId);
                ps.setInt(2, r.itemId);
                ps.setInt(3, r.count);
                ps.addBatch();

                if (++n % 2000 == 0) {
                    ps.executeBatch();
                    con.commit();
                }
            }

            ps.executeBatch();
            con.commit();
        }
    }

    public static void syncAllRecipesGlobalSafe() throws Exception {

        // 1) Fetch all recipe IDs
        String idsUrl = "https://api.guildwars2.com/v2/recipes";
        HttpRequest idsReq = publicRequest(idsUrl).GET().build();
        HttpResponse<String> idsRes = CLIENT.send(idsReq, HttpResponse.BodyHandlers.ofString());

        if (idsRes.statusCode() != 200) {
            throw new RuntimeException("All-recipes id fetch failed: HTTP " + idsRes.statusCode() + " body=" + idsRes.body());
        }

        JsonNode idsRoot = MAPPER.readTree(idsRes.body());
        if (!idsRoot.isArray()) throw new RuntimeException("Unexpected JSON (/v2/recipes): " + idsRes.body());

        List<Integer> ids = new ArrayList<>(idsRoot.size());
        for (JsonNode n : idsRoot) ids.add(n.asInt());

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

        // Pass 1: recipes only + collect all item ids
        try (Connection con = openConnection();
             PreparedStatement psRecipe = con.prepareStatement(recipeSql)) {

            con.setAutoCommit(false);

            final int batchSize = 200;
            for (int i = 0; i < ids.size(); i += batchSize) {

                List<Integer> batch = ids.subList(i, Math.min(i + batchSize, ids.size()));
                String idsParam = batch.stream().map(String::valueOf).collect(Collectors.joining(","));

                String url = "https://api.guildwars2.com/v2/recipes?ids=" + idsParam;
                HttpRequest req = publicRequest(url).GET().build();
                HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

                int code = res.statusCode();
                if (code != 200 && code != 206) {
                    throw new RuntimeException("Global recipe batch fetch failed: HTTP " + code + " body=" + res.body());
                }

                JsonNode root = MAPPER.readTree(res.body());
                if (!root.isArray()) throw new RuntimeException("Unexpected JSON (recipes batch): " + res.body());

                for (JsonNode r : root) {
                    int recipeId    = r.path("id").asInt();
                    String type     = r.path("type").asText(null);
                    int outputItem  = r.path("output_item_id").asInt();
                    int outputCount = r.path("output_item_count").asInt();
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
                            int itemId = ing.path("item_id").asInt();
                            if (itemId > 0) neededItemIds.add(itemId);
                        }
                    }
                }

                psRecipe.executeBatch();
                con.commit();

                System.out.println("Global recipes-only progress: " + Math.min(i + batchSize, ids.size()) + " / " + ids.size());
            }
        }

        System.out.println("Global items needed: " + neededItemIds.size());

        // 2) Insert/Upsert ALL needed items (now FK can be satisfied)
        syncItemsByIds(neededItemIds);

        // 3) Pass 2: now insert ingredients safely
        String ingredientSql = """
        INSERT INTO recipe_ingredients (recipe_id, item_id, count)
        VALUES (?, ?, ?)
        ON CONFLICT (recipe_id, item_id) DO UPDATE SET count = EXCLUDED.count
        """;

        try (Connection con = openConnection();
             PreparedStatement psIng = con.prepareStatement(ingredientSql)) {

            con.setAutoCommit(false);

            final int batchSize = 200;
            int counter = 0;

            for (int i = 0; i < ids.size(); i += batchSize) {

                List<Integer> batch = ids.subList(i, Math.min(i + batchSize, ids.size()));
                String idsParam = batch.stream().map(String::valueOf).collect(Collectors.joining(","));

                String url = "https://api.guildwars2.com/v2/recipes?ids=" + idsParam;
                HttpRequest req = publicRequest(url).GET().build();
                HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

                int code = res.statusCode();
                if (code != 200 && code != 206) {
                    throw new RuntimeException("Global recipe batch fetch (ingredients) failed: HTTP " + code + " body=" + res.body());
                }

                JsonNode root = MAPPER.readTree(res.body());
                if (!root.isArray()) throw new RuntimeException("Unexpected JSON (recipes batch): " + res.body());

                for (JsonNode r : root) {
                    int recipeId = r.path("id").asInt();
                    JsonNode ingredients = r.get("ingredients");
                    if (ingredients == null || !ingredients.isArray()) continue;

                    for (JsonNode ing : ingredients) {
                        psIng.setInt(1, recipeId);
                        psIng.setInt(2, ing.path("item_id").asInt());
                        psIng.setInt(3, ing.path("count").asInt());
                        psIng.addBatch();

                        if (++counter % 5000 == 0) {
                            psIng.executeBatch();
                            con.commit();
                            System.out.println("Global ingredients inserted: " + counter);
                        }
                    }
                }
            }

            psIng.executeBatch();
            con.commit();
        }

        System.out.println("✅ Global recipes + items + ingredients synced (SAFE).");
    }


}