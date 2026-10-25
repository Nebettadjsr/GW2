package craft;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * When both inventory and buying are disabled, recursive crafting remains a valid acquisition
 * path and must not be skipped based on direct Trading Post prices.
 */
class CraftingPlannerRecursiveCraftingWithoutInventoryOrBuyingTest {

    private static final int OUTPUT_ITEM_ID = 900_001;
    private static final int INTERMEDIATE_ITEM_ID = 900_002;
    private static final int BASE_ITEM_ID = 900_003;

    @Test
    void shouldRecursivelyCraftEvenWhenDirectIngredientPricesMakeTheRecipeLookUnprofitable() {
        // Output: sells directly for 500/unit. Intermediate appears to cost 1000 directly,
        // but its recipe below makes it recursively craftable from a free Base.
        Recipe outputRecipe = CraftTestFixtures.recipe(
                1, OUTPUT_ITEM_ID, List.of(CraftTestFixtures.ingredient(INTERMEDIATE_ITEM_ID, 1)));

        // Base has no ingredients at all, so it can always be "crafted" without buying or
        // owning anything - standing in for a recursively-cheap base material so the
        // Intermediate -> Base chain fully resolves under allowBuying=false/useOwnMats=false.
        Recipe baseRecipe = CraftTestFixtures.recipe(3, BASE_ITEM_ID, List.of());
        Recipe intermediateRecipe = CraftTestFixtures.recipe(
                2, INTERMEDIATE_ITEM_ID, List.of(CraftTestFixtures.ingredient(BASE_ITEM_ID, 1)));

        List<Recipe> recipes = List.of(outputRecipe, intermediateRecipe, baseRecipe);

        Map<Integer, PriceQuote> tp = Map.of(
                // Instant-sell value of Output (listingSell=false -> buyUnit) = 500.
                OUTPUT_ITEM_ID, CraftTestFixtures.quote(500, null),
                // Instant-buy cost of Intermediate (listingBuy=false -> sellUnit) = 1000;
                // no buy-order value, so it can't be dismissed as "sell it instead".
                INTERMEDIATE_ITEM_ID, CraftTestFixtures.quote(null, 1000)
        );

        CraftingSettings settings = new CraftingSettings(
                false,  // useOwnMats
                false,  // allowBuying
                0,      // maxBuyCopper (0 = unlimited)
                false,  // listingSell (instant sell)
                false,  // listingBuy (instant buy)
                false   // allowDailyCrafts
        );

        Map<Integer, CraftResult> results = new CraftingPlanner().evaluateAll(
                recipes, Map.of(), tp, settings, Set.of(1, 2, 3));

        CraftResult outputResult = results.get(1);

        assertEquals(250, outputResult.craftableCount);
        assertEquals(425 * 250, outputResult.totalProfitCopper);
        assertEquals(425, outputResult.profitCopper);
        assertEquals(Map.of(), outputResult.missingToBuy);
    }
}
