package craft;

import org.junit.jupiter.api.Test;
import repo.RecipeRepository;
import repo.tp.TpPriceRepository;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Characterization test for KNOWN_PROBLEMS.md section 7.4: under
 * useOwnMats=false/allowBuying=false, CraftingPlanner.evaluateOneRecipeNew may skip
 * simulating a recipe entirely (shouldSimulateRecipe(...), gated by maySkipCheap) based
 * only on each ingredient's DIRECT, non-recursive buy/sell price - never considering
 * that an ingredient might itself be cheaper to craft recursively.
 * <p>
 * This does NOT assert that the skip is correct behavior - it pins down what the planner
 * currently returns for a recipe engineered to trigger that skip, so a future change to
 * the heuristic (e.g. making it recursive) is a deliberate, visible change.
 */
class CraftingPlannerHeuristicSkipCharacterizationTest {

    private static final int OUTPUT_ITEM_ID = 900_001;
    private static final int INTERMEDIATE_ITEM_ID = 900_002;
    private static final int BASE_ITEM_ID = 900_003;

    @Test
    void shouldCurrentlySkipSimulationBasedOnDirectPricesEvenWhenRecursiveCraftingWouldBeCheaper() {
        // Output: sells directly for 500/unit -> shouldSimulateRecipe's "revenue".
        // Intermediate: 1000 to buy directly, but its OWN recipe below crafts it from
        // Base at effectively zero direct cost - i.e. recursively, Intermediate is far
        // cheaper than its direct TP price suggests. shouldSimulateRecipe only ever looks
        // at Intermediate's direct price (1000), never at its recipe, so
        // minCost(1000) >= revenue(500) and the whole Output recipe is skipped outright.
        RecipeRepository.Recipe outputRecipe = CraftTestFixtures.recipe(
                1, OUTPUT_ITEM_ID, List.of(CraftTestFixtures.ingredient(INTERMEDIATE_ITEM_ID, 1)));

        // Base has no ingredients at all, so it can always be "crafted" without buying or
        // owning anything - standing in for a recursively-cheap base material so the
        // Intermediate -> Base chain fully resolves under allowBuying=false/useOwnMats=false.
        RecipeRepository.Recipe baseRecipe = CraftTestFixtures.recipe(3, BASE_ITEM_ID, List.of());
        RecipeRepository.Recipe intermediateRecipe = CraftTestFixtures.recipe(
                2, INTERMEDIATE_ITEM_ID, List.of(CraftTestFixtures.ingredient(BASE_ITEM_ID, 1)));

        List<RecipeRepository.Recipe> recipes = List.of(outputRecipe, intermediateRecipe, baseRecipe);

        Map<Integer, TpPriceRepository.TpQuote> tp = Map.of(
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
                false   // dailyBuyInsteadOfCraft
        );

        Map<Integer, CraftResult> results = new CraftingPlanner().evaluateAll(
                recipes, Map.of(), tp, settings, Set.of(1, 2, 3));

        CraftResult outputResult = results.get(1);

        // Current behavior: the recipe is treated as entirely un-craftable (craftableCount
        // 0, zero total profit), even though profitCopper shows a per-craft profit of 500
        // would exist if a single craft were simulated - the recursive Intermediate<-Base
        // craft path that would make this genuinely profitable is never explored because
        // shouldSimulateRecipe short-circuited on Intermediate's direct buy price alone.
        assertEquals(0, outputResult.craftableCount);
        assertEquals(0, outputResult.totalProfitCopper);
        assertEquals(500, outputResult.profitCopper);
        assertEquals(Map.of(), outputResult.missingToBuy);
    }
}
