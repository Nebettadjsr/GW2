package sync;

import api.TaskDeadline;
import api.tp.TpPriceApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import repo.Db;
import repo.EnvConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * STORY-STATUS-004: the owner full item/recipe/Trading Post operations against a production-migration
 * disposable schema with deterministic in-memory upstreams. No live GW2 call; nothing outside the
 * isolated schema is written.
 */
class FullCatalogSyncTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String schema = "test_full_catalog_" + UUID.randomUUID().toString().replace("-", "");
    private Connection admin;
    private final List<String> urls = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        admin = DriverManager.getConnection(EnvConfig.require("DATABASE_URL"),
                EnvConfig.require("DATABASE_USER"), EnvConfig.require("DATABASE_PASSWORD"));
        try (Statement st = admin.createStatement()) { st.execute("CREATE SCHEMA " + schema); }
        admin.setSchema(schema);
        Flyway.configure().dataSource(EnvConfig.require("DATABASE_URL"), EnvConfig.require("DATABASE_USER"),
                        EnvConfig.require("DATABASE_PASSWORD"))
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
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
        try (Statement st = admin.createStatement()) { st.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE"); }
        finally { admin.close(); }
    }

    private void exec(String sql) throws Exception { try (Statement st = admin.createStatement()) { st.execute(sql); } }

    private String scalar(String sql) throws Exception {
        try (Statement st = admin.createStatement(); ResultSet rs = st.executeQuery(sql)) { return rs.next() ? rs.getString(1) : null; }
    }

    private static JsonNode json(String s) {
        try { return JSON.readTree(s); } catch (Exception e) { throw new AssertionError(e); }
    }

    private static String ids(int from, int to) {
        return IntStream.rangeClosed(from, to).boxed().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }

    private static List<Integer> requested(String url) {
        return java.util.Arrays.stream(url.substring(url.indexOf("ids=") + 4).split(",")).map(Integer::valueOf).toList();
    }

    private static String itemJson(int id, String name) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"type\":\"Trophy\",\"rarity\":\"Basic\",\"vendor_value\":" + id + "}";
    }

    private static JsonNode itemsFor(String url, String name) {
        return json(requested(url).stream().map(i -> itemJson(i, name)).collect(Collectors.joining(",", "[", "]")));
    }

    private static String recipeJson(int id, int out, int ingredient) {
        return "{\"id\":" + id + ",\"type\":\"Refinement\",\"output_item_id\":" + out + ",\"output_item_count\":1,\"min_rating\":0,"
                + "\"time_to_craft_ms\":1,\"disciplines\":[\"Cook\"],\"chat_link\":\"[&A]\","
                + "\"ingredients\":[{\"item_id\":" + ingredient + ",\"count\":2}]}";
    }

    // ------------------------------------------------------------------ items

    @Test
    void fullItems_refetchesEveryIdInTwoHundredBatches_reconcilesOnlyAfterCoverage_keepsReferenced() throws Exception {
        // 450 captured ids: 1..449 unchanged/added + 450. Local: 1..10 unchanged, 9001 absent, 9002 absent but referenced.
        exec("INSERT INTO items (item_id, name) SELECT g, 'OLD' FROM generate_series(1,10) g");
        exec("INSERT INTO items (item_id, name) VALUES (9001, 'gone'), (9002, 'referenced')");
        exec("INSERT INTO recipes (recipe_id, type) VALUES (1, 'x')");
        exec("INSERT INTO recipe_ingredients (recipe_id, item_id, count) VALUES (1, 9002, 1)");
        ItemSync.fetcher = url -> {
            urls.add(url);
            return url.endsWith("/v2/items") ? json(ids(1, 450)) : itemsFor(url, "NEW");
        };

        ItemSync.syncAllItemsFull(TaskDeadline.start());

        assertEquals(1 + 3, urls.size(), "one snapshot plus ceil(450/200)=3 detail batches");
        assertEquals("450", scalar("SELECT count(*) FROM items WHERE name='NEW'"), "unchanged ids re-downloaded too");
        assertNull(scalar("SELECT 1 FROM items WHERE item_id=9001"), "absent and unreferenced -> removed");
        assertEquals("referenced", scalar("SELECT name FROM items WHERE item_id=9002"), "referenced row kept");
        // After recipe reconciliation removes the reference, the next run can delete it.
        exec("DELETE FROM recipes WHERE recipe_id=1");
        ItemSync.syncAllItemsFull(TaskDeadline.start());
        assertNull(scalar("SELECT 1 FROM items WHERE item_id=9002"));
    }

    @Test
    void fullItems_everyMalformedBatchFailsAndNothingIsReconciled() throws Exception {
        exec("INSERT INTO items (item_id, name) VALUES (9001, 'keep')");
        String[] bad = {
                "{\"not\":\"array\"}",
                "[" + itemJson(1, "a") + ",null]",
                "[" + itemJson(1, "a") + "," + itemJson(1, "dup") + "]",
                "[" + itemJson(1, "a") + "," + itemJson(2, "b") + "," + itemJson(77, "unexpected") + "]",
                "[" + itemJson(1, "a") + "]",              // id 2 missing
        };
        for (String payload : bad) {
            ItemSync.fetcher = url -> url.endsWith("/v2/items") ? json("[1,2]") : json(payload);
            assertThrows(IllegalStateException.class, () -> ItemSync.syncAllItemsFull(TaskDeadline.start()), payload);
            assertEquals("keep", scalar("SELECT name FROM items WHERE item_id=9001"), "no reconciliation after failure");
        }
        ItemSync.fetcher = url -> { throw new java.io.IOException("timeout"); };
        assertThrows(java.io.IOException.class, () -> ItemSync.syncAllItemsFull(TaskDeadline.start()));
        ItemSync.fetcher = url -> url.endsWith("/v2/items") ? json("[]") : json("[]");
        assertThrows(IllegalStateException.class, () -> ItemSync.syncAllItemsFull(TaskDeadline.start()));
        ItemSync.fetcher = url -> url.endsWith("/v2/items") ? json("[1,\"x\"]") : json("[]");
        assertThrows(IllegalStateException.class, () -> ItemSync.syncAllItemsFull(TaskDeadline.start()));
    }

    @Test
    void fullItems_persistenceFailureAndExpiredBoundFail() throws Exception {
        exec("CREATE FUNCTION rej() RETURNS trigger AS $$ BEGIN IF NEW.item_id = 2 THEN RAISE EXCEPTION 'rejected'; END IF; RETURN NEW; END; $$ LANGUAGE plpgsql");
        exec("CREATE TRIGGER rej BEFORE INSERT ON items FOR EACH ROW EXECUTE FUNCTION rej()");
        ItemSync.fetcher = url -> url.endsWith("/v2/items") ? json("[1,2]") : itemsFor(url, "N");
        assertThrows(Exception.class, () -> ItemSync.syncAllItemsFull(TaskDeadline.start()));

        var clock = new AtomicLong();
        var d = TaskDeadline.after(Duration.ofMinutes(30), clock::get);
        ItemSync.fetcher = url -> {
            clock.addAndGet(Duration.ofMinutes(31).toNanos()); // upstream "took" longer than the bound
            return url.endsWith("/v2/items") ? json("[3]") : itemsFor(url, "N");
        };
        assertThrows(TaskDeadline.ExceededException.class, () -> ItemSync.syncAllItemsFull(d));
        assertNull(scalar("SELECT 1 FROM items WHERE item_id=3"));
    }

    // ------------------------------------------------------------------ recipes

    private void recipeUpstream(int recipeCount, java.util.function.IntFunction<String> recipe) {
        RecipeSync.fetcher = url -> {
            urls.add(url);
            if (url.endsWith("/v2/recipes")) return json(ids(1, recipeCount));
            return json(requested(url).stream().map(i -> recipe.apply(i)).collect(Collectors.joining(",", "[", "]")));
        };
        ItemSync.fetcher = url -> { urls.add(url); return itemsFor(url, "I"); };
    }

    @Test
    void fullRecipes_refetchUnchangedIds_publishOnlyAfterTotalSuccess_andRecoverAfterFailure() throws Exception {
        exec("INSERT INTO recipes (recipe_id, type) VALUES (1, 'Old'), (2, 'Old'), (9999, 'deleted')");
        exec("INSERT INTO recipe_catalog_publication (complete) VALUES (TRUE)");
        // 250 recipes -> 2 detail batches; recipe 230 (second batch) is malformed -> failure after batch 1 committed.
        recipeUpstream(250, i -> i == 230 ? "null" : recipeJson(i, 100000 + i, 500000 + i));
        assertThrows(IllegalStateException.class, () -> RecipeSync.syncAllRecipesFull(TaskDeadline.start()));
        assertEquals("f", scalar("SELECT complete FROM recipe_catalog_publication"), "publication stays incomplete");
        assertEquals("200", scalar("SELECT count(*) FROM recipes WHERE type='Refinement'"), "committed batch remains");
        assertNotNull(scalar("SELECT 1 FROM recipes WHERE recipe_id=9999"), "deletion happens only after success");

        urls.clear();
        recipeUpstream(250, i -> recipeJson(i, 100000 + i, 500000 + i));
        RecipeSync.syncAllRecipesFull(TaskDeadline.start());
        assertEquals("t", scalar("SELECT complete FROM recipe_catalog_publication"));
        assertEquals("250", scalar("SELECT count(*) FROM recipes WHERE type='Refinement'"));
        assertNull(scalar("SELECT 1 FROM recipes WHERE recipe_id=9999"));
        assertEquals("250", scalar("SELECT count(*) FROM recipe_ingredients"));
        assertEquals(2L, urls.stream().filter(u -> u.contains("/v2/recipes?ids=")).count());

        // Unchanged ID list: ordinary sync would skip; the full operation re-downloads everything.
        urls.clear();
        RecipeSync.syncAllRecipesFull(TaskDeadline.start());
        assertEquals(2L, urls.stream().filter(u -> u.contains("/v2/recipes?ids=")).count());
        assertEquals("t", scalar("SELECT complete FROM recipe_catalog_publication"));
    }

    @Test
    void fullRecipes_missingOrDuplicateDetailRejected() throws Exception {
        RecipeSync.fetcher = url -> url.endsWith("/v2/recipes") ? json("[1,2]")
                : json("[" + recipeJson(1, 10, 11) + "]");
        ItemSync.fetcher = url -> itemsFor(url, "I");
        assertThrows(IllegalStateException.class, () -> RecipeSync.syncAllRecipesFull(TaskDeadline.start()));
        assertEquals("f", scalar("SELECT complete FROM recipe_catalog_publication"));
        RecipeSync.fetcher = url -> url.endsWith("/v2/recipes") ? json("[1,2]")
                : json("[" + recipeJson(1, 10, 11) + "," + recipeJson(1, 10, 11) + "]");
        assertThrows(IllegalStateException.class, () -> RecipeSync.syncAllRecipesFull(TaskDeadline.start()));
    }

    // ------------------------------------------------------------------ trading post

    private static TpSync.TpUpstream tpUpstream(List<List<Integer>> batches) {
        return new TpSync.TpUpstream() {
            public TpPriceApi.BatchResult fetchBatch(List<Integer> b) {
                batches.add(List.copyOf(b));
                var arr = JSON.createArrayNode();
                for (int id : b) arr.add(json("{\"id\":" + id + ",\"buys\":{\"quantity\":1,\"unit_price\":5},\"sells\":{\"quantity\":2,\"unit_price\":9}}"));
                return new TpPriceApi.BatchResult(200, arr, new java.util.HashSet<>(b));
            }
            public JsonNode fetchSingle(int id) { throw new AssertionError(); }
            public Set<Integer> fetchAllTradeableItemIds() { throw new AssertionError(); }
        };
    }

    @Test
    void fullTp_coversEveryTradeableIdDespiteFreshPrices_andIsNotTheOrdinaryFilteredPath() throws Exception {
        exec("INSERT INTO tp_tradeable_items (item_id) SELECT g FROM generate_series(1,450) g");
        exec("INSERT INTO tp_prices (item_id, buy_quantity, buy_unit_price, sell_quantity, sell_unit_price, fetched_at) "
                + "SELECT g, 0, 1, 0, 1, now() FROM generate_series(1,450) g");
        List<List<Integer>> batches = new ArrayList<>();
        TpSync.upstream = tpUpstream(batches);

        TpSync.syncAllTradeablePrices(TaskDeadline.start());

        assertEquals(List.of(200, 200, 50), batches.stream().map(List::size).toList());
        assertEquals("450", scalar("SELECT count(*) FROM tp_prices WHERE buy_unit_price=5 AND sell_unit_price=9"));
    }

    @Test
    void fullTp_emptyCatalogMalformedAndExhaustedBoundFail() throws Exception {
        TpSync.upstream = tpUpstream(new ArrayList<>());
        assertThrows(IllegalStateException.class, () -> TpSync.syncAllTradeablePrices(TaskDeadline.start()));
        exec("INSERT INTO tp_tradeable_items (item_id) VALUES (1)");
        TpSync.upstream = new TpSync.TpUpstream() {
            public TpPriceApi.BatchResult fetchBatch(List<Integer> b) { throw new RuntimeException("HTTP 503"); }
            public JsonNode fetchSingle(int id) { return null; }
            public Set<Integer> fetchAllTradeableItemIds() { return Set.of(); }
        };
        assertThrows(RuntimeException.class, () -> TpSync.syncAllTradeablePrices(TaskDeadline.start()));
        var clock = new AtomicLong(); var d = TaskDeadline.after(Duration.ofMinutes(30), clock::get);
        clock.addAndGet(Duration.ofMinutes(31).toNanos());
        TpSync.upstream = tpUpstream(new ArrayList<>());
        assertThrows(TaskDeadline.ExceededException.class, () -> TpSync.syncAllTradeablePrices(d));
        assertEquals("0", scalar("SELECT count(*) FROM tp_prices"));
    }

    // ------------------------------------------------------------------ cache coherence

    @Test
    void fullItems_refreshOnlyAlreadyCachedEquipmentProjection_leavesItemStatsAndUncachedRowsAlone() throws Exception {
        String oldDetails = "{\"format\":1,\"itemId\":1,\"itemType\":\"Trophy\",\"description\":\"OLD\"}";
        exec("INSERT INTO equipment_item_details (item_id, details, fetched_at) VALUES (1, '" + oldDetails + "'::jsonb, now() - interval '1 day')");
        exec("INSERT INTO equipment_item_stats (stat_id, attributes, fetched_at) VALUES (5, '{\"statId\":5}'::jsonb, now() - interval '1 day')");
        String statsBefore = scalar("SELECT attributes::text || fetched_at::text FROM equipment_item_stats WHERE stat_id=5");
        ItemSync.fetcher = url -> url.endsWith("/v2/items") ? json("[1,2]") : json("["
                + "{\"id\":1,\"name\":\"N1\",\"type\":\"Trophy\",\"rarity\":\"Basic\",\"vendor_value\":1,\"level\":80,\"description\":\"NEW\"},"
                + "{\"id\":2,\"name\":\"N2\",\"type\":\"Trophy\",\"rarity\":\"Basic\",\"vendor_value\":1,\"level\":80,\"description\":\"x\"}]");
        ItemSync.syncAllItemsFull(TaskDeadline.start());
        assertEquals("NEW", scalar("SELECT details->>'description' FROM equipment_item_details WHERE item_id=1"));
        assertEquals("80", scalar("SELECT details->>'level' FROM equipment_item_details WHERE item_id=1"));
        assertEquals("1", scalar("SELECT count(*) FROM equipment_item_details"), "uncached item 2 gets no row");
        assertEquals(statsBefore, scalar("SELECT attributes::text || fetched_at::text FROM equipment_item_stats WHERE stat_id=5"));
    }


    @Test
    void fullItems_unprojectableChangedPayloadForCachedEquipmentRowFailsAndKeepsUncachedUntouched() throws Exception {
        String oldDetails = "{\"format\":1,\"itemId\":1,\"itemType\":\"Trophy\",\"description\":\"OLD\"}";
        exec("INSERT INTO equipment_item_details (item_id, details, fetched_at) VALUES (1, '" + oldDetails + "'::jsonb, now() - interval '1 day')");
        // Valid catalog records, but item 1 (cached) has no description -> the equipment parser cannot project it.
        ItemSync.fetcher = url -> url.endsWith("/v2/items") ? json("[1,2]") : json("["
                + itemJson(1, "N1") + "," + itemJson(2, "N2") + "]");
        assertThrows(IllegalStateException.class, () -> ItemSync.syncAllItemsFull(TaskDeadline.start()));
        assertEquals("OLD", scalar("SELECT details->>'description' FROM equipment_item_details WHERE item_id=1"));
        assertEquals("1", scalar("SELECT count(*) FROM equipment_item_details"), "uncached item 2 stays uncached");
        assertNull(scalar("SELECT 1 FROM items WHERE item_id=1"), "the failed batch is not reported as refreshed");
    }

    // ------------------------------------------------------------------ real shared graph

    private repo.CraftingGraphCache realGraphCache() throws Exception {
        java.nio.file.Path f = java.nio.file.Files.createTempFile("graph", ".json");
        java.nio.file.Files.delete(f);
        f.toFile().deleteOnExit();
        System.setProperty(repo.CraftingGraphCache.TEST_CACHE_FILE_PROPERTY, f.toString());
        return new repo.CraftingGraphCache(new repo.RecipeRepository());
    }

    @Test
    void fullItems_changedNameUnderUnchangedRecipeCountsIsVisibleInTheRealSharedGraph() throws Exception {
        try {
            recipeUpstream(2, i -> recipeJson(i, 100000 + i, 500000 + i));
            RecipeSync.syncAllRecipesFull(TaskDeadline.start());
            repo.CraftingGraphCache cache = realGraphCache();
            assertEquals("I", cache.rebuild(TaskDeadline.start()).getItemNames().get(100001));
            String counts = scalar("SELECT (SELECT count(*) FROM recipes) || '/' || (SELECT count(*) FROM recipe_ingredients)");

            ItemSync.fetcher = url -> url.endsWith("/v2/items") ? json(java.util.List.of(100001, 100002, 500001, 500002).toString())
                    : itemsFor(url, "RENAMED");
            new application.FullCatalogAdministrationService(cache).syncItems();

            assertEquals(counts, scalar("SELECT (SELECT count(*) FROM recipes) || '/' || (SELECT count(*) FROM recipe_ingredients)"));
            assertEquals("RENAMED", cache.load().getItemNames().get(100001));
            assertEquals("RENAMED", cache.load().getItemNames().get(500002));
        } finally {
            System.clearProperty(repo.CraftingGraphCache.TEST_CACHE_FILE_PROPERTY);
        }
    }

    @Test
    void boundedGraphRebuild_blockedQueryFailsWithinShortBudget_leavesGraphInvalidated_thenRecovers() throws Exception {
        try {
            recipeUpstream(2, i -> recipeJson(i, 100000 + i, 500000 + i));
            RecipeSync.syncAllRecipesFull(TaskDeadline.start());
            repo.CraftingGraphCache cache = realGraphCache();
            cache.rebuild(TaskDeadline.start());
            var service = new application.CraftingGraphRebuildService(cache);

            // Another session holds exclusive locks, so the rebuild's own SELECTs block in the database.
            admin.setAutoCommit(false);
            exec("LOCK TABLE recipes, recipe_ingredients IN ACCESS EXCLUSIVE MODE");
            cache.invalidate();
            long t0 = System.nanoTime();
            var short_ = TaskDeadline.after(Duration.ofMillis(500), System::nanoTime);
            assertThrows(java.sql.SQLException.class, () -> service.rebuild(short_));
            long elapsedMs = Duration.ofNanos(System.nanoTime() - t0).toMillis();
            assertTrue(elapsedMs < 10_000, "blocked JDBC query must be cut off by the budget, took " + elapsedMs + " ms");
            admin.rollback();
            admin.setAutoCommit(true);

            // Nothing was published by the failed attempt; a fresh budget recovers.
            service.rebuild(TaskDeadline.start());
            assertEquals("I", cache.load().getItemNames().get(100001));
            // Ordinary unbounded rebuild path is unchanged.
            assertEquals("I", cache.rebuild().getItemNames().get(500001));
        } finally {
            System.clearProperty(repo.CraftingGraphCache.TEST_CACHE_FILE_PROPERTY);
        }
    }

    // ------------------------------------------------------------------ strict record validation

    @Test
    void fullItems_bareIdWrongTypeAndMissingRequiredFieldsAreRejected() throws Exception {
        String ok = "\"name\":\"a\",\"type\":\"Trophy\",\"rarity\":\"Basic\",\"vendor_value\":1";
        String[] bad = {
                "{\"id\":1}",
                "{\"id\":1,\"name\":5,\"type\":\"T\",\"rarity\":\"B\",\"vendor_value\":1}",
                "{\"id\":1,\"name\":\"a\",\"type\":\"T\",\"rarity\":\"B\",\"vendor_value\":\"7\"}",
                "{\"id\":1,\"name\":\"a\",\"type\":\"T\",\"rarity\":\"B\",\"vendor_value\":-1}",
                "{\"id\":\"1\"," + ok + "}",
                "{\"id\":1," + ok + ",\"icon\":7}",
                "{\"id\":1," + ok + ",\"flags\":\"NoSell\"}",
        };
        for (String rec : bad) {
            ItemSync.fetcher = url -> url.endsWith("/v2/items") ? json("[1]") : json("[" + rec + "]");
            assertThrows(IllegalStateException.class, () -> ItemSync.syncAllItemsFull(TaskDeadline.start()), rec);
        }
        assertEquals("0", scalar("SELECT count(*) FROM items"));
        // legitimate shape without optional icon/flags still succeeds
        ItemSync.fetcher = url -> url.endsWith("/v2/items") ? json("[1]") : json("[{\"id\":1," + ok + "}]");
        ItemSync.syncAllItemsFull(TaskDeadline.start());
        assertEquals("1", scalar("SELECT count(*) FROM items"));
    }

    @Test
    void fullRecipes_bareIdWrongTypeAndBadIngredientsAreRejectedAndNeverPublished() throws Exception {
        String head = "\"id\":1,\"type\":\"Refinement\",\"output_item_id\":10,\"output_item_count\":1,\"min_rating\":0,\"time_to_craft_ms\":1";
        String[] bad = {
                "{\"id\":1}",
                "{" + head + "}",                                                     // item output without ingredients
                "{" + head + ",\"ingredients\":[]}",
                "{" + head + ",\"ingredients\":[{\"item_id\":11,\"count\":0}]}",
                "{" + head + ",\"ingredients\":[{\"item_id\":11,\"count\":-2}]}",
                "{" + head + ",\"ingredients\":[{\"item_id\":\"11\",\"count\":1}]}",
                "{" + head + ",\"ingredients\":[{\"count\":1}]}",
                "{" + head + ",\"ingredients\":[{\"item_id\":11,\"count\":1},{\"item_id\":11,\"count\":1}]}",
                "{" + head + ",\"ingredients\":[7]}",
                "{" + head.replace("\"output_item_count\":1", "\"output_item_count\":0") + ",\"ingredients\":[{\"item_id\":11,\"count\":1}]}",
                "{" + head.replace("\"time_to_craft_ms\":1", "\"time_to_craft_ms\":\"1\"") + ",\"ingredients\":[{\"item_id\":11,\"count\":1}]}",
                "{" + head + ",\"disciplines\":\"Cook\",\"ingredients\":[{\"item_id\":11,\"count\":1}]}",
        };
        ItemSync.fetcher = url -> itemsFor(url, "I");
        for (String rec : bad) {
            RecipeSync.fetcher = url -> url.endsWith("/v2/recipes") ? json("[1]") : json("[" + rec + "]");
            assertThrows(IllegalStateException.class, () -> RecipeSync.syncAllRecipesFull(TaskDeadline.start()), rec);
            assertEquals("f", scalar("SELECT complete FROM recipe_catalog_publication"), rec);
        }
        assertEquals("0", scalar("SELECT count(*) FROM recipe_ingredients"));
        // guild upgrade recipe (no item output, no ingredients array) is a legitimate documented shape
        RecipeSync.fetcher = url -> url.endsWith("/v2/recipes") ? json("[1]") : json(
                "[{\"id\":1,\"type\":\"GuildConsumable\",\"output_upgrade_id\":3,\"min_rating\":0,\"time_to_craft_ms\":1,"
                        + "\"guild_ingredients\":[{\"upgrade_id\":2,\"count\":1}]}]");
        RecipeSync.syncAllRecipesFull(TaskDeadline.start());
        assertEquals("t", scalar("SELECT complete FROM recipe_catalog_publication"));
    }

    @Test
    void fullRecipes_expiryBeforeFinalPublicationLeavesItIncomplete() throws Exception {
        var clock = new AtomicLong();
        var d = TaskDeadline.after(Duration.ofMinutes(30), clock::get);
        recipeUpstream(2, i -> recipeJson(i, 100000 + i, 500000 + i));
        ItemSync.fetcher = url -> { clock.addAndGet(Duration.ofMinutes(31).toNanos()); return itemsFor(url, "I"); };
        assertThrows(TaskDeadline.ExceededException.class, () -> RecipeSync.syncAllRecipesFull(d));
        assertEquals("f", scalar("SELECT complete FROM recipe_catalog_publication"));
    }

    // ------------------------------------------------------------------ trading post coverage

    private static TpSync.TpUpstream scripted(int status, String arrayJson, java.util.function.IntFunction<String> single) {
        return new TpSync.TpUpstream() {
            public TpPriceApi.BatchResult fetchBatch(List<Integer> b) {
                JsonNode arr = json(arrayJson);
                Set<Integer> ret = new java.util.HashSet<>();
                if (arr != null && arr.isArray()) for (JsonNode n : arr) if (n.hasNonNull("id")) ret.add(n.get("id").asInt());
                return new TpPriceApi.BatchResult(status, arr, ret);
            }
            public JsonNode fetchSingle(int id) { String r = single.apply(id); return r == null ? null : json(r); }
            public Set<Integer> fetchAllTradeableItemIds() { return Set.of(); }
        };
    }

    private static String quote(int id, int buy) {
        return "{\"id\":" + id + ",\"buys\":{\"quantity\":1,\"unit_price\":" + buy + "},\"sells\":{\"quantity\":2,\"unit_price\":9}}";
    }

    private void seedOldTp() throws Exception {
        exec("INSERT INTO tp_tradeable_items (item_id) VALUES (1),(2)");
        exec("INSERT INTO tp_prices (item_id, buy_quantity, buy_unit_price, sell_quantity, sell_unit_price, fetched_at) "
                + "VALUES (1,1,111,1,111, now() - interval '1 day'),(2,1,222,1,222, now() - interval '1 day')");
    }

    @Test
    void fullTp_omissionsMalformedDuplicateAndUnexpectedFailAndKeepOldQuotesUntouched() throws Exception {
        seedOldTp();
        String[] bad = {
                "[]",                                                   // 200 omitting every requested id
                "[" + quote(1, 5) + "]",                                // 200 omitting id 2
                "[" + quote(1, 5) + "," + quote(1, 5) + "]",            // duplicate
                "[" + quote(1, 5) + "," + quote(2, 5) + "," + quote(3, 5) + "]", // unexpected
                "[" + quote(1, 5) + ",{\"id\":2,\"buys\":{\"quantity\":\"x\",\"unit_price\":1},\"sells\":{\"quantity\":1,\"unit_price\":1}}]",
                "[" + quote(1, 5) + ",{\"id\":2,\"buys\":{\"quantity\":1,\"unit_price\":1}}]",   // one side only
                "[" + quote(1, 5) + ",{\"buys\":null,\"sells\":null}]",
                "[{\"id\":1},{\"id\":2}]",                              // bare ids: sides absent, not explicit null
                "[" + quote(1, 5) + ",{\"id\":2}]",                      // one bare id
                "[" + quote(1, 5) + ",{\"id\":2,\"buys\":null}]",         // sells absent
                "{\"id\":1}",
        };
        for (String payload : bad) {
            TpSync.upstream = scripted(200, payload, id -> { throw new AssertionError("no fallback on 200"); });
            assertThrows(IllegalStateException.class, () -> TpSync.syncAllTradeablePrices(TaskDeadline.start()), payload);
            assertEquals("111,222", scalar("SELECT string_agg(buy_unit_price::text, ',' ORDER BY item_id) FROM tp_prices"), payload);
            assertEquals("2", scalar("SELECT count(*) FROM tp_prices WHERE fetched_at < now() - interval '1 hour'"), payload);
        }
    }

    @Test
    void fullTp_partialAndNotFoundFallbacksResolveEveryIdOrMarkItUnavailable() throws Exception {
        seedOldTp();
        // 206 returns id 1 only; id 2 resolves through the single fallback.
        TpSync.upstream = scripted(206, "[" + quote(1, 5) + "]", id -> id == 2 ? quote(2, 6) : null);
        TpSync.syncAllTradeablePrices(TaskDeadline.start());
        assertEquals("5,6", scalar("SELECT string_agg(buy_unit_price::text, ',' ORDER BY item_id) FROM tp_prices"));
        // 404 batch: id 1 single present, id 2 explicitly unavailable -> all-null row, old quote not kept as fresh.
        TpSync.upstream = scripted(404, "[]", id -> id == 1 ? quote(1, 7) : null);
        TpSync.syncAllTradeablePrices(TaskDeadline.start());
        assertEquals("7", scalar("SELECT buy_unit_price FROM tp_prices WHERE item_id=1"));
        assertNull(scalar("SELECT buy_unit_price FROM tp_prices WHERE item_id=2"));
        assertEquals("0", scalar("SELECT count(*) FROM tp_prices WHERE fetched_at < now() - interval '1 hour'"));
        // a fallback single response for the wrong id is rejected
        seedFallbackWrongId();
    }

    private void seedFallbackWrongId() throws Exception {
        TpSync.upstream = scripted(404, "[]", id -> quote(id + 100, 1));
        assertThrows(IllegalStateException.class, () -> TpSync.syncAllTradeablePrices(TaskDeadline.start()));
    }

    // ------------------------------------------------------------------ overlapping publication ordering

    /** Fake upstream: the first batch fetch signals and blocks until released; later fetches answer at once. */
    private static final class GatedUpstream implements TpSync.TpUpstream {
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        volatile long firstEnteredMillis;
        public TpPriceApi.BatchResult fetchBatch(List<Integer> b) throws Exception {
            boolean first = calls.incrementAndGet() == 1;
            int buy = first ? 5 : 99;
            if (first) { firstEnteredMillis = System.currentTimeMillis(); entered.countDown(); release.await(); }
            JsonNode arr = json("[" + b.stream().map(i -> quote(i, buy)).collect(Collectors.joining(",")) + "]");
            return new TpPriceApi.BatchResult(200, arr, new java.util.HashSet<>(b));
        }
        public JsonNode fetchSingle(int id) { throw new AssertionError(); }
        public Set<Integer> fetchAllTradeableItemIds() { return Set.of(); }
    }

    private interface TpRun { void run() throws Exception; }

    /** Runs the delayed (older) observation, completes the newer one meanwhile, then resumes the older. */
    private void delayedOlderThenNewer(TpRun older, TpRun newer) throws Exception {
        var up = new GatedUpstream();
        TpSync.upstream = up;
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread t = new Thread(() -> { try { older.run(); } catch (Throwable e) { failure.set(e); } });
        t.start();
        up.entered.await();
        while (System.currentTimeMillis() <= up.firstEnteredMillis) Thread.onSpinWait(); // newer strictly later
        newer.run();
        assertEquals("99", scalar("SELECT buy_unit_price FROM tp_prices WHERE item_id=1"));
        String newerFetchedAt = scalar("SELECT fetched_at FROM tp_prices WHERE item_id=1");
        up.release.countDown();
        t.join();
        assertNull(failure.get());
        assertEquals("99,99", scalar("SELECT string_agg(buy_unit_price::text, ',' ORDER BY item_id) FROM tp_prices"));
        assertEquals(newerFetchedAt, scalar("SELECT fetched_at FROM tp_prices WHERE item_id=1"));
    }

    private void seedOverlap() throws Exception {
        exec("INSERT INTO tp_tradeable_items (item_id) VALUES (1),(2)");
        exec("INSERT INTO tp_prices (item_id, buy_quantity, buy_unit_price, sell_quantity, sell_unit_price, fetched_at) "
                + "VALUES (1,1,111,1,111, now() - interval '1 day'),(2,1,222,1,222, now() - interval '1 day')");
    }

    @Test
    void delayedOlderFullObservationNeverReplacesNewerOrdinaryQuote() throws Exception {
        seedOverlap();
        delayedOlderThenNewer(() -> TpSync.syncAllTradeablePrices(TaskDeadline.start()), () -> TpSync.syncTpPrices(Set.of(1, 2)));
    }

    @Test
    void delayedOlderOrdinaryObservationNeverReplacesNewerFullQuote() throws Exception {
        seedOverlap();
        delayedOlderThenNewer(() -> TpSync.syncTpPrices(Set.of(1, 2)), () -> TpSync.syncAllTradeablePrices(TaskDeadline.start()));
    }

    @Test
    void newerObservationCommittingAfterOlderOneStillWins() throws Exception {
        seedOverlap();
        TpSync.upstream = scripted(200, "[" + quote(1, 5) + "," + quote(2, 5) + "]", id -> null);
        TpSync.syncAllTradeablePrices(TaskDeadline.start());
        TpSync.upstream = scripted(200, "[" + quote(1, 99) + "," + quote(2, 99) + "]", id -> null);
        TpSync.syncTpPrices(Set.of(1, 2));
        assertEquals("99,99", scalar("SELECT string_agg(buy_unit_price::text, ',' ORDER BY item_id) FROM tp_prices"));
    }

    // ------------------------------------------------------------------ multi-batch correctness

    /**
     * Controlled multi-batch fixture (4000 items = 20 detail batches of 200): every batch is requested
     * and every item stored. No live API call; the upstream is an in-memory fake.
     */
    @Test
    void multiBatchCatalog_requestsEveryDetailBatchAndStoresEveryItem() throws Exception {
        int n = 4000;
        ItemSync.fetcher = url -> { urls.add(url); return url.endsWith("/v2/items") ? json(ids(1, n)) : itemsFor(url, "R"); };
        ItemSync.syncAllItemsFull(TaskDeadline.start());
        assertEquals(1 + 20, urls.size());
        assertEquals(String.valueOf(n), scalar("SELECT count(*) FROM items"));
    }
}
