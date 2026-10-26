package sync;

import api.tp.TpPriceApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import repo.Db;
import repo.EnvConfig;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-COV-002: the real {@code ItemSync}, {@code RecipeSync} and {@code TpSync} orchestration
 * (fetch, parse, batch, publish, failure) against a production-migration disposable schema, with a
 * deterministic in-memory upstream substituted through the smallest package-private seams. No live
 * GW2 call, no key, no write outside the isolated schema. Expected rows are written out literally
 * from the fixture JSON, not derived by calling the code under test.
 */
class SyncFetchPublicationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String schema = "test_sync_fetch_" + UUID.randomUUID().toString().replace("-", "");
    private Connection admin;
    private final List<String> urls = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        admin = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"));
        try (Statement st = admin.createStatement()) {
            st.execute("CREATE SCHEMA " + schema);
        }
        admin.setSchema(schema);
        Flyway.configure()
                .dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();
        System.setProperty(Db.TEST_SCHEMA_PROPERTY, schema);
    }

    @AfterEach
    void tearDown() throws Exception {
        ItemSync.fetcher = api.Gw2ApiClient::getPublicArray;
        RecipeSync.fetcher = api.Gw2ApiClient::getPublicArray;
        TpSync.upstream = new TpSync.TpUpstream() {
            public TpPriceApi.BatchResult fetchBatch(List<Integer> b) throws Exception { return TpPriceApi.fetchBatch(b); }
            public JsonNode fetchSingle(int id) throws Exception { return TpPriceApi.fetchSingle(id); }
            public Set<Integer> fetchAllTradeableItemIds() throws Exception { return TpPriceApi.fetchAllTradeableItemIds(); }
        };
        System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
        try (Statement st = admin.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        } finally {
            admin.close();
        }
    }

    // ------------------------------------------------------------------ helpers

    private void exec(String sql) throws Exception {
        try (Statement st = admin.createStatement()) {
            st.execute(sql);
        }
    }

    private String scalar(String sql) throws Exception {
        try (Statement st = admin.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    /** Makes any insert of {@code table} for the given id fail inside the database. */
    private void failInsertOf(String table, String idColumn, int id) throws Exception {
        exec("CREATE FUNCTION reject_" + table + "() RETURNS trigger AS $$ BEGIN "
                + "IF NEW." + idColumn + " = " + id + " THEN RAISE EXCEPTION 'rejected'; END IF; RETURN NEW; END; $$ LANGUAGE plpgsql");
        exec("CREATE TRIGGER reject_" + table + " BEFORE INSERT ON " + table
                + " FOR EACH ROW EXECUTE FUNCTION reject_" + table + "()");
    }

    private static JsonNode json(String s) {
        try {
            return JSON.readTree(s);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static Set<Integer> range(int from, int toInclusive) {
        return IntStream.rangeClosed(from, toInclusive).boxed().collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String idsOf(String url) {
        return url.substring(url.indexOf("ids=") + 4);
    }

    private static String itemJson(int id) {
        return "{\"id\":" + id + ",\"name\":\"Item " + id + "\",\"type\":\"Trophy\",\"rarity\":\"Basic\",\"vendor_value\":" + id + "}";
    }

    // ------------------------------------------------------------------ ItemSync

    @Test
    void itemSync_emptyAndNullInputMakeNoUpstreamCall() throws Exception {
        ItemSync.fetcher = url -> { throw new AssertionError("no fetch expected"); };
        ItemSync.syncItemsByIds(Set.of());
        ItemSync.syncItemsByIds(null);
        assertEquals("0", scalar("SELECT count(*) FROM items"));
    }

    @Test
    void itemSync_parsesMetadataSkipsMalformedEntriesAndKeepsExistingIconOnUpsert() throws Exception {
        exec("INSERT INTO items (item_id, name, icon_url) VALUES (11, 'Old', 'https://icon/old.png')");
        ItemSync.fetcher = url -> {
            urls.add(url);
            return json("""
                [{"id":10,"name":"Copper Ore","type":"CraftingMaterial","rarity":"Basic","vendor_value":8,"icon":"https://icon/10.png"},
                 {"id":11,"name":"Renamed","type":"Weapon","rarity":"Rare"},
                 {"id":10,"name":"Copper Ore Dup","type":"CraftingMaterial","rarity":"Basic","vendor_value":9,"icon":"https://icon/10b.png"},
                 {"id":0,"name":"zero id"}, {"name":"no id"}, null, {"id":-4}, "scalar"]
                """);
        };

        ItemSync.syncItemsByIds(new LinkedHashSet<>(List.of(10, 11)));

        assertEquals(List.of("https://api.guildwars2.com/v2/items?ids=10,11"), urls);
        assertEquals("2", scalar("SELECT count(*) FROM items"));
        // duplicate id 10: the later entry wins
        assertEquals("Copper Ore Dup|CraftingMaterial|Basic|9|https://icon/10b.png",
                scalar("SELECT concat_ws('|', name, type, rarity, vendor_value, icon_url) FROM items WHERE item_id=10"));
        // 11: absent vendor_value stored as 0, absent icon keeps the existing one
        assertEquals("Renamed|Weapon|Rare|0|https://icon/old.png",
                scalar("SELECT concat_ws('|', name, type, rarity, vendor_value, icon_url) FROM items WHERE item_id=11"));
        assertEquals("2", scalar("SELECT count(DISTINCT fetched_at) + 1 FROM items"), "one run timestamp for the batch");
    }

    @Test
    void itemSync_transportFailureKeepsCommittedBatchesAndLaterBatchUnwritten() throws Exception {
        ItemSync.fetcher = url -> {
            urls.add(url);
            if (urls.size() == 2) throw new IOException("connection reset");
            String body = java.util.Arrays.stream(idsOf(url).split(",")).map(s -> itemJson(Integer.parseInt(s)))
                    .collect(Collectors.joining(",", "[", "]"));
            return json(body);
        };

        IOException failure = assertThrows(IOException.class, () -> ItemSync.syncItemsByIds(range(1, 250)));
        assertEquals("connection reset", failure.getMessage());
        assertEquals(2, urls.size());
        assertEquals("200", scalar("SELECT count(*) FROM items"));
        assertEquals("1", scalar("SELECT min(item_id) FROM items"));
        assertEquals("200", scalar("SELECT max(item_id) FROM items"));
    }

    @Test
    void itemSync_databaseFailureRollsBackTheWholeBatch() throws Exception {
        failInsertOf("items", "item_id", 999);
        ItemSync.fetcher = url -> json("[" + itemJson(5) + "," + itemJson(999) + "]");

        assertThrows(java.sql.SQLException.class, () -> ItemSync.syncItemsByIds(new LinkedHashSet<>(List.of(5, 999))));
        assertEquals("0", scalar("SELECT count(*) FROM items"));
    }

    // ------------------------------------------------------------------ RecipeSync

    private static JsonNode recipesFixture(String url) {
        if (url.endsWith("/v2/recipes")) return json("[1, 2, 2, 0]");
        if (url.contains("/v2/recipes?ids=")) {
            return json("""
                [{"id":1,"type":"Refinement","output_item_id":20,"output_item_count":2,"min_rating":100,"time_to_craft_ms":1000,
                  "disciplines":["Weaponsmith"],"flags":["AutoLearned"],"chat_link":"[&A]",
                  "ingredients":[{"item_id":10,"count":3},{"item_id":10,"count":4},{"currency_id":1,"count":9},{"item_id":11,"count":0}]},
                 {"id":2,"type":"Weapon","output_item_id":21,"output_item_count":1,"min_rating":0,"time_to_craft_ms":0,
                  "disciplines":[],"ingredients":[{"item_id":11,"count":1}]},
                 {"id":0}, null]
                """);
        }
        if (url.contains("/v2/items?ids=")) {
            return json(Set.of(10, 11, 20, 21).stream().map(SyncFetchPublicationTest::itemJson)
                    .collect(Collectors.joining(",", "[", "]")));
        }
        throw new AssertionError("unexpected " + url);
    }

    @Test
    void recipeSync_changedCatalogPublishesRecipesItemsIngredientsAndRemovesDeleted() throws Exception {
        exec("INSERT INTO items (item_id, name) VALUES (30, 'stale')");
        exec("INSERT INTO recipes (recipe_id, type, output_item_id) VALUES (3, 'Old', 30)");
        exec("INSERT INTO recipe_ingredients (recipe_id, item_id, count) VALUES (3, 30, 1)");
        RecipeSync.fetcher = url -> { urls.add(url); return recipesFixture(url); };
        ItemSync.fetcher = RecipeSync.fetcher::get;

        assertTrue(RecipeSync.syncAllRecipesGlobalSafe());

        assertEquals("2", scalar("SELECT count(*) FROM recipes"));
        assertNull(scalar("SELECT 1 FROM recipes WHERE recipe_id=3"));
        assertNull(scalar("SELECT 1 FROM recipe_ingredients WHERE recipe_id=3"));
        assertEquals("Refinement|20|2|100|1000|[&A]|{Weaponsmith}|{AutoLearned}",
                scalar("SELECT concat_ws('|', type, output_item_id, output_item_count, min_rating, time_to_craft_ms, chat_link, disciplines, flags) FROM recipes WHERE recipe_id=1"));
        // Item ingredients only; duplicate (recipe,item) pairs collapse to the later count
        assertEquals("1:10:4,2:11:1",
                scalar("SELECT string_agg(recipe_id||':'||item_id||':'||count, ',' ORDER BY recipe_id) FROM recipe_ingredients"));
        assertEquals("10,11,20,21,30", scalar("SELECT string_agg(item_id::text, ',' ORDER BY item_id) FROM items"));
        long detailCalls = urls.stream().filter(url -> url.contains("/v2/recipes?ids=")).count();
        System.out.println("INVESTIGATION_RECIPE_DETAIL_CALLS=" + detailCalls);
        assertEquals(1, detailCalls,
                "Measure recipe payload fetch count while preserving exact SQL publication assertions");
    }

    @Test
    void recipeSync_unchangedCatalogFetchesNothingFurtherAndWritesNothing() throws Exception {
        exec("INSERT INTO recipes (recipe_id, type) VALUES (1, 'Keep'), (2, 'Keep')");
        exec("INSERT INTO recipe_catalog_publication (complete) VALUES (TRUE)");
        RecipeSync.fetcher = url -> {
            urls.add(url);
            if (url.endsWith("/v2/recipes")) return json("[1,2]");
            throw new AssertionError("no detail fetch expected: " + url);
        };

        assertFalse(RecipeSync.syncAllRecipesGlobalSafe());
        assertEquals(1, urls.size());
        assertEquals("Keep,Keep", scalar("SELECT string_agg(type, ',') FROM recipes"));
    }

    @Test
    void recipeSync_malformedIdListAndTransportFailureLeaveDatabaseUntouched() throws Exception {
        RecipeSync.fetcher = url -> json("{\"text\":\"error\"}");
        assertThrows(IllegalArgumentException.class, RecipeSync::syncAllRecipesGlobalSafe);

        RecipeSync.fetcher = url -> { throw new IOException("timeout"); };
        assertThrows(IOException.class, RecipeSync::syncAllRecipesGlobalSafe);

        RecipeSync.fetcher = url -> {
            if (url.endsWith("/v2/recipes")) return json("[1]");
            throw new IOException("detail timeout");
        };
        assertThrows(IOException.class, RecipeSync::syncAllRecipesGlobalSafe);
        assertEquals("0", scalar("SELECT count(*) FROM recipes"));
    }

    @Test
    void recipeSync_databaseFailureRollsBackTheBatchAndSkipsLaterSteps() throws Exception {
        failInsertOf("recipes", "recipe_id", 2);
        RecipeSync.fetcher = url -> { urls.add(url); return recipesFixture(url); };
        ItemSync.fetcher = url -> { throw new AssertionError("items must not be fetched after a failed publish"); };

        assertThrows(java.sql.SQLException.class, RecipeSync::syncAllRecipesGlobalSafe);
        assertEquals("0", scalar("SELECT count(*) FROM recipes"));
        assertEquals("0", scalar("SELECT count(*) FROM recipe_ingredients"));
    }

    @Test
    void recipeSync_itemFetchFailureAfterRecipePublicationLeavesNoIngredientsOrDeletions() throws Exception {
        exec("INSERT INTO recipes (recipe_id, type) VALUES (3, 'Old')");
        RecipeSync.fetcher = url -> recipesFixture(url);
        ItemSync.fetcher = url -> { throw new IOException("items down"); };

        assertThrows(IOException.class, RecipeSync::syncAllRecipesGlobalSafe);
        assertEquals("1,2,3", scalar("SELECT string_agg(recipe_id::text, ',' ORDER BY recipe_id) FROM recipes"));
        assertEquals("0", scalar("SELECT count(*) FROM recipe_ingredients"));
    }

    @Test
    void recipeSync_retryWithUnchangedIdsAfterItemFailureCompletesItemsAndIngredients() throws Exception {
        RecipeSync.fetcher = url -> recipesFixture(url);
        ItemSync.fetcher = url -> { throw new IOException("items down"); };
        assertThrows(IOException.class, RecipeSync::syncAllRecipesGlobalSafe);
        assertEquals("1,2", scalar("SELECT string_agg(recipe_id::text, ',' ORDER BY recipe_id) FROM recipes"));
        assertEquals("0", scalar("SELECT count(*) FROM items"));

        ItemSync.fetcher = RecipeSync.fetcher::get;
        assertTrue(RecipeSync.syncAllRecipesGlobalSafe(), "incomplete catalog must report changed so the graph refreshes");

        assertEquals("1:10:4,2:11:1",
                scalar("SELECT string_agg(recipe_id||':'||item_id||':'||count, ',' ORDER BY recipe_id) FROM recipe_ingredients"));
        assertEquals("10,11,20,21", scalar("SELECT string_agg(item_id::text, ',' ORDER BY item_id) FROM items"));

        urls.clear();
        RecipeSync.fetcher = url -> { urls.add(url); if (url.endsWith("/v2/recipes")) return json("[1,2]"); throw new AssertionError(url); };
        assertFalse(RecipeSync.syncAllRecipesGlobalSafe(), "completed catalog takes the fast path");
        assertEquals(1, urls.size());
    }

    @Test
    void recipeSync_retryWithUnchangedIdsAfterIngredientFailureCompletesIngredients() throws Exception {
        RecipeSync.fetcher = url -> recipesFixture(url);
        ItemSync.fetcher = RecipeSync.fetcher::get;
        failInsertOf("recipe_ingredients", "recipe_id", 2);
        assertThrows(java.sql.SQLException.class, RecipeSync::syncAllRecipesGlobalSafe);
        assertEquals("1,2", scalar("SELECT string_agg(recipe_id::text, ',' ORDER BY recipe_id) FROM recipes"));
        assertEquals("0", scalar("SELECT count(*) FROM recipe_ingredients WHERE recipe_id=2"));

        exec("DROP TRIGGER reject_recipe_ingredients ON recipe_ingredients");
        assertTrue(RecipeSync.syncAllRecipesGlobalSafe());
        assertEquals("1:10:4,2:11:1",
                scalar("SELECT string_agg(recipe_id||':'||item_id||':'||count, ',' ORDER BY recipe_id) FROM recipe_ingredients"));
    }

    // ------------------------------------------------------------------ TpSync

    @Test
    void recipeSync_missingOutputAndInvalidIngredientsDoNotRequestItemZero() throws Exception {
        RecipeSync.fetcher = url -> url.endsWith("/v2/recipes") ? json("[1]")
                : json("[{\"id\":1,\"ingredients\":[{\"item_id\":0,\"count\":1}]}]");
        ItemSync.fetcher = url -> { throw new AssertionError("no valid item IDs to fetch: " + url); };

        assertTrue(RecipeSync.syncAllRecipesGlobalSafe());
        assertEquals("0", scalar("SELECT count(*) FROM items"));
        assertEquals("0", scalar("SELECT count(*) FROM recipe_ingredients"));
        assertEquals("0", scalar("SELECT output_item_id FROM recipes WHERE recipe_id=1"));
    }

    @Test
    void tpSync_malformedSinglePayloadIsSkippedForBothFallbackModes() throws Exception {
        tp(b -> new TpPriceApi.BatchResult(404, null, Set.of()), id -> json("{\"text\":\"missing id\"}"));
        TpSync.syncTpPrices(Set.of(1));
        assertEquals("0", scalar("SELECT count(*) FROM tp_prices"));

        tp(b -> ok(206, "[]"), id -> json("{\"text\":\"missing id\"}"));
        TpSync.syncTpPrices(Set.of(1));
        assertEquals("0", scalar("SELECT count(*) FROM tp_prices"));
    }

    private static String quote(int id, int buyQty, int buyUnit, int sellQty, int sellUnit) {
        return "{\"id\":" + id + ",\"buys\":{\"quantity\":" + buyQty + ",\"unit_price\":" + buyUnit
                + "},\"sells\":{\"quantity\":" + sellQty + ",\"unit_price\":" + sellUnit + "}}";
    }

    private static TpPriceApi.BatchResult ok(int status, String arrayJson) {
        JsonNode arr = json(arrayJson);
        Set<Integer> ids = new HashSet<>();
        arr.forEach(n -> { if (n != null && n.hasNonNull("id")) ids.add(n.get("id").asInt()); });
        return new TpPriceApi.BatchResult(status, arr, ids);
    }

    private interface Batch { TpPriceApi.BatchResult get(List<Integer> b) throws Exception; }
    private interface Single { JsonNode get(int id) throws Exception; }

    private void tp(Batch batch, Single single) {
        TpSync.upstream = new TpSync.TpUpstream() {
            public TpPriceApi.BatchResult fetchBatch(List<Integer> b) throws Exception { return batch.get(b); }
            public JsonNode fetchSingle(int id) throws Exception { return single.get(id); }
            public Set<Integer> fetchAllTradeableItemIds() { throw new AssertionError(); }
        };
    }

    private String prices() throws Exception {
        return scalar("SELECT string_agg(item_id||':'||coalesce(buy_quantity::text,'-')||'/'||coalesce(buy_unit_price::text,'-')"
                + "||'/'||coalesce(sell_quantity::text,'-')||'/'||coalesce(sell_unit_price::text,'-'), ',' ORDER BY item_id) FROM tp_prices");
    }

    @Test
    void tpSync_emptySetDoesNothing() throws Exception {
        tp(b -> { throw new AssertionError(); }, id -> { throw new AssertionError(); });
        TpSync.syncTpPrices(Set.of());
        assertEquals("0", scalar("SELECT count(*) FROM tp_prices"));
    }

    @Test
    void tpSync_fullBatchStoresQuotesAndNoDataAsNulls() throws Exception {
        tp(b -> {
            assertEquals(List.of(1, 2, 3), b);
            return ok(200, "[" + quote(1, 5, 100, 7, 120) + "," + quote(2, 1, 10, 2, 20)
                    + ",{\"id\":3,\"buys\":null,\"sells\":null},{\"nope\":1},null]");
        }, id -> { throw new AssertionError("no single fetch on a full batch"); });

        TpSync.syncTpPrices(new LinkedHashSet<>(List.of(3, 1, 2)));

        assertEquals("1:5/100/7/120,2:1/10/2/20,3:-/-/-/-", prices());
    }

    @Test
    void tpSync_partialBatchFetchesOnlyMissingIdsIndividuallyAndKeepsConfirmedAbsenceUnwritten() throws Exception {
        List<Integer> singles = new ArrayList<>();
        tp(b -> ok(206, "[" + quote(1, 5, 100, 7, 120) + "]"), id -> {
            singles.add(id);
            return id == 2 ? json(quote(2, 3, 30, 4, 40)) : null; // id 3: 404 -> confirmed absence
        });

        TpSync.syncTpPrices(Set.of(1, 2, 3));

        assertEquals(List.of(2, 3), singles);
        assertEquals("1:5/100/7/120,2:3/30/4/40", prices());
    }

    @Test
    void tpSync_batch404FallsBackToSingleFetchForEveryId() throws Exception {
        List<Integer> singles = new ArrayList<>();
        tp(b -> new TpPriceApi.BatchResult(404, null, Set.of()), id -> {
            singles.add(id);
            return id == 8 ? null : json(quote(id, id, id * 10, id + 1, id * 10 + 5));
        });

        TpSync.syncTpPrices(Set.of(7, 8, 9));

        assertEquals(List.of(7, 8, 9), singles);
        assertEquals("7:7/70/8/75,9:9/90/10/95", prices());
    }

    @Test
    void tpSync_existingRowIsUpdatedAndFailureOnLaterBatchKeepsEarlierBatch() throws Exception {
        exec("INSERT INTO tp_prices VALUES (1, 1, 1, 1, 1, now())");
        int[] call = {0};
        tp(b -> {
            if (++call[0] == 2) throw new IOException("tp down");
            return ok(200, b.stream().map(i -> quote(i, 2, 22, 3, 33)).collect(Collectors.joining(",", "[", "]")));
        }, id -> { throw new AssertionError(); });

        assertThrows(IOException.class, () -> TpSync.syncTpPrices(range(1, 201)));

        assertEquals("200", scalar("SELECT count(*) FROM tp_prices"));
        assertEquals("2/22/3/33", scalar("SELECT buy_quantity||'/'||buy_unit_price||'/'||sell_quantity||'/'||sell_unit_price FROM tp_prices WHERE item_id=1"));
        assertNull(scalar("SELECT 1 FROM tp_prices WHERE item_id=201"));
    }

    @Test
    void tpSync_singleFetchFailureAndDatabaseFailureWriteNothingForThatBatch() throws Exception {
        tp(b -> new TpPriceApi.BatchResult(404, null, Set.of()), id -> { throw new IOException("single failed"); });
        assertThrows(IOException.class, () -> TpSync.syncTpPrices(Set.of(1, 2)));
        assertEquals("0", scalar("SELECT count(*) FROM tp_prices"));

        failInsertOf("tp_prices", "item_id", 2);
        tp(b -> ok(200, "[" + quote(1, 1, 1, 1, 1) + "," + quote(2, 1, 1, 1, 1) + "]"), id -> null);
        assertThrows(java.sql.SQLException.class, () -> TpSync.syncTpPrices(Set.of(1, 2)));
        assertEquals("0", scalar("SELECT count(*) FROM tp_prices"));
    }

    @Test
    void tpSync_tradeableListUnchangedReturnsFalseAndChangedRewritesTableAndFetchesAddedItems() throws Exception {
        exec("INSERT INTO tp_tradeable_items (item_id) VALUES (1), (2)");
        TpSync.upstream = new TpSync.TpUpstream() {
            public TpPriceApi.BatchResult fetchBatch(List<Integer> b) { throw new AssertionError(); }
            public JsonNode fetchSingle(int id) { throw new AssertionError(); }
            public Set<Integer> fetchAllTradeableItemIds() { return Set.of(1, 2); }
        };
        assertFalse(TpSync.syncTpTradeableItems());

        TpSync.upstream = new TpSync.TpUpstream() {
            public TpPriceApi.BatchResult fetchBatch(List<Integer> b) { throw new AssertionError(); }
            public JsonNode fetchSingle(int id) { throw new AssertionError(); }
            public Set<Integer> fetchAllTradeableItemIds() { return Set.of(2, 3, 4); }
        };
        ItemSync.fetcher = url -> { urls.add(url); return json("[" + itemJson(3) + "," + itemJson(4) + "]"); };

        assertTrue(TpSync.syncTpTradeableItems());

        assertEquals("2,3,4", scalar("SELECT string_agg(item_id::text, ',' ORDER BY item_id) FROM tp_tradeable_items"));
        assertEquals("3,4", scalar("SELECT string_agg(item_id::text, ',' ORDER BY item_id) FROM items"));
        assertEquals(1, urls.size());
    }

    @Test
    void tpSync_tradeableListLargerThanOneFlushIsFullyPublished() throws Exception {
        TpSync.upstream = new TpSync.TpUpstream() {
            public TpPriceApi.BatchResult fetchBatch(List<Integer> b) { throw new AssertionError(); }
            public JsonNode fetchSingle(int id) { throw new AssertionError(); }
            public Set<Integer> fetchAllTradeableItemIds() { return range(1, 4001); }
        };
        ItemSync.fetcher = url -> json("[]");

        assertTrue(TpSync.syncTpTradeableItems());
        assertEquals("4001", scalar("SELECT count(*) FROM tp_tradeable_items"));
    }

    @Test
    void recipeSync_ingredientUpsertFlushesEveryConfiguredBatchAndPublishesAllRows() throws Exception {
        int n = SyncConstants.DB_FLUSH_BATCH + 3;
        exec("INSERT INTO items (item_id) SELECT g FROM generate_series(1, " + n + ") g");
        exec("INSERT INTO recipes (recipe_id) VALUES (1)");
        List<parser.RecipeParser.IngredientRow> rows = IntStream.rangeClosed(1, n)
                .mapToObj(i -> new parser.RecipeParser.IngredientRow(1, i, 2)).toList();

        try (Connection con = Db.open()) {
            con.setAutoCommit(false);
            RecipeSync.upsertRecipeIngredients(con, rows);
            con.commit();
        }
        assertEquals(String.valueOf(n), scalar("SELECT count(*) FROM recipe_ingredients WHERE count=2"));
    }
}
