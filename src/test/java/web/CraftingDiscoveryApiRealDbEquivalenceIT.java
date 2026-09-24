package web;

import application.CharacterSelectionService;
import application.CraftingDiscoveryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import craft.CraftResult;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * STORY-API-002 acceptance criterion 3 on real data: proves the HTTP boundary reports exactly what
 * the Discovery application service computed for the same discipline+character scope and the same
 * selected inventory character - same rows, same order, same authoritative values, same blocked
 * states, nothing dropped or re-derived. Fixture-based contract tests cannot prove that on the real
 * recipe/inventory/price data, which is the point of this check (TEST_STRATEGY.md §35.2).
 *
 * <p>The endpoint is called over HTTP and {@link CraftingDiscoveryService#reload} is called in
 * process with identical inputs, then every row is compared field by field. Both runs read the same
 * database moments apart and never write to it; no synchronization runs in between, so a difference
 * means the mapping changed the result, not that the data moved.
 *
 * <p>Excluded from the default {@code ./mvnw test} run by its {@code IT} suffix. Run explicitly:
 * {@code ./mvnw test -Dtest=CraftingDiscoveryApiRealDbEquivalenceIT -DfailIfNoSpecifiedTests=false}
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CraftingDiscoveryApiRealDbEquivalenceIT {

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Test
    void httpResponseMatchesTheInProcessCalculationForARealCraftingCharacter() throws Exception {
        List<CharacterRepository.DiscRow> options = new CharacterSelectionService().getCraftingCharacterOptions();
        if (options.isEmpty()) {
            System.out.println("SKIPPED - no synced crafting character in the real database");
            return;
        }

        CharacterRepository.DiscRow scope = options.get(0);

        // Discovery's own opening settings, with the scope's character also selected as the
        // inventory owner - the binding-aware path the JavaFX page uses.
        assertEquivalent(scope, scope.charName,
                """
                        {"scope": {"discipline": "%s", "characterName": "%s", "rating": %d},
                         "inventoryCharacterName": "%s"}"""
                        .formatted(scope.discipline, scope.charName, scope.rating, scope.charName),
                new CraftingSettings(true, true, 200_000, false, false, false));
    }

    @Test
    void httpResponseMatchesTheInProcessCalculationWithNoInventoryCharacterSelected() throws Exception {
        List<CharacterRepository.DiscRow> options = new CharacterSelectionService().getCraftingCharacterOptions();
        if (options.isEmpty()) {
            System.out.println("SKIPPED - no synced crafting character in the real database");
            return;
        }

        CharacterRepository.DiscRow scope = options.get(0);

        // No inventory character: the service's unfiltered owned-inventory fallback
        // (STORY-DOM-012) must survive the boundary too, listing prices this time.
        assertEquivalent(scope, null,
                """
                        {"scope": {"discipline": "%s", "characterName": "%s", "rating": %d},
                         "settings": {"listingSell": true, "listingBuy": true}}"""
                        .formatted(scope.discipline, scope.charName, scope.rating),
                new CraftingSettings(true, true, 200_000, true, true, false));
    }

    private void assertEquivalent(CharacterRepository.DiscRow scope,
                                  String inventoryCharacter,
                                  String requestBody,
                                  CraftingSettings settings) throws Exception {

        String label = scope.discipline + " lvl " + scope.rating + " - " + scope.charName
                + ", inventory character " + inventoryCharacter;

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/crafting/discovery"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode(), label + ": unexpected status - " + response.body());
        JsonNode rows = JSON.readTree(response.body()).path("rows");

        CraftingDiscoveryService.DiscoveryData data = new CraftingDiscoveryService().reload(
                DiscChoice.charDiscipline(scope.discipline, scope.rating, scope.charName),
                settings,
                inventoryCharacter);

        List<Recipe> visible = data.visibleRecipes();
        Map<Integer, CraftResult> results = data.resultsByRecipeId();

        assertEquals(visible.size(), rows.size(), label + ": the API dropped or added rows");

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
