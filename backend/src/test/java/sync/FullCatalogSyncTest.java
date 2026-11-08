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

    // ------------------------------------------------------------------ resource evidence

    @Test
    void resourceEvidence_recordsBoundsCardinalityRequestsElapsedMemoryAndGrowth() throws Exception {
        int n = 2000;
        ItemSync.fetcher = url -> { urls.add(url); return url.endsWith("/v2/items") ? json(ids(1, n)) : itemsFor(url, "R"); };
        Runtime rt = Runtime.getRuntime();
        System.gc();
        long usedBefore = rt.totalMemory() - rt.freeMemory();
        long t0 = System.nanoTime();
        ItemSync.syncAllItemsFull(TaskDeadline.start());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        long usedAfter = rt.totalMemory() - rt.freeMemory();
        String bytes = scalar("SELECT pg_total_relation_size('items')");
        System.out.printf("FULL-CATALOG-EVIDENCE bounds=connect %ds/request %ds/task %dmin; items fixture=%d ids, requests=%d, "
                        + "elapsed=%d ms, heap delta=%d KiB (single sample, not a peak), items relation size=%s bytes%n",
                TaskDeadline.CONNECT_SECONDS, TaskDeadline.REQUEST_SECONDS, TaskDeadline.TASK_MINUTES,
                n, urls.size(), ms, (usedAfter - usedBefore) / 1024, bytes);
        assertEquals(1 + 10, urls.size());
        assertEquals(String.valueOf(n), scalar("SELECT count(*) FROM items"));
    }
}
