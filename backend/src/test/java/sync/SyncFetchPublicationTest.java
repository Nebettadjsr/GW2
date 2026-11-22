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
        String ids = url.substring(url.indexOf("ids=") + 4);
        int query = ids.indexOf('&'); // recipe detail URLs carry the pinned schema version after the ids
        return query < 0 ? ids : ids.substring(0, query);
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
        if (url.equals(RecipeSync.CATALOG_URL)) return json("[1, 2, 2, 0]");
        if (url.contains("/v2/recipes?ids=")) {
            return json("""
                [{"id":1,"type":"Refinement","output_item_id":20,"output_item_count":2,"min_rating":100,"time_to_craft_ms":1000,
                  "disciplines":["Weaponsmith"],"flags":["AutoLearned"],"chat_link":"[&A]",
                  "ingredients":[{"item_id":10,"count":4}]},
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
    void recipeSync_skippedGuildUpgradeRecipeRemovesItsLegacyCachedRowInsteadOfLeavingAFreeRecipe() throws Exception {
        // A legacy cache: the guild recipe row exists and its ingredients were already cleared.
        exec("INSERT INTO items (item_id, name) VALUES (30, 'stale')");
        exec("INSERT INTO recipes (recipe_id, type, output_item_id) VALUES (13324, 'GuildConsumable', 30)");
        RecipeSync.fetcher = url -> {
            urls.add(url);
            if (url.equals(RecipeSync.CATALOG_URL)) return json("[1, 13324]");
            if (url.contains("/v2/recipes?ids=")) {
                return json("""
                    [{"id":1,"type":"Refinement","output_item_id":20,"output_item_count":1,"min_rating":0,"time_to_craft_ms":1000,
                      "disciplines":["Chef"],"flags":[],"chat_link":"[&A]","ingredients":[{"type":"Item","id":10,"count":1}]},
                     {"id":13324,"type":"GuildConsumable","output_item_id":30,"output_item_count":1,"min_rating":0,"time_to_craft_ms":1000,
                      "disciplines":["Chef"],"flags":[],"chat_link":"[&B]",
                      "ingredients":[{"type":"Item","id":70926,"count":1},{"type":"Item","id":74982,"count":4},
                                     {"type":"GuildUpgrade","id":721,"count":8}]}]
                    """);
            }
            return json("[" + itemJson(10) + "," + itemJson(20) + "]");
        };
        ItemSync.fetcher = RecipeSync.fetcher::get;

        assertTrue(RecipeSync.syncAllRecipesGlobalSafe());

        assertNull(scalar("SELECT 1 FROM recipes WHERE recipe_id=13324"), "never usable as item-only");
        assertNull(scalar("SELECT 1 FROM recipe_ingredients WHERE recipe_id=13324"));
        assertEquals("1", scalar("SELECT string_agg(recipe_id::text, ',') FROM recipes"));
    }

    private static final String GUILD_AND_PLAIN = """
            [{"id":1,"type":"Refinement","output_item_id":20,"output_item_count":1,"min_rating":0,"time_to_craft_ms":1000,
              "disciplines":["Chef"],"flags":[],"chat_link":"[&A]","ingredients":[{"type":"Item","id":10,"count":1}]},
             {"id":13324,"type":"GuildConsumable","output_item_id":30,"output_item_count":1,"min_rating":0,"time_to_craft_ms":1000,
              "disciplines":["Chef"],"flags":[],"chat_link":"[&B]",
              "ingredients":[{"type":"Item","id":70926,"count":1},{"type":"GuildUpgrade","id":721,"count":8}]}]
            """;

    @Test
    void recipeSync_unchangedCatalogWithASkippedGuildRecipeIsSkippedOnTheSecondRun() throws Exception {
        RecipeSync.fetcher = url -> {
            urls.add(url);
            if (url.equals(RecipeSync.CATALOG_URL)) return json("[1, 13324]");
            if (url.contains("/v2/recipes?ids=")) return json(GUILD_AND_PLAIN);
            return json("[" + itemJson(10) + "," + itemJson(20) + "]");
        };
        ItemSync.fetcher = RecipeSync.fetcher::get;

        assertTrue(RecipeSync.syncAllRecipesGlobalSafe());
        assertEquals("13324", scalar("SELECT skipped_recipe_ids[1] FROM recipe_catalog_publication"));

        urls.clear();
        assertFalse(RecipeSync.syncAllRecipesGlobalSafe(), "an unchanged observed catalog is not re-synced");
        assertEquals(List.of(RecipeSync.CATALOG_URL), urls, "no detail or item fetch on the second run");

        urls.clear();
        RecipeSync.fetcher = url -> {
            urls.add(url);
            if (url.equals(RecipeSync.CATALOG_URL)) return json("[1, 13324, 13325]");
            if (url.contains("/v2/recipes?ids=")) return json(GUILD_AND_PLAIN);
            return json("[" + itemJson(10) + "," + itemJson(20) + "]");
        };
        assertTrue(RecipeSync.syncAllRecipesGlobalSafe(), "a changed upstream catalog triggers a sync");
        assertTrue(urls.size() > 1);
    }

    @Test
    void recipeSync_interruptedSyncNeverPublishesTheSkippedCatalogAsComplete() throws Exception {
        RecipeSync.fetcher = url -> {
            if (url.equals(RecipeSync.CATALOG_URL)) return json("[1, 13324]");
            if (url.contains("/v2/recipes?ids=")) return json(GUILD_AND_PLAIN);
            return json("[" + itemJson(10) + "," + itemJson(20) + "]");
        };
        ItemSync.fetcher = url -> { throw new IOException("items down"); };
        assertThrows(IOException.class, RecipeSync::syncAllRecipesGlobalSafe);

        assertEquals("f", scalar("SELECT complete FROM recipe_catalog_publication"));
        assertEquals("0", scalar("SELECT cardinality(skipped_recipe_ids) FROM recipe_catalog_publication"));
        urls.clear();
        RecipeSync.fetcher = url -> { urls.add(url); return url.equals(RecipeSync.CATALOG_URL) ? json("[1, 13324]") : json(GUILD_AND_PLAIN); };
        ItemSync.fetcher = url -> json("[" + itemJson(10) + "," + itemJson(20) + "]");
        assertTrue(RecipeSync.syncAllRecipesGlobalSafe(), "an unfinished publication still syncs");
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
        // Valid legacy item_id/count ingredients retain their exact quantities.
        assertEquals("1:10:4,2:11:1",
                scalar("SELECT string_agg(recipe_id||':'||item_id||':'||count, ',' ORDER BY recipe_id) FROM recipe_ingredients"));
        assertEquals("10,11,20,21,30", scalar("SELECT string_agg(item_id::text, ',' ORDER BY item_id) FROM items"));
        long detailCalls = urls.stream().filter(url -> url.contains("/v2/recipes?ids=")).count();
        assertEquals(1, detailCalls,
                "Recipe and ingredient publication must share the one fetched payload");
    }

    @Test void recipeSync_reusesEveryDetailBatchAcrossItemAndIngredientPublication() throws Exception {
        int count = SyncConstants.HTTP_IDS_BATCH * 2 + 1;
        RecipeSync.fetcher = url -> {
            urls.add(url);
            if (url.equals(RecipeSync.CATALOG_URL)) return json(range(1, count).toString());
            return json(java.util.Arrays.stream(idsOf(url).split(",")).map(Integer::parseInt)
                    .map(id -> "{\"id\":" + id + ",\"type\":\"Refinement\",\"output_item_id\":" + (10000 + id)
                            + ",\"output_item_count\":6,\"ingredients\":[{\"item_id\":" + (20000 + id) + ",\"count\":5}]}")
                    .collect(Collectors.joining(",", "[", "]")));
        };
        ItemSync.fetcher = url -> json(java.util.Arrays.stream(idsOf(url).split(","))
                .map(Integer::parseInt).map(SyncFetchPublicationTest::itemJson)
                .collect(Collectors.joining(",", "[", "]")));
        assertTrue(RecipeSync.syncAllRecipesGlobalSafe());
        assertEquals(3, urls.stream().filter(url -> url.contains("?ids=")).count());
        assertEquals(4, new HashSet<>(urls).size(), "Each batch downloaded exactly once plus the ID list");
        assertEquals(String.valueOf(count), scalar("SELECT count(*) FROM recipes WHERE output_item_count=6"));
        assertEquals(String.valueOf(count), scalar("SELECT count(*) FROM recipe_ingredients WHERE count=5 AND item_id=20000+recipe_id"));
        assertEquals(String.valueOf(count * 2), scalar("SELECT count(*) FROM items"));
        assertEquals("t", scalar("SELECT complete FROM recipe_catalog_publication"));
        assertFalse(RecipeSync.syncAllRecipesGlobalSafe());
        assertEquals(3, urls.stream().filter(url -> url.contains("?ids=")).count());
    }

    @Test
    void recipeSync_unchangedCatalogFetchesNothingFurtherAndWritesNothing() throws Exception {
        exec("INSERT INTO recipes (recipe_id, type) VALUES (1, 'Keep'), (2, 'Keep')");
        exec("INSERT INTO recipe_catalog_publication (complete) VALUES (TRUE)");
        RecipeSync.fetcher = url -> {
            urls.add(url);
            if (url.equals(RecipeSync.CATALOG_URL)) return json("[1,2]");
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
            if (url.equals(RecipeSync.CATALOG_URL)) return json("[1]");
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
        RecipeSync.fetcher = url -> { urls.add(url); if (url.equals(RecipeSync.CATALOG_URL)) return json("[1,2]"); throw new AssertionError(url); };
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
        RecipeSync.fetcher = url -> url.equals(RecipeSync.CATALOG_URL) ? json("[1]")
                : json("[{\"id\":1,\"ingredients\":[{\"item_id\":0,\"count\":1}]}]");
        ItemSync.fetcher = url -> { throw new AssertionError("no valid item IDs to fetch: " + url); };

        assertTrue(RecipeSync.syncAllRecipesGlobalSafe());
        assertEquals("0", scalar("SELECT count(*) FROM items"));
        assertEquals("0", scalar("SELECT count(*) FROM recipe_ingredients"));
        assertNull(scalar("SELECT output_item_id FROM recipes WHERE recipe_id=1"), "invalid legacy ingredients quarantine the recipe");
        assertEquals("f", scalar("SELECT complete FROM recipe_catalog_publication"));
    }

    private static String currencyRecipe(int id, String currencyIngredient) {
        return "{\"id\":" + id + ",\"type\":\"Refinement\",\"output_item_id\":20,\"output_item_count\":1,\"min_rating\":0,"
                + "\"time_to_craft_ms\":1000,\"disciplines\":[\"Chef\"],\"flags\":[],\"chat_link\":\"[&A]\",\"ingredients\":["
                + "{\"type\":\"Item\",\"id\":10,\"count\":1}" + (currencyIngredient == null ? "" : "," + currencyIngredient) + "]}";
    }

    @Test
    void recipeSync_malformedTypedOrLegacyRequirementQuarantinesTheWholeRecipeAndValidOnePublishes() throws Exception {
        String[] bad = {
                "{\"type\":\"Currency\",\"count\":30}",
                "{\"type\":\"Currency\",\"id\":61}",
                "{\"type\":\"Currency\",\"id\":61,\"count\":0}",
                "{\"type\":\"Currency\",\"id\":61,\"count\":-3}",
                "{\"type\":\"Currency\",\"id\":0,\"count\":3}",
                "{\"type\":\"Currency\",\"id\":61,\"count\":3000000000}",
                "{\"type\":\"Currency\",\"id\":3000000000,\"count\":3}",
                "{\"type\":null,\"id\":61,\"count\":30}",
                "null",
                "7",
                "{\"id\":61,\"count\":30}",
                "{\"item_id\":0,\"count\":1}",
                "{\"item_id\":-1,\"count\":1}",
                "{\"item_id\":null,\"count\":1}",
                "{\"item_id\":1.5,\"count\":1}",
                "{\"item_id\":3000000000,\"count\":1}",
                "{\"item_id\":11,\"count\":0}",
                "{\"item_id\":11,\"count\":-1}",
                "{\"item_id\":11,\"count\":null}",
                "{\"item_id\":11,\"count\":1.5}",
                "{\"item_id\":11,\"count\":\"1\"}",
                "{\"item_id\":11,\"count\":3000000000}",
                "{\"item_id\":\"11\",\"count\":1}",
                "{\"item_id\":10,\"count\":2}", // duplicates typed Item 10 already in this recipe
                "{\"item_id\":11,\"count\":1},{\"item_id\":11,\"count\":2}"};
        StringBuilder body = new StringBuilder("[").append(currencyRecipe(1, "{\"type\":\"Currency\",\"id\":61,\"count\":30}"));
        StringBuilder ids = new StringBuilder("[1");
        for (int i = 0; i < bad.length; i++) {
            body.append(',').append(currencyRecipe(100 + i, bad[i]));
            ids.append(',').append(100 + i);
        }
        String detail = body.append(']').toString();
        RecipeSync.fetcher = url -> {
            if (url.equals(RecipeSync.CATALOG_URL)) return json(ids.append(']').toString());
            if (url.contains("/v2/recipes?ids=")) return json(detail);
            return json("[" + itemJson(10) + "," + itemJson(20) + "]");
        };
        ItemSync.fetcher = RecipeSync.fetcher::get;

        assertTrue(RecipeSync.syncAllRecipesGlobalSafe());

        assertEquals("1", scalar("SELECT string_agg(recipe_id::text, ',') FROM recipes"), "only the valid recipe publishes");
        assertEquals("1", scalar("SELECT count(*) FROM recipe_non_item_requirements WHERE recipe_id=1 AND ref_id=61 AND count=30"));
        assertEquals("0", scalar("SELECT count(*) FROM recipe_non_item_requirements WHERE recipe_id>=100"));
        assertEquals("0", scalar("SELECT count(*) FROM recipe_ingredients WHERE recipe_id>=100"));
        assertEquals("f", scalar("SELECT complete FROM recipe_catalog_publication"));
        assertEquals("0", scalar("SELECT cardinality(skipped_recipe_ids) FROM recipe_catalog_publication"),
                "transient malformed details are not recorded as a completed skipped catalog");
    }

    @Test
    void recipeSync_missingNullOrNonArrayIngredientCollectionQuarantinesSeededCurrencyRecipe() throws Exception {
        String head = "{\"id\":%d,\"type\":\"Refinement\",\"output_item_id\":20,\"output_item_count\":1,\"min_rating\":0,"
                + "\"time_to_craft_ms\":1000,\"disciplines\":[\"Chef\"],\"flags\":[],\"chat_link\":\"[&A]\"%s}";
        String[] tails = {"", ",\"ingredients\":null", ",\"ingredients\":7", ",\"ingredients\":{\"type\":\"Currency\",\"id\":61,\"count\":30}"};
        for (int i = 0; i < tails.length; i++) {
            int id = 400 + i;
            exec("INSERT INTO recipes (recipe_id, type) VALUES (" + id + ", 'Old')");
            exec("INSERT INTO recipe_non_item_requirements (recipe_id, kind, ref_id, count) VALUES (" + id + ", 'Currency', 61, 30)");
        }
        StringBuilder body = new StringBuilder("[").append(currencyRecipe(1, "{\"type\":\"Currency\",\"id\":61,\"count\":30}"));
        StringBuilder ids = new StringBuilder("[1");
        for (int i = 0; i < tails.length; i++) {
            body.append(',').append(String.format(head, 400 + i, tails[i]));
            ids.append(',').append(400 + i);
        }
        String detail = body.append(']').toString();
        RecipeSync.fetcher = url -> {
            if (url.equals(RecipeSync.CATALOG_URL)) return json(ids.append(']').toString());
            if (url.contains("/v2/recipes?ids=")) return json(detail);
            return json("[" + itemJson(10) + "," + itemJson(20) + "]");
        };
        ItemSync.fetcher = RecipeSync.fetcher::get;

        assertTrue(RecipeSync.syncAllRecipesGlobalSafe());

        assertEquals("1", scalar("SELECT string_agg(recipe_id::text, ',') FROM recipes"), "malformed collections never publish or clear to free");
        assertEquals("f", scalar("SELECT complete FROM recipe_catalog_publication"));
        assertEquals("0", scalar("SELECT cardinality(skipped_recipe_ids) FROM recipe_catalog_publication"));
    }

    @Test
    void recipeSync_mixedShadowFieldsPublishCanonicalTypedIdentityAndInvalidCanonicalIdIsNotRescued() throws Exception {
        exec("INSERT INTO items (item_id, name) VALUES (10, 'Old item')");
        exec("INSERT INTO recipes (recipe_id, type) VALUES (300, 'Old')");
        exec("INSERT INTO recipe_ingredients (recipe_id, item_id, count) VALUES (300, 10, 99)");
        exec("INSERT INTO recipe_non_item_requirements (recipe_id, kind, ref_id, count) VALUES (300, 'Currency', 61, 99)");
        String mixed = "{\"type\":\"Item\",\"id\":10,\"item_id\":0,\"count\":3},"
                + "{\"type\":\"Currency\",\"id\":61,\"item_id\":20,\"count\":30}";
        String invalid = "{\"id\":301,\"type\":\"Refinement\",\"output_item_id\":20,\"output_item_count\":1,"
                + "\"min_rating\":0,\"time_to_craft_ms\":1000,\"disciplines\":[],\"flags\":[],\"chat_link\":\"[&A]\","
                + "\"ingredients\":[{\"type\":\"Item\",\"id\":0,\"item_id\":10,\"count\":1}]}";
        String detail = "[" + currencyRecipe(300, mixed).replace("{\"type\":\"Item\",\"id\":10,\"count\":1},", "") + "," + invalid + "]";
        RecipeSync.fetcher = url -> json(url.equals(RecipeSync.CATALOG_URL) ? "[300,301]" : detail);
        ItemSync.fetcher = url -> json("[" + itemJson(10) + "," + itemJson(20) + "]");

        assertTrue(RecipeSync.syncAllRecipesGlobalSafe());

        assertEquals("10:3", scalar("SELECT string_agg(item_id||':'||count, ',') FROM recipe_ingredients WHERE recipe_id=300"));
        assertEquals("61:30", scalar("SELECT string_agg(ref_id||':'||count, ',') FROM recipe_non_item_requirements WHERE recipe_id=300"));
        assertEquals("0", scalar("SELECT count(*) FROM recipes WHERE recipe_id=301"), "invalid canonical id is quarantined");
        assertEquals("0", scalar("SELECT count(*) FROM recipe_ingredients WHERE recipe_id=301"));
    }

    @Test
    void recipeSync_unknownAndMixedKindsRemainRetryableWhileKnownGuildSkipsBecomeComplete() throws Exception {
        exec("INSERT INTO items (item_id, name) VALUES (10, 'Old item')");
        for (int id = 300; id <= 302; id++) {
            exec("INSERT INTO recipes (recipe_id, type) VALUES (" + id + ", 'Old')");
            exec("INSERT INTO recipe_ingredients (recipe_id, item_id, count) VALUES (" + id + ", 10, 99)");
            exec("INSERT INTO recipe_non_item_requirements (recipe_id, kind, ref_id, count) VALUES (" + id + ", 'Currency', 61, 30)");
        }
        String catalog = "[1,13324,300,301,302]";
        String guild = "{\"type\":\"GuildUpgrade\",\"id\":721,\"count\":8}";
        String unknown = "{\"type\":\"Mystery\",\"id\":61,\"count\":30}";
        String prefix = "[" + currencyRecipe(1, null) + "," + currencyRecipe(13324, guild);
        String malformed = prefix + "," + currencyRecipe(300, unknown)
                + "," + currencyRecipe(301, guild + "," + unknown)
                + "," + currencyRecipe(302, "{\"type\":5,\"id\":61,\"count\":30}") + "]";
        int[] detailRequests = {0};
        RecipeSync.fetcher = url -> {
            if (url.equals(RecipeSync.CATALOG_URL)) return json(catalog);
            detailRequests[0]++;
            return json(malformed);
        };
        ItemSync.fetcher = url -> json("[" + itemJson(10) + "," + itemJson(20) + "]");
        assertTrue(RecipeSync.syncAllRecipesGlobalSafe());
        assertEquals("1", scalar("SELECT string_agg(recipe_id::text, ',') FROM recipes"));
        assertEquals("0", scalar("SELECT count(*) FROM recipe_ingredients WHERE recipe_id>=300"));
        assertEquals("0", scalar("SELECT count(*) FROM recipe_non_item_requirements WHERE recipe_id>=300"));
        assertEquals("f", scalar("SELECT complete FROM recipe_catalog_publication"));
        assertEquals("0", scalar("SELECT cardinality(skipped_recipe_ids) FROM recipe_catalog_publication"));

        String corrected = prefix + "," + java.util.stream.IntStream.rangeClosed(300, 302)
                .mapToObj(id -> currencyRecipe(id, "{\"type\":\"Currency\",\"id\":61,\"count\":60}"))
                .collect(java.util.stream.Collectors.joining(",")) + "]";
        RecipeSync.fetcher = url -> {
            if (url.equals(RecipeSync.CATALOG_URL)) return json(catalog);
            detailRequests[0]++;
            return json(corrected);
        };
        assertTrue(RecipeSync.syncAllRecipesGlobalSafe(), "unknown kinds must not establish a permanent skip");
        assertEquals("3", scalar("SELECT count(*) FROM recipe_ingredients WHERE recipe_id>=300 AND item_id=10 AND count=1"));
        assertEquals("3", scalar("SELECT count(*) FROM recipe_non_item_requirements WHERE recipe_id>=300 AND ref_id=61 AND count=60"));
        assertEquals("t", scalar("SELECT complete FROM recipe_catalog_publication"));
        assertEquals("{13324}", scalar("SELECT skipped_recipe_ids::text FROM recipe_catalog_publication"));
        assertFalse(RecipeSync.syncAllRecipesGlobalSafe());
        assertEquals(2, detailRequests[0], "only the now-complete known Guild skip participates in the fast path");
    }

    @Test
    void recipeSync_emptyItemRequirementsAreQuarantinedAndRetriedWithTheSameCatalog() throws Exception {
        exec("INSERT INTO items (item_id, name) VALUES (10, 'Old item')");
        exec("INSERT INTO recipes (recipe_id, type) VALUES (300, 'Old')");
        exec("INSERT INTO recipe_ingredients (recipe_id, item_id, count) VALUES (300, 10, 99)");
        exec("INSERT INTO recipe_non_item_requirements (recipe_id, kind, ref_id, count) VALUES (300, 'Currency', 61, 30)");
        String empty = "{\"id\":300,\"type\":\"Refinement\",\"output_item_id\":20,\"output_item_count\":1,\"ingredients\":[]}";
        RecipeSync.fetcher = url -> json(url.equals(RecipeSync.CATALOG_URL) ? "[300]" : "[" + empty + "]");
        ItemSync.fetcher = url -> json("[" + itemJson(10) + "," + itemJson(20) + "]");

        assertTrue(RecipeSync.syncAllRecipesGlobalSafe());
        assertEquals("0", scalar("SELECT count(*) FROM recipes WHERE recipe_id=300"));
        assertEquals("0", scalar("SELECT count(*) FROM recipe_ingredients WHERE recipe_id=300"));
        assertEquals("0", scalar("SELECT count(*) FROM recipe_non_item_requirements WHERE recipe_id=300"));
        assertEquals("f", scalar("SELECT complete FROM recipe_catalog_publication"));
        assertEquals("0", scalar("SELECT cardinality(skipped_recipe_ids) FROM recipe_catalog_publication"));

        RecipeSync.fetcher = url -> json(url.equals(RecipeSync.CATALOG_URL) ? "[300]"
                : "[" + currencyRecipe(300, "{\"type\":\"Currency\",\"id\":61,\"count\":60}") + "]");
        assertTrue(RecipeSync.syncAllRecipesGlobalSafe(), "the unchanged catalog must retry corrected requirements");
        assertEquals("1", scalar("SELECT count FROM recipe_ingredients WHERE recipe_id=300 AND item_id=10"));
        assertEquals("61:60", scalar("SELECT ref_id||':'||count FROM recipe_non_item_requirements WHERE recipe_id=300"));
        assertEquals("t", scalar("SELECT complete FROM recipe_catalog_publication"));
        assertFalse(RecipeSync.syncAllRecipesGlobalSafe());
    }

    @Test
    void recipeSync_omittedOrUnparseableDetailRecordsAreQuarantinedNotClearedToFree() throws Exception {
        exec("INSERT INTO recipes (recipe_id, type) VALUES (300, 'Old')");
        exec("INSERT INTO recipe_non_item_requirements (recipe_id, kind, ref_id, count) VALUES (300, 'Currency', 61, 30)");
        String detail = "[" + currencyRecipe(1, "{\"type\":\"Currency\",\"id\":61,\"count\":30}")
                + ",{\"id\":\"bad\"}]";
        RecipeSync.fetcher = url -> {
            if (url.equals(RecipeSync.CATALOG_URL)) return json("[1,2,300]");
            if (url.contains("/v2/recipes?ids=")) return json(detail);
            return json("[" + itemJson(10) + "," + itemJson(20) + "]");
        };
        ItemSync.fetcher = RecipeSync.fetcher::get;

        assertTrue(RecipeSync.syncAllRecipesGlobalSafe());

        assertEquals("1", scalar("SELECT string_agg(recipe_id::text, ',') FROM recipes"),
                "omitted/unparseable ids never remain as requirement-free recipes");
        assertEquals("0", scalar("SELECT count(*) FROM recipe_non_item_requirements WHERE recipe_id=300"));
        assertEquals("1", scalar("SELECT count(*) FROM recipe_non_item_requirements WHERE recipe_id=1 AND ref_id=61"));
        assertEquals("f", scalar("SELECT complete FROM recipe_catalog_publication"));
        assertEquals("0", scalar("SELECT cardinality(skipped_recipe_ids) FROM recipe_catalog_publication"));

        RecipeSync.fetcher = url -> {
            urls.add(url);
            if (url.equals(RecipeSync.CATALOG_URL)) return json("[1,2,300]");
            return json("[" + currencyRecipe(1, null) + "," + currencyRecipe(2, null) + ","
                    + currencyRecipe(300, "{\"type\":\"Currency\",\"id\":61,\"count\":60}") + "]");
        };
        ItemSync.fetcher = url -> json("[" + itemJson(10) + "," + itemJson(20) + "]");
        assertTrue(RecipeSync.syncAllRecipesGlobalSafe(), "same IDs retry omitted/unparseable details");
        assertEquals("1,2,300", scalar("SELECT string_agg(recipe_id::text, ',' ORDER BY recipe_id) FROM recipes"));
        assertEquals("60", scalar("SELECT count FROM recipe_non_item_requirements WHERE recipe_id=300 AND ref_id=61"));
        assertEquals("t", scalar("SELECT complete FROM recipe_catalog_publication"));
        urls.clear();
        assertFalse(RecipeSync.syncAllRecipesGlobalSafe(), "a corrected complete publication uses the fast path again");
        assertEquals(List.of(RecipeSync.CATALOG_URL), urls);
    }

    @Test
    void recipeSync_malformedDetailsAreRetriedWithUnchangedCatalogIds() throws Exception {
        RecipeSync.fetcher = url -> url.equals(RecipeSync.CATALOG_URL) ? json("[1]")
                : json("[" + currencyRecipe(1, "{\"type\":\"Currency\",\"id\":61,\"count\":0}") + "]");
        ItemSync.fetcher = url -> json("[" + itemJson(10) + "," + itemJson(20) + "]");
        assertTrue(RecipeSync.syncAllRecipesGlobalSafe());
        assertEquals("f", scalar("SELECT complete FROM recipe_catalog_publication"));

        RecipeSync.fetcher = url -> url.equals(RecipeSync.CATALOG_URL) ? json("[1]")
                : json("[" + currencyRecipe(1, "{\"type\":\"Currency\",\"id\":61,\"count\":30}") + "]");
        assertTrue(RecipeSync.syncAllRecipesGlobalSafe());
        assertEquals("30", scalar("SELECT count FROM recipe_non_item_requirements WHERE recipe_id=1 AND ref_id=61"));
        assertEquals("t", scalar("SELECT complete FROM recipe_catalog_publication"));
        assertFalse(RecipeSync.syncAllRecipesGlobalSafe());
    }

    @Test
    void recipeSync_duplicateDetailsQuarantineAllVersionsThenRetryTheSameCatalog() throws Exception {
        exec("INSERT INTO items (item_id, name) VALUES (10, 'Old item')");
        exec("INSERT INTO recipes (recipe_id, type) VALUES (300, 'Old')");
        exec("INSERT INTO recipe_ingredients (recipe_id, item_id, count) VALUES (300, 10, 99)");
        exec("INSERT INTO recipe_non_item_requirements (recipe_id, kind, ref_id, count) VALUES (300, 'Currency', 61, 30)");
        String first = currencyRecipe(300, "{\"type\":\"Item\",\"id\":11,\"count\":2},{\"type\":\"Currency\",\"id\":61,\"count\":40}");
        String second = currencyRecipe(300, "{\"type\":\"Item\",\"id\":12,\"count\":3},{\"type\":\"Currency\",\"id\":50,\"count\":5}");
        RecipeSync.fetcher = url -> url.equals(RecipeSync.CATALOG_URL) ? json("[1,300]")
                : json("[" + currencyRecipe(1, null) + "," + first + "," + second + "]");
        ItemSync.fetcher = url -> json("[" + itemJson(10) + "," + itemJson(20) + "]");

        assertTrue(RecipeSync.syncAllRecipesGlobalSafe());
        assertEquals("1", scalar("SELECT string_agg(recipe_id::text, ',') FROM recipes"));
        assertEquals("0", scalar("SELECT count(*) FROM recipe_ingredients WHERE recipe_id=300"));
        assertEquals("0", scalar("SELECT count(*) FROM recipe_non_item_requirements WHERE recipe_id=300"));
        assertEquals("f", scalar("SELECT complete FROM recipe_catalog_publication"));
        assertEquals("0", scalar("SELECT cardinality(skipped_recipe_ids) FROM recipe_catalog_publication"));

        RecipeSync.fetcher = url -> {
            urls.add(url);
            return url.equals(RecipeSync.CATALOG_URL) ? json("[1,300]")
                    : json("[" + currencyRecipe(1, null) + "," + currencyRecipe(300,
                            "{\"type\":\"Currency\",\"id\":61,\"count\":60}") + "]");
        };
        assertTrue(RecipeSync.syncAllRecipesGlobalSafe(), "a duplicate never establishes a permanent skipped id");
        assertEquals("1", scalar("SELECT count FROM recipe_ingredients WHERE recipe_id=300 AND item_id=10"));
        assertEquals("61:60", scalar("SELECT ref_id||':'||count FROM recipe_non_item_requirements WHERE recipe_id=300"));
        assertEquals("t", scalar("SELECT complete FROM recipe_catalog_publication"));
        urls.clear();
        assertFalse(RecipeSync.syncAllRecipesGlobalSafe());
        assertEquals(List.of(RecipeSync.CATALOG_URL), urls);
    }

    @Test
    void completedPublicationInvalidatesModernDiskGraphEvenWhenRowCountsStayEqual() throws Exception {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("gw2-publication-graph", ".json");
        java.nio.file.Files.delete(file);
        String prior = System.getProperty(repo.CraftingGraphCache.TEST_CACHE_FILE_PROPERTY);
        System.setProperty(repo.CraftingGraphCache.TEST_CACHE_FILE_PROPERTY, file.toString());
        try {
            RecipeSync.fetcher = url -> url.equals(RecipeSync.CATALOG_URL) ? json("[1]")
                    : json("[" + currencyRecipe(1, "{\"type\":\"Currency\",\"id\":61,\"count\":30}") + "]");
            ItemSync.fetcher = url -> json("[" + itemJson(10) + "," + itemJson(20) + "]");
            RecipeSync.syncAllRecipesFull(api.TaskDeadline.NONE);
            var recipes = new repo.RecipeRepository();
            var items = new repo.ItemRepository();
            var before = new repo.CraftingGraphCache(recipes, items).rebuild();
            String firstPublication = recipes.publicationKey();
            assertEquals(List.of(new craft.CurrencyRequirement(61, 30)), before.getRecipes().get(0).currencies);

            RecipeSync.fetcher = url -> url.equals(RecipeSync.CATALOG_URL) ? json("[1]")
                    : json("[" + currencyRecipe(1, "{\"type\":\"Currency\",\"id\":61,\"count\":60}") + "]");
            RecipeSync.syncAllRecipesFull(api.TaskDeadline.NONE);

            assertEquals(1, recipes.countRecipes());
            assertEquals(1, recipes.countRecipeIngredients());
            assertEquals(1, recipes.countRecipeCurrencyRequirements());
            assertFalse(firstPublication.equals(recipes.publicationKey()));
            assertEquals(List.of(new craft.CurrencyRequirement(61, 60)),
                    new repo.CraftingGraphCache(recipes, items).load().getRecipes().get(0).currencies);
        } finally {
            if (prior == null) System.clearProperty(repo.CraftingGraphCache.TEST_CACHE_FILE_PROPERTY);
            else System.setProperty(repo.CraftingGraphCache.TEST_CACHE_FILE_PROPERTY, prior);
            java.nio.file.Files.deleteIfExists(file);
        }
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
    void tpSync_partialBatchFetchesOnlyMissingIdsIndividuallyAndStoresConfirmedAbsenceAsNoData() throws Exception {
        List<Integer> singles = new ArrayList<>();
        tp(b -> ok(206, "[" + quote(1, 5, 100, 7, 120) + "]"), id -> {
            singles.add(id);
            return id == 2 ? json(quote(2, 3, 30, 4, 40)) : null; // id 3: 404 -> confirmed absence
        });

        TpSync.syncTpPrices(Set.of(1, 2, 3));

        assertEquals(List.of(2, 3), singles);
        assertEquals("1:5/100/7/120,2:3/30/4/40,3:-/-/-/-", prices());
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
        assertEquals("7:7/70/8/75,8:-/-/-/-,9:9/90/10/95", prices());
    }

    @Test
    void tpSync_existingRowIsUpdatedAndFailureOnLaterBatchKeepsEarlierBatch() throws Exception {
        exec("INSERT INTO tp_prices VALUES (1, 1, 1, 1, 1, now() - interval '1 hour')");
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
