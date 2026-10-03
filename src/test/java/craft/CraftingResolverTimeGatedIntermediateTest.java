package craft;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CraftingResolverTimeGatedIntermediateTest {
    private static final int XUNLAI_INGOT = 46743;
    private static final int LUMP_OF_MITHRILLIUM = 46742;
    private static final int OLD_MISIDENTIFIED_ID = 70762;
    private static final int MITHRIL_INGOT = 19687;
    private static final int ORICHALCUM_INGOT = 19682;
    private static final int ANCIENT_WOOD_PLANK = 19686;
    private static final int ECTOPLASM = 19721;
    private static final int THERMOCATALYTIC_REAGENT = 46747;
    private static final Recipe XUNLAI_RECIPE = CraftTestFixtures.recipe(7310, XUNLAI_INGOT, 1, List.of(
            new Ingredient(MITHRIL_INGOT, 20), new Ingredient(ORICHALCUM_INGOT, 10),
            new Ingredient(ANCIENT_WOOD_PLANK, 20), new Ingredient(LUMP_OF_MITHRILLIUM, 1)));
    private static final Recipe LUMP_RECIPE = CraftTestFixtures.recipe(7319, LUMP_OF_MITHRILLIUM, 1, List.of(
            new Ingredient(MITHRIL_INGOT, 50), new Ingredient(ECTOPLASM, 1),
            new Ingredient(THERMOCATALYTIC_REAGENT, 10)));
    private static final List<Recipe> RECIPES = List.of(XUNLAI_RECIPE, LUMP_RECIPE);

    @Test
    void usesTheOfficialLumpOutputIdAsDailyAndDoesNotMisclassifyItsRecipeScroll() {
        for (int id : new int[]{43772, 66913, 79795, 79726, 79817, 79790, 46744, 79763,
                66993, 67015, 46742, 66917, 66923, 46740, 46745, 67377}) {
            assertTrue(DailyCrafts.isDailyOutput(id), "time-gated output item " + id);
        }
        for (int id : new int[]{OLD_MISIDENTIFIED_ID, 70773, 70772, 74326, 74315, 66916, 66922,
                67378, 67379, 67380, 70957}) {
            assertFalse(DailyCrafts.isDailyOutput(id), "non-output or unrelated item " + id);
        }
    }

    @Test
    void dailyDisabledBuysTheIntermediateAtItsActualPriceAndKeepsAllOutputsWithinBudget() {
        var inventory = Map.of(LUMP_OF_MITHRILLIUM, 20, MITHRIL_INGOT, 500,
                ORICHALCUM_INGOT, 100, ANCIENT_WOOD_PLANK, 500);
        CraftResult result = plannerResult(false, true, false, 100_000, inventory,
                Map.of(XUNLAI_INGOT, CraftTestFixtures.quote(18_000, null),
                        LUMP_OF_MITHRILLIUM, CraftTestFixtures.quote(null, 13_500),
                        MITHRIL_INGOT, CraftTestFixtures.quote(null, 10),
                        ORICHALCUM_INGOT, CraftTestFixtures.quote(null, 10),
                        ANCIENT_WOOD_PLANK, CraftTestFixtures.quote(null, 10),
                        ECTOPLASM, CraftTestFixtures.quote(null, 10),
                        THERMOCATALYTIC_REAGENT, CraftTestFixtures.quote(null, 10)));

        assertEquals(7, result.craftableCount);
        assertEquals(98_000, result.buyCostCopper);
        assertEquals(1_300, result.profitCopper);
        assertEquals(9_100, result.totalProfitCopper);
        assertEquals(Map.of(LUMP_OF_MITHRILLIUM, 7, MITHRIL_INGOT, 140,
                ORICHALCUM_INGOT, 70, ANCIENT_WOOD_PLANK, 140), result.missingToBuy);
        assertEquals(94_500, result.materialPurchaseCosts.get(LUMP_OF_MITHRILLIUM).totalPriceCopper());
    }

    @Test
    void dailyDisabledAndUnbuyableIntermediateBlocksTheWholePlanEvenWhenItsRecipeInputsAreBuyable() {
        CraftResult result = plannerResult(false, true, false, 0, Map.of(),
                Map.of(XUNLAI_INGOT, CraftTestFixtures.quote(18_000, null),
                        MITHRIL_INGOT, CraftTestFixtures.quote(null, 10),
                        ORICHALCUM_INGOT, CraftTestFixtures.quote(null, 10),
                        ANCIENT_WOOD_PLANK, CraftTestFixtures.quote(null, 10),
                        ECTOPLASM, CraftTestFixtures.quote(null, 10),
                        THERMOCATALYTIC_REAGENT, CraftTestFixtures.quote(null, 10)));

        assertEquals(0, result.craftableCount);
        assertEquals(0, result.totalProfitCopper);
        assertTrue(result.missingToBuy.isEmpty());
    }

    @Test
    void existingIntermediateIsUsableWhenOwnMaterialsAreEnabledWithoutCraftingTheDailyRecipe() {
        CraftResult result = plannerResult(true, false, false, 0,
                Map.of(LUMP_OF_MITHRILLIUM, 1, MITHRIL_INGOT, 20,
                        ORICHALCUM_INGOT, 10, ANCIENT_WOOD_PLANK, 20),
                Map.of(XUNLAI_INGOT, CraftTestFixtures.quote(18_000, null)));

        assertEquals(1, result.craftableCount);
        assertTrue(result.missingToBuy.isEmpty());
        assertTrue(result.totalProfitCopper > 0);
    }

    @Test
    void enabledDailyCraftUsesOneDailyIntermediateThenBuysAdditionalCopies() {
        CraftResult result = plannerResult(false, true, true, 100_000, Map.of(),
                Map.of(XUNLAI_INGOT, CraftTestFixtures.quote(18_000, null),
                        LUMP_OF_MITHRILLIUM, CraftTestFixtures.quote(null, 13_500),
                        MITHRIL_INGOT, CraftTestFixtures.quote(null, 10),
                        ORICHALCUM_INGOT, CraftTestFixtures.quote(null, 10),
                        ANCIENT_WOOD_PLANK, CraftTestFixtures.quote(null, 10),
                        ECTOPLASM, CraftTestFixtures.quote(null, 10),
                        THERMOCATALYTIC_REAGENT, CraftTestFixtures.quote(null, 10)));

        assertEquals(8, result.craftableCount);
        assertEquals(7, result.missingToBuy.get(LUMP_OF_MITHRILLIUM));
        assertEquals(1, result.missingToBuy.get(ECTOPLASM));
        assertEquals(10, result.missingToBuy.get(THERMOCATALYTIC_REAGENT));
        assertTrue(result.totalProfitCopper > 0);
    }

    private static CraftResult plannerResult(boolean useOwnMaterials, boolean allowBuying,
                                             boolean allowDaily, int budget,
                                             Map<Integer, Integer> inventory,
                                             Map<Integer, PriceQuote> prices) {
        CraftingSettings settings = new CraftingSettings(useOwnMaterials, allowBuying, budget,
                false, false, allowDaily);
        return new CraftingPlanner().evaluateOne(XUNLAI_RECIPE, RECIPES, inventory,
                Map.of(), prices, settings, Set.of(7310, 7319));
    }
}
