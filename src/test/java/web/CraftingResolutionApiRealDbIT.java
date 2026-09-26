package web;

import application.CharacterSelectionService;
import application.CraftingDiscoveryService;
import application.CraftingProfitService;
import application.CraftingResolutionDetail;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import craft.CraftResult;
import craft.CraftTraceNode;
import craft.CraftingSettings;
import craft.Recipe;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import repo.CharacterRepository;
import repo.DiscChoice;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-API-008 acceptance criterion 5 and the equivalence half of the required tests, on the
 * developer's real PostgreSQL database: no {@code gw2tool.test.schema} override, so {@code repo.Db}
 * reaches the same database the JavaFX application uses. Read-only throughout - a resolution detail
 * consumes no persisted inventory and triggers no synchronization.
 *
 * <p>Two things are produced here, which fixtures cannot produce:
 *
 * <ul>
 *   <li><b>Equivalence.</b> The HTTP response is compared field by field - row and the complete
 *       recursive tree, in order - against {@code resolveDetail(...)} called in process with the same
 *       inputs, so the transport layer is shown to report exactly the facts the application produced
 *       on real recipe, inventory and price data. Both are fresh calculations moments apart with no
 *       synchronization in between (§13.2), which is the one caveat on a mismatch.</li>
 *   <li><b>Execution-policy evidence.</b> First and repeat wall-clock timings for the complete HTTP
 *       detail request, with the data scale they were measured at, for the §13.5 / §23 synchronous
 *       versus task decision. This is <em>backend request</em> time only: it is one part of §33's
 *       navigation-to-complete-page budget and proves nothing about a browser page.</li>
 * </ul>
 *
 * <p>Excluded from the default {@code ./mvnw test} run by its {@code IT} suffix
 * (TEST_STRATEGY.md §34). Run explicitly:
 * {@code ./mvnw test -Dtest=CraftingResolutionApiRealDbIT -DfailIfNoSpecifiedTests=false}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CraftingResolutionApiRealDbIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int REPEAT_REQUESTS = 3;

    /** The Profit view's opening settings, as the table route's defaults reproduce them. */
    private static final CraftingSettings PROFIT_DEFAULTS =
            new CraftingSettings(true, false, 10_000, false, false, true);

    /** The Discovery view's opening settings; its daily flag is fixed to false. */
    private static final CraftingSettings DISCOVERY_DEFAULTS =
            new CraftingSettings(true, true, 200_000, false, false, false);

    @LocalServerPort
    private int port;

    @Test
    void profitDetailMatchesTheInProcessOperationAndReportsItsTimings() throws Exception {
        CraftingProfitService.ProfitData table = new CraftingProfitService()
                .reload(DiscChoice.all(), PROFIT_DEFAULTS);

        Integer recipeId = richestVisibleRecipe(table.visibleRecipes(), table.allRecipes());
        if (recipeId == null) {
            System.out.println("SKIPPED - the real database has no visible recipe in the All scope");
            return;
        }

        String body = """
                {"recipeId": %d,
                 "calculation": {"scope": {"kind": "ALL"},
                                 "settings": {"useOwnMats": true, "allowBuying": false,
                                              "maxBuyCopper": 10000, "listingSell": false,
                                              "listingBuy": false, "dailyBuyInsteadOfCraft": true}}}"""
                .formatted(recipeId);

        System.out.println("=== STORY-API-008 Profit resolution detail (real DB) ===");
        System.out.println("Scope: ALL, settings: Profit view defaults (useOwnMats, no buying, 1g budget,"
                + " instant sell/buy, daily bought)");
        System.out.println("Data scale: visibleRecipes=" + table.visibleRecipes().size()
                + " graphRecipes=" + table.allRecipes().size()
                + " selected recipe=" + recipeId);

        JsonNode response = measureAndReport("/api/crafting/profit/resolution", body);

        CraftingResolutionDetail expected = new CraftingProfitService()
                .resolveDetail(recipeId, DiscChoice.all(), PROFIT_DEFAULTS);
        assertEquivalent("Profit recipe " + recipeId, expected, response);
    }

    @Test
    void profitDetailOnADeepRecipeWithBuyingEnabledReportsItsTimings() throws Exception {
        // The default Profit settings forbid buying, which keeps most trees shallow. Buying enabled
        // with a large budget is the case where the resolver really descends, so it is measured
        // separately rather than left as an unmeasured assumption.
        CraftingSettings settings = new CraftingSettings(true, true, 10_000_000, false, false, true);

        CraftingProfitService.ProfitData table = new CraftingProfitService()
                .reload(DiscChoice.all(), settings);

        // Probe a few of the graph's most expandable visible recipes in process until one actually
        // resolves into a multi-level tree, so what gets measured is a real deep resolution rather
        // than an assumed one. The probe only chooses the candidate; the comparison below re-runs the
        // operation from scratch.
        List<Integer> candidates = rankedByIngredientClosure(
                table.visibleRecipes(), table.allRecipes(), 12);
        Integer recipeId = null;
        int bestNodes = -1;
        int probed = 0;
        for (Integer candidate : candidates) {
            CraftingResolutionDetail probe = new CraftingProfitService()
                    .resolveDetail(candidate, DiscChoice.all(), settings);
            probed++;
            CraftTraceNode root = probe.explanation() == null ? null : probe.explanation().root();
            int nodes = nodeCount(root);
            if (nodes > bestNodes) {
                bestNodes = nodes;
                recipeId = candidate;
            }
            if (nodeDepth(root) >= 3) break;
        }
        if (recipeId == null) {
            System.out.println("SKIPPED - the real database has no visible recipe in the All scope");
            return;
        }
        System.out.println("Candidate probe: " + probed + " of " + candidates.size()
                + " most-expandable visible recipes resolved in process; deepest tree had "
                + bestNodes + " nodes");

        String body = """
                {"recipeId": %d,
                 "calculation": {"scope": {"kind": "ALL"},
                                 "settings": {"useOwnMats": true, "allowBuying": true,
                                              "maxBuyCopper": 10000000, "listingSell": false,
                                              "listingBuy": false, "dailyBuyInsteadOfCraft": true}}}"""
                .formatted(recipeId);

        System.out.println("=== STORY-API-008 Profit resolution detail, deep recipe (real DB) ===");
        System.out.println("Scope: ALL, settings: useOwnMats, buying ENABLED, 1000g budget,"
                + " instant sell/buy, daily bought");
        System.out.println("Data scale: visibleRecipes=" + table.visibleRecipes().size()
                + " graphRecipes=" + table.allRecipes().size()
                + " selected recipe=" + recipeId);

        JsonNode response = measureAndReport("/api/crafting/profit/resolution", body);

        CraftingResolutionDetail expected = new CraftingProfitService()
                .resolveDetail(recipeId, DiscChoice.all(), settings);
        assertEquivalent("Profit (deep) recipe " + recipeId, expected, response);
    }

    @Test
    void discoveryDetailMatchesTheInProcessOperationAndReportsItsTimings() throws Exception {
        List<CharacterRepository.DiscRow> options =
                new CharacterSelectionService().getCraftingCharacterOptions();
        if (options.isEmpty()) {
            System.out.println("SKIPPED - no synced crafting character in the real database");
            return;
        }

        CharacterRepository.DiscRow scope = options.get(0);
        DiscChoice choice = DiscChoice.charDiscipline(scope.discipline, scope.rating, scope.charName);

        CraftingDiscoveryService.DiscoveryData table = new CraftingDiscoveryService()
                .reload(choice, DISCOVERY_DEFAULTS, scope.charName);

        Integer recipeId = richestVisibleRecipe(table.visibleRecipes(), table.allRecipes());
        if (recipeId == null) {
            System.out.println("SKIPPED - " + scope.charName + " has nothing left to discover in "
                    + scope.discipline + " at rating " + scope.rating);
            return;
        }

        String body = """
                {"recipeId": %d,
                 "calculation": {"scope": {"discipline": "%s", "characterName": "%s", "rating": %d},
                                 "inventoryCharacterName": "%s",
                                 "settings": {"useOwnMats": true, "allowBuying": true,
                                              "maxBuyCopper": 200000, "listingSell": false,
                                              "listingBuy": false}}}"""
                .formatted(recipeId, scope.discipline, scope.charName, scope.rating, scope.charName);

        System.out.println("=== STORY-API-008 Discovery resolution detail (real DB) ===");
        System.out.println("Scope: " + scope.discipline + " lvl " + scope.rating + " - " + scope.charName
                + ", inventory character " + scope.charName
                + ", settings: Discovery view defaults (buying on, 20g budget, daily fixed false)");
        System.out.println("Data scale: visibleRecipes=" + table.visibleRecipes().size()
                + " graphRecipes=" + table.allRecipes().size()
                + " selected recipe=" + recipeId);

        JsonNode response = measureAndReport("/api/crafting/discovery/resolution", body);

        CraftingResolutionDetail expected = new CraftingDiscoveryService()
                .resolveDetail(recipeId, choice, DISCOVERY_DEFAULTS, scope.charName);
        assertEquivalent("Discovery recipe " + recipeId, expected, response);
    }

    @Test
    void aRecipeOutsideTheCalculationIsA404AndIsMeasuredSeparately() throws Exception {
        // The §13.4 outcome that returns before any price, item or inventory load, measured on its own
        // so its cost is not mistaken for a completed detail's.
        String body = """
                {"recipeId": 99999999, "calculation": {"scope": {"kind": "ALL"}}}""";

        HttpResponse<String> response = send("/api/crafting/profit/resolution", body);

        assertEquals(404, response.statusCode(), "unexpected status - " + response.body());
        JsonNode error = JSON.readTree(response.body());
        assertEquals("RECIPE_NOT_IN_CALCULATION", error.path("error").asText());
        assertTrue(error.path("message").asText().contains("99999999"));

        System.out.println("=== STORY-API-008 not-in-calculation (real DB, All scope) ===");
        long elapsed = measure("/api/crafting/profit/resolution", body, "404 candidate-set check");
        System.out.println("404 path: " + elapsed + " ms (candidate + graph load only, no price/inventory load)");
    }

    // ---------- measurement ----------

    /** Times one first and {@link #REPEAT_REQUESTS} repeat requests, and returns the first body. */
    private JsonNode measureAndReport(String path, String body) throws Exception {
        List<Long> timings = new ArrayList<>();

        HttpResponse<String> first = send(path, body);
        assertEquals(200, first.statusCode(), "unexpected status - " + first.body());
        JsonNode firstBody = JSON.readTree(first.body());

        System.out.println("Response: bytes=" + first.body().getBytes(StandardCharsets.UTF_8).length
                + " treeStatus=" + firstBody.path("treeStatus").asText()
                + " treeNodes=" + countNodes(firstBody.path("tree"))
                + " maxDepth=" + depth(firstBody.path("tree"))
                + " rootRecipeId=" + firstBody.path("tree").path("recipeId").asText("null")
                + " requestedRecipeId=" + firstBody.path("recipeId").asInt());
        System.out.println("Root sourcing: methods=" + firstBody.path("tree").path("methods")
                + " states=" + firstBody.path("tree").path("states")
                + " blockedReasons=" + firstBody.path("tree").path("blockedReasons")
                + " inventory=" + firstBody.path("tree").path("inventoryQuantity").asInt()
                + " crafted=" + firstBody.path("tree").path("craftedQuantity").asInt()
                + " bought=" + firstBody.path("tree").path("boughtQuantity").asInt()
                + " missing=" + firstBody.path("tree").path("missingQuantity").asInt());

        timings.add(measure(path, body, "FIRST (cold JVM/JIT, cold caches)"));
        for (int i = 1; i <= REPEAT_REQUESTS; i++) {
            timings.add(measure(path, body, "REPEAT " + i));
        }

        long max = timings.stream().mapToLong(Long::longValue).max().orElse(0);
        System.out.println("MAXIMUM request time: " + max + " ms  (backend request only; §33's"
                + " seven-second budget also covers transport to a browser and complete page rendering)");
        return firstBody;
    }

    private long measure(String path, String body, String label) throws Exception {
        long start = System.nanoTime();
        HttpResponse<String> response = send(path, body);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println(label + ": " + elapsedMs + " ms (HTTP " + response.statusCode() + ")");
        return elapsedMs;
    }

    private HttpResponse<String> send(String path, String body) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The visible recipe with the most ingredients, so the measured and compared tree is one of this
     * scope's larger ones rather than a trivial leaf. Null when the scope has no usable recipe.
     */
    private static Integer richestVisibleRecipe(List<Recipe> visible, List<Recipe> graphRecipes) {
        Map<Integer, Recipe> graph = new java.util.HashMap<>();
        for (Recipe recipe : graphRecipes) graph.put(recipe.recipeId, recipe);

        return visible.stream()
                .map(recipe -> graph.get(recipe.recipeId))
                .filter(java.util.Objects::nonNull)
                .max(Comparator.comparingInt(recipe -> recipe.ingredients.size()))
                .map(recipe -> recipe.recipeId)
                .orElse(null);
    }

    /**
     * Visible recipes ordered by their estimated ingredient closure in the crafting graph, most
     * expandable first, capped at {@code limit}. A rough, depth-capped estimate over the first recipe
     * found per output item: it only selects <em>which</em> recipes to probe and is never compared
     * against anything, so it models no domain rule and its approximation cannot make an assertion
     * pass.
     */
    private static List<Integer> rankedByIngredientClosure(List<Recipe> visible,
                                                           List<Recipe> graphRecipes,
                                                           int limit) {
        Map<Integer, Recipe> firstByOutput = new java.util.HashMap<>();
        Map<Integer, Recipe> byId = new java.util.HashMap<>();
        for (Recipe recipe : graphRecipes) {
            firstByOutput.putIfAbsent(recipe.outputItemId, recipe);
            byId.put(recipe.recipeId, recipe);
        }

        Map<Integer, Integer> closureByItem = new java.util.HashMap<>();
        Map<Integer, Integer> scoreByRecipe = new java.util.LinkedHashMap<>();
        for (Recipe visibleRecipe : visible) {
            Recipe graphRecipe = byId.get(visibleRecipe.recipeId);
            if (graphRecipe == null) continue;

            int score = 1;
            for (var ingredient : graphRecipe.ingredients) {
                score += closure(ingredient.itemId, firstByOutput, closureByItem, 0);
            }
            scoreByRecipe.put(graphRecipe.recipeId, score);
        }

        return scoreByRecipe.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static int nodeCount(CraftTraceNode node) {
        if (node == null) return 0;
        int count = 1;
        for (CraftTraceNode child : node.children()) count += nodeCount(child);
        return count;
    }

    private static int nodeDepth(CraftTraceNode node) {
        if (node == null) return 0;
        int deepest = 0;
        for (CraftTraceNode child : node.children()) deepest = Math.max(deepest, nodeDepth(child));
        return 1 + deepest;
    }

    private static int closure(int itemId,
                               Map<Integer, Recipe> firstByOutput,
                               Map<Integer, Integer> cache,
                               int depth) {
        if (depth >= 6) return 1;
        Integer cached = cache.get(itemId);
        if (cached != null) return cached;

        Recipe producing = firstByOutput.get(itemId);
        int score = 1;
        if (producing != null) {
            cache.put(itemId, 1); // cycle guard while this item is being expanded
            for (var ingredient : producing.ingredients) {
                score += closure(ingredient.itemId, firstByOutput, cache, depth + 1);
            }
        }
        cache.put(itemId, score);
        return score;
    }

    // ---------- equivalence ----------

    private static void assertEquivalent(String where,
                                        CraftingResolutionDetail expected,
                                        JsonNode actual) {
        assertNotEquals(CraftingResolutionDetail.Status.RECIPE_NOT_IN_CALCULATION, expected.status(),
                where + ": the in-process operation no longer sees this recipe");

        assertEquals(expected.recipeId(), actual.path("recipeId").asInt(), where + ": requested recipe id");
        assertEquals("FRESH_CALCULATION", actual.path("consistency").asText(), where + ": consistency");
        assertEquals("SINGLE_OUTPUT_REQUIREMENT", actual.path("treeBasis").asText(), where + ": tree basis");

        String expectedStatus = expected.status() == CraftingResolutionDetail.Status.RESULT_UNAVAILABLE
                ? "RESULT_UNAVAILABLE" : "AVAILABLE";
        assertEquals(expectedStatus, actual.path("treeStatus").asText(), where + ": tree status");

        assertRowEquivalent(where, expected, actual.path("row"));

        CraftTraceNode expectedRoot = expected.explanation().root();
        if (expectedRoot == null) {
            assertTrue(actual.path("tree").isMissingNode() || actual.path("tree").isNull(),
                    where + ": an absent result must not become a tree");
        } else {
            assertNodeEquivalent(where + " root", expectedRoot, actual.path("tree"), expected.itemNames());
        }

        System.out.println(where + ": HTTP output equivalent to the in-process detail operation ("
                + countNodes(actual.path("tree")) + " nodes compared)");
    }

    private static void assertRowEquivalent(String where,
                                           CraftingResolutionDetail expected,
                                           JsonNode row) {
        Recipe recipe = expected.recipe();
        assertEquals(recipe.recipeId, row.path("recipeId").asInt(), where + " row: recipe id");
        assertEquals(recipe.outputItemId, row.path("outputItemId").asInt(), where + " row: output item");
        assertEquals(recipe.outputCount, row.path("outputCount").asInt(), where + " row: output count");
        assertEquals(recipe.minRating, row.path("minRating").asInt(), where + " row: min rating");

        CraftResult result = expected.row();
        if (result == null) {
            assertEquals(false, row.path("resultAvailable").asBoolean(), where + " row: availability");
            return;
        }

        assertEquals(true, row.path("resultAvailable").asBoolean(), where + " row: availability");
        assertEquals(result.craftableCount, row.path("craftableCount").asInt(), where + " row: craftableCount");
        assertEquals(result.buyCostCopper, row.path("buyCostCopper").asInt(), where + " row: buyCostCopper");
        assertEquals(result.matsSellValueCopper, row.path("matsSellValueCopper").asInt(),
                where + " row: matsSellValueCopper");
        assertEquals(result.revenueCopper, row.path("revenueCopper").asInt(), where + " row: revenueCopper");
        assertEquals(result.profitCopper, row.path("profitCopper").asInt(), where + " row: profitCopper");
        assertEquals(result.totalProfitCopper, row.path("totalProfitCopper").asInt(),
                where + " row: totalProfitCopper");
        assertEquals(result.blockedReason.name(), row.path("blockedReason").asText(),
                where + " row: blockedReason");
    }

    private static void assertNodeEquivalent(String where,
                                             CraftTraceNode expected,
                                             JsonNode actual,
                                             Map<Integer, String> itemNames) {
        assertNotNull(actual, where + ": missing node");
        assertEquals(expected.itemId(), actual.path("itemId").asInt(), where + ": itemId");
        assertEquals(itemNames.get(expected.itemId()),
                actual.path("itemName").isMissingNode() || actual.path("itemName").isNull()
                        ? null : actual.path("itemName").asText(),
                where + ": itemName");
        assertEquals(expected.requestedQuantity(), actual.path("requestedQuantity").asInt(),
                where + ": requestedQuantity");
        assertEquals(expected.inventoryQuantity(), actual.path("inventoryQuantity").asInt(),
                where + ": inventoryQuantity");
        assertEquals(expected.craftedQuantity(), actual.path("craftedQuantity").asInt(),
                where + ": craftedQuantity");
        assertEquals(expected.boughtQuantity(), actual.path("boughtQuantity").asInt(),
                where + ": boughtQuantity");
        assertEquals(expected.missingQuantity(), actual.path("missingQuantity").asInt(),
                where + ": missingQuantity");
        assertEquals(expected.recipeId(), integerOrNull(actual.path("recipeId")), where + ": recipeId");
        assertEquals(expected.craftCount(), actual.path("craftCount").asInt(), where + ": craftCount");
        assertEquals(expected.producedQuantity(), actual.path("producedQuantity").asInt(),
                where + ": producedQuantity");
        assertEquals(expected.characterName(),
                actual.path("characterName").isMissingNode() || actual.path("characterName").isNull()
                        ? null : actual.path("characterName").asText(),
                where + ": characterName");

        assertNames(where + ": methods", expected.methods().stream().map(Enum::name).toList(),
                actual.path("methods"));
        assertNames(where + ": states", expected.states().stream().map(Enum::name).toList(),
                actual.path("states"));
        assertNames(where + ": blockedReasons",
                expected.blockedReasons().stream().map(Enum::name).toList(), actual.path("blockedReasons"));

        assertEquals(expected.cashCostCopper(), integerOrNull(actual.path("cashCostCopper")),
                where + ": cashCostCopper");
        assertEquals(expected.opportunityCostCopper(), integerOrNull(actual.path("opportunityCostCopper")),
                where + ": opportunityCostCopper");
        assertEquals(expected.effectiveCostCopper(), integerOrNull(actual.path("effectiveCostCopper")),
                where + ": effectiveCostCopper");

        JsonNode children = actual.path("children");
        assertEquals(expected.children().size(), children.size(), where + ": child count");
        for (int i = 0; i < expected.children().size(); i++) {
            assertNodeEquivalent(where + " > child " + i + " (item " + expected.children().get(i).itemId() + ")",
                    expected.children().get(i), children.get(i), itemNames);
        }
    }

    private static void assertNames(String where, List<String> expected, JsonNode actual) {
        assertEquals(expected.size(), actual.size(), where + ": entry count");
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), actual.get(i).asText(), where + ": entry " + i);
        }
    }

    private static Integer integerOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asInt();
    }

    private static int countNodes(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return 0;
        int count = 1;
        for (JsonNode child : node.path("children")) count += countNodes(child);
        return count;
    }

    private static int depth(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return 0;
        int deepest = 0;
        for (JsonNode child : node.path("children")) deepest = Math.max(deepest, depth(child));
        return 1 + deepest;
    }
}
