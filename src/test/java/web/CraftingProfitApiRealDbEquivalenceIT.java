package web;

import application.CraftingProfitService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import craft.CraftResult;
import craft.CraftingSettings;
import craft.Recipe;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import repo.DiscChoice;
import util.CoinUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STORY-API-001 acceptance criteria 3 and 6 on real data: proves the HTTP boundary reports exactly
 * what the application service computed, on the developer's real PostgreSQL database - same rows,
 * same order, same authoritative values, same blocked states, nothing dropped or re-derived.
 *
 * <p>For each settings combination the endpoint is called over HTTP and
 * {@link CraftingProfitService#reload} is called in process with the identical inputs, then every
 * row is compared field by field. Both runs read the same database moments apart and never write
 * to it; no synchronization runs in between, so a difference means the mapping changed the result,
 * not that the data moved.
 *
 * <p>Excluded from the default {@code ./mvnw test} run by its {@code IT} suffix
 * (TEST_STRATEGY.md §34). Run explicitly:
 * {@code ./mvnw test -Dtest=CraftingProfitApiRealDbEquivalenceIT -DfailIfNoSpecifiedTests=false}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CraftingProfitApiRealDbEquivalenceIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Test
    void httpResponseMatchesTheInProcessCalculationForTheDefaultAllScope() throws Exception {
        assertEquivalent("default All (empty request body)",
                "{}",
                DiscChoice.all(),
                new CraftingSettings(true, false, CoinUtils.parseToCopper("1g"), false, false, true));
    }

    @Test
    void httpResponseMatchesTheInProcessCalculationWithBuyingEnabled() throws Exception {
        assertEquivalent("All, buying enabled at 10g, listing prices",
                """
                        {"settings": {"useOwnMats": true, "allowBuying": true, "maxBuyCopper": 100000,
                                      "listingSell": true, "listingBuy": true,
                                      "allowDailyCrafts": false}}""",
                DiscChoice.all(),
                new CraftingSettings(true, true, CoinUtils.parseToCopper("10g"), true, true, false));
    }

    private void assertEquivalent(String label, String requestBody, DiscChoice choice, CraftingSettings settings)
            throws Exception {

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/crafting/profit"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), label + ": unexpected status - " + response.body());
        JsonNode rows = JSON.readTree(response.body()).path("rows");

        CraftingProfitService.ProfitData data = new CraftingProfitService().reload(choice, settings);
        List<Recipe> visible = data.visibleRecipes();
        Map<Integer, CraftResult> results = data.resultsByRecipeId();

        assertTrue(visible.size() > 0, label + ": the real database produced no rows to compare");
        assertEquals(visible.size(), rows.size(), label + ": the API dropped or added rows");

        for (int i = 0; i < visible.size(); i++) {
            Recipe recipe = visible.get(i);
            JsonNode row = rows.get(i);
            String where = label + " row " + i + " (recipe " + recipe.recipeId + ")";

            assertEquals(recipe.recipeId, row.path("recipeId").asInt(), where + ": recipe id/order");
            assertEquals(recipe.outputItemId, row.path("outputItemId").asInt(), where + ": output item");

            CraftResult expected = results.get(recipe.recipeId);
            if (expected == null) {
                assertEquals(false, row.path("resultAvailable").asBoolean(), where + ": result availability");
                continue;
            }

            assertEquals(true, row.path("resultAvailable").asBoolean(), where + ": result availability");
            assertEquals(expected.craftableCount, row.path("craftableCount").asInt(), where + ": craftableCount");
            assertEquals(expected.buyCostCopper, row.path("buyCostCopper").asInt(), where + ": buyCostCopper");
            assertEquals(expected.matsSellValueCopper, row.path("matsSellValueCopper").asInt(),
                    where + ": matsSellValueCopper");
            assertEquals(expected.revenueCopper, row.path("revenueCopper").asInt(), where + ": revenueCopper");
            assertEquals(expected.profitCopper, row.path("profitCopper").asInt(), where + ": profitCopper");
            assertEquals(expected.totalProfitCopper, row.path("totalProfitCopper").asInt(),
                    where + ": totalProfitCopper");
            assertEquals(expected.blockedReason.name(), row.path("blockedReason").asText(),
                    where + ": blockedReason");

            assertMissingEquivalent(where + " missingToBuy", expected.missingToBuy, row.path("missingToBuy"));
            assertMissingEquivalent(where + " missingToBuyOne", expected.missingToBuyOne,
                    row.path("missingToBuyOne"));
        }

        System.out.println(label + ": " + visible.size() + " rows equivalent between HTTP and in-process calls");
    }

    private static void assertMissingEquivalent(String where, Map<Integer, Integer> expected, JsonNode actual) {
        assertEquals(expected.size(), actual.size(), where + ": entry count");
        for (JsonNode entry : actual) {
            int itemId = entry.path("itemId").asInt();
            assertEquals(expected.get(itemId), (Integer) entry.path("quantity").asInt(),
                    where + ": quantity for item " + itemId);
        }
    }
}
