package support.fixture;

import application.CraftingDiscoveryService;
import application.CraftingProfitService;
import application.CraftingResolutionDetail;
import application.ProfitInputSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import craft.CraftResult;
import craft.CraftTraceNode;
import craft.CraftingSettings;
import craft.Recipe;
import model.AccountScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import repo.CraftingGraphCache;
import repo.Db;
import repo.DiscChoice;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Default-CI replacement for the retired owner-database equivalence suites
 * ({@code CraftingProfitApiRealDbEquivalenceIT}, {@code CraftingDiscoveryApiRealDbEquivalenceIT},
 * {@code CraftingResolutionApiRealDbIT}): the real HTTP controllers, mappers, account guard and the REAL
 * scoped Profit/Discovery services run over a disposable Flyway schema holding the synthetic fixtures plus a
 * three-level recipe chain, with the proof and refresh edges replaced by the same offline ports as
 * {@link PopulatedFixtureHttpTest}.
 *
 * <p>Every HTTP payload is compared field by field with the in-process scoped calculation for the same
 * verified account (row count/order, each authoritative value, both missing-material maps, JSON null versus
 * zero, and the complete recursive resolution tree including child order). The in-process side is the
 * application service, never a production DTO mapper. Independent hand-written fixture outcomes (A and B share
 * item and recipe ids but differ in stock and knowledge) guard against both sides being wrong together, and
 * prove the comparisons are not vacuous.
 */
@SpringBootTest(classes = web.Gw2ApiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"gw2.account-luck.schema-init=false", "gw2.account-access.enforce=true",
                "gw2.global-refresh.initial-delay-ms=3600000",
                "gw2.owner.account-guid=" + SyntheticFixtures.A, "gw2.test.suite=scoped-calculation-parity"})
@Import(PopulatedFixtureHttpTest.Proofs.class)
class ScopedCalculationHttpParityTest {

    static final String SCHEMA = "kpi_par_" + UUID.randomUUID().toString().replace("-", "");
    static final ObjectMapper JSON = new ObjectMapper();
    static final Duration MAX_AGE = Duration.ofMinutes(15);

    private static final CraftingSettings PROFIT_DEFAULT = new CraftingSettings(true, false, 10000, false, false, false);
    private static final CraftingSettings PROFIT_BUYING = new CraftingSettings(true, true, 100000, true, true, false);
    private static final CraftingSettings PROFIT_DETAIL = new CraftingSettings(true, false, 10000, false, false, true);
    private static final CraftingSettings PROFIT_DEEP = new CraftingSettings(true, true, 10_000_000, false, false, true);
    private static final CraftingSettings DISCOVERY_DEFAULT = new CraftingSettings(true, true, 0, false, false, true);
    private static final CraftingSettings DISCOVERY_LISTING = new CraftingSettings(true, true, 0, true, true, true);
    private static final CraftingSettings DISCOVERY_DETAIL = new CraftingSettings(true, true, 200_000, false, false, true);

    @LocalServerPort int port;
    @Autowired CraftingGraphCache graphCache;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeAll
    static void load() throws Exception {
        PopulatedFixtureIsolationIT.createSchema(SCHEMA);
        FixtureSnapshot base = SyntheticFixtures.build();
        Map<String, List<ObjectNode>> merged = new LinkedHashMap<>();
        for (String t : FixtureSnapshot.ALL_TABLES) merged.put(t, new ArrayList<>(base.rows(t)));
        extendWithThreeLevelChain(merged);
        try (Connection c = PopulatedFixtureIsolationIT.connect()) {
            FixtureLoader.load(c, SCHEMA, new FixtureSnapshot(base.kind(), base.version(), merged, base.expected(),
                    base.sourceRevision(), null));
        }
        System.setProperty(Db.TEST_SCHEMA_PROPERTY, SCHEMA);
        System.setProperty(CraftingGraphCache.TEST_CACHE_FILE_PROPERTY, cacheFile().toString());
    }

    /**
     * Banquet 102 (recipe 20003) needs Feast 101 (recipe 20002) which needs Dish 100 (recipe 20001): item 102
     * resolves through three crafting levels. Neither account knows 20003, so Discovery has something to find
     * for both; A already knows 20002 account-wide, B does not, so their Discovery results differ.
     */
    private static void extendWithThreeLevelChain(Map<String, List<ObjectNode>> t) {
        t.get("items").add(SyntheticFixtures.row("item_id", 102, "name", "Fixture Banquet", "type", "Consumable",
                "rarity", "Fine", "level", 80, "vendor_value", 5, "fetched_at", SyntheticFixtures.FRESH));
        t.get("recipes").add(SyntheticFixtures.row("recipe_id", 20003, "type", "Feast", "output_item_id", 102,
                "output_item_count", 1, "time_to_craft_ms", 1000, "min_rating", 150, "disciplines", List.of("Chef"),
                "flags", List.of(), "fetched_at", SyntheticFixtures.FRESH));
        t.get("recipe_ingredients").add(SyntheticFixtures.row("recipe_id", 20003, "item_id", 101, "count", 1));
        t.get("recipe_ingredients").add(SyntheticFixtures.row("recipe_id", 20003, "item_id", 4, "count", 2));
        t.get("tp_tradeable_items").add(SyntheticFixtures.row("item_id", 102, "fetched_at", SyntheticFixtures.FRESH));
        t.get("tp_prices").add(SyntheticFixtures.row("item_id", 102, "buy_quantity", 500, "buy_unit_price", 9000,
                "sell_quantity", 500, "sell_unit_price", 9900, "fetched_at", SyntheticFixtures.FRESH));
    }

    private static java.nio.file.Path cacheFile() {
        return java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), SCHEMA + "-graph.json");
    }

    @AfterEach
    void noLiveEdgeAndNoWorkerLeft() throws Exception {
        PopulatedFixtureHttpTest.drain();
        assertEquals(List.of(), PopulatedFixtureHttpTest.UNEXPECTED, "no external edge other than the offline price edge");
    }

    @AfterAll
    static void cleanup() throws Exception {
        PopulatedFixtureHttpTest.drain();
        System.clearProperty(Db.TEST_SCHEMA_PROPERTY);
        System.clearProperty(CraftingGraphCache.TEST_CACHE_FILE_PROPERTY);
        java.nio.file.Files.deleteIfExists(cacheFile());
        PopulatedFixtureIsolationIT.drop(SCHEMA);
    }

    // ------------------------------------------------------------------ HTTP principal

    private final class Principal {
        final String account, cookie, context;
        Principal(String account) throws Exception {
            this.account = account;
            HttpResponse<String> r = raw("/api/session", "{}", SyntheticFixtures.keyOf(account), null, null);
            assertEquals(200, r.statusCode(), "synthetic login");
            String set = r.headers().firstValue("Set-Cookie").orElseThrow();
            cookie = set.substring(0, set.indexOf(';'));
            context = r.headers().firstValue("X-Account-Context").orElseThrow();
        }
        AccountScope scope() { return AccountScope.of(UUID.fromString(account)); }
        HttpResponse<String> post(String path, String body) throws Exception { return raw(path, body, null, cookie, context); }
        JsonNode ok(String path, String body) throws Exception {
            HttpResponse<String> r = post(path, body);
            assertEquals(200, r.statusCode(), path + ": " + r.body());
            return JSON.readTree(r.body());
        }
    }

    private HttpResponse<String> raw(String path, String body, String key, String cookie, String context) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Origin", "http://localhost:" + port).header("Content-Type", "application/json");
        if (key != null) b.header("X-GW2-API-Key", key);
        if (cookie != null) b.header("Cookie", cookie).header("X-Account-Context", context);
        b.POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------ in-process scoped oracles

    private CraftingProfitService.ProfitData profit(Principal p, CraftingSettings s) throws Exception {
        try (ProfitInputSession session = ProfitInputSession.open(p.scope())) {
            return new CraftingProfitService(graphCache, session).reload(DiscChoice.all(), s);
        }
    }

    private CraftingResolutionDetail profitDetail(Principal p, int recipeId, CraftingSettings s) throws Exception {
        try (ProfitInputSession session = ProfitInputSession.open(p.scope())) {
            return new CraftingProfitService(graphCache, session).resolveDetail(recipeId, DiscChoice.all(), s);
        }
    }

    private CraftingDiscoveryService discovery(Principal p) {
        return new CraftingDiscoveryService(graphCache).withFreshness(Clock.systemUTC(), MAX_AGE).forScope(p.scope());
    }

    private static String profitBody(String settings) {
        return settings == null ? "{}" : "{\"settings\": " + settings + "}";
    }

    private static String discoveryScope(String character, int rating) {
        return "\"scope\": {\"discipline\": \"Chef\", \"characterName\": \"" + character + "\", \"rating\": " + rating + "}";
    }

    // ------------------------------------------------------------------ Profit table parity

    @Test
    void profitTableMatchesTheScopedCalculationForDefaultAndBuyingSettingsPerAccount() throws Exception {
        Principal a = new Principal(SyntheticFixtures.A);
        Principal b = new Principal(SyntheticFixtures.B);
        String buying = "{\"useOwnMats\": true, \"allowBuying\": true, \"maxBuyCopper\": 100000, "
                + "\"listingSell\": true, \"listingBuy\": true, \"allowDailyCrafts\": false}";

        JsonNode aDefault = a.ok("/api/crafting/profit", profitBody(null));
        JsonNode bDefault = b.ok("/api/crafting/profit", profitBody(null));
        assertEquals("CALCULATED", aDefault.path("status").asText());
        assertTableEquivalent("A default", profit(a, PROFIT_DEFAULT), aDefault.path("rows"));
        assertTableEquivalent("B default", profit(b, PROFIT_DEFAULT), bDefault.path("rows"));
        assertTableEquivalent("A buying", profit(a, PROFIT_BUYING), a.ok("/api/crafting/profit", profitBody(buying)).path("rows"));
        assertTableEquivalent("B buying", profit(b, PROFIT_BUYING), b.ok("/api/crafting/profit", profitBody(buying)).path("rows"));

        // Independent fixture outcomes (hand arithmetic from SyntheticFixtures; see PopulatedFixtureHttpTest):
        // A holds 250 of item 1 and 180 of item 2 => 60 Dishes; B holds 12 and 9 => 3. A knows recipe 20002, B not.
        assertEquals(60, rowOf(aDefault.path("rows"), 20001).path("craftableCount").asInt());
        assertEquals(3, rowOf(bDefault.path("rows"), 20001).path("craftableCount").asInt());
        assertNotNull(rowOf(aDefault.path("rows"), 20002), "A knows the Feast recipe");
        assertNull(rowOf(bDefault.path("rows"), 20002), "B never unlocked the Feast recipe");
        assertEquals("BUYING_DISABLED", rowOf(aDefault.path("rows"), 20001).path("blockedReason").asText(),
                "default settings forbid buying, so the 60th craft is the last own-stock one");
    }

    private void assertTableEquivalent(String label, CraftingProfitService.ProfitData data, JsonNode rows) {
        assertRows(label, data.visibleRecipes(), data.resultsByRecipeId(), rows);
    }

    private static void assertRows(String label, List<Recipe> visible, Map<Integer, CraftResult> results, JsonNode rows) {
        assertTrue(!visible.isEmpty(), label + ": no rows to compare");
        assertEquals(visible.size(), rows.size(), label + ": the API dropped or added rows");
        int positive = 0;
        for (int i = 0; i < visible.size(); i++) {
            Recipe recipe = visible.get(i);
            JsonNode row = rows.get(i);
            String where = label + " row " + i + " (recipe " + recipe.recipeId + ")";
            assertEquals(recipe.recipeId, row.path("recipeId").asInt(), where + ": recipe id/order");
            assertEquals(recipe.outputItemId, row.path("outputItemId").asInt(), where + ": output item");
            assertEquals(recipe.minRating, row.path("minRating").asInt(), where + ": minRating");
            CraftResult expected = results.get(recipe.recipeId);
            if (expected == null) {
                assertEquals(false, row.path("resultAvailable").asBoolean(), where + ": result availability");
                continue;
            }
            assertEquals(true, row.path("resultAvailable").asBoolean(), where + ": result availability");
            assertEquals(expected.craftableCount, row.path("craftableCount").asInt(), where + ": craftableCount");
            assertEquals(expected.buyCostCopper, row.path("buyCostCopper").asInt(), where + ": buyCostCopper");
            assertEquals(expected.matsSellValueCopper, row.path("matsSellValueCopper").asInt(), where + ": matsSellValueCopper");
            assertEquals(expected.revenueCopper, row.path("revenueCopper").asInt(), where + ": revenueCopper");
            assertEquals(expected.profitCopper, row.path("profitCopper").asInt(), where + ": profitCopper");
            assertEquals(expected.totalProfitCopper, row.path("totalProfitCopper").asInt(), where + ": totalProfitCopper");
            assertEquals(expected.blockedReason.name(), row.path("blockedReason").asText(), where + ": blockedReason");
            assertMissing(where + " missingToBuy", expected.missingToBuy, row.path("missingToBuy"));
            assertMissing(where + " missingToBuyOne", expected.missingToBuyOne, row.path("missingToBuyOne"));
            if (expected.craftableCount > 0) positive++;
        }
        assertTrue(positive > 0, label + ": every compared row was zero - the comparison would be vacuous");
    }

    private static void assertMissing(String where, Map<Integer, Integer> expected, JsonNode actual) {
        assertEquals(expected.size(), actual.size(), where + ": entry count");
        for (JsonNode entry : actual) {
            int itemId = entry.path("itemId").asInt();
            assertEquals(expected.get(itemId), (Integer) entry.path("quantity").asInt(), where + ": quantity for item " + itemId);
        }
    }

    private static JsonNode rowOf(JsonNode rows, int recipeId) {
        for (JsonNode r : rows) if (r.path("recipeId").asInt() == recipeId) return r;
        return null;
    }

    // ------------------------------------------------------------------ Discovery table parity

    @Test
    void discoveryTableMatchesTheScopedCalculationForTheSelectedCharacterPerAccount() throws Exception {
        Principal a = new Principal(SyntheticFixtures.A);
        Principal b = new Principal(SyntheticFixtures.B);
        String listing = ", \"settings\": {\"listingSell\": true, \"listingBuy\": true}";

        for (Object[] c : new Object[][] {{a, "Aria", 400}, {b, "Cato", 200}}) {
            Principal p = (Principal) c[0];
            String name = (String) c[1];
            int rating = (Integer) c[2];
            String who = name + " (" + p.account.substring(p.account.length() - 4) + ")";
            DiscChoice choice = DiscChoice.charDiscipline("Chef", rating, name);
            for (Object[] variant : new Object[][] {{"", DISCOVERY_DEFAULT}, {listing, DISCOVERY_LISTING}}) {
                JsonNode body = p.ok("/api/crafting/discovery", "{" + discoveryScope(name, rating) + variant[0] + "}");
                var data = discovery(p).reload(choice, (CraftingSettings) variant[1]);
                assertRows(who + (variant[0].toString().isEmpty() ? " defaults" : " listing prices"),
                        data.visibleRecipes(), data.resultsByRecipeId(), body.path("rows"));
                assertEquals(data.visibleRecipes().size(), body.path("rowCount").asInt(), who + ": rowCount");
            }
        }

        // Independent fixture outcomes: A already knows 20001/20002 account-wide so only the Banquet remains;
        // B knows only 20001 so both Feast and Banquet are still to discover, in that order.
        assertEquals(List.of(20003), recipeIds(a.ok("/api/crafting/discovery", "{" + discoveryScope("Aria", 400) + "}").path("rows")));
        assertEquals(List.of(20002, 20003), recipeIds(b.ok("/api/crafting/discovery", "{" + discoveryScope("Cato", 200) + "}").path("rows")));
        // Another account's character is refused, never served from the other account's rows.
        assertTrue(a.post("/api/crafting/discovery", "{" + discoveryScope("Cato", 200) + "}").statusCode() >= 400);
    }

    private static List<Integer> recipeIds(JsonNode rows) {
        List<Integer> ids = new ArrayList<>();
        for (JsonNode r : rows) ids.add(r.path("recipeId").asInt());
        return ids;
    }

    // ------------------------------------------------------------------ Resolution detail parity

    @Test
    void profitResolutionMatchesTheScopedDetailIncludingTheRecursiveTreeAndTheNotInCalculation404() throws Exception {
        Principal a = new Principal(SyntheticFixtures.A);
        Principal b = new Principal(SyntheticFixtures.B);
        String defaults = "{\"useOwnMats\": true, \"allowBuying\": false, \"maxBuyCopper\": 10000, \"listingSell\": false, "
                + "\"listingBuy\": false, \"allowDailyCrafts\": true}";
        String deep = "{\"useOwnMats\": true, \"allowBuying\": true, \"maxBuyCopper\": 10000000, \"listingSell\": false, "
                + "\"listingBuy\": false, \"allowDailyCrafts\": true}";
        int[] depths = new int[2];

        JsonNode shallow = a.ok("/api/crafting/profit/resolution", profitResolutionBody(20001, defaults));
        assertDetailEquivalent("Profit A recipe 20001", true, profitDetail(a, 20001, PROFIT_DETAIL), shallow);
        // Feast needs a Dish: with buying enabled the resolver descends Feast -> Dish -> materials.
        JsonNode deepA = a.ok("/api/crafting/profit/resolution", profitResolutionBody(20002, deep));
        depths[0] = assertDetailEquivalent("Profit A recipe 20002 buying", true, profitDetail(a, 20002, PROFIT_DEEP), deepA);
        JsonNode bShallow = b.ok("/api/crafting/profit/resolution", profitResolutionBody(20001, defaults));
        assertDetailEquivalent("Profit B recipe 20001", true, profitDetail(b, 20001, PROFIT_DETAIL), bShallow);
        assertTrue(depths[0] >= 3, "the deep Profit case must resolve through at least three tree levels, was " + depths[0]);

        // Same recipe, different accounts: A's own stock covers it, B's does not => genuinely different trees.
        assertNotEquals(shallow.path("tree").path("inventoryQuantity").asInt() + "/" + shallow.path("row").path("craftableCount").asInt(),
                bShallow.path("tree").path("inventoryQuantity").asInt() + "/" + bShallow.path("row").path("craftableCount").asInt(),
                "A and B must not resolve identically");
        assertEquals(60, shallow.path("row").path("craftableCount").asInt());
        assertEquals(3, bShallow.path("row").path("craftableCount").asInt());

        // Recipes outside the account's calculation: unknown id, and a recipe only the other account knows.
        for (Object[] c : new Object[][] {{a, 99999999}, {b, 20002}}) {
            HttpResponse<String> r = ((Principal) c[0]).post("/api/crafting/profit/resolution",
                    profitResolutionBody((Integer) c[1], defaults));
            assertEquals(404, r.statusCode(), r.body());
            JsonNode error = JSON.readTree(r.body());
            assertEquals("RECIPE_NOT_IN_CALCULATION", error.path("error").asText());
            assertTrue(error.path("message").asText().contains(String.valueOf(c[1])));
        }
    }

    private static String profitResolutionBody(int recipeId, String settings) {
        return "{\"recipeId\": " + recipeId + ", \"calculation\": {\"scope\": {\"kind\": \"ALL\"}, \"settings\": " + settings + "}}";
    }

    @Test
    void discoveryResolutionMatchesTheScopedDetailIncludingTheRecursiveTreeAndTheNotInCalculation404() throws Exception {
        Principal a = new Principal(SyntheticFixtures.A);
        Principal b = new Principal(SyntheticFixtures.B);
        String settings = ", \"settings\": {\"useOwnMats\": true, \"allowBuying\": true, \"listingSell\": false, \"listingBuy\": false}";

        // B: Banquet (20003) <- Feast (20002) <- Dish (20001): none of the chain is known, so it is discovered.
        JsonNode bBanquet = b.ok("/api/crafting/discovery/resolution",
                "{\"recipeId\": 20003, \"calculation\": {" + discoveryScope("Cato", 200) + settings + "}}");
        int depth = assertDetailEquivalent("Discovery B recipe 20003", false,
                discovery(b).resolveDetail(20003, DiscChoice.charDiscipline("Chef", 200, "Cato"), DISCOVERY_DETAIL), bBanquet);
        assertTrue(depth >= 3, "the Discovery tree must have real recursive children, depth was " + depth);
        JsonNode bFeast = b.ok("/api/crafting/discovery/resolution",
                "{\"recipeId\": 20002, \"calculation\": {" + discoveryScope("Cato", 200) + settings + "}}");
        assertDetailEquivalent("Discovery B recipe 20002", false,
                discovery(b).resolveDetail(20002, DiscChoice.charDiscipline("Chef", 200, "Cato"), DISCOVERY_DETAIL), bFeast);
        JsonNode aBanquet = a.ok("/api/crafting/discovery/resolution",
                "{\"recipeId\": 20003, \"calculation\": {" + discoveryScope("Aria", 400) + settings + "}}");
        assertDetailEquivalent("Discovery A recipe 20003", false,
                discovery(a).resolveDetail(20003, DiscChoice.charDiscipline("Chef", 400, "Aria"), DISCOVERY_DETAIL), aBanquet);

        // Scoping: A already knows the Feast, so it is not a Discovery candidate for A, but is for B.
        HttpResponse<String> notForA = a.post("/api/crafting/discovery/resolution",
                "{\"recipeId\": 20002, \"calculation\": {" + discoveryScope("Aria", 400) + settings + "}}");
        assertEquals(404, notForA.statusCode(), notForA.body());
        assertEquals("RECIPE_NOT_IN_CALCULATION", JSON.readTree(notForA.body()).path("error").asText());
        HttpResponse<String> unknown = b.post("/api/crafting/discovery/resolution",
                "{\"recipeId\": 99999999, \"calculation\": {" + discoveryScope("Cato", 200) + settings + "}}");
        assertEquals(404, unknown.statusCode(), unknown.body());
        assertTrue(JSON.readTree(unknown.body()).path("message").asText().contains("99999999"));
    }

    // ------------------------------------------------------------------ detail equivalence helpers

    /** Compares the response with the application detail; returns the HTTP tree's depth. */
    private static int assertDetailEquivalent(String where, boolean profit, CraftingResolutionDetail expected, JsonNode actual) {
        assertNotEquals(CraftingResolutionDetail.Status.RECIPE_NOT_IN_CALCULATION, expected.status(), where + ": not in calculation");
        assertEquals(expected.recipeId(), actual.path("recipeId").asInt(), where + ": requested recipe id");
        assertEquals("FRESH_CALCULATION", actual.path("consistency").asText(), where + ": consistency");
        // Contract: Discovery always explains one output requirement; Profit explains the selected result's
        // output quantity, or the first blocked attempt when that result crafts nothing.
        boolean blockedProfit = profit && expected.row() != null && expected.row().craftableCount == 0;
        assertEquals(!profit ? "SINGLE_OUTPUT_REQUIREMENT"
                        : blockedProfit ? "FIRST_BLOCKED_ATTEMPT" : "SELECTED_RESULT_OUTPUT_QUANTITY",
                actual.path("treeBasis").asText(), where + ": tree basis");
        assertEquals(expected.status() == CraftingResolutionDetail.Status.RESULT_UNAVAILABLE ? "RESULT_UNAVAILABLE" : "AVAILABLE",
                actual.path("treeStatus").asText(), where + ": tree status");

        JsonNode row = actual.path("row");
        Recipe recipe = expected.recipe();
        assertEquals(recipe.recipeId, row.path("recipeId").asInt(), where + " row: recipe id");
        assertEquals(recipe.outputItemId, row.path("outputItemId").asInt(), where + " row: output item");
        assertEquals(recipe.outputCount, row.path("outputCount").asInt(), where + " row: output count");
        assertEquals(recipe.minRating, row.path("minRating").asInt(), where + " row: min rating");
        CraftResult result = expected.row();
        if (result == null) {
            assertEquals(false, row.path("resultAvailable").asBoolean(), where + " row: availability");
        } else {
            assertEquals(true, row.path("resultAvailable").asBoolean(), where + " row: availability");
            assertEquals(result.craftableCount, row.path("craftableCount").asInt(), where + " row: craftableCount");
            assertEquals(result.buyCostCopper, row.path("buyCostCopper").asInt(), where + " row: buyCostCopper");
            assertEquals(result.matsSellValueCopper, row.path("matsSellValueCopper").asInt(), where + " row: matsSellValueCopper");
            assertEquals(result.revenueCopper, row.path("revenueCopper").asInt(), where + " row: revenueCopper");
            assertEquals(result.profitCopper, row.path("profitCopper").asInt(), where + " row: profitCopper");
            assertEquals(result.totalProfitCopper, row.path("totalProfitCopper").asInt(), where + " row: totalProfitCopper");
            assertEquals(result.blockedReason.name(), row.path("blockedReason").asText(), where + " row: blockedReason");
        }

        CraftTraceNode root = expected.explanation().root();
        if (root == null) {
            assertTrue(actual.path("tree").isMissingNode() || actual.path("tree").isNull(), where + ": absent result must not become a tree");
            return 0;
        }
        assertNode(where + " root", root, actual.path("tree"), expected.itemNames());
        assertTrue(depth(actual.path("tree")) > 1 || root.children().isEmpty(), where + ": children were dropped");
        return depth(actual.path("tree"));
    }

    private static void assertNode(String where, CraftTraceNode expected, JsonNode actual, Map<Integer, String> names) {
        assertTrue(actual.isObject(), where + ": missing node");
        assertEquals(expected.itemId(), actual.path("itemId").asInt(), where + ": itemId");
        assertEquals(names.get(expected.itemId()), textOrNull(actual.path("itemName")), where + ": itemName");
        assertEquals(expected.requestedQuantity(), actual.path("requestedQuantity").asInt(), where + ": requestedQuantity");
        assertEquals(expected.inventoryQuantity(), actual.path("inventoryQuantity").asInt(), where + ": inventoryQuantity");
        assertEquals(expected.craftedQuantity(), actual.path("craftedQuantity").asInt(), where + ": craftedQuantity");
        assertEquals(expected.boughtQuantity(), actual.path("boughtQuantity").asInt(), where + ": boughtQuantity");
        assertEquals(expected.missingQuantity(), actual.path("missingQuantity").asInt(), where + ": missingQuantity");
        assertEquals(expected.recipeId(), integerOrNull(actual.path("recipeId")), where + ": recipeId (null vs zero)");
        assertEquals(expected.craftCount(), actual.path("craftCount").asInt(), where + ": craftCount");
        assertEquals(expected.producedQuantity(), actual.path("producedQuantity").asInt(), where + ": producedQuantity");
        assertEquals(expected.characterName(), textOrNull(actual.path("characterName")), where + ": characterName");
        assertNames(where + ": methods", expected.methods().stream().map(Enum::name).toList(), actual.path("methods"));
        assertNames(where + ": states", expected.states().stream().map(Enum::name).toList(), actual.path("states"));
        assertNames(where + ": blockedReasons", expected.blockedReasons().stream().map(Enum::name).toList(), actual.path("blockedReasons"));
        assertEquals(expected.cashCostCopper(), integerOrNull(actual.path("cashCostCopper")), where + ": cashCostCopper (null vs zero)");
        assertEquals(expected.opportunityCostCopper(), integerOrNull(actual.path("opportunityCostCopper")), where + ": opportunityCostCopper (null vs zero)");
        assertEquals(expected.effectiveCostCopper(), integerOrNull(actual.path("effectiveCostCopper")), where + ": effectiveCostCopper (null vs zero)");

        JsonNode children = actual.path("children");
        assertEquals(expected.children().size(), children.size(), where + ": child count");
        for (int i = 0; i < expected.children().size(); i++) {
            assertNode(where + " > child " + i + " (item " + expected.children().get(i).itemId() + ")",
                    expected.children().get(i), children.get(i), names);
        }
    }

    private static void assertNames(String where, List<String> expected, JsonNode actual) {
        assertEquals(expected.size(), actual.size(), where + ": entry count");
        for (int i = 0; i < expected.size(); i++) assertEquals(expected.get(i), actual.get(i).asText(), where + ": entry " + i);
    }

    private static Integer integerOrNull(JsonNode n) { return n.isMissingNode() || n.isNull() ? null : n.asInt(); }

    private static String textOrNull(JsonNode n) { return n.isMissingNode() || n.isNull() ? null : n.asText(); }

    private static int depth(JsonNode n) {
        if (n.isMissingNode() || n.isNull()) return 0;
        int deepest = 0;
        for (JsonNode c : n.path("children")) deepest = Math.max(deepest, depth(c));
        return 1 + deepest;
    }
}
